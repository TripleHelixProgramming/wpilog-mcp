/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.triplehelix.wpilogmcp.capture.context.DeviceIdentity;
import org.triplehelix.wpilogmcp.capture.context.RobotIdentityReader;
import org.triplehelix.wpilogmcp.config.PullConfig;
import org.triplehelix.wpilogmcp.sync.FileTransfer;

/** Read-only robot files over a consumer-owned channel of a shared SSH connection. */
public final class SftpTransport implements RobotRemote {
  record Item(String name, long size, long mtimeMillis, boolean directory, boolean regular, boolean symlink) {}
  interface Channel extends AutoCloseable {
    List<Item> list(String directory) throws IOException;
    byte[] read(String path, long offset, int count) throws IOException;
    Optional<String> exec(String command, long timeoutMs) throws IOException;
    @Override void close();
  }
  private final Channel channel;
  private final List<String> directories;
  private final String address, fingerprint;

  SftpTransport(Channel channel, List<String> directories, String address, String fingerprint) {
    this.channel = channel; this.directories = List.copyOf(directories); this.address = address; this.fingerprint = fingerprint;
  }

  /** Standalone callers own their connection; the capture service instead lends a shared one. */
  public static SftpTransport connect(String address, PullConfig config, String pin) throws IOException {
    return using(org.triplehelix.wpilogmcp.ssh.JschConnection.connect(address, config.ssh(), pin), config.directories(), address, true);
  }
  public static SftpTransport using(org.triplehelix.wpilogmcp.ssh.SshConnection ssh, List<String> directories,
      String address, boolean ownConnection) throws IOException {
    org.triplehelix.wpilogmcp.ssh.SshConnection.Files files;
    try { files = ssh.files(); }
    catch (IOException | RuntimeException e) { if (ownConnection) ssh.close(); throw e; }
    return new SftpTransport(new Channel() {
      public List<Item> list(String directory) throws IOException {
        return files.list(directory).stream().map(i -> new Item(i.name(), i.size(), i.mtimeMillis(), i.directory(), i.regular(), i.symlink())).toList();
      }
      public byte[] read(String path, long offset, int count) throws IOException { return files.read(path, offset, count); }
      public Optional<String> exec(String command, long timeoutMs) throws IOException { return ssh.exec(command, timeoutMs, 4096); }
      public void close() { files.close(); if (ownConnection) ssh.close(); }
    }, directories, address, ssh.fingerprint());
  }
  static String fingerprint(byte[] key) { return org.triplehelix.wpilogmcp.ssh.JschConnection.fingerprint(key); }

  @Override public DeviceIdentity identity() throws IOException {
    return RobotIdentityReader.read(new RobotIdentityReader.Files() {
      @Override public List<String> environments() throws IOException {
        return channel.list("/proc").stream().filter(i -> i.directory() && !i.symlink() && i.name().matches("[0-9]+"))
            .map(i -> "/proc/" + i.name() + "/environ").toList();
      }
      @Override public byte[] read(String path) throws IOException { return channel.read(path, 0, 1 << 20); }
    }, address, fingerprint);
  }
  @Override public List<File> list() throws IOException {
    var result = new ArrayList<File>(); var pending = new java.util.ArrayDeque<>(directories);
    var visited = new java.util.HashSet<String>();
    while (!pending.isEmpty()) {
      String parent = pending.removeFirst(); if (!visited.add(parent)) continue;
      if (visited.size() + result.size() > 100_000) throw new IOException("Remote log listing exceeds 100000 paths");
      for (var item : channel.list(parent)) {
        if (item.name().equals(".") || item.name().equals("..")) continue;
        if (item.name().contains("/") || item.name().indexOf('\0') >= 0) throw new IOException("Invalid remote child name");
        if (item.symlink()) continue;
        String path = (parent.endsWith("/") ? parent : parent + "/") + item.name();
        if (item.directory()) pending.addLast(path);
        else if (item.regular() && item.name().toLowerCase(java.util.Locale.ROOT).matches(".*\\.(wpilog|revlog)")) {
          result.add(new File(path, item.size(), item.mtimeMillis()));
        }
      }
    }
    return result.stream().sorted(java.util.Comparator.comparing(File::name)).toList();
  }
  @Override public byte[] read(String name, long offset, int count) throws IOException {
    if (offset < 0 || count < 0 || count > FileTransfer.BLOCK_BYTES) throw new IOException("Invalid SFTP block range");
    return channel.read(name, offset, count);
  }
  @Override public Optional<String> prefixHash(String name, long length) throws IOException {
    if (length < 0) throw new IOException("Negative prefix length");
    var value = channel.exec("head -c " + length + " -- " + shellQuote(name) + " | sha256sum", hashTimeoutMs(length));
    if (value.isEmpty()) return Optional.empty();
    String text = value.get().strip();
    if (!text.matches("(?s)[0-9a-fA-F]{64}\\s+.*")) return Optional.empty();
    return Optional.of(text.substring(0, 64).toLowerCase(java.util.Locale.ROOT));
  }
  /** Allow 30 seconds of startup plus one second per 256 KiB read and hashed on the robot. */
  static long hashTimeoutMs(long length) {
    return 30_000 + (length / 262_144 + (length % 262_144 == 0 ? 0 : 1)) * 1000;
  }
  static String shellQuote(String value) { return org.triplehelix.wpilogmcp.ssh.SshConnection.quote(value); }
  @Override public void close() { channel.close(); }
}
