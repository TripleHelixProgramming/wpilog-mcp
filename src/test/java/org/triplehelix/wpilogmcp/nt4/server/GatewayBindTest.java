/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.server;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;

class GatewayBindTest {
  @Test void backoffDoublesToThirtySecondsAndKeepsOneWaitingEpisodeUntilClose() throws Exception {
    var manual = new ManualScheduler(); var delays = new ArrayList<Long>();
    var clock = new ClientScheduler() {
      public long nowUs() { return manual.nowUs(); }
      public void execute(Runnable action) { manual.execute(action); }
      public void schedule(Runnable action, long delayUs) { delays.add(delayUs); manual.schedule(action, delayUs); }
      public void close() { manual.close(); }
    };
    var messages = new java.io.ByteArrayOutputStream(); var stderr = System.err;
    System.setErr(new java.io.PrintStream(messages, true, java.nio.charset.StandardCharsets.UTF_8));
    try (var held = new ServerSocket()) {
      held.bind(new InetSocketAddress("127.0.0.1", 0));
      var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", held.getLocalPort()), () -> 0, Clock.systemUTC(), clock);
      try {
        var listening = gateway.start(); manual.drain(); var waiting = gateway.status();
        assertEquals(GatewayStatus.State.WAITING, waiting.state()); assertTrue(waiting.cause().contains("BindException"));
        long[] expected = {1, 2, 4, 8, 16, 30, 30, 30};
        for (int i = 0; i < expected.length; i++) {
          assertEquals(i + 1, delays.size()); assertEquals(expected[i] * 1_000_000, delays.get(i));
          assertEquals(waiting, gateway.status(), "A retry must not reset the start of the waiting episode");
          manual.advance(expected[i] * 1_000_000 - 1); assertEquals(i + 1, delays.size());
          manual.advance(1);
        }
        assertFalse(listening.isDone(), "Waiting is recoverable, not a terminal failed startup");
        assertTimeoutPreemptively(Duration.ofSeconds(2), gateway::close);
        int attempts = delays.size(); manual.advance(30_000_000); assertEquals(attempts, delays.size());
        assertEquals(GatewayStatus.State.STOPPED, gateway.status().state());
        String log = messages.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(1, log.lines().filter(line -> line.contains("waiting for port")).count(), log);
        assertTrue(log.lines().anyMatch(line -> line.contains("ERROR") && line.contains("capture.gateway.port")), log);
        assertFalse(log.contains("Shutdown due to fatal error"), log);
      } finally { gateway.close(); }
    } finally { System.setErr(stderr); }
  }

  @Test void theListeningSocketEnablesAddressReuseAndCloseBeforeStartDoesNotBind() throws Exception {
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 0)) {
      gateway.start().get(5, TimeUnit.SECONDS); assertTrue(gateway.reusesAddress());
    }
    var manual = new ManualScheduler();
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 0, Clock.systemUTC(), manual)) {
      gateway.close(); assertTrue(gateway.start().isCompletedExceptionally()); manual.drain();
      assertEquals(GatewayStatus.State.STOPPED, gateway.status().state()); assertEquals(0, gateway.port());
    }
  }
}
