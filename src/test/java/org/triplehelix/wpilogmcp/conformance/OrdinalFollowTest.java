/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.capture.WpilogOutput;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.log.LiveLog;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.ReadProblem;
import org.triplehelix.wpilogmcp.read.ReadQuery;
import org.triplehelix.wpilogmcp.read.ReadSnapshot;
import org.triplehelix.wpilogmcp.read.SampleBatch;
import org.triplehelix.wpilogmcp.read.SampleCursor;
import org.triplehelix.wpilogmcp.read.internal.ReadWire;
import org.triplehelix.wpilogmcp.read.server.OrdinalFollow;

/** The oracle reads WPILOG framing directly, not SampleCursors, LogManager or follow output.
 * These tests exercise the recorder's actual index/mappings, including a cold malformed value. */
class OrdinalFollowTest {
  @TempDir Path temp;
  private static final String EPOCH = "test-server-epoch", RECORDING = "anchored-recording";
  private static final ReadQuery X = new ReadQuery("/x", List.of(), ReadQuery.Order.FILE, 4096, 4 << 20);
  private static OrdinalFollow.Budget budget() { return new OrdinalFollow.Budget(64L << 20); }

  @ParameterizedTest @ValueSource(strings = {"struct:Outer", "struct:Outer[]", "structarray:Outer"})
  void queriedMetadataKeepsOnlySelectedSchemasAndTheirTransitiveDependencies(String type) throws Exception {
    try (var file = new Recording(temp.resolve("selected-schemas.wpilog"))) {
      for (var schema : Map.of("Outer", "Inner child; double extra;", "Inner", "double x;", "Unused", "double unrelated;").entrySet()) {
        String name = "/.schema/struct:" + schema.getKey(); file.entry(name, "structschema", ""); file.text(name, schema.getValue());
      }
      file.entry("/pose", type, "{}"); file.entry("/rotation", "struct:Rotation2d", "");
      file.entry("/other", "int64", ""); file.number("/other", 123, 99, false);
      var info = file.entries.get("/pose"); byte[] bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putDouble(3).putDouble(7).array();
      var written = file.output.append(info.id(), 456, bytes); file.live.append(info, new ValueFrame(info.id(), 456, 5, bytes), written); file.live.expire();
      String prefix = StructSchemas.isArrayType(type) ? "[0]." : "";
      var pose = new ReadQuery("/pose", List.of(), ReadQuery.Order.FILE, 4096, 1 << 20);
      var queries = List.of(pose.project(prefix + "child.x"), pose.project(prefix + "extra"), new ReadQuery("/rotation", List.of(), ReadQuery.Order.FILE, 4096, 1 << 20));
      try (var follow = new OrdinalFollow(EPOCH, () -> file.prefix(1), queries, budget(), 4096, 1 << 20, OrdinalFollow.FrontierScope.QUERIES)) {
        var result = page(follow, follow.initialCursor()); var snapshot = member(result, 0).getAsJsonObject("snapshot");
        assertEquals(java.util.Set.of("/pose", "/rotation"), snapshot.getAsJsonObject("entries").keySet());
        assertEquals(snapshot.getAsJsonObject("entries").keySet(), snapshot.getAsJsonObject("sample_counts").keySet());
        var schemas = new HashMap<String, JsonObject>(); snapshot.getAsJsonArray("schemas").forEach(s -> schemas.put(s.getAsJsonObject().get("name").getAsString(), s.getAsJsonObject()));
        assertEquals(java.util.Set.of("Outer", "Inner", "Rotation2d"), schemas.keySet());
        assertEquals("LOGGED", schemas.get("Inner").get("source").getAsString());
        assertEquals("WPILIB", schemas.get("Rotation2d").get("source").getAsString());
        assertEquals("/.schema/struct:Inner", schemas.get("Inner").get("schema_entry").getAsString());
        assertEquals("Inner child; double extra;", schemas.get("Outer").get("schema").getAsString());
        var batches = result.getAsJsonArray("batches"); assertEquals(2, batches.size());
        for (var item : batches) {
          var batch = item.getAsJsonObject(); var sample = batch.getAsJsonObject("batch").getAsJsonArray("samples").get(0).getAsJsonObject();
          assertEquals(0, sample.get("ordinal").getAsInt()); assertEquals("456", sample.get("timestamp_us").getAsString());
          assertEquals(batch.get("field").getAsString().endsWith("child.x") ? "4008000000000000" : "401c000000000000", sample.getAsJsonObject("value").get("value").getAsString());
        }
      }
    }
  }

  @Test void queriedSchemaEntriesRetainTheirDependenciesOnce() throws Exception {
    try (var file = new Recording(temp.resolve("selected-schema-entries.wpilog"))) {
      String outer = "/.schema/struct:Outer", inner = "/.schema/struct:Inner";
      file.entry(outer, "structschema", ""); file.text(outer, "Inner first; Inner second;");
      file.entry(inner, "structschema", ""); file.text(inner, "double x;");
      file.entry("/unused", "struct:Pose2d", "");
      var queries = List.of(new ReadQuery(outer, List.of(), ReadQuery.Order.FILE, 4096, 1 << 20),
          new ReadQuery(inner, List.of(), ReadQuery.Order.FILE, 4096, 1 << 20));
      try (var follow = new OrdinalFollow(EPOCH, () -> file.prefix(1), queries, budget(), 4096, 1 << 20, OrdinalFollow.FrontierScope.QUERIES)) {
        var snapshot = member(page(follow, follow.initialCursor()), 0).getAsJsonObject("snapshot");
        assertEquals(java.util.Set.of(outer, inner), snapshot.getAsJsonObject("entries").keySet());
        var schemas = snapshot.getAsJsonArray("schemas"); assertEquals(2, schemas.size());
        assertEquals(java.util.Set.of("Outer", "Inner"), java.util.stream.StreamSupport.stream(schemas.spliterator(), false)
            .map(s -> s.getAsJsonObject().get("name").getAsString()).collect(java.util.stream.Collectors.toSet()));
      }
    }
  }

  @Test void queriedMetadataPublishesLateSelectionsAndSchemasWithoutBecomingACompleteInventory() throws Exception {
    try (var file = new Recording(temp.resolve("selected-late.wpilog"))) {
      file.entry("/x", "int64", ""); file.number("/x", 10, 1, false);
      var late = new ReadQuery("/late", List.of(), ReadQuery.Order.FILE, 4096, 1 << 20);
      try (var follow = new OrdinalFollow(EPOCH, () -> file.prefix(1), List.of(X, late), budget(), 4096, 1 << 20, OrdinalFollow.FrontierScope.QUERIES)) {
        var first = page(follow, follow.initialCursor()); assertEquals(java.util.Set.of("/x"), member(first, 0).getAsJsonObject("snapshot").getAsJsonObject("entries").keySet());
        file.entry("/.schema/struct:Later", "structschema", ""); file.text("/.schema/struct:Later", "double x;");
        file.entry("/late", "struct:Later", "new"); file.entry("/unselected-late", "double", "");
        var next = page(follow, cursor(first)); var snapshot = member(next, 0).getAsJsonObject("snapshot");
        assertEquals(java.util.Set.of("/x", "/late"), snapshot.getAsJsonObject("entries").keySet());
        assertEquals(0, snapshot.getAsJsonObject("sample_counts").get("/late").getAsInt());
        assertEquals(1, snapshot.getAsJsonArray("schemas").size());
        assertEquals("Later", snapshot.getAsJsonArray("schemas").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(next, page(follow, cursor(first)), "New metadata page is retained byte-for-byte until acknowledgement");
      }
    }
  }

  @Test void queriedMetadataKeepsInvalidSchemaDiagnosticsWithoutAddingUnrelatedSchemas() throws Exception {
    try (var file = new Recording(temp.resolve("selected-invalid.wpilog"))) {
      file.entry("/.schema/struct:Broken", "structschema", ""); file.text("/.schema/struct:Broken", "Missing child;");
      file.entry("/broken", "struct:Broken", "");
      try (var follow = new OrdinalFollow(EPOCH, () -> file.prefix(1), List.of(new ReadQuery("/broken", List.of(), ReadQuery.Order.FILE, 4096, 1 << 20)), budget(), 4096, 1 << 20, OrdinalFollow.FrontierScope.QUERIES)) {
        var schemas = member(page(follow, follow.initialCursor()), 0).getAsJsonObject("snapshot").getAsJsonArray("schemas");
        assertEquals(1, schemas.size()); var schema = schemas.get(0).getAsJsonObject();
        assertEquals("Broken", schema.get("name").getAsString()); assertFalse(schema.get("valid").getAsBoolean());
        assertTrue(schema.get("error").getAsString().contains("Missing"));
      }
    }
  }

  @Test void queriedMetadataStillProvesUnselectedEntryHistoryOnEveryPrefix() throws Exception {
    try (var file = new Recording(temp.resolve("selected-proof.wpilog"))) {
      file.entry("/x", "int64", ""); file.number("/x", 10, 1, false);
      file.entry("/hidden", "int64", ""); file.number("/hidden", 20, 2, false);
      var opens = new AtomicInteger(); var closes = new AtomicInteger();
      try (var follow = new OrdinalFollow(EPOCH, () -> {
        boolean shrink = opens.getAndIncrement() > 0; var member = file.member("member", 1);
        var snapshot = new TrackingSnapshot(member.snapshot(), closes) {
          @Override public long sampleCount(String entry) { return shrink && entry.equals("/hidden") ? 0 : super.sampleCount(entry); }
        };
        return new OrdinalFollow.Prefix(RECORDING, 1, false, List.of(new OrdinalFollow.Member("member", 1, snapshot, null, file.writerIdentity)));
      }, List.of(X), budget(), 4096, 1 << 20, OrdinalFollow.FrontierScope.QUERIES)) {
        var first = page(follow, follow.initialCursor());
        assertFalse(member(first, 0).getAsJsonObject("snapshot").getAsJsonObject("entries").has("/hidden"));
        var refused = assertThrows(ReadException.class, () -> follow.next(cursor(first)));
        assertEquals(ReadProblem.Code.FILE_CHANGED, refused.code()); assertFalse(refused.retryable());
        assertTrue(refused.getMessage().contains("retained history shrank"));
        assertEquals(2, closes.get(), "The acknowledged prefix and the rejected successor both release their snapshots");
      }
    }
  }

  @Test void twoFieldsOfOneEntryHaveIndependentProgressAndUseTheNativeProjector() throws Exception {
    try (var file = new Recording(temp.resolve("fields.wpilog"))) {
      file.entry("/x", "double[]", "");
      var entry = file.entries.get("/x");
      var written = file.output.append(entry.id(), 100, ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putDouble(3).putDouble(7).array());
      file.live.append(entry, new ValueFrame(entry.id(), 100, 17, List.of(3.0, 7.0)), written);
      var queries = List.of(X.project("[0]"), X.project("[1]"));
      try (var follow = new OrdinalFollow(EPOCH, () -> file.prefix(1), queries, budget(), 1, 128 << 10)) {
        var a = page(follow, follow.initialCursor()); var b = page(follow, cursor(a));
        assertFalse(a.get("frontier_complete").getAsBoolean()); assertTrue(b.get("frontier_complete").getAsBoolean());
        for (int i = 0; i < 2; i++) {
          var frame = List.of(a, b).get(i).getAsJsonArray("batches").get(0).getAsJsonObject();
          assertEquals("[" + i + "]", frame.get("field").getAsString());
          var sample = frame.getAsJsonObject("batch").getAsJsonArray("samples").get(0).getAsJsonObject();
          assertEquals(0, sample.get("ordinal").getAsInt());
          assertEquals(i == 0 ? 3.0 : 7.0, ReadWire.value(sample.getAsJsonObject("value")));
        }
      }
    }
  }

  @Test void anAcknowledgedFrontierReleasesItsNativePrefixBeforeAnIdleWait() throws Exception {
    try (var file = new Recording(temp.resolve("idle-release.wpilog"))) {
      file.entry("/x", "int64", ""); file.number("/x", 1, 7, false);
      var closes = new AtomicInteger(); var opens = new AtomicInteger(); var waits = new AtomicInteger();
      var source = new OrdinalFollow.Source() {
        public OrdinalFollow.Prefix open() throws IOException {
          opens.incrementAndGet();
          return new OrdinalFollow.Prefix(RECORDING, opens.get(), false, List.of(new OrdinalFollow.Member("member", 1,
              new TrackingSnapshot(file.member("member", 1).snapshot(), closes))));
        }
        public boolean awaitChange(java.time.Duration wait, java.util.function.BooleanSupplier cancelled) {
          waits.incrementAndGet(); assertEquals(opens.get(), closes.get(), "No native mapping is held during a publication wait"); return false;
        }
      };
      try (var follow = new OrdinalFollow(EPOCH, source, List.of(X), budget())) {
        var first = page(follow, follow.initialCursor());
        assertTrue(follow.next(cursor(first), java.time.Duration.ofMillis(1)).isEmpty());
        assertTrue(follow.next(cursor(first), java.time.Duration.ofMillis(1)).isEmpty());
        assertEquals(1, opens.get()); assertEquals(2, waits.get());
        assertThrows(ReadException.class, () -> follow.next("foreign", java.time.Duration.ZERO));
      }
      assertEquals(1, closes.get(), "Close after an idle acknowledgement must not dispose a prefix twice");
    }
  }

  @Test void fieldTurnsRotateAcrossAcknowledgedPagesWithoutLosingProjectionOrdinals() throws Exception {
    try (var file = new Recording(temp.resolve("field-turns.wpilog"))) {
      file.entry("/x", "double[]", ""); var entry = file.entries.get("/x");
      for (int i = 0; i < 2; i++) {
        var written = file.output.append(entry.id(), i, ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putDouble(3 + i).putDouble(7 + i).array());
        file.live.append(entry, new ValueFrame(entry.id(), i, 17, List.of(3.0 + i, 7.0 + i)), written);
      }
      try (var follow = new OrdinalFollow(EPOCH, () -> file.prefix(1), List.of(X.project("[0]"), X.project("[1]")), budget(), 1, 128 << 10)) {
        String cursor = follow.initialCursor();
        for (int i = 0; i < 4; i++) {
          var page = page(follow, cursor); assertEquals(page, page(follow, cursor));
          var frame = page.getAsJsonArray("batches").get(0).getAsJsonObject();
          assertEquals("[" + i % 2 + "]", frame.get("field").getAsString());
          var sample = frame.getAsJsonObject("batch").getAsJsonArray("samples").get(0).getAsJsonObject();
          assertEquals(i / 2, sample.get("ordinal").getAsInt());
          assertEquals((i % 2 == 0 ? 3.0 : 7.0) + i / 2, ReadWire.value(sample.getAsJsonObject("value")));
          cursor = cursor(page);
        }
      }
    }
  }

  @Test void byteDeferredRecordsYieldToQuietEntriesAndResumeWithoutSkipping() throws Exception {
    try (var file = new Recording(temp.resolve("deferred-turn.wpilog"))) {
      file.entry("/x", "string", ""); file.entry("/quiet", "string", "");
      for (int i = 0; i < 32; i++) file.text("/x", i + "s".repeat(1000));
      file.text("/quiet", "quiet"); var actual = new ArrayList<String>(); boolean quiet = false;
      try (var follow = new OrdinalFollow(EPOCH, () -> file.prefix(1), List.of(X,
          new ReadQuery("/quiet", List.of(), ReadQuery.Order.FILE, 4096, 4 << 20)), budget(), 4096, 16 << 10)) {
        String cursor = follow.initialCursor();
        for (int number = 0; number < 8; number++) {
          var page = page(follow, cursor); assertEquals(page, page(follow, cursor));
          for (var frame : page.getAsJsonArray("batches")) {
            var b = frame.getAsJsonObject(); var samples = b.getAsJsonObject("batch").getAsJsonArray("samples");
            assertTrue(b.getAsJsonObject("batch").getAsJsonArray("problems").isEmpty());
            if (b.get("entry").getAsString().equals("/quiet")) { assertFalse(quiet); quiet = true; }
            else for (var sample : samples) {
              var value = sample.getAsJsonObject(); assertEquals(actual.size(), value.get("ordinal").getAsInt());
              actual.add(value.getAsJsonObject("value").get("value").getAsString());
            }
          }
          if (number == 1) assertTrue(quiet, "A byte-deferred turn must yield the next page to the quiet entry");
          cursor = cursor(page); if (page.get("frontier_complete").getAsBoolean()) break;
        }
      }
      assertTrue(quiet); assertEquals(32, actual.size());
      for (int i = 0; i < 32; i++) assertEquals(i + "s".repeat(1000), actual.get(i));
    }
  }

  @ParameterizedTest @ValueSource(ints = {1, 2})
  void byteDeferredLargeRecordsOpenTheNextPageBeforeTheChattyBacklogDrains(int largeEntries) throws Exception {
    var pages = largeRecordPages(largeEntries);
    var first = pages.get(1).getAsJsonArray("batches").get(0).getAsJsonObject();
    assertEquals("/large/0", first.get("entry").getAsString(),
        "The first deferred selection must open the next page, even if later selections also defer");
    assertEquals(0, first.getAsJsonObject("batch").getAsJsonArray("samples").get(0)
        .getAsJsonObject().get("ordinal").getAsInt());
  }

  @Test void byteDeferralKeepsPackingTheOtherSelections() throws Exception {
    var pages = largeRecordPages(1);
    assertTrue(entryRecords(pages.get(1), "/large/0") == 1 && entryRecords(pages.get(1), "/x") > 0,
        "Deferring a second large record must leave the remaining space available to the chatty entry");
  }

  @Test void byteDeferredPrioritySurvivesOtherSelectionsReachingTheirPageLimit() throws Exception {
    var pages = largeRecordPages(1, 256);
    assertEquals("/large/0", pages.get(1).getAsJsonArray("batches").get(0).getAsJsonObject().get("entry").getAsString(),
        "The first deferral keeps priority even when a query cap ends packing before its turn comes around again");
  }

  /** Small transport pages must make progress on both large and small values. The values and
   * timestamps below have independent, exact expectations; retries must not read them again. */
  private List<JsonObject> largeRecordPages(int largeEntries) throws Exception {
    return largeRecordPages(largeEntries, 4096);
  }

  private List<JsonObject> largeRecordPages(int largeEntries, int chattyLimit) throws Exception {
    try (var file = new Recording(temp.resolve("large-deferral-" + largeEntries + ".wpilog"))) {
      file.entry("/x", "int64", "");
      for (int i = 0; i < 5000; i++) file.number("/x", i, 3L * i + 7, false);
      var queries = new ArrayList<ReadQuery>();
      queries.add(new ReadQuery("/x", List.of(), ReadQuery.Order.FILE, chattyLimit, 4 << 20));
      for (int entryNumber = 0; entryNumber < largeEntries; entryNumber++) {
        String name = "/large/" + entryNumber; file.entry(name, "raw", "");
        queries.add(new ReadQuery(name, List.of(), ReadQuery.Order.FILE, 4096, 4 << 20));
        var entry = file.entries.get(name);
        for (int i = 0; i < 10; i++) {
          byte[] payload = new byte[40000]; java.util.Arrays.fill(payload, (byte) (entryNumber * 16 + i + 1));
          long time = (i + 1) * 1000L;
          var written = file.output.append(entry.id(), time, payload);
          file.live.append(entry, new ValueFrame(entry.id(), time, 5, payload), written); file.live.expire();
        }
      }
      var reads = new HashMap<String, Integer>();
      var source = (OrdinalFollow.Source) () -> new OrdinalFollow.Prefix(RECORDING, 1, false, List.of(
          new OrdinalFollow.Member("member", 1, new TrackingSnapshot(file.member("member", 1).snapshot(), new AtomicInteger()) {
            @Override public SampleCursor read(ReadQuery query) throws IOException {
              var delegate = super.read(query);
              return new SampleCursor() {
                @Override public SampleBatch next() throws IOException {
                  var result = delegate.next(); reads.merge(query.entry(), result.recordsVisited(), Integer::sum); return result;
                }
                @Override public void close() throws IOException { delegate.close(); }
              };
            }
          })));
      var pages = new ArrayList<JsonObject>(); var ordinals = new HashMap<String, Integer>();
      try (var follow = new OrdinalFollow(EPOCH, source, queries, budget(), 4096, 64 << 10)) {
        String cursor = follow.initialCursor();
        while (pages.size() < 64) {
          reads.clear(); byte[] encoded;
          try (var response = follow.next(cursor)) { encoded = bytes(response); }
          var readCounts = Map.copyOf(reads);
          for (int retry = 0; retry < 2; retry++) {
            try (var response = follow.next(cursor)) { assertArrayEquals(encoded, bytes(response)); }
            assertEquals(readCounts, reads, "A retained page retry must not read or advance the schedule");
          }
          assertTrue(encoded.length <= 64 << 10);
          assertTrue(reads.values().stream().mapToInt(Integer::intValue).sum() <= 4096);
          var page = ReadWire.parse(encoded); pages.add(page);
          for (var query : queries) assertTrue(reads.getOrDefault(query.entry(), 0) <= entryRecords(page, query.entry()) + 1,
              "A deferred selection must not be read again on this page: " + query.entry());
          for (var frame : page.getAsJsonArray("batches")) {
            var item = frame.getAsJsonObject(); String name = item.get("entry").getAsString();
            var batch = item.getAsJsonObject("batch"); assertTrue(batch.getAsJsonArray("problems").isEmpty());
            assertTrue(batch.get("records_visited").getAsInt() <= 256);
            for (var value : batch.getAsJsonArray("samples")) {
              var sample = value.getAsJsonObject(); int ordinal = ordinals.getOrDefault(name, 0);
              assertEquals(ordinal, sample.get("ordinal").getAsInt());
              var wire = sample.getAsJsonObject("value");
              if (name.equals("/x")) {
                assertEquals("i64", wire.get("type").getAsString());
                assertEquals(3L * ordinal + 7, wire.get("value").getAsLong());
                assertEquals(ordinal, sample.get("timestamp_us").getAsLong());
              } else {
                assertEquals("bytes", wire.get("type").getAsString());
                byte[] expected = new byte[40000];
                java.util.Arrays.fill(expected, (byte) (Integer.parseInt(name.substring("/large/".length())) * 16 + ordinal + 1));
                assertArrayEquals(expected, java.util.Base64.getDecoder().decode(wire.get("value").getAsString()));
                assertEquals((ordinal + 1) * 1000L, sample.get("timestamp_us").getAsLong());
              }
              ordinals.put(name, ordinal + 1);
            }
          }
          cursor = cursor(page); if (page.get("frontier_complete").getAsBoolean()) break;
        }
      }
      assertTrue(pages.get(pages.size() - 1).get("frontier_complete").getAsBoolean());
      assertEquals(5000, ordinals.get("/x"));
      for (int i = 0; i < largeEntries; i++) assertEquals(10, ordinals.get("/large/" + i));
      int firstLargePage = java.util.stream.IntStream.range(0, pages.size())
          .filter(i -> entryRecords(pages.get(i), "/large/0") != 0).findFirst().orElseThrow() + 1;
      System.out.printf("Deferral fixture: largeEntries=%d chattyLimit=%d pages=%d firstLargePage=%d firstChattyRecords=%d%n",
          largeEntries, chattyLimit, pages.size(), firstLargePage, entryRecords(pages.get(0), "/x"));
      return pages;
    }
  }

  private static int entryRecords(JsonObject page, String entry) {
    return page.getAsJsonArray("batches").asList().stream().map(com.google.gson.JsonElement::getAsJsonObject)
        .filter(frame -> frame.get("entry").getAsString().equals(entry))
        .mapToInt(frame -> frame.getAsJsonObject("batch").get("records_visited").getAsInt()).sum();
  }

  @Test void byteBoundariesDeferFittingRecordsAndChargeLookaheadAsWork() throws Exception {
    try (var file = new Recording(temp.resolve("byte-boundary.wpilog"))) {
      file.entry("/x", "string", "");
      for (int i = 0; i < 32; i++) file.text("/x", i + "s".repeat(1000));
      var visited = new AtomicInteger(); var actual = new ArrayList<String>();
      try (var follow = new OrdinalFollow(EPOCH, () -> new OrdinalFollow.Prefix(RECORDING, 1, false, List.of(
          new OrdinalFollow.Member("member", 1, new TrackingSnapshot(file.member("member", 1).snapshot(), new AtomicInteger()) {
            @Override public SampleCursor read(ReadQuery query) throws IOException {
              var delegate = super.read(query);
              return new SampleCursor() {
                @Override public SampleBatch next() throws IOException {
                  var result = delegate.next(); visited.addAndGet(result.recordsVisited()); return result;
                }
                @Override public void close() throws IOException { delegate.close(); }
              };
            }
          }))), List.of(X), budget(), 16, 16 << 10)) {
        String cursor = follow.initialCursor(); int pages = 0;
        do {
          visited.set(0); var result = page(follow, cursor); cursor = cursor(result); pages++;
          assertTrue(visited.get() <= 16, "Deferred lookahead still counts against the global work bound");
          assertTrue(ReadWire.bytes(result, 16 << 10).length <= 16 << 10);
          for (var frame : result.getAsJsonArray("batches")) {
            var body = frame.getAsJsonObject().getAsJsonObject("batch"); assertTrue(body.getAsJsonArray("problems").isEmpty());
            for (var sample : body.getAsJsonArray("samples")) {
              var s = sample.getAsJsonObject(); assertEquals(actual.size(), s.get("ordinal").getAsInt());
              actual.add(s.getAsJsonObject("value").get("value").getAsString());
            }
          }
          if (result.get("frontier_complete").getAsBoolean()) break;
          assertTrue(pages < 6, "Small records must pack across pages without repeatedly sending the frontier");
        } while (true);
        assertEquals(32, actual.size());
        for (int i = 0; i < 32; i++) assertEquals(i + "s".repeat(1000), actual.get(i));
      }
    }
  }

  @Test void admissionIncludesTheWholeEnvelopeAndExplainsTheRefusal() throws Exception {
    try (var file = new Recording(temp.resolve("envelope.wpilog"))) {
      file.entry("/x", "int64", ""); int descriptionBytes;
      String epoch = "e".repeat(256); var closes = new AtomicInteger();
      try (var probe = new OrdinalFollow(epoch, () -> file.prefix(1), List.of(X), budget())) {
        descriptionBytes = ReadWire.bytes(page(probe, probe.initialCursor()).getAsJsonObject("frontier"), 4 << 20).length;
      }
      var failure = assertThrows(ReadException.class, () -> new OrdinalFollow(epoch,
          () -> new OrdinalFollow.Prefix(RECORDING, 1, false, List.of(new OrdinalFollow.Member("member", 1,
              new TrackingSnapshot(file.member("member", 1).snapshot(), closes)))),
          List.of(X), budget(), 4096, descriptionBytes + 530));
      assertEquals(ReadProblem.Code.RESOURCE_LIMIT, failure.code());
      assertTrue(failure.getMessage().contains("Follow frontier metadata"));
      assertFalse(failure.getMessage().contains("use Arrow")); assertEquals(1, closes.get());
    }
  }

  @Test void manyEntriesAndMembersShareOneGloballyBoundedPage() throws Exception {
    try (var a = new Recording(temp.resolve("packed-a.wpilog")); var b = new Recording(temp.resolve("packed-b.wpilog"))) {
      var queries = new ArrayList<ReadQuery>();
      for (int i = 0; i < 32; i++) {
        String name = "/value/" + i; queries.add(new ReadQuery(name, List.of(), ReadQuery.Order.FILE, 4096, 4 << 20));
        for (var file : List.of(a, b)) { file.entry(name, "int64", ""); file.number(name, 1, i * 3L + 7, false); }
      }
      try (var follow = new OrdinalFollow(EPOCH, () -> new OrdinalFollow.Prefix(RECORDING, 1, false,
          List.of(a.member("a", 1), b.member("b", 1))), queries, budget())) {
        var first = page(follow, follow.initialCursor());
        assertEquals(64, first.getAsJsonArray("batches").size()); assertTrue(first.get("frontier_complete").getAsBoolean());
        for (var item : first.getAsJsonArray("batches")) {
          var frame = item.getAsJsonObject(); int index = Integer.parseInt(frame.get("entry").getAsString().substring(7));
          assertEquals(index * 3L + 7, frame.getAsJsonObject("batch").getAsJsonArray("samples").get(0)
              .getAsJsonObject().getAsJsonObject("value").get("value").getAsLong());
        }
      }
      try (var follow = new OrdinalFollow(EPOCH, () -> a.prefix(1), queries, budget(), 3, 128 << 10)) {
        String cursor = follow.initialCursor(); int count = 0, pages = 0;
        do {
          var result = page(follow, cursor); cursor = cursor(result); pages++;
          int records = result.getAsJsonArray("batches").size(); assertTrue(records <= 3); count += records;
          if (result.get("frontier_complete").getAsBoolean()) break;
          assertTrue(pages < 20);
        } while (true);
        assertEquals(32, count); assertEquals(11, pages);
      }
    }
  }

  @Test void aFrontierIsSentOncePerPrefixAndOnlyAcknowledgementRetiresIt() throws Exception {
    try (var file = new Recording(temp.resolve("frontier-once.wpilog"))) {
      file.entry("/x", "int64", ""); file.number("/x", 1, 1, false); file.number("/x", 2, 2, false);
      try (var follow = new OrdinalFollow(EPOCH, () -> file.prefix(1), List.of(X), budget(), 1, 128 << 10)) {
        byte[] original;
        try (var response = follow.next(follow.initialCursor())) { original = bytes(response); }
        try (var retry = follow.next(follow.initialCursor())) { assertArrayEquals(original, bytes(retry)); }
        var first = ReadWire.parse(original); assertTrue(first.has("frontier"));
        var second = page(follow, cursor(first)); assertFalse(second.has("frontier"));
        assertEquals(first.get("prefix_id"), second.get("prefix_id"));
        var third = page(follow, cursor(second)); assertTrue(third.has("frontier"));
        assertNotEquals(first.get("prefix_id"), third.get("prefix_id"));
      }
    }
  }

  @ParameterizedTest @ValueSource(ints = {1048576, 32768})
  void oversizedPayloadOrWireExpansionIsAnExplicitGapAndOtherEntriesContinue(int payloadBytes) throws Exception {
    var shared = budget();
    try (var file = new Recording(temp.resolve("oversize-" + payloadBytes + ".wpilog"))) {
      file.entry("/x", "string", ""); file.text("/x", "s".repeat(payloadBytes)); file.text("/x", "after");
      file.entry("/y", "int64", ""); file.number("/y", 2, 42, false);
      try (var follow = new OrdinalFollow(EPOCH, () -> file.prefix(1), List.of(X,
          new ReadQuery("/y", List.of(), ReadQuery.Order.FILE, 4096, 4 << 20)), shared, 4096, 128 << 10)) {
        var first = page(follow, follow.initialCursor());
        assertEquals(first, page(follow, follow.initialCursor()));
        var x = first.getAsJsonArray("batches").get(0).getAsJsonObject();
        assertEquals(2, x.get("next_ordinal").getAsInt());
        var body = x.getAsJsonObject("batch"); var problem = body.getAsJsonArray("problems").get(0).getAsJsonObject();
        assertEquals("RESOURCE_LIMIT", problem.get("code").getAsString());
        assertEquals(0, problem.get("ordinal").getAsInt()); assertEquals(payloadBytes, problem.get("payload_bytes").getAsInt());
        assertEquals(oracle(file.path).get(0).offset(), problem.get("record_offset").getAsLong());
        assertEquals("after", body.getAsJsonArray("samples").get(0).getAsJsonObject().getAsJsonObject("value").get("value").getAsString());
        assertEquals(2, first.getAsJsonArray("batches").size()); assertTrue(first.get("frontier_complete").getAsBoolean());
      }
      assertEquals(0, shared.heldBytes());
    }
  }

  @Test void metadataCanFillItsOwnPageWithoutTurningAFittingRecordIntoAGap() throws Exception {
    try (var file = new Recording(temp.resolve("metadata-page.wpilog"))) {
      file.entry("/x", "string", "m".repeat(30 << 10)); file.text("/x", "s".repeat(4000));
      // Size the page from the actual descriptor: platform paths and file keys vary.
      int descriptionBytes;
      try (var probe = new OrdinalFollow(EPOCH, () -> file.prefix(1), List.of(X), budget())) {
        descriptionBytes = ReadWire.bytes(page(probe, probe.initialCursor()).getAsJsonObject("frontier"), 4 << 20).length;
      }
      // Leave room for a small problem but not this valid value: classifying against the
      // remaining space would wrongly replace it with a gap instead of deferring it.
      int limit = descriptionBytes + 1400;
      try (var follow = new OrdinalFollow(EPOCH, () -> file.prefix(1), List.of(X), budget(), 4096, limit)) {
        var first = page(follow, follow.initialCursor());
        assertTrue(first.has("frontier")); assertTrue(first.getAsJsonArray("batches").isEmpty());
        assertFalse(first.get("frontier_complete").getAsBoolean());
        assertTrue(ReadWire.bytes(first, limit).length <= limit);
        var second = page(follow, cursor(first)); assertFalse(second.has("frontier"));
        var batch = second.getAsJsonArray("batches").get(0).getAsJsonObject().getAsJsonObject("batch");
        assertTrue(batch.getAsJsonArray("problems").isEmpty());
        assertEquals("s".repeat(4000), batch.getAsJsonArray("samples").get(0).getAsJsonObject().getAsJsonObject("value").get("value").getAsString());
        assertTrue(second.get("frontier_complete").getAsBoolean());
      }
    }
  }

  @Test void bootstrapAndDeltasMatchAnIndependentReaderAcrossEqualBackwardAndFilteredRecords() throws Exception {
    try (var file = new Recording(temp.resolve("oracle.wpilog"))) {
      file.entry("/x", "int64", "old"); file.entry("/y", "int64", ""); file.entry("/unselected", "int64", "");
      for (int i = 0; i < 4100; i++) {
        long timestamp = i % 5 == 0 ? 9 : i % 5 == 1 ? 20 : 10;
        file.number("/x", timestamp, 3L * i + 7, i == 17);
      }
      file.number("/y", 1, 99, false); file.number("/unselected", 1, 123, false);
      var historical = new ReadQuery("/x", List.of(new ReadQuery.Window(10, 20, true)), ReadQuery.Order.FILE, 4096, 4 << 20);
      var actual = new ArrayList<Datum>(); var problems = new ArrayList<Long>();
      try (var follow = new OrdinalFollow(EPOCH, () -> file.prefix(1), List.of(historical,
          new ReadQuery("/y", List.of(), ReadQuery.Order.FILE, 4096, 4 << 20)), budget())) {
        String cursor = follow.initialCursor(); var first = page(follow, cursor); cursor = cursor(first);
        collect(first, actual, problems);
        assertTrue(first.get("bootstrap").getAsBoolean());
        assertFalse(first.get("frontier_complete").getAsBoolean());
        assertEquals(4096, first.getAsJsonArray("batches").asList().stream().mapToInt(b ->
            b.getAsJsonObject().getAsJsonObject("batch").get("records_visited").getAsInt()).sum());
        assertTrue(actual.stream().anyMatch(d -> d.entry.equals("/y")), "The sparse entry is served on the first page");
        String prefix = first.get("prefix_id").getAsString();
        var counts = member(first, 0).getAsJsonObject("snapshot").getAsJsonObject("sample_counts");
        assertEquals(4100, counts.get("/x").getAsInt()); assertEquals(1, counts.get("/unselected").getAsInt());
        // Append while bootstrap is still paging. Include late samples outside the history window.
        for (int i = 4100; i < 4111; i++) file.number("/x", i % 3 == 0 ? 5 : 20, 3L * i + 7, i == 4104);
        file.number("/y", 0, 100, false);
        var second = page(follow, cursor); cursor = cursor(second); collect(second, actual, problems);
        assertEquals(prefix, second.get("prefix_id").getAsString());
        assertTrue(second.getAsJsonArray("batches").get(0).getAsJsonObject().getAsJsonObject("batch").get("complete").getAsBoolean());
        assertEquals(1, second.getAsJsonArray("batches").size());
        assertTrue(second.get("frontier_complete").getAsBoolean());
        assertFalse(second.has("frontier"));
        var delta = page(follow, cursor); cursor = cursor(delta); collect(delta, actual, problems);
        assertFalse(delta.get("bootstrap").getAsBoolean()); assertNotEquals(prefix, delta.get("prefix_id").getAsString());
        assertEquals(2, delta.getAsJsonArray("batches").size()); assertTrue(delta.get("frontier_complete").getAsBoolean());
        var expected = oracle(file.path).stream().filter(d -> d.entry.equals("/y") || d.entry.equals("/x")
            && (d.ordinal >= 4100 || d.time >= 10 && d.time <= 20)).filter(d -> d.value != null).toList();
        assertEquals(expected.stream().sorted().toList(), actual.stream().sorted().toList());
        assertEquals(oracle(file.path).stream().filter(d -> d.value == null).map(d -> d.offset).sorted().toList(), problems.stream().sorted().toList());
        var empty = page(follow, cursor);
        assertTrue(empty.getAsJsonArray("batches").isEmpty()); assertTrue(empty.get("frontier_complete").getAsBoolean());
      }
    }
  }

  @Test void anInterruptedPageRetriesIdenticalBytesAndAcknowledgesOnlyTheTerminalCursor() throws Exception {
    try (var file = new Recording(temp.resolve("retry.wpilog"))) {
      file.entry("/x", "int64", ""); file.number("/x", 10, 1, false); file.number("/x", 10, 2, false);
      try (var follow = new OrdinalFollow(EPOCH, () -> file.prefix(1), List.of(X), budget(), 1, 128 << 10)) {
        String input = follow.initialCursor(); byte[] expected;
        try (var response = follow.next(input)) {
          expected = bytes(response); assertThrows(ReadOnlyBufferException.class, () -> response.payload().put((byte) 0));
          assertThrows(IOException.class, () -> response.writeTo(new OutputStream() {
            int written;
            @Override public void write(int b) throws IOException { if (++written == 17) throw new IOException("Connection lost"); }
          }));
        }
        file.number("/x", 9, 3, false);
        try (var retry = follow.next(input)) { assertArrayEquals(expected, bytes(retry)); }
        String next = cursor(ReadWire.parse(expected));
        var second = page(follow, next); assertEquals(1, ordinal(second));
        assertEquals(ReadProblem.Code.INVALID_REQUEST, assertThrows(ReadException.class, () -> follow.next(input)).code());
        var third = page(follow, cursor(second)); assertEquals(2, ordinal(third));
        assertEquals(9, third.getAsJsonArray("batches").get(0).getAsJsonObject().getAsJsonObject("batch")
            .getAsJsonArray("samples").get(0).getAsJsonObject().get("timestamp_us").getAsLong());
      }
    }
  }

  @Test void metadataOnlyAndNewEmptyEntriesPublishWithoutAnyDataSample() throws Exception {
    try (var file = new Recording(temp.resolve("metadata.wpilog"))) {
      file.entry("/x", "int64", "original"); var revision = new AtomicInteger(1);
      try (var follow = new OrdinalFollow(EPOCH, () -> file.prefix(revision.get()), List.of(X), budget())) {
        var before = page(follow, follow.initialCursor());
        file.metadata("/x", "changed"); file.entry("/late", "double", "new entry"); revision.incrementAndGet();
        var after = page(follow, cursor(before));
        assertTrue(after.getAsJsonArray("batches").isEmpty()); assertTrue(after.get("frontier_complete").getAsBoolean());
        assertEquals(2, after.getAsJsonObject("frontier").get("catalog_revision").getAsInt());
        var entries = member(after, 0).getAsJsonObject("snapshot").getAsJsonObject("entries");
        assertEquals("changed", entries.getAsJsonObject("/x").get("metadata").getAsString());
        assertEquals("double", entries.getAsJsonObject("/late").get("type").getAsString());
        assertEquals("original", member(before, 0).getAsJsonObject("snapshot").getAsJsonObject("entries")
            .getAsJsonObject("/x").get("metadata").getAsString());
      }
    }
  }

  @Test void filteredAndUndecodableRecordsStillMakeProgressInEmptyPages() throws Exception {
    try (var file = new Recording(temp.resolve("empty-pages.wpilog"))) {
      file.entry("/x", "int64", ""); file.number("/x", 1, 1, false); file.number("/x", 10, 2, true); file.number("/x", 10, 3, false);
      var query = new ReadQuery("/x", List.of(new ReadQuery.Window(10, 10, true)), ReadQuery.Order.FILE, 1, 4096);
      try (var follow = new OrdinalFollow(EPOCH, () -> file.prefix(1), List.of(query), budget())) {
        var first = page(follow, follow.initialCursor()); var b1 = first.getAsJsonArray("batches").get(0).getAsJsonObject();
        assertEquals(1, b1.get("next_ordinal").getAsInt()); assertTrue(b1.getAsJsonObject("batch").getAsJsonArray("samples").isEmpty());
        var second = page(follow, cursor(first)); var b2 = second.getAsJsonArray("batches").get(0).getAsJsonObject();
        assertEquals(2, b2.get("next_ordinal").getAsInt()); assertEquals(1, b2.getAsJsonObject("batch").getAsJsonArray("problems").size());
        var third = page(follow, cursor(second)); assertEquals(2, ordinal(third)); assertTrue(third.get("frontier_complete").getAsBoolean());
      }
    }
  }

  @Test void lateSchemaAndSelectedEntryAreVisibleWithoutRereadingTheOldEntry() throws Exception {
    try (var file = new Recording(temp.resolve("late-schema.wpilog"))) {
      file.entry("/x", "int64", ""); file.number("/x", 10, 1, false);
      var late = new ReadQuery("/late", List.of(), ReadQuery.Order.FILE, 4096, 4 << 20);
      try (var follow = new OrdinalFollow(EPOCH, () -> file.prefix(1), List.of(X, late), budget())) {
        var first = page(follow, follow.initialCursor()); assertTrue(first.get("frontier_complete").getAsBoolean());
        file.entry("/.schema/struct:FollowPose", "structschema", ""); file.text("/.schema/struct:FollowPose", "double x;");
        file.entry("/late", "int64", "new"); file.number("/late", 9, 77, false);
        var after = page(follow, cursor(first));
        assertEquals(1, after.getAsJsonArray("batches").size()); assertEquals("/late", after.getAsJsonArray("batches").get(0).getAsJsonObject().get("entry").getAsString());
        assertEquals(0, ordinal(after)); assertTrue(after.get("frontier_complete").getAsBoolean());
        var schemas = member(after, 0).getAsJsonObject("snapshot").getAsJsonArray("schemas");
        var schema = java.util.stream.StreamSupport.stream(schemas.spliterator(), false).map(e -> e.getAsJsonObject())
            .filter(s -> s.get("name").getAsString().equals("FollowPose")).findFirst().orElseThrow();
        assertEquals("double x;", schema.get("schema").getAsString());
        assertEquals(1, member(after, 0).getAsJsonObject("snapshot").getAsJsonObject("sample_counts").get("/.schema/struct:FollowPose").getAsInt());
      }
    }
  }

  @Test void cursorsBelongToOneLeaseAndEpochAndNeverSilentlyRebootstrap() throws Exception {
    try (var file = new Recording(temp.resolve("cursor-scope.wpilog"))) {
      file.entry("/x", "int64", ""); file.number("/x", 10, 1, false);
      try (var a = new OrdinalFollow("epoch-a", () -> file.prefix(1), List.of(X), budget());
           var b = new OrdinalFollow("epoch-b", () -> file.prefix(1), List.of(X), budget())) {
        var first = page(a, a.initialCursor());
        assertEquals(ReadProblem.Code.INVALID_REQUEST, assertThrows(ReadException.class, () -> b.next(a.initialCursor())).code());
        assertEquals(ReadProblem.Code.INVALID_REQUEST, assertThrows(ReadException.class, () -> b.next(cursor(first))).code());
        var own = page(b, b.initialCursor()); assertEquals(0, ordinal(own)); assertEquals("epoch-b", own.get("server_epoch").getAsString());
      }
    }
  }

  @Test void frontierMetadataIsBoundedAndFailedAdmissionClosesEverySnapshot() throws Exception {
    try (var file = new Recording(temp.resolve("metadata-budget.wpilog"))) {
      file.entry("/x", "int64", "m".repeat(32 << 10)); var closes = new AtomicInteger();
      assertEquals(ReadProblem.Code.RESOURCE_LIMIT, assertThrows(ReadException.class, () -> new OrdinalFollow(EPOCH, () -> {
        var members = new ArrayList<OrdinalFollow.Member>();
        for (int i = 0; i < 8; i++) members.add(new OrdinalFollow.Member("member-" + i, 1,
            new TrackingSnapshot(file.manager.readAccess().open(file.path), closes)));
        return new OrdinalFollow.Prefix(RECORDING, 1, false, members);
      }, List.of(X), budget(), 4096, 128 << 10)).code());
      assertEquals(8, closes.get());
    }
  }

  @Test void newMembersStartAtZeroAndFinalizationDrainsWithoutFollowingTheNextRecording() throws Exception {
    try (var a = new Recording(temp.resolve("a.wpilog")); var b = new Recording(temp.resolve("b.wpilog"))) {
      a.entry("/x", "int64", ""); b.entry("/x", "int64", ""); a.number("/x", 10, 1, false); b.number("/x", 10, 2, false);
      var stage = new AtomicInteger(); var opens = new AtomicInteger();
      try (var follow = new OrdinalFollow(EPOCH, () -> {
        opens.incrementAndGet(); return new OrdinalFollow.Prefix(RECORDING, stage.get(), stage.get() == 2,
            stage.get() == 0 ? List.of(a.member("a", 1)) : List.of(b.member("b", 1), a.member("a", 1)));
      }, List.of(X), budget())) {
        var first = page(follow, follow.initialCursor()); stage.set(1);
        var rollover = page(follow, cursor(first)); assertEquals("b", rollover.getAsJsonArray("batches").get(0).getAsJsonObject().get("member_id").getAsString());
        assertEquals(0, ordinal(rollover)); assertTrue(rollover.get("frontier_complete").getAsBoolean());
        b.number("/x", 9, 3, false); a.finish(); b.finish(); stage.set(2);
        var last = page(follow, cursor(rollover)); assertEquals(1, ordinal(last));
        assertTrue(last.getAsJsonObject("frontier").get("finalized").getAsBoolean());
        assertTrue(last.get("frontier_complete").getAsBoolean()); int before = opens.get();
        stage.set(3);
        assertEquals("FOLLOW_ENDED", assertThrows(ReadException.class, () -> follow.next(cursor(last))).code().name());
        assertEquals(before, opens.get(), "A finalized follow cannot attach itself to a later boot");
      }
    }
  }

  @ParameterizedTest @ValueSource(ints = {1, 257, 4096})
  void quietEntriesReceiveATurnBeforeAChattyEntryCanDrain(int pageSize) throws Exception {
    try (var a = new Recording(temp.resolve("fair-a.wpilog")); var b = new Recording(temp.resolve("fair-b.wpilog"))) {
      a.entry("/x", "int64", ""); a.entry("/quiet", "int64", ""); b.entry("/x", "int64", "");
      for (int i = 0; i < 600; i++) a.number("/x", i, i, i == 255);
      a.number("/quiet", 0, 71, false); b.number("/x", 0, 72, false);
      var quiet = new ReadQuery("/quiet", List.of(), ReadQuery.Order.FILE, 4096, 4 << 20);
      try (var follow = new OrdinalFollow(EPOCH, () -> new OrdinalFollow.Prefix(RECORDING, 1, false,
          List.of(a.member("a", 1), b.member("b", 1))), List.of(X, quiet), budget(), pageSize, 128 << 10)) {
        String cursor = follow.initialCursor(); var order = new ArrayList<String>(); int visits = 0;
        while (order.size() < 3) {
          var page = page(follow, cursor); assertEquals(page, page(follow, cursor), "Retries cannot rotate the schedule");
          for (var element : page.getAsJsonArray("batches")) {
            var frame = element.getAsJsonObject(); var batch = frame.getAsJsonObject("batch");
            String key = frame.get("member_id").getAsString() + frame.get("entry").getAsString();
            if (!order.contains(key)) order.add(key);
            if (key.equals("a/x") && !order.contains("b/x")) {
              visits += batch.get("records_visited").getAsInt();
              assertTrue(visits <= 256, "A quiet member must get a turn within 256 chatty records, including malformed ones");
            }
          }
          cursor = cursor(page);
        }
        assertEquals(List.of("a/x", "a/quiet", "b/x"), order);
      }
    }
  }

  @ParameterizedTest @ValueSource(strings = {"recording", "generation", "member", "count", "catalog", "type"})
  void aChangedIdentityOrLostHistoryRequiresExplicitRebootstrap(String change) throws Exception {
    try (var a = new Recording(temp.resolve("old.wpilog")); var b = new Recording(temp.resolve("new.wpilog"))) {
      a.entry("/x", "int64", ""); a.number("/x", 10, 1, false); a.number("/x", 10, 2, false);
      b.entry("/x", change.equals("type") ? "double" : "int64", "");
      if (change.equals("type")) { b.number("/x", 10, 1, false); b.number("/x", 10, 2, false); }
      var stage = new AtomicInteger(); var closed = new AtomicInteger();
      try (var follow = new OrdinalFollow(EPOCH, () -> {
        if (stage.getAndIncrement() == 0) return a.prefix(2);
        var member = change.equals("count") || change.equals("type") ? b.member("member", 1) :
            a.member(change.equals("member") ? "other" : "member", change.equals("generation") ? 2 : 1);
        return new OrdinalFollow.Prefix(change.equals("recording") ? "another-boot" : RECORDING,
            change.equals("catalog") ? 1 : 2, false, List.of(new OrdinalFollow.Member(member.id(), member.generation(),
                new TrackingSnapshot(member.snapshot(), closed))));
      }, List.of(X), budget())) {
        var first = page(follow, follow.initialCursor());
        var failure = assertThrows(ReadException.class, () -> follow.next(cursor(first)));
        assertEquals(ReadProblem.Code.FILE_CHANGED, failure.code()); assertTrue(failure.getMessage().contains("rebootstrap the same recording"));
        assertEquals(1, closed.get(), "A rejected candidate releases its independently owned snapshots");
      }
    }
  }

  @Test void anOpenFailureAfterAnAcknowledgementCanRetryWithoutSkippingTheNewPrefix() throws Exception {
    try (var file = new Recording(temp.resolve("open-retry.wpilog"))) {
      file.entry("/x", "int64", ""); file.number("/x", 10, 1, false); var opens = new AtomicInteger();
      try (var follow = new OrdinalFollow(EPOCH, () -> {
        if (opens.incrementAndGet() == 2) throw new IOException("Catalog temporarily unavailable");
        return file.prefix(1);
      }, List.of(X), budget())) {
        var first = page(follow, follow.initialCursor()); file.number("/x", 5, 2, false);
        String cursor = cursor(first);
        assertEquals("Catalog temporarily unavailable", assertThrows(IOException.class, () -> follow.next(cursor)).getMessage());
        assertEquals(1, ordinal(page(follow, cursor)));
      }
    }
  }

  @Test void responseReferencesKeepTheSharedBudgetUntilTheLastClose() throws Exception {
    var shared = new OrdinalFollow.Budget(256 << 10);
    try (var file = new Recording(temp.resolve("budget.wpilog"))) {
      file.entry("/x", "int64", ""); file.number("/x", 10, 1, false);
      try (var first = new OrdinalFollow(EPOCH, () -> file.prefix(1), List.of(X), shared, 1, 128 << 10);
           var second = new OrdinalFollow(EPOCH, () -> file.prefix(1), List.of(X), shared, 1, 128 << 10);
           var held = first.next(first.initialCursor())) {
        long bytes = held.payload().remaining(); assertEquals(bytes, shared.heldBytes()); first.close();
        assertEquals(bytes, shared.heldBytes(), "Closing the cache cannot release an admitted response");
        assertEquals(ReadProblem.Code.RESOURCE_LIMIT, assertThrows(ReadException.class, () -> second.next(second.initialCursor())).code());
        held.close(); held.close(); assertEquals(0, shared.heldBytes());
        assertEquals(0, ordinal(page(second, second.initialCursor())));
        second.close(); assertEquals(0, shared.heldBytes());
      }
    }
  }

  @Test void anOversizedRecordAdvancesOnlyAfterAcknowledgementWithoutALeakedBudget() throws Exception {
    var shared = budget();
    try (var file = new Recording(temp.resolve("large-record.wpilog"))) {
      file.entry("/x", "string", ""); file.text("/x", "s".repeat(1 << 20)); file.text("/x", "next");
      try (var follow = new OrdinalFollow(EPOCH, () -> file.prefix(1), List.of(X), shared, 1, 128 << 10)) {
        var first = page(follow, follow.initialCursor());
        assertEquals(first, page(follow, follow.initialCursor()));
        assertFalse(first.get("frontier_complete").getAsBoolean());
        assertEquals(1, first.getAsJsonArray("batches").get(0).getAsJsonObject().get("next_ordinal").getAsInt());
        var next = page(follow, cursor(first)); assertEquals(1, ordinal(next));
        assertTrue(next.get("frontier_complete").getAsBoolean());
      }
      assertEquals(0, shared.heldBytes());
    }
  }

  @Test void closeDuringABatchRetiresAdmissionWithoutWaitingOrStoppingAnotherReaderOrWriter() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var closes = new AtomicInteger(); var shared = budget();
    try (var file = new Recording(temp.resolve("close.wpilog"))) {
      file.entry("/x", "int64", ""); file.number("/x", 10, 1, false);
      try (var follow = new OrdinalFollow(EPOCH, () -> {
        var member = file.member("member", 1);
        var snapshot = new TrackingSnapshot(member.snapshot(), closes) {
          @Override public SampleCursor read(ReadQuery query) throws IOException {
            var delegate = super.read(query);
            return new SampleCursor() {
              @Override public SampleBatch next() throws IOException {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                return delegate.next();
              }
              @Override public void close() throws IOException { delegate.close(); }
            };
          }
        };
        return new OrdinalFollow.Prefix(RECORDING, 1, false, List.of(new OrdinalFollow.Member("member", 1, snapshot)));
      }, List.of(X), shared);
           var other = new OrdinalFollow(EPOCH, () -> file.prefix(1), List.of(X), shared)) {
        var result = CompletableFuture.supplyAsync(() -> {
          try (var ignored = follow.next(follow.initialCursor())) { return null; }
          catch (IOException e) { return e; }
        });
        try {
          assertTrue(entered.await(5, TimeUnit.SECONDS));
          assertEquals(ReadProblem.Code.BUSY, assertThrows(ReadException.class, () -> follow.next(follow.initialCursor())).code());
          follow.close(); assertEquals(0, closes.get(), "Close does not wait for the held batch");
          var first = page(other, other.initialCursor()); file.number("/x", 9, 2, false);
          assertEquals(1, ordinal(page(other, cursor(first))), "The other reader and acquisition continue");
        } finally { release.countDown(); }
        assertEquals(ReadProblem.Code.CLOSED, assertInstanceOf(ReadException.class, result.get(5, TimeUnit.SECONDS)).code());
        assertEquals(1, closes.get()); other.close(); assertEquals(0, shared.heldBytes());
        assertEquals(ReadProblem.Code.CLOSED, assertThrows(ReadException.class, () -> follow.next(follow.initialCursor())).code());
      }
    }
  }

  private static JsonObject page(OrdinalFollow follow, String cursor) throws IOException {
    try (var page = follow.next(cursor)) {
      byte[] data = bytes(page); assertTrue(data.length <= OrdinalFollow.MAX_PAGE_BYTES);
      var json = ReadWire.parse(data);
      int visited = 0;
      for (var batch : json.getAsJsonArray("batches")) visited += batch.getAsJsonObject().getAsJsonObject("batch").get("records_visited").getAsInt();
      assertTrue(visited <= OrdinalFollow.MAX_RECORDS); return json;
    }
  }
  private static byte[] bytes(OrdinalFollow.Page page) throws IOException {
    var out = new ByteArrayOutputStream(); page.writeTo(out); return out.toByteArray();
  }
  private static String cursor(JsonObject page) { return page.get("next_cursor").getAsString(); }
  private static JsonObject member(JsonObject page, int index) { return page.getAsJsonObject("frontier").getAsJsonArray("members").get(index).getAsJsonObject(); }
  private static int ordinal(JsonObject page) {
    return page.getAsJsonArray("batches").get(0).getAsJsonObject().getAsJsonObject("batch").getAsJsonArray("samples")
        .get(0).getAsJsonObject().get("ordinal").getAsInt();
  }
  private record Datum(String entry, int ordinal, long offset, long time, Long value) implements Comparable<Datum> {
    @Override public int compareTo(Datum other) { int byName = entry.compareTo(other.entry); return byName == 0 ? Integer.compare(ordinal, other.ordinal) : byName; }
  }
  private static void collect(JsonObject page, List<Datum> values, List<Long> problems) {
    for (var batch : page.getAsJsonArray("batches")) {
      var item = batch.getAsJsonObject(); var body = item.getAsJsonObject("batch");
      for (var sample : body.getAsJsonArray("samples")) {
        var s = sample.getAsJsonObject(); assertEquals("i64", s.getAsJsonObject("value").get("type").getAsString());
        values.add(new Datum(item.get("entry").getAsString(), s.get("ordinal").getAsInt(), s.get("record_offset").getAsLong(),
            s.get("timestamp_us").getAsLong(), s.getAsJsonObject("value").get("value").getAsLong()));
      }
      for (var problem : body.getAsJsonArray("problems")) {
        var p = problem.getAsJsonObject(); assertEquals("MALFORMED_RECORD", p.get("code").getAsString()); problems.add(p.get("record_offset").getAsLong());
      }
    }
  }
  private static List<Datum> oracle(Path path) throws IOException {
    var result = new ArrayList<Datum>(); var names = new HashMap<Long, String>(); var ordinals = new HashMap<Long, Integer>();
    try (var input = new IndependentLog.Records(path)) {
      int offset = input.first;
      for (var r = input.at(offset); r != null; r = input.at(offset)) {
        offset = r.end();
        if (r.id() == 0) { if (input.control(r) == 0) names.put(input.start(r).id(), input.start(r).name()); continue; }
        int ordinal = ordinals.getOrDefault(r.id(), 0); ordinals.put(r.id(), ordinal + 1);
        Long value = r.payloadSize() == 8 ? ByteBuffer.wrap(input.payload(r)).order(ByteOrder.LITTLE_ENDIAN).getLong() : null;
        result.add(new Datum(names.get(r.id()), ordinal, r.offset(), r.timestampUs(), value));
      }
      assertNull(input.stopped);
    }
    return result;
  }

  private static final class Recording implements AutoCloseable {
    final Path path; final WpilogOutput output; final LiveLog live; final LogManager manager;
    final String writerIdentity = java.util.UUID.randomUUID().toString();
    final Map<String, EntryInfo> entries = new HashMap<>(); boolean finished;
    Recording(Path path) throws IOException {
      try {
        var constructor = LogManager.class.getDeclaredConstructor(); constructor.setAccessible(true); manager = constructor.newInstance();
      } catch (ReflectiveOperationException e) { throw new IOException(e); }
      this.path = path.toAbsolutePath(); manager.addAllowedDirectory(path.getParent().toRealPath());
      output = new WpilogOutput(this.path); live = new LiveLog(this.path, 0); manager.beginCapture(live);
    }
    void entry(String name, String type, String metadata) throws IOException {
      var entry = new EntryInfo(output.start(name, type, metadata, 0), name, type, metadata); entries.put(name, entry); live.announce(entry);
    }
    void number(String name, long time, long value, boolean malformed) throws IOException {
      var entry = entries.get(name); byte[] payload = malformed ? new byte[] {1} : ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
      var written = output.append(entry.id(), time, payload); live.append(entry, new ValueFrame(entry.id(), time, 2, value), written); live.expire();
    }
    void text(String name, String text) throws IOException {
      var entry = entries.get(name); var written = output.append(entry.id(), 1, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      live.append(entry, new ValueFrame(entry.id(), 1, 4, text), written); live.expire();
    }
    void metadata(String name, String metadata) throws IOException {
      var before = entries.get(name); output.setMetadata(before.id(), metadata, 0);
      var after = new EntryInfo(before.id(), name, before.type(), metadata); entries.put(name, after); live.metadata(after);
    }
    OrdinalFollow.Member member(String id, long generation) throws IOException { return new OrdinalFollow.Member(id, generation, manager.readAccess().open(path), null, writerIdentity); }
    OrdinalFollow.Prefix prefix(long revision) throws IOException { return new OrdinalFollow.Prefix(RECORDING, revision, finished, List.of(member("member", 1))); }
    void finish() throws IOException { if (!finished) { output.close(); manager.finishCapture(live); finished = true; } }
    @Override public void close() throws IOException { try { finish(); } finally { manager.shutdown(); } }
  }

  private static class TrackingSnapshot implements ReadSnapshot {
    private final ReadSnapshot delegate; private final AtomicInteger closes;
    TrackingSnapshot(ReadSnapshot delegate, AtomicInteger closes) { this.delegate = delegate; this.closes = closes; }
    @Override public Path path() { return delegate.path(); }
    @Override public FileSnapshot boundary() { return delegate.boundary(); }
    @Override public boolean live() { return delegate.live(); }
    @Override public Map<String, EntryInfo> entries() { return delegate.entries(); }
    @Override public StructSchemas schemas() { return delegate.schemas(); }
    @Override public long sampleCount(String entry) { return delegate.sampleCount(entry); }
    @Override public long indexBytes() { return delegate.indexBytes(); }
    @Override public boolean truncated() { return delegate.truncated(); }
    @Override public boolean damaged() { return delegate.damaged(); }
    @Override public String truncationMessage() { return delegate.truncationMessage(); }
    @Override public SampleCursor read(ReadQuery query) throws IOException { return delegate.read(query); }
    @Override public void close() throws IOException { closes.incrementAndGet(); delegate.close(); }
  }
}
