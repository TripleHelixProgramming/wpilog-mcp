/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SftpTransportTest {
  static SftpTransport connect(String host, org.triplehelix.wpilogmcp.config.PullConfig config, String pin,
      com.jcraft.jsch.SlowSsh ssh, Timers deadlines) throws IOException {
    return SftpTransport.using(org.triplehelix.wpilogmcp.ssh.JschConnection.connect(host, config.ssh(), pin, ssh, deadlines),
        config.directories(), host, true);
  }

  static final class Fake implements SftpTransport.Channel {
    final Map<String, List<SftpTransport.Item>> directories = new HashMap<>();
    final Map<String, byte[]> files = new HashMap<>();
    final List<String> reads = new ArrayList<>(), walked = new ArrayList<>();
    final Map<String, String> canonical = new HashMap<>();
    String command; Optional<String> reply = Optional.empty(); boolean closed, hashing;
    @Override public Optional<String> realpath(String directory) { return Optional.of(canonical.getOrDefault(directory, directory)); }
    @Override public List<SftpTransport.Item> list(String directory) { walked.add(directory); return directories.getOrDefault(directory, List.of()); }
    @Override public byte[] read(String path, long offset, int count) throws IOException {
      reads.add(path + ":" + offset + ":" + count);
      var data = files.get(path); if (data == null) throw new IOException("unreadable");
      return java.util.Arrays.copyOfRange(data, (int) offset, (int) Math.min(data.length, offset + count));
    }
    @Override public Optional<String> exec(String value, long timeoutMs) {
      command = value;
      if (!hashing) return reply;
      try {
        var fields = value.split(" "); int length = Integer.parseInt(fields[2]);
        String path = fields[4].substring(1, fields[4].length() - 1);
        return Optional.of(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(java.util.Arrays.copyOf(files.get(path), length))) + "  -");
      } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    @Override public void close() { closed = true; }
  }
  static SftpTransport.Item file(String name) { return new SftpTransport.Item(name, 123, 456_000, false, true, false); }
  static SftpTransport.Item dir(String name) { return new SftpTransport.Item(name, 0, 0, true, false, false); }

  @Test void listingRecursesWithoutLinksOrNonLogsAndUsesLiteralOffsetReads() throws Exception {
    var fake = new Fake();
    fake.directories.put("/u/logs", List.of(file("b.REVLOG"), file("notes.txt"), dir("sub"), dir("."), dir(".."),
        new SftpTransport.Item("link", 0, 0, true, false, true)));
    fake.directories.put("/u/logs/sub", List.of(file("a.wpilog")));
    fake.directories.put("/u/logs/link", List.of(file("must-not-follow.wpilog")));
    fake.files.put("/u/logs/sub/a.wpilog", new byte[] {0, 1, 2, 3, 4, 5});
    try (var remote = new SftpTransport(fake, List.of("/missing", "/u/logs", "/u/logs"), "127.0.0.1", "key")) {
      var files = remote.list();
      assertEquals(List.of("/u/logs/b.REVLOG", "/u/logs/sub/a.wpilog"), files.stream().map(f -> f.name()).toList());
      assertEquals(123, files.get(0).size()); assertEquals(456_000, files.get(0).mtimeMillis());
      assertArrayEquals(new byte[] {2, 3, 4}, remote.read(files.get(1).name(), 2, 3));
      assertEquals(List.of("/u/logs/sub/a.wpilog:2:3"), fake.reads);
      assertThrows(IOException.class, () -> remote.read("/u/logs/sub/a.wpilog", -1, 1));
      assertThrows(IOException.class, () -> remote.read("/u/logs/sub/a.wpilog", 0, 65537));
      assertThrows(IOException.class, () -> remote.read("/u/logs/sub/a.wpilog", 0, -1));
      assertThrows(IOException.class, () -> remote.prefixHash("x", -1));
      assertEquals(1, fake.reads.size(), "Invalid ranges must not reach SFTP");
      fake.directories.put("/u/logs", List.of(file("../escape.wpilog")));
      assertThrows(IOException.class, remote::list);
    }
    assertTrue(fake.closed);
    assertEquals("/u/a\\*b\\?c\\\\d", org.triplehelix.wpilogmcp.ssh.JschConnection.sftpLiteral("/u/a*b?c\\d"));
  }

  @Test void rootAliasesAreWalkedOnlyOnce() throws Exception {
    var fake = new Fake();
    for (String root : List.of("/u/logs", "/U/logs")) {
      fake.canonical.put(root, "/media/usb/logs");
      fake.directories.put(root, List.of(file("a.wpilog"), dir("session")));
      fake.directories.put(root + "/session", List.of(file("b.revlog")));
    }
    try (var remote = new SftpTransport(fake, List.of("/u/logs", "/U/logs"), "synthetic", "key")) {
      assertEquals(List.of("/u/logs/a.wpilog", "/u/logs/session/b.revlog"), remote.list().stream().map(f -> f.name()).toList());
      assertEquals(List.of("/u/logs", "/u/logs/session"), fake.walked);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void anExistingUppercaseManifestKeepsItsVerifiedFileAndPartialBytes(boolean hashing) throws Exception {
    var fake = new Fake(); fake.hashing = hashing; byte[] partial = new byte[100_000]; new java.util.Random(5).nextBytes(partial);
    byte[] complete = new byte[] {1, 2, 3, 4};
    for (String root : List.of("/u/logs", "/U/logs")) {
      fake.canonical.put(root, "/media/usb/logs");
      fake.directories.put(root, List.of(new SftpTransport.Item("a.wpilog", complete.length, 1000, false, true, false),
          new SftpTransport.Item("b.revlog", partial.length, 1000, false, true, false)));
      fake.files.put(root + "/a.wpilog", complete); fake.files.put(root + "/b.revlog", partial);
    }
    var local = new AliasLocal(); local.data.put("complete", complete); local.data.put("partial", java.util.Arrays.copyOf(partial, 65536));
    local.saved = new org.triplehelix.wpilogmcp.sync.PullManifest(1, "SYNTHETIC", List.of(
        new org.triplehelix.wpilogmcp.sync.PullManifest.Entry("/U/logs/a.wpilog", 4, 1000, 4, true, "complete", 0, null),
        new org.triplehelix.wpilogmcp.sync.PullManifest.Entry("/U/logs/b.revlog", 100000, 1000, 65536, false, "partial", 0, null)), List.of());
    var clock = new java.util.concurrent.atomic.AtomicLong();
    try (var remote = new SftpTransport(fake, List.of("/u/logs", "/U/logs"), "synthetic", "key")) {
      var transfer = new org.triplehelix.wpilogmcp.sync.FileTransfer(remote, local, local.saved, 1_000_000, clock::get, () -> true);
      for (int n = 0; n < 40; n++) {
        var result = transfer.step(); clock.addAndGet(Math.max(1, result.waitUs()));
        if (result.status() == org.triplehelix.wpilogmcp.sync.FileTransfer.Status.IDLE) break;
      }
    }
    assertEquals(2, local.saved.files().size(), "Aliases must not add a second pair of transfers");
    assertEquals(100000 - 65536, local.appended, "Only bytes after the existing checkpoint may be copied");
    assertEquals(0, local.created); assertTrue(local.saved.history().isEmpty());
    assertTrue(local.saved.files().stream().allMatch(org.triplehelix.wpilogmcp.sync.PullManifest.Entry::verified));
    assertArrayEquals(complete, local.data.get("complete")); assertArrayEquals(partial, local.data.get("partial"));
  }

  private static final class AliasLocal implements org.triplehelix.wpilogmcp.sync.FileTransfer.Local {
    final Map<String, byte[]> data = new HashMap<>();
    org.triplehelix.wpilogmcp.sync.PullManifest saved; int appended, created;
    public String create(String remote) { String name = "new-" + ++created; data.put(name, new byte[0]); return name; }
    public long size(String name) { return data.get(name).length; }
    public byte[] read(String name, long offset, int count) { return java.util.Arrays.copyOfRange(data.get(name), (int) offset, (int) offset + count); }
    public String prefixHash(String name, long length) {
      try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
          .digest(java.util.Arrays.copyOf(data.get(name), (int) length))); }
      catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public void append(String name, long offset, byte[] bytes) {
      assertEquals(data.get(name).length, offset); var grown = java.util.Arrays.copyOf(data.get(name), (int) offset + bytes.length);
      System.arraycopy(bytes, 0, grown, (int) offset, bytes.length); data.put(name, grown); appended += bytes.length;
    }
    public String rename(String name, String remote) { return name; }
    public String archive(String name) { throw new AssertionError("Existing alias content must be preserved"); }
    public void verify(String name) {}
    public String verified(org.triplehelix.wpilogmcp.sync.PullManifest.Entry entry) { return entry.localName(); }
    public void save(org.triplehelix.wpilogmcp.sync.PullManifest value) { saved = value; }
  }

  @Test void realpathSkipsAbsentRootsButPreservesPermissionErrors() throws Exception {
    var ssh = new com.jcraft.jsch.SlowSsh();
    try (var connection = org.triplehelix.wpilogmcp.ssh.JschConnection.connect("synthetic",
        org.triplehelix.wpilogmcp.config.PullConfig.DISABLED.ssh(), null, ssh, new Timers());
        var files = connection.files()) {
      ssh.realpathError = com.jcraft.jsch.ChannelSftp.SSH_FX_NO_SUCH_FILE;
      assertTrue(files.realpath("/missing").isEmpty());
      ssh.realpathError = com.jcraft.jsch.ChannelSftp.SSH_FX_PERMISSION_DENIED;
      var error = assertThrows(IOException.class, () -> files.realpath("/denied"));
      assertTrue(error.getMessage().contains("SFTP realpath failed: /denied"));
    }
  }

  @Test void hashCommandQuotesEveryPathAndRefusesUnavailableOrMalformedAnswers() throws Exception {
    var fake = new Fake(); var remote = new SftpTransport(fake, List.of(), "127.0.0.1", "key");
    assertTrue(remote.prefixHash("/u/a'$(touch x).wpilog", 70001).isEmpty());
    assertEquals("head -c 70001 -- '/u/a'\"'\"'$(touch x).wpilog' | sha256sum", fake.command);
    fake.reply = Optional.of("E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855  -\n");
    assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", remote.prefixHash("x", 0).orElseThrow());
    fake.reply = Optional.of("permission denied"); assertTrue(remote.prefixHash("x", 1).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> SftpTransport.shellQuote("a\0b"));
  }

  static final class Timers implements org.triplehelix.wpilogmcp.ssh.JschConnection.Deadlines {
    long delay; Runnable pending; boolean cancelled, closed;
    public Runnable schedule(Runnable task, long delayMs) { delay = delayMs; pending = task; return () -> cancelled = true; }
    void advance(long elapsedMs) { if (pending != null && elapsedMs >= delay && !cancelled) pending.run(); }
    public void close() { closed = true; }
  }
  @Test void slowHashKeepsItsConnectionAndHasALengthScaledDeadline() throws Exception {
    var ssh = new com.jcraft.jsch.SlowSsh(); var timers = new Timers();
    ssh.elapsedMs = 15_000; ssh.onRead = () -> timers.advance(ssh.elapsedMs);
    try (var remote = connect("127.0.0.1", org.triplehelix.wpilogmcp.config.PullConfig.DISABLED, null, ssh, timers)) {
      assertEquals("0".repeat(64), remote.prefixHash("/u/large.wpilog", 300L * 1024 * 1024).orElseThrow());
      assertFalse(ssh.disconnected); assertEquals(5000, ssh.session.getServerAliveInterval());
      assertEquals(3, ssh.session.getServerAliveCountMax()); assertEquals(5000, ssh.connectTimeout); assertEquals(5000, ssh.channelTimeout);
      assertEquals(1_230_000, timers.delay); assertTrue(timers.cancelled); assertTrue(ssh.commandClosed);
    }
    assertTrue(timers.closed); assertTrue(ssh.disconnected);
  }
  @Test void stalledHashDeadlineClosesItsChannelAndRejectsPartialOrOversizedOutput() throws Exception {
    for (long length : List.of(0L, 262_145L)) {
      var ssh = new com.jcraft.jsch.SlowSsh(); var timers = new Timers();
      ssh.endOnClose = length == 0;
      ssh.onRead = () -> {
        timers.advance(length == 0 ? 30_000 : 32_000);
        assertTrue(ssh.commandClosed, "the deadline itself must interrupt a blocked read");
      };
      try (var remote = connect("127.0.0.1", org.triplehelix.wpilogmcp.config.PullConfig.DISABLED, null, ssh, timers)) {
        var error = assertThrows(IOException.class, () -> remote.prefixHash("slow", length));
        assertTrue(error.getMessage().contains("deadline")); assertTrue(ssh.commandClosed); assertTrue(timers.cancelled);
        assertEquals(length == 0 ? 30_000 : 32_000, timers.delay);
      }
    }
    var ssh = new com.jcraft.jsch.SlowSsh(); var timers = new Timers(); ssh.reply = "x".repeat(4097);
    try (var remote = connect("127.0.0.1", org.triplehelix.wpilogmcp.config.PullConfig.DISABLED, null, ssh, timers)) {
      assertTrue(assertThrows(IOException.class, () -> remote.prefixHash("large", 1)).getMessage().contains("too large"));
      assertTrue(ssh.commandClosed); assertTrue(timers.cancelled);
    }
  }

  @Test void changedKeyRefusesSecretAuthenticationUnlessExplicitlyAccepted() throws Exception {
    var original = System.err; var bytes = new java.io.ByteArrayOutputStream();
    try (var stream = new java.io.PrintStream(bytes)) {
      System.setErr(stream);
      for (var secret : List.of(new org.triplehelix.wpilogmcp.config.PullConfig.Ssh("lvuser", "synthetic-secret", null),
          new org.triplehelix.wpilogmcp.config.PullConfig.Ssh("lvuser", "", java.nio.file.Path.of("synthetic-key")))) {
        var config = new org.triplehelix.wpilogmcp.config.PullConfig(true, List.of(), 0, 1_000_000, secret);
        var refused = new com.jcraft.jsch.SlowSsh();
        assertThrows(IOException.class, () -> connect("127.0.0.1", config, "SHA256:old", refused, new Timers()));
        assertFalse(refused.authenticated, "refuse before offering a password or signature"); assertTrue(refused.disconnected);
        var accepted = new org.triplehelix.wpilogmcp.config.PullConfig(true, List.of(), 0, 1_000_000,
            new org.triplehelix.wpilogmcp.config.PullConfig.Ssh(secret.user(), secret.password(), secret.key(), true));
        var ssh = new com.jcraft.jsch.SlowSsh();
        try (var remote = connect("127.0.0.1", accepted, "SHA256:old", ssh, new Timers())) { assertTrue(ssh.authenticated); }
        ssh = new com.jcraft.jsch.SlowSsh();
        try (var remote = connect("127.0.0.1", config, SftpTransport.fingerprint(ssh.hostKey), ssh, new Timers())) { assertTrue(ssh.authenticated); }
        ssh = new com.jcraft.jsch.SlowSsh();
        try (var remote = connect("127.0.0.1", config, null, ssh, new Timers())) { assertTrue(ssh.authenticated); }
      }
      var ssh = new com.jcraft.jsch.SlowSsh();
      try (var remote = connect("127.0.0.1", org.triplehelix.wpilogmcp.config.PullConfig.DISABLED, "SHA256:old", ssh, new Timers())) { assertTrue(ssh.authenticated); }
    } finally { System.setErr(original); }
    String log = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(log.contains("capture.pull.ssh.accept_changed_host_key")); assertTrue(log.contains("robot.json"));
    assertFalse(log.contains("synthetic-secret"));
  }

  @Test void deviceReadingUsesHalSourcesAndReportsChangedPinnedKeys() throws Exception {
    var fake = new Fake(); fake.directories.put("/proc", List.of(dir("42"), dir("self"), file("43")));
    fake.files.put("/proc/42/environ", "x=y\0serialnum=SYNTHETIC-A\0".getBytes(StandardCharsets.UTF_8));
    fake.files.put("/etc/machine-info", "PRETTY_HOSTNAME=\"fixture robot\"\n".getBytes(StandardCharsets.UTF_8));
    var identity = new SftpTransport(fake, List.of(), "127.0.0.1", "SHA256:fixture").identity();
    assertEquals("SYNTHETIC-A", identity.serialNumber()); assertEquals("fixture robot", identity.comments());
    assertEquals("127.0.0.1", identity.address()); assertEquals("SHA256:fixture", identity.hostKeyFingerprint());
    assertEquals(List.of("/proc/42/environ:0:1048576", "/etc/machine-info:0:1048576"), fake.reads);
    assertEquals("SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU", SftpTransport.fingerprint(new byte[0]));
    var original = System.err; var bytes = new java.io.ByteArrayOutputStream();
    try (var stream = new java.io.PrintStream(bytes)) {
      System.setErr(stream);
      assertEquals(0, new org.triplehelix.wpilogmcp.ssh.JschConnection.Pin("127.0.0.1", null).check("127.0.0.1", new byte[0]));
      assertEquals(0, bytes.size());
      assertEquals(0, new org.triplehelix.wpilogmcp.ssh.JschConnection.Pin("127.0.0.1", "SHA256:old").check("127.0.0.1", new byte[0]));
      assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("SSH host key changed"));
      assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("SHA256:old"));
    } finally { System.setErr(original); }
    assertEquals("ssh-ed25519,rsa-sha2-512,rsa-sha2-256", org.triplehelix.wpilogmcp.ssh.JschConnection.HOST_KEY_ALGORITHMS);
  }
}
