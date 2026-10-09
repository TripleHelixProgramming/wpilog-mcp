/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.ssh;

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
import org.triplehelix.wpilogmcp.config.PullConfig;

/** JSch session ownership lives here; closing a consumer channel never closes another consumer. */
public final class JschConnection implements SshConnection {
  public static final int TIMEOUT_MS = 5000;
  public static final String HOST_KEY_ALGORITHMS = "ssh-ed25519,rsa-sha2-512,rsa-sha2-256";
  public interface Deadlines extends AutoCloseable {
    Runnable schedule(Runnable task, long delayMs);
    @Override void close();
  }
  private final Session session;
  private final Deadlines deadlines;
  private final String fingerprint;
  private final String authentication;
  public String authentication() { return authentication; }
  private JschConnection(Session session, Deadlines deadlines, String authentication) {
    this.authentication = authentication;
    this.session = session; this.deadlines = deadlines;
    fingerprint = fingerprint(Base64.getDecoder().decode(session.getHostKey().getKey()));
  }
  public static JschConnection connect(String host, PullConfig.Ssh config, String pin) throws IOException {
    return connect(host, config, pin, new JSch(), daemonDeadlines());
  }
  /** Deterministic channel/deadline seam, also exercising authentication before opening channels. */
  public static JschConnection connect(String host, PullConfig.Ssh config, String pin, JSch jsch, Deadlines deadlines) throws IOException {
    if (deadlines == null) deadlines = daemonDeadlines();
    Session session = null;
    try {
      var method = new java.util.concurrent.atomic.AtomicReference<>("none");
      // Retain only the successful method, never JSch diagnostics or authentication material.
      jsch.setInstanceLogger(new com.jcraft.jsch.Logger() {
        public boolean isEnabled(int level) { return level == INFO; }
        public void log(int level, String message) {
          var match = java.util.regex.Pattern.compile("Authentication succeeded \\((password|publickey|keyboard-interactive)\\)\\.").matcher(message);
          if (match.matches()) method.set(match.group(1));
        }
      });
      if (config.key() != null) jsch.addIdentity(config.key().toString());
      jsch.setHostKeyRepository(new Pin(host, pin, config));
      session = jsch.getSession(config.user(), host, config.port());
      session.setConfig("StrictHostKeyChecking", "yes");
      session.setConfig("server_host_key", HOST_KEY_ALGORITHMS);
      session.setConfig("PreferredAuthentications", config.key() == null ? "password,keyboard-interactive" : "publickey");
      if (config.key() == null) session.setPassword(config.password());
      session.setDaemonThread(true);
      session.setServerAliveInterval(TIMEOUT_MS); session.setServerAliveCountMax(3);
      session.connect(TIMEOUT_MS);
      return new JschConnection(session, deadlines, method.get());
    } catch (JSchException | RuntimeException e) {
      if (session != null) session.disconnect();
      deadlines.close();
      throw new IOException("SSH connection to " + host + " failed: " + e.getMessage(), e);
    }
  }
  @Override public String fingerprint() { return fingerprint; }
  @Override public boolean connected() { return session.isConnected(); }
  @Override public Files files() throws IOException {
    ChannelSftp sftp = null;
    try {
      sftp = (ChannelSftp) session.openChannel("sftp"); sftp.connect(TIMEOUT_MS); sftp.setBulkRequests(1);
      return new FileChannel(sftp);
    } catch (JSchException e) {
      if (sftp != null) sftp.disconnect();
      throw new IOException("Cannot open SFTP channel", e);
    }
  }
  private record FileChannel(ChannelSftp sftp) implements Files {
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
    @Override public void close() { sftp.disconnect(); }
  }
  @Override public Optional<String> exec(String command, long timeoutMs, int maxBytes) throws IOException {
    return execBytes(command, timeoutMs, maxBytes).map(bytes -> new String(bytes, StandardCharsets.UTF_8));
  }
  @Override public Optional<byte[]> execBytes(String command, long timeoutMs, int maxBytes) throws IOException {
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
        byte[] output = input.readNBytes(maxBytes + 1);
        if (expired.get()) throw new IOException("SSH command deadline exceeded after " + timeoutMs + " ms");
        if (output.length > maxBytes) throw new IOException("SSH command reply too large");
        return Optional.of(output);
      }
    } catch (JSchException | IOException e) {
      if (expired.get()) throw new IOException("SSH command deadline exceeded after " + timeoutMs + " ms", e);
      if (e instanceof IOException io) throw io;
      return Optional.empty(); // SFTP-only servers use the range comparison.
    } finally { completed.set(true); cancel.run(); if (exec != null) exec.disconnect(); }
  }

  /** One bounded diagnostic command; EOF on stdout alone is not an exit status. */
  public record Reply(int exitStatus, String stdout, String stderr, boolean truncated,
      boolean timedOut, long elapsedNanos) {}

  public Reply inspect(String command, long timeoutMs, int maxBytes) throws IOException {
    if (timeoutMs <= 0 || maxBytes < 1 || maxBytes > 1_048_576) throw new IllegalArgumentException("Invalid diagnostic bounds");
    long start = System.nanoTime();
    var done = new java.util.concurrent.CountDownLatch(1);
    var stdout = new LimitedOutput(maxBytes, () -> {});
    // JSch closes stderr on CHANNEL_CLOSE, after exit-status; stdout may close earlier at EOF.
    var stderr = new LimitedOutput(maxBytes, done::countDown);
    ChannelExec exec = null;
    Runnable cancel = () -> {};
    var expired = new java.util.concurrent.atomic.AtomicBoolean();
    try {
      exec = (ChannelExec) session.openChannel("exec"); exec.setCommand(command); exec.setInputStream(null);
      exec.setOutputStream(stdout); exec.setErrStream(stderr);
      var channel = exec;
      cancel = deadlines.schedule(() -> { expired.set(true); channel.disconnect(); done.countDown(); }, timeoutMs);
      exec.connect(TIMEOUT_MS);
      done.await();
      return new Reply(exec.getExitStatus(), stdout.text(), stderr.text(), stdout.truncated() || stderr.truncated(),
          expired.get(), System.nanoTime() - start);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); throw new IOException("SSH inspection interrupted");
    } catch (JSchException e) { throw new IOException("SSH inspection channel refused", e); }
    finally { cancel.run(); if (exec != null) exec.disconnect(); }
  }
  private static final class LimitedOutput extends java.io.OutputStream {
    private final int limit;
    private final Runnable closed;
    private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    private boolean truncated;
    LimitedOutput(int limit, Runnable closed) { this.limit = limit; this.closed = closed; }
    @Override public synchronized void write(int value) { if (bytes.size() < limit) bytes.write(value); else truncated = true; }
    @Override public synchronized void write(byte[] value, int offset, int length) {
      int keep = Math.min(length, limit - bytes.size()); bytes.write(value, offset, keep);
      if (keep < length) truncated = true;
    }
    synchronized String text() { return bytes.toString(StandardCharsets.UTF_8); }
    synchronized boolean truncated() { return truncated; }
    @Override public void close() { closed.run(); }
  }

  @Override public Command follow(String command) throws IOException {
    ChannelExec exec = null;
    try {
      exec = (ChannelExec) session.openChannel("exec"); exec.setCommand(command); exec.setInputStream(null);
      // A bounded diagnostic stream must never backpressure an otherwise quiet follower.
      exec.setErrStream(OutputSink.INSTANCE);
      InputStream output = exec.getInputStream(); exec.connect(TIMEOUT_MS);
      var channel = exec;
      return new Command() {
        public InputStream output() { return output; }
        public void close() { channel.disconnect(); }
      };
    } catch (JSchException | IOException e) {
      if (exec != null) exec.disconnect();
      throw new IOException("Cannot open SSH follower channel", e);
    }
  }
  private static final class OutputSink extends java.io.OutputStream {
    static final OutputSink INSTANCE = new OutputSink();
    @Override public void write(int value) {}
    @Override public void write(byte[] value, int offset, int length) {}
  }
  @Override public void close() { session.disconnect(); deadlines.close(); }
  public static String fingerprint(byte[] key) {
    try { return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key)); }
    catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
  }
  public static final class Pin implements HostKeyRepository {
    private final String address, expected;
    private final PullConfig.Ssh authentication;
    public Pin(String address, String expected) { this(address, expected, PullConfig.DISABLED.ssh()); }
    public Pin(String address, String expected, PullConfig.Ssh authentication) {
      this.address = address; this.expected = expected; this.authentication = authentication;
    }
    @Override public int check(String host, byte[] key) {
      String observed = fingerprint(key);
      if (expected != null && !expected.equals(observed)) {
        boolean secret = authentication.key() != null || !authentication.password().isEmpty();
        if (secret && !authentication.acceptChangedHostKey()) {
          LoggerFactory.getLogger(JschConnection.class).warn(
              "SSH host key changed at {}: {} -> {}; refused before authentication. Verify the replacement, then set capture.pull.ssh.accept_changed_host_key: true or remove the pinned fingerprint from robot.json", address, expected, observed);
          return CHANGED;
        }
        LoggerFactory.getLogger(JschConnection.class).warn(
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
  public static String sftpLiteral(String value) { return value.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?"); }
}
