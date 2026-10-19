/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.triplehelix.wpilogmcp.store.ReplicaCatalog;
import org.triplehelix.wpilogmcp.store.StoreDoor;
import org.triplehelix.wpilogmcp.store.StoreRegistry;

/** Explicit local master migration uses bounded jobs, never an HTTP worker waiting on hashing. */
final class StoreReplicaEndpoint implements AutoCloseable {
  static final String PATH = "/store/replicas";
  private final StoreRegistry stores;
  private final StoreDoor door;
  private final Map<UUID, Job> jobs = new LinkedHashMap<>();
  private boolean closed;
  private static final class Job {
    final UUID id = UUID.randomUUID();
    final String storeId;
    volatile CompletableFuture<ReplicaCatalog> operation;
    volatile Map<String, Object> response;
    Job(String storeId) {
      this.storeId = storeId; response = Map.of("job_id", id.toString(), "store_id", storeId, "state", "running");
    }
  }
  StoreReplicaEndpoint(StoreRegistry stores, StoreDoor door) { this.stores = stores; this.door = door; }

  void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    try {
      if (exchange.getRequestMethod().equals("GET") && path.startsWith(PATH + "/jobs/")) {
        var id = UUID.fromString(path.substring((PATH + "/jobs/").length())); Job job;
        synchronized (jobs) { job = jobs.get(id); }
        if (job == null) { StoreEndpoint.send(exchange, 404, Map.of("error", "Unknown migration job")); return; }
        StoreEndpoint.send(exchange, 200, job.response); return;
      }
      if (!path.equals(PATH + "/migrate")) { StoreEndpoint.send(exchange, 404, Map.of("error", "Unknown replica control")); return; }
      if (!exchange.getRequestMethod().equals("POST")) { StoreEndpoint.send(exchange, 405, Map.of("error", "POST required")); return; }
      byte[] bytes = RequestBodies.read(exchange, 65_536);
      if (bytes.length > 65_536) throw new IllegalArgumentException("Migration request exceeds 64 KiB");
      com.google.gson.JsonObject body;
      try { body = org.triplehelix.wpilogmcp.read.internal.ReadWire.parse(bytes); }
      catch (IOException invalid) { throw new IllegalArgumentException("Invalid migration JSON", invalid); }
      if (!body.keySet().equals(java.util.Set.of("store_id")) || !body.get("store_id").isJsonPrimitive()
          || !body.getAsJsonPrimitive("store_id").isString()) throw new IllegalArgumentException("store_id is required");
      String storeId = body.get("store_id").getAsString(); UUID.fromString(storeId);
      var selected = door.select(storeId);
      if (selected.description().mirror()) throw new IOException("Use the configured mirror's /store/mirror/migrate control so its capacity is enforced");
      var store = stores.store(selected.root()); var job = new Job(storeId);
      synchronized (jobs) {
        if (closed) throw new IOException("Replica controls are closed");
        if (jobs.size() >= 8) jobs.entrySet().removeIf(entry -> !entry.getValue().response.get("state").equals("running"));
        if (jobs.size() >= 8) throw new IOException("BUSY: migration job limit reached; retry");
        jobs.put(job.id, job);
      }
      var operation = store.migrateReplicas(storeId); job.operation = operation;
      boolean stopped; synchronized (jobs) { stopped = closed; }
      if (stopped) operation.cancel(false);
      operation.whenComplete((catalog, failure) -> {
        if (failure == null) job.response = Map.of("job_id", job.id.toString(), "store_id", storeId,
            "state", "complete", "format_version", 2, "catalog_epoch", catalog.epoch(),
            "catalog_revision", catalog.revision(), "files", catalog.files().size());
        else {
          String reason = java.util.Objects.toString(failure.getMessage(), "Migration cancelled");
          job.response = Map.of("job_id", job.id.toString(), "store_id", storeId, "state", "error",
              "error", reason.length() > 2048 ? reason.substring(0, 2048) : reason);
        }
      });
      StoreEndpoint.send(exchange, 202, job.response);
    } catch (IllegalArgumentException failure) {
      StoreEndpoint.send(exchange, 400, Map.of("error", "Invalid replica request"));
    } catch (IOException failure) { StoreEndpoint.send(exchange, 409, Map.of("error", failure.getMessage())); }
  }

  @Override public void close() {
    java.util.List<Job> retiring;
    synchronized (jobs) { closed = true; retiring = java.util.List.copyOf(jobs.values()); jobs.clear(); }
    // Cancellation can run callbacks, so it must not inherit the jobs monitor.
    for (var job : retiring) { var operation = job.operation; if (operation != null) operation.cancel(false); }
  }
}
