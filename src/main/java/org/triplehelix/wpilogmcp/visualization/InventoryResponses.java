/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import java.util.ArrayList;

/** A transport retains inventory response reservations through encoding, including MCP batches, and frees
 * them when its reply's last write begins ({@link #settle}), or at the latest when the scope closes. Direct
 * in-process calls have no transport buffer to reserve. */
public final class InventoryResponses implements AutoCloseable {
  private static final ThreadLocal<InventoryResponses> CURRENT = new ThreadLocal<>();
  private final InventoryResponses previous;
  private final ArrayList<Runnable> releases = new ArrayList<>();
  private boolean settled;
  private InventoryResponses() { previous = CURRENT.get(); CURRENT.set(this); }
  public static InventoryResponses open() { return new InventoryResponses(); }
  static boolean retain(Runnable release) {
    var current = CURRENT.get(); if (current == null) return false;
    if (current.settled) release.run(); else current.releases.add(release);
    return true;
  }
  /** The reply's last write has begun: what it encoded no longer needs its reservation. */
  public void settle() { settled = true; for (var release : releases) release.run(); releases.clear(); }
  @Override public void close() {
    CURRENT.set(previous); for (var release : releases) release.run(); releases.clear();
  }
}
