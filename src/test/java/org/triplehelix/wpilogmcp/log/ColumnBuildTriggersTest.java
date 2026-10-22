/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureWriter;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.store.DiskSpaceBudgets;
import org.triplehelix.wpilogmcp.store.LogStore;
import org.triplehelix.wpilogmcp.store.StoreManifest;

/**
 * What asks for a column file once the server starts building them: a log read from its file,
 * and a log a store records in a session, as an import does; and what stops them.
 */
class ColumnBuildTriggersTest {
  @TempDir Path directory;
  private Path source;
  private LogManager manager;

  @BeforeEach
  void setup() throws Exception {
    directory = directory.toRealPath();
    source = directory.resolve("read.wpilog");
    try (var w = new FixtureWriter(source, "")) {
      for (int i = 0; i < 50; i++) w.dbl("/v", 1 + i * 0.02, i * 0.5);
    }
    manager = IsolatedLogManagers.create(directory, "column-triggers");
  }

  @AfterEach
  void stop() {
    manager.shutdown();
  }

  private static String sha256(Path file) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
  }

  private Path columnFile(Path log) throws Exception {
    return manager.getDiskCache().columnFilePath(sha256(log)).orElseThrow();
  }

  private static void awaitFile(Path file) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!Files.exists(file)) {
      assertTrue(System.nanoTime() < deadline, "no column file appeared");
      Thread.sleep(5);
    }
  }

  @Test
  void aManagerThatHasNotStartedBuildsBuildsNothing() throws Exception {
    try (var use = manager.acquire(source.toString())) {
      assertTrue(((LazyParsedLog) use.log()).contentSha256().isPresent());
    }
    Thread.sleep(200);
    assertFalse(Files.exists(columnFile(source)));
  }

  /** The server's builds, with a heap gate that the test JVM's garbage cannot hold shut. */
  private void startBuilds() {
    manager.startColumnBuilds(() -> Long.MAX_VALUE, 5);
  }

  @Test
  void readingALogFromItsFileBuildsItsColumnFile() throws Exception {
    startBuilds();
    try (var use = manager.acquire(source.toString())) {
      assertEquals(50, use.log().values().get("/v").size());
    }
    awaitFile(columnFile(source));
  }

  @Test
  void anImportBuildsTheColumnFileOfTheLogItPlaces() throws Exception {
    startBuilds();
    var original = ImportFixture.write(directory.resolve("incoming").resolve("original.wpilog"), 51);
    var store = manager.stores().store(directory.resolve("store"));
    var result = store.importPaths(new LogStore.Request(List.of(original), false, "practice"), progress -> {}).get();
    var placed = result.files().get(0).path();
    assertTrue(placed.startsWith(store.root()), "the import placed the log in the store");
    awaitFile(columnFile(placed));
  }

  /**
   * A store publishes after its turn, outside any observer's guard, so a file path no file system
   * accepts must neither fail the publication nor keep the session's other logs from being built.
   */
  @Test
  void aSessionFileWhosePathCannotBeResolvedFailsNothing() throws Exception {
    startBuilds();
    var session = Files.createDirectories(directory.resolve("session"));
    var good = Files.copy(source, session.resolve("good.wpilog"));
    var provenance = new StoreManifest.Provenance("imported", null, "good.wpilog", "2026-10-21T00:00:00Z", false);
    var files = List.of(
        new StoreManifest.LogFile("bad\u0000name.wpilog", "0".repeat(64), 1, "wpilog", provenance, true, 1, 2,
            null, null, null, false, null),
        new StoreManifest.LogFile("good.wpilog", sha256(good), Files.size(good), "wpilog", provenance, true, 1, 2,
            null, null, null, false, null));
    manager.catalogPublished(session.resolve("session.json"), new StoreManifest.Session("s", "2026-10-21T00:00:00Z",
        "2026-10-21T00:01:00Z", "wall_clock", null, null, null, null, files, null, null, null, List.of()));
    awaitFile(columnFile(good));
  }

  @Test
  void afterShutdownBuildsStayStopped() throws Exception {
    manager.shutdown();
    manager.startColumnBuilds();
    startBuilds();
    try (var use = manager.acquire(source.toString())) {
      assertTrue(((LazyParsedLog) use.log()).contentSha256().isPresent());
    }
    Thread.sleep(200);
    assertFalse(Files.exists(columnFile(source)), "a read after shutdown starts no build");
  }

  private static DiskSpaceBudgets.Snapshot filesystem(Long free, long reserved, long captureReserve) {
    return new DiskSpaceBudgets.Snapshot("fs", free == null ? DiskSpaceBudgets.State.UNKNOWN
        : DiskSpaceBudgets.State.AVAILABLE, free, reserved, captureReserve, 0);
  }

  /** A build waits exactly when a store would refuse a copy of one byte. */
  @Test
  void recordingsReserveIsShortWhenAStoreWouldRefuseACopy() {
    long reserve = 2L << 30;
    assertFalse(LogManager.reserveShort(List.of()), "no store, nothing to keep in reserve");
    assertFalse(LogManager.reserveShort(List.of(filesystem(reserve + 1, 0, reserve))));
    assertTrue(LogManager.reserveShort(List.of(filesystem(reserve, 0, reserve))), "down to the reserve");
    assertTrue(LogManager.reserveShort(List.of(filesystem(reserve + 100, 100, reserve))),
        "space reserved for copies in progress counts as used");
    assertFalse(LogManager.reserveShort(List.of(filesystem(reserve + 101, 100, reserve))));
    assertTrue(LogManager.reserveShort(List.of(filesystem(null, 0, reserve))), "unknown space");
    assertTrue(LogManager.reserveShort(List.of(new DiskSpaceBudgets.Snapshot("fs", DiskSpaceBudgets.State.UNKNOWN,
        reserve * 4, 0, reserve, 0))), "a stale figure whose measurement failed");
    assertTrue(LogManager.reserveShort(List.of(filesystem(reserve * 4, 0, reserve), filesystem(0L, 0, reserve))),
        "any store's filesystem");
  }
}
