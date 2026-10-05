/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.store.LogStore;
import org.triplehelix.wpilogmcp.store.StoreJson;
import org.triplehelix.wpilogmcp.store.StoreRegistry;

/**
 * A job wraps the existing store queue, never another executor. Active jobs cannot be evicted:
 * when all history slots are active, refuse admission so polling never loses accepted work.
 */
final class StoreImportEndpoint {
  static final String PATH = "/store/import";
  static final int HISTORY_SIZE = 100;
  private static final int MAX_BODY_BYTES = 1024 * 1024;
  private static final com.google.gson.Gson BODY_JSON = new com.google.gson.GsonBuilder()
      .setStrictness(com.google.gson.Strictness.STRICT).create();
  private static final String INBOX_HINT = "Copy outside files into the store's inbox/ folder; "
      + "inbox/imported.log records the result. Configure a log directory first if none is set.";
  private record Request(Path store, LogStore.Request request) {}
  private record View(String jobId, String state, LogStore.Progress progress,
      LogStore.Result result, String error) {}
  private static final class Job {
    volatile View view;
    Job(String id) { view = new View(id, "queued", null, null, null); }
    void progress(LogStore.Progress progress) {
      view = new View(view.jobId(), "running", progress, null, null);
    }
    boolean active() { return view.state().equals("queued") || view.state().equals("running"); }
  }

  private final StoreRegistry stores;
  private final int capacity;
  private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();

  StoreImportEndpoint(StoreRegistry stores) { this(stores, HISTORY_SIZE); }

  StoreImportEndpoint(StoreRegistry stores, int capacity) {
    this.stores = stores;
    this.capacity = capacity;
  }

  synchronized boolean active() {
    return jobs.values().stream().anyMatch(Job::active);
  }

  void handle(HttpExchange exchange) throws IOException {
    var path = exchange.getRequestURI().getPath();
    if (path.equals(PATH) && exchange.getRequestMethod().equals("POST")) {
      submit(exchange);
    } else if (path.startsWith(PATH + "/") && exchange.getRequestMethod().equals("GET")) {
      Job job;
      synchronized (this) { job = jobs.get(path.substring(PATH.length() + 1)); }
      if (job == null) refuse(exchange, 404, "Unknown import job", "Jobs expire from memory and are lost at restart");
      else send(exchange, 200, StoreJson.JSON.toJsonTree(job.view));
    } else {
      refuse(exchange, path.equals(PATH) || path.startsWith(PATH + "/") ? 405 : 404,
          "No such import route or method", "POST /store/import; GET /store/import/<job>");
    }
  }

  private void submit(HttpExchange exchange) throws IOException {
    Request request;
    try {
      byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
      if (bytes.length > MAX_BODY_BYTES) throw new IllegalArgumentException("Import body exceeds 1 MiB");
      request = parse(new String(bytes, StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      refuse(exchange, 400, "Malformed import body", "Expected store (directory), paths (string array), "
          + "move (boolean), and optional stated_robot (name or null); at most 1 MiB");
      return;
    }
    try {
      stores.validate(request.store());
      for (var path : request.request().paths()) stores.validate(path);
    } catch (IOException e) {
      refuse(exchange, 403, "Import paths must be inside configured log directories", INBOX_HINT);
      return;
    }
    LogStore store;
    try {
      store = stores.store(request.store());
    } catch (IOException | IllegalArgumentException e) {
      refuse(exchange, 400, "Store directory cannot be opened", "Choose a writable directory inside configured log directories");
      return;
    }
    Job job;
    synchronized (this) {
      if (jobs.size() >= capacity) {
        var oldest = jobs.entrySet().stream().filter(e -> !e.getValue().active()).findFirst();
        if (oldest.isPresent()) jobs.remove(oldest.get().getKey());
      }
      job = jobs.size() < capacity ? new Job(UUID.randomUUID().toString()) : null;
      if (job != null) jobs.put(job.view.jobId(), job);
    }
    if (job == null) {
      exchange.getResponseHeaders().set("Retry-After", "3");
      refuse(exchange, 503, "Import job queue is full", "Wait for a running job to finish, then retry");
      return;
    }
    try {
      store.importPaths(request.request(), job::progress).whenComplete((result, error) -> {
        job.view = new View(job.view.jobId(), error == null ? "done" : "failed",
            job.view.progress(), result, error == null ? null : error.getMessage());
        LoggerFactory.getLogger(StoreImportEndpoint.class).info("Import job {}: {}",
            job.view.jobId(), job.view.state());
      });
    } catch (RuntimeException e) {
      job.view = new View(job.view.jobId(), "failed", null, null, "Import queue is closed");
    }
    var body = new JsonObject();
    body.addProperty("job_id", job.view.jobId());
    body.addProperty("url", PATH + "/" + job.view.jobId());
    exchange.getResponseHeaders().set("Location", body.get("url").getAsString());
    send(exchange, 202, body);
  }

  private static Request parse(String body) {
    var json = BODY_JSON.fromJson(body, JsonObject.class);
    var store = Path.of(string(json.get("store")));
    var move = json.get("move");
    if (move == null || !move.isJsonPrimitive() || !move.getAsJsonPrimitive().isBoolean()) {
      throw new IllegalArgumentException("move must be a boolean");
    }
    var paths = new ArrayList<Path>();
    for (var path : json.getAsJsonArray("paths")) paths.add(Path.of(string(path)));
    var robot = json.get("stated_robot");
    return new Request(store, new LogStore.Request(paths, move.getAsBoolean(),
        robot == null || robot.isJsonNull() ? null : string(robot)));
  }

  private static String string(JsonElement value) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
        || value.getAsString().isBlank()) throw new IllegalArgumentException("Expected nonempty string");
    return value.getAsString();
  }

  private static void refuse(HttpExchange exchange, int status, String error, String hint) throws IOException {
    var body = new JsonObject();
    body.addProperty("error", error);
    body.addProperty("hint", hint);
    send(exchange, status, body);
  }

  private static void send(HttpExchange exchange, int status, JsonElement body) throws IOException {
    byte[] bytes = StoreJson.JSON.toJson(body).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var out = exchange.getResponseBody()) { out.write(bytes); }
  }
}
