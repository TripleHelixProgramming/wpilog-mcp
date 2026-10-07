/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.triplehelix.wpilogmcp.capture.context.DeviceIdentity;

/** Only bytes made by test fixture writers; used at the transport boundary, without sockets. */
public final class FakeRobot implements RobotRemote {
  public record Read(String name, long offset, int count) {}
  public final Map<String, byte[]> files = new LinkedHashMap<>();
  public final List<Read> reads = new ArrayList<>();
  public long mtime = 1_772_892_000_000L;
  public DeviceIdentity device = device("SYNTHETIC-A", "SHA256:first");
  public Runnable onRead = () -> {};
  public boolean closed;
  public static DeviceIdentity device(String serial, String fingerprint) {
    return new DeviceIdentity(serial, "fixture robot", "127.0.0.1", fingerprint, Map.of("serial_source", "/proc/42/environ:serialnum", "comments_source", "/etc/machine-info:PRETTY_HOSTNAME"));
  }
  @Override public DeviceIdentity identity() { return device; }
  @Override public List<File> list() { return files.entrySet().stream().map(e -> new File(e.getKey(), e.getValue().length, mtime)).toList(); }
  @Override public byte[] read(String name, long offset, int count) throws IOException {
    reads.add(new Read(name, offset, count)); onRead.run();
    var data = files.get(name); if (data == null) throw new IOException("missing remote file");
    return Arrays.copyOfRange(data, (int) offset, (int) Math.min(data.length, offset + count));
  }
  @Override public Optional<String> prefixHash(String name, long length) throws IOException {
    if (length > files.get(name).length) throw new IOException("short remote prefix");
    try { return Optional.of(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(files.get(name), (int) length)))); }
    catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
  }
  @Override public void close() { closed = true; }
}
