/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;
import static org.triplehelix.wpilogmcp.fixtures.MirroredOffsets.*;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.read.ReadQuery;
import org.triplehelix.wpilogmcp.read.catalog.SessionSnapshot;
import org.triplehelix.wpilogmcp.read.catalog.StoreReadCatalog;
import org.triplehelix.wpilogmcp.read.rev.RecordedRevRead;
import org.triplehelix.wpilogmcp.revlog.ParsedRevLog;
import org.triplehelix.wpilogmcp.store.CaptureRecording;
import org.triplehelix.wpilogmcp.store.StoreManifest.Matching;
import org.triplehelix.wpilogmcp.sync.ConfidenceLevel;
import org.triplehelix.wpilogmcp.sync.LogSynchronizer;
import org.triplehelix.wpilogmcp.sync.SyncMethod;
import org.triplehelix.wpilogmcp.sync.SyncResult;

/**
 * A laptop's mirror holds the robot log and a REV log the pit pulled, not the pit's capture they
 * were both measured against ({@link org.triplehelix.wpilogmcp.fixtures.MirroredOffsets} sets the
 * clocks and solves them by hand). Every reader must place the REV samples on the robot log's clock
 * at those times, from the records alone.
 */
class CombinedRevAlignmentTest {
  @TempDir Path temp;
  @Test void theReaderLibraryPlacesTheRevSamplesOnTheRobotLogsClock() throws Exception {
    var mirror = write(temp.resolve("reader"), "laptop-reader", REV_WHILE_RECORDING, null, List.of(RECORDING));
    try (var catalog = new StoreReadCatalog(List.of(mirror.root()));
         var read = catalog.openRev(mirror.rev(), mirror.robot())) {
      assertEquals(RecordedRevRead.State.RECORDED, read.state(), read.reason());
      assertEquals(SyncMethod.COMBINED, read.alignment().method());
      assertEquals(ConfidenceLevel.MEDIUM, read.alignment().confidenceLevel());
      var key = read.signals().keySet().stream().filter(k -> k.endsWith("/AppliedOutput")).findFirst().orElseThrow();
      var raw = read.rawValues(key); var aligned = read.values(key);
      assertEquals(REV_TIMES_US.length, aligned.size());
      for (int i = 0; i < raw.size(); i++) {
        assertEquals(REV_TIMES_US[i] / 1e6, raw.get(i).timestamp(), 1e-9);
        assertEquals(onRobotLog(onCapture(raw.get(i).timestamp())), aligned.get(i).timestamp(), 1e-6);
      }
    }
  }

  @Test void theServerPairsThemWithoutCorrelatingAndTheToolsReportTheCombination() throws Exception {
    var mirror = write(temp.resolve("server"), "laptop-server", REV_WHILE_RECORDING, null, List.of(RECORDING));
    var calls = new AtomicInteger();
    var manager = new LogManager(new LogSynchronizer() {
      @Override public SyncResult synchronize(LogData log, ParsedRevLog rev) { calls.incrementAndGet(); return super.synchronize(log, rev); }
    });
    try {
      manager.addAllowedDirectory(temp); manager.getSyncDiskCache().setEnabled(false);
      manager.loadLog(mirror.robot().toString()); manager.waitForRevLogSync(mirror.robot().toString(), 30_000);
      assertEquals(0, calls.get(), "A store's files are never correlated afresh");
      var synced = manager.getSynchronizedLogs(mirror.robot().toString());
      assertEquals(1, synced.revlogCount());
      var result = synced.revlogs().get(0).syncResult();
      assertEquals(SyncMethod.COMBINED, result.method(), result.explanation());
      for (long us : REV_TIMES_US) assertEquals(onRobotLog(onCapture(us / 1e6)), result.toFpgaTime(us / 1e6), 1e-6);
    } finally { manager.shutdown(); }

    var shared = LogManager.getInstance(); var directory = LogDirectory.getInstance();
    var previous = shared.getAllowedDirectories(); var listed = directory.getLogDirectories();
    shared.addAllowedDirectory(temp); shared.getSyncDiskCache().setEnabled(false);
    try {
      directory.setLogDirectories(List.of(mirror.root().toString()));
      var registry = new org.triplehelix.wpilogmcp.mcp.ToolRegistry();
      org.triplehelix.wpilogmcp.tools.CoreTools.registerAll(registry);
      org.triplehelix.wpilogmcp.tools.RevLogTools.registerAll(registry);
      var listing = registry.getTool("list_available_logs").execute(new JsonObject()).getAsJsonObject();
      var robotEntry = listing.getAsJsonArray("logs").asList().stream().map(e -> e.getAsJsonObject())
          .filter(o -> o.get("path").getAsString().equals(mirror.robot().toString())).findFirst().orElseThrow();
      var companions = robotEntry.getAsJsonArray("revlogs");
      assertEquals(1, companions.size(), robotEntry.toString());
      assertEquals("COMBINED", companions.get(0).getAsJsonObject().get("sync_method").getAsString());
      assertNull(companions.get(0).getAsJsonObject().get("read_error"));

      shared.loadLog(mirror.robot().toString()); shared.waitForRevLogSync(mirror.robot().toString(), 30_000);
      var args = new JsonObject(); args.addProperty("path", mirror.robot().toString());
      var status = registry.getTool("sync_status").execute(args).getAsJsonObject();
      var sync = status.getAsJsonArray("revlogs").get(0).getAsJsonObject().getAsJsonObject("sync");
      assertEquals("COMBINED", sync.get("method").getAsString());
      assertEquals("medium", sync.get("confidence_level").getAsString());
      var combined = sync.getAsJsonObject("combined");
      assertEquals(55.0, combined.get("max_error_ms").getAsDouble());
      assertEquals("the capture recording started " + WHEN + " by store " + PIT, combined.get("reference").getAsString());
      var measurements = combined.getAsJsonArray("measurements");
      assertEquals(List.of(REV, ROBOT), measurements.asList().stream().map(m -> m.getAsJsonObject().get("file").getAsString()).toList());
      assertEquals(List.of(false, true), measurements.asList().stream().map(m -> m.getAsJsonObject().get("reversed").getAsBoolean()).toList());
      assertEquals("up to 55", status.getAsJsonObject("_metadata").get("timing_accuracy_ms").getAsString());

      var key = "REV/" + shared.getSynchronizedLogs(mirror.robot().toString()).revlogs().get(0).revlog().signals().keySet().stream()
          .filter(k -> k.endsWith("/AppliedOutput")).findFirst().orElseThrow();
      args.addProperty("signal_key", key);
      var data = registry.getTool("get_revlog_data").execute(args).getAsJsonObject();
      assertEquals("COMBINED", data.get("sync_method").getAsString(), data.toString());
      var points = data.getAsJsonArray("data");
      assertEquals(REV_TIMES_US.length, points.size());
      for (int i = 0; i < points.size(); i++) {
        assertEquals(onRobotLog(onCapture(REV_TIMES_US[i] / 1e6)), points.get(i).getAsJsonObject().get("timestamp").getAsDouble(), 1e-6);
      }
      assertEquals("up to 55", data.getAsJsonObject("_metadata").get("timing_accuracy_ms").getAsString());
      assertTrue(data.toString().contains("combining offsets recorded against"), "a medium-confidence combination is flagged: " + data);
    } finally {
      shared.unloadAllLogs(); shared.clearAllowedDirectories(); previous.forEach(shared::addAllowedDirectory);
      directory.setLogDirectories(listed.stream().map(Path::toString).toList());
    }
  }

  @Test void aRevLogTheManifestNoLongerPairsStaysUnsynchronizedRatherThanCorrelated() throws Exception {
    // The companions were found from one read of the manifest and their offsets come from another;
    // a REV log the second read no longer pairs must not fall through to a fresh correlation.
    var mirror = write(temp.resolve("unpaired"), "laptop-unpaired",
        against("f".repeat(64), null, measured(95_123_456, 0.9, ConfidenceLevel.HIGH, 150_000, 20)), null, List.of(RECORDING));
    var manager = new LogManager();
    try {
      manager.addAllowedDirectory(temp); manager.getSyncDiskCache().setEnabled(false);
      var log = manager.loadLog(mirror.robot().toString());
      var info = LogDirectory.getInstance().extractRevLogInfo(mirror.rev());
      var recorded = manager.recordedStoreOffsets(log, List.of(info));
      var result = recorded.get(info.path());
      assertNotNull(result, "every candidate of a store log gets a recorded result");
      assertFalse(result.isSuccessful());
      assertTrue(result.explanation().contains("no longer pairs"), result.explanation());
    } finally { manager.shutdown(); }
  }

  @Test void aSessionReadOnTheRobotLogsClockIncludesALogMeasuredAgainstTheSameCapture() throws Exception {
    var otherMatch = against(CAPTURE, null, measured(7_500, 0.95, ConfidenceLevel.HIGH, 0, 0));
    var mirror = write(temp.resolve("session"), "laptop-session", REV_WHILE_RECORDING, otherMatch, List.of(RECORDING));
    try (var catalog = new StoreReadCatalog(List.of(mirror.root()));
         var union = catalog.openSession("laptop-session", "session-id", ROBOT, SessionSnapshot.Overlap.KEEP_ALL)) {
      assertEquals(List.of(), union.excludedSources());
      var source = union.sources().stream().filter(s -> s.path().equals(mirror.other().toString())).findFirst().orElseThrow();
      assertEquals(1, source.clock().size());
      assertEquals(SyncMethod.COMBINED, source.clock().get(0).method());
      var times = new ArrayList<Double>();
      try (var cursor = union.read(ReadQuery.all("/Arm/Angle"))) {
        for (var batch = cursor.next(); ; batch = cursor.next()) {
          for (var sample : batch.samples()) times.add(sample.timestampUs() / 1e6);
          if (batch.complete()) break;
        }
      }
      assertEquals(11, times.size());
      for (int i = 0; i < times.size(); i++) assertEquals(onRobotLog(otherOnCapture(100 + i)), times.get(i), 1e-6);
    }
  }

  @Test void twoRecordingsAndAnOffsetThatNamesNeitherAreRefusedWithThatReason() throws Exception {
    var legacy = against(null, null, measured(95_123_456, 0.9, ConfidenceLevel.HIGH, 150_000, 20));
    var mirror = write(temp.resolve("refused"), "laptop-refused", legacy, legacy, List.of(RECORDING, new CaptureRecording(PIT, "2026-10-17T11:00:00Z")));
    try (var catalog = new StoreReadCatalog(List.of(mirror.root()))) {
      try (var read = catalog.openRev(mirror.rev(), mirror.robot())) {
        assertEquals(RecordedRevRead.State.UNSYNCHRONIZED, read.state());
        assertTrue(read.reason().contains("holds 2 recordings"), read.reason());
      }
      try (var union = catalog.openSession("laptop-refused", "session-id", ROBOT, SessionSnapshot.Overlap.KEEP_ALL)) {
        var excluded = union.excludedSources().stream().filter(s -> s.path().equals(mirror.other().toString())).findFirst().orElseThrow();
        assertTrue(excluded.reason().contains("holds 2 recordings"), excluded.reason());
      }
    }
    var single = write(temp.resolve("accepted"), "laptop-accepted", legacy, null, List.of(RECORDING));
    try (var catalog = new StoreReadCatalog(List.of(single.root()));
         var read = catalog.openRev(single.rev(), single.robot())) {
      assertEquals(RecordedRevRead.State.RECORDED, read.state(), read.reason());
    }
  }
}
