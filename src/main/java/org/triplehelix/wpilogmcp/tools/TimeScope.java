/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * The time windows a statistic is computed over: the whole log, the robot's enabled or disabled
 * time, a mode, or one enabled segment, optionally intersected with {@code start_time} /
 * {@code end_time}. Built from the same {@link MatchTimeline} as {@code get_match_phases}, so a
 * scope means the same thing in every tool.
 *
 * <p>Scopes: {@code all} (default), {@code enabled}, {@code disabled}, {@code auto},
 * {@code teleop}, {@code test}, {@code segment:<i>} (the i-th enabled segment, from 0).
 *
 * @since 0.9.0
 */
public final class TimeScope {

  /**
   * A time window. Segment windows are half-open, [start, end), because the sample at a
   * transition belongs to the new state (the robot is disabled at the disable timestamp); a
   * window ending at the end of the log or at an explicit end_time includes its end.
   */
  public record Window(double start, double end, boolean endInclusive) {
    public double duration() {
      return Math.max(0, end - start);
    }

    public boolean contains(double t) {
      return t >= start && (endInclusive ? t <= end : t < end);
    }
  }

  /** Scope values accepted by tools, for schemas and error messages. */
  public static final String SCOPE_DESCRIPTION = "Time scope: 'all' (default), 'enabled', "
      + "'disabled', 'auto', 'teleop', 'test', or 'segment:<i>' (the i-th enabled segment from "
      + "get_match_phases, counting from 0). Combined with start_time/end_time when both are given.";

  /** The {@code windows} parameter, for schemas. */
  public static final String WINDOWS_DESCRIPTION = "Explicit time windows, each {start, end} in "
      + "seconds, half-open [start, end) like get_match_phases segments, e.g. the intervals "
      + "returned by find_condition (whose end is the sample where the condition turned false). "
      + "Intersected with scope and start_time/end_time.";

  public static final String LAST_SECONDS_DESCRIPTION = "Last N seconds ending at the call's current robot time for an open capture, or the last record for a closed log. N must be positive and finite. Cannot combine with start_time/end_time; scope and windows still intersect. inputs.window gives the resolved absolute bounds.";

  static boolean acceptsRelative(JsonObject properties) {
    return properties != null && List.of("scope", "windows", "start_time", "end_time").stream().anyMatch(properties::has);
  }

  /** Resolve once on the acquired view, before any tool reads entries or the writer appends. */
  static JsonObject relativeArguments(LogData log, JsonObject arguments) {
    if (!arguments.has("last_seconds") || arguments.get("last_seconds").isJsonNull()) return arguments;
    var number = arguments.get("last_seconds");
    if (!number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber()) {
      throw new IllegalArgumentException("last_seconds must be a positive finite number");
    }
    double seconds = number.getAsDouble();
    if (!Double.isFinite(seconds) || seconds <= 0) throw new IllegalArgumentException("last_seconds must be a positive finite number");
    for (String key : List.of("start_time", "end_time")) if (arguments.has(key) && !arguments.get(key).isJsonNull()) {
      throw new IllegalArgumentException("last_seconds cannot be combined with " + key);
    }
    double end = log.timeScopeEnd();
    var resolved = arguments.deepCopy(); resolved.remove("last_seconds");
    resolved.addProperty("start_time", Math.max(log.minTimestamp(), end - seconds));
    resolved.addProperty("end_time", end);
    return resolved;
  }

  /** Windows listed in {@link #toJson()}, at most; the count is always complete. */
  static final int LISTED_WINDOWS = 50;

  /** Item schema of the {@code windows} parameter. */
  public static JsonObject windowItemSchema() {
    var item = new JsonObject();
    item.addProperty("type", "object");
    var properties = new JsonObject();
    for (var key : List.of("start", "end")) {
      var p = new JsonObject();
      p.addProperty("type", "number");
      properties.add(key, p);
    }
    item.add("properties", properties);
    var required = new JsonArray();
    required.add("start");
    required.add("end");
    item.add("required", required);
    return item;
  }

  /**
   * Resolves the scope named by a tool's arguments: {@code scope}, {@code windows},
   * {@code start_time}, and {@code end_time}, all optional.
   *
   * @throws IllegalArgumentException for an unknown scope or a malformed window
   */
  public static TimeScope fromArguments(LogData log, MatchTimeline timeline, JsonObject args) {
    var scopeArg = args.has("scope") && !args.get("scope").isJsonNull()
        ? args.get("scope").getAsString() : null;
    var start = ToolUtils.getOptDouble(args, "start_time");
    var end = ToolUtils.getOptDouble(args, "end_time");
    var scope = resolve(log, timeline, scopeArg, start, end);
    if (!args.has("windows") || args.get("windows").isJsonNull()) return scope;
    var given = parseWindows(args.get("windows"));
    var name = scope.name().equals("all") ? "windows" : scope.name() + "+windows";
    return new TimeScope(name, intersect(scope.windows(), given), true, start, end);
  }

  /** Parses [{start, end}, ...] or [[start, end], ...]; sorted and merged where they overlap. */
  static List<Window> parseWindows(com.google.gson.JsonElement element) {
    if (!element.isJsonArray()) {
      throw new IllegalArgumentException("windows must be an array of {start, end} objects");
    }
    var out = new ArrayList<Window>();
    for (var item : element.getAsJsonArray()) {
      double start;
      double end;
      try {
        if (item.isJsonObject()) {
          start = item.getAsJsonObject().get("start").getAsDouble();
          end = item.getAsJsonObject().get("end").getAsDouble();
        } else if (item.isJsonArray() && item.getAsJsonArray().size() == 2) {
          start = item.getAsJsonArray().get(0).getAsDouble();
          end = item.getAsJsonArray().get(1).getAsDouble();
        } else {
          throw new IllegalArgumentException();
        }
      } catch (RuntimeException e) {
        throw new IllegalArgumentException("Each window must be {start, end} (or [start, end]) "
            + "in seconds, got " + item);
      }
      if (!Double.isFinite(start) || !Double.isFinite(end) || end < start) {
        throw new IllegalArgumentException("Window " + item + " must have finite start <= end");
      }
      out.add(new Window(start, end, end == start)); // half-open; a point is inclusive
    }
    out.sort(java.util.Comparator.comparingDouble(Window::start));
    var merged = new ArrayList<Window>();
    for (var w : out) {
      if (!merged.isEmpty() && w.start() <= merged.get(merged.size() - 1).end()) {
        var last = merged.remove(merged.size() - 1);
        boolean inclusive = w.end() > last.end() ? w.endInclusive()
            : w.end() < last.end() ? last.endInclusive() : w.endInclusive() || last.endInclusive();
        merged.add(new Window(last.start(), Math.max(last.end(), w.end()), inclusive));
      } else {
        merged.add(w);
      }
    }
    return merged;
  }

  /** The overlap of two sorted, disjoint window lists. */
  static List<Window> intersect(List<Window> a, List<Window> b) {
    var out = new ArrayList<Window>();
    for (var x : a) {
      for (var y : b) {
        double s = Math.max(x.start(), y.start());
        double e = Math.min(x.end(), y.end());
        boolean inclusive = (e < x.end() || x.endInclusive()) && (e < y.end() || y.endInclusive());
        if (e > s || (e == s && inclusive)) out.add(new Window(s, e, inclusive));
      }
    }
    out.sort(java.util.Comparator.comparingDouble(Window::start));
    return out;
  }

  private final String scope;
  private final List<Window> windows;
  private final boolean explicit;
  private final Double start;
  private final Double end;

  private TimeScope(String scope, List<Window> windows, boolean explicit, Double start,
      Double end) {
    this.scope = scope;
    this.windows = List.copyOf(windows);
    this.explicit = explicit;
    this.start = start;
    this.end = end;
  }

  /**
   * Resolves a scope for a log.
   *
   * @param log The log
   * @param timeline The log's timeline (built on demand when null and needed)
   * @param scope The scope name, or null for {@code all}
   * @param start Optional start bound
   * @param end Optional end bound
   * @throws IllegalArgumentException for an unknown scope, a segment index out of range, or a
   *     state scope on a log without DriverStation data
   */
  public static TimeScope resolve(LogData log, MatchTimeline timeline, String scope, Double start,
      Double end) {
    var name = scope == null || scope.isBlank() ? "all" : scope.strip().toLowerCase(Locale.ROOT);
    List<Window> base;
    if (name.equals("all")) {
      base = List.of(new Window(log.minTimestamp(), log.maxTimestamp(), true));
    } else {
      var tl = timeline != null ? timeline : MatchTimeline.of(log);
      if (!tl.hasEnabledData()) {
        throw new IllegalArgumentException("scope '" + name + "' needs the robot's enabled "
            + "state, but this log has no DriverStation state entries; use scope 'all' with "
            + "start_time/end_time instead");
      }
      base = new ArrayList<>();
      if (name.startsWith("segment:")) {
        int index;
        try {
          index = Integer.parseInt(name.substring("segment:".length()).strip());
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("scope 'segment:<i>' needs an integer index, got '"
              + scope + "'");
        }
        var enabled = tl.enabledSegments();
        if (index < 0 || index >= enabled.size()) {
          throw new IllegalArgumentException("scope '" + scope + "': the log has "
              + enabled.size() + " enabled segment(s), indexed from 0");
        }
        var s = enabled.get(index);
        base.add(window(s));
      } else {
        for (var s : tl.segments()) {
          boolean include = switch (name) {
            case "enabled" -> s.state() == MatchTimeline.State.ENABLED;
            case "disabled" -> s.state() == MatchTimeline.State.DISABLED;
            case "auto" -> s.state() == MatchTimeline.State.ENABLED
                && s.mode() == MatchTimeline.Mode.AUTO;
            case "teleop" -> s.state() == MatchTimeline.State.ENABLED
                && s.mode() == MatchTimeline.Mode.TELEOP;
            case "test" -> s.state() == MatchTimeline.State.ENABLED
                && s.mode() == MatchTimeline.Mode.TEST;
            default -> throw new IllegalArgumentException("Unknown scope '" + scope + "'. "
                + SCOPE_DESCRIPTION);
          };
          if (include) base.add(window(s));
        }
      }
    }
    ToolUtils.validateTimeRange(start, end);
    var clipped = new ArrayList<Window>();
    for (var w : base) {
      double s = start != null ? Math.max(w.start(), start) : w.start();
      boolean clippedEnd = end != null && end <= w.end();
      double e = clippedEnd ? end : w.end();
      boolean inclusive = clippedEnd || w.endInclusive();
      if (e > s || (e == s && inclusive)) clipped.add(new Window(s, e, inclusive));
    }
    return new TimeScope(name, clipped, false, start, end);
  }

  private static Window window(MatchTimeline.Segment s) {
    return new Window(s.start(), s.end(), s.endReason() == MatchTimeline.EndReason.LOG_END);
  }

  /** Whether this is the plain {@code all} scope (at most start_time/end_time applied). */
  public boolean isAll() {
    return !explicit && scope.equals("all");
  }

  /** The start_time argument, or null. */
  public Double requestedStart() {
    return start;
  }

  /** The end_time argument, or null. */
  public Double requestedEnd() {
    return end;
  }

  /**
   * The values that fall in each window, as separate lists (values must be sorted by timestamp).
   * Tools compute differences, peaks, and unwrapped angles within a window, never across the gap
   * between two.
   */
  public List<List<TimestampedValue>> split(List<TimestampedValue> values) {
    var out = new ArrayList<List<TimestampedValue>>(windows.size());
    for (var w : windows) {
      int from = firstAtOrAfter(values, w.start());
      int to = from;
      while (to < values.size() && w.contains(values.get(to).timestamp())) to++;
      out.add(values.subList(from, to));
    }
    return out;
  }

  /** The values in any window, in time order. */
  public List<TimestampedValue> filter(List<TimestampedValue> values) {
    if (windows.size() == 1) return split(values).get(0);
    var out = new ArrayList<TimestampedValue>();
    split(values).forEach(out::addAll);
    return out;
  }

  private static int firstAtOrAfter(List<TimestampedValue> values, double t) {
    int lo = 0;
    int hi = values.size();
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (values.get(mid).timestamp() < t) lo = mid + 1;
      else hi = mid;
    }
    return lo;
  }

  /** Whether {@code t} lies in any window. */
  public boolean contains(double t) {
    for (var w : windows) {
      if (w.contains(t)) return true;
    }
    return false;
  }

  public List<Window> windows() {
    return windows;
  }

  public String name() {
    return scope;
  }

  public boolean isEmpty() {
    return windows.isEmpty();
  }

  public double totalDuration() {
    return windows.stream().mapToDouble(Window::duration).sum();
  }

  /**
   * {@code {scope, windows: [[start, end], ...], window_count, total_sec}} for {@code inputs};
   * at most {@value #LISTED_WINDOWS} windows are listed.
   */
  public JsonObject toJson() {
    var o = new JsonObject();
    o.addProperty("scope", scope);
    var array = new JsonArray();
    for (var w : windows.subList(0, Math.min(windows.size(), LISTED_WINDOWS))) {
      var pair = new JsonArray();
      pair.add(w.start());
      pair.add(w.end());
      array.add(pair);
    }
    o.add("windows", array);
    o.addProperty("window_count", windows.size());
    o.addProperty("total_sec", totalDuration());
    return o;
  }

  /** A short description for messages: "scope enabled (4 windows, 1052.3 s)". */
  public String describe() {
    return "scope " + scope + " (" + windows.size() + " window" + (windows.size() == 1 ? "" : "s")
        + ", " + String.format(Locale.ROOT, "%.1f", totalDuration()) + " s)";
  }
}
