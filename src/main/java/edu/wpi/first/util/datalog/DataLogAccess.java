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

  /** Reads a record at the given byte offset. */
  public static DataLogRecord getRecord(DataLogReader reader, int pos) {
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
}
