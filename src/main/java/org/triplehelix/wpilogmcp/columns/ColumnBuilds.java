/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.columns;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.cache.DiskCache;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.log.LazyParsedLog;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.LogManager;

/**
 * Builds column files in the background. A log's file is asked for when the log is read and when
 * a store finishes a file; only a log whose content its scan index proved can have one, since the
 * file is named by that content. One build runs at a time, on a daemon thread of the lowest
 * priority (which Linux ignores unless the JVM is told otherwise).
 *
 * <p>Asking touches only memory, since a store asks on its own thread. A build waits while the
 * heap has less than 512 MB free, since one entry's columns may take 200 MB of it, and while
 * recording's disk reserve is short, since a recording comes first; it is tried again 30 seconds
 * later. A file already in the cache and a build already waiting are not started again, and
 * neither is a build that failed in this process or a file the cache's limit had no room for. A
 * path that cannot be read is not remembered, since the same content may be read at another. A
 * file is published under its final name only once complete, and only if the log file still has
 * the attributes it was read with; then the cache is trimmed to its limit, keeping the files
 * readers hold.
 */
public final class ColumnBuilds implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(ColumnBuilds.class);
  static final long MIN_FREE_HEAP = 512L << 20;
  static final long RETRY_MS = 30_000;
  static final int REMEMBERED_FAILURES = 4096;

  /** A log the build reads, held until it closes so that the log is not unloaded under it. */
  public interface Lease extends AutoCloseable {
    LogData log();

    /** Whether the file still has the attributes it had when the log was read. */
    boolean unchanged();

    @Override
    void close() throws IOException;
  }

  /** Lends the log at a path, as the server admits it. */
  @FunctionalInterface
  public interface Logs {
    Lease acquire(String path) throws IOException;
  }

  /**
   * The manager's logs as their files hold them: read-only, so a build starts no REV
   * synchronization, and from the file even where a closed capture is still served from memory.
   */
  public static Logs from(LogManager manager) {
    return path -> {
      var use = manager.acquireFromFile(path);
      return new Lease() {
        @Override
        public LogData log() {
          return use.log();
        }

        /** The file read, not the path asked for: a request may name a path since moved. */
        @Override
        public boolean unchanged() {
          try {
            return use.snapshot() != null && use.snapshot().sameAs(FileSnapshot.of(Path.of(use.log().path())));
          } catch (IOException gone) {
            return false;
          }
        }

        @Override
        public void close() {
          use.close();
        }
      };
    };
  }

  private final DiskCache cache;
  private final Logs logs;
  private final LongSupplier freeHeap;
  private final BooleanSupplier recordingShort;
  private final Predicate<Path> inUse;
  private final long retryMs;
  private final ScheduledThreadPoolExecutor worker;
  private final Set<String> queued = ConcurrentHashMap.newKeySet();
  private final Set<String> failed = ConcurrentHashMap.newKeySet();
  private volatile boolean closed;

  /**
   * @param recordingShort whether recording's disk reserve is short now
   * @param inUse whether a reader holds a cache file, which trimming then keeps
   */
  public ColumnBuilds(DiskCache cache, Logs logs, BooleanSupplier recordingShort, Predicate<Path> inUse) {
    this(cache, logs, ColumnBuilds::freeHeap, recordingShort, inUse, RETRY_MS);
  }

  /**
   * @param freeHeap the heap that can still be allocated now, in bytes
   * @param retryMs how long a build that must wait waits before it is tried again
   */
  public ColumnBuilds(DiskCache cache, Logs logs, LongSupplier freeHeap, BooleanSupplier recordingShort,
      Predicate<Path> inUse, long retryMs) {
    this.cache = cache;
    this.logs = logs;
    this.freeHeap = freeHeap;
    this.recordingShort = recordingShort;
    this.inUse = inUse;
    this.retryMs = retryMs;
    worker = new ScheduledThreadPoolExecutor(1, task -> {
      var thread = new Thread(task, "column-builds");
      thread.setDaemon(true);
      thread.setPriority(Thread.MIN_PRIORITY);
      return thread;
    });
    worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  /** The heap that can still be allocated: the maximum less what is in use now. */
  static long freeHeap() {
    var runtime = Runtime.getRuntime();
    return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
  }

  /** Asks for the column file of a log just read; a log without a proven content has none. */
  public void request(String path, LogData log) {
    if (log instanceof LazyParsedLog lazy) lazy.contentSha256().ifPresent(hash -> request(path, hash));
  }

  /** Asks for the column file of the content at {@code path}, which the build checks again. */
  public void request(String path, String contentSha256) {
    if (closed || path == null || !DiskCache.isContentHash(contentSha256) || failed.contains(contentSha256)) return;
    if (!queued.add(contentSha256)) return;
    try {
      worker.execute(() -> attempt(path, contentSha256));
    } catch (RejectedExecutionException shutDown) {
      queued.remove(contentSha256);
    }
  }

  /** Whether a build of this content is waiting or running. */
  boolean queued(String contentSha256) {
    return queued.contains(contentSha256);
  }

  /** Whether no build is waiting or running. */
  boolean idle() {
    return queued.isEmpty();
  }

  private void attempt(String path, String contentSha256) {
    boolean waiting = false;
    try {
      if (closed) return;
      Optional<Path> target = cache.columnFilePath(contentSha256);
      if (target.isEmpty() || Files.exists(target.get())) return;
      if (gated()) {
        worker.schedule(() -> attempt(path, contentSha256), retryMs, TimeUnit.MILLISECONDS);
        waiting = true;
        return;
      }
      build(path, contentSha256, target.get());
    } catch (RejectedExecutionException closing) {
      // Closed while waiting: nothing more is built.
    } catch (Exception | OutOfMemoryError failure) {
      remember(contentSha256);
      logger.debug("Column file of {} not built: {}", path, failure.toString());
    } finally {
      if (!waiting) queued.remove(contentSha256);
    }
  }

  /** A gate that cannot answer holds the build, as a store refuses a copy when space is unknown. */
  private boolean gated() {
    try {
      return freeHeap.getAsLong() < MIN_FREE_HEAP || recordingShort.getAsBoolean();
    } catch (RuntimeException unanswered) {
      return true;
    }
  }

  private void build(String path, String contentSha256, Path target) throws IOException {
    Lease lease;
    try {
      lease = logs.acquire(path);
    } catch (IOException | RuntimeException unreadable) {
      logger.debug("Column file of {} not built: {}", path, unreadable.toString());
      return;
    }
    try (lease) {
      // The file must still hold the content the request named.
      if (!(lease.log() instanceof LazyParsedLog lazy) || !contentSha256.equals(lazy.contentSha256().orElse(null))) return;
      var temporary = target.resolveSibling(target.getFileName() + ".tmp." + UUID.randomUUID());
      try {
        ColumnFileWriter.write(lazy, "sha256:" + contentSha256, temporary);
        // Bytes read after a change to the file are not the content the name promises.
        if (!lease.unchanged()) return;
        try {
          Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException raced) {
          // Another server sharing the cache published the same content first.
          if (!Files.exists(target)) throw raced;
        }
      } finally {
        Files.deleteIfExists(temporary);
      }
    }
    cache.trim(inUse);
    if (!Files.exists(target)) remember(contentSha256);
  }

  private void remember(String contentSha256) {
    if (failed.size() < REMEMBERED_FAILURES) failed.add(contentSha256);
  }

  /** Stops building. A build in progress is interrupted, which abandons its file. */
  @Override
  public void close() {
    closed = true;
    worker.shutdownNow();
    try {
      worker.awaitTermination(3, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    queued.clear();
  }
}
