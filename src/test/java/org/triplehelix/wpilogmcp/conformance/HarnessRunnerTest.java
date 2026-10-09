/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Execute the real scripts with stand-in commands: selection must never start Docker on a Mac. */
@EnabledOnOs({OS.MAC, OS.LINUX})
class HarnessRunnerTest {
  @TempDir Path root;
  private String run(String entry, String platform, boolean docker) throws Exception {
    Files.createDirectories(root.resolve("harness/rio")); var bin = Files.createDirectories(root.resolve("bin"));
    Files.copy(Path.of("harness/run"), root.resolve("harness/run"));
    Files.copy(Path.of("harness/rio/run"), root.resolve("harness/rio/run"));
    script(root.resolve("gradlew"), "printf '%s\\n' \"$*\" >> \"$CALLS\"\n");
    script(bin.resolve("uname"), "if [ \"${1:-}\" = -m ]; then echo x86_64; else echo " + platform + "; fi\n");
    script(bin.resolve("docker"), "printf '%s\\n' \"docker $*\" >> \"$CALLS\"\n" + (docker ? "exit 0\n" : "exit 1\n"));
    script(bin.resolve("python3"), "echo \"$PWD/build/pinned.jar\"\n");
    root.resolve("harness/run").toFile().setExecutable(true); root.resolve("harness/rio/run").toFile().setExecutable(true);
    var output = root.resolve("output.txt");
    var builder = new ProcessBuilder("bash", root.resolve(entry).toString(), "chosen.json", "-PconformanceNative=none")
        .redirectErrorStream(true).redirectOutput(output.toFile());
    builder.environment().remove("HARNESS_RIO_IMAGE"); builder.environment().put("CALLS", root.resolve("calls").toString());
    builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
    var child = builder.start(); assertTrue(child.waitFor(30, TimeUnit.SECONDS));
    assertEquals(0, child.exitValue(), Files.readString(output));
    String calls = Files.readString(root.resolve("calls"));
    assertTrue(calls.contains("-p harness/robot prepareHarness"));
    assertTrue(calls.contains("shopHarness -PharnessTimeline=chosen.json"));
    assertTrue(calls.contains("-PconformanceNative=none"));
    return Files.readString(output) + "\n" + calls;
  }
  private static void script(Path file, String body) throws Exception {
    Files.writeString(file, "#!/bin/sh\nset -eu\n" + body); assertTrue(file.toFile().setExecutable(true));
  }
  @Test void oldContainerEntryPointOnMacRunsMinaAndNamesBothSkippedBackends() throws Exception {
    String result = run("harness/rio/run", "Darwin", true);
    assertTrue(result.contains("Skipping the roboRIO container and real PhotonVision on Darwin"));
    assertFalse(result.contains("docker info")); assertFalse(result.contains("-PharnessRio="));
  }
  @Test void linuxWithoutADaemonStillRunsThePortableTimeline() throws Exception {
    String result = run("harness/run", "Linux", false);
    assertTrue(result.contains("Skipping the roboRIO container and real PhotonVision on Linux"));
    assertFalse(result.contains("-PharnessRio="));
  }
  @Test void linuxWithDockerAddsBothBackendsAndForwardsTheSelection() throws Exception {
    String result = run("harness/run", "Linux", true);
    assertTrue(result.contains("docker build -f harness/rio/Dockerfile"));
    assertTrue(result.contains("-PharnessRio=wpilog-rio-harness:local"));
    assertTrue(result.contains("-PharnessPhoton=" + root + "/build/pinned.jar"));
  }
}
