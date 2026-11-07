/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;

class ReplayPullTest {
  @TempDir Path directory;
  @org.junit.jupiter.api.Test void aBusWithoutAlignmentEvidenceStaysUnsynchronizedAfterReplay() throws Exception {
    var name = "20260307_142233";
    var path = directory.resolve("FRC_" + name + ".wpilog");
    try (var out = new WpilogWriter(path, "")) {
      int output = out.start("/Drive/AppliedOutput", "double", "", 1_000_000);
      for (int i = 0; i < 3000; i++) out.append(output, 1_000_000 + i * 20_000L,
          WpilogWriter.encodeDouble(Math.sin(i * i * 0.000007 + i * 0.013)));
    }
    try (var out = new WpilogWriter(directory.resolve("REV_" + name + ".revlog"), "")) {
      int can = out.start("CAN/3/Periodic Status 0", "raw", "", 0);
      for (int i = 0; i < 5000; i++) out.append(can, i * 10_000L,
          org.triplehelix.wpilogmcp.fixtures.FixtureLogs.sparkStatus0(0, 12, 0, 25, false));
    }
    Files.copy(directory.resolve("REV_" + name + ".revlog"), directory.resolve("REV_19700101_000000.revlog"));
    var incomplete = directory.resolve("REV_19700101_000001.revlog");
    Files.copy(directory.resolve("REV_" + name + ".revlog"), incomplete);
    Files.write(incomplete, new byte[] {0}, java.nio.file.StandardOpenOption.APPEND);
    try (var source = new ReplaySource(path)) {
      var report = gateway(source, directory.resolve("failed-bus"), 120_000);
      var pull = (Map<?, ?>) report.get("pull");
      var buses = (java.util.List<?>) pull.get("revlogs");
      assertEquals(3, buses.size(), "Even a sibling omitted by filename nomination must meet the same data check");
      assertEquals(1, ((Number) pull.get("retained_revlogs_peak")).intValue(),
          "Retain only the bus being compared, not every decoded source and capture copy");
      assertEquals(1, buses.stream().map(b -> (Map<?, ?>) b).filter(b -> Boolean.TRUE.equals(b.get("rejected_incomplete_source"))).count());
      for (var item : buses) {
      var bus = (Map<?, ?>) item;
      assertEquals(org.triplehelix.wpilogmcp.sync.SyncMethod.FAILED, bus.get("source_method"));
      assertEquals(org.triplehelix.wpilogmcp.sync.SyncMethod.FAILED, bus.get("capture_method"));
      assertEquals(true, bus.get("matches")); assertEquals(true, bus.get("http_visible"));
      }
    }
  }
  @org.junit.jupiter.api.Test void incompleteSourceFinishesItsOneRetryBeforeReportingRefusal() throws Exception {
    var path = directory.resolve("FRC_20260307_142233.wpilog");
    try (var out = new WpilogWriter(path, "")) {
      int raw = out.start("NT:/raw", "raw", "", 1_000_000);
      for (int i = 0; i < 1100; i++) out.append(raw, 1_000_000 + i * 20_000L, new byte[8192]);
    }
    Files.write(path, new byte[] {0}, java.nio.file.StandardOpenOption.APPEND);
    try (var source = new ReplaySource(path)) {
      var report = gateway(source, directory.resolve("incomplete"), 0);
      var pull = (Map<?, ?>) report.get("pull");
      assertEquals(true, pull.get("rejected_incomplete_source"), pull.toString());
      assertEquals(1, pull.get("retries")); assertEquals(Files.size(path), pull.get("bytes_held"));
      assertTrue(((Map<?, ?>) ((Map<?, ?>) report.get("fidelity")).get("mismatches")).isEmpty());
    }
  }
  @org.junit.jupiter.api.Test void revCompanionKeepsItsRelativeOffsetThroughRealSftp() throws Exception {
    var path = org.triplehelix.wpilogmcp.fixtures.FixtureLogs.writeRevlogPair(directory,
        "2026-revlog_pair.wpilog", java.time.ZoneOffset.UTC, "systemTime");
    try (var source = new ReplaySource(path)) {
      var report = gateway(source, directory.resolve("rev-store"), 120_000);
      var pull = (Map<?, ?>) report.get("pull");
      var rows = (java.util.List<?>) pull.get("revlogs"); assertFalse(rows.isEmpty());
      for (var item : rows) {
        var row = (Map<?, ?>) item;
        assertEquals(true, row.get("matches"), row.toString());
        assertEquals(true, row.get("copy_equal")); assertEquals(true, row.get("http_visible"));
      }
    }
  }

  @org.junit.jupiter.api.Test void revComparisonUsesTheWholeSourceRangeAcrossRollover() throws Exception {
    var path = org.triplehelix.wpilogmcp.fixtures.FixtureLogs.writeRevlogPair(directory,
        "2026-revlog_pair.wpilog", java.time.ZoneOffset.UTC, "systemTime");
    try (var source = new ReplaySource(path)) {
      var report = gateway(source, directory.resolve("rolled-rev"), 120_000, 8L << 10);
      var fidelity = (Map<?, ?>) report.get("fidelity");
      assertTrue(((Number) fidelity.get("files")).intValue() > 1);
      assertTrue(((Map<?, ?>) fidelity.get("mismatches")).isEmpty());
      var rows = (java.util.List<?>) ((Map<?, ?>) report.get("pull")).get("revlogs");
      assertEquals(1, rows.size(), "Compare each bus once over the same record set as the source");
      var bus = (Map<?, ?>) rows.get(0);
      assertEquals(source.entries.values().stream().filter(e -> java.util.List.of("double", "float", "int64").contains(e.type))
          .mapToLong(e -> e.count).sum(), ((Number) bus.get("numeric_records_compared")).longValue());
      assertEquals(true, bus.get("matches")); assertEquals(true, bus.get("http_visible"));
    }
  }
  static Path fixture(Path directory, ReplaySource.Kind kind) throws Exception {
    return fixture(directory, kind, 0);
  }
  static Path fixture(Path directory, ReplaySource.Kind kind, int boot) throws Exception {
    Files.createDirectories(directory);
    var date = Instant.parse("2026-03-07T14:22:33Z").plusSeconds(600L * boot);
    var path = directory.resolve("FRC_" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        .withZone(java.time.ZoneOffset.UTC).format(date) + ".wpilog");
    try (var out = new WpilogWriter(path, kind == ReplaySource.Kind.ADVANTAGEKIT ? "AdvantageKit" : "")) {
      String prefix = kind == ReplaySource.Kind.DATALOGMANAGER ? "NT:/" : "/";
      int serial = out.start("/SystemStats/SerialNumber", "string", "", 10_000_000);
      out.append(serial, 10_000_000, WpilogWriter.encodeString("REPLAY-A"));
      int time = out.start(kind == ReplaySource.Kind.ADVANTAGEKIT ? "/SystemStats/EpochTimeMicros" : "systemTime", "int64", "", 10_000_000);
      long epoch = date.toEpochMilli() * 1000;
      var ids = new int[3];
      for (int i = 0; i < ids.length; i++) ids[i] = out.start(prefix + (kind == ReplaySource.Kind.ADVANTAGEKIT ? "RealOutputs/" : "") + "drive" + i, "double", "{\"unit\":\"synthetic\"}", 10_000_000);
      for (int n = 0; n <= 1500; n++) {
        long timestamp = 10_000_000 + n * 40_000L;
        out.append(time, timestamp, WpilogWriter.encodeInt64(epoch + n * 40_000L));
        double t = n * 0.04;
        for (int i = 0; i < ids.length; i++) out.append(ids[i], timestamp, WpilogWriter.encodeDouble(
            Math.sin(t * t * (i + 1) * (0.007 + boot * 0.012) + t * (0.13 + boot * 0.24))));
      }
    }
    return path;
  }

  @ParameterizedTest @EnumSource(ReplaySource.Kind.class)
  void measuredShiftsPassThroughSftpAndPreserveThePlacementLimit(ReplaySource.Kind kind) throws Exception {
    try (var source = new ReplaySource(fixture(directory, kind))) {
      // Zero proves the baseline, -120 ms the sign, +240 ms acceptance just inside
      // 250 ms, and +260 ms refusal just outside it. The full matrix is opt-in.
      for (long shift : new long[] {0, -120_000, 240_000, 260_000}) {
        var run = Files.createDirectory(directory.resolve("shift-" + shift));
        var report = gateway(source, run, shift);
        var fidelity = (Map<?, ?>) report.get("fidelity");
        assertTrue(((Map<?, ?>) fidelity.get("mismatches")).isEmpty(), report.toString());
        var pull = (Map<?, ?>) report.get("pull");
        assertEquals(true, pull.get("verified")); assertEquals(true, pull.get("retrievable"));
        assertEquals(true, pull.get("copy_equal")); assertEquals(true, pull.get("same_serial"));
        assertEquals(Math.abs(shift) <= 250_000, pull.get("placed"), report.toString());
        if (Math.abs(shift) <= 250_000) assertEquals(shift, ((Number) pull.get("offset_us")).longValue(), 5_000, report.toString());
        else { assertEquals(true, pull.get("refusal_recorded")); assertEquals(true, pull.get("refusal_listed")); }
      }
    }
  }

  static Map<String, Object> gateway(ReplaySource source, Path run, long shift) throws Exception {
    return gateway(source, run, shift, 64L << 20);
  }
  static Map<String, Object> gateway(ReplaySource source, Path run, long shift, long maxFileBytes) throws Exception {
    var now = new AtomicLong(source.minUs + shift);
    var wall = new ReplayClock(source.calendar().orElseThrow().start(), source.minUs + shift);
    try (var remote = new ReplayPull(source, run.resolve("rio"));
         var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), now::get)) {
      gateway.start().get(10, TimeUnit.SECONDS);
      var replayer = new LogReplayer(source, gateway, now::set); replayer.announce();
      try (var capture = new ReplayCapture(RobotAddress.uri("127.0.0.1", gateway.port(), "replay"), source.path,
          run.resolve("store"), wall, remote.device, maxFileBytes)) {
        capture.ready(replayer.topicCount()); replayer.replay(shift, 0, ignored -> fail("Unexpected pacing"), capture::receivedThrough, capture::propertiesThrough); capture.stop();
        assertEquals(source.calendar().orElseThrow().start(), capture.writer.session().startedAt());
        return Map.of("fidelity", new ReplayAudit().verify(source, capture, shift), "pull", remote.verify(source, capture, wall, shift));
      }
    }
  }
}
