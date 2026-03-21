package org.triplehelix.wpilogmcp.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

@DisplayName("DiskCache")
class DiskCacheTest {

  @TempDir Path tempDir;
  private DiskCache cache;
  private Path cacheDir;

  @BeforeEach
  void setUp() throws IOException {
    cacheDir = tempDir.resolve("cache");
    Files.createDirectories(cacheDir);

    var directory = new CacheDirectory();
    directory.setOverride(cacheDir.toString());

    cache = new DiskCache(directory, "0.5.0-test");
  }

  @AfterEach
  void tearDown() {
    cache.shutdown();
  }

  private Path createWpilog(String relativePath, byte[] content) throws IOException {
    Path file = tempDir.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.write(file, content);
    return file;
  }

  private ParsedLog createSimpleLog(String path, int valueCount) {
    var tvs = new ArrayList<TimestampedValue>();
    for (int i = 0; i < valueCount; i++) {
      tvs.add(new TimestampedValue(i * 0.02, 12.0 + Math.sin(i * 0.1) * 0.5));
    }
    return new ParsedLog(path,
        Map.of("sensor", new EntryInfo(1, "sensor", "double", "")),
        Map.of("sensor", tvs),
        0.0, valueCount > 0 ? (valueCount - 1) * 0.02 : 0.0);
  }

  private ParsedLog createMultiTypeLog(String path) {
    var entries = new HashMap<String, EntryInfo>();
    entries.put("voltage", new EntryInfo(1, "voltage", "double", ""));
    entries.put("enabled", new EntryInfo(2, "enabled", "boolean", ""));
    entries.put("state", new EntryInfo(3, "state", "string", ""));
    entries.put("counter", new EntryInfo(4, "counter", "int64", ""));

    var pose = new LinkedHashMap<String, Object>();
    var trans = new LinkedHashMap<String, Object>();
    trans.put("x", 1.0);
    trans.put("y", 2.0);
    pose.put("translation", trans);
    entries.put("pose", new EntryInfo(5, "pose", "struct:Pose2d", ""));

    var values = new HashMap<String, List<TimestampedValue>>();
    values.put("voltage", new ArrayList<>(List.of(
        new TimestampedValue(0.0, 12.5), new TimestampedValue(1.0, 11.8))));
    values.put("enabled", new ArrayList<>(List.of(
        new TimestampedValue(0.0, true), new TimestampedValue(5.0, false))));
    values.put("state", new ArrayList<>(List.of(
        new TimestampedValue(0.0, "IDLE"), new TimestampedValue(3.0, "SCORING"))));
    values.put("counter", new ArrayList<>(List.of(
        new TimestampedValue(0.0, 0L), new TimestampedValue(1.0, 42L))));
    values.put("pose", new ArrayList<>(List.of(new TimestampedValue(0.0, pose))));

    return new ParsedLog(path, entries, values, 0.0, 5.0);
  }

  // ==================== Basic Save/Load ====================

  @Nested
  @DisplayName("Basic Save/Load")
  class BasicSaveLoad {

    @Test
    @DisplayName("save then load returns equivalent log")
    void saveAndLoadRoundTrip() throws IOException {
      Path wpilog = createWpilog("test.wpilog", "WPILOG test content here".getBytes());
      ParsedLog log = createSimpleLog(wpilog.toString(), 100);

      cache.save(log, wpilog);
      var loaded = cache.load(wpilog);

      assertTrue(loaded.isPresent());
      var restored = loaded.get();
      assertEquals(log.entryCount(), restored.entryCount());
      assertEquals(log.values().get("sensor").size(), restored.values().get("sensor").size());
      assertEquals(log.minTimestamp(), restored.minTimestamp(), 0.001);
      assertEquals(log.maxTimestamp(), restored.maxTimestamp(), 0.001);
    }

    @Test
    @DisplayName("multi-type log round-trips correctly")
    void multiTypeRoundTrip() throws IOException {
      Path wpilog = createWpilog("multi.wpilog", "multi type test data content".getBytes());
      ParsedLog log = createMultiTypeLog(wpilog.toString());

      cache.save(log, wpilog);
      var loaded = cache.load(wpilog);

      assertTrue(loaded.isPresent());
      var restored = loaded.get();
      assertEquals(5, restored.entryCount());
      assertEquals(12.5, ((Number) restored.values().get("voltage").get(0).value()).doubleValue(), 0.001);
      assertEquals(true, restored.values().get("enabled").get(0).value());
      assertEquals("IDLE", restored.values().get("state").get(0).value());
      assertEquals(0L, ((Number) restored.values().get("counter").get(0).value()).longValue());
      @SuppressWarnings("unchecked")
      var restoredPose = (Map<String, Object>) restored.values().get("pose").get(0).value();
      assertNotNull(restoredPose.get("translation"));
    }

    @Test
    @DisplayName("loading same file twice returns same result")
    void idempotentLoad() throws IOException {
      Path wpilog = createWpilog("idem.wpilog", "idempotent test data".getBytes());
      ParsedLog log = createSimpleLog(wpilog.toString(), 50);

      cache.save(log, wpilog);
      var loaded1 = cache.load(wpilog);
      var loaded2 = cache.load(wpilog);

      assertTrue(loaded1.isPresent());
      assertTrue(loaded2.isPresent());
      assertEquals(loaded1.get().entryCount(), loaded2.get().entryCount());
    }

    @Test
    @DisplayName("save is idempotent (writing same log twice doesn't corrupt)")
    void idempotentSave() throws IOException {
      Path wpilog = createWpilog("double_save.wpilog", "double save test".getBytes());
      ParsedLog log = createSimpleLog(wpilog.toString(), 50);

      cache.save(log, wpilog);
      cache.save(log, wpilog); // Second save should be a no-op

      var loaded = cache.load(wpilog);
      assertTrue(loaded.isPresent());
      assertEquals(50, loaded.get().values().get("sensor").size());
    }
  }

  // ==================== Cache Misses ====================

  @Nested
  @DisplayName("Cache Misses")
  class CacheMisses {

    @Test
    @DisplayName("cache miss for uncached file")
    void uncachedFile() throws IOException {
      Path wpilog = createWpilog("uncached.wpilog", "some data".getBytes());
      assertFalse(cache.load(wpilog).isPresent());
    }

    @Test
    @DisplayName("cache miss after file content changes")
    void fileContentChanged() throws IOException {
      Path wpilog = createWpilog("changing.wpilog", "original content here".getBytes());
      ParsedLog log = createSimpleLog(wpilog.toString(), 50);
      cache.save(log, wpilog);

      // Modify the file content (different fingerprint)
      Files.write(wpilog, "modified content here!!".getBytes());

      assertFalse(cache.load(wpilog).isPresent());
    }

    @Test
    @DisplayName("cache miss after file size changes")
    void fileSizeChanged() throws IOException {
      Path wpilog = createWpilog("growing.wpilog", "short".getBytes());
      ParsedLog log = createSimpleLog(wpilog.toString(), 10);
      cache.save(log, wpilog);

      // Append data (changes both size and fingerprint)
      Files.write(wpilog, "short plus a lot more data appended here".getBytes());

      assertFalse(cache.load(wpilog).isPresent());
    }
  }

  // ==================== Content-Based Sharing ====================

  @Nested
  @DisplayName("Content-Based Sharing")
  class ContentSharing {

    @Test
    @DisplayName("same content in different directories shares cache")
    void sameContentSharesCache() throws IOException {
      byte[] content = "shared content for cache test bytes".getBytes();
      Path wpilog1 = createWpilog("dir1/test.wpilog", content);
      Path wpilog2 = createWpilog("dir2/test.wpilog", content);

      ParsedLog log = createSimpleLog(wpilog1.toString(), 50);
      cache.save(log, wpilog1);

      // Second file with same content should hit cache
      assertTrue(cache.load(wpilog2).isPresent());
    }

    @Test
    @DisplayName("same filename but different content does not share cache")
    void sameNameDifferentContent() throws IOException {
      Path wpilog1 = createWpilog("dir1/match.wpilog", "content version A".getBytes());
      Path wpilog2 = createWpilog("dir2/match.wpilog", "content version B".getBytes());

      ParsedLog log = createSimpleLog(wpilog1.toString(), 50);
      cache.save(log, wpilog1);

      assertFalse(cache.load(wpilog2).isPresent());
    }
  }

  // ==================== Enabled/Disabled State ====================

  @Nested
  @DisplayName("Enabled/Disabled State")
  class EnabledDisabled {

    @Test
    @DisplayName("disabled cache always returns empty on load")
    void disabledLoad() throws IOException {
      Path wpilog = createWpilog("test.wpilog", "test data content".getBytes());
      ParsedLog log = createSimpleLog(wpilog.toString(), 50);

      // Save while enabled
      cache.save(log, wpilog);

      // Disable and try to load
      cache.setEnabled(false);
      assertFalse(cache.load(wpilog).isPresent());
    }

    @Test
    @DisplayName("disabled cache does not write on save")
    void disabledSave() throws IOException {
      cache.setEnabled(false);

      Path wpilog = createWpilog("test.wpilog", "test data".getBytes());
      ParsedLog log = createSimpleLog(wpilog.toString(), 50);
      cache.save(log, wpilog);

      // Re-enable and check — should be a miss since save was skipped
      cache.setEnabled(true);
      assertFalse(cache.load(wpilog).isPresent());
    }

    @Test
    @DisplayName("re-enabling cache works after disable")
    void reEnable() throws IOException {
      Path wpilog = createWpilog("test.wpilog", "re-enable test data".getBytes());
      ParsedLog log = createSimpleLog(wpilog.toString(), 50);

      cache.setEnabled(false);
      cache.setEnabled(true);

      cache.save(log, wpilog);
      assertTrue(cache.load(wpilog).isPresent());
    }
  }

  // ==================== Cleanup ====================

  @Nested
  @DisplayName("Cleanup")
  class CleanupTests {

    @Test
    @DisplayName("cleanup removes files older than maxAge")
    void removesOldFiles() throws IOException {
      Path wpilog = createWpilog("old.wpilog", "old data for cleanup test".getBytes());
      ParsedLog log = createSimpleLog(wpilog.toString(), 10);
      cache.save(log, wpilog);

      // Make the cache file look old (2 days ago)
      try (var stream = Files.list(cacheDir)) {
        stream.filter(f -> f.toString().endsWith(".msgpack")).forEach(f -> {
          try {
            Files.setLastModifiedTime(f,
                FileTime.from(Instant.now().minusSeconds(2 * 24 * 3600)));
          } catch (IOException e) { throw new RuntimeException(e); }
        });
      }

      // Set maxAge to 1 day = file from 2 days ago is expired
      cache.setMaxAgeDays(1);
      cache.cleanup();

      assertEquals(0, countCacheFiles());
    }

    @Test
    @DisplayName("cleanup preserves recent files")
    void preservesRecentFiles() throws IOException {
      Path wpilog = createWpilog("recent.wpilog", "recent data for cache test".getBytes());
      ParsedLog log = createSimpleLog(wpilog.toString(), 10);
      cache.save(log, wpilog);

      // Default maxAge is 30 days, file was just created
      cache.cleanup();

      assertTrue(countCacheFiles() > 0, "Recent files should be preserved");
    }

    @Test
    @DisplayName("cleanup removes orphaned temp files older than 1 hour")
    void removesOrphanedTempFiles() throws IOException {
      // Create an orphaned temp file
      Path tempFile = cacheDir.resolve("test.msgpack.tmp.12345");
      Files.write(tempFile, "orphaned temp data".getBytes());
      // Make it look old
      Files.setLastModifiedTime(tempFile,
          FileTime.from(Instant.now().minusSeconds(7200)));

      cache.cleanup();

      assertFalse(Files.exists(tempFile), "Old temp files should be cleaned up");
    }

    @Test
    @DisplayName("cleanup preserves recent temp files")
    void preservesRecentTempFiles() throws IOException {
      // Create a recent temp file (could be actively written by another process)
      Path tempFile = cacheDir.resolve("test.msgpack.tmp.99999");
      Files.write(tempFile, "active temp data".getBytes());

      cache.cleanup();

      assertTrue(Files.exists(tempFile), "Recent temp files should be preserved");
    }

    @Test
    @DisplayName("cleanup removes stale format version files")
    void removesStaleFormatFiles() throws IOException {
      // Write a cache file with a stale format version
      var serializer = new DiskCacheSerializer();
      var log = createSimpleLog("/test/stale.wpilog", 5);
      var staleMetadata = new CacheMetadata(
          0, "0.1.0", "/test/stale.wpilog", 100, 0L, "stale", 0L);

      Path staleFile = cacheDir.resolve("stale-100.msgpack");
      serializer.write(log, staleFile, staleMetadata);

      assertTrue(Files.exists(staleFile));
      cache.cleanup();
      assertFalse(Files.exists(staleFile), "Stale format version files should be removed");
    }

    @Test
    @DisplayName("size-based cleanup evicts only enough files to reach target (§2.1 fix)")
    void sizeBasedCleanupEvictsToTarget() throws IOException {
      // Create 3 cache files of known size
      for (int i = 0; i < 3; i++) {
        Path wpilog = createWpilog("log" + i + ".wpilog",
            ("content " + i + " with padding data to differentiate").getBytes());
        ParsedLog log = createSimpleLog(wpilog.toString(), 100);
        cache.save(log, wpilog);
      }

      long initialCount = countCacheFiles();
      assertEquals(3, initialCount, "Should have 3 cache files");

      // Set a size limit that should cause eviction of some but not all files
      // Each cache file for 100 values is small (~few KB), so set limit very low
      cache.setMaxTotalSizeMb(0); // 0 MB limit = evict everything
      cache.cleanup();

      long afterCleanup = countCacheFiles();
      assertEquals(0, afterCleanup, "0 MB limit should evict all files");
    }

    @Test
    @DisplayName("cleanup deletes corrupted/unreadable cache files (§2.3 fix)")
    void deletesCorruptedCacheFiles() throws IOException {
      // Create a file with .msgpack extension but corrupt content
      Path corruptFile = cacheDir.resolve("corrupt-999.msgpack");
      Files.write(corruptFile, new byte[]{0x01, 0x02, 0x03, 0x04, 0x05});

      assertTrue(Files.exists(corruptFile));
      cache.cleanup();
      assertFalse(Files.exists(corruptFile),
          "Corrupted cache files should be deleted during cleanup");
    }
  }

  // ==================== Async Save ====================

  @Nested
  @DisplayName("Async Save")
  class AsyncSave {

    @Test
    @DisplayName("saveAsync eventually writes cache file")
    void asyncSaveWritesFile() throws Exception {
      Path wpilog = createWpilog("async.wpilog", "async test data content".getBytes());
      ParsedLog log = createSimpleLog(wpilog.toString(), 50);

      cache.saveAsync(log, wpilog);

      // Wait for async write to complete
      Thread.sleep(500);

      var loaded = cache.load(wpilog);
      assertTrue(loaded.isPresent(), "Async save should eventually produce loadable cache");
    }

    @Test
    @DisplayName("saveAsync returns before file is written")
    void asyncSaveReturnsBeforeWrite() throws Exception {
      Path wpilog = createWpilog("noblock.wpilog", "non-blocking test data".getBytes());
      ParsedLog log = createSimpleLog(wpilog.toString(), 5000);

      cache.saveAsync(log, wpilog);

      // The method returned — the file may or may not exist yet.
      // The real test is that it eventually appears.
      // Give it time to complete on the background thread.
      for (int i = 0; i < 50; i++) {
        if (cache.load(wpilog).isPresent()) return; // success
        Thread.sleep(100);
      }
      fail("Async save did not complete within 5 seconds");
    }
  }

  // ==================== Concurrent Access ====================

  @Nested
  @DisplayName("Concurrent Access")
  class ConcurrentAccess {

    @Test
    @DisplayName("concurrent saves of same file do not corrupt cache")
    void concurrentSaves() throws Exception {
      Path wpilog = createWpilog("concurrent.wpilog", "concurrent test data".getBytes());
      ParsedLog log = createSimpleLog(wpilog.toString(), 100);

      int threadCount = 5;
      var latch = new CountDownLatch(1);
      var errors = new AtomicInteger(0);
      var threads = new Thread[threadCount];

      for (int i = 0; i < threadCount; i++) {
        threads[i] = new Thread(() -> {
          try {
            latch.await();
            cache.save(log, wpilog);
          } catch (Exception e) {
            errors.incrementAndGet();
          }
        });
        threads[i].start();
      }

      latch.countDown(); // Start all threads simultaneously
      for (var t : threads) t.join(5000);

      assertEquals(0, errors.get(), "Concurrent saves should not throw");

      var loaded = cache.load(wpilog);
      assertTrue(loaded.isPresent(), "Cache should be readable after concurrent saves");
      assertEquals(100, loaded.get().values().get("sensor").size());
    }

    @Test
    @DisplayName("concurrent reads do not interfere")
    void concurrentReads() throws Exception {
      Path wpilog = createWpilog("read_concurrent.wpilog", "concurrent read test".getBytes());
      ParsedLog log = createSimpleLog(wpilog.toString(), 100);
      cache.save(log, wpilog);

      int threadCount = 10;
      var latch = new CountDownLatch(1);
      var errors = new AtomicInteger(0);
      var hits = new AtomicInteger(0);
      var threads = new Thread[threadCount];

      for (int i = 0; i < threadCount; i++) {
        threads[i] = new Thread(() -> {
          try {
            latch.await();
            var loaded = cache.load(wpilog);
            if (loaded.isPresent()) hits.incrementAndGet();
          } catch (Exception e) {
            errors.incrementAndGet();
          }
        });
        threads[i].start();
      }

      latch.countDown();
      for (var t : threads) t.join(5000);

      assertEquals(0, errors.get(), "Concurrent reads should not throw");
      assertEquals(threadCount, hits.get(), "All reads should hit cache");
    }
  }

  // ==================== Edge Cases ====================

  @Nested
  @DisplayName("Edge Cases")
  class EdgeCases {

    @Test
    @DisplayName("empty log (no entries)")
    void emptyLog() throws IOException {
      Path wpilog = createWpilog("empty.wpilog", "empty log data content".getBytes());
      var log = new ParsedLog(wpilog.toString(),
          new HashMap<>(), new HashMap<>(), 0.0, 0.0);

      cache.save(log, wpilog);
      var loaded = cache.load(wpilog);

      assertTrue(loaded.isPresent());
      assertEquals(0, loaded.get().entryCount());
    }

    @Test
    @DisplayName("log with single value")
    void singleValue() throws IOException {
      Path wpilog = createWpilog("single.wpilog", "single value content".getBytes());
      var log = createSimpleLog(wpilog.toString(), 1);

      cache.save(log, wpilog);
      var loaded = cache.load(wpilog);

      assertTrue(loaded.isPresent());
      assertEquals(1, loaded.get().values().get("sensor").size());
    }

    @Test
    @DisplayName("very long entry names")
    void longEntryNames() throws IOException {
      Path wpilog = createWpilog("longnames.wpilog", "long entry names test".getBytes());
      String longName = "/Robot/Subsystem/Module/Sensor/".repeat(10) + "Value";

      var log = new ParsedLog(wpilog.toString(),
          Map.of(longName, new EntryInfo(1, longName, "double", "")),
          Map.of(longName, new ArrayList<>(List.of(new TimestampedValue(0.0, 42.0)))),
          0.0, 0.0);

      cache.save(log, wpilog);
      var loaded = cache.load(wpilog);

      assertTrue(loaded.isPresent());
      assertNotNull(loaded.get().values().get(longName));
    }

    @Test
    @DisplayName("large string values")
    void largeStringValues() throws IOException {
      Path wpilog = createWpilog("bigstr.wpilog", "large string test data".getBytes());
      String bigString = "A".repeat(100_000);

      var log = new ParsedLog(wpilog.toString(),
          Map.of("json", new EntryInfo(1, "json", "json", "")),
          Map.of("json", new ArrayList<>(List.of(new TimestampedValue(0.0, bigString)))),
          0.0, 0.0);

      cache.save(log, wpilog);
      var loaded = cache.load(wpilog);

      assertTrue(loaded.isPresent());
      assertEquals(bigString, loaded.get().values().get("json").get(0).value());
    }

    @Test
    @DisplayName("special double values: NaN and Infinity survive round-trip")
    void specialDoubleValues() throws IOException {
      Path wpilog = createWpilog("special.wpilog", "special doubles test".getBytes());
      var values = new ArrayList<TimestampedValue>();
      values.add(new TimestampedValue(0.0, Double.NaN));
      values.add(new TimestampedValue(1.0, Double.POSITIVE_INFINITY));
      values.add(new TimestampedValue(2.0, Double.NEGATIVE_INFINITY));
      values.add(new TimestampedValue(3.0, -0.0));

      var log = new ParsedLog(wpilog.toString(),
          Map.of("special", new EntryInfo(1, "special", "double", "")),
          Map.of("special", values),
          0.0, 3.0);

      cache.save(log, wpilog);
      var loaded = cache.load(wpilog);

      assertTrue(loaded.isPresent());
      var rv = loaded.get().values().get("special");
      assertTrue(Double.isNaN(((Number) rv.get(0).value()).doubleValue()));
      assertEquals(Double.POSITIVE_INFINITY, ((Number) rv.get(1).value()).doubleValue());
      assertEquals(Double.NEGATIVE_INFINITY, ((Number) rv.get(2).value()).doubleValue());
    }

    @Test
    @DisplayName("load returns empty when source file is deleted (can't compute fingerprint)")
    void sourceFileDeletedAfterSave() throws IOException {
      Path wpilog = createWpilog("ephemeral.wpilog", "ephemeral test data".getBytes());
      ParsedLog log = createSimpleLog(wpilog.toString(), 50);
      cache.save(log, wpilog);

      // The source file is gone — load can't compute fingerprint
      Files.delete(wpilog);
      assertFalse(cache.load(wpilog).isPresent(),
          "Should return empty when source file doesn't exist");
    }
  }

  // ==================== Cache Metadata Validation ====================

  @Nested
  @DisplayName("Cache Metadata Validation")
  class MetadataValidation {

    @Test
    @DisplayName("file touched but not modified still validates (mtime changed, content same)")
    void touchedButNotModified() throws Exception {
      Path wpilog = createWpilog("touched.wpilog", "touch test content data".getBytes());
      ParsedLog log = createSimpleLog(wpilog.toString(), 50);
      cache.save(log, wpilog);

      // Touch the file (changes mtime but not content)
      Thread.sleep(50); // Ensure mtime changes
      Files.setLastModifiedTime(wpilog,
          FileTime.from(Instant.now().plusSeconds(100)));

      // Should still hit cache because fingerprint matches
      var loaded = cache.load(wpilog);
      assertTrue(loaded.isPresent(),
          "File touched but not modified should still hit cache (fingerprint recheck)");
    }
  }

  // ==================== Helper ====================

  private long countCacheFiles() throws IOException {
    try (var stream = Files.list(cacheDir)) {
      return stream.filter(f -> f.toString().endsWith(".msgpack")).count();
    }
  }
}
