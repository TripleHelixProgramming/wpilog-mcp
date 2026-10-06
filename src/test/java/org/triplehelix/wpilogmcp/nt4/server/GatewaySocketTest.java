/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.server;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.nt4.ControlMessage;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.*;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;

class GatewaySocketTest {
  private static final class WireClient implements WebSocket.Listener, AutoCloseable {
    final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
    final StringBuilder text = new StringBuilder();
    final ByteArrayOutputStream binary = new ByteArrayOutputStream();
    final WebSocket socket;
    WireClient(int port, String protocol) throws Exception {
      socket = HttpClient.newHttpClient().newWebSocketBuilder().subprotocols(protocol)
          .buildAsync(RobotAddress.uri("127.0.0.1", port, "wire"), this).get(5, TimeUnit.SECONDS);
    }
    @Override public void onOpen(WebSocket socket) { socket.request(1); }
    @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
      text.append(data);
      if (last) { events.addAll(ControlMessage.decode(text.toString())); text.setLength(0); }
      socket.request(1); return null;
    }
    @Override public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
      var part = new byte[data.remaining()]; data.get(part); binary.writeBytes(part);
      if (last) { events.addAll(ValueFrame.decode(binary.toByteArray())); binary.reset(); }
      socket.request(1); return null;
    }
    void send(ControlMessage m) { socket.sendText(ControlMessage.encode(List.of(m)), true).join(); }
    void send(ValueFrame v) { socket.sendBinary(ByteBuffer.wrap(v.encode()), true).join(); }
    Object next() throws Exception {
      var event = events.poll(5, TimeUnit.SECONDS); assertNotNull(event, "Expected gateway event"); return event;
    }
    @Override public void close() { socket.abort(); }
  }

  @Test void realClientsSeeEveryChangeOrLatestAndWritesDoNotLeak() throws Exception {
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 1234567)) {
      gateway.start().get(5, TimeUnit.SECONDS); gateway.announce("/x", "int", new JsonObject()).join();
      try (var all = new WireClient(gateway.port(), Nt4Client.V41);
           var sampled = new WireClient(gateway.port(), Nt4Client.V40)) {
        all.send(GatewayCoreTest.sub(0, "", "{\"prefix\":true,\"all\":true,\"periodic\":60}"));
        sampled.send(GatewayCoreTest.sub(0, "", "{\"prefix\":true,\"periodic\":60}"));
        assertEquals("/x", ((Announce) all.next()).name()); assertEquals("/x", ((Announce) sampled.next()).name());
        sampled.send(new Publish("/x", 99, "string", new JsonObject()));
        var reply = (Announce) sampled.next(); assertEquals("int", reply.type()); assertEquals(99, reply.pubuid());
        sampled.send(new ValueFrame(99, 500, 2, 999L));
        var properties = new JsonObject(); properties.addProperty("retained", true);
        sampled.send(new SetProperties("/x", properties));
        var ack = (Properties) sampled.next(); assertTrue(ack.ack()); assertTrue(ack.update().get("retained").isJsonNull());
        // One ordered TCP exchange proves the write was processed before the upstream feed.
        sampled.send(new ValueFrame(-1, 0, 1, 2.5));
        assertEquals(new ValueFrame(-1, 1234567, 1, 2.5), sampled.next());
        for (long n = 1; n <= 3; n++) gateway.value("/x", 100 + n, 2, n).join();
        gateway.unannounce("/x").join(); // Flushes while the 60 s period is still pending.
        for (long n = 1; n <= 3; n++) {
          var value = (ValueFrame) all.next(); assertEquals(n, value.value()); assertEquals(100 + n, value.timestampUs());
        }
        assertInstanceOf(Unannounce.class, all.next());
        var last = (ValueFrame) sampled.next(); assertEquals(3L, last.value()); assertEquals(103, last.timestampUs());
        assertInstanceOf(Unannounce.class, sampled.next());
        for (var client : List.of(all, sampled)) {
          client.send(new ValueFrame(-1, 0, 2, 7L)); assertEquals(-1, ((ValueFrame) client.next()).topicId());
          assertTrue(client.events.isEmpty(), "No extra write or duplicate values before the barrier");
        }
      }
    }
  }

  @Test void handshakeNeedsNtPathAndCommonSubprotocolAndLoopback() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> new Nt4Gateway(new InetSocketAddress("0.0.0.0", 5810), () -> 0));
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 0)) {
      gateway.start().get(5, TimeUnit.SECONDS);
      var http = HttpClient.newHttpClient();
      assertThrows(java.util.concurrent.ExecutionException.class, () -> http.newWebSocketBuilder().subprotocols("not-nt4")
          .buildAsync(RobotAddress.uri("127.0.0.1", gateway.port(), "bad"), new WebSocket.Listener() {}).get(5, TimeUnit.SECONDS));
      assertThrows(java.util.concurrent.ExecutionException.class, () -> http.newWebSocketBuilder().subprotocols(Nt4Client.V41)
          .buildAsync(URI.create("ws://127.0.0.1:" + gateway.port() + "/other"), new WebSocket.Listener() {}).get(5, TimeUnit.SECONDS));
      try (var valid = new WireClient(gateway.port(), Nt4Client.V41)) { assertEquals(Nt4Client.V41, valid.socket.getSubprotocol()); }
    }
  }
}
