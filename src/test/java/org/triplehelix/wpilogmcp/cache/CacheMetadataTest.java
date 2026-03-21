package org.triplehelix.wpilogmcp.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("CacheMetadata")
class CacheMetadataTest {

  @TempDir Path tempDir;

  @Nested
  @DisplayName("forLog factory")
  class ForLogFactory {

    @Test
    @DisplayName("creates metadata from file attributes")
    void createsFromFile() throws IOException {
      Path file = tempDir.resolve("test.wpilog");
      Files.write(file, "test content".getBytes());

      var meta = CacheMetadata.forLog(file, "abc123", 1, "0.5.0");

      assertEquals(1, meta.cacheFormatVersion());
      assertEquals("0.5.0", meta.serverVersion());
      assertTrue(meta.originalPath().endsWith("test.wpilog"));
      assertEquals(Files.size(file), meta.originalSizeBytes());
      assertEquals(Files.getLastModifiedTime(file).toMillis(), meta.originalLastModified());
      assertEquals("abc123", meta.contentFingerprint());
      assertTrue(meta.createdAt() > 0);
    }
  }

  @Nested
  @DisplayName("isValidFor")
  class IsValidFor {

    @Test
    @DisplayName("returns true for unchanged file")
    void unchangedFile() throws IOException {
      Path file = tempDir.resolve("unchanged.wpilog");
      Files.write(file, "unchanged content".getBytes());

      var meta = CacheMetadata.forLog(file, "fp", 1, "0.5.0");
      assertTrue(meta.isValidFor(file));
    }

    @Test
    @DisplayName("returns false when file size changes")
    void sizeChanged() throws IOException {
      Path file = tempDir.resolve("growing.wpilog");
      Files.write(file, "short".getBytes());
      var meta = CacheMetadata.forLog(file, "fp", 1, "0.5.0");

      Files.write(file, "much longer content now".getBytes());
      assertFalse(meta.isValidFor(file));
    }

    @Test
    @DisplayName("returns false when mtime changes")
    void mtimeChanged() throws Exception {
      Path file = tempDir.resolve("touched.wpilog");
      Files.write(file, "touch test".getBytes());
      var meta = CacheMetadata.forLog(file, "fp", 1, "0.5.0");

      Thread.sleep(50);
      Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(60)));
      assertFalse(meta.isValidFor(file));
    }

    @Test
    @DisplayName("returns false for non-existent file")
    void nonExistentFile() {
      var meta = new CacheMetadata(1, "0.5.0", "/nope", 100, 0, "fp", 0);
      assertFalse(meta.isValidFor(tempDir.resolve("nonexistent.wpilog")));
    }

    @Test
    @DisplayName("returns false when file is deleted")
    void deletedFile() throws IOException {
      Path file = tempDir.resolve("deleted.wpilog");
      Files.write(file, "will be deleted".getBytes());
      var meta = CacheMetadata.forLog(file, "fp", 1, "0.5.0");

      Files.delete(file);
      assertFalse(meta.isValidFor(file));
    }
  }
}
