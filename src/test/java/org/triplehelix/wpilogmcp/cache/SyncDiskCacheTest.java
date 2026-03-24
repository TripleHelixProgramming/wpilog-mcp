package org.triplehelix.wpilogmcp.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.revlog.ParsedRevLog;
import org.triplehelix.wpilogmcp.revlog.RevLogDevice;
import org.triplehelix.wpilogmcp.revlog.RevLogSignal;
import org.triplehelix.wpilogmcp.sync.ConfidenceLevel;
import org.triplehelix.wpilogmcp.sync.SignalPairResult;
import org.triplehelix.wpilogmcp.sync.SyncMethod;
import org.triplehelix.wpilogmcp.sync.SyncResult;

class SyncDiskCacheTest {
  @TempDir Path tempDir;
  private SyncDiskCache cache;

  @BeforeEach void setUp() {
    var cacheDir = new CacheDirectory();
    cacheDir.setOverride(tempDir.toString());
    cache = new SyncDiskCache(cacheDir);
  }

  private ParsedRevLog createTestRevLog() {
    return new ParsedRevLog("/logs/test.revlog", "20260321_103045",
        Map.of(1, new RevLogDevice(1, "SPARK MAX", "v1.6.4")),
        Map.of("SparkMax_1/out", new RevLogSignal("out", "SparkMax_1",
            List.of(new TimestampedValue(0.0, 0.5)), "duty_cycle")),
        0.0, 150.0, 7500);
  }

  private SyncResult createTestSyncResult() {
    return new SyncResult(12345678L, 0.92, ConfidenceLevel.HIGH,
        List.of(new SignalPairResult("/Bat", "SparkMax_1/bv", 12345678L, 0.95, 5000)),
        SyncMethod.CROSS_CORRELATION, "Test", 1.5, 75.0);
  }

  @Nested @DisplayName("Save and load") class SaveLoad {
    @Test @DisplayName("save then load") void saveThenLoad() {
      cache.save(createTestRevLog(), createTestSyncResult(), "fp1", "fp2");
      assertTrue(cache.load("fp1", "fp2").isPresent());
    }
    @Test @DisplayName("cache miss") void miss() { assertTrue(cache.load("x", "y").isEmpty()); }
    @Test @DisplayName("different fingerprint") void diffFp() {
      cache.save(createTestRevLog(), createTestSyncResult(), "fp1", "fp2");
      assertTrue(cache.load("fp1", "fp3").isEmpty());
    }
    @Test @DisplayName("disabled") void disabled() {
      cache.save(createTestRevLog(), createTestSyncResult(), "fp1", "fp2");
      cache.setEnabled(false);
      assertTrue(cache.load("fp1", "fp2").isEmpty());
    }
  }

  @Nested @DisplayName("Fingerprint") class Fp {
    @Test @DisplayName("deterministic") void det() {
      assertEquals(SyncDiskCache.combinedFingerprint("a", "b"),
          SyncDiskCache.combinedFingerprint("a", "b"));
    }
    @Test @DisplayName("order matters") void order() {
      assertNotEquals(SyncDiskCache.combinedFingerprint("a", "b"),
          SyncDiskCache.combinedFingerprint("b", "a"));
    }
    @Test @DisplayName("32 hex chars") void len() {
      String fp = SyncDiskCache.combinedFingerprint("x", "y");
      assertEquals(32, fp.length());
      assertTrue(fp.matches("[0-9a-f]+"));
    }
  }

  @Nested @DisplayName("Cleanup stale formats") class Cleanup {
    @Test @DisplayName("cleanupRemovesStaleFormatFiles") void cleanupRemovesStaleFormatFiles() throws IOException {
      // Save a valid entry
      cache.save(createTestRevLog(), createTestSyncResult(), "fp_valid", "fp_valid2");
      String validFp = SyncDiskCache.combinedFingerprint("fp_valid", "fp_valid2");
      Path validFile = tempDir.resolve(validFp + "-sync.msgpack");
      assertTrue(Files.exists(validFile), "Valid cache file should exist");

      // Write a fake stale sync file with corrupt/wrong format version
      // A file with just a single msgpack int (value 999) as the format version
      Path staleFile = tempDir.resolve("deadbeef01234567deadbeef01234567-sync.msgpack");
      // Write a msgpack-encoded int with a wrong version number (0xFF as a single byte is invalid msgpack,
      // which will cause readFormatVersion to return -1, which != CURRENT_FORMAT_VERSION)
      Files.write(staleFile, new byte[]{(byte) 0xFF, 0x00, 0x01, 0x02});
      assertTrue(Files.exists(staleFile), "Stale file should exist before cleanup");

      int deleted = cache.cleanupStaleFormats();

      assertTrue(deleted >= 1, "Should delete at least one stale file");
      assertFalse(Files.exists(staleFile), "Stale file should be deleted after cleanup");
      assertTrue(Files.exists(validFile), "Valid file should remain after cleanup");

      // Verify we can still load the valid entry
      assertTrue(cache.load("fp_valid", "fp_valid2").isPresent(),
          "Valid entry should still be loadable after cleanup");
    }
  }

  @Nested @DisplayName("Corrupt handling") class Corrupt {
    @Test @DisplayName("load deletes corrupt file") void corruptLoad() throws IOException {
      cache.save(createTestRevLog(), createTestSyncResult(), "fp_a", "fp_b");
      String combinedFp = SyncDiskCache.combinedFingerprint("fp_a", "fp_b");
      Path corruptFile = tempDir.resolve(combinedFp + "-sync.msgpack");
      Files.writeString(corruptFile, "corrupt data");
      assertTrue(cache.load("fp_a", "fp_b").isEmpty());
      assertFalse(Files.exists(corruptFile));
    }
  }
}
