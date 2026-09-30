/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.triplehelix.wpilogmcp.game.GameData;
import org.triplehelix.wpilogmcp.game.GameKnowledgeBase;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * The robot's enabled and mode timeline, derived from the log's DriverStation state entries.
 *
 * <p>DriverStation values are usually logged only when they change (AdvantageKit, NetworkTables),
 * so each value holds until the next sample (sample-and-hold). A log contains any number of
 * enabled segments; an FMS match is one pattern among them, recognized only when FMS is attached
 * or the segments fit the season's match timing. Before the first DriverStation sample the state
 * is unknown, not disabled.
 *
 * <p>Entries are recognized by their leaf name — {@code Enabled}, {@code Autonomous},
 * {@code Test}, {@code FMSAttached} — under AdvantageKit's {@code /DriverStation/} or WPILib's
 * {@code DS:} prefix, AdvantageKit first, ties broken by entry id. When no boolean entries exist,
 * NetworkTables' {@code FMSInfo/FMSControlData} bit field is used.
 *
 * @since 0.9.0
 */
public final class MatchTimeline {

  /** Robot state over a segment. */
  public enum State { ENABLED, DISABLED, UNKNOWN }

  /** Robot mode over an enabled segment. */
  public enum Mode { AUTO, TELEOP, TEST, UNKNOWN }

  /** Why a segment ended. */
  public enum EndReason {
    /** The robot was disabled. */
    DISABLED,
    /** The robot was enabled. */
    ENABLED,
    /** The robot changed mode while enabled. */
    MODE_CHANGE,
    /** DriverStation data starts (the end of an unknown-state segment). */
    DS_DATA,
    /** The log ends. */
    LOG_END
  }

  /** A maximal interval of constant state (and mode, when enabled). */
  public record Segment(double start, double end, State state, Mode mode, EndReason endReason) {
    public double duration() {
      return Math.max(0.0, end - start);
    }

    public boolean contains(double t) {
      return t >= start && (t < end || (endReason == EndReason.LOG_END && t <= end));
    }

    public JsonObject toJson() {
      var o = new JsonObject();
      o.addProperty("start", start);
      o.addProperty("end", end);
      o.addProperty("duration", duration());
      o.addProperty("state", state.name().toLowerCase(Locale.ROOT));
      if (state == State.ENABLED) o.addProperty("mode", mode.name().toLowerCase(Locale.ROOT));
      o.addProperty("end_reason", endReason.name().toLowerCase(Locale.ROOT));
      return o;
    }
  }

  /** A season and where the year came from. */
  public record Season(int year, String basis) {}

  /**
   * An FMS match (or a practice run that follows match timing) found among the segments.
   *
   * @param auto The autonomous segment, or null if the robot was not enabled in autonomous
   * @param teleop The teleop segment
   * @param endgameStart Endgame start derived from the season's timing, or null when the teleop
   *     segment is incomplete or the season is unknown
   * @param basis {@code fms_attached}, or {@code mode_sequence} (no FMS: an autonomous segment of
   *     roughly the season's length followed within seconds by teleop)
   * @param complete Whether teleop ended in a disable at about the season's teleop length
   */
  public record Match(Segment auto, Segment teleop, Double endgameStart, String basis,
      boolean complete) {}

  /** Which entries the timeline was built from. */
  public record Sources(String enabled, String autonomous, String test, String fmsAttached,
      String controlWord, List<String> ignored) {}

  private static final double MODE_SETTLE_SEC = 0.25;
  private static final double AUTO_MIN_FRACTION = 0.5;
  private static final double AUTO_MAX_FRACTION = 1.5;
  private static final double TELEOP_TOLERANCE_SEC = 5.0;
  private static final double GAP_TOLERANCE_SEC = 5.0;
  private static final double DEFAULT_AUTO_SEC = 15.0;
  private static final double DEFAULT_DELAY_SEC = 3.0;
  private static final double DEFAULT_TELEOP_SEC = 135.0;

  private final Sources sources;
  private final List<TimestampedValue> enabledValues;
  private final List<TimestampedValue> autoValues;
  private final List<TimestampedValue> testValues;
  private final List<TimestampedValue> fmsValues;
  private final List<TimestampedValue> controlValues;
  private final boolean stateFromControl;
  private final List<Segment> segments;
  private final List<Match> matches;
  private final Season season;
  private final GameData game;

  private MatchTimeline(LogData log) {
    var enabled = findBoolean(log, "enabled");
    var auto = findBoolean(log, "autonomous");
    var test = findBoolean(log, "test");
    var fms = findBoolean(log, "fmsattached");
    String control = findControlWord(log).orElse(null);
    var ignored = new ArrayList<String>();
    for (var leaf : List.of("enabled", "autonomous", "test", "fmsattached")) {
      var all = candidates(log, leaf);
      if (all.size() > 1) all.subList(1, all.size()).forEach(e -> ignored.add(e.name()));
    }
    this.sources = new Sources(enabled.orElse(null), auto.orElse(null), test.orElse(null),
        fms.orElse(null), control, List.copyOf(ignored));
    this.enabledValues = values(log, sources.enabled());
    this.autoValues = values(log, sources.autonomous());
    this.testValues = values(log, sources.test());
    this.fmsValues = values(log, sources.fmsAttached());
    this.controlValues = values(log, control);
    // The control word decides enabled state and mode only when no boolean entry does
    this.stateFromControl = enabledValues.isEmpty() && !controlValues.isEmpty();
    this.season = seasonOf(log);
    GameData g = null;
    try {
      g = GameKnowledgeBase.getInstance().getGame(season.year());
    } catch (Exception ignoredException) {
      // no game data for this season
    }
    this.game = g;
    this.segments = buildSegments(log.minTimestamp(), log.maxTimestamp());
    this.matches = findMatches();
  }

  /** Builds the timeline for a log. */
  public static MatchTimeline of(LogData log) {
    return new MatchTimeline(log);
  }

  // ==================== accessors ====================

  public Sources sources() {
    return sources;
  }

  /** Whether any entry told us the robot's enabled state. */
  public boolean hasEnabledData() {
    return !enabledValues.isEmpty() || stateFromControl;
  }

  /** Whether the log has an autonomous-mode entry at all. */
  public boolean hasAutonomousData() {
    return !autoValues.isEmpty() || stateFromControl;
  }

  /** Whether the autonomous flag was ever true. */
  public boolean autonomousEverTrue() {
    if (stateFromControl) {
      return controlValues.stream().anyMatch(tv -> (bits(tv) & 2) != 0);
    }
    return autoValues.stream().anyMatch(tv -> Boolean.TRUE.equals(tv.value()));
  }

  /** Number of samples of the autonomous entry. */
  public int autonomousSampleCount() {
    return autoValues.size();
  }

  public List<Segment> segments() {
    return segments;
  }

  public List<Segment> enabledSegments() {
    return segments.stream().filter(s -> s.state() == State.ENABLED).toList();
  }

  public List<Match> matches() {
    return matches;
  }

  public Season season() {
    return season;
  }

  public Optional<GameData> game() {
    return Optional.ofNullable(game);
  }

  /** The robot's state at {@code t}, with sample-and-hold; unknown before any data. */
  public State stateAt(double t) {
    Boolean enabled = enabledAt(t);
    if (enabled == null) return State.UNKNOWN;
    return enabled ? State.ENABLED : State.DISABLED;
  }

  /** The robot's mode at {@code t} (meaningful while enabled). */
  public Mode modeAt(double t) {
    if (stateFromControl) {
      var word = ToolUtils.getValueAtTimeZoh(controlValues, t);
      if (!(word instanceof Number n)) return Mode.UNKNOWN;
      long w = n.longValue();
      if ((w & 4) != 0) return Mode.TEST;
      return (w & 2) != 0 ? Mode.AUTO : Mode.TELEOP;
    }
    if (Boolean.TRUE.equals(ToolUtils.getValueAtTimeZoh(testValues, t))) return Mode.TEST;
    if (autoValues.isEmpty()) return Mode.UNKNOWN;
    var auto = ToolUtils.getValueAtTimeZoh(autoValues, t);
    if (auto == null) return Mode.UNKNOWN;
    return Boolean.TRUE.equals(auto) ? Mode.AUTO : Mode.TELEOP;
  }

  /** Whether FMS was attached at {@code t}, or null when the log does not say. */
  public Boolean fmsAttachedAt(double t) {
    if (!fmsValues.isEmpty()) {
      var v = ToolUtils.getValueAtTimeZoh(fmsValues, t);
      return v instanceof Boolean b ? b : null;
    }
    if (!controlValues.isEmpty()) {
      var word = ToolUtils.getValueAtTimeZoh(controlValues, t);
      return word instanceof Number n ? (n.longValue() & 16) != 0 : null;
    }
    return null;
  }

  private Boolean enabledAt(double t) {
    if (!enabledValues.isEmpty()) {
      var v = ToolUtils.getValueAtTimeZoh(enabledValues, t);
      return v instanceof Boolean b ? b : null;
    }
    if (stateFromControl) {
      var word = ToolUtils.getValueAtTimeZoh(controlValues, t);
      return word instanceof Number n ? (n.longValue() & 1) != 0 : null;
    }
    return null;
  }

  // ==================== construction ====================

  /** Boolean DriverStation entries whose leaf name is {@code leaf}, best first. */
  static List<EntryInfo> candidates(LogData log, String leaf) {
    return log.entries().values().stream()
        .filter(e -> "boolean".equals(e.type()))
        .filter(e -> leafName(e.name()).equals(leaf))
        .filter(e -> ToolUtils.isDsEntry(e.name().toLowerCase(Locale.ROOT)))
        .sorted(Comparator.comparingInt((EntryInfo e) -> e.name().startsWith("DS:") ? 1 : 0)
            .thenComparingInt(EntryInfo::id))
        .toList();
  }

  static Optional<String> findBoolean(LogData log, String leaf) {
    return candidates(log, leaf).stream().map(EntryInfo::name).findFirst();
  }

  static Optional<String> findControlWord(LogData log) {
    return log.entries().values().stream()
        .filter(e -> "int64".equals(e.type()))
        .filter(e -> e.name().toLowerCase(Locale.ROOT).endsWith("fmsinfo/fmscontroldata"))
        .min(Comparator.comparingInt(EntryInfo::id))
        .map(EntryInfo::name);
  }

  /** The last path segment of an entry name, lower-cased ("DS:enabled" → "enabled"). */
  static String leafName(String name) {
    int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf(':'));
    return name.substring(cut + 1).toLowerCase(Locale.ROOT);
  }

  private static List<TimestampedValue> values(LogData log, String name) {
    if (name == null) return List.of();
    var v = log.values().get(name);
    return v == null ? List.of() : v;
  }

  private static long bits(TimestampedValue tv) {
    return tv.value() instanceof Number n ? n.longValue() : 0;
  }

  private List<Segment> buildSegments(double logStart, double logEnd) {
    var result = new ArrayList<Segment>();
    if (logEnd < logStart) return result;
    var boundaries = new TreeSet<Double>();
    for (var list : List.of(enabledValues, autoValues, testValues,
        stateFromControl ? controlValues : List.<TimestampedValue>of())) {
      for (var tv : list) {
        if (tv.timestamp() > logStart && tv.timestamp() < logEnd) boundaries.add(tv.timestamp());
      }
    }
    boundaries.add(logEnd);

    // Raw intervals of constant (state, mode)
    var raw = new ArrayList<Segment>();
    double cursor = logStart;
    for (double next : boundaries) {
      if (next <= cursor) continue;
      var state = stateAt(cursor);
      var mode = state == State.ENABLED ? modeAt(cursor) : Mode.UNKNOWN;
      if (!raw.isEmpty()) {
        var last = raw.get(raw.size() - 1);
        if (last.state() == state && last.mode() == mode) {
          raw.set(raw.size() - 1, new Segment(last.start(), next, state, mode, null));
          cursor = next;
          continue;
        }
      }
      raw.add(new Segment(cursor, next, state, mode, null));
      cursor = next;
    }
    if (raw.isEmpty()) {
      raw.add(new Segment(logStart, logEnd, stateAt(logStart),
          stateAt(logStart) == State.ENABLED ? modeAt(logStart) : Mode.UNKNOWN, null));
    }

    // A mode flag that changes just after an enable or just before a disable (logging order)
    // is not a mode change: fold an enabled sliver shorter than MODE_SETTLE_SEC into the
    // adjacent enabled segment (the following one if there is one, else the preceding one).
    var settled = new ArrayList<Segment>();
    for (int i = 0; i < raw.size(); i++) {
      var s = raw.get(i);
      if (s.state() == State.ENABLED && s.duration() < MODE_SETTLE_SEC) {
        if (i + 1 < raw.size() && raw.get(i + 1).state() == State.ENABLED) {
          var next = raw.get(i + 1);
          raw.set(i + 1, new Segment(s.start(), next.end(), next.state(), next.mode(), null));
          continue;
        }
        if (!settled.isEmpty() && settled.get(settled.size() - 1).state() == State.ENABLED) {
          var prev = settled.remove(settled.size() - 1);
          settled.add(new Segment(prev.start(), s.end(), prev.state(), prev.mode(), null));
          continue;
        }
      }
      if (!settled.isEmpty()) {
        var prev = settled.get(settled.size() - 1);
        if (prev.state() == s.state() && prev.mode() == s.mode()) {
          settled.set(settled.size() - 1, new Segment(prev.start(), s.end(), s.state(), s.mode(),
              null));
          continue;
        }
      }
      settled.add(s);
    }

    // A transition logged exactly at the last timestamp ends the final segment (it has no
    // duration of its own, so it is not a segment)
    var endState = stateAt(logEnd);
    var endMode = endState == State.ENABLED ? modeAt(logEnd) : Mode.UNKNOWN;
    for (int i = 0; i < settled.size(); i++) {
      var s = settled.get(i);
      EndReason reason;
      if (i == settled.size() - 1) {
        reason = endState == s.state() && endMode == s.mode() ? EndReason.LOG_END
            : transition(s.state(), endState);
      } else {
        reason = transition(s.state(), settled.get(i + 1).state());
      }
      result.add(new Segment(s.start(), s.end(), s.state(), s.mode(), reason));
    }
    return List.copyOf(result);
  }

  private static EndReason transition(State from, State to) {
    if (from == State.UNKNOWN) return EndReason.DS_DATA;
    if (from == State.ENABLED) {
      return to == State.ENABLED ? EndReason.MODE_CHANGE : EndReason.DISABLED;
    }
    return to == State.ENABLED ? EndReason.ENABLED : EndReason.DS_DATA;
  }

  // ==================== events ====================

  /** A state or mode transition, for timelines. */
  public record Event(double timestamp, String type, String category, String source,
      boolean initial) {
    public JsonObject toJson() {
      var o = new JsonObject();
      o.addProperty("timestamp", timestamp);
      o.addProperty("type", type);
      o.addProperty("category", category);
      if (source != null) o.addProperty("source", source);
      if (initial) o.addProperty("initial", true);
      return o;
    }
  }

  /**
   * Transitions between segments: ENABLED/DISABLED ({@code robot_state}) and
   * AUTO_START/TELEOP_START/TEST_START ({@code match_phase}, when the mode is known). The state at
   * the start of the log is reported once, marked {@code initial}.
   *
   * @param start Window start (inclusive), or null
   * @param end Window end (inclusive), or null
   */
  public List<Event> events(Double start, Double end) {
    var out = new ArrayList<Event>();
    String stateSource = sources.enabled() != null ? sources.enabled() : sources.controlWord();
    String modeSource = sources.autonomous() != null ? sources.autonomous()
        : stateFromControl ? sources.controlWord() : null;
    State prevState = State.UNKNOWN;
    Mode prevMode = Mode.UNKNOWN;
    boolean first = true;
    for (var s : segments) {
      if (s.state() != State.UNKNOWN) {
        addTransition(out, s.start(), prevState, prevMode, s.state(), s.mode(), stateSource,
            modeSource, first && prevState == State.UNKNOWN, start, end);
        first = false;
      }
      prevState = s.state();
      prevMode = s.mode();
    }
    if (!segments.isEmpty()) {
      var last = segments.get(segments.size() - 1);
      if (last.endReason() != EndReason.LOG_END) {
        var endState = stateAt(last.end());
        var endMode = endState == State.ENABLED ? modeAt(last.end()) : Mode.UNKNOWN;
        addTransition(out, last.end(), last.state(), last.mode(), endState, endMode, stateSource,
            modeSource, false, start, end);
      }
    }
    return out;
  }

  private static void addTransition(List<Event> out, double t, State fromState, Mode fromMode,
      State toState, Mode toMode, String stateSource, String modeSource, boolean initial,
      Double start, Double end) {
    if ((start != null && t < start) || (end != null && t > end)) return;
    if (toState != fromState && toState != State.UNKNOWN) {
      out.add(new Event(t, toState == State.ENABLED ? "ENABLED" : "DISABLED", "robot_state",
          stateSource, initial));
    }
    if (toState == State.ENABLED && toMode != Mode.UNKNOWN
        && (fromState != State.ENABLED || fromMode != toMode)) {
      out.add(new Event(t, toMode.name() + "_START", "match_phase", modeSource, false));
    }
  }

  private double autoSec() {
    return game != null ? game.autoDurationSec() : DEFAULT_AUTO_SEC;
  }

  private double delaySec() {
    return game != null ? game.autoToTeleopDelaySec() : DEFAULT_DELAY_SEC;
  }

  private double teleopSec() {
    return game != null ? game.teleopDurationSec() : DEFAULT_TELEOP_SEC;
  }

  private List<Match> findMatches() {
    var enabled = enabledSegments();
    var found = new ArrayList<Match>();
    for (int i = 0; i < enabled.size(); i++) {
      var a = enabled.get(i);
      if (a.mode() != Mode.AUTO) continue;
      Segment teleop = null;
      if (i + 1 < enabled.size()) {
        var t = enabled.get(i + 1);
        if (t.mode() == Mode.TELEOP && t.start() - a.end() <= delaySec() + GAP_TOLERANCE_SEC) {
          teleop = t;
        }
      }
      if (teleop == null) continue;
      boolean fms = Boolean.TRUE.equals(fmsAttachedAt(a.start()));
      // Without FMS, an autonomous segment of roughly the season's length followed at once by
      // teleop is a match run (DS practice mode, or a scrimmage); a short test of an auto
      // routine is not.
      boolean autoFits = a.duration() >= AUTO_MIN_FRACTION * autoSec()
          && a.duration() <= AUTO_MAX_FRACTION * autoSec();
      if (!fms && !autoFits) continue;
      found.add(match(a, teleop, fms ? "fms_attached" : "mode_sequence"));
      i++; // the teleop segment belongs to this match
    }
    // An FMS match in which the robot was not enabled for autonomous
    for (var t : enabled) {
      if (t.mode() != Mode.TELEOP || !Boolean.TRUE.equals(fmsAttachedAt(t.start()))) continue;
      boolean used = found.stream().anyMatch(m -> m.teleop() == t);
      if (!used && Math.abs(t.duration() - teleopSec()) <= TELEOP_TOLERANCE_SEC) {
        found.add(match(null, t, "fms_attached"));
      }
    }
    found.sort(Comparator.comparingDouble(m -> m.teleop().start()));
    return List.copyOf(found);
  }

  private Match match(Segment auto, Segment teleop, String basis) {
    boolean complete = teleop.endReason() == EndReason.DISABLED
        && Math.abs(teleop.duration() - teleopSec()) <= TELEOP_TOLERANCE_SEC;
    Double endgame = null;
    if (complete && game != null) {
      double start = teleop.end() - game.endgameStartBeforeEndSec();
      if (start > teleop.start()) endgame = start;
    }
    return new Match(auto, teleop, endgame, basis, complete);
  }

  // ==================== season ====================

  private static final Pattern YEAR = Pattern.compile("(20\\d{2})");

  /**
   * The season a log is from. The log's own wall clock comes first (AdvantageKit's
   * {@code /SystemStats/EpochTimeMicros}, WPILib's {@code systemTime}), then the build date in
   * {@code /RealMetadata/BuildDate}, then a year in the file name, then the current year.
   */
  public static Season seasonOf(LogData log) {
    for (var name : List.of("/SystemStats/EpochTimeMicros", "systemTime")) {
      var values = log.values().get(name);
      if (values == null) continue;
      for (int i = values.size() - 1; i >= 0; i--) {
        if (values.get(i).value() instanceof Long micros) {
          int year = Instant.ofEpochMilli(micros / 1000).atZone(ZoneOffset.UTC).getYear();
          if (year >= 2020 && year <= 2099) return new Season(year, "log_clock:" + name);
        }
      }
    }
    for (var e : log.entries().values()) {
      if (!e.name().endsWith("/BuildDate") || !"string".equals(e.type())) continue;
      var values = log.values().get(e.name());
      if (values == null || values.isEmpty()) continue;
      var m = YEAR.matcher(String.valueOf(values.get(0).value()));
      if (m.find()) return new Season(Integer.parseInt(m.group(1)), "build_date:" + e.name());
    }
    if (log.path() != null) {
      var fileName = java.nio.file.Path.of(log.path()).getFileName();
      var m = YEAR.matcher(fileName == null ? log.path() : fileName.toString());
      if (m.find()) {
        int year = Integer.parseInt(m.group(1));
        if (year >= 2020 && year <= 2099) return new Season(year, "file_name");
      }
    }
    return new Season(java.time.Year.now().getValue(), "current_year");
  }

  // ==================== JSON ====================

  /** Segments as a JSON array. */
  public JsonArray segmentsJson() {
    var array = new JsonArray();
    segments.forEach(s -> array.add(s.toJson()));
    return array;
  }

  /** A match as JSON, with the timing values it was judged against. */
  public JsonObject matchJson(Match m) {
    var o = new JsonObject();
    if (m.auto() != null) o.add("autonomous", phase(m.auto().start(), m.auto().end()));
    o.add("teleop", phase(m.teleop().start(), m.teleop().end()));
    if (m.endgameStart() != null) {
      var endgame = phase(m.endgameStart(), m.teleop().end());
      endgame.addProperty("basis", "game_timing");
      o.add("endgame", endgame);
    }
    o.addProperty("basis", m.basis());
    o.addProperty("complete", m.complete());
    var expected = new JsonObject();
    expected.addProperty("season", season.year());
    expected.addProperty("auto_sec", autoSec());
    expected.addProperty("teleop_sec", teleopSec());
    expected.addProperty("source", game != null ? "game_data" : "default");
    o.add("expected_timing", expected);
    return o;
  }

  static JsonObject phase(double start, double end) {
    var o = new JsonObject();
    o.addProperty("start", start);
    o.addProperty("end", end);
    o.addProperty("duration", Math.max(0.0, end - start));
    return o;
  }
}
