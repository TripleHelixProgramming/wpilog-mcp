/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The scan's offset list (review 6, section 5.1). */
class RecordOffsetsTest {

  @Test
  @DisplayName("grows past its initial capacity and keeps insertion order")
  void growsAndKeepsOrder() {
    var list = new RecordOffsets();
    assertTrue(list.isEmpty());
    for (int i = 0; i < 1000; i++) list.add(i * 3);
    assertEquals(1000, list.size());
    assertFalse(list.isEmpty());
    assertEquals(0, list.get(0));
    assertEquals(2997, list.get(999));
    for (int i = 0; i < 1000; i++) assertEquals(i * 3L, list.get(i));
    assertEquals(4000, list.compact().storageBytes(), "the array is exactly the size, not the capacity");
  }

  @Test
  @DisplayName("removeLast drops the newest offset; out-of-range reads and an empty removal throw")
  void removeLastAndBounds() {
    var list = new RecordOffsets();
    assertThrows(IllegalStateException.class, list::removeLast);
    list.add(7);
    list.add(9);
    list.removeLast();
    assertEquals(1, list.size());
    assertEquals(7, list.get(0));
    assertEquals(4, list.compact().storageBytes());
    assertThrows(IndexOutOfBoundsException.class, () -> list.get(1));
    assertThrows(IndexOutOfBoundsException.class, () -> list.get(-1));
  }
}
