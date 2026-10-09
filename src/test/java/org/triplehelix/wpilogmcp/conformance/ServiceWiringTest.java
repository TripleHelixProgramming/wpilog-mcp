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
import org.junit.jupiter.api.Test;

/** The real supervisor check must run independently and retain the journal even on failure. */
class ServiceWiringTest {
  @Test void linuxRunsThePrintedUnitsAndProvesAnUnmanagedPlantIsRejected() throws Exception {
    Map<?, ?> ci = new org.yaml.snakeyaml.Yaml().load(Files.readString(Path.of(".github/workflows/ci.yml")));
    var job = (Map<?, ?>) ((Map<?, ?>) ci.get("jobs")).get("systemd");
    assertNotNull(job, "A real systemd job must exercise the printed units");
    assertEquals("ubuntu-latest", job.get("runs-on")); assertEquals("changes", job.get("needs"));
    assertEquals("needs.changes.outputs.service == 'true'", job.get("if"));
    var steps = ((List<?>) job.get("steps")).stream().map(s -> (Map<?, ?>) s).toList();
    String commands = steps.stream().map(s -> String.valueOf(s.get("run"))).collect(java.util.stream.Collectors.joining("\n"));
    assertTrue(commands.contains("./gradlew shadowJar")); assertFalse(commands.contains("./gradlew build"));
    assertTrue(commands.contains("ci/check_service.py")); assertTrue(commands.contains("--plant-unmanaged"));
    var upload = steps.stream().filter(s -> "actions/upload-artifact@v4".equals(s.get("uses"))).findFirst().orElseThrow();
    assertEquals("always()", upload.get("if"));
    assertTrue(((Map<?, ?>) upload.get("with")).get("path").toString().contains("build/reports/systemd"));
  }
}
