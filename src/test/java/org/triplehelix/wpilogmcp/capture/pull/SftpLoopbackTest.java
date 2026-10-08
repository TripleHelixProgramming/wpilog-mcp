/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import static org.junit.jupiter.api.Assertions.*;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.config.PullConfig;
import org.triplehelix.wpilogmcp.harness.FakeRoboRio;

/** Real SSH framing on both CI operating systems; slow-channel unit checks remain independent. */
class SftpLoopbackTest {
  @TempDir Path temp;
  private static PullConfig config(int port, String password, boolean accept) {
    return new PullConfig(true, PullConfig.DISABLED.directories(), 0, 1_000_000,
        new PullConfig.Ssh("lvuser", password, null, accept, port));
  }
  static final class Connection extends JSch {
    Session session;
    @Override public Session getSession(String user, String host, int port) throws com.jcraft.jsch.JSchException {
      return session = super.getSession(user, host, port);
    }
  }
  static final class Deadlines implements org.triplehelix.wpilogmcp.ssh.JschConnection.Deadlines {
    volatile Runnable pending;
    volatile long delay;
    volatile boolean cancelled;
    public Runnable schedule(Runnable action, long delayMs) { delay = delayMs; pending = action; return () -> cancelled = true; }
    public void close() {}
  }

  private static SftpTransport connected(FakeRoboRio rio, Connection jsch, Deadlines deadlines) throws IOException {
    var settings = config(rio.port(), "", false);
    return SftpTransport.using(org.triplehelix.wpilogmcp.ssh.JschConnection.connect("127.0.0.1", settings.ssh(), null, jsch, deadlines),
        settings.directories(), "127.0.0.1", true);
  }

  @Test void deviceFilesSftpOffsetsAndExactHashUseRealEd25519Ssh() throws Exception {
    try (var rio = new FakeRoboRio(temp.resolve("rio"), "HARNESS-01", "synthetic controller")) {
      var file = rio.logs().resolve("a ' quoted.wpilog"); byte[] bytes = new byte[70009];
      for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 13);
      Files.write(file, bytes);
      try (var remote = SftpTransport.connect("127.0.0.1", config(rio.port(), "", false), null)) {
        var identity = remote.identity();
        assertEquals("HARNESS-01", identity.serialNumber()); assertEquals("synthetic controller", identity.comments());
        assertTrue(identity.hostKeyFingerprint().startsWith("SHA256:"));
        var listing = remote.list(); assertEquals(1, listing.size()); assertEquals(bytes.length, listing.get(0).size());
        String name = "/home/lvuser/logs/a ' quoted.wpilog"; assertEquals(name, listing.get(0).name());
        assertArrayEquals(java.util.Arrays.copyOfRange(bytes, 65531, 65551), remote.read(name, 65531, 20));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(java.util.Arrays.copyOf(bytes, 65539))),
            remote.prefixHash(name, 65539).orElseThrow());
        assertFalse(Files.exists(temp.resolve("rio/u/logs"))); assertEquals(1, rio.commands.get());
      }
    }
  }

  @Test void keepalivesWorkWhileARealExecChannelIsSilentAndDeadlineClosesOnlyThatChannel() throws Exception {
    try (var rio = new FakeRoboRio(temp.resolve("slow"), "HARNESS-02", "")) {
      Files.write(rio.logs().resolve("slow.wpilog"), new byte[] {1, 2, 3});
      var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var alive = new CountDownLatch(2);
      rio.beforeHash = h -> { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); };
      rio.onKeepalive = n -> alive.countDown();
      var jsch = new Connection(); var deadlines = new Deadlines();
      try (var remote = connected(rio, jsch, deadlines)) {
        assertEquals("ssh-ed25519", jsch.session.getHostKey().getType());
        assertEquals(5000, jsch.session.getServerAliveInterval()); assertEquals(3, jsch.session.getServerAliveCountMax());
        // Accelerate actual socket keepalives, not the production command deadline. No clock sleeps.
        jsch.session.setServerAliveInterval(50);
        var result = CompletableFuture.supplyAsync(() -> {
          try { return remote.prefixHash("/home/lvuser/logs/slow.wpilog", 3); }
          catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(alive.await(5, TimeUnit.SECONDS));
        assertFalse(result.isDone()); release.countDown();
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", result.get(5, TimeUnit.SECONDS).orElseThrow());
        assertTrue(deadlines.cancelled); assertEquals(31000, deadlines.delay);
        var second = new CountDownLatch(1); var never = new CountDownLatch(1);
        rio.beforeHash = h -> { second.countDown(); never.await(); };
        var expired = CompletableFuture.runAsync(() -> {
          var error = assertThrows(IOException.class, () -> remote.prefixHash("/home/lvuser/logs/slow.wpilog", 3));
          assertTrue(error.getMessage().contains("deadline"));
        });
        assertTrue(second.await(5, TimeUnit.SECONDS)); deadlines.pending.run(); expired.get(5, TimeUnit.SECONDS);
        assertEquals(1, remote.list().size()); assertTrue(jsch.session.isConnected());
      } finally { release.countDown(); }
    }
  }

  @Test void changedKeysRefuseBeforePasswordAuthenticationAndEmptyPasswordCanContinue() throws Exception {
    String pin;
    try (var first = new FakeRoboRio(temp.resolve("first"), "SAME-SERIAL", "")) {
      try (var remote = SftpTransport.connect("127.0.0.1", config(first.port(), "", false), null)) { pin = remote.identity().hostKeyFingerprint(); }
    }
    try (var second = new FakeRoboRio(temp.resolve("second"), "SAME-SERIAL", "")) {
      assertThrows(IOException.class, () -> SftpTransport.connect("127.0.0.1", config(second.port(), "synthetic-secret", false), pin));
      assertEquals(0, second.authentications.get(), "No secret reaches the changed server");
      try (var remote = SftpTransport.connect("127.0.0.1", config(second.port(), "", false), pin)) {
        assertEquals("SAME-SERIAL", remote.identity().serialNumber()); assertNotEquals(pin, remote.identity().hostKeyFingerprint());
      }
      int before = second.authentications.get();
      // Explicit acceptance reaches authentication; this fixture deliberately rejects nonempty passwords.
      assertThrows(IOException.class, () -> SftpTransport.connect("127.0.0.1", config(second.port(), "synthetic-secret", true), pin));
      assertTrue(second.authentications.get() > before);
    }
  }

  @Test void execGrammarRejectsAllOtherCommands() throws Exception {
    assertEquals(new FakeRoboRio.Hash("/a'b", 2), FakeRoboRio.parseHash("head -c 2 -- '/a'\"'\"'b' | sha256sum"));
    for (String command : List.of("id", "head -c 2 -- '/a'; id | sha256sum", "head -c -1 -- '/a' | sha256sum",
        "head -c 99999999999999999999 -- '/a' | sha256sum", "head -c 1 -- 'relative' | sha256sum")) {
      assertThrows(IOException.class, () -> FakeRoboRio.parseHash(command), command);
    }
  }

  @Test void execRefusesCommandsAndPathsOutsideTheSyntheticDeviceOverTheWire() throws Exception {
    Files.write(temp.resolve("outside"), new byte[] {42});
    try (var rio = new FakeRoboRio(temp.resolve("jail"), "HARNESS-JAIL", "")) {
      Files.write(rio.logs().resolve("short.wpilog"), new byte[] {1, 2});
      var jsch = new Connection();
      try (var remote = connected(rio, jsch, null)) {
        for (String command : List.of("id", "head -c 1 -- '/../outside' | sha256sum",
            "head -c 3 -- '/home/lvuser/logs/short.wpilog' | sha256sum")) {
          var channel = (com.jcraft.jsch.ChannelExec) jsch.session.openChannel("exec");
          try {
            channel.setCommand(command);
            var errors = new java.io.ByteArrayOutputStream(); channel.setErrStream(errors);
            var input = channel.getInputStream(); channel.connect(5000);
            assertEquals(0, input.readNBytes(4097).length, command);
            assertFalse(errors.toString(java.nio.charset.StandardCharsets.UTF_8).isEmpty(), command);
          } finally { channel.disconnect(); }
        }
        assertEquals(3, rio.refusedCommands.get());
        assertEquals(1, remote.list().size());
        assertArrayEquals(new byte[] {42}, Files.readAllBytes(temp.resolve("outside")));
      }
    }
  }
}
