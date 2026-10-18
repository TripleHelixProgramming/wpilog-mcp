/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.fixtures;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.triplehelix.wpilogmcp.store.CaptureRecording;
import org.triplehelix.wpilogmcp.store.ClockRecords;
import org.triplehelix.wpilogmcp.store.StoreJson;
import org.triplehelix.wpilogmcp.store.StoreManifest;
import org.triplehelix.wpilogmcp.store.StoreManifest.ClockRecord;
import org.triplehelix.wpilogmcp.store.StoreManifest.LogFile;
import org.triplehelix.wpilogmcp.store.StoreManifest.Matching;
import org.triplehelix.wpilogmcp.sync.ConfidenceLevel;
import org.triplehelix.wpilogmcp.sync.SyncMethod;
import org.triplehelix.wpilogmcp.sync.SyncResult;

/**
 * A laptop's mirror holding the robot log and a REV log the pit pulled, not the pit's capture they
 * were both measured against. The fixture sets the clocks: the robot log's recorded offset against
 * the capture is +4 ms with 2 ppm drift about 30 s, the REV log's +95.123456 s with 150 ppm drift
 * about 20 s, another robot log's +7.5 ms. The expected times solve those definitions by hand, so
 * they do not reuse the server's composition.
 */
public final class MirroredOffsets {
  private MirroredOffsets() {}

  public static final String WHEN = "2026-10-17T10:00:00Z";
  public static final String PIT = "pit-store";
  public static final CaptureRecording RECORDING = new CaptureRecording(PIT, WHEN);
  public static final String CAPTURE = "c".repeat(64);
  public static final long[] REV_TIMES_US = {0, 20_000_000, 37_500_000, 50_000_000};
  public static final String ROBOT = "robot/FRC_robot.wpilog", REV = "robot/REV_bus.revlog", OTHER = "robot/other.wpilog";

  public static double onCapture(double rev) { return rev + 95.123456 + (rev - 20) * 150e-6; }
  public static double onRobotLog(double robot) { return (robot - 0.004 + 30 * 2e-6) / (1 + 2e-6); }
  /** The other robot log's definition: robot time is its own time plus 7.5 ms. */
  public static double otherOnCapture(double other) { return other + 0.0075; }

  public static SyncResult measured(long offset, double confidence, ConfidenceLevel level, double drift, double reference) {
    return new SyncResult(offset, confidence, level, List.of(), SyncMethod.CROSS_CORRELATION, "fixture measurement", drift, reference);
  }
  public static Matching against(String hash, CaptureRecording recording, SyncResult m) {
    return new Matching("by_correlation", hash, m.offsetMicros(), m.confidence(), m.driftRateNanosPerSec(),
        m.referenceTimeSec(), "data_alone", m, recording);
  }
  public static final Matching ROBOT_MATCH = against(CAPTURE, null, measured(4_000, 0.7, ConfidenceLevel.MEDIUM, 2_000, 30));
  public static final Matching REV_WHILE_RECORDING = against(null, RECORDING, measured(95_123_456, 0.9, ConfidenceLevel.HIGH, 150_000, 20));

  public record Mirror(Path root, Path session, Path robot, Path rev, Path other) {}

  /** A mirror store as a laptop holds it: the pulled files, the capture's clock record, no capture. */
  public static Mirror write(Path directory, String storeId, Matching revMatch, Matching otherMatch, List<CaptureRecording> recordings) throws Exception {
    var root = Files.createDirectories(directory).toRealPath();
    var robotDirectory = root.resolve("robots/serial");
    var session = Files.createDirectories(robotDirectory.resolve("sessions/2026-10-17/100000Z"));
    var robot = session.resolve(ROBOT); Files.createDirectories(robot.getParent());
    try (var w = new WpilogWriter(robot, "")) {
      int id = w.start("/Drive/Speed", "double", "", 0);
      for (int i = 90; i <= 150; i++) w.append(id, i * 1_000_000L, WpilogWriter.encodeDouble(i));
    }
    var other = session.resolve(OTHER);
    try (var w = new WpilogWriter(other, "")) {
      int id = w.start("/Arm/Angle", "double", "", 0);
      for (int i = 100; i <= 110; i++) w.append(id, i * 1_000_000L, WpilogWriter.encodeDouble(i));
    }
    var rev = session.resolve(REV);
    try (var w = new WpilogWriter(rev, "")) {
      int id = w.start("CAN/3/Periodic Status 0", "raw", "", 0);
      for (long us : REV_TIMES_US) w.append(id, us, FixtureLogs.sparkStatus0(0.25, 12, 2, 30, false));
      w.finish(id, REV_TIMES_US[REV_TIMES_US.length - 1] + 1);
    }
    var files = new ArrayList<LogFile>(List.of(file(robot, ROBOT, "wpilog", 90, 150, ROBOT_MATCH), file(rev, REV, "revlog", 0, 50, revMatch)));
    if (otherMatch != null) files.add(file(other, OTHER, "wpilog", 100, 110, otherMatch));
    var clocks = new ArrayList<ClockRecord>();
    for (int i = 0; i < recordings.size(); i++) {
      String capture = i == 0 ? "capture.wpilog" : "capture-" + i + ".wpilog";
      String sha = i == 0 ? CAPTURE : String.valueOf((char) ('d' + i)).repeat(64);
      var record = session.resolve("clock/" + capture + ".json"); Files.createDirectories(record.getParent());
      Files.writeString(record, StoreJson.JSON.toJson(new ClockRecords.Facts(ClockRecords.FORMAT_VERSION, capture, sha, 1000,
          recordings.get(i).collectingStoreId(), recordings.get(i).startedAt(), 0, 900, List.of())));
      clocks.add(new ClockRecord("clock/" + capture + ".json", hash(record), Files.size(record), capture, sha, recordings.get(i)));
    }
    var manifest = new StoreManifest.Session("session-id", WHEN, WHEN, "log", null, null, null, null, files, null, null, null,
        List.of(), List.of(), null, null, List.of(), clocks);
    var header = new StoreManifest.Header(StoreManifest.FORMAT_VERSION, WHEN, storeId, List.of())
        .withOrigin(new StoreManifest.MirrorOrigin(PIT, "http://pit.invalid", WHEN, List.of(), Map.of()));
    Files.writeString(root.resolve("store.json"), StoreJson.JSON.toJson(header));
    Files.writeString(robotDirectory.resolve("robot.json"), StoreJson.JSON.toJson(new StoreManifest.Robot("serial", "serial", "Fixture", "", "logged")));
    Files.writeString(session.resolve("session.json"), StoreJson.JSON.toJson(manifest));
    return new Mirror(root, session, robot, rev, other);
  }
  private static LogFile file(Path path, String relative, String kind, double min, double max, Matching matching) throws Exception {
    var provenance = new StoreManifest.Provenance("pulled", "/home/lvuser/logs/" + path.getFileName(), path.getFileName().toString(), WHEN, true, "serial");
    return new LogFile(relative, hash(path), Files.size(path), kind, provenance, true, min, max, WHEN, WHEN, "log", false, matching);
  }
  private static String hash(Path path) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }
}
