package org.triplehelix.wpilogmcp.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

@DisplayName("DiskCacheSerializer")
class DiskCacheSerializerTest {

  @TempDir Path tempDir;
  private DiskCacheSerializer serializer;

  @BeforeEach
  void setUp() {
    serializer = new DiskCacheSerializer();
  }

  private CacheMetadata testMetadata() {
    return new CacheMetadata(
        DiskCacheSerializer.CURRENT_FORMAT_VERSION,
        "0.5.0", "/test/log.wpilog", 1024, 1710000000000L,
        "abcdef1234567890", System.currentTimeMillis());
  }

  private ParsedLog roundTrip(ParsedLog log) throws IOException {
    Path cacheFile = tempDir.resolve("rt-" + System.nanoTime() + ".msgpack");
    serializer.write(log, cacheFile, testMetadata());
    return serializer.read(cacheFile);
  }

  // ==================== Primitive Value Type Round-Trips ====================

  @Nested
  @DisplayName("Primitive Value Round-Trips")
  class PrimitiveRoundTrips {

    @Test
    @DisplayName("double values")
    void doubleValues() throws IOException {
      var values = List.of(
          new TimestampedValue(0.0, 1.5),
          new TimestampedValue(0.02, -3.14159),
          new TimestampedValue(0.04, 0.0),
          new TimestampedValue(0.06, Double.MAX_VALUE),
          new TimestampedValue(0.08, Double.MIN_VALUE));

      var log = new ParsedLog("/test/log.wpilog",
          Map.of("v", new EntryInfo(1, "v", "double", "")),
          Map.of("v", new ArrayList<>(values)),
          0.0, 0.08);

      var restored = roundTrip(log);
      assertNotNull(restored);
      var rv = restored.values().get("v");
      assertEquals(5, rv.size());
      assertEquals(1.5, ((Number) rv.get(0).value()).doubleValue(), 1e-10);
      assertEquals(-3.14159, ((Number) rv.get(1).value()).doubleValue(), 1e-10);
      assertEquals(0.0, ((Number) rv.get(2).value()).doubleValue(), 1e-10);
      assertEquals(Double.MAX_VALUE, ((Number) rv.get(3).value()).doubleValue(), 1e-10);
    }

    @Test
    @DisplayName("float values")
    void floatValues() throws IOException {
      var values = List.of(
          new TimestampedValue(0.0, 1.5f),
          new TimestampedValue(0.02, -0.001f));

      var log = new ParsedLog("/test/log.wpilog",
          Map.of("f", new EntryInfo(1, "f", "float", "")),
          Map.of("f", new ArrayList<>(values)),
          0.0, 0.02);

      var restored = roundTrip(log);
      assertNotNull(restored);
      // Float is deserialized as Double (MessagePack promotion)
      assertEquals(1.5, ((Number) restored.values().get("f").get(0).value()).doubleValue(), 0.01);
    }

    @Test
    @DisplayName("int64 values including extremes")
    void int64Values() throws IOException {
      var values = List.of(
          new TimestampedValue(0.0, 0L),
          new TimestampedValue(1.0, 42L),
          new TimestampedValue(2.0, -1L),
          new TimestampedValue(3.0, Long.MAX_VALUE),
          new TimestampedValue(4.0, Long.MIN_VALUE));

      var log = new ParsedLog("/test/log.wpilog",
          Map.of("i", new EntryInfo(1, "i", "int64", "")),
          Map.of("i", new ArrayList<>(values)),
          0.0, 4.0);

      var restored = roundTrip(log);
      assertNotNull(restored);
      var rv = restored.values().get("i");
      assertEquals(0L, ((Number) rv.get(0).value()).longValue());
      assertEquals(42L, ((Number) rv.get(1).value()).longValue());
      assertEquals(-1L, ((Number) rv.get(2).value()).longValue());
      assertEquals(Long.MAX_VALUE, ((Number) rv.get(3).value()).longValue());
      assertEquals(Long.MIN_VALUE, ((Number) rv.get(4).value()).longValue());
    }

    @Test
    @DisplayName("boolean values")
    void booleanValues() throws IOException {
      var values = List.of(
          new TimestampedValue(0.0, true),
          new TimestampedValue(1.0, false),
          new TimestampedValue(2.0, true));

      var log = new ParsedLog("/test/log.wpilog",
          Map.of("b", new EntryInfo(1, "b", "boolean", "")),
          Map.of("b", new ArrayList<>(values)),
          0.0, 2.0);

      var restored = roundTrip(log);
      assertNotNull(restored);
      assertEquals(true, restored.values().get("b").get(0).value());
      assertEquals(false, restored.values().get("b").get(1).value());
      assertEquals(true, restored.values().get("b").get(2).value());
    }

    @Test
    @DisplayName("string values including empty and unicode")
    void stringValues() throws IOException {
      var values = List.of(
          new TimestampedValue(0.0, "INTAKING"),
          new TimestampedValue(1.0, ""),
          new TimestampedValue(2.0, "special chars: <>&\"'"),
          new TimestampedValue(3.0, "unicode: \u00e9\u00e0\u00fc\u2603"));

      var log = new ParsedLog("/test/log.wpilog",
          Map.of("s", new EntryInfo(1, "s", "string", "")),
          Map.of("s", new ArrayList<>(values)),
          0.0, 3.0);

      var restored = roundTrip(log);
      assertNotNull(restored);
      var rv = restored.values().get("s");
      assertEquals("INTAKING", rv.get(0).value());
      assertEquals("", rv.get(1).value());
      assertEquals("special chars: <>&\"'", rv.get(2).value());
      assertEquals("unicode: \u00e9\u00e0\u00fc\u2603", rv.get(3).value());
    }

    @Test
    @DisplayName("null values")
    void nullValues() throws IOException {
      var values = List.of(
          new TimestampedValue(0.0, (Object) null),
          new TimestampedValue(1.0, 42.0));

      var log = new ParsedLog("/test/log.wpilog",
          Map.of("n", new EntryInfo(1, "n", "double", "")),
          Map.of("n", new ArrayList<>(values)),
          0.0, 1.0);

      var restored = roundTrip(log);
      assertNotNull(restored);
      assertNull(restored.values().get("n").get(0).value());
      assertNotNull(restored.values().get("n").get(1).value());
    }

    @Test
    @DisplayName("byte array (raw) values")
    void byteArrayValues() throws IOException {
      byte[] data = new byte[]{0x00, 0x01, (byte) 0xFF, 0x42, (byte) 0xAB};
      var values = List.of(
          new TimestampedValue(0.0, data),
          new TimestampedValue(1.0, new byte[0])); // empty array

      var log = new ParsedLog("/test/log.wpilog",
          Map.of("raw", new EntryInfo(1, "raw", "raw", "")),
          Map.of("raw", new ArrayList<>(values)),
          0.0, 1.0);

      var restored = roundTrip(log);
      assertNotNull(restored);
      assertArrayEquals(data, (byte[]) restored.values().get("raw").get(0).value());
      assertArrayEquals(new byte[0], (byte[]) restored.values().get("raw").get(1).value());
    }
  }

  // ==================== Array Value Type Round-Trips ====================

  @Nested
  @DisplayName("Array Value Round-Trips")
  class ArrayRoundTrips {

    @Test
    @DisplayName("double[] preserves original array type")
    void doubleArrayPreservesType() throws IOException {
      double[] arr = {1.0, 2.5, -3.14, 0.0};
      var values = List.of(new TimestampedValue(0.0, arr));

      var log = new ParsedLog("/test/log.wpilog",
          Map.of("da", new EntryInfo(1, "da", "double[]", "")),
          Map.of("da", new ArrayList<>(values)),
          0.0, 0.0);

      var restored = roundTrip(log);
      assertNotNull(restored);
      var restoredVal = restored.values().get("da").get(0).value();
      assertInstanceOf(double[].class, restoredVal,
          "double[] should round-trip as double[], not List<Object>");
      var restoredArr = (double[]) restoredVal;
      assertEquals(4, restoredArr.length);
      assertEquals(1.0, restoredArr[0], 1e-10);
      assertEquals(-3.14, restoredArr[2], 1e-10);
    }

    @Test
    @DisplayName("long[] preserves original array type")
    void longArrayPreservesType() throws IOException {
      long[] arr = {0L, 42L, -1L, Long.MAX_VALUE};
      var values = List.of(new TimestampedValue(0.0, arr));

      var log = new ParsedLog("/test/log.wpilog",
          Map.of("la", new EntryInfo(1, "la", "int64[]", "")),
          Map.of("la", new ArrayList<>(values)),
          0.0, 0.0);

      var restored = roundTrip(log);
      assertNotNull(restored);
      var restoredVal = restored.values().get("la").get(0).value();
      assertInstanceOf(long[].class, restoredVal,
          "long[] should round-trip as long[], not List<Object>");
      var restoredArr = (long[]) restoredVal;
      assertEquals(4, restoredArr.length);
      assertEquals(Long.MAX_VALUE, restoredArr[3]);
    }

    @Test
    @DisplayName("boolean[] preserves original array type")
    void booleanArrayPreservesType() throws IOException {
      boolean[] arr = {true, false, true, true, false};
      var values = List.of(new TimestampedValue(0.0, arr));

      var log = new ParsedLog("/test/log.wpilog",
          Map.of("ba", new EntryInfo(1, "ba", "boolean[]", "")),
          Map.of("ba", new ArrayList<>(values)),
          0.0, 0.0);

      var restored = roundTrip(log);
      assertNotNull(restored);
      var restoredVal = restored.values().get("ba").get(0).value();
      assertInstanceOf(boolean[].class, restoredVal,
          "boolean[] should round-trip as boolean[], not List<Object>");
      var restoredArr = (boolean[]) restoredVal;
      assertEquals(5, restoredArr.length);
      assertTrue(restoredArr[0]);
      assertFalse(restoredArr[1]);
    }

    @Test
    @DisplayName("String[] preserves original array type")
    void stringArrayPreservesType() throws IOException {
      String[] arr = {"hello", "world", ""};
      var values = List.of(new TimestampedValue(0.0, arr));

      var log = new ParsedLog("/test/log.wpilog",
          Map.of("sa", new EntryInfo(1, "sa", "string[]", "")),
          Map.of("sa", new ArrayList<>(values)),
          0.0, 0.0);

      var restored = roundTrip(log);
      assertNotNull(restored);
      var restoredVal = restored.values().get("sa").get(0).value();
      assertInstanceOf(String[].class, restoredVal,
          "String[] should round-trip as String[], not List<Object>");
      var restoredArr = (String[]) restoredVal;
      assertEquals(3, restoredArr.length);
      assertEquals("hello", restoredArr[0]);
      assertEquals("", restoredArr[2]);
    }

    @Test
    @DisplayName("empty arrays preserve type")
    void emptyArraysPreserveType() throws IOException {
      var entries = new HashMap<String, EntryInfo>();
      entries.put("ed", new EntryInfo(1, "ed", "double[]", ""));
      entries.put("es", new EntryInfo(2, "es", "string[]", ""));
      var vals = new HashMap<String, List<TimestampedValue>>();
      vals.put("ed", new ArrayList<>(List.of(new TimestampedValue(0.0, new double[0]))));
      vals.put("es", new ArrayList<>(List.of(new TimestampedValue(1.0, new String[0]))));

      var log = new ParsedLog("/test/log.wpilog", entries, vals, 0.0, 1.0);
      var restored = roundTrip(log);
      assertNotNull(restored);
      assertInstanceOf(double[].class, restored.values().get("ed").get(0).value());
      assertEquals(0, ((double[]) restored.values().get("ed").get(0).value()).length);
      assertInstanceOf(String[].class, restored.values().get("es").get(0).value());
      assertEquals(0, ((String[]) restored.values().get("es").get(0).value()).length);
    }

    @Test
    @DisplayName("float[] preserves original array type")
    void floatArrayPreservesType() throws IOException {
      float[] arr = {1.0f, -0.5f, 3.14f};
      var values = List.of(new TimestampedValue(0.0, arr));

      var log = new ParsedLog("/test/log.wpilog",
          Map.of("fa", new EntryInfo(1, "fa", "float[]", "")),
          Map.of("fa", new ArrayList<>(values)),
          0.0, 0.0);

      var restored = roundTrip(log);
      assertNotNull(restored);
      var restoredVal = restored.values().get("fa").get(0).value();
      assertInstanceOf(float[].class, restoredVal,
          "float[] should round-trip as float[], not List<Object>");
      var restoredArr = (float[]) restoredVal;
      assertEquals(3, restoredArr.length);
      assertEquals(1.0f, restoredArr[0], 0.01f);
    }
  }

  // ==================== Struct (Map) Value Round-Trips ====================

  @Nested
  @DisplayName("Struct (Map) Value Round-Trips")
  class StructRoundTrips {

    @Test
    @DisplayName("Pose2d with nested translation and rotation")
    void pose2dStruct() throws IOException {
      var pose = new LinkedHashMap<String, Object>();
      var translation = new LinkedHashMap<String, Object>();
      translation.put("x", 1.5);
      translation.put("y", 2.3);
      pose.put("translation", translation);
      var rotation = new LinkedHashMap<String, Object>();
      rotation.put("value", 0.785);
      pose.put("rotation", rotation);

      var values = List.of(new TimestampedValue(0.0, pose));
      var log = new ParsedLog("/test/log.wpilog",
          Map.of("pose", new EntryInfo(1, "pose", "struct:Pose2d", "")),
          Map.of("pose", new ArrayList<>(values)),
          0.0, 0.0);

      var restored = roundTrip(log);
      assertNotNull(restored);
      @SuppressWarnings("unchecked")
      var rPose = (Map<String, Object>) restored.values().get("pose").get(0).value();
      @SuppressWarnings("unchecked")
      var rTrans = (Map<String, Object>) rPose.get("translation");
      assertEquals(1.5, ((Number) rTrans.get("x")).doubleValue(), 1e-10);
      assertEquals(2.3, ((Number) rTrans.get("y")).doubleValue(), 1e-10);
      @SuppressWarnings("unchecked")
      var rRot = (Map<String, Object>) rPose.get("rotation");
      assertEquals(0.785, ((Number) rRot.get("value")).doubleValue(), 1e-10);
    }

    @Test
    @DisplayName("deeply nested struct (Pose3d with quaternion)")
    void deeplyNestedStruct() throws IOException {
      var quat = new LinkedHashMap<String, Object>();
      quat.put("w", 1.0);
      quat.put("x", 0.0);
      quat.put("y", 0.0);
      quat.put("z", 0.0);
      var rotation = new LinkedHashMap<String, Object>();
      rotation.put("quaternion", quat);
      var translation = new LinkedHashMap<String, Object>();
      translation.put("x", 5.0);
      translation.put("y", 3.0);
      translation.put("z", 0.5);
      var pose3d = new LinkedHashMap<String, Object>();
      pose3d.put("translation", translation);
      pose3d.put("rotation", rotation);

      var values = List.of(new TimestampedValue(0.0, pose3d));
      var log = new ParsedLog("/test/log.wpilog",
          Map.of("p3d", new EntryInfo(1, "p3d", "struct:Pose3d", "")),
          Map.of("p3d", new ArrayList<>(values)),
          0.0, 0.0);

      var restored = roundTrip(log);
      @SuppressWarnings("unchecked")
      var rPose = (Map<String, Object>) restored.values().get("p3d").get(0).value();
      @SuppressWarnings("unchecked")
      var rRot = (Map<String, Object>) rPose.get("rotation");
      @SuppressWarnings("unchecked")
      var rQuat = (Map<String, Object>) rRot.get("quaternion");
      assertEquals(1.0, ((Number) rQuat.get("w")).doubleValue(), 1e-10);
    }

    @Test
    @DisplayName("struct array (List of Maps)")
    void structArray() throws IOException {
      var mod1 = new LinkedHashMap<String, Object>();
      mod1.put("speed_mps", 3.5);
      mod1.put("angle_rad", 0.0);
      var mod2 = new LinkedHashMap<String, Object>();
      mod2.put("speed_mps", 2.1);
      mod2.put("angle_rad", 1.57);

      var values = List.of(new TimestampedValue(0.0, List.of(mod1, mod2)));
      var log = new ParsedLog("/test/log.wpilog",
          Map.of("sms", new EntryInfo(1, "sms", "structarray:SwerveModuleState", "")),
          Map.of("sms", new ArrayList<>(values)),
          0.0, 0.0);

      var restored = roundTrip(log);
      @SuppressWarnings("unchecked")
      var rList = (List<Object>) restored.values().get("sms").get(0).value();
      assertEquals(2, rList.size());
      @SuppressWarnings("unchecked")
      var rMod1 = (Map<String, Object>) rList.get(0);
      assertEquals(3.5, ((Number) rMod1.get("speed_mps")).doubleValue(), 1e-10);
    }

    @Test
    @DisplayName("empty map")
    void emptyMap() throws IOException {
      var values = List.of(new TimestampedValue(0.0, new LinkedHashMap<String, Object>()));
      var log = new ParsedLog("/test/log.wpilog",
          Map.of("empty", new EntryInfo(1, "empty", "struct:Empty", "")),
          Map.of("empty", new ArrayList<>(values)),
          0.0, 0.0);

      var restored = roundTrip(log);
      @SuppressWarnings("unchecked")
      var rMap = (Map<String, Object>) restored.values().get("empty").get(0).value();
      assertTrue(rMap.isEmpty());
    }
  }

  // ==================== Log Metadata Round-Trips ====================

  @Nested
  @DisplayName("Log Metadata")
  class LogMetadataTests {

    @Test
    @DisplayName("preserves path")
    void preservesPath() throws IOException {
      var log = new ParsedLog("/team/2024/match42.wpilog",
          Map.of("v", new EntryInfo(1, "v", "double", "")),
          Map.of("v", new ArrayList<>(List.of(new TimestampedValue(0.0, 1.0)))),
          0.0, 0.0);

      var restored = roundTrip(log);
      assertEquals("/team/2024/match42.wpilog", restored.path());
    }

    @Test
    @DisplayName("preserves timestamps")
    void preservesTimestamps() throws IOException {
      var log = new ParsedLog("/test/log.wpilog",
          Map.of("v", new EntryInfo(1, "v", "double", "")),
          Map.of("v", new ArrayList<>(List.of(
              new TimestampedValue(12.345, 1.0),
              new TimestampedValue(167.89, 2.0)))),
          12.345, 167.89);

      var restored = roundTrip(log);
      assertEquals(12.345, restored.minTimestamp(), 1e-10);
      assertEquals(167.89, restored.maxTimestamp(), 1e-10);
    }

    @Test
    @DisplayName("preserves truncation info when truncated")
    void preservesTruncationTrue() throws IOException {
      var log = new ParsedLog("/test/trunc.wpilog",
          Map.of("v", new EntryInfo(1, "v", "double", "")),
          Map.of("v", new ArrayList<>(List.of(new TimestampedValue(0.0, 1.0)))),
          0.0, 0.0, true, "Log truncated at 45.2 seconds");

      var restored = roundTrip(log);
      assertTrue(restored.truncated());
      assertEquals("Log truncated at 45.2 seconds", restored.truncationMessage());
    }

    @Test
    @DisplayName("preserves truncation info when not truncated")
    void preservesTruncationFalse() throws IOException {
      var log = new ParsedLog("/test/clean.wpilog",
          Map.of("v", new EntryInfo(1, "v", "double", "")),
          Map.of("v", new ArrayList<>(List.of(new TimestampedValue(0.0, 1.0)))),
          0.0, 0.0, false, null);

      var restored = roundTrip(log);
      assertFalse(restored.truncated());
      assertNull(restored.truncationMessage());
    }

    @Test
    @DisplayName("preserves entry metadata (id, type, metadata string)")
    void preservesEntryMetadata() throws IOException {
      var log = new ParsedLog("/test/log.wpilog",
          Map.of("sensor", new EntryInfo(42, "sensor", "double", "unit=volts;source=pdh")),
          Map.of("sensor", new ArrayList<>(List.of(new TimestampedValue(0.0, 12.0)))),
          0.0, 0.0);

      var restored = roundTrip(log);
      var entry = restored.entries().get("sensor");
      assertEquals(42, entry.id());
      assertEquals("sensor", entry.name());
      assertEquals("double", entry.type());
      assertEquals("unit=volts;source=pdh", entry.metadata());
    }

    @Test
    @DisplayName("preserves null entry metadata")
    void preservesNullEntryMetadata() throws IOException {
      var log = new ParsedLog("/test/log.wpilog",
          Map.of("v", new EntryInfo(1, "v", "double", null)),
          Map.of("v", new ArrayList<>(List.of(new TimestampedValue(0.0, 1.0)))),
          0.0, 0.0);

      var restored = roundTrip(log);
      assertNull(restored.entries().get("v").metadata());
    }
  }

  // ==================== Multi-Entry Logs ====================

  @Nested
  @DisplayName("Multi-Entry Logs")
  class MultiEntryLogs {

    @Test
    @DisplayName("multiple entries with different types")
    void multipleEntriesDifferentTypes() throws IOException {
      var entries = new HashMap<String, EntryInfo>();
      entries.put("voltage", new EntryInfo(1, "voltage", "double", ""));
      entries.put("enabled", new EntryInfo(2, "enabled", "boolean", ""));
      entries.put("state", new EntryInfo(3, "state", "string", ""));
      entries.put("counter", new EntryInfo(4, "counter", "int64", ""));

      var values = new HashMap<String, List<TimestampedValue>>();
      values.put("voltage", new ArrayList<>(List.of(
          new TimestampedValue(0.0, 12.5),
          new TimestampedValue(1.0, 11.8))));
      values.put("enabled", new ArrayList<>(List.of(
          new TimestampedValue(0.0, true),
          new TimestampedValue(5.0, false))));
      values.put("state", new ArrayList<>(List.of(
          new TimestampedValue(0.0, "IDLE"),
          new TimestampedValue(3.0, "SCORING"))));
      values.put("counter", new ArrayList<>(List.of(
          new TimestampedValue(0.0, 0L),
          new TimestampedValue(1.0, 1L))));

      var log = new ParsedLog("/test/multi.wpilog", entries, values, 0.0, 5.0);
      var restored = roundTrip(log);

      assertNotNull(restored);
      assertEquals(4, restored.entryCount());
      assertEquals(2, restored.values().get("voltage").size());
      assertEquals(2, restored.values().get("enabled").size());
      assertEquals(2, restored.values().get("state").size());
      assertEquals(2, restored.values().get("counter").size());
      assertEquals(12.5, ((Number) restored.values().get("voltage").get(0).value()).doubleValue(), 0.001);
      assertEquals(true, restored.values().get("enabled").get(0).value());
      assertEquals("IDLE", restored.values().get("state").get(0).value());
      assertEquals(0L, ((Number) restored.values().get("counter").get(0).value()).longValue());
    }

    @Test
    @DisplayName("entry with empty value list")
    void entryWithEmptyValueList() throws IOException {
      var log = new ParsedLog("/test/log.wpilog",
          Map.of("empty", new EntryInfo(1, "empty", "double", "")),
          Map.of("empty", new ArrayList<>()),
          0.0, 0.0);

      var restored = roundTrip(log);
      assertNotNull(restored);
      assertEquals(1, restored.entryCount());
      assertEquals(0, restored.values().get("empty").size());
    }

    @Test
    @DisplayName("entry with null value list")
    void entryWithNullValueList() throws IOException {
      var entries = Map.of("missing", new EntryInfo(1, "missing", "double", ""));
      var values = new HashMap<String, List<TimestampedValue>>();
      values.put("missing", null);

      var log = new ParsedLog("/test/log.wpilog", entries, values, 0.0, 0.0);
      var restored = roundTrip(log);

      assertNotNull(restored);
      assertEquals(0, restored.values().get("missing").size());
    }

    @Test
    @DisplayName("large number of entries")
    void manyEntries() throws IOException {
      var entries = new HashMap<String, EntryInfo>();
      var values = new HashMap<String, List<TimestampedValue>>();

      for (int i = 0; i < 200; i++) {
        String name = "/Subsystem" + (i / 10) + "/Entry" + (i % 10);
        entries.put(name, new EntryInfo(i, name, "double", ""));
        var tvs = new ArrayList<TimestampedValue>();
        for (int j = 0; j < 10; j++) {
          tvs.add(new TimestampedValue(j * 0.02, Math.random()));
        }
        values.put(name, tvs);
      }

      var log = new ParsedLog("/test/large.wpilog", entries, values, 0.0, 0.18);
      var restored = roundTrip(log);

      assertNotNull(restored);
      assertEquals(200, restored.entryCount());
      for (var entry : restored.values().entrySet()) {
        assertEquals(10, entry.getValue().size(),
            "Entry " + entry.getKey() + " should have 10 values");
      }
    }

    @Test
    @DisplayName("entry with many values (simulates real match data)")
    void manyValues() throws IOException {
      // 50Hz for 150 seconds = 7500 values
      var tvs = new ArrayList<TimestampedValue>();
      for (int i = 0; i < 7500; i++) {
        tvs.add(new TimestampedValue(i * 0.02, 12.0 + Math.sin(i * 0.01) * 0.5));
      }

      var log = new ParsedLog("/test/match.wpilog",
          Map.of("voltage", new EntryInfo(1, "voltage", "double", "")),
          Map.of("voltage", tvs),
          0.0, 149.98);

      var restored = roundTrip(log);
      assertNotNull(restored);
      assertEquals(7500, restored.values().get("voltage").size());

      // Spot-check a few values
      assertEquals(0.0, restored.values().get("voltage").get(0).timestamp(), 1e-10);
      assertEquals(149.98, restored.values().get("voltage").get(7499).timestamp(), 0.01);
    }
  }

  // ==================== Version and Corruption Handling ====================

  @Nested
  @DisplayName("Version and Corruption Handling")
  class VersionAndCorruption {

    @Test
    @DisplayName("returns null for future format version")
    void futureFormatVersion() throws IOException {
      var log = new ParsedLog("/test/log.wpilog",
          Map.of("v", new EntryInfo(1, "v", "double", "")),
          Map.of("v", new ArrayList<>(List.of(new TimestampedValue(0.0, 1.0)))),
          0.0, 0.0);

      var futureMetadata = new CacheMetadata(
          999, "99.0", "/test/log.wpilog", 1024, 0L, "abc", 0L);

      Path cacheFile = tempDir.resolve("future.msgpack");
      serializer.write(log, cacheFile, futureMetadata);

      assertNull(serializer.read(cacheFile));
    }

    @Test
    @DisplayName("returns null for completely corrupt data")
    void corruptData() throws IOException {
      Path cacheFile = tempDir.resolve("corrupt.msgpack");
      Files.write(cacheFile, new byte[]{0x01, 0x02, 0x03, 0x04, 0x05});

      assertNull(serializer.read(cacheFile));
    }

    @Test
    @DisplayName("returns null for empty file")
    void emptyFile() throws IOException {
      Path cacheFile = tempDir.resolve("empty.msgpack");
      Files.write(cacheFile, new byte[0]);

      assertNull(serializer.read(cacheFile));
    }

    @Test
    @DisplayName("returns null for truncated cache file")
    void truncatedCacheFile() throws IOException {
      // Write a valid cache, then truncate it
      var log = new ParsedLog("/test/log.wpilog",
          Map.of("v", new EntryInfo(1, "v", "double", "")),
          Map.of("v", new ArrayList<>(List.of(new TimestampedValue(0.0, 1.0)))),
          0.0, 0.0);

      Path cacheFile = tempDir.resolve("trunc.msgpack");
      serializer.write(log, cacheFile, testMetadata());

      // Truncate to half the original size
      byte[] original = Files.readAllBytes(cacheFile);
      Files.write(cacheFile, java.util.Arrays.copyOf(original, original.length / 2));

      assertNull(serializer.read(cacheFile));
    }

    @Test
    @DisplayName("returns null for non-existent file")
    void nonExistentFile() {
      Path noFile = tempDir.resolve("does-not-exist.msgpack");
      assertNull(serializer.read(noFile));
    }
  }

  // ==================== Metadata-Only Reading ====================

  @Nested
  @DisplayName("Metadata-Only Reading")
  class MetadataOnlyReading {

    @Test
    @DisplayName("reads metadata without full deserialization")
    void readMetadataOnly() throws IOException {
      var meta = testMetadata();
      var log = new ParsedLog("/test/log.wpilog",
          Map.of("v", new EntryInfo(1, "v", "double", "")),
          Map.of("v", new ArrayList<>(List.of(new TimestampedValue(0.0, 1.0)))),
          0.0, 0.0);

      Path cacheFile = tempDir.resolve("meta.msgpack");
      serializer.write(log, cacheFile, meta);

      CacheMetadata restored = serializer.readMetadata(cacheFile);
      assertNotNull(restored);
      assertEquals(meta.cacheFormatVersion(), restored.cacheFormatVersion());
      assertEquals(meta.serverVersion(), restored.serverVersion());
      assertEquals(meta.originalPath(), restored.originalPath());
      assertEquals(meta.originalSizeBytes(), restored.originalSizeBytes());
      assertEquals(meta.originalLastModified(), restored.originalLastModified());
      assertEquals(meta.contentFingerprint(), restored.contentFingerprint());
      assertEquals(meta.createdAt(), restored.createdAt());
    }

    @Test
    @DisplayName("readMetadata returns null for corrupt file")
    void readMetadataCorruptFile() throws IOException {
      Path cacheFile = tempDir.resolve("corrupt.msgpack");
      Files.write(cacheFile, new byte[]{0x01});

      assertNull(serializer.readMetadata(cacheFile));
    }

    @Test
    @DisplayName("readMetadata returns null for non-existent file")
    void readMetadataNonExistent() {
      assertNull(serializer.readMetadata(tempDir.resolve("nope.msgpack")));
    }
  }

  // ==================== Timestamp Precision ====================

  @Nested
  @DisplayName("Timestamp Precision")
  class TimestampPrecision {

    @Test
    @DisplayName("preserves microsecond-level timestamp precision")
    void microsecondPrecision() throws IOException {
      // Timestamps with microsecond resolution
      var values = List.of(
          new TimestampedValue(0.000001, 1.0),  // 1 microsecond
          new TimestampedValue(123.456789, 2.0),
          new TimestampedValue(999999.999999, 3.0));

      var log = new ParsedLog("/test/log.wpilog",
          Map.of("v", new EntryInfo(1, "v", "double", "")),
          Map.of("v", new ArrayList<>(values)),
          0.000001, 999999.999999);

      var restored = roundTrip(log);
      assertNotNull(restored);
      assertEquals(0.000001, restored.values().get("v").get(0).timestamp(), 1e-12);
      assertEquals(123.456789, restored.values().get("v").get(1).timestamp(), 1e-12);
      assertEquals(999999.999999, restored.values().get("v").get(2).timestamp(), 1e-12);
    }
  }

  // ==================== CRC-32 Checksum Integrity ====================

  @Nested
  @DisplayName("CRC-32 Checksum Integrity")
  class ChecksumIntegrity {

    @Test
    @DisplayName("valid cache file passes checksum verification")
    void validFilePassesChecksum() throws IOException {
      var log = new ParsedLog("/test/log.wpilog",
          Map.of("v", new EntryInfo(1, "v", "double", "")),
          Map.of("v", new ArrayList<>(List.of(
              new TimestampedValue(0.0, 42.0),
              new TimestampedValue(1.0, 43.0)))),
          0.0, 1.0);

      Path cacheFile = tempDir.resolve("valid_crc.msgpack");
      serializer.write(log, cacheFile, testMetadata());

      // Should read successfully (checksum matches)
      var restored = serializer.read(cacheFile);
      assertNotNull(restored, "Valid file should pass checksum verification");
      assertEquals(2, restored.values().get("v").size());
    }

    @Test
    @DisplayName("single bit flip in payload is detected")
    void detectsSingleBitFlip() throws IOException {
      var log = new ParsedLog("/test/log.wpilog",
          Map.of("v", new EntryInfo(1, "v", "double", "")),
          Map.of("v", new ArrayList<>(List.of(new TimestampedValue(0.0, 42.0)))),
          0.0, 0.0);

      Path cacheFile = tempDir.resolve("bitflip.msgpack");
      serializer.write(log, cacheFile, testMetadata());

      // Flip a bit in the middle of the payload
      byte[] bytes = Files.readAllBytes(cacheFile);
      int midpoint = bytes.length / 2;
      bytes[midpoint] ^= 0x01; // flip lowest bit
      Files.write(cacheFile, bytes);

      assertNull(serializer.read(cacheFile),
          "Single bit flip should be detected by CRC-32 checksum");
    }

    @Test
    @DisplayName("corrupted CRC trailer is detected")
    void detectsCorruptedTrailer() throws IOException {
      var log = new ParsedLog("/test/log.wpilog",
          Map.of("v", new EntryInfo(1, "v", "double", "")),
          Map.of("v", new ArrayList<>(List.of(new TimestampedValue(0.0, 42.0)))),
          0.0, 0.0);

      Path cacheFile = tempDir.resolve("bad_trailer.msgpack");
      serializer.write(log, cacheFile, testMetadata());

      // Overwrite the last 4 bytes (CRC trailer) with garbage
      byte[] bytes = Files.readAllBytes(cacheFile);
      bytes[bytes.length - 1] ^= 0xFF;
      bytes[bytes.length - 2] ^= 0xFF;
      Files.write(cacheFile, bytes);

      assertNull(serializer.read(cacheFile),
          "Corrupted CRC trailer should be detected");
    }

    @Test
    @DisplayName("truncated file missing CRC trailer is detected")
    void detectsMissingTrailer() throws IOException {
      var log = new ParsedLog("/test/log.wpilog",
          Map.of("v", new EntryInfo(1, "v", "double", "")),
          Map.of("v", new ArrayList<>(List.of(new TimestampedValue(0.0, 42.0)))),
          0.0, 0.0);

      Path cacheFile = tempDir.resolve("no_trailer.msgpack");
      serializer.write(log, cacheFile, testMetadata());

      // Remove the last 4 bytes (CRC trailer)
      byte[] bytes = Files.readAllBytes(cacheFile);
      byte[] truncated = java.util.Arrays.copyOf(bytes, bytes.length - 4);
      Files.write(cacheFile, truncated);

      // Should fail — the "CRC" read from the last 4 payload bytes won't match
      assertNull(serializer.read(cacheFile),
          "File with missing CRC trailer should fail verification");
    }

    @Test
    @DisplayName("appended garbage after CRC is detected")
    void detectsAppendedData() throws IOException {
      var log = new ParsedLog("/test/log.wpilog",
          Map.of("v", new EntryInfo(1, "v", "double", "")),
          Map.of("v", new ArrayList<>(List.of(new TimestampedValue(0.0, 42.0)))),
          0.0, 0.0);

      Path cacheFile = tempDir.resolve("appended.msgpack");
      serializer.write(log, cacheFile, testMetadata());

      // Append extra bytes after the CRC
      byte[] bytes = Files.readAllBytes(cacheFile);
      byte[] extended = java.util.Arrays.copyOf(bytes, bytes.length + 10);
      Files.write(cacheFile, extended);

      // The CRC will be read from the wrong position (last 4 bytes of the extended file)
      assertNull(serializer.read(cacheFile),
          "File with appended data should fail CRC verification");
    }

    @Test
    @DisplayName("file with only 4 bytes (no payload) is rejected")
    void rejectsFourByteFile() throws IOException {
      Path cacheFile = tempDir.resolve("tiny.msgpack");
      Files.write(cacheFile, new byte[]{0x00, 0x00, 0x00, 0x00});

      assertNull(serializer.read(cacheFile),
          "File with no payload (only CRC) should be rejected");
    }

    @Test
    @DisplayName("different data produces different checksums")
    void differentDataDifferentChecksums() throws IOException {
      var log1 = new ParsedLog("/test/a.wpilog",
          Map.of("v", new EntryInfo(1, "v", "double", "")),
          Map.of("v", new ArrayList<>(List.of(new TimestampedValue(0.0, 1.0)))),
          0.0, 0.0);

      var log2 = new ParsedLog("/test/b.wpilog",
          Map.of("v", new EntryInfo(1, "v", "double", "")),
          Map.of("v", new ArrayList<>(List.of(new TimestampedValue(0.0, 2.0)))),
          0.0, 0.0);

      Path file1 = tempDir.resolve("crc1.msgpack");
      Path file2 = tempDir.resolve("crc2.msgpack");
      serializer.write(log1, file1, testMetadata());
      serializer.write(log2, file2, testMetadata());

      byte[] bytes1 = Files.readAllBytes(file1);
      byte[] bytes2 = Files.readAllBytes(file2);

      // Extract CRC trailers
      byte[] crc1 = java.util.Arrays.copyOfRange(bytes1, bytes1.length - 4, bytes1.length);
      byte[] crc2 = java.util.Arrays.copyOfRange(bytes2, bytes2.length - 4, bytes2.length);

      assertFalse(java.util.Arrays.equals(crc1, crc2),
          "Different data should produce different CRC checksums");
    }
  }
}
