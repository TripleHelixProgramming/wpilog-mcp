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
import org.triplehelix.wpilogmcp.fixtures.*;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.CallerContext.Authority;
import org.triplehelix.wpilogmcp.read.*;
import org.triplehelix.wpilogmcp.read.server.OrdinalFollow;

class ViewBindingTest {
  @TempDir Path temp;
  final LogManager logs = LogManager.getInstance();
  Set<Path> original; RecordingFixture fixture; VisualizationWorkspace workspace;
  @BeforeEach void start() throws Exception {
    temp = temp.toRealPath(); original = logs.getConfiguredDirectories(); logs.clearAllowedDirectories(); logs.addAllowedDirectory(temp);
    fixture = new RecordingFixture(temp.resolve("store"), 80);
    workspace = new VisualizationWorkspace(ServerIdentity.ephemeral(), logs);
  }
  @AfterEach void stop() throws Exception {
    workspace.close(); logs.addAllowedDirectory(temp); assertTrue(logs.release(temp).released(), "Binding and failed admission must release prefixes");
    logs.clearAllowedDirectories(); original.forEach(logs::addAllowedDirectory);
  }
  JsonObject config() { return RecordingBindingsTest.config("entry", RecordingFixture.name(0), "int64", "number", null); }
  JsonObject view(JsonObject config) throws Exception {
    return workspace.views.create(Authority.STDIO_LOCAL, UUID.randomUUID(), fixture.reference, config).get();
  }
  JsonObject request(JsonObject view) {
    var out = new JsonObject(); out.add("view_id", view.get("view_id")); out.add("expected_revision", view.get("revision")); return out;
  }
  UUID id(JsonObject view) { return UUID.fromString(view.get("view_id").getAsString()); }
  JsonObject bind(JsonObject view) throws Exception { return workspace.bindView(Authority.STDIO_LOCAL, request(view)); }
  @Test void detachedBindingDeduplicatesSelectionsAndKeepsUnresolvedRequirements() throws Exception {
    var config = config(); var duplicate = config.getAsJsonArray("requirements").get(0).deepCopy().getAsJsonObject(); duplicate.addProperty("id", "again"); config.getAsJsonArray("requirements").add(duplicate);
    var missing = duplicate.deepCopy(); missing.addProperty("id", "missing"); missing.addProperty("entry", "/missing"); config.getAsJsonArray("requirements").add(missing);
    var view = view(config); var bound = bind(view);
    assertTrue(bound.get("follow_ready").getAsBoolean()); assertEquals(1, bound.getAsJsonArray("queries").size());
    assertEquals(3, bound.getAsJsonObject("compatibility").getAsJsonArray("bindings").size());
    assertEquals("partial", bound.getAsJsonObject("compatibility").get("compatibility").getAsString());
    assertEquals(fixture.reference, bound.get("source_ref")); assertEquals(view, workspace.views.get(id(view)));
    assertTrue(workspace.capabilities().get("recording_view_follow").getAsBoolean()); assertFalse(workspace.capabilities().get("live").getAsBoolean());
    assertTrue(logs.release(temp).released());
  }
  @Test void bootstrapUsesTheBindingPrefixAndExactFixedWindowAcrossMembers() throws Exception {
    var config = config(); config.getAsJsonObject("time").add("recorded", JsonParser.parseString("{\"mode\":\"fixed\",\"start_sec\":1.5,\"end_sec\":2}"));
    var view = view(config); var opens = new AtomicInteger();
    var owner = new ViewBinding(workspace.views, new RecordingBindings(s -> { opens.incrementAndGet(); return workspace.sources.admitRecording(s); }));
    var budget = new OrdinalFollow.Budget(64 << 20);
    try (var prepared = owner.prepare(id(view), 1, () -> true)) {
      var bound = prepared.describe(); var window = bound.getAsJsonArray("queries").get(0).getAsJsonObject().getAsJsonArray("windows").get(0).getAsJsonObject();
      assertEquals("1500000", window.get("start_us").getAsString()); assertEquals("2000000", window.get("end_us").getAsString()); assertTrue(window.get("end_inclusive").getAsBoolean());
      try (var follow = prepared.open(UUID.randomUUID().toString(), budget); var frame = follow.next(follow.initialCursor())) {
        assertEquals(1, opens.get(), "Binding and bootstrap must not open two different prefixes");
        var bytes = frame.payload(); byte[] copy = new byte[bytes.remaining()]; bytes.get(copy);
        var page = JsonParser.parseString(new String(copy, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(bound.get("clock"), page.getAsJsonObject("frontier").get("clock"), "A bound frontier carries the shared clock facts for that exact prefix");
        int delivered = 0; for (var batch : page.getAsJsonArray("batches")) for (var value : batch.getAsJsonObject().getAsJsonObject("batch").getAsJsonArray("samples")) {
          var sample = value.getAsJsonObject(); assertEquals("2000000", sample.get("timestamp_us").getAsString());
          assertEquals("1", sample.getAsJsonObject("value").get("value").getAsString()); delivered++;
        }
        assertEquals(1, delivered); assertTrue(page.get("ended").getAsBoolean());
      }
    }
    assertEquals(0, budget.heldBytes());
  }
  @Test void staleRevisionRefusesBeforeSourceWorkAndUntrustedBeforeLookup() throws Exception {
    var view = view(config()); var opens = new AtomicInteger();
    var owner = new ViewBinding(workspace.views, new RecordingBindings(s -> { opens.incrementAndGet(); return workspace.sources.admitRecording(s); }));
    assertTrue(assertThrows(IOException.class, () -> owner.prepare(id(view), 2, () -> true)).getMessage().startsWith("REVISION_CONFLICT:"));
    assertEquals(0, opens.get());
    assertThrows(java.nio.file.AccessDeniedException.class, () -> workspace.prepareViewFollow(Authority.UNTRUSTED, new JsonObject(), () -> true));
  }
  @Test void editDuringSlowAdmissionRefusesWithoutBlockingMetadataQueue() throws Exception {
    var view = view(config()); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var owner = new ViewBinding(workspace.views, new RecordingBindings(s -> {
      var admission = workspace.sources.admitRecording(s); entered.countDown();
      try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture release timed out"); }
      catch (InterruptedException failure) { Thread.currentThread().interrupt(); admission.close(); throw new IOException(failure); }
      return admission;
    }));
    var pool = Executors.newSingleThreadExecutor();
    try {
      var pending = pool.submit(() -> owner.prepare(id(view), 1, () -> true)); assertTrue(entered.await(5, TimeUnit.SECONDS));
      var changed = config(); changed.getAsJsonObject("links").addProperty("cursor", false);
      assertEquals(2, workspace.views.update(Authority.STDIO_LOCAL, id(view), 1, changed).get(2, TimeUnit.SECONDS).get("revision").getAsInt());
      release.countDown(); var failure = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
      assertTrue(failure.getCause().getMessage().startsWith("REVISION_CONFLICT:"));
    } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
  }
  @Test void editOrSessionExpiryAfterPreparationCannotOpenAStaleFollow() throws Exception {
    var view = view(config()); var alive = new AtomicBoolean(true);
    try (var prepared = workspace.prepareViewFollow(Authority.STDIO_LOCAL, request(view), alive::get)) {
      workspace.views.update(Authority.STDIO_LOCAL, id(view), 1, config()).get();
      assertTrue(assertThrows(IOException.class, () -> prepared.open("epoch", new OrdinalFollow.Budget(64 << 20))).getMessage().startsWith("REVISION_CONFLICT:"));
    }
    view = workspace.views.get(id(view));
    try (var prepared = workspace.prepareViewFollow(Authority.STDIO_LOCAL, request(view), alive::get)) {
      alive.set(false); assertThrows(java.nio.file.AccessDeniedException.class, () -> prepared.open("epoch", new OrdinalFollow.Budget(64 << 20)));
    }
  }
  @Test void emptyAndUnresolvedViewsStayInspectableButCannotMintAnEmptyFollow() throws Exception {
    for (var config : List.of(VisualizationContracts.blank(), RecordingBindingsTest.config("entry", "/missing", "double", "number", null))) {
      var view = view(config);
      try (var prepared = workspace.prepareViewFollow(Authority.STDIO_LOCAL, request(view), () -> true)) {
        assertFalse(prepared.describe().get("follow_ready").getAsBoolean());
        assertTrue(assertThrows(IOException.class, () -> prepared.open("epoch", new OrdinalFollow.Budget(64 << 20))).getMessage().startsWith("NO_RESOLVED_BINDINGS:"));
      }
    }
  }
  @Test void thirtyThreeDistinctSelectionsRefuseWithoutTruncatingTheLayout() throws Exception {
    var config = config(); var requirements = config.getAsJsonArray("requirements");
    for (int i = 1; i < 33; i++) {
      var r = requirements.get(0).deepCopy().getAsJsonObject(); r.addProperty("id", "r" + i); r.addProperty("entry", RecordingFixture.name(i)); requirements.add(r);
    }
    var view = view(config); var refused = assertThrows(ReadException.class, () -> bind(view));
    assertEquals(ReadProblem.Code.RESOURCE_LIMIT, refused.code()); assertFalse(refused.retryable());
    assertEquals(33, workspace.views.get(id(view)).getAsJsonObject("configuration").getAsJsonArray("requirements").size());
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {5000, 10000})
  void largeRecordingBindingsKeepOneMiBPagesAndExactSelectedValues(int topics) throws Exception {
    fixture = new RecordingFixture(temp.resolve("large-" + topics), topics);
    var config = config(); var reqs = config.getAsJsonArray("requirements");
    for (int i = 1; i < 32; i++) {
      var requirement = reqs.get(0).deepCopy().getAsJsonObject(); requirement.addProperty("id", "r" + i);
      requirement.addProperty("entry", RecordingFixture.name(i)); reqs.add(requirement);
    }
    var view = view(config()); // Independent peer layout must not share this configuration.
    var selected = view(config);
    try (var prepared = workspace.prepareViewFollow(Authority.STDIO_LOCAL, request(selected), () -> true);
         var follow = prepared.open("epoch", new OrdinalFollow.Budget(64 << 20))) {
      String cursor = follow.initialCursor(); int delivered = 0;
      while (true) {
        JsonObject page;
        try (var frame = follow.next(cursor)) {
          var buffer = frame.payload(); assertTrue(buffer.remaining() <= 1 << 20); var bytes = new byte[buffer.remaining()]; buffer.get(bytes);
          page = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
        for (var batch : page.getAsJsonArray("batches")) {
          var item = batch.getAsJsonObject(); int n = Integer.parseInt(item.get("entry").getAsString().substring("/signal/".length()));
          for (var sample : item.getAsJsonObject("batch").getAsJsonArray("samples")) {
            var value = sample.getAsJsonObject(); int ordinal = value.get("ordinal").getAsInt();
            assertEquals((ordinal + 1) * 1_000_000L, value.get("timestamp_us").getAsLong());
            assertEquals(n + ordinal, value.getAsJsonObject("value").get("value").getAsInt()); delivered++;
          }
        }
        if (page.get("ended").getAsBoolean()) break; cursor = page.get("next_cursor").getAsString();
      }
      assertEquals(96, delivered); assertEquals(1, workspace.views.get(id(view)).getAsJsonObject("configuration").getAsJsonArray("requirements").size());
    }
  }
  @Test void projectedFieldsRemainDistinctWhileDuplicateRequirementsShareOneQuery() throws Exception {
    for (int member = 0; member < 2; member++) fixture.rewrite(member, writer -> {
      int entry = writer.start("/array", "double[]", "", 0);
      writer.append(entry, 1_000_000, WpilogWriter.encodeDoubleArray(new double[]{2, 7}));
    });
    var config = RecordingBindingsTest.config("entry", "/array", "double[]", "number", "[0]");
    var second = config.getAsJsonArray("requirements").get(0).deepCopy().getAsJsonObject(); second.addProperty("id", "second"); second.addProperty("field_path", "[1]"); config.getAsJsonArray("requirements").add(second);
    var plan = bind(view(config)); var queries = plan.getAsJsonArray("queries");
    assertEquals(2, queries.size()); assertEquals("[0]", queries.get(0).getAsJsonObject().get("field").getAsString()); assertEquals("[1]", queries.get(1).getAsJsonObject().get("field").getAsString());
  }
  @Test void noClockEvidenceDoesNotTurnFollowingIntoAnUnboundedBootstrap() throws Exception {
    for (int member = 0; member < 2; member++) fixture.rewrite(member, writer -> writer.start(RecordingFixture.name(0), "int64", "", 0));
    var view = view(config());
    var owner = new ViewBinding(workspace.views, new RecordingBindings(s -> {
      var admitted = workspace.sources.admitRecording(s); var prefix = admitted.prefix();
      return new RecordedSources.Admission(new OrdinalFollow.Prefix(prefix.recordingId(), prefix.catalogRevision(), false, prefix.members()), admitted.access(), admitted.continuation());
    }));
    try (var prepared = owner.prepare(id(view), 1, () -> true)) {
      assertEquals("waiting_for_time_sync", prepared.describe().getAsJsonObject("clock").get("basis").getAsString());
      assertFalse(prepared.describe().get("follow_ready").getAsBoolean());
      assertTrue(assertThrows(IOException.class, () -> prepared.open("epoch", new OrdinalFollow.Budget(64 << 20))).getMessage().startsWith("CLOCK_UNAVAILABLE:"));
    }
  }
  @Test void revisionChangeAtEngineAdmissionReleasesItsTransferredPrefix() throws Exception {
    var view = view(config()); var reads = new AtomicInteger();
    var owner = new ViewBinding(key -> {
      var current = workspace.views.get(key); if (reads.incrementAndGet() == 4) current.addProperty("revision", 2); return current;
    }, new RecordingBindings(workspace.sources));
    try (var prepared = owner.prepare(id(view), 1, () -> true)) {
      assertTrue(assertThrows(IOException.class, () -> prepared.open("epoch", new OrdinalFollow.Budget(64 << 20))).getMessage().startsWith("REVISION_CONFLICT:"));
    }
    assertEquals(4, reads.get()); assertTrue(logs.release(temp).released());
  }
  @Test void oversizedSelectedFrontierReleasesThePrefixOnConstructorFailure() throws Exception {
    fixture = new RecordingFixture(temp.resolve("metadata"), 4, "{\"description\":\"" + "x".repeat(600 << 10) + "\"}");
    var view = view(config());
    try (var prepared = workspace.prepareViewFollow(Authority.STDIO_LOCAL, request(view), () -> true)) {
      assertEquals(ReadProblem.Code.RESOURCE_LIMIT, assertThrows(ReadException.class, () -> prepared.open("epoch", new OrdinalFollow.Budget(64 << 20))).code());
    }
    assertTrue(logs.release(temp).released());
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {2, 4})
  void uncheckedFailureReleasesAdmissionAndTransferredPrefixes(int failAt) throws Exception {
    var view = view(config()); var reads = new AtomicInteger(); var retained = new AtomicReference<RecordedSources.Admission>();
    var owner = new ViewBinding(key -> {
      if (reads.incrementAndGet() == failAt) throw new AssertionError("Injected unchecked failure");
      return workspace.views.get(key);
    }, new RecordingBindings(source -> { var admitted = workspace.sources.admitRecording(source); retained.set(admitted); return admitted; }));
    assertThrows(AssertionError.class, () -> {
      try (var prepared = owner.prepare(id(view), 1, () -> true); var follow = prepared.open("epoch", new OrdinalFollow.Budget(64 << 20))) { fail("Expected injected failure"); }
    });
    boolean released = logs.release(temp).released();
    if (!released) retained.get().close(); // Clean the deliberately planted leak before the assertion.
    assertTrue(released, "Unchecked failure must release the native prefix");
  }
  @Test void sourceReplacementBetweenBindingAndBootstrapRefuses() throws Exception {
    var view = view(config());
    try (var prepared = workspace.prepareViewFollow(Authority.STDIO_LOCAL, request(view), () -> true)) {
      var file = fixture.directory.resolve("capture.wpilog"); Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10_000));
      assertEquals(ReadProblem.Code.FILE_CHANGED, assertThrows(ReadException.class, () -> prepared.open("epoch", new OrdinalFollow.Budget(64 << 20))).code());
    }
  }
}
