/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.CaptureIndex;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.tools.LogRequiringTool;
import org.triplehelix.wpilogmcp.tools.ToolDependencies;

class LiveLogTest {
  @TempDir Path directory;
  private static final Announce X = new Announce("/x", 1, "int", null, new JsonObject());
  private static void connect(CaptureWriter writer, long time, long received) {
    writer.connected(RobotAddress.uri("127.0.0.1", 5810, "test"), "networktables.first.wpi.edu");
    writer.timeSync(time, received); writer.announce(X);
  }
  private CaptureIndex index(LogManager manager, Path path, long hot) {
    return new CaptureIndex(new CaptureWriter.Observer() {
      @Override public Path create(String address, Instant start) { return path; }
    }, manager, hot);
  }

  @Test void aCallKeepsItsPrefixDuringAppendAndReportsItsSessionRangeEvenWithRoleInputs() throws Exception {
    var manager = new LogManager(); manager.addAllowedDirectory(directory);
    var path = directory.resolve("prefix.wpilog"); var index = index(manager, path, 0);
    var entered = new CompletableFuture<Void>(); var appended = new CompletableFuture<Void>();
    var pool = Executors.newSingleThreadExecutor();
    var loop = new ManualScheduler();
    try (var writer = new CaptureWriter(Clock.systemUTC(), loop, CapturePolicy.ALL, index)) {
      connect(writer, 1_000_000, 0); writer.value(X, new ValueFrame(1, 1_000_000, 2, 1L), 0);
      var tool = new LogRequiringTool(new ToolDependencies(manager, null, null, null)) {
        @Override public String name() { return "prefix_probe"; }
        @Override public String description() { return "Test the prefix through the normal tool base."; }
        @Override protected JsonObject toolSchema() { return new JsonObject(); }
        @Override protected com.google.gson.JsonElement executeWithLog(LogData log, JsonObject args) throws Exception {
          entered.complete(null); appended.get(10, TimeUnit.SECONDS);
          var result = new JsonObject();
          result.addProperty("status", "ok"); result.addProperty("success", true);
          result.addProperty("count", log.sampleCount("NT:/x"));
          result.addProperty("entries", log.entries().size());
          result.addProperty("sum", log.values().get("NT:/x").stream().mapToLong(v -> ((Number) v.value()).longValue()).sum());
          var inputs = new JsonObject(); inputs.addProperty("role", "NT:/x"); result.add("inputs", inputs);
          return result;
        }
      };
      var args = new JsonObject(); args.addProperty("path", path.toString());
      var first = pool.submit(() -> tool.execute(args)); entered.get(10, TimeUnit.SECONDS);
      writer.announce(new Announce("/later", 2, "int", null, new JsonObject()));
      writer.value(X, new ValueFrame(1, 2_000_000, 2, 2L), 0); appended.complete(null);
      var before = first.get(10, TimeUnit.SECONDS).getAsJsonObject();
      assertEquals(1, before.get("count").getAsInt()); assertEquals(1, before.get("sum").getAsLong());
      assertEquals(1, before.get("entries").getAsInt());
      assertEquals(1, before.getAsJsonObject("inputs").getAsJsonObject("session_time_range").get("end_sec").getAsDouble());
      var after = tool.execute(args).getAsJsonObject();
      assertEquals(2, after.get("count").getAsInt()); assertEquals(3, after.get("sum").getAsLong());
      assertEquals(2, after.get("entries").getAsInt());
      assertEquals(2, after.getAsJsonObject("inputs").getAsJsonObject("session_time_range").get("end_sec").getAsDouble());
      assertEquals("ok", before.get("status").getAsString()); assertEquals("ok", after.get("status").getAsString());
      String guide = Files.readString(Path.of("doc/TOOLS.md"));
      for (String field : List.of("inputs.session_time_range", "inputs.session_time_ranges", "start_sec", "end_sec")) {
        assertTrue(guide.contains("`" + field + "`"), field);
      }
      loop.advance(250_000);
      assertEquals(0, index.live().hotRecordCount());
      assertFalse(manager.release(path).released(), "An import cannot move an active writer");
      try (var canonical = manager.acquire(path.toRealPath().toString())) {
        assertInstanceOf(LiveLog.View.class, canonical.log()); assertNull(canonical.snapshot());
      }
      assertFalse(manager.release(path.toRealPath()).released());
    } finally { pool.shutdownNow(); manager.shutdown(); }
  }

  @Test void mappedColdValuesSurviveGrowthRetirementAndResumeAfterEviction() throws Exception {
    var manager = new LogManager(); manager.addAllowedDirectory(directory);
    var path = directory.resolve("growth.wpilog"); var index = index(manager, path, 2_000_000);
    var loop = new ManualScheduler();
    try (var writer = new CaptureWriter(Clock.systemUTC(), loop, CapturePolicy.ALL, index)) {
      connect(writer, 10_000_000, 0);
      for (int i = 0; i < 2200; i++) writer.value(X, new ValueFrame(1, 10_000_000L + i * 1_000_000L, 2, (long) i), i);
      loop.advance(250_000);
      assertEquals(2, index.live().hotRecordCount());
      try (var use = manager.acquire(path.toString())) {
        assertInstanceOf(LiveLog.View.class, use.log()); assertNull(use.snapshot());
        assertEquals(2200, use.log().sampleCount("NT:/x"));
        writer.value(X, new ValueFrame(1, 2_210_000_000L, 2, 2200L), 2200);
        writer.disconnected();
        assertSame(index.live(), manager.getAllLoadedLogs().get(path.toString()));
        try (var finished = manager.acquire(path.toString())) {
          assertInstanceOf(LiveLog.View.class, finished.log()); assertTrue(finished.log().sessionTimeRange().isEmpty());
          assertNotNull(finished.snapshot()); assertEquals(2201, finished.log().sampleCount("NT:/x"));
        }
        assertTrue(manager.unloadLog(path.toString()));
        // Decode for the first time after retirement, including across two array chunks and remaps.
        long reads = index.live().mappedReadCount();
        var values = use.log().values().get("NT:/x"); assertEquals(2200, values.size());
        assertEquals(2198, index.live().mappedReadCount() - reads, "The expired values must come from the mapping");
        for (int i = 0; i < values.size(); i++) { assertEquals((long) i, values.get(i).value()); assertEquals(10.0 + i, values.get(i).timestamp()); }
      }
      try (var loaded = manager.acquire(path.toString())) {
        assertInstanceOf(LazyParsedLog.class, loaded.log()); assertEquals(2201, loaded.log().sampleCount("NT:/x"));
        assertEquals(2200L, loaded.log().values().get("NT:/x").get(2200).value());
      }
      // Use a continuing time-sync clock, independently of the deliberately accelerated data clock.
      connect(writer, 11_000_000, 1_000_000);
      try (var resumed = manager.acquire(path.toString())) {
        assertEquals(0L, resumed.log().values().get("NT:/x").get(0).value());
      }
      writer.value(X, new ValueFrame(1, 2_211_000_000L, 2, 2201L), 2201);
      try (var resumed = manager.acquire(path.toString())) {
        assertInstanceOf(LiveLog.View.class, resumed.log()); assertEquals(2202, resumed.log().sampleCount("NT:/x"));
        assertEquals(0L, resumed.log().values().get("NT:/x").get(0).value());
        assertEquals(2201L, resumed.log().values().get("NT:/x").get(2201).value());
      }
      writer.disconnected(); assertTrue(manager.release(path).released());
      Files.move(path, directory.resolve("released.wpilog")); // Native Windows must have no mapped handle left.
    } finally { manager.shutdown(); }
  }

  @Test void declarationOrderSchemasAndEveryHotValueFamilyAgreeWithColdDecoding() throws Exception {
    var manager = new LogManager(); manager.addAllowedDirectory(directory);
    var path = directory.resolve("families.wpilog"); var index = index(manager, path, 1);
    var json = new com.google.gson.GsonBuilder().serializeSpecialFloatingPointValues().create();
    var loop = new ManualScheduler();
    try (var writer = new CaptureWriter(Clock.systemUTC(), loop, CapturePolicy.ALL, index)) {
      connect(writer, 1_000_000, 0);
      var types = List.of("boolean", "double", "int", "float", "string", "raw", "boolean[]", "double[]", "int[]", "float[]", "string[]", "structschema", "struct:Point", "custom");
      var codes = List.of(0, 1, 2, 3, 4, 5, 16, 17, 18, 19, 20, 5, 5, 5);
      var vals = List.of(true, 2.5, -4L, 3.25f, "é", new byte[]{4, 2}, List.of(true, false), List.of(1.0, 2.0), List.of(1L, 2L), List.of(1.5f), List.of("a", "b"),
          "int32 x;".getBytes(java.nio.charset.StandardCharsets.UTF_8), new byte[]{3, 0, 0, 0}, new byte[]{1, 2});
      var hotResults = new java.util.LinkedHashMap<String, String>();
      for (int i = 0; i < types.size(); i++) {
        String name = i == 11 ? "/.schema/struct:Point" : "/t" + i;
        var topic = new Announce(name, i + 2, types.get(i), null, new JsonObject()); writer.announce(topic);
        writer.value(topic, new ValueFrame(i + 2, 1_000_000 + i * 100, codes.get(i), vals.get(i)), 0);
        try (var use = manager.acquire(path.toString())) { hotResults.put("NT:" + name, json.toJson(use.log().values().get("NT:" + name))); }
      }
      writer.timeSync(3_000_000, 1_000_000);
      loop.advance(250_000);
      assertEquals(0, index.live().hotRecordCount(), "Idle topics expire on the flush after clock sync, without another value");
      writer.value(X, new ValueFrame(1, 2_000_000, 2, 7L), 0);
      try (var use = manager.acquire(path.toString())) {
        assertEquals("NT:/x", use.log().entries().keySet().iterator().next());
        for (var entry : hotResults.entrySet()) assertEquals(entry.getValue(), json.toJson(use.log().values().get(entry.getKey())), entry.getKey());
        assertEquals(3, ((Number) ((java.util.Map<?, ?>) use.log().values().get("NT:/t12").get(0).value()).get("x")).intValue());
      }
      writer.disconnected(); manager.unloadLog(path.toString());
      try (var use = manager.acquire(path.toString())) {
        for (var entry : hotResults.entrySet()) assertEquals(entry.getValue(), json.toJson(use.log().values().get(entry.getKey())), entry.getKey());
      }
    } finally { manager.shutdown(); }
  }


  @Test void zeroHotWindowExpiresOnlyOnQuarterSecondFlushes() throws Exception {
    var manager = new LogManager(); manager.addAllowedDirectory(directory);
    var path = directory.resolve("ticks.wpilog"); var index = index(manager, path, 0);
    var loop = new ManualScheduler();
    try (var writer = new CaptureWriter(Clock.systemUTC(), loop, CapturePolicy.ALL, index)) {
      connect(writer, 1_000_000, 0);
      long mappings = index.live().mappingCount();
      for (int tick = 0; tick < 4; tick++) {
        for (int i = 0; i < 100; i++) {
          long n = tick * 100L + i;
          writer.value(X, new ValueFrame(1, 1_000_000 + n, 2, n), loop.nowUs());
        }
        writer.timeSync(2_000_000 + tick, loop.nowUs());
        assertEquals(100, index.live().hotRecordCount(), "Append and time sync only advance the expiry boundary");
        assertEquals(mappings + tick, index.live().mappingCount());
        loop.advance(249_999);
        assertEquals(100, index.live().hotRecordCount());
        loop.advance(1);
        assertEquals(0, index.live().hotRecordCount());
        assertEquals(mappings + tick + 1, index.live().mappingCount());
      }
      long reads = index.live().mappedReadCount();
      try (var use = manager.acquire(path.toString())) {
        var values = use.log().values().get("NT:/x");
        assertEquals(400, values.size());
        for (int i = 0; i < 400; i++) {
          assertEquals((long) i, values.get(i).value());
          assertEquals((1_000_000 + i) / 1_000_000.0, values.get(i).timestamp());
        }
      }
      assertEquals(400, index.live().mappedReadCount() - reads);
    } finally { manager.shutdown(); }
  }

  @Test void eachRolloverFileCanDecodeItsCustomStructWithoutAnotherFile() throws Exception {
    var manager = new LogManager(); manager.addAllowedDirectory(directory);
    var path = directory.resolve("capture.wpilog"); var index = index(manager, path, 0);
    var loop = new ManualScheduler();
    try (var writer = new CaptureWriter(Clock.systemUTC(), loop, CapturePolicy.ALL, index, 1024)) {
      connect(writer, 1_000_000, 0);
      var schema = new Announce("/.schema/struct:ReviewPoint", 2, "structschema", null, new JsonObject());
      var point = new Announce("/point", 3, "struct:ReviewPoint", null, new JsonObject());
      writer.announce(schema); writer.announce(point);
      writer.value(schema, new ValueFrame(2, 1_000_000, 5, "int32 x;".getBytes(java.nio.charset.StandardCharsets.UTF_8)), 0);
      for (int i = 0; i < 200; i++) {
        byte[] payload = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(i).array();
        writer.value(point, new ValueFrame(3, 1_000_001 + i, 5, payload), i);
      }
      assertNotEquals(path, writer.session().path());
      loop.advance(250_000);
      try (var use = manager.acquire(writer.session().path().toString())) {
        var values = use.log().values().get("NT:/point");
        assertFalse(values.isEmpty(), "The rolled file needs its schema to decode point values");
        var last = values.get(values.size() - 1).value();
        assertInstanceOf(java.util.Map.class, last);
        assertEquals(199, ((Number) ((java.util.Map<?, ?>) last).get("x")).intValue());
      }
      var last = writer.session().path(); writer.disconnected(); manager.unloadLog(last.toString());
      try (var use = manager.acquire(last.toString())) {
        var values = use.log().values().get("NT:/point");
        assertEquals(199, ((Number) ((java.util.Map<?, ?>) values.get(values.size() - 1).value()).get("x")).intValue());
      }
    } finally { manager.shutdown(); }
  }

  @Test void repeatedDeclarationsAndImpossibleForwardTimestampsFollowTheFinishedReader() throws Exception {
    var manager = new LogManager(); manager.addAllowedDirectory(directory);
    var path = directory.resolve("restarted-entry.wpilog"); var index = index(manager, path, 0);
    var loop = new ManualScheduler();
    try (var writer = new CaptureWriter(Clock.systemUTC(), loop, CapturePolicy.ALL, index)) {
      connect(writer, 1_000_000, 0); writer.value(X, new ValueFrame(1, 1_000_000, 2, 1L), 0);
      var wrongType = new Announce("/x", 2, "string", null, new JsonObject()); writer.announce(wrongType);
      writer.value(wrongType, new ValueFrame(2, 3_000_000, 4, "ignored type restart"), 0);
      var sameType = new Announce("/x", 3, "int", null, new JsonObject()); writer.announce(sameType);
      writer.value(sameType, new ValueFrame(3, -1_000_000, 2, 2L), 0);
      writer.value(sameType, new ValueFrame(3, 90_000_000_000L, 2, 3L), 0);
      String warning;
      try (var use = manager.acquire(path.toString())) {
        assertEquals(1, use.log().entryCount()); assertEquals(1, use.log().entries().get("NT:/x").id());
        assertEquals(2, use.log().sampleCount("NT:/x"));
        assertEquals(List.of(1L, 2L), use.log().values().get("NT:/x").stream().map(TimestampedValue::value).toList());
        assertEquals(-1, use.log().minTimestamp()); assertEquals(1, use.log().maxTimestamp());
        assertTrue(use.log().damaged()); warning = use.log().truncationMessage();
      }
      writer.disconnected(); manager.unloadLog(path.toString());
      try (var use = manager.acquire(path.toString())) {
        assertEquals(2, use.log().sampleCount("NT:/x")); assertEquals(warning, use.log().truncationMessage());
      }
    } finally { manager.shutdown(); }
  }
}
