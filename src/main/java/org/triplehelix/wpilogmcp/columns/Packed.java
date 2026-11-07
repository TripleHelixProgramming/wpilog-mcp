/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.columns;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A sequence of longs kept as differences, for record facts that change by small steps. Each
 * block of 4,096 values keeps its first value whole, beside where the block's differences
 * start, and the rest as zigzag varints of the difference from the value before. A record's
 * byte position, which grows by a few kilobytes from one of an entry's records to the next,
 * then takes two or three bytes instead of eight, and a read can start at any block.
 *
 * <pre>
 * index  per block: its first value, and its differences' start within the data   16 bytes
 * data   per block: the zigzag varint differences of its values after the first
 * </pre>
 *
 * Measured on three team logs, record facts kept this way added 6 to 8% to their column
 * files, against 21 to 22% with whole byte positions and 39 to 42% with every fact whole.
 */
final class Packed {
  private Packed() {}

  /** Values in a block. */
  static final int BLOCK = 1 << 12;
  static final int SHIFT = 12;
  /** Bytes of a block's index record. */
  static final int INDEX_BYTES = 16;
  /** Bytes the longest varint of a long takes. */
  static final int MAX_VARINT_BYTES = 10;

  static int blocks(int count) {
    return (int) (((long) count + BLOCK - 1) >>> SHIFT);
  }

  /** Small differences of either sign become small unsigned numbers. */
  static long zigzag(long value) {
    return value << 1 ^ value >> 63;
  }

  static long unzigzag(long value) {
    return value >>> 1 ^ -(value & 1);
  }

  static int varintBytes(long value) {
    int bytes = 1;
    while ((value >>>= 7) != 0) bytes++;
    return bytes;
  }

  static void putVarint(ByteBuffer out, long value) {
    while ((value & ~0x7FL) != 0) {
      out.put((byte) (value & 0x7F | 0x80));
      value >>>= 7;
    }
    out.put((byte) value);
  }

  /** The next varint, or an IOException for one that ends early or does not fit a long. */
  static long getVarint(ByteBuffer in) throws IOException {
    long value = 0;
    for (int shift = 0; shift < 64; shift += 7) {
      if (!in.hasRemaining()) throw new IOException("Packed section ends inside a value");
      int next = in.get() & 0xFF;
      if (shift == 63 && next > 1) throw new IOException("Packed value overflows a long");
      value |= (long) (next & 0x7F) << shift;
      if ((next & 0x80) == 0) return value;
    }
    throw new IOException("Packed value is longer than ten bytes");
  }
}
