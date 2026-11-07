/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The NT4 subset of MessagePack, written from msgpack/msgpack's spec.md. No extension formats:
 * NT4 timestamps are integer microseconds, not MessagePack timestamp extensions. Integers decode
 * as Long, except unsigned 64-bit values above Long.MAX_VALUE, which retain all bits in BigInteger.
 * Lengths are checked against remaining input before allocation, and nesting is bounded.
 */
public final class MessagePack {
  public static final int MAX_BYTES = 16 * 1024 * 1024;
  private static final int MAX_DEPTH = 64;
  private static final int MAX_ITEMS = 1_000_000;

  private MessagePack() {}

  public static byte[] encode(Object value) {
    var out = new ByteArrayOutputStream();
    write(out, value, 0);
    return out.toByteArray();
  }

  /** A WebSocket message may contain several consecutive MessagePack objects. */
  public static List<Object> decodeStream(byte[] bytes) {
    if (bytes.length > MAX_BYTES) throw bad("message too large");
    var reader = new Reader(ByteBuffer.wrap(bytes));
    var values = new ArrayList<Object>();
    while (reader.in.hasRemaining()) values.add(reader.read(0));
    return Collections.unmodifiableList(values);
  }

  public static Object decode(byte[] bytes) {
    var values = decodeStream(bytes);
    if (values.size() != 1) throw bad("expected one object");
    return values.get(0);
  }

  private static void write(ByteArrayOutputStream out, Object value, int depth) {
    if (depth > MAX_DEPTH) throw bad("nesting too deep");
    if (value == null) out.write(0xc0);
    else if (value instanceof Boolean b) out.write(b ? 0xc3 : 0xc2);
    else if (value instanceof Float f) number(out, 0xca, Float.floatToRawIntBits(f), 4);
    else if (value instanceof Double d) number(out, 0xcb, Double.doubleToRawLongBits(d), 8);
    else if (value instanceof BigInteger b) {
      if (b.bitLength() > 64 || b.signum() < 0 && b.bitLength() > 63) {
        throw bad("integer outside MessagePack range");
      }
      if (b.bitLength() == 64 && b.signum() > 0) number(out, 0xcf, b.longValue(), 8);
      else integer(out, b.longValue());
    } else if (value instanceof Byte || value instanceof Short
        || value instanceof Integer || value instanceof Long) integer(out, ((Number) value).longValue());
    else if (value instanceof String s) {
      byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
      length(out, bytes.length, 31, 0xa0, 0xd9, 0xda, 0xdb);
      out.writeBytes(bytes);
    } else if (value instanceof byte[] bytes) {
      length(out, bytes.length, -1, 0, 0xc4, 0xc5, 0xc6);
      out.writeBytes(bytes);
    } else if (value instanceof List<?> list) {
      length(out, list.size(), 15, 0x90, -1, 0xdc, 0xdd);
      for (var item : list) write(out, item, depth + 1);
    } else if (value instanceof Map<?, ?> map) {
      length(out, map.size(), 15, 0x80, -1, 0xde, 0xdf);
      for (var entry : map.entrySet()) {
        write(out, entry.getKey(), depth + 1);
        write(out, entry.getValue(), depth + 1);
      }
    } else throw bad("unsupported value class: " + value.getClass().getName());
    if (out.size() > MAX_BYTES) throw bad("message too large");
  }

  private static void integer(ByteArrayOutputStream out, long n) {
    if (n >= -32 && n <= 127) out.write((int) n);
    else if (n >= 0) {
      if (n <= 255) number(out, 0xcc, n, 1);
      else if (n <= 65535) number(out, 0xcd, n, 2);
      else if (n <= 0xffffffffL) number(out, 0xce, n, 4);
      else number(out, 0xcf, n, 8);
    } else if (n >= -128) number(out, 0xd0, n, 1);
    else if (n >= -32768) number(out, 0xd1, n, 2);
    else if (n >= Integer.MIN_VALUE) number(out, 0xd2, n, 4);
    else number(out, 0xd3, n, 8);
  }

  private static void length(ByteArrayOutputStream out, int n, int maxFix, int fix,
      int code8, int code16, int code32) {
    if (n > MAX_BYTES) throw bad("length too large");
    if (n <= maxFix) out.write(fix | n);
    else if (n <= 255 && code8 >= 0) number(out, code8, n, 1);
    else if (n <= 65535) number(out, code16, n, 2);
    else number(out, code32, n, 4);
  }

  private static void number(ByteArrayOutputStream out, int tag, long bits, int width) {
    out.write(tag);
    for (int shift = (width - 1) * 8; shift >= 0; shift -= 8) out.write((int) (bits >>> shift));
  }

  private static IllegalArgumentException bad(String reason) {
    return new IllegalArgumentException("MessagePack: " + reason);
  }

  private static final class Reader {
    private final ByteBuffer in;
    private int items;

    Reader(ByteBuffer in) { this.in = in; }

    private long unsigned(int width) {
      require(width);
      long n = 0;
      for (int i = 0; i < width; i++) n = (n << 8) | (in.get() & 255L);
      return n;
    }

    private void require(long n) {
      if (n < 0 || n > in.remaining()) throw bad("truncated or excessive length");
    }

    Object read(int depth) {
      if (depth > MAX_DEPTH || ++items > MAX_ITEMS) throw bad("object too complex");
      int tag = (int) unsigned(1);
      if (tag <= 0x7f) return (long) tag;
      if (tag >= 0xe0) return (long) (byte) tag;
      if ((tag & 0xe0) == 0xa0) return string(tag & 31);
      if ((tag & 0xf0) == 0x90) return array(tag & 15, depth);
      if ((tag & 0xf0) == 0x80) return map(tag & 15, depth);
      return switch (tag) {
        case 0xc0 -> null;
        case 0xc2 -> false;
        case 0xc3 -> true;
        case 0xcc -> unsigned(1);
        case 0xcd -> unsigned(2);
        case 0xce -> unsigned(4);
        case 0xcf -> {
          long bits = unsigned(8);
          yield bits >= 0 ? bits : BigInteger.valueOf(bits & Long.MAX_VALUE).setBit(63);
        }
        case 0xd0 -> (long) (byte) unsigned(1);
        case 0xd1 -> (long) (short) unsigned(2);
        case 0xd2 -> (long) (int) unsigned(4);
        case 0xd3 -> unsigned(8);
        case 0xca -> Float.intBitsToFloat((int) unsigned(4));
        case 0xcb -> Double.longBitsToDouble(unsigned(8));
        case 0xc4 -> binary(unsigned(1));
        case 0xc5 -> binary(unsigned(2));
        case 0xc6 -> binary(unsigned(4));
        case 0xd9 -> string(unsigned(1));
        case 0xda -> string(unsigned(2));
        case 0xdb -> string(unsigned(4));
        case 0xdc -> array(unsigned(2), depth);
        case 0xdd -> array(unsigned(4), depth);
        case 0xde -> map(unsigned(2), depth);
        case 0xdf -> map(unsigned(4), depth);
        default -> throw bad("unsupported tag: " + Integer.toHexString(tag));
      };
    }

    private byte[] binary(long n) {
      require(n);
      var bytes = new byte[(int) n];
      in.get(bytes);
      return bytes;
    }

    private String string(long n) {
      var bytes = binary(n);
      try {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString();
      } catch (CharacterCodingException e) {
        throw bad("invalid UTF-8");
      }
    }

    private List<Object> array(long n, int depth) {
      require(n); // Every element consumes at least one byte. Do not preallocate from wire lengths.
      var result = new ArrayList<Object>();
      for (long i = 0; i < n; i++) result.add(read(depth + 1));
      return Collections.unmodifiableList(result);
    }

    private Map<Object, Object> map(long n, int depth) {
      require(n * 2);
      var result = new LinkedHashMap<Object, Object>();
      for (long i = 0; i < n; i++) {
        var key = read(depth + 1);
        if (result.containsKey(key)) throw bad("duplicate map key");
        result.put(key, read(depth + 1));
      }
      return Collections.unmodifiableMap(result);
    }
  }
}
