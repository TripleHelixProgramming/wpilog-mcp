/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.server;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.util.List;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.framing.BinaryFrame;
import org.java_websocket.framing.ContinuousFrame;
import org.junit.jupiter.api.Test;

class GatewayReceiveBudgetTest {
  @Test void unfinishedMessagesAreBoundedBeforeTheFinalFragmentArrives() throws Exception {
    // The library copies the draft for each socket: test that copy, not an unused prototype.
    var draft = Nt4Gateway.receiveDraft(List.of("v4.1.networktables.first.wpi.edu"), 16, 8).copyInstance();
    var first = new BinaryFrame(); first.setFin(false); first.setPayload(ByteBuffer.allocate(8));
    draft.processFrame(null, first);
    var more = new ContinuousFrame(); more.setFin(false); more.setPayload(ByteBuffer.allocate(8));
    draft.processFrame(null, more);
    var failure = assertThrows(InvalidDataException.class, () -> draft.processFrame(null, more));
    assertEquals(1009, failure.getCloseCode());
  }
  @Test void zeroByteFragmentsStillConsumeTheFragmentBudget() throws Exception {
    var draft = Nt4Gateway.receiveDraft(List.of(), 16, 2).copyInstance();
    var first = new BinaryFrame(); first.setFin(false); first.setPayload(ByteBuffer.allocate(0));
    draft.processFrame(null, first);
    var more = new ContinuousFrame(); more.setFin(false); more.setPayload(ByteBuffer.allocate(0));
    draft.processFrame(null, more);
    assertEquals(1009, assertThrows(InvalidDataException.class,
        () -> draft.processFrame(null, more)).getCloseCode());
  }
}
