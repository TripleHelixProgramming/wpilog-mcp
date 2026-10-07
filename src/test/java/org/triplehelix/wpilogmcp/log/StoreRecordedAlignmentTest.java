/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.revlog.ParsedRevLog;
import org.triplehelix.wpilogmcp.store.LogStore;
import org.triplehelix.wpilogmcp.store.StoreCatalog;
import org.triplehelix.wpilogmcp.store.StoreJson;
import org.triplehelix.wpilogmcp.store.StoreManifest;
import org.triplehelix.wpilogmcp.sync.LogSynchronizer;
import org.triplehelix.wpilogmcp.sync.SyncResult;

class StoreRecordedAlignmentTest {
  @TempDir Path temp;
  @Test void aMirrorUsesRecordedOffsetsEvenWhenTheCorrelatorWouldDisagree() throws Exception {
    var calls = new AtomicInteger();
    var manager = new LogManager(new LogSynchronizer() {
      @Override public SyncResult synchronize(LogData log, ParsedRevLog rev) {
        calls.incrementAndGet(); return super.synchronize(log, rev);
      }
    });
    try {
      manager.addAllowedDirectory(temp); manager.getSyncDiskCache().setEnabled(false);
      var fixtures = FixtureLogs.generateAll(temp.resolve("fixtures"));
      var wpilog = fixtures.stream().filter(f -> f.id().equals("revlog_pair")).findFirst().orElseThrow().path();
      Path rev; try (var paths = Files.list(wpilog.getParent())) { rev = paths.filter(p -> p.toString().endsWith(".revlog")).findFirst().orElseThrow(); }
      var store = manager.stores().store(temp.resolve("mirror"));
      store.importPaths(new LogStore.Request(List.of(wpilog, rev), false, "fixture"), p -> {}).get();
      var catalog = StoreCatalog.readManaged(store.root(), manager.testGetSecurityValidator());
      var primary = catalog.files().stream().filter(f -> f.file().kind().equals("wpilog")).findFirst().orElseThrow();
      var companion = catalog.files().stream().filter(f -> f.file().kind().equals("revlog")).findFirst().orElseThrow();
      var old = companion.file(); var alignment = old.matching(); assertNotNull(alignment);
      long recorded = alignment.offsetMicros() + 123_456;
      var changed = new StoreManifest.LogFile(old.path(), old.sha256(), old.sizeBytes(), old.kind(), old.provenance(), old.verified(), old.minTimestampSec(), old.maxTimestampSec(),
          old.startedAt(), old.endedAt(), old.startBasis(), old.truncated(), new StoreManifest.Matching(alignment.method(), alignment.wpilogSha256(), recorded,
          alignment.confidence(), alignment.driftRateNanosPerSec(), alignment.referenceTimeSec(), alignment.identityBasis()), old.robotFingerprint());
      var s = companion.session();
      Files.writeString(companion.manifestPath(), StoreJson.JSON.toJson(s.withFiles(s.files().stream().map(f -> f.sha256().equals(old.sha256()) ? changed : f).toList())));
      var h = catalog.header();
      Files.writeString(store.root().resolve("store.json"), StoreJson.JSON.toJson(h.withOrigin(new StoreManifest.MirrorOrigin("origin", "http://example.test", "2026-01-01T00:00:00Z", List.of(), java.util.Map.of()))));
      manager.loadLog(primary.path().toString()); manager.waitForRevLogSync(primary.path().toString(), 30_000);
      assertEquals(0, calls.get(), "A mirror must never run the correlator");
      var synchronizedLogs = manager.getSynchronizedLogs(primary.path().toString());
      assertEquals(recorded, synchronizedLogs.revlogs().get(0).syncResult().offsetMicros());
    } finally { manager.shutdown(); }
  }
}
