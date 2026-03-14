package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for JSON-RPC 2.0 message creation utilities.
 */
class JsonRpcTest {

  @Nested
  @DisplayName("createRequest")
  class CreateRequest {

    @Test
    @DisplayName("creates request with string ID")
    void createsRequestWithStringId() {
      JsonObject params = new JsonObject();
      params.addProperty("key", "value");

      JsonObject request = JsonRpc.createRequest("req-123", "tools/list", params);

      assertEquals("2.0", request.get("jsonrpc").getAsString());
      assertEquals("req-123", request.get("id").getAsString());
      assertEquals("tools/list", request.get("method").getAsString());
      assertEquals("value", request.getAsJsonObject("params").get("key").getAsString());
    }

    @Test
    @DisplayName("creates request with numeric ID")
    void createsRequestWithNumericId() {
      JsonObject request = JsonRpc.createRequest(42, "initialize", null);

      assertEquals("2.0", request.get("jsonrpc").getAsString());
      assertEquals(42, request.get("id").getAsInt());
      assertEquals("initialize", request.get("method").getAsString());
      assertFalse(request.has("params"));
    }

    @Test
    @DisplayName("creates request without params when null")
    void createsRequestWithoutParamsWhenNull() {
      JsonObject request = JsonRpc.createRequest("1", "ping", null);

      assertFalse(request.has("params"));
    }
  }

  @Nested
  @DisplayName("createResponse")
  class CreateResponse {

    @Test
    @DisplayName("creates success response with result")
    void createsSuccessResponse() {
      JsonObject result = new JsonObject();
      result.addProperty("status", "ok");

      JsonObject response = JsonRpc.createResponse(new JsonPrimitive("req-1"), result);

      assertEquals("2.0", response.get("jsonrpc").getAsString());
      assertEquals("req-1", response.get("id").getAsString());
      assertEquals("ok", response.getAsJsonObject("result").get("status").getAsString());
      assertFalse(response.has("error"));
    }

    @Test
    @DisplayName("creates response with JsonNull ID")
    void createsResponseWithJsonNullId() {
      JsonObject result = new JsonObject();
      JsonObject response = JsonRpc.createResponse(JsonNull.INSTANCE, result);

      assertTrue(response.get("id").isJsonNull());
    }

    @Test
    @DisplayName("creates response with Java null ID")
    void createsResponseWithJavaNullId() {
      // This is the actual case that occurs when message.get("id") returns null
      // for a malformed request. The id field must still be present per JSON-RPC 2.0.
      JsonObject result = new JsonObject();
      JsonObject response = JsonRpc.createResponse(null, result);

      assertTrue(response.has("id"), "Response must have 'id' field per JSON-RPC 2.0");
      assertTrue(response.get("id").isJsonNull(), "id must be null");
    }

    @Test
    @DisplayName("creates response with null result")
    void createsResponseWithNullResult() {
      // Edge case: tool returns null. Response should still have "result" field.
      JsonObject response = JsonRpc.createResponse(new JsonPrimitive(1), null);

      assertTrue(response.has("result"), "Response must have 'result' field per JSON-RPC 2.0");
      assertTrue(response.get("result").isJsonNull());
    }
  }

  @Nested
  @DisplayName("createErrorResponse")
  class CreateErrorResponse {

    @Test
    @DisplayName("creates error response with code and message")
    void createsErrorResponse() {
      JsonObject response =
          JsonRpc.createErrorResponse(new JsonPrimitive(1), JsonRpc.PARSE_ERROR, "Invalid JSON");

      assertEquals("2.0", response.get("jsonrpc").getAsString());
      assertEquals(1, response.get("id").getAsInt());

      JsonObject error = response.getAsJsonObject("error");
      assertEquals(-32700, error.get("code").getAsInt());
      assertEquals("Invalid JSON", error.get("message").getAsString());
      assertFalse(error.has("data"));
    }

    @Test
    @DisplayName("creates error response with data")
    void createsErrorResponseWithData() {
      JsonObject data = new JsonObject();
      data.addProperty("details", "Missing field");

      JsonObject response =
          JsonRpc.createErrorResponse(
              new JsonPrimitive("req-1"), JsonRpc.INVALID_PARAMS, "Bad params", data);

      JsonObject error = response.getAsJsonObject("error");
      assertEquals(-32602, error.get("code").getAsInt());
      assertEquals("Missing field", error.getAsJsonObject("data").get("details").getAsString());
    }

    @Test
    @DisplayName("uses standard error codes correctly")
    void usesStandardErrorCodes() {
      assertEquals(-32700, JsonRpc.PARSE_ERROR);
      assertEquals(-32600, JsonRpc.INVALID_REQUEST);
      assertEquals(-32601, JsonRpc.METHOD_NOT_FOUND);
      assertEquals(-32602, JsonRpc.INVALID_PARAMS);
      assertEquals(-32603, JsonRpc.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("creates error response with null ID (parse error case)")
    void createsErrorResponseWithNullId() {
      // When a parse error occurs, there's no valid JSON to extract an id from.
      // JSON-RPC 2.0 requires "id": null in this case, not a missing id field.
      JsonObject response =
          JsonRpc.createErrorResponse(null, JsonRpc.PARSE_ERROR, "Invalid JSON");

      assertEquals("2.0", response.get("jsonrpc").getAsString());
      assertTrue(response.has("id"), "Error response must have 'id' field per JSON-RPC 2.0");
      assertTrue(response.get("id").isJsonNull(), "id must be null for parse errors");

      JsonObject error = response.getAsJsonObject("error");
      assertEquals(-32700, error.get("code").getAsInt());
    }
  }

  @Nested
  @DisplayName("createNotification")
  class CreateNotification {

    @Test
    @DisplayName("creates notification without ID")
    void createsNotificationWithoutId() {
      JsonObject params = new JsonObject();
      params.addProperty("event", "ready");

      JsonObject notification = JsonRpc.createNotification("initialized", params);

      assertEquals("2.0", notification.get("jsonrpc").getAsString());
      assertFalse(notification.has("id"));
      assertEquals("initialized", notification.get("method").getAsString());
      assertEquals("ready", notification.getAsJsonObject("params").get("event").getAsString());
    }

    @Test
    @DisplayName("creates notification without params")
    void createsNotificationWithoutParams() {
      JsonObject notification = JsonRpc.createNotification("shutdown", null);

      assertFalse(notification.has("params"));
    }
  }
}
