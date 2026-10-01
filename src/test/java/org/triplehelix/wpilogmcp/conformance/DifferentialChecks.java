/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

/**
 * Compares what the tools say about a log with what {@link IndependentLog} reads from the same
 * file: the time range, every entry's sample count, the statistics of its most-sampled numeric
 * entries (and of those holding NaN or infinite values), and the enabled windows.
 *
 * <p>A conformance sweep shows the tools keep their contract; it cannot show a number is right.
 * This does: on other teams' published logs it found a rule that dropped every record with a
 * negative timestamp, which the sweep had passed.
 */
final class DifferentialChecks {
  private DifferentialChecks() {}

  static final int ENTRIES_PER_LOG = 8;
  static final double RELATIVE = 1e-9;
  static final double ABSOLUTE = 1e-9;

  record Outcome(int entries, long records, int countsCompared, int statisticsCompared,
      int windowsCompared, List<String> findings, List<String> notes) {}

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

  /** Linear interpolation between closest ranks (rank p * (n - 1)), on sorted values. */
  static double percentile(double[] sorted, double p) {
    double rank = p / 100.0 * (sorted.length - 1);
    int lo = (int) Math.floor(rank);
    int hi = (int) Math.ceil(rank);
    return sorted[lo] + (rank - lo) * (sorted[hi] - sorted[lo]);
  }

  static Outcome compare(ToolRegistry registry, Path log) throws Exception {
    var independent = IndependentLog.read(log);
    var findings = new ArrayList<String>();
    var notes = new ArrayList<String>();
    int countsCompared = 0;
    int statisticsCompared = 0;
    int windowsCompared = 0;

    var listed = call(registry, "list_entries", log);
    if (!"ok".equals(status(listed))) {
      if (independent.dataRecords > 0 || !independent.series.isEmpty()) {
        findings.add("list_entries is " + status(listed) + " but the file holds "
            + independent.series.size() + " entries: " + listed);
      }
      return new Outcome(independent.series.size(), independent.dataRecords, 0, 0, 0, findings,
          notes);
    }
    // The server may set records aside only where this reader sees the reason itself: a file
    // that stops being a log (then the few records just before that point may go with it), or
    // a timestamp more than a day past everything before it. Its own warning excuses nothing:
    // a rule that discarded every negative timestamp said "ignored" too.
    boolean damaged = independent.stopped != null;
    if (damaged) notes.add("the independent reader stopped: " + independent.stopped);
    if (independent.dayJumps > 0) {
      notes.add(independent.dayJumps + " records jump more than a day and were set aside");
    }
    boolean truncated = listed.has("truncated") && listed.get("truncated").getAsBoolean();
    boolean expectTruncated = damaged || independent.dayJumps > 0;
    if (truncated != expectTruncated) {
      findings.add("truncated is " + truncated + (listed.has("warning") ? " (" + listed.get("warning")
          .getAsString() + ")" : "") + ", but the independent reader " + (expectTruncated
              ? "found damage: " + independent.stopped : "read the whole file and found none"));
    }

    if (independent.dataRecords > 0) {
      var range = listed.getAsJsonObject("time_range_sec");
      double start = range.get("start").getAsDouble();
      double end = range.get("end").getAsDouble();
      boolean same = Math.abs(start - independent.minTime) < 1e-6
          && Math.abs(end - independent.maxTime) < 1e-6;
      if (!same && !(damaged && start >= independent.minTime - 1e-6
          && end <= independent.maxTime + 1e-6)) {
        findings.add("time range " + start + " to " + end + ", independently "
            + independent.minTime + " to " + independent.maxTime);
      }
    }

    var serverCounts = new java.util.HashMap<String, Integer>();
    for (var e : listed.getAsJsonArray("entries")) {
      var o = e.getAsJsonObject();
      serverCounts.put(o.get("name").getAsString(), o.get("sample_count").getAsInt());
    }
    int countMismatches = 0;
    for (var s : independent.series.values()) {
      countsCompared++;
      var server = serverCounts.get(s.name);
      // Near damage the server may drop the last records before it, and no others
      boolean agrees = server != null && server <= s.records
          && server >= s.records - independent.nearDamage(s.name);
      if (!agrees && countMismatches++ < 5) {
        findings.add("sample count of " + s.name + ": " + server + ", independently " + s.records);
      }
    }
    if (countMismatches > 5) findings.add((countMismatches - 5) + " more sample counts differ");

    // Statistics: the most-sampled numeric entries, and any that hold non-finite values
    var numeric = independent.series.values().stream()
        .filter(s -> IndependentLog.NUMERIC.contains(s.type) && s.n > 0)
        .sorted(Comparator.comparingInt((IndependentLog.Series s) -> -s.n)
            .thenComparing(s -> s.name))
        .toList();
    var chosen = new LinkedHashSet<IndependentLog.Series>();
    numeric.stream().limit(ENTRIES_PER_LOG).forEach(chosen::add);
    numeric.stream().filter(s -> s.finiteSorted().length < s.n).limit(4).forEach(chosen::add);
    for (var s : chosen) {
      if (!Integer.valueOf(s.records).equals(serverCounts.get(s.name))) continue;
      var sorted = s.finiteSorted();
      var stats = call(registry, "get_statistics", log, "name", s.name, "scope", "all");
      if (sorted.length == 0) {
        if ("ok".equals(status(stats))) {
          findings.add(s.name + " has no finite value, yet get_statistics is ok: " + stats);
        }
        continue;
      }
      if (!"ok".equals(status(stats))) {
        findings.add("get_statistics(" + s.name + ") is " + status(stats) + ": "
            + (stats.has("error") ? stats.get("error") : stats.get("reason")));
        continue;
      }
      double sum = 0;
      for (double v : sorted) sum += v;
      double mean = sum / sorted.length;
      double squares = 0;
      for (double v : sorted) squares += (v - mean) * (v - mean);
      var expected = new java.util.LinkedHashMap<String, Double>();
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
      for (var e : expected.entrySet()) {
        statisticsCompared++;
        var got = stats.get(e.getKey());
        if (got == null || got.isJsonNull() || !close(got.getAsDouble(), e.getValue())) {
          findings.add(s.name + " " + e.getKey() + ": " + got + ", independently " + e.getValue());
        }
      }
      // The quality block must describe the same samples: all of them, less the non-finite
      var quality = stats.getAsJsonObject("data_quality");
      if (quality != null) {
        int all = quality.get("sample_count").getAsInt();
        int nonFinite = quality.has("nan_filtered") ? quality.get("nan_filtered").getAsInt() : 0;
        if (all != s.n || all - nonFinite != sorted.length) {
          findings.add(s.name + " data_quality counts " + all + " samples, " + nonFinite
              + " non-finite; independently " + s.n + " and " + (s.n - sorted.length));
        }
      }
    }

    // Enabled windows, from the entry the server says it read
    var phases = call(registry, "get_match_phases", log);
    if ("ok".equals(status(phases))) {
      var inputs = phases.getAsJsonObject("inputs").getAsJsonObject("entries");
      var source = inputs.has("enabled") ? independent.series.get(inputs.get("enabled").getAsString())
          : null;
      if (source != null && (source.type.equals("boolean") || source.type.equals("int64"))) {
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
        if (!Double.isNaN(opened)) windows.add(new double[] {opened, independent.maxTime});
        var got = new ArrayList<double[]>();
        for (var seg : phases.getAsJsonArray("segments")) {
          var o = seg.getAsJsonObject();
          if ("enabled".equals(o.get("state").getAsString())) {
            got.add(new double[] {o.get("start").getAsDouble(), o.get("end").getAsDouble()});
          }
        }
        windowsCompared++;
        boolean same = got.size() == windows.size();
        for (int i = 0; same && i < got.size(); i++) {
          same = Math.abs(got.get(i)[0] - windows.get(i)[0]) < 1e-6
              && Math.abs(got.get(i)[1] - windows.get(i)[1]) < 1e-6;
        }
        if (!same && !damaged) {
          findings.add("enabled windows from " + source.name + ": " + got.size() + " "
              + got.stream().map(java.util.Arrays::toString).toList() + ", independently "
              + windows.stream().map(java.util.Arrays::toString).toList());
        }
      } else {
        notes.add("enabled windows not compared: the source is "
            + (inputs.has("enabled") ? inputs.get("enabled") : "not named"));
      }
    }
    return new Outcome(independent.series.size(), independent.dataRecords, countsCompared,
        statisticsCompared, windowsCompared, findings, notes);
  }

  static List<String> describe(String id, Outcome o) {
    var lines = new ArrayList<String>();
    lines.add(id + ": " + o.entries() + " entries, " + o.records() + " records; "
        + o.countsCompared() + " counts, " + o.statisticsCompared() + " statistics, "
        + o.windowsCompared() + " timelines compared; "
        + (o.findings().isEmpty() ? "agree" : o.findings().size() + " DISAGREE"));
    o.findings().forEach(f -> lines.add("    DISAGREE " + f));
    o.notes().forEach(n -> lines.add("    note " + n));
    return lines;
  }
}
