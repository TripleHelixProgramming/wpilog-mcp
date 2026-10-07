/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/**
 * An HTTP-owned hidden inbox transfer. Its separate lock keeps the watcher from taking a slow
 * upload; a crash releases that lock and the existing inbox sweep removes the abandoned bytes.
 * Reception uses bounded buffers outside the store queue. Only the verified importer places it.
 */
public final class StoreUpload implements AutoCloseable {
  public static final long MAX_BYTES = Integer.MAX_VALUE;
  private final StoreFiles io;
  private final Path root, directory, marker, path;
  private final FileChannel channel;
  private final FileLock lock;
  private StoreUpload(Path root, StoreFiles io, Path directory, Path marker, Path path,
      FileChannel channel, FileLock lock) {
    this.root = root; this.io = io; this.directory = directory; this.marker = marker; this.path = path;
    this.channel = channel; this.lock = lock;
  }
  public Path path() { return path; }
  Path root() { return root; }
  public static void validate(String name, long length, String hash) {
    StoreFiles.component(name);
    if (length < 0 || length > MAX_BYTES) throw new IllegalArgumentException("Upload exceeds the 2 GB reader limit or has no length");
    if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("X-WPILOG-SHA256 must be a lowercase SHA-256 hash");
  }
  static StoreUpload receive(Path root, SecurityValidator security, String name, long length,
      String hash, InputStream input) throws IOException {
    validate(name, length, hash);
    var io = new StoreFiles(root, security);
    if (io.read(root.resolve("store.json"), StoreManifest.Header.class).mirror()) {
      throw new IOException("A mirror is owned by its synchronization; uploads are refused");
    }
    var inbox = io.check(root.resolve("inbox")); Files.createDirectories(inbox);
    String id = ".transfer-" + UUID.randomUUID();
    var directory = io.check(inbox.resolve(id)); var marker = io.check(inbox.resolve(id + ".lock"));
    var channel = FileChannel.open(marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    FileLock lock;
    try { lock = channel.lock(); } catch (IOException | RuntimeException e) { channel.close(); Files.deleteIfExists(marker); throw e; }
    var upload = new StoreUpload(root, io, directory, marker, directory.resolve(name), channel, lock);
    try {
      Files.createDirectory(directory);
      var digest = MessageDigest.getInstance("SHA-256");
      long count = 0;
      try (var output = Files.newOutputStream(io.check(upload.path), StandardOpenOption.CREATE_NEW)) {
        byte[] buffer = new byte[64 * 1024];
        for (int read; (read = input.read(buffer)) != -1;) {
          count += read;
          if (count > length) throw new IOException("Upload exceeds its declared Content-Length");
          output.write(buffer, 0, read); digest.update(buffer, 0, read);
        }
      }
      if (count != length) throw new IOException("Upload ended before its declared Content-Length");
      if (!HexFormat.of().formatHex(digest.digest()).equals(hash)) throw new IllegalArgumentException("Upload SHA-256 differs from the source");
      return upload;
    } catch (java.security.NoSuchAlgorithmException e) {
      upload.close(); throw new IllegalStateException("The JDK must provide SHA-256", e);
    } catch (IOException | RuntimeException e) {
      try { upload.close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
      throw e;
    }
  }
  @Override public void close() throws IOException {
    // Keep the ownership lock until our bytes are gone, then close it before Windows deletes it.
    try { Files.deleteIfExists(io.check(path)); Files.deleteIfExists(io.check(directory)); }
    finally { try { lock.close(); } finally { channel.close(); } }
    Files.deleteIfExists(io.check(marker));
  }
}
