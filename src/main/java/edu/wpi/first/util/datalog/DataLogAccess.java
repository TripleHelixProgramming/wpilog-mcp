/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package edu.wpi.first.util.datalog;

/**
 * Exposes package-private methods from WPILib's {@link DataLogReader} for random access.
 *
 * <p>WPILib's {@code getRecord(int pos)} and {@code getNextRecord(int pos)} are package-private,
 * as is the {@link DataLogRecord} constructor. By placing this class in the same package, we
 * can delegate to them directly — no reimplementation of the binary format, no risk of divergence.
 *
 * <p>If WPILib makes these methods public in a future release, this class can be deleted.
 *
 * @since 0.8.0
 */
public final class DataLogAccess {

  private DataLogAccess() {}

  // Per-thread test instrumentation counts bytes accessed through mapped records, not mapping size.
  static final ThreadLocal<java.util.function.LongConsumer> READ_BYTES = new ThreadLocal<>();

  public static DataLogRecord getRecord(org.triplehelix.wpilogmcp.log.LogReader reader, long pos) { return reader.getRecord(pos); }
  public static long recordEnd(org.triplehelix.wpilogmcp.log.LogReader reader, long pos) { return reader.recordEnd(pos); }
  public static long size(org.triplehelix.wpilogmcp.log.LogReader reader) { return reader.size(); }

  /** Reads a record at the given byte offset. */
  public static DataLogRecord getRecord(DataLogReader reader, long pos) {
    var observer = READ_BYTES.get();
    if (observer != null) observer.accept(reader.getNextRecord(Math.toIntExact(pos)) - (long) pos);
    return reader.getRecord(Math.toIntExact(pos));
  }

  /** Computes the byte offset of the next record after the one at {@code pos}. */
  public static long getNextRecord(DataLogReader reader, long pos) {
    return reader.getNextRecord(Math.toIntExact(pos));
  }

  /** Total buffer size in bytes. */
  public static long size(DataLogReader reader) {
    return reader.size();
  }

  /**
   * The offset just past the record at {@code pos}, or -1 when the buffer ends inside it.
   *
   * <p>Walk records with this rather than the reader's iterator: WPILib's
   * {@code DataLogIterator.hasNext()} requires 16 bytes after a record's start, so it silently
   * skips a short final record (a boolean, a small struct, a Finish record).
   */
  public static long recordEnd(DataLogReader reader, long pos) {
    try {
      int next = reader.getNextRecord(Math.toIntExact(pos));
      return next > pos && next <= reader.size() ? next : -1;
    } catch (IndexOutOfBoundsException e) {
      return -1;
    }
  }

  /**
   * The offset of the first record: after the 12-byte header and the extra header, whose length
   * is read from the file itself (re-encoding the decoded extra header string can differ).
   */
  public static long firstRecordOffset(java.nio.file.Path path) throws java.io.IOException {
    try (var channel = java.nio.channels.FileChannel.open(path)) {
      var header = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      while (header.hasRemaining() && channel.read(header) >= 0) {
        // read the whole 12-byte header
      }
      if (header.hasRemaining()) throw new java.io.IOException("WPILOG header is incomplete");
      return 12L + Integer.toUnsignedLong(header.getInt(8));
    }
  }
}
