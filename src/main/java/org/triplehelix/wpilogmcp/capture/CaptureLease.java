/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writer and recovery share a persistent sidecar lock. Locking the data file itself would let
 * a mapped reader's channel close release a writer's OS lock on some systems. A process-local
 * claim also prevents a failed second probe from closing another channel to this lock file.
 * Never unlink the sidecar: a replaced inode could admit two owners. Process death releases it.
 */
public final class CaptureLease implements AutoCloseable {
  private static final Set<Path> HELD = ConcurrentHashMap.newKeySet();
  private final Path path;
  private final FileChannel channel;
  private final AtomicBoolean closed = new AtomicBoolean();

  private CaptureLease(Path path, FileChannel channel) { this.path = path; this.channel = channel; }

  public static Path lockPath(Path capture) throws IOException {
    var real = Files.exists(capture) ? capture.toRealPath()
        : capture.getParent().toRealPath().resolve(capture.getFileName());
    return real.resolveSibling("." + real.getFileName() + ".writer.lock");
  }

  /** Empty means a writer or recovery already owns the file; no waiting or PID heuristics. */
  public static Optional<CaptureLease> tryAcquire(Path capture) throws IOException {
    var path = lockPath(capture);
    if (Files.isSymbolicLink(path)) throw new IOException("Capture lock must not be a symbolic link: " + path);
    if (!HELD.add(path)) return Optional.empty();
    FileChannel channel = null;
    try {
      channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
      if (channel.tryLock() != null) return Optional.of(new CaptureLease(path, channel));
      channel.close(); HELD.remove(path); return Optional.empty();
    } catch (IOException | RuntimeException e) {
      try { if (channel != null) channel.close(); } catch (IOException close) { e.addSuppressed(close); }
      HELD.remove(path); throw e;
    }
  }

  @Override public void close() throws IOException {
    if (!closed.compareAndSet(false, true)) return;
    try { channel.close(); } finally { HELD.remove(path); }
  }
}
