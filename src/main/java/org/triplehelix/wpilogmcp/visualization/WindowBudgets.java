/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import com.google.gson.*;
import java.util.*;

/** Window allowances are client-reported facts, never server allocations or access grants.
 * Registration/removal is ordered by the transport's session owner; these short methods do no I/O.
 * The transport session owns its report, but a description names each window only by its public
 * window ID and marks the caller's own: a session ID is a credential, so no report returns one. */
public final class WindowBudgets {
  private static final long MIB = 1048576;
  private record Window(String id, long bytes) {}
  private final Map<String, Window> windows = new HashMap<>();
  private final WindowIds ids;
  public WindowBudgets(WindowIds ids) { this.ids = ids; }
  public static JsonObject limits() {
    var out = new JsonObject(); out.addProperty("default_bytes", 64 * MIB);
    out.addProperty("minimum_bytes", 16 * MIB); out.addProperty("maximum_bytes", 1024 * MIB);
    out.addProperty("setting", "wpilog-mcp.visualization.decodedBufferMiB");
    out.addProperty("scope", "client_window"); out.addProperty("requires_window_reload", true);
    out.addProperty("max_registered_windows", 256); return out;
  }
  public static long validate(JsonObject input) {
    if (!input.keySet().equals(Set.of("decoded_buffer_budget_bytes"))) throw new IllegalArgumentException("Supply only decoded_buffer_budget_bytes; session identity comes from the transport");
    var value = input.get("decoded_buffer_budget_bytes");
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Decoded budget must be an integer byte count");
    long bytes;
    try { bytes = value.getAsBigDecimal().longValueExact(); }
    catch (ArithmeticException e) { throw new IllegalArgumentException("Decoded budget must be an integer byte count"); }
    if (bytes < 16 * MIB || bytes > 1024 * MIB || bytes % MIB != 0) throw new IllegalArgumentException("Decoded budget must be 16–1024 whole MiB");
    return bytes;
  }
  public synchronized JsonObject register(String session, long bytes) {
    if (!windows.containsKey(session) && windows.size() >= 256) throw new IllegalArgumentException("WINDOW_LIMIT: close an unused window before registering another");
    var window = new Window(ids.forSession(session), bytes); windows.put(session, window); return describe(window, true);
  }
  public synchronized void release(String session) { windows.remove(session); }
  /** Rows sort by window ID, so not even their order depends on a session ID. A null caller, such
   * as direct stdio, has no window of its own. */
  public synchronized JsonArray describe(String caller) {
    var out = new JsonArray();
    windows.entrySet().stream().sorted(Comparator.comparing(e -> e.getValue().id()))
        .forEach(e -> out.add(describe(e.getValue(), e.getKey().equals(caller))));
    return out;
  }
  private static JsonObject describe(Window window, boolean caller) {
    var out = new JsonObject(); out.addProperty("client_id", window.id()); out.addProperty("current_session", caller);
    out.addProperty("decoded_buffer_budget_bytes", window.bytes()); out.addProperty("basis", "client_reported"); return out;
  }
}
