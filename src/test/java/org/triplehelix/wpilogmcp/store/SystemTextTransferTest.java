/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import static org.triplehelix.wpilogmcp.fixtures.SystemSessionFixture.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.pull.FakeRobot;
import org.triplehelix.wpilogmcp.config.MirrorConfig;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.*;
import org.triplehelix.wpilogmcp.sync.*;
import org.triplehelix.wpilogmcp.tools.*;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

class SystemTextTransferTest {
  @TempDir Path temp;
  final LogManager manager = LogManager.getInstance(); Set<Path> allowed;
  LogStore origin, copy; Path capture; HttpTransport http, localHttp;
  @BeforeEach void setup() throws Exception {
    temp = temp.toRealPath(); allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    capture = create(temp.resolve("origin"), "boot", true, 42);
    origin = manager.stores().store(temp.resolve("origin")); copy = manager.stores().store(temp.resolve("copy"));
    copy.importPaths(new LogStore.Request(List.of(), false, null), p -> {}).get();
    var pull = origin.systemPulls(FakeRobot.device(SERIAL, "SHA256:fixture"), WALL); assertTrue(pull.beginPass());
    pull.kernel(List.of("[105.0] warning synthetic kernel"), 110);
    var remote = new FakeRobot(); remote.files.put("/var/log/messages.1", "2026-03-07T14:22:38Z error synthetic syslog\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    pull.sources(Map.of("/var/log/messages.1", "syslog")); var clock = new java.util.concurrent.atomic.AtomicLong();
    var transfer = new FileTransfer(remote, pull, pull.manifest(), Long.MAX_VALUE, clock::get, () -> true);
    for (int n = 0; n < 10; n++) { var r = transfer.step(); clock.addAndGet(Math.max(1, r.waitUs())); if (r.status() == FileTransfer.Status.IDLE) break; assertNotEquals(FileTransfer.Status.REFUSED, r.status()); }
    origin.capture(io -> {
      var path = capture.resolveSibling("session.json"); var s = io.read(path, Session.class); var open = s.openCapture();
      var file = new LogFile(open.path(), StoreFiles.hash(capture), Files.size(capture), "wpilog", open.provenance(), true, 10, 20, s.startedAt(), s.endedAt(), s.startBasis(), false, null);
      io.write(path, new Session(s.id(), s.startedAt(), s.endedAt(), s.startBasis(), null, null, null, null, List.of(file), null, "closed", s.deviceIdentity(), s.identityConflicts(), s.conflicts(), s.captureStats(), s.systemLogs())); return null;
    });
    var registry = new ToolRegistry(); WpilogTools.registerAll(registry);
    http = new HttpTransport(registry, 0); http.setStoreDirectories(Set.of(origin.root())); http.start();
    localHttp = new HttpTransport(registry, 0); localHttp.setStoreDirectories(Set.of(copy.root())); localHttp.start();
  }
  @AfterEach void cleanup() throws Exception {
    if (http != null) http.stop(); if (localHttp != null) localHttp.stop(); manager.release(temp);
    manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory);
  }
  String url() { return "http://127.0.0.1:" + http.getPort(); }
  MirrorConfig config() { return new MirrorConfig(url(), copy.root(), 14, 1000000, List.of(), List.of(), 30, 0); }
  Path kernel() { return capture.getParent().resolve("robot/system/dmesg.txt"); }
  com.google.gson.JsonObject search(Path file) throws Exception {
    var args = new com.google.gson.JsonObject(); args.addProperty("path", file.toString());
    return new SearchSystemLogsTool().execute(args).getAsJsonObject();
  }
  void assertSearch(Path file) throws Exception {
    var result = search(file); assertEquals("ok", result.get("status").getAsString(), result::toString);
    assertEquals(2, result.get("total_matches").getAsInt());
    var rows = result.getAsJsonArray("matches").asList().stream().map(com.google.gson.JsonElement::getAsJsonObject).collect(java.util.stream.Collectors.toMap(r -> r.get("source").getAsString(), r -> r));
    assertEquals(12.5, rows.get("kernel").get("timestamp_sec").getAsDouble()); assertEquals(15., rows.get("syslog").get("timestamp_sec").getAsDouble());
  }
  @Test void doorListsSharedIndexAndServesOnlyManifestedSystemPrefixes() throws Exception {
    var client = HttpClient.newHttpClient(); String path = StoreFiles.relative(origin.root(), kernel());
    var response = client.send(HttpRequest.newBuilder(URI.create(url() + "/store/files/" + path)).header("Range", "bytes=2-8").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(206, response.statusCode(), () -> new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
    assertArrayEquals(Arrays.copyOfRange(Files.readAllBytes(kernel()), 2, 9), response.body());
    try (var remote = new HttpRemoteFiles(url())) {
      assertEquals(StoreFiles.hash(kernel()), remote.prefixHash(path, Files.size(kernel())).orElseThrow());
      assertEquals(3, remote.list().size());
    }
    var listing = client.send(HttpRequest.newBuilder(URI.create(url() + "/store/sessions")).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertTrue(listing.body().contains(StoreFiles.hash(kernel()))); assertTrue(listing.body().contains("written_span"));
    Files.writeString(kernel().resolveSibling("stray.txt"), "not published");
    assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(url() + "/store/files/" + path.replace("dmesg.txt", "stray.txt"))).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
    byte[] committed = Files.readAllBytes(kernel());
    Files.writeString(kernel(), "not committed\n", java.nio.file.StandardOpenOption.APPEND);
    var body = client.send(HttpRequest.newBuilder(URI.create(url() + "/store/files/" + path)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    assertArrayEquals(committed, body.body(), "The door exposes the receipt, not an uncommitted append");
  }
  @Test void twoServersSyncKernelAndSharedIndexThenCopyNothingOnTheNextPass() throws Exception {
    var result = copy.sync(url(), 0, p -> {}, WALL, StoreSync::http).get(20, TimeUnit.SECONDS);
    assertTrue(result.stopped().isEmpty(), result::toString); assertTrue(result.refusals().isEmpty(), result::toString);
    assertEquals(3, result.filesCopied().size());
    var local = StoreCatalog.readManaged(copy.root(), manager.testGetSecurityValidator());
    assertSearch(local.files().get(0).path());
    assertEquals(1, SystemLogIndex.read(new StoreFiles(copy.root(), manager.testGetSecurityValidator()), copy.root().resolve("robots").resolve(SERIAL)).files().size());
    assertEquals(0, copy.sync(url(), 0, p -> {}, WALL, StoreSync::http).get(20, TimeUnit.SECONDS).filesCopied().size());
    try (var remote = new HttpRemoteFiles("http://127.0.0.1:" + localHttp.getPort())) { assertEquals(3, remote.list().size()); }
  }
  @Test void aPeerKeepsOlderRotationsWhileAMirrorSelectsOnlySessionSpans() throws Exception {
    origin.capture(io -> {
      var robot = origin.root().resolve("robots").resolve(SERIAL); var text = robot.resolve("system/old.txt");
      Files.writeString(text, "2020-01-01T00:00:00Z warning earlier rotation\n");
      var file = new SystemLogState.File(SystemLogState.Location.STORE, StoreFiles.relative(origin.root(), text), "syslog", "text", StoreFiles.hash(text), Files.size(text),
          new Provenance("pulled", "/var/log/messages.9", "messages.9", WALL.instant().toString(), false, SERIAL), null);
      SystemLogIndex.put(io, robot, new SystemLogIndex.Entry(file, SystemLogIndex.span(text, "text"))); return null;
    });
    var result = copy.sync(url(), 0, p -> {}, WALL, StoreSync::http).get();
    assertTrue(result.stopped().isEmpty(), result::toString); assertEquals(4, result.filesCopied().size());
    assertEquals(2, SystemLogIndex.read(new StoreFiles(copy.root(), manager.testGetSecurityValidator()), copy.root().resolve("robots").resolve(SERIAL)).files().size());
    var mirror = manager.stores().store(temp.resolve("mirror"));
    var config = new MirrorConfig(url(), mirror.root(), 14, 1000000, List.of(), List.of(), 30, 0);
    var mirrored = mirror.mirror(config, p -> {}, WALL, StoreSync::http).get(); assertEquals("synchronized", mirrored.state(), mirrored::toString);
    assertEquals(1, SystemLogIndex.read(new StoreFiles(mirror.root(), manager.testGetSecurityValidator()), mirror.root().resolve("robots").resolve(SERIAL)).files().size());
    assertSearch(StoreCatalog.readManaged(copy.root(), manager.testGetSecurityValidator()).files().get(0).path());
  }
  @Test void mirrorSearchesItsCopiesAndRefusesBytesThatDisagreeWithTheAdvertisedHash() throws Exception {
    var bad = copy.mirror(config(), p -> {}, WALL, address -> {
      var peer = StoreSync.http(address); var remote = peer.remote();
      return new StoreSync.Peer(peer.url(), peer.description(), peer.robots(), peer.sessions(), new RemoteFiles() {
        public List<File> list() throws java.io.IOException { return remote.list(); }
        public byte[] read(String name, long offset, int count) throws java.io.IOException {
          var bytes = remote.read(name, offset, count); if (name.endsWith("dmesg.txt") && offset == 0 && bytes.length > 0) bytes[0] ^= 1; return bytes;
        }
        public Optional<String> prefixHash(String name, long length) throws java.io.IOException {
          if (!name.endsWith("dmesg.txt")) return remote.prefixHash(name, length);
          try { return Optional.of(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(read(name, 0, (int) length)))); }
          catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
        }
      });
    }).get(20, TimeUnit.SECONDS);
    assertEquals("partial", bad.state(), bad::toString); assertFalse(bad.refusals().isEmpty());
    assertFalse(Files.exists(copy.root().resolve(origin.root().relativize(kernel()))), "Unverified system bytes must not be installed");
    var good = copy.mirror(config(), p -> {}, WALL, StoreSync::http).get(20, TimeUnit.SECONDS);
    assertEquals("synchronized", good.state(), good::toString);
    assertSearch(copy.root().resolve(origin.root().relativize(capture)));
  }
  @Test void aMirrorManifestAheadOfItsTextNamesTheCollectingServer() throws Exception {
    copy.mirror(config(), p -> {}, WALL, address -> { throw new java.io.IOException("offline fixture"); }).get();
    Path destination = copy.root().resolve(origin.root().relativize(capture)); Files.createDirectories(destination.getParent());
    Files.copy(capture, destination); Files.copy(capture.resolveSibling("session.json"), destination.resolveSibling("session.json"));
    var robot = copy.root().resolve("robots").resolve(SERIAL); Files.copy(origin.root().resolve("robots").resolve(SERIAL).resolve("robot.json"), robot.resolve("robot.json"));
    var result = search(destination); assertEquals("not_applicable", result.get("status").getAsString(), result::toString);
    assertTrue(result.toString().contains(url()), result::toString); assertFalse(result.toString().contains("Internal error"));
  }
  @Test void mirrorCapIncludesSharedTextAndEvictsItOnlyAfterItsLastSession() throws Exception {
    var cap = new MirrorConfig(url(), copy.root(), 14, Files.size(capture) + Files.size(kernel()), List.of(), List.of(), 30, 0);
    var refused = copy.mirror(cap, p -> {}, WALL, StoreSync::http).get(20, TimeUnit.SECONDS);
    assertEquals("synchronized", refused.state(), refused::toString);
    assertTrue(StoreCatalog.readManaged(copy.root(), manager.testGetSecurityValidator()).sessions().isEmpty(), "The shared syslog consumes capacity too");
    copy.mirror(config(), p -> {}, WALL, StoreSync::http).get(20, TimeUnit.SECONDS);
    var tiny = new MirrorConfig(url(), copy.root(), 0, 1, List.of(), List.of(), 30, 0);
    var evicted = copy.mirror(tiny, p -> {}, WALL, StoreSync::http).get(20, TimeUnit.SECONDS);
    assertEquals(List.of("boot"), evicted.evicted());
    var io = new StoreFiles(copy.root(), manager.testGetSecurityValidator());
    assertTrue(SystemLogIndex.read(io, copy.root().resolve("robots").resolve(SERIAL)).files().isEmpty());
  }
  @Test void growingKernelTextResumesOnBothPathsWithoutDuplicatingTheReceipt() throws Exception {
    copy.sync(url(), 0, p -> {}, WALL, StoreSync::http).get();
    var mirror = manager.stores().store(temp.resolve("mirror"));
    var config = new MirrorConfig(url(), mirror.root(), 14, 1000000, List.of(), List.of(), 30, 0);
    mirror.mirror(config, p -> {}, WALL, StoreSync::http).get();
    var renamed = capture.getParent().resolveSibling("renamed-event");
    Files.move(capture.getParent(), renamed); capture = renamed.resolve("capture.wpilog");
    long held = Files.size(kernel());
    origin.capture(io -> {
      var path = capture.resolveSibling("session.json"); var s = io.read(path, Session.class);
      Files.writeString(kernel(), "[110.0] warning later\n", java.nio.file.StandardOpenOption.APPEND);
      var old = s.systemLogs().files().get(0);
      var receipt = new SystemLogState.File(old.location(), old.path(), old.source(), old.format(), StoreFiles.hash(kernel()), Files.size(kernel()), old.provenance(), old.note());
      io.write(path, s.withSystemLogs(s.systemLogs().withFiles(List.of(receipt)))); return null;
    });
    var offsets = new ArrayList<Long>(); var proofs = new ArrayList<Long>();
    StoreSync.Source spy = address -> {
      var peer = StoreSync.http(address); var remote = peer.remote();
      return new StoreSync.Peer(peer.url(), peer.description(), peer.robots(), peer.sessions(), new RemoteFiles() {
        public List<File> list() throws java.io.IOException { return remote.list(); }
        public byte[] read(String name, long offset, int count) throws java.io.IOException { if (name.endsWith("dmesg.txt")) offsets.add(offset); return remote.read(name, offset, count); }
        public Optional<String> prefixHash(String name, long length) throws java.io.IOException { if (name.endsWith("dmesg.txt")) proofs.add(length); return remote.prefixHash(name, length); }
        public void close() throws java.io.IOException { remote.close(); }
      });
    };
    var copied = copy.sync(url(), 0, p -> {}, WALL, spy).get();
    assertTrue(copied.stopped().isEmpty(), copied::toString); assertTrue(copied.refusals().isEmpty(), copied::toString);
    assertEquals(List.of(held), offsets); assertTrue(proofs.contains(held)); offsets.clear(); proofs.clear();
    var mirrored = mirror.mirror(config, p -> {}, WALL, spy).get();
    assertEquals("synchronized", mirrored.state(), mirrored::toString);
    assertEquals(List.of(held), offsets); assertTrue(proofs.contains(held));
    for (var store : List.of(copy, mirror)) {
      var local = StoreCatalog.readManaged(store.root(), manager.testGetSecurityValidator());
      assertEquals(1, local.sessions().get(0).session().systemLogs().files().size());
      assertEquals(3, search(local.files().get(0).path()).get("total_matches").getAsInt());
    }
  }
  @Test void anInterruptedTextTransferNamesItsPrefixAndResumesIt() throws Exception {
    origin.capture(io -> {
      var path = capture.resolveSibling("session.json"); var s = io.read(path, Session.class); var old = s.systemLogs().files().get(0);
      Files.writeString(kernel(), "[105.0] warning synthetic\n".repeat(6000));
      var receipt = new SystemLogState.File(old.location(), old.path(), old.source(), old.format(), StoreFiles.hash(kernel()), Files.size(kernel()), old.provenance(), old.note());
      io.write(path, s.withSystemLogs(s.systemLogs().withFiles(List.of(receipt)))); return null;
    });
    var drop = new java.util.concurrent.atomic.AtomicBoolean(true); var offsets = new ArrayList<Long>();
    StoreSync.Source source = address -> {
      var peer = StoreSync.http(address); var remote = peer.remote();
      return new StoreSync.Peer(peer.url(), peer.description(), peer.robots(), peer.sessions(), new RemoteFiles() {
        public List<File> list() throws java.io.IOException { return remote.list(); }
        public byte[] read(String name, long offset, int count) throws java.io.IOException {
          if (name.endsWith("dmesg.txt")) { offsets.add(offset); if (offset > 0 && drop.get()) throw new java.io.IOException("scripted peer stopped"); }
          return remote.read(name, offset, count);
        }
        public Optional<String> prefixHash(String name, long length) throws java.io.IOException { return remote.prefixHash(name, length); }
        public void close() throws java.io.IOException { remote.close(); }
      });
    };
    var interrupted = copy.sync(url(), 0, p -> {}, WALL, source).get();
    assertEquals(1, interrupted.stopped().size());
    assertEquals(StoreFiles.relative(origin.root(), kernel()), interrupted.stopped().get(0).remotePath());
    assertEquals(FileTransfer.BLOCK_BYTES, interrupted.stopped().get(0).bytesCopied());
    assertTrue(StoreCatalog.readManaged(copy.root(), manager.testGetSecurityValidator()).sessions().get(0).session().systemLogs().files().isEmpty());
    drop.set(false); offsets.clear();
    var resumed = copy.sync(url(), 0, p -> {}, WALL, source).get(); assertTrue(resumed.stopped().isEmpty(), resumed::toString);
    assertEquals(FileTransfer.BLOCK_BYTES, offsets.get(0));
    assertEquals(StoreFiles.hash(kernel()), StoreCatalog.readManaged(copy.root(), manager.testGetSecurityValidator()).sessions().get(0).session().systemLogs().files().get(0).sha256());
  }
  @Test void twoMirroredSessionsCountOneSharedRotationAndEvictionKeepsTheRemainingUsersText() throws Exception {
    var second = create(origin.root(), "second", false, 43);
    long shared = SystemLogIndex.read(new StoreFiles(origin.root(), manager.testGetSecurityValidator()), origin.root().resolve("robots").resolve(SERIAL)).files().get(0).file().sizeBytes();
    long allBytes = Files.size(capture) + Files.size(kernel()) + Files.size(second) + shared;
    var both = new MirrorConfig(url(), copy.root(), 14, allBytes, List.of(), List.of(), 30, 0);
    var complete = copy.mirror(both, p -> {}, WALL, StoreSync::http).get(); assertEquals("synchronized", complete.state(), complete::toString);
    assertEquals(2, StoreCatalog.readManaged(copy.root(), manager.testGetSecurityValidator()).sessions().size(), "Shared bytes count once");
    var one = new MirrorConfig(url(), copy.root(), 14, allBytes - Files.size(second), List.of(), List.of(), 30, 0);
    var trimmed = copy.mirror(one, p -> {}, WALL, StoreSync::http).get(); assertEquals(List.of("second"), trimmed.evicted());
    assertSearch(copy.root().resolve(origin.root().relativize(capture)));
  }
  @Test void consolidatedLocalFragmentsKeepTheirSystemTextInTheDoor() throws Exception {
    for (String name : List.of("first", "second")) {
      var log = create(copy.root(), name, false, name.equals("first") ? 43 : 44);
      copy.capture(io -> {
        var path = log.resolveSibling("session.json"); var s = io.read(path, Session.class);
        var text = log.getParent().resolve("robot/system/dmesg.txt"); Files.createDirectories(text.getParent());
        Files.writeString(text, "[105.0] warning " + name + " fragment\n");
        var file = new SystemLogState.File(SystemLogState.Location.SESSION, "robot/system/dmesg.txt", "kernel", "dmesg", StoreFiles.hash(text), Files.size(text),
            new Provenance("pulled", "dmesg", "dmesg", WALL.instant().toString(), false, SERIAL), null);
        io.write(path, s.withSystemLogs(SystemLogState.EMPTY.withFiles(List.of(file)))); return null;
      });
    }
    var result = copy.sync(url(), 0, p -> {}, WALL, StoreSync::http).get(); assertTrue(result.stopped().isEmpty(), result::toString);
    var local = StoreCatalog.readManaged(copy.root(), manager.testGetSecurityValidator()); assertEquals(1, local.sessions().size());
    assertEquals(3, local.sessions().get(0).session().systemLogs().files().size(), "Consolidation must carry both local fragments and the peer's kernel");
    try (var remote = new HttpRemoteFiles("http://127.0.0.1:" + localHttp.getPort())) {
      var text = remote.list().stream().filter(f -> f.name().endsWith("dmesg.txt")).toList(); assertEquals(3, text.size());
      for (var file : text) assertEquals(file.size(), remote.read(file.name(), 0, (int) file.size()).length);
    }
  }
}
