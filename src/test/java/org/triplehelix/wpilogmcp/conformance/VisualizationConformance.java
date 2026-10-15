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
      var inspect = args(); inspect.addProperty("path", fixture.toString()); var source = run.call("source_inspect", inspect, "source").getAsJsonObject("source_ref");
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
      var expected = run.registry.getToolNames().stream().filter(n -> n.startsWith("visualization_")).collect(java.util.stream.Collectors.toSet());
      var exercised = run.calls.stream().map(ToolConformanceTest.Call::tool).collect(java.util.stream.Collectors.toSet());
      if (!expected.equals(exercised)) throw new AssertionError("Every visualization operation needs an isolated success case: " + expected + " vs " + exercised);
      return List.copyOf(run.calls);
    } finally { run.registry.closeVisualizations(); }
  }
}
