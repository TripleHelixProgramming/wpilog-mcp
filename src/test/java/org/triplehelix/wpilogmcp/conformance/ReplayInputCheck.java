/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import org.triplehelix.wpilogmcp.log.ScopedLogReader;

/** Cross-check an impossible string with WPILib's reader and a reporting UTF-8 decoder. */
final class ReplayInputCheck {
  private ReplayInputCheck() {}
  static long invalidUtf8(Path path) throws Exception {
    long invalid = 0;
    try (var scope = new ScopedLogReader(path)) {
      var types = new HashMap<Integer, String>();
      for (var record : scope.reader()) {
        if (record.isStart()) { var start = record.getStartData(); types.put(start.entry, start.type); }
        else if (record.isFinish()) types.remove(record.getFinishEntry());
        else if (!record.isControl()) {
          String type = types.get(record.getEntry());
          if (!java.util.Set.of("string", "json", "string[]").contains(type == null ? "" : type)) continue;
          var decoder = StandardCharsets.UTF_8.newDecoder();
          var bytes = ByteBuffer.wrap(record.getRaw()).order(ByteOrder.LITTLE_ENDIAN);
          try {
            if (type.equals("string[]")) {
              int count = bytes.getInt();
              for (int i = 0; i < count; i++) {
                int length = bytes.getInt(); decoder.decode(bytes.slice(bytes.position(), length)); bytes.position(bytes.position() + length);
              }
            } else decoder.decode(bytes);
          } catch (java.nio.charset.CharacterCodingException malformed) { invalid++; }
        }
      }
    }
    return invalid;
  }
}
