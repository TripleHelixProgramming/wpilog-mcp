/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log.struct;

import edu.wpi.first.util.struct.BadSchemaException;
import edu.wpi.first.util.struct.StructDescriptor;
import edu.wpi.first.util.struct.StructDescriptorDatabase;
import edu.wpi.first.util.struct.StructFieldDescriptor;
import edu.wpi.first.util.struct.StructFieldType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.triplehelix.wpilogmcp.log.EntryInfo;

/**
 * The struct schemas of one log, and decoding by them.
 *
 * <p>A log carries a schema for every struct type it records ({@code /.schema/struct:<Name>},
 * type {@code structschema}; NetworkTables-logged schemas appear as
 * {@code NT:/.schema/struct:<Name>}). Values are decoded only from those schemas, parsed by WPILib's
 * {@link StructDescriptorDatabase} (nested structs in any order, fixed-size arrays, enums,
 * bit-fields), so a team's own struct — or a team's edited copy of a template struct — decodes
 * exactly as it was written. When a log lacks a schema, WPILib's canonical schemas and a few
 * template schemas fill in, and {@link #source} says so.
 *
 * <p>Decoded structs are {@link LinkedHashMap}s keyed by the schema's field names, in schema
 * order; nested structs are nested maps, fixed-size arrays are lists, enum fields are
 * {@link EnumValue}s. Record length is checked exactly (a multiple of the struct size for arrays).
 * {@code Rotation2d} and {@code Rotation3d} values whose schema matches WPILib's gain a
 * {@code _derived} map (degrees; roll, pitch, and yaw), wherever they are nested.
 *
 * <p>Immutable after construction except for a concurrent cache of compiled decode plans, so one
 * instance is safely shared by all threads decoding the log.
 *
 * @since 0.9.0
 */
public final class StructSchemas {

  // An untrusted acyclic chain can overflow the stack just as a cycle can.
  private static final int MAX_DEPTH = 64;

  /** Where a struct's schema came from. */
  public enum Source {
    /** The log's own {@code /.schema/struct:} entry. */
    LOGGED,
    /** WPILib's canonical schema, because the log did not record one. */
    WPILIB,
    /** A team-template layout, because the log did not record one: an assumption. */
    ASSUMED;

    public String wire() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  /** One field of a struct, as the schema declares it. */
  public record FieldInfo(String name, String type, int arraySize, int bitWidth,
      Map<String, Long> enumValues, String structType) {
    public boolean isArray() {
      return arraySize > 1;
    }
  }

  /** A struct type in this log. */
  public record StructInfo(String name, Source source, String schema, int size, boolean valid,
      String error, List<FieldInfo> fields, String schemaEntry) {}

  private static final String DERIVED = "_derived";

  private final StructDescriptorDatabase db = new StructDescriptorDatabase();
  private final Map<String, Source> sources = new LinkedHashMap<>();
  private final Map<String, String> schemaText = new HashMap<>();
  private final Map<String, String> schemaEntries = new HashMap<>();
  private final Map<String, String> errors = new HashMap<>();
  private final ConcurrentHashMap<String, Plan> plans = new ConcurrentHashMap<>();

  private StructSchemas() {}

  /**
   * Builds the schemas for a log.
   *
   * @param logged Struct name to schema text, from the log's schema entries
   * @param entries Struct name to the schema entry's name (for reporting)
   */
  public static StructSchemas of(Map<String, String> logged, Map<String, String> entries) {
    var s = recordedOnly(logged, entries);
    CanonicalSchemas.WPILIB.forEach((name, schema) -> {
      if (!s.sources.containsKey(name)) s.add(name, schema, Source.WPILIB);
    });
    CanonicalSchemas.ASSUMED.forEach((name, schema) -> {
      if (!s.sources.containsKey(name)) s.add(name, schema, Source.ASSUMED);
    });
    return s;
  }

  /** A dashboard cannot disclose schema assumptions beside each number. Decode only the
   * schemas actually published, including nested types; missing dependencies stay missing. */
  public static StructSchemas recordedOnly(Map<String, String> logged, Map<String, String> entries) {
    var s = new StructSchemas();
    logged.forEach((name, schema) -> s.add(name, schema, Source.LOGGED));
    s.schemaEntries.putAll(entries);
    return s;
  }

  /** A schema entry's name: {@code /.schema/struct:Name}, also under a prefix such as NT:. */
  private static final Pattern SCHEMA_ENTRY = Pattern.compile("^(?:.*/)?\\.schema/struct:(.+)$");

  /** The struct a schema entry describes, or null when the entry is not a struct schema. */
  public static String schemaEntryStruct(String entryName, String type) {
    if (!"structschema".equals(type)) return null;
    var m = SCHEMA_ENTRY.matcher(entryName);
    return m.matches() ? m.group(1).strip() : null;
  }

  /**
   * Builds the schemas recorded by a log's schema entries. When a struct's schema is recorded
   * twice (DataLog and NetworkTables), the DataLog one ({@code /.schema/...}) wins.
   *
   * @param entries The log's entries, in declaration order
   * @param firstValue The first value of an entry (the schema text, as a String or bytes), or null
   */
  public static StructSchemas fromLog(Map<String, EntryInfo> entries,
      Function<String, Object> firstValue) {
    var logged = new LinkedHashMap<String, String>();
    var from = new HashMap<String, String>();
    for (var info : entries.values()) {
      var struct = schemaEntryStruct(info.name(), info.type());
      if (struct == null) continue;
      var prior = from.get(struct);
      if (prior != null && !(isPrefixed(prior) && !isPrefixed(info.name()))) continue;
      var value = firstValue.apply(info.name());
      String text = value instanceof String str ? str
          : value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : null;
      if (text == null) continue;
      logged.put(struct, text);
      from.put(struct, info.name());
    }
    return logged.isEmpty() ? FALLBACK : of(logged, from);
  }

  private static boolean isPrefixed(String entryName) {
    return !entryName.startsWith("/");
  }

  /** Schemas for a log that records none: WPILib's canonical ones and the template ones. */
  public static StructSchemas fallbackOnly() {
    return FALLBACK;
  }

  private static final StructSchemas FALLBACK = of(Map.of(), Map.of());

  private void add(String name, String schema, Source source) {
    sources.put(name, source);
    schemaText.put(name, schema);
    try {
      db.add(name, schema);
    } catch (StackOverflowError e) {
      errors.put(name, "schema depth exceeds the supported limit for " + name);
    } catch (BadSchemaException | RuntimeException e) {
      errors.put(name, "invalid schema for " + name + " (" + e.getMessage() + "): " + schema);
    }
  }

  /** The struct name in a type string: "struct:Pose2d[]" → "Pose2d"; null if not a struct. */
  public static String structName(String type) {
    String t;
    if (type.startsWith("struct:")) t = type.substring(7);
    else if (type.startsWith("structarray:")) t = type.substring(12);
    else return null;
    return t.endsWith("[]") ? t.substring(0, t.length() - 2).strip() : t.strip();
  }

  /** Whether a struct type string denotes an array. */
  public static boolean isArrayType(String type) {
    return type.endsWith("[]") || type.startsWith("structarray:");
  }

  // ==================== information ====================

  public Source source(String name) {
    return sources.get(name);
  }

  /** Struct types this log records schemas for (logged sources only), in declaration order. */
  public List<String> loggedStructs() {
    return sources.entrySet().stream().filter(e -> e.getValue() == Source.LOGGED)
        .map(Map.Entry::getKey).toList();
  }

  /** Every struct type known here: logged, then WPILib's, then assumed. */
  public List<String> allStructs() {
    return List.copyOf(sources.keySet());
  }

  public Optional<StructInfo> info(String name) {
    if (!sources.containsKey(name)) return Optional.empty();
    var desc = db.find(name);
    boolean valid = desc != null && desc.isValid() && !errors.containsKey(name);
    String error = errors.get(name);
    if (error == null && desc != null && !desc.isValid()) {
      error = "schema for " + name + " references a struct with no valid schema: "
          + schemaText.get(name);
    }
    var fields = new ArrayList<FieldInfo>();
    if (desc != null) {
      for (var f : desc.getFields()) {
        fields.add(new FieldInfo(f.getName(), typeName(f), f.getArraySize(),
            f.isBitField() ? f.getBitWidth() : 0,
            f.hasEnum() ? Map.copyOf(f.getEnumValues()) : Map.of(),
            f.getStruct() != null ? f.getStruct().getName() : null));
      }
    }
    return Optional.of(new StructInfo(name, sources.get(name), schemaText.get(name),
        valid ? desc.getSize() : 0, valid, error, List.copyOf(fields), schemaEntries.get(name)));
  }

  private static String typeName(StructFieldDescriptor f) {
    return f.getType() == StructFieldType.kStruct ? f.getStruct().getName() : f.getType().name;
  }

  /**
   * Addressable numeric leaf paths of a struct: {@code translation.x}, {@code currents[1]},
   * enum fields (their numeric value), booleans (0/1), and the {@code _derived} values of
   * rotations. Arrays longer than 8 are listed as {@code field[*]}.
   */
  public List<String> numericLeafPaths(String name) {
    var out = new ArrayList<String>();
    var plan = plan(name);
    if (plan != null) collectPaths(plan, "", out);
    return out;
  }

  private static void collectPaths(Plan plan, String prefix, List<String> out) {
    for (var f : plan.fields) {
      var base = prefix + f.name;
      if (f.type == StructFieldType.kChar) continue;
      List<String> names = new ArrayList<>();
      if (f.arraySize > 1) {
        if (f.arraySize <= 8) {
          for (int i = 0; i < f.arraySize; i++) names.add(base + "[" + i + "]");
        } else {
          names.add(base + "[*]");
        }
      } else {
        names.add(base);
      }
      for (var n : names) {
        if (f.nested != null) collectPaths(f.nested, n + ".", out);
        else out.add(n);
      }
    }
    if (plan.enricher != null) {
      for (var d : plan.enricher.keys()) out.add(prefix + DERIVED + "." + d);
    }
  }

  // ==================== decoding ====================

  /**
   * Decodes a struct record by the log's schema for its type.
   *
   * @param type The entry type ({@code struct:Name} or {@code struct:Name[]})
   * @param data The record payload
   * @return A map (single struct) or a list of maps (array)
   * @throws StructDecodeException when there is no usable schema or the length does not fit
   */
  public Object decode(String type, byte[] data) {
    var name = structName(type);
    if (name == null) throw new StructDecodeException("not a struct type: " + type);
    var plan = plan(name);
    if (plan == null) {
      var info = info(name);
      if (info.isPresent() && info.get().error() != null) {
        throw new StructDecodeException(info.get().error());
      }
      throw new StructDecodeException("no schema for struct " + name + " in this log (no "
          + "/.schema/struct:" + name + " entry, and not a WPILib or template struct)");
    }
    var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    if (isArrayType(type)) {
      if (plan.size == 0) {
        throw new StructDecodeException("struct " + name + " has size 0");
      }
      if (data.length % plan.size != 0) {
        throw new StructDecodeException("record is " + data.length + " bytes, not a multiple of "
            + "the " + plan.size + "-byte " + name + " schema (" + sources.get(name).wire() + ")");
      }
      var elements = new Object[data.length / plan.size];
      for (int i = 0; i < elements.length; i++) elements[i] = plan.decode(buffer, i * plan.size);
      return List.of(elements);
    }
    if (data.length != plan.size) {
      throw new StructDecodeException("record is " + data.length + " bytes; the " + name
          + " schema (" + sources.get(name).wire() + ") is " + plan.size + " bytes");
    }
    return plan.decode(buffer, 0);
  }

  /** The compiled decode plan for a struct, or null when it has no valid schema. */
  private Plan plan(String name) {
    var cached = plans.get(name);
    if (cached != null) return cached;
    var desc = db.find(name);
    if (desc == null || !desc.isValid() || errors.containsKey(name)) return null;
    var plan = compile(desc, new HashMap<>(), 0);
    plans.putIfAbsent(name, plan);
    return plans.get(name);
  }

  private Plan compile(StructDescriptor desc, Map<String, Plan> inProgress, int depth) {
    if (depth >= MAX_DEPTH) throw new StructDecodeException("schema depth exceeds " + MAX_DEPTH
        + " at " + desc.getName());
    var existing = inProgress.get(desc.getName());
    if (existing != null) {
      if (depth + existing.depth() > MAX_DEPTH) throw new StructDecodeException("schema depth exceeds " + MAX_DEPTH
          + " at " + desc.getName());
      return existing;
    }
    var fields = new ArrayList<FieldPlan>();
    var enricher = enricherFor(desc.getName());
    var names = new ArrayList<String>();
    desc.getFields().forEach(f -> names.add(f.getName()));
    if (enricher != null) names.add(DERIVED);
    int planDepth = 1;
    for (var f : desc.getFields()) {
      Map<Long, String> labels = null;
      if (f.hasEnum()) {
        labels = new HashMap<>();
        for (var e : f.getEnumValues().entrySet()) labels.put(e.getValue(), e.getKey());
      }
      Plan nested = f.getType() == StructFieldType.kStruct ? compile(f.getStruct(), inProgress, depth + 1)
          : null;
      if (nested != null) planDepth = Math.max(planDepth, 1 + nested.depth());
      fields.add(new FieldPlan(f.getName(), f.getType(), f.getOffset(), f.getSize(),
          f.getArraySize(), f.isBitField(), f.getBitShift(), f.getBitWidth(), f.isInt(),
          labels, nested));
    }
    var plan = new Plan(desc.getSize(), List.copyOf(fields), enricher, names.toArray(String[]::new), planDepth);
    inProgress.put(desc.getName(), plan);
    return plan;
  }

  private Enricher enricherFor(String name) {
    var text = schemaText.get(name);
    var canonical = CanonicalSchemas.WPILIB.get(name);
    if (text == null || canonical == null
        || !CanonicalSchemas.normalize(text).equals(CanonicalSchemas.normalize(canonical))) {
      return null;
    }
    return switch (name) {
      case "Rotation2d" -> Enricher.ROTATION2D;
      case "Rotation3d" -> Enricher.ROTATION3D;
      default -> null;
    };
  }

  // ==================== plans ====================

  /** How to decode one struct type; {@code keys} are its field names (and _derived), shared. */
  private record Plan(int size, List<FieldPlan> fields, Enricher enricher, String[] keys, int depth) {
    Map<String, Object> decode(ByteBuffer buffer, int base) {
      var values = new Object[keys.length];
      for (int i = 0; i < fields.size(); i++) values[i] = fields.get(i).read(buffer, base);
      var map = new StructMap(keys, values);
      if (enricher != null) values[keys.length - 1] = enricher.derive(map);
      return map;
    }
  }

  private record FieldPlan(String name, StructFieldType type, int offset, int size,
      int arraySize, boolean bitField, int shift, int width, boolean signed,
      Map<Long, String> labels, Plan nested) {

    Object read(ByteBuffer buffer, int base) {
      int start = base + offset;
      if (type == StructFieldType.kChar) {
        var bytes = new byte[arraySize];
        buffer.get(start, bytes);
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] == 0) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
      }
      if (arraySize > 1) {
        var elements = new Object[arraySize];
        for (int i = 0; i < arraySize; i++) elements[i] = readOne(buffer, start + i * size);
        return List.of(elements);
      }
      return readOne(buffer, start);
    }

    private Object readOne(ByteBuffer buffer, int pos) {
      if (nested != null) return nested.decode(buffer, pos);
      switch (type) {
        case kDouble:
          return buffer.getDouble(pos);
        case kFloat:
          return buffer.getFloat(pos);
        case kBool:
          if (bitField) return ((unsignedLe(buffer, pos, size) >>> shift) & 1L) != 0;
          return buffer.get(pos) != 0;
        default:
          break;
      }
      long raw = unsignedLe(buffer, pos, size);
      long value;
      if (bitField) {
        long mask = width >= 64 ? -1L : (1L << width) - 1;
        value = (raw >>> shift) & mask;
        if (signed && width < 64 && (value & (1L << (width - 1))) != 0) value -= (1L << width);
      } else if (signed && size < 8) {
        int bits = size * 8;
        value = (raw & (1L << (bits - 1))) != 0 ? raw - (1L << bits) : raw;
      } else {
        value = raw;
      }
      Object number = !signed && size == 8 && value < 0
          ? (Object) Double.parseDouble(Long.toUnsignedString(value)) : (Object) value;
      if (labels != null) return new EnumValue(value, labels.get(value));
      return number;
    }

    private static long unsignedLe(ByteBuffer buffer, int pos, int size) {
      long v = 0;
      for (int i = 0; i < size; i++) v |= (buffer.get(pos + i) & 0xFFL) << (8 * i);
      return v;
    }
  }

  // ==================== enrichment ====================

  private enum Enricher {
    ROTATION2D(new String[] {"degrees"}) {
      @Override
      Object[] values(Map<String, Object> map) {
        return map.get("value") instanceof Double rad
            ? new Object[] {Math.toDegrees(rad)} : null;
      }
    },
    ROTATION3D(new String[] {"roll", "pitch", "yaw", "roll_deg", "pitch_deg", "yaw_deg"}) {
      @Override
      Object[] values(Map<String, Object> map) {
        if (map.get("q") instanceof Map<?, ?> q && q.get("w") instanceof Double w
            && q.get("x") instanceof Double x && q.get("y") instanceof Double y
            && q.get("z") instanceof Double z) {
          double roll = roll(w, x, y, z);
          double pitch = pitch(w, x, y, z);
          double yaw = yaw(w, x, y, z);
          return new Object[] {roll, pitch, yaw, Math.toDegrees(roll), Math.toDegrees(pitch),
              Math.toDegrees(yaw)};
        }
        return null;
      }
    };

    private final String[] keys;

    Enricher(String[] keys) {
      this.keys = keys;
    }

    /** The derived values, in {@link #keys()} order, or null when they cannot be computed. */
    abstract Object[] values(Map<String, Object> map);

    /** The derived values as a struct-like map (empty when they cannot be computed). */
    Map<String, Object> derive(Map<String, Object> map) {
      var values = values(map);
      return values != null ? new StructMap(keys, values) : Map.of();
    }

    List<String> keys() {
      return List.of(keys);
    }

    // WPILib Rotation3d.getX/getY/getZ, including their gimbal-lock handling
    static double roll(double w, double x, double y, double z) {
      double cxcy = 1.0 - 2.0 * (x * x + y * y);
      double sxcy = 2.0 * (w * x + y * z);
      double cySq = cxcy * cxcy + sxcy * sxcy;
      return cySq > 1e-20 ? Math.atan2(sxcy, cxcy) : 0.0;
    }

    static double pitch(double w, double x, double y, double z) {
      double ratio = 2.0 * (w * y - z * x);
      return Math.abs(ratio) >= 1.0 ? Math.copySign(Math.PI / 2.0, ratio) : Math.asin(ratio);
    }

    static double yaw(double w, double x, double y, double z) {
      double cycz = 1.0 - 2.0 * (y * y + z * z);
      double cysz = 2.0 * (w * z + x * y);
      double cySq = cycz * cycz + cysz * cysz;
      return cySq > 1e-20 ? Math.atan2(cysz, cycz) : Math.atan2(2.0 * w * z, w * w - z * z);
    }
  }
}
