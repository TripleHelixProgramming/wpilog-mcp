/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.triplehelix.wpilogmcp.fixtures.WpiStructs.SWERVE_MODULE_STATE;
import static org.triplehelix.wpilogmcp.fixtures.WpiStructs.swerveModuleState;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.columns.ColumnFile;
import org.triplehelix.wpilogmcp.columns.ColumnFileWriter;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.fixtures.FixtureWriter;
import org.triplehelix.wpilogmcp.log.DecodeProblem;
import org.triplehelix.wpilogmcp.log.LogManager;

/**
 * Every held entry's record facts against the log's own bytes, read by the independent reader:
 * the record each position names by its index among the entry's records must begin where the
 * facts say and carry a payload of the size they say, the positions must run in time order with
 * equal times in file order, and the records left out must be exactly as many as failed to
 * decode.
 */
class ColumnFactsDifferentialTest {
  @TempDir Path scratch;

  /** What the comparisons covered, so a fixture set that lost a case fails rather than passes. */
  private static final class Counts {
    int entries;
    int reordered;
    int failed;
    int varying;
    int longest;
  }

  @Test
  void everyFixtureEntrysRecordFactsMatchTheLogsBytes() throws Exception {
    var dir = FixtureLogs.defaultDirectory();
    var fixtures = FixtureLogs.generateAll(dir);
    var dedicated = writeDedicated(scratch.resolve("facts.wpilog"));
    var manager = LogManager.getInstance();
    // An empty grant list allows every path, which tests that never add a grant rely on.
    var allowed = manager.getAllowedDirectories();
    manager.addAllowedDirectory(dir);
    manager.addAllowedDirectory(scratch.toRealPath());
    var counts = new Counts();
    try {
      for (var fixture : fixtures) compare(manager, fixture.path(), fixture.id(), counts);
      compare(manager, dedicated, "dedicated", counts);
    } finally {
      manager.clearAllowedDirectories();
      allowed.forEach(manager::addAllowedDirectory);
    }
    assertTrue(counts.entries > 50, "entries compared: " + counts.entries);
    assertTrue(counts.reordered >= 1, "no entry out of time order was compared");
    assertTrue(counts.failed >= 1, "no entry with a record that failed to decode was compared");
    assertTrue(counts.varying >= 1, "no entry whose payload sizes vary was compared");
    assertTrue(counts.longest > 2 * 4096, "no entry spanning three blocks was compared: " + counts.longest);
  }

  /**
   * Answers worked out from the dedicated log as written, and reads in any order giving the
   * answers a forward read gives.
   */
  @Test
  void theDedicatedLogsFactsAreTheOnesWritten() throws Exception {
    var log = writeDedicated(scratch.resolve("dedicated.wpilog"));
    var manager = LogManager.getInstance();
    var allowed = manager.getAllowedDirectories();
    manager.addAllowedDirectory(scratch.toRealPath());
    try {
      var file = build(manager, log, "dedicated");
      try (var columns = ColumnFile.open(file, "test:dedicated")) {
        var backwards = columns.facts("/backwards").orElseThrow();
        int[] order = {4, 2, 3, 1, 0};
        for (int p = 0; p < order.length; p++) assertEquals(order[p], backwards.ordinal(p), "/backwards position " + p);
        var broken = columns.facts("/broken").orElseThrow();
        assertEquals(2, columns.entry("/broken").orElseThrow().decoded());
        assertEquals(0, broken.ordinal(0));
        assertEquals(2, broken.ordinal(1), "the record after the one that failed keeps its index");
        var modules = columns.facts("/modules").orElseThrow();
        long[] sizes = {16, 48, 32};
        for (int p = 0; p < sizes.length; p++) assertEquals(sizes[p], modules.payloadBytes(p), "/modules position " + p);

        var ordered = columns.facts("/ordered").orElseThrow();
        int count = columns.entry("/ordered").orElseThrow().decoded();
        assertEquals(10_000, count);
        var forward = new long[count];
        for (int p = 0; p < count; p++) {
          forward[p] = ordered.offset(p);
          assertEquals(p, ordered.ordinal(p));
          assertEquals(8, ordered.payloadBytes(p));
          if (p > 0) assertTrue(forward[p] > forward[p - 1], "in file order");
        }
        List<Integer> shuffled = IntStream.range(0, count).boxed().collect(Collectors.toCollection(ArrayList::new));
        Collections.shuffle(shuffled, new Random(11));
        var any = columns.facts("/ordered").orElseThrow();
        for (int p : shuffled) assertEquals(forward[p], any.offset(p), "position " + p + " read out of order");
        for (int p = count - 1; p >= 0; p -= 97) assertEquals(forward[p], any.offset(p), "position " + p + " read backwards");
      }
    } finally {
      manager.clearAllowedDirectories();
      allowed.forEach(manager::addAllowedDirectory);
    }
  }

  /**
   * {@code /ordered} spans three blocks, interleaved with {@code /flag} so its differences vary;
   * {@code /backwards} is out of time order with a tie at 0.3 s; {@code /broken}'s second record
   * is three bytes where a double needs eight; {@code /modules} holds 1, 3 and 2 states.
   */
  private static Path writeDedicated(Path file) throws Exception {
    try (var w = new FixtureWriter(file, "")) {
      for (int i = 0; i < 10_000; i++) {
        w.dbl("/ordered", 1 + i * 0.001, i);
        if (i % 7 == 0) w.bool("/flag", 1 + i * 0.001, i % 2 == 0);
      }
      w.dbl("/backwards", 0.5, 1).dbl("/backwards", 0.4, 2).dbl("/backwards", 0.3, 3).dbl("/backwards", 0.3, 4)
          .dbl("/backwards", 0.1, 5);
      w.dbl("/broken", 0.1, 1).raw("/broken", "double", 0.2, new byte[3]).dbl("/broken", 0.3, 3);
      w.structArr("/modules", SWERVE_MODULE_STATE, 0.1, swerveModuleState(1, 0))
          .structArr("/modules", SWERVE_MODULE_STATE, 0.2, swerveModuleState(1, 0), swerveModuleState(2, 0),
              swerveModuleState(3, 0))
          .structArr("/modules", SWERVE_MODULE_STATE, 0.3, swerveModuleState(1, 0), swerveModuleState(2, 0));
    }
    return file;
  }

  private Path build(LogManager manager, Path log, String id) throws Exception {
    var file = scratch.resolve(id + ".columns");
    java.nio.file.Files.deleteIfExists(file);
    try (var use = manager.acquire(log.toString())) {
      ColumnFileWriter.write(use.log(), "test:" + id, file);
    } finally {
      manager.unloadLog(log.toString());
    }
    return file;
  }

  private void compare(LogManager manager, Path log, String id, Counts counts) throws Exception {
    var file = build(manager, log, id);
    Set<String> names;
    try (var columns = ColumnFile.open(file, "test:" + id)) {
      names = IndependentLog.read(log, Set.of()).series.keySet().stream()
          .filter(name -> columns.entry(name).isPresent()).collect(Collectors.toSet());
    }
    var independent = IndependentLog.read(log, Set.of(), Set.of(), names);
    try (var columns = ColumnFile.open(file, "test:" + id)) {
      for (var name : names) {
        var where = id + " " + name;
        var entry = columns.entry(name).orElseThrow();
        var facts = columns.facts(name).orElseThrow(() -> new AssertionError(where + ": no record facts"));
        var series = independent.series.get(name);
        assertEquals(series.records, entry.records(), where + ": records");
        int failed = entry.decodeProblem().map(DecodeProblem::failedRecords).orElse(0);
        assertEquals(entry.records() - failed, entry.decoded(), where + ": decoded");
        var seen = new BitSet(series.facts);
        long previousTime = Long.MIN_VALUE;
        int previousOrdinal = -1;
        boolean reordered = false, varying = false;
        for (int p = 0; p < entry.decoded(); p++) {
          int ordinal = facts.ordinal(p);
          assertTrue(ordinal >= 0 && ordinal < series.facts && !seen.get(ordinal), where + ": position " + p + " names " + ordinal);
          seen.set(ordinal);
          assertEquals(series.factOffsets[ordinal], facts.offset(p), where + ": position " + p + " begins");
          assertEquals(series.factSizes[ordinal], facts.payloadBytes(p), where + ": position " + p + " payload");
          long time = series.factMicros[ordinal];
          assertTrue(time > previousTime || time == previousTime && ordinal > previousOrdinal,
              where + ": position " + p + " out of time order");
          reordered |= ordinal < previousOrdinal;
          varying |= p > 0 && facts.payloadBytes(p) != facts.payloadBytes(0);
          previousTime = time;
          previousOrdinal = ordinal;
        }
        counts.entries++;
        if (reordered) counts.reordered++;
        if (failed > 0) counts.failed++;
        if (varying) counts.varying++;
        counts.longest = Math.max(counts.longest, entry.decoded());
      }
    }
  }
}
