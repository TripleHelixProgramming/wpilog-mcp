/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;

class ReplaySourceTest {
  @TempDir Path directory;
  // WPILOG specification: Start id=1, name=test, type=boolean; a five-byte final data record.
  private static final String LOG = "5750494c4f47000100000000"
      + "00001c000001000000040000007465737407000000626f6f6c65616e00000000"
      + "0001010001";

  @Test void handEncodedShortFinalRecordAndDeclaredBounds() throws Exception {
    var path = directory.resolve("short.wpilog"); Files.write(path, HexFormat.of().parseHex(LOG));
    try (var source = new ReplaySource(path)) {
      assertEquals(1, source.records); assertEquals(5, source.bytes);
      var entry = source.entries.get("test"); assertEquals("boolean", entry.type); assertEquals(1, entry.count);
      var value = source.reader.at(entry.offset(0)); assertEquals(44, value.offset()); assertEquals(49, value.end());
      assertEquals(0, value.timestampUs()); assertArrayEquals(new byte[] {1}, source.reader.payload(value));
      assertEquals(true, ReplaySource.value("boolean", source.reader.payload(value)));
    }
    // Undeclared id=2 is damage, not an entry the replay is allowed to invent; later bytes stay out.
    Files.write(path, HexFormat.of().parseHex(LOG + "0002010100" + "0001010201"));
    try (var source = new ReplaySource(path)) { assertEquals(1, source.records); assertEquals("undeclared_entry", source.reader.stopped); }
    Files.write(path, HexFormat.of().parseHex(LOG + "00010202"));
    try (var source = new ReplaySource(path)) { assertEquals(1, source.records); assertEquals("incomplete_payload", source.reader.stopped); }
  }

  @Test void unknownMajorVersionCannotBecomeAnEmptySuccessfulReplay() throws Exception {
    var bytes = HexFormat.of().parseHex(LOG); bytes[7] = 2;
    var path = directory.resolve("version.wpilog"); Files.write(path, bytes);
    assertThrows(IndependentLog.NotALog.class, () -> new ReplaySource(path));
  }

  @Test void invalidUtf8CannotBeReportedAsALosslessString() {
    assertThrows(ReplaySource.InvalidUtf8.class, () -> ReplaySource.value("string", new byte[] {(byte) 0xc3, 0x28}));
  }

  @Test void directoryReportDistinguishesAnImpossibleStringFromASuccessfulReplay() throws Exception {
    var path = directory.resolve("invalid.wpilog");
    try (var out = new WpilogWriter(path, "")) {
      int id = out.start("NT:/text", "string", "", 1_000_000);
      out.append(id, 1_000_000, new byte[] {(byte) 0xc3, 0x28}); out.finish(id, 1_000_000);
    }
    assertEquals(1, ReplayInputCheck.invalidUtf8(path));
    String previous = System.getProperty("conformance.logdir");
    try {
      System.setProperty("conformance.logdir", directory.toString());
      new RealLogReplayTest().replayDirectory();
    } finally {
      if (previous == null) System.clearProperty("conformance.logdir"); else System.setProperty("conformance.logdir", previous);
    }
    var report = Path.of("build/reports/replay").resolve("gateway-" + directory.getFileName() + ".jsonl");
    var first = com.google.gson.JsonParser.parseString(Files.readAllLines(report).get(0)).getAsJsonObject();
    assertTrue(first.get("rejected_invalid_utf8").getAsBoolean());
    assertEquals(1, first.get("invalid_utf8_records").getAsLong());
    assertEquals(1, first.get("records").getAsLong()); assertFalse(first.has("captured_records"));
  }

  @Test void deviceSeedUsesTheLoggedSerialAfterEmptyStartupValues() throws Exception {
    var path = directory.resolve("identity.wpilog");
    try (var out = new WpilogWriter(path, "")) {
      int serial = out.start("/SystemStats/SerialNumber", "string", "", 0);
      out.append(serial, 0, WpilogWriter.encodeString(""));
      out.append(serial, 1_000_000, WpilogWriter.encodeString(" REPLAY-A "));
    }
    try (var source = new ReplaySource(path)) { assertEquals("REPLAY-A", source.serial()); }
  }

  @Test void calendarUsesRecordedEpochThenDatalogManagerDateAndNeverTheHostDate() throws Exception {
    var path = directory.resolve("FRC_20260307_142233.wpilog");
    var epoch = Instant.parse("2026-04-01T01:02:03Z");
    try (var writer = new WpilogWriter(path, "")) {
      int id = writer.start("systemTime", "int64", "", 0);
      writer.append(id, 10_000_000, WpilogWriter.encodeInt64(epoch.toEpochMilli() * 1000));
    }
    try (var source = new ReplaySource(path)) {
      assertEquals(new ReplaySource.Calendar(epoch, "system_time"), source.calendar().orElseThrow());
      var clock = new ReplayClock(epoch, source.minUs + 120_000);
      clock.advance(source.minUs + 1_120_000); assertEquals(epoch.plusSeconds(1), clock.instant());
      clock.advance(source.minUs); assertEquals(epoch.plusSeconds(1), clock.instant());
    }
    Files.write(path, HexFormat.of().parseHex(LOG));
    try (var source = new ReplaySource(path)) {
      assertEquals(new ReplaySource.Calendar(Instant.parse("2026-03-07T14:22:33Z"), "datalogmanager_filename_assumed_utc"), source.calendar().orElseThrow());
    }
    var undated = directory.resolve("undated.wpilog"); Files.copy(path, undated);
    try (var source = new ReplaySource(undated)) { assertTrue(source.calendar().isEmpty()); }
  }
}
