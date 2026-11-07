/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;

class CaptureStoreTest {
  @TempDir Path directory;
  private java.util.Set<Path> allowed;
  @org.junit.jupiter.api.BeforeEach void allowStore() {
    allowed = LogManager.getInstance().getAllowedDirectories();
    LogManager.getInstance().addAllowedDirectory(directory);
  }
  @org.junit.jupiter.api.AfterEach void restorePermissions() throws Exception {
    try { LogManager.getInstance().release(directory); }
    finally {
      LogManager.getInstance().clearAllowedDirectories();
      allowed.forEach(LogManager.getInstance()::addAllowedDirectory);
    }
  }
  private static final Clock WALL = Clock.fixed(Instant.parse("2026-03-07T14:22:33Z"), ZoneOffset.UTC);
  private static final Announce VALUE = new Announce("/x", 1, "int", null, new JsonObject());
  private static void connect(CaptureWriter writer, ManualScheduler loop, long time, long received) throws InterruptedException {
    writer.connected(RobotAddress.uri("127.0.0.1", 5810, "test"), "networktables.first.wpi.edu");
    writer.timeSync(time, received); writer.announce(VALUE); loop.until(writer::ready);
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void writerIncarnationsArePersistedForOpenClosedResumedAndRolloverFiles(boolean rollover) throws Exception {
    var manager = LogManager.getInstance(); var store = manager.stores().store(directory.resolve("identity"));
    var placement = store.captures(WALL); var loop = new ManualScheduler();
    var index = new org.triplehelix.wpilogmcp.capture.CaptureIndex(placement, manager, 0);
    var identities = new java.util.HashMap<String, String>();
    try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, index, rollover ? 1024 : 1L << 30)) {
      connect(writer, loop, 10_000_000, 0); placement.completion().get();
      try (var use = manager.acquire(writer.session().path().toString())) {
        String identity = ((org.triplehelix.wpilogmcp.log.LiveLog.View) use.log()).captureIdentity();
        identities.put(writer.session().path().getFileName().toString(), identity);
        var json = com.google.gson.JsonParser.parseString(Files.readString(writer.session().path().resolveSibling("session.json"))).getAsJsonObject();
        assertEquals(new com.google.gson.JsonPrimitive(identity), json.getAsJsonObject("open_capture")
            .getAsJsonObject("provenance").get("writer_identity"));
      }
      for (int i = 0; i < 200; i++) {
        writer.value(VALUE, new ValueFrame(1, 10_000_000 + i, 2, (long) i), 0);
        try (var use = manager.acquire(writer.session().path().toString())) {
          identities.put(writer.session().path().getFileName().toString(), ((org.triplehelix.wpilogmcp.log.LiveLog.View) use.log()).captureIdentity());
        }
      }
      writer.disconnected(); placement.completion().get();
      if (rollover) assertTrue(identities.size() > 1);
      assertEquals(identities.size(), new java.util.HashSet<>(identities.values()).size(), "Each rollover is a distinct writer incarnation");
      var json = com.google.gson.JsonParser.parseString(Files.readString(writer.session().path().resolveSibling("session.json"))).getAsJsonObject();
      assertEquals(identities.size(), json.getAsJsonArray("files").size());
      for (var file : json.getAsJsonArray("files")) {
        var f = file.getAsJsonObject();
        assertEquals(new com.google.gson.JsonPrimitive(identities.get(f.get("path").getAsString())),
            f.getAsJsonObject("provenance").get("writer_identity"));
      }
      String closedName = writer.session().path().getFileName().toString(), last = identities.get(closedName);
      // The same boot reconnects: its session continues in the next file, a writer incarnation of
      // its own, and the closed file keeps the proof it was closed with.
      connect(writer, loop, 11_000_000, 1_000_000); placement.completion().get();
      assertNotEquals(closedName, writer.session().path().getFileName().toString());
      json = com.google.gson.JsonParser.parseString(Files.readString(writer.session().path().resolveSibling("session.json"))).getAsJsonObject();
      String continued = json.getAsJsonObject("open_capture").getAsJsonObject("provenance").get("writer_identity").getAsString();
      assertFalse(identities.containsValue(continued), "The next file is a new writer incarnation");
      var closedFile = java.util.stream.StreamSupport.stream(json.getAsJsonArray("files").spliterator(), false).map(f -> f.getAsJsonObject())
          .filter(f -> f.get("path").getAsString().equals(closedName)).findFirst().orElseThrow();
      assertEquals(last, closedFile.getAsJsonObject("provenance").get("writer_identity").getAsString());
      writer.disconnected(); placement.completion().get();
      connect(writer, loop, 1_000_000, 2_000_000); placement.completion().get();
      json = com.google.gson.JsonParser.parseString(Files.readString(writer.session().path().resolveSibling("session.json"))).getAsJsonObject();
      assertNotEquals(new com.google.gson.JsonPrimitive(last), json.getAsJsonObject("open_capture").getAsJsonObject("provenance").get("writer_identity"));
      writer.disconnected(); placement.completion().get();
    }
  }

  /**
   * A same-boot reconnect's admission leaves the closed file as it was published: the next file
   * opens beside it, and an abandoned admission keeps the closed file's hash and writer proof.
   */
  @Test void aContinuedAdmissionLeavesTheClosedFileAndItsWriterProofUntouched() throws Exception {
    var manager = LogManager.getInstance(); var store = manager.stores().store(directory.resolve("resume-proof"));
    var placement = store.captures(WALL); var loop = new ManualScheduler();
    var index = new org.triplehelix.wpilogmcp.capture.CaptureIndex(placement, manager, 0);
    try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, index)) {
      connect(writer, loop, 10_000_000, 0); String identity;
      try (var use = manager.acquire(writer.session().path().toString())) {
        identity = ((org.triplehelix.wpilogmcp.log.LiveLog.View) use.log()).captureIdentity();
      }
      writer.disconnected(); placement.completion().get();
      var manifest = writer.session().path().resolveSibling("session.json"); String before = Files.readString(manifest);
      var path = placement.createAsync(writer.session().address(), writer.session().startedAt(), writer.session(), null).get();
      try {
        assertEquals(writer.session().path(), path, "Admission names where the session's last file is now");
        assertEquals(before, Files.readString(manifest), "Nothing changes until the next file opens");
      } finally { placement.abandoned(path, WALL.instant().plusSeconds(60), "fixture cancelled reconnect").get(); }
      var after = com.google.gson.JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
      var was = com.google.gson.JsonParser.parseString(before).getAsJsonObject();
      assertEquals(was.get("files"), after.get("files"), "The closed file keeps its hash and size");
      assertEquals(identity, after.getAsJsonArray("files").get(0).getAsJsonObject().getAsJsonObject("provenance").get("writer_identity").getAsString());
      assertEquals(was.get("ended_at"), after.get("ended_at"), "An abandoned reconnect does not move the session's end");
    }
  }

  @Test void openClosedContinuedAndSameSecondSessionsKeepTheirFactsAndBytes() throws Exception {
    directory = directory.toRealPath();
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    try (var stores = new StoreRegistry(security)) {
      var root = directory.resolve("store"); var loop = new ManualScheduler();
      var placement = stores.store(root).captures(WALL);
      try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, placement)) {
        connect(writer, loop, 10_000_000, 0);
        assertEquals(root.resolve("robots/address-127.0.0.1/sessions/2026-03-07/142233Z/capture.wpilog"), writer.session().path());
        writer.value(VALUE, new ValueFrame(1, 10_000_000, 2, 5L), 0); loop.advance(5_000_000); loop.until(() -> writer.session().observedAtUs() == loop.nowUs()); placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        var open = StoreCatalog.read(root, security); assertEquals(1, open.openCaptures().size());
        assertTrue(open.files().isEmpty()); assertTrue(open.unmanaged().isEmpty());
        var first = open.openCaptures().get(0); assertEquals("captured", first.file().provenance().kind());
        assertEquals("address", first.robot().basis());
        var dirs = LogDirectory.getInstance().getLogDirectories();
        try {
          LogDirectory.getInstance().setLogDirectory(root.toString());
          var tools = new org.triplehelix.wpilogmcp.mcp.ToolRegistry();
          org.triplehelix.wpilogmcp.tools.CoreTools.registerAll(tools);
          var listed = tools.getTool("list_available_logs").execute(new JsonObject()).getAsJsonObject();
          assertEquals("address", listed.getAsJsonArray("logs").get(0).getAsJsonObject().getAsJsonObject("robot").get("basis").getAsString());
        } finally { LogDirectory.getInstance().setLogDirectories(dirs.stream().map(Path::toString).toList()); }
        assertNull(first.file().sha256()); assertEquals(10, first.file().minTimestampSec());
        assertEquals(WALL.instant().toString(), first.session().startedAt());
        assertEquals("pit_clock", first.session().startBasis());
        writer.disconnected(); placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS); var closed = StoreCatalog.read(root, security);
        assertTrue(closed.openCaptures().isEmpty()); assertEquals(1, closed.files().size());
        var file = closed.files().get(0); assertEquals(StoreFiles.hash(file.path()), file.file().sha256());
        assertEquals(Files.size(file.path()), file.file().sizeBytes());
        String firstHash = file.file().sha256();
        connect(writer, loop, 11_000_000, 1_000_000);
        writer.value(VALUE, new ValueFrame(1, 11_000_000, 2, 6L), 1_000_000); writer.disconnected(); placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        // The same boot reconnected: the session continues in its next file, and the closed one keeps its bytes and hash.
        closed = StoreCatalog.read(root, security); assertEquals(2, closed.files().size());
        var byName = new java.util.HashMap<String, StoreCatalog.StoredFile>();
        closed.files().forEach(f -> byName.put(f.path().getFileName().toString(), f));
        assertEquals(firstHash, byName.get("capture.wpilog").file().sha256()); assertEquals(firstHash, StoreFiles.hash(byName.get("capture.wpilog").path()));
        assertEquals(10, byName.get("capture.wpilog").file().maxTimestampSec());
        var continued = byName.get("capture-2.wpilog");
        assertEquals(first.session().id(), continued.session().id()); assertEquals(11, continued.file().maxTimestampSec());
        assertEquals(StoreFiles.hash(continued.path()), continued.file().sha256());
        connect(writer, loop, 1_000_000, 2_000_000); writer.disconnected(); placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("142233Z_2", writer.session().path().getParent().getFileName().toString());
        assertEquals(3, StoreCatalog.read(root, security).files().size());
        assertEquals(1, StoreCatalog.read(root, security).header().formatVersion());
      }
    }
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void matchFactsPrecedeTheCloseTimeRenameAndARefusedMoveCanRetry(boolean windowsRefusal) throws Exception {
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    var allowMove = new AtomicBoolean(!windowsRefusal);
    try (var stores = new StoreRegistry(security)) {
      var root = directory.resolve("store"); var loop = new ManualScheduler();
      var attempts = new java.util.concurrent.atomic.AtomicInteger();
      var placement = new CaptureStore(stores.store(root), LogManager.getInstance(), WALL, (from, to) -> {
        attempts.incrementAndGet();
        if (!allowMove.get()) throw new java.nio.file.AccessDeniedException(from.toString());
        Files.move(from, to);
      });
      var index = new org.triplehelix.wpilogmcp.capture.CaptureIndex(placement, LogManager.getInstance(), 0);
      try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, index)) {
        connect(writer, loop, 10_000_000, 0); var before = writer.session().path();
        var names = List.of("/FMSInfo/EventName", "/FMSInfo/MatchType", "/FMSInfo/MatchNumber");
        for (int i = 0; i < names.size(); i++) {
          var topic = new Announce(names.get(i), i + 2, i == 0 ? "string" : "int", null, new JsonObject());
          writer.announce(topic); writer.value(topic, new ValueFrame(i + 2, 10_000_000, i == 0 ? 4 : 2,
              i == 0 ? "TEST" : i == 1 ? 2L : 7L), 0);
        }
        placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        var held = StoreCatalog.read(root, security).openCaptures().get(0);
        try (var use = LogManager.getInstance().acquire(writer.session().path().toString())) {
          assertInstanceOf(org.triplehelix.wpilogmcp.log.LiveLog.View.class, use.log());
          assertEquals("TEST", use.log().values().get("NT:/FMSInfo/EventName").get(0).value());
        }
        assertEquals("TEST", held.session().event()); assertEquals("Qualification", held.session().matchType());
        assertEquals(7, held.session().matchNumber());
        assertEquals(before, writer.session().path());
        assertEquals(0, attempts.get(), "The writer keeps a stable directory until close");
        var oldDirs = LogDirectory.getInstance().getLogDirectories();
        try {
          LogDirectory.getInstance().setLogDirectory(root.toString());
          var listed = LogDirectory.getInstance().scanLogs().logs().get(0);
          assertEquals("TEST", listed.eventName()); assertEquals(7, listed.matchNumber());
        } finally { LogDirectory.getInstance().setLogDirectories(oldDirs.stream().map(Path::toString).toList()); }
        writer.value(VALUE, new ValueFrame(1, 12_000_000, 2, 8L), 0);
        writer.disconnected(); placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1, attempts.get());
        if (windowsRefusal) {
          assertEquals(before, writer.session().path());
          assertEquals("TEST", StoreCatalog.read(root, security).files().get(0).session().event());
          allowMove.set(true);
          connect(writer, loop, 11_000_000, 1_000_000); writer.disconnected();
          placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
          assertEquals(2, attempts.get());
        }
        assertEquals("142233Z_TEST_Q7", writer.session().path().getParent().getFileName().toString());
        var finished = StoreCatalog.read(root, security);
        // A reconnect on the same boot continued the session in its next file.
        assertEquals(windowsRefusal ? 2 : 1, finished.files().size());
        var original = finished.files().stream().filter(f -> f.path().getFileName().toString().equals("capture.wpilog")).findFirst().orElseThrow();
        assertEquals(12, original.file().maxTimestampSec());
        assertEquals(StoreFiles.hash(original.path()), original.file().sha256());
        assertTrue(Files.exists(original.path())); assertFalse(Files.exists(before));
      }
    }
  }

  @Test void aBlockedStoreQueueDoesNotDelayValuesFlushesOrDisconnect() throws Exception {
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var threads = java.util.concurrent.Executors.newFixedThreadPool(2);
    try (var stores = new StoreRegistry(security)) {
      var store = stores.store(directory.resolve("store"));
      var placement = store.captures(WALL); var loop = new ManualScheduler();
      var flushed = new java.util.concurrent.atomic.AtomicInteger();
      var outputFlushes = new java.util.concurrent.atomic.AtomicInteger();
      var observer = new CaptureWriter.Observer() {
        @Override public Path create(String address, Instant start) throws java.io.IOException { return placement.create(address, start); }
        @Override public void opened(CaptureWriter.Session session) throws java.io.IOException { placement.opened(session); }
        @Override public void value(CaptureWriter.Session session, org.triplehelix.wpilogmcp.log.EntryInfo entry,
            ValueFrame frame, org.triplehelix.wpilogmcp.capture.WpilogOutput.Written written) throws java.io.IOException {
          placement.value(session, entry, frame, written);
        }
        @Override public void flushed(CaptureWriter.Session session) throws java.io.IOException {
          placement.flushed(session); flushed.incrementAndGet();
        }
        @Override public void closed(CaptureWriter.Session session) throws java.io.IOException { placement.closed(session); }
      };
      // The deadline detects a dependency on the blocked queue, not the speed of 40 disk forces
      // and remaps. Keep real record writes and the index; LiveLogTest covers actual flush/expiry.
      var index = new org.triplehelix.wpilogmcp.capture.CaptureIndex(observer, LogManager.getInstance(), 600_000_000);
      try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, index, CaptureWriter.DEFAULT_MAX_FILE_BYTES,
          (path, id, resume) -> new org.triplehelix.wpilogmcp.capture.WpilogOutput(path, id, resume) {
            @Override public void flush() { outputFlushes.incrementAndGet(); }
          })) {
        connect(writer, loop, 10_000_000, 0);
        var event = new Announce("/FMSInfo/EventName", 2, "string", null, new JsonObject()); writer.announce(event);
        var busy = threads.submit(() -> store.capture(io -> {
          entered.countDown();
          try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("queue test timed out"); }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
          return null;
        }));
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
        long queuedBefore = placement.queuedUpdates();
        var recording = threads.submit(() -> {
          for (int i = 0; i < 40; i++) {
            writer.value(VALUE, new ValueFrame(1, 10_000_000 + i * 250_000L, 2, (long) i), loop.nowUs());
            writer.value(event, new ValueFrame(2, 10_000_000 + i * 250_000L, 4, "TEST"), loop.nowUs());
            loop.advance(250_000); loop.until(() -> writer.session().observedAtUs() == loop.nowUs());
          }
          writer.disconnected();
          return null;
        });
        try {
          recording.get(2, java.util.concurrent.TimeUnit.SECONDS);
          assertEquals(queuedBefore + 1, placement.queuedUpdates(), "Only one pending queue task despite ten seconds of writes");
          assertFalse(placement.completion().isDone(), "Only shutdown waits for the final manifest");
          assertEquals(40, flushed.get()); assertEquals(40, index.live().sampleCount("NT:/x"));
          assertEquals(41, outputFlushes.get(), "Forty flush ticks plus close, all while the store is blocked");
          assertEquals(1, release.getCount(), "The queue must still be blocked when capture finishes");
        } finally { release.countDown(); recording.get(10, java.util.concurrent.TimeUnit.SECONDS); busy.get(10, java.util.concurrent.TimeUnit.SECONDS); }
        placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        var finished = StoreCatalog.read(store.root(), security);
        assertTrue(finished.openCaptures().isEmpty()); assertEquals(1, finished.files().size());
        var file = finished.files().get(0);
        assertEquals("TEST", file.session().event());
        assertEquals(10, file.file().minTimestampSec()); assertEquals(19.75, file.file().maxTimestampSec());
        assertEquals(Files.size(file.path()), file.file().sizeBytes()); assertEquals(StoreFiles.hash(file.path()), file.file().sha256());
      }
    } finally { release.countDown(); threads.shutdownNow(); }
  }

  @Test void manifestWritesFollowChangedFactsOrFiveSecondsNotEveryValueAndFlush() throws Exception {
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    try (var stores = new StoreRegistry(security)) {
      var store = stores.store(directory.resolve("store")); var placement = store.captures(WALL);
      var loop = new ManualScheduler();
      try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, placement)) {
        connect(writer, loop, 10_000_000, 0); placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        long initial = placement.manifestWrites();
        var event = new Announce("/FMSInfo/EventName", 2, "string", null, new JsonObject()); writer.announce(event);
        writer.value(event, new ValueFrame(2, 10_000_000, 4, "TEST"), 0);
        placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(initial + 1, placement.manifestWrites());
        for (int i = 0; i < 19; i++) {
          writer.value(event, new ValueFrame(2, 10_000_000 + i, 4, "TEST"), loop.nowUs());
          writer.value(VALUE, new ValueFrame(1, 10_000_000 + i, 2, (long) i), loop.nowUs());
          loop.advance(250_000); loop.until(() -> writer.session().observedAtUs() == loop.nowUs()); placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(initial + 1, placement.manifestWrites(), "Identical facts and 4.75 seconds need no manifest write");
        loop.advance(250_000); loop.until(() -> writer.session().observedAtUs() == loop.nowUs()); placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(initial + 2, placement.manifestWrites());
        var team = new Announce("/SystemStats/TeamNumber", 3, "int", null, new JsonObject());
        writer.announce(team); writer.value(team, new ValueFrame(3, 11_000_000, 2, 9999L), loop.nowUs());
        placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(initial + 3, placement.manifestWrites());
        assertEquals(9999, StoreCatalog.read(store.root(), security).openCaptures().get(0).session().teamNumber());
        writer.disconnected(); placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(initial + 4, placement.manifestWrites());
      }
    }
  }

  /**
   * An entry's name and type decide its role, and the store keeps the answer for each name: a
   * name that comes back with another type, after a code change on the robot, is decided again.
   */
  @Test void aMatchNumberPublishedAsTextIsNotTheMatchNumberAndItsNumberLaterIs() throws Exception {
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    try (var stores = new StoreRegistry(security)) {
      var store = stores.store(directory.resolve("store")); var placement = store.captures(WALL);
      var loop = new ManualScheduler();
      try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, placement)) {
        connect(writer, loop, 10_000_000, 0);
        var type = new Announce("/FMSInfo/MatchType", 2, "int", null, new JsonObject());
        var text = new Announce("/FMSInfo/MatchNumber", 3, "string", null, new JsonObject());
        writer.announce(type); writer.announce(text);
        writer.value(type, new ValueFrame(2, 10_000_000, 2, 2L), 0);
        writer.value(text, new ValueFrame(3, 10_000_000, 4, "7"), 0);
        placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertNull(StoreCatalog.read(store.root(), security).openCaptures().get(0).session().matchNumber());
        writer.disconnected(); placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        // The robot restarts with new code, which publishes the match number as a number.
        connect(writer, loop, 100_000, 1_000_000);
        var number = new Announce("/FMSInfo/MatchNumber", 3, "int", null, new JsonObject());
        writer.announce(type); writer.announce(number);
        writer.value(type, new ValueFrame(2, 100_000, 2, 2L), 1_000_000);
        writer.value(number, new ValueFrame(3, 100_000, 2, 42L), 1_000_000);
        placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        var open = StoreCatalog.read(store.root(), security).openCaptures().get(0).session();
        assertEquals("Qualification", open.matchType()); assertEquals(42, open.matchNumber());
      }
    }
  }

  @Test void rolloverManifestsHashEachFileAndServeOneLiveIndexPerFile() throws Exception {
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    var manager = LogManager.getInstance(); manager.addAllowedDirectory(directory);
    try (var stores = new StoreRegistry(security, manager)) {
      var store = stores.store(directory.resolve("store")); var placement = store.captures(WALL);
      var index = new org.triplehelix.wpilogmcp.capture.CaptureIndex(placement, manager, 0);
      var loop = new ManualScheduler();
      try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, index, 256)) {
        connect(writer, loop, 10_000_000, 0);
        for (int i = 0; i < 100; i++) writer.value(VALUE, new ValueFrame(1, 10_000_000 + i, 2, (long) i), i);
        placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        var open = StoreCatalog.read(store.root(), security);
        assertEquals(1, open.openCaptures().size()); assertTrue(open.files().size() > 2);
        assertEquals(writer.session().path().toRealPath(), open.openCaptures().get(0).path());
        try (var use = manager.acquire(writer.session().path().toString())) {
          assertInstanceOf(org.triplehelix.wpilogmcp.log.LiveLog.View.class, use.log());
          assertTrue(use.log().sampleCount("NT:/x") < 100);
          assertEquals(99L, use.log().values().get("NT:/x").get(use.log().sampleCount("NT:/x") - 1).value());
        }
        writer.disconnected(); placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        var closed = StoreCatalog.read(store.root(), security);
        assertTrue(closed.openCaptures().isEmpty()); assertEquals(open.files().size() + 1, closed.files().size());
        assertEquals(1, closed.files().stream().map(f -> f.session().id()).distinct().count());
        var all = new java.util.TreeMap<Long, Long>();
        for (var file : closed.files()) {
          assertEquals(StoreFiles.hash(file.path()), file.file().sha256());
          assertEquals(Files.size(file.path()), file.file().sizeBytes()); assertTrue(file.file().sizeBytes() <= 256);
          assertEquals("captured", file.file().provenance().kind());
          try (var use = manager.acquire(file.path().toString())) {
            assertEquals(use.log().minTimestamp(), file.file().minTimestampSec());
            assertEquals(use.log().maxTimestamp(), file.file().maxTimestampSec());
            for (var value : use.log().values().get("NT:/x")) assertNull(all.put(Math.round(value.timestamp() * 1_000_000), ((Number) value.value()).longValue()));
          }
        }
        assertEquals(100, all.size());
        for (int i = 0; i < 100; i++) assertEquals((long) i, all.get(10_000_000L + i));
      }
    } finally { manager.release(directory); }
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void eachSessionStartsItsOwnRolloverClockWatermark(boolean coalescedOpen) throws Exception {
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    var manager = LogManager.getInstance();
    try (var stores = new StoreRegistry(security, manager)) {
      var store = stores.store(directory.resolve("clock-watermarks")); var placement = store.captures(WALL);
      var loop = new ManualScheduler();
      var index = new org.triplehelix.wpilogmcp.capture.CaptureIndex(placement, manager, 0);
      var release = new java.util.concurrent.CountDownLatch(1);
      try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, index, 256)) {
        connect(writer, loop, 10_000_000, 0);
        for (int i = 0; writer.session().files().size() < 9; i++) {
          assertTrue(i < 500, "Fixture must roll ten files");
          writer.value(VALUE, new ValueFrame(1, 10_000_000 + i, 2, (long) i), i);
        }
        writer.disconnected(); settleClocks(store, placement);
        var previous = writer.session();
        assertEquals(10, previous.files().size());
        var first = StoreCatalog.read(store.root(), security).sessions().get(0).session();
        assertEquals(10, first.clocks().size());
        // A regressed robot clock opens a new Session, even though the wall clock is unchanged.
        if (coalescedOpen) {
          writer.connected(RobotAddress.uri("127.0.0.1", 5810, "test"), "networktables.first.wpi.edu");
          writer.timeSync(1_000_000, 0); writer.announce(VALUE);
          // Creation precedes this task; its opened/rollover updates all wait behind it.
          // The store must recognize the new session even if it never observes count zero.
          var entered = new java.util.concurrent.CountDownLatch(1);
          store.captureAsync(io -> {
            entered.countDown();
            try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("Fixture store hold timed out"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
            return null;
          });
          assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)); loop.until(writer::ready);
        } else connect(writer, loop, 1_000_000, 0);
        assertNotSame(previous, writer.session());
        for (int rolled = 1, i = 0; rolled <= 3; rolled++) {
          while (writer.session().files().size() < rolled) {
            assertTrue(i < 200, "Fixture must roll the new session");
            writer.value(VALUE, new ValueFrame(1, 1_000_000 + i, 2, (long) i), i++);
          }
          release.countDown();
          settleClocks(store, placement);
          var active = StoreCatalog.read(store.root(), security).openCaptures().get(0);
          assertEquals(rolled, active.session().files().size());
          assertEquals(rolled, active.session().clocks().size(),
              "Every closed file gets its clock record while the new session is still open");
          for (var record : active.session().clocks()) {
            var file = active.session().files().stream().filter(f -> f.path().equals(record.capture())).findFirst().orElseThrow();
            assertEquals(file.sha256(), record.captureSha256());
            assertTrue(Files.isRegularFile(active.path().getParent().resolve(record.path())));
          }
        }
        assertEquals(10, StoreCatalog.read(store.root(), security).sessions().stream()
            .filter(s -> s.session().id().equals(first.id())).findFirst().orElseThrow().session().clocks().size());
      } finally { release.countDown(); settleClocks(store, placement); }
    }
  }

  private static void settleClocks(LogStore store, CaptureStore placement) throws Exception {
    placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
    store.capture(io -> null); // Drain the completion callback before observing the clock worker.
    store.clocksSettled().get(10, java.util.concurrent.TimeUnit.SECONDS);
  }

  @Test void schemaSeedsGiveEachRolledFileItsOwnRangeInTheManifestAndTools() throws Exception {
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    var manager = LogManager.getInstance();
    try (var stores = new StoreRegistry(security, manager)) {
      var store = stores.store(directory.resolve("store")); var placement = store.captures(WALL);
      var loop = new ManualScheduler();
      var index = new org.triplehelix.wpilogmcp.capture.CaptureIndex(placement, manager, 0);
      try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, index, 1024)) {
        connect(writer, loop, 1_000_000, 0);
        var schema = new Announce("/.schema/struct:SeedPoint", 2, "structschema", null, new JsonObject());
        var point = new Announce("/point", 3, "struct:SeedPoint", null, new JsonObject());
        writer.announce(schema); writer.announce(point);
        writer.value(schema, new ValueFrame(2, 1_000_000, 5, "int32 x;".getBytes(java.nio.charset.StandardCharsets.UTF_8)), 0);
        var first = writer.session().path();
        writer.timeSync(20_000_000, 19_000_000);
        for (int i = 0; i < 200; i++) {
          var value = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(i).array();
          writer.value(point, new ValueFrame(3, 21_000_000 + i, 5, value), i);
        }
        assertNotEquals(first, writer.session().path());
        loop.advance(5_000_000); loop.until(() -> writer.session().observedAtUs() == loop.nowUs()); placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        var catalog = StoreCatalog.read(store.root(), security);
        for (var file : catalog.allFiles()) {
          if (file.path().equals(first.toRealPath())) continue;
          assertEquals(20.0, file.file().minTimestampSec(), "Manifest starts at the rollover seed, never the boot schema");
          try (var use = manager.acquire(file.path().toString())) {
            assertEquals(20.0, use.log().minTimestamp());
            assertEquals(20.0, use.log().values().get("NT:/.schema/struct:SeedPoint").get(0).timestamp());
          }
        }
        var tools = new org.triplehelix.wpilogmcp.mcp.ToolRegistry();
        org.triplehelix.wpilogmcp.tools.CoreTools.registerAll(tools);
        var args = new JsonObject(); args.addProperty("path", writer.session().path().toString());
        var listed = tools.getTool("list_entries").execute(args).getAsJsonObject();
        assertEquals(20.0, listed.getAsJsonObject("time_range_sec").get("start").getAsDouble());
        assertEquals(20.0, listed.getAsJsonObject("inputs").getAsJsonObject("session_time_range").get("start_sec").getAsDouble());
        writer.disconnected(); placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        var last = writer.session().path(); manager.unloadLog(last.toString());
        try (var use = manager.acquire(last.toString())) { assertEquals(20.0, use.log().minTimestamp()); }
      }
    }
  }

  @Test void aNewerStoreIsNeverWritten() throws Exception {
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    Files.writeString(directory.resolve("store.json"), "{\"format_version\":999}");
    try (var stores = new StoreRegistry(security)) {
      assertThrows(java.io.IOException.class, () -> stores.store(directory).captures(WALL).create("127.0.0.1", WALL.instant()));
      assertEquals("{\"format_version\":999}", Files.readString(directory.resolve("store.json")));
      assertFalse(Files.exists(directory.resolve("robots")));
    }
  }
}
