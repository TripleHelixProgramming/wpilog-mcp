package org.triplehelix.wpilogmcp.revlog;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * Tests for RevLog data structure records.
 */
class RevLogDataStructuresTest {

  // ========== RevLogDevice Tests ==========

  @Test
  void testRevLogDeviceBasic() {
    RevLogDevice device = new RevLogDevice(1, "SPARK MAX", "1.6.2");

    assertEquals(1, device.canId());
    assertEquals("SPARK MAX", device.deviceType());
    assertEquals("1.6.2", device.firmwareVersion());
  }

  @Test
  void testRevLogDeviceWithoutFirmware() {
    RevLogDevice device = new RevLogDevice(5, "SPARK Flex");

    assertEquals(5, device.canId());
    assertEquals("SPARK Flex", device.deviceType());
    assertNull(device.firmwareVersion());
  }

  @Test
  void testRevLogDeviceKey() {
    // Device keys are normalized to PascalCase
    RevLogDevice sparkMax = new RevLogDevice(1, "SPARK MAX");
    assertEquals("SparkMax_1", sparkMax.deviceKey());

    RevLogDevice sparkFlex = new RevLogDevice(10, "SPARK Flex");
    assertEquals("SparkFlex_10", sparkFlex.deviceKey());
  }

  // ========== RevLogSignal Tests ==========

  @Test
  void testRevLogSignalBasic() {
    List<TimestampedValue> values = List.of(
        new TimestampedValue(0.0, 0.5),
        new TimestampedValue(0.02, 0.6),
        new TimestampedValue(0.04, 0.7)
    );

    RevLogSignal signal = new RevLogSignal(
        "appliedOutput",
        "SparkMax_1",
        values,
        "duty_cycle"
    );

    assertEquals("appliedOutput", signal.name());
    assertEquals("SparkMax_1", signal.deviceKey());
    assertEquals(3, signal.sampleCount());
    assertEquals("duty_cycle", signal.unit());
  }

  @Test
  void testRevLogSignalFullKey() {
    RevLogSignal signal = new RevLogSignal(
        "velocity",
        "SparkMax_5",
        List.of(),
        "rpm"
    );

    assertEquals("SparkMax_5/velocity", signal.fullKey());
  }

  @Test
  void testRevLogSignalTimestampRange() {
    List<TimestampedValue> values = List.of(
        new TimestampedValue(1.0, 100),
        new TimestampedValue(2.0, 200),
        new TimestampedValue(3.0, 300)
    );

    RevLogSignal signal = new RevLogSignal("test", "Device_1", values, "");

    assertEquals(1.0, signal.minTimestamp(), 0.001);
    assertEquals(3.0, signal.maxTimestamp(), 0.001);
  }

  @Test
  void testRevLogSignalEmptyTimestampRange() {
    RevLogSignal signal = new RevLogSignal("test", "Device_1", List.of(), "");

    assertEquals(0.0, signal.minTimestamp());
    assertEquals(0.0, signal.maxTimestamp());
  }

  // ========== RevLogEntry Tests ==========

  @Test
  void testRevLogEntryBasic() {
    byte[] data = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
    RevLogEntry entry = new RevLogEntry(
        1000000L, // 1 second in microseconds
        0x02051805,
        data,
        "Periodic Status 0"
    );

    assertEquals(1000000L, entry.timestamp());
    assertEquals(0x02051805, entry.canId());
    assertArrayEquals(data, entry.data());
    assertEquals("Periodic Status 0", entry.entryType());
  }

  @Test
  void testRevLogEntryDeviceId() {
    // Device ID is in bits 5-0
    RevLogEntry entry = new RevLogEntry(0, 0x02051805, new byte[0], "");
    assertEquals(5, entry.deviceId());

    RevLogEntry entry2 = new RevLogEntry(0, 0x0205183F, new byte[0], "");
    assertEquals(63, entry2.deviceId());
  }

  @Test
  void testRevLogEntryApiClass() {
    // API class is bits 15-10
    RevLogEntry entry = new RevLogEntry(0, 0x02051800, new byte[0], "");
    assertEquals(6, entry.apiClass());
  }

  @Test
  void testRevLogEntryApiIndex() {
    // API index is bits 9-6
    // Status 0: api_index = 0, Status 1: api_index = 1
    RevLogEntry status0 = new RevLogEntry(0, 0x02051800, new byte[0], "");
    assertEquals(0, status0.apiIndex());

    RevLogEntry status1 = new RevLogEntry(0, 0x02051840, new byte[0], "");
    assertEquals(1, status1.apiIndex());
  }

  @Test
  void testRevLogEntryManufacturerId() {
    RevLogEntry entry = new RevLogEntry(0, 0x02051800, new byte[0], "");
    assertEquals(5, entry.manufacturerId()); // REV = 5
  }

  @Test
  void testRevLogEntryDeviceType() {
    RevLogEntry entry = new RevLogEntry(0, 0x02051800, new byte[0], "");
    assertEquals(2, entry.deviceType()); // SPARK MAX = 2
  }

  @Test
  void testRevLogEntryIsPeriodicStatus() {
    RevLogEntry status = new RevLogEntry(0, 0, new byte[0], "Periodic Status 0");
    assertTrue(status.isPeriodicStatus());

    RevLogEntry other = new RevLogEntry(0, 0, new byte[0], "rawBytes");
    assertFalse(other.isPeriodicStatus());

    RevLogEntry nullType = new RevLogEntry(0, 0, new byte[0], null);
    assertFalse(nullType.isPeriodicStatus());
  }

  @Test
  void testRevLogEntryStatusFrameNumber() {
    RevLogEntry status0 = new RevLogEntry(0, 0, new byte[0], "Periodic Status 0");
    assertEquals(0, status0.statusFrameNumber());

    RevLogEntry status1 = new RevLogEntry(0, 0, new byte[0], "Periodic Status 1");
    assertEquals(1, status1.statusFrameNumber());

    RevLogEntry status7 = new RevLogEntry(0, 0, new byte[0], "Periodic Status 7");
    assertEquals(7, status7.statusFrameNumber());

    RevLogEntry notStatus = new RevLogEntry(0, 0, new byte[0], "rawBytes");
    assertEquals(-1, notStatus.statusFrameNumber());
  }

  // ========== ParsedRevLog Tests ==========

  @Test
  void testParsedRevLogBasic() {
    Map<Integer, RevLogDevice> devices = Map.of(
        1, new RevLogDevice(1, "SPARK MAX"),
        5, new RevLogDevice(5, "SPARK Flex")
    );

    Map<String, RevLogSignal> signals = Map.of(
        "SparkMax_1/appliedOutput", new RevLogSignal("appliedOutput", "SparkMax_1", List.of(), "duty_cycle"),
        "SparkMax_1/velocity", new RevLogSignal("velocity", "SparkMax_1", List.of(), "rpm"),
        "SparkFlex_5/appliedOutput", new RevLogSignal("appliedOutput", "SparkFlex_5", List.of(), "duty_cycle")
    );

    ParsedRevLog revlog = new ParsedRevLog(
        "/path/to/REV_20260320_143052.revlog",
        "20260320_143052",
        devices,
        signals,
        0.0,
        120.0,
        50000L
    );

    assertEquals("/path/to/REV_20260320_143052.revlog", revlog.path());
    assertEquals("20260320_143052", revlog.filenameTimestamp());
    assertEquals(2, revlog.deviceCount());
    assertEquals(3, revlog.signalCount());
    assertEquals(0.0, revlog.minTimestamp());
    assertEquals(120.0, revlog.maxTimestamp());
    assertEquals(120.0, revlog.duration());
    assertEquals(50000L, revlog.recordCount());
  }

  @Test
  void testParsedRevLogGetDevice() {
    RevLogDevice device1 = new RevLogDevice(1, "SPARK MAX");
    Map<Integer, RevLogDevice> devices = Map.of(1, device1);

    ParsedRevLog revlog = new ParsedRevLog(
        "/test.revlog", null, devices, Map.of(), 0, 0, 0
    );

    assertEquals(device1, revlog.getDevice(1));
    assertNull(revlog.getDevice(99));
  }

  @Test
  void testParsedRevLogGetSignal() {
    RevLogSignal signal = new RevLogSignal("velocity", "SparkMax_1", List.of(), "rpm");
    Map<String, RevLogSignal> signals = Map.of("SparkMax_1/velocity", signal);

    ParsedRevLog revlog = new ParsedRevLog(
        "/test.revlog", null, Map.of(), signals, 0, 0, 0
    );

    assertEquals(signal, revlog.getSignal("SparkMax_1/velocity"));
    assertNull(revlog.getSignal("NotExists"));

    assertEquals(signal, revlog.getSignal("SparkMax_1", "velocity"));
    assertNull(revlog.getSignal("SparkMax_1", "notexists"));
  }
}
