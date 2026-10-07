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
      int remainingFiles, long remainingBytes, MirrorSync.Result result, StoreManifest.MirrorOrigin origin) {}
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
    return new Status(state, config, last, age, active() ? p.remainingFiles() : 0, active() ? p.remainingBytes() : 0, result, seen);
  }
  public CompletableFuture<StoreManifest.MirrorOrigin> pin(String id, boolean pinned) {
    return store.pin(id, pinned).thenApply(value -> { origin = value; return value; });
  }
  @Override public void close() { closed = true; timer.shutdownNow(); }
}
