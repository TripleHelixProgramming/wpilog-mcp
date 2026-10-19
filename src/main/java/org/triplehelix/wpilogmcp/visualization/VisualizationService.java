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
  public static final int MAX_TOMBSTONES = 256;
  public static final long MAX_TOMBSTONE_BYTES = 8L * 1024 * 1024;
  public static final Duration TOMBSTONE_LIFETIME = Duration.ofHours(6);
  private final StateFiles files;
  private final FileChannel lock;
  private final Clock clock;
  private final int documentLimit;
  private final long byteLimit;
  private final ConcurrentMap<UUID, JsonObject> views = new ConcurrentHashMap<>();
  private final ThreadPoolExecutor queue = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(64), task -> { var t = new Thread(task, "visualization-state"); t.setDaemon(true); return t; });
  private volatile boolean closed;
  private final UUID serverId;
  public UUID serverId() { return serverId; }
  private volatile java.util.function.Consumer<JsonObject> changed = ignored -> {};
  /** The listener routes by the attached window's session, so it receives view_id, revision and
   * that internal attached_client_id, never the public projection. */
  public void onChanged(java.util.function.Consumer<JsonObject> listener) { changed = listener; }
  private final WindowIds windowIds = new WindowIds();
  /** Public window IDs for this workspace's replies; window allowance reports use the same ones. */
  public WindowIds windowIds() { return windowIds; }
  private final ConcurrentMap<UUID, JsonObject> locators = new ConcurrentHashMap<>();
  private final Set<UUID> endedClients = ConcurrentHashMap.newKeySet();

  public VisualizationService(ServerIdentity identity) throws IOException { this(identity, Clock.systemUTC(), MAX_DOCUMENTS, MAX_STATE_BYTES); }
  VisualizationService(ServerIdentity identity, Clock clock, int documents, long bytes) throws IOException {
    serverId = identity.id();
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
          int loadedDocuments = 0;
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
            if (!deleted(record) && (++loadedDocuments > documentLimit || (loadedBytes += size(record)) > byteLimit))
              throw new IOException("WORKSPACE_LIMIT: existing working views exceed their configured quota");
            // The unreleased prototype retained deleted records for 30 days. Shorten that retry
            // window once on recovery, persisting it so repeated restarts cannot extend it.
            if (deleted(record)) {
              var deadline = clock.instant().plus(TOMBSTONE_LIFETIME);
              if (record.get("detached_expires_at").isJsonNull()
                  || Instant.parse(record.get("detached_expires_at").getAsString()).isAfter(deadline)) {
                record.addProperty("detached_expires_at", deadline.toString());
                files.write("views/" + name, record.toString());
              }
            }
            // A process restart ends all old window attachments, never the recoverable draft.
            if (!record.get("attached_client_id").isJsonNull()) {
              record.add("attached_client_id", JsonNull.INSTANCE);
              record.addProperty("detached_expires_at", clock.instant().plus(DRAFT_LIFETIME).toString());
              files.write("views/" + name, record.toString());
            }
            views.put(id, record);
            trimTombstones(null, null);
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
    if (!record.has("requests")) record.add("requests", new JsonObject());
    if (!record.has("deleted")) record.addProperty("deleted", false);
    if (!record.has("template_ref")) record.add("template_ref", JsonNull.INSTANCE);
    if (!record.has("creation_saved_view_ref")) record.add("creation_saved_view_ref", JsonNull.INSTANCE);
    if (!record.get("creation_saved_view_ref").isJsonNull()) LibraryReferences.saved(record.getAsJsonObject("creation_saved_view_ref"));
    TemplateDefinition.validateReference(record.get("template_ref"));
    if (!record.get("saved_view_ref").isJsonNull()) LibraryReferences.saved(record.getAsJsonObject("saved_view_ref"));
    if (!record.keySet().equals(Set.of("schema_version", "view_id", "creation_request_id", "creation_saved_view_ref", "template_ref", "requests", "deleted", "revision", "source_ref", "configuration", "initial_configuration", "checkpoint_configuration", "saved_view_ref", "attached_client_id", "detached_expires_at"))) throw new IOException("Unknown working-state fields");
    TemplateDefinition.revision(record, "revision", false);
    UUID.fromString(record.get("creation_request_id").getAsString());
    if (!record.get("deleted").isJsonPrimitive() || !record.getAsJsonPrimitive("deleted").isBoolean()) throw new IOException("Invalid deleted marker");
    var receipts = record.getAsJsonObject("requests");
    if (receipts.size() > 4096) throw new IOException("Invalid working receipt count");
    for (var entry : receipts.entrySet()) {
      UUID.fromString(entry.getKey()); var receipt = entry.getValue().getAsJsonObject();
      if (!(receipt.keySet().equals(Set.of("digest", "result")) || receipt.keySet().equals(Set.of("digest", "snapshot")))
          || !receipt.get("digest").getAsString().matches("[a-f0-9]{64}")) throw new IOException("Invalid working receipt");
      var retained = receipt.getAsJsonObject(receipt.has("result") ? "result" : "snapshot");
      TemplateDefinition.validateReference(retained.get("template_ref"));
      if (retained.has("template")) {
        if (!retained.keySet().equals(Set.of("template", "template_ref"))) throw new IOException("Invalid template mutation receipt");
        TemplateDefinition.validate(retained.getAsJsonObject("template"));
      } else { UUID.fromString(retained.get("view_id").getAsString()); VisualizationContracts.configuration(retained.getAsJsonObject("configuration")); }
    }
    VisualizationContracts.configuration(record.getAsJsonObject("initial_configuration"));
    VisualizationContracts.source(record.getAsJsonObject("source_ref"));
    VisualizationContracts.configuration(record.getAsJsonObject("configuration"));
    if (!record.get("checkpoint_configuration").isJsonNull()) VisualizationContracts.configuration(record.getAsJsonObject("checkpoint_configuration"));
    if (!record.get("attached_client_id").isJsonNull()) UUID.fromString(record.get("attached_client_id").getAsString());
    if (!record.get("detached_expires_at").isJsonNull()) Instant.parse(record.get("detached_expires_at").getAsString());
  }

  public CompletableFuture<JsonObject> create(Authority caller, UUID request, JsonObject source, JsonObject configuration) {
    return create(caller, request, source, configuration, null);
  }
  public CompletableFuture<JsonObject> create(Authority caller, UUID request, JsonObject source, JsonObject configuration, JsonObject templateRef) {
    return createSaved(caller, request, source, configuration, templateRef, null);
  }
  /** Opening a committed library definition publishes its checkpoint and retry identity together. */
  public CompletableFuture<JsonObject> createSaved(Authority caller, UUID request, JsonObject source, JsonObject configuration, JsonObject templateRef, JsonObject savedRef) {
    var saved = savedRef == null ? JsonNull.INSTANCE : LibraryReferences.saved(savedRef);
    var reference = templateRef == null ? JsonNull.INSTANCE : templateRef.deepCopy();
    var sourceCopy = source.deepCopy(); var configCopy = configuration.deepCopy();
    return submit(caller, () -> {
      var validSource = VisualizationContracts.source(sourceCopy);
      var validConfig = VisualizationContracts.configuration(configCopy);
      TemplateDefinition.validateReference(reference);
      prune();
      for (var record : views.values()) if (request.toString().equals(record.get("creation_request_id").getAsString())) {
        if (!validSource.equals(record.get("source_ref")) || !validConfig.equals(record.get("initial_configuration")) || !reference.equals(record.get("template_ref")) || !saved.equals(record.get("creation_saved_view_ref"))) throw new IOException("REQUEST_CONFLICT: create request reused with different content");
        return record;
      }
      var id = UUID.randomUUID(); var record = new JsonObject();
      record.addProperty("schema_version", 1); record.addProperty("view_id", id.toString());
      record.addProperty("creation_request_id", request.toString()); record.addProperty("revision", 1); record.add("requests", new JsonObject()); record.addProperty("deleted", false);
      record.add("source_ref", validSource); record.add("configuration", validConfig); record.add("template_ref", reference);
      record.add("initial_configuration", validConfig.deepCopy());
      record.add("creation_saved_view_ref", saved); record.add("checkpoint_configuration", saved.isJsonNull() ? JsonNull.INSTANCE : validConfig.deepCopy()); record.add("saved_view_ref", saved.deepCopy());
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

  /** Public-tool edits retain original results across session retries. Receipts share the atomic
   * working record and its quota/lifetime. Deleting releases the live quota; the resulting retry
   * record has its own short lifetime and yields to older-first eviction under pressure. */
  public CompletableFuture<JsonObject> revise(Authority caller, UUID view, long revision, UUID request,
      JsonObject configuration, boolean discard, boolean delete) {
    var copy = configuration == null ? null : configuration.deepCopy();
    return enqueue(caller, () -> {
      var signature = new JsonObject(); signature.addProperty("revision", revision); signature.addProperty("discard", discard); signature.addProperty("delete", delete);
      signature.add("configuration", copy == null ? JsonNull.INSTANCE : copy); String digest = TemplateDefinition.digest(signature);
      var original = views.get(view);
      if (original != null && !expired(original) && original.getAsJsonObject("requests").has(request.toString())) {
        var receipt = original.getAsJsonObject("requests").getAsJsonObject(request.toString());
        if (!digest.equals(receipt.get("digest").getAsString())) throw new IOException("REQUEST_CONFLICT: request id reused with different content");
        return receipt.getAsJsonObject("result").deepCopy();
      }
      var record = checked(view, revision);
      if (delete && !record.get("attached_client_id").isJsonNull()) throw new IOException("VIEW_ATTACHED: close the owning editor before deleting its working view");
      JsonObject config = discard ? (record.get("checkpoint_configuration").isJsonNull() ? record.getAsJsonObject("initial_configuration") : record.getAsJsonObject("checkpoint_configuration")) : copy;
      if (!delete) record.add("configuration", VisualizationContracts.configuration(config));
      if (delete) { record.addProperty("deleted", true); record.addProperty("detached_expires_at", clock.instant().plus(TOMBSTONE_LIFETIME).toString()); }
      next(record); var result = describe(record);
      var receipts = record.getAsJsonObject("requests");
      if (receipts.size() >= 4096) throw new IOException("WORKSPACE_LIMIT: working request receipts are full; clone or save this view");
      var receipt = new JsonObject(); receipt.addProperty("digest", digest); receipt.add("result", result.deepCopy()); receipts.add(request.toString(), receipt);
      write(view, record); return result;
    });
  }

  /** Reserve the exact intent before work on the store queue. Pending receipts survive a crash;
   * retries use the original snapshot even if another caller edits the working view meanwhile. */
  public CompletableFuture<JsonObject> prepareMutation(Authority caller, UUID view, long revision,
      UUID request, JsonObject intent, UUID client) {
    var copy = intent.deepCopy();
    return enqueue(caller, () -> {
      String digest = TemplateDefinition.digest(copy); var record = raw(view); requireOwner(record, client);
      var receipts = record.getAsJsonObject("requests"); var existing = receipts.get(request.toString());
      if (existing != null) {
        if (!digest.equals(existing.getAsJsonObject().get("digest").getAsString())) throw new IOException("REQUEST_CONFLICT: request id reused with different content");
        return existing.deepCopy().getAsJsonObject();
      }
      if (record.get("revision").getAsLong() != revision) throw new IOException("REVISION_CONFLICT: current revision is " + record.get("revision"));
      if (receipts.size() >= 4096) throw new IOException("WORKSPACE_LIMIT: working retry history is full; save a new copy");
      var receipt = new JsonObject(); receipt.addProperty("digest", digest); receipt.add("snapshot", describe(record));
      receipts.add(request.toString(), receipt); write(view, record); return receipt.deepCopy();
    });
  }

  /** A saved checkpoint records the reserved configuration, leaving intervening edits dirty.
   * Clone completion only retains its receipt. Neither operation holds the state queue over I/O
   * on another owner's queue. */
  public CompletableFuture<JsonObject> completeMutation(Authority caller, UUID view, UUID request,
      JsonObject result, JsonObject savedReference) {
    var answer = result == null ? null : result.deepCopy();
    var ref = savedReference == null ? null : LibraryReferences.saved(savedReference);
    return enqueue(caller, () -> {
      var record = raw(view); var receipt = record.getAsJsonObject("requests").getAsJsonObject(request.toString());
      if (receipt.has("result")) return receipt.getAsJsonObject("result").deepCopy();
      JsonObject completed = answer;
      if (ref != null) {
        record.add("saved_view_ref", ref); record.add("checkpoint_configuration", receipt.getAsJsonObject("snapshot").get("configuration").deepCopy());
        next(record); completed = describe(record);
        if (ref.has("library_id")) VisualizationLibrary.wire(completed);
      }
      receipt.remove("snapshot"); receipt.add("result", completed.deepCopy()); write(view, record); return completed;
    });
  }

  /** Called only after the store has acknowledged its own CAS. A concurrent edit stays dirty. */
  public CompletableFuture<JsonObject> checkpoint(Authority caller, UUID view, long revision, JsonObject savedReference) {
    return checkpointAttached(caller, view, revision, savedReference, null);
  }
  public CompletableFuture<JsonObject> checkpointAttached(Authority caller, UUID view, long revision, JsonObject savedReference, UUID client) {
    var ref = savedReference.deepCopy();
    return submit(caller, () -> {
      LibraryReferences.saved(ref);
      var record = checked(view, revision); requireOwner(record, client);
      record.add("saved_view_ref", ref); record.add("checkpoint_configuration", record.get("configuration").deepCopy());
      next(record); write(view, record); return record;
    });
  }

  public CompletableFuture<JsonObject> attach(Authority caller, UUID view, UUID client) {
    return attach(caller, view, client, () -> true);
  }
  public CompletableFuture<JsonObject> attach(Authority caller, UUID view, UUID client, java.util.function.BooleanSupplier active) {
    return attach(caller, view, client, active, owner -> false);
  }
  /**
   * Attaches the view to {@code client}'s window. Another window's attachment stands unless
   * {@code gone} says that window has stopped answering: one closed or reloaded without ending its
   * session would otherwise hold its views until the session expires an hour later.
   */
  public CompletableFuture<JsonObject> attach(Authority caller, UUID view, UUID client, java.util.function.BooleanSupplier active,
      java.util.function.Predicate<UUID> gone) {
    return submit(caller, () -> {
      if (!active.getAsBoolean()) throw new IOException("SESSION_EXPIRED: the session ended while attachment was queued");
      var record = raw(view);
      var attached = record.get("attached_client_id");
      if (!attached.isJsonNull() && !client.toString().equals(attached.getAsString())) {
        if (!gone.test(UUID.fromString(attached.getAsString()))) throw new IOException("VIEW_ATTACHED: reveal the owning window or open a copy");
        org.slf4j.LoggerFactory.getLogger(VisualizationService.class).info("View {} moves from a window that stopped answering", view);
      }
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
  /** A view's source is immutable, including in retry tombstones. Version checks therefore
   * precede mutations without interfering with their existing idempotency handling. */
  public void requireSourceVersion(UUID view, int version) {
    var record = views.get(view);
    if (record != null) { VisualizationContracts.transportSource(record, version); LibraryReferences.version(record, version); }
  }
  public List<JsonObject> list(JsonObject source) throws IOException {
    var checked = VisualizationContracts.source(source);
    return views.values().stream().filter(r -> !expired(r) && !r.get("deleted").getAsBoolean() && checked.equals(r.get("source_ref")))
        .sorted(Comparator.comparing(r -> r.get("view_id").getAsString())).map(this::describe).toList();
  }
  /** Navigation never copies configurations or recovery checkpoints into the response. */
  public List<JsonObject> summaries(JsonObject source) throws IOException {
    var checked = VisualizationContracts.source(source);
    return views.values().stream().filter(r -> !expired(r) && !r.get("deleted").getAsBoolean() && checked.equals(r.get("source_ref")))
        .sorted(Comparator.comparing(r -> r.get("view_id").getAsString())).map(r -> {
          var summary = new JsonObject();
          for (var key : List.of("view_id", "revision", "saved_view_ref", "detached_expires_at")) summary.add(key, r.get(key).deepCopy());
          summary.add("attached_client_id", attachment(r)); return summary;
        }).toList();
  }
  public List<JsonObject> all() {
    return views.values().stream().filter(r -> !expired(r) && !r.get("deleted").getAsBoolean()).map(this::describe).toList();
  }

  private JsonObject raw(UUID id) throws IOException {
    var record = views.get(id);
    if (record == null || expired(record) || record.get("deleted").getAsBoolean()) throw new IOException("VIEW_UNAVAILABLE: draft is missing or expired");
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
  /** The public projection, which retry receipts also store. Internally an attachment is the
   * window's transport session, which ownership checks compare; callers see its public window ID. */
  private JsonObject describe(JsonObject original) {
    var record = original.deepCopy(); record.remove("requests"); record.remove("creation_saved_view_ref"); record.add("attached_client_id", attachment(original));
    boolean unsaved = record.get("checkpoint_configuration").isJsonNull() || !record.get("configuration").equals(record.get("checkpoint_configuration"));
    record.addProperty("has_unsaved_changes", unsaved);
    record.addProperty("native_dirty", unsaved && !record.get("saved_view_ref").isJsonNull());
    return record;
  }
  private JsonElement attachment(JsonObject record) {
    var session = record.get("attached_client_id");
    return session.isJsonNull() ? JsonNull.INSTANCE : new JsonPrimitive(windowIds.forSession(session.getAsString()));
  }
  private boolean expired(JsonObject record) {
    return record.get("attached_client_id").isJsonNull() && !record.get("detached_expires_at").isJsonNull()
        && !Instant.parse(record.get("detached_expires_at").getAsString()).isAfter(clock.instant());
  }
  private static boolean deleted(JsonObject record) { return record.get("deleted").getAsBoolean(); }

  /** Retry receipts are disposable only after explicit deletion. Account for the pending write
   * before publishing it, and remove files before their in-memory entries. A failed write can
   * lose old retry proof under pressure, never a live draft or its acknowledged edits. */
  private void trimTombstones(UUID replacing, JsonObject replacement) throws IOException {
    var tombstones = views.entrySet().stream()
        .filter(e -> !e.getKey().equals(replacing) && deleted(e.getValue()))
        .sorted(Comparator.<Map.Entry<UUID, JsonObject>, Instant>comparing(e -> Instant.parse(e.getValue().get("detached_expires_at").getAsString()))
            .thenComparing(Map.Entry::getKey)).toList();
    boolean adding = replacement != null && deleted(replacement);
    long bytes = (adding ? size(replacement) : 0L) + tombstones.stream().mapToLong(e -> size(e.getValue())).sum();
    int count = tombstones.size() + (adding ? 1 : 0);
    for (var entry : tombstones) {
      if (count <= MAX_TOMBSTONES && bytes <= MAX_TOMBSTONE_BYTES) break;
      if (files != null) Files.deleteIfExists(files.path("views/" + entry.getKey() + ".json"));
      views.remove(entry.getKey()); count--; bytes -= size(entry.getValue());
    }
  }
  private void prune() throws IOException {
    var expiredSources = new HashSet<UUID>();
    for (var entry : views.entrySet()) if (expired(entry.getValue())) {
      if (files != null) Files.deleteIfExists(files.path("views/" + entry.getKey() + ".json"));
      var source = entry.getValue().getAsJsonObject("source_ref");
      if (VisualizationContracts.kind(source).equals("unmanaged")) expiredSources.add(UUID.fromString(VisualizationContracts.content(source).get("source_id").getAsString()));
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
      if (deleted(record)) continue;
      var source = record.getAsJsonObject("source_ref");
      if (VisualizationContracts.kind(source).equals("unmanaged")) referenced.add(VisualizationContracts.content(source).get("source_id").getAsString());
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
    long total = record == null || deleted(record) ? 0 : size(record);
    int count = record == null || deleted(record) ? 0 : 1;
    for (var entry : views.entrySet()) if (!entry.getKey().equals(replacing) && !deleted(entry.getValue())) { total += size(entry.getValue()); count++; }
    // Keep recent identifications until pressure: two overviews can identify A/B before either creates a view.
    if (total + 4096 + size(locatorRecords()) > byteLimit) {
      var source = record == null ? null : record.getAsJsonObject("source_ref");
      var keep = source != null && VisualizationContracts.kind(source).equals("unmanaged") ? UUID.fromString(VisualizationContracts.content(source).get("source_id").getAsString()) : null;
      pruneLocators(keep, null);
    }
    // Reserve space for identity and the lock; temporary replacement uses at most one extra record.
    if (count > documentLimit) {
      long live = views.values().stream().filter(r -> !deleted(r)).count();
      long attached = views.values().stream().filter(r -> !deleted(r) && !r.get("attached_client_id").isJsonNull()).count();
      throw new IOException("WORKSPACE_LIMIT: " + live + " working views (" + attached + " attached, " + (live - attached)
          + " detached) hold the " + documentLimit + "-view limit; close and delete an unneeded working view; saving alone does not release its slot");
    }
    if (total + 4096 + locators.values().stream().mapToLong(VisualizationService::size).sum() > byteLimit)
      throw new IOException("WORKSPACE_LIMIT: working views, checkpoints, edit receipts and source locators exceed the " + byteLimit
          + "-byte metadata limit; delete an unneeded working view; deleted retry records use a separate budget");
  }
  private static int size(JsonObject record) { return record.toString().getBytes(StandardCharsets.UTF_8).length; }
  private void write(UUID id, JsonObject record) throws IOException {
    if (size(record) > 4 * 1024 * 1024) throw new IOException("WORKSPACE_LIMIT: working record and receipts exceed 4 MiB; save a new copy");
    checkBudget(id, record);
    trimTombstones(id, record);
    if (files != null) files.write("views/" + id + ".json", record.toString());
    views.put(id, record.deepCopy());
    var change = new JsonObject(); for (var key : List.of("view_id", "revision", "attached_client_id")) change.add(key, record.get(key).deepCopy());
    changed.accept(change);
  }
  /** Locators are owner-local recovery metadata, never part of a portable SourceRef. */
  public CompletableFuture<JsonObject> rememberSource(Authority caller, UUID id, JsonObject locator) {
    var copy = locator.deepCopy();
    return enqueue(caller, () -> {
      prune();
      var all = locatorRecords(); all.add(id.toString(), copy);
      long workingBytes = 4096L + views.values().stream().filter(r -> !deleted(r)).mapToLong(VisualizationService::size).sum();
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
      VisualizationAccess.require(caller, VisualizationAccess.Operation.WORKING);
      if (closed) throw new IOException("WORKSPACE_CLOSED");
      queue.execute(() -> {
        drainReleasedClients();
        // Every failure answers the caller, an Error too: the HTTP worker waits on this future
        // without a deadline, so an unanswered one would hold it for good.
        try { result.complete(operation.run()); }
        catch (Throwable failure) { result.completeExceptionally(failure); if (failure instanceof VirtualMachineError fatal) throw fatal; }
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
