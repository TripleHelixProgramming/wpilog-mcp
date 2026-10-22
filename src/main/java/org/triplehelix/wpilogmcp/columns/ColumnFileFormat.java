/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.columns;

import java.io.IOException;
import org.triplehelix.wpilogmcp.log.struct.EnumValue;

/**
 * The layout of a column file: one log's numeric entries, and the numeric leaves of its struct
 * entries, as columns of timestamps and values that a reader maps instead of decoding the log.
 * Everything is little-endian, and every section starts on an eight-byte boundary, so a value
 * never straddles two of the reader's mapping windows.
 *
 * <pre>
 * header     magic, format version, recipe version, two reserved words               32 bytes
 * sections   per entry: the timestamps of its decoded records (int64 microseconds), shared
 *            by every column with a value in each record; its records' facts, packed (see
 *            Packed): each record's byte position in the log, its payload's size when the
 *            sizes vary, and its index among the entry's records in file order when that is
 *            not its position; per column: its own timestamps when it has fewer samples, its
 *            values (8 bytes each), and for an array element missing from some records, each
 *            sample's record position (int32)
 * directory  the source's identity; per entry its name, type, record counts, decode problem,
 *            record facts and columns; per section its offset and CRC32C
 * footer     directory offset and length, CRC32C of the header and directory, magic    24 bytes
 * </pre>
 *
 * <p>A reader rejects a file whose magic, versions, footer or directory does not hold, before
 * it reads a value, and checks a section's checksum the first time a read uses it, so a read
 * pays only for the columns it asks for. The directory's counts and offsets are checked against
 * the file and against fixed limits before anything is allocated, so a corrupt or crafted file
 * can neither crash the server nor exhaust its heap. The recipe version changes whenever the
 * values a file holds would change for the same log.
 *
 * <p>Format 2 added the record facts, which native reads report with every sample; a file of
 * format 1 is refused, and the startup sweep removes it for a rebuild.
 */
public final class ColumnFileFormat {
  private ColumnFileFormat() {}

  /** "WPICOLS1", read little-endian. */
  public static final long MAGIC = 0x31534C4F43495057L;
  /** "COLE", read little-endian. */
  public static final int FOOTER_MAGIC = 0x454C4F43;
  public static final int FORMAT_VERSION = 2;
  public static final int RECIPE_VERSION = 1;
  static final int HEADER_BYTES = 32;
  static final int FOOTER_BYTES = 24;

  /** The scan index's limits, so a log whose index can be kept can have its columns kept. */
  static final int MAX_ENTRIES = 200_000;
  static final int MAX_STRING_BYTES = 1 << 20;
  static final int MAX_DIRECTORY_BYTES = 64 << 20;
  /** Columns in one file, which bounds the directory's heap at about 16 MB. */
  static final int MAX_COLUMNS = 65_536;
  /** Columns of one struct entry: 64 elements of a Pose3d array need 832. */
  static final int MAX_ENTRY_COLUMNS = 4_096;
  /** An array element at this index or beyond gets no column, so a long array cannot fill the
   * directory; a read that needs one takes the log's own path. */
  static final int MAX_INDEX = 64;
  /** Steps in a path, as the field path grammar allows. */
  static final int MAX_DEPTH = 64;
  /** Characters in a column's path, so a schema's long field names cannot bloat the directory. */
  static final int MAX_PATH_CHARS = 4_096;

  /** How a column's eight-byte values are stored; the code is its byte in the directory. */
  public enum Kind {
    /** IEEE 754 binary64 bits, NaN payloads kept. */
    F64(0),
    /** binary32 bits in the low half, so a float comes back as the same float. */
    F32(1),
    /** A two's-complement 64-bit integer, exact. */
    I64(2),
    /** 0 or 1. */
    BOOL(3),
    /** An enum field's stored value; its label is not kept. */
    ENUM(4);

    final int code;

    Kind(int code) { this.code = code; }

    static Kind of(int code) throws IOException {
      for (var kind : values()) if (kind.code == code) return kind;
      throw new IOException("Unknown column kind " + code);
    }

    /**
     * The value as the tools read it: a boolean as 1 or 0, an enum as its number, and every
     * other value converted to double as {@code Number.doubleValue} converts the decoded object.
     */
    public double number(long bits) {
      return switch (this) {
        case F64 -> Double.longBitsToDouble(bits);
        case F32 -> Float.intBitsToFloat((int) bits);
        case I64, ENUM -> bits;
        case BOOL -> bits != 0 ? 1.0 : 0.0;
      };
    }

    /**
     * The value as the decode gives a scalar entry's: a Double, Float, Long or Boolean. An enum
     * field has no such value here, since its label is not kept.
     */
    public Object value(long bits) {
      return switch (this) {
        case F64 -> Double.longBitsToDouble(bits);
        case F32 -> Float.intBitsToFloat((int) bits);
        case I64 -> bits;
        case BOOL -> bits != 0;
        case ENUM -> throw new IllegalStateException("A column file keeps an enum's number, not its label");
      };
    }
  }

  /** The kind that holds a decoded leaf exactly, or null when no kind does. */
  static Kind kindOf(Object leaf) {
    if (leaf instanceof Double) return Kind.F64;
    if (leaf instanceof Float) return Kind.F32;
    if (leaf instanceof Long) return Kind.I64;
    if (leaf instanceof Boolean) return Kind.BOOL;
    if (leaf instanceof EnumValue) return Kind.ENUM;
    return null;
  }

  /** A leaf's stored bits, for a leaf whose {@link #kindOf} is not null. */
  static long bitsOf(Object leaf) {
    if (leaf instanceof Double d) return Double.doubleToRawLongBits(d);
    if (leaf instanceof Float f) return Float.floatToRawIntBits(f) & 0xFFFF_FFFFL;
    if (leaf instanceof Long l) return l;
    if (leaf instanceof Boolean b) return b ? 1 : 0;
    if (leaf instanceof EnumValue e) return e.value();
    throw new IllegalArgumentException("No column kind holds " + leaf.getClass().getSimpleName());
  }
}
