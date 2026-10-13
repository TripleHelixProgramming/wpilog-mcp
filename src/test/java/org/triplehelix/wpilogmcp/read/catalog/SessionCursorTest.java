/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.catalog;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.read.*;

class SessionCursorTest {
  private SessionCursor.Child child(String name) {
    return new SessionCursor.Child(new SampleCursor() {
      public SampleBatch next() { return new SampleBatch(List.of(new Sample(1, 10, 0, 1L, 5 << 20)), List.of(), 1, 5 << 20, true); }
      public void close() {}
    }, new ReadSnapshot.SourceFile(name, null, null, List.of()));
  }
  @Test void mergeLookaheadCannotMultiplyTheRecordBudgetByTheNumberOfFiles() throws Exception {
    try (var cursor = new SessionCursor(List.of(child("a"), child("b")), ReadQuery.all("x"))) {
      var failure = assertThrows(ReadException.class, () -> {
        while (!cursor.next().complete()) { /* bounded work can yield before every head is ready */ }
      });
      assertEquals(ReadProblem.Code.RESOURCE_LIMIT, failure.code());
      assertTrue(failure.getMessage().contains("lookahead"));
    }
  }
}
