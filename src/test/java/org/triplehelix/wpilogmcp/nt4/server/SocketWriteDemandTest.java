/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.server;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.concurrent.TimeUnit;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.harness.HarnessHttp;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client;

class SocketWriteDemandTest {
  @ParameterizedTest @ValueSource(booleans = {false, true})
  void aQueuedFrameWithLostWriteInterestStillArrivesWithoutAnotherSend(boolean scripted) throws Exception {
    try (var peer = scripted ? new ScriptedPeer(Nt4Client.V40) : null;
        var gateway = scripted ? null : new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 0)) {
      final WebSocketServer server;
      if (gateway != null) {
        gateway.start().get(5, TimeUnit.SECONDS);
        // Fault injection only: keep the adapter's socket implementation out of its public API.
        var field = Nt4Gateway.class.getDeclaredField("server"); field.setAccessible(true);
        server = (WebSocketServer) field.get(gateway);
      } else server = peer;
      try (var client = new GatewaySocketTest.WireClient(server.getPort(), Nt4Client.V40)) {
        client.send(new ValueFrame(-1, 0, 2, 0L)); assertInstanceOf(ValueFrame.class, client.next());
        var socket = (WebSocketImpl) server.getConnections().iterator().next();
        HarnessHttp.await("idle selector", 5, () -> socket.outQueue.isEmpty()
            && socket.getSelectionKey().interestOps() == SelectionKey.OP_READ);
        // Plant exactly the observed 1.6.0 race: enqueue happened, then doWrite cleared
        // OP_WRITE after onWriteDemand set it. A final binary RFC 6455 frame carries the
        // hand-encoded MessagePack [1, 2, 2, 7]. There is no later message or 4.1 ping to wake it.
        socket.outQueue.add(ByteBuffer.wrap(new byte[] {(byte) 0x82, 5, (byte) 0x94, 1, 2, 2, 7}));
        assertEquals(new ValueFrame(1, 2, 2, 7L), client.next());
        client.send(new ValueFrame(-1, 0, 2, 9L));
        var barrier = (ValueFrame) client.next(); assertEquals(-1, barrier.topicId()); assertEquals(9L, barrier.value());
        assertTrue(client.events.isEmpty(), "Rearming does not resend or duplicate a frame");
      }
    }
  }
}
