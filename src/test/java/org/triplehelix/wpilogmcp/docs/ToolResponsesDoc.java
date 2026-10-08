/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    titles.put("live", "Live Tools");
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
    var registry = new ToolRegistry();
    WpilogTools.registerAll(registry);
    org.triplehelix.wpilogmcp.tools.LiveTools.registerAll(registry, null);
    registry.getToolNames().stream().sorted().forEach(name -> byName.put(name, registry.getTool(name)));
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

    var scenarios = scenarios();
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

  /** The scenarios file: the logs it reads and the calls captured into TOOL_RESPONSES.md. */
  static JsonObject scenarios() throws Exception {
    try (var in = ToolResponsesDoc.class.getResourceAsStream("/tool-responses/scenarios.json")) {
      return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
          .getAsJsonObject();
    }
  }

  /**
   * What is wrong with a doc's tool sections, given the server's. A section must be one of the
   * server's, in the server's order, and hold only tools the server puts there. A doc that must
   * be {@code complete} also holds every tool.
   *
   * <p>TOOLS.md is written by hand and must be complete. TOOL_RESPONSES.md is generated from
   * Team 2363's logs, which someone adding a tool does not have: it may lack that tool until it
   * is next generated, but it may not misplace a tool or hold one the server does not have.
   */
  static List<String> sectionProblems(Map<String, Set<String>> expected,
      Map<String, Set<String>> actual, boolean complete) {
    var problems = new ArrayList<String>();
    var known = actual.keySet().stream().filter(expected::containsKey).toList();
    actual.keySet().stream().filter(title -> !expected.containsKey(title))
        .forEach(title -> problems.add("section '" + title + "' is not one of the server's"));
    var serverOrder = expected.keySet().stream().filter(actual::containsKey).toList();
    if (!known.equals(serverOrder)) {
      problems.add("sections are in the order " + known + "; the server's is " + serverOrder);
    }
    for (var title : known) {
      for (var tool : actual.get(title)) {
        if (expected.get(title).contains(tool)) continue;
        var belongs = expected.entrySet().stream().filter(e -> e.getValue().contains(tool))
            .map(Map.Entry::getKey).findFirst();
        problems.add(tool + " is under '" + title + "'" + belongs
            .map(where -> "; the server puts it under '" + where + "'")
            .orElse("; the server has no such tool"));
      }
    }
    if (complete) {
      expected.forEach((title, tools) -> tools.stream()
          .filter(tool -> !actual.getOrDefault(title, Set.of()).contains(tool))
          .forEach(tool -> problems.add(tool + " is missing from '" + title + "'")));
    }
    return problems;
  }

  static Map<String, Set<String>> serverSections() throws Exception {
    var expected = new LinkedHashMap<String, Set<String>>();
    categories(registeredTools())
        .forEach((title, tools) -> expected.put(title, new TreeSet<>(names(tools))));
    return expected;
  }

  @Test
  @DisplayName("TOOLS.md holds every tool in the server's section; TOOL_RESPONSES.md misplaces "
      + "none")
  void docsUseTheServersSections() throws Exception {
    var expected = serverSections();
    assertEquals(List.of(), sectionProblems(expected, docSections(Path.of("doc", "TOOLS.md")),
        true), "TOOLS.md");
    assertEquals(List.of(), sectionProblems(expected,
        docSections(Path.of("doc", "TOOL_RESPONSES.md")), false), "TOOL_RESPONSES.md");
  }

  @Test
  @DisplayName("a tool added without the logs: TOOL_RESPONSES.md may lack it, TOOLS.md may not")
  void aNewToolNeedsNoLogs() throws Exception {
    // What a contributor's branch looks like: the server has a tool the generated doc does not
    // hold yet. This used to fail the build, and the doc cannot be generated without the logs.
    var doc = serverSections();
    var server = new LinkedHashMap<String, Set<String>>();
    doc.forEach((title, tools) -> server.put(title, new TreeSet<>(tools)));
    server.get("Statistics Tools").add("a_new_tool");
    assertNotEquals(server, doc, "the old check compared the two for equality");
    assertEquals(List.of(), sectionProblems(server, doc, false));
    assertEquals(List.of("a_new_tool is missing from 'Statistics Tools'"),
        sectionProblems(server, doc, true), "the hand-written doc must have it");

    // What still fails, generated doc or not
    var misplaced = new LinkedHashMap<String, Set<String>>();
    doc.forEach((title, tools) -> misplaced.put(title, new TreeSet<>(tools)));
    misplaced.get("Statistics Tools").remove("get_statistics");
    misplaced.get("Core Tools").add("get_statistics");
    assertEquals(List.of("get_statistics is under 'Core Tools'; the server puts it under "
        + "'Statistics Tools'"), sectionProblems(doc, misplaced, false));

    var unknown = new LinkedHashMap<String, Set<String>>();
    doc.forEach((title, tools) -> unknown.put(title, new TreeSet<>(tools)));
    unknown.get("Core Tools").add("removed_tool");
    unknown.put("Other Tools", new TreeSet<>(Set.of("x")));
    assertEquals(List.of("section 'Other Tools' is not one of the server's",
        "removed_tool is under 'Core Tools'; the server has no such tool"),
        sectionProblems(doc, unknown, false));

    var reordered = new LinkedHashMap<String, Set<String>>();
    var titles = new ArrayList<>(doc.keySet());
    java.util.Collections.swap(titles, 0, 1);
    titles.forEach(title -> reordered.put(title, doc.get(title)));
    assertEquals(1, sectionProblems(doc, reordered, false).size());
  }

  /** What is wrong with the scenarios file, given the server's tools. */
  static List<String> scenarioProblems(Set<String> toolNames, JsonObject scenarios) {
    var problems = new ArrayList<String>();
    var called = new HashSet<String>();
    int index = 0;
    for (var element : scenarios.getAsJsonArray("calls")) {
      index++;
      var call = element.getAsJsonObject();
      var tool = call.has("tool") ? call.get("tool").getAsString() : null;
      if (tool == null || !toolNames.contains(tool)) {
        problems.add("call " + index + " names " + tool + ", which is not a tool");
        continue;
      }
      called.add(tool);
      if (!call.has("title") || call.get("title").getAsString().isBlank()) {
        problems.add("call " + index + " (" + tool + ") has no title");
      }
      if (!call.has("args") || !call.get("args").isJsonObject()) {
        problems.add("call " + index + " (" + tool + ") has no args object");
      }
    }
    toolNames.stream().filter(name -> !called.contains(name)).sorted()
        .forEach(name -> problems.add(name + " has no call"));
    return problems;
  }

  @Test
  @DisplayName("the scenarios file has a call for every tool, so the next generation captures it")
  void everyToolHasAScenario() throws Exception {
    var toolNames = registeredTools().keySet();
    assertEquals(List.of(), scenarioProblems(toolNames, scenarios()));

    // A tool added without a call is the one thing a contributor must not forget
    var withNew = new TreeSet<>(toolNames);
    withNew.add("a_new_tool");
    assertEquals(List.of("a_new_tool has no call"), scenarioProblems(withNew, scenarios()));
    var stale = JsonParser.parseString(
        "{\"calls\": [{\"tool\": \"removed_tool\", \"title\": \"x\", \"args\": {}},"
            + " {\"tool\": \"get_types\", \"args\": []}]}").getAsJsonObject();
    assertEquals(List.of("call 1 names removed_tool, which is not a tool",
        "call 2 (get_types) has no title", "call 2 (get_types) has no args object"),
        scenarioProblems(Set.of("get_types"), stale));
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
        .append("The logs: Team 2363 at VACHE 2026 (qualification 10), a ")
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
