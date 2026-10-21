/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.sync;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.triplehelix.wpilogmcp.sync.PullManifest.Entry;

/**
 * One step reads at most one block, on one caller's thread. Neither names nor a common header
 * justify resuming: compare every held byte by hash, or the known range's final 64 KiB when
 * the remote cannot execute a hash command. Time and storage are ports, not sleeps or sockets.
 */
public final class FileTransfer implements AutoCloseable {
  public static final int BLOCK_BYTES = 64 * 1024;
  public static final long LISTING_PERIOD_US = 10_000_000;
  /** A lease belongs to I/O completion, not its caller's timeout or one transfer step. */
  @FunctionalInterface public interface Reservation extends AutoCloseable {
    @Override void close();
  }
  public interface Local {
    /** Store adapters reserve the listed remaining bytes before creating or appending payloads. */
    default Reservation reserve(String remoteName, long bytes) throws IOException { return () -> {}; }
    String create(String remoteName) throws IOException;
    long size(String name) throws IOException;
    byte[] read(String name, long offset, int count) throws IOException;
    String prefixHash(String name, long length) throws IOException;
    void append(String name, long offset, byte[] bytes) throws IOException;
    /** Stores opt into batching; ordinary peers keep their existing block checkpoints. */
    default int checkpointBytes() { return BLOCK_BYTES; }
    /** Append must become durable before progress can count it. Implementations may combine queue admission. */
    default void checkpoint(String name, long offset, byte[] bytes, PullManifest progress) throws IOException {
      append(name, offset, bytes); save(progress);
    }
    /** Only an owned staging file may discard bytes a failed checkpoint did not count. */
    default boolean truncate(String name, long length) throws IOException { return false; }
    String rename(String name, String remoteName) throws IOException;
    String archive(String name) throws IOException;
    /** A verified file returns to staging before it grows, so its session never keeps a stale hash. */
    default String resume(Entry entry) throws IOException { return entry.localName(); }
    /** Load through the ordinary reader: pulls require clean EOF; peers preserve imported truncation notes. */
    void verify(String name) throws IOException;
    /** Placement may change the path, but never the verified bytes. */
    String verified(Entry entry) throws IOException;
    void save(PullManifest manifest) throws IOException;
  }
  public enum Status { PAUSED, WAITING, COPIED, VERIFIED, RETRIED, REFUSED, IDLE }
  public record Result(Status status, long waitUs, String remoteName, long bytes, String detail) {}
  private final RemoteFiles remote;
  private final Local local;
  private final BooleanSupplier gate;
  private final LongSupplier clock;
  private final long rateBytes;
  private static final int BUSY = 1, CLOSE_REQUESTED = 2;
  private final AtomicInteger activity = new AtomicInteger();
  private static final class HeldSpace {
    long coveredThrough;
    final List<Reservation> parts = new ArrayList<>();
    HeldSpace(long coveredThrough) { this.coveredThrough = coveredThrough; }
  }
  private final Map<String, HeldSpace> reservations = new HashMap<>();
  private final Map<String, Entry> files = new LinkedHashMap<>();
  private final List<Entry> history;
  private final Map<String, RemoteFiles.File> observed = new HashMap<>();
  private final Map<String, Entry> validated = new HashMap<>();
  private final Map<String, Boolean> renameProof = new HashMap<>();
  private final String serial;
  private volatile PullManifest manifest;
  private volatile int pendingFiles;
  private long nextReadUs, listedAtUs;
  private long readCredit = BLOCK_BYTES * 1_000_000L;
  private long creditedAtUs;
  private boolean creditStarted;
  private int streamReadBytes;
  private final ByteArrayOutputStream buffered = new ByteArrayOutputStream();
  private RemoteFiles.File bufferedFile;
  private long bufferedSinceUs;
  private InputStream input;
  private String inputName;
  private long inputOffset;
  private List<RemoteFiles.File> listing;
  private final Map<String, RemoteFiles.File> previousPass = new HashMap<>();

  public FileTransfer(RemoteFiles remote, Local local, PullManifest manifest,
      long rateBytes, LongSupplier clock, BooleanSupplier gate) {
    if (local.checkpointBytes() < BLOCK_BYTES || local.checkpointBytes() > 1024 * 1024) throw new IllegalArgumentException("Checkpoint must be between 64 KiB and 1 MiB");
    if (rateBytes <= 0) throw new IllegalArgumentException("Rate must be positive");
    this.remote = remote; this.local = local; this.manifest = manifest; this.serial = manifest.serialNumber();
    this.rateBytes = rateBytes; this.clock = clock; this.gate = gate;
    manifest.files().forEach(e -> files.put(e.remoteName(), e)); history = new ArrayList<>(manifest.history());
  }
  public PullManifest manifest() { return manifest; }
  /** Last listing's unfinished files; reading it never lists the robot or touches storage. */
  public int pendingFiles() { return pendingFiles; }

  public Result step() throws IOException {
    if (!activity.compareAndSet(0, BUSY)) throw new IllegalStateException("A transfer step is already running");
    Throwable stepFailure = null;
    try {
      if (!gate.getAsBoolean()) return pause(null);
      checkpointIfDue();
      if (clock.getAsLong() < nextReadUs) return result(Status.WAITING, null, 0, null);
      if (listing == null || clock.getAsLong() - listedAtUs >= LISTING_PERIOD_US) {
        checkpoint(); closeInput();
        var fresh = remote.list().stream().sorted(java.util.Comparator.comparing(RemoteFiles.File::name)).toList();
        var unique = new HashSet<String>();
        for (var item : fresh) if (!unique.add(item.name())) throw new IOException("Duplicate remote name: " + item.name());
        previousPass.clear(); previousPass.putAll(observed); observed.clear();
        fresh.forEach(item -> observed.put(item.name(), item));
        listing = fresh; listedAtUs = clock.getAsLong(); renameProof.clear();
        for (var name : List.copyOf(reservations.keySet())) if (!observed.containsKey(name)) releaseSpace(name);
      }
      var names = observed.keySet();
      boolean needsStablePass = false;
      for (var item : listing) {
        var entry = files.get(item.name());
        if (entry == null) {
          // A rename is unique content evidence among names absent from this listing.
          var renamed = new ArrayList<Entry>();
          for (var held : List.copyOf(files.values())) {
            if (!names.contains(held.remoteName()) && held.bytesCopied() > 0 && held.bytesCopied() <= item.size()
                && reconciledSize(held) == held.bytesCopied()) {
              String key = item + "|" + held;
              Boolean proof = renameProof.get(key);
              if (proof == null) {
                var match = matches(item, held);
                if (match.isEmpty()) return result(Status.WAITING, item.name(), 0, null);
                proof = match.get(); renameProof.put(key, proof);
              }
              if (proof) renamed.add(held);
            }
            if (!gate.getAsBoolean()) return pause(null);
            if (clock.getAsLong() < nextReadUs) return result(Status.WAITING, null, 0, null);
          }
          if (renamed.size() == 1) {
            var old = renamed.get(0); reserveSpace(item, old.bytesCopied());
            String path = local.rename(old.localName(), item.name());
            files.remove(old.remoteName()); validated.remove(old.remoteName());
            entry = old.progress(item, old.bytesCopied(), false, path, old.retries(), null);
            files.put(item.name(), entry); validated.put(item.name(), entry); save();
          } else {
            reserveSpace(item, 0);
            entry = new Entry(item.name(), item.size(), item.mtimeMillis(), 0, false, local.create(item.name()), 0, null);
            files.put(item.name(), entry); save();
          }
        }
        boolean changed = entry.size() != item.size() || entry.mtimeMillis() != item.mtimeMillis();
        if (!changed && (entry.verified() || entry.failure() != null) && entry.equals(validated.get(item.name()))) continue;
        boolean replaced = item.size() < entry.size() || item.mtimeMillis() < entry.mtimeMillis()
            || (changed || !entry.equals(validated.get(item.name()))) && reconciledSize(entry) != entry.bytesCopied();
        if (!replaced && entry.bytesCopied() > 0 && (changed || !entry.equals(validated.get(item.name())))) {
          var match = matches(item, entry);
          if (match.isEmpty()) return result(Status.WAITING, item.name(), 0, null);
          replaced = !match.get();
        }
        if (!gate.getAsBoolean()) return pause(item.name());
        if (replaced) entry = restart(item, entry, 0);
        else if (changed) {
          entry = entry.progress(item, entry.bytesCopied(), false, local.resume(entry), 0, null);
          files.put(item.name(), entry); save();
        }
        validated.put(item.name(), entry);
        if (entry.verified() || entry.failure() != null) { releaseSpace(item.name()); continue; }
        reserveSpace(item, entry.bytesCopied());
        if (clock.getAsLong() < nextReadUs) return result(Status.WAITING, item.name(), 0, null);
        long offset = entry.bytesCopied() + buffered.size();
        if (offset < item.size()) {
          int count = (int) Math.min(Math.min(BLOCK_BYTES, local.checkpointBytes() - buffered.size()), item.size() - offset);
          if (input != null) {
            replenish(clock.getAsLong());
            // A short packet sets the next useful wakeup, not a permanent ceiling on later reads.
            count = (int) Math.min(count, Math.max(1, readCredit / 1_000_000));
          }
          if (!ready(count)) return result(Status.WAITING, item.name(), 0, null);
          long startedUs = clock.getAsLong();
          var bytes = read(item.name(), offset, count);
          if (bytes.length == 0 || bytes.length > count) throw new IOException("Short or oversized remote read: " + item.name());
          charge(bytes.length, startedUs);
          if (buffered.size() == 0) { bufferedFile = item; bufferedSinceUs = startedUs; }
          buffered.writeBytes(bytes);
          boolean complete = offset + bytes.length == item.size();
          if (buffered.size() >= local.checkpointBytes() || complete || !gate.getAsBoolean()) checkpoint();
          else checkpointIfDue();
          if (complete || !gate.getAsBoolean()) closeInput();
          return result(Status.COPIED, item.name(), bytes.length, null);
        }
        if (!item.equals(previousPass.get(item.name()))) { needsStablePass = true; continue; }
        var match = matches(item, entry);
        if (match.isEmpty()) return result(Status.WAITING, item.name(), 0, null);
        if (!match.get()) { restart(item, entry, 0); return result(Status.RETRIED, item.name(), 0, "remote content changed"); }
        try { local.verify(entry.localName()); }
        catch (IOException e) {
          if (entry.retries() == 0) { restart(item, entry, 1); return result(Status.RETRIED, item.name(), 0, e.getMessage()); }
          var failed = entry.progress(item, entry.bytesCopied(), false, entry.localName(), 1, e.getMessage());
          files.put(item.name(), failed); validated.put(item.name(), failed); save();
          releaseSpace(item.name());
          return result(Status.REFUSED, item.name(), 0, e.getMessage());
        }
        String path = local.verified(entry);
        var verified = entry.progress(item, entry.bytesCopied(), true, path, entry.retries(), null);
        files.put(item.name(), verified); validated.put(item.name(), verified); save();
        releaseSpace(item.name());
        return result(Status.VERIFIED, item.name(), 0, null);
      }
      listing = null; // All files reached their listed end; the next step starts a new pass.
      return result(needsStablePass ? Status.WAITING : Status.IDLE, null, 0, null);
    } catch (IOException | RuntimeException | Error e) {
      stepFailure = e;
      buffered.reset(); bufferedFile = null; validated.clear();
      try { closeInput(); } catch (IOException | RuntimeException | Error close) { e.addSuppressed(close); }
      finally { releaseAllSpace(); }
      listing = null; // A renamed or removed remote file invalidates this snapshot immediately.
      throw e;
    } finally {
      if (!activity.compareAndSet(BUSY, 0)) {
        try { finishClose(); }
        catch (IOException | RuntimeException | Error closing) {
          if (stepFailure != null) stepFailure.addSuppressed(closing); else throw closing;
        } finally { activity.set(0); }
      }
    }
  }

  private Entry restart(RemoteFiles.File item, Entry old, int retry) throws IOException {
    closeInput(); buffered.reset(); bufferedFile = null;
    releaseSpace(item.name()); reserveSpace(item, 0);
    String archive = local.archive(old.localName());
    history.add(new Entry(old.remoteName(), old.size(), old.mtimeMillis(), old.bytesCopied(), old.verified(), archive, old.retries(), old.failure()));
    var next = new Entry(item.name(), item.size(), item.mtimeMillis(), 0, false, local.create(item.name()), retry, null);
    files.put(item.name(), next); validated.remove(item.name()); save(); return next;
  }
  private java.util.Optional<Boolean> matches(RemoteFiles.File item, Entry held) throws IOException {
    long length = held.bytesCopied(); if (length == 0) return java.util.Optional.of(true);
    if (length > item.size()) return java.util.Optional.of(false);
    closeInput();
    var hash = remote.prefixHash(item.name(), length);
    if (hash.isPresent()) return java.util.Optional.of(hash.get().equals(local.prefixHash(held.localName(), length)));
    int count = (int) Math.min(BLOCK_BYTES, length); long offset = length - count;
    if (!ready(count)) return java.util.Optional.empty();
    long startedUs = clock.getAsLong();
    var bytes = remote.read(item.name(), offset, count); charge(bytes.length, startedUs);
    return java.util.Optional.of(bytes.length == count && Arrays.equals(bytes, local.read(held.localName(), offset, count)));
  }
  private void replenish(long nowUs) {
    if (!creditStarted) { creditedAtUs = nowUs; creditStarted = true; }
    long elapsedUs = Math.max(0, nowUs - creditedAtUs);
    long missing = BLOCK_BYTES * 1_000_000L - readCredit;
    // Credit uses byte-microseconds: exact integer ceilings, with saturation before multiplication.
    readCredit = elapsedUs >= creditWaitUs(missing)
        ? BLOCK_BYTES * 1_000_000L : readCredit + elapsedUs * rateBytes;
    creditedAtUs = Math.max(creditedAtUs, nowUs);
  }
  private long creditWaitUs(long missing) {
    return missing / rateBytes + (missing % rateBytes == 0 ? 0 : 1);
  }
  private boolean ready(int count) {
    long nowUs = clock.getAsLong(); replenish(nowUs);
    long missing = Math.max(0, count * 1_000_000L - readCredit);
    long waitUs = creditWaitUs(missing);
    nextReadUs = nowUs + waitUs; return waitUs == 0;
  }
  private void charge(int count, long startedUs) {
    replenish(startedUs);
    if (count * 1_000_000L > readCredit) throw new IllegalStateException("Read exceeded its byte credit");
    readCredit -= count * 1_000_000L;
    ready(input != null && streamReadBytes > 0 ? streamReadBytes : BLOCK_BYTES);
  }
  /** A failed remote-handle close after local checkpointing succeeded; callers need its actual origin. */
  public static final class RemoteCloseException extends IOException {
    private RemoteCloseException(IOException cause) { super(cause.getMessage(), cause); }
  }
  /**
   * Routine disconnects checkpoint memory. A busy close only requests cleanup: the step retains
   * its lease until its remote/local I/O actually unwinds, even if the caller stopped waiting.
   */
  @Override public void close() throws IOException {
    int previous = activity.getAndUpdate(state -> state | CLOSE_REQUESTED);
    if ((previous & BUSY) != 0 || !activity.compareAndSet(CLOSE_REQUESTED, BUSY | CLOSE_REQUESTED)) return;
    try { finishClose(); }
    finally { activity.set(0); }
  }
  private void finishClose() throws IOException {
    Throwable checkpointFailure = null;
    try { checkpoint(); }
    catch (IOException | RuntimeException | Error failure) { checkpointFailure = failure; throw failure; }
    finally {
      try { closeInput(); }
      catch (IOException | RuntimeException | Error failure) {
        if (checkpointFailure != null) checkpointFailure.addSuppressed(failure);
        else if (failure instanceof IOException io) throw new RemoteCloseException(io);
        else throw failure;
      } finally { releaseAllSpace(); }
    }
  }

  private void reserveSpace(RemoteFiles.File file, long copied) throws IOException {
    var held = reservations.get(file.name());
    long covered = held == null ? copied : held.coveredThrough;
    long needed = Math.max(0, file.size() - covered);
    if (needed == 0) return;
    // Do not return an old lease while acquiring growth: the old copy still owns its full charge.
    var admitted = local.reserve(file.name(), needed);
    if (held == null) {
      held = new HeldSpace(covered); reservations.put(file.name(), held);
    }
    held.parts.add(admitted); held.coveredThrough = file.size();
  }
  private void releaseSpace(String name) {
    var held = reservations.remove(name);
    if (held != null) for (var reservation : held.parts) reservation.close();
  }
  private void releaseAllSpace() {
    for (var name : List.copyOf(reservations.keySet())) releaseSpace(name);
  }
  private long reconciledSize(Entry entry) throws IOException {
    long size = local.size(entry.localName());
    if (size > entry.bytesCopied() && !entry.verified() && local.truncate(entry.localName(), entry.bytesCopied())) return entry.bytesCopied();
    return size;
  }
  private byte[] read(String name, long offset, int count) throws IOException {
    if (!name.equals(inputName) || offset != inputOffset) {
      closeInput(); input = remote.open(name, offset).orElse(null); inputName = name; inputOffset = offset;
    }
    byte[] bytes;
    if (input == null) bytes = remote.read(name, offset, count);
    else {
      // Take one available packet. Filling a second packet spends another RTT and permits excess read ahead.
      var block = new byte[count]; int n = input.read(block);
      bytes = n < 0 ? new byte[0] : Arrays.copyOf(block, n);
    }
    if (input != null) streamReadBytes = Math.max(streamReadBytes, bytes.length);
    inputOffset += bytes.length; return bytes;
  }
  private void closeInput() throws IOException {
    var previous = input; input = null; inputName = null; streamReadBytes = 0;
    if (previous != null) previous.close();
  }
  private Result pause(String name) throws IOException {
    checkpoint(); closeInput(); validated.clear(); renameProof.clear();
    return result(Status.PAUSED, name, 0, null);
  }
  private void checkpointIfDue() throws IOException {
    if (bufferedFile != null && clock.getAsLong() - bufferedSinceUs >= 1_000_000) checkpoint();
  }
  private void checkpoint() throws IOException {
    if (bufferedFile == null) return;
    var item = bufferedFile; var before = files.get(item.name());
    reserveSpace(item, before.bytesCopied());
    var after = before.progress(item, before.bytesCopied() + buffered.size(), false, before.localName(), before.retries(), null);
    var committed = new LinkedHashMap<>(files); committed.put(item.name(), after);
    var next = new PullManifest(PullManifest.FORMAT_VERSION, serial, List.copyOf(committed.values()), List.copyOf(history));
    local.checkpoint(before.localName(), before.bytesCopied(), buffered.toByteArray(), next);
    files.put(item.name(), after); validated.put(item.name(), after); manifest = next;
    buffered.reset(); bufferedFile = null;
  }
  private void save() throws IOException {
    var next = new PullManifest(PullManifest.FORMAT_VERSION, serial, List.copyOf(files.values()), List.copyOf(history));
    local.save(next); manifest = next;
  }
  private Result result(Status status, String remote, long bytes, String detail) {
    pendingFiles = (int) observed.values().stream().filter(item -> {
      var held = files.get(item.name());
      return held == null || held.size() != item.size() || held.mtimeMillis() != item.mtimeMillis()
          || !held.verified() && held.failure() == null;
    }).count();
    long nowUs = clock.getAsLong(), waitUs = Math.max(0, nextReadUs - nowUs);
    if (bufferedFile != null) waitUs = Math.min(waitUs, Math.max(0, bufferedSinceUs + 1_000_000 - nowUs));
    return new Result(status, waitUs, remote, bytes, detail);
  }
}
