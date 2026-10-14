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

/** Local working-view owner, shared by the recorded preview and later tool adapters.
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
  private final ConcurrentMap<UUID, JsonObject> locators = new ConcurrentHashMap<>();
  private final Set<UUID> endedClients = ConcurrentHashMap.newKeySet();

  public VisualizationService(ServerIdentity identity) throws IOException { this(identity, Clock.systemUTC(), MAX_DOCUMENTS, MAX_STATE_BYTES); }
  VisualizationService(ServerIdentity identity, Clock clock, int documents, long bytes) throws IOException {
    this.clock = clock; documentLimit = documents; byteLimit = bytes;
    files = identity.durable() ? new StateFiles(identity.stateDirectory()) : null;
    lock = files == null ? null : files.lock();
    try {
      if (files != null && Files.exists(files.path("sources.json"))) {
        var saved = VisualizationContracts.parse(files.read("sources.json", 1024 * 1024));
        for (var entry : saved.entrySet()) locators.put(UUID.fromString(entry.getKey()), entry.getValue().getAsJsonObject());
      }
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
      prune(); pruneLocators(null, null); checkBudget(null, null);
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
    return updateAttached(caller, view, revision, configuration, null);
  }
  public CompletableFuture<JsonObject> updateAttached(Authority caller, UUID view, long revision, JsonObject configuration, UUID client) {
    var copy = configuration.deepCopy();
    return submit(caller, () -> {
      var config = VisualizationContracts.configuration(copy); var record = checked(view, revision);
      requireOwner(record, client);
      record.add("configuration", config); next(record); write(view, record); return record;
    });
  }

  /** Called only after the store has acknowledged its own CAS. A concurrent edit stays dirty. */
  public CompletableFuture<JsonObject> checkpoint(Authority caller, UUID view, long revision, JsonObject savedReference) {
    return checkpointAttached(caller, view, revision, savedReference, null);
  }
  public CompletableFuture<JsonObject> checkpointAttached(Authority caller, UUID view, long revision, JsonObject savedReference, UUID client) {
    var ref = savedReference.deepCopy();
    return submit(caller, () -> {
      if (!ref.keySet().equals(Set.of("store_id", "saved_view_id", "revision"))) throw new IOException("Invalid saved-view reference");
      UUID.fromString(ref.get("store_id").getAsString()); UUID.fromString(ref.get("saved_view_id").getAsString());
      long savedRevision = ref.get("revision").getAsLong();
      if (savedRevision < 1 || savedRevision > 9_007_199_254_740_991L || ref.get("revision").getAsDouble() != savedRevision) throw new IOException("Invalid saved revision");
      var record = checked(view, revision); requireOwner(record, client);
      record.add("saved_view_ref", ref); record.add("checkpoint_configuration", record.get("configuration").deepCopy());
      next(record); write(view, record); return record;
    });
  }

  public CompletableFuture<JsonObject> attach(Authority caller, UUID view, UUID client) {
    return attach(caller, view, client, () -> true);
  }
  public CompletableFuture<JsonObject> attach(Authority caller, UUID view, UUID client, java.util.function.BooleanSupplier active) {
    return submit(caller, () -> {
      if (!active.getAsBoolean()) throw new IOException("SESSION_EXPIRED: the session ended while attachment was queued");
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

  public static void requireOwner(JsonObject record, UUID client) throws IOException {
    if (client != null && (record.get("attached_client_id").isJsonNull()
        || !client.toString().equals(record.get("attached_client_id").getAsString())))
      throw new IOException("VIEW_ATTACHED: this session does not own the view");
  }

  public JsonObject get(UUID view) throws IOException { return describe(raw(view)); }
  public List<JsonObject> list(JsonObject source) throws IOException {
    var checked = VisualizationContracts.source(source);
    return views.values().stream().filter(r -> !expired(r) && checked.equals(r.get("source_ref")))
        .sorted(Comparator.comparing(r -> r.get("view_id").getAsString())).map(this::describe).toList();
  }
  /** Navigation never copies configurations or recovery checkpoints into the response. */
  public List<JsonObject> summaries(JsonObject source) throws IOException {
    var checked = VisualizationContracts.source(source);
    return views.values().stream().filter(r -> !expired(r) && checked.equals(r.get("source_ref")))
        .sorted(Comparator.comparing(r -> r.get("view_id").getAsString())).map(r -> {
          var summary = new JsonObject();
          for (var key : List.of("view_id", "revision", "saved_view_ref", "attached_client_id", "detached_expires_at")) summary.add(key, r.get(key).deepCopy());
          return summary;
        }).toList();
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
    var expiredSources = new HashSet<UUID>();
    for (var entry : views.entrySet()) if (expired(entry.getValue())) {
      if (files != null) Files.deleteIfExists(files.path("views/" + entry.getKey() + ".json"));
      var source = entry.getValue().getAsJsonObject("source_ref");
      if (source.get("kind").getAsString().equals("unmanaged")) expiredSources.add(UUID.fromString(source.get("source_id").getAsString()));
      views.remove(entry.getKey());
    }
    if (!expiredSources.isEmpty()) pruneLocators(null, expiredSources);
  }
  private JsonObject locatorRecords() {
    var all = new JsonObject(); locators.forEach((key, value) -> all.add(key.toString(), value)); return all;
  }
  private void retainReferencedLocators(JsonObject all, UUID keep, Set<UUID> candidates) {
    var referenced = new HashSet<String>();
    for (var record : views.values()) {
      var source = record.getAsJsonObject("source_ref");
      if (source.get("kind").getAsString().equals("unmanaged")) referenced.add(source.get("source_id").getAsString());
    }
    if (keep != null) referenced.add(keep.toString());
    all.entrySet().removeIf(entry -> !referenced.contains(entry.getKey())
        && (candidates == null || candidates.contains(UUID.fromString(entry.getKey()))));
  }
  private void writeLocators(JsonObject all) throws IOException {
    // Persist before publishing eviction; a failed atomic write keeps the old locators available.
    if (files != null) files.write("sources.json", all.toString());
    all.entrySet().forEach(entry -> locators.put(UUID.fromString(entry.getKey()), entry.getValue().getAsJsonObject()));
    locators.keySet().removeIf(id -> !all.has(id.toString()));
  }
  private void pruneLocators(UUID keep, Set<UUID> candidates) throws IOException {
    var all = locatorRecords(); retainReferencedLocators(all, keep, candidates);
    if (all.size() != locators.size()) writeLocators(all);
  }
  private void checkBudget(UUID replacing, JsonObject record) throws IOException {
    prune();
    long total = record == null ? 0 : size(record);
    for (var entry : views.entrySet()) if (!entry.getKey().equals(replacing)) total += size(entry.getValue());
    int count = views.size() + (record != null && !views.containsKey(replacing) ? 1 : 0);
    // Keep recent identifications until pressure: two overviews can identify A/B before either creates a view.
    if (total + 4096 + size(locatorRecords()) > byteLimit) {
      var source = record == null ? null : record.getAsJsonObject("source_ref");
      var keep = source != null && source.get("kind").getAsString().equals("unmanaged") ? UUID.fromString(source.get("source_id").getAsString()) : null;
      pruneLocators(keep, null);
    }
    // Reserve space for identity and the lock; temporary replacement uses at most one extra record.
    if (count > documentLimit || total + 4096 + locators.values().stream().mapToLong(VisualizationService::size).sum() > byteLimit) throw new IOException("WORKSPACE_LIMIT: save or remove a view; unexpired drafts were retained");
  }
  private static int size(JsonObject record) { return record.toString().getBytes(StandardCharsets.UTF_8).length; }
  private void write(UUID id, JsonObject record) throws IOException {
    checkBudget(id, record);
    if (files != null) files.write("views/" + id + ".json", record.toString());
    views.put(id, record.deepCopy());
  }
  /** Locators are owner-local recovery metadata, never part of a portable SourceRef. */
  public CompletableFuture<JsonObject> rememberSource(Authority caller, UUID id, JsonObject locator) {
    var copy = locator.deepCopy();
    return enqueue(caller, () -> {
      prune();
      var all = locatorRecords(); all.add(id.toString(), copy);
      long workingBytes = 4096L + views.values().stream().mapToLong(VisualizationService::size).sum();
      if (all.size() > 1024 || size(all) > 1024 * 1024 || workingBytes + size(all) > byteLimit)
        retainReferencedLocators(all, id, null);
      if (all.size() > 1024) throw new IOException("WORKSPACE_LIMIT: source locator limit reached; unexpired views were retained");
      if (size(all) > 1024 * 1024) throw new IOException("WORKSPACE_LIMIT: source locators exceed 1 MiB; unexpired views were retained");
      if (workingBytes + size(all) > byteLimit) throw new IOException("WORKSPACE_LIMIT: source locator exceeds the combined state quota");
      writeLocators(all); return copy.deepCopy();
    });
  }
  public JsonObject sourceLocator(UUID id) throws IOException {
    var found = locators.get(id);
    if (found == null) throw new IOException("SOURCE_UNAVAILABLE: this server has no authorized locator for the source; reopen the unchanged file in Source Overview");
    return found.deepCopy();
  }

  /** Called under the session map's admission lock: only coalesce and enqueue here, never I/O.
   * If the bounded queue is full its already-admitted jobs drain the coalesced releases. */
  public void releaseClient(UUID client) {
    endedClients.add(client);
    try { queue.execute(this::drainReleasedClients); }
    catch (RejectedExecutionException fullOrClosed) { /* Each pending job drains, and restart detaches all. */ }
  }
  private void drainReleasedClients() {
    for (var client : endedClients) {
      try {
        for (var entry : views.entrySet()) {
          var value = entry.getValue();
          if (!value.get("attached_client_id").isJsonNull() && client.toString().equals(value.get("attached_client_id").getAsString())) {
            var copy = value.deepCopy(); copy.add("attached_client_id", JsonNull.INSTANCE);
            copy.addProperty("detached_expires_at", clock.instant().plus(DRAFT_LIFETIME).toString()); write(entry.getKey(), copy);
          }
        }
        endedClients.remove(client);
      } catch (IOException failure) {
        org.slf4j.LoggerFactory.getLogger(VisualizationService.class).warn("Cannot detach expired visualization client: {}", failure.getMessage());
      }
    }
  }
  @FunctionalInterface private interface Operation { JsonObject run() throws Exception; }
  private CompletableFuture<JsonObject> submit(Authority caller, Operation operation) {
    return enqueue(caller, () -> describe(operation.run()));
  }
  private CompletableFuture<JsonObject> enqueue(Authority caller, Operation operation) {
    var result = new CompletableFuture<JsonObject>();
    try {
      Objects.requireNonNullElse(caller, Authority.UNTRUSTED).requireLocal();
      if (closed) throw new IOException("WORKSPACE_CLOSED");
      queue.execute(() -> {
        drainReleasedClients();
        try { result.complete(operation.run()); }
        catch (Exception failure) { result.completeExceptionally(failure); }
        finally { drainReleasedClients(); }
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
