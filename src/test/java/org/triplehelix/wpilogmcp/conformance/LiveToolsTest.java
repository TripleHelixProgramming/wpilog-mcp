/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.harness.HarnessHttp;
import org.triplehelix.wpilogmcp.store.StoreCatalog;
import org.triplehelix.wpilogmcp.tools.LiveTools;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

class LiveToolsTest {
  @TempDir Path directory;
  static final java.util.List<Map.Entry<String, com.google.gson.JsonElement>> outputs = new java.util.concurrent.CopyOnWriteArrayList<>();
  private LiveToolRig rig(CapturePolicy policy) throws Exception {
    var rig = new LiveToolRig(directory, policy);
    rig.observed = (name, result) -> outputs.add(Map.entry(name, result)); return rig;
  }
  @org.junit.jupiter.api.AfterAll static void descriptionsNameObservedFields() {
    var registry = new ToolRegistry(); LiveTools.registerAll(registry, null);
    assertEquals(java.util.Set.of(), DescriptionOutputs.missing(registry.getToolNames().stream().map(registry::getTool).toList(), outputs));
  }
  @Test void latestValuesUseTheRobotClockAndUnannounceDropsThem() throws Exception {
    try (var rig = rig(CapturePolicy.ALL)) {
      assertEquals("not_applicable", rig.call("get_latest_values", "{\"entries\":[\"/x\"]}").get("status").getAsString());
      rig.announce("/x", "int"); rig.value("/x", 10_100_000, 2, 7L); rig.flush();
      assertEquals("no_match", rig.call("wait_for_change", "{\"entry\":\"/missing\"}").get("status").getAsString());
      rig.calendar.addAndGet(86_400_000); // A laptop calendar correction must not change robot age.
      var value = rig.call("get_latest_values", "{\"entries\":[\"NT:/x\",\"/missing\"]}");
      assertEquals("partial", value.get("status").getAsString()); assertEquals(1, value.getAsJsonArray("skipped").size());
      var row = value.getAsJsonArray("values").get(0).getAsJsonObject();
      assertEquals(7, row.get("value").getAsLong()); assertEquals("int", row.get("type").getAsString());
      assertEquals(10.1, row.get("timestamp_sec").getAsDouble()); assertEquals(150, row.get("age_ms").getAsDouble(), .001);
      assertEquals(List.of("/missing"), value.getAsJsonArray("missing").asList().stream().map(v -> v.getAsString()).toList());
      assertEquals(rig.service.live().current().path().toString(), value.getAsJsonObject("inputs").get("session").getAsString());
      rig.gateway.unannounce("/x").get(); rig.pump(() -> !rig.service.live().topics().containsKey("/x"));
      assertEquals("no_match", rig.call("get_latest_values", "{\"entries\":[\"/x\"]}").get("status").getAsString());
      rig.gateway.dropClients().get(); rig.pump(() -> !rig.service.live().connected());
      var absent = rig.call("get_latest_values", "{\"entries\":[\"/x\"]}");
      assertEquals("not_applicable", absent.get("status").getAsString()); assertFalse(absent.get("last_session").isJsonNull()); assertFalse(absent.get("ended_at").isJsonNull());
    }
  }
  @Test void waitsArePerMcpSessionFirstPublicationWinsAndTimeoutUsesTheInjectedClock() throws Exception {
    try (var rig = rig(CapturePolicy.ALL)) {
      rig.announce("/x", "int"); rig.value("/x", 10_000_000, 2, 1L);
      var first = CompletableFuture.supplyAsync(() -> call(rig.http, "{\"entry\":\"/x\",\"timeout_ms\":999999}"));
      rig.pump(() -> rig.service.live().pendingWaits() == 1);
      var duplicate = rig.call("wait_for_change", "{\"entry\":\"NT:/x\",\"timeout_ms\":0}");
      assertEquals("error", duplicate.get("status").getAsString()); assertTrue(duplicate.get("error").getAsString().contains("outstanding"));
      var peer = new HarnessHttp(rig.transport.getPort()); peer.initialize();
      var second = CompletableFuture.supplyAsync(() -> call(peer, "{\"entry\":\"/x\"}"));
      rig.pump(() -> rig.service.live().pendingWaits() == 2);
      rig.value("/x", 10_020_000, 2, 1L); rig.value("/x", 10_040_000, 2, 2L);
      for (var pending : List.of(first, second)) {
        var result = pending.get(5, TimeUnit.SECONDS);
        assertTrue(result.get("changed").getAsBoolean()); assertEquals(1, result.get("value").getAsLong());
        assertEquals(10.02, result.get("timestamp_sec").getAsDouble());
      }
      var timeout = CompletableFuture.supplyAsync(() -> call(rig.http, "{\"entry\":\"/x\",\"timeout_ms\":999999}"));
      rig.pump(() -> rig.service.live().pendingWaits() == 1);
      for (int i = 0; i < 9; i++) {
        rig.robot.addAndGet(3_000_000); rig.loop.advance(3_000_000);
        rig.pump(() -> rig.service.live().timeEstimate().orElseThrow().receivedUs() == rig.loop.nowUs());
        assertFalse(timeout.isDone());
      }
      rig.loop.advance(2_999_000); assertEquals(1, rig.service.live().pendingWaits()); assertFalse(timeout.isDone());
      rig.loop.advance(1_000); var timed = timeout.get(5, TimeUnit.SECONDS);
      assertEquals("ok", timed.get("status").getAsString()); assertFalse(timed.get("changed").getAsBoolean());
      var dropped = CompletableFuture.supplyAsync(() -> call(rig.http, "{\"entry\":\"/x\"}"));
      rig.pump(() -> rig.service.live().pendingWaits() == 1);
      rig.gateway.dropClients().get(); rig.pump(() -> !rig.service.live().connected());
      assertEquals("not_applicable", dropped.get(5, TimeUnit.SECONDS).get("status").getAsString());
      assertEquals(0, rig.service.live().pendingWaits());
    }
  }
  @Test void defaultWaitEndsAtFiveSecondsAndUnannounceReleasesItsSlot() throws Exception {
    try (var rig = rig(CapturePolicy.ALL)) {
      rig.announce("/x", "int");
      var pending = CompletableFuture.supplyAsync(() -> call(rig.http, "{\"entry\":\"/x\"}"));
      rig.pump(() -> rig.service.live().pendingWaits() == 1);
      rig.robot.addAndGet(3_000_000); rig.loop.advance(3_000_000);
      rig.pump(() -> rig.service.live().timeEstimate().orElseThrow().receivedUs() == rig.loop.nowUs());
      rig.loop.advance(1_999_000); assertEquals(1, rig.service.live().pendingWaits()); assertFalse(pending.isDone()); rig.loop.advance(1_000);
      assertEquals(0, rig.service.live().pendingWaits());
      assertFalse(pending.get(5, TimeUnit.SECONDS).get("changed").getAsBoolean());
      var removed = CompletableFuture.supplyAsync(() -> call(rig.http, "{\"entry\":\"/x\"}"));
      rig.pump(() -> rig.service.live().pendingWaits() == 1);
      rig.gateway.unannounce("/x").get(); rig.pump(() -> !rig.service.live().topics().containsKey("/x"));
      assertEquals("not_applicable", removed.get(5, TimeUnit.SECONDS).get("status").getAsString());
      assertEquals(0, rig.service.live().pendingWaits());
    }
  }

  @Test void shutdownRefusesNewWaitsBeforeTheClientEventLoopStops() throws Exception {
    try (var rig = rig(CapturePolicy.ALL)) {
      rig.announce("/x", "int");
      var live = rig.service.live();
      var first = live.waitFor("first", "/x", 5000);
      var closing = CompletableFuture.runAsync(rig.service::close);
      try {
        assertNotNull(first.get(5, TimeUnit.SECONDS).reason());
        // Do not pump the client: close has released waits but cannot yet close the writer.
        assertTrue(live.current().open());
        var late = live.waitFor("late", "/x", 5000);
        try {
          assertTrue(late.isDone(), "A wait registered during shutdown must not await a stopped clock");
          assertNotNull(late.join().reason());
        } finally { late.cancel(false); }
      } finally { rig.pump(closing::isDone); closing.get(5, TimeUnit.SECONDS); }
    }
  }

  @Test void registrationRechecksAnUnannouncedTopicUnderTheWaitLock() throws Exception {
    try (var rig = rig(CapturePolicy.ALL)) {
      rig.announce("/x", "int");
      // This is the ordering where the tool saw the topic immediately before its removal.
      rig.gateway.unannounce("/x").get(); rig.pump(() -> !rig.service.live().topics().containsKey("/x"));
      var late = rig.service.live().waitFor("late", "/x", 5000);
      try {
        assertTrue(late.isDone(), "An unannounced topic must not leave a new waiter behind");
        assertNotNull(late.join().reason());
      } finally { late.cancel(false); }
    }
  }

  @Test void deviceIdentityIsVisibleBeforeTheAddressDirectoryMoves() throws Exception {
    try (var rig = rig(CapturePolicy.ALL)) {
      rig.announce("/x", "int");
      var old = rig.service.live().current();
      var device = new org.triplehelix.wpilogmcp.capture.context.DeviceIdentity(
          "SYNTHETIC-LIVE", "synthetic device comments", old.address(), "SHA256:synthetic", Map.of());
      rig.service.live().status(new org.triplehelix.wpilogmcp.store.CaptureStore.Status(old.path(),
          old.startedAt(), old.endedAt(), old.address(), old.open(), old.endReason(), device,
          old.statistics(), old.event(), old.matchType(), old.matchNumber()));
      var session = rig.session(); var robot = session.getAsJsonObject("robot");
      assertEquals("SYNTHETIC-LIVE", robot.get("serial_number").getAsString());
      assertEquals("synthetic device comments", robot.get("comments").getAsString());
      assertEquals("device", robot.get("basis").getAsString());
      assertEquals(old.path().toString(), session.get("path").getAsString());
    }
  }

  @Test void newestSessionsCompareInstantsAcrossFractionalSeconds() throws Exception {
    try (var rig = rig(CapturePolicy.ALL)) {
      rig.announce("/x", "int");
      String later = rig.service.live().current().startedAt().plusMillis(500).toString();
      rig.service.live().manifest(rig.root.resolve("robots/other/sessions/later/session.json"),
          new org.triplehelix.wpilogmcp.store.StoreManifest.Session("later", later, later,
              "synthetic", null, null, null, null, List.of()));
      var sessions = rig.call("list_sessions", "{}").getAsJsonArray("sessions");
      assertEquals(2, sessions.size());
      assertEquals("later", sessions.get(0).getAsJsonObject().get("id").getAsString());
    }
  }

  @Test void aBlockedStoreCannotDelayLiveQueriesOrCostPublicationAndImportsAppearAfterward() throws Exception {
    try (var rig = rig(CapturePolicy.ALL)) {
      rig.announce("/SystemStats/SerialNumber", "string"); rig.announce("/x", "int");
      rig.value("/x", 10_000_000, 2, 1L); rig.flush(); rig.manager.stores().awaitImports();
      var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
      var job = rig.manager.stores().store(rig.root).importPaths(new org.triplehelix.wpilogmcp.store.LogStore.Request(List.of(
          org.triplehelix.wpilogmcp.fixtures.ImportFixture.write(directory.resolve("stick/import.wpilog"), 9, null, rig.calendar.get() * 1000 - 250_000)), false,
          rig.root.relativize(rig.service.live().current().path()).getName(1).toString()), progress -> {
        if (progress.phase().equals("starting")) { entered.countDown(); try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
          catch (InterruptedException e) { throw new AssertionError(e); } }
      });
      try {
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        rig.value("/x", 10_020_000, 2, 2L); rig.flush();
        assertEquals(2, rig.session().get("records").getAsLong());
        assertEquals(2, rig.call("get_latest_values", "{\"entries\":[\"/x\"]}").getAsJsonArray("values").get(0).getAsJsonObject().get("value").getAsLong());
        assertFalse(job.isDone(), "Queries must finish while the store is still blocked");
      } finally { release.countDown(); job.get(10, TimeUnit.SECONDS); }
      rig.manager.stores().awaitImports();
      var imports = rig.session().getAsJsonArray("imports"); assertEquals(1, imports.size());
      var imported = imports.get(0).getAsJsonObject(); assertEquals("by_time_overlap", imported.get("method").getAsString());
      assertTrue(imported.get("offset_sec").isJsonNull());
      assertTrue(java.nio.file.Files.exists(Path.of(imported.get("path").getAsString())));
      assertEquals(2, rig.session().get("records").getAsLong());
      assertTrue(rig.session().get("connected").getAsBoolean());
      assertTrue(rig.session().get("ended_at").isJsonNull());
    }
  }

  @Test void costAndSessionLimitsAreExplicitAndOldManifestCountsStayUnknown() throws Exception {
    try (var rig = rig(CapturePolicy.ALL)) {
      for (int i = 0; i < 12; i++) { rig.announce("/topic" + i, "int"); rig.value("/topic" + i, 10_000_000, 2, (long) i); }
      rig.flush();
      var first = rig.session(); assertEquals(10, first.getAsJsonArray("cost").size());
      assertEquals(java.util.stream.IntStream.range(0, 12).mapToObj(i -> "/topic" + i).sorted().limit(10).toList(),
          first.getAsJsonArray("cost").asList().stream().map(v -> v.getAsJsonObject().get("name").getAsString()).toList());
      assertEquals(12, first.getAsJsonObject("limits").getAsJsonObject("cost").get("total").getAsInt());
      rig.gateway.dropClients().get(); rig.pump(() -> !rig.service.live().connected()); rig.manager.stores().awaitImports();
      rig.calendar.addAndGet(60_000); rig.robot.set(1_000_000); rig.loop.advance(1_000_000);
      rig.pump(() -> rig.service.live().connected() && rig.service.live().current().open());
      rig.flush();
      var limited = rig.call("list_sessions", "{\"limit\":1}");
      assertEquals(2, limited.getAsJsonObject("limits").getAsJsonObject("sessions").get("total").getAsInt());
      assertEquals(1, limited.getAsJsonArray("sessions").size());
      assertEquals(1, limited.getAsJsonObject("limits").getAsJsonObject("sessions").get("returned").getAsInt());
      assertEquals(1, limited.getAsJsonObject("limits").getAsJsonObject("sessions").get("limit").getAsInt());
      var newest = limited.getAsJsonArray("sessions").get(0).getAsJsonObject();
      assertNotEquals(first.get("id"), newest.get("id")); assertTrue(newest.get("started_at").getAsString().compareTo(first.get("started_at").getAsString()) > 0);
      var catalog = StoreCatalog.readManaged(rig.root, rig.manager.testGetSecurityValidator());
      var historical = catalog.sessions().get(0);
      var old = historical.session();
      rig.service.live().manifest(historical.path().resolve("session.json"), new org.triplehelix.wpilogmcp.store.StoreManifest.Session(
          old.id(), old.startedAt(), old.endedAt(), old.startBasis(), old.event(), old.matchType(), old.matchNumber(), old.teamNumber(), old.files()));
      var rows = rig.call("list_sessions", "{}").getAsJsonArray("sessions");
      var unknown = rows.asList().stream().map(v -> v.getAsJsonObject()).filter(v -> v.get("id").getAsString().equals(old.id())).findFirst().orElseThrow();
      assertTrue(unknown.get("records").isJsonNull(), unknown.toString());
    }
  }

  private static JsonObject call(HarnessHttp http, String args) {
    try { var result = http.call("wait_for_change", com.google.gson.JsonParser.parseString(args).getAsJsonObject()); outputs.add(Map.entry("wait_for_change", result)); return result; }
    catch (Exception e) { throw new RuntimeException(e); }
  }
  @Test void countsCostsPolicyAndClosedManifestAgreeWithIndependentRecordBytes() throws Exception {
    var policy = new CapturePolicy(List.of("/excluded"), Map.of("/thin", 100_000L));
    try (var rig = rig(policy)) {
      for (String name : List.of("/x", "/thin", "/excluded")) rig.announce(name, "int");
      for (int i = 0; i < 3; i++) for (String name : List.of("/x", "/thin", "/excluded")) rig.value(name, 10_000_000 + i * 50_000L, 2, (long) i);
      rig.flush();
      var row = rig.session();
      assertEquals(2, row.get("topic_count").getAsInt()); assertEquals(5, row.get("records").getAsLong());
      assertTrue(row.get("connected").getAsBoolean()); assertTrue(row.get("ended_at").isJsonNull());
      // id and payload-length each occupy one byte; timestamp 10,000,000 needs three.
      // Each int64 value has 1 + 1 + 1 + 3 + 8 = 14 bytes, including the header.
      assertEquals(70, row.get("bytes").getAsLong()); assertEquals(70.0 / 60, row.get("bytes_per_sec").getAsDouble(), 1e-12);
      assertEquals("/x", row.getAsJsonArray("cost").get(0).getAsJsonObject().get("name").getAsString());
      assertEquals(42, row.getAsJsonArray("cost").get(0).getAsJsonObject().get("bytes").getAsLong());
      assertEquals("/excluded", row.getAsJsonArray("excluded").get(0).getAsString());
      assertEquals(.1, row.getAsJsonArray("thinned").get(0).getAsJsonObject().get("period_sec").getAsDouble());
      rig.gateway.dropClients().get(); rig.pump(() -> !rig.service.live().connected());
      rig.manager.stores().awaitImports();
      var catalog = StoreCatalog.readManaged(rig.root, rig.manager.testGetSecurityValidator());
      assertEquals(5, catalog.sessions().get(0).session().captureStats().records());
      assertEquals(70, catalog.sessions().get(0).session().captureStats().bytes());
      var refreshed = new org.triplehelix.wpilogmcp.capture.LiveCapture(rig.root, rig.loop);
      refreshed.inventory(catalog); var tools = new ToolRegistry(); LiveTools.registerAll(tools, refreshed);
      var closed = tools.getTool("list_sessions").execute(new JsonObject()).getAsJsonObject().getAsJsonArray("sessions").get(0).getAsJsonObject();
      assertEquals(70, closed.get("bytes").getAsLong()); assertTrue(closed.get("bytes_per_sec").isJsonNull());
      var args = new JsonObject(); var names = new com.google.gson.JsonArray(); names.add("/x"); args.add("entries", names);
      var inactive = tools.getTool("get_latest_values").execute(args).getAsJsonObject();
      assertEquals(closed.get("path"), inactive.get("last_session"));
      assertEquals(closed.get("ended_at"), inactive.get("ended_at"));
      assertEquals(closed.get("path"), inactive.getAsJsonObject("inputs").get("session"));
    }
  }
  @Test void badArgumentsAreExplainedAndDisabledToolsAreAbsentFromTheOrdinaryRegistry() throws Exception {
    var registry = new ToolRegistry(); org.triplehelix.wpilogmcp.tools.WpilogTools.registerAll(registry);
    assertNull(registry.getTool("list_sessions"));
    try (var rig = rig(CapturePolicy.ALL)) {
      assertEquals("not_applicable", rig.call("wait_for_change", "{\"entry\":\"/x\"}").get("status").getAsString());
      for (String bad : List.of("{\"limit\":0}", "{\"limit\":1.5}", "{\"limit\":[]}", "{\"limit\":101}")) {
        assertEquals("error", rig.call("list_sessions", bad).get("status").getAsString());
      }
      for (String bad : List.of("{}", "{\"entries\":[]}", "{\"entries\":[null]}", "{\"entries\":[1]}")) {
        var result = rig.call("get_latest_values", bad); assertEquals("error", result.get("status").getAsString()); assertFalse(result.toString().contains("Internal error"));
      }
      for (String bad : List.of("{\"entry\":[]}", "{\"entry\":\"\"}", "{\"entry\":\"/x\",\"timeout_ms\":-1}", "{\"entry\":\"/x\",\"timeout_ms\":0.5}")) {
        var result = rig.call("wait_for_change", bad); assertEquals("error", result.get("status").getAsString()); assertFalse(result.toString().contains("Internal error"));
      }
    }
  }
}
