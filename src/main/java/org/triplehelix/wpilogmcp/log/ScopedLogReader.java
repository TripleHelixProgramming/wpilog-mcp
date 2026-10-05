/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import edu.wpi.first.util.datalog.DataLogReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * A reader whose mapping belongs to one operation. Import must release it before renaming a
 * file on Windows; GC is not a resource lifetime. Neither the reader nor decoded buffer views
 * may escape this scope. Shared, cached lazy logs deliberately have a different lifetime.
 */
public final class ScopedLogReader implements AutoCloseable {
  private final MappedByteBuffer buffer;
  private final DataLogReader reader;
  private final Object cleaner;
  private final Method clean;
  private boolean closed;
  private final LogFileAccess.Lease lease;

  public ScopedLogReader(Path path) throws IOException {
    try {
      var type = Class.forName("sun.misc.Unsafe");
      var field = type.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      cleaner = field.get(null);
      clean = type.getMethod("invokeCleaner", java.nio.ByteBuffer.class);
    } catch (ReflectiveOperationException | RuntimeException e) {
      throw new IOException("This JVM cannot release a log mapping before an import moves it", e);
    }
    lease = LogFileAccess.read(path, false);
    try (var channel = FileChannel.open(path)) {
      long size = channel.size();
      if (size < 12 || size > Integer.MAX_VALUE) throw LogFileException.invalid(path);
      buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
    } catch (IOException | RuntimeException e) {
      lease.close();
      throw e;
    }
    reader = new DataLogReader(buffer);
    if (!reader.isValid()) {
      close();
      throw LogFileException.invalid(path);
    }
  }

  public DataLogReader reader() {
    if (closed) throw new IllegalStateException("Log reader is closed");
    return reader;
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    try {
      clean.invoke(cleaner, buffer);
      closed = true;
      lease.close();
    } catch (ReflectiveOperationException e) {
      throw new IOException("Could not release log mapping; file must not be moved", e);
    }
  }
}
