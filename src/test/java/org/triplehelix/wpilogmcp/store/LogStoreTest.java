/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import static org.triplehelix.wpilogmcp.fixtures.WpilogWriter.*;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogFileAccess;
import org.triplehelix.wpilogmcp.log.ScopedLogReader;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.tools.CoreTools;

/** Synthetic files only: known clocks, declared identities, and deliberately distinct outputs. */
class LogStoreTest {
  @TempDir Path temp;
  Path root;
  SecurityValidator security;
  StoreRegistry registry;
  List<Path> previousDirectories;
  static final Instant START = Instant.parse("2026-01-10T15:00:00Z");

  @BeforeEach
  void setup() throws Exception {
    temp = temp.toRealPath();
    root = Files.createDirectory(temp.resolve("store"));
    security = new SecurityValidator();
    security.addAllowedDirectory(temp);
    registry = new StoreRegistry(security);
    previousDirectories = LogDirectory.getInstance().getLogDirectories();
    LogDirectory.getInstance().setLogDirectory(root.toString());
  }

  @AfterEach
  void cleanup() {
    registry.close();
    LogDirectory.getInstance().setLogDirectories(previousDirectories.stream().map(Path::toString).toList());
    LogDirectory.getInstance().clearCache();
  }

  private Path log(Path path, String serial, Instant start, int tag) throws Exception {
    Files.createDirectories(path.getParent());
    try (var w = new WpilogWriter(path, "synthetic store fixture " + tag)) {
      int clock = w.start("systemTime", "int64", "", 0);
      int value = w.start("/Drive/AppliedOutput", "double", "", 0);
      int event = w.start("/DriverStation/EventName", "string", "", 0);
      int type = w.start("/DriverStation/MatchType", "int64", "", 0);
      int number = w.start("/DriverStation/MatchNumber", "int64", "", 0);
      int team = w.start("/SystemStats/TeamNumber", "int64", "", 0);
      w.append(team, 10_000_000, encodeInt64(9999));
      if (serial != null) {
        int id = w.start("/SystemStats/SerialNumber", "string", "", 0);
        w.append(id, 10_000_000, encodeString(serial));
        int comments = w.start("/SystemStats/Comments", "string", "", 0);
        w.append(comments, 10_000_000, encodeString("fixture robot"));
      }
      if (start != null) w.append(clock, 12_000_000,
          encodeInt64(start.plusSeconds(2).toEpochMilli() * 1000));
      w.append(value, 10_000_000, encodeDouble(tag));
      // Metadata arrives late, after the startup records a directory listing may stop at.
      w.append(event, 15_000_000, encodeString("TEST"));
      w.append(type, 15_000_000, encodeInt64(2));
      w.append(number, 15_000_000, encodeInt64(7));
      w.append(value, 20_000_000, encodeDouble(tag + 1));
    }
    return path;
  }

  private LogStore.Result run(List<Path> paths, boolean move, String robot) throws Exception {
    return registry.store(root).importPaths(new LogStore.Request(paths, move, robot), p -> {})
        .get(60, TimeUnit.SECONDS);
  }

  private StoreCatalog.Snapshot catalog() throws Exception { return StoreCatalog.read(root, security); }

  private JsonObject listing() throws Exception {
    var tools = new ToolRegistry();
    CoreTools.registerAll(tools);
    return tools.getTool("list_available_logs").execute(new JsonObject()).getAsJsonObject();
  }

  @Test
  void twoRobotsWithRevPairsMoveTogether() throws Exception {
    for (String robot : List.of("practice", "competition")) {
      var incoming = Files.createDirectories(root.resolve(robot));
      var wpi = incoming.resolve(robot + ".wpilog");
      double scale = robot.equals("practice") ? 1.0 : 0.5;
      try (var w = new WpilogWriter(wpi, robot)) {
        int clock = w.start("systemTime", "int64", "", 0);
        int output = w.start("/Drive/AppliedOutput", "double", "", 0);
        for (int i = 0; i <= 3000; i++) {
          double t = 10 + i * 0.02;
          w.append(output, Math.round(t * 1e6), encodeDouble(scale * FixtureLogs.revlogPairOutput(t)));
          if (i % 50 == 0) w.append(clock, Math.round(t * 1e6),
              encodeInt64(START.toEpochMilli() * 1000 + Math.round((t - 10) * 1e6)));
        }
      }
      try (var w = new WpilogWriter(incoming.resolve("REV_20260110_150005.revlog"), robot)) {
        int output = w.start("CAN/3/Periodic Status 0", "raw", "", 0);
        for (int i = 0; i <= 5000; i++) {
          double t = i * 0.01;
          w.append(output, Math.round(t * 1e6), FixtureLogs.sparkStatus0(
              scale * FixtureLogs.revlogPairOutput(t + 15.3), 12, 20, 30, false));
        }
      }
      List<Path> originals;
      try (var files = Files.list(incoming)) { originals = files.sorted().toList(); }
      var hashes = new java.util.HashMap<Path, byte[]>();
      for (var path : originals) hashes.put(path, Files.readAllBytes(path));
      var result = run(List.of(incoming), true, robot);
      assertEquals(List.of("imported", "imported"), result.files().stream().map(LogStore.Outcome::status).toList(), result.toString());
      for (var outcome : result.files()) {
        assertFalse(Files.exists(outcome.originalPath()));
        assertArrayEquals(hashes.get(outcome.originalPath()), Files.readAllBytes(outcome.path()));
        assertTrue(outcome.path().startsWith(root.resolve("robots").resolve(robot).resolve("sessions")));
        var stored = catalog().files().stream().filter(f -> f.path().equals(outcome.path())).findFirst().orElseThrow();
        assertEquals(outcome.originalPath().toString(), stored.file().provenance().originalPath());
        assertEquals(outcome.originalPath().getFileName().toString(), stored.file().provenance().originalName());
        assertTrue(stored.file().verified());
        if (stored.file().kind().equals("revlog")) {
          assertEquals("by_correlation", stored.file().matching().method());
          assertEquals(15_300_000, stored.file().matching().offsetMicros(), 20_000);
        }
      }
    }
    assertEquals(2, catalog().robots().size());
  }

  @Test
  void duplicateHashLeavesOriginalAndReportsStoredPath() throws Exception {
    var original = log(temp.resolve("input.wpilog"), null, START, 1);
    var first = run(List.of(original), false, "practice").files().get(0);
    var duplicate = temp.resolve("renamed.wpilog");
    Files.copy(original, duplicate);
    var again = run(List.of(duplicate), true, "competition").files().get(0);
    assertEquals("present", again.status());
    assertEquals(first.path(), again.path());
    assertTrue(Files.exists(original));
    assertTrue(Files.exists(duplicate));
    assertEquals(1, catalog().files().size());
  }

  @Test
  void lateRevUsesExistingSession() throws Exception {
    var incoming = Files.createDirectory(temp.resolve("incoming"));
    var wpi = FixtureLogs.writeRevlogPair(incoming, "pair.wpilog", java.time.ZoneOffset.UTC, "systemTime");
    Path rev;
    try (var paths = Files.list(incoming)) { rev = paths.filter(p -> !p.equals(wpi)).findFirst().orElseThrow(); }
    var first = run(List.of(wpi), true, "practice").files().get(0);
    var later = run(List.of(rev), true, null).files().get(0);
    assertEquals("imported", later.status(), later.toString());
    assertEquals(first.path().getParent(), later.path().getParent());
    assertEquals(2, catalog().files().get(0).session().files().size());
  }

  @Test
  void serialPromotesStatedRobotAndKeepsRedirects() throws Exception {
    var first = log(temp.resolve("first.wpilog"), null, START, 1);
    var oldPath = run(List.of(first), true, "practice").files().get(0).path();
    run(List.of(log(temp.resolve("serial.wpilog"), "SERIAL42", START.plusSeconds(30), 2)), true, "practice");
    assertFalse(Files.exists(root.resolve("robots").resolve("practice")));
    assertTrue(Files.isDirectory(root.resolve("robots").resolve("SERIAL42")));
    var robot = catalog().robots().get(0).robot();
    assertEquals("SERIAL42", robot.serialNumber());
    assertEquals("logged", robot.basis());
    assertEquals("fixture robot", robot.comments());
    assertTrue(catalog().moved().stream().anyMatch(m -> m.originalPath().equals(oldPath.toString())
        && Files.exists(Path.of(m.movedTo()))));
  }

  @Test
  void existingSerialIsReportedWithoutMerging() throws Exception {
    run(List.of(log(temp.resolve("known.wpilog"), "SERIAL42", START, 1)), false, null);
    var first = run(List.of(log(temp.resolve("stated.wpilog"), null, START, 2)), false, "practice");
    var result = run(List.of(log(temp.resolve("later.wpilog"), "SERIAL42", START, 3)), false, "practice");
    assertEquals("SERIAL42", result.sameRobots().get(0).serialNumber());
    assertEquals(List.of(root.resolve("robots").resolve("practice"), root.resolve("robots").resolve("SERIAL42")),
        result.sameRobots().get(0).directories());
    assertEquals(2, catalog().robots().size());
    assertTrue(Files.exists(first.files().get(0).path()));
    assertTrue(catalog().robots().stream().allMatch(r -> r.robot().serialNumber().equals("SERIAL42")));
  }

  @Test
  void storeListingUsesManifestsAndReportsStraysAndMoves() throws Exception {
    var path = log(root.resolve("one.wpilog"), null, START, 1);
    var plain = listing();
    var plainLog = plain.getAsJsonArray("logs").get(0).getAsJsonObject();
    assertFalse(plainLog.has("robot"));
    assertFalse(plainLog.has("session"));
    assertFalse(plain.has("unmanaged"));
    var imported = run(List.of(path), true, "practice").files().get(0).path();
    var stray = imported.getParent().resolve("hand-copied.wpilog");
    Files.copy(imported, stray);
    var stored = listing();
    assertEquals(1, stored.get("log_count").getAsInt(), stored.toString());
    var row = stored.getAsJsonArray("logs").get(0).getAsJsonObject();
    assertEquals("stated", row.getAsJsonObject("robot").get("basis").getAsString());
    assertTrue(row.getAsJsonObject("robot").get("serial_number").isJsonNull());
    assertEquals(START.toString(), row.getAsJsonObject("session").get("started_at").getAsString());
    assertEquals(stray, Path.of(stored.getAsJsonArray("unmanaged").get(0).getAsJsonObject().get("path").getAsString()));
    assertEquals(imported, Path.of(stored.getAsJsonArray("moved_to").get(0).getAsJsonObject().get("moved_to").getAsString()));
    int depth = LogDirectory.getInstance().getScanDepth();
    try {
      LogDirectory.getInstance().setScanDepth(10);
      LogDirectory.getInstance().setLogDirectories(List.of(temp.toString(), root.toString()));
      var overlapping = listing();
      assertEquals(1, overlapping.get("log_count").getAsInt(), overlapping.toString());
      assertTrue(overlapping.getAsJsonArray("logs").get(0).getAsJsonObject().has("session"));
    } finally { LogDirectory.getInstance().setScanDepth(depth); }
  }

  @Test
  void newerFormatIsRefusedWithoutChanges() throws Exception {
    run(List.of(), false, null);
    var path = root.resolve("store.json");
    var header = StoreFiles.JSON.fromJson(Files.readString(path), JsonObject.class);
    header.addProperty("format_version", StoreManifest.FORMAT_VERSION + 1);
    Files.writeString(path, header.toString());
    byte[] before = Files.readAllBytes(path);
    var error = assertThrows(java.io.IOException.class, this::catalog);
    assertTrue(error.getMessage().contains("newer"));
    assertEquals("error", listing().get("status").getAsString());
    assertThrows(java.util.concurrent.ExecutionException.class,
        () -> run(List.of(log(temp.resolve("new.wpilog"), null, START, 1)), true, "practice"));
    assertArrayEquals(before, Files.readAllBytes(path));
  }

  @Test
  void clocksAndSessionOverlapUseRecordedFacts() throws Exception {
    var a = log(temp.resolve("misleading.wpilog"), "R1", START, 1);
    var first = run(List.of(a), false, null).files().get(0);
    var sessionDir = root.resolve("robots").resolve("R1").resolve("sessions")
        .resolve("2026-01-10").resolve("150000Z_TEST_Q7");
    assertEquals(sessionDir.resolve("robot").resolve(a.getFileName()), first.path());
    var b = log(temp.resolve("overlap.wpilog"), "R1", START.plusSeconds(5), 2);
    var second = run(List.of(b), false, null).files().get(0);
    assertEquals(first.path().getParent(), second.path().getParent());
    var session = catalog().files().get(0).session();
    assertEquals(START.plusSeconds(15).toString(), session.endedAt());
    assertEquals("logged:systemTime", session.startBasis());
    assertEquals(10.0, session.files().get(0).minTimestampSec());
    assertEquals(20.0, session.files().get(0).maxTimestampSec());
    assertEquals("TEST", session.event());
    assertEquals("Qualification", session.matchType());
    assertEquals(7, session.matchNumber());
    assertEquals(9999, session.teamNumber());
    assertEquals(9999, listing().getAsJsonArray("logs").get(0).getAsJsonObject().get("team_number").getAsInt());
  }

  @Test
  void filenameAndModificationTimeFallbacksAreExplicit() throws Exception {
    var named = log(temp.resolve("FRC_20260110_150000.wpilog"), null, null, 1);
    var unnamed = log(temp.resolve("unknown.wpilog"), null, null, 2);
    Files.setLastModifiedTime(unnamed, FileTime.from(START.plusSeconds(40)));
    run(List.of(named, unnamed), false, "practice");
    var files = catalog().files();
    var filename = files.stream().filter(f -> f.file().startBasis().equals("filename")).findFirst().orElseThrow();
    assertEquals(START.toString(), filename.file().startedAt());
    var mtime = files.stream().filter(f -> f.file().startBasis().equals("modification_time")).findFirst().orElseThrow();
    assertEquals(START.plusSeconds(30).toString(), mtime.file().startedAt());
  }

  @Test
  void unknownContentRefusedAndUnassignedListed() throws Exception {
    var text = Files.writeString(temp.resolve("fake.wpilog"), "this is not a robot log");
    var valid = log(temp.resolve("log.data"), null, START, 1);
    var result = run(List.of(text, valid), true, null);
    var refused = result.files().stream().filter(f -> f.originalPath().equals(text)).findFirst().orElseThrow();
    assertEquals("refused", refused.status());
    assertTrue(refused.reason().contains("fake.wpilog"));
    assertTrue(Files.exists(text));
    var assigned = result.files().stream().filter(f -> f.originalPath().equals(valid)).findFirst().orElseThrow();
    assertEquals("unassigned", assigned.status());
    assertTrue(assigned.path().startsWith(root.resolve("unassigned")));
    assertEquals(0, listing().getAsJsonArray("logs").size());
    assertEquals(1, listing().getAsJsonArray("unassigned").size());
  }

  @Test
  void secondImportQueuesAndReportsCompletion() throws Exception {
    var store = registry.store(root);
    assertSame(store, registry.store(root.resolve(".")));
    var first = log(temp.resolve("one.wpilog"), null, START, 1);
    var second = log(temp.resolve("two.wpilog"), null, START.plusSeconds(30), 2);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var updates = new ArrayList<LogStore.Progress>();
    var secondEntered = new CountDownLatch(1);
    var a = store.importPaths(new LogStore.Request(List.of(first), false, "practice"), p -> {
      if (p.phase().equals("inspecting")) {
        entered.countDown();
        try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out"); }
        catch (InterruptedException e) { throw new IllegalStateException(e); }
      }
    });
    assertTrue(entered.await(10, TimeUnit.SECONDS));
    try {
      var b = store.importPaths(new LogStore.Request(List.of(second), false, "practice"), p -> {
        updates.add(p);
        if (p.phase().equals("inspecting")) secondEntered.countDown();
      });
      assertFalse(secondEntered.await(1, TimeUnit.SECONDS));
      assertFalse(b.isDone());
      assertTrue(updates.isEmpty());
      release.countDown();
      a.get(10, TimeUnit.SECONDS);
      b.get(10, TimeUnit.SECONDS);
      var last = updates.get(updates.size() - 1);
      assertEquals("complete", last.phase());
      assertEquals(1, last.completed());
      assertEquals(1, last.total());
    } finally { release.countDown(); }
  }

  @Test
  void pathsCannotEscapeConfiguredDirectoriesOrManifests() throws Exception {
    run(List.of(), false, null);
    var limited = new SecurityValidator();
    limited.addAllowedDirectory(root);
    try (var restricted = new StoreRegistry(limited)) {
      var outside = log(temp.resolve("outside.wpilog"), null, START, 1);
      var result = restricted.store(root).importPaths(
          new LogStore.Request(List.of(outside), true, "practice"), p -> {}).get();
      assertEquals("refused", result.files().get(0).status());
      assertTrue(Files.exists(outside));
    }
    var io = new StoreFiles(root, security);
    assertThrows(java.io.IOException.class, () -> io.resolve(root, "../outside.wpilog"));
    assertThrows(java.io.IOException.class, () -> io.resolve(root, "..\\outside.wpilog"));
    assertThrows(IllegalArgumentException.class, () -> StoreFiles.component("CON"));
    assertThrows(IllegalArgumentException.class, () -> StoreFiles.component("../practice"));
  }

  @Test
  void scopedReaderReleasesMappingAndProtectsOpenFiles() throws Exception {
    var input = log(temp.resolve("mapped.wpilog"), null, START, 1);
    var reader = new ScopedLogReader(input);
    try {
      assertThrows(java.io.IOException.class, () -> LogFileAccess.move(List.of(input)));
    } finally { reader.close(); }
    assertThrows(IllegalStateException.class, reader::reader);
    var result = run(List.of(input), true, "practice");
    assertEquals("imported", result.files().get(0).status());
  }

  @Test
  void duplicateWithinBatchKeepsSecondSource() throws Exception {
    var first = log(temp.resolve("a.wpilog"), null, START, 1);
    var second = temp.resolve("b.wpilog");
    Files.copy(first, second);
    var result = run(List.of(first, second), true, "practice");
    assertEquals(1, catalog().files().size());
    assertEquals("present", result.files().get(1).status());
    assertTrue(Files.exists(second));
  }

  @Test
  void failedVerificationNeverCommitsAndKeepsCopyAsUnmanaged() throws Exception {
    var input = log(temp.resolve("one.wpilog"), null, START, 1);
    byte[] original = Files.readAllBytes(input);
    var result = registry.store(root).importPaths(new LogStore.Request(List.of(input), false, "practice"), p -> {
      if (p.phase().equals("verifying")) {
        try { Files.writeString(p.path(), "not a log"); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
      }
    }).get();
    assertEquals("refused", result.files().get(0).status());
    assertArrayEquals(original, Files.readAllBytes(input));
    assertTrue(catalog().files().isEmpty());
    assertEquals(List.of(result.files().get(0).path()), catalog().unmanaged());
  }

  @Test
  void sharedMappingRefusesMoveButAllowsCopy() throws Exception {
    var input = log(temp.resolve("one.wpilog"), null, START, 1);
    // Shared mappings can outlive eviction; copy is available without ending active readers.
    LogFileAccess.read(input, true);
    var moved = run(List.of(input), true, "practice").files().get(0);
    assertEquals("refused", moved.status());
    assertTrue(moved.reason().contains("restart"));
    assertTrue(Files.exists(input));
    assertTrue(catalog().files().isEmpty());
    assertEquals("imported", run(List.of(input), false, "practice").files().get(0).status());
  }

  @Test
  void nativeRevHeaderAcceptedWithoutTrustingExtension() throws Exception {
    var nativeLog = temp.resolve("controller.data");
    // One native firmware record: id 1, ten-byte payload, REV SPARK device 4.
    var bytes = java.nio.ByteBuffer.allocate(13).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    bytes.put((byte) 0).put((byte) 1).put((byte) 10).putInt(0x02050004)
        .put(new byte[] {26, 1, 0, 5, 0, 0});
    Files.write(nativeLog, bytes.array());
    var result = run(List.of(nativeLog), false, null).files().get(0);
    assertEquals("unassigned", result.status(), result.toString());
    assertEquals("revlog", catalog().files().get(0).file().kind());
    assertArrayEquals(bytes.array(), Files.readAllBytes(result.path()));
  }

  @Test
  void ambiguousRevPairStaysUnassigned() throws Exception {
    var a = Files.createDirectory(temp.resolve("a"));
    var b = Files.createDirectory(temp.resolve("b"));
    var first = FixtureLogs.writeRevlogPair(a, "one.wpilog", java.time.ZoneOffset.UTC, "systemTime");
    var second = FixtureLogs.writeRevlogPair(b, "two.wpilog", java.time.ZoneOffset.UTC, "systemTime");
    // Valid extra header bytes change content identity but cannot distinguish the signal evidence.
    byte[] bytes = Files.readAllBytes(second);
    int extraLength = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(8);
    var different = java.nio.ByteBuffer.allocate(bytes.length + 1).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    different.put(bytes, 0, 8).putInt(extraLength + 1).put((byte) 'X').put(bytes, 12, bytes.length - 12);
    Files.write(second, different.array());
    run(List.of(first), false, "practice");
    run(List.of(second), false, "competition");
    Path rev;
    try (var paths = Files.list(a)) { rev = paths.filter(p -> p.toString().endsWith(".revlog")).findFirst().orElseThrow(); }
    var result = run(List.of(rev), false, null).files().get(0);
    assertEquals("unassigned", result.status());
    assertNull(catalog().files().stream().filter(f -> f.path().equals(result.path())).findFirst().orElseThrow().session());
  }

  @Test
  void expiredMoveNoticesAreOmitted() throws Exception {
    run(List.of(log(temp.resolve("one.wpilog"), null, START, 1)), true, "practice");
    var io = new StoreFiles(root, security);
    var header = catalog().header();
    var original = header.moves().get(0);
    var expired = new StoreManifest.Move(original.originalPath(), original.movedTo(),
        Instant.now().minus(java.time.Duration.ofDays(8)).toString());
    io.write(root.resolve("store.json"), new StoreManifest.Header(header.formatVersion(),
        header.createdAt(), header.id(), List.of(expired)));
    assertTrue(catalog().moved().isEmpty());
    assertTrue(listing().getAsJsonArray("moved_to").isEmpty());
  }

  @Test
  void storedRevDiscoveryExcludesOtherRobotsAndUnmanagedFiles() throws Exception {
    var incoming = Files.createDirectory(temp.resolve("incoming"));
    var wpi = FixtureLogs.writeRevlogPair(incoming, "pair.wpilog", java.time.ZoneOffset.UTC, "systemTime");
    run(List.of(incoming), true, "practice");
    var catalog = catalog();
    var log = catalog.files().stream().filter(f -> f.file().kind().equals("wpilog")).findFirst().orElseThrow();
    var rev = catalog.files().stream().filter(f -> f.file().kind().equals("revlog")).findFirst().orElseThrow();
    var stray = rev.path().getParent().resolve("stray.revlog");
    Files.copy(rev.path(), stray);
    var manager = org.triplehelix.wpilogmcp.log.LogManager.getInstance();
    var allowed = manager.getAllowedDirectories();
    manager.addAllowedDirectory(root);
    try {
      manager.loadLog(log.path().toString());
      assertTrue(manager.waitForRevLogSync(log.path().toString(), 30_000));
      var synchronizedLogs = manager.getSynchronizedLogs(log.path().toString());
      assertEquals(List.of(rev.path().toString()), synchronizedLogs.revlogs().stream()
          .map(r -> r.revlog().path()).toList());
      assertEquals(List.of(rev.path()), LogDirectory.getInstance().listRevLogFiles().stream()
          .map(LogDirectory.RevLogFileInfo::path).toList());
    } finally {
      manager.unloadLog(log.path().toString());
      manager.clearAllowedDirectories();
      allowed.forEach(manager::addAllowedDirectory);
    }
  }
}
