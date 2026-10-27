/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.triplehelix.wpilogmcp.log.LogFileAccess;
import org.triplehelix.wpilogmcp.read.internal.ReadWire;

/** Fixed, session-bound offer snapshots. Reservations precede construction and remain owned by
 * timed-out builders until they unwind. Metadata locks never enclose filesystem work or callbacks.
 * Authority is a channel decision at the receiver; a forwarded verification claim is only a claim. */
public final class ReplicaProtocol implements AutoCloseable {
  public static final int PAGE_BYTES = 1 << 20, HANDSHAKE_BYTES = 64 << 10;
  private static final long BUILD_BYTES = 32L << 20, TOTAL_BYTES = 64L << 20;
  public record Store(String storeId, String masterStoreId, boolean master, int formatVersion) {
    public Store {
      ReplicaJournal.uuid(storeId); if (masterStoreId != null) ReplicaJournal.uuid(masterStoreId);
      if (formatVersion != 2 || master && !storeId.equals(masterStoreId)) throw new IllegalArgumentException("Invalid replica store identity");
    }
  }
  public record Hello(int protocolVersion, String sessionId, List<String> capabilities, List<Store> stores) {}
  public record Offer(ReplicaCatalog.Identity identity, String name, String sha256, long sizeBytes,
      String kind, String completionBasis, String verificationClaim, ReplicaCatalog.Placement placement, boolean growing) {
    public Offer {
      Objects.requireNonNull(identity); StoreFiles.component(name); ReplicaJournal.hash(sha256);
      if (name.length() > 1024 || sizeBytes < 0 || !Set.of("wpilog", "revlog", "clock_record").contains(kind)
          || !Set.of("closed_capture", "completed_import", "closed_clock").contains(completionBasis)
          || !Set.of("master_local", "legacy_unconfirmed", "provisional", "verified").contains(verificationClaim))
        throw new IllegalArgumentException("Invalid replica offer");
    }
  }
  public record Page(String inventoryId, String catalogEpoch, long catalogRevision, int total,
      List<Offer> offers, String nextCursor) {}
  public record Payload(Path path, long sizeBytes, LogFileAccess.Lease lease) implements AutoCloseable {
    @Override public void close() { lease.close(); }
  }
  private static final class Session {
    final String id = UUID.randomUUID().toString(); final long born; long used;
    final Map<String, List<StoreDoor.Selected>> stores;
    Session(long now, Map<String, List<StoreDoor.Selected>> stores) {
      born = used = now; this.stores = Map.copyOf(stores);
    }
  }
  private static final class Snapshot {
    final String id = UUID.randomUUID().toString(), session, store; final int limit; final long born;
    long used, bytes = BUILD_BYTES; int users; boolean retired;
    StoreDoor.Selected selected; String epoch; long revision; List<Offer> offers;
    // One opaque cursor per boundary: replay is deterministic, and callers cannot invent offsets.
    final Map<String, Integer> cursors = new HashMap<>();
    Snapshot(String session, String store, int limit, long now) {
      this.session = session; this.store = store; this.limit = limit; born = used = now;
    }
  }
  private final StoreDoor door;
  private final Clock clock;
  private java.util.function.Consumer<Path> recovery = path -> {};
  private StoreRegistry publicationOwners;
  private final java.util.concurrent.atomic.AtomicLong hashBytes = new java.util.concurrent.atomic.AtomicLong(), fileOpens = new java.util.concurrent.atomic.AtomicLong();
  private final org.triplehelix.wpilogmcp.read.server.VerifiedContentHashes hashes =
      new org.triplehelix.wpilogmcp.read.server.VerifiedContentHashes(path -> {
        hashBytes.addAndGet(java.nio.file.Files.size(path)); return StoreFiles.hash(path);
      });
  private final Object lock = new Object();
  private final Map<String, Session> sessions = new HashMap<>();
  private final Map<String, Snapshot> snapshots = new HashMap<>();
  private long accounted;
  private boolean closed;
  /**
   * Two catalog builds at a time. The limit counts builds, not threads: a build gives its permit back before it
   * completes its result, so the caller it wakes can start the next build while the build's thread is still on its
   * way back to the pool. A pool of two threads refused that next build whenever both threads were still finishing.
   * A build its caller timed out keeps its permit until it unwinds, as it keeps its reservation.
   */
  private final Semaphore buildPermits = new Semaphore(2);
  private final ThreadPoolExecutor builders = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 30, TimeUnit.SECONDS,
      new SynchronousQueue<>(), task -> { var t = new Thread(task, "replica-catalog"); t.setDaemon(true); return t; });
  /** Runs on a builder's thread once its result is complete; tests hold the thread there, as a busy machine can. */
  private volatile Runnable afterBuild = () -> {};
  void afterBuild(Runnable probe) { afterBuild = probe; }
  private final ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor(task -> {
    var t = new Thread(task, "replica-catalog-expiry"); t.setDaemon(true); return t;
  });
  public ReplicaProtocol(StoreDoor door) { this(door, Clock.systemUTC()); }
  public ReplicaProtocol(StoreDoor door, java.util.function.Consumer<Path> recovery) { this(door); this.recovery = recovery; }
  /** Handshake may register known roots; status only reads owners already admitted there. */
  public ReplicaProtocol(StoreDoor door, StoreRegistry stores) {
    this(door);
    publicationOwners = Objects.requireNonNull(stores);
    recovery = root -> {
      try { stores.store(root).recoverMasterPublication(); }
      catch (IOException ignored) { /* The durable intent remains and catalog admission refuses. */ }
    };
  }
  ReplicaProtocol(StoreDoor door, Clock clock) {
    this.door = door; this.clock = clock;
    expiry.scheduleWithFixedDelay(() -> { synchronized (lock) { expire(); } }, 1, 1, TimeUnit.SECONDS);
  }
  public Hello handshake() throws IOException {
    var available = new ArrayList<Store>();
    var selectedStores = new HashMap<String, List<StoreDoor.Selected>>();
    for (var selected : door.stores()) {
      var header = header(selected);
      if (header.replicas() == null) continue;
      if (!header.id().equals(selected.description().id()))
        throw new IOException("SOURCE_UNAVAILABLE: store identity changed");
      available.add(new Store(header.id(), header.mirror() ? header.origin().storeId() : header.id(), !header.mirror(), 2));
      if (available.size() > 64) throw new IOException("RESOURCE_LIMIT: at most 64 replica stores per server");
      selectedStores.computeIfAbsent(header.id(), ignored -> new ArrayList<>()).add(selected);
      if (publicationOwners != null) publicationOwners.store(selected.root());
    }
    selectedStores.replaceAll((id, roots) -> List.copyOf(roots));
    synchronized (lock) {
      expire(); if (closed || sessions.size() >= 64) throw new IOException("BUSY: replica peer session capacity reached");
      var session = new Session(clock.millis(), selectedStores); sessions.put(session.id, session);
      return new Hello(1, session.id, List.of("finalized_offers", "immutable_ranges", "publication_status"), List.copyOf(available));
    }
  }

  /** A structured refusal keeps the verified pending cause separate from transport/file errors. */
  public static final class StatusFailure extends IOException {
    private final JsonObject result;
    private StatusFailure(JsonObject result) {
      super(result.get("code").getAsString()); this.result = result.deepCopy();
    }
    public JsonObject result() { return result.deepCopy(); }
  }

  /**
   * Pending receipts have no immutable public file identity yet. Their session lookup therefore
   * uses an already published bounded owner snapshot, with fresh grant checks but no discovery,
   * recovery or store-queue join. A concurrent owner change asks the caller to retry.
   */
  public JsonObject status(String sessionId, String storeId, String selectedSession, int limit)
      throws IOException {
    ReplicaJournal.uuid(storeId);
    if (limit < 1 || limit > 256) throw new IllegalArgumentException("limit must be 1 to 256");
    if (selectedSession != null && (selectedSession.isBlank() || selectedSession.length() > 1024))
      throw new IllegalArgumentException("Invalid session_id");
    final StoreDoor.Selected selected;
    synchronized (lock) {
      session(sessionId);
      var choices = sessions.get(sessionId).stores.get(storeId);
      if (choices == null) throw new IOException("SOURCE_UNAVAILABLE: store was not offered by this handshake");
      if (choices.size() != 1) throw new IOException("SOURCE_SELECTION_REQUIRED: store identity has multiple configured copies");
      selected = choices.get(0);
    }
    checkStore(selected, storeId);
    var header = header(selected);
    if (!storeId.equals(header.id()) || header.replicas() == null)
      throw new IOException("SOURCE_UNAVAILABLE: selected replica store changed");
    var owner = publicationOwners == null ? null : publicationOwners.registered(selected.root()).orElse(null);
    if (owner == null) throw new IOException("BUSY: publication status is initializing");
    var snapshot = owner.publicationStatus();
    if (!storeId.equals(snapshot.storeId()))
      throw new IOException("BUSY: publication status has not observed this store identity");

    JsonObject refusal = null;
    if (selectedSession != null) {
      var pending = snapshot.entries().stream().filter(entry -> selectedSession.equals(entry.sessionId())).toList();
      if (pending.size() > 1) throw new IOException("SOURCE_SELECTION_REQUIRED: session identity has multiple pending receipts");
      if (!pending.isEmpty()) {
        refusal = statusError("PUBLICATION_PENDING", "The session is recorded; replica publication is pending.", true);
        refusal.addProperty("store_id", storeId);
        refusal.addProperty("session_id", selectedSession);
        refusal.add("cause", cause(pending.get(0).cause()));
      }
    }
    if (refusal == null && snapshot.state() != ReplicaPublications.State.READY) {
      refusal = statusError("BUSY", "Publication recovery has not produced a complete inventory.", true);
      var reason = new JsonObject();
      reason.addProperty("code", snapshot.failureCode());
      reason.addProperty("message", snapshot.failureMessage());
      refusal.add("cause", reason);
    }
    if (refusal == null && selectedSession != null && header.replicas().files().stream()
        .noneMatch(file -> selectedSession.equals(file.sessionId())))
      refusal = statusError("SOURCE_UNAVAILABLE", "The selected session is not published or pending.", false);

    var result = new JsonObject();
    if (refusal == null) {
      result.addProperty("status", "ok");
      result.addProperty("store_id", storeId);
      result.addProperty("catalog_epoch", header.replicas().epoch());
      result.addProperty("catalog_revision", header.replicas().revision());
      result.add("catalog_capacity", StoreJson.JSON.toJsonTree(ReplicaCatalogCapacity.of(header.replicas())));
      result.addProperty("publication_state", selectedSession == null ? "ready" : "published");
      if (selectedSession != null) result.addProperty("session_id", selectedSession);
      result.addProperty("pending_total", snapshot.totalPendingCount());
      result.addProperty("known_pending_count", snapshot.knownPendingCount());
      result.addProperty("oldest_failed_at", snapshot.oldestFailedAt());
      result.addProperty("oldest_time_complete", snapshot.oldestTimeComplete());
      var rows = new JsonArray();
      result.add("pending_publications", rows);
      // The allowance covers the limits envelope; every row and the completed response are
      // measured as UTF-8 JSON, including escaping, rather than estimated from Java characters.
      int bytes = ReadWire.bytes(result, PAGE_BYTES).length + 256;
      for (var entry : snapshot.entries()) {
        if (rows.size() == limit) break;
        var row = cause(entry.cause());
        row.addProperty("session_id", entry.sessionId());
        int cost = ReadWire.bytes(row, PAGE_BYTES).length + 1;
        if (bytes + cost > PAGE_BYTES) break;
        rows.add(row); bytes += cost;
      }
      if (rows.size() < snapshot.totalPendingCount()) {
        var limits = new JsonObject(); var pending = new JsonObject();
        pending.addProperty("returned", rows.size());
        pending.addProperty("total", snapshot.totalPendingCount());
        pending.addProperty("limit", limit);
        limits.add("pending_publications", pending); result.add("limits", limits);
      }
      ReadWire.bytes(result, PAGE_BYTES);
    }
    checkStore(selected, storeId);
    if (owner.publicationStatus() != snapshot || !header.equals(header(selected)))
      throw new IOException("BUSY: publication status changed during the read");
    synchronized (lock) { session(sessionId); }
    if (refusal != null) throw new StatusFailure(refusal);
    return result;
  }

  private void checkStore(StoreDoor.Selected selected, String storeId) throws IOException {
    if (!storeId.equals(door.describe(selected.root()).id()))
      throw new IOException("SOURCE_UNAVAILABLE: selected store identity changed");
  }

  private static JsonObject cause(ReplicaPublications.Cause cause) {
    var result = new JsonObject();
    result.addProperty("code", cause.code()); result.addProperty("message", cause.message());
    result.addProperty("first_failed_at", cause.firstFailedAt()); result.addProperty("failed_at", cause.failedAt());
    result.addProperty("cause_durable", cause.durable());
    return result;
  }

  private static JsonObject statusError(String code, String message, boolean retryable) {
    var result = new JsonObject();
    result.addProperty("status", "error"); result.addProperty("code", code);
    result.addProperty("message", message); result.addProperty("retryable", retryable);
    return result;
  }
  private void session(String id) throws IOException {
    expire(); var value = sessions.get(id);
    if (closed || value == null) throw new IOException("SESSION_EXPIRED: handshake again");
    value.used = clock.millis();
  }
  public Page page(String session, String store, int limit, String inventory, String cursor) throws IOException {
    if (limit < 1 || limit > 256 || store == null) throw new IllegalArgumentException("store_id and limit 1 to 256 required");
    Snapshot snapshot;
    if (inventory == null) {
      if (cursor != null) throw new IllegalArgumentException("cursor requires inventory_id");
      snapshot = build(session, store, limit);
    } else synchronized (lock) {
      session(session); snapshot = snapshots.get(inventory);
      if (snapshot == null || snapshot.retired || snapshot.offers == null || !snapshot.session.equals(session)
          || !snapshot.store.equals(store) || snapshot.limit != limit)
        throw new IOException("CURSOR_INVALID: inventory, session or selection changed");
      snapshot.users++; snapshot.used = clock.millis();
    }
    try {
      // Every replay rechecks the serving scope; a snapshot is never an authorization grant.
      if (!door.describe(snapshot.selected.root()).id().equals(store)) throw new IOException("SOURCE_UNAVAILABLE: store identity changed");
      int offset;
      synchronized (lock) {
        if (snapshot.retired || !sessions.containsKey(session)) throw new IOException("INVENTORY_EXPIRED: restart enumeration");
        offset = cursor == null ? 0 : snapshot.cursors.getOrDefault(cursor, -1);
      }
      if (offset < 0) throw new IOException("CURSOR_INVALID: unknown cursor");
      var rows = new ArrayList<Offer>(); int budget = 1024;
      for (int i = offset; i < snapshot.offers.size() && rows.size() < limit; i++) {
        var row = snapshot.offers.get(i);
        int cost = ReadWire.bytes(StoreJson.JSON.toJsonTree(row), PAGE_BYTES).length + 1;
        if (budget + cost > PAGE_BYTES) break;
        rows.add(row); budget += cost;
      }
      if (rows.isEmpty() && offset < snapshot.offers.size()) throw new IOException("RESOURCE_LIMIT: offer exceeds page budget");
      int next = offset + rows.size(); String nextCursor = null;
      synchronized (lock) {
        if (next < snapshot.offers.size()) {
          for (var entry : snapshot.cursors.entrySet()) if (entry.getValue() == next) { nextCursor = entry.getKey(); break; }
          if (nextCursor == null) { nextCursor = UUID.randomUUID().toString(); snapshot.cursors.put(nextCursor, next); }
        }
      }
      return new Page(snapshot.id, snapshot.epoch, snapshot.revision, snapshot.offers.size(), List.copyOf(rows), nextCursor);
    } finally { synchronized (lock) { release(snapshot); } }
  }
  private Snapshot build(String session, String store, int limit) throws IOException {
    final Snapshot snapshot;
    synchronized (lock) {
      session(session);
      if (snapshots.size() >= 8 || snapshots.values().stream().filter(s -> s.session.equals(session)).count() >= 2
          || accounted + BUILD_BYTES > TOTAL_BYTES) throw new IOException("BUSY: replica catalog capacity reached");
      snapshot = new Snapshot(session, store, limit, clock.millis()); snapshot.users = 1;
      snapshots.put(snapshot.id, snapshot); accounted += BUILD_BYTES;
    }
    var result = new CompletableFuture<Snapshot>();
    if (!buildPermits.tryAcquire()) {
      synchronized (lock) { snapshot.retired = true; release(snapshot); }
      throw new IOException("BUSY: two replica catalogs are building");
    }
    try {
      builders.execute(() -> {
        Throwable failure = null;
        try {
          var selected = door.select(store); var header = header(selected); var catalog = header.replicas();
          if (catalog == null) throw new IOException("MIGRATION_REQUIRED: replica protocol requires format 2");
          if (!header.mirror() && java.nio.file.Files.exists(selected.root().resolve(ReplicaMasters.PENDING))) {
            recovery.accept(selected.root()); throw new IOException("BUSY: completed master receipt is recovering; retry catalog");
          }
          var rows = new ArrayList<Offer>(); long size = 4096;
          // These are finalized identity receipts, not filesystem size/mtime guesses.
          for (var file : catalog.files()) if (file.identity() != null && file.transferVerified()) {
            var row = offer(file); size = Math.addExact(size, 1024L + 4L * ReadWire.bytes(StoreJson.JSON.toJsonTree(row), PAGE_BYTES).length);
            if (size > BUILD_BYTES) throw new IOException("RESOURCE_LIMIT: replica catalog exceeds 32 MiB accounted metadata");
            rows.add(row);
          }
          rows.sort(Comparator.comparing((Offer o) -> o.identity().fileId()).thenComparing(o -> o.identity().fileVersion()));
          synchronized (lock) {
            if (closed || snapshot.retired || clock.millis() - snapshot.born >= 10_000)
              throw new IOException("DEADLINE: catalog build expired before publication");
            snapshot.selected = selected; snapshot.epoch = catalog.epoch(); snapshot.revision = catalog.revision();
            snapshot.offers = List.copyOf(rows); accounted -= snapshot.bytes - size; snapshot.bytes = size;
          }
        } catch (Throwable problem) {
          failure = problem;
          synchronized (lock) { snapshot.retired = true; release(snapshot); }
        } finally {
          buildPermits.release(); // Before the result wakes its caller, who may start the next build at once.
        }
        if (failure == null) result.complete(snapshot); else result.completeExceptionally(failure);
        afterBuild.run();
      });
    } catch (RejectedExecutionException stopped) {
      buildPermits.release();
      synchronized (lock) { snapshot.retired = true; release(snapshot); }
      throw new IOException("BUSY: two replica catalogs are building", stopped);
    }
    try { return result.get(10, TimeUnit.SECONDS); }
    catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt(); synchronized (lock) { snapshot.retired = true; if (snapshot.offers != null) release(snapshot); }
      throw new IOException("Catalog request interrupted", interrupted);
    }
    catch (TimeoutException timedOut) {
      synchronized (lock) {
        snapshot.retired = true;
        // A completed build has handed ownership to this caller; otherwise the worker still owns it.
        if (snapshot.offers != null) release(snapshot);
      }
      throw new IOException("DEADLINE: catalog build exceeded ten seconds", timedOut);
    } catch (ExecutionException failure) { throw new IOException(failure.getCause().getMessage(), failure.getCause()); }
  }
  static Offer offer(ReplicaCatalog.Entry file) {
    String name = Path.of(file.path()).getFileName().toString();
    return new Offer(file.identity(), name, file.sha256(), file.sizeBytes(), file.kind(), file.completionBasis(),
        file.verificationBasis(), file.placement(), false);
  }
  public Payload payload(String session, String store, ReplicaCatalog.Identity identity, String hash) throws IOException {
    synchronized (lock) { session(session); }
    var selected = door.select(store); var header = header(selected);
    if (header.replicas() == null) throw new IOException("SOURCE_UNAVAILABLE: replica catalog missing");
    var file = header.replicas().files().stream().filter(f -> identity.equals(f.identity()) && hash.equals(f.sha256()) && f.transferVerified())
        .findFirst().orElseThrow(() -> new IOException("SOURCE_UNAVAILABLE: candidate is not published"));
    var path = new StoreFiles(selected.root(), selected.security()).resolve(selected.root(), file.path());
    var lease = LogFileAccess.read(path);
    try {
      var boundary = org.triplehelix.wpilogmcp.log.FileSnapshot.of(path);
      if (boundary == null || boundary.size() != file.sizeBytes())
        throw new IOException("CORRUPT_CONTENT: published candidate differs from its receipt");
      hashes.verify(path, boundary, file.sha256()); fileOpens.incrementAndGet();
      if (!door.describe(selected.root()).id().equals(store) || !header(selected).replicas().files().contains(file))
        throw new IOException("SOURCE_CHANGED: candidate changed during admission");
      return new Payload(path, file.sizeBytes(), lease);
    } catch (Throwable failure) { lease.close(); throw failure; }
  }
  private static StoreManifest.Header header(StoreDoor.Selected selected) throws IOException {
    return new StoreFiles(selected.root(), selected.security()).read(selected.root().resolve("store.json"), StoreManifest.Header.class);
  }
  public void closeInventory(String session, String inventory) throws IOException {
    synchronized (lock) {
      var snapshot = snapshots.get(inventory);
      if (snapshot == null) return;
      if (!snapshot.session.equals(session)) throw new IOException("CURSOR_INVALID: foreign inventory");
      retire(snapshot);
    }
  }
  public void closeSession(String id) {
    synchronized (lock) { sessions.remove(id); for (var s : List.copyOf(snapshots.values())) if (s.session.equals(id)) retire(s); }
  }
  private void expire() {
    long now = clock.millis();
    sessions.values().removeIf(s -> now - s.used >= 60_000 || now - s.born >= 300_000);
    for (var s : List.copyOf(snapshots.values()))
      if (!sessions.containsKey(s.session) || now - s.used >= 60_000 || now - s.born >= 300_000) retire(s);
  }
  private void retire(Snapshot snapshot) { snapshot.retired = true; if (snapshot.users == 0 && snapshots.remove(snapshot.id) != null) accounted -= snapshot.bytes; }
  private void release(Snapshot snapshot) { snapshot.users--; if (snapshot.retired) retire(snapshot); }
  public long accountedBytes() { synchronized (lock) { return accounted; } }
  public record Diagnostics(long accountedBytes, long hashBytes, long admittedFiles) {}
  public Diagnostics diagnostics() { return new Diagnostics(accountedBytes(), hashBytes.get(), fileOpens.get()); }
  @Override public void close() {
    synchronized (lock) { closed = true; sessions.clear(); for (var s : List.copyOf(snapshots.values())) retire(s); }
    builders.shutdownNow(); expiry.shutdownNow(); hashes.close();
  }
}
