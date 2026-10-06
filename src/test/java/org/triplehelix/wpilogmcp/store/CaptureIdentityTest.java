/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.*;
import org.triplehelix.wpilogmcp.capture.context.DeviceIdentity;
import org.triplehelix.wpilogmcp.log.*;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;

class CaptureIdentityTest {
  @TempDir Path temp;
  static final Clock WALL = Clock.fixed(Instant.parse("2026-03-07T14:22:33Z"), ZoneOffset.UTC);
  static final Announce VALUE = new Announce("/x", 1, "int", null, new JsonObject());
  private Set<Path> allowed;
  @org.junit.jupiter.api.BeforeEach void allow() throws Exception {
    temp = temp.toRealPath();
    allowed = LogManager.getInstance().getAllowedDirectories(); LogManager.getInstance().addAllowedDirectory(temp);
  }
  @org.junit.jupiter.api.AfterEach void restore() throws Exception {
    var manager = LogManager.getInstance(); manager.release(temp); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory);
  }
  static DeviceIdentity device(String serial, String key) {
    return new DeviceIdentity(serial, "fixture robot", "127.0.0.1", key,
        Map.of("serial_source", "/proc/42/environ:serialnum", "comments_source", "/etc/machine-info:PRETTY_HOSTNAME"));
  }
  static void connect(CaptureWriter writer, long time, long received) {
    writer.connected(RobotAddress.uri("127.0.0.1", 5810, "identity"), "networktables.first.wpi.edu");
    writer.timeSync(time, received); writer.announce(VALUE);
  }
  private SecurityValidator security() { var result = new SecurityValidator(); result.addAllowedDirectory(temp); return result; }

  @Test void knownIdentityIsTheFirstContextAtStartAndResumeAndSurvivesReload() throws Exception {
    var root = temp.resolve("store"); var security = security(); var loop = new ManualScheduler();
    try (var stores = new StoreRegistry(security)) {
      var placement = stores.store(root).captures(WALL); var index = new CaptureIndex(placement, LogManager.getInstance(), 0);
      try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, index)) {
        var identity = device("SYNTHETIC-A", "SHA256:fixture-a"); writer.identity(identity);
        connect(writer, 10_000_000, 0); var path = writer.session().path();
        assertTrue(path.startsWith(root.resolve("robots").resolve("SYNTHETIC-A")));
        writer.value(VALUE, new ValueFrame(1, 10_000_000, 2, 7L), 0);
        writer.disconnected(); placement.completion().get(10, TimeUnit.SECONDS);
        connect(writer, 12_000_000, 2_000_000); writer.value(VALUE, new ValueFrame(1, 12_000_000, 2, 9L), 2_000_000);
        try (var use = LogManager.getInstance().acquire(path.toString())) {
          assertEquals("/Daemon/Robot/Identity", use.log().entries().keySet().iterator().next());
          var values = use.log().values().get("/Daemon/Robot/Identity");
          assertEquals(List.of(10.0, 12.0), values.stream().map(TimestampedValue::timestamp).toList());
          assertEquals(identity.json(), JsonParser.parseString((String) values.get(1).value()));
        }
        writer.disconnected(); placement.completion().get(10, TimeUnit.SECONDS); LogManager.getInstance().release(path);
        try (var reader = new ScopedLogReader(path); var log = new LazyParsedLog(path.toString(), reader.reader(), 1 << 20)) {
          assertEquals(List.of(10.0, 12.0), log.values().get("/Daemon/Robot/Identity").stream().map(TimestampedValue::timestamp).toList());
          assertEquals("json", log.entries().get("/Daemon/Robot/Identity").type());
        }
        var stored = StoreCatalog.read(root, security).files().get(0);
        assertEquals(identity, stored.session().deviceIdentity()); assertEquals("device", stored.robot().basis());
        assertEquals("SYNTHETIC-A", stored.robot().serialNumber()); assertEquals(1, stored.robot().contacts().size());
      }
    }
  }

  @Test void learningIdentityMovesTheAddressSessionWithAnOpenMappingAndOldPathsStillLoad() throws Exception {
    var root = temp.resolve("store"); var security = security(); var loop = new ManualScheduler();
    try (var stores = new StoreRegistry(security)) {
      var placement = stores.store(root).captures(WALL); var index = new CaptureIndex(placement, LogManager.getInstance(), 0);
      try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, index)) {
        connect(writer, 10_000_000, 0); var old = writer.session().path();
        writer.value(VALUE, new ValueFrame(1, 10_000_000, 2, 7L), 0); loop.advance(250_000);
        try (var use = LogManager.getInstance().acquire(old.toString())) { assertEquals(7L, use.log().values().get("NT:/x").get(0).value()); }
        placement.completion().get(10, TimeUnit.SECONDS);
        String session = StoreCatalog.read(root, security).openCaptures().get(0).session().id();
        writer.identity(device("SYNTHETIC-A", "SHA256:fixture-a"));
        assertTrue(writer.session().open(), writer.session().endReason());
        var next = writer.session().path(); assertEquals("capture-2.wpilog", next.getFileName().toString());
        assertTrue(next.startsWith(root.resolve("robots").resolve("SYNTHETIC-A"))); assertFalse(Files.exists(old));
        writer.value(VALUE, new ValueFrame(1, 11_000_000, 2, 8L), 0); writer.disconnected();
        placement.completion().get(10, TimeUnit.SECONDS);
        var snapshot = StoreCatalog.read(root, security); assertEquals(2, snapshot.files().size());
        assertTrue(snapshot.files().stream().allMatch(f -> f.session().id().equals(session)));
        assertEquals(1, snapshot.robots().size()); assertTrue(snapshot.unmanaged().isEmpty());
        var move = snapshot.header().moves().stream().filter(m -> Path.of(m.originalPath()).equals(old)).findFirst().orElseThrow();
        assertTrue(Files.exists(root.resolve(move.movedTo())));
        try (var use = LogManager.getInstance().acquire(old.toString())) {
          assertEquals(7L, use.log().values().get("NT:/x").get(0).value());
          var beforeCall = LogManager.getInstance().snapshotOf(old.toString(), use.log()); assertNotNull(beforeCall);
          assertNull(LogManager.getInstance().changeDuringCall(old.toString(), use.log(), beforeCall));
          Files.setLastModifiedTime(Path.of(use.log().path()), java.nio.file.attribute.FileTime.from(WALL.instant().plusSeconds(60)));
          assertNotNull(LogManager.getInstance().changeDuringCall(old.toString(), use.log(), beforeCall));
        }
        // The address mapping survives a new writer and puts later boots under the serial immediately.
        var later = stores.store(root).captures(WALL).create("127.0.0.1", WALL.instant().plusSeconds(1));
        assertTrue(later.startsWith(root.resolve("robots").resolve("SYNTHETIC-A")));
      }
    }
  }

  @Test void disagreementIsManifestedAndLoggedWhileTheFilesLoggedSerialWins() throws Exception {
    var root = temp.resolve("store"); var security = security(); var loop = new ManualScheduler();
    var oldDirs = LogDirectory.getInstance().getLogDirectories();
    var err = System.err; var messages = new java.io.ByteArrayOutputStream();
    try (var stores = new StoreRegistry(security); var output = new java.io.PrintStream(messages)) {
      System.setErr(output);
      var placement = stores.store(root).captures(WALL);
      try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, placement)) {
        writer.identity(device("SYNTHETIC-A", "SHA256:fixture-a")); connect(writer, 10_000_000, 0);
        var serial = new Announce("/SystemStats/SerialNumber", 2, "string", null, new JsonObject());
        writer.announce(serial); writer.value(serial, new ValueFrame(2, 10_000_000, 4, "SYNTHETIC-B"), 0);
        writer.disconnected(); placement.completion().get(10, TimeUnit.SECONDS);
        var stored = StoreCatalog.read(root, security).files().get(0);
        assertEquals(List.of(new StoreManifest.IdentityConflict("capture.wpilog", "SYNTHETIC-B", "SYNTHETIC-A")), stored.session().identityConflicts());
        assertTrue(messages.toString().contains("identity disagreement"));
        LogDirectory.getInstance().setLogDirectory(root.toString());
        var tools = new org.triplehelix.wpilogmcp.mcp.ToolRegistry(); org.triplehelix.wpilogmcp.tools.CoreTools.registerAll(tools);
        var row = tools.getTool("list_available_logs").execute(new JsonObject()).getAsJsonObject().getAsJsonArray("logs").get(0).getAsJsonObject();
        assertEquals("SYNTHETIC-B", row.getAsJsonObject("robot").get("serial_number").getAsString());
        assertEquals("logged", row.getAsJsonObject("robot").get("basis").getAsString());
      }
    } finally { System.setErr(err); LogDirectory.getInstance().setLogDirectories(oldDirs.stream().map(Path::toString).toList()); }
  }

  @Test void hostKeyChangesPreserveRobotHistoryAndAnAddressCanLearnANewSerial() throws Exception {
    var root = temp.resolve("store"); var security = security();
    try (var stores = new StoreRegistry(security)) {
      var store = stores.store(root); store.identify(device("SYNTHETIC-A", "SHA256:first"), WALL).get(10, TimeUnit.SECONDS);
      var state = root.resolve("robots").resolve("SYNTHETIC-A").resolve("pull.json");
      Files.writeString(state, "synthetic transfer state");
      var err = System.err; var messages = new java.io.ByteArrayOutputStream();
      try (var output = new java.io.PrintStream(messages)) {
        System.setErr(output); store.identify(device("SYNTHETIC-A", "SHA256:second"), WALL).get(10, TimeUnit.SECONDS);
      } finally { System.setErr(err); }
      assertEquals("synthetic transfer state", Files.readString(state));
      assertTrue(messages.toString().contains("SSH host key changed"));
      assertEquals(List.of("SHA256:first", "SHA256:second"), StoreCatalog.read(root, security).robots().get(0).robot().contacts()
          .stream().map(StoreManifest.Contact::hostKeyFingerprint).toList());
      store.identify(device("SYNTHETIC-B", "SHA256:third"), WALL).get(10, TimeUnit.SECONDS);
      assertEquals("SYNTHETIC-B", StoreCatalog.read(root, security).header().addresses().get("127.0.0.1"));
      assertFalse(Files.exists(root.resolve("robots").resolve("SYNTHETIC-B").resolve("pull.json")));
      assertEquals("synthetic transfer state", Files.readString(state));
    }
  }

  @Test void firstContactPromotesEarlierClosedAddressSessionsBeforeTheNextCapture() throws Exception {
    var root = temp.resolve("store"); var security = security();
    try (var stores = new StoreRegistry(security)) {
      var store = stores.store(root); var placement = store.captures(WALL);
      Path old;
      try (var writer = new CaptureWriter(WALL, new ManualScheduler(), CapturePolicy.ALL, placement)) {
        connect(writer, 1_000_000, 0); old = writer.session().path();
        writer.value(VALUE, new ValueFrame(1, 1_000_000, 2, 7L), 0);
      }
      placement.completion().get(10, TimeUnit.SECONDS);
      store.identify(device("SYNTHETIC-A", "SHA256:first"), WALL).get(10, TimeUnit.SECONDS);
      assertFalse(Files.exists(old), "closed address history must move on first contact");
      var files = StoreCatalog.read(root, security).files(); assertEquals(1, files.size());
      assertEquals("SYNTHETIC-A", files.get(0).robot().serialNumber());
      try (var use = LogManager.getInstance().acquire(old.toString())) {
        assertEquals(7L, use.log().values().get("NT:/x").get(0).value());
      }
    }
  }

  @Test void identityLearnedDuringADisconnectIsRecordedOnResume() throws Exception {
    var root = temp.resolve("store");
    try (var stores = new StoreRegistry(security())) {
      var placement = stores.store(root).captures(WALL);
      try (var writer = new CaptureWriter(WALL, new ManualScheduler(), CapturePolicy.ALL, placement)) {
        connect(writer, 10_000_000, 0); writer.disconnected(); placement.completion().get(10, TimeUnit.SECONDS);
        writer.identity(device("SYNTHETIC-A", "SHA256:first"));
        connect(writer, 11_000_000, 1_000_000);
        assertEquals("SYNTHETIC-A", writer.session().identity().serialNumber());
        assertTrue(writer.session().path().startsWith(root.resolve("robots").resolve("SYNTHETIC-A")));
        writer.disconnected(); placement.completion().get(10, TimeUnit.SECONDS);
      }
    }
  }

  @Test void identityRolloverRefusesDeclarationsThatCannotFitTheBound() throws Exception {
    var root = temp.resolve("store");
    try (var stores = new StoreRegistry(security())) {
      var placement = stores.store(root).captures(WALL);
      try (var writer = new CaptureWriter(WALL, new ManualScheduler(), CapturePolicy.ALL, placement, 2048)) {
        connect(writer, 1_000_000, 0);
        for (int i = 2; i < 8; i++) writer.announce(new Announce("/" + "x".repeat(180) + i, i, "int", null, new JsonObject()));
        writer.identity(device("SYNTHETIC-A", "SHA256:first"));
        assertFalse(writer.session().open());
        assertTrue(writer.session().endReason().contains("cannot hold identity"), writer.session().endReason());
        placement.completion().get(10, TimeUnit.SECONDS);
        for (var file : StoreCatalog.read(root, security()).files()) assertTrue(Files.size(file.path()) <= 2048);
      }
    }
  }

  @Test void identityGuidesNameTheEvidenceAndItsLimits() throws Exception {
    for (String guide : List.of("ARCHITECTURE", "STANDALONE", "TOOLS", "DEVELOPMENT", "PIT_SERVER_PLAN")) {
      String text = Files.readString(Path.of("doc", guide + ".md"));
      assertTrue(text.contains("robot_candidates"), guide);
      assertTrue(text.contains("/SystemStats/SerialNumber"), guide);
    }
    String plan = Files.readString(Path.of("doc", "PIT_SERVER_PLAN.md"));
    assertTrue(plan.contains("/proc/[0-9]*/environ")); assertTrue(plan.contains("/etc/machine-info"));
  }
}
