package org.triplehelix.wpilogmcp.cache;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.cache.SyncCacheSerializer.CachedSyncEntry;
import org.triplehelix.wpilogmcp.revlog.ParsedRevLog;
import org.triplehelix.wpilogmcp.sync.SyncResult;

/**
 * Persistent disk cache for revlog synchronization results.
 *
 * <p>Caches the parsed revlog data and cross-correlation sync result so that
 * reloading the same wpilog+revlog pair skips both parsing and correlation.
 *
 * @since 0.8.0
 */
public class SyncDiskCache {
  private static final Logger logger = LoggerFactory.getLogger(SyncDiskCache.class);

  private final CacheDirectory cacheDirectory;
  private final SyncCacheSerializer serializer;
  private final java.util.concurrent.ConcurrentHashMap<String, Boolean> writesInProgress =
      new java.util.concurrent.ConcurrentHashMap<>();
  private volatile boolean enabled = true;

  public SyncDiskCache(CacheDirectory cacheDirectory) {
    this.cacheDirectory = cacheDirectory;
    this.serializer = new SyncCacheSerializer();
  }

  public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public boolean isEnabled() { return enabled; }

  public Optional<CachedSyncEntry> load(String wpilogFingerprint, String revlogFingerprint) {
    if (!enabled) return Optional.empty();
    try {
      Path cacheFile = resolveCacheFile(wpilogFingerprint, revlogFingerprint);
      if (!Files.exists(cacheFile)) return Optional.empty();
      CachedSyncEntry entry = serializer.read(cacheFile);
      if (entry == null) {
        logger.debug("Sync cache corrupt, deleting: {}", cacheFile.getFileName());
        deleteQuietly(cacheFile);
        return Optional.empty();
      }
      logger.info("Sync cache hit: {} (confidence: {}, offset: {}ms)",
          entry.revlog().path() != null ? Path.of(entry.revlog().path()).getFileName() : "unknown",
          entry.syncResult().confidenceLevel().getLabel(),
          entry.syncResult().offsetMillis());
      return Optional.of(entry);
    } catch (IOException e) {
      logger.debug("Sync cache load failed: {}", e.getMessage());
      return Optional.empty();
    }
  }

  public void save(ParsedRevLog revlog, SyncResult syncResult,
      String wpilogFingerprint, String revlogFingerprint) {
    if (!enabled) return;
    String cacheFileName = null;
    try {
      Path cacheFile = resolveCacheFile(wpilogFingerprint, revlogFingerprint);
      cacheFileName = cacheFile.getFileName().toString();
      if (Files.exists(cacheFile)) return;
      if (writesInProgress.putIfAbsent(cacheFileName, Boolean.TRUE) != null) return;
      try {
        var entry = new CachedSyncEntry(revlog, syncResult,
            wpilogFingerprint, revlogFingerprint, System.currentTimeMillis());
        Path tempFile = cacheFile.resolveSibling(cacheFileName + ".tmp."
            + ProcessHandle.current().pid() + "." + Thread.currentThread().getId());
        try {
          serializer.write(entry, tempFile);
          Files.move(tempFile, cacheFile,
              StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
          long sizeKb = Files.size(cacheFile) / 1024;
          logger.info("Sync cached: {} ({} KB)",
              revlog.path() != null ? Path.of(revlog.path()).getFileName() : "revlog", sizeKb);
        } catch (IOException e) {
          deleteQuietly(tempFile);
          throw e;
        }
      } finally {
        writesInProgress.remove(cacheFileName);
      }
    } catch (IOException e) {
      logger.warn("Sync cache save failed: {}", e.getMessage());
    }
  }

  public int cleanupStaleFormats() {
    if (!enabled) return 0;
    try {
      Path cacheDir = cacheDirectory.getPath();
      if (!Files.isDirectory(cacheDir)) return 0;
      int deleted = 0;
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "*-sync.msgpack")) {
        for (Path file : stream) {
          int version = serializer.readFormatVersion(file);
          if (version != SyncCacheSerializer.CURRENT_FORMAT_VERSION) {
            deleteQuietly(file);
            deleted++;
          }
        }
      }
      if (deleted > 0) logger.info("Cleaned up {} stale sync cache file(s)", deleted);
      return deleted;
    } catch (IOException e) {
      logger.debug("Sync cache cleanup error: {}", e.getMessage());
      return 0;
    }
  }

  private Path resolveCacheFile(String wpilogFp, String revlogFp) throws IOException {
    Path cacheDir = cacheDirectory.getPath();
    String combinedKey = combinedFingerprint(wpilogFp, revlogFp);
    return cacheDir.resolve(combinedKey + "-sync.msgpack");
  }

  static String combinedFingerprint(String wpilogFp, String revlogFp) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      digest.update(wpilogFp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      digest.update(new byte[]{0}); // separator
      digest.update(revlogFp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      byte[] hash = digest.digest();
      var sb = new StringBuilder(32);
      for (int i = 0; i < 16; i++) sb.append(String.format("%02x", hash[i]));
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 not available", e);
    }
  }

  private static void deleteQuietly(Path file) {
    try { Files.deleteIfExists(file); } catch (IOException ignored) {}
  }
}
