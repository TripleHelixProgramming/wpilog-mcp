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
  @org.junit.jupiter.api.AfterEach void restorePermissions() {
    LogManager.getInstance().clearAllowedDirectories();
    allowed.forEach(LogManager.getInstance()::addAllowedDirectory);
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
      try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, stores.store(root).captures(WALL))) {
        connect(writer, 10_000_000, 0);
        assertEquals(root.resolve("robots/address-127.0.0.1/sessions/2026-03-07/142233Z/capture.wpilog"), writer.session().path());
        writer.value(VALUE, new ValueFrame(1, 10_000_000, 2, 5L), 0); loop.advance(250_000);
        var open = StoreCatalog.read(root, security); assertEquals(1, open.openCaptures().size());
        assertTrue(open.files().isEmpty()); assertTrue(open.unmanaged().isEmpty());
        var first = open.openCaptures().get(0); assertEquals("captured", first.file().provenance().kind());
        assertNull(first.file().sha256()); assertEquals(10, first.file().minTimestampSec());
        assertEquals(WALL.instant().toString(), first.session().startedAt());
        assertEquals("pit_clock", first.session().startBasis());
        writer.disconnected(); var closed = StoreCatalog.read(root, security);
        assertTrue(closed.openCaptures().isEmpty()); assertEquals(1, closed.files().size());
        var file = closed.files().get(0); assertEquals(StoreFiles.hash(file.path()), file.file().sha256());
        assertEquals(Files.size(file.path()), file.file().sizeBytes());
        connect(writer, 11_000_000, 1_000_000);
        writer.value(VALUE, new ValueFrame(1, 11_000_000, 2, 6L), 1_000_000); writer.disconnected();
        closed = StoreCatalog.read(root, security); assertEquals(1, closed.files().size());
        assertEquals(first.session().id(), closed.files().get(0).session().id());
        assertEquals(11, closed.files().get(0).file().maxTimestampSec());
        connect(writer, 1_000_000, 2_000_000); writer.disconnected();
        assertEquals("142233Z_2", writer.session().path().getParent().getFileName().toString());
        assertEquals(2, StoreCatalog.read(root, security).files().size());
        assertEquals(1, StoreCatalog.read(root, security).header().formatVersion());
      }
    }
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void matchFactsAreImmediateAndRenameRetriesAtCloseWhenOpenFileMoveIsRefused(boolean windowsRefusal) throws Exception {
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    var allowMove = new AtomicBoolean(!windowsRefusal);
    try (var stores = new StoreRegistry(security)) {
      var root = directory.resolve("store"); var loop = new ManualScheduler();
      var placement = new CaptureStore(stores.store(root), LogManager.getInstance(), WALL, (from, to) -> {
        if (!allowMove.get()) throw new java.nio.file.AccessDeniedException(from.toString());
        Files.move(from, to);
      });
      try (var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, placement)) {
        connect(writer, 10_000_000, 0); var before = writer.session().path();
        var names = List.of("/FMSInfo/EventName", "/FMSInfo/MatchType", "/FMSInfo/MatchNumber");
        for (int i = 0; i < names.size(); i++) {
          var topic = new Announce(names.get(i), i + 2, i == 0 ? "string" : "int", null, new JsonObject());
          writer.announce(topic); writer.value(topic, new ValueFrame(i + 2, 10_000_000, i == 0 ? 4 : 2,
              i == 0 ? "TEST" : i == 1 ? 2L : 7L), 0);
        }
        var held = StoreCatalog.read(root, security).openCaptures().get(0);
        assertEquals("TEST", held.session().event()); assertEquals("Qualification", held.session().matchType());
        assertEquals(7, held.session().matchNumber());
        // The real platform may refuse too. The manifest is always authoritative for the listing.
        if (windowsRefusal) assertEquals(before, writer.session().path());
        var oldDirs = LogDirectory.getInstance().getLogDirectories();
        try {
          LogDirectory.getInstance().setLogDirectory(root.toString());
          var listed = LogDirectory.getInstance().scanLogs().logs().get(0);
          assertEquals("TEST", listed.eventName()); assertEquals(7, listed.matchNumber());
        } finally { LogDirectory.getInstance().setLogDirectories(oldDirs.stream().map(Path::toString).toList()); }
        writer.value(VALUE, new ValueFrame(1, 12_000_000, 2, 8L), 0);
        allowMove.set(true); writer.disconnected();
        assertEquals("142233Z_TEST_Q7", writer.session().path().getParent().getFileName().toString());
        var finished = StoreCatalog.read(root, security); assertEquals(1, finished.files().size());
        assertEquals(12, finished.files().get(0).file().maxTimestampSec());
        assertEquals(StoreFiles.hash(writer.session().path()), finished.files().get(0).file().sha256());
        assertTrue(Files.exists(writer.session().path())); assertFalse(Files.exists(before));
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
