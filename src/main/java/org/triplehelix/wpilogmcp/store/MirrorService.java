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
      ReplicaSummary replicas, MirrorCapacity.Snapshot capacity) {}
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
  private volatile MirrorCapacity.Snapshot capacity;
  private volatile CompletableFuture<?> migration;

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
  public boolean active() { return busy.get(); }
  public MirrorConfig config() { return config; }
  public CompletableFuture<MirrorSync.Result> syncNow() {
    if (closed || !busy.compareAndSet(false, true)) return CompletableFuture.failedFuture(new IOException("Mirror synchronization is already running or stopped"));
    state = "synchronizing";
    return store.mirror(config, p -> progress = p, clock, StoreSync::http).whenComplete((value, error) -> {
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
        catalog == null ? 0 : catalog.exclusions().size(), false);
    return new Status(state, config, last, age, active() ? p.remainingFiles() : 0, active() ? p.remainingBytes() : 0, result, seen, summary, capacity);
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
        state = error == null ? "writer_not_ready" : "error";
        result = new MirrorSync.Result(state, origin == null ? null : origin.lastSync(), 0, java.util.List.of(),
            java.util.List.of(), java.util.List.of(), error == null ? "Migration complete; payload writer remains disabled"
                : error.getMessage());
      } catch (IOException failure) { state = "error"; }
      finally { busy.set(false); }
    });
  }

  public ReplicaCatalog.CleanupPreview cleanupPreview(java.util.List<String> keys) throws IOException {
    var header = metadata;
    if (header == null || header.replicas() == null) throw new IOException("MIGRATION_REQUIRED: migrate this mirror first");
    return header.replicas().preview(header.id(), keys);
  }

  public java.util.Map<String, Object> files(int offset, int limit) throws IOException {
    var header = metadata;
    if (header == null || header.replicas() == null) throw new IOException("MIGRATION_REQUIRED: migrate this mirror first");
    var all = header.replicas().files();
    if (offset < 0 || limit < 1 || limit > 256) throw new IllegalArgumentException("offset must be nonnegative and limit 1 to 256");
    int end = Math.min(all.size(), Math.min(offset, all.size()) + limit);
    var page = new java.util.ArrayList<ReplicaCatalog.Entry>();
    int bytes = 1024;
    for (var entry : all.subList(Math.min(offset, all.size()), end)) {
      int cost = StoreJson.JSON.toJson(entry).getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
      if (bytes + cost > 1_000_000) break;
      bytes += cost; page.add(entry);
    }
    if (page.isEmpty() && offset < all.size()) throw new IOException("RESPONSE_TOO_LARGE: replica record cannot fit a page");
    return java.util.Map.of("files", page, "offset", offset, "limit", limit, "total", all.size(),
        "has_more", offset < all.size() - page.size(), "catalog_epoch", header.replicas().epoch(),
        "catalog_revision", header.replicas().revision());
  }
  public CompletableFuture<StoreManifest.MirrorOrigin> pin(String id, boolean pinned) {
    return store.pin(id, pinned).thenApply(value -> {
      try {
        // Inclusion changes the catalog revision; previews must not keep the previous snapshot.
        metadata = store.replicaHeader(); capacity = store.mirrorCapacity(config);
        origin = value; return value;
      } catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
    });
  }
  @Override public void close() {
    closed = true; timer.shutdownNow(); var operation = migration; if (operation != null) operation.cancel(false);
  }
}
