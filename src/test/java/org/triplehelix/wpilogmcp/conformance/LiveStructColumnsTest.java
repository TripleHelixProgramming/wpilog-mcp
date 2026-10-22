/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.triplehelix.wpilogmcp.capture.WpilogOutput;
import org.triplehelix.wpilogmcp.columns.ColumnFile;
import org.triplehelix.wpilogmcp.columns.ColumnFileWriter;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LiveLog;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.OpenColumns;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.read.FieldPath;
import org.triplehelix.wpilogmcp.read.LegacyMaterializer;

/**
 * An open capture answers a struct field from its columns as a decode of the same prefix does: at
 * sampled publication boundaries, for every numeric leaf and pooled path of every struct entry,
 * the samples, their times and values bit for bit, the records that decoded and those without the
 * leaf, and the entry's decode problem. Each entry is a case where the columns must stop, start
 * again or step aside: malformed records, arrays of changing length, values before their schema,
 * a nested schema recorded late, one recorded late with another layout of the same size, a WPILib
 * schema recorded after its values, an array wider than columns take, an unsigned field crossing
 * 2^63 alone and inside an array, more records before a schema than the writer decodes again for
 * it, and time running backwards. Each view is read again after later records and schemas, as a
 * call holding it would, and one is held from early on to the end. The pose's numbers are checked against the values written, a fresh scan of a copy of
 * the prefix answers alike, and the finished file's column file holds the same leaves.
 */
class LiveStructColumnsTest {
  @TempDir Path directory;

  private static final long START_US = 1_000_000;
  private static final long STEP_US = 20_000;
  private static final int STEPS = 1600;
  private static final Set<Integer> COMPARED = Set.of(1, 7, 150, 299, 300, 301, 433, 499, 500, 501, 699, 700, 866,
      899, 900, 1099, 1100, 1199, 1200, 1201, 1299, 1300, 1301, STEPS);
  private static final Set<Integer> SCANNED = Set.of(300, 700, 1300, STEPS);

  /** A struct path's numbers as a decode projects them: in time order, with the records that held none. */
  private record Projection(List<TimestampedValue> values, int without) {}

  /** Which paths the columns answered, by entry, path and step, for the cases that must hold. */
  private final Map<String, Set<Integer>> answered = new HashMap<>();

  /** A view held while more is recorded, and what it answered when it was taken. */
  private record Held(LogManager.LogUse use, Map<String, Optional<Projection>> answers,
      Map<String, Optional<org.triplehelix.wpilogmcp.log.DecodeProblem>> problems) {}

  /** The previous step's view, and one taken early and read again at the end. */
  private Held held;
  private Held kept;
  private static final int KEPT_FROM = 150;

  private WpilogOutput out;
  private LiveLog live;

  @ParameterizedTest(name = "blocks in a file: {0}, hot window {1} us")
  @CsvSource({"true, 0", "false, 0", "true, 600000000"})
  void everyFieldOfEveryPrefixAnswersAsADecodeOfIt(boolean inFile, long hotWindowUs) throws Exception {
    var path = directory.resolve("capture.wpilog");
    var columnsFile = directory.resolve("columns-open.col");
    OpenColumns.Placement placement = inFile ? new OpenColumns.Placement() {
      @Override public Optional<Path> file(String captureIdentity) { return Optional.of(columnsFile); }
      @Override public boolean reserveShort() { return false; }
    } : OpenColumns.Placement.HEAP;
    var manager = org.triplehelix.wpilogmcp.log.IsolatedLogManagers.create(directory.toRealPath(), "live-struct-columns");
    out = new WpilogOutput(path);
    live = new LiveLog(path, hotWindowUs, () -> null, UUID.randomUUID().toString(), placement);
    manager.beginCapture(live);
    int compared = 0;
    try (var writer = out) {
      var schemas = new LinkedHashMap<String, EntryInfo>();
      for (var name : List.of("Translation2d", "Rotation2d", "Pose2d", "SwerveModuleState", "Gadget", "Translation3d",
          "Counter", "Outer", "Inner", "Late", "Spin", "Quaternion")) {
        schemas.put(name, declare("NT:/.schema/struct:" + name, "structschema"));
      }
      var pose = declare("/pose", "struct:Pose2d");
      var modules = declare("/modules", "struct:SwerveModuleState[]");
      var gadget = declare("/gadget", "struct:Gadget");
      var spot = declare("/spot", "struct:Translation3d");
      var targets = declare("/targets", "struct:Translation2d[]");
      var counter = declare("/counter", "struct:Counter");
      var outer = declare("/outer", "struct:Outer");
      // More records before Late's schema, together, than the writer decodes again for one schema.
      var lagging = new ArrayList<EntryInfo>();
      for (int k = 1; k <= 4; k++) lagging.add(declare("/lagging" + k, "struct:Late"));
      var backwards = declare("/backwards", "struct:Translation2d");
      var spin = declare("/spin", "struct:Spin");
      var counters = declare("/counters", "struct:Counter[]");
      for (int step = 1; step <= STEPS; step++) {
        long time = START_US + step * STEP_US;
        if (step == 1) {
          // WPILib's own schemas, nested ones first as WPILib publishes them; Outer before its Inner.
          schema(schemas.get("Translation2d"), time, "double x;double y");
          schema(schemas.get("Rotation2d"), time, "double value");
          schema(schemas.get("Pose2d"), time, "Translation2d translation;Rotation2d rotation");
          schema(schemas.get("SwerveModuleState"), time, "double speed;Rotation2d angle");
          schema(schemas.get("Counter"), time, "uint64 n;double d");
          schema(schemas.get("Outer"), time, "Inner inner;double z");
          // WPILib's Quaternion until the log records another layout of the same size.
          schema(schemas.get("Spin"), time, "Quaternion q;double t");
        }
        if (step == 300) schema(schemas.get("Gadget"), time, "double a;int32 b;bool c;float f;enum{off=0,on=1,auto=2} int8 mode");
        if (step == 500) schema(schemas.get("Translation3d"), time, "double x;double y;double z");
        if (step == 700) schema(schemas.get("Inner"), time, "double k");
        if (step == 1200) schema(schemas.get("Late"), time, "double v");
        if (step == 800) schema(schemas.get("Quaternion"), time, "double a;double b;double c;double d");

        // Malformed records of two sizes, so the first failure's reason differs from the last's.
        write(pose, time, step % 397 == 0 ? new byte[step % 2 == 0 ? 10 : 12] : doubles(step * 0.5, -step * 0.25, step * 0.001));
        var states = new double[2 * (step % 7)];
        for (int i = 0; i < step % 7; i++) {
          states[2 * i] = step + i * 0.125;
          states[2 * i + 1] = i * 0.5 + step * 1e-3;
        }
        write(modules, time, doubles(states));
        // Every kind a leaf can be: a double, an integer, a boolean, a float and an enum.
        write(gadget, time, ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN).putDouble(step * 2.0).putInt(-step)
            .put((byte) (step % 3 == 0 ? 1 : 0)).putFloat(step / 3.0f).put((byte) (step % 3)).array());
        write(spot, time, doubles(step, step + 0.5, -step));
        if (step % 2 == 0) {
          var corners = new double[2 * (step == 1100 ? 70 : 3)];
          for (int i = 0; i < corners.length; i++) corners[i] = step + i;
          write(targets, time, doubles(corners));
        }
        long n = step < 1300 ? step : Long.MIN_VALUE + step;
        write(counter, time, ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(n).putDouble(step / 4.0).array());
        write(outer, time, doubles(step * 3.0, step * -3.0));
        for (var late : lagging) write(late, time, doubles(step * 7.0));
        write(backwards, step == 900 ? time - 5 * STEP_US : time, doubles(step, -step));
        write(spin, time, doubles(1, step, step + 1, step + 2, step / 8.0));
        long second = step < 1400 ? 2L * step : Long.MIN_VALUE + step;
        write(counters, time, ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(step).putDouble(-step).putLong(second).putDouble(step * 1.5).array());
        live.advance(time + 1);
        if (step % 13 == 0) live.expire();
        if (COMPARED.contains(step)) {
          // With no hot window, expiring first leaves every record of the prefix cold.
          if (hotWindowUs == 0) live.expire();
          compared += compare(manager, path, step);
        }
      }
      held = release(held);
      kept = release(kept);
      assertFinishedFileHoldsTheSameLeaves(manager, path);
    } finally {
      if (held != null) held.use().close();
      if (kept != null) kept.use().close();
      live.finish(); manager.shutdown(); live.discard();
    }
    assertTrue(compared > 100_000, "samples compared: " + compared);
    assertTrue(!Files.exists(columnsFile), "a discarded index deletes its columns' file");

    // Each case answered from columns where its records allow, and from the decode where not.
    assertAnswered("/pose .translation.x", COMPARED);
    assertAnswered("/pose .rotation._derived.degrees", COMPARED);
    assertAnswered("/modules [*].speed", COMPARED);
    assertAnswered("/modules [5].angle.value", steps(6, STEPS));
    assertAnswered("/gadget .a", steps(300, STEPS));
    assertAnswered("/gadget .c", steps(300, STEPS));
    assertAnswered("/gadget .f", steps(300, STEPS));
    assertAnswered("/gadget .mode", steps(300, STEPS));
    assertAnswered("/spot .z", COMPARED);
    assertAnswered("/targets [2].y", steps(2, 1099));
    assertAnswered("/targets [*].x", steps(2, 1099));
    assertAnswered("/counter .n", steps(1, 1299));
    assertAnswered("/counter .d", COMPARED);
    assertAnswered("/outer .inner.k", steps(700, STEPS));
    // The first three take 3,597 of the 4,096 records; the fourth's 1,199 do not fit.
    for (int k = 1; k <= 3; k++) assertAnswered("/lagging" + k + " .v", steps(1200, STEPS));
    assertAnswered("/lagging4 .v", Set.of());
    assertAnswered("/backwards .x", steps(1, 899));
    assertAnswered("/spin .q.w", steps(1, 799));
    assertAnswered("/spin .q.a", steps(800, STEPS));
    assertAnswered("/spin .t", COMPARED);
    assertAnswered("/counters [1].n", steps(1, 1399));
    assertAnswered("/counters [0].n", COMPARED);
    assertAnswered("/counters [*].n", steps(1, 1399));
    assertAnswered("/counters [*].d", steps(1, 1399));
  }

  private static Set<Integer> steps(int from, int to) {
    var steps = new TreeSet<Integer>();
    for (int step : COMPARED) if (step >= from && step <= to) steps.add(step);
    return steps;
  }

  private void assertAnswered(String key, Set<Integer> expected) {
    assertEquals(new TreeSet<>(expected), new TreeSet<>(answered.getOrDefault(key, Set.of())), key + ": the steps answered from columns");
  }

  private EntryInfo declare(String name, String type) throws Exception {
    var info = new EntryInfo(out.start(name, type, "", 0), name, type, "");
    live.announce(info);
    return info;
  }

  private void schema(EntryInfo info, long time, String text) throws Exception {
    write(info, time, text.getBytes(StandardCharsets.UTF_8));
  }

  private void write(EntryInfo info, long time, byte[] payload) throws Exception {
    var written = out.append(info.id(), time, payload);
    live.append(info, new ValueFrame(info.id(), time, 5, payload), written);
  }

  private static byte[] doubles(double... values) {
    var buffer = ByteBuffer.allocate(8 * values.length).order(ByteOrder.LITTLE_ENDIAN);
    for (double value : values) buffer.putDouble(value);
    return buffer.array();
  }

  /**
   * A held view, read again after everything recorded since: it answers each path and each
   * decode problem as it did, from the records and schemas of its own prefix.
   */
  private static Held release(Held held) {
    if (held == null) return null;
    var view = (LiveLog.View) held.use().log();
    for (var answer : held.answers().entrySet()) {
      int space = answer.getKey().indexOf(' ');
      var column = view.numericColumn(answer.getKey().substring(0, space), FieldPath.parse(answer.getKey().substring(space + 1)));
      assertEquals(answer.getValue(), column.map(LiveStructColumnsTest::projection), answer.getKey() + ": a held view, read again");
    }
    for (var problem : held.problems().entrySet()) {
      assertEquals(problem.getValue(), view.decodeProblem(problem.getKey()), problem.getKey() + ": a held view's decode problem");
    }
    held.use().close();
    return null;
  }

  private static Projection projection(org.triplehelix.wpilogmcp.log.NumericColumn column) {
    var values = new ArrayList<TimestampedValue>();
    var samples = column.samples();
    for (int i = 0; i < samples.size(); i++) values.add(TimestampedValue.atMicros(samples.timeUs(i), samples.number(i)));
    return new Projection(values, column.recordsWithoutValue());
  }

  /** One prefix: every path of every struct entry, against the same view's decode and, at some steps, a scan of a copy. */
  private int compare(LogManager manager, Path path, int step) throws Exception {
    held = release(held);
    int compared = 0;
    var use = manager.acquire(path.toString());
    var taken = new Held(use, new HashMap<>(), new HashMap<>());
    if (step == KEPT_FROM) kept = taken;
    else held = taken;
    {
      var view = (LiveLog.View) use.log();
      LogData scanned = null;
      Path copy = null;
      LogManager.LogUse scan = null;
      if (SCANNED.contains(step)) {
        copy = directory.resolve("prefix-" + step + ".wpilog");
        Files.write(copy, Files.readAllBytes(path));
        scan = manager.acquire(copy.toString());
        scanned = scan.log();
      }
      try {
        for (var entry : view.entries().values()) {
          if (!entry.type().startsWith("struct:")) continue;
          var name = entry.name();
          var where = name + " at step " + step;
          var decode = LegacyMaterializer.read(name, view.records(name, false));
          assertEquals(Optional.ofNullable(decode.problem()), view.decodeProblem(name), where + ": decode problem");
          taken.problems().put(name, Optional.ofNullable(decode.problem()));
          var values = decode.values();
          if (scanned != null) {
            assertEquals(values.size(), scanned.values().get(name).size(), where + ": a scan's decoded records");
            assertEquals(scanned.decodeProblem(name), view.decodeProblem(name), where + ": a scan's decode problem");
          }
          for (var text : paths(values, entry.type().endsWith("[]"))) {
            var field = FieldPath.parse(text);
            var expected = project(values, field);
            if (scanned != null) assertEquals(expected, project(scanned.values().get(name), field), where + " " + text + ": a scan");
            var column = view.numericColumn(name, field);
            taken.answers().put(name + " " + text, column.map(LiveStructColumnsTest::projection));
            if (column.isEmpty()) continue;
            answered.computeIfAbsent(name + " " + text, key -> new TreeSet<>()).add(step);
            var held = column.get();
            assertEquals(values.size(), held.decoded(), where + " " + text + ": records decoded");
            assertEquals(expected.without(), held.recordsWithoutValue(), where + " " + text + ": records without the leaf");
            var samples = held.samples();
            assertEquals(expected.values().size(), samples.size(), where + " " + text + ": samples");
            for (int i = 0; i < samples.size(); i++) {
              var value = expected.values().get(i);
              assertEquals(value.timestampUs(), samples.timeUs(i), where + " " + text + " sample " + i + ": time");
              assertEquals(Double.doubleToRawLongBits(value.timestamp()), Double.doubleToRawLongBits(samples.seconds(i)),
                  where + " " + text + " sample " + i + ": seconds");
              assertEquals(Double.doubleToRawLongBits(((Number) value.value()).doubleValue()), Double.doubleToRawLongBits(samples.number(i)),
                  where + " " + text + " sample " + i + ": value");
              compared++;
            }
            if (name.equals("/pose") && text.equals(".translation.x")) {
              // The numbers written: x is half the step, and no malformed record holds one.
              for (int i = 0; i < samples.size(); i++) {
                long written = (samples.timeUs(i) - START_US) / STEP_US;
                assertTrue(written % 397 != 0, where + ": a malformed record has no sample");
                assertEquals(written * 0.5, samples.number(i), where + " sample " + i);
              }
            }
          }
        }
      } finally {
        if (scan != null) scan.close();
        if (copy != null) {
          manager.unloadLog(copy.toString());
          Files.delete(copy);
        }
      }
    }
    return compared;
  }

  /** Every numeric leaf path the decoded values hold, and for an array entry each leaf pooled across elements. */
  private static Set<String> paths(List<TimestampedValue> values, boolean array) {
    var paths = new TreeSet<String>();
    for (var value : values) leaves("", value.value(), paths);
    if (array) {
      for (var text : List.copyOf(paths)) paths.add("[*]" + text.substring(text.indexOf(']') + 1));
    }
    return paths;
  }

  private static void leaves(String path, Object value, Set<String> paths) {
    if (value instanceof Map<?, ?> fields) fields.forEach((key, field) -> leaves(path + "." + key, field, paths));
    else if (value instanceof List<?> elements) for (int i = 0; i < elements.size(); i++) leaves(path + "[" + i + "]", elements.get(i), paths);
    else if (FieldPath.toNumber(value) != null) paths.add(path);
  }

  /** A path's numbers as {@code NumericSignal} projects decoded values. */
  private static Projection project(List<TimestampedValue> values, FieldPath path) {
    var numbers = new ArrayList<TimestampedValue>();
    int without = 0;
    for (var value : values) {
      int before = numbers.size();
      if (path.hasWildcard()) {
        for (var leaf : path.resolveAll(value.value())) {
          var number = FieldPath.toNumber(leaf);
          if (number != null) numbers.add(value.withValue(number));
        }
      } else {
        var number = FieldPath.toNumber(path.resolveOne(value.value()));
        if (number != null) numbers.add(value.withValue(number));
      }
      if (numbers.size() == before) without++;
    }
    return new Projection(numbers, without);
  }

  /**
   * The finished file's column file, built from the WPILOG, holds each leaf the recording's
   * columns answer, with the same samples: one set of rules decomposes both.
   */
  private void assertFinishedFileHoldsTheSameLeaves(LogManager manager, Path path) throws Exception {
    var copy = directory.resolve("finished.wpilog");
    Files.write(copy, Files.readAllBytes(path));
    var file = directory.resolve("finished.columns");
    int checked = 0;
    try (var use = manager.acquire(path.toString()); var finished = manager.acquire(copy.toString())) {
      var view = (LiveLog.View) use.log();
      ColumnFileWriter.write(finished.log(), "test:finished", file);
      try (var columns = ColumnFile.open(file, "test:finished")) {
        for (var entry : view.entries().values()) {
          if (!entry.type().startsWith("struct:")) continue;
          for (var text : paths(view.values().get(entry.name()), entry.type().endsWith("[]"))) {
            var field = FieldPath.parse(text);
            var recorded = view.numericColumn(entry.name(), field);
            var built = columns.select(entry.name(), field);
            if (recorded.isEmpty() || built.isEmpty()) continue;
            var a = recorded.get().samples();
            var b = built.get().samples(built.get().all());
            var where = entry.name() + " " + text + " in the finished file";
            assertEquals(b.size(), a.size(), where);
            assertEquals(built.get().recordsWithoutValue(), recorded.get().recordsWithoutValue(), where);
            for (int i = 0; i < a.size(); i++) {
              assertEquals(b.timeUs(i), a.timeUs(i), where + " sample " + i);
              assertEquals(Double.doubleToRawLongBits(b.number(i)), Double.doubleToRawLongBits(a.number(i)), where + " sample " + i);
            }
            checked++;
          }
        }
        // The file holds no column the recording dropped, and no entry wider than columns take.
        assertFalse(columns.select("/counter", FieldPath.parse(".n")).isPresent(), "a leaf whose kind changed");
        assertFalse(columns.select("/targets", FieldPath.parse("[0].x")).isPresent(), "an array wider than 64 elements");
      }
    } finally {
      manager.unloadLog(copy.toString());
    }
    assertTrue(checked > 20, "leaves checked against the finished file: " + checked);
  }
}
