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

  /** Reads a record at the given byte offset. */
  public static DataLogRecord getRecord(DataLogReader reader, int pos) {
    var observer = READ_BYTES.get();
    if (observer != null) observer.accept(reader.getNextRecord(pos) - (long) pos);
    return reader.getRecord(pos);
  }

  /** Computes the byte offset of the next record after the one at {@code pos}. */
  public static int getNextRecord(DataLogReader reader, int pos) {
    return reader.getNextRecord(pos);
  }

  /** Total buffer size in bytes. */
  public static int size(DataLogReader reader) {
    return reader.size();
  }

  /**
   * The offset just past the record at {@code pos}, or -1 when the buffer ends inside it.
   *
   * <p>Walk records with this rather than the reader's iterator: WPILib's
   * {@code DataLogIterator.hasNext()} requires 16 bytes after a record's start, so it silently
   * skips a short final record (a boolean, a small struct, a Finish record).
   */
  public static int recordEnd(DataLogReader reader, int pos) {
    try {
      int next = reader.getNextRecord(pos);
      return next > pos && next <= reader.size() ? next : -1;
    } catch (IndexOutOfBoundsException e) {
      return -1;
    }
  }

  /**
   * The offset of the first record: after the 12-byte header and the extra header, whose length
   * is read from the file itself (re-encoding the decoded extra header string can differ).
   */
  public static int firstRecordOffset(java.nio.file.Path path) throws java.io.IOException {
    try (var channel = java.nio.channels.FileChannel.open(path)) {
      var header = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      while (header.hasRemaining() && channel.read(header) >= 0) {
        // read the whole 12-byte header
      }
      if (header.hasRemaining()) throw new java.io.IOException("WPILOG header is incomplete");
      return 12 + header.getInt(8);
    }
  }
}
