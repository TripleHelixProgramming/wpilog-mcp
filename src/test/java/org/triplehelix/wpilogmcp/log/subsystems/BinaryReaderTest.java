package org.triplehelix.wpilogmcp.log.subsystems;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive tests for BinaryReader little-endian binary reading.
 */
class BinaryReaderTest {
  private BinaryReader reader;

  @BeforeEach
  void setUp() {
    reader = new BinaryReader();
  }

  @Test
  void testReadDouble() {
    ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putDouble(123.456);
    byte[] data = buffer.array();

    double result = reader.readDouble(data, 0);
    assertEquals(123.456, result, 1e-10);
  }

  @Test
  void testReadDoubleWithOffset() {
    ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putLong(0); // 8 byte offset
    buffer.putDouble(789.012);
    byte[] data = buffer.array();

    double result = reader.readDouble(data, 8);
    assertEquals(789.012, result, 1e-10);
  }

  @Test
  void testReadDoubleNegative() {
    ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putDouble(-456.789);
    byte[] data = buffer.array();

    double result = reader.readDouble(data, 0);
    assertEquals(-456.789, result, 1e-10);
  }

  @Test
  void testReadDoubleZero() {
    ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putDouble(0.0);
    byte[] data = buffer.array();

    double result = reader.readDouble(data, 0);
    assertEquals(0.0, result, 1e-10);
  }

  @Test
  void testReadDoubleInfinity() {
    ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putDouble(Double.POSITIVE_INFINITY);
    byte[] data = buffer.array();

    double result = reader.readDouble(data, 0);
    assertEquals(Double.POSITIVE_INFINITY, result);
  }

  @Test
  void testReadDoubleNaN() {
    ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putDouble(Double.NaN);
    byte[] data = buffer.array();

    double result = reader.readDouble(data, 0);
    assertTrue(Double.isNaN(result));
  }

  @Test
  void testReadFloat() {
    ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putFloat(12.34f);
    byte[] data = buffer.array();

    float result = reader.readFloat(data, 0);
    assertEquals(12.34f, result, 1e-5f);
  }

  @Test
  void testReadFloatWithOffset() {
    ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(0); // 4 byte offset
    buffer.putFloat(56.78f);
    byte[] data = buffer.array();

    float result = reader.readFloat(data, 4);
    assertEquals(56.78f, result, 1e-5f);
  }

  @Test
  void testReadFloatNegative() {
    ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putFloat(-98.76f);
    byte[] data = buffer.array();

    float result = reader.readFloat(data, 0);
    assertEquals(-98.76f, result, 1e-5f);
  }

  @Test
  void testReadInt32Positive() {
    ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(12345);
    byte[] data = buffer.array();

    int result = reader.readInt32(data, 0);
    assertEquals(12345, result);
  }

  @Test
  void testReadInt32Negative() {
    ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(-67890);
    byte[] data = buffer.array();

    int result = reader.readInt32(data, 0);
    assertEquals(-67890, result);
  }

  @Test
  void testReadInt32Zero() {
    ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(0);
    byte[] data = buffer.array();

    int result = reader.readInt32(data, 0);
    assertEquals(0, result);
  }

  @Test
  void testReadInt32MaxValue() {
    ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(Integer.MAX_VALUE);
    byte[] data = buffer.array();

    int result = reader.readInt32(data, 0);
    assertEquals(Integer.MAX_VALUE, result);
  }

  @Test
  void testReadInt32MinValue() {
    ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(Integer.MIN_VALUE);
    byte[] data = buffer.array();

    int result = reader.readInt32(data, 0);
    assertEquals(Integer.MIN_VALUE, result);
  }

  @Test
  void testReadInt32WithOffset() {
    ByteBuffer buffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(111);
    buffer.putInt(222);
    buffer.putInt(333);
    byte[] data = buffer.array();

    assertEquals(111, reader.readInt32(data, 0));
    assertEquals(222, reader.readInt32(data, 4));
    assertEquals(333, reader.readInt32(data, 8));
  }

  @Test
  void testBytesToHexEmpty() {
    byte[] data = new byte[0];
    String hex = BinaryReader.bytesToHex(data);
    assertEquals("", hex);
  }

  @Test
  void testBytesToHexSingleByte() {
    byte[] data = {(byte) 0xAB};
    String hex = BinaryReader.bytesToHex(data);
    assertEquals("ab", hex);
  }

  @Test
  void testBytesToHexMultipleBytes() {
    byte[] data = {0x01, (byte) 0xA3, (byte) 0xFF};
    String hex = BinaryReader.bytesToHex(data);
    assertEquals("01a3ff", hex);
  }

  @Test
  void testBytesToHexAllZeros() {
    byte[] data = {0x00, 0x00, 0x00};
    String hex = BinaryReader.bytesToHex(data);
    assertEquals("000000", hex);
  }

  @Test
  void testBytesToHexAllFFs() {
    byte[] data = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    String hex = BinaryReader.bytesToHex(data);
    assertEquals("ffffff", hex);
  }

  @Test
  void testLittleEndianOrder() {
    // Verify that we're reading little-endian
    // The number 0x12345678 in little-endian is: 78 56 34 12
    byte[] data = {0x78, 0x56, 0x34, 0x12};
    int result = reader.readInt32(data, 0);
    assertEquals(0x12345678, result);
  }

  @Test
  void testReadMultiplePrimitivesFromSameBuffer() {
    ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putDouble(3.14159);
    buffer.putFloat(2.71f);
    buffer.putInt(42);
    byte[] data = buffer.array();

    double d = reader.readDouble(data, 0);
    float f = reader.readFloat(data, 8);
    int i = reader.readInt32(data, 12);

    assertEquals(3.14159, d, 1e-10);
    assertEquals(2.71f, f, 1e-5f);
    assertEquals(42, i);
  }

  @Test
  void testReadDoubleInsufficientData() {
    byte[] data = {0x01, 0x02, 0x03}; // Only 3 bytes
    assertThrows(ArrayIndexOutOfBoundsException.class, () -> reader.readDouble(data, 0));
  }

  @Test
  void testReadFloatInsufficientData() {
    byte[] data = {0x01, 0x02}; // Only 2 bytes
    assertThrows(ArrayIndexOutOfBoundsException.class, () -> reader.readFloat(data, 0));
  }

  @Test
  void testReadInt32InsufficientData() {
    byte[] data = {0x01, 0x02}; // Only 2 bytes
    assertThrows(ArrayIndexOutOfBoundsException.class, () -> reader.readInt32(data, 0));
  }

  @Test
  void testReadWithInvalidOffset() {
    byte[] data = new byte[8];
    assertThrows(ArrayIndexOutOfBoundsException.class, () -> reader.readDouble(data, 10));
  }

  @Test
  void testReadWithNegativeOffset() {
    byte[] data = new byte[8];
    assertThrows(ArrayIndexOutOfBoundsException.class, () -> reader.readDouble(data, -1));
  }
}
