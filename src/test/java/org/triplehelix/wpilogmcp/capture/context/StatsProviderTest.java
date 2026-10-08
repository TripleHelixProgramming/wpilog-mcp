/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.config.ProviderConfig;
import org.triplehelix.wpilogmcp.config.PullConfig;
import org.triplehelix.wpilogmcp.harness.FakeRoboRio;
import org.triplehelix.wpilogmcp.ssh.JschConnection;

class StatsProviderTest {
  @TempDir Path temp;
  @Test void procUnitsAndRatesHaveIndependentAnswersIncludingParenthesesInTheProgramName() throws Exception {
    var first = ProcStats.parse(ProcFixture.sample(0));
    var initial = ProcStats.between(null, first);
    assertFalse(initial.values().containsKey("cpu_busy_fraction"));
    assertFalse(initial.values().containsKey("net/eth0/rx_bytes_per_sec"));
    assertFalse(initial.values().containsKey("program/cpu_fraction"));
    assertNull(initial.robotCpuSeconds());
    var second = ProcStats.between(first, ProcStats.parse(ProcFixture.sample(1)));
    var values = second.values();
    assertEquals(0.6, values.get("cpu_busy_fraction").doubleValue());
    assertEquals(1.2, second.robotCpuSeconds());
    assertEquals(200, values.get("net/eth0/rx_bytes_per_sec").doubleValue());
    assertEquals(300, values.get("net/eth0/tx_bytes_per_sec").doubleValue());
    assertEquals(0.25, values.get("program/cpu_fraction").doubleValue());
    assertEquals(24_576, values.get("program/rss_bytes").longValue());
    assertEquals(7, values.get("program/threads").longValue()); assertEquals(42, values.get("program/pid").longValue());
    assertEquals(512_000, values.get("mem_available_bytes").longValue());
    assertEquals(102_400, values.get("mem_free_bytes").longValue());
    assertEquals(11_264, values.get("disk/home/lvuser/free_bytes").longValue());
    assertEquals(1.5, values.get("load_1min").doubleValue()); assertEquals(0.5, values.get("load_5min").doubleValue());
    assertEquals(0.25, values.get("load_15min").doubleValue()); assertEquals(3, values.get("runnable_tasks").longValue());
    assertEquals(102, values.get("uptime_sec").doubleValue()); assertTrue(second.notes().isEmpty());
  }

  @Test void missingUnitsAmbiguousProgramsAndCounterResetsNeverProduceGuessedRates() throws Exception {
    String text = ProcFixture.sample(0);
    for (String invalid : List.of(text.replace("500 kB", "500 MB"), text.replace("WPILOG_STATS_1:ticks\n100", "WPILOG_STATS_1:ticks\n0"),
        text.replace("MemAvailable: 500 kB\n", ""), text.replace("1.5 0.5", "NaN 0.5"))) {
      assertTrue(assertThrows(IOException.class, () -> ProcStats.parse(invalid)).getMessage().contains("Unsupported SSH stats output"));
    }
    var first = ProcStats.parse(text); var restarted = ProcStats.parse(ProcFixture.sample(1).replace("42 (robot", "43 (robot"));
    assertFalse(ProcStats.between(first, restarted).values().containsKey("program/cpu_fraction"));
    var reset = ProcStats.between(ProcStats.parse(ProcFixture.sample(1)), first);
    assertFalse(reset.values().containsKey("cpu_busy_fraction")); assertFalse(reset.values().containsKey("net/eth0/rx_bytes_per_sec"));
    assertFalse(reset.notes().isEmpty());
    String program = text.lines().filter(line -> line.startsWith("42 (")).findFirst().orElseThrow();
    for (String changed : List.of(text.replace(program, ""), text + program + "\n")) {
      var sample = ProcStats.between(null, ProcStats.parse(changed));
      assertFalse(sample.values().containsKey("program/pid")); assertFalse(sample.notes().isEmpty());
    }
  }

  @Test void realExecIsStampedAtSendAndNoEstimateDropsEvenIfOneArrivesBeforeTheReply() throws Exception {
    var time = new AtomicLong(10_000_000); var offset = new AtomicReference<Double>(40_000_000.0);
    try (var rio = new FakeRoboRio(temp, "SYNTHETIC-STATS", "")) {
      rio.script(StatsCommand.lookup(true), out -> out.write(ProcFixture.sample(0).getBytes(StandardCharsets.UTF_8)));
      String command = StatsCommand.sample(PullConfig.DISABLED.directories(), org.triplehelix.wpilogmcp.capture.context.ProcStats.configuration(ProcFixture.sample(0), null));
      rio.script(command, output -> {
        time.addAndGet(150_000); offset.set(90_000_000.0);
        output.write(ProcFixture.sample(0).getBytes(StandardCharsets.UTF_8));
      });
      try (var ssh = JschConnection.connect("127.0.0.1", new PullConfig.Ssh("lvuser", "", null, false, rio.port()), null)) {
        var provider = new StatsProvider(new ProviderConfig.Stats(true, 2_000_000, 100_000), PullConfig.DISABLED.directories(), time::get, offset::get);
        var result = provider.sample(ssh);
        assertEquals(50_000_000L, result.timestampUs()); assertEquals(150_000, result.roundTripUs());
        assertEquals(2_000_000, result.periodUs()); assertEquals(4_000_000, provider.periodUs());
        assertEquals(100, result.kernelClock().uptimeSec()); assertEquals(50, result.kernelClock().fpgaTimestampSec());
        assertEquals(-50, result.kernelClock().offsetSec()); assertEquals(150, result.kernelClock().roundTripMs());
        offset.set(null); result = provider.sample(ssh);
        assertNull(result.timestampUs()); assertNull(result.kernelClock()); assertEquals(1, provider.droppedBeforeSync());
        assertEquals(3, rio.commands.get()); assertEquals(1, rio.authentications.get());
      }
    }
  }

  @Test void discoversOnceChecksStartTicksAndDiscoversAgainOnlyAfterRestartOrReconnect() throws Exception {
    var phase = new java.util.concurrent.atomic.AtomicInteger();
    var initialLookups = new java.util.concurrent.atomic.AtomicInteger();
    var restartLookups = new java.util.concurrent.atomic.AtomicInteger();
    var samples = new java.util.concurrent.atomic.AtomicInteger();
    var clock = new AtomicLong();
    var provider = new StatsProvider(new ProviderConfig.Stats(true, 2_000_000, 100_000),
        PullConfig.DISABLED.directories(), clock::get, () -> 0.0);
    var original = ProcStats.configuration(ProcFixture.sample(0), null);
    String command = StatsCommand.sample(PullConfig.DISABLED.directories(), original);
    // This is the steady command: shell reads/printf and exactly one external df, no process scan.
    for (String forbidden : List.of("getconf", "cmdline", "robotCommand", "sed ", "grep ", "tr ", "cat ")) {
      assertFalse(command.contains(forbidden), forbidden);
    }
    assertEquals(1, command.split("df -Pk", -1).length - 1);
    assertTrue(command.contains("emit /proc/42/stat"));
    try (var rio = new FakeRoboRio(temp, "SYNTHETIC-STATS", "")) {
      rio.script(StatsCommand.lookup(true), out -> {
        initialLookups.incrementAndGet(); out.write(ProcFixture.sample(0).getBytes(StandardCharsets.UTF_8));
      });
      rio.script(StatsCommand.lookup(false), out -> {
        restartLookups.incrementAndGet(); out.write(ProcFixture.sample(3).replace(" 500 99999", " 800 99999").getBytes(StandardCharsets.UTF_8));
      });
      rio.script(command, out -> {
        samples.incrementAndGet(); int n = phase.get(); clock.addAndGet(2_000_000);
        String text = ProcFixture.sample(n);
        if (n >= 3) text = text.replace(" 500 99999", " 800 99999"); // Same pid, different process.
        out.write(text.getBytes(StandardCharsets.UTF_8));
      });
      var settings = new PullConfig.Ssh("lvuser", "", null, false, rio.port());
      try (var ssh = JschConnection.connect("127.0.0.1", settings, null)) {
        for (int n = 0; n < 3; n++) { phase.set(n); provider.sample(ssh); }
        assertEquals(1, initialLookups.get()); assertEquals(0, restartLookups.get()); assertEquals(3, samples.get());
        phase.set(3);
        assertFalse(provider.sample(ssh).sample().values().containsKey("program/pid"), "Do not report the reused pid until discovery confirms it");
        assertEquals(0, restartLookups.get());
        phase.set(4); assertEquals(42L, provider.sample(ssh).sample().values().get("program/pid").longValue());
        phase.set(5); provider.sample(ssh);
        assertEquals(1, initialLookups.get()); assertEquals(1, restartLookups.get()); assertEquals(6, samples.get());
      }
      phase.set(0);
      try (var ssh = JschConnection.connect("127.0.0.1", settings, null)) { provider.sample(ssh); }
      assertEquals(2, initialLookups.get()); assertEquals(1, restartLookups.get()); assertEquals(7, samples.get());
      assertEquals(10, rio.commands.get());
    }
  }

  @Test void oneDfReportsEachFilesystemAndAnUnmountDoesNotInventItsFreeSpace() throws Exception {
    String text = ProcFixture.sample(0).replace("WPILOG_STATS_1:disk/home/lvuser", "WPILOG_STATS_1:disk_paths\n/home/lvuser\n/u\nWPILOG_STATS_1:disk")
        .replace("WPILOG_STATS_1:program", "/dev/usb 200 180 20 90% /u\nWPILOG_STATS_1:program");
    var values = ProcStats.parse(text).gauges();
    assertEquals(11_264L, values.get("disk/home/lvuser/free_bytes").longValue());
    assertEquals(20_480L, values.get("disk/u/free_bytes").longValue());
    var missing = ProcStats.parse(text.replace("/dev/usb 200 180 20 90% /u\n", ""));
    assertFalse(missing.gauges().keySet().stream().anyMatch(k -> k.startsWith("disk/")));
    assertTrue(missing.notes().stream().anyMatch(n -> n.contains("Filesystems changed")));
  }

  @Test void budgetBacksOffToThirtySecondsAndRecoversToTheConfiguredBase() {
    var period = new SamplePeriod(2_000_000, 100_000);
    for (long expected : new long[] {4, 8, 16, 30, 30}) { period.measured(100_001); assertEquals(expected * 1_000_000, period.periodUs()); }
    for (long expected : new long[] {15_000_000, 7_500_000, 3_750_000, 2_000_000, 2_000_000}) {
      period.measured(100_000); assertEquals(expected, period.periodUs());
    }
  }
}
