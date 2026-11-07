/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.tools.ToolBase;

/** A refused call must leave the next stdio request answerable, without restarting the server. */
class StackOverflowBoundaryTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void theToolBaseExplainsOverflowDuringExecutionAndNormalization(boolean duringNormalization) {
    var tool = new OverflowTool() {
      @Override protected JsonElement executeInternal(JsonObject args) {
        if (!duringNormalization) throw new StackOverflowError("synthetic overflow");
        // An actual cycle can exhaust heap first after JIT compilation deepens recursion.
        // Throw at normalization's entry instead, independent of stack size and prior tests.
        return new JsonElement() {
          @Override public JsonElement deepCopy() { return this; }
          @Override public boolean isJsonObject() {
            throw new StackOverflowError("synthetic normalization overflow");
          }
        };
      }
    };
    var result = assertDoesNotThrow(() -> tool.execute(new JsonObject())).getAsJsonObject();
    assertEquals("error", result.get("status").getAsString());
    assertFalse(result.get("success").getAsBoolean());
    assertTrue(result.get("error").getAsString().contains("Stack overflow"));
  }

  enum Failure { TOOL_BASE, RAW_TOOL, SCHEMA, NOTIFICATION }

  @ParameterizedTest
  @EnumSource(Failure.class)
  void stdioAnswersTheFollowingRequestAfterAnOverflow(Failure failure) throws Exception {
    var first = new JsonObject();
    first.addProperty("jsonrpc", "2.0");
    if (failure != Failure.NOTIFICATION) first.addProperty("id", 1);
    first.addProperty("method", failure == Failure.SCHEMA ? "tools/list" : "tools/call");
    var params = new JsonObject();
    params.addProperty("name", "overflow");
    params.add("arguments", new JsonObject());
    first.add("params", params);
    String input = first + "\n" + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}\n";
    var originalIn = System.in;
    var originalOut = System.out;
    var output = new ByteArrayOutputStream();
    try (var wire = new PrintStream(output, true, StandardCharsets.UTF_8)) {
      System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
      System.setOut(wire);
      var registry = new ToolRegistry();
      registry.registerTool(failure == Failure.TOOL_BASE ? new OverflowTool() : new McpServer.Tool() {
        public String name() { return "overflow"; }
        public String description() { return "Synthetic recursion failure"; }
        public JsonObject inputSchema() {
          if (failure == Failure.SCHEMA) throw new StackOverflowError("synthetic schema overflow");
          return new JsonObject();
        }
        public JsonElement execute(JsonObject args) { throw new StackOverflowError("synthetic overflow"); }
      });
      var server = new McpServer(registry);
      // Finite input ends at EOF; no timing guard can hide a failed reader loop.
      assertDoesNotThrow(server::run);
    } finally {
      System.setIn(originalIn);
      System.setOut(originalOut);
    }
    var replies = output.toString(StandardCharsets.UTF_8).lines()
        .map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
    assertEquals(failure == Failure.NOTIFICATION ? 1 : 2, replies.size());
    if (failure != Failure.NOTIFICATION) {
      var refused = replies.get(0);
      assertEquals(1, refused.get("id").getAsInt());
      if (failure == Failure.TOOL_BASE) {
        var content = refused.getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject();
        var result = JsonParser.parseString(content.get("text").getAsString()).getAsJsonObject();
        assertEquals("error", result.get("status").getAsString());
        assertTrue(result.get("error").getAsString().contains("Stack overflow"));
      } else {
        assertEquals(JsonRpc.INTERNAL_ERROR, refused.getAsJsonObject("error").get("code").getAsInt());
        assertTrue(refused.getAsJsonObject("error").get("message").getAsString().contains("Stack overflow"));
      }
    }
    var next = replies.get(replies.size() - 1);
    assertEquals(2, next.get("id").getAsInt());
    assertTrue(next.has("result"));
    assertFalse(next.has("error"));
  }

  private static class OverflowTool extends ToolBase {
    public String name() { return "overflow"; }
    public String description() { return "Synthetic recursion failure"; }
    public JsonObject inputSchema() { return new JsonObject(); }
    protected JsonElement executeInternal(JsonObject args) { throw new StackOverflowError("synthetic overflow"); }
  }
}
