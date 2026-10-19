/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.RecordingFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.CallerContext.Authority;

class VisualizationLibraryWorkspaceTest {
  @TempDir Path temp;
  final LogManager logs = LogManager.getInstance();
  Set<Path> original; VisualizationWorkspace workspace; ServerIdentity identity;
  @BeforeEach void start() throws Exception {
    temp = temp.toRealPath(); original = logs.getConfiguredDirectories(); logs.clearAllowedDirectories(); logs.addAllowedDirectory(temp);
    identity = ServerIdentity.open(temp.resolve("state")); workspace = new VisualizationWorkspace(identity, logs);
  }
  @AfterEach void stop() throws Exception {
    workspace.close(); logs.addAllowedDirectory(temp); assertTrue(logs.release(temp).released());
    logs.clearAllowedDirectories(); original.forEach(logs::addAllowedDirectory);
  }
  static JsonObject request() {
    var r = new JsonObject(); r.addProperty("api_version", 2); r.addProperty("request_id", UUID.randomUUID().toString()); return r;
  }
  @Test void ordinaryFolderSaveNeedsNoWritableLogStoreAndReopensAfterRestart() throws Exception {
    var fixture = new RecordingFixture(temp.resolve("fixture"), 2);
    var file = Files.copy(fixture.directory.resolve("capture.wpilog"), temp.resolve("ordinary.wpilog"));
    var source = workspace.sources.identify(Authority.STDIO_LOCAL, file).getAsJsonObject("source_ref");
    var create = request(); create.add("source_ref", source); var view = workspace.create(Authority.STDIO_LOCAL, create);
    var save = request(); save.add("view_id", view.get("view_id")); save.add("expected_revision", view.get("revision")); save.addProperty("title", "Ordinary folder");
    var saved = workspace.saveView(Authority.STDIO_LOCAL, save);
    assertFalse(saved.getAsJsonObject("saved_view_ref").has("store_id"));
    assertEquals(identity.id().toString(), saved.getAsJsonObject("saved_view_ref").get("server_instance_id").getAsString());
    assertEquals(saved, workspace.saveView(Authority.STDIO_LOCAL, save));
    workspace.close(); workspace = new VisualizationWorkspace(identity, logs);
    var open = request(); open.add("saved_view_ref", saved.get("saved_view_ref"));
    var reopened = workspace.openSaved(Authority.STDIO_LOCAL, open);
    assertEquals(source, reopened.get("source_ref")); assertEquals(saved.get("configuration"), reopened.get("configuration"));
    assertFalse(Files.exists(temp.resolve(".wpilog-store")), "Saving must not enroll a folder");
  }
  @Test void templateCreateUsesServerLibraryWithoutAStoreAndRetriesExactly() throws Exception {
    var create = request(); create.addProperty("name", "Library template"); create.addProperty("description", "Synthetic"); create.add("configuration", VisualizationContracts.blank());
    var saved = workspace.createTemplate(Authority.STDIO_LOCAL, create);
    assertEquals("library", saved.getAsJsonObject("template_ref").get("origin").getAsString());
    assertEquals(saved, workspace.createTemplate(Authority.STDIO_LOCAL, create));
    var get = request(); get.add("template_ref", saved.get("template_ref")); assertEquals(saved, workspace.template(get));
  }
  @Test void ephemeralServerRefusesDurableSaveExplicitly() throws Exception {
    workspace.close(); workspace = new VisualizationWorkspace(ServerIdentity.ephemeral("test"), logs);
    var create = request(); create.addProperty("name", "Cannot persist"); create.addProperty("description", ""); create.add("configuration", VisualizationContracts.blank());
    var failure = assertThrows(Exception.class, () -> workspace.createTemplate(Authority.STDIO_LOCAL, create));
    assertTrue(failure.toString().contains("LIBRARY_UNAVAILABLE"), failure.toString());
  }
  JsonObject ordinaryView() throws Exception {
    var fixture = new RecordingFixture(temp.resolve("fixture"), 2);
    var file = Files.copy(fixture.directory.resolve("capture.wpilog"), temp.resolve("ordinary.wpilog"));
    var source = workspace.sources.identify(Authority.STDIO_LOCAL, file).getAsJsonObject("source_ref");
    var create = request(); create.add("source_ref", source); return workspace.create(Authority.STDIO_LOCAL, create);
  }
  static JsonObject saveRequest(JsonObject view) {
    var request = request(); request.add("view_id", view.get("view_id")); request.add("expected_revision", view.get("revision")); request.addProperty("title", "Saved definition"); return request;
  }
  @Test void libraryRetainsUnmanagedLocatorAfterEveryWorkingDraftExpires() throws Exception {
    var view = ordinaryView(); var saved = workspace.saveView(Authority.STDIO_LOCAL, saveRequest(view));
    workspace.close(); var path = identity.stateDirectory().resolve("views/" + view.get("view_id").getAsString() + ".json");
    var record = JsonParser.parseString(Files.readString(path)).getAsJsonObject(); record.addProperty("detached_expires_at", "2000-01-01T00:00:00Z"); Files.writeString(path, record.toString());
    workspace = new VisualizationWorkspace(identity, logs); assertTrue(workspace.views.all().isEmpty());
    assertThrows(Exception.class, () -> workspace.views.sourceLocator(UUID.fromString(view.getAsJsonObject("source_ref").get("source_id").getAsString())));
    var open = request(); open.add("saved_view_ref", saved.get("saved_view_ref"));
    var reopened = workspace.openSaved(Authority.STDIO_LOCAL, open);
    assertEquals(view.get("source_ref"), reopened.get("source_ref")); assertEquals(temp.resolve("ordinary.wpilog"), workspace.sources.resolve(reopened.getAsJsonObject("source_ref")));
    logs.clearAllowedDirectories(); logs.addAllowedDirectory(identity.stateDirectory());
    assertThrows(Exception.class, () -> workspace.openSaved(Authority.STDIO_LOCAL, open), "Restoring a locator cannot restore a revoked grant");
  }
  @Test void committedLibrarySaveRecoversItsOriginalCheckpointAfterCrashAndInterveningEdit() throws Exception {
    var view = ordinaryView(); var save = saveRequest(view); var intent = save.deepCopy(); intent.add("revision", intent.get("expected_revision")); intent.addProperty("operation", "save");
    UUID id = UUID.fromString(view.get("view_id").getAsString()), operation = UUID.fromString(save.get("request_id").getAsString());
    workspace.views.prepareMutation(Authority.STDIO_LOCAL, id, 1, operation, intent, null).get();
    var candidate = new JsonObject(); candidate.addProperty("schema_version", 1); candidate.addProperty("saved_view_id", operation.toString()); candidate.addProperty("revision", 1); candidate.add("title", save.get("title"));
    for (String key : List.of("source_ref", "configuration", "template_ref")) candidate.add(key, view.get(key));
    var locator = workspace.views.sourceLocator(UUID.fromString(view.getAsJsonObject("source_ref").get("source_id").getAsString()));
    var committed = workspace.library.save(Authority.STDIO_LOCAL, "view", operation, 0, candidate, locator, id).get();
    var changed = VisualizationContracts.blank(); changed.getAsJsonObject("links").addProperty("cursor", false);
    workspace.views.update(Authority.STDIO_LOCAL, id, 1, changed).get();
    workspace.close(); workspace = new VisualizationWorkspace(identity, logs);
    var recovered = workspace.saveView(Authority.STDIO_LOCAL, save);
    assertEquals(committed.get("saved_view_ref"), recovered.get("saved_view_ref")); assertEquals(changed, recovered.get("configuration"));
    assertEquals(view.get("configuration"), recovered.get("checkpoint_configuration")); assertTrue(recovered.get("native_dirty").getAsBoolean());
    assertEquals(recovered, workspace.saveView(Authority.STDIO_LOCAL, save));
    assertEquals(1, workspace.library.list(Authority.STDIO_LOCAL, VisualizationLibraryTest.listRequest("view", 256)).get().get("total_matching").getAsInt());
    var next = saveRequest(recovered); next.remove("title"); var resaved = workspace.saveView(Authority.STDIO_LOCAL, next);
    assertEquals(2, resaved.getAsJsonObject("saved_view_ref").get("revision").getAsInt()); assertFalse(resaved.get("native_dirty").getAsBoolean());
  }
  @Test void committedSaveWithFailedCheckpointExplainsThatTheOriginalRequestMustBeRetried() throws Exception {
    var view = ordinaryView(); var save = saveRequest(view);
    workspace.library.beforeWrite = () -> workspace.views.close();
    var failure = assertThrows(Exception.class, () -> workspace.saveView(Authority.STDIO_LOCAL, save));
    assertTrue(failure.toString().contains("LIBRARY_CHECKPOINT_PENDING"), failure.toString());
    workspace.close(); workspace = new VisualizationWorkspace(identity, logs);
    var recovered = workspace.saveView(Authority.STDIO_LOCAL, save);
    assertFalse(recovered.get("saved_view_ref").isJsonNull());
    assertEquals(1, workspace.library.list(Authority.STDIO_LOCAL, VisualizationLibraryTest.listRequest("view", 256)).get().get("total_matching").getAsInt());
  }
  @Test void explicitMirrorSaveNeverMutatesMirrorMetadataOrPayloads() throws Exception {
    var fixture = new RecordingFixture(temp.resolve("mirror"), 2);
    var headerPath = fixture.root.resolve("store.json");
    var header = org.triplehelix.wpilogmcp.store.StoreJson.JSON.fromJson(Files.readString(headerPath), org.triplehelix.wpilogmcp.store.StoreManifest.Header.class);
    String origin = fixture.reference.get("origin_store_id").getAsString(); String mirrorId = UUID.randomUUID().toString();
    header = new org.triplehelix.wpilogmcp.store.StoreManifest.Header(1, RecordingFixture.WHEN, mirrorId, List.of())
        .withOrigin(new org.triplehelix.wpilogmcp.store.StoreManifest.MirrorOrigin(origin, "http://synthetic.invalid", RecordingFixture.WHEN, List.of(), Map.of()));
    Files.writeString(headerPath, org.triplehelix.wpilogmcp.store.StoreJson.JSON.toJson(header)); logs.catalogPublished();
    var before = new TreeMap<String, String>(); try (var files = Files.walk(fixture.root)) {
      for (var file : files.filter(Files::isRegularFile).toList()) before.put(file.toString(), org.triplehelix.wpilogmcp.store.LogStore.contentHash(file));
    }
    var create = request(); create.add("source_ref", VisualizationContracts.qualify(identity.id(), mirrorId, fixture.reference));
    var view = workspace.create(Authority.STDIO_LOCAL, create); var saved = workspace.saveView(Authority.STDIO_LOCAL, saveRequest(view));
    assertEquals(create.get("source_ref"), saved.get("source_ref")); assertTrue(saved.getAsJsonObject("saved_view_ref").has("library_id"));
    var after = new TreeMap<String, String>(); try (var files = Files.walk(fixture.root)) {
      for (var file : files.filter(Files::isRegularFile).toList()) after.put(file.toString(), org.triplehelix.wpilogmcp.store.LogStore.contentHash(file));
    }
    assertEquals(before, after);
  }
  @Test void legacySaveMigrationRetainsOriginalBytesAndAdvancesOnlyLibraryCheckpoint() throws Exception {
    var view = ordinaryView(); var store = temp.resolve("legacy");
    logs.stores().store(store).importPaths(new org.triplehelix.wpilogmcp.store.LogStore.Request(List.of(), false, null), ignored -> {}).get();
    var selected = workspace.stores().getAsJsonArray("stores").asList().stream().map(JsonElement::getAsJsonObject).filter(s -> s.get("path").getAsString().equals(store.toString())).findFirst().orElseThrow();
    var oldSave = saveRequest(view); oldSave.addProperty("api_version", 1); oldSave.add("store_id", selected.get("store_id"));
    var legacy = workspace.saveView(Authority.STDIO_LOCAL, oldSave);
    var legacyFiles = new TreeMap<String, String>(); try (var files = Files.walk(store)) {
      for (var file : files.filter(Files::isRegularFile).toList()) legacyFiles.put(file.toString(), org.triplehelix.wpilogmcp.store.LogStore.contentHash(file));
    }
    var converted = workspace.saveView(Authority.STDIO_LOCAL, saveRequest(legacy));
    assertTrue(converted.getAsJsonObject("saved_view_ref").has("library_id")); assertEquals(2, converted.getAsJsonObject("saved_view_ref").get("revision").getAsInt());
    var migration = request(); migration.remove("request_id"); migration.addProperty("kind", "view"); migration.add("legacy_ref", legacy.get("saved_view_ref"));
    var alias = workspace.migrateLibrary(Authority.STDIO_LOCAL, migration); assertEquals(1, alias.getAsJsonObject("saved_view_ref").get("revision").getAsInt());
    for (var entry : legacyFiles.entrySet()) assertEquals(entry.getValue(), org.triplehelix.wpilogmcp.store.LogStore.contentHash(Path.of(entry.getKey())));
  }
  @Test void reusedRequestCannotBlendEqualConfigurationsFromDifferentWorkingOrSavedViews() throws Exception {
    var a = ordinaryView(); var create = request(); create.add("source_ref", a.get("source_ref")); var b = workspace.create(Authority.STDIO_LOCAL, create);
    var firstRequest = saveRequest(a); var savedA = workspace.saveView(Authority.STDIO_LOCAL, firstRequest);
    var reused = saveRequest(b); reused.add("request_id", firstRequest.get("request_id"));
    VisualizationLibraryTest.code("REQUEST_CONFLICT", () -> workspace.saveView(Authority.STDIO_LOCAL, reused));
    var savedB = workspace.saveView(Authority.STDIO_LOCAL, saveRequest(b));
    var open = request(); open.add("saved_view_ref", savedA.get("saved_view_ref")); workspace.openSaved(Authority.STDIO_LOCAL, open);
    open.add("saved_view_ref", savedB.get("saved_view_ref"));
    VisualizationLibraryTest.code("REQUEST_CONFLICT", () -> workspace.openSaved(Authority.STDIO_LOCAL, open));
  }
  @Test void templateFromViewRetryKeepsReservedConfigurationAfterLaterEditAndRestart() throws Exception {
    var view = ordinaryView(); var create = request(); create.add("view_id", view.get("view_id")); create.addProperty("expected_view_revision", 1);
    create.addProperty("name", "Pinned view template"); create.addProperty("description", "");
    var first = workspace.createTemplate(Authority.STDIO_LOCAL, create);
    var changed = VisualizationContracts.blank(); changed.getAsJsonObject("links").addProperty("cursor", false);
    workspace.views.update(Authority.STDIO_LOCAL, UUID.fromString(view.get("view_id").getAsString()), 1, changed).get();
    workspace.close(); workspace = new VisualizationWorkspace(identity, logs);
    assertEquals(first, workspace.createTemplate(Authority.STDIO_LOCAL, create));
    assertEquals(view.get("configuration"), first.getAsJsonObject("template").get("configuration"));
    assertEquals(changed, workspace.views.get(UUID.fromString(view.get("view_id").getAsString())).get("configuration"));
  }
}
