/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import com.google.gson.annotations.SerializedName;

/** Published provider facts; unknown costs stay null and snapshots carry no credentials. */
public record ProviderStatus(String name, String state, String reason, @SerializedName("period_sec") double periodSec,
    @SerializedName("last_round_trip_ms") Double lastRoundTripMs, @SerializedName("robot_cpu_sec") Double robotCpuSec, @SerializedName("lines_per_sec") double linesPerSec, @SerializedName("dropped_lines") long droppedLines,
    @SerializedName("dropped_before_sync") long droppedBeforeSync, long records, long bytes, @SerializedName("sample_bytes") long sampleBytes) {
  /** FPGA = kernel uptime + offset; the command's round trip bounds this sampled pairing. */
  public record KernelClock(@SerializedName("uptime_sec") double uptimeSec, @SerializedName("fpga_timestamp_sec") double fpgaTimestampSec, @SerializedName("offset_sec") double offsetSec, @SerializedName("round_trip_ms") double roundTripMs) {}
}
