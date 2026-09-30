/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import edu.wpi.first.util.datalog.DataLogReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.log.struct.EnumValue;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.log.subsystems.LogParser;

/** Struct entries of fixture logs decode by the logs' own schemas, lazily and eagerly alike. */
@DisplayName("Struct decoding on fixture logs")
class StructDecodingFixtureTest {

  static final Map<String, Path> paths = new HashMap<>();

  @BeforeAll
  static void generate() throws IOException {
    for (var f : FixtureLogs.generateAll(FixtureLogs.defaultDirectory())) paths.put(f.id(), f.path());
  }

  static LazyParsedLog lazy(String id) throws IOException {
    var path = paths.get(id).toString();
    return new LazyParsedLog(path, new DataLogReader(path), 200_000_000);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Object value) {
    assertInstanceOf(Map.class, value, String.valueOf(value));
    return (Map<String, Object>) value;
  }

  static double loopTime(int i) {
    return Math.round((1.0 + i * FixtureLogs.LOOP) * 1e6) / 1e6;
  }

  static void assertArm(Map<String, Object> arm, double angle, double c0, double c1, int mode,
      String label, int flags, boolean homed, float temperature) {
    assertEquals(angle, (Double) map(arm.get("angle")).get("value"), 1e-12);
    assertEquals(List.of(c0, c1), arm.get("currents"));
    assertEquals(new EnumValue(mode, label), arm.get("mode"));
    assertEquals((long) flags, arm.get("flags"));
    assertEquals(homed, arm.get("homed"));
    assertEquals(temperature, arm.get("temperature"));
  }

  @Test
  @DisplayName("custom structs: nested, fixed arrays, enums, bit-fields, out-of-order schemas")
  void customStructs() throws Exception {
    try (var log = lazy("struct_custom")) {
      var schemas = log.structSchemas();
      assertEquals(List.of("ShotRecord", "ArmState", "Rotation2d"), schemas.loggedStructs());
      assertEquals(StructSchemas.Source.LOGGED, schemas.source("ArmState"));
      assertEquals(30, schemas.info("ArmState").orElseThrow().size());

      var states = log.values().get("/RealOutputs/Arm/State");
      assertEquals(log.sampleCount("/RealOutputs/Arm/State"), states.size());
      assertTrue(log.decodeProblem("/RealOutputs/Arm/State").isEmpty());
      double t0 = loopTime(0);
      assertArm(map(states.get(0).value()), 0.5 * Math.sin(0.2 * t0), 10.0 + t0, 11.0 + t0, 0,
          "STOWED", 0, false, (float) (30.0 + 0.1 * t0));
      double t300 = loopTime(300);
      assertArm(map(states.get(300).value()), 0.5 * Math.sin(0.2 * t300), 10.0 + t300,
          11.0 + t300, 1, "SCORING", 4, true, (float) (30.0 + 0.1 * t300));
      double t600 = loopTime(600);
      assertArm(map(states.get(600).value()), 0.5 * Math.sin(0.2 * t600), 10.0 + t600,
          11.0 + t600, 2, "INTAKE", 0, true, (float) (30.0 + 0.1 * t600));

      var arrays = log.values().get("/RealOutputs/Arm/States");
      var pair = (List<?>) arrays.get(1).value(); // i = 10
      assertEquals(2, pair.size());
      double t10 = loopTime(10);
      assertArm(map(pair.get(0)), 0.5 * Math.sin(0.2 * t10), 10.0 + t10, 11.0 + t10, 0,
          "STOWED", 2, false, (float) (30.0 + 0.1 * t10));
      assertArm(map(pair.get(1)), -0.25, 1.0, 2.0, 1, "SCORING", 5, true, 41.5f);

      var shots = log.values().get("/RealOutputs/Shots/Last");
      assertEquals(2.5, map(shots.get(0).value()).get("distanceMeters"));
      assertEquals(1L, map(shots.get(0).value()).get("shotId"));
      assertEquals(2L, map(shots.get(1).value()).get("shotId"));
      double t125 = loopTime(125);
      assertArm(map(map(shots.get(0).value()).get("arm")), 0.5 * Math.sin(0.2 * t125),
          10.0 + t125, 11.0 + t125, 0, "STOWED", 5, true, (float) (30.0 + 0.1 * t125));

      // the logged schema text is readable as text
      assertEquals(FixtureLogs.ARM_STATE_SCHEMA,
          log.values().get("/.schema/struct:ArmState").get(0).value());
    }
  }

  @Test
  @DisplayName("a struct with no schema anywhere is reported, not silently empty")
  void mysteryStruct() throws Exception {
    try (var log = lazy("struct_custom")) {
      assertEquals(List.of(), log.values().get("/RealOutputs/Mystery"));
      var problem = log.decodeProblem("/RealOutputs/Mystery").orElseThrow();
      assertTrue(problem.allFailed());
      assertEquals(1, problem.failedRecords());
      assertTrue(problem.message().contains("no schema for struct Mystery"), problem.message());
      assertTrue(log.decodeProblem("/no/such/entry").isEmpty());
    }
  }

  @Test
  @DisplayName("a short final record is read (WPILib's for-each iterator skips it)")
  void shortFinalRecord() throws Exception {
    // /RealOutputs/Mystery is the file's last record: 10 bytes, under hasNext()'s 16
    try (var log = lazy("struct_custom")) {
      assertEquals(1, log.sampleCount("/RealOutputs/Mystery"));
    }
    var eager = new LogParser().parse(paths.get("struct_custom"));
    assertEquals(1, eager.decodeProblem("/RealOutputs/Mystery").orElseThrow().failedRecords());
  }

  @Test
  @DisplayName("a team PoseObservation with an extra field decodes by its logged 96-byte schema")
  void layoutMismatch() throws Exception {
    try (var log = lazy("struct_layout_mismatch")) {
      var name = "/Vision/Camera0/PoseObservations";
      assertTrue(log.decodeProblem(name).isEmpty(),
          () -> log.decodeProblem(name).orElseThrow().describe());
      var first = (List<?>) log.values().get(name).get(0).value();
      assertEquals(2, first.size());
      var obs = map(first.get(1));
      assertEquals(4.0, map(map(obs.get("pose")).get("translation")).get("x"));
      assertEquals(0.25, obs.get("stdDevXY"));
      assertEquals(2L, obs.get("tagCount"));
      assertEquals(3.0, obs.get("averageTagDistance"));
      assertEquals(new EnumValue(1, "MEGATAG_2"), obs.get("type"));
      assertEquals(StructSchemas.Source.LOGGED, log.structSchemas().source("PoseObservation"));
    }
  }

  @Test
  @DisplayName("the AdvantageKit vision template decodes, with Rotation3d angles derived")
  void visionTemplate() throws Exception {
    try (var log = lazy("vision_photon_akit")) {
      var observations = log.values().get("/Vision/Camera0/PoseObservations");
      assertFalse(observations.isEmpty());
      var obs = map(((List<?>) observations.get(0).value()).get(0));
      assertEquals(new EnumValue(2, "PHOTONVISION"), obs.get("type"));
      var rotation = map(map(obs.get("pose")).get("rotation"));
      assertTrue(map(rotation.get("_derived")).containsKey("yaw_deg"), rotation.toString());
      var target = map(log.values().get("/Vision/Camera1/LatestTargetObservation").get(0).value());
      assertEquals(0.95f, target.get("confidence"));
      assertEquals(8L, target.get("objectID"));
      assertEquals(0.1, (Double) map(target.get("yaw")).get("value"), 1e-12);
    }
  }

  @Test
  @DisplayName("eager parsing decodes every entry exactly as lazy decoding does")
  void eagerMatchesLazy() throws Exception {
    for (var id : List.of("struct_custom", "struct_layout_mismatch", "vision_photon_akit",
        "swerve_array", "akit_match")) {
      var eager = new LogParser().parse(paths.get(id));
      try (var lazy = lazy(id)) {
        assertEquals(lazy.entries().keySet(), eager.entries().keySet(), id);
        for (var name : lazy.entries().keySet()) {
          var a = lazy.values().get(name);
          var b = eager.values().get(name);
          assertEquals(a.size(), b.size(), id + " " + name);
          for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).timestamp(), b.get(i).timestamp(), id + " " + name);
            assertTrue(Objects.deepEquals(a.get(i).value(), b.get(i).value()),
                id + " " + name + " [" + i + "]");
          }
          assertEquals(lazy.decodeProblem(name), eager.decodeProblem(name), id + " " + name);
        }
        assertEquals(lazy.structSchemas().loggedStructs(), eager.structSchemas().loggedStructs());
      }
    }
  }
}
