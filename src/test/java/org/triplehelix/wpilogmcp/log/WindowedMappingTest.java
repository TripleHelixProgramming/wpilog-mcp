/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import edu.wpi.first.util.datalog.DataLogAccess;

class WindowedMappingTest {
  @TempDir Path temp;
  private Path fixture(String name, int target, boolean atEnd, boolean truncate) throws Exception {
    var path = temp.resolve(name);
    try (var w = new WpilogWriter(path, "")) { int id = w.start("/x", "double", "", 0); w.append(id, 2, WpilogWriter.encodeDouble(42)); }
    byte[] raw = Files.readAllBytes(path);
    int start = raw.length - 12; // minimal header: id/size/timestamp one byte each, double payload 8.
    int extra = target - (atEnd ? raw.length : start);
    var out = ByteBuffer.allocate(raw.length + extra - (truncate ? 1 : 0)).order(ByteOrder.LITTLE_ENDIAN);
    out.put(raw, 0, 8).putInt(extra).put(new byte[extra]).put(raw, 12, raw.length - 12 - (truncate ? 1 : 0));
    Files.write(path, out.array()); return path;
  }
  @Test void recordEndingExactlyAtBoundaryAndBothKindsOfStraddle() throws Exception {
    for (boolean ending : List.of(true, false)) {
      var file = fixture(ending + ".wpilog", ending ? 4096 : 4094, ending, false);
      try (var scoped = new ScopedLogReader(file, 4096)) {
        var scan = LogScan.of(scoped.reader(), file); assertFalse(scan.truncated());
        long offset = scan.offsets().get("/x").get(0);
        assertEquals(ending ? 4096 : 4106, scoped.reader().recordEnd(offset));
        assertEquals(42, scoped.reader().getRecord(offset).getDouble());
      }
      // Even the 12-byte file header may cross a deliberately tiny test window.
      try (var scoped = new ScopedLogReader(file, 7)) { assertTrue(scoped.reader().isValid()); assertEquals(1, LogScan.of(scoped.reader(), file).dataRecords()); }
    }
  }
  @Test void truncatedLastRecordCrossingTheBoundaryIsExplained() throws Exception {
    var file = fixture("cut.wpilog", 4094, false, true);
    try (var scoped = new ScopedLogReader(file, 4096)) {
      assertEquals(-1, scoped.reader().recordEnd(4094));
      var scan = LogScan.of(scoped.reader(), file);
      assertTrue(scan.truncated()); assertFalse(scan.damaged()); assertEquals(0, scan.dataRecords());
      assertTrue(scan.truncationMessage().contains("byte 4094"));
    }
  }
  @Test void wideOffsetsStayLongThroughIndexAndRandomAccess() throws Exception {
    long offset = (1L << 31) + 4093;
    var path = temp.resolve("sparse.wpilog");
    // No two-gigabyte allocation: a valid header and one independently encoded record at a wide address.
    try (var channel = java.nio.channels.FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
      channel.write(ByteBuffer.wrap(new byte[]{'W','P','I','L','O','G',0,1,0,0,0,0}));
      var record = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put(new byte[]{0,1,8,2}).putDouble(42).flip();
      channel.position(offset); channel.write(record);
    }
    var offsets = new RecordOffsets(); offsets.add(12); offsets.add(offset); offsets.compact();
    assertEquals(16, offsets.storageBytes()); assertEquals(offset, offsets.get(1));
    try (var scoped = new ScopedLogReader(path)) {
      assertEquals(3, scoped.mappedWindows());
      assertEquals(42, DataLogAccess.getRecord(scoped.reader(), offsets.get(1)).getDouble());
      assertEquals(offset + 12, DataLogAccess.recordEnd(scoped.reader(), offset));
    }
  }
  @Test void iterationKeepsTheUpstreamRevContractWhileRandomAccessIncludesShortTails() throws Exception {
    var file = fixture("iterator.wpilog", 4094, false, false);
    var upstream = new edu.wpi.first.util.datalog.DataLogReader(ByteBuffer.wrap(Files.readAllBytes(file)));
    try (var scoped = new ScopedLogReader(file, 4096)) {
      var expected = upstream.iterator(); var actual = scoped.reader().iterator(); int records = 0;
      while (expected.hasNext()) {
        assertTrue(actual.hasNext()); var a = actual.next(); var b = expected.next();
        assertEquals(b.getTimestamp(), a.getTimestamp()); assertArrayEquals(b.getRaw(), a.getRaw()); records++;
      }
      assertFalse(actual.hasNext()); assertEquals(1, records, "Only the declaration passes the upstream 16-byte tail guard");
      assertEquals(42, scoped.reader().getRecord(4094).getDouble(), "The normal WPILOG scan includes the complete short tail");
    }
  }
  @Test void everyWindowIsCleanedBeforeTheFileCanMove() throws Exception {
    var file = fixture("move.wpilog", 8200, false, false);
    var bytes = new MappedLogBytes(file, 4096); assertEquals(3, bytes.openWindows());
    assertEquals(42, new LogReader(bytes).getRecord(8200).getDouble());
    bytes.close(); assertEquals(0, bytes.openWindows());
    Files.move(file, temp.resolve("moved.wpilog"));
    assertThrows(IllegalStateException.class, () -> bytes.get(0)); bytes.close();
  }
  @Test void aColdLiveValuePastTwoGiBUsesTheSameLongAddressedSource() throws Exception {
    wideOffsetsStayLongThroughIndexAndRandomAccess();
    var file = temp.resolve("sparse.wpilog"); long offset = (1L << 31) + 4093;
    var live = new LiveLog(file, 0); var entry = new EntryInfo(1, "/x", "double", ""); live.announce(entry);
    try {
      live.append(entry, new org.triplehelix.wpilogmcp.nt4.ValueFrame(1, 2, 1, 42.),
          new org.triplehelix.wpilogmcp.capture.WpilogOutput.Written(offset, 12, offset + 4, 8));
      live.expire(); assertEquals(0, live.hotRecordCount());
      assertEquals(42., live.values().get("/x").get(0).value()); assertEquals(1, live.mappedReadCount());
    } finally { live.finish(); live.close(); }
  }
}
