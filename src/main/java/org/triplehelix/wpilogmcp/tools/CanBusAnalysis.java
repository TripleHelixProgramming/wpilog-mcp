/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * CAN bus health from the structured counters a log records, shared by {@code analyze_can_bus}
 * and {@code can_health}.
 *
 * <p>A bus is a path prefix holding entries with the standard CAN status field names — WPILib's
 * {@code CANStatus} (as AdvantageKit logs it under {@code /SystemStats/CANBus}) and CTRE's
 * {@code CANBusStatus} (as teams log CANivore status): {@code Utilization}/{@code BusUtilization},
 * {@code BusOffCount}/{@code OffCount}, {@code TxFullCount}, {@code REC}/{@code ReceiveErrorCount},
 * {@code TEC}/{@code TransmitErrorCount}. Fields are recognized by exact leaf name, never by a
 * substring such as "can" (which also matches Canandgyro or scan).
 *
 * <p>TEC and REC are the controller's transmit and receive error counters: a node becomes
 * error-passive at 128 and goes bus-off when TEC passes 255. They rise and fall, so they are
 * reported as levels (maximum, excursions above 128). Bus-off and TX-full counts only grow, so
 * they are reported as increases.
 *
 * @since 0.9.0
 */
final class CanBusAnalysis {

  private CanBusAnalysis() {}

  /** Error-passive threshold for TEC and REC. */
  static final double ERROR_PASSIVE = 128;

  /** The CAN status fields, by role. */
  enum Field {
    UTILIZATION("utilization"),
    BUS_OFF("bus_off"),
    TX_FULL("tx_full"),
    REC("rec"),
    TEC("tec");

    final String key;

    Field(String key) {
      this.key = key;
    }
  }

  static final Map<String, Field> FIELD_NAMES = Map.ofEntries(
      Map.entry("utilization", Field.UTILIZATION),
      Map.entry("busutilization", Field.UTILIZATION),
      Map.entry("percentbusutilization", Field.UTILIZATION),
      Map.entry("busoffcount", Field.BUS_OFF),
      Map.entry("offcount", Field.BUS_OFF),
      Map.entry("txfullcount", Field.TX_FULL),
      Map.entry("rec", Field.REC),
      Map.entry("receiveerrorcount", Field.REC),
      Map.entry("tec", Field.TEC),
      Map.entry("transmiterrorcount", Field.TEC));

  /** A CAN bus found in the log: its display name, path prefix, and field entries. */
  record Bus(String name, String prefix, Map<Field, String> entries) {
    boolean matches(String query) {
      var q = query.toLowerCase(Locale.ROOT);
      return name.toLowerCase(Locale.ROOT).equals(q) || prefix.toLowerCase(Locale.ROOT).equals(q)
          || (q.equals("rio") && name.equals("rio"));
    }
  }

  /** Whether an entry type carries a scalar number. */
  static boolean numeric(String type) {
    return type.equals("double") || type.equals("float") || type.equals("int64");
  }

  /** Every CAN bus in the log, in order of first declaration. */
  static List<Bus> discoverBuses(LogData log) {
    var groups = new LinkedHashMap<String, Map<Field, String>>();
    log.entries().values().stream()
        .sorted(Comparator.comparingInt(EntryInfo::id))
        .filter(e -> numeric(e.type()))
        .forEach(e -> {
          int cut = e.name().lastIndexOf('/');
          if (cut <= 0) return;
          var field = FIELD_NAMES.get(e.name().substring(cut + 1).toLowerCase(Locale.ROOT));
          if (field == null) return;
          groups.computeIfAbsent(e.name().substring(0, cut), k -> new EnumMap<>(Field.class))
              .putIfAbsent(field, e.name());
        });
    var buses = new ArrayList<Bus>();
    groups.forEach((prefix, fields) -> {
      long counters = fields.keySet().stream().filter(f -> f != Field.UTILIZATION).count();
      boolean canPath = prefix.toLowerCase(Locale.ROOT).contains("can");
      if (!canPath && counters < 2) return; // a lone "Utilization" elsewhere is not a CAN bus
      buses.add(new Bus(busName(prefix), prefix, Map.copyOf(fields)));
    });
    return buses;
  }

  /** "rio" for AdvantageKit's roboRIO bus, otherwise the last path segment. */
  static String busName(String prefix) {
    if (prefix.equalsIgnoreCase("/SystemStats/CANBus")) return "rio";
    var leaf = prefix.substring(prefix.lastIndexOf('/') + 1);
    if (leaf.equalsIgnoreCase("rio") || leaf.equalsIgnoreCase("roborio")) return "rio";
    return leaf;
  }

  /** Finite numeric samples inside the window (both bounds inclusive; null means unbounded). */
  static List<TimestampedValue> window(List<TimestampedValue> values, Double start, Double end) {
    var out = new ArrayList<TimestampedValue>();
    if (values == null) return out;
    for (var tv : values) {
      if (start != null && tv.timestamp() < start) continue;
      if (end != null && tv.timestamp() > end) continue;
      if (tv.value() instanceof Number n && Double.isFinite(n.doubleValue())) out.add(tv);
    }
    return out;
  }

  static double num(TimestampedValue tv) {
    return ((Number) tv.value()).doubleValue();
  }

  /** Analyzes one bus over a window; state comes from the log's DriverStation timeline. */
  static JsonObject analyze(LogData log, Bus bus, MatchTimeline timeline, Double start,
      Double end) {
    var o = new JsonObject();
    o.addProperty("bus", bus.name());
    o.addProperty("prefix", bus.prefix());
    var entries = new JsonObject();
    for (var field : Field.values()) {
      var name = bus.entries().get(field);
      if (name != null) entries.addProperty(field.key, name);
    }
    o.add("entries", entries);
    double windowEnd = end != null ? end : log.maxTimestamp();
    for (var field : Field.values()) {
      var name = bus.entries().get(field);
      if (name == null) continue;
      var values = window(log.values().get(name), start, end);
      var stats = switch (field) {
        case UTILIZATION -> utilization(values, timeline);
        case TEC, REC -> level(values, timeline, windowEnd);
        case BUS_OFF, TX_FULL -> counter(values, timeline);
      };
      o.add(field.key, stats);
    }
    return o;
  }

  /** Utilization in percent; 0–1 fractions are detected from the range and scaled. */
  static JsonObject utilization(List<TimestampedValue> values, MatchTimeline timeline) {
    var o = new JsonObject();
    o.addProperty("samples", values.size());
    if (values.isEmpty()) return o;
    double max = values.stream().mapToDouble(CanBusAnalysis::num).max().orElse(0);
    boolean fraction = max <= 1.0;
    double scale = fraction ? 100.0 : 1.0;
    o.addProperty("unit_detected", fraction ? "fraction (0-1), converted to percent" : "percent");
    var all = values.stream().mapToDouble(tv -> num(tv) * scale).sorted().toArray();
    o.addProperty("mean_percent", Arrays.stream(all).average().orElse(0));
    o.addProperty("p95_percent", ToolUtils.percentile(all, 0.95));
    o.addProperty("max_percent", all[all.length - 1]);
    var enabled = values.stream()
        .filter(tv -> timeline.stateAt(tv.timestamp()) == MatchTimeline.State.ENABLED)
        .mapToDouble(tv -> num(tv) * scale).sorted().toArray();
    if (enabled.length > 0) {
      var e = new JsonObject();
      e.addProperty("samples", enabled.length);
      e.addProperty("mean_percent", Arrays.stream(enabled).average().orElse(0));
      e.addProperty("max_percent", enabled[enabled.length - 1]);
      o.add("while_enabled", e);
    }
    return o;
  }

  /**
   * A TEC/REC level: maximum and when, excursions above the error-passive threshold (rising
   * crossings of 128), and the time spent at or above it (values held until the next sample).
   */
  static JsonObject level(List<TimestampedValue> values, MatchTimeline timeline,
      double windowEnd) {
    var o = new JsonObject();
    o.addProperty("samples", values.size());
    if (values.isEmpty()) return o;
    TimestampedValue max = values.get(0);
    TimestampedValue maxEnabled = null;
    int excursions = 0;
    int excursionsEnabled = 0;
    double timeAbove = 0;
    boolean above = false;
    for (int i = 0; i < values.size(); i++) {
      var tv = values.get(i);
      double v = num(tv);
      boolean enabled = timeline.stateAt(tv.timestamp()) == MatchTimeline.State.ENABLED;
      if (v > num(max)) max = tv;
      if (enabled && (maxEnabled == null || v > num(maxEnabled))) maxEnabled = tv;
      boolean nowAbove = v >= ERROR_PASSIVE;
      if (nowAbove && !above) {
        excursions++;
        if (enabled) excursionsEnabled++;
      }
      above = nowAbove;
      if (nowAbove) {
        double next = i + 1 < values.size() ? values.get(i + 1).timestamp() : windowEnd;
        timeAbove += Math.max(0, next - tv.timestamp());
      }
    }
    o.addProperty("max", num(max));
    o.addProperty("max_time_sec", max.timestamp());
    o.addProperty("error_passive_excursions", excursions);
    o.addProperty("time_error_passive_sec", timeAbove);
    if (maxEnabled != null) {
      var e = new JsonObject();
      e.addProperty("max", num(maxEnabled));
      e.addProperty("max_time_sec", maxEnabled.timestamp());
      e.addProperty("error_passive_excursions", excursionsEnabled);
      o.add("while_enabled", e);
    }
    return o;
  }

  /**
   * A count that only grows (bus-off, TX-full): first and last value and the total increase. A
   * decrease is a counter reset; counting resumes from the new value.
   */
  static JsonObject counter(List<TimestampedValue> values, MatchTimeline timeline) {
    var o = new JsonObject();
    o.addProperty("samples", values.size());
    if (values.isEmpty()) return o;
    double increase = 0;
    double increaseEnabled = 0;
    int resets = 0;
    for (int i = 1; i < values.size(); i++) {
      double delta = num(values.get(i)) - num(values.get(i - 1));
      if (delta > 0) {
        increase += delta;
        if (timeline.stateAt(values.get(i).timestamp()) == MatchTimeline.State.ENABLED) {
          increaseEnabled += delta;
        }
      } else if (delta < 0) {
        resets++;
      }
    }
    o.addProperty("first", num(values.get(0)));
    o.addProperty("last", num(values.get(values.size() - 1)));
    o.addProperty("increase", increase);
    o.addProperty("increase_while_enabled", increaseEnabled);
    if (resets > 0) o.addProperty("resets", resets);
    return o;
  }

  // ==================== console text ====================

  /** "CAN" as a word, or a CAN compound (CANbus, CANivore, CANcoder); not "cannot" or "scan". */
  private static final Pattern CAN_WORD =
      Pattern.compile("(?i)(?<![a-z])(can(?![a-z])|canbus|canivore|cancoder)");
  private static final Pattern FAILURE_WORD = Pattern.compile("(?i)(timeout|timed out|error|fault)");

  /**
   * Whether a console line reports a CAN failure: "CAN" as a word (or CANbus, CANivore,
   * CANcoder) together with timeout, error, or fault. "default" is not a fault.
   */
  static boolean isCanErrorLine(String line) {
    var cleaned = line.replaceAll("(?i)default", "");
    return CAN_WORD.matcher(cleaned).find() && FAILURE_WORD.matcher(cleaned).find();
  }

  /** Summary array of buses for results. */
  static JsonArray busesJson(LogData log, List<Bus> buses, MatchTimeline timeline, Double start,
      Double end) {
    var array = new JsonArray();
    for (var bus : buses) array.add(analyze(log, bus, timeline, start, end));
    return array;
  }
}
