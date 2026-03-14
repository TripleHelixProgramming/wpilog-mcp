package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.LogDirectory.LogFileInfo;

/** Tests for LogDirectory functionality. */
class LogDirectoryTest {

  private LogDirectory logDirectory;

  @BeforeEach
  void setUp() {
    logDirectory = LogDirectory.getInstance();
    // Reset the directory and cache for each test
    logDirectory.setLogDirectory(null);
    logDirectory.clearCache();
  }

  @Nested
  @DisplayName("Singleton Pattern")
  class SingletonPattern {

    @Test
    @DisplayName("returns same instance")
    void returnsSameInstance() {
      LogDirectory instance1 = LogDirectory.getInstance();
      LogDirectory instance2 = LogDirectory.getInstance();
      assertSame(instance1, instance2);
    }
  }

  @Nested
  @DisplayName("Configuration")
  class Configuration {

    @Test
    @DisplayName("isConfigured returns false when not set")
    void isConfiguredReturnsFalseWhenNotSet() {
      logDirectory.setLogDirectory(null);
      assertFalse(logDirectory.isConfigured());
    }

    @Test
    @DisplayName("isConfigured returns false for non-existent directory")
    void isConfiguredReturnsFalseForNonExistent() {
      logDirectory.setLogDirectory("/nonexistent/path/that/does/not/exist");
      assertFalse(logDirectory.isConfigured());
    }

    @Test
    @DisplayName("isConfigured returns true for valid directory")
    void isConfiguredReturnsTrueForValidDirectory(@TempDir Path tempDir) {
      logDirectory.setLogDirectory(tempDir.toString());
      assertTrue(logDirectory.isConfigured());
    }

    @Test
    @DisplayName("getLogDirectory returns absolute path")
    void getLogDirectoryReturnsAbsolutePath(@TempDir Path tempDir) {
      logDirectory.setLogDirectory(tempDir.toString());
      Path result = logDirectory.getLogDirectory();
      assertTrue(result.isAbsolute());
    }
  }

  @Nested
  @DisplayName("List Available Logs")
  class ListAvailableLogs {

    @Test
    @DisplayName("throws when not configured")
    void throwsWhenNotConfigured() {
      logDirectory.setLogDirectory(null);
      assertThrows(IOException.class, () -> logDirectory.listAvailableLogs());
    }

    @Test
    @DisplayName("returns empty list for empty directory")
    void returnsEmptyListForEmptyDirectory(@TempDir Path tempDir) throws IOException {
      logDirectory.setLogDirectory(tempDir.toString());
      var logs = logDirectory.listAvailableLogs();
      assertTrue(logs.isEmpty());
    }

    @Test
    @DisplayName("finds wpilog files in directory")
    void findsWpilogFilesInDirectory(@TempDir Path tempDir) throws IOException {
      // Create some test files
      Files.createFile(tempDir.resolve("test1.wpilog"));
      Files.createFile(tempDir.resolve("test2.wpilog"));
      Files.createFile(tempDir.resolve("other.txt")); // Should be ignored

      logDirectory.setLogDirectory(tempDir.toString());
      var logs = logDirectory.listAvailableLogs();

      assertEquals(2, logs.size());
    }

    @Test
    @DisplayName("finds wpilog files in subdirectories")
    void findsWpilogFilesInSubdirectories(@TempDir Path tempDir) throws IOException {
      Path subDir = tempDir.resolve("subdir");
      Files.createDirectory(subDir);
      Files.createFile(subDir.resolve("nested.wpilog"));
      Files.createFile(tempDir.resolve("root.wpilog"));

      logDirectory.setLogDirectory(tempDir.toString());
      var logs = logDirectory.listAvailableLogs();

      assertEquals(2, logs.size());
    }
  }

  @Nested
  @DisplayName("LogFileInfo")
  class LogFileInfoTest {

    @Test
    @DisplayName("friendlyName includes all components")
    void friendlyNameIncludesAllComponents() {
      LogFileInfo info =
          new LogFileInfo(
              "/path/to/log.wpilog",
              "log.wpilog",
              "VADC",
              "Qualification",
              42,
              2168,
              System.currentTimeMillis(),
              1024);

      assertEquals("VADC Qualification 42", info.friendlyName());
    }

    @Test
    @DisplayName("friendlyName with only event")
    void friendlyNameWithOnlyEvent() {
      LogFileInfo info =
          new LogFileInfo(
              "/path/to/log.wpilog",
              "log.wpilog",
              "VADC",
              null,
              null,
              null,
              System.currentTimeMillis(),
              1024);

      assertEquals("VADC", info.friendlyName());
    }

    @Test
    @DisplayName("friendlyName with only match type and number")
    void friendlyNameWithOnlyMatchTypeAndNumber() {
      LogFileInfo info =
          new LogFileInfo(
              "/path/to/log.wpilog",
              "log.wpilog",
              null,
              "Practice",
              5,
              null,
              System.currentTimeMillis(),
              1024);

      assertEquals("Practice 5", info.friendlyName());
    }

    @Test
    @DisplayName("friendlyName falls back to filename")
    void friendlyNameFallsBackToFilename() {
      LogFileInfo info =
          new LogFileInfo(
              "/path/to/some_log.wpilog",
              "some_log.wpilog",
              null,
              null,
              null,
              null,
              System.currentTimeMillis(),
              1024);

      assertEquals("some_log", info.friendlyName());
    }

    @Test
    @DisplayName("record stores all fields")
    void recordStoresAllFields() {
      long now = System.currentTimeMillis();
      LogFileInfo info =
          new LogFileInfo("/path/to/log.wpilog", "log.wpilog", "DCMP", "Final", 1, 2168, now, 2048);

      assertEquals("/path/to/log.wpilog", info.path());
      assertEquals("log.wpilog", info.filename());
      assertEquals("DCMP", info.eventName());
      assertEquals("Final", info.matchType());
      assertEquals(1, info.matchNumber());
      assertEquals(2168, info.teamNumber());
      assertEquals(now, info.lastModified());
      assertEquals(2048, info.fileSize());
    }
  }

  @Nested
  @DisplayName("Metadata Cache")
  class MetadataCache {

    @Test
    @DisplayName("getCacheStats returns initial zeros")
    void getCacheStatsReturnsInitialZeros() {
      var stats = logDirectory.getCacheStats();

      assertEquals(0L, stats.get("size"));
      assertEquals(0L, stats.get("hits"));
      assertEquals(0L, stats.get("misses"));
    }

    @Test
    @DisplayName("clearCache resets all counters")
    void clearCacheResetsAllCounters(@TempDir Path tempDir) throws IOException {
      // Create a file and list logs to populate cache
      Files.createFile(tempDir.resolve("test.wpilog"));
      logDirectory.setLogDirectory(tempDir.toString());
      logDirectory.listAvailableLogs();

      // Verify cache has entries
      var statsBefore = logDirectory.getCacheStats();
      assertEquals(1L, statsBefore.get("size"));
      assertTrue(statsBefore.get("misses") > 0);

      // Clear cache
      logDirectory.clearCache();

      // Verify all zeros
      var statsAfter = logDirectory.getCacheStats();
      assertEquals(0L, statsAfter.get("size"));
      assertEquals(0L, statsAfter.get("hits"));
      assertEquals(0L, statsAfter.get("misses"));
    }

    @Test
    @DisplayName("first access is a cache miss")
    void firstAccessIsCacheMiss(@TempDir Path tempDir) throws IOException {
      Files.createFile(tempDir.resolve("test.wpilog"));
      logDirectory.setLogDirectory(tempDir.toString());

      logDirectory.listAvailableLogs();

      var stats = logDirectory.getCacheStats();
      assertEquals(1L, stats.get("misses"));
      assertEquals(0L, stats.get("hits"));
    }

    @Test
    @DisplayName("repeated access is a cache hit")
    void repeatedAccessIsCacheHit(@TempDir Path tempDir) throws IOException {
      Files.createFile(tempDir.resolve("test.wpilog"));
      logDirectory.setLogDirectory(tempDir.toString());

      // First access - miss
      logDirectory.listAvailableLogs();
      // Second access - hit
      logDirectory.listAvailableLogs();

      var stats = logDirectory.getCacheStats();
      assertEquals(1L, stats.get("misses"));
      assertEquals(1L, stats.get("hits"));
    }

    @Test
    @DisplayName("multiple files are cached independently")
    void multipleFilesAreCachedIndependently(@TempDir Path tempDir) throws IOException {
      Files.createFile(tempDir.resolve("test1.wpilog"));
      Files.createFile(tempDir.resolve("test2.wpilog"));
      Files.createFile(tempDir.resolve("test3.wpilog"));
      logDirectory.setLogDirectory(tempDir.toString());

      // First access - 3 misses
      logDirectory.listAvailableLogs();
      var stats1 = logDirectory.getCacheStats();
      assertEquals(3L, stats1.get("size"));
      assertEquals(3L, stats1.get("misses"));
      assertEquals(0L, stats1.get("hits"));

      // Second access - 3 hits
      logDirectory.listAvailableLogs();
      var stats2 = logDirectory.getCacheStats();
      assertEquals(3L, stats2.get("size"));
      assertEquals(3L, stats2.get("misses"));
      assertEquals(3L, stats2.get("hits"));
    }

    @Test
    @DisplayName("modified file causes cache miss")
    void modifiedFileCausesCacheMiss(@TempDir Path tempDir) throws IOException, InterruptedException {
      Path logFile = tempDir.resolve("test.wpilog");
      Files.createFile(logFile);
      logDirectory.setLogDirectory(tempDir.toString());

      // First access - miss
      logDirectory.listAvailableLogs();
      assertEquals(1L, logDirectory.getCacheStats().get("misses"));
      assertEquals(0L, logDirectory.getCacheStats().get("hits"));

      // Modify the file (touch it to update lastModified)
      // Need a small delay to ensure different timestamp
      Thread.sleep(10);
      Files.setLastModifiedTime(
          logFile, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));

      // Second access - miss because file was modified
      logDirectory.listAvailableLogs();
      assertEquals(2L, logDirectory.getCacheStats().get("misses"));
      assertEquals(0L, logDirectory.getCacheStats().get("hits"));
    }

    @Test
    @DisplayName("new file does not affect cached entries")
    void newFileDoesNotAffectCachedEntries(@TempDir Path tempDir) throws IOException {
      Files.createFile(tempDir.resolve("existing.wpilog"));
      logDirectory.setLogDirectory(tempDir.toString());

      // First access - 1 miss for existing file
      logDirectory.listAvailableLogs();
      assertEquals(1L, logDirectory.getCacheStats().get("misses"));

      // Add a new file
      Files.createFile(tempDir.resolve("new.wpilog"));

      // Second access - 1 hit for existing, 1 miss for new
      logDirectory.listAvailableLogs();
      assertEquals(2L, logDirectory.getCacheStats().get("misses"));
      assertEquals(1L, logDirectory.getCacheStats().get("hits"));
    }

    @Test
    @DisplayName("returns same LogFileInfo instance from cache")
    void returnsSameLogFileInfoInstanceFromCache(@TempDir Path tempDir) throws IOException {
      Files.createFile(tempDir.resolve("test.wpilog"));
      logDirectory.setLogDirectory(tempDir.toString());

      var logs1 = logDirectory.listAvailableLogs();
      var logs2 = logDirectory.listAvailableLogs();

      // The LogFileInfo should be the exact same object instance
      assertSame(logs1.get(0), logs2.get(0));
    }
  }
}
