/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.config.ProviderConfig;
import org.triplehelix.wpilogmcp.config.PullConfig;
import org.triplehelix.wpilogmcp.harness.FakeRoboRio;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.ssh.JschConnection;
import org.triplehelix.wpilogmcp.ssh.SshConnection;

class TailProviderTest {
  @TempDir Path temp;
  @Test void burstsAreCappedAndReportedWhenQuietAndPreSessionHistoryIsBounded() {
    var buffer = new TailBuffer();
    for (int n = 0; n < 275; n++) buffer.line("line-" + n, 123_000, true);
    assertEquals(200, buffer.size()); assertEquals(75, buffer.dropped());
    var lines = buffer.drain(1_123_000, 1000);
    assertEquals(201, lines.size()); assertEquals("line-0", lines.get(0).text());
    assertEquals("line-199", lines.get(199).text());
    assertEquals("[wpilog-mcp dropped 75 tail lines: rate or line-size limit]", lines.get(200).text());
    assertEquals(123_000, lines.get(0).receivedUs());
    assertEquals(0, lines.get(0).timestampUs(-500_000)); assertEquals(223_000, lines.get(0).timestampUs(100_000)); assertTrue(lines.stream().allMatch(TailBuffer.Line::buffered));
    assertTrue(buffer.drain(2_000_000, 1000).isEmpty(), "One notice, not one each tick");
    for (int n = 0; n < 1300; n++) buffer.line("held-" + n, 3_000_000L + n * 10_000L, true);
    assertEquals(1000, buffer.size()); assertEquals(375, buffer.dropped());
    lines = buffer.drain(20_000_000, 1001);
    assertEquals(1001, lines.size()); assertEquals("[wpilog-mcp dropped 300 tail lines: buffer limit]", lines.get(0).text());
    assertEquals("held-300", lines.get(1).text()); assertEquals("held-1299", lines.get(1000).text());
    assertTrue(buffer.buffered());
  }

  @Test void aSustainedBurstProducesOneNoticeOnlyAfterADropFreeSecond() {
    var buffer = new TailBuffer(); var notices = new ArrayList<String>();
    for (int second = 0; second < 3; second++) {
      for (int n = 0; n < 500; n++) {
        long time = second * 1_000_000L + n * 2000L;
        buffer.line("burst", time, false);
        buffer.drain(time, 1000).stream().filter(line -> line.text().startsWith("[wpilog-mcp"))
            .forEach(line -> notices.add(line.text()));
      }
    }
    assertTrue(notices.isEmpty(), "The burst has not ended merely because a new rate bucket began");
    assertEquals(900, buffer.dropped());
    assertTrue(buffer.drain(3_997_999, 1000).isEmpty());
    assertEquals(List.of("[wpilog-mcp dropped 900 tail lines: rate or line-size limit]"),
        buffer.drain(3_998_000, 1000).stream().map(TailBuffer.Line::text).toList());
    assertTrue(buffer.drain(6_000_000, 1000).isEmpty());
  }

  @Test void realExecFollowsScriptedAppendsAndRotationAndResumesAfterSshDrop() throws Exception {
    var clock = new AtomicLong(100); var worker = new ManualScheduler();
    var current = new AtomicReference<SshConnection>();
    var writes = new LinkedBlockingQueue<String>(); var ended = new CountDownLatch(1);
    var settings = new ProviderConfig.Tail(null, PullConfig.DISABLED.ssh(), "/home/lvuser/console", "program_console");
    try (var rio = new FakeRoboRio(temp, "TAIL-FIXTURE", "")) {
      String command = TailCommand.open(settings.path(), settings.role());
      assertTrue(command.contains("tail -n 0 -F -s 0.25"));
      var console = rio.logs().resolve("console.txt"); java.nio.file.Files.writeString(console, "");
      rio.script(command, out -> {
        long offset = java.nio.file.Files.size(console);
        out.write((TailCommand.FOLLOW + "\n").getBytes(StandardCharsets.UTF_8)); out.flush();
        try {
          while (true) {
            String event = writes.take(); if (event.equals("STOP")) return;
            if (event.equals("ROTATED") && command.contains(" -F ")) offset = 0;
            byte[] text = java.nio.file.Files.readAllBytes(console);
            out.write(text, (int) offset, text.length - (int) offset); out.flush(); offset = text.length;
          }
        } finally { ended.countDown(); }
      });
      var auth = new PullConfig.Ssh("lvuser", "", null, false, rio.port());
      current.set(JschConnection.connect("127.0.0.1", auth, null));
      try (var provider = new TailProvider(settings, current::get, clock::get, () -> 2_000_000, () -> false, worker)) {
        provider.start(); worker.drain(); await(() -> provider.state().state().equals("following"));
        java.nio.file.Files.writeString(console, "first\n"); writes.add("APPEND"); await(() -> provider.buffer().size() == 1);
        clock.set(300); java.nio.file.Files.move(console, console.resolveSibling("console.old"));
        java.nio.file.Files.writeString(console, "rotated\n"); writes.add("ROTATED"); await(() -> provider.buffer().size() == 2);
        var lines = provider.buffer().drain(300, 20);
        assertEquals(List.of("first", "rotated"), lines.stream().map(TailBuffer.Line::text).toList());
        assertEquals(List.of(100L, 300L), lines.stream().map(TailBuffer.Line::receivedUs).toList());
        assertTrue(lines.stream().allMatch(TailBuffer.Line::buffered));
        rio.dropConnections(); await(() -> !current.get().connected()); worker.advance(250_000);
        assertEquals("offline", provider.state().state());
        // Release the fixture's old command before installing the next one.
        writes.add("STOP"); assertTrue(ended.await(5, TimeUnit.SECONDS)); current.get().close(); writes.clear();
        current.set(JschConnection.connect("127.0.0.1", auth, null)); worker.advance(250_000);
        await(() -> provider.state().state().equals("following"));
        clock.set(500); java.nio.file.Files.writeString(console, "resumed\n", java.nio.file.StandardOpenOption.APPEND); writes.add("APPEND"); await(() -> provider.buffer().size() == 1);
        assertEquals("resumed", provider.buffer().drain(500, 20).get(0).text());
        assertEquals(2, rio.authentications.get());
      } finally { writes.add("STOP"); if (current.get() != null) current.get().close(); }
    }
  }

  @Test void aBurstOnRealSshIsCountedAndHasOneNoticeAfterItEnds() throws Exception {
    var worker = new ManualScheduler(); var clock = new AtomicLong(100_000); var finish = new CountDownLatch(1);
    var config = new ProviderConfig.Tail(null, PullConfig.DISABLED.ssh(), "/console", "program_console");
    try (var rio = new FakeRoboRio(temp, "TAIL-BURST", "")) {
      rio.script(TailCommand.open(config.path(), config.role()), out -> {
        out.write((TailCommand.FOLLOW + "\n").getBytes(StandardCharsets.UTF_8));
        for (int n = 0; n < 275; n++) out.write(("line-" + n + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush(); finish.await();
      });
      try (var ssh = JschConnection.connect("127.0.0.1", new PullConfig.Ssh("lvuser", "", null, false, rio.port()), null);
          var tail = new TailProvider(config, () -> ssh, clock::get, () -> 2_000_000, () -> true, worker)) {
        tail.start(); worker.drain(); await(() -> tail.buffer().dropped() == 75);
        assertEquals(200, tail.buffer().size());
        var accepted = tail.buffer().drain(clock.get(), 1000);
        for (int n = 0; n < accepted.size(); n++) assertEquals("line-" + n, accepted.get(n).text());
        clock.addAndGet(1_000_000);
        assertEquals(List.of("[wpilog-mcp dropped 75 tail lines: rate or line-size limit]"),
            tail.buffer().drain(clock.get(), 1000).stream().map(TailBuffer.Line::text).toList());
      } finally { finish.countDown(); }
    }
  }

  @Test void missingSourceStandsDownForTheSessionAndAnUnsupportedSourceIsNeverGuessed() throws Exception {
    try (var rio = new FakeRoboRio(temp, "TAIL-MISSING", "")) {
      var config = new ProviderConfig.Tail(null, PullConfig.DISABLED.ssh(), "/absent", "program_console");
      rio.script(TailCommand.open(config.path(), config.role()), out -> out.write((TailCommand.MISSING + "\n").getBytes(StandardCharsets.UTF_8)));
      try (var ssh = JschConnection.connect("127.0.0.1", new PullConfig.Ssh("lvuser", "", null, false, rio.port()), null)) {
        var worker = new ManualScheduler(); var first = new Object();
        try (var tail = new TailProvider(config, () -> ssh, worker::nowUs, () -> 2_000_000, () -> true, worker)) {
          tail.session(first); tail.start(); worker.drain();
          await(() -> { worker.drain(); return tail.state().state().equals("stand_down"); });
          assertTrue(tail.state().reason().contains("missing or unreadable: /absent"));
          assertEquals(0, tail.buffer().size()); int calls = rio.commands.get();
          worker.advance(60_000_000); assertEquals(calls, rio.commands.get());
          tail.session(first); worker.advance(60_000_000); assertEquals(calls, rio.commands.get());
          tail.session(new Object()); worker.advance(250_000);
          await(() -> rio.commands.get() == calls + 1);
        }
      }
    }
  }

  @Test void kernelFallbackPollsByteOffsetsAndResetsOnRotationIncludingSplitUtf8() throws Exception {
    var worker = new ManualScheduler(); var clock = new AtomicLong();
    var path = "/var/log/kernel"; var config = new ProviderConfig.Tail(null, PullConfig.DISABLED.ssh(), path, "kernel");
    var bytes = new AtomicReference<>(new byte[0]); var inode = new AtomicLong(10); var reads = new ArrayList<Long>();
    try (var rio = new FakeRoboRio(temp, "TAIL-POLL", "")) {
      rio.script(TailCommand.open(path, "kernel"), out -> out.write((TailCommand.POLL + "\n").getBytes(StandardCharsets.UTF_8)));
      rio.script(TailCommand.stat(path), out -> out.write((inode.get() + " " + bytes.get().length).getBytes(StandardCharsets.UTF_8)));
      for (long offset : List.of(0L, 1L, 4L)) rio.script(TailCommand.read(path, offset), out -> { reads.add(offset); out.write(java.util.Arrays.copyOfRange(bytes.get(), (int) offset, bytes.get().length)); });
      try (var ssh = JschConnection.connect("127.0.0.1", new PullConfig.Ssh("lvuser", "", null, false, rio.port()), null);
          var tail = new TailProvider(config, () -> ssh, clock::get, () -> 2_000_000, () -> true, worker)) {
        tail.start(); worker.drain();
        await(() -> { worker.drain(); return tail.state().state().equals("polling"); }); worker.advance(250_000);
        bytes.set(new byte[] {(byte) 0xc3}); clock.set(2_000_000); worker.advance(250_000);
        assertEquals(0, tail.buffer().size());
        bytes.set("éx\n".getBytes(StandardCharsets.UTF_8)); clock.set(4_000_000); worker.advance(250_000);
        assertEquals("éx", tail.buffer().drain(clock.get(), 20).get(0).text());
        inode.incrementAndGet(); bytes.set("new\n".getBytes(StandardCharsets.UTF_8)); clock.set(6_000_000); worker.advance(250_000);
        assertEquals("new", tail.buffer().drain(clock.get(), 20).get(0).text());
        assertEquals(List.of(0L, 1L, 0L), reads);
      }
    }
  }
  static void await(BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) { assertTrue(System.nanoTime() < end, "SSH fixture callback deadline"); Thread.yield(); }
  }
}
