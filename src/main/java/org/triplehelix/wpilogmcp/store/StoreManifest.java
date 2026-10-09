/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.util.List;
import java.util.Map;
import org.triplehelix.wpilogmcp.capture.context.DeviceIdentity;

/** Durable facts, with portable relative paths; clocks and identity always retain their basis. */
public final class StoreManifest {
  private StoreManifest() {
  }

  public static final int FORMAT_VERSION = 1;

  public record Header(int formatVersion, String createdAt, String id, List<Move> moves,
      Map<String, String> addresses, boolean mirror, List<String> peers, MirrorOrigin origin) {
    public Header {
      addresses = addresses == null ? Map.of() : Map.copyOf(addresses);
      peers = peers == null ? List.of() : List.copyOf(peers);
    }
    public Header(int version, String createdAt, String id, List<Move> moves, Map<String, String> addresses) {
      this(version, createdAt, id, moves, addresses, false, List.of());
    }
    public Header(int version, String createdAt, String id, List<Move> moves, Map<String, String> addresses,
        boolean mirror, List<String> peers) {
      this(version, createdAt, id, moves, addresses, mirror, peers, null);
    }
    public Header(int version, String createdAt, String id, List<Move> moves) {
      this(version, createdAt, id, moves, Map.of());
    }
    public Header withMoves(List<Move> value) { return new Header(formatVersion, createdAt, id, value, addresses, mirror, peers, origin); }
    public Header withAddresses(Map<String, String> value) { return new Header(formatVersion, createdAt, id, moves, value, mirror, peers, origin); }
    public Header withPeers(List<String> value) { return new Header(formatVersion, createdAt, id, moves, addresses, mirror, value, origin); }
    public Header withOrigin(MirrorOrigin value) { return new Header(formatVersion, createdAt, id, moves, addresses, true, peers, value); }
  }
  /** Mirror policy state belongs to the copy; the origin's session manifests remain unchanged. */
  public record MirrorOrigin(String storeId, String url, String lastSync, List<String> pinnedSessions,
      Map<String, MirrorSession> sessions) {
    public MirrorOrigin {
      pinnedSessions = pinnedSessions == null ? List.of() : List.copyOf(pinnedSessions);
      sessions = sessions == null ? Map.of() : Map.copyOf(sessions);
    }
  }
  public record MirrorSession(String path, String lastSync, boolean complete, boolean growing) {}
  public record Move(String originalPath, String movedTo, String movedAt) {}
  public record Contact(String address, String hostKeyFingerprint, String seenAt) {}
  public record Robot(String id, String serialNumber, String name, String comments, String basis,
      List<Contact> contacts) {
    public Robot { contacts = contacts == null ? List.of() : List.copyOf(contacts); }
    public Robot(String id, String serialNumber, String name, String comments, String basis) {
      this(id, serialNumber, name, comments, basis, List.of());
    }
  }
  public record IdentityConflict(String path, String loggedSerial, String deviceSerial) {}
  public record Conflict(String field, String localValue, String peerValue, String peerStoreId) {}
  public record Session(String id, String startedAt, String endedAt, String startBasis,
      String event, String matchType, Integer matchNumber, Integer teamNumber, List<LogFile> files,
      OpenCapture openCapture, String endReason, DeviceIdentity deviceIdentity, List<IdentityConflict> identityConflicts,
      List<Conflict> conflicts, org.triplehelix.wpilogmcp.capture.CaptureStats captureStats, SystemLogState systemLogs) {
    public Session {
      identityConflicts = identityConflicts == null ? List.of() : List.copyOf(identityConflicts);
      conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
      systemLogs = systemLogs == null ? SystemLogState.EMPTY : systemLogs;
    }
    public Session(String id, String startedAt, String endedAt, String startBasis,
        String event, String matchType, Integer matchNumber, Integer teamNumber, List<LogFile> files,
        OpenCapture openCapture, String endReason, DeviceIdentity deviceIdentity, List<IdentityConflict> identityConflicts,
        List<Conflict> conflicts, org.triplehelix.wpilogmcp.capture.CaptureStats captureStats) {
      this(id, startedAt, endedAt, startBasis, event, matchType, matchNumber, teamNumber, files,
          openCapture, endReason, deviceIdentity, identityConflicts, conflicts, captureStats, SystemLogState.EMPTY);
    }
    public Session withSystemLogs(SystemLogState value) {
      return new Session(id, startedAt, endedAt, startBasis, event, matchType, matchNumber, teamNumber, files,
          openCapture, endReason, deviceIdentity, identityConflicts, conflicts, captureStats, value);
    }
    public Session(String id, String startedAt, String endedAt, String startBasis,
        String event, String matchType, Integer matchNumber, Integer teamNumber, List<LogFile> files,
        OpenCapture openCapture, String endReason, DeviceIdentity deviceIdentity, List<IdentityConflict> identityConflicts,
        List<Conflict> conflicts) {
      this(id, startedAt, endedAt, startBasis, event, matchType, matchNumber, teamNumber, files,
          openCapture, endReason, deviceIdentity, identityConflicts, conflicts, null);
    }
    public Session(String id, String startedAt, String endedAt, String startBasis,
        String event, String matchType, Integer matchNumber, Integer teamNumber, List<LogFile> files,
        OpenCapture openCapture, String endReason, DeviceIdentity deviceIdentity, List<IdentityConflict> identityConflicts) {
      this(id, startedAt, endedAt, startBasis, event, matchType, matchNumber, teamNumber, files,
          openCapture, endReason, deviceIdentity, identityConflicts, List.of());
    }
    public Session withFiles(List<LogFile> value) {
      return new Session(id, startedAt, endedAt, startBasis, event, matchType, matchNumber, teamNumber,
          value, openCapture, endReason, deviceIdentity, identityConflicts, conflicts, captureStats, systemLogs);
    }
    public Session(String id, String startedAt, String endedAt, String startBasis,
        String event, String matchType, Integer matchNumber, Integer teamNumber, List<LogFile> files,
        OpenCapture openCapture, String endReason) {
      this(id, startedAt, endedAt, startBasis, event, matchType, matchNumber, teamNumber, files,
          openCapture, endReason, null, List.of());
    }
    public Session(String id, String startedAt, String endedAt, String startBasis,
        String event, String matchType, Integer matchNumber, Integer teamNumber, List<LogFile> files,
        OpenCapture openCapture) {
      this(id, startedAt, endedAt, startBasis, event, matchType, matchNumber, teamNumber, files, openCapture, null);
    }
    public Session(String id, String startedAt, String endedAt, String startBasis,
        String event, String matchType, Integer matchNumber, Integer teamNumber, List<LogFile> files) {
      this(id, startedAt, endedAt, startBasis, event, matchType, matchNumber, teamNumber, files, null);
    }
  }
  /** Additive field: older readers still understand every completed file in files. */
  public record OpenCapture(String path, Provenance provenance, long sizeBytes,
      double minTimestampSec, double maxTimestampSec) {}
  public record Provenance(String kind, String originalPath, String originalName,
      String importedAt, boolean moved, String sourceRobotSerial, List<PeerCopy> copiedFrom) {
    public Provenance { copiedFrom = copiedFrom == null ? List.of() : List.copyOf(copiedFrom); }
    public Provenance(String kind, String originalPath, String originalName, String importedAt, boolean moved, String sourceRobotSerial) {
      this(kind, originalPath, originalName, importedAt, moved, sourceRobotSerial, List.of());
    }
    public Provenance(String kind, String originalPath, String originalName, String importedAt, boolean moved) {
      this(kind, originalPath, originalName, importedAt, moved, null);
    }
  }
  public record PeerCopy(String storeId, String url, String copiedAt) {}
  public record Matching(String method, String wpilogSha256, long offsetMicros,
      double confidence, double driftRateNanosPerSec, double referenceTimeSec, String identityBasis,
      org.triplehelix.wpilogmcp.sync.SyncResult synchronization) {
    public Matching(String method, String wpilogSha256, long offsetMicros, double confidence,
        double driftRateNanosPerSec, double referenceTimeSec, String identityBasis) {
      this(method, wpilogSha256, offsetMicros, confidence, driftRateNanosPerSec, referenceTimeSec, identityBasis, null);
    }
  }
  public record LogFile(String path, String sha256, long sizeBytes, String kind,
      Provenance provenance, boolean verified, double minTimestampSec, double maxTimestampSec,
      String startedAt, String endedAt, String startBasis, boolean truncated, Matching matching,
      org.triplehelix.wpilogmcp.log.RobotCandidates.Fingerprint robotFingerprint, String matchingReason) {
    public LogFile(String path, String sha256, long sizeBytes, String kind, Provenance provenance,
        boolean verified, double minTimestampSec, double maxTimestampSec, String startedAt,
        String endedAt, String startBasis, boolean truncated, Matching matching,
        org.triplehelix.wpilogmcp.log.RobotCandidates.Fingerprint robotFingerprint) {
      this(path, sha256, sizeBytes, kind, provenance, verified, minTimestampSec, maxTimestampSec,
          startedAt, endedAt, startBasis, truncated, matching, robotFingerprint, null);
    }
    public LogFile(String path, String sha256, long sizeBytes, String kind, Provenance provenance,
        boolean verified, double minTimestampSec, double maxTimestampSec, String startedAt,
        String endedAt, String startBasis, boolean truncated, Matching matching) {
      this(path, sha256, sizeBytes, kind, provenance, verified, minTimestampSec, maxTimestampSec,
          startedAt, endedAt, startBasis, truncated, matching, null);
    }
  }
}
