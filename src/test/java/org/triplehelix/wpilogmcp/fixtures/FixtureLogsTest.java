/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.fixtures;

import static org.junit.jupiter.api.Assertions.*;

import edu.wpi.first.util.datalog.DataLogReader;
import edu.wpi.first.util.struct.StructDescriptorDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs.Fixture;
import org.triplehelix.wpilogmcp.log.LazyParsedLog;

/**
 * Checks the fixture corpus itself, reading the files back with WPILib's own reader so the
 * fixtures can be trusted independently of the server's parser.
 */
@DisplayName("Fixture corpus")
class FixtureLogsTest {

  /** Entries with records undecodable on purpose (no schema; records cut short). */
  static final java.util.Set<String> UNDECODABLE = java.util.Set.of(
      "struct_custom /RealOutputs/Mystery", "struct_custom /RealOutputs/Arm/Partial");

  @TempDir static Path dir;
  static List<Fixture> fixtures;

  @BeforeAll
  static void generate() throws Exception {
    fixtures = FixtureLogs.generateAll(dir.resolve("fixtures"));
  }

  /** Entry name → type, and entry name → sample count, read with WPILib's reader. */
  record Contents(Map<String, String> types, Map<String, Integer> counts,
      Map<String, String> schemas, List<String> schemaOrder) {}

  static Contents read(Path path) throws Exception {
    var reader = new DataLogReader(path.toString());
    assertTrue(reader.isValid(), "not a valid WPILOG: " + path);
    var names = new HashMap<Integer, String>();
    var types = new LinkedHashMap<String, String>();
    var counts = new HashMap<String, Integer>();
    var schemas = new HashMap<String, String>();
    var schemaOrder = new ArrayList<String>();
    try {
      // forEachRemaining bounds each record by the file size; the for-each form (hasNext) skips
      // a final record shorter than 16 bytes, which is the bug the parsers work around
      reader.iterator().forEachRemaining(record -> {
        if (record.isStart()) {
          var data = record.getStartData();
          names.put(data.entry, data.name);
          types.put(data.name, data.type);
          if (data.name.startsWith("/.schema/")) schemaOrder.add(data.name);
        } else if (!record.isFinish() && !record.isSetMetadata() && !record.isControl()) {
          var name = names.get(record.getEntry());
          if (name == null) return;
          counts.merge(name, 1, Integer::sum);
          if (name.startsWith("/.schema/")) {
            schemas.put(name, new String(record.getRaw(), StandardCharsets.UTF_8));
          }
        }
      });
    } catch (RuntimeException truncatedTail) {
      // the truncated fixture ends mid-record; everything before it is still counted
    }
    return new Contents(types, counts, schemas, schemaOrder);
  }

  static Fixture fixture(String id) {
    return fixtures.stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
  }

  @Test
  @DisplayName("every fixture has a unique id, a year-prefixed file, and a description")
  void fixturesAreWellFormed() {
    assertEquals(fixtures.size(), fixtures.stream().map(Fixture::id).distinct().count());
    for (var f : fixtures) {
      assertTrue(Files.exists(f.path()), f.id());
      assertTrue(f.path().getFileName().toString().matches("20\\d\\d-.*\\.wpilog"), f.id());
      assertFalse(f.description().isBlank(), f.id());
    }
  }

  @Test
  @DisplayName("generation is deterministic (byte-identical across runs)")
  void generationIsDeterministic(@TempDir Path other) throws Exception {
    var second = FixtureLogs.generateAll(other);
    for (int i = 0; i < fixtures.size(); i++) {
      assertArrayEquals(Files.readAllBytes(fixtures.get(i).path()),
          Files.readAllBytes(second.get(i).path()), fixtures.get(i).id());
    }
  }

  @Test
  @DisplayName("every fixture opens in LazyParsedLog")
  void everyFixtureLoads() throws Exception {
    for (var f : fixtures) {
      try (var log = new LazyParsedLog(f.path().toString(), new DataLogReader(f.path().toString()),
          50_000_000)) {
        if (f.id().equals("empty")) {
          assertEquals(0, log.entryCount());
        } else {
          assertTrue(log.entryCount() > 0, f.id());
        }
        assertEquals(f.id().equals("truncated"), log.truncated(), f.id());
      }
    }
  }

  @Test
  @DisplayName("every entry decodes through LazyParsedLog to exactly the records WPILib reads")
  void everyRecordDecodes() throws Exception {
    for (var f : fixtures) {
      var expected = read(f.path()).counts();
      try (var log = new LazyParsedLog(f.path().toString(), new DataLogReader(f.path().toString()),
          200_000_000)) {
        for (var name : log.entries().keySet()) {
          int want = expected.getOrDefault(name, 0);
          assertEquals(want, log.sampleCount(name), f.id() + " " + name + " (offsets)");
          int decoded = log.values().get(name).size();
          var problem = log.decodeProblem(name);
          if (problem.isPresent()) {
            // only the deliberately undecodable entries, and every missing record accounted for
            assertTrue(UNDECODABLE.contains(f.id() + " " + name), f.id() + " " + name + ": "
                + problem.get().describe());
            assertEquals(want, decoded + problem.get().failedRecords(), f.id() + " " + name);
          } else {
            assertEquals(want, decoded, f.id() + " " + name + " (decoded)");
          }
        }
      }
    }
  }

  @Test
  @DisplayName("AdvantageKit fixtures tag entries with AdvantageKit metadata")
  void akitMetadata() throws Exception {
    var reader = new DataLogReader(fixture("akit_match").path().toString());
    int starts = 0;
    for (var record : reader) {
      if (record.isStart()) {
        starts++;
        assertEquals(FixtureWriter.AKIT_METADATA, record.getStartData().metadata);
      }
    }
    assertTrue(starts > 30);
  }

  @Test
  @DisplayName("practice fixture: Autonomous logged once as false; Enabled has 4 rising edges")
  void practiceTimeline() throws Exception {
    var c = read(fixture("akit_practice").path());
    assertEquals(1, c.counts().get("/DriverStation/Autonomous"));
    // one initial false + 4 enables + 3 disables (the last segment runs to the end of the log)
    assertEquals(8, c.counts().get("/DriverStation/Enabled"));
    assertEquals(5, c.counts().get("/SystemStats/BrownedOut"));
    assertEquals("struct:SwerveModuleState[]", c.types().get("/RealOutputs/SwerveStates/Measured"));
  }

  @Test
  @DisplayName("WPILib-serialized structs carry their schemas, including nested ones")
  void wpilibSchemasPresent() throws Exception {
    var c = read(fixture("akit_match").path());
    for (var name : List.of("Pose2d", "Translation2d", "Rotation2d", "SwerveModuleState",
        "ChassisSpeeds")) {
      assertTrue(c.schemas().containsKey("/.schema/struct:" + name), name);
      assertEquals("structschema", c.types().get("/.schema/struct:" + name), name);
    }
    assertEquals("Translation2d translation;Rotation2d rotation",
        c.schemas().get("/.schema/struct:Pose2d"));
  }

  @Test
  @DisplayName("custom struct fixture: out-of-order schemas describe the written bytes")
  void customStructsRoundTrip() throws Exception {
    var c = read(fixture("struct_custom").path());
    // ShotRecord and ArmState are declared before the Rotation2d they depend on
    assertTrue(c.schemaOrder().indexOf("/.schema/struct:ShotRecord")
        < c.schemaOrder().indexOf("/.schema/struct:Rotation2d"));
    var db = new StructDescriptorDatabase();
    for (var name : c.schemaOrder()) {
      db.add(name.substring("/.schema/struct:".length()), c.schemas().get(name));
    }
    var arm = db.find("ArmState");
    assertTrue(arm.isValid());
    assertEquals(30, arm.getSize()); // 8 + 16 + 1 + 1 (flags:3 and homed:1 share a byte) + 4
    assertEquals(3, arm.findFieldByName("flags").getBitWidth());
    assertEquals(Map.of("STOWED", 0L, "SCORING", 1L, "INTAKE", 2L),
        arm.findFieldByName("mode").getEnumValues());
    assertEquals(arm.getSize() + 12, db.find("ShotRecord").getSize());
    assertNull(c.schemas().get("/.schema/struct:Mystery"));
  }

  @Test
  @DisplayName("layout-mismatch fixture: PoseObservation schema is 96 bytes, records are 2 x 96")
  void layoutMismatch() throws Exception {
    var c = read(fixture("struct_layout_mismatch").path());
    var db = new StructDescriptorDatabase();
    for (var name : c.schemaOrder()) {
      db.add(name.substring("/.schema/struct:".length()), c.schemas().get(name));
    }
    assertEquals(96, db.find("PoseObservation").getSize());
  }

  @Test
  @DisplayName("vision fixture: the template PoseObservation schema is 88 bytes")
  void photonObservationSize() throws Exception {
    var c = read(fixture("vision_photon_akit").path());
    var db = new StructDescriptorDatabase();
    for (var name : c.schemaOrder()) {
      db.add(name.substring("/.schema/struct:".length()), c.schemas().get(name));
    }
    assertEquals(88, db.find("PoseObservation").getSize());
    assertEquals(40, db.find("TargetObservation").getSize());
    assertTrue(c.counts().get("/Vision/Camera0/PoseObservations")
        > c.counts().get("/Vision/Camera1/PoseObservations"));
  }

  @Test
  @DisplayName("dual DS fixture has both naming conventions")
  void dualDs() throws Exception {
    var c = read(fixture("dual_ds").path());
    assertTrue(c.types().containsKey("DS:enabled"));
    assertTrue(c.types().containsKey("/DriverStation/Enabled"));
  }

  @Test
  @DisplayName("no-DS fixture has no DriverStation entries")
  void noDs() throws Exception {
    var c = read(fixture("no_ds").path());
    assertTrue(c.types().keySet().stream()
        .noneMatch(n -> n.startsWith("DS:") || n.contains("DriverStation")));
  }
}
