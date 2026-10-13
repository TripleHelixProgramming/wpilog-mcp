/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.*;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.read.ReadQuery;
import org.triplehelix.wpilogmcp.read.http.RemoteLogAccess;
import org.triplehelix.wpilogmcp.store.StoreJson;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

/** Exercise the real HTTP wiring: a session must borrow the same published prefix as one file. */
class SessionReadTest {
  @TempDir Path temp;

  @Test void sessionBorrowsLivePrefixWhileRecordingGrowsAndNextOpenSeesNewSamples() throws Exception {
    var root = temp.toRealPath();
    var robot = root.resolve("robots/fixture");
    var directory = Files.createDirectories(robot.resolve("sessions/2026-01-01/session"));
    var path = directory.resolve("capture.wpilog");
    String when = "2026-01-01T00:00:00Z";
    Files.writeString(root.resolve("store.json"), StoreJson.JSON.toJson(new Header(1, when, "store", List.of())));
    Files.writeString(robot.resolve("robot.json"), StoreJson.JSON.toJson(new Robot("fixture", "fixture", null, null, "logged")));
    var manager = LogManager.getInstance(); manager.resetConfiguration(); manager.addAllowedDirectory(root);
    var index = new CaptureIndex((address, start) -> path, manager, 10_000_000);
    var topic = new Announce("/x", 1, "int", null, new JsonObject());
    var transport = new HttpTransport(new ToolRegistry(), 0);
    transport.setStoreDirectories(java.util.Set.of(root));
    try (var writer = new CaptureWriter(Clock.systemUTC(), new ManualScheduler(), CapturePolicy.ALL, index)) {
      writer.connected(RobotAddress.uri("127.0.0.1", 5810, "session-read-test"), "networktables.first.wpi.edu");
      writer.timeSync(1_000_000, 0); writer.announce(topic);
      writer.value(topic, new ValueFrame(1, 1_000_001, 2, 41L), 0);
      var open = new OpenCapture("capture.wpilog", new Provenance("captured", null, "capture.wpilog", when, false),
          Files.size(path), 1.000001, 1.000001);
      Files.writeString(directory.resolve("session.json"), StoreJson.JSON.toJson(new Session(
          "session", when, when, "pit_clock", null, null, null, null, List.of(), open)));
      transport.start();
      try (var remote = new RemoteLogAccess(URI.create("http://127.0.0.1:" + transport.getPort()));
           var first = remote.openSession("store", "session", "capture.wpilog", org.triplehelix.wpilogmcp.read.catalog.SessionSnapshot.Overlap.KEEP_ALL)) {
        assertTrue(first.live(), "Session admission must use the manager's writer-owned live prefix");
        writer.value(topic, new ValueFrame(1, 2_000_001, 2, 42L), 0);
        try (var held = first.read(ReadQuery.all("NT:/x"))) {
          assertEquals(List.of(41L), held.next().samples().stream().map(s -> s.value()).toList());
        }
        try (var second = remote.openSession("store", "session", "capture.wpilog", org.triplehelix.wpilogmcp.read.catalog.SessionSnapshot.Overlap.KEEP_ALL);
             var current = second.read(ReadQuery.all("NT:/x"))) {
          assertEquals(List.of(41L, 42L), current.next().samples().stream().map(s -> s.value()).toList());
        }
        // The manifest still ends at the first sample. It cannot prove that the current
        // live prefix is disjoint from this second recording of the same entry.
        var other = directory.resolve("other.wpilog");
        try (var prefix = manager.readAccess().open(path);
             var file = new org.triplehelix.wpilogmcp.fixtures.WpilogWriter(other, "")) {
          var entry = prefix.entries().get("NT:/x");
          int id = file.start(entry.name(), entry.type(), entry.metadata(), 0);
          file.append(id, 2_000_001, org.triplehelix.wpilogmcp.fixtures.WpilogWriter.encodeInt64(99));
        }
        var hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(other)));
        var closed = new LogFile("other.wpilog", hash, Files.size(other), "wpilog", open.provenance(), true,
            2.000001, 2.000001, when, when, "pit_clock", false, null);
        Files.writeString(directory.resolve("session.json"), StoreJson.JSON.toJson(new Session(
            "session", when, when, "pit_clock", null, null, null, null, List.of(closed), open)));
        var failure = assertThrows(java.io.IOException.class, () -> remote.openSession("store", "session", "capture.wpilog",
            org.triplehelix.wpilogmcp.read.catalog.SessionSnapshot.Overlap.REJECT));
        assertTrue(failure.getMessage().contains("KEEP_ALL"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Potentially overlapping live recordings"), failure.getMessage());
        try (var both = remote.openSession("store", "session", "capture.wpilog",
                 org.triplehelix.wpilogmcp.read.catalog.SessionSnapshot.Overlap.KEEP_ALL);
             var samples = both.read(ReadQuery.all("NT:/x"))) {
          assertEquals(List.of(41L, 42L, 99L), samples.next().samples().stream().map(s -> s.value()).toList());
        }
      }
    } finally { transport.stop(); manager.resetConfiguration(); }
  }
}
