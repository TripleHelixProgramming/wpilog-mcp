/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A move reserves its sources before evicting their cached logs, so another request cannot
 * reopen a mapping while the importer waits for existing readers. Claims last exactly as long
 * as their readers; no file is held until GC or a server restart. The monitor protects only
 * claims: path resolution, unmapping, and waiting all happen outside it.
 */
public final class LogFileAccess {
  private LogFileAccess() {
  }

  private static final class Readers {
    int count;
    final CompletableFuture<Void> drained = new CompletableFuture<>();
  }

  private static final HashMap<Path, Readers> readers = new HashMap<>();
  private static final HashSet<Path> moving = new HashSet<>();

  public interface Lease extends AutoCloseable {
    @Override
    void close();
  }

  public static Lease read(Path path) throws IOException {
    var real = path.toRealPath();
    Readers state;
    synchronized (LogFileAccess.class) {
      refuseReserved(real);
      state = readers.computeIfAbsent(real, p -> new Readers());
      state.count++;
    }
    var closed = new AtomicBoolean();
    return () -> {
      if (!closed.compareAndSet(false, true)) return;
      boolean drained;
      synchronized (LogFileAccess.class) {
        drained = --state.count == 0;
        if (drained) readers.remove(real, state);
      }
      if (drained) state.drained.complete(null);
    };
  }

  /** Cached reads must obey a move reservation too, even though they open no new mapping. */
  static void checkReadable(Path path) throws IOException {
    var real = Files.exists(path) ? path.toRealPath() : path.toAbsolutePath().normalize();
    synchronized (LogFileAccess.class) {
      refuseReserved(real);
    }
  }

  private static void refuseReserved(Path path) throws LogFileException {
    if (moving.stream().anyMatch(path::startsWith)) {
      throw new LogFileException("Log is being moved by an import: " + path + ". Retry after the import finishes.");
    }
  }

  /** A reservation survives the wait for holders and the rename; closing it admits readers again. */
  public static Lease reserveMove(List<Path> paths) throws IOException {
    var real = new ArrayList<Path>();
    for (var path : paths) real.add(path.toRealPath());
    synchronized (LogFileAccess.class) {
      for (var path : real) {
        if (moving.stream().anyMatch(p -> p.startsWith(path) || path.startsWith(p))) {
          throw new IOException("Another import is moving: " + path);
        }
      }
      moving.addAll(real);
    }
    return () -> {
      synchronized (LogFileAccess.class) {
        moving.removeAll(real);
      }
    };
  }

  /** For callers with no cache to evict: reserve and require that all readers have already closed. */
  public static Lease move(List<Path> paths) throws IOException {
    var lease = reserveMove(paths);
    try {
      for (var path : paths) {
        if (!awaitFree(path, Duration.ZERO)) throw new IOException("Log is still held by active readers: " + path);
      }
      return lease;
    } catch (IOException | RuntimeException e) {
      lease.close();
      throw e;
    }
  }

  /** Wait outside the monitor; a reader's last close must be able to complete the wait. */
  static boolean awaitFree(Path path, Duration timeout) throws IOException {
    var real = Files.exists(path) ? path.toRealPath() : path.toAbsolutePath().normalize();
    CompletableFuture<?>[] pending;
    synchronized (LogFileAccess.class) {
      pending = readers.entrySet().stream().filter(e -> e.getKey().startsWith(real))
          .map(e -> e.getValue().drained).toArray(CompletableFuture<?>[]::new);
    }
    try {
      CompletableFuture.allOf(pending).get(timeout.toNanos(), TimeUnit.NANOSECONDS);
      return true;
    } catch (TimeoutException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for log readers: " + path, e);
    } catch (ExecutionException e) {
      throw new IOException("Could not release log readers: " + path, e);
    }
  }
}
