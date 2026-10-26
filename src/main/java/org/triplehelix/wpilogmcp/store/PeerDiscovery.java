/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** Endpoint hints are never authority. A bounded handshake must establish the serving store
 * and the already selected master before an address can become a supplier. */
public final class PeerDiscovery implements AutoCloseable {
  public static final String SERVICE_TYPE = "_wpilog-replica._tcp.local.";
  public record Peer(String peerId, String masterStoreId, String endpoint, String state, long expiresAtMs, String reason) {}
  @FunctionalInterface public interface Probe { List<ReplicaProtocol.Store> handshake(String endpoint) throws IOException; }
  private final Probe probe;
  private final Clock clock;
  private static final class Candidate {
    final String peer, master, endpoint; long expires, retry; boolean probing; String state = "candidate", reason;
    Candidate(String peer, String master, String endpoint, long expires) { this.peer = peer; this.master = master; this.endpoint = endpoint; this.expires = expires; }
    Peer snapshot() { return new Peer(peer, master, endpoint, state, expires, reason); }
  }
  private final Object lock = new Object();
  private final Map<String, Candidate> candidates = new LinkedHashMap<>();
  private boolean closed;
  /**
   * Two handshake probes at a time. The limit counts probes, not threads: a probe gives its permit back before it
   * publishes its result, so a tick can start the next probe while the finished probe's thread is still on its way
   * back to the pool. A pool of two threads put that next probe off by a second whenever both were still finishing.
   */
  private final Semaphore probes = new Semaphore(2);
  private final ThreadPoolExecutor workers = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
    var thread = new Thread(task, "replica-handshake"); thread.setDaemon(true); return thread;
  });
  /** Runs on a probe's thread once it has published its result; tests hold the thread there, as a busy machine can. */
  private volatile Runnable afterProbe = () -> {};
  void afterProbe(Runnable probe) { afterProbe = probe; }
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
    var thread = new Thread(task, "replica-discovery"); thread.setDaemon(true); return thread;
  });
  public PeerDiscovery() { this(address -> { try (var client = new ReplicaClient(address)) { return client.hello().stores(); } }, Clock.systemUTC()); }
  PeerDiscovery(Probe probe, Clock clock) {
    this.probe = probe; this.clock = clock;
    timer.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
  }
  public void announce(String peer, String master, String endpoint, int txtBytes, long ttlSeconds) {
    if (txtBytes < 0 || txtBytes > 4096 || ttlSeconds <= 0) return;
    try { ReplicaJournal.uuid(peer); ReplicaJournal.uuid(master); }
    catch (RuntimeException invalid) { return; }
    String normalized;
    try {
      normalized = new org.triplehelix.wpilogmcp.sync.HttpRemoteFiles(endpoint).url();
      var uri = java.net.URI.create(normalized);
      if (!uri.getScheme().equals("http") || uri.getRawQuery() != null || !uri.getPath().isEmpty() || normalized.length() > 1024) return;
      String literal = uri.getHost().replaceAll("^\\[|\\]$", "");
      if (!literal.matches("[0-9.]+") && !literal.contains(":")) return; // No advertised DNS names or redirects become arbitrary fetches.
      var address = java.net.InetAddress.getByName(literal);
      if (address.isAnyLocalAddress() || address.isMulticastAddress()
          || !(address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isLoopbackAddress())) return;
    } catch (IOException | RuntimeException invalid) { return; }
    Candidate selected;
    synchronized (lock) {
      expire(); if (closed) return;
      var old = candidates.get(peer);
      if (old == null && candidates.size() >= 64) return;
      long expires = clock.millis() + Math.min(ttlSeconds, 300) * 1000;
      if (old != null && old.master.equals(master) && old.endpoint.equals(normalized)) { old.expires = expires; selected = old; }
      else { selected = new Candidate(peer, master, normalized, expires); candidates.put(peer, selected); }
    }
    probe(selected);
  }
  public void remove(String peer, String endpoint) {
    synchronized (lock) { var value = candidates.get(peer); if (value != null && value.endpoint.equals(endpoint)) candidates.remove(peer); }
  }
  public List<Peer> snapshot() {
    synchronized (lock) { expire(); return candidates.values().stream().map(Candidate::snapshot).toList(); }
  }
  public List<String> endpoints(String master) {
    return snapshot().stream().filter(p -> p.state().equals("ready") && p.masterStoreId().equals(master))
        .map(p -> p.endpoint() + "?store=" + p.peerId()).toList();
  }
  private void tick() {
    List<Candidate> copy;
    synchronized (lock) { expire(); copy = List.copyOf(candidates.values()); }
    copy.forEach(this::probe);
  }
  private void expire() { long now = clock.millis(); candidates.values().removeIf(c -> c.expires <= now); }
  private void probe(Candidate candidate) {
    synchronized (lock) {
      if (closed || candidates.get(candidate.peer) != candidate || candidate.probing || candidate.retry > clock.millis()) return;
      candidate.probing = true;
    }
    if (!probes.tryAcquire()) { synchronized (lock) { candidate.probing = false; candidate.retry = clock.millis() + 1000; } return; }
    try { workers.execute(() -> {
      String state = "ready", reason = null;
      try {
        var stores = probe.handshake(candidate.endpoint);
        if (stores.size() > 64 || stores.stream().noneMatch(s -> s.formatVersion() == 2
            && candidate.peer.equals(s.storeId()) && candidate.master.equals(s.masterStoreId())))
          throw new IOException("Handshake does not match the advertised serving store and master");
      } catch (Exception failure) { state = "unavailable"; reason = Objects.toString(failure.getMessage(), "Handshake failed"); }
      probes.release(); // Before the result is published: the next tick may start the next probe at once.
      synchronized (lock) {
        if (!closed && candidates.get(candidate.peer) == candidate) {
          candidate.probing = false; candidate.state = state; candidate.reason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 1024));
          candidate.retry = clock.millis() + (state.equals("ready") ? 30_000 : 60_000);
        }
      }
      afterProbe.run();
    }); } catch (RejectedExecutionException stopped) { probes.release(); synchronized (lock) { candidate.probing = false; candidate.retry = clock.millis() + 1000; } }
  }
  @Override public void close() {
    synchronized (lock) { closed = true; candidates.clear(); }
    workers.shutdownNow(); timer.shutdownNow();
  }
}
