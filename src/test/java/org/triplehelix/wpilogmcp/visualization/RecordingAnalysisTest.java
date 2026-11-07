/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.fixtures.*;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.read.internal.ReadWire;
import org.triplehelix.wpilogmcp.read.*;

/** The expected answers come from the generated records, not the plot or another server call. */
class RecordingAnalysisTest {
  @TempDir Path temp;
  final LogManager logs = LogManager.getInstance();
  Set<Path> original; VisualizationWorkspace workspace; RecordingFixture fixture;
  @BeforeEach void start() throws Exception {
    temp = temp.toRealPath(); original = logs.getConfiguredDirectories(); logs.clearAllowedDirectories(); logs.addAllowedDirectory(temp);
    fixture = new RecordingFixture(temp.resolve("store"), 4);
    workspace = new VisualizationWorkspace(ServerIdentity.ephemeral(), logs);
  }
  @AfterEach void stop() throws Exception {
    workspace.close(); logs.addAllowedDirectory(temp); assertTrue(logs.release(temp).released(), "Analysis releases every cursor and prefix");
    logs.clearAllowedDirectories(); original.forEach(logs::addAllowedDirectory);
  }
  JsonObject request(String entry) { var r = new JsonObject(); r.add("source_ref", fixture.reference); r.addProperty("entry", entry); return r; }
  static double number(JsonObject result) { return ((Number) ReadWire.value(result.get("value"))).doubleValue(); }
  void doubles() throws Exception {
    fixture.rewrite(0, w -> {
      int id = w.start("/n", "double", "", 0);
      w.append(id, 2_000_000, WpilogWriter.encodeDouble(4));
      w.append(id, 1_000_000, WpilogWriter.encodeDouble(2));
      w.append(id, 2_000_000, WpilogWriter.encodeDouble(6));
    });
    fixture.rewrite(1, w -> {
      int id = w.start("/n", "double", "", 0);
      w.append(id, 2_000_000, WpilogWriter.encodeDouble(8));
      w.append(id, 3_000_000, WpilogWriter.encodeDouble(10));
      w.append(id, 1_500_000, WpilogWriter.encodeDouble(12));
    });
  }
  @Test void latestAndCursorUseTimestampThenStableMemberAndPhysicalOrdinal() throws Exception {
    doubles(); var r = request("/n"); var latest = workspace.value(r);
    assertEquals(10, number(latest)); assertEquals("3000000", latest.get("timestamp_us").getAsString());
    assertEquals("rollover.wpilog", latest.get("member_id").getAsString()); assertEquals("1", latest.get("ordinal").getAsString());
    r.addProperty("at_sec", 2); var at = workspace.value(r);
    assertEquals(8, number(at)); assertEquals("rollover.wpilog", at.get("member_id").getAsString());
    r.addProperty("at_sec", 1.75); assertEquals(12, number(workspace.value(r)));
    r.addProperty("at_sec", 0); assertTrue(workspace.value(r).get("value").isJsonNull());
    assertEquals(2, latest.getAsJsonObject("prefix").getAsJsonArray("members").size());
  }
  @Test void equalTimesInOneMemberKeepLastOrdinalAndUnsafeIntegersStayExact() throws Exception {
    fixture.rewrite(0, w -> {
      int id = w.start("/exact", "int64", "", 0);
      w.append(id, 10, WpilogWriter.encodeInt64(9_007_199_254_740_992L));
      w.append(id, 10, WpilogWriter.encodeInt64(9_007_199_254_740_993L));
      w.append(id, 9, WpilogWriter.encodeInt64(1));
    });
    var out = workspace.value(request("/exact"));
    assertEquals("9007199254740993", out.getAsJsonObject("value").get("value").getAsString());
    assertEquals("1", out.get("ordinal").getAsString()); assertEquals("capture.wpilog", out.get("member_id").getAsString());
  }
  @Test void statisticsUseEveryDuplicateAndBackwardRecordAcrossMembers() throws Exception {
    doubles(); var out = workspace.statistics(request("/n"));
    assertEquals(6, out.get("count").getAsInt()); assertEquals(2, out.get("min").getAsDouble());
    assertEquals(12, out.get("max").getAsDouble()); assertEquals(7, out.get("mean").getAsDouble());
    assertEquals(7, out.get("median").getAsDouble()); assertEquals(Math.sqrt(14), out.get("std_dev").getAsDouble(), 1e-12);
    assertEquals(4.5, out.get("q1").getAsDouble()); assertEquals(9.5, out.get("q3").getAsDouble());
    assertEquals(2.5, out.get("p5").getAsDouble()); assertEquals(11.5, out.get("p95").getAsDouble());
    assertTrue(out.has("data_quality")); assertEquals(fixture.reference, out.getAsJsonObject("inputs").get("source_ref"));
    assertEquals("admitted_recording_prefix", out.get("basis").getAsString());
  }
  @Test void inclusiveWindowCountsAllTiesAndNeverBridgesMembersByInterpolation() throws Exception {
    doubles(); var r = request("/n"); r.addProperty("start_time", 2); r.addProperty("end_time", 2);
    var out = workspace.statistics(r);
    assertEquals(3, out.get("count").getAsInt()); assertEquals(6, out.get("mean").getAsDouble());
    assertEquals(2, out.get("std_dev").getAsDouble());
  }
  @Test void relativeWindowUsesTheAdmittedRecordingEndBeforeMaterializingHistory() throws Exception {
    doubles(); var r = request("/n"); r.addProperty("last_seconds", 1);
    var d = RecordingAnalysis.Limits.defaults();
    var owner = new RecordingAnalysis(new RecordingBindings(workspace.sources),
        new RecordingAnalysis.Limits(d.records(), 4, d.retainedBytes(), d.recordBytes(), d.resultBytes(), d.nanos()), System::nanoTime);
    var result = owner.statistics(r);
    assertEquals(4, result.get("count").getAsInt()); assertEquals(7, result.get("mean").getAsDouble());
    var inputs = result.getAsJsonObject("inputs"); assertEquals(1, inputs.get("last_seconds").getAsInt());
    assertEquals(2, inputs.getAsJsonObject("window").get("start").getAsDouble());
    assertEquals(3, inputs.getAsJsonObject("window").get("end").getAsDouble());
    assertFalse(r.has("start_time"), "Resolving a relative window never mutates the caller's arguments");
    for (var bad : List.of(new JsonPrimitive(0), new JsonPrimitive(-1), new JsonPrimitive(Double.NaN),
        new JsonPrimitive(Double.POSITIVE_INFINITY), new JsonPrimitive("1"), new JsonPrimitive(true))) {
      r.add("last_seconds", bad); assertThrows(IllegalArgumentException.class, () -> workspace.statistics(r));
    }
    r.addProperty("last_seconds", 1);
    for (var key : List.of("start_time", "end_time")) {
      r.addProperty(key, 2); assertTrue(assertThrows(IllegalArgumentException.class, () -> workspace.statistics(r)).getMessage().contains(key)); r.remove(key);
    }
  }
  @ParameterizedTest @org.junit.jupiter.params.provider.NullSource @ValueSource(strings = {"-3", "3"})
  void emptyHistoryScopeDoesNotInventMeasuredSessionBounds(String clock) throws Exception {
    for (int member = 0; member < 2; member++) fixture.rewrite(member, w -> w.start("/empty", "double", "", 0));
    try (var admission = workspace.sources.admitRecording(fixture.reference).prefix()) {
      var summary = RecordingInventory.summary(admission);
      assertTrue(summary.get("start_time_sec").isJsonNull()); assertTrue(summary.get("end_time_sec").isJsonNull());
      if (clock != null) summary.getAsJsonObject("clock").addProperty("robot_time_sec", Double.parseDouble(clock));
      var first = admission.members().get(0).snapshot();
      var history = new RecordingAnalysis.History(admission.recordingId(), first.entries().get("/empty"), first.schemas(), List.of(), summary, new RecordingAnalysis.Problems());
      assertTrue(history.sessionTimeRange().isEmpty(), "A usable empty scope is not an observed session duration");
      assertEquals(0, history.minTimestamp()); assertEquals(0, history.maxTimestamp());
      assertEquals(clock == null ? 0 : Double.parseDouble(clock), history.timeScopeEnd());
      summary.addProperty("start_time_sec", -5); summary.addProperty("end_time_sec", -4);
      var range = history.sessionTimeRange().orElseThrow();
      assertEquals(-5, range.startSec()); assertEquals(-4, range.endSec());
      assertEquals(clock == null ? -4 : Math.max(-4, Double.parseDouble(clock)), history.timeScopeEnd());
    }
  }
  @Test void scalarStructProjectionKeepsTheExistingAngleStatisticsOwner() throws Exception {
    fixture.rewrite(0, w -> { int id = w.start("/angle", "struct:Rotation2d", "", 0); w.append(id, 2_000_000, WpilogWriter.encodeDouble(Math.toRadians(-179))); });
    fixture.rewrite(1, w -> { int id = w.start("/angle", "struct:Rotation2d", "", 0); w.append(id, 1_000_000, WpilogWriter.encodeDouble(Math.toRadians(179))); });
    var r = request("/angle"); r.addProperty("field", "_derived.degrees"); var out = workspace.statistics(r);
    assertEquals(180, out.get("mean").getAsDouble(), 1e-10); assertEquals(Math.sqrt(2), out.get("std_dev").getAsDouble(), 1e-10);
    assertTrue(out.getAsJsonObject("angle").get("unwrapped").getAsBoolean());
    assertEquals(-179, number(workspace.value(r)), 1e-10);
  }
  @Test void malformedAndNonfiniteValuesRemainDisclosedRatherThanZero() throws Exception {
    fixture.rewrite(0, w -> { int id = w.start("/n", "double", "", 0);
      w.append(id, 1, WpilogWriter.encodeDouble(2)); w.append(id, 2, new byte[]{1});
      w.append(id, 3, WpilogWriter.encodeDouble(Double.NaN));
    });
    fixture.rewrite(1, w -> { int id = w.start("/n", "double", "", 0); w.append(id, 4, WpilogWriter.encodeDouble(4)); });
    var out = workspace.statistics(request("/n"));
    assertEquals(2, out.get("count").getAsInt()); assertEquals(3, out.get("mean").getAsDouble());
    assertEquals(1, out.get("problem_count").getAsInt());
    assertEquals("capture.wpilog", out.getAsJsonArray("problems").get(0).getAsJsonObject().get("member_id").getAsString());
    assertEquals(1, out.getAsJsonObject("data_quality").get("nan_filtered").getAsInt());
    var value = request("/n"); value.addProperty("at_sec", .000003);
    assertTrue(Double.isNaN(number(workspace.value(value))), "NaN is a logged value, not a hold of the preceding finite value");
  }
  @ParameterizedTest @ValueSource(strings = {"records", "samples", "retained", "record", "result"})
  void everyResourceLimitRefusesInsteadOfPublishingPartialNumbers(String bound) throws Exception {
    doubles(); var defaults = RecordingAnalysis.Limits.defaults();
    var limits = new RecordingAnalysis.Limits(bound.equals("records") ? 5 : defaults.records(),
        bound.equals("samples") ? 5 : defaults.samples(), bound.equals("retained") ? 1151 : defaults.retainedBytes(),
        bound.equals("record") ? 4 : defaults.recordBytes(), bound.equals("result") ? 128 : defaults.resultBytes(), defaults.nanos());
    var service = new RecordingAnalysis(new RecordingBindings(workspace.sources), limits, System::nanoTime);
    var refusal = assertThrows(ReadException.class, () -> service.statistics(request("/n")));
    assertEquals(ReadProblem.Code.RESOURCE_LIMIT, refusal.code()); assertFalse(refusal.retryable());
    assertTrue(logs.release(temp).released());
  }
  @Test void resourceBoundariesAreInclusiveAndEstimateExpandedValuesWithoutEncoding() throws Exception {
    doubles(); var d = RecordingAnalysis.Limits.defaults();
    var owner = new RecordingAnalysis(new RecordingBindings(workspace.sources), new RecordingAnalysis.Limits(6, 6, 1152, d.recordBytes(), d.resultBytes(), d.nanos()), System::nanoTime);
    assertEquals(6, owner.statistics(request("/n")).get("count").getAsInt());
    assertEquals(64, ReadWire.estimatedValueBytes(1.0, 64));
    assertEquals(256, ReadWire.estimatedValueBytes(new double[]{1, 2, 3}, 256));
    assertThrows(ReadWire.WireLimit.class, () -> ReadWire.estimatedValueBytes(new double[]{1, 2, 3}, 255));
  }
  @Test void uncomputableStatisticsUseTheSharedNullAndWarningContract() throws Exception {
    fixture.rewrite(0, w -> { int id = w.start("/overflow", "double", "", 0);
      w.append(id, 1, WpilogWriter.encodeDouble(Double.MAX_VALUE)); w.append(id, 2, WpilogWriter.encodeDouble(-Double.MAX_VALUE));
    });
    var result = workspace.statistics(request("/overflow"));
    assertEquals("ok", result.get("status").getAsString()); assertEquals(2, result.get("count").getAsInt());
    assertTrue(result.get("std_dev").isJsonNull()); assertTrue(result.getAsJsonArray("warnings").toString().contains("replaced with null"));
  }
  @Test void sparseWindowsStillCountEveryVisitedRecordAndDoNotDecodeExcludedMalformedData() throws Exception {
    fixture.rewrite(0, w -> { int id = w.start("/n", "double", "", 0);
      w.append(id, 1, new byte[]{1}); w.append(id, 2, WpilogWriter.encodeDouble(6));
    });
    var r = request("/n"); r.addProperty("start_time", .000002); r.addProperty("end_time", .000002);
    var result = workspace.statistics(r); assertEquals(1, result.get("count").getAsInt());
    assertTrue(result.get("std_dev").isJsonNull()); assertEquals(2, result.get("records_visited").getAsLong());
    assertEquals(0, result.get("problem_count").getAsInt());
  }
  @Test void missingScalarValuesAndNonNumericOrEmptyStatisticsNeverBecomeZero() throws Exception {
    fixture.rewrite(0, w -> { int a = w.start("/array", "double[]", "", 0);
      w.append(a, 1, WpilogWriter.encodeDouble(7)); w.append(a, 2, new byte[0]);
      int n = w.start("/nan", "double", "", 0); w.append(n, 1, WpilogWriter.encodeDouble(Double.NaN));
      int s = w.start("/text", "string", "", 0); w.append(s, 1, "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    });
    var array = request("/array"); array.addProperty("field", "[0]");
    assertTrue(workspace.value(array).get("value").isJsonNull(), "The later empty array is a recorded missing field, not the previous value");
    assertEquals("2", workspace.value(array).get("timestamp_us").getAsString());
    assertEquals(1, workspace.statistics(array).get("count").getAsInt());
    assertEmptyStatistics(workspace.statistics(request("/nan")));
    assertThrows(IllegalArgumentException.class, () -> workspace.statistics(request("/text")));
    var empty = array.deepCopy(); empty.addProperty("start_time", 5); assertEmptyStatistics(workspace.statistics(empty));
  }
  private static void assertEmptyStatistics(JsonObject result) {
    assertEquals("no_match", result.get("status").getAsString(), result.toString());
    assertEquals(0, result.get("count").getAsInt());
    assertFalse(result.has("mean"), "No finite sample is not a zero measurement");
    assertTrue(result.has("looked_for")); assertTrue(result.get("hint").getAsString().contains("samples"));
    assertTrue(result.has("inputs")); assertTrue(result.has("prefix"));
  }
  @ParameterizedTest @ValueSource(strings = {"type", "metadata", "schema"})
  void exactAnalysisRefusesConflictingMemberDeclarations(String conflict) throws Exception {
    if (conflict.equals("schema")) {
      fixture.rewrite(0, w -> RecordingBindingsTest.struct(w, "double value;", 2));
      fixture.rewrite(1, w -> RecordingBindingsTest.struct(w, "double other;", 3));
    } else {
      fixture.rewrite(0, w -> RecordingBindingsTest.scalar(w, "/measure", "double", "", 1));
      fixture.rewrite(1, w -> RecordingBindingsTest.scalar(w, "/measure", conflict.equals("type") ? "int64" : "double", conflict.equals("metadata") ? "{\"unit\":\"A\"}" : "", 1));
    }
    var r = request("/measure"); if (conflict.equals("schema")) r.addProperty("field", "value");
    for (boolean stats : List.of(false, true)) assertTrue(assertThrows(IOException.class,
        () -> { if (stats) workspace.statistics(r); else workspace.value(r); }).getMessage().contains("SOURCE_UNAVAILABLE"));
  }
  @Test void malformedDetailsAreCappedButTotalAndMemberProvenanceStayExact() throws Exception {
    fixture.rewrite(0, w -> { int id = w.start("/n", "double", "", 0);
      for (int i = 0; i < 105; i++) w.append(id, i, new byte[]{1}); w.append(id, 106, WpilogWriter.encodeDouble(2));
    });
    for (var result : List.of(workspace.value(request("/n")), workspace.statistics(request("/n")))) {
      assertEquals(105, result.get("problem_count").getAsInt()); assertEquals(100, result.getAsJsonArray("problems").size());
      assertTrue(result.get("problems_truncated").getAsBoolean());
      assertEquals(106, result.get("records_visited").getAsInt());
    }
  }
  @Test void newCallsRecheckSourceHashesAndGrants() throws Exception {
    doubles(); workspace.value(request("/n")); workspace.statistics(request("/n"));
    assertTrue(logs.release(temp).released());
    var path = fixture.directory.resolve("capture.wpilog"); var bytes = Files.readAllBytes(path); bytes[bytes.length - 1] ^= 1; Files.write(path, bytes);
    assertThrows(IOException.class, () -> workspace.value(request("/n")));
    logs.clearAllowedDirectories(); logs.addAllowedDirectory(Files.createDirectory(temp.resolve("revoked")));
    assertThrows(IOException.class, () -> workspace.statistics(request("/n")));
  }
  @ParameterizedTest @ValueSource(booleans = {false, true})
  void grantOrBoundaryChangeAtPublicationDiscardsTheAnswer(boolean boundary) throws Exception {
    doubles(); var checks = new AtomicInteger();
    var service = new RecordingAnalysis(new RecordingBindings(source -> {
      var a = workspace.sources.admitRecording(source);
      return new RecordedSources.Admission(a.prefix(), () -> {
        if (checks.incrementAndGet() == 2) {
          if (boundary) Files.setLastModifiedTime(fixture.directory.resolve("capture.wpilog"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 30_000));
          else throw new java.nio.file.AccessDeniedException("revoked during analysis");
        }
        a.access().check();
      });
    }));
    var failure = assertThrows(IOException.class, () -> service.statistics(request("/n")));
    if (boundary) assertEquals(ReadProblem.Code.FILE_CHANGED, ((ReadException) failure).code());
    else assertInstanceOf(java.nio.file.AccessDeniedException.class, failure);
    assertEquals(2, checks.get());
  }
  @Test void cancellationAndExpiredSessionReleaseTheSlotBeforeAnotherRead() throws Exception {
    var opens = new AtomicInteger();
    var service = new RecordingAnalysis(new RecordingBindings(source -> { opens.incrementAndGet(); return workspace.sources.admitRecording(source); }));
    assertThrows(java.nio.file.AccessDeniedException.class, () -> service.value(request("/signal/00000"), () -> false)); assertEquals(0, opens.get());
    try {
      Thread.currentThread().interrupt(); assertThrows(InterruptedIOException.class, () -> service.statistics(request("/signal/00000")));
    } finally { Thread.interrupted(); }
    assertEquals(3, service.statistics(request("/signal/00000")).get("count").getAsInt());
    var checks = new AtomicInteger();
    assertThrows(java.nio.file.AccessDeniedException.class, () -> service.statistics(request("/signal/00000"), () -> checks.incrementAndGet() < 4));
    assertTrue(logs.release(temp).released());
  }
  @Test void malformedTimesAndWildcardOrMissingSelectionsRefuseWithoutInventingAnswers() throws Exception {
    doubles();
    for (var text : List.of("\"2\"", "null", "true", "1e300")) {
      var r = request("/n"); r.add("at_sec", JsonParser.parseString(text)); assertThrows(IllegalArgumentException.class, () -> workspace.value(r));
      r.remove("at_sec"); r.add("start_time", JsonParser.parseString(text)); assertThrows(IllegalArgumentException.class, () -> workspace.statistics(r));
    }
    var r = request("/n"); r.addProperty("start_time", 3); r.addProperty("end_time", 2);
    assertThrows(IllegalArgumentException.class, () -> workspace.statistics(r));
    var wildcard = request("/n"); wildcard.addProperty("field", "[*]");
    assertThrows(IllegalArgumentException.class, () -> workspace.value(wildcard));
    assertThrows(IllegalArgumentException.class, () -> workspace.statistics(request("/missing")));
  }
  @Test void cooperativeDeadlineIncludesAdmissionAndReleasesAllResources() throws Exception {
    var time = new AtomicLong();
    var service = new RecordingAnalysis(new RecordingBindings(source -> {
      var a = workspace.sources.admitRecording(source); time.addAndGet(java.time.Duration.ofSeconds(11).toNanos()); return a;
    }), RecordingAnalysis.Limits.defaults(), time::get);
    for (int i = 0; i < 3; i++) {
      var failure = assertThrows(ReadException.class, () -> service.value(request("/signal/00000")));
      assertFalse(failure.retryable()); assertTrue(failure.getMessage().contains("work budget"));
    }
    assertTrue(logs.release(temp).released());
  }
  @Test void twoBusyAnalysesRefuseImmediatelyWithoutBlockingMetadataOrLeakingSlots() throws Exception {
    var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
    var service = new RecordingAnalysis(new RecordingBindings(source -> {
      var a = workspace.sources.admitRecording(source); entered.countDown();
      try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture deadline"); return a; }
      catch (InterruptedException failure) { a.close(); throw new InterruptedIOException(); }
    }));
    var pool = Executors.newFixedThreadPool(2);
    try {
      var a = pool.submit(() -> service.value(request("/signal/00000"))); var b = pool.submit(() -> service.statistics(request("/signal/00000")));
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      var failure = assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> assertThrows(ReadException.class, () -> service.value(request("/signal/00000"))));
      assertTrue(failure.retryable()); assertTrue(failure.getMessage().contains("Two recording analyses"));
      workspace.views.create(org.triplehelix.wpilogmcp.mcp.CallerContext.Authority.STDIO_LOCAL, UUID.randomUUID(), fixture.reference, VisualizationContracts.blank()).get(1, TimeUnit.SECONDS);
      release.countDown(); assertEquals(1, number(a.get(3, TimeUnit.SECONDS))); assertEquals(3, b.get(3, TimeUnit.SECONDS).get("count").getAsInt());
      assertEquals(1, number(service.value(request("/signal/00000"))));
    } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS)); }
  }
  @Test void appendsDuringAdmissionCannotLeakIntoTheAlreadyAdmittedPrefix() throws Exception {
    var path = fixture.directory.resolve("capture.wpilog"); Files.delete(path);
    var topic = new org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce("/x", 1, "int", null, new JsonObject());
    var robotTime = new AtomicReference<Double>(3_000_000.0);
    var index = new org.triplehelix.wpilogmcp.capture.CaptureIndex((address, start) -> path, logs, 10_000_000, robotTime::get);
    try (var writer = new org.triplehelix.wpilogmcp.capture.CaptureWriter(
        java.time.Clock.fixed(java.time.Instant.parse(RecordingFixture.WHEN), java.time.ZoneOffset.UTC),
        new org.triplehelix.wpilogmcp.nt4.client.ManualScheduler(), org.triplehelix.wpilogmcp.capture.CapturePolicy.ALL, index)) {
      writer.connected(org.triplehelix.wpilogmcp.nt4.client.RobotAddress.uri("127.0.0.1", 5810, "analysis-test"), "networktables.first.wpi.edu");
      writer.timeSync(1_000_000, 0); writer.announce(topic);
      writer.value(topic, new org.triplehelix.wpilogmcp.nt4.ValueFrame(1, 1_000_001, 2, 41L), 0);
      var manifest = fixture.directory.resolve("session.json"); var state = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
      var captured = state.getAsJsonArray("files").remove(0).getAsJsonObject();
      var provenance = org.triplehelix.wpilogmcp.store.StoreJson.JSON.fromJson(captured.get("provenance"), org.triplehelix.wpilogmcp.store.StoreManifest.Provenance.class);
      var open = org.triplehelix.wpilogmcp.store.StoreJson.JSON.toJsonTree(new org.triplehelix.wpilogmcp.store.StoreManifest.OpenCapture("capture.wpilog", provenance, Files.size(path), 1, 1));
      state.add("open_capture", open); Files.writeString(manifest, state.toString()); logs.catalogPublished();
      var opens = new AtomicInteger();
      var service = new RecordingAnalysis(new RecordingBindings(source -> {
        var a = workspace.sources.admitRecording(source); opens.incrementAndGet();
        writer.value(topic, new org.triplehelix.wpilogmcp.nt4.ValueFrame(1, 2_000_000, 2, 99L), 0); return a;
      }));
      var value = service.value(request("NT:/x")); assertEquals(1, opens.get()); assertEquals(41, number(value));
      assertEquals("1", value.getAsJsonObject("prefix").getAsJsonArray("members").get(0).getAsJsonObject().get("entry_records").getAsString());
      var stats = service.statistics(request("NT:/x")); assertEquals(2, opens.get());
      assertEquals(2, stats.get("count").getAsInt()); assertEquals(70, stats.get("mean").getAsDouble());
      assertEquals(99, number(workspace.value(request("NT:/x"))));
      var relative = request("NT:/x"); relative.addProperty("last_seconds", .5);
      var quiet = workspace.statistics(relative); assertEmptyStatistics(quiet);
      assertEquals(3, quiet.getAsJsonObject("inputs").getAsJsonObject("window").get("end").getAsDouble(),
          "The admitted robot clock, not the last sample, anchors a quiet recording");
      relative.addProperty("end_time", 2); relative.addProperty("start_time", 1);
      assertThrows(IllegalArgumentException.class, () -> workspace.statistics(relative), "Invalid bounds remain a request error");
      relative.remove("end_time"); relative.remove("start_time");
      robotTime.set(2_500_000.0); var recent = workspace.statistics(relative);
      assertEquals(2, recent.get("count").getAsInt()); assertEquals(99, recent.get("mean").getAsDouble());
      assertEquals(2.5, recent.getAsJsonObject("inputs").getAsJsonObject("window").get("end").getAsDouble());
      robotTime.set(3_000_000.0);
      // A new sample makes the same still-live window useful, without changing its selector.
      writer.value(topic, new org.triplehelix.wpilogmcp.nt4.ValueFrame(1, 2_750_000, 2, 17L), 0);
      var advanced = workspace.statistics(relative);
      assertEquals(1, advanced.get("count").getAsInt()); assertEquals(17, advanced.get("mean").getAsDouble());
    }
  }
}
