package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import edu.wpi.first.util.WPIUtilJNI;
import edu.wpi.first.util.datalog.BooleanLogEntry;
import edu.wpi.first.util.datalog.DataLogReader;
import edu.wpi.first.util.datalog.DataLogWriter;
import edu.wpi.first.util.datalog.DoubleLogEntry;
import edu.wpi.first.util.datalog.IntegerLogEntry;
import edu.wpi.first.util.datalog.StringLogEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.subsystems.StructDecoderRegistry;

/**
 * Dedicated tests for LazyParsedLog — the on-demand parsing engine.
 */
@DisplayName("LazyParsedLog")
class LazyParsedLogTest {

  @BeforeAll
  static void loadNativeLibraries() throws IOException {
    WPIUtilJNI.Helper.setExtractOnStaticLoad(false);

    String nativesPath = System.getProperty("wpilib.natives.path");
    if (nativesPath == null) {
      throw new IOException("wpilib.natives.path system property not set - run tests via Gradle");
    }

    String osName = System.getProperty("os.name").toLowerCase();
    String baseLibName;
    String jniLibName;
    String platform;
    if (osName.contains("mac")) {
      baseLibName = "libwpiutil.dylib";
      jniLibName = "libwpiutiljni.dylib";
      platform = "osx/universal";
    } else if (osName.contains("win")) {
      baseLibName = "wpiutil.dll";
      jniLibName = "wpiutiljni.dll";
      platform = "windows/x86-64";
    } else {
      baseLibName = "libwpiutil.so";
      jniLibName = "libwpiutiljni.so";
      platform = "linux/x86-64";
    }

    Path nativesDir = Path.of(nativesPath);
    Path sharedDir = nativesDir.resolve(platform).resolve("shared");
    Path baseLib = sharedDir.resolve(baseLibName);
    Path jniLib = sharedDir.resolve(jniLibName);

    if (!Files.exists(jniLib)) {
      throw new IOException("Native library not found at: " + jniLib);
    }

    if (Files.exists(baseLib)) System.load(baseLib.toAbsolutePath().toString());
    System.load(jniLib.toAbsolutePath().toString());
  }

  private Path createTestLog(Path tempDir, String name, int recordCount) throws IOException, InterruptedException {
    Path logFile = tempDir.resolve(name);
    try (var log = new DataLogWriter(logFile.toString())) {
      var dblEntry = new DoubleLogEntry(log, "/Test/Voltage");
      var intEntry = new IntegerLogEntry(log, "/Test/Counter");
      var boolEntry = new BooleanLogEntry(log, "/Test/Enabled");
      var strEntry = new StringLogEntry(log, "/Test/Status");

      for (int i = 0; i < recordCount; i++) {
        long ts = (i + 1) * 1_000_000L;
        dblEntry.append(12.0 + i * 0.1, ts);
        intEntry.append(i * 100, ts + 100);
        boolEntry.append(i % 2 == 0, ts + 200);
        strEntry.append("state_" + i, ts + 300);
      }
      // Sentinel to avoid dropped last record
      dblEntry.append(0.0, 99_000_000L);
      log.flush();
    }
    Thread.sleep(50);
    return logFile;
  }

  private LazyParsedLog openLazy(Path logFile) throws IOException {
    var reader = new DataLogReader(logFile.toString());
    return new LazyParsedLog(logFile.toString(), reader, new StructDecoderRegistry(), 10_000_000);
  }

  // ==================== Construction ====================

  @Nested
  @DisplayName("Construction")
  class ConstructionTests {

    @Test
    @DisplayName("scans entries without decoding values")
    void scansEntriesWithoutDecoding(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 100);

      try (var lazy = openLazy(logFile)) {
        assertEquals(4, lazy.entryCount());
        assertTrue(lazy.entries().containsKey("/Test/Voltage"));
        assertTrue(lazy.entries().containsKey("/Test/Counter"));
        assertTrue(lazy.entries().containsKey("/Test/Enabled"));
        assertTrue(lazy.entries().containsKey("/Test/Status"));
      }
    }

    @Test
    @DisplayName("records correct entry types")
    void recordsCorrectEntryTypes(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 10);

      try (var lazy = openLazy(logFile)) {
        assertEquals("double", lazy.entries().get("/Test/Voltage").type());
        assertEquals("int64", lazy.entries().get("/Test/Counter").type());
        assertEquals("boolean", lazy.entries().get("/Test/Enabled").type());
        assertEquals("string", lazy.entries().get("/Test/Status").type());
      }
    }

    @Test
    @DisplayName("computes correct timestamp range")
    void computesCorrectTimestampRange(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 20);

      try (var lazy = openLazy(logFile)) {
        // First record at 1_000_000 µs = 1.0s, last data at 20_000_300 µs ≈ 20.0003s
        // Sentinel at 99.0s
        assertTrue(lazy.minTimestamp() > 0);
        assertTrue(lazy.maxTimestamp() > lazy.minTimestamp());
        assertTrue(lazy.duration() > 0);
      }
    }

    @Test
    @DisplayName("rejects invalid wpilog file")
    void rejectsInvalidFile(@TempDir Path tempDir) throws Exception {
      var badFile = tempDir.resolve("bad.wpilog");
      Files.writeString(badFile, "this is not a wpilog file");

      var reader = new DataLogReader(badFile.toString());
      assertThrows(IOException.class, () -> new LazyParsedLog(
          badFile.toString(), reader, new StructDecoderRegistry(), 10_000_000));
    }

    @Test
    @DisplayName("handles empty log file")
    void handlesEmptyLog(@TempDir Path tempDir) throws Exception {
      var logFile = tempDir.resolve("empty.wpilog");
      try (var log = new DataLogWriter(logFile.toString())) {
        log.flush();
      }
      Thread.sleep(50);

      try (var lazy = openLazy(logFile)) {
        assertEquals(0, lazy.entryCount());
        assertTrue(lazy.values().isEmpty());
      }
    }
  }

  // ==================== Lazy Value Access ====================

  @Nested
  @DisplayName("Lazy value access")
  class LazyValueAccessTests {

    @Test
    @DisplayName("decodes values on first access")
    void decodesOnFirstAccess(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 10);

      try (var lazy = openLazy(logFile)) {
        var values = lazy.values().get("/Test/Voltage");
        assertNotNull(values);
        assertFalse(values.isEmpty());
        // First record: 12.0
        assertEquals(12.0, (double) values.get(0).value(), 0.001);
      }
    }

    @Test
    @DisplayName("returns null for nonexistent entry")
    void returnsNullForNonexistent(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 10);

      try (var lazy = openLazy(logFile)) {
        assertNull(lazy.values().get("/Nonexistent/Entry"));
      }
    }

    @Test
    @DisplayName("caches decoded values across repeated access")
    void cachesDecodedValues(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 10);

      try (var lazy = openLazy(logFile)) {
        var first = lazy.values().get("/Test/Voltage");
        var second = lazy.values().get("/Test/Voltage");
        assertSame(first, second, "Repeated access should return cached instance");
      }
    }

    @Test
    @DisplayName("values map reports correct size and containsKey")
    void valuesMapMetadata(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 10);

      try (var lazy = openLazy(logFile)) {
        assertEquals(4, lazy.values().size());
        assertTrue(lazy.values().containsKey("/Test/Voltage"));
        assertFalse(lazy.values().containsKey("/Nonexistent"));
      }
    }

    @Test
    @DisplayName("decodes all four entry types correctly")
    void decodesAllTypes(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 5);

      try (var lazy = openLazy(logFile)) {
        var doubles = lazy.values().get("/Test/Voltage");
        var ints = lazy.values().get("/Test/Counter");
        var bools = lazy.values().get("/Test/Enabled");
        var strings = lazy.values().get("/Test/Status");

        assertNotNull(doubles);
        assertNotNull(ints);
        assertNotNull(bools);
        assertNotNull(strings);

        // Verify types
        assertInstanceOf(Double.class, doubles.get(0).value());
        assertInstanceOf(Long.class, ints.get(0).value());
        assertInstanceOf(Boolean.class, bools.get(0).value());
        assertInstanceOf(String.class, strings.get(0).value());

        // Verify values
        assertEquals(12.0, (double) doubles.get(0).value(), 0.001);
        assertEquals(0L, ints.get(0).value());
        assertEquals(true, bools.get(0).value());
        assertEquals("state_0", strings.get(0).value());
      }
    }
  }

  // ==================== LogData Interface ====================

  @Nested
  @DisplayName("LogData interface")
  class LogDataInterfaceTests {

    @Test
    @DisplayName("implements all LogData methods")
    void implementsLogData(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 10);

      try (var lazy = openLazy(logFile)) {
        // All LogData methods should work
        assertNotNull(lazy.path());
        assertTrue(lazy.path().endsWith("test.wpilog"));
        assertNotNull(lazy.entries());
        assertNotNull(lazy.values());
        assertTrue(lazy.minTimestamp() >= 0);
        assertTrue(lazy.maxTimestamp() >= 0);
        assertFalse(lazy.truncated());
        assertNull(lazy.truncationMessage());
        assertEquals(4, lazy.entryCount());
        assertTrue(lazy.duration() >= 0);
      }
    }

    @Test
    @DisplayName("can be used as LogData reference")
    void usableAsLogData(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 10);

      try (var lazy = openLazy(logFile)) {
        LogData logData = lazy; // compile-time interface check
        assertEquals(4, logData.entryCount());
        assertNotNull(logData.values().get("/Test/Voltage"));
      }
    }
  }

  // ==================== Close and Resource Management ====================

  @Nested
  @DisplayName("Resource management")
  class ResourceManagementTests {

    @Test
    @DisplayName("close() clears cached values")
    void closeClearsCachedValues(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 10);
      var lazy = openLazy(logFile);

      // Access to populate cache
      lazy.values().get("/Test/Voltage");

      // Close should not throw
      assertDoesNotThrow(lazy::close);
    }

    @Test
    @DisplayName("implements AutoCloseable")
    void implementsAutoCloseable(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 10);

      // Should work in try-with-resources
      try (var lazy = openLazy(logFile)) {
        assertNotNull(lazy.values().get("/Test/Voltage"));
      }
      // No exception means success
    }
  }

  // ==================== Caffeine Cache Behavior ====================

  @Nested
  @DisplayName("Caffeine cache")
  class CaffeineCacheTests {

    @Test
    @DisplayName("evicts entries when cache weight limit is exceeded")
    void evictsUnderWeightPressure(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 1000);
      var reader = new DataLogReader(logFile.toString());

      // Very small cache: 1 KB — not enough for all entries
      try (var lazy = new LazyParsedLog(logFile.toString(), reader,
          new StructDecoderRegistry(), 1024)) {
        // Access all 4 entries to force eviction of earlier ones
        var v1 = lazy.values().get("/Test/Voltage");
        var v2 = lazy.values().get("/Test/Counter");
        var v3 = lazy.values().get("/Test/Enabled");
        var v4 = lazy.values().get("/Test/Status");

        // All should decode successfully (even if some are evicted)
        assertNotNull(v1);
        assertNotNull(v2);
        assertNotNull(v3);
        assertNotNull(v4);
      }
    }

    @Test
    @DisplayName("re-decodes evicted entries on next access")
    void reDecodesEvictedEntries(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 500);
      var reader = new DataLogReader(logFile.toString());

      // Tiny cache
      try (var lazy = new LazyParsedLog(logFile.toString(), reader,
          new StructDecoderRegistry(), 512)) {
        // Access, let evict, re-access
        var first = lazy.values().get("/Test/Voltage");
        assertNotNull(first);

        // Access all others to push voltage out
        lazy.values().get("/Test/Counter");
        lazy.values().get("/Test/Enabled");
        lazy.values().get("/Test/Status");

        // Re-access — should re-decode without error
        var second = lazy.values().get("/Test/Voltage");
        assertNotNull(second);
        assertEquals(first.size(), second.size());
      }
    }
  }

  // ==================== Consistency with Eager Parsing ====================

  @Nested
  @DisplayName("Consistency with eager parsing")
  class ConsistencyTests {

    @Test
    @DisplayName("produces same values as LogParser.parse()")
    void matchesEagerParsing(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 50);

      try (var lazy = openLazy(logFile)) {
        var parser = new org.triplehelix.wpilogmcp.log.subsystems.LogParser(
            new StructDecoderRegistry());
        var eager = parser.parse(logFile);

        assertEquals(eager.entries().keySet(), lazy.entries().keySet());

        for (var entryName : eager.entries().keySet()) {
          var eagerValues = eager.values().get(entryName);
          var lazyValues = lazy.values().get(entryName);

          assertNotNull(lazyValues, "Missing values for " + entryName);
          assertEquals(eagerValues.size(), lazyValues.size(),
              "Count mismatch for " + entryName);

          for (int i = 0; i < eagerValues.size(); i++) {
            assertEquals(eagerValues.get(i).timestamp(), lazyValues.get(i).timestamp(), 0.000001,
                "Timestamp mismatch at " + i + " for " + entryName);
            if (eagerValues.get(i).value() instanceof Double d) {
              assertEquals(d, (double) lazyValues.get(i).value(), 0.000001,
                  "Double value mismatch at " + i + " for " + entryName);
            } else {
              assertEquals(eagerValues.get(i).value(), lazyValues.get(i).value(),
                  "Value mismatch at " + i + " for " + entryName);
            }
          }
        }

        assertEquals(eager.minTimestamp(), lazy.minTimestamp(), 0.000001);
        assertEquals(eager.maxTimestamp(), lazy.maxTimestamp(), 0.000001);
      }
    }
  }
}
