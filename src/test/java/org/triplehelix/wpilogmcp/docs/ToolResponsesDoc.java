/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.docs;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.Version;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.Tool;
import org.triplehelix.wpilogmcp.tools.CoreTools;
import org.triplehelix.wpilogmcp.tools.DiscoveryTools;
import org.triplehelix.wpilogmcp.tools.ExportTools;
import org.triplehelix.wpilogmcp.tools.FrcDomainTools;
import org.triplehelix.wpilogmcp.tools.QueryTools;
import org.triplehelix.wpilogmcp.tools.RevLogTools;
import org.triplehelix.wpilogmcp.tools.RobotAnalysisTools;
import org.triplehelix.wpilogmcp.tools.StatisticsTools;
import org.triplehelix.wpilogmcp.tools.TbaTools;

/**
 * Regenerates {@code doc/TOOL_RESPONSES.md} by running the calls in
 * {@code src/test/resources/tool-responses/scenarios.json} against real logs:
 *
 * <pre>
 * ./gradlew test --tests '*ToolResponsesDoc*' -PtoolResponsesLogDir=/path/to/riologs
 * </pre>
 *
 * <p>Skipped unless the log directory is given. Every registered tool must have a call.
 */
@DisplayName("TOOL_RESPONSES.md capture")
class ToolResponsesDoc {

  static final int KEEP_ITEMS = 3;
  static final int MAX_ITEMS = 5;
  static final int MAX_STRING = 400;

  record Category(String title, Consumer<ToolRegistry> register) {}

  static final List<Category> CATEGORIES = List.of(
      new Category("Discovery Tools", DiscoveryTools::registerAll),
      new Category("Core Tools", CoreTools::registerAll),
      new Category("Query Tools", QueryTools::registerAll),
      new Category("Statistics Tools", StatisticsTools::registerAll),
      new Category("Robot Analysis Tools", RobotAnalysisTools::registerAll),
      new Category("FRC Domain Tools", FrcDomainTools::registerAll),
      new Category("Export Tools", ExportTools::registerAll),
      new Category("TBA Tools", TbaTools::registerAll),
      new Category("RevLog Tools", RevLogTools::registerAll));

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
      var toolsByCategory = new LinkedHashMap<String, List<Tool>>();
      var toolsByName = new LinkedHashMap<String, Tool>();
      for (var category : CATEGORIES) {
        var list = new ArrayList<Tool>();
        category.register().accept(new ToolRegistry() {
          @Override
          public void registerTool(Tool tool) {
            list.add(tool);
            toolsByName.put(tool.name(), tool);
          }
        });
        toolsByCategory.put(category.title(), list);
      }

      // Run every call in order; ${first_revlog_signal} comes from list_revlog_signals
      var captures = new LinkedHashMap<String, List<String[]>>();
      String firstRevlogSignal = "unknown";
      for (var element : scenarios.getAsJsonArray("calls")) {
        var call = element.getAsJsonObject();
        var toolName = call.get("tool").getAsString();
        var tool = toolsByName.get(toolName);
        assertTrue(tool != null, "scenario for unknown tool " + toolName);
        var args = substitute(call.getAsJsonObject("args"), aliases, firstRevlogSignal)
            .getAsJsonObject();
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
        .append("** returns, captured from real logs by `ToolResponsesDoc` running the calls in ")
        .append("`src/test/resources/tool-responses/scenarios.json`. To regenerate:\n\n")
        .append("```\n./gradlew test --tests '*ToolResponsesDoc*' ")
        .append("-PtoolResponsesLogDir=/path/to/riologs\n```\n\n")
        .append("The logs: Team 2363 at VACHE 2026 (qualification 10, with its REV log), two ")
        .append("practice-session logs, and an AdvantageKit replay (`_sim`) log. Responses are ")
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
