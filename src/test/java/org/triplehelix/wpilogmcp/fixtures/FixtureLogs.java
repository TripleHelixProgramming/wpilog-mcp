/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.fixtures;

import static org.triplehelix.wpilogmcp.fixtures.FixtureWriter.AKIT_METADATA;

import static org.triplehelix.wpilogmcp.fixtures.WpiStructs.CHASSIS_SPEEDS;
import static org.triplehelix.wpilogmcp.fixtures.WpiStructs.POSE2D;
import static org.triplehelix.wpilogmcp.fixtures.WpiStructs.POSE3D;
import static org.triplehelix.wpilogmcp.fixtures.WpiStructs.ROTATION2D;
import static org.triplehelix.wpilogmcp.fixtures.WpiStructs.SWERVE_MODULE_STATE;
import static org.triplehelix.wpilogmcp.fixtures.WpiStructs.chassisSpeeds;
import static org.triplehelix.wpilogmcp.fixtures.WpiStructs.pose2d;
import static org.triplehelix.wpilogmcp.fixtures.WpiStructs.pose3d;
import static org.triplehelix.wpilogmcp.fixtures.WpiStructs.rotation2d;
import static org.triplehelix.wpilogmcp.fixtures.WpiStructs.swerveModuleState;

import edu.wpi.first.util.struct.DynamicStruct;
import edu.wpi.first.util.struct.StructDescriptorDatabase;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The fixture corpus: small, real {@code .wpilog} files, one per logging convention a tool must
 * handle without seeing the code that produced the log (see {@code doc/ROBUSTNESS_REVIEW.md}
 * section 6).
 *
 * <p>Every fixture is deterministic: no randomness, no wall-clock time. Values are closed-form
 * functions of time so tests can assert exact expectations. File names start with the season year,
 * the last fallback of {@code MatchTimeline.seasonOf} for logs that record no date.
 */
public final class FixtureLogs {

  private FixtureLogs() {}

  /** Robot loop period (50 Hz). */
  public static final double LOOP = 0.02;

  /**
   * A generated fixture log.
   *
   * @param id Short identifier used in test names and the known-failures list
   * @param path The generated file
   * @param description What convention the fixture represents
   * @param exercises Review issue IDs the fixture exists to exercise
   */
  public record Fixture(String id, Path path, String description, List<String> exercises) {}

  /** Default directory for generated fixtures (under Gradle's build directory). */
  public static Path defaultDirectory() {
    return Path.of("build", "test-fixtures").toAbsolutePath();
  }

  /**
   * Generates every fixture into {@code dir}, replacing existing files.
   *
   * @param dir Target directory (created if missing)
   * @return The fixtures, in a stable order
   */
  public static List<Fixture> generateAll(Path dir) throws IOException {
    Files.createDirectories(dir);
    var all = new ArrayList<Fixture>();
    all.add(akitMatch(dir));
    all.add(akitPractice(dir));
    all.add(wpilibDataLogManager(dir));
    all.add(dlmRetained(dir));
    all.add(swervePerModule(dir));
    all.add(swerveArray(dir));
    all.add(visionLimelight(dir));
    all.add(visionPhotonAkit(dir));
    all.add(visionPose3dSingle(dir));
    all.add(structLayoutMismatch(dir));
    all.add(structCustom(dir));
    all.add(brownoutRio2(dir));
    all.add(brownoutRio1NoThreshold(dir));
    all.add(canivore(dir));
    all.add(alertsConsole(dir));
    all.add(replay(dir, false));
    all.add(replay(dir, true));
    all.add(truncated(dir));
    all.add(noDriverStation(dir));
    all.add(dualDriverStation(dir));
    all.add(revlogPair(dir));
    all.add(empty(dir));
    return List.copyOf(all);
  }

  // ==================== Shared pieces ====================

  /** Loop index helper: {@code t = start + i * LOOP}, computed from the index to avoid drift. */
  static double loopTime(double start, int i) {
    return Math.round((start + i * LOOP) * 1e6) / 1e6;
  }

  static int loops(double start, double end) {
    return (int) Math.floor((end - start) / LOOP + 1e-9) + 1;
  }

  /** An enabled period. */
  record Segment(double start, double end, boolean auto) {
    boolean contains(double t) {
      return t >= start && t < end;
    }
  }

  static boolean enabledAt(List<Segment> segments, double t) {
    return segments.stream().anyMatch(s -> s.contains(t));
  }

  static long epochMicros(String isoInstant, double logSeconds) {
    return Instant.parse(isoInstant).toEpochMilli() * 1000L + Math.round(logSeconds * 1e6);
  }

  /**
   * Writes AdvantageKit-style DriverStation entries for the given enabled segments, logging each
   * entry once at {@code start} and then only on change, as AdvantageKit does.
   */
  static void akitDriverStation(FixtureWriter w, double start, double end,
      List<Segment> segments, boolean fms, String eventName) {
    w.bool("/DriverStation/Enabled", start, false)
        .bool("/DriverStation/Autonomous", start, false)
        .bool("/DriverStation/Test", start, false)
        .bool("/DriverStation/EmergencyStop", start, false)
        .bool("/DriverStation/DSAttached", start, true)
        .bool("/DriverStation/FMSAttached", start, false)
        .str("/DriverStation/EventName", start, "")
        .i64("/DriverStation/MatchType", start, 0)
        .i64("/DriverStation/MatchNumber", start, 0)
        .i64("/DriverStation/AllianceStation", start, 1);
    if (fms) {
      w.bool("/DriverStation/FMSAttached", start + 2.0, true)
          .str("/DriverStation/EventName", start + 2.0, eventName)
          .i64("/DriverStation/MatchType", start + 2.0, 2)
          .i64("/DriverStation/MatchNumber", start + 2.0, 10);
    }
    for (var s : segments) {
      if (s.auto()) w.bool("/DriverStation/Autonomous", s.start() - LOOP, true);
      w.bool("/DriverStation/Enabled", s.start(), true);
      if (s.end() < end) {
        w.bool("/DriverStation/Enabled", s.end(), false);
        if (s.auto()) w.bool("/DriverStation/Autonomous", s.end() + LOOP, false);
      }
    }
  }

  /** Loop timing as AdvantageKit's LoggedRobot writes it; the first cycle is the slow boot cycle. */
  static void akitLoopTiming(FixtureWriter w, double t, int i, boolean enabled) {
    double full;
    if (i == 0) {
      full = 9603.5;
    } else if (enabled && i % 25 == 0) {
      full = 31.0; // overrun: 4 % of enabled loops
    } else {
      full = 16.0 + (i % 7) * 0.5;
    }
    w.dbl("/RealOutputs/LoggedRobot/FullCycleMS", t, full)
        .dbl("/RealOutputs/LoggedRobot/UserCodeMS", t, Math.max(0.5, full - 4.0))
        .dbl("/RealOutputs/LoggedRobot/LogPeriodicMS", t, 1.5 + (i % 3) * 0.1)
        .i64("/Timestamp", t, FixtureWriter.micros(t));
  }

  /** Signed module speed (m/s) for module {@code m}; negative about half the time. */
  static double moduleSpeed(double t, int m, double amplitude, double scale) {
    return scale * amplitude * Math.sin(0.5 * t + m * 0.7);
  }

  static double moduleAngle(double t, int m) {
    return Math.IEEEremainder(0.3 * t + m * 0.2, 2 * Math.PI);
  }

  /** Four SwerveModuleState records (FL, FR, BL, BR order, as the AdvantageKit template logs). */
  static byte[][] moduleStates(double t, double amplitude, double scale) {
    var states = new byte[4][];
    for (int m = 0; m < 4; m++) {
      states[m] = swerveModuleState(moduleSpeed(t, m, amplitude, scale), moduleAngle(t, m));
    }
    return states;
  }

  static void metadata(FixtureWriter w, double t, String sha) {
    w.str("/RealMetadata/GitSHA", t, sha)
        .str("/RealMetadata/GitBranch", t, "main")
        .str("/RealMetadata/GitDirty", t, "All changes committed")
        .str("/RealMetadata/BuildDate", t, "2026-03-21 09:12:00 EDT")
        .str("/RealMetadata/ProjectName", t, "RobotCode2026");
  }

  static final String BOOT_BANNER = String.join("\n",
      "********** Robot program starting **********",
      "NT: Listening on NT3 port 1735, NT4 port 5810",
      "Default error handler registered",
      "Warning at edu.wpi.first.wpilibj.DriverStation.reportJoystickUnpluggedWarning"
          + "(DriverStation.java:2200): Joystick Button 1 on port 1 not available, "
          + "check if controller is plugged in",
      "********** Robot program startup complete **********");

  // ==================== Fixtures ====================

  /** AdvantageKit template style, FMS-attached 2026 qualification match. The baseline. */
  static Fixture akitMatch(Path dir) throws IOException {
    var path = dir.resolve("2026-akit_match.wpilog");
    double start = 6.0;
    double end = 200.0;
    // 2026: 20 s auto, 3 s transition, 140 s teleop
    var segments = List.of(new Segment(20.0, 40.0, true), new Segment(43.0, 183.0, false));
    try (var w = new FixtureWriter(path, AKIT_METADATA)) {
      akitDriverStation(w, start, end, segments, true, "2026TEST");
      metadata(w, start, "abc1234");
      w.str("/RealOutputs/Console", start, BOOT_BANNER)
          .str("/RealOutputs/AutoSelector/SelectedAutoMode", start, "Do Nothing")
          .str("/RealOutputs/AutoSelector/SelectedAutoMode", 12.0, "Two Piece Center")
          .dbl("/SystemStats/BrownoutVoltage", start, 6.75)
          .bool("/SystemStats/BrownedOut", start, false)
          .dbl("/PowerDistribution/Voltage", start, 12.6)
          .strArr("/RealOutputs/Alerts/errors", start)
          .strArr("/RealOutputs/Alerts/warnings", start)
          .strArr("/RealOutputs/Alerts/infos", start);
      for (var name : List.of("OffCount", "TxFullCount", "ReceiveErrorCount",
          "TransmitErrorCount")) {
        w.i64("/SystemStats/CANBus/" + name, start, 0);
      }
      String intakeState = "Retract and stop";
      w.str("/RealOutputs/Subsystems/Intake/Command", start, intakeState);
      double elevatorGoal = 0.0;
      w.dbl("/RealOutputs/Elevator/GoalMeters", start, elevatorGoal);
      int n = loops(start, end);
      for (int i = 0; i < n; i++) {
        double t = loopTime(start, i);
        boolean enabled = enabledAt(segments, t);
        akitLoopTiming(w, t, i, enabled);
        w.i64("/SystemStats/EpochTimeMicros", t, epochMicros("2026-03-21T16:30:00Z", t));
        double load = enabled ? 1.5 * Math.abs(Math.sin(0.4 * t)) : 0.0;
        double battery = enabled ? 12.2 - load : 12.6;
        if (t >= 150.0 && t < 150.06) battery = 7.1; // a deep dip, not a brownout
        w.dbl("/SystemStats/BatteryVoltage", t, battery)
            .dbl("/SystemStats/BatteryCurrent", t, enabled ? 20.0 + 60.0 * load : 2.0)
            .dbl("/SystemStats/5vRail/Voltage", t, 5.02 - (i % 5) * 0.001)
            .dbl("/SystemStats/3v3Rail/Voltage", t, 3.31 - (i % 5) * 0.001);
        if (i % 5 == 0) {
          double[] channels = new double[24];
          for (int c = 0; c < 24; c++) channels[c] = enabled ? (c + 1) * load : 0.0;
          w.dblArr("/PowerDistribution/ChannelCurrent", t, channels)
              .dbl("/PowerDistribution/TotalCurrent", t, enabled ? 300.0 * load : 2.0);
        }
        if (i % 10 == 0) {
          w.flt("/SystemStats/CANBus/Utilization", t, (float) (0.42 + 0.02 * ((i / 10) % 5)));
        }
        if (enabled || i == 0) {
          double drive = enabled ? 1.0 : 0.0;
          w.structArr("/RealOutputs/SwerveStates/Measured", SWERVE_MODULE_STATE, t,
                  moduleStates(t, 2.0, drive))
              .structArr("/RealOutputs/SwerveStates/Setpoints", SWERVE_MODULE_STATE, t,
                  moduleStates(t, 2.1, drive))
              .struct("/RealOutputs/SwerveChassisSpeeds/Measured", CHASSIS_SPEEDS, t,
                  chassisSpeeds(drive * 1.5 * Math.sin(0.1 * t),
                      drive * 1.0 * Math.cos(0.1 * t), drive * 0.3));
          double heading = Math.IEEEremainder(0.3 * t, 2 * Math.PI);
          w.struct("/RealOutputs/Drive/Pose", POSE2D, t,
                  pose2d(3.0 + 2.0 * Math.sin(0.05 * t), 4.0 + 1.5 * Math.cos(0.05 * t), heading))
              .struct("/Drive/Gyro/YawPosition", ROTATION2D, t, rotation2d(heading));
        }
        // Elevator: goal steps every 10 s in teleop; position follows with a first-order lag
        if (enabled && !segments.get(0).contains(t) && i % 500 == 0) {
          elevatorGoal = elevatorGoal > 0.5 ? 0.1 : 1.2;
          w.dbl("/RealOutputs/Elevator/GoalMeters", t, elevatorGoal);
        }
        if (i % 2 == 0) {
          double position = elevatorGoal * (enabled ? 0.98 : 1.0);
          w.dbl("/Elevator/PositionMeters", t, position)
              .dbl("/Elevator/VelocityMetersPerSec", t, enabled ? 0.4 * Math.sin(0.6 * t) : 0.0)
              .dbl("/Elevator/CurrentAmps", t, enabled ? 12.0 + 8.0 * Math.abs(Math.sin(t)) : 0.0);
        }
        // Intake cycles during teleop: 4 s intaking, 6 s retracted
        if (enabled && !segments.get(0).contains(t)) {
          String next = ((int) ((t - 43.0) / 5.0)) % 2 == 0 ? "Intake" : "Retract and stop";
          if (!next.equals(intakeState)) {
            intakeState = next;
            w.str("/RealOutputs/Subsystems/Intake/Command", t, intakeState);
          }
        }
      }
      w.str("/RealOutputs/Console", 60.0, "Loop time of 0.02s overrun\n\tteleopPeriodic(): 0.031s")
          .str("/RealOutputs/Console", 100.0, "CommandScheduler loop overrun")
          .str("/RealOutputs/Console", 120.5,
              "Error at frc.robot.subsystems.Elevator.periodic(Elevator.java:88): "
                  + "Encoder disconnected")
          .strArr("/RealOutputs/Alerts/errors", 120.5, "Elevator encoder disconnected.")
          .strArr("/RealOutputs/Alerts/errors", 125.0);
    }
    return new Fixture("akit_match", path,
        "AdvantageKit template style, FMS-attached 2026 qualification match",
        List.of("baseline", "E1", "B2", "B7", "F2"));
  }

  /**
   * AdvantageKit practice session: no FMS, four enabled segments with the last running to the end
   * of the log, Autonomous logged once as false, two short roboRIO brownouts.
   */
  static Fixture akitPractice(Path dir) throws IOException {
    var path = dir.resolve("2026-akit_practice.wpilog");
    double start = 8.36;
    double end = 240.0;
    var segments = List.of(new Segment(40.2, 70.0, false), new Segment(80.0, 110.0, false),
        new Segment(120.0, 130.0, false), new Segment(150.0, 1e9, false));
    try (var w = new FixtureWriter(path, AKIT_METADATA)) {
      akitDriverStation(w, start, end, segments, false, "");
      metadata(w, start, "def5678");
      w.str("/RealOutputs/Console", start, BOOT_BANNER)
          .dbl("/SystemStats/BrownoutVoltage", start, 6.75)
          .bool("/SystemStats/BrownedOut", start, false)
          .bool("/SystemStats/BrownedOut", 100.40, true)
          .bool("/SystemStats/BrownedOut", 100.54, false)
          .bool("/SystemStats/BrownedOut", 125.40, true)
          .bool("/SystemStats/BrownedOut", 125.44, false);
      int n = loops(start, end);
      double px = 1.0;
      double py = 1.0;
      double heading = 0.0;
      for (int i = 0; i < n; i++) {
        double t = loopTime(start, i);
        boolean enabled = enabledAt(segments, t);
        akitLoopTiming(w, t, i, enabled);
        w.i64("/SystemStats/EpochTimeMicros", t, epochMicros("2026-09-29T23:40:00Z", t));
        double battery = enabled ? 11.9 - 0.8 * Math.abs(Math.sin(0.3 * t)) : 12.5;
        if (t >= 100.40 && t < 100.54) battery = 6.62;
        if (t >= 125.40 && t < 125.44) battery = 6.70;
        w.dbl("/SystemStats/BatteryVoltage", t, battery);
        if (enabled || i == 0) {
          double drive = enabled ? 1.0 : 0.0;
          w.structArr("/RealOutputs/SwerveStates/Measured", SWERVE_MODULE_STATE, t,
                  moduleStates(t, 1.5, drive))
              .structArr("/RealOutputs/SwerveStates/SetpointsOptimized", SWERVE_MODULE_STATE, t,
                  moduleStates(t, 1.55, drive));
          px = 4.0 + Math.sin(0.1 * t);
          py = 3.0 + Math.cos(0.1 * t);
          heading = Math.IEEEremainder(0.2 * t, 2 * Math.PI);
          w.struct("/RealOutputs/Drive/Pose", POSE2D, t, pose2d(px, py, heading))
              .struct("/Drive/Gyro/YawPosition", ROTATION2D, t, rotation2d(heading));
        } else if (t >= 110.0 && t < 120.0 && i % 5 == 0) {
          // Disabled and stationary, but vision corrections move the pose (review H)
          double step = 0.01 * ((i / 5) % 3 - 1);
          px += step;
          py -= step;
          w.struct("/RealOutputs/Drive/Pose", POSE2D, t, pose2d(px, py, heading));
        }
      }
    }
    return new Fixture("akit_practice", path,
        "AdvantageKit practice session: 4 enabled segments, last open, Autonomous never true",
        List.of("E1", "E2", "E3", "B5", "H"));
  }

  /** Plain WPILib DataLogManager: DS: entries, NT:/ names, console and messages, 2025 match. */
  static Fixture wpilibDataLogManager(Path dir) throws IOException {
    var path = dir.resolve("2025-wpilib_dlm.wpilog");
    double start = 3.0;
    double end = 170.0;
    var segments = List.of(new Segment(10.0, 25.0, true), new Segment(28.0, 163.0, false));
    try (var w = new FixtureWriter(path, "")) {
      w.bool("DS:enabled", start, false)
          .bool("DS:autonomous", start, false)
          .bool("DS:test", start, false)
          .bool("DS:estop", start, false)
          .i64("NT:/FMSInfo/FMSControlData", start, 32) // DS attached
          .i64("NT:/FMSInfo/FMSControlData", 5.0, 48) // + FMS attached
          .str("NT:/FMSInfo/EventName", 5.0, "2025TEST")
          .i64("NT:/FMSInfo/MatchNumber", 5.0, 42)
          .bool("NT:/FMSInfo/IsRedAlliance", 5.0, true)
          .str("messages", start, "DataLogManager: Logging to 2025-wpilib_dlm.wpilog")
          .str("console", start, BOOT_BANNER)
          .i64("systemTime", start, epochMicros("2025-03-05T18:00:00Z", start));
      for (var s : segments) {
        if (s.auto()) {
          w.bool("DS:autonomous", s.start() - LOOP, true);
        }
        w.bool("DS:enabled", s.start(), true)
            .i64("NT:/FMSInfo/FMSControlData", s.start(), s.auto() ? 51 : 49)
            .bool("DS:enabled", s.end(), false)
            .i64("NT:/FMSInfo/FMSControlData", s.end(), 48);
        if (s.auto()) w.bool("DS:autonomous", s.end() + LOOP, false);
      }
      int n = loops(start, end);
      for (int i = 0; i < n; i++) {
        double t = loopTime(start, i);
        boolean enabled = enabledAt(segments, t);
        if (i % 5 == 0) { // NetworkTables publishes at 10 Hz
          double load = enabled ? Math.abs(Math.sin(0.3 * t)) : 0.0;
          w.dbl("NT:/SmartDashboard/PowerDistribution[1]/Voltage", t,
                  enabled ? 12.1 - 1.2 * load : 12.7)
              .dbl("NT:/SmartDashboard/PowerDistribution[1]/TotalCurrent", t,
                  enabled ? 40.0 + 150.0 * load : 1.5)
              .dbl("NT:/SmartDashboard/PowerDistribution[1]/Chan0", t, enabled ? 30.0 * load : 0.0)
              .dbl("NT:/SmartDashboard/PowerDistribution[1]/Chan1", t, enabled ? 25.0 * load : 0.0)
              .dblArr("NT:/SmartDashboard/Field/Robot", t, 2.0 + Math.sin(0.05 * t),
                  3.0 + Math.cos(0.05 * t), Math.toDegrees(Math.IEEEremainder(0.2 * t,
                      2 * Math.PI)));
        }
        if (enabled && i % 50 == 0) {
          w.fltArr("DS:joystick0/axes", t, (float) Math.sin(t), (float) Math.cos(t), 0f, 0f);
        }
      }
      w.str("messages", 60.0, "Loop time of 0.02s overrun")
          .str("console", 61.0, "Warning at frc.robot.Robot.robotPeriodic(Robot.java:40): "
              + "Loop time of 0.02s overrun")
          .str("console", 90.0, "ERROR  -3  CAN: Message not found  "
              + "edu.wpi.first.hal.CANAPIJNI.readCANPacketNew");
    }
    return new Fixture("wpilib_dlm", path,
        "Plain WPILib DataLogManager log (DS:, NT:/ entries, console, messages), 2025 match",
        List.of("B*", "E1", "F1"));
  }

  /**
   * A DataLogManager log as real robots write it: several NetworkTables entries carry one record
   * each with a timestamp before zero (8 to 30 s early), then the session. Real logs from
   * several teams look like this; a rule that read negative timestamps as damage emptied those
   * entries and called healthy logs truncated. Every name and value here is made up.
   */
  static Fixture dlmRetained(Path dir) throws IOException {
    var path = dir.resolve("2026-dlm_retained.wpilog");
    double start = 0.5;
    double end = 60.0;
    var segments = List.of(new Segment(10.0, 50.0, false));
    try (var w = new FixtureWriter(path, "")) {
      w.i64("NT:/photonvision/ledModeState", -30.25, 1)
          .str("NT:/CameraPublisher/Front/description", -29.9, "USB camera")
          .bool("NT:/CameraPublisher/Front/connected", -29.9, true)
          .strArr("NT:/SmartDashboard/Auto Chooser/options", -12.5, "Do Nothing", "Two Piece")
          .dbl("NT:/SmartDashboard/Shooter/kP", -8.5, 0.05)
          .bool("DS:enabled", start, false)
          .bool("DS:autonomous", start, false)
          .i64("systemTime", start, epochMicros("2026-03-15T15:54:22Z", start));
      for (var s : segments) {
        w.bool("DS:enabled", s.start(), true).bool("DS:enabled", s.end(), false);
      }
      int n = loops(start, end);
      for (int i = 0; i < n; i++) {
        double t = loopTime(start, i);
        if (i % 5 == 0) {
          w.dbl("NT:/SmartDashboard/Shooter/RPM", t,
              enabledAt(segments, t) ? 3000.0 + 50.0 * Math.sin(t) : 0.0);
        }
      }
    }
    return new Fixture("dlm_retained", path,
        "DataLogManager log whose NetworkTables values from before time zero carry negative "
            + "timestamps",
        List.of("B*"));
  }

  /** Per-module swerve entries, one SwerveModuleState per module (the older convention). */
  static Fixture swervePerModule(Path dir) throws IOException {
    var path = dir.resolve("2026-swerve_per_module.wpilog");
    double start = 1.0;
    double end = 70.0;
    var segments = List.of(new Segment(5.0, 65.0, false));
    try (var w = new FixtureWriter(path, AKIT_METADATA)) {
      akitDriverStation(w, start, end, segments, false, "");
      int n = loops(start, end);
      for (int i = 0; i < n; i++) {
        double t = loopTime(start, i);
        boolean enabled = enabledAt(segments, t);
        double drive = enabled ? 1.0 : 0.0;
        for (int m = 0; m < 4; m++) {
          double speed = moduleSpeed(t, m, 2.0, drive);
          double measuredSpeed = m == 2 ? speed * 0.7 : speed;
          w.struct("/Drive/Module" + m + "/Setpoint", SWERVE_MODULE_STATE, t,
                  swerveModuleState(speed, moduleAngle(t, m)))
              .struct("/Drive/Module" + m + "/Measured", SWERVE_MODULE_STATE, t,
                  swerveModuleState(measuredSpeed, moduleAngle(t, m)));
        }
        double x = 2.0 * Math.sin(0.03 * t);
        double y = 1.5 * Math.cos(0.03 * t);
        w.struct("/Odometry/Robot", POSE2D, t, pose2d(x + 0.01 * t, y + 0.005 * t, 0.0));
        if (i % 5 == 0) {
          w.struct("/Vision/EstimatedPose", POSE2D, t, pose2d(x, y, 0.0));
        }
      }
    }
    return new Fixture("swerve_per_module", path,
        "Swerve as one SwerveModuleState entry per module; module 2 slips; odometry drifts",
        List.of("B5"));
  }

  /** AdvantageKit swerve: SwerveModuleState[4] arrays, plus the drift-discovery traps. */
  static Fixture swerveArray(Path dir) throws IOException {
    var path = dir.resolve("2026-swerve_array.wpilog");
    double start = 1.0;
    double end = 70.0;
    var segments = List.of(new Segment(5.0, 65.0, false));
    try (var w = new FixtureWriter(path, AKIT_METADATA)) {
      akitDriverStation(w, start, end, segments, false, "");
      // One-sample trajectory and an array of accepted vision poses: both look like poses by name
      w.structArr("/RealOutputs/Odometry/Trajectory", POSE2D, start, pose2d(0, 0, 0),
          pose2d(5, 5, 0));
      int n = loops(start, end);
      for (int i = 0; i < n; i++) {
        double t = loopTime(start, i);
        boolean enabled = enabledAt(segments, t);
        double drive = enabled ? 1.0 : 0.0;
        if (enabled || i == 0) {
          w.structArr("/RealOutputs/SwerveStates/Measured", SWERVE_MODULE_STATE, t,
                  moduleStates(t, 2.0, drive))
              .structArr("/RealOutputs/SwerveStates/SetpointsOptimized", SWERVE_MODULE_STATE, t,
                  moduleStates(t, 2.0, drive))
              .structArr("/RealOutputs/SwerveStates/Setpoints", SWERVE_MODULE_STATE, t,
                  moduleStates(t, 2.05, drive));
        }
        double x = 2.0 * Math.sin(0.03 * t);
        double y = 1.5 * Math.cos(0.03 * t);
        w.struct("/RealOutputs/Odometry/Robot", POSE2D, t, pose2d(x + 0.01 * t, y, 0.0));
        if (i % 5 == 0) {
          w.structArr("/RealOutputs/Vision/Summary/RobotPosesAccepted", POSE3D, t,
              pose3d(x, y, 0, 0));
        }
      }
    }
    return new Fixture("swerve_array", path,
        "AdvantageKit swerve: SwerveModuleState[4] arrays, one-sample trajectory, pose arrays",
        List.of("B5", "A4"));
  }

  /** Limelight through NetworkTables (plain WPILib log). */
  static Fixture visionLimelight(Path dir) throws IOException {
    var path = dir.resolve("2025-vision_limelight.wpilog");
    double start = 1.0;
    double end = 60.0;
    try (var w = new FixtureWriter(path, "")) {
      w.bool("DS:enabled", start, false).bool("DS:autonomous", start, false)
          .bool("DS:enabled", 5.0, true).bool("DS:enabled", 55.0, false);
      int n = loops(start, end);
      double tv = -1;
      for (int i = 0; i < n; i++) {
        double t = loopTime(start, i);
        if (i % 5 != 0) continue;
        double seen = (t % 10.0) < 7.0 ? 1.0 : 0.0; // 70 % of the time
        if (seen != tv) {
          tv = seen;
          w.dbl("NT:/limelight-front/tv", t, tv);
        }
        w.dbl("NT:/limelight-front/tx", t, seen > 0 ? 5.0 * Math.sin(t) : 0.0)
            .dbl("NT:/limelight-front/ty", t, seen > 0 ? 2.0 : 0.0)
            .dbl("NT:/limelight-front/ta", t, seen > 0 ? 0.8 : 0.0)
            .dbl("NT:/limelight-front/tl", t, 11.0)
            .dbl("NT:/limelight-front/cl", t, 7.5)
            .dblArr("NT:/limelight-front/botpose_wpiblue", t,
                3.0 + 0.1 * Math.sin(t), 4.0, 0.0, 0.0, 0.0, 30.0, 18.5, seen > 0 ? 1 : 0, 0.0,
                2.5, 0.8)
            .dblArr("NT:/SmartDashboard/Field/Robot", t, 3.0 + 0.1 * Math.sin(t), 4.0, 30.0);
      }
    }
    return new Fixture("vision_limelight", path,
        "Limelight via NetworkTables (tv/tx/ty/ta/tl/cl, botpose_wpiblue)", List.of("B4"));
  }

  /** The AdvantageKit vision template's PoseObservation schema (as logged by team 2363). */
  public static final String POSE_OBSERVATION_SCHEMA = "double timestamp;Pose3d pose;"
      + "double ambiguity;int32 tagCount;double averageTagDistance;"
      + "enum {MEGATAG_1=0, MEGATAG_2=1, PHOTONVISION=2} int32 type;";

  /** The AdvantageKit vision template's TargetObservation schema. */
  public static final String TARGET_OBSERVATION_SCHEMA =
      "Rotation2d yaw;Rotation2d pitch;Rotation2d skew;double area;float confidence;int32 objectID;";

  static byte[] poseObservation(double timestamp, double x, double y, double z, double yaw,
      double ambiguity, int tagCount, double avgDistance, int type) {
    return FixtureWriter.le(88).putDouble(timestamp).put(pose3d(x, y, z, yaw))
        .putDouble(ambiguity).putInt(tagCount).putDouble(avgDistance).putInt(type).array();
  }

  /** PhotonVision through the AdvantageKit vision template: PoseObservation[] per camera. */
  static Fixture visionPhotonAkit(Path dir) throws IOException {
    var path = dir.resolve("2026-vision_photon_akit.wpilog");
    double start = 1.0;
    double end = 60.0;
    var segments = List.of(new Segment(5.0, 55.0, false));
    try (var w = new FixtureWriter(path, AKIT_METADATA)) {
      akitDriverStation(w, start, end, segments, false, "");
      // Pose3d's schema (and its nested ones) come from WPILib; the custom structs are hand-written
      w.structArr("/RealOutputs/Vision/Summary/RobotPosesAccepted", POSE3D, start)
          .schema("PoseObservation", POSE_OBSERVATION_SCHEMA, start)
          .schema("TargetObservation", TARGET_OBSERVATION_SCHEMA, start)
          .struct("/Drive/Gyro/YawPosition", ROTATION2D, start, rotation2d(0.0));
      int n = loops(start, end);
      for (int i = 0; i < n; i++) {
        double t = loopTime(start, i);
        double x = 3.0 + 0.5 * Math.sin(0.1 * t);
        double y = 4.5 + 0.3 * Math.cos(0.1 * t);
        w.struct("/RealOutputs/Drive/Pose", POSE2D, t, pose2d(x, y, 0.0));
        if (i % 2 != 0) continue; // camera inputs every other loop, like the review log
        for (int cam = 0; cam < 2; cam++) {
          String prefix = "/Vision/Camera" + cam + "/";
          boolean connected = cam == 0 || t < 30.0 || t >= 40.0; // camera 1 drops out 30-40 s
          if (i == 0 || (cam == 1 && (Math.abs(t - 30.0) < 1e-6 || Math.abs(t - 40.0) < 1e-6))) {
            w.bool(prefix + "Connected", t, connected);
          }
          if (!connected) continue;
          var data = poseObservation(t - 0.06, x + 0.02 * cam, y - 0.01 * cam, 0.0, 0.01 * cam,
              0.05 * cam, 1, 2.8 + cam, 2);
          w.raw(prefix + "PoseObservations", "struct:PoseObservation[]", t, data)
              .i64Arr(prefix + "TagIds", t, 7 + cam)
              .dbl(prefix + "LatencyMs", t, 61.0 + cam);
          var target = FixtureWriter.le(40).putDouble(0.1 * cam).putDouble(0.05).putDouble(0.0)
              .putDouble(0.9).putFloat(0.95f).putInt(7 + cam).array();
          w.raw(prefix + "LatestTargetObservation", "struct:TargetObservation", t, target);
        }
        w.structArr("/RealOutputs/Vision/Summary/RobotPosesAccepted", POSE3D, t,
            pose3d(x, y, 0.0, 0.0));
      }
    }
    return new Fixture("vision_photon_akit", path,
        "PhotonVision via the AdvantageKit vision template: PoseObservation[] for 2 cameras",
        List.of("B4", "A1", "C1"));
  }

  /** A single Pose3d vision estimate entry (not an array). */
  static Fixture visionPose3dSingle(Path dir) throws IOException {
    var path = dir.resolve("2026-vision_pose3d.wpilog");
    double start = 1.0;
    double end = 40.0;
    try (var w = new FixtureWriter(path, AKIT_METADATA)) {
      akitDriverStation(w, start, end, List.of(new Segment(5.0, 35.0, false)), false, "");
      int n = loops(start, end);
      for (int i = 0; i < n; i++) {
        double t = loopTime(start, i);
        double x = 1.0 + 0.2 * t;
        w.struct("/RealOutputs/Drive/Pose", POSE2D, t, pose2d(x, 2.0, 0.0));
        if (i % 3 == 0) {
          double jump = (t > 20.0 && t < 20.1) ? 1.5 : 0.0; // one real vision jump
          w.struct("/RealOutputs/Vision/Camera0/EstimatedPose", POSE3D, t,
              pose3d(x + jump, 2.0, 0.0, 0.0));
        }
      }
    }
    return new Fixture("vision_pose3d", path, "Vision estimate as a single Pose3d entry",
        List.of("B4"));
  }

  /** PoseObservation with the built-in's name but an extra field (96 bytes, not 88). */
  static Fixture structLayoutMismatch(Path dir) throws IOException {
    var path = dir.resolve("2026-struct_layout_mismatch.wpilog");
    String schema = "double timestamp;Pose3d pose;double ambiguity;double stdDevXY;int32 tagCount;"
        + "double averageTagDistance;enum {MEGATAG_1=0, MEGATAG_2=1, PHOTONVISION=2} int32 type;";
    try (var w = new FixtureWriter(path, AKIT_METADATA)) {
      akitDriverStation(w, 1.0, 20.0, List.of(new Segment(2.0, 18.0, false)), false, "");
      w.structArr("/RealOutputs/Vision/Summary/RobotPosesAccepted", POSE3D, 1.0)
          .schema("PoseObservation", schema, 1.0);
      int n = loops(1.0, 20.0);
      for (int i = 0; i < n; i += 5) {
        double t = loopTime(1.0, i);
        var buffer = FixtureWriter.le(2 * 96);
        for (int k = 0; k < 2; k++) {
          buffer.putDouble(t - 0.05).putDouble(3.0 + k).putDouble(4.0).putDouble(0.0)
              .putDouble(1.0).putDouble(0.0).putDouble(0.0).putDouble(0.0)
              .putDouble(0.1).putDouble(0.25).putInt(2).putDouble(3.0).putInt(1);
        }
        w.raw("/Vision/Camera0/PoseObservations", "struct:PoseObservation[]", t, buffer.array());
      }
    }
    return new Fixture("struct_layout_mismatch", path,
        "A team PoseObservation with an extra field: same name as the built-in, 96-byte records",
        List.of("C2", "R1"));
  }

  /** The ArmState schema: nested struct, fixed array, enum, and two bit-fields sharing a byte. */
  public static final String ARM_STATE_SCHEMA = "Rotation2d angle;double currents[2];"
      + "enum {STOWED=0, SCORING=1, INTAKE=2} int8 mode;uint8 flags:3;bool homed:1;"
      + "float temperature";

  /** A custom struct that nests another custom struct. */
  public static final String SHOT_RECORD_SCHEMA = "ArmState arm;double distanceMeters;int32 shotId";

  /** Unknown custom structs: nested, fixed array, enum, bit-fields, out-of-order schemas. */
  static Fixture structCustom(Path dir) throws IOException {
    var path = dir.resolve("2026-struct_custom.wpilog");
    // Pack with WPILib's own dynamic struct support so the bytes follow the spec exactly
    var db = new StructDescriptorDatabase();
    try {
      db.add("Rotation2d", "double value");
      db.add("ArmState", ARM_STATE_SCHEMA);
      db.add("ShotRecord", SHOT_RECORD_SCHEMA);
    } catch (edu.wpi.first.util.struct.BadSchemaException e) {
      throw new IOException(e);
    }
    var armDesc = db.find("ArmState");
    var shotDesc = db.find("ShotRecord");
    try (var w = new FixtureWriter(path, AKIT_METADATA)) {
      akitDriverStation(w, 1.0, 30.0, List.of(new Segment(2.0, 28.0, false)), false, "");
      // Declared out of order: ShotRecord and ArmState before the Rotation2d they depend on
      w.schema("ShotRecord", SHOT_RECORD_SCHEMA, 1.0)
          .schema("ArmState", ARM_STATE_SCHEMA, 1.0)
          .struct("/Drive/Gyro/YawPosition", ROTATION2D, 1.0, rotation2d(0.0));
      int n = loops(1.0, 30.0);
      int shot = 0;
      for (int i = 0; i < n; i++) {
        double t = loopTime(1.0, i);
        byte[] arm = packArm(armDesc, 0.5 * Math.sin(0.2 * t), 10.0 + t, 11.0 + t, (i / 250) % 3,
            i % 8, i > 100, (float) (30.0 + 0.1 * t));
        w.raw("/RealOutputs/Arm/State", "struct:ArmState", t, arm);
        if (i % 100 == 0) {
          // every fourth of these records is cut to 29 bytes: 3 of 15 cannot be decoded
          w.raw("/RealOutputs/Arm/Partial", "struct:ArmState", t,
              (i / 100) % 4 == 3 ? java.util.Arrays.copyOf(arm, 29) : arm);
        }
        if (i % 10 == 0) {
          byte[] arm2 = packArm(armDesc, -0.25, 1.0, 2.0, 1, 5, true, 41.5f);
          var both = ByteBuffer.allocate(arm.length * 2).put(arm).put(arm2).array();
          w.raw("/RealOutputs/Arm/States", "struct:ArmState[]", t, both);
        }
        if (i % 250 == 125) {
          var record = DynamicStruct.allocate(shotDesc);
          record.getStructField(shotDesc.findFieldByName("arm")).setData(arm);
          record.setDoubleField(shotDesc.findFieldByName("distanceMeters"), 2.5 + shot);
          record.setIntField(shotDesc.findFieldByName("shotId"), ++shot);
          w.raw("/RealOutputs/Shots/Last", "struct:ShotRecord", t, bytes(record));
        }
      }
      // A struct entry whose schema was never logged
      w.raw("/RealOutputs/Mystery", "struct:Mystery", 5.0, new byte[] {1, 2, 3, 4});
    }
    return new Fixture("struct_custom", path,
        "Unknown custom structs: nested, fixed array, enum, bit-fields, out-of-order schemas; "
            + "a struct with no schema; an entry with 3 of 15 records cut short",
        List.of("C1", "C3", "C4", "C5", "R1", "R5"));
  }

  static byte[] packArm(edu.wpi.first.util.struct.StructDescriptor desc, double angleRad,
      double current0, double current1, int mode, int flags, boolean homed, float temperature) {
    var arm = DynamicStruct.allocate(desc);
    var angle = arm.getStructField(desc.findFieldByName("angle"));
    angle.setDoubleField(angle.findField("value"), angleRad);
    var currents = desc.findFieldByName("currents");
    arm.setDoubleField(currents, current0, 0);
    arm.setDoubleField(currents, current1, 1);
    arm.setIntField(desc.findFieldByName("mode"), mode);
    arm.setIntField(desc.findFieldByName("flags"), flags);
    arm.setBoolField(desc.findFieldByName("homed"), homed);
    arm.setFloatField(desc.findFieldByName("temperature"), temperature);
    return bytes(arm);
  }

  static byte[] bytes(DynamicStruct struct) {
    var buffer = struct.getBuffer();
    var out = new byte[struct.getDescriptor().getSize()];
    buffer.get(out);
    return out;
  }

  /** roboRIO 2: BrownoutVoltage logged as 6.3 V; a dip to 6.5 V is below 6.8 but not a brownout. */
  static Fixture brownoutRio2(Path dir) throws IOException {
    var path = dir.resolve("2026-brownout_rio2.wpilog");
    double start = 1.0;
    double end = 60.0;
    var segments = List.of(new Segment(10.0, 50.0, false));
    try (var w = new FixtureWriter(path, AKIT_METADATA)) {
      akitDriverStation(w, start, end, segments, false, "");
      w.dbl("/SystemStats/BrownoutVoltage", start, 6.3).bool("/SystemStats/BrownedOut", start,
          false);
      int n = loops(start, end);
      for (int i = 0; i < n; i++) {
        double t = loopTime(start, i);
        boolean enabled = enabledAt(segments, t);
        double v = enabled ? 12.0 : 12.6;
        if (t >= 30.0 && t < 30.2) v = 6.5;
        w.dbl("/SystemStats/BatteryVoltage", t, v)
            .dbl("/SystemStats/BatteryCurrent", t, enabled ? 60.0 : 2.0);
      }
    }
    return new Fixture("brownout_rio2", path,
        "roboRIO 2: logged BrownoutVoltage 6.3 V; a 6.5 V dip that is not a brownout",
        List.of("E3"));
  }

  /** roboRIO 1 without a BrownoutVoltage entry; the logged flag reports one real brownout. */
  static Fixture brownoutRio1NoThreshold(Path dir) throws IOException {
    var path = dir.resolve("2026-brownout_rio1.wpilog");
    double start = 1.0;
    double end = 60.0;
    var segments = List.of(new Segment(10.0, 50.0, false));
    try (var w = new FixtureWriter(path, AKIT_METADATA)) {
      akitDriverStation(w, start, end, segments, false, "");
      w.bool("/SystemStats/BrownedOut", start, false)
          .bool("/SystemStats/BrownedOut", 30.0, true)
          .bool("/SystemStats/BrownedOut", 30.1, false);
      int n = loops(start, end);
      for (int i = 0; i < n; i++) {
        double t = loopTime(start, i);
        boolean enabled = enabledAt(segments, t);
        double v = enabled ? 12.0 : 12.6;
        if (t >= 30.0 && t < 30.1) v = 6.7;
        w.dbl("/SystemStats/BatteryVoltage", t, v);
      }
    }
    return new Fixture("brownout_rio1", path,
        "roboRIO 1 without a BrownoutVoltage entry; BrownedOut true for 0.1 s", List.of("E3"));
  }

  /** CANivore counters with a TEC excursion, plus names that contain "can" but are not CAN. */
  static Fixture canivore(Path dir) throws IOException {
    var path = dir.resolve("2026-canivore.wpilog");
    double start = 1.0;
    double end = 70.0;
    var segments = List.of(new Segment(20.0, 60.0, false));
    try (var w = new FixtureWriter(path, AKIT_METADATA)) {
      akitDriverStation(w, start, end, segments, false, "");
      for (var name : List.of("OffCount", "TxFullCount", "ReceiveErrorCount",
          "TransmitErrorCount")) {
        w.i64("/SystemStats/CANBus/" + name, start, 0);
      }
      for (var name : List.of("REC", "BusOffCount", "TxFullCount")) {
        w.i64("/RealOutputs/CANBus/CANHD/" + name, start, 0);
      }
      w.i64("/RealOutputs/CANBus/CANHD/TEC", start, 0)
          .i64("/RealOutputs/CANBus/CAN2/TEC", start, 0)
          .str("/RealOutputs/Console", start, BOOT_BANNER)
          .str("/RealOutputs/Console", 5.0, "CAN frame timeout: device 12 (disabled, harmless)");
      long tec = 0;
      int n = loops(start, end);
      for (int i = 0; i < n; i++) {
        double t = loopTime(start, i);
        if (i % 10 == 0) {
          w.flt("/SystemStats/CANBus/Utilization", t, 0.35f)
              .flt("/RealOutputs/CANBus/CANHD/Utilization", t, (float) (0.55 + 0.1 * Math.sin(t)))
              .flt("/RealOutputs/CANBus/CAN2/Utilization", t, 0.2f)
              .dbl("/Drive/Canandgyro/YawDegrees", t, 10.0 * Math.sin(0.1 * t))
              .dbl("/Vision/ScanRateHz", t, 30.0)
              .bool("/Intake/CanSeeGamePiece", t, (i / 100) % 2 == 0);
        }
        // TEC climbs to 85 at 35 s (enabled), then decays back to 0 by 40 s
        long next = t < 30.0 ? 0 : t < 35.0 ? Math.round(17.0 * (t - 30.0))
            : t < 40.0 ? Math.round(17.0 * (40.0 - t)) : 0;
        if (next != tec) {
          tec = next;
          w.i64("/RealOutputs/CANBus/CANHD/TEC", t, tec);
        }
      }
    }
    return new Fixture("canivore", path,
        "CANivore counters (TEC/REC/BusOffCount) with a TEC excursion to 85 while enabled",
        List.of("B3", "F3"));
  }

  /** WPILib alerts as string[] state, batched console, and a json status entry. */
  static Fixture alertsConsole(Path dir) throws IOException {
    var path = dir.resolve("2026-alerts_console.wpilog");
    double start = 5.0;
    double end = 120.0;
    var segments = List.of(new Segment(20.0, 100.0, false));
    try (var w = new FixtureWriter(path, AKIT_METADATA)) {
      akitDriverStation(w, start, end, segments, false, "");
      w.strArr("/RealOutputs/Alerts/errors", start)
          .strArr("/RealOutputs/Alerts/warnings", start)
          .strArr("/RealOutputs/Alerts/infos", start, "Robot code version abc1234")
          .strArr("/RealOutputs/PhotonAlerts/errors", start)
          .str("/RealOutputs/Console", start, BOOT_BANNER)
          .json("/RadioStatus/Status", start, "{\"status\":\"ok\",\"linkQuality\":0.98}")
          .strArr("/RealOutputs/Alerts/warnings", 30.0, "Low battery voltage.")
          .strArr("/RealOutputs/Alerts/warnings", 40.0)
          .strArr("/RealOutputs/Alerts/errors", 50.0, "Vision camera 3 is disconnected.")
          .strArr("/RealOutputs/PhotonAlerts/errors", 50.0,
              "PhotonCamera 'OV2311_TH_7' is disconnected.")
          .str("/RealOutputs/Console", 50.2, "Warning: PhotonCamera 'OV2311_TH_7' is disconnected")
          .json("/RadioStatus/Status", 70.0,
              "{\"status\":\"error\",\"message\":\"radio link lost\",\"linkQuality\":0.0}")
          .json("/RadioStatus/Status", 72.0, "{\"status\":\"ok\",\"linkQuality\":0.91}")
          .strArr("/RealOutputs/Alerts/errors", 96.0)
          .strArr("/RealOutputs/PhotonAlerts/errors", 96.0);
      for (int k = 0; k < 20; k++) {
        double t = 25.0 + k * 3.0;
        w.str("/RealOutputs/Console", t, "Loop time of 0.02s overrun\n\tteleopPeriodic(): "
            + String.format("%.3f", 0.021 + k * 0.001) + "s");
      }
      w.str("/RealOutputs/Console", 85.0,
          "Error at frc.robot.Robot.teleopPeriodic(Robot.java:77): NullPointerException\n"
              + "\tat frc.robot.subsystems.Shooter.periodic(Shooter.java:50)");
      int n = loops(start, end);
      for (int i = 0; i < n; i += 5) {
        double t = loopTime(start, i);
        w.dbl("/SystemStats/BatteryVoltage", t, enabledAt(segments, t) ? 12.1 : 12.6);
      }
    }
    return new Fixture("alerts_console", path,
        "Alerts as string[] (appear/clear), batched multi-line console, json radio status",
        List.of("F1", "F2"));
  }

  /** An AdvantageKit replay output (_sim) log, with or without real divergence. */
  static Fixture replay(Path dir, boolean divergent) throws IOException {
    String id = divergent ? "replay_divergent" : "replay_identical";
    var path = dir.resolve("2026-akit_" + id + "_sim.wpilog");
    double start = 1.0;
    double end = 40.0;
    try (var w = new FixtureWriter(path, AKIT_METADATA)) {
      akitDriverStation(w, start, end, List.of(new Segment(5.0, 35.0, false)), false, "");
      w.dbl("/RealOutputs/Only/InReal", start, 1.0);
      int n = loops(start, end);
      for (int i = 0; i < n; i++) {
        double t = loopTime(start, i);
        double speed = 50.0 + 10.0 * Math.sin(0.2 * t);
        double replaySpeed = divergent && t >= 30.0 ? speed + 0.5 : speed;
        double x = 1.0 + 0.1 * t;
        // Floating-point noise below any sensible tolerance: not a divergence
        double replayX = divergent ? x + 1e-12 : x;
        double[] currents = {1.0 + i % 3, 2.0, 3.0};
        w.dbl("/RealOutputs/Shooter/SpeedRPS", t, speed)
            .dbl("/ReplayOutputs/Shooter/SpeedRPS", t, replaySpeed)
            .struct("/RealOutputs/Drive/Pose", POSE2D, t, pose2d(x, 2.0, 0.0))
            .struct("/ReplayOutputs/Drive/Pose", POSE2D, t, pose2d(replayX, 2.0, 0.0))
            .dblArr("/RealOutputs/PD/ChannelCurrentsAmps", t, currents)
            .dblArr("/ReplayOutputs/PD/ChannelCurrentsAmps", t, currents.clone());
      }
    }
    return new Fixture(id, path, divergent
        ? "AdvantageKit replay log: one real divergence (+0.5 after 30 s) and 1e-12 pose noise"
        : "AdvantageKit replay log: replay matches the real outputs exactly",
        List.of("A3", "B10"));
  }

  /** A log whose final record was cut mid-write. */
  static Fixture truncated(Path dir) throws IOException {
    var path = dir.resolve("2026-truncated.wpilog");
    try (var w = new FixtureWriter(path, AKIT_METADATA)) {
      akitDriverStation(w, 1.0, 30.0, List.of(new Segment(5.0, 25.0, false)), false, "");
      int n = loops(1.0, 30.0);
      for (int i = 0; i < n; i++) {
        double t = loopTime(1.0, i);
        w.dbl("/SystemStats/BatteryVoltage", t, 12.3)
            .struct("/RealOutputs/Drive/Pose", POSE2D, t, pose2d(0.1 * t, 0, 0));
      }
    }
    try (var file = new RandomAccessFile(path.toFile(), "rw")) {
      file.setLength(file.length() - 7);
    }
    return new Fixture("truncated", path, "A log whose last record was cut mid-write",
        List.of("loader"));
  }

  /** Telemetry with no DriverStation entries at all. */
  static Fixture noDriverStation(Path dir) throws IOException {
    var path = dir.resolve("2026-no_ds.wpilog");
    try (var w = new FixtureWriter(path, AKIT_METADATA)) {
      int n = loops(1.0, 30.0);
      for (int i = 0; i < n; i++) {
        double t = loopTime(1.0, i);
        akitLoopTiming(w, t, i, false);
        w.dbl("/SystemStats/BatteryVoltage", t, 12.4 - 0.001 * i)
            .struct("/RealOutputs/Drive/Pose", POSE2D, t, pose2d(0.1 * t, 0.05 * t, 0));
      }
      w.str("/RealOutputs/Console", 10.0, "Error: CAN timeout on device 3");
    }
    return new Fixture("no_ds", path, "Telemetry without any DriverStation entries",
        List.of("R4", "R6"));
  }

  /** Both naming conventions for DriverStation state in one log (NT mirror plus AdvantageKit). */
  static Fixture dualDriverStation(Path dir) throws IOException {
    var path = dir.resolve("2026-dual_ds.wpilog");
    double start = 1.0;
    double end = 180.0;
    var segments = List.of(new Segment(10.0, 30.0, true), new Segment(33.0, 173.0, false));
    try (var w = new FixtureWriter(path, "")) {
      akitDriverStation(w, start, end, segments, true, "2026DUAL");
      w.bool("DS:enabled", start, false).bool("DS:autonomous", start, false);
      for (var s : segments) {
        if (s.auto()) w.bool("DS:autonomous", s.start() - LOOP, true);
        w.bool("DS:enabled", s.start(), true).bool("DS:enabled", s.end(), false);
        if (s.auto()) w.bool("DS:autonomous", s.end() + LOOP, false);
      }
      int n = loops(start, end);
      for (int i = 0; i < n; i += 5) {
        double t = loopTime(start, i);
        w.dbl("/SystemStats/BatteryVoltage", t, enabledAt(segments, t) ? 12.0 : 12.6);
      }
    }
    return new Fixture("dual_ds", path,
        "DriverStation state logged under both DS: and /DriverStation/ names", List.of("E1"));
  }

  /** Wall-clock time (UTC) of the revlog pair's FPGA 10 s. */
  public static final java.time.LocalDateTime REVLOG_PAIR_WALL =
      java.time.LocalDateTime.of(2026, 1, 10, 15, 0, 0);

  /** Seconds added to the pair's revlog timestamps to give the wpilog's FPGA time. */
  public static final double REVLOG_PAIR_OFFSET_SEC = 15.3;

  /** The pair's motor applied output (duty cycle) at FPGA time t: smooth plus seeded steps. */
  public static double revlogPairOutput(double t) {
    double steps = (((int) Math.floor(t / 1.7) * 7919) % 11 - 5) * 0.04;
    return Math.max(-1, Math.min(1, 0.45 * Math.sin(0.9 * t) + 0.2 * Math.sin(3.1 * t + 1)
        + steps));
  }

  /** The pair's bus voltage (V), current (A), and motor temperature (degC). */
  public static final double REVLOG_PAIR_BUS_VOLTS = 12.3;
  public static final double REVLOG_PAIR_AMPS = 20.0;
  public static final int REVLOG_PAIR_TEMP_C = 31;
  /** The status 0 SPARK_MODEL code of a SPARK MAX (REVLib's SparkModel: 1 Flex, 2 MAX). */
  public static final long SPARK_MAX_MODEL_CODE = 2;

  /**
   * A SPARK status 0 frame (firmware 25+, REV's spark-frames 2.1.0): applied output int16 in
   * bits 0-15 (1.01/32767 per count), bus voltage uint12 in 16-27 (30/4095 V), current uint12 in
   * 28-39 (150/4095 A), motor temperature in 40-47 (degC), inverted in bit 52, and the model in
   * bits 54-57 (SPARK_MODEL: 2 = SPARK MAX, the code of REVLib's SparkModel).
   */
  public static byte[] sparkStatus0(double appliedOutput, double busVolts, double amps,
      int tempC, boolean inverted) {
    long applied = Math.round(appliedOutput / 0.00003082369457075716) & 0xFFFF;
    long volts = Math.round(busVolts / 0.0073260073260073) & 0xFFF;
    long current = Math.round(amps / 0.0366300366300366) & 0xFFF;
    long bits = applied | volts << 16 | current << 28 | (long) (tempC & 0xFF) << 40
        | (inverted ? 1L : 0L) << 52 | SPARK_MAX_MODEL_CODE << 54;
    var frame = new byte[8];
    for (int i = 0; i < 8; i++) frame[i] = (byte) (bits >>> (8 * i));
    return frame;
  }

  static byte[] sparkStatus0(double appliedOutput) {
    return sparkStatus0(appliedOutput, REVLOG_PAIR_BUS_VOLTS, REVLOG_PAIR_AMPS,
        REVLOG_PAIR_TEMP_C, false);
  }

  /**
   * A wpilog and the REV log recorded alongside it on a roboRIO, in the WPILOG format REVLib
   * writes: the wpilog logs a motor's applied output and systemTime (FPGA to wall clock), the
   * revlog the same SPARK MAX's Periodic Status 0 frames on its own clock, named for its
   * wall-clock start in UTC (the roboRIO's zone), 5 s after the wpilog's FPGA 10 s. Its true
   * offset to FPGA time is {@link #REVLOG_PAIR_OFFSET_SEC}, 0.3 s from the coarse estimate the
   * names and systemTime give.
   */
  static Fixture revlogPair(Path dir) throws IOException {
    var path = writeRevlogPair(dir, "2026-revlog_pair.wpilog", java.time.ZoneOffset.UTC,
        "systemTime");
    return new Fixture("revlog_pair", path,
        "A wpilog with the REV log recorded alongside it (SPARK MAX 3 applied output)",
        List.of("revlog sync"));
  }

  /**
   * Writes a revlog pair (see {@link #revlogPair}) whose REV log is named by a clock in
   * {@code zone}: UTC for a roboRIO, the local zone for a desktop running simulation. The wall
   * clock ({@code clockEntry}: systemTime or /SystemStats/EpochTimeMicros) is true epoch time.
   *
   * @return the wpilog's path
   */
  public static Path writeRevlogPair(Path dir, String wpilogName, java.time.ZoneOffset zone,
      String clockEntry) throws IOException {
    return writeRevlogPair(dir, wpilogName, zone, clockEntry, null);
  }

  /** The date an unset roboRIO clock reads (as in real logs) before the Driver Station sets it. */
  public static final java.time.LocalDateTime UNSET_CLOCK =
      java.time.LocalDateTime.of(2024, 12, 18, 14, 8, 5);

  /**
   * As {@link #writeRevlogPair(Path, String, java.time.ZoneOffset, String)}, with the wall clock
   * reading {@link #UNSET_CLOCK} (UTC) plus elapsed time until FPGA {@code clockSetAt}, and true
   * time after.
   */
  public static Path writeRevlogPair(Path dir, String wpilogName, java.time.ZoneOffset zone,
      String clockEntry, Double clockSetAt) throws IOException {
    var path = dir.resolve(wpilogName);
    long unset0 = UNSET_CLOCK.toInstant(java.time.ZoneOffset.UTC).toEpochMilli() * 1000L;
    double start = 10.0;
    double end = 70.0;
    long wall0 = REVLOG_PAIR_WALL.toInstant(java.time.ZoneOffset.UTC).toEpochMilli() * 1000L;
    try (var w = new FixtureWriter(path, AKIT_METADATA)) {
      int n = loops(start, end);
      for (int i = 0; i < n; i++) {
        double t = loopTime(start, i);
        w.dbl("/Drive/FrontLeft/AppliedOutput", t, revlogPairOutput(t));
        if (i % 50 == 0) {
          boolean unset = clockSetAt != null && t < clockSetAt;
          // As on a roboRIO: the first reading, before the Driver Station sets the clock, is
          // 1970 (or, with clockSetAt, the default date until then)
          long wall = i == 0 && clockSetAt == null ? 0L
              : (unset ? unset0 : wall0) + Math.round((t - start) * 1e6);
          w.i64(clockEntry, t, wall);
        }
      }
    }
    var revName = "REV_" + namedAt(REVLOG_PAIR_WALL.plusSeconds(5), zone).format(
        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".revlog";
    writeRevlog(dir.resolve(revName));
    return path;
  }

  /** A UTC wall-clock time as a clock in {@code zone} shows it (and names files with it). */
  public static java.time.LocalDateTime namedAt(java.time.LocalDateTime utc,
      java.time.ZoneOffset zone) {
    return utc.atOffset(java.time.ZoneOffset.UTC).withOffsetSameInstant(zone).toLocalDateTime();
  }

  /** The pair's REV log: SPARK MAX 3's Periodic Status 0 at 100 Hz on its own clock. */
  public static void writeRevlog(Path path) throws IOException {
    try (var w = new FixtureWriter(path, "")) {
      for (int i = 0; i <= 5000; i++) {
        double tr = i * 0.01; // 100 Hz on the revlog's own clock, from 0 s
        w.raw("CAN/3/Periodic Status 0", "raw", tr,
            sparkStatus0(revlogPairOutput(tr + REVLOG_PAIR_OFFSET_SEC)));
      }
    }
  }

  /** A valid log with a header and no entries. */
  static Fixture empty(Path dir) throws IOException {
    var path = dir.resolve("2026-empty.wpilog");
    try (var w = new FixtureWriter(path, "")) {
      // header only
    }
    return new Fixture("empty", path, "A valid log with no entries", List.of("R4"));
  }
}
