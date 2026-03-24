package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

  private JsonObject parse(String json) {
    return JsonParser.parseString(json).getAsJsonObject();
  }
}
