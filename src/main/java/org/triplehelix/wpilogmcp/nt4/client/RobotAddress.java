/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/** Address order is explicit: mDNS then team IPv4, or USB, or the supplied host. No discovery. */
public final class RobotAddress {
  private RobotAddress() {}

  public static List<String> team(int team) {
    if (team < 1 || team > 25599) throw new IllegalArgumentException("Team outside IPv4 team range");
    return List.of("roboRIO-" + team + "-FRC.local", "10." + team / 100 + "." + team % 100 + ".2");
  }

  public static List<String> usb() { return List.of("172.22.11.2"); }

  public static URI uri(String host, int port, String clientName) {
    if (host.isBlank() || port < 1 || port > 65535 || clientName.isBlank()
        || clientName.contains("@")) throw new IllegalArgumentException("Invalid NT4 address/name");
    try {
      return new URI("ws", null, host, port, "/nt/" + clientName, null, null);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("Invalid NT4 host", e);
    }
  }
}
