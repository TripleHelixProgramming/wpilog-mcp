/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import edu.wpi.first.util.datalog.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.NoSuchElementException;

/** Long-addressed framing around WPILib's DataLogReader. WPILib still decodes each complete
 * record: the byte source supplies a zero-copy window view or a copy of a straddling record.
 * The upstream reader adapter keeps independent tests using WPILib's original buffer reader. */
public final class LogReader implements Iterable<DataLogRecord> {
  private final LogBytes source;
  private final DataLogReader legacy;
  public LogReader(LogBytes source) { this.source = source; legacy = null; }
  private LogReader(DataLogReader reader) { source = null; legacy = reader; }
  public static LogReader of(DataLogReader reader) { return new LogReader(reader); }
  public long size() { return legacy != null ? DataLogAccess.size(legacy) : source.size(); }
  public boolean isValid() {
    return legacy != null ? legacy.isValid() : size() >= 12 && new DataLogReader(source.view(0, 12)).isValid();
  }
  public String getExtraHeader() {
    if (legacy != null) return legacy.getExtraHeader();
    long length = unsigned(8, 4);
    var bytes = source.view(12, Math.toIntExact(length));
    return StandardCharsets.UTF_8.decode(bytes).toString();
  }
  private long unsigned(long offset, int bytes) {
    long value = 0;
    for (int i = 0; i < bytes; i++) value |= (source.get(offset + i) & 255L) << (i * 8);
    return value;
  }
  /** Hash only an anchor, in bounded chunks; never hash the entire indexed prefix. */
  byte[] fingerprint(long start, long end) {
    if (source == null) return null; // Legacy buffer readers do not supply resume checkpoints.
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      for (long pos = start; pos < end;) {
        int count = (int) Math.min(65536, end - pos);
        digest.update(source.view(pos, count)); pos += count;
      }
      return digest.digest();
    } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
  }
  public long recordEnd(long offset) {
    if (legacy != null) return DataLogAccess.recordEnd(legacy, Math.toIntExact(offset));
    try {
      int flags = source.get(offset) & 255;
      int entry = (flags & 3) + 1, size = ((flags >>> 2) & 3) + 1, timestamp = ((flags >>> 4) & 7) + 1;
      long end = offset + 1 + entry + size + timestamp + unsigned(offset + 1 + entry, size);
      return end > offset && end <= size() ? end : -1;
    } catch (IndexOutOfBoundsException e) { return -1; }
  }
  public DataLogRecord getRecord(long offset) {
    if (legacy != null) return DataLogAccess.getRecord(legacy, Math.toIntExact(offset));
    long end = recordEnd(offset);
    if (end < 0) throw new NoSuchElementException("Incomplete WPILOG record at byte " + offset);
    // The upstream decoder addresses this record from zero, never the file's long address.
    ByteBuffer record = source.view(offset, Math.toIntExact(end - offset));
    return DataLogAccess.getRecord(new DataLogReader(record), 0);
  }
  @Override public Iterator<DataLogRecord> iterator() {
    if (legacy != null) return legacy.iterator();
    long first = 12 + unsigned(8, 4);
    return new Iterator<>() {
      long offset = first;
      // Preserve the upstream iterator contract for existing REV parsing. WPILOG scans
      // deliberately use recordEnd instead, so they include complete short final records.
      @Override public boolean hasNext() { return offset + 16 <= size(); }
      @Override public DataLogRecord next() {
        var record = getRecord(offset); offset = recordEnd(offset); return record;
      }
    };
  }
}
