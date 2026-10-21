/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.columns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.triplehelix.wpilogmcp.columns.ColumnFileFormat.Kind;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.read.AngleUnwrapper;
import org.triplehelix.wpilogmcp.read.FieldPath;
import org.triplehelix.wpilogmcp.tools.Buckets;
import org.triplehelix.wpilogmcp.tools.EntryData;
import org.triplehelix.wpilogmcp.tools.NumericSignalAccess;

/**
 * Compares a column file with the reads the tools make from the log itself: every sample's
 * microsecond, seconds and value bits, the record counts the tools report, every pooled path,
 * and windows and overview buckets as the data endpoint computes them. The answers come from the
 * server's own resolver, window filter and bucketing, which read the decoded log.
 */
final class ColumnOracle {
  private ColumnOracle() {}

  static final int[] POINTS = {1, 2, 3, 7, 1000};

  /** What one comparison covered, so a sweep can show it did not pass by comparing nothing. */
  static final class Counts {
    int entries, columns, structColumns, pooled, samples, windows, buckets;
    final Set<Kind> kinds = EnumSet.noneOf(Kind.class);

    void add(Counts other) {
      entries += other.entries;
      columns += other.columns;
      structColumns += other.structColumns;
      pooled += other.pooled;
      samples += other.samples;
      windows += other.windows;
      buckets += other.buckets;
      kinds.addAll(other.kinds);
    }

    @Override
    public String toString() {
      return entries + " entries, " + columns + " columns (" + structColumns + " in structs), " + pooled
          + " pooled paths, " + samples + " samples, " + windows + " windows, " + buckets + " overviews, kinds " + kinds;
    }
  }

  /** Every entry the file should hold, and every path it holds, against the log. */
  static Counts compare(LogData log, ColumnFile file, String where) {
    var counts = new Counts();
    for (var info : log.entries().values()) {
      var label = where + " " + info.name();
      var held = file.entry(info.name());
      if (!ColumnFileWriter.covered(info.type())) {
        assertTrue(held.isEmpty(), label + ": a " + info.type() + " entry is not held");
        continue;
      }
      var values = log.values().get(info.name());
      if (values == null) values = List.of();
      boolean struct = StructSchemas.structName(info.type()) != null;
      if (!struct) assertEquals(!values.isEmpty(), held.isPresent(), label + ": a number with values is held");
      if (held.isEmpty()) {
        boolean wide = values.stream().anyMatch(v -> v.value() instanceof List<?> l && l.size() > ColumnFileFormat.MAX_INDEX);
        assertTrue(wide || leafPaths(log, info, values).isEmpty(),
            label + ": a struct with numeric leaves and no array past the limit is held");
        continue;
      }
      var entry = held.get();
      counts.entries++;
      assertEquals(log.sampleCount(info.name()), entry.records(), label + ": records");
      assertEquals(values.size(), entry.decoded(), label + ": decoded records");
      assertEquals(log.decodeProblem(info.name()), entry.decodeProblem(), label + ": decode problem");
      if (struct) {
        var missing = new TreeSet<>(leafPaths(log, info, values));
        missing.removeAll(entry.paths());
        if (entry.complete()) assertTrue(missing.isEmpty(), label + ": leaves without a column " + missing);
      } else {
        assertRawValues(values, file, info, label);
      }
      for (var path : entry.paths()) {
        compareSelection(log, file, info, FieldPath.parse(path), counts, label + path);
        counts.columns++;
        if (struct) counts.structColumns++;
      }
      for (var pattern : pooledPatterns(entry.paths())) {
        var path = FieldPath.parse(pattern);
        if (!entry.complete()) {
          assertTrue(file.select(info.name(), path).isEmpty(), label + pattern + ": pooled from an incomplete entry");
          continue;
        }
        compareSelection(log, file, info, path, counts, label + pattern);
        counts.pooled++;
      }
    }
    return counts;
  }

  /** One path: its samples, counts, windows and buckets. */
  static void compareSelection(LogData log, ColumnFile file, EntryInfo info, FieldPath path, Counts counts, String where) {
    var resolved = NumericSignalAccess.resolve(log, info.name(), path.isRoot() ? null : path.toString());
    var selection = file.select(info.name(), path).orElseThrow(() -> new AssertionError(where + ": not held"));
    var expected = resolved.values();
    assertEquals(expected.size(), selection.size(), where + ": samples");
    assertEquals(resolved.recordCount(), selection.entry().decoded(), where + ": records");
    assertEquals(resolved.recordsWithoutValue(), selection.recordsWithoutValue(), where + ": records without a value");
    selection.kind().ifPresent(counts.kinds::add);
    assertSameSamples(expected, selection, selection.all(), where);
    counts.samples += expected.size();
    var series = new EntryData.Series(info.name(), info.name(), path.toString(), info.type(), expected, true);
    for (var window : windows(expected)) {
      var label = where + " " + window;
      var kept = series.inWindow(window.start(), window.end());
      var range = selection.window(window.start(), window.end());
      assertSameSamples(kept, selection, range, label);
      counts.windows++;
      for (int points : POINTS) {
        assertSameBuckets(kept, window, points, selection, range, false, label);
        counts.buckets++;
      }
      assertSameBuckets(kept, window, 7, selection, range, true, label + " unwrapped");
      counts.buckets++;
    }
  }

  static void assertSameSamples(List<TimestampedValue> expected, ColumnFile.Selection selection,
      ColumnFile.Range range, String where) {
    assertEquals(expected.size(), range.size(), where + ": samples in range");
    var cursor = selection.cursor(range);
    for (int i = 0; i < expected.size(); i++) {
      assertTrue(cursor.next(), where + ": sample " + i);
      var value = expected.get(i);
      assertEquals(value.timestampUs(), cursor.timeUs(), where + ": microseconds of sample " + i);
      assertEquals(Double.doubleToRawLongBits(value.timestamp()), Double.doubleToRawLongBits(cursor.seconds()),
          where + ": seconds of sample " + i);
      assertEquals(Double.doubleToRawLongBits(((Number) value.value()).doubleValue()),
          Double.doubleToRawLongBits(cursor.number()), where + ": value of sample " + i);
    }
    assertFalse(cursor.next(), where + ": samples beyond the range");
  }

  /**
   * The endpoint's buckets of the window's values against the overview of its range; with
   * {@code unwrap}, both after unwrapping the values as an angle in radians across the window.
   */
  static void assertSameBuckets(List<TimestampedValue> kept, Window window, int points,
      ColumnFile.Selection selection, ColumnFile.Range range, boolean unwrap, String where) {
    var values = kept;
    if (unwrap) {
      var unwrapper = new AngleUnwrapper(2 * Math.PI);
      values = kept.stream().map(v -> v.withValue(unwrapper.apply(((Number) v.value()).doubleValue()))).toList();
    }
    var label = where + " at " + points + " points";
    Buckets.Result expected;
    try {
      expected = Buckets.of(values, window.start(), window.end(), points);
    } catch (IllegalArgumentException refused) {
      var thrown = assertThrows(IllegalArgumentException.class, () -> ColumnOverview.of(selection, range,
          window.start(), window.end(), points, unwrap ? new AngleUnwrapper(2 * Math.PI)::apply : null), label);
      assertEquals(refused.getMessage(), thrown.getMessage(), label);
      return;
    }
    var actual = ColumnOverview.of(selection, range, window.start(), window.end(), points,
        unwrap ? new AngleUnwrapper(2 * Math.PI)::apply : null);
    assertEquals(Double.doubleToRawLongBits(expected.bucketSec()), Double.doubleToRawLongBits(actual.bucketSec()), label + ": bucket duration");
    assertEquals(expected.buckets().size(), actual.buckets().size(), label + ": buckets");
    for (int i = 0; i < expected.buckets().size(); i++) {
      var e = expected.buckets().get(i);
      var a = actual.buckets().get(i);
      var at = label + " bucket " + i;
      assertEquals(expected.startUs(i), a.startUs(), at + ": start");
      assertEquals(Double.doubleToRawLongBits(e.start()), Double.doubleToRawLongBits(a.startUs() / 1e6), at + ": start seconds");
      assertEquals(e.count(), a.count(), at + ": count");
      assertSameNumber(e.min(), a.min(), at + ": min");
      assertSameNumber(e.max(), a.max(), at + ": max");
      assertSameNumber(e.mean(), a.mean(), at + ": mean");
      assertEquals(Double.doubleToRawLongBits(e.first()), Double.doubleToRawLongBits(a.first()), at + ": first");
      assertEquals(Double.doubleToRawLongBits(e.last()), Double.doubleToRawLongBits(a.last()), at + ": last");
    }
  }

  private static void assertSameNumber(Double expected, Double actual, String where) {
    if (expected == null || actual == null) assertEquals(expected, actual, where);
    else assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual), where);
  }

  /** A number entry's column against its decoded objects: the kind that holds each, and its bits. */
  private static void assertRawValues(List<TimestampedValue> values, ColumnFile file, EntryInfo info, String where) {
    var selection = file.select(info.name(), FieldPath.ROOT).orElseThrow(() -> new AssertionError(where + ": not held"));
    var cursor = selection.cursor(selection.all());
    for (int i = 0; i < values.size(); i++) {
      assertTrue(cursor.next(), where + ": sample " + i);
      var value = values.get(i).value();
      var at = where + ": raw sample " + i;
      Kind kind;
      long bits;
      if (value instanceof Double d) {
        kind = Kind.F64;
        bits = Double.doubleToRawLongBits(d);
      } else if (value instanceof Float f) {
        kind = Kind.F32;
        bits = Integer.toUnsignedLong(Float.floatToRawIntBits(f));
      } else if (value instanceof Long l) {
        kind = Kind.I64;
        bits = l;
      } else if (value instanceof Boolean b) {
        kind = Kind.BOOL;
        bits = b ? 1 : 0;
      } else {
        throw new AssertionError(at + ": decoded as " + value.getClass().getName());
      }
      assertEquals(kind, cursor.kind(), at);
      assertEquals(bits, cursor.bits(), at);
    }
  }

  /**
   * The concrete numeric leaves today's grammar reaches in a struct entry's records, listed by
   * the field path code the metrics endpoint uses (derived angles are not declared fields, so
   * they are compared through the resolver instead).
   */
  static Set<String> leafPaths(LogData log, EntryInfo info, List<TimestampedValue> values) {
    var schemas = log.structSchemas();
    var struct = StructSchemas.structName(info.type());
    var paths = new LinkedHashSet<String>();
    if (struct == null || schemas.info(struct).isEmpty()) return paths;
    for (var value : values) {
      if (value.value() instanceof Map<?, ?>) {
        for (var leaf : FieldPath.numericLeaves(schemas, struct, value.value(), ColumnFileFormat.MAX_INDEX))
          paths.add("." + leaf.field());
      } else if (value.value() instanceof List<?> elements) {
        for (int i = 0; i < Math.min(elements.size(), ColumnFileFormat.MAX_INDEX); i++) {
          for (var leaf : FieldPath.numericLeaves(schemas, struct, elements.get(i), ColumnFileFormat.MAX_INDEX))
            paths.add("[" + i + "]." + leaf.field());
        }
      }
    }
    return paths;
  }

  /** Every way of replacing some of a held path's indexes with {@code [*]}. */
  static Set<String> pooledPatterns(Set<String> paths) {
    var patterns = new TreeSet<String>();
    for (var text : paths) {
      var steps = FieldPath.parse(text).steps();
      var indexes = new ArrayList<Integer>();
      for (int i = 0; i < steps.size(); i++) if (steps.get(i) instanceof FieldPath.Index) indexes.add(i);
      if (indexes.size() > 4) indexes = new ArrayList<>(indexes.subList(0, 4));
      for (int mask = 1; mask < 1 << indexes.size(); mask++) {
        var pattern = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
          int bit = indexes.indexOf(i);
          pattern.append(bit >= 0 && (mask & 1 << bit) != 0 ? "[*]" : steps.get(i).toString());
        }
        patterns.add(pattern.toString());
      }
    }
    return patterns;
  }

  /** A window as the data endpoint takes one: seconds, inclusive, either end open. */
  record Window(Double start, Double end) {
    @Override
    public String toString() {
      return "[" + start + ", " + end + "]";
    }
  }

  /**
   * Windows around a series' own samples: open ends, bounds on samples, between samples, before
   * and after the data, one instant, reversed, and bounds a fraction of a microsecond off.
   */
  static List<Window> windows(List<TimestampedValue> values) {
    var windows = new ArrayList<Window>();
    windows.add(new Window(null, null));
    if (values.isEmpty()) {
      windows.add(new Window(0.0, 1.0));
      return windows;
    }
    double first = values.get(0).timestamp(), last = values.get(values.size() - 1).timestamp();
    double middle = values.get(values.size() / 2).timestamp();
    double next = values.get(Math.min(values.size() - 1, values.size() / 2 + 1)).timestamp();
    windows.add(new Window(first, last));
    windows.add(new Window(middle, null));
    windows.add(new Window(null, middle));
    windows.add(new Window(middle, middle));
    windows.add(new Window((middle + next) / 2, last));
    windows.add(new Window(first - 1, first - 0.5));
    windows.add(new Window(last + 1, last + 2));
    windows.add(new Window(last, first));
    windows.add(new Window(first + 4e-7, last - 6e-7));
    windows.add(new Window(middle - 5e-7, middle + 5e-7));
    return windows;
  }
}
