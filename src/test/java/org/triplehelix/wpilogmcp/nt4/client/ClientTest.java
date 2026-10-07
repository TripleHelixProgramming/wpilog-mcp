/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.client;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.nt4.ControlMessage;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.*;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;
import org.triplehelix.wpilogmcp.nt4.server.ScriptedPeer;

class ClientTest {
  private static byte[] hex(String text) { return HexFormat.of().parseHex(text); }
  private static int unusedPort() throws Exception {
    try (var socket = new ServerSocket()) {
      socket.bind(new InetSocketAddress("127.0.0.1", 0)); return socket.getLocalPort();
    }
  }
  private static Nt4Client client(int port, ManualScheduler loop, Nt4Client.Listener listener) {
    return new Nt4Client(List.of(RobotAddress.uri("127.0.0.1", port, "test")),
        Nt4Client.captureSubscription(0.01), listener, HttpClient.newHttpClient(), loop);
  }

  @Test void addressOrderAndEscaping() {
    assertEquals(List.of("roboRIO-2363-FRC.local", "10.23.63.2"), RobotAddress.team(2363));
    assertEquals(List.of("172.22.11.2"), RobotAddress.usb());
    assertEquals("ws://127.0.0.1:5810/nt/pit%20server", RobotAddress.uri("127.0.0.1", 5810, "pit server").toString());
    assertEquals("ws://[::1]:5810/nt/pit", RobotAddress.uri("::1", 5810, "pit").toString());
    assertThrows(IllegalArgumentException.class, () -> RobotAddress.team(0));
    assertThrows(IllegalArgumentException.class, () -> RobotAddress.team(25600));
    assertThrows(IllegalArgumentException.class, () -> RobotAddress.uri("x", 0, "pit"));
    assertThrows(IllegalArgumentException.class, () -> RobotAddress.uri("x", 5810, "pit@2"));
  }

  @Test void retriesForeverWithInjectedTimeAtOneTwoFourEightTenSeconds() throws Exception {
    var loop = new ManualScheduler();
    try (var client = client(unusedPort(), loop, new Nt4Client.Listener() {})) {
      client.start();
      long[] expected = {1, 2, 4, 8, 10, 10, 10};
      for (int i = 0; i < expected.length; i++) {
        int count = i + 1;
        loop.until(() -> loop.delays.size() >= count);
        assertEquals(expected[i] * 1_000_000, loop.delays.get(i));
        assertFalse(client.isConnected());
        if (i + 1 < expected.length) {
          loop.advance(expected[i] * 1_000_000 - 1);
          assertEquals(count, loop.delays.size(), "No early retry"); loop.advance(1);
        }
      }
    }
    loop.drain(); int scheduled = loop.delays.size(); loop.advance(100_000_000);
    assertEquals(scheduled, loop.delays.size(), "Close stops reconnects");
  }

  @Test void candidatesInOrderAndV40FallbackWithSyncBeforeSubscribeAndEveryThreeSeconds() throws Exception {
    var loop = new ManualScheduler();
    try (var peer = new ScriptedPeer(Nt4Client.V40);
         var client = new Nt4Client(List.of(RobotAddress.uri("127.0.0.1", unusedPort(), "pit"),
             RobotAddress.uri("127.0.0.1", peer.getPort(), "pit")), Nt4Client.captureSubscription(0.01),
             new Nt4Client.Listener() {}, HttpClient.newHttpClient(), loop)) {
      peer.answerSync = false;
      client.start(); loop.until(() -> client.isConnected());
      assertTrue(peer.offeredProtocols.indexOf(Nt4Client.V41) < peer.offeredProtocols.indexOf(Nt4Client.V40));
      assertEquals("/nt/pit", peer.resource);
      var first = peer.frames.poll(5, TimeUnit.SECONDS);
      assertNotNull(first); assertEquals(new ValueFrame(-1, 0, 2, 0L), first);
      assertTrue(peer.controls.isEmpty(), "Subscribe waits for first RTT reply");
      peer.binary(hex("94ffcd13880200")); loop.until(() -> client.timeEstimate().isPresent());
      var subscription = (Subscribe) peer.controls.poll(5, TimeUnit.SECONDS);
      assertNotNull(subscription); assertEquals(List.of(""), subscription.topics());
      assertTrue(subscription.all()); assertTrue(subscription.prefix()); assertEquals(0.01, subscription.periodic());
      assertEquals(5000, client.timeEstimate().orElseThrow().offsetUs());
      loop.advance(2_999_999); assertTrue(peer.frames.isEmpty());
      loop.advance(1);
      var second = peer.frames.poll(5, TimeUnit.SECONDS); assertNotNull(second); assertEquals(3_000_000L, second.value());
      assertEquals(0, peer.pings.get(), "4.0 must never receive WebSocket pings");
      assertFalse(loop.delays.contains(1_000_000L), "Candidate failover does not wait a backoff");
    }
    loop.drain();
  }

  @Test void fragmentedMessagesKeepCallbackOrderAuthoritativeTypesAndLatestTimestamp() throws Exception {
    var loop = new ManualScheduler();
    var events = new ArrayList<String>(); var values = new ArrayList<ValueFrame>();
    var listener = new Nt4Client.Listener() {
      @Override public void announce(Announce a) { events.add("announce:" + a.type()); }
      @Override public void value(Announce a, ValueFrame f, long receivedAt) {
        assertEquals("json", a.type()); assertEquals(7, receivedAt); events.add("value:" + f.timestampUs()); values.add(f);
      }
      @Override public void properties(Properties p) { events.add("properties"); }
      @Override public void unannounce(Unannounce a) { events.add("unannounce"); }
    };
    try (var peer = new ScriptedPeer(Nt4Client.V40); var client = client(peer.getPort(), loop, listener)) {
      client.start(); loop.until(() -> client.timeEstimate().isPresent());
      assertNotNull(peer.controls.poll(5, TimeUnit.SECONDS)); loop.advance(7);
      peer.fragmented("[{\"method\":\"announce\",\"params\":{\"name\":\"/j\",\"id\":1,\"type\":\"json\",\"properties\":{}}}]",
          hex("94011404a27b7d94010a04a26e6f94021404a178"));
      loop.until(() -> values.size() == 2);
      assertEquals(List.of("announce:json", "value:20", "value:10"), events);
      assertEquals("{}", client.latestValues().get("/j").value());
      assertEquals("json", client.latestValues().get("/j").type());
      assertEquals(20, client.latestValues().get("/j").serverTimestampUs());
      assertEquals(7, client.latestValues().get("/j").receivedAtUs());
      peer.text("[{\"method\":\"properties\",\"params\":{\"name\":\"/j\",\"update\":{\"cached\":false}}}]");
      loop.until(() -> events.size() == 4); assertTrue(client.latestValues().isEmpty());
      peer.text("[{\"method\":\"unannounce\",\"params\":{\"name\":\"/j\",\"id\":1}}]");
      loop.until(() -> events.size() == 5); assertTrue(client.topics().isEmpty());
      assertEquals("unannounce", events.get(4));
    }
    loop.drain();
  }

  @Test void reconnectUsesNewAnnouncementsAndContinuingValuesAndResetsBackoff() throws Exception {
    var loop = new ManualScheduler(); var received = new ArrayList<Long>(); var topics = new ArrayList<Integer>();
    var disconnected = new AtomicInteger();
    var listener = new Nt4Client.Listener() {
      @Override public void announce(Announce a) { topics.add(a.id()); }
      @Override public void value(Announce a, ValueFrame v, long receipt) { received.add((Long) v.value()); }
      @Override public void disconnected() {
        disconnected.incrementAndGet();
        throw new IllegalStateException("planted writer cleanup failure");
      }
    };
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> loop.nowUs() + 5000)) {
      gateway.start().get(5, TimeUnit.SECONDS); gateway.announce("/x", "int", new JsonObject()).join();
      try (var client = client(gateway.port(), loop, listener)) {
        client.start(); loop.until(() -> topics.size() == 1);
        gateway.value("/x", 100, 2, 1L).join(); loop.until(() -> received.size() == 1);
        gateway.dropClients().join(); loop.until(() -> disconnected.get() == 1);
        assertTrue(client.topics().isEmpty()); assertTrue(client.latestValues().isEmpty()); assertTrue(client.timeEstimate().isEmpty());
        gateway.unannounce("/x").join(); gateway.announce("/x", "int", new JsonObject()).join();
        loop.advance(999999); assertFalse(client.isConnected()); loop.advance(1);
        loop.until(() -> topics.size() == 2);
        assertNotEquals(topics.get(0), topics.get(1));
        gateway.value("/x", 1_000_100, 2, 2L).join(); loop.until(() -> received.size() == 2);
        assertEquals(List.of(1L, 2L), received);
        assertEquals(1_000_100, client.latestValues().get("/x").serverTimestampUs());
        gateway.dropClients().join(); loop.until(() -> disconnected.get() == 2);
        assertEquals(2, loop.delays.stream().filter(d -> d == 1_000_000).count());
      }
      loop.drain();
    }
  }

  @Test void plantedServerClockEstimateIsWithinRoundTrip() throws Exception {
    var loop = ClientScheduler.daemon();
    long plantedOffset = 5_000_000;
    var ready = new CompletableFuture<Void>();
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> loop.nowUs() + plantedOffset)) {
      gateway.start().get(5, TimeUnit.SECONDS); gateway.announce("/ready", "int", new JsonObject()).join();
      try (var client = new Nt4Client(List.of(RobotAddress.uri("127.0.0.1", gateway.port(), "clock")),
          Nt4Client.captureSubscription(0.01), new Nt4Client.Listener() {
            @Override public void announce(Announce a) { ready.complete(null); }
          }, HttpClient.newHttpClient(), loop)) {
        client.start(); ready.get(10, TimeUnit.SECONDS);
        var estimate = client.timeEstimate().orElseThrow();
        assertTrue(Math.abs(estimate.offsetUs() - plantedOffset) <= estimate.roundTripUs() / 2 + 1,
            () -> "offset=" + estimate.offsetUs() + " RTT=" + estimate.roundTripUs());
      }
    }
  }

  @Test void reconnectPrefersLastConnectedCandidateThenConfiguredOrder() throws Exception {
    var loop = new ManualScheduler();
    var seen = new ArrayList<String>();
    var disconnected = new AtomicInteger();
    int firstPort = unusedPort();
    try (var second = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> loop.nowUs() + 5000);
         var third = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> loop.nowUs() + 5000)) {
      second.start().get(5, TimeUnit.SECONDS); second.announce("/second", "int", new JsonObject()).join();
      third.start().get(5, TimeUnit.SECONDS); third.announce("/third", "int", new JsonObject()).join();
      try (var client = new Nt4Client(List.of(RobotAddress.uri("127.0.0.1", firstPort, "first"),
          RobotAddress.uri("127.0.0.1", second.port(), "second"),
          RobotAddress.uri("127.0.0.1", third.port(), "third")), Nt4Client.captureSubscription(0.01),
          new Nt4Client.Listener() {
            @Override public void announce(Announce a) { seen.add(a.name()); }
            @Override public void disconnected() { disconnected.incrementAndGet(); }
          }, HttpClient.newHttpClient(), loop)) {
        client.start(); loop.until(() -> seen.size() == 1); assertEquals(List.of("/second"), seen);
        try (var first = new Nt4Gateway(new InetSocketAddress("127.0.0.1", firstPort), () -> loop.nowUs() + 5000)) {
          first.start().get(5, TimeUnit.SECONDS); first.announce("/first", "int", new JsonObject()).join();
          second.dropClients().join(); loop.until(() -> disconnected.get() == 1);
          loop.advance(1_000_000); loop.until(() -> seen.size() == 2);
          assertEquals(List.of("/second", "/second"), seen,
              "The successful address stays first even when an earlier candidate becomes reachable");
          second.close(); loop.until(() -> disconnected.get() == 2);
          loop.advance(1_000_000); loop.until(() -> seen.size() == 3);
          assertEquals(List.of("/second", "/second", "/first"), seen,
              "After the remembered address fails, the others retain their configured order");
        }
      }
      loop.drain();
    }
  }

  @Test void keepaliveAndMalformedBinaryDisconnectWithNoRealSleep() throws Exception {
    for (var protocol : List.of(Nt4Client.V41, Nt4Client.V40)) {
      var loop = new ManualScheduler(); var disconnected = new AtomicInteger();
      try (var peer = new ScriptedPeer(protocol); var client = client(peer.getPort(), loop, new Nt4Client.Listener() {
        @Override public void disconnected() { disconnected.incrementAndGet(); }
      })) {
        peer.answerPing = false;
        client.start(); loop.until(() -> client.timeEstimate().isPresent());
        peer.answerSync = false;
        loop.advance(protocol.equals(Nt4Client.V41) ? 1_000_000 : 10_000_000);
        loop.until(() -> disconnected.get() == 1); assertFalse(client.isConnected());
        assertTrue(loop.delays.contains(1_000_000L));
      }
      loop.drain();
    }
    var loop = new ManualScheduler(); var disconnected = new AtomicInteger();
    try (var peer = new ScriptedPeer(Nt4Client.V40); var client = client(peer.getPort(), loop, new Nt4Client.Listener() {
      @Override public void disconnected() { disconnected.incrementAndGet(); }
    })) {
      client.start(); loop.until(client::isConnected); peer.binary(hex("93ff0002"));
      loop.until(() -> disconnected.get() == 1); assertFalse(client.isConnected());
    }
    loop.drain();
  }

  @Test void wrongValueFamilyIsCountedWithoutLosingTheNextFrameOrConnection() throws Exception {
    var loop = new ManualScheduler(); var values = new ArrayList<ValueFrame>(); var rejected = new AtomicInteger();
    try (var peer = new ScriptedPeer(Nt4Client.V40); var client = client(peer.getPort(), loop, new Nt4Client.Listener() {
      @Override public void invalidValue(Announce topic, int code) {
        assertEquals("/x", topic.name()); assertEquals(2, code); rejected.incrementAndGet();
      }
      @Override public void value(Announce topic, ValueFrame frame, long receivedAt) { values.add(frame); }
    })) {
      client.start(); loop.until(() -> client.timeEstimate().isPresent());
      peer.text("[{\"method\":\"announce\",\"params\":{\"name\":\"/x\",\"id\":1,\"type\":\"int\",\"properties\":{}}}]");
      loop.until(() -> client.topics().size() == 1);
      peer.binary(hex("94010102c39401020207")); // int code with boolean, then integer 7 at 2 us
      loop.until(() -> client.invalidValueCount() > 0 || !client.isConnected());
      assertTrue(client.isConnected()); assertEquals(1, rejected.get()); assertEquals(1, client.invalidValueCount());
      assertEquals(List.of(new ValueFrame(1, 2, 2, 7L)), values);
    }
    loop.drain();
  }
}
