/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client.LatestValue;

/** Two concurrent table reads can straddle an unannounce and redeclaration of the same name. */
class LiveValueSnapshotTest {
  @Test void aRedeclaredTopicCannotRelabelAValueFromThePreviousDeclaration() {
    var held = new LatestValue("{}", 20_000, 21_000, "json");
    var latest = Map.of("/x", held);
    // The tool copied the old value before the client unannounced and declared /x as int.
    var topics = Map.of("/x", new Announce("/x", 2, "int", null, new JsonObject()));
    var response = LiveTools.GetLatestValues.currentValues(Set.of("/x"), latest, topics, 25_000.0).build();
    var row = response.getAsJsonArray("values").get(0).getAsJsonObject();
    assertEquals("json", row.get("type").getAsString());
    assertEquals("{}", row.get("value").getAsString());
    assertEquals(.02, row.get("timestamp_sec").getAsDouble());
    assertEquals(5, row.get("age_ms").getAsDouble());
  }
}
