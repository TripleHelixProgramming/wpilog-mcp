package org.triplehelix.wpilogmcp.cache;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes a fast content fingerprint for cache identity.
 *
 * <p>Reads the first 64 KB, middle 64 KB (for files &gt; 192 KB), and last 64 KB
 * of the file plus the file size, then computes SHA-256 over those bytes.
 * This is fast even for 500 MB files (reads at most 192 KB from disk) while
 * being collision-resistant in practice.
 *
 * <p>Two copies of the same .wpilog file in different directories produce
 * identical fingerprints, enabling cache sharing.
 *
 * @since 0.5.0
 */
public final class ContentFingerprint {
  private static final int CHUNK_SIZE = 64 * 1024; // 64 KB
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private ContentFingerprint() {}

  /**
   * Computes the fingerprint for a file.
   *
   * @param file The file to fingerprint
   * @return A hex-encoded SHA-256 fingerprint
   * @throws IOException if the file cannot be read
   */
  public static String compute(Path file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      long fileSize = java.nio.file.Files.size(file);

      // Include file size in the hash
      digest.update(ByteBuffer.allocate(8).putLong(fileSize).flip());

      try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
        // Read first chunk
        int firstChunkSize = (int) Math.min(CHUNK_SIZE, fileSize);
        ByteBuffer buffer = ByteBuffer.allocate(firstChunkSize);
        while (buffer.hasRemaining()) {
          int bytesRead = channel.read(buffer, buffer.position());
          if (bytesRead <= 0) break;
        }
        buffer.flip();
        digest.update(buffer);

        // Read middle chunk (if file is large enough for 3 distinct chunks)
        if (fileSize > 3L * CHUNK_SIZE) {
          long middleChunkStart = fileSize / 2;
          buffer.clear();
          while (buffer.hasRemaining()) {
            int bytesRead = channel.read(buffer, middleChunkStart + buffer.position());
            if (bytesRead <= 0) break;
          }
          buffer.flip();
          digest.update(buffer);
        }

        // Read last chunk (if file is larger than one chunk)
        if (fileSize > CHUNK_SIZE) {
          long lastChunkStart = fileSize - CHUNK_SIZE;
          buffer.clear();
          while (buffer.hasRemaining()) {
            int bytesRead = channel.read(buffer, lastChunkStart + buffer.position());
            if (bytesRead <= 0) break;
          }
          buffer.flip();
          digest.update(buffer);
        }
      }

      return toHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 not available", e);
    }
  }

  /**
   * Returns the cache filename for a given fingerprint and file size.
   *
   * @param fingerprint The content fingerprint
   * @param fileSize The original file size in bytes
   * @return The cache filename (e.g., "a3f7b2c1...-157286432.msgpack")
   */
  public static String cacheFileName(String fingerprint, long fileSize) {
    // Use first 32 chars (128 bits) of fingerprint for collision resistance.
    // 16 chars (64 bits) was too short given the partial-file sampling strategy.
    String prefix = fingerprint.length() > 32 ? fingerprint.substring(0, 32) : fingerprint;
    return prefix + "-" + fileSize + ".msgpack";
  }

  private static String toHex(byte[] bytes) {
    char[] hex = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      int v = bytes[i] & 0xFF;
      hex[i * 2] = HEX[v >>> 4];
      hex[i * 2 + 1] = HEX[v & 0x0F];
    }
    return new String(hex);
  }
}
