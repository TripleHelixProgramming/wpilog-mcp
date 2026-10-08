/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import static org.triplehelix.wpilogmcp.fixtures.WpilogWriter.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.pull.FakeRobot;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;
import org.triplehelix.wpilogmcp.sync.*;

class PullStoreTest {
  @TempDir Path temp;
  Path root; StoreRegistry registry; LogStore store; SecurityValidator security;
  final LogManager manager = LogManager.getInstance(); Set<Path> allowed;
  static final Clock WALL = Clock.fixed(Instant.parse("2026-03-07T14:22:33Z"), ZoneOffset.UTC);
  @BeforeEach void setup() throws Exception {
    temp = temp.toRealPath(); root = temp.resolve("store"); allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    security = new SecurityValidator(); security.addAllowedDirectory(temp); registry = new StoreRegistry(security); store = registry.store(root);
    store.identify(FakeRobot.device("SYNTHETIC-A", "SHA256:first"), WALL).get(10, TimeUnit.SECONDS);
  }
  @AfterEach void cleanup() throws Exception {
    registry.close(); manager.release(temp); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory);
  }
  Path log(String filename, String serial, String name, long shiftUs, boolean flat) throws Exception {
    var path = temp.resolve(filename);
    try (var writer = new WpilogWriter(path, "synthetic pull matching")) {
      int value = writer.start(name, "double", "", 0);
      if (serial != null) writer.append(writer.start("/SystemStats/SerialNumber", "string", "", 0), 10_000_000 + shiftUs, encodeString(serial));
      for (int n = 0; n <= 3000; n++) {
        double time = 10 + n * 0.02;
        writer.append(value, 10_000_000 + n * 20_000L + shiftUs, encodeDouble(flat ? 1 : FixtureLogs.revlogPairOutput(time)));
      }
    }
    return path;
  }
  Path seed(String serial, String session, Path input) throws Exception {
    return seed(serial, session, input, WALL.instant());
  }
  Path seed(String serial, String session, Path input, Instant start) throws Exception {
    return store.capture(io -> {
      var robot = root.resolve("robots").resolve(serial);
      if (!Files.exists(robot.resolve("robot.json"))) io.write(robot.resolve("robot.json"), new Robot(serial, serial, null, "fixture", "logged"));
      var directory = robot.resolve("sessions").resolve("2026-03-07").resolve(session);
      Files.createDirectories(directory); var path = directory.resolve("capture.wpilog"); Files.copy(input, path);
      var file = new LogFile("capture.wpilog", StoreFiles.hash(path), Files.size(path), "wpilog",
          new Provenance("captured", null, null, null, false), true, 10, 70, start.toString(), start.plusSeconds(60).toString(), "server_clock", false, null);
      io.write(directory.resolve("session.json"), new Session(session, start.toString(), start.plusSeconds(60).toString(), "server_clock", null, null, null, null, List.of(file)));
      return path;
    });
  }
  PullManifest.Entry pull(FakeRobot remote) throws Exception {
    var local = store.pulls(remote.device, WALL); var clock = new AtomicLong();
    var transfer = new FileTransfer(remote, local, local.manifest(), 1_000_000, clock::get, () -> true);
    for (int n = 0; n < 100; n++) {
      var result = transfer.step(); clock.addAndGet(Math.max(1, result.waitUs()));
      if (result.status() == FileTransfer.Status.VERIFIED) return transfer.manifest().files().stream().filter(e -> e.remoteName().equals(result.remoteName())).findFirst().orElseThrow();
      assertNotEquals(FileTransfer.Status.REFUSED, result.status(), result.detail());
    }
    fail("transfer did not verify"); return null;
  }
  StoreCatalog.StoredFile placed(PullManifest.Entry entry) throws Exception {
    return StoreCatalog.read(root, security).files().stream().filter(f -> f.path().equals(root.resolve(entry.localName()))).findFirst().orElseThrow();
  }

  @Test void candidateClocksUseInclusiveTwoOrSixteenHourWindows() {
    for (String basis : List.of("logged:systemTime", "filename")) {
      var start = WALL.instant(); var end = start.plusSeconds(60);
      var input = new ImportInspection(Path.of("synthetic.wpilog"), "", 0, "wpilog", null,
          0, 60, start, end, basis, false, null, null);
      long slack = (basis.equals("filename") ? 16 : 2) * 3600L;
      var lower = start.minusSeconds(slack); var upper = end.plusSeconds(slack);
      assertAll(
          () -> assertTrue(input.nearClock(lower.minusSeconds(60), lower)),
          () -> assertTrue(input.nearClock(upper, upper.plusSeconds(60))),
          () -> assertFalse(input.nearClock(lower.minusSeconds(60), lower.minusNanos(1))),
          () -> assertFalse(input.nearClock(upper.plusNanos(1), upper.plusSeconds(60))),
          () -> assertTrue(input.nearClock(null, null)));
    }
  }

  @Test void matchingLoadsOnlySessionsWhoseManifestClocksOverlap() throws Exception {
    var capture = log("capture.wpilog", null, "NT:/x", 0, false);
    var distant = new java.util.ArrayList<Path>(); Path overlap = null;
    for (int day = -10; day <= 10; day++) {
      var path = seed("SYNTHETIC-A", "day-" + (day + 10), capture, WALL.instant().plusSeconds(day * 86400L));
      if (day == 0) overlap = path; else distant.add(path);
    }
    var remote = new FakeRobot();
    remote.files.put("/u/logs/robot.wpilog", Files.readAllBytes(log("robot.wpilog", null, "/x", 0, false)));
    var stored = placed(pull(remote));
    var loaded = manager.getLoadedLogPaths();
    assertTrue(loaded.contains(overlap.toString()), "the overlapping capture reached correlation");
    assertTrue(distant.stream().noneMatch(p -> loaded.contains(p.toString())), "non-overlapping sessions were loaded: " + loaded);
    assertEquals("day-10", stored.session().id()); assertNotNull(stored.file().matching());
  }

  @Test void byteIdenticalSignalsNeverMatchAnotherSerialAndMissingLoggedSerialIsDataAlone() throws Exception {
    var capture = log("capture.wpilog", null, "NT:/Drive/FrontLeft/AppliedOutput", 0, false);
    seed("SYNTHETIC-A", "142233Z", capture); seed("SYNTHETIC-B", "142233Z", capture);
    var input = log("robot.wpilog", null, "/Drive/FrontLeft/AppliedOutput", 0, false);
    var remote = new FakeRobot(); remote.files.put("/u/logs/robot.wpilog", Files.readAllBytes(input));
    var entry = pull(remote); var stored = placed(entry);
    assertEquals("SYNTHETIC-A", stored.robot().serialNumber()); assertEquals("142233Z", stored.session().id());
    assertEquals("by_correlation", stored.file().matching().method()); assertEquals("data_alone", stored.file().matching().identityBasis());
    assertEquals(0, stored.file().matching().offsetMicros(), 20_000);
    assertEquals("pulled", stored.file().provenance().kind()); assertEquals("/u/logs/robot.wpilog", stored.file().provenance().originalPath());
    assertEquals("SYNTHETIC-A", stored.file().provenance().sourceRobotSerial());
    assertEquals(StoreFiles.hash(input), stored.file().sha256()); assertEquals(Files.size(input), stored.file().sizeBytes());
    assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(stored.path()));
    assertEquals(entry, store.pulls(remote.device, WALL).manifest().files().get(0)); assertTrue(StoreCatalog.read(root, security).unmanaged().isEmpty());
  }

  @Test void loggedIdentityWinsOverDeviceAndKnownSerialMatchingIsRecorded() throws Exception {
    seed("SYNTHETIC-B", "142233Z", log("capture.wpilog", "SYNTHETIC-B", "NT:/x", 0, false));
    seed("SYNTHETIC-A", "142233Z", log("other.wpilog", "SYNTHETIC-A", "NT:/x", 0, false));
    var remote = new FakeRobot(); remote.files.put("/u/logs/robot.wpilog", Files.readAllBytes(log("robot.wpilog", "SYNTHETIC-B", "/x", 0, false)));
    var stored = placed(pull(remote)); assertEquals("SYNTHETIC-B", stored.robot().serialNumber());
    assertEquals("serial_and_data", stored.file().matching().identityBasis());
    assertEquals(List.of(new IdentityConflict(stored.file().path(), "SYNTHETIC-B", "SYNTHETIC-A")), stored.session().identityConflicts());
    assertEquals("SYNTHETIC-A", store.pulls(remote.device, WALL).manifest().serialNumber());
  }

  @Test void flatOrFarShiftedOrAmbiguousEvidenceStartsItsOwnSession() throws Exception {
    seed("SYNTHETIC-A", "142233Z", log("capture.wpilog", null, "NT:/x", 0, false));
    for (var variant : List.of("flat", "shifted", "ambiguous")) {
      if (variant.equals("ambiguous")) seed("SYNTHETIC-A", "142234Z", temp.resolve("capture.wpilog"));
      var remote = new FakeRobot();
      remote.files.put("/u/logs/" + variant + ".wpilog", Files.readAllBytes(log(variant + ".wpilog", null, "/x", variant.equals("shifted") ? 2_000_000 : 0, variant.equals("flat"))));
      var stored = placed(pull(remote)); assertNull(stored.file().matching(), variant); assertNotEquals("142233Z", stored.session().id());
    }
  }

  @Test void outsideToleranceRemainsRetrievableWithAnExplainedRefusal() throws Exception {
    seed("SYNTHETIC-A", "142233Z", log("capture.wpilog", null, "NT:/x", 0, false));
    var remote = new FakeRobot();
    remote.files.put("/u/logs/shifted.wpilog", Files.readAllBytes(log("shifted.wpilog", null, "/x", 6_000_000, false)));
    var stored = placed(pull(remote));
    assertNotEquals("142233Z", stored.session().id()); assertNull(stored.file().matching());
    var facts = StoreJson.JSON.toJsonTree(stored.file()).getAsJsonObject();
    assertTrue(facts.has("matching_reason"), "A refusal must retain its reason with the file");
    assertTrue(facts.get("matching_reason").getAsString().contains("250000"));
    try (var use = manager.acquire(stored.path().toString())) { assertEquals(3001, use.log().sampleCount("/x")); }
  }

  @Test void verifiedGrowthAndRenameReturnToStagingAndKeepOldPathsReadable() throws Exception {
    var source = log("source.wpilog", null, "/x", 0, false); var remote = new FakeRobot();
    byte[] data = Files.readAllBytes(source);
    remote.files.put("/u/logs/FRC_TBD.wpilog", data);
    var first = pull(remote); var firstPath = root.resolve(first.localName());
    try (var use = manager.acquire(firstPath.toString())) { assertEquals(3001, use.log().values().get("/x").size()); }
    // WPILOG record: entry 1, payload 8, 4-byte timestamp 71,000,000, then a little-endian double 2.
    var extra = java.nio.ByteBuffer.allocate(15).order(java.nio.ByteOrder.LITTLE_ENDIAN).put((byte) 0x30).put((byte) 1).put((byte) 8).putInt(71_000_000).putDouble(2).array();
    remote.files.put("/u/logs/FRC_TBD.wpilog", java.nio.ByteBuffer.allocate(data.length + extra.length).put(data).put(extra).array()); remote.mtime++;
    var second = pull(remote); assertEquals(data.length + 15, second.bytesCopied()); assertTrue(second.verified());
    try (var use = manager.acquire(firstPath.toString())) { assertEquals(3002, use.log().values().get("/x").size()); }
    remote.files.put("/u/logs/FRC_20260307.wpilog", remote.files.remove("/u/logs/FRC_TBD.wpilog"));
    int reads = remote.reads.size(); var renamed = pull(remote); assertEquals(reads, remote.reads.size());
    assertEquals("FRC_20260307.wpilog", root.resolve(renamed.localName()).getFileName().toString());
    assertEquals(1, StoreCatalog.read(root, security).files().size());
    assertTrue(StoreCatalog.read(root, security).unmanaged().isEmpty());
  }

  @Test void stagingIsHiddenAndCannotMutateControlOrAnotherRobotsFiles() throws Exception {
    var local = store.pulls(FakeRobot.device("SYNTHETIC-A", "SHA256:first"), WALL); local.manifest();
    String name = local.create("/u/logs/name:invalid.wpilog"); assertTrue(root.resolve(name).getFileName().toString().startsWith("remote-"));
    local.append(name, 0, new byte[] {1, 2, 3});
    var progress = new PullManifest(1, "SYNTHETIC-A", List.of(new PullManifest.Entry("/u/logs/x.wpilog", 3, 0, 3, false, name, 0, null)), List.of());
    local.save(progress); assertTrue(StoreCatalog.read(root, security).files().isEmpty()); assertTrue(StoreCatalog.read(root, security).unmanaged().isEmpty());
    assertThrows(IOException.class, () -> local.append(name, 0, new byte[] {4}));
    for (String invalid : List.of("../outside", "store.json", "robots/SYNTHETIC-A/robot.json", "robots/SYNTHETIC-B/pulled/a.wpilog")) {
      assertThrows(IOException.class, () -> local.size(invalid), invalid);
      assertThrows(IOException.class, () -> local.save(new PullManifest(1, "SYNTHETIC-A", List.of(new PullManifest.Entry("/x", 0, 0, 0, false, invalid, 0, null)), List.of())), invalid);
    }
    assertEquals(progress, local.manifest()); assertArrayEquals(new byte[] {1, 2, 3}, local.read(name, 0, 3));
  }

  @Test void nearZeroMeansAtMostAQuarterSecondAndRevUsesTheSameDataDecision() throws Exception {
    seed("SYNTHETIC-A", "142233Z", log("capture.wpilog", null, "NT:/Drive/FrontLeft/AppliedOutput", 0, false));
    for (long offset : new long[] {200_000, 300_000}) {
      var remote = new FakeRobot();
      remote.files.put("/u/logs/shift-" + offset + ".wpilog", Files.readAllBytes(log("shift-" + offset + ".wpilog", null, "/Drive/FrontLeft/AppliedOutput", offset, false)));
      var stored = placed(pull(remote));
      if (offset == 200_000) { assertNotNull(stored.file().matching()); assertEquals(-200_000, stored.file().matching().offsetMicros(), 20_000); }
      else assertNull(stored.file().matching());
    }
    var rev = temp.resolve("REV_19700101_000000.revlog");
    try (var writer = new WpilogWriter(rev, "synthetic same-clock REV")) {
      int id = writer.start("CAN/3/Periodic Status 0", "raw", "", 0);
      for (int n = 0; n <= 6000; n++) {
        double t = 10 + n * 0.01;
        writer.append(id, 10_000_000 + n * 10_000L, FixtureLogs.sparkStatus0(FixtureLogs.revlogPairOutput(t), 12, 20, 30, false));
      }
    }
    var remote = new FakeRobot(); remote.files.put("/u/logs/REV_19700101_000000.revlog", Files.readAllBytes(rev));
    var stored = placed(pull(remote)); assertEquals("revlog", stored.file().kind());
    assertEquals("142233Z", stored.session().id()); assertEquals(0, stored.file().matching().offsetMicros(), 20_000);
    assertEquals("data_alone", stored.file().matching().identityBasis());
  }

  @Test void aPullMatchesAnOpenCaptureAndLaterFlushesPreserveItsFactsAndConflict() throws Exception {
    var loop = new org.triplehelix.wpilogmcp.nt4.client.ManualScheduler();
    var placement = store.captures(WALL);
    var topic = new org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce("/Drive/FrontLeft/AppliedOutput", 1, "double", null, new com.google.gson.JsonObject());
    try (var writer = new org.triplehelix.wpilogmcp.capture.CaptureWriter(WALL, loop,
        org.triplehelix.wpilogmcp.capture.CapturePolicy.ALL, new org.triplehelix.wpilogmcp.capture.CaptureIndex(placement, manager, 600_000_000))) {
      writer.identity(FakeRobot.device("SYNTHETIC-A", "SHA256:first"));
      writer.connected(org.triplehelix.wpilogmcp.nt4.client.RobotAddress.uri("127.0.0.1", 5810, "live-pull"), "networktables.first.wpi.edu");
      writer.timeSync(10_000_000, 0); writer.announce(topic);
      for (int n = 0; n <= 3000; n++) writer.value(topic, new org.triplehelix.wpilogmcp.nt4.ValueFrame(1, 10_000_000 + n * 20_000L, 1,
          FixtureLogs.revlogPairOutput(10 + n * 0.02)), 0);
      loop.advance(250_000); loop.until(() -> writer.session().observedAtUs() == loop.nowUs()); placement.completion().get(10, TimeUnit.SECONDS);
      String session = StoreCatalog.read(root, security).openCaptures().get(0).session().id();
      var remote = new FakeRobot(); remote.device = FakeRobot.device("SYNTHETIC-B", "SHA256:second");
      store.identify(remote.device, WALL).get(10, TimeUnit.SECONDS);
      remote.files.put("/u/logs/robot.wpilog", Files.readAllBytes(log("robot.wpilog", "SYNTHETIC-A", "/Drive/FrontLeft/AppliedOutput", 0, false)));
      var entry = pull(remote); var stored = placed(entry);
      assertEquals(session, stored.session().id()); assertNotNull(stored.session().openCapture());
      assertEquals("by_correlation", stored.file().matching().method()); assertNull(stored.file().matching().wpilogSha256());
      var conflict = new IdentityConflict(stored.file().path(), "SYNTHETIC-A", "SYNTHETIC-B");
      assertEquals(List.of(conflict), stored.session().identityConflicts());
      writer.value(topic, new org.triplehelix.wpilogmcp.nt4.ValueFrame(1, 71_000_000, 1, 0.5), 0);
      loop.advance(5_000_000); loop.until(() -> writer.session().observedAtUs() == loop.nowUs());
      placement.completion().get(10, TimeUnit.SECONDS);
      assertEquals(List.of(conflict), placed(entry).session().identityConflicts());
      assertEquals(stored.file(), placed(entry).file());
    }
    placement.completion().get(10, TimeUnit.SECONDS);
    assertTrue(StoreCatalog.read(root, security).openCaptures().isEmpty());
    assertEquals(2, StoreCatalog.read(root, security).files().size());
  }
}
