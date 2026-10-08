/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;

/** Uses native ntcore in a separate process; assertions still come from the independent reader. */
@Tag("shop-harness")
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "conformance.native", matches = "sample|full")
class NtcoreReplayTest {
  @org.junit.jupiter.api.Test void publisherStopsBeforeTheOfflineAuditAndTransfersBegin(
      @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
    var path = directory.resolve("audit-order.wpilog");
    try (var out = new org.triplehelix.wpilogmcp.fixtures.WpilogWriter(path, "")) {
      int id = out.start("NT:/counter", "int64", "", 1_000_000);
      for (int i = 0; i < 3; i++) out.append(id, 1_000_000 + i * 20_000L,
          org.triplehelix.wpilogmcp.fixtures.WpilogWriter.encodeInt64(i));
    }
    try (var source = new ReplaySource(path)) {
      replay(source, 0, false, robot -> org.junit.jupiter.api.Assertions.assertFalse(robot.isAlive(),
          "Offline comparisons and transfers must not consume the native stop-handshake deadline"));
    }
  }

  @org.junit.jupiter.api.Test void nativeReplayRefusesMalformedUtf8BeforeAnnouncing(
      @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
    var path = directory.resolve("invalid-string.wpilog");
    try (var out = new org.triplehelix.wpilogmcp.fixtures.WpilogWriter(path, "")) {
      int id = out.start("NT:/text", "string", "", 1_000_000);
      out.append(id, 1_000_000, new byte[] {(byte) 0xc3, 0x28}); out.finish(id, 1_000_000);
    }
    boolean refused = false;
    try (var source = new ReplaySource(path)) {
      try (var process = new NativeReplayProcess(source, 0, directory.resolve("native"), NativeReplayProcess.freePort())) {
        // A ready server would silently replace the malformed text when it decodes the record.
      } catch (Exception | AssertionError expected) { refused = true; }
    }
    assertTrue(refused, "NT4 strings cannot preserve invalid UTF-8 bytes");
    assertTrue(Files.exists(directory.resolve("native").resolve("control").resolve("failure.json")));
  }
  @org.junit.jupiter.api.Test void driverStationMatchNumbersRespectTheirLoggedNumericType(
      @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
    var path = directory.resolve("match-types.wpilog");
    try (var writer = new org.triplehelix.wpilogmcp.fixtures.WpilogWriter(path, "")) {
      int flags = writer.start("NT:/FMSInfo/FMSControlData", "double", "", 1_000_000);
      int number = writer.start("NT:/FMSInfo/MatchNumber", "double", "", 1_000_000);
      int type = writer.start("NT:/FMSInfo/MatchType", "double", "", 1_000_000);
      int event = writer.start("NT:/FMSInfo/EventName", "string", "", 1_000_000);
      writer.append(flags, 1_000_000, org.triplehelix.wpilogmcp.fixtures.WpilogWriter.encodeDouble(32));
      writer.append(number, 1_020_000, org.triplehelix.wpilogmcp.fixtures.WpilogWriter.encodeDouble(7));
      writer.append(type, 1_040_000, org.triplehelix.wpilogmcp.fixtures.WpilogWriter.encodeDouble(2));
      writer.append(event, 1_060_000, org.triplehelix.wpilogmcp.fixtures.WpilogWriter.encodeString("Synthetic"));
    }
    try (var source = new ReplaySource(path)) { replay(source, 0); }
  }
  @org.junit.jupiter.api.Test void collidingNamesRemainDistinctThroughNativePublishers(
      @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
    var path = directory.resolve("collisions.wpilog");
    try (var writer = new org.triplehelix.wpilogmcp.fixtures.WpilogWriter(path, "")) {
      int first = writer.start("NT:/x", "double", "first", 1_000_000);
      int second = writer.start("/x", "double", "second", 1_000_000);
      for (int i = 0; i < 3000; i++) {
        writer.append(first, 1_000_000 + 20_000L * i, org.triplehelix.wpilogmcp.fixtures.WpilogWriter.encodeDouble(i));
        writer.append(second, 1_000_000 + 20_000L * i, org.triplehelix.wpilogmcp.fixtures.WpilogWriter.encodeDouble(-i));
      }
    }
    try (var source = new ReplaySource(path)) {
      var result = replay(source, 0); assertTrue(((Map<?, ?>) result.get("mismatches")).isEmpty());
    }
  }
  @TestFactory Stream<DynamicTest> fixturesThroughNativePublishers() throws Exception {
    return FixtureLogs.generateAll(FixtureLogs.defaultDirectory()).stream().map(f -> DynamicTest.dynamicTest(f.id(), () -> {
      try (var source = new ReplaySource(f.path())) {
        var report = replay(source, 0);
        Files.writeString(Files.createDirectories(Path.of("build/reports/replay"))
            .resolve("ntcore-fixture-" + f.id() + ".json"), new com.google.gson.Gson().toJson(report));
        assertTrue(((Map<?, ?>) report.get("mismatches")).isEmpty(), report.toString());
      }
    }));
  }

  static Map<String, Object> replay(ReplaySource source, long shiftUs) throws Exception {
    return replay(source, shiftUs, false);
  }

  static Map<String, Object> replay(ReplaySource source, long shiftUs, boolean pull) throws Exception {
    return replay(source, shiftUs, pull, robot -> {});
  }

  private static Map<String, Object> replay(ReplaySource source, long shiftUs, boolean pull,
      java.util.function.Consumer<NativeReplayProcess> beforeAudit) throws Exception {
    var run = Files.createTempDirectory(Files.createDirectories(Path.of("build/replay-native")), "run-").toAbsolutePath();
    int port = NativeReplayProcess.freePort();
    try (var robot = new NativeReplayProcess(source, shiftUs, run, port);
         var remote = pull && source.calendar().isPresent() ? new ReplayPull(source, run.resolve("rio")) : null) {
      var wall = new ReplayClock(source.calendar().map(ReplaySource.Calendar::start).orElseGet(java.time.Instant::now), source.minUs + shiftUs);
      try (var capture = new ReplayCapture(RobotAddress.uri("127.0.0.1", port, "replay"), source.path, run.resolve("capture"), wall,
          remote == null ? null : remote.device)) {
        capture.ready(robot.topics); robot.consume(capture); capture.stop();
        // All receipts are acknowledged. Offline audits and SFTP retries can outlast the
        // publisher's bounded stop handshake, so finish it before that independent work.
        robot.finish();
        beforeAudit.accept(robot);
        var result = new ReplayAudit().verify(source, capture, shiftUs);
        result.put("calendar_basis", source.calendar().map(ReplaySource.Calendar::basis).orElse("unavailable"));
        if (remote != null) result.put("pull", remote.verify(source, capture, wall, shiftUs));
        else if (pull) result.put("pull_skipped", "No recorded calendar clock or dated DataLogManager filename");
        return result;
      }
    }
  }
}
