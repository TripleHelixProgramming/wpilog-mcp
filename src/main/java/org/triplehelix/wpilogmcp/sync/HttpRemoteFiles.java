/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.sync;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.triplehelix.wpilogmcp.store.StoreDoor;
import org.triplehelix.wpilogmcp.store.StoreJson;
import org.triplehelix.wpilogmcp.store.StoreManifest;

/** HTTP is another read-only transport for FileTransfer, not another resume implementation. */
public final class HttpRemoteFiles implements RemoteFiles {
  private static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final URI base;
  private final String selector;
  private final HttpClient client;
  public HttpRemoteFiles(String url) { this(url, HTTP); }
  public HttpRemoteFiles(String url, HttpClient client) {
    var uri = URI.create(url);
    if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
        || uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException("Peer URL must be HTTP(S), with no credentials or fragment");
    String selected = null;
    if (uri.getRawQuery() != null) {
      var fields = uri.getRawQuery().split("=", -1);
      if (fields.length != 2 || !fields[0].equals("store") || fields[1].isBlank()) throw new IllegalArgumentException("Peer URL accepts only ?store=<id>");
      selected = URLDecoder.decode(fields[1], StandardCharsets.UTF_8);
    }
    String path = uri.getPath().replaceAll("/+$", "");
    if (path.endsWith("/store")) path = path.substring(0, path.length() - 6);
    try { base = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), path, null, null); }
    catch (URISyntaxException e) { throw new IllegalArgumentException("Invalid peer URL", e); }
    if (client.followRedirects() != HttpClient.Redirect.NEVER) throw new IllegalArgumentException("Store HTTP must not follow redirects with a credential");
    this.selector = selected; this.client = client;
  }
  public String url() { return base.toASCIIString() + (selector == null ? "" : "?store=" + encode(selector)); }
  private URI endpoint(String path, String query) {
    try {
      String encoded = new URI(null, null, "/store" + path, null).toASCIIString();
      String parameters = selector == null ? query : "store=" + encode(selector) + (query == null ? "" : "&" + query);
      return URI.create(base.toASCIIString() + encoded + (parameters == null ? "" : "?" + parameters));
    } catch (URISyntaxException e) { throw new IllegalArgumentException("Invalid store path", e); }
  }
  private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }

  public StoreDoor.Description description() throws IOException {
    var object = json("", null);
    if (object.has("stores")) throw new IOException("Peer has multiple stores or none; select one with ?store=<id>");
    var description = StoreJson.JSON.fromJson(object, StoreDoor.Description.class);
    if (description.id() == null || description.formatVersion() != StoreManifest.FORMAT_VERSION) throw new IOException("Unsupported peer store format");
    return description;
  }
  public List<StoreManifest.Robot> robots() throws IOException {
    return List.of(StoreJson.JSON.fromJson(json("/robots", null).get("robots"), StoreManifest.Robot[].class));
  }
  public StoreDoor.Sessions sessions() throws IOException {
    try {
      var sessions = StoreJson.JSON.fromJson(json("/sessions", null), StoreDoor.Sessions.class);
      if (sessions.sessions() == null || sessions.unassigned() == null) throw new IllegalArgumentException("Missing store listing");
      return sessions;
    } catch (RuntimeException e) { throw new IOException("Invalid peer session listing", e); }
  }
  @Override public List<File> list() throws IOException {
    var result = new ArrayList<File>(); var catalog = sessions();
    for (var session : catalog.sessions()) {
      for (var file : session.manifest().files()) result.add(file(session.path() + "/" + file.path(), file.sizeBytes(), file.endedAt()));
      var open = session.manifest().openCapture();
      if (open != null) result.add(file(session.path() + "/" + open.path(), open.sizeBytes(), session.manifest().endedAt()));
    }
    for (var unassigned : catalog.unassigned()) result.add(file(unassigned.path(), unassigned.file().sizeBytes(), unassigned.file().endedAt()));
    var text = new java.util.LinkedHashMap<String, File>();
    for (var session : catalog.sessions()) for (var receipt : session.manifest().systemLogs().files()) {
      String path = receipt.location() == org.triplehelix.wpilogmcp.store.SystemLogState.Location.STORE ? receipt.path() : session.path() + "/" + receipt.path();
      text.put(path, file(path, receipt.sizeBytes(), receipt.provenance().importedAt()));
    }
    for (var robot : catalog.systemLogs()) for (var entry : robot.files()) {
      var f = entry.file(); text.put(f.path(), file(f.path(), f.sizeBytes(), f.provenance().importedAt()));
    }
    result.addAll(text.values()); return List.copyOf(result);
  }
  private static File file(String path, long size, String ended) throws IOException {
    try { return new File(path, size, ended == null ? 0 : Instant.parse(ended).toEpochMilli()); }
    catch (RuntimeException e) { throw new IOException("Invalid peer file facts", e); }
  }
  @Override public byte[] read(String name, long offset, int count) throws IOException {
    if (offset < 0 || count <= 0 || count > MAX_JSON_BYTES || offset > Long.MAX_VALUE - count) throw new IllegalArgumentException("Invalid byte range");
    var request = request("/files/" + name, null).header("Range", "bytes=" + offset + "-" + (offset + count - 1)).build();
    var response = send(request, count + 1);
    {
      if (response.statusCode() != 206) throw failure(response.statusCode());
      String range = response.headers().firstValue("Content-Range").orElse("");
      if (!range.startsWith("bytes " + offset + "-")) throw new IOException("Peer returned a different range");
      var bytes = response.body();
      if (bytes.length > count) throw new IOException("Peer returned an oversized block");
      return bytes;
    }
  }
  @Override public Optional<String> prefixHash(String name, long length) throws IOException {
    if (length < 0) throw new IllegalArgumentException("Invalid prefix length");
    var result = json("/files/" + name + "/prefix-hash", "bytes=" + length);
    try {
      String hash = result.get("sha256").getAsString();
      if (result.get("bytes").getAsLong() != length || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
      return Optional.of(hash);
    } catch (RuntimeException e) { throw new IOException("Peer returned an invalid prefix hash", e); }
  }
  private JsonObject json(String path, String query) throws IOException {
    var response = send(request(path, query).build(), MAX_JSON_BYTES);
    {
      if (response.statusCode() != 200) throw failure(response.statusCode());
      byte[] bytes = response.body();
      try { return StoreJson.JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class); }
      catch (RuntimeException e) { throw new IOException("Peer returned invalid JSON", e); }
    }
  }
  private HttpRequest.Builder request(String path, String query) {
    var uri = endpoint(path, query);
    var request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).GET();
    var authorization = org.triplehelix.wpilogmcp.config.ClientLeases.getInstance().pitAuthorization(uri);
    if (authorization != null) request.header("Authorization", authorization);
    return request;
  }
  private HttpResponse<byte[]> send(HttpRequest request, int limit) throws IOException {
    var pending = client.sendAsync(request, info -> new LimitedBody(limit));
    try { return pending.get(2, java.util.concurrent.TimeUnit.MINUTES); }
    catch (InterruptedException e) {
      pending.cancel(true); Thread.currentThread().interrupt(); throw new IOException("Store transfer interrupted", e);
    } catch (java.util.concurrent.TimeoutException e) {
      pending.cancel(true); throw new IOException("Peer store response exceeded its two-minute deadline", e);
    } catch (java.util.concurrent.ExecutionException e) {
      throw new IOException("Peer store request failed: " + e.getCause().getMessage(), e.getCause());
    }
  }
  /** Cancel before retaining an oversized response; the deadline covers its body, too. */
  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> body = HttpResponse.BodySubscribers.ofByteArray();
    private final int limit;
    private java.util.concurrent.Flow.Subscription subscription;
    private long size;
    LimitedBody(int limit) { this.limit = limit; }
    @Override public java.util.concurrent.CompletionStage<byte[]> getBody() { return body.getBody(); }
    @Override public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription = value; body.onSubscribe(value); }
    @Override public void onNext(List<java.nio.ByteBuffer> blocks) {
      for (var block : blocks) size += block.remaining();
      if (size > limit) { subscription.cancel(); body.onError(new IOException("Peer response exceeds " + limit + " bytes")); }
      else body.onNext(blocks);
    }
    @Override public void onError(Throwable failure) { body.onError(failure); }
    @Override public void onComplete() { body.onComplete(); }
  }
  private static IOException failure(int status) { return new IOException("Peer store HTTP " + status); }
}
