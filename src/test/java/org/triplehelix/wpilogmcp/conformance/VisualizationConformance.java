/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.*;
import org.triplehelix.wpilogmcp.store.*;
import org.triplehelix.wpilogmcp.tools.WpilogTools;
import org.triplehelix.wpilogmcp.visualization.*;

/** Success cases use the real dispatcher and isolated state, in addition to the ordinary schema
 * corpus's untrusted refusals. No tool can satisfy its description using only error responses. */
final class VisualizationConformance {
  private final ToolRegistry registry = new ToolRegistry();
  private final List<ToolConformanceTest.Call> calls = new ArrayList<>();
  private final McpMessageHandler handler = new McpMessageHandler(registry);
  private static JsonObject args() { var v = new JsonObject(); v.addProperty("api_version", 1); return v; }
  private static JsonObject mutation() { var v = args(); v.addProperty("request_id", UUID.randomUUID().toString()); return v; }
  private JsonObject call(String operation, JsonObject arguments, String resultKey) { return call(operation, arguments, resultKey, "ok"); }
  private JsonObject call(String operation, JsonObject arguments, String resultKey, String expectedStatus) {
    String name = "visualization_" + operation; var request = new JsonObject(); request.addProperty("jsonrpc", "2.0"); request.addProperty("id", 1); request.addProperty("method", "tools/call");
    var params = new JsonObject(); params.addProperty("name", name); params.add("arguments", arguments); request.add("params", params);
    var rpc = handler.handleMessage(request, null, CallerContext.Authority.STDIO_LOCAL).response();
    var result = JsonParser.parseString(rpc.getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject();
    var tool = registry.getTool(name);
    calls.add(new ToolConformanceTest.Call(name, "isolated-workspace", "recorded-reuse", result,
        ConformanceChecks.check(result, null, ToolArguments.takesPath(tool), name, arguments)));
    if (!result.get("status").getAsString().equals(expectedStatus)) throw new AssertionError(name + ": " + result);
    return result.getAsJsonObject(resultKey);
  }
  static List<ToolConformanceTest.Call> exercise(Path root, Path fixture) throws Exception {
    var run = new VisualizationConformance(); run.registry.serverIdentity(ServerIdentity.open(root.resolve("state")));
    WpilogTools.registerAll(run.registry);
    try {
      var recording = new org.triplehelix.wpilogmcp.fixtures.RecordingFixture(root.resolve("recording-store"), 4);
      run.call("capabilities_get", args(), "capabilities");
      // The ordinary stdio corpus has no editor window. Exercise the populated owner too;
      // endpoint tests separately prove that only the admitted session can register this fact.
      String window = UUID.randomUUID().toString();
      run.registry.visualizations().windows.register(window, 16777216);
      var reported = run.call("capabilities_get", args(), "capabilities").getAsJsonArray("attached_windows");
      var allowance = reported.get(0).getAsJsonObject();
      if (reported.toString().contains(window)) throw new AssertionError("Capabilities must not disclose a window's session ID");
      if (!allowance.get("client_id").getAsString().equals(run.registry.visualizations().views.windowIds().forSession(window))
          || allowance.get("current_session").getAsBoolean()
          || allowance.get("decoded_buffer_budget_bytes").getAsLong() != 16777216
          || !allowance.get("basis").getAsString().equals("client_reported"))
        throw new AssertionError("Capabilities must retain the reported window allowance under its window ID; stdio has no window");
      run.registry.visualizations().windows.release(window);
      var inspect = args(); inspect.addProperty("path", fixture.toString()); var source = run.call("source_inspect", inspect, "source").getAsJsonObject("source_ref");
      var healthArgs = args(); healthArgs.add("source_ref", recording.reference);
      var health = run.call("source_health", healthArgs, "health");
      if (!health.get("history_state").getAsString().equals("finalized") || !health.get("acquisition_state").getAsString().equals("inactive")
          || !health.get("connected").isJsonNull() || !health.get("source_ref").equals(recording.reference))
        throw new AssertionError("Health describes this finalized anchor without inventing an active acquisition");
      var acquisition = new org.triplehelix.wpilogmcp.capture.LiveCapture(recording.root,
          new org.triplehelix.wpilogmcp.nt4.client.ManualScheduler());
      acquisition.manifest(recording.root.resolve("store.json"), StoreJson.JSON.fromJson(Files.readString(recording.root.resolve("store.json")), StoreManifest.Header.class));
      acquisition.status(new CaptureStore.Status(recording.directory.resolve("capture.wpilog"),
          java.time.Instant.parse(org.triplehelix.wpilogmcp.fixtures.RecordingFixture.WHEN), null, "synthetic", false, null, null, null, null, null, null));
      acquisition.publicationTiming(new org.triplehelix.wpilogmcp.capture.CaptureIndex.PublicationTiming(
          "synthetic-writer", 7, org.triplehelix.wpilogmcp.fixtures.RecordingFixture.WHEN, 9.0));
      run.registry.captureSource(() -> acquisition);
      var timed = run.call("source_health", healthArgs, "health").getAsJsonObject("publication_timing");
      if (!timed.get("writer_identity").getAsString().equals("synthetic-writer") || timed.get("publication_revision").getAsLong() != 7
          || timed.get("receive_to_publication_ms").getAsDouble() != 9.0)
        throw new AssertionError("Health preserves published capture timing in its own monotonic clock domain");
      run.registry.captureSource(() -> null);
      var analysis = args(); analysis.add("source_ref", recording.reference); analysis.addProperty("entry", "/signal/00000");
      var value = run.call("source_value", analysis, "value");
      if (!value.getAsJsonObject("value").get("value").getAsString().equals("1")) throw new AssertionError("Recording cursor oracle");
      var statistics = run.call("source_statistics", analysis, "statistics");
      if (statistics.get("count").getAsInt() != 3 || Math.abs(statistics.get("mean").getAsDouble() - 1.0 / 3) > 1e-12)
        throw new AssertionError("Recording statistics oracle");
      var emptyWindow = analysis.deepCopy(); emptyWindow.addProperty("start_time", 500); emptyWindow.addProperty("end_time", 600);
      var emptyStatistics = run.call("source_statistics", emptyWindow, "statistics", "no_match");
      if (emptyStatistics.get("count").getAsInt() != 0 || emptyStatistics.has("mean")
          || !emptyStatistics.has("looked_for") || !emptyStatistics.has("hint") || !emptyStatistics.has("prefix"))
        throw new AssertionError("Empty recording statistics retain evidence without inventing measurements");
      var reducedArgs = analysis.deepCopy(); reducedArgs.addProperty("max_points", 1);
      var reduced = run.call("source_reduction", reducedArgs, "reduction");
      if (reduced.get("sample_count").getAsInt() != 3 || reduced.getAsJsonArray("buckets").size() != 1
          || reduced.getAsJsonArray("buckets").get(0).getAsJsonObject().get("max").getAsInt() != 1
          || Math.abs(reduced.getAsJsonArray("buckets").get(0).getAsJsonObject().get("mean").getAsDouble() - 1.0 / 3) > 1e-12)
        throw new AssertionError("Reduction must include every member and original sample");
      var histogram = run.call("source_histogram", analysis, "histogram");
      if (histogram.getAsJsonArray("bins").get(0).getAsJsonObject().get("count").getAsInt() != 2) throw new AssertionError("Histogram original count oracle");
      var scatterArgs = args(); scatterArgs.add("source_ref", recording.reference); scatterArgs.addProperty("x_entry", "/signal/00000"); scatterArgs.addProperty("y_entry", "/signal/00000");
      var scatter = run.call("source_scatter", scatterArgs, "scatter");
      if (scatter.get("matched").getAsInt() != 3 || scatter.get("unpaired_x").getAsInt() != 0) throw new AssertionError("Scatter exact pairing oracle");
      analysis.addProperty("at_sec", -1); run.call("source_value", analysis, "value", "no_match");
      var paged = args(); paged.add("source_ref", recording.reference); paged.addProperty("inventory_mode", "paged");
      var inventory = run.call("source_inspect", paged, "source").getAsJsonObject("inventory");
      var listing = args(); listing.add("inventory_id", inventory.get("inventory_id"));
      listing.addProperty("limit", 2); var page = run.call("source_entries_list", listing, "page"); listing.remove("limit");
      var noMatch = listing.deepCopy(); noMatch.addProperty("query", "absent-name");
      run.call("source_entries_list", noMatch, "page", "no_match");
      var selected = listing.deepCopy(); var ids = new JsonArray(); ids.add(page.getAsJsonArray("entries").get(0).getAsJsonObject().get("row_id")); selected.add("row_ids", ids);
      run.call("source_entries_get", selected, "details"); run.call("source_inventory_close", listing, "inventory");
      var catalog = run.call("template_list", args(), "catalog"); var bundled = catalog.getAsJsonArray("templates").get(0).getAsJsonObject().getAsJsonObject("template_ref");
      var ref = args(); ref.add("template_ref", bundled); run.call("template_get", ref, "definition");
      var check = ref.deepCopy(); check.add("source_ref", source); run.call("template_check", check, "compatibility");
      var storeRoot = root.resolve("store");
      LogManager.getInstance().stores().store(storeRoot).importPaths(new LogStore.Request(List.of(), false, null), ignored -> {}).get();
      var store = new StoreDoor(LogManager.getInstance()::getAllowedDirectories).stores().stream().filter(s -> s.root().equals(storeRoot)).findFirst().orElseThrow();
      var create = mutation(); create.addProperty("store_id", store.description().id()); create.addProperty("name", "Conformance template"); create.addProperty("description", "Generated empty definition"); create.addProperty("configuration_json", VisualizationContracts.blank().toString());
      var template = run.call("template_create", create, "definition");
      var update = mutation(); update.add("template_ref", template.get("template_ref")); update.addProperty("expected_revision", 1); update.addProperty("name", "Revised template"); update.addProperty("description", "Explicit CAS"); update.addProperty("configuration_json", VisualizationContracts.blank().toString());
      template = run.call("template_update", update, "definition");
      ref.add("template_ref", template.get("template_ref")); var exported = run.call("template_export", ref, "export");
      var imported = mutation(); imported.addProperty("store_id", store.description().id()); imported.add("envelope_json", exported.get("envelope_json")); run.call("template_import", imported, "definition");
      var viewArgs = mutation(); viewArgs.add("source_ref", source); viewArgs.add("template_ref", template.get("template_ref"));
      var view = run.call("view_create", viewArgs, "view"); var get = args(); get.add("view_id", view.get("view_id")); run.call("view_get", get, "view"); run.call("view_list", args(), "workspace");
      var edit = mutation(); edit.add("view_id", view.get("view_id")); edit.addProperty("expected_revision", 1); edit.addProperty("configuration_json", VisualizationContracts.blank().toString());
      view = run.call("view_update", edit, "view");
      var clone = mutation(); clone.add("view_id", view.get("view_id")); clone.add("expected_revision", view.get("revision")); var copied = run.call("view_clone", clone, "view");
      var save = mutation(); save.add("view_id", view.get("view_id")); save.add("expected_revision", view.get("revision")); save.addProperty("store_id", store.description().id()); save.addProperty("title", "Conformance view"); view = run.call("view_save", save, "view");
      var discard = mutation(); discard.add("view_id", view.get("view_id")); discard.add("expected_revision", view.get("revision")); run.call("view_discard", discard, "view");
      var delete = mutation(); delete.add("view_id", copied.get("view_id")); delete.add("expected_revision", copied.get("revision")); run.call("view_delete", delete, "view");
      var recordedViewArgs = mutation(); recordedViewArgs.add("source_ref", recording.reference);
      var recordingConfig = VisualizationContracts.blank();
      recordingConfig.getAsJsonArray("requirements").add(JsonParser.parseString("""
          {"id":"signal","selector_kind":"entry","entry":"/signal/00000","accepted_entry_types":["int64"],
           "field_path":null,"value_type":"number","unit":null}
          """));
      recordedViewArgs.addProperty("configuration_json", recordingConfig.toString());
      var recordedView = run.call("view_create", recordedViewArgs, "view"); var bindingArgs = args();
      bindingArgs.add("view_id", recordedView.get("view_id")); bindingArgs.add("expected_revision", recordedView.get("revision"));
      run.call("view_bind", bindingArgs, "binding");
      recordedViewArgs.addProperty("request_id", UUID.randomUUID().toString()); recordedViewArgs.addProperty("configuration_json", VisualizationContracts.blank().toString());
      recordedView = run.call("view_create", recordedViewArgs, "view"); bindingArgs.add("view_id", recordedView.get("view_id"));
      run.call("view_bind", bindingArgs, "binding", "no_match");
      var migration = args(); migration.addProperty("api_version", 2); migration.addProperty("kind", "view"); migration.add("legacy_ref", view.get("saved_view_ref"));
      var migrated = run.call("library_migrate", migration, "definition");
      if (!migrated.equals(run.call("library_migrate", migration, "definition"))) throw new AssertionError("Migration retry must return its original pinned definition");
      var opening = mutation(); opening.addProperty("api_version", 2); opening.add("saved_view_ref", migrated.get("saved_view_ref"));
      var opened = run.call("saved_view_open", opening, "view");
      if (!opened.get("configuration").equals(view.get("configuration"))) throw new AssertionError("Library open preserves the migrated configuration");
      var libraryList = args(); libraryList.addProperty("api_version", 2); libraryList.addProperty("kind", "view"); libraryList.addProperty("limit", 1);
      var definitions = run.call("library_list", libraryList, "catalog");
      if (definitions.get("total_matching").getAsInt() != 1 || !definitions.getAsJsonArray("items").get(0).getAsJsonObject().get("saved_view_ref").equals(migrated.get("saved_view_ref")))
        throw new AssertionError("Library listing names the exact migrated identity");
      libraryList.addProperty("query", "not-a-saved-title"); run.call("library_list", libraryList, "catalog", "no_match");
      migration.addProperty("kind", "template"); migration.add("legacy_ref", template.get("template_ref")); run.call("library_migrate", migration, "definition");
      var libraryTemplates = args(); libraryTemplates.addProperty("api_version", 2); libraryTemplates.addProperty("limit", 1);
      run.call("template_list", libraryTemplates, "catalog");
      var expected = run.registry.getToolNames().stream().filter(n -> n.startsWith("visualization_")).collect(java.util.stream.Collectors.toSet());
      var exercised = run.calls.stream().map(ToolConformanceTest.Call::tool).collect(java.util.stream.Collectors.toSet());
      if (!expected.equals(exercised)) throw new AssertionError("Every visualization operation needs an isolated success case: " + expected + " vs " + exercised);
      return List.copyOf(run.calls);
    } finally { run.registry.closeVisualizations(); }
  }
}
