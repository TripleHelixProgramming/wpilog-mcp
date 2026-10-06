/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.sync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.triplehelix.wpilogmcp.sync.PullManifest.Entry;

/**
 * One step reads at most one block, on one caller's thread. Neither names nor a common header
 * justify resuming: compare every held byte by hash, or the known range's final 64 KiB when
 * the remote cannot execute a hash command. Time and storage are ports, not sleeps or sockets.
 */
public final class FileTransfer {
  public static final int BLOCK_BYTES = 64 * 1024;
  public interface Local {
    String create(String remoteName) throws IOException;
    long size(String name) throws IOException;
    byte[] read(String name, long offset, int count) throws IOException;
    String prefixHash(String name, long length) throws IOException;
    void append(String name, long offset, byte[] bytes) throws IOException;
    String rename(String name, String remoteName) throws IOException;
    String archive(String name) throws IOException;
    /** Load through the ordinary reader; refuse a scan that stops before EOF. */
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
  private final AtomicBoolean busy = new AtomicBoolean();
  private final Map<String, Entry> files = new LinkedHashMap<>();
  private final List<Entry> history;
  private final Map<String, RemoteFiles.File> observed = new HashMap<>();
  private final Map<String, Entry> validated = new HashMap<>();
  private final Map<String, Boolean> renameProof = new HashMap<>();
  private final String serial;
  private volatile PullManifest manifest;
  private long nextReadUs;

  public FileTransfer(RemoteFiles remote, Local local, PullManifest manifest,
      long rateBytes, LongSupplier clock, BooleanSupplier gate) {
    if (rateBytes <= 0) throw new IllegalArgumentException("Rate must be positive");
    this.remote = remote; this.local = local; this.manifest = manifest; this.serial = manifest.serialNumber();
    this.rateBytes = rateBytes; this.clock = clock; this.gate = gate;
    manifest.files().forEach(e -> files.put(e.remoteName(), e)); history = new ArrayList<>(manifest.history());
  }
  public PullManifest manifest() { return manifest; }

  public Result step() throws IOException {
    if (!busy.compareAndSet(false, true)) throw new IllegalStateException("A transfer step is already running");
    try {
      if (!gate.getAsBoolean()) { validated.clear(); renameProof.clear(); return result(Status.PAUSED, null, 0, null); }
      if (clock.getAsLong() < nextReadUs) return result(Status.WAITING, null, 0, null);
      var listing = remote.list().stream().sorted(java.util.Comparator.comparing(RemoteFiles.File::name)).toList();
      var names = new HashSet<String>();
      for (var item : listing) if (!names.add(item.name())) throw new IOException("Duplicate remote name: " + item.name());
      for (var item : listing) {
        var previousPass = observed.put(item.name(), item);
        var entry = files.get(item.name());
        if (entry == null) {
          // A rename is unique content evidence among names absent from this listing.
          var renamed = new ArrayList<Entry>();
          for (var held : List.copyOf(files.values())) {
            if (!names.contains(held.remoteName()) && held.bytesCopied() > 0 && held.bytesCopied() <= item.size()
                && held.bytesCopied() == local.size(held.localName())) {
              String key = item + "|" + held;
              Boolean proof = renameProof.get(key);
              if (proof == null) { proof = matches(item, held); renameProof.put(key, proof); }
              if (proof) renamed.add(held);
            }
            if (!gate.getAsBoolean()) { validated.clear(); renameProof.clear(); return result(Status.PAUSED, null, 0, null); }
            if (clock.getAsLong() < nextReadUs) return result(Status.WAITING, null, 0, null);
          }
          if (renamed.size() == 1) {
            var old = renamed.get(0); String path = local.rename(old.localName(), item.name());
            files.remove(old.remoteName()); validated.remove(old.remoteName());
            entry = old.progress(item, old.bytesCopied(), false, path, old.retries(), null);
            files.put(item.name(), entry); validated.put(item.name(), entry); save();
          } else {
            entry = new Entry(item.name(), item.size(), item.mtimeMillis(), 0, false, local.create(item.name()), 0, null);
            files.put(item.name(), entry); save();
          }
        }
        renameProof.clear();
        boolean changed = entry.size() != item.size() || entry.mtimeMillis() != item.mtimeMillis();
        if (!changed && (entry.verified() || entry.failure() != null)) continue;
        boolean replaced = item.size() < entry.size() || item.mtimeMillis() < entry.mtimeMillis()
            || local.size(entry.localName()) != entry.bytesCopied();
        if (!replaced && entry.bytesCopied() > 0 && !entry.equals(validated.get(item.name()))) {
          replaced = !matches(item, entry);
        }
        if (!gate.getAsBoolean()) { validated.clear(); renameProof.clear(); return result(Status.PAUSED, item.name(), 0, null); }
        if (replaced) entry = restart(item, entry, 0);
        else if (changed) {
          entry = entry.progress(item, entry.bytesCopied(), false, entry.localName(), 0, null);
          files.put(item.name(), entry); save();
        }
        validated.put(item.name(), entry);
        if (clock.getAsLong() < nextReadUs) return result(Status.WAITING, item.name(), 0, null);
        if (entry.bytesCopied() < item.size()) {
          int count = (int) Math.min(BLOCK_BYTES, item.size() - entry.bytesCopied());
          var bytes = remote.read(item.name(), entry.bytesCopied(), count);
          if (bytes.length == 0 || bytes.length > count) throw new IOException("Short or oversized remote read: " + item.name());
          local.append(entry.localName(), entry.bytesCopied(), bytes); charge(bytes.length);
          entry = entry.progress(item, entry.bytesCopied() + bytes.length, false, entry.localName(), entry.retries(), null);
          files.put(item.name(), entry); validated.put(item.name(), entry); save();
          return result(Status.COPIED, item.name(), bytes.length, null);
        }
        if (!item.equals(previousPass)) continue; // Size/mtime must be stable for a whole listing pass.
        if (!matches(item, entry)) { restart(item, entry, 0); return result(Status.RETRIED, item.name(), 0, "remote content changed"); }
        try { local.verify(entry.localName()); }
        catch (IOException e) {
          if (entry.retries() == 0) { restart(item, entry, 1); return result(Status.RETRIED, item.name(), 0, e.getMessage()); }
          files.put(item.name(), entry.progress(item, entry.bytesCopied(), false, entry.localName(), 1, e.getMessage())); save();
          return result(Status.REFUSED, item.name(), 0, e.getMessage());
        }
        String path = local.verified(entry);
        files.put(item.name(), entry.progress(item, entry.bytesCopied(), true, path, entry.retries(), null)); save();
        return result(Status.VERIFIED, item.name(), 0, null);
      }
      observed.keySet().retainAll(names);
      return result(Status.IDLE, null, 0, null);
    } finally { busy.set(false); }
  }

  private Entry restart(RemoteFiles.File item, Entry old, int retry) throws IOException {
    String archive = local.archive(old.localName());
    history.add(new Entry(old.remoteName(), old.size(), old.mtimeMillis(), old.bytesCopied(), old.verified(), archive, old.retries(), old.failure()));
    var next = new Entry(item.name(), item.size(), item.mtimeMillis(), 0, false, local.create(item.name()), retry, null);
    files.put(item.name(), next); validated.remove(item.name()); save(); return next;
  }
  private boolean matches(RemoteFiles.File item, Entry held) throws IOException {
    long length = held.bytesCopied(); if (length == 0) return true;
    if (length > item.size()) return false;
    var hash = remote.prefixHash(item.name(), length);
    if (hash.isPresent()) return hash.get().equals(local.prefixHash(held.localName(), length));
    int count = (int) Math.min(BLOCK_BYTES, length); long offset = length - count;
    var bytes = remote.read(item.name(), offset, count); charge(bytes.length);
    return bytes.length == count && Arrays.equals(bytes, local.read(held.localName(), offset, count));
  }
  private void charge(int count) {
    long duration = (long) Math.ceil(count * 1_000_000.0 / rateBytes);
    nextReadUs = Math.max(nextReadUs, clock.getAsLong()) + duration;
  }
  private void save() throws IOException {
    var next = new PullManifest(PullManifest.FORMAT_VERSION, serial, List.copyOf(files.values()), List.copyOf(history));
    local.save(next); manifest = next;
  }
  private Result result(Status status, String remote, long bytes, String detail) {
    return new Result(status, Math.max(0, nextReadUs - clock.getAsLong()), remote, bytes, detail);
  }
}
