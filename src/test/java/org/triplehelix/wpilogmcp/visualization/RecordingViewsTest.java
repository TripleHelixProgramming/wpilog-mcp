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
import org.triplehelix.wpilogmcp.store.LogStore;

/** Working records hold durable intent, not leases or a frozen compatibility verdict. */
class RecordingViewsTest {
  @TempDir Path temp;
  final LogManager logs = LogManager.getInstance();
  Set<Path> original; RecordingFixture fixture; VisualizationWorkspace workspace; ServerIdentity identity;
  @BeforeEach void start() throws Exception {
    temp = temp.toRealPath(); original = logs.getConfiguredDirectories(); logs.clearAllowedDirectories(); logs.addAllowedDirectory(temp);
    fixture = new RecordingFixture(temp.resolve("recording"), 8); identity = ServerIdentity.open(temp.resolve("state"));
    workspace = new VisualizationWorkspace(identity, logs);
  }
  @AfterEach void stop() throws Exception {
    workspace.close(); logs.addAllowedDirectory(temp); assertTrue(logs.release(temp).released(), "Metadata operations cannot retain source mappings");
    logs.clearAllowedDirectories(); original.forEach(logs::addAllowedDirectory);
  }
  JsonObject request() {
    var out = new JsonObject(); out.add("source_ref", fixture.reference.deepCopy()); out.addProperty("request_id", UUID.randomUUID().toString()); return out;
  }
  JsonObject create(JsonObject request) throws Exception { return workspace.create(Authority.STDIO_LOCAL, request); }
  static UUID id(JsonObject view) { return UUID.fromString(view.get("view_id").getAsString()); }
  static JsonObject operation(JsonObject view) {
    var out = new JsonObject(); out.add("view_id", view.get("view_id")); out.add("expected_revision", view.get("revision"));
    out.addProperty("request_id", UUID.randomUUID().toString()); return out;
  }
  JsonObject seed() throws Exception {
    return workspace.views.create(Authority.STDIO_LOCAL, UUID.randomUUID(), fixture.reference, VisualizationContracts.blank()).get();
  }
  JsonObject save(JsonObject view) throws Exception {
    var root = temp.resolve("layouts"); logs.stores().store(root).importPaths(new LogStore.Request(List.of(), false, null), ignored -> {}).get();
    var store = workspace.stores().getAsJsonArray("stores").asList().stream().map(JsonElement::getAsJsonObject)
        .filter(s -> s.get("path").getAsString().equals(root.toString())).findFirst().orElseThrow();
    var request = operation(view); request.add("store_id", store.get("store_id")); request.addProperty("title", "Synthetic recording layout");
    return workspace.saveView(Authority.STDIO_LOCAL, request);
  }
  static JsonObject reopen(JsonObject saved) {
    var request = saved.getAsJsonObject("saved_view_ref").deepCopy(); request.addProperty("request_id", UUID.randomUUID().toString()); return request;
  }
  void corrupt() throws Exception {
    var path = fixture.directory.resolve("capture.wpilog"); org.triplehelix.wpilogmcp.fixtures.LogRewrites.flipLastByte(path);
    Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2000));
  }
  @Test void healthRequiresLocalAuthorityBeforeResolvingAnIdentity() {
    assertThrows(java.nio.file.AccessDeniedException.class, () -> workspace.health(Authority.UNTRUSTED, new JsonObject()));
  }
  @Test void recordingDraftsKeepIdentityAndIndependentConfigurationsWithoutSourceLeases() throws Exception {
    var first = create(request()); var second = create(request());
    assertEquals(fixture.reference, first.get("source_ref")); assertNotEquals(first.get("view_id"), second.get("view_id"));
    assertEquals(VisualizationContracts.blank(), first.get("configuration")); assertFalse(first.get("native_dirty").getAsBoolean());
    assertEquals("follow", first.getAsJsonObject("configuration").getAsJsonObject("time").getAsJsonObject("live").get("mode").getAsString());
    assertEquals(30, first.getAsJsonObject("configuration").getAsJsonObject("time").getAsJsonObject("live").get("window_sec").getAsInt());
    var changed = VisualizationContracts.blank(); changed.getAsJsonObject("links").addProperty("cursor", false);
    workspace.views.update(Authority.STDIO_LOCAL, id(first), 1, changed).get();
    assertEquals(VisualizationContracts.blank(), workspace.views.get(id(second)).get("configuration"));
    assertTrue(logs.release(fixture.root).released());
    assertTrue(workspace.capabilities().get("recording_views").getAsBoolean()); assertFalse(workspace.capabilities().get("live").getAsBoolean());
  }
  @Test void retriesReuseOneDraftAndChangedIntentRefuses() throws Exception {
    var input = request(); var view = create(input); assertEquals(view, create(input));
    input.add("configuration", VisualizationContracts.blank()); input.getAsJsonObject("configuration").getAsJsonObject("links").addProperty("cursor", false);
    assertTrue(assertThrows(Exception.class, () -> create(input)).toString().contains("REQUEST_CONFLICT"));
    assertEquals(1, workspace.views.all().size());
  }
  @Test void explicitAndPinnedTemplateIntentSurvivesAnotherTemplateSelection() throws Exception {
    var ref = workspace.templates(new JsonObject()).getAsJsonArray("templates").get(0).getAsJsonObject().getAsJsonObject("template_ref");
    var expected = workspace.template(object("template_ref", ref)).getAsJsonObject("template").get("configuration");
    var input = request(); input.add("template_ref", ref); var first = create(input);
    assertEquals(ref, first.get("template_ref")); assertEquals(expected, first.get("configuration"));
    var explicit = request(); var config = RecordingBindingsTest.config("entry", "/not-yet-declared", "double", "number", null);
    config.getAsJsonObject("time").add("live", JsonParser.parseString("{\"mode\":\"fixed\",\"start_sec\":2,\"end_sec\":5}"));
    explicit.add("configuration", config); var second = create(explicit);
    assertEquals(config, second.get("configuration"), "An unresolved layout remains editable and retains its explicit range");
    assertEquals(expected, workspace.views.get(id(first)).get("configuration"));
  }
  @Test void savedRecordingReopensWithAnotherWorkingIdAndPinnedSourceAfterRestart() throws Exception {
    var before = create(request()); var saved = save(before); var input = reopen(saved);
    workspace.close(); workspace = new VisualizationWorkspace(identity, logs);
    var opened = workspace.openSaved(Authority.STDIO_LOCAL, input);
    assertNotEquals(before.get("view_id"), opened.get("view_id")); assertEquals(before.get("source_ref"), opened.get("source_ref"));
    assertEquals(saved.get("saved_view_ref"), opened.get("saved_view_ref")); assertFalse(opened.get("native_dirty").getAsBoolean());
    assertEquals(opened, workspace.openSaved(Authority.STDIO_LOCAL, input));
    assertEquals(before.get("source_ref"), workspace.views.get(id(before)).get("source_ref"));
  }
  @Test void savedOpenCanAdmitAnExistingRecordingRecord() throws Exception {
    var saved = save(seed()); var opened = workspace.openSaved(Authority.STDIO_LOCAL, reopen(saved));
    assertEquals(fixture.reference, opened.get("source_ref")); assertEquals(saved.get("saved_view_ref"), opened.get("saved_view_ref"));
  }
  @Test void cloneReadmitsSourceBeforeCreatingAndLeavesTheOriginalUnchanged() throws Exception {
    var source = seed(); var input = operation(source); var copy = workspace.cloneView(Authority.STDIO_LOCAL, input);
    assertNotEquals(source.get("view_id"), copy.get("view_id")); assertEquals(source.get("configuration"), copy.get("configuration"));
    assertEquals(copy, workspace.cloneView(Authority.STDIO_LOCAL, input));
    corrupt(); int count = workspace.views.all().size();
    assertThrows(Exception.class, () -> workspace.cloneView(Authority.STDIO_LOCAL, operation(source)));
    assertEquals(count, workspace.views.all().size()); assertEquals(source.get("configuration"), workspace.views.get(id(source)).get("configuration"));
  }
  @Test void failedAdmissionDoesNotMintADraftOrRewriteExistingMetadata() throws Exception {
    var existing = seed(); corrupt();
    assertThrows(Exception.class, () -> create(request())); assertEquals(1, workspace.views.all().size());
    assertEquals(existing.get("source_ref"), workspace.views.get(id(existing)).get("source_ref"));
    workspace.close(); workspace = new VisualizationWorkspace(identity, logs);
    assertEquals(existing.get("configuration"), workspace.views.get(id(existing)).get("configuration"));
  }
  @Test void withdrawnGrantRefusesCreateCloneAndSavedOpenButPreservesRecovery() throws Exception {
    var existing = seed(); var saved = save(existing); var input = reopen(saved); var layouts = temp.resolve("layouts");
    logs.clearAllowedDirectories(); logs.addAllowedDirectory(layouts);
    assertThrows(Exception.class, () -> create(request()));
    assertThrows(Exception.class, () -> workspace.cloneView(Authority.STDIO_LOCAL, operation(saved)));
    assertThrows(Exception.class, () -> workspace.openSaved(Authority.STDIO_LOCAL, input));
    assertEquals(1, workspace.views.all().size()); assertEquals(fixture.reference, workspace.views.get(id(saved)).get("source_ref"));
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"create", "clone", "open"})
  void admissionSlotsAreSharedWithCompatibilityForAllCreationPaths(String operation) throws Exception {
    var existing = save(seed());
    var field = VisualizationWorkspace.class.getDeclaredField("recordingBindings"); field.setAccessible(true); var owner = field.get(workspace);
    var slotField = owner.getClass().getDeclaredField("slots"); slotField.setAccessible(true);
    var slots = (java.util.concurrent.Semaphore) slotField.get(owner); slots.acquire(2);
    try {
      var refusal = assertThrows(org.triplehelix.wpilogmcp.read.ReadException.class, () -> {
        switch (operation) {
          case "create" -> create(request());
          case "clone" -> workspace.cloneView(Authority.STDIO_LOCAL, operation(existing));
          case "open" -> workspace.openSaved(Authority.STDIO_LOCAL, reopen(existing));
          default -> fail("unknown fixture operation");
        }
      });
      assertTrue(refusal.retryable()); assertEquals(org.triplehelix.wpilogmcp.read.ReadProblem.Code.RESOURCE_LIMIT, refusal.code());
      assertEquals(1, workspace.views.all().size());
    } finally { slots.release(2); }
    assertEquals(fixture.reference, create(request()).get("source_ref"));
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"create", "clone", "open"})
  void untrustedMetadataOperationsRefuseBeforeLookingUpAnySource(String operation) {
    assertThrows(java.nio.file.AccessDeniedException.class, () -> {
      var missing = new JsonObject();
      switch (operation) {
        case "create" -> workspace.create(Authority.UNTRUSTED, missing);
        case "clone" -> workspace.cloneView(Authority.UNTRUSTED, missing);
        case "open" -> workspace.openSaved(Authority.UNTRUSTED, missing);
        default -> fail("unknown fixture operation");
      }
    });
  }
  static JsonObject object(String key, JsonElement value) { var out = new JsonObject(); out.add(key, value); return out; }
}
