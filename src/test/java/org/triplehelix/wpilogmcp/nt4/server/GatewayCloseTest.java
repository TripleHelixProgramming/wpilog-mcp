/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.server;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;

class GatewayCloseTest {
  @Test void dropWaitsForThePeerCloseReplyAndTheClosedPeerIsRemoved() throws Exception {
    var receivedClose = new CompletableFuture<Integer>();
    var reply = new CompletableFuture<Void>();
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 0, List.of(Nt4Client.V40))) {
      gateway.start().get(30, TimeUnit.SECONDS);
      var peer = HttpClient.newHttpClient().newWebSocketBuilder().subprotocols(Nt4Client.V40)
          .buildAsync(RobotAddress.uri("127.0.0.1", gateway.port(), "close-barrier"), new WebSocket.Listener() {
            public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
              receivedClose.complete(code);
              // The JDK sends its close reply when this stage completes. Hold that ordering,
              // rather than hoping a loaded machine delays the peer for a useful duration.
              return reply;
            }
          }).get(30, TimeUnit.SECONDS);
      try {
        // onOpen is ordered before a message from this connection, not the HTTP handshake.
        org.triplehelix.wpilogmcp.harness.HarnessHttp.await("registered peer", 30, () -> gateway.clientCount() == 1);
        var dropped = gateway.dropClients();
        int code = receivedClose.get(30, TimeUnit.SECONDS);
        // An unrelated loop action proves the close request has run. No sleep proves absence.
        gateway.endSession().get(30, TimeUnit.SECONDS);
        assertFalse(dropped.isDone(), "dropClients must wait for the peer's close reply, not just request closure");
        assertEquals(1001, code);
        assertEquals(1, gateway.clientCount());
        reply.complete(null);
        dropped.get(30, TimeUnit.SECONDS);
        assertEquals(0, gateway.clientCount(), "The close barrier includes ordered peer removal");
        assertTrue(peer.isInputClosed());
      } finally { reply.complete(null); peer.abort(); }
    }
  }
}
