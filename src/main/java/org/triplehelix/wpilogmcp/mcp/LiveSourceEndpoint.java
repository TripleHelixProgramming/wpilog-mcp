/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.triplehelix.wpilogmcp.capture.CaptureService;
import org.triplehelix.wpilogmcp.capture.LiveCapture;
import org.triplehelix.wpilogmcp.config.CaptureConfig;
import org.triplehelix.wpilogmcp.config.ConfigException;
import org.triplehelix.wpilogmcp.log.LogManager;

/** A laptop's live source: the capture that records the pit's NT4 gateway into a laptop store, set
 * at run time by the person's editor rather than in servers.yaml, so a window reads live data from
 * local files only. A capture configured in servers.yaml owns this server's recording: the control
 * reports it but never replaces it. Like the mirror's, these are a person's controls and absent
 * from the assistant's tools. The live tools follow whichever capture is current. */
final class LiveSourceEndpoint implements AutoCloseable {
  static final String PATH = "/store/live";
  static final String BUSY = "The live source or the mirror is already changing; retry";
  private record Running(CaptureService service, CaptureConfig config, JsonObject request, AtomicReference<String> failure) {}
  private final LogManager manager;
  private final AtomicReference<LiveCapture> live;
  private final Supplier<Path> mirrorFolder;
  private volatile Supplier<java.util.List<Path>> mirrorFolders;
  void mirrorFolders(Supplier<java.util.List<Path>> folders) { mirrorFolders = folders; }
  private final AtomicBoolean configuring;
  private volatile CaptureService configured;
  private volatile Running running;

  LiveSourceEndpoint(LogManager manager, AtomicReference<LiveCapture> live, Supplier<Path> mirrorFolder) {
    this(manager, live, mirrorFolder, new AtomicBoolean());
  }
  /** {@code configuring} is shared with the mirror's control, so neither can take a folder while the
   * other is still checking it: each refuses the other's folder, and both checks must see the same state. */
  LiveSourceEndpoint(LogManager manager, AtomicReference<LiveCapture> live, Supplier<Path> mirrorFolder, AtomicBoolean configuring) {
    this.manager = manager; this.live = live; this.mirrorFolder = mirrorFolder; this.configuring = configuring;
  }
  /** The capture servers.yaml configured; the control can no longer set one. */
  void useConfigured(CaptureService capture) { configured = capture; live.set(capture.live()); }
  /** The folder this server records into, from servers.yaml or the control, so the mirror can refuse
   * to share it. A configured capture's store is empty until its robot connects: a mirror would take it. */
  Path folder() {
    var capture = configured; if (capture != null) return capture.folder();
    var current = running; return current == null ? null : current.config().store();
  }

  /** Stopping the old capture finishes its file; the new one starts after, so two writers never
   * share the folder. The folder is admitted first, so a refusal leaves the old capture running. */
  void configure(JsonObject request) throws IOException, ConfigException {
    if (configured != null) throw new IOException("This server's capture is configured in servers.yaml; change it there");
    var config = CaptureConfig.parseLiveSource(request);
    if (!configuring.compareAndSet(false, true)) throw new IOException(BUSY);
    try {
      var old = running;
      if (old != null && old.config().equals(config)) return;
      var mirror = mirrorFolder.get();
      if (mirror != null && sameFolder(mirror, config.store())) throw new IOException("The live folder cannot be the mirror folder; choose another folder");
      var mirrors = mirrorFolders;
      if (mirrors != null) for (var folder : mirrors.get())
        if (sameFolder(folder, config.store()) || folder.startsWith(config.store()) || config.store().startsWith(folder))
          throw new IOException("The live folder cannot overlap a mirror folder; choose another folder");
      manager.stores().store(config.store()); // A session lease or permanent configuration must admit this folder.
      stop();
      var next = new CaptureService(config, manager);
      var failure = new AtomicReference<String>();
      running = new Running(next, config, request.deepCopy(), failure); live.set(next.live());
      next.start().whenComplete((ignored, error) -> { if (error != null) failure.set(String.valueOf(error.getMessage())); });
    } finally { configuring.set(false); }
  }
  private void stop() {
    var previous = running; running = null;
    if (previous == null) return;
    live.compareAndSet(previous.service().live(), null); previous.service().close();
  }
  static boolean sameFolder(Path a, Path b) {
    var x = a.toAbsolutePath().normalize(); var y = b.toAbsolutePath().normalize();
    if (x.equals(y)) return true;
    try { return Files.exists(x) && Files.exists(y) && Files.isSameFile(x, y); } catch (IOException unreadable) { return false; }
  }

  /** disabled, connecting, waiting_for_time_sync, connected (no session open yet), recording or failed. */
  Map<String, Object> status() {
    var current = running; var capture = configured;
    if (current == null && capture == null) return Map.of("state", "disabled");
    var source = capture != null ? capture.live() : current.service().live();
    var out = new LinkedHashMap<String, Object>();
    String failure = current == null ? null : current.failure().get();
    var session = source.current(); boolean synced = source.timeEstimate().isPresent();
    out.put("state", failure != null ? "failed" : !source.connected() ? "connecting" : !synced ? "waiting_for_time_sync"
        : session != null && session.open() ? "recording" : "connected");
    out.put("configured_by", capture != null ? "servers.yaml" : "live source control");
    if (current != null) out.put("configuration", current.request());
    out.put("address", source.metrics().address());
    if (!source.connected()) out.put("disconnect_reason", source.disconnectReason());
    if (failure != null) out.put("error", failure);
    if (session != null) {
      var facts = new LinkedHashMap<String, Object>();
      facts.put("file", source.resolve(session.path()).toString());
      facts.put("started_at", session.startedAt() == null ? null : session.startedAt().toString());
      facts.put("open", session.open());
      facts.put("records", session.statistics() == null ? null : session.statistics().records());
      out.put("session", facts);
    } else out.put("session", null);
    return out;
  }

  void handle(HttpExchange exchange) throws IOException {
    String route = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
    try {
      if (route.equals(PATH) && method.equals("GET")) { StoreEndpoint.send(exchange, 200, status()); return; }
      if (route.equals(PATH) && method.equals("DELETE")) {
        if (configured != null) throw new IOException("This server's capture is configured in servers.yaml; change it there");
        if (!configuring.compareAndSet(false, true)) throw new IOException(BUSY);
        try { stop(); } finally { configuring.set(false); }
        StoreEndpoint.send(exchange, 200, status()); return;
      }
      if (!route.equals(PATH + "/configure")) { StoreEndpoint.send(exchange, 404, Map.of("error", "Unknown live source control")); return; }
      if (!method.equals("POST")) { StoreEndpoint.send(exchange, 405, Map.of("error", "Live source configuration requires POST")); return; }
      byte[] bytes = RequestBodies.read(exchange, 65_536);
      if (bytes.length > 65_536) throw new IllegalArgumentException("Live source body exceeds 64 KiB");
      var body = new GsonBuilder().setStrictness(Strictness.STRICT).create().fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
      if (body == null) throw new IllegalArgumentException("Expected a JSON object");
      configure(body); StoreEndpoint.send(exchange, 202, status());
    } catch (ConfigException | RuntimeException e) { StoreEndpoint.send(exchange, 400, Map.of("error", "Invalid live source request: " + e.getMessage())); }
    catch (IOException e) { StoreEndpoint.send(exchange, 409, Map.of("error", e.getMessage())); }
  }
  @Override public void close() { stop(); }
}
