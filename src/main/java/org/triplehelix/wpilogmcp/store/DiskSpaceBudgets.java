/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * One process's copying writers share physical-space admission by filesystem, not store.
 * Every registered store begins with a standing capture reserve, even without a connected robot.
 * Full copy reservations remain charged until their owners finish I/O; a timeout never releases one.
 * This is conservative admission against sampled usable space, not an operating-system allocation
 * or a promise against other processes consuming the disk.
 */
public final class DiskSpaceBudgets implements AutoCloseable {
  @FunctionalInterface public interface SpaceSource {
    long usableBytes(FileStore filesystem) throws IOException;
  }
  public enum State { AVAILABLE, UNKNOWN }
  public enum CaptureDecision { KEEP_STATE, PAUSE, RESUME, UNKNOWN }

  /** Hysteresis belongs to each capture; only its reserve participates in the filesystem maximum. */
  public record Policy(long reserveBytes, long floorBytes, long resumeMarginBytes) {
    public static final Policy DEFAULT = new Policy(2L << 30, 256L << 20, 512L << 20);
    public Policy {
      if (reserveBytes < 0 || floorBytes < 0 || resumeMarginBytes < 0
          || floorBytes > Long.MAX_VALUE - resumeMarginBytes) {
        throw new IllegalArgumentException("Invalid capture disk-space policy");
      }
    }
    public CaptureDecision decide(Snapshot snapshot, boolean paused) {
      if (snapshot.state() == State.UNKNOWN || snapshot.freeBytes() == null) return CaptureDecision.UNKNOWN;
      if (paused) return snapshot.freeBytes() > floorBytes + resumeMarginBytes
          ? CaptureDecision.RESUME : CaptureDecision.KEEP_STATE;
      return snapshot.freeBytes() <= floorBytes ? CaptureDecision.PAUSE : CaptureDecision.KEEP_STATE;
    }
  }

  /** Public facts deliberately exclude mount names, paths and filesystem-provider exception text. */
  public record Snapshot(String filesystemId, State state, Long freeBytes, long reservedBytes,
      long captureReserveBytes, long sampledAtNanos) {
    public Long availableBytes() {
      return freeBytes == null ? null : Math.max(0, freeBytes - reservedBytes);
    }
  }

  public static final class Refused extends IOException {
    private final String code;
    private final Snapshot snapshot;
    private final long requestedBytes;
    private Refused(String code, Snapshot snapshot, long requestedBytes, String message) {
      super(message);
      this.code = code;
      this.snapshot = snapshot;
      this.requestedBytes = requestedBytes;
    }
    public String code() { return code; }
    public Snapshot snapshot() { return snapshot; }
    public long requestedBytes() { return requestedBytes; }
  }

  private static final int MAX_SAMPLE_RETRIES = 8;
  private final SpaceSource space;
  private final LongSupplier clock;
  private final Map<FilesystemIdentity, Budget> filesystems = new LinkedHashMap<>();
  private final Map<Path, StorePolicy> policies = new LinkedHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private int nextFilesystem;

  public DiskSpaceBudgets() { this(FileStore::getUsableSpace); }
  public DiskSpaceBudgets(SpaceSource space) { this(space, System::nanoTime); }
  DiskSpaceBudgets(SpaceSource space, LongSupplier clock) {
    this.space = Objects.requireNonNull(space);
    this.clock = Objects.requireNonNull(clock);
  }

  /** Resolve at registration/copy admission, never from the listener or a health rendering path. */
  public StoreBudget store(Path existingDirectory) throws IOException {
    if (closed.get()) throw new IOException("Disk-space owner is closed");
    var root = existingDirectory.toRealPath();
    return destination(root, root);
  }

  private StoreBudget destination(Path root, Path existingDirectory) throws IOException {
    // Resolve actual placement, including a nested mount, before entering accounting.
    var folder = existingDirectory.toRealPath();
    var filesystem = Files.getFileStore(folder);
    var identity = FilesystemIdentity.of(filesystem);
    synchronized (filesystems) {
      if (closed.get()) throw new IOException("Disk-space owner is closed");
      var policy = policies.computeIfAbsent(root,
          ignored -> new StorePolicy(Policy.DEFAULT.reserveBytes(), false));
      var budget = filesystems.computeIfAbsent(identity,
          ignored -> new Budget(filesystem, identity, folder, "filesystem_" + ++nextFilesystem));
      if (budget.stores.putIfAbsent(root, policy) == null) budget.changed();
      return new StoreBudget(budget, root);
    }
  }

  /** A daemon owned by the server invokes this; capture and health read only published snapshots. */
  public void refreshAll() {
    if (closed.get()) return;
    List<Budget> budgets;
    synchronized (filesystems) { budgets = List.copyOf(filesystems.values()); }
    for (var budget : budgets) {
      try { refresh(budget); }
      catch (IOException ignored) {
        // Unknown is published by measurement failure. Contention preserves the last snapshot.
      }
    }
  }

  public List<Snapshot> snapshots() {
    List<Budget> budgets;
    synchronized (filesystems) { budgets = List.copyOf(filesystems.values()); }
    return budgets.stream().map(budget -> budget.published).toList();
  }

  public final class StoreBudget {
    private final Budget budget;
    private final Path root;
    private StoreBudget(Budget budget, Path root) { this.budget = budget; this.root = root; }
    public Snapshot snapshot() { return budget.published; }
    public Snapshot refresh() throws IOException { return DiskSpaceBudgets.this.refresh(budget); }
    /**
     * Configuration is standing protection, never a lease on an active recording. The first
     * explicit policy replaces this store's default; several services configuring the same store
     * retain their maximum for this registry lifetime. Stopping or pausing one cannot lower it.
     */
    public void configureCapture(Policy policy) throws IOException {
      Objects.requireNonNull(policy);
      synchronized (filesystems) {
        if (closed.get()) throw new IOException("Disk-space owner is closed");
        var previous = policies.get(root);
        long reserve = previous.configured() ? Math.max(previous.reserveBytes(), policy.reserveBytes())
            : policy.reserveBytes();
        var configured = new StorePolicy(reserve, true);
        policies.put(root, configured);
        for (var destination : filesystems.values()) {
          if (destination.stores.containsKey(root)) {
            destination.stores.put(root, configured);
            destination.changed();
          }
        }
      }
    }
    /** Every physical allocation inherits its owning store's policy on the actual destination. */
    public StoreBudget destination(Path existingDirectory) throws IOException {
      return DiskSpaceBudgets.this.destination(root, existingDirectory);
    }
    public Reservation reserveCopy(long bytes) throws IOException {
      if (bytes < 0) throw new IllegalArgumentException("Negative disk reservation");
      for (int attempt = 0; attempt < MAX_SAMPLE_RETRIES; attempt++) {
        long generation;
        synchronized (filesystems) {
          if (closed.get()) throw new IOException("Disk-space owner is closed");
          generation = budget.generation;
        }
        var sample = measure(budget);
        synchronized (filesystems) {
          if (closed.get()) throw new IOException("Disk-space owner is closed");
          if (budget.generation != generation) continue;
          budget.measured(sample);
          requireCopySpace(budget.published, bytes);
          if (bytes > Long.MAX_VALUE - budget.reserved) throw new IOException("Disk reservation count overflow");
          budget.reserved += bytes;
          budget.changed();
          return new Reservation(budget, bytes);
        }
      }
      throw new IOException("Disk-space accounting changed during measurement; retry admission");
    }
  }

  private static void requireCopySpace(Snapshot snapshot, long bytes) throws Refused {
    if (snapshot.freeBytes() == null || snapshot.state() == State.UNKNOWN) {
      throw new Refused("DISK_SPACE_UNKNOWN", snapshot, bytes,
          "Disk free space is unavailable; requested_bytes=" + bytes
          + ", reserved_bytes=" + snapshot.reservedBytes()
          + ", capture_reserve_bytes=" + snapshot.captureReserveBytes());
    }
    long unreserved = Math.max(0, snapshot.freeBytes() - snapshot.reservedBytes());
    long permitted = Math.max(0, unreserved - snapshot.captureReserveBytes());
    if (bytes > permitted) {
      throw new Refused("CAPTURE_DISK_RESERVE", snapshot, bytes,
          "Copy would consume capture's reserve: free_bytes=" + snapshot.freeBytes()
          + ", reserved_bytes=" + snapshot.reservedBytes()
          + ", capture_reserve_bytes=" + snapshot.captureReserveBytes()
          + ", requested_bytes=" + bytes);
    }
  }

  private record Measurement(Long freeBytes, long atNanos) {}

  /**
   * A FileStore measures through the folder it was resolved from, and that folder can go while
   * its filesystem stays. Then each store registered on the filesystem is resolved again, newest
   * first, and the first that still exists measures from then on. Before, deleting the first
   * store folder registered on a disk made every later copy there refuse as DISK_SPACE_UNKNOWN
   * until the server restarted. A failure while the folder still exists is the filesystem's own,
   * and is not read again through another folder.
   */
  private Measurement measure(Budget budget) {
    FileStore through;
    Path folder;
    List<Path> roots;
    synchronized (filesystems) {
      through = budget.measuredThrough;
      folder = budget.measuredFolder;
      roots = List.copyOf(budget.stores.keySet());
    }
    var bytes = usableBytes(through);
    if (bytes == null && !Files.exists(folder)) {
      for (int i = roots.size() - 1; bytes == null && i >= 0; i--) {
        var root = roots.get(i);
        try {
          var resolved = Files.getFileStore(root);
          if (!FilesystemIdentity.of(resolved).equals(budget.identity)) continue;
          bytes = usableBytes(resolved);
          if (bytes != null) synchronized (filesystems) {
            budget.measuredThrough = resolved;
            budget.measuredFolder = root;
          }
        } catch (IOException | SecurityException gone) {
          // That store's folder is gone too; try an older one.
        }
      }
    }
    return new Measurement(bytes, clock.getAsLong());
  }

  private Long usableBytes(FileStore filesystem) {
    try {
      long measured = space.usableBytes(filesystem);
      return measured < 0 ? null : measured;
    } catch (IOException | SecurityException failure) {
      return null;
    }
  }

  private Snapshot refresh(Budget budget) throws IOException {
    for (int attempt = 0; attempt < MAX_SAMPLE_RETRIES; attempt++) {
      long generation;
      synchronized (filesystems) {
        if (closed.get()) throw new IOException("Disk-space owner is closed");
        generation = budget.generation;
      }
      var sample = measure(budget);
      synchronized (filesystems) {
        if (closed.get()) throw new IOException("Disk-space owner is closed");
        if (budget.generation != generation) continue;
        budget.measured(sample);
        return budget.published;
      }
    }
    throw new IOException("Disk-space accounting changed during measurement; retry sampling");
  }

  public final class Reservation implements AutoCloseable {
    private final Budget budget;
    private final long bytes;
    private boolean released;
    private Reservation(Budget budget, long bytes) { this.budget = budget; this.bytes = bytes; }
    @Override public void close() {
      synchronized (filesystems) {
        if (released) return;
        released = true;
        budget.reserved -= bytes;
        budget.changed();
      }
    }
  }

  private record StorePolicy(long reserveBytes, boolean configured) {}

  private static final class Budget {
    final FilesystemIdentity identity;
    /** The filesystem as resolved through measuredFolder; both guarded by filesystems. */
    FileStore measuredThrough;
    Path measuredFolder;
    final String id;
    final Map<Path, StorePolicy> stores = new LinkedHashMap<>();
    long generation, reserved, sampledAt;
    Long free;
    volatile Snapshot published;
    Budget(FileStore filesystem, FilesystemIdentity identity, Path folder, String id) {
      this.identity = identity;
      this.measuredThrough = filesystem;
      this.measuredFolder = folder;
      this.id = id;
      publish();
    }
    void measured(Measurement sample) {
      free = sample.freeBytes();
      sampledAt = sample.atNanos();
      changed();
    }
    void changed() { generation++; publish(); }
    void publish() {
      long reserve = stores.values().stream().mapToLong(StorePolicy::reserveBytes).max().orElse(Policy.DEFAULT.reserveBytes());
      published = new Snapshot(id, free == null ? State.UNKNOWN : State.AVAILABLE,
          free, reserved, reserve, sampledAt);
    }
  }

  /** Existing reservations remain charged and may still be released after shutdown admission closes. */
  @Override public void close() { closed.set(true); }
}
