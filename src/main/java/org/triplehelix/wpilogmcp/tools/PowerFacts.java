/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LogData;

/**
 * Brownout facts a log can record about itself, shared by {@code power_analysis},
 * {@code predict_battery_health}, {@code get_ds_timeline}, and {@code generate_report} so they
 * agree.
 *
 * <ul>
 *   <li>The brownout threshold: the roboRIO's own setting when logged (AdvantageKit
 *       {@code /SystemStats/BrownoutVoltage}), otherwise the roboRIO 1 default of 6.8 V, stated as
 *       an assumption (a roboRIO 2 defaults to 6.3 V, which the log does not reveal).
 *   <li>The roboRIO's brownout flag (AdvantageKit {@code /SystemStats/BrownedOut}): when it is
 *       logged, its true intervals are the brownouts — the times the roboRIO actually disabled
 *       outputs — as opposed to voltage threshold crossings.
 * </ul>
 *
 * @since 0.9.0
 */
final class PowerFacts {

  private PowerFacts() {}

  /** roboRIO 1 default brownout threshold. */
  static final double DEFAULT_THRESHOLD = 6.8;

  /** A threshold and where it came from. */
  record Threshold(double volts, String basis, String entry) {
    void addTo(JsonObject o) {
      o.addProperty("brownout_threshold", volts);
      o.addProperty("brownout_threshold_basis", basis);
      if (entry != null) o.addProperty("brownout_threshold_entry", entry);
    }
  }

  /** One interval during which the roboRIO brownout flag was true. */
  record Brownout(double start, double end, boolean openAtLogEnd) {
    double duration() {
      return end - start;
    }
  }

  /**
   * The threshold to use: an explicit argument, else the logged setting, else the default.
   *
   * @param explicit The caller's brownout_threshold argument, or null
   */
  static Threshold threshold(LogData log, Double explicit) {
    if (explicit != null) {
      if (!Double.isFinite(explicit) || explicit <= 0) {
        throw new IllegalArgumentException("brownout_threshold must be a positive number");
      }
      return new Threshold(explicit, "argument", null);
    }
    var logged = log.entries().values().stream()
        .filter(e -> ToolUtils.isNumericType(e.type()))
        .filter(e -> leaf(e.name()).equals("brownoutvoltage"))
        .sorted(Comparator.comparingInt(EntryInfo::id))
        .toList();
    for (var e : logged) {
      var values = log.values().get(e.name());
      if (values == null) continue;
      for (int i = values.size() - 1; i >= 0; i--) {
        if (values.get(i).value() instanceof Number n && Double.isFinite(n.doubleValue())
            && n.doubleValue() > 0) {
          return new Threshold(n.doubleValue(), "logged", e.name());
        }
      }
    }
    return new Threshold(DEFAULT_THRESHOLD, "default: roboRIO 1 (6.8 V); a roboRIO 2 defaults "
        + "to 6.3 V, and this log does not record which is installed", null);
  }

  /**
   * The roboRIO brownout flag entry: a boolean whose leaf is BrownedOut or IsBrownedOut
   * (AdvantageKit /SystemStats/BrownedOut, a logged RobotController.isBrownedOut()), lowest id.
   */
  static Optional<String> flagEntry(LogData log) {
    return log.entries().values().stream()
        .filter(e -> "boolean".equals(e.type()))
        .filter(e -> {
          var leaf = SignalResolver.leaf(e.name());
          return leaf.equals("brownedout") || leaf.equals("isbrownedout");
        })
        .min(Comparator.comparingInt(EntryInfo::id))
        .map(EntryInfo::name);
  }

  /** Intervals during which the flag was true, clipped to [start, end] when given. */
  static List<Brownout> brownouts(LogData log, String flagEntry, Double start, Double end) {
    var out = new ArrayList<Brownout>();
    var values = log.values().get(flagEntry);
    if (values == null) return out;
    Double openedAt = null;
    for (var tv : values) {
      if (!(tv.value() instanceof Boolean state)) continue;
      if (state && openedAt == null) {
        openedAt = tv.timestamp();
      } else if (!state && openedAt != null) {
        out.add(new Brownout(openedAt, tv.timestamp(), false));
        openedAt = null;
      }
    }
    if (openedAt != null) out.add(new Brownout(openedAt, log.maxTimestamp(), true));
    return out.stream()
        .filter(b -> (end == null || b.start() <= end) && (start == null || b.end() >= start))
        .toList();
  }

  /** {@code {flag_entry, count, total_sec, events: [{start, end, duration_sec}]}}. */
  static JsonObject brownoutsJson(String flagEntry, List<Brownout> brownouts) {
    var o = new JsonObject();
    o.addProperty("flag_entry", flagEntry);
    o.addProperty("count", brownouts.size());
    o.addProperty("total_sec", brownouts.stream().mapToDouble(Brownout::duration).sum());
    var events = new JsonArray();
    for (var b : brownouts) {
      var e = new JsonObject();
      e.addProperty("start", b.start());
      e.addProperty("end", b.end());
      e.addProperty("duration_sec", b.duration());
      if (b.openAtLogEnd()) e.addProperty("open_at_log_end", true);
      events.add(e);
    }
    o.add("events", events);
    return o;
  }

  static String leaf(String name) {
    return name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
  }

  /** Volts a crossing must recover above the threshold to end (noisy voltage is one crossing). */
  static final double HYSTERESIS = 0.2;

  /** An interval the battery voltage spent below the threshold (with hysteresis). */
  record Crossing(double start, double end, double minVolts) {
    double duration() {
      return end - start;
    }
  }

  /**
   * Battery voltage over a scope: sample count, minimum (and when), maximum, mean, samples below
   * the threshold, and crossings below it (each ending when the voltage recovers above the
   * threshold plus {@value #HYSTERESIS} V, or at the scope's last sample).
   */
  record VoltageFacts(int samples, double min, double minTime, double max, double mean,
      long samplesBelow, List<Crossing> crossings) {

    double secondsBelow() {
      return crossings.stream().mapToDouble(Crossing::duration).sum();
    }

    void addTo(JsonObject o) {
      o.addProperty("samples", samples);
      o.addProperty("min_voltage", min);
      o.addProperty("min_voltage_time_sec", minTime);
      o.addProperty("max_voltage", max);
      o.addProperty("avg_voltage", mean);
      o.addProperty("samples_below_threshold", samplesBelow);
      o.addProperty("threshold_crossings", crossings.size());
      o.addProperty("seconds_below_threshold", secondsBelow());
    }
  }

  /** Voltage facts for the finite samples of {@code values} inside {@code scope}, or empty. */
  static Optional<VoltageFacts> voltage(List<org.triplehelix.wpilogmcp.log.TimestampedValue> values,
      TimeScope scope, double threshold) {
    int n = 0;
    double min = Double.MAX_VALUE;
    double minTime = 0;
    double max = -Double.MAX_VALUE;
    double sum = 0;
    long below = 0;
    var crossings = new ArrayList<Crossing>();
    Double openedAt = null;
    double openMin = Double.MAX_VALUE;
    double lastTime = 0;
    for (var tv : values) {
      if (!scope.contains(tv.timestamp())) continue;
      if (!(tv.value() instanceof Number num) || !Double.isFinite(num.doubleValue())) continue;
      double v = num.doubleValue();
      double t = tv.timestamp();
      n++;
      sum += v;
      if (v < min) {
        min = v;
        minTime = t;
      }
      max = Math.max(max, v);
      if (v < threshold) below++;
      if (openedAt == null && v < threshold) {
        openedAt = t;
        openMin = v;
      } else if (openedAt != null) {
        openMin = Math.min(openMin, v);
        if (v >= threshold + HYSTERESIS) {
          crossings.add(new Crossing(openedAt, t, openMin));
          openedAt = null;
        }
      }
      lastTime = t;
    }
    if (n == 0) return Optional.empty();
    if (openedAt != null) crossings.add(new Crossing(openedAt, lastTime, openMin));
    return Optional.of(new VoltageFacts(n, min, minTime, max, sum / n, below, crossings));
  }

  /** A brownout risk level and the evidence it rests on. */
  record Risk(String level, String basis) {
    void addTo(JsonObject o) {
      o.addProperty("brownout_risk", level);
      o.addProperty("brownout_risk_basis", basis);
    }
  }

  /**
   * The brownout risk over a scope, one rule for every power tool. Only the roboRIO's logged
   * flag confirms a brownout (outputs disabled): with it, HIGH means the flag was set in scope.
   * Without it, voltage below the threshold is HIGH but unconfirmed. Otherwise MODERATE when the
   * minimum came within 1 V of the threshold, else LOW.
   *
   * @param flagEntry The brownout flag entry, or null when none is logged
   * @param flagged Flag brownouts inside the scope
   */
  static Risk risk(VoltageFacts v, Threshold threshold, String flagEntry, List<Brownout> flagged) {
    double t = threshold.volts();
    if (flagEntry != null && !flagged.isEmpty()) {
      return new Risk("HIGH", flagged.size() + " roboRIO brownout(s) in scope (" + flagEntry
          + " true: outputs were disabled)");
    }
    if (v.crossings().size() > 0) {
      return new Risk(flagEntry != null ? "MODERATE" : "HIGH", String.format(
          "%d crossing(s) below %.2f V, %.3f s in all; %s", v.crossings().size(), t,
          v.secondsBelow(), flagEntry != null
              ? flagEntry + " stayed false, so the roboRIO did not disable outputs"
              : "no brownout flag is logged, so whether outputs were disabled is unknown"));
    }
    if (v.min() < t + 1.0) {
      return new Risk("MODERATE", String.format("minimum %.2f V, within 1 V of the %.2f V "
          + "threshold", v.min(), t));
    }
    return new Risk("LOW", String.format("minimum %.2f V, more than 1 V above the %.2f V "
        + "threshold", v.min(), t));
  }
}
