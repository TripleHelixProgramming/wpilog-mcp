/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.mcp.CallerContext.Authority;
import org.triplehelix.wpilogmcp.visualization.*;

class TemplateMetadataTest {
  @TempDir Path root;
  @Test void removalWhileAnUpdateWaitsForTheStoreQueueIsUnavailableNotRevisionZero() throws Exception {
    root = root.toRealPath(); var security = new SecurityValidator(); security.addAllowedDirectory(root);
    try (var registry = new StoreRegistry(security)) {
      var store = registry.store(root); var metadata = new TemplateMetadata(store, UUID.randomUUID().toString()); var id = UUID.randomUUID();
      metadata.save(Authority.STDIO_LOCAL, UUID.randomUUID(), id, 0, "Fixture", "", VisualizationContracts.blank()).get();
      var entered = new java.util.concurrent.CountDownLatch(1); var proceed = new java.util.concurrent.CountDownLatch(1);
      var blocker = store.captureAsync(io -> {
        entered.countDown();
        try { assertTrue(proceed.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
        return null;
      });
      java.util.concurrent.CompletableFuture<com.google.gson.JsonObject> update;
      var path = root.resolve("visualizations/templates/" + id + ".json");
      try {
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        update = metadata.save(Authority.STDIO_LOCAL, UUID.randomUUID(), id, 1, "Updated", "", VisualizationContracts.blank());
        Files.delete(path);
      } finally { proceed.countDown(); }
      blocker.get();
      var failure = assertThrows(ExecutionException.class, update::get);
      assertTrue(failure.getCause().getMessage().startsWith("TEMPLATE_UNAVAILABLE: store template is missing"), failure.getCause().toString());
      assertInstanceOf(NoSuchFileException.class, failure.getCause().getCause());
      assertFalse(Files.exists(path));
    }
  }
  @Test void unreadableNeighborCannotHideValidTemplatesOrBeOverwrittenByImport() throws Exception {
    root = root.toRealPath(); var security = new SecurityValidator(); security.addAllowedDirectory(root);
    try (var registry = new StoreRegistry(security)) {
      var store = registry.store(root); var metadata = new TemplateMetadata(store, UUID.randomUUID().toString()); var id = UUID.randomUUID();
      var created = metadata.save(Authority.STDIO_LOCAL, id, id, 0, "Fixture", "", VisualizationContracts.blank()).get();
      var selected = new StoreDoor(() -> Set.of(root)).stores().get(0); var bad = root.resolve("visualizations/templates/" + UUID.randomUUID() + ".json");
      for (String bytes : List.of("not JSON", "{\"schema_version\":2}", "{\"schema_version\":1,\"unknown\":true}")) {
        Files.writeString(bad, bytes); var listing = TemplateMetadata.list(selected);
        assertEquals(1, listing.records().size()); assertEquals(1, listing.unreadable().size()); assertEquals(bytes, Files.readString(bad));
        assertEquals(created.get("template"), TemplateMetadata.read(selected, id));
        var failure = assertThrows(ExecutionException.class, () -> metadata.importDefinition(Authority.STDIO_LOCAL, UUID.randomUUID(), TemplateDefinition.export(BundledTemplates.all().get(0)).toString(), "copy", null).get());
        assertTrue(failure.getCause().getMessage().contains("INCOMPLETE_CATALOG")); assertEquals(bytes, Files.readString(bad));
      }
    }
  }
  @Test void writesHonorAuthorityCasAndReceiptCapacityWithoutEvictingProof() throws Exception {
    root = root.toRealPath(); var security = new SecurityValidator(); security.addAllowedDirectory(root);
    try (var registry = new StoreRegistry(security)) {
      var metadata = new TemplateMetadata(registry.store(root), UUID.randomUUID().toString()); UUID id = UUID.randomUUID(), request = UUID.randomUUID();
      assertThrows(ExecutionException.class, () -> metadata.save(Authority.UNTRUSTED, request, id, 0, "Fixture", "", VisualizationContracts.blank()).get()); assertFalse(Files.exists(root.resolve("store.json")));
      var first = metadata.save(Authority.STDIO_LOCAL, request, id, 0, "Fixture", "", VisualizationContracts.blank()).get();
      for (int i = 1; i < 64; i++) metadata.save(Authority.STDIO_LOCAL, UUID.randomUUID(), id, i, "Revision " + i, "", VisualizationContracts.blank()).get();
      var bytes = Files.readAllBytes(root.resolve("visualizations/templates/" + id + ".json"));
      var full = assertThrows(ExecutionException.class, () -> metadata.save(Authority.STDIO_LOCAL, UUID.randomUUID(), id, 64, "Full", "", VisualizationContracts.blank()).get()); assertTrue(full.getCause().getMessage().contains("WORKSPACE_LIMIT"));
      assertArrayEquals(bytes, Files.readAllBytes(root.resolve("visualizations/templates/" + id + ".json")));
      assertEquals(first, metadata.save(Authority.STDIO_LOCAL, request, id, 0, "Fixture", "", VisualizationContracts.blank()).get());
      var stale = assertThrows(ExecutionException.class, () -> metadata.save(Authority.STDIO_LOCAL, UUID.randomUUID(), id, 1, "Stale", "", VisualizationContracts.blank()).get()); assertTrue(stale.getCause().getMessage().contains("REVISION_CONFLICT"));
    }
  }
  @Test void templateAndReceiptBytesAreAdmittedTogetherBeforeReplacingTheFile() throws Exception {
    root = root.toRealPath(); var security = new SecurityValidator(); security.addAllowedDirectory(root);
    try (var registry = new StoreRegistry(security)) {
      var metadata = new TemplateMetadata(registry.store(root), UUID.randomUUID().toString()); UUID id = UUID.randomUUID(); var config = VisualizationContracts.blank();
      for (int i = 0; i < 200; i++) {
        var requirement = new com.google.gson.JsonObject(); requirement.addProperty("id", "r" + i); requirement.addProperty("selector_kind", "entry"); requirement.addProperty("entry", "/" + "x".repeat(900) + i);
        requirement.add("field_path", com.google.gson.JsonNull.INSTANCE); requirement.add("unit", com.google.gson.JsonNull.INSTANCE); requirement.addProperty("value_type", "number"); requirement.add("accepted_entry_types", com.google.gson.JsonParser.parseString("[\"double\"]")); config.getAsJsonArray("requirements").add(requirement);
      }
      byte[] previous = null; boolean refused = false;
      for (int i = 0; i < 20; i++) {
        try { metadata.save(Authority.STDIO_LOCAL, UUID.randomUUID(), id, i, "Large", "", config).get(); }
        catch (ExecutionException e) { assertTrue(e.getCause().getMessage().contains("WORKSPACE_LIMIT")); refused = true; break; }
        previous = Files.readAllBytes(root.resolve("visualizations/templates/" + id + ".json")); assertTrue(previous.length <= 1048576);
      }
      assertTrue(refused, "metadata plus full retry results must not grow beyond 1 MiB"); assertNotNull(previous);
      assertArrayEquals(previous, Files.readAllBytes(root.resolve("visualizations/templates/" + id + ".json")));
    }
  }

}
