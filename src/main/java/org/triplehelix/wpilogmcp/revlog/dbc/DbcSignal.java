package org.triplehelix.wpilogmcp.revlog.dbc;

/**
 * A CAN signal definition from a DBC file.
 *
 * <p>DBC files define how to extract named signals from raw CAN frame bytes.
 * Each signal specifies its bit position, length, byte order, scale, offset,
 * and unit.
 *
 * <p>Example DBC signal definition:
 * <pre>
 * SG_ AppliedOutput : 0|16@1- (0.0001,0) [-1|1] "duty_cycle"
 * </pre>
 *
 * @param name The signal name (e.g., "AppliedOutput")
 * @param startBit The start bit position (0-63)
 * @param bitLength The number of bits (1-64)
 * @param littleEndian True for Intel byte order (1), false for Motorola (0)
 * @param signed True if the signal is signed (-), false if unsigned (+)
 * @param scale The scale factor to multiply raw value by
 * @param offset The offset to add after scaling
 * @param min The minimum allowed value (for documentation)
 * @param max The maximum allowed value (for documentation)
 * @param unit The unit string (e.g., "rpm", "V", "A")
 * @since 0.5.0
 */
public record DbcSignal(
    String name,
    int startBit,
    int bitLength,
    boolean littleEndian,
    boolean signed,
    double scale,
    double offset,
    double min,
    double max,
    String unit) {

  /**
   * Decodes this signal's value from raw CAN frame data.
   *
   * <p>The decoding process:
   * <ol>
   *   <li>Extract the raw bits from the specified position</li>
   *   <li>Apply sign extension if signed</li>
   *   <li>Apply scale and offset: value = raw * scale + offset</li>
   * </ol>
   *
   * @param data The raw CAN frame data (up to 8 bytes)
   * @return The decoded signal value
   * @throws IllegalArgumentException if data is insufficient for this signal
   */
  public double decode(byte[] data) {
    if (data == null || data.length == 0) {
      throw new IllegalArgumentException("CAN data cannot be null or empty");
    }

    long rawValue;
    if (littleEndian) {
      rawValue = decodeIntel(data);
    } else {
      rawValue = decodeMotorola(data);
    }

    // Apply sign extension if needed
    if (signed && bitLength < 64) {
      long signBit = 1L << (bitLength - 1);
      if ((rawValue & signBit) != 0) {
        // Sign extend by filling upper bits with 1s
        rawValue |= ~((1L << bitLength) - 1);
      }
    }

    // Apply scale and offset.
    // For unsigned 64-bit signals, Java's signed long would misinterpret
    // values with the MSB set as negative. Convert using unsigned semantics:
    // if the value appears negative, add 2^64 to get the correct unsigned double.
    if (!signed && bitLength == 64 && rawValue < 0) {
      // rawValue is the unsigned 64-bit value stored in a signed long.
      // Double can represent all unsigned 64-bit integers (with possible
      // precision loss for values > 2^53, which is acceptable for CAN signals).
      double unsignedValue = (double) (rawValue & 0x7FFF_FFFF_FFFF_FFFFL) + 0x1p63;
      return unsignedValue * scale + offset;
    }
    return rawValue * scale + offset;
  }

  /**
   * Decodes Intel (little-endian) byte order signal.
   */
  private long decodeIntel(byte[] data) {
    long result = 0;
    int bitsRemaining = bitLength;
    int currentBit = startBit;

    while (bitsRemaining > 0) {
      int byteIndex = currentBit / 8;
      int bitInByte = currentBit % 8;

      if (byteIndex >= data.length) {
        break; // Not enough data
      }

      int bitsInThisByte = Math.min(8 - bitInByte, bitsRemaining);
      int mask = (1 << bitsInThisByte) - 1;
      int byteValue = (data[byteIndex] >> bitInByte) & mask;

      result |= ((long) byteValue) << (bitLength - bitsRemaining);

      bitsRemaining -= bitsInThisByte;
      currentBit += bitsInThisByte;
    }

    return result;
  }

  /**
   * Decodes Motorola (big-endian) byte order signal.
   */
  private long decodeMotorola(byte[] data) {
    long result = 0;
    int bitsRemaining = bitLength;

    // Motorola format: startBit is the MSB position
    int currentBit = startBit;

    while (bitsRemaining > 0) {
      int byteIndex = currentBit / 8;
      int bitInByte = currentBit % 8;

      if (byteIndex >= data.length) {
        break;
      }

      int bitsInThisByte = Math.min(bitInByte + 1, bitsRemaining);
      int mask = (1 << bitsInThisByte) - 1;
      int shift = bitInByte - bitsInThisByte + 1;
      int byteValue = (data[byteIndex] >> shift) & mask;

      result = (result << bitsInThisByte) | byteValue;

      bitsRemaining -= bitsInThisByte;
      // Move to next byte, starting at bit 7 (MSB)
      currentBit = (byteIndex + 1) * 8 + 7;
    }

    return result;
  }

  /**
   * Creates a builder for constructing DbcSignal instances.
   *
   * @param name The signal name
   * @return A new builder
   */
  public static Builder builder(String name) {
    return new Builder(name);
  }

  /**
   * Builder for DbcSignal instances.
   */
  public static class Builder {
    private final String name;
    private int startBit = 0;
    private int bitLength = 8;
    private boolean littleEndian = true;
    private boolean signed = false;
    private double scale = 1.0;
    private double offset = 0.0;
    private double min = 0.0;
    private double max = 0.0;
    private String unit = "";

    private Builder(String name) {
      this.name = name;
    }

    public Builder startBit(int startBit) {
      this.startBit = startBit;
      return this;
    }

    public Builder bitLength(int bitLength) {
      this.bitLength = bitLength;
      return this;
    }

    public Builder littleEndian(boolean littleEndian) {
      this.littleEndian = littleEndian;
      return this;
    }

    public Builder signed(boolean signed) {
      this.signed = signed;
      return this;
    }

    public Builder scale(double scale) {
      this.scale = scale;
      return this;
    }

    public Builder offset(double offset) {
      this.offset = offset;
      return this;
    }

    public Builder min(double min) {
      this.min = min;
      return this;
    }

    public Builder max(double max) {
      this.max = max;
      return this;
    }

    public Builder unit(String unit) {
      this.unit = unit;
      return this;
    }

    public DbcSignal build() {
      return new DbcSignal(name, startBit, bitLength, littleEndian, signed,
          scale, offset, min, max, unit);
    }
  }
}
