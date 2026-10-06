/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/**
 * Refresh must close file handles before renaming a directory on Windows. A marker in the
 * locked file bridges that gap: even a caller that opened the old inode before the rename
 * sees the marker, and cannot mistake its lock for a lock on the new installation.
 */
final class InstallGuard implements AutoCloseable {
  private static final String MARKER = "refresh ";
  private final FileChannel channel;
  private final FileLock lock;
  private String mark;

  private InstallGuard(FileChannel channel, FileLock lock) {
    this.channel = channel;
    this.lock = lock;
  }

  static InstallGuard acquire(Path directory, Path file) throws IOException {
    return acquire(directory, file, false);
  }

  static InstallGuard acquire(Path directory, Path file, boolean refresh) throws IOException {
    var security = new SecurityValidator();
    security.addAllowedDirectory(directory.toRealPath());
    security.validate(file);
    var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ,
        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    try {
      var lock = channel.tryLock();
      if (lock == null) {
        throw new IOException("Another installation is in progress in " + directory);
      }
      var guard = new InstallGuard(channel, lock);
      var contents = read(channel);
      if (contents.startsWith(MARKER)) {
        String owner = contents.substring(MARKER.length()).split(" ")[0];
        boolean alive;
        try {
          alive = ProcessHandle.of(Long.parseLong(owner)).map(ProcessHandle::isAlive).orElse(false);
        } catch (NumberFormatException e) {
          alive = true;
        }
        if (!refresh || alive) {
          throw new IOException("Install refresh is in progress or was interrupted: " + file
              + ". If its installer has exited, run install --refresh again.");
        }
        channel.truncate(0);
      }
      return guard;
    } catch (IOException | RuntimeException e) {
      channel.close();
      if (e instanceof OverlappingFileLockException) {
        throw new IOException("Another installation is in progress in " + directory, e);
      }
      throw e;
    }
  }

  void markForRefresh() throws IOException {
    mark = MARKER + ProcessHandle.current().pid() + " " + UUID.randomUUID();
    channel.truncate(0);
    channel.position(0);
    var bytes = ByteBuffer.wrap(mark.getBytes(StandardCharsets.UTF_8));
    while (bytes.hasRemaining()) {
      channel.write(bytes);
    }
    channel.force(true);
  }

  /** Clear only our own marker if rename failed; a competing operation's marker is never erased. */
  void clearMarker(Path file) throws IOException {
    if (mark == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (var current = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS); var held = current.tryLock()) {
      if (held != null && mark.equals(read(current))) {
        current.truncate(0);
      }
    }
  }

  private static String read(FileChannel channel) throws IOException {
    var bytes = ByteBuffer.allocate(256);
    channel.read(bytes, 0);
    bytes.flip();
    return StandardCharsets.UTF_8.decode(bytes).toString();
  }

  @Override
  public void close() throws IOException {
    try {
      lock.release();
    } finally {
      channel.close();
    }
  }
}
