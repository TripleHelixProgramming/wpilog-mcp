/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.data;

import java.util.List;

/**
 * The Arrow types the data endpoint writes: the few a log's values need. Each knows its IPC
 * layout (which buffers an array of it has) through {@link ColumnBuilder}; the Flatbuffers
 * description of it is written by {@link ArrowStreamWriter}.
 */
public sealed interface ArrowType {

  /** A 64-bit float: a log's double and float values, and a bucket's statistics. */
  record Float64() implements ArrowType {}

  /** A signed 64-bit integer: a log's int64 values, a bucket's count, an enum's number. */
  record Int64() implements ArrowType {}

  /** A native struct's uint64 field; its full unsigned value never passes through a double. */
  record UInt64() implements ArrowType {}

  /** A boolean, bit-packed as Arrow packs it. */
  record Bool() implements ArrowType {}

  /** Text in UTF-8, with 32-bit offsets. */
  record Utf8() implements ArrowType {}

  /** Bytes as logged, with 32-bit offsets: a raw entry. */
  record Binary() implements ArrowType {}

  /** A missing zone preserves the log's own clock; UTC identifies a measured epoch column. */
  record TimestampMicros(String timezone) implements ArrowType {
    public TimestampMicros() { this(null); }
  }

  /** A struct with named children: a decoded struct value. */
  record Struct(List<Field> fields) implements ArrowType {
    public Struct {
      fields = List.copyOf(fields);
    }
  }

  /** A variable-length list of one child type: an array entry. */
  record ListOf(Field child) implements ArrowType {}

  /** A field: a name, a type, and whether it may hold nulls. */
  record Field(String name, ArrowType type, boolean nullable) {}
}
