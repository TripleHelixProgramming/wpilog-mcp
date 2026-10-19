/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import com.google.gson.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;
import java.util.function.BooleanSupplier;
import org.triplehelix.wpilogmcp.log.*;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.read.*;
import org.triplehelix.wpilogmcp.read.internal.ReadWire;
import org.triplehelix.wpilogmcp.read.server.OrdinalFollow;
import org.triplehelix.wpilogmcp.tools.VisualizationBindings;

/** Exact analysis over one fresh, proven recording prefix. Nothing reads a plot buffer or
 * retains a prefix between calls. Slot admission never queues, and every traversal and
 * materialization has a stated limit; a limit is a refusal, never an approximate statistic. */
final class RecordingAnalysis {
  record Limits(int records, int samples, long retainedBytes, int recordBytes, int resultBytes, long nanos) {
    static Limits defaults() { return new Limits(1_048_576, 262_144, 32L << 20, 64 << 10, 1 << 20, Duration.ofSeconds(10).toNanos()); }
  }
  private final RecordingBindings bindings;
  private final Semaphore slots = new Semaphore(2);
  private final Limits limits;
  private final LongSupplier clock;
  RecordingAnalysis(RecordingBindings bindings) { this(bindings, Limits.defaults(), System::nanoTime); }
  RecordingAnalysis(RecordingBindings bindings, Limits limits, LongSupplier clock) { this.bindings = bindings; this.limits = limits; this.clock = clock; }
  static JsonObject capabilities() {
    var l = Limits.defaults(); var out = new JsonObject(); out.addProperty("max_concurrent_reads", 2);
    out.addProperty("max_records_visited", l.records()); out.addProperty("max_statistics_samples", l.samples());
    out.addProperty("max_retained_value_bytes", l.retainedBytes()); out.addProperty("max_record_payload_bytes", l.recordBytes());
    out.addProperty("max_result_bytes", l.resultBytes()); out.addProperty("max_problem_details", 100);
    out.addProperty("cooperative_work_budget_ms", l.nanos() / 1_000_000); return out;
  }
  private static ReadException limited(String reason) {
    return new ReadException(ReadProblem.Code.RESOURCE_LIMIT, "Recording analysis " + reason + "; narrow the range where applicable or read individual members");
  }
  static final class Budget {
    final Limits limits; final LongSupplier clock; final long started; long visited, retained; int samples;
    final BooleanSupplier alive;
    Budget(Limits limits, LongSupplier clock, BooleanSupplier alive) { this.limits = limits; this.clock = clock; this.alive = alive; this.started = clock.getAsLong(); }
    void check() throws IOException {
      if (!alive.getAsBoolean()) throw new java.nio.file.AccessDeniedException("Visualization session expired; reconnect before reading the recording");
      if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Recording analysis cancelled");
      if (clock.getAsLong() - started >= limits.nanos()) throw limited("exceeded its cooperative 10 second work budget");
    }
    void batch(SampleBatch batch) throws IOException {
      check(); visited += batch.recordsVisited();
      if (visited > limits.records()) throw limited("exceeded its record-visit limit");
    }
    void retain(Sample sample) throws IOException {
      if (++samples > limits.samples()) throw limited("exceeded its exact statistics sample limit");
      try { retained += 128 + ReadWire.estimatedValueBytes(sample.value(), limits.retainedBytes()); }
      catch (ReadWire.WireLimit tooLarge) { throw limited("exceeded its retained-value allowance"); }
      if (retained > limits.retainedBytes()) throw limited("exceeded its retained-value allowance");
    }
  }
  static final class Problems {
    long count; final JsonArray details = new JsonArray();
    void accept(String member, List<ReadProblem> problems) {
      count += problems.size();
      for (var problem : problems) if (details.size() < 100) {
        var p = new JsonObject(); p.addProperty("member_id", member); p.addProperty("code", problem.code().name());
        p.addProperty("entry", problem.entry()); p.addProperty("record_offset", Long.toString(problem.recordOffset())); p.addProperty("message", problem.message()); details.add(p);
      }
    }
    void annotate(JsonObject result) {
      result.add("problems", details); result.addProperty("problem_count", count); result.addProperty("problems_truncated", count > details.size());
    }
  }
  JsonObject value(JsonObject request) throws IOException {
    return value(request, () -> true);
  }
  JsonObject value(JsonObject request, BooleanSupplier alive) throws IOException {
    try { return read(request, false, alive); }
    catch (IOException | RuntimeException failure) { throw failure; }
    catch (Exception failure) { throw new IOException(failure); }
  }
  JsonObject statistics(JsonObject request) throws Exception { return statistics(request, () -> true); }
  JsonObject statistics(JsonObject request, BooleanSupplier alive) throws Exception { return read(request, true, alive); }
  private RecordingBindings.Bound admit(JsonObject source, String entry, String field, Budget budget) throws IOException {
    budget.check(); return bindings.admitExact(source, entry, field);
  }
  private JsonObject read(JsonObject request, boolean statistics, BooleanSupplier alive) throws Exception {
    String entry = ReadWire.text(request, "entry"), field = request.has("field") ? ReadWire.text(request, "field") : null;
    if (field != null && FieldPath.parse(field).hasWildcard()) throw new IllegalArgumentException("Recording analysis requires a scalar field or array index, not a wildcard");
    var source = VisualizationContracts.source(request.getAsJsonObject("source_ref"));
    var windows = new ArrayList<ReadQuery.Window>();
    if (statistics && (request.has("start_time") || request.has("end_time"))) windows.add(new ReadQuery.Window(
        request.has("start_time") ? micros(request, "start_time") : Long.MIN_VALUE,
        request.has("end_time") ? micros(request, "end_time") : Long.MAX_VALUE, true));
    if (!statistics && request.has("at_sec")) windows.add(new ReadQuery.Window(Long.MIN_VALUE, micros(request, "at_sec"), true));
    if (!slots.tryAcquire()) throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT, "Two recording analyses are in progress; retry after one finishes", true);
    var budget = new Budget(limits, clock, alive);
    try (var bound = admit(source, entry, field, budget)) {
      budget.check();
      var binding = bound.result().getAsJsonArray("bindings").get(0).getAsJsonObject();
      if (!binding.get("state").getAsString().equals("resolved"))
        throw new IOException("SOURCE_UNAVAILABLE: selected recording declarations or schemas disagree: " + binding.get("problems"));
      var prefix = bound.admission().prefix(); var summary = RecordingInventory.summary(prefix);
      var members = prefix.members().stream().sorted(Comparator.comparing(OrdinalFollow.Member::id)).toList();
      var history = new ArrayList<Sample>(); var problems = new Problems(); Sample latest = null; String latestMember = null;
      var arguments = new JsonObject();
      if (statistics) {
        arguments.addProperty("name", entry);
        for (var key : List.of("field", "start_time", "end_time", "last_seconds")) if (request.has(key)) arguments.add(key, request.get(key));
        var first = members.stream().map(OrdinalFollow.Member::snapshot).filter(s -> s.entries().containsKey(entry)).findFirst().orElseThrow();
        arguments = VisualizationBindings.statisticsArguments(
            new History(prefix.recordingId(), first.entries().get(entry), first.schemas(), List.of(), summary, problems), arguments);
        windows.clear();
        if (arguments.has("start_time") || arguments.has("end_time")) windows.add(new ReadQuery.Window(
            arguments.has("start_time") ? micros(arguments, "start_time") : Long.MIN_VALUE,
            arguments.has("end_time") ? micros(arguments, "end_time") : Long.MAX_VALUE, true));
      }
      ReadSnapshot representative = null;
      for (var member : members) {
        budget.check(); var snapshot = member.snapshot();
        if (!snapshot.entries().containsKey(entry)) continue;
        if (representative == null) representative = snapshot;
        // File order avoids a full chronological permutation for a one-value query. The scan
        // counts excluded records too, so a narrow window cannot disguise unbounded work.
        var query = new ReadQuery(entry, windows, ReadQuery.Order.FILE, 256, limits.recordBytes())
            .withRecordLimit(limits.recordBytes(), ReadQuery.LimitAction.ERROR);
        if (!statistics && field != null) query = query.project(field);
        try (var cursor = snapshot.read(query)) {
          while (true) {
            budget.check(); var batch = cursor.next(); budget.batch(batch); problems.accept(member.id(), batch.problems());
            for (var sample : batch.samples()) {
              if (statistics) { budget.retain(sample); history.add(sample); }
              else if (latest == null || member.id().equals(latestMember) && RecordedValue.later(sample, latest)
                  || !member.id().equals(latestMember) && sample.timestampUs() >= latest.timestampUs()) {
                latest = sample; latestMember = member.id();
              }
            }
            if (batch.complete()) break;
          }
        }
      }
      JsonObject result;
      if (statistics) {
        // Stable long-time ordering preserves member-id then physical-ordinal ties. The shared
        // tool still owns field projection, angle unwrapping, percentiles and data quality.
        history.sort(Comparator.comparingLong(Sample::timestampUs));
        var values = history.stream().map(s -> new TimestampedValue(s.timestampUs() / 1e6, s.value())).toList();
        var log = new History(prefix.recordingId(), representative.entries().get(entry), representative.schemas(), values, summary, problems);
        budget.check(); result = VisualizationBindings.statistics(log, arguments); result.addProperty("basis", "admitted_recording_prefix");
      } else {
        result = new JsonObject(); result.add("value", latest == null ? JsonNull.INSTANCE : ReadWire.value(latest.value(), limits.resultBytes() / 2));
        result.addProperty("timestamp_us", latest == null ? null : Long.toString(latest.timestampUs()));
        result.addProperty("member_id", latestMember); result.addProperty("ordinal", latest == null ? null : Integer.toString(latest.ordinal()));
        result.addProperty("basis", "last_recorded_sample_at_or_before_cursor");
        result.addProperty("tie_basis", "timestamp_us, then member_id in Java string order, then physical entry ordinal; greatest wins");
      }
      problems.annotate(result);
      var proof = new JsonObject(); proof.addProperty("recording_id", prefix.recordingId()); proof.addProperty("finalized", prefix.finalized());
      proof.addProperty("catalog_revision", Long.toString(prefix.catalogRevision())); proof.add("members", summary.get("members")); proof.add("clock", summary.get("clock"));
      for (int i = 0; i < prefix.members().size(); i++) {
        var m = prefix.members().get(i); var item = proof.getAsJsonArray("members").get(i).getAsJsonObject();
        boolean present = m.snapshot().entries().containsKey(entry); item.addProperty("entry_present", present);
        item.addProperty("entry_records", present ? Long.toString(m.snapshot().sampleCount(entry)) : "0");
      }
      result.add("prefix", proof); result.addProperty("records_visited", budget.visited);
      var inputs = result.has("inputs") ? result.getAsJsonObject("inputs") : new JsonObject();
      if (statistics && request.has("last_seconds") && !request.get("last_seconds").isJsonNull()) {
        var window = new JsonObject(); window.add("start", arguments.get("start_time")); window.add("end", arguments.get("end_time"));
        inputs.add("window", window); inputs.add("last_seconds", request.get("last_seconds"));
      }
      inputs.add("source_ref", source); inputs.addProperty("entry", entry); inputs.addProperty("field", field); result.add("inputs", inputs);
      budget.check(); bound.admission().check();
      try { ReadWire.bytes(result, limits.resultBytes()); }
      catch (ReadException | ReadWire.WireLimit tooLarge) { throw limited("result exceeds 1 MiB"); }
      budget.check(); return result;
    } catch (ReadWire.WireLimit tooLarge) { throw limited("value exceeds its result allowance"); }
    finally { slots.release(); }
  }
  private static long micros(JsonObject request, String key) {
    var value = request.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + " must be a finite number of seconds");
    double seconds = value.getAsDouble();
    if (!Double.isFinite(seconds) || Math.abs(seconds) > 9_007_199_254.0) throw new IllegalArgumentException(key + " exceeds the exact microsecond range");
    return Math.round(seconds * 1e6);
  }
  record History(String path, EntryInfo entry, StructSchemas structSchemas,
      List<TimestampedValue> samples, JsonObject summary, Problems problems) implements LogData {
    public Map<String, EntryInfo> entries() { return Map.of(entry.name(), entry); }
    public Map<String, List<TimestampedValue>> values() { return Map.of(entry.name(), samples); }
    // Empty LogData views use a zero scope origin. This is not measured clock evidence:
    // sessionTimeRange and the published prefix must retain the absence of recorded bounds.
    public double minTimestamp() { return summary.get("start_time_sec").isJsonNull() ? 0 : summary.get("start_time_sec").getAsDouble(); }
    public double maxTimestamp() { return summary.get("end_time_sec").isJsonNull() ? 0 : summary.get("end_time_sec").getAsDouble(); }
    public double timeScopeEnd() {
      var estimate = summary.getAsJsonObject("clock").get("robot_time_sec");
      if (estimate.isJsonNull()) return maxTimestamp();
      return summary.get("end_time_sec").isJsonNull() ? estimate.getAsDouble() : Math.max(maxTimestamp(), estimate.getAsDouble());
    }
    public Optional<SessionTimeRange> sessionTimeRange() { return !summary.get("start_time_sec").isJsonNull() && !summary.get("end_time_sec").isJsonNull() ? Optional.of(new SessionTimeRange(minTimestamp(), maxTimestamp())) : Optional.empty(); }
    public boolean truncated() { return summary.get("truncated").getAsBoolean(); }
    public boolean damaged() { return summary.get("damaged").getAsBoolean(); }
    public String truncationMessage() { return truncated() ? "An admitted recording member has an incomplete or damaged tail; inspect prefix.members" : null; }
    public Optional<DecodeProblem> decodeProblem(String name) {
      return problems.count == 0 ? Optional.empty() : Optional.of(new DecodeProblem("Selected-window decode failures; see member-qualified problems", Math.toIntExact(problems.count), Math.toIntExact(samples.size() + problems.count)));
    }
  }
}
