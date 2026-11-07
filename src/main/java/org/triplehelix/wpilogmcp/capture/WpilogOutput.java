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
 * this can ship in the Java-only install. Offsets describe the durable format, not a second
 * representation invented for capture.
 *
 * <p>A capture's output keeps its records in memory until the file takes many in one write: at
 * the writer's flush tick, when the buffer fills, and at close. Written record by record, each
 * value cost five system calls, two writes and three requests for the position, about 4 µs, and
 * at ten times the soak's rate the loop waited inside them for over a second at a time. A record
 * is therefore in the file only once {@link #writtenBytes} covers it; a crash loses what the
 * buffer holds, at most one flush tick's records. Any other output writes each record as it comes.
 */
public class WpilogOutput implements AutoCloseable {
  public record Written(long offset, int size, long payloadOffset, int payloadSize) {}
  /** What a capture's output keeps in memory: a tick's records at ten times the soak's rate are about 200 KB. */
  public static final int CAPTURE_BUFFER_BYTES = 256 << 10;
  private final FileChannel channel;
  private final CaptureLease lease;
  private int nextEntry;
  private boolean broken;
  /** Records waiting for the file, or null when each record is written as it comes. */
  private final ByteBuffer pending;
  private int pendingRecords;
  /** With a buffer, the bytes the file holds, which a record's offset counts from. */
  private long written;
  private long lostRecords;

  public WpilogOutput(Path path) throws IOException { this(path, 1, false); }

  /** Only the recorder which closed this session can resume it, with its next unused entry id. */
  public WpilogOutput(Path path, int nextEntry, boolean resume) throws IOException {
    this(path, nextEntry, resume, 0);
  }

  /** As above, keeping up to {@code bufferBytes} of records in memory; 0 writes each as it comes. */
  public WpilogOutput(Path path, int nextEntry, boolean resume, int bufferBytes) throws IOException {
    if (bufferBytes < 0) throw new IllegalArgumentException("Negative buffer");
    pending = bufferBytes == 0 ? null : ByteBuffer.allocate(bufferBytes);
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
      written = opened.size();
    } catch (IOException | RuntimeException e) {
      try { if (opened != null) opened.close(); } catch (IOException close) { e.addSuppressed(close); }
      try { lease.close(); } catch (IOException close) { e.addSuppressed(close); }
      throw e;
    }
  }

  public int nextEntry() { return nextEntry; }
  /** The bytes written so far, the buffer's included: where the next record starts. */
  public long size() throws IOException { return pending == null ? channel.position() : written + pending.position(); }
  /** The bytes the file holds: every record that ends within them is in the file. */
  public long writtenBytes() throws IOException { return pending == null ? channel.position() : written; }
  /** Records a failed write of the buffer lost; the file ends before them. */
  public long lostRecords() { return lostRecords; }

  /**
   * Writes the records waiting in memory to the file, in one write. A failure leaves the file as
   * it was before them, and they are lost.
   */
  public void drain() throws IOException {
    if (pending == null || pending.position() == 0) return;
    if (broken) throw new IOException("Capture output has an incomplete record");
    pending.flip();
    try {
      write(pending);
      written += pending.limit();
    } catch (IOException failure) {
      lostRecords += pendingRecords;
      try { channel.truncate(written); channel.position(written); }
      catch (IOException rollback) { broken = true; failure.addSuppressed(rollback); }
      throw failure;
    } finally {
      pending.clear();
      pendingRecords = 0;
    }
  }
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
    int headerSize = 1 + ids + sizes + times;
    if (pending != null && headerSize + payload.length <= pending.capacity()) {
      if (pending.remaining() < headerSize + payload.length) drain();
      long offset = written + pending.position();
      pending.put((byte) (ids - 1 | (sizes - 1) << 2 | (times - 1) << 4));
      little(pending, id, ids); little(pending, payload.length, sizes); little(pending, timestampUs, times);
      pending.put(payload);
      pendingRecords++;
      return new Written(offset, headerSize + payload.length, offset + headerSize, payload.length);
    }
    // A record larger than the buffer follows the records waiting for the file.
    if (pending != null) drain();
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
    if (pending != null) written = offset + header.size() + payload.length;
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
  private static void little(ByteBuffer out, long value, int size) {
    for (int i = 0; i < size; i++) out.put((byte) (value >>> (i * 8)));
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

  /** Forces what the file holds to the disk; records still in the buffer wait for {@link #drain}. */
  public void flush() throws IOException { channel.force(false); }
  @Override public void close() throws IOException {
    try {
      IOException failure = null;
      try { drain(); } catch (IOException e) { failure = e; }
      // What the file holds is forced even when the buffer's last write failed.
      try { flush(); } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
      if (failure != null) throw failure;
    } finally { try { channel.close(); } finally { lease.close(); } }
  }
}
