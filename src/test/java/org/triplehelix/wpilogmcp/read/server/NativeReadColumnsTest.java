/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.triplehelix.wpilogmcp.fixtures.WpiStructs.POSE2D;
import static org.triplehelix.wpilogmcp.fixtures.WpiStructs.SWERVE_MODULE_STATE;
import static org.triplehelix.wpilogmcp.fixtures.WpiStructs.pose2d;
import static org.triplehelix.wpilogmcp.fixtures.WpiStructs.swerveModuleState;

import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.columns.ColumnFile;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.fixtures.FixtureWriter;
import org.triplehelix.wpilogmcp.log.ColumnFixtures;
import org.triplehelix.wpilogmcp.log.IsolatedLogManagers;
import org.triplehelix.wpilogmcp.log.LazyParsedLog;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.read.FieldPath;
import org.triplehelix.wpilogmcp.read.ReadQuery;
import org.triplehelix.wpilogmcp.read.ReadQuery.LimitAction;
import org.triplehelix.wpilogmcp.read.ReadQuery.Order;
import org.triplehelix.wpilogmcp.read.ReadQuery.Window;
import org.triplehelix.wpilogmcp.read.Sample;
import org.triplehelix.wpilogmcp.read.SampleBatch;
import org.triplehelix.wpilogmcp.read.SampleCursor;

/**
 * A native read served from a column file is the read of the log's own records: every batch,
 * sample, record fact, problem and count, to the bit, over every fixture and a log of edge cases,
 * in time and file order, with windows, small batches, first indexes, record limits and
 * reductions. A read the file cannot answer exactly reads the log.
 *
 * <p>The edge log is read every way on every path. A fixture's entry is read every way on its
 * first held path and in time order on up to seven more, spread across its paths: batches,
 * indexes and limits follow the entry's records whichever leaf is read, and every leaf's values
 * are compared with the decode's by the column file's own tests. A read the file does not serve,
 * which reads the log either way, is not repeated.
 */
class NativeReadColumnsTest {
  @TempDir Path directory;
  private LogManager manager;
  private Path edges;

  private record Selected(String entry, String field) {
    @Override public String toString() { return entry + (field == null ? "" : field); }
  }

  @BeforeEach
  void setup() throws Exception {
    directory = directory.toRealPath();
    edges = directory.resolve("edges.wpilog");
    writeEdges(edges);
    manager = IsolatedLogManagers.create(directory, "native-columns");
  }

  @AfterEach
  void stop() {
    manager.shutdown();
  }

  private static void writeEdges(Path path) throws IOException {
    double nan = Double.longBitsToDouble(0x7ff8_0000_0000_0123L);
    float floatNan = Float.intBitsToFloat(0x7fc0_0123);
    try (var w = new FixtureWriter(path, "")) {
      double[] doubles = {0.5, -0.0, nan, Double.MAX_VALUE, Double.MIN_VALUE, 1e-300, 7.25};
      for (int i = 0; i < doubles.length; i++) w.dbl("/d", 1.0 + i / 10.0, doubles[i]);
      w.dbl("/d", 1.6, 9.5);
      float[] floats = {0.25f, -0.0f, floatNan, Float.MAX_VALUE, 3.5f};
      for (int i = 0; i < floats.length; i++) w.flt("/f", 1.0 + i / 10.0, floats[i]);
      long[] longs = {9_007_199_254_740_993L, Long.MIN_VALUE, Long.MAX_VALUE, -1, 0};
      for (int i = 0; i < longs.length; i++) w.i64("/i", 1.0 + i / 10.0, longs[i]);
      for (int i = 0; i < 6; i++) w.bool("/b", 1.0 + i / 10.0, i % 3 == 0);
      // Out of time order, with equal times: an index is not a position.
      w.dbl("/back", 2.0, 20).dbl("/back", 1.0, 10).dbl("/back", 1.0, 11).dbl("/back", 3.0, 30)
          .dbl("/back", 0.5, 5);
      for (int i = 0; i < 12; i++) w.struct("/pose", POSE2D, 1.0 + i / 10.0, pose2d(i, -i, i / 4.0));
      w.struct("/late", POSE2D, 3.0, pose2d(3, 0, 0)).struct("/late", POSE2D, 1.0, pose2d(1, 0, 0))
          .struct("/late", POSE2D, 2.0, pose2d(2, 0, 0)).struct("/late", POSE2D, 2.0, pose2d(2.5, 0, 0));
      // Two modules in every record but the fifth, which has one, so payload sizes vary.
      for (int i = 0; i < 9; i++) {
        var states = i == 4 ? new byte[][] {swerveModuleState(i, 0.1)}
            : new byte[][] {swerveModuleState(i, 0.1 * i), swerveModuleState(100 + i, -0.1 * i)};
        w.structArr("/gap", SWERVE_MODULE_STATE, 1.0 + i / 10.0, states);
      }
      w.struct("/bad", POSE2D, 1.0, pose2d(1, 1, 1)).raw("/bad", "struct:Pose2d", 2.0, new byte[5])
          .struct("/bad", POSE2D, 3.0, pose2d(3, 3, 3));
      // Every uint64 value below 2^63, so its column holds Longs while native reads give BigIntegers.
      w.schema("Mixed", "uint64 big;enum {idle=0, run=1} int8 mode;bool on;float gain;double hist[3]", 1.0);
      for (int i = 0; i < 5; i++) {
        w.raw("/u", "struct:Mixed", 1.0 + i / 10.0, mixed(5 + i, i % 2, i % 2 == 0, 0.25f * i, i, 2 * i, 3 * i));
      }
      for (int i = 0; i < 4; i++) w.dblArr("/arr", 1.0 + i / 10.0, i, i + 1);
      for (int i = 0; i < 3; i++) w.str("/s", 1.0 + i / 10.0, "v" + i);
    }
  }

  private static byte[] mixed(long big, int mode, boolean on, float gain, double... hist) {
    var b = ByteBuffer.allocate(38).order(ByteOrder.LITTLE_ENDIAN).putLong(big).put((byte) mode)
        .put((byte) (on ? 1 : 0)).putFloat(gain);
    for (var h : hist) b.putDouble(h);
    return b.array();
  }

  /** A value as plain data that tells every bit: doubles and floats by their bits. */
  private static Object key(Object value) {
    if (value == null) return "null";
    if (value instanceof Double d) return "D" + Long.toHexString(Double.doubleToRawLongBits(d));
    if (value instanceof Float f) return "F" + Integer.toHexString(Float.floatToRawIntBits(f));
    if (value instanceof Map<?, ?> map) {
      var out = new LinkedHashMap<Object, Object>();
      map.forEach((k, v) -> out.put(k, key(v)));
      return out;
    }
    if (value instanceof List<?> list) return list.stream().map(NativeReadColumnsTest::key).toList();
    if (value.getClass().isArray()) {
      var out = new ArrayList<Object>();
      for (int i = 0; i < Array.getLength(value); i++) out.add(key(Array.get(value, i)));
      return out;
    }
    return value.getClass().getSimpleName() + ":" + value;
  }

  private static boolean sameValue(Object a, Object b) {
    if (a instanceof Double x && b instanceof Double y) return Double.doubleToRawLongBits(x) == Double.doubleToRawLongBits(y);
    if (a instanceof Float x && b instanceof Float y) return Float.floatToRawIntBits(x) == Float.floatToRawIntBits(y);
    if (a == null || b == null || a.getClass() != b.getClass()) return a == b;
    if (a instanceof Map || a instanceof List || a.getClass().isArray()) return key(a).equals(key(b));
    return a.equals(b);
  }

  private static boolean sameSample(Sample a, Sample b) {
    return a.timestampUs() == b.timestampUs() && a.recordOffset() == b.recordOffset() && a.ordinal() == b.ordinal()
        && a.payloadBytes() == b.payloadBytes() && Objects.equals(a.source(), b.source())
        && sameValue(a.value(), b.value());
  }

  /** A batch as plain data, for a difference shown in full. */
  private static List<Object> data(SampleBatch batch) {
    return Arrays.asList(batch.samples().stream().map(s -> Arrays.asList(s.timestampUs(), s.recordOffset(),
            s.ordinal(), key(s.value()), s.payloadBytes(), String.valueOf(s.source()))).toList(),
        batch.problems().stream().map(p -> Arrays.asList(p.code(), p.entry(), p.recordOffset(), p.message())).toList(),
        batch.recordsVisited(), batch.payloadBytes(), batch.complete());
  }

  /** One read, a batch at a time; a failure is how it ended. */
  private static final class Reading implements AutoCloseable {
    private SampleCursor cursor;
    private List<String> failure;
    boolean ended;

    Reading(ManagedReadSnapshot snapshot, ReadQuery query) {
      try {
        cursor = snapshot.read(query);
      } catch (IOException | RuntimeException refused) {
        failure = failed(refused);
      }
    }

    private static List<String> failed(Exception e) {
      return Arrays.asList("failed", e.getClass().getName(), String.valueOf(e.getMessage()));
    }

    /** The next batch, or the failure that ends the read. */
    Object next() {
      if (failure == null) {
        try {
          var batch = cursor.next();
          ended = batch.complete();
          return batch;
        } catch (IOException | RuntimeException refused) {
          failure = failed(refused);
        }
      }
      ended = true;
      return failure;
    }

    @Override
    public void close() throws IOException {
      if (cursor != null) cursor.close();
    }
  }

  /** Reads a query from both snapshots in step and compares each pair of batches. */
  private static void assertSameReads(ManagedReadSnapshot fromLog, ManagedReadSnapshot fromFile, ReadQuery query,
      String name) throws IOException {
    try (var want = new Reading(fromLog, query); var got = new Reading(fromFile, query)) {
      for (int batch = 0; batch < 100_000; batch++) {
        var expected = want.next();
        var actual = got.next();
        if (expected instanceof SampleBatch a && actual instanceof SampleBatch b) {
          boolean same = a.samples().size() == b.samples().size() && a.recordsVisited() == b.recordsVisited()
              && a.payloadBytes() == b.payloadBytes() && a.complete() == b.complete()
              && a.problems().equals(b.problems());
          for (int i = 0; same && i < a.samples().size(); i++) same = sameSample(a.samples().get(i), b.samples().get(i));
          if (!same) assertEquals(data(a), data(b), name + ", batch " + batch);
        } else {
          assertEquals(expected instanceof SampleBatch a ? data(a) : expected,
              actual instanceof SampleBatch b ? data(b) : actual, name + ", batch " + batch);
        }
        if (want.ended || got.ended) {
          assertEquals(want.ended, got.ended, name + ": one read ended first, at batch " + batch);
          return;
        }
      }
      fail(name + " did not end");
    }
  }

  /** The record times of a selection, read in time order. */
  private static long[] times(ManagedReadSnapshot snapshot, Selected selected) throws IOException {
    var times = new ArrayList<Long>();
    try (var cursor = snapshot.read(new ReadQuery(selected.entry(), List.of(), Order.TIME, 4096, 4 << 20,
        selected.field(), null))) {
      while (true) {
        var batch = cursor.next();
        batch.samples().forEach(s -> times.add(s.timestampUs()));
        if (batch.complete()) break;
      }
    }
    return times.stream().mapToLong(Long::longValue).toArray();
  }

  /** The reads compared for one selection: orders, windows, batch limits, indexes, limits, reductions. */
  private static List<ReadQuery> queries(Selected selected, long[] t) {
    var entry = selected.entry();
    var field = selected.field();
    var all = new ReadQuery(entry, List.of(), Order.TIME, 4096, 4 << 20, field, null);
    var file = new ReadQuery(entry, List.of(), Order.FILE, 4096, 4 << 20, field, null);
    var out = new ArrayList<ReadQuery>(List.of(all, file, all.reduce(3)));
    if (t.length == 0) return out;
    long first = t[0], last = t[t.length - 1], a = t[t.length / 3], b = t[2 * t.length / 3];
    var split = a < b ? List.of(new Window(first, a, false), new Window(b, last, true))
        : List.of(new Window(first, last, true));
    out.add(new ReadQuery(entry, List.of(), Order.TIME, 3, 4 << 20, field, null));
    out.add(new ReadQuery(entry, List.of(), Order.TIME, 4096, 40, field, null));
    out.add(new ReadQuery(entry, split, Order.TIME, 4096, 4 << 20, field, null));
    out.add(new ReadQuery(entry, List.of(new Window(first, b, false)), Order.TIME, 2, 4 << 20, field, null));
    out.add(new ReadQuery(entry, List.of(new Window(a, b, true)), Order.TIME, 4096, 4 << 20, field, null));
    out.add(new ReadQuery(entry, List.of(new Window(last + 1, last + 2, true)), Order.TIME, 4096, 4 << 20, field, null));
    out.add(new ReadQuery(entry, split, Order.FILE, 2, 4 << 20, field, null));
    out.add(all.fromOrdinal(t.length / 2));
    out.add(file.fromOrdinal(t.length / 2));
    out.add(new ReadQuery(entry, split, Order.TIME, 4096, 4 << 20, field, null).reduce(1000));
    out.add(all.withRecordLimit(20, LimitAction.PROBLEM));
    out.add(all.withRecordLimit(1, LimitAction.PROBLEM));
    out.add(all.withRecordLimit(1, LimitAction.ERROR));
    return out;
  }

  /**
   * The paths the column file holds, each entry's whole record, and pooled forms of indexed
   * paths; at most {@code perEntry} held paths of an entry, spread across them.
   */
  private List<Selected> selections(Path log, Path columns, int perEntry) throws IOException {
    String source;
    try (var use = manager.acquire(log.toString())) {
      source = "sha256:" + ((LazyParsedLog) use.log()).contentSha256().orElseThrow();
    }
    var out = new ArrayList<Selected>();
    try (var file = ColumnFile.open(columns, source); var use = manager.acquire(log.toString())) {
      for (var name : use.log().entries().keySet()) {
        out.add(new Selected(name, null));
        var held = file.entry(name);
        if (held.isEmpty()) continue;
        var paths = held.get().paths().stream().filter(path -> !path.isEmpty()).sorted().toList();
        int step = Math.max(1, (paths.size() + perEntry - 1) / perEntry);
        for (int i = 0; i < paths.size(); i += step) {
          var path = paths.get(i);
          out.add(new Selected(name, path));
          if (path.contains("[0]")) out.add(new Selected(name, path.replaceFirst("\\[0]", "[*]")));
        }
      }
    }
    return out;
  }

  /**
   * Compares one log's native reads with and without its column file, every way on every path
   * when {@code everyWay} is set; returns how many reads the file served. The log is loaded with
   * its column file and then again without, while the first load holds its file open.
   */
  private int compare(String id, Path log, List<Selected> extra, boolean everyWay) throws IOException {
    var columns = ColumnFixtures.build(manager, log);
    var selected = new ArrayList<>(selections(log, columns, everyWay ? Integer.MAX_VALUE : 8));
    selected.addAll(extra);
    int served = 0;
    try {
      manager.testReadColumnFiles(true);
      try (var fromFile = manager.readAccess().open(log)) {
        manager.testReadColumnFiles(false);
        try (var fromLog = manager.readAccess().open(log)) {
          assertNotSame(fromFile.log(), fromLog.log(), "two loads of " + id);
          var shaped = new HashSet<String>();
          for (var selection : selected) {
            var all = new ReadQuery(selection.entry(), List.of(), Order.TIME, 4096, 4 << 20, selection.field(), null);
            if (fromFile.held(all) == null && !everyWay) continue;
            var queries = everyWay || shaped.add(selection.entry()) ? queries(selection, times(fromLog, selection)) : List.of(all);
            for (var query : queries) {
              var name = id + " " + selection + " (" + query + ")";
              assertNull(fromLog.held(query), name + ": held with no column file");
              if (fromFile.held(query) != null) served++;
              else if (!everyWay) continue;
              assertSameReads(fromLog, fromFile, query, name);
            }
          }
        }
      }
    } finally {
      manager.testReadColumnFiles(false);
    }
    return served;
  }

  @Test
  void everyReadAnswersFromTheColumnFileAsFromTheLog() throws Exception {
    var fixtures = FixtureLogs.generateAll(FixtureLogs.defaultDirectory());
    manager.addAllowedDirectory(FixtureLogs.defaultDirectory().toRealPath());
    int served = compare("edges", edges, List.of(new Selected("/arr", "[1]"),
        new Selected("/pose", ".translation"), new Selected("/d", ".x")), true);
    assertTrue(served >= 150, "edge reads served from the column file: " + served);
    int fixtureServed = 0;
    for (var fixture : fixtures) fixtureServed += compare(fixture.id(), fixture.path().toRealPath(), List.of(), false);
    assertTrue(fixtureServed >= 1000, "fixture reads served from the column file: " + fixtureServed);
  }

  @Test
  void theColumnFileAnswersExactlyTheReadsItHolds() throws Exception {
    var columns = ColumnFixtures.build(manager, edges);
    String source;
    try (var use = manager.acquire(edges.toString())) {
      source = "sha256:" + ((LazyParsedLog) use.log()).contentSha256().orElseThrow();
    }
    try (var file = ColumnFile.open(columns, source)) {
      assertTrue(file.records("/u", FieldPath.parse(".big")).isPresent(),
          "the file holds the uint64 column, so only the recorded type can refuse it");
      var back = file.records("/back", FieldPath.ROOT).orElseThrow();
      assertFalse(back.inFileOrder(), "records moved into time order keep their indexes");
      assertTrue(file.records("/d", FieldPath.ROOT).orElseThrow().inFileOrder());
    }
    manager.testReadColumnFiles(true);
    try (var snapshot = manager.readAccess().open(edges)) {
      for (var held : List.of(new Selected("/d", null), new Selected("/f", null), new Selected("/i", null),
          new Selected("/b", null), new Selected("/back", null), new Selected("/pose", ".translation.x"),
          new Selected("/pose", ".rotation._derived.degrees"), new Selected("/late", ".translation.y"),
          new Selected("/gap", "[0].speed"), new Selected("/gap", "[0].angle.value"),
          new Selected("/u", ".on"), new Selected("/u", ".gain"), new Selected("/u", ".hist[2]"))) {
        var query = new ReadQuery(held.entry(), List.of(), Order.TIME, 4096, 4 << 20, held.field(), null);
        assertTrue(snapshot.held(query) != null, held + " is held");
      }
      var refused = List.of(new Selected("/gap", "[1].speed"), new Selected("/gap", "[*].speed"),
          new Selected("/bad", ".translation.x"), new Selected("/u", ".big"), new Selected("/u", ".mode"),
          new Selected("/arr", "[0]"), new Selected("/pose", null), new Selected("/pose", ".translation"),
          new Selected("/s", null), new Selected("/d", ".x"));
      for (var read : refused) {
        var query = new ReadQuery(read.entry(), List.of(), Order.TIME, 4096, 4 << 20, read.field(), null);
        assertNull(snapshot.held(query), read + " is read from the log");
      }
      // A read by record index needs indexes that are positions.
      var d = new ReadQuery("/d", List.of(), Order.FILE, 4096, 4 << 20);
      assertTrue(snapshot.held(d) != null && snapshot.held(d.fromOrdinal(3)) != null);
      var back = new ReadQuery("/back", List.of(), Order.TIME, 4096, 4 << 20);
      assertTrue(snapshot.held(back) != null);
      assertNull(snapshot.held(back.fromOrdinal(1)));
      assertNull(snapshot.held(new ReadQuery("/back", List.of(), Order.FILE, 4096, 4 << 20)));
      assertNull(snapshot.held(new ReadQuery("/late", List.of(), Order.FILE, 4096, 4 << 20, ".translation.x", null)));
    } finally {
      manager.testReadColumnFiles(false);
    }
  }
}
