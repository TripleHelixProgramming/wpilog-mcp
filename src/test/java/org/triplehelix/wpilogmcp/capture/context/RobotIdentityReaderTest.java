/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RobotIdentityReaderTest {
  private static RobotIdentityReader.Files remote(Map<String, String> contents) {
    return new RobotIdentityReader.Files() {
      public List<String> environments() { return List.of("/proc/1/environ", "/proc/2/environ", "/proc/3/environ"); }
      public byte[] read(String path) throws IOException {
        if (!contents.containsKey(path)) throw new IOException("unreadable fixture path");
        return contents.get(path).getBytes(StandardCharsets.UTF_8);
      }
    };
  }
  @Test void usesTheHalEnvironmentAndCommentsWithProvenance() throws Exception {
    var device = RobotIdentityReader.read(remote(Map.of(
        "/proc/2/environ", "notserialnum=wrong\u0000serialnum=SYNTHETIC-A\u0000PATH=/bin\u0000",
        "/etc/machine-info", "OTHER=x\nPRETTY_HOSTNAME=\"fixture \\" + "\"bot\\\"\\n\\101\\x42\"\n")),
        "127.0.0.1", "SHA256:synthetic");
    assertEquals("SYNTHETIC-A", device.serialNumber()); assertEquals("fixture \"bot\"\nAB", device.comments());
    assertEquals("device", device.json().get("basis").getAsString());
    assertEquals("127.0.0.1", device.json().get("address").getAsString());
    assertEquals("SHA256:synthetic", device.json().get("host_key_fingerprint").getAsString());
    assertEquals("/proc/2/environ:serialnum", device.metadata().get("serial_source").getAsString());
    assertEquals("/etc/machine-info:PRETTY_HOSTNAME", device.metadata().get("comments_source").getAsString());
    assertEquals("received", device.metadata().get("timestamp").getAsString());
  }
  @Test void refusesMissingConflictingOrUnsafeSerialsAndAllowsMissingComments() throws Exception {
    var contents = new LinkedHashMap<String, String>();
    assertTrue(assertThrows(IOException.class, () -> RobotIdentityReader.read(remote(contents), "host", "key"))
        .getMessage().contains("serialnum"));
    contents.put("/proc/2/environ", "serialnum=SYNTHETIC-A\u0000");
    assertEquals("", RobotIdentityReader.read(remote(contents), "host", "key").comments());
    contents.put("/proc/3/environ", "serialnum=SYNTHETIC-B\u0000");
    assertTrue(assertThrows(IOException.class, () -> RobotIdentityReader.read(remote(contents), "host", "key"))
        .getMessage().contains("Conflicting"));
    contents.put("/proc/3/environ", "serialnum=SYNTHETIC-A\u0000");
    assertEquals("SYNTHETIC-A", RobotIdentityReader.read(remote(contents), "host", "key").serialNumber());
    contents.remove("/proc/3/environ"); contents.put("/proc/2/environ", "serialnum=../../outside\u0000");
    assertThrows(IOException.class, () -> RobotIdentityReader.read(remote(contents), "host", "key"));
  }
  @Test void commentsFollowTheHalsByteBoundAndQuotedField() {
    assertEquals("", RobotIdentityReader.comments("PRETTY_HOSTNAME=unquoted".getBytes(StandardCharsets.UTF_8)));
    assertEquals("é".repeat(32), RobotIdentityReader.comments(("PRETTY_HOSTNAME=\"" + "é".repeat(40) + "\"")
        .getBytes(StandardCharsets.UTF_8)));
  }
}
