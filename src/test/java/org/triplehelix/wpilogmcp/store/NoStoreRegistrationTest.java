/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/**
 * Capture start, imports, sync, mirrors and listings all register folders, many of which hold
 * no store. Registration's publication step once took the store lock in every one of them,
 * leaving an empty store.lock behind and racing a caller that meant to put its own file there.
 * A folder that does hold a store keeps the locked step, including its symbolic-link refusal.
 */
class NoStoreRegistrationTest {
  @TempDir Path temp;

  @Test void registeringAFolderWithoutAStoreLeavesItEmpty() throws Exception {
    temp = temp.toRealPath();
    var security = new SecurityValidator();
    security.addAllowedDirectory(temp);
    var root = Files.createDirectory(temp.resolve("empty"));
    try (var registry = new StoreRegistry(security, LogManager.getInstance())) {
      var store = registry.store(root);
      store.publicationsSettled().get(10, TimeUnit.SECONDS);
      try (var entries = Files.list(root)) {
        assertEquals(List.of(), entries.map(path -> path.getFileName().toString()).toList(),
            "Registration must leave a folder without a store as it found it");
      }
      var status = store.publicationStatus();
      assertNull(status.storeId(), "Publication status must report that the folder holds no store");
      assertEquals(ReplicaPublications.State.READY, status.state());
      assertEquals(0, status.knownPendingCount());
      assertEquals(0, status.totalPendingCount());
    }
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void aRegisteredStoreStillRefusesASymlinkedLock(boolean targetExists) throws Exception {
    temp = temp.toRealPath();
    requireSymbolicLinks();
    var security = new SecurityValidator();
    security.addAllowedDirectory(temp);
    var root = temp.resolve("store");
    var outside = temp.resolve("outside-lock");
    try (var registry = new StoreRegistry(security, LogManager.getInstance())) {
      var store = registry.store(root);
      store.importPaths(new LogStore.Request(List.of(), false, null), progress -> {}).get(10, TimeUnit.SECONDS);
      store.refreshStatus().get(10, TimeUnit.SECONDS);
      assertTrue(StoreCatalog.isStore(root));
      assertNotNull(store.publicationStatus().storeId());
      // The test owns this store; no other writer can hold the lock it replaces.
      Files.delete(root.resolve("store.lock"));
      if (targetExists) Files.writeString(outside, "untouched sentinel");
      var link = Files.createSymbolicLink(root.resolve("store.lock"), outside);
      var refused = assertThrows(ExecutionException.class, () -> store.refreshStatus().get(10, TimeUnit.SECONDS));
      assertTrue(refused.getCause().getMessage().contains("symbolic link"), refused.getCause().toString());
      assertEquals(ReplicaPublications.State.UNAVAILABLE, store.publicationStatus().state());
      assertEquals("SOURCE_UNAVAILABLE", store.publicationStatus().failureCode());
      assertTrue(Files.isSymbolicLink(link));
      assertEquals(targetExists, Files.exists(outside), "A dangling lock must not create its target");
      if (targetExists) assertEquals("untouched sentinel", Files.readString(outside));
    }
  }

  /** Probe where nothing can already exist, so only missing link support skips the test. */
  private void requireSymbolicLinks() throws IOException {
    var probe = Files.createTempDirectory(temp, "link-probe");
    try {
      Files.createSymbolicLink(probe.resolve("link"), probe.resolve("target"));
    } catch (UnsupportedOperationException | IOException e) {
      Assumptions.abort("Symbolic links unavailable: " + e.getMessage());
    }
  }
}
