/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.fixtures.ChildJvms;
import org.triplehelix.wpilogmcp.sync.PullManifest;

/** Process death must preserve proven prefixes, and recovery must recheck its own witnesses. */
class StoreSyncRecoveryHardeningTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"STAGED", "RENAMED", "CROSS_RENAMED", "CROSS_COMMITTED", "PARTIAL"})
  void aKilledSyncRecoversExactlyOnceWithoutLosingItsProvenPrefix(String boundary) throws Exception {
    var crash = crash(boundary);
    boolean crossFilesystem = boundary.startsWith("CROSS_");
    boolean renamed = boundary.equals("RENAMED") || crossFilesystem;
    try (var peer = StoreSyncHardeningFixture.reopen(crash.directory())) {
      long size = peer.sizeBytes();
      assertEquals(crossFilesystem ? 1 : 0, crash.placementCopies(),
          "The cross-filesystem boundary must follow one real second copy");
      if (boundary.equals("PARTIAL")) {
        assertEquals(65_536, Files.size(crash.staged()),
            "The first complete 65,536-byte block reached the durable checkpoint");
      } else if (boundary.equals("STAGED")) {
        assertEquals(size, Files.size(crash.staged()));
        assertFalse(Files.exists(crash.destination()));
        assertEquals(0, receipts(peer.target.root()),
            "STAGED precedes admission and creation of a placement receipt");
      } else {
        if (crossFilesystem) {
          assertEquals(size, Files.size(crash.staged()),
              "Renaming the second copy leaves the original durable prefix until publication succeeds");
          assertArrayEquals(Files.readAllBytes(peer.source), Files.readAllBytes(crash.staged()));
        } else {
          assertFalse(Files.exists(crash.staged()), "The atomic rename consumed the staged path");
        }
        assertEquals(size, Files.size(crash.destination()));
        assertEquals(1, receipts(peer.target.root()));
        Path receipt;
        try (var paths = Files.walk(peer.target.root().resolve(".sync"))) {
          receipt = paths.filter(path -> path.getFileName().toString().equals("placing.json"))
              .findFirst().orElseThrow();
        }
        var placement = StoreJson.JSON.fromJson(Files.readString(receipt), StoreTransferInventory.Placement.class);
        assertEquals(boundary.equals("CROSS_COMMITTED"), placement.committed(),
            "The chosen process boundary distinguishes rename recovery from committed cleanup");
      }

      var openedAfterRecovery = new AtomicBoolean();
      peer.beforeOpen(() -> {
        var recovered = assertDoesNotThrow(peer::targetCatalog);
        assertEquals(renamed ? 1 : 0, recovered.files().size(),
            "An admitted rename receipt is recovered before the peer is contacted");
        openedAfterRecovery.set(true);
      });
      var result = peer.sync().get(15, TimeUnit.SECONDS);
      assertTrue(openedAfterRecovery.get());
      assertEquals(1, peer.peerOpens());
      assertEquals(List.of(), result.refusals(), result.toString());
      assertEquals(List.of(), result.stopped(), result.toString());
      assertEquals(renamed ? 0 : 1, result.filesCopied().size());
      if (boundary.equals("PARTIAL")) {
        assertFalse(peer.readTrace().isEmpty());
        assertEquals(65_536, peer.readTrace().get(0).offset());
        assertEquals(65_536, peer.readTrace().get(0).provedPrefixBytes(),
            "The resumed read must follow the peer's proof of the held first block");
        assertEquals(size - 65_536, peer.deliveredBytes());
        assertEquals(size - 65_536, result.filesCopied().get(0).bytes());
      } else {
        assertEquals(0, peer.payloadReads(), "A complete verified prefix needs no new payload bytes");
        assertEquals(0, peer.deliveredBytes());
        if (boundary.equals("STAGED"))
          assertTrue(peer.prefixProofs().contains(size), "The peer must prove the complete held prefix");
      }
      assertOneIndependentCopy(peer);
      assertNoStagedPayload(peer.target.root());
      assertEquals(0, peer.reservedBytes());

      // Exactly once includes the next sync, which must recognize the committed catalog copy.
      peer.beforeOpen(() -> {});
      var repeated = peer.anotherSync().get(15, TimeUnit.SECONDS);
      assertEquals(List.of(), repeated.filesCopied());
      assertEquals(List.of(), repeated.refusals(), repeated.toString());
      assertEquals(List.of(), repeated.stopped(), repeated.toString());
      assertOneIndependentCopy(peer);
      assertNoStagedPayload(peer.target.root());
    }
  }

  @Test void destinationAppearingAfterRecoveryVerificationIsNotAdopted() throws Exception {
    var crash = crash("BEFORE_RENAME");
    byte[] stray = "unverified destination\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    try (var peer = StoreSyncHardeningFixture.reopen(crash.directory())) {
      var reached = new AtomicBoolean();
      var result = peer.anotherSync((boundary, staged, destination, manifest) -> {
        if (boundary != StoreSync.Boundary.RECOVERY_VERIFIED) return;
        assertFalse(Files.exists(destination), "Recovery verified the source while the target was absent");
        Files.createDirectories(destination.getParent());
        Files.write(destination, stray);
        reached.set(true);
      });
      var failure = assertThrows(ExecutionException.class, () -> result.get(15, TimeUnit.SECONDS),
          "Recovery must refuse a destination which appeared after it verified the staged bytes");
      assertTrue(reached.get(), "The real recovery must reach its post-verification seam");
      assertInstanceOf(IOException.class, failure.getCause());
      assertEquals(0, peer.peerOpens(), "Refused recovery must happen before a new peer connection");
      assertArrayEquals(stray, Files.readAllBytes(crash.destination()));
      assertArrayEquals(Files.readAllBytes(peer.source), Files.readAllBytes(crash.staged()));
      var selected = StoreJson.JSON.fromJson(Files.readString(crash.manifest()), StoreManifest.Session.class);
      assertEquals(0, selected.files().size(), "Refusing recovery must precede writing a file receipt");
      assertEquals(0, peer.targetCatalog().files().size());
      assertEquals(0, peer.reservedBytes());
    }
  }

  @Test void sessionReplacedAfterRecoveryVerificationIsNotUsed() throws Exception {
    var crash = crash("BEFORE_RENAME");
    try (var peer = StoreSyncHardeningFixture.reopen(crash.directory())) {
      var reached = new AtomicBoolean();
      var result = peer.anotherSync((boundary, staged, destination, manifest) -> {
        if (boundary != StoreSync.Boundary.RECOVERY_VERIFIED) return;
        var changed = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        changed.addProperty("id", "replacement-session");
        Files.writeString(manifest, changed.toString());
        reached.set(true);
      });
      var failure = assertThrows(ExecutionException.class, () -> result.get(15, TimeUnit.SECONDS),
          "Recovery cannot associate the old receipt's bytes with a replacement session");
      assertTrue(reached.get());
      assertInstanceOf(IOException.class, failure.getCause());
      assertEquals(0, peer.peerOpens());
      assertFalse(Files.exists(crash.destination()));
      assertArrayEquals(Files.readAllBytes(peer.source), Files.readAllBytes(crash.staged()));
      var catalog = peer.targetCatalog();
      assertEquals(0, catalog.files().size());
      assertEquals("replacement-session", catalog.sessions().get(0).session().id());
      assertEquals(0, peer.reservedBytes());
    }
  }

  @Test void sessionReplacedAfterCrashCannotConsumeTheOldReceipt() throws Exception {
    var crash = crash("BEFORE_RENAME");
    var changed = JsonParser.parseString(Files.readString(crash.manifest())).getAsJsonObject();
    changed.addProperty("id", "replacement-before-restart");
    Files.writeString(crash.manifest(), changed.toString());
    try (var peer = StoreSyncHardeningFixture.reopen(crash.directory())) {
      var result = peer.anotherSync();
      var failure = assertThrows(ExecutionException.class, () -> result.get(15, TimeUnit.SECONDS),
          "The receipt must remember the selected session across process death");
      assertInstanceOf(IOException.class, failure.getCause());
      assertTrue(failure.getCause().getMessage().contains("session")
          || failure.getCause().getMessage().contains("manifest"), failure.getCause().getMessage());
      assertEquals(0, peer.peerOpens());
      assertFalse(Files.exists(crash.destination()));
      assertArrayEquals(Files.readAllBytes(peer.source), Files.readAllBytes(crash.staged()));
      var catalog = peer.targetCatalog();
      assertEquals(0, catalog.files().size());
      assertEquals("replacement-before-restart", catalog.sessions().get(0).session().id());
      assertEquals(0, peer.reservedBytes());
    }
  }

  private static void assertOneIndependentCopy(StoreSyncHardeningFixture peer) throws Exception {
    var files = peer.targetCatalog().files();
    assertEquals(1, files.size());
    byte[] expected = Files.readAllBytes(peer.source);
    String independent = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected));
    assertEquals(independent, files.get(0).file().sha256());
    assertEquals(expected.length, files.get(0).file().sizeBytes());
    assertArrayEquals(expected, Files.readAllBytes(files.get(0).path()));
  }

  private static long receipts(Path root) throws IOException {
    try (var paths = Files.walk(root.resolve(".sync"))) {
      return paths.filter(path -> path.getFileName().toString().equals("placing.json")).count();
    }
  }

  private static void assertNoStagedPayload(Path root) throws IOException {
    try (var paths = Files.walk(root.resolve(".sync"))) {
      assertEquals(0, paths.filter(Files::isRegularFile).filter(path -> {
        for (var part : root.resolve(".sync").relativize(path))
          if (part.toString().equals("files")) return true;
        return false;
      }).count(), "The recovered transfer leaves no payload beneath its journal's files container");
    }
  }

  private record Crash(Path directory, Path staged, Path destination, Path manifest, int placementCopies) {}

  private Crash crash(String boundary) throws Exception {
    var root = Files.createTempDirectory(directory, "sync-crash-").toRealPath();
    var ready = root.resolve("ready.json");
    var output = root.resolve("child.log");
    var process = new ProcessBuilder(ChildJvms.JAVA, ChildJvms.HEADLESS,
        "-cp", System.getProperty("java.class.path"), CrashProcess.class.getName(),
        root.toString(), ready.toString(), boundary)
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (!Files.exists(ready) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
      assertTrue(Files.exists(ready), () -> "Child did not reach " + boundary + ": " + readOutput(output));
      var state = JsonParser.parseString(Files.readString(ready)).getAsJsonObject();
      var staged = Path.of(state.get("staged").getAsString());
      var destination = state.has("destination") ? Path.of(state.get("destination").getAsString()) : null;
      var manifest = state.has("manifest") ? Path.of(state.get("manifest").getAsString()) : null;
      assertTrue(staged.startsWith(root));
      if (destination != null) assertTrue(destination.startsWith(root));
      if (manifest != null) assertTrue(manifest.startsWith(root));
      process.destroyForcibly();
      assertTrue(process.waitFor(5, TimeUnit.SECONDS));
      // At its boundary the child held a store's lock and the sync's owner lock, which Windows
      // frees when it can, not when the process exits; the reopen below takes both.
      org.triplehelix.wpilogmcp.fixtures.ProcessLocks.awaitReleasedUnder(root, "store.lock");
      org.triplehelix.wpilogmcp.fixtures.ProcessLocks.awaitReleasedUnder(root, SyncPublication.LOCK_NAME);
      return new Crash(root, staged, destination, manifest, state.get("placement_copies").getAsInt());
    } finally {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
  }

  private static String readOutput(Path path) {
    try { return Files.readString(path); }
    catch (IOException failure) { return failure.toString(); }
  }

  /** The peer is an in-memory adapter; the URL is only a provenance string, never a request. */
  public static final class CrashProcess {
    public static void main(String[] args) throws Exception {
      var directory = Path.of(args[0]);
      var ready = Path.of(args[1]);
      String requested = args[2];
      var copies = new AtomicInteger();
      var mover = requested.startsWith("CROSS_") ? new LogStore.Mover() {
        @Override boolean sameFileStore(Path source, Path destination) { return false; }
        @Override LogStore.CopyAttributes copy(Path source, Path destination, long bytes) throws IOException {
          var attributes = super.copy(source, destination, bytes);
          copies.incrementAndGet();
          return attributes;
        }
      } : null;
      try (var peer = new StoreSyncHardeningFixture(directory, requested.equals("PARTIAL"), mover)) {
        if (requested.equals("PARTIAL")) {
          peer.sync();
          peer.awaitPayload();
          Path journal;
          try (var paths = Files.walk(peer.target.root().resolve(".sync"))) {
            journal = paths.filter(path -> path.getFileName().toString().equals("pull.json")).findFirst().orElseThrow();
          }
          var progress = StoreJson.JSON.fromJson(Files.readString(journal), PullManifest.class);
          var held = progress.files().get(0);
          if (held.bytesCopied() != 65_536) throw new IOException("First block was not checkpointed");
          barrier(ready, peer.target.root().resolve(held.localName()), null, null, copies.get());
        } else {
          String reachedBoundary = requested.startsWith("CROSS_")
              ? requested.substring("CROSS_".length()) : requested;
          peer.sync((boundary, staged, destination, manifest) -> {
            if (boundary.name().equals(reachedBoundary))
              barrier(ready, staged, destination, manifest, copies.get());
          }).get(1, TimeUnit.MINUTES);
        }
      }
    }

    private static void barrier(Path ready, Path staged, Path destination, Path manifest,
        int placementCopies) throws IOException {
      var state = new JsonObject();
      state.addProperty("staged", staged.toString());
      if (destination != null) state.addProperty("destination", destination.toString());
      if (manifest != null) state.addProperty("manifest", manifest.toString());
      state.addProperty("placement_copies", placementCopies);
      var pending = ready.resolveSibling("ready.tmp");
      Files.writeString(pending, state.toString());
      Files.move(pending, ready, StandardCopyOption.ATOMIC_MOVE);
      try { Thread.sleep(TimeUnit.MINUTES.toMillis(1)); }
      catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
    }
  }
}
