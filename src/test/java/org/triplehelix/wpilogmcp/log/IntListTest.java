/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The scan's offset list (review 6, section 5.1). */
class IntListTest {

  @Test
  @DisplayName("grows past its initial capacity and keeps insertion order")
  void growsAndKeepsOrder() {
    var list = new IntList();
    assertTrue(list.isEmpty());
    for (int i = 0; i < 1000; i++) list.add(i * 3);
    assertEquals(1000, list.size());
    assertFalse(list.isEmpty());
    assertEquals(0, list.get(0));
    assertEquals(2997, list.get(999));
    assertArrayEquals(IntStream.range(0, 1000).map(i -> i * 3).toArray(), list.toArray());
    assertEquals(1000, list.toArray().length, "the array is exactly the size, not the capacity");
  }

  @Test
  @DisplayName("removeLast drops the newest offset; out-of-range reads and an empty removal throw")
  void removeLastAndBounds() {
    var list = new IntList();
    assertThrows(IllegalStateException.class, list::removeLast);
    list.add(7);
    list.add(9);
    list.removeLast();
    assertEquals(1, list.size());
    assertEquals(7, list.get(0));
    assertArrayEquals(new int[] {7}, list.toArray());
    assertThrows(IndexOutOfBoundsException.class, () -> list.get(1));
    assertThrows(IndexOutOfBoundsException.class, () -> list.get(-1));
  }
}
