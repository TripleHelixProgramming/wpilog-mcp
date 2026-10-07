/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.config.CaptureConfig;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.ScopedLogReader;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.store.*;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

class CaptureRecoveryTest {
  @TempDir Path directory;
  private java.util.Set<Path> allowed;
  static final Clock WALL = Clock.fixed(Instant.parse("2026-03-07T14:22:33Z"), ZoneOffset.UTC);
  @org.junit.jupiter.api.BeforeEach void allow() {
    allowed = LogManager.getInstance().getAllowedDirectories();
    LogManager.getInstance().addAllowedDirectory(directory);
  }
  @org.junit.jupiter.api.AfterEach void restore() throws Exception {
    var manager = LogManager.getInstance(); manager.release(directory);
    manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory);
  }
  static CaptureConfig config(Path store) {
    return new CaptureConfig(List.of(RobotAddress.uri("127.0.0.1", 9, "recovery")), store, 0.01, CapturePolicy.ALL, 0);
  }
  private StoreCatalog.Snapshot catalog(Path store) throws Exception {
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    return StoreCatalog.read(store, security);
  }
  static Session manifest(Path file) throws Exception {
    return StoreJson.JSON.fromJson(Files.readString(file.resolveSibling("session.json")), Session.class);
  }
  static Path plant(Path store, boolean readable, boolean truncated) throws Exception {
    var file = LogManager.getInstance().stores().store(store).captures(WALL).create("127.0.0.1", WALL.instant());
    if (readable) {
      try (var fixture = new WpilogWriter(file, "synthetic recovery")) {
        int id = fixture.start("NT:/x", "int64", "", 0);
        fixture.append(id, 2_000_000, WpilogWriter.encodeInt64(4));
        fixture.append(id, 5_000_000, WpilogWriter.encodeInt64(10));
        fixture.finish(id, 5_000_000);
      }
      if (truncated) Files.write(file, new byte[] {0, 1}, java.nio.file.StandardOpenOption.APPEND);
    } else Files.writeString(file, "not a WPILOG file");
    Files.setLastModifiedTime(file, FileTime.from(WALL.instant().plusSeconds(7)));
    var old = manifest(file);
    var open = new OpenCapture(file.getFileName().toString(),
        new Provenance("captured", null, file.getFileName().toString(), WALL.instant().toString(), false), 1, 99, 100);
    Files.writeString(file.resolveSibling("session.json"), StoreJson.JSON.toJson(new Session(old.id(), old.startedAt(),
        old.endedAt(), old.startBasis(), "Synthetic", "Qualification", 7, 9999, old.files(), open, null)));
    return file;
  }
  private void startup(Path root) throws Exception {
    try (var service = new CaptureService(config(root), LogManager.getInstance(), WALL,
        org.triplehelix.wpilogmcp.nt4.client.ClientScheduler.daemon())) {
      service.start().get(10, TimeUnit.SECONDS);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"{not json", "{\"id\":\"broken\"}"})
  void damagedSessionInventoryIsLoggedAndSkippedBeforeCaptureStarts(String damaged) throws Exception {
    var root = directory.resolve("store"); var bad = plant(root, true, false); var good = plant(root, true, false);
    var manifest = bad.resolveSibling("session.json"); Files.writeString(manifest, damaged);
    var messages = new ByteArrayOutputStream(); var stderr = System.err;
    var started = new java.util.concurrent.CountDownLatch(1);
    var delegate = org.triplehelix.wpilogmcp.nt4.client.ClientScheduler.daemon();
    var loop = new org.triplehelix.wpilogmcp.nt4.client.ClientScheduler() {
      public long nowUs() { return delegate.nowUs(); }
      public void execute(Runnable task) { started.countDown(); delegate.execute(task); }
      public void schedule(Runnable task, long delayUs) { delegate.schedule(task, delayUs); }
      public void close() { delegate.close(); }
    };
    try (var out = new PrintStream(messages, true, java.nio.charset.StandardCharsets.UTF_8);
        var service = new CaptureService(config(root), LogManager.getInstance(), WALL, loop)) {
      System.setErr(out);
      service.start().get(10, TimeUnit.SECONDS);
      assertTrue(started.await(10, TimeUnit.SECONDS), "Inventory damage must not prevent client startup");
      assertEquals(List.of(manifest(good).id()), service.live().sessions().stream().map(s -> s.session().id()).toList());
    } finally { System.setErr(stderr); }
    String logged = messages.toString(java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(logged.contains("Skipping invalid session inventory " + manifest.toRealPath()), logged);
    assertEquals(damaged, Files.readString(manifest), "Inventory must not repair or adopt damaged state");
    assertThrows(java.io.IOException.class, () -> catalog(root), "Import and door catalogs remain strict");
  }

  @Test void startupRecoversEveryAbandonedCaptureFromItsBytesIncludingAnIncompleteTail() throws Exception {
    var root = directory.resolve("store"); var first = plant(root, true, false); var second = plant(root, true, true);
    byte[] original = Files.readAllBytes(first); var modified = Files.getLastModifiedTime(first);
    var previous = first.resolveSibling("capture-previous.wpilog");
    Files.copy(first, previous, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
    var prior = new LogFile(previous.getFileName().toString(),
        java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original)),
        original.length, "wpilog", new Provenance("captured", null, previous.getFileName().toString(), WALL.instant().toString(), false),
        true, 2, 5, WALL.instant().toString(), modified.toInstant().toString(), "pit_clock", false, null);
    var initial = manifest(first);
    Files.writeString(first.resolveSibling("session.json"), StoreJson.JSON.toJson(new Session(initial.id(), initial.startedAt(),
        initial.endedAt(), initial.startBasis(), initial.event(), initial.matchType(), initial.matchNumber(), initial.teamNumber(),
        List.of(prior), initial.openCapture(), null)));
    startup(root);
    var catalog = catalog(root); assertTrue(catalog.openCaptures().isEmpty()); assertEquals(3, catalog.files().size());
    assertTrue(manifest(first).files().contains(prior), "Recovery preserves previously finalized rollover files");
    assertTrue(catalog.unmanaged().isEmpty());
    for (var stored : catalog.files()) {
      var file = stored.file(); var session = stored.session();
      assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(stored.path()))), file.sha256());
      assertEquals(Files.size(stored.path()), file.sizeBytes()); assertTrue(file.verified());
      assertEquals(2.0, file.minTimestampSec()); assertEquals(5.0, file.maxTimestampSec());
      assertEquals("captured", file.provenance().kind());
      assertEquals(Files.getLastModifiedTime(stored.path()).toInstant().toString(), session.endedAt());
      assertEquals(session.endedAt(), file.endedAt()); assertEquals("server stopped while recording", session.endReason());
      assertEquals("Synthetic", session.event()); assertEquals(7, session.matchNumber()); assertEquals(9999, session.teamNumber());
      assertEquals(stored.path().equals(second.toRealPath()), file.truncated());
    }
    assertArrayEquals(original, Files.readAllBytes(first)); assertEquals(modified, Files.getLastModifiedTime(first));
    String once = Files.readString(first.resolveSibling("session.json")); startup(root);
    assertEquals(once, Files.readString(first.resolveSibling("session.json")), "A second startup leaves completed facts alone");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void unreadableCaptureStaysOpenWithItsReasonAndDoesNotPreventOtherRecovery(boolean damagedHeader) throws Exception {
    var root = directory.resolve("store"); var bad = plant(root, false, false); plant(root, true, false);
    if (damagedHeader) Files.write(bad, new byte[] {'W', 'P', 'I', 'L', 'O', 'G', 0, 1, 100, 0, 0, 0});
    byte[] bytes = Files.readAllBytes(bad); var stderr = System.err; var messages = new ByteArrayOutputStream();
    try (var out = new PrintStream(messages, true, java.nio.charset.StandardCharsets.UTF_8)) {
      System.setErr(out); startup(root);
    } finally { System.setErr(stderr); }
    var manifest = manifest(bad); assertNotNull(manifest.openCapture()); assertTrue(manifest.files().isEmpty());
    assertNotNull(manifest.endReason());
    assertTrue(manifest.endReason().contains("recovery failed"), manifest.endReason());
    assertTrue(messages.toString(java.nio.charset.StandardCharsets.UTF_8).contains(manifest.endReason()));
    assertArrayEquals(bytes, Files.readAllBytes(bad));
    assertEquals(1, catalog(root).files().size()); assertNull(catalog(root).openCaptures().get(0).file().sha256());
  }

  @Test void startupLeavesAnOwnedFileAloneEvenAfterMappedReads() throws Exception {
    var root = directory.resolve("store"); var file = plant(root, true, false);
    String open = Files.readString(file.resolveSibling("session.json"));
    try (var writer = new WpilogOutput(file, 2, true)) {
      try (var reader = new ScopedLogReader(file)) { assertTrue(reader.reader().isValid()); }
      startup(root); startup(root);
      assertEquals(open, Files.readString(file.resolveSibling("session.json")));
      assertThrows(java.io.IOException.class, () -> { try (var duplicate = new WpilogOutput(file, 2, true)) {} });
    }
    startup(root); assertNull(manifest(file).openCapture());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void anAbandonedRecorderSummaryIsNotPresentedAsFinalCounts(boolean readable) throws Exception {
    var root = directory.resolve("store"); var file = plant(root, readable, false);
    var old = manifest(file);
    var stale = new CaptureStats(1, 1, 14, java.util.Map.of(), List.of(), java.util.Map.of());
    Files.writeString(file.resolveSibling("session.json"), StoreJson.JSON.toJson(new Session(old.id(), old.startedAt(),
        old.endedAt(), old.startBasis(), old.event(), old.matchType(), old.matchNumber(), old.teamNumber(),
        old.files(), old.openCapture(), null, old.deviceIdentity(), old.identityConflicts(), old.conflicts(), stale)));
    startup(root);
    assertNull(manifest(file).captureStats(), "The last queued summary can predate records flushed before a crash");
    assertEquals(!readable, manifest(file).openCapture() != null);
  }

  @Test void startupWaitsBehindTheStoreQueueBeforeStartingTheClient() throws Exception {
    var root = directory.resolve("store"); var file = plant(root, true, false);
    var manager = LogManager.getInstance(); var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1); var threads = java.util.concurrent.Executors.newSingleThreadExecutor();
    var fixture = directory.resolve("import.wpilog"); Files.copy(file, fixture);
    var importing = manager.stores().store(root).importPaths(new LogStore.Request(List.of(fixture), false, null), progress -> {
      if (!progress.phase().equals("starting")) return;
      entered.countDown();
      try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    });
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      var starting = threads.submit(() -> { startup(root); return null; });
      assertThrows(java.util.concurrent.TimeoutException.class, () -> starting.get(100, TimeUnit.MILLISECONDS));
      assertNotNull(manifest(file).openCapture());
      release.countDown(); importing.get(10, TimeUnit.SECONDS); starting.get(10, TimeUnit.SECONDS);
      assertNull(manifest(file).openCapture());
    } finally { release.countDown(); threads.shutdownNow(); }
  }

  @Test void slowRecoveryDoesNotDelayHttpHealthOrStartNt4BeforeItsSweep() throws Exception {
    var root = directory.resolve("store"); var file = plant(root, true, false);
    var manager = LogManager.getInstance(); var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var clientStarted = new java.util.concurrent.CountDownLatch(1);
    var delegate = org.triplehelix.wpilogmcp.nt4.client.ClientScheduler.daemon();
    var loop = new org.triplehelix.wpilogmcp.nt4.client.ClientScheduler() {
      public long nowUs() { return delegate.nowUs(); }
      public void execute(Runnable task) { clientStarted.countDown(); delegate.execute(task); }
      public void schedule(Runnable task, long delayUs) { delegate.schedule(task, delayUs); }
      public void close() { delegate.close(); }
    };
    var threads = java.util.concurrent.Executors.newSingleThreadExecutor();
    var service = new java.util.concurrent.atomic.AtomicReference<CaptureService>();
    var http = new org.triplehelix.wpilogmcp.mcp.HttpTransport(new org.triplehelix.wpilogmcp.mcp.ToolRegistry(), 0);
    var fixture = directory.resolve("queued.wpilog"); Files.copy(file, fixture);
    var importing = manager.stores().store(root).importPaths(new LogStore.Request(List.of(fixture), false, null), progress -> {
      if (!progress.phase().equals("starting")) return;
      entered.countDown();
      try { assertTrue(release.await(30, TimeUnit.SECONDS)); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    });
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      // Main constructs capture before opening HTTP, then starts capture after HTTP is listening.
      var starting = threads.submit(() -> {
        service.set(new CaptureService(config(root), manager, WALL, loop));
        http.start(); service.get().start(); return http.getPort();
      });
      int port = starting.get(2, TimeUnit.SECONDS);
      var response = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
          java.net.URI.create("http://127.0.0.1:" + port + "/health")).timeout(java.time.Duration.ofSeconds(2)).build(),
          java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals(1, clientStarted.getCount(), "NT4 must wait until recovery has scanned and hashed the old file");
      assertNotNull(manifest(file).openCapture());
      release.countDown(); importing.get(10, TimeUnit.SECONDS);
      assertTrue(clientStarted.await(10, TimeUnit.SECONDS));
      assertNull(manifest(file).openCapture());
    } finally {
      release.countDown(); threads.shutdown(); assertTrue(threads.awaitTermination(10, TimeUnit.SECONDS));
      if (service.get() != null) service.get().close(); else loop.close();
      http.stop();
    }
  }

  @Test void failedOutputCreationReleasesItsLease() throws Exception {
    var file = directory.resolve("failed.wpilog");
    assertThrows(java.io.IOException.class, () -> new WpilogOutput(file) {
      @Override protected void write(java.nio.ByteBuffer bytes) throws java.io.IOException { throw new java.io.IOException("planted header failure"); }
    });
    assertTrue(Files.exists(CaptureLease.lockPath(file)), "Releasing a failed writer must not delete the lock inode");
    try (var lease = CaptureLease.tryAcquire(file).orElseThrow()) {
      assertTrue(Files.exists(CaptureLease.lockPath(file)), "Lease inodes stay stable after release");
    }
  }

  @Test void ownershipRefusesASymlinkSidecar() throws Exception {
    var file = directory.resolve("symlink.wpilog"); var target = directory.resolve("target");
    Files.writeString(target, "untouched");
    try { Files.createSymbolicLink(CaptureLease.lockPath(file), target); }
    catch (UnsupportedOperationException | java.io.IOException e) { org.junit.jupiter.api.Assumptions.abort("Symlinks unavailable: " + e.getMessage()); }
    var error = assertThrows(java.io.IOException.class, () -> CaptureLease.tryAcquire(file));
    assertTrue(error.getMessage().contains("symbolic link"), error.getMessage());
    assertEquals("untouched", Files.readString(target)); assertFalse(Files.exists(file));
  }

  @Test void aCaptureAliasUsesTheSameOwnershipLease() throws Exception {
    var file = directory.resolve("owner.wpilog"); var alias = directory.resolve("alias.wpilog");
    try (var output = new WpilogOutput(file)) {
      try { Files.createSymbolicLink(alias, file); }
      catch (UnsupportedOperationException | java.io.IOException e) { org.junit.jupiter.api.Assumptions.abort("Symlinks unavailable: " + e.getMessage()); }
      var other = CaptureLease.tryAcquire(alias);
      try { assertTrue(other.isEmpty(), "A path alias must not admit a recovery beside its writer"); }
      finally { if (other.isPresent()) other.get().close(); }
    }
  }

  private Process child(String mode, Path store, Path output) throws Exception {
    return new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
        "-Duser.home=" + directory, "-cp", System.getProperty("java.class.path"),
        CaptureRecoveryProcess.class.getName(), mode, store.toString())
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
  }

  @Test void aDifferentProcessOwnsItsCaptureUntilItDies() throws Exception {
    var root = directory.resolve("store"); var file = plant(root, true, false);
    var output = directory.resolve("owner.txt"); var child = child("hold", file, output);
    try {
      // A pipe handshake avoids sleeps and proves the child acquired ownership before recovery.
      var ready = child.getOutputStream(); ready.write(1); ready.flush();
      var signal = directory.resolve("owned.signal");
      var watcher = java.nio.file.FileSystems.getDefault().newWatchService();
      try (watcher) {
        directory.register(watcher, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);
        while (!Files.exists(signal)) {
          var key = watcher.poll(10, TimeUnit.SECONDS); assertNotNull(key, Files.readString(output)); key.reset();
        }
      }
      startup(root); assertNotNull(manifest(file).openCapture());
    } finally { child.destroyForcibly(); assertTrue(child.waitFor(10, TimeUnit.SECONDS)); }
    startup(root); assertNull(manifest(file).openCapture());
  }

  @Test void timedOutShutdownLeavesAnOpenManifestThatTheNextStartupCompletes() throws Exception {
    var root = directory.resolve("store"); var output = directory.resolve("shutdown.txt");
    var child = child("timeout", root, output);
    try { assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Shutdown exceeded its injected bound"); }
    finally { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS); }
    assertEquals(0, child.exitValue(), Files.readString(output));
    assertTrue(Files.readString(output).contains("next startup sweep"), Files.readString(output));
    var open = catalog(root).openCaptures(); assertEquals(1, open.size()); var file = open.get(0).path();
    startup(root);
    assertNull(manifest(file).openCapture()); assertEquals("server stopped while recording", manifest(file).endReason());
    assertEquals(10.0, catalog(root).files().get(0).file().minTimestampSec());
    assertEquals(12.0, catalog(root).files().get(0).file().maxTimestampSec());
  }

  @Test void theGuidesStateTheShutdownBoundRecoveryReasonAndSeedTime() throws Exception {
    for (String name : List.of("STANDALONE", "ARCHITECTURE", "PIT_SERVER_PLAN")) {
      String guide = Files.readString(Path.of("doc", name + ".md"));
      assertTrue(guide.contains(CaptureService.CLOSE_TIMEOUT.toSeconds() + " seconds"), name);
      assertTrue(guide.contains("server stopped while recording"), name);
      assertTrue(guide.contains("after HTTP is listening"), name);
      assertTrue(guide.contains("rollover") && guide.contains("server time"), name);
    }
  }
}
