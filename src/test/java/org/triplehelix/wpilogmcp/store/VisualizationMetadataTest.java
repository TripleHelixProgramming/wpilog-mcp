/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.mcp.CallerContext.Authority;
import org.triplehelix.wpilogmcp.visualization.VisualizationContracts;

class VisualizationMetadataTest {
  @TempDir Path root;
  @Test void savedMetadataListingHasAnAggregateByteBound() throws Exception {
    root = root.toRealPath(); var security = new SecurityValidator(); security.addAllowedDirectory(root);
    try (var registry = new StoreRegistry(security)) {
      var source = new JsonObject(); source.addProperty("kind", "unmanaged"); source.addProperty("source_id", UUID.randomUUID().toString());
      var metadata = new VisualizationMetadata(registry.store(root));
      var sample = metadata.save(Authority.STDIO_LOCAL, UUID.randomUUID(), 0, UUID.randomUUID(), "Fixture", source, VisualizationContracts.blank()).get();
      // Each input is under the individual record cap; the listing must also bound their sum.
      // JSON whitespace counts toward read admission even though it is absent after parsing.
      String padding = " ".repeat(1_000_000);
      for (int i = 0; i < 10; i++) {
        var id = UUID.randomUUID(); sample.addProperty("saved_view_id", id.toString());
        Files.writeString(root.resolve("visualizations/saved-views/" + id + ".json"), sample.toString() + padding);
      }
      var selected = new StoreDoor(() -> java.util.Set.of(root)).stores().get(0);
      var listing = assertDoesNotThrow(() -> VisualizationMetadata.list(selected));
      assertTrue(listing.incomplete());
      assertTrue(listing.views().size() >= 8 && listing.views().size() <= 9);
      assertTrue(listing.views().stream().mapToLong(v -> v.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum() <= 8L * 1024 * 1024);
      assertTrue(listing.unreadable().stream().anyMatch(p -> p.reason().contains("WORKSPACE_LIMIT")));
      assertEquals(sample, VisualizationMetadata.read(selected, UUID.fromString(sample.get("saved_view_id").getAsString())));
    }
  }
  @Test void savedViewsUseTheStoreLockAndCasAndReplayALostReply() throws Exception {
    root = root.toRealPath(); var security = new SecurityValidator(); security.addAllowedDirectory(root);
    try (var registry = new StoreRegistry(security)) {
      var metadata = new VisualizationMetadata(registry.store(root)); var id = UUID.randomUUID(); var request = UUID.randomUUID();
      var source = new JsonObject(); source.addProperty("kind", "unmanaged"); source.addProperty("source_id", UUID.randomUUID().toString());
      var config = VisualizationContracts.blank();
      assertThrows(ExecutionException.class, () -> metadata.save(Authority.UNTRUSTED, id, 0, request, "Fixture", source, config).get());
      assertFalse(Files.exists(root.resolve("store.json")));
      try (var lock = StoreLock.acquire(root, security)) {
        var failure = assertThrows(ExecutionException.class, () -> metadata.save(Authority.STDIO_LOCAL, id, 0, request, "Fixture", source, config).get());
        assertTrue(failure.getCause().getMessage().contains("lock"));
      }
      var result = metadata.save(Authority.STDIO_LOCAL, id, 0, request, "Fixture", source, config).get();
      assertEquals(1, result.get("revision").getAsLong());
      assertEquals(result, metadata.save(Authority.STDIO_LOCAL, id, 0, request, "Fixture", source, config).get());
      var conflict = assertThrows(ExecutionException.class, () -> metadata.save(Authority.STDIO_LOCAL, id, 0, UUID.randomUUID(), "Replacement", source, config).get());
      assertTrue(conflict.getCause().getMessage().contains("REVISION_CONFLICT"));
      assertTrue(StoreCatalog.read(root, security).unmanaged().isEmpty());
      assertEquals("Fixture", new StoreFiles(root, security).read(root.resolve("visualizations/saved-views/" + id + ".json"), JsonObject.class).get("title").getAsString());
    }
  }
}
