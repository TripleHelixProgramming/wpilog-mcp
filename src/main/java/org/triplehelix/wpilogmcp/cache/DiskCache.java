/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.cache;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.columns.ColumnFileFormat;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.log.LogScan;

/**
 * Shared disk-cache capacity and cleanup owner for scan indexes and REV results.
 *
 * <p>The ordinary load path persists bounded scan indexes, not decoded values. Its caller
 * writes an index with a fresh whole-content proof while holding the source read lease, and
 * reuses one by what the index recorded: unchanged attributes, a matching CRC32C, or a fresh
 * proof (see the opener in {@code LazyParsedLog}). Legacy eager
 * serialization remains available to its existing callers; its sampled fingerprint is
 * never a scan-index proof. The configured size limit is enforced by cleanup, shared with
 * REV results, rather than a reservation on each write.
 *
 * <p>Concurrency safety:
 * <ul>
 *   <li>Writes use atomic rename (write to temp file, then {@code Files.move})</li>
 *   <li>Advisory file locks prevent concurrent writes to the same cache entry</li>
 *   <li>Reads are lock-free (atomic rename guarantees complete files)</li>
 * </ul>
 *
 * @since 0.5.0
 */
public class DiskCache {
  private static final Logger logger = LoggerFactory.getLogger(DiskCache.class);

  private final CacheDirectory cacheDirectory;
  private final DiskCacheSerializer serializer;
  private final ScanIndexSerializer scanSerializer = new ScanIndexSerializer();
  private final LongAdder scanHits = new LongAdder();
  private final LongAdder scanMisses = new LongAdder();
  private final String serverVersion;
  private final ExecutorService writeExecutor;
  /** Tracks in-flight writes within this JVM to avoid OverlappingFileLockException. */
  private final java.util.concurrent.ConcurrentHashMap<String, Boolean> writesInProgress =
      new java.util.concurrent.ConcurrentHashMap<>();

  private volatile boolean enabled = true;
  private volatile long maxTotalSizeMb = 8192;

  /**
   * Creates a new DiskCache.
   *
   * @param cacheDirectory The cache directory resolver
   * @param serverVersion The current server version string
   */
  public DiskCache(CacheDirectory cacheDirectory, String serverVersion) {
    this.cacheDirectory = cacheDirectory;
    this.serializer = new DiskCacheSerializer();
    this.serverVersion = serverVersion;
    // Single-thread executor for background writes — no need for parallelism
    this.writeExecutor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "disk-cache-writer");
      t.setDaemon(true);
      return t;
    });
  }

  /**
   * Enables or disables the disk cache.
   *
   * @param enabled true to enable (default), false to disable
   */
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
    logger.info("Disk cache {}", enabled ? "enabled" : "disabled");
  }

  /**
   * Checks if the disk cache is enabled.
   *
   * @return true if enabled
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Sets the maximum total cache size in megabytes.
   *
   * @param mb Maximum size (default: 8192)
   */
  public void setMaxTotalSizeMb(long mb) {
    this.maxTotalSizeMb = mb;
  }

  /** The suffix of a log's column file in the cache directory. */
  public static final String COLUMN_SUFFIX = "-columns.col";

  /**
   * A log's column file, named by the content it was built from and by the format and recipe
   * that wrote it, so that a file of another recipe is never read or replaced, and every copy of
   * the same bytes shares one file.
   */
  public static String columnFileName(String contentSha256) {
    if (!isContentHash(contentSha256))
      throw new IllegalArgumentException("A column file is named by a SHA-256 content hash");
    return columnPrefix() + contentSha256 + COLUMN_SUFFIX;
  }

  /** The suffix of a recording's open column file, which lives only as long as its capture's index. */
  public static final String OPEN_COLUMN_SUFFIX = "-open.col";

  /**
   * Where a recording's open column file goes, named by the capture's identity; empty when the
   * cache is off or unavailable, or when the identity is not a UUID, so a name is never built
   * from anything else.
   */
  public Optional<Path> openColumnPath(String captureIdentity) {
    if (!enabled || captureIdentity == null
        || !captureIdentity.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return Optional.empty();
    try {
      var directory = cacheDirectory.getPath();
      Files.createDirectories(directory);
      return Optional.of(directory.resolve(captureIdentity + OPEN_COLUMN_SUFFIX));
    } catch (IOException unavailable) {
      logger.debug("Open column directory unavailable: {}", unavailable.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  /** Whether a value is a SHA-256 in lowercase hex, which is how manifests and indexes write it. */
  public static boolean isContentHash(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  private static String columnPrefix() {
    return "v" + ColumnFileFormat.FORMAT_VERSION + "-r" + ColumnFileFormat.RECIPE_VERSION + "-";
  }

  /**
   * Where a log's column file is or would be; empty when the cache is off or unavailable, or
   * when the hash is not a SHA-256 in lowercase hex, so that a caller never fails on a name.
   */
  public Optional<Path> columnFilePath(String contentSha256) {
    if (!enabled || !isContentHash(contentSha256)) return Optional.empty();
    try {
      return Optional.of(cacheDirectory.getPath().resolve(columnFileName(contentSha256)));
    } catch (IOException unavailable) {
      logger.debug("Column file directory unavailable: {}", unavailable.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  /**
   * After a column file is written: deletes the oldest cache files of every kind until the cache
   * is under its limit again, keeping any file {@code inUse} names, since a reader holds it.
   */
  public void trim(Predicate<Path> inUse) {
    if (!enabled) return;
    try {
      Path cacheDir = cacheDirectory.getPath();
      if (!Files.isDirectory(cacheDir)) return;
      long total = 0;
      try (var files = Files.list(cacheDir)) {
        for (var file : (Iterable<Path>) files::iterator) {
          if (!counted(file)) continue;
          try {
            total += Files.size(file);
          } catch (IOException gone) {
            // Deleted by another sweep: it no longer counts.
          }
        }
      }
      evictOldest(cacheDir, total, inUse);
    } catch (IOException e) {
      logger.debug("Cache trim failed: {}", e.getMessage());
    }
  }

  /** Versions belong in the identity so a new recipe cannot overwrite another recipe's entry. */
  public static String scanFileName(ScanIndexSerializer.Source source) {
    return scanFileName(source.identity(), source.sizeBytes());
  }

  /** The content hash is not part of the name: a file's index is found before its bytes are read. */
  public static String scanFileName(String identity, long sizeBytes) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      digest.update(identity.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(Long.toString(sizeBytes).getBytes(StandardCharsets.US_ASCII));
      return "v" + ScanIndexSerializer.FORMAT_VERSION + "-r" + ScanIndexSerializer.RECIPE_VERSION
          + "-" + HexFormat.of().formatHex(digest.digest()) + "-scan.idx";
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  /**
   * The index stored for a file's identity and size, whatever content it was proved for. It is
   * not counted: the opener decides how far the recorded attributes vouch, then records one lookup.
   */
  public ScanIndexSerializer.Held findScanIndex(String identity, long sizeBytes) {
    if (!enabled) return null;
    try {
      return scanSerializer.hold(cacheDirectory.getPath().resolve(scanFileName(identity, sizeBytes)),
          identity, sizeBytes);
    } catch (IOException optionalCacheFailure) {
      logger.debug("Scan index lookup unavailable: {}", optionalCacheFailure.getClass().getSimpleName());
      return null;
    }
  }

  /** An eligible open counts once: a hit when it reused an index, a miss when it scanned. */
  public void recordScanLookup(boolean reused) {
    (reused ? scanHits : scanMisses).increment();
  }

  /**
   * Encode on the exclusive loading request before its mutable index is published.
   * Each attempt owns only its CREATE_NEW temporary; atomic replacement permits competing
   * processes without a lock or a background queue retaining mapped sources.
   */
  public void saveScanIndex(ScanIndexSerializer.Source source, ScanIndexSerializer.Recorded recorded,
      LogScan scan) {
    try {
      saveScanIndex(source, recorded, scan, () -> true);
    } catch (IOException impossibleSourceRefusal) {
      throw new AssertionError(impossibleSourceRefusal);
    }
  }

  @FunctionalInterface public interface IndexPublication {
    boolean allowed() throws IOException;
  }

  /** The source owner rechecks its generation after optional encoding, immediately before rename. */
  public void saveScanIndex(ScanIndexSerializer.Source source, ScanIndexSerializer.Recorded recorded,
      LogScan scan, IndexPublication publication) throws IOException {
    if (!enabled) return;
    Path target;
    try {
      target = cacheDirectory.getPath().resolve(scanFileName(source));
    } catch (IOException optionalCacheFailure) {
      logger.debug("Scan index directory unavailable: {}", optionalCacheFailure.getClass().getSimpleName());
      return;
    }
    var temporary = target.resolveSibling(target.getFileName() + ".tmp." + UUID.randomUUID());
    try {
      try {
        if (!scanSerializer.write(temporary, source, recorded, scan)) return;
      } catch (IOException optionalCacheFailure) {
        logger.debug("Scan index encoding unavailable: {}", optionalCacheFailure.getClass().getSimpleName());
        return;
      }
      // This belongs to source admission, not optional cache I/O. Its refusal must reach
      // the loader even when the index itself was encoded successfully.
      if (!publication.allowed()) return;
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException optionalCacheFailure) {
        logger.debug("Scan index publication unavailable: {}", optionalCacheFailure.getClass().getSimpleName());
      }
    } finally {
      deleteQuietly(temporary);
    }
  }

  public record ScanStats(long entryCount, long sizeBytes, long hits, long misses) {}

  /** Disk sizes describe current artifacts; lookup counters describe this process's eligible reads. */
  public ScanStats scanStats() throws IOException {
    long entries = 0;
    long bytes = 0;
    try (var files = Files.newDirectoryStream(cacheDirectory.getPath(), "*-scan.idx")) {
      for (var file : files) {
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
        try {
          bytes = Math.addExact(bytes, Files.size(file));
          entries++;
        } catch (java.nio.file.NoSuchFileException concurrentlyRemoved) {
          // Cleanup is allowed to remove an entry while health samples the directory.
        }
      }
    }
    return new ScanStats(entries, bytes, scanHits.sum(), scanMisses.sum());
  }

  /**
   * Attempts to load a parsed log from the disk cache.
   *
   * @param wpilogFile The original .wpilog file path
   * @return The cached ParsedLog, or empty if not cached or cache is stale
   */
  public Optional<ParsedLog> load(Path wpilogFile) {
    if (!enabled) {
      return Optional.empty();
    }

    try {
      Path cacheDir = cacheDirectory.getPath();
      long fileSize = Files.size(wpilogFile);
      String fingerprint = ContentFingerprint.compute(wpilogFile);
      String cacheFileName = ContentFingerprint.cacheFileName(fingerprint, fileSize);
      Path cacheFile = cacheDir.resolve(cacheFileName);

      if (!Files.exists(cacheFile)) {
        logger.debug("Cache miss (no file): {}", wpilogFile.getFileName());
        return Optional.empty();
      }

      // Read metadata header first (fast)
      CacheMetadata metadata = serializer.readMetadata(cacheFile);
      if (metadata == null) {
        logger.debug("Cache miss (corrupt metadata): {}", cacheFile);
        deleteQuietly(cacheFile);
        return Optional.empty();
      }

      // Check format version
      if (metadata.cacheFormatVersion() != DiskCacheSerializer.CURRENT_FORMAT_VERSION) {
        logger.debug("Cache miss (format version {} != {}): {}",
            metadata.cacheFormatVersion(), DiskCacheSerializer.CURRENT_FORMAT_VERSION, cacheFile);
        deleteQuietly(cacheFile);
        return Optional.empty();
      }

      // Fast validation: check size + mtime
      if (!metadata.isValidFor(wpilogFile)) {
        // File may have changed — recheck fingerprint
        String currentFingerprint = ContentFingerprint.compute(wpilogFile);
        if (!currentFingerprint.equals(metadata.contentFingerprint())) {
          logger.info("Cache invalidated (file modified): {}", wpilogFile.getFileName());
          deleteQuietly(cacheFile);
          return Optional.empty();
        }
        // Fingerprint matches but mtime changed (file was touched, not modified)
        logger.debug("Cache valid (fingerprint match despite mtime change): {}",
            wpilogFile.getFileName());
      }

      // Full deserialization
      ParsedLog log = serializer.read(cacheFile);
      if (log == null) {
        logger.warn("Cache corrupt (deserialization failed): {}", cacheFile);
        deleteQuietly(cacheFile);
        return Optional.empty();
      }

      logger.info("Cache hit: {} ({} entries, {}s)",
          wpilogFile.getFileName(), log.entryCount(), log.duration());
      return Optional.of(log);

    } catch (IOException e) {
      logger.debug("Cache load failed for {}: {}", wpilogFile, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Saves a parsed log to the disk cache asynchronously.
   *
   * <p>The save runs on a background thread so it doesn't block the MCP response.
   * Errors are logged but do not propagate.
   *
   * @param log The parsed log to cache
   * @param wpilogFile The original .wpilog file path
   */
  public java.util.concurrent.Future<?> saveAsync(ParsedLog log, Path wpilogFile) {
    if (!enabled) {
      return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    return writeExecutor.submit(() -> {
      try {
        save(log, wpilogFile);
      } catch (Exception e) {
        logger.warn("Background cache save failed for {}: {}",
            wpilogFile.getFileName(), e.getMessage());
      }
    });
  }

  /**
   * Saves a parsed log to the disk cache synchronously.
   *
   * @param log The parsed log to cache
   * @param wpilogFile The original .wpilog file path
   * @throws IOException if the save fails
   */
  public void save(ParsedLog log, Path wpilogFile) throws IOException {
    if (!enabled) {
      return;
    }
    Path cacheDir = cacheDirectory.getPath();
    long fileSize = Files.size(wpilogFile);
    String fingerprint = ContentFingerprint.compute(wpilogFile);
    String cacheFileName = ContentFingerprint.cacheFileName(fingerprint, fileSize);
    Path cacheFile = cacheDir.resolve(cacheFileName);

    // Skip if already cached
    if (Files.exists(cacheFile)) {
      logger.debug("Cache already exists: {}", cacheFileName);
      return;
    }

    // In-process deduplication: skip if another thread is already writing this file.
    // FileLock is per-JVM (not per-thread), so we need this to avoid
    // OverlappingFileLockException from concurrent threads in the same process.
    if (writesInProgress.putIfAbsent(cacheFileName, Boolean.TRUE) != null) {
      logger.debug("Another thread is writing cache for {}, skipping", cacheFileName);
      return;
    }

    CacheMetadata metadata = CacheMetadata.forLog(
        wpilogFile, fingerprint, DiskCacheSerializer.CURRENT_FORMAT_VERSION, serverVersion);

    // Lock a shared lock file so concurrent processes skip redundant work.
    Path lockFile = cacheDir.resolve(cacheFileName + ".lock");
    Path tempFile = cacheDir.resolve(cacheFileName + ".tmp."
        + ProcessHandle.current().pid() + "." + Thread.currentThread().getId());
    try {
      try (FileChannel lockChannel = FileChannel.open(lockFile,
              StandardOpenOption.CREATE, StandardOpenOption.WRITE);
           FileLock lock = lockChannel.tryLock()) {

        if (lock == null) {
          logger.debug("Another process is writing cache for {}, skipping", cacheFileName);
          return;
        }

        // Re-check after acquiring lock (another process may have finished)
        if (Files.exists(cacheFile)) {
          logger.debug("Cache appeared while waiting for lock: {}", cacheFileName);
          return;
        }

        serializer.write(log, tempFile, metadata);

        // Atomic rename inside lock scope to prevent races with other processes
        Files.move(tempFile, cacheFile,
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      }

      long cacheSizeKb = Files.size(cacheFile) / 1024;
      logger.info("Cached {} ({} KB)", wpilogFile.getFileName(), cacheSizeKb);

    } catch (IOException e) {
      // Clean up temp file on failure
      deleteQuietly(tempFile);
      throw e;
    } finally {
      writesInProgress.remove(cacheFileName);
      // Clean up lock file (best effort)
      deleteQuietly(lockFile);
    }
  }

  /**
   * Removes oversized and stale-format cache files.
   *
   * <p>Deletes cache files with incompatible format versions, then trims
   * if total size exceeds {@code maxTotalSizeMb}. Runs synchronously
   * but is designed to be called from a background thread.
   */
  /**
   * Creates and reports the cache directory now. The cleanup that would otherwise do it first runs on
   * a background thread, so a server that stopped at once could exit before saying which cache it uses.
   */
  public void prepareDirectory() {
    if (!enabled) return;
    try { cacheDirectory.getPath(); }
    catch (IOException unavailable) { logger.debug("Cache directory unavailable: {}", unavailable.getMessage()); }
  }

  public void cleanup() {
    if (!enabled) return;

    try {
      Path cacheDir = cacheDirectory.getPath();
      if (!Files.isDirectory(cacheDir)) return;

      long now = System.currentTimeMillis();
      long totalSize = 0;
      int deleted = 0;

      // Index inspection streams the checksum and structure without allocating offset arrays.
      try (var indexes = Files.newDirectoryStream(cacheDir, "*-scan.idx")) {
        for (var file : indexes) {
          var header = scanSerializer.inspect(file);
          if (header == null) {
            deleteQuietly(file);
            deleted++;
          } else {
            totalSize += header.encodedBytes();
          }
        }
      }

      // Also clean up stale sync cache files
      var syncSerializer = new SyncCacheSerializer();
      try (DirectoryStream<Path> syncStream = Files.newDirectoryStream(cacheDir, "*-sync.msgpack")) {
        for (Path file : syncStream) {
          try {
            long size = Files.size(file);
            int version = syncSerializer.readFormatVersion(file);
            if (version != SyncCacheSerializer.CURRENT_FORMAT_VERSION) {
              deleteQuietly(file);
              deleted++;
            } else {
              totalSize += size;
            }
          } catch (IOException e) {
            deleteQuietly(file);
            deleted++;
          }
        }
      }

      try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "*.msgpack")) {
        for (Path file : stream) {
          // Skip sync cache files — already handled above
          if (file.getFileName().toString().endsWith("-sync.msgpack")) continue;
          try {
            long size = Files.size(file);

            // Check format version — delete stale or unreadable cache files
            CacheMetadata meta = serializer.readMetadata(file);
            if (meta == null) {
              logger.debug("Deleting unreadable cache file: {}", file.getFileName());
              deleteQuietly(file);
              deleted++;
              continue;
            }
            if (meta.cacheFormatVersion() != DiskCacheSerializer.CURRENT_FORMAT_VERSION) {
              deleteQuietly(file);
              deleted++;
              continue;
            }

            // Only count files that passed the format version check toward total size.
            // Deleted stale files should not inflate the total and trigger unnecessary eviction.
            totalSize += size;
          } catch (IOException e) {
            logger.debug("Deleting unreadable cache file {}: {}", file, e.getMessage());
            deleteQuietly(file);
            deleted++;
          }
        }
      }

      // A column file of another format or recipe, or whose header is damaged, is deleted; the
      // rest count toward the limit. A damaged body is found when the file is opened.
      try (var columns = Files.newDirectoryStream(cacheDir, "*" + COLUMN_SUFFIX)) {
        for (var file : columns) {
          if (!file.getFileName().toString().startsWith(columnPrefix()) || !currentColumnHeader(file)) {
            deleteQuietly(file);
            deleted++;
            continue;
          }
          try {
            totalSize += Files.size(file);
          } catch (IOException gone) {
            deleted++;
          }
        }
      }

      // An open column file is never read after its process, so one last written before this
      // process started goes: a server that stopped or crashed left it. A running server sharing
      // the cache may have last written one before then too, an idle index kept for resumption;
      // deleting it removes only the name, since that server's open channel goes on reading and
      // writing the file, and its bytes are left uncounted here. The newer ones count toward the
      // limit.
      long started = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
      try (var open = Files.newDirectoryStream(cacheDir, "*" + OPEN_COLUMN_SUFFIX)) {
        for (var file : open) {
          try {
            if (Files.getLastModifiedTime(file).toMillis() < started) {
              deleteQuietly(file);
              deleted++;
            } else {
              totalSize += Files.size(file);
            }
          } catch (IOException gone) {
            // Deleted by its owner meanwhile.
          }
        }
      }

      // If still over size limit, delete oldest files until under limit
      evictOldest(cacheDir, totalSize, file -> false);

      if (deleted > 0) {
        logger.info("Cleaned up {} expired cache files", deleted);
      }

      // Clean up orphaned lock files
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "*.lock")) {
        for (Path lockFile : stream) {
          try {
            long mtime = Files.getLastModifiedTime(lockFile).toMillis();
            if (now - mtime > 60 * 60 * 1000) { // Older than 1 hour
              deleteQuietly(lockFile);
            }
          } catch (IOException e) {
            // ignore
          }
        }
      }

      // Also clean up any orphaned temp files
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "*.tmp.*")) {
        for (Path tempFile : stream) {
          try {
            long mtime = Files.getLastModifiedTime(tempFile).toMillis();
            if (now - mtime > 60 * 60 * 1000) { // Older than 1 hour
              deleteQuietly(tempFile);
            }
          } catch (IOException e) {
            // ignore
          }
        }
      }

    } catch (IOException e) {
      logger.warn("Cache cleanup failed: {}", e.getMessage());
    }
  }

  /**
   * The files the size limit counts: parsed logs, REV results, scan indexes and column files, and
   * recordings' open column files, which are never evicted, since a capture is writing them.
   */
  private static boolean counted(Path file) {
    var name = file.getFileName().toString();
    return name.endsWith(".msgpack") || name.endsWith("-scan.idx") || name.endsWith(COLUMN_SUFFIX)
        || name.endsWith(OPEN_COLUMN_SUFFIX);
  }

  private static boolean evictable(Path file) {
    return counted(file) && !file.getFileName().toString().endsWith(OPEN_COLUMN_SUFFIX);
  }

  /** Deletes the oldest counted files, by modification time, until the total is under the limit. */
  private void evictOldest(Path cacheDir, long totalSize, Predicate<Path> inUse) throws IOException {
    long limitBytes = maxTotalSizeMb * 1024 * 1024;
    if (totalSize <= limitBytes) return;
    logger.info("Cache size ({} MB) exceeds limit ({} MB), evicting oldest files",
        totalSize / (1024 * 1024), maxTotalSizeMb);
    try (var files = Files.list(cacheDir)) {
      long[] runningSize = {totalSize};
      files
          .filter(DiskCache::evictable)
          .filter(f -> !inUse.test(f))
          .sorted((a, b) -> {
            try {
              return Long.compare(
                  Files.getLastModifiedTime(a).toMillis(),
                  Files.getLastModifiedTime(b).toMillis());
            } catch (IOException e) {
              return 0;
            }
          })
          .takeWhile(f -> runningSize[0] > limitBytes)
          .forEach(f -> {
            try {
              runningSize[0] -= Files.size(f);
              deleteQuietly(f);
            } catch (Exception e) {
              // ignore
            }
          });
    }
  }

  /** Whether a file starts with a column file header of the current format and recipe. */
  private static boolean currentColumnHeader(Path file) {
    try (var channel = java.nio.channels.FileChannel.open(file)) {
      var header = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      while (header.hasRemaining() && channel.read(header) >= 0) {
        // keep reading until the header is complete or the file ends
      }
      return !header.hasRemaining() && header.getLong(0) == ColumnFileFormat.MAGIC
          && header.getInt(8) == ColumnFileFormat.FORMAT_VERSION && header.getInt(12) == ColumnFileFormat.RECIPE_VERSION;
    } catch (IOException unreadable) {
      return false;
    }
  }

  /**
   * Shuts down the background write executor.
   */
  public void shutdown() {
    writeExecutor.shutdown();
    try {
      writeExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      // ignore
    }
  }
}
