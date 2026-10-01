/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The stress test tasks are run by hand, on real logs, so nothing else notices when they stop
 * meaning anything. Two ways they did: the stdio task did not compile the tests first, so it ran
 * whatever classes an earlier build had left, and both tasks ignored the test run's exit code, so
 * a failing stress test ended in BUILD SUCCESSFUL.
 */
class BuildFileTest {

  /** The text of a task registered as {@code tasks.register('name') { ... }}. */
  private static String task(String build, String name) {
    int start = build.indexOf("tasks.register('" + name + "')");
    assertTrue(start >= 0, "no task " + name);
    int next = build.indexOf("\ntasks.register(", start + 1);
    return build.substring(start, next < 0 ? build.length() : next);
  }

  @Test
  @DisplayName("the stress test tasks build the test classes first and fail when a test fails")
  void stressTasks() throws IOException {
    var build = Files.readString(Path.of("build.gradle"));
    for (var name : List.of("stdioStressTest", "httpStressTest")) {
      var body = task(build, name);
      assertTrue(body.contains("dependsOn 'testClasses'"),
          name + " does not build the test classes first");
      int exitCheck = body.indexOf("result.exitValue != 0");
      assertTrue(exitCheck > 0, name + " does not look at the test run's exit code");
      var onFailure = body.substring(exitCheck, body.indexOf('}', exitCheck));
      assertTrue(onFailure.contains("throw new GradleException"),
          name + " reports failing stress tests without failing the build: " + onFailure);
    }
  }
}
