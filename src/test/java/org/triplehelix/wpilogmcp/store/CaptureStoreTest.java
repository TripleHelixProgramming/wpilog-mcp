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
  private static void connect(CaptureWriter writer, long time, long received) {
    writer.connected(RobotAddress.uri("127.0.0.1", 5810, "test"), "networktables.first.wpi.edu");
    writer.timeSync(time, received); writer.announce(VALUE);
  }

  @Test void openClosedResumeAndSameSecondSessionsKeepTheirFactsAndBytes() throws Exception {
    directory = directory.toRealPath();
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    try (var stores = new StoreRegistry(security)) {
      var root = directory.resolve("store"); var loop = new ManualScheduler();
      var placement = stores.store(root).captures(WALL);
      try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, placement)) {
        connect(writer, 10_000_000, 0);
        assertEquals(root.resolve("robots/address-127.0.0.1/sessions/2026-03-07/142233Z/capture.wpilog"), writer.session().path());
        writer.value(VALUE, new ValueFrame(1, 10_000_000, 2, 5L), 0); loop.advance(5_000_000); placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
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
        connect(writer, 11_000_000, 1_000_000);
        writer.value(VALUE, new ValueFrame(1, 11_000_000, 2, 6L), 1_000_000); writer.disconnected(); placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        closed = StoreCatalog.read(root, security); assertEquals(1, closed.files().size());
        assertEquals(first.session().id(), closed.files().get(0).session().id());
        assertEquals(11, closed.files().get(0).file().maxTimestampSec());
        connect(writer, 1_000_000, 2_000_000); writer.disconnected(); placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("142233Z_2", writer.session().path().getParent().getFileName().toString());
        assertEquals(2, StoreCatalog.read(root, security).files().size());
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
        connect(writer, 10_000_000, 0); var before = writer.session().path();
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
          connect(writer, 11_000_000, 1_000_000); writer.disconnected();
          placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
          assertEquals(2, attempts.get());
        }
        assertEquals("142233Z_TEST_Q7", writer.session().path().getParent().getFileName().toString());
        var finished = StoreCatalog.read(root, security); assertEquals(1, finished.files().size());
        assertEquals(12, finished.files().get(0).file().maxTimestampSec());
        assertEquals(StoreFiles.hash(writer.session().path()), finished.files().get(0).file().sha256());
        assertTrue(Files.exists(writer.session().path())); assertFalse(Files.exists(before));
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
        @Override public void opened(CaptureWriter.Session session, boolean resumed) throws java.io.IOException { placement.opened(session, resumed); }
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
        connect(writer, 10_000_000, 0);
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
            loop.advance(250_000);
          }
          writer.disconnected();
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
        connect(writer, 10_000_000, 0); placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        long initial = placement.manifestWrites();
        var event = new Announce("/FMSInfo/EventName", 2, "string", null, new JsonObject()); writer.announce(event);
        writer.value(event, new ValueFrame(2, 10_000_000, 4, "TEST"), 0);
        placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(initial + 1, placement.manifestWrites());
        for (int i = 0; i < 19; i++) {
          writer.value(event, new ValueFrame(2, 10_000_000 + i, 4, "TEST"), loop.nowUs());
          writer.value(VALUE, new ValueFrame(1, 10_000_000 + i, 2, (long) i), loop.nowUs());
          loop.advance(250_000); placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(initial + 1, placement.manifestWrites(), "Identical facts and 4.75 seconds need no manifest write");
        loop.advance(250_000); placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
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

  @Test void rolloverManifestsHashEachFileAndServeOneLiveIndexPerFile() throws Exception {
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    var manager = LogManager.getInstance(); manager.addAllowedDirectory(directory);
    try (var stores = new StoreRegistry(security, manager)) {
      var store = stores.store(directory.resolve("store")); var placement = store.captures(WALL);
      var index = new org.triplehelix.wpilogmcp.capture.CaptureIndex(placement, manager, 0);
      try (var writer = new CaptureWriter(WALL, new ManualScheduler(), CapturePolicy.ALL, index, 256)) {
        connect(writer, 10_000_000, 0);
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

  @Test void schemaSeedsGiveEachRolledFileItsOwnRangeInTheManifestAndTools() throws Exception {
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    var manager = LogManager.getInstance();
    try (var stores = new StoreRegistry(security, manager)) {
      var store = stores.store(directory.resolve("store")); var placement = store.captures(WALL);
      var loop = new ManualScheduler();
      var index = new org.triplehelix.wpilogmcp.capture.CaptureIndex(placement, manager, 0);
      try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, index, 1024)) {
        connect(writer, 1_000_000, 0);
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
        loop.advance(5_000_000); placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
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
