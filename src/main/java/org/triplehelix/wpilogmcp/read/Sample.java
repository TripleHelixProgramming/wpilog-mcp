/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

/** Timestamp and identity retain integer precision. Ordinal is the entry's original file-order
 * index (including malformed records); offsets are long across mapping windows. Values use the
 * shared decoder's primitive wrappers/arrays, maps and enum values; opaque types retain bytes.
 * Arrays belong to this sample, never a shared decoded cache. The encoded payload size permits
 * transport rebatching without guessing byte accounting from decoded values. */
public record Sample(long timestampUs, long recordOffset, int ordinal, Object value, long payloadBytes, Source source) {
  public record Source(String path, long timestampUs) {}
  public Sample(long timestampUs, long recordOffset, int ordinal, Object value, long payloadBytes) {
    this(timestampUs, recordOffset, ordinal, value, payloadBytes, null);
  }
  /** Compatibility constructor for caller-built values without an encoded payload coordinate. */
  public Sample(long timestampUs, long recordOffset, int ordinal, Object value) {
    this(timestampUs, recordOffset, ordinal, value, -1);
  }
}
