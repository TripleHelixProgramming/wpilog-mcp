/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogScan;
import org.triplehelix.wpilogmcp.log.ScopedLogReader;

/** The shared cache sweeper must recognize invalid indexes before they consume its budget. */
class ScanDiskCacheTest {
  @TempDir Path root;

  @Test void cleanupRemovesMalformedScanIndexWithoutTouchingUnrelatedFiles() throws Exception {
    var directory = new CacheDirectory();
    directory.setOverride(root.toString());
    var cache = new DiskCache(directory, "test");
    var invalid = Files.write(root.resolve("bad-scan.idx"), new byte[] {1, 2, 3, 4});
    var unrelated = Files.writeString(root.resolve("keep.txt"), "not a cache entry");
    try {
      cache.cleanup();
      assertFalse(Files.exists(invalid), "Malformed scan indexes must be removed by the shared cleanup owner");
      assertEquals("not a cache entry", Files.readString(unrelated));
    } finally {
      cache.shutdown();
    }
  }

  @Test void versionsSeparateKeysAndCountsDescribeRealArtifacts() throws Exception {
    var fixture = fixture();
    var cache = cache();
    try {
      assertTrue(DiskCache.scanFileName(fixture.source()).startsWith(
          "v" + ScanIndexSerializer.FORMAT_VERSION + "-r" + ScanIndexSerializer.RECIPE_VERSION + "-"));
      assertEquals(new DiskCache.ScanStats(0, 0, 0, 0), cache.scanStats());
      assertNull(cache.loadScanIndex(fixture.source()));
      assertEquals(new DiskCache.ScanStats(0, 0, 0, 1), cache.scanStats());
      cache.saveScanIndex(fixture.source(), fixture.scan());
      var file = root.resolve(DiskCache.scanFileName(fixture.source()));
      long expectedBytes = Files.size(file);
      assertTrue(expectedBytes > 0);
      assertEquals(new DiskCache.ScanStats(1, expectedBytes, 0, 1), cache.scanStats());
      var loaded = cache.loadScanIndex(fixture.source());
      assertNotNull(loaded);
      assertEquals(2, loaded.dataRecords());
      assertEquals(1.0, loaded.minTimestamp());
      assertEquals(2.0, loaded.maxTimestamp());
      assertEquals(new DiskCache.ScanStats(1, expectedBytes, 1, 1), cache.scanStats());
      cache.setEnabled(false);
      assertNull(cache.loadScanIndex(fixture.source()));
      assertEquals(new DiskCache.ScanStats(1, expectedBytes, 1, 1), cache.scanStats());
    } finally {
      cache.shutdown();
    }
  }

  @Test void corruptIndexIsAMissAndCleanupRemovesIt() throws Exception {
    var fixture = fixture();
    var cache = cache();
    try {
      cache.saveScanIndex(fixture.source(), fixture.scan());
      var file = root.resolve(DiskCache.scanFileName(fixture.source()));
      byte[] bytes = Files.readAllBytes(file);
      bytes[bytes.length - 1] ^= 1;
      Files.write(file, bytes);
      assertNull(cache.loadScanIndex(fixture.source()), "A failed checksum must never hydrate an index");
      assertEquals(0, cache.scanStats().hits());
      assertEquals(1, cache.scanStats().misses());
      cache.cleanup();
      assertFalse(Files.exists(file));
    } finally {
      cache.shutdown();
    }
  }

  @Test void scanArtifactsParticipateInTheExistingCapacitySweep() throws Exception {
    var fixture = fixture();
    var cache = cache();
    try {
      cache.saveScanIndex(fixture.source(), fixture.scan());
      assertEquals(1, cache.scanStats().entryCount());
      cache.setMaxTotalSizeMb(0);
      cache.cleanup();
      assertEquals(0, cache.scanStats().entryCount(),
          "The shared zero-byte budget must evict a valid scan artifact");
      assertEquals(0, cache.scanStats().sizeBytes());
    } finally {
      cache.shutdown();
    }
  }

  @Test void competingOwnersPublishOneCompleteIndexAndLeaveNoTemporary() throws Exception {
    var fixture = fixture();
    var first = cache();
    var second = cache();
    var workers = Executors.newFixedThreadPool(2, task -> {
      var thread = new Thread(task, "scan-cache-test");
      thread.setDaemon(true);
      return thread;
    });
    try {
      var a = workers.submit(() -> first.saveScanIndex(fixture.source(), fixture.scan()));
      var b = workers.submit(() -> second.saveScanIndex(fixture.source(), fixture.scan()));
      a.get(5, TimeUnit.SECONDS);
      b.get(5, TimeUnit.SECONDS);
      assertEquals(1, first.scanStats().entryCount());
      var scan = second.loadScanIndex(fixture.source());
      assertNotNull(scan);
      assertEquals(2, scan.dataRecords());
      try (var files = Files.list(root)) {
        assertEquals(0, files.filter(path -> path.getFileName().toString().contains(".tmp.")).count());
      }
    } finally {
      workers.shutdownNow();
      first.shutdown();
      second.shutdown();
    }
  }

  @Test void finalSourceRefusalIsNotSwallowedAsAnOptionalCacheFailure() throws Exception {
    var fixture = fixture();
    var cache = cache();
    try {
      var failure = assertThrows(java.io.IOException.class,
          () -> cache.saveScanIndex(fixture.source(), fixture.scan(), () -> {
            throw new java.io.IOException("SOURCE_UNAVAILABLE: synthetic source check failed");
          }));
      assertEquals("SOURCE_UNAVAILABLE: synthetic source check failed", failure.getMessage());
      assertEquals(0, cache.scanStats().entryCount());
      try (var files = Files.list(root)) {
        assertEquals(0, files.filter(path -> path.getFileName().toString().contains(".tmp.")).count());
      }
    } finally {
      cache.shutdown();
    }
  }

  private DiskCache cache() {
    var directory = new CacheDirectory();
    directory.setOverride(root.toString());
    return new DiskCache(directory, "test");
  }

  private Fixture fixture() throws Exception {
    var file = root.resolve("source.wpilog");
    try (var writer = new WpilogWriter(file, "cache owner fixture")) {
      int id = writer.start("/Value", "double", "", 0);
      writer.append(id, 1_000_000, WpilogWriter.encodeDouble(10));
      writer.append(id, 2_000_000, WpilogWriter.encodeDouble(20));
    }
    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    var source = new ScanIndexSerializer.Source("sha256:" + sha, Files.size(file), sha);
    try (var reader = new ScopedLogReader(file)) {
      return new Fixture(source, LogScan.of(reader.reader(), file));
    }
  }

  private record Fixture(ScanIndexSerializer.Source source, LogScan scan) {}
}
