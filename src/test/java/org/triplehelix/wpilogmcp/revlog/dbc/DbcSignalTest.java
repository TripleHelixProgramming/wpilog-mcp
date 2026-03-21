package org.triplehelix.wpilogmcp.revlog.dbc;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * Tests for DbcSignal decoding.
 */
class DbcSignalTest {

  @Test
  void testDecodeUnsignedLittleEndian8Bit() {
    DbcSignal signal = DbcSignal.builder("TestSignal")
        .startBit(0)
        .bitLength(8)
        .littleEndian(true)
        .signed(false)
        .scale(1.0)
        .offset(0.0)
        .build();

    byte[] data = {(byte) 0x42}; // 66 decimal
    assertEquals(66.0, signal.decode(data), 0.001);
  }

  @Test
  void testDecodeUnsignedLittleEndian16Bit() {
    DbcSignal signal = DbcSignal.builder("TestSignal")
        .startBit(0)
        .bitLength(16)
        .littleEndian(true)
        .signed(false)
        .scale(1.0)
        .offset(0.0)
        .build();

    // Little endian: 0x34 0x12 = 0x1234 = 4660
    byte[] data = {0x34, 0x12};
    assertEquals(4660.0, signal.decode(data), 0.001);
  }

  @Test
  void testDecodeSignedLittleEndian16Bit() {
    DbcSignal signal = DbcSignal.builder("TestSignal")
        .startBit(0)
        .bitLength(16)
        .littleEndian(true)
        .signed(true)
        .scale(1.0)
        .offset(0.0)
        .build();

    // Little endian: 0xFF 0xFF = -1
    byte[] data = {(byte) 0xFF, (byte) 0xFF};
    assertEquals(-1.0, signal.decode(data), 0.001);
  }

  @Test
  void testDecodeSignedLittleEndian16BitNegativeValue() {
    DbcSignal signal = DbcSignal.builder("TestSignal")
        .startBit(0)
        .bitLength(16)
        .littleEndian(true)
        .signed(true)
        .scale(1.0)
        .offset(0.0)
        .build();

    // Little endian: 0x00 0x80 = 0x8000 = -32768
    byte[] data = {0x00, (byte) 0x80};
    assertEquals(-32768.0, signal.decode(data), 0.001);
  }

  @Test
  void testDecodeWithScale() {
    DbcSignal signal = DbcSignal.builder("AppliedOutput")
        .startBit(0)
        .bitLength(16)
        .littleEndian(true)
        .signed(true)
        .scale(0.0001)
        .offset(0.0)
        .build();

    // Raw value 5000 * 0.0001 = 0.5
    byte[] data = {(byte) 0x88, 0x13}; // 0x1388 = 5000
    assertEquals(0.5, signal.decode(data), 0.0001);
  }

  @Test
  void testDecodeWithScaleAndOffset() {
    DbcSignal signal = DbcSignal.builder("Temperature")
        .startBit(0)
        .bitLength(8)
        .littleEndian(true)
        .signed(false)
        .scale(1.0)
        .offset(-40.0)
        .build();

    // Raw value 65, with offset -40 = 25
    byte[] data = {65};
    assertEquals(25.0, signal.decode(data), 0.001);
  }

  @Test
  void testDecodeWithBitOffset() {
    DbcSignal signal = DbcSignal.builder("Flags")
        .startBit(16)
        .bitLength(8)
        .littleEndian(true)
        .signed(false)
        .scale(1.0)
        .offset(0.0)
        .build();

    byte[] data = {0x00, 0x00, 0x42, 0x00}; // Signal at byte 2
    assertEquals(66.0, signal.decode(data), 0.001);
  }

  @Test
  void testDecodeSingleBit() {
    DbcSignal signal = DbcSignal.builder("IsFollower")
        .startBit(48)
        .bitLength(1)
        .littleEndian(true)
        .signed(false)
        .scale(1.0)
        .offset(0.0)
        .build();

    byte[] data = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00};
    assertEquals(1.0, signal.decode(data), 0.001);
  }

  @Test
  void testDecodeSingleBitZero() {
    DbcSignal signal = DbcSignal.builder("IsFollower")
        .startBit(48)
        .bitLength(1)
        .littleEndian(true)
        .signed(false)
        .scale(1.0)
        .offset(0.0)
        .build();

    byte[] data = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
    assertEquals(0.0, signal.decode(data), 0.001);
  }

  @Test
  void testDecode32BitSigned() {
    DbcSignal signal = DbcSignal.builder("Position")
        .startBit(0)
        .bitLength(32)
        .littleEndian(true)
        .signed(true)
        .scale(1.0)
        .offset(0.0)
        .build();

    // Test positive value
    byte[] data1 = {0x01, 0x00, 0x00, 0x00}; // 1
    assertEquals(1.0, signal.decode(data1), 0.001);

    // Test negative value: -1
    byte[] data2 = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    assertEquals(-1.0, signal.decode(data2), 0.001);
  }

  @Test
  void testDecode12BitWithOffset() {
    // BusVoltage: 40|12@1+ (0.01,0) [0|40.95] "V"
    DbcSignal signal = DbcSignal.builder("BusVoltage")
        .startBit(40)
        .bitLength(12)
        .littleEndian(true)
        .signed(false)
        .scale(0.01)
        .offset(0.0)
        .build();

    // Set up 8 bytes with the voltage value at bit 40
    ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
    buffer.position(0);
    buffer.put(new byte[5]); // Skip first 5 bytes
    // Put 12-bit value 1234 (12.34V) starting at bit 40
    // At byte 5, bits 0-7, and byte 6, bits 0-3
    buffer.put(5, (byte) (1234 & 0xFF));
    buffer.put(6, (byte) ((1234 >> 8) & 0x0F));

    assertEquals(12.34, signal.decode(buffer.array()), 0.01);
  }

  @Test
  void testDecodeNullDataThrows() {
    DbcSignal signal = DbcSignal.builder("Test")
        .startBit(0)
        .bitLength(8)
        .build();

    assertThrows(IllegalArgumentException.class, () -> signal.decode(null));
  }

  @Test
  void testDecodeEmptyDataThrows() {
    DbcSignal signal = DbcSignal.builder("Test")
        .startBit(0)
        .bitLength(8)
        .build();

    assertThrows(IllegalArgumentException.class, () -> signal.decode(new byte[0]));
  }

  @Test
  void testBuilderDefaults() {
    DbcSignal signal = DbcSignal.builder("Test").build();

    assertEquals("Test", signal.name());
    assertEquals(0, signal.startBit());
    assertEquals(8, signal.bitLength());
    assertTrue(signal.littleEndian());
    assertFalse(signal.signed());
    assertEquals(1.0, signal.scale());
    assertEquals(0.0, signal.offset());
    assertEquals("", signal.unit());
  }

  @Test
  void testDecodeUnsigned64BitWithMsbSet() {
    // Regression test: unsigned 64-bit signals with MSB set should produce
    // large positive values, not negative ones.
    DbcSignal signal = DbcSignal.builder("Counter64")
        .startBit(0)
        .bitLength(64)
        .littleEndian(true)
        .signed(false)
        .scale(1.0)
        .offset(0.0)
        .build();

    // 0x8000000000000000 = 2^63 = 9223372036854775808 (unsigned)
    // In signed long, this is Long.MIN_VALUE (-9223372036854775808)
    byte[] data = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0x80};
    double decoded = signal.decode(data);
    assertTrue(decoded > 0, "Unsigned 64-bit with MSB set should be positive, got: " + decoded);
    assertEquals(9.223372036854776E18, decoded, 1e10); // 2^63, allow float precision
  }

  @Test
  void testDecodeUnsigned64BitMaxValue() {
    DbcSignal signal = DbcSignal.builder("MaxCounter")
        .startBit(0)
        .bitLength(64)
        .littleEndian(true)
        .signed(false)
        .scale(1.0)
        .offset(0.0)
        .build();

    // All 1s = 0xFFFFFFFFFFFFFFFF = 2^64 - 1
    byte[] data = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                   (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    double decoded = signal.decode(data);
    assertTrue(decoded > 0, "Max unsigned 64-bit should be positive, got: " + decoded);
    assertEquals(1.8446744073709552E19, decoded, 1e10); // ~2^64
  }

  @Test
  void testDecodeUnsigned64BitSmallValueUnaffected() {
    // Values below 2^63 should work the same regardless of the fix
    DbcSignal signal = DbcSignal.builder("SmallCounter")
        .startBit(0)
        .bitLength(64)
        .littleEndian(true)
        .signed(false)
        .scale(1.0)
        .offset(0.0)
        .build();

    byte[] data = {0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
    assertEquals(1.0, signal.decode(data), 0.001);
  }

  @Test
  void testDecodeUnsigned64BitWithScale() {
    DbcSignal signal = DbcSignal.builder("ScaledCounter")
        .startBit(0)
        .bitLength(64)
        .littleEndian(true)
        .signed(false)
        .scale(0.001)
        .offset(0.0)
        .build();

    // 0x8000000000000000 * 0.001 should be positive
    byte[] data = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0x80};
    double decoded = signal.decode(data);
    assertTrue(decoded > 0, "Scaled unsigned 64-bit with MSB set should be positive");
  }

  @Test
  void testBuilderFullConfiguration() {
    DbcSignal signal = DbcSignal.builder("Velocity")
        .startBit(0)
        .bitLength(32)
        .littleEndian(true)
        .signed(true)
        .scale(1.0)
        .offset(0.0)
        .min(-100000)
        .max(100000)
        .unit("rpm")
        .build();

    assertEquals("Velocity", signal.name());
    assertEquals(32, signal.bitLength());
    assertTrue(signal.signed());
    assertEquals("rpm", signal.unit());
    assertEquals(-100000.0, signal.min());
    assertEquals(100000.0, signal.max());
  }
}
