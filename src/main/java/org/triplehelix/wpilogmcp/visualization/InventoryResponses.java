/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import java.util.ArrayList;

/** A transport retains inventory response reservations through encoding and the socket write,
 * including MCP batches. Direct in-process calls have no transport buffer to reserve. */
public final class InventoryResponses implements AutoCloseable {
  private static final ThreadLocal<InventoryResponses> CURRENT = new ThreadLocal<>();
  private final InventoryResponses previous;
  private final ArrayList<Runnable> releases = new ArrayList<>();
  private InventoryResponses() { previous = CURRENT.get(); CURRENT.set(this); }
  public static InventoryResponses open() { return new InventoryResponses(); }
  static boolean retain(Runnable release) {
    var current = CURRENT.get(); if (current == null) return false;
    current.releases.add(release); return true;
  }
  @Override public void close() {
    CURRENT.set(previous); for (var release : releases) release.run(); releases.clear();
  }
}
