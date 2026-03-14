package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Utility class for creating JSON-RPC 2.0 messages.
 *
 * <p>JSON-RPC is a stateless, light-weight remote procedure call (RPC) protocol that uses JSON for
 * encoding. The MCP protocol uses JSON-RPC 2.0 for client-server communication.
 *
 * <h2>Message Types</h2>
 *
 * <ul>
 *   <li><b>Request</b>: Has "id", "method", and optional "params". Expects a response.
 *   <li><b>Response</b>: Has "id" and either "result" (success) or "error" (failure).
 *   <li><b>Notification</b>: Has "method" and optional "params", but no "id". No response expected.
 * </ul>
 *
 * <h2>Error Codes</h2>
 *
 * <p>Standard JSON-RPC error codes are defined as constants:
 *
 * <ul>
 *   <li>{@link #PARSE_ERROR} (-32700): Invalid JSON
 *   <li>{@link #INVALID_REQUEST} (-32600): Invalid Request object
 *   <li>{@link #METHOD_NOT_FOUND} (-32601): Method does not exist
 *   <li>{@link #INVALID_PARAMS} (-32602): Invalid method parameters
 *   <li>{@link #INTERNAL_ERROR} (-32603): Internal JSON-RPC error
 * </ul>
 *
 * @see <a href="https://www.jsonrpc.org/specification">JSON-RPC 2.0 Specification</a>
 */
public class JsonRpc {

  /** JSON-RPC protocol version. Always "2.0" for JSON-RPC 2.0. */
  public static final String VERSION = "2.0";

  // ========== Standard JSON-RPC 2.0 Error Codes ==========

  /**
   * Parse error (-32700).
   *
   * <p>Invalid JSON was received by the server. An error occurred on the server while parsing the
   * JSON text.
   */
  public static final int PARSE_ERROR = -32700;

  /**
   * Invalid Request (-32600).
   *
   * <p>The JSON sent is not a valid Request object.
   */
  public static final int INVALID_REQUEST = -32600;

  /**
   * Method not found (-32601).
   *
   * <p>The method does not exist or is not available.
   */
  public static final int METHOD_NOT_FOUND = -32601;

  /**
   * Invalid params (-32602).
   *
   * <p>Invalid method parameter(s).
   */
  public static final int INVALID_PARAMS = -32602;

  /**
   * Internal error (-32603).
   *
   * <p>Internal JSON-RPC error.
   */
  public static final int INTERNAL_ERROR = -32603;

  // Prevent instantiation - this is a utility class
  private JsonRpc() {}

  /**
   * Creates a JSON-RPC request object.
   *
   * <p>A request object has the following members:
   *
   * <ul>
   *   <li>"jsonrpc": Always "2.0"
   *   <li>"id": A unique identifier (string or number)
   *   <li>"method": The name of the method to invoke
   *   <li>"params": Optional parameters for the method
   * </ul>
   *
   * @param id Request identifier (String or Number)
   * @param method The method name to invoke
   * @param params Optional parameters (can be null)
   * @return A JSON-RPC request object
   */
  public static JsonObject createRequest(Object id, String method, JsonElement params) {
    var request = new JsonObject();
    request.addProperty("jsonrpc", VERSION);
    if (id instanceof String) {
      request.addProperty("id", (String) id);
    } else if (id instanceof Number) {
      request.addProperty("id", (Number) id);
    }
    request.addProperty("method", method);
    if (params != null) {
      request.add("params", params);
    }
    return request;
  }

  /**
   * Creates a JSON-RPC success response.
   *
   * <p>A successful response object has the following members:
   *
   * <ul>
   *   <li>"jsonrpc": Always "2.0"
   *   <li>"id": Same id as the request
   *   <li>"result": The result of the method invocation
   * </ul>
   *
   * @param id The request id (must match the original request)
   * @param result The result of the method invocation
   * @return A JSON-RPC success response
   */
  public static JsonObject createResponse(JsonElement id, JsonElement result) {
    var response = new JsonObject();
    response.addProperty("jsonrpc", VERSION);
    // Handle null id (edge case - shouldn't happen for valid requests, but be safe)
    if (id == null) {
      response.add("id", com.google.gson.JsonNull.INSTANCE);
    } else {
      response.add("id", id);
    }
    // Handle null result (edge case - use empty object if null)
    if (result == null) {
      response.add("result", com.google.gson.JsonNull.INSTANCE);
    } else {
      response.add("result", result);
    }
    return response;
  }

  /**
   * Creates a JSON-RPC error response without additional data.
   *
   * @param id The request id (can be null if request parsing failed)
   * @param code The error code (use one of the standard codes)
   * @param message Human-readable error message
   * @return A JSON-RPC error response
   * @see #PARSE_ERROR
   * @see #INVALID_REQUEST
   * @see #METHOD_NOT_FOUND
   * @see #INVALID_PARAMS
   * @see #INTERNAL_ERROR
   */
  public static JsonObject createErrorResponse(JsonElement id, int code, String message) {
    return createErrorResponse(id, code, message, null);
  }

  /**
   * Creates a JSON-RPC error response with additional data.
   *
   * <p>An error response object has the following members:
   *
   * <ul>
   *   <li>"jsonrpc": Always "2.0"
   *   <li>"id": Same id as the request (or null if parsing failed)
   *   <li>"error": An object with "code", "message", and optional "data"
   * </ul>
   *
   * @param id The request id (can be null if request parsing failed)
   * @param code The error code (use one of the standard codes)
   * @param message Human-readable error message
   * @param data Optional additional error data (can be null)
   * @return A JSON-RPC error response
   */
  public static JsonObject createErrorResponse(
      JsonElement id, int code, String message, JsonElement data) {
    var response = new JsonObject();
    response.addProperty("jsonrpc", VERSION);
    // JSON-RPC 2.0 requires "id": null when id cannot be determined (e.g., parse error)
    // Gson's add() skips null values, so we must use addProperty for null
    if (id == null) {
      response.add("id", com.google.gson.JsonNull.INSTANCE);
    } else {
      response.add("id", id);
    }

    var error = new JsonObject();
    error.addProperty("code", code);
    error.addProperty("message", message);
    if (data != null) {
      error.add("data", data);
    }
    response.add("error", error);

    return response;
  }

  /**
   * Creates a JSON-RPC notification (a request without an id).
   *
   * <p>Notifications are requests that do not expect a response. They have no "id" field.
   *
   * <p>A notification object has the following members:
   *
   * <ul>
   *   <li>"jsonrpc": Always "2.0"
   *   <li>"method": The name of the method to invoke
   *   <li>"params": Optional parameters for the method
   * </ul>
   *
   * @param method The method name
   * @param params Optional parameters (can be null)
   * @return A JSON-RPC notification object
   */
  public static JsonObject createNotification(String method, JsonElement params) {
    var notification = new JsonObject();
    notification.addProperty("jsonrpc", VERSION);
    notification.addProperty("method", method);
    if (params != null) {
      notification.add("params", params);
    }
    return notification;
  }
}
