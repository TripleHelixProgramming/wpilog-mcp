/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import com.google.gson.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WindowBudgetsTest {
  @Test void capacityAndReleaseDoNotEvictAnotherWindow() {
    var ids = new WindowIds(); var windows = new WindowBudgets(ids);
    IntStream.range(0, 256).parallel().forEach(n -> windows.register("session-" + n, 16777216));
    assertEquals(256, windows.describe(null).size());
    assertThrows(IllegalArgumentException.class, () -> windows.register("overflow", 67108864));
    windows.register("session-0", 134217728);
    assertEquals(256, windows.describe(null).size());
    assertEquals(134217728, row(windows.describe(null), ids.forSession("session-0")).get("decoded_buffer_budget_bytes").getAsLong());
    windows.release("session-1"); windows.release("session-1"); windows.register("replacement", 67108864);
    assertEquals(256, windows.describe(null).size());
    assertNull(row(windows.describe(null), ids.forSession("session-1")));
    assertEquals("client_reported", windows.describe(null).get(0).getAsJsonObject().get("basis").getAsString());
  }
  /** Rows name windows by window ID in ID order, mark only the caller's own, and never carry a session. */
  @Test void descriptionsNameWindowsWithoutTheirSessions() {
    var ids = new WindowIds(); var windows = new WindowBudgets(ids);
    var sessions = IntStream.range(0, 32).mapToObj(n -> java.util.UUID.randomUUID().toString()).toList();
    sessions.forEach(session -> assertTrue(windows.register(session, 16777216).get("current_session").getAsBoolean()));
    for (var caller : java.util.stream.Stream.concat(sessions.stream(), java.util.stream.Stream.of((String) null)).toList()) {
      var rows = windows.describe(caller); var text = rows.toString();
      sessions.forEach(session -> assertFalse(text.contains(session), "a description carries a session ID"));
      var reported = rows.asList().stream().map(r -> r.getAsJsonObject().get("client_id").getAsString()).toList();
      assertEquals(reported.stream().sorted().toList(), reported);
      assertEquals(sessions.stream().map(ids::forSession).sorted().toList(), reported);
      assertEquals(caller == null ? 0 : 1, rows.asList().stream().filter(r -> r.getAsJsonObject().get("current_session").getAsBoolean()).count());
      if (caller != null) assertTrue(row(rows, ids.forSession(caller)).get("current_session").getAsBoolean());
    }
    assertNotEquals(ids.forSession(sessions.get(0)), new WindowIds().forSession(sessions.get(0)), "window IDs are keyed per workspace");
    assertTrue(ids.forSession(sessions.get(0)).matches("window-[0-9a-f]{32}"));
  }
  static JsonObject row(JsonArray rows, String id) {
    return rows.asList().stream().map(JsonElement::getAsJsonObject).filter(r -> r.get("client_id").getAsString().equals(id)).findFirst().orElse(null);
  }
  @Test void invalidByteReportsNeverBecomeServerAllocations() {
    for (var text : new String[] {"{}", "{\"decoded_buffer_budget_bytes\":16777217}", "{\"decoded_buffer_budget_bytes\":1e50}",
        "{\"decoded_buffer_budget_bytes\":{}}", "{\"decoded_buffer_budget_bytes\":[]}", "{\"decoded_buffer_budget_bytes\":true}"})
      assertThrows(IllegalArgumentException.class, () -> WindowBudgets.validate(JsonParser.parseString(text).getAsJsonObject()), text);
    assertEquals(1073741824, WindowBudgets.validate(JsonParser.parseString("{\"decoded_buffer_budget_bytes\":1073741824}").getAsJsonObject()));
    assertEquals("client_window", WindowBudgets.limits().get("scope").getAsString());
  }
}
