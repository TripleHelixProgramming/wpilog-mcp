/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import com.google.gson.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.triplehelix.wpilogmcp.mcp.CallerContext.Authority;

/** Local working-view owner. Public adapters are introduced after the Stage 1 lifecycle review.
 * The bounded worker is independent of acquisition and imports. Reads use immutable snapshots.
 * Persistent instances hold the state lock until admitted work drains, including during close. */
public final class VisualizationService implements AutoCloseable {
  public static final int MAX_DOCUMENTS = 256;
  public static final long MAX_STATE_BYTES = 256L * 1024 * 1024;
  public static final Duration DRAFT_LIFETIME = Duration.ofDays(30);
  private final StateFiles files;
  private final FileChannel lock;
  private final Clock clock;
  private final int documentLimit;
  private final long byteLimit;
  private final ConcurrentMap<UUID, JsonObject> views = new ConcurrentHashMap<>();
  private final ThreadPoolExecutor queue = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(64), task -> { var t = new Thread(task, "visualization-state"); t.setDaemon(true); return t; });
  private volatile boolean closed;

  public VisualizationService(ServerIdentity identity) throws IOException { this(identity, Clock.systemUTC(), MAX_DOCUMENTS, MAX_STATE_BYTES); }
  VisualizationService(ServerIdentity identity, Clock clock, int documents, long bytes) throws IOException {
    this.clock = clock; documentLimit = documents; byteLimit = bytes;
    files = identity.durable() ? new StateFiles(identity.stateDirectory()) : null;
    lock = files == null ? null : files.lock();
    try {
      if (files != null && Files.exists(files.path("views"))) {
        try (var children = Files.list(files.path("views"))) {
          long loadedBytes = 4096;
          var iterator = children.iterator();
          while (iterator.hasNext()) {
            var path = iterator.next();
            var name = path.getFileName().toString();
            if (name.startsWith(".pending-") && name.endsWith(".tmp")) { Files.delete(files.path("views/" + name)); continue; }
            UUID id; JsonObject record;
            try {
              if (!name.endsWith(".json")) throw new IOException("Unexpected working-state file");
              id = UUID.fromString(name.substring(0, name.length() - 5));
              record = JsonParser.parseString(files.read("views/" + name, 4L * 1024 * 1024)).getAsJsonObject();
              validateRecord(id, record);
            } catch (IOException | RuntimeException invalid) {
              files.quarantineView(name);
              org.slf4j.LoggerFactory.getLogger(VisualizationService.class).warn(
                  "Quarantined visualization working record {}: {}", name, invalid.getMessage());
              continue;
            }
            if (expired(record)) { Files.delete(files.path("views/" + name)); continue; }
            // A valid record over a lowered quota stays in place; it is not corrupt.
            if (views.size() >= documentLimit || (loadedBytes += size(record)) > byteLimit) throw new IOException("WORKSPACE_LIMIT: existing state exceeds its configured quota");
            // A process restart ends all old window attachments, never the recoverable draft.
            if (!record.get("attached_client_id").isJsonNull()) {
              record.add("attached_client_id", JsonNull.INSTANCE);
              record.addProperty("detached_expires_at", clock.instant().plus(DRAFT_LIFETIME).toString());
              files.write("views/" + name, record.toString());
            }
            views.put(id, record);
          }
        }
      }
      prune(); checkBudget(null, null);
    } catch (Exception e) {
      if (lock != null) lock.close(); queue.shutdownNow();
      throw new IOException("Cannot recover visualization state; records retained: " + e.getMessage(), e);
    }
  }

  private static void validateRecord(UUID id, JsonObject record) throws IOException {
    if (record.get("schema_version").getAsInt() != 1 || !id.toString().equals(record.get("view_id").getAsString())) throw new IOException("Invalid working-state identity");
    if (!record.keySet().equals(Set.of("schema_version", "view_id", "creation_request_id", "revision", "source_ref", "configuration", "initial_configuration", "checkpoint_configuration", "saved_view_ref", "attached_client_id", "detached_expires_at"))) throw new IOException("Unknown working-state fields");
    long revision = record.get("revision").getAsLong();
    if (revision < 1 || revision > 9_007_199_254_740_991L || revision != record.get("revision").getAsDouble()) throw new IOException("Invalid working revision");
    UUID.fromString(record.get("creation_request_id").getAsString());
    VisualizationContracts.configuration(record.getAsJsonObject("initial_configuration"));
    VisualizationContracts.source(record.getAsJsonObject("source_ref"));
    VisualizationContracts.configuration(record.getAsJsonObject("configuration"));
    if (!record.get("checkpoint_configuration").isJsonNull()) VisualizationContracts.configuration(record.getAsJsonObject("checkpoint_configuration"));
    if (!record.get("attached_client_id").isJsonNull()) UUID.fromString(record.get("attached_client_id").getAsString());
    if (!record.get("detached_expires_at").isJsonNull()) Instant.parse(record.get("detached_expires_at").getAsString());
  }

  public CompletableFuture<JsonObject> create(Authority caller, UUID request, JsonObject source, JsonObject configuration) {
    var sourceCopy = source.deepCopy(); var configCopy = configuration.deepCopy();
    return submit(caller, () -> {
      var validSource = VisualizationContracts.source(sourceCopy);
      var validConfig = VisualizationContracts.configuration(configCopy);
      for (var record : views.values()) if (request.toString().equals(record.get("creation_request_id").getAsString())) {
        if (!validSource.equals(record.get("source_ref")) || !validConfig.equals(record.get("initial_configuration"))) throw new IOException("REQUEST_CONFLICT: create request reused with different content");
        return record;
      }
      prune();
      var id = UUID.randomUUID(); var record = new JsonObject();
      record.addProperty("schema_version", 1); record.addProperty("view_id", id.toString());
      record.addProperty("creation_request_id", request.toString()); record.addProperty("revision", 1);
      record.add("source_ref", validSource); record.add("configuration", validConfig);
      record.add("initial_configuration", validConfig.deepCopy());
      record.add("checkpoint_configuration", JsonNull.INSTANCE); record.add("saved_view_ref", JsonNull.INSTANCE);
      record.add("attached_client_id", JsonNull.INSTANCE);
      record.addProperty("detached_expires_at", clock.instant().plus(DRAFT_LIFETIME).toString());
      write(id, record); return record;
    });
  }

  public CompletableFuture<JsonObject> update(Authority caller, UUID view, long revision, JsonObject configuration) {
    var copy = configuration.deepCopy();
    return submit(caller, () -> {
      var config = VisualizationContracts.configuration(copy); var record = checked(view, revision);
      record.add("configuration", config); next(record); write(view, record); return record;
    });
  }

  /** Called only after the store has acknowledged its own CAS. A concurrent edit stays dirty. */
  public CompletableFuture<JsonObject> checkpoint(Authority caller, UUID view, long revision, JsonObject savedReference) {
    var ref = savedReference.deepCopy();
    return submit(caller, () -> {
      if (!ref.keySet().equals(Set.of("store_id", "saved_view_id", "revision"))) throw new IOException("Invalid saved-view reference");
      UUID.fromString(ref.get("store_id").getAsString()); UUID.fromString(ref.get("saved_view_id").getAsString());
      long savedRevision = ref.get("revision").getAsLong();
      if (savedRevision < 1 || savedRevision > 9_007_199_254_740_991L || ref.get("revision").getAsDouble() != savedRevision) throw new IOException("Invalid saved revision");
      var record = checked(view, revision);
      record.add("saved_view_ref", ref); record.add("checkpoint_configuration", record.get("configuration").deepCopy());
      next(record); write(view, record); return record;
    });
  }

  public CompletableFuture<JsonObject> attach(Authority caller, UUID view, UUID client) {
    return submit(caller, () -> {
      var record = raw(view);
      if (!record.get("attached_client_id").isJsonNull() && !client.toString().equals(record.get("attached_client_id").getAsString())) throw new IOException("VIEW_ATTACHED: reveal the owning window or open a copy");
      record.addProperty("attached_client_id", client.toString()); record.add("detached_expires_at", JsonNull.INSTANCE);
      write(view, record); return record;
    });
  }
  public CompletableFuture<JsonObject> detach(Authority caller, UUID view, UUID client) {
    return submit(caller, () -> {
      var record = raw(view);
      if (record.get("attached_client_id").isJsonNull() || !client.toString().equals(record.get("attached_client_id").getAsString())) throw new IOException("VIEW_ATTACHED: this window does not own the attachment");
      record.add("attached_client_id", JsonNull.INSTANCE); record.addProperty("detached_expires_at", clock.instant().plus(DRAFT_LIFETIME).toString());
      write(view, record); return record;
    });
  }

  public JsonObject get(UUID view) throws IOException { return describe(raw(view)); }
  public List<JsonObject> list(JsonObject source) throws IOException {
    var checked = VisualizationContracts.source(source);
    return views.values().stream().filter(r -> !expired(r) && checked.equals(r.get("source_ref")))
        .sorted(Comparator.comparing(r -> r.get("view_id").getAsString())).map(this::describe).toList();
  }
  private JsonObject raw(UUID id) throws IOException {
    var record = views.get(id);
    if (record == null || expired(record)) throw new IOException("VIEW_UNAVAILABLE: draft is missing or expired");
    return record.deepCopy();
  }
  private JsonObject checked(UUID id, long revision) throws IOException {
    var record = raw(id);
    if (revision != record.get("revision").getAsLong()) throw new IOException("REVISION_CONFLICT: current revision is " + record.get("revision"));
    return record;
  }
  private static void next(JsonObject record) throws IOException {
    long revision = record.get("revision").getAsLong();
    if (revision >= 9_007_199_254_740_991L) throw new IOException("WORKSPACE_LIMIT: revision exhausted");
    record.addProperty("revision", revision + 1);
  }
  private JsonObject describe(JsonObject original) {
    var record = original.deepCopy();
    boolean unsaved = record.get("checkpoint_configuration").isJsonNull() || !record.get("configuration").equals(record.get("checkpoint_configuration"));
    record.addProperty("has_unsaved_changes", unsaved);
    record.addProperty("native_dirty", unsaved && !record.get("saved_view_ref").isJsonNull());
    return record;
  }
  private boolean expired(JsonObject record) {
    return record.get("attached_client_id").isJsonNull() && !record.get("detached_expires_at").isJsonNull()
        && !Instant.parse(record.get("detached_expires_at").getAsString()).isAfter(clock.instant());
  }
  private void prune() throws IOException {
    for (var entry : views.entrySet()) if (expired(entry.getValue())) {
      if (files != null) Files.deleteIfExists(files.path("views/" + entry.getKey() + ".json"));
      views.remove(entry.getKey());
    }
  }
  private void checkBudget(UUID replacing, JsonObject record) throws IOException {
    prune();
    long total = record == null ? 0 : size(record);
    for (var entry : views.entrySet()) if (!entry.getKey().equals(replacing)) total += size(entry.getValue());
    int count = views.size() + (record != null && !views.containsKey(replacing) ? 1 : 0);
    // Reserve space for identity and the lock; temporary replacement uses at most one extra record.
    if (count > documentLimit || total + 4096 > byteLimit) throw new IOException("WORKSPACE_LIMIT: save or remove a view; unexpired drafts were retained");
  }
  private static int size(JsonObject record) { return record.toString().getBytes(StandardCharsets.UTF_8).length; }
  private void write(UUID id, JsonObject record) throws IOException {
    checkBudget(id, record);
    if (files != null) files.write("views/" + id + ".json", record.toString());
    views.put(id, record.deepCopy());
  }
  @FunctionalInterface private interface Operation { JsonObject run() throws Exception; }
  private CompletableFuture<JsonObject> submit(Authority caller, Operation operation) {
    var result = new CompletableFuture<JsonObject>();
    try {
      Objects.requireNonNullElse(caller, Authority.UNTRUSTED).requireLocal();
      if (closed) throw new IOException("WORKSPACE_CLOSED");
      queue.execute(() -> {
        try { result.complete(describe(operation.run())); }
        catch (Exception failure) { result.completeExceptionally(failure); }
      });
    } catch (Exception failure) { result.completeExceptionally(failure); }
    return result;
  }
  @Override public void close() {
    closed = true;
    // Admission and shutdown are serialized by the executor. A rejected cleanup runs after termination.
    queue.shutdown();
    var closer = new Thread(() -> {
      try { while (!queue.awaitTermination(1, TimeUnit.SECONDS)) { /* bounded queue drains */ } }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      finally { if (lock != null) try { lock.close(); } catch (IOException ignored) { /* shutdown */ } }
    }, "visualization-close");
    closer.setDaemon(true); closer.start();
    try { closer.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
  }
}
