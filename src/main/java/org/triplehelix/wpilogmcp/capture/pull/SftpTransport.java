/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UserInfo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.capture.context.DeviceIdentity;
import org.triplehelix.wpilogmcp.capture.context.RobotIdentityReader;
import org.triplehelix.wpilogmcp.config.PullConfig;
import org.triplehelix.wpilogmcp.sync.FileTransfer;

/** JSch is confined here. No shell command writes a remote file, and SFTP has no write port. */
public final class SftpTransport implements RobotRemote {
  static final int TIMEOUT_MS = 5000;
  public static final String HOST_KEY_ALGORITHMS = "ssh-ed25519,rsa-sha2-512,rsa-sha2-256";
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

  /** Secret authentication requires the pinned key or explicit acceptance of its replacement. */
  public static SftpTransport connect(String address, PullConfig config, String pinnedFingerprint) throws IOException {
    return connect(address, config, pinnedFingerprint, new JSch(), null);
  }
  interface Deadlines extends AutoCloseable {
    Runnable schedule(Runnable task, long delayMs);
    @Override void close();
  }
  static SftpTransport connect(String address, PullConfig config, String pinnedFingerprint, JSch jsch, Deadlines deadlines) throws IOException {
    Session session = null;
    try {
      if (config.ssh().key() != null) jsch.addIdentity(config.ssh().key().toString());
      var keys = new Pin(address, pinnedFingerprint, config.ssh()); jsch.setHostKeyRepository(keys);
      session = jsch.getSession(config.ssh().user(), address, 22);
      session.setConfig("StrictHostKeyChecking", "yes");
      session.setConfig("server_host_key", HOST_KEY_ALGORITHMS);
      session.setConfig("PreferredAuthentications", config.ssh().key() == null ? "password,keyboard-interactive" : "publickey");
      if (config.ssh().key() == null) session.setPassword(config.ssh().password());
      session.setDaemonThread(true);
      session.setServerAliveInterval(TIMEOUT_MS); session.setServerAliveCountMax(3);
      session.connect(TIMEOUT_MS);
      var sftp = (ChannelSftp) session.openChannel("sftp"); sftp.connect(TIMEOUT_MS); sftp.setBulkRequests(1);
      String fingerprint = fingerprint(Base64.getDecoder().decode(session.getHostKey().getKey()));
      return new SftpTransport(new JschChannel(session, sftp, deadlines == null ? daemonDeadlines() : deadlines), config.directories(), address, fingerprint);
    } catch (JSchException | RuntimeException e) {
      if (session != null) session.disconnect();
      if (deadlines != null) deadlines.close();
      throw new IOException("SSH connection to " + address + " failed: " + e.getMessage(), e);
    }
  }

  static String fingerprint(byte[] key) {
    try { return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key)); }
    catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
  }
  static final class Pin implements HostKeyRepository {
    private final String address, expected;
    private final PullConfig.Ssh authentication;
    Pin(String address, String expected) { this(address, expected, PullConfig.DISABLED.ssh()); }
    Pin(String address, String expected, PullConfig.Ssh authentication) {
      this.address = address; this.expected = expected; this.authentication = authentication;
    }
    @Override public int check(String host, byte[] key) {
      String observed = fingerprint(key);
      if (expected != null && !expected.equals(observed)) {
        boolean secret = authentication.key() != null || !authentication.password().isEmpty();
        if (secret && !authentication.acceptChangedHostKey()) {
          LoggerFactory.getLogger(SftpTransport.class).warn(
              "SSH host key changed at {}: {} -> {}; refused before authentication. Verify the replacement, then set capture.pull.ssh.accept_changed_host_key: true or remove the pinned fingerprint from robot.json", address, expected, observed);
          return CHANGED;
        }
        LoggerFactory.getLogger(SftpTransport.class).warn(
            "SSH host key changed at {}: {} -> {}; checking device serial before continuing its pull manifest", address, expected, observed);
      }
      return OK; // First contact is TOFU; a changed key with secrets needs the opt-in above.
    }
    @Override public void add(HostKey key, UserInfo info) {}
    @Override public void remove(String host, String type) { throw new UnsupportedOperationException("Read-only host keys"); }
    @Override public void remove(String host, String type, byte[] key) { throw new UnsupportedOperationException("Read-only host keys"); }
    @Override public String getKnownHostsRepositoryID() { return "robot.json contact fingerprints"; }
    @Override public HostKey[] getHostKey() { return new HostKey[0]; }
    @Override public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
  }

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
  private static Deadlines daemonDeadlines() {
    return new Deadlines() {
      private final java.util.concurrent.ScheduledThreadPoolExecutor timer = new java.util.concurrent.ScheduledThreadPoolExecutor(1, task -> {
        var thread = new Thread(task, "ssh-command-deadline"); thread.setDaemon(true); return thread;
      });
      { timer.setRemoveOnCancelPolicy(true); timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false); }
      public Runnable schedule(Runnable task, long delayMs) {
        var future = timer.schedule(task, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
      }
      public void close() { timer.shutdownNow(); }
    };
  }
  static String shellQuote(String value) {
    if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL in remote path");
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }
  /** JSch accepts globs even for get; escape their metacharacters to address one literal file. */
  static String sftpLiteral(String value) { return value.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?"); }
  @Override public void close() { channel.close(); }

  private static final class JschChannel implements Channel {
    private final Session session;
    private final ChannelSftp sftp;
    private final Deadlines deadlines;
    private JschChannel(Session session, ChannelSftp sftp, Deadlines deadlines) {
      this.session = session; this.sftp = sftp; this.deadlines = deadlines;
    }
    @Override public List<Item> list(String directory) throws IOException {
      try {
        var result = new ArrayList<Item>();
        for (var item : sftp.ls(sftpLiteral(directory))) {
          var a = item.getAttrs();
          result.add(new Item(item.getFilename(), a.getSize(), Integer.toUnsignedLong(a.getMTime()) * 1000, a.isDir(), a.isReg(), a.isLink()));
        }
        return result;
      } catch (SftpException e) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return List.of(); // USB logging directories are optional.
        throw new IOException("SFTP listing failed: " + directory, e);
      }
    }
    @Override public byte[] read(String path, long offset, int count) throws IOException {
      try (var input = sftp.get(sftpLiteral(path), null, offset)) { return input.readNBytes(count); }
      catch (SftpException e) { throw new IOException("SFTP read failed: " + path, e); }
    }
    @Override public Optional<String> exec(String command, long timeoutMs) throws IOException {
      ChannelExec exec = null;
      Runnable cancel = () -> {};
      var completed = new java.util.concurrent.atomic.AtomicBoolean();
      var expired = new java.util.concurrent.atomic.AtomicBoolean();
      try {
        exec = (ChannelExec) session.openChannel("exec"); exec.setCommand(command); exec.setInputStream(null);
        var channel = exec;
        cancel = deadlines.schedule(() -> {
          if (completed.compareAndSet(false, true)) { expired.set(true); channel.disconnect(); }
        }, timeoutMs);
        try (InputStream input = exec.getInputStream()) {
          exec.connect(TIMEOUT_MS);
          byte[] output = input.readNBytes(4097);
          if (expired.get()) throw new IOException("SSH hash deadline exceeded after " + timeoutMs + " ms");
          if (output.length > 4096) throw new IOException("SSH hash reply too large");
          return Optional.of(new String(output, StandardCharsets.US_ASCII));
        }
      } catch (JSchException | IOException e) {
        if (expired.get()) throw new IOException("SSH hash deadline exceeded after " + timeoutMs + " ms", e);
        if (e instanceof IOException io) throw io;
        return Optional.empty(); // SFTP-only servers use the range comparison.
      } finally { completed.set(true); cancel.run(); if (exec != null) exec.disconnect(); }
    }
    @Override public void close() { sftp.disconnect(); session.disconnect(); deadlines.close(); }
  }
}
