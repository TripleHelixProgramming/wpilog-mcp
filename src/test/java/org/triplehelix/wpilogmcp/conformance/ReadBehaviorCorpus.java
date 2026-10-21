/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs.Fixture;
import org.triplehelix.wpilogmcp.fixtures.SystemSessionFixture;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.store.StoreJson;
import org.triplehelix.wpilogmcp.store.StoreManifest;
import org.triplehelix.wpilogmcp.store.SystemLogState;
import org.triplehelix.wpilogmcp.tools.LiveTools;
import org.triplehelix.wpilogmcp.tools.WpilogTools;

/** Freezes generated inputs and requests before comparing different server builds. */
public final class ReadBehaviorCorpus {
  private static final Set<String> GLOBAL_READS = Set.of("list_available_logs", "get_server_guide",
      "suggest_tools", "get_game_info", "get_tba_status", "list_sessions", "get_latest_values", "wait_for_change");
  private static final Map<String, String> EXCLUDED = excluded();
  private static Map<String, String> excluded() {
    var excluded = new java.util.LinkedHashMap<String, String>(Map.of(
      "list_source_scopes", "Machine-local source configuration added after the frozen baseline; SourceScopesTest and SourceSelectionTest pin selection and paging.",
      "health_check", "Runtime heap/cache counters are checked separately, not equal across processes.",
      "list_loaded_logs", "Runtime cache residency depends on heap pressure; lifecycle checks cover it separately.",
      "set_revlog_offset", "Changes shared alignment state; this frozen corpus compares read-only requests.",
      "get_tba_match_data", "External service results are outside the read-layer equivalence boundary."));
    // These definitions and machine-local source IDs did not exist in the frozen reader baseline.
    // Their successful state transitions are exercised through VisualizationConformance.
    for (String tool : List.of(
        "visualization_library_list", "visualization_library_migrate", "visualization_saved_view_open",
        "visualization_capabilities_get", "visualization_source_inspect", "visualization_source_health",
        "visualization_source_entries_list", "visualization_source_entries_get", "visualization_source_inventory_close", "visualization_source_value", "visualization_source_statistics", "visualization_source_reduction", "visualization_source_histogram", "visualization_source_scatter", "visualization_template_list",
        "visualization_template_get", "visualization_template_check", "visualization_template_create",
        "visualization_template_update", "visualization_template_import", "visualization_template_export",
        "visualization_view_list", "visualization_view_get", "visualization_view_bind", "visualization_view_create",
        "visualization_view_update", "visualization_view_clone", "visualization_view_save",
        "visualization_view_discard", "visualization_view_delete"))
      excluded.put(tool, "Machine-local visualization state; isolated success/receipt conformance covers it, not frozen reader equivalence.");
    excluded.put("compare_sessions", "Added after the frozen reader baseline and reads only catalogued stores; CompareSessionsToolTest and CompareSessionsLimitsTest pin its answers and refusals.");
    return Map.copyOf(excluded);
  }
  private ReadBehaviorCorpus() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Expected a new corpus directory");
    create(Path.of(args[0]));
  }

  static void create(Path root) throws Exception {
    root = root.toAbsolutePath().normalize();
    // Never refresh a baseline implicitly. A new baseline needs a new directory and review.
    Files.createDirectory(root);
    var files = Files.createDirectory(root.resolve("files"));
    var exports = Files.createDirectory(root.resolve("exports"));
    var fixtures = new ArrayList<>(FixtureLogs.generateAll(files));
    fixtures.add(diagnosticFixture(files));
    fixtures.add(systemFixture(files));
    var registry = new ToolRegistry();
    WpilogTools.registerAll(registry);
    LiveTools.registerAll(registry, null);
    var manager = LogManager.getInstance();
    var savedDirectories = LogDirectory.getInstance().getLogDirectories();
    var manifest = new JsonObject();
    manifest.addProperty("format_version", 1);
    var cases = new JsonArray();
    var excluded = new JsonObject();
    EXCLUDED.forEach(excluded::addProperty);
    try {
      manager.addAllowedDirectory(files);
      var tools = registry.getToolNames().stream().sorted().map(registry::getTool).toList();
      for (var tool : tools) {
        if (EXCLUDED.containsKey(tool.name()) || ToolArguments.takesPath(tool)) continue;
        if (!GLOBAL_READS.contains(tool.name())) throw new IllegalStateException("Classify tool for parity: " + tool.name());
        for (var variant : ToolArguments.variants(tool, null, null, fixtures, exports)) {
          cases.add(mcp("global/" + tool.name() + "/" + variant.label(), tool.name(), variant.args()));
        }
      }
      for (var fixture : fixtures) {
        var log = manager.getOrLoad(fixture.path().toString());
        if (!manager.waitForRevLogSync(log.path(), 30_000)) throw new IllegalStateException("Fixture sync timed out: " + fixture.id());
        var waitArgs = new JsonObject();
        waitArgs.addProperty("path", log.path()); waitArgs.addProperty("timeout_ms", 30_000);
        var barrier = mcp(fixture.id() + "/sync-ready", "wait_for_sync", waitArgs);
        var before = new JsonArray(); before.add(mcp("prepare-sync", "wait_for_sync", waitArgs.deepCopy()));
        barrier.add("before", before); cases.add(barrier);
        for (var tool : tools) {
          if (!ToolArguments.takesPath(tool) || EXCLUDED.containsKey(tool.name())) continue;
          for (var variant : ToolArguments.variants(tool, fixture, log, fixtures, exports)) {
            cases.add(mcp(fixture.id() + "/" + tool.name() + "/" + variant.label(), tool.name(), variant.args()));
          }
        }
        for (var kind : ToolArguments.Kind.values()) {
          var name = ToolArguments.pick(log, kind);
          if (name.isEmpty()) continue;
          for (String format : List.of("arrow", "csv")) {
            cases.add(http(fixture.id() + "/" + format + "/" + kind.label(), log.path(), name.get(), format));
            var values = log.values().get(name.get());
            if (values != null && !values.isEmpty()) {
              var window = http(fixture.id() + "/" + format + "/" + kind.label() + "/window", log.path(), name.get(), format);
              parameter(window, "start_time", Double.toString(values.get(values.size() / 4).timestamp()));
              parameter(window, "end_time", Double.toString(values.get(3 * values.size() / 4).timestamp())); cases.add(window);
            }
            if (kind == ToolArguments.Kind.NUMERIC) {
              var buckets = http(fixture.id() + "/" + format + "/buckets", log.path(), name.get(), format);
              parameter(buckets, "max_points", "5"); cases.add(buckets);
            }
          }
          if (kind == ToolArguments.Kind.STRUCT || kind == ToolArguments.Kind.STRUCT_ARRAY) {
            String type = log.entries().get(name.get()).type().substring("struct:".length()).replace("[]", "");
            var leaves = log.structSchemas().numericLeafPaths(type);
            if (!leaves.isEmpty()) {
              String projection = name.get() + (kind == ToolArguments.Kind.STRUCT_ARRAY ? "[*]." : ".") + leaves.get(0);
              for (String format : List.of("arrow", "csv")) cases.add(http(fixture.id() + "/" + format + "/" + kind.label() + "/field", log.path(), projection, format));
            }
          }
        }
        var numericNames = log.entries().values().stream().filter(e -> e.type().equals("double"))
            .map(e -> e.name()).sorted().limit(2).toList();
        if (numericNames.size() == 2) for (String format : List.of("arrow", "csv")) {
          var multiple = http(fixture.id() + "/" + format + "/multiple", log.path(), numericNames.get(0), format);
          parameter(multiple, "names", numericNames.get(1)); cases.add(multiple);
        }
        var aligned = manager.getSynchronizedLogs(log.path());
        if (aligned != null) {
          var signal = aligned.getAllEntries().keySet().stream().filter(n -> !log.entries().containsKey(n)).sorted().findFirst();
          if (signal.isPresent()) {
            var args = new JsonObject(); args.addProperty("path", log.path()); args.addProperty("signal_key", signal.get());
            cases.add(mcp(fixture.id() + "/rev-values", "get_revlog_data", args));
            for (String format : List.of("arrow", "csv")) cases.add(http(fixture.id() + "/rev/" + format, log.path(), signal.get(), format));
          }
        }
        manager.unloadLog(log.path());
      }
      String path = files.resolve("2026-decode-outside-window.wpilog").toString();
      var args = new JsonObject(); args.addProperty("path", path); args.addProperty("name", "/Value");
      args.addProperty("start_time", 1); args.addProperty("end_time", 1);
      cases.add(mcp("decode-outside-window/mcp", "read_entry", args));
      for (String format : List.of("arrow", "csv")) {
        var c = http("decode-outside-window/" + format, path, "/Value", format);
        parameter(c, "start_time", "1"); parameter(c, "end_time", "1"); cases.add(c);
      }
      var refused = http("http/origin-refused", path, "/Value", "arrow");
      var headers = new JsonObject(); headers.addProperty("Origin", "https://untrusted.example");
      refused.add("headers", headers); cases.add(refused);
      cases.add(http("http/path-refused", root.resolve("outside.wpilog").toString(), "/Value", "arrow"));
      cases.add(http("http/missing-entry", path, "/Missing", "arrow"));
      var empty = http("http/empty-window", path, "/Value", "arrow");
      parameter(empty, "start_time", "100"); parameter(empty, "end_time", "101"); cases.add(empty);
      var badWindow = http("http/reversed-window", path, "/Value", "arrow");
      parameter(badWindow, "start_time", "3"); parameter(badWindow, "end_time", "1"); cases.add(badWindow);
      manifest.add("cases", cases);
      manifest.add("excluded_tools", excluded);
      manifest.addProperty("fixture_count", fixtures.size());
      var inputs = new JsonArray();
      try (var paths = Files.walk(files)) {
        for (var file : paths.filter(Files::isRegularFile).sorted().toList()) {
          Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
          var input = new JsonObject();
          input.addProperty("path", root.relativize(file).toString().replace('\\', '/'));
          input.addProperty("size_bytes", Files.size(file));
          input.addProperty("modified_ms", Files.getLastModifiedTime(file).toMillis());
          input.addProperty("sha256", hash(file)); inputs.add(input);
        }
      }
      manifest.add("inputs", inputs);
      Files.writeString(root.resolve("manifest.json"), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(manifest), StandardCharsets.UTF_8);
      System.out.println("Frozen " + fixtures.size() + " fixtures, " + inputs.size() + " files and " + cases.size() + " requests in " + root);
    } finally {
      manager.unloadAllLogs();
      LogDirectory.getInstance().setLogDirectories(savedDirectories.stream().map(Path::toString).toList());
    }
  }

  private static Fixture diagnosticFixture(Path files) throws Exception {
    var file = files.resolve("2026-decode-outside-window.wpilog");
    try (var writer = new WpilogWriter(file, "synthetic decode scope, ordering and exact integers")) {
      int value = writer.start("/Value", "double", "{\"source\":\"synthetic\"}", 0);
      writer.append(value, 1_000_000, WpilogWriter.encodeDouble(7));
      writer.append(value, 2_000_000, new byte[] {42});
      int counter = writer.start("/Counter", "int64", "", 0);
      writer.append(counter, 3_000_000, WpilogWriter.encodeInt64(9007199254740993L));
      writer.append(counter, 1_000_000, WpilogWriter.encodeInt64(9007199254740992L));
      writer.append(counter, 1_000_000, WpilogWriter.encodeInt64(-1));
    }
    return new Fixture("decode-outside-window", file, "Legacy diagnostics include malformed records outside the requested window", List.of());
  }

  private static Fixture systemFixture(Path files) throws Exception {
    var capture = SystemSessionFixture.create(files.resolve("system-store"), "frozen", false, 42);
    var sessionPath = capture.getParent().resolve("session.json");
    var session = StoreJson.JSON.fromJson(Files.readString(sessionPath), StoreManifest.Session.class);
    var text = capture.getParent().resolve("robot/system/dmesg.txt");
    Files.createDirectories(text.getParent());
    Files.writeString(text, "[105.0] warning synthetic\n[110.0] error synthetic\nunknown clock\n", StandardCharsets.UTF_8);
    var receipt = new SystemLogState.File(SystemLogState.Location.SESSION, "robot/system/dmesg.txt", "kernel", "dmesg",
        hash(text), Files.size(text), new StoreManifest.Provenance("pulled", "/synthetic/dmesg", null,
        SystemSessionFixture.WALL.instant().toString(), false), null);
    SystemSessionFixture.write(sessionPath, session.withSystemLogs(SystemLogState.EMPTY.withFiles(List.of(receipt))));
    return new Fixture("system-session", capture, "Frozen manifest and kernel clock pairing", List.of());
  }

  private static String hash(Path file) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    try (var in = Files.newInputStream(file)) {
      var bytes = new byte[65536]; int count;
      while ((count = in.read(bytes)) != -1) digest.update(bytes, 0, count);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static JsonObject mcp(String id, String tool, JsonObject args) {
    var c = new JsonObject(); c.addProperty("id", id); c.addProperty("kind", "mcp");
    c.addProperty("tool", tool); c.add("arguments", args); return c;
  }

  private static JsonObject http(String id, String path, String name, String format) {
    var c = new JsonObject(); c.addProperty("id", id); c.addProperty("kind", "http");
    c.addProperty("path", "/data/entries"); c.add("query", new JsonArray());
    parameter(c, "path", path); parameter(c, "names", name); parameter(c, "format", format); return c;
  }

  private static void parameter(JsonObject c, String key, String value) {
    var pair = new JsonArray(); pair.add(key); pair.add(value); c.getAsJsonArray("query").add(pair);
  }
}
