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
  @DisplayName("ToolUtils.selectVoltageEntry")
  class VoltageSelection {
    @Test
    @DisplayName("ranks battery > input/bus > generic > rails and requires finite samples")
    void ranking() {
      assertEquals(0, ToolUtils.voltageEntryRank("/systemstats/batteryvoltage"));
      assertEquals(1, ToolUtils.voltageEntryRank("/power/battery/voltage"));
      assertEquals(2, ToolUtils.voltageEntryRank("/pdh/inputvoltage"));
      assertEquals(3, ToolUtils.voltageEntryRank("nt:/smartdashboard/powerdistribution[1]/voltage"));
      assertEquals(4, ToolUtils.voltageEntryRank("/systemstats/5vrail/voltage"));
      assertEquals(4, ToolUtils.voltageEntryRank("/elevator/appliedvoltage"));
      assertEquals(2, ToolUtils.voltageEntryRank("/pdh/busvoltage"));
      assertEquals(4, ToolUtils.voltageEntryRank("/systemstats/brownoutvoltage"));
      // Hints apply to the last two path segments only: "RealOutputs" is not a motor output
      assertEquals(3, ToolUtils.voltageEntryRank("/realoutputs/pdh/voltage"));
      assertEquals(3, ToolUtils.voltageEntryRank("/replayoutputs/pdh/voltage"));
      assertEquals(4, ToolUtils.voltageEntryRank("/realoutputs/elevator/appliedvoltage"));

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
      // Battery entry has no finite samples, so the next rank wins
      assertEquals("/PDH/InputVoltage", ToolUtils.selectVoltageEntry(log, null).orElseThrow());
      // Prefix restricts candidates
      assertEquals("/SystemStats/5vRail/Voltage",
          ToolUtils.selectVoltageEntry(log, "/SystemStats/5v").orElseThrow());
      assertTrue(ToolUtils.selectVoltageEntry(log, "/Vision").isEmpty());
      assertTrue(ToolUtils.selectVoltageEntry(log, "/Nope").isEmpty());
    }

    @Test
    @DisplayName("ties within a rank are broken by WPILOG declaration order")
    void tieBreakByDeclarationOrder() {
      var log = new MockLogBuilder()
          .setPath("/test/voltage_tie.wpilog")
          .addNumericEntry("/Z/PDH/Voltage", new double[]{0}, new double[]{12.0})
          .addNumericEntry("/A/PDH/Voltage", new double[]{0}, new double[]{12.0})
          .build();
      // Both rank 3; the first declared entry (lower id) wins regardless of name order
      assertEquals("/Z/PDH/Voltage", ToolUtils.selectVoltageEntry(log, null).orElseThrow());
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
