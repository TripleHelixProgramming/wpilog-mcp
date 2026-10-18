/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.*;
import org.triplehelix.wpilogmcp.log.*;
import org.triplehelix.wpilogmcp.read.*;
import org.triplehelix.wpilogmcp.read.internal.ReadWire;
import org.triplehelix.wpilogmcp.tools.VisualizationBindings;

/** Histograms and scatter use one bounded, freshly admitted source prefix. Both axes are read
 * from it before pairing; independent calls cannot accidentally align different source versions.
 * Numerical rules belong to the same owner as render_chart, not to the reader or a webview. */
final class SourceCharts {
  private final RecordingBindings bindings;
  private final RecordingAnalysis.Limits limits;
  private final LongSupplier clock;
  private final Semaphore slots = new Semaphore(2);
  SourceCharts(RecordedSources sources) { this(new RecordingBindings(sources::admitChart), RecordingAnalysis.Limits.defaults(), System::nanoTime); }
  SourceCharts(RecordingBindings bindings, RecordingAnalysis.Limits limits, LongSupplier clock) { this.bindings = bindings; this.limits = limits; this.clock = clock; }
  static JsonObject capabilities() {
    var result = RecordingAnalysis.capabilities(); result.remove("max_statistics_samples");
    result.addProperty("max_samples_across_selected_entries", RecordingAnalysis.Limits.defaults().samples());
    result.addProperty("max_scatter_page_points", 4096); result.addProperty("default_scatter_page_points", 256);
    result.addProperty("max_histogram_bins", 64); result.addProperty("min_live_window_sec", 1); result.addProperty("max_live_window_sec", 300);
    return result;
  }
  JsonObject read(JsonObject request, boolean scatter, BooleanSupplier alive) throws Exception {
    var keys = new HashSet<>(List.of("api_version", "source_ref", "start_time", "end_time", "last_seconds"));
    keys.addAll(scatter ? List.of("x_entry", "x_field", "y_entry", "y_field", "offset", "limit") : List.of("entry", "field"));
    for (var key : request.keySet()) if (!keys.contains(key)) throw new IllegalArgumentException("Unknown chart argument " + key);
    if (request.has("api_version") && !request.get("api_version").equals(new JsonPrimitive(1))) throw new IllegalArgumentException("Unsupported api_version");
    var source = VisualizationContracts.source(request.getAsJsonObject("source_ref"));
    var selections = scatter ? List.of(selection(request, "x_entry", "x_field"), selection(request, "y_entry", "y_field")) : List.of(selection(request, "entry", "field"));
    int offset = scatter && request.has("offset") ? ReadWire.integer(request, "offset") : 0;
    int limit = scatter && request.has("limit") ? ReadWire.integer(request, "limit") : 256;
    if (offset < 0 || limit < 1 || limit > 4096) throw new IllegalArgumentException("Scatter offset must be nonnegative and limit must be 1..4096");
    for (var key : List.of("start_time", "end_time")) if (request.has(key)) micros(request, key);
    if (!slots.tryAcquire()) throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT, "Two source chart analyses are in progress; retry after one finishes", true);
    var budget = new RecordingAnalysis.Budget(limits, clock, alive);
    try {
      budget.check();
      try (var bound = bindings.admitExact(source, selections)) {
        budget.check();
        for (var b : bound.result().getAsJsonArray("bindings")) if (!b.getAsJsonObject().get("state").getAsString().equals("resolved"))
          throw new IOException("SOURCE_UNAVAILABLE: chart declarations or schemas disagree: " + b.getAsJsonObject().get("problems"));
        var prefix = bound.admission().prefix(); var summary = RecordingInventory.summary(prefix);
        var members = prefix.members().stream().sorted(Comparator.comparing(org.triplehelix.wpilogmcp.read.server.OrdinalFollow.Member::id)).toList();
        var histories = new LinkedHashMap<String, LogData>(); var problems = new RecordingAnalysis.Problems();
        var range = new JsonObject();
        for (var key : List.of("start_time", "end_time", "last_seconds")) if (request.has(key)) range.add(key, request.get(key));
        var first = members.stream().map(m -> m.snapshot()).filter(s -> s.entries().containsKey(selections.get(0).entry())).findFirst().orElseThrow();
        range = VisualizationBindings.statisticsArguments(new RecordingAnalysis.History(prefix.recordingId(), first.entries().get(selections.get(0).entry()), first.schemas(), List.of(), summary, problems), range);
        if (!prefix.finalized()) {
          if (!range.has("start_time") || !range.has("end_time")) throw new IllegalArgumentException("Active recording charts require an explicit 1–300 second interval or last_seconds");
          double duration = request.has("last_seconds") ? request.get("last_seconds").getAsDouble()
              : range.get("end_time").getAsDouble() - range.get("start_time").getAsDouble();
          if (!Double.isFinite(duration) || duration < 1 || duration > 300) throw new IllegalArgumentException("Active recording charts require a 1–300 second interval; choose a shorter window");
        }
        var windows = new ArrayList<ReadQuery.Window>();
        if (range.has("start_time") || range.has("end_time")) windows.add(new ReadQuery.Window(range.has("start_time") ? micros(range, "start_time") : Long.MIN_VALUE,
            range.has("end_time") ? micros(range, "end_time") : Long.MAX_VALUE, true));
        for (var selection : selections) {
          String entry = selection.entry(); if (histories.containsKey(entry)) continue;
          var samples = new ArrayList<Sample>(); var entryProblems = new RecordingAnalysis.Problems(); ReadSnapshot representative = null;
          for (var member : members) {
            budget.check(); var snapshot = member.snapshot(); if (!snapshot.entries().containsKey(entry)) continue;
            if (representative == null) representative = snapshot;
            var query = new ReadQuery(entry, windows, ReadQuery.Order.FILE, 256, limits.recordBytes()).withRecordLimit(limits.recordBytes(), ReadQuery.LimitAction.ERROR);
            try (var cursor = snapshot.read(query)) {
              while (true) {
                budget.check(); var batch = cursor.next(); budget.batch(batch); problems.accept(member.id(), batch.problems()); entryProblems.accept(member.id(), batch.problems());
                for (var sample : batch.samples()) {
                  // The shared numeric tools use binary64 seconds. Refuse where that conversion
                  // loses a microsecond rather than calling two different native times equal.
                  if (sample.timestampUs() < -9_007_199_254_000_000L || sample.timestampUs() > 9_007_199_254_000_000L
                      || Math.round(sample.timestampUs() / 1e6 * 1e6) != sample.timestampUs()) throw new IllegalArgumentException("Chart timestamps cannot retain exact microseconds in numeric seconds; use native ordinal reads");
                  budget.retain(sample); samples.add(sample);
                }
                if (batch.complete()) break;
              }
            }
          }
          samples.sort(Comparator.comparingLong(Sample::timestampUs));
          var values = samples.stream().map(s -> new TimestampedValue(s.timestampUs() / 1e6, s.value())).toList();
          histories.put(entry, new RecordingAnalysis.History(prefix.recordingId(), representative.entries().get(entry), representative.schemas(), values, summary, entryProblems));
        }
        budget.check();
        var selectedLogs = selections.stream().map(s -> histories.get(s.entry())).toList();
        var numeric = selections.stream().map(s -> new VisualizationBindings.ChartSelection(s.entry(), s.field())).toList();
        var result = VisualizationBindings.chart(selectedLogs, numeric, range, scatter, offset, limit);
        problems.annotate(result);
        if (problems.count > 0 && result.get("status").getAsString().equals("ok")) {
          result.addProperty("status", "partial"); var skipped = result.has("skipped") ? result.getAsJsonArray("skipped") : new JsonArray();
          var skippedRecords = new JsonObject(); skippedRecords.addProperty("section", "undecodable_records"); skippedRecords.addProperty("reason", problems.count + " selected records failed; inspect member-qualified problems"); skipped.add(skippedRecords); result.add("skipped", skipped);
        }
        var proof = new JsonObject(); boolean recording = source.get("kind").getAsString().equals("session");
        proof.addProperty("source_kind", recording ? "session" : "file"); proof.addProperty("finalized", prefix.finalized());
        if (recording) { proof.addProperty("recording_id", prefix.recordingId()); proof.addProperty("catalog_revision", Long.toString(prefix.catalogRevision())); }
        proof.add("members", summary.get("members")); proof.add("clock", summary.get("clock"));
        result.add("prefix", proof); result.addProperty("basis", "admitted_source_prefix"); result.addProperty("records_visited", budget.visited);
        var inputs = result.getAsJsonObject("inputs"); inputs.add("source_ref", source);
        for (var key : scatter ? List.of("x_entry", "x_field", "y_entry", "y_field") : List.of("entry", "field")) if (request.has(key)) inputs.add(key, request.get(key));
        if (request.has("last_seconds")) inputs.add("last_seconds", request.get("last_seconds"));
        budget.check(); bound.admission().check();
        try { ReadWire.bytes(result, limits.resultBytes()); }
        catch (ReadException | ReadWire.WireLimit tooLarge) { throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT, "Chart result exceeds its 1 MiB allowance; reduce scatter limit or select shorter entry names"); }
        budget.check(); return result;
      }
    } finally { slots.release(); }
  }
  private static RecordingBindings.Selection selection(JsonObject request, String name, String field) {
    String entry = ReadWire.text(request, name), path = request.has(field) ? ReadWire.text(request, field) : null;
    if (path != null && (path.isBlank() || FieldPath.parse(path).hasWildcard())) throw new IllegalArgumentException("Charts require a scalar numeric field or array index, not a blank path or wildcard");
    return new RecordingBindings.Selection(entry, path);
  }
  private static long micros(JsonObject request, String key) {
    var value = request.get(key);
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + " must be a finite number of seconds");
    double seconds = value.getAsDouble();
    if (!Double.isFinite(seconds) || Math.abs(seconds) > 9_007_199_254.0) throw new IllegalArgumentException(key + " exceeds the exact microsecond range");
    return Math.round(seconds * 1e6);
  }
}
