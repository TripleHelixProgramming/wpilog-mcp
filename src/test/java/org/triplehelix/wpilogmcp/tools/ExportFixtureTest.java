/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** export_csv paths, flattening, and inline mode (review issue D3). */
@DisplayName("export_csv on fixture logs")
class ExportFixtureTest extends FixtureToolTestBase {

  @TempDir static Path exportDir;
  static Path saved;

  @BeforeAll
  static void useTempExportDir() {
    saved = ExportTools.getExportDirectory();
    ExportTools.setExportDirectory(exportDir.toString());
  }

  @AfterAll
  static void restore() {
    ExportTools.setExportDirectory(saved.toString());
  }

  @Test
  @DisplayName("a bare name is written inside the export directory; the absolute path is returned")
  void bareName() throws Exception {
    var r = call("export_csv", "akit_match", "name", "/SystemStats/BatteryVoltage",
        "output_path", "battery.csv");
    assertEquals("ok", r.get("status").getAsString(), r.toString());
    var written = Path.of(r.get("output_path").getAsString());
    assertTrue(written.isAbsolute());
    assertEquals(exportDir.toRealPath().resolve("battery.csv"), written);
    assertTrue(Files.exists(written));
    assertEquals(exportDir.toRealPath().toString(), r.get("export_directory").getAsString());
  }

  @Test
  @DisplayName("a relative path creates subdirectories inside the export directory")
  void relativePath() throws Exception {
    var r = call("export_csv", "akit_match", "name", "/SystemStats/BatteryVoltage",
        "output_path", "run1/battery.csv");
    assertEquals(exportDir.toRealPath().resolve("run1/battery.csv").toString(),
        r.get("output_path").getAsString());
  }

  @Test
  @DisplayName("omitting output_path generates a name from the log and entry")
  void generatedName() {
    var r = call("export_csv", "akit_match", "name", "/RealOutputs/Drive/Pose");
    assertTrue(r.get("output_path").getAsString().endsWith(
        "2026-akit_match__RealOutputs_Drive_Pose.csv"), r.toString());
  }

  @Test
  @DisplayName("an absolute path outside is refused, naming the real export directory")
  void outsideRefused() throws Exception {
    var r = call("export_csv", "akit_match", "name", "/SystemStats/BatteryVoltage",
        "output_path", "/tmp/elsewhere/battery.csv");
    assertEquals("error", r.get("status").getAsString());
    var error = r.get("error").getAsString();
    assertTrue(error.contains("not allowed"), error);
    assertTrue(error.contains(exportDir.toRealPath().toString()), error);
    var escape = call("export_csv", "akit_match", "name", "/SystemStats/BatteryVoltage",
        "output_path", "../escape.csv");
    assertEquals("error", escape.get("status").getAsString());
  }

  @Test
  @DisplayName("struct arrays: index plus every field, header and rows aligned")
  void structArrayColumns() throws Exception {
    var r = call("export_csv", "swerve_array", "name", "/RealOutputs/Odometry/Trajectory",
        "output_path", "trajectory.csv");
    var lines = Files.readAllLines(Path.of(r.get("output_path").getAsString()));
    var header = lines.get(0).split(",");
    assertEquals("timestamp_sec", header[0]);
    assertEquals("index", header[1]);
    for (int i = 1; i < lines.size(); i++) {
      assertEquals(header.length, lines.get(i).split(",", -1).length, "row " + i + " aligned");
    }
    assertEquals(3, lines.size()); // header + two poses
    assertFalse(String.join("\n", lines).contains("[D@"));
  }

  @Test
  @DisplayName("inline mode returns columns and rows without writing a file, with limits")
  void inline() {
    var r = call("export_csv", "akit_match", "name", "/SystemStats/BatteryVoltage",
        "inline", true, "max_rows", 3);
    assertFalse(r.has("output_path"));
    assertEquals(3, r.getAsJsonArray("rows").size());
    var limits = r.getAsJsonObject("limits").getAsJsonObject("rows");
    assertTrue(limits.get("total").getAsInt() > 3);
    assertEquals("timestamp_sec", r.getAsJsonArray("columns").get(0).getAsString());
    assertEquals("value", r.getAsJsonArray("columns").get(1).getAsString());
  }
}
