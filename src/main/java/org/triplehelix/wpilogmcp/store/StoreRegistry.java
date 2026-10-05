/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/** A server owns one registry, so aliases for a store share one import queue. */
public final class StoreRegistry implements AutoCloseable {
  private final SecurityValidator security;
  private final LogManager logManager;
  private final ConcurrentHashMap<Path, LogStore> stores = new ConcurrentHashMap<>();
  private boolean closed;
  private ScheduledExecutorService watcher;
  private int watchers;
  private final java.util.concurrent.atomic.AtomicBoolean polling = new java.util.concurrent.atomic.AtomicBoolean();

  public StoreRegistry(SecurityValidator security) {
    this(security, LogManager.getInstance());
  }

  /** The import must retire mappings from the same manager that serves the caller's tools. */
  public StoreRegistry(SecurityValidator security, LogManager logManager) {
    this.security = security;
    this.logManager = logManager;
  }

  public LogStore store(Path directory) throws IOException {
    validate(directory);
    Files.createDirectories(directory);
    var real = directory.toRealPath();
    security.validate(real);
    synchronized (this) {
      if (closed) throw new IllegalStateException("Store registry is closed");
      return stores.computeIfAbsent(real,
          path -> new LogStore(path, security, logManager, StoreCatalog::read));
    }
  }

  /** Writes never inherit the reader's legacy unrestricted mode when no directory is set. */
  public void validate(Path path) throws IOException {
    if (security.getAllowedDirectories().isEmpty()) {
      throw new IOException("Import requires a configured log directory");
    }
    security.validate(path);
  }

  /** More than one transport in a process shares this watcher as well as its import queues. */
  public synchronized void startWatching() {
    if (closed) throw new IllegalStateException("Store registry is closed");
    if (watchers++ > 0) return;
    watcher = Executors.newSingleThreadScheduledExecutor(task -> {
      var thread = new Thread(task, "store-inbox");
      thread.setDaemon(true);
      return thread;
    });
    watcher.scheduleWithFixedDelay(this::poll, 0, 3, TimeUnit.SECONDS);
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

  private void poll() {
    if (!polling.compareAndSet(false, true)) return;
    try {
      for (var directory : security.getAllowedDirectories()) {
        try {
          for (var root : StoreCatalog.discover(directory)) {
            store(root).inbox().poll(System.nanoTime());
          }
        } catch (IOException | RuntimeException e) {
          org.slf4j.LoggerFactory.getLogger(StoreRegistry.class).warn("Inbox scan could not finish");
        }
      }
    } finally { polling.set(false); }
  }

  public void awaitImports() {
    stores.values().forEach(LogStore::awaitImports);
  }

  public boolean importing() {
    return stores.values().stream().anyMatch(LogStore::importing);
  }

  /** Queued and running imports finish; daemon workers never keep the process alive. */
  @Override
  public void close() {
    ScheduledExecutorService polling;
    synchronized (this) {
      if (closed) return;
      closed = true;
      polling = watcher;
      if (polling != null) polling.shutdown();
    }
    // No monitor held across I/O or the import barrier.
    if (polling != null) {
      try {
        polling.awaitTermination(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    awaitImports();
    stores.values().forEach(LogStore::close);
  }
}
