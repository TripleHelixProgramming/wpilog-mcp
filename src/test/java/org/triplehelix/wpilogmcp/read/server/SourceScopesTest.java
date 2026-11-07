/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.server;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.store.StoreJson;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.tools.LogRequiringTool;
import org.triplehelix.wpilogmcp.tools.WpilogTools;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.store.StoreManifest.Header;

class SourceScopesTest {
  @TempDir Path temp;
  final AtomicReference<Set<Path>> roots = new AtomicReference<>(Set.of());
  final SourceScopes scopes = new SourceScopes(roots::get);

  @Test void unavailableChoicesCountAndDirectoryIdsSurviveReorderAndNewOwner() throws Exception {
    var first = Files.createDirectory(temp.resolve("first")).toRealPath();
    var second = temp.toRealPath().resolve("offline"); roots.set(Set.of(first, second));
    var initial = scopes.snapshot(); assertEquals(2, initial.size());
    var error = assertThrows(SourceScopes.Failure.class, () -> scopes.select(null));
    assertEquals("SOURCE_SELECTION_REQUIRED", error.result().get("code").getAsString());
    String id = initial.stream().filter(s -> s.root().equals(first)).findFirst().orElseThrow().id();
    assertEquals(first, scopes.select(id).root());
    assertThrows(SourceScopes.Failure.class, () -> scopes.select(initial.stream().filter(s -> !s.available()).findFirst().orElseThrow().id()));
    roots.set(new LinkedHashSet<>(List.of(second, first)));
    assertEquals(initial, new SourceScopes(roots::get).snapshot());
    roots.set(Set.of(second)); assertThrows(SourceScopes.Failure.class, () -> scopes.select(id));
  }

  @Test void aSelectedScopeCannotReadItsNeighborOrANestedStore() throws Exception {
    var directory = Files.createDirectory(temp.resolve("tree")).toRealPath();
    var outside = Files.createDirectory(temp.resolve("outside")).toRealPath();
    var store = Files.createDirectory(directory.resolve("managed"));
    String id = UUID.randomUUID().toString();
    Files.writeString(store.resolve("store.json"), StoreJson.JSON.toJson(new Header(1, "2026-01-01T00:00:00Z", id, List.of())));
    roots.set(Set.of(directory, outside));
    String ordinary = scopes.snapshot().stream().filter(s -> s.root().equals(directory)).findFirst().orElseThrow().id();
    assertEquals(id, scopes.forPath(store.resolve("future.wpilog"), null).id());
    assertThrows(SourceScopes.Failure.class, () -> scopes.forPath(store.resolve("future.wpilog"), ordinary));
    assertThrows(SourceScopes.Failure.class, () -> scopes.forPath(outside.resolve("future.wpilog"), ordinary));
    assertThrows(SourceScopes.Failure.class, () -> scopes.forPath(Path.of("future.wpilog"), null));
  }

  @Test void duplicateStoreIdsAreRefusedAndMissingStoreDoesNotBecomeDirectory() throws Exception {
    String id = UUID.randomUUID().toString(); var first = Files.createDirectory(temp.resolve("a")).toRealPath();
    var second = Files.createDirectory(temp.resolve("b")).toRealPath();
    String header = StoreJson.JSON.toJson(new Header(1, "2026-01-01T00:00:00Z", id, List.of()));
    Files.writeString(first.resolve("store.json"), header); Files.writeString(second.resolve("store.json"), header);
    roots.set(Set.of(first, second)); assertEquals(2, scopes.snapshot().size());
    assertThrows(SourceScopes.Failure.class, () -> scopes.select(id));
    roots.set(Set.of(first)); assertEquals("store", scopes.select(id).kind());
    Files.delete(first.resolve("store.json"));
    assertThrows(SourceScopes.Failure.class, () -> scopes.select(id));
    assertFalse(scopes.snapshot().get(0).available());
  }

  @Test void pagingReportsTrueTotalsAndHasNoDuplicateScopes() throws Exception {
    var configured = new HashSet<Path>();
    for (int i = 0; i < 75; i++) configured.add(Files.createDirectory(temp.resolve("source-" + i)).toRealPath());
    roots.set(configured); var ids = new HashSet<String>(); int offset = 0;
    do {
      var page = scopes.describe(offset, 17); assertEquals(75, page.get("total").getAsInt());
      for (var row : page.getAsJsonArray("source_scopes")) assertTrue(ids.add(row.getAsJsonObject().get("scope_id").getAsString()));
      if (!page.get("has_more").getAsBoolean()) break;
      int next = page.get("next_offset").getAsInt(); assertTrue(next > offset); offset = next;
    } while (true);
    assertEquals(75, ids.size());
    assertThrows(IllegalArgumentException.class, () -> scopes.describe(0, 65));
    assertThrows(IllegalArgumentException.class, () -> scopes.describe(-1, 1));
  }
  @Test void pagingRefusesLossyOrUndeclaredArguments() {
    for (String json : List.of("{\"offset\":0.5}", "{\"offset\":4294967296}", "{\"offset\":\"0\"}",
        "{\"limit\":null}", "{\"limit\":true}", "{\"unknown\":1}")) {
      var request = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
      assertThrows(IllegalArgumentException.class, () -> scopes.describe(request), json);
    }
  }
  @Test void aNewNestedStoreImmediatelyChangesTheChoiceSet() throws Exception {
    var directory = Files.createDirectory(temp.resolve("configured")).toRealPath(); roots.set(Set.of(directory));
    var original = scopes.select(null); assertEquals("directory", original.kind());
    var store = Files.createDirectory(directory.resolve("new-store")); String id = UUID.randomUUID().toString();
    Files.writeString(store.resolve("store.json"), StoreJson.JSON.toJson(new Header(1, "2026-01-01T00:00:00Z", id, List.of())));
    assertThrows(SourceScopes.Failure.class, () -> scopes.select(null));
    assertEquals(store, scopes.select(id).root());
    assertThrows(SourceScopes.Failure.class, () -> scopes.forPath(store.resolve("file.wpilog"), original.id()));
  }
  @Test void aScopedToolAndListingDiscoverEachRootAtMostOnce() throws Exception {
    var logs = LogManager.getInstance(); var old = logs.getConfiguredDirectories();
    temp = temp.toRealPath(); logs.clearAllowedDirectories(); logs.addAllowedDirectory(temp);
    var file = syntheticLog(temp.resolve("probe.wpilog"));
    var listing = LogDirectory.getInstance(); var priorDirectories = listing.getLogDirectories();
    listing.setLogDirectory(temp.toString());
    try {
      String id = logs.sourceScopes().select(null).id();
      var registered = new HashMap<String, ToolRegistry.Tool>();
      WpilogTools.registerAll(new ToolRegistry() {
        @Override public void registerTool(Tool tool) { registered.put(tool.name(), tool); super.registerTool(tool); }
      });
      for (String name : List.of("get_statistics", "list_available_logs")) {
        var args = new JsonObject(); args.addProperty("scope_id", id);
        if (name.equals("get_statistics")) { args.addProperty("path", file.toString()); args.addProperty("name", "/x"); }
        var owner = name.equals("get_statistics") ? logs.sourceScopes() : listing.sourceScopes();
        long before = owner.discoveryWalks();
        var result = registered.get(name).execute(args).getAsJsonObject();
        assertEquals("ok", result.get("status").getAsString(), result::toString);
        if (name.equals("get_statistics")) { assertEquals(3, result.get("count").getAsInt()); assertEquals(2, result.get("mean").getAsDouble()); }
        assertEquals(1, owner.discoveryWalks() - before, name + " must discover the configured root only once");
      }
    } finally {
      listing.setLogDirectories(priorDirectories.stream().map(Path::toString).toList());
      logs.release(temp); logs.clearAllowedDirectories(); old.forEach(logs::addAllowedDirectory);
    }
  }

  @Test void aStoreCreatedAfterOpenIsRefusedByThePostCallCheck() throws Exception {
    var logs = LogManager.getInstance(); var old = logs.getConfiguredDirectories();
    temp = temp.toRealPath(); logs.clearAllowedDirectories(); logs.addAllowedDirectory(temp);
    var directory = Files.createDirectory(temp.resolve("becomes-store")); var file = syntheticLog(directory.resolve("probe.wpilog"));
    try {
      var selected = logs.sourceScopes().select(null);
      var args = new JsonObject(); args.addProperty("scope_id", selected.id()); args.addProperty("path", file.toString());
      var tool = new LogRequiringTool() {
        @Override public String name() { return "post_call_scope_probe"; }
        @Override public String description() { return "Synthetic post-call ownership check"; }
        @Override protected JsonObject toolSchema() { return new JsonObject(); }
        @Override protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
          Files.writeString(directory.resolve("store.json"), StoreJson.JSON.toJson(new Header(1, "2026-01-01T00:00:00Z", UUID.randomUUID().toString(), List.of())));
          var result = new JsonObject(); result.addProperty("count", log.sampleCount("/x")); return result;
        }
      };
      var result = tool.execute(args).getAsJsonObject();
      assertTrue(result.has("code"), "Post-call ownership change must refuse: " + result);
      assertEquals("SOURCE_UNAVAILABLE", result.get("code").getAsString(), result::toString);
    } finally { logs.release(temp); logs.clearAllowedDirectories(); old.forEach(logs::addAllowedDirectory); }
  }

  @Test void aScopedAliasCannotOpenAnEntryThatNowBelongsToANestedStore() throws Exception {
    var logs = LogManager.getInstance(); var old = logs.getConfiguredDirectories();
    temp = temp.toRealPath(); logs.clearAllowedDirectories(); logs.addAllowedDirectory(temp);
    var nested = Files.createDirectory(temp.resolve("nested")); syntheticLog(nested.resolve("target.wpilog"));
    String id = UUID.randomUUID().toString(); var original = temp.resolve("original.wpilog");
    Files.writeString(temp.resolve("store.json"), StoreJson.JSON.toJson(new Header(1, "2026-01-01T00:00:00Z", id,
        List.of(new org.triplehelix.wpilogmcp.store.StoreManifest.Move(original.toString(), "nested/target.wpilog", java.time.Instant.now().toString())))));
    Files.writeString(nested.resolve("store.json"), StoreJson.JSON.toJson(new Header(1, "2026-01-01T00:00:00Z", UUID.randomUUID().toString(), List.of())));
    try {
      var failure = assertThrows(SourceScopes.Failure.class, () -> {
        try (var use = logs.readAccess().acquire(original.toString(), id)) { assertEquals(3, use.sampleCount("/x")); }
      });
      assertEquals("SOURCE_UNAVAILABLE", failure.result().get("code").getAsString());
    } finally { logs.release(temp); logs.clearAllowedDirectories(); old.forEach(logs::addAllowedDirectory); }
  }

  @Test void boundedRechecksKeepGrantsStoreIdentityAndAncestry() throws Exception {
    for (String mutation : List.of("revoke", "remove-header", "replace-id", "become-store", "redirect", "directory-redirect", "nested", "hidden-store")) {
      var directory = Files.createDirectories(temp.resolve(mutation)).toRealPath();
      var store = Files.createDirectory(directory.resolve("root"));
      var member = syntheticLog(store.resolve("probe.wpilog"));
      String id = UUID.randomUUID().toString();
      String header = StoreJson.JSON.toJson(new Header(1, "2026-01-01T00:00:00Z", id, List.of()));
      if (!Set.of("become-store", "directory-redirect").contains(mutation)) Files.writeString(store.resolve("store.json"), header);
      roots.set(mutation.equals("hidden-store") ? Set.of(directory) : Set.of(store));
      var selected = scopes.forPath(member, Set.of("become-store", "directory-redirect").contains(mutation) ? null : id);
      scopes.recheckPath(member, selected);
      Path checked = member;
      switch (mutation) {
        case "revoke" -> roots.set(Set.of());
        case "remove-header" -> Files.delete(store.resolve("store.json"));
        case "replace-id" -> Files.writeString(store.resolve("store.json"), StoreJson.JSON.toJson(new Header(1, "2026-01-01T00:00:00Z", UUID.randomUUID().toString(), List.of())));
        case "become-store" -> Files.writeString(store.resolve("store.json"), header);
        case "redirect", "directory-redirect" -> checked = syntheticLog(directory.resolve("other.wpilog"));
        case "nested" -> {
          var nested = Files.createDirectory(store.resolve("new-store"));
          checked = syntheticLog(nested.resolve("probe.wpilog")); Files.writeString(nested.resolve("store.json"), header);
        }
        case "hidden-store" -> Files.writeString(directory.resolve("store.json"), header);
      }
      var path = checked; long before = scopes.discoveryWalks();
      var failure = assertThrows(SourceScopes.Failure.class, () -> scopes.recheckPath(path, selected), mutation);
      assertEquals("SOURCE_UNAVAILABLE", failure.result().get("code").getAsString(), mutation);
      assertEquals(before, scopes.discoveryWalks(), "Refusal must not walk unrelated trees: " + mutation);
    }
  }

  private static Path syntheticLog(Path path) throws Exception {
    try (var writer = new WpilogWriter(path, "")) {
      int id = writer.start("/x", "double", "", 0);
      for (int i = 1; i <= 3; i++) writer.append(id, i * 20_000L, WpilogWriter.encodeDouble(i));
    }
    return path;
  }

  @Test void discoveryFailurePreservesKnownStoreIdentityAndColdDirectoryChoice() throws Exception {
    var healthy = Files.createDirectory(temp.resolve("healthy")).toRealPath();
    var denied = Files.createDirectory(temp.resolve("denied")).toRealPath();
    org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(denied).supportsFileAttributeView("posix"), "POSIX permissions required");
    String store = UUID.randomUUID().toString();
    Files.writeString(denied.resolve("store.json"), StoreJson.JSON.toJson(new Header(1, "2026-01-01T00:00:00Z", store, List.of())));
    roots.set(Set.of(healthy, denied)); assertEquals("store", scopes.select(store).kind());
    String chosen = scopes.snapshot().stream().filter(s -> s.root().equals(healthy)).findFirst().orElseThrow().id();
    var permissions = Files.getPosixFilePermissions(denied);
    try {
      Files.setPosixFilePermissions(denied, Set.of());
      org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(denied), "Process can bypass directory permissions");
      var unavailable = scopes.snapshot().stream().filter(s -> s.id().equals(store)).findFirst().orElseThrow();
      assertEquals("store", unavailable.kind()); assertFalse(unavailable.available());
      assertEquals(healthy, scopes.select(chosen).root());
      assertEquals("SOURCE_UNAVAILABLE", assertThrows(SourceScopes.Failure.class, () -> scopes.select(store)).result().get("code").getAsString());
      var cold = new SourceScopes(roots::get); var choices = cold.snapshot();
      assertEquals(2, choices.size());
      var unknown = choices.stream().filter(s -> s.root().equals(denied)).findFirst().orElseThrow();
      assertFalse(unknown.available()); assertEquals("directory", unknown.kind(), "Unreadable metadata cannot establish a store identity");
      assertEquals(healthy, cold.select(chosen).root());
      assertEquals("SOURCE_SELECTION_REQUIRED", assertThrows(SourceScopes.Failure.class, () -> cold.select(null)).result().get("code").getAsString());
    } finally { Files.setPosixFilePermissions(denied, permissions); }
    assertEquals(denied, scopes.select(store).root());
  }
}
