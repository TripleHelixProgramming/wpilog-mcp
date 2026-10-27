/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.fixtures;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.triplehelix.wpilogmcp.log.LogManager;

/**
 * Changes to a log the server may have loaded. Windows refuses to truncate, replace or delete a
 * file while any process has it mapped, and a loaded log stays mapped; macOS and Linux allow all
 * three, so a test that rewrote a loaded log passed there and failed on Windows. A whole new log
 * releases the old one first. A changed byte is written where it lies, which Windows allows
 * through another handle, and the server's mapping stays loaded for the test to observe.
 */
public final class LogRewrites {
  private LogRewrites() {}

  /** Unloads every cached spelling of a log before a test writes a new one in its place. */
  public static void releaseBeforeRewrite(Path file) throws IOException {
    if (!Files.exists(file)) return;
    var result = LogManager.getInstance().release(file);
    if (!result.released()) throw new IOException("Could not release " + file + " before rewriting it: " + result.reason());
  }

  /** Writes bytes at a position without truncating the file. */
  public static void writeInPlace(Path file, long position, byte[] bytes) throws IOException {
    try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
      var buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) channel.write(buffer, position + buffer.position());
    }
  }

  /** Flips the lowest bit of the file's last byte, in place. */
  public static void flipLastByte(Path file) throws IOException {
    try (var channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      long last = channel.size() - 1;
      var one = ByteBuffer.allocate(1);
      if (last < 0 || channel.read(one, last) != 1) throw new IOException("No last byte to flip in " + file);
      one.put(0, (byte) (one.get(0) ^ 1)).rewind();
      channel.write(one, last);
    }
  }
}
