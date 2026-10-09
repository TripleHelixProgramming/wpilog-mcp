/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import java.io.IOException;
import java.nio.file.Path;

/** All windows belong to one operation. Release every mapping before releasing the file lease:
 * an import may rename immediately after that lease ends, including on Windows. */
public final class ScopedLogReader implements AutoCloseable {
  private final MappedLogBytes bytes;
  private final LogReader reader;
  private final LogFileAccess.Lease lease;
  private boolean closed;
  public ScopedLogReader(Path path) throws IOException { this(path, MappedLogBytes.windowBytes()); }
  public ScopedLogReader(Path path, int windowBytes) throws IOException {
    lease = LogFileAccess.read(path);
    try { bytes = new MappedLogBytes(path, windowBytes); }
    catch (Throwable e) { lease.close(); throw e; }
    reader = new LogReader(bytes);
    try { if (!reader.isValid()) throw LogFileException.invalid(path); }
    catch (Throwable e) { try { close(); } catch (IOException failure) { e.addSuppressed(failure); } throw e; }
  }
  public LogReader reader() {
    if (closed) throw new IllegalStateException("Log reader is closed"); return reader;
  }
  int mappedWindows() { return bytes.mappedWindows(); }
  @Override public void close() throws IOException {
    if (closed) return;
    bytes.close(); closed = true; lease.close();
  }
}
