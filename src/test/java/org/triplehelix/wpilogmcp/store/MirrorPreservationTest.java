/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.config.MirrorConfig;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

class MirrorPreservationTest {
  @TempDir Path temp;
  final LogManager manager = LogManager.getInstance();
  Set<Path> previous;
  LogStore mirror;
  @BeforeEach void setup() throws Exception {
    temp = temp.toRealPath(); previous = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    mirror = manager.stores().store(temp.resolve("mirror"));
  }
  @AfterEach void cleanup() {
    manager.unloadAllLogs(); manager.clearAllowedDirectories(); previous.forEach(manager::addAllowedDirectory);
  }

  @Test void legacySynchronizationIsPausedBeforeAnyRemoteOrPayloadWork() throws Exception {
    var input = ImportFixture.write(temp.resolve("input.wpilog"), 1);
    var held = mirror.importPaths(new LogStore.Request(List.of(input), false, "RIO"), p -> {}).get().files().get(0).path();
    var io = new StoreFiles(mirror.root(), manager.testGetSecurityValidator());
    var header = io.read(mirror.root().resolve("store.json"), Header.class);
    io.write(mirror.root().resolve("store.json"), header.withOrigin(new MirrorOrigin("master", "http://127.0.0.1:9", null, List.of("held"), Map.of())));
    byte[] payload = Files.readAllBytes(held), manifest = Files.readAllBytes(mirror.root().resolve("store.json"));
    var calls = new AtomicInteger();
    // Clock advance, a narrowed scope and a cap smaller than the held file must all preserve it.
    for (int days : List.of(0, 14)) {
      var config = new MirrorConfig("http://127.0.0.1:9", mirror.root(), days, 1, List.of("OTHER"), List.of(), 30, 0);
      var result = mirror.mirror(config, p -> {}, Clock.systemUTC(), address -> {
        calls.incrementAndGet(); throw new java.io.IOException("fixture master is offline");
      }).get();
      assertEquals("migration_required", result.state());
      assertEquals(0, result.bytesCopied()); assertTrue(result.evicted().isEmpty());
      assertArrayEquals(payload, Files.readAllBytes(held));
      assertArrayEquals(manifest, Files.readAllBytes(mirror.root().resolve("store.json")));
    }
    assertEquals(0, calls.get(), "Legacy sync must not contact the master before explicit migration");
  }

  @Test void explicitAllScopeAndMetadataHeadroomAreConfigurationContracts() throws Exception {
    var json = com.google.gson.JsonParser.parseString("{\"origin\":\"http://127.0.0.1:9\",\"folder\":\"mirror\",\"scope\":\"all\",\"metadata_reserve_bytes\":1048576}");
    assertDoesNotThrow(() -> MirrorConfig.parse(json, s -> s));
  }

  @Test void mirrorCompletionRunsAfterTheStoreLockIsReleased() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var blocker = mirror.captureAsync(io -> {
      entered.countDown();
      try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("fixture timed out"); }
      catch (InterruptedException e) { throw new java.io.IOException(e); }
      return null;
    });
    assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
    try {
      var future = mirror.mirror(new MirrorConfig("http://127.0.0.1:9", mirror.root(), 14, 1_000_000, List.of(), List.of(), 30, 0), p -> {});
      var continuation = future.thenApply(value -> {
        assertDoesNotThrow(() -> { try (var lock = StoreLock.acquire(mirror.root(), manager.testGetSecurityValidator())) { } },
            "A future continuation must never inherit the store lock");
        return value;
      });
      release.countDown(); blocker.get(); continuation.get();
    } finally { release.countDown(); }
  }
}
