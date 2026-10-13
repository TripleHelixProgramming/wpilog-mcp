/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * A child test process that steals the port after the manager's probe, before its child binds.
 * The existing launcher seam makes the real packaged daemon's bind failure deterministic;
 * neither a sleep nor a production startup hook is needed.
 */
public final class DaemonPortCollision {
  private DaemonPortCollision() {}

  public static void main(String[] args) throws Exception {
    var home = Path.of(System.getProperty("user.home"));
    int port = Integer.parseInt(args[3]);
    ServerSocket[] held = {null};
    Process[] child = {null};
    boolean started;
    try {
      var manager = new DaemonManager(home.resolve(".wpilog-mcp/run"), Duration.ofSeconds(10),
          (command, environment, log) -> {
            held[0] = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
            var actual = new ArrayList<>(command);
            // A substituted launcher receives a placeholder JAR; keep the real daemon code.
            actual.set(actual.indexOf("-jar") + 1, args[0]);
            var builder = new ProcessBuilder(actual).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log));
            builder.environment().putAll(environment);
            child[0] = builder.start();
            return new DaemonManager.Launched() {
              @Override public long pid() { return child[0].pid(); }
              @Override public boolean isAlive() { return child[0].isAlive(); }
              @Override public void destroy() { child[0].destroy(); }
            };
          });
      started = manager.spawnDaemon(args[1], port, Path.of(args[2]));
    } finally {
      if (held[0] != null) held[0].close();
      if (child[0] != null) {
        child[0].destroyForcibly();
        if (!child[0].waitFor(10, TimeUnit.SECONDS)) {
          throw new AssertionError("Collision fixture could not reap its child");
        }
      }
    }
    System.exit(started ? 0 : 1);
  }
}
