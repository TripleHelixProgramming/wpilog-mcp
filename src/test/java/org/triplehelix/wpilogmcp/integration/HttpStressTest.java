package org.triplehelix.wpilogmcp.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.HttpTransport;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.tba.TbaConfig;
import org.triplehelix.wpilogmcp.tools.WpilogTools;

/**
 * HTTP transport stress test exercising multi-client concurrency and session isolation.
 *
 * <p>Starts a real {@link HttpTransport} server and makes concurrent HTTP requests
 * from multiple simulated clients, each with their own session. Tests session isolation
 * (one client's active log doesn't affect another), concurrent tool execution,
 * and protocol compliance under load.
 *
 * <p>Requires real log files. Configured identically to {@link StressTest}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("HTTP Transport Stress Test")
class HttpStressTest {

  private static Path logDirectory;
  private static HttpTransport transport;
  private static HttpClient httpClient;
  private static int port;
  private static List<String> availableLogPaths;

  // Statistics
  private static final AtomicInteger totalRequests = new AtomicInteger(0);
  private static final AtomicInteger successfulRequests = new AtomicInteger(0);
  private static final AtomicInteger failedRequests = new AtomicInteger(0);
  private static long testStartMs;

  @BeforeAll
  static void setup() throws IOException {
    // Only runs when explicitly invoked via ./gradlew httpStressTest
    boolean enabled = "true".equals(System.getProperty("stress.enabled"));
    assumeTrue(enabled,
        "HTTP stress test skipped. Run via: ./gradlew httpStressTest");

    // Load configuration: try "stresstest" named config from config file,
    // fall back to synthesized defaults (~/riologs, team 2363, TBA from env).
    try {
      Path configPath = System.getProperty("stress.configpath") != null
          ? Path.of(System.getProperty("stress.configpath")) : null;
      var loader = new org.triplehelix.wpilogmcp.config.ConfigLoader();
      org.triplehelix.wpilogmcp.config.ServerConfig config;
      try {
        config = loader.load("stresstest", configPath);
      } catch (Exception e) {
        String home = System.getProperty("user.home");
        config = new org.triplehelix.wpilogmcp.config.ServerConfig("stresstest",
            home + "/riologs", 2363, System.getenv("TBA_API_KEY"),
            "stdio", null, null, null, null, null, null, null);
      }
      org.triplehelix.wpilogmcp.Main.applyConfig(config);

      String logDirPath = config.logdir();
      assumeTrue(logDirPath != null && !logDirPath.isEmpty(),
          "HTTP stress test skipped: no logdir configured");

      logDirectory = Path.of(logDirPath);
      assumeTrue(Files.isDirectory(logDirectory),
          "HTTP stress test skipped: directory does not exist: " + logDirPath);
    } catch (Exception e) {
      assumeTrue(false, "HTTP stress test skipped: " + e.getMessage());
      return;
    }

    // Register all tools (including DiscoveryTools)
    var registry = new ToolRegistry();
    WpilogTools.registerAll(registry);

    // Start the HTTP transport on a random port
    transport = new HttpTransport(registry, 0);
    transport.start();
    port = transport.getPort();
    httpClient = HttpClient.newHttpClient();

    testStartMs = System.currentTimeMillis();
    System.out.println("\n========================================");
    System.out.println("HTTP Transport Stress Test");
    System.out.println("========================================");
    System.out.println("Server: http://127.0.0.1:" + port + "/mcp");
    System.out.println("Log directory: " + logDirectory);
    System.out.println();
  }

  @AfterAll
  static void tearDown() {
    if (transport != null) {
      transport.stop();
    }
    LogManager.getInstance().unloadAllLogs();
  }

  // ==================== 1. Session Lifecycle ====================

  @Test
  @Order(1)
  @DisplayName("1. Session lifecycle: initialize, use, delete")
  void sessionLifecycle() throws Exception {
    System.out.println("Session lifecycle:");

    // Initialize
    var sessionId = initialize();
    assertNotNull(sessionId);
    System.out.println("  Created session: " + sessionId.substring(0, 8) + "...");

    // tools/list
    var listResult = rpcCall(sessionId, "tools/list", null, 2);
    var tools = listResult.getAsJsonObject("result").getAsJsonArray("tools");
    assertTrue(tools.size() >= 45, "Should have all tools registered, got " + tools.size());
    System.out.println("  tools/list: " + tools.size() + " tools");

    // ping
    var pingResult = rpcCall(sessionId, "ping", null, 3);
    assertNotNull(pingResult.get("result"));
    System.out.println("  ping: OK");

    // Delete session
    var deleteResponse = delete(sessionId);
    assertEquals(200, deleteResponse.statusCode());
    System.out.println("  delete: OK");

    // Verify session is gone
    var afterDelete = post(
        rpcMessage("tools/list", null, 4), sessionId);
    assertEquals(404, afterDelete.statusCode());
    System.out.println("  post-delete 404: OK");
  }

  // ==================== 2. Discover available logs ====================

  @Test
  @Order(2)
  @DisplayName("2. Discover and load logs via HTTP")
  void discoverAndLoad() throws Exception {
    var sessionId = initialize();
    System.out.println("\nDiscover and load via HTTP:");

    // list_available_logs
    var result = toolCall(sessionId, "list_available_logs", new JsonObject());
    int logCount = result.get("log_count").getAsInt();
    System.out.println("  Found " + logCount + " logs");

    availableLogPaths = new ArrayList<>();
    for (var entry : result.getAsJsonArray("logs")) {
      availableLogPaths.add(entry.getAsJsonObject().get("path").getAsString());
    }
    assumeTrue(!availableLogPaths.isEmpty(), "No log files found");

    // Load first log via list_entries (auto-loads)
    var listArgs = new JsonObject();
    listArgs.addProperty("path", availableLogPaths.get(0));
    var listResult = toolCall(sessionId, "list_entries", listArgs);
    assertTrue(listResult.get("success").getAsBoolean());
    int entryCount = listResult.getAsJsonArray("entries").size();
    System.out.println("  Loaded: " + Path.of(availableLogPaths.get(0)).getFileName()
        + " (" + entryCount + " entries)");

    delete(sessionId);
  }

  // ==================== 3. Session Isolation ====================

  @Test
  @Order(3)
  @DisplayName("3. Session isolation: independent active logs")
  void sessionIsolation() throws Exception {
    assumeTrue(availableLogPaths != null && availableLogPaths.size() >= 2,
        "Need at least 2 logs for isolation test");

    System.out.println("\nSession isolation test:");

    // Create two sessions
    var session1 = initialize();
    var session2 = initialize();
    System.out.println("  Session 1: " + session1.substring(0, 8) + "...");
    System.out.println("  Session 2: " + session2.substring(0, 8) + "...");

    // Load different logs in each session via list_entries (auto-loads)
    var list1Args = new JsonObject();
    list1Args.addProperty("path", availableLogPaths.get(0));
    var result1 = toolCall(session1, "list_entries", list1Args);
    assertTrue(result1.get("success").getAsBoolean());
    int entries1 = result1.getAsJsonArray("entries").size();
    System.out.println("  Session 1 loaded: " + Path.of(availableLogPaths.get(0)).getFileName()
        + " (" + entries1 + " entries)");

    var list2Args = new JsonObject();
    list2Args.addProperty("path", availableLogPaths.get(1));
    var result2 = toolCall(session2, "list_entries", list2Args);
    assertTrue(result2.get("success").getAsBoolean());
    int entries2 = result2.getAsJsonArray("entries").size();
    System.out.println("  Session 2 loaded: " + Path.of(availableLogPaths.get(1)).getFileName()
        + " (" + entries2 + " entries)");

    // Verify each session sees consistent results with explicit paths
    var list1Again = toolCall(session1, "list_entries", list1Args);
    var list2Again = toolCall(session2, "list_entries", list2Args);
    int s1entries = list1Again.getAsJsonArray("entries").size();
    int s2entries = list2Again.getAsJsonArray("entries").size();

    assertEquals(entries1, s1entries, "Session 1 should see consistent results");
    assertEquals(entries2, s2entries, "Session 2 should see consistent results");

    if (entries1 != entries2) {
      System.out.println("  Isolation confirmed: session 1 has " + s1entries
          + " entries, session 2 has " + s2entries);
    } else {
      System.out.println("  Sessions have same entry count (logs may be similar)");
    }

    // Session 1 queries different path — session 2 should still see its own
    var crossArgs = new JsonObject();
    crossArgs.addProperty("path", availableLogPaths.get(1));
    toolCall(session1, "list_entries", crossArgs);

    var list2check = toolCall(session2, "list_entries", list2Args);
    int s2entriesCheck = list2check.getAsJsonArray("entries").size();
    assertEquals(s2entries, s2entriesCheck,
        "Session 2 should be unaffected by session 1 querying a different path");
    System.out.println("  Cross-session path isolation: OK");

    delete(session1);
    delete(session2);
  }

  // ==================== 4. Concurrent Tool Execution ====================

  @Test
  @Order(4)
  @DisplayName("4. Concurrent tool execution across sessions")
  void concurrentToolExecution() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());

    System.out.println("\nConcurrent tool execution:");

    int clientCount = 4;
    int opsPerClient = 15;
    var errors = Collections.synchronizedList(new ArrayList<String>());
    var opCounts = new ConcurrentHashMap<String, AtomicInteger>();
    var barrier = new CyclicBarrier(clientCount);

    ExecutorService executor = Executors.newFixedThreadPool(clientCount);
    var latch = new CountDownLatch(clientCount);

    for (int c = 0; c < clientCount; c++) {
      final int clientId = c;
      executor.submit(() -> {
        try {
          // Each client gets its own session
          var sessionId = initialize();
          String logPath = availableLogPaths.get(0);

          // Wait for all clients to be ready
          barrier.await(30, TimeUnit.SECONDS);

          // Fire concurrent requests
          for (int i = 0; i < opsPerClient; i++) {
            String toolName;
            var args = new JsonObject();
            args.addProperty("path", logPath);

            // Rotate through different tool types
            switch (i % 5) {
              case 0 -> { toolName = "health_check"; }
              case 1 -> { toolName = "list_entries"; }
              case 2 -> { toolName = "get_match_phases"; }
              case 3 -> { toolName = "generate_report"; }
              default -> { toolName = "get_types"; }
            }

            try {
              var result = toolCall(sessionId, toolName, args);
              opCounts.computeIfAbsent(toolName, k -> new AtomicInteger()).incrementAndGet();
            } catch (Exception e) {
              errors.add("Client " + clientId + " " + toolName + ": " + e.getMessage());
            }
          }

          delete(sessionId);
        } catch (Exception e) {
          errors.add("Client " + clientId + " setup: " + e.getMessage());
        } finally {
          latch.countDown();
        }
      });
    }

    assertTrue(latch.await(60, TimeUnit.SECONDS), "Clients should finish within 60s");
    executor.shutdownNow();

    int totalOps = opCounts.values().stream().mapToInt(AtomicInteger::get).sum();
    System.out.println("  " + clientCount + " clients × " + opsPerClient
        + " ops = " + totalOps + " successful tool calls");
    opCounts.forEach((tool, count) ->
        System.out.println("    " + tool + ": " + count.get()));

    if (!errors.isEmpty()) {
      System.out.println("  Errors (" + errors.size() + "):");
      errors.stream().limit(5).forEach(e -> System.out.println("    " + e));
    }

    assertTrue(errors.size() < clientCount * opsPerClient / 2,
        "Most operations should succeed. Errors: " + errors);
  }

  // ==================== 5. Concurrent Load of Same File ====================

  @Test
  @Order(5)
  @DisplayName("5. Concurrent load of same log from multiple sessions")
  void concurrentSameFileLoad() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());

    System.out.println("\nConcurrent same-file load:");

    int clientCount = 3;
    var barrier = new CyclicBarrier(clientCount);
    var results = new ConcurrentHashMap<Integer, Boolean>();
    var latch = new CountDownLatch(clientCount);

    for (int c = 0; c < clientCount; c++) {
      final int clientId = c;
      new Thread(() -> {
        try {
          var sessionId = initialize();
          barrier.await(10, TimeUnit.SECONDS);

          var listArgs = new JsonObject();
          listArgs.addProperty("path", availableLogPaths.get(0));
          var result = toolCall(sessionId, "list_entries", listArgs);
          results.put(clientId, result.get("success").getAsBoolean());

          delete(sessionId);
        } catch (Exception e) {
          results.put(clientId, false);
        } finally {
          latch.countDown();
        }
      }).start();
    }

    assertTrue(latch.await(30, TimeUnit.SECONDS));

    long succeeded = results.values().stream().filter(b -> b).count();
    System.out.println("  " + succeeded + "/" + clientCount + " clients loaded successfully");
    assertTrue(succeeded == clientCount,
        "All clients should successfully load the same file");
  }

  // ==================== 6. Batch Requests ====================

  @Test
  @Order(6)
  @DisplayName("6. Batch JSON-RPC requests")
  void batchRequests() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());

    System.out.println("\nBatch requests:");

    var sessionId = initialize();
    String logPath = availableLogPaths.get(0);

    // Send a batch of 5 requests (log-requiring tools include path)
    var batch = new JsonArray();
    batch.add(JsonParser.parseString(rpcMessage("ping", null, 10)));
    batch.add(JsonParser.parseString(rpcMessage("tools/list", null, 11)));
    batch.add(JsonParser.parseString(toolCallMessage("health_check", new JsonObject(), 12)));
    var listEntriesArgs = new JsonObject();
    listEntriesArgs.addProperty("path", logPath);
    batch.add(JsonParser.parseString(toolCallMessage("list_entries", listEntriesArgs, 13)));
    var getTypesArgs = new JsonObject();
    getTypesArgs.addProperty("path", logPath);
    batch.add(JsonParser.parseString(toolCallMessage("get_types", getTypesArgs, 14)));

    var response = post(batch.toString(), sessionId);
    assertEquals(200, response.statusCode());

    var batchResponse = JsonParser.parseString(response.body()).getAsJsonArray();
    assertEquals(5, batchResponse.size(), "Should have 5 responses");

    int batchSuccesses = 0;
    for (var resp : batchResponse) {
      if (resp.getAsJsonObject().has("result")) batchSuccesses++;
    }
    System.out.println("  Batch of 5: " + batchSuccesses + " successful");
    assertEquals(5, batchSuccesses);

    delete(sessionId);
  }

  // ==================== 7. Protocol Edge Cases ====================

  @Test
  @Order(7)
  @DisplayName("7. Protocol edge cases")
  void protocolEdgeCases() throws Exception {
    System.out.println("\nProtocol edge cases:");

    // POST without session header (non-initialize) → 400
    var noSession = post(rpcMessage("tools/list", null, 1), null);
    assertEquals(400, noSession.statusCode());
    System.out.println("  Missing session header → 400: OK");

    // POST with invalid session → 404
    var badSession = post(rpcMessage("tools/list", null, 2), "nonexistent");
    assertEquals(404, badSession.statusCode());
    System.out.println("  Invalid session → 404: OK");

    // Notification (no id) → 202
    var sessionId = initialize();
    var notification = "{\"jsonrpc\":\"2.0\",\"method\":\"initialized\"}";
    var notifResponse = post(notification, sessionId);
    assertEquals(202, notifResponse.statusCode());
    System.out.println("  Notification → 202: OK");

    // Unknown method → error response
    var unknownResult = rpcCall(sessionId, "nonexistent/method", null, 3);
    assertTrue(unknownResult.has("error"));
    System.out.println("  Unknown method → error: OK");

    // Empty batch → 400
    var emptyBatch = post("[]", sessionId);
    assertEquals(400, emptyBatch.statusCode());
    System.out.println("  Empty batch → 400: OK");

    // Batch without session header and without initialize → 400
    var batchNoSession = post(
        "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}]", null);
    assertEquals(400, batchNoSession.statusCode());
    System.out.println("  Batch without session → 400: OK");

    // Malformed JSON → 400
    var malformed = post("not json", sessionId);
    assertEquals(400, malformed.statusCode());
    System.out.println("  Malformed JSON → 400: OK");

    // OPTIONS (CORS preflight)
    var options = httpClient.send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", "http://localhost:2363")
            .build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(204, options.statusCode());
    assertTrue(options.headers().firstValue("Access-Control-Allow-Methods").isPresent());
    System.out.println("  CORS OPTIONS → 204: OK");

    delete(sessionId);
  }

  // ==================== 8. Full Tool Coverage ====================

  @Test
  @Order(8)
  @DisplayName("8. Full tool coverage via HTTP")
  void fullToolCoverage() throws Exception {
    assumeTrue(availableLogPaths != null && !availableLogPaths.isEmpty());

    System.out.println("\nFull tool coverage via HTTP:");

    var sessionId = initialize();
    String logPath = availableLogPaths.get(0);

    // Get entry names for parameterized tools (auto-loads the log)
    var listEntriesArgs = new JsonObject();
    listEntriesArgs.addProperty("path", logPath);
    var entries = toolCall(sessionId, "list_entries", listEntriesArgs);
    var entryNames = new ArrayList<String>();
    for (var e : entries.getAsJsonArray("entries")) {
      entryNames.add(e.getAsJsonObject().get("name").getAsString());
    }

    // Find a numeric entry for statistical tools
    String numericEntry = entryNames.stream()
        .filter(n -> n.toLowerCase().contains("voltage") || n.toLowerCase().contains("velocity")
            || n.toLowerCase().contains("position") || n.toLowerCase().contains("current"))
        .findFirst().orElse(entryNames.isEmpty() ? null : entryNames.get(0));

    // Discovery tools (not log-requiring)
    exerciseTool(sessionId, "get_server_guide", new JsonObject(), "discovery");
    var suggestArgs = new JsonObject();
    suggestArgs.addProperty("task", "analyze battery voltage");
    exerciseTool(sessionId, "suggest_tools", suggestArgs, "discovery");

    // Core tools (not log-requiring)
    exerciseTool(sessionId, "health_check", new JsonObject(), "core");
    exerciseTool(sessionId, "list_struct_types", new JsonObject(), "core");
    exerciseTool(sessionId, "list_loaded_logs", new JsonObject(), "core");

    // Query tools (log-requiring)
    var searchArgs = new JsonObject();
    searchArgs.addProperty("path", logPath);
    searchArgs.addProperty("pattern", "voltage");
    exerciseTool(sessionId, "search_strings", searchArgs, "query");

    // Statistics tools (log-requiring, with a numeric entry)
    if (numericEntry != null) {
      var statsArgs = new JsonObject();
      statsArgs.addProperty("path", logPath);
      statsArgs.addProperty("name", numericEntry);
      exerciseTool(sessionId, "get_statistics", statsArgs, "statistics");
      exerciseTool(sessionId, "detect_anomalies", statsArgs, "statistics");

      var peakArgs = new JsonObject();
      peakArgs.addProperty("path", logPath);
      peakArgs.addProperty("name", numericEntry);
      peakArgs.addProperty("limit", 5);
      exerciseTool(sessionId, "find_peaks", peakArgs, "statistics");

      var rateArgs = new JsonObject();
      rateArgs.addProperty("path", logPath);
      rateArgs.addProperty("name", numericEntry);
      rateArgs.addProperty("limit", 5);
      exerciseTool(sessionId, "rate_of_change", rateArgs, "statistics");
    }

    // FRC domain tools (log-requiring)
    exerciseTool(sessionId, "get_match_phases", withPath(logPath), "frc");
    exerciseTool(sessionId, "analyze_auto", withPath(logPath), "frc");
    exerciseTool(sessionId, "get_ds_timeline", withPath(logPath), "frc");
    exerciseTool(sessionId, "analyze_vision", withPath(logPath), "frc");
    exerciseTool(sessionId, "analyze_replay_drift", withPath(logPath), "frc");
    exerciseTool(sessionId, "analyze_loop_timing", withPath(logPath), "frc");
    exerciseTool(sessionId, "analyze_can_bus", withPath(logPath), "frc");
    exerciseTool(sessionId, "predict_battery_health", withPath(logPath), "frc");
    exerciseTool(sessionId, "analyze_swerve", withPath(logPath), "frc");

    // Robot analysis tools (log-requiring)
    exerciseTool(sessionId, "power_analysis", withPath(logPath), "robot");
    exerciseTool(sessionId, "can_health", withPath(logPath), "robot");
    exerciseTool(sessionId, "get_code_metadata", withPath(logPath), "robot");

    // Game data (not log-requiring)
    exerciseTool(sessionId, "get_game_info", new JsonObject(), "game");

    // Export (log-requiring)
    exerciseTool(sessionId, "generate_report", withPath(logPath), "export");
    if (numericEntry != null) {
      var exportArgs = new JsonObject();
      exportArgs.addProperty("path", logPath);
      exportArgs.addProperty("name", numericEntry);
      exportArgs.addProperty("output_path",
          System.getProperty("java.io.tmpdir") + "/wpilog-export/http_stress_export.csv");
      exerciseTool(sessionId, "export_csv", exportArgs, "export");
    }

    // TBA (not log-requiring)
    exerciseTool(sessionId, "get_tba_status", new JsonObject(), "tba");

    // RevLog (log-requiring)
    exerciseTool(sessionId, "sync_status", withPath(logPath), "revlog");
    exerciseTool(sessionId, "list_revlog_signals", withPath(logPath), "revlog");

    delete(sessionId);
  }

  // ==================== 9. Summary ====================

  @Test
  @Order(99)
  @DisplayName("9. Summary")
  void printSummary() {
    long elapsed = System.currentTimeMillis() - testStartMs;
    System.out.println("\n========================================");
    System.out.println("HTTP Stress Test Summary");
    System.out.println("========================================");
    System.out.println("Total HTTP requests: " + totalRequests.get());
    System.out.println("Successful: " + successfulRequests.get());
    System.out.println("Failed: " + failedRequests.get());
    System.out.printf("Total time: %dms%n", elapsed);
    if (totalRequests.get() > 0) {
      System.out.printf("Average: %.2fms/request%n",
          (double) elapsed / totalRequests.get());
    }
    System.out.println("========================================\n");
  }

  // ==================== HTTP Helpers ====================

  private static String initialize() throws IOException, InterruptedException {
    var response = post(
        rpcMessage("initialize", new JsonObject(), 1), null);
    assertEquals(200, response.statusCode(),
        "Initialize should return 200, got " + response.statusCode());
    return response.headers().firstValue("Mcp-Session-Id").orElseThrow(
        () -> new AssertionError("Initialize response missing Mcp-Session-Id header"));
  }

  private static JsonObject toolCall(String sessionId, String toolName, JsonObject arguments)
      throws IOException, InterruptedException {
    var response = post(toolCallMessage(toolName, arguments, nextId()), sessionId);
    assertEquals(200, response.statusCode(),
        "Tool call " + toolName + " should return 200, got " + response.statusCode()
            + ": " + response.body());

    var body = JsonParser.parseString(response.body()).getAsJsonObject();
    var result = body.getAsJsonObject("result");
    assertNotNull(result, "Tool call " + toolName + " should have result");

    // Unwrap MCP content wrapper
    if (result.has("content")) {
      var content = result.getAsJsonArray("content");
      if (!content.isEmpty()) {
        var text = content.get(0).getAsJsonObject().get("text").getAsString();
        return JsonParser.parseString(text).getAsJsonObject();
      }
    }
    return result;
  }

  private static JsonObject rpcCall(String sessionId, String method, JsonElement params, int id)
      throws IOException, InterruptedException {
    var response = post(rpcMessage(method, params, id), sessionId);
    totalRequests.incrementAndGet();
    if (response.statusCode() == 200) {
      successfulRequests.incrementAndGet();
    } else {
      failedRequests.incrementAndGet();
    }
    return JsonParser.parseString(response.body()).getAsJsonObject();
  }

  private static void exerciseTool(String sessionId, String toolName, JsonObject args,
      String category) {
    try {
      var result = toolCall(sessionId, toolName, args);
      boolean success = result.has("success") && result.get("success").getAsBoolean();
      System.out.printf("  [%s] %-25s %s%n", category, toolName, success ? "OK" : "no data");
    } catch (Exception e) {
      System.out.printf("  [%s] %-25s ERROR: %s%n", category, toolName, e.getMessage());
    }
  }

  private static HttpResponse<String> post(String body, String sessionId)
      throws IOException, InterruptedException {
    totalRequests.incrementAndGet();
    var builder = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + "/mcp"))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString(body));
    if (sessionId != null) {
      builder.header("Mcp-Session-Id", sessionId);
    }
    var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 200 || response.statusCode() == 202) {
      successfulRequests.incrementAndGet();
    } else {
      failedRequests.incrementAndGet();
    }
    return response;
  }

  private static HttpResponse<String> delete(String sessionId)
      throws IOException, InterruptedException {
    totalRequests.incrementAndGet();
    var request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + "/mcp"))
        .header("Mcp-Session-Id", sessionId)
        .method("DELETE", HttpRequest.BodyPublishers.noBody())
        .build();
    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 200) {
      successfulRequests.incrementAndGet();
    } else {
      failedRequests.incrementAndGet();
    }
    return response;
  }

  private static String rpcMessage(String method, JsonElement params, int id) {
    var msg = new JsonObject();
    msg.addProperty("jsonrpc", "2.0");
    msg.addProperty("id", id);
    msg.addProperty("method", method);
    if (params != null) msg.add("params", params);
    return msg.toString();
  }

  private static String toolCallMessage(String toolName, JsonObject arguments, int id) {
    var params = new JsonObject();
    params.addProperty("name", toolName);
    params.add("arguments", arguments != null ? arguments : new JsonObject());
    return rpcMessage("tools/call", params, id);
  }

  private static JsonObject withPath(String logPath) {
    var args = new JsonObject();
    args.addProperty("path", logPath);
    return args;
  }

  private static final AtomicInteger idCounter = new AtomicInteger(100);
  private static int nextId() { return idCounter.incrementAndGet(); }
}
