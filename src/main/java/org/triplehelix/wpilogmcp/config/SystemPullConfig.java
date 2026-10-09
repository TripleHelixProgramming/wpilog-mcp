/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import com.google.gson.JsonElement;
import java.util.List;
import java.util.Set;

/** Candidate image paths, deliberately opt-in until the team's shop inspection verifies them. */
public record SystemPullConfig(boolean enabled, Kernel kernel, List<String> syslog, boolean journal, List<String> ni,
    List<String> jvmCrash) {
  public enum Kernel { DMESG, OFF }
  public static final Set<String> KEYS = Set.of("enabled", "kernel", "syslog", "journal", "ni", "jvm_crash");
  public static final SystemPullConfig DISABLED = new SystemPullConfig(false, Kernel.DMESG,
      List.of("/var/log/messages"), false, List.of("/var/local/natinst/log"), List.of("/home/lvuser"));
  public SystemPullConfig { syslog = List.copyOf(syslog); ni = List.copyOf(ni); jvmCrash = List.copyOf(jvmCrash); }

  static SystemPullConfig parse(JsonElement value) throws ConfigException {
    if (value == null) return DISABLED;
    if (!value.isJsonObject()) throw bad("", "must be an object");
    var block = value.getAsJsonObject();
    for (String key : block.keySet()) if (!KEYS.contains(key)) throw bad("." + key, "unknown key");
    boolean enabled = false;
    if (block.has("enabled")) {
      var v = block.get("enabled");
      if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) throw bad(".enabled", "must be a boolean");
      enabled = v.getAsBoolean();
    }
    var kernel = Kernel.DMESG;
    if (block.has("kernel")) {
      var v = block.get("kernel");
      if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) throw bad(".kernel", "must be dmesg or off");
      kernel = switch (v.getAsString()) {
        case "dmesg" -> Kernel.DMESG;
        case "off" -> Kernel.OFF;
        default -> throw bad(".kernel", "must be dmesg or off");
      };
    }
    boolean journal = false;
    if (block.has("journal")) {
      var v = block.get("journal");
      if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) throw bad(".journal", "must be a boolean");
      journal = v.getAsBoolean();
    }
    return new SystemPullConfig(enabled, kernel,
        block.has("syslog") ? PullConfig.remotePaths(block.get("syslog"), "system.syslog") : DISABLED.syslog(),
        journal,
        block.has("ni") ? PullConfig.remotePaths(block.get("ni"), "system.ni") : DISABLED.ni(),
        block.has("jvm_crash") ? PullConfig.remotePaths(block.get("jvm_crash"), "system.jvm_crash") : DISABLED.jvmCrash());
  }
  private static ConfigException bad(String key, String reason) { return new ConfigException("capture.pull.system" + key + ": " + reason); }
}
