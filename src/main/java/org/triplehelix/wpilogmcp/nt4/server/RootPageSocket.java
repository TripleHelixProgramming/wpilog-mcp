/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.server;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.WebSocketListener;
import org.java_websocket.drafts.Draft;
import org.java_websocket.enums.ReadyState;

/**
 * Answers the plain {@code GET /} that ntcore answers, before Java-WebSocket sees the request.
 * AdvantageScope 26 requests that page on the NT4 port and opens its WebSocket only after a 2xx
 * answer. The library answers every request that is not a WebSocket upgrade with a 404, so
 * AdvantageScope polled the gateway every 350 ms and never connected. Every other request
 * reaches the library unchanged, so handshakes and refusals behave as before.
 */
final class RootPageSocket extends WebSocketImpl {
  /** ntcore's page, headers and status, so a client gets the same answer from the robot and the pit. */
  private static final byte[] RESPONSE = response(
      "<html><head><title>NetworkTables</title></head><body><p>WebSockets must be used to access NetworkTables.</body></html>");
  /** A request whose header runs past this is not the probe; the library decides what it is. */
  private static final int MAX_HEADER_BYTES = 8192;

  // The server decodes on one thread, in arrival order, so these need no lock.
  private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
  private boolean delegated;

  RootPageSocket(WebSocketListener listener, List<Draft> drafts) { super(listener, drafts); }

  @Override public void decode(ByteBuffer socketBuffer) {
    if (delegated || getReadyState() != ReadyState.NOT_YET_CONNECTED) { super.decode(socketBuffer); return; }
    var bytes = new byte[socketBuffer.remaining()]; socketBuffer.get(bytes); pending.writeBytes(bytes);
    // A kept-alive connection can carry several polls before the client gives up on it.
    while (pending.size() > 0) {
      byte[] received = pending.toByteArray();
      int end = headerEnd(received);
      if (end < 0 && received.length < MAX_HEADER_BYTES) return; // The rest of the header is on its way.
      if (end < 0 || !rootPageRequest(new String(received, 0, end, StandardCharsets.ISO_8859_1))) {
        // The library sees the whole request at once, as it would from a single read.
        delegated = true; pending.reset();
        super.decode(ByteBuffer.wrap(received));
        return;
      }
      outQueue.add(ByteBuffer.wrap(RESPONSE));
      getWebSocketListener().onWriteDemand(this);
      pending.reset(); pending.write(received, end, received.length - end);
    }
  }

  /** A GET of the root path, as ntcore parses it (a query is allowed), that is not a WebSocket upgrade. */
  static boolean rootPageRequest(String header) {
    var lines = header.split("\r\n");
    var request = lines[0].split(" ", -1);
    if (request.length != 3 || !request[0].equals("GET") || !request[2].startsWith("HTTP/1.")) return false;
    if (!request[1].equals("/") && !request[1].startsWith("/?")) return false;
    for (int i = 1; i < lines.length; i++) {
      int colon = lines[i].indexOf(':');
      if (colon > 0 && lines[i].substring(0, colon).trim().equalsIgnoreCase("Upgrade")
          && lines[i].substring(colon + 1).toLowerCase(Locale.ROOT).contains("websocket")) return false;
    }
    return true;
  }

  /** The index just past the blank line that ends the header, or -1 before it arrives. */
  private static int headerEnd(byte[] received) {
    for (int i = 3; i < received.length; i++) {
      if (received[i - 3] == '\r' && received[i - 2] == '\n' && received[i - 1] == '\r' && received[i] == '\n') return i + 1;
    }
    return -1;
  }

  private static byte[] response(String page) {
    byte[] body = page.getBytes(StandardCharsets.US_ASCII);
    byte[] head = """
        HTTP/1.1 200 OK\r
        Content-Type: text/html\r
        Content-Length: %d\r
        Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r
        Access-Control-Allow-Origin: *\r
        Access-Control-Allow-Methods: *\r
        \r
        """.formatted(body.length).getBytes(StandardCharsets.US_ASCII);
    var out = new ByteArrayOutputStream(); out.writeBytes(head); out.writeBytes(body);
    return out.toByteArray();
  }
}
