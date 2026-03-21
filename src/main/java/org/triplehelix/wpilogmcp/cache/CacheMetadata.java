package org.triplehelix.wpilogmcp.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Metadata stored in each cache file for validation and diagnostics.
 *
 * @param cacheFormatVersion The serialization format version (increment on breaking changes)
 * @param serverVersion The wpilog-mcp version that created this cache
 * @param originalPath The original .wpilog file path (diagnostics only, not part of identity)
 * @param originalSizeBytes The original file size in bytes
 * @param originalLastModified The original file's last modified time (epoch millis)
 * @param contentFingerprint The content fingerprint (cache identity)
 * @param createdAt When this cache file was created (epoch millis)
 * @since 0.5.0
 */
public record CacheMetadata(
    int cacheFormatVersion,
    String serverVersion,
    String originalPath,
    long originalSizeBytes,
    long originalLastModified,
    String contentFingerprint,
    long createdAt) {

  /**
   * Creates metadata for a .wpilog file being cached.
   *
   * @param wpilogFile The source file
   * @param fingerprint The computed content fingerprint
   * @param formatVersion The current cache format version
   * @param serverVersion The current server version
   * @return The metadata record
   * @throws IOException if file attributes cannot be read
   */
  public static CacheMetadata forLog(
      Path wpilogFile, String fingerprint, int formatVersion, String serverVersion)
      throws IOException {
    return new CacheMetadata(
        formatVersion,
        serverVersion,
        wpilogFile.toAbsolutePath().normalize().toString(),
        Files.size(wpilogFile),
        Files.getLastModifiedTime(wpilogFile).toMillis(),
        fingerprint,
        System.currentTimeMillis());
  }

  /**
   * Checks if this metadata is still valid for the given file.
   *
   * <p>Returns true if the file size and modification time match. This is the fast path
   * that avoids recomputing the fingerprint.
   *
   * @param wpilogFile The file to check against
   * @return true if the file appears unchanged
   */
  public boolean isValidFor(Path wpilogFile) {
    try {
      long currentSize = Files.size(wpilogFile);
      long currentMtime = Files.getLastModifiedTime(wpilogFile).toMillis();
      return currentSize == originalSizeBytes && currentMtime == originalLastModified;
    } catch (IOException e) {
      return false;
    }
  }
}
