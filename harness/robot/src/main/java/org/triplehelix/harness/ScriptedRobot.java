/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.harness;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.networktables.*;
import edu.wpi.first.util.datalog.IntegerLogEntry;
import edu.wpi.first.util.datalog.StringLogEntry;
import edu.wpi.first.wpilibj.*;
import edu.wpi.first.wpilibj.simulation.DriverStationSim;
import edu.wpi.first.wpilibj.simulation.RoboRioSim;
import edu.wpi.first.wpilibj.simulation.SimHooks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Published values are simple functions of the timeline's tick, with explicit FPGA timestamps.
 * The desktop HAL clock advances in 20 ms steps paced at real time. The runner releases the clock
 * only after capture is subscribed, so startup scheduling cannot lose the beginning of the script.
 */
public final class ScriptedRobot extends TimedRobot {
  private final JsonObject timeline, boot;
  private final Path logs, control;
  private final int bootNumber;
  private final long periodUs;
  private final NetworkTableInstance nt = NetworkTableInstance.getDefault();
  private DoublePublisher sine;
  private IntegerPublisher counter;
  private BooleanPublisher toggle;
  private StringPublisher string;
  private RawPublisher raw;
  private StructPublisher<Pose2d> pose;
  private StructArrayPublisher<SwerveModuleState> modules;
  private final java.util.List<Publisher> publishers = new java.util.ArrayList<>();
  private boolean matchApplied, logClosed;
  private java.io.BufferedWriter ticks;
  private final int gatewayPort;
  private final Path gatewayOutput;
  private GatewayProbe gateway;
  private final boolean realtime = Boolean.getBoolean("harness.realtime");
  private long nextSampleUs;
  private String lastConsoleState;


  public ScriptedRobot(String[] args) {
    super(0.02);
    try {
      timeline = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
      bootNumber = Integer.parseInt(args[1]); boot = timeline.getAsJsonArray("boots").get(bootNumber).getAsJsonObject();
      logs = Path.of(args[2]); control = Path.of(args[3]);
      gatewayPort = args.length > 5 ? Integer.parseInt(args[5]) : 0;
      gatewayOutput = args.length > 6 ? Path.of(args[6]) : null;
      periodUs = timeline.get("period_us").getAsLong();
      nextSampleUs = timeline.get("sample_start_us").getAsLong();
      if (periodUs != 20000) throw new IllegalArgumentException("TimedRobot harness period_us must be 20000");
    } catch (Exception e) { throw new IllegalArgumentException("Invalid harness arguments", e); }
  }

  @Override public void robotInit() {
    try {
      RoboRioSim.setTeamNumber(timeline.get("team_number").getAsInt());
      RoboRioSim.setComments(timeline.get("comments").getAsString());
      DataLogManager.start(logs.toString(), "", 0.02);
      DriverStation.startDataLog(DataLogManager.getLog());
      String serial = RobotController.getSerialNumber();
      if (serial.isEmpty()) serial = System.getenv("serialnum");
      if (serial == null || serial.isBlank()) throw new IllegalStateException("Harness requires serialnum");
      identity("SerialNumber", serial); identity("Comments", RobotController.getComments());
      new IntegerLogEntry(DataLogManager.getLog(), "/SystemStats/TeamNumber").append(RobotController.getTeamNumber(), 0);
      var team = nt.getIntegerTopic("/SystemStats/TeamNumber").publish(); publishers.add(team); team.set(RobotController.getTeamNumber(), 0);
      var options = new PubSubOption[] {PubSubOption.keepDuplicates(true), PubSubOption.sendAll(true), PubSubOption.periodic(0.02)};
      sine = nt.getDoubleTopic("/Harness/Sine").publish(options);
      counter = nt.getIntegerTopic("/Harness/Counter").publish(options);
      toggle = nt.getBooleanTopic("/Harness/Toggle").publish(options);
      string = nt.getStringTopic("/Harness/String").publish(options);
      raw = nt.getRawTopic("/Harness/Raw").publish("raw", options);
      pose = nt.getStructTopic("/Harness/Pose", Pose2d.struct).publish(options);
      modules = nt.getStructArrayTopic("/Harness/Modules", SwerveModuleState.struct).publish(options);
      publishers.addAll(java.util.List.of(sine, counter, toggle, string, raw, pose, modules));
      applyDriverStation(0); nt.flush();
      Files.createDirectories(control);
      if (gatewayPort != 0) gateway = new GatewayProbe(gatewayPort, control, gatewayOutput);
      ticks = Files.newBufferedWriter(control.resolve("ticks.csv"));
      Files.writeString(control.resolve("ready"), "ready\n");
      // JMX uptime is monotonic wall time. The container uses the HAL's continuous clock,
      // not 20 ms stair steps whose quantization would exceed a sub-millisecond SSH/JMX RTT.
      if (realtime) return;
      var pacer = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "scripted-hal-clock"); t.setDaemon(true); return t;
      });
      pacer.scheduleAtFixedRate(() -> {
        if (!Files.exists(control.resolve("go"))) return;
        try {
          long next = RobotController.getFPGATime() + periodUs;
          ticks.write(next + "," + System.currentTimeMillis() * 1000 + "\n"); ticks.flush();
          SimHooks.stepTiming(periodUs / 1_000_000.0);
        } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
      }, 0, periodUs, TimeUnit.MICROSECONDS);
    } catch (Exception e) { throw new IllegalStateException("Harness robot startup failed", e); }
  }

  private void identity(String suffix, String value) {
    String name = "/SystemStats/" + suffix;
    new StringLogEntry(DataLogManager.getLog(), name).append(value, 0);
    var publisher = nt.getStringTopic(name).publish(); publishers.add(publisher); publisher.set(value, 0);
  }

  private void applyDriverStation(long timeUs) {
    String state = "disabled";
    for (var phase : boot.getAsJsonArray("phases")) {
      var p = phase.getAsJsonObject(); if (timeUs >= p.get("at_us").getAsLong()) state = p.get("state").getAsString();
    }
    if (!state.equals(lastConsoleState)) {
      System.out.println("harness boot=" + bootNumber + " state=" + state);
      lastConsoleState = state;
    }
    DriverStationSim.setDsAttached(true);
    DriverStationSim.setEnabled(!state.equals("disabled")); DriverStationSim.setAutonomous(state.equals("autonomous"));
    var match = boot.getAsJsonObject("match");
    if (!matchApplied && timeUs >= match.get("at_us").getAsLong()) {
      DriverStationSim.setEventName(match.get("event").getAsString());
      DriverStationSim.setMatchType(DriverStation.MatchType.valueOf(match.get("type").getAsString()));
      DriverStationSim.setMatchNumber(match.get("number").getAsInt());
      DriverStationSim.setFmsAttached(true); matchApplied = true;
    }
    DriverStationSim.notifyNewData();
  }

  @Override public void robotPeriodic() {
    long nowUs = RobotController.getFPGATime();
    if (realtime && !Files.exists(control.resolve("go"))) return;
    applyDriverStation(nowUs);
    if (realtime) {
      try { ticks.write(nowUs + "," + System.currentTimeMillis() * 1000 + "\n"); ticks.flush(); }
      catch (Exception e) { throw new IllegalStateException(e); }
    }
    long start = timeline.get("sample_start_us").getAsLong(), end = timeline.get("sample_end_us").getAsLong();
    // A late callback emits every due scripted record with its specified timestamp. This
    // leaves the real HAL/JVM clocks running while preserving the independent timeline oracle.
    while (nextSampleUs <= nowUs && nextSampleUs < end) {
      long timeUs = nextSampleUs; nextSampleUs += periodUs;
      long index = (timeUs - start) / periodUs; double seconds = timeUs / 1_000_000.0;
      sine.set(Math.sin(2 * Math.PI * boot.get("sine_hz").getAsDouble() * seconds), timeUs);
      long reset = 0;
      for (var at : boot.getAsJsonArray("counter_resets")) if (at.getAsLong() <= index) reset = at.getAsLong();
      counter.set(index - reset, timeUs); toggle.set((index / 5) % 2 == 0, timeUs);
      string.set("boot-" + bootNumber + ":tick-" + index, timeUs);
      byte[] bytes = new byte[timeline.get("raw_bytes").getAsInt()];
      for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (index + i + bootNumber);
      raw.set(bytes, timeUs);
      pose.set(new Pose2d(seconds, -seconds / 2, new Rotation2d(seconds / 8)), timeUs);
      modules.set(new SwerveModuleState[] {
          new SwerveModuleState(seconds, new Rotation2d(seconds / 9)),
          new SwerveModuleState(-seconds, new Rotation2d(-seconds / 10))}, timeUs);
    }
    nt.flush();
    if (!logClosed && nowUs >= end + 1_000_000) {
      // stop() closes the file; DS/NT still own references to the DataLog object.
      DataLogManager.stop(); logClosed = true;
      if (gateway != null) {
        try { gateway.close(); } catch (Exception e) { throw new IllegalStateException("Gateway probe shutdown failed", e); }
      }
    }
    if (nowUs >= boot.get("end_us").getAsLong()) {
      try { ticks.close(); Files.writeString(control.resolve("done"), "done\n"); }
      catch (Exception e) { throw new IllegalStateException(e); }
      // Model the process boundary after the log has stopped. Native NT/DS worker threads still
      // reference WPILib globals; running C++ static destructors under them can abort on macOS.
      Runtime.getRuntime().halt(boot.get("reboot").getAsBoolean() ? 75 : 0);
    }
  }
}
