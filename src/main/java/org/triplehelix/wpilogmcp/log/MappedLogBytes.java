/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/** Read-only windows with one deterministic owner. A straddling record alone is copied;
 * ordinary records remain views of mapped bytes, with no per-record payload allocation. */
public final class MappedLogBytes implements LogBytes, AutoCloseable {
  public static final int DEFAULT_WINDOW_BYTES = 1 << 30;
  public static final long MAX_FILE_BYTES = 1L << 40; // 1 TiB, at most 1024 default windows.
  private static final ThreadLocal<Integer> TEST_WINDOW = new InheritableThreadLocal<>();
  private final MappedByteBuffer[] windows;
  private final int windowBytes;
  private final long size;
  private final Object cleaner;
  private final Method clean;
  private boolean closed;

  /** Scoped injection is inherited by a test's newly created store queue, never a global property. */
  public static int windowBytes() { return TEST_WINDOW.get() == null ? DEFAULT_WINDOW_BYTES : TEST_WINDOW.get(); }
  public static AutoCloseable withWindowBytes(int bytes) {
    checkWindow(bytes); var before = TEST_WINDOW.get(); TEST_WINDOW.set(bytes);
    return () -> { if (before == null) TEST_WINDOW.remove(); else TEST_WINDOW.set(before); };
  }
  private static void checkWindow(int bytes) {
    if (bytes < 1 || bytes > DEFAULT_WINDOW_BYTES) throw new IllegalArgumentException("Mapping windows must be 1..1073741824 bytes");
  }
  public MappedLogBytes(Path path, int windowBytes) throws IOException {
    checkWindow(windowBytes); this.windowBytes = windowBytes;
    try {
      var type = Class.forName("sun.misc.Unsafe"); var field = type.getDeclaredField("theUnsafe");
      field.setAccessible(true); cleaner = field.get(null); clean = type.getMethod("invokeCleaner", ByteBuffer.class);
    } catch (ReflectiveOperationException | RuntimeException e) {
      throw new IOException("This JVM cannot release log mappings before an import moves them", e);
    }
    try (var channel = FileChannel.open(path)) {
      size = channel.size();
      if (size > MAX_FILE_BYTES) throw LogFileException.tooLarge(path, size);
      windows = new MappedByteBuffer[Math.toIntExact((size + windowBytes - 1) / windowBytes)];
      try {
        for (int i = 0; i < windows.length; i++) {
          long start = (long) i * windowBytes;
          windows[i] = channel.map(FileChannel.MapMode.READ_ONLY, start, Math.min(windowBytes, size - start));
        }
      } catch (Throwable e) { try { close(); } catch (IOException failure) { e.addSuppressed(failure); } throw e; }
    }
  }
  @Override public long size() { return size; }
  private void check(long offset, long length) {
    if (closed) throw new IllegalStateException("Log mapping is closed");
    if (offset < 0 || length < 0 || offset > size - length) throw new IndexOutOfBoundsException("Outside mapped log: " + offset + " + " + length);
  }
  @Override public byte get(long offset) {
    check(offset, 1); return windows[(int) (offset / windowBytes)].get((int) (offset % windowBytes));
  }
  @Override public ByteBuffer view(long offset, int length) {
    check(offset, length);
    if (length == 0) return ByteBuffer.allocate(0);
    int index = (int) (offset / windowBytes), position = (int) (offset % windowBytes);
    var window = windows[index];
    if (length <= window.limit() - position) return window.slice(position, length).order(ByteOrder.LITTLE_ENDIAN);
    var copy = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
    while (copy.hasRemaining()) {
      var part = windows[index++]; int count = Math.min(copy.remaining(), part.limit() - position);
      copy.put(part.slice(position, count)); position = 0;
    }
    return copy.flip();
  }
  int mappedWindows() { return windows.length; }
  int openWindows() { int count = 0; for (var window : windows) if (window != null) count++; return count; }
  @Override public void close() throws IOException {
    if (closed) return;
    IOException failure = null;
    for (int i = 0; i < windows.length; i++) if (windows[i] != null) {
      try { clean.invoke(cleaner, windows[i]); windows[i] = null; }
      catch (ReflectiveOperationException e) { if (failure == null) failure = new IOException("Could not release log mapping; file must not be moved", e); else failure.addSuppressed(e); }
    }
    if (failure != null) throw failure;
    closed = true;
  }
}
