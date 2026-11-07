/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.server;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.Locale;

/** Published listener state: health and live tools must not wait for a bind attempt. */
public record GatewayStatus(State state, int port, String cause, Instant since) {
  public enum State { DISABLED, WAITING, LISTENING, STOPPED }
  public static final GatewayStatus DISABLED = new GatewayStatus(State.DISABLED, 0, null, null);

  public JsonObject json() {
    var result = new JsonObject();
    result.addProperty("state", state.name().toLowerCase(Locale.ROOT));
    result.addProperty("port", port);
    result.addProperty("cause", cause);
    result.addProperty("since", since == null ? null : since.toString());
    return result;
  }
}
