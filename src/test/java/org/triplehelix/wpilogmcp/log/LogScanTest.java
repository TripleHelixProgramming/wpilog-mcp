/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import edu.wpi.first.util.datalog.DataLogReader;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.subsystems.LogParser;

/**
 * The record scan both parsers share, on logs damaged the ways real ones are: a write cut off
 * mid-file leaves records for undeclared entry ids and timestamps far outside the log (three of
 * 88 real logs reported time ranges of 1e7 to 6e10 s this way).
 */
@DisplayName("LogScan")
class LogScanTest {

  @TempDir Path dir;

  static final long SEC = 1_000_000L;

  static byte[] dbl(double v) {
    return WpilogWriter.encodeDouble(v);
  }

  /** Both parsers read the file the same way. */
  void assertParsersAgree(Path path, double min, double max, int voltageSamples)
      throws Exception {
    try (var lazy = new LazyParsedLog(path.toString(), new DataLogReader(path.toString()),
        64L * 1024 * 1024)) {
      assertEquals(min, lazy.minTimestamp(), 1e-9, "lazy min");
      assertEquals(max, lazy.maxTimestamp(), 1e-9, "lazy max");
      assertEquals(voltageSamples, lazy.values().get("/Battery/Voltage").size(), "lazy count");
    }
    var eager = new LogParser().parse(path);
    assertEquals(min, eager.minTimestamp(), 1e-9, "eager min");
    assertEquals(max, eager.maxTimestamp(), 1e-9, "eager max");
    assertEquals(voltageSamples, eager.values().get("/Battery/Voltage").size(), "eager count");
  }

  @Test
  @DisplayName("a clean log: its whole time range, not truncated")
  void clean() throws Exception {
    var path = dir.resolve("clean.wpilog");
    try (var w = new WpilogWriter(path, "")) {
      int v = w.start("/Battery/Voltage", "double", "", 0);
      for (int i = 1; i <= 10; i++) w.append(v, i * SEC, dbl(12.0));
    }
    var scan = LogScan.of(new DataLogReader(path.toString()), path);
    assertFalse(scan.truncated());
    assertNull(scan.truncationMessage());
    assertEquals(10, scan.dataRecords());
    assertParsersAgree(path, 1.0, 10.0, 10);
  }

  @Test
  @DisplayName("a record for an undeclared entry ends the scan; later records are not read")
  void undeclaredEntryTail() throws Exception {
    var path = dir.resolve("undeclared.wpilog");
    try (var w = new WpilogWriter(path, "")) {
      int v = w.start("/Battery/Voltage", "double", "", 0);
      for (int i = 1; i <= 10; i++) w.append(v, i * SEC, dbl(12.0));
      w.append(1007427528, 2_384_800_000_000_000L, new byte[151]); // garbage
      w.append(v, 98_538_759L * SEC, dbl(3.0)); // parses as a real entry, but is garbage
    }
    var scan = LogScan.of(new DataLogReader(path.toString()), path);
    assertTrue(scan.truncated());
    assertTrue(scan.truncationMessage().contains("entry id 1007427528, which the log never "
        + "declared"), scan.truncationMessage());
    assertTrue(scan.truncationMessage().contains("Data from 1.00 to 10.00 s was recovered"),
        scan.truncationMessage());
    assertParsersAgree(path, 1.0, 10.0, 10);
  }

  @Test
  @DisplayName("records whose time runs backward just before the damage belong to it")
  void backwardRecordsBeforeDamage() throws Exception {
    var path = dir.resolve("backward_tail.wpilog");
    try (var w = new WpilogWriter(path, "")) {
      int v = w.start("/Battery/Voltage", "double", "", 0);
      for (int i = 1; i <= 100; i++) w.append(v, i * SEC, dbl(12.0));
      w.append(v, 172L, dbl(0.0)); // 0.000172 s: garbage
      w.append(v, 63_456_800L * SEC, dbl(0.0)); // 6.3e7 s forward: garbage, skipped
      w.append(30912, 253L, new byte[4]); // undeclared: the damage
    }
    var scan = LogScan.of(new DataLogReader(path.toString()), path);
    assertTrue(scan.truncationMessage().contains("the 1 record just before it, whose "
        + "timestamps ran backward, was dropped"), scan.truncationMessage());
    assertTrue(scan.truncationMessage().contains("1 record whose timestamp jumps more than a "
        + "day"), scan.truncationMessage());
    assertParsersAgree(path, 1.0, 100.0, 100);
  }

  @Test
  @DisplayName("a forward jump of more than a day is ignored anywhere; the rest is read")
  void forwardJumpMidLog() throws Exception {
    var path = dir.resolve("jump.wpilog");
    try (var w = new WpilogWriter(path, "")) {
      int v = w.start("/Battery/Voltage", "double", "", 0);
      for (int i = 1; i <= 10; i++) w.append(v, i * SEC, dbl(12.0));
      w.append(v, 1_000_000_000L * SEC, dbl(12.0));
      for (int i = 11; i <= 20; i++) w.append(v, i * SEC, dbl(12.0));
    }
    var scan = LogScan.of(new DataLogReader(path.toString()), path);
    assertTrue(scan.truncated());
    assertFalse(scan.truncationMessage().contains("rest of the file"), scan.truncationMessage());
    assertParsersAgree(path, 1.0, 20.0, 20);
  }

  @Test
  @DisplayName("an older timestamp mid-log (NetworkTables logs a value's last change) is kept")
  void backwardMidLogIsKept() throws Exception {
    var path = dir.resolve("nt_initial.wpilog");
    try (var w = new WpilogWriter(path, "")) {
      int v = w.start("/Battery/Voltage", "double", "", 0);
      for (int i = 100; i <= 200; i++) w.append(v, i * SEC, dbl(12.0));
      int nt = w.start("NT:/SmartDashboard/Mode", "string", "", 200 * SEC);
      w.append(nt, 5 * SEC, WpilogWriter.encodeString("Idle")); // last changed at 5 s
      for (int i = 201; i <= 210; i++) w.append(v, i * SEC, dbl(12.0));
    }
    var scan = LogScan.of(new DataLogReader(path.toString()), path);
    assertFalse(scan.truncated(), scan.truncationMessage());
    assertParsersAgree(path, 5.0, 210.0, 111);
  }

  @Test
  @DisplayName("an entry restarted with another type is ignored, not read as damage")
  void restartedWithAnotherType() throws Exception {
    var path = dir.resolve("restart.wpilog");
    try (var w = new WpilogWriter(path, "")) {
      int v = w.start("/Battery/Voltage", "double", "", 0);
      for (int i = 1; i <= 5; i++) w.append(v, i * SEC, dbl(12.0));
      int other = w.start("/Battery/Voltage", "string", "", 6 * SEC);
      w.append(other, 6 * SEC, WpilogWriter.encodeString("12 V"));
      for (int i = 7; i <= 10; i++) w.append(v, i * SEC, dbl(12.0));
    }
    var scan = LogScan.of(new DataLogReader(path.toString()), path);
    assertFalse(scan.truncated(), scan.truncationMessage());
    assertParsersAgree(path, 1.0, 10.0, 9);
  }

  @Test
  @DisplayName("a file cut off inside a record: what came before is read")
  void cutOffInsideRecord() throws Exception {
    var path = dir.resolve("cut.wpilog");
    try (var w = new WpilogWriter(path, "")) {
      int v = w.start("/Battery/Voltage", "double", "", 0);
      for (int i = 1; i <= 10; i++) w.append(v, i * SEC, dbl(12.0));
    }
    var bytes = java.nio.file.Files.readAllBytes(path);
    java.nio.file.Files.write(path, java.util.Arrays.copyOf(bytes, bytes.length - 3));
    var scan = LogScan.of(new DataLogReader(path.toString()), path);
    assertTrue(scan.truncated());
    assertTrue(scan.truncationMessage().startsWith("Log file is truncated or damaged: the file "
        + "ends inside a record"), scan.truncationMessage());
    assertParsersAgree(path, 1.0, 9.0, 9);
  }
}
