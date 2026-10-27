/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.harness.HarnessHttp;

/** A stop refuses new work, lets admitted work finish or ends it with a retry answer, then releases (rule R2). */
class HttpTransportStopTest {
  private final ToolRegistry registry = new ToolRegistry();
  private final HttpClient client = HttpClient.newHttpClient();
  private final List<HttpTransport> started = new ArrayList<>();
  private final CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
  private final AtomicBoolean interrupted = new AtomicBoolean();

  HttpTransportStopTest() {
    registry.registerTool(new ToolRegistry.Tool() {
      @Override public String name() { return "held"; }
      @Override public String description() { return "Waits until the test lets it finish"; }
      @Override public JsonObject inputSchema() { return new ToolRegistry.SchemaBuilder().build(); }
      @Override public com.google.gson.JsonElement execute(JsonObject arguments) {
        entered.countDown();
        try { finish.await(); }
        catch (InterruptedException e) { interrupted.set(true); Thread.currentThread().interrupt(); }
        var result = new JsonObject();
        result.addProperty("done", true);
        return result;
      }
    });
  }

  @AfterEach void stopAll() {
    finish.countDown();
    started.forEach(HttpTransport::stop);
  }

  @Test void aRequestArrivingDuringTheStopIsToldToRetryWhileHealthStillAnswers() throws Exception {
    var transport = start();
    var held = callHeld(transport);
    var stopping = CompletableFuture.runAsync(transport::stop);
    HarnessHttp.await("stop begun", 5, transport::stopping);
    var late = post(transport, "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"initialize\",\"params\":{}}", null);
    assertEquals(503, late.statusCode(), late.body());
    assertEquals("1", late.headers().firstValue("Retry-After").orElse(null));
    assertEquals("CLOSED", JsonParser.parseString(late.body()).getAsJsonObject().get("code").getAsString());
    var data = send(transport, HttpRequest.newBuilder(uri(transport, HttpTransport.DATA_PATH)).GET());
    assertEquals(503, data.statusCode(), "A data request is refused too, not answered from closing owners: " + data.body());
    assertEquals(200, send(transport, HttpRequest.newBuilder(uri(transport, "/health")).GET()).statusCode(),
        "Health still answers during the stop");
    assertFalse(stopping.isDone(), "The admitted call is still running");
    finish.countDown();
    stopping.get(10, TimeUnit.SECONDS);
    var response = held.get(10, TimeUnit.SECONDS);
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("done"), response.body());
  }

  @Test void aToolCallLongerThanTheStreamGraceStillFinishes() throws Exception {
    var transport = start(t -> t.streamGrace(Duration.ofMillis(100)));
    var held = callHeld(transport);
    var stopping = CompletableFuture.runAsync(transport::stop);
    HarnessHttp.await("stop begun", 5, transport::stopping);
    long begun = System.nanoTime();
    // Well past the grace a byte stream would have been ended; a tool call is awaited until the deadline.
    HarnessHttp.await("grace over", 5, () -> System.nanoTime() - begun > TimeUnit.MILLISECONDS.toNanos(300));
    finish.countDown();
    var response = held.get(10, TimeUnit.SECONDS);
    stopping.get(10, TimeUnit.SECONDS);
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("done"), response.body());
    assertFalse(interrupted.get(), "Nothing interrupted the call before the deadline");
  }

  @Test void aSecondStopWaitsForTheFirst() throws Exception {
    var transport = start();
    callHeld(transport);
    var first = CompletableFuture.runAsync(transport::stop);
    HarnessHttp.await("stop begun", 5, transport::stopping);
    var second = new AtomicReference<Thread>();
    var secondStop = CompletableFuture.runAsync(() -> { second.set(Thread.currentThread()); transport.stop(); });
    HarnessHttp.await("second stop waiting", 5, () -> second.get() != null
        && java.util.Arrays.stream(second.get().getStackTrace()).anyMatch(f -> f.getMethodName().equals("awaitStopped")));
    assertFalse(secondStop.isDone(), "A second stop returns only when the first has finished");
    finish.countDown();
    first.get(10, TimeUnit.SECONDS);
    secondStop.get(10, TimeUnit.SECONDS);
  }

  @Test void pastItsDeadlineTheStopInterruptsWhatStillRunsAndEnds() throws Exception {
    var transport = start(t -> t.stopDeadline(Duration.ofMillis(300)));
    callHeld(transport);
    long began = System.nanoTime();
    CompletableFuture.runAsync(transport::stop).get(10, TimeUnit.SECONDS);
    assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(5), "The stop ends near its deadline");
    HarnessHttp.await("call interrupted", 5, interrupted::get);
  }

  @Test void theIdleExitStopsOnItsOwnThreadWithoutInterruptingItself() throws Exception {
    var ran = new CountDownLatch(1);
    var thread = new AtomicReference<String>();
    var stopInterrupted = new AtomicBoolean(true);
    var transportRef = new AtomicReference<HttpTransport>();
    start(t -> {
      transportRef.set(t);
      t.setIdleExit(Duration.ofMillis(40), () -> {
        transportRef.get().stop();
        thread.set(Thread.currentThread().getName());
        stopInterrupted.set(Thread.currentThread().isInterrupted());
        ran.countDown();
      });
    });
    assertTrue(ran.await(10, TimeUnit.SECONDS), "The idle exit ran");
    assertEquals("idle-exit", thread.get());
    assertFalse(stopInterrupted.get(), "Its stop shut down the session clock without interrupting its own waits");
  }

  @Test void aClosedWorkspaceIsNotBuiltAgainUntilATransportOpensIt() {
    var builds = new AtomicInteger();
    registry.workspaceBuild = identity -> { builds.incrementAndGet(); throw new IOException("no workspace in this test"); };
    registry.closeVisualizations();
    var closed = assertThrows(IOException.class, registry::visualizations);
    assertTrue(closed.getMessage().contains("closed"), closed.getMessage());
    assertEquals(0, builds.get(), "A request still running after the stop cannot build a new workspace");
    registry.openVisualizations();
    assertThrows(IOException.class, registry::visualizations);
    assertEquals(1, builds.get(), "A transport that starts serving may build it again");
  }

  @Test void aStreamingRequestIsCountedFromItsHandOffUntilItsWorkerFinishes() throws Exception {
    var admissions = new Admissions();
    var release = new CountDownLatch(1);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try (var streams = new StreamingRequests(1, Duration.ofSeconds(5), admissions)) {
      var running = new CountDownLatch(1);
      server.createContext("/held", streams.handle(exchange -> {
        running.countDown();
        try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        exchange.sendResponseHeaders(204, -1);
      }));
      server.start();
      var pending = client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/held")).build(),
          HttpResponse.BodyHandlers.discarding());
      assertTrue(running.await(5, TimeUnit.SECONDS));
      // The dispatcher has handed the exchange on and returned: only the hand-off's ticket counts it now.
      assertEquals(List.of("GET /held (0 s)"), admissions.awaitIdle(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50)));
      release.countDown();
      assertEquals(204, pending.get(5, TimeUnit.SECONDS).statusCode());
      assertEquals(List.of(), admissions.awaitIdle(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
    } finally {
      server.stop(0);
    }
  }

  // ---- helpers ----

  private HttpTransport start() throws IOException { return start(t -> {}); }

  private HttpTransport start(java.util.function.Consumer<HttpTransport> configure) throws IOException {
    var transport = new HttpTransport(registry, 0);
    configure.accept(transport);
    transport.start();
    started.add(transport);
    return transport;
  }

  /** Starts the held tool call and returns once it is running. */
  private CompletableFuture<HttpResponse<String>> callHeld(HttpTransport transport) throws Exception {
    var initialized = post(transport, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
    assertEquals(200, initialized.statusCode(), initialized.body());
    var session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
    var call = client.sendAsync(mcp(transport, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"held\",\"arguments\":{}}}", session)
        .build(), HttpResponse.BodyHandlers.ofString());
    assertTrue(entered.await(5, TimeUnit.SECONDS), "The tool call is running");
    return call;
  }

  private HttpResponse<String> post(HttpTransport transport, String body, String session) throws Exception {
    return send(transport, mcp(transport, body, session));
  }

  private HttpRequest.Builder mcp(HttpTransport transport, String body, String session) {
    var request = HttpRequest.newBuilder(uri(transport, "/mcp"))
        .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    return session == null ? request : request.header("Mcp-Session-Id", session);
  }

  private HttpResponse<String> send(HttpTransport transport, HttpRequest.Builder request) throws Exception {
    return client.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private static URI uri(HttpTransport transport, String path) {
    return URI.create("http://127.0.0.1:" + transport.getPort() + path);
  }

}
