/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RioAliasHarnessTest {
  @Test void syntheticUsbIsOneDirectoryWithBothRobotSpellings() throws Exception {
    String docker = Files.readString(Path.of("harness/rio/Dockerfile"));
    assertTrue(docker.contains("/media/usb/logs"));
    assertTrue(docker.contains("ln -s /media/usb /u"));
    assertTrue(docker.contains("ln -s /media/usb /U"));
    assertTrue(Files.readString(Path.of("harness/rio/robotCommand")).contains("/u/logs"));
    assertTrue(Files.readString(Path.of("src/test/java/org/triplehelix/wpilogmcp/conformance/RioHarnessTest.java"))
        .contains("RioExpectations.pullsOnce("));
  }
  @Test void harnessRejectsAnExtraTransferEvenAfterContentDeduplication() {
    var manifest = JsonParser.parseString("{\"files\":[{\"remote_name\":\"/u/logs/a.wpilog\"},{\"remote_name\":\"/U/logs/a.wpilog\"}]}").getAsJsonObject();
    assertThrows(AssertionError.class, () -> RioExpectations.pullsOnce(manifest, 1));
  }
  @Test void harnessRequiresOneDistinctLogPerBoot() {
    var manifest = JsonParser.parseString("{\"files\":[{\"remote_name\":\"/u/logs/a.wpilog\"},{\"remote_name\":\"/U/logs/a.wpilog\"}]}").getAsJsonObject();
    assertThrows(AssertionError.class, () -> RioExpectations.pullsOnce(manifest, 2));
    manifest.getAsJsonArray("files").get(1).getAsJsonObject().addProperty("remote_name", "/u/logs/b.wpilog");
    assertDoesNotThrow(() -> RioExpectations.pullsOnce(manifest, 2));
  }
}
