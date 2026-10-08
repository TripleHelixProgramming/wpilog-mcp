/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;

/** The batching claim is bounded by independently read fixture payloads, not a wall-time guess. */
class ReplayPacingTest {
  @Test void nativeBatchAmortizesTheHandshakeAndFitsTheCopiedWorkBudget() throws Exception {
    String source = Files.readString(Path.of("harness/robot/src/main/java/org/triplehelix/harness/LogReplay.java"));
    var match = java.util.regex.Pattern.compile("int BATCH = ([0-9_]+);").matcher(source);
    assertTrue(match.find()); int batch = Integer.parseInt(match.group(1).replace("_", ""));
    assertEquals(32_768, batch, "The record bound amortizes handshakes up to 32,768 values, subject to the native byte limit");
    long largestWindow = 0;
    for (var fixture : FixtureLogs.generateAll(FixtureLogs.defaultDirectory())) {
      var window = new java.util.ArrayDeque<Long>(); long bytes = 0;
      try (var records = new IndependentLog.Records(fixture.path())) {
        for (var record = records.at(records.first); record != null; record = records.at(record.end())) {
          if (record.id() != 0) {
            long size = record.payloadSize() * 2L + 64;
            window.addLast(size); bytes += size;
            if (window.size() > batch) bytes -= window.removeFirst();
            largestWindow = Math.max(largestWindow, bytes);
          }
        }
      }
    }
    assertTrue(largestWindow < (4L << 20), "Recheck the comment's fixture window allowance: " + largestWindow);
    assertTrue(largestWindow < (32L << 20) / 8, "Room for framing and copied work");
    String verifier = Files.readString(Path.of("src/test/java/org/triplehelix/wpilogmcp/conformance/NativeReplayProcess.java"));
    assertFalse(verifier.contains("awaitProgress("), "Receipts and pipe notifications wake the verifier");
    assertTrue(verifier.contains("capture.receivedThrough("));
  }
}
