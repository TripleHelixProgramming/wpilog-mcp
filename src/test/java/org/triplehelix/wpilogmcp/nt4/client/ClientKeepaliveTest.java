/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.client;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;

class ClientKeepaliveTest {
  private Nt4Client client(ControlledSockets sockets, ManualScheduler loop, Nt4Client.Listener listener) {
    sockets.protocol = Nt4Client.V41;
    return new Nt4Client(List.of(RobotAddress.uri("127.0.0.1", 5810, "test")),
        Nt4Client.captureSubscription(0.01), listener, sockets, loop);
  }

  @Test void aStalledListenerCannotBlameAHealthyServerOrDelayNetworkPongReceipt() {
    var loop = new ManualScheduler(); var sockets = new ControlledSockets();
    try (var client = client(sockets, loop, new Nt4Client.Listener() {
      @Override public void announce(Announce topic) {
        long requests = sockets.peers.get(0).demand;
        assertTrue(requests >= 3, "Receive demand must continue before the listener completes");
        loop.elapse(1_500_000); // The listener is occupied; no event-loop heartbeat ran.
      }
    })) {
      client.start(); loop.drain(); var peer = sockets.peers.get(0);
      peer.text(new Announce("/x", 1, "int", null, new JsonObject())); loop.drain();
      loop.advance(0); assertTrue(client.isConnected(), "Answered ping survives a 1.5 s listener stall");
      peer.answerPing = false; loop.advance(200_000);
      loop.elapse(1_500_000); peer.pong(); loop.advance(0);
      assertTrue(client.isConnected(), "A received pong must be visible before queued listener work");
    }
    loop.drain();
  }

  @Test void unansweredPingExpiresOneSecondAfterSendEvenThoughMorePingsAreSent() {
    var loop = new ManualScheduler(); var sockets = new ControlledSockets();
    try (var client = client(sockets, loop, new Nt4Client.Listener() {})) {
      client.start(); loop.drain(); var peer = sockets.peers.get(0); peer.answerPing = false;
      loop.advance(200_000);
      for (int i = 0; i < 4; i++) loop.advance(200_000);
      assertTrue(client.isConnected()); assertEquals(6, peer.pings);
      loop.elapse(199_999); assertTrue(client.isConnected());
      loop.advance(1); assertFalse(client.isConnected());
      assertEquals("NT4 pong timeout", client.disconnectReason());
    }
    loop.drain();
  }

  @Test void queuedListenerWorkIsBoundedWhileNetworkDemandContinues() {
    var loop = new ManualScheduler(); var sockets = new ControlledSockets();
    try (var client = client(sockets, loop, new Nt4Client.Listener() {})) {
      client.start(); loop.drain();
      var properties = new JsonObject(); properties.addProperty("padding", "x".repeat(64 * 1024));
      var topic = new Announce("/x", 1, "int", null, properties);
      for (int i = 0; i < 257; i++) sockets.peers.get(0).text(topic);
      loop.drain();
      assertFalse(client.isConnected()); assertEquals("NT4 listener fell behind: receive queue limit", client.disconnectReason());
    }
    loop.drain();
  }
}
