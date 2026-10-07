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
  static final class Fake implements SftpTransport.Channel {
    final Map<String, List<SftpTransport.Item>> directories = new HashMap<>();
    final Map<String, byte[]> files = new HashMap<>();
    final List<String> reads = new ArrayList<>();
    String command; Optional<String> reply = Optional.empty(); boolean closed;
    @Override public List<SftpTransport.Item> list(String directory) { return directories.getOrDefault(directory, List.of()); }
    @Override public byte[] read(String path, long offset, int count) throws IOException {
      reads.add(path + ":" + offset + ":" + count);
      var data = files.get(path); if (data == null) throw new IOException("unreadable");
      return java.util.Arrays.copyOfRange(data, (int) offset, (int) Math.min(data.length, offset + count));
    }
    @Override public Optional<String> exec(String value) { command = value; return reply; }
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
    assertEquals("/u/a\\*b\\?c\\\\d", SftpTransport.sftpLiteral("/u/a*b?c\\d"));
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
      assertEquals(0, new SftpTransport.Pin("127.0.0.1", null).check("127.0.0.1", new byte[0]));
      assertEquals(0, bytes.size());
      assertEquals(0, new SftpTransport.Pin("127.0.0.1", "SHA256:old").check("127.0.0.1", new byte[0]));
      assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("SSH host key changed"));
      assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("SHA256:old"));
    } finally { System.setErr(original); }
    assertEquals("ssh-ed25519,rsa-sha2-512,rsa-sha2-256", SftpTransport.HOST_KEY_ALGORITHMS);
  }
}
