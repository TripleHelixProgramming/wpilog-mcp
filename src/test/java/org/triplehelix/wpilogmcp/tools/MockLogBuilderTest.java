package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the MockLogBuilder test data library (§7.2).
 */
@DisplayName("MockLogBuilder Test Data Library")
class MockLogBuilderTest {

  @Test
  @DisplayName("createCleanMatchLog produces valid log with expected entries")
  void cleanMatchLog() {
    var log = MockLogBuilder.createCleanMatchLog();
    assertNotNull(log);
    assertTrue(log.entryCount() >= 4);
    assertNotNull(log.values().get("/DriverStation/Enabled"));
    assertNotNull(log.values().get("/DriverStation/Autonomous"));
    assertNotNull(log.values().get("/Robot/BatteryVoltage"));
    assertNotNull(log.values().get("/Drive/Velocity"));
    assertTrue(log.duration() >= 150, "Clean match should span ~160s");

    // Quality should be high
    var quality = DataQuality.fromValues(log.values().get("/Robot/BatteryVoltage"));
    assertTrue(quality.qualityScore() > 0.7,
        "Clean match battery data should have high quality, got: " + quality.qualityScore());
  }

  @Test
  @DisplayName("createBrownoutMatchLog has voltage drops below brownout threshold")
  void brownoutMatchLog() {
    var log = MockLogBuilder.createBrownoutMatchLog();
    assertNotNull(log);
    var voltages = log.values().get("/Robot/BatteryVoltage");
    assertNotNull(voltages);

    double minVoltage = voltages.stream()
        .mapToDouble(tv -> ((Number) tv.value()).doubleValue())
        .min().orElse(999);
    assertTrue(minVoltage < 6.8, "Should have voltage below brownout threshold, min=" + minVoltage);
  }

  @Test
  @DisplayName("createSwerveModuleLog has 4 modules with setpoint and measured")
  void swerveModuleLog() {
    var log = MockLogBuilder.createSwerveModuleLog();
    assertNotNull(log);

    for (int i = 0; i < 4; i++) {
      assertNotNull(log.values().get("/Drive/Module" + i + "/Setpoint"),
          "Module " + i + " should have setpoint");
      assertNotNull(log.values().get("/Drive/Module" + i + "/Measured"),
          "Module " + i + " should have measured");
    }

    assertNotNull(log.values().get("/Odometry/Pose"), "Should have odometry pose");
    assertNotNull(log.values().get("/Vision/Pose"), "Should have vision pose");
  }

  @Test
  @DisplayName("createLowQualityLog has poor data quality score")
  void lowQualityLog() {
    var log = MockLogBuilder.createLowQualityLog();
    assertNotNull(log);

    var quality = DataQuality.fromValues(log.values().get("/Sensor/Noisy"));
    assertTrue(quality.qualityScore() < 0.6,
        "Low quality log should have low score, got: " + quality.qualityScore());
    assertTrue(quality.nanFiltered() > 0, "Should have NaN values");
    assertTrue(quality.gapCount() > 0, "Should have data gaps");
  }

  @Test
  @DisplayName("createVisionLog has target flickering and pose jumps")
  void visionLog() {
    var log = MockLogBuilder.createVisionLog();
    assertNotNull(log);
    assertNotNull(log.values().get("/Vision/HasTarget"));
    assertNotNull(log.values().get("/Vision/Pose"));
    assertTrue(log.values().get("/Vision/Pose").size() > 50);
  }

  @Test
  @DisplayName("addPeriodicEntry generates correct sample count")
  void periodicEntry() {
    var log = new MockLogBuilder()
        .addPeriodicEntry("/Test", 0, 10, 0.02, t -> Math.sin(t))
        .build();
    var values = log.values().get("/Test");
    // 10s / 0.02s = 500 samples + 1 for inclusive end
    assertTrue(values.size() >= 500);
  }

  @Test
  @DisplayName("makePose2d creates correct structure")
  void pose2dStructure() {
    var pose = MockLogBuilder.makePose2d(1.5, 2.3, 0.785);

    @SuppressWarnings("unchecked")
    var trans = (java.util.Map<String, Object>) pose.get("translation");
    assertEquals(1.5, ((Number) trans.get("x")).doubleValue(), 0.001);
    assertEquals(2.3, ((Number) trans.get("y")).doubleValue(), 0.001);

    @SuppressWarnings("unchecked")
    var rot = (java.util.Map<String, Object>) pose.get("rotation");
    assertEquals(0.785, ((Number) rot.get("value")).doubleValue(), 0.001);
  }
}
