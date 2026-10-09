/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.capture.CaptureLease;
import org.triplehelix.wpilogmcp.log.LogScan;
import org.triplehelix.wpilogmcp.log.ScopedLogReader;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

/**
 * Runs on the store queue before NT4 starts. An open manifest is only a recovery candidate:
 * an OS-backed writer lease, not a stale size or a process ID, decides whether its bytes are final.
 * A crash can leave a partial final record; the normal scanner supplies the same readable prefix
 * and truncation flag the tools will see. Failed reads never acquire a verified hash.
 */
final class CaptureRecovery {
  private static final String STOPPED = "server stopped while recording";
  private CaptureRecovery() {}

  static void run(StoreFiles io, Path root) throws IOException {
    var robots = io.check(root.resolve("robots"));
    if (!Files.isDirectory(robots)) return;
    try (var walk = Files.walk(robots)) {
      for (var manifest : walk.filter(p -> p.getFileName().toString().equals("session.json"))
          .filter(Files::isRegularFile).sorted().toList()) {
        try {
          var session = io.read(manifest, Session.class);
          if (session.openCapture() != null) recover(io, manifest, session);
        } catch (IOException | RuntimeException e) {
          LoggerFactory.getLogger(CaptureRecovery.class).error("Capture recovery could not update {}: {}", manifest, e.getMessage());
        }
      }
    }
  }

  // A queued recorder summary may predate the durable prefix. Recovery clears it rather
  // than present an old count as final; ordinary log tools still compute from all records.
  private static void recover(StoreFiles io, Path manifest, Session session) throws IOException {
    var open = session.openCapture();
    try {
      var file = io.resolve(manifest.getParent(), open.path());
      io.check(CaptureLease.lockPath(file));
      var claimed = CaptureLease.tryAcquire(file);
      if (claimed.isEmpty()) return;
      try (var owner = claimed.get()) {
        LogScan scan;
        try (var reader = new ScopedLogReader(file)) { scan = LogScan.of(reader.reader(), file); }
        if (scan.damaged()) throw new IOException(scan.truncationMessage());
        long size = Files.size(file);
        String ended = Files.getLastModifiedTime(file).toInstant().toString();
        String hash = StoreFiles.hash(file);
        var files = new ArrayList<>(session.files());
        files.removeIf(f -> f.path().equals(open.path()));
        files.add(new LogFile(open.path(), hash, size, "wpilog",
            new Provenance("captured", null, file.getFileName().toString(), session.startedAt(), false),
            true, scan.minTimestamp(), scan.maxTimestamp(), session.startedAt(), ended,
            session.startBasis(), scan.truncated(), null));
        io.write(manifest, new Session(session.id(), session.startedAt(), ended, session.startBasis(),
            session.event(), session.matchType(), session.matchNumber(), session.teamNumber(),
            java.util.List.copyOf(files), null, STOPPED, session.deviceIdentity(), session.identityConflicts(), session.conflicts(), null, session.systemLogs()));
        LoggerFactory.getLogger(CaptureRecovery.class).info("Recovered capture {}: {}", file, STOPPED);
      }
    } catch (IOException | RuntimeException e) {
      String reason = STOPPED + "; recovery failed: " + e.getMessage();
      LoggerFactory.getLogger(CaptureRecovery.class).error("Capture {}: {}", manifest, reason);
      io.write(manifest, new Session(session.id(), session.startedAt(), session.endedAt(), session.startBasis(),
          session.event(), session.matchType(), session.matchNumber(), session.teamNumber(),
          session.files(), open, reason, session.deviceIdentity(), session.identityConflicts(), session.conflicts(), null, session.systemLogs()));
    }
  }
}
