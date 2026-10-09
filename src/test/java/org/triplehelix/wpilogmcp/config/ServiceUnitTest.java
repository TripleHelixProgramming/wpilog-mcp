/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceUnitTest {
  @TempDir Path temp;
  @Test void printedUnitsHaveAnUnprivilegedStateDirectoryAndBoundedShutdownWithoutJitOrHeapRestrictions() throws Exception {
    var config = temp.resolve("servers.yaml");
    Files.writeString(config, "servers:\n  pit:\n    transport: http\n    port: 12345\n");
    var stdout = new ByteArrayOutputStream(); var stderr = new ByteArrayOutputStream();
    assertEquals(0, ServiceUnit.run(new String[] {"service-unit", "pit", "--config", config.toString()}, new PrintStream(stdout), new PrintStream(stderr)), stderr.toString());
    try (var files = Files.list(temp)) { assertEquals(List.of(config), files.toList(), "Printing must not install anything"); }
    String text = stdout.toString(java.nio.charset.StandardCharsets.UTF_8);
    for (String directive : List.of("# file: wpilog-mcp-pit.service", "User=wpilog-mcp", "Group=wpilog-mcp", "StateDirectory=wpilog-mcp",
        "EnvironmentFile=-/etc/wpilog-mcp/environment", "Environment=LANG=C.UTF-8 LC_ALL=C.UTF-8", "TimeoutStopSec=90s", "KillMode=mixed", "SuccessExitStatus=143",
        "Restart=always", "StartLimitIntervalSec=0", "NoNewPrivileges=true", "ProtectSystem=strict", "ProtectHome=true", "PrivateTmp=true",
        "PrivateDevices=true", "ProtectKernelTunables=true", "ProtectKernelModules=true", "ProtectControlGroups=true", "RestrictSUIDSGID=true",
        "LockPersonality=true", "RestrictRealtime=true", "CapabilityBoundingSet=", "RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6",
        "ExecStart=/opt/wpilog-mcp/bin/wpilog-mcp run pit --config", "--managed", "# file: wpilog-mcp-pit-health.service",
        "--max-time 5 http://127.0.0.1:12345/health", "# file: wpilog-mcp-pit-health.timer", "OnUnitActiveSec=30s")) {
      assertTrue(text.contains(directive), directive);
    }
    assertFalse(text.contains("MemoryDenyWriteExecute")); assertFalse(text.contains("MemoryMax"));
    assertFalse(text.contains("TBA_API_KEY=")); assertFalse(text.contains("PIDFile=")); assertFalse(text.contains("--internal-daemon"));
  }
  @Test void argumentsCannotBecomeUnitDirectivesOrSystemdSubstitutions() throws Exception {
    for (String name : List.of("../pit", "pit\nUser=root", "pit%u", "pit$X", "pit with space")) {
      assertThrows(IllegalArgumentException.class, () -> ServiceUnit.render(name, 12345, temp));
    }
    String text = ServiceUnit.render("pit", 12345, temp.resolve("space $VAR %h.yaml")).get("wpilog-mcp-pit.service");
    assertTrue(text.contains("space $$VAR %%h.yaml\" --managed"), text);
    var err = new ByteArrayOutputStream();
    var config = temp.resolve("stdio.yaml"); Files.writeString(config, "servers:\n  pit: {transport: stdio}\n");
    assertEquals(1, ServiceUnit.run(new String[] {"service-unit", "pit", "--config", config.toString()}, System.out, new PrintStream(err)));
    assertTrue(err.toString().contains("transport: http"));
  }
}
