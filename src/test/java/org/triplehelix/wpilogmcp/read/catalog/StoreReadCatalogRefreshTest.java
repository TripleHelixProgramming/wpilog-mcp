/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.catalog;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.ReadOptions;
import org.triplehelix.wpilogmcp.read.ReadProblem;
import org.triplehelix.wpilogmcp.store.StoreJson;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

/** Physical mutations at the scan/validation boundary avoid timing-dependent race tests. */
class StoreReadCatalogRefreshTest {
  @TempDir Path temp;
  static final String WHEN = "2026-01-01T00:00:00Z";
  Path payload, manifest;

  void store(boolean open) throws Exception {
    temp = temp.toRealPath(); header("store");
    var robot = Files.createDirectories(temp.resolve("robots/fixture"));
    Files.writeString(robot.resolve("robot.json"), StoreJson.JSON.toJson(new Robot("fixture", "fixture", null, null, "logged")));
    var session = Files.createDirectories(robot.resolve("sessions/2026-01-01/session"));
    payload = session.resolve("capture.wpilog"); Files.write(payload, new byte[128]);
    manifest = session.resolve("session.json");
    var provenance = new Provenance("captured", null, "capture.wpilog", WHEN, false);
    var capture = new OpenCapture("capture.wpilog", provenance, 128, 0, 1);
    var closed = new LogFile("capture.wpilog", "a".repeat(64), 128, "wpilog", provenance, true, 0, 1, WHEN, WHEN, "pit_clock", false, null);
    Files.writeString(manifest, StoreJson.JSON.toJson(new Session("session", WHEN, WHEN, "pit_clock", null, null, null, null,
        open ? List.of() : List.of(closed), open ? capture : null)));
  }
  void header(String id) throws Exception {
    Files.writeString(temp.resolve("store.json"), StoreJson.JSON.toJson(new Header(1, WHEN, id, List.of())));
  }
  StoreReadCatalog catalog(StoreReadCatalog.ScanObserver observer) throws Exception {
    return new StoreReadCatalog(ReadOptions.defaults(List.of(temp)), null, StoreReadCatalog.DEFAULT_SESSION_INDEX_BYTES, observer);
  }
  @Test void appendDuringEveryScanIsAdmittedWithoutRetryAndLaterGrowthRefreshesFacts() throws Exception {
    store(true); var scans = new AtomicInteger();
    try (var catalog = catalog(() -> { scans.incrementAndGet(); Files.write(payload, new byte[16], StandardOpenOption.APPEND); })) {
      var first = catalog.snapshot();
      assertEquals(1, scans.get()); assertEquals(128, first.stores().get(0).openCaptures().get(0).file().sizeBytes());
      var second = catalog.snapshot();
      assertNotSame(first, second, "The cache must still refresh the observed open-file size");
      assertEquals(2, scans.get()); assertEquals(144, second.stores().get(0).openCaptures().get(0).file().sizeBytes());
      assertTrue(second.problems().isEmpty());
    }
  }
  @Test void oneManifestChangeIsRereadInsteadOfPublishingMixedInventory() throws Exception {
    store(true); var scans = new AtomicInteger();
    try (var catalog = catalog(() -> {
      if (scans.incrementAndGet() == 1) Files.writeString(temp.resolve("store.json"),
          StoreJson.JSON.toJson(new Header(1, WHEN, "changed-store-id", List.of())));
    })) {
      assertEquals("changed-store-id", catalog.snapshot().stores().get(0).header().id()); assertEquals(2, scans.get());
      assertEquals("changed-store-id", catalog.snapshot().stores().get(0).header().id()); assertEquals(2, scans.get(), "Stable inventory is reused");
    }
  }
  @Test void repeatedManifestChurnRefusesAfterTwoScansAsRetryableAndRecovers() throws Exception {
    store(true); var scans = new AtomicInteger();
    try (var catalog = catalog(() -> {
      int n = scans.incrementAndGet();
      if (n <= 2) Files.writeString(temp.resolve("store.json"), StoreJson.JSON.toJson(new Header(1, WHEN, "store" + "x".repeat(n), List.of())));
    })) {
      var failure = assertThrows(ReadException.class, catalog::snapshot);
      assertEquals(ReadProblem.Code.FILE_CHANGED, failure.code()); assertTrue(failure.retryable()); assertEquals(2, scans.get());
      assertEquals("storexx", catalog.snapshot().stores().get(0).header().id()); assertEquals(3, scans.get(), "Failed refresh releases its shared future");
    }
  }
  @ParameterizedTest @ValueSource(strings = {"shrink", "rewrite", "replacement", "closed-growth"})
  void onlyOpenPayloadGrowthCanPassThePostScanCheck(String change) throws Exception {
    store(!change.equals("closed-growth")); var scans = new AtomicInteger();
    try (var catalog = catalog(() -> {
      scans.incrementAndGet();
      switch (change) {
        case "shrink" -> { try (var file = FileChannel.open(payload, StandardOpenOption.WRITE)) { file.truncate(file.size() - 1); } }
        case "rewrite" -> Files.setLastModifiedTime(payload, FileTime.fromMillis(Files.getLastModifiedTime(payload).toMillis() + 2000));
        case "replacement" -> {
          var parent = payload.getParent(); var modified = Files.getLastModifiedTime(parent);
          var replacement = parent.resolve("replacement.tmp"); Files.write(replacement, new byte[(int) Files.size(payload) + 16]);
          Files.move(replacement, payload, StandardCopyOption.REPLACE_EXISTING);
          Files.setLastModifiedTime(parent, modified); // Isolate file identity from the directory stamp.
        }
        default -> Files.write(payload, new byte[16], StandardOpenOption.APPEND);
      }
    })) {
      var failure = assertThrows(ReadException.class, catalog::snapshot);
      assertTrue(failure.retryable()); assertEquals(ReadProblem.Code.FILE_CHANGED, failure.code()); assertEquals(2, scans.get());
    }
  }
  @Test void payloadDisappearanceIsRereadAndInvalidMembershipIsNotPublished() throws Exception {
    store(true); var scans = new AtomicInteger();
    try (var catalog = catalog(() -> { if (scans.incrementAndGet() == 1) Files.delete(payload); })) {
      var result = catalog.snapshot(); assertEquals(2, scans.get());
      assertTrue(result.stores().get(0).allFiles().isEmpty());
      assertTrue(result.problems().stream().anyMatch(p -> p.manifest().equals(manifest)));
    }
  }
}
