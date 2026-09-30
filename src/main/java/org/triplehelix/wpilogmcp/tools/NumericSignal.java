/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;

/**
 * A numeric time series read from an entry, a struct field, or an array element — what every
 * numeric tool measures (review issues D1, D2).
 *
 * <p>A signal is named by an entry and an optional {@link FieldPath}: either a {@code field}
 * argument, or a path appended to the entry name ({@code /RealOutputs/Drive/Pose.translation.x},
 * {@code /PowerDistribution/ChannelCurrent[3]}, {@code /Vision/Camera0/PoseObservations[*].tagCount}).
 * An exact entry name always wins, since names can contain dots; otherwise the longest entry name
 * followed by {@code .} or {@code [} is the entry. Booleans read as 1/0 and enum fields as their
 * number. Asking for a non-numeric value is an error that says what the entry is and lists the
 * numeric fields it does have.
 *
 * <p>Fields that hold angles (a {@code Rotation2d}'s value and derived degrees, a
 * {@code Rotation3d}'s derived roll, pitch, and yaw, a {@code SwerveSample}'s heading) are marked
 * with their unit, so tools can unwrap them and use circular statistics.
 *
 * @since 0.9.0
 */
final class NumericSignal {

  /** The unit of an angle field; {@code NONE} for anything else. */
  enum AngleUnit {
    NONE(0),
    RADIANS(2 * Math.PI),
    DEGREES(360.0);

    /** One full turn in this unit. */
    final double period;

    AngleUnit(double period) {
      this.period = period;
    }

    String wire() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  /** How numeric tools name what they measure, appended to their descriptions. */
  static final String PATH_HELP = " The name is an entry, or an entry with a field path "
      + "appended: a struct field (/RealOutputs/Drive/Pose.translation.x) or an array element "
      + "(/PowerDistribution/ChannelCurrent[3], /Vision/Camera0/PoseObservations[0].tagCount); "
      + "or pass the path as field. get_entry_info lists an entry's numeric_leaf_paths. "
      + "Booleans read as 1/0 and enum fields as their number. Angle fields (a Rotation2d's "
      + "value or derived degrees, a Rotation3d's derived roll/pitch/yaw) are unwrapped, so "
      + "crossing +-180 degrees is not a jump.";

  /** The schema text of a {@code field} parameter. */
  static final String FIELD_PARAM = "Field path inside the entry's values, e.g. 'translation.x', "
      + "'[3]', '[0].tagCount' (or append it to the name)";

  private static final Set<String> NUMERIC_ARRAYS =
      Set.of("double[]", "float[]", "int64[]", "boolean[]");

  /** Numeric leaf paths listed in an error message, at most. */
  private static final int LISTED_FIELDS = 24;

  private final String entry;
  private final FieldPath path;
  private final String type;
  private final AngleUnit angle;
  private final List<TimestampedValue> values;
  private final int recordCount;
  private final int recordsWithoutValue;

  private NumericSignal(String entry, FieldPath path, String type, AngleUnit angle,
      List<TimestampedValue> values, int recordCount, int recordsWithoutValue) {
    this.entry = entry;
    this.path = path;
    this.type = type;
    this.angle = angle;
    this.values = values;
    this.recordCount = recordCount;
    this.recordsWithoutValue = recordsWithoutValue;
  }

  /** The entry the values come from. */
  String entry() {
    return entry;
  }

  /** The field path inside the entry's values (root for the whole value). */
  FieldPath path() {
    return path;
  }

  /** The entry's type. */
  String type() {
    return type;
  }

  /** The angle unit of the field, or {@code NONE}. */
  AngleUnit angle() {
    return angle;
  }

  boolean isAngle() {
    return angle != AngleUnit.NONE;
  }

  /** The entry and path as one name, e.g. {@code /RealOutputs/Drive/Pose.translation.x}. */
  String label() {
    return entry + path;
  }

  /**
   * The values as Doubles in time order (NaN kept); for a {@code [*]} path, one value per element,
   * several sharing a timestamp.
   */
  List<TimestampedValue> values() {
    return values;
  }

  /** Records of the entry. */
  int recordCount() {
    return recordCount;
  }

  /** Records in which the path held no number (e.g. an empty array for {@code [0]}). */
  int recordsWithoutValue() {
    return recordsWithoutValue;
  }

  /** Whether several values can share a timestamp ({@code [*]} paths). */
  boolean multiValued() {
    return path.hasWildcard();
  }

  /**
   * This signal, when it has one value per timestamp.
   *
   * @throws IllegalArgumentException for a {@code [*]} path, naming the tool
   */
  NumericSignal requireSingleValued(String tool) {
    if (multiValued()) {
      throw new IllegalArgumentException(label() + " selects every element ([*]), so a sample "
          + "can hold several values; " + tool + " needs one value per sample. Select one element "
          + "with [i] (e.g. " + entry + path.toString().replaceFirst("\\[\\*]", "[0]")
          + "); get_statistics accepts [*] to pool every element.");
    }
    return this;
  }

  /** How the signal was resolved, for results: entry, field, type, angle unit. */
  JsonObject describe() {
    var o = new JsonObject();
    o.addProperty("entry", entry);
    if (!path.isRoot()) o.addProperty("field", path.toString());
    o.addProperty("type", type);
    if (isAngle()) o.addProperty("angle_unit", angle.wire());
    return o;
  }

  /**
   * Values of this signal (e.g. a window of {@link #values()}) made continuous when it is an
   * angle: each step is taken as the shortest turn, so crossing +-180 degrees does not jump by a
   * full turn; the first value is kept as logged. Other signals are returned unchanged. NaN
   * values stay NaN and do not break continuity.
   */
  List<TimestampedValue> unwrap(List<TimestampedValue> values) {
    if (!isAngle() || values.isEmpty()) return values;
    var out = new ArrayList<TimestampedValue>(values.size());
    double previous = Double.NaN;
    double turns = 0; // whole turns added so far: values stay exact between wraps
    for (var tv : values) {
      double v = (Double) tv.value();
      if (!Double.isFinite(v)) {
        out.add(tv);
        continue;
      }
      if (!Double.isNaN(previous)) turns -= Math.rint((v - previous) / angle.period);
      out.add(turns == 0 ? tv : new TimestampedValue(tv.timestamp(), v + turns * angle.period));
      previous = v;
    }
    return out;
  }

  /** How many steps between consecutive finite values jump by more than a half turn. */
  int wrapCount(List<TimestampedValue> values) {
    if (!isAngle()) return 0;
    int wraps = 0;
    double previous = Double.NaN;
    for (var tv : values) {
      double v = (Double) tv.value();
      if (!Double.isFinite(v)) continue;
      if (!Double.isNaN(previous) && Math.abs(v - previous) > angle.period / 2) wraps++;
      previous = v;
    }
    return wraps;
  }

  /** A difference reduced to (-half turn, +half turn]. */
  static double wrapToHalfTurn(double difference, double period) {
    double wrapped = difference - period * Math.floor(difference / period + 0.5);
    return wrapped == -period / 2 ? period / 2 : wrapped;
  }

  /**
   * Circular statistics of angles in the given unit: mean direction, resultant length R (1 = all
   * equal, 0 = spread evenly), and circular standard deviation sqrt(-2 ln R), in the same unit.
   * The standard deviation is null when R is 0 (no mean direction).
   */
  static JsonObject circularStatistics(double[] angles, AngleUnit unit) {
    double toRadians = 2 * Math.PI / unit.period;
    double c = 0;
    double s = 0;
    int n = 0;
    for (double a : angles) {
      if (!Double.isFinite(a)) continue;
      c += Math.cos(a * toRadians);
      s += Math.sin(a * toRadians);
      n++;
    }
    var o = new JsonObject();
    if (n == 0) return o;
    c /= n;
    s /= n;
    double r = Math.min(1.0, Math.hypot(c, s));
    o.addProperty("resultant_length", r);
    if (r > 1e-12) {
      o.addProperty("circular_mean", Math.atan2(s, c) / toRadians);
      o.addProperty("circular_std", Math.sqrt(-2 * Math.log(r)) / toRadians);
    } else {
      o.add("circular_mean", com.google.gson.JsonNull.INSTANCE);
      o.add("circular_std", com.google.gson.JsonNull.INSTANCE);
    }
    return o;
  }

  // ==================== resolution ====================

  /**
   * Resolves a numeric signal.
   *
   * @param log The log
   * @param name An entry name, or an entry name with a field path appended
   * @param field An explicit field path (then {@code name} must be an entry), or null
   * @throws IllegalArgumentException when the entry does not exist or holds no number at the path,
   *     with the entry's type and its numeric fields
   */
  static NumericSignal resolve(LogData log, String name, String field) {
    String entryName;
    FieldPath path;
    if (field != null && !field.isBlank()) {
      if (!log.entries().containsKey(name)) throw notFound(log, name);
      entryName = name;
      path = FieldPath.parse(field.strip());
    } else if (log.entries().containsKey(name)) {
      entryName = name;
      path = FieldPath.ROOT;
    } else {
      entryName = longestEntryPrefix(log, name);
      if (entryName == null) throw notFound(log, name);
      path = FieldPath.parse(name.substring(entryName.length()));
    }
    var type = log.entries().get(entryName).type();
    var structName = StructSchemas.structName(type);
    boolean scalar = ToolUtils.isNumericType(type) || "boolean".equals(type);

    if (path.isRoot() && !scalar) throw notANumber(log, entryName, type, structName);
    if (!path.isRoot() && scalar) {
      throw new IllegalArgumentException("Entry " + entryName + " is " + type
          + ", a single number: it has no field " + path + ". Pass the entry name alone.");
    }
    if (!path.isRoot() && structName == null && !NUMERIC_ARRAYS.contains(type)) {
      throw new IllegalArgumentException("Entry " + entryName + " is " + type + "; field paths "
          + "apply to struct entries and numeric arrays (double[], float[], int64[], boolean[]).");
    }

    var raw = log.values().get(entryName);
    if (raw == null) raw = List.of();
    var out = new ArrayList<TimestampedValue>(raw.size());
    int without = 0;
    for (var tv : raw) {
      if (path.hasWildcard()) {
        int before = out.size();
        for (var leaf : path.resolveAll(tv.value())) {
          var number = FieldPath.toNumber(leaf);
          if (number != null) out.add(new TimestampedValue(tv.timestamp(), number));
        }
        if (out.size() == before) without++;
      } else {
        var number = FieldPath.toNumber(path.resolveOne(tv.value()));
        if (number != null) {
          out.add(new TimestampedValue(tv.timestamp(), number));
        } else {
          without++;
        }
      }
    }
    if (!path.isRoot() && out.isEmpty() && !raw.isEmpty()) {
      throw fieldNotNumeric(log, entryName, type, structName, path, raw);
    }
    var angle = structName != null
        ? angleUnit(log.structSchemas(), structName, path) : AngleUnit.NONE;
    return new NumericSignal(entryName, path, type, angle, out, raw.size(), without);
  }

  /** The longest entry name that {@code name} starts with, followed by '.' or '['; or null. */
  static String longestEntryPrefix(LogData log, String name) {
    for (int i = name.length() - 1; i > 0; i--) {
      char c = name.charAt(i);
      if ((c == '.' || c == '[') && log.entries().containsKey(name.substring(0, i))) {
        return name.substring(0, i);
      }
    }
    return null;
  }

  private static IllegalArgumentException notFound(LogData log, String name) {
    var lower = name.toLowerCase(java.util.Locale.ROOT);
    var suggestions = log.entries().keySet().stream()
        .filter(n -> n.toLowerCase(java.util.Locale.ROOT).contains(lower)
            || lower.startsWith(n.toLowerCase(java.util.Locale.ROOT)))
        .sorted().limit(5).toList();
    return new IllegalArgumentException("Entry not found: " + name
        + (suggestions.isEmpty() ? "" : ". Did you mean: " + String.join(", ", suggestions) + "?"));
  }

  /** The numeric fields an entry has, relative to it: ".translation.x", "[*].tagCount". */
  static List<String> numericFields(LogData log, String type, String structName) {
    if (NUMERIC_ARRAYS.contains(type)) return List.of("[i]", "[*]");
    if (structName == null) return List.of();
    var prefix = StructSchemas.isArrayType(type) ? "[*]." : ".";
    return log.structSchemas().numericLeafPaths(structName).stream().map(p -> prefix + p).toList();
  }

  private static IllegalArgumentException notANumber(LogData log, String entryName, String type,
      String structName) {
    if (NUMERIC_ARRAYS.contains(type)) {
      return new IllegalArgumentException("Entry " + entryName + " is " + type + ", one value per "
          + "element. Select an element: " + entryName + "[0] (or field \"[0]\"), or "
          + entryName + "[*] for every element.");
    }
    if (structName != null) {
      var fields = numericFields(log, type, structName);
      if (fields.isEmpty()) {
        return new IllegalArgumentException("Entry " + entryName + " is " + type + ", which this "
            + "log cannot decode into numeric fields (see get_entry_info).");
      }
      return new IllegalArgumentException("Entry " + entryName + " is " + type + ", not a number; "
          + "its numeric fields are " + listFields(fields) + ". Pass one as field, or append it "
          + "to the name (e.g. " + entryName + fields.get(0) + ").");
    }
    return new IllegalArgumentException("Entry " + entryName + " is " + type + ", not numeric: "
        + "numeric tools read double, float, int64, and boolean entries, and numeric fields of "
        + "structs and arrays.");
  }

  private static IllegalArgumentException fieldNotNumeric(LogData log, String entryName,
      String type, String structName, FieldPath path, List<TimestampedValue> raw) {
    Object sample = null;
    for (var tv : raw) {
      var resolved = path.hasWildcard() ? path.resolveAll(tv.value()).stream().findFirst()
          .orElse(null) : path.resolveOne(tv.value());
      if (resolved != null) {
        sample = resolved;
        break;
      }
    }
    var fields = numericFields(log, type, structName);
    String what = sample instanceof java.util.Map<?, ?> ? "is a struct, not a number"
        : sample instanceof String ? "is text, not a number"
        : sample != null ? "is not a number"
        : NUMERIC_ARRAYS.contains(type) || StructSchemas.isArrayType(type)
            ? "matches no element in any sample (arrays may be shorter, or empty)"
            : "does not exist";
    return new IllegalArgumentException("Field " + path + " of " + entryName + " (" + type + ") "
        + what + (fields.isEmpty() ? "." : "; numeric fields are " + listFields(fields) + "."));
  }

  private static String listFields(List<String> fields) {
    return fields.size() <= LISTED_FIELDS ? String.join(", ", fields)
        : String.join(", ", fields.subList(0, LISTED_FIELDS)) + ", and "
            + (fields.size() - LISTED_FIELDS) + " more";
  }

  /** Whether the value at a path is an angle, by walking the struct's schema. */
  static AngleUnit angleUnit(StructSchemas schemas, String structName, FieldPath path) {
    String current = structName;
    var steps = path.steps();
    for (int i = 0; i < steps.size(); i++) {
      if (!(steps.get(i) instanceof FieldPath.Field f)) continue; // elements keep the type
      if (current == null) return AngleUnit.NONE;
      if (f.name().equals("_derived")) {
        if (i + 1 >= steps.size() || !(steps.get(i + 1) instanceof FieldPath.Field derived)) {
          return AngleUnit.NONE;
        }
        return switch (current) {
          case "Rotation2d" -> derived.name().equals("degrees") ? AngleUnit.DEGREES
              : AngleUnit.NONE;
          case "Rotation3d" -> derived.name().endsWith("_deg") ? AngleUnit.DEGREES
              : Set.of("roll", "pitch", "yaw").contains(derived.name()) ? AngleUnit.RADIANS
                  : AngleUnit.NONE;
          default -> AngleUnit.NONE;
        };
      }
      var info = schemas.info(current);
      if (info.isEmpty()) return AngleUnit.NONE;
      var fieldInfo = info.get().fields().stream().filter(x -> x.name().equals(f.name()))
          .findFirst();
      if (fieldInfo.isEmpty()) return AngleUnit.NONE;
      if (fieldInfo.get().structType() != null) {
        current = fieldInfo.get().structType();
        continue;
      }
      // a leaf (possibly followed by array indexes)
      boolean leafIsLast = steps.subList(i + 1, steps.size()).stream()
          .noneMatch(s -> s instanceof FieldPath.Field);
      if (!leafIsLast) return AngleUnit.NONE;
      if (current.equals("Rotation2d") && f.name().equals("value")) return AngleUnit.RADIANS;
      if (current.equals("SwerveSample") && f.name().equals("heading")) return AngleUnit.RADIANS;
      return AngleUnit.NONE;
    }
    return AngleUnit.NONE;
  }
}
