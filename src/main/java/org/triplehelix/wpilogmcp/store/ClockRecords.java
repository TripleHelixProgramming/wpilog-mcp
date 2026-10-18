/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.triplehelix.wpilogmcp.store.StoreManifest.ClockRecord;

/**
 * The clock facts a closed capture holds for placing a session's other sources on robot time,
 * written beside the session so that a copy without the capture, such as a laptop's mirror, can
 * still use them. Today that is the roboRIO uptime the pit samples over SSH, the only link between
 * kernel log times and robot time. A record keeps every sample, not thinned, so the origin and its
 * copies place kernel lines identically. It is derived from the capture alone: a store holding the
 * capture can always write it again, and the capture's hash in it shows when it is stale.
 */
public final class ClockRecords {
  private ClockRecords() {}
  public static final int FORMAT_VERSION = 1;
  public static final String UPTIME_ENTRY = "/Daemon/roboRIO/uptime_sec";

  /** One capture file's clock facts. Each uptime pair is [uptime_sec, robot_sec]. */
  public record Facts(int formatVersion, String capture, String captureSha256, long captureSizeBytes,
      String collectingStoreId, String recordingStartedAt, double minTimestampSec, double maxTimestampSec,
      List<double[]> uptime) {
    public Facts { uptime = uptime == null ? List.of() : List.copyOf(uptime); }
  }

  private static boolean blank(String value) { return value == null || value.isBlank(); }

  /** A record sits under its session's clock/ at the capture's own relative path, so names never collide. */
  static String path(String capture) { return "clock/" + capture + ".json"; }

  static void validate(ClockRecord record) throws IOException {
    if (record == null || record.path() == null || record.capture() == null || record.sizeBytes() < 0
        || record.sha256() == null || !record.sha256().matches("[0-9a-f]{64}")
        || record.captureSha256() == null || !record.captureSha256().matches("[0-9a-f]{64}")
        || !record.path().equals(path(record.capture()))
        || record.recording() != null && (blank(record.recording().collectingStoreId()) || blank(record.recording().startedAt()))) {
      throw new IOException("Invalid clock record receipt");
    }
  }

  static Path resolve(StoreFiles io, Path session, ClockRecord record) throws IOException {
    validate(record);
    var path = io.resolve(session, record.path());
    if (!path.startsWith(session.resolve("clock"))) throw new IOException("Clock record is outside its session's clock/: " + record.path());
    return io.check(path);
  }

  /** A record is trusted only as the receipt its session lists names it: whole, current and of a known format. */
  public static Facts read(StoreFiles io, Path path, ClockRecord record) throws IOException {
    if (!Files.isRegularFile(path) || Files.size(path) != record.sizeBytes() || !StoreFiles.hash(path).equals(record.sha256())) {
      throw new IOException("Clock record differs from its receipt: " + path);
    }
    var facts = io.read(path, Facts.class);
    if (facts.formatVersion() != FORMAT_VERSION || !record.capture().equals(facts.capture())
        || !record.captureSha256().equals(facts.captureSha256())
        || record.recording() != null && (!record.recording().collectingStoreId().equals(facts.collectingStoreId())
            || !record.recording().startedAt().equals(facts.recordingStartedAt()))
        || !Double.isFinite(facts.minTimestampSec()) || !Double.isFinite(facts.maxTimestampSec())
        || facts.minTimestampSec() > facts.maxTimestampSec()) throw new IOException("Clock record does not describe its capture: " + path);
    for (var pair : facts.uptime()) {
      if (pair == null || pair.length != 2 || !Double.isFinite(pair[0]) || !Double.isFinite(pair[1])) throw new IOException("Invalid clock record pair: " + path);
    }
    return facts;
  }
}
