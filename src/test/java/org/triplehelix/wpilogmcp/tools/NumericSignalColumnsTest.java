/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.triplehelix.wpilogmcp.fixtures.WpiStructs.POSE2D;
import static org.triplehelix.wpilogmcp.fixtures.WpiStructs.pose2d;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.columns.ColumnFile;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.fixtures.FixtureWriter;
import org.triplehelix.wpilogmcp.log.ColumnFixtures;
import org.triplehelix.wpilogmcp.log.LazyParsedLog;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.read.FieldPath;
import org.triplehelix.wpilogmcp.read.NumericSamples;

/**
 * A path read from a column file is the path read from the decoded log: every held path of
 * every fixture and of a log of edge cases, sample by sample to the bit, with the same counts,
 * through the per-value view the tools not yet on samples read; and the data endpoint's series
 * keep the decode's value types, bits by which repeated values are told apart, and decode
 * problems.
 */
class NumericSignalColumnsTest {
  @TempDir Path directory;
  private final LogManager manager = LogManager.getInstance();
  private java.util.Set<Path> allowed;
  private Path edges;

  private record Selected(String entry, String field) {}

  @BeforeEach
  void setup() throws Exception {
    directory = directory.toRealPath();
    edges = directory.resolve("edges.wpilog");
    writeEdges(edges);
    // An empty grant list allows every path, which tests that never add a grant rely on.
    allowed = manager.getAllowedDirectories();
    manager.addAllowedDirectory(directory);
    manager.addAllowedDirectory(FixtureLogs.defaultDirectory().toRealPath());
  }

  @AfterEach
  void restore() {
    manager.testReadColumnFiles(false);
    manager.clearAllowedDirectories();
    allowed.forEach(manager::addAllowedDirectory);
  }

  /** Integers that are distinct but equal as doubles, NaN payloads, signed zeros and a failed record. */
  private static void writeEdges(Path path) throws IOException {
    double nan = Double.longBitsToDouble(0x7ff8_0000_0000_0123L);
    float floatNan = Float.intBitsToFloat(0x7fc0_0123);
    long big = 1L << 53;
    try (var w = new FixtureWriter(path, "")) {
      long[] longs = {big, big + 1, big + 1, big + 2, big, Long.MIN_VALUE, Long.MAX_VALUE, -1};
      for (int i = 0; i < longs.length; i++) w.i64("/i", 1.0 + i * i / 10.0, longs[i]);
      double[] doubles = {0.5, -0.0, nan, nan, Double.MAX_VALUE, 1e-300, 0.0};
      for (int i = 0; i < doubles.length; i++) w.dbl("/d", 1.0 + i * i / 10.0, doubles[i]);
      float[] floats = {0.25f, -0.0f, floatNan, floatNan, Float.MAX_VALUE};
      for (int i = 0; i < floats.length; i++) w.flt("/f", 1.0 + i * i / 10.0, floats[i]);
      boolean[] booleans = {true, false, true, true, false};
      for (int i = 0; i < booleans.length; i++) w.bool("/b", 1.0 + i * i / 10.0, booleans[i]);
      w.dbl("/back", 2.0, 20).dbl("/back", 1.0, 10).dbl("/back", 1.0, 11).dbl("/back", 3.0, 30)
          .dbl("/back", 0.5, 5);
      w.struct("/bad", POSE2D, 1.0, pose2d(1, 1, 1)).raw("/bad", "struct:Pose2d", 2.0, new byte[5])
          .struct("/bad", POSE2D, 3.0, pose2d(3, 3, 3));
      w.schema("Counts", "int64 total;uint64 big;float gain", 1.0);
      for (int i = 0; i < 5; i++) {
        var record = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putLong(big + i / 2)
            .putLong(5 + i).putFloat(0.25f * i).array();
        w.raw("/counts", "struct:Counts", 1.0 + i / 10.0, record);
      }
    }
  }

  private List<Path> logs() throws IOException {
    var out = new ArrayList<Path>();
    out.add(edges);
    for (var fixture : FixtureLogs.generateAll(FixtureLogs.defaultDirectory())) out.add(fixture.path().toRealPath());
    return out;
  }

  /**
   * The held paths of a log's column file, which {@link ColumnFixtures} builds first, with each
   * indexed path of a complete entry pooled over its first index.
   */
  private List<Selected> held(Path log) throws IOException {
    var file = ColumnFixtures.build(manager, log);
    String source;
    try (var use = manager.acquire(log.toString())) {
      source = "sha256:" + ((LazyParsedLog) use.log()).contentSha256().orElseThrow();
    }
    var paths = new ArrayList<Selected>();
    try (var columns = ColumnFile.open(file, source); var use = manager.acquire(log.toString())) {
      for (var name : use.log().entries().keySet()) {
        var held = columns.entry(name);
        if (held.isEmpty()) continue;
        for (var text : held.get().paths()) {
          paths.add(new Selected(name, text.isEmpty() ? null : text));
          if (held.get().complete() && text.contains("[0]")) paths.add(new Selected(name, text.replaceFirst("\\[0]", "[*]")));
        }
      }
    }
    return paths;
  }

  /** A sample series to the bit: times, numbers, and the bits that tell repeated values apart. */
  private static List<Object> bits(NumericSamples samples) {
    int n = samples.size();
    var micros = new long[n];
    var seconds = new long[n];
    var numbers = new long[n];
    var valueBits = new long[n];
    for (int i = 0; i < n; i++) {
      micros[i] = samples.timeUs(i);
      seconds[i] = Double.doubleToRawLongBits(samples.seconds(i));
      numbers[i] = Double.doubleToRawLongBits(samples.number(i));
      valueBits[i] = samples.valueBits(i);
    }
    return List.of(Arrays.toString(micros), Arrays.toString(seconds), Arrays.toString(numbers),
        Arrays.toString(valueBits));
  }

  /** Values as the per-value view gives them: each one's type and, for floating point, its bits. */
  private static List<Object> values(List<org.triplehelix.wpilogmcp.log.TimestampedValue> values) {
    var out = new ArrayList<Object>();
    for (var tv : values) {
      var value = tv.value();
      Object key = value instanceof Double d ? "D" + Long.toHexString(Double.doubleToRawLongBits(d))
          : value instanceof Float f ? "F" + Integer.toHexString(Float.floatToRawIntBits(f))
          : value == null ? "null" : value.getClass().getSimpleName() + ":" + value;
      out.add(List.of(tv.timestampUs(), Double.doubleToRawLongBits(tv.timestamp()), key));
    }
    return out;
  }

  private static List<Object> signal(NumericSignal signal) {
    return List.of(bits(signal.samples()), values(signal.values()), signal.recordCount(), signal.recordsWithoutValue());
  }

  @Test
  void everyHeldPathResolvesFromItsColumnFileAsFromTheLog() throws Exception {
    int compared = 0;
    try {
      for (var log : logs()) {
        var paths = held(log);
        var expected = new ArrayList<List<Object>>();
        manager.testReadColumnFiles(false);
        try (var use = manager.acquire(log.toString())) {
          for (var path : paths) expected.add(signal(NumericSignal.resolve(use.log(), path.entry(), path.field())));
        }
        manager.testReadColumnFiles(true);
        try (var use = manager.acquire(log.toString())) {
          for (int i = 0; i < paths.size(); i++) {
            var path = paths.get(i);
            var where = log.getFileName() + " " + path.entry() + (path.field() == null ? "" : path.field());
            var field = path.field() == null ? FieldPath.ROOT : FieldPath.parse(path.field());
            assertTrue(use.log().numericColumn(path.entry(), field).isPresent(), where + ": not read from its column file");
            assertEquals(expected.get(i), signal(NumericSignal.resolve(use.log(), path.entry(), path.field())), where);
            compared++;
          }
        }
      }
    } finally {
      manager.testReadColumnFiles(false);
    }
    assertTrue(compared > 100, "paths compared: " + compared);
  }

  /** What the data endpoint reads for a name: its series, value types and decoding metadata. */
  private static List<Object> series(org.triplehelix.wpilogmcp.log.LogData log, String name) {
    var series = EntryData.resolve(log, name);
    return Arrays.asList(series.name(), series.entry(), series.field(), series.type(), series.numeric(),
        values(series.values()), series.samples() == null ? null : bits(series.samples()),
        EntryData.decodingMetadata(log, series).toString());
  }

  @Test
  void theDataEndpointsSeriesComeFromTheColumnFileAsFromTheLog() throws Exception {
    int fromFile = 0;
    try {
      for (var log : logs()) {
        var names = new ArrayList<String>();
        for (var path : held(log)) {
          var field = path.field();
          names.add(field == null ? path.entry()
              : path.entry() + (field.startsWith(".") || field.startsWith("[") ? field : "." + field));
        }
        var expected = new ArrayList<List<Object>>();
        manager.testReadColumnFiles(false);
        try (var use = manager.acquire(log.toString())) {
          for (var name : names) expected.add(series(use.log(), name));
        }
        manager.testReadColumnFiles(true);
        try (var use = manager.acquire(log.toString())) {
          for (int i = 0; i < names.size(); i++) {
            var name = names.get(i);
            if (use.log().entries().containsKey(name)
                && use.log().numericColumn(name, FieldPath.ROOT).isPresent()) fromFile++;
            assertEquals(expected.get(i), series(use.log(), name), log.getFileName() + " " + name);
          }
        }
      }
    } finally {
      manager.testReadColumnFiles(false);
    }
    assertTrue(fromFile > 50, "whole entries read from column files: " + fromFile);
  }
}
