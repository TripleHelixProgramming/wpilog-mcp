package org.triplehelix.wpilogmcp.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.cache.SyncCacheSerializer.CachedSyncEntry;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.revlog.ParsedRevLog;
import org.triplehelix.wpilogmcp.revlog.RevLogDevice;
import org.triplehelix.wpilogmcp.revlog.RevLogSignal;
import org.triplehelix.wpilogmcp.sync.ConfidenceLevel;
import org.triplehelix.wpilogmcp.sync.SignalPairResult;
import org.triplehelix.wpilogmcp.sync.SyncMethod;
import org.triplehelix.wpilogmcp.sync.SyncResult;

class SyncCacheSerializerTest {
  @TempDir Path tempDir;
  private final SyncCacheSerializer serializer = new SyncCacheSerializer();

  private ParsedRevLog createTestRevLog() {
    return new ParsedRevLog("/logs/REV_20260321_103045.revlog", "20260321_103045",
        Map.of(1, new RevLogDevice(1, "SPARK MAX", "v1.6.4")),
        Map.of("SparkMax_1/appliedOutput", new RevLogSignal("appliedOutput", "SparkMax_1",
            List.of(new TimestampedValue(0.0, 0.5), new TimestampedValue(0.02, 1.0)), "duty_cycle")),
        0.0, 150.0, 7500);
  }

  private SyncResult createTestSyncResult() {
    return new SyncResult(12345678L, 0.92, ConfidenceLevel.HIGH,
        List.of(new SignalPairResult("/Battery", "SparkMax_1/busVoltage", 12345678L, 0.95, 5000)),
        SyncMethod.CROSS_CORRELATION, "Test sync", 1.5, 75.0);
  }

  @Nested @DisplayName("Round-trip") class RoundTrip {
    @Test @DisplayName("full entry round-trips") void fullRoundTrip() throws IOException {
      var entry = new CachedSyncEntry(createTestRevLog(), createTestSyncResult(), "fp1", "fp2", 123L);
      Path file = tempDir.resolve("test.msgpack");
      serializer.write(entry, file);
      var loaded = serializer.read(file);
      assertNotNull(loaded);
      assertEquals("fp1", loaded.wpilogFingerprint());
      assertEquals(12345678L, loaded.syncResult().offsetMicros());
      assertEquals(1, loaded.revlog().signals().size());
    }

    @Test @DisplayName("null fields preserved") void nullFields() throws IOException {
      var revlog = new ParsedRevLog(null, null, Map.of(), Map.of(), 0, 0, 0);
      var entry = new CachedSyncEntry(revlog, SyncResult.failed("x"), "a", "b", 0L);
      Path file = tempDir.resolve("null.msgpack");
      serializer.write(entry, file);
      var loaded = serializer.read(file);
      assertNotNull(loaded);
      assertNull(loaded.revlog().path());
      assertNull(loaded.revlog().filenameTimestamp());
    }
  }

  @Nested @DisplayName("Error handling") class Errors {
    @Test @DisplayName("corrupt file returns null") void corrupt() throws IOException {
      Path file = tempDir.resolve("corrupt.msgpack");
      Files.writeString(file, "not msgpack");
      assertNull(serializer.read(file));
    }

    @Test @DisplayName("tampered CRC returns null") void tamperedCrc() throws IOException {
      var entry = new CachedSyncEntry(createTestRevLog(), createTestSyncResult(), "a", "b", 0L);
      Path file = tempDir.resolve("tampered.msgpack");
      serializer.write(entry, file);
      byte[] bytes = Files.readAllBytes(file);
      bytes[10] ^= 0xFF;
      Files.write(file, bytes);
      assertNull(serializer.read(file));
    }
  }
}
