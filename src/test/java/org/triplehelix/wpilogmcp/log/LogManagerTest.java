package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.LogManager.EntryInfo;
import org.triplehelix.wpilogmcp.log.LogManager.ParsedLog;
import org.triplehelix.wpilogmcp.log.LogManager.TimestampedValue;

/**
 * Tests for LogManager functionality including struct decoding and cache management.
 */
class LogManagerTest {

  private LogManager logManager;

  @BeforeEach
  void setUp() {
    logManager = LogManager.getInstance();
    logManager.unloadAllLogs();
    logManager.resetConfiguration();
  }

  @Nested
  @DisplayName("Singleton Pattern")
  class SingletonPattern {

    @Test
    @DisplayName("returns same instance")
    void returnsSameInstance() {
      LogManager instance1 = LogManager.getInstance();
      LogManager instance2 = LogManager.getInstance();
      assertSame(instance1, instance2);
    }
  }

  @Nested
  @DisplayName("Log Loading")
  class LogLoading {

    @Test
    @DisplayName("throws IOException for non-existent file")
    void throwsForNonExistentFile() {
      assertThrows(IOException.class, () -> logManager.loadLog("/nonexistent/file.wpilog"));
    }

    @Test
    @DisplayName("throws IOException for invalid file")
    void throwsForInvalidFile(@TempDir Path tempDir) throws IOException {
      Path invalidFile = tempDir.resolve("invalid.wpilog");
      Files.write(invalidFile, "not a valid wpilog".getBytes());

      assertThrows(IOException.class, () -> logManager.loadLog(invalidFile.toString()));
    }
  }

  @Nested
  @DisplayName("Active Log Management")
  class ActiveLogManagement {

    @Test
    @DisplayName("returns null when no log loaded")
    void returnsNullWhenNoLogLoaded() {
      logManager.unloadAllLogs();
      assertNull(logManager.getActiveLog());
    }

    @Test
    @DisplayName("setActiveLog returns false for unknown path")
    void setActiveLogReturnsFalseForUnknownPath() {
      assertFalse(logManager.setActiveLog("/some/unknown/path.wpilog"));
    }
  }

  @Nested
  @DisplayName("LRU Cache Behavior")
  class LruCacheBehavior {

    @Test
    @DisplayName("evicts least recently used logs when capacity reached")
    void evictLeastRecentlyUsed() {
      // Set max to 5 for this test (default is now 20)
      logManager.setMaxLoadedLogs(5);

      // Create dummy logs using test accessor
      for (int i = 1; i <= 5; i++) {
        String path = "/log" + i;
        ParsedLog dummyLog = new ParsedLog(path, Map.of(), Map.of(), 0, 10);
        logManager.testPutLog(path, dummyLog);
      }
      logManager.testSetActiveLogPath("/log5");

      assertEquals(5, logManager.getLoadedLogCount());

      // Should not evict anything at size 5
      logManager.testEvictIfNeeded();
      assertEquals(5, logManager.getLoadedLogCount());

      // Add one more to go over capacity
      logManager.testPutLog("/log6", new ParsedLog("/log6", Map.of(), Map.of(), 0, 10));

      // Now it should evict the oldest one (/log1)
      logManager.testEvictIfNeeded();

      assertEquals(5, logManager.getLoadedLogCount());
      assertFalse(logManager.testContainsLog("/log1"));
      assertTrue(logManager.testContainsLog("/log2"));
      assertTrue(logManager.testContainsLog("/log6"));
    }
  }

  @Nested
  @DisplayName("Struct Decoding")
  class StructDecoding {

    @Test
    @DisplayName("readDouble decodes little-endian double")
    void readDoubleDecodesLittleEndian() {
      double expected = 3.14159265359;
      ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
      buffer.putDouble(expected);
      byte[] data = buffer.array();

      double actual = logManager.testReadDouble(data, 0);
      assertEquals(expected, actual, 1e-10);
    }

    @Test
    @DisplayName("decodePose2d extracts x, y, and rotation")
    void decodePose2dExtractsFields() {
      double x = 1.5, y = 2.5, rotation = Math.PI / 4;
      byte[] data = createPose2dBytes(x, y, rotation);

      Map<String, Object> result = logManager.testDecodePose2d(data, 0);

      assertEquals(x, (double) result.get("x"), 1e-10);
      assertEquals(y, (double) result.get("y"), 1e-10);
      assertEquals(rotation, (double) result.get("rotation_rad"), 1e-10);
    }

    private byte[] createPose2dBytes(double x, double y, double rotation) {
      ByteBuffer buffer = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
      buffer.putDouble(x).putDouble(y).putDouble(rotation);
      return buffer.array();
    }
  }

  @Nested
  @DisplayName("Record Types")
  class RecordTypes {

    @Test
    @DisplayName("EntryInfo stores all fields")
    void entryInfoStoresAllFields() {
      EntryInfo info = new EntryInfo(1, "/Robot/Pose", "struct:Pose2d", "some metadata");

      assertEquals(1, info.id());
      assertEquals("/Robot/Pose", info.name());
      assertEquals("struct:Pose2d", info.type());
      assertEquals("some metadata", info.metadata());
    }

    @Test
    @DisplayName("TimestampedValue stores timestamp and value")
    void timestampedValueStoresFields() {
      Map<String, Object> value = Map.of("x", 1.0, "y", 2.0);
      TimestampedValue tv = new TimestampedValue(123.456, value);

      assertEquals(123.456, tv.timestamp());
      assertEquals(value, tv.value());
    }

    @Test
    @DisplayName("ParsedLog computes entry count")
    void parsedLogComputesEntryCount() {
      Map<String, EntryInfo> entries =
          Map.of(
              "entry1", new EntryInfo(1, "entry1", "double", ""),
              "entry2", new EntryInfo(2, "entry2", "string", ""));

      ParsedLog log = new ParsedLog("/test.wpilog", entries, Map.of(), 0.0, 10.0);

      assertEquals(2, log.entryCount());
    }

    @Test
    @DisplayName("ParsedLog computes duration")
    void parsedLogComputesDuration() {
      ParsedLog log = new ParsedLog("/test.wpilog", Map.of(), Map.of(), 5.0, 15.0);

      assertEquals(10.0, log.duration());
    }
  }
}
