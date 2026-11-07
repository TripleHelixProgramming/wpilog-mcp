/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.fixtures;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.triplehelix.wpilogmcp.capture.CaptureStats;
import org.triplehelix.wpilogmcp.capture.context.ProviderStatus;
import org.triplehelix.wpilogmcp.capture.pull.FakeRobot;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;
import org.triplehelix.wpilogmcp.store.StoreJson;

/** Synthetic paired clocks: kernel 100..120 maps to FPGA 10..20; wall clock advances one for one. */
public final class SystemSessionFixture {
  private SystemSessionFixture() {}
  public static final Clock WALL = Clock.fixed(Instant.parse("2026-03-07T14:22:33Z"), ZoneOffset.UTC);
  public static final String SERIAL = "SYSTEM-FIXTURE";
  public static Path create(Path root, String id, boolean open, long pid) throws Exception {
    Files.createDirectories(root);
    if (!Files.exists(root.resolve("store.json"))) write(root.resolve("store.json"), new Header(1, WALL.instant().toString(), "system-fixture-store", List.of()));
    var robot = root.resolve("robots").resolve(SERIAL); Files.createDirectories(robot);
    write(robot.resolve("robot.json"), new Robot(SERIAL, SERIAL, null, "synthetic", "device"));
    var directory = robot.resolve("sessions/2026-03-07").resolve(id); Files.createDirectories(directory);
    var capture = directory.resolve("capture.wpilog");
    try (var writer = new WpilogWriter(capture, "synthetic paired clocks")) {
      int uptime = writer.start("/Daemon/roboRIO/uptime_sec", "double", "{\"source\":\"ssh\"}", 10_000_000);
      int system = writer.start("systemTime", "int64", "", 10_000_000);
      int process = writer.start("/Daemon/roboRIO/program/pid", "int64", "{\"source\":\"ssh\"}", 10_000_000);
      writer.append(uptime, 10_000_000, WpilogWriter.encodeDouble(100)); writer.append(uptime, 20_000_000, WpilogWriter.encodeDouble(120));
      writer.append(system, 10_000_000, WpilogWriter.encodeInt64(WALL.instant().toEpochMilli() * 1000));
      writer.append(system, 20_000_000, WpilogWriter.encodeInt64(WALL.instant().plusSeconds(10).toEpochMilli() * 1000));
      writer.append(process, 10_000_000, WpilogWriter.encodeInt64(pid));
    }
    var provenance = new Provenance("captured", null, null, null, false);
    String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(capture)));
    var file = new LogFile("capture.wpilog", hash, Files.size(capture), "wpilog", provenance, true, 10, 20,
        WALL.instant().toString(), WALL.instant().plusSeconds(10).toString(), "pit_clock", false, null);
    var stats = new CaptureStats(0, 5, Files.size(capture), Map.of(), List.of(), Map.of(), List.of(new ProviderStatus("roboRIO", "running", null, 2, 1., null, 0, 0, 0, 5, 40, 0, List.of(pid))), null);
    write(directory.resolve("session.json"), new Session(id, WALL.instant().toString(), WALL.instant().plusSeconds(10).toString(), "pit_clock", null, null, null, null,
        open ? List.of() : List.of(file), open ? new OpenCapture("capture.wpilog", provenance, Files.size(capture), 10, 20) : null,
        null, FakeRobot.device(SERIAL, "SHA256:fixture"), List.of(), List.of(), stats));
    return capture;
  }
  public static void write(Path path, Object value) throws Exception { Files.writeString(path, StoreJson.JSON.toJson(value)); }
}
