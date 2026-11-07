/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaptureDiskConfigTest {
  @TempDir Path directory;
  private CaptureConfig parse(String extra) throws Exception {
    return CaptureConfig.parse(JsonParser.parseString("{robot:{host:'127.0.0.1'},store:'synthetic'" + extra + "}"), p -> p);
  }
  @Test void defaultsAreExactBinaryByteCounts() throws Exception {
    var config = parse("");
    assertEquals(2_147_483_648L, config.diskPolicy().reserveBytes());
    assertEquals(268_435_456L, config.diskPolicy().floorBytes());
    assertEquals(536_870_912L, config.diskPolicy().resumeMarginBytes());
  }
  @Test void configurableByteCountsRemainExactAndRejectFractionalOrOverflowedValues() throws Exception {
    var config = assertDoesNotThrow(() -> parse(",disk_reserve_bytes:9007199254740993,disk_floor_bytes:17,disk_resume_margin_bytes:23"),
        "Exact nonnegative byte settings must be accepted");
    assertEquals(9_007_199_254_740_993L, config.diskPolicy().reserveBytes());
    assertEquals(17, config.diskPolicy().floorBytes()); assertEquals(23, config.diskPolicy().resumeMarginBytes());
    for (String invalid : new String[] {"-1", "0.5", "9223372036854775808", "'100'"}) {
      assertTrue(assertThrows(ConfigException.class, () -> parse(",disk_floor_bytes:" + invalid)).getMessage().contains("disk_floor_bytes"));
    }
    assertThrows(ConfigException.class, () -> parse(",disk_floor_bytes:9223372036854775807,disk_resume_margin_bytes:1"));
  }
  @Test void contextOverridesDoNotEraseTheCaptureDiskPolicy() throws Exception {
    var file = directory.resolve("servers.yaml");
    Files.writeString(file, "servers:\n  pit:\n    transport: http\n    capture:\n      robot: {host: 127.0.0.1}\n"
        + "      store: synthetic\n      disk_reserve_bytes: 123\n      disk_floor_bytes: 17\n      disk_resume_margin_bytes: 23\n"
        + "    context:\n      jvm: {port: 7199}\n");
    var loaded = assertDoesNotThrow(() -> new ConfigLoader().load("pit", file),
        "Server capture byte settings must survive loading with a context override");
    assertEquals(loaded.capture().diskPolicy(), loaded.effectiveCapture().diskPolicy());
    assertEquals(123, loaded.effectiveCapture().diskPolicy().reserveBytes());
  }
}
