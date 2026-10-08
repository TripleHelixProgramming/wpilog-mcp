/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.harness;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Ordinary MCP HTTP requests, including initialization and the session header. */
public final class HarnessHttp {
  private final URI endpoint;
  private final HttpClient http = HttpClient.newHttpClient();
  private String session;
  private int id;
  public HarnessHttp(int port) { endpoint = URI.create("http://127.0.0.1:" + port + "/mcp"); }
  public void initialize() throws Exception { request("initialize", new JsonObject()); }
  public JsonObject call(String tool, JsonObject arguments) throws Exception {
    var params = new JsonObject(); params.addProperty("name", tool); params.add("arguments", arguments);
    var reply = request("tools/call", params);
    return JsonParser.parseString(reply.getAsJsonObject("result").getAsJsonArray("content")
        .get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject();
  }
  private JsonObject request(String method, JsonObject params) throws Exception {
    var body = new JsonObject(); body.addProperty("jsonrpc", "2.0"); body.addProperty("id", ++id);
    body.addProperty("method", method); body.add("params", params);
    var builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
    if (session != null) builder.header("Mcp-Session-Id", session);
    var reply = http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
    if (reply.statusCode() != 200) throw new java.io.IOException("HTTP " + reply.statusCode() + ": " + reply.body());
    session = reply.headers().firstValue("Mcp-Session-Id").orElse(session);
    var result = JsonParser.parseString(reply.body()).getAsJsonObject();
    if (result.has("error")) throw new java.io.IOException(result.toString());
    return result;
  }
  @FunctionalInterface public interface Condition { boolean ready() throws Exception; }
  /** A long replay is bounded by missing receipt progress, not by the source file's size. */
  public static void awaitProgress(String what, int seconds, java.util.function.LongSupplier progress,
      Condition condition) throws Exception {
    awaitProgress(what, seconds, progress, System::nanoTime, condition);
  }

  static void awaitProgress(String what, int seconds, java.util.function.LongSupplier progress,
      java.util.function.LongSupplier clock, Condition condition) throws Exception {
    var done = new CompletableFuture<Void>();
    var last = new java.util.concurrent.atomic.AtomicReference<Exception>();
    long[] observed = {progress.getAsLong(), clock.getAsLong()};
    var poller = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "harness-progress"); t.setDaemon(true); return t; });
    try {
      poller.scheduleWithFixedDelay(() -> {
        try {
          if (condition.ready()) { done.complete(null); return; }
        } catch (Exception e) { last.set(e); }
        catch (Error e) { done.completeExceptionally(e); }
        long count = progress.getAsLong(), now = clock.getAsLong();
        if (count > observed[0]) { observed[0] = count; observed[1] = now; }
        if (now - observed[1] >= TimeUnit.SECONDS.toNanos(seconds)) {
          done.completeExceptionally(new AssertionError("Timed out waiting for " + what, last.get()));
        }
      }, 0, 50, TimeUnit.MILLISECONDS);
      done.get();
    } finally { poller.shutdownNow(); }
  }

  /** Wall-time bounds for processes/sockets only; no assertion advances a simulated clock by sleeping. */
  public static void await(String what, int seconds, Condition condition) throws Exception {
    var done = new CompletableFuture<Void>();
    var last = new java.util.concurrent.atomic.AtomicReference<Exception>();
    var poller = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "harness-await"); t.setDaemon(true); return t; });
    try {
      poller.scheduleWithFixedDelay(() -> {
        try { if (condition.ready()) done.complete(null); }
        catch (Exception e) { last.set(e); }
        catch (Error e) { done.completeExceptionally(e); }
      }, 0, 50, TimeUnit.MILLISECONDS);
      try { done.get(seconds, TimeUnit.SECONDS); }
      catch (java.util.concurrent.TimeoutException e) { throw new AssertionError("Timed out waiting for " + what, last.get()); }
    } finally { poller.shutdownNow(); }
  }
}
