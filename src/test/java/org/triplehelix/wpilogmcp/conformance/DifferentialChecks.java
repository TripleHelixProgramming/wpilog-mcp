/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

/**
 * Compares what the tools say about a log with what {@link IndependentLog} reads from the same
 * file: the time range, every entry's type and sample count, the statistics of its most-sampled
 * numeric entries (and of those holding NaN or infinite values), the enabled windows, and the
 * domain answers {@link DomainChecks} recomputes from the entries each tool names.
 *
 * <p>A conformance sweep shows the tools keep their contract; it cannot show a number is right.
 * This does: on other teams' published logs it found a rule that dropped every record with a
 * negative timestamp, which the sweep had passed.
 *
 * <p>The server may set records aside only where the independent reader sees the reason itself:
 * a file that stops being a log (then the few records just before that point may go with it),
 * or a timestamp more than a day past everything before it. The server's own warning excuses
 * nothing: the rule that discarded negative timestamps said "ignored" too.
 */
final class DifferentialChecks {
  private DifferentialChecks() {}

  static final int ENTRIES_PER_LOG = 8;
  static final int NON_FINITE_ENTRIES_PER_LOG = 4;
  static final int MISMATCHES_SHOWN = 5;
  static final double RELATIVE = 1e-9;
  static final double ABSOLUTE = 1e-9;
  static final double TIME_TOLERANCE_SEC = 1e-6;

  record Outcome(int entries, long records, int countsCompared, int statisticsCompared,
      int windowsCompared, int domainCompared, List<String> findings, List<String> notes) {}

  static JsonObject call(ToolRegistry registry, String tool, Path log, Object... keyValues)
      throws Exception {
    var args = new JsonObject();
    args.addProperty("path", log.toString());
    for (int i = 0; i < keyValues.length; i += 2) {
      var value = keyValues[i + 1];
      if (value instanceof Number n) args.addProperty((String) keyValues[i], n);
      else args.addProperty((String) keyValues[i], value.toString());
    }
    return registry.getTool(tool).execute(args).getAsJsonObject();
  }

  static String status(JsonObject o) {
    return o.has("status") ? o.get("status").getAsString() : "(none)";
  }

  static boolean close(double a, double b) {
    return Math.abs(a - b) <= Math.max(ABSOLUTE, RELATIVE * Math.max(Math.abs(a), Math.abs(b)));
  }

  private static boolean sameTime(double a, double b) {
    return Math.abs(a - b) < TIME_TOLERANCE_SEC;
  }

  /** Linear interpolation between closest ranks (rank p * (n - 1)), on sorted values. */
  static double percentile(double[] sorted, double p) {
    double rank = p / 100.0 * (sorted.length - 1);
    int lo = (int) Math.floor(rank);
    int hi = (int) Math.ceil(rank);
    return sorted[lo] + (rank - lo) * (sorted[hi] - sorted[lo]);
  }

  /** The statistics get_statistics reports, computed the plain way from sorted finite values. */
  static Map<String, Double> statistics(double[] sorted) {
    double sum = 0;
    for (double v : sorted) sum += v;
    double mean = sum / sorted.length;
    double squares = 0;
    for (double v : sorted) squares += (v - mean) * (v - mean);
    var expected = new LinkedHashMap<String, Double>();
    expected.put("count", (double) sorted.length);
    expected.put("min", sorted[0]);
    expected.put("max", sorted[sorted.length - 1]);
    expected.put("mean", mean);
    expected.put("median", percentile(sorted, 50));
    if (sorted.length > 1) expected.put("std_dev", Math.sqrt(squares / (sorted.length - 1)));
    expected.put("q1", percentile(sorted, 25));
    expected.put("q3", percentile(sorted, 75));
    expected.put("p5", percentile(sorted, 5));
    expected.put("p95", percentile(sorted, 95));
    return expected;
  }

  /** The windows in which a boolean (or a control word's low bit) reads enabled. */
  static List<double[]> enabledWindows(IndependentLog.Series source, double logEnd) {
    var windows = new ArrayList<double[]>();
    double opened = Double.NaN;
    for (int i = 0; i < source.n; i++) {
      boolean enabled = source.type.equals("boolean") ? source.values[i] != 0
          : (((long) source.values[i]) & 1) != 0;
      if (enabled && Double.isNaN(opened)) {
        opened = source.times[i];
      } else if (!enabled && !Double.isNaN(opened)) {
        windows.add(new double[] {opened, source.times[i]});
        opened = Double.NaN;
      }
    }
    if (!Double.isNaN(opened)) windows.add(new double[] {opened, logEnd});
    return windows;
  }

  static Outcome compare(ToolRegistry registry, Path log) throws Exception {
    // First pass: every entry's record count, keeping no values
    var survey = IndependentLog.read(log, Set.of());
    var findings = new ArrayList<String>();
    var notes = new ArrayList<String>();
    int countsCompared = 0;
    int statisticsCompared = 0;
    int windowsCompared = 0;

    var listed = call(registry, "list_entries", log);
    if (!"ok".equals(status(listed))) {
      if (survey.dataRecords > 0 || !survey.series.isEmpty()) {
        findings.add("list_entries is " + status(listed) + " but the file holds "
            + survey.series.size() + " entries: " + listed);
      }
      return new Outcome(survey.series.size(), survey.dataRecords, 0, 0, 0, 0, findings, notes);
    }
    boolean damaged = survey.stopped != null;
    if (damaged) notes.add("the independent reader stopped: " + survey.stopped);
    if (survey.dayJumps > 0) {
      notes.add(survey.dayJumps + " records jump more than a day and were set aside");
    }
    boolean truncated = listed.has("truncated") && listed.get("truncated").getAsBoolean();
    boolean expectTruncated = damaged || survey.dayJumps > 0;
    if (truncated != expectTruncated) {
      findings.add("truncated is " + truncated + (listed.has("warning") ? " ("
          + listed.get("warning").getAsString() + ")" : "") + ", but the independent reader "
          + (expectTruncated ? "found damage: " + survey.stopped
              : "read the whole file and found none"));
    }

    if (survey.dataRecords > 0) {
      var range = listed.getAsJsonObject("time_range_sec");
      double start = range.get("start").getAsDouble();
      double end = range.get("end").getAsDouble();
      boolean same = sameTime(start, survey.minTime) && sameTime(end, survey.maxTime);
      // Records set aside near damage can only narrow the range
      boolean narrowed = damaged && start >= survey.minTime - TIME_TOLERANCE_SEC
          && end <= survey.maxTime + TIME_TOLERANCE_SEC;
      if (!same && !narrowed) {
        findings.add("time range " + start + " to " + end + ", independently " + survey.minTime
            + " to " + survey.maxTime);
      }
    }

    var serverCounts = new HashMap<String, Integer>();
    var serverTypes = new HashMap<String, String>();
    for (var e : listed.getAsJsonArray("entries")) {
      var o = e.getAsJsonObject();
      serverCounts.put(o.get("name").getAsString(), o.get("sample_count").getAsInt());
      serverTypes.put(o.get("name").getAsString(), o.get("type").getAsString());
    }
    int mismatches = 0;
    for (var s : survey.series.values()) {
      countsCompared++;
      var server = serverCounts.get(s.name);
      // Near damage the server may drop the last records before it, and no others
      boolean agrees = server != null && server <= s.records
          && server >= s.records - survey.nearDamage(s.name);
      if (!agrees && mismatches++ < MISMATCHES_SHOWN) {
        findings.add("sample count of " + s.name + ": " + server + ", independently " + s.records);
      }
      if (server != null && !s.type.equals(serverTypes.get(s.name))
          && mismatches++ < MISMATCHES_SHOWN) {
        findings.add("type of " + s.name + ": " + serverTypes.get(s.name) + ", independently "
            + s.type);
      }
    }
    for (var name : serverCounts.keySet()) {
      if (!survey.series.containsKey(name) && mismatches++ < MISMATCHES_SHOWN) {
        findings.add("the server lists " + name + ", which the file does not declare");
      }
    }
    if (mismatches > MISMATCHES_SHOWN) {
      findings.add((mismatches - MISMATCHES_SHOWN) + " more entries differ");
    }

    // Second pass: the values of the most-sampled numeric entries, of some that hold non-finite
    // values, and of the entry the server says it read the enabled state from
    var numeric = survey.series.values().stream()
        .filter(s -> s.numeric > 0)
        .sorted(Comparator.comparingInt((IndependentLog.Series s) -> -s.numeric)
            .thenComparing(s -> s.name))
        .toList();
    var chosen = new LinkedHashSet<String>();
    numeric.stream().limit(ENTRIES_PER_LOG).forEach(s -> chosen.add(s.name));
    numeric.stream().filter(s -> s.finite < s.numeric).limit(NON_FINITE_ENTRIES_PER_LOG)
        .forEach(s -> chosen.add(s.name));
    var phases = call(registry, "get_match_phases", log);
    String enabledName = null;
    if ("ok".equals(status(phases))) {
      var inputs = phases.getAsJsonObject("inputs").getAsJsonObject("entries");
      // A boolean enabled entry, or the FMS control word, whose lowest bit is "enabled"
      if (inputs.has("enabled")) enabledName = inputs.get("enabled").getAsString();
      else if (inputs.has("control_word")) enabledName = inputs.get("control_word").getAsString();
    }
    // The domain answers, recomputed below from the entries each tool names
    var answers = new DomainChecks.Answers(phases, call(registry, "analyze_loop_timing", log),
        call(registry, "power_analysis", log), call(registry, "get_ds_timeline", log),
        call(registry, "analyze_can_bus", log), call(registry, "analyze_swerve", log));
    var keep = new LinkedHashSet<>(chosen);
    if (enabledName != null) keep.add(enabledName);
    keep.addAll(DomainChecks.entries(answers));
    var detail = IndependentLog.read(log, keep, DomainChecks.rawEntries(answers, survey));

    for (var name : chosen) {
      var s = detail.series.get(name);
      if (!Integer.valueOf(s.records).equals(serverCounts.get(name))) continue;
      var sorted = s.finiteSorted();
      var stats = call(registry, "get_statistics", log, "name", name, "scope", "all");
      if (sorted.length == 0) {
        if ("ok".equals(status(stats))) {
          findings.add(name + " has no finite value, yet get_statistics is ok: " + stats);
        }
        continue;
      }
      if (!"ok".equals(status(stats))) {
        findings.add("get_statistics(" + name + ") is " + status(stats) + ": "
            + (stats.has("error") ? stats.get("error") : stats.get("reason")));
        continue;
      }
      for (var e : statistics(sorted).entrySet()) {
        statisticsCompared++;
        var got = stats.get(e.getKey());
        if (got == null || got.isJsonNull() || !close(got.getAsDouble(), e.getValue())) {
          findings.add(name + " " + e.getKey() + ": " + got + ", independently " + e.getValue());
        }
      }
      // read_entry at a resolution: each bucket's count, extremes, mean, first, and last from
      // the raw records, by the same division of the entry's own span
      statisticsCompared += compareBuckets(registry, log, s, findings);
      // The quality block must describe the same samples: all of them, less the non-finite
      var quality = stats.getAsJsonObject("data_quality");
      if (quality != null) {
        int all = quality.get("sample_count").getAsInt();
        int nonFinite = quality.has("nan_filtered") ? quality.get("nan_filtered").getAsInt() : 0;
        if (all != s.n || all - nonFinite != sorted.length) {
          findings.add(name + " data_quality counts " + all + " samples, " + nonFinite
              + " non-finite; independently " + s.n + " and " + (s.n - sorted.length));
        }
      }
    }

    // Enabled windows, from the entry the server says it read
    if ("ok".equals(status(phases))) {
      var source = enabledName == null ? null : detail.series.get(enabledName);
      if (source != null && (source.type.equals("boolean") || source.type.equals("int64"))) {
        var windows = enabledWindows(source, detail.maxTime);
        // The server splits an enabled stretch where the mode changes (autonomous straight
        // into teleop): adjoining enabled segments are one window
        var got = new ArrayList<double[]>();
        for (var seg : phases.getAsJsonArray("segments")) {
          var o = seg.getAsJsonObject();
          if (!"enabled".equals(o.get("state").getAsString())) continue;
          double start = o.get("start").getAsDouble();
          double end = o.get("end").getAsDouble();
          if (!got.isEmpty() && sameTime(got.get(got.size() - 1)[1], start)) {
            got.get(got.size() - 1)[1] = end;
          } else {
            got.add(new double[] {start, end});
          }
        }
        windowsCompared++;
        boolean same;
        if (!damaged) {
          same = got.size() == windows.size();
          for (int i = 0; same && i < got.size(); i++) {
            same = sameTime(got.get(i)[0], windows.get(i)[0])
                && sameTime(got.get(i)[1], windows.get(i)[1]);
          }
        } else {
          // The last records before the damage may be set aside, and with them the end of the
          // last window or the last window itself. Everything before it must still agree.
          int common = Math.min(got.size(), windows.size());
          same = Math.abs(got.size() - windows.size()) <= 1;
          for (int i = 0; same && i < common - 1; i++) {
            same = sameTime(got.get(i)[0], windows.get(i)[0])
                && sameTime(got.get(i)[1], windows.get(i)[1]);
          }
          if (same && common > 0) {
            same = sameTime(got.get(common - 1)[0], windows.get(common - 1)[0]);
          }
        }
        if (!same) {
          findings.add("enabled windows from " + source.name + ": " + got.size() + " "
              + got.stream().map(Arrays::toString).toList() + ", independently "
              + windows.stream().map(Arrays::toString).toList());
        }
      } else {
        notes.add("enabled windows not compared: the source is "
            + (enabledName == null ? "not named" : enabledName + " ("
                + (source == null ? "not in the file" : source.type) + ")"));
      }
    }
    int domainCompared = 0;
    if (survey.dataRecords > 0) {
      double serverEnd = listed.getAsJsonObject("time_range_sec").get("end").getAsDouble();
      domainCompared = DomainChecks.compare(detail, serverCounts, serverEnd, answers, findings,
          notes);
    }
    return new Outcome(survey.series.size(), survey.dataRecords, countsCompared,
        statisticsCompared, windowsCompared, domainCompared, findings, notes);
  }

  /** How many buckets read_entry is asked for in the differential check. */
  static final int BUCKETS = 50;

  /**
   * Calls read_entry with max_points on the whole entry and recomputes every bucket from the
   * independent reader's times and values: the window is the entry's own span divided into
   * equal buckets, the last one closed. Returns how many bucket fields were compared.
   */
  static int compareBuckets(ToolRegistry registry, Path log, IndependentLog.Series s,
      List<String> findings) throws Exception {
    if (s.n < 2) return 0;
    var read = call(registry, "read_entry", log, "name", s.name, "max_points", BUCKETS);
    if (!"ok".equals(status(read))) {
      findings.add("read_entry(" + s.name + ", max_points) is " + status(read) + ": " + read);
      return 0;
    }
    double start = s.times[0];
    double end = s.times[s.n - 1];
    boolean bucketed = read.get("bucketed").getAsBoolean();
    if (bucketed != (s.n > BUCKETS)) {
      findings.add(s.name + ": bucketed is " + bucketed + " for " + s.n + " samples and max_points "
          + BUCKETS);
      return 0;
    }
    if (!bucketed) return 0;
    double bucketSec = (end - start) / BUCKETS;
    if (!close(read.get("bucket_sec").getAsDouble(), bucketSec)) {
      findings.add(s.name + " bucket_sec: " + read.get("bucket_sec") + ", independently " + bucketSec);
    }
    // Expected buckets, by index
    var expected = new java.util.TreeMap<Integer, double[]>(); // count, min, max, sum, finite, first, last
    for (int i = 0; i < s.n; i++) {
      int index = bucketSec > 0 ? (int) Math.min(BUCKETS - 1, Math.floor((s.times[i] - start) / bucketSec)) : 0;
      var b = expected.computeIfAbsent(index, k -> new double[] {0, Double.POSITIVE_INFINITY,
          Double.NEGATIVE_INFINITY, 0, 0, Double.NaN, Double.NaN});
      double v = s.values[i];
      if (b[0] == 0) b[5] = v;
      b[0]++;
      b[6] = v;
      if (Double.isFinite(v)) {
        b[4]++;
        b[3] += v;
        if (v < b[1]) b[1] = v;
        if (v > b[2]) b[2] = v;
      }
    }
    var samples = read.getAsJsonArray("samples");
    if (samples.size() != expected.size()) {
      findings.add(s.name + ": " + samples.size() + " buckets, independently " + expected.size());
      return 0;
    }
    int compared = 0;
    int shown = 0;
    var indices = new ArrayList<>(expected.keySet());
    for (int k = 0; k < indices.size(); k++) {
      var got = samples.get(k).getAsJsonObject();
      var b = expected.get(indices.get(k));
      var problems = new ArrayList<String>();
      if (!close(got.get("timestamp_sec").getAsDouble(), start + indices.get(k) * bucketSec)) {
        problems.add("start " + got.get("timestamp_sec"));
      }
      if (got.get("count").getAsInt() != (int) b[0]) problems.add("count " + got.get("count") + " vs " + (int) b[0]);
      if (b[4] > 0) {
        if (!close(got.get("min").getAsDouble(), b[1])) problems.add("min " + got.get("min") + " vs " + b[1]);
        if (!close(got.get("max").getAsDouble(), b[2])) problems.add("max " + got.get("max") + " vs " + b[2]);
        if (!close(got.get("mean").getAsDouble(), b[3] / b[4])) problems.add("mean " + got.get("mean") + " vs " + b[3] / b[4]);
      } else if (!got.get("mean").isJsonNull() || !got.get("min").isJsonNull()) {
        problems.add("no finite sample, yet min/mean are " + got.get("min") + "/" + got.get("mean"));
      }
      if (!sameLogged(got.get("first"), b[5])) problems.add("first " + got.get("first") + " vs " + b[5]);
      if (!sameLogged(got.get("last"), b[6])) problems.add("last " + got.get("last") + " vs " + b[6]);
      compared += 7;
      if (!problems.isEmpty() && shown++ < MISMATCHES_SHOWN) {
        findings.add(s.name + " bucket " + indices.get(k) + ": " + String.join(", ", problems));
      }
    }
    return compared;
  }

  /** A logged value as read_entry reports it: a number, or the string "NaN"/"Infinity"/"-Infinity". */
  private static boolean sameLogged(com.google.gson.JsonElement got, double expected) {
    if (got == null || got.isJsonNull()) return false;
    if (got.isJsonPrimitive() && got.getAsJsonPrimitive().isString()) {
      return String.valueOf(expected).equals(got.getAsString());
    }
    return close(got.getAsDouble(), expected);
  }

  static List<String> describe(String id, Outcome o) {
    var lines = new ArrayList<String>();
    lines.add(id + ": " + o.entries() + " entries, " + o.records() + " records; "
        + o.countsCompared() + " counts, " + o.statisticsCompared() + " statistics, "
        + o.windowsCompared() + " timelines, " + o.domainCompared() + " domain answers compared; "
        + (o.findings().isEmpty() ? "agree" : o.findings().size() + " DISAGREE"));
    o.findings().forEach(f -> lines.add("    DISAGREE " + f));
    o.notes().forEach(n -> lines.add("    note " + n));
    return lines;
  }
}
