/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.Version;
import org.triplehelix.wpilogmcp.config.ClientLeases;

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
 *
 * <p>Beside the MCP endpoint, {@code GET /health} says the server is up, with its version and
 * process ID, so a {@code start} can tell a daemon of another version from one of its own, and a
 * stranger on the port from either; and {@code POST /stop} ends the server when it is enabled
 * (see {@link #setStop}). A daemon can also end itself when nothing has used it for a while
 * (see {@link #setIdleExit}).
 */
public class HttpTransport {
  private static final Logger logger = LoggerFactory.getLogger(HttpTransport.class);
  private static final String SESSION_HEADER = "Mcp-Session-Id";
  private static final Duration SESSION_IDLE_TIMEOUT = Duration.ofHours(1);
  private static final long CLEANUP_INTERVAL_MINUTES = 5;
  private static final AtomicInteger SSE_THREAD_COUNTER = new AtomicInteger(0);
  /** The header that carries the token a {@code POST /stop} must present. */
  public static final String STOP_TOKEN_HEADER = "X-Wpilog-Stop-Token";
  /** How long {@link #stop} lets requests in flight finish before closing their connections. */
  private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(5);
  /** The data endpoint's path, beside the MCP endpoint and /health. */
  public static final String DATA_PATH = "/data/entries";
  private static final Duration DRAIN_POLL_INTERVAL = Duration.ofMillis(20);

  private final Gson gson;
  private final McpMessageHandler handler;
  private final SessionManager sessionManager;
  private final ClientLeases leases = ClientLeases.getInstance();
  private final RegistrationEndpoint registration;
  private boolean leasesOpened;
  private final int port;
  private final String bindAddress;
  private final String mcpPath;
  private final java.util.Set<String> allowedOriginHosts;
  private HttpServer server;
  private java.util.concurrent.ExecutorService httpExecutor;
  private ScheduledExecutorService scheduler;
  private java.util.concurrent.ExecutorService sseExecutor;
  /** Handlers running right now. An SSE stream is not one: its handler returns at once. */
  private final AtomicInteger inFlightRequests = new AtomicInteger();
  private final AtomicBoolean stopped = new AtomicBoolean();
  /** The token {@code POST /stop} must carry, or null when stopping over HTTP is not enabled. */
  private volatile String stopToken;
  private volatile Runnable onStop;
  /** How long with no session and no MCP request before {@link #onIdle} runs; null for never. */
  private volatile Duration idleExit;
  private volatile Runnable onIdle;
  private final AtomicBoolean idleExitRun = new AtomicBoolean();
  /** {@code GET /data/entries} (see {@link DataEndpoint}). */
  private final DataEndpoint dataEndpoint =
      new DataEndpoint(org.triplehelix.wpilogmcp.log.LogManager.getInstance());
  private final org.triplehelix.wpilogmcp.store.StoreRegistry stores =
      org.triplehelix.wpilogmcp.log.LogManager.getInstance().stores();
  private volatile java.util.Set<java.nio.file.Path> storeDirectories;
  private final org.triplehelix.wpilogmcp.store.StoreDoor storeDoor = new org.triplehelix.wpilogmcp.store.StoreDoor(
      () -> storeDirectories != null ? storeDirectories : org.triplehelix.wpilogmcp.log.LogManager.getInstance()
          .getConfiguredDirectories());
  private final StoreEndpoint storeEndpoint = new StoreEndpoint(storeDoor);
  private volatile MetricsEndpoint metricsEndpoint = new MetricsEndpoint(null, null);
  public void configureMetrics(org.triplehelix.wpilogmcp.config.MetricsConfig config,
      org.triplehelix.wpilogmcp.capture.LiveCapture capture) { metricsEndpoint = new MetricsEndpoint(config, capture); }
  public void configureMetrics(org.triplehelix.wpilogmcp.config.MetricsConfig config,
      org.triplehelix.wpilogmcp.capture.LiveCapture capture, java.util.function.Supplier<MetricsEndpoint.Components> components) {
    metricsEndpoint = new MetricsEndpoint(config, capture, components);
  }
  private final StoreImportEndpoint importEndpoint = new StoreImportEndpoint(stores, storeDoor);
  private final MirrorEndpoint mirrorEndpoint = new MirrorEndpoint(stores);
  private final StoreSyncEndpoint syncEndpoint = new StoreSyncEndpoint(stores,
      new org.triplehelix.wpilogmcp.store.StoreDoor(() -> storeDirectories != null ? storeDirectories
          : org.triplehelix.wpilogmcp.log.LogManager.getInstance().getAllowedDirectories()),
      () -> storeDirectories != null ? storeDirectories : org.triplehelix.wpilogmcp.log.LogManager.getInstance().getAllowedDirectories());
  /** When the MCP endpoint was last asked for anything, or a session last removed. */
  private volatile long lastMcpActivityNanos = System.nanoTime();

  public HttpTransport(ToolRegistry toolRegistry, int port) {
    this(toolRegistry, port, "127.0.0.1", null, null);
  }

  public HttpTransport(ToolRegistry toolRegistry, int port, String bindAddress,
      java.util.Set<String> allowedOriginHosts, String mcpPath) {
    this.gson = new GsonBuilder().serializeNulls().create();
    this.sessionManager = new SessionManager(leases::remove);
    this.registration = new RegistrationEndpoint(sessionManager, leases);
    this.handler = new McpMessageHandler(toolRegistry, sessionManager);
    this.port = port;
    this.bindAddress = bindAddress != null ? bindAddress : "127.0.0.1";
    this.mcpPath = mcpPath != null && !mcpPath.isEmpty() ? mcpPath : "/mcp";
    this.allowedOriginHosts = allowedOriginHosts != null
        ? allowedOriginHosts : java.util.Set.of();
  }

  /**
   * Enables {@code POST /stop}: a request over a loopback connection carrying {@code token} in
   * the {@value #STOP_TOKEN_HEADER} header is answered, and then {@code onStop} runs on a thread
   * of its own, so that it may call {@link #stop}, which waits for the request to finish.
   *
   * <p>The token is how a {@code stop} command proves it may end the server: the start that
   * spawned the daemon wrote it to a file beside the PID file that only the user can read, and
   * gave it to the daemon in its environment, never on its command line, where every user's
   * process list would show it. Loopback only, because a server bound to every interface for a
   * container must not be stoppable from the network. Call before {@link #start()}.
   */
  public void setStop(String token, Runnable onStop) {
    this.stopToken = token;
    this.onStop = onStop;
  }

  /**
   * Makes the server end itself, by {@code onIdle}, once {@code idle} has passed with no MCP
   * session open and no request to the MCP endpoint; a health check does not count, since a
   * start's probe would otherwise keep a server alive. The clock starts when the server starts
   * and again when its last session is removed, so a server that was used and then left exits
   * {@code idle} after its last client went, and one started and never used exits {@code idle}
   * after it started. Call before {@link #start()}.
   */
  public void setIdleExit(Duration idle, Runnable onIdle) {
    this.idleExit = idle;
    this.onIdle = onIdle;
  }

  public void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(this.bindAddress, port), 0);
    server.createContext(this.mcpPath, counted(this::handleRequest));
    server.createContext("/directories", counted(this::handleRegistration));
    server.createContext("/tba-key", counted(this::handleRegistration));
    server.createContext("/pit-credential", counted(this::handleRegistration));
    server.createContext("/pit-mcp", counted(this::handlePitMcp));
    server.createContext("/health", counted(this::handleHealthCheck));
    server.createContext("/metrics", counted(this::handleMetrics));
    server.createContext("/stop", counted(this::handleStop));
    server.createContext(DATA_PATH, counted(this::handleData));
    server.createContext(StoreImportEndpoint.PATH, counted(this::handleImport));
    server.createContext(StoreImportEndpoint.ASSIGN_PATH, counted(this::handleImport));
    server.createContext("/store", counted(this::handleStore));
    server.createContext(StoreSyncEndpoint.PATH, counted(this::handleSync));
    server.createContext(MirrorEndpoint.PATH, counted(this::handleMirror));
    httpExecutor = Executors.newFixedThreadPool(
        Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
    server.setExecutor(httpExecutor);
    leases.transportOpened();
    leasesOpened = true;
    server.start();
    stores.startWatching();

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
        () -> expireSessions(SESSION_IDLE_TIMEOUT),
        CLEANUP_INTERVAL_MINUTES, CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES);
    lastMcpActivityNanos = System.nanoTime();
    var idle = idleExit;
    if (idle != null && onIdle != null) {
      // Checked often enough that the exit comes within a quarter of the idle time after it is
      // due, and at least every 30 seconds for a long idle time
      long checkMillis = Math.max(10, Math.min(idle.toMillis() / 4, 30_000));
      scheduler.scheduleAtFixedRate(this::exitIfIdle, checkMillis, checkMillis,
          TimeUnit.MILLISECONDS);
      logger.info("The server exits after {} with no session and no request", idle);
    }

    logger.info("MCP HTTP server listening on http://{}:{}{}", this.bindAddress, getPort(), this.mcpPath);
  }

  /** The number of MCP sessions open now. */
  public int sessionCount() {
    return sessionManager.size();
  }

  private void noteMcpActivity() {
    lastMcpActivityNanos = System.nanoTime();
  }

  /** Runs {@code onIdle} once, when no session is open and the idle time has passed. */
  private void exitIfIdle() {
    var idle = idleExit;
    if (idle == null || sessionManager.size() > 0 || stores.importing() || importEndpoint.active() || syncEndpoint.active() || mirrorEndpoint.active()) return;
    long idleFor = System.nanoTime() - lastMcpActivityNanos;
    if (idleFor < idle.toNanos()) return;
    if (!idleExitRun.compareAndSet(false, true)) return;
    logger.info("No session and no request for {}: exiting", idle);
    try {
      onIdle.run();
    } catch (RuntimeException e) {
      logger.error("The idle exit failed: {}", e.toString());
      idleExitRun.set(false);
    }
  }

  public int getPort() {
    return server != null ? server.getAddress().getPort() : port;
  }

  /**
   * Stops the server: SSE streams are ended first, requests in flight get up to
   * {@link #DRAIN_TIMEOUT} to finish, then the listening socket and every connection are closed.
   *
   * <p>{@code HttpServer.stop(delay)} is not used for the drain: it waits its whole delay unless
   * an exchange ends after it was called, so with an SSE stream open (or nothing in flight at
   * all) it would block for the full delay. Idempotent.
   */
  public void stop() {
    if (!stopped.compareAndSet(false, true)) {
      return;
    }
    mirrorEndpoint.close();
    // 1. End the SSE streams. Their loops sleep between pings; the interrupt ends the loop,
    //    which closes the exchange. They would otherwise stay open until the client went away.
    if (sseExecutor != null) {
      sseExecutor.shutdownNow();
      try {
        sseExecutor.awaitTermination(1, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    // 2. Let requests in flight finish, then close the listening socket and all connections
    if (server != null) {
      awaitInFlightRequests();
      server.stop(0);
      logger.info("MCP HTTP server stopped");
    }
    // 3. Shut down the executors; a handler cut off by the close finishes promptly
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
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
    sessionManager.clear();
    stores.stopWatching();
    stores.awaitImports();
    if (leasesOpened) leases.transportClosed();
  }

  /** Wraps a handler so that {@link #stop} can wait for the requests being handled. */
  private HttpHandler counted(HttpHandler handler) {
    return exchange -> {
      inFlightRequests.incrementAndGet();
      try {
        handler.handle(exchange);
      } finally {
        inFlightRequests.decrementAndGet();
      }
    };
  }

  private void awaitInFlightRequests() {
    long deadline = System.nanoTime() + DRAIN_TIMEOUT.toNanos();
    while (inFlightRequests.get() > 0 && System.nanoTime() < deadline) {
      try {
        Thread.sleep(DRAIN_POLL_INTERVAL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    int remaining = inFlightRequests.get();
    if (remaining > 0) {
      logger.warn("{} request(s) still in flight after {} s; closing their connections",
          remaining, DRAIN_TIMEOUT.toSeconds());
    }
  }

  private void handleRequest(HttpExchange exchange) throws IOException {
    noteMcpActivity();
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
    // Determine if this is an initialize request (no session required). A non-string method is
    // not a crash: the handler answers it with an invalid-request error.
    boolean isInitialize = "initialize".equals(McpMessageHandler.methodName(message));

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
          if ("initialize".equals(McpMessageHandler.methodName(msg))) {
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
    // The idle clock runs from the last session's end, not from its last request
    noteMcpActivity();

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

  /**
   * The data endpoint, behind the same {@code Origin} check as the MCP endpoint: a web page
   * must not be able to fetch a log's samples any more than it can call a tool. It serves only
   * the files the log manager's validator allows, as every tool does.
   */
  private void handleRegistration(HttpExchange exchange) throws IOException {
    var origin = exchange.getRequestHeaders().getFirst("Origin");
    if (origin != null && !isAllowedOrigin(origin)) {
      sendError(exchange, 403, "Forbidden: invalid origin");
      return;
    }
    if (!server.getAddress().getAddress().isLoopbackAddress()) {
      sendError(exchange, 403, "Registration requires a server bound to loopback");
      return;
    }
    noteMcpActivity();
    registration.handle(exchange);
  }

  /** A secret-free URL for Claude's bridge; only the local listener can use the person's lease. */
  private void handlePitMcp(HttpExchange exchange) throws IOException {
    var origin = exchange.getRequestHeaders().getFirst("Origin");
    if (origin != null && !isAllowedOrigin(origin) || !server.getAddress().getAddress().isLoopbackAddress()) {
      sendError(exchange, 403, "Pit credential forwarding requires loopback and an allowed Origin"); return;
    }
    noteMcpActivity();
    PitMcpEndpoint.handle(exchange);
  }

  /** The scheduler and transport tests use the same session expiry path. */
  int expireSessions(Duration maximumIdle) {
    return sessionManager.cleanupExpired(maximumIdle);
  }

  private void handleData(HttpExchange exchange) throws IOException {
    var origin = exchange.getRequestHeaders().getFirst("Origin");
    if (origin != null && !isAllowedOrigin(origin)) {
      sendError(exchange, 403, "Forbidden: invalid origin");
      return;
    }
    dataEndpoint.handle(exchange);
  }

  /** The import write surface has exactly the same Origin gate as MCP and data reads. */
  private void handleImport(HttpExchange exchange) throws IOException {
    var origin = exchange.getRequestHeaders().getFirst("Origin");
    if (origin != null && !isAllowedOrigin(origin)) {
      sendError(exchange, 403, "Forbidden: invalid origin");
      return;
    }
    boolean bytes = exchange.getRequestURI().getPath().equals(StoreImportEndpoint.PATH)
        && "application/octet-stream".equals(exchange.getRequestHeaders().getFirst("Content-Type"));
    if (exchange.getRequestMethod().equals("POST") && !bytes
        && !exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
      sendError(exchange, 403, "Server-path imports and assignments require a loopback connection; upload file bytes instead"); return;
    }
    noteMcpActivity();
    importEndpoint.handle(exchange);
  }

  /** Configuration scope, independent of client leases; useful when transports share a JVM. */
  public void setStoreDirectories(java.util.Set<java.nio.file.Path> directories) {
    if (server != null) throw new IllegalStateException("Set store directories before starting HTTP");
    storeDirectories = java.util.Set.copyOf(directories);
  }

  private void handleStore(HttpExchange exchange) throws IOException {
    var origin = exchange.getRequestHeaders().getFirst("Origin");
    if (origin != null && !isAllowedOrigin(origin)) {
      sendError(exchange, 403, "Forbidden: invalid origin"); return;
    }
    noteMcpActivity();
    storeEndpoint.handle(exchange);
  }

  private void handleSync(HttpExchange exchange) throws IOException {
    var origin = exchange.getRequestHeaders().getFirst("Origin");
    if (origin != null && !isAllowedOrigin(origin)) {
      sendError(exchange, 403, "Forbidden: invalid origin"); return;
    }
    if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
      sendError(exchange, 403, "Store sync jobs require a loopback connection"); return;
    }
    noteMcpActivity(); syncEndpoint.handle(exchange);
  }

  /** Called after the listener starts, so an offline origin cannot delay daemon health. */
  public void configureMirror(org.triplehelix.wpilogmcp.config.MirrorConfig config) throws IOException {
    mirrorEndpoint.configure(config);
  }

  private void handleMirror(HttpExchange exchange) throws IOException {
    var origin = exchange.getRequestHeaders().getFirst("Origin");
    if (origin != null && !isAllowedOrigin(origin)) {
      sendError(exchange, 403, "Forbidden: invalid origin"); return;
    }
    if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
      sendError(exchange, 403, "Mirror controls require a loopback connection"); return;
    }
    noteMcpActivity(); mirrorEndpoint.handle(exchange);
  }

  /** The size cap of a data response, in bytes (see {@link DataEndpoint}). */
  public void setDataMaxBytes(long maxBytes) {
    dataEndpoint.setMaxBytes(maxBytes);
  }

  /** The data endpoint's URL, once the server has started. */
  public String dataEndpointUrl() {
    return DataEndpoint.url(bindAddress, getPort());
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
    // A start compares the version with its own JAR's, and records the process ID of a server
    // it finds on the port without a PID file
    health.addProperty("version", Version.VERSION);
    health.addProperty("pid", ProcessHandle.current().pid());
    var bytes = gson.toJson(health).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private void handleMetrics(HttpExchange exchange) throws IOException {
    var origin = exchange.getRequestHeaders().getFirst("Origin");
    if (origin != null && !isAllowedOrigin(origin)) { sendError(exchange, 403, "Forbidden: invalid origin"); return; }
    metricsEndpoint.handle(exchange);
  }

  /**
   * {@code POST /stop}: ends the server when {@link #setStop} enabled it, the connection is from
   * this machine, and the token matches. Every refusal is 403 and says why, except that a
   * wrong token is not told apart from a missing one.
   */
  private void handleStop(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    var token = stopToken;
    if (token == null) {
      sendError(exchange, 403, "Stopping over HTTP is not enabled for this server; end its "
          + "process instead");
      return;
    }
    if (!isLoopback(exchange.getRemoteAddress())) {
      sendError(exchange, 403, "Stop requests are accepted only from this machine");
      return;
    }
    var presented = exchange.getRequestHeaders().getFirst(STOP_TOKEN_HEADER);
    if (presented == null || !MessageDigest.isEqual(
        presented.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
      sendError(exchange, 403, "Stop token missing or wrong");
      return;
    }
    logger.info("Stop requested from {}", exchange.getRemoteAddress());
    var body = new JsonObject();
    body.addProperty("status", "stopping");
    sendJsonResponse(exchange, 200, body, null);
    // On its own thread: onStop may call stop(), which waits for this handler to return
    var stopper = new Thread(onStop, "stop-request");
    stopper.setDaemon(false);
    stopper.start();
  }

  /** Whether a connection comes from this machine: the loopback interface, by address. */
  static boolean isLoopback(InetSocketAddress remote) {
    return remote != null && remote.getAddress() != null
        && remote.getAddress().isLoopbackAddress();
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
