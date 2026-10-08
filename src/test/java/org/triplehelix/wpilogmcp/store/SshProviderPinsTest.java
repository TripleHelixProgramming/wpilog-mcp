/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.context.DeviceIdentity;
import org.triplehelix.wpilogmcp.log.LogManager;

class SshProviderPinsTest {
  @TempDir Path temp;
  @Test void otherHostsRetainPinsAndRobotContactRemainsAuthoritativeWithoutPersistingCredentials() throws Exception {
    var manager = LogManager.getInstance(); var saved = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    try {
      var store = manager.stores().store(temp);
      store.recordHostKey("camera.local", "SHA256:camera").get();
      store.recordHostKey("robot.local", "SHA256:initial").get();
      assertEquals("SHA256:camera", store.hostKey("camera.local").get()); assertNull(store.hostKey("unknown.local").get());
      store.identify(new DeviceIdentity("SYNTHETIC-PIN", "", "robot.local", "SHA256:device", Map.of()), Clock.systemUTC()).get();
      assertEquals("SHA256:device", store.hostKey("robot.local").get());
      var pin = com.google.gson.JsonParser.parseString(Files.readString(temp.resolve("ssh-hosts.json"))).getAsJsonObject();
      assertEquals(java.util.Set.of("fingerprints"), pin.keySet());
      assertEquals(Map.of("camera.local", "SHA256:camera", "robot.local", "SHA256:initial"),
          new com.google.gson.Gson().fromJson(pin.get("fingerprints"), Map.class));
    } finally { manager.release(temp); manager.clearAllowedDirectories(); saved.forEach(manager::addAllowedDirectory); }
  }
}
