/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.columns;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.triplehelix.wpilogmcp.fixtures.WpiStructs.POSE2D;
import static org.triplehelix.wpilogmcp.fixtures.WpiStructs.SWERVE_MODULE_STATE;
import static org.triplehelix.wpilogmcp.fixtures.WpiStructs.pose2d;
import static org.triplehelix.wpilogmcp.fixtures.WpiStructs.swerveModuleState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.columns.ColumnFileFormat.Kind;
import org.triplehelix.wpilogmcp.fixtures.FixtureWriter;
import org.triplehelix.wpilogmcp.fixtures.WpiStructs;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.MappedLogBytes;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.read.FieldPath;

/**
 * Column files of a log written for their edge cases, with each answer worked out by hand, and
 * the file itself damaged, crafted, truncated and read concurrently.
 */
class ColumnFileTest {
  private static final String SOURCE = "test:edges";
  private static final long PAYLOAD_NAN = 0x7FF8_0000_0000_0123L;
  private static final int FLOAT_PAYLOAD_NAN = 0x7FC0_0123;
  private static final double LARGE = 1e308;

  @TempDir static Path dir;
  private static Path log;
  private static Path built;
  /** The log manager's grants before this class added its own, restored after it. */
  private static java.util.Set<Path> allowed;

  @BeforeAll
  static void writeLog() throws IOException {
    log = dir.resolve("edges.wpilog");
    try (var w = new FixtureWriter(log, "")) {
      // Out of time order, with two records at 2.0 s that must stay in file order.
      w.raw("/d", "double", 3.0, le(8).putLong(PAYLOAD_NAN).array())
          .dbl("/d", 1.0, -0.0).dbl("/d", 2.0, Double.POSITIVE_INFINITY).dbl("/d", 2.0, 5.5)
          .dbl("/d", 4.0, Double.NEGATIVE_INFINITY).dbl("/d", 0.5, LARGE);
      w.raw("/f", "float", 1.0, le(4).putInt(FLOAT_PAYLOAD_NAN).array()).flt("/f", 2.0, 1.5f).flt("/f", 3.0, -0.0f);
      w.i64("/i", 1.0, Long.MIN_VALUE).i64("/i", 2.0, Long.MAX_VALUE).i64("/i", 3.0, (1L << 53) + 1).i64("/i", 4.0, 0);
      w.bool("/b", 1.0, true).bool("/b", 2.0, false).bool("/b", 3.0, true);
      w.declare("/empty", "double", 1.0);
      w.dbl("/one", 1.0, 42.0);
      // Arrays of 0, 2, 4 and 2 modules; the 2- and 4-module records share 2.0 s.
      w.structArr("/s", SWERVE_MODULE_STATE, 1.0)
          .structArr("/s", SWERVE_MODULE_STATE, 2.0, swerveModuleState(1, 0.1), swerveModuleState(2, 0.2))
          .structArr("/s", SWERVE_MODULE_STATE, 2.0, swerveModuleState(3, 0.3), swerveModuleState(4, 0.4),
              swerveModuleState(5, 0.5), swerveModuleState(6, 0.6))
          .structArr("/s", SWERVE_MODULE_STATE, 3.0, swerveModuleState(7, 0.7), swerveModuleState(8, 0.8));
      w.structArr("/big", SWERVE_MODULE_STATE, 1.0, modules(70));
      w.structArr("/edge64", SWERVE_MODULE_STATE, 1.0, modules(64));
      var wide = le(70 * 8);
      for (int i = 0; i < 70; i++) wide.putDouble(i + 0.5);
      w.schema("Wide", "double many[70]", 1.0).raw("/wide", "struct:Wide", 1.0, wide.array());
      // Out of time order: the 3- and 1-module records share 1.0 s and follow the 2.0 s record.
      w.structArr("/s2", SWERVE_MODULE_STATE, 2.0, swerveModuleState(10, 0), swerveModuleState(11, 0))
          .structArr("/s2", SWERVE_MODULE_STATE, 1.0, swerveModuleState(20, 0), swerveModuleState(21, 0),
              swerveModuleState(22, 0))
          .structArr("/s2", SWERVE_MODULE_STATE, 1.0, swerveModuleState(30, 0));
      // Two modules in every record but the eleventh, which has one.
      for (int i = 0; i < 20; i++) {
        var states = i == 10 ? new byte[][] {swerveModuleState(i, 0)}
            : new byte[][] {swerveModuleState(i, 0), swerveModuleState(100 + i, 0)};
        w.structArr("/gap", SWERVE_MODULE_STATE, 1.0 + i / 10.0, states);
      }
      w.struct("/late", POSE2D, 3.0, pose2d(3, 0, 0)).struct("/late", POSE2D, 1.0, pose2d(1, 0, 0))
          .struct("/late", POSE2D, 2.0, pose2d(2, 0, 0)).struct("/late", POSE2D, 2.0, pose2d(2.5, 0, 0));
      w.schema("Mixed", "uint64 big;enum {idle=0, run=1} int8 mode;bool on;float gain;double hist[3]", 1.0)
          .raw("/u", "struct:Mixed", 1.0, mixed(5, 1, true, 0.25f, 1, 2, 3))
          .raw("/u", "struct:Mixed", 2.0, mixed(0x8000_0000_0000_0005L, 0, false, Float.NaN, 4, 5, 6));
      w.struct("/pose", POSE2D, 1.0, pose2d(1, 2, 0.5)).struct("/pose", POSE2D, 2.0, pose2d(3, 4, -3.0));
      w.struct("/bad", POSE2D, 1.0, pose2d(1, 1, 1)).raw("/bad", "struct:Pose2d", 2.0, new byte[5])
          .struct("/bad", POSE2D, 3.0, pose2d(3, 3, 3));
      w.raw("/unknown", "struct:Nope", 1.0, new byte[] {1, 2, 3});
    }
    allowed = LogManager.getInstance().getAllowedDirectories();
    LogManager.getInstance().addAllowedDirectory(dir);
    built = dir.resolve("edges.columns");
    try (var use = LogManager.getInstance().acquire(log.toString())) {
      ColumnFileWriter.write(use.log(), SOURCE, built);
    }
  }

  /** An empty grant list allows every path, which tests that never add a grant rely on. */
  @AfterAll
  static void unload() {
    var manager = LogManager.getInstance();
    manager.unloadLog(log.toString());
    manager.clearAllowedDirectories();
    allowed.forEach(manager::addAllowedDirectory);
  }

  private static ByteBuffer le(int size) {
    return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
  }

  /** Module states whose speeds are 1, 2, 3, ... */
  private static byte[][] modules(int count) {
    var states = new byte[count][];
    for (int i = 0; i < count; i++) states[i] = swerveModuleState(i + 1, 0);
    return states;
  }

  private static byte[] mixed(long big, int mode, boolean on, float gain, double... hist) {
    var b = le(38).putLong(big).put((byte) mode).put((byte) (on ? 1 : 0)).putFloat(gain);
    for (var h : hist) b.putDouble(h);
    return b.array();
  }

  private static <T> T withLog(java.util.function.Function<LogData, T> read) throws IOException {
    try (var use = LogManager.getInstance().acquire(log.toString())) {
      return read.apply(use.log());
    }
  }

  private static ColumnFile.Selection select(ColumnFile file, String entry, String path) {
    return file.select(entry, FieldPath.parse(path)).orElseThrow(() -> new AssertionError(entry + path + " is not held"));
  }

  private static List<Double> numbers(ColumnFile.Selection selection) {
    var out = new ArrayList<Double>();
    var cursor = selection.cursor(selection.all());
    while (cursor.next()) out.add(cursor.number());
    return out;
  }

  private static long[] micros(ColumnFile.Selection selection) {
    var out = new ArrayList<Long>();
    var cursor = selection.cursor(selection.all());
    while (cursor.next()) out.add(cursor.timeUs());
    return out.stream().mapToLong(Long::longValue).toArray();
  }

  @Test
  void everyColumnReadsAsTheLogDoes() throws IOException {
    try (var file = ColumnFile.open(built, SOURCE)) {
      var counts = withLog(l -> ColumnOracle.compare(l, file, "edges"));
      assertTrue(counts.kinds.containsAll(java.util.EnumSet.allOf(Kind.class)), "kinds compared: " + counts.kinds);
    }
  }

  @Test
  void numbersKeepTheirBitsAndEqualTimesKeepFileOrder() throws IOException {
    try (var file = ColumnFile.open(built, SOURCE)) {
      var d = select(file, "/d", "");
      assertArrayEquals(new long[] {500_000, 1_000_000, 2_000_000, 2_000_000, 3_000_000, 4_000_000}, micros(d));
      var bits = new ArrayList<Long>();
      var cursor = d.cursor(d.all());
      while (cursor.next()) bits.add(cursor.bits());
      assertEquals(List.of(Double.doubleToRawLongBits(LARGE), Double.doubleToRawLongBits(-0.0),
          Double.doubleToRawLongBits(Double.POSITIVE_INFINITY), Double.doubleToRawLongBits(5.5), PAYLOAD_NAN,
          Double.doubleToRawLongBits(Double.NEGATIVE_INFINITY)), bits);

      var f = select(file, "/f", "");
      assertEquals(Kind.F32, f.kind().orElseThrow());
      cursor = f.cursor(f.all());
      assertTrue(cursor.next());
      assertEquals(FLOAT_PAYLOAD_NAN, (int) cursor.bits());
      assertTrue(cursor.next());
      assertEquals(1.5, cursor.number());
      assertTrue(cursor.next());
      assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(cursor.number()));

      var i = select(file, "/i", "");
      cursor = i.cursor(i.all());
      var longs = new ArrayList<Long>();
      while (cursor.next()) longs.add(cursor.bits());
      assertEquals(List.of(Long.MIN_VALUE, Long.MAX_VALUE, (1L << 53) + 1, 0L), longs);
      assertEquals(List.of(1.0, 0.0, 1.0), numbers(select(file, "/b", "")));
      assertEquals(List.of(42.0), numbers(select(file, "/one", "")));
    }
  }

  @Test
  void pooledElementsComeBackRecordByRecord() throws IOException {
    try (var file = ColumnFile.open(built, SOURCE)) {
      var speeds = select(file, "/s", "[*].speed");
      assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0), numbers(speeds));
      assertEquals(1, speeds.recordsWithoutValue(), "the empty array has no speed");
      var third = select(file, "/s", "[2].speed");
      assertEquals(List.of(5.0), numbers(third));
      assertArrayEquals(new long[] {2_000_000}, micros(third));
      assertEquals(3, third.recordsWithoutValue());
      assertEquals(List.of(Math.toDegrees(0.1), Math.toDegrees(0.3), Math.toDegrees(0.7)),
          numbers(select(file, "/s", "[0].angle._derived.degrees")));
    }
  }

  @Test
  void whatTheFileCannotHoldExactlyIsLeftToTheLog() throws IOException {
    try (var file = ColumnFile.open(built, SOURCE)) {
      assertTrue(file.entry("/big").isEmpty(), "an array of 70 structs is left to the log before it is decoded");
      var edge = file.entry("/edge64").orElseThrow();
      assertTrue(edge.complete(), "64 elements are within the limit");
      assertEquals(List.of(64.0), numbers(select(file, "/edge64", "[63].speed")));
      var wide = file.entry("/wide").orElseThrow();
      assertFalse(wide.complete(), "elements past the limit inside a struct leave the entry incomplete");
      assertEquals(List.of(10.5), numbers(select(file, "/wide", ".many[10]")));
      assertEquals(List.of(63.5), numbers(select(file, "/wide", ".many[63]")));
      assertTrue(file.select("/wide", FieldPath.parse(".many[64]")).isEmpty());
      assertTrue(file.select("/wide", FieldPath.parse(".many[*]")).isEmpty());

      var mixed = file.entry("/u").orElseThrow();
      assertFalse(mixed.complete(), "a uint64 field decodes as an integer, then as a double");
      assertTrue(file.select("/u", FieldPath.parse(".big")).isEmpty());
      assertTrue(file.select("/u", FieldPath.parse(".hist[*]")).isEmpty());
      var mode = select(file, "/u", ".mode");
      assertEquals(Kind.ENUM, mode.kind().orElseThrow());
      assertEquals(List.of(1.0, 0.0), numbers(mode));
      assertEquals(Kind.BOOL, select(file, "/u", ".on").kind().orElseThrow());
      assertEquals(List.of(1.0, 0.0), numbers(select(file, "/u", ".on")));
      var gain = numbers(select(file, "/u", ".gain"));
      assertEquals(0.25, gain.get(0));
      assertTrue(Double.isNaN(gain.get(1)));
      assertEquals(List.of(2.0, 5.0), numbers(select(file, "/u", ".hist[1]")));

      assertTrue(file.entry("/empty").isEmpty(), "an entry with no values has nothing to hold");
      assertTrue(file.entry("/unknown").isEmpty(), "a struct without a schema has no numeric leaves");
      assertTrue(file.select("/pose", FieldPath.parse(".translation")).isEmpty(), "a struct is not a number");
      assertTrue(file.select("/nothing", FieldPath.ROOT).isEmpty());
    }
  }

  @Test
  void structsOutOfTimeOrderAreSortedAsTheLogSortsThem() throws IOException {
    try (var file = ColumnFile.open(built, SOURCE)) {
      assertEquals(List.of(20.0, 21.0, 22.0, 30.0, 10.0, 11.0), numbers(select(file, "/s2", "[*].speed")));
      var second = select(file, "/s2", "[1].speed");
      assertEquals(List.of(21.0, 11.0), numbers(second));
      assertArrayEquals(new long[] {1_000_000, 2_000_000}, micros(second));
      assertEquals(List.of(20.0, 30.0, 10.0), numbers(select(file, "/s2", "[0].speed")));
      assertEquals(List.of(1.0, 2.0, 2.5, 3.0), numbers(select(file, "/late", ".translation.x")));
    }
  }

  @Test
  void anElementMissingFromOneRecordKeepsTheOthersOnTheirRecords() throws IOException {
    try (var file = ColumnFile.open(built, SOURCE)) {
      var second = select(file, "/gap", "[1].speed");
      var expected = new ArrayList<Double>();
      var micros = new ArrayList<Long>();
      for (int i = 0; i < 20; i++) {
        if (i == 10) continue;
        expected.add(100.0 + i);
        micros.add(FixtureWriter.micros(1.0 + i / 10.0));
      }
      assertEquals(expected, numbers(second));
      assertArrayEquals(micros.stream().mapToLong(Long::longValue).toArray(), micros(second));
      assertEquals(1, second.recordsWithoutValue());
    }
  }

  @Test
  void anEntryOverTheSampleLimitIsLeftToTheLog() throws IOException {
    var target = dir.resolve("capped.columns");
    try (var use = LogManager.getInstance().acquire(log.toString())) {
      var lazy = (org.triplehelix.wpilogmcp.log.LazyParsedLog) use.log();
      ColumnFileWriter.write(lazy, entry -> lazy.records(entry, false), SOURCE, target, 5);
    }
    try (var file = ColumnFile.open(target, SOURCE)) {
      assertTrue(file.entry("/d").isEmpty(), "six samples");
      assertTrue(file.entry("/pose").isEmpty(), "two records of four leaves");
      assertEquals(List.of(42.0), numbers(select(file, "/one", "")));
      assertEquals(4, select(file, "/i", "").size());
    }
  }

  @Test
  void aRecordThatFailsToDecodeIsReportedAsTheLogReportsIt() throws IOException {
    try (var file = ColumnFile.open(built, SOURCE)) {
      var bad = file.entry("/bad").orElseThrow();
      assertEquals(3, bad.records());
      assertEquals(2, bad.decoded());
      var problem = bad.decodeProblem().orElseThrow();
      assertEquals(1, problem.failedRecords());
      assertEquals(3, problem.totalRecords());
      assertEquals(withLog(l -> l.decodeProblem("/bad")), bad.decodeProblem());
      assertEquals(List.of(1.0, 3.0), numbers(select(file, "/bad", ".translation.x")));
    }
  }

  /**
   * Two buckets over 0.5 s to 4.0 s split at 2.25 s: the first holds 1e308, -0.0, +inf and 5.5,
   * whose finite minimum is -0.0 (seen before any equal value), maximum 1e308, and mean
   * (1e308 + -0.0 + 5.5) / 3; the second holds only NaN and -inf.
   */
  @Test
  void anOverviewMatchesBucketsWorkedOutByHand() throws IOException {
    try (var file = ColumnFile.open(built, SOURCE)) {
      var d = select(file, "/d", "");
      var overview = ColumnOverview.of(d, d.window(0.5, 4.0), 0.5, 4.0, 2, null);
      assertEquals(1.75, overview.bucketSec());
      assertEquals(2, overview.buckets().size());
      var first = overview.buckets().get(0);
      assertEquals(500_000, first.startUs());
      assertEquals(4, first.count());
      assertEquals(3, first.finite());
      assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(first.min()));
      assertEquals(LARGE, first.max());
      assertEquals((LARGE + -0.0 + 5.5) / 3, first.mean());
      assertEquals(LARGE, first.first());
      assertEquals(5.5, first.last());
      var second = overview.buckets().get(1);
      assertEquals(2_250_000, second.startUs());
      assertEquals(2, second.count());
      assertEquals(0, second.finite());
      assertNull(second.min());
      assertNull(second.mean());
      assertTrue(Double.isNaN(second.first()));
      assertEquals(Double.NEGATIVE_INFINITY, second.last());
      assertTrue(ColumnOverview.of(d, d.window(5.0, 6.0), 5.0, 6.0, 2, null).buckets().isEmpty());
      assertThrows(IllegalArgumentException.class, () -> ColumnOverview.of(d, d.all(), null, null, 0, null));
    }
  }

  /** The overview's boundaries in long arithmetic against the shared rule, overflow included. */
  @Test
  void boundariesInLongArithmeticEqualTheSharedRule() {
    long[] starts = {Long.MIN_VALUE, -1_000_000_000_000L, -1, 0, 1, 1_000_000_000L, Long.MAX_VALUE - 10};
    long[] spans = {0, 1, 7, 1_000_000, 3_500_000, 1L << 40, 1L << 62, Long.MAX_VALUE};
    int[] points = {1, 2, 3, 7, 1000, 65_536, Integer.MAX_VALUE};
    int compared = 0;
    for (long start : starts) for (long span : spans) for (int count : points) {
      long end;
      try {
        end = Math.addExact(start, span);
      } catch (ArithmeticException beyond) {
        continue;
      }
      for (int index : new int[] {0, 1, count / 2, count - 1, count}) {
        if (index < 0 || index > count) continue;
        assertEquals(org.triplehelix.wpilogmcp.read.BucketCoordinates.boundary(start, end, count, index),
            ColumnOverview.boundary(start, end, count, index), start + " + " + span + " at " + index + "/" + count);
        compared++;
      }
    }
    var random = new java.util.Random(11);
    for (int i = 0; i < 100_000; i++) {
      long start = random.nextLong() >> random.nextInt(64);
      long span = random.nextLong() >>> (1 + random.nextInt(63));
      long end;
      try {
        end = Math.addExact(start, span);
      } catch (ArithmeticException beyond) {
        continue;
      }
      int count = 1 + random.nextInt(i % 2 == 0 ? 2000 : Integer.MAX_VALUE - 1);
      int index = random.nextInt(count) + random.nextInt(2);
      assertEquals(org.triplehelix.wpilogmcp.read.BucketCoordinates.boundary(start, end, count, index),
          ColumnOverview.boundary(start, end, count, index), start + " + " + span + " at " + index + "/" + count);
      compared++;
    }
    assertTrue(compared > 50_000, "compared " + compared);
  }

  @Test
  void smallMappingWindowsReadTheSameValues() throws Exception {
    try (var scope = MappedLogBytes.withWindowBytes(64); var file = ColumnFile.open(built, SOURCE)) {
      withLog(l -> ColumnOracle.compare(l, file, "edges in 64-byte windows"));
    }
  }

  /** Every read finishes before the file closes: a mapping released under a reader crashes the JVM. */
  @Test
  void concurrentReadersGetTheSameAnswers() throws Exception {
    var answers = new ArrayList<List<Double>>();
    try (var file = ColumnFile.open(built, SOURCE)) {
      var pool = Executors.newFixedThreadPool(8);
      try {
        var reads = new ArrayList<Future<List<Double>>>();
        for (int i = 0; i < 64; i++) {
          String path = i % 2 == 0 ? "[*].speed" : "[1].angle.value";
          reads.add(pool.submit(() -> numbers(select(file, "/s", path))));
        }
        for (var read : reads) answers.add(read.get());
      } finally {
        pool.shutdown();
        assertTrue(pool.awaitTermination(1, java.util.concurrent.TimeUnit.MINUTES));
      }
    }
    for (int i = 0; i < answers.size(); i++) {
      assertEquals(i % 2 == 0 ? List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0) : List.of(0.2, 0.4, 0.8), answers.get(i));
    }
  }

  @Test
  void aBuildIsRepeatableAndNeverReplacesAFile() throws IOException {
    var again = dir.resolve("again.columns");
    try (var use = LogManager.getInstance().acquire(log.toString())) {
      ColumnFileWriter.write(use.log(), SOURCE, again);
      assertArrayEquals(Files.readAllBytes(built), Files.readAllBytes(again));
      var before = Files.readAllBytes(again);
      assertThrows(FileAlreadyExistsException.class, () -> ColumnFileWriter.write(use.log(), SOURCE, again));
      assertArrayEquals(before, Files.readAllBytes(again));
    }
  }

  @Test
  void aFailedBuildLeavesNoFile() throws IOException {
    var decoded = dir.resolve("failed-decoded.columns");
    var streamed = dir.resolve("failed-streamed.columns");
    try (var use = LogManager.getInstance().acquire(log.toString())) {
      var failing = failingAt(use.log(), "/s");
      assertThrows(UncheckedIOException.class, () -> ColumnFileWriter.write(failing, SOURCE, decoded));
      var lazy = (org.triplehelix.wpilogmcp.log.LazyParsedLog) use.log();
      var unreadable = (org.triplehelix.wpilogmcp.read.RecordSeries) Proxy.newProxyInstance(
          ColumnFileTest.class.getClassLoader(), new Class<?>[] {org.triplehelix.wpilogmcp.read.RecordSeries.class},
          (proxy, method, args) -> { throw new UncheckedIOException(new IOException("unreadable")); });
      assertThrows(UncheckedIOException.class, () -> ColumnFileWriter.write(lazy,
          entry -> entry.equals("/s") ? unreadable : lazy.records(entry, false), SOURCE, streamed));
    }
    assertFalse(Files.exists(decoded));
    assertFalse(Files.exists(streamed));
  }

  /** The log, except that decoding {@code entry} fails as an unreadable file does. */
  private static LogData failingAt(LogData log, String entry) {
    var values = log.values();
    @SuppressWarnings("unchecked")
    var failing = (Map<String, List<TimestampedValue>>) Proxy.newProxyInstance(Map.class.getClassLoader(),
        new Class<?>[] {Map.class}, (proxy, method, args) -> {
          if (method.getName().equals("get") && entry.equals(args[0])) throw new UncheckedIOException(new IOException("unreadable"));
          return invoke(method, values, args);
        });
    return (LogData) Proxy.newProxyInstance(LogData.class.getClassLoader(), new Class<?>[] {LogData.class},
        (proxy, method, args) -> method.getName().equals("values") ? failing : invoke(method, log, args));
  }

  private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  // ==================== Damaged and crafted files ====================

  private static Path copy(String name, Consumer<byte[]> damage) throws IOException {
    var bytes = Files.readAllBytes(built);
    damage.accept(bytes);
    var path = dir.resolve(name);
    Files.write(path, bytes);
    return path;
  }

  private static void assertRefused(Path path, String why) {
    var refused = assertThrows(IOException.class, () -> ColumnFile.open(path, SOURCE).close());
    assertTrue(refused.getMessage().contains(why), "refused for " + refused.getMessage() + ", expected " + why);
  }

  @Test
  void aDamagedHeaderFooterOrDirectoryIsRefused() throws IOException {
    assertRefused(copy("magic.columns", b -> b[0] ^= 1), "Not a column file");
    assertRefused(copy("format.columns", b -> b[8] = 2), "format 2");
    assertRefused(copy("recipe.columns", b -> b[12] = 9), "recipe 9");
    assertRefused(copy("footer.columns", b -> b[b.length - 1] ^= 1), "incomplete");
    assertRefused(copy("directory.columns", b -> b[(int) directoryOffset(b) + 6] ^= 1), "checksum");
    var truncated = dir.resolve("truncated.columns");
    var bytes = Files.readAllBytes(built);
    Files.write(truncated, java.util.Arrays.copyOf(bytes, bytes.length - 1));
    assertRefused(truncated, "");
    var empty = dir.resolve("empty.columns");
    Files.write(empty, new byte[0]);
    assertRefused(empty, "Not a column file");
    var noise = dir.resolve("noise.columns");
    var random = new byte[4096];
    new java.util.Random(7).nextBytes(random);
    Files.write(noise, random);
    assertRefused(noise, "");
    assertThrows(IOException.class, () -> ColumnFile.open(built, "test:other").close());
  }

  @Test
  void aDamagedColumnIsNeverRead() throws IOException {
    var damaged = copy("values.columns", b -> b[indexOf(b, Double.doubleToRawLongBits(LARGE)) + 2] ^= 1);
    try (var file = ColumnFile.open(damaged, SOURCE)) {
      assertTrue(file.select("/d", FieldPath.ROOT).isEmpty(), "the damaged column");
      assertTrue(file.damage().orElseThrow().contains("checksum"));
      assertTrue(file.select("/i", FieldPath.ROOT).isEmpty(), "a file found damaged is not read again");
    }
  }

  /**
   * Directories whose checksum is right but whose counts or offsets are not: each is refused
   * before anything sized by them is allocated. The first entry is {@code /b}, a boolean.
   */
  @Test
  void aCraftedDirectoryIsRefusedBeforeItIsBelieved() throws IOException {
    int source = 4 + SOURCE.length();
    int entryCount = source;
    int name = entryCount + 4;
    int type = name + 4 + "/b".length();
    int records = type + 4 + "boolean".length() + 1;
    int columnCount = records + 4 + 4 + 8 + 4;
    int path = columnCount + 4;
    int kind = path + 4;
    int size = kind + 1;
    int valuesOffset = size + 4 + 1;
    assertRefused(crafted("entries.columns", entryCount, b -> b.putInt(Integer.MAX_VALUE)), "exceeds");
    assertRefused(crafted("name.columns", name, b -> b.putInt(0x7FFF_FFF0)), "exceeds");
    assertRefused(crafted("columns.columns", columnCount, b -> b.putInt(5000)), "exceeds");
    assertRefused(crafted("kind.columns", kind, b -> b.put((byte) 9)), "Unknown column kind");
    assertRefused(crafted("size.columns", size, b -> b.putInt(4)), "exceeds");
    assertRefused(crafted("far.columns", valuesOffset, b -> b.putLong(Long.MAX_VALUE - 7)), "outside");
    assertRefused(crafted("unaligned.columns", valuesOffset, b -> b.putLong(36)), "outside");
  }

  /** The built file with directory bytes from {@code at} rewritten, and its checksum made right. */
  private static Path crafted(String name, int at, Consumer<ByteBuffer> write) throws IOException {
    return copy(name, bytes -> {
      var all = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
      int directory = (int) directoryOffset(bytes);
      int length = (int) all.getLong(bytes.length - 16);
      write.accept(all.position(directory + at));
      var crc = new CRC32C();
      crc.update(bytes, 0, ColumnFileFormat.HEADER_BYTES);
      crc.update(bytes, directory, length);
      all.putInt(bytes.length - 8, (int) crc.getValue());
    });
  }

  private static long directoryOffset(byte[] bytes) {
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(bytes.length - ColumnFileFormat.FOOTER_BYTES);
  }

  private static int indexOf(byte[] bytes, long value) {
    var pattern = le(8).putLong(value).array();
    outer:
    for (int i = 0; i + 8 <= bytes.length; i++) {
      for (int j = 0; j < 8; j++) if (bytes[i + j] != pattern[j]) continue outer;
      return i;
    }
    throw new AssertionError("value not found");
  }
}
