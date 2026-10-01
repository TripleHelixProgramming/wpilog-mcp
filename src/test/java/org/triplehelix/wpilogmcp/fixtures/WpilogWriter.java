/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.fixtures;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A minimal pure-Java writer for the WPILOG binary format (version 1.0), for test fixtures.
 *
 * <p>Follows WPILib's {@code datalog.adoc} specification: a header ({@code WPILOG}, version
 * {@code 0x0100}, extra header), then records whose entry id, payload size, and timestamp fields
 * use the minimal little-endian width, announced in a one-byte length bitfield. Entry 0 carries
 * control records (Start, Finish, Set Metadata). Files are validated against WPILib's own
 * {@code DataLogReader} in {@code FixtureLogsTest}.
 *
 * <p>WPILib's native {@code DataLogWriter} is not used: fixture generation must be deterministic
 * and must not depend on JNI (the native writer can block indefinitely inside {@code appendRaw}).
 */
public final class WpilogWriter implements AutoCloseable {

  private static final byte CONTROL_START = 0;
  private static final byte CONTROL_FINISH = 1;
  private static final byte CONTROL_SET_METADATA = 2;

  private final OutputStream out;
  private int nextEntry = 1;

  public WpilogWriter(Path path, String extraHeader) throws IOException {
    this.out = new BufferedOutputStream(Files.newOutputStream(path), 1 << 16);
    byte[] extra = extraHeader.getBytes(StandardCharsets.UTF_8);
    out.write(new byte[] {'W', 'P', 'I', 'L', 'O', 'G', 0x00, 0x01});
    out.write(le4(extra.length));
    out.write(extra);
  }

  /** Starts an entry and returns its id. */
  public int start(String name, String type, String metadata, long timestamp) throws IOException {
    int id = nextEntry++;
    byte[] n = name.getBytes(StandardCharsets.UTF_8);
    byte[] t = type.getBytes(StandardCharsets.UTF_8);
    byte[] m = metadata.getBytes(StandardCharsets.UTF_8);
    var payload = ByteBuffer.allocate(1 + 4 + 4 + n.length + 4 + t.length + 4 + m.length)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(CONTROL_START).putInt(id)
        .putInt(n.length).put(n)
        .putInt(t.length).put(t)
        .putInt(m.length).put(m);
    record(0, timestamp, payload.array());
    return id;
  }

  public void finish(int entry, long timestamp) throws IOException {
    var payload = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        .put(CONTROL_FINISH).putInt(entry);
    record(0, timestamp, payload.array());
  }

  public void setMetadata(int entry, String metadata, long timestamp) throws IOException {
    byte[] m = metadata.getBytes(StandardCharsets.UTF_8);
    var payload = ByteBuffer.allocate(1 + 4 + 4 + m.length).order(ByteOrder.LITTLE_ENDIAN)
        .put(CONTROL_SET_METADATA).putInt(entry).putInt(m.length).put(m);
    record(0, timestamp, payload.array());
  }

  /** Writes one data record for {@code entry}. */
  public void append(int entry, long timestamp, byte[] payload) throws IOException {
    if (entry <= 0) throw new IllegalArgumentException("entry must be positive: " + entry);
    record(entry, timestamp, payload);
  }

  private void record(int entry, long timestamp, byte[] payload) throws IOException {
    int idLen = width(entry & 0xFFFFFFFFL, 4);
    int sizeLen = width(payload.length & 0xFFFFFFFFL, 4);
    int tsLen = width(timestamp, 8);
    out.write((idLen - 1) | ((sizeLen - 1) << 2) | ((tsLen - 1) << 4));
    writeLe(entry & 0xFFFFFFFFL, idLen);
    writeLe(payload.length & 0xFFFFFFFFL, sizeLen);
    writeLe(timestamp, tsLen);
    out.write(payload);
  }

  /** Minimal number of bytes (at least 1) to hold {@code value} as unsigned little-endian. */
  static int width(long value, int max) {
    int n = 1;
    while (n < max && (value >>> (8 * n)) != 0) n++;
    return n;
  }

  private void writeLe(long value, int bytes) throws IOException {
    for (int i = 0; i < bytes; i++) out.write((int) (value >>> (8 * i)) & 0xFF);
  }

  private static byte[] le4(int value) {
    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
  }

  // ==================== payload encoders ====================

  static ByteBuffer le(int size) {
    return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
  }

  public static byte[] encodeBoolean(boolean v) {
    return new byte[] {(byte) (v ? 1 : 0)};
  }

  public static byte[] encodeInt64(long v) {
    return le(8).putLong(v).array();
  }

  public static byte[] encodeFloat(float v) {
    return le(4).putFloat(v).array();
  }

  public static byte[] encodeDouble(double v) {
    return le(8).putDouble(v).array();
  }

  public static byte[] encodeString(String v) {
    return v.getBytes(StandardCharsets.UTF_8);
  }

  public static byte[] encodeBooleanArray(boolean[] v) {
    var b = new byte[v.length];
    for (int i = 0; i < v.length; i++) b[i] = (byte) (v[i] ? 1 : 0);
    return b;
  }

  public static byte[] encodeInt64Array(long[] v) {
    var b = le(8 * v.length);
    for (long x : v) b.putLong(x);
    return b.array();
  }

  public static byte[] encodeFloatArray(float[] v) {
    var b = le(4 * v.length);
    for (float x : v) b.putFloat(x);
    return b.array();
  }

  public static byte[] encodeDoubleArray(double[] v) {
    var b = le(8 * v.length);
    for (double x : v) b.putDouble(x);
    return b.array();
  }

  /** string[]: a 4-byte count, then each string as a 4-byte length and UTF-8 bytes. */
  public static byte[] encodeStringArray(String[] v) {
    var parts = new byte[v.length][];
    int size = 4;
    for (int i = 0; i < v.length; i++) {
      parts[i] = v[i].getBytes(StandardCharsets.UTF_8);
      size += 4 + parts[i].length;
    }
    var b = le(size).putInt(v.length);
    for (var p : parts) b.putInt(p.length).put(p);
    return b.array();
  }

  @Override
  public void close() throws IOException {
    out.close();
  }
}
