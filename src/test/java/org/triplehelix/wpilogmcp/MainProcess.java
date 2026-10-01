/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@link Main} in a child JVM, for what can only be seen from a fresh process: the log level
 * (decided before the first logger exists) and the startup configuration from flags and the
 * environment.
 */
final class MainProcess {

  private MainProcess() {}

  /**
   * Runs {@code Main} with the given arguments in a child JVM on this JVM's class path, stdin
   * at end of file (the stdio server starts, sees the client gone, and exits), with
   * {@code WPILOG_DEBUG} and {@code WPILOG_DIR} unset unless {@code env} sets them, and returns
   * everything it wrote to stdout and stderr.
   *
   * @param workDir A scratch directory for the empty stdin file
   */
  static String run(Path workDir, List<String> args, Map<String, String> env) throws Exception {
    var classpath = System.getProperty("java.class.path");
    assumeTrue(classpath != null && classpath.contains("classes"),
        "The test class path is not available to a child JVM: " + classpath);
    var java = ProcessHandle.current().info().command().orElse("java");
    var stdin = Files.createTempFile(workDir, "stdin", ".empty");

    var command = new ArrayList<String>();
    command.add(java);
    command.add("-cp");
    command.add(classpath);
    command.add(Main.class.getName());
    command.addAll(args);

    var pb = new ProcessBuilder(command);
    pb.environment().remove("WPILOG_DEBUG");
    pb.environment().remove("WPILOG_DIR");
    pb.environment().putAll(env);
    pb.redirectErrorStream(true);
    pb.redirectInput(stdin.toFile());
    var process = pb.start();
    var output = new StringBuilder();
    var reader = new Thread(() -> {
      try {
        output.append(new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8));
      } catch (IOException e) {
        output.append("\n[output could not be read: ").append(e.getMessage()).append("]");
      }
    }, "main-output-reader");
    reader.start();
    boolean exited = process.waitFor(60, TimeUnit.SECONDS);
    if (!exited) {
      process.destroyForcibly();
    }
    reader.join(10_000);
    assertTrue(exited, "Main did not exit within 60 s. Output:\n" + output);
    return output.toString();
  }
}
