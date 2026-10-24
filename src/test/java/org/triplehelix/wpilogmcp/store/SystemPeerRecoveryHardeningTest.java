/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.fixtures.SystemSessionFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.store.StoreManifest.Header;
import org.triplehelix.wpilogmcp.store.StoreManifest.Provenance;
import org.triplehelix.wpilogmcp.store.StoreManifest.Robot;
import org.triplehelix.wpilogmcp.store.StoreManifest.Session;
import org.triplehelix.wpilogmcp.sync.RemoteFiles;

/** A renamed text payload proves its bytes, not the identity of the selected session or robot. */
class SystemPeerRecoveryHardeningTest {
  @TempDir Path directory;
  private static final String PEER = "11111111-1111-4111-8111-111111111111";
  private static final String ROBOT = SystemSessionFixture.SERIAL;
  private static final String SESSION = "selected-session";
  private static final byte[] TEXT = "0123456789abcdef\n".getBytes(StandardCharsets.UTF_8);

  @ParameterizedTest
  @ValueSource(strings = {"session", "shared", "legacy-session", "legacy-shared"})
  void renamedTextRecoversExactlyOnceAndKeepsItsResumePrefix(String kind) throws Exception {
    boolean shared = kind.endsWith("shared");
    var crash = crash(shared);
    if (kind.startsWith("legacy-")) {
      var receipt = JsonParser.parseString(Files.readString(crash.receipt())).getAsJsonObject();
      // Retain precisely the old receipt fields, independent of later optional witnesses.
      var legacy = new JsonObject();
      for (String name : List.of("staged", "destination", "manifest", "robot_id", "entry", "committed"))
        if (receipt.has(name)) legacy.add(name, receipt.get(name));
      Files.writeString(crash.receipt(), legacy.toString());
    }
    try (var fixture = new Fixture(crash.root(), false);
         var publication = fixture.publication()) {
      assertEquals(17, TEXT.length);
      assertEquals(0, fixture.receipts(shared).size(), "The child stopped before the metadata receipt");
      assertArrayEquals(TEXT, Files.readAllBytes(crash.staged()));
      assertArrayEquals(TEXT, Files.readAllBytes(crash.destination()));
      SystemPeerSync.recover(publication, fixture.journalDirectory());
      assertOneReceipt(fixture, shared, crash);
      assertArrayEquals(TEXT, Files.readAllBytes(crash.staged()),
          "A system-text prefix remains available for content-checked growth");
      assertTrue(JsonParser.parseString(Files.readString(crash.receipt()))
          .getAsJsonObject().get("committed").getAsBoolean());
      SystemPeerSync.recover(publication, fixture.journalDirectory());
      assertOneReceipt(fixture, shared, crash);
      assertEquals(0, fixture.reads.get(), "Offline receipt recovery must not contact a peer");
      assertEquals(0, fixture.reservedBytes());
    }
  }

  @Test void aRenamedTextReceiptCannotAdoptAReplacementSession() throws Exception {
    var crash = crash(false);
    var changed = JsonParser.parseString(Files.readString(crash.metadata())).getAsJsonObject();
    changed.addProperty("id", "replacement-session");
    Files.writeString(crash.metadata(), changed.toString());
    try (var fixture = new Fixture(crash.root(), false);
         var publication = fixture.publication()) {
      assertThrows(IOException.class, () -> SystemPeerSync.recover(publication, fixture.journalDirectory()),
          "Matching destination bytes cannot replace the original selected session identity");
      assertEquals("replacement-session", fixture.session().id());
      assertEquals(0, fixture.receipts(false).size());
      assertArrayEquals(TEXT, Files.readAllBytes(crash.staged()));
      assertArrayEquals(TEXT, Files.readAllBytes(crash.destination()));
      assertEquals(0, fixture.reads.get());
      assertEquals(0, fixture.reservedBytes());
    }
  }

  @Test void aRenamedSharedReceiptCannotAdoptAReplacementRobotIdentity() throws Exception {
    var crash = crash(true);
    var manifest = crash.root().resolve("robots").resolve(ROBOT).resolve("robot.json");
    var changed = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
    changed.addProperty("serial_number", "REPLACEMENT-SYNTHETIC");
    Files.writeString(manifest, changed.toString());
    try (var fixture = new Fixture(crash.root(), false);
         var publication = fixture.publication()) {
      assertThrows(IOException.class, () -> SystemPeerSync.recover(publication, fixture.journalDirectory()),
          "A robot directory name cannot replace the selected robot identity across a crash");
      assertEquals("REPLACEMENT-SYNTHETIC", fixture.io.read(manifest, Robot.class).serialNumber());
      assertEquals(0, fixture.receipts(true).size());
      assertArrayEquals(TEXT, Files.readAllBytes(crash.staged()));
      assertArrayEquals(TEXT, Files.readAllBytes(crash.destination()));
      assertEquals(0, fixture.reads.get());
      assertEquals(0, fixture.reservedBytes());
    }
  }

  private static void assertOneReceipt(Fixture fixture, boolean shared, Crash crash) throws Exception {
    var receipts = fixture.receipts(shared);
    assertEquals(1, receipts.size());
    assertEquals(17, receipts.get(0).sizeBytes());
    assertEquals(hash(TEXT), receipts.get(0).sha256());
    assertArrayEquals(TEXT, Files.readAllBytes(crash.destination()));
    assertEquals(shared ? StoreFiles.relative(fixture.root, crash.destination())
        : StoreFiles.relative(fixture.manifest.getParent(), crash.destination()), receipts.get(0).path());
  }

  private record Crash(Path root, Path staged, Path destination, Path metadata, Path receipt) {}

  private Crash crash(boolean shared) throws Exception {
    var root = Files.createTempDirectory(directory, "system-peer-crash-").toRealPath();
    var ready = root.resolve("ready.json");
    var output = root.resolve("child.log");
    var process = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
        "-cp", System.getProperty("java.class.path"), CrashProcess.class.getName(),
        root.toString(), ready.toString(), Boolean.toString(shared))
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (!Files.exists(ready) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
      assertTrue(Files.exists(ready), () -> "Child did not rename its immutable text: " + readOutput(output));
      var state = JsonParser.parseString(Files.readString(ready)).getAsJsonObject();
      assertEquals(1, state.get("payload_reads").getAsInt());
      var staged = Path.of(state.get("staged").getAsString());
      var destination = Path.of(state.get("destination").getAsString());
      var metadata = Path.of(state.get("metadata").getAsString());
      var receipt = Path.of(state.get("receipt").getAsString());
      for (var path : List.of(staged, destination, metadata, receipt)) assertTrue(path.startsWith(root));
      process.destroyForcibly();
      assertTrue(process.waitFor(5, TimeUnit.SECONDS));
      // Its sync ownership and stage markers share this name.
      org.triplehelix.wpilogmcp.fixtures.ProcessLocks.awaitReleasedUnder(root, ".owner.lock");
      var pending = JsonParser.parseString(Files.readString(receipt)).getAsJsonObject();
      assertFalse(pending.get("committed").getAsBoolean());
      return new Crash(root, staged, destination, metadata, receipt);
    } finally {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
  }

  private static String readOutput(Path path) {
    try { return Files.readString(path); }
    catch (IOException failure) { return failure.toString(); }
  }

  private static String hash(byte[] bytes) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
  }

  /** Only synthetic text and the pure-Java fixture writer are used; no socket is opened. */
  private static final class Fixture implements AutoCloseable {
    final Path root, manifest;
    final LogManager manager = LogManager.getInstance();
    final Set<Path> previous;
    final SecurityValidator security = new SecurityValidator();
    final DiskSpaceBudgets budget = new DiskSpaceBudgets(filesystem -> 1_000_000_000);
    final StoreRegistry registry;
    final LogStore store;
    final StoreFiles io;
    final AtomicInteger reads = new AtomicInteger();

    Fixture(Path root, boolean create) throws Exception {
      this.root = root;
      if (create) SystemSessionFixture.create(root, SESSION, false, 42);
      previous = manager.getAllowedDirectories();
      manager.addAllowedDirectory(root);
      security.addAllowedDirectory(root);
      registry = new StoreRegistry(security, manager, StoreCatalog::discover, budget);
      store = registry.store(root);
      store.diskBudget().configureCapture(new DiskSpaceBudgets.Policy(100, 20, 30));
      io = new StoreFiles(root, security);
      manifest = root.resolve("robots").resolve(ROBOT).resolve("sessions/2026-03-07")
          .resolve(SESSION).resolve("session.json");
    }

    SyncPublication publication() throws IOException {
      return new SyncPublication(store, io, io.read(root.resolve("store.json"), Header.class).id());
    }
    Path journalDirectory() { return root.resolve(".sync").resolve("peer-" + PEER).resolve("system"); }
    Session session() throws IOException { return io.read(manifest, Session.class); }
    long reservedBytes() { return budget.snapshots().stream().mapToLong(DiskSpaceBudgets.Snapshot::reservedBytes).sum(); }
    List<SystemLogState.File> receipts(boolean shared) throws IOException {
      return shared ? SystemLogIndex.read(io, root.resolve("robots").resolve(ROBOT))
          .files().stream().map(SystemLogIndex.Entry::file).toList() : session().systemLogs().files();
    }

    StoreSync.Peer peer(boolean shared) throws IOException {
      String path = shared ? "robots/" + ROBOT + "/system/messages.txt" : "robot/system/program.log";
      var file = new SystemLogState.File(shared ? SystemLogState.Location.STORE : SystemLogState.Location.SESSION,
          path, shared ? "syslog" : "program", "text", hash(TEXT), TEXT.length,
          new Provenance("pulled", "/var/log/synthetic", "synthetic", SystemSessionFixture.WALL.instant().toString(),
              false, ROBOT), null);
      String remotePath = shared ? path : "remote-session/" + path;
      var remote = new RemoteFiles() {
        @Override public List<File> list() { return List.of(); }
        @Override public byte[] read(String name, long offset, int count) {
          assertEquals(remotePath, name); reads.incrementAndGet();
          return Arrays.copyOfRange(TEXT, Math.toIntExact(offset), Math.toIntExact(Math.min(TEXT.length, offset + count)));
        }
        @Override public Optional<String> prefixHash(String name, long length) {
          assertEquals(remotePath, name);
          return Optional.of(hash(Arrays.copyOf(TEXT, Math.toIntExact(length))));
        }
      };
      var remoteSession = session().withSystemLogs(session().systemLogs().withFiles(List.of(file)));
      var catalog = shared
          ? new StoreDoor.Sessions(List.of(), List.of(),
              List.of(new StoreDoor.SharedSystem(ROBOT, List.of(new SystemLogIndex.Entry(file, null)))))
          : new StoreDoor.Sessions(List.of(new StoreDoor.Session(ROBOT, "remote-session", remoteSession)), List.of());
      return new StoreSync.Peer("http://synthetic.invalid",
          new StoreDoor.Description(PEER, StoreManifest.FORMAT_VERSION, "synthetic", false),
          List.of(io.read(root.resolve("robots").resolve(ROBOT).resolve("robot.json"), Robot.class)), catalog, remote);
    }

    @Override public void close() {
      registry.close();
      budget.close();
      manager.clearAllowedDirectories();
      previous.forEach(manager::addAllowedDirectory);
    }
  }

  public static final class CrashProcess {
    public static void main(String[] args) throws Exception {
      var root = Path.of(args[0]);
      var ready = Path.of(args[1]);
      boolean shared = Boolean.parseBoolean(args[2]);
      try (var fixture = new Fixture(root, true);
           var publication = fixture.publication()) {
        var peer = fixture.peer(shared);
        var sync = new SystemPeerSync(publication, peer, SystemSessionFixture.WALL,
            new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), (boundary, staged, destination, metadata) -> {
              if (boundary != StoreSync.Boundary.RENAMED) return;
              var state = new JsonObject();
              state.addProperty("staged", staged.toString());
              state.addProperty("destination", destination.toString());
              state.addProperty("metadata", metadata.toString());
              state.addProperty("receipt", fixture.journalDirectory().resolve("placing.json").toString());
              state.addProperty("payload_reads", fixture.reads.get());
              var temporary = ready.resolveSibling("ready.tmp");
              Files.writeString(temporary, state.toString());
              Files.move(temporary, ready, StandardCopyOption.ATOMIC_MOVE);
              try { Thread.sleep(TimeUnit.MINUTES.toMillis(1)); }
              catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
            });
        sync.run(Map.of(SESSION, fixture.manifest), 0, progress -> {});
      }
    }
  }
}
