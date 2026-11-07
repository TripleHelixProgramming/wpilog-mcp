/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.log.subsystems.LogParser;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;

/** Presentation reads use one angle rule while ordinary whole-value reads retain their types. */
class PresentationAnglesTest {
  @TempDir Path directory;
  private LogData log;

  @BeforeEach void writeSyntheticAngles() throws Exception {
    var path = directory.resolve("presentation-angles.wpilog");
    try (var writer = new WpilogWriter(path, "independent presentation angle fixture")) {
      int schema = writer.start("/.schema/struct:Rotation2d", "structschema", "", 0);
      writer.append(schema, 0, "double value;".getBytes(StandardCharsets.UTF_8));
      int heading = writer.start("/Heading", "struct:Rotation2d", "", 0);
      int degrees = writer.start("/Degrees", "double", "", 0);
      int radians = writer.start("/Radians", "double", "", 0);
      int integers = writer.start("/IntegerRadians", "int64", "", 0);
      int large = writer.start("/LargeIntegers", "int64", "", 0);
      int array = writer.start("/Array", "double[]", "", 0);
      int gap = writer.start("/WithGap", "double", "", 0);
      int text = writer.start("/Text", "string", "", 0);
      for (int i = 0; i < 2; i++) {
        double value = i == 0 ? 179 : -179;
        long time = i * 1_000_000L;
        writer.append(heading, time, WpilogWriter.encodeDouble(Math.toRadians(value)));
        writer.append(degrees, time, WpilogWriter.encodeDouble(value));
        writer.append(radians, time, WpilogWriter.encodeDouble(Math.toRadians(value)));
        writer.append(integers, time, WpilogWriter.encodeInt64(i == 0 ? 3 : -3));
        writer.append(large, time, WpilogWriter.encodeInt64(9_007_199_254_740_993L + i * 2));
        writer.append(array, time, WpilogWriter.encodeDoubleArray(i == 0
            ? new double[] {179, -179} : new double[] {-177, 177}));
      }
      double[] values = {179, Double.NaN, -179, -177};
      for (int i = 0; i < values.length; i++)
        writer.append(gap, i * 1_000_000L, WpilogWriter.encodeDouble(values[i]));
      writer.append(text, 0, "synthetic".getBytes(StandardCharsets.UTF_8));
    }
    log = new LogParser().parse(path);
  }

  @Test void declaredDegreesUnwrapBeforeReduction() throws Exception {
    var result = read("/Heading._derived.degrees", "max_points", 1);
    assertBucket(result, 2, 179, 181, 180, 179, 181);
  }

  @Test void declaredRadiansUnwrapBeforeReduction() throws Exception {
    assertBucket(read("/Heading.value", "max_points", 1), 2,
        Math.toRadians(179), Math.toRadians(181), Math.PI, Math.toRadians(179), Math.toRadians(181));
  }

  @Test void declaredAnglesUnwrapInTheExactResolutionBranch() throws Exception {
    var result = read("/Heading._derived.degrees", "max_points", 2);
    assertFalse(result.get("bucketed").getAsBoolean());
    assertValues(result, 179, 181);
  }

  @Test void explicitPlainUnitsAndAliasesUnwrapBeforeReduction() throws Exception {
    for (String unit : new String[] {"degrees", "deg"})
      assertBucket(read("/Degrees", "max_points", 1, "angle", unit), 2, 179, 181, 180, 179, 181);
    for (String unit : new String[] {"radians", "rad"})
      assertBucket(read("/Radians", "max_points", 1, "angle", unit), 2,
          Math.toRadians(179), Math.toRadians(181), Math.PI, Math.toRadians(179), Math.toRadians(181));
  }

  @Test void explicitAngleWithoutResolutionUsesNumericPresentationAndPreservesPaging() throws Exception {
    var result = read("/Degrees", "angle", "degrees", "offset", 1, "limit", 1);
    assertEquals(2, result.get("total_in_range").getAsInt());
    assertEquals(1, result.get("returned_count").getAsInt());
    assertFalse(result.get("has_more").getAsBoolean());
    assertValues(result, 181);
    assertEquals(1.0, result.getAsJsonArray("samples").get(0).getAsJsonObject().get("timestamp_sec").getAsDouble());
  }

  @Test void explicitAngleCanProjectTheRequestedFieldWithoutChangingOrdinaryStructReads() throws Exception {
    var result = assertDoesNotThrow(() -> read("/Heading._derived.degrees", "angle", "degrees"),
        "An explicit numeric angle request may project its selected field without max_points");
    assertValues(result, 179, 181);
  }

  @Test void explicitIntegerRadiansKeepTheFractionalUnwrappedValue() throws Exception {
    assertValues(read("/IntegerRadians", "max_points", 2, "angle", "radians"), 3, 2 * Math.PI - 3);
    assertValues(read("/IntegerRadians", "angle", "radians"), 3, 2 * Math.PI - 3);
  }

  @Test void invalidOrInapplicableExplicitUnitsAreNotSilentlyIgnored() {
    var invalid = assertThrows(IllegalArgumentException.class,
        () -> read("/Degrees", "angle", "turns"));
    assertTrue(invalid.getMessage().contains("angle"));
    assertThrows(IllegalArgumentException.class, () -> read("/Text", "angle", "degrees"));
    assertThrows(IllegalArgumentException.class, () -> read("/Heading", "max_points", 1, "angle", "radians"));
  }

  @Test void plainOmittedAnglesRemainArithmetic() throws Exception {
    assertBucket(read("/Degrees", "max_points", 1), 2, -179, 179, 0, 179, -179);
    assertValues(read("/Degrees"), 179, -179);
  }

  @Test void ordinaryWholeStructArrayAndLargeIntegerReadsRemainTyped() throws Exception {
    var struct = read("/Heading").getAsJsonArray("samples").get(1).getAsJsonObject().getAsJsonObject("value");
    assertEquals(Math.toRadians(-179), struct.get("value").getAsDouble(), 1e-12);
    assertEquals(-179, struct.getAsJsonObject("_derived").get("degrees").getAsDouble(), 1e-12);
    var integers = read("/LargeIntegers").getAsJsonArray("samples");
    assertEquals(9_007_199_254_740_993L, integers.get(0).getAsJsonObject().get("value").getAsLong());
    assertEquals(9_007_199_254_740_995L, integers.get(1).getAsJsonObject().get("value").getAsLong());
    var array = read("/Array").getAsJsonArray("samples").get(0).getAsJsonObject().getAsJsonArray("value");
    assertEquals(179, array.get(0).getAsDouble());
    assertEquals(-179, array.get(1).getAsDouble());
  }

  @Test void wildcardPoolsStayArithmeticEvenWithAnExplicitAngle() throws Exception {
    assertBucket(read("/Array[*]", "max_points", 1, "angle", "degrees"), 4, -179, 179, 0, 179, 177);
  }

  @Test void nanRowsRemainCountedAndDoNotResetAngleContinuity() throws Exception {
    assertBucket(read("/WithGap", "max_points", 1, "angle", "degrees"), 4, 179, 183, 181, 179, 183);
    var exact = read("/WithGap", "max_points", 4, "angle", "degrees");
    assertEquals(4, exact.get("total_in_range").getAsInt());
    var samples = exact.getAsJsonArray("samples");
    assertEquals(179, samples.get(0).getAsJsonObject().get("value").getAsDouble());
    assertEquals("NaN", samples.get(1).getAsJsonObject().get("value").getAsString());
    assertEquals(181, samples.get(2).getAsJsonObject().get("value").getAsDouble());
    assertEquals(183, samples.get(3).getAsJsonObject().get("value").getAsDouble());
  }

  @Test void eachWindowStartsAtItsOwnFirstValueAndIncludesItsEnd() throws Exception {
    var result = read("/WithGap", "max_points", 2, "angle", "degrees", "start_time", 2, "end_time", 3);
    assertEquals(2, result.get("total_in_range").getAsInt());
    assertValues(result, -179, -177);
  }

  @Test void metadataOnlyAngleSelectionNeverLoadsAHistory() {
    var metadata = metadataOnly();
    assertEquals(360, VisualizationBindings.anglePeriod(metadata, "/Heading", "._derived.degrees"));
    assertEquals(2 * Math.PI, VisualizationBindings.anglePeriod(metadata, "/Heading", ".value"));
    assertEquals(0, VisualizationBindings.anglePeriod(metadata, "/Degrees", null));
    assertEquals(0, VisualizationBindings.anglePeriod(metadata, "/Array", "[*]"));
    var selected = EntryData.resolve(log, "/Heading._derived.degrees");
    var prepared = EntryData.prepare(metadata, selected, null, null, null);
    assertTrue(prepared.angle());
    assertEquals(179, (Double) prepared.values().get(0).value(), 1e-12);
    assertEquals(181, (Double) prepared.values().get(1).value(), 1e-12);
  }

  @Test void preparedRevScalarsNeedNoMatchingWpilogEntryAndKeepRawMicros() {
    long time = 9_007_199_254_740_993L;
    var values = List.of(TimestampedValue.atMicros(time, 3.0),
        TimestampedValue.atMicros(time + 1, -3.0));
    var selected = new EntryData.Series("REV/1/Yaw", "REV/1/Yaw", null, "revlog", values, true);
    var prepared = EntryData.prepare(metadataOnly(), selected, null, null, "rad");
    assertTrue(prepared.angle());
    assertEquals(3.0, (Double) prepared.values().get(0).value());
    assertEquals(2 * Math.PI - 3, (Double) prepared.values().get(1).value());
    assertEquals(time, prepared.values().get(0).timestampUs());
    assertEquals(time + 1, prepared.values().get(1).timestampUs());
    var raw = EntryData.prepare(metadataOnly(), selected, null, null, null);
    assertFalse(raw.angle());
    assertEquals(values, raw.values());
  }

  @Test void invalidUnitsStillRefuseAnEmptyWindowAndWholeValuesStayTyped() {
    assertThrows(IllegalArgumentException.class, () -> EntryData.prepare(metadataOnly(),
        EntryData.resolve(log, "/Degrees"), 10.0, 20.0, "turns"));
    assertThrows(IllegalArgumentException.class, () -> EntryData.validateAngle(""));
    var selected = EntryData.resolve(log, "/LargeIntegers");
    var raw = EntryData.prepare(metadataOnly(), selected, null, null, null);
    assertFalse(raw.angle());
    assertEquals(9_007_199_254_740_993L, raw.values().get(0).value());
    assertEquals(9_007_199_254_740_995L, raw.values().get(1).value());
    var whole = EntryData.resolve(log, "/Heading");
    assertThrows(IllegalArgumentException.class,
        () -> EntryData.prepare(metadataOnly(), whole, null, null, "degrees"));
    assertThrows(IllegalArgumentException.class,
        () -> EntryData.prepare(metadataOnly(), whole, 10.0, 20.0, "degrees"));
  }

  private LogData metadataOnly() {
    var schemas = log.structSchemas();
    return new LogData() {
      public String path() { return log.path(); }
      public Map<String, EntryInfo> entries() { return log.entries(); }
      public Map<String, List<TimestampedValue>> values() {
        throw new AssertionError("Angle metadata must not materialize a history");
      }
      public double minTimestamp() { return log.minTimestamp(); }
      public double maxTimestamp() { return log.maxTimestamp(); }
      public boolean truncated() { return false; }
      public String truncationMessage() { return null; }
      public StructSchemas structSchemas() { return schemas; }
    };
  }

  private JsonObject read(String name, Object... arguments) throws Exception {
    var args = new JsonObject();
    args.addProperty("name", name);
    for (int i = 0; i < arguments.length; i += 2) {
      if (arguments[i + 1] instanceof Number number) args.addProperty((String) arguments[i], number);
      else args.addProperty((String) arguments[i], arguments[i + 1].toString());
    }
    return new CoreTools.ReadEntryTool().executeWithLog(log, args).getAsJsonObject();
  }

  private static void assertValues(JsonObject result, double... expected) {
    var samples = result.getAsJsonArray("samples");
    assertEquals(expected.length, samples.size());
    for (int i = 0; i < expected.length; i++)
      assertEquals(expected[i], samples.get(i).getAsJsonObject().get("value").getAsDouble(), 1e-12, "sample " + i);
  }

  private static void assertBucket(JsonObject result, int count, double min, double max,
      double mean, double first, double last) {
    assertTrue(result.get("bucketed").getAsBoolean());
    assertEquals(count, result.get("total_in_range").getAsInt());
    assertEquals(1, result.getAsJsonArray("samples").size());
    var bucket = result.getAsJsonArray("samples").get(0).getAsJsonObject();
    assertEquals(count, bucket.get("count").getAsInt());
    assertEquals(min, bucket.get("min").getAsDouble(), 1e-12);
    assertEquals(max, bucket.get("max").getAsDouble(), 1e-12);
    assertEquals(mean, bucket.get("mean").getAsDouble(), 1e-12);
    assertEquals(first, bucket.get("first").getAsDouble(), 1e-12);
    assertEquals(last, bucket.get("last").getAsDouble(), 1e-12);
  }
}
