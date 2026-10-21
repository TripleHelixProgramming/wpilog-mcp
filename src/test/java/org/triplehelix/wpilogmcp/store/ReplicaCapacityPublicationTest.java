/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.store.StoreManifest.Header;

/** Late notification and invalid startup metadata must not replace committed capacity facts. */
class ReplicaCapacityPublicationTest {
  @TempDir Path temp;

  @Test void delayedReplicaNotificationCannotReplaceNewerOwnerPublication() throws Exception {
    var security = security();
    try (var registry = new StoreRegistry(security, LogManager.getInstance())) {
      var store = registry.store(temp.resolve("store"));
      String id = UUID.randomUUID().toString(); String epoch = UUID.randomUUID().toString();
      var earlier = header(id, epoch, 1, 0); var later = header(id, epoch, 2, 3277);
      store.captureAsync(io -> { io.writeDurable(store.root().resolve("store.json"), earlier); return null; })
          .get(10, TimeUnit.SECONDS);
      store.captureAsync(io -> { io.writeDurable(store.root().resolve("store.json"), later); return null; })
          .get(10, TimeUnit.SECONDS);
      // ReplicaTransfers releases busy before this off-owner callback. Another operation can
      // commit the later header first; this is the actual delayed-notification entry point.
      store.replicaPublished(earlier);
      var observed = store.catalogCapacity();
      assertEquals(id, observed.storeId(), "The published header supplies the serving store identity");
      assertEquals(3277, observed.catalogCapacity().exclusions(),
          "A delayed replica notification must not replace the latest committed catalog counts");
      assertTrue(observed.catalogCapacity().nearLimit(), "The warning must survive the old callback");
      assertEquals(2, store.replicaHeader().replicas().revision(), "Disk still holds the newer catalog");
    }
  }

  @Test void replicaTurnPublishesCapacityWithoutEnablingOtherObservers() throws Exception {
    var security = security();
    try (var registry = new StoreRegistry(security, LogManager.getInstance())) {
      var store = registry.store(temp.resolve("store"));
      String id = UUID.randomUUID().toString(); String epoch = UUID.randomUUID().toString();
      var earlier = header(id, epoch, 1, 0); var later = header(id, epoch, 2, 3277);
      store.captureAsync(io -> { io.writeDurable(store.root().resolve("store.json"), earlier); return null; })
          .get(10, TimeUnit.SECONDS);
      var callbacks = new AtomicInteger();
      try (var ignored = store.observe(new LogStore.Observer() {
        @Override public void manifest(Path path, Object value) { callbacks.incrementAndGet(); }
        @Override public void inventory(StoreCatalog.Snapshot snapshot) { callbacks.incrementAndGet(); }
      })) {
        store.replicaTurn(io -> { io.writeDurable(store.root().resolve("store.json"), later); return null; })
            .get(10, TimeUnit.SECONDS);
      }
      assertEquals(0, callbacks.get(), "Capacity publication must not change replica notification ownership");
      assertEquals(3277, store.catalogCapacity().catalogCapacity().exclusions(),
          "A committed replica turn must publish capacity before its delayed observer callback");
    }
  }

  @Test void missingStoreIdentityIsOmittedFromInitialCapacityFacts() throws Exception {
    var security = security();
    var root = Files.createDirectory(temp.resolve("invalid"));
    // Header permits an empty format-2 catalog with a missing id; catalog admission normally
    // validates that identity separately. Startup metrics must not publish it as a label.
    Files.writeString(root.resolve("store.json"), StoreJson.JSON.toJson(
        header(null, UUID.randomUUID().toString(), 1, 0)));
    try (var registry = new StoreRegistry(security, LogManager.getInstance())) {
      var store = registry.store(root);
      store.awaitImports(); // The staging barrier includes the initial off-owner header read.
      assertTrue(registry.catalogCapacities().isEmpty(),
          "An invalid store identity must be omitted, never published as a null metrics label");
    }
  }

  private SecurityValidator security() throws Exception {
    temp = temp.toRealPath();
    var security = new SecurityValidator(); security.addAllowedDirectory(temp); return security;
  }

  private static Header header(String id, String epoch, long revision, int count) {
    var exclusions = new ArrayList<ReplicaCatalog.Exclusion>();
    for (int i = 0; i < count; i++) exclusions.add(new ReplicaCatalog.Exclusion(
        UUID.randomUUID().toString(), null, "0".repeat(64)));
    return new Header(1, "2026-01-01T00:00:00Z", id, List.of())
        .withReplicas(new ReplicaCatalog(1, epoch, revision, List.of(), List.of(), exclusions));
  }
}
