/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A read of a memory-mapped log that faults, because the file was truncated or rewritten under
 * the mapping, throws an {@link InternalError}: an Error, not an Exception, which the handler's
 * catches missed, so in stdio mode it ended the server's loop. The handler now answers it like
 * any other tool failure.
 */
@DisplayName("a tool whose read faults")
class FaultingToolTest {

  @Test
  @DisplayName("gets a tool error reply, not an escaped InternalError")
  void internalErrorIsAReply() {
    var registry = new ToolRegistry();
    registry.registerTool(new ToolRegistry.Tool() {
      @Override public String name() { return "faulting_tool"; }
      @Override public String description() { return "Reads a file that was truncated"; }
      @Override public JsonObject inputSchema() { return new ToolRegistry.SchemaBuilder().build(); }
      @Override public com.google.gson.JsonElement execute(JsonObject arguments) {
        throw new InternalError("a fault occurred in a recent unsafe memory access operation");
      }
    });
    var handler = new McpMessageHandler(registry);

    var request = JsonParser.parseString("""
        {"jsonrpc":"2.0","id":7,"method":"tools/call",
         "params":{"name":"faulting_tool","arguments":{}}}
        """).getAsJsonObject();
    var result = handler.handleMessage(request);

    assertNotNull(result.response(), "no reply");
    var reply = result.response();
    assertEquals(7, reply.get("id").getAsInt());
    var text = reply.toString();
    assertTrue(text.contains("changed on disk"), text);
    assertTrue(reply.getAsJsonObject("result").get("isError").getAsBoolean(), text);
  }
}
