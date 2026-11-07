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
    if (Boolean.getBoolean("harness.realtime")) SimHooks.resumeTiming();
    if (args.length > 0 && args[0].equals("--replay")) {
      try { LogReplay.run(args); Runtime.getRuntime().halt(0); }
      catch (Throwable failure) {
        try {
          var diagnostic = new com.google.gson.JsonObject(); diagnostic.addProperty("category", failure.getClass().getSimpleName());
          diagnostic.add("stack", new com.google.gson.Gson().toJsonTree(java.util.Arrays.stream(failure.getStackTrace()).map(Object::toString).toList()));
          java.nio.file.Files.writeString(java.nio.file.Path.of(args[2]).resolve("failure.json"), diagnostic.toString());
        } catch (Exception ignored) { }
        // The source belongs to the team. Even a decode failure must not print one of its values.
        System.err.println(args.length > 1 ? args[1] : "--replay requires a file"); Runtime.getRuntime().halt(1);
      }
    }
    // RobotBase's default startServer() is a no-op once this server is starting/running. Start
    // first so construction never briefly binds the standard dashboard ports on all interfaces.
    NetworkTableInstance.getDefault().startServer("", System.getProperty("harness.bind", "127.0.0.1"), 0, Integer.parseInt(args[4]));
    RobotBase.startRobot(() -> new ScriptedRobot(args));
  }
}
