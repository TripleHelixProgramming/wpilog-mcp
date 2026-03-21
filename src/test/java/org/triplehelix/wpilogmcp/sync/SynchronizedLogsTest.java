package org.triplehelix.wpilogmcp.sync;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.revlog.ParsedRevLog;
import org.triplehelix.wpilogmcp.revlog.RevLogDevice;
import org.triplehelix.wpilogmcp.revlog.RevLogSignal;
import org.triplehelix.wpilogmcp.sync.SynchronizedLogs.SyncedRevLog;

/**
 * Tests for SynchronizedLogs container.
 */
class SynchronizedLogsTest {

  private ParsedLog wpilog;
  private ParsedRevLog revlog1;
  private ParsedRevLog revlog2;
  private SyncResult syncResult1;
  private SyncResult syncResult2;

  @BeforeEach
  void setUp() {
    // Create mock wpilog
    Map<String, EntryInfo> wpilogEntries = new HashMap<>();
    wpilogEntries.put("/drive/output", new EntryInfo(1, "/drive/output", "double", ""));
    wpilogEntries.put("/sensors/gyro", new EntryInfo(2, "/sensors/gyro", "double", ""));

    Map<String, List<TimestampedValue>> wpilogValues = new HashMap<>();
    wpilogValues.put("/drive/output", createValues(100));
    wpilogValues.put("/sensors/gyro", createValues(100));

    wpilog = new ParsedLog("/test.wpilog", wpilogEntries, wpilogValues, 0, 2);

    // Create mock revlog 1
    Map<Integer, RevLogDevice> devices1 = new HashMap<>();
    devices1.put(1, new RevLogDevice(1, "SPARK MAX"));

    Map<String, RevLogSignal> signals1 = new HashMap<>();
    signals1.put("SparkMax_1/appliedOutput",
        new RevLogSignal("appliedOutput", "SparkMax_1", createValues(100), ""));

    revlog1 = new ParsedRevLog("/rio.revlog", "20260320_143052", devices1, signals1, 0, 2, 100);

    // Create mock revlog 2
    Map<Integer, RevLogDevice> devices2 = new HashMap<>();
    devices2.put(5, new RevLogDevice(5, "SPARK Flex"));

    Map<String, RevLogSignal> signals2 = new HashMap<>();
    signals2.put("SparkFlex_5/velocity",
        new RevLogSignal("velocity", "SparkFlex_5", createValues(100), ""));

    revlog2 = new ParsedRevLog("/canivore.revlog", "20260320_143052", devices2, signals2, 0, 2, 100);

    // Create sync results
    syncResult1 = new SyncResult(500_000L, 0.9, ConfidenceLevel.HIGH,
        List.of(), SyncMethod.CROSS_CORRELATION, "Good sync");
    syncResult2 = new SyncResult(600_000L, 0.7, ConfidenceLevel.MEDIUM,
        List.of(), SyncMethod.CROSS_CORRELATION, "Medium sync");
  }

  @Test
  void testBasicConstruction() {
    SynchronizedLogs syncLogs = new SynchronizedLogs(wpilog);

    assertNotNull(syncLogs.wpilog());
    assertEquals(wpilog, syncLogs.wpilog());
    assertEquals(0, syncLogs.revlogCount());
    assertFalse(syncLogs.hasAnySynchronized());
  }

  @Test
  void testWithSingleRevLog() {
    SynchronizedLogs syncLogs = new SynchronizedLogs.Builder()
        .wpilog(wpilog)
        .addRevLog(revlog1, syncResult1, "rio")
        .build();

    assertEquals(1, syncLogs.revlogCount());
    assertTrue(syncLogs.hasAnySynchronized());
    assertEquals(ConfidenceLevel.HIGH, syncLogs.overallConfidence());
  }

  @Test
  void testWithMultipleRevLogs() {
    SynchronizedLogs syncLogs = new SynchronizedLogs.Builder()
        .wpilog(wpilog)
        .addRevLog(revlog1, syncResult1, "rio")
        .addRevLog(revlog2, syncResult2, "canivore")
        .build();

    assertEquals(2, syncLogs.revlogCount());
    assertTrue(syncLogs.hasAnySynchronized());
    // Overall confidence is the lowest (MEDIUM)
    assertEquals(ConfidenceLevel.MEDIUM, syncLogs.overallConfidence());
  }

  @Test
  void testGetWpilogEntry() {
    SynchronizedLogs syncLogs = new SynchronizedLogs(wpilog);

    List<TimestampedValue> values = syncLogs.getWpilogEntry("/drive/output");
    assertNotNull(values);
    assertEquals(100, values.size());

    assertNull(syncLogs.getWpilogEntry("/nonexistent"));
  }

  @Test
  void testGetRevLogSignalWithSingleBus() {
    SynchronizedLogs syncLogs = new SynchronizedLogs.Builder()
        .wpilog(wpilog)
        .addRevLog(revlog1, syncResult1, "rio")
        .build();

    // Without bus prefix (searches all buses)
    List<TimestampedValue> values = syncLogs.getRevLogSignal("REV/SparkMax_1/appliedOutput");
    assertNotNull(values);
    assertEquals(100, values.size());
    // Check that timestamps have been adjusted by offset (0.5s = 500ms)
    assertEquals(0.5, values.get(0).timestamp(), 0.001);
  }

  @Test
  void testGetRevLogSignalWithBusPrefix() {
    SynchronizedLogs syncLogs = new SynchronizedLogs.Builder()
        .wpilog(wpilog)
        .addRevLog(revlog1, syncResult1, "rio")
        .addRevLog(revlog2, syncResult2, "canivore")
        .build();

    // With bus prefix
    List<TimestampedValue> rioValues = syncLogs.getRevLogSignal("REV/rio/SparkMax_1/appliedOutput");
    assertNotNull(rioValues);

    List<TimestampedValue> canivoreValues = syncLogs.getRevLogSignal("REV/canivore/SparkFlex_5/velocity");
    assertNotNull(canivoreValues);

    // Wrong bus should return null
    assertNull(syncLogs.getRevLogSignal("REV/canivore/SparkMax_1/appliedOutput"));
  }

  @Test
  void testGetRevLogSignalInvalidKey() {
    SynchronizedLogs syncLogs = new SynchronizedLogs.Builder()
        .wpilog(wpilog)
        .addRevLog(revlog1, syncResult1, "rio")
        .build();

    assertNull(syncLogs.getRevLogSignal("invalid_key"));
    assertNull(syncLogs.getRevLogSignal("REV/"));
    assertNull(syncLogs.getRevLogSignal("REV/SparkMax_1"));
  }

  @Test
  void testGetAllEntries() {
    SynchronizedLogs syncLogs = new SynchronizedLogs.Builder()
        .wpilog(wpilog)
        .addRevLog(revlog1, syncResult1, "rio")
        .build();

    Map<String, EntryInfo> all = syncLogs.getAllEntries();

    // Should have wpilog entries
    assertTrue(all.containsKey("/drive/output"));
    assertTrue(all.containsKey("/sensors/gyro"));

    // Should have revlog entries (without bus prefix for single revlog)
    assertTrue(all.containsKey("REV/SparkMax_1/appliedOutput"));

    // Check metadata
    EntryInfo revEntry = all.get("REV/SparkMax_1/appliedOutput");
    assertTrue(revEntry.metadata().contains("revlog"));
    assertTrue(revEntry.metadata().contains("high"));
  }

  @Test
  void testGetAllEntriesWithMultipleBuses() {
    SynchronizedLogs syncLogs = new SynchronizedLogs.Builder()
        .wpilog(wpilog)
        .addRevLog(revlog1, syncResult1, "rio")
        .addRevLog(revlog2, syncResult2, "canivore")
        .build();

    Map<String, EntryInfo> all = syncLogs.getAllEntries();

    // Should have bus prefixes when multiple revlogs
    assertTrue(all.containsKey("REV/rio/SparkMax_1/appliedOutput"));
    assertTrue(all.containsKey("REV/canivore/SparkFlex_5/velocity"));
  }

  @Test
  void testGetValues() {
    SynchronizedLogs syncLogs = new SynchronizedLogs.Builder()
        .wpilog(wpilog)
        .addRevLog(revlog1, syncResult1, "rio")
        .build();

    // Get wpilog entry
    assertNotNull(syncLogs.getValues("/drive/output"));

    // Get revlog signal
    assertNotNull(syncLogs.getValues("REV/SparkMax_1/appliedOutput"));

    // Nonexistent
    assertNull(syncLogs.getValues("/nonexistent"));
  }

  @Test
  void testGetSyncResult() {
    SynchronizedLogs syncLogs = new SynchronizedLogs.Builder()
        .wpilog(wpilog)
        .addRevLog(revlog1, syncResult1, "rio")
        .addRevLog(revlog2, syncResult2, "canivore")
        .build();

    SyncResult rio = syncLogs.getSyncResult("rio");
    assertNotNull(rio);
    assertEquals(500_000L, rio.offsetMicros());

    SyncResult canivore = syncLogs.getSyncResult("canivore");
    assertNotNull(canivore);
    assertEquals(600_000L, canivore.offsetMicros());

    assertNull(syncLogs.getSyncResult("nonexistent"));
  }

  @Test
  void testBuilderAutoInferCanBusName() {
    SynchronizedLogs syncLogs = new SynchronizedLogs.Builder()
        .wpilog(wpilog)
        .addRevLog(revlog1, syncResult1)  // No explicit name
        .build();

    // Should infer "rio" from filename or use default
    assertEquals(1, syncLogs.revlogCount());
    SyncedRevLog synced = syncLogs.revlogs().get(0);
    assertNotNull(synced.canBusName());
  }

  @Test
  void testBuilderThrowsWithoutWpilog() {
    assertThrows(IllegalStateException.class, () ->
        new SynchronizedLogs.Builder()
            .addRevLog(revlog1, syncResult1, "rio")
            .build()
    );
  }

  @Test
  void testOverallConfidenceWithFailedSync() {
    SyncResult failedResult = SyncResult.failed("Test failure");

    SynchronizedLogs syncLogs = new SynchronizedLogs.Builder()
        .wpilog(wpilog)
        .addRevLog(revlog1, syncResult1, "rio")
        .addRevLog(revlog2, failedResult, "canivore")
        .build();

    // Overall confidence should be FAILED (lowest)
    assertEquals(ConfidenceLevel.FAILED, syncLogs.overallConfidence());
  }

  @Test
  void testOverallConfidenceNoRevLogs() {
    SynchronizedLogs syncLogs = new SynchronizedLogs(wpilog);
    assertEquals(ConfidenceLevel.FAILED, syncLogs.overallConfidence());
  }

  @Test
  void testRevlogsListIsImmutable() {
    SynchronizedLogs syncLogs = new SynchronizedLogs.Builder()
        .wpilog(wpilog)
        .addRevLog(revlog1, syncResult1, "rio")
        .build();

    List<SyncedRevLog> revlogs = syncLogs.revlogs();
    assertThrows(UnsupportedOperationException.class, () ->
        revlogs.add(new SyncedRevLog(revlog2, syncResult2, "canivore"))
    );
  }

  @Test
  void testGetRevLogSignalReturnsSameInstanceOnRepeatedCalls() {
    SynchronizedLogs syncLogs = new SynchronizedLogs.Builder()
        .wpilog(wpilog)
        .addRevLog(revlog1, syncResult1, "rio")
        .build();

    List<TimestampedValue> first = syncLogs.getRevLogSignal("REV/SparkMax_1/appliedOutput");
    List<TimestampedValue> second = syncLogs.getRevLogSignal("REV/SparkMax_1/appliedOutput");

    assertNotNull(first);
    // Should return the exact same cached list instance
    assertSame(first, second, "Repeated calls should return cached instance");
  }

  // ========== Helper Methods ==========

  private List<TimestampedValue> createValues(int count) {
    List<TimestampedValue> values = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      values.add(new TimestampedValue(i * 0.02, Math.sin(i * 0.1)));
    }
    return values;
  }
}
