/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * A stage retains its own process lock through copy and publication. A later import can remove
 * an abandoned stage without guessing whether another process is still writing its bytes.
 */
final class ImportStage implements AutoCloseable {
  private static final String PREFIX = ".import-staging-";
  private static final String OWNER = ".owner.lock";
  private static final String MAGIC = "wpilog-import-stage-v1";
  private final Path directory;
  private final FileChannel channel;
  private final FileLock lock;

  private ImportStage(Path directory, FileChannel channel, FileLock lock) {
    this.directory = directory;
    this.channel = channel;
    this.lock = lock;
  }

  /** A persistent container isolates temporary directory changes from catalog identity stamps. */
  static ImportStage create(StoreFiles io, Path destinationParent) throws IOException {
    return create(io, destinationParent, marker -> {});
  }

  static ImportStage create(StoreFiles io, Path destinationParent, BeforeRecovery beforeLock) throws IOException {
    var container = io.check(destinationParent.resolve(".import-staging"));
    Files.createDirectories(container);
    var directory = Files.createTempDirectory(container, PREFIX);
    var marker = directory.resolve(OWNER);
    var channel = FileChannel.open(marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS);
    FileLock held = null;
    try {
      beforeLock.ready(marker);
      held = channel.tryLock();
      if (held == null) throw new IOException("Import stage ownership could not be claimed");
      // Recovery recognizes only an initialized marker. Publishing it after the nonblocking
      // claim prevents another process from collecting a stage before its creator owns it.
      var bytes = java.nio.ByteBuffer.wrap(MAGIC.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      while (bytes.hasRemaining()) channel.write(bytes);
      return new ImportStage(directory, channel, held);
    } catch (IOException | RuntimeException failure) {
      try {
        if (held != null) held.release();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      } finally {
        try { channel.close(); }
        catch (IOException cleanup) { failure.addSuppressed(cleanup); }
      }
      throw failure;
    }
  }

  Path path(Path name) { return directory.resolve(name); }

  /**
   * Removing this marker turns an ordinary refused copy into an unmanaged file, preserving
   * existing diagnostics. A process death retains the marker for the next import's cleanup.
   */
  @Override public void close() {
    try {
      // Retirement is published while ownership is still held. A recoverer which read the
      // old marker must recheck it after locking before deleting an ordinary refused copy.
      channel.truncate(0);
    } catch (IOException failure) {
      LoggerFactory.getLogger(ImportStage.class).warn("Import stage cleanup remains pending: {}", failure.getMessage());
    } finally {
      try { lock.release(); }
      catch (IOException failure) { LoggerFactory.getLogger(ImportStage.class).warn("Import stage lock release failed: {}", failure.getMessage()); }
      try { channel.close(); }
      catch (IOException failure) { LoggerFactory.getLogger(ImportStage.class).warn("Import stage channel close failed: {}", failure.getMessage()); }
    }
    try {
      Files.deleteIfExists(directory.resolve(OWNER));
      try { Files.deleteIfExists(directory); }
      catch (java.nio.file.DirectoryNotEmptyException retained) { /* A refused copy remains visible. */ }
    } catch (IOException failure) {
      LoggerFactory.getLogger(ImportStage.class).warn("Retired import stage cleanup remains pending: {}", failure.getMessage());
    }
  }

  /** Called on staging, never the store owner: discovery and cleanup may outlast a queue turn. */
  static void recover(StoreFiles io) throws IOException {
    recover(io, marker -> {});
  }

  @FunctionalInterface interface BeforeRecovery { void ready(Path marker) throws IOException; }

  static void recover(StoreFiles io, BeforeRecovery beforeLock) throws IOException {
    for (var base : List.of(io.root().resolve("robots"), io.root().resolve("unassigned"))) {
      if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) continue;
      Files.walkFileTree(io.check(base), new SimpleFileVisitor<>() {
        @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
          if (!path.getFileName().toString().startsWith(PREFIX)) return FileVisitResult.CONTINUE;
          recoverDirectory(io, path, beforeLock);
          return FileVisitResult.SKIP_SUBTREE;
        }
      });
    }
  }

  /**
   * Only the locking handle reads the marker. Windows enforces a byte-range lock against every other
   * handle, even this process's own, so a read through another one failed: before the lock for a
   * stage still in use, and after it for this recoverer's own lock. One abandoned stage then broke
   * every later import, sync and pull into the store. An initialized marker has the magic's exact
   * size, which needs no read.
   */
  private static void recoverDirectory(StoreFiles io, Path directory, BeforeRecovery beforeLock) throws IOException {
    var marker = io.check(directory.resolve(OWNER));
    if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) != MAGIC.length()) return;
    beforeLock.ready(marker);
    try (var channel = FileChannel.open(marker, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
         var held = channel.tryLock()) {
      if (held == null) return;
      if (channel.size() != MAGIC.length() || !MAGIC.equals(readMarker(channel))) return;
      // walk does not follow links; a planted link is removed, never traversed.
      try (var paths = Files.walk(directory)) {
        for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
          if (path.equals(directory) || path.equals(marker)) continue;
          if (!Files.isSymbolicLink(path)) io.check(path);
          new LogStore.Mover().discardCopy(path);
        }
      }
    } catch (OverlappingFileLockException active) {
      return;
    } catch (java.nio.file.NoSuchFileException gone) {
      return;
    }
    Files.deleteIfExists(marker);
    Files.deleteIfExists(directory);
  }

  private static String readMarker(FileChannel channel) throws IOException {
    var bytes = java.nio.ByteBuffer.allocate(MAGIC.length());
    while (bytes.hasRemaining() && channel.read(bytes, bytes.position()) >= 0) { /* until full or at the end */ }
    return new String(bytes.array(), 0, bytes.position(), java.nio.charset.StandardCharsets.US_ASCII);
  }
}
