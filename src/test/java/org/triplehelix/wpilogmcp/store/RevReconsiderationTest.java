/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/** Folder import must not make a proven pair depend on which completed file arrived first. */
class RevReconsiderationTest {
  @TempDir Path temp;
  private final LogManager logs = LogManager.getInstance();
  private Set<Path> previousDirectories;

  @BeforeEach void allowFixtureMoves() throws Exception {
    temp = temp.toRealPath();
    previousDirectories = logs.getConfiguredDirectories();
    // LogStore's move reservations also use the shared manager's path validator.
    logs.addAllowedDirectory(temp);
  }

  @AfterEach void restoreDirectories() {
    logs.clearAllowedDirectories();
    previousDirectories.forEach(logs::addAllowedDirectory);
  }

  private record Pair(Path wpilog, Path revlog) {}

  private Pair pair(String directory) throws Exception {
    var input = Files.createDirectories(temp.resolve(directory));
    var wpilog = ImportFixture.sameClockRevPair(input, directory + ".wpilog");
    try (var paths = Files.list(input)) {
      return new Pair(wpilog, paths.filter(p -> p.toString().endsWith(".revlog")).findFirst().orElseThrow());
    }
  }

  private SecurityValidator security() throws Exception {
    temp = temp.toRealPath();
    var validator = new SecurityValidator();
    validator.addAllowedDirectory(temp);
    return validator;
  }

  private LogStore.Outcome imported(LogStore store, Path path, String robot) throws Exception {
    return store.importPaths(new LogStore.Request(List.of(path), false, robot), p -> {})
        .get(30, TimeUnit.SECONDS).files().get(0);
  }

  private LogStore.Reconsideration reconsider(LogStore store) throws Exception {
    return store.reconsiderUnassignedRev().get(30, TimeUnit.SECONDS);
  }

  @Test void revArrivingFirstIsPromotedWithItsOriginalProvenanceAndMovedPath() throws Exception {
    var security = security();
    var pair = pair("first");
    var root = Files.createDirectory(temp.resolve("store"));
    try (var registry = new StoreRegistry(security)) {
      var store = registry.store(root);
      var early = imported(store, pair.revlog(), null);
      assertEquals("unassigned", early.status());
      var original = StoreCatalog.read(root, security).files().get(0).file().provenance();
      assertEquals(0, reconsider(store).considered(), "No anchor is not permission to guess");
      var anchor = imported(store, pair.wpilog(), "practice");
      var result = reconsider(store);
      assertEquals(1, result.imported(), "The later WPILOG must reconsider the earlier unassigned REV");
      var rev = StoreCatalog.read(root, security).files().stream()
          .filter(f -> f.file().kind().equals("revlog")).findFirst().orElseThrow();
      assertEquals(anchor.path().getParent(), rev.path().getParent());
      assertEquals(original, rev.file().provenance(), "Promotion retains the first import's provenance");
      assertEquals("by_correlation", rev.file().matching().method());
      assertEquals(0, rev.file().matching().offsetMicros(), 20_000);
      assertArrayEquals(Files.readAllBytes(pair.revlog()), Files.readAllBytes(rev.path()));
      assertFalse(Files.exists(early.path()));
      assertTrue(StoreCatalog.read(root, security).header().moves().stream()
          .anyMatch(move -> move.originalPath().equals(early.path().toString())));
      assertEquals(0, reconsider(store).considered());
    }
  }

  @Test void ambiguityIncludesEveryEligibleAnchorAndIsNotRetriedAfterRestart() throws Exception {
    var security = security();
    var pair = pair("ambiguous");
    var other = temp.resolve("other.wpilog");
    byte[] bytes = Files.readAllBytes(pair.wpilog());
    int extraLength = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(8);
    Files.write(other, ByteBuffer.allocate(bytes.length + 1).order(ByteOrder.LITTLE_ENDIAN)
        .put(bytes, 0, 8).putInt(extraLength + 1).put((byte) 'X').put(bytes, 12, bytes.length - 12).array());
    var root = Files.createDirectory(temp.resolve("store"));
    try (var registry = new StoreRegistry(security)) {
      var store = registry.store(root);
      imported(store, pair.revlog(), null);
      imported(store, pair.wpilog(), "practice");
      imported(store, other, "competition");
      var result = reconsider(store);
      assertEquals(1, result.considered());
      assertEquals(0, result.imported(), "Two equally proven anchors must remain ambiguous");
      assertEquals(0, result.remaining());
      assertNull(StoreCatalog.read(root, security).files().stream()
          .filter(f -> f.file().kind().equals("revlog")).findFirst().orElseThrow().session());
      assertTrue(Files.exists(root.resolve(".rev-reconsideration/cursor.json")));
      assertTrue(StoreCatalog.read(root, security).unmanaged().isEmpty(),
          "Reconsideration receipts are store metadata, not unmanaged logs offered for import");
      assertEquals(0, reconsider(store).considered(), "Idle scans must not repeat correlation");
    }
    try (var registry = new StoreRegistry(security)) {
      assertEquals(0, reconsider(registry.store(root)).considered(), "Restart must retain the attempted generation");
    }
  }

  @Test void wrongBootStaysUnassignedAndANewlyAssignedWpilogChangesTheGeneration() throws Exception {
    var security = security();
    var pair = pair("clock");
    try (var writer = new WpilogWriter(pair.revlog(), "synthetic other boot")) {
      int output = writer.start("CAN/3/Periodic Status 0", "raw", "", 0);
      for (int n = 0; n <= 5000; n++) {
        double time = n * 0.01 + FixtureLogs.REVLOG_PAIR_OFFSET_SEC;
        writer.append(output, Math.round(time * 1e6),
            FixtureLogs.sparkStatus0(FixtureLogs.revlogPairOutput(time + 2), 12, 20, 30, false));
      }
    }
    var root = Files.createDirectory(temp.resolve("store"));
    try (var registry = new StoreRegistry(security)) {
      var store = registry.store(root);
      imported(store, pair.revlog(), null);
      imported(store, pair.wpilog(), "practice");
      var result = reconsider(store);
      assertEquals(1, result.considered());
      assertEquals(0, result.imported(), "Strong signal correlation outside the same-boot gate is insufficient");
      assertEquals(0, reconsider(store).considered());
      var unknown = imported(store, ImportFixture.write(temp.resolve("unknown.wpilog"), 73), null);
      assertEquals("unassigned", unknown.status());
      assertEquals(0, reconsider(store).considered(), "An unassigned WPILOG is not an eligible anchor");
      store.assignPaths(List.of(unknown.path()), "practice", p -> {}).get(30, TimeUnit.SECONDS);
      assertEquals(1, reconsider(store).considered(), "Assignment changes the eligible anchor generation");
    }
  }

  @Test void aPassIsBoundedAndANewRevIsConsideredEvenAfterTheGenerationWasCompleted() throws Exception {
    var security = security();
    var sources = new ArrayList<Path>();
    for (int i = 0; i < 34; i++) {
      var file = temp.resolve("short-" + i + ".revlog");
      try (var writer = new WpilogWriter(file, "synthetic bounded pass " + i)) {
        int output = writer.start("CAN/3/Periodic Status 0", "raw", "", 0);
        writer.append(output, 1_000_000, FixtureLogs.sparkStatus0(0.2, 12, 20, 30, false));
        writer.append(output, 2_000_000, FixtureLogs.sparkStatus0(0.3, 12, 20, 30, false));
      }
      sources.add(file);
    }
    var root = Files.createDirectory(temp.resolve("store"));
    try (var registry = new StoreRegistry(security)) {
      var store = registry.store(root);
      store.importPaths(new LogStore.Request(sources.subList(0, 33), false, null), p -> {})
          .get(30, TimeUnit.SECONDS);
      imported(store, ImportFixture.write(temp.resolve("short.wpilog"), 17), "practice");
      var first = reconsider(store);
      assertEquals(32, first.considered(), "One queue turn must not correlate an entire season");
      assertEquals(1, first.remaining());
      assertEquals(0, first.imported(), "Two samples cannot prove a REV association");
      var second = reconsider(store);
      assertEquals(1, second.considered());
      assertEquals(0, second.remaining());
      assertEquals(0, reconsider(store).considered());
      imported(store, sources.get(33), null);
      assertEquals(1, reconsider(store).considered(), "A newly imported REV has no receipt for this generation");
      assertEquals(0, reconsider(store).considered());
    }
  }

  @Test void simultaneousRequestsShareTheOutstandingStagingTurn() throws Exception {
    var security = security();
    var root = Files.createDirectory(temp.resolve("store"));
    var source = pair("held").wpilog();
    try (var registry = new StoreRegistry(security)) {
      var store = registry.store(root);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      // Reconsideration waits behind import preparation on the staging thread, not behind the store
      // queue. An import's first progress report runs on that thread, so holding it holds the turn.
      var held = store.importPaths(new LogStore.Request(List.of(source), false, null), progress -> {
        if (!progress.phase().equals("starting")) return;
        entered.countDown();
        try {
          if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Test staging barrier expired");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      try {
        assertTrue(entered.await(30, TimeUnit.SECONDS));
        var first = store.reconsiderUnassignedRev();
        var second = store.reconsiderUnassignedRev();
        assertSame(first, second, "Only one reconsideration may wait behind other staged work");
        assertFalse(first.isDone(), "The held staging thread has not run it");
        release.countDown();
        assertEquals(0, first.get(30, TimeUnit.SECONDS).considered());
      } finally {
        release.countDown();
        held.get(30, TimeUnit.SECONDS);
      }
    }
  }

  @Test void failedPlacementKeepsTheUnassignedSourceAndCanBeReconsideredAgain() throws Exception {
    var security = security();
    var pair = pair("recovery");
    var root = Files.createDirectory(temp.resolve("store"));
    try (var registry = new StoreRegistry(security)) {
      var store = registry.store(root);
      var early = imported(store, pair.revlog(), null);
      var anchor = imported(store, pair.wpilog(), "practice");
      var name = pair.revlog().getFileName();
      var direct = Files.createDirectory(anchor.path().getParent().resolve(name));
      var fallback = Files.createDirectories(anchor.path().getParent()
          .resolve(StoreFiles.hash(pair.revlog())).resolve(name));
      // A nonempty directory cannot be mistaken for the payload or replaced on any platform.
      var marker = Files.writeString(fallback.resolve("keep.txt"), "fixture obstacle");
      var failure = assertThrows(java.util.concurrent.ExecutionException.class,
          () -> store.reconsiderUnassignedRev().get(30, TimeUnit.SECONDS));
      assertTrue(failure.getCause().getMessage().contains("REV reconsideration could not place"), failure.toString());
      assertArrayEquals(Files.readAllBytes(pair.revlog()), Files.readAllBytes(early.path()));
      assertNull(StoreCatalog.read(root, security).files().stream()
          .filter(f -> f.file().kind().equals("revlog")).findFirst().orElseThrow().session());
      Files.delete(marker); Files.delete(fallback); Files.delete(direct);
      assertEquals(1, reconsider(store).imported(), "A transfer failure is not a completed matching decision");
    }
  }
}
