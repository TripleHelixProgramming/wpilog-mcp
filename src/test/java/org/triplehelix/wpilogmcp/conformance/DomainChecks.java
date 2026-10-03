/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Domain answers recomputed from the raw records of the entries each tool says it used: the
 * loop-time statistics of analyze_loop_timing, the roboRIO brownouts in power_analysis and
 * get_ds_timeline, analyze_can_bus's figures per bus, analyze_swerve's module speeds, and the
 * modes of get_match_phases' enabled segments.
 *
 * <p>Which entries a tool chooses is checked elsewhere (its conventions, and the claims on the
 * live service). Given those entries, this checks the numbers, on every log the differential
 * check reads, with no expected value stored anywhere: logs other teams publish are checked
 * without anything taken from them. The rules come from each tool's documentation in TOOLS.md:
 * what holds between samples, what a threshold counts, which window bounds are inclusive.
 *
 * <p>An entry is compared over the records the server holds: all the reader found, or, where the
 * file is damaged, all but the last few before the damage, which the server may set aside (the
 * differential check verifies that it sets aside no others). Its records must all decode and its
 * timestamps never go backwards, since the server's lookups of a held value assume time order.
 * Otherwise a note says why it was not compared.
 */
final class DomainChecks {
  private DomainChecks() {}

  /** The timeline folds an enabled stretch shorter than this into its neighbour. */
  static final double MODE_SETTLE_SEC = 0.25;
  /** A CAN controller is error-passive at this TEC or REC. */
  static final double ERROR_PASSIVE = 128;
  /**
   * WPILib's own struct definitions of the module state, for a log that declares a schema entry
   * but never wrote its value (robot code published it before logging started). The server
   * decodes those with WPILib's definitions too.
   */
  static final Map<String, String> WPILIB_SCHEMAS = Map.of(
      "SwerveModuleState", "double speed;Rotation2d angle",
      "Rotation2d", "double value");

  /** The answers of the tools whose numbers are recomputed, from their default calls. */
  record Answers(JsonObject phases, JsonObject loop, JsonObject power, JsonObject timeline,
      JsonObject can, JsonObject swerve) {}

  static boolean ok(JsonObject o) {
    var s = DifferentialChecks.status(o);
    return s.equals("ok") || s.equals("partial");
  }

  private static String str(JsonObject o, String key) {
    return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
  }

  /** The entries whose values the checks need, as the tools name them. */
  static Set<String> entries(Answers a) {
    var names = new LinkedHashSet<String>();
    if (ok(a.phases())) {
      var in = a.phases().getAsJsonObject("inputs").getAsJsonObject("entries");
      for (var role : List.of("enabled", "autonomous", "test", "control_word")) {
        if (in.has(role)) names.add(in.get(role).getAsString());
      }
    }
    if (ok(a.loop())) {
      names.add(str(a.loop(), "loop_time_entry"));
      if (a.loop().has("user_code")) names.add(str(a.loop().getAsJsonObject("user_code"), "entry"));
    }
    var flag = flagEntry(a);
    if (flag != null) names.add(flag);
    if (ok(a.can())) {
      for (var bus : a.can().getAsJsonArray("buses")) {
        for (var e : bus.getAsJsonObject().getAsJsonObject("entries").entrySet()) {
          names.add(e.getValue().getAsString());
        }
      }
    }
    return names;
  }

  /** The entries whose payloads the checks need: module state arrays, and every struct schema. */
  static Set<String> rawEntries(Answers a, IndependentLog survey) {
    var names = new LinkedHashSet<String>();
    if (ok(a.swerve()) && "array".equals(str(a.swerve(), "layout"))) {
      for (var m : a.swerve().getAsJsonArray("modules")) {
        names.add(str(m.getAsJsonObject(), "measured_entry"));
      }
      // Schemas are /.schema/struct:<name>, or NT:/.schema/struct:<name> through NetworkTables
      survey.series.keySet().stream().filter(n -> n.contains("/.schema/")).forEach(names::add);
    }
    return names;
  }

  private static String flagEntry(Answers a) {
    if (ok(a.power()) && a.power().has("rio_brownouts")) {
      return str(a.power().getAsJsonObject("rio_brownouts"), "flag_entry");
    }
    return ok(a.timeline()) ? str(a.timeline(), "rio_brownout_flag_entry") : null;
  }

  /**
   * Compares the answers with the records, adding a finding for each disagreement.
   *
   * @param logEnd The log's last timestamp, as the server reports it
   * @return How many values were compared
   */
  static int compare(IndependentLog log, Map<String, Integer> serverCounts, double logEnd,
      Answers a, List<String> findings, List<String> notes) {
    var c = new Context(log, serverCounts, logEnd, findings, notes);
    if (ok(a.phases())) {
      var in = a.phases().getAsJsonObject("inputs").getAsJsonObject("entries");
      c.controlWord = !in.has("enabled") && in.has("control_word");
      var name = in.has("enabled") ? str(in, "enabled") : str(in, "control_word");
      if (name != null) {
        c.enabled = c.usable(name, "the enabled state");
        c.enabledKnown = c.enabled != null;
      }
      phaseModes(c, a.phases());
    }
    loopTiming(c, a.loop());
    brownouts(c, a);
    canBus(c, a.can());
    swerve(c, a.swerve());
    return c.compared;
  }

  // ==================== the log's records ====================

  private static final class Context {
    final IndependentLog log;
    final Map<String, Integer> serverCounts;
    final double logEnd;
    final List<String> findings;
    final List<String> notes;
    int compared;
    /** The DriverStation entry the timeline reads the enabled state from, when usable. */
    IndependentLog.Series enabled;
    /** Whether the enabled state is the FMS control word's lowest bit. */
    boolean controlWord;
    /** False when the log has an enabled entry this check cannot use. */
    boolean enabledKnown = true;
    private Map<String, String> schemas;

    Context(IndependentLog log, Map<String, Integer> serverCounts, double logEnd,
        List<String> findings, List<String> notes) {
      this.log = log;
      this.serverCounts = serverCounts;
      this.logEnd = logEnd;
      this.findings = findings;
      this.notes = notes;
    }

    /**
     * The entry's records that the server holds (all, or all but the last few before damage), when
     * they all decode and are in time order; else null, with a note.
     */
    IndependentLog.Series usable(String name, String use) {
      var full = log.series.get(name);
      if (full == null) {
        notes.add(use + " not compared: " + name + " is not in the file");
        return null;
      }
      var server = serverCounts.get(name);
      boolean decodable = full.keptRaw ? full.payloads.size() == full.records
          : full.numeric == full.records;
      if (server == null || server > full.records
          || server < full.records - log.nearDamage(name) || !decodable) {
        notes.add(use + " not compared: the server holds " + server + " records of " + name
            + ", the file " + full.records + (decodable ? "" : " (not all decodable)"));
        return null;
      }
      var s = server == full.records ? full : first(full, server);
      double previous = Double.NEGATIVE_INFINITY;
      int n = s.keptRaw ? s.payloadTimes.size() : s.n;
      for (int i = 0; i < n; i++) {
        double t = s.keptRaw ? s.payloadTimes.get(i) : s.times[i];
        if (t < previous) {
          notes.add(use + " not compared: the timestamps of " + name + " go backwards");
          return null;
        }
        previous = t;
      }
      return s;
    }

    /** Whether the robot was enabled at t, as the timeline holds it; null when unknown. */
    Boolean enabledAt(double t) {
      if (enabled == null) return null;
      double v = heldAt(enabled, t);
      if (Double.isNaN(v)) return null;
      return controlWord ? (((long) v) & 1) != 0 : v != 0;
    }

    /**
     * Whether a scope the server reports holds t: its windows are half-open, except one ending
     * at the log's end while its state still holds then. Null when that cannot be rebuilt.
     */
    Boolean inScope(JsonObject scope, double t) {
      var name = str(scope, "scope");
      var windows = scope.getAsJsonArray("windows");
      if (windows.size() != scope.get("window_count").getAsInt()) return null;
      boolean endHolds;
      if ("all".equals(name)) endHolds = true;
      else if ("enabled".equals(name)) endHolds = Boolean.TRUE.equals(enabledAt(logEnd));
      else return null;
      for (var w : windows) {
        double start = w.getAsJsonArray().get(0).getAsDouble();
        double end = w.getAsJsonArray().get(1).getAsDouble();
        if (t >= start && (t < end || (t == end && end >= logEnd && endHolds))) return true;
      }
      return false;
    }

    /** The struct schemas the log declares, by struct name. */
    Map<String, String> schemas() {
      if (schemas == null) {
        schemas = new HashMap<>();
        for (var s : log.series.values()) {
          int at = s.name.indexOf("/.schema/struct:");
          if (at < 0 || s.payloads.isEmpty()) continue;
          schemas.put(s.name.substring(at + "/.schema/struct:".length()),
              new String(s.payloads.get(s.payloads.size() - 1), StandardCharsets.UTF_8));
        }
        WPILIB_SCHEMAS.forEach(schemas::putIfAbsent);
      }
      return schemas;
    }

    void expect(String what, JsonObject o, String key, double expected) {
      compared++;
      var got = o == null ? null : o.get(key);
      if (got == null || got.isJsonNull() || !got.isJsonPrimitive()
          || !DifferentialChecks.close(got.getAsDouble(), expected)) {
        findings.add(what + " " + key + ": " + got + ", independently " + expected);
      }
    }

    void expectAbsent(String what, JsonObject o, String key) {
      compared++;
      if (o != null && o.has(key)) {
        findings.add(what + ": " + key + " is " + o.get(key) + ", independently absent");
      }
    }

    void disagree(String what) {
      findings.add(what);
    }
  }

  /** The first {@code count} records of a series. */
  private static IndependentLog.Series first(IndependentLog.Series s, int count) {
    var out = new IndependentLog.Series(s.name, s.type, s.kept, s.keptRaw);
    out.records = count;
    out.numeric = s.kept ? count : s.numeric;
    if (s.kept) {
      out.times = Arrays.copyOf(s.times, count);
      out.values = Arrays.copyOf(s.values, count);
      out.n = count;
      for (int i = 0; i < count; i++) if (Double.isFinite(out.values[i])) out.finite++;
    }
    if (s.keptRaw) {
      out.payloadTimes.addAll(s.payloadTimes.subList(0, count));
      out.payloads.addAll(s.payloads.subList(0, count));
    }
    return out;
  }

  /** The value of the last sample at or before t (the server's sample-and-hold); NaN before. */
  static double heldAt(IndependentLog.Series s, double t) {
    if (s.n == 0 || s.times[0] > t) return Double.NaN;
    int lo = 0;
    int hi = s.n - 1;
    while (lo < hi) {
      int mid = (lo + hi + 1) >>> 1;
      if (s.times[mid] <= t) lo = mid;
      else hi = mid - 1;
    }
    return s.values[lo];
  }

  private static double mean(List<Double> values) {
    double sum = 0;
    for (double v : values) sum += v;
    return sum / values.size();
  }

  private static double[] sorted(List<Double> values) {
    var out = values.stream().mapToDouble(Double::doubleValue).toArray();
    Arrays.sort(out);
    return out;
  }

  // ==================== get_match_phases: the mode of each enabled segment ====================

  /**
   * Each enabled segment's mode must be the one the DriverStation entries hold throughout it,
   * apart from the settle time at its ends (a mode flag logged just after an enable or just before
   * a disable is not a mode change), so a mode change inside a segment is also a finding.
   */
  static void phaseModes(Context c, JsonObject phases) {
    var in = phases.getAsJsonObject("inputs").getAsJsonObject("entries");
    IndependentLog.Series control = null;
    IndependentLog.Series auto = null;
    IndependentLog.Series test = null;
    var use = "get_match_phases modes";
    if (c.controlWord) {
      control = c.usable(str(in, "control_word"), use);
      if (control == null) return;
    } else {
      if (in.has("autonomous") && (auto = c.usable(str(in, "autonomous"), use)) == null) return;
      if (in.has("test") && (test = c.usable(str(in, "test"), use)) == null) return;
    }
    var flags = new ArrayList<IndependentLog.Series>();
    for (var s : new IndependentLog.Series[] {control, auto, test}) if (s != null) flags.add(s);
    for (var element : phases.getAsJsonArray("segments")) {
      var seg = element.getAsJsonObject();
      if (!"enabled".equals(str(seg, "state"))) continue;
      double start = seg.get("start").getAsDouble();
      double end = seg.get("end").getAsDouble();
      if (end - start <= 2 * MODE_SETTLE_SEC) continue;
      var points = new ArrayList<Double>();
      points.add((start + end) / 2);
      for (var s : flags) {
        for (int i = 0; i < s.n; i++) {
          if (s.times[i] > start + MODE_SETTLE_SEC && s.times[i] < end - MODE_SETTLE_SEC) {
            points.add(s.times[i]);
          }
        }
      }
      c.compared++;
      for (double t : points) {
        var expected = modeAt(t, control, auto, test);
        if (!expected.equals(str(seg, "mode"))) {
          c.disagree("get_match_phases segment " + start + " to " + end + " is "
              + str(seg, "mode") + ", but the DriverStation entries hold " + expected + " at " + t);
          break;
        }
      }
    }
  }

  private static String modeAt(double t, IndependentLog.Series control,
      IndependentLog.Series auto, IndependentLog.Series test) {
    if (control != null) {
      double w = heldAt(control, t);
      if (Double.isNaN(w)) return "unknown";
      long bits = (long) w;
      if ((bits & 4) != 0) return "test";
      return (bits & 2) != 0 ? "auto" : "teleop";
    }
    if (test != null && heldAt(test, t) == 1) return "test";
    if (auto == null) return "unknown";
    double v = heldAt(auto, t);
    if (Double.isNaN(v)) return "unknown";
    return v != 0 ? "auto" : "teleop";
  }

  // ==================== analyze_loop_timing ====================

  static void loopTiming(Context c, JsonObject loop) {
    if (!ok(loop)) return;
    var entry = str(loop, "loop_time_entry");
    var use = "analyze_loop_timing on " + entry;
    if (!"all".equals(str(loop.getAsJsonObject("scope"), "scope"))) {
      c.notes.add(use + " not compared: the default scope is not 'all'");
      return;
    }
    var s = c.usable(entry, use);
    if (s == null) return;
    var times = new ArrayList<Double>();
    var ms = new ArrayList<Double>();
    if ("/Timestamp".equals(entry)) {
      // Loop periods: the difference from the previous /Timestamp value, in microseconds
      for (int i = 1; i < s.n; i++) {
        times.add(s.times[i]);
        ms.add((s.values[i] - s.values[i - 1]) / 1000.0);
      }
    } else {
      double factor = switch (String.valueOf(str(loop.getAsJsonObject("unit"), "value"))) {
        case "ms" -> 1.0;
        case "s" -> 1000.0;
        case "us" -> 0.001;
        default -> Double.NaN;
      };
      if (Double.isNaN(factor)) {
        c.notes.add(use + " not compared: unit " + loop.get("unit"));
        return;
      }
      for (int i = 0; i < s.n; i++) {
        if (Double.isFinite(s.values[i])) {
          times.add(s.times[i]);
          ms.add(s.values[i] * factor);
        }
      }
      // The slow cycle after boot: the entry's first sample, over ten times the median
      if (ms.size() > 1 && times.get(0) == s.times[0]
          && ms.get(0) > 10 * DifferentialChecks.percentile(sorted(ms), 50)) {
        var boot = loop.getAsJsonObject("excluded_boot_cycle");
        c.expect(use + " excluded_boot_cycle", boot, "timestamp", times.get(0));
        c.expect(use + " excluded_boot_cycle", boot, "loop_time_ms", ms.get(0));
        times.remove(0);
        ms.remove(0);
      } else {
        c.expectAbsent(use, loop, "excluded_boot_cycle");
      }
    }
    if (ms.isEmpty()) return;
    double threshold = loop.get("threshold_ms").getAsDouble();
    c.expect(use, loop, "total_samples", ms.size());
    c.expect(use, loop, "violation_count", ms.stream().filter(v -> v > threshold).count());
    var st = loop.getAsJsonObject("statistics");
    var sorted = sorted(ms);
    c.expect(use, st, "avg_ms", mean(ms));
    c.expect(use, st, "median_ms", DifferentialChecks.percentile(sorted, 50));
    c.expect(use, st, "p90_ms", DifferentialChecks.percentile(sorted, 90));
    c.expect(use, st, "p95_ms", DifferentialChecks.percentile(sorted, 95));
    c.expect(use, st, "p99_ms", DifferentialChecks.percentile(sorted, 99));
    c.expect(use, st, "min_ms", sorted[0]);
    int max = 0;
    for (int i = 1; i < ms.size(); i++) if (ms.get(i) > ms.get(max)) max = i;
    c.expect(use, st, "max_ms", ms.get(max));
    c.expect(use, st, "max_time_sec", times.get(max));

    // The robot code's own time, alongside, in the entry's own unit
    if (loop.has("user_code")) {
      var userCode = loop.getAsJsonObject("user_code");
      var name = str(userCode, "entry");
      var u = c.usable(name, "analyze_loop_timing user_code on " + name);
      if (u == null) return;
      var values = new ArrayList<Double>();
      for (int i = 0; i < u.n; i++) if (Double.isFinite(u.values[i])) values.add(u.values[i]);
      if (values.isEmpty()) return;
      var us = sorted(values);
      var what = "analyze_loop_timing user_code (" + name + ")";
      c.expect(what, userCode, "median_ms", DifferentialChecks.percentile(us, 50));
      c.expect(what, userCode, "p95_ms", DifferentialChecks.percentile(us, 95));
      c.expect(what, userCode, "percent_over_threshold",
          100.0 * values.stream().filter(v -> v > threshold).count() / values.size());
    }
  }

  // ==================== roboRIO brownouts ====================

  /**
   * The intervals the logged flag was true: from each sample that turns it on to the next that
   * turns it off, or to the log's end. get_ds_timeline marks each change; power_analysis lists the
   * intervals that start in its scope.
   */
  static void brownouts(Context c, Answers a) {
    var flag = flagEntry(a);
    if (flag == null) return;
    var s = c.usable(flag, "brownouts from " + flag);
    if (s == null) return;
    var intervals = new ArrayList<double[]>(); // {start, end, 1 when still open at the log's end}
    double opened = Double.NaN;
    for (int i = 0; i < s.n; i++) {
      boolean on = s.values[i] != 0;
      if (on && Double.isNaN(opened)) {
        opened = s.times[i];
      } else if (!on && !Double.isNaN(opened)) {
        intervals.add(new double[] {opened, s.times[i], 0});
        opened = Double.NaN;
      }
    }
    if (!Double.isNaN(opened)) intervals.add(new double[] {opened, c.logEnd, 1});

    var timeline = a.timeline();
    if (ok(timeline) && flag.equals(str(timeline, "rio_brownout_flag_entry"))) {
      var events = timeline.getAsJsonArray("events");
      var limits = timeline.has("limits") ? timeline.getAsJsonObject("limits") : null;
      if (limits != null && limits.has("events")
          && limits.getAsJsonObject("events").get("returned").getAsInt()
              < limits.getAsJsonObject("events").get("total").getAsInt()) {
        c.notes.add("get_ds_timeline brownouts not compared: the event list is shortened");
      } else {
        var starts = new ArrayList<Double>();
        var ends = new ArrayList<Double>();
        for (var e : events) {
          var type = str(e.getAsJsonObject(), "type");
          if ("RIO_BROWNOUT_START".equals(type)) starts.add(e.getAsJsonObject().get("timestamp").getAsDouble());
          if ("RIO_BROWNOUT_END".equals(type)) ends.add(e.getAsJsonObject().get("timestamp").getAsDouble());
        }
        var expectedStarts = intervals.stream().map(i -> i[0]).toList();
        var expectedEnds = intervals.stream().filter(i -> i[2] == 0).map(i -> i[1]).toList();
        c.compared += 2;
        if (!expectedStarts.equals(starts) || !expectedEnds.equals(ends)) {
          c.disagree("get_ds_timeline RIO_BROWNOUT_START " + starts + " and END " + ends
              + ", independently " + expectedStarts + " and " + expectedEnds);
        }
      }
    }

    var power = a.power();
    if (ok(power) && power.has("rio_brownouts")) {
      var scope = power.getAsJsonObject("scope");
      var inScope = new ArrayList<double[]>();
      for (var i : intervals) {
        var contains = c.inScope(scope, i[0]);
        if (contains == null) {
          c.notes.add("power_analysis brownouts not compared: scope " + scope.get("scope"));
          return;
        }
        if (contains) inScope.add(i);
      }
      var rb = power.getAsJsonObject("rio_brownouts");
      var what = "power_analysis rio_brownouts";
      c.expect(what, rb, "count", inScope.size());
      c.expect(what, rb, "total_sec", inScope.stream().mapToDouble(i -> i[1] - i[0]).sum());
      var events = rb.getAsJsonArray("events");
      for (int k = 0; k < Math.min(events.size(), inScope.size()); k++) {
        var e = events.get(k).getAsJsonObject();
        var i = inScope.get(k);
        c.expect(what + "[" + k + "]", e, "start", i[0]);
        c.expect(what + "[" + k + "]", e, "end", i[1]);
        c.expect(what + "[" + k + "]", e, "duration_sec", i[1] - i[0]);
      }
    }
  }

  // ==================== analyze_can_bus ====================

  static void canBus(Context c, JsonObject can) {
    if (!ok(can)) return;
    for (var element : can.getAsJsonArray("buses")) {
      var bus = element.getAsJsonObject();
      var entries = bus.getAsJsonObject("entries");
      for (var role : List.of("utilization", "tec", "rec", "bus_off", "tx_full")) {
        if (!entries.has(role) || !bus.has(role)) continue;
        var entry = str(entries, role);
        var what = "analyze_can_bus " + str(bus, "bus") + " " + role + " (" + entry + ")";
        var s = c.usable(entry, what);
        if (s == null) continue;
        var times = new ArrayList<Double>();
        var values = new ArrayList<Double>();
        for (int i = 0; i < s.n; i++) {
          if (Double.isFinite(s.values[i])) {
            times.add(s.times[i]);
            values.add(s.values[i]);
          }
        }
        var got = bus.getAsJsonObject(role);
        c.expect(what, got, "samples", values.size());
        if (values.isEmpty()) continue;
        var enabled = new boolean[values.size()];
        for (int i = 0; i < enabled.length; i++) {
          enabled[i] = Boolean.TRUE.equals(c.enabledAt(times.get(i)));
        }
        switch (role) {
          case "utilization" -> utilization(c, what, got, values, enabled);
          case "tec", "rec" -> level(c, what, got, times, values, enabled);
          default -> counter(c, what, got, values, enabled);
        }
      }
    }
  }

  private static void utilization(Context c, String what, JsonObject got, List<Double> values,
      boolean[] enabled) {
    boolean fraction = values.stream().mapToDouble(Double::doubleValue).max().orElse(0) <= 1.0;
    double scale = fraction ? 100 : 1;
    c.compared++;
    if (String.valueOf(str(got, "unit_detected")).startsWith("fraction") != fraction) {
      c.disagree(what + " unit_detected: " + got.get("unit_detected") + ", independently "
          + (fraction ? "a fraction" : "percent"));
    }
    var scaled = values.stream().map(v -> v * scale).toList();
    var sorted = sorted(scaled);
    c.expect(what, got, "mean_percent", mean(scaled));
    c.expect(what, got, "p95_percent", DifferentialChecks.percentile(sorted, 95));
    c.expect(what, got, "max_percent", sorted[sorted.length - 1]);
    if (!c.enabledKnown) return;
    var whileEnabled = new ArrayList<Double>();
    for (int i = 0; i < enabled.length; i++) if (enabled[i]) whileEnabled.add(scaled.get(i));
    if (whileEnabled.isEmpty()) {
      c.expectAbsent(what, got, "while_enabled");
      return;
    }
    var e = got.getAsJsonObject("while_enabled");
    c.expect(what + " while_enabled", e, "samples", whileEnabled.size());
    c.expect(what + " while_enabled", e, "mean_percent", mean(whileEnabled));
    c.expect(what + " while_enabled", e, "max_percent",
        whileEnabled.stream().mapToDouble(Double::doubleValue).max().orElseThrow());
  }

  /** TEC and REC are levels: maximum, rises to error-passive, and the time held there. */
  private static void level(Context c, String what, JsonObject got, List<Double> times,
      List<Double> values, boolean[] enabled) {
    int max = 0;
    int maxEnabled = -1;
    int excursions = 0;
    int excursionsEnabled = 0;
    double timeAbove = 0;
    boolean above = false;
    for (int i = 0; i < values.size(); i++) {
      double v = values.get(i);
      if (v > values.get(max)) max = i;
      if (enabled[i] && (maxEnabled < 0 || v > values.get(maxEnabled))) maxEnabled = i;
      boolean nowAbove = v >= ERROR_PASSIVE;
      if (nowAbove && !above) {
        excursions++;
        if (enabled[i]) excursionsEnabled++;
      }
      above = nowAbove;
      if (nowAbove) {
        double next = i + 1 < values.size() ? times.get(i + 1) : c.logEnd;
        timeAbove += Math.max(0, next - times.get(i));
      }
    }
    c.expect(what, got, "max", values.get(max));
    c.expect(what, got, "max_time_sec", times.get(max));
    c.expect(what, got, "error_passive_excursions", excursions);
    c.expect(what, got, "time_error_passive_sec", timeAbove);
    if (!c.enabledKnown) return;
    if (maxEnabled < 0) {
      c.expectAbsent(what, got, "while_enabled");
      return;
    }
    var e = got.getAsJsonObject("while_enabled");
    c.expect(what + " while_enabled", e, "max", values.get(maxEnabled));
    c.expect(what + " while_enabled", e, "max_time_sec", times.get(maxEnabled));
    c.expect(what + " while_enabled", e, "error_passive_excursions", excursionsEnabled);
  }

  /** Bus-off and TX-full only grow: the rises add up, and a fall is a counter reset. */
  private static void counter(Context c, String what, JsonObject got, List<Double> values,
      boolean[] enabled) {
    double increase = 0;
    double increaseEnabled = 0;
    int resets = 0;
    for (int i = 1; i < values.size(); i++) {
      double delta = values.get(i) - values.get(i - 1);
      if (delta > 0) {
        increase += delta;
        if (enabled[i]) increaseEnabled += delta;
      } else if (delta < 0) {
        resets++;
      }
    }
    c.expect(what, got, "first", values.get(0));
    c.expect(what, got, "last", values.get(values.size() - 1));
    c.expect(what, got, "increase", increase);
    if (c.enabledKnown) c.expect(what, got, "increase_while_enabled", increaseEnabled);
    if (resets > 0) c.expect(what, got, "resets", resets);
    else c.expectAbsent(what, got, "resets");
  }

  // ==================== analyze_swerve ====================

  /** Per module of a SwerveModuleState array: how many states, and their mean and peak |speed|. */
  static void swerve(Context c, JsonObject swerve) {
    if (!ok(swerve) || !"array".equals(str(swerve, "layout"))) return;
    if (!"all".equals(str(swerve.getAsJsonObject("scope"), "scope"))) {
      c.notes.add("analyze_swerve not compared: the default scope is not 'all'");
      return;
    }
    for (var element : swerve.getAsJsonArray("modules")) {
      var module = element.getAsJsonObject();
      var entry = str(module, "measured_entry");
      int index = module.get("index").getAsInt();
      var what = "analyze_swerve " + str(module, "module") + " (" + entry + ")";
      var s = c.usable(entry, what);
      if (s == null) continue;
      if (!s.type.startsWith("struct:") || !s.type.endsWith("[]")) {
        c.notes.add(what + " not compared: type " + s.type);
        continue;
      }
      var layout = layout(s.type.substring("struct:".length(), s.type.length() - 2),
          c.schemas(), 0);
      var speed = layout == null ? null : layout.fields().get("speed");
      if (speed == null || layout.size() == 0) {
        c.notes.add(what + " not compared: no schema with a speed field");
        continue;
      }
      int samples = 0;
      int nonFinite = 0;
      double sum = 0;
      double peak = 0;
      for (var payload : s.payloads) {
        if (payload.length % layout.size() != 0 || index >= payload.length / layout.size()) continue;
        double v = read(payload, index * layout.size() + speed.offset(), speed.type());
        if (!Double.isFinite(v)) {
          nonFinite++;
          continue;
        }
        samples++;
        sum += Math.abs(v);
        peak = Math.max(peak, Math.abs(v));
      }
      if (nonFinite > 0) {
        c.notes.add(what + " not compared: " + nonFinite + " speeds are not finite");
        continue;
      }
      c.expect(what, module, "samples", samples);
      if (samples > 0) {
        c.expect(what, module, "mean_abs_speed_mps", sum / samples);
        c.expect(what, module, "max_abs_speed_mps", peak);
      }
    }
  }

  record Field(int offset, String type) {}

  record Layout(int size, Map<String, Field> fields) {}

  /**
   * A struct's size and field offsets, from the schema the log carries, by WPILib's struct format
   * (fields packed in order, little-endian, nested structs by their own schema). Null for what
   * this does not decode: an unknown type, a bit-field, or a schema missing from the log.
   */
  static Layout layout(String struct, Map<String, String> schemas, int depth) {
    var schema = schemas.get(struct);
    if (schema == null || depth > 8) return null;
    int offset = 0;
    var fields = new LinkedHashMap<String, Field>();
    for (var raw : schema.split(";")) {
      var declaration = raw.strip();
      if (declaration.isEmpty()) continue;
      if (declaration.startsWith("enum")) {
        int close = declaration.indexOf('}');
        if (close < 0) return null;
        declaration = declaration.substring(close + 1).strip();
      }
      var parts = declaration.split("\\s+");
      if (parts.length != 2 || parts[1].contains(":")) return null;
      var type = parts[0];
      var name = parts[1];
      int count = 1;
      int bracket = name.indexOf('[');
      if (bracket >= 0) {
        count = Integer.parseInt(name.substring(bracket + 1, name.indexOf(']')).strip());
        name = name.substring(0, bracket);
      }
      int size = switch (type) {
        case "bool", "char", "int8", "uint8" -> 1;
        case "int16", "uint16" -> 2;
        case "int32", "uint32", "float", "float32" -> 4;
        case "int64", "uint64", "double", "float64" -> 8;
        default -> {
          var nested = layout(type, schemas, depth + 1);
          yield nested == null ? -1 : nested.size();
        }
      };
      if (size < 0) return null;
      fields.put(name, new Field(offset, type));
      offset += size * count;
    }
    return new Layout(offset, fields);
  }

  private static double read(byte[] payload, int offset, String type) {
    var b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    return switch (type) {
      case "double", "float64" -> b.getDouble(offset);
      case "float", "float32" -> b.getFloat(offset);
      default -> Double.NaN;
    };
  }
}
