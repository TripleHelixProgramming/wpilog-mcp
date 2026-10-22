/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.columns;

import static org.triplehelix.wpilogmcp.columns.ColumnFileFormat.MAX_DEPTH;
import static org.triplehelix.wpilogmcp.columns.ColumnFileFormat.MAX_ENTRY_COLUMNS;
import static org.triplehelix.wpilogmcp.columns.ColumnFileFormat.MAX_INDEX;
import static org.triplehelix.wpilogmcp.columns.ColumnFileFormat.MAX_PATH_CHARS;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.triplehelix.wpilogmcp.columns.ColumnFileFormat.Kind;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.read.FieldPath;

/**
 * How a decoded struct value's numeric leaves become columns. A finished log's column file and an
 * open capture's columns walk each record by these rules, so both hold the same leaves under the
 * same paths and a read gives the same samples from either.
 *
 * <p>A leaf's column is named by the path the field path grammar spells for it
 * ({@code .translation.x}, {@code [2].angle.value}), derived angles included. A column keeps the
 * kind of the first leaf at its path; a leaf of another kind later drops the column, since no one
 * kind holds both (a uint64 field decodes as an integer below 2^63 and as a double above). An
 * element at index 64 or beyond, a field name the grammar cannot address, a path longer than a
 * column's may be, a value nested deeper than a path may go, a number of a type no kind stores,
 * and a leaf past an entry's 4,096 columns get no column. Each of those leaves the entry
 * incomplete: its other columns still hold their paths exactly, but a pooled read ({@code [*]}),
 * which needs every element, is not answered from columns.
 */
public final class StructLeaves {
  private StructLeaves() {}

  /** One path in an entry's records; a subclass keeps the column of the leaves at it. */
  public abstract static class Node<N extends Node<N>> {
    final String path;
    final boolean indexed;
    final int depth;
    private Map<String, N> fields;
    private List<N> elements;
    /** The kind of the leaves at this path, from the first one; null until then. */
    Kind kind;
    /** Set once this path keeps no column: a leaf of another kind came, or the entry had no room. */
    boolean dropped;

    protected Node(String path, boolean indexed, int depth) {
      this.path = path;
      this.indexed = indexed;
      this.depth = depth;
    }

    /** A new node one step below this one. */
    protected abstract N child(String path, boolean indexed, int depth);

    /** The path, as the field path grammar spells it. */
    public String path() { return path; }

    /** The kind of the leaves at this path, or null before the first. */
    public Kind kind() { return kind; }

    /** The node of a field, or null when its path would be longer than a column's may be. */
    N field(String name) {
      if (path.length() + 1 + name.length() > MAX_PATH_CHARS) return null;
      if (fields == null) fields = new HashMap<>();
      return fields.computeIfAbsent(name, n -> child(path + "." + n, indexed, depth + 1));
    }

    N element(int index) {
      if (path.length() + 12 > MAX_PATH_CHARS) return null;
      if (elements == null) elements = new ArrayList<>();
      while (elements.size() <= index) elements.add(child(path + "[" + elements.size() + "]", true, depth + 1));
      return elements.get(index);
    }
  }

  /** What a walk does with the leaves it finds; the walk keeps each node's kind and drop. */
  public interface Leaves<N extends Node<N>> {
    /** How many columns the entry has begun, dropped ones included. */
    int columns();

    /** A path's first leaf: its column begins, of the node's kind. */
    void begin(N node);

    /** A leaf's stored bits, at a path whose column holds its kind. */
    void value(N node, long bits);

    /** A leaf of another kind came to a path with a column: the column is dropped. */
    void drop(N node);

    /** Something no column holds: the entry is no longer complete. */
    void incomplete();
  }

  /** Walks one record's decoded value from its entry's root node. */
  public static <N extends Node<N>> void walk(N node, Object value, Leaves<N> leaves) {
    if (value == null) return;
    if (value instanceof Map<?, ?> fields) {
      if (node.depth >= MAX_DEPTH) { leaves.incomplete(); return; }
      for (var field : fields.entrySet()) {
        var child = field.getKey() instanceof String name && addressable(name) ? node.field(name) : null;
        if (child != null) walk(child, field.getValue(), leaves);
        else leaves.incomplete();
      }
    } else if (value instanceof List<?> elements) {
      if (node.depth >= MAX_DEPTH) { leaves.incomplete(); return; }
      if (elements.size() > MAX_INDEX) leaves.incomplete();
      for (int i = 0; i < Math.min(elements.size(), MAX_INDEX); i++) {
        var child = node.element(i);
        if (child != null) walk(child, elements.get(i), leaves);
        else leaves.incomplete();
      }
    } else {
      leaf(node, value, leaves);
    }
  }

  private static <N extends Node<N>> void leaf(N node, Object value, Leaves<N> leaves) {
    var kind = ColumnFileFormat.kindOf(value);
    if (kind == null) {
      // A number of another type, or an array whose elements a path could still address.
      if (FieldPath.toNumber(value) != null || value.getClass().isArray() && !(value instanceof byte[])) leaves.incomplete();
      return;
    }
    if (node.dropped) return;
    if (node.kind == null) {
      if (leaves.columns() == MAX_ENTRY_COLUMNS) {
        node.dropped = true;
        leaves.incomplete();
        return;
      }
      node.kind = kind;
      leaves.begin(node);
    } else if (node.kind != kind) {
      node.dropped = true;
      leaves.drop(node);
      leaves.incomplete();
      return;
    }
    leaves.value(node, ColumnFileFormat.bitsOf(value));
  }

  /**
   * The largest record of a struct array entry whose elements all fit the columns, by its
   * schema's size: a wider record has an element at index 64 or beyond, and an entry with one is
   * not held in columns. Zero for an entry that is not a struct array, or whose schema gives no
   * size.
   */
  public static long widestRecord(StructSchemas schemas, String type) {
    var struct = StructSchemas.structName(type);
    if (struct == null || !StructSchemas.isArrayType(type)) return 0;
    return schemas.info(struct).filter(s -> s.valid() && s.size() > 0).map(s -> (long) s.size() * MAX_INDEX).orElse(0L);
  }

  /**
   * The columns a pooled path matches, in the order {@code FieldPath.resolveAll} visits their
   * elements within a record: by the indexes at the pattern's {@code [*]} steps.
   */
  public static <C> List<C> pooled(Collection<C> columns, Function<C, FieldPath> path, FieldPath pattern) {
    var steps = pattern.steps();
    record Ranked<C>(C column, int[] rank) {}
    var matches = new ArrayList<Ranked<C>>();
    for (var column : columns) {
      var concrete = path.apply(column).steps();
      if (concrete.size() != steps.size()) continue;
      var rank = new ArrayList<Integer>();
      boolean match = true;
      for (int i = 0; i < steps.size() && match; i++) {
        var step = steps.get(i);
        if (step instanceof FieldPath.All) {
          if (concrete.get(i) instanceof FieldPath.Index index) rank.add(index.index());
          else match = false;
        } else {
          match = step.equals(concrete.get(i));
        }
      }
      if (match) matches.add(new Ranked<>(column, rank.stream().mapToInt(Integer::intValue).toArray()));
    }
    matches.sort((a, b) -> Arrays.compare(a.rank, b.rank));
    return matches.stream().map(Ranked::column).toList();
  }

  /** A name the grammar reads back as one field step. */
  private static boolean addressable(String name) {
    return !name.isEmpty() && name.indexOf('.') < 0 && name.indexOf('[') < 0;
  }
}
