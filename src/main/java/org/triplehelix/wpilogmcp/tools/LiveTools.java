/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.triplehelix.wpilogmcp.capture.CaptureStats;
import org.triplehelix.wpilogmcp.capture.LiveCapture;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;
import org.triplehelix.wpilogmcp.mcp.SessionContext;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.store.CaptureStore;
import static org.triplehelix.wpilogmcp.tools.ToolUtils.*;

/** Publication facts are queried in memory; a tool cannot put the NT4 loop behind store I/O. */
public final class LiveTools {
  private LiveTools() {}
  public static void registerAll(ToolRegistry registry, LiveCapture live) {
    registry.setCurrentSession(() -> org.triplehelix.wpilogmcp.mcp.CurrentSessionResource.read(live));
    registry.registerTool(new ListSessions(live, registry::isManaged));
    registry.registerTool(new GetLatestValues(live));
    registry.registerTool(new WaitForChange(live));
  }
  private static final String MEANING = " These are the latest published, not measured, values; "
      + "a stale age_ms means publishing stopped, not that the robot stopped. NT4 names and their "
      + "NT: capture names are accepted. Binary values (including structs) are raw signed-byte arrays; "
      + "use the ordinary log tools to decode structs. Raw NaN and infinities are strings.";
  private abstract static class LiveTool extends ToolBase {
    final LiveCapture live;
    LiveTool(LiveCapture live) { this.live = live; }
    @Override protected final JsonElement executeInternal(JsonObject arguments) throws Exception {
      var current = live == null ? null : live.current();
      JsonObject result;
      try { result = read(arguments, current).build(); }
      catch (IllegalArgumentException e) { result = ResponseBuilder.error(e.getMessage()).build(); }
      var inputs = new JsonObject();
      inputs.addProperty("session", location(current).path());
      result.add("inputs", inputs);
      return result;
    }
    abstract ResponseBuilder read(JsonObject arguments, CaptureStore.Status current) throws Exception;
    private record Location(String path, String endedAt) {}
    private Location location(CaptureStore.Status current) {
      if (current != null) return new Location(live.resolve(current.path()).toString(), current.endedAt() == null ? null : current.endedAt().toString());
      if (live != null) for (var view : live.sessions()) {
        var capture = view.session().files().stream().filter(f -> "captured".equals(f.provenance().kind())).findFirst();
        if (capture.isPresent()) return new Location(live.resolve(view.directory().resolve(capture.get().path())).toString(), view.session().endedAt());
      }
      return new Location(null, null);
    }
    ResponseBuilder inactive(CaptureStore.Status current) {
      var last = location(current);
      return ResponseBuilder.notApplicable(live == null ? "Capture is not enabled" : "No capture session is open")
          .addProperty("last_session", last.path()).addProperty("ended_at", last.endedAt());
    }
  }

  static final class ListSessions extends LiveTool {
    private final java.util.function.BooleanSupplier managed;
    ListSessions(LiveCapture live) { this(live, () -> false); }
    ListSessions(LiveCapture live, java.util.function.BooleanSupplier managed) { super(live); this.managed = managed; }
    @Override public String name() { return "list_sessions"; }
    @Override public String description() {
      return "List current and recent store sessions, newest first, without scanning logs. Returns sessions[] "
          + "with id, path (capture, null if none), started_at, ended_at (null while open), robot "
          + "(serial_number, comments, address, basis), connected, topic_count, records, bytes, bytes_per_sec "
          + "(captured value records over the last minute), event, match (type, number), cost[] "
          + "(ten highest bytes_per_sec topics: name, records, bytes, bytes_per_sec), thinned[] "
          + "(prefix, period_sec), excluded[], imports[] (path, method, offset_sec, reason), end_reason and "
          + "counts_basis. providers[] reports name, state, reason (stand-down or partial sample), period_sec, "
          + "last_round_trip_ms, robot_cpu_sec (processor time between samples, not provider-only CPU), lines_per_sec, "
          + "dropped_lines, dropped_before_sync, records, bytes (provider value records), sample_bytes (last reply), and program_pids (observed program identities for crash-file placement). "
          + "Old manifests without recorder summaries return null counts, never a file scan. "
          + "Recorder counts are published every 250 ms; closed-session rates are null. limits.sessions "
          + "reports the true total when limit cuts sessions, and each session's limits.cost reports a cut "
          + "topic list. gateway reports state (disabled, waiting, listening, stopped), port, cause "
          + "(while waiting), and since (UTC time of that state). inputs.session names the current or last capture. "
          + "managed says a supervisor owns this server. Capture-disabled use is not_applicable.";
    }
    @Override public JsonObject inputSchema() {
      return new SchemaBuilder().addIntegerProperty("limit", "Newest sessions to return, 1 to 100 (default 20)", false, 20).build();
    }
    @Override ResponseBuilder read(JsonObject arguments, CaptureStore.Status current) {
      int limit = getOptWhole(arguments, "limit", 20);
      if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be an integer from 1 to 100");
      if (live == null) return inactive(current).addProperty("managed", managed.getAsBoolean()).addData("gateway", org.triplehelix.wpilogmcp.nt4.server.GatewayStatus.DISABLED.json());
      var sessions = live.sessions(); var rows = new JsonArray();
      for (var session : sessions.stream().limit(limit).toList()) rows.add(row(session, current));
      return (sessions.isEmpty() ? ResponseBuilder.notApplicable("No capture or imported session has been recorded") : ResponseBuilder.success())
          .addLimitedList("sessions", rows, sessions.size(), limit).addProperty("managed", managed.getAsBoolean()).addData("gateway", live.gateway().json());
    }
    private JsonObject row(LiveCapture.SessionView view, CaptureStore.Status current) {
      var session = view.session(); var row = new JsonObject();
      // An import can widen the session's calendar window and replace its clock basis.
      // The writer's path, including store move aliases, still identifies that same session.
      boolean thisCapture = current != null && live.resolve(current.path()).getParent().equals(view.directory());
      boolean open = thisCapture && current.open();
      CaptureStats stats = thisCapture ? current.statistics() : session.captureStats();
      Path capture = thisCapture ? live.resolve(current.path()) : session.openCapture() != null
          ? live.resolve(view.directory().resolve(session.openCapture().path()))
          : session.files().stream().filter(f -> f.provenance().kind().equals("captured"))
              .map(f -> live.resolve(view.directory().resolve(f.path()))).findFirst().orElse(null);
      row.addProperty("id", session.id()); row.addProperty("path", capture == null ? null : capture.toString());
      row.addProperty("started_at", session.startedAt());
      row.addProperty("ended_at", open ? null : thisCapture && current.endedAt() != null ? current.endedAt().toString() : session.endedAt());
      var robot = new JsonObject(); var device = thisCapture && current.identity() != null ? current.identity() : session.deviceIdentity();
      var known = view.robot();
      robot.addProperty("serial_number", known == null || known.serialNumber() == null
          ? device == null ? null : device.serialNumber() : known.serialNumber());
      robot.addProperty("comments", known == null || known.comments() == null
          ? device == null ? null : device.comments() : known.comments());
      robot.addProperty("address", thisCapture ? current.address() : device == null ? null : device.address());
      robot.addProperty("basis", device != null ? "device" : known == null ? null : known.basis()); row.add("robot", robot);
      row.addProperty("connected", thisCapture && live.connected());
      row.addProperty("topic_count", stats == null ? null : stats.topicCount());
      row.addProperty("records", stats == null ? null : stats.records()); row.addProperty("bytes", stats == null ? null : stats.bytes());
      row.addProperty("bytes_per_sec", open && stats != null ? stats.costs().values().stream().mapToDouble(c -> c.bytesPerSecond()).sum() : null);
      row.add("providers", GSON.toJsonTree(open ? live.providers() : stats == null ? List.of() : stats.providers()));
      row.addProperty("counts_basis", stats == null ? "no complete recorder summary in this manifest" : "capture value records; excludes control records, context and copied schema seeds");
      row.addProperty("end_reason", thisCapture ? current.endReason() : session.endReason());
      row.addProperty("event", thisCapture && current.event() != null ? current.event() : session.event());
      var match = new JsonObject();
      match.addProperty("type", thisCapture && current.matchType() != null ? current.matchType() : session.matchType());
      match.addProperty("number", thisCapture && current.matchNumber() != null ? current.matchNumber() : session.matchNumber()); row.add("match", match);
      var costs = new JsonArray();
      if (stats != null) stats.costs().entrySet().stream().sorted(
          Comparator.<Map.Entry<String, org.triplehelix.wpilogmcp.capture.TopicCost.Snapshot>>comparingDouble(e -> e.getValue().bytesPerSecond()).reversed()
          .thenComparing(Map.Entry::getKey)).limit(10).forEach(e -> {
            var value = new JsonObject(); value.addProperty("name", e.getKey()); value.addProperty("records", e.getValue().records());
            value.addProperty("bytes", e.getValue().bytes()); value.addProperty("bytes_per_sec", open ? e.getValue().bytesPerSecond() : null); costs.add(value);
          });
      ResultContract.addLimitedList(row, "cost", costs, stats == null ? 0 : stats.costs().size(), 10);
      var thinned = new JsonArray();
      if (stats != null) stats.thinnedUs().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
        var value = new JsonObject(); value.addProperty("prefix", e.getKey()); value.addProperty("period_sec", e.getValue() / 1_000_000.0); thinned.add(value);
      });
      row.add("thinned", thinned); row.add("excluded", GSON.toJsonTree(stats == null ? List.of() : stats.excluded()));
      var imports = new JsonArray();
      for (var file : session.files()) if (!file.provenance().kind().equals("captured")) {
        var imported = new JsonObject(); imported.addProperty("path", live.resolve(view.directory().resolve(file.path())).toString());
        var matching = file.matching();
        imported.addProperty("method", matching != null ? matching.method() : file.placementMethod() != null
            ? file.placementMethod() : file.matchingReason() != null || capture == null ? null : "by_time_overlap");
        imported.addProperty("offset_sec", matching == null ? null : matching.offsetMicros() / 1_000_000.0);
        imported.addProperty("reason", file.matchingReason()); imports.add(imported);
      }
      row.add("imports", imports); return row;
    }
  }

  static final class GetLatestValues extends LiveTool {
    GetLatestValues(LiveCapture live) { super(live); }
    @Override public String name() { return "get_latest_values"; }
    @Override public String description() {
      return "Read named entries from the capture client's concurrent latest-value table. Returns values[] "
          + "with name, value, timestamp_sec (robot clock), age_ms (robot now minus timestamp, null before time sync), "
          + "source (nt4, ssh, tail, photonvision or jmx), and type (authoritative entry type), plus missing[]. Missing some is partial with skipped; "
          + "all missing is no_match with looked_for and hint. No open capture is not_applicable with last_session "
          + "and ended_at. inputs.session names the capture." + MEANING;
    }
    @Override public JsonObject inputSchema() {
      var item = new JsonObject(); item.addProperty("type", "string");
      return new SchemaBuilder().addArrayProperty("entries", "NT4 topic names or NT: capture names; 1 to 2000 names", item, true).build();
    }
    @Override ResponseBuilder read(JsonObject arguments, CaptureStore.Status current) {
      if (!arguments.has("entries") || !arguments.get("entries").isJsonArray()) throw new IllegalArgumentException("entries must be an array of names");
      var entries = arguments.getAsJsonArray("entries");
      if (entries.isEmpty() || entries.size() > 2000) throw new IllegalArgumentException("entries must contain 1 to 2000 names");
      var names = new java.util.LinkedHashSet<String>();
      for (var entry : entries) {
        if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString() || entry.getAsString().isBlank()) throw new IllegalArgumentException("entries must contain nonempty strings");
        names.add(entry.getAsString());
      }
      if (current == null || !current.open()) return inactive(current);
      var latest = live.latest(); var topics = live.topics(); Double now = live.robotNowUs();
      return currentValues(names, latest, topics, now, live.providerSources());
    }
    static ResponseBuilder currentValues(java.util.Set<String> names,
        Map<String, org.triplehelix.wpilogmcp.nt4.client.Nt4Client.LatestValue> latest,
        Map<String, org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce> topics, Double now) {
      return currentValues(names, latest, topics, now, Map.of());
    }
    static ResponseBuilder currentValues(java.util.Set<String> names,
        Map<String, org.triplehelix.wpilogmcp.nt4.client.Nt4Client.LatestValue> latest,
        Map<String, org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce> topics, Double now, Map<String, String> sources) {
      var values = new JsonArray(); var missing = new JsonArray();
      for (String name : names) {
        String topic = topics.containsKey(name) ? name : name.startsWith("NT:") ? name.substring(3) : name;
        var value = latest.get(topic); var declaration = topics.get(topic);
        if (value == null || declaration == null) { missing.add(name); continue; }
        var row = value(name, value.type(), value.value(), value.serverTimestampUs());
        row.addProperty("source", sources.getOrDefault(topic, "nt4"));
        row.addProperty("age_ms", now == null ? null : (now - value.serverTimestampUs()) / 1000.0); values.add(row);
      }
      var result = values.isEmpty() ? ResponseBuilder.noMatch("None of the named topics has a current published value")
          .lookedFor(names).hint("Use list_entries on inputs.session for captured names; an unannounced topic has no latest value")
          : ResponseBuilder.success();
      result.addData("values", values).addData("missing", missing);
      if (!values.isEmpty() && !missing.isEmpty()) result.addSkipped("missing", "Some named topics have no current published value");
      return result;
    }
  }

  static final class WaitForChange extends LiveTool {
    WaitForChange(LiveCapture live) { super(live); }
    @Override public String name() { return "wait_for_change"; }
    @Override public String description() {
      return "Wait for the first publication received after this call installs its waiter, even if its value "
          + "equals the previous one. Returns changed, and on a publication name, value, type, timestamp_sec "
          + "(robot clock) and age_ms. A timeout is ok with changed:false. One outstanding wait per entry per MCP session; "
          + "a second is error. No open capture is not_applicable with last_session and ended_at; disconnect or "
          + "unannounce cancels with reason. An unknown topic is no_match with looked_for and hint. "
          + "inputs.session names the capture. timeout_ms defaults to 5000 and is capped at 30000." + MEANING;
    }
    @Override public JsonObject inputSchema() {
      return new SchemaBuilder().addProperty("entry", "string", "One NT4 topic name or NT: capture name", true)
          .addIntegerProperty("timeout_ms", "Wait duration in milliseconds, nonnegative; capped at 30000", false, 5000).build();
    }
    @Override ResponseBuilder read(JsonObject arguments, CaptureStore.Status current) throws Exception {
      var supplied = arguments.get("entry");
      if (supplied == null || !supplied.isJsonPrimitive() || !supplied.getAsJsonPrimitive().isString() || supplied.getAsString().isBlank()) throw new IllegalArgumentException("entry must be a nonempty string");
      String name = getRequiredString(arguments, "entry");
      int timeout = getOptWhole(arguments, "timeout_ms", 5000);
      if (timeout < 0) throw new IllegalArgumentException("timeout_ms must be a nonnegative integer");
      timeout = Math.min(timeout, 30000);
      if (current == null || !current.open()) return inactive(current);
      String topic = live.topic(name);
      if (!live.topics().containsKey(topic)) return ResponseBuilder.noMatch("The topic is not announced")
          .lookedFor(List.of(name)).hint("Use a currently announced NT4 name or its NT: capture name");
      var session = SessionContext.current();
      var future = live.waitFor(session == null ? "direct" : session.getId(), topic, timeout);
      LiveCapture.Change change;
      try { change = future.get(); }
      catch (InterruptedException e) { future.cancel(false); Thread.currentThread().interrupt(); return ResponseBuilder.error("Live wait interrupted"); }
      if (change.reason() != null) return ResponseBuilder.notApplicable(change.reason()).addProperty("changed", false);
      var result = ResponseBuilder.success().addProperty("changed", change.changed());
      if (change.changed()) {
        var row = value(name, change.type(), change.frame().value(), change.frame().timestampUs());
        Double now = live.robotNowUs();
        row.addProperty("age_ms", now == null ? null : (now - change.frame().timestampUs()) / 1000.0);
        row.entrySet().forEach(e -> result.addData(e.getKey(), e.getValue()));
      }
      return result;
    }
  }
  private static int getOptWhole(JsonObject arguments, String key, int fallback) {
    var value = arguments.get(key);
    if (value == null || value.isJsonNull()) return fallback;
    try {
      if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
      return value.getAsBigDecimal().intValueExact();
    } catch (RuntimeException e) { throw new IllegalArgumentException(key + " must be an integer"); }
  }
  private static JsonObject value(String name, String type, Object value, long timestampUs) {
    var row = new JsonObject(); row.addProperty("name", name); row.addProperty("type", type);
    row.add("value", sampleToJson(value)); row.addProperty("timestamp_sec", timestampUs / 1_000_000.0); return row;
  }
}
