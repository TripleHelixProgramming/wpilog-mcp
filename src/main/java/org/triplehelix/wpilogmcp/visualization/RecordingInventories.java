/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.triplehelix.wpilogmcp.mcp.CallerContext.Authority;

/** Workspace ownership for detached recording inventories. Locks only claim/reclaim accounting;
 * native admission, grant checks, encoding and completion never run under the registry lock.
 * A timed-out OS read keeps its build reservation until it really unwinds. */
public final class RecordingInventories implements AutoCloseable {
  public static final int MAX_HANDLES = 8, MAX_SESSION_HANDLES = 2, MAX_OCCURRENCES = 200_000;
  public static final int MAX_ROWS = 256, MAX_DETAILS = 32, MAX_RESPONSES = 4, MAX_BUILDERS = 2;
  public static final int MAX_REQUEST_BYTES = 64 << 10, MAX_MCP_BATCH_REPLY_BYTES = 8 << 20;
  public static final long INVENTORY_BYTES = 32L << 20, TOTAL_BYTES = 64L << 20;
  public static final Duration IDLE = Duration.ofSeconds(60), LIFETIME = Duration.ofMinutes(5), BUILD_DEADLINE = Duration.ofSeconds(10);
  public record Client(Authority authority, String id, BooleanSupplier alive) {
    public Client { Objects.requireNonNull(authority); Objects.requireNonNull(id); Objects.requireNonNull(alive); }
    public static Client current(Authority authority) {
      var session = org.triplehelix.wpilogmcp.mcp.SessionContext.current();
      if (session != null) return new Client(authority, session.getId(), session::active);
      return new Client(authority, "stdio", () -> authority == Authority.STDIO_LOCAL);
    }
    void check() throws IOException {
      VisualizationAccess.require(authority, VisualizationAccess.Operation.READ); if (!alive.getAsBoolean()) throw InventoryFailure.expired();
    }
  }
  @FunctionalInterface interface Admissions { RecordedSources.Admission open(JsonObject ref) throws IOException; }
  record Limits(long inventoryBytes, long totalBytes, int handles, int sessionHandles, long idleNanos, long lifetimeNanos, long buildNanos) {
    static Limits defaults() { return new Limits(INVENTORY_BYTES, TOTAL_BYTES, MAX_HANDLES, MAX_SESSION_HANDLES, IDLE.toNanos(), LIFETIME.toNanos(), BUILD_DEADLINE.toNanos()); }
  }
  private final Object lock = new Object();
  private final Map<String, Slot> available = new HashMap<>();
  private final Set<Slot> retained = new HashSet<>();
  private final String epoch = UUID.randomUUID().toString();
  private final byte[] secret = new byte[32];
  private final Admissions admissions;
  private final Limits limits;
  private final LongSupplier nanoTime;
  /**
   * Two inventory builds at a time. The limit counts builds, not threads: a build gives its permit back before it
   * completes its result, so the caller it wakes can start the next build while the build's thread is still on its
   * way back to the pool. A pool of two threads refused that next build whenever both threads were still finishing.
   */
  private final Semaphore buildPermits = new Semaphore(MAX_BUILDERS);
  private final ThreadPoolExecutor builders;
  /** Runs on a builder's thread once its result is complete; tests hold the thread there, as a busy machine can. */
  private volatile Runnable afterBuild = () -> {};
  void afterBuild(Runnable probe) { afterBuild = probe; }
  private final ScheduledExecutorService expiry;
  private final Semaphore responses = new Semaphore(MAX_RESPONSES);
  private long bytes;
  private boolean closed;
  private static final class Slot {
    final String id; final Client client; final long created;
    long touched, charge; int readers;
    boolean building = true, retired;
    volatile boolean cancelled;
    volatile Thread worker;
    DetachedInventory inventory;
    Slot(String id, Client client, long now, long charge) { this.id = id; this.client = client; created = touched = now; this.charge = charge; }
  }
  public RecordingInventories(RecordedSources sources) { this(sources::admitRecording, Limits.defaults(), System::nanoTime); }
  RecordingInventories(Admissions admissions, Limits limits, LongSupplier nanoTime) {
    this.admissions = admissions; this.limits = limits; this.nanoTime = nanoTime; new SecureRandom().nextBytes(secret);
    builders = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
      var thread = new Thread(task, "recording-inventory-build"); thread.setDaemon(true); return thread;
    });
    expiry = Executors.newSingleThreadScheduledExecutor(task -> { var thread = new Thread(task, "recording-inventory-expiry"); thread.setDaemon(true); return thread; });
    expiry.scheduleWithFixedDelay(this::expire, 1, 1, TimeUnit.SECONDS);
  }
  public JsonObject capabilities() {
    var out = new JsonObject(); out.addProperty("max_handles", MAX_HANDLES); out.addProperty("max_handles_per_session", MAX_SESSION_HANDLES);
    out.addProperty("max_inventory_bytes", INVENTORY_BYTES); out.addProperty("max_total_inventory_bytes", TOTAL_BYTES);
    out.addProperty("max_member_entry_occurrences", MAX_OCCURRENCES); out.addProperty("max_reply_bytes", InventoryWire.BYTES);
    out.addProperty("max_request_bytes", InventoryWire.REQUEST_BYTES); out.addProperty("max_page_rows", MAX_ROWS); out.addProperty("max_detail_rows", MAX_DETAILS);
    out.addProperty("max_mcp_batch_reply_bytes", MAX_MCP_BATCH_REPLY_BYTES);
    out.addProperty("max_concurrent_requests", MAX_RESPONSES); out.addProperty("max_response_pool_bytes", 2L * MAX_RESPONSES * InventoryWire.BYTES);
    out.addProperty("max_build_workers", MAX_BUILDERS); out.addProperty("build_deadline_ms", BUILD_DEADLINE.toMillis());
    out.addProperty("idle_expiry_ms", IDLE.toMillis()); out.addProperty("absolute_expiry_ms", LIFETIME.toMillis());
    out.addProperty("max_filter_codepoints", 256); out.addProperty("max_exact_types", 16); return out;
  }
  public static String mode(JsonObject request) {
    String mode = request.has("inventory_mode") ? InventoryWire.text(request, "inventory_mode") : "full";
    if (!Set.of("full", "paged").contains(mode)) throw new IllegalArgumentException("inventory_mode must be full or paged");
    return mode;
  }
  public static void checkRequest(JsonObject request) throws IOException { InventoryWire.request(request); }
  public final class Response implements AutoCloseable {
    final AtomicBoolean released = new AtomicBoolean(); boolean retained;
    Response() throws InventoryFailure {
      if (!responses.tryAcquire()) throw InventoryFailure.limit("Inventory response capacity is busy; retry after 1000 ms", true);
    }
    public JsonObject finish(JsonObject value) throws InventoryFailure {
      InventoryWire.size(value); retained = InventoryResponses.retain(this::release); return value;
    }
    void release() { if (released.compareAndSet(false, true)) responses.release(); }
    @Override public void close() { if (!retained) release(); }
  }
  /** Library controls share the existing encoded-response pool, held until the transport finishes. */
  public Response reserveMetadataResponse() throws InventoryFailure { return new Response(); }
  public JsonObject open(Client client, JsonObject source) throws Exception {
    client.check(); InventoryWire.request(source); var ref = VisualizationContracts.source(source);
    if (!VisualizationContracts.kind(ref).equals("session")) throw new IllegalArgumentException("Paged inventory requires an anchored recording source_ref");
    try (var response = new Response()) {
      Slot slot;
      synchronized (lock) {
        expireLocked(); if (closed || !client.alive().getAsBoolean()) throw InventoryFailure.expired();
        if (retained.size() >= limits.handles() || retained.stream().filter(s -> s.client.id().equals(client.id())).count() >= limits.sessionHandles())
          throw InventoryFailure.limit("Inventory handle capacity is in use; close an inventory or retry after 1000 ms", true);
        if (limits.inventoryBytes() > limits.totalBytes() - bytes) throw InventoryFailure.limit("Inventory metadata reservation is busy; retry after 1000 ms", true);
        slot = new Slot(handle(client.id()), client, nanoTime.getAsLong(), limits.inventoryBytes());
        slot.readers = 1; retained.add(slot); bytes += slot.charge;
      }
      var result = new CompletableFuture<DetachedInventory>();
      if (!buildPermits.tryAcquire()) {
        synchronized (lock) { slot.building = false; slot.readers--; retire(slot); }
        throw InventoryFailure.limit("Both inventory builders are busy; retry after 1000 ms", true);
      }
      try { builders.execute(() -> build(slot, ref, result)); }
      catch (RejectedExecutionException stopped) {
        buildPermits.release();
        synchronized (lock) { slot.building = false; slot.readers--; retire(slot); }
        throw InventoryFailure.limit("Both inventory builders are busy; retry after 1000 ms", true);
      }
      try {
        var inventory = result.get(limits.buildNanos(), TimeUnit.NANOSECONDS);
        var out = inventory.summary.deepCopy(); out.addProperty("inventory_mode", "paged"); out.addProperty("server_epoch", epoch);
        out.addProperty("inventory_id", slot.id); out.add("source_ref", inventory.source.deepCopy()); out.addProperty("clock_basis_time", "inventory_creation");
        out.addProperty("total_entry_names", inventory.rows.size()); out.addProperty("conflicting_entry_names", inventory.conflicting);
        out.addProperty("unavailable_entry_names", inventory.unavailable); out.addProperty("expires_at", java.time.Instant.now().plusNanos(Math.max(0, limits.lifetimeNanos() - (nanoTime.getAsLong() - slot.created))).toString());
        out.addProperty("idle_expiry_ms", TimeUnit.NANOSECONDS.toMillis(limits.idleNanos())); out.add("limits", capabilities());
        client.check(); inventory.access.check();
        synchronized (lock) { if (slot.retired) throw InventoryFailure.expired(); }
        return response.finish(out);
      } catch (TimeoutException timedOut) {
        cancel(slot); throw InventoryFailure.limit("Inventory construction exceeded 10000 ms; retry after 1000 ms or choose a smaller recording", true);
      } catch (Exception failure) {
        cancel(slot);
        if (failure instanceof ExecutionException execution && execution.getCause() instanceof Exception cause) throw cause;
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        throw failure;
      } finally { synchronized (lock) { slot.readers--; reclaim(slot); } }
    }
  }
  private void build(Slot slot, JsonObject source, CompletableFuture<DetachedInventory> result) {
    slot.worker = Thread.currentThread(); DetachedInventory inventory = null; Throwable failure = null;
    try (var admission = admissions.open(source)) {
      inventory = DetachedInventory.build(source, admission, limits.inventoryBytes(), () -> slot.cancelled || !slot.client.alive().getAsBoolean());
    } catch (Throwable problem) { failure = problem; }
    // The native prefix is closed before a handle can become visible or a future can complete.
    try {
      synchronized (lock) {
        slot.building = false;
        boolean cancelled = slot.cancelled || closed || !slot.client.alive().getAsBoolean();
        boolean overdue = nanoTime.getAsLong() - slot.created >= limits.buildNanos();
        if (failure != null || cancelled || overdue) {
          retire(slot);
          if (cancelled) failure = InventoryFailure.expired();
          else if (overdue) failure = InventoryFailure.limit("Inventory construction exceeded 10000 ms; retry after 1000 ms or choose a smaller recording", true);
        } else {
          bytes -= slot.charge - inventory.bytes; slot.charge = inventory.bytes;
          slot.inventory = inventory; available.put(slot.id, slot);
        }
      }
      slot.worker = null;
    } finally {
      buildPermits.release(); // Before the result wakes its caller, who may start the next build at once.
    }
    if (failure == null) result.complete(inventory); else result.completeExceptionally(failure);
    afterBuild.run();
    Thread.interrupted();
  }
  private void cancel(Slot slot) {
    synchronized (lock) { retire(slot); }
    var worker = slot.worker; if (worker != null) worker.interrupt();
  }
  private final class Use implements AutoCloseable {
    final Slot slot; final DetachedInventory inventory;
    Use(Client client, String id) throws IOException {
      client.check(); validateHandle(client, id);
      synchronized (lock) {
        expireLocked(); slot = available.get(id);
        if (closed || slot == null || slot.retired) throw InventoryFailure.expired();
        slot.readers++; inventory = slot.inventory;
      }
      try { inventory.access.check(); client.check(); }
      catch (Exception failure) { synchronized (lock) { retire(slot); } close(); throw failure; }
    }
    void checked(Client client) throws IOException {
      client.check();
      try { inventory.access.check(); }
      catch (IOException failure) { synchronized (lock) { retire(slot); } throw failure; }
      synchronized (lock) { expireLocked(); if (slot.retired) throw InventoryFailure.expired(); slot.touched = nanoTime.getAsLong(); }
    }
    @Override public void close() { synchronized (lock) { slot.readers--; reclaim(slot); } }
  }
  private record Filter(String query, List<String> types, boolean unavailable, int limit) {
    boolean matches(DetachedInventory.Row row) {
      return row.unavailable() == unavailable && (unavailable || row.name().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))
          && (types.isEmpty() || row.declarations().stream().anyMatch(d -> types.contains(d.type()))));
    }
    JsonObject json() {
      var out = new JsonObject(); out.addProperty("query", query); var list = new JsonArray(); types.forEach(list::add); out.add("types", list);
      out.addProperty("unavailable", unavailable); out.addProperty("limit", limit); return out;
    }
  }
  private static Filter filter(JsonObject request) {
    String query = request.has("query") ? InventoryWire.text(request, "query") : "";
    if (query.codePointCount(0, query.length()) > 256) throw new IllegalArgumentException("query exceeds 256 Unicode code points");
    var types = new TreeSet<String>();
    if (request.has("types")) {
      if (!request.get("types").isJsonArray() || request.getAsJsonArray("types").size() > 16) throw new IllegalArgumentException("types must contain at most 16 exact types");
      for (var element : request.getAsJsonArray("types")) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("types must contain strings");
        types.add(element.getAsString());
      }
    }
    boolean unavailable = false;
    if (request.has("unavailable")) {
      if (!request.get("unavailable").isJsonPrimitive() || !request.getAsJsonPrimitive("unavailable").isBoolean()) throw new IllegalArgumentException("unavailable must be boolean");
      unavailable = request.get("unavailable").getAsBoolean();
    }
    int limit = MAX_ROWS;
    if (request.has("limit")) {
      try { limit = request.get("limit").getAsBigDecimal().intValueExact(); }
      catch (RuntimeException invalid) { throw new IllegalArgumentException("limit must be an integer"); }
      if (!request.get("limit").isJsonPrimitive() || !request.getAsJsonPrimitive("limit").isNumber()) throw new IllegalArgumentException("limit must be an integer");
    }
    if (limit < 1 || limit > MAX_ROWS) throw new IllegalArgumentException("limit must be between 1 and 256");
    if (unavailable && (!query.isEmpty() || !types.isEmpty())) throw new IllegalArgumentException("Unavailable rows cannot be searched by truncated text");
    return new Filter(query, List.copyOf(types), unavailable, limit);
  }
  public JsonObject list(Client client, JsonObject request) throws Exception {
    InventoryWire.request(request); fields(request, "api_version", "inventory_id", "cursor", "query", "types", "unavailable", "limit");
    String id = InventoryWire.text(request, "inventory_id");
    try (var response = new Response(); var use = new Use(client, id)) {
      Filter filter; int start = 0;
      if (request.has("cursor")) {
        if (request.keySet().stream().anyMatch(k -> !Set.of("inventory_id", "cursor", "api_version").contains(k))) throw new IllegalArgumentException("A continuation cannot change its filter or limit");
        var cursor = cursor(InventoryWire.text(request, "cursor"));
        if (!epoch.equals(InventoryWire.text(cursor, "epoch")) || !id.equals(InventoryWire.text(cursor, "inventory_id"))) throw new IllegalArgumentException("Cursor belongs to another inventory");
        filter = filter(cursor.getAsJsonObject("filter")); start = cursor.get("position").getAsInt();
        if (start < 0 || start > use.inventory.rows.size()) throw new IllegalArgumentException("Invalid cursor position");
      } else filter = filter(request);
      int total = 0; for (var row : use.inventory.rows) if (filter.matches(row)) total++;
      var out = page(id, use.inventory, filter, total); var entries = out.getAsJsonArray("entries");
      var maximumCursor = new JsonObject(); maximumCursor.addProperty("epoch", epoch); maximumCursor.addProperty("inventory_id", id);
      maximumCursor.add("filter", filter.json()); maximumCursor.addProperty("position", MAX_OCCURRENCES);
      String reservedCursor = sign(InventoryWire.json(maximumCursor));
      var continuation = new JsonObject(); continuation.addProperty("inventory_id", id); continuation.addProperty("cursor", reservedCursor);
      try { InventoryWire.continuation(continuation); }
      catch (InventoryFailure large) { throw new IllegalArgumentException("Filter is too large for a 64 KiB continuation; shorten the query or exact types"); }
      int encoded = InventoryWire.size(out) + reservedCursor.length() + 512;
      int position = start;
      for (; position < use.inventory.rows.size(); position++) {
        var row = use.inventory.rows.get(position); if (!filter.matches(row)) continue;
        if (entries.size() >= filter.limit()) break;
        if (encoded + row.wireBytes() > InventoryWire.BYTES) break;
        encoded += row.wireBytes(); entries.add(row.json());
      }
      if (entries.isEmpty() && position < use.inventory.rows.size()) throw InventoryFailure.limit("One row cannot fit the requested page", false);
      String next = null;
      if (position < use.inventory.rows.size()) {
        var value = new JsonObject(); value.addProperty("epoch", epoch); value.addProperty("inventory_id", id);
        value.add("filter", filter.json()); value.addProperty("position", position); next = sign(InventoryWire.json(value));
      }
      out.addProperty("returned", entries.size()); out.addProperty("complete", next == null); out.addProperty("next_cursor", next);
      use.checked(client); return response.finish(out);
    }
  }
  private JsonObject page(String id, DetachedInventory inventory, Filter filter, int total) {
    var out = new JsonObject(); out.addProperty("inventory_id", id); out.addProperty("server_epoch", epoch);
    out.addProperty("total_entry_names", inventory.rows.size()); out.addProperty("total_matching", total);
    out.addProperty("unavailable_entry_names", inventory.unavailable); out.addProperty("unavailable_excluded", !filter.unavailable());
    out.addProperty("limit", filter.limit()); out.add("filter", filter.json()); out.add("entries", new JsonArray()); return out;
  }
  public JsonObject details(Client client, JsonObject request) throws Exception {
    InventoryWire.request(request); fields(request, "api_version", "inventory_id", "row_ids");
    if (!request.has("row_ids") || !request.get("row_ids").isJsonArray() || request.getAsJsonArray("row_ids").isEmpty()
        || request.getAsJsonArray("row_ids").size() > MAX_DETAILS) throw new IllegalArgumentException("row_ids must contain 1 to 32 selected IDs");
    var ids = new ArrayList<String>(); for (var element : request.getAsJsonArray("row_ids")) {
      if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString() || ids.contains(element.getAsString())) throw new IllegalArgumentException("row_ids must contain distinct strings");
      ids.add(element.getAsString());
    }
    String id = InventoryWire.text(request, "inventory_id");
    try (var response = new Response(); var use = new Use(client, id); var admission = admissions.open(use.inventory.source)) {
      var out = use.inventory.details(admission, ids); out.addProperty("inventory_id", id); out.addProperty("server_epoch", epoch);
      use.checked(client); return response.finish(out);
    }
  }
  public JsonObject release(Client client, JsonObject request) throws IOException {
    client.check(); InventoryWire.request(request); fields(request, "api_version", "inventory_id");
    String id = InventoryWire.text(request, "inventory_id"); validateHandle(client, id);
    synchronized (lock) { var slot = available.get(id); if (slot != null) retire(slot); }
    var out = new JsonObject(); out.addProperty("inventory_id", id); out.addProperty("closed", true); return out;
  }
  /** Session removal invokes this under its registry lock: metadata only, no I/O/completion. */
  public void releaseSession(String session) {
    synchronized (lock) { for (var slot : List.copyOf(retained)) if (slot.client.id().equals(session)) retire(slot); }
  }
  private void retire(Slot slot) { slot.retired = true; slot.cancelled = true; available.remove(slot.id); reclaim(slot); }
  private void reclaim(Slot slot) {
    if (slot.retired && !slot.building && slot.readers == 0 && retained.remove(slot)) { bytes -= slot.charge; slot.inventory = null; }
  }
  void expire() { synchronized (lock) { expireLocked(); } }
  private void expireLocked() {
    long now = nanoTime.getAsLong();
    for (var slot : List.copyOf(retained)) if (!slot.client.alive().getAsBoolean()
        || now - slot.created >= limits.lifetimeNanos() || now - slot.touched >= limits.idleNanos()) retire(slot);
  }
  long retainedBytes() { synchronized (lock) { return bytes; } }
  int retainedHandles() { synchronized (lock) { return retained.size(); } }
  int responseBytes() { return (MAX_RESPONSES - responses.availablePermits()) * 2 * InventoryWire.BYTES; }
  private String handle(String owner) {
    String value = epoch + "." + UUID.randomUUID(); return value + "." + mac(owner + "\n" + value);
  }
  private void validateHandle(Client client, String id) throws IOException {
    var parts = id.split("\\.", -1);
    if (parts.length != 3 || id.length() > 160) throw new IllegalArgumentException("Invalid inventory_id");
    if (!parts[0].equals(epoch)) throw InventoryFailure.expired();
    if (!equal(parts[2], mac(client.id() + "\n" + parts[0] + "." + parts[1]))) throw new java.nio.file.AccessDeniedException("Inventory belongs to another session or its identity is invalid");
  }
  private String sign(String json) {
    String value = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8)); return value + "." + mac(value);
  }
  private JsonObject cursor(String token) {
    var parts = token.split("\\.", -1);
    if (parts.length != 2 || token.length() > InventoryWire.REQUEST_BYTES || !equal(parts[1], mac(parts[0]))) throw new IllegalArgumentException("Invalid or tampered inventory cursor");
    try { return VisualizationContracts.parse(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)); }
    catch (Exception invalid) { throw new IllegalArgumentException("Invalid inventory cursor"); }
  }
  private String mac(String value) {
    try { var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException impossible) { throw new AssertionError(impossible); }
  }
  private static boolean equal(String a, String b) { return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
  private static void fields(JsonObject request, String... names) {
    var allowed = Set.of(names); for (String key : request.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown inventory field " + key);
  }
  @Override public void close() {
    synchronized (lock) { closed = true; for (var slot : List.copyOf(retained)) retire(slot); }
    expiry.shutdownNow(); builders.shutdownNow();
  }
}
