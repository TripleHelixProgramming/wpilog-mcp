/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.config.ContextConfig;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;

class JvmProviderTest {
  @Test void realJvmCountersReceiptClockAndUptimeMappingSurviveAJump() throws Exception {
    try (var fixture = new JmxFixture()) {
      var clock = new ManualScheduler(); var robot = new AtomicReference<Double>(20_000_000.0);
      var samples = new ArrayList<JvmProvider.Sample>(); Object token = new Object();
      try (var provider = new JvmProvider(new ContextConfig.Jvm(fixture.port(), 1_000_000), robot::get, () -> 2000.,
          (session, sample) -> { assertSame(token, session); samples.add(sample); return CompletableFuture.completedFuture(null); },
          () -> {}, clock, JvmProvider::connect)) {
        System.gc(); System.gc(); // Establish a cumulative baseline distinct from one sample's delta.
        long start = ManagementFactory.getRuntimeMXBean().getStartTime();
        provider.session(token, true, "127.0.0.1"); clock.until(() -> samples.size() == 1); clock.drain();
        var first = samples.get(0);
        assertEquals(20_000_000, first.timestampUs(), "Receipt must use the robot estimate, never the laptop clock");
        assertEquals(start, first.metadata().get("jvm_start_time_ms").getAsLong());
        assertEquals("measured", first.metadata().get("clock").getAsString());
        assertEquals("receipt mapped through NT4 server time", first.metadata().get("timestamp_basis").getAsString());
        assertEquals(20. - first.values().get("uptime_sec").doubleValue(), first.values().get("clock/offset_sec").doubleValue(), 1e-9);
        assertNotNull(first.runtime()); assertNull(first.note());
        assertEquals(ManagementFactory.getRuntimeMXBean().getVmName(), first.runtime().get("vm_name").getAsString());
        long gcBefore = gcCount(first); System.gc();
        var expected = ManagementFactory.getGarbageCollectorMXBeans().stream().filter(gc -> gc.getCollectionCount() >= 0)
            .collect(java.util.stream.Collectors.toMap(gc -> "gc/" + JvmSample.component(gc.getName()) + "/count", gc -> gc.getCollectionCount()));
        long independentCount = expected.values().stream().mapToLong(Long::longValue).sum();
        assertTrue(independentCount > gcBefore, "The test's explicit collection must be observable");
        robot.set(27_000_000.); clock.advance(1_000_000); clock.until(() -> samples.size() == 2); clock.drain();
        var second = samples.get(1);
        assertTrue(gcCount(second) >= independentCount);
        expected.forEach((name, count) -> assertTrue(second.values().get(name).longValue() >= count, "Counts remain cumulative: " + name));
        for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
          String key = "gc/" + JvmSample.component(collector.getName()) + "/time_sec";
          if (collector.getCollectionTime() >= 0) assertTrue(second.values().get(key).doubleValue() <= collector.getCollectionTime() / 1000.0);
        }
        assertEquals(27_000_000, second.timestampUs()); assertNotNull(second.note()); assertNull(second.runtime());
        assertEquals(second.values().get("clock/offset_sec").doubleValue() - first.values().get("clock/offset_sec").doubleValue(),
            second.note().get("change_sec").getAsDouble(), 1e-9);
        assertEquals(20_000_000, first.timestampUs(), "A later mapping never retimes earlier samples");
        assertTrue(second.values().get("heap/committed_bytes").longValue() >= second.values().get("heap/used_bytes").longValue());
        assertTrue(second.values().get("classes/total").longValue() >= second.values().get("classes/loaded").longValue());
        assertTrue(second.values().get("threads/peak").longValue() >= second.values().get("threads/live").longValue());
        if (second.values().containsKey("process/cpu_sec")) {
          double actual = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime() / 1e9;
          assertTrue(second.values().get("process/cpu_sec").doubleValue() <= actual);
        }
        long payload = second.values().size() * 8L + second.note().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertEquals(payload, provider.status().sampleBytes()); assertTrue(provider.status().lastRoundTripMs() > 0);
        System.out.println("JMX measurement: round_trip_ms=" + provider.status().lastRoundTripMs() + ", sample_payload_bytes=" + payload);
      }
    }
  }
  private static long gcCount(JvmProvider.Sample sample) {
    return sample.values().entrySet().stream().filter(v -> v.getKey().startsWith("gc/") && v.getKey().endsWith("/count"))
        .mapToLong(v -> v.getValue().longValue()).sum();
  }

  @Test void refusedPortNamesLaunchFlagsAndUsesOneTwoFourSecondBackoff() throws Exception {
    int port; try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
    var clock = new ManualScheduler(); var attempts = new AtomicInteger(); var retries = new ArrayList<Long>();
    var scheduler = new org.triplehelix.wpilogmcp.nt4.client.ClientScheduler() {
      public long nowUs() { return clock.nowUs(); }
      public void execute(Runnable task) { clock.execute(task); }
      public void schedule(Runnable task, long delay) { if (delay != JvmProvider.DEADLINE_US) retries.add(delay); clock.schedule(task, delay); }
      public void close() { clock.close(); }
    };
    try (var provider = new JvmProvider(new ContextConfig.Jvm(port, 1_000_000), () -> 1_000_000., () -> 0.,
        (session, sample) -> { fail("Refused port cannot produce a sample"); return CompletableFuture.completedFuture(null); }, () -> {}, scheduler,
        (host, number) -> { attempts.incrementAndGet(); return JvmProvider.connect(host, number); })) {
      provider.session(new Object(), true, "127.0.0.1");
      for (int i = 0; i < 3; i++) {
        int count = i + 1;
        clock.until(() -> retries.size() >= count);
        assertEquals(count, attempts.get());
        assertTrue(provider.status().reason().contains("jmxremote.rmi.port"));
        assertTrue(provider.status().reason().contains("java.rmi.server.hostname"));
        long delay = 1_000_000L << i;
        assertEquals(delay, retries.get(i));
        clock.advance(delay - 1); assertEquals(count, attempts.get());
        clock.advance(1);
      }
      clock.until(() -> retries.size() == 4);
      provider.session(null, false, null); clock.drain();
      int count = attempts.get(); clock.advance(60_000_000); assertEquals(count, attempts.get());
    }
  }

  @Test void delayedConnectionCannotDeliverToAnotherSessionAndTheDeadlineRunsOutsideIo() throws Exception {
    try (var fixture = new JmxFixture()) {
      var clock = new ManualScheduler(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
      var attempts = new AtomicInteger(); var delivered = new ArrayList<Object>(); Object first = new Object(), second = new Object();
      try (var provider = new JvmProvider(new ContextConfig.Jvm(fixture.port(), 1_000_000), () -> 2_000_000., () -> 0.,
          (session, sample) -> { delivered.add(session); return CompletableFuture.completedFuture(null); }, () -> {}, clock,
          (host, port) -> { if (attempts.incrementAndGet() == 1) { entered.countDown(); assertTrue(release.await(30, TimeUnit.SECONDS)); }
            return JvmProvider.connect(host, port); })) {
        provider.session(first, true, "127.0.0.1"); clock.drain(); assertTrue(entered.await(30, TimeUnit.SECONDS));
        clock.advance(5_000_000); assertEquals("stand_down", provider.status().state());
        clock.advance(60_000_000); assertEquals(1, attempts.get(), "A stuck RMI call must not spawn more calls");
        provider.session(second, true, "127.0.0.1"); clock.drain(); release.countDown();
        clock.until(() -> !delivered.isEmpty()); assertEquals(java.util.List.of(second), delivered);
      } finally { release.countDown(); }
    }
  }

  @Test void noEstimateDropsTheSampleAndRuntimeArgumentsRedactSecrets() throws Exception {
    try (var fixture = new JmxFixture()) {
      var clock = new ManualScheduler(); var time = new AtomicReference<Double>(); var samples = new ArrayList<JvmProvider.Sample>();
      try (var provider = new JvmProvider(new ContextConfig.Jvm(fixture.port(), 1_000_000), time::get, () -> 0.,
          (session, sample) -> { samples.add(sample); return CompletableFuture.completedFuture(null); }, () -> {}, clock, JvmProvider::connect)) {
        provider.session(new Object(), true, "127.0.0.1"); clock.until(() -> provider.status().state().equals("waiting_for_sync"));
        assertEquals(1, provider.status().droppedBeforeSync()); assertTrue(samples.isEmpty());
        time.set(4_000_000.); clock.advance(1_000_000); clock.until(() -> samples.size() == 1);
        assertNotNull(samples.get(0).runtime());
      }
    }
    assertEquals("-Dapi.token=[redacted]", JvmSample.redact("-Dapi.token=synthetic-secret"));
    assertEquals("-Xmx64m", JvmSample.redact("-Xmx64m"));
  }
}
