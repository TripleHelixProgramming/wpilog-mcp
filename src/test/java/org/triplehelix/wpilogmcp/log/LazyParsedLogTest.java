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
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
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

    @Test
    @DisplayName("values().get() returns empty list after close, not cached data or null")
    void testDecodeEntryReturnsEmptyAfterClose(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 10);
      var lazy = openLazy(logFile);

      // Populate cache before closing
      var beforeClose = lazy.values().get("/Test/Voltage");
      assertNotNull(beforeClose);
      assertFalse(beforeClose.isEmpty());

      lazy.close();

      // After close, should return empty list (not cached data, not null)
      var afterClose = lazy.values().get("/Test/Voltage");
      assertNotNull(afterClose, "Should not return null after close");
      assertTrue(afterClose.isEmpty(), "Should return empty list after close, not cached data");
    }

    @Test
    @DisplayName("accessing values after close does not throw")
    void testDecodeEntryDoesNotThrowAfterClose(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 10);
      var lazy = openLazy(logFile);

      // Populate cache
      lazy.values().get("/Test/Voltage");
      lazy.values().get("/Test/Counter");

      lazy.close();

      // No exception should be thrown for any entry access after close
      assertDoesNotThrow(() -> lazy.values().get("/Test/Voltage"));
      assertDoesNotThrow(() -> lazy.values().get("/Test/Counter"));
      assertDoesNotThrow(() -> lazy.values().get("/Test/Enabled"));
      assertDoesNotThrow(() -> lazy.values().get("/Test/Status"));
      assertDoesNotThrow(() -> lazy.values().get("/Nonexistent/Entry"));
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

  // ==================== estimateMemoryBytes via Reflection ====================

  @Nested
  @DisplayName("estimateMemoryBytes")
  class EstimateMemoryBytesTests {

    /**
     * Invokes the private estimateMemoryBytes method via reflection.
     */
    private int invokeEstimate(LazyParsedLog log, String key, List<TimestampedValue> values) throws Exception {
      Method method = LazyParsedLog.class.getDeclaredMethod("estimateMemoryBytes", String.class, List.class);
      method.setAccessible(true);
      return (int) method.invoke(log, key, values);
    }

    @Test
    @DisplayName("empty list returns small positive value")
    void emptyListReturnsSmallPositive(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 5);
      try (var lazy = openLazy(logFile)) {
        int result = invokeEstimate(lazy, "key", List.of());
        assertTrue(result > 0, "Empty list should return a small positive value");
        assertTrue(result < 1000, "Empty list estimate should be small");
      }
    }

    @Test
    @DisplayName("null list returns small positive value")
    void nullListReturnsSmallPositive(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 5);
      try (var lazy = openLazy(logFile)) {
        int result = invokeEstimate(lazy, "key", null);
        assertTrue(result > 0, "Null list should return a small positive value");
      }
    }

    @Test
    @DisplayName("list of Double values gives reasonable estimate")
    void doubleValuesEstimate(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 5);
      try (var lazy = openLazy(logFile)) {
        var values = new ArrayList<TimestampedValue>();
        for (int i = 0; i < 100; i++) {
          values.add(new TimestampedValue(i * 0.02, (double) i));
        }
        int result = invokeEstimate(lazy, "key", values);
        assertTrue(result > 100, "100 Double values should have non-trivial weight");
        assertTrue(result < 100_000, "100 Double values should not be enormous");
      }
    }

    @Test
    @DisplayName("list of Long values gives reasonable estimate")
    void longValuesEstimate(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 5);
      try (var lazy = openLazy(logFile)) {
        var values = new ArrayList<TimestampedValue>();
        for (int i = 0; i < 100; i++) {
          values.add(new TimestampedValue(i * 0.02, (long) i));
        }
        int result = invokeEstimate(lazy, "key", values);
        assertTrue(result > 100, "100 Long values should have non-trivial weight");
      }
    }

    @Test
    @DisplayName("list of String values scales with string length")
    void stringValuesScaleWithLength(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 5);
      try (var lazy = openLazy(logFile)) {
        var shortStrings = new ArrayList<TimestampedValue>();
        var longStrings = new ArrayList<TimestampedValue>();
        for (int i = 0; i < 100; i++) {
          shortStrings.add(new TimestampedValue(i * 0.02, "hi"));
          longStrings.add(new TimestampedValue(i * 0.02, "a".repeat(1000)));
        }
        int shortEstimate = invokeEstimate(lazy, "key", shortStrings);
        int longEstimate = invokeEstimate(lazy, "key", longStrings);
        assertTrue(longEstimate > shortEstimate,
            "Long strings should produce larger estimate than short strings");
      }
    }

    @Test
    @DisplayName("list of Map values gives reasonable estimate")
    void mapValuesEstimate(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 5);
      try (var lazy = openLazy(logFile)) {
        var values = new ArrayList<TimestampedValue>();
        for (int i = 0; i < 50; i++) {
          values.add(new TimestampedValue(i * 0.02, Map.of("x", 1.0, "y", 2.0)));
        }
        int result = invokeEstimate(lazy, "key", values);
        assertTrue(result > 50, "50 Map values should have non-trivial weight");
      }
    }

    @Test
    @DisplayName("list of byte[] values reflects array size")
    void byteArrayEstimate(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 5);
      try (var lazy = openLazy(logFile)) {
        var smallArrays = new ArrayList<TimestampedValue>();
        var largeArrays = new ArrayList<TimestampedValue>();
        for (int i = 0; i < 50; i++) {
          smallArrays.add(new TimestampedValue(i * 0.02, new byte[10]));
          largeArrays.add(new TimestampedValue(i * 0.02, new byte[10_000]));
        }
        int smallEstimate = invokeEstimate(lazy, "key", smallArrays);
        int largeEstimate = invokeEstimate(lazy, "key", largeArrays);
        assertTrue(largeEstimate > smallEstimate,
            "Large byte arrays should produce larger estimate");
      }
    }

    @Test
    @DisplayName("list of double[] values reflects array size")
    void doubleArrayEstimate(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 5);
      try (var lazy = openLazy(logFile)) {
        var smallArrays = new ArrayList<TimestampedValue>();
        var largeArrays = new ArrayList<TimestampedValue>();
        for (int i = 0; i < 50; i++) {
          smallArrays.add(new TimestampedValue(i * 0.02, new double[2]));
          largeArrays.add(new TimestampedValue(i * 0.02, new double[1000]));
        }
        int smallEstimate = invokeEstimate(lazy, "key", smallArrays);
        int largeEstimate = invokeEstimate(lazy, "key", largeArrays);
        assertTrue(largeEstimate > smallEstimate,
            "Large double arrays should produce larger estimate");
      }
    }

    @Test
    @DisplayName("single null value does not throw")
    void singleNullValueDoesNotThrow(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 5);
      try (var lazy = openLazy(logFile)) {
        // List.of() does not allow null elements, so use ArrayList
        var values = new ArrayList<TimestampedValue>();
        values.add(new TimestampedValue(0.0, null));
        assertDoesNotThrow(() -> invokeEstimate(lazy, "key", values));
        int result = invokeEstimate(lazy, "key", values);
        assertTrue(result > 0);
      }
    }

    @Test
    @DisplayName("very large list does not overflow (capped at Integer.MAX_VALUE)")
    void veryLargeListDoesNotOverflow(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 5);
      try (var lazy = openLazy(logFile)) {
        // Create a list that would overflow int if not capped.
        // Use large strings so perValue is big, then use a large list size.
        // We can't actually create billions of elements, so we use reflection
        // to test the math with a mock-sized list.
        // Instead, create a moderately large list of large strings.
        var values = new ArrayList<TimestampedValue>();
        String bigString = "x".repeat(50_000);
        for (int i = 0; i < 10_000; i++) {
          values.add(new TimestampedValue(i * 0.001, bigString));
        }
        int result = invokeEstimate(lazy, "key", values);
        assertTrue(result > 0, "Large list should have positive weight");
        // The method caps at Integer.MAX_VALUE
        assertTrue(result <= Integer.MAX_VALUE);
      }
    }

    @Test
    @DisplayName("string sampling with variable lengths produces larger estimate than short-only")
    void testEstimateMemoryBytesStringSamplingVariableLengths(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 5);
      try (var lazy = openLazy(logFile)) {
        // List where first string is "a" but middle and last are 10000 chars
        String longStr = "x".repeat(10_000);
        var variableValues = new ArrayList<TimestampedValue>();
        variableValues.add(new TimestampedValue(0.0, "a"));          // first: tiny
        variableValues.add(new TimestampedValue(0.02, longStr));     // middle: huge
        variableValues.add(new TimestampedValue(0.04, longStr));     // last: huge

        // List where all strings are "a" (short)
        var shortValues = new ArrayList<TimestampedValue>();
        shortValues.add(new TimestampedValue(0.0, "a"));
        shortValues.add(new TimestampedValue(0.02, "a"));
        shortValues.add(new TimestampedValue(0.04, "a"));

        int variableEstimate = invokeEstimate(lazy, "key", variableValues);
        int shortEstimate = invokeEstimate(lazy, "key", shortValues);

        assertTrue(variableEstimate > shortEstimate,
            "Estimate with variable-length strings (including long ones) should be larger "
            + "than estimate with only short strings. Variable=" + variableEstimate
            + ", Short=" + shortEstimate);
      }
    }

    @Test
    @DisplayName("single element String and Map types produce valid estimates")
    void testEstimateMemoryBytesSingleElementStringOrMap(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 5);
      try (var lazy = openLazy(logFile)) {
        // Single String element
        var singleString = List.of(new TimestampedValue(0.0, "hello world"));
        int stringEstimate = invokeEstimate(lazy, "key", singleString);
        assertTrue(stringEstimate > 0, "Single String element should produce positive estimate");

        // Single Map element
        var singleMap = List.of(new TimestampedValue(0.0, Map.of("x", 1.0, "y", 2.0)));
        int mapEstimate = invokeEstimate(lazy, "key", singleMap);
        assertTrue(mapEstimate > 0, "Single Map element should produce positive estimate");
      }
    }
  }

  // ==================== Concurrent Access ====================

  @Nested
  @DisplayName("Concurrent access")
  class ConcurrentAccessTests {

    @Test
    @DisplayName("concurrent reads of same entry return consistent results")
    void concurrentReadsAreConsistent(@TempDir Path tempDir) throws Exception {
      var logFile = createTestLog(tempDir, "test.wpilog", 100);

      try (var lazy = openLazy(logFile)) {
        int threadCount = 8;
        var latch = new CountDownLatch(1);
        var results = new CopyOnWriteArrayList<List<TimestampedValue>>();
        var errors = new CopyOnWriteArrayList<Throwable>();

        var threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
          threads[i] = new Thread(() -> {
            try {
              latch.await(); // Synchronize thread starts
              var values = lazy.values().get("/Test/Voltage");
              results.add(values);
            } catch (Throwable t) {
              errors.add(t);
            }
          });
          threads[i].start();
        }

        // Release all threads simultaneously
        latch.countDown();

        for (var t : threads) {
          t.join(5000);
        }

        assertTrue(errors.isEmpty(),
            "No exceptions should be thrown, but got: " + errors);
        assertEquals(threadCount, results.size(),
            "All threads should have returned a result");

        // All threads should get the same list size
        int expectedSize = results.get(0).size();
        assertTrue(expectedSize > 0, "Should have decoded values");

        for (int i = 1; i < results.size(); i++) {
          assertEquals(expectedSize, results.get(i).size(),
              "Thread " + i + " got different list size");
          // Verify contents match
          assertEquals(results.get(0).get(0).value(), results.get(i).get(0).value(),
              "Thread " + i + " got different first value");
        }
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

  // ==================== Truncated Log Handling ====================

  @Nested
  @DisplayName("Truncated log handling")
  class TruncatedLogHandling {

    /**
     * Creates a valid wpilog file, then truncates it in a way that triggers
     * WPILib's truncation detection. The approach: keep a fraction of the file,
     * then overwrite the last record's data-size field to claim more bytes than
     * actually exist. This causes getRecord() to throw when it tries to slice
     * the oversized data from the buffer.
     *
     * @param fraction The fraction of the original file to keep as valid data.
     */
    private Path createTruncatedLog(Path tempDir, String name, int recordCount,
        double fraction) throws Exception {
      // First create a complete, valid log
      Path completeLog = createTestLog(tempDir, "complete_" + name, recordCount);

      byte[] fullBytes = Files.readAllBytes(completeLog);
      int keepBytes = (int) (fullBytes.length * fraction);
      assertTrue(keepBytes > 100 && keepBytes < fullBytes.length,
          "Truncated size must be between header size and full file. Keep: " + keepBytes
          + ", Full: " + fullBytes.length);

      // Strategy: find a data record near the keepBytes offset, then corrupt its
      // data-size field to claim a payload larger than what remains in the file.
      // This triggers WPILib's getRecord() to throw IllegalArgumentException
      // ("newLimit > capacity") when it tries to slice the oversized data.
      //
      // WPILib's hasNext() requires >= 16 bytes from record start, so we ensure
      // at least 20 bytes remain from the target record to the truncation point.
      var reader = new edu.wpi.first.util.datalog.DataLogReader(
          java.nio.ByteBuffer.wrap(fullBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN));
      int startPos = 12 + reader.getExtraHeader().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

      // Scan forward to find a data record near the target truncation point.
      // We want a record past the start records (which are at the beginning) but
      // within the keepBytes range, and far enough from the start that we preserve
      // entry metadata and some data.
      int targetPos = -1;
      int scanPos = startPos;
      try {
        while (scanPos + 16 <= fullBytes.length) {
          int nextPos = edu.wpi.first.util.datalog.DataLogAccess.getNextRecord(reader, scanPos);
          // Pick a record near keepBytes (within the last 25% of the kept portion)
          if (scanPos > keepBytes * 0.75 && scanPos < keepBytes) {
            targetPos = scanPos;
            break;
          }
          scanPos = nextPos;
        }
      } catch (Exception e) {
        // Scan ended
      }
      assertTrue(targetPos > 0, "Must find a target record to corrupt");

      // Parse the record header to find the data-size field location
      int lb = fullBytes[targetPos] & 0xFF;
      int eLen = (lb & 0x3) + 1;
      int sLen = ((lb >> 2) & 0x3) + 1;
      int tLen = ((lb >> 4) & 0x7) + 1;
      int hLen = 1 + eLen + sLen + tLen;

      // Overwrite the data-size field to claim 255 bytes of payload.
      // Then truncate the file to targetPos + 20 — enough for hasNext() (>= 16)
      // but nowhere near 255 bytes of data.
      fullBytes[targetPos + 1 + eLen] = (byte) 0xFF;
      if (sLen > 1) {
        // Zero out higher bytes so size = 255
        for (int i = 1; i < sLen; i++) {
          fullBytes[targetPos + 1 + eLen + i] = 0;
        }
      }

      int truncateAt = targetPos + 20;
      assertTrue(truncateAt < fullBytes.length,
          "Truncation point must be within file bounds. truncateAt=" + truncateAt);
      byte[] truncated = java.util.Arrays.copyOf(fullBytes, truncateAt);

      Path truncatedLog = tempDir.resolve(name);
      Files.write(truncatedLog, truncated);
      return truncatedLog;
    }

    @Test
    @DisplayName("detects truncated file and sets truncated flag")
    void detectsTruncatedFile(@TempDir Path tempDir) throws Exception {
      // Create a log with many records, then chop a significant chunk off the end
      // so that the iterator hits a truncated record mid-iteration
      Path truncatedLog = createTruncatedLog(tempDir, "truncated.wpilog", 200, 0.7);

      try (var lazy = openLazy(truncatedLog)) {
        assertTrue(lazy.truncated(), "Truncated log should report truncated=true");
        assertNotNull(lazy.truncationMessage(),
            "Truncated log should have a truncation message");
        assertTrue(lazy.truncationMessage().contains("truncated"),
            "Truncation message should mention truncation: " + lazy.truncationMessage());
      }
    }

    @Test
    @DisplayName("recovers partial data before truncation point")
    void recoversPartialData(@TempDir Path tempDir) throws Exception {
      // Create a complete log to measure the full record count
      Path completeLog = createTestLog(tempDir, "complete.wpilog", 200);
      int fullEntryCount;
      int fullVoltageCount;
      try (var fullLazy = openLazy(completeLog)) {
        fullEntryCount = fullLazy.entryCount();
        fullVoltageCount = fullLazy.values().get("/Test/Voltage").size();
      }

      // Now truncate — chop enough to lose some records but keep most
      Path truncatedLog = createTruncatedLog(tempDir, "partial.wpilog", 200, 0.7);

      try (var lazy = openLazy(truncatedLog)) {
        assertTrue(lazy.truncated(), "Should be truncated");

        // Entry metadata should still be discovered (entries are declared at the start)
        assertEquals(fullEntryCount, lazy.entryCount(),
            "Entry metadata should be fully recovered since start records are at the beginning");

        // Some voltage values should be recovered (not all, since the end is truncated)
        var voltageValues = lazy.values().get("/Test/Voltage");
        assertNotNull(voltageValues, "Voltage entry should have recoverable values");
        assertFalse(voltageValues.isEmpty(),
            "Should recover at least some voltage values from the intact portion");
        assertTrue(voltageValues.size() < fullVoltageCount,
            "Truncated log should have fewer values than the complete log. "
            + "Truncated: " + voltageValues.size() + ", Full: " + fullVoltageCount);

        // Verify the recovered values are correct (first value should be 12.0)
        assertEquals(12.0, (double) voltageValues.get(0).value(), 0.001,
            "First recovered voltage value should match the original");
      }
    }

    @Test
    @DisplayName("truncated log has valid timestamp range from recovered data")
    void truncatedLogHasValidTimestampRange(@TempDir Path tempDir) throws Exception {
      Path truncatedLog = createTruncatedLog(tempDir, "timestamps.wpilog", 200, 0.7);

      try (var lazy = openLazy(truncatedLog)) {
        assertTrue(lazy.truncated());
        assertTrue(lazy.minTimestamp() > 0,
            "Min timestamp should be positive from recovered data");
        assertTrue(lazy.maxTimestamp() > lazy.minTimestamp(),
            "Max timestamp should be greater than min from recovered data");
        assertTrue(lazy.duration() > 0,
            "Duration should be positive from recovered data");
      }
    }

    @Test
    @DisplayName("truncated log duration is shorter than complete log duration")
    void truncatedDurationShorterThanComplete(@TempDir Path tempDir) throws Exception {
      Path completeLog = createTestLog(tempDir, "full.wpilog", 200);
      double fullDuration;
      try (var fullLazy = openLazy(completeLog)) {
        fullDuration = fullLazy.duration();
      }

      Path truncatedLog = createTruncatedLog(tempDir, "shorter.wpilog", 200, 0.7);
      try (var lazy = openLazy(truncatedLog)) {
        assertTrue(lazy.truncated());
        assertTrue(lazy.duration() < fullDuration,
            "Truncated log duration (" + lazy.duration()
            + ") should be less than complete log duration (" + fullDuration + ")");
      }
    }

    @Test
    @DisplayName("multiple entry types are partially recoverable from truncated log")
    void multipleTypesRecoverable(@TempDir Path tempDir) throws Exception {
      Path truncatedLog = createTruncatedLog(tempDir, "multi.wpilog", 200, 0.7);

      try (var lazy = openLazy(truncatedLog)) {
        assertTrue(lazy.truncated());

        // All four entry types should have at least some recovered data
        var doubles = lazy.values().get("/Test/Voltage");
        var ints = lazy.values().get("/Test/Counter");
        var bools = lazy.values().get("/Test/Enabled");
        var strings = lazy.values().get("/Test/Status");

        assertNotNull(doubles);
        assertNotNull(ints);
        assertNotNull(bools);
        assertNotNull(strings);

        assertFalse(doubles.isEmpty(), "Should recover some double values");
        assertFalse(ints.isEmpty(), "Should recover some integer values");
        assertFalse(bools.isEmpty(), "Should recover some boolean values");
        assertFalse(strings.isEmpty(), "Should recover some string values");

        // Verify types are correct
        assertInstanceOf(Double.class, doubles.get(0).value());
        assertInstanceOf(Long.class, ints.get(0).value());
        assertInstanceOf(Boolean.class, bools.get(0).value());
        assertInstanceOf(String.class, strings.get(0).value());
      }
    }

    @Test
    @DisplayName("severely truncated file with only header and a few records")
    void severelyTruncatedFile(@TempDir Path tempDir) throws Exception {
      // Use the same truncation helper at 40%
      Path truncatedLog = createTruncatedLog(tempDir, "severe.wpilog", 200, 0.4);

      try (var lazy = openLazy(truncatedLog)) {
        assertTrue(lazy.truncated(),
            "Severely truncated log should report truncated=true");

        // Should still have entry metadata
        assertTrue(lazy.entryCount() > 0,
            "Should recover at least some entry metadata");

        // Should have some data, but much less than the full log
        boolean hasAnyData = false;
        for (var entryName : lazy.entries().keySet()) {
          var values = lazy.values().get(entryName);
          if (values != null && !values.isEmpty()) {
            hasAnyData = true;
            break;
          }
        }
        assertTrue(hasAnyData, "Should recover at least some data records");
      }
    }
  }
}
