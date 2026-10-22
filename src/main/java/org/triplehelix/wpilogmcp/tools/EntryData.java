/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.triplehelix.wpilogmcp.data.ArrowType;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.NumericColumn;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.read.AngleUnwrapper;
import org.triplehelix.wpilogmcp.read.FieldPath;
import org.triplehelix.wpilogmcp.read.NumericSamples;
import org.triplehelix.wpilogmcp.sync.SyncMethod;

/**
 * An entry's samples as the data endpoint serves them (EXPLORER_PLAN.md §6): resolved by the
 * same rules the tools use (an entry by name, or a numeric field inside one by a field path, as
 * {@code get_statistics} takes it), typed for Arrow by the entry's type and the log's own struct
 * schemas, classed by sampling as {@code data_quality} classes it, and flattened for CSV as
 * {@code export_csv} flattens it. The endpoint, in the transport, holds no knowledge of logs:
 * everything it needs to know about an entry is here, beside the tools that know the same.
 */
public final class EntryData {
  private EntryData() {}

  /**
   * A resolved series.
   *
   * @param name The name as asked for (an entry, or an entry with a field path)
   * @param entry The entry's name
   * @param field The field path inside it, or null for the whole value
   * @param type The entry's type as the log declares it
   * @param values The samples in time order: numbers for a field path, decoded values otherwise
   * @param numeric Whether every value is a number (a field path, or a numeric scalar entry)
   * @param samples The same samples by position, booleans as 1 or 0, for a series known to be in
   *     time order; null for any other, which its values alone describe
   */
  public record Series(String name, String entry, String field, String type,
      List<TimestampedValue> values, boolean numeric, NumericSamples samples) {

    /** A series its values alone describe, such as a REV signal's. */
    public Series(String name, String entry, String field, String type, List<TimestampedValue> values,
        boolean numeric) {
      this(name, entry, field, type, values, numeric, null);
    }

    /** The samples within the window; a null bound is open. */
    public List<TimestampedValue> inWindow(Double startTime, Double endTime) {
      var window = new org.triplehelix.wpilogmcp.read.SecondsWindow(startTime, endTime);
      if (window.open()) return values;
      return values.stream().filter(tv -> window.contains(tv.timestamp())).toList();
    }
  }

  /**
   * Prepared presentation values and whether Arrow must represent them as floating point, with
   * their numbers by position when the series has them.
   */
  public record Prepared(List<TimestampedValue> values, boolean angle, NumericSamples samples) {}

  /** Invalid explicit units are refused even when the selected interval contains no records. */
  public static void validateAngle(String angle) {
    angleArgument(angle);
  }

  private static NumericSignal.AngleUnit angleArgument(String angle) {
    var arguments = new JsonObject();
    if (angle != null) arguments.addProperty("angle", angle);
    return NumericSignal.angleArgument(arguments);
  }

  /**
   * Applies presentation to the requested interval before either exact output or reduction.
   * Ordinary whole-value reads keep their decoded types, including exact int64 values; only
   * a scalar angle becomes floating point. Wildcards are unordered pools, never a sequence
   * of turns. Already-resolved REV values need no second log lookup.
   */
  public static Prepared prepare(LogData log, Series series, Double start, Double end,
      String angle) {
    var explicit = angleArgument(angle);
    boolean numeric = series.numeric() || "boolean".equals(series.type());
    if (explicit != null && !numeric)
      throw new IllegalArgumentException("Entry " + series.name() + " is " + series.type()
          + "; angle requires a numeric scalar or selected numeric field");
    boolean wildcard = series.field() != null && FieldPath.parse(series.field()).hasWildcard();
    if (series.samples() != null) return prepare(log, series, start, end, explicit, wildcard);
    var values = series.inWindow(start, end);
    if (wildcard) return new Prepared(values, false, null);
    double period = explicit == null
        ? VisualizationBindings.anglePeriod(log, series.entry(), series.field()) : explicit.period;
    if (period == 0) return new Prepared(values, false, null);
    var unwrapper = new AngleUnwrapper(period);
    var prepared = new ArrayList<TimestampedValue>(values.size());
    for (var value : values) {
      var number = FieldPath.toNumber(value.value());
      if (number == null)
        throw new IllegalArgumentException("Entry " + series.name() + " has a non-numeric angle value");
      prepared.add(value.withValue(unwrapper.apply(number)));
    }
    return new Prepared(prepared, true, null);
  }

  /**
   * The same presentation by position, for a series in time order: the window is found by
   * search, as the filter above keeps it, and an angle is unwrapped into one array of numbers.
   */
  private static Prepared prepare(LogData log, Series series, Double start, Double end,
      NumericSignal.AngleUnit explicit, boolean wildcard) {
    var samples = series.samples();
    var window = new org.triplehelix.wpilogmcp.read.SecondsWindow(start, end);
    int from = window.from(samples), to = window.to(samples);
    var values = series.values().subList(from, to);
    var windowed = NumericSamples.range(samples, from, to);
    if (wildcard) return new Prepared(values, false, windowed);
    double period = explicit == null
        ? VisualizationBindings.anglePeriod(log, series.entry(), series.field()) : explicit.period;
    if (period == 0) return new Prepared(values, false, windowed);
    var unwrapper = new AngleUnwrapper(period);
    var numbers = new double[windowed.size()];
    for (int i = 0; i < numbers.length; i++) numbers[i] = unwrapper.apply(windowed.number(i));
    var unwrapped = NumericSamples.withNumbers(windowed, numbers);
    return new Prepared(NumericSamples.asValues(unwrapped), true, unwrapped);
  }

  /**
   * Resolves a name: an entry of any type, or a numeric field inside a struct or array entry.
   *
   * @throws IllegalArgumentException when the name is neither, with the tools' own message
   *     (suggestions for an unknown entry, the numeric fields for a field that is not one)
   */
  public static Series resolve(LogData log, String name) {
    var info = log.entries().get(name);
    if (info != null) {
      boolean numeric = ToolUtils.isNumericType(info.type());
      boolean numbers = numeric || "boolean".equals(info.type());
      // A column file gives the typed values the decode would, without decoding.
      var column = numbers ? log.numericColumn(name, FieldPath.ROOT) : java.util.Optional.<NumericColumn>empty();
      if (column.isPresent() && column.get().values() != null)
        return new Series(name, name, null, info.type(), column.get().values(), numeric, column.get().samples());
      var values = log.values().get(name);
      if (values == null) values = List.of();
      return new Series(name, name, null, info.type(), values, numeric, numbers ? NumericSamples.of(values) : null);
    }
    var signal = NumericSignal.resolve(log, name, null);
    return new Series(name, signal.entry(), signal.path().toString(), signal.type(),
        signal.values(), true, signal.samples());
  }

  /** The stream must disclose records lost in decoding just as a tool using the same entry does. */
  public static JsonObject decodingMetadata(LogData log, Series series) {
    var metadata = new JsonObject();
    boolean recorded = log.entries().containsKey(series.entry());
    metadata.addProperty("total_records", recorded ? log.sampleCount(series.entry()) : series.values().size());
    if (recorded) {
      log.decodeProblem(series.entry()).ifPresent(problem -> {
        metadata.addProperty("total_records", problem.totalRecords());
        metadata.add("decode_problem", AccessTrackingLogData.toJson(series.entry(), problem));
        metadata.addProperty("warning", AccessTrackingLogData.warning(series.entry(), problem));
      });
    }
    return metadata;
  }

  /** The prefix of a REV log signal's key, as {@code list_revlog_signals} gives it. */
  public static final String REV_PREFIX = "REV/";

  /**
   * How a REV signal's timestamps were put on the wpilog's clock, as {@code list_revlog_signals}
   * reports it, for the stream's metadata: a reader must know an offset's basis before trusting
   * a REV sample's time beside a wpilog sample's.
   */
  public record RevSync(String device, String signal, String unit, String canBus, String method,
      Double offsetSeconds, String confidence, boolean aligned) {}

  /** A REV signal resolved: its samples on the wpilog's clock, and how they got there. */
  public record RevSeries(Series series, RevSync sync, RevValidator validator) {}

  /** A validator must cover the same immutable alignment used to transform this response. */
  public record RevValidator(Path path, FileSnapshot snapshot, SyncMethod method, long offsetMicros,
      double driftRateNanosPerSec, double referenceTimeSec, String confidence) {}

  /** Why a REV key could not be served, with the status a request gets. */
  public static final class RevUnavailable extends Exception {
    public final int status;
    public final String hint;

    RevUnavailable(int status, String message, String hint) {
      super(message);
      this.status = status;
      this.hint = hint;
    }
  }

  /**
   * Resolves a REV signal key ({@code REV/<device>/<signal>}, or with the bus) to its samples on
   * the wpilog's clock, exactly as {@code get_revlog_data} reads them: through the synchronized
   * logs the manager keeps for the wpilog. A key whose REV log could not be synchronized is
   * refused, since its timestamps would be on the REV log's own clock; so is one asked for while
   * the synchronization is still running, with the hint to wait.
   */
  public static RevSeries resolveRev(LogManager manager, LogData log, String key)
      throws RevUnavailable {
    manager.refreshRevLogsIfChanged(log.path());
    var syncLogs = manager.getSynchronizedLogs(log.path());
    if (syncLogs == null || syncLogs.revlogCount() == 0) {
      if (manager.isRevLogSyncInProgress(log.path())) {
        throw new RevUnavailable(503, "REV log synchronization for this log is still in "
            + "progress, so " + key + " is not available yet", "Call wait_for_sync, then ask again");
      }
      throw new RevUnavailable(404, "No REV log (.revlog) is synchronized with this log, so "
          + key + " is not available", "list_revlog_signals says which REV logs the server found");
    }
    var synced = syncLogs.revlogFor(key);
    var values = synced == null ? null : syncLogs.getValues(key);
    if (values == null) {
      throw new RevUnavailable(404, "Signal not found: " + key,
          "list_revlog_signals lists the keys");
    }
    var result = synced.syncResult();
    boolean failed = result.method() == SyncMethod.FAILED;
    var signal = synced.revlog().getSignal(stripBus(key, synced.canBusName()));
    var sync = new RevSync(
        signal != null ? signal.deviceKey() : null,
        signal != null ? signal.name() : null,
        signal != null ? signal.unit() : null,
        synced.canBusName(),
        result.method().name(),
        failed ? null : result.offsetSeconds(),
        result.confidenceLevel().getLabel(),
        !failed);
    if (failed) {
      throw new RevUnavailable(409, "The REV log holding " + key + " (bus '" + synced.canBusName()
          + "') could not be synchronized to the wpilog's clock, so its timestamps are on its "
          + "own clock", "set_revlog_offset gives the bus an offset; sync_status says why it failed");
    }
    var path = Path.of(synced.revlog().path());
    FileSnapshot snapshot;
    try {
      snapshot = FileSnapshot.of(path);
      if (snapshot == null) throw new IOException("The file no longer exists: " + path);
    } catch (IOException e) {
      throw new RevUnavailable(409, "Cannot validate REV data: " + e.getMessage(),
          "Refresh the REV logs and wait for synchronization before asking again");
    }
    var validator = new RevValidator(path, snapshot, result.method(), result.offsetMicros(),
        result.driftRateNanosPerSec(), result.referenceTimeSec(), sync.confidence());
    return new RevSeries(new Series(key, key, null, "revlog", values, true), sync, validator);
  }

  /** The key inside the REV log: without the {@code REV/} prefix and, when present, the bus. */
  private static String stripBus(String key, String bus) {
    var inner = key.startsWith(REV_PREFIX) ? key.substring(REV_PREFIX.length()) : key;
    if (bus != null && inner.startsWith(bus + "/")) inner = inner.substring(bus.length() + 1);
    return inner;
  }

  /** The sampling class of the values, as {@code data_quality.sampling} reports it. */
  public static String sampling(List<TimestampedValue> values) {
    return DataQuality.fromValues(values).sampling().name().toLowerCase(java.util.Locale.ROOT);
  }

  public static String sampling(LogData log, Series series) {
    var quality = series.samples() != null
        ? DataQuality.fromSamples(log, series.entry(), List.of(series.samples()))
        : DataQuality.fromSegments(log, series.entry(), List.of(series.values()));
    return quality.sampling().name().toLowerCase(java.util.Locale.ROOT);
  }

  /** Suffixes a name may end with, and the unit each states. */
  private static final String[][] UNIT_SUFFIXES = {
      {"MetersPerSecondSquared", "m/s^2"}, {"MetersPerSecSquared", "m/s^2"},
      {"MetersPerSecond", "m/s"}, {"MetersPerSec", "m/s"}, {"MPS", "m/s"},
      {"RadiansPerSecond", "rad/s"}, {"RadPerSec", "rad/s"}, {"RadiansPerSec", "rad/s"},
      {"DegreesPerSecond", "deg/s"}, {"DegPerSec", "deg/s"},
      {"Meters", "m"}, {"Millimeters", "mm"}, {"Inches", "in"}, {"Feet", "ft"},
      {"Radians", "rad"}, {"Rad", "rad"}, {"Degrees", "deg"}, {"Deg", "deg"}, {"Rotations", "rot"},
      {"Amps", "A"}, {"Amperes", "A"}, {"Volts", "V"}, {"Watts", "W"}, {"Celsius", "degC"},
      {"RPM", "rpm"}, {"Hz", "Hz"}, {"Percent", "%"},
      {"Millis", "ms"}, {"Milliseconds", "ms"}, {"MS", "ms"}, {"Micros", "us"},
      {"Seconds", "s"}, {"Sec", "s"}, {"Secs", "s"},
  };

  /**
   * The unit a name states, where it states one by a suffix the conventions use
   * ({@code VelocityMetersPerSec}, {@code CurrentAmps}, {@code FullCycleMS}), or null. A name
   * is not evidence of what an entry holds; this reads only what the name itself says, and a
   * reader labels an axis with it as the name's claim, not the server's.
   */
  public static String unitFromName(String name) {
    var leaf = name;
    int dot = leaf.lastIndexOf('.');
    if (dot >= 0) leaf = leaf.substring(dot + 1);
    int slash = leaf.lastIndexOf('/');
    if (slash >= 0) leaf = leaf.substring(slash + 1);
    int bracket = leaf.indexOf('[');
    if (bracket >= 0) leaf = leaf.substring(0, bracket);
    for (var suffix : UNIT_SUFFIXES) {
      if (leaf.endsWith(suffix[0]) && leaf.length() > suffix[0].length()) {
        char before = leaf.charAt(leaf.length() - suffix[0].length() - 1);
        // The suffix must start a word: a lower-case letter before it, as camel case has
        if (Character.isLowerCase(before) || before == '_') return suffix[1];
      }
    }
    return null;
  }

  // ---- Arrow typing ----

  /**
   * The Arrow type of a series' values: by the entry's declared type, and for a struct by the
   * shape of its decoded values (the log's own schema, with the derived fields the decoder
   * adds), falling back on the schema's fields when no value is there to look at.
   */
  public static ArrowType arrowType(LogData log, Series series) {
    if (series.field() != null) return new ArrowType.Float64();
    return org.triplehelix.wpilogmcp.data.ReadArrowTypes.type(series.type(), log.structSchemas(),
        () -> series.values().stream().map(TimestampedValue::value).iterator());
  }

  // ---- CSV ----

  /**
   * The CSV columns of a series over its values, as {@code export_csv} names them: `timestamp_sec`,
   * then `index` when the values are arrays, then every flattened field in name order (`value`
   * for a scalar).
   */
  public static List<String> csvColumns(List<TimestampedValue> values) {
    var columns = new TreeSet<String>();
    boolean indexed = false;
    for (var tv : values) {
      var elements = ExportTools.ExportCsvTool.elementsOf(tv.value());
      if (elements != null) {
        indexed = true;
        for (var element : elements) columns.addAll(ExportTools.ExportCsvTool.flatten(element).keySet());
      } else {
        columns.addAll(ExportTools.ExportCsvTool.flatten(tv.value()).keySet());
      }
    }
    var out = new ArrayList<String>();
    out.add("timestamp_sec");
    if (indexed) out.add("index");
    out.addAll(columns);
    return out;
  }

  /** One sample's CSV rows (several for an array value), cells in the columns' order. */
  public static List<List<String>> csvRows(TimestampedValue tv, List<String> columns) {
    boolean indexed = columns.size() > 1 && columns.get(1).equals("index");
    var elements = ExportTools.ExportCsvTool.elementsOf(tv.value());
    var rows = new ArrayList<List<String>>();
    if (elements != null) {
      for (int i = 0; i < elements.size(); i++) {
        rows.add(row(tv.timestamp(), indexed ? i : -1, ExportTools.ExportCsvTool.flatten(elements.get(i)), columns));
      }
    } else {
      rows.add(row(tv.timestamp(), -1, ExportTools.ExportCsvTool.flatten(tv.value()), columns));
    }
    return rows;
  }

  private static List<String> row(double timestamp, int index, Map<String, Object> fields,
      List<String> columns) {
    var cells = new ArrayList<String>(columns.size());
    for (var column : columns) {
      if (column.equals("timestamp_sec")) {
        cells.add(String.valueOf(timestamp));
      } else if (column.equals("index")) {
        cells.add(index >= 0 ? String.valueOf(index) : "");
      } else {
        var v = fields.get(column);
        cells.add(v == null ? "" : csvEscape(String.valueOf(v)));
      }
    }
    return cells;
  }

  /** Escapes a cell as export_csv does (RFC 4180). */
  public static String csvEscape(String value) {
    if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
      return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    return value;
  }
}
