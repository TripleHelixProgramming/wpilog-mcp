/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.data;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.triplehelix.wpilogmcp.log.struct.EnumValue;

/**
 * Builds one column of a record batch in Arrow's IPC layout, on the heap: the validity bitmap,
 * the offsets, and the data of the array, each a growing byte array, with the children of a
 * struct or list built the same way beneath it. {@link ArrowStreamWriter} takes the nodes and
 * buffers in the order the format wants them (the array's own, then each child's, depth first)
 * and writes them as a batch's body.
 *
 * <p>This is the whole of the layout work: a few hundred lines against the specification, so the
 * server never needs arrow-vector's off-heap allocator (see ARCHITECTURE.md). A value is appended
 * as the log decodes it (a Number, a Boolean, a String, a byte[], a List, a Map); null is a null.
 * A validity bitmap is written only when a null was appended, which the specification allows and
 * which keeps an all-valid column to its data alone.
 */
public abstract class ColumnBuilder {

  /** A buffer's bytes and how many of them are in use. */
  public static final class Bytes {
    private byte[] data = new byte[256];
    private int length;

    int length() {
      return length;
    }

    byte[] array() {
      return data;
    }

    private void ensure(int more) {
      if (length + more > data.length) {
        data = Arrays.copyOf(data, Math.max(data.length * 2, length + more));
      }
    }

    void putByte(int b) {
      ensure(1);
      data[length++] = (byte) b;
    }

    void putInt(int v) {
      ensure(4);
      data[length++] = (byte) v;
      data[length++] = (byte) (v >>> 8);
      data[length++] = (byte) (v >>> 16);
      data[length++] = (byte) (v >>> 24);
    }

    void putLong(long v) {
      ensure(8);
      for (int i = 0; i < 8; i++) data[length++] = (byte) (v >>> (8 * i));
    }

    void putDouble(double v) {
      putLong(Double.doubleToRawLongBits(v));
    }

    void putBytes(byte[] bytes) {
      ensure(bytes.length);
      System.arraycopy(bytes, 0, data, length, bytes.length);
      length += bytes.length;
    }

    /** Sets bit {@code index}, growing the buffer as a bitmap of that many bits needs. */
    void setBit(int index) {
      int byteIndex = index >>> 3;
      ensureBits(index + 1);
      data[byteIndex] |= (byte) (1 << (index & 7));
    }

    /** Makes the buffer at least {@code bits} bits long, the new bits clear. */
    void ensureBits(int bits) {
      int bytes = (bits + 7) >>> 3;
      if (bytes > length) {
        ensure(bytes - length);
        Arrays.fill(data, length, bytes, (byte) 0);
        length = bytes;
      }
    }

    void clear() {
      length = 0;
    }
  }

  /** A node of the batch's header: an array's length and null count. */
  public record Node(long length, long nullCount) {}

  protected int length;
  protected int nullCount;
  /** The validity bitmap; written only when a null was appended. */
  protected final Bytes validity = new Bytes();

  /** A builder for a type. */
  public static ColumnBuilder of(ArrowType type) {
    if (type instanceof ArrowType.Float64) return new Float64Builder();
    if (type instanceof ArrowType.Int64) return new Int64Builder();
    if (type instanceof ArrowType.TimestampMicros) return new Int64Builder();
    if (type instanceof ArrowType.Bool) return new BoolBuilder();
    if (type instanceof ArrowType.Utf8) return new BinaryBuilder(true);
    if (type instanceof ArrowType.Binary) return new BinaryBuilder(false);
    if (type instanceof ArrowType.Struct s) return new StructBuilder(s);
    if (type instanceof ArrowType.ListOf l) return new ListBuilder(l);
    throw new IllegalArgumentException("no builder for " + type);
  }

  /** How many values the column holds. */
  public int length() {
    return length;
  }

  /** Appends a value; null appends a null. */
  public final void append(Object value) {
    if (value == null) {
      appendNull();
    } else {
      validity.ensureBits(length + 1);
      validity.setBit(length);
      appendValue(value);
    }
    length++;
  }

  /** Appends a null: the validity bit stays clear and the data gets a placeholder. */
  protected void appendNull() {
    validity.ensureBits(length + 1);
    nullCount++;
    appendPlaceholder();
  }

  /** Appends the data of a non-null value. */
  protected abstract void appendValue(Object value);

  /** Appends the data a null occupies (a zero of the type's width, an empty list). */
  protected abstract void appendPlaceholder();

  /**
   * Collects this array's node and buffers, then its children's, depth first, as the format
   * orders them.
   */
  public void collect(List<Node> nodes, List<Bytes> buffers) {
    nodes.add(new Node(length, nullCount));
    buffers.add(nullCount > 0 ? validity : EMPTY);
    collectOwn(buffers);
    collectChildren(nodes, buffers);
  }

  /** The buffers after the validity bitmap: offsets and data, as the type has them. */
  protected abstract void collectOwn(List<Bytes> buffers);

  protected void collectChildren(List<Node> nodes, List<Bytes> buffers) {}

  /** Empties the builder for the next batch. */
  public void reset() {
    length = 0;
    nullCount = 0;
    validity.clear();
    resetOwn();
  }

  protected abstract void resetOwn();

  private static final Bytes EMPTY = new Bytes();

  // ---- the types ----

  static final class Float64Builder extends ColumnBuilder {
    private final Bytes data = new Bytes();

    @Override
    protected void appendValue(Object value) {
      data.putDouble(((Number) value).doubleValue());
    }

    @Override
    protected void appendPlaceholder() {
      data.putDouble(0);
    }

    @Override
    protected void collectOwn(List<Bytes> buffers) {
      buffers.add(data);
    }

    @Override
    protected void resetOwn() {
      data.clear();
    }
  }

  static final class Int64Builder extends ColumnBuilder {
    private final Bytes data = new Bytes();

    @Override
    protected void appendValue(Object value) {
      data.putLong(value instanceof EnumNumber e ? e.value() : ((Number) value).longValue());
    }

    @Override
    protected void appendPlaceholder() {
      data.putLong(0);
    }

    @Override
    protected void collectOwn(List<Bytes> buffers) {
      buffers.add(data);
    }

    @Override
    protected void resetOwn() {
      data.clear();
    }
  }

  /** A value that is an enum's number: what {@link Int64Builder} reads it as. */
  public record EnumNumber(long value) {}

  static final class BoolBuilder extends ColumnBuilder {
    private final Bytes data = new Bytes();

    @Override
    protected void appendValue(Object value) {
      data.ensureBits(length + 1);
      boolean set = value instanceof Boolean b ? b : ((Number) value).doubleValue() != 0;
      if (set) data.setBit(length);
    }

    @Override
    protected void appendPlaceholder() {
      data.ensureBits(length + 1);
    }

    @Override
    protected void collectOwn(List<Bytes> buffers) {
      buffers.add(data);
    }

    @Override
    protected void resetOwn() {
      data.clear();
    }
  }

  /** Utf8 and Binary share a layout: 32-bit offsets and the bytes. */
  static final class BinaryBuilder extends ColumnBuilder {
    private final boolean text;
    private final Bytes offsets = new Bytes();
    private final Bytes data = new Bytes();

    BinaryBuilder(boolean text) {
      this.text = text;
      offsets.putInt(0);
    }

    @Override
    protected void appendValue(Object value) {
      byte[] bytes = value instanceof byte[] b ? b
          : String.valueOf(value).getBytes(StandardCharsets.UTF_8);
      data.putBytes(bytes);
      offsets.putInt(data.length());
    }

    @Override
    protected void appendPlaceholder() {
      offsets.putInt(data.length());
    }

    @Override
    protected void collectOwn(List<Bytes> buffers) {
      buffers.add(offsets);
      buffers.add(data);
    }

    @Override
    protected void resetOwn() {
      offsets.clear();
      offsets.putInt(0);
      data.clear();
    }

    boolean isText() {
      return text;
    }
  }

  /** A struct: a validity bitmap and a child per field, each as long as the struct. */
  static final class StructBuilder extends ColumnBuilder {
    private final List<String> names = new ArrayList<>();
    private final List<ColumnBuilder> children = new ArrayList<>();

    StructBuilder(ArrowType.Struct type) {
      for (var field : type.fields()) {
        names.add(field.name());
        children.add(ColumnBuilder.of(field.type()));
      }
    }

    @Override
    protected void appendValue(Object value) {
      for (int i = 0; i < names.size(); i++) {
        // A number absent from the enum schema is still valid; only its label is null.
        Object child = value instanceof EnumValue e
            ? (names.get(i).equals("value") ? e.value() : e.label())
            : ((Map<?, ?>) value).get(names.get(i));
        children.get(i).append(child);
      }
    }

    @Override
    protected void appendPlaceholder() {
      for (var child : children) child.append(null);
    }

    @Override
    protected void collectOwn(List<Bytes> buffers) {}

    @Override
    protected void collectChildren(List<Node> nodes, List<Bytes> buffers) {
      for (var child : children) child.collect(nodes, buffers);
    }

    @Override
    protected void resetOwn() {
      for (var child : children) child.reset();
    }
  }

  /** A list: a validity bitmap, 32-bit offsets into one child holding every element. */
  static final class ListBuilder extends ColumnBuilder {
    private final Bytes offsets = new Bytes();
    private final ColumnBuilder child;

    ListBuilder(ArrowType.ListOf type) {
      child = ColumnBuilder.of(type.child().type());
      offsets.putInt(0);
    }

    @Override
    protected void appendValue(Object value) {
      if (value instanceof List<?> list) {
        for (var element : list) child.append(element);
      } else if (value instanceof double[] a) {
        for (double d : a) child.append(d);
      } else if (value instanceof float[] a) {
        for (float f : a) child.append(f);
      } else if (value instanceof long[] a) {
        for (long l : a) child.append(l);
      } else if (value instanceof int[] a) {
        for (int i : a) child.append(i);
      } else if (value instanceof boolean[] a) {
        for (boolean b : a) child.append(b);
      } else if (value instanceof String[] a) {
        for (String s : a) child.append(s);
      } else if (value instanceof Object[] a) {
        for (Object o : a) child.append(o);
      } else {
        throw new IllegalArgumentException("not a list: " + value.getClass().getName());
      }
      offsets.putInt(child.length());
    }

    @Override
    protected void appendPlaceholder() {
      offsets.putInt(child.length());
    }

    @Override
    protected void collectOwn(List<Bytes> buffers) {
      buffers.add(offsets);
    }

    @Override
    protected void collectChildren(List<Node> nodes, List<Bytes> buffers) {
      child.collect(nodes, buffers);
    }

    @Override
    protected void resetOwn() {
      offsets.clear();
      offsets.putInt(0);
      child.reset();
    }
  }
}
