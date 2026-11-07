/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** A packaging check that is never reached by build cannot protect the recording process. */
class RuntimeNativePolicyTest {
  @Test void ordinaryBuildChecksRuntimeAndTheDistributionWithoutTestNatives() throws Exception {
    String build = Files.readString(Path.of("build.gradle"));
    assertTrue(build.contains("apply from: 'gradle/verify-runtime-native.gradle'"),
        "build must load the server native-library guard");
    var guard = Path.of("gradle/verify-runtime-native.gradle");
    assertTrue(Files.isRegularFile(guard), "Missing server native-library guard");
    String text = Files.readString(guard);
    assertTrue(text.contains("tasks.named('check') { dependsOn tasks.named('verifyRuntimeNativeLibraries') }"),
        "build's check lifecycle must run the native-library guard");
    assertTrue(text.contains("sourceSets.main.runtimeClasspath.files.each(inspect)"),
        "Check the application runtime, not the test runtime");
    assertTrue(text.contains("inspect(tasks.named('shadowJar').get().archiveFile.get().asFile)"),
        "Check the actual distribution after shadowJar");
    assertFalse(text.contains("configurations.testNatives"), "Native test oracles are not server inputs");
  }
}
