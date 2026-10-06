/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** One NT4 value; timestamp is the server's microseconds, never receipt time. */
public record ValueFrame(int topicId, long timestampUs, int typeCode, Object value) {
  public ValueFrame {
    if (topicId < -1) throw new IllegalArgumentException("Invalid topic id");
    value = NtType.checkedValue(typeCode, value);
  }

  @Override public Object value() {
    return value instanceof byte[] bytes ? bytes.clone() : value;
  }

  public byte[] encode() {
    return MessagePack.encode(List.of(topicId, timestampUs, typeCode, value));
  }

  public static byte[] encode(List<ValueFrame> frames) {
    var out = new ByteArrayOutputStream();
    for (var frame : frames) out.writeBytes(frame.encode());
    if (out.size() > MessagePack.MAX_BYTES) throw new IllegalArgumentException("NT4 message too large");
    return out.toByteArray();
  }

  /** Invalid shapes/families terminate the connection; unsupported IDs/codes are ignored. */
  public static List<ValueFrame> decode(byte[] bytes) {
    var frames = new ArrayList<ValueFrame>();
    for (var object : MessagePack.decodeStream(bytes)) {
      if (!(object instanceof List<?> a) || a.size() != 4
          || !(a.get(1) instanceof Long timestamp)) {
        throw new IllegalArgumentException("Invalid NT4 value frame");
      }
      // Unsigned 64-bit IDs/codes are valid MessagePack but outside our supported ID/type range.
      if (a.get(0) instanceof java.math.BigInteger || a.get(2) instanceof java.math.BigInteger) continue;
      if (!(a.get(0) instanceof Long id) || !(a.get(2) instanceof Long code)) {
        throw new IllegalArgumentException("Invalid NT4 id/type");
      }
      if (id < -1 || id > Integer.MAX_VALUE || code < 0 || code > 20
          || code > 5 && code < 16) continue;
      frames.add(new ValueFrame(id.intValue(), timestamp, code.intValue(), a.get(3)));
    }
    return List.copyOf(frames);
  }
}
