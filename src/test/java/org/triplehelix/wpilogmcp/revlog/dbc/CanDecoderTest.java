package org.triplehelix.wpilogmcp.revlog.dbc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for CanDecoder.
 */
class CanDecoderTest {

  private CanDecoder decoder;

  @BeforeEach
  void setUp() {
    // Create a simple DBC database for testing
    String dbcContent = """
        VERSION "1.0"

        BO_ 0x02051800 Periodic_Status_0: 8 SparkMax
         SG_ AppliedOutput : 0|16@1- (0.0001,0) [-1|1] "duty_cycle"
         SG_ Faults : 16|16@1+ (1,0) [0|65535] ""

        BO_ 0x02051840 Periodic_Status_1: 8 SparkMax
         SG_ Velocity : 0|32@1- (1,0) [-2147483648|2147483647] "rpm"
         SG_ Temperature : 32|8@1+ (1,0) [0|255] "degC"
        """;

    DbcParser parser = new DbcParser();
    DbcDatabase database = parser.parse(dbcContent);
    decoder = new CanDecoder(database);
  }

  @Test
  void testDecodeStatus0AppliedOutput() {
    // Test decoding AppliedOutput = 0.5 (raw value 5000)
    byte[] data = {
        (byte) 0x88, 0x13, // AppliedOutput: 5000 -> 0.5
        0x00, 0x00,        // Faults: 0
        0x00, 0x00,        // StickyFaults: 0
        0x00, 0x00         // Flags
    };

    Map<String, Double> signals = decoder.decode(0x02051800, data);

    assertTrue(signals.containsKey("AppliedOutput"));
    assertEquals(0.5, signals.get("AppliedOutput"), 0.0001);
  }

  @Test
  void testDecodeStatus0NegativeAppliedOutput() {
    // Test decoding AppliedOutput = -0.5 (raw value -5000)
    // -5000 in 16-bit signed little-endian: 0x78EC (0xFFFFEC78 truncated)
    byte[] data = {
        (byte) 0x78, (byte) 0xEC, // AppliedOutput: -5000 -> -0.5
        0x00, 0x00,
        0x00, 0x00,
        0x00, 0x00
    };

    Map<String, Double> signals = decoder.decode(0x02051800, data);

    assertTrue(signals.containsKey("AppliedOutput"));
    assertEquals(-0.5, signals.get("AppliedOutput"), 0.0001);
  }

  @Test
  void testDecodeStatus0Faults() {
    byte[] data = {
        0x00, 0x00,              // AppliedOutput: 0
        0x05, 0x00,              // Faults: 5
        0x00, 0x00,
        0x00, 0x00
    };

    Map<String, Double> signals = decoder.decode(0x02051800, data);

    assertTrue(signals.containsKey("Faults"));
    assertEquals(5.0, signals.get("Faults"), 0.001);
  }

  @Test
  void testDecodeStatus1Velocity() {
    // Velocity = 1000 rpm
    byte[] data = {
        (byte) 0xE8, 0x03, 0x00, 0x00, // Velocity: 1000
        0x19,                           // Temperature: 25
        0x00, 0x00, 0x00
    };

    Map<String, Double> signals = decoder.decode(0x02051840, data);

    assertTrue(signals.containsKey("Velocity"));
    assertEquals(1000.0, signals.get("Velocity"), 0.001);
  }

  @Test
  void testDecodeStatus1NegativeVelocity() {
    // Velocity = -500 rpm
    // -500 in 32-bit signed little-endian: 0xFFFFFF0C
    byte[] data = {
        (byte) 0x0C, (byte) 0xFE, (byte) 0xFF, (byte) 0xFF, // Velocity: -500
        0x19,                                                 // Temperature: 25
        0x00, 0x00, 0x00
    };

    Map<String, Double> signals = decoder.decode(0x02051840, data);

    assertTrue(signals.containsKey("Velocity"));
    assertEquals(-500.0, signals.get("Velocity"), 0.001);
  }

  @Test
  void testDecodeStatus1Temperature() {
    byte[] data = {
        0x00, 0x00, 0x00, 0x00, // Velocity: 0
        0x32,                   // Temperature: 50
        0x00, 0x00, 0x00
    };

    Map<String, Double> signals = decoder.decode(0x02051840, data);

    assertTrue(signals.containsKey("Temperature"));
    assertEquals(50.0, signals.get("Temperature"), 0.001);
  }

  @Test
  void testDecodeUnknownMessageReturnsEmpty() {
    byte[] data = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
    Map<String, Double> signals = decoder.decode(0xFFFFFFFF, data);
    assertTrue(signals.isEmpty());
  }

  @Test
  void testDecodeWithDeviceIdMasking() {
    // Status 0 with device ID 5 (bits 5-0)
    // Base ID 0x02051800, with device 5 = 0x02051805
    byte[] data = {
        (byte) 0x88, 0x13, // AppliedOutput: 5000 -> 0.5
        0x00, 0x00,
        0x00, 0x00,
        0x00, 0x00
    };

    // Should match by base ID (masking off device bits)
    Map<String, Double> signals = decoder.decode(0x02051805, data);

    assertTrue(signals.containsKey("AppliedOutput"));
    assertEquals(0.5, signals.get("AppliedOutput"), 0.0001);
  }

  @Test
  void testDecodeSignalSpecific() {
    byte[] data = {
        (byte) 0xE8, 0x03, 0x00, 0x00, // Velocity: 1000
        0x19,                           // Temperature: 25
        0x00, 0x00, 0x00
    };

    var velocity = decoder.decodeSignal(0x02051840, data, "Velocity");
    assertTrue(velocity.isPresent());
    assertEquals(1000.0, velocity.get(), 0.001);

    var temp = decoder.decodeSignal(0x02051840, data, "Temperature");
    assertTrue(temp.isPresent());
    assertEquals(25.0, temp.get(), 0.001);

    var notFound = decoder.decodeSignal(0x02051840, data, "NotExists");
    assertFalse(notFound.isPresent());
  }

  @Test
  void testGetMessageName() {
    var name0 = decoder.getMessageName(0x02051800);
    assertTrue(name0.isPresent());
    assertEquals("Periodic_Status_0", name0.get());

    var name1 = decoder.getMessageName(0x02051840);
    assertTrue(name1.isPresent());
    assertEquals("Periodic_Status_1", name1.get());

    var notFound = decoder.getMessageName(0xFFFFFFFF);
    assertFalse(notFound.isPresent());
  }

  @Test
  void testExtractDeviceId() {
    assertEquals(0, CanDecoder.extractDeviceId(0x02051800));
    assertEquals(5, CanDecoder.extractDeviceId(0x02051805));
    assertEquals(63, CanDecoder.extractDeviceId(0x0205183F));
  }

  @Test
  void testExtractManufacturerId() {
    assertEquals(5, CanDecoder.extractManufacturerId(0x02051800));
  }

  @Test
  void testExtractDeviceType() {
    assertEquals(2, CanDecoder.extractDeviceType(0x02051800));
  }

  @Test
  void testExtractApiClass() {
    // API class is bits 15-10
    // 0x02051800: bits 15-10 = 0x18 >> 10 = 6
    assertEquals(6, CanDecoder.extractApiClass(0x02051800));
  }

  @Test
  void testExtractApiIndex() {
    // Status 0: API index = 0
    assertEquals(0, CanDecoder.extractApiIndex(0x02051800));
    // Status 1: API index = 1
    assertEquals(1, CanDecoder.extractApiIndex(0x02051840));
  }

  @Test
  void testIsRevDevice() {
    assertTrue(CanDecoder.isRevDevice(0x02051800));
    // Test with manufacturer ID 1 (not REV which is 5)
    // Format: (deviceType << 24) | (manufacturerId << 16) | (apiClass << 10) | (apiIndex << 6) | deviceId
    // 0x02011800 has manufacturer ID 1, not 5
    assertFalse(CanDecoder.isRevDevice(0x02011800)); // Different manufacturer
  }

  @Test
  void testGetDeviceTypeName() {
    assertEquals("SPARK MAX", CanDecoder.getDeviceTypeName(0x02051800));
  }

  @Test
  void testBuildArbitrationId() {
    // Device type=2, manufacturer=5, api_class=6, api_index=0, device_id=0
    int id = CanDecoder.buildArbitrationId(2, 5, 6, 0, 0);
    assertEquals(0x02051800, id);

    // Same with device ID 5
    int id5 = CanDecoder.buildArbitrationId(2, 5, 6, 0, 5);
    assertEquals(0x02051805, id5);

    // Status 1 (api_index=1)
    int status1 = CanDecoder.buildArbitrationId(2, 5, 6, 1, 0);
    assertEquals(0x02051840, status1);
  }

  @Test
  void testGetDatabase() {
    DbcDatabase db = decoder.getDatabase();
    assertNotNull(db);
    assertEquals(2, db.messageCount());
  }
}
