/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import static org.triplehelix.wpilogmcp.fixtures.WpilogWriter.*;
import static org.triplehelix.wpilogmcp.store.StoreManifest.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/** Calendar collisions between generated boots must be settled by recorded signals. */
class ImportMatchingTest {
  @TempDir Path temp;
  Path root;
  LogStore store;
  StoreRegistry registry;
  SecurityValidator security;
  Set<Path> allowed;
  final LogManager manager = LogManager.getInstance();
  static final Instant WALL = Instant.parse("2026-03-07T14:22:33Z");
  static final String SERIAL = "SYNTHETIC-IMPORT";

  @BeforeEach void setup() throws Exception {
    temp = temp.toRealPath(); root = temp.resolve("store");
    allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    security = new SecurityValidator(); security.addAllowedDirectory(temp);
    registry = new StoreRegistry(security); store = registry.store(root);
  }
  @AfterEach void close() throws Exception {
    registry.close(); manager.release(temp);
    manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory);
  }

  Path wpilog(String name, String serial, boolean capture, long shiftUs, double bootShift) throws Exception {
    var path = temp.resolve(name);
    try (var w = new WpilogWriter(path, "generated import matching " + name)) {
      int value = w.start((capture ? "NT:" : "") + "/Drive/FrontLeft/AppliedOutput", "double", "", 0);
      int clock = name.startsWith("FRC_1970") ? -1 : w.start("systemTime", "int64", "", 0);
      if (serial != null) w.append(w.start("/SystemStats/SerialNumber", "string", "", 0),
          10_000_000 + shiftUs, encodeString(serial));
      for (int n = 0; n <= 3000; n++) {
        double t = 10 + n * 0.02;
        long time = 10_000_000 + n * 20_000L + shiftUs;
        w.append(value, time, encodeDouble(FixtureLogs.revlogPairOutput(t + bootShift)));
        // A single calendar anchor nominates the boot; only the generated output proves it.
        if (n == 0 && clock != -1) w.append(clock, time, encodeInt64(WALL.toEpochMilli() * 1000));
      }
    }
    return path;
  }

  Path seed(String serial, String id, Path input, Instant calendar, String anchor) throws Exception {
    return store.capture(io -> {
      var robot = root.resolve("robots").resolve(serial);
      io.write(robot.resolve("robot.json"), new Robot(serial, serial, null, "generated", "logged"));
      var dir = robot.resolve("sessions/2026-03-07").resolve(id);
      Files.createDirectories(dir);
      var path = dir.resolve("capture.wpilog"); Files.copy(input, path);
      var provenance = new Provenance(anchor.equals("matched") ? "pulled" : "captured", null, input.getFileName().toString(), calendar.toString(), false);
      var matching = anchor.equals("matched") ? new Matching("by_correlation", "f".repeat(64), 0, 1, 0, 10, "serial_and_data") : null;
      var file = new LogFile("capture.wpilog", StoreFiles.hash(path), Files.size(path), "wpilog", provenance,
          true, 10, 70, calendar.toString(), calendar.plusSeconds(60).toString(), "pit_clock", false, matching);
      var open = anchor.equals("open") ? new OpenCapture("capture.wpilog", provenance, Files.size(path), 10, 70) : null;
      io.write(dir.resolve("session.json"), new Session(id, calendar.toString(), calendar.plusSeconds(60).toString(),
          "pit_clock", null, null, null, null, open == null ? List.of(file) : List.of(), open, null));
      return path;
    });
  }

  StoreCatalog.StoredFile imported(Path source) throws Exception {
    var result = store.importPaths(new LogStore.Request(List.of(source), false, SERIAL), p -> {})
        .get(30, TimeUnit.SECONDS).files().get(0);
    assertTrue(List.of("imported", "unassigned", "present").contains(result.status()), result.toString());
    return StoreCatalog.read(root, security).files().stream().filter(f -> f.path().equals(result.path())).findFirst().orElseThrow();
  }

  @ParameterizedTest @ValueSource(strings = {"captured", "open", "matched"})
  void dataPicksTheRightBootEvenWhenCalendarOverlapPicksTheOther(String anchor) throws Exception {
    var wrong = seed(SERIAL, "first", wpilog("first.wpilog", SERIAL, true, 0, 8), WALL, "captured");
    var right = seed(SERIAL, "second", wpilog("second.wpilog", SERIAL, true, 0, 0), WALL.plusSeconds(3600), anchor);
    // The unrelated robot and the distant session have the same samples; neither may nominate.
    seed("OTHER", "other", temp.resolve("second.wpilog"), WALL, "captured");
    seed(SERIAL, "distant", temp.resolve("second.wpilog"), WALL.plusSeconds(86400), "captured");
    byte[] wrongManifest = Files.readAllBytes(wrong.getParent().resolve("session.json"));
    var original = wpilog("import.wpilog", SERIAL, false, 120_000, 0);
    var placed = imported(original);
    assertEquals("second", placed.session().id(), "Overlap alone selects the first boot; only the second has near-zero data proof");
    assertEquals(WALL.plusSeconds(3600).toString(), placed.session().startedAt(), "the anchor owns the session calendar");
    var evidence = placed.file().matching(); assertNotNull(evidence);
    assertEquals("by_correlation", evidence.method());
    assertEquals(-120_000, evidence.offsetMicros(), 2_000);
    assertEquals(anchor.equals("open") ? null : StoreFiles.hash(right), evidence.wpilogSha256());
    assertEquals("serial_and_data", evidence.identityBasis());
    assertNotNull(evidence.synchronization()); assertTrue(evidence.synchronization().strongPairCount() > 0);
    assertEquals(StoreFiles.hash(original), placed.file().sha256()); assertTrue(placed.file().verified());
    assertArrayEquals(Files.readAllBytes(original), Files.readAllBytes(placed.path()));
    assertArrayEquals(wrongManifest, Files.readAllBytes(wrong.getParent().resolve("session.json")));
    byte[] manifest = Files.readAllBytes(placed.manifestPath());
    assertEquals(placed.path(), imported(original).path());
    assertArrayEquals(manifest, Files.readAllBytes(placed.manifestPath()), "a duplicate never reclassifies or moves an existing placement");
  }

  @Test void withoutAnAnchorOverlapRemainsExplicitAndOldReceiptsStayValid() throws Exception {
    var first = imported(wpilog("first.wpilog", SERIAL, false, 0, 0));
    var original = first.file();
    var second = imported(wpilog("second.wpilog", SERIAL, false, 0, 8));
    assertEquals(first.session().id(), second.session().id());
    assertNull(second.file().matching(), "calendar overlap has no measured offset or correlation evidence");
    var json = StoreJson.JSON.toJsonTree(second.file()).getAsJsonObject();
    assertTrue(json.has("placement_method"), "new calendar-only placement must say how it was placed");
    assertEquals("by_time_overlap", json.get("placement_method").getAsString());
    assertTrue(second.file().matchingReason().contains("no capture or data-matched anchor"));
    assertEquals(original, second.session().files().get(0), "a legacy receipt is not retroactively classified");
    assertEquals(first.path(), StoreCatalog.read(root, security).files().stream()
        .filter(f -> f.file().sha256().equals(original.sha256())).findFirst().orElseThrow().path());
  }

  @Test void anUnsetCalendarStillNominatesTheKnownRobotsAnchor() throws Exception {
    seed(SERIAL, "boot", wpilog("anchor.wpilog", SERIAL, true, 0, 0), WALL, "captured");
    var placed = imported(wpilog("FRC_19700101_000000.wpilog", SERIAL, false, 0, 0));
    assertEquals("filename", placed.file().startBasis());
    assertEquals("boot", placed.session().id(), "an unset calendar cannot exclude data proof");
    assertEquals(WALL.toString(), placed.session().startedAt(), "an unset source clock must not widen the proven boot to 1970");
    assertEquals("by_correlation", placed.file().matching().method());
    assertEquals(0, placed.file().matching().offsetMicros(), 2_000);
  }

  @Test void aKnownSerialNominatesItsAnchorUnderAnOlderRobotDirectory() throws Exception {
    var anchor = seed("practice", "boot", wpilog("anchor.wpilog", SERIAL, true, 0, 0), WALL, "captured");
    store.capture(io -> {
      // Promotion can retain both directories when the serial directory already exists.
      io.write(root.resolve("robots/practice/robot.json"), new Robot("practice", SERIAL, "practice", null, "logged"));
      io.write(root.resolve("robots").resolve(SERIAL).resolve("robot.json"), new Robot(SERIAL, SERIAL, null, null, "logged"));
      return null;
    });
    var placed = imported(wpilog("import.wpilog", SERIAL, false, 0, 0));
    assertEquals("boot", placed.session().id(), "known serial, not directory spelling, nominates the boot");
    assertEquals(anchor.getParent().resolve("session.json"), placed.manifestPath());
    assertEquals("by_correlation", placed.file().matching().method());
    assertTrue(Files.exists(anchor), "the existing anchor never moves");
  }

  @ParameterizedTest @ValueSource(longs = {-240_000, 240_000, -260_000, 260_000, 15_300_000})
  void revCompanionsNeedTheSameBootClock(long offsetUs) throws Exception {
    var wpi = imported(wpilog("robot.wpilog", SERIAL, false, 0, 0));
    var rev = temp.resolve("synthetic.revlog");
    try (var w = new WpilogWriter(rev, "generated reset clock")) {
      int output = w.start("CAN/3/Periodic Status 0", "raw", "", 0);
      for (int n = 0; n <= 6000; n++) {
        double t = 10 + n * 0.01;
        w.append(output, 10_000_000 + n * 10_000L,
            FixtureLogs.sparkStatus0(FixtureLogs.revlogPairOutput(t + offsetUs / 1e6), 12, 20, 30, false));
      }
    }
    var placed = imported(rev);
    if (Math.abs(offsetUs) <= 250_000) {
      assertEquals(wpi.session().id(), placed.session().id());
      assertEquals(offsetUs, placed.file().matching().offsetMicros(), 20_000);
    } else {
      assertNull(placed.session(), "strong correlation from another boot must not place a REV companion");
      assertNull(placed.file().matching());
    }
    assertArrayEquals(Files.readAllBytes(rev), Files.readAllBytes(placed.path()));
  }

  @Test void failedOrAmbiguousAnchoredProofNeverFallsBackToCalendarOverlap() throws Exception {
    seed(SERIAL, "first", wpilog("first.wpilog", SERIAL, true, 0, 0), WALL, "captured");
    var far = imported(wpilog("far.wpilog", SERIAL, false, 2_000_000, 0));
    assertNotEquals("first", far.session().id()); assertNull(far.file().matching());
    assertTrue(far.file().matchingReason().contains("250000"));
    seed(SERIAL, "second", temp.resolve("first.wpilog"), WALL, "captured");
    var ambiguous = imported(wpilog("ambiguous.wpilog", SERIAL, false, 0, 0));
    assertFalse(List.of("first", "second").contains(ambiguous.session().id()));
    assertNull(ambiguous.file().matching());
    assertTrue(ambiguous.file().matchingReason().contains("ambiguous"));
  }

  @Test void aMatchedAnchorCannotAccumulateAnotherToleranceStep() throws Exception {
    var anchor = seed(SERIAL, "matched", wpilog("anchor.wpilog", SERIAL, false, -200_000, 0), WALL, "matched");
    store.capture(io -> {
      var path = anchor.getParent().resolve("session.json"); var s = io.read(path, Session.class); var f = s.files().get(0);
      var evidence = new Matching("by_correlation", "f".repeat(64), 200_000, 1, 0, 10, "serial_and_data");
      io.write(path, s.withFiles(List.of(new LogFile(f.path(), f.sha256(), f.sizeBytes(), f.kind(), f.provenance(),
          f.verified(), f.minTimestampSec(), f.maxTimestampSec(), f.startedAt(), f.endedAt(), f.startBasis(), f.truncated(), evidence))));
      return null;
    });
    var further = wpilog("further.wpilog", SERIAL, false, -320_000, 0);
    try (var left = manager.acquire(anchor.toString()); var right = manager.acquire(further.toString())) {
      var measured = new org.triplehelix.wpilogmcp.sync.LogSynchronizer().synchronize(left.log(), right.log());
      assertEquals(120_000, measured.offsetMicros(), 2_000, "the planted next step fits the local allowance");
      assertTrue(measured.strongPairCount() > 0);
    }
    var placed = imported(further);
    // 120 ms against the file, but 320 ms against its already-proven session clock.
    assertNotEquals("matched", placed.session().id(), "data-matched anchors cannot chain the 250 ms allowance");
    assertNull(placed.file().matching());
    assertTrue(placed.file().matchingReason().contains("250000"));
  }

  @ParameterizedTest @ValueSource(strings = {"corrupt", "cycle", "offset"})
  void invalidRecordedAnchorsRefusePlacementWithTheReason(String fault) throws Exception {
    var anchor = seed(SERIAL, "bad", wpilog("anchor.wpilog", SERIAL, true, 0, 0), WALL, "matched");
    store.capture(io -> {
      var path = anchor.getParent().resolve("session.json"); var s = io.read(path, Session.class); var f = s.files().get(0);
      var evidence = new Matching("by_correlation", fault.equals("cycle") ? f.sha256() : "f".repeat(64),
          fault.equals("offset") ? 400_000 : 0, fault.equals("corrupt") ? -1 : 1, 0, 10, "serial_and_data");
      io.write(path, s.withFiles(List.of(new LogFile(f.path(), f.sha256(), f.sizeBytes(), f.kind(), f.provenance(),
          f.verified(), f.minTimestampSec(), f.maxTimestampSec(), f.startedAt(), f.endedAt(), f.startBasis(), f.truncated(), evidence))));
      return null;
    });
    byte[] before = Files.readAllBytes(anchor.getParent().resolve("session.json"));
    var placed = imported(wpilog("import.wpilog", SERIAL, false, 0, 0));
    assertNotEquals("bad", placed.session().id()); assertNull(placed.file().matching());
    String reason = switch (fault) {
      case "corrupt" -> "Invalid recorded alignment";
      case "cycle" -> "Cycle in recorded anchor alignment";
      default -> "250000";
    };
    assertTrue(placed.file().matchingReason().contains(reason), placed.file().matchingReason());
    assertArrayEquals(before, Files.readAllBytes(anchor.getParent().resolve("session.json")));
  }
}
