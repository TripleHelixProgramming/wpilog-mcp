/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.util.List;

/** Durable facts, with portable relative paths; clocks and identity always retain their basis. */
public final class StoreManifest {
  private StoreManifest() {
  }

  public static final int FORMAT_VERSION = 1;

  public record Header(int formatVersion, String createdAt, String id, List<Move> moves) {}
  public record Move(String originalPath, String movedTo, String movedAt) {}
  public record Robot(String id, String serialNumber, String name, String comments, String basis) {}
  public record Session(String id, String startedAt, String endedAt, String startBasis,
      String event, String matchType, Integer matchNumber, Integer teamNumber, List<LogFile> files) {}
  public record Provenance(String kind, String originalPath, String originalName,
      String importedAt, boolean moved) {}
  public record Matching(String method, String wpilogSha256, long offsetMicros,
      double confidence, double driftRateNanosPerSec, double referenceTimeSec, String identityBasis) {}
  public record LogFile(String path, String sha256, long sizeBytes, String kind,
      Provenance provenance, boolean verified, double minTimestampSec, double maxTimestampSec,
      String startedAt, String endedAt, String startBasis, boolean truncated, Matching matching) {}
}
