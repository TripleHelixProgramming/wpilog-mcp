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
 * a failing stress test ended in BUILD SUCCESSFUL. A third: they used the user's own disk cache, so
 * they read REV log synchronizations that other code had saved, and saved theirs there.
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

  @Test
  @DisplayName("every test task uses the test disk cache, emptied first unless -PtestCacheDir is given")
  void testDiskCache() throws IOException {
    // Line endings as Windows may check the file out, so a block's end is found either way
    var build = Files.readString(Path.of("build.gradle")).replace("\r\n", "\n");
    int start = build.indexOf("tasks.named('test')");
    assertTrue(start >= 0, "no test task");
    var bodies = new java.util.LinkedHashMap<String, String>();
    bodies.put("test", build.substring(start, build.indexOf("\n}\n", start)));
    for (var name : List.of("stdioStressTest", "httpStressTest")) bodies.put(name, task(build, name));
    for (var e : bodies.entrySet()) {
      var body = e.getValue();
      assertTrue(body.contains("environment 'WPILOG_DISK_CACHE_DIR', testCacheDir.absolutePath"),
          e.getKey() + " does not give its JVM the test disk cache");
      assertTrue(body.contains("if (!keepTestCache) delete testCacheDir"),
          e.getKey() + " does not empty the test disk cache first");
    }
    // The stress tests apply the user's configuration, whose diskcachedir would win over the
    // environment, so they set the test cache themselves, after it
    for (var name : List.of("stdioStressTest", "httpStressTest")) {
      assertTrue(bodies.get(name).contains("systemProperty 'stress.cachedir', testCacheDir.absolutePath"),
          name + " does not pass the test disk cache to the test");
    }
    for (var test : List.of("StressTest", "HttpStressTest")) {
      var source = Files.readString(
          Path.of("src/test/java/org/triplehelix/wpilogmcp/integration", test + ".java"));
      int config = source.indexOf("applyConfig(config);");
      int cache = source.indexOf("StressSupport.useTestCache();");
      assertTrue(config >= 0 && cache > config,
          test + " does not set the test disk cache after applying the configuration");
    }
  }
}
