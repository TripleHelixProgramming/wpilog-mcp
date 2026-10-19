/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ContextTopicTest {
  @Test void onlyAnExplicitKnownContextDeclarationSuppliesProviderMetadata() {
    for (String invalid : new String[] {"null", "[]", "true", "{}", "{\"version\":2,\"metadata\":{\"source\":\"ssh\"}}",
        "{\"version\":\"1\",\"metadata\":{\"source\":\"ssh\"}}", "{\"version\":1,\"metadata\":{\"source\":3}}",
        "{\"version\":1,\"metadata\":{}}", "{\"version\":1,\"metadata\":{\"source\":\"\"}}"}) {
      var properties = new JsonObject(); properties.add("wpilog_context", JsonParser.parseString(invalid));
      assertTrue(ContextTopic.metadata("/Daemon/x", properties).isEmpty(), invalid);
    }
    var metadata = new JsonObject(); metadata.addProperty("source", "jmx");
    var properties = ContextTopic.properties(metadata); metadata.addProperty("source", "changed");
    assertTrue(ContextTopic.metadata("/other", properties).isEmpty());
    var result = ContextTopic.metadata("/Daemon/x", properties).orElseThrow(); assertEquals("jmx", result.get("source").getAsString());
    result.addProperty("source", "mutated"); assertEquals("jmx", ContextTopic.metadata("/Daemon/x", properties).orElseThrow().get("source").getAsString());
  }
}
