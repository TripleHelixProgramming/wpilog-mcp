/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import java.util.Arrays;

/** Long addresses without doubling ordinary logs' index memory. Upgrade only the entry that
 * first reaches a wide address, then compact once after scanning. No boxed offset per record. */
public final class RecordOffsets {
  private int[] narrow = new int[16];
  private long[] wide;
  private int size;
  private int firstUnordered = Integer.MAX_VALUE;
  private long lastTimestampUs;
  private boolean lastTimestampKnown;

  /** The scan already has the timestamp; remember ordering without another array per entry. */
  public void add(long offset, long timestampUs) {
    append(offset);
    if (size > 1 && (!lastTimestampKnown || timestampUs < lastTimestampUs)) {
      firstUnordered = Math.min(firstUnordered, size - 1);
    }
    lastTimestampUs = timestampUs;
    lastTimestampKnown = true;
  }

  /** Nondecreasing timestamps, including duplicates. Unknown legacy timestamps never prove order. */
  public boolean monotonic() { return firstUnordered >= size; }

  /** Offset-only callers cannot establish timestamp ordering across the appended record. */
  public void add(long offset) {
    append(offset);
    if (size > 1) firstUnordered = Math.min(firstUnordered, size - 1);
    lastTimestampKnown = false;
  }

  private void append(long offset) {
    if (offset < 0) throw new IllegalArgumentException("Negative record offset");
    if (wide == null && offset > Integer.MAX_VALUE) {
      wide = new long[narrow.length]; for (int i = 0; i < size; i++) wide[i] = narrow[i]; narrow = null;
    }
    if (wide == null) {
      if (size == narrow.length) narrow = Arrays.copyOf(narrow, Math.max(16, size * 2));
      narrow[size++] = Math.toIntExact(offset);
    } else {
      if (size == wide.length) wide = Arrays.copyOf(wide, Math.max(16, size * 2));
      wide[size++] = offset;
    }
  }
  public long get(int index) {
    if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index + " of " + size);
    return wide == null ? narrow[index] : wide[index];
  }
  public int size() { return size; }
  public boolean isEmpty() { return size == 0; }
  public void removeLast() {
    if (size == 0) throw new IllegalStateException("empty");
    size--;
    if (firstUnordered >= size) firstUnordered = Integer.MAX_VALUE;
    // Without retaining every timestamp, another append needs the new last timestamp supplied.
    lastTimestampKnown = false;
  }
  /** Damage rollback reads the preceding record's header before removing the last offset. */
  public void removeLast(long precedingTimestampUs) {
    removeLast();
    if (size > 0) { lastTimestampUs = precedingTimestampUs; lastTimestampKnown = true; }
  }
  public RecordOffsets compact() {
    if (wide == null) narrow = Arrays.copyOf(narrow, size); else wide = Arrays.copyOf(wide, size);
    return this;
  }
  /** A resumed index owns its arrays; an in-flight reader keeps the old prefix unchanged. */
  public RecordOffsets copy() {
    var copy = new RecordOffsets(); copy.size = size;
    copy.firstUnordered = firstUnordered;
    copy.lastTimestampUs = lastTimestampUs;
    copy.lastTimestampKnown = lastTimestampKnown;
    if (wide == null) copy.narrow = Arrays.copyOf(narrow, size);
    else { copy.narrow = null; copy.wide = Arrays.copyOf(wide, size); }
    return copy;
  }
  public long storageBytes() { return wide == null ? narrow.length * 4L : wide.length * 8L; }
}
