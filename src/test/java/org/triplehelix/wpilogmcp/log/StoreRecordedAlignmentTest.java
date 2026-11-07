/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.revlog.ParsedRevLog;
import org.triplehelix.wpilogmcp.store.LogStore;
import org.triplehelix.wpilogmcp.store.StoreCatalog;
import org.triplehelix.wpilogmcp.store.StoreJson;
import org.triplehelix.wpilogmcp.store.StoreManifest;
import org.triplehelix.wpilogmcp.sync.LogSynchronizer;
import org.triplehelix.wpilogmcp.sync.SyncResult;

class StoreRecordedAlignmentTest {
  @TempDir Path temp;
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"confidence", "offset", "method"})
  void aCorruptRecordedAlignmentNamesItsManifestAndLeavesTheCompanionUnsynchronized(String fault) throws Exception {
    var manager = LogManager.getInstance(); var directory = LogDirectory.getInstance();
    var previous = manager.getAllowedDirectories(); var listed = directory.getLogDirectories();
    manager.addAllowedDirectory(temp);
    try {
      var wpilog = ImportFixture.sameClockRevPair(Files.createDirectory(temp.resolve("fixtures")), "pair.wpilog");
      Path rev;
      try (var paths = Files.list(wpilog.getParent())) {
        rev = paths.filter(p -> p.toString().endsWith(".revlog")).findFirst().orElseThrow();
      }
      var store = manager.stores().store(temp.resolve("store"));
      store.importPaths(new LogStore.Request(List.of(wpilog, rev), false, "fixture"), p -> {}).get();
      var catalog = StoreCatalog.readManaged(store.root(), manager.testGetSecurityValidator());
      var primary = catalog.files().stream().filter(f -> f.file().kind().equals("wpilog")).findFirst().orElseThrow();
      var companion = catalog.files().stream().filter(f -> f.file().kind().equals("revlog")).findFirst().orElseThrow();
      var json = com.google.gson.JsonParser.parseString(Files.readString(companion.manifestPath())).getAsJsonObject();
      var match = json.getAsJsonArray("files").asList().stream().map(e -> e.getAsJsonObject())
          .filter(f -> f.get("kind").getAsString().equals("revlog")).findFirst().orElseThrow().getAsJsonObject("matching");
      switch (fault) {
        case "confidence" -> match.addProperty("confidence", 2);
        case "offset" -> match.getAsJsonObject("synchronization").addProperty("offset_micros", match.get("offset_micros").getAsLong() + 1);
        case "method" -> match.getAsJsonObject("synchronization").addProperty("method", "NOT_A_METHOD");
        default -> throw new AssertionError(fault);
      }
      Files.writeString(companion.manifestPath(), json.toString());
      directory.setLogDirectories(List.of(store.root().toString()));
      var registry = new org.triplehelix.wpilogmcp.mcp.ToolRegistry();
      org.triplehelix.wpilogmcp.tools.CoreTools.registerAll(registry);
      org.triplehelix.wpilogmcp.tools.RevLogTools.registerAll(registry);
      var listing = registry.getTool("list_available_logs").execute(new com.google.gson.JsonObject()).getAsJsonObject();
      assertFalse(listing.toString().contains("Internal error"), listing.toString());
      var error = listing.getAsJsonArray("logs").get(0).getAsJsonObject().getAsJsonArray("revlogs")
          .get(0).getAsJsonObject().get("read_error");
      assertNotNull(error, "The companion must explain its invalid recorded alignment");
      assertTrue(error.getAsString().contains(companion.manifestPath().toString()));
      manager.loadLog(primary.path().toString()); manager.waitForRevLogSync(primary.path().toString(), 30_000);
      var synced = manager.getSynchronizedLogs(primary.path().toString());
      assertEquals(1, synced.revlogCount());
      assertFalse(synced.revlogs().get(0).syncResult().isSuccessful());
      assertTrue(synced.revlogs().get(0).syncResult().explanation().contains(companion.manifestPath().toString()));
      var args = new com.google.gson.JsonObject(); args.addProperty("path", primary.path().toString());
      args.addProperty("signal_key", "REV/" + synced.revlogs().get(0).revlog().signals().keySet().iterator().next());
      for (String tool : List.of("sync_status", "list_revlog_signals", "get_revlog_data")) {
        var result = registry.getTool(tool).execute(args).getAsJsonObject();
        assertFalse(result.toString().contains("Internal error"), tool + ": " + result);
        assertTrue(result.toString().contains("Invalid recorded alignment"), tool + ": " + result);
        if (!tool.equals("sync_status")) assertEquals("not_applicable", result.get("status").getAsString());
      }
    } finally {
      manager.unloadAllLogs(); manager.clearAllowedDirectories(); previous.forEach(manager::addAllowedDirectory);
      directory.setLogDirectories(listed.stream().map(Path::toString).toList());
    }
  }

  @Test void aMirrorUsesRecordedOffsetsEvenWhenTheCorrelatorWouldDisagree() throws Exception {
    var calls = new AtomicInteger();
    var manager = new LogManager(new LogSynchronizer() {
      @Override public SyncResult synchronize(LogData log, ParsedRevLog rev) {
        calls.incrementAndGet(); return super.synchronize(log, rev);
      }
    });
    try {
      manager.addAllowedDirectory(temp); manager.getSyncDiskCache().setEnabled(false);
      var wpilog = ImportFixture.sameClockRevPair(Files.createDirectory(temp.resolve("fixtures")), "pair.wpilog");
      Path rev; try (var paths = Files.list(wpilog.getParent())) { rev = paths.filter(p -> p.toString().endsWith(".revlog")).findFirst().orElseThrow(); }
      var store = manager.stores().store(temp.resolve("mirror"));
      store.importPaths(new LogStore.Request(List.of(wpilog, rev), false, "fixture"), p -> {}).get();
      var catalog = StoreCatalog.readManaged(store.root(), manager.testGetSecurityValidator());
      var primary = catalog.files().stream().filter(f -> f.file().kind().equals("wpilog")).findFirst().orElseThrow();
      var companion = catalog.files().stream().filter(f -> f.file().kind().equals("revlog")).findFirst().orElseThrow();
      var old = companion.file(); var alignment = old.matching(); assertNotNull(alignment);
      // A legacy receipt stays authoritative even outside the current import admission gate.
      long recorded = alignment.offsetMicros() + 15_300_000;
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
