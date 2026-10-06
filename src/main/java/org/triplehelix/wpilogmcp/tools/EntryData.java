/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.triplehelix.wpilogmcp.data.ArrowType;
import org.triplehelix.wpilogmcp.data.ArrowType.Field;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.log.struct.EnumValue;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
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
   */
  public record Series(String name, String entry, String field, String type,
      List<TimestampedValue> values, boolean numeric) {

    /** The samples within the window; a null bound is open. */
    public List<TimestampedValue> inWindow(Double startTime, Double endTime) {
      if (startTime == null && endTime == null) return values;
      return values.stream()
          .filter(tv -> startTime == null || tv.timestamp() >= startTime)
          .filter(tv -> endTime == null || tv.timestamp() <= endTime)
          .toList();
    }
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
      var values = log.values().get(name);
      if (values == null) values = List.of();
      boolean numeric = ToolUtils.isNumericType(info.type());
      return new Series(name, name, null, info.type(), values, numeric);
    }
    var signal = NumericSignal.resolve(log, name, null);
    return new Series(name, signal.entry(), signal.path().toString(), signal.type(),
        signal.values(), true);
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
    var type = series.type();
    switch (type) {
      case "double", "float", "revlog" -> {
        return new ArrowType.Float64();
      }
      case "int64" -> {
        return new ArrowType.Int64();
      }
      case "boolean" -> {
        return new ArrowType.Bool();
      }
      case "string", "json" -> {
        return new ArrowType.Utf8();
      }
      case "raw" -> {
        return new ArrowType.Binary();
      }
      case "double[]", "float[]" -> {
        return list(new ArrowType.Float64());
      }
      case "int64[]" -> {
        return list(new ArrowType.Int64());
      }
      case "boolean[]" -> {
        return list(new ArrowType.Bool());
      }
      case "string[]" -> {
        return list(new ArrowType.Utf8());
      }
      default -> {
        var structName = StructSchemas.structName(type);
        if (structName == null) return new ArrowType.Binary();
        boolean array = StructSchemas.isArrayType(type);
        var element = structType(log, structName, series.values(), array);
        return array ? list(element) : element;
      }
    }
  }

  private static ArrowType list(ArrowType element) {
    return new ArrowType.ListOf(new Field("item", element, true));
  }

  /** The struct's Arrow type from its first decoded value, else from its schema's fields. */
  private static ArrowType structType(LogData log, String structName,
      List<TimestampedValue> values, boolean array) {
    for (var tv : values) {
      Object sample = tv.value();
      if (array) {
        var elements = sample instanceof List<?> l ? l : null;
        if (elements == null || elements.isEmpty()) continue;
        sample = elements.get(0);
      }
      if (sample instanceof Map<?, ?> map) return typeOfValue(map);
    }
    return fromSchema(log.structSchemas(), structName, 0);
  }

  /** The Arrow type of a decoded value, recursively. */
  private static ArrowType typeOfValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      var fields = new ArrayList<Field>();
      for (var e : map.entrySet()) {
        fields.add(new Field(String.valueOf(e.getKey()), typeOfValue(e.getValue()), true));
      }
      return new ArrowType.Struct(fields);
    }
    if (value instanceof EnumValue) {
      return new ArrowType.Struct(List.of(new Field("value", new ArrowType.Int64(), true),
          new Field("label", new ArrowType.Utf8(), true)));
    }
    if (value instanceof Boolean) return new ArrowType.Bool();
    if (value instanceof Double || value instanceof Float) return new ArrowType.Float64();
    if (value instanceof Number) return new ArrowType.Int64();
    if (value instanceof String) return new ArrowType.Utf8();
    if (value instanceof byte[]) return new ArrowType.Binary();
    if (value instanceof double[] || value instanceof float[]) return list(new ArrowType.Float64());
    if (value instanceof long[] || value instanceof int[]) return list(new ArrowType.Int64());
    if (value instanceof boolean[]) return list(new ArrowType.Bool());
    if (value instanceof String[]) return list(new ArrowType.Utf8());
    if (value instanceof List<?> l) {
      return list(l.isEmpty() ? new ArrowType.Float64() : typeOfValue(l.get(0)));
    }
    if (value == null) return new ArrowType.Float64();
    return new ArrowType.Utf8();
  }

  /** The struct's Arrow type from the schema alone: when no record of it was decoded. */
  private static ArrowType fromSchema(StructSchemas schemas, String structName, int depth) {
    var info = schemas.info(structName).orElse(null);
    if (info == null || depth > 8) return new ArrowType.Struct(List.of());
    var fields = new ArrayList<Field>();
    for (var f : info.fields()) {
      ArrowType type = switch (f.type()) {
        case "double", "float" -> new ArrowType.Float64();
        case "bool" -> new ArrowType.Bool();
        case "char" -> new ArrowType.Utf8();
        case "int8", "int16", "int32", "int64", "uint8", "uint16", "uint32", "uint64" ->
            new ArrowType.Int64();
        default -> fromSchema(schemas, f.type(), depth + 1);
      };
      if (f.isArray() && !"char".equals(f.type())) type = list(type);
      fields.add(new Field(f.name(), type, true));
    }
    return new ArrowType.Struct(fields);
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
