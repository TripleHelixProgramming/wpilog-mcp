/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.CaptureIndex;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.columns.ColumnFile;
import org.triplehelix.wpilogmcp.fixtures.FixtureWriter;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.read.FieldPath;

/**
 * A capture's column file is built when it closes, from its file. A closed capture is still
 * served from memory, and that copy names no proven content, so the build reads the file
 * itself; a session with its match facts is renamed at close and read at its new place. A
 * capture still recording has no such read.
 */
class CaptureColumnBuildTest {
  private static final Clock WALL = Clock.fixed(Instant.parse("2026-03-07T14:22:33Z"), ZoneOffset.UTC);
  private static final Announce VALUE = new Announce("/x", 1, "int", null, new JsonObject());
  private static final int VALUES = 20;
  @TempDir Path directory;
  private LogManager manager;

  @BeforeEach
  void setup() throws Exception {
    directory = directory.toRealPath();
    manager = IsolatedLogManagers.create(directory, "capture-columns");
    manager.startColumnBuilds(() -> Long.MAX_VALUE, 5);
  }

  @AfterEach
  void stop() {
    manager.shutdown();
  }

  /** Connects, then records {@code /x} as 0, 3, ..., 57, every 20 ms from the robot's 10 s. */
  private static void record(CaptureWriter writer, ManualScheduler loop) throws InterruptedException {
    writer.connected(RobotAddress.uri("127.0.0.1", 5810, "test"), "networktables.first.wpi.edu");
    writer.timeSync(10_000_000, 0);
    writer.announce(VALUE);
    loop.until(writer::ready);
    for (int i = 0; i < VALUES; i++) writer.value(VALUE, new ValueFrame(1, 10_000_000 + i * 20_000L, 2, 3L * i), 0);
  }

  private static void announceMatch(CaptureWriter writer) {
    var names = List.of("/FMSInfo/EventName", "/FMSInfo/MatchType", "/FMSInfo/MatchNumber");
    for (int i = 0; i < names.size(); i++) {
      var topic = new Announce(names.get(i), i + 2, i == 0 ? "string" : "int", null, new JsonObject());
      writer.announce(topic);
      writer.value(topic, new ValueFrame(i + 2, 10_000_000, i == 0 ? 4 : 2, i == 0 ? "TEST" : i == 1 ? 2L : 7L), 0);
    }
  }

  private static String sha256(Path file) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
  }

  private static void awaitFile(Path file) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!Files.exists(file)) {
      assertTrue(System.nanoTime() < deadline, "no column file appeared");
      Thread.sleep(5);
    }
  }

  /** The column file holds the recorded values, at the times the file's own decode gives them. */
  private void assertHoldsTheRecording(Path capture, String hash) throws Exception {
    var target = manager.getDiskCache().columnFilePath(hash).orElseThrow();
    manager.unloadLog(capture.toString());
    try (var columns = ColumnFile.open(target, "sha256:" + hash); var use = manager.acquireReadOnly(capture.toString())) {
      assertInstanceOf(LazyParsedLog.class, use.log(), "unloaded, the capture is read from its file");
      var decoded = use.log().values().get("NT:/x");
      var selection = columns.select("NT:/x", FieldPath.parse("")).orElseThrow();
      var samples = selection.samples(selection.all());
      assertEquals(VALUES, samples.size());
      assertEquals(VALUES, decoded.size());
      for (int i = 0; i < VALUES; i++) {
        assertEquals(3.0 * i, samples.number(i));
        assertEquals(decoded.get(i).timestampUs(), samples.timeUs(i));
      }
    }
  }

  @Test
  void aClosedCaptureIsBuiltFromItsFileWhileItsCopyInMemoryServesReads() throws Exception {
    var store = manager.stores().store(directory.resolve("store"));
    var placement = store.captures(WALL);
    var loop = new ManualScheduler();
    try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, new CaptureIndex(placement, manager, 0))) {
      record(writer, loop);
      writer.disconnected();
      placement.completion().get(10, TimeUnit.SECONDS);
      var capture = writer.session().path();
      var hash = sha256(capture);
      awaitFile(manager.getDiskCache().columnFilePath(hash).orElseThrow());
      try (var use = manager.acquireReadOnly(capture.toString())) {
        assertInstanceOf(LiveLog.View.class, use.log(), "the build left the copy in memory serving reads");
      }
      assertHoldsTheRecording(capture, hash);
    }
  }

  @Test
  void aCaptureRenamedForItsMatchAtCloseIsBuiltAtItsNewPlace() throws Exception {
    var store = manager.stores().store(directory.resolve("store"));
    var placement = store.captures(WALL);
    var loop = new ManualScheduler();
    try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, new CaptureIndex(placement, manager, 0))) {
      record(writer, loop);
      announceMatch(writer);
      placement.completion().get(10, TimeUnit.SECONDS);
      writer.disconnected();
      placement.completion().get(10, TimeUnit.SECONDS);
      var capture = writer.session().path();
      assertEquals("142233Z_TEST_Q7", capture.getParent().getFileName().toString(), "renamed for its match");
      var hash = sha256(capture);
      awaitFile(manager.getDiskCache().columnFilePath(hash).orElseThrow());
      assertHoldsTheRecording(capture, hash);
    }
  }

  @Test
  void aCaptureStillRecordingIsRefusedAReadFromItsFile() throws Exception {
    var store = manager.stores().store(directory.resolve("store"));
    var placement = store.captures(WALL);
    var loop = new ManualScheduler();
    try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, new CaptureIndex(placement, manager, 0))) {
      record(writer, loop);
      placement.completion().get(10, TimeUnit.SECONDS);
      var capture = writer.session().path();
      var refused = assertThrows(LogFileException.class, () -> manager.acquireFromFile(capture.toString()));
      assertTrue(refused.getMessage().contains("Still recording"), refused.getMessage());
      writer.disconnected();
      placement.completion().get(10, TimeUnit.SECONDS);
    }
  }

  @Test
  void aClosedCapturesFileReadIsPrivateAndClosedWithItsUse() throws Exception {
    var store = manager.stores().store(directory.resolve("store"));
    var placement = store.captures(WALL);
    var loop = new ManualScheduler();
    try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, new CaptureIndex(placement, manager, 0))) {
      record(writer, loop);
      writer.disconnected();
      placement.completion().get(10, TimeUnit.SECONDS);
      var capture = writer.session().path();
      LogData fromFile;
      try (var use = manager.acquireFromFile(capture.toString())) {
        fromFile = use.log();
        assertInstanceOf(LazyParsedLog.class, fromFile, "read from the file, not the copy in memory");
        assertEquals(VALUES, fromFile.values().get("NT:/x").size());
      }
      var read = (LazyParsedLog) fromFile;
      assertThrows(IllegalStateException.class, () -> read.records("NT:/x", false), "released with its use");
      try (var use = manager.acquireReadOnly(capture.toString())) {
        assertInstanceOf(LiveLog.View.class, use.log(), "the private read was never cached");
      }
    }
  }

  @Test
  void aFileAlreadyReadFromDiskIsReadThroughItsCachedLog() throws Exception {
    var plain = directory.resolve("plain.wpilog");
    try (var w = new FixtureWriter(plain, "")) {
      for (int i = 0; i < 10; i++) w.dbl("/v", 1 + i * 0.1, i);
    }
    try (var cached = manager.acquireReadOnly(plain.toString()); var fromFile = manager.acquireFromFile(plain.toString())) {
      assertSame(cached.log(), fromFile.log());
    }
    try (var again = manager.acquireReadOnly(plain.toString())) {
      assertEquals(10, again.log().values().get("/v").size(), "the cached log stays open after the use");
    }
  }
}
