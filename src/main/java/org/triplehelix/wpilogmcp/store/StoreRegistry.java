/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/** A server owns one registry, so aliases for a store share one import queue. */
public final class StoreRegistry implements AutoCloseable {
  private final SecurityValidator security;
  private final LogManager logManager;
  private final DiskSpaceBudgets diskSpace;
  private final ScheduledExecutorService diskSampler = Executors.newSingleThreadScheduledExecutor(task -> {
    var thread = new Thread(task, "store-disk-space"); thread.setDaemon(true); return thread;
  });
  private final ConcurrentHashMap<Path, LogStore> stores = new ConcurrentHashMap<>();
  private boolean closed;
  private ScheduledExecutorService watcher;
  private int watchers;
  java.util.function.Supplier<ScheduledExecutorService> watcherFactory = StoreRegistry::newWatcher;
  java.util.function.LongSupplier watchClock = System::nanoTime;
  private final AtomicBoolean polling = new AtomicBoolean();
  private final Discovery discovery;
  private volatile boolean discoverNext = true;
  private long lastDiscovery;
  private volatile LocalIngestion localIngestion;
  private final AtomicBoolean configuringIngestion = new AtomicBoolean();

  /** Shared by discovery and listing; editor windows never own separate ingestion loops. */
  public LocalIngestion localIngestion() { return localIngestion; }

  public synchronized void setLocalIngestion(LocalIngestion service) {
    var previous = localIngestion;
    if (previous != null && previous != service) throw new IllegalStateException("Local ingestion is already configured");
    localIngestion = service;
  }

  public LocalIngestion configureLocalIngestion(Path policyFile) throws IOException {
    if (!configuringIngestion.compareAndSet(false, true)) throw new IllegalStateException("Local ingestion is being configured");
    try {
      if (localIngestion != null) throw new IllegalStateException("Local ingestion is already configured");
      var service = new LocalIngestion(this, security, policyFile);
      try { setLocalIngestion(service); return service; }
      catch (RuntimeException failure) { service.close(); throw failure; }
    } finally { configuringIngestion.set(false); }
  }

  public synchronized void clearLocalIngestion(LocalIngestion expected) {
    if (localIngestion == expected) localIngestion = null;
  }

  @FunctionalInterface
  interface Discovery {
    List<Path> find(Path directory) throws IOException;
  }

  public StoreRegistry(SecurityValidator security) {
    this(security, LogManager.getInstance());
  }

  /** The import must retire mappings from the same manager that serves the caller's tools. */
  public StoreRegistry(SecurityValidator security, LogManager logManager) {
    this(security, logManager, StoreCatalog::discover);
  }

  StoreRegistry(SecurityValidator security, LogManager logManager, Discovery discovery) {
    this(security, logManager, discovery, new DiskSpaceBudgets());
  }

  /** Every store and copying writer on one filesystem shares this injectable accounting owner. */
  StoreRegistry(SecurityValidator security, LogManager logManager, Discovery discovery,
      DiskSpaceBudgets diskSpace) {
    this.diskSpace = java.util.Objects.requireNonNull(diskSpace);
    this.discovery = discovery;
    this.security = security;
    this.logManager = logManager;
    diskSampler.scheduleWithFixedDelay(diskSpace::refreshAll, 0, 1, TimeUnit.SECONDS);
  }

  /** Filesystem discovery is admission work, outside the registry publication monitor. */
  public DiskSpaceBudgets.StoreBudget diskBudget(Path directory) throws IOException {
    return diskSpace.store(directory);
  }

  public List<DiskSpaceBudgets.Snapshot> diskSnapshots() { return diskSpace.snapshots(); }

  /** Status reads must neither create a store nor start filesystem recovery on a request worker. */
  public java.util.Optional<LogStore> registered(Path canonicalRoot) {
    return java.util.Optional.ofNullable(stores.get(canonicalRoot));
  }

  public LogStore store(Path directory) throws IOException {
    return store(directory, null);
  }

  /** Opens a folder's store with a mover of a test's own; the store must be the folder's first. */
  LogStore store(Path directory, LogStore.Mover mover) throws IOException {
    validate(directory);
    Files.createDirectories(directory);
    var real = directory.toRealPath();
    security.validate(real);
    StoreLock.checkedPath(real, security);
    var budget = diskBudget(real);
    synchronized (this) {
      if (closed) throw new IllegalStateException("Store registry is closed");
      if (mover != null && stores.containsKey(real)) throw new IllegalStateException("A store with its own mover must be its folder's first: " + real);
      return stores.computeIfAbsent(real,
          path -> new LogStore(path, security, logManager, StoreCatalog::read, mover == null ? new LogStore.Mover() : mover, budget));
    }
  }

  /**
   * Closes and forgets one store once its last turn has run, so another store object may open the
   * folder. The server never gives a folder back; a test that listed a store folder through the
   * process's registry returns it here, since a second registry of its own would be refused.
   */
  void release(Path directory) throws IOException {
    var real = directory.toRealPath();
    LogStore store;
    synchronized (this) { store = stores.remove(real); }
    if (store != null) store.close();
  }

  /** Move notices expire from listings, but a path once handed to a tool remains a durable alias. */
  public Path resolveMoved(Path original) throws IOException {
    return StoreCatalog.resolveMoved(original, security);
  }

  /** Writes never inherit the reader's legacy unrestricted mode when no directory is set. */
  public void validate(Path path) throws IOException {
    if (security.getAllowedDirectories().isEmpty()) {
      throw new IOException("Import requires a configured log directory");
    }
    security.validate(path);
  }
  /** Local configuration must already admit the root before it can become server-owned. */
  public AutoCloseable retainMirrorRoot(Path root) throws IOException {
    validate(root); return security.retainServiceDirectory(root.toRealPath());
  }

  public java.util.Optional<SystemLogFiles.Snapshot> systemLogs(Path capture) throws IOException {
    security.validate(capture);
    return SystemLogFiles.read(capture, security);
  }

  /** More than one transport in a process shares this watcher as well as its import queues. */
  public synchronized void startWatching() {
    if (closed) throw new IllegalStateException("Store registry is closed");
    if (watchers++ > 0) return;
    discoverNext = true;
    watcher = watcherFactory.get();
    watcher.scheduleWithFixedDelay(() -> poll(watchClock.getAsLong()), 0, 3, TimeUnit.SECONDS);
  }

  static ScheduledExecutorService newWatcher() {
    return Executors.newSingleThreadScheduledExecutor(task -> {
      var thread = new Thread(task, "store-inbox"); thread.setDaemon(true); return thread;
    });
  }

  public void stopWatching() {
    ScheduledExecutorService previous;
    synchronized (this) {
      if (watchers == 0 || --watchers > 0) return;
      previous = watcher;
      previous.shutdown();
    }
    try {
      previous.awaitTermination(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Listings already walk the directories; reuse their discoveries without another walk. */
  public void discovered(Path root) {
    try {
      store(root);
    } catch (IOException | RuntimeException e) {
      LoggerFactory.getLogger(StoreRegistry.class).warn("Discovered store could not be watched");
    }
  }

  /** Inboxes are cheap to poll; searching a season's directory tree is limited to once a minute. */
  void poll(long now) {
    if (!polling.compareAndSet(false, true)) return;
    try {
      if (discoverNext || now - lastDiscovery >= Duration.ofMinutes(1).toNanos()) {
        discoverNext = false;
        lastDiscovery = now;
        for (var directory : security.getAllowedDirectories()) {
          try {
            discovery.find(directory).forEach(this::discovered);
          } catch (IOException | RuntimeException e) {
            LoggerFactory.getLogger(StoreRegistry.class).warn("Store discovery could not finish");
          }
        }
      }
      for (var store : stores.values()) {
        try {
          validate(store.root());
          if (StoreCatalog.isStore(store.root())
              && !new StoreFiles(store.root(), security).read(store.root().resolve("store.json"), StoreManifest.Header.class).mirror()) {
            store.inbox().poll(now);
          }
        } catch (IOException | RuntimeException e) {
          LoggerFactory.getLogger(StoreRegistry.class).warn("Inbox scan could not finish");
        }
      }
    } finally {
      polling.set(false);
    }
  }

  public void awaitImports() {
    stores.values().forEach(LogStore::awaitImports);
  }

  /** Transport closure drains admitted mutations without stopping shared import preparation. */
  public void awaitMutations() {
    stores.values().forEach(LogStore::awaitMutations);
  }

  public boolean importing() {
    return stores.values().stream().anyMatch(LogStore::importing);
  }

  /** Published accounting is safe for health and metrics without joining any store owner. */
  public record CatalogCapacity(String storeId, ReplicaCatalogCapacity catalogCapacity) {}
  public List<CatalogCapacity> catalogCapacities() {
    return stores.values().stream().map(LogStore::catalogCapacity).filter(java.util.Objects::nonNull)
        .filter(value -> value.catalogCapacity() != null).sorted(java.util.Comparator.comparing(CatalogCapacity::storeId)).toList();
  }

  /** Unknown totals are omitted: an interrupted recovery is not evidence of an empty inventory. */
  public record PendingCount(String storeId, int totalPendingCount) {}
  public List<PendingCount> publicationCounts() {
    return stores.values().stream().map(LogStore::publicationStatus)
        .filter(value -> value.state() == ReplicaPublications.State.READY
            && value.storeId() != null && value.totalPendingCount() != null)
        .map(value -> new PendingCount(value.storeId(), value.totalPendingCount()))
        .sorted(java.util.Comparator.comparing(PendingCount::storeId)).toList();
  }

  /** Published accounting is safe for health and metrics without joining any store owner. */
  public record QueueMetrics(long overBudgetTurns, long longestTurnUs) {}
  public QueueMetrics queueMetrics() {
    long overBudget = 0, longestUs = 0;
    for (var store : stores.values()) {
      var value = store.queueMetrics();
      overBudget += value.overBudgetTurns();
      longestUs = Math.max(longestUs, value.longestTurnUs());
    }
    return new QueueMetrics(overBudget, longestUs);
  }

  /** Staging stops before draining short mutations; workers retain their bytes until unwinding. */
  @Override
  public void close() {
    ScheduledExecutorService polling;
    synchronized (this) {
      if (closed) return;
      closed = true;
      polling = watcher;
      if (polling != null) polling.shutdown();
    }
    // Refuse, drain, release: imports stop and are waited for before the disk budget they reserve
    // from closes. Closing the budget first failed an import in flight with "Disk-space owner is
    // closed" while its store did not yet know it was stopping, so it was recorded as refused, and
    // local ingestion kept that refusal after the restart.
    stores.values().forEach(LogStore::stopImports);
    var ingestion = localIngestion;
    if (ingestion != null) ingestion.stop();
    // No monitor held across I/O or the import barrier.
    if (polling != null) {
      try {
        polling.awaitTermination(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    awaitImports();
    beforeBudgetCloses.run();
    diskSampler.shutdown();
    diskSpace.close();
    // Every store stops before any is waited for, so a stop waits once, not once per store.
    stores.values().forEach(LogStore::beginClose);
    long deadline = System.nanoTime() + LogStore.CLOSE_WAIT.toNanos();
    for (var store : stores.values()) store.awaitClosed(java.time.Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
  }
  private volatile Runnable beforeBudgetCloses = () -> {};
  /** Tests observe the stores at the moment the disk budget is about to close. */
  void beforeBudgetCloses(Runnable probe) { beforeBudgetCloses = java.util.Objects.requireNonNull(probe); }
}
