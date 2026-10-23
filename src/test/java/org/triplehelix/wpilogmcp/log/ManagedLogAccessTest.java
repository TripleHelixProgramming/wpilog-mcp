/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.CaptureIndex;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.read.ReadQuery;
import org.triplehelix.wpilogmcp.read.server.ManagedLogAccess;

/** Managed admission must share the manager's identity and mapping, including a live prefix;
 * the neutral reader must not reopen a file behind the manager's leases or reload bookkeeping. */
class ManagedLogAccessTest {
  @TempDir Path temp;
  private LogManager manager;

  @AfterEach void shutdown() { if (manager != null) manager.shutdown(); }

  private void allow(Path directory) throws Exception {
    manager = new LogManager();
    manager.addAllowedDirectory(directory.toRealPath());
  }

  private Path numbers(String name) throws Exception {
    var path = temp.resolve(name);
    try (var writer = new WpilogWriter(path, "")) {
      int id = writer.start("/x", "int64", "{\"unit\":\"count\"}", 0);
      writer.append(id, 1_000_001, WpilogWriter.encodeInt64(9_007_199_254_740_993L));
      writer.append(id, 2_000_003, WpilogWriter.encodeInt64(9_007_199_254_740_995L));
    }
    return path.toRealPath();
  }

  @Test void aLostCatalogStampCannotTurnADiskFileIntoALivePrefix() throws Exception {
    var path = numbers("stamp.wpilog"); allow(temp);
    try (var first = manager.acquire(path.toString())) { assertNotNull(first.snapshot()); }
    // Eviction can remove this map entry after the cache lookup but before retain. Remove only
    // the stamp to force that ordering without depending on the heap or the eviction timer.
    var field = LogManager.class.getDeclaredField("loaded"); field.setAccessible(true);
    ((java.util.Map<?, ?>) field.get(manager)).clear();
    try (var access = new ManagedLogAccess(manager); var read = access.open(path)) {
      assertNotNull(read.boundary(), "A disk snapshot always has a file boundary");
    }
  }

  @Test void nativeOpenDoesNotStartSyncButLaterLegacyAcquisitionStillDoes() throws Exception {
    var path = numbers("read-only.wpilog"); allow(temp);
    var cacheField = LogManager.class.getDeclaredField("syncCache"); cacheField.setAccessible(true);
    var syncs = (Map<?, ?>) cacheField.get(manager);
    try (var access = new ManagedLogAccess(manager); var nativeRead = access.open(path)) {
      assertTrue(syncs.isEmpty(), "Native admission must not even start REV discovery or its placeholder");
      try (var legacy = access.acquire(path.toString())) {
        assertSame(nativeRead.log(), legacy.log());
        assertTrue(syncs.containsKey(path.toString()), "Legacy admission still starts synchronization of a natively cached log");
      }
    }
  }

  @Test void managedOpensReuseTheOriginalCachedLogAndItsBoundary() throws Exception {
    var path = numbers("shared.wpilog"); allow(temp);
    try (var original = manager.acquire(path.toString());
         var access = new ManagedLogAccess(manager);
         var first = access.open(path);
         var second = access.open(path)) {
      assertSame(original.log(), first.log(), "Reload hooks depend on the original cached object identity");
      assertSame(first.log(), second.log(), "Opening the shared interface must not build a second log");
      assertEquals(original.snapshot(), first.boundary());
      assertEquals(first.boundary(), first.snapshot());
      assertEquals(path, first.path());
      assertEquals(2, first.sampleCount("/x"));
      assertEquals(8, first.indexBytes(), "Reuse the compact index instead of building another timestamp array");
      assertEquals("{\"unit\":\"count\"}", first.entries().get("/x").metadata());
      try (var cursor = first.read(ReadQuery.all("/x"))) {
        var batch = cursor.next();
        assertEquals(List.of(1_000_001L, 2_000_003L), batch.samples().stream().map(s -> s.timestampUs()).toList());
        assertEquals(List.of(9_007_199_254_740_993L, 9_007_199_254_740_995L),
            batch.samples().stream().map(s -> s.value()).toList());
        assertTrue(batch.complete());
      }
    }
  }

  @Test void managedOpenKeepsTheManagersDirectoryAdmission() throws Exception {
    var allowed = Files.createDirectory(temp.resolve("allowed"));
    var outside = numbers("outside.wpilog"); allow(allowed);
    try (var access = new ManagedLogAccess(manager)) {
      assertThrows(IOException.class, () -> {
        try (var ignored = access.open(outside)) { }
      });
      assertTrue(manager.getAllLoadedLogs().isEmpty(), "A refused source was not admitted to the shared cache");
    }
  }

  @Test void legacyAcquisitionKeepsItsMappingUntilTheCallerClosesAfterManagerShutdown() throws Exception {
    var path = numbers("shutdown-held.wpilog"); allow(temp);
    try (var use = manager.readAccess().acquire(path.toString())) {
      var original = assertInstanceOf(LazyParsedLog.class, use.log());
      assertEquals(0, original.cachedEntryCount(), "Exercise a mapped read after shutdown, not a cached value");
      manager.shutdown();
      assertThrows(IOException.class, () -> LogFileAccess.move(List.of(path)),
          "Shutting down admission must not end a legacy call's existing use");
      assertEquals(List.of(9_007_199_254_740_993L, 9_007_199_254_740_995L),
          original.values().get("/x").stream().map(TimestampedValue::value).toList());
      use.close();
      try (var move = LogFileAccess.move(List.of(path))) {
        Files.move(path, path.resolveSibling("shutdown-released.wpilog"));
      }
    }
  }

  @Test void admittedCursorOutlivesSnapshotAccessAndCacheEvictionUntilItsLastClose() throws Exception {
    var path = numbers("held.wpilog"); allow(temp);
    try (var access = new ManagedLogAccess(manager);
         var snapshot = access.open(path);
         var cursor = snapshot.read(ReadQuery.all("/x"))) {
      snapshot.close(); access.close();
      assertThrows(IOException.class, () -> snapshot.read(ReadQuery.all("/x")));
      assertThrows(IOException.class, () -> access.open(path));
      assertTrue(manager.unloadLog(path.toString()));
      assertThrows(IOException.class, () -> LogFileAccess.move(List.of(path)),
          "The surviving cursor, rather than the retired cache, still owns the mapping use");
      var batch = cursor.next();
      assertEquals(List.of(9_007_199_254_740_993L, 9_007_199_254_740_995L),
          batch.samples().stream().map(s -> s.value()).toList());
      assertTrue(batch.complete());
      cursor.close(); cursor.close();
      try (var move = LogFileAccess.move(List.of(path))) {
        var destination = path.resolveSibling("released.wpilog");
        Files.move(path, destination);
        assertTrue(Files.exists(destination), "The last cursor must release every mapping on Windows too");
      }
    }
  }

  @Test void nativeWindowExcludesDamageWhileLegacyViewKeepsWholeEntryDiagnostics() throws Exception {
    var path = temp.resolve("diagnostics.wpilog");
    try (var writer = new WpilogWriter(path, "")) {
      int id = writer.start("/x", "double", "", 0);
      writer.append(id, 0, new byte[]{1});
      writer.append(id, 10, WpilogWriter.encodeDouble(4));
    }
    allow(temp);
    try (var access = new ManagedLogAccess(manager); var snapshot = access.open(path);
         var cursor = snapshot.read(ReadQuery.window("/x", 10, 10, true))) {
      var batch = cursor.next();
      assertEquals(1, batch.samples().size()); assertEquals(4., batch.samples().get(0).value());
      assertTrue(batch.problems().isEmpty());
      var problem = snapshot.log().decodeProblem("/x").orElseThrow();
      assertEquals(2, problem.totalRecords()); assertEquals(1, problem.failedRecords());
      assertEquals("record of 1 bytes cannot be read as double", problem.message());
      assertEquals(List.of(new TimestampedValue(0.000010, 4.)), snapshot.log().values().get("/x"));
    }
  }

  @Test void liveCursorKeepsItsExactPrefixAndALaterOpenSeesTheAppend() throws Exception {
    allow(temp);
    var path = temp.toRealPath().resolve("live.wpilog");
    var now = new java.util.concurrent.atomic.AtomicReference<Double>(3_000_000.0);
    var index = new CaptureIndex((address, start) -> path, manager, 10_000_000, now::get);
    var loop = new ManualScheduler();
    var topic = new Announce("/x", 1, "int", null, new JsonObject());
    try (var writer = new CaptureWriter(Clock.systemUTC(), loop, CapturePolicy.ALL, index);
         var access = new ManagedLogAccess(manager)) {
      writer.connected(RobotAddress.uri("127.0.0.1", 5810, "managed-read-test"), "networktables.first.wpi.edu");
      writer.timeSync(1_000_000, 0); writer.announce(topic);
      writer.value(topic, new ValueFrame(1, 1_000_001, 2, 9_007_199_254_740_993L), 0);
      try (var first = access.open(path); var cursor = first.read(ReadQuery.all("NT:/x"))) {
        assertInstanceOf(LiveLog.View.class, first.log());
        assertNull(first.snapshot(), "Growing recordings have no closed-file validator or ETag");
        assertEquals(3., first.log().timeScopeEnd());
        writer.announce(new Announce("/later", 2, "int", null, new JsonObject()));
        writer.value(topic, new ValueFrame(1, 2_000_003, 2, 9_007_199_254_740_995L), 0);
        now.set(4_000_000.0);
        var batch = cursor.next();
        assertEquals(List.of(1_000_001L), batch.samples().stream().map(s -> s.timestampUs()).toList());
        assertEquals(9_007_199_254_740_993L, batch.samples().get(0).value());
        assertTrue(batch.complete()); assertEquals(1, first.sampleCount("NT:/x"));
        assertFalse(first.entries().containsKey("NT:/later"));
        assertEquals(3., first.log().timeScopeEnd());
        try (var after = access.open(path); var updated = after.read(ReadQuery.all("NT:/x"))) {
          assertEquals(List.of(1_000_001L, 2_000_003L),
              updated.next().samples().stream().map(s -> s.timestampUs()).toList());
          assertEquals(2, after.sampleCount("NT:/x"));
          assertTrue(after.entries().containsKey("NT:/later"));
          assertEquals(4., after.log().timeScopeEnd());
        }
      }
    }
  }

  @Test void mcpAndDataConsumersUseTheSharedAdmissionBoundary() throws Exception {
    var sources = Map.of("tools/LogRequiringTool.java", 1, "tools/CoreTools.java", 1,
        "tools/RobotAnalysisTools.java", 2, "tools/SystemLogClocks.java", 2,
        "capture/context/SystemTextPublisher.java", 1, "mcp/DataEndpoint.java", 2);
    var root = Path.of("src/main/java/org/triplehelix/wpilogmcp");
    var direct = Pattern.compile("\\b(?:logManager|manager)\\s*\\.\\s*acquire\\s*\\(");
    var shared = Pattern.compile("\\b(?:logAccess|access)\\s*\\.\\s*(?:open|acquire)\\s*\\(");
    for (var source : sources.entrySet()) {
      String text = Files.readString(root.resolve(source.getKey()));
      assertFalse(direct.matcher(text).find(), source.getKey() + " bypasses shared read admission");
      assertEquals(source.getValue().longValue(), shared.matcher(text).results().count(),
          source.getKey() + " must keep each existing log acquisition behind the shared owner");
    }
    // Keep future tools behind the boundary too; the positive checks above prevent deleting
    // an acquisition merely to satisfy this prohibition.
    var bypass = Pattern.compile("(?:\\blogManager|\\bgetLogManager\\(\\)|"
        + "\\bLogManager\\.getInstance\\(\\))\\s*\\.\\s*(?:acquire|loadLog|getOrLoad)\\s*\\(");
    for (var directory : List.of(root.resolve("tools"), root.resolve("data"))) {
      try (var files = Files.walk(directory)) {
        for (var file : files.filter(p -> p.toString().endsWith(".java")).toList())
          assertFalse(bypass.matcher(Files.readString(file)).find(), file + " bypasses shared admission");
      }
    }
  }
}
