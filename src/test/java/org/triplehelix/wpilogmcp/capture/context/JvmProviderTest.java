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
        // Read status before advancing to the next poll: its RTT belongs to this sample.
        assertEquals(provider.status().lastRoundTripMs() / 1000.0 + .002 + .001,
            first.values().get("clock/round_trip_bound_sec").doubleValue(), 1e-9);
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
        assertEquals(java.util.Set.of("previous_offset_sec", "current_offset_sec", "change_sec",
            "previous_round_trip_bound_sec", "current_round_trip_bound_sec", "previous_jvm_start_time_ms",
            "current_jvm_start_time_ms", "reason"), second.note().keySet());
        assertEquals(first.values().get("clock/offset_sec").doubleValue(), second.note().get("previous_offset_sec").getAsDouble(), 1e-9);
        assertEquals(second.values().get("clock/offset_sec").doubleValue(), second.note().get("current_offset_sec").getAsDouble(), 1e-9);
        assertEquals(first.values().get("clock/round_trip_bound_sec").doubleValue(),
            second.note().get("previous_round_trip_bound_sec").getAsDouble(), 1e-9);
        assertEquals(second.values().get("clock/round_trip_bound_sec").doubleValue(),
            second.note().get("current_round_trip_bound_sec").getAsDouble(), 1e-9);
        assertEquals(start, second.note().get("previous_jvm_start_time_ms").getAsLong());
        assertEquals(start, second.note().get("current_jvm_start_time_ms").getAsLong());
        assertEquals("JVM uptime to FPGA mapping moved beyond the sum of the bounds; the provider does not determine the cause",
            second.note().get("reason").getAsString());
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
  @Test void uptimeTrackingAndWithinBoundChangesWriteNoNoteButASixSecondMoveDoes() throws Exception {
    try (var fixture = new JmxFixture()) {
      var clock = new ManualScheduler(); var offsetUs = new AtomicLong(20_000_000);
      var runtime = ManagementFactory.getRuntimeMXBean(); var samples = new ArrayList<JvmProvider.Sample>();
      try (var provider = new JvmProvider(new ContextConfig.Jvm(fixture.port(), 1_000_000),
          () -> (double) (offsetUs.get() + runtime.getUptime() * 1000), () -> 2000.,
          (session, sample) -> { samples.add(sample); return CompletableFuture.completedFuture(null); },
          () -> {}, clock, JvmProvider::connect)) {
        provider.session(new Object(), true, "127.0.0.1"); clock.until(() -> samples.size() == 1); clock.drain();
        clock.advance(1_000_000); clock.until(() -> samples.size() == 2); clock.drain();
        assertNull(samples.get(0).note()); assertNull(samples.get(1).note());
        // A half-millisecond change cannot cancel an integer-millisecond uptime delta.
        // This guarantees a nonzero change inside the bounds, even on a very fast target.
        offsetUs.addAndGet(500); clock.advance(1_000_000); clock.until(() -> samples.size() == 3); clock.drain();
        double change = samples.get(2).values().get("clock/offset_sec").doubleValue()
            - samples.get(1).values().get("clock/offset_sec").doubleValue();
        assertNotEquals(0., change);
        assertNull(samples.get(2).note(), "A nonzero change within the adjacent bounds is not a clock note");
        offsetUs.addAndGet(6_000_000); clock.advance(1_000_000); clock.until(() -> samples.size() == 4); clock.drain();
        assertNotNull(samples.get(3).note());
        assertEquals(6., samples.get(3).note().get("change_sec").getAsDouble(),
            samples.get(2).values().get("clock/round_trip_bound_sec").doubleValue()
                + samples.get(3).values().get("clock/round_trip_bound_sec").doubleValue());
      }
    }
  }

  @Test void aFailedDeliveryStandsDownUntilANewSessionEvenAcrossAResume() throws Exception {
    try (var fixture = new JmxFixture()) {
      var clock = new ManualScheduler(); var deliveries = new ArrayList<Object>(); var connections = new AtomicInteger();
      Object first = new Object(), second = new Object();
      var scheduled = new ArrayList<Long>();
      var scheduler = new org.triplehelix.wpilogmcp.nt4.client.ClientScheduler() {
        public long nowUs() { return clock.nowUs(); }
        public void execute(Runnable task) { clock.execute(task); }
        public void schedule(Runnable task, long delay) {
          if (delay != JvmProvider.DEADLINE_US) scheduled.add(delay);
          clock.schedule(task, delay);
        }
        public void close() { clock.close(); }
      };
      try (var provider = new JvmProvider(new ContextConfig.Jvm(fixture.port(), 1_000_000), () -> 20_000_000., () -> 2000.,
          (session, sample) -> {
            deliveries.add(session);
            return deliveries.size() == 1 ? CompletableFuture.failedFuture(new java.io.IOException("scripted delivery failure"))
                : CompletableFuture.completedFuture(null);
          }, () -> {}, scheduler, (host, port) -> { connections.incrementAndGet(); return JvmProvider.connect(host, port); })) {
        provider.session(first, true, "127.0.0.1"); clock.until(() -> provider.status().state().equals("stand_down"));
        assertTrue(provider.status().reason().contains("JMX sample delivery failed"));
        assertTrue(scheduled.isEmpty(), "Failed delivery must schedule no further poll in this session");
        clock.advance(60_000_000); assertEquals(java.util.List.of(first), deliveries);
        provider.session(null, false, null); clock.until(() -> provider.status().state().equals("offline"));
        provider.session(first, true, "127.0.0.1");
        clock.until(() -> provider.status().state().equals("stand_down") || deliveries.size() > 1);
        assertEquals("stand_down", provider.status().state(), "A resume cannot retry a delivery failed in the same session");
        clock.advance(60_000_000); assertEquals(1, connections.get()); assertEquals(java.util.List.of(first), deliveries);
        provider.session(second, true, "127.0.0.1"); clock.until(() -> deliveries.size() == 2); clock.drain();
        assertEquals(java.util.List.of(first, second), deliveries); assertEquals("sampling", provider.status().state());
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
