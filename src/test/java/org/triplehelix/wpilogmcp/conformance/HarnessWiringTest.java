/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/** A simulation-only pass must not silently disappear from CI or enter the ordinary server build. */
class HarnessWiringTest {
  @Test void harnessIsOptInAndCiRunsItSeparatelyWithCachedDownloads() throws Exception {
    String build = Files.readString(Path.of("build.gradle")).replace("\r\n", "\n");
    String task = build.substring(build.indexOf("tasks.register('shopHarness', Test)"));
    task = task.substring(0, task.indexOf("\n}"));
    assertTrue(build.contains("excludeTags 'shop-harness'"));
    assertEquals("shop-harness", ShopHarnessTest.class.getAnnotation(org.junit.jupiter.api.Tag.class).value());
    assertTrue(task.contains("includeTags 'shop-harness'"));
    assertTrue(task.contains("dependsOn testClasses, shadowJar"));
    assertTrue(task.contains("environment 'WPILOG_DISK_CACHE_DIR', file('build/harness-test-disk-cache').absolutePath"));
    assertTrue(task.contains("delete file('build/harness-test-disk-cache')"));
    assertFalse(build.matches("(?s).*dependsOn[^\\n]*shopHarness.*"));
    Map<?, ?> ci = new org.yaml.snakeyaml.Yaml().load(Files.readString(Path.of(".github", "workflows", "ci.yml")));
    var job = (Map<?, ?>) ((Map<?, ?>) ci.get("jobs")).get("shop-harness");
    assertNotNull(job); assertEquals("ubuntu-latest", job.get("runs-on"));
    assertEquals("github.event_name == 'push' && github.ref == 'refs/heads/development' && needs.changes.outputs.harness == 'true'", job.get("if"));
    // SnakeYAML's YAML 1.1 resolver reads GitHub's unquoted "on" as Boolean.TRUE.
    var triggers = (Map<?, ?>) ci.get(Boolean.TRUE);
    assertEquals(List.of("main", "development"), ((Map<?, ?>) triggers.get("push")).get("branches"));
    var steps = (List<?>) job.get("steps");
    var commands = steps.stream().map(s -> ((Map<?, ?>) s).get("run")).toList();
    assertFalse(commands.contains("./gradlew test"), "The ordinary build already ran every ordinary check");
    assertEquals("changes", job.get("needs"), "The harness must run beside ordinary builds");
    assertFalse(steps.stream().map(s -> (Map<?, ?>) s).anyMatch(s -> "actions/download-artifact@v4".equals(s.get("uses"))),
        "The build job already uploads its evidence");
    assertTrue(steps.stream().map(s -> (Map<?, ?>) s).anyMatch(s -> "harness/run -PconformanceNative=${{ needs.changes.outputs.native }}".equals(s.get("run"))));
    var releaseCache = steps.stream().map(s -> (Map<?, ?>) s)
        .filter(s -> "actions/cache@v4".equals(s.get("uses"))).findFirst().orElseThrow();
    var releaseCacheSettings = (Map<?, ?>) releaseCache.get("with");
    assertEquals("build/harness-downloads", releaseCacheSettings.get("path"));
    assertTrue(releaseCacheSettings.get("key").toString().contains("hashFiles('harness/photonvision/release.json')"));
    var release = com.google.gson.JsonParser.parseString(Files.readString(Path.of("harness/photonvision/release.json"))).getAsJsonObject();
    assertEquals(org.triplehelix.wpilogmcp.capture.context.PhotonSettings.RELEASE, release.get("version").getAsString());
    assertTrue(release.get("sha256").getAsString().matches("[0-9a-f]{64}"));
    assertTrue(release.get("url").getAsString().endsWith("/" + release.get("version").getAsString() + "/" + release.get("file").getAsString()));
    var builder = steps.stream().map(s -> (Map<?, ?>) s)
        .filter(s -> "docker/setup-buildx-action@v3".equals(s.get("uses"))).findFirst().orElseThrow();
    var builderSettings = assertInstanceOf(Map.class, builder.get("with"), "BuildKit must also avoid the throttled registry");
    assertEquals("image=mirror.gcr.io/moby/buildkit:buildx-stable-1", builderSettings.get("driver-opts"));
    var image = steps.stream().map(s -> (Map<?, ?>) s)
        .filter(s -> "docker/build-push-action@v6".equals(s.get("uses"))).findFirst().orElseThrow();
    var imageSettings = (Map<?, ?>) image.get("with");
    assertEquals("harness/rio/Dockerfile", imageSettings.get("file"));
    assertEquals(true, imageSettings.get("load"));
    assertTrue(imageSettings.get("cache-from").toString().contains("rio-harness"));
    assertTrue(imageSettings.get("cache-to").toString().contains("rio-harness"));
    var evidence = steps.stream().map(s -> (Map<?, ?>) s)
        .filter(s -> "actions/upload-artifact@v4".equals(s.get("uses"))).findFirst().orElseThrow();
    assertTrue(((Map<?, ?>) evidence.get("with")).get("path").toString().contains("build/reports/replay"),
        "The harness job retains per-log replay counts");
    assertEquals("always()", evidence.get("if"));
    var cache = steps.stream().map(s -> (Map<?, ?>) s).filter(s -> "gradle/actions/setup-gradle@v4".equals(s.get("uses"))).findFirst().orElseThrow();
    assertEquals(false, ((Map<?, ?>) cache.get("with")).get("cache-read-only"), "development must save its WPILib downloads");
    assertTrue(((Map<?, ?>) cache.get("with")).get("gradle-home-cache-includes").toString().contains("permwrapper/dists"));
    var submission = (Map<?, ?>) ((Map<?, ?>) ci.get("jobs")).get("robot-dependency-submission");
    assertNotNull(submission, "GradleRIO's separate build must reach the dependency graph");
    assertEquals("github.event_name == 'push' && github.ref == 'refs/heads/development'", submission.get("if"));
    assertTrue(((List<?>) submission.get("steps")).stream().map(s -> (Map<?, ?>) s)
        .anyMatch(s -> s.get("with") instanceof Map<?, ?> with && "-p harness/robot".equals(with.get("additional-arguments"))));
    String runner = Files.readString(Path.of("harness", "run"));
    assertTrue(runner.contains("set -euo pipefail"));
    int robotBuild = runner.indexOf("-p harness/robot prepareHarness");
    assertTrue(robotBuild >= 0 && runner.indexOf("./gradlew shopHarness") > robotBuild);
    assertTrue(runner.contains("\"$@\""), "Forward conformanceLogDir and other Gradle properties");
    assertTrue(task.contains("systemProperty 'conformance.logdir', project.property('conformanceLogDir')"));
    assertEquals("shop-harness", NtcoreReplayTest.class.getAnnotation(org.junit.jupiter.api.Tag.class).value());
    assertEquals("shop-harness", RealNtcoreReplayTest.class.getAnnotation(org.junit.jupiter.api.Tag.class).value());
  }

  @Test void testSshAndSimulationLibrariesAreAbsentFromTheShippedJar() throws Exception {
    try (var jar = new JarFile(System.getProperty("install.testJar"))) {
      for (String prefix : List.of("org/apache/sshd/", "net/i2p/crypto/", "edu/wpi/first/wpilibj/", "org/triplehelix/harness/")) {
        assertFalse(jar.stream().anyMatch(e -> e.getName().startsWith(prefix)), prefix);
      }
    }
  }

  @Test void guidesNameTheRunnableSuiteAndItsHardwareLimits() throws Exception {
    var checks = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String guide : List.of("DEVELOPMENT", "PIT_SERVER_PLAN", "IDEAS")) {
      String text = Files.readString(Path.of("doc", guide + ".md"));
      checks.add(() -> assertTrue(text.contains("harness"), guide));
    }
    String guide = Files.readString(Path.of("doc", "DEVELOPMENT.md"));
    for (String fact : List.of("harness/run", "shopHarness", "shop-harness", "serialnum", "Linux and macOS")) {
      checks.add(() -> assertTrue(guide.contains(fact), fact));
    }
    String checklist = guide.substring(guide.indexOf("This checks the programs"), guide.indexOf("### Stress tests"));
    for (String fact : List.of("sshd permits an empty password", "`/proc` environment", "`sha256sum` is installed", "CPU/disk", "NI-image", "PhotonVision")) {
      checks.add(() -> assertTrue(checklist.contains(fact), "hardware checklist: " + fact));
    }
    String robot = Files.readString(Path.of("harness", "robot", "build.gradle"));
    assertTrue(robot.contains("2026.2.1")); assertTrue(robot.contains("2026.2.2"));
    assertFalse(robot.contains("enableGui"));
    try (var files = Files.walk(Path.of("harness", "robot", "src"))) {
      for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        checks.add(() -> assertTrue(Files.readString(file).contains("SPDX-License-Identifier: MIT"), file.toString()));
      }
    }
    assertAll(checks);
  }
}
