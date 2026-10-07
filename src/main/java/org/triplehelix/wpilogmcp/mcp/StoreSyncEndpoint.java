/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.store.StoreDoor;
import org.triplehelix.wpilogmcp.store.StoreRegistry;
import org.triplehelix.wpilogmcp.store.StoreSync;

/** The loopback command submits a store job; network peers can only read the store door. */
final class StoreSyncEndpoint {
  static final String PATH = "/store/sync";
  private static final int CAPACITY = 100;
  private record View(String jobId, String state, StoreSync.Progress progress, StoreSync.Result result, String error) {}
  private static final class Job {
    volatile View view;
    Job() { view = new View(UUID.randomUUID().toString(), "queued", null, null, null); }
    boolean active() { return view.state().equals("queued") || view.state().equals("running"); }
    void progress(StoreSync.Progress value) { view = new View(view.jobId(), "running", value, null, null); }
  }
  private final StoreRegistry stores;
  private final StoreDoor door;
  private final Supplier<Set<Path>> configured;
  private final Map<String, Job> jobs = new LinkedHashMap<>();
  StoreSyncEndpoint(StoreRegistry stores, StoreDoor door, Supplier<Set<Path>> configured) {
    this.stores = stores; this.door = door; this.configured = configured;
  }
  synchronized boolean active() { return jobs.values().stream().anyMatch(Job::active); }
  void handle(HttpExchange exchange) throws IOException {
    String route = exchange.getRequestURI().getPath();
    if (route.equals(PATH) && exchange.getRequestMethod().equals("GET")) {
      var inventory = door.inventory(); var targets = new java.util.ArrayList<Map<String, Object>>();
      var unreadable = new java.util.ArrayList<>(inventory.unreadable());
      for (var selected : inventory.stores()) if (!selected.description().mirror()) {
        try {
          var peers = door.peers(selected);
          targets.add(Map.of("path", selected.root().toString(), "id", selected.description().id(), "peers", peers));
        } catch (IOException | RuntimeException e) { unreadable.add(new StoreDoor.Unreadable(selected.root().toString(), e.getMessage())); }
      }
      StoreEndpoint.send(exchange, 200, Map.of("stores", targets, "unreadable", unreadable)); return;
    }
    if (exchange.getRequestMethod().equals("GET") && route.startsWith(PATH + "/")) {
      Job job; synchronized (this) { job = jobs.get(route.substring(PATH.length() + 1)); }
      StoreEndpoint.send(exchange, job == null ? 404 : 200, job == null ? Map.of("error", "Unknown sync job") : job.view); return;
    }
    if (!route.equals(PATH) || !exchange.getRequestMethod().equals("POST")) {
      StoreEndpoint.send(exchange, 405, Map.of("error", "POST /store/sync; GET /store/sync/<job>")); return;
    }
    JsonObject body; Path root; String url; long rate;
    try {
      var bytes = exchange.getRequestBody().readNBytes(65_537);
      if (bytes.length > 65_536) throw new IllegalArgumentException("Sync body exceeds 64 KiB");
      body = new GsonBuilder().setStrictness(Strictness.STRICT).create().fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
      if (body == null) throw new IllegalArgumentException("Expected a JSON object");
      url = string(body, "url");
      if (url != null) new org.triplehelix.wpilogmcp.sync.HttpRemoteFiles(url); // Validate before accepting a job.
      rate = 0;
      if (body.has("rate_bytes")) {
        var value = body.get("rate_bytes");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("rate_bytes must be an integer");
        try { rate = value.getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("rate_bytes must be an integer within the 64-bit range", e); }
        if (rate < 0) throw new IllegalArgumentException("rate_bytes must be nonnegative; 0 is unlimited");
      }
      String selected = string(body, "store");
      if (selected != null) root = Path.of(selected);
      else {
        var found = door.stores().stream().filter(s -> !s.description().mirror()).toList();
        if (found.size() == 1) root = found.get(0).root();
        else if (found.isEmpty() && configured.get().size() == 1) root = configured.get().iterator().next();
        else throw new IllegalArgumentException("store is required when there is not one configured destination");
      }
    } catch (RuntimeException | IOException e) { StoreEndpoint.send(exchange, 400, Map.of("error", "Invalid sync body: " + e.getMessage())); return; }
    try {
      var roots = configured.get(); if (roots.isEmpty()) throw new IOException("Sync requires a configured store directory");
      var security = new SecurityValidator(); roots.forEach(security::addAllowedDirectory); security.validate(root);
      var store = stores.store(root);
      Job job;
      synchronized (this) {
        if (jobs.size() >= CAPACITY) jobs.entrySet().stream().filter(e -> !e.getValue().active()).findFirst().ifPresent(e -> jobs.remove(e.getKey()));
        job = jobs.size() < CAPACITY ? new Job() : null;
        if (job != null) jobs.put(job.view.jobId(), job);
      }
      if (job == null) { StoreEndpoint.send(exchange, 503, Map.of("error", "Sync job history is full of active jobs; retry later")); return; }
      var future = store.sync(url, rate, job::progress);
      if (future.isCompletedExceptionally()) {
        try { future.join(); } catch (java.util.concurrent.CompletionException e) {
          synchronized (this) { jobs.remove(job.view.jobId()); }
          StoreEndpoint.send(exchange, 409, Map.of("error", e.getCause().getMessage())); return;
        }
      }
      future.whenComplete((result, error) -> {
        job.view = new View(job.view.jobId(), error == null ? "done" : "failed", job.view.progress(), result, error == null ? null : error.getMessage());
        org.slf4j.LoggerFactory.getLogger(StoreSyncEndpoint.class).info("Store sync job {}: {}", job.view.jobId(), job.view.state());
      });
      String location = PATH + "/" + job.view.jobId(); exchange.getResponseHeaders().set("Location", location);
      StoreEndpoint.send(exchange, 202, Map.of("job_id", job.view.jobId(), "url", location));
    } catch (IOException e) { StoreEndpoint.send(exchange, 403, Map.of("error", e.getMessage())); }
  }
  private static String string(JsonObject body, String key) {
    var value = body.get(key); if (value == null || value.isJsonNull()) return null;
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) throw new IllegalArgumentException(key + " must be a nonempty string");
    return value.getAsString();
  }
}
