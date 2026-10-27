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
import java.util.HashMap;
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

/** Exercises the historical writer only as a generator of preserved format-1 fixtures. */
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
    var result = org.triplehelix.wpilogmcp.store.LegacyMirrorFixture.sync(mirror, c, p -> {}, clock, StoreSync::http).get(20, TimeUnit.SECONDS);
    assertEquals("synchronized", result.state(), result.toString()); return result;
  }
  StoreCatalog.Snapshot catalog(LogStore store) throws Exception { return StoreCatalog.read(store.root(), manager.testGetSecurityValidator()); }
  Set<String> held() throws Exception {
    return catalog(mirror).sessions().stream().map(s -> s.session().id()).collect(java.util.stream.Collectors.toSet());
  }

  /** One log of an origin session, recorded the way the store records its kind; only a capture is open. */
  record Member(String name, String kind, boolean open, int count, int tag) {}
  static Member pulledLog(int count, int tag) { return new Member("pulled.wpilog", "pulled", false, count, tag); }
  static Member capture(boolean open, int count, int tag) { return new Member("capture.wpilog", "captured", open, count, tag); }
  static Member imported(int count, int tag) { return new Member("imported.wpilog", "imported", false, count, tag); }

  /** A log the origin pulled from its robot, alone in its session. */
  Path session(String id, String day, String robot, String event, int count, int tag) throws Exception {
    return session(id, day, robot, event, List.of(pulledLog(count, tag))).get(0);
  }
  List<Path> session(String id, String day, String robot, String event, List<Member> members) throws Exception {
    var folder = origin.root().resolve("robots").resolve(robot).resolve("sessions").resolve(day).resolve(id);
    Files.createDirectories(folder); var paths = new ArrayList<Path>();
    for (var member : members) {
      var path = folder.resolve(member.name()); paths.add(path);
      org.triplehelix.wpilogmcp.fixtures.LogRewrites.releaseBeforeRewrite(path);
      try (var writer = new WpilogWriter(path, "mirror fixture")) {
        int clockEntry = writer.start("systemTime", "int64", "", 0);
        int entry = writer.start("/Value", "double", "", 0);
        writer.append(clockEntry, 0, WpilogWriter.encodeInt64(Instant.parse(day + "T00:00:00Z").toEpochMilli() * 1000));
        for (int i = 0; i < member.count(); i++) writer.append(entry, i * 1000L, WpilogWriter.encodeDouble(member.tag() + i));
      }
    }
    var s = manifest(id, day, robot, event, members, paths);
    origin.capture(io -> {
      io.write(origin.root().resolve("robots").resolve(robot).resolve("robot.json"), new Robot(robot, robot, robot, "fixture", "device"));
      io.write(folder.resolve("session.json"), s); return null;
    });
    return paths;
  }
  static Session manifest(String id, String day, String robot, String event, List<Member> members, List<Path> paths) throws java.io.IOException {
    var files = new ArrayList<LogFile>(); OpenCapture open = null;
    for (int i = 0; i < members.size(); i++) {
      var member = members.get(i); var path = paths.get(i); double end = (member.count() - 1) / 1000.0;
      var provenance = switch (member.kind()) {
        // As a pit's pull records one: the robot's own path and serial, placed by the pull.
        case "pulled" -> new Provenance("pulled", "/home/lvuser/logs/" + id + ".wpilog", id + ".wpilog", day + "T00:00:02Z", true, robot);
        case "imported" -> new Provenance("imported", "upload:" + id + ".wpilog", id + ".wpilog", day + "T00:00:02Z", false);
        case "captured" -> new Provenance("captured", null, null, null, false);
        default -> throw new IllegalArgumentException(member.kind());
      };
      if (member.open()) open = new OpenCapture(member.name(), provenance, Files.size(path), 0, end);
      else files.add(new LogFile(member.name(), StoreFiles.hash(path), Files.size(path), "wpilog", provenance,
          true, 0, end, day + "T00:00:00Z", day + "T00:00:01Z", "pit_server_clock", false, null));
    }
    return new Session(id, day + "T00:00:00Z", day + "T00:00:01Z", "pit_server_clock", event, null, null, null, files, open);
  }
  Path copy(Path source) { return mirror.root().resolve(origin.root().relativize(source)); }

  /**
   * The copy that 0.10.0-dev1 to dev4 left of a session's captures. Those versions copied every
   * member the way the mirror still copies a pulled log, wrote the origin's manifest verbatim, and
   * marked the copy of an open capture growing. So each member is offered as pulled and copied, and
   * then both manifests record what it was.
   */
  List<Path> copiedByAnEarlierVersion(String id, String day, List<Member> members) throws Exception {
    var paths = session(id, day, "RIO", null, members.stream().map(m -> new Member(m.name(), "pulled", false, m.count(), m.tag())).toList());
    sync();
    var truth = manifest(id, day, "RIO", null, members, paths);
    origin.capture(io -> { io.write(paths.get(0).resolveSibling("session.json"), truth); return null; });
    var io = new StoreFiles(mirror.root(), manager.testGetSecurityValidator());
    io.write(copy(paths.get(0)).resolveSibling("session.json"), truth);
    var header = io.read(mirror.root().resolve("store.json"), Header.class); var o = header.origin();
    var sessions = new HashMap<>(o.sessions()); var state = sessions.get(id); boolean open = members.stream().anyMatch(Member::open);
    sessions.put(id, new MirrorSession(state.path(), state.lastSync(), !open, open));
    io.write(mirror.root().resolve("store.json"), header.withOrigin(new MirrorOrigin(o.storeId(), o.url(), o.lastSync(), o.pinnedSessions(), sessions)));
    return paths;
  }

  @Test void aMirrorCopiesPulledAndUploadedLogsButNeverACapture() throws Exception {
    var alone = session("robot-only", "2026-01-19", "RIO", null, 100, 1);
    var match = session("match", "2026-01-19", "RIO", "District", List.of(pulledLog(200, 2), capture(false, 300, 3), imported(50, 4)));
    var practice = session("practice", "2026-01-19", "RIO", null, List.of(pulledLog(100, 5), capture(true, 100, 6)));
    var upload = session("upload", "2026-01-19", "RIO", null, List.of(imported(100, 7))).get(0);
    var finished = session("pit-capture", "2026-01-19", "PIT", null, List.of(capture(false, 100, 8))).get(0);
    var live = session("pit-live", "2026-01-19", "PIT", null, List.of(capture(true, 100, 9))).get(0);
    var result = sync();
    assertEquals(Set.of("robot-only", "match", "practice", "upload"), held());
    long copied = 0;
    for (var file : List.of(alone, match.get(0), match.get(2), practice.get(0), upload)) {
      copied += Files.size(file); assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(copy(file)), file.toString());
    }
    assertEquals(copied, result.bytesCopied(), result.toString());
    for (var stays : List.of(match.get(1), practice.get(1), finished, live)) assertFalse(Files.exists(copy(stays)), stays.toString());
    assertFalse(Files.exists(mirror.root().resolve("robots").resolve("PIT")), "A robot with nothing to copy gets no folder");
    // A session without a capture keeps the origin's manifest byte for byte; any other lists only what is held.
    for (var file : List.of(alone, upload)) {
      assertEquals(Files.readString(file.resolveSibling("session.json")), Files.readString(copy(file).resolveSibling("session.json")));
    }
    for (var files : List.of(match, practice)) {
      var expected = com.google.gson.JsonParser.parseString(Files.readString(files.get(0).resolveSibling("session.json"))).getAsJsonObject();
      var kept = new com.google.gson.JsonArray();
      for (var file : expected.getAsJsonArray("files")) {
        if (!"captured".equals(file.getAsJsonObject().getAsJsonObject("provenance").get("kind").getAsString())) kept.add(file);
      }
      expected.add("files", kept); expected.add("open_capture", com.google.gson.JsonNull.INSTANCE);
      assertEquals(expected, com.google.gson.JsonParser.parseString(Files.readString(copy(files.get(0)).resolveSibling("session.json"))));
    }
    assertEquals(List.of(), catalog(mirror).openCaptures()); assertEquals(List.of(), catalog(mirror).unmanaged());
    var state = catalog(mirror).header().origin().sessions().get("practice"); assertTrue(state.complete()); assertFalse(state.growing());
    // A pin keeps what the mirror holds of a session; it cannot make the mirror copy a capture.
    mirror.pin("pit-capture", true).get();
    assertEquals(0, sync().bytesCopied()); assertEquals(Set.of("robot-only", "match", "practice", "upload"), held());
  }

  @Test void aMirrorCopiesAClockRecordInsteadOfItsCapture() throws Exception {
    var files = session("match", "2026-01-19", "RIO", null, List.of(pulledLog(100, 1), capture(false, 100, 2)));
    assertEquals(1, origin.keepClocks().get(20, TimeUnit.SECONDS));
    var offered = StoreJson.JSON.fromJson(Files.readString(files.get(0).resolveSibling("session.json")), Session.class);
    var receipt = offered.clocks().get(0); var record = files.get(0).resolveSibling(receipt.path());
    var result = sync();
    assertEquals(Files.size(files.get(0)) + receipt.sizeBytes(), result.bytesCopied(), result.toString());
    assertArrayEquals(Files.readAllBytes(record), Files.readAllBytes(copy(record))); assertFalse(Files.exists(copy(files.get(1))));
    assertEquals(offered.clocks(), catalog(mirror).sessions().get(0).session().clocks());
    assertEquals(List.of(), catalog(mirror).unmanaged());
    clock = Clock.offset(clock, java.time.Duration.ofDays(30));
    assertEquals(List.of("match"), sync().evicted()); assertFalse(Files.exists(copy(record)), "A record goes with its session");
  }

  @Test void aClockRecordThatNoLongerDescribesItsCaptureIsNotCopied() throws Exception {
    var files = session("match", "2026-01-19", "RIO", null, List.of(pulledLog(100, 1), capture(false, 100, 2)));
    assertEquals(1, origin.keepClocks().get(20, TimeUnit.SECONDS));
    var manifest = files.get(0).resolveSibling("session.json");
    var stale = StoreJson.JSON.fromJson(Files.readString(manifest), Session.class).clocks();
    // The origin's capture changed after its record was written, and the record has not caught up.
    session("match", "2026-01-19", "RIO", null, List.of(pulledLog(100, 1), capture(false, 120, 2)));
    origin.capture(io -> { io.write(manifest, io.read(manifest, Session.class).withClocks(stale)); return null; });
    sync();
    assertEquals(List.of(), catalog(mirror).sessions().get(0).session().clocks());
    assertFalse(Files.exists(copy(files.get(0).resolveSibling("clock").resolve("capture.wpilog.json"))));
  }

  @Test void aClockRecordWhoseBytesDisagreeWithItsHashIsNeverInstalled() throws Exception {
    var files = session("match", "2026-01-19", "RIO", null, List.of(pulledLog(100, 1), capture(false, 100, 2)));
    assertEquals(1, origin.keepClocks().get(20, TimeUnit.SECONDS));
    var record = files.get(0).resolveSibling("clock").resolve("capture.wpilog.json");
    var result = org.triplehelix.wpilogmcp.store.LegacyMirrorFixture.sync(mirror, config(), p -> {}, clock, address -> {
      var peer = StoreSync.http(address); var remote = peer.remote();
      return new StoreSync.Peer(peer.url(), peer.description(), peer.robots(), peer.sessions(), new RemoteFiles() {
        public List<File> list() throws java.io.IOException { return remote.list(); }
        public byte[] read(String name, long offset, int count) throws java.io.IOException {
          var bytes = remote.read(name, offset, count); if (name.endsWith(".json") && offset == 0 && bytes.length > 0) bytes[0] ^= 1; return bytes;
        }
        public java.util.Optional<String> prefixHash(String name, long length) throws java.io.IOException {
          if (!name.endsWith(".json")) return remote.prefixHash(name, length);
          try { return java.util.Optional.of(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(read(name, 0, (int) length)))); }
          catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
        }
      });
    }).get(20, TimeUnit.SECONDS);
    assertEquals("partial", result.state(), result.toString());
    assertTrue(result.refusals().stream().anyMatch(r -> r.contains("clock record")), result.toString());
    assertFalse(Files.exists(copy(record))); assertEquals(Set.of(), held());
  }

  @Test void theCapCountsOnlyTheFilesTheMirrorHolds() throws Exception {
    var files = session("match", "2026-01-19", "RIO", null, List.of(pulledLog(100, 1), capture(false, 50_000, 2)));
    long pulled = Files.size(files.get(0)); assertTrue(Files.size(files.get(1)) > 10 * pulled);
    var result = sync(config(14, pulled, List.of(), List.of()));
    assertEquals(pulled, result.bytesCopied(), result.toString()); assertEquals(List.of(), result.retained());
    assertEquals(Set.of("match"), held());
  }

  @Test void mirrorCopiesMergeAliasesThroughOpenAndClosedSessionManifests() throws Exception {
    for (boolean open : List.of(false, true)) {
      // The pulled log beside a capture, finished or still recording, is copied; the capture is not.
      var file = session(open ? "growing-alias" : "closed-alias", "2026-01-19", "RIO", null,
          List.of(pulledLog(2, open ? 7 : 8), capture(open, 2, open ? 9 : 10))).get(0);
      var manifest = file.resolveSibling("session.json");
      var json = com.google.gson.JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
      json.add("merged_session_ids", StoreJson.JSON.toJsonTree(List.of(open ? "old-growing" : "old-closed")));
      origin.capture(io -> { io.write(manifest, json); return null; });
    }
    sync();
    for (var session : catalog(mirror).sessions()) {
      assertEquals(List.of(session.session().id().equals("closed-alias") ? "old-closed" : "old-growing"), session.session().mergedSessionIds());
      assertNull(session.session().openCapture());
      assertEquals(List.of("pulled.wpilog"), session.session().files().stream().map(LogFile::path).toList(),
          "A mirrored session lists only the files the mirror holds");
    }
    assertEquals(2, catalog(mirror).sessions().size());
  }

  @Test void capturesAnEarlierVersionCopiedStayUnrefreshedUntilTheirSessionAgesOut() throws Exception {
    var finished = copiedByAnEarlierVersion("finished", "2026-01-19", List.of(capture(false, 100, 1))).get(0);
    var live = copiedByAnEarlierVersion("live", "2026-01-19", List.of(capture(true, 6000, 2))).get(0);
    var mixed = copiedByAnEarlierVersion("mixed", "2026-01-19", List.of(pulledLog(100, 3), capture(false, 100, 4), imported(100, 5)));
    var copies = new ArrayList<>(List.of(finished, live)); copies.addAll(mixed);
    var bytes = new HashMap<Path, byte[]>(); for (var file : copies) bytes.put(file, Files.readAllBytes(copy(file)));
    var manifests = new HashMap<String, String>(); var states = new HashMap<String, MirrorSession>();
    for (var file : List.of(finished, live)) {
      String id = file.getParent().getFileName().toString();
      manifests.put(id, Files.readString(copy(file).resolveSibling("session.json")));
      states.put(id, catalog(mirror).header().origin().sessions().get(id));
    }
    assertTrue(states.get("live").growing()); assertFalse(states.get("live").complete());
    // The pit kept recording the capture an earlier version copied while it was open, then finished it.
    session("live", "2026-01-19", "RIO", null, List.of(capture(true, 9000, 2)));
    clock = Clock.offset(clock, java.time.Duration.ofHours(1));
    var growing = sync(); assertEquals(0, growing.bytesCopied(), growing.toString());
    session("live", "2026-01-19", "RIO", null, List.of(capture(false, 10_000, 2)));
    var result = sync();
    assertEquals(0, result.bytesCopied(), result.toString()); assertEquals(List.of(), result.evicted());
    for (var file : copies) assertArrayEquals(bytes.get(file), Files.readAllBytes(copy(file)), file.toString());
    for (var file : List.of(finished, live)) {
      String id = file.getParent().getFileName().toString();
      assertEquals(manifests.get(id), Files.readString(copy(file).resolveSibling("session.json")), id + " is not republished");
      assertEquals(states.get(id), catalog(mirror).header().origin().sessions().get(id), id + " keeps its last synchronization");
    }
    // A session with something to copy is republished, keeping its earlier capture copy in the origin's order.
    assertEquals(Files.readString(mixed.get(0).resolveSibling("session.json")), Files.readString(copy(mixed.get(0)).resolveSibling("session.json")));
    assertEquals(clock.instant().toString(), catalog(mirror).header().origin().sessions().get("mixed").lastSync());
    // Listed and readable as they were copied.
    assertTrue(catalog(mirror).openCaptures().stream().anyMatch(f -> f.path().equals(copy(live))));
    assertEquals(6000, manager.loadLog(copy(live).toString()).sampleCount("/Value"));
    clock = Clock.offset(clock, java.time.Duration.ofDays(30));
    var aged = sync();
    assertEquals(Set.of("finished", "live", "mixed"), Set.copyOf(aged.evicted()), aged.toString());
    assertTrue(catalog(mirror).sessions().isEmpty()); for (var file : copies) assertFalse(Files.exists(copy(file)), file.toString());
  }

  @Test void aCaptureAnEarlierVersionCopiedCountsTowardTheCapLikeAnyOtherCopy() throws Exception {
    var older = copiedByAnEarlierVersion("older", "2026-01-18", List.of(capture(false, 100, 1))).get(0);
    var newer = session("newer", "2026-01-19", "RIO", null, 100, 2);
    var result = sync(config(14, Files.size(newer), List.of(), List.of()));
    assertEquals(List.of("older"), result.evicted()); assertFalse(Files.exists(copy(older)));
    assertEquals(Set.of("newer"), held());
  }

  @Test void anEarlierCopyOfAnOpenCaptureStaysListedBesideItsSessionsOtherFiles() throws Exception {
    var files = copiedByAnEarlierVersion("practice", "2026-01-19", List.of(capture(true, 100, 1), pulledLog(100, 2)));
    session("practice", "2026-01-19", "RIO", null, List.of(capture(true, 200, 1), pulledLog(100, 2)));
    var result = sync();
    assertEquals(0, result.bytesCopied(), result.toString());
    var held = catalog(mirror).sessions().get(0).session();
    assertNotNull(held.openCapture(), "An unlisted copy would be left behind as a stray");
    assertEquals(Files.size(copy(files.get(0))), held.openCapture().sizeBytes());
    assertEquals(List.of("pulled.wpilog"), held.files().stream().map(LogFile::path).toList());
    assertEquals(List.of(), catalog(mirror).unmanaged());
    var state = catalog(mirror).header().origin().sessions().get("practice"); assertTrue(state.growing()); assertFalse(state.complete());
  }

  @Test void anEarlierCopyOfAnOpenCaptureGainsNothingWhileTheCaptureIsOpen() throws Exception {
    var file = copiedByAnEarlierVersion("live", "2026-01-19", List.of(capture(true, 100, 1))).get(0);
    // The pit keeps recording, and pulls the robot's kernel log for the session.
    session("live", "2026-01-19", "RIO", null, List.of(capture(true, 200, 1)));
    var text = file.resolveSibling("robot").resolve("system").resolve("dmesg.txt"); Files.createDirectories(text.getParent());
    Files.writeString(text, "[105.0] warning synthetic kernel\n");
    origin.capture(io -> {
      var path = file.resolveSibling("session.json"); var s = io.read(path, Session.class);
      var receipt = new SystemLogState.File(SystemLogState.Location.SESSION, "robot/system/dmesg.txt", "kernel", "dmesg", StoreFiles.hash(text),
          Files.size(text), new Provenance("pulled", "dmesg", "dmesg", "2026-01-19T00:00:02Z", false, "RIO"), null);
      io.write(path, s.withSystemLogs(SystemLogState.EMPTY.withFiles(List.of(receipt)))); return null;
    });
    var result = sync();
    assertEquals(0, result.bytesCopied(), result.toString()); assertFalse(Files.exists(copy(text)));
    assertEquals(List.of(), catalog(mirror).unmanaged());
  }

  @Test void copiesInScopeSessionsWithExactManifestsAndNoStrayAdoption() throws Exception {
    var file = session("recent", "2026-01-19", "RIO", null, 100, 1);
    session("old", "2025-12-01", "RIO", null, 100, 2);
    session("event", "2025-12-02", "RIO", "District", 100, 3);
    session("other", "2026-01-19", "OTHER", "District", 100, 4);
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
    var file = session("boot", "2026-01-19", "RIO", null, 100, 1); sync();
    var moved = file.getParent().resolveSibling("000000Z_EVENT_Q1"); Files.move(file.getParent(), moved);
    var result = sync(); assertEquals(0, result.bytesCopied());
    assertEquals(1, catalog(mirror).sessions().size()); assertEquals("boot", catalog(mirror).sessions().get(0).session().id());
    var destination = copy(moved.resolve(file.getFileName())); assertTrue(Files.exists(destination)); assertFalse(Files.exists(copy(file)));
    assertEquals(destination, manager.stores().resolveMoved(copy(file)));
    assertArrayEquals(Files.readAllBytes(moved.resolve(file.getFileName())), Files.readAllBytes(destination));
    var movedAgain = moved.resolveSibling("000000Z_CORRECTED_Q2"); Files.move(moved, movedAgain);
    assertEquals(0, sync().bytesCopied());
    var finalDestination = copy(movedAgain.resolve(file.getFileName()));
    assertEquals(finalDestination, manager.stores().resolveMoved(copy(file)));
    assertEquals(finalDestination, manager.stores().resolveMoved(destination));
  }

  @Test void windowAndCapEvictOldestUnpinnedButPinsAndOriginMissingCopiesSurvive() throws Exception {
    var first = session("first", "2026-01-07", "RIO", null, 100, 1);
    var middle = session("middle", "2026-01-18", "RIO", null, 100, 2);
    var newest = session("newest", "2026-01-19", "RIO", null, 100, 3); sync();
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
    var file = session("boot", "2026-01-19", "RIO", null, 100, 1); sync();
    String last = catalog(mirror).header().origin().lastSync();
    var offline = org.triplehelix.wpilogmcp.store.LegacyMirrorFixture.sync(mirror, config(), p -> {}, Clock.offset(clock, java.time.Duration.ofHours(2)), address -> {
      throw new java.io.IOException("origin unavailable");
    }).get();
    assertEquals("offline", offline.state()); assertEquals(last, offline.lastSync());
    assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(copy(file))); assertEquals(1, catalog(mirror).sessions().size());
    assertEquals(0, sync().bytesCopied()); assertEquals(1, catalog(mirror).sessions().size());
  }

  @Test void interruptedBetweenBlocksResumesItsJournalRatherThanAdoptingStrays() throws Exception {
    var file = session("boot", "2026-01-19", "RIO", null, 10_000, 1);
    assertThrows(java.util.concurrent.ExecutionException.class, () -> org.triplehelix.wpilogmcp.store.LegacyMirrorFixture.sync(mirror, config(), p -> {
      if (p.remainingBytes() > 0) throw new IllegalStateException("test stop between blocks");
    }, clock, StoreSync::http).get());
    assertTrue(catalog(mirror).files().isEmpty()); assertTrue(catalog(mirror).unmanaged().isEmpty());
    var offsets = new ArrayList<Long>(); var hashes = new ArrayList<Long>();
    var result = org.triplehelix.wpilogmcp.store.LegacyMirrorFixture.sync(mirror, config(), p -> {}, clock, observed(offsets, hashes)).get();
    assertEquals("synchronized", result.state(), result.toString()); assertEquals(65536L, offsets.get(0)); assertTrue(hashes.contains(65536L));
    assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(copy(file)));
  }

  @Test void completionDoesNotRaceAheadOfTheDurableStatusRead() throws Exception {
    session("boot", "2026-01-19", "RIO", null, 100, 1);
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
      assertEquals("migration_required", service.status().state()); assertNull(service.status().lastSync());
      assertNull(service.status().ageSec());
    } finally { release.countDown(); }
  }

  @Test void anOccupiedDestinationAndAnIncorrectAdvertisedHashAreNeverAdmitted() throws Exception {
    sync(); // Establish mirror ownership before a person places a stray inside its layout.
    var source = session("boot", "2026-01-19", "RIO", null, 100, 1);
    var occupied = copy(source); Files.createDirectories(occupied.getParent()); Files.writeString(occupied, "a person's file");
    var refused = org.triplehelix.wpilogmcp.store.LegacyMirrorFixture.sync(mirror, config(), p -> {}, clock, StoreSync::http).get();
    assertEquals("offline", refused.state()); assertTrue(refused.reason().contains("unmanaged"));
    assertEquals("a person's file", Files.readString(occupied)); assertTrue(catalog(mirror).files().isEmpty());
    assertTrue(catalog(mirror).unmanaged().contains(occupied)); Files.delete(occupied);
    var manifest = source.resolveSibling("session.json");
    var json = com.google.gson.JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
    json.getAsJsonArray("files").get(0).getAsJsonObject().addProperty("sha256", "0".repeat(64)); Files.writeString(manifest, json.toString());
    var wrongHash = org.triplehelix.wpilogmcp.store.LegacyMirrorFixture.sync(mirror, config(), p -> {}, clock, StoreSync::http).get();
    assertEquals("partial", wrongHash.state()); assertFalse(wrongHash.refusals().isEmpty());
    assertTrue(catalog(mirror).files().isEmpty()); assertFalse(Files.exists(occupied));
  }

  @Test void aContinuouslyChangingPrefixEndsThePassAndKeepsItsJournal() throws Exception {
    session("changing", "2026-01-19", "RIO", null, 100, 1);
    var checks = new java.util.concurrent.atomic.AtomicInteger();
    var result = org.triplehelix.wpilogmcp.store.LegacyMirrorFixture.sync(mirror, config(), p -> {}, clock, address -> {
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
    var file = copiedByAnEarlierVersion("live", "2026-01-19", List.of(capture(true, 100, 1))).get(0);
    var offsets = new ArrayList<Long>(); var hashes = new ArrayList<Long>();
    var result = org.triplehelix.wpilogmcp.store.LegacyMirrorFixture.sync(mirror, config(0, 1, List.of(), List.of()), p -> {}, clock, observed(offsets, hashes)).get();
    assertEquals(List.of("live"), result.evicted()); assertEquals(List.of(Files.size(file)), hashes);
    assertFalse(Files.exists(copy(file))); assertTrue(catalog(mirror).sessions().isEmpty());
    var changed = copiedByAnEarlierVersion("changed", "2026-01-19", List.of(capture(true, 100, 1))).get(0);
    session("changed", "2026-01-19", "RIO", null, List.of(capture(true, 100, 10)));
    result = org.triplehelix.wpilogmcp.store.LegacyMirrorFixture.sync(mirror, config(0, 1, List.of(), List.of()), p -> {}, clock, observed(offsets, hashes)).get();
    assertTrue(result.evicted().isEmpty(), result.toString()); assertTrue(Files.exists(copy(changed)));
    assertTrue(result.retained().stream().anyMatch(reason -> reason.startsWith("changed:")), result.toString());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void aGrowingPrefixWithoutAnAgreeingJournalIsRetained(boolean missing) throws Exception {
    var file = copiedByAnEarlierVersion("live", "2026-01-19", List.of(capture(true, 100, 1))).get(0);
    byte[] held = Files.readAllBytes(copy(file));
    var io = new StoreFiles(mirror.root(), manager.testGetSecurityValidator());
    var journal = io.read(MirrorSync.journalPath(mirror.root()), org.triplehelix.wpilogmcp.sync.PullManifest.class);
    var entry = journal.files().get(0);
    var changed = new org.triplehelix.wpilogmcp.sync.PullManifest.Entry(entry.remoteName(), entry.size(), entry.mtimeMillis(),
        entry.bytesCopied() - 1, false, entry.localName(), 0, null);
    io.write(MirrorSync.journalPath(mirror.root()), new org.triplehelix.wpilogmcp.sync.PullManifest(1, null,
        missing ? List.of() : List.of(changed), List.of()));
    var result = sync(config(0, 1, List.of(), List.of()));
    assertTrue(result.evicted().isEmpty()); assertArrayEquals(held, Files.readAllBytes(copy(file)));
    assertEquals(1, catalog(mirror).sessions().size());
    assertTrue(result.retained().stream().anyMatch(reason -> reason.startsWith("live:") && reason.contains("retained")), result.toString());
  }

  @Test void pendingFileAndDirectoryMovesRecoverBeforeContactingAnOfflineOrigin() throws Exception {
    var file = session("boot", "2026-01-19", "RIO", null, 100, 1);
    assertThrows(java.util.concurrent.ExecutionException.class, () -> org.triplehelix.wpilogmcp.store.LegacyMirrorFixture.sync(mirror, config(), p -> {
      throw new IllegalStateException("stop before verification");
    }, clock, StoreSync::http).get());
    var io = new StoreFiles(mirror.root(), manager.testGetSecurityValidator());
    var journal = io.read(MirrorSync.journalPath(mirror.root()), org.triplehelix.wpilogmcp.sync.PullManifest.class);
    var entry = journal.files().get(0); var destination = copy(file); Files.createDirectories(destination.getParent());
    io.write(mirror.root().resolve(".mirror/pending.json"), java.util.Map.of("from", entry.localName(),
        "to", StoreFiles.relative(mirror.root(), destination), "hash", StoreFiles.hash(io.resolve(mirror.root(), entry.localName()))));
    Files.move(io.resolve(mirror.root(), entry.localName()), destination);
    StoreSync.Source offline = address -> { throw new java.io.IOException("offline"); };
    assertEquals("offline", org.triplehelix.wpilogmcp.store.LegacyMirrorFixture.sync(mirror, config(), p -> {}, clock, offline).get().state());
    journal = io.read(MirrorSync.journalPath(mirror.root()), org.triplehelix.wpilogmcp.sync.PullManifest.class);
    assertEquals(StoreFiles.relative(mirror.root(), destination), journal.files().get(0).localName());
    assertFalse(Files.exists(mirror.root().resolve(".mirror/pending.json"))); sync();
    var from = destination.getParent(); var to = from.resolveSibling("renamed");
    io.write(mirror.root().resolve(".mirror/pending.json"), java.util.Map.of("from", StoreFiles.relative(mirror.root(), from),
        "to", StoreFiles.relative(mirror.root(), to), "session_id", "boot"));
    Files.move(from, to);
    assertEquals("offline", org.triplehelix.wpilogmcp.store.LegacyMirrorFixture.sync(mirror, config(), p -> {}, clock, offline).get().state());
    assertEquals(StoreFiles.relative(mirror.root(), to), catalog(mirror).header().origin().sessions().get("boot").path());
    assertEquals(to.resolve(file.getFileName()), manager.stores().resolveMoved(destination));
    assertFalse(Files.exists(mirror.root().resolve(".mirror/pending.json")));
  }

  @Test void anUnreadableOriginFileIsRefusedEvenWhenItsAdvertisedHashMatches() throws Exception {
    var file = session("broken", "2026-01-19", "RIO", null, 100, 1);
    // Corrupt a generated fixture's magic, then honestly advertise the corrupted bytes' hash.
    byte[] bytes = Files.readAllBytes(file); bytes[0] ^= 1; Files.write(file, bytes);
    var manifest = file.resolveSibling("session.json");
    var json = com.google.gson.JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
    json.getAsJsonArray("files").get(0).getAsJsonObject().addProperty("sha256", StoreFiles.hash(file));
    Files.writeString(manifest, json.toString());
    var result = org.triplehelix.wpilogmcp.store.LegacyMirrorFixture.sync(mirror, config(), p -> {}, clock, StoreSync::http).get();
    assertEquals("partial", result.state()); assertEquals(2 * bytes.length, result.bytesCopied());
    assertFalse(result.refusals().isEmpty()); assertTrue(catalog(mirror).files().isEmpty());
    assertFalse(Files.exists(copy(file)), "A matching hash alone must not admit an unreadable log");
  }

  @Test void neitherAnExistingStoreNorADifferentOriginIdCanBecomeThisMirror() throws Exception {
    var source = session("boot", "2026-01-19", "RIO", null, 100, 1); sync();
    var existing = manager.stores().store(temp.resolve("existing"));
    existing.importPaths(new LogStore.Request(List.of(source), false, "RIO"), p -> {}).get();
    var c = new MirrorConfig(url(), existing.root(), 14, 20_000_000_000L, List.of(), List.of(), 30, 0);
    assertThrows(java.util.concurrent.ExecutionException.class, () -> org.triplehelix.wpilogmcp.store.LegacyMirrorFixture.sync(existing, c, p -> {}, clock, StoreSync::http).get());
    var before = Files.readAllBytes(copy(source));
    origin.capture(io -> {
      var h = io.read(origin.root().resolve("store.json"), Header.class);
      io.write(origin.root().resolve("store.json"), new Header(h.formatVersion(), h.createdAt(), "different", h.moves())); return null;
    });
    var refused = org.triplehelix.wpilogmcp.store.LegacyMirrorFixture.sync(mirror, config(), p -> {}, clock, StoreSync::http).get();
    assertEquals("offline", refused.state()); assertTrue(refused.reason().contains("different store id"));
    assertArrayEquals(before, Files.readAllBytes(copy(source)));
  }
}
