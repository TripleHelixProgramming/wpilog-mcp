/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.triplehelix.wpilogmcp.config.ProviderConfig;
import org.triplehelix.wpilogmcp.ssh.SshConnection;

/** One command at a time, on the provider's worker. Capture only sees the immutable result. */
public final class StatsProvider {
  public static final long COMMAND_DEADLINE_MS = 30_000;
  public static final int MAX_REPLY_BYTES = 65_536;
  public record Result(ProcStats.Sample sample, Long timestampUs, long periodUs,
      long roundTripUs, long bytes, ProviderStatus.KernelClock kernelClock) {}
  private final LongSupplier clock;
  private final Supplier<Double> offset;
  private final SamplePeriod period;
  private final String command;
  private ProcStats.Snapshot previous;
  private long droppedBeforeSync;
  public StatsProvider(ProviderConfig.Stats config, List<String> directories, LongSupplier clock, Supplier<Double> offset) {
    this.clock = clock; this.offset = offset;
    period = new SamplePeriod(config.periodUs(), config.budgetUs()); command = StatsCommand.sample(directories);
  }
  public long periodUs() { return period.periodUs(); }
  public long droppedBeforeSync() { return droppedBeforeSync; }
  public void reconnect() { previous = null; }
  public Result sample(SshConnection connection) throws IOException {
    long usedPeriod = period.periodUs();
    // Capture both at send. A time estimate arriving during exec cannot retroactively timestamp it.
    Double mapping = offset.get(); long sent = clock.getAsLong();
    String text = connection.exec(command, COMMAND_DEADLINE_MS, MAX_REPLY_BYTES)
        .orElseThrow(() -> new IOException("SSH exec is unavailable for stats"));
    long rtt = clock.getAsLong() - sent; period.measured(rtt);
    var current = ProcStats.parse(text); var sample = ProcStats.between(previous, current); previous = current;
    Long timestamp = mapping == null ? null : Math.max(0, Math.round(sent + mapping));
    if (timestamp == null) droppedBeforeSync++;
    var kernel = timestamp == null ? null : new ProviderStatus.KernelClock(current.uptime(), timestamp / 1_000_000.0,
        timestamp / 1_000_000.0 - current.uptime(), rtt / 1000.0);
    return new Result(sample, timestamp, usedPeriod, rtt, text.getBytes(StandardCharsets.UTF_8).length, kernel);
  }
}
