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
  private final LiveCapture live;
  private final org.triplehelix.wpilogmcp.nt4.client.LoopWatchdog watchdog;
  private final org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway gateway;
  private final org.triplehelix.wpilogmcp.store.LogStore store;
  private final AutoCloseable observation;
  public LiveCapture live() { return live; }
  private final org.triplehelix.wpilogmcp.capture.pull.PullCoordinator pull;
  private final org.triplehelix.wpilogmcp.capture.context.ContextProviders providers;
  private final org.triplehelix.wpilogmcp.store.CaptureStore placement;
  private volatile java.util.concurrent.CompletableFuture<Void> starting;
  private final java.util.concurrent.atomic.AtomicBoolean stopping = new java.util.concurrent.atomic.AtomicBoolean();

  public CaptureService(CaptureConfig config, LogManager manager) throws IOException {
    this(config, manager, Clock.systemUTC(), ClientScheduler.daemon());
  }
  /** The gateway follows the deliberately selected HTTP bind, on its own port. */
  public CaptureService(CaptureConfig config, LogManager manager, String bindAddress) throws IOException {
    this(config, manager, Clock.systemUTC(), ClientScheduler.daemon(), WpilogOutput::new, CLOSE_TIMEOUT,
        null, bindAddress);
  }
  public int gatewayClients() { return gateway == null ? 0 : gateway.clientCount(); }

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
    this(config, manager, clock, loop, outputs, closeTimeout, null);
  }

  CaptureService(CaptureConfig config, LogManager manager, Clock clock, ClientScheduler loop,
      CaptureWriter.OutputFactory outputs, Duration closeTimeout,
      org.triplehelix.wpilogmcp.capture.pull.PullCoordinator.Factory pulls) throws IOException {
    this(config, manager, clock, loop, outputs, closeTimeout, pulls, "127.0.0.1");
  }

  private CaptureService(CaptureConfig config, LogManager manager, Clock clock, ClientScheduler loop,
      CaptureWriter.OutputFactory outputs, Duration closeTimeout,
      org.triplehelix.wpilogmcp.capture.pull.PullCoordinator.Factory pulls, String bindAddress) throws IOException {
    this(config, manager, clock, loop, outputs, closeTimeout, pulls, bindAddress,
        config.gatewayPort() == 0 ? null : ClientScheduler.daemon("nt4-gateway-bind"));
  }

  CaptureService(CaptureConfig config, LogManager manager, Clock clock, ClientScheduler loop,
      CaptureWriter.OutputFactory outputs, Duration closeTimeout,
      org.triplehelix.wpilogmcp.capture.pull.PullCoordinator.Factory pulls, String bindAddress,
      ClientScheduler binds) throws IOException {
    this.closeTimeout = closeTimeout;
    watchdog = new org.triplehelix.wpilogmcp.nt4.client.LoopWatchdog(loop);
    store = manager.stores().store(config.store());
    live = new LiveCapture(store.root(), loop);
    gateway = config.gatewayPort() == 0 ? null : new org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway(
        new java.net.InetSocketAddress(bindAddress == null ? "127.0.0.1" : bindAddress, config.gatewayPort()), () -> {
          var robotNow = live.robotNowUs();
          // ntcore servers answer with their local monotonic clock. Until upstream sync exists,
          // ours is the only reference; the first valid estimate resets downstream connections.
          return robotNow == null ? loop.nowUs() : Math.round(robotNow);
        }, clock, binds);
    if (gateway != null) live.attachGateway(gateway::status);
    observation = store.observe(live);
    placement = store.captures(clock);
    placement.onStatus(live::status);
    var writer = new CaptureWriter(clock, loop, config.policy(), new CaptureIndex(placement, manager, config.hotWindowUs(), live::robotNowUs), config.maxFileBytes(), outputs);
    var gate = new org.triplehelix.wpilogmcp.capture.pull.PullGate(loop::nowUs, config.pull().settleUs());
    providers = (config.providers().robotSsh() || !config.providers().tails().isEmpty() || pulls == null && config.pull().enabled())
        ? new org.triplehelix.wpilogmcp.capture.context.ContextProviders(config, writer, live, loop, store, clock) : null;
    var factory = pulls == null ? (org.triplehelix.wpilogmcp.capture.pull.PullCoordinator.Factory)
        (settings, admission, storage, wall, identity) -> new org.triplehelix.wpilogmcp.capture.pull.PullCoordinator(
            settings, admission, storage, wall, identity, ClientScheduler.daemon("robot-pull"), (address, options, pin) -> providers.pull(address, options, pin)) : pulls;
    pull = config.pull().enabled() ? factory.create(config.pull(), gate, store, clock,
        learned -> loop.execute(() -> { if (learned.connection() == gate.connection()) writer.identity(learned.device()); })) : null;
    if (pull != null) live.attachPull(pull::progress);
    client = new Nt4Client(config.addresses(), Nt4Client.captureSubscription(config.periodSeconds()), listener(writer, gate, live, gateway, providers),
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), loop);
    live.attach(client);
  }

  /** Called after HTTP is listening: scanning and hashing old captures must not delay health. */
  public synchronized java.util.concurrent.CompletableFuture<Void> start() {
    if (starting != null) return starting;
    if (stopping.get()) return java.util.concurrent.CompletableFuture.completedFuture(null);
    // The gateway is a view. Its independent bind retry must never hold up the record or puller.
    if (gateway != null) gateway.start();
    starting = placement.recoverAsync().thenCompose(ignored -> store.refreshStatus()).thenRun(() -> {
      if (!stopping.get()) { watchdog.start(); if (providers != null) providers.start(); client.start(); if (pull != null) pull.start(); }
    }).whenComplete((ignored, error) -> {
      if (error != null) org.slf4j.LoggerFactory.getLogger(CaptureService.class)
          .error("Capture startup failed (store recovery); NT4 capture has not started", error);
    });
    return starting;
  }

  @Override public void close() {
    stopping.set(true);
    watchdog.close();
    live.stop();
    var providersClosed = providers == null ? java.util.concurrent.CompletableFuture.completedFuture(null) : providers.closeAsync();
    var pullClosed = pull == null ? java.util.concurrent.CompletableFuture.completedFuture(null) : pull.closeAsync();
    var gatewayClosed = gateway == null ? java.util.concurrent.CompletableFuture.completedFuture(null)
        : java.util.concurrent.CompletableFuture.runAsync(() -> {
          try { gateway.close(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CompletionException(e); }
        });
    try {
      // One bound covers stopping the writer and waiting behind imports for the final manifest.
      var recovery = starting;
      java.util.concurrent.CompletableFuture.allOf(client.closeAsync(), recovery == null
          ? java.util.concurrent.CompletableFuture.completedFuture(null) : recovery.handle((ignored, error) -> null), pullClosed, gatewayClosed, providersClosed)
          .thenCompose(ignored -> placement.completion())
          .get(closeTimeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); deferred(e);
    } catch (java.util.concurrent.TimeoutException e) {
      deferred(e);
    } catch (java.util.concurrent.ExecutionException e) {
      org.slf4j.LoggerFactory.getLogger(CaptureService.class).warn("Capture shutdown failed; the next startup sweep will recover unowned captures", e.getCause());
    } finally {
      try { observation.close(); } catch (Exception e) { deferred(e); }
    }
  }

  /** These are the same ordered values the client publishes to its latest-value table. */
  static Nt4Client.Listener listener(CaptureWriter writer, org.triplehelix.wpilogmcp.capture.pull.PullGate gate) {
    return listener(writer, gate, null, null, null);
  }
  private static Nt4Client.Listener listener(CaptureWriter writer, org.triplehelix.wpilogmcp.capture.pull.PullGate gate,
      LiveCapture live, org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway gateway,
      org.triplehelix.wpilogmcp.capture.context.ContextProviders providers) {
    return new Nt4Client.Listener() {
      private final java.util.Map<Integer, String> names = new java.util.HashMap<>();
      private int controlId = -1;
      private boolean synchronizedClock;
      @Override public void connected(java.net.URI address, String protocol) { synchronizedClock = false; controlId = -1; names.clear(); gate.connected(address.getHost()); writer.connected(address, protocol);
        if (providers != null) providers.connected(address.getHost());
      }
      @Override public void disconnected() {
        gate.disconnected();
        if (providers != null) providers.disconnected();
        writer.disconnected();
        if (live != null) live.endWaits("The NT4 connection dropped");
        if (gateway != null) gateway.endSession();
      }
      @Override public void timeSync(long time, long received) {
        writer.timeSync(time, received);
        if (providers != null) providers.sessionChanged();
        if (!synchronizedClock && gateway != null) gateway.resetClock();
        synchronizedClock = true;
      }
      @Override public void announce(org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce topic) {
        names.put(topic.id(), topic.name());
        if (topic.name().equals("/FMSInfo/FMSControlData")) controlId = topic.id(); writer.announce(topic);
        if (providers != null) providers.sessionChanged();
        if (gateway != null) gateway.announce(topic.name(), topic.type(), topic.properties());
      }
      @Override public void unannounce(org.triplehelix.wpilogmcp.nt4.ControlMessage.Unannounce topic) {
        String name = names.remove(topic.id()); if (live != null && name != null) live.unannounce(name);
        if (topic.id() == controlId) { controlId = -1; gate.unknown(); } writer.unannounce(topic);
        if (gateway != null && name != null) gateway.unannounce(name);
      }
      @Override public void properties(org.triplehelix.wpilogmcp.nt4.ControlMessage.Properties change) {
        writer.properties(change);
        if (gateway != null) gateway.properties(change.name(), change.update());
      }
      @Override public void value(org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce topic, org.triplehelix.wpilogmcp.nt4.ValueFrame frame, long received) {
        if (topic.id() == controlId) gate.control(frame.value()); writer.value(topic, frame, received);
        if (live != null) live.value(topic, frame);
        if (gateway != null) gateway.value(topic.name(), frame.timestampUs(), frame.typeCode(), frame.value());
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
