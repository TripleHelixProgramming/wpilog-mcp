/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import edu.wpi.first.util.datalog.DataLogRecord;
import java.io.IOException;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.RecordOffsets;
import org.triplehelix.wpilogmcp.log.subsystems.EntryDecoder;

/** Snapshot lifetime and file-change checks surround the shared record-selection engine. */
final class LocalSampleCursor implements SampleCursor {
  private final SampleCursor cursor;

  LocalSampleCursor(LocalReadSnapshot snapshot, EntryInfo entry, RecordOffsets offsets,
      int[] order, ReadQuery query) {
    var series = new RecordSeries() {
      private DataLogRecord record;
      @Override public int size() { return offsets.size(); }
      @Override public long timestampUs(int index) {
        record = null;
        return snapshot.reader().recordTimestampUs(offsets.get(index));
      }
      @Override public long offset(int index) { return offsets.get(index); }
      @Override public long payloadBytes(int index) {
        record = null;
        return snapshot.reader().recordPayloadSize(offsets.get(index));
      }
      @Override public boolean monotonic() { return offsets.monotonic(); }
      @Override public Object decode(int index) {
        record = null;
        record = snapshot.reader().getRecord(offsets.get(index));
        return EntryDecoder.decodeRecordedValue(record, entry.type(), snapshot.schemas());
      }
      @Override public String problemMessage(int index, Exception failure) {
        return EntryDecoder.malformedMessage(record, entry.type(), failure);
      }
    };
    cursor = new GuardedSampleCursor(
        SampleCursors.open(series, query, order, snapshot.maxRecordBytes()),
        snapshot::checkUnchanged, snapshot::releaseCursor, snapshot.path().toString());
  }

  @Override public SampleBatch next() throws IOException { return cursor.next(); }
  @Override public void close() throws IOException { cursor.close(); }
}
