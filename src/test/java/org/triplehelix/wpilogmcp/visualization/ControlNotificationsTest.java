/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ControlNotificationsTest {
  static JsonObject view(long revision) {
    var value = new JsonObject(); value.addProperty("view_id", "fixture-view"); value.addProperty("revision", revision); value.addProperty("attached_client_id", "window");
    value.addProperty("configuration", "must not enter notification"); return value;
  }
  @Test void negotiatedAudiencesReplayExactlyAndOverflowResynchronizes() throws Exception {
    var notifications = new ControlNotifications(); notifications.negotiate("window"); notifications.negotiate("agent"); notifications.negotiate("other"); notifications.watch("agent", "fixture-view");
    notifications.changed(view(1));
    assertTrue(notifications.after("unnegotiated", null, 0).isEmpty()); assertTrue(notifications.after("other", null, 0).isEmpty());
    var initial = notifications.after("window", null, 0); assertEquals(1, initial.size()); assertFalse(initial.get(0).json().contains("configuration"));
    assertEquals(initial, notifications.after("agent", null, 0)); assertTrue(notifications.after("window", initial.get(0).id(), 0).isEmpty());
    for (int i = 2; i <= 260; i++) notifications.changed(view(i));
    var overflow = notifications.after("window", initial.get(0).id(), 0); assertEquals(1, overflow.size()); assertTrue(overflow.get(0).json().contains("resync_required"));
    assertTrue(notifications.after("window", "old-epoch:1", 0).get(0).json().contains("resync_required"));
    assertTrue(notifications.after("window", overflow.get(0).id(), 0).isEmpty());
    notifications.changed(view(261)); assertEquals(1, notifications.after("window", overflow.get(0).id(), 0).size());
    notifications.release("window"); assertFalse(notifications.negotiated("window")); assertTrue(notifications.after("window", null, 0).isEmpty());
  }

  @Test void overflowWhileDeliverySleepsRequiresResynchronization() throws Exception {
    var notifications = new ControlNotifications(); notifications.negotiate("window");
    notifications.changed(view(1)); var initial = notifications.after("window", null, 0).get(0);
    var result = new AtomicReference<List<ControlNotifications.Event>>();
    var failure = new AtomicReference<Throwable>();
    var waiting = new Thread(() -> {
      try { result.set(notifications.after("window", initial.id(), 15000)); }
      catch (Throwable e) { failure.set(e); }
    });
    // Hold the real delivery monitor to make the producer burst occur before the awakened
    // transport can reacquire it; no wall-clock race determines whether retention overflows.
    var field = ControlNotifications.class.getDeclaredField("sessions"); field.setAccessible(true);
    Object ring = ((Map<?, ?>) field.get(notifications)).get("window");
    try {
      waiting.start(); long deadline = System.nanoTime() + 2_000_000_000L;
      while (waiting.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.sleep(1);
      assertEquals(Thread.State.TIMED_WAITING, waiting.getState());
      synchronized (ring) { for (int revision = 2; revision <= 300; revision++) notifications.changed(view(revision)); }
      waiting.join(2000); assertFalse(waiting.isAlive()); assertNull(failure.get());
      assertEquals(1, result.get().size()); assertTrue(result.get().get(0).json().contains("resync_required"));
    } finally { waiting.interrupt(); waiting.join(2000); }
  }
}
