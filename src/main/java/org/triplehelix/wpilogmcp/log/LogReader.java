/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import edu.wpi.first.util.datalog.*;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.NoSuchElementException;

/** Long-addressed framing around WPILib's DataLogRecord. WPILib still decodes each complete
 * payload: the byte source supplies a zero-copy window view or a copy of a straddling payload.
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
  /** Hash a range in bounded chunks. Resume callers request only header/tail anchors;
   * persisted-index admission explicitly requests the whole file through contentSha256. */
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
  /** A persisted index needs a current whole-content proof, not the resume path's two anchors.
   * The bounded views belong to the reader's existing lease and never allocate a file-sized array. */
  String contentSha256() {
    if (source == null) throw new IllegalStateException("Content proof requires an owned byte source");
    return java.util.HexFormat.of().formatHex(fingerprint(0, size()));
  }
  /** The SHA-256 and the CRC32C of the whole content in one pass: the proof an index is written
   * with, and the cheap check a later open falls back to when only the file's time moved. */
  ContentProof contentProof() {
    if (source == null) throw new IllegalStateException("Content proof requires an owned byte source");
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      var crc = new java.util.zip.CRC32C();
      for (long pos = 0, end = size(); pos < end;) {
        int count = (int) Math.min(65536, end - pos);
        var chunk = source.view(pos, count);
        digest.update(chunk.duplicate()); crc.update(chunk); pos += count;
      }
      return new ContentProof(java.util.HexFormat.of().formatHex(digest.digest()), crc.getValue());
    } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
  }
  /** The CRC32C of the whole content, about a quarter of the SHA-256's cost: enough to tell an
   * index's bytes from changed ones, not to stand in for a store manifest's hash. */
  long contentCrc32c() {
    if (source == null) throw new IllegalStateException("Content proof requires an owned byte source");
    var crc = new java.util.zip.CRC32C();
    for (long pos = 0, end = size(); pos < end;) {
      int count = (int) Math.min(65536, end - pos);
      crc.update(source.view(pos, count)); pos += count;
    }
    return crc.getValue();
  }
  record ContentProof(String sha256, long crc32c) {}
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
    try {
      int flags = source.get(offset) & 255;
      int entryBytes = (flags & 3) + 1;
      int sizeBytes = ((flags >>> 2) & 3) + 1;
      int timestampBytes = ((flags >>> 4) & 7) + 1;
      int headerBytes = 1 + entryBytes + sizeBytes + timestampBytes;
      if (offset < 0 || offset > size() - headerBytes) throw new IndexOutOfBoundsException();
      long payloadBytes = unsigned(offset + 1 + entryBytes, sizeBytes);
      if (payloadBytes > size() - offset - headerBytes) throw new IndexOutOfBoundsException();
      // Preserve the existing int-sized complete-record bound, including its header.
      Math.toIntExact(headerBytes + payloadBytes);
      int entry = (int) unsigned(offset + 1, entryBytes);
      long timestamp = unsigned(offset + 1 + entryBytes + sizeBytes, timestampBytes);
      return DataLogAccess.record(entry, timestamp,
          source.view(offset + headerBytes, (int) payloadBytes), headerBytes);
    } catch (IndexOutOfBoundsException e) {
      throw new NoSuchElementException("Incomplete WPILOG record at byte " + offset);
    }
  }
  /** A time-window lookup must not copy a straddling payload merely to compare its timestamp. */
  public long recordTimestampUs(long offset) {
    return legacy != null ? DataLogAccess.recordTimestampUs(legacy, offset) : headerField(offset, 2);
  }
  /** Unsigned payload length; a caller can reject an oversized value before obtaining its view. */
  public long recordPayloadSize(long offset) {
    return legacy != null ? DataLogAccess.recordPayloadSize(legacy, offset) : headerField(offset, 1);
  }
  /** The index only needs payloads for entry zero's declarations and metadata records. */
  public int recordEntry(long offset) {
    return legacy != null ? DataLogAccess.recordEntry(legacy, offset) : (int) headerField(offset, 0);
  }

  private long headerField(long offset, int field) {
    try {
      if (offset < 0 || offset >= size()) throw new IndexOutOfBoundsException();
      int flags = source.get(offset) & 255;
      int entryBytes = (flags & 3) + 1;
      int sizeBytes = ((flags >>> 2) & 3) + 1;
      int timestampBytes = ((flags >>> 4) & 7) + 1;
      int headerBytes = 1 + entryBytes + sizeBytes + timestampBytes;
      // Subtraction keeps even a hostile long address from wrapping around the file bounds.
      if (offset < 0 || offset > size() - headerBytes) throw new IndexOutOfBoundsException();
      long payloadBytes = unsigned(offset + 1 + entryBytes, sizeBytes);
      if (payloadBytes > size() - offset - headerBytes) throw new IndexOutOfBoundsException();
      return switch (field) {
        case 0 -> unsigned(offset + 1, entryBytes);
        case 1 -> payloadBytes;
        case 2 -> unsigned(offset + 1 + entryBytes + sizeBytes, timestampBytes);
        default -> throw new AssertionError("Unknown WPILOG header field");
      };
    } catch (IndexOutOfBoundsException e) {
      throw new NoSuchElementException("Incomplete WPILOG record at byte " + offset);
    }
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
