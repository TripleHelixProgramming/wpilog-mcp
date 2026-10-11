/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.server;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.List;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.framing.Framedata;
import org.java_websocket.framing.PingFrame;
import org.java_websocket.framing.PongFrame;
import org.java_websocket.handshake.HandshakeImpl1Client;
import org.java_websocket.protocols.Protocol;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client;

/** The network callbacks run while the fan-out clock is stalled; no wall-clock sleeps. */
class GatewayKeepaliveTest {
  @Test void completingOneTaskReleasesAdmissionBeforeItsContinuationSubmitsTheNext() throws Exception {
    try (var f = new Fixture()) {
      f.gateway.workLimits(1024, 1);
      var both = f.gateway.announce("/x", "double", new com.google.gson.JsonObject())
          .thenCompose(ignored -> f.gateway.value("/x", 1, 1, 1.0));
      f.loop.drain(); assertDoesNotThrow(both::join);
    }
  }
  @Test void pausedFanOutRefusesExcessUpstreamWorkAndDisconnectsTheView() throws Exception {
    try (var f = new Fixture()) {
      var announced = f.gateway.announce("/x", "double", new com.google.gson.JsonObject());
      f.loop.drain(); announced.join();
      f.gateway.workLimits(1024, 2);
      var first = f.gateway.value("/x", 1, 1, 1.0);
      var second = f.gateway.value("/x", 2, 1, 2.0);
      var excess = f.gateway.value("/x", 3, 1, 3.0);
      assertTrue(excess.isCompletedExceptionally(), "Admission must happen before the paused loop");
      f.loop.drain(); first.join(); second.join();
      assertNotNull(f.peer.dropped, "An interrupted view must reconnect instead of silently losing values");
      assertTrue(f.peer.dropped.contains("work queue"));
      var next = f.gateway.value("/x", 4, 1, 4.0); f.loop.drain(); next.join();
    }
  }
  @Test void pausedFanOutChargesPeerBytesBeforeCopyingTheNextMessage() throws Exception {
    try (var f = new Fixture()) {
      f.gateway.workLimits(1024, 4096);
      var bytes = java.nio.ByteBuffer.allocate(600);
      f.server.onMessage(f.peer, bytes.asReadOnlyBuffer());
      f.server.onMessage(f.peer, bytes.asReadOnlyBuffer());
      assertNotNull(f.peer.dropped, "A receive budget must be checked before the loop can drain");
      assertTrue(f.peer.dropped.contains("work queue"));
      f.loop.drain();
    }
  }
  @Test void onePeersQueuedWorkCannotDisconnectAnotherView() throws Exception {
    try (var f = new Fixture()) {
      var slow = f.connect("slow");
      f.gateway.workLimits(2048, 4);
      String request = "[{\"method\":\"unsubscribe\",\"params\":{\"subuid\":9}}]";
      for (int i = 0; i < 3; i++) f.server.onMessage(slow, request);
      f.loop.drain();
      assertNotNull(slow.dropped, "The peer that exceeded its own work allowance is refused");
      assertNull(f.peer.dropped, "A second peer must retain its connection after the first overruns");
      var announced = f.gateway.announce("/x", "double", new com.google.gson.JsonObject());
      f.loop.drain(); announced.join();
      f.server.onMessage(f.peer, "[{\"method\":\"subscribe\",\"params\":{\"subuid\":1,\"topics\":[\"/x\"],\"options\":{\"all\":true,\"periodic\":0.001}}}]");
      f.loop.drain();
      for (int i = 0; i < 3; i++) {
        var value = f.gateway.value("/x", i, 1, (double) i); f.loop.drain(); value.join();
        f.loop.advance(1000);
      }
      assertEquals(3, f.peer.values, "The surviving peer keeps receiving every value");
    }
  }

  @Test void privateBacklogReleasesCapacityForOtherPeersSharedPublications() throws Exception {
    try (var f = new Fixture()) {
      var slow = f.connect("slow"); f.gateway.workLimits(8192, 8);
      String request = "[{\"method\":\"unsubscribe\",\"params\":{\"subuid\":9}}]";
      for (int i = 0; i < 4; i++) f.server.onMessage(slow, request);
      var published = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Void>>();
      for (int i = 0; i < 5; i++) published.add(f.gateway.announce("/x" + i, "double", new com.google.gson.JsonObject()));
      f.loop.drain();
      assertNotNull(slow.dropped); assertNull(f.peer.dropped);
      published.forEach(future -> assertDoesNotThrow(future::join,
          "Cancelled private work must free capacity without losing a healthy peer's publication"));
    }
  }

  @Test void aShortFanOutStallHoldsASecondOfTypicalRobotTraffic() throws Exception {
    try (var f = new Fixture()) {
      var announced = f.gateway.announce("/x", "double", new com.google.gson.JsonObject());
      f.loop.drain(); announced.join();
      var work = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Void>>();
      // 100 topics at 50 Hz is 5,000 values. A short local stall is not a dead subscriber.
      for (int i = 0; i < 5000; i++) work.add(f.gateway.value("/x", i, 1, (double) i));
      f.loop.elapse(500_000); f.loop.drain();
      assertNull(f.peer.dropped, "A half-second loop stall must not disconnect a healthy view");
      work.forEach(java.util.concurrent.CompletableFuture::join);
    }
  }

  @Test void aGlobalOverrunAfterFiveSecondsNamesTheStalledLoop() throws Exception {
    try (var f = new Fixture()) {
      var second = f.connect("second");
      f.gateway.workLimits(2048, 4);
      for (int i = 0; i < 4; i++) f.gateway.announce("/x" + i, "double", new com.google.gson.JsonObject());
      f.loop.elapse(5_000_000);
      f.gateway.announce("/excess", "double", new com.google.gson.JsonObject());
      f.loop.drain();
      assertNotNull(f.peer.dropped); assertNotNull(second.dropped);
      assertTrue(f.peer.dropped.contains("fan-out loop stalled"), f.peer.dropped);
      assertTrue(f.peer.dropped.contains("5000000"), f.peer.dropped);
    }
  }

  private static final class Fixture implements AutoCloseable {
    final ManualScheduler loop = new ManualScheduler(), binds = new ManualScheduler();
    final Nt4Gateway gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 0,
        List.of(Nt4Client.V41), 2, (name, reason) -> {}, Clock.systemUTC(), binds, loop);
    final WebSocketServer server;
    final Peer peer;
    Fixture() throws Exception {
      var started = gateway.start(); binds.until(started::isDone); started.join();
      var field = Nt4Gateway.class.getDeclaredField("server"); field.setAccessible(true);
      server = (WebSocketServer) field.get(gateway); peer = connect("healthy");
    }
    Peer connect(String name) {
      var peer = new Peer(server);
      var request = new HandshakeImpl1Client(); request.setResourceDescriptor("/nt/" + name);
      server.onOpen(peer, request); loop.drain(); return peer;
    }
    public void close() throws Exception { gateway.close(); }
  }
  private static final class Peer extends WebSocketImpl {
    final WebSocketServer server;
    boolean answer = true;
    int pings, pongs, values;
    String dropped;
    Peer(WebSocketServer server) { super(server, new Draft_6455()); this.server = server; }
    @Override public Protocol getProtocol() { return new Protocol(Nt4Client.V41); }
    @Override public boolean isOpen() { return dropped == null; }
    @Override public void sendPing() { pings++; if (answer) server.onWebsocketPong(this, new PongFrame()); }
    @Override public void sendFrame(Framedata frame) { if (frame instanceof PongFrame) pongs++; }
    @Override public void sendFragmentedFrame(org.java_websocket.enums.Opcode opcode,
        java.nio.ByteBuffer bytes, boolean fin) {
      if (opcode == org.java_websocket.enums.Opcode.BINARY) {
        var copy = new byte[bytes.remaining()]; bytes.get(copy);
        values += org.triplehelix.wpilogmcp.nt4.ValueFrame.decode(copy).size();
      }
    }
    @Override public void closeConnection(int code, String reason) { dropped = reason; }
  }

  @Test void aHealthyPeerSurvivesAStalledLoopAndANetworkPongAheadOfTheNextHeartbeat() throws Exception {
    try (var f = new Fixture()) {
      f.loop.advance(200_000); assertEquals(1, f.peer.pings);
      f.loop.elapse(1_500_000); f.loop.advance(0);
      assertNull(f.peer.dropped, "A stalled loop sent no unanswered ping during the stall");
      f.peer.answer = false; f.loop.advance(200_000);
      f.loop.elapse(1_500_000);
      // Delivery is on the network thread before the overdue heartbeat runs, not queued behind it.
      f.server.onWebsocketPong(f.peer, new PongFrame()); f.loop.advance(0);
      assertNull(f.peer.dropped, "Pong receipt must not wait behind the heartbeat");
    }
  }

  @Test void anUnansweredPingExpiresAtOneSecondWithoutLaterPingsResettingItsDeadline() throws Exception {
    try (var f = new Fixture()) {
      f.loop.advance(200_000); f.peer.answer = false;
      f.loop.advance(200_000); // First unanswered ping, at 400 ms.
      for (int i = 0; i < 4; i++) f.loop.advance(200_000);
      assertNull(f.peer.dropped); assertEquals(6, f.peer.pings);
      f.loop.elapse(199_999); assertNull(f.peer.dropped);
      f.loop.advance(1); assertEquals("NT4 pong timeout", f.peer.dropped);
    }
  }

  @Test void networkPingReplyDoesNotWaitForTheFanOutLoopButAnOverrunDropDoes() throws Exception {
    try (var f = new Fixture()) {
      f.loop.elapse(1_500_000);
      f.server.onWebsocketPing(f.peer, new PingFrame());
      assertEquals(1, f.peer.pongs, "Reply before any fan-out task runs");
      f.peer.outQueue.add(java.nio.ByteBuffer.allocate(1)); f.peer.outQueue.add(java.nio.ByteBuffer.allocate(1));
      f.server.onWebsocketPing(f.peer, new PingFrame());
      assertEquals(1, f.peer.pongs); assertNull(f.peer.dropped);
      f.loop.drain(); assertEquals("NT4 client fell behind: socket send queue limit", f.peer.dropped);
    }
  }
}
