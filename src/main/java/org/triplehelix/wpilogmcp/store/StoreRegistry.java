/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/** A server owns one registry, so aliases for a store share one import queue. */
public final class StoreRegistry implements AutoCloseable {
  private final SecurityValidator security;
  private final LogManager logManager;
  private final ConcurrentHashMap<Path, LogStore> stores = new ConcurrentHashMap<>();
  private boolean closed;

  public StoreRegistry(SecurityValidator security) {
    this(security, LogManager.getInstance());
  }

  /** The import must retire mappings from the same manager that serves the caller's tools. */
  public StoreRegistry(SecurityValidator security, LogManager logManager) {
    this.security = security;
    this.logManager = logManager;
  }

  public LogStore store(Path directory) throws IOException {
    security.validate(directory);
    Files.createDirectories(directory);
    var real = directory.toRealPath();
    security.validate(real);
    synchronized (this) {
      if (closed) throw new IllegalStateException("Store registry is closed");
      return stores.computeIfAbsent(real,
          path -> new LogStore(path, security, logManager, StoreCatalog::read));
    }
  }

  /** Queued and running imports finish; daemon workers never keep the process alive. */
  @Override
  public synchronized void close() {
    closed = true;
    stores.values().forEach(LogStore::close);
  }
}
