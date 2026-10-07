/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.config.MirrorConfig;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.HttpTransport;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;
import org.triplehelix.wpilogmcp.sync.RemoteFiles;

class MirrorSyncTest {
  @TempDir Path temp;
  LogManager manager; Set<Path> previous;
  LogStore origin, mirror; HttpTransport http;
  Clock clock = Clock.fixed(Instant.parse("2026-01-20T00:00:00Z"), ZoneOffset.UTC);
  @BeforeEach void setup() throws Exception {
    temp = temp.toRealPath(); manager = LogManager.getInstance(); previous = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    origin = manager.stores().store(temp.resolve("origin")); mirror = manager.stores().store(temp.resolve("mirror"));
    origin.importPaths(new LogStore.Request(List.of(), false, null), p -> {}).get();
    http = new HttpTransport(new ToolRegistry(), 0); http.setStoreDirectories(Set.of(origin.root())); http.start();
  }
  @AfterEach void cleanup() {
    http.stop(); manager.unloadAllLogs(); manager.clearAllowedDirectories(); previous.forEach(manager::addAllowedDirectory);
  }
  String url() { return "http://127.0.0.1:" + http.getPort(); }
  MirrorConfig config(int days, long cap, List<String> robots, List<String> events) {
    return new MirrorConfig(url(), mirror.root(), days, cap, robots, events, 30, 0);
  }
  MirrorConfig config() { return config(14, 20_000_000_000L, List.of(), List.of()); }
  MirrorSync.Result sync() throws Exception { return sync(config()); }
  MirrorSync.Result sync(MirrorConfig c) throws Exception {
    var result = mirror.mirror(c, p -> {}, clock, StoreSync::http).get(20, TimeUnit.SECONDS);
    assertEquals("synchronized", result.state(), result.toString()); return result;
  }
  StoreCatalog.Snapshot catalog(LogStore store) throws Exception { return StoreCatalog.read(store.root(), manager.testGetSecurityValidator()); }
  Path session(String id, String day, String robot, String event, boolean open, int count, int tag) throws Exception {
    var folder = origin.root().resolve("robots").resolve(robot).resolve("sessions").resolve(day).resolve(id);
    Files.createDirectories(folder); var path = folder.resolve("capture.wpilog");
    try (var writer = new WpilogWriter(path, "mirror fixture")) {
      int clockEntry = writer.start("systemTime", "int64", "", 0);
      int entry = writer.start("/Value", "double", "", 0);
      writer.append(clockEntry, 0, WpilogWriter.encodeInt64(Instant.parse(day + "T00:00:00Z").toEpochMilli() * 1000));
      for (int i = 0; i < count; i++) writer.append(entry, i * 1000L, WpilogWriter.encodeDouble(tag + i));
    }
    var provenance = new Provenance("captured", null, null, null, false);
    var s = new Session(id, day + "T00:00:00Z", day + "T00:00:01Z", "pit_server_clock", event, null, null, null,
        open ? List.of() : List.of(new LogFile("capture.wpilog", StoreFiles.hash(path), Files.size(path), "wpilog", provenance,
            true, 0, (count - 1) / 1000.0, day + "T00:00:00Z", day + "T00:00:01Z", "pit_server_clock", false, null)),
        open ? new OpenCapture("capture.wpilog", provenance, Files.size(path), 0, (count - 1) / 1000.0) : null);
    origin.capture(io -> {
      io.write(origin.root().resolve("robots").resolve(robot).resolve("robot.json"), new Robot(robot, robot, robot, "fixture", "device"));
      io.write(folder.resolve("session.json"), s); return null;
    });
    return path;
  }
  Path copy(Path source) { return mirror.root().resolve(origin.root().relativize(source)); }

  @Test void copiesInScopeSessionsWithExactManifestsAndNoStrayAdoption() throws Exception {
    var file = session("recent", "2026-01-19", "RIO", null, false, 100, 1);
    session("old", "2025-12-01", "RIO", null, false, 100, 2);
    session("event", "2025-12-02", "RIO", "District", false, 100, 3);
    session("other", "2026-01-19", "OTHER", "District", false, 100, 4);
    var result = sync(config(14, 20_000_000_000L, List.of("RIO"), List.of("District")));
    var got = catalog(mirror);
    assertTrue(got.header().mirror()); assertEquals(catalog(origin).header().id(), got.header().origin().storeId());
    assertEquals(url(), got.header().origin().url()); assertEquals(clock.instant().toString(), result.lastSync());
    assertEquals(Set.of("recent", "event"), got.sessions().stream().map(s -> s.session().id()).collect(java.util.stream.Collectors.toSet()));
    assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(copy(file)));
    assertEquals(Files.readString(file.resolveSibling("session.json")), Files.readString(copy(file).resolveSibling("session.json")));
    assertEquals(List.of(), got.unmanaged());
    assertTrue(got.header().origin().sessions().get("recent").complete());
    var stray = mirror.root().resolve("stray.wpilog"); Files.copy(file, stray);
    assertTrue(assertThrows(java.util.concurrent.ExecutionException.class, () -> mirror.importPaths(new LogStore.Request(List.of(stray), true, null), p -> {}).get()).getCause().getMessage().contains("mirror"));
    sync(); assertTrue(Files.exists(stray)); assertEquals(List.of(stray), catalog(mirror).unmanaged());
    assertEquals(1, mirror.sync(url(), 0, p -> {}).get().refusals().size());
  }

  @Test void growingFilesResumeAfterProofAndReloadWhileAChangedPrefixStartsAtZero() throws Exception {
    var file = session("live", "2026-01-19", "RIO", null, true, 6000, 1);
    sync(); long held = Files.size(copy(file));
    var before = manager.loadLog(copy(file).toString()); long records = before.sampleCount("/Value");
    session("live", "2026-01-19", "RIO", null, true, 9000, 1);
    var offsets = new ArrayList<Long>(); var hashes = new ArrayList<Long>();
    var result = mirror.mirror(config(), p -> {}, clock, observed(offsets, hashes)).get();
    assertEquals("synchronized", result.state(), result.toString()); assertEquals(held, offsets.get(0)); assertTrue(hashes.contains(held));
    assertEquals(Files.size(file) - held, result.bytesCopied());
    assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(copy(file)));
    var after = manager.loadLog(copy(file).toString()); assertEquals(6000, records); assertEquals(9000, after.sampleCount("/Value"));
    assertNotSame(before, after);
    var state = catalog(mirror).header().origin().sessions().get("live"); assertTrue(state.growing()); assertFalse(state.complete());
    session("live", "2026-01-19", "RIO", null, true, 10_000, 10);
    offsets.clear(); hashes.clear();
    result = mirror.mirror(config(), p -> {}, clock, observed(offsets, hashes)).get();
    assertEquals("synchronized", result.state(), result.toString()); assertEquals(0L, offsets.get(0));
    assertEquals(Files.size(file), result.bytesCopied()); assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(copy(file)));
    session("live", "2026-01-19", "RIO", null, false, 10_000, 10); sync();
    assertEquals(Files.readString(file.resolveSibling("session.json")), Files.readString(copy(file).resolveSibling("session.json")));
    assertTrue(catalog(mirror).header().origin().sessions().get("live").complete());
  }

  StoreSync.Source observed(List<Long> offsets, List<Long> hashes) {
    return address -> {
      var peer = StoreSync.http(address); var remote = peer.remote();
      return new StoreSync.Peer(peer.url(), peer.description(), peer.robots(), peer.sessions(), new RemoteFiles() {
        public List<File> list() throws java.io.IOException { return remote.list(); }
        public byte[] read(String name, long offset, int count) throws java.io.IOException { offsets.add(offset); return remote.read(name, offset, count); }
        public java.util.Optional<String> prefixHash(String name, long length) throws java.io.IOException { hashes.add(length); return remote.prefixHash(name, length); }
      });
    };
  }

  @Test void followsDirectoryRenameBySessionIdWithoutCopyingTheBytesAgain() throws Exception {
    var file = session("boot", "2026-01-19", "RIO", null, false, 100, 1); sync();
    var moved = file.getParent().resolveSibling("000000Z_EVENT_Q1"); Files.move(file.getParent(), moved);
    var result = sync(); assertEquals(0, result.bytesCopied());
    assertEquals(1, catalog(mirror).sessions().size()); assertEquals("boot", catalog(mirror).sessions().get(0).session().id());
    var destination = copy(moved.resolve(file.getFileName())); assertTrue(Files.exists(destination)); assertFalse(Files.exists(copy(file)));
    assertEquals(destination, manager.stores().resolveMoved(copy(file)));
    assertArrayEquals(Files.readAllBytes(moved.resolve(file.getFileName())), Files.readAllBytes(destination));
  }

  @Test void windowAndCapEvictOldestUnpinnedButPinsAndOriginMissingCopiesSurvive() throws Exception {
    var first = session("first", "2026-01-07", "RIO", null, false, 100, 1);
    var middle = session("middle", "2026-01-18", "RIO", null, false, 100, 2);
    var newest = session("newest", "2026-01-19", "RIO", null, false, 100, 3); sync();
    mirror.pin("first", true).get();
    long cap = Files.size(first) + Files.size(newest);
    var limited = config(14, cap, List.of(), List.of());
    assertEquals(List.of("middle"), sync(limited).evicted()); assertFalse(Files.exists(copy(middle)));
    assertTrue(Files.exists(copy(first))); assertTrue(Files.exists(copy(newest)));
    assertEquals(cap, catalog(mirror).files().stream().mapToLong(f -> f.file().sizeBytes()).sum());
    clock = Clock.offset(clock, java.time.Duration.ofDays(30));
    assertEquals(List.of("newest"), sync(limited).evicted()); assertTrue(Files.exists(copy(first)));
    // A pin may exceed the cap; it is a stated exception, never permission to delete it.
    var small = sync(config(0, 1, List.of(), List.of())); assertFalse(small.retained().isEmpty()); assertTrue(Files.exists(copy(first)));
    Files.delete(first.resolveSibling("session.json")); mirror.pin("first", false).get();
    var missing = sync(config(0, 1, List.of(), List.of()));
    assertTrue(missing.retained().stream().anyMatch(s -> s.contains("absent from origin"))); assertTrue(Files.exists(copy(first)));
  }

  @Test void offlineRetainsTheLastSuccessfulSyncAndResumesWithoutADuplicate() throws Exception {
    var file = session("boot", "2026-01-19", "RIO", null, false, 100, 1); sync();
    String last = catalog(mirror).header().origin().lastSync();
    var offline = mirror.mirror(config(), p -> {}, Clock.offset(clock, java.time.Duration.ofHours(2)), address -> {
      throw new java.io.IOException("origin unavailable");
    }).get();
    assertEquals("offline", offline.state()); assertEquals(last, offline.lastSync());
    assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(copy(file))); assertEquals(1, catalog(mirror).sessions().size());
    assertEquals(0, sync().bytesCopied()); assertEquals(1, catalog(mirror).sessions().size());
  }

  @Test void interruptedBetweenBlocksResumesItsJournalRatherThanAdoptingStrays() throws Exception {
    var file = session("boot", "2026-01-19", "RIO", null, false, 10_000, 1);
    assertThrows(java.util.concurrent.ExecutionException.class, () -> mirror.mirror(config(), p -> {
      if (p.remainingBytes() > 0) throw new IllegalStateException("test stop between blocks");
    }, clock, StoreSync::http).get());
    assertTrue(catalog(mirror).files().isEmpty()); assertTrue(catalog(mirror).unmanaged().isEmpty());
    var offsets = new ArrayList<Long>(); var hashes = new ArrayList<Long>();
    var result = mirror.mirror(config(), p -> {}, clock, observed(offsets, hashes)).get();
    assertEquals("synchronized", result.state(), result.toString()); assertEquals(65536L, offsets.get(0)); assertTrue(hashes.contains(65536L));
    assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(copy(file)));
  }

  @Test void completionDoesNotRaceAheadOfTheDurableStatusRead() throws Exception {
    session("boot", "2026-01-19", "RIO", null, false, 100, 1);
    var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
    try (var service = new MirrorService(mirror, config(), clock, () -> {
      entered.countDown();
      try { if (!release.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("test status read timed out"); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
      return mirror.mirrorOrigin();
    })) {
      var pending = service.syncNow();
      try {
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        assertEquals("synchronizing", service.status().state());
      } finally { release.countDown(); }
      pending.get(10, TimeUnit.SECONDS);
      assertEquals("synchronized", service.status().state()); assertEquals(clock.instant().toString(), service.status().lastSync());
      assertEquals(0L, service.status().ageSec());
    } finally { release.countDown(); }
  }

  @Test void anOccupiedDestinationAndAnIncorrectAdvertisedHashAreNeverAdmitted() throws Exception {
    sync(); // Establish mirror ownership before a person places a stray inside its layout.
    var source = session("boot", "2026-01-19", "RIO", null, false, 100, 1);
    var occupied = copy(source); Files.createDirectories(occupied.getParent()); Files.writeString(occupied, "a person's file");
    var refused = mirror.mirror(config(), p -> {}, clock, StoreSync::http).get();
    assertEquals("offline", refused.state()); assertTrue(refused.reason().contains("unmanaged"));
    assertEquals("a person's file", Files.readString(occupied)); assertTrue(catalog(mirror).files().isEmpty());
    assertTrue(catalog(mirror).unmanaged().contains(occupied)); Files.delete(occupied);
    var manifest = source.resolveSibling("session.json");
    var json = com.google.gson.JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
    json.getAsJsonArray("files").get(0).getAsJsonObject().addProperty("sha256", "0".repeat(64)); Files.writeString(manifest, json.toString());
    var wrongHash = mirror.mirror(config(), p -> {}, clock, StoreSync::http).get();
    assertEquals("partial", wrongHash.state()); assertFalse(wrongHash.refusals().isEmpty());
    assertTrue(catalog(mirror).files().isEmpty()); assertFalse(Files.exists(occupied));
  }

  @Test void aContinuouslyChangingPrefixEndsThePassAndKeepsItsJournal() throws Exception {
    session("changing", "2026-01-19", "RIO", null, false, 100, 1);
    var checks = new java.util.concurrent.atomic.AtomicInteger();
    var result = mirror.mirror(config(), p -> {}, clock, address -> {
      var peer = StoreSync.http(address); var remote = peer.remote();
      return new StoreSync.Peer(peer.url(), peer.description(), peer.robots(), peer.sessions(), new RemoteFiles() {
        public List<File> list() throws java.io.IOException { return remote.list(); }
        public byte[] read(String name, long offset, int count) throws java.io.IOException { return remote.read(name, offset, count); }
        public java.util.Optional<String> prefixHash(String name, long length) throws java.io.IOException {
          if (checks.incrementAndGet() > 2) throw new java.io.IOException("test guard: unbounded retry");
          return java.util.Optional.of("0".repeat(64));
        }
      });
    }).get(10, TimeUnit.SECONDS);
    assertEquals("partial", result.state()); assertEquals(2, checks.get());
    assertTrue(result.reason().contains("next mirror pass")); assertTrue(catalog(mirror).files().isEmpty());
    assertTrue(Files.exists(MirrorSync.journalPath(mirror.root())));
    sync(); assertEquals(1, catalog(mirror).files().size());
    assertTrue(catalog(mirror).unmanaged().isEmpty());
    try (var paths = Files.walk(mirror.root().resolve(".mirror/files"))) {
      assertEquals(0, paths.filter(Files::isRegularFile).count(), "obsolete private generations must not accumulate");
    }
  }

  @Test void aGrowingPrefixCanBeEvictedOnlyAfterTheOriginProvesItStillHasIt() throws Exception {
    var file = session("live", "2026-01-19", "RIO", null, true, 100, 1); sync();
    var offsets = new ArrayList<Long>(); var hashes = new ArrayList<Long>();
    var result = mirror.mirror(config(0, 1, List.of(), List.of()), p -> {}, clock, observed(offsets, hashes)).get();
    assertEquals(List.of("live"), result.evicted()); assertEquals(List.of(Files.size(file)), hashes);
    assertFalse(Files.exists(copy(file))); assertTrue(catalog(mirror).sessions().isEmpty());
    sync(); session("live", "2026-01-19", "RIO", null, true, 100, 10);
    result = mirror.mirror(config(0, 1, List.of(), List.of()), p -> {}, clock, observed(offsets, hashes)).get();
    assertTrue(result.evicted().isEmpty()); assertTrue(Files.exists(copy(file))); assertFalse(result.retained().isEmpty());
  }

  @Test void pendingFileAndDirectoryMovesRecoverBeforeContactingAnOfflineOrigin() throws Exception {
    var file = session("boot", "2026-01-19", "RIO", null, false, 100, 1);
    assertThrows(java.util.concurrent.ExecutionException.class, () -> mirror.mirror(config(), p -> {
      throw new IllegalStateException("stop before verification");
    }, clock, StoreSync::http).get());
    var io = new StoreFiles(mirror.root(), manager.testGetSecurityValidator());
    var journal = io.read(MirrorSync.journalPath(mirror.root()), org.triplehelix.wpilogmcp.sync.PullManifest.class);
    var entry = journal.files().get(0); var destination = copy(file); Files.createDirectories(destination.getParent());
    io.write(mirror.root().resolve(".mirror/pending.json"), java.util.Map.of("from", entry.localName(),
        "to", StoreFiles.relative(mirror.root(), destination), "hash", StoreFiles.hash(io.resolve(mirror.root(), entry.localName()))));
    Files.move(io.resolve(mirror.root(), entry.localName()), destination);
    StoreSync.Source offline = address -> { throw new java.io.IOException("offline"); };
    assertEquals("offline", mirror.mirror(config(), p -> {}, clock, offline).get().state());
    journal = io.read(MirrorSync.journalPath(mirror.root()), org.triplehelix.wpilogmcp.sync.PullManifest.class);
    assertEquals(StoreFiles.relative(mirror.root(), destination), journal.files().get(0).localName());
    assertFalse(Files.exists(mirror.root().resolve(".mirror/pending.json"))); sync();
    var from = destination.getParent(); var to = from.resolveSibling("renamed");
    io.write(mirror.root().resolve(".mirror/pending.json"), java.util.Map.of("from", StoreFiles.relative(mirror.root(), from),
        "to", StoreFiles.relative(mirror.root(), to), "session_id", "boot"));
    Files.move(from, to);
    assertEquals("offline", mirror.mirror(config(), p -> {}, clock, offline).get().state());
    assertEquals(StoreFiles.relative(mirror.root(), to), catalog(mirror).header().origin().sessions().get("boot").path());
    assertEquals(to.resolve(file.getFileName()), manager.stores().resolveMoved(destination));
    assertFalse(Files.exists(mirror.root().resolve(".mirror/pending.json")));
  }

  @Test void neitherAnExistingStoreNorADifferentOriginIdCanBecomeThisMirror() throws Exception {
    var source = session("boot", "2026-01-19", "RIO", null, false, 100, 1); sync();
    var existing = manager.stores().store(temp.resolve("existing"));
    existing.importPaths(new LogStore.Request(List.of(source), false, "RIO"), p -> {}).get();
    var c = new MirrorConfig(url(), existing.root(), 14, 20_000_000_000L, List.of(), List.of(), 30, 0);
    assertThrows(java.util.concurrent.ExecutionException.class, () -> existing.mirror(c, p -> {}, clock, StoreSync::http).get());
    var before = Files.readAllBytes(copy(source));
    origin.capture(io -> {
      var h = io.read(origin.root().resolve("store.json"), Header.class);
      io.write(origin.root().resolve("store.json"), new Header(h.formatVersion(), h.createdAt(), "different", h.moves())); return null;
    });
    var refused = mirror.mirror(config(), p -> {}, clock, StoreSync::http).get();
    assertEquals("offline", refused.state()); assertTrue(refused.reason().contains("different store id"));
    assertArrayEquals(before, Files.readAllBytes(copy(source)));
  }
}
