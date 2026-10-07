/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.config.PullConfig;

class PullDocumentationTest {
  @Test void standaloneDocumentsAllAcceptedPullAndSshKeys() throws Exception {
    String guide = Files.readString(Path.of("doc/STANDALONE.md"));
    for (String key : PullConfig.KEYS) assertTrue(guide.contains("`capture.pull." + key + "`"), key);
    for (String key : PullConfig.SSH_KEYS) assertTrue(guide.contains("`capture.pull.ssh." + key + "`"), key);
    assertTrue(guide.contains("default `false`")); assertTrue(guide.contains("default `1000000`"));
    assertTrue(guide.contains("disabled for `5` seconds"));
  }
  @ParameterizedTest @ValueSource(strings = {"ARCHITECTURE", "STANDALONE", "DEVELOPMENT", "PIT_SERVER_PLAN"})
  void guidesDescribeTransportIdentityAndTheMatchingBound(String name) throws Exception {
    String text = Files.readString(Path.of("doc", name + ".md"));
    assertTrue(text.contains("SFTP"), name); assertTrue(text.contains("250 ms"), name);
    assertTrue(text.contains("data_alone"), name);
  }
  @Test void hardwareInvocationIsDocumentedAndOptInOnly() throws Exception {
    String build = Files.readString(Path.of("build.gradle"));
    String guide = Files.readString(Path.of("doc/DEVELOPMENT.md"));
    assertTrue(build.contains("hasProperty('pullRobot')")); assertTrue(build.contains("systemProperty 'pull.robot'"));
    assertTrue(guide.contains("-PpullRobot=172.22.11.2")); assertTrue(guide.contains("SftpRobotTest"));
    var condition = SftpRobotTest.class.getAnnotation(org.junit.jupiter.api.condition.EnabledIfSystemProperty.class);
    assertNotNull(condition); assertEquals("pull.robot", condition.named()); assertEquals(".+", condition.matches());
  }
}
