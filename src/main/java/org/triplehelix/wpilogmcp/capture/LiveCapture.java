/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.triplehelix.wpilogmcp.capture.pull.PullCoordinator;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.TimeSync;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client;
import org.triplehelix.wpilogmcp.store.CaptureStore;
import org.triplehelix.wpilogmcp.store.LogStore;
import org.triplehelix.wpilogmcp.store.StoreCatalog;
import org.triplehelix.wpilogmcp.store.StoreManifest;

/**
 * Live tools read these published facts, never the filesystem or the store queue. Store writes
 * replace one manifest view; imports refresh the inventory once per job. The small wait lock
 * orders registration against delivery so the first received value cannot slip between them.
 */
public final class LiveCapture implements LogStore.Observer {
  public record SessionView(Path directory, StoreManifest.Session session, StoreManifest.Robot robot) {}
  public record Change(boolean changed, String reason, String type, ValueFrame frame) {}
  private record Held(Path directory, StoreManifest.Session session, java.time.Instant started) {
    Held(Path directory, StoreManifest.Session session) {
      this(directory, session, java.time.Instant.parse(session.startedAt()));
    }
  }
  private record Catalog(Map<String, Held> sessions, Map<String, StoreManifest.Robot> robots) {}
  private record Wait(String session, String topic, CompletableFuture<Change> result) {}
  private final Path root;
  private final ClientScheduler clock;
  private volatile Nt4Client client;
  private final Map<String, Nt4Client.LatestValue> providerValues = new ConcurrentHashMap<>();
  private final Map<String, Announce> providerTopics = new ConcurrentHashMap<>();
  private volatile List<org.triplehelix.wpilogmcp.capture.context.ProviderStatus> providers = List.of();
  public List<org.triplehelix.wpilogmcp.capture.context.ProviderStatus> providers() { return providers; }
  public void providers(List<org.triplehelix.wpilogmcp.capture.context.ProviderStatus> value) { providers = List.copyOf(value); }
  public void clearProviders() { providerValues.clear(); providerTopics.clear(); }
  public void context(String name, String type, Object value, long timestampUs, long receivedUs, com.google.gson.JsonObject metadata) {
    var topic = new Announce(name, -1, type, null, metadata);
    providerTopics.put(name, topic);
    providerValues.put(name, new Nt4Client.LatestValue(value, timestampUs, receivedUs, type));
    changed(topic, new ValueFrame(-1, timestampUs, org.triplehelix.wpilogmcp.nt4.NtType.fromNt4(type).code(), value));
  }
  public Map<String, String> providerSources() {
    var result = new HashMap<String, String>();
    providerTopics.forEach((name, topic) -> result.put(name, topic.properties().get("source").getAsString()));
    return result;
  }
  private final java.util.concurrent.atomic.AtomicLong receivedValues = new java.util.concurrent.atomic.AtomicLong();
  public long receivedValues() { return receivedValues.get(); }
  private volatile CaptureStore.Status current;
  private volatile Catalog catalog = new Catalog(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
  private volatile StoreManifest.Header header;
  private final Object waitLock = new Object();
  private final Map<String, Map<String, Wait>> waiting = new HashMap<>();
  private boolean stopped;
  private volatile Supplier<Map<String, PullCoordinator.Progress>> pull = Map::of;
  private volatile Supplier<org.triplehelix.wpilogmcp.nt4.server.GatewayStatus> gateway =
      () -> org.triplehelix.wpilogmcp.nt4.server.GatewayStatus.DISABLED;

  public LiveCapture(Path root, ClientScheduler clock) { this.root = root; this.clock = clock; }
  public void attach(Nt4Client client) { this.client = client; }
  public void attachPull(Supplier<Map<String, PullCoordinator.Progress>> progress) { pull = progress; }
  public void attachGateway(Supplier<org.triplehelix.wpilogmcp.nt4.server.GatewayStatus> status) { gateway = status; }
  public org.triplehelix.wpilogmcp.nt4.server.GatewayStatus gateway() { return gateway.get(); }
  public record Metrics(boolean connected, String address, CaptureStore.Status current,
      Map<String, Nt4Client.LatestValue> latest, TimeSync.Sample time,
      Double robotNowUs, Map<String, PullCoordinator.Progress> pull,
      List<org.triplehelix.wpilogmcp.capture.context.ProviderStatus> providers) {
    public Metrics(boolean connected, String address, CaptureStore.Status current,
        Map<String, Nt4Client.LatestValue> latest, TimeSync.Sample time, Double robotNowUs,
        Map<String, PullCoordinator.Progress> pull) { this(connected, address, current, latest, time, robotNowUs, pull, List.of()); }
  }
  /** Independent published components, just like live tools; no event-loop call or store read. */
  public Metrics metrics() {
    var value = client;
    var estimate = timeEstimate().orElse(null);
    return new Metrics(connected(), value == null ? "" : value.connectedAddress(), current,
        latest(), estimate, estimate == null ? null : (double) clock.nowUs() + estimate.offsetUs(), pull.get(), providers);
  }
  public CaptureStore.Status current() { return current; }
  public void status(CaptureStore.Status status) {
    current = status;
    if (!status.open()) endWaits("The capture session ended");
  }
  public boolean connected() { var value = client; return value != null && value.isConnected(); }
  public String disconnectReason() { var value = client; return value == null ? "Not started" : value.disconnectReason(); }
  public Map<String, Nt4Client.LatestValue> latest() {
    var value = client; var result = new HashMap<String, Nt4Client.LatestValue>(value == null ? Map.of() : value.latestValues());
    result.putAll(providerValues); return Map.copyOf(result);
  }
  public Map<String, Announce> topics() {
    var value = client; var result = new HashMap<String, Announce>(value == null ? Map.of() : value.topics());
    result.putAll(providerTopics); return Map.copyOf(result);
  }
  public java.util.Optional<org.triplehelix.wpilogmcp.nt4.TimeSync.Sample> timeEstimate() {
    var value = client; return value == null ? java.util.Optional.empty() : value.timeEstimate();
  }
  public Double robotNowUs() {
    var value = client; if (value == null) return null;
    return value.timeEstimate().map(sample -> (double) clock.nowUs() + sample.offsetUs()).orElse(null);
  }
  /** Accept either the NT4 name or its DataLogManager capture name, without guessing a suffix. */
  public String topic(String name) {
    return topics().containsKey(name) ? name : name.startsWith("NT:") ? name.substring(3) : name;
  }
  @Override public void manifest(Path path, Object value) {
    var known = catalog;
    if (value instanceof StoreManifest.Header h) header = h;
    else if (value instanceof StoreManifest.Robot robot) known.robots().put(robot.id(), robot);
    else if (value instanceof StoreManifest.Session session) known.sessions().put(session.id(), new Held(path.getParent(), session));
  }
  @Override public void inventory(StoreCatalog.Snapshot snapshot) {
    var sessions = new ConcurrentHashMap<String, Held>(); var robots = new ConcurrentHashMap<String, StoreManifest.Robot>();
    snapshot.sessions().forEach(item -> sessions.put(item.session().id(), new Held(item.path(), item.session())));
    snapshot.robots().forEach(item -> robots.put(item.robot().id(), item.robot()));
    header = snapshot.header(); catalog = new Catalog(sessions, robots);
  }
  public Path resolve(Path path) {
    var value = header; if (value == null) return path;
    var seen = new java.util.HashSet<Path>();
    while (seen.add(path)) {
      Path old = path;
      for (var move : value.moves()) if (Path.of(move.originalPath()).equals(old)) path = root.resolve(move.movedTo());
      if (path.equals(old)) break;
    }
    return path;
  }
  public List<SessionView> sessions() {
    var known = catalog;
    // ISO strings with and without fractional seconds do not sort chronologically. Parse
    // once when a manifest is published, keeping repeated live queries free of that work.
    return known.sessions().values().stream()
        .sorted(java.util.Comparator.comparing(Held::started).reversed().thenComparing(v -> v.session().id()))
        .map(item -> {
      var s = item.session(); Path directory = item.directory();
      String relative = s.openCapture() != null ? s.openCapture().path() : s.files().stream().findFirst().map(StoreManifest.LogFile::path).orElse(null);
      if (relative != null) {
        Path moved = resolve(directory.resolve(relative));
        for (int i = 0; i < Path.of(relative).getNameCount(); i++) moved = moved.getParent();
        directory = moved;
      }
      var parts = root.relativize(directory);
      var robot = parts.getNameCount() >= 2 && parts.getName(0).toString().equals("robots") ? known.robots().get(parts.getName(1).toString()) : null;
      return new SessionView(directory, s, robot);
    }).toList();
  }

  public CompletableFuture<Change> waitFor(String session, String topic, int timeoutMs) {
    var result = new CompletableFuture<Change>(); var wait = new Wait(session, topic, result);
    synchronized (waitLock) {
      if (stopped) return CompletableFuture.completedFuture(new Change(false, "The capture service is stopping", null, null));
      if (current == null || !current.open()) return CompletableFuture.completedFuture(new Change(false, "No capture session is open", null, null));
      if (!topics().containsKey(topic)) return CompletableFuture.completedFuture(new Change(false, "The topic was unannounced", null, null));
      var slots = waiting.computeIfAbsent(topic, ignored -> new HashMap<>());
      if (slots.putIfAbsent(session, wait) != null) throw new IllegalArgumentException("An outstanding wait already exists for this entry in this MCP session");
      long deadline = clock.nowUs() + timeoutMs * 1000L;
      // Enqueue before stop can close the clock. This is a nonblocking event-loop enqueue,
      // not a wait on its thread; shutdown cannot overtake the timeout's installation.
      clock.execute(() -> clock.schedule(() -> finish(wait, new Change(false, null, null, null)), Math.max(0, deadline - clock.nowUs())));
    }
    result.whenComplete((ignored, error) -> remove(wait));
    return result;
  }
  private boolean remove(Wait wait) {
    synchronized (waitLock) {
      var slots = waiting.get(wait.topic());
      boolean removed = slots != null && slots.remove(wait.session(), wait);
      if (slots != null && slots.isEmpty()) waiting.remove(wait.topic());
      return removed;
    }
  }
  private void finish(Wait wait, Change change) { if (remove(wait)) wait.result().complete(change); }
  /** Called by the same ordered listener after the writer saw this publication. */
  public void value(Announce topic, ValueFrame frame) {
    receivedValues.incrementAndGet(); changed(topic, frame);
  }
  private void changed(Announce topic, ValueFrame frame) {
    List<Wait> targets;
    synchronized (waitLock) {
      var slots = waiting.remove(topic.name());
      targets = slots == null ? List.of() : List.copyOf(slots.values());
    }
    for (var wait : targets) wait.result().complete(new Change(true, null, topic.type(), frame));
  }
  public void unannounce(String topic) {
    List<Wait> targets;
    synchronized (waitLock) { var slots = waiting.remove(topic); targets = slots == null ? List.of() : List.copyOf(slots.values()); }
    targets.forEach(wait -> wait.result().complete(new Change(false, "The topic was unannounced", null, null)));
  }
  public void endWaits(String reason) {
    var targets = new ArrayList<Wait>();
    synchronized (waitLock) { waiting.values().forEach(slots -> targets.addAll(slots.values())); waiting.clear(); }
    targets.forEach(wait -> wait.result().complete(new Change(false, reason, null, null)));
  }
  /** Close admission before the client closes its scheduler, including waits racing shutdown. */
  public void stop() {
    synchronized (waitLock) { stopped = true; }
    endWaits("The capture service is stopping");
  }
  /** Visible for ordering tests; it reads no file and waits for no event-loop task. */
  public int pendingWaits() { synchronized (waitLock) { return waiting.values().stream().mapToInt(Map::size).sum(); } }
}
