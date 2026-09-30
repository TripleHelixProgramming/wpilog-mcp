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

  private final String scope;
  private final List<Window> windows;

  private TimeScope(String scope, List<Window> windows) {
    this.scope = scope;
    this.windows = List.copyOf(windows);
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
    var clipped = new ArrayList<Window>();
    for (var w : base) {
      double s = start != null ? Math.max(w.start(), start) : w.start();
      boolean clippedEnd = end != null && end <= w.end();
      double e = clippedEnd ? end : w.end();
      boolean inclusive = clippedEnd || w.endInclusive();
      if (e > s || (e == s && inclusive)) clipped.add(new Window(s, e, inclusive));
    }
    return new TimeScope(name, clipped);
  }

  private static Window window(MatchTimeline.Segment s) {
    return new Window(s.start(), s.end(), s.endReason() == MatchTimeline.EndReason.LOG_END);
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

  /** {@code {scope, windows: [[start, end], ...], total_sec}} for {@code inputs}. */
  public JsonObject toJson() {
    var o = new JsonObject();
    o.addProperty("scope", scope);
    var array = new JsonArray();
    for (var w : windows) {
      var pair = new JsonArray();
      pair.add(w.start());
      pair.add(w.end());
      array.add(pair);
    }
    o.add("windows", array);
    o.addProperty("total_sec", totalDuration());
    return o;
  }
}
