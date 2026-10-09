/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RioHarnessWiringTest {
  @Test void imageHasRealSshAndALimitedJreAndNeverIncludesAnNiImageOrAHostKey() throws Exception {
    String docker = Files.readString(Path.of("harness/rio/Dockerfile"));
    assertTrue(docker.contains("eclipse-temurin:17-jdk-jammy"));
    assertTrue(docker.contains("jdk.management.agent"));
    assertFalse(docker.contains("--add-modules ALL-MODULE-PATH"));
    assertTrue(docker.contains("passwd -d lvuser"));
    assertTrue(docker.contains("! command -v journalctl"));
    assertTrue(docker.contains("openssh-server coreutils"));
    String ssh = Files.readString(Path.of("harness/rio/sshd_config"));
    for (String policy : List.of("PermitEmptyPasswords yes", "PasswordAuthentication yes", "UsePAM no", "AllowUsers lvuser", "Subsystem sftp internal-sftp")) assertTrue(ssh.contains(policy), policy);
    String entrypoint = Files.readString(Path.of("harness/rio/entrypoint"));
    assertTrue(entrypoint.contains("ssh-keygen -q -t ed25519"));
    assertFalse(docker.contains("RUN ssh-keygen"), "Generate keys only at runtime");
    String robot = Files.readString(Path.of("harness/rio/robotCommand"));
    for (String flag : List.of("jmxremote.port", "jmxremote.rmi.port", "java.rmi.server.hostname", "jmxremote.authenticate=false", "jmxremote.ssl=false")) assertTrue(robot.contains(flag), flag);
    assertTrue(robot.contains("-jar \"/home/lvuser/frcUserProgram.jar\""));
    assertTrue(robot.contains("FRC_UserProgram.log"));
    assertEquals("shop-harness", RioHarnessTest.class.getAnnotation(org.junit.jupiter.api.Tag.class).value());
  }
  @Test void clockOracleRejectsAJumpOrAReceiptTimestampThatDoesNotMatchItsPairing() {
    RioExpectations.pairing(new double[]{1, 2, 3}, new double[]{11, 12, 13}, new double[]{-10, -10, -10}, new double[]{.002, .002, .002});
    assertThrows(AssertionError.class, () -> RioExpectations.pairing(new double[]{1, 2, 9}, new double[]{11, 12, 13}, new double[]{-10, -10, -4}, new double[]{.002, .002, .002}));
    assertThrows(AssertionError.class, () -> RioExpectations.pairing(new double[]{1, 2, 3}, new double[]{11, 12, 13}, new double[]{-10, -10, -4}, new double[]{.002, .002, .002}));
  }
}
