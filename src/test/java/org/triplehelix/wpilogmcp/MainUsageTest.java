/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What {@code -help} says, and the Gradle tasks that run {@link Main} with flags. */
@DisplayName("Main usage")
class MainUsageTest {

  @TempDir
  static Path tempDir;

  static String help;

  @BeforeAll
  static void runHelp() throws Exception {
    help = MainProcess.run(tempDir, List.of("-help"), Map.of());
  }

  @Test
  @DisplayName("WPILOG_MAX_HEAP names the launcher that reads it and the servers start runs")
  void maxHeapLine() {
    var line = help.lines().filter(l -> l.contains("WPILOG_MAX_HEAP")).findFirst().orElseThrow();
    assertFalse(line.contains("run-mcp"), "there is no run-mcp launcher: " + line);
    assertTrue(line.contains("wpilog-mcp launcher"), line);
    assertTrue(line.contains("start"), "servers started with start get the heap too: " + line);
  }

  /**
   * The flags passed by each javaexec in build.gradle that runs Main, from its {@code args} up to
   * the end of its block.
   */
  static List<String> gradleFlagsForMain(String buildScript) {
    var flags = new ArrayList<String>();
    var runsMain = Pattern.compile("mainClass\\s*=\\s*'org\\.triplehelix\\.wpilogmcp\\.Main'");
    var quotedFlag = Pattern.compile("'(-[^']*)'");
    var m = runsMain.matcher(buildScript);
    while (m.find()) {
      int end = buildScript.indexOf('}', m.end());
      var block = buildScript.substring(m.end(), end < 0 ? buildScript.length() : end);
      block.lines().filter(l -> l.strip().startsWith("args")).forEach(l -> {
        var f = quotedFlag.matcher(l);
        while (f.find()) flags.add(f.group(1));
      });
    }
    return flags;
  }

  @Test
  @DisplayName("the flag reader finds flags passed to Main and nothing else")
  void flagReader() {
    var script = """
        application { mainClass = 'org.triplehelix.wpilogmcp.Main' }
        javaexec {
            mainClass = 'org.triplehelix.wpilogmcp.Main'
            args '--measure-memory', logPath
        }
        javaexec {
            mainClass = 'org.junit.platform.console.ConsoleLauncher'
            args '--select-class', 'X'
        }
        """;
    assertTrue(gradleFlagsForMain(script).equals(List.of("--measure-memory")),
        gradleFlagsForMain(script).toString());
  }

  /** Whether -help lists the flag (each help line is "[main] INFO ... -   -flag ..."). */
  static boolean helpLists(String flag) {
    return Pattern.compile("\\s" + Pattern.quote(flag) + "(?=[\\s,])").matcher(help).find();
  }

  @Test
  @DisplayName("every flag a Gradle task passes to Main is one Main documents (and accepts)")
  void gradleTasksPassKnownFlags() throws Exception {
    assertTrue(helpLists("-logdir") && helpLists("-help"), help);
    assertFalse(helpLists("--measure-memory") || helpLists("-log"), "whole flags only");
    for (var flag : gradleFlagsForMain(Files.readString(Path.of("build.gradle")))) {
      assertTrue(helpLists(flag), "build.gradle passes " + flag + ", which -help does not list:\n"
          + help);
    }
  }
}
