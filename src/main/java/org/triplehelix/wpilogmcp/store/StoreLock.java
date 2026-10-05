/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/**
 * The queue serializes a server's callers; this lock also excludes an offline command or a
 * second server. Never delete the lock file: replacing its inode would admit a second writer.
 */
final class StoreLock implements AutoCloseable {
  private final FileChannel channel;

  private StoreLock(FileChannel channel) {
    this.channel = channel;
  }

  static StoreLock acquire(Path root, SecurityValidator security) throws IOException {
    var io = new StoreFiles(root, security);
    var channel = FileChannel.open(io.check(root.resolve("store.lock")),
        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    try {
      if (channel.tryLock() == null) throw held();
      return new StoreLock(channel);
    } catch (OverlappingFileLockException e) {
      channel.close();
      throw held();
    } catch (IOException | RuntimeException e) {
      channel.close();
      throw e;
    }
  }

  private static IOException held() {
    return new IOException("Store lock is held by another import (store.lock); retry when it finishes");
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }
}
