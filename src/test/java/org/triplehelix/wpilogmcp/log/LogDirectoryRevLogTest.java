package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for LogDirectory revlog discovery functionality.
 */
class LogDirectoryRevLogTest {

  @TempDir
  Path tempDir;

  @BeforeEach
  void setUp() {
    LogDirectory.getInstance().setLogDirectory(tempDir.toString());
  }

  @AfterEach
  void tearDown() {
    LogDirectory.getInstance().setLogDirectory(null);
    LogDirectory.getInstance().clearCache();
  }

  @Test
  void testListRevLogFilesEmpty() throws IOException {
    List<LogDirectory.RevLogFileInfo> revlogs = LogDirectory.getInstance().listRevLogFiles();
    assertTrue(revlogs.isEmpty());
  }

  @Test
  void testListRevLogFilesSingleFile() throws IOException {
    // Create a test revlog file
    Path revlogFile = tempDir.resolve("REV_20260320_143052.revlog");
    Files.writeString(revlogFile, "test content");

    List<LogDirectory.RevLogFileInfo> revlogs = LogDirectory.getInstance().listRevLogFiles();

    assertEquals(1, revlogs.size());
    LogDirectory.RevLogFileInfo info = revlogs.get(0);
    assertEquals("REV_20260320_143052.revlog", info.filename());
    assertEquals("20260320_143052", info.filenameTimestamp());
    assertNotNull(info.parsedTimestamp());
    assertEquals(2026, info.parsedTimestamp().getYear());
    assertEquals(3, info.parsedTimestamp().getMonthValue());
    assertEquals(20, info.parsedTimestamp().getDayOfMonth());
    assertEquals(14, info.parsedTimestamp().getHour());
    assertEquals(30, info.parsedTimestamp().getMinute());
    assertEquals(52, info.parsedTimestamp().getSecond());
  }

  @Test
  void testListRevLogFilesMultipleFiles() throws IOException {
    // Create multiple revlog files with different timestamps
    Files.writeString(tempDir.resolve("REV_20260320_100000.revlog"), "");
    Files.writeString(tempDir.resolve("REV_20260320_120000.revlog"), "");
    Files.writeString(tempDir.resolve("REV_20260320_140000.revlog"), "");

    List<LogDirectory.RevLogFileInfo> revlogs = LogDirectory.getInstance().listRevLogFiles();

    assertEquals(3, revlogs.size());
    // Should be sorted newest first
    assertEquals("REV_20260320_140000.revlog", revlogs.get(0).filename());
    assertEquals("REV_20260320_120000.revlog", revlogs.get(1).filename());
    assertEquals("REV_20260320_100000.revlog", revlogs.get(2).filename());
  }

  @Test
  void testListRevLogFilesWithCanBusName() throws IOException {
    Files.writeString(tempDir.resolve("REV_20260320_143052_canivore.revlog"), "");
    Files.writeString(tempDir.resolve("REV_20260320_143052_rio.revlog"), "");

    List<LogDirectory.RevLogFileInfo> revlogs = LogDirectory.getInstance().listRevLogFiles();

    assertEquals(2, revlogs.size());

    // Find the canivore one
    var canivore = revlogs.stream()
        .filter(r -> r.filename().contains("canivore"))
        .findFirst()
        .orElseThrow();
    assertEquals("canivore", canivore.canBusName());
    assertEquals("20260320_143052", canivore.filenameTimestamp());

    // Find the rio one
    var rio = revlogs.stream()
        .filter(r -> r.filename().contains("rio"))
        .findFirst()
        .orElseThrow();
    assertEquals("rio", rio.canBusName());
  }

  @Test
  void testListRevLogFilesIgnoresOtherFiles() throws IOException {
    Files.writeString(tempDir.resolve("REV_20260320_143052.revlog"), "");
    Files.writeString(tempDir.resolve("frc_26-03-20_14-30-52.wpilog"), "");
    Files.writeString(tempDir.resolve("other.txt"), "");
    Files.writeString(tempDir.resolve("REV_log.json"), "");

    List<LogDirectory.RevLogFileInfo> revlogs = LogDirectory.getInstance().listRevLogFiles();

    assertEquals(1, revlogs.size());
    assertEquals("REV_20260320_143052.revlog", revlogs.get(0).filename());
  }

  @Test
  void testListRevLogFilesInSubdirectory() throws IOException {
    Path subdir = tempDir.resolve("logs");
    Files.createDirectory(subdir);
    Files.writeString(subdir.resolve("REV_20260320_143052.revlog"), "");

    List<LogDirectory.RevLogFileInfo> revlogs = LogDirectory.getInstance().listRevLogFiles();

    assertEquals(1, revlogs.size());
  }

  @Test
  void testRevLogFileInfoTimestampMillis() throws IOException {
    Files.writeString(tempDir.resolve("REV_20260320_143052.revlog"), "");

    List<LogDirectory.RevLogFileInfo> revlogs = LogDirectory.getInstance().listRevLogFiles();
    LogDirectory.RevLogFileInfo info = revlogs.get(0);

    Long millis = info.timestampMillis();
    assertNotNull(millis);
    // Verify it's a reasonable timestamp (after 2020)
    assertTrue(millis > 1577836800000L); // Jan 1, 2020
  }

  @Test
  void testRevLogFileInfoWithInvalidFilename() throws IOException {
    // Create a file that doesn't match the expected pattern but has .revlog extension
    Files.writeString(tempDir.resolve("invalid_name.revlog"), "");

    List<LogDirectory.RevLogFileInfo> revlogs = LogDirectory.getInstance().listRevLogFiles();

    assertEquals(1, revlogs.size());
    LogDirectory.RevLogFileInfo info = revlogs.get(0);
    assertEquals("invalid_name.revlog", info.filename());
    assertNull(info.filenameTimestamp());
    assertNull(info.parsedTimestamp());
    assertNull(info.canBusName());
  }

  @Test
  void testFindRevLogsInTimeRange() throws IOException {
    // Create revlog files with different timestamps
    Files.writeString(tempDir.resolve("REV_20260320_100000.revlog"), ""); // 10:00
    Files.writeString(tempDir.resolve("REV_20260320_120000.revlog"), ""); // 12:00
    Files.writeString(tempDir.resolve("REV_20260320_140000.revlog"), ""); // 14:00
    Files.writeString(tempDir.resolve("REV_20260320_160000.revlog"), ""); // 16:00

    // Get timestamp for 12:00 on 2026-03-20
    LocalDateTime startTime = LocalDateTime.of(2026, 3, 20, 11, 30, 0);
    LocalDateTime endTime = LocalDateTime.of(2026, 3, 20, 14, 30, 0);

    long startMillis = startTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    long endMillis = endTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();

    List<LogDirectory.RevLogFileInfo> matching =
        LogDirectory.getInstance().findRevLogsInTimeRange(startMillis, endMillis, 0);

    // Should find 12:00 and 14:00
    assertEquals(2, matching.size());
    assertTrue(matching.stream().anyMatch(r -> r.filename().contains("120000")));
    assertTrue(matching.stream().anyMatch(r -> r.filename().contains("140000")));
  }

  @Test
  void testFindRevLogsInTimeRangeWithTolerance() throws IOException {
    Files.writeString(tempDir.resolve("REV_20260320_100000.revlog"), ""); // 10:00
    Files.writeString(tempDir.resolve("REV_20260320_120000.revlog"), ""); // 12:00

    LocalDateTime targetTime = LocalDateTime.of(2026, 3, 20, 10, 30, 0);
    long targetMillis = targetTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();

    // Without tolerance, should only find 10:00
    List<LogDirectory.RevLogFileInfo> noTolerance =
        LogDirectory.getInstance().findRevLogsInTimeRange(targetMillis, targetMillis, 0);
    assertEquals(0, noTolerance.size()); // 10:30 doesn't match 10:00 exactly

    // With 60 minute tolerance, should find both
    List<LogDirectory.RevLogFileInfo> withTolerance =
        LogDirectory.getInstance().findRevLogsInTimeRange(targetMillis, targetMillis, 60);
    assertEquals(1, withTolerance.size()); // 10:00 is within tolerance

    // With 120 minute tolerance
    List<LogDirectory.RevLogFileInfo> largeTolerance =
        LogDirectory.getInstance().findRevLogsInTimeRange(targetMillis, targetMillis, 120);
    assertEquals(2, largeTolerance.size()); // Both within 2 hours
  }

  @Test
  void testListRevLogFilesNotConfigured() {
    LogDirectory.getInstance().setLogDirectory(null);

    assertThrows(IOException.class, () -> LogDirectory.getInstance().listRevLogFiles());
  }

  @Test
  void testRevLogFileInfoFileSize() throws IOException {
    String content = "some test content that has a specific length";
    Path revlogFile = tempDir.resolve("REV_20260320_143052.revlog");
    Files.writeString(revlogFile, content);

    List<LogDirectory.RevLogFileInfo> revlogs = LogDirectory.getInstance().listRevLogFiles();

    assertEquals(1, revlogs.size());
    assertEquals(content.length(), revlogs.get(0).fileSize());
  }

  @Test
  void testRevLogFileInfoPath() throws IOException {
    Files.writeString(tempDir.resolve("REV_20260320_143052.revlog"), "");

    List<LogDirectory.RevLogFileInfo> revlogs = LogDirectory.getInstance().listRevLogFiles();

    assertEquals(1, revlogs.size());
    Path path = revlogs.get(0).path();
    assertTrue(path.isAbsolute());
    assertTrue(path.toString().endsWith("REV_20260320_143052.revlog"));
  }

  @Test
  void testCaseInsensitiveRevLogExtension() throws IOException {
    Files.writeString(tempDir.resolve("REV_20260320_143052.REVLOG"), "");
    Files.writeString(tempDir.resolve("REV_20260320_143053.Revlog"), "");

    List<LogDirectory.RevLogFileInfo> revlogs = LogDirectory.getInstance().listRevLogFiles();

    assertEquals(2, revlogs.size());
  }

  // ==================== listRevLogFilesInDirectory ====================

  @Test
  void testListRevLogFilesInDirectory() throws IOException {
    // Create a directory outside the configured logdir
    Path otherDir = tempDir.resolve("other");
    Files.createDirectory(otherDir);
    Files.writeString(otherDir.resolve("REV_20260320_143052.revlog"), "");

    var revlogs = LogDirectory.getInstance().listRevLogFilesInDirectory(otherDir);

    assertEquals(1, revlogs.size());
    assertEquals("REV_20260320_143052.revlog", revlogs.get(0).filename());
    assertNotNull(revlogs.get(0).parsedTimestamp());
  }

  @Test
  void testListRevLogFilesInDirectoryWalksSubdirs() throws IOException {
    // Revlog is in a subdirectory
    Path parent = tempDir.resolve("parent");
    Path child = parent.resolve("child");
    Files.createDirectories(child);
    Files.writeString(child.resolve("REV_20260320_143052.revlog"), "");

    var revlogs = LogDirectory.getInstance().listRevLogFilesInDirectory(parent);

    assertEquals(1, revlogs.size());
  }

  @Test
  void testListRevLogFilesInDirectoryHandlesNullDir() {
    var revlogs = LogDirectory.getInstance().listRevLogFilesInDirectory(null);
    assertTrue(revlogs.isEmpty());
  }

  @Test
  void testListRevLogFilesInDirectoryHandlesNonexistentDir() {
    var revlogs = LogDirectory.getInstance().listRevLogFilesInDirectory(
        tempDir.resolve("nonexistent"));
    assertTrue(revlogs.isEmpty());
  }

  @Test
  void testListRevLogFilesInDirectoryParsesNonStandardFilenames() throws IOException {
    // File with no standard REV timestamp — should still be discovered but with null timestamp
    Path dir = tempDir.resolve("custom");
    Files.createDirectory(dir);
    Files.writeString(dir.resolve("bah.revlog"), "");

    var revlogs = LogDirectory.getInstance().listRevLogFilesInDirectory(dir);

    assertEquals(1, revlogs.size());
    assertEquals("bah.revlog", revlogs.get(0).filename());
    assertNull(revlogs.get(0).parsedTimestamp());
    assertNull(revlogs.get(0).timestampMillis());
  }

  // ==================== extractRevLogInfo (package-private) ====================

  @Test
  void testExtractRevLogInfoStandardFilename() throws IOException {
    Path file = tempDir.resolve("REV_20260321_103045.revlog");
    Files.writeString(file, "data");

    var info = LogDirectory.getInstance().extractRevLogInfo(file);

    assertEquals("20260321_103045", info.filenameTimestamp());
    assertEquals(2026, info.parsedTimestamp().getYear());
    assertEquals(3, info.parsedTimestamp().getMonthValue());
    assertEquals(21, info.parsedTimestamp().getDayOfMonth());
    assertEquals(10, info.parsedTimestamp().getHour());
    assertEquals(30, info.parsedTimestamp().getMinute());
    assertEquals(45, info.parsedTimestamp().getSecond());
    assertNull(info.canBusName());
  }

  @Test
  void testExtractRevLogInfoArbitraryFilename() throws IOException {
    Path file = tempDir.resolve("bah.revlog");
    Files.writeString(file, "data");

    var info = LogDirectory.getInstance().extractRevLogInfo(file);

    assertNull(info.filenameTimestamp());
    assertNull(info.parsedTimestamp());
    assertNull(info.canBusName());
    assertEquals("bah.revlog", info.filename());
  }

  // ==================== extractCreationTime ====================

  @Test
  void testExtractCreationTimeStandardFilename() {
    Long time = LogDirectory.getInstance().extractCreationTime(
        "frc_26-03-21_10-30-45_vadc.wpilog");
    assertNotNull(time);
    assertTrue(time > 1577836800000L); // After Jan 1, 2020
  }

  @Test
  void testExtractCreationTimeNonStandardFilename() {
    Long time = LogDirectory.getInstance().extractCreationTime("poo.wpilog");
    assertNull(time);
  }

  @Test
  void testExtractCreationTimeWithSimSuffix() {
    Long time = LogDirectory.getInstance().extractCreationTime(
        "frc_26-03-21_10-30-45_vadc_sim.wpilog");
    assertNotNull(time);
  }

  // ==================== Sibling directory discovery ====================

  @Test
  void testRevLogsFoundInSiblingDirectories() throws IOException {
    // Simulate: logdir has two subdirectories — wpilog in one, revlog in the other
    Path wpilogDir = tempDir.resolve("wpilogs");
    Path revlogDir = tempDir.resolve("revlogs");
    Files.createDirectories(wpilogDir);
    Files.createDirectories(revlogDir);

    Files.writeString(wpilogDir.resolve("poo.wpilog"), "");
    Files.writeString(revlogDir.resolve("REV_20260321_103045.revlog"), "");

    // listRevLogFiles walks up to scanDepth (default 5) under tempDir (the configured logdir)
    var revlogs = LogDirectory.getInstance().listRevLogFiles();
    assertEquals(1, revlogs.size());
    assertTrue(revlogs.get(0).path().toString().contains("revlogs"));
  }

  @Test
  void testRevLogsWithArbitraryNamesFoundInSiblingDirectories() throws IOException {
    Path wpilogDir = tempDir.resolve("match1");
    Path revlogDir = tempDir.resolve("match2");
    Files.createDirectories(wpilogDir);
    Files.createDirectories(revlogDir);

    Files.writeString(wpilogDir.resolve("poo.wpilog"), "");
    Files.writeString(revlogDir.resolve("bah.revlog"), "");

    // Discovery finds the revlog even though it has no standard filename
    var revlogs = LogDirectory.getInstance().listRevLogFiles();
    assertEquals(1, revlogs.size());
    assertEquals("bah.revlog", revlogs.get(0).filename());
    // No filename timestamp — will need mtime-based matching
    assertNull(revlogs.get(0).parsedTimestamp());
  }
}
