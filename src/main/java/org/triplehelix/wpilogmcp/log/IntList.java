/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import java.util.Arrays;

/**
 * A growable list of ints: the record offsets of one entry during a scan, at 4 bytes each
 * (a boxed {@code List<Integer>} held about five times that per record).
 *
 * @since 0.9.0
 */
public final class IntList {
  private int[] data = new int[16];
  private int size;

  public void add(int value) {
    if (size == data.length) data = Arrays.copyOf(data, data.length * 2);
    data[size++] = value;
  }

  public int get(int index) {
    if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index + " of " + size);
    return data[index];
  }

  public int size() {
    return size;
  }

  public boolean isEmpty() {
    return size == 0;
  }

  public void removeLast() {
    if (size == 0) throw new IllegalStateException("empty");
    size--;
  }

  /** The values as an array of exactly {@link #size()} elements. */
  public int[] toArray() {
    return Arrays.copyOf(data, size);
  }
}
