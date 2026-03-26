package org.triplehelix.wpilogmcp.log.subsystems;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Comprehensive tests for SecurityValidator to prevent path traversal attacks.
 */
class SecurityValidatorTest {
  private SecurityValidator validator;

  @TempDir
  Path tempDir;

  @BeforeEach
  void setUp() {
    validator = new SecurityValidator();
  }

  @Test
  void testNoRestrictionsWhenNoDirectoriesConfigured() throws IOException {
    // When no allowed directories are configured, all paths should be allowed
    Path anyPath = tempDir.resolve("test.wpilog");
    assertDoesNotThrow(() -> validator.validate(anyPath));
  }

  @Test
  void testAllowsPathWithinConfiguredDirectory() throws IOException {
    validator.addAllowedDirectory(tempDir);

    Path allowedPath = tempDir.resolve("test.wpilog");
    assertDoesNotThrow(() -> validator.validate(allowedPath));
  }

  @Test
  void testBlocksPathOutsideConfiguredDirectory() {
    Path allowedDir = tempDir.resolve("allowed");
    validator.addAllowedDirectory(allowedDir);

    Path forbiddenPath = tempDir.resolve("forbidden/test.wpilog");
    IOException exception = assertThrows(IOException.class, () -> validator.validate(forbiddenPath));
    assertTrue(exception.getMessage().contains("Access denied"));
  }

  @Test
  void testBlocksPathTraversalAttack() throws IOException {
    Path allowedDir = tempDir.resolve("allowed");
    Files.createDirectories(allowedDir);
    validator.addAllowedDirectory(allowedDir);

    // Try to traverse outside using ../
    Path traversalPath = allowedDir.resolve("../forbidden/test.wpilog");
    IOException exception = assertThrows(IOException.class, () -> validator.validate(traversalPath));
    assertTrue(exception.getMessage().contains("Access denied"));
  }

  @Test
  void testAllowsSubdirectories() throws IOException {
    validator.addAllowedDirectory(tempDir);

    Path subdir = tempDir.resolve("subdir/nested/test.wpilog");
    assertDoesNotThrow(() -> validator.validate(subdir));
  }

  @Test
  void testMultipleAllowedDirectories() throws IOException {
    Path dir1 = tempDir.resolve("dir1");
    Path dir2 = tempDir.resolve("dir2");
    Files.createDirectories(dir1);
    Files.createDirectories(dir2);

    validator.addAllowedDirectory(dir1);
    validator.addAllowedDirectory(dir2);

    assertDoesNotThrow(() -> validator.validate(dir1.resolve("test1.wpilog")));
    assertDoesNotThrow(() -> validator.validate(dir2.resolve("test2.wpilog")));

    Path dir3 = tempDir.resolve("dir3");
    IOException exception = assertThrows(IOException.class,
        () -> validator.validate(dir3.resolve("test3.wpilog")));
    assertTrue(exception.getMessage().contains("Access denied"));
  }

  @Test
  void testClearAllowedDirectories() throws IOException {
    validator.addAllowedDirectory(tempDir);
    validator.clearAllowedDirectories();

    // After clearing, all paths should be allowed again
    Path anyPath = tempDir.resolve("anywhere/test.wpilog");
    assertDoesNotThrow(() -> validator.validate(anyPath));
  }

  @Test
  void testGetAllowedDirectories() {
    validator.addAllowedDirectory(tempDir.resolve("dir1"));
    validator.addAllowedDirectory(tempDir.resolve("dir2"));

    var dirs = validator.getAllowedDirectories();
    assertEquals(2, dirs.size());
    assertTrue(dirs.stream().anyMatch(p -> p.endsWith("dir1")));
    assertTrue(dirs.stream().anyMatch(p -> p.endsWith("dir2")));
  }

  @Test
  void testNormalizesRelativePaths() throws IOException {
    Path absoluteDir = tempDir.toAbsolutePath().normalize();
    validator.addAllowedDirectory(absoluteDir);

    // Use a relative path that resolves to the allowed directory
    Path relativePath = Path.of(absoluteDir.toString(), "./test.wpilog");
    assertDoesNotThrow(() -> validator.validate(relativePath));
  }

  @Test
  void testValidateOrAllowCached() throws IOException {
    Path allowedDir = tempDir.resolve("allowed");
    Files.createDirectories(allowedDir);
    validator.addAllowedDirectory(allowedDir);

    // Create the cached path's parent so symlink resolution works consistently
    Path cachedDir = tempDir.resolve("cached");
    Files.createDirectories(cachedDir);
    // Use toRealPath() on the parent to match how SecurityValidator resolves paths
    Path cachedPath = cachedDir.toRealPath().resolve("test.wpilog");
    Path uncachedPath = tempDir.resolve("uncached/test.wpilog").toAbsolutePath().normalize();

    // Cached path should be allowed even if outside allowed directories
    assertDoesNotThrow(() -> validator.validateOrAllowCached(cachedPath,
        path -> path.equals(cachedPath.toString())));

    // Uncached path outside allowed directories should be blocked
    IOException exception = assertThrows(IOException.class,
        () -> validator.validateOrAllowCached(uncachedPath, path -> false));
    assertTrue(exception.getMessage().contains("Access denied"));
  }

  @Test
  void testAddAllowedDirectoryIgnoresNull() {
    assertDoesNotThrow(() -> validator.addAllowedDirectory((Path) null));
    assertDoesNotThrow(() -> validator.addAllowedDirectory((String) null));
    assertDoesNotThrow(() -> validator.addAllowedDirectory(""));
    assertDoesNotThrow(() -> validator.addAllowedDirectory("   "));
  }

  @Test
  void testConcurrentAddAndValidate() throws Exception {
    int threadCount = 8;
    var latch = new java.util.concurrent.CountDownLatch(threadCount);
    var errors = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();

    for (int i = 0; i < threadCount; i++) {
      int idx = i;
      new Thread(() -> {
        try {
          if (idx % 2 == 0) {
            // Half the threads add directories
            Path dir = tempDir.resolve("concurrent_dir_" + idx);
            Files.createDirectories(dir);
            validator.addAllowedDirectory(dir);
          } else {
            // Other half validate paths within a known-good directory
            // First ensure the directory exists and is allowed
            Path dir = tempDir.resolve("concurrent_dir_0");
            Files.createDirectories(dir);
            validator.addAllowedDirectory(dir);
            validator.validate(dir.resolve("test.wpilog"));
          }
        } catch (java.util.ConcurrentModificationException e) {
          errors.add(e);
        } catch (IOException e) {
          // IOException from validate is expected if directory not yet added
        }
        latch.countDown();
      }).start();
    }

    latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
    assertTrue(errors.isEmpty(),
        "Should have no ConcurrentModificationException, got: " + errors);
  }

  @Test
  void testConcurrentValidateFromMultipleThreads() throws Exception {
    Path dir = tempDir.resolve("shared_dir");
    Files.createDirectories(dir);
    validator.addAllowedDirectory(dir);

    int threadCount = 8;
    var latch = new java.util.concurrent.CountDownLatch(threadCount);
    var errors = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();

    for (int i = 0; i < threadCount; i++) {
      int idx = i;
      new Thread(() -> {
        try {
          validator.validate(dir.resolve("file_" + idx + ".wpilog"));
        } catch (Throwable e) {
          errors.add(e);
        }
        latch.countDown();
      }).start();
    }

    latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
    assertTrue(errors.isEmpty(),
        "All concurrent validates should succeed, got: " + errors);
  }
}
