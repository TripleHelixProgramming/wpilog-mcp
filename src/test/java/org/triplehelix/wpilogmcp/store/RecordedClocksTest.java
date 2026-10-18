/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.store.StoreCatalog.StoredFile;
import org.triplehelix.wpilogmcp.store.StoreManifest.ClockRecord;
import org.triplehelix.wpilogmcp.store.StoreManifest.LogFile;
import org.triplehelix.wpilogmcp.store.StoreManifest.Matching;
import org.triplehelix.wpilogmcp.store.StoreManifest.Provenance;
import org.triplehelix.wpilogmcp.store.StoreManifest.Session;
import org.triplehelix.wpilogmcp.sync.ConfidenceLevel;
import org.triplehelix.wpilogmcp.sync.SyncMethod;
import org.triplehelix.wpilogmcp.sync.SyncResult;

/**
 * Offsets meet at their reference. The fixture defines each file's clock against the capture's
 * (robot time): the robot log's measured offset is +4 ms with 2 ppm drift about 30 s, the REV
 * log's +95.123456 s with 150 ppm drift about 20 s. The expected times below solve those
 * definitions by hand, so they do not reuse the server's composition.
 */
class RecordedClocksTest {
  private static final String START = "2026-10-17T10:00:00Z";
  private static final String ORIGIN = "pit-store";
  private static final CaptureRecording RECORDING = new CaptureRecording(ORIGIN, START);
  private static final CaptureRecording LATER = new CaptureRecording(ORIGIN, "2026-10-17T11:00:00Z");
  private static final String CAPTURE = "c".repeat(64), ROBOT = "a".repeat(64), REV = "b".repeat(64), OTHER = "d".repeat(64);
  private static final Path SESSION = Path.of("store", "robots", "serial", "sessions", "2026-10-17", "100000Z");
  private static final Path MANIFEST = SESSION.resolve("session.json");
  private static final SyncResult ROBOT_MEASURED = measured(4_000, 0.7, ConfidenceLevel.MEDIUM, 2_000, 30);
  private static final SyncResult REV_MEASURED = measured(95_123_456, 0.9, ConfidenceLevel.HIGH, 150_000, 20);

  /** The REV log's time on the capture's clock, as the fixture defines it. */
  private static double onCapture(double rev) { return rev + 95.123456 + (rev - 20) * 150e-6; }
  /** Robot time on the robot log's clock: the robot log's definition, solved for its own time. */
  private static double onRobotLog(double robot) { return (robot - 0.004 + 30 * 2e-6) / (1 + 2e-6); }

  private static SyncResult measured(long offset, double confidence, ConfidenceLevel level, double drift, double reference) {
    return new SyncResult(offset, confidence, level, List.of(), SyncMethod.CROSS_CORRELATION, "fixture measurement", drift, reference);
  }
  private static Matching against(String hash, CaptureRecording recording, SyncResult m) {
    return new Matching("by_correlation", hash, m.offsetMicros(), m.confidence(), m.driftRateNanosPerSec(),
        m.referenceTimeSec(), "data_alone", m, recording);
  }
  private static LogFile capture(String path, String sha, String startedAt) {
    return new LogFile(path, sha, 1, "wpilog", new Provenance("captured", "nt4://10.0.0.2", path, startedAt, false, "serial"),
        true, 0, 900, START, START, "log", false, null);
  }
  private static LogFile pulled(String path, String sha, String kind, Matching matching) {
    return new LogFile(path, sha, 1, kind, new Provenance("pulled", "/home/lvuser/logs/" + path, path, START, true, "serial"),
        true, 0, 600, START, START, "log", false, matching);
  }
  private static LogFile robotLog(Matching matching) { return pulled("robot/FRC_robot.wpilog", ROBOT, "wpilog", matching); }
  private static LogFile revLog(Matching matching) { return pulled("robot/REV_bus.revlog", REV, "revlog", matching); }
  private static ClockRecord record(String sha, CaptureRecording recording) {
    return new ClockRecord("clock/capture.wpilog.json", "e".repeat(64), 10, "capture.wpilog", sha, recording);
  }

  private record Files(List<StoredFile> all) {
    StoredFile get(String path) { return all.stream().filter(f -> f.file().path().equals(path)).findFirst().orElseThrow(); }
    StoredFile robot() { return get("robot/FRC_robot.wpilog"); }
    StoredFile rev() { return get("robot/REV_bus.revlog"); }
    StoredFile capture() { return get("capture.wpilog"); }
  }
  private static Files session(List<LogFile> files, List<ClockRecord> clocks) {
    var session = new Session("id", START, START, "log", null, null, null, null, files, null, null, null,
        List.of(), List.of(), null, null, List.of(), clocks);
    return new Files(files.stream().map(f -> new StoredFile(SESSION.resolve(f.path()), MANIFEST, null, session, f)).toList());
  }
  private static SyncResult rev(Files files, StoredFile anchor) {
    return RecordedClocks.revAlignment(files.rev(), anchor, files.all(), ORIGIN).orElseThrow();
  }
  private static void assertPlaced(SyncResult alignment, java.util.function.DoubleUnaryOperator expected) {
    for (double t : new double[] {0, 20, 37.5, 600}) {
      assertEquals(expected.applyAsDouble(t), alignment.toFpgaTime(t), 1e-6, "time " + t);
    }
  }

  @Test void aRevLogPairsWithTheRobotLogByCombiningTheirOffsetsAgainstTheCapture() {
    var files = session(List.of(capture("capture.wpilog", CAPTURE, START), robotLog(against(CAPTURE, null, ROBOT_MEASURED)),
        revLog(against(CAPTURE, null, REV_MEASURED))), List.of());
    var direct = rev(files, files.capture());
    assertSame(REV_MEASURED, direct, "Against its own reference a REV log keeps its recorded measurement");

    var combined = rev(files, files.robot());
    assertEquals(SyncMethod.COMBINED, combined.method());
    assertPlaced(combined, t -> onRobotLog(onCapture(t)));
    assertEquals(0.7, combined.confidence(), "no more confident than the robot log's measurement");
    assertEquals(ConfidenceLevel.MEDIUM, combined.confidenceLevel());
    assertEquals(55.0, combined.combination().maxErrorMs(), "5 ms for high plus 50 ms for medium");
    assertEquals("up to 55", combined.estimatedAccuracyMs());
    assertEquals("capture.wpilog", combined.combination().reference());
    var measurements = combined.combination().measurements();
    assertEquals(List.of("robot/REV_bus.revlog", "robot/FRC_robot.wpilog"), measurements.stream().map(m -> m.file()).toList());
    assertEquals(List.of(false, true), measurements.stream().map(m -> m.reversed()).toList());
    assertEquals(List.of(95_123_456L, 4_000L), measurements.stream().map(m -> m.offsetMicros()).toList());
    assertTrue(combined.explanation().contains("capture.wpilog") && combined.explanation().contains("No correlation"), combined.explanation());
    assertEquals(List.of(), combined.signalPairs(), "no signal was correlated between the two files");
  }

  @Test void aMirrorWithoutTheCapturePairsThemIdentically() {
    var pit = session(List.of(capture("capture.wpilog", CAPTURE, START), robotLog(against(CAPTURE, null, ROBOT_MEASURED)),
        revLog(against(CAPTURE, null, REV_MEASURED))), List.of());
    var mirror = session(List.of(robotLog(against(CAPTURE, null, ROBOT_MEASURED)), revLog(against(CAPTURE, null, REV_MEASURED))),
        List.of(record(CAPTURE, RECORDING)));
    var onPit = rev(pit, pit.robot()); var onMirror = rev(mirror, mirror.robot());
    assertEquals(onPit.offsetMicros(), onMirror.offsetMicros());
    assertEquals(onPit.driftRateNanosPerSec(), onMirror.driftRateNanosPerSec());
    assertEquals(onPit.referenceTimeSec(), onMirror.referenceTimeSec());
    assertPlaced(onMirror, t -> onRobotLog(onCapture(t)));
    assertTrue(onMirror.combination().reference().startsWith("capture.wpilog (not held here"), onMirror.combination().reference());
  }

  @Test void anOffsetMeasuredWhileTheCaptureRecordedMeetsTheRobotLogThroughTheRecording() {
    var rev = revLog(against(null, RECORDING, REV_MEASURED));
    var pit = session(List.of(capture("capture.wpilog", CAPTURE, START), robotLog(against(CAPTURE, null, ROBOT_MEASURED)), rev), List.of());
    assertSame(REV_MEASURED, rev(pit, pit.capture()), "a capture file of the recording is on its clock");
    assertPlaced(rev(pit, pit.robot()), t -> onRobotLog(onCapture(t)));
    var mirror = session(List.of(robotLog(against(CAPTURE, null, ROBOT_MEASURED)), rev), List.of(record(CAPTURE, RECORDING)));
    var combined = rev(mirror, mirror.robot());
    assertPlaced(combined, t -> onRobotLog(onCapture(t)));
    assertEquals("the capture recording started " + START + " by store " + ORIGIN, combined.combination().reference());
    // Without the record a mirror cannot tell which recording the capture's hash belongs to.
    var unknown = session(List.of(robotLog(against(CAPTURE, null, ROBOT_MEASURED)), rev), List.of());
    assertTrue(RecordedClocks.revAlignment(unknown.rev(), unknown.robot(), unknown.all(), ORIGIN).isEmpty());
  }

  @Test void anOlderOffsetThatNamesNoReferenceIsTheOnlyRecordingsOrIsRefused() {
    var legacy = revLog(against(null, null, REV_MEASURED));
    var single = session(List.of(capture("capture.wpilog", CAPTURE, START), robotLog(against(CAPTURE, null, ROBOT_MEASURED)), legacy), List.of());
    assertPlaced(rev(single, single.robot()), t -> onRobotLog(onCapture(t)));
    var mirrored = session(List.of(robotLog(against(CAPTURE, null, ROBOT_MEASURED)), legacy), List.of(record(CAPTURE, RECORDING)));
    assertPlaced(rev(mirrored, mirrored.robot()), t -> onRobotLog(onCapture(t)));

    // Peer sync put a second boot's capture in this session: the old offset could be on either clock.
    var two = session(List.of(capture("capture.wpilog", CAPTURE, START), capture("later.wpilog", OTHER, LATER.startedAt()),
        robotLog(against(CAPTURE, null, ROBOT_MEASURED)), legacy), List.of());
    for (var anchor : List.of(two.robot(), two.capture(), two.get("later.wpilog"))) {
      var refused = rev(two, anchor);
      assertEquals(SyncMethod.FAILED, refused.method());
      assertTrue(refused.explanation().contains("holds 2 recordings"), refused.explanation());
    }
    var twoRecorded = session(List.of(robotLog(against(CAPTURE, null, ROBOT_MEASURED)), legacy),
        List.of(record(CAPTURE, RECORDING), new ClockRecord("clock/later.wpilog.json", "f".repeat(64), 10, "later.wpilog", OTHER, LATER)));
    assertTrue(rev(twoRecorded, twoRecorded.robot()).explanation().contains("holds 2 recordings"));
    var unrecorded = session(List.of(robotLog(against(CAPTURE, null, ROBOT_MEASURED)), legacy),
        List.of(record(CAPTURE, null)));
    assertTrue(RecordedClocks.revAlignment(unrecorded.rev(), unrecorded.robot(), unrecorded.all(), ORIGIN).isEmpty(),
        "the robot log's capture is of no known recording, so nothing relates them");
  }

  @Test void filesMeasuredAgainstDifferentClocksDoNotPair() {
    var files = session(List.of(capture("capture.wpilog", CAPTURE, START), capture("later.wpilog", OTHER, LATER.startedAt()),
        robotLog(against(CAPTURE, null, ROBOT_MEASURED)), revLog(against(null, LATER, REV_MEASURED))), List.of());
    assertTrue(RecordedClocks.revAlignment(files.rev(), files.robot(), files.all(), ORIGIN).isEmpty());
    assertTrue(RecordedClocks.revAlignment(files.rev(), files.capture(), files.all(), ORIGIN).isEmpty());
    assertSame(REV_MEASURED, rev(files, files.get("later.wpilog")));
    var unmatched = session(List.of(capture("capture.wpilog", CAPTURE, START), robotLog(null), revLog(against(CAPTURE, null, REV_MEASURED))), List.of());
    assertTrue(RecordedClocks.revAlignment(unmatched.rev(), unmatched.robot(), unmatched.all(), ORIGIN).isEmpty());
  }

  @Test void aMeasurementBothChainsShareCancelsAndASessionReadKeepsForwardSteps() throws Exception {
    var hand = measured(-1_250, 0.95, ConfidenceLevel.HIGH, 0, 0);
    var imported = pulled("imported.wpilog", OTHER, "wpilog", against(ROBOT, null, hand));
    var files = session(List.of(capture("capture.wpilog", CAPTURE, START), robotLog(against(CAPTURE, null, ROBOT_MEASURED)), imported), List.of());
    var toRobot = RecordedClocks.between(files.get("imported.wpilog"), files.robot(), files.all(), ORIGIN).orElseThrow();
    assertEquals(List.of(hand), toRobot.steps(), "measured against the robot log itself, so no combination");
    assertSame(hand, toRobot.single());
    var toCapture = RecordedClocks.between(files.get("imported.wpilog"), files.capture(), files.all(), ORIGIN).orElseThrow();
    assertEquals(List.of(hand, ROBOT_MEASURED), toCapture.steps(), "forward steps stay as recorded");
    assertEquals(SyncMethod.COMBINED, toCapture.single().method());
  }

  @Test void theCaptureOnTheRobotLogsClockIsItsMeasurementReversed() throws Exception {
    var files = session(List.of(capture("capture.wpilog", CAPTURE, START), robotLog(against(CAPTURE, null, ROBOT_MEASURED))), List.of());
    var alignment = RecordedClocks.between(files.capture(), files.robot(), files.all(), ORIGIN).orElseThrow();
    var steps = alignment.steps();
    assertEquals(1, steps.size());
    assertEquals(SyncMethod.COMBINED, steps.get(0).method());
    assertEquals(List.of(true), steps.get(0).combination().measurements().stream().map(m -> m.reversed()).toList());
    for (double robot : new double[] {0, 30, 125.25, 900}) assertEquals(onRobotLog(robot), steps.get(0).toFpgaTime(robot), 1e-6);
    assertEquals(50.0, steps.get(0).combination().maxErrorMs());
  }

  @Test void invalidEvidenceIsExplainedNotUsed() {
    var corrupt = measured(4_000, 2, ConfidenceLevel.HIGH, 0, 0);
    var files = session(List.of(capture("capture.wpilog", CAPTURE, START), robotLog(against(CAPTURE, null, corrupt)),
        revLog(against(CAPTURE, null, REV_MEASURED))), List.of());
    var refused = rev(files, files.robot());
    assertEquals(SyncMethod.FAILED, refused.method());
    assertTrue(refused.explanation().contains("Invalid recorded alignment") && refused.explanation().contains("session.json"), refused.explanation());

    var cycle = session(List.of(robotLog(against(OTHER, null, ROBOT_MEASURED)),
        pulled("other.wpilog", OTHER, "wpilog", against(ROBOT, null, ROBOT_MEASURED)), revLog(against(ROBOT, null, REV_MEASURED))), List.of());
    assertTrue(rev(cycle, cycle.get("other.wpilog")).explanation().contains("Cycle"));

    var contradicted = session(List.of(capture("capture.wpilog", CAPTURE, START), robotLog(against(CAPTURE, null, ROBOT_MEASURED)),
        revLog(against(CAPTURE, LATER, REV_MEASURED))), List.of());
    assertTrue(rev(contradicted, contradicted.robot()).explanation().contains("does not belong to"));

    var huge = session(List.of(capture("capture.wpilog", CAPTURE, START),
        robotLog(against(CAPTURE, null, measured(Long.MIN_VALUE + 1, 0.9, ConfidenceLevel.HIGH, 0, 0))),
        revLog(against(CAPTURE, null, measured(Long.MAX_VALUE - 1, 0.9, ConfidenceLevel.HIGH, 0, 0)))), List.of());
    assertTrue(rev(huge, huge.robot()).explanation().contains("overflow"));
  }

  @Test void aStoredSynchronizationCannotClaimToBeACombination() {
    var claimed = new SyncResult(4_000, 0.9, ConfidenceLevel.HIGH, List.of(), SyncMethod.COMBINED, "crafted", 0, 0);
    var files = session(List.of(capture("capture.wpilog", CAPTURE, START), robotLog(against(CAPTURE, null, claimed))), List.of());
    assertFalse(StoreCatalog.recordedAlignment(files.robot()).isSuccessful());
  }

  @Test void eachRefusalAndPairingHoldsAgainstEveryAnchorOfTheSession() {
    // A REV log is never paired with a file of another session, whatever its offsets name.
    var files = session(List.of(capture("capture.wpilog", CAPTURE, START), robotLog(against(CAPTURE, null, ROBOT_MEASURED)),
        revLog(against(CAPTURE, null, REV_MEASURED))), List.of());
    var elsewhere = new ArrayList<StoredFile>(files.all());
    var other = files.robot();
    elsewhere.add(new StoredFile(Path.of("store", "other", "robot.wpilog"), Path.of("store", "other", "session.json"),
        null, other.session(), other.file()));
    assertTrue(RecordedClocks.revAlignment(files.rev(), elsewhere.get(elsewhere.size() - 1), elsewhere, ORIGIN).isEmpty());
    assertTrue(RecordedClocks.revAlignment(files.robot(), files.capture(), files.all(), ORIGIN).isEmpty(), "only a REV log is a REV companion");
  }
}
