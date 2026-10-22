/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.triplehelix.wpilogmcp.tools.ToolUtils.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.mcp.BoundedReply;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;
import org.triplehelix.wpilogmcp.read.FieldPath;
import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.ReadOptions;
import org.triplehelix.wpilogmcp.read.ReadProblem;
import org.triplehelix.wpilogmcp.read.ReadQuery;
import org.triplehelix.wpilogmcp.read.TimeWindows;
import org.triplehelix.wpilogmcp.read.catalog.SessionSnapshot;
import org.triplehelix.wpilogmcp.read.catalog.StoreReadCatalog;
import org.triplehelix.wpilogmcp.read.server.SourceScopes;
import org.triplehelix.wpilogmcp.read.server.VerifiedContentHashes;
import org.triplehelix.wpilogmcp.store.StoreCatalog;

/**
 * One statistic of each metric in every selected finished session of one store or mirror, and the
 * same statistics over those per-session values with each session counted once. The session is
 * the unit: fifty sessions with unequal sample counts give an equal-weight mean, never the pooled
 * mean, which appears only when asked for and is labeled sample-weighted. Only a catalogued store
 * or mirror says which files belong to a session, so a plain folder is refused, and a session still
 * recording has no final bytes to verify, so it is skipped.
 *
 * <p>Work is capped before it starts (sizes, members) or refused when a cap is reached (record
 * visits, kept values, evidence, the complete reply); nothing is truncated into a smaller answer.
 * At most two comparisons run at once and a third is refused, not queued: there is no deadline or
 * cancellation, so admission is the only bound on concurrent work. A comparison reads through the
 * shared log manager and never joins the store queue, so capture and health keep their turns.
 *
 * @since 0.10.0
 */
final class CompareSessionsTool extends ToolBase implements BoundedReply {

  /** One statistic of one number. Order statistics have no meaning on a circle. */
  enum Statistic {
    MEAN, STD_DEV, MIN, MAX, MEDIAN, P5, P95;

    String wire() {
      return name().toLowerCase(Locale.ROOT);
    }

    boolean ordered() {
      return this != MEAN && this != STD_DEV;
    }

    boolean keepsValues() {
      return this == MEDIAN || this == P5 || this == P95;
    }

    static Statistic of(String wire) {
      for (var statistic : values()) if (statistic.wire().equals(wire)) return statistic;
      return null;
    }
  }

  /** The settled caps (hardening plan, "Multi-session work and limits"); one test pins them. */
  record Limits(int sessions, int metrics, int windows, int memberOccurrences, int membersPerSession,
      long memberBytes, long recordVisits, int keptValues, int pooledValues, int requestBytes,
      int factValues, int confounderBytes, int replyBytes) {
    static final Limits PRODUCTION = new Limits(64, 8, 16, 256, 64, 1L << 30, 8_388_608L, 65_536,
        1_048_576, 64 << 10, 64, 256 << 10, 1 << 20);
  }

  private static volatile Limits limits = Limits.PRODUCTION;

  static Limits limits() {
    return limits;
  }

  /** Test seam: smaller caps exercise the same boundaries without season-sized fixtures. */
  static void limits(Limits value) {
    limits = Objects.requireNonNull(value);
  }

  /** Comparisons running at once, server-wide. */
  static final int CONCURRENT = 2;
  private static final Semaphore SLOTS = new Semaphore(CONCURRENT);
  /** Runs after a call takes its slot and before it reads anything; tests hold calls here. */
  static volatile Runnable admitted = () -> {};
  /** Runs after each batch of samples while a session and its cursor are open; tests hold calls here. */
  static volatile Runnable reading = () -> {};
  /** The bounded proof memory all comparisons share, so a repeated call rehashes no unchanged file. */
  private static final VerifiedContentHashes PROOFS = new VerifiedContentHashes();

  private static final List<String> FACTS = List.of("robot", "git_sha", "event", "battery", "firmware");
  private static final Set<String> TOP = Set.of("scope_id", "sessions", "metrics", "scope", "start_time",
      "end_time", "windows", "last_seconds", "overlap", "pool_samples", "confounder_entries");
  private static final Set<String> SESSION_KEYS = Set.of("session_id", "anchor_path");
  private static final Set<String> METRIC_KEYS = Set.of("id", "name", "role", "field", "angle", "unit", "statistic");
  private static final Map<String, SignalResolver.Role> ROLES = Map.of(
      "battery_voltage", SignalResolver.Role.BATTERY_VOLTAGE, "total_current", SignalResolver.Role.TOTAL_CURRENT,
      "loop_time_full", SignalResolver.Role.LOOP_TIME_FULL, "loop_time_user", SignalResolver.Role.LOOP_TIME_USER);
  private static final Set<String> SCOPES = Set.of("all", "enabled", "disabled", "auto", "teleop", "test");

  @Override
  public String name() {
    return "compare_sessions";
  }

  @Override
  public String description() {
    return "Compare one statistic of each metric across finished sessions of one store or mirror, "
        + "each session counting once. Pass scope_id (list_source_scopes) when several sources are "
        + "configured; sessions as [{session_id, anchor_path}] with ids from list_available_logs "
        + "(anchor_path, the session's WPILOG member whose clock places scope and windows, defaults "
        + "to its only WPILOG member); and metrics as [{id, name or role, field, angle, unit, "
        + "statistic}], where a role is battery_voltage, total_current, loop_time_full or "
        + "loop_time_user and the statistic is mean, std_dev, min, max, median, p5 or p95. scope, "
        + "windows, start_time, end_time and last_seconds resolve on each session's own timeline. "
        + "Returns requested_session_count, analyzed_session_count and skipped_session_count "
        + "(requested always equals analyzed plus skipped); sessions, one row per analyzed session "
        + "with session_id, anchor, inputs (members and their sha256, excluded_members), scope and "
        + "metrics (metric_id, value, unit, unit_basis, sample_count, non_finite_count, "
        + "records_without_value, null_reason, data_quality); summary, per metric: session_count, "
        + "contributing_sessions, excluded_sessions and the same seven statistics over the "
        + "per-session values with equal session weights (one session's mean counts once, whatever "
        + "its sample count); pooled only with pool_samples: the statistic over all samples together, "
        + "weighting sample_weighted, with session_count and sample_count; confounders: robot, "
        + "git_sha, event, battery and firmware, every recorded value with its sessions and "
        + "evidence, and changed_within, conflicting_sessions and unknown_sessions; skipped, each "
        + "session or metric left out and why. A plain folder is refused (import it into a store, or "
        + "compare two files with compare_matches); a session still recording is skipped; a "
        + "selection naming one session twice, by alias, or by equal or shared member content is "
        + "refused. Angles take only a circular mean and its spread (std_dev). At most two "
        + "comparisons run at once; a third is refused as retryable. Caps: 64 sessions, 8 metrics, "
        + "16 windows per session, 256 members, 1 GiB of member files, 8,388,608 record visits, "
        + "65,536 kept values per session and metric for median and percentiles, and a complete "
        + "reply of 1 MiB; a cap refuses with a way to narrow and never truncates. Confounders are "
        + "observations, not causes; distinct sessions are not proven independent; one season of "
        + "sessions is not a population; no significance test is made."
        + GUIDANCE_UNIVERSAL + GUIDANCE_STATISTICAL;
  }

  @Override
  public JsonObject inputSchema() {
    var sessionItem = new JsonObject();
    sessionItem.addProperty("type", "object");
    var sessionProperties = new JsonObject();
    sessionProperties.add("session_id", stringSchema("A session id or merge alias in the selected store"));
    sessionProperties.add("anchor_path", stringSchema("The WPILOG member (its manifest path) whose clock places "
        + "scope and windows; optional when the session has one WPILOG member"));
    sessionItem.add("properties", sessionProperties);
    var sessionRequired = new JsonArray();
    sessionRequired.add("session_id");
    sessionItem.add("required", sessionRequired);

    var metricItem = new JsonObject();
    metricItem.addProperty("type", "object");
    var metricProperties = new JsonObject();
    metricProperties.add("id", stringSchema("Your label for this metric, unique in the call"));
    metricProperties.add("name", stringSchema("An exact entry, optionally with a field path; or give role"));
    metricProperties.add("role", enumSchema("A signal role resolved in each session by the shared resolver",
        List.of("battery_voltage", "total_current", "loop_time_full", "loop_time_user")));
    metricProperties.add("field", stringSchema(NumericSignal.FIELD_PARAM + " One scalar field or fixed index; no [*]."));
    metricProperties.add("angle", stringSchema(NumericSignal.ANGLE_PARAM));
    metricProperties.add("unit", stringSchema("The unit, only where the log records none; a logged unit wins"));
    metricProperties.add("statistic", enumSchema("The per-session statistic",
        Arrays.stream(Statistic.values()).map(Statistic::wire).toList()));
    metricItem.add("properties", metricProperties);
    var metricRequired = new JsonArray();
    metricRequired.add("id");
    metricRequired.add("statistic");
    metricItem.add("required", metricRequired);

    return new SchemaBuilder()
        .addProperty("scope_id", "string", "Store or mirror scope from list_source_scopes; required "
            + "when several sources are configured. A plain folder is refused.", false)
        .addArrayProperty("sessions", "1 to 64 finished sessions of that store, each counted once",
            sessionItem, true)
        .addArrayProperty("metrics", "1 to 8 metrics, each one statistic of one number", metricItem, true)
        .addProperty("scope", "string", TimeScope.SCOPE_DESCRIPTION
            + " Resolved in each session's own timeline.", false)
        .addNumberProperty("start_time", "Start (s), on each session's anchor clock", false, null)
        .addNumberProperty("end_time", "End (s), on each session's anchor clock", false, null)
        .addArrayProperty("windows", TimeScope.WINDOWS_DESCRIPTION + " On each session's anchor clock.",
            TimeScope.windowItemSchema(), false)
        .addNumberProperty("last_seconds", TimeScope.LAST_SECONDS_DESCRIPTION
            + " Resolved from each session's end.", false, null)
        .addProperty("overlap", "string", "'reject' (default) skips a session whose members record "
            + "the same entry over overlapping times; 'keep_all' keeps every source", false)
        .addProperty("pool_samples", "boolean", "Also compute each statistic over all sessions' samples "
            + "together, labeled sample-weighted (default false)", false)
        .addProperty("confounder_entries", "object", "Exact entry names for any of robot, git_sha, "
            + "event, battery or firmware, used instead of the published conventions", false)
        .build();
  }

  private static JsonObject stringSchema(String description) {
    var schema = new JsonObject();
    schema.addProperty("type", "string");
    schema.addProperty("description", description);
    return schema;
  }

  private static JsonObject enumSchema(String description, List<String> values) {
    var schema = stringSchema(description);
    var allowed = new JsonArray();
    values.forEach(allowed::add);
    schema.add("enum", allowed);
    return schema;
  }

  // ==================== the call ====================

  @Override
  protected JsonElement executeInternal(JsonObject arguments) throws Exception {
    var caps = limits();
    try {
      var request = parse(arguments, caps);
      if (!SLOTS.tryAcquire()) return busy();
      try {
        admitted.run();
        return compare(request, arguments, caps);
      } finally {
        SLOTS.release();
      }
    } catch (Refusal refusal) {
      return refusal.result;
    }
  }

  private JsonElement compare(Request request, JsonObject arguments, Limits caps) throws IOException {
    var scope = logManager.sourceScopes().select(request.scopeId());
    if (scope.kind().equals("directory")) {
      throw new Refusal(refusal("NOT_A_STORE", "compare_sessions compares finished sessions in a store "
          + "or mirror; the selected source is a plain folder (" + scope.root() + "), which names no "
          + "sessions", "Import the folder's logs into a store and compare its sessions, or use "
          + "compare_matches to compare two files").addProperty("scope_id", scope.id()).build());
    }
    try (var catalog = new StoreReadCatalog(ReadOptions.defaults(List.of(scope.root())),
        logManager.readAccess(), StoreReadCatalog.DEFAULT_SESSION_INDEX_BYTES)) {
      var inventory = catalog.snapshot();
      var store = inventory.stores().stream().filter(s -> s.header().id().equals(scope.id())).findFirst()
          .orElseThrow(() -> new Refusal(refusal("SOURCE_UNAVAILABLE", "The selected store's manifest "
              + "could not be read" + (inventory.problems().isEmpty() ? "" : ": "
              + inventory.problems().get(0).reason()), "Check the store with list_available_logs "
              + "and this scope_id, then retry").build()));
      var result = new Comparison(request, arguments, caps, scope, catalog, store).run();
      logManager.sourceScopes().recheck(scope);
      return result;
    }
  }

  @Override
  public int maxReplyBytes() {
    return limits().replyBytes();
  }

  /** A complete answer that cannot be sent whole is refused with its true counts, never cut. */
  @Override
  public JsonElement replyTooLarge(JsonElement result, long replyBytes, int limit) {
    var source = result != null && result.isJsonObject() ? result.getAsJsonObject() : new JsonObject();
    var builder = refusal("RESPONSE_TOO_LARGE", "The complete reply would be " + replyBytes
        + " bytes; compare_sessions sends one complete reply of at most " + limit
        + " bytes and never a shortened one", "Request fewer sessions, metrics or windows, or "
        + "shorter entry names, and compare the rest in another call")
        .addProperty("reply_bytes", replyBytes).addProperty("limit_bytes", limit);
    for (var key : List.of("requested_session_count", "analyzed_session_count", "skipped_session_count")) {
      if (source.has(key)) builder.addData(key, source.get(key));
    }
    if (source.has("metrics") && source.get("metrics").isJsonArray()) {
      builder.addProperty("metric_count", source.getAsJsonArray("metrics").size());
    }
    return ResultContract.enforce(builder.build());
  }

  // ==================== requests ====================

  private record SessionRequest(int position, String id, String anchor) {}

  private record MetricRequest(String id, String name, String field, SignalResolver.Role role,
      String roleWire, NumericSignal.AngleUnit angle, String unit, Statistic statistic) {
    String label() {
      return role != null ? roleWire : field == null ? name : name + " " + field;
    }
  }

  private record Request(String scopeId, List<SessionRequest> sessions, List<MetricRequest> metrics,
      SessionSnapshot.Overlap overlap, boolean pool, Map<String, String> confounderEntries) {}

  private static Request parse(JsonObject arguments, Limits caps) {
    long bytes = arguments.toString().getBytes(StandardCharsets.UTF_8).length;
    if (bytes > caps.requestBytes()) {
      throw limit("request_bytes", bytes, caps.requestBytes(), "The encoded arguments are " + bytes
          + " bytes; the limit is " + caps.requestBytes(), "Select fewer sessions or metrics, or "
          + "shorter names");
    }
    for (var key : arguments.keySet()) {
      if (!TOP.contains(key)) throw invalid("Unknown parameter '" + key + "'; compare_sessions takes "
          + String.join(", ", new TreeSet<>(TOP)));
    }
    String scopeId = text(arguments.get("scope_id"), "scope_id", true);

    var sessionsElement = arguments.get("sessions");
    if (sessionsElement == null || !sessionsElement.isJsonArray() || sessionsElement.getAsJsonArray().isEmpty()) {
      throw invalid("sessions must be a non-empty array of {session_id, anchor_path}");
    }
    var sessionArray = sessionsElement.getAsJsonArray();
    if (sessionArray.size() > caps.sessions()) {
      throw limit("sessions", sessionArray.size(), caps.sessions(), sessionArray.size()
          + " sessions were requested; one comparison takes at most " + caps.sessions(),
          "Compare at most " + caps.sessions() + " sessions per call");
    }
    var sessions = new ArrayList<SessionRequest>();
    for (int i = 0; i < sessionArray.size(); i++) {
      String where = "sessions[" + i + "]";
      var item = object(sessionArray.get(i), where, SESSION_KEYS);
      sessions.add(new SessionRequest(i, text(item.get("session_id"), where + ".session_id", false),
          text(item.get("anchor_path"), where + ".anchor_path", true)));
    }

    var metricsElement = arguments.get("metrics");
    if (metricsElement == null || !metricsElement.isJsonArray() || metricsElement.getAsJsonArray().isEmpty()) {
      throw invalid("metrics must be a non-empty array of {id, name or role, statistic}");
    }
    var metricArray = metricsElement.getAsJsonArray();
    if (metricArray.size() > caps.metrics()) {
      throw limit("metrics", metricArray.size(), caps.metrics(), metricArray.size()
          + " metrics were requested; one comparison takes at most " + caps.metrics(),
          "Compare at most " + caps.metrics() + " metrics per call");
    }
    var metrics = new ArrayList<MetricRequest>();
    var ids = new HashSet<String>();
    for (int i = 0; i < metricArray.size(); i++) {
      String where = "metrics[" + i + "]";
      var item = object(metricArray.get(i), where, METRIC_KEYS);
      String id = text(item.get("id"), where + ".id", false);
      if (!ids.add(id)) throw invalid("Metric id '" + id + "' is used twice; each metric needs its own id");
      String name = text(item.get("name"), where + ".name", true);
      String roleWire = text(item.get("role"), where + ".role", true);
      if ((name == null) == (roleWire == null)) throw invalid(where + " needs exactly one of name or role");
      SignalResolver.Role role = null;
      if (roleWire != null) {
        role = ROLES.get(roleWire);
        if (role == null) throw invalid(where + ".role must be one of " + String.join(", ", new TreeSet<>(ROLES.keySet())));
      }
      String field = text(item.get("field"), where + ".field", true);
      if (field != null) {
        if (role != null) throw invalid(where + ": a role names a scalar entry; field applies to name");
        FieldPath path;
        try {
          path = FieldPath.parse(field);
        } catch (IllegalArgumentException malformed) {
          throw invalid(where + ".field: " + malformed.getMessage());
        }
        if (path.hasWildcard()) throw invalid(where + ".field selects every element ([*]); a metric is "
            + "one number, so choose one index");
      }
      String angleText = text(item.get("angle"), where + ".angle", true);
      NumericSignal.AngleUnit angle = null;
      if (angleText != null) {
        angle = switch (angleText.toLowerCase(Locale.ROOT)) {
          case "radians", "rad" -> NumericSignal.AngleUnit.RADIANS;
          case "degrees", "deg" -> NumericSignal.AngleUnit.DEGREES;
          default -> throw invalid(where + ".angle must be 'radians' or 'degrees'");
        };
      }
      String unit = text(item.get("unit"), where + ".unit", true);
      String statisticText = text(item.get("statistic"), where + ".statistic", false);
      var statistic = Statistic.of(statisticText);
      if (statistic == null) throw invalid(where + ".statistic must be one of mean, std_dev, min, max, median, p5, p95");
      if (angle != null && statistic.ordered()) throw incompatible(where + " (" + id + ") is an angle; angles "
          + "take only mean (a circular mean) and std_dev (its spread), not " + statistic.wire(), List.of());
      metrics.add(new MetricRequest(id, name, field, role, roleWire, angle, unit, statistic));
    }

    var overlapText = text(arguments.get("overlap"), "overlap", true);
    SessionSnapshot.Overlap overlap = SessionSnapshot.Overlap.REJECT;
    if (overlapText != null) {
      overlap = switch (overlapText) {
        case "reject" -> SessionSnapshot.Overlap.REJECT;
        case "keep_all" -> SessionSnapshot.Overlap.KEEP_ALL;
        default -> throw invalid("overlap must be 'reject' or 'keep_all'");
      };
    }
    var poolElement = arguments.get("pool_samples");
    boolean pool = false;
    if (poolElement != null && !poolElement.isJsonNull()) {
      if (!poolElement.isJsonPrimitive() || !poolElement.getAsJsonPrimitive().isBoolean()) {
        throw invalid("pool_samples must be true or false");
      }
      pool = poolElement.getAsBoolean();
    }
    var entries = new LinkedHashMap<String, String>();
    var factsElement = arguments.get("confounder_entries");
    if (factsElement != null && !factsElement.isJsonNull()) {
      var facts = object(factsElement, "confounder_entries", new HashSet<>(FACTS));
      for (var fact : FACTS) {
        String entry = text(facts.get(fact), "confounder_entries." + fact, true);
        if (entry != null) entries.put(fact, entry);
      }
    }
    var scopeText = text(arguments.get("scope"), "scope", true);
    if (scopeText != null) {
      var normalized = scopeText.toLowerCase(Locale.ROOT);
      if (!SCOPES.contains(normalized) && !normalized.matches("segment:\\s*\\d+")) {
        throw invalid("Unknown scope '" + scopeText + "'. " + TimeScope.SCOPE_DESCRIPTION);
      }
    }
    var windows = arguments.get("windows");
    if (windows != null && !windows.isJsonNull()) {
      try {
        TimeScope.parseWindows(windows);
      } catch (IllegalArgumentException malformed) {
        throw invalid(malformed.getMessage());
      }
    }
    Double start = number(arguments.get("start_time"), "start_time");
    Double end = number(arguments.get("end_time"), "end_time");
    if (start != null && end != null && start > end) throw invalid("start_time must not follow end_time");
    Double last = number(arguments.get("last_seconds"), "last_seconds");
    if (last != null && (last <= 0 || start != null || end != null)) {
      throw invalid("last_seconds must be positive and cannot be combined with start_time or end_time");
    }
    return new Request(scopeId, List.copyOf(sessions), List.copyOf(metrics), overlap, pool, Map.copyOf(entries));
  }

  private static JsonObject object(JsonElement element, String where, Set<String> keys) {
    if (element == null || !element.isJsonObject()) throw invalid(where + " must be an object");
    var value = element.getAsJsonObject();
    for (var key : value.keySet()) {
      if (!keys.contains(key)) throw invalid("Unknown key '" + key + "' in " + where + "; it takes "
          + String.join(", ", new TreeSet<>(keys)));
    }
    return value;
  }

  private static String text(JsonElement element, String where, boolean optional) {
    if (element == null || element.isJsonNull()) {
      if (optional) return null;
      throw invalid(where + " is required");
    }
    if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString() || element.getAsString().isBlank()) {
      throw invalid(where + " must be a non-empty string");
    }
    return element.getAsString().strip();
  }

  private static Double number(JsonElement element, String where) {
    if (element == null || element.isJsonNull()) return null;
    if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber() || !Double.isFinite(element.getAsDouble())) {
      throw invalid(where + " must be a finite number of seconds");
    }
    return element.getAsDouble();
  }

  // ==================== refusals ====================

  /** A whole-call refusal; the comparison stops and returns this result. */
  private static final class Refusal extends RuntimeException {
    final JsonObject result;

    Refusal(JsonObject result) {
      super(result.has("error") ? result.get("error").getAsString() : "refused", null, false, false);
      this.result = result;
    }
  }

  private static ResponseBuilder refusal(String code, String message, String hint) {
    return ResponseBuilder.error(message).addProperty("code", code).hint(hint);
  }

  private static Refusal invalid(String message) {
    return new Refusal(refusal("INVALID_REQUEST", message, "Correct the request; get_server_guide "
        + "describes compare_sessions' parameters").build());
  }

  private static Refusal limit(String name, long requested, long limit, String message, String hint) {
    return new Refusal(refusal("RESOURCE_LIMIT", message, hint).addProperty("limit_name", name)
        .addProperty("limit", limit).addProperty("requested", requested).build());
  }

  private static Refusal incompatible(String message, List<JsonObject> details) {
    var builder = refusal("INCOMPATIBLE_METRIC", message, "Narrow the metric: choose the field, angle "
        + "or unit that applies to every selected session, or compare those sessions separately");
    if (!details.isEmpty()) {
      var array = new JsonArray();
      details.forEach(array::add);
      builder.addData("details", array);
    }
    return new Refusal(builder.build());
  }

  private static JsonObject busy() {
    return ResultContract.enforce(refusal("BUSY", CONCURRENT + " session comparisons are already "
        + "running; this one was refused, not queued", "Retry after one of them finishes")
        .addProperty("retryable", true).addProperty("retry_after_ms", 1000).build()).getAsJsonObject();
  }

  // ==================== per-call state ====================

  /** Samples of one entry projection in one session, reduced as they stream. */
  private static final class Series {
    final NumericSignal.AngleUnit angle;
    final DataQuality.Accumulator quality;
    final boolean keep;
    long nonFinite;
    long without;
    long problems;
    /** The summary statistics every tool shares; the circular ones for an angle. */
    final SummaryStatistics summary = new SummaryStatistics();
    final SummaryStatistics.Circular circular;
    double[] kept = new double[0];
    int keptCount;

    Series(boolean keep, NumericSignal.AngleUnit angle, boolean sampled) {
      this.keep = keep;
      this.angle = angle;
      this.quality = new DataQuality.Accumulator(sampled);
      circular = angle == NumericSignal.AngleUnit.NONE ? null : new SummaryStatistics.Circular(angle.period);
    }

    /** False when keeping this value would pass the kept-value cap. */
    boolean add(double timestamp, double value, int keptCap) {
      quality.add(timestamp, value);
      if (!Double.isFinite(value)) {
        nonFinite++;
        return true;
      }
      if (keep) {
        if (keptCount == keptCap) return false;
        if (keptCount == kept.length) kept = Arrays.copyOf(kept, Math.max(16, Math.min(keptCap, keptCount * 2)));
        kept[keptCount++] = value;
      }
      summary.add(value);
      if (circular != null) circular.add(value);
      return true;
    }

    long finite() {
      return summary.count();
    }

    double[] sorted() {
      var values = Arrays.copyOf(kept, keptCount);
      Arrays.sort(values);
      return values;
    }
  }

  /** The samples of every contributing session together, for pool_samples. */
  private static final class Pool {
    int sessions;
    final SummaryStatistics summary = new SummaryStatistics();
    SummaryStatistics.Circular circular;
    double[] kept = new double[0];
    int keptCount;

    void add(Series series) {
      if (series.finite() == 0) return;
      sessions++;
      summary.combine(series.summary);
      if (series.circular != null) {
        if (circular == null) circular = new SummaryStatistics.Circular(series.angle.period);
        circular.combine(series.circular);
      }
      if (series.keptCount > 0) {
        if (keptCount + series.keptCount > kept.length) kept = Arrays.copyOf(kept, keptCount + series.keptCount);
        System.arraycopy(series.kept, 0, kept, keptCount, series.keptCount);
        keptCount += series.keptCount;
      }
    }

    long finite() {
      return summary.count();
    }
  }

  private record Value(Double value, String nullReason) {}

  /** The summary statistics every tool shares, with the reason when one cannot be computed. */
  private static Value linear(Statistic statistic, SummaryStatistics summary, double[] sorted, String what) {
    if (summary.count() == 0) return new Value(null, "no finite value " + what);
    return switch (statistic) {
      case MEAN -> new Value(summary.mean(), null);
      case STD_DEV -> summary.count() < 2 ? new Value(null, "a standard deviation needs at least two finite values, "
          + "and there is 1 " + what) : new Value(summary.stdDev(), null);
      case MIN -> new Value(summary.min(), null);
      case MAX -> new Value(summary.max(), null);
      case MEDIAN -> new Value(SummaryStatistics.median(sorted), null);
      case P5 -> new Value(percentile(sorted, 0.05), null);
      case P95 -> new Value(percentile(sorted, 0.95), null);
    };
  }

  /** The circular statistics every tool shares: mean direction or sqrt(-2 ln R), with the reason
   * when the angles have none. */
  private static Value circular(Statistic statistic, SummaryStatistics.Circular directions, String what) {
    if (directions == null || directions.count() == 0) return new Value(null, "no finite value " + what);
    if (!directions.directed()) return new Value(null, "the angles " + what + " have no mean direction (resultant length 0)");
    return statistic == Statistic.MEAN ? new Value(directions.mean(), null) : new Value(directions.stdDev(), null);
  }

  private static Value value(Series series, Statistic statistic) {
    if (series.angle != NumericSignal.AngleUnit.NONE) return circular(statistic, series.circular, "in this session's scope");
    return linear(statistic, series.summary, statistic.keepsValues() ? series.sorted() : null, "in this session's scope");
  }

  private record Key(String entry, String field, boolean derived, NumericSignal.AngleUnit angle) {}

  /** One metric resolved in one session. */
  private record Resolved(MetricRequest metric, Key key, String type, String unit, String unitBasis,
      String basis, String nullReason) {
    static Resolved omitted(MetricRequest metric, String reason) {
      return new Resolved(metric, null, null, null, null, null, reason);
    }
  }

  private record Span(String source, String entry, String value, Double first, Double last, long count,
      String basis) {}

  /** One selected session through admission, verification and analysis. */
  private static final class Selection {
    final SessionRequest request;
    final StoreCatalog.SessionDirectory directory;
    final List<StoreCatalog.StoredFile> wpilogs;
    StoreCatalog.StoredFile anchor;
    String anchorBasis;
    String skipCode;
    String skipReason;
    boolean absence;
    final Map<Path, FileSnapshot> stamps = new LinkedHashMap<>();
    final Set<String> hashes = new LinkedHashSet<>();
    JsonObject row;
    final Map<String, Resolved> resolved = new LinkedHashMap<>();
    final Map<String, Value> values = new LinkedHashMap<>();
    final Map<String, Series> series = new LinkedHashMap<>();
    final Map<String, List<Span>> facts = new LinkedHashMap<>();
    final Map<String, String> unknown = new LinkedHashMap<>();

    Selection(SessionRequest request, StoreCatalog.SessionDirectory directory,
        List<StoreCatalog.StoredFile> wpilogs) {
      this.request = request;
      this.directory = directory;
      this.wpilogs = wpilogs;
    }

    String id() {
      return directory.session().id();
    }

    boolean skipped() {
      return skipCode != null;
    }

    void skip(String code, String reason, boolean isAbsence) {
      skipCode = code;
      skipReason = reason;
      absence = isAbsence;
    }
  }

  private final class Comparison implements SessionView.Budget {
    private final Request request;
    private final JsonObject arguments;
    private final Limits caps;
    private final SourceScopes.Scope scope;
    private final StoreReadCatalog catalog;
    private final StoreCatalog.Snapshot store;
    private final Map<String, StoreCatalog.StoredFile> byPath = new HashMap<>();
    private final List<JsonObject> ambiguous = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private long visits;

    Comparison(Request request, JsonObject arguments, Limits caps, SourceScopes.Scope scope,
        StoreReadCatalog catalog, StoreCatalog.Snapshot store) {
      this.request = request;
      this.arguments = arguments;
      this.caps = caps;
      this.scope = scope;
      this.catalog = catalog;
      this.store = store;
      for (var file : store.allFiles()) byPath.put(file.path().toString(), file);
    }

    /** Every record any read visits counts once against the call's cap. */
    @Override
    public void visit(long records) {
      visits += records;
      if (visits > caps.recordVisits()) {
        throw limit("record_visits", visits, caps.recordVisits(), "The comparison reached more than "
            + caps.recordVisits() + " record visits (" + visits + " when it stopped); nothing was "
            + "summarized from the part already read", "Narrow scope or windows, or select fewer "
            + "sessions or metrics");
      }
    }

    JsonElement run() throws IOException {
      var selections = select();
      admit(selections);
      verify(selections);
      refuseDuplicates(selections);
      for (var selection : selections) if (!selection.skipped()) analyze(selection);
      if (!ambiguous.isEmpty()) {
        var array = new JsonArray();
        ambiguous.forEach(array::add);
        throw new Refusal(refusal("AMBIGUOUS_ROLE", "A metric's role has no unique published entry in "
            + "some sessions; the server does not choose between candidates", "Pass that metric's entry "
            + "with name instead of role").addData("choices", array).build());
      }
      checkCompatibility(selections);
      return result(selections);
    }

    // ---- selection ----

    private List<Selection> select() {
      var out = new ArrayList<Selection>();
      var unknown = new JsonArray();
      for (var request : request.sessions()) {
        var matches = store.sessions().stream().filter(d -> d.session().names(request.id())).toList();
        if (matches.size() != 1) {
          var item = new JsonObject();
          item.addProperty("position", request.position());
          item.addProperty("session_id", request.id());
          item.addProperty("reason", matches.isEmpty() ? "not in this store" : "names several sessions");
          unknown.add(item);
          continue;
        }
        var directory = matches.get(0);
        var manifest = directory.path().resolve("session.json");
        var wpilogs = store.allFiles().stream().filter(f -> f.manifestPath().equals(manifest))
            .filter(f -> "wpilog".equals(f.file().kind())).toList();
        out.add(new Selection(request, directory, wpilogs));
      }
      if (!unknown.isEmpty()) {
        throw new Refusal(refusal("UNKNOWN_SESSION", unknown.size() + " requested session id(s) do not "
            + "name exactly one session of store " + store.header().id(), "list_available_logs with this "
            + "scope_id lists the store's sessions and their ids").addData("unknown", unknown).build());
      }
      var byId = new LinkedHashMap<String, List<Selection>>();
      for (var selection : out) byId.computeIfAbsent(selection.id(), k -> new ArrayList<>()).add(selection);
      var duplicates = new JsonArray();
      for (var group : byId.values()) if (group.size() > 1) {
        var item = new JsonObject();
        item.addProperty("kind", "same_session");
        item.addProperty("session_id", group.get(0).id());
        var positions = new JsonArray();
        var requested = new JsonArray();
        for (var selection : group) {
          positions.add(selection.request.position());
          requested.add(selection.request.id());
        }
        item.add("positions", positions);
        item.add("requested_ids", requested);
        duplicates.add(item);
      }
      if (!duplicates.isEmpty()) throw duplicateRefusal(duplicates);
      return out;
    }

    private Refusal duplicateRefusal(JsonArray duplicates) {
      return new Refusal(refusal("DUPLICATE_SELECTION", "The selection counts the same session or "
          + "recorded content more than once; copies are not independent sessions", "Remove the "
          + "repeated positions listed in duplicates and compare each session once")
          .addData("duplicates", duplicates).build());
    }

    // ---- admission ----

    private void admit(List<Selection> selections) {
      var anchorChoices = new JsonArray();
      var unknownAnchors = new JsonArray();
      long occurrences = 0;
      for (var selection : selections) {
        var session = selection.directory.session();
        var origin = store.header().mirror() && store.header().origin() != null
            ? store.header().origin().sessions().get(session.id()) : null;
        if (session.openCapture() != null || origin != null && (!origin.complete() || origin.growing())) {
          selection.skip("NOT_FINISHED", session.openCapture() != null ? "the session is still recording; "
              + "compare it after its capture closes" : "the mirror has not finished copying this session",
              false);
          continue;
        }
        occurrences += selection.wpilogs.size();
        if (selection.wpilogs.size() > caps.membersPerSession()) {
          throw limit("members_per_session", selection.wpilogs.size(), caps.membersPerSession(), "Session "
              + selection.id() + " has " + selection.wpilogs.size() + " WPILOG members; a session union "
              + "admits at most " + caps.membersPerSession(), "Compare that session's files individually");
        }
        if (selection.request.anchor() != null) {
          var anchors = selection.wpilogs.stream().filter(f -> f.file().path().equals(selection.request.anchor())).toList();
          if (anchors.size() != 1) {
            var item = new JsonObject();
            item.addProperty("position", selection.request.position());
            item.addProperty("session_id", selection.id());
            item.addProperty("anchor_path", selection.request.anchor());
            item.add("wpilog_members", paths(selection.wpilogs));
            unknownAnchors.add(item);
            continue;
          }
          selection.anchor = anchors.get(0);
          selection.anchorBasis = "requested";
        } else if (selection.wpilogs.size() == 1) {
          selection.anchor = selection.wpilogs.get(0);
          selection.anchorBasis = "only_wpilog_member";
        } else if (selection.wpilogs.isEmpty()) {
          selection.skip("NO_WPILOG", "the session has no WPILOG member", true);
        } else {
          var item = new JsonObject();
          item.addProperty("position", selection.request.position());
          item.addProperty("session_id", selection.id());
          item.add("wpilog_members", paths(selection.wpilogs));
          anchorChoices.add(item);
        }
      }
      if (!unknownAnchors.isEmpty()) {
        throw new Refusal(refusal("UNKNOWN_ANCHOR", "An anchor_path does not name one WPILOG member of its "
            + "session", "Pass one of the listed wpilog_members as that session's anchor_path")
            .addData("choices", unknownAnchors).build());
      }
      if (!anchorChoices.isEmpty()) {
        throw new Refusal(refusal("ANCHOR_REQUIRED", "These sessions have several WPILOG members; the "
            + "member whose clock places scope and windows must be chosen", "Pass anchor_path for each "
            + "listed session, choosing one of its wpilog_members").addData("choices", anchorChoices).build());
      }
      if (occurrences > caps.memberOccurrences()) {
        throw limit("member_occurrences", occurrences, caps.memberOccurrences(), "The selection names "
            + occurrences + " WPILOG members; one comparison admits at most " + caps.memberOccurrences(),
            "Select fewer sessions");
      }
      long bytes = 0;
      for (var selection : selections) {
        if (selection.skipped()) continue;
        for (var file : selection.wpilogs) bytes += file.file().sizeBytes();
      }
      if (bytes > caps.memberBytes()) {
        throw limit("member_bytes", bytes, caps.memberBytes(), "The selected sessions' WPILOG members "
            + "hold " + bytes + " bytes; one comparison reads at most " + caps.memberBytes(),
            "Select fewer or shorter sessions, or compare the rest in another call");
      }
    }

    private static JsonArray paths(List<StoreCatalog.StoredFile> files) {
      var array = new JsonArray();
      for (var file : files) array.add(file.file().path());
      return array;
    }

    // ---- verification ----

    private void verify(List<Selection> selections) throws IOException {
      for (var selection : selections) {
        if (selection.skipped()) continue;
        try {
          for (var file : selection.wpilogs) {
            if (file.file().sha256() == null) throw new IOException("member " + file.file().path() + " has no recorded hash");
            var path = logManager.validateSourcePath(file.path());
            logManager.sourceScopes().recheckPath(path, scope);
            var stamp = FileSnapshot.of(path);
            PROOFS.verify(path, stamp, file.file().sha256());
            selection.stamps.put(path, stamp);
            selection.hashes.add(file.file().sha256());
          }
        } catch (SourceScopes.Failure failure) {
          throw failure;
        } catch (IOException | SecurityException unreadable) {
          selection.skip("UNREADABLE", "a member could not be verified: " + unreadable.getMessage(), false);
        }
      }
    }

    private void refuseDuplicates(List<Selection> selections) {
      var duplicates = new JsonArray();
      var verified = selections.stream().filter(s -> !s.skipped()).toList();
      for (int i = 0; i < verified.size(); i++) {
        for (int j = i + 1; j < verified.size(); j++) {
          var a = verified.get(i);
          var b = verified.get(j);
          var shared = new TreeSet<>(a.hashes);
          shared.retainAll(b.hashes);
          if (shared.isEmpty()) continue;
          var item = new JsonObject();
          item.addProperty("kind", a.hashes.equals(b.hashes) ? "equal_content" : "shared_member");
          var positions = new JsonArray();
          positions.add(a.request.position());
          positions.add(b.request.position());
          item.add("positions", positions);
          var ids = new JsonArray();
          ids.add(a.id());
          ids.add(b.id());
          item.add("session_ids", ids);
          var hashes = new JsonArray();
          shared.forEach(hashes::add);
          item.add("shared_sha256", hashes);
          duplicates.add(item);
        }
      }
      if (!duplicates.isEmpty()) throw duplicateRefusal(duplicates);
    }

    // ---- analysis ----

    private void analyze(Selection selection) throws IOException {
      try (var snapshot = catalog.openSession(store.header().id(), selection.request.id(),
          selection.anchor.file().path(), request.overlap())) {
        for (var stamp : selection.stamps.entrySet()) {
          if (!Objects.equals(stamp.getValue(), FileSnapshot.of(stamp.getKey()))) {
            selection.skip("CHANGED", "member " + stamp.getKey().getFileName() + " changed after its "
                + "content was verified", false);
            return;
          }
        }
        double[] bounds = bounds(snapshot);
        var view = new SessionView(snapshot, bounds[0], bounds[1], this);
        TimeScope timeScope;
        try {
          timeScope = TimeScope.fromArguments(view, null, TimeScope.relativeArguments(view, arguments));
        } catch (IllegalArgumentException unscoped) {
          selection.skip("SCOPE_UNAVAILABLE", unscoped.getMessage(), true);
          return;
        }
        if (timeScope.windows().size() > caps.windows()) {
          throw limit("windows", timeScope.windows().size(), caps.windows(), "Session " + selection.id()
              + " resolves to " + timeScope.windows().size() + " windows; a session takes at most "
              + caps.windows(), "Choose a narrower scope, such as one segment, or fewer windows");
        }
        selection.row = row(selection, snapshot, timeScope);
        if (timeScope.isEmpty()) {
          selection.skip("EMPTY_SCOPE", "the resolved scope is empty in this session", true);
          return;
        }
        var windows = micros(timeScope);
        for (var metric : request.metrics()) selection.resolved.put(metric.id(), resolve(selection, view, snapshot, metric));
        var keys = new LinkedHashMap<Key, Boolean>();
        for (var resolved : selection.resolved.values()) {
          if (resolved.key() != null) keys.merge(resolved.key(), resolved.metric().statistic().keepsValues(), Boolean::logicalOr);
        }
        var bySeries = new HashMap<Key, Series>();
        for (var key : keys.entrySet()) {
          bySeries.put(key.getKey(), read(selection, snapshot, key.getKey(), key.getValue(), windows));
        }
        var metrics = new JsonArray();
        for (var resolved : selection.resolved.values()) {
          var metric = resolved.metric();
          var series = resolved.key() == null ? null : bySeries.get(resolved.key());
          var value = series == null ? new Value(null, resolved.nullReason())
              : series.finite() == 0 ? new Value(null, empty(series, resolved.key()))
              : value(series, metric.statistic());
          selection.values.put(metric.id(), value);
          if (series != null) selection.series.put(metric.id(), series);
          metrics.add(metricRow(resolved, series, value));
        }
        selection.row.add("metrics", metrics);
        facts(selection, snapshot);
        if (selection.values.values().stream().allMatch(v -> v.value() == null)) {
          selection.skip("NO_DATA", "no requested metric has a value in this session", true);
        }
      } catch (ReadException failure) {
        if (failure.code() == ReadProblem.Code.RESOURCE_LIMIT) {
          throw new Refusal(refusal("RESOURCE_LIMIT", failure.getMessage(), "Select fewer or smaller "
              + "sessions, or compare their files individually").addProperty("limit_name", "session_index").build());
        }
        selection.skip(failure.code() == ReadProblem.Code.FILE_CHANGED ? "CHANGED" : "UNREADABLE",
            failure.getMessage(), false);
      } catch (UncheckedIOException failure) {
        var cause = failure.getCause();
        if (cause instanceof ReadException read && read.code() == ReadProblem.Code.RESOURCE_LIMIT) {
          throw new Refusal(refusal("RESOURCE_LIMIT", read.getMessage(), "Select fewer or smaller sessions")
              .addProperty("limit_name", "session_index").build());
        }
        selection.skip(cause instanceof ReadException read && read.code() == ReadProblem.Code.FILE_CHANGED
            ? "CHANGED" : "UNREADABLE", cause.getMessage(), false);
      } catch (IOException failure) {
        // The session union reports these only by message: overlapping recordings of one entry,
        // or members that declare an entry or struct differently.
        String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        boolean overlap = message.contains("verlapping");
        String code = overlap ? "OVERLAP" : message.startsWith("Conflicting") ? "INCOMPATIBLE_MEMBERS" : "UNREADABLE";
        selection.skip(code, overlap ? message + " (pass overlap 'keep_all' to keep every source)" : message, false);
      }
      if (selection.skipped()) selection.row = null;
    }

    /** SessionSnapshot's own rule: manifested data times through each member's recorded clock. */
    private double[] bounds(SessionSnapshot snapshot) {
      double low = Double.POSITIVE_INFINITY;
      double high = Double.NEGATIVE_INFINITY;
      for (var source : snapshot.sources()) {
        var file = byPath.get(source.path());
        if (file == null) continue;
        double start = file.file().minTimestampSec();
        double end = file.file().maxTimestampSec();
        for (var step : source.clock()) {
          start = step.toFpgaTime(start);
          end = step.toFpgaTime(end);
        }
        low = Math.min(low, start);
        high = Math.max(high, end);
      }
      return low <= high ? new double[] {low, high} : new double[] {0, 0};
    }

    private JsonObject row(Selection selection, SessionSnapshot snapshot, TimeScope timeScope) {
      var row = new JsonObject();
      row.addProperty("position", selection.request.position());
      row.addProperty("session_id", selection.id());
      if (!selection.request.id().equals(selection.id())) row.addProperty("requested_id", selection.request.id());
      var anchor = new JsonObject();
      anchor.addProperty("path", selection.anchor.file().path());
      anchor.addProperty("basis", selection.anchorBasis);
      row.add("anchor", anchor);
      var inputs = new JsonObject();
      var members = new JsonArray();
      for (var source : snapshot.sources()) {
        var member = new JsonObject();
        member.addProperty("path", source.path());
        member.addProperty("sha256", source.sha256());
        var file = byPath.get(source.path());
        if (file != null) member.addProperty("size_bytes", file.file().sizeBytes());
        member.addProperty("verified", true);
        members.add(member);
      }
      inputs.add("members", members);
      var excluded = new JsonArray();
      for (var source : snapshot.excludedSources()) {
        var member = new JsonObject();
        member.addProperty("path", source.path());
        member.addProperty("reason", source.reason());
        excluded.add(member);
        warnings.add("Session " + selection.id() + ": member " + Path.of(source.path()).getFileName()
            + " was excluded (" + source.reason() + ")");
      }
      inputs.add("excluded_members", excluded);
      inputs.addProperty("overlap", request.overlap() == SessionSnapshot.Overlap.REJECT ? "reject" : "keep_all");
      row.add("inputs", inputs);
      row.add("scope", timeScope.toJson());
      row.addProperty("clock_basis", "the anchor member's clock; other members by their recorded alignment");
      if (snapshot.truncated()) row.addProperty("log_truncation", snapshot.truncationMessage());
      return row;
    }

    private Resolved resolve(Selection selection, SessionView view, SessionSnapshot snapshot, MetricRequest metric) {
      String entry;
      String field = metric.field();
      boolean derived = false;
      String basis;
      if (metric.role() != null) {
        var resolution = SignalResolver.resolve(view, metric.role());
        if (resolution.needsConfirmation() || resolution.ambiguous()) {
          var item = new JsonObject();
          item.addProperty("session_id", selection.id());
          item.addProperty("metric_id", metric.id());
          item.addProperty("role", metric.roleWire());
          item.add("resolution", resolution.toJson(10));
          ambiguous.add(item);
          return Resolved.omitted(metric, "the role has no unique published entry in this session");
        }
        if (resolution.entries().isEmpty()) return Resolved.omitted(metric, SignalResolver.unresolvedReason(resolution, "name"));
        entry = resolution.entries().get(0);
        basis = resolution.basis();
        derived = metric.role() == SignalResolver.Role.LOOP_TIME_FULL && "/Timestamp".equals(entry);
      } else {
        entry = metric.name();
        if (!snapshot.entries().containsKey(entry) && field == null) {
          String prefix = NumericSignal.longestEntryPrefix(view, entry);
          if (prefix != null) {
            field = entry.substring(prefix.length());
            entry = prefix;
          }
        }
        if (!snapshot.entries().containsKey(entry)) return Resolved.omitted(metric, "entry " + metric.name() + " is not in this session");
        basis = "named entry";
      }
      var info = snapshot.entries().get(entry);
      String type = info.type();
      var path = field == null ? FieldPath.ROOT : FieldPath.parse(field);
      if (path.hasWildcard()) throw invalid("Metric " + metric.id() + " selects every element ([*]); choose one index");
      String structName = StructSchemas.structName(type);
      try {
        NumericSignal.validateProjectionType(view, entry, type, structName, path);
      } catch (IllegalArgumentException mismatch) {
        throw incompatible("Metric " + metric.id() + " in session " + selection.id() + ": "
            + mismatch.getMessage(), List.of());
      }
      var declared = structName != null ? NumericSignal.angleUnit(snapshot.schemas(), structName, path) : NumericSignal.AngleUnit.NONE;
      var angle = declared != NumericSignal.AngleUnit.NONE ? declared
          : metric.angle() != null ? metric.angle() : NumericSignal.AngleUnit.NONE;
      if (declared != NumericSignal.AngleUnit.NONE && metric.angle() != null && metric.angle() != declared) {
        throw incompatible("Metric " + metric.id() + ": " + entry + path + " is declared in "
            + declared.wire() + ", not " + metric.angle().wire(), List.of());
      }
      if (angle != NumericSignal.AngleUnit.NONE && metric.statistic().ordered()) {
        throw incompatible("Metric " + metric.id() + " is an angle in session " + selection.id()
            + "; angles take only mean (a circular mean) and std_dev (its spread), not "
            + metric.statistic().wire(), List.of());
      }
      String unit;
      String unitBasis;
      if (derived) {
        unit = "ms";
        unitBasis = "derived from /Timestamp microseconds, as analyze_loop_timing derives loop periods";
      } else {
        var logged = SignalResolver.visualizationUnit(view, entry, metric.role());
        unit = logged.get("symbol").isJsonNull() ? null : logged.get("symbol").getAsString();
        unitBasis = logged.get("basis").getAsString();
        if (unit == null && metric.role() == SignalResolver.Role.LOOP_TIME_FULL && entry.endsWith("FullCycleMS")) {
          unit = "ms";
          unitBasis = "published convention of resolved loop_time_full";
        }
      }
      if (metric.unit() != null) {
        if (unit != null && !unit.equals(metric.unit())) {
          throw incompatible("Metric " + metric.id() + ": the caller's unit " + metric.unit() + " contradicts "
              + unit + " (" + unitBasis + ") in session " + selection.id() + "; no conversion is made", List.of());
        }
        if (unit == null) {
          unit = metric.unit();
          unitBasis = "caller_supplied";
        }
      }
      return new Resolved(metric, new Key(entry, path.isRoot() ? null : path.toString(), derived, angle), type,
          unit, unitBasis == null ? "unknown" : unitBasis, basis, null);
    }

    private Series read(Selection selection, SessionSnapshot snapshot, Key key, boolean keep,
        List<ReadQuery.Window> windows) throws IOException {
      var query = new ReadQuery(key.entry(), windows, ReadQuery.Order.TIME, 4096, 4 << 20);
      if (key.field() != null) query = query.project(key.field());
      var info = snapshot.entries().get(key.entry());
      var series = new Series(keep, key.angle(), info != null && DataQuality.sampled(info.metadata()));
      try (var cursor = snapshot.read(query)) {
        int window = Integer.MIN_VALUE;
        Double previous = null;
        while (true) {
          var batch = cursor.next();
          visit(batch.recordsVisited());
          reading.run();
          series.problems += batch.problems().size();
          for (var sample : batch.samples()) {
            int at = windows.isEmpty() ? 0 : TimeWindows.containing(windows, sample.timestampUs());
            if (at != window) {
              series.quality.segment();
              window = at;
              previous = null;
            }
            Double number = FieldPath.toNumber(sample.value());
            if (number == null) {
              series.without++;
              continue;
            }
            double value = number;
            if (key.derived()) {
              Double last = previous;
              previous = number;
              if (last == null) continue;
              value = (number - last) / 1000.0;
            }
            if (!series.add(sample.timestampUs() / 1e6, value, caps.keptValues())) {
              throw limit("kept_values", (long) caps.keptValues() + 1, caps.keptValues(), "Session "
                  + selection.id() + " has more than " + caps.keptValues() + " values of " + key.entry()
                  + " in scope; median and percentiles keep at most that many per session and metric",
                  "Narrow scope or windows, or use mean, std_dev, min or max, which keep no values");
            }
          }
          if (batch.complete()) break;
        }
      }
      return series;
    }

    /** Why a series that was read has no finite value: no record, no number there, or all non-finite. */
    private static String empty(Series series, Key key) {
      String at = key.entry() + (key.field() == null ? "" : key.field());
      if (series.without > 0) return "none of the " + series.without + " records of " + key.entry()
          + " in this session's scope has a number at " + at + "; check the field or index";
      if (series.nonFinite > 0) return "all " + series.nonFinite + " values of " + at
          + " in this session's scope are NaN or infinite";
      return key.derived() ? "fewer than two /Timestamp records in this session's scope, so no loop period"
          : "no record of " + key.entry() + " in this session's scope";
    }

    private JsonObject metricRow(Resolved resolved, Series series, Value value) {
      var metric = resolved.metric();
      var row = new JsonObject();
      row.addProperty("metric_id", metric.id());
      row.addProperty("statistic", metric.statistic().wire());
      if (metric.role() != null) row.addProperty("role", metric.roleWire());
      row.addProperty("entry", resolved.key() == null ? metric.name() : resolved.key().entry());
      if (resolved.key() != null && resolved.key().field() != null) row.addProperty("field", resolved.key().field());
      if (resolved.basis() != null) row.addProperty("resolution_basis", resolved.basis());
      row.addProperty("value", value.value());
      row.addProperty("unit", resolved.unit());
      row.addProperty("unit_basis", resolved.unitBasis() == null ? "unknown" : resolved.unitBasis());
      if (resolved.unit() == null && resolved.key() != null) {
        row.addProperty("unit_reason", "no unit is logged or published for this entry; pass unit if it is known");
      }
      if (resolved.key() != null && resolved.key().angle() != NumericSignal.AngleUnit.NONE) {
        row.addProperty("angle", resolved.key().angle().wire());
      }
      row.addProperty("sample_count", series == null ? 0 : series.finite());
      row.addProperty("non_finite_count", series == null ? 0 : series.nonFinite);
      row.addProperty("records_without_value", series == null ? 0 : series.without);
      if (series != null && series.problems > 0) row.addProperty("unreadable_records", series.problems);
      if (value.value() == null) row.addProperty("null_reason", value.nullReason());
      if (series != null) {
        var quality = series.quality.result();
        row.add("data_quality", quality.toJson());
        if (series.finite() > 0) worst = worst == null || quality.qualityScore() < worst.qualityScore() ? quality : worst;
      }
      return row;
    }

    private DataQuality worst;

    // ---- confounders ----

    private void facts(Selection selection, SessionSnapshot snapshot) throws IOException {
      for (var fact : FACTS) {
        var spans = new ArrayList<Span>();
        String explicit = request.confounderEntries().get(fact);
        var entries = new ArrayList<String>();
        if (explicit != null) {
          if (snapshot.entries().containsKey(explicit)) entries.add(explicit);
          else selection.unknown.put(fact, "entry " + explicit + " (confounder_entries) is not in this session");
        } else {
          for (var info : snapshot.entries().values()) {
            if (!"string".equals(info.type())) continue;
            String lower = info.name().toLowerCase(Locale.ROOT);
            boolean match = switch (fact) {
              case "robot" -> lower.endsWith("/systemstats/serialnumber");
              case "git_sha" -> MatchTimeline.leafName(info.name()).equals("gitsha");
              case "event" -> lower.endsWith("/driverstation/eventname") || lower.endsWith("/fmsinfo/eventname");
              default -> false;
            };
            if (match) entries.add(info.name());
          }
        }
        for (var entry : entries) spans.addAll(values(snapshot, entry, fact));
        if (explicit == null && fact.equals("robot") && selection.directory.robot() != null
            && selection.directory.robot().serialNumber() != null) {
          spans.add(new Span("manifest", null, selection.directory.robot().serialNumber(), null, null, 0,
              "store manifest robot (" + selection.directory.robot().basis() + ")"));
        }
        if (explicit == null && fact.equals("event") && selection.directory.session().event() != null) {
          spans.add(new Span("manifest", null, selection.directory.session().event(), null, null, 0,
              "store manifest session event"));
        }
        if (spans.size() > caps.factValues()) {
          throw limit("fact_values", spans.size(), caps.factValues(), "Session " + selection.id() + " records "
              + spans.size() + " distinct " + fact + " values or spans; the evidence is capped at "
              + caps.factValues() + " per fact and session", "Name a narrower entry in confounder_entries, or "
              + "compare fewer sessions");
        }
        if (spans.isEmpty() && !selection.unknown.containsKey(fact)) {
          selection.unknown.put(fact, switch (fact) {
            case "robot" -> "no SystemStats/SerialNumber entry and no manifest robot serial";
            case "git_sha" -> "no string entry named GitSHA (get_code_metadata's convention)";
            case "event" -> "no DriverStation or FMSInfo EventName entry and no manifest event";
            default -> "no published convention identifies the " + fact + "; name its entry in confounder_entries."
                + fact + " if the log records one";
          });
        }
        selection.facts.put(fact, spans);
      }
    }

    private List<Span> values(SessionSnapshot snapshot, String entry, String fact) throws IOException {
      var spans = new LinkedHashMap<String, Span>();
      try (var cursor = snapshot.read(new ReadQuery(entry, List.of(), ReadQuery.Order.TIME, 4096, 4 << 20))) {
        while (true) {
          var batch = cursor.next();
          visit(batch.recordsVisited());
          for (var sample : batch.samples()) {
            var raw = sample.value();
            if (!(raw instanceof String || raw instanceof Number || raw instanceof Boolean)) continue;
            String text = String.valueOf(raw);
            double at = sample.timestampUs() / 1e6;
            var old = spans.get(text);
            spans.put(text, old == null ? new Span("entry", entry, text, at, at, 1, null)
                : new Span("entry", entry, text, old.first(), at, old.count() + 1, null));
            if (spans.size() > caps.factValues()) {
              throw limit("fact_values", spans.size(), caps.factValues(), "Entry " + entry + " records more "
                  + "than " + caps.factValues() + " distinct " + fact + " values in one session", "Name a "
                  + "narrower entry in confounder_entries, or compare fewer sessions");
            }
          }
          if (batch.complete()) break;
        }
      }
      return List.copyOf(spans.values());
    }

    private JsonObject confounders(List<Selection> analyzed) {
      var out = new JsonObject();
      for (var fact : FACTS) {
        var o = new JsonObject();
        String explicit = request.confounderEntries().get(fact);
        o.addProperty("basis", explicit != null ? "entry " + explicit + " named in confounder_entries" : switch (fact) {
          case "robot" -> "SystemStats/SerialNumber entries and the store manifest's robot";
          case "git_sha" -> "string entries named GitSHA, every value (get_code_metadata's convention)";
          case "event" -> "DriverStation or FMSInfo EventName entries and the store manifest's event";
          default -> "no published convention; only an entry named in confounder_entries";
        });
        var values = new LinkedHashMap<String, LinkedHashSet<String>>();
        var evidence = new JsonArray();
        var changed = new JsonArray();
        var conflicting = new JsonArray();
        var unknown = new JsonArray();
        for (var selection : analyzed) {
          var spans = selection.facts.getOrDefault(fact, List.of());
          if (spans.isEmpty()) {
            var item = new JsonObject();
            item.addProperty("session_id", selection.id());
            item.addProperty("reason", selection.unknown.getOrDefault(fact, "not recorded"));
            unknown.add(item);
            continue;
          }
          var bySource = new LinkedHashMap<String, Set<String>>();
          for (var span : spans) {
            values.computeIfAbsent(span.value(), k -> new LinkedHashSet<>()).add(selection.id());
            bySource.computeIfAbsent(span.source() + ":" + span.entry(), k -> new LinkedHashSet<>()).add(span.value());
            var item = new JsonObject();
            item.addProperty("session_id", selection.id());
            item.addProperty("source", span.source());
            if (span.entry() != null) item.addProperty("entry", span.entry());
            item.addProperty("value", span.value());
            if (span.first() != null) {
              item.addProperty("first_sec", span.first());
              item.addProperty("last_sec", span.last());
              item.addProperty("sample_count", span.count());
            }
            if (span.basis() != null) item.addProperty("basis", span.basis());
            evidence.add(item);
          }
          if (bySource.values().stream().anyMatch(set -> set.size() > 1)) changed.add(selection.id());
          if (bySource.size() > 1 && new HashSet<>(bySource.values()).size() > 1) conflicting.add(selection.id());
        }
        o.addProperty("distinct_value_count", values.size());
        var array = new JsonArray();
        for (var value : values.entrySet()) {
          var item = new JsonObject();
          item.addProperty("value", value.getKey());
          var sessions = new JsonArray();
          value.getValue().forEach(sessions::add);
          item.add("sessions", sessions);
          array.add(item);
        }
        o.add("values", array);
        o.add("evidence", evidence);
        o.add("changed_within", changed);
        o.add("conflicting_sessions", conflicting);
        o.add("unknown_sessions", unknown);
        out.add(fact, o);
      }
      long bytes = out.toString().getBytes(StandardCharsets.UTF_8).length;
      if (bytes > caps.confounderBytes()) {
        throw limit("confounder_bytes", bytes, caps.confounderBytes(), "The confounder evidence encodes to "
            + bytes + " bytes; it is capped at " + caps.confounderBytes(), "Compare fewer sessions, or name "
            + "narrower confounder_entries");
      }
      return out;
    }

    // ---- compatibility and results ----

    private void checkCompatibility(List<Selection> selections) {
      for (var metric : request.metrics()) {
        var units = new LinkedHashMap<String, List<String>>();
        var types = new LinkedHashMap<String, List<String>>();
        var angles = new LinkedHashMap<String, List<String>>();
        for (var selection : selections) {
          if (selection.skipped()) continue;
          var resolved = selection.resolved.get(metric.id());
          if (resolved == null || resolved.key() == null) continue;
          if (resolved.unit() != null) units.computeIfAbsent(resolved.unit(), k -> new ArrayList<>()).add(selection.id());
          if (metric.role() == null) types.computeIfAbsent(resolved.type(), k -> new ArrayList<>()).add(selection.id());
          angles.computeIfAbsent(resolved.key().angle().wire(), k -> new ArrayList<>()).add(selection.id());
        }
        for (var kind : List.of(Map.entry("unit", units), Map.entry("type", types), Map.entry("angle", angles))) {
          if (kind.getValue().size() < 2) continue;
          var details = new ArrayList<JsonObject>();
          for (var group : kind.getValue().entrySet()) {
            var item = new JsonObject();
            item.addProperty(kind.getKey(), group.getKey());
            var ids = new JsonArray();
            group.getValue().forEach(ids::add);
            item.add("session_ids", ids);
            details.add(item);
          }
          throw incompatible("Metric " + metric.id() + " has different " + kind.getKey() + "s across the "
              + "selected sessions; equal names are not assumed to measure the same quantity", details);
        }
      }
    }

    private JsonElement result(List<Selection> selections) {
      var analyzed = selections.stream().filter(s -> !s.skipped()).toList();
      int requested = selections.size();
      int skippedCount = requested - analyzed.size();
      var skipped = new JsonArray();
      for (var selection : selections) {
        if (selection.skipped()) {
          var item = new JsonObject();
          item.addProperty("section", "session " + selection.id());
          item.addProperty("kind", "session");
          item.addProperty("position", selection.request.position());
          item.addProperty("session_id", selection.id());
          item.addProperty("code", selection.skipCode);
          item.addProperty("reason", selection.skipReason);
          if (!selection.values.isEmpty()) {
            var reasons = new JsonArray();
            for (var value : selection.values.entrySet()) {
              var reason = new JsonObject();
              reason.addProperty("metric_id", value.getKey());
              reason.addProperty("null_reason", value.getValue().nullReason());
              reasons.add(reason);
            }
            item.add("metric_reasons", reasons);
          }
          skipped.add(item);
        } else {
          for (var value : selection.values.entrySet()) {
            if (value.getValue().value() != null) continue;
            var item = new JsonObject();
            item.addProperty("section", "session " + selection.id() + " metric " + value.getKey());
            item.addProperty("kind", "metric");
            item.addProperty("session_id", selection.id());
            item.addProperty("metric_id", value.getKey());
            item.addProperty("reason", value.getValue().nullReason());
            skipped.add(item);
          }
        }
      }
      boolean omissions = analyzed.stream().anyMatch(s -> s.values.values().stream().anyMatch(v -> v.value() == null));
      var labels = request.metrics().stream().map(MetricRequest::label).distinct().toList();
      ResponseBuilder builder;
      if (!analyzed.isEmpty()) {
        builder = ResponseBuilder.success();
        if (skippedCount > 0 || omissions) builder.status(ResultContract.Status.PARTIAL);
      } else if (selections.stream().allMatch(s -> s.absence)) {
        builder = ResponseBuilder.noMatch("None of the " + requested + " selected sessions has a usable "
            + "value for " + String.join(", ", labels) + " in the requested scope; skipped gives each "
            + "session's reasons").lookedFor(labels)
            .hint("Check the entry names with list_entries on a session's member, or widen the scope");
      } else {
        builder = refusal("NO_ANALYZABLE_SESSION", "No selected session could be analyzed; each one's "
            + "reason is in skipped", "Resolve the listed reasons (unreadable, changed or still-recording "
            + "sessions) and retry, or select other sessions");
      }
      var source = new JsonObject();
      source.addProperty("scope_id", scope.id());
      source.addProperty("kind", scope.kind());
      source.addProperty("store_id", store.header().id());
      source.addProperty("path", scope.root().toString());
      builder.addData("source", source)
          .addProperty("requested_session_count", requested)
          .addProperty("analyzed_session_count", analyzed.size())
          .addProperty("skipped_session_count", skippedCount)
          .addData("metrics", metricsEcho());
      var rows = new JsonArray();
      analyzed.forEach(s -> rows.add(s.row));
      builder.addData("sessions", rows);
      builder.addData("summary", summary(selections, analyzed));
      if (request.pool()) builder.addData("pooled", pooled(analyzed));
      builder.addData("confounders", confounders(analyzed));
      if (!skipped.isEmpty()) builder.addData("skipped", skipped);
      builder.addData("inputs", inputs(selections));
      warnings.forEach(builder::addWarning);
      if (worst != null) {
        builder.addDirectives(AnalysisDirectives.fromQuality(worst)
            .addGuidance("Each session counts once in summary: a mean of session means is not the mean "
                + "of all samples. pooled appears only with pool_samples and is sample-weighted.")
            .addGuidance("confounders lists recorded differences (code, robot, event, battery, firmware); "
                + "they are observations to check, not proven causes, and an unknown fact is not equality.")
            .addGuidance("Sessions from one season are not a random sample of a population, and distinct "
                + "sessions are not proven independent; no significance test is made.")
            .addFollowup("Use get_statistics on a session member's path for one session's full statistics")
            .addFollowup("Use get_code_metadata on a member's path for its build details"));
      }
      return builder.build();
    }

    private JsonArray metricsEcho() {
      var array = new JsonArray();
      for (var metric : request.metrics()) {
        var item = new JsonObject();
        item.addProperty("id", metric.id());
        item.addProperty("statistic", metric.statistic().wire());
        if (metric.name() != null) item.addProperty("name", metric.name());
        if (metric.role() != null) item.addProperty("role", metric.roleWire());
        if (metric.field() != null) item.addProperty("field", metric.field());
        if (metric.angle() != null) item.addProperty("angle", metric.angle().wire());
        if (metric.unit() != null) item.addProperty("unit", metric.unit());
        array.add(item);
      }
      return array;
    }

    private JsonArray summary(List<Selection> selections, List<Selection> analyzed) {
      var out = new JsonArray();
      for (var metric : request.metrics()) {
        var item = new JsonObject();
        item.addProperty("metric_id", metric.id());
        item.addProperty("statistic", metric.statistic().wire());
        var contributing = new JsonArray();
        var excluded = new JsonArray();
        var values = new ArrayList<Double>();
        String unit = null;
        NumericSignal.AngleUnit angle = NumericSignal.AngleUnit.NONE;
        for (var selection : selections) {
          var value = selection.values.get(metric.id());
          if (!selection.skipped() && value != null && value.value() != null) {
            contributing.add(selection.id());
            values.add(value.value());
            var resolved = selection.resolved.get(metric.id());
            if (resolved.unit() != null) unit = resolved.unit();
            angle = resolved.key().angle();
            continue;
          }
          var reason = new JsonObject();
          reason.addProperty("session_id", selection.id());
          reason.addProperty("reason", selection.skipped() ? selection.skipReason
              : value == null ? "not analyzed" : value.nullReason());
          excluded.add(reason);
        }
        item.addProperty("unit", unit);
        item.addProperty("session_count", values.size());
        item.add("contributing_sessions", contributing);
        item.add("excluded_sessions", excluded);
        double[] data = values.stream().mapToDouble(Double::doubleValue).toArray();
        String what = "among the contributing sessions";
        if (data.length == 0) {
          for (var statistic : Statistic.values()) item.add(statistic.wire(), com.google.gson.JsonNull.INSTANCE);
          item.addProperty("null_reason", "no selected session contributed a value; excluded_sessions gives each reason");
        } else if (angle != NumericSignal.AngleUnit.NONE && metric.statistic() == Statistic.MEAN) {
          // Session means of an angle are directions: summarized on the circle, not by order.
          var directions = new SummaryStatistics.Circular(angle.period);
          for (double value : data) directions.add(value);
          var mean = circular(Statistic.MEAN, directions, what);
          var spread = circular(Statistic.STD_DEV, directions, what);
          item.addProperty("mean", mean.value());
          item.addProperty("std_dev", spread.value());
          item.addProperty("circular", true);
          for (var statistic : List.of("min", "max", "median", "p5", "p95")) item.add(statistic, com.google.gson.JsonNull.INSTANCE);
          item.addProperty("order_statistics_reason", "angles have no order: only a circular mean and spread summarize them");
          if (mean.value() == null) item.addProperty("null_reason", mean.nullReason());
        } else {
          var sorted = data.clone();
          Arrays.sort(sorted);
          var summary = SummaryStatistics.of(data);
          var reasons = new JsonObject();
          for (var statistic : Statistic.values()) {
            var value = linear(statistic, summary, sorted, what);
            item.addProperty(statistic.wire(), value.value());
            if (value.value() == null) reasons.addProperty(statistic.wire(), value.nullReason());
          }
          if (reasons.size() > 0) item.add("null_reasons", reasons);
        }
        out.add(item);
      }
      return out;
    }

    private JsonArray pooled(List<Selection> analyzed) {
      var out = new JsonArray();
      for (var metric : request.metrics()) {
        var pool = new Pool();
        NumericSignal.AngleUnit angle = NumericSignal.AngleUnit.NONE;
        String unit = null;
        for (var selection : analyzed) {
          var series = selection.series.get(metric.id());
          if (series == null) continue;
          if (metric.statistic().keepsValues() && pool.keptCount + series.keptCount > caps.pooledValues()) {
            throw limit("pooled_values", (long) pool.keptCount + series.keptCount, caps.pooledValues(),
                "Pooling metric " + metric.id() + " would keep more than " + caps.pooledValues() + " values",
                "Turn pool_samples off, narrow the scope, or pool fewer sessions");
          }
          pool.add(series);
          angle = series.angle;
          var resolved = selection.resolved.get(metric.id());
          if (resolved.unit() != null) unit = resolved.unit();
        }
        var item = new JsonObject();
        item.addProperty("metric_id", metric.id());
        item.addProperty("statistic", metric.statistic().wire());
        item.addProperty("weighting", "sample_weighted");
        item.addProperty("session_count", pool.sessions);
        item.addProperty("sample_count", pool.finite());
        item.addProperty("unit", unit);
        String what = "in the pooled samples";
        Value value;
        if (angle != NumericSignal.AngleUnit.NONE) {
          value = circular(metric.statistic(), pool.circular, what);
        } else {
          double[] sorted = null;
          if (metric.statistic().keepsValues()) {
            sorted = Arrays.copyOf(pool.kept, pool.keptCount);
            Arrays.sort(sorted);
          }
          value = linear(metric.statistic(), pool.summary, sorted, what);
        }
        item.addProperty("value", value.value());
        if (value.value() == null) item.addProperty("null_reason", value.nullReason());
        out.add(item);
      }
      return out;
    }

    private JsonObject inputs(List<Selection> selections) {
      var inputs = new JsonObject();
      inputs.addProperty("scope_id", scope.id());
      inputs.addProperty("store_id", store.header().id());
      inputs.addProperty("kind", scope.kind());
      var sessions = new JsonArray();
      for (var selection : selections) {
        var item = new JsonObject();
        item.addProperty("position", selection.request.position());
        item.addProperty("session_id", selection.request.id());
        item.addProperty("anchor_path", selection.anchor == null ? selection.request.anchor()
            : selection.anchor.file().path());
        sessions.add(item);
      }
      inputs.add("sessions", sessions);
      for (var key : List.of("scope", "start_time", "end_time", "last_seconds", "windows")) {
        if (arguments.has(key) && !arguments.get(key).isJsonNull()) inputs.add(key, arguments.get(key));
      }
      inputs.addProperty("overlap", request.overlap() == SessionSnapshot.Overlap.REJECT ? "reject" : "keep_all");
      inputs.addProperty("pool_samples", request.pool());
      inputs.addProperty("record_visits", visits);
      return inputs;
    }
  }

  /** Scope windows on the anchor clock, in the read layer's microseconds (as other session reads round). */
  private static List<ReadQuery.Window> micros(TimeScope scope) {
    if (scope.isAll() && scope.requestedStart() == null && scope.requestedEnd() == null) return List.of();
    var out = new ArrayList<ReadQuery.Window>();
    for (var window : scope.windows()) {
      long start = Math.round(window.start() * 1e6);
      long end = Math.max(start, Math.round(window.end() * 1e6));
      boolean inclusive = window.endInclusive();
      if (!out.isEmpty()) {
        var last = out.get(out.size() - 1);
        if (start < last.endUs() || start == last.endUs() && last.endInclusive()) {
          // Rounding can make neighbors touch; their union is the same set of microseconds.
          boolean keep = end > last.endUs() ? inclusive : end < last.endUs() ? last.endInclusive()
              : inclusive || last.endInclusive();
          out.set(out.size() - 1, new ReadQuery.Window(last.startUs(), Math.max(end, last.endUs()), keep));
          continue;
        }
      }
      out.add(new ReadQuery.Window(start, end, inclusive));
    }
    return out;
  }
}
