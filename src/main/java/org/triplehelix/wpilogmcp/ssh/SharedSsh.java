/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.ssh;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.config.PullConfig;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;

/**
 * One connection attempt and authenticated session per host. A host worker may spend at most
 * the five-second connect deadline in SSH; consumers never join that worker or a store lookup.
 * NT4 presence admits connections, independently of the disabled-only file-transfer gate.
 */
public final class SharedSsh implements AutoCloseable {
  @FunctionalInterface public interface Connector {
    SshConnection connect(String host, PullConfig.Ssh config, String fingerprint) throws IOException;
  }
  public record State(String state, String reason) {}
  private final Map<String, Host> hosts = new ConcurrentHashMap<>();
  private final Connector connector;
  private final Function<String, CompletableFuture<String>> pins;
  private final Function<String, ClientScheduler> workers;
  private final AtomicBoolean stopped = new AtomicBoolean();

  public SharedSsh(Function<String, CompletableFuture<String>> pins) {
    this(pins, JschConnection::connect, name -> ClientScheduler.daemon("ssh-" + name));
  }
  public SharedSsh(Function<String, CompletableFuture<String>> pins, Connector connector,
      Function<String, ClientScheduler> workers) {
    this.pins = pins; this.connector = connector; this.workers = workers;
  }
  public Host host(String address, PullConfig.Ssh config) {
    if (stopped.get()) throw new IllegalStateException("SSH manager is closed");
    var host = hosts.computeIfAbsent(address, name -> new Host(name, config));
    if (!host.config.equals(config)) throw new IllegalArgumentException("Conflicting SSH settings for host " + address);
    host.start();
    return host;
  }
  public final class Host {
    private final String address;
    private final PullConfig.Ssh config;
    private final ClientScheduler worker;
    private final AtomicBoolean started = new AtomicBoolean();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private volatile boolean required;
    private volatile SshConnection connection;
    private volatile State state = new State("offline", "NT4 is disconnected");
    private volatile Consumer<SshConnection> onConnect = ignored -> {};
    private long retryUs = 1_000_000, nextAttemptUs;
    private CompletableFuture<String> pin;
    private String learnedPin;
    private Host(String address, PullConfig.Ssh config) {
      this.address = address; this.config = config; worker = workers.apply(address);
    }
    private void start() { if (started.compareAndSet(false, true)) worker.execute(this::tick); }
    public String address() { return address; }
    public State state() { return state; }
    public void required(boolean value) { required = value; }
    public void onConnect(Consumer<SshConnection> action) { onConnect = action; }
    public SshConnection connection() {
      var value = connection;
      return required && !stopped.get() && value != null && value.connected() ? value : null;
    }
    private void state(String name, String reason) {
      var after = new State(name, reason);
      if (!after.equals(state)) {
        state = after;
        LoggerFactory.getLogger(SharedSsh.class).info("SSH {} {}{}", address, name, reason == null ? "" : ": " + reason);
      }
    }
    private void discard() { var old = connection; connection = null; if (old != null) old.close(); }
    private void tick() {
      try {
        if (stopped.get()) { discard(); state("stopped", null); closed.complete(null); worker.close(); return; }
        if (!required) {
          discard(); pin = null; retryUs = 1_000_000; nextAttemptUs = 0;
          state("offline", "NT4 is disconnected"); return;
        }
        if (connection != null && connection.connected()) return;
        discard();
        if (worker.nowUs() < nextAttemptUs) return;
        if (pin == null) pin = pins.apply(address);
        if (!pin.isDone()) { state("waiting", "Reading the pinned host key"); return; }
        var connected = connector.connect(address, config, learnedPin == null ? pin.join() : learnedPin);
        if (!required || stopped.get()) { connected.close(); return; }
        connection = connected; learnedPin = connected.fingerprint(); retryUs = 1_000_000; nextAttemptUs = 0; pin = null;
        state("connected", null); onConnect.accept(connected);
      } catch (Exception e) {
        discard(); pin = null; state("waiting", e.getMessage());
        nextAttemptUs = worker.nowUs() + retryUs; retryUs = Math.min(30_000_000, retryUs * 2);
      } finally {
        if (!closed.isDone()) worker.schedule(this::tick, 250_000);
      }
    }
  }
  public CompletableFuture<Void> closeAsync() {
    stopped.set(true);
    return CompletableFuture.allOf(hosts.values().stream().map(h -> h.closed).toArray(CompletableFuture[]::new));
  }
  @Override public void close() { closeAsync(); }
}
