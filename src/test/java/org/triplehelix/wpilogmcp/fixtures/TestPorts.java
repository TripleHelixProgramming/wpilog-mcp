/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.fixtures;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A port a test names before anything listens on it: one a daemon the test launches will bind,
 * or one that must stay empty. A port the system hands out for port 0 comes from its ephemeral
 * range, which every outgoing connection and every other test's port-0 bind draws from too, so a
 * port closed now and bound later can be taken in between: daemon, installer and import tests
 * failed full builds finding theirs "in use by another program". These ports come from below
 * every system's ephemeral range (Linux starts at 32768, macOS and Windows at 49152), which
 * nothing takes without asking for it by number, and each Gradle fork has a slice of its own, so
 * two forks never hand out the same port. A port is checked free before it is handed out, and
 * none is handed out again until the fork has used its whole slice.
 */
public final class TestPorts {
  private TestPorts() {}

  static final int FIRST = 20_000, SLICES = 50, SLICE = 200;
  private static final int BASE = base(worker());
  private static final AtomicInteger NEXT = new AtomicInteger();

  /** The first port of a fork's slice: consecutive workers, as concurrent forks are, never share one. */
  static int base(long worker) {
    return FIRST + SLICE * (int) Math.floorMod(worker, (long) SLICES);
  }

  /** The Gradle test worker's number, or this process's id outside Gradle. */
  private static long worker() {
    var id = System.getProperty("org.gradle.test.worker");
    try {
      return id == null ? ProcessHandle.current().pid() : Long.parseLong(id);
    } catch (NumberFormatException other) {
      return ProcessHandle.current().pid();
    }
  }

  /** A loopback port nothing listens on now and no other fork is handed. */
  public static int unused() throws IOException {
    for (int tried = 0; tried < SLICE; tried++) {
      int port = BASE + Math.floorMod(NEXT.getAndIncrement(), SLICE);
      try (var probe = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
        return port;
      } catch (IOException taken) {
        // In use, or reserved by the system: the next one.
      }
    }
    throw new IOException("No unused port in " + BASE + " to " + (BASE + SLICE - 1));
  }
}
