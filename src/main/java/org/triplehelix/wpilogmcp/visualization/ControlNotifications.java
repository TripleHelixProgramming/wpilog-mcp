/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded, session-scoped control invalidations. Publication only appends small immutable text;
 * transport threads perform writes. Telemetry and configuration buffers never enter this ring. */
public final class ControlNotifications {
  public static final String CAPABILITY = "wpilog/visualizationNotifications";
  public static final String METHOD = "notifications/experimental/visualization";
  public record Event(String id, String json) {}
  private final String epoch = UUID.randomUUID().toString();
  private final Map<String, Ring> sessions = new ConcurrentHashMap<>();
  private static final class Ring {
    final ArrayDeque<Event> events = new ArrayDeque<>(); final Set<String> views = new HashSet<>(); long sequence; int bytes;
  }
  public void negotiate(String session) { sessions.putIfAbsent(session, new Ring()); }
  public boolean negotiated(String session) { return sessions.containsKey(session); }
  public void release(String session) { var ring = sessions.remove(session); if (ring != null) synchronized (ring) { ring.notifyAll(); } }
  public void watch(String session, String view) {
    var ring = sessions.get(session); if (ring != null) synchronized (ring) { if (ring.views.size() < 256) ring.views.add(view); }
  }
  public void changed(JsonObject view) {
    for (var entry : sessions.entrySet()) {
      var ring = entry.getValue();
      synchronized (ring) {
        String id = view.get("view_id").getAsString();
        if (!ring.views.contains(id) && !entry.getKey().equals(view.get("attached_client_id").isJsonNull() ? null : view.get("attached_client_id").getAsString())) continue;
        var event = event("view_revision_changed", ++ring.sequence, id, view.get("revision").getAsLong());
        ring.events.add(event); ring.bytes += event.json().getBytes(StandardCharsets.UTF_8).length;
        while (ring.events.size() > 256 || ring.bytes > 1 << 20) ring.bytes -= ring.events.removeFirst().json().getBytes(StandardCharsets.UTF_8).length;
        ring.notifyAll();
      }
    }
  }
  private Event event(String kind, long sequence, String view, Long revision) {
    var params = new JsonObject(); params.addProperty("api_version", 1); params.addProperty("kind", kind); params.addProperty("server_epoch", epoch); params.addProperty("sequence", sequence);
    if (view != null) params.addProperty("view_id", view); if (revision != null) params.addProperty("revision", revision);
    var rpc = new JsonObject(); rpc.addProperty("jsonrpc", "2.0"); rpc.addProperty("method", METHOD); rpc.add("params", params);
    return new Event(epoch + ":" + sequence, rpc.toString());
  }
  public List<Event> after(String session, String lastId, long waitMs) throws InterruptedException {
    var ring = sessions.get(session); if (ring == null) return List.of();
    synchronized (ring) {
      long after = 0;
      if (lastId != null) try {
        if (!lastId.startsWith(epoch + ":")) return List.of(event("resync_required", ring.sequence, null, null));
        after = Long.parseLong(lastId.substring(epoch.length() + 1));
      } catch (NumberFormatException e) { return List.of(event("resync_required", ring.sequence, null, null)); }
      if (after == ring.sequence && waitMs > 0) ring.wait(Math.min(15000, waitMs));
      // A producer burst can exhaust retention before this waiter reacquires the monitor.
      if (after > ring.sequence || !ring.events.isEmpty() && after < sequence(ring.events.getFirst()) - 1)
        return List.of(event("resync_required", ring.sequence, null, null));
      final long acknowledged = after;
      return ring.events.stream().filter(e -> sequence(e) > acknowledged).toList();
    }
  }
  private static long sequence(Event event) { return Long.parseLong(event.id().substring(event.id().lastIndexOf(':') + 1)); }
}
