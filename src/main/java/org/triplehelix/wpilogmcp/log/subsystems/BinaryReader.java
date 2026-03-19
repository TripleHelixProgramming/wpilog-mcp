package org.triplehelix.wpilogmcp.log.subsystems;

/**
 * Utility class for reading primitive values from byte arrays in little-endian format.
 *
 * <p>WPILib data logs store all numeric values in little-endian byte order. This class provides
 * methods to read doubles, floats, and 32-bit integers from byte arrays.
 *
 * <p>This class is thread-safe and stateless.
 *
 * @since 0.4.0
 */
public class BinaryReader {

  /**
   * Reads a little-endian double (8 bytes) from a byte array at the given offset.
   *
   * <p>Little-endian format means the least significant byte comes first. For example, the double
   * value 1.0 is stored as: 00 00 00 00 00 00 F0 3F
   *
   * @param data The byte array to read from
   * @param offset The offset to start reading (must be >= 0 and <= data.length - 8)
   * @return The decoded double value
   * @throws ArrayIndexOutOfBoundsException if offset is invalid or insufficient data
   */
  public double readDouble(byte[] data, int offset) {
    long bits = 0;
    for (int i = 0; i < 8; i++) {
      bits |= (long) (data[offset + i] & 0xFF) << (i * 8);
    }
    return Double.longBitsToDouble(bits);
  }

  /**
   * Reads a little-endian float (4 bytes) from a byte array at the given offset.
   *
   * @param data The byte array to read from
   * @param offset The offset to start reading (must be >= 0 and <= data.length - 4)
   * @return The decoded float value
   * @throws ArrayIndexOutOfBoundsException if offset is invalid or insufficient data
   */
  public float readFloat(byte[] data, int offset) {
    int bits = 0;
    for (int i = 0; i < 4; i++) {
      bits |= (data[offset + i] & 0xFF) << (i * 8);
    }
    return Float.intBitsToFloat(bits);
  }

  /**
   * Reads a little-endian 32-bit integer (4 bytes) from a byte array at the given offset.
   *
   * @param data The byte array to read from
   * @param offset The offset to start reading (must be >= 0 and <= data.length - 4)
   * @return The decoded int value
   * @throws ArrayIndexOutOfBoundsException if offset is invalid or insufficient data
   */
  public int readInt32(byte[] data, int offset) {
    int value = 0;
    for (int i = 0; i < 4; i++) {
      value |= (data[offset + i] & 0xFF) << (i * 8);
    }
    return value;
  }

  private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

  /**
   * Converts a byte array to a hexadecimal string representation.
   *
   * <p>This is useful for displaying unknown struct types or debugging binary data.
   *
   * @param bytes The byte array to convert
   * @return A hex string (e.g., "01a3ff" for bytes [1, 163, 255])
   */
  public static String bytesToHex(byte[] bytes) {
    char[] hexChars = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      int v = bytes[i] & 0xFF;
      hexChars[i * 2] = HEX_CHARS[v >>> 4];
      hexChars[i * 2 + 1] = HEX_CHARS[v & 0x0F];
    }
    return new String(hexChars);
  }
}
