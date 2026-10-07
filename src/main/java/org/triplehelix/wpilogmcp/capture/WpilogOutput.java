/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Single-writer WPILOG 1.0 output, from WPILib's datalog.adoc. Unlike DataLog's JNI writer,
 * this can ship in the Java-only install. A complete write precedes publication to the live index;
 * offsets describe the durable format, not a second representation invented for capture.
 */
public class WpilogOutput implements AutoCloseable {
  public record Written(long offset, int size, long payloadOffset, int payloadSize) {}
  private final FileChannel channel;
  private final CaptureLease lease;
  private int nextEntry;
  private boolean broken;

  public WpilogOutput(Path path) throws IOException { this(path, 1, false); }

  /** Only the recorder which closed this session can resume it, with its next unused entry id. */
  public WpilogOutput(Path path, int nextEntry, boolean resume) throws IOException {
    lease = CaptureLease.tryAcquire(path).orElseThrow(() -> resume
        ? new IOException("Capture is owned by another writer or recovery: " + path)
        : new java.nio.file.FileAlreadyExistsException(path.toString()));
    FileChannel opened = null;
    this.nextEntry = nextEntry;
    try {
      opened = resume ? FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
          : FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
      channel = opened;
      if (!resume) write(ByteBuffer.wrap(new byte[] {'W', 'P', 'I', 'L', 'O', 'G', 0, 1, 0, 0, 0, 0}));
    } catch (IOException | RuntimeException e) {
      try { if (opened != null) opened.close(); } catch (IOException close) { e.addSuppressed(close); }
      try { lease.close(); } catch (IOException close) { e.addSuppressed(close); }
      throw e;
    }
  }

  public int nextEntry() { return nextEntry; }
  public long size() throws IOException { return channel.position(); }
  public static int recordSize(int id, long timestampUs, int payloadSize) {
    return 1 + width(Integer.toUnsignedLong(id), 4) + width(payloadSize, 4) + width(timestampUs, 8) + payloadSize;
  }
  public static int startSize(String name, String type, String metadata, long timestampUs) {
    int payload = 17 + name.getBytes(StandardCharsets.UTF_8).length
        + type.getBytes(StandardCharsets.UTF_8).length + metadata.getBytes(StandardCharsets.UTF_8).length;
    return recordSize(0, timestampUs, payload);
  }
  public int start(String name, String type, String metadata, long timestampUs) throws IOException {
    int id = nextEntry++;
    var out = new ByteArrayOutputStream();
    out.write(0); little(out, id, 4);
    string(out, name); string(out, type); string(out, metadata);
    record(0, timestampUs, out.toByteArray());
    return id;
  }

  public void finish(int id, long timestampUs) throws IOException {
    var out = new ByteArrayOutputStream(); out.write(1); little(out, id, 4);
    record(0, timestampUs, out.toByteArray());
  }

  public static int metadataSize(String metadata, long timestampUs) {
    return recordSize(0, timestampUs, 9 + metadata.getBytes(StandardCharsets.UTF_8).length);
  }

  /** Set Metadata replaces the whole string; NT4 property patches are merged by the listener. */
  public void setMetadata(int id, String metadata, long timestampUs) throws IOException {
    var out = new ByteArrayOutputStream(); out.write(2); little(out, id, 4); string(out, metadata);
    record(0, timestampUs, out.toByteArray());
  }

  public Written append(int id, long timestampUs, byte[] payload) throws IOException {
    if (id <= 0) throw new IllegalArgumentException("Entry id must be positive");
    return record(id, timestampUs, payload);
  }

  private Written record(int id, long timestampUs, byte[] payload) throws IOException {
    if (broken) throw new IOException("Capture output has an incomplete record");
    int ids = width(Integer.toUnsignedLong(id), 4);
    int sizes = width(payload.length, 4);
    int times = width(timestampUs, 8);
    var header = new ByteArrayOutputStream();
    header.write(ids - 1 | (sizes - 1) << 2 | (times - 1) << 4);
    little(header, id, ids); little(header, payload.length, sizes); little(header, timestampUs, times);
    long offset = channel.position();
    try { write(ByteBuffer.wrap(header.toByteArray())); write(ByteBuffer.wrap(payload)); }
    catch (IOException failure) {
      // Never append finishes after half a data record. Truncation also frees a disk-full tail.
      try { channel.truncate(offset); channel.position(offset); }
      catch (IOException rollback) { broken = true; failure.addSuppressed(rollback); }
      throw failure;
    }
    return new Written(offset, header.size() + payload.length, offset + header.size(), payload.length);
  }

  protected void write(ByteBuffer buffer) throws IOException {
    while (buffer.hasRemaining()) channel.write(buffer);
  }
  private static int width(long value, int max) {
    int n = 1;
    while (n < max && value >>> (8 * n) != 0) n++;
    return n;
  }
  private static void little(ByteArrayOutputStream out, long value, int size) {
    for (int i = 0; i < size; i++) out.write((int) (value >>> (i * 8)) & 255);
  }
  private static void string(ByteArrayOutputStream out, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    little(out, bytes.length, 4); out.writeBytes(bytes);
  }

  /** The NT4 code selects the payload decoder; the entry's announced type string is preserved. */
  public static byte[] payload(int code, Object value) {
    var out = new ByteArrayOutputStream();
    if (code >= 16) {
      var values = (List<?>) value;
      if (code == 20) {
        little(out, values.size(), 4);
        values.forEach(v -> string(out, (String) v));
      } else values.forEach(v -> out.writeBytes(payload(code - 16, v)));
      return out.toByteArray();
    }
    return switch (code) {
      case 0 -> new byte[] {(byte) ((Boolean) value ? 1 : 0)};
      case 1 -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble((Double) value).array();
      case 2 -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(((Number) value).longValue()).array();
      case 3 -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat((Float) value).array();
      case 4 -> ((String) value).getBytes(StandardCharsets.UTF_8);
      case 5 -> ((byte[]) value).clone();
      default -> throw new IllegalArgumentException("Unsupported NT4 type code " + code);
    };
  }

  public void flush() throws IOException { channel.force(false); }
  @Override public void close() throws IOException {
    try { flush(); } finally { try { channel.close(); } finally { lease.close(); } }
  }
}
