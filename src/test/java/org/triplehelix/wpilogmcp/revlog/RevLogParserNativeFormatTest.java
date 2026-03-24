package org.triplehelix.wpilogmcp.revlog;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.revlog.dbc.DbcDatabase;
import org.triplehelix.wpilogmcp.revlog.dbc.DbcLoader;

/**
 * Tests for RevLogParser native binary format parsing.
 *
 * <p>Creates synthetic native-format byte arrays and verifies correct parsing
 * of firmware entries, periodic status entries, device detection, and edge cases.
 */
class RevLogParserNativeFormatTest {

  @TempDir
  Path tempDir;

  private RevLogParser parser;

  @BeforeEach
  void setUp() throws IOException {
    DbcDatabase dbc = new DbcLoader().load(null);
    parser = new RevLogParser(dbc);
  }

  /**
   * Builds a native revlog record.
   * Format: [bitfield(1)] [entryId(variable)] [payloadSize(variable)] [payload]
   */
  private byte[] buildRecord(int entryId, byte[] payload) {
    // Use 1-byte entry ID and 1-byte payload size (bitfield = 0x00)
    int entryIdLen = entryId <= 0xFF ? 1 : 2;
    int payloadSizeLen = payload.length <= 0xFF ? 1 : 2;
    int bitfield = ((entryIdLen - 1) & 0x03) | (((payloadSizeLen - 1) & 0x03) << 2);

    var buf = ByteBuffer.allocate(1 + entryIdLen + payloadSizeLen + payload.length)
        .order(ByteOrder.LITTLE_ENDIAN);
    buf.put((byte) bitfield);
    if (entryIdLen == 1) buf.put((byte) entryId);
    else buf.putShort((short) entryId);
    if (payloadSizeLen == 1) buf.put((byte) payload.length);
    else buf.putShort((short) payload.length);
    buf.put(payload);
    return buf.array();
  }

  /** Builds a firmware entry payload (10-byte chunk). */
  private byte[] buildFirmwareChunk(int canId, int major, int minor, int build) {
    var buf = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
    buf.putInt(canId);       // CAN ID (LE per revlog format)
    buf.put((byte) major);
    buf.put((byte) minor);
    // Build number is big-endian u16 per REV spec
    buf.put((byte) ((build >> 8) & 0xFF));
    buf.put((byte) (build & 0xFF));
    buf.put((byte) 0);
    buf.put((byte) 0);
    return buf.array();
  }

  /** Builds a periodic status payload chunk (16 bytes). */
  private byte[] buildPeriodicChunk(long timestampMs, int canId, byte[] canData) {
    var buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
    buf.putInt((int) timestampMs);
    buf.putInt(canId);
    buf.put(canData, 0, Math.min(8, canData.length));
    // Pad to 8 bytes if needed
    for (int i = canData.length; i < 8; i++) buf.put((byte) 0);
    return buf.array();
  }

  /** Concatenates byte arrays. */
  private byte[] concat(byte[]... arrays) {
    int totalLen = 0;
    for (var a : arrays) totalLen += a.length;
    var result = new byte[totalLen];
    int pos = 0;
    for (var a : arrays) {
      System.arraycopy(a, 0, result, pos, a.length);
      pos += a.length;
    }
    return result;
  }

  private Path writeRevlog(String name, byte[] data) throws IOException {
    Path file = tempDir.resolve(name);
    Files.write(file, data);
    return file;
  }

  // SPARK MAX device type = 2, REV manufacturer = 5
  // CAN ID for SPARK MAX status 0, device 4: buildArbitrationId(2, 5, 6, 0, 4) = 0x02051804
  // But in native format, the CAN ID uses different encoding.
  // Native format CAN ID for device 4, status 0: device_type=2, mfr=5, plus native API bits
  // For simplicity, we use the canonical REV CAN ID format:
  // (2 << 24) | (5 << 16) | (native_api_bits) | device_id
  // Status 0 in native format has api_index=0 at bits 6-9
  private static final int SPARK_MAX_FW_CAN_ID = 0x02050004; // firmware entry, device 4, type 2
  private static final int SPARK_STATUS0_DEV4 = 0x0205b804; // native status 0, device 4

  @Nested
  @DisplayName("Firmware entries")
  class FirmwareEntries {

    @Test
    @DisplayName("parses firmware entry with device type and version")
    void parsesFirmware() throws IOException {
      byte[] fwPayload = buildFirmwareChunk(SPARK_MAX_FW_CAN_ID, 26, 1, 5);
      byte[] record = buildRecord(1, fwPayload);
      Path file = writeRevlog("REV_20260322_120000.revlog", record);

      var result = parser.parse(file);

      assertFalse(result.devices().isEmpty(), "Should find at least one device");
      // Device 4 should be detected
      boolean foundDevice4 = result.devices().values().stream()
          .anyMatch(d -> d.canId() == 4);
      assertTrue(foundDevice4, "Should find device with CAN ID 4");

      var device = result.devices().values().stream()
          .filter(d -> d.canId() == 4).findFirst().orElseThrow();
      assertEquals("SPARK MAX", device.deviceType());
      assertEquals("26.1.5", device.firmwareVersion());
    }

    @Test
    @DisplayName("different device types with same CAN device ID are distinct")
    void differentDeviceTypesSameCandIdAreDistinct() throws IOException {
      // SPARK MAX (type=2) device 4
      int sparkMaxCanId = (2 << 24) | (5 << 16) | 4;
      byte[] sparkFw = buildFirmwareChunk(sparkMaxCanId, 26, 1, 5);
      byte[] sparkRecord = buildRecord(1, sparkFw);

      // Servo Hub (type=12) device 4 — same device ID, different type
      int servoHubCanId = (12 << 24) | (5 << 16) | 4;
      byte[] servoFw = buildFirmwareChunk(servoHubCanId, 1, 0, 0);
      byte[] servoRecord = buildRecord(1, servoFw);

      Path file = writeRevlog("REV_20260322_120000.revlog",
          concat(sparkRecord, servoRecord));

      var result = parser.parse(file);

      // Both devices should be present and distinct
      assertTrue(result.devices().size() >= 2,
          "Should have at least 2 devices, got: " + result.devices().size());

      boolean foundSparkMax = result.devices().values().stream()
          .anyMatch(d -> d.canId() == 4 && "SPARK MAX".equals(d.deviceType()));
      boolean foundServoHub = result.devices().values().stream()
          .anyMatch(d -> d.canId() == 4 && "Servo Hub".equals(d.deviceType()));

      assertTrue(foundSparkMax, "Should find SPARK MAX with CAN ID 4");
      assertTrue(foundServoHub, "Should find Servo Hub with CAN ID 4");
    }

    @Test
    @DisplayName("detects Servo Hub device type")
    void detectsServoHub() throws IOException {
      int servoHubCanId = (12 << 24) | (5 << 16) | 7; // type 12, mfr 5, device 7
      byte[] fwPayload = buildFirmwareChunk(servoHubCanId, 1, 0, 0);
      byte[] record = buildRecord(1, fwPayload);
      Path file = writeRevlog("REV_20260322_120000.revlog", record);

      var result = parser.parse(file);
      var device = result.devices().values().stream()
          .filter(d -> d.canId() == 7).findFirst().orElseThrow();
      assertEquals("Servo Hub", device.deviceType());
    }
  }

  @Nested
  @DisplayName("Periodic status entries")
  class PeriodicEntries {

    @Test
    @DisplayName("parses periodic status and decodes signals")
    void decodesSignals() throws IOException {
      // Build a firmware record first (so device is registered)
      byte[] fwPayload = buildFirmwareChunk(SPARK_MAX_FW_CAN_ID, 26, 1, 5);
      byte[] fwRecord = buildRecord(1, fwPayload);

      // Build periodic status 1 (velocity, temperature, voltage, current)
      // CAN ID with api_index=1 at bits 6-9: SPARK_STATUS0_DEV4 | (1 << 6) = status 1
      int status1CanId = SPARK_STATUS0_DEV4 | (1 << 6); // api_index 1
      // CAN data for status 1: velocity=0 (4 bytes LE), temp=25, voltage+current
      byte[] canData = new byte[]{0, 0, 0, 0, 25, 0x00, 0x20, 0x00};
      byte[] periodicPayload = buildPeriodicChunk(5000, status1CanId, canData);
      byte[] periodicRecord = buildRecord(2, periodicPayload);

      Path file = writeRevlog("REV_20260322_120000.revlog",
          concat(fwRecord, periodicRecord));

      var result = parser.parse(file);

      assertTrue(result.signals().size() > 0, "Should decode at least one signal");
      assertEquals(1, result.recordCount() > 0 ? 1 : 0, "Should have records");
      assertTrue(result.maxTimestamp() >= 5.0, "Timestamp should be >= 5 seconds");
    }

    @Test
    @DisplayName("extracts correct timestamps from periodic entries")
    void correctTimestamps() throws IOException {
      byte[] fwPayload = buildFirmwareChunk(SPARK_MAX_FW_CAN_ID, 26, 1, 5);
      byte[] fwRecord = buildRecord(1, fwPayload);

      // Two periodic chunks at different timestamps
      byte[] canData = new byte[8];
      byte[] chunk1 = buildPeriodicChunk(1000, SPARK_STATUS0_DEV4, canData);
      byte[] chunk2 = buildPeriodicChunk(2000, SPARK_STATUS0_DEV4, canData);
      byte[] periodicPayload = concat(chunk1, chunk2);
      byte[] periodicRecord = buildRecord(2, periodicPayload);

      Path file = writeRevlog("REV_20260322_120000.revlog",
          concat(fwRecord, periodicRecord));

      var result = parser.parse(file);
      assertEquals(1.0, result.minTimestamp(), 0.01);
      assertEquals(2.0, result.maxTimestamp(), 0.01);
    }
  }

  @Nested
  @DisplayName("Edge cases")
  class EdgeCases {

    @Test
    @DisplayName("handles empty file")
    void emptyFile() throws IOException {
      Path file = writeRevlog("REV_20260322_120000.revlog", new byte[0]);
      var result = parser.parse(file);
      assertTrue(result.devices().isEmpty());
      assertTrue(result.signals().isEmpty());
      assertEquals(0, result.recordCount());
    }

    @Test
    @DisplayName("handles truncated record gracefully")
    void truncatedRecord() throws IOException {
      // Write only 2 bytes — not enough for a complete record
      Path file = writeRevlog("REV_20260322_120000.revlog", new byte[]{0x00, 0x01});
      var result = parser.parse(file);
      // Should not crash, may have 0 or partial records
      assertNotNull(result);
    }

    @Test
    @DisplayName("handles record with payload size exceeding file")
    void oversizedPayload() throws IOException {
      // Bitfield 0x00 = 1-byte entryId, 1-byte payloadSize
      // entryId=2, payloadSize=255 (but file is only a few bytes total)
      byte[] data = new byte[]{0x00, 0x02, (byte) 0xFF};
      Path file = writeRevlog("REV_20260322_120000.revlog", data);
      var result = parser.parse(file);
      assertNotNull(result);
      // Should stop parsing gracefully
    }

    @Test
    @DisplayName("handles unknown entry IDs gracefully")
    void unknownEntryId() throws IOException {
      byte[] record = buildRecord(99, new byte[]{1, 2, 3, 4});
      Path file = writeRevlog("REV_20260322_120000.revlog", record);
      var result = parser.parse(file);
      // Unknown entry IDs are silently skipped
      assertTrue(result.devices().isEmpty());
      assertTrue(result.signals().isEmpty());
    }

    @Test
    @DisplayName("extracts filename timestamp")
    void filenameTimestamp() throws IOException {
      Path file = writeRevlog("REV_20260322_143052.revlog", new byte[0]);
      var result = parser.parse(file);
      assertEquals("20260322_143052", result.filenameTimestamp());
    }

    @Test
    @DisplayName("handles file without standard REV filename")
    void nonStandardFilename() throws IOException {
      Path file = writeRevlog("bah.revlog", new byte[0]);
      var result = parser.parse(file);
      assertNull(result.filenameTimestamp());
    }

    @Test
    @DisplayName("variable-length entry ID with 2 bytes")
    void twoByteEntryId() throws IOException {
      // Bitfield: entryIdLen=2 (bits 0-1 = 1), payloadSizeLen=1 (bits 2-3 = 0)
      // = 0x01
      byte[] data = new byte[]{
          0x01,       // bitfield: 2-byte entryId, 1-byte payloadSize
          0x02, 0x00, // entryId = 2 (LE)
          0x00        // payloadSize = 0
      };
      Path file = writeRevlog("REV_20260322_120000.revlog", data);
      var result = parser.parse(file);
      assertNotNull(result);
      assertEquals(1, result.recordCount());
    }
  }
}
