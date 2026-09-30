/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class McpMessageHandlerTest {
  private ToolRegistry registry;
  private McpMessageHandler handler;

  @BeforeEach
  void setUp() {
    registry = new ToolRegistry();
    handler = new McpMessageHandler(registry);
  }

  @Test
  @DisplayName("initialize returns protocol version and server info")
  void initialize() {
    var msg = parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
    var result = handler.handleMessage(msg);

    assertNotNull(result.response());
    assertFalse(result.shouldShutdown());

    var jsonResult = result.response().getAsJsonObject("result");
    assertEquals("2025-03-26", jsonResult.get("protocolVersion").getAsString());
    assertEquals("wpilog-mcp", jsonResult.getAsJsonObject("serverInfo").get("name").getAsString());
  }

  @Test
  @DisplayName("initialize omits instructions when the registry has none")
  void initializeWithoutInstructions() {
    var msg = parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
    var jsonResult = handler.handleMessage(msg).response().getAsJsonObject("result");
    assertFalse(jsonResult.has("instructions"));

    registry.setServerInstructions("   ");
    jsonResult = handler.handleMessage(msg).response().getAsJsonObject("result");
    assertFalse(jsonResult.has("instructions"), "Blank instructions must be omitted");
  }

  @Test
  @DisplayName("initialize includes server instructions when set")
  void initializeWithInstructions() {
    registry.setServerInstructions("Answer the question asked first.");
    var msg = parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
    var jsonResult = handler.handleMessage(msg).response().getAsJsonObject("result");

    assertEquals("Answer the question asked first.", jsonResult.get("instructions").getAsString());
    assertEquals("2025-03-26", jsonResult.get("protocolVersion").getAsString());
  }

  @Test
  @DisplayName("tools/list includes _meta only for tools that provide it")
  void toolsListMeta() {
    registry.registerTool(new ToolRegistry.Tool() {
      @Override public String name() { return "plain_tool"; }
      @Override public String description() { return "No metadata"; }
      @Override public JsonObject inputSchema() { return new ToolRegistry.SchemaBuilder().build(); }
      @Override public com.google.gson.JsonElement execute(JsonObject arguments) { return new JsonObject(); }
    });
    registry.registerTool(new ToolRegistry.Tool() {
      @Override public String name() { return "meta_tool"; }
      @Override public String description() { return "Has metadata"; }
      @Override public JsonObject inputSchema() { return new ToolRegistry.SchemaBuilder().build(); }
      @Override public com.google.gson.JsonElement execute(JsonObject arguments) { return new JsonObject(); }
      @Override public JsonObject meta() {
        var meta = new JsonObject();
        meta.addProperty("anthropic/alwaysLoad", true);
        return meta;
      }
    });

    var msg = parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
    var tools = handler.handleMessage(msg).response().getAsJsonObject("result").getAsJsonArray("tools");
    assertEquals(2, tools.size());
    for (var element : tools) {
      var tool = element.getAsJsonObject();
      if (tool.get("name").getAsString().equals("meta_tool")) {
        assertTrue(tool.getAsJsonObject("_meta").get("anthropic/alwaysLoad").getAsBoolean());
      } else {
        assertFalse(tool.has("_meta"));
      }
    }
  }

  @Test
  @DisplayName("initialize with SessionManager creates session")
  void initializeWithSession() {
    var sessionManager = new SessionManager();
    var sessionHandler = new McpMessageHandler(registry, sessionManager);

    var msg = parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
    var result = sessionHandler.handleMessage(msg);

    assertNotNull(result.newSessionId());
    assertEquals(1, sessionManager.size());
    assertNotNull(sessionManager.getSession(result.newSessionId()));
  }

  @Test
  @DisplayName("tools/list returns empty when no tools registered")
  void toolsListEmpty() {
    var msg = parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
    var result = handler.handleMessage(msg);

    var tools = result.response().getAsJsonObject("result").getAsJsonArray("tools");
    assertEquals(0, tools.size());
  }

  @Test
  @DisplayName("tools/list returns registered tools")
  void toolsList() {
    registry.registerTool(new ToolRegistry.Tool() {
      @Override public String name() { return "test_tool"; }
      @Override public String description() { return "A test tool"; }
      @Override public JsonObject inputSchema() { return new ToolRegistry.SchemaBuilder().build(); }
      @Override public com.google.gson.JsonElement execute(JsonObject arguments) { return new JsonObject(); }
    });

    var msg = parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
    var result = handler.handleMessage(msg);

    var tools = result.response().getAsJsonObject("result").getAsJsonArray("tools");
    assertEquals(1, tools.size());
    assertEquals("test_tool", tools.get(0).getAsJsonObject().get("name").getAsString());
  }

  @Test
  @DisplayName("tools/call with unknown tool returns error")
  void toolCallUnknown() {
    var msg = parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
        + "\"params\":{\"name\":\"nonexistent\"}}");
    var result = handler.handleMessage(msg);

    assertTrue(result.response().has("error"));
  }

  @Test
  @DisplayName("ping returns empty result")
  void ping() {
    var msg = parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
    var result = handler.handleMessage(msg);

    assertNotNull(result.response());
    assertTrue(result.response().has("result"));
  }

  @Test
  @DisplayName("shutdown sets shouldShutdown flag")
  void shutdown() {
    var msg = parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"shutdown\"}");
    var result = handler.handleMessage(msg);

    assertTrue(result.shouldShutdown());
  }

  @Test
  @DisplayName("unknown method returns METHOD_NOT_FOUND")
  void unknownMethod() {
    var msg = parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"bogus\"}");
    var result = handler.handleMessage(msg);

    var error = result.response().getAsJsonObject("error");
    assertEquals(JsonRpc.METHOD_NOT_FOUND, error.get("code").getAsInt());
  }

  @Test
  @DisplayName("missing method returns INVALID_REQUEST")
  void missingMethod() {
    var msg = parse("{\"jsonrpc\":\"2.0\",\"id\":1}");
    var result = handler.handleMessage(msg);

    var error = result.response().getAsJsonObject("error");
    assertEquals(JsonRpc.INVALID_REQUEST, error.get("code").getAsInt());
  }

  @Test
  @DisplayName("initialized notification returns no response")
  void initializedNotification() {
    var msg = parse("{\"jsonrpc\":\"2.0\",\"method\":\"initialized\"}");
    var result = handler.handleMessage(msg);

    assertNull(result.response());
    assertFalse(result.shouldShutdown());
  }

  @Test
  @DisplayName("session context is set during tool execution")
  void sessionContextDuringToolExecution() {
    var session = new McpSession();
    var capturedSession = new McpSession[1];

    registry.registerTool(new ToolRegistry.Tool() {
      @Override public String name() { return "capture_session"; }
      @Override public String description() { return "Captures session context"; }
      @Override public JsonObject inputSchema() { return new ToolRegistry.SchemaBuilder().build(); }
      @Override public com.google.gson.JsonElement execute(JsonObject arguments) {
        capturedSession[0] = SessionContext.current();
        return new JsonObject();
      }
    });

    var msg = parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
        + "\"params\":{\"name\":\"capture_session\"}}");
    handler.handleMessage(msg, session);

    assertSame(session, capturedSession[0]);
    // Context should be cleared after the call
    assertNull(SessionContext.current());
  }

  @Test
  @DisplayName("concurrent tool calls with different sessions are isolated")
  void concurrentToolCallsWithSessions() throws InterruptedException {
    var sessionManager = new SessionManager();
    var sessionHandler = new McpMessageHandler(registry, sessionManager);

    // Register a tool that captures the session context
    var capturedSessionIds = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
    registry.registerTool(new ToolRegistry.Tool() {
      @Override public String name() { return "capture_session_id"; }
      @Override public String description() { return "test"; }
      @Override public JsonObject inputSchema() { return new JsonObject(); }
      @Override public com.google.gson.JsonElement execute(JsonObject args) {
        var session = SessionContext.current();
        if (session != null) capturedSessionIds.add(session.getId());
        try { Thread.sleep(5); } catch (InterruptedException ignored) {} // Simulate work
        var result = new JsonObject();
        result.addProperty("session_id", session != null ? session.getId() : "none");
        return result;
      }
    });

    // Create sessions
    int threadCount = 10;
    var sessions = new McpSession[threadCount];
    for (int i = 0; i < threadCount; i++) {
      sessions[i] = sessionManager.createSession();
    }

    var errors = new java.util.concurrent.atomic.AtomicInteger(0);
    var barrier = new java.util.concurrent.CyclicBarrier(threadCount);
    var threads = new Thread[threadCount];

    for (int i = 0; i < threadCount; i++) {
      final var session = sessions[i];
      threads[i] = new Thread(() -> {
        try {
          barrier.await(); // All threads start simultaneously
          var msg = parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
              + "\"params\":{\"name\":\"capture_session_id\"}}");
          sessionHandler.handleMessage(msg, session);
        } catch (Exception e) {
          errors.incrementAndGet();
        }
      });
    }
    for (var t : threads) t.start();
    for (var t : threads) t.join();

    assertEquals(0, errors.get(), "Concurrent tool calls should not throw");
    assertEquals(threadCount, capturedSessionIds.size(),
        "Each thread should have captured its session");

    // Verify SessionContext is cleared after each call (ThreadLocal cleanup)
    assertNull(SessionContext.current(), "SessionContext should be cleared after calls");
  }

  @Nested
  @DisplayName("Malformed messages and notifications")
  class MalformedMessages {

    /** Registers a tool that returns its arguments, and records that it ran. */
    private java.util.concurrent.atomic.AtomicInteger registerEcho() {
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      registry.registerTool(new ToolRegistry.Tool() {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "Returns its arguments"; }
        @Override public JsonObject inputSchema() { return new ToolRegistry.SchemaBuilder().build(); }
        @Override public com.google.gson.JsonElement execute(JsonObject arguments) {
          calls.incrementAndGet();
          return arguments.deepCopy();
        }
      });
      return calls;
    }

    private JsonObject errorOf(McpMessageHandler.HandlerResult result) {
      assertNotNull(result.response(), "A malformed request must be answered");
      assertTrue(result.response().has("error"), "Expected an error: " + result.response());
      return result.response().getAsJsonObject("error");
    }

    private void assertInvalidParams(String json) {
      var result = handler.handleMessage(parse(json));
      assertEquals(JsonRpc.INVALID_PARAMS, errorOf(result).get("code").getAsInt(),
          "Expected INVALID_PARAMS for " + json + ": " + result.response());
      assertEquals(1, result.response().get("id").getAsInt(), "The id must be preserved");
    }

    @Test
    @DisplayName("numeric method is INVALID_REQUEST with the id preserved")
    void numericMethod() {
      var result = handler.handleMessage(parse("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":5}"));
      assertEquals(JsonRpc.INVALID_REQUEST, errorOf(result).get("code").getAsInt());
      assertEquals(7, result.response().get("id").getAsInt());
    }

    @Test
    @DisplayName("object method is INVALID_REQUEST with a string id preserved")
    void objectMethod() {
      var result = handler.handleMessage(
          parse("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":{\"a\":1}}"));
      assertEquals(JsonRpc.INVALID_REQUEST, errorOf(result).get("code").getAsInt());
      assertEquals("abc", result.response().get("id").getAsString());
    }

    @Test
    @DisplayName("null method is INVALID_REQUEST")
    void nullMethod() {
      var result = handler.handleMessage(parse("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":null}"));
      assertEquals(JsonRpc.INVALID_REQUEST, errorOf(result).get("code").getAsInt());
      assertEquals(3, result.response().get("id").getAsInt());
    }

    @Test
    @DisplayName("non-string method without an id is still answered, with id null")
    void nonStringMethodWithoutId() {
      var result = handler.handleMessage(parse("{\"jsonrpc\":\"2.0\",\"method\":5}"));
      assertEquals(JsonRpc.INVALID_REQUEST, errorOf(result).get("code").getAsInt());
      assertTrue(result.response().get("id").isJsonNull());
    }

    @Test
    @DisplayName("tools/call with non-object, null, or missing params is INVALID_PARAMS")
    void badParams() {
      assertInvalidParams("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":\"bar\"}");
      assertInvalidParams("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":[1,2]}");
      assertInvalidParams("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":7}");
      assertInvalidParams("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":null}");
      assertInvalidParams("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"}");
    }

    @Test
    @DisplayName("tools/call with a null, non-string, or missing name is INVALID_PARAMS")
    void badName() {
      registerEcho();
      assertInvalidParams(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":null}}");
      assertInvalidParams(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":5}}");
      assertInvalidParams(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":{}}}");
      assertInvalidParams(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{}}");
    }

    @Test
    @DisplayName("tools/call with non-object arguments is INVALID_PARAMS, and the tool does not run")
    void badArguments() {
      var calls = registerEcho();
      assertInvalidParams("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
          + "\"params\":{\"name\":\"echo\",\"arguments\":\"nope\"}}");
      assertInvalidParams("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
          + "\"params\":{\"name\":\"echo\",\"arguments\":[1]}}");
      assertInvalidParams("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
          + "\"params\":{\"name\":\"echo\",\"arguments\":3}}");
      assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("tools/call with null or missing arguments runs the tool without any")
    void nullArgumentsMeanNone() {
      var calls = registerEcho();
      var withNull = handler.handleMessage(parse("{\"jsonrpc\":\"2.0\",\"id\":1,"
          + "\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":null}}"));
      var without = handler.handleMessage(parse("{\"jsonrpc\":\"2.0\",\"id\":2,"
          + "\"method\":\"tools/call\",\"params\":{\"name\":\"echo\"}}"));

      assertTrue(withNull.response().has("result"), withNull.response().toString());
      assertTrue(without.response().has("result"), without.response().toString());
      assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("a request without an id for a known method is a notification: no reply")
    void knownMethodNotificationsGetNoReply() {
      assertNull(handler.handleMessage(parse("{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}")).response());
      assertNull(handler.handleMessage(parse("{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\"}")).response());
      assertNull(handler.handleMessage(parse("{\"jsonrpc\":\"2.0\",\"method\":\"prompts/list\"}")).response());
      assertNull(handler.handleMessage(parse("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}")).response(),
          "An explicit null id is treated as no id");
      assertNull(handler.handleMessage(parse("{\"jsonrpc\":\"2.0\",\"method\":\"bogus\"}")).response(),
          "An unknown notification gets no reply either");
    }

    @Test
    @DisplayName("a tools/call notification runs the tool but gets no reply")
    void toolCallNotificationRunsSilently() {
      var calls = registerEcho();
      var result = handler.handleMessage(parse("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\","
          + "\"params\":{\"name\":\"echo\",\"arguments\":{\"a\":1}}}"));

      assertNull(result.response());
      assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("a tools/call notification with bad params gets no reply")
    void badNotificationGetsNoReply() {
      var result = handler.handleMessage(parse("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\","
          + "\"params\":\"bar\"}"));
      assertNull(result.response());
    }

    @Test
    @DisplayName("a shutdown notification shuts down without a reply")
    void shutdownNotification() {
      var result = handler.handleMessage(parse("{\"jsonrpc\":\"2.0\",\"method\":\"shutdown\"}"));
      assertNull(result.response());
      assertTrue(result.shouldShutdown());
    }

    @Test
    @DisplayName("an initialize notification creates no session and gets no reply")
    void initializeNotificationCreatesNoSession() {
      var sessionManager = new SessionManager();
      var sessionHandler = new McpMessageHandler(registry, sessionManager);
      var result = sessionHandler.handleMessage(
          parse("{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"params\":{}}"));

      assertNull(result.response());
      assertNull(result.newSessionId());
      assertEquals(0, sessionManager.size());
    }
  }

  private JsonObject parse(String json) {
    return JsonParser.parseString(json).getAsJsonObject();
  }
}
