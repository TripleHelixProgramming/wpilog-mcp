/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.Version;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.Tool;
import org.triplehelix.wpilogmcp.tools.ExportTools;
import org.triplehelix.wpilogmcp.tools.WpilogTools;

/**
 * Regenerates {@code doc/TOOL_RESPONSES.md} by running the calls in
 * {@code src/test/resources/tool-responses/scenarios.json} against real logs:
 *
 * <pre>
 * ./gradlew test --tests '*.docs.*' -PtoolResponsesLogDir=/path/to/riologs
 * </pre>
 *
 * <p>Skipped unless the log directory is given. Every registered tool must have a call.
 */
@DisplayName("TOOL_RESPONSES.md capture")
class ToolResponsesDoc {

  static final int KEEP_ITEMS = 3;
  static final int MAX_ITEMS = 5;
  static final int MAX_STRING = 400;

  /** The section title for each of the server's categories, in TOOLS.md's order. */
  static final Map<String, String> TITLES = titles();

  private static Map<String, String> titles() {
    var titles = new LinkedHashMap<String, String>();
    titles.put("discovery", "Discovery Tools");
    titles.put("core", "Core Tools");
    titles.put("query", "Query Tools");
    titles.put("statistics", "Statistics Tools");
    titles.put("robot_analysis", "Robot Analysis Tools");
    titles.put("frc_domain", "FRC Domain Tools");
    titles.put("export", "Export Tools");
    titles.put("tba", "TBA Tools");
    titles.put("revlog", "RevLog Tools");
    return titles;
  }

  /** Every tool the server registers, by name. */
  static Map<String, Tool> registeredTools() {
    var byName = new LinkedHashMap<String, Tool>();
    WpilogTools.registerAll(new ToolRegistry() {
      @Override
      public void registerTool(Tool tool) {
        byName.put(tool.name(), tool);
      }
    });
    return byName;
  }

  /**
   * The tools grouped as the server groups them for agents (get_server_guide's categories),
   * titled and ordered as in TOOLS.md, each section in the catalog's order.
   */
  static Map<String, List<Tool>> categories(Map<String, Tool> toolsByName) throws Exception {
    var guide = toolsByName.get("get_server_guide").execute(new JsonObject()).getAsJsonObject();
    var byId = new LinkedHashMap<String, List<Tool>>();
    for (var element : guide.getAsJsonArray("categories")) {
      var category = element.getAsJsonObject();
      var id = category.get("name").getAsString();
      assertTrue(TITLES.containsKey(id), "no section title for the server's category " + id);
      var list = new ArrayList<Tool>();
      for (var t : category.getAsJsonArray("tools")) {
        var name = t.getAsJsonObject().get("name").getAsString();
        assertTrue(toolsByName.containsKey(name), "cataloged but not registered: " + name);
        list.add(toolsByName.get(name));
      }
      byId.put(id, list);
    }
    var sections = new LinkedHashMap<String, List<Tool>>();
    TITLES.forEach((id, title) -> {
      if (byId.containsKey(id)) sections.put(title, byId.get(id));
    });
    return sections;
  }

  @Test
  @DisplayName("capture every scenario into doc/TOOL_RESPONSES.md")
  void regenerate(@TempDir Path exportDir) throws Exception {
    var dirProperty = System.getProperty("tool.responses.logdir");
    Assumptions.assumeTrue(dirProperty != null && !dirProperty.isBlank(),
        "tool.responses.logdir not set; run with -PtoolResponsesLogDir=/path/to/riologs");
    var logDir = Path.of(dirProperty).toAbsolutePath().normalize();

    JsonObject scenarios;
    try (var in = ToolResponsesDoc.class.getResourceAsStream("/tool-responses/scenarios.json")) {
      scenarios = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
          .getAsJsonObject();
    }
    var aliases = new LinkedHashMap<String, String>();
    for (var e : scenarios.getAsJsonObject("logs").entrySet()) {
      var file = logDir.resolve(e.getValue().getAsString());
      Assumptions.assumeTrue(Files.exists(file), "log not found: " + file);
      aliases.put(e.getKey(), file.toString());
    }

    var savedExport = ExportTools.getExportDirectory();
    ExportTools.setExportDirectory(exportDir.toString());
    LogManager.getInstance().addAllowedDirectory(logDir);
    LogDirectory.getInstance().setLogDirectory(logDir.toString());
    try {
      var toolsByName = registeredTools();
      var toolsByCategory = categories(toolsByName);

      // Run every call in order; ${first_revlog_signal} comes from list_revlog_signals
      var captures = new LinkedHashMap<String, List<String[]>>();
      String firstRevlogSignal = "unknown";
      var revlogTools = toolsByCategory.get("RevLog Tools").stream().map(Tool::name)
          .collect(java.util.stream.Collectors.toSet());
      for (var element : scenarios.getAsJsonArray("calls")) {
        var call = element.getAsJsonObject();
        var toolName = call.get("tool").getAsString();
        var tool = toolsByName.get(toolName);
        assertTrue(tool != null, "scenario for unknown tool " + toolName);
        var args = substitute(call.getAsJsonObject("args"), aliases, firstRevlogSignal)
            .getAsJsonObject();
        if (revlogTools.contains(toolName) && args.has("path")) {
          // Capture the synchronized state: a reload (after eviction) restarts the sync, and
          // the doc runs with an empty disk cache, so it takes real time
          var wait = new JsonObject();
          wait.add("path", args.get("path"));
          wait.addProperty("timeout_ms", 120_000);
          toolsByName.get("wait_for_sync").execute(wait);
        }
        var result = tool.execute(args);
        if (toolName.equals("list_revlog_signals")) {
          firstRevlogSignal = firstSignalKey(result).orElse(firstRevlogSignal);
        }
        var request = new JsonObject();
        request.addProperty("name", toolName);
        request.add("arguments", args);
        captures.computeIfAbsent(toolName, k -> new ArrayList<>()).add(new String[] {
            call.get("title").getAsString(), pretty(request), pretty(shorten(result))});
      }
      for (var name : toolsByName.keySet()) {
        assertTrue(captures.containsKey(name), "no scenario for tool " + name);
      }

      var markdown = render(toolsByCategory, captures)
          .replace(exportDir.toRealPath().toString(), "<exportdir>")
          .replace(exportDir.toString(), "<exportdir>")
          .replace(logDir.toString(), "<logdir>")
          .replace(System.getProperty("user.home"), "~");
      Files.writeString(Path.of("doc", "TOOL_RESPONSES.md"), markdown, StandardCharsets.UTF_8);
    } finally {
      ExportTools.setExportDirectory(savedExport.toString());
      LogManager.getInstance().unloadAllLogs();
    }
  }

  static List<String> names(List<Tool> tools) {
    return tools.stream().map(Tool::name).toList();
  }

  @Test
  @DisplayName("sections are the server's categories: every tool once, under its own category")
  void sectionsAreTheServersCategories() throws Exception {
    var byName = registeredTools();
    var sections = categories(byName);
    var listed = sections.values().stream().flatMap(List::stream).map(Tool::name).toList();
    assertEquals(new TreeSet<>(byName.keySet()), new TreeSet<>(listed), "every tool");
    assertEquals(listed.size(), new HashSet<>(listed).size(), "each tool once");
    assertEquals(new ArrayList<>(TITLES.values()), new ArrayList<>(sections.keySet()));
    // The server puts these here; their Java classes (PoseTools, CanBusAnalysis) do not decide
    assertTrue(names(sections.get("FRC Domain Tools"))
        .containsAll(List.of("compare_poses", "pose_corrections")));
    assertTrue(names(sections.get("Robot Analysis Tools")).contains("analyze_can_bus"));
  }

  /** The "## ... Tools" sections of a doc and the "### `tool`" headings under each. */
  static Map<String, Set<String>> docSections(Path doc) throws Exception {
    var out = new LinkedHashMap<String, Set<String>>();
    Set<String> current = null;
    var toolHeading = Pattern.compile("^### `([a-z_0-9]+)`$");
    for (var line : Files.readAllLines(doc)) {
      if (line.startsWith("## ")) {
        var title = line.substring(3).strip();
        current = title.endsWith(" Tools") ? out.computeIfAbsent(title, k -> new TreeSet<>())
            : null;
      } else if (current != null) {
        var m = toolHeading.matcher(line);
        if (m.matches()) current.add(m.group(1));
      }
    }
    return out;
  }

  @Test
  @DisplayName("TOOLS.md and TOOL_RESPONSES.md put each tool in the server's section")
  void docsUseTheServersSections() throws Exception {
    var expected = new LinkedHashMap<String, Set<String>>();
    categories(registeredTools())
        .forEach((title, tools) -> expected.put(title, new TreeSet<>(names(tools))));
    for (var doc : List.of("TOOLS.md", "TOOL_RESPONSES.md")) {
      var actual = docSections(Path.of("doc", doc));
      assertEquals(expected, actual, doc);
      assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(actual.keySet()),
          doc + ": section order");
    }
  }

  static java.util.Optional<String> firstSignalKey(JsonElement result) {
    if (!result.isJsonObject() || !result.getAsJsonObject().has("signals")) {
      return java.util.Optional.empty();
    }
    for (var s : result.getAsJsonObject().getAsJsonArray("signals")) {
      if (s.isJsonObject() && s.getAsJsonObject().has("key")) {
        return java.util.Optional.of(s.getAsJsonObject().get("key").getAsString());
      }
      if (s.isJsonPrimitive()) return java.util.Optional.of(s.getAsString());
    }
    return java.util.Optional.empty();
  }

  /** Replaces ${alias} (and ${first_revlog_signal}) in string values. */
  static JsonElement substitute(JsonElement e, Map<String, String> aliases, String signal) {
    if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
      var text = e.getAsString().replace("${first_revlog_signal}", signal);
      for (var a : aliases.entrySet()) text = text.replace("${" + a.getKey() + "}", a.getValue());
      return new JsonPrimitive(text);
    }
    if (e.isJsonArray()) {
      var out = new JsonArray();
      e.getAsJsonArray().forEach(item -> out.add(substitute(item, aliases, signal)));
      return out;
    }
    if (e.isJsonObject()) {
      var out = new JsonObject();
      e.getAsJsonObject().entrySet()
          .forEach(entry -> out.add(entry.getKey(), substitute(entry.getValue(), aliases, signal)));
      return out;
    }
    return e;
  }

  /** Long arrays keep their first items and say how many were cut; long strings likewise. */
  static JsonElement shorten(JsonElement e) {
    if (e == null || e.isJsonNull()) return e;
    if (e.isJsonPrimitive()) {
      if (e.getAsJsonPrimitive().isString() && e.getAsString().length() > MAX_STRING) {
        var s = e.getAsString();
        return new JsonPrimitive(s.substring(0, MAX_STRING) + "... (" + (s.length() - MAX_STRING)
            + " more characters)");
      }
      return e;
    }
    if (e.isJsonArray()) {
      var array = e.getAsJsonArray();
      var out = new JsonArray();
      int keep = array.size() > MAX_ITEMS ? KEEP_ITEMS : array.size();
      for (int i = 0; i < keep; i++) out.add(shorten(array.get(i)));
      if (array.size() > keep) out.add("... (" + (array.size() - keep) + " more items)");
      return out;
    }
    var out = new JsonObject();
    for (var entry : e.getAsJsonObject().entrySet()) {
      out.add(entry.getKey(), shorten(entry.getValue()));
    }
    return out;
  }

  static String pretty(JsonElement e) {
    return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()
        .toJson(e);
  }

  static String render(Map<String, List<Tool>> categories, Map<String, List<String[]>> captures) {
    var md = new StringBuilder();
    md.append("# wpilog-mcp Tool Response Reference\n\n");
    md.append("The JSON every tool of **wpilog-mcp ").append(Version.VERSION)
        .append("** returns, captured from real logs by running the calls in ")
        .append("`src/test/resources/tool-responses/scenarios.json`. To regenerate (see ")
        .append("[DEVELOPMENT.md](DEVELOPMENT.md#changing-or-adding-a-tool)):\n\n")
        .append("```\n./gradlew test --tests '*.docs.*' ")
        .append("-PtoolResponsesLogDir=/path/to/riologs\n```\n\n")
        .append("The logs: Team 2363 at VACHE 2026 (qualification 10, with its REV log), a ")
        .append("practice session (the robustness review's log), and an AdvantageKit replay ")
        .append("(`_sim`) log. Responses are ")
        .append("verbatim except that arrays longer than ").append(MAX_ITEMS)
        .append(" items keep their first ").append(KEEP_ITEMS)
        .append(" and end with `\"... (N more items)\"`, strings longer than ").append(MAX_STRING)
        .append(" characters are cut the same way, and paths are shown as `<logdir>`, ")
        .append("`<exportdir>`, and `~`. What each field means is in [TOOLS.md](TOOLS.md); this ")
        .append("file shows what the fields look like on real data.\n\n");
    md.append("## Transport envelope\n\n")
        .append("Each result travels as the text of an MCP `tools/call` result ")
        .append("(`result.content[0].text`, a JSON string to parse); the server adds ")
        .append("`_execution_time_ms` before serializing (not shown below). Every result follows ")
        .append("the [result contract](TOOLS.md#result-contract-success-status-and-related-fields)")
        .append(": `success` and `status` first, with `reason`, `looked_for`, `hint`, `inputs`, ")
        .append("`skipped`, `limits`, and `warnings` where they apply.\n\n");
    md.append("## Table of contents\n\n");
    for (var category : categories.entrySet()) {
      md.append("- [").append(category.getKey()).append("](#")
          .append(category.getKey().toLowerCase().replace(' ', '-')).append(")\n");
      for (var tool : category.getValue()) {
        md.append("  - [`").append(tool.name()).append("`](#").append(tool.name()).append(")\n");
      }
    }
    md.append('\n');
    for (var category : categories.entrySet()) {
      md.append("## ").append(category.getKey()).append("\n\n");
      for (var tool : category.getValue()) {
        md.append("### `").append(tool.name()).append("`\n\n");
        md.append(tool.description().strip().replace("\n", "\n\n")).append("\n\n");
        md.append("**Parameters** ([TOOLS.md](TOOLS.md#").append(tool.name()).append("))\n\n");
        var schema = tool.inputSchema();
        var props = schema.getAsJsonObject("properties");
        var required = new java.util.HashSet<String>();
        if (schema.has("required")) {
          schema.getAsJsonArray("required").forEach(r -> required.add(r.getAsString()));
        }
        if (props == null || props.size() == 0) {
          md.append("None.\n\n");
        } else {
          md.append("| Parameter | Type | Required | Description |\n|---|---|---|---|\n");
          for (var p : props.entrySet()) {
            var o = p.getValue().getAsJsonObject();
            md.append("| `").append(p.getKey()).append("` | ")
                .append(o.has("type") ? o.get("type").getAsString() : "")
                .append(" | ").append(required.contains(p.getKey()) ? "yes" : "no").append(" | ")
                .append(o.has("description") ? o.get("description").getAsString()
                    .replace("|", "\\|").replace("\n", " ") : "")
                .append(" |\n");
          }
          md.append('\n');
        }
        for (var capture : captures.getOrDefault(tool.name(), List.of())) {
          md.append("**Example: ").append(capture[0]).append("**\n\n");
          md.append("Request:\n```json\n").append(capture[1]).append("\n```\n\n");
          md.append("Response:\n```json\n").append(capture[2]).append("\n```\n\n");
        }
      }
    }
    return md.toString();
  }
}
