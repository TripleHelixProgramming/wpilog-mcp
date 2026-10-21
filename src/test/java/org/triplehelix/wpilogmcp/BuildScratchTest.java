/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Scratch a run leaves under build/ lasts until the next run of the same suite, and build.gradle
 * lists which task empties which folder. That holds only while every folder a test, a native
 * editor runner or the build script names there is one of those, a disk cache, or a folder kept
 * on purpose. Before, three replay suites, the fixture generator and the editor runners wrote run
 * folders that nothing removed, and one checkout's build folder grew past 500 GiB; a new suite
 * writing to a new folder would start the same leak unnoticed.
 */
class BuildScratchTest {
  /** Folders kept on purpose, and why. An entry nothing names any more fails as well. */
  private static final Map<String, String> KEPT = Map.ofEntries(
      Map.entry("reports", "results written by name, which the suite's next run replaces"),
      Map.entry("arrow-samples", "CI's pyarrow cross-check reads them after the tests; written by name"),
      Map.entry("harness-downloads", "the pinned PhotonVision release, downloaded once and cached"),
      Map.entry("extension-smoke", "the editor fixture and classpath extensionSmokeFixture writes, and the smoke runner's latest run"),
      Map.entry("visualization-stage1", "a native editor runner's latest run for each editor version"),
      Map.entry("visualization-stage2b", "a native editor runner's latest run for each editor version"),
      Map.entry("visualization-stage3-overview", "a native editor runner's latest run for each editor version"),
      Map.entry("visualization-stage3-panels", "a native editor runner's latest run for each editor version"),
      Map.entry("test-disk-cache", "the test task's disk cache, emptied first unless -PtestCacheDir names one to keep"),
      Map.entry("testnatives", "native libraries the tests load, extracted again by each build"),
      Map.entry("generated", "the version source the build generates"),
      Map.entry("missing-reader-library.jar", "the path ReadLibraryTest reports when no reader JAR is configured; never written"),
      Map.entry("nested-source-*", "VisualizationEndpointTest deletes its folder when the test ends"));

  /** A folder named as "build/x", 'build/x', ("build", "x") or "$buildDir/x". */
  private static final List<Pattern> NAMED = List.of(
      Pattern.compile("[\"']build/([^\"'/\\s]+)"),
      Pattern.compile("\"build\",\\s*\"([^\"/]+)\""),
      Pattern.compile("\\$\\{?buildDir}?/([A-Za-z0-9._-]+)"));
  private static final Pattern TEMPORARY = Pattern.compile("createTempDirectory\\(\\s*Path\\.of\\(\"build\"\\),\\s*\"([^\"]+)\"");
  private static final Pattern BARE = Pattern.compile("Path\\.of\\(\"build\"\\)");

  @Test void everyFolderNamedUnderBuildIsEmptiedCachedOrKeptOnPurpose() throws IOException {
    var build = Files.readString(Path.of("build.gradle"));
    var emptied = new TreeSet<String>();
    scratchLists(build).values().forEach(emptied::addAll);
    var caches = new TreeSet<String>();
    build.lines().filter(line -> line.contains("WPILOG_DISK_CACHE_DIR")).forEach(line -> caches.addAll(folders(line)));
    var named = new TreeMap<String, Set<String>>();
    for (var source : sources()) {
      for (var folder : folders(Files.readString(source))) named.computeIfAbsent(folder, ignored -> new TreeSet<>()).add(source.toString());
    }
    var unaccounted = new ArrayList<String>();
    named.forEach((folder, sources) -> {
      if (!emptied.contains(folder) && !caches.contains(folder) && !KEPT.containsKey(folder)) unaccounted.add(folder + " " + sources);
    });
    assertEquals(List.of(), unaccounted, "Folders under build/ that nothing empties. Add each to the scratch list in "
        + "build.gradle that the task running its suite empties, or to KEPT here with the reason it may stay");
    var stale = new TreeSet<>(KEPT.keySet());
    stale.addAll(emptied);
    stale.removeAll(named.keySet());
    assertEquals(Set.of(), stale, "Listed folders that no test, runner or build script names any more");
  }

  @Test void eachScratchListIsEmptiedByTheTaskThatRunsItsSuites() throws IOException {
    var build = Files.readString(Path.of("build.gradle"));
    assertEquals(Set.of("fixtureScratch", "realLogScratch", "harnessScratch"), scratchLists(build).keySet(),
        "A new scratch list needs a task that empties it, checked here");
    assertTrue(blocks(build, "tasks.withType(Test).configureEach {").stream().anyMatch(b -> b.contains("delete fixtureScratch")),
        "Every test task must empty the fixture folders: worker numbers keep rising for the daemon's life");
    assertTrue(blocks(build, "tasks.named('test') {").stream()
        .flatMap(test -> blocks(test, "if (project.hasProperty('conformanceLogDir')) {").stream())
        .anyMatch(b -> b.contains("delete realLogScratch")), "A real-log run of test must empty the real-log folders");
    assertTrue(blocks(build, "tasks.register('shopHarness', Test) {").stream().anyMatch(b -> b.contains("delete harnessScratch")),
        "shopHarness must empty the native replay and harness folders");
  }

  @Test void theCheckSeesEveryWayAFolderIsNamed() {
    assertEquals(Set.of("replay-work"), folders("Path.of(\"build/replay-work\")"));
    assertEquals(Set.of("test-fixtures"), folders("Path.of(\"build\", \"test-fixtures\", worker)"));
    assertEquals(Set.of("shop-harness"), folders("file('build/shop-harness').absolutePath"));
    assertEquals(Set.of("large-log-disk-cache"), folders("file(\"$buildDir/large-log-disk-cache\")"));
    assertEquals(Set.of("visualization-stage1"), folders("path.resolve(extension, \"..\", \"build\", \"visualization-stage1\", version)"));
    assertEquals(Set.of("nested-source-*"), folders("Files.createTempDirectory(Path.of(\"build\"), \"nested-source-\")"));
    assertEquals(Set.of("build itself"), folders("Path.of(\"build\").resolve(name)"),
        "A folder the check cannot read must fail, never pass unseen");
  }

  /** Every folder directly under build/ that {@code text} names. */
  private static Set<String> folders(String text) {
    var folders = new TreeSet<String>();
    for (var pattern : NAMED) for (var match = pattern.matcher(text); match.find();) folders.add(match.group(1));
    var temporary = new ArrayList<int[]>();
    for (var match = TEMPORARY.matcher(text); match.find();) {
      folders.add(match.group(1) + "*");
      temporary.add(new int[] {match.start(), match.end()});
    }
    for (var match = BARE.matcher(text); match.find();) {
      int at = match.start();
      if (temporary.stream().noneMatch(range -> at >= range[0] && at < range[1])) folders.add("build itself");
    }
    return folders;
  }

  /** Scratch lists declared as {@code def nameScratch = ['a', 'b']}. */
  private static Map<String, List<String>> scratchLists(String build) {
    var lists = new LinkedHashMap<String, List<String>>();
    for (var list = Pattern.compile("def (\\w+Scratch) = \\[([^\\]]*)]").matcher(build); list.find();) {
      var names = new ArrayList<String>();
      for (var name = Pattern.compile("'([^']+)'").matcher(list.group(2)); name.find();) names.add(name.group(1));
      lists.put(list.group(1), names);
    }
    return lists;
  }

  /** The text of each block that {@code opener}, ending in its opening brace, starts. */
  private static List<String> blocks(String text, String opener) {
    var blocks = new ArrayList<String>();
    for (int start = text.indexOf(opener); start >= 0; start = text.indexOf(opener, start + 1)) {
      int depth = 0, at = start + opener.length() - 1;
      do {
        char c = text.charAt(at++);
        if (c == '{') depth++;
        else if (c == '}') depth--;
      } while (depth > 0 && at < text.length());
      blocks.add(text.substring(start, at));
    }
    return blocks;
  }

  /** The build scripts, the test sources and the native editor runners. */
  private static List<Path> sources() throws IOException {
    var sources = new ArrayList<Path>(List.of(Path.of("build.gradle")));
    try (var scripts = Files.list(Path.of("gradle"))) { scripts.filter(p -> p.toString().endsWith(".gradle")).forEach(sources::add); }
    try (var tests = Files.walk(Path.of("src", "test", "java"))) {
      tests.filter(p -> p.toString().endsWith(".java") && !p.getFileName().toString().equals("BuildScratchTest.java")).forEach(sources::add);
    }
    try (var runners = Files.list(Path.of("vscode-extension", "src", "smoke"))) {
      runners.filter(p -> p.toString().endsWith(".ts")).forEach(sources::add);
    }
    return sources;
  }
}
