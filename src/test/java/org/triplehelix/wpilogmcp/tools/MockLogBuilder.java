package org.triplehelix.wpilogmcp.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * Utility for programmatically building Mock ParsedLog objects for unit testing.
 *
 * <p>Provides both a fluent builder API and static factory methods for common scenarios.
 */
public class MockLogBuilder {
  private String path = "/mock/test.wpilog";
  private final Map<String, EntryInfo> entries = new HashMap<>();
  private final Map<String, List<TimestampedValue>> values = new HashMap<>();
  private double minTimestamp = Double.MAX_VALUE;
  private double maxTimestamp = Double.NEGATIVE_INFINITY;
  private int nextId = 1;

  public MockLogBuilder setPath(String path) {
    this.path = path;
    return this;
  }

  public MockLogBuilder addEntry(String name, String type, List<TimestampedValue> data) {
    entries.put(name, new EntryInfo(nextId++, name, type, ""));
    values.put(name, new ArrayList<>(data));
    for (var tv : data) {
      minTimestamp = Math.min(minTimestamp, tv.timestamp());
      maxTimestamp = Math.max(maxTimestamp, tv.timestamp());
    }
    return this;
  }

  public MockLogBuilder addNumericEntry(String name, double[] timestamps, double[] data) {
    var tvs = new ArrayList<TimestampedValue>();
    for (int i = 0; i < timestamps.length; i++) {
      tvs.add(new TimestampedValue(timestamps[i], data[i]));
    }
    return addEntry(name, "double", tvs);
  }

  /** Adds a boolean entry from parallel arrays. */
  public MockLogBuilder addBooleanEntry(String name, double[] timestamps, boolean[] data) {
    var tvs = new ArrayList<TimestampedValue>();
    for (int i = 0; i < timestamps.length; i++) {
      tvs.add(new TimestampedValue(timestamps[i], data[i]));
    }
    return addEntry(name, "boolean", tvs);
  }

  /** Generates periodic numeric data from a function. */
  public MockLogBuilder addPeriodicEntry(String name, double startTime, double endTime,
      double periodSec, DoubleUnaryOperator valueFunc) {
    var tvs = new ArrayList<TimestampedValue>();
    for (double t = startTime; t <= endTime; t += periodSec) {
      tvs.add(new TimestampedValue(t, valueFunc.applyAsDouble(t)));
    }
    return addEntry(name, "double", tvs);
  }

  /** Adds a struct-typed entry (Map values) from parallel arrays. */
  public MockLogBuilder addStructEntry(String name, String type, double[] timestamps,
      List<Map<String, Object>> maps) {
    var tvs = new ArrayList<TimestampedValue>();
    for (int i = 0; i < timestamps.length; i++) {
      tvs.add(new TimestampedValue(timestamps[i], maps.get(i)));
    }
    return addEntry(name, type, tvs);
  }

  public ParsedLog build() {
    if (entries.isEmpty()) {
      return new ParsedLog(path, new HashMap<>(), new HashMap<>(), 0.0, 0.0);
    }
    return new ParsedLog(path, entries, values, minTimestamp, maxTimestamp);
  }

  // ==================== Factory Methods ====================

  /** Creates a standard mock log with DriverStation state for testing timelines and cycles. */
  public static ParsedLog createStandardMatchLog() {
    var builder = new MockLogBuilder();

    // 0-15s Auto, 15-135s Teleop
    var enabledValues = new ArrayList<TimestampedValue>();
    enabledValues.add(new TimestampedValue(0.0, true));
    enabledValues.add(new TimestampedValue(135.0, false));

    var autoValues = new ArrayList<TimestampedValue>();
    autoValues.add(new TimestampedValue(0.0, true));
    autoValues.add(new TimestampedValue(15.0, false));

    return builder
        .addEntry("/DriverStation/Enabled", "boolean", enabledValues)
        .addEntry("/DriverStation/Autonomous", "boolean", autoValues)
        .build();
  }

  /**
   * Creates a full clean match log with realistic telemetry data.
   * Includes DS data, battery voltage, and periodic sensor data over 160s.
   */
  public static ParsedLog createCleanMatchLog() {
    var builder = new MockLogBuilder().setPath("/mock/clean_match.wpilog");

    // DS state: 20s auto, 2s disabled gap, 138s teleop (REBUILT timing)
    builder.addBooleanEntry("/DriverStation/Enabled",
        new double[]{0, 20, 22, 160}, new boolean[]{true, false, true, false});
    builder.addBooleanEntry("/DriverStation/Autonomous",
        new double[]{0, 20}, new boolean[]{true, false});

    // Battery voltage: healthy, slight sag under load
    builder.addPeriodicEntry("/Robot/BatteryVoltage", 0, 160, 0.02,
        t -> 12.5 - 0.8 * Math.sin(t * 0.05) * Math.sin(t * 0.05) + 0.1 * Math.random());

    // Drive velocity
    builder.addPeriodicEntry("/Drive/Velocity", 0, 160, 0.02,
        t -> t < 20 ? 2.0 * Math.sin(t * 0.3) : 3.0 * Math.sin(t * 0.2));

    // Loop time (~20ms with occasional spikes)
    builder.addPeriodicEntry("/RobotCode/LoopTime", 0, 160, 0.02,
        t -> 0.018 + 0.002 * Math.random());

    return builder.build();
  }

  /**
   * Creates a match log with brownout events.
   * Voltage drops below 6.8V during heavy current draw periods.
   */
  public static ParsedLog createBrownoutMatchLog() {
    var builder = new MockLogBuilder().setPath("/mock/brownout_match.wpilog");

    builder.addBooleanEntry("/DriverStation/Enabled",
        new double[]{0, 160}, new boolean[]{true, false});
    builder.addBooleanEntry("/DriverStation/Autonomous",
        new double[]{0, 20}, new boolean[]{true, false});

    // Voltage with brownout events at ~60s and ~120s
    builder.addPeriodicEntry("/Robot/BatteryVoltage", 0, 160, 0.02, t -> {
      if (t > 58 && t < 62) return 6.0 + 0.5 * (t - 58); // brownout #1
      if (t > 118 && t < 121) return 5.5 + 1.0 * (t - 118); // brownout #2
      return 12.0 - 0.5 * Math.abs(Math.sin(t * 0.04));
    });

    // Correlated high current
    builder.addPeriodicEntry("/PowerDistribution/TotalCurrent", 0, 160, 0.02, t -> {
      if (t > 58 && t < 62) return 120 + 30 * Math.random();
      if (t > 118 && t < 121) return 140 + 20 * Math.random();
      return 30 + 20 * Math.abs(Math.sin(t * 0.04));
    });

    return builder.build();
  }

  /**
   * Creates a log with 4 swerve modules (measured + setpoint states).
   * Includes one module with intentional slip for testing.
   */
  public static ParsedLog createSwerveModuleLog() {
    var builder = new MockLogBuilder().setPath("/mock/swerve.wpilog");

    int samples = 500;
    double period = 0.02;

    for (int mod = 0; mod < 4; mod++) {
      String prefix = "/Drive/Module" + mod;

      // Setpoint states
      var setpointTs = new double[samples];
      var setpointMaps = new ArrayList<Map<String, Object>>();

      // Measured states (module 2 has slip — measured speed trails setpoint)
      var measuredTs = new double[samples];
      var measuredMaps = new ArrayList<Map<String, Object>>();

      for (int i = 0; i < samples; i++) {
        double t = i * period;
        setpointTs[i] = t;
        measuredTs[i] = t;

        double speed = 2.0 * Math.sin(t * 0.5);
        double angle = t * 0.3;

        var setpoint = new LinkedHashMap<String, Object>();
        setpoint.put("speed_mps", speed);
        setpoint.put("angle_rad", angle);
        setpointMaps.add(setpoint);

        var measured = new LinkedHashMap<String, Object>();
        // Module 2 has slip: measured speed is 70% of commanded
        double measuredSpeed = (mod == 2) ? speed * 0.7 : speed + 0.05 * Math.random();
        measured.put("speed_mps", measuredSpeed);
        measured.put("angle_rad", (mod == 3) ? angle + 0.2 : angle + 0.01 * Math.random());
        measuredMaps.add(measured);
      }

      builder.addStructEntry(prefix + "/Setpoint", "struct:SwerveModuleState",
          setpointTs, setpointMaps);
      builder.addStructEntry(prefix + "/Measured", "struct:SwerveModuleState",
          measuredTs, measuredMaps);
    }

    // Also add an odometry pose that drifts and a vision pose that doesn't
    var odomTs = new double[samples];
    var odomMaps = new ArrayList<Map<String, Object>>();
    var visionTs = new double[samples / 5]; // Vision at lower rate
    var visionMaps = new ArrayList<Map<String, Object>>();

    for (int i = 0; i < samples; i++) {
      double t = i * period;
      odomTs[i] = t;
      double trueX = 2.0 * Math.sin(t * 0.3);
      double trueY = 1.5 * Math.cos(t * 0.3);

      // Odometry drifts 0.01m per second
      var odomPose = makePose2d(trueX + t * 0.01, trueY + t * 0.005);
      odomMaps.add(odomPose);

      if (i % 5 == 0) {
        visionTs[i / 5] = t;
        visionMaps.add(makePose2d(trueX, trueY)); // Vision is ground truth
      }
    }

    builder.addStructEntry("/Odometry/Pose", "struct:Pose2d", odomTs, odomMaps);
    builder.addStructEntry("/Vision/Pose", "struct:Pose2d", visionTs, visionMaps);

    return builder.build();
  }

  /**
   * Creates a log with intentional quality issues: gaps, NaN values, low sample count.
   */
  public static ParsedLog createLowQualityLog() {
    var builder = new MockLogBuilder().setPath("/mock/low_quality.wpilog");

    var tvs = new ArrayList<TimestampedValue>();
    for (int i = 0; i < 40; i++) {
      double t = i * 0.02;
      // Skip samples 15-25 (gap from 0.3s to 0.5s)
      if (i >= 15 && i <= 25) continue;
      // Insert NaN at samples 5 and 30
      double val = (i == 5 || i == 30) ? Double.NaN : Math.sin(t);
      tvs.add(new TimestampedValue(t, val));
    }
    builder.addEntry("/Sensor/Noisy", "double", tvs);

    return builder.build();
  }

  /**
   * Creates a log with vision data including target flicker and pose jumps.
   */
  public static ParsedLog createVisionLog() {
    var builder = new MockLogBuilder().setPath("/mock/vision.wpilog");

    int samples = 200;
    // HasTarget: flickers at ~1s intervals
    var hasTvs = new ArrayList<TimestampedValue>();
    for (int i = 0; i < samples; i++) {
      double t = i * 0.02;
      boolean hasTarget = !(t > 1.0 && t < 1.1) && !(t > 2.5 && t < 2.6);
      hasTvs.add(new TimestampedValue(t, hasTarget));
    }
    builder.addEntry("/Vision/HasTarget", "boolean", hasTvs);

    // Pose with jump at t=2.0
    var poseTvs = new ArrayList<TimestampedValue>();
    for (int i = 0; i < samples; i++) {
      double t = i * 0.02;
      double x = (t > 2.0) ? 5.0 : 1.0 + t * 0.1; // 4m jump at t=2
      poseTvs.add(new TimestampedValue(t, makePose2d(x, 2.0)));
    }
    builder.addEntry("/Vision/Pose", "struct:Pose2d", poseTvs);

    return builder.build();
  }

  // ==================== Helpers ====================

  /** Creates a Pose2d-like map with translation and rotation. */
  public static Map<String, Object> makePose2d(double x, double y) {
    return makePose2d(x, y, 0.0);
  }

  public static Map<String, Object> makePose2d(double x, double y, double rotationRad) {
    var pose = new LinkedHashMap<String, Object>();
    var translation = new LinkedHashMap<String, Object>();
    translation.put("x", x);
    translation.put("y", y);
    pose.put("translation", translation);
    var rotation = new LinkedHashMap<String, Object>();
    rotation.put("value", rotationRad);
    pose.put("rotation", rotation);
    return pose;
  }
}
