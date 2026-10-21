/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.triplehelix.wpilogmcp.config.MirrorConfig;

/** A quiet, daemon-owned polling clock; no HTTP handler waits for the origin or the store queue. */
public final class MirrorService implements AutoCloseable {
  public record Status(String state, MirrorConfig config, String lastSync, Long ageSec,
      int remainingFiles, long remainingBytes, MirrorSync.Result result, StoreManifest.MirrorOrigin origin,
      ReplicaSummary replicas, MirrorCapacity.Snapshot capacity, Operation operation, Object masterCatalog, String mirrorId) {}
  public record Operation(String requestId, String state, Long catalogRevision, String error) {}
  @FunctionalInterface public interface ImpactReader { com.google.gson.JsonArray read(StoreManifest.Header header, java.util.List<ReplicaCatalog.Entry> files) throws IOException; }
  private volatile ImpactReader impacts = (header, files) -> { throw new IOException("VIEW_IMPACT_UNAVAILABLE: no view owner is attached"); };
  public void impacts(ImpactReader reader) { impacts = reader; }
  private volatile Operation operation;
  public record ReplicaSummary(int formatVersion, String catalogEpoch, long catalogRevision,
      int files, int legacyUnconfirmed, int verified, int exclusions, boolean writerEnabled) {}
  private final LogStore store;
  private final MirrorConfig config;
  private final Clock clock;
  @FunctionalInterface interface OriginReader { StoreManifest.MirrorOrigin read() throws IOException; }
  private final OriginReader readOrigin;
  private final ScheduledExecutorService timer;
  private final AtomicBoolean busy = new AtomicBoolean();
  private volatile boolean closed;
  private volatile String state = "waiting";
  private volatile MirrorSync.Progress progress = new MirrorSync.Progress("waiting", 0, 0, null);
  private volatile MirrorSync.Result result;
  private volatile StoreManifest.MirrorOrigin origin;
  private volatile StoreManifest.Header metadata;
  private volatile ReplicaJournal.State evidence;
  private volatile MirrorCapacity.Snapshot capacity;
  private volatile CompletableFuture<?> migration;
  private AutoCloseable rootGrant;
  public void rootGrant(AutoCloseable grant) { rootGrant = grant; }
  private java.util.function.Function<String, java.util.List<String>> discovered = master -> java.util.List.of();
  public void discovery(java.util.function.Function<String, java.util.List<String>> supplier) { discovered = supplier; }

  public MirrorService(LogStore store, MirrorConfig config) { this(store, config, Clock.systemUTC()); }
  MirrorService(LogStore store, MirrorConfig config, Clock clock) {
    this(store, config, clock, store::mirrorOrigin);
  }
  MirrorService(LogStore store, MirrorConfig config, Clock clock, OriginReader readOrigin) {
    this.store = store; this.config = config; this.clock = clock;
    this.readOrigin = readOrigin;
    timer = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "store-mirror"); t.setDaemon(true); return t; });
  }
  public void start() { timer.scheduleWithFixedDelay(this::syncNow, 0, config.intervalSec(), TimeUnit.SECONDS); }
  public boolean active() { return busy.get() || store.replicaTransfers().active(); }
  public MirrorConfig config() { return config; }
  public String id() { var seen = metadata; return seen == null ? null : seen.id(); }
  public CompletableFuture<MirrorSync.Result> syncNow() {
    if (closed || !busy.compareAndSet(false, true)) return CompletableFuture.failedFuture(new IOException("Mirror synchronization is already running or stopped"));
    state = "synchronizing";
    return store.mirror(config, p -> progress = p, clock, StoreSync::http, discovered).whenComplete((value, error) -> {
      try {
        String completed;
        if (error == null) { result = value; completed = value.state(); }
        else {
          completed = "error";
          result = new MirrorSync.Result("error", origin == null ? null : origin.lastSync(), 0, java.util.List.of(),
              java.util.List.of(), java.util.List.of(), error.getCause() == null ? error.getMessage() : error.getCause().getMessage());
        }
        origin = readOrigin.read();
        metadata = store.replicaHeader();
        evidence = ReplicaJournal.read(store.root(), new StoreFiles(store.root(), store.replicaSecurity()));
        capacity = store.mirrorCapacity(config);
        // Publish completion last: a polling client must see the matching durable sync time.
        state = completed;
      } catch (IOException e) { state = "error"; }
      finally { busy.set(false); }
    });
  }
  public Status status() {
    var seen = origin; String last = seen == null ? null : seen.lastSync();
    Long age = last == null ? null : Math.max(0, Duration.between(Instant.parse(last), clock.instant()).getSeconds());
    var p = progress;
    var header = metadata; var catalog = header == null ? null : header.replicas();
    var summary = header == null ? null : new ReplicaSummary(header.formatVersion(), catalog == null ? null : catalog.epoch(),
        catalog == null ? 0 : catalog.revision(), catalog == null ? 0 : catalog.files().size(),
        catalog == null ? 0 : (int) catalog.files().stream().filter(f -> f.verificationBasis().equals("legacy_unconfirmed")).count(),
        catalog == null ? 0 : (int) catalog.files().stream().filter(f -> f.verificationBasis().equals("verified")).count(),
        catalog == null ? 0 : catalog.exclusions().size(), catalog != null);
    return new Status(state, config, last, age, active() ? p.remainingFiles() : 0, active() ? p.remainingBytes() : 0, result, seen, summary, store.replicaTransfers().active() && store.replicaTransfers().capacity() != null ? store.replicaTransfers().capacity() : capacity, operation,
        evidence == null ? null : evidence.coverage(), id());
  }

  public CompletableFuture<ReplicaCatalog> migrate(String storeId) {
    if (closed || !busy.compareAndSet(false, true)) return CompletableFuture.failedFuture(new IOException("Mirror is busy or stopped"));
    state = "migrating";
    var operation = store.migrateReplicas(storeId, config); migration = operation;
    if (closed) operation.cancel(false);
    return operation.whenComplete((catalog, error) -> {
      if (closed) { busy.set(false); return; }
      try {
        metadata = store.replicaHeader(); origin = metadata.origin();
        capacity = store.mirrorCapacity(config);
        state = error == null ? "ready" : "error";
        result = new MirrorSync.Result(state, origin == null ? null : origin.lastSync(), 0, java.util.List.of(),
            java.util.List.of(), java.util.List.of(), error == null ? "Migration complete; finalized replica synchronization is ready"
                : error.getMessage());
      } catch (IOException failure) { state = "error"; }
      finally { busy.set(false); }
    });
  }

  public com.google.gson.JsonObject cleanupPreview(java.util.List<String> keys) throws IOException {
    var header = metadata;
    if (header == null || header.replicas() == null) throw new IOException("MIGRATION_REQUIRED: migrate this mirror first");
    var preview = header.replicas().preview(header.id(), keys);
    var result = StoreJson.JSON.toJsonTree(preview).getAsJsonObject();
    result.addProperty("writer_state", "ready"); result.addProperty("policy_revision", ReplicaTransfers.policyKey(config));
    result.add("affected_views", impacts.read(header, preview.files()));
    result.addProperty("master_presence_basis", "not_queried; stored confirmations are historical evidence only");
    return result;
  }
  public CompletableFuture<?> cleanup(String request, String storeId, String epoch, long revision, String policy, java.util.List<String> keys) throws IOException {
    return maintain(request, () -> store.replicaTransfers().cleanup(config, request, storeId, epoch, revision, policy, keys));
  }
  public CompletableFuture<?> reinclude(String request, String epoch, long revision, String selectedPolicy, java.util.List<String> keys) throws IOException {
    if (!ReplicaTransfers.policyKey(config).equals(selectedPolicy)) throw new IOException("STALE_PREVIEW: mirror policy changed");
    return maintain(request, () -> store.replicaTransfers().reinclude(config, request, epoch, revision, keys));
  }
  private CompletableFuture<?> maintain(String request, java.util.function.Supplier<CompletableFuture<ReplicaTransfers.Receipt>> action) throws IOException {
    if (closed || !busy.compareAndSet(false, true)) throw new IOException("Mirror operation is already running or stopped");
    state = "maintenance_running"; operation = new Operation(request, "running", null, null);
    CompletableFuture<ReplicaTransfers.Receipt> pending;
    try { pending = action.get(); } catch (RuntimeException invalid) { busy.set(false); state = "error"; throw invalid; }
    return pending.whenComplete((value, error) -> {
      try {
        metadata = store.replicaHeader(); origin = metadata.origin(); capacity = store.mirrorCapacity(config);
        evidence = ReplicaJournal.read(store.root(), new StoreFiles(store.root(), store.replicaSecurity()));
        if (error == null) operation = new Operation(request, value.state(), value.catalogRevision(), null);
        else { Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause(); operation = new Operation(request, "error", null, cause.getMessage()); }
        state = error == null ? "maintenance_complete" : "error";
      } catch (IOException failure) { state = "error"; operation = new Operation(request, "error", null, failure.getMessage()); }
      finally { busy.set(false); }
    });
  }

  public java.util.Map<String, Object> files(int offset, int limit) throws IOException {
    var header = metadata;
    if (header == null || header.replicas() == null) throw new IOException("MIGRATION_REQUIRED: migrate this mirror first");
    var all = header.replicas().files();
    if (offset < 0 || limit < 1 || limit > 256) throw new IllegalArgumentException("offset must be nonnegative and limit 1 to 256");
    int end = Math.min(all.size(), Math.min(offset, all.size()) + limit);
    var page = new java.util.ArrayList<ReplicaCatalog.Entry>();
    var observed = evidence;
    var notes = new java.util.ArrayList<ReplicaJournal.Observation>();
    int bytes = 1024;
    for (var entry : all.subList(Math.min(offset, all.size()), end)) {
      var history = observed == null ? java.util.List.<ReplicaJournal.Observation>of() : observed.observations().stream()
          .filter(o -> o.identity().equals(entry.identity())).toList();
      int cost = StoreJson.JSON.toJson(entry).getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1
          + StoreJson.JSON.toJson(history).getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
      if (bytes + cost > 1_000_000) break;
      bytes += cost; page.add(entry); notes.addAll(history);
    }
    if (page.isEmpty() && offset < all.size()) throw new IOException("RESPONSE_TOO_LARGE: replica record cannot fit a page");
    return java.util.Map.of("files", page, "offset", offset, "limit", limit, "total", all.size(), "observations", notes,
        "has_more", offset < all.size() - page.size(), "catalog_epoch", header.replicas().epoch(),
        "catalog_revision", header.replicas().revision());
  }
  public java.util.Map<String, Object> exclusions(int offset, int limit) throws IOException {
    var header = metadata;
    if (header == null || header.replicas() == null) throw new IOException("MIGRATION_REQUIRED: migrate this mirror first");
    if (offset < 0 || limit < 1 || limit > 256) throw new IllegalArgumentException("offset must be nonnegative and limit 1 to 256");
    var all = header.replicas().exclusions(); int start = Math.min(offset, all.size()), end = Math.min(all.size(), start + limit);
    return java.util.Map.of("exclusions", all.subList(start, end), "offset", offset, "limit", limit, "total", all.size(),
        "has_more", end < all.size(), "catalog_epoch", header.replicas().epoch(), "catalog_revision", header.replicas().revision(),
        "policy_revision", ReplicaTransfers.policyKey(config));
  }
  public CompletableFuture<StoreManifest.MirrorOrigin> pin(String id, boolean pinned) {
    try {
      if (store.replicaHeader().replicas() != null)
        return maintain(null, () -> store.replicaTransfers().pin(config, id, pinned)).thenApply(value -> origin);
    } catch (IOException failure) { return CompletableFuture.failedFuture(failure); }
    return store.pin(id, pinned).thenApply(value -> {
      try {
        // Inclusion changes the catalog revision; previews must not keep the previous snapshot.
        metadata = store.replicaHeader(); capacity = store.mirrorCapacity(config);
        origin = metadata.origin(); return origin;
      } catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
    });
  }
  @Override public void close() {
    closed = true; store.replicaTransfers().cancel(); timer.shutdownNow(); var operation = migration; if (operation != null) operation.cancel(false);
    var grant = rootGrant; rootGrant = null;
    if (grant != null) try { grant.close(); } catch (Exception failure) { throw new IllegalStateException("Cannot release mirror directory grant", failure); }
  }
}
