/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import com.google.gson.JsonElement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.Tool;

/**
 * Review issue G4: a tool description must not promise an output that never appears. Every
 * snake_case term a description names — other than the tool's parameters, tool names, and a few
 * words that are values rather than keys — must be a key in at least one result the tool
 * returned on the fixture corpus.
 */
final class DescriptionOutputs {

  private DescriptionOutputs() {}

  private static final Pattern TERM = Pattern.compile("\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b");

  /** Terms that are statuses or operators rather than result keys. */
  static final Set<String> NOT_KEYS = Set.of("no_match", "not_applicable", "abs_lt", "abs_lte",
      "abs_gt", "abs_gte");

  /**
   * Outputs present only in situations the fixture sweep's arguments do not create, each checked
   * by the named test instead.
   */
  static final Map<String, String> VERIFIED_ELSEWHERE = Map.ofEntries(
      Map.entry("detect_anomalies | spike_interval_sec", "ScopeToolsTest.spikeCadence"),
      Map.entry("get_entry_info | decode_problem", "StructToolsFixtureTest.entryInfoMystery"),
      Map.entry("get_statistics | records_in_window", "FieldPathToolsTest.wildcardPools"),
      Map.entry("list_available_logs | has_more", "CoreToolsLogicTest.paging (needs a log directory)"),
      Map.entry("list_available_logs | log_count", "CoreToolsLogicTest.paging (needs a log directory)"),
      Map.entry("list_revlog_signals | sync_confidence", "RevLogToolsTest (needs a synced revlog)"),
      Map.entry("profile_mechanism | other_stems", "NoGuessRolesTest.explicitEntriesWithSeveralStems"),
      Map.entry("profile_mechanism | needs_confirmation", "MechanismFixtureTest.stems"),
      Map.entry("get_tba_match_data | lookup_method", "TbaReplayTest.found (no TBA in the corpus)"),
      Map.entry("get_tba_match_data | match_key", "TbaReplayTest.found"),
      Map.entry("get_tba_match_data | double_elimination_bracket",
          "TbaReplayTest.eliminationBracket"),
      Map.entry("get_tba_match_data | play_order", "TbaReplayTest.enrichmentPlayOrderBefore2023"),
      Map.entry("get_tba_status | key_check", "TbaReplayTest.statusChecksTheKey"),
      Map.entry("list_available_logs | match_key", "TbaReplayTest.enrichmentNearestTime"),
      Map.entry("list_available_logs | lookup_method", "TbaReplayTest.enrichmentNearestTime"),
      Map.entry("list_available_logs | nearest_time", "TbaReplayTest.enrichmentNearestTime"),
      Map.entry("search_strings | repeat_count", "QueryToolsLogicTest (collapse_repeats)"),
      Map.entry("get_match_phases | mode_change", "MatchTimelineTest (a mode change while enabled)"));

  /**
   * "tool | term" for every promised term that no result of the tool contains as a key or a
   * short string value. A term may also be any tool's parameter, or an output of another tool the
   * description names ("get_entry_info lists numeric_leaf_paths").
   */
  static Set<String> missing(List<Tool> tools, List<Map.Entry<String, JsonElement>> results) {
    var toolNames = new HashSet<String>();
    var allParams = new HashSet<String>();
    for (var t : tools) {
      toolNames.add(t.name());
      var props = t.inputSchema().getAsJsonObject("properties");
      if (props != null) allParams.addAll(props.keySet());
    }
    var namesByTool = new HashMap<String, Set<String>>();
    for (var r : results) {
      collectKeys(r.getValue(), namesByTool.computeIfAbsent(r.getKey(), k -> new HashSet<>()));
    }
    var missing = new TreeSet<String>();
    for (var tool : tools) {
      var names = namesByTool.getOrDefault(tool.name(), Set.of());
      var description = tool.description();
      var m = TERM.matcher(description);
      while (m.find()) {
        var term = m.group();
        if (allParams.contains(term) || toolNames.contains(term) || NOT_KEYS.contains(term)
            || names.contains(term) || VERIFIED_ELSEWHERE.containsKey(tool.name() + " | " + term)) {
          continue;
        }
        boolean otherToolsOutput = toolNames.stream()
            .filter(other -> !other.equals(tool.name()) && description.contains(other))
            .anyMatch(other -> namesByTool.getOrDefault(other, Set.of()).contains(term));
        if (!otherToolsOutput) missing.add(tool.name() + " | " + term);
      }
    }
    return missing;
  }

  /** Every key, and every short string value (a basis, a column name), anywhere in e. */
  static void collectKeys(JsonElement e, Set<String> keys) {
    if (e == null || e.isJsonNull()) return;
    if (e.isJsonPrimitive()) {
      if (e.getAsJsonPrimitive().isString() && e.getAsString().length() <= 64) {
        keys.add(e.getAsString());
      }
      return;
    }
    if (e.isJsonArray()) {
      e.getAsJsonArray().forEach(item -> collectKeys(item, keys));
      return;
    }
    for (var entry : e.getAsJsonObject().entrySet()) {
      keys.add(entry.getKey());
      collectKeys(entry.getValue(), keys);
    }
  }
}
