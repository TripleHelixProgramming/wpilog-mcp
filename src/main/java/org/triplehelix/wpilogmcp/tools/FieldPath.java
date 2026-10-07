/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.triplehelix.wpilogmcp.log.struct.EnumValue;

/**
 * A path into a decoded value: struct fields ({@code .translation.x}), array elements
 * ({@code [2]}), and every element of an array ({@code [*]}). Paths address struct values
 * (nested maps), struct arrays (lists), fixed-size struct fields (lists), and numeric array
 * entries ({@code double[]} and friends).
 *
 * <p>Grammar: steps separated by dots, each a field name optionally followed by indexes;
 * a path may start with an index ({@code [*].tagCount}) or a dot ({@code .translation.x}).
 *
 * @since 0.9.0
 */
public final class FieldPath {

  /** Numeric leaves with the tools' field-path spelling and enum/boolean conversions.
   * Each array dimension is bounded; derived rotations are not declared schema fields. */
  public record NumericLeaf(String field, double value) {}
  public static List<NumericLeaf> numericLeaves(org.triplehelix.wpilogmcp.log.struct.StructSchemas schemas,
      String struct, Object decoded, int arrayLimit) {
    var result = new ArrayList<NumericLeaf>();
    numericLeaves(schemas, struct, decoded, decoded, "", arrayLimit, result);
    return List.copyOf(result);
  }
  private static void numericLeaves(org.triplehelix.wpilogmcp.log.struct.StructSchemas schemas,
      String struct, Object root, Object value, String prefix, int limit, List<NumericLeaf> out) {
    var info = schemas.info(struct).orElseThrow();
    for (var field : info.fields()) {
      String path = prefix + field.name();
      Object child = value instanceof Map<?, ?> fields ? fields.get(field.name()) : null;
      int count = field.isArray() ? Math.min(limit, length(child)) : 1;
      for (int i = 0; i < count; i++) {
        String leaf = field.isArray() ? path + "[" + i + "]" : path;
        Object item = field.isArray() ? element(child, i) : child;
        if (field.structType() != null) numericLeaves(schemas, field.structType(), root, item, leaf + ".", limit, out);
        else {
          var number = toNumber(parse(leaf).resolveOne(root));
          if (number != null) out.add(new NumericLeaf(leaf, number));
        }
      }
    }
  }

  /** One step of a path. */
  sealed interface Step permits Field, Index, All {}

  /** A struct field by name. */
  record Field(String name) implements Step {
    @Override
    public String toString() {
      return "." + name;
    }
  }

  /** One array element, zero-based. */
  record Index(int index) implements Step {
    @Override
    public String toString() {
      return "[" + index + "]";
    }
  }

  /** Every element of an array. */
  record All() implements Step {
    @Override
    public String toString() {
      return "[*]";
    }
  }

  private final List<Step> steps;

  private FieldPath(List<Step> steps) {
    this.steps = List.copyOf(steps);
  }

  /** The empty path: the value itself. */
  static final FieldPath ROOT = new FieldPath(List.of());

  /**
   * Parses a path.
   *
   * @throws IllegalArgumentException for malformed paths (unclosed brackets, bad indexes,
   *     empty field names)
   */
  static FieldPath parse(String text) {
    if (text == null || text.isEmpty()) return ROOT;
    var steps = new ArrayList<Step>();
    int i = 0;
    int n = text.length();
    if (text.charAt(0) == '.') i = 1;
    while (i < n) {
      char c = text.charAt(i);
      if (c == '[') {
        int close = text.indexOf(']', i);
        if (close < 0) throw new IllegalArgumentException("Unclosed '[' in field path: " + text);
        var inside = text.substring(i + 1, close).strip();
        if (inside.equals("*")) {
          steps.add(new All());
        } else {
          try {
            int index = Integer.parseInt(inside);
            if (index < 0) throw new NumberFormatException();
            steps.add(new Index(index));
          } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Array index must be a non-negative integer or *, "
                + "got [" + inside + "] in field path: " + text);
          }
        }
        i = close + 1;
        if (i < n && text.charAt(i) == '.') {
          i++;
          if (i == n) throw new IllegalArgumentException("Field path ends with '.': " + text);
        }
      } else {
        int end = i;
        while (end < n && text.charAt(end) != '.' && text.charAt(end) != '[') end++;
        if (end == i) throw new IllegalArgumentException("Empty field name in field path: " + text);
        steps.add(new Field(text.substring(i, end)));
        i = end;
        if (i < n && text.charAt(i) == '.') {
          i++;
          if (i == n) throw new IllegalArgumentException("Field path ends with '.': " + text);
        }
      }
    }
    return new FieldPath(steps);
  }

  List<Step> steps() {
    return steps;
  }

  boolean isRoot() {
    return steps.isEmpty();
  }

  /** Whether the path selects every element of some array, so one value yields several leaves. */
  boolean hasWildcard() {
    return steps.stream().anyMatch(s -> s instanceof All);
  }

  /** The leaf at this path, or null when the path does not exist in the value. */
  Object resolveOne(Object value) {
    Object current = value;
    for (var step : steps) {
      if (current == null) return null;
      if (step instanceof Field f) {
        current = current instanceof Map<?, ?> map ? map.get(f.name()) : null;
      } else if (step instanceof Index ix) {
        current = element(current, ix.index());
      } else {
        throw new IllegalStateException("resolveOne on a wildcard path: " + this);
      }
    }
    return current;
  }

  /** Every leaf at this path (several for a wildcard); missing branches contribute nothing. */
  List<Object> resolveAll(Object value) {
    var out = new ArrayList<Object>();
    collect(value, 0, out);
    return out;
  }

  private void collect(Object current, int depth, List<Object> out) {
    if (current == null) return;
    if (depth == steps.size()) {
      out.add(current);
      return;
    }
    var step = steps.get(depth);
    if (step instanceof Field f) {
      if (current instanceof Map<?, ?> map) collect(map.get(f.name()), depth + 1, out);
    } else if (step instanceof Index ix) {
      collect(element(current, ix.index()), depth + 1, out);
    } else {
      int size = length(current);
      for (int i = 0; i < size; i++) collect(element(current, i), depth + 1, out);
    }
  }

  /** Array or list element, or null when out of range or not an array. */
  static Object element(Object value, int index) {
    if (value instanceof List<?> list) return index < list.size() ? list.get(index) : null;
    if (value != null && value.getClass().isArray() && !(value instanceof byte[])) {
      return index < Array.getLength(value) ? Array.get(value, index) : null;
    }
    return null;
  }

  /** Array or list length; 0 for anything else. */
  static int length(Object value) {
    if (value instanceof List<?> list) return list.size();
    if (value != null && value.getClass().isArray() && !(value instanceof byte[])) {
      return Array.getLength(value);
    }
    return 0;
  }

  /**
   * A leaf as a number: numbers as themselves, booleans as 1 or 0, enum fields as their stored
   * value; null for anything else (maps, strings, missing).
   */
  static Double toNumber(Object leaf) {
    if (leaf instanceof Number n) return n.doubleValue();
    if (leaf instanceof Boolean b) return b ? 1.0 : 0.0;
    if (leaf instanceof EnumValue e) return (double) e.value();
    return null;
  }

  @Override
  public String toString() {
    var sb = new StringBuilder();
    for (var step : steps) sb.append(step);
    return sb.toString();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof FieldPath other && other.steps.equals(steps);
  }

  @Override
  public int hashCode() {
    return steps.hashCode();
  }
}
