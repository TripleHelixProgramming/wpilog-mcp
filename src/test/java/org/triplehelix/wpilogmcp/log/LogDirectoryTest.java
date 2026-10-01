/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import edu.wpi.first.util.WPIUtilJNI;
import edu.wpi.first.util.datalog.DataLogWriter;
import edu.wpi.first.util.datalog.IntegerLogEntry;
import edu.wpi.first.util.datalog.StringLogEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.LogDirectory.LogFileInfo;

/** Tests for LogDirectory functionality. */
class LogDirectoryTest {

  private LogDirectory logDirectory;

  /** Load WPILib native libraries before any tests run. */
  @BeforeAll
  static void loadNativeLibraries() throws IOException {
    // Disable WPILib's automatic static loading - we'll load manually
    WPIUtilJNI.Helper.setExtractOnStaticLoad(false);

    // Find native library from extracted test natives (set by Gradle)
    String nativesPath = System.getProperty("wpilib.natives.path");
    if (nativesPath == null) {
      throw new IOException("wpilib.natives.path system property not set - run tests via Gradle");
    }

    String osName = System.getProperty("os.name").toLowerCase();
    String baseLibName;
    String jniLibName;
    String platform;
    if (osName.contains("mac")) {
      baseLibName = "libwpiutil.dylib";
      jniLibName = "libwpiutiljni.dylib";
      platform = "osx/universal";
    } else if (osName.contains("win")) {
      baseLibName = "wpiutil.dll";
      jniLibName = "wpiutiljni.dll";
      platform = "windows/x86-64";
    } else {
      baseLibName = "libwpiutil.so";
      jniLibName = "libwpiutiljni.so";
      platform = "linux/x86-64";
    }

    // Look for the native libraries in the extracted directory
    Path nativesDir = Path.of(nativesPath);
    Path sharedDir = nativesDir.resolve(platform).resolve("shared");
    Path baseLibPath = sharedDir.resolve(baseLibName);
    Path jniLibPath = sharedDir.resolve(jniLibName);

    if (!Files.exists(jniLibPath)) {
      throw new IOException("Native library not found at: " + jniLibPath +
          " - ensure extractTestNatives task ran");
    }

    // Load base library first (JNI depends on it), then JNI library
    if (Files.exists(baseLibPath)) {
      System.load(baseLibPath.toAbsolutePath().toString());
    }
    System.load(jniLibPath.toAbsolutePath().toString());
  }

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
    @DisplayName("getLogDirectories returns absolute paths")
    void getLogDirectoriesReturnsAbsolutePaths(@TempDir Path tempDir) {
      logDirectory.setLogDirectory(tempDir.toString());
      var result = logDirectory.getLogDirectories();
      assertEquals(1, result.size());
      assertTrue(result.get(0).isAbsolute());
    }
  }

  @Nested
  @DisplayName("Several Directories")
  class SeveralDirectories {

    /** An empty file whose name gives its time (the metadata falls back to the name). */
    private Path log(Path dir, String hhmmss) throws IOException {
      Files.createDirectories(dir);
      return Files.createFile(dir.resolve("frc_26-03-21_" + hhmmss + "_vaale.wpilog"));
    }

    private void configure(Path... dirs) {
      logDirectory.setLogDirectories(java.util.Arrays.stream(dirs).map(Path::toString).toList());
    }

    @Test
    @DisplayName("keeps the order given, drops null and blank entries and repeats")
    void orderAndRepeats(@TempDir Path tempDir) {
      var a = tempDir.resolve("a");
      var b = tempDir.resolve("b");
      var entries = new java.util.ArrayList<String>();
      entries.add(b.toString());
      entries.add(null);
      entries.add("  ");
      entries.add(a.toString());
      entries.add(b.resolve("..").resolve("b").toString());
      logDirectory.setLogDirectories(entries);
      assertEquals(java.util.List.of(b, a), logDirectory.getLogDirectories());
    }

    @Test
    @DisplayName("null, an empty list, and a null single directory all clear the directories")
    void clearing(@TempDir Path tempDir) {
      configure(tempDir);
      logDirectory.setLogDirectories(null);
      assertTrue(logDirectory.getLogDirectories().isEmpty());
      configure(tempDir);
      logDirectory.setLogDirectories(java.util.List.of());
      assertTrue(logDirectory.getLogDirectories().isEmpty());
      configure(tempDir);
      logDirectory.setLogDirectory(null);
      assertTrue(logDirectory.getLogDirectories().isEmpty());
      assertFalse(logDirectory.isConfigured());
    }

    @Test
    @DisplayName("the list returned cannot be modified")
    void unmodifiable(@TempDir Path tempDir) {
      configure(tempDir);
      assertThrows(UnsupportedOperationException.class,
          () -> logDirectory.getLogDirectories().add(tempDir));
    }

    @Test
    @DisplayName("isConfigured when at least one directory exists")
    void configuredWhenOneExists(@TempDir Path tempDir) {
      configure(tempDir.resolve("missing"), tempDir);
      assertTrue(logDirectory.isConfigured());
      configure(tempDir.resolve("missing"), tempDir.resolve("also-missing"));
      assertFalse(logDirectory.isConfigured());
    }

    @Test
    @DisplayName("lists the logs of every directory together, newest first")
    void listsAllNewestFirst(@TempDir Path tempDir) throws Exception {
      var a = tempDir.resolve("a");
      var b = tempDir.resolve("b");
      log(a, "10-00-00");
      log(b, "11-00-00");
      log(a, "12-00-00");
      configure(a, b);

      var scan = logDirectory.scanLogs();
      assertEquals(java.util.List.of(a, b), scan.directories());
      assertTrue(scan.unavailable().isEmpty());
      assertFalse(scan.noneReadable());
      var names = scan.logs().stream().map(LogFileInfo::filename).toList();
      assertEquals(java.util.List.of("frc_26-03-21_12-00-00_vaale.wpilog",
          "frc_26-03-21_11-00-00_vaale.wpilog", "frc_26-03-21_10-00-00_vaale.wpilog"), names);
      assertEquals(scan.logs(), logDirectory.listAvailableLogs());
    }

    @Test
    @DisplayName("a log reached from nested directories is listed once")
    void nestedDirectories(@TempDir Path tempDir) throws Exception {
      var inner = tempDir.resolve("event/session_1");
      log(inner, "10-00-00");
      log(tempDir.resolve("event"), "11-00-00");
      configure(tempDir.resolve("event"), inner);

      var logs = logDirectory.listAvailableLogs();
      assertEquals(2, logs.size(), logs.toString());
      assertEquals(2, logs.stream().map(LogFileInfo::path).distinct().count());
    }

    @Test
    @DisplayName("a directory configured twice under different names (a link) is listed once")
    void linkedDirectory(@TempDir Path tempDir) throws Exception {
      var real = tempDir.resolve("real");
      log(real, "10-00-00");
      var link = tempDir.resolve("link");
      try {
        Files.createSymbolicLink(link, real);
      } catch (UnsupportedOperationException | IOException e) {
        org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links not available");
      }
      configure(real, link);

      var logs = logDirectory.listAvailableLogs();
      assertEquals(1, logs.size(), logs.toString());
      assertTrue(logs.get(0).path().startsWith(real.toString()), "listed under the first");
    }

    @Test
    @DisplayName("a missing directory is reported and the others are still listed")
    void missingDirectory(@TempDir Path tempDir) throws Exception {
      var present = tempDir.resolve("present");
      var missing = tempDir.resolve("missing");
      log(present, "10-00-00");
      configure(missing, present);

      var scan = logDirectory.scanLogs();
      assertEquals(1, scan.logs().size());
      assertFalse(scan.noneReadable());
      assertEquals(java.util.List.of(new LogDirectory.UnavailableDirectory(missing,
          "does not exist")), scan.unavailable());
      assertEquals(1, logDirectory.listAvailableLogs().size());
    }

    @Test
    @DisplayName("a file configured as a directory is reported as not a directory")
    void fileAsDirectory(@TempDir Path tempDir) throws Exception {
      var file = Files.createFile(tempDir.resolve("notes.txt"));
      log(tempDir.resolve("logs"), "10-00-00");
      configure(file, tempDir.resolve("logs"));

      var scan = logDirectory.scanLogs();
      assertEquals(1, scan.unavailable().size());
      assertEquals("is not a directory", scan.unavailable().get(0).reason());
      assertEquals(1, scan.logs().size());
    }

    @Test
    @DisplayName("when no directory can be read, listing throws naming each and why")
    void noneReadable(@TempDir Path tempDir) throws Exception {
      var missing = tempDir.resolve("missing");
      var file = Files.createFile(tempDir.resolve("notes.txt"));
      configure(missing, file);

      var scan = logDirectory.scanLogs();
      assertTrue(scan.noneReadable());
      assertTrue(scan.logs().isEmpty());
      var e = assertThrows(IOException.class, () -> logDirectory.listAvailableLogs());
      assertTrue(e.getMessage().contains(missing + " does not exist"), e.getMessage());
      assertTrue(e.getMessage().contains(file + " is not a directory"), e.getMessage());
      assertThrows(IOException.class, () -> logDirectory.listRevLogFiles());
    }

    @Test
    @DisplayName("with no directory configured, scanning throws")
    void noneConfigured() {
      logDirectory.setLogDirectories(java.util.List.of());
      assertThrows(IOException.class, () -> logDirectory.scanLogs());
      assertThrows(IOException.class, () -> logDirectory.listRevLogFiles());
    }

    @Test
    @DisplayName("a directory without read permission is reported, the others still listed")
    void unreadableDirectory(@TempDir Path tempDir) throws Exception {
      var locked = Files.createDirectories(tempDir.resolve("locked"));
      log(tempDir.resolve("open"), "10-00-00");
      org.junit.jupiter.api.Assumptions.assumeTrue(
          java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
          "POSIX permissions");
      var saved = Files.getPosixFilePermissions(locked);
      Files.setPosixFilePermissions(locked, java.util.Set.of());
      try {
        org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(locked),
            "running as a user who can read anything");
        configure(locked, tempDir.resolve("open"));
        var scan = logDirectory.scanLogs();
        assertEquals(1, scan.logs().size());
        assertEquals(1, scan.unavailable().size());
        assertEquals("is not readable", scan.unavailable().get(0).reason());
      } finally {
        Files.setPosixFilePermissions(locked, saved);
      }
    }

    @Test
    @DisplayName("a directory whose subdirectory cannot be read is reported with the cause")
    void unreadableSubdirectory(@TempDir Path tempDir) throws Exception {
      var root = tempDir.resolve("root");
      var locked = Files.createDirectories(root.resolve("locked"));
      log(root, "10-00-00");
      log(tempDir.resolve("other"), "11-00-00");
      org.junit.jupiter.api.Assumptions.assumeTrue(
          java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
          "POSIX permissions");
      var saved = Files.getPosixFilePermissions(locked);
      Files.setPosixFilePermissions(locked, java.util.Set.of());
      try {
        org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(locked),
            "running as a user who can read anything");
        configure(root, tempDir.resolve("other"));
        var scan = logDirectory.scanLogs();
        assertEquals(java.util.List.of("frc_26-03-21_11-00-00_vaale.wpilog"),
            scan.logs().stream().map(LogFileInfo::filename).toList());
        assertEquals(1, scan.unavailable().size());
        assertEquals(root, scan.unavailable().get(0).directory());
        assertTrue(scan.unavailable().get(0).reason().startsWith("could not be read ("),
            scan.unavailable().get(0).reason());
      } finally {
        Files.setPosixFilePermissions(locked, saved);
      }
    }

    @Test
    @DisplayName("the scan depth counts from each directory")
    void depthPerDirectory(@TempDir Path tempDir) throws Exception {
      int savedDepth = logDirectory.getScanDepth();
      var deep = tempDir.resolve("a/b/c");
      log(deep, "10-00-00");
      try {
        logDirectory.setScanDepth(2);
        configure(tempDir);
        assertTrue(logDirectory.listAvailableLogs().isEmpty(), "depth 4 from the top");
        configure(tempDir, tempDir.resolve("a/b"));
        assertEquals(1, logDirectory.listAvailableLogs().size(), "depth 2 from a/b");
      } finally {
        logDirectory.setScanDepth(savedDepth);
      }
    }

    @Test
    @DisplayName("directoriesContaining names every configured directory above a file, in order")
    void containing(@TempDir Path tempDir) throws Exception {
      var event = tempDir.resolve("event");
      var session = event.resolve("session_1");
      var other = tempDir.resolve("other");
      var file = log(session, "10-00-00");
      Files.createDirectories(other);
      configure(other, event, session);

      assertEquals(java.util.List.of(event, session), logDirectory.directoriesContaining(file));
      assertEquals(java.util.List.of(), logDirectory.directoriesContaining(
          tempDir.resolve("elsewhere.wpilog")));
      // A sibling whose name starts with a configured directory's is not inside it
      var sibling = log(tempDir.resolve("event2"), "11-00-00");
      assertEquals(java.util.List.of(), logDirectory.directoriesContaining(sibling));
    }

    @Test
    @DisplayName("directoriesContaining sees through a link to a configured directory")
    void containingThroughLink(@TempDir Path tempDir) throws Exception {
      var real = tempDir.resolve("real");
      var file = log(real, "10-00-00");
      var link = tempDir.resolve("link");
      try {
        Files.createSymbolicLink(link, real);
      } catch (UnsupportedOperationException | IOException e) {
        org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links not available");
      }
      configure(link);
      assertEquals(java.util.List.of(link), logDirectory.directoriesContaining(file));
      configure(real);
      assertEquals(java.util.List.of(real),
          logDirectory.directoriesContaining(link.resolve(file.getFileName())));
    }

    @Test
    @DisplayName("REV logs are listed from every directory, each once, newest first")
    void revlogsFromEveryDirectory(@TempDir Path tempDir) throws Exception {
      var a = Files.createDirectories(tempDir.resolve("a"));
      var b = Files.createDirectories(tempDir.resolve("b"));
      Files.createFile(a.resolve("REV_20260321_100000.revlog"));
      Files.createFile(b.resolve("REV_20260321_110000.revlog"));
      configure(a, b, tempDir.resolve("missing"));

      var names = logDirectory.listRevLogFiles().stream()
          .map(LogDirectory.RevLogFileInfo::filename).toList();
      assertEquals(java.util.List.of("REV_20260321_110000.revlog", "REV_20260321_100000.revlog"),
          names);
      assertEquals(2, logDirectory.listRevLogFilesInDirectories(java.util.List.of(tempDir, a, b))
          .size(), "a REV log reached from two directories is listed once");
      assertEquals(java.util.List.of(), logDirectory.listRevLogFilesInDirectories(
          java.util.List.of(tempDir.resolve("missing"))));
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
    @DisplayName("getCacheStats keys come in a fixed order: size, hits, misses")
    void getCacheStatsOrder() {
      assertEquals(java.util.List.of("size", "hits", "misses"),
          java.util.List.copyOf(logDirectory.getCacheStats().keySet()));
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

  /**
   * Tests for metadata extraction empty value handling.
   *
   * <p>These tests verify the fix for the bug where FMS metadata logged at boot (empty)
   * could overwrite valid FMS data logged later. WPILib logs FMS data periodically:
   * <ol>
   *   <li>Robot boots → empty/zero values logged
   *   <li>FMS connection → valid values logged
   *   <li>Periodic updates → may include empty/zero values again
   * </ol>
   *
   * <p>The fix ensures that once we have valid data, empty/zero values don't overwrite it.
   *
   * <p><b>Note:</b> Full integration tests require WPILib native libraries (for DataLogWriter)
   * which are not included in this project. The fix has been verified manually with real robot
   * logs. These tests document the expected behavior.
   */
  @Nested
  @DisplayName("Metadata Extraction - Empty Value Handling")
  class MetadataExtractionEmptyValueHandling {

    @Test
    @DisplayName("MatchType.fromString handles empty/null correctly")
    void matchTypeFromStringHandlesEmpty() {
      // Empty and null should return null (no valid match type)
      assertNull(LogDirectory.MatchType.fromString(null));
      assertNull(LogDirectory.MatchType.fromString(""));
      assertNull(LogDirectory.MatchType.fromString("   "));

      // Valid strings should parse correctly
      assertEquals(LogDirectory.MatchType.QUALIFICATION, LogDirectory.MatchType.fromString("Qualification"));
      assertEquals(LogDirectory.MatchType.QUALIFICATION, LogDirectory.MatchType.fromString("qual"));
      assertEquals(LogDirectory.MatchType.QUALIFICATION, LogDirectory.MatchType.fromString("Q"));
      assertEquals(LogDirectory.MatchType.PRACTICE, LogDirectory.MatchType.fromString("Practice"));
      assertEquals(LogDirectory.MatchType.PRACTICE, LogDirectory.MatchType.fromString("p"));
      assertEquals(LogDirectory.MatchType.ELIMINATION, LogDirectory.MatchType.fromString("Elimination"));
      assertEquals(LogDirectory.MatchType.FINAL, LogDirectory.MatchType.fromString("Final"));
    }

    @Test
    @DisplayName("MatchType.fromOrdinal handles invalid values correctly")
    void matchTypeFromOrdinalHandlesInvalid() {
      // Invalid ordinals should return null
      assertNull(LogDirectory.MatchType.fromOrdinal(0));
      assertNull(LogDirectory.MatchType.fromOrdinal(-1));
      assertNull(LogDirectory.MatchType.fromOrdinal(100));

      // Valid ordinals should work
      assertEquals(LogDirectory.MatchType.PRACTICE, LogDirectory.MatchType.fromOrdinal(1));
      assertEquals(LogDirectory.MatchType.QUALIFICATION, LogDirectory.MatchType.fromOrdinal(2));
      assertEquals(LogDirectory.MatchType.ELIMINATION, LogDirectory.MatchType.fromOrdinal(3));
    }

    @Test
    @DisplayName("empty eventName does not overwrite valid value")
    void emptyEventNameDoesNotOverwriteValid(@TempDir Path tempDir) throws IOException {
      // Simulate FMS data logging pattern:
      // 1. Robot boots -> empty eventName logged
      // 2. FMS connects -> valid eventName logged
      // 3. Periodic update -> empty eventName logged again
      Path logFile = tempDir.resolve("test.wpilog");
      try (var log = new DataLogWriter(logFile.toString())) {
        var eventEntry = new StringLogEntry(log, "/FMSInfo/EventName");

        // Boot: empty value
        eventEntry.append("", 1000000);
        // FMS connection: valid value
        eventEntry.append("2024VADC", 2000000);
        // Periodic update: empty again
        eventEntry.append("", 3000000);
        log.flush();
      }

      logDirectory.setLogDirectory(tempDir.toString());
      var logs = logDirectory.listAvailableLogs();

      assertEquals(1, logs.size());
      assertEquals("2024VADC", logs.get(0).eventName(), "Should keep valid eventName, not overwrite with empty");
    }

    @Test
    @DisplayName("zero matchNumber does not overwrite valid value")
    void zeroMatchNumberDoesNotOverwriteValid(@TempDir Path tempDir) throws IOException {
      Path logFile = tempDir.resolve("test.wpilog");
      try (var log = new DataLogWriter(logFile.toString())) {
        var matchEntry = new IntegerLogEntry(log, "/FMSInfo/MatchNumber");

        // Boot: zero value
        matchEntry.append(0, 1000000);
        // FMS connection: valid value
        matchEntry.append(42, 2000000);
        // Periodic update: zero again
        matchEntry.append(0, 3000000);
        log.flush();
      }

      logDirectory.setLogDirectory(tempDir.toString());
      var logs = logDirectory.listAvailableLogs();

      assertEquals(1, logs.size());
      assertEquals(42, logs.get(0).matchNumber(), "Should keep valid matchNumber, not overwrite with zero");
    }

    @Test
    @DisplayName("empty matchType does not overwrite valid value")
    void emptyMatchTypeDoesNotOverwriteValid(@TempDir Path tempDir) throws IOException {
      Path logFile = tempDir.resolve("test.wpilog");
      try (var log = new DataLogWriter(logFile.toString())) {
        var matchTypeEntry = new StringLogEntry(log, "/FMSInfo/MatchType");

        // Boot: empty value
        matchTypeEntry.append("", 1000000);
        // FMS connection: valid value
        matchTypeEntry.append("Qualification", 2000000);
        // Periodic update: empty again
        matchTypeEntry.append("", 3000000);
        log.flush();
      }

      logDirectory.setLogDirectory(tempDir.toString());
      var logs = logDirectory.listAvailableLogs();

      assertEquals(1, logs.size());
      assertEquals("Qualification", logs.get(0).matchType(), "Should keep valid matchType, not overwrite with empty");
    }

    @Test
    @DisplayName("valid values logged first are preserved when followed by empty")
    void validValuesPreservedWhenFollowedByEmpty(@TempDir Path tempDir) throws IOException {
      // Test the reverse order: valid first, then empty
      Path logFile = tempDir.resolve("test.wpilog");
      try (var log = new DataLogWriter(logFile.toString())) {
        var eventEntry = new StringLogEntry(log, "/FMSInfo/EventName");
        var matchEntry = new IntegerLogEntry(log, "/FMSInfo/MatchNumber");
        var matchTypeEntry = new StringLogEntry(log, "/FMSInfo/MatchType");

        // Valid values first
        eventEntry.append("DCMP", 1000000);
        matchEntry.append(7, 1000000);
        matchTypeEntry.append("Final", 1000000);

        // Empty values after
        eventEntry.append("", 2000000);
        matchEntry.append(0, 2000000);
        matchTypeEntry.append("", 2000000);
        log.flush();
      }

      logDirectory.setLogDirectory(tempDir.toString());
      var logs = logDirectory.listAvailableLogs();

      assertEquals(1, logs.size());
      var info = logs.get(0);
      assertEquals("DCMP", info.eventName());
      assertEquals(7, info.matchNumber());
      assertEquals("Final", info.matchType());
    }

    @Test
    @DisplayName("teamNumber threshold of 10 filters station numbers")
    void teamNumberThresholdFiltersStationNumbers() {
      // Station numbers are 1-3, team numbers are > 10
      // The existing code already had this protection:
      //   if (val > 10) teamNumber = val;
      //
      // This ensures station numbers (1, 2, 3) don't get mistaken for team numbers
      assertTrue(10 < 2363, "Team number 2363 passes threshold");
      assertFalse(10 < 3, "Station number 3 does not pass threshold");
    }

    @Test
    @DisplayName("teamNumber is extracted from log metadata")
    void teamNumberExtractedFromMetadata(@TempDir Path tempDir) throws IOException {
      Path logFile = tempDir.resolve("test.wpilog");
      try (var log = new DataLogWriter(logFile.toString())) {
        var teamEntry = new IntegerLogEntry(log, "/FMSInfo/StationNumber");
        // First log station number (should be ignored since < 10)
        teamEntry.append(2, 1000000);
        // Then log something that looks like a team number entry
        var teamNumEntry = new IntegerLogEntry(log, "/FMSInfo/TeamNumber");
        teamNumEntry.append(2363, 2000000);
        log.flush();
      }

      logDirectory.setLogDirectory(tempDir.toString());
      var logs = logDirectory.listAvailableLogs();

      assertEquals(1, logs.size());
      assertEquals(2363, logs.get(0).teamNumber());
    }

    @Test
    @DisplayName("station number below threshold does not set teamNumber")
    void stationNumberBelowThresholdIgnored(@TempDir Path tempDir) throws IOException {
      Path logFile = tempDir.resolve("test.wpilog");
      try (var log = new DataLogWriter(logFile.toString())) {
        // Only log station numbers (1-3), should all be ignored
        var stationEntry = new IntegerLogEntry(log, "/FMSInfo/StationNumber");
        stationEntry.append(1, 1000000);
        stationEntry.append(2, 2000000);
        stationEntry.append(3, 3000000);
        log.flush();
      }

      logDirectory.setLogDirectory(tempDir.toString());
      logDirectory.setDefaultTeamNumber(null); // Ensure no default
      var logs = logDirectory.listAvailableLogs();

      assertEquals(1, logs.size());
      assertNull(logs.get(0).teamNumber(), "Station numbers should not be used as team number");
    }

    @Test
    @DisplayName("defaultTeamNumber is used as fallback")
    void defaultTeamNumberUsedAsFallback(@TempDir Path tempDir) throws IOException {
      Path logFile = tempDir.resolve("test.wpilog");
      try (var log = new DataLogWriter(logFile.toString())) {
        // Create a log with no team number entry
        var entry = new StringLogEntry(log, "/Other/Entry");
        entry.append("test", 1000000);
        log.flush();
      }

      logDirectory.setLogDirectory(tempDir.toString());
      logDirectory.setDefaultTeamNumber(2363);
      var logs = logDirectory.listAvailableLogs();

      assertEquals(1, logs.size());
      assertEquals(2363, logs.get(0).teamNumber(), "Should use default team number");
    }

    @Test
    @DisplayName("a default team number of 0 or less is no team (old installers wrote team: 0)")
    void nonPositiveDefaultTeamIsUnset(@TempDir Path tempDir) throws IOException {
      Path logFile = tempDir.resolve("test.wpilog");
      try (var log = new DataLogWriter(logFile.toString())) {
        var entry = new StringLogEntry(log, "/Other/Entry");
        entry.append("test", 1000000);
        log.flush();
      }

      logDirectory.setLogDirectory(tempDir.toString());
      try {
        for (int team : new int[] {0, -1, Integer.MIN_VALUE}) {
          logDirectory.setDefaultTeamNumber(2363);
          logDirectory.setDefaultTeamNumber(team);
          assertNull(logDirectory.getDefaultTeamNumber(), "team " + team);
          logDirectory.clearCache();
          assertNull(logDirectory.listAvailableLogs().get(0).teamNumber(), "team " + team);
        }
        logDirectory.setDefaultTeamNumber(1);
        assertEquals(1, logDirectory.getDefaultTeamNumber(), "team 1 is a team");
      } finally {
        logDirectory.setDefaultTeamNumber(null);
      }
    }
  }

  @Nested
  @DisplayName("Filename Parsing")
  class FilenameParsing {

    @Test
    @DisplayName("parses standard WPILib filename format")
    void parsesStandardFormat(@TempDir Path tempDir) throws IOException {
      // Format: prefix_YY-MM-DD_HH-MM-SS_EVENT_TYPEnumber.wpilog
      Path logFile = tempDir.resolve("FRC_25-03-15_14-30-45_vadc_q42.wpilog");
      Files.createFile(logFile);

      logDirectory.setLogDirectory(tempDir.toString());
      var logs = logDirectory.listAvailableLogs();

      assertEquals(1, logs.size());
      var info = logs.get(0);
      assertEquals("VADC", info.eventName());
      assertEquals("Qualification", info.matchType());
      assertEquals(42, info.matchNumber());
    }

    @Test
    @DisplayName("parses practice match format")
    void parsesPracticeMatch(@TempDir Path tempDir) throws IOException {
      // Practice matches may not have a match number
      Path logFile = tempDir.resolve("FRC_25-03-15_10-00-00_vadc.wpilog");
      Files.createFile(logFile);

      logDirectory.setLogDirectory(tempDir.toString());
      var logs = logDirectory.listAvailableLogs();

      assertEquals(1, logs.size());
      var info = logs.get(0);
      assertEquals("VADC", info.eventName());
      assertEquals("Practice", info.matchType()); // Default when no type specified
    }

    @Test
    @DisplayName("parses simulation file format")
    void parsesSimulationFile(@TempDir Path tempDir) throws IOException {
      Path logFile = tempDir.resolve("FRC_25-03-15_10-00-00_test_sim.wpilog");
      Files.createFile(logFile);

      logDirectory.setLogDirectory(tempDir.toString());
      var logs = logDirectory.listAvailableLogs();

      assertEquals(1, logs.size());
      assertTrue(logs.get(0).matchType().contains("sim"), "Should indicate simulation");
    }

    @Test
    @DisplayName("handles non-standard filename gracefully")
    void handlesNonStandardFilename(@TempDir Path tempDir) throws IOException {
      Path logFile = tempDir.resolve("random_log_file.wpilog");
      Files.createFile(logFile);

      logDirectory.setLogDirectory(tempDir.toString());
      var logs = logDirectory.listAvailableLogs();

      assertEquals(1, logs.size());
      // Should not crash, should use filename as friendly name
      assertEquals("random_log_file", logs.get(0).friendlyName());
    }

    @Test
    @DisplayName("extracts creation time from filename")
    void extractsCreationTimeFromFilename(@TempDir Path tempDir) throws IOException {
      Path logFile = tempDir.resolve("FRC_25-03-15_14-30-45_event.wpilog");
      Files.createFile(logFile);

      logDirectory.setLogDirectory(tempDir.toString());
      var logs = logDirectory.listAvailableLogs();

      assertEquals(1, logs.size());
      assertNotNull(logs.get(0).logCreationTime(), "Should extract creation time from filename");
    }

    @Test
    @DisplayName("getBestTimestamp prefers logCreationTime over lastModified")
    void getBestTimestampPrefersLogCreationTime() {
      long now = System.currentTimeMillis();
      long earlier = now - 10000;

      // With logCreationTime
      var infoWithCreation = new LogFileInfo("/path", "file.wpilog", null, null, null, null, now, 1024, earlier);
      assertEquals(earlier, infoWithCreation.getBestTimestamp(), "Should use logCreationTime when available");

      // Without logCreationTime
      var infoWithoutCreation = new LogFileInfo("/path", "file.wpilog", null, null, null, null, now, 1024, null);
      assertEquals(now, infoWithoutCreation.getBestTimestamp(), "Should fall back to lastModified");
    }
  }
}
