/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class PeerDiscoveryTest {
  static final String PEER = "11111111-1111-4111-8111-111111111111", MASTER = "22222222-2222-4222-8222-222222222222";
  static void until(java.util.function.BooleanSupplier ready) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
    assertTrue(ready.getAsBoolean(), "discovery did not reach its bounded observable state");
  }
  @Test void endpointHintsRequireMatchingHandshakeAndNeverChangeTheSelectedMaster() throws Exception {
    try (var discovery = new PeerDiscovery(address -> List.of(new ReplicaProtocol.Store(PEER, MASTER, false, 2)), Clock.systemUTC())) {
      discovery.announce(PEER, MASTER, "http://127.0.0.1:12345", 100, 60);
      until(() -> discovery.endpoints(MASTER).size() == 1);
      assertEquals(List.of("http://127.0.0.1:12345?store=" + PEER), discovery.endpoints(MASTER));
      assertTrue(discovery.endpoints(UUID.randomUUID().toString()).isEmpty());
      discovery.announce(PEER, UUID.randomUUID().toString(), "http://127.0.0.1:12346", 100, 60);
      until(() -> discovery.snapshot().stream().anyMatch(p -> p.state().equals("unavailable")));
      assertTrue(discovery.endpoints(MASTER).isEmpty(), "an address change revokes the previous ready supplier until revalidated");
    }
  }
  @Test void removalCannotBeUndoneByALateHandshakeAndOnlyTwoProbesRun() throws Exception {
    var entered = new CountDownLatch(2); var release = new CountDownLatch(1); var active = new java.util.concurrent.atomic.AtomicInteger();
    try (var discovery = new PeerDiscovery(address -> {
      active.incrementAndGet(); entered.countDown();
      try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      finally { active.decrementAndGet(); }
      return List.of(new ReplicaProtocol.Store(PEER, MASTER, false, 2));
    }, Clock.systemUTC())) {
      for (int i = 0; i < 80; i++) discovery.announce(new UUID(0, i + 1).toString(), MASTER, "http://127.0.0.1:" + (10000 + i), 100, 60);
      assertTrue(entered.await(3, TimeUnit.SECONDS)); assertEquals(2, active.get()); assertEquals(64, discovery.snapshot().size());
      var first = new UUID(0, 1).toString(); discovery.remove(first, "http://127.0.0.1:10000");
      release.countDown(); until(() -> active.get() == 0);
      assertTrue(discovery.snapshot().stream().noneMatch(p -> p.peerId().equals(first)));
    } finally { release.countDown(); }
  }
  @Test void expiryAndOversizedOrOffLinkHintsCannotCreateFetches() throws Exception {
    var calls = new java.util.concurrent.atomic.AtomicInteger(); var time = new java.util.concurrent.atomic.AtomicLong();
    Clock clock = new Clock() {
      public ZoneId getZone() { return ZoneOffset.UTC; } public Clock withZone(ZoneId zone) { return this; }
      public Instant instant() { return Instant.ofEpochMilli(time.get()); } public long millis() { return time.get(); }
    };
    try (var discovery = new PeerDiscovery(address -> { calls.incrementAndGet(); return List.of(new ReplicaProtocol.Store(PEER, MASTER, false, 2)); }, clock)) {
      for (var address : List.of("http://example.com:80", "http://8.8.8.8", "http://127.0.0.1:1234/path", "http://127.0.0.1:1234?store=" + PEER))
        discovery.announce(PEER, MASTER, address, 100, 60);
      discovery.announce(PEER, MASTER, "http://127.0.0.1:1234", 4097, 60);
      assertEquals(0, calls.get()); assertTrue(discovery.snapshot().isEmpty());
      discovery.announce(PEER, MASTER, "http://127.0.0.1:1234", 100, 2);
      until(() -> discovery.endpoints(MASTER).size() == 1);
      discovery.announce(PEER, MASTER, "http://127.0.0.1:1234", 100, 2);
      assertEquals(1, calls.get(), "duplicate announcements retain one probe and its backoff");
      time.set(2000); assertTrue(discovery.snapshot().isEmpty()); assertTrue(discovery.endpoints(MASTER).isEmpty());
    }
  }
}
