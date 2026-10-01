/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Comments that date a change ({@code @since 0.9.0}, "since 0.8.0", "(0.9.0)") name a release
 * that exists or is being prepared, never a later one: the version in build.gradle, without its
 * -dev suffix, is the newest. Notes once said 0.9.1 while 0.9.0 was still unreleased.
 */
@DisplayName("version references in the sources")
class SourceVersionReferencesTest {

  /**
   * Our own release numbers where comments date things. Majors of four digits are year-based
   * library versions (REVLib 2026.0.5), not ours.
   */
  static final Pattern DATED = Pattern.compile(
      "(?i)(?:@since\\s+|\\bsince\\s+|\\()(\\d{1,3})\\.(\\d+)\\.(\\d+)(?![\\d.]*\\d)");

  static int[] parse(String version) {
    var core = version.replaceFirst("-.*$", "");
    return Arrays.stream(core.split("\\.")).mapToInt(Integer::parseInt).toArray();
  }

  static boolean newer(int[] a, int[] b) {
    return Arrays.compare(a, b) > 0;
  }

  /** The references newer than {@code current} in one source text, as "line: text". */
  static List<String> newerReferences(String text, int[] current) {
    var found = new ArrayList<String>();
    var lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      var m = DATED.matcher(lines[i]);
      while (m.find()) {
        int[] v = {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
            Integer.parseInt(m.group(3))};
        if (newer(v, current)) found.add((i + 1) + ": " + lines[i].strip());
      }
    }
    return found;
  }

  @Test
  @DisplayName("the pattern finds dated comments and skips addresses and library versions")
  void patternScope() {
    int[] current = parse("0.9.0-dev");
    assertEquals(List.of("1: * @since 0.9.1"), newerReferences(" * @since 0.9.1", current));
    assertEquals(1, newerReferences("cache key (0.10.0).", current).size());
    assertEquals(1, newerReferences("on the load path since 1.0.0", current).size());
    assertTrue(newerReferences(" * @since 0.9.0\n(0.8.2) since 0.5.0", current).isEmpty());
    assertTrue(newerReferences("bind 127.0.0.1 or 0.0.0.0", current).isEmpty());
    assertTrue(newerReferences("SparkModel (2026.0.5): 1 = SPARK Flex", current).isEmpty());
    assertTrue(newerReferences("(0.9.0.1)", current).isEmpty(), "four parts is not a release");
    assertFalse(newer(parse("0.9.0"), parse("0.9.0-dev")));
    assertTrue(newer(parse("0.10.0"), parse("0.9.0")), "numeric, not lexical");
  }

  @Test
  @DisplayName("no main source dates anything to a release after the project version")
  void noFutureVersions() throws Exception {
    int[] current = parse(Version.VERSION);
    var problems = new ArrayList<String>();
    try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
      for (var file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
        for (var hit : newerReferences(Files.readString(file), current)) {
          problems.add(file + ":" + hit);
        }
      }
    }
    assertTrue(problems.isEmpty(), "newer than " + Version.VERSION + ":\n"
        + String.join("\n", problems));
  }
}
