/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.server;

import java.util.ArrayList;
import java.util.List;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.enums.Opcode;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.framing.Framedata;
import org.java_websocket.protocols.Protocol;

/** Java-WebSocket checks aggregate size at FIN; an unfinished message needs a budget earlier. */
final class BoundedReceiveDraft extends Draft_6455 {
  private final List<String> protocols;
  private final int maxBytes, maxFragments;
  private long bytes;
  private int fragments;

  BoundedReceiveDraft(List<String> protocols, int maxBytes, int maxFragments) {
    super(List.of(), new ArrayList<>(protocols.stream().map(Protocol::new).toList()), maxBytes);
    this.protocols = List.copyOf(protocols); this.maxBytes = maxBytes; this.maxFragments = maxFragments;
  }

  @Override public void processFrame(WebSocketImpl socket, Framedata frame) throws InvalidDataException {
    var opcode = frame.getOpcode();
    boolean data = opcode == Opcode.TEXT || opcode == Opcode.BINARY || opcode == Opcode.CONTINUOUS;
    if (data) {
      if (opcode != Opcode.CONTINUOUS) { bytes = 0; fragments = 0; }
      bytes += frame.getPayloadData().remaining(); fragments++;
      if (bytes > maxBytes || fragments > maxFragments) {
        throw new InvalidDataException(1009, "NT4 receive message exceeds byte or fragment budget");
      }
    }
    super.processFrame(socket, frame);
    if (data && frame.isFin()) { bytes = 0; fragments = 0; }
  }

  @Override public Draft copyInstance() { return new BoundedReceiveDraft(protocols, maxBytes, maxFragments); }
  @Override public void reset() { super.reset(); bytes = 0; fragments = 0; }
}
