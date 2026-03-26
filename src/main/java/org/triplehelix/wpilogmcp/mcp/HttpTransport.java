package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP server using Streamable HTTP transport.
 *
 * <p>Exposes a single endpoint ({@code /mcp}) supporting POST, GET, and DELETE as defined by the
 * MCP Streamable HTTP specification. Uses {@code com.sun.net.httpserver.HttpServer} (built-in to
 * Java 17+) with no additional dependencies.
 *
 * <p>Each client gets a session (via {@link SessionManager}) with independent active-log state.
 * Parsed logs are shared across sessions via the global {@link
 * org.triplehelix.wpilogmcp.log.LogManager} cache.
 */
public class HttpTransport {
  private static final Logger logger = LoggerFactory.getLogger(HttpTransport.class);
  private static final String SESSION_HEADER = "Mcp-Session-Id";
  private static final Duration SESSION_IDLE_TIMEOUT = Duration.ofHours(1);
  private static final long CLEANUP_INTERVAL_MINUTES = 5;
  private static final AtomicInteger SSE_THREAD_COUNTER = new AtomicInteger(0);

  private final Gson gson;
  private final McpMessageHandler handler;
  private final SessionManager sessionManager;
  private final int port;
  private final String bindAddress;
  private final String mcpPath;
  private final java.util.Set<String> allowedOriginHosts;
  private HttpServer server;
  private java.util.concurrent.ExecutorService httpExecutor;
  private ScheduledExecutorService scheduler;
  private java.util.concurrent.ExecutorService sseExecutor;

  public HttpTransport(ToolRegistry toolRegistry, int port) {
    this(toolRegistry, port, "127.0.0.1", null, null);
  }

  public HttpTransport(ToolRegistry toolRegistry, int port, String bindAddress,
      java.util.Set<String> allowedOriginHosts, String mcpPath) {
    this.gson = new GsonBuilder().serializeNulls().create();
    this.sessionManager = new SessionManager();
    this.handler = new McpMessageHandler(toolRegistry, sessionManager);
    this.port = port;
    this.bindAddress = bindAddress != null ? bindAddress : "127.0.0.1";
    this.mcpPath = mcpPath != null && !mcpPath.isEmpty() ? mcpPath : "/mcp";
    this.allowedOriginHosts = allowedOriginHosts != null
        ? allowedOriginHosts : java.util.Set.of();
  }

  public void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(this.bindAddress, port), 0);
    server.createContext(this.mcpPath, this::handleRequest);
    server.createContext("/health", this::handleHealthCheck);
    httpExecutor = Executors.newFixedThreadPool(
        Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
    server.setExecutor(httpExecutor);
    server.start();

    // Separate bounded thread pool for SSE streams — these block indefinitely and must not
    // starve the main request handler pool. Capped at 64 concurrent SSE connections.
    sseExecutor = new ThreadPoolExecutor(0, 64, 60L, TimeUnit.SECONDS,
        new SynchronousQueue<>(), r -> {
          var t = new Thread(r, "mcp-sse-" + SSE_THREAD_COUNTER.getAndIncrement());
          t.setDaemon(true);
          return t;
        });

    // Schedule periodic session cleanup
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      var t = new Thread(r, "session-cleanup");
      t.setDaemon(true);
      return t;
    });
    scheduler.scheduleAtFixedRate(
        () -> sessionManager.cleanupExpired(SESSION_IDLE_TIMEOUT),
        CLEANUP_INTERVAL_MINUTES, CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES);

    logger.info("MCP HTTP server listening on http://{}:{}{}", this.bindAddress, getPort(), this.mcpPath);
  }

  public int getPort() {
    return server != null ? server.getAddress().getPort() : port;
  }

  public void stop() {
    // 1. Stop accepting new connections
    if (server != null) {
      // Give in-flight requests up to 5 seconds to complete
      server.stop(5);
      logger.info("MCP HTTP server stopped");
    }
    // 2. Drain the main request executor (requests are already completing via server.stop delay)
    if (httpExecutor != null) {
      httpExecutor.shutdown();
      try {
        if (!httpExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          httpExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        httpExecutor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
    // 3. Shut down SSE streams and scheduler (safe to tear down after requests drain)
    if (sseExecutor != null) {
      sseExecutor.shutdownNow();
    }
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  private void handleRequest(HttpExchange exchange) throws IOException {
    try {
      // Validate Origin header to prevent DNS rebinding
      var origin = exchange.getRequestHeaders().getFirst("Origin");
      if (origin != null && !isAllowedOrigin(origin)) {
        sendError(exchange, 403, "Forbidden: invalid origin");
        return;
      }

      var method = exchange.getRequestMethod();
      switch (method) {
        case "POST" -> handlePost(exchange);
        case "GET" -> handleGet(exchange);
        case "DELETE" -> handleDelete(exchange);
        case "OPTIONS" -> handleOptions(exchange);
        default -> sendError(exchange, 405, "Method not allowed");
      }
    } catch (Exception e) {
      logger.error("Unhandled error in HTTP handler: {}", e.getMessage(), e);
      try {
        sendError(exchange, 500, "Internal server error");
      } catch (IOException ignored) {
        // Client may have disconnected
      }
    }
  }

  private void handlePost(HttpExchange exchange) throws IOException {
    // Parse JSON-RPC from request body (single message or batch array)
    JsonElement parsed;
    try (var reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
      parsed = JsonParser.parseReader(reader);
    } catch (Exception e) {
      logger.warn("Failed to parse JSON-RPC request: {}", e.getMessage());
      sendJsonResponse(exchange, 400,
          JsonRpc.createErrorResponse(null, JsonRpc.PARSE_ERROR, "Parse error"), null);
      return;
    }

    if (parsed.isJsonArray()) {
      handleBatchPost(exchange, parsed.getAsJsonArray());
    } else if (parsed.isJsonObject()) {
      handleSinglePost(exchange, parsed.getAsJsonObject());
    } else {
      sendJsonResponse(exchange, 400,
          JsonRpc.createErrorResponse(null, JsonRpc.INVALID_REQUEST, "Expected object or array"),
          null);
    }
  }

  private void handleSinglePost(HttpExchange exchange, JsonObject message) throws IOException {
    // Determine if this is an initialize request (no session required)
    var methodName = message.has("method") ? message.get("method").getAsString() : null;
    boolean isInitialize = "initialize".equals(methodName);

    // Resolve session
    McpSession session = null;
    if (!isInitialize) {
      var sessionId = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
      if (sessionId == null) {
        sendError(exchange, 400, "Missing " + SESSION_HEADER + " header");
        return;
      }
      session = sessionManager.getSession(sessionId);
      if (session == null) {
        sendError(exchange, 404, "Session not found or expired");
        return;
      }
    }

    // Handle the message
    var result = handler.handleMessage(message, session);

    if (result.response() == null) {
      // Notification — no response body
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      return;
    }

    // Send response with session header if this was an initialize
    sendJsonResponse(exchange, 200, result.response(), result.newSessionId());
  }

  private void handleBatchPost(HttpExchange exchange, JsonArray batch) throws IOException {
    if (batch.isEmpty()) {
      sendJsonResponse(exchange, 400,
          JsonRpc.createErrorResponse(null, JsonRpc.INVALID_REQUEST, "Empty batch"), null);
      return;
    }

    // Resolve session from header
    var sessionId = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
    McpSession session = null;
    if (sessionId != null) {
      session = sessionManager.getSession(sessionId);
      if (session == null) {
        sendError(exchange, 404, "Session not found or expired");
        return;
      }
    } else {
      // No session header — check if batch contains an initialize request.
      // If not, reject (matching handleSinglePost behavior).
      boolean hasInitialize = false;
      for (var element : batch) {
        if (element.isJsonObject()) {
          var msg = element.getAsJsonObject();
          if (msg.has("method") && "initialize".equals(msg.get("method").getAsString())) {
            hasInitialize = true;
            break;
          }
        }
      }
      if (!hasInitialize) {
        sendError(exchange, 400, "Missing " + SESSION_HEADER + " header");
        return;
      }
    }

    var responses = new JsonArray();
    String newSessionId = null;

    for (var element : batch) {
      if (!element.isJsonObject()) {
        responses.add(JsonRpc.createErrorResponse(null, JsonRpc.INVALID_REQUEST,
            "Batch element must be an object"));
        continue;
      }
      var msg = element.getAsJsonObject();
      var result = handler.handleMessage(msg, session);
      if (result.newSessionId() != null) {
        newSessionId = result.newSessionId();
        // Update session for subsequent messages in the batch
        session = sessionManager.getSession(newSessionId);
      }
      if (result.response() != null) {
        responses.add(result.response());
      }
    }

    if (responses.isEmpty()) {
      // All were notifications
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      return;
    }

    // Send batch response
    var json = gson.toJson(responses);
    var bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    if (newSessionId != null) {
      exchange.getResponseHeaders().set(SESSION_HEADER, newSessionId);
    }
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private void handleGet(HttpExchange exchange) throws IOException {
    // SSE stream for server-initiated messages
    var sessionId = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
    if (sessionId == null) {
      sendError(exchange, 400, "Missing " + SESSION_HEADER + " header");
      return;
    }
    var session = sessionManager.getSession(sessionId);
    if (session == null) {
      sendError(exchange, 404, "Session not found or expired");
      return;
    }

    // Check Accept header
    var accept = exchange.getRequestHeaders().getFirst("Accept");
    if (accept == null || !accept.contains("text/event-stream")) {
      sendError(exchange, 406, "Not Acceptable: must accept text/event-stream");
      return;
    }

    // Keep the stream open with periodic pings until the client disconnects.
    // Run on a separate cached thread pool to avoid consuming the main thread pool
    // indefinitely — each SSE client holds a thread for the session's lifetime.
    //
    // Headers and sendResponseHeaders are inside the submitted task so that if the
    // pool is full, we can return 503 before committing to a 200 response.
    var origin = exchange.getRequestHeaders().getFirst("Origin");
    try {
      sseExecutor.submit(() -> {
        try {
          if (origin != null && isAllowedOrigin(origin)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
          }
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.getResponseHeaders().set("Cache-Control", "no-cache");
          exchange.getResponseHeaders().set("Connection", "keep-alive");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            while (sessionManager.getSession(sessionId) != null) {
              os.write(":ping\n\n".getBytes(StandardCharsets.UTF_8));
              os.flush();
              Thread.sleep(15_000);
            }
          }
        } catch (IOException | InterruptedException e) {
          // Client disconnected or thread interrupted — normal
          logger.debug("SSE stream closed for session {}", sessionId);
        }
      });
    } catch (java.util.concurrent.RejectedExecutionException e) {
      sendError(exchange, 503, "Too many concurrent SSE connections. Try again later.");
    }
  }

  private void handleDelete(HttpExchange exchange) throws IOException {
    var sessionId = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
    if (sessionId == null) {
      sendError(exchange, 400, "Missing " + SESSION_HEADER + " header");
      return;
    }

    var removed = sessionManager.removeSession(sessionId);
    if (removed == null) {
      sendError(exchange, 404, "Session not found");
      return;
    }

    exchange.sendResponseHeaders(200, -1);
    exchange.close();
  }

  private void handleOptions(HttpExchange exchange) throws IOException {
    var origin = exchange.getRequestHeaders().getFirst("Origin");
    if (origin != null && isAllowedOrigin(origin)) {
      exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
    }
    exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, GET, DELETE, OPTIONS");
    exchange.getResponseHeaders().set("Access-Control-Allow-Headers",
        "Content-Type, Accept, " + SESSION_HEADER);
    exchange.sendResponseHeaders(204, -1);
    exchange.close();
  }

  private void sendJsonResponse(HttpExchange exchange, int status, JsonObject response,
      String sessionId) throws IOException {
    var json = gson.toJson(response);
    var bytes = json.getBytes(StandardCharsets.UTF_8);

    var origin = exchange.getRequestHeaders().getFirst("Origin");
    if (origin != null && isAllowedOrigin(origin)) {
      exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
    }
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    if (sessionId != null) {
      exchange.getResponseHeaders().set(SESSION_HEADER, sessionId);
    }
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private void sendError(HttpExchange exchange, int status, String message) throws IOException {
    var error = new JsonObject();
    error.addProperty("error", message);
    var bytes = gson.toJson(error).getBytes(StandardCharsets.UTF_8);

    var origin = exchange.getRequestHeaders().getFirst("Origin");
    if (origin != null && isAllowedOrigin(origin)) {
      exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
    }
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private void handleHealthCheck(HttpExchange exchange) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    var health = new JsonObject();
    health.addProperty("status", "ok");
    health.addProperty("sessions", sessionManager.size());
    var bytes = gson.toJson(health).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private boolean isAllowedOrigin(String origin) {
    if (origin == null || origin.isEmpty()) return false;
    try {
      java.net.URI uri = java.net.URI.create(origin);
      String host = uri.getHost();
      if ("localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host)) {
        return true;
      }
      return allowedOriginHosts.contains(host);
    } catch (Exception e) {
      return false;
    }
  }
}
