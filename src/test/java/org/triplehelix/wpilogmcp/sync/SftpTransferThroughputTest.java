/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.sync;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.pull.SftpTransport;
import org.triplehelix.wpilogmcp.config.PullConfig;
import org.triplehelix.wpilogmcp.harness.FakeRoboRio;

class SftpTransferThroughputTest {
  /** Delay each byte stream independently: sleeping inside the SSH dispatcher models robot CPU, not RTT. */
  static final class DelayedLink implements AutoCloseable {
    private final java.net.ServerSocket listener = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
    private final java.util.concurrent.ScheduledExecutorService outgoing = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> daemon(r));
    private final java.util.concurrent.ScheduledExecutorService incoming = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> daemon(r));
    private volatile java.net.Socket client, server;
    DelayedLink(int port) throws Exception {
      daemon(() -> {
        try {
          client = listener.accept(); client.setTcpNoDelay(true);
          server = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), port); server.setTcpNoDelay(true);
          forward(client, server, outgoing); forward(server, client, incoming);
        } catch (java.io.IOException e) { if (!listener.isClosed()) throw new java.io.UncheckedIOException(e); }
      }).start();
    }
    private static Thread daemon(Runnable action) { var t = new Thread(action, "sftp-latency-fixture"); t.setDaemon(true); return t; }
    private static void forward(java.net.Socket from, java.net.Socket to, java.util.concurrent.ScheduledExecutorService delayed) {
      daemon(() -> {
        try {
          var input = from.getInputStream(); var output = to.getOutputStream(); byte[] block = new byte[65536];
          for (int n; (n = input.read(block)) >= 0;) {
            var bytes = java.util.Arrays.copyOf(block, n);
            delayed.schedule(() -> { try { output.write(bytes); output.flush(); } catch (java.io.IOException ignored) { } }, 15, java.util.concurrent.TimeUnit.MILLISECONDS);
          }
        } catch (java.io.IOException | java.util.concurrent.RejectedExecutionException ignored) { }
      }).start();
    }
    int port() { return listener.getLocalPort(); }
    public void close() throws Exception {
      listener.close(); if (client != null) client.close(); if (server != null) server.close();
      outgoing.shutdownNow(); incoming.shutdownNow();
    }
  }
  @TempDir Path temp;

  @Test void pausingClosesTheStreamWithoutAnotherApplicationBlockAndBoundsReadAhead() throws Exception {
    try (var robot = new FakeRoboRio(temp, "SYNTHETIC-PAUSE", "fixture")) {
      Files.write(robot.logs().resolve("robot.revlog"), new byte[1024 * 1024]);
      var ssh = new PullConfig.Ssh("lvuser", "", null, false, robot.port());
      try (var remote = SftpTransport.connect("127.0.0.1", new PullConfig(true, List.of("/home/lvuser/logs"), 0, 1_000_000, ssh), null)) {
        var local = new FileTransferBatchTest.Batched();
        var gate = new java.util.concurrent.atomic.AtomicBoolean(true);
        var now = new java.util.concurrent.atomic.AtomicLong();
        var transfer = new FileTransfer(remote, local, local.saved, 1_000_000, now::get, gate::get);
        long copied = 0, current = 0;
        for (int block = 0; block < 6; block++) {
          var step = transfer.step(); current = step.bytes();
          assertTrue(current > 0 && current <= 65536); copied += current;
          now.addAndGet(Math.max(1, step.waitUs()));
        }
        assertEquals(1, robot.fileOpens.get()); assertEquals(0, robot.fileCloses.get());
        gate.set(false); assertEquals(FileTransfer.Status.PAUSED, transfer.step().status());
        assertEquals(1, robot.fileCloses.get()); assertEquals(copied, local.saved.files().get(0).bytesCopied());
        long received = robot.reads().stream().mapToLong(FakeRoboRio.Read::bytes).sum();
        assertTrue(received <= copied - current + 65536, "at most 64 KiB total for the current and in-flight packet: " + received);
        for (int i = 0; i < 5; i++) transfer.step();
        assertEquals(received, robot.reads().stream().mapToLong(FakeRoboRio.Read::bytes).sum());
      }
    }
  }

  @Test void thirtyMillisecondSftpRequestsLeaveTheConfiguredRateInControl() throws Exception {
    byte[] content = FileTransferTest.bytes(128 * 65536, 94);
    try (var robot = new FakeRoboRio(temp, "SYNTHETIC-BATCH", "fixture");
        var network = new DelayedLink(robot.port())) {
      Files.write(robot.logs().resolve("robot.revlog"), content);
      var ssh = new PullConfig.Ssh("lvuser", "", null, false, network.port());
      var config = new PullConfig(true, List.of("/home/lvuser/logs"), 0, 1_000_000, ssh);
      try (var remote = SftpTransport.connect("127.0.0.1", config, null)) {
        var local = new FileTransferBatchTest.Batched() { public int checkpointBytes() { return 1024 * 1024; } };
        long start = System.nanoTime();
        var transfer = new FileTransfer(remote, local, local.saved, config.rateBytes(), () -> (System.nanoTime() - start) / 1000, () -> true);
        long copied = 0;
        for (int steps = 0; copied < content.length && steps < 400; steps++) {
          var result = transfer.step(); copied += result.bytes();
          if (result.waitUs() > 0) LockSupport.parkNanos(result.waitUs() * 1000);
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        double rate = copied / seconds;
        System.out.printf("S6 real SFTP injected network RTT=30ms copied=%d seconds=%.3f bytes_per_sec=%.1f file_opens=%d file_closes=%d appends=%d checkpoints=%d%n",
            copied, seconds, rate, robot.fileOpens.get(), robot.fileCloses.get(), local.appends, local.checkpoints);
        System.out.println("S6 wire reads=" + robot.reads().size() + " bytes=" + robot.reads().stream().mapToLong(FakeRoboRio.Read::bytes).sum());
        assertAll(() -> assertEquals(content.length, local.saved.files().get(0).bytesCopied()),
            () -> assertArrayEquals(content, local.local.get(local.saved.files().get(0).localName())),
            () -> assertEquals(content.length, robot.reads().stream().mapToLong(FakeRoboRio.Read::bytes).sum(), "wire payload is paced too"),
            () -> assertEquals(1, robot.fileOpens.get(), "one SFTP handle for the file"),
            () -> assertEquals(1, robot.fileCloses.get(), "the handle closes when the listed end is reached"),
            () -> assertTrue(rate >= 850_000, "1 MB/s cap should dominate at 30 ms latency: " + rate),
            () -> assertTrue(rate <= 1_020_000, "rate cap including scheduling tolerance: " + rate));
      }
    }
  }
}
