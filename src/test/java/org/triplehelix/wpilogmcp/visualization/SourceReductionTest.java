/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.fixtures.*;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.read.*;

/** Generated records and hand-computed buckets; no expected value calls the reducer. */
class SourceReductionTest {
  @TempDir Path temp;
  final LogManager logs = LogManager.getInstance(); Set<Path> original;
  RecordingFixture fixture; VisualizationWorkspace workspace;
  @BeforeEach void start() throws Exception {
    temp = temp.toRealPath(); original = logs.getConfiguredDirectories(); logs.clearAllowedDirectories(); logs.addAllowedDirectory(temp);
    fixture = new RecordingFixture(temp.resolve("store"), 0); workspace = new VisualizationWorkspace(ServerIdentity.ephemeral(), logs);
  }
  @AfterEach void stop() throws Exception {
    workspace.close(); logs.addAllowedDirectory(temp); assertTrue(logs.release(temp).released()); logs.clearAllowedDirectories(); original.forEach(logs::addAllowedDirectory);
  }
  JsonObject request() { var r = new JsonObject(); r.add("source_ref", fixture.reference); r.addProperty("entry", "/n"); r.addProperty("max_points", 2); return r; }
  SourceReduction owner() { return new SourceReduction(workspace.sources); }
  void numbers() throws Exception {
    fixture.rewrite(0, w -> { int n = w.start("/n", "double", "", 0); w.append(n, 2_000_000, WpilogWriter.encodeDouble(8)); w.append(n, 0, WpilogWriter.encodeDouble(2)); });
    fixture.rewrite(1, w -> { int n = w.start("/n", "double", "", 0); w.append(n, 1_000_000, WpilogWriter.encodeDouble(4)); w.append(n, 2_000_000, WpilogWriter.encodeDouble(6)); });
  }
  @Test void exactEndAndZeroWidthIntervalsKeepEveryTieInMemberOrder() throws Exception {
    numbers(); var r = request(); r.addProperty("start_time", 2); r.addProperty("end_time", 2); r.addProperty("max_points", 4096);
    var result = owner().read(r, () -> true); var b = result.getAsJsonArray("buckets").get(0).getAsJsonObject();
    assertEquals(1, result.getAsJsonArray("buckets").size()); assertEquals("2000000", b.get("start_us").getAsString()); assertEquals("2000000", b.get("end_us").getAsString());
    assertEquals(2, b.get("count").getAsInt()); assertEquals(7, b.get("mean").getAsDouble()); assertEquals(8, b.get("first").getAsDouble()); assertEquals(6, b.get("last").getAsDouble());
    r.remove("start_time"); r.remove("end_time"); result = owner().read(r, () -> true);
    assertEquals(4, result.get("sample_count").getAsInt()); assertEquals("0", result.getAsJsonObject("window").get("start_us").getAsString()); assertEquals("2000000", result.getAsJsonObject("window").get("end_us").getAsString());
  }
  @Test void booleansNumericProjectionNonfiniteAndSignedZeroKeepTheirMeaning() throws Exception {
    fixture.rewrite(0, w -> { int n = w.start("/n", "boolean", "", 0); w.append(n, 0, new byte[]{0}); w.append(n, 1, new byte[]{1}); });
    var r = request(); r.addProperty("max_points", 1); var b = owner().read(r, () -> true).getAsJsonArray("buckets").get(0).getAsJsonObject();
    assertEquals(.5, b.get("mean").getAsDouble()); assertEquals(0, b.get("min").getAsDouble()); assertEquals(1, b.get("max").getAsDouble());
    logs.release(temp);
    fixture.rewrite(0, w -> { int n = w.start("/n", "double[]", "", 0); for (double v : new double[]{-0.0, Double.NaN, Double.POSITIVE_INFINITY, 8})
      w.append(n, 1, java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN).putDouble(100).putDouble(v).array()); });
    r.addProperty("field", "[1]"); var result = owner().read(r, () -> true); b = result.getAsJsonArray("buckets").get(0).getAsJsonObject();
    assertEquals(4, b.get("count").getAsInt()); assertEquals(2, b.get("finite_count").getAsInt()); assertEquals(4, b.get("mean").getAsDouble());
    assertEquals(Double.doubleToLongBits(-0.0), Double.doubleToLongBits(b.get("first").getAsDouble())); assertEquals(8, b.get("last").getAsDouble()); assertEquals(2, result.get("unplottable_count").getAsInt());
  }
  @Test void malformedPayloadsAreBoundedMemberQualifiedProblemsNotSilentLoss() throws Exception {
    fixture.rewrite(0, w -> { int n = w.start("/n", "double", "", 0); for (int i = 0; i < 105; i++) w.append(n, i, new byte[]{1}); w.append(n, 105, WpilogWriter.encodeDouble(2)); });
    var result = owner().read(request(), () -> true); assertEquals("partial", result.get("status").getAsString()); assertTrue(result.has("skipped"));
    assertEquals(105, result.get("problem_count").getAsInt()); assertEquals(100, result.getAsJsonArray("problems").size()); assertTrue(result.get("problems_truncated").getAsBoolean());
    assertEquals("capture.wpilog", result.getAsJsonArray("problems").get(0).getAsJsonObject().get("member_id").getAsString()); assertEquals(1, result.get("sample_count").getAsInt()); assertEquals(106, result.get("records_visited").getAsInt());
  }
  @Test void aNewPrefixRecomputesOldBucketsForLateSamplesWithoutCachingOldAnswers() throws Exception {
    numbers(); var first = owner().read(request(), () -> true); assertEquals(2, first.getAsJsonArray("buckets").get(0).getAsJsonObject().get("mean").getAsDouble());
    logs.release(temp);
    fixture.rewrite(1, w -> { int n = w.start("/n", "double", "", 0); w.append(n, 1_000_000, WpilogWriter.encodeDouble(4)); w.append(n, 2_000_000, WpilogWriter.encodeDouble(6)); w.append(n, 0, WpilogWriter.encodeDouble(10)); });
    var later = owner().read(request(), () -> true); assertEquals(5, later.get("sample_count").getAsInt()); assertEquals(6, later.getAsJsonArray("buckets").get(0).getAsJsonObject().get("mean").getAsDouble());
    assertNotEquals(first.get("prefix"), later.get("prefix"));
  }
  @ParameterizedTest @ValueSource(strings = {"records", "record", "result"})
  void resourceLimitsRefuseAndReleaseThePrefix(String which) throws Exception {
    numbers(); var d = SourceReduction.Limits.defaults(); var limits = new SourceReduction.Limits(which.equals("records") ? 3 : d.records(), which.equals("record") ? 4 : d.recordBytes(), which.equals("result") ? 128 : d.resultBytes(), d.nanos());
    var service = new SourceReduction(new RecordingBindings(workspace.sources::admitChart), limits, System::nanoTime);
    var query = request();
    if (which.equals("records")) { query.addProperty("start_time", 0); query.addProperty("end_time", 0); }
    var failure = assertThrows(ReadException.class, () -> service.read(query, () -> true)); assertEquals(ReadProblem.Code.RESOURCE_LIMIT, failure.code()); assertFalse(failure.retryable()); assertTrue(logs.release(temp).released());
  }
  @Test void cancellationAndDeadlineCheckBeforeAdmissionAndBeforePublication() throws Exception {
    numbers(); var opens = new AtomicInteger(); var now = new AtomicLong();
    var service = new SourceReduction(new RecordingBindings(source -> { opens.incrementAndGet(); var a = workspace.sources.admitChart(source); now.addAndGet(11_000_000_000L); return a; }), SourceReduction.Limits.defaults(), now::get);
    assertThrows(AccessDeniedException.class, () -> service.read(request(), () -> false)); assertEquals(0, opens.get());
    try { Thread.currentThread().interrupt(); assertThrows(InterruptedIOException.class, () -> service.read(request(), () -> true)); } finally { Thread.interrupted(); }
    assertEquals(0, opens.get());
    for (int i = 0; i < 3; i++) assertTrue(assertThrows(ReadException.class, () -> service.read(request(), () -> true)).getMessage().contains("work budget"));
    assertEquals(3, opens.get());
  }
  @ParameterizedTest @ValueSource(booleans = {false, true})
  void grantsAndBoundariesAreRecheckedBeforePublishingAnyBuckets(boolean boundary) throws Exception {
    numbers(); var checks = new AtomicInteger();
    var service = new SourceReduction(new RecordingBindings(source -> {
      var a = workspace.sources.admitChart(source);
      return new RecordedSources.Admission(a.prefix(), () -> {
        if (checks.incrementAndGet() == 2) {
          if (boundary) Files.setLastModifiedTime(fixture.directory.resolve("capture.wpilog"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 30000));
          else throw new AccessDeniedException("revoked");
        }
        a.access().check();
      });
    }), SourceReduction.Limits.defaults(), System::nanoTime);
    var failure = assertThrows(IOException.class, () -> service.read(request(), () -> true));
    if (boundary) assertEquals(ReadProblem.Code.FILE_CHANGED, ((ReadException) failure).code()); else assertInstanceOf(AccessDeniedException.class, failure);
    assertEquals(2, checks.get());
  }
  @Test void twoSlotsRefuseImmediatelyAndRecoverAfterRelease() throws Exception {
    numbers(); var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
    var service = owner(); var pool = Executors.newFixedThreadPool(2);
    // Block only after compatibility has returned its slots. Otherwise that independent
    // two-slot guard would hide a missing reduction-worker cap.
    Callable<JsonObject> read = () -> {
      var checks = new AtomicInteger();
      return service.read(request(), () -> {
        if (checks.incrementAndGet() == 2) { entered.countDown();
          try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fixture timeout"); }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return true;
      });
    };
    try {
      var a = pool.submit(read); var b = pool.submit(read); assertTrue(entered.await(3, TimeUnit.SECONDS));
      var failure = assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> assertThrows(ReadException.class, () -> service.read(request(), () -> true))); assertTrue(failure.retryable());
      release.countDown(); assertEquals(4, a.get(3, TimeUnit.SECONDS).get("sample_count").getAsInt()); assertEquals(4, b.get(3, TimeUnit.SECONDS).get("sample_count").getAsInt());
      assertEquals(4, service.read(request(), () -> true).get("sample_count").getAsInt());
    } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS)); }
  }
  @Test void conflictsAndInvalidArgumentsRefuseInsteadOfChoosingOrCoercing() throws Exception {
    numbers();
    for (var key : List.of("max_points", "max_bytes", "start_time")) for (var bad : List.of("null", "true", "\"1\"", "1e300")) {
      var r = request(); r.add(key, JsonParser.parseString(bad)); assertThrows(IllegalArgumentException.class, () -> owner().read(r, () -> true), key + bad);
    }
    for (int points : new int[]{0, -1, 4097}) { var r = request(); r.addProperty("max_points", points); assertThrows(IllegalArgumentException.class, () -> owner().read(r, () -> true)); }
    var reverse = request(); reverse.addProperty("start_time", 3); reverse.addProperty("end_time", 2); assertThrows(IllegalArgumentException.class, () -> owner().read(reverse, () -> true));
    var wildcard = request(); wildcard.addProperty("field", "[*]"); assertThrows(IllegalArgumentException.class, () -> owner().read(wildcard, () -> true));
    var unknown = request(); unknown.addProperty("mystery", 1); assertThrows(IllegalArgumentException.class, () -> owner().read(unknown, () -> true));
    logs.release(temp); fixture.rewrite(1, w -> { int id = w.start("/n", "int64", "", 0); w.append(id, 1, WpilogWriter.encodeInt64(3)); });
    assertTrue(assertThrows(IOException.class, () -> owner().read(request(), () -> true)).getMessage().contains("SOURCE_UNAVAILABLE"));
  }
}
