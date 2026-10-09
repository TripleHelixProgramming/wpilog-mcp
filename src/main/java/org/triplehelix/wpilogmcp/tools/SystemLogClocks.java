/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.WallClock;
import org.triplehelix.wpilogmcp.capture.pull.KernelLines;
import org.triplehelix.wpilogmcp.capture.pull.SystemLogTime;

/** Written timestamps and measured pairs only. Kernel time may precede FPGA startup; a
 * boot-time guess would assign early kernel faults to a match that had not begun. */
final class SystemLogClocks {
  record Pair(double source, double robot) {}
  record Mapped(String written, Double seconds, String basis, String reason) {}
  private final List<Pair> kernel = new ArrayList<>(), wall = new ArrayList<>();
  private final double start, end;
  SystemLogClocks(double start, double end) { this.start = start; this.end = end; }
  void add(LogData log, java.util.function.DoubleUnaryOperator robotTime, boolean includeWall) {
    var uptime = log.values().get("/Daemon/roboRIO/uptime_sec");
    if (uptime != null) for (var value : uptime) if (value.value() instanceof Number number && Double.isFinite(number.doubleValue())) {
      kernel.add(new Pair(number.doubleValue(), robotTime.applyAsDouble(value.timestamp())));
    }
    if (includeWall) for (var reading : WallClock.validReadings(log)) {
      wall.add(new Pair(reading.epochMicros() / 1e6, robotTime.applyAsDouble(reading.logTime())));
    }
  }
  boolean hasWall() { return !wall.isEmpty(); }
  void sort() { kernel.sort(Comparator.comparingDouble(Pair::source)); wall.sort(Comparator.comparingDouble(Pair::source)); }
  Mapped map(String line, String format) {
    String written = SystemLogTime.written(line, format);
    boolean uptime = format.equals("dmesg");
    Double value = uptime ? KernelLines.seconds(line) : SystemLogTime.epoch(line, format);
    if (value == null) return new Mapped(written, null, null, "No unambiguous written timestamp (a wall clock needs a year and zone)");
    var pairs = uptime ? kernel : wall;
    if (pairs.isEmpty()) return new Mapped(written, null, null, uptime ? "No uptime_sec samples pair the kernel and FPGA clocks" : "No systemTime or measured wall-clock offset in this session");
    Double mapped = interpolate(pairs, value, uptime);
    if (mapped == null || mapped < start || mapped > end) return new Mapped(written, null, null,
        uptime ? "Kernel timestamp is outside the measured uptime pairing or session; no extrapolation" : "Wall timestamp is outside this session's robot-clock range");
    return new Mapped(written, mapped, uptime ? "uptime_pairing" : "system_time", null);
  }
  private static Double interpolate(List<Pair> pairs, double value, boolean bounded) {
    int low = 0, high = pairs.size();
    while (low < high) { int mid = (low + high) >>> 1; if (pairs.get(mid).source() < value) low = mid + 1; else high = mid; }
    if (low < pairs.size() && pairs.get(low).source() == value) return pairs.get(low).robot();
    if (low == 0 || low == pairs.size()) {
      if (bounded) return null;
      var p = pairs.get(low == 0 ? 0 : pairs.size() - 1); return p.robot() + value - p.source();
    }
    var a = pairs.get(low - 1); var b = pairs.get(low);
    if (b.robot() <= a.robot()) return null;
    return a.robot() + (value - a.source()) * (b.robot() - a.robot()) / (b.source() - a.source());
  }
}
