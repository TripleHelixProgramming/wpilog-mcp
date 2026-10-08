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
  private static final class Fixture implements AutoCloseable {
    final ManualScheduler loop = new ManualScheduler(), binds = new ManualScheduler();
    final Nt4Gateway gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 0,
        List.of(Nt4Client.V41), 2, (name, reason) -> {}, Clock.systemUTC(), binds, loop);
    final WebSocketServer server;
    final Peer peer;
    Fixture() throws Exception {
      var started = gateway.start(); binds.until(started::isDone); started.join();
      var field = Nt4Gateway.class.getDeclaredField("server"); field.setAccessible(true);
      server = (WebSocketServer) field.get(gateway); peer = new Peer(server);
      var request = new HandshakeImpl1Client(); request.setResourceDescriptor("/nt/healthy");
      server.onOpen(peer, request); loop.drain();
    }
    public void close() throws Exception { gateway.close(); }
  }
  private static final class Peer extends WebSocketImpl {
    final WebSocketServer server;
    boolean answer = true;
    int pings, pongs;
    String dropped;
    Peer(WebSocketServer server) { super(server, new Draft_6455()); this.server = server; }
    @Override public Protocol getProtocol() { return new Protocol(Nt4Client.V41); }
    @Override public boolean isOpen() { return dropped == null; }
    @Override public void sendPing() { pings++; if (answer) server.onWebsocketPong(this, new PongFrame()); }
    @Override public void sendFrame(Framedata frame) { if (frame instanceof PongFrame) pongs++; }
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
