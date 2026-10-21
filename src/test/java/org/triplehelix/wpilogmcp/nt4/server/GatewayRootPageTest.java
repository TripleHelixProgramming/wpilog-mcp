/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.server;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketAdapter;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.enums.ReadyState;
import org.java_websocket.handshake.Handshakedata;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.Test;

/**
 * Feeds the gateway's own connections the bytes TCP may deliver, in pieces, without a selector.
 * AdvantageScope 26 requests {@code GET /} on the NT4 port and opens its WebSocket only after a
 * 2xx answer, which ntcore gives; GatewaySocketTest makes the same request over a real socket.
 */
class GatewayRootPageTest {
  private static final String PAGE = "<html><head><title>NetworkTables</title></head><body><p>WebSockets must be"
      + " used to access NetworkTables.</body></html>";

  /** Keeps what a connection queues for the network instead of writing it. */
  private static final class Sink extends WebSocketAdapter {
    @Override public void onWebsocketMessage(WebSocket socket, String message) {}
    @Override public void onWebsocketMessage(WebSocket socket, ByteBuffer blob) {}
    @Override public void onWebsocketOpen(WebSocket socket, Handshakedata handshake) {}
    @Override public void onWebsocketClose(WebSocket socket, int code, String reason, boolean remote) {}
    @Override public void onWebsocketClosing(WebSocket socket, int code, String reason, boolean remote) {}
    @Override public void onWebsocketCloseInitiated(WebSocket socket, int code, String reason) {}
    @Override public void onWebsocketError(WebSocket socket, Exception error) {}
    @Override public void onWriteDemand(WebSocket socket) {}
    @Override public InetSocketAddress getLocalSocketAddress(WebSocket socket) { return null; }
    @Override public InetSocketAddress getRemoteSocketAddress(WebSocket socket) { return null; }
  }

  /** A connection made by the bound gateway's own factory, so the test covers its wiring. */
  private static WebSocketImpl connection(Nt4Gateway gateway) throws Exception {
    var field = Nt4Gateway.class.getDeclaredField("server"); field.setAccessible(true);
    var server = (WebSocketServer) field.get(gateway);
    return (WebSocketImpl) server.getWebSocketFactory().createWebSocket(new Sink(), List.of());
  }

  private static void receive(WebSocketImpl connection, String bytes) {
    connection.decode(ByteBuffer.wrap(bytes.getBytes(StandardCharsets.ISO_8859_1)));
  }

  private static String written(WebSocketImpl connection) {
    var out = new StringBuilder();
    for (ByteBuffer queued; (queued = connection.outQueue.poll()) != null; ) out.append(StandardCharsets.ISO_8859_1.decode(queued));
    return out.toString();
  }

  @Test void aRootRequestSplitAcrossReadsGetsNtcoresPageOnEachPollOfAKeptAliveConnection() throws Exception {
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 0)) {
      gateway.start().get(5, TimeUnit.SECONDS);
      var connection = connection(gateway);
      // AdvantageScope reuses one keep-alive connection, polling every 350 ms until it connects.
      for (int poll = 0; poll < 2; poll++) {
        receive(connection, "GET / HTTP/1.1\r\nHost: 10.23.63.240:5810\r\nConnection: keep-");
        assertEquals("", written(connection), "No answer before the request's header is complete");
        receive(connection, "alive\r\nUser-Agent: AdvantageScope/26.0.0\r\nAccept: */*\r\n\r\n");
        var answer = written(connection);
        assertTrue(answer.startsWith("HTTP/1.1 200 OK\r\n"), answer);
        assertTrue(answer.contains("\r\nAccess-Control-Allow-Origin: *\r\n"), answer);
        assertTrue(answer.contains("\r\nContent-Length: " + PAGE.length() + "\r\n"), answer);
        assertTrue(answer.endsWith("\r\n\r\n" + PAGE), answer);
        assertEquals(ReadyState.NOT_YET_CONNECTED, connection.getReadyState(), "The connection stays open for the next poll");
      }
    }
  }

  @Test void anH2cOfferIsNotAWebSocketUpgradeAndGetsThePage() throws Exception {
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 0)) {
      gateway.start().get(5, TimeUnit.SECONDS);
      var connection = connection(gateway);
      receive(connection, "GET / HTTP/1.1\r\nHost: pit\r\nConnection: Upgrade, HTTP2-Settings\r\nUpgrade: h2c\r\n"
          + "HTTP2-Settings: AAEAAEAAAAIAAAABAAMAAABkAAQBAAAAAAUAAEAA\r\n\r\n");
      assertTrue(written(connection).startsWith("HTTP/1.1 200 OK\r\n"));
    }
  }

  @Test void aWebSocketHandshakeSplitAcrossReadsStillReachesTheLibrary() throws Exception {
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 0)) {
      gateway.start().get(5, TimeUnit.SECONDS);
      var connection = connection(gateway);
      receive(connection, "GET /nt/AdvantageScope HTTP/1.1\r\nHost: pit\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n");
      assertEquals("", written(connection));
      receive(connection, "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n"
          + "Sec-WebSocket-Protocol: v4.1.networktables.first.wpi.edu, networktables.first.wpi.edu\r\n\r\n");
      var answer = written(connection);
      assertTrue(answer.startsWith("HTTP/1.1 101 "), answer);
      assertTrue(answer.contains("Sec-WebSocket-Protocol: v4.1.networktables.first.wpi.edu"), answer);
      assertEquals(ReadyState.OPEN, connection.getReadyState());
    }
  }

  @Test void otherPlainRequestsKeepTheLibrarysRefusal() throws Exception {
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 0)) {
      gateway.start().get(5, TimeUnit.SECONDS);
      for (var request : List.of("GET /other HTTP/1.1\r\nHost: pit\r\n\r\n", "GET /nt/ HTTP/1.1\r\nHost: pit\r\n\r\n")) {
        var connection = connection(gateway);
        receive(connection, request);
        var answer = written(connection);
        assertTrue(answer.startsWith("HTTP/1.1 404 "), request + " -> " + answer);
      }
    }
  }
}
