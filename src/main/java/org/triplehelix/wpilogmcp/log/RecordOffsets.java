/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import java.util.Arrays;
import java.io.IOException;

/** Long addresses without doubling ordinary logs' index memory. Upgrade only the entry that
 * first reaches a wide address, then compact once after scanning. No boxed offset per record. */
public final class RecordOffsets {
  private int[] narrow = new int[16];
  private long[] wide;
  private int size;
  private int firstUnordered = Integer.MAX_VALUE;
  private long lastTimestampUs;
  private boolean lastTimestampKnown;

  /** Persist the exact ordering proof; reconstructing positions alone cannot prove time order. */
  public record OrderState(int firstUnordered, long lastTimestampUs, boolean lastTimestampKnown) {}

  public OrderState orderState() {
    return new OrderState(firstUnordered, lastTimestampUs, lastTimestampKnown);
  }

  public boolean wide() { return wide != null; }

  /** A bounded decoder supplies scalar positions without an intermediate boxed/long array. */
  @FunctionalInterface public interface OffsetReader { long read() throws IOException; }

  /**
   * Reconstruct one already-budgeted persisted array without growing it or guessing its
   * timestamp order. The enclosing decoder owns the aggregate allocation allowance.
   */
  public static RecordOffsets readPersisted(int count, boolean wide, OrderState state,
      long sourceSize, OffsetReader input) throws IOException {
    validateOrderState(count, state);
    if (sourceSize < 12) throw new IOException("Invalid persisted source size");
    var result = new RecordOffsets();
    result.narrow = wide ? null : new int[count];
    result.wide = wide ? new long[count] : null;
    long preceding = -1;
    for (int i = 0; i < count; i++) {
      long position = input.read();
      if (position < 12 || position > sourceSize - 4 || position <= preceding
          || !wide && position > Integer.MAX_VALUE)
        throw new IOException("Invalid persisted record position");
      if (wide) result.wide[i] = position;
      else result.narrow[i] = (int) position;
      preceding = position;
    }
    result.size = count;
    result.firstUnordered = state.firstUnordered();
    result.lastTimestampUs = state.lastTimestampUs();
    result.lastTimestampKnown = state.lastTimestampKnown();
    return result;
  }

  /** The serializer also validates this when inspecting without allocating an offset array. */
  public static void validateOrderState(int count, OrderState state) throws IOException {
    if (count < 0 || state == null
        || state.firstUnordered() != Integer.MAX_VALUE
            && (state.firstUnordered() < 1 || state.firstUnordered() >= count)
        || count == 0 && state.lastTimestampKnown())
      throw new IOException("Invalid persisted timestamp ordering state");
  }

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
    if (wide == null) {
      if (narrow.length != size) narrow = Arrays.copyOf(narrow, size);
    } else if (wide.length != size) wide = Arrays.copyOf(wide, size);
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
