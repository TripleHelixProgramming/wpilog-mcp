/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * Unit tests for the entry-name heuristics shared by the power and DriverStation tools.
 */
class PowerEntrySelectionTest {

  @Nested
  @DisplayName("ToolUtils.isDsEntry")
  class DsEntries {
    @Test
    @DisplayName("recognizes AdvantageKit and WPILib DataLogManager naming")
    void isDsEntry() {
      assertTrue(ToolUtils.isDsEntry("/driverstation/enabled"));
      assertTrue(ToolUtils.isDsEntry("/realoutputs/driverstation/autonomous"));
      assertTrue(ToolUtils.isDsEntry("ds:enabled"));
      assertTrue(ToolUtils.isDsEntry("ds:autonomous"));
      assertTrue(ToolUtils.isDsEntry("ds:joystick0/axes"));
      assertFalse(ToolUtils.isDsEntry("nt:/smartdashboard/ds:enabled"));
      assertFalse(ToolUtils.isDsEntry("/robot/enabled"));
      assertFalse(ToolUtils.isDsEntry("/systemstats/batteryvoltage"));
    }
  }

  @Nested
  @DisplayName("SignalResolver.batteryVoltage: conventions only, no guessing")
  class BatteryVoltage {
    @Test
    @DisplayName("conventional names: BatteryVoltage, Voltage under PowerDistribution/PDH/PDP/Battery")
    void conventions() {
      assertEquals(0, SignalResolver.batteryConventionRank("/SystemStats/BatteryVoltage"));
      assertEquals(0, SignalResolver.batteryConventionRank("/Robot/Battery Voltage"));
      assertEquals(1, SignalResolver.batteryConventionRank("/PowerDistribution/Voltage"));
      assertEquals(1, SignalResolver.batteryConventionRank(
          "NT:/SmartDashboard/PowerDistribution[1]/Voltage"));
      assertEquals(1, SignalResolver.batteryConventionRank("NT:Robot/pdh/Voltage"));
      assertEquals(1, SignalResolver.batteryConventionRank("/Power/Battery/Voltage"));
      assertEquals(-1, SignalResolver.batteryConventionRank("/PDH/InputVoltage"));
      assertEquals(-1, SignalResolver.batteryConventionRank("/Drive/Module0/SupplyVoltage"));
      assertEquals(-1, SignalResolver.batteryConventionRank("/SystemStats/5vRail/Voltage"));
    }

    @Test
    @DisplayName("only name matches: listed to confirm, never chosen; rails never listed")
    void heuristicOnly() {
      var nan = new ArrayList<TimestampedValue>();
      nan.add(new TimestampedValue(0.0, Double.NaN));
      nan.add(new TimestampedValue(1.0, Double.POSITIVE_INFINITY));
      var log = new MockLogBuilder()
          .setPath("/test/voltage_select.wpilog")
          .addEntry("/SystemStats/BatteryVoltage", "double", nan)
          .addNumericEntry("/PDH/InputVoltage", new double[]{0, 1}, new double[]{12.4, 12.3})
          .addNumericEntry("/SystemStats/5vRail/Voltage", new double[]{0, 1}, new double[]{5, 5})
          .addEntry("/Vision/VoltageString", "string", List.of(new TimestampedValue(0.0, "12V")))
          .build();
      // The battery entry has no finite samples; the others match by name only
      var r = SignalResolver.batteryVoltage(log, null, null);
      assertTrue(r.chosen().isEmpty());
      assertEquals(SignalResolver.Tier.HEURISTIC, r.tier());
      assertEquals(List.of("/PDH/InputVoltage"), r.candidates());
      var reason = SignalResolver.unresolvedReason(r, "voltage_entry");
      assertTrue(reason.contains("/PDH/InputVoltage") && reason.contains("voltage_entry")
          && reason.contains("does not guess"), reason);
      // A 5 V rail is never the battery, even when it is all there is
      var rail = SignalResolver.batteryVoltage(log, "/SystemStats/5v", null);
      assertEquals(SignalResolver.Tier.NONE, rail.tier());
      assertTrue(SignalResolver.batteryVoltage(log, "/Nope", null).chosen().isEmpty());
    }

    @Test
    @DisplayName("an explicit voltage_entry is used; a non-numeric one is an error")
    void explicit() {
      var log = new MockLogBuilder()
          .setPath("/test/voltage_explicit.wpilog")
          .addNumericEntry("/PDH/InputVoltage", new double[]{0, 1}, new double[]{12.4, 12.3})
          .addEntry("/Vision/VoltageString", "string", List.of(new TimestampedValue(0.0, "12V")))
          .build();
      var r = SignalResolver.batteryVoltage(log, null, "/PDH/InputVoltage");
      assertEquals("/PDH/InputVoltage", r.chosen().orElseThrow());
      assertEquals(SignalResolver.Tier.EXPLICIT, r.tier());
      assertThrows(IllegalArgumentException.class,
          () -> SignalResolver.batteryVoltage(log, null, "/Vision/VoltageString"));
      assertThrows(IllegalArgumentException.class,
          () -> SignalResolver.batteryVoltage(log, null, "/Missing"));
    }

    @Test
    @DisplayName("ties among conventional entries go to declaration order, flagged ambiguous")
    void tieBreakByDeclarationOrder() {
      var log = new MockLogBuilder()
          .setPath("/test/voltage_tie.wpilog")
          .addNumericEntry("/Z/PDH/Voltage", new double[]{0}, new double[]{12.0})
          .addNumericEntry("/A/PDH/Voltage", new double[]{0}, new double[]{12.0})
          .build();
      var r = SignalResolver.batteryVoltage(log, null, null);
      assertEquals("/Z/PDH/Voltage", r.chosen().orElseThrow());
      assertTrue(r.ambiguous());
      assertTrue(log.entries().get("/Z/PDH/Voltage").id() < log.entries().get("/A/PDH/Voltage").id());
    }
  }

  @Nested
  @DisplayName("PowerAnalysisTool.isCurrentEntryName")
  class CurrentNames {
    @Test
    @DisplayName("accepts amperage names")
    void accepts() {
      for (var name : List.of(
          "/PowerDistribution/ChannelCurrent", "/PowerDistribution/TotalCurrent",
          "/SystemStats/BatteryCurrent", "/RealOutputs/PDH/TotalCurrentAmps",
          "/Spindexer/CurrentAmps", "/Drive/Module2/DriveCurrentAmps", "/Elevator/StatorAmps",
          "/Elevator/STATOR_AMPS", "/Elevator/stator_amps", "Amps", "/Climber/CurrentDraw",
          "/Climber/Currents", "/Climber/Current_A", "Intake Current (A)", "/Intake/CurrentAmperage",
          "/Intake/Current/Stator", "NT:/SmartDashboard/FrontLeft/OutputCurrent",
          "NT:/SmartDashboard/PowerDistribution[1]/Chan3",
          "NT:/SmartDashboard/PowerDistribution[0]/Chan15")) {
        assertTrue(RobotAnalysisTools.PowerAnalysisTool.isCurrentEntryName(name), name);
      }
    }

    @Test
    @DisplayName("rejects names that only contain the letters")
    void rejects() {
      for (var name : List.of(
          "/Drive/Module0/OdometryTimestamps", "/Drive/Gyro/OdometryYawTimestamps",
          "/Drive/SlewRamps", "/Arm/Clamps", "/Log/Stamps",
          "NT:/SmartDashboard/Algae Wrist/Current Angle Degrees", "/Drive/CurrentLimit",
          "/Elevator/CurrentState", "/Elevator/CurrentSetpoint", "/Intake/Current/Setpoint",
          "/Intake/Current/Velocity", "/PowerDistribution/Voltage", "/Elevator/AppliedVoltage",
          "/PowerDistribution/ChannelCount", "/Chan3", "/Robot/Chan3")) {
        assertFalse(RobotAnalysisTools.PowerAnalysisTool.isCurrentEntryName(name), name);
      }
    }
  }

  @Nested
  @DisplayName("ToolUtils.classifyText / normalizeMessage")
  class TextClassification {
    @Test
    @DisplayName("classifies by the first matching line, errors before warnings, ignoring 'default'")
    void classify() {
      assertNull(ToolUtils.classifyText("Robot program starting"));
      assertNull(ToolUtils.classifyText("DefaultDrive scheduled"));
      assertNull(ToolUtils.classifyText("ArmDefaultCommand"));
      var fault = ToolUtils.classifyText("StickyFaults cleared");
      assertEquals("ERROR", fault.type());
      // Errors dominate regardless of line order; the message is the first error line
      var mixed = ToolUtils.classifyText(
          "Shuffleboard.update(): 0.0001s\n\tLoop time of 0.02s overrun\nCAN error device 5");
      assertEquals("ERROR", mixed.type());
      assertEquals("CAN error device 5", mixed.message());
      var warningOnly = ToolUtils.classifyText(
          "Shuffleboard.update(): 0.0001s\n\tLoop time of 0.02s overrun\n\tdisabledPeriodic(): 0.6s");
      assertEquals("WARNING", warningOnly.type());
      assertEquals("Loop time of 0.02s overrun", warningOnly.message());
      var both = ToolUtils.classifyText("Warning: CAN error on 5");
      assertEquals("ERROR", both.type());
      assertEquals("WARNING", ToolUtils.classifyText("Watchdog not fed within 0.02s").type());
      // Not truncated here: callers truncate for display
      var longLine = ToolUtils.classifyText("error " + "x".repeat(300));
      assertEquals(306, longLine.message().length());
      assertEquals(203, ToolUtils.truncate(longLine.message(), ToolUtils.MESSAGE_LINE_LIMIT).length());
    }

    @Test
    @DisplayName("normalizes numbers and whitespace so recurring messages group together")
    void normalize() {
      Function<String, String> n = ToolUtils::normalizeMessage;
      assertEquals("Loop time of #s overrun", n.apply("Loop time of 0.023s overrun"));
      assertEquals("Loop time of #s overrun", n.apply("Loop time of 0.031s overrun"));
      assertEquals("CAN timeout on device #", n.apply("CAN timeout on device 5"));
      assertEquals("Error at Foo.java:#", n.apply("Error at   Foo.java:42"));
      assertEquals("Error at Thread.getStackTrace(Unknown Source)",
          n.apply("Error at Thread.getStackTrace(Unknown Source)"));
      assertEquals("PhotonVision exception", n.apply("  PhotonVision\texception "));
    }
  }
}
