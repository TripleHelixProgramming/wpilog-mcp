/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.revlog.dbc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for DbcLoader.
 */
class DbcLoaderTest {

  private DbcLoader loader;

  @TempDir
  Path tempDir;

  @BeforeEach
  void setUp() {
    loader = new DbcLoader();
  }

  @Test
  void testLoadFromString() {
    String content = """
        VERSION "1.0"
        BO_ 0x100 Test: 8 Node
         SG_ Signal : 0|8@1+ (1,0) [0|255] ""
        """;

    DbcDatabase db = loader.loadFromString(content);

    assertEquals("1.0", db.version());
    assertEquals(1, db.messageCount());
  }

  @Test
  void testLoadFromFile(@TempDir Path tempDir) throws IOException {
    String content = """
        VERSION "file_test"
        BO_ 0x200 FileTest: 8 Node
         SG_ FileSignal : 0|16@1+ (1,0) [0|65535] ""
        """;

    Path dbcFile = tempDir.resolve("test.dbc");
    Files.writeString(dbcFile, content);

    DbcDatabase db = loader.loadFromFile(dbcFile);

    assertEquals("file_test", db.version());
    assertTrue(db.getMessage(0x200).isPresent());
  }

  @Test
  void testLoadWithCommandLineOverride(@TempDir Path tempDir) throws IOException {
    String content = """
        VERSION "override"
        BO_ 0x300 Override: 8 Node
         SG_ OverrideSignal : 0|8@1+ (1,0) [0|255] ""
        """;

    Path overrideFile = tempDir.resolve("override.dbc");
    Files.writeString(overrideFile, content);

    DbcDatabase db = loader.load(overrideFile.toString());

    assertEquals("override", db.version());
    assertTrue(db.getMessage(0x300).isPresent());
  }

  @Test
  void testLoadFallsBackToEmbedded() throws IOException {
    // Pass a non-existent path, should fall back to embedded
    DbcDatabase db = loader.load("/nonexistent/path/to/file.dbc");

    // The embedded resource should have been loaded
    // Check that it has some content (the embedded rev_spark.dbc)
    assertNotNull(db);
    assertTrue(db.messageCount() > 0);
  }

  @Test
  void testLoadNullOverrideFallsBackToEmbedded() throws IOException {
    DbcDatabase db = loader.load(null);

    assertNotNull(db);
    assertTrue(db.messageCount() > 0);
  }

  @Test
  void testLoadEmbedded() throws IOException {
    DbcDatabase db = loader.loadEmbedded();

    // Verify embedded DBC has expected content
    assertNotNull(db);
    assertTrue(db.messageCount() > 0);

    // Firmware 25+ periodic status frames: API class 46, indices 0-9 (REV spark-frames 2.1.0)
    for (int index = 0; index <= 9; index++) {
      assertTrue(db.getMessage(0x0205B800 + 0x40 * index).isPresent(), "status " + index);
    }
    // The legacy class 6 status 0 (always zero output, every fault set) is not decoded
    assertTrue(db.getMessage(0x02051800).isEmpty());

    var status0 = db.getMessage(0x0205B800).orElseThrow();
    var appliedOutput = status0.getSignal("AppliedOutput");
    assertEquals(1.01 / 32767, appliedOutput.scale(), 1e-18);
    assertTrue(appliedOutput.signed());
    assertEquals(DbcSignal.ValueType.FLOAT32,
        db.getMessage(0x0205B880).orElseThrow().getSignal("Velocity").valueType());
  }

  @Test
  void embeddedDecodesRealFramesFromA2026RevLog() throws IOException {
    // Frames from REV_20260321_162932.revlog (SPARK MAX firmware 26.1.5), checked against the
    // AdvantageKit inputs REVLib read from the same controllers
    var decoder = new CanDecoder(loader.loadEmbedded());
    var spindexer = decoder.decode(0x0205B810, HexFormat.of().parseHex("0000b8c500206000"));
    assertEquals(0.0, spindexer.get("AppliedOutput"), 1e-12);
    assertEquals(1464 * 30.0 / 4095, spindexer.get("BusVoltage"), 1e-9);
    assertEquals(12 * 150.0 / 4095, spindexer.get("OutputCurrent"), 1e-9);
    assertEquals(32.0, spindexer.get("MotorTemperature"));
    assertEquals(0.0, spindexer.get("IsInverted"));
    assertEquals(1.0, spindexer.get("PrimaryHeartbeatLock"));

    var turret = decoder.decode(0x0205B80C, HexFormat.of().parseHex("1e014405001db000"));
    assertEquals(286 * 1.01 / 32767, turret.get("AppliedOutput"), 1e-12);
    assertEquals(1348 * 30.0 / 4095, turret.get("BusVoltage"), 1e-9);
    assertEquals(29.0, turret.get("MotorTemperature"));
    assertEquals(1.0, turret.get("IsInverted"));

    var encoder = decoder.decode(0x0205B890, HexFormat.of().parseHex("91179e4095f00f44"));
    assertEquals(4.9403767585754395, encoder.get("Velocity"), 1e-12);
    assertEquals(575.7590942382812, encoder.get("Position"), 1e-9);

    // Full negative output: -32442 counts is -0.99998
    var reverse = decoder.decode(0x0205B800, new byte[] {0x46, (byte) 0x81, 0, 0, 0, 0, 0, 0});
    assertEquals(-32442 * 1.01 / 32767, reverse.get("AppliedOutput"), 1e-12);
    assertTrue(decoder.decode(0x02051810, new byte[8]).isEmpty(), "legacy status 0");
  }

  @Test
  void testGetConfigDir() {
    Path configDir = DbcLoader.getConfigDir();
    assertNotNull(configDir);
    assertTrue(configDir.isAbsolute());

    String os = System.getProperty("os.name", "").toLowerCase();
    if (os.contains("mac")) {
      assertTrue(configDir.toString().contains("Library/Application Support"));
    } else if (os.contains("win")) {
      assertTrue(configDir.toString().contains("wpilog-mcp"));
    } else {
      assertTrue(configDir.toString().contains(".config"));
    }
  }

  @Test
  void testGetSourceDescriptionWithOverride(@TempDir Path tempDir) throws IOException {
    Path overrideFile = tempDir.resolve("override.dbc");
    Files.writeString(overrideFile, "VERSION \"\"");

    String desc = loader.getSourceDescription(overrideFile.toString());
    assertTrue(desc.startsWith("Command-line:"));
    assertTrue(desc.contains(overrideFile.toString()));
  }

  @Test
  void testGetSourceDescriptionNoOverride() {
    String desc = loader.getSourceDescription(null);
    // Should fall through to embedded
    assertTrue(desc.contains("Embedded") || desc.contains("Config") || desc.contains("Environment"),
        "Expected description to indicate source: " + desc);
  }

  @Test
  void testGetSourceDescriptionNonexistentOverride() {
    String desc = loader.getSourceDescription("/nonexistent/path.dbc");
    // Should fall through since file doesn't exist
    assertFalse(desc.contains("Command-line"));
  }
}
