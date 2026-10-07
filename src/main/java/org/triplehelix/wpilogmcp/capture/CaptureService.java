/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.triplehelix.wpilogmcp.config.CaptureConfig;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client;

/** Owns the recorder for a configured HTTP server; waiting for a robot never blocks startup. */
public final class CaptureService implements AutoCloseable {
  static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(30);
  private final Duration closeTimeout;
  private final Nt4Client client;
  private final org.triplehelix.wpilogmcp.capture.pull.PullCoordinator pull;
  private final org.triplehelix.wpilogmcp.store.CaptureStore placement;
  private volatile java.util.concurrent.CompletableFuture<Void> starting;
  private final java.util.concurrent.atomic.AtomicBoolean stopping = new java.util.concurrent.atomic.AtomicBoolean();

  public CaptureService(CaptureConfig config, LogManager manager) throws IOException {
    this(config, manager, Clock.systemUTC(), ClientScheduler.daemon());
  }

  public CaptureService(CaptureConfig config, LogManager manager, Clock clock, ClientScheduler loop)
      throws IOException {
    this(config, manager, clock, loop, WpilogOutput::new);
  }

  /** Injectable output for filesystem-failure checks over a real loopback NT4 connection. */
  public CaptureService(CaptureConfig config, LogManager manager, Clock clock, ClientScheduler loop,
      CaptureWriter.OutputFactory outputs) throws IOException {
    this(config, manager, clock, loop, outputs, CLOSE_TIMEOUT);
  }

  /** Test seam for the single shutdown deadline, without waiting thirty seconds in the suite. */
  CaptureService(CaptureConfig config, LogManager manager, Clock clock, ClientScheduler loop,
      CaptureWriter.OutputFactory outputs, Duration closeTimeout) throws IOException {
    this(config, manager, clock, loop, outputs, closeTimeout, org.triplehelix.wpilogmcp.capture.pull.PullCoordinator::new);
  }

  CaptureService(CaptureConfig config, LogManager manager, Clock clock, ClientScheduler loop,
      CaptureWriter.OutputFactory outputs, Duration closeTimeout,
      org.triplehelix.wpilogmcp.capture.pull.PullCoordinator.Factory pulls) throws IOException {
    this.closeTimeout = closeTimeout;
    var store = manager.stores().store(config.store());
    placement = store.captures(clock);
    var writer = new CaptureWriter(clock, loop, config.policy(), new CaptureIndex(placement, manager, config.hotWindowUs()), config.maxFileBytes(), outputs);
    var gate = new org.triplehelix.wpilogmcp.capture.pull.PullGate(loop::nowUs, config.pull().settleUs());
    pull = config.pull().enabled() ? pulls.create(config.pull(), gate, store, clock,
        learned -> loop.execute(() -> { if (learned.connection() == gate.connection()) writer.identity(learned.device()); })) : null;
    client = new Nt4Client(config.addresses(), Nt4Client.captureSubscription(config.periodSeconds()), listener(writer, gate),
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), loop);
  }

  /** Called after HTTP is listening: scanning and hashing old captures must not delay health. */
  public synchronized java.util.concurrent.CompletableFuture<Void> start() {
    if (starting != null) return starting;
    if (stopping.get()) return java.util.concurrent.CompletableFuture.completedFuture(null);
    starting = placement.recoverAsync().thenRun(() -> { client.start(); if (pull != null) pull.start(); }).whenComplete((ignored, error) -> {
      if (error != null) org.slf4j.LoggerFactory.getLogger(CaptureService.class)
          .error("Capture recovery failed; NT4 capture has not started", error);
    });
    return starting;
  }

  @Override public void close() {
    stopping.set(true);
    var pullClosed = pull == null ? java.util.concurrent.CompletableFuture.completedFuture(null) : pull.closeAsync();
    try {
      // One bound covers stopping the writer and waiting behind imports for the final manifest.
      var recovery = starting;
      java.util.concurrent.CompletableFuture.allOf(client.closeAsync(), recovery == null
          ? java.util.concurrent.CompletableFuture.completedFuture(null) : recovery, pullClosed)
          .thenCompose(ignored -> placement.completion())
          .get(closeTimeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); deferred(e);
    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
      deferred(e);
    }
  }

  /** These are the same ordered values the client publishes to its latest-value table. */
  static Nt4Client.Listener listener(CaptureWriter writer, org.triplehelix.wpilogmcp.capture.pull.PullGate gate) {
    return new Nt4Client.Listener() {
      private int controlId = -1;
      @Override public void connected(java.net.URI address, String protocol) { controlId = -1; gate.connected(address.getHost()); writer.connected(address, protocol); }
      @Override public void disconnected() { gate.disconnected(); writer.disconnected(); }
      @Override public void timeSync(long time, long received) { writer.timeSync(time, received); }
      @Override public void announce(org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce topic) {
        if (topic.name().equals("/FMSInfo/FMSControlData")) controlId = topic.id(); writer.announce(topic);
      }
      @Override public void unannounce(org.triplehelix.wpilogmcp.nt4.ControlMessage.Unannounce topic) {
        if (topic.id() == controlId) { controlId = -1; gate.unknown(); } writer.unannounce(topic);
      }
      @Override public void value(org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce topic, org.triplehelix.wpilogmcp.nt4.ValueFrame frame, long received) {
        if (topic.id() == controlId) gate.control(frame.value()); writer.value(topic, frame, received);
      }
      @Override public void invalidValue(org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce topic, int code) {
        if (topic.id() == controlId) gate.unknown(); writer.invalidValue(topic, code);
      }
    };
  }

  private void deferred(Exception reason) {
    org.slf4j.LoggerFactory.getLogger(CaptureService.class).warn(
        "Capture shutdown could not finish ({} second bound); the next startup sweep will recover unowned captures: {}",
        closeTimeout.toSeconds(), reason.toString());
  }
}
