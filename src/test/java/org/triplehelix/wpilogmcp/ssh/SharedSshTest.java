/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.ssh;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.pull.SftpTransport;
import org.triplehelix.wpilogmcp.config.PullConfig;
import org.triplehelix.wpilogmcp.harness.FakeRoboRio;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;

class SharedSshTest {
  @TempDir Path temp;

  @Test void sftpSamplesAndLongLivedFollowsShareOneAuthenticationAndCloseTheirOwnChannel() throws Exception {
    try (var rio = new FakeRoboRio(temp, "SYNTHETIC-SHARED", "")) {
      Files.write(rio.logs().resolve("fixture.wpilog"), new byte[] {1, 2, 3});
      rio.script("sample", out -> out.write("known sample\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      var following = new CountDownLatch(1); var release = new CountDownLatch(1);
      rio.script("follow", out -> { out.write("first line\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.flush(); following.countDown(); release.await(); });
      var loop = new ManualScheduler();
      var config = new PullConfig.Ssh("lvuser", "", null, false, rio.port());
      var pool = new SharedSsh(host -> CompletableFuture.completedFuture(null), JschConnection::connect, host -> loop);
      try {
        var host = pool.host("127.0.0.1", config);
        assertSame(host, pool.host("127.0.0.1", config));
        loop.drain(); assertEquals(0, rio.authentications.get());
        host.required(true); loop.advance(250_000);
        var ssh = host.connection(); assertNotNull(ssh);
        try (var tail = ssh.follow("follow")) {
          assertTrue(following.await(5, TimeUnit.SECONDS));
          assertEquals("first line\n", new String(tail.output().readNBytes(11), java.nio.charset.StandardCharsets.UTF_8));
          try (var files = SftpTransport.using(ssh, List.of("/home/lvuser/logs"), "127.0.0.1", false)) {
            assertEquals(1, files.list().size());
            assertArrayEquals(new byte[] {2, 3}, files.read("/home/lvuser/logs/fixture.wpilog", 1, 2));
            assertEquals("known sample\n", ssh.exec("sample", 1000, 4096).orElseThrow());
          }
          assertTrue(ssh.connected(), "A closed pull channel must leave stats and tails connected");
          assertEquals("known sample\n", ssh.exec("sample", 1000, 4096).orElseThrow());
        } finally { release.countDown(); }
        assertEquals(1, rio.authentications.get()); assertTrue(ssh.connected());
        host.required(false); assertNull(host.connection()); loop.advance(250_000);
        assertFalse(ssh.connected()); assertEquals("offline", host.state().state());
        host.required(true); loop.advance(250_000); assertNotNull(host.connection());
        assertEquals(2, rio.authentications.get());
      } finally { var closing = pool.closeAsync(); loop.advance(250_000); assertTrue(closing.isDone()); }
    }
  }

  @Test void reconnectBackoffIsBoundedAndDoesNothingWithoutNt4OrWhilePinLookupIsPending() {
    var loop = new ManualScheduler(); var attempts = new ArrayList<Long>();
    var pin = new CompletableFuture<String>();
    var pool = new SharedSsh(host -> pin, (host, config, fingerprint) -> {
      attempts.add(loop.nowUs()); throw new IOException("scripted offline");
    }, host -> loop);
    var host = pool.host("synthetic", PullConfig.DISABLED.ssh());
    loop.drain(); loop.advance(60_000_000); assertTrue(attempts.isEmpty());
    host.required(true); loop.advance(250_000); assertTrue(attempts.isEmpty());
    pin.complete("SHA256:synthetic"); loop.advance(250_000);
    long first = loop.nowUs();
    long expectedAt = first;
    for (long delay : new long[] {1, 2, 4, 8, 16, 30, 30}) {
      int count = attempts.size();
      loop.advance(delay * 1_000_000 - 250_000); assertEquals(count, attempts.size());
      loop.advance(250_000); expectedAt += delay * 1_000_000;
      assertEquals(count + 1, attempts.size());
      assertEquals(expectedAt, attempts.get(count));
    }
    assertEquals(8, attempts.size());
    assertEquals(first, attempts.get(0));
    host.required(false); loop.advance(60_000_000); assertEquals(8, attempts.size());
    assertThrows(IllegalArgumentException.class, () -> pool.host("synthetic", new PullConfig.Ssh("different", "", null)));
    var closing = pool.closeAsync(); loop.advance(250_000); assertTrue(closing.isDone());
  }
}
