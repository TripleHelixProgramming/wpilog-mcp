/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import edu.wpi.first.util.datalog.DataLogReader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.*;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;

class CaptureAdmissionQueueTest {
  @TempDir Path directory;
  @Test void initialAnnounceAndValuesDoNotJoinARealImport() throws Exception {
    var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories();
    manager.addAllowedDirectory(directory);
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    var entered = new CountDownLatch(1);
    try (var stores = new StoreRegistry(security)) {
      var store = stores.store(directory.resolve("store"));
      var source = ImportFixture.write(directory.resolve("source.wpilog"), 19);
      var importing = store.importPaths(new LogStore.Request(List.of(source), false, null), progress -> {
        if (progress.phase().equals("inspecting")) {
          entered.countDown();
          try { Thread.sleep(750); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
      });
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      var loop = new ManualScheduler();
      var placement = store.captures(Clock.fixed(Instant.parse("2026-04-05T12:00:00Z"), ZoneOffset.UTC));
      var topic = new Announce("/ordered", 1, "int", null, new JsonObject());
      try (var writer = new CaptureWriter(Clock.fixed(Instant.parse("2026-04-05T12:00:00Z"), ZoneOffset.UTC), loop,
          CapturePolicy.ALL, new CaptureIndex(placement, manager, 0))) {
        writer.connected(java.net.URI.create("ws://localhost/nt/test"), "networktables.first.wpi.edu");
        writer.timeSync(1_000_000, 0);
        long start = System.nanoTime(); writer.announce(topic);
        double waitMs = (System.nanoTime() - start) / 1e6;
        System.out.println("S7 generic import announce wait ms=" + waitMs);
        writer.value(topic, new ValueFrame(1, 1_000_100, 2, 17L), 100);
        writer.disconnected();
        importing.get(10, TimeUnit.SECONDS);
        loop.until(() -> writer.session() != null && !writer.session().open());
        placement.completion().get(10, TimeUnit.SECONDS);
        var values = new java.util.ArrayList<Long>();
        for (var record : org.triplehelix.wpilogmcp.fixtures.CaptureRecords.robot(writer.session().path()))
          { assertEquals(1_000_100, record.getTimestamp()); values.add(record.getInteger()); }
        assertEquals(List.of(17L), values);
        assertTrue(waitMs < 250, "First announce must not wait behind the 750 ms import: " + waitMs);
      }
    } finally { manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  }
  @Test void cancelledResumeRetainsTheAlreadyClosedFileAndNamesTheFailure() throws Exception {
    var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(directory);
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    try (var stores = new StoreRegistry(security)) {
      var store = stores.store(directory.resolve("resume-store")); var wall = Clock.fixed(Instant.parse("2026-04-05T12:00:00Z"), ZoneOffset.UTC);
      var placement = store.captures(wall); var loop = new ManualScheduler();
      var writer = new CaptureWriter(wall, loop, CapturePolicy.ALL, placement);
      var topic = new Announce("/resume", 1, "int", null, new JsonObject());
      writer.connected(java.net.URI.create("ws://localhost/nt/test"), "networktables.first.wpi.edu");
      writer.timeSync(1_000_000, 0); writer.announce(topic); loop.until(writer::ready);
      writer.value(topic, new ValueFrame(1, 1_000_000, 2, 15L), 0); writer.disconnected();
      placement.completion().get(10, TimeUnit.SECONDS);
      var before = StoreCatalog.read(store.root(), security).files().get(0); byte[] bytes = Files.readAllBytes(before.path());
      var path = placement.createAsync("localhost", wall.instant(), writer.session(), null).get(10, TimeUnit.SECONDS);
      placement.abandoned(path, wall.instant().plusSeconds(30), "admission deadline fixture").get(10, TimeUnit.SECONDS);
      var after = StoreCatalog.read(store.root(), security);
      assertTrue(after.openCaptures().isEmpty()); assertEquals(1, after.files().size());
      assertEquals(before.file(), after.files().get(0).file()); assertArrayEquals(bytes, Files.readAllBytes(path));
      assertEquals("admission deadline fixture", after.files().get(0).session().endReason());
      assertEquals(before.session().endedAt(), after.files().get(0).session().endedAt());
    } finally { manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  }

  @Test void aFailedAdmissionDoesNotPoisonTheNextCreationAfterStorageIsRepaired() throws Exception {
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    try (var stores = new StoreRegistry(security)) {
      var store = stores.store(directory.resolve("repair-store")); var placement = store.captures(Clock.systemUTC());
      var blocker = store.root().resolve("robots"); Files.writeString(blocker, "not a directory");
      assertThrows(java.util.concurrent.ExecutionException.class,
          () -> placement.createAsync("localhost", Instant.parse("2026-04-05T12:00:00Z"), null, null).get(10, TimeUnit.SECONDS));
      Files.delete(blocker);
      var path = assertDoesNotThrow(() -> placement.createAsync("localhost", Instant.parse("2026-04-05T12:00:01Z"), null, null).get(10, TimeUnit.SECONDS));
      assertTrue(Files.isRegularFile(path.getParent().resolve("session.json")));
    }
  }

  @Test void delayedOpenPublicationKeepsTheOriginalCalendarWindow() throws Exception {
    var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(directory);
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    var now = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-04-05T12:00:00Z"));
    var wall = new Clock() {
      public Instant instant() { return now.get(); }
      public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
      public Clock withZone(java.time.ZoneId zone) { return this; }
    };
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    try (var stores = new StoreRegistry(security)) {
      var store = stores.store(directory.resolve("clock-store")); var placement = store.captures(wall); var loop = new ManualScheduler();
      var blocked = store.captureAsync(io -> {
        entered.countDown(); try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new java.io.IOException(e); }
        return null;
      });
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      var writer = new CaptureWriter(wall, loop, CapturePolicy.ALL, placement);
      var topic = new Announce("/clock", 1, "int", null, new JsonObject());
      writer.connected(java.net.URI.create("ws://localhost/nt/test"), "networktables.first.wpi.edu");
      writer.timeSync(1_000_000, 0); writer.announce(topic);
      for (int i = 0; i < 300; i++) writer.value(topic, new ValueFrame(1, 1_000_000 + i, 2, (long) i), i);
      now.set(Instant.parse("2026-04-05T12:00:02Z")); loop.elapse(2_000_000); writer.disconnected();
      now.set(Instant.parse("2026-04-05T12:00:20Z")); release.countDown(); blocked.get(10, TimeUnit.SECONDS);
      loop.until(() -> writer.session() != null);
      placement.completion().get(10, TimeUnit.SECONDS); // Persist opened before the second replay slice closes.
      loop.drain(); placement.completion().get(10, TimeUnit.SECONDS);
      var session = StoreCatalog.read(store.root(), security).files().get(0).session();
      assertEquals("2026-04-05T12:00:00Z", session.startedAt());
      assertEquals("2026-04-05T12:00:02Z", session.endedAt());
    } finally { release.countDown(); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  }

  @Test void delayedResumeKeepsTheOriginalCalendarWindow() throws Exception {
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    var now = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-04-05T12:00:00Z"));
    var wall = new Clock() {
      public Instant instant() { return now.get(); }
      public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
      public Clock withZone(java.time.ZoneId zone) { return this; }
    };
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    try (var stores = new StoreRegistry(security)) {
      var store = stores.store(directory.resolve("resume-clock-store")); var placement = store.captures(wall); var loop = new ManualScheduler();
      var writer = new CaptureWriter(wall, loop, CapturePolicy.ALL, placement);
      var topic = new Announce("/clock", 1, "int", null, new JsonObject());
      writer.connected(java.net.URI.create("ws://localhost/nt/test"), "networktables.first.wpi.edu");
      writer.timeSync(1_000_000, 0); writer.announce(topic); loop.until(writer::ready);
      writer.value(topic, new ValueFrame(1, 1_000_001, 2, 1L), 1);
      now.set(Instant.parse("2026-04-05T12:00:02Z")); loop.elapse(2_000_000); writer.disconnected();
      placement.completion().get(10, TimeUnit.SECONDS);
      var blocked = store.captureAsync(io -> {
        entered.countDown(); try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new java.io.IOException(e); }
        return null;
      });
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      now.set(Instant.parse("2026-04-05T12:00:03Z")); loop.elapse(1_000_000);
      writer.connected(java.net.URI.create("ws://localhost/nt/test"), "networktables.first.wpi.edu");
      writer.timeSync(4_000_000, 3_000_000); writer.announce(topic);
      writer.value(topic, new ValueFrame(1, 4_000_001, 2, 2L), 3_000_001);
      now.set(Instant.parse("2026-04-05T12:00:04Z")); loop.elapse(1_000_000); writer.disconnected();
      now.set(Instant.parse("2026-04-05T12:00:20Z")); release.countDown(); blocked.get(10, TimeUnit.SECONDS);
      loop.until(() -> writer.admissionCompletion().isDone()); placement.completion().get(10, TimeUnit.SECONDS);
      var catalog = StoreCatalog.read(store.root(), security);
      assertEquals(1, catalog.sessions().size());
      assertEquals("2026-04-05T12:00:04Z", catalog.files().get(0).session().endedAt());
    } finally { release.countDown(); }
  }

}
