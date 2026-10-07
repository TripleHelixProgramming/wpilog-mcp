/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.harness;

import edu.wpi.first.hal.HAL;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.wpilibj.RobotBase;
import edu.wpi.first.wpilibj.simulation.SimHooks;

/** A fresh process is a fresh FPGA clock. No HAL simulation GUI or DS socket is loaded. */
public final class Main {
  private Main() {}
  public static void main(String[] args) {
    if (!HAL.initialize(500, 0)) throw new IllegalStateException("Simulation HAL initialization failed");
    SimHooks.pauseTiming(); SimHooks.restartTiming();
    // RobotBase's default startServer() is a no-op once this server is starting/running. Start
    // first so construction never briefly binds the standard dashboard ports on all interfaces.
    NetworkTableInstance.getDefault().startServer("", "127.0.0.1", 0, Integer.parseInt(args[4]));
    RobotBase.startRobot(() -> new ScriptedRobot(args));
  }
}
