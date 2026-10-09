/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

/**
 * Tests for the general reasoning guidance shipped to client LLMs.
 */
class AnalysisGuidanceTest {

  /**
   * Snake-case identifiers that appear in the guidance but are tool parameters or response
   * fields, not tools. Anything else in snake_case must be a registered tool name.
   */
  private static final Set<String> KNOWN_NON_TOOL_IDENTIFIERS =
      Set.of(
          "confidence_level",
          "quality_score",
          "sample_count",
          "gap_count",
          "data_quality",
          "deep_dive",
          "start_time",
          "end_time",
          "time_range_sec",
          "voltage_analysis",
          "channel_analysis",
          "rio_brownout_flag_logged",
          "voltage_threshold",
          "rio_flag",
          "brownout_voltage_entry",
          "brownout_threshold",
          "brownout_threshold_basis",
          "voltage_entry",
          "effective_sample_size",
          "pose_entry",
          "chooser_entry",
          "needs_confirmation",
          "samples_below_threshold",
          "channel_limit",
          "current_entries_analyzed",
          "text_event_counts",
          "text_event_summary",
          "total_matches",
          "has_more",
          "entry_pattern",
          "collapse_repeats",
          "max_value_chars",
          "value_truncated",
          // Result contract fields and statuses (ResultContract)
          "no_match",
          "not_applicable",
          "looked_for",
          "numeric_leaf_paths",
          "change_only");

  private static final Pattern SNAKE_CASE = Pattern.compile("\\b[a-z]+(?:_[a-z0-9]+)+\\b");

  @Test void instructionsKeepTheFourConcreteCautions() {
    for (String phrase : List.of("whole-log numbers mix in disabled time and boot", "absent data is not absent problems",
        "even when the user names a cause", "one log is one sample")) {
      assertTrue(AnalysisGuidance.SERVER_INSTRUCTIONS.contains(phrase), phrase);
    }
  }

  @Test
  @DisplayName("server instructions fit within Claude Code's 2 KB truncation limit")
  void instructionsFitWithinClientLimit() {
    var escaped = new Gson().toJson(AnalysisGuidance.SERVER_INSTRUCTIONS);
    int bytes = escaped.getBytes(StandardCharsets.UTF_8).length;
    assertTrue(
        bytes <= AnalysisGuidance.INSTRUCTIONS_BYTE_LIMIT,
        "JSON-escaped instructions are " + bytes + " bytes; limit is "
            + AnalysisGuidance.INSTRUCTIONS_BYTE_LIMIT);
    // "2 KB" may be counted in characters by some clients; stay under 2000 either way.
    assertTrue(
        AnalysisGuidance.SERVER_INSTRUCTIONS.length() <= 2000,
        "Instructions are " + AnalysisGuidance.SERVER_INSTRUCTIONS.length() + " characters");
  }

  @Test void completeInstructionsLocateFreshDataBeforeTheAnalysisRulesAndFitTheLimit() {
    for (String location : List.of(ToolRegistry.LOCAL_LOCATION, ToolRegistry.PIT_LOCATION)) {
      String text = AnalysisGuidance.forLocation(location);
      assertTrue(text.startsWith(location + "\n" + AnalysisGuidance.FRESH_DATA_INSTRUCTIONS + "\n"));
      assertTrue(new Gson().toJson(text).getBytes(StandardCharsets.UTF_8).length <= AnalysisGuidance.INSTRUCTIONS_BYTE_LIMIT, text);
      for (String tool : List.of("list_sessions", "get_latest_values", "wait_for_change", "list_available_logs")) assertTrue(text.contains(tool));
      assertTrue(text.contains("recorded past")); assertTrue(text.contains("marks open"));
    }
  }

  @Test
  @DisplayName("server instructions are plain ASCII")
  void instructionsAreAscii() {
    var text = AnalysisGuidance.SERVER_INSTRUCTIONS;
    for (int i = 0; i < text.length(); i++) {
      assertTrue(text.charAt(i) < 128, "Non-ASCII character at index " + i);
    }
    assertFalse(text.isBlank());
    assertFalse(text.endsWith("\n"), "Trailing newline wastes budget");
  }

  @Test
  @DisplayName("server instructions lead with the answer-first and no-invented-data rules")
  void instructionsLeadWithCoreRules() {
    var text = AnalysisGuidance.SERVER_INSTRUCTIONS;
    assertTrue(text.startsWith("wpilog-mcp"));
    int answerFirst = text.indexOf("Answer the question asked first");
    int noInvention = text.indexOf("Never name an entry");
    int tiers = text.indexOf("Three tiers");
    assertTrue(answerFirst >= 0 && answerFirst < noInvention && noInvention < tiers);
    // The most important rules must survive a client that truncates early.
    assertTrue(tiers < 1200, "Tiered-language rule starts at " + tiers);
  }

  @Test
  @DisplayName("analysis principles have the expected sections")
  void principlesHaveExpectedSections() {
    var principles = AnalysisGuidance.analysisPrinciples();
    for (var key :
        List.of(
            "purpose",
            "answer_first",
            "method",
            "calibration",
            "user_proposed_cause",
            "traps",
            "cross_match",
            "naming",
            "units",
            "report_format")) {
      assertTrue(principles.has(key), "Missing section: " + key);
    }

    var method = principles.getAsJsonObject("method");
    assertTrue(method.has("applies_to"));
    assertTrue(method.getAsJsonArray("steps").size() >= 5);

    var calibration = principles.getAsJsonObject("calibration");
    assertTrue(calibration.has("discrete_events"));
    assertTrue(calibration.has("do_not_hedge"));
    assertTrue(calibration.getAsJsonObject("cause_confidence").has("high"));

    var traps = principles.getAsJsonArray("traps");
    assertTrue(traps.size() >= 15);
    for (var trap : traps) {
      var obj = trap.getAsJsonObject();
      assertTrue(obj.has("trap") && obj.has("fix"), "Trap entries need trap + fix: " + obj);
      assertFalse(obj.get("fix").getAsString().isBlank());
    }

    var report = principles.getAsJsonObject("report_format");
    assertTrue(report.has("pit") && report.has("deep_dive") && report.has("example"));
  }

  @Test
  @DisplayName("analysis principles are returned as an independent deep copy")
  void principlesAreCopied() {
    var pristine = AnalysisGuidance.analysisPrinciples();
    int trapCount = pristine.getAsJsonArray("traps").size();
    var appliesTo = pristine.getAsJsonObject("method").get("applies_to").getAsString();

    var first = AnalysisGuidance.analysisPrinciples();
    first.remove("purpose");
    first.getAsJsonArray("traps").remove(0);
    first.getAsJsonObject("method").addProperty("applies_to", "mutated");
    first.getAsJsonObject("calibration").getAsJsonArray("never_high_when").add("mutated");

    var second = AnalysisGuidance.analysisPrinciples();
    assertTrue(second.has("purpose"));
    assertEquals(trapCount, second.getAsJsonArray("traps").size());
    assertEquals(appliesTo, second.getAsJsonObject("method").get("applies_to").getAsString());
    assertEquals(pristine, second);
  }

  @Test
  @DisplayName("analysis principles contain no nulls, empty arrays, or blank strings")
  void principlesAreWellFormed() {
    assertWellFormed(AnalysisGuidance.analysisPrinciples(), "$");
  }

  private static void assertWellFormed(JsonElement element, String path) {
    assertFalse(element.isJsonNull(), "null at " + path + " (stray comma?)");
    if (element.isJsonPrimitive()) {
      var primitive = element.getAsJsonPrimitive();
      assertTrue(primitive.isString(), "non-string primitive at " + path);
      assertFalse(primitive.getAsString().isBlank(), "blank string at " + path);
      assertTrue(primitive.getAsString().chars().allMatch(c -> c < 128), "non-ASCII at " + path);
    } else if (element.isJsonArray()) {
      var array = element.getAsJsonArray();
      assertTrue(array.size() > 0, "empty array at " + path);
      for (int i = 0; i < array.size(); i++) {
        assertWellFormed(array.get(i), path + "[" + i + "]");
      }
    } else {
      var object = element.getAsJsonObject();
      assertTrue(object.size() > 0, "empty object at " + path);
      object.entrySet().forEach(e -> assertWellFormed(e.getValue(), path + "." + e.getKey()));
    }
  }

  @Test
  @DisplayName("every tool name mentioned in the guidance is a registered tool")
  void referencedToolsExist() {
    var registry = new ToolRegistry();
    WpilogTools.registerAll(registry);
    var toolNames = new HashSet<>(registry.getToolNames());

    var identifiers = new TreeSet<String>();
    collectIdentifiers(AnalysisGuidance.SERVER_INSTRUCTIONS, identifiers);
    collectIdentifiers(AnalysisGuidance.analysisPrinciples(), identifiers);

    var unknown = new TreeSet<String>();
    for (var id : identifiers) {
      if (!toolNames.contains(id) && !KNOWN_NON_TOOL_IDENTIFIERS.contains(id)) {
        unknown.add(id);
      }
    }
    assertTrue(unknown.isEmpty(), "Guidance references unknown identifiers: " + unknown);

    // Sanity: the guidance really does reference tools (guards against a broken regex).
    assertTrue(identifiers.contains("get_match_phases"));
    assertTrue(identifiers.contains("get_server_guide"));
  }

  @Test
  @DisplayName("what an entry measures comes from the robot source code, not from its name")
  void sourceCodeIsTheEvidenceForWhatAnEntryMeasures() throws Exception {
    // In the instructions, as its own rule right after the no-invented-data rule: it must
    // survive a client that truncates, and it comes before any reasoning about the values
    var text = AnalysisGuidance.SERVER_INSTRUCTIONS;
    int rule = text.indexOf("2. An entry's name does not prove what it measures");
    assertTrue(rule > 0, "the rule is missing: " + text);
    int code = text.indexOf("robot source code", rule);
    assertTrue(code > rule && code < text.indexOf("\n3. "), "the rule names the source code");
    var ruleText = text.substring(rule, text.indexOf("\n3. "));
    for (var part : List.of("mechanism", "units", "measured or commanded", "assumption")) {
      assertTrue(ruleText.contains(part), part + " missing from: " + ruleText);
    }

    // In the long form: where to look in the method, a trap with real examples, and the way a
    // candidate is confirmed
    var principles = AnalysisGuidance.analysisPrinciples();
    var observe = principles.getAsJsonObject("method").getAsJsonArray("steps").get(0)
        .getAsString();
    assertTrue(observe.contains("source code"), observe);
    var fix = trapFix(principles, "Taking an entry's name as proof of what it measures");
    assertNotNull(fix, "the trap is missing");
    for (var part : List.of("currentHeight", "targetYaw", "units", "assumption", "cite")) {
      assertTrue(fix.contains(part), part + " missing from: " + fix);
    }
    var noGuess = principles.getAsJsonObject("naming").get("the_server_does_not_guess")
        .getAsString();
    assertTrue(noGuess.contains("source code"), noGuess);
    assertTrue(noGuess.indexOf("source code") < noGuess.indexOf("get_entry_info"),
        "the code comes before the values as evidence: " + noGuess);
    assertTrue(noGuess.contains("profile_mechanism"), noGuess);

    // Where an agent meets an unresolved role, and in the guide's short list of rules
    var registry = new ToolRegistry();
    WpilogTools.registerAll(registry);
    var guide = registry.getTool("get_server_guide").execute(new com.google.gson.JsonObject())
        .getAsJsonObject();
    var tip = guide.getAsJsonObject("critical_guidance").get("source_code_tip").getAsString();
    assertTrue(tip.contains("source code") && tip.contains("assumption"), tip);
    assertTrue(SignalResolver.HOW_TO_CONFIRM.startsWith("the robot's source code"),
        SignalResolver.HOW_TO_CONFIRM);
    assertTrue(registry.getTool("resolve_signals").description().contains("source code"));
  }

  @Test
  @DisplayName("registering all tools installs the server instructions")
  void registerAllInstallsInstructions() {
    var registry = new ToolRegistry();
    assertNull(registry.getServerInstructions());
    WpilogTools.registerAll(registry);
    assertEquals(AnalysisGuidance.SERVER_INSTRUCTIONS, registry.getServerInstructions());
  }

  @Test
  @DisplayName("the whole-log statistics trap names exactly the tools that take scope and windows")
  void scopeAndWindowsClaimsMatchSchemas() {
    var registry = new ToolRegistry();
    WpilogTools.registerAll(registry);
    var fix = trapFix(AnalysisGuidance.analysisPrinciples(), "Whole-log statistics");
    assertNotNull(fix, "the whole-log statistics trap");

    // "Pass scope (...) to <tools>, which also take windows, ... <tools> take scope but not windows."
    // The list starts after the parenthesis, which names get_match_phases as a source of segments
    int listStart = fix.indexOf(") to ", fix.indexOf("Pass scope"));
    int windowsEnd = fix.indexOf("which also take windows");
    int scopeOnlyEnd = fix.indexOf("take scope but not windows");
    assertTrue(listStart > 0 && windowsEnd > listStart && scopeOnlyEnd > windowsEnd, fix);
    var takeWindows = new TreeSet<String>();
    collectIdentifiers(fix.substring(listStart, windowsEnd), takeWindows);
    var scopeOnly = new TreeSet<String>();
    collectIdentifiers(fix.substring(fix.lastIndexOf(". ", scopeOnlyEnd), scopeOnlyEnd), scopeOnly);
    takeWindows.retainAll(registry.getToolNames());
    scopeOnly.retainAll(registry.getToolNames());

    var expectWindows = new TreeSet<String>();
    var expectScopeOnly = new TreeSet<String>();
    for (var name : registry.getToolNames()) {
      var properties = registry.getTool(name).inputSchema().getAsJsonObject("properties");
      if (properties == null || !properties.has("scope")) continue;
      (properties.has("windows") ? expectWindows : expectScopeOnly).add(name);
    }
    assertEquals(expectWindows, takeWindows, "tools named as taking scope and windows");
    assertEquals(expectScopeOnly, scopeOnly, "tools named as taking scope but not windows");
  }

  /** The fix text of the trap with this name, anywhere in the principles. */
  private static String trapFix(JsonElement element, String trap) {
    if (element.isJsonObject()) {
      var object = element.getAsJsonObject();
      if (object.has("trap") && trap.equals(object.get("trap").getAsString())) {
        return object.get("fix").getAsString();
      }
      for (var e : object.entrySet()) {
        var found = trapFix(e.getValue(), trap);
        if (found != null) return found;
      }
    } else if (element.isJsonArray()) {
      for (var e : element.getAsJsonArray()) {
        var found = trapFix(e, trap);
        if (found != null) return found;
      }
    }
    return null;
  }

  /** Collects snake_case identifiers from every string value in a JSON tree (keys excluded). */
  private static void collectIdentifiers(JsonElement element, Set<String> out) {
    if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
      collectIdentifiers(element.getAsString(), out);
    } else if (element.isJsonArray()) {
      element.getAsJsonArray().forEach(e -> collectIdentifiers(e, out));
    } else if (element.isJsonObject()) {
      element.getAsJsonObject().entrySet().forEach(e -> collectIdentifiers(e.getValue(), out));
    }
  }

  private static void collectIdentifiers(String text, Set<String> out) {
    var matcher = SNAKE_CASE.matcher(text);
    while (matcher.find()) {
      out.add(matcher.group());
    }
  }
}
