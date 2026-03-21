package org.triplehelix.wpilogmcp.cache;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.ParsedLog;

/**
 * Persistent disk cache for parsed WPILOG files.
 *
 * <p>Avoids reparsing log files on server restart by caching the parsed representation
 * to disk as MessagePack binary files. Cache files are keyed by content fingerprint
 * (not path), so identical files in different directories share a single cache entry.
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
  private final String serverVersion;
  private final ExecutorService writeExecutor;
  /** Tracks in-flight writes within this JVM to avoid OverlappingFileLockException. */
  private final java.util.concurrent.ConcurrentHashMap<String, Boolean> writesInProgress =
      new java.util.concurrent.ConcurrentHashMap<>();

  private volatile boolean enabled = true;
  private volatile int maxAgeDays = 30;
  private volatile long maxTotalSizeMb = 2048;

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
   * Sets the maximum age for cache files in days.
   *
   * @param days Maximum age (default: 30)
   */
  public void setMaxAgeDays(int days) {
    this.maxAgeDays = days;
  }

  /**
   * Sets the maximum total cache size in megabytes.
   *
   * @param mb Maximum size (default: 2048)
   */
  public void setMaxTotalSizeMb(long mb) {
    this.maxTotalSizeMb = mb;
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
  public void saveAsync(ParsedLog log, Path wpilogFile) {
    if (!enabled) {
      return;
    }

    writeExecutor.submit(() -> {
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
      }

      // Atomic rename (after lock is released — the file is complete)
      Files.move(tempFile, cacheFile,
          StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

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
   * Removes expired and oversized cache files.
   *
   * <p>Deletes files older than {@code maxAgeDays} and trims the cache
   * if total size exceeds {@code maxTotalSizeMb}. Runs synchronously
   * but is designed to be called from a background thread.
   */
  public void cleanup() {
    if (!enabled) return;

    try {
      Path cacheDir = cacheDirectory.getPath();
      if (!Files.isDirectory(cacheDir)) return;

      long now = System.currentTimeMillis();
      long maxAgeMs = maxAgeDays * 24L * 60 * 60 * 1000;
      long totalSize = 0;
      int deleted = 0;

      try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "*.msgpack")) {
        for (Path file : stream) {
          try {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            long size = Files.size(file);

            if (now - mtime > maxAgeMs) {
              deleteQuietly(file);
              deleted++;
              continue;
            }

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

            totalSize += size;
          } catch (IOException e) {
            logger.debug("Deleting unreadable cache file {}: {}", file, e.getMessage());
            deleteQuietly(file);
            deleted++;
          }
        }
      }

      // If still over size limit, delete oldest files until under limit
      long limitBytes = maxTotalSizeMb * 1024 * 1024;
      if (totalSize > limitBytes) {
        logger.info("Cache size ({} MB) exceeds limit ({} MB), evicting oldest files",
            totalSize / (1024 * 1024), maxTotalSizeMb);
        try (var files = Files.list(cacheDir)) {
          long[] runningSize = {totalSize};
          files
              .filter(f -> f.toString().endsWith(".msgpack"))
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
   * Shuts down the background write executor.
   */
  public void shutdown() {
    writeExecutor.shutdown();
  }

  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      // ignore
    }
  }
}
