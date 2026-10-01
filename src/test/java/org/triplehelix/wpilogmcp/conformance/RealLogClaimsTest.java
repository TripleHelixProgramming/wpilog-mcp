/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.HttpTransport;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.tools.ExportTools;
import org.triplehelix.wpilogmcp.tools.WpilogTools;

/**
 * The documentation's claims, checked against a live instance of the service on real logs: the
 * server runs on its HTTP transport, the test calls it as a client would, and each answer is
 * compared with a fact established independently of the tool under test: the simulated logs'
 * manifest (written by a different parser), facts recorded about the real logs, the test's own
 * decoding of a REV log's bytes, or a second tool that reports the same fact another way.
 *
 * <p>Opt-in, because it needs the real logs:
 * <pre>
 * ./gradlew test --tests '*RealLogClaimsTest*' -PconformanceLogDir=$HOME/th/riologs
 * </pre>
 * A claim whose precondition the available logs do not meet is skipped and reported as not
 * verifiable, never passed. The report is {@code build/reports/conformance/claims.txt}.
 */
@DisplayName("Documented claims against the live service on real logs (opt-in)")
class RealLogClaimsTest {
  static final Path REPORT = Path.of("build", "reports", "conformance", "claims.txt");
  static final Duration CALL_TIMEOUT = Duration.ofMinutes(5);

  // Real logs and what is known about them independently of the server (see the memory notes
  // and doc/ROBUSTNESS_REVIEW.md)
  static final String PRACTICE = "akit_26-09-30_00-10-26.wpilog"; // brownouts, CAN counters, truncated
  static final String BENCH = "akit_26-09-29_23-11-20.wpilog"; // practice: no FMS, autonomous never true
  static final String Q10 = "vache/session_23/akit_26-03-21_16-29-56_vache_q10.wpilog";
  static final String Q10_REVLOG = "REV_20260321_162932.revlog"; // named by the roboRIO clock, UTC
  static final String E4 = "vache/session_55/akit_26-03-22_18-15-22_vache_e4.wpilog";
  static final String E4_REPLAY = "vache/session_55/akit_26-03-22_18-15-22_vache_e4_sim.wpilog";
  static final String SIM_MANIFEST = "sim/MANIFEST.md";

  static Path logDir;
  static HttpTransport transport;
  static HttpClient http;
  static String session;
  static int port;
  static Path exportDir;
  static Path savedExportDir;
  static java.util.List<Path> savedLogDirs;
  static final List<String> report = new ArrayList<>();
  static int nextId = 1;

  @BeforeAll
  static void start() throws Exception {
    var property = System.getProperty("conformance.logdir");
    Assumptions.assumeTrue(property != null && !property.isBlank(),
        "conformance.logdir not set; run with -PconformanceLogDir=/path/to/logs");
    logDir = Path.of(property).toAbsolutePath().normalize();
    Assumptions.assumeTrue(Files.isDirectory(logDir), "not a directory: " + logDir);
    var logManager = LogManager.getInstance();
    logManager.unloadAllLogs();
    logManager.addAllowedDirectory(logDir);
    savedLogDirs = LogDirectory.getInstance().getLogDirectories();
    LogDirectory.getInstance().setLogDirectory(logDir.toString());
    exportDir = Files.createTempDirectory("claims-export");
    savedExportDir = ExportTools.getExportDirectory();
    ExportTools.setExportDirectory(exportDir.toString());
    var registry = new ToolRegistry();
    WpilogTools.registerAll(registry);
    transport = new HttpTransport(registry, 0);
    transport.start();
    port = transport.getPort();
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    var init = post("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{}}", null);
    assertEquals(200, init.statusCode(), init.body());
    session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
  }

  @AfterAll
  static void stop() throws IOException {
    if (transport != null) transport.stop();
    if (savedExportDir != null) ExportTools.setExportDirectory(savedExportDir.toString());
    if (savedLogDirs != null) {
      LogDirectory.getInstance().setLogDirectories(
          savedLogDirs.stream().map(Path::toString).toList());
    }
    LogManager.getInstance().unloadAllLogs();
    if (!report.isEmpty()) {
      Files.createDirectories(REPORT.getParent());
      Files.write(REPORT, report, StandardCharsets.UTF_8);
    }
  }

  // ==================== the client ====================

  static HttpResponse<String> post(String body, String sessionId) throws Exception {
    var builder = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + "/mcp"))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .timeout(CALL_TIMEOUT)
        .POST(HttpRequest.BodyPublishers.ofString(body));
    if (sessionId != null) builder.header("Mcp-Session-Id", sessionId);
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** Calls a tool through the transport and returns the tool's own JSON result. */
  static JsonObject call(String tool, JsonObject args) throws Exception {
    var request = new JsonObject();
    request.addProperty("jsonrpc", "2.0");
    request.addProperty("id", nextId++);
    request.addProperty("method", "tools/call");
    var params = new JsonObject();
    params.addProperty("name", tool);
    params.add("arguments", args);
    request.add("params", params);
    var response = post(request.toString(), session);
    assertEquals(200, response.statusCode(), tool + ": " + response.body());
    var envelope = JsonParser.parseString(response.body()).getAsJsonObject();
    assertFalse(envelope.has("error"), tool + ": " + envelope);
    var result = envelope.getAsJsonObject("result");
    var text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    return JsonParser.parseString(text).getAsJsonObject();
  }

  static JsonObject call(String tool, Object... keyValues) throws Exception {
    var args = new JsonObject();
    for (int i = 0; i < keyValues.length; i += 2) {
      var key = (String) keyValues[i];
      var value = keyValues[i + 1];
      if (value instanceof Number n) args.addProperty(key, n);
      else if (value instanceof Boolean b) args.addProperty(key, b);
      else if (value instanceof JsonElement e) args.add(key, e);
      else args.addProperty(key, value.toString());
    }
    return call(tool, args);
  }

  static String path(String relative) {
    return logDir.resolve(relative).toString();
  }

  /** Records the claim as not verifiable here and skips, when its precondition fails. */
  static void needs(boolean condition, String claim, String why) {
    if (!condition) {
      report.add("NOT VERIFIABLE  " + claim + " — " + why);
      Assumptions.assumeTrue(false, claim + ": " + why);
    }
  }

  static void needsLog(String relative, String claim) {
    needs(Files.exists(logDir.resolve(relative)), claim, "no log " + relative);
  }

  static void verified(String claim, String evidence) {
    report.add("VERIFIED        " + claim + " — " + evidence);
  }

  static double num(JsonObject o, String key) {
    assertTrue(o.has(key) && o.get(key).isJsonPrimitive(), "no number " + key + " in " + o);
    return o.get(key).getAsDouble();
  }

  static String str(JsonObject o, String key) {
    assertTrue(o.has(key) && o.get(key).isJsonPrimitive(), "no string " + key + " in " + o);
    return o.get(key).getAsString();
  }

  /** Entries whose name contains the pattern and whose type is one of the given, by sample count. */
  static List<String> entriesOfType(String log, String pattern, int minSamples, String... types)
      throws Exception {
    var listed = call("list_entries", "path", path(log), "pattern", pattern);
    var out = new ArrayList<String>();
    if (!listed.has("entries")) return out;
    var wanted = List.of(types);
    var entries = new ArrayList<JsonObject>();
    for (var e : listed.getAsJsonArray("entries")) entries.add(e.getAsJsonObject());
    entries.sort((a, b) -> Double.compare(num(b, "sample_count"), num(a, "sample_count")));
    for (var e : entries) {
      if (wanted.contains(str(e, "type")) && num(e, "sample_count") >= minSamples) out.add(str(e, "name"));
    }
    return out;
  }

  static String status(JsonObject o) {
    return o.has("status") ? o.get("status").getAsString() : "(none)";
  }

  /** Every key anywhere in the element, with its value when primitive. */
  static void walk(JsonElement e, String prefix, Map<String, JsonElement> out) {
    if (e == null) return;
    if (e.isJsonObject()) {
      for (var entry : e.getAsJsonObject().entrySet()) {
        out.put(prefix + entry.getKey(), entry.getValue());
        walk(entry.getValue(), prefix + entry.getKey() + ".", out);
      }
    } else if (e.isJsonArray()) {
      int i = 0;
      for (var item : e.getAsJsonArray()) walk(item, prefix + "[" + i++ + "].", out);
    }
  }

  // ==================== the simulated logs' manifest ====================

  /** What the manifest records about one simulated log: span, entries, truncation, windows. */
  record SimFacts(String file, double start, double end, int entries, boolean truncated,
      String enabledSource, List<double[]> enabledWindows) {}

  static List<SimFacts> simFacts() throws IOException {
    var file = logDir.resolve(SIM_MANIFEST);
    if (!Files.exists(file)) return List.of();
    var facts = new ArrayList<SimFacts>();
    String name = null;
    double start = 0;
    double end = 0;
    int entries = 0;
    boolean truncated = false;
    String source = null;
    List<double[]> windows = null;
    var span = Pattern.compile("\\*\\*Time span:\\*\\* ([0-9.]+)–([0-9.]+) s .* in ([0-9,]+) entries");
    var transitions = Pattern.compile("^- `(/DriverStation/Enabled|DS:enabled|FMSControlData bit0 \\(enabled\\))`: (.*)$");
    for (var line : Files.readAllLines(file)) {
      if (line.startsWith("## `")) {
        if (name != null && windows != null) {
          facts.add(new SimFacts(name, start, end, entries, truncated, source, windows));
        }
        name = line.substring(4, line.indexOf('`', 4));
        windows = null;
        source = null;
        continue;
      }
      var s = span.matcher(line);
      if (s.find()) {
        start = Double.parseDouble(s.group(1));
        end = Double.parseDouble(s.group(2));
        entries = Integer.parseInt(s.group(3).replace(",", ""));
        truncated = line.contains("truncated final record");
        continue;
      }
      var t = transitions.matcher(line);
      if (t.find() && windows == null) {
        source = t.group(1);
        windows = new ArrayList<>();
        double opened = Double.NaN;
        for (var token : t.group(2).trim().split("\\s+")) {
          var parts = token.split("s=");
          double at = Double.parseDouble(parts[0]);
          if (parts[1].equals("T")) opened = at;
          else if (!Double.isNaN(opened)) {
            windows.add(new double[] {opened, at});
            opened = Double.NaN;
          }
        }
      }
    }
    if (name != null && windows != null) {
      facts.add(new SimFacts(name, start, end, entries, truncated, source, windows));
    }
    return facts;
  }

  // ==================== claims ====================

  @Test
  @DisplayName("match phases, time range, truncation, and scope windows agree with the manifest")
  void simulatedLogsAgreeWithTheirManifest() throws Exception {
    var claim = "simulated logs: phases, span, truncation, scope windows versus the manifest";
    var facts = simFacts();
    needs(!facts.isEmpty(), claim, "no " + SIM_MANIFEST);
    var checked = new ArrayList<String>();
    for (var f : facts) {
      var log = "sim/" + f.file();
      if (!Files.exists(logDir.resolve(log))) continue;
      var entries = call("list_entries", "path", path(log));
      assertEquals("ok", status(entries), entries.toString());
      var range = entries.getAsJsonObject("time_range_sec");
      assertEquals(f.start(), num(range, "start"), 0.011, f.file() + " start");
      assertEquals(f.end(), num(range, "end"), 0.011, f.file() + " end");
      assertEquals(f.truncated(), entries.has("truncated") && entries.get("truncated").getAsBoolean(),
          f.file() + " truncated");
      var phases = call("get_match_phases", "path", path(log));
      assertEquals("ok", status(phases), f.file() + ": " + phases);
      var enabled = new ArrayList<double[]>();
      for (var seg : phases.getAsJsonArray("segments")) {
        var o = seg.getAsJsonObject();
        if ("enabled".equals(str(o, "state"))) {
          enabled.add(new double[] {num(o, "start"), num(o, "end")});
        }
      }
      assertEquals(f.enabledWindows().size(), enabled.size(), f.file() + " enabled segments: "
          + phases.getAsJsonArray("segments"));
      for (int i = 0; i < enabled.size(); i++) {
        assertEquals(f.enabledWindows().get(i)[0], enabled.get(i)[0], 0.05, f.file() + " window " + i);
        assertEquals(f.enabledWindows().get(i)[1], enabled.get(i)[1], 0.05, f.file() + " window " + i);
      }
      // The scope a statistical tool reports is the same timeline
      var voltages = entriesOfType(log, "Voltage", 100, "double", "float");
      if (!voltages.isEmpty()) {
        var stats = call("get_statistics", "path", path(log), "name", voltages.get(0), "scope", "enabled");
        if ("ok".equals(status(stats))) {
          var windows = stats.getAsJsonObject("inputs").getAsJsonObject("scope").getAsJsonArray("windows");
          assertEquals(enabled.size(), windows.size(), f.file() + " scope windows");
          double total = 0;
          for (var w : f.enabledWindows()) total += w[1] - w[0];
          assertTrue(num(stats.getAsJsonObject("data_quality"), "time_span_seconds") <= total + 1,
              f.file() + " quality span within the enabled time");
        }
      }
      checked.add(f.file() + " (" + f.enabledSource() + ", " + enabled.size() + " windows)");
    }
    needs(!checked.isEmpty(), claim, "no simulated log present");
    verified(claim, String.join("; ", checked));
  }

  @Test
  @DisplayName("a truncated real log is reported as such and keeps a sane time range")
  void truncatedLogsKeepASaneRange() throws Exception {
    var claim = "truncated real logs: truncated flag, warning, duration under two hours";
    var checked = new ArrayList<String>();
    for (var log : List.of(PRACTICE, Q10)) {
      if (!Files.exists(logDir.resolve(log))) continue;
      var r = call("list_entries", "path", path(log), "pattern", "BatteryVoltage");
      assertEquals("ok", status(r), r.toString());
      assertTrue(r.get("truncated").getAsBoolean(), log + " is known to end mid-record");
      assertTrue(str(r, "warning").contains("truncated or damaged"), r.get("warning").toString());
      double duration = num(r.getAsJsonObject("time_range_sec"), "duration");
      assertTrue(duration > 60 && duration < 7200, log + " duration " + duration);
      checked.add(log + " " + String.format("%.1f s", duration));
    }
    needs(!checked.isEmpty(), claim, "neither truncated log present");
    verified(claim, String.join("; ", checked));
  }

  @Test
  @DisplayName("a practice log without FMS or autonomous: phases, auto, and the enabled scope say so")
  void practiceWithoutFmsOrAuto() throws Exception {
    var claim = "practice log: no match phases, analyze_auto not_applicable, enabled scope from the segments";
    needsLog(BENCH, claim);
    var phases = call("get_match_phases", "path", path(BENCH));
    assertEquals("ok", status(phases), phases.toString());
    assertTrue(phases.getAsJsonArray("matches").isEmpty(), "no FMS match pattern: " + phases.getAsJsonArray("matches"));
    assertTrue(phases.getAsJsonObject("phases").isEmpty(), phases.getAsJsonObject("phases").toString());
    var notes = phases.getAsJsonArray("notes").toString();
    assertTrue(notes.contains("Autonomous was never true"), notes);
    double enabledSec = num(phases, "enabled_time_sec");
    var auto = call("analyze_auto", "path", path(BENCH));
    assertEquals("not_applicable", status(auto), auto.toString());
    assertTrue(str(auto, "reason").contains("No autonomous period"), auto.toString());
    var voltages = entriesOfType(BENCH, "BatteryVoltage", 100, "double");
    needs(!voltages.isEmpty(), claim, "no battery voltage entry in " + BENCH);
    var scoped = call("get_statistics", "path", path(BENCH), "name", voltages.get(0), "scope", "enabled");
    assertEquals("ok", status(scoped), scoped.toString());
    assertEquals(enabledSec, num(scoped.getAsJsonObject("inputs").getAsJsonObject("scope"), "total_sec"), 0.01,
        "the enabled scope is the timeline's enabled time");
    report.add("NOT VERIFIABLE  can_health UNKNOWN (CAN error lines without DriverStation state) — every sample log has DriverStation data; covered by the fixture test");
    verified(claim, (int) num(phases, "enabled_segment_count") + " enabled segments, " + String.format("%.0f", enabledSec)
        + " s enabled, analyze_auto not_applicable");
  }

  @Test
  @DisplayName("brownout facts agree across the timeline, power, and battery tools")
  void brownoutsAgreeAcrossTools() throws Exception {
    var claim = "roboRIO brownouts: the same count from get_ds_timeline, power_analysis, and predict_battery_health";
    var checked = new ArrayList<String>();
    for (var log : List.of(Q10, PRACTICE)) {
      if (!Files.exists(logDir.resolve(log))) continue;
      var timeline = call("get_ds_timeline", "path", path(log));
      assertEquals("ok", status(timeline), timeline.toString());
      assertFalse(timeline.has("data_quality"), "a timeline of events carries no statistic-style quality");
      long starts = 0;
      for (var e : timeline.getAsJsonArray("events")) {
        if ("RIO_BROWNOUT_START".equals(e.getAsJsonObject().get("type").getAsString())) starts++;
      }
      if (!timeline.get("rio_brownout_flag_logged").getAsBoolean()) continue;
      var power = call("power_analysis", "path", path(log), "scope", "all");
      var battery = call("predict_battery_health", "path", path(log), "scope", "all");
      long powerCount = (long) num(power.getAsJsonObject("rio_brownouts"), "count");
      long batteryCount = (long) num(battery, "brownout_events");
      assertEquals(starts, powerCount, log + ": timeline versus power_analysis");
      assertEquals(starts, batteryCount, log + ": timeline versus predict_battery_health");
      assertTrue(str(battery, "brownout_basis").startsWith("rio_flag"), battery.get("brownout_basis").toString());
      if (starts > 0) {
        var voltage = power.getAsJsonObject("voltage_analysis");
        assertEquals("HIGH", str(voltage, "brownout_risk"), voltage.toString());
        assertTrue(str(voltage, "brownout_risk_basis").contains("brownout"), voltage.toString());
      }
      checked.add(log + ": " + starts + " brownouts");
    }
    needs(!checked.isEmpty(), claim, "no log with a logged roboRIO brownout flag");
    verified(claim, String.join("; ", checked));
  }

  @Test
  @DisplayName("text error and warning counts agree between the timeline and the message search")
  void textCountsAgreeAcrossTools() throws Exception {
    var claim = "text events classified once per sample: get_ds_timeline counts equal search_strings totals";
    var checked = new ArrayList<String>();
    for (var log : List.of(Q10, PRACTICE)) {
      if (!Files.exists(logDir.resolve(log))) continue;
      var timeline = call("get_ds_timeline", "path", path(log));
      if (!timeline.has("text_event_counts")) continue;
      var counts = timeline.getAsJsonObject("text_event_counts");
      for (var level : List.of("error", "warning")) {
        var search = call("search_strings", "path", path(log), "level", level, "limit", 1);
        assertEquals("ok".equals(status(search)) ? num(search, "total_matches") : 0, num(counts, level), 0,
            log + " " + level + ": timeline " + counts + " versus search_strings " + search);
      }
      checked.add(log + ": " + counts.get("error") + " errors, " + counts.get("warning") + " warnings");
    }
    needs(!checked.isEmpty(), claim, "no log with text events");
    verified(claim, String.join("; ", checked));
  }

  @Test
  @DisplayName("CAN counter facts agree between analyze_can_bus, can_health, and get_statistics")
  void canCountersAgreeAcrossTools() throws Exception {
    var claim = "CAN counters: the same TEC maximum from three tools; excursions at 128 or above match find_condition";
    var checked = new ArrayList<String>();
    for (var log : List.of(PRACTICE, Q10)) {
      if (!Files.exists(logDir.resolve(log))) continue;
      var can = call("analyze_can_bus", "path", path(log), "scope", "all");
      if (!"ok".equals(status(can)) || !can.has("buses") || can.getAsJsonArray("buses").isEmpty()) continue;
      var bus = can.getAsJsonArray("buses").get(0).getAsJsonObject();
      if (!bus.has("tec") || !bus.getAsJsonObject("entries").has("tec")) continue;
      var tecEntry = str(bus.getAsJsonObject("entries"), "tec");
      double tecMax = num(bus.getAsJsonObject("tec"), "max");
      var stats = call("get_statistics", "path", path(log), "name", tecEntry);
      assertEquals(tecMax, num(stats, "max"), 1e-9, log + " TEC max versus get_statistics");
      var health = call("can_health", "path", path(log));
      if (health.has("bus_counters")) {
        var counter = health.getAsJsonArray("bus_counters").get(0).getAsJsonObject();
        assertEquals(tecMax, num(counter, "tec_max"), 1e-9, log + " TEC max versus can_health");
      }
      var keys = new LinkedHashMap<String, JsonElement>();
      walk(bus.getAsJsonObject("tec"), "", keys);
      var excursions = keys.entrySet().stream().filter(e -> e.getKey().contains("excursion")
          && e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()).findFirst();
      String evidence = log + ": TEC max " + tecMax;
      if (tecMax >= 128 && excursions.isPresent()) {
        var condition = call("find_condition", "path", path(log), "name", tecEntry, "operator", "gte",
            "threshold", 128.0, "limit", 1000);
        assertEquals(num(condition, "interval_count"), excursions.get().getValue().getAsDouble(), 0,
            log + " excursions versus find_condition >= 128");
        evidence += ", " + excursions.get().getKey() + " " + excursions.get().getValue() + " = find_condition intervals";
      } else {
        evidence += " (the 128 boundary is not reached in this log)";
      }
      checked.add(evidence);
    }
    needs(!checked.isEmpty(), claim, "no log with CAN error counters");
    verified(claim, String.join("; ", checked));
  }

  /** The SPARK_MODEL code (status 0, bits 54-57) of every device in a native REV log, by majority. */
  static Map<Integer, Integer> sparkModelsByFrames(Path revlog) throws IOException {
    var bytes = Files.readAllBytes(revlog);
    var counts = new HashMap<Integer, int[]>();
    int base = (2 << 24) | (5 << 16) | (46 << 10); // SPARK, REV, periodic status, index 0
    for (int i = 0; i + 16 <= bytes.length; i++) {
      int id = (bytes[i + 4] & 0xFF) | (bytes[i + 5] & 0xFF) << 8 | (bytes[i + 6] & 0xFF) << 16
          | (bytes[i + 7] & 0xFF) << 24;
      id &= 0x1FFF_FFFF;
      if ((id & ~0x3F) != base) continue;
      int device = id & 0x3F;
      int code = ((bytes[i + 14] & 0xFF) >> 6) | ((bytes[i + 15] & 0x03) << 2);
      counts.computeIfAbsent(device, d -> new int[16])[code]++;
    }
    var models = new TreeMap<Integer, Integer>();
    for (var e : counts.entrySet()) {
      int best = 0;
      int total = 0;
      for (int c = 0; c < 16; c++) {
        total += e.getValue()[c];
        if (e.getValue()[c] > e.getValue()[best]) best = c;
      }
      if (total >= 100) models.put(e.getKey(), best);
    }
    return models;
  }

  @Test
  @DisplayName("SPARK devices are labeled by the model their frames carry; the REV log is found by its UTC name and synchronized")
  void revlogModelsAndSync() throws Exception {
    var claim = "REV log: found by the roboRIO's UTC file name, synchronized near zero, SPARK models from the frames";
    needsLog(Q10, claim);
    var synced = call("wait_for_sync", "path", path(Q10), "timeout_ms", 240_000);
    var status = call("sync_status", "path", path(Q10));
    needs("ok".equals(status(status)) && status.has("revlogs") && !status.getAsJsonArray("revlogs").isEmpty(),
        claim, "no REV log synchronized for " + Q10 + ": " + status + " / " + synced);
    var revlog = status.getAsJsonArray("revlogs").get(0).getAsJsonObject();
    var revlogPath = Path.of(str(revlog, "path"));
    assertEquals(Q10_REVLOG, revlogPath.getFileName().toString(),
        "the REV log named 24 s before the wpilog, read as UTC like the wpilog's own name");
    assertTrue(str(status, "revlog_filename_zone").startsWith("UTC"), status.get("revlog_filename_zone").toString());
    var sync = revlog.getAsJsonObject("sync");
    assertEquals("CROSS_CORRELATION", str(sync, "method"), sync.toString());
    assertEquals(0.0, num(sync, "offset_seconds"), 0.05, "the true offset is near zero (pairs -26..+8 ms)");
    var models = sparkModelsByFrames(revlogPath);
    needs(!models.isEmpty(), claim, "no status 0 frames decoded from " + revlogPath);
    var signals = call("list_revlog_signals", "path", path(Q10));
    assertEquals("ok", status(signals), signals.toString());
    var devices = new TreeSet<String>();
    for (var s : signals.getAsJsonArray("signals")) devices.add(s.getAsJsonObject().get("device").getAsString());
    var evidence = new ArrayList<String>();
    for (var e : models.entrySet()) {
      var expected = switch (e.getValue()) {
        case 1 -> "SparkFlex_" + e.getKey();
        case 2 -> "SparkMax_" + e.getKey();
        default -> "Spark_" + e.getKey();
      };
      assertTrue(devices.contains(expected), "device " + e.getKey() + " reports model code " + e.getValue()
          + " in its frames, expected key " + expected + "; keys: " + devices);
      for (var other : devices) {
        assertFalse(other.endsWith("_" + e.getKey()) && !other.equals(expected),
            "device " + e.getKey() + " also labeled " + other);
      }
      evidence.add(expected + " (code " + e.getValue() + ")");
    }
    verified(claim, revlogPath.getFileName() + ", offset " + sync.get("offset_seconds") + " s, " + String.join(", ", evidence));
  }

  @Test
  @DisplayName("profile_mechanism resolves a named mechanism and refuses to choose among several")
  void mechanismRolesAndAmbiguity() throws Exception {
    var claim = "profile_mechanism: Turret's current entry resolved; an ambiguous name is no_match with the stems";
    needsLog(Q10, claim);
    var turret = call("profile_mechanism", "path", path(Q10), "mechanism_name", "Turret");
    assertTrue(status(turret).equals("ok") || status(turret).equals("partial"), turret.toString());
    assertEquals("/Turret/CurrentAmps", str(turret.getAsJsonObject("roles"), "current"), turret.getAsJsonObject("roles").toString());
    var currents = call("list_entries", "path", path(Q10), "pattern", "/CurrentAmps");
    var stems = new TreeSet<String>();
    for (var e : currents.getAsJsonArray("entries")) {
      var name = e.getAsJsonObject().get("name").getAsString();
      stems.add(name.substring(0, name.lastIndexOf('/')));
    }
    String ambiguous = null;
    for (var candidate : List.of("er", "e", "r", "o")) {
      long matching = stems.stream().filter(s -> s.toLowerCase().contains(candidate)).count();
      if (matching >= 2) {
        ambiguous = candidate;
        break;
      }
    }
    needs(ambiguous != null, claim, "no substring matches two mechanism stems among " + stems);
    var several = call("profile_mechanism", "path", path(Q10), "mechanism_name", ambiguous);
    assertEquals("no_match", status(several), several.toString());
    assertTrue(several.get("needs_confirmation").getAsBoolean(), several.toString());
    assertTrue(several.getAsJsonObject("stems").size() >= 2, several.getAsJsonObject("stems").toString());
    verified(claim, "Turret current /Turret/CurrentAmps; '" + ambiguous + "' matches "
        + several.getAsJsonObject("stems").size() + " stems and is refused");
  }

  @Test
  @DisplayName("angle statistics report wraps for a single-valued signal, none for a [*] pool")
  void angleWrapsOnlyForOrderedSignals() throws Exception {
    var claim = "get_statistics angle: wraps for a scalar angle, no wraps for a [*] pool";
    var log = Files.exists(logDir.resolve(Q10)) ? Q10 : PRACTICE;
    needsLog(log, claim);
    // An angle, not a rate or a timestamp that happens to mention yaw
    String angleEntry = null;
    var notAngles = List.of("Velocity", "Rate", "Timestamp", "Accel", "Offset");
    var scalarYaws = entriesOfType(log, "Yaw", 100, "double").stream()
        .filter(n -> notAngles.stream().noneMatch(n::contains)).toList();
    var structYaws = entriesOfType(log, "Yaw", 100, "struct:Rotation2d");
    if (!scalarYaws.isEmpty()) angleEntry = scalarYaws.get(0);
    else if (!structYaws.isEmpty()) angleEntry = structYaws.get(0) + ".value";
    needs(angleEntry != null, claim, "no yaw entry in " + log);
    var scalar = call("get_statistics", "path", path(log), "name", angleEntry, "angle", "radians");
    assertEquals("ok", status(scalar), scalar.toString());
    var scalarAngle = scalar.getAsJsonObject("angle");
    assertTrue(scalarAngle.get("unwrapped").getAsBoolean(), "a single-valued angle is unwrapped: " + scalarAngle);
    assertTrue(scalarAngle.has("wraps"), "the number of wraps for " + angleEntry + ": " + scalarAngle);
    assertTrue(scalarAngle.has("circular_mean") && scalarAngle.has("circular_std"), scalarAngle.toString());
    var arrays = entriesOfType(log, "/", 100, "double[]");
    needs(!arrays.isEmpty(), claim, "no double[] entry in " + log);
    var pool = arrays.get(0) + "[*]";
    var pooled = call("get_statistics", "path", path(log), "name", pool, "angle", "degrees");
    assertEquals("ok", status(pooled), pooled.toString());
    var poolAngle = pooled.getAsJsonObject("angle");
    assertFalse(poolAngle.get("unwrapped").getAsBoolean(), "a pool has no order to unwrap: " + poolAngle);
    assertFalse(poolAngle.has("wraps"), "no wrap count for a pool: " + poolAngle);
    assertTrue(poolAngle.has("circular_mean"), "circular statistics still apply: " + poolAngle);
    verified(claim, angleEntry + " unwrapped with " + scalarAngle.get("wraps") + " wraps; " + pool + " not unwrapped, no wrap count");
  }

  @Test
  @DisplayName("export_csv writes only the file results, in the export directory")
  void exportDirectoryAndInline() throws Exception {
    var claim = "export_csv: file results name export_directory and the file exists; inline writes nothing";
    var log = Files.exists(logDir.resolve(Q10)) ? Q10 : PRACTICE;
    needsLog(log, claim);
    var entry = "/SystemStats/BatteryVoltage";
    long before;
    try (Stream<Path> s = Files.list(exportDir)) { before = s.count(); }
    var inline = call("export_csv", "path", path(log), "name", entry, "inline", true, "max_rows", 5, "end_time", 60.0);
    assertEquals("ok", status(inline), inline.toString());
    assertFalse(inline.has("output_path"), inline.toString());
    long afterInline;
    try (Stream<Path> s = Files.list(exportDir)) { afterInline = s.count(); }
    assertEquals(before, afterInline, "inline export wrote a file");
    var file = call("export_csv", "path", path(log), "name", entry, "output_path", "claims-voltage.csv", "end_time", 60.0);
    assertEquals("ok", status(file), file.toString());
    assertEquals(exportDir.toRealPath().toString(), Path.of(str(file, "export_directory")).toRealPath().toString());
    assertTrue(Files.exists(Path.of(str(file, "output_path"))), file.toString());
    verified(claim, file.get("rows_exported") + " rows to " + file.get("output_path"));
  }

  @Test
  @DisplayName("the listing's match metadata, UTC file-name times, and TBA status")
  void listingMetadataAndFilenameTimes() throws Exception {
    var claim = "list_available_logs: match metadata, file-name time read as UTC, tba_enrichment status";
    needsLog(Q10, claim);
    var listed = call("list_available_logs", "name", "vache_q10", "limit", 5);
    assertEquals("ok", status(listed), listed.toString());
    JsonObject q10 = null;
    for (var l : listed.getAsJsonArray("logs")) {
      if (l.getAsJsonObject().get("filename").getAsString().contains("vache_q10")) q10 = l.getAsJsonObject();
    }
    assertNotNull(q10, listed.toString());
    assertEquals("VACHE", str(q10, "event"));
    assertEquals("Qualification", str(q10, "match_type"));
    assertEquals(10, (int) num(q10, "match_number"));
    // The name says 16:29:56; read as UTC, a filter one second either side includes or excludes it
    var included = call("list_available_logs", "name", "vache_q10", "since", "2026-03-21T16:29:55Z");
    var excluded = call("list_available_logs", "name", "vache_q10", "since", "2026-03-21T16:29:57Z");
    assertTrue(included.has("logs") && included.getAsJsonArray("logs").size() == 1, included.toString());
    assertTrue(!"ok".equals(status(excluded)) || excluded.getAsJsonArray("logs").isEmpty(), excluded.toString());
    var tba = listed.getAsJsonObject("tba_enrichment");
    var tbaStatus = call("get_tba_status");
    if ("not_configured".equals(str(tbaStatus, "configuration"))) {
      assertFalse(tba.get("available").getAsBoolean(), tba.toString());
      assertTrue(str(tba, "reason").contains("not configured"), tba.toString());
    }
    verified(claim, "VACHE Qualification 10; since 16:29:55Z includes it, 16:29:57Z excludes it; tba_enrichment " + tba);
  }

  @Test
  @DisplayName("replay drift applies to a replay log and not to a real-robot log")
  void replayDriftApplicability() throws Exception {
    var claim = "analyze_replay_drift: ok on the AdvantageKit replay, not_applicable on the real log";
    needsLog(E4_REPLAY, claim);
    needsLog(E4, claim);
    var replay = call("analyze_replay_drift", "path", path(E4_REPLAY), "limit", 3);
    assertEquals("ok", status(replay), replay.toString());
    assertTrue(num(replay, "pairs_compared") > 0, replay.toString());
    var real = call("analyze_replay_drift", "path", path(E4), "limit", 3);
    assertEquals("not_applicable", status(real), real.toString());
    assertTrue(str(real, "reason").contains("ReplayOutputs"), real.toString());
    verified(claim, replay.get("pairs_compared") + " pairs compared on the replay; the real log is not_applicable");
  }

  @Test
  @DisplayName("swerve drift is never computed from a name-only pose choice")
  void swerveDriftNeverFromNames() throws Exception {
    var claim = "analyze_swerve: a drift section states its odometry_basis and vision_basis, or is skipped with a reason";
    needsLog(Q10, claim);
    var r = call("analyze_swerve", "path", path(Q10), "scope", "enabled");
    assertTrue(status(r).equals("ok") || status(r).equals("partial"), r.toString());
    if (r.has("odometry_drift")) {
      var drift = r.getAsJsonObject("odometry_drift");
      assertTrue(drift.has("odometry_basis") && drift.has("vision_basis"), drift.toString());
      verified(claim, "drift computed with bases " + drift.get("odometry_basis") + " / " + drift.get("vision_basis"));
    } else {
      boolean skipped = false;
      for (var s : r.has("skipped") ? r.getAsJsonArray("skipped") : new JsonArray()) {
        if ("odometry_drift".equals(s.getAsJsonObject().get("section").getAsString())) skipped = true;
      }
      assertTrue(skipped, "no drift and no skipped reason: " + r);
      verified(claim, "drift skipped with a reason");
    }
  }

  @Test
  @DisplayName("statistics on a scope are a subset of the whole log's, and data_quality counts them")
  void scopedCountsAreSubsets() throws Exception {
    var claim = "get_statistics: enabled + disabled sample counts never exceed all; data_quality counts the samples analyzed";
    var log = Files.exists(logDir.resolve(Q10)) ? Q10 : PRACTICE;
    needsLog(log, claim);
    var entry = "/SystemStats/BatteryVoltage";
    var all = call("get_statistics", "path", path(log), "name", entry, "scope", "all");
    var enabled = call("get_statistics", "path", path(log), "name", entry, "scope", "enabled");
    var disabled = call("get_statistics", "path", path(log), "name", entry, "scope", "disabled");
    for (var r : List.of(all, enabled, disabled)) {
      assertEquals("ok", status(r), r.toString());
      assertEquals(num(r, "count"), num(r.getAsJsonObject("data_quality"), "sample_count"), 0, r.toString());
    }
    assertTrue(num(enabled, "count") + num(disabled, "count") <= num(all, "count"), "enabled " + enabled.get("count")
        + " + disabled " + disabled.get("count") + " > all " + all.get("count"));
    assertTrue(num(enabled, "count") < num(all, "count"), "the enabled scope excludes the disabled time");
    verified(claim, "all " + all.get("count") + ", enabled " + enabled.get("count") + ", disabled " + disabled.get("count"));
  }
}
