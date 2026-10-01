/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Debug logging is decided before the first logger exists (SimpleLogger reads its level once, when
 * the first logger is created), so it can only be verified in a fresh JVM: these tests run
 * {@link Main} as a child process with stdin at end of file (the stdio server starts, sees the
 * client gone, and exits) and read what it logged.
 */
@DisplayName("Main debug logging")
class MainDebugLoggingTest {

  @TempDir
  Path tempDir;

  @Nested
  @DisplayName("debugRequested")
  class DebugRequested {

    @Test
    @DisplayName("-debug anywhere in the arguments")
    void flag() {
      assertTrue(Main.debugRequested(new String[] {"-logdir", "/x", "-debug"}, null));
      assertTrue(Main.debugRequested(new String[] {"-debug"}, "false"));
    }

    @Test
    @DisplayName("WPILOG_DEBUG=true, whatever the case")
    void env() {
      assertTrue(Main.debugRequested(new String[0], "true"));
      assertTrue(Main.debugRequested(new String[0], "TRUE"));
    }

    @Test
    @DisplayName("neither")
    void neither() {
      assertFalse(Main.debugRequested(new String[] {"-logdir", "/x"}, null));
      assertFalse(Main.debugRequested(new String[0], "false"));
      assertFalse(Main.debugRequested(new String[0], ""));
      assertFalse(Main.debugRequested(new String[] {"--debug", "-d"}, null));
    }
  }

  @Nested
  @DisplayName("in a fresh JVM")
  class FreshJvm {

    @Test
    @DisplayName("-debug turns on DEBUG output")
    void debugFlag() throws Exception {
      var output = runMain(List.of("-debug", "-logdir", tempDir.toString(), "-diskcachedisable"),
          Map.of());
      assertTrue(hasDebugLine(output), "Expected DEBUG lines with -debug. Output:\n" + output);
    }

    @Test
    @DisplayName("WPILOG_DEBUG=true turns on DEBUG output")
    void debugEnv() throws Exception {
      var output = runMain(List.of("-logdir", tempDir.toString(), "-diskcachedisable"),
          Map.of("WPILOG_DEBUG", "true"));
      assertTrue(hasDebugLine(output),
          "Expected DEBUG lines with WPILOG_DEBUG=true. Output:\n" + output);
    }

    @Test
    @DisplayName("without either, nothing is logged at DEBUG")
    void noDebug() throws Exception {
      var output = runMain(List.of("-logdir", tempDir.toString(), "-diskcachedisable"),
          Map.of());
      assertTrue(output.contains("INFO"), "The server should have logged its start. Output:\n"
          + output);
      assertFalse(hasDebugLine(output), "No DEBUG lines expected. Output:\n" + output);
    }

    @Test
    @DisplayName("debug: true in the configuration file turns on DEBUG output")
    void debugConfig() throws Exception {
      var config = tempDir.resolve("servers.yaml");
      Files.writeString(config, """
          logdir: %s
          diskcachedisable: true
          servers:
            quiet:
              transport: stdio
            chatty:
              transport: stdio
              debug: true
          """.formatted(tempDir));

      var chatty = runMain(List.of("start", "chatty", "--config", config.toString()), Map.of());
      assertTrue(hasDebugLine(chatty),
          "Expected DEBUG lines with debug: true in the configuration. Output:\n" + chatty);
      assertTrue(chatty.contains("Loaded configuration 'chatty'"),
          "The configuration load should be logged. Output:\n" + chatty);

      var quiet = runMain(List.of("start", "quiet", "--config", config.toString()), Map.of());
      assertFalse(hasDebugLine(quiet), "No DEBUG lines expected. Output:\n" + quiet);
    }

    private boolean hasDebugLine(String output) {
      return output.lines().anyMatch(line -> line.contains(" DEBUG "));
    }

    private String runMain(List<String> args, Map<String, String> env) throws Exception {
      return MainProcess.run(tempDir, args, env);
    }
  }
}
