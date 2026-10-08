/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Season replay samples input facts, never successful captures or correlations. Selection is
 * stable in path order, prefers the native sampler's small ten-second recordings, and retains
 * the largest and damaged tails. Only the small inventory is shared between suites; readers
 * close before replay starts. A full run changes file coverage, not the clock-shift matrix.
 */
final class ConformanceSample {
  static final long SMALL_BYTES = 1L << 20, LARGE_BYTES = 64L << 20;
  static final List<Long> SHIFTS = List.of(0L, 40_000L, 120_000L, 200_000L, -120_000L, 240_000L, 260_000L, 6_000_000L);
  private static final Comparator<Fact> BY_PATH = Comparator.comparing(f -> portable(f.path()));
  private static final Comparator<Fact> BY_SIZE = Comparator.comparingLong(Fact::bytes).thenComparing(BY_PATH);
  private static final Map<List<Input>, List<Fact>> INVENTORIES = new ConcurrentHashMap<>();

  record Fact(Path path, long bytes, String kind, boolean rev, boolean incomplete,
      boolean calendar, long startUs, long endUs, String identity) {
    boolean readable() { return !kind.equals("unreadable"); }
    boolean span() { return endUs - startUs >= 10_000_000; }
    boolean pairable() { return readable() && !incomplete && calendar && span(); }
    String sizeClass() { return bytes < SMALL_BYTES ? "small" : bytes < LARGE_BYTES ? "medium" : "large"; }
  }
  private record Input(Path path, long bytes, FileTime modified, boolean rev) {}
  record Selection(String mode, List<Fact> available, List<Path> paths,
      Map<Path, List<String>> reasons, List<Path> pair, List<String> unavailable, boolean shiftMatrix) {
    List<Long> shifts(Path path) {
      if (!shiftMatrix) return List.of(0L);
      // Offsets test the correlator and placement rule; size/tail coverage needs one zero-shift
      // differential replay. A boundary-size source need not be pulled eight times per transport.
      boolean matrix = reasons.getOrDefault(path, List.of()).stream().anyMatch(reason ->
          reason.startsWith("logger:") || reason.startsWith("logger_calendar:") || reason.equals("rev_companion"));
      return matrix ? SHIFTS : List.of(0L);
    }
  }

  private ConformanceSample() {}

  static Selection configured(Path root, String suite) throws IOException {
    return configured(root, suite, System.getProperty("conformance.sample", "sample"), true);
  }

  static Selection configured(Path root, String suite, String mode, boolean matrix) throws IOException {
    if (mode.isBlank()) mode = "sample";
    if (!Set.of("sample", "full").contains(mode)) throw new IllegalArgumentException("conformanceSample must be sample or full");
    int limit;
    try { limit = Integer.parseInt(System.getProperty("conformance.maxlogs", Integer.toString(Integer.MAX_VALUE))); }
    catch (NumberFormatException invalid) { throw new IllegalArgumentException("conformanceMaxLogs must be a nonnegative integer"); }
    if (limit < 0) throw new IllegalArgumentException("conformanceMaxLogs must be a nonnegative integer");
    var inputs = inputs(root.toRealPath());
    // The limit still means the first N paths, before any selection or inspection.
    var bounded = List.copyOf(inputs.subList(0, Math.min(limit, inputs.size())));
    List<Fact> facts;
    try {
      facts = INVENTORIES.computeIfAbsent(bounded, items -> items.stream().map(input -> {
        try { return inspect(input); } catch (IOException e) { throw new UncheckedIOException(e); }
      }).toList());
    } catch (UncheckedIOException e) { throw e.getCause(); }
    var selected = select(facts, mode);
    var selection = new Selection(selected.mode, selected.available, selected.paths, selected.reasons,
        selected.pair, selected.unavailable, matrix);
    var report = new LinkedHashMap<String, Object>();
    report.put("mode", mode); report.put("available_files", inputs.size()); report.put("limited_files", bounded.size());
    report.put("selected_files", selection.paths.size()); report.put("unavailable_strata", selection.unavailable);
    report.put("matrix_files", matrix ? selection.paths.stream().filter(path -> selection.shifts(path).size() > 1).count() : 0);
    report.put("files", selection.paths.stream().map(path -> {
      var fact = facts.stream().filter(f -> f.path.equals(path)).findFirst().orElseThrow();
      return Map.of("path", path.toString(), "bytes", fact.bytes, "kind", fact.kind,
          "strata", selection.reasons.getOrDefault(path, List.of("full")),
          "shifts_us", matrix ? selection.shifts(path) : List.of(0L));
    }).toList());
    report.put("two_boot_pair", selection.pair.stream().map(Path::toString).toList());
    var output = Files.createDirectories(Path.of("build/reports/conformance-sample"))
        .resolve(suite + "-" + root.getFileName() + ".json");
    Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
    return selection;
  }

  private static List<Input> inputs(Path root) throws IOException {
    List<Path> paths;
    try (var walk = Files.walk(root)) { paths = walk.filter(Files::isRegularFile).toList(); }
    var companions = paths.stream().filter(p -> suffix(p, ".revlog")).map(Path::getParent).collect(java.util.stream.Collectors.toSet());
    var result = new ArrayList<Input>();
    for (var path : paths.stream().filter(p -> suffix(p, ".wpilog")).sorted(Comparator.comparing(ConformanceSample::portable)).toList()) {
      result.add(new Input(path, Files.size(path), Files.getLastModifiedTime(path), companions.contains(path.getParent())));
    }
    return result;
  }

  private static boolean suffix(Path path, String suffix) { return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix); }
  private static String portable(Path path) { return path.toString().replace('\\', '/'); }

  private static Fact inspect(Input input) throws IOException {
    try (var source = new ReplaySource(input.path)) {
      return new Fact(input.path, input.bytes, source.kind.name().toLowerCase(Locale.ROOT), input.rev,
          source.reader.stopped != null, source.calendar().isPresent(), source.minUs, source.maxUs, source.kind + ":" + source.serial());
    } catch (IndependentLog.NotALog | IndependentLog.TooLarge invalid) {
      return new Fact(input.path, input.bytes, "unreadable", input.rev, false, false, 0, 0, "");
    }
  }

  static Selection select(List<Fact> inventory, String mode) {
    var facts = inventory.stream().sorted(BY_SIZE).toList();
    var representatives = new LinkedHashMap<Path, List<String>>();
    var unavailable = new ArrayList<String>();
    // The smallest sufficiently long log and one complete calendar log per kind retain the
    // native sampler's correlation coverage; short-only kinds are still replayed.
    for (var layout : ReplaySource.Kind.values()) {
      String kind = layout.name().toLowerCase(Locale.ROOT);
      var candidates = facts.stream().filter(f -> f.kind.equals(kind)).toList();
      var timed = candidates.stream().filter(Fact::span).toList();
      pick(representatives, unavailable, "logger:" + kind, timed.isEmpty() ? candidates : timed);
      var calendar = candidates.stream().filter(Fact::pairable).toList();
      if (!calendar.isEmpty()) add(representatives, calendar.get(0).path, "logger_calendar:" + kind);
    }
    // OTHER names no layout; two such files need not share one. Each remains its own stratum.
    facts.stream().filter(f -> f.kind.equals("other") || !f.readable()).forEach(f ->
        add(representatives, f.path, f.readable() ? "unrecognized_layout" : "unreadable_input"));
    for (String size : List.of("small", "medium", "large")) {
      pick(representatives, unavailable, "size:" + size, facts.stream().filter(f -> f.sizeClass().equals(size)).toList());
    }
    if (!facts.isEmpty()) add(representatives, facts.get(facts.size() - 1).path, "largest");
    pick(representatives, unavailable, "rev_companion", facts.stream().filter(Fact::rev).toList());
    pick(representatives, unavailable, "incomplete_tail", facts.stream().filter(Fact::incomplete).toList());
    pick(representatives, unavailable, "calendar:present", facts.stream().filter(Fact::calendar).toList());
    pick(representatives, unavailable, "calendar:absent", facts.stream().filter(f -> f.readable() && !f.calendar).toList());
    var pair = pair(facts);
    if (pair.isEmpty()) unavailable.add("two_boot_pair");
    else pair.forEach(path -> add(representatives, path, "two_boot_pair"));
    var selected = inventory.stream().sorted(BY_PATH).filter(f -> mode.equals("full") || representatives.containsKey(f.path))
        .map(Fact::path).toList();
    return new Selection(mode, List.copyOf(inventory), selected, Map.copyOf(representatives), pair, List.copyOf(unavailable), true);
  }

  private static void pick(Map<Path, List<String>> chosen, List<String> missing, String stratum, List<Fact> candidates) {
    if (candidates.isEmpty()) missing.add(stratum); else add(chosen, candidates.get(0).path, stratum);
  }

  private static void add(Map<Path, List<String>> chosen, Path path, String reason) {
    chosen.computeIfAbsent(path, ignored -> new ArrayList<>()).add(reason);
  }

  private static List<Path> pair(List<Fact> facts) {
    var first = new java.util.HashMap<String, Fact>();
    for (var fact : facts) {
      if (!fact.pairable()) continue;
      String key = fact.kind + ":" + fact.identity; var previous = first.get(key);
      if (previous != null && fact.startUs + 5_000_000 < previous.endUs
          && !previous.path.getFileName().equals(fact.path.getFileName())) return List.of(previous.path, fact.path);
      first.putIfAbsent(key, fact);
    }
    return List.of();
  }
}
