/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.server;

import java.nio.channels.SelectionKey;
import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.server.WebSocketServer;

/**
 * Repairs Java-WebSocket 1.6.0's lost write demand. Its selector clears OP_WRITE after
 * draining a batch, concurrently with onWriteDemand setting it for a new publication.
 * A 4.0 peer has no ping to unstick that final queued frame. Check from the existing
 * aliveness tick, without writing, copying, resending, or walking a socket's queue.
 */
final class SocketWrites {
  private SocketWrites() {}

  static void rearm(WebSocketServer server, WebSocket socket) {
    var connection = (WebSocketImpl) socket;
    if (!connection.isOpen() || connection.outQueue.isEmpty()) return;
    var key = connection.getSelectionKey();
    try {
      if (key != null && key.isValid() && (key.interestOps() & SelectionKey.OP_WRITE) == 0) server.onWriteDemand(socket);
    } catch (java.nio.channels.CancelledKeyException | java.nio.channels.ClosedSelectorException ignored) {
      // Disconnect won the race; its owner performs cleanup.
    }
  }
}
