/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.capture.LiveCapture;
import org.triplehelix.wpilogmcp.capture.pull.SftpTransport;
import org.triplehelix.wpilogmcp.config.CaptureConfig;
import org.triplehelix.wpilogmcp.config.ProviderConfig;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;
import org.triplehelix.wpilogmcp.ssh.SharedSsh;
import org.triplehelix.wpilogmcp.ssh.SshConnection;
import org.triplehelix.wpilogmcp.store.LogStore;

/**
 * The loop drains bounded immutable results; host, sample and tail workers own all SSH waits.
 * Providers share the robot's presence, not the puller's disabled-state gate.
 */
public final class ContextProviders implements AutoCloseable {
  private record Reading(SshConnection connection, long episode, StatsProvider.Result value, long dropped, long period) {}
  private static final class Follower {
    final ProviderConfig.Tail config;
    final TailProvider provider;
    volatile SharedSsh.Host host;
    long records, bytes;
    Follower(ProviderConfig.Tail config, TailProvider provider) { this.config = config; this.provider = provider; }
  }
  private final CaptureConfig config;
  private final CaptureWriter writer;
  private final LiveCapture live;
  private final ClientScheduler loop, worker;
  private final LogStore store;
  private final Clock wall;
  private final SharedSsh ssh;
  private final List<Follower> tails = new ArrayList<>();
  private final List<PhotonVisionProvider> photons = new ArrayList<>();
  private final AtomicBoolean stopped = new AtomicBoolean(), deliveryQueued = new AtomicBoolean();
  private final AtomicReference<Reading> pending = new AtomicReference<>();
  private final StatsProvider stats;
  private final JvmProvider jvm;
  private String robotAddress;
  private volatile SharedSsh.Host robot;
  private volatile boolean connected, open;
  private final java.util.concurrent.atomic.AtomicLong episode = new java.util.concurrent.atomic.AtomicLong();
  private long sampledEpisode;
  private volatile long periodUs;
  private volatile ProviderStatus statsStatus;
  private volatile String statsState = "offline", statsReason = "NT4 is disconnected";
  private SshConnection sampledConnection;
  private volatile CaptureWriter.Session session;
  private CaptureWriter.Session blockedStatsSession;
  private String blockedStatsReason;
  private ProviderStatus.KernelClock kernel;
  private long records, bytes, sampleBytes, nextSample;
  private final java.util.Set<Long> programPids = new java.util.TreeSet<>();
  private String reportedStatsState, reportedStatsReason;

  public ContextProviders(CaptureConfig config, CaptureWriter writer, LiveCapture live, ClientScheduler loop,
      LogStore store, Clock wall) {
    this(config, writer, live, loop, store, wall, new SharedSsh(store::hostKey), ClientScheduler.daemon("ssh-stats"));
  }
  public ContextProviders(CaptureConfig config, CaptureWriter writer, LiveCapture live, ClientScheduler loop,
      LogStore store, Clock wall, SharedSsh ssh, ClientScheduler worker) {
    this.config = config; this.writer = writer; this.live = live; this.loop = loop; this.store = store;
    this.wall = wall; this.ssh = ssh; this.worker = worker;
    periodUs = config.providers().stats().periodUs();
    stats = new StatsProvider(config.providers().stats(), config.pull().directories(), loop::nowUs,
        () -> live.timeEstimate().map(s -> (double) s.offsetUs()).orElse(null));
    for (var tail : config.providers().tails()) {
      var slot = new AtomicReference<Follower>();
      var provider = new TailProvider(tail, () -> {
        var host = slot.get().host; return host == null ? null : host.connection();
      }, loop::nowUs, () -> periodUs, () -> open, ClientScheduler.daemon("ssh-tail-control-" + tail.role()));
      provider.onActivity(this::requestDrain);
      var follower = new Follower(tail, provider); slot.set(follower); tails.add(follower);
      if (tail.host() != null) follower.host = host(tail.host(), tail.ssh());
    }
    jvm = config.providers().jvm() == null ? null : new JvmProvider(config.providers().jvm(), live::robotNowUs,
        () -> live.timeEstimate().map(t -> (double) t.roundTripUs()).orElse(null), this::writeJvm, this::requestDrain);
    for (var address : config.providers().photonvision()) {
      var owner = new AtomicReference<PhotonVisionProvider>();
      var provider = new PhotonVisionProvider(address, live::robotNowUs, (target, timestamp, cameras, metadata) -> {
        var done = new CompletableFuture<Void>();
        if (stopped.get()) { done.complete(null); return done; }
        try { loop.execute(() -> {
          try {
            if (!stopped.get() && writer.session() == target && open) {
              for (var camera : cameras) owner.get().recorded(record("/Daemon/PhotonVision/" + camera.name() + "/Settings",
                  "json", camera.settings().toString(), timestamp, metadata));
              publish();
            }
            done.complete(null);
          } catch (Exception e) { done.completeExceptionally(e); }
        }); } catch (java.util.concurrent.RejectedExecutionException e) { done.complete(null); }
        return done;
      }, this::requestDrain);
      owner.set(provider); photons.add(provider);
    }
  }
  /** Session admission is checked again on delivery, after any intervening capture-loop backlog. */
  java.util.concurrent.CompletionStage<Void> writeJvm(Object target, JvmProvider.Sample sample) {
    var done = new CompletableFuture<Void>();
    if (stopped.get()) { done.complete(null); return done; }
    try { loop.execute(() -> {
      try {
        if (!stopped.get() && writer.session() == target && open) {
          for (var value : new java.util.TreeMap<>(sample.values()).entrySet()) jvm.recorded(record("/Daemon/JVM/" + value.getKey(),
              value.getValue() instanceof Long ? "int64" : "double", value.getValue(), sample.timestampUs(), sample.metadata()));
          if (sample.runtime() != null) jvm.recorded(record("/Daemon/JVM/Runtime", "json", sample.runtime().toString(), sample.timestampUs(), sample.metadata()));
          if (sample.note() != null) jvm.recorded(record("/Daemon/JVM/ClockNote", "json", sample.note().toString(), sample.timestampUs(), sample.metadata()));
          publish();
        }
        done.complete(null);
      } catch (Exception e) { done.completeExceptionally(e); }
    }); } catch (java.util.concurrent.RejectedExecutionException e) { done.complete(null); }
    return done;
  }
  private SharedSsh.Host host(String address, org.triplehelix.wpilogmcp.config.PullConfig.Ssh settings) {
    var host = ssh.host(address, settings);
    host.onConnect(connection -> {
      store.recordHostKey(address, connection.fingerprint()).exceptionally(error -> {
        LoggerFactory.getLogger(ContextProviders.class).warn("Could not persist SSH fingerprint for {}: {}", address, error.getMessage()); return null;
      });
    });
    return host;
  }
  public void start() {
    tails.forEach(t -> t.provider.start()); worker.execute(this::sample); loop.execute(this::drain);
  }
  /** Only the ordered NT4 listener calls presence/session methods. */
  public void connected(String address) {
    connected = true; robotAddress = address; episode.incrementAndGet();
    if (config.providers().robotSsh() || config.pull().active()) {
      robot = host(address, config.pull().ssh()); robot.required(true);
    }
    for (var tail : tails) {
      if (tail.config.host() == null) tail.host = robot;
      if (tail.host != null) tail.host.required(true);
    }
  }
  public void disconnected() {
    connected = false; open = false;
    photons.forEach(p -> p.session(session, false));
    if (jvm != null) jvm.session(session, false, robotAddress);
    if (robot != null) robot.required(false);
    tails.forEach(t -> { if (t.host != null) t.host.required(false); });
    publish();
  }
  public void sessionChanged() {
    var current = writer.session(); open = connected && current != null && current.open();
    if (current != session) {
      if (session != null) episode.incrementAndGet();
      session = current; kernel = null; statsStatus = null; live.clearProviders(); records = 0; bytes = 0; sampleBytes = 0; programPids.clear();
      tails.forEach(t -> { t.records = 0; t.bytes = 0; });
    }
    tails.forEach(t -> t.provider.session(current));
    photons.forEach(p -> p.session(current, open));
    if (jvm != null) jvm.session(current, open, robotAddress);
  }
  /** A borrowed SFTP channel never closes the shared connection. Pull still applies its own gate. */
  public SftpTransport pull(String address, org.triplehelix.wpilogmcp.config.PullConfig settings, String pin) throws IOException {
    var current = robot;
    var connection = current == null || !current.address().equals(address) ? null : current.connection();
    if (connection == null) throw new IOException("Shared SSH is waiting for " + address);
    return SftpTransport.using(connection, settings.enabled() ? settings.directories() : List.of(), address, false);
  }
  private void sample() {
    if (stopped.get()) return;
    try {
      var host = robot; var connection = host == null ? null : host.connection();
      if (connection == null) { sampledConnection = null; statsState = "offline"; statsReason = "SSH or NT4 is disconnected"; return; }
      long generation = episode.get();
      if (sampledConnection != connection || sampledEpisode != generation) {
        sampledConnection = connection; sampledEpisode = generation; stats.reconnect(); nextSample = 0;
        // Device identity is useful even when pulling is disabled or the robot is enabled.
        try (var remote = SftpTransport.using(connection, config.pull().directories(), host.address(), false)) {
          var identity = remote.identity();
          store.identify(identity, wall).whenComplete((ignored, error) -> {
            if (error != null) LoggerFactory.getLogger(ContextProviders.class).warn("SSH identity: {}", error.getMessage());
            else if (!stopped.get()) loop.execute(() -> {
              if (connected && robot == host && host.connection() == connection) writer.identity(identity);
            });
          });
        } catch (IOException e) { LoggerFactory.getLogger(ContextProviders.class).warn("SSH device identity unavailable: {}", e.getMessage()); }
      }
      if (blockedStatsReason != null) {
        var current = session;
        if (blockedStatsSession == null && current != null) blockedStatsSession = current;
        if (blockedStatsSession != null && current != null && current != blockedStatsSession) {
          blockedStatsReason = null; blockedStatsSession = null; nextSample = 0;
        } else { statsState = "stand_down"; statsReason = blockedStatsReason; return; }
      }
      if (!config.providers().stats().enabled() || pending.get() != null || loop.nowUs() < nextSample) return;
      var sampledSession = session;
      StatsProvider.Result result;
      try { result = stats.sample(connection); }
      catch (ProcStats.UnsupportedOutputException e) {
        // An old connection's reply cannot stand down the next boot's provider.
        if (generation != episode.get()) return;
        blockedStatsSession = sampledSession; blockedStatsReason = e.getMessage();
        statsState = "stand_down"; statsReason = blockedStatsReason; requestDrain(); return;
      }
      periodUs = stats.periodUs();
      pending.set(new Reading(connection, generation, result, stats.droppedBeforeSync(), periodUs));
      statsState = result.timestampUs() == null ? "waiting_for_sync" : "sampling";
      statsReason = result.timestampUs() == null ? "No NT4 clock estimate; sample dropped" : result.sample().notes().isEmpty() ? null : String.join("; ", result.sample().notes());
      nextSample = loop.nowUs() + periodUs; requestDrain();
    } catch (Exception e) {
      statsState = "waiting"; statsReason = e.getMessage(); nextSample = loop.nowUs() + periodUs;
    } finally { if (!stopped.get()) worker.schedule(this::sample, 250_000); }
  }
  private void requestDrain() {
    if (!stopped.get() && deliveryQueued.compareAndSet(false, true)) loop.execute(() -> { deliveryQueued.set(false); deliver(); });
  }
  private void drain() {
    if (stopped.get()) return;
    try { deliver(); } finally { if (!stopped.get()) loop.schedule(this::drain, 100_000); }
  }
  private void deliver() {
    if (stopped.get()) return;
    sessionChanged();
    var reading = pending.getAndSet(null);
    if (reading != null && robot != null && robot.connection() == reading.connection() && reading.episode() == episode.get()) {
      var result = reading.value(); sampleBytes = result.bytes();
      if (open && result.timestampUs() != null) {
        var metadata = new JsonObject(); metadata.addProperty("source", "ssh"); metadata.addProperty("host", robot.address());
        metadata.addProperty("sampled", true); metadata.addProperty("period_sec", result.periodUs() / 1_000_000.0);
        for (var value : new java.util.TreeMap<>(result.sample().values()).entrySet()) {
          String type = value.getValue() instanceof Long ? "int64" : "double";
          int written = record("/Daemon/roboRIO/" + value.getKey(), type, value.getValue(), result.timestampUs(), metadata);
          if (written > 0) {
            records++; bytes += written;
            if (value.getKey().equals("program/pid") && value.getValue() instanceof Long pid) programPids.add(pid);
          }
        }
        kernel = result.kernelClock();
      }
      statsStatus = new ProviderStatus("roboRIO", statsState, statsReason, reading.period() / 1_000_000.0,
          result.roundTripUs() / 1000.0, result.sample().robotCpuSeconds(), 0, 0, reading.dropped(), records, bytes, sampleBytes);
    }
    Double mapping = live.timeEstimate().map(s -> (double) s.offsetUs()).orElse(null);
    if (open && mapping != null) for (var tail : tails) {
      String host = tail.host == null ? "robot" : tail.host.address();
      var metadata = new JsonObject(); metadata.addProperty("source", "tail"); metadata.addProperty("host", host);
      metadata.addProperty("path", tail.config.path()); metadata.addProperty("timestamp", "received");
      if (tail.provider.buffer().buffered()) metadata.addProperty("buffered_before_session", "receipt mapped with the session NT4 offset and clamped at zero");
      for (var line : tail.provider.buffer().drain(loop.nowUs(), 50)) {
        int written = record("/Daemon/Tail/" + host + "/" + tail.config.role(), "string", line.text(),
            line.timestampUs(mapping), metadata);
        if (written > 0) { tail.records++; tail.bytes += written; }
      }
    }
    publish();
  }
  private int record(String name, String type, Object value, long time, JsonObject metadata) {
    int bytes = writer.recordContext(name, type, value, time, metadata);
    if (bytes > 0) live.context(name, org.triplehelix.wpilogmcp.nt4.NtType.fromWpilog(type).nt4(), value, time, loop.nowUs(), metadata);
    return bytes;
  }
  private void publish() {
    var snapshots = new ArrayList<ProviderStatus>();
    if (config.providers().stats().enabled()) {
      var value = statsStatus;
      String state = connected ? statsState : "offline", reason = connected ? statsReason : "NT4 is disconnected";
      if (connected && robot != null && robot.connection() == null) { state = robot.state().state(); reason = robot.state().reason(); }
      if (!java.util.Objects.equals(state, reportedStatsState) || !java.util.Objects.equals(reason, reportedStatsReason)) {
        LoggerFactory.getLogger(ContextProviders.class).info("SSH stats {}{}", state, reason == null ? "" : ": " + reason);
        reportedStatsState = state; reportedStatsReason = reason;
      }
      snapshots.add(new ProviderStatus("roboRIO", state, reason,
          periodUs / 1_000_000.0, value == null ? null : value.lastRoundTripMs(), value == null ? null : value.robotCpuSec(), 0, 0,
          value == null ? 0 : value.droppedBeforeSync(), records, bytes, sampleBytes, List.copyOf(programPids)));
    }
    for (var tail : tails) {
      var value = tail.provider.state(); var buffer = tail.provider.buffer();
      String state = connected ? value.state() : "offline", reason = connected ? value.reason() : "NT4 is disconnected";
      if (connected && tail.host != null && tail.host.connection() == null) { state = tail.host.state().state(); reason = tail.host.state().reason(); }
      snapshots.add(new ProviderStatus("tail/" + (tail.host == null ? "robot" : tail.host.address()) + "/" + tail.config.role(),
          state, reason, value.state().equals("polling") ? periodUs / 1_000_000.0 : 0.25,
          value.roundTripMs(), null, buffer.linesPerSecond(loop.nowUs()), buffer.dropped(), 0, tail.records, tail.bytes, 0));
    }
    photons.forEach(p -> snapshots.add(p.status()));
    if (jvm != null) snapshots.add(jvm.status());
    writer.providers(snapshots, kernel); live.providers(snapshots);
  }
  public CompletableFuture<Void> closeAsync() {
    if (stopped.compareAndSet(false, true)) { connected = false; open = false; tails.forEach(t -> t.provider.close()); photons.forEach(PhotonVisionProvider::close); if (jvm != null) jvm.close(); worker.close(); }
    return ssh.closeAsync();
  }
  @Override public void close() { closeAsync(); }
}
