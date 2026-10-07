/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.triplehelix.wpilogmcp.config.PullConfig;

/** Opt-in, read-only hardware check. No robot byte or identity is saved in a fixture or printed. */
@EnabledIfSystemProperty(named = "pull.robot", matches = ".+")
class SftpRobotTest {
  @Test void realRobotSupportsIdentitySftpAndTheExactPrefixCommand() throws Exception {
    String key = System.getenv("WPILOG_PULL_KEY"); String password = System.getenv("WPILOG_PULL_PASSWORD");
    var ssh = new PullConfig.Ssh(System.getProperty("pull.user", "lvuser"), password == null ? "" : password,
        key == null || key.isBlank() ? null : Path.of(key));
    var config = new PullConfig(true, PullConfig.DISABLED.directories(), 5_000_000, 1_000_000, ssh);
    try (var remote = SftpTransport.connect(System.getProperty("pull.robot"), config, null)) {
      var identity = remote.identity(); assertFalse(identity.serialNumber().isBlank());
      assertTrue(identity.hostKeyFingerprint().startsWith("SHA256:"));
      var files = remote.list(); assertFalse(files.isEmpty(), "Create a synthetic robot test log before the shop test");
      var file = files.stream().filter(f -> f.size() > 0).findFirst().orElseThrow();
      int count = (int) Math.min(file.size(), 65536);
      var block = remote.read(file.name(), 0, count); assertEquals(count, block.length);
      var expected = java.security.MessageDigest.getInstance("SHA-256").digest(block);
      var actual = java.util.HexFormat.of().parseHex(remote.prefixHash(file.name(), count).orElseThrow(() -> new AssertionError("Exec hash unavailable")));
      assertTrue(java.security.MessageDigest.isEqual(expected, actual), "Robot hash differs from exactly the prefix read");
      assertArrayEquals(java.util.Arrays.copyOfRange(block, count / 2, count), remote.read(file.name(), count / 2, count - count / 2));
    }
  }
}
