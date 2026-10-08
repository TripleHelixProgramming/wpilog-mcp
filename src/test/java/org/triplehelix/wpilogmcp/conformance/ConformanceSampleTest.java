/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;

/** Coverage is decided from input facts, never from a replay or correlation succeeding. */
class ConformanceSampleTest {
  @TempDir Path directory;

  @Test void defaultSampleIncludesTheLargestAndTheIncompleteTail() throws Exception {
    var files = corpus();
    withOptions(null, null, () -> {
      var selected = RealNtcoreReplayTest.samples(directory);
      assertTrue(selected.contains(files.get(4)), "The largest input is a required representative");
      assertTrue(selected.contains(files.get(3)), "A complete calendar log cannot replace the incomplete-tail stratum");
      assertTrue(selected.size() < files.size(), "Default replay selects representatives, not the entire directory");
    });
  }

  @Test void fullReplaysEveryInputIncludingThoseNotNeededByTheSample() throws Exception {
    var files = corpus();
    withOptions("full", null, () -> assertEquals(files, RealNtcoreReplayTest.samples(directory)));
  }

  @Test void maxLogsStillMeansTheFirstNInputsInPathOrder() throws Exception {
    var files = corpus();
    withOptions("full", "2", () -> assertEquals(files.subList(0, 2), RealNtcoreReplayTest.samples(directory)));
    withOptions(null, "2", () -> assertEquals(files.subList(0, 2), RealNtcoreReplayTest.samples(directory)));
    withOptions("full", "0", () -> assertEquals(List.of(), RealNtcoreReplayTest.samples(directory)));
  }

  @Test void everyAvailableStratumAndTheResetPairHaveAnIndependentRepresentative() {
    var facts = facts();
    var sample = ConformanceSample.select(facts, "sample");
    assertEquals(List.of("a.wpilog", "b.wpilog", "c.wpilog", "d.wpilog", "e.wpilog", "f.wpilog",
        "g.wpilog", "h.wpilog", "i.wpilog"), sample.paths().stream().map(Path::toString).toList());
    assertEquals(List.of(Path.of("a.wpilog"), Path.of("i.wpilog")), sample.pair());
    var reasons = sample.reasons().values().stream().flatMap(List::stream).collect(java.util.stream.Collectors.toSet());
    assertTrue(reasons.containsAll(List.of("logger:datalogmanager", "logger:advantagekit", "logger:other", "unrecognized_layout",
        "size:small", "size:medium", "size:large", "largest", "rev_companion", "incomplete_tail",
        "calendar:present", "calendar:absent", "two_boot_pair")));
    assertEquals(List.of(), sample.unavailable());
    var reversed = new java.util.ArrayList<>(facts); java.util.Collections.reverse(reversed);
    var again = ConformanceSample.select(reversed, "sample");
    assertEquals(sample.paths(), again.paths()); assertEquals(sample.reasons(), again.reasons()); assertEquals(sample.pair(), again.pair());
  }

  @Test void fullAddsAllFilesAtZeroShiftButSmallLoggerAndRevRepresentativesKeepTheMatrix() {
    var full = ConformanceSample.select(facts(), "full");
    assertEquals(10, full.paths().size());
    assertEquals(List.of(0L), full.shifts(Path.of("z.wpilog")));
    var expected = List.of(0L, 40_000L, 120_000L, 200_000L, -120_000L, 240_000L, 260_000L, 6_000_000L);
    for (String name : List.of("a", "b", "c", "f")) assertEquals(expected, full.shifts(Path.of(name + ".wpilog")));
    for (String name : List.of("d", "e", "g", "h", "i")) assertEquals(List.of(0L), full.shifts(Path.of(name + ".wpilog")));
    var sample = ConformanceSample.select(facts(), "sample");
    for (var path : sample.paths()) assertEquals(full.shifts(path), sample.shifts(path));
  }

  @Test void sizeStrataIncludeTheirDocumentedBoundaries() {
    for (long bytes : new long[] {0, 1_048_575}) assertEquals("small", fact("x", bytes, "other", false, false, false, "").sizeClass());
    for (long bytes : new long[] {1_048_576, 67_108_863}) assertEquals("medium", fact("x", bytes, "other", false, false, false, "").sizeClass());
    for (long bytes : new long[] {67_108_864, 1_000_000_000}) assertEquals("large", fact("x", bytes, "other", false, false, false, "").sizeClass());
  }

  @Test void bootPairRequiresTheSameIdentityAResetAndCompleteCalendarEvidence() {
    var candidates = List.of(
        fact("a", 100, "datalogmanager", false, false, true, "one"),
        fact("b", 101, "datalogmanager", false, false, true, "another"),
        new ConformanceSample.Fact(Path.of("c.wpilog"), 102, "datalogmanager", false, false, true, 15_000_000, 30_000_000, "one"),
        fact("d", 103, "datalogmanager", false, true, true, "one"),
        fact("e", 104, "datalogmanager", false, false, false, "one"),
        new ConformanceSample.Fact(Path.of("f.wpilog"), 105, "datalogmanager", false, false, true, 0, 1_000_000, "one"),
        fact("g", 106, "datalogmanager", false, false, true, "one"));
    assertEquals(List.of(Path.of("a.wpilog"), Path.of("g.wpilog")), ConformanceSample.select(candidates, "sample").pair());
    assertTrue(ConformanceSample.select(candidates.subList(0, 6), "full").pair().isEmpty());
  }

  @Test void reportNamesRuntimePathsAndReasonsAndInventoryRefreshesAfterTheInputChanges() throws Exception {
    var files = corpus();
    withOptions(null, null, () -> {
      var first = ConformanceSample.configured(directory, "unit");
      assertTrue(first.unavailable().contains("rev_companion"));
      Files.write(directory.resolve("synthetic.revlog"), new byte[0]);
      var next = ConformanceSample.configured(directory, "unit");
      assertFalse(next.unavailable().contains("rev_companion"));
      var report = com.google.gson.JsonParser.parseString(Files.readString(Path.of("build/reports/conformance-sample")
          .resolve("unit-" + directory.getFileName() + ".json"))).getAsJsonObject();
      assertEquals("sample", report.get("mode").getAsString());
      assertEquals(5, report.get("available_files").getAsInt()); assertEquals(4, report.get("selected_files").getAsInt());
      var rows = report.getAsJsonArray("files");
      assertTrue(rows.asList().stream().anyMatch(r -> r.getAsJsonObject().get("path").getAsString().equals(files.get(4).toString())
          && r.getAsJsonObject().getAsJsonArray("strata").asList().stream().anyMatch(s -> s.getAsString().equals("largest"))));
      assertEquals(2, report.getAsJsonArray("two_boot_pair").size());
      assertFalse(report.toString().contains("identity"), "Only selection categories, paths and counts belong in the report");
    });
  }

  @Test void invalidOptionsNameThePropertyAndCannotSilentlySelectLess() throws Exception {
    withOptions("ful", null, () -> assertTrue(assertThrows(IllegalArgumentException.class,
        () -> ConformanceSample.configured(directory, "unit")).getMessage().contains("conformanceSample")));
    for (String limit : List.of("-1", "many")) withOptions(null, limit, () -> assertTrue(assertThrows(IllegalArgumentException.class,
        () -> ConformanceSample.configured(directory, "unit")).getMessage().contains("conformanceMaxLogs")));
  }

  @Test void inspectionClassifiesLayoutsFromDeclarationsAndHeadersWithoutAFileNameGuess() throws Exception {
    var expected = java.util.Map.of("plain.wpilog", "advantagekit", "AdvantageKit.wpilog", "datalogmanager",
        "FRC_unset.wpilog", "other", "another.wpilog", "other");
    for (var item : expected.entrySet()) {
      String kind = item.getValue(); var file = directory.resolve(item.getKey());
      String header = kind.equals("advantagekit") ? "AdvantageKit" : "";
      try (var out = new WpilogWriter(file, header)) {
        int value = out.start(kind.equals("datalogmanager") ? "NT:/counter" : "/counter", "int64", "", 0);
        out.append(value, 0, WpilogWriter.encodeInt64(0));
      }
    }
    withOptions(null, null, () -> {
      var sample = ConformanceSample.configured(directory, "inspection");
      assertEquals(4, sample.paths().size());
      for (var fact : sample.available()) assertEquals(expected.get(fact.path().getFileName().toString()), fact.kind());
      assertTrue(sample.unavailable().contains("two_boot_pair"));
      assertTrue(sample.unavailable().contains("calendar:present"));
    });
  }

  @Test void everyRealSuiteUsesTheSharedSelectionAndBothGradleTasksForwardItsFlags() throws Exception {
    var base = Path.of("src/test/java/org/triplehelix/wpilogmcp/conformance");
    for (String suite : List.of("RealLogReplayTest", "RealReplayPullTest", "RealReplayPairTest", "RealNtcoreReplayTest",
        "LiveReplayTest", "RealLogConformanceTest", "RealLogDifferentialTest", "RealLogClaimsTest")) {
      assertTrue(Files.readString(base.resolve(suite + ".java")).contains("ConformanceSample.configured("), suite);
    }
    assertTrue(Files.readString(base.resolve("NtcoreReplayPairTest.java")).contains("RealReplayPairTest.run(true)"));
    String build = Files.readString(Path.of("build.gradle"));
    for (String forwarding : List.of("systemProperty 'conformance.sample', project.findProperty('conformanceSample') ?: 'sample'",
        "systemProperty 'conformance.maxlogs', project.property('conformanceMaxLogs')")) {
      assertEquals(2, build.split(java.util.regex.Pattern.quote(forwarding), -1).length - 1, forwarding);
    }
  }

  private static List<ConformanceSample.Fact> facts() {
    return List.of(
        fact("a", 100, "datalogmanager", false, false, true, "one"),
        fact("b", 1_048_576, "advantagekit", false, false, true, "two"),
        fact("c", 1_048_577, "other", false, false, true, "three"),
        fact("d", 67_108_864, "other", false, false, true, "four"),
        fact("e", 101, "datalogmanager", false, false, false, "one"),
        fact("f", 102, "datalogmanager", true, false, false, "one"),
        fact("g", 103, "datalogmanager", false, true, false, "one"),
        fact("h", 1_000_000_000, "datalogmanager", false, false, true, "five"),
        fact("i", 104, "datalogmanager", false, false, true, "one"),
        fact("z", 200, "datalogmanager", false, false, true, "one"));
  }

  private static ConformanceSample.Fact fact(String path, long bytes, String kind, boolean rev,
      boolean incomplete, boolean calendar, String identity) {
    return new ConformanceSample.Fact(Path.of(path + ".wpilog"), bytes, kind, rev, incomplete, calendar, 0, 12_000_000, identity);
  }

  private List<Path> corpus() throws Exception {
    var paths = new java.util.ArrayList<Path>();
    for (int i = 0; i < 5; i++) {
      var path = directory.toRealPath().resolve("input-" + i + ".wpilog"); paths.add(path);
      try (var out = new WpilogWriter(path, "")) {
        int value = out.start("NT:/counter", "int64", "", 0);
        int time = out.start("systemTime", "int64", "", 0);
        out.append(value, 0, WpilogWriter.encodeInt64(0));
        out.append(value, 12_000_000, WpilogWriter.encodeInt64(12));
        out.append(time, 12_000_000, WpilogWriter.encodeInt64(1_800_000_000_000_000L));
        if (i == 4) {
          int blob = out.start("NT:/blob", "raw", "", 0);
          out.append(blob, 12_000_000, new byte[100_000]);
        }
      }
      if (i == 3) Files.write(path, new byte[] {0}, java.nio.file.StandardOpenOption.APPEND);
    }
    return paths;
  }

  static void withOptions(String mode, String limit, org.junit.jupiter.api.function.Executable action) throws Exception {
    String oldMode = System.getProperty("conformance.sample"), oldLimit = System.getProperty("conformance.maxlogs");
    try {
      set("conformance.sample", mode); set("conformance.maxlogs", limit);
      try { action.execute(); } catch (Exception | Error e) { throw e; } catch (Throwable e) { throw new AssertionError(e); }
    } finally { set("conformance.sample", oldMode); set("conformance.maxlogs", oldLimit); }
  }

  private static void set(String key, String value) {
    if (value == null) System.clearProperty(key); else System.setProperty(key, value);
  }
}
