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
  private final ToolRegistry toolRegistry;
  private final McpMessageHandler handler;
  private final SessionManager sessionManager;
  private final ClientLeases leases = ClientLeases.getInstance();
  private final RegistrationEndpoint registration;
  private volatile VisualizationEndpoint visualization;
  private String visualizationFailure;
  private boolean leasesOpened;
  private final int port;
  private final String bindAddress;
  private final String mcpPath;
  private final String configurationName;
  private final java.util.Set<String> allowedOriginHosts;
  private HttpServer server;
  private java.util.concurrent.ExecutorService httpExecutor;
  private ScheduledExecutorService scheduler;
  private java.util.concurrent.ExecutorService sseExecutor;
  private java.util.concurrent.ExecutorService mcpExecutor;
  private ScheduledExecutorService bodyDeadlines;
  private StreamingRequests streaming;
  private StreamingRequests storeStreaming;
  private StreamingRequests followStreaming;
  private StreamingRequests inventoryStreaming;
  private Duration streamTimeout = Duration.ofSeconds(10);
  void streamTimeout(Duration timeout) { streamTimeout = timeout; }
  int activeStreams() { return streaming == null ? 0 : streaming.activeCount(); }
  private int maxRequestBytes = 4 * 1024 * 1024;
  private int maxMcpRequests = Math.min(32, Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
  private Duration bodyTimeout = Duration.ofSeconds(10);
  private final AtomicInteger readingBodies = new AtomicInteger();
  void requestLimits(int bytes, int concurrent, Duration timeout) {
    if (server != null || bytes < 1 || concurrent < 1 || timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("Positive request limits must be set before start");
    }
    maxRequestBytes = bytes; maxMcpRequests = concurrent; bodyTimeout = timeout;
  }
  int readingBodies() { return readingBodies.get(); }
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
  private volatile org.triplehelix.wpilogmcp.store.LocalIngestion localIngestion;
  /** Selected config identity is supplied by Main; anonymous transports never persist policy. */
  public void configureLocalIngestion(java.nio.file.Path policyFile) throws IOException {
    if (server != null || configurationName == null) throw new IllegalStateException("A named server must configure ingestion before start");
    localIngestion = stores.configureLocalIngestion(policyFile);
    readEndpoint.preferredStore(localIngestion::preferredStoreId);
  }
  private final org.triplehelix.wpilogmcp.store.StoreDoor storeDoor = new org.triplehelix.wpilogmcp.store.StoreDoor(
      () -> storeDirectories != null ? storeDirectories : org.triplehelix.wpilogmcp.log.LogManager.getInstance()
          .getConfiguredDirectories());
  private final StoreEndpoint storeEndpoint = new StoreEndpoint(storeDoor);
  private final ReadEndpoint readEndpoint = new ReadEndpoint(
      org.triplehelix.wpilogmcp.log.LogManager.getInstance().readAccess(), () -> {
        var inventory = storeDoor.inventory();
        var roots = new java.util.ArrayList<java.nio.file.Path>();
        inventory.stores().forEach(s -> roots.add(s.root()));
        inventory.unreadable().forEach(s -> roots.add(java.nio.file.Path.of(s.path())));
        return roots.isEmpty() ? null : new org.triplehelix.wpilogmcp.read.catalog.StoreReadCatalog(
            org.triplehelix.wpilogmcp.read.ReadOptions.defaults(roots),
            org.triplehelix.wpilogmcp.log.LogManager.getInstance().readAccess(),
            org.triplehelix.wpilogmcp.read.catalog.StoreReadCatalog.DEFAULT_SESSION_INDEX_BYTES);
      });
  private volatile MetricsEndpoint metricsEndpoint = new MetricsEndpoint(null, null);
  private final FollowEndpoint followEndpoint = new FollowEndpoint(readEndpoint, reference ->
      new org.triplehelix.wpilogmcp.read.server.CatalogRecordingSource(
          org.triplehelix.wpilogmcp.log.LogManager.getInstance(), readEndpoint::catalog, reference));
  private volatile java.util.function.Supplier<org.triplehelix.wpilogmcp.nt4.server.GatewayStatus> gatewayStatus =
      () -> org.triplehelix.wpilogmcp.nt4.server.GatewayStatus.DISABLED;
  public void setGatewayStatus(java.util.function.Supplier<org.triplehelix.wpilogmcp.nt4.server.GatewayStatus> status) {
    gatewayStatus = java.util.Objects.requireNonNull(status);
  }
  public void configureMetrics(org.triplehelix.wpilogmcp.config.MetricsConfig config,
      org.triplehelix.wpilogmcp.capture.LiveCapture capture) { metricsEndpoint = new MetricsEndpoint(config, capture); }
  public void configureMetrics(org.triplehelix.wpilogmcp.config.MetricsConfig config,
      org.triplehelix.wpilogmcp.capture.LiveCapture capture, java.util.function.Supplier<MetricsEndpoint.Components> components) {
    metricsEndpoint = new MetricsEndpoint(config, capture, components);
  }
  private final StoreImportEndpoint importEndpoint = new StoreImportEndpoint(stores, storeDoor);
  /** One guard for the mirror's and the live source's controls, which each refuse the other's folder. */
  private final java.util.concurrent.atomic.AtomicBoolean localControls = new java.util.concurrent.atomic.AtomicBoolean();
  /** The guard both local controls take, so a test can hold it as a change in progress would. */
  java.util.concurrent.atomic.AtomicBoolean localControlsGuard() { return localControls; }
  private final MirrorEndpoint mirrorEndpoint = new MirrorEndpoint(stores, () -> this.liveSourceEndpoint.folder(), localControls);
  /** The capture the live tools answer from: the one servers.yaml configured, or a laptop's live
   * source set at run time, or none. */
  private final java.util.concurrent.atomic.AtomicReference<org.triplehelix.wpilogmcp.capture.LiveCapture> liveCapture =
      new java.util.concurrent.atomic.AtomicReference<>();
  private final LiveSourceEndpoint liveSourceEndpoint = new LiveSourceEndpoint(
      org.triplehelix.wpilogmcp.log.LogManager.getInstance(), liveCapture, mirrorEndpoint::folder, localControls);
  /** What the live tools read at each call. */
  public java.util.function.Supplier<org.triplehelix.wpilogmcp.capture.LiveCapture> liveCapture() { return liveCapture::get; }
  /** A capture configured in servers.yaml: the live tools read it, and the live source control
   * reports it but cannot replace it. */
  public void useConfiguredCapture(org.triplehelix.wpilogmcp.capture.CaptureService capture) { liveSourceEndpoint.useConfigured(capture); }
  private final StoreSyncEndpoint syncEndpoint = new StoreSyncEndpoint(stores,
      new org.triplehelix.wpilogmcp.store.StoreDoor(() -> storeDirectories != null ? storeDirectories
          : org.triplehelix.wpilogmcp.log.LogManager.getInstance().getAllowedDirectories()),
      () -> storeDirectories != null ? storeDirectories : org.triplehelix.wpilogmcp.log.LogManager.getInstance().getAllowedDirectories());
  /** When the MCP endpoint was last asked for anything, or a session last removed. */
  private java.util.function.LongSupplier idleClock = System::nanoTime;
  private volatile long lastMcpActivityNanos = idleClock.getAsLong();

  public HttpTransport(ToolRegistry toolRegistry, int port) {
    this(toolRegistry, port, "127.0.0.1", null, null);
  }

  public HttpTransport(ToolRegistry toolRegistry, int port, String bindAddress,
      java.util.Set<String> allowedOriginHosts, String mcpPath) {
    this(toolRegistry, port, bindAddress, allowedOriginHosts, mcpPath, null);
  }

  public HttpTransport(ToolRegistry toolRegistry, int port, String bindAddress,
      java.util.Set<String> allowedOriginHosts, String mcpPath, String configurationName) {
    this.toolRegistry = toolRegistry;
    this.gson = new GsonBuilder().serializeNulls().create();
    this.sessionManager = new SessionManager(id -> {
      leases.remove(id);
      var endpoint = visualization; if (endpoint != null) endpoint.release(id);
    });
    this.registration = new RegistrationEndpoint(sessionManager, leases);
    this.handler = new McpMessageHandler(toolRegistry, sessionManager);
    this.port = port;
    this.configurationName = configurationName;
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
  void idleClock(java.util.function.LongSupplier clock) {
    idleClock = clock; lastMcpActivityNanos = clock.getAsLong();
  }
  boolean stopping() { return stopped.get(); }

  public void setIdleExit(Duration idle, Runnable onIdle) {
    this.idleExit = idle;
    this.onIdle = onIdle;
  }

  private void handleLocalIngestion(HttpExchange exchange) throws IOException {
    var origin = exchange.getRequestHeaders().getFirst("Origin");
    if (forwarded(exchange)) {
      LocalIngestionEndpoint.error(exchange, 403, "ACCESS_DENIED", "Data store setup requires a direct loopback connection; " + FORWARDED); return;
    }
    if (origin != null && !isAllowedOrigin(origin)
        || !server.getAddress().getAddress().isLoopbackAddress()
        || !exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
      LocalIngestionEndpoint.error(exchange, 403, "ACCESS_DENIED", "Data store setup requires loopback and an allowed Origin"); return;
    }
    noteMcpActivity();
    new LocalIngestionEndpoint(sessionManager, () -> localIngestion).handle(exchange);
  }

  private void handleVisualization(HttpExchange exchange) throws IOException { handleVisualization(exchange, true); }
  private void handleVisualization(HttpExchange exchange, boolean inventoryAdmitted) throws IOException {
    var origin = exchange.getRequestHeaders().getFirst("Origin");
    if (forwarded(exchange)) {
      LocalIngestionEndpoint.error(exchange, 403, "ACCESS_DENIED", "Visualization preview requires a direct loopback connection; " + FORWARDED); return;
    }
    var authority = CallerContext.http(server.getAddress().getAddress(), exchange.getRemoteAddress().getAddress(), origin == null || isAllowedOrigin(origin), forwarded(exchange));
    if (authority == CallerContext.Authority.UNTRUSTED) {
      LocalIngestionEndpoint.error(exchange, 403, "ACCESS_DENIED", "Visualization preview requires loopback and an allowed Origin"); return;
    }
    noteMcpActivity();
    var endpoint = visualization;
    if (endpoint == null) LocalIngestionEndpoint.error(exchange, 503, "WORKSPACE_UNAVAILABLE", visualizationFailure);
    else endpoint.handle(exchange, authority, inventoryAdmitted);
  }

  public void start() throws IOException {
    try { visualization = new VisualizationEndpoint(sessionManager, toolRegistry); }
    catch (IOException failure) { visualizationFailure = failure.getMessage(); logger.warn("Visualization preview unavailable: {}", visualizationFailure); }
    server = HttpServer.create(new InetSocketAddress(this.bindAddress, port), 0);
    server.createContext(this.mcpPath, counted(this::handleRequest));
    server.createContext("/directories", counted(this::handleRegistration));
    server.createContext("/tba-key", counted(this::handleRegistration));
    server.createContext("/pit-credential", counted(this::handleRegistration));
    server.createContext("/pit-mcp", counted(this::handlePitMcp));
    server.createContext("/health", counted(this::handleHealthCheck));
    server.createContext("/metrics", counted(this::handleMetrics));
    server.createContext("/stop", counted(this::handleStop));
    streaming = new StreamingRequests(16, streamTimeout);
    storeStreaming = new StreamingRequests(4, streamTimeout);
    followStreaming = new StreamingRequests(16, streamTimeout);
    inventoryStreaming = new StreamingRequests(4, streamTimeout);
    var inventoryRead = inventoryStreaming.handle(counted(this::handleVisualization),
        streaming.handle(counted(e -> handleVisualization(e, false))));
    var visualizationControl = streaming.handle(counted(this::handleVisualization));
    server.createContext(VisualizationEndpoint.PATH, exchange -> {
      if (InventoryRequests.httpRead(exchange.getRequestURI().getPath())) inventoryRead.handle(exchange);
      else visualizationControl.handle(exchange);
    });
    server.createContext(DATA_PATH, streaming.handle(counted(this::handleData)));
    server.createContext(ReadEndpoint.PATH, streaming.handle(counted(this::handleRead)));
    var nextFollow = followStreaming.handle(counted(this::handleFollow));
    var controlFollow = streaming.handle(counted(this::handleFollow));
    server.createContext(FollowEndpoint.PATH, exchange -> {
      if (exchange.getRequestURI().getPath().endsWith("/next") && exchange.getRequestMethod().equals("POST")) nextFollow.handle(exchange);
      else controlFollow.handle(exchange);
    });
    server.createContext(StoreImportEndpoint.PATH, storeStreaming.handle(counted(this::handleImport)));
    server.createContext(StoreImportEndpoint.ASSIGN_PATH, storeStreaming.handle(counted(this::handleImport)));
    server.createContext("/store", storeStreaming.handle(counted(this::handleStore)));
    server.createContext(LocalIngestionEndpoint.PATH, storeStreaming.handle(counted(this::handleLocalIngestion)));
    server.createContext(StoreSyncEndpoint.PATH, counted(this::handleSync));
    server.createContext(MirrorEndpoint.PATH, counted(this::handleMirror));
    server.createContext(LiveSourceEndpoint.PATH, counted(this::handleLiveSource));
    // Direct handoff grows beyond four workers as soon as they are busy. A waiting queue
    // would keep health behind four streaming data/store responses until that queue filled.
    httpExecutor = new ThreadPoolExecutor(4, 32, 60, TimeUnit.SECONDS,
        new SynchronousQueue<>(), r -> {
          var thread = new Thread(r, "http-request"); thread.setDaemon(true); return thread;
        });
    mcpExecutor = new ThreadPoolExecutor(0, maxMcpRequests, 60, TimeUnit.SECONDS,
        new SynchronousQueue<>(), r -> {
          var thread = new Thread(r, "mcp-request"); thread.setDaemon(true); return thread;
        });
    bodyDeadlines = Executors.newSingleThreadScheduledExecutor(r -> {
      var thread = new Thread(r, "mcp-body-deadline"); thread.setDaemon(true); return thread;
    });
    server.setExecutor(httpExecutor);
    leases.transportOpened();
    leasesOpened = true;

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
    server.start();
    stores.startWatching();
    if (localIngestion != null) localIngestion.start();
    lastMcpActivityNanos = idleClock.getAsLong();
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
    lastMcpActivityNanos = idleClock.getAsLong();
  }

  /** Runs {@code onIdle} once, when no session is open and the idle time has passed. */
  void exitIfIdle() {
    var idle = idleExit;
    if (idle == null || localIngestion != null && localIngestion.enabled() || sessionManager.size() > 0 || stores.importing() || importEndpoint.active() || syncEndpoint.active() || mirrorEndpoint.active()) return;
    long idleFor = idleClock.getAsLong() - lastMcpActivityNanos;
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
    liveSourceEndpoint.close();
    followEndpoint.close();
    readEndpoint.close();
    if (localIngestion != null) { localIngestion.close(); stores.clearLocalIngestion(localIngestion); }
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
    if (mcpExecutor != null) mcpExecutor.shutdownNow();
    // No SSE iteration can construct a replacement owner. Keep the existing workspace alive
    // until admitted HTTP/MCP operations drain and the stream sockets have been closed.
    toolRegistry.closeVisualizations();
    if (bodyDeadlines != null) bodyDeadlines.shutdownNow();
    if (streaming != null) streaming.close();
    if (storeStreaming != null) storeStreaming.close();
    if (followStreaming != null) followStreaming.close();
    if (inventoryStreaming != null) inventoryStreaming.close();
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
        case "POST" -> submitPost(exchange);
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
    JsonElement parsed; int rawBytes;
    var reading = new AtomicBoolean(true);
    var expiry = bodyDeadlines.schedule(() -> {
      if (reading.compareAndSet(true, false)) exchange.close();
    }, bodyTimeout.toMillis(), TimeUnit.MILLISECONDS);
    readingBodies.incrementAndGet();
    try {
      var bytes = RequestBodies.read(exchange, maxRequestBytes); rawBytes = bytes.length;
      if (!reading.compareAndSet(true, false)) return;
      if (bytes.length > maxRequestBytes) {
        sendError(exchange, 413, "MCP request body exceeds " + maxRequestBytes + " bytes"); return;
      }
      parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
    } catch (Exception e) {
      logger.warn("Failed to parse JSON-RPC request: {}", e.getMessage());
      sendJsonResponse(exchange, 400,
          JsonRpc.createErrorResponse(null, JsonRpc.PARSE_ERROR, "Parse error"), null);
      return;
    } finally {
      reading.set(false);
      expiry.cancel(false);
      readingBodies.decrementAndGet();
    }

    if (InventoryRequests.reads(parsed)) {
      inventoryStreaming.handle(counted(e -> dispatchMessage(e, parsed, new InventoryRequests.Admission(rawBytes, true))),
          streaming.handle(counted(e -> dispatchMessage(e, parsed, new InventoryRequests.Admission(rawBytes, false))))).handle(exchange);
    } else dispatchMessage(exchange, parsed, new InventoryRequests.Admission(rawBytes, true));
  }
  private void dispatchMessage(HttpExchange exchange, JsonElement parsed, InventoryRequests.Admission admission) throws IOException {
    try (var responses = org.triplehelix.wpilogmcp.visualization.InventoryResponses.open()) {
      if (parsed.isJsonArray()) handleBatchPost(exchange, parsed.getAsJsonArray(), admission);
      else if (parsed.isJsonObject()) handleSinglePost(exchange, parsed.getAsJsonObject(), admission);
      else sendJsonResponse(exchange, 400, JsonRpc.createErrorResponse(null, JsonRpc.INVALID_REQUEST, "Expected object or array"), null);
    }
  }
  /** Slow bodies and tool calls share a bounded admission pool; health never waits behind them. */
  private void submitPost(HttpExchange exchange) throws IOException {
    inFlightRequests.incrementAndGet();
    try {
      mcpExecutor.execute(() -> {
        try { handlePost(exchange); }
        catch (Exception e) {
          logger.warn("MCP request failed: {}", e.toString());
          try { sendError(exchange, 500, "Internal server error"); }
          catch (IOException ignored) { exchange.close(); }
        } finally { inFlightRequests.decrementAndGet(); }
      });
    } catch (java.util.concurrent.RejectedExecutionException e) {
      inFlightRequests.decrementAndGet();
      streaming.refuse(exchange);
    }
  }

  private CallerContext.Authority callerAuthority(HttpExchange exchange) {
    var origin = exchange.getRequestHeaders().getFirst("Origin");
    return CallerContext.http(server.getAddress().getAddress(), exchange.getRemoteAddress().getAddress(),
        origin == null || isAllowedOrigin(origin), forwarded(exchange));
  }

  private void handleSinglePost(HttpExchange exchange, JsonObject message, InventoryRequests.Admission admission) throws IOException {
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
    var result = handler.handleMessage(message, session, callerAuthority(exchange), admission);

    if (result.response() == null) {
      // Notification — no response body
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      return;
    }

    // Send response with session header if this was an initialize
    sendJsonResponse(exchange, 200, result.response(), result.newSessionId());
  }

  private void handleBatchPost(HttpExchange exchange, JsonArray batch, InventoryRequests.Admission admission) throws IOException {
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
    boolean bounded = InventoryRequests.containsOperation(batch);
    int responseBytes = 2; // JSON array brackets; each subsequent response also needs a comma.
    for (var element : batch) {
      JsonObject reply;
      if (!element.isJsonObject()) reply = JsonRpc.createErrorResponse(null, JsonRpc.INVALID_REQUEST, "Batch element must be an object");
      else {
        var result = handler.handleMessage(element.getAsJsonObject(), session, callerAuthority(exchange), admission);
        if (result.newSessionId() != null) {
          newSessionId = result.newSessionId(); session = sessionManager.getSession(newSessionId);
        }
        reply = result.response();
      }
      if (reply == null) continue;
      if (bounded) {
        try {
          int comma = responses.isEmpty() ? 0 : 1;
          int remaining = org.triplehelix.wpilogmcp.visualization.RecordingInventories.MAX_MCP_BATCH_REPLY_BYTES - responseBytes - comma;
          if (remaining < 1) throw new IOException("Batch reply is full");
          responseBytes += comma + org.triplehelix.wpilogmcp.read.internal.ReadWire.bytes(reply, remaining).length;
        } catch (IOException | org.triplehelix.wpilogmcp.read.internal.ReadWire.WireLimit limit) {
          var failure = new org.triplehelix.wpilogmcp.visualization.InventoryFailure(413, "RESOURCE_LIMIT",
              "Inventory-containing MCP batch reply exceeds 8 MiB; earlier calls may have executed. Inspect their state before sending smaller batches", false);
          sendJsonResponse(exchange, 413, failure.json(), newSessionId); return;
        }
      }
      responses.add(reply);
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
            String lastEvent = exchange.getRequestHeaders().getFirst("Last-Event-ID");
            var endpoint = visualization;
            var control = endpoint == null ? null : endpoint.notifications();
            while (!stopped.get() && sessionManager.getSession(sessionId) != null) {
              var events = control == null ? java.util.List.<org.triplehelix.wpilogmcp.visualization.ControlNotifications.Event>of() : control.after(sessionId, lastEvent, 1000);
              for (var event : events) {
                os.write(("id: " + event.id() + "\ndata: " + event.json() + "\n\n").getBytes(StandardCharsets.UTF_8)); lastEvent = event.id();
              }
              os.write(":ping\n\n".getBytes(StandardCharsets.UTF_8)); os.flush();
              if (control == null || !control.negotiated(sessionId)) Thread.sleep(15_000);
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

  private void sendJsonResponse(HttpExchange exchange, int status, JsonElement response,
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
    if (forwarded(exchange) || !isLoopback(exchange.getRemoteAddress())) {
      sendError(exchange, 403, "Registration requires a direct loopback connection; " + FORWARDED);
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
    if (forwarded(exchange) || !isLoopback(exchange.getRemoteAddress())) {
      sendError(exchange, 403, "Pit credential forwarding requires a direct loopback connection; " + FORWARDED); return;
    }
    noteMcpActivity();
    PitMcpEndpoint.handle(exchange);
  }

  /** The time a window's silence is measured against, for tests. */
  void visualizationNow(java.util.function.Supplier<java.time.Instant> now) { visualization.now = now; }

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

  /** Native reads share the existing Origin and configured-directory admission policies. */
  private void handleRead(HttpExchange exchange) throws IOException {
    var origin = exchange.getRequestHeaders().getFirst("Origin");
    if (origin != null && !isAllowedOrigin(origin)) {
      sendError(exchange, 403, "Forbidden: invalid origin"); return;
    }
    readEndpoint.handle(exchange);
  }

  private void handleFollow(HttpExchange exchange) throws IOException {
    var origin = exchange.getRequestHeaders().getFirst("Origin");
    if (origin != null && !isAllowedOrigin(origin)) {
      sendError(exchange, 403, "Forbidden: invalid origin"); return;
    }
    followEndpoint.handle(exchange, body -> {
      if (forwarded(exchange)) throw new java.nio.file.AccessDeniedException("View follow requires a direct loopback connection; " + FORWARDED);
      var authority = callerAuthority(exchange); authority.requireLocal();
      String id = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
      var session = id == null ? null : sessionManager.getSession(id);
      if (session == null) throw new java.nio.file.AccessDeniedException("View follow requires an active Mcp-Session-Id; reconnect before binding");
      return toolRegistry.visualizations().prepareViewFollow(authority, body, session::active);
    });
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
    if (exchange.getRequestMethod().equals("POST") && !bytes && forwarded(exchange)) {
      sendError(exchange, 403, "Server-path imports and assignments require a direct loopback connection; " + FORWARDED + "; upload file bytes instead"); return;
    }
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
    if (forwarded(exchange)) {
      sendError(exchange, 403, "Store sync jobs require a direct loopback connection; " + FORWARDED); return;
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
    if (forwarded(exchange)) {
      sendError(exchange, 403, "Mirror controls require a direct loopback connection; " + FORWARDED); return;
    }
    if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
      sendError(exchange, 403, "Mirror controls require a loopback connection"); return;
    }
    noteMcpActivity(); mirrorEndpoint.handle(exchange);
  }

  private void handleLiveSource(HttpExchange exchange) throws IOException {
    var origin = exchange.getRequestHeaders().getFirst("Origin");
    if (origin != null && !isAllowedOrigin(origin)) {
      sendError(exchange, 403, "Forbidden: invalid origin"); return;
    }
    if (forwarded(exchange)) {
      sendError(exchange, 403, "Live source controls require a direct loopback connection; " + FORWARDED); return;
    }
    if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
      sendError(exchange, 403, "Live source controls require a loopback connection"); return;
    }
    noteMcpActivity(); liveSourceEndpoint.handle(exchange);
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
    health.addProperty("managed", toolRegistry.isManaged());
    health.addProperty("sessions", sessionManager.size());
    // A start compares the version with its own JAR's, and records the process ID of a server
    // it finds on the port without a PID file
    health.addProperty("version", Version.VERSION);
    health.addProperty("name", configurationName);
    health.addProperty("pid", ProcessHandle.current().pid());
    toolRegistry.serverIdentity().describe().entrySet().forEach(e -> health.add(e.getKey(), e.getValue()));
    health.add("gateway", gatewayStatus.get().json());
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
    if (forwarded(exchange)) {
      sendError(exchange, 403, "Stop requests require a direct loopback connection; " + FORWARDED);
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

  /** A same-host reverse proxy reaches a loopback listener from a loopback peer, so the pit
   * guide's nginx block once made everyone past its login a local caller: directory leases, pit
   * forwarding, server-path imports, sync and mirror controls, local ingestion, the visualization
   * routes and stop. A request carrying a header that proxies add is therefore never local at any
   * of those gates. The header only withdraws trust. A proxy that adds none of them cannot be told
   * from a local client, which is why the documented proxy block sets one. */
  static final java.util.List<String> FORWARDING_HEADERS = java.util.List.of(
      "Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Real-IP", "Via");
  static final String FORWARDED = "requests forwarded by a proxy are not local";
  static boolean forwarded(HttpExchange exchange) {
    var headers = exchange.getRequestHeaders();
    return FORWARDING_HEADERS.stream().anyMatch(headers::containsKey);
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
