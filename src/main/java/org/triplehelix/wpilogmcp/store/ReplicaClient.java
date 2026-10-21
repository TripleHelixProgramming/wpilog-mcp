/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import com.google.gson.JsonObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.triplehelix.wpilogmcp.read.internal.ReadWire;
import org.triplehelix.wpilogmcp.sync.HttpRemoteFiles;

/** Bounded pull adapter. No redirects, cookies, credential propagation or upload operation exists.
 * Connect and per-read deadlines also cover response bodies, including a silent byte supplier. */
public final class ReplicaClient implements AutoCloseable {
  /** The reconciliation owner needs a stable code to recover expired sessions, while status
   * retains the supplier's bounded remedy and retryability instead of an unexplained HTTP code. */
  public static final class Refusal extends IOException {
    private final String code;
    private final int status;
    private final boolean retryable;
    Refusal(String code, String message, int status, boolean retryable) {
      super(code + ": " + message + " (HTTP " + status + ")");
      this.code = code; this.status = status; this.retryable = retryable;
    }
    public String code() { return code; }
    public int status() { return status; }
    public boolean retryable() { return retryable; }
  }
  private final URI base;
  private final String selection;
  private String session;
  private ReplicaProtocol.Hello hello;
  private ReplicaProtocol.Store selected;
  private String inventory;
  public ReplicaClient(String address) throws IOException {
    var normalized = URI.create(new HttpRemoteFiles(address).url());
    selection = normalized.getRawQuery() == null ? null : URLDecoder.decode(normalized.getRawQuery().substring(6), StandardCharsets.UTF_8);
    try { base = new URI(normalized.getScheme(), null, normalized.getHost(), normalized.getPort(), normalized.getPath(), null, null); }
    catch (URISyntaxException invalid) { throw new IOException("Invalid replica address", invalid); }
    renew();
  }
  private void renew() throws IOException {
    session = null; inventory = null;
    hello = decode(json("/handshake", null, ReplicaProtocol.HANDSHAKE_BYTES), ReplicaProtocol.Hello.class);
    if (hello.protocolVersion() != 1 || hello.sessionId() == null || !hello.sessionId().matches("[a-f0-9-]{36}")
        || hello.capabilities() == null || !hello.capabilities().containsAll(List.of("finalized_offers", "immutable_ranges"))
        || hello.stores() == null || hello.stores().size() > 64) throw new IOException("PROTOCOL_MISMATCH: invalid replica handshake");
    session = hello.sessionId();
    if (selected != null && !hello.stores().contains(selected)) throw new IOException("SOURCE_CHANGED: selected supplier identity changed during renewal");
  }
  public ReplicaProtocol.Hello hello() { return hello; }
  public ReplicaProtocol.Store select(String master, boolean authority) throws IOException {
    var choices = hello.stores().stream().filter(s -> selection == null || selection.equals(s.storeId()))
        .filter(s -> master == null || master.equals(s.masterStoreId())).filter(s -> !authority || s.master()).toList();
    if (choices.size() != 1) throw new IOException("SOURCE_SELECTION_REQUIRED: select one matching replica store with ?store=<id>");
    var store = choices.get(0);
    try {
      UUID.fromString(store.storeId()); UUID.fromString(store.masterStoreId());
      if (store.master() && !store.storeId().equals(store.masterStoreId()) || store.formatVersion() != 2) throw new IllegalArgumentException();
    } catch (RuntimeException invalid) { throw new IOException("PROTOCOL_MISMATCH: invalid serving store", invalid); }
    selected = store; return store;
  }
  public ReplicaProtocol.Page page(String store, String cursor) throws IOException {
    var request = new JsonObject(); request.addProperty("store_id", store); request.addProperty("limit", 256);
    if (inventory != null) request.addProperty("inventory_id", inventory);
    if (cursor != null) request.addProperty("cursor", cursor);
    var page = decode(json("/catalog", request, ReplicaProtocol.PAGE_BYTES), ReplicaProtocol.Page.class);
    if (page.inventoryId() == null || page.catalogEpoch() == null || page.catalogRevision() < 1
        || page.total() < 0 || page.total() > ReplicaCatalog.MAX_FILES || page.offers() == null || page.offers().size() > 256
        || page.nextCursor() != null && page.offers().isEmpty() || inventory != null && !inventory.equals(page.inventoryId()))
      throw new IOException("PROTOCOL_MISMATCH: invalid replica page");
    inventory = page.inventoryId(); return page;
  }
  record Listing(String epoch, long revision, int total, List<ReplicaProtocol.Offer> offers) {}
  /** Enumeration owns at most 32 MiB accounted metadata, before any slow payload work. Two
   * reconciliation coordinators bound the aggregate to 64 MiB. Expiry discards an incomplete
   * listing; it cannot relabel partial coverage or repeatedly strand later payloads. */
  Listing catalog(String store) throws IOException {
    for (int attempt = 0; ; attempt++) {
      try {
        var rows = new ArrayList<ReplicaProtocol.Offer>(); String cursor = null, epoch = null, previous = null;
        long revision = -1, accounted = 4096; int total = -1;
        do {
          var page = page(store, cursor);
          if (epoch == null) { epoch = page.catalogEpoch(); revision = page.catalogRevision(); total = page.total(); }
          if (!epoch.equals(page.catalogEpoch()) || revision != page.catalogRevision() || total != page.total())
            throw new IOException("SOURCE_CHANGED: supplier changed catalog revision between pages");
          for (var offer : page.offers()) {
            String key = offer.identity().fileId() + ":" + offer.identity().fileVersion();
            if (previous != null && key.compareTo(previous) <= 0 || rows.size() >= total)
              throw new IOException("PROTOCOL_MISMATCH: duplicate, unordered or excess offer");
            previous = key;
            accounted += 256L + 4L * ReadWire.bytes(StoreJson.JSON.toJsonTree(offer), ReplicaProtocol.PAGE_BYTES).length;
            if (accounted > 32L << 20) throw new IOException("RESOURCE_LIMIT: replica listing exceeds 32 MiB accounted metadata");
            rows.add(offer);
          }
          cursor = page.nextCursor();
        } while (cursor != null);
        if (rows.size() != total) throw new IOException("PROTOCOL_MISMATCH: incomplete catalog traversal");
        var close = new JsonObject(); close.addProperty("inventory_id", inventory);
        try { json("/close", close, 4096); } catch (IOException ignored) { /* Expiry owns abandoned handles. */ }
        inventory = null;
        return new Listing(epoch, revision, total, List.copyOf(rows));
      } catch (Refusal expired) {
        if (attempt >= 2 || !Set.of("SESSION_EXPIRED", "INVENTORY_EXPIRED", "CURSOR_INVALID").contains(expired.code())) throw expired;
        // Close an invalid inventory before renewal; a still-live old session cannot consume slots.
        close(); renew();
      }
    }
  }
  InputStream bytes(String store, ReplicaProtocol.Offer offer, long offset) throws IOException {
    return bytes(store, offer, offset, true);
  }
  private InputStream bytes(String store, ReplicaProtocol.Offer offer, long offset, boolean retry) throws IOException {
    var request = new JsonObject(); request.addProperty("store_id", store);
    request.addProperty("master_store_id", offer.identity().masterStoreId()); request.addProperty("file_id", offer.identity().fileId());
    request.addProperty("file_version", offer.identity().fileVersion()); request.addProperty("sha256", offer.sha256()); request.addProperty("offset", offset);
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    var connection = open("/file", request, deadline);
    try {
      check(connection, deadline);
      if (!offer.sha256().equals(connection.getHeaderField("X-Content-SHA256"))
          || !Long.toString(offset).equals(connection.getHeaderField("X-Replica-Offset"))
          || connection.getContentLengthLong() != offer.sizeBytes() - offset && offer.sizeBytes() != offset)
        throw new IOException("PROTOCOL_MISMATCH: immutable range identity or size changed");
      return new FilterInputStream(connection.getInputStream()) {
        @Override public void close() throws IOException { try { super.close(); } finally { connection.disconnect(); } }
      };
    } catch (Refusal expired) {
      connection.disconnect();
      if (!retry || !expired.code().equals("SESSION_EXPIRED")) throw expired;
      renew(); return bytes(store, offer, offset, false);
    } catch (IOException failure) { connection.disconnect(); throw failure; }
  }
  private JsonObject json(String route, JsonObject body, int maximum) throws IOException {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    var connection = open(route, body, deadline);
    try {
      remaining(connection, deadline);
      check(connection, deadline);
      try (var input = connection.getInputStream()) {
        byte[] bytes = new byte[maximum + 1]; int used = 0;
        while (used < bytes.length) {
          long left = deadline - System.nanoTime();
          if (left <= 0) throw new IOException("DEADLINE: replica control exceeded ten seconds");
          connection.setReadTimeout((int) Math.max(1, Math.min(10_000, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(left))));
          int count = input.read(bytes, used, Math.min(8192, bytes.length - used));
          if (count < 0) break; used += count;
        }
        if (used > maximum) throw new IOException("RESOURCE_LIMIT: oversized replica response");
        if (System.nanoTime() >= deadline) throw new IOException("DEADLINE: replica control exceeded ten seconds");
        return ReadWire.parse(Arrays.copyOf(bytes, used));
      }
    } finally { connection.disconnect(); }
  }
  private HttpURLConnection open(String route, JsonObject body) throws IOException {
    return open(route, body, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10));
  }
  private static void remaining(HttpURLConnection connection, long deadline) throws IOException {
    long left = deadline - System.nanoTime();
    if (left <= 0) throw new IOException("DEADLINE: replica control exceeded ten seconds");
    connection.setReadTimeout((int) Math.max(1, Math.min(10_000, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(left))));
  }
  private HttpURLConnection open(String route, JsonObject body, long deadline) throws IOException {
    var connection = (HttpURLConnection) URI.create(base.toASCIIString() + "/replicas/v1" + route).toURL().openConnection();
    connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(5000); connection.setReadTimeout(10_000);
    connection.setUseCaches(false);
    if (session != null) connection.setRequestProperty("Replica-Session", session);
    if (body != null) {
      byte[] bytes = ReadWire.bytes(body, 64 << 10); connection.setRequestMethod("POST");
      connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
      connection.setRequestProperty("Content-Type", "application/json");
      try {
        connection.connect(); remaining(connection, deadline);
        try (var out = connection.getOutputStream()) { out.write(bytes); }
      }
      catch (IOException failure) { connection.disconnect(); throw failure; }
    } else try { connection.connect(); remaining(connection, deadline); }
    catch (IOException failure) { connection.disconnect(); throw failure; }
    return connection;
  }
  private static void check(HttpURLConnection connection, long deadline) throws IOException {
    remaining(connection, deadline);
    int status = connection.getResponseCode();
    if (status == 200) return;
    String code = "REPLICA_HTTP_" + status, message = "supplier refused request";
    boolean retryable = false;
    try (var input = connection.getErrorStream()) {
      if (input != null) {
        byte[] bytes = new byte[4097]; int used = 0;
        while (used < bytes.length) {
          remaining(connection, deadline);
          int count = input.read(bytes, used, bytes.length - used);
          if (count < 0) break;
          used += count;
        }
        remaining(connection, deadline);
        if (used <= 4096) try {
          var body = ReadWire.parse(Arrays.copyOf(bytes, used));
          var suppliedCode = body.getAsJsonPrimitive("code");
          var suppliedMessage = body.getAsJsonPrimitive("message");
          var suppliedRetry = body.getAsJsonPrimitive("retryable");
          if (suppliedCode != null && suppliedCode.isString() && suppliedCode.getAsString().matches("[A-Z_]{2,64}")
              && suppliedMessage != null && suppliedMessage.isString() && suppliedMessage.getAsString().length() <= 2048) {
            code = suppliedCode.getAsString(); message = suppliedMessage.getAsString();
            if (message.startsWith(code + ": ")) message = message.substring(code.length() + 2);
            retryable = suppliedRetry != null && suppliedRetry.isBoolean() && suppliedRetry.getAsBoolean();
          }
        } catch (IOException | RuntimeException invalid) { /* Bounded malformed bodies retain the HTTP fallback. */ }
      }
    }
    throw new Refusal(code, message, status, retryable);
  }
  private static <T> T decode(JsonObject object, Class<T> type) throws IOException {
    try { return StoreJson.JSON.fromJson(object, type); }
    catch (RuntimeException failure) { throw new IOException("PROTOCOL_MISMATCH: malformed replica response", failure); }
  }
  @Override public void close() {
    try { json("/close", new JsonObject(), 4096); } catch (IOException ignored) { /* Server expiry owns abandoned sessions. */ }
  }
}
