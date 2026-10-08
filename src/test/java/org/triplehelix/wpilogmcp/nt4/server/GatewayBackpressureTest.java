/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.server;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.nt4.ControlMessage;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client;

/** A real TCP receive window stops advancing; no fake WebSocket can stand in for backpressure. */
class GatewayBackpressureTest {
  static final class PausedReader implements AutoCloseable {
    final Socket socket = new Socket();
    PausedReader(int port) throws Exception {
      socket.setReceiveBufferSize(1024); socket.setSoTimeout(5000);
      socket.connect(new InetSocketAddress("127.0.0.1", port));
      var output = socket.getOutputStream();
      output.write(("GET /nt/paused HTTP/1.1\r\nHost: 127.0.0.1:" + port
          + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13"
          + "\r\nSec-WebSocket-Key: AAAAAAAAAAAAAAAAAAAAAA==\r\nSec-WebSocket-Protocol: "
          + Nt4Client.V40 + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
      var headers = new StringBuilder(); var input = new DataInputStream(socket.getInputStream());
      while (!headers.toString().endsWith("\r\n\r\n")) headers.append((char) input.readUnsignedByte());
      assertTrue(headers.toString().startsWith("HTTP/1.1 101"), headers.toString());
      var subscription = GatewayCoreTest.sub(0, "/payload", "{all:true,periodic:0.001}");
      byte[] text = ControlMessage.encode(List.of(subscription)).getBytes(StandardCharsets.UTF_8);
      var out = new DataOutputStream(output); out.writeByte(0x81);
      if (text.length < 126) out.writeByte(0x80 | text.length);
      else { out.writeByte(0x80 | 126); out.writeShort(text.length); }
      byte[] mask = {1, 2, 3, 4}; out.write(mask);
      for (int i = 0; i < text.length; i++) out.writeByte(text[i] ^ mask[i % 4]);
      out.flush();
      // Read the announce to prove the subscription is installed, then never read another byte.
      assertEquals(0x81, input.readUnsignedByte());
      int length = input.readUnsignedByte(); assertTrue(length < 128);
      if (length == 126) length = input.readUnsignedShort();
      byte[] announcement = input.readNBytes(length); assertEquals(length, announcement.length);
      assertEquals("/payload", ((ControlMessage.Announce) ControlMessage.decode(new String(announcement, StandardCharsets.UTF_8)).get(0)).name());
    }
    @Override public void close() throws Exception { socket.close(); }
  }

  @Test void unreadSocketIsDroppedWhileAnotherClientReceivesEveryPublication() throws Exception {
    var dropped = new LinkedBlockingQueue<String>();
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 1234567,
        List.of(Nt4Client.V40), 128, (name, reason) -> dropped.add(name + ": " + reason))) {
      gateway.start().get(5, TimeUnit.SECONDS); gateway.announce("/payload", "raw", new JsonObject()).join();
      try (var paused = new PausedReader(gateway.port());
           var reading = new GatewaySocketTest.WireClient(gateway.port(), Nt4Client.V40)) {
        reading.send(GatewayCoreTest.sub(0, "/payload", "{all:true,periodic:0.001}"));
        assertInstanceOf(ControlMessage.Announce.class, reading.next());
        assertEquals(2, gateway.clientCount());
        // More than TCP's send buffer even on hosts that tune it upward. Each active receive
        // is a barrier, so this checks an unread peer, not an artificial producer burst.
        for (int i = 0; i < 512; i++) {
          byte[] bytes = new byte[32 * 1024]; java.util.Arrays.fill(bytes, (byte) i);
          gateway.value("/payload", 1000 + i, 5, bytes).get(2, TimeUnit.SECONDS);
          var frame = (ValueFrame) reading.next();
          assertEquals(1000 + i, frame.timestampUs()); assertArrayEquals(bytes, (byte[]) frame.value());
        }
        String reason = dropped.poll(5, TimeUnit.SECONDS); assertNotNull(reason, "An unread socket must have a finite send queue");
        assertEquals("paused: NT4 client fell behind: socket send queue limit", reason);
        assertEquals(1, gateway.clientCount()); assertTrue(dropped.isEmpty(), "The reading client must stay connected");
        reading.send(new ValueFrame(-1, 0, 2, 17L));
        assertEquals(new ValueFrame(-1, 1234567, 2, 17L), reading.next());
        assertTrue(reading.events.isEmpty(), "Every value arrived exactly once");
      }
    }
  }
}
