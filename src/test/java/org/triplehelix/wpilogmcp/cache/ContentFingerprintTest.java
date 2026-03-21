package org.triplehelix.wpilogmcp.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ContentFingerprint")
class ContentFingerprintTest {

  @TempDir Path tempDir;

  // ==================== Identity (same content = same fingerprint) ====================

  @Nested
  @DisplayName("Content Identity")
  class ContentIdentity {

    @Test
    @DisplayName("same content in different directories produces same fingerprint")
    void sameContentDifferentPaths() throws IOException {
      byte[] content = "WPILOG test data 12345 with enough bytes to be meaningful".getBytes();
      Path file1 = tempDir.resolve("dir1/test.wpilog");
      Path file2 = tempDir.resolve("dir2/test.wpilog");
      Files.createDirectories(file1.getParent());
      Files.createDirectories(file2.getParent());
      Files.write(file1, content);
      Files.write(file2, content);

      assertEquals(ContentFingerprint.compute(file1), ContentFingerprint.compute(file2));
    }

    @Test
    @DisplayName("same content with different filenames produces same fingerprint")
    void sameContentDifferentNames() throws IOException {
      byte[] content = "identical content across names".getBytes();
      Path file1 = tempDir.resolve("match1.wpilog");
      Path file2 = tempDir.resolve("practice3.wpilog");
      Files.write(file1, content);
      Files.write(file2, content);

      assertEquals(ContentFingerprint.compute(file1), ContentFingerprint.compute(file2));
    }

    @Test
    @DisplayName("fingerprint is stable across multiple invocations")
    void stableAcrossInvocations() throws IOException {
      Path file = tempDir.resolve("stable.wpilog");
      Files.write(file, "stable content for fingerprinting".getBytes());

      String fp1 = ContentFingerprint.compute(file);
      String fp2 = ContentFingerprint.compute(file);
      String fp3 = ContentFingerprint.compute(file);

      assertEquals(fp1, fp2);
      assertEquals(fp2, fp3);
    }
  }

  // ==================== Uniqueness (different content = different fingerprint) ====================

  @Nested
  @DisplayName("Content Uniqueness")
  class ContentUniqueness {

    @Test
    @DisplayName("different content produces different fingerprint")
    void differentContent() throws IOException {
      Path file1 = tempDir.resolve("a.wpilog");
      Path file2 = tempDir.resolve("b.wpilog");
      Files.write(file1, "content A with some data".getBytes());
      Files.write(file2, "content B with other data".getBytes());

      assertNotEquals(ContentFingerprint.compute(file1), ContentFingerprint.compute(file2));
    }

    @Test
    @DisplayName("files differing by one byte produce different fingerprints")
    void singleByteDifference() throws IOException {
      byte[] content1 = "abcdefghijklmnopqrstuvwxyz".getBytes();
      byte[] content2 = "abcdefghijklmnopqrstuvwxyZ".getBytes(); // last char differs
      Path file1 = tempDir.resolve("one.wpilog");
      Path file2 = tempDir.resolve("two.wpilog");
      Files.write(file1, content1);
      Files.write(file2, content2);

      assertNotEquals(ContentFingerprint.compute(file1), ContentFingerprint.compute(file2));
    }

    @Test
    @DisplayName("same content but different size (appended data) produces different fingerprint")
    void appendedData() throws IOException {
      byte[] base = "base content".getBytes();
      byte[] extended = "base content + extra".getBytes();
      Path file1 = tempDir.resolve("base.wpilog");
      Path file2 = tempDir.resolve("extended.wpilog");
      Files.write(file1, base);
      Files.write(file2, extended);

      assertNotEquals(ContentFingerprint.compute(file1), ContentFingerprint.compute(file2));
    }

    @Test
    @DisplayName("many distinct files produce unique fingerprints")
    void manyDistinctFiles() throws IOException {
      var fingerprints = new HashSet<String>();
      for (int i = 0; i < 50; i++) {
        Path file = tempDir.resolve("file" + i + ".wpilog");
        Files.write(file, ("unique content number " + i + " with padding data").getBytes());
        fingerprints.add(ContentFingerprint.compute(file));
      }
      assertEquals(50, fingerprints.size(), "All 50 files should have unique fingerprints");
    }
  }

  // ==================== File Size Handling ====================

  @Nested
  @DisplayName("File Size Handling")
  class FileSizeHandling {

    @Test
    @DisplayName("empty file produces valid fingerprint")
    void emptyFile() throws IOException {
      Path file = tempDir.resolve("empty.wpilog");
      Files.write(file, new byte[0]);

      String fp = ContentFingerprint.compute(file);
      assertNotNull(fp);
      assertEquals(64, fp.length(), "SHA-256 hex should be 64 chars");
    }

    @Test
    @DisplayName("single byte file produces valid fingerprint")
    void singleByteFile() throws IOException {
      Path file = tempDir.resolve("tiny.wpilog");
      Files.write(file, new byte[]{0x42});

      String fp = ContentFingerprint.compute(file);
      assertNotNull(fp);
      assertEquals(64, fp.length());
    }

    @Test
    @DisplayName("file exactly 64KB (one chunk) is handled correctly")
    void exactlyOneChunk() throws IOException {
      Path file = tempDir.resolve("onechunk.wpilog");
      byte[] data = new byte[64 * 1024];
      for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 251);
      Files.write(file, data);

      String fp = ContentFingerprint.compute(file);
      assertNotNull(fp);
      assertEquals(64, fp.length());
    }

    @Test
    @DisplayName("file exactly 64KB+1 reads both chunks")
    void justOverOneChunk() throws IOException {
      byte[] data1 = new byte[64 * 1024 + 1];
      byte[] data2 = new byte[64 * 1024 + 1];
      // Same first chunk, different last byte
      for (int i = 0; i < data1.length - 1; i++) {
        data1[i] = (byte) i;
        data2[i] = (byte) i;
      }
      data1[data1.length - 1] = 0x00;
      data2[data2.length - 1] = 0x01;

      Path file1 = tempDir.resolve("over1.wpilog");
      Path file2 = tempDir.resolve("over2.wpilog");
      Files.write(file1, data1);
      Files.write(file2, data2);

      // The last byte differs and falls in the "last 64KB" chunk,
      // so fingerprints should differ
      assertNotEquals(ContentFingerprint.compute(file1), ContentFingerprint.compute(file2));
    }

    @Test
    @DisplayName("large file (256KB) only reads head and tail chunks")
    void largeFile() throws IOException {
      Path file = tempDir.resolve("large.wpilog");
      byte[] data = new byte[256 * 1024];
      for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 256);
      Files.write(file, data);

      String fp = ContentFingerprint.compute(file);
      assertNotNull(fp);
      assertEquals(64, fp.length());
    }

    @Test
    @DisplayName("files differing only in the middle produce same fingerprint (known limitation)")
    void middleDifferenceNotDetected() throws IOException {
      // Files that differ only in the middle 128KB (outside first/last 64KB)
      // will produce the same fingerprint. This is a known tradeoff for speed.
      byte[] data1 = new byte[256 * 1024];
      byte[] data2 = new byte[256 * 1024];
      // Same head and tail
      for (int i = 0; i < data1.length; i++) {
        data1[i] = (byte) (i % 256);
        data2[i] = (byte) (i % 256);
      }
      // Differ only in the middle
      data2[128 * 1024] = (byte) (data1[128 * 1024] + 1);

      Path file1 = tempDir.resolve("mid1.wpilog");
      Path file2 = tempDir.resolve("mid2.wpilog");
      Files.write(file1, data1);
      Files.write(file2, data2);

      // This documents the known limitation — not a bug
      assertEquals(ContentFingerprint.compute(file1), ContentFingerprint.compute(file2),
          "Files differing only in the middle are expected to have same fingerprint (speed tradeoff)");
    }

    @Test
    @DisplayName("file size is included in fingerprint")
    void fileSizeMatters() throws IOException {
      // Two files where the first 64KB are identical but sizes differ
      byte[] small = new byte[100];
      byte[] large = new byte[200];
      for (int i = 0; i < small.length; i++) {
        small[i] = (byte) i;
        large[i] = (byte) i;
      }
      Path file1 = tempDir.resolve("small.wpilog");
      Path file2 = tempDir.resolve("large.wpilog");
      Files.write(file1, small);
      Files.write(file2, large);

      assertNotEquals(ContentFingerprint.compute(file1), ContentFingerprint.compute(file2),
          "Different sizes should produce different fingerprints even if head content matches");
    }
  }

  // ==================== Output Format ====================

  @Nested
  @DisplayName("Output Format")
  class OutputFormat {

    @Test
    @DisplayName("fingerprint is 64-char hex string")
    void hexFormat() throws IOException {
      Path file = tempDir.resolve("format.wpilog");
      Files.write(file, "format test".getBytes());

      String fp = ContentFingerprint.compute(file);
      assertEquals(64, fp.length());
      assertTrue(fp.matches("[0-9a-f]+"), "Should be lowercase hex");
    }

    @Test
    @DisplayName("cacheFileName uses first 16 chars of fingerprint")
    void cacheFileNameFormat() {
      String fp = "a3f7b2c1d4e5f6a7890123456789abcdef0123456789abcdef0123456789abcd";
      String name = ContentFingerprint.cacheFileName(fp, 157286432);
      assertEquals("a3f7b2c1d4e5f6a7-157286432.msgpack", name);
    }

    @Test
    @DisplayName("cacheFileName handles short fingerprint gracefully")
    void cacheFileNameShortFingerprint() {
      String name = ContentFingerprint.cacheFileName("abc", 100);
      assertEquals("abc-100.msgpack", name);
    }

    @Test
    @DisplayName("cacheFileName handles zero file size")
    void cacheFileNameZeroSize() {
      String name = ContentFingerprint.cacheFileName("abcdef1234567890", 0);
      assertEquals("abcdef1234567890-0.msgpack", name);
    }
  }

  // ==================== Error Conditions ====================

  @Nested
  @DisplayName("Error Conditions")
  class ErrorConditions {

    @Test
    @DisplayName("throws IOException for non-existent file")
    void nonExistentFile() {
      Path noFile = tempDir.resolve("does-not-exist.wpilog");
      assertThrows(IOException.class, () -> ContentFingerprint.compute(noFile));
    }
  }
}
