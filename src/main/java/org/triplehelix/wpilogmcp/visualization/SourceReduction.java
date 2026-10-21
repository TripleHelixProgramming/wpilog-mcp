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
import org.triplehelix.wpilogmcp.read.*;
import org.triplehelix.wpilogmcp.read.catalog.SessionCursor;
import org.triplehelix.wpilogmcp.read.internal.ReadWire;
import org.triplehelix.wpilogmcp.tools.VisualizationBindings;

/** Drawing buckets over a freshly proven source, never over the client's retained tail.
 * Session merging and min/max/mean arithmetic belong to the native reader. This owner only
 * admits the prefix, budgets work and presents declared angles and explicit numeric gaps.
 * No decoded history is retained: only bounded merge lookahead and at most 4096 buckets. */
final class SourceReduction {
  record Limits(int records, int recordBytes, int resultBytes, long nanos) {
    static Limits defaults() { return new Limits(4_194_304, 64 << 10, 1 << 20, 10_000_000_000L); }
  }
  private final RecordingBindings bindings;
  private final Limits limits;
  private final LongSupplier clock;
  private final Semaphore slots = new Semaphore(2);
  SourceReduction(RecordedSources sources) { this(new RecordingBindings(sources::admitChart), Limits.defaults(), System::nanoTime); }
  SourceReduction(RecordingBindings bindings, Limits limits, LongSupplier clock) { this.bindings = bindings; this.limits = limits; this.clock = clock; }
  static JsonObject capabilities() {
    var l = Limits.defaults(); var out = new JsonObject(); out.addProperty("max_concurrent_reads", 2);
    out.addProperty("max_points", 4096); out.addProperty("default_max_points", 1024);
    out.addProperty("max_selected_records", l.records()); out.addProperty("max_record_payload_bytes", l.recordBytes());
    out.addProperty("max_merge_lookahead_payload_bytes", 8 << 20); out.addProperty("max_result_bytes", l.resultBytes());
    out.addProperty("max_request_bytes", 64 << 10); out.addProperty("max_problem_details", 100);
    out.addProperty("cooperative_work_budget_ms", l.nanos() / 1_000_000); return out;
  }
  JsonObject read(JsonObject request, BooleanSupplier alive) throws IOException {
    for (var key : request.keySet()) if (!Set.of("api_version", "source_ref", "entry", "field", "start_time", "end_time", "last_seconds", "max_points", "max_bytes").contains(key))
      throw new IllegalArgumentException("Unknown reduction argument " + key);
    if (request.has("api_version") && !Set.of(new JsonPrimitive(1), new JsonPrimitive(2)).contains(request.get("api_version"))) throw new IllegalArgumentException("Unsupported api_version");
    String entry = ReadWire.text(request, "entry"), field = request.has("field") ? ReadWire.text(request, "field") : null;
    if (field != null && (field.isBlank() || FieldPath.parse(field).hasWildcard())) throw new IllegalArgumentException("Reduction requires a scalar field or array index, not a wildcard");
    int points = request.has("max_points") ? ReadWire.integer(request, "max_points") : 1024;
    int maxBytes = request.has("max_bytes") ? ReadWire.integer(request, "max_bytes") : 1 << 20;
    if (points < 1 || points > 4096 || maxBytes < 4096 || maxBytes > 1 << 20) throw new IllegalArgumentException("Reduction requires max_points 1..4096 and max_bytes 4096..1048576");
    Long from = request.has("start_time") ? micros(request.get("start_time")) : null, to = request.has("end_time") ? micros(request.get("end_time")) : null;
    if (from != null && to != null && from > to) throw new IllegalArgumentException("Reduction end precedes start");
    var source = VisualizationContracts.source(request.getAsJsonObject("source_ref"));
    if (!slots.tryAcquire()) throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT, "Two source reductions are in progress; retry after one finishes", true);
    var budget = new RecordingAnalysis.Budget(new RecordingAnalysis.Limits(limits.records(), 0, 0, limits.recordBytes(), limits.resultBytes(), limits.nanos()), clock, alive);
    try {
      budget.check();
      try (var bound = bindings.admitExact(source, entry, field)) {
        budget.check();
        if (!bound.result().getAsJsonArray("bindings").get(0).getAsJsonObject().get("state").getAsString().equals("resolved"))
          throw new IOException("SOURCE_UNAVAILABLE: reduction declarations or schemas disagree: " + bound.result().get("bindings"));
        var prefix = bound.admission().prefix(); var summary = RecordingInventory.summary(prefix);
        // Admission already proved the present members agree. Metadata chooses the angle
        // rule without reading values or constructing another selected-entry history.
        var selected = prefix.members().stream().map(m -> m.snapshot()).filter(m -> m.entries().containsKey(entry)).findFirst().orElseThrow();
        double anglePeriod = VisualizationBindings.anglePeriod(
            new BindingEvidence(selected, new BindingEvidence.Budget(clock)), entry, field);
        if (request.has("last_seconds") && !request.get("last_seconds").isJsonNull()) {
          var history = new RecordingAnalysis.History(prefix.recordingId(), selected.entries().get(entry), selected.schemas(), List.of(), summary, new RecordingAnalysis.Problems());
          var range = org.triplehelix.wpilogmcp.tools.VisualizationBindings.statisticsArguments(history, request);
          from = micros(range.get("start_time")); to = micros(range.get("end_time"));
        }
        // The native time-order cache needs at most twelve bytes per selected record while
        // sorting, independently capped by its shared budget. Check before opening cursors.
        long records = prefix.members().stream().mapToLong(m -> m.snapshot().sampleCount(entry)).sum();
        if (records > limits.records()) throw limited("selected entry exceeds its record limit, including out-of-window records; read individual members");
        long start = from != null ? from : optionalTime(summary.get("start_time_sec"), to == null ? 0 : to);
        long end = to != null ? to : optionalTime(summary.get("end_time_sec"), from == null ? start : from);
        // A one-sided interval outside the source has no samples, not a reversed request.
        if (end < start) { if (from == null) start = end; else end = start; }
        var window = new ReadQuery.Window(start, end, true);
        var query = new ReadQuery(entry, List.of(window), ReadQuery.Order.TIME, 256, limits.recordBytes()).project(field).reduce(points);
        var problems = new RecordingAnalysis.Problems(); var cursors = new TreeMap<String, SampleCursor>();
        var buckets = new JsonArray(); long count = 0, finite = 0;
        try {
          for (var member : prefix.members()) if (member.snapshot().entries().containsKey(entry)) {
            budget.check();
            var raw = new ReadQuery(entry, List.of(window), ReadQuery.Order.TIME, 64, limits.recordBytes()).project(field)
                .withRecordLimit(limits.recordBytes(), ReadQuery.LimitAction.ERROR);
            var cursor = member.snapshot().read(raw);
            cursors.put(member.id(), new SampleCursor() {
              @Override public SampleBatch next() throws IOException {
                budget.check(); var batch = cursor.next(); budget.batch(batch); problems.accept(member.id(), batch.problems());
                var values = new ArrayList<Sample>();
                for (var sample : batch.samples()) {
                  var numeric = FieldPath.toPlotNumber(sample.value());
                  if (numeric == null && sample.value() != null) throw new IllegalArgumentException("Reduction requires scalar numeric or boolean values; select a numeric field");
                  // A valid array selection may be absent in this record. Keep its ordinal
                  // and count as an explicit gap rather than discarding the whole history.
                  values.add(new Sample(sample.timestampUs(), sample.recordOffset(), sample.ordinal(), numeric == null ? Double.NaN : numeric, sample.payloadBytes(), sample.source()));
                }
                return new SampleBatch(values, List.of(), batch.recordsVisited(), batch.payloadBytes(), batch.complete());
              }
              @Override public void close() throws IOException { cursor.close(); }
            });
          }
          try (var reduced = SampleCursors.reduce(presentAngles(SessionCursor.onCommonClock(cursors, query), anglePeriod), query, start, end)) {
            // The merged cursor now owns the children, including close on every refusal.
            cursors = new TreeMap<>();
            while (true) {
              budget.check(); var batch = reduced.next();
              for (var sample : batch.samples()) {
                @SuppressWarnings("unchecked") var values = (Map<String, Object>) sample.value();
                var bucket = new JsonObject();
                bucket.addProperty("start_us", values.get("start_us").toString()); bucket.addProperty("end_us", values.get("end_us").toString());
                for (var key : List.of("count", "finite_count", "min", "max", "mean", "first", "last")) {
                  var value = (Number) values.get(key);
                  if (value == null || !Double.isFinite(value.doubleValue())) bucket.add(key, JsonNull.INSTANCE); else bucket.addProperty(key, value);
                }
                count += bucket.get("count").getAsLong(); finite += bucket.get("finite_count").getAsLong(); buckets.add(bucket);
              }
              if (batch.complete()) break;
            }
          }
        } finally { for (var cursor : cursors.values()) cursor.close(); }
        var result = new JsonObject(); result.addProperty("status", count == 0 ? "no_match" : problems.count > 0 ? "partial" : "ok");
        if (count == 0) {
          result.addProperty("reason", "No decodable numeric samples in the requested interval"); var looked = new JsonArray(); looked.add(entry); result.add("looked_for", looked);
          result.addProperty("hint", "Widen the interval, inspect problems or wait for samples in the same recording");
        }
        if (problems.count > 0) { var skipped = new JsonArray(); var item = new JsonObject(); item.addProperty("section", "undecodable_records"); item.addProperty("reason", problems.count + " records could not be decoded or projected"); skipped.add(item); result.add("skipped", skipped); }
        result.add("buckets", buckets); result.addProperty("sample_count", count); result.addProperty("finite_count", finite);
        result.addProperty("unplottable_count", count - finite); result.addProperty("max_points", points);
        result.addProperty("reduction_rule", "time_buckets_min_max_mean"); result.addProperty("basis", "admitted_source_prefix");
        var interval = new JsonObject(); interval.addProperty("start_us", Long.toString(start)); interval.addProperty("end_us", Long.toString(end)); result.add("window", interval);
        var proof = summary.deepCopy(); proof.addProperty("source_kind", VisualizationContracts.kind(source).equals("session") ? "session" : "file");
        if (VisualizationContracts.kind(source).equals("session")) proof.addProperty("catalog_revision", Long.toString(prefix.catalogRevision()));
        result.add("prefix", proof); result.addProperty("records_visited", budget.visited); problems.annotate(result);
        var inputs = request.deepCopy(); inputs.remove("api_version"); inputs.add("window", interval.deepCopy()); result.add("inputs", inputs);
        budget.check(); bound.admission().check();
        try { ReadWire.bytes(result, Math.min(maxBytes, limits.resultBytes())); }
        catch (ReadException | ReadWire.WireLimit tooLarge) { throw limited("result exceeds its byte allowance; request fewer buckets or a larger max_bytes allowance"); }
        budget.check(); return result;
      }
    } finally { slots.release(); }
  }
  /**
   * One trajectory belongs to the requested window after all members have been merged.
   * Resetting at a batch or member boundary turns an ordinary wrap into a false excursion.
   * Native reduction remains arithmetic; this bounded adapter owns only presentation.
   */
  private static SampleCursor presentAngles(SampleCursor source, double period) {
    if (period == 0) return source;
    var angles = new AngleUnwrapper(period);
    return new SampleCursor() {
      @Override public SampleBatch next() throws IOException {
        var batch = source.next();
        var values = new ArrayList<Sample>(batch.samples().size());
        for (var sample : batch.samples()) {
          double value = angles.apply(((Number) sample.value()).doubleValue());
          values.add(new Sample(sample.timestampUs(), sample.recordOffset(), sample.ordinal(),
              value, sample.payloadBytes(), sample.source()));
        }
        return new SampleBatch(values, batch.problems(), batch.recordsVisited(),
            batch.payloadBytes(), batch.complete());
      }
      @Override public void close() throws IOException { source.close(); }
    };
  }
  private static ReadException limited(String reason) { return new ReadException(ReadProblem.Code.RESOURCE_LIMIT, "Source reduction " + reason); }
  private static long optionalTime(JsonElement value, long fallback) { return value == null || value.isJsonNull() ? fallback : micros(value); }
  private static long micros(JsonElement value) {
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble()) || Math.abs(value.getAsDouble()) > 9_007_199_254.0)
      throw new IllegalArgumentException("Reduction times must be finite seconds within the exact microsecond range");
    return Math.round(value.getAsDouble() * 1e6);
  }
}
