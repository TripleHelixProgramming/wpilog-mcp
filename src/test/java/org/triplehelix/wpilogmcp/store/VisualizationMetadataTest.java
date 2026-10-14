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
