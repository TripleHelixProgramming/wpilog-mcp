/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.fixtures.RecordingFixture;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;

/** Preview angles follow one measured trajectory across input batches and rollover members. */
class SourceReductionAnglesTest {
  private static final String ENTRY = "/orientation";
  private static final double EPSILON = 1e-9;
  @TempDir Path temp;
  private final LogManager logs = LogManager.getInstance();
  private Set<Path> original;
  private RecordingFixture fixture;
  private VisualizationWorkspace workspace;

  @BeforeEach void start() throws Exception {
    temp = temp.toRealPath();
    original = logs.getConfiguredDirectories();
    logs.clearAllowedDirectories();
    logs.addAllowedDirectory(temp);
    fixture = new RecordingFixture(temp.resolve("store"), 0);
    workspace = new VisualizationWorkspace(ServerIdentity.ephemeral(), logs);
  }

  @AfterEach void stop() throws Exception {
    if (workspace != null) workspace.close();
    logs.addAllowedDirectory(temp);
    assertTrue(logs.release(temp).released());
    logs.clearAllowedDirectories();
    original.forEach(logs::addAllowedDirectory);
  }

  private void angles(int member, long[] times, double[] degrees) throws Exception {
    assertEquals(times.length, degrees.length);
    fixture.rewrite(member, writer -> {
      int schema = writer.start("/.schema/struct:Rotation2d", "structschema", "", 0);
      writer.append(schema, 0, "double value".getBytes(StandardCharsets.UTF_8));
      int id = writer.start(ENTRY, "struct:Rotation2d", "", 0);
      for (int i = 0; i < times.length; i++)
        writer.append(id, times[i], WpilogWriter.encodeDouble(Math.toRadians(degrees[i])));
    });
  }

  private JsonObject request(String field, long startUs, long endUs) {
    var request = new JsonObject();
    request.add("source_ref", fixture.reference.deepCopy());
    request.addProperty("entry", ENTRY);
    if (field != null) request.addProperty("field", field);
    request.addProperty("start_time", startUs / 1_000_000.0);
    request.addProperty("end_time", endUs / 1_000_000.0);
    request.addProperty("max_points", 1);
    return request;
  }

  private JsonObject read(String field, long startUs, long endUs) throws Exception {
    return new SourceReduction(workspace.sources).read(request(field, startUs, endUs), () -> true);
  }

  private static void bucket(JsonObject result, int count, int finite,
      double min, double max, double mean, double first, double last) {
    assertEquals("ok", result.get("status").getAsString());
    assertEquals(count, result.get("sample_count").getAsInt());
    assertEquals(finite, result.get("finite_count").getAsInt());
    assertEquals(count - finite, result.get("unplottable_count").getAsInt());
    assertEquals(count, result.get("records_visited").getAsInt());
    assertEquals(1, result.getAsJsonArray("buckets").size());
    var bucket = result.getAsJsonArray("buckets").get(0).getAsJsonObject();
    assertEquals(count, bucket.get("count").getAsInt());
    assertEquals(finite, bucket.get("finite_count").getAsInt());
    assertEquals(min, bucket.get("min").getAsDouble(), EPSILON, "minimum");
    assertEquals(max, bucket.get("max").getAsDouble(), EPSILON, "maximum");
    assertEquals(mean, bucket.get("mean").getAsDouble(), EPSILON, "mean");
    assertEquals(first, bucket.get("first").getAsDouble(), EPSILON, "first");
    assertEquals(last, bucket.get("last").getAsDouble(), EPSILON, "last");
  }

  @ParameterizedTest @ValueSource(strings = {"_derived.degrees", "value"})
  void declaredRotationCrossingUnwrapsBeforeReducing(String field) throws Exception {
    angles(0, new long[]{0, 10}, new double[]{179, -179});
    // The two physical angles are 179 and 181 degrees along their shortest turn.
    double scale = field.equals("value") ? Math.PI / 180 : 1;
    bucket(read(field, 0, 10), 2, 2,
        179 * scale, 181 * scale, 180 * scale, 179 * scale, 181 * scale);
  }

  @ParameterizedTest @ValueSource(ints = {64, 256})
  void oneTrajectorySurvivesRawAndMergedBatchBoundaries(int boundary) throws Exception {
    long[] times = new long[boundary + 1];
    double[] degrees = new double[boundary + 1];
    for (int i = 0; i <= boundary; i++) {
      times[i] = i;
      degrees[i] = i == boundary ? -179 : 179;
    }
    angles(0, times, degrees);
    // Exactly boundary copies of 179 and one 181; no reducer supplies this answer.
    double mean = (179.0 * boundary + 181) / (boundary + 1);
    bucket(read("_derived.degrees", 0, boundary), boundary + 1, boundary + 1,
        179, 181, mean, 179, 181);
  }

  @Test void rolloverMembersUnwrapAfterTheirTimestampsAreMerged() throws Exception {
    angles(0, new long[]{0, 20}, new double[]{179, -177});
    angles(1, new long[]{10}, new double[]{-179});
    // Common-clock order is 179, -179, -177, hence 179, 181, 183.
    bucket(read("_derived.degrees", 0, 20), 3, 3, 179, 183, 181, 179, 183);
  }

  @Test void eachRequestedWindowStartsAtItsOwnFirstRecordedAngle() throws Exception {
    angles(0, new long[]{0, 10, 20}, new double[]{179, -179, -177});
    var owner = new SourceReduction(workspace.sources);
    bucket(owner.read(request("_derived.degrees", 0, 20), () -> true),
        3, 3, 179, 183, 181, 179, 183);
    bucket(owner.read(request("_derived.degrees", 10, 20), () -> true),
        2, 2, -179, -177, -178, -179, -177);
    bucket(owner.read(request("_derived.degrees", 20, 20), () -> true),
        1, 1, -177, -177, -177, -177, -177);
  }

  @Test void nonfiniteGapsKeepCountsAndAngleContinuity() throws Exception {
    angles(0, new long[]{0, 5, 10, 15, 20},
        new double[]{179, Double.NaN, -179, Double.POSITIVE_INFINITY, -177});
    bucket(read("_derived.degrees", 0, 20), 5, 3, 179, 183, 181, 179, 183);
  }

  @Test void aPlainNumberWithAnAngleLikeNameRemainsArithmetic() throws Exception {
    fixture.rewrite(0, writer -> {
      int id = writer.start(ENTRY, "double", "", 0);
      writer.append(id, 0, WpilogWriter.encodeDouble(179));
      writer.append(id, 10, WpilogWriter.encodeDouble(-179));
    });
    // Existing behavior control: a name supplies no declaration of angle units.
    bucket(read(null, 0, 10), 2, 2, -179, 179, 0, 179, -179);
  }
}
