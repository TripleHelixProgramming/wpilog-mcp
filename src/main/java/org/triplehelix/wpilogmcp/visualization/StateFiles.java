/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Local recovery state has its own lock and queue; it never waits on the log import queue. */
final class StateFiles {
  private final Path root;
  StateFiles(Path root) throws IOException {
    this.root = root.toAbsolutePath().normalize();
    for (Path p = this.root; p != null; p = p.getParent()) {
      if (Files.isSymbolicLink(p)) throw new IOException("Recovery state cannot traverse a symbolic link: " + p);
    }
    Files.createDirectories(this.root);
  }
  Path path(String name) throws IOException {
    var path = root.resolve(name).normalize();
    if (!path.startsWith(root) || path.equals(root)) throw new IOException("Recovery path escapes state directory");
    for (Path p = path; !p.equals(root); p = p.getParent()) {
      if (Files.isSymbolicLink(p)) throw new IOException("Recovery state cannot contain symbolic links: " + p);
    }
    return path;
  }
  FileChannel lock() throws IOException {
    var channel = FileChannel.open(path("state.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    try {
      if (channel.tryLock() == null) throw new IOException("WORKSPACE_BUSY: recovery state is being updated; retry");
      return channel;
    } catch (OverlappingFileLockException e) {
      channel.close(); throw new IOException("WORKSPACE_BUSY: recovery state is being updated; retry", e);
    } catch (IOException | RuntimeException e) { channel.close(); throw e; }
  }
  String read(String name, long limit) throws IOException {
    var file = path(name);
    try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      var bytes = in.readNBytes(Math.toIntExact(limit + 1));
      if (bytes.length > limit) throw new IOException("WORKSPACE_LIMIT: recovery record too large");
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }
  /** Move the leaf itself, including an unsafe symlink, without reading its target. */
  void quarantineView(String name) throws IOException {
    if (!Path.of(name).getFileName().toString().equals(name)) throw new IOException("Invalid recovery filename");
    var source = path("views").resolve(name);
    var destination = path("quarantine/" + java.util.UUID.randomUUID());
    Files.createDirectories(destination);
    Files.move(source, destination.resolve(name), StandardCopyOption.ATOMIC_MOVE);
  }
  void write(String name, String json) throws IOException {
    var target = path(name); Files.createDirectories(target.getParent()); path(name);
    var temp = Files.createTempFile(target.getParent(), ".pending-", ".tmp");
    try {
      try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        var buffer = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally { Files.deleteIfExists(temp); }
  }
}
