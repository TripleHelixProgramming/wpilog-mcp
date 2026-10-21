/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The opt-in stress suite must retain its persisted-scan comparison when normal tests cannot run it. */
class ScanIndexCoverageTest {
  @Test void stressInvokesTheFreshVersusPersistedScanComparison() throws Exception {
    var type = Class.forName("org.triplehelix.wpilogmcp.integration.StressTest");
    assertTrue(Arrays.stream(type.getDeclaredMethods()).anyMatch(method ->
        method.getName().equals("scanIndexCacheMatchesFreshScan") && method.isAnnotationPresent(Test.class)),
        "StressTest must execute a fresh-versus-persisted scan comparison as a JUnit test");
    String source = Files.readString(Path.of("src/test/java/org/triplehelix/wpilogmcp/integration/StressTest.java"));
    assertTrue(source.contains("compareScanIndexWithFreshScan(manager, logPath, tools, false)"),
        "The opt-in stress method must invoke the same comparison exercised by synthetic tests");
  }
}
