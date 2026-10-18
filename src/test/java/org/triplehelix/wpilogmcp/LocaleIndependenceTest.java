/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The server compares names, keys and units, never prose, so case conversion must not follow the
 * machine's locale: in Turkish, "I".toLowerCase() is a dotless i, which made a filter for "imu"
 * miss "/IMU", spelled a TBA event key wrongly, and failed the check for Windows. Every conversion
 * in the server names its locale.
 */
class LocaleIndependenceTest {
  private static final Pattern UNNAMED = Pattern.compile("\\.to(Lower|Upper)Case\\(\\s*\\)");

  static List<String> unnamed(String text) {
    var found = new ArrayList<String>(); var lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) if (UNNAMED.matcher(lines[i]).find()) found.add((i + 1) + ": " + lines[i].strip());
    return found;
  }

  @Test void everyCaseConversionInTheServerNamesItsLocale() throws Exception {
    var found = new ArrayList<String>();
    try (var files = Files.walk(Path.of("src/main/java"))) {
      for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        for (var line : unnamed(Files.readString(file))) found.add(file + ":" + line);
      }
    }
    assertEquals(List.of(), found);
  }

  @Test void theCheckSeesAConversionWithoutALocale() {
    assertEquals(List.of("1: var a = name.toLowerCase();", "2: b.toUpperCase( )"),
        unnamed("var a = name.toLowerCase();\nb.toUpperCase( )\nc.toLowerCase(Locale.ROOT)"));
  }
}
