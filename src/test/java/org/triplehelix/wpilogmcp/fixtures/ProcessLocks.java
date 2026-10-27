/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.fixtures;

import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/**
 * Waits for the file locks a killed child process held. Windows releases a terminated process's
 * locks when resources allow, not when the process exits (LockFileEx's documentation says so),
 * so a test that kills a lock holder and at once expects the lock free failed there under load:
 * the next sync found "A sync is already running", or recovery kept a stage or capture as live.
 * macOS and Linux release the locks at exit.
 */
public final class ProcessLocks {
  private ProcessLocks() {}

  /** Returns once a lock on the file can be taken; a missing file holds no lock. */
  public static void awaitReleased(Path file) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (Files.exists(file)) {
      try (var channel = FileChannel.open(file, StandardOpenOption.WRITE); var lock = channel.tryLock()) {
        if (lock != null) return;
      } catch (OverlappingFileLockException ownedHere) {
        return; // This JVM holds it, not the dead child.
      }
      if (System.nanoTime() > deadline) throw new AssertionError("A dead child's lock was still held 10 s after it exited: " + file);
      Thread.sleep(10);
    }
  }

  /** Waits for every lock file of this name under a folder: a killed child may hold several. */
  public static void awaitReleasedUnder(Path root, String name) throws Exception {
    java.util.List<Path> files;
    try (var paths = Files.walk(root)) {
      files = paths.filter(path -> path.getFileName() != null && path.getFileName().toString().equals(name)).toList();
    }
    for (var file : files) awaitReleased(file);
  }
}
