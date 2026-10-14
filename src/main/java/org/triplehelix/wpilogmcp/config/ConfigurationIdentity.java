/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** A named configuration keeps the same local identity across upgrades and port changes. */
public final class ConfigurationIdentity {
  private ConfigurationIdentity() {}
  public static String key(Path config, String name) throws IOException {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("A named server is required");
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest((config.toRealPath() + "\n" + name).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
}
