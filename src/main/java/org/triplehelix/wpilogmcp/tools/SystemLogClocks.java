/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import java.util.ArrayList;
import java.io.IOException;
import java.nio.file.Path;
import com.google.gson.JsonArray;
import org.triplehelix.wpilogmcp.store.SystemLogFiles;
import org.triplehelix.wpilogmcp.store.StoreManifest;
import java.util.Comparator;
import java.util.List;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.WallClock;
import org.triplehelix.wpilogmcp.read.UtcTimeMapping;
import org.triplehelix.wpilogmcp.capture.pull.KernelLines;
import org.triplehelix.wpilogmcp.capture.pull.SystemLogTime;

/** Written timestamps and measured pairs only. Kernel time may precede FPGA startup; a
 * boot-time guess would assign early kernel faults to a match that had not begun. */
public final class SystemLogClocks {
  record Pair(double source, double robot) {}
  /** Only an unchanged robot clock may reuse a raw microsecond coordinate for validation. */
  record RobotTime(java.util.function.DoubleUnaryOperator mapping, boolean identity)
      implements java.util.function.DoubleUnaryOperator {
    @Override public double applyAsDouble(double value) { return mapping.applyAsDouble(value); }
  }
  public record Mapped(String written, Double seconds, String basis, String reason) {}
  private final List<Pair> kernel = new ArrayList<>();
  private final List<UtcTimeMapping.Coordinate> wallCoordinates = new ArrayList<>();
  private UtcTimeMapping.Inverse wallInverse = UtcTimeMapping.inverse(List.of());
  private String wallReason;
  private boolean nonFiniteWallCoordinate;
  private final double start, end;
  SystemLogClocks(double start, double end) { this.start = start; this.end = end; }
  void add(LogData log, java.util.function.DoubleUnaryOperator robotTime, boolean includeWall, boolean includeKernel) {
    var uptime = includeKernel ? log.values().get("/Daemon/roboRIO/uptime_sec") : null;
    if (uptime != null) for (var value : uptime) if (value.value() instanceof Number number && Double.isFinite(number.doubleValue())) {
      kernel.add(new Pair(number.doubleValue(), robotTime.applyAsDouble(value.timestamp())));
    }
    if (includeWall) {
      boolean identity = robotTime instanceof RobotTime mapping && mapping.identity();
      var exact = identity ? WallClock.validExactReadings(log) : List.<WallClock.ExactReading>of();
      int index = 0;
      for (var reading : WallClock.validReadings(log)) {
        double robot = robotTime.applyAsDouble(reading.logTime());
        if (Double.isFinite(robot)) {
          var coordinate = identity ? java.math.BigDecimal.valueOf(exact.get(index).logMicros())
              : new java.math.BigDecimal(robot).scaleByPowerOfTen(6);
          wallCoordinates.add(new UtcTimeMapping.Coordinate(coordinate, reading.epochMicros()));
        } else nonFiniteWallCoordinate = true;
        index++;
      }
    }
  }
  /** A clock record's pairs, already in the robot time of the capture it was read from. */
  void addKernel(java.util.List<double[]> pairs) { for (var pair : pairs) kernel.add(new Pair(pair[0], pair[1])); }
  boolean hasWall() { return !wallCoordinates.isEmpty() || nonFiniteWallCoordinate; }
  void sort() {
    kernel.sort(Comparator.comparingDouble(Pair::source));
    wallInverse = UtcTimeMapping.inverse(wallCoordinates);
    wallReason = nonFiniteWallCoordinate ? "Ambiguous wall-clock mapping: non-finite aligned log timestamp"
        : wallInverse.reason();
  }
  public Mapped map(String line, String format) {
    String written = SystemLogTime.written(line, format);
    boolean uptime = format.equals("dmesg");
    Double value = uptime ? KernelLines.seconds(line) : SystemLogTime.epoch(line, format);
    if (value == null) return new Mapped(written, null, null, "No unambiguous written timestamp (a wall clock needs a year and zone)");
    if (!uptime && wallReason != null) return new Mapped(written, null, null, wallReason);
    if (uptime ? kernel.isEmpty() : !hasWall()) return new Mapped(written, null, null, uptime ? "No uptime_sec samples pair the kernel and FPGA clocks" : "No systemTime or measured wall-clock offset in this session");
    Double mapped = uptime ? interpolate(kernel, value, true) : wallInverse.at(exactEpochMicros(written, format));
    if (mapped == null || mapped < start || mapped > end) return new Mapped(written, null, null,
        uptime ? "Kernel timestamp is outside the measured uptime pairing or session; no extrapolation" : "Wall timestamp is outside this session's robot-clock range");
    return new Mapped(written, mapped, uptime ? "uptime_pairing" : "system_time", null);
  }
  /**
   * The prior parser still gates grammar and finite range. Journal text contains no exponent;
   * its decimal storage grows only with the already-read literal. ISO precision is the same
   * OffsetDateTime precision accepted previously. Neither path rounds an epoch through double.
   */
  private static java.math.BigDecimal exactEpochMicros(String written, String format) {
    if (format.equals("journal")) return new java.math.BigDecimal(written).scaleByPowerOfTen(6);
    var instant = java.time.OffsetDateTime.parse(written).toInstant();
    return java.math.BigDecimal.valueOf(instant.getEpochSecond()).scaleByPowerOfTen(6)
        .add(java.math.BigDecimal.valueOf(instant.getNano()).scaleByPowerOfTen(-3));
  }
  public static SystemLogClocks read(LogData log, SystemLogFiles.Snapshot snapshot, JsonArray read,
      org.triplehelix.wpilogmcp.read.server.ManagedLogAccess logAccess) throws IOException {
    var session = snapshot.session(); var directory = snapshot.manifest().getParent();
    // The session's robot time spans its WPILOGs, each placed by the offset recorded when it was
    // matched. A REV log's span is on the REV log's own clock and says nothing about robot time.
    double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
    var searched = onRobotTime(Path.of(log.path()), snapshot);
    if (searched.isPresent()) {
      min = searched.get().applyAsDouble(log.minTimestamp()); max = searched.get().applyAsDouble(log.maxTimestamp());
    }
    for (var f : session.files()) {
      if (!"wpilog".equals(f.kind())) continue;
      var placed = onRobotTime(directory.resolve(f.path()), snapshot);
      if (placed.isEmpty()) continue;
      min = Math.min(min, placed.get().applyAsDouble(f.minTimestampSec())); max = Math.max(max, placed.get().applyAsDouble(f.maxTimestampSec()));
    }
    if (session.openCapture() != null) { min = Math.min(min, session.openCapture().minTimestampSec()); max = Math.max(max, session.openCapture().maxTimestampSec()); }
    // A clock record stands in for its capture, which this copy may not hold, so the session's span
    // and kernel pairs are the same either way. A capture listed with another hash outranks a stale record.
    var covered = new java.util.HashSet<Path>();
    var records = new java.util.ArrayList<org.triplehelix.wpilogmcp.store.ClockRecords.Facts>();
    for (var clock : snapshot.clockRecords()) {
      var facts = clock.facts(); var capture = directory.resolve(facts.capture()).normalize();
      if (session.files().stream().anyMatch(f -> directory.resolve(f.path()).normalize().equals(capture) && !f.sha256().equals(facts.captureSha256()))) continue;
      records.add(facts); covered.add(capture); read.add(clock.path().toString());
      min = Math.min(min, facts.minTimestampSec()); max = Math.max(max, facts.maxTimestampSec());
    }
    var result = new SystemLogClocks(min, max);
    for (var facts : records) result.addKernel(facts.uptime());
    // Prefer the robot's systemTime when a pulled log supplies it, respecting its recorded offset.
    for (Path path : snapshot.clocks()) {
      if (!pulled(path, snapshot)) continue;
      var placed = onRobotTime(path, snapshot);
      if (placed.isEmpty()) continue;
      try (var use = logAccess.acquire(path.toString())) {
        result.add(use.log(), placed.get(), true, !covered.contains(path.normalize())); read.add(path.toString());
      }
    }
    boolean wallFallback = !result.hasWall();
    for (Path path : snapshot.clocks()) {
      if (pulled(path, snapshot)) continue;
      boolean kernel = !covered.contains(path.normalize());
      // A capture whose record supplies its kernel pairs, beside a log that supplied the wall clock,
      // has nothing to give: reading it would cost the whole capture and list it as a clock it is not.
      if (!wallFallback && !kernel) continue;
      var placed = onRobotTime(path, snapshot);
      if (placed.isEmpty()) continue;
      if (path.toString().equals(log.path())) result.add(log, placed.get(), wallFallback, kernel);
      else try (var use = logAccess.acquire(path.toString())) { result.add(use.log(), placed.get(), wallFallback, kernel); }
      read.add(path.toString());
    }
    result.sort(); return result;
  }
  private static StoreManifest.LogFile receipt(Path path, SystemLogFiles.Snapshot snapshot) {
    var directory = snapshot.manifest().getParent();
    return snapshot.session().files().stream().filter(f -> directory.resolve(f.path()).equals(path)).findFirst().orElse(null);
  }
  private static boolean pulled(Path path, SystemLogFiles.Snapshot snapshot) {
    var receipt = receipt(path, snapshot);
    return receipt != null && receipt.provenance().kind().equals("pulled");
  }
  /** A file's own time on the session's robot time, by the offset recorded when it was matched, or
   * as it is when it was not matched. Empty when the recorded offset is invalid: a damaged record
   * places nothing. */
  private static java.util.Optional<RobotTime> onRobotTime(Path path, SystemLogFiles.Snapshot snapshot) {
    var receipt = receipt(path, snapshot);
    if (receipt == null || receipt.matching() == null) return java.util.Optional.of(new RobotTime(t -> t, true));
    var alignment = org.triplehelix.wpilogmcp.store.StoreCatalog.recordedAlignment(
        new org.triplehelix.wpilogmcp.store.StoreCatalog.StoredFile(path, snapshot.manifest(), null, snapshot.session(), receipt));
    return alignment.isSuccessful() ? java.util.Optional.of(new RobotTime(alignment::toFpgaTime, false)) : java.util.Optional.empty();
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
