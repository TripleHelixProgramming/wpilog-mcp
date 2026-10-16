/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import org.triplehelix.wpilogmcp.visualization.*;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.*;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.CallerContext.Authority;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.*;
import org.triplehelix.wpilogmcp.store.*;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

/** The fixture is a real writer-owned prefix and a synthetic manifested recording. Assertions
 * use its declared counts/timestamps, not a second call to the inventory being tested. */
class LiveSourceInspectionTest {
  @TempDir Path temp;
  static final String WHEN = "2026-01-01T00:00:00Z";
  static final String STORE = "11111111-1111-4111-8111-111111111111";
  static final String SESSION = "22222222-2222-4222-8222-222222222222";
  final Announce topic = new Announce("/x", 1, "int", null, new JsonObject());
  final AtomicReference<Double> now = new AtomicReference<>(12_000_000.0);
  LogManager logs; VisualizationWorkspace workspace; CaptureWriter writer; CaptureIndex index;
  Path file, manifest; JsonObject session;

  @BeforeEach void start() throws Exception {
    temp = temp.toRealPath(); logs = new LogManager(); logs.addAllowedDirectory(temp);
    var robot = Files.createDirectories(temp.resolve("robots/fixture"));
    var directory = Files.createDirectories(robot.resolve("sessions/2026-01-01/session"));
    file = directory.resolve("capture.wpilog"); manifest = directory.resolve("session.json");
    Files.writeString(temp.resolve("store.json"), StoreJson.JSON.toJson(new Header(1, WHEN, STORE, List.of())));
    Files.writeString(robot.resolve("robot.json"), StoreJson.JSON.toJson(new Robot("fixture", "fixture", null, null, "logged")));
    index = new CaptureIndex((address, start) -> file, logs, 0, now::get);
    writer = new CaptureWriter(Clock.fixed(Instant.parse(WHEN), ZoneOffset.UTC), new ManualScheduler(), CapturePolicy.ALL, index);
    writer.connected(RobotAddress.uri("127.0.0.1", 5810, "inspection-test"), "networktables.first.wpi.edu");
    writer.timeSync(10_000_000, 0); writer.announce(topic);
    var open = new OpenCapture("capture.wpilog", new Provenance("captured", null, "capture.wpilog", WHEN, false), Files.size(file), 0, 0);
    session = StoreJson.JSON.toJsonTree(new Session(SESSION, WHEN, WHEN, "pit_clock", null, null, null, null, List.of(), open)).getAsJsonObject();
    session.getAsJsonObject("open_capture").getAsJsonObject("provenance").addProperty("writer_identity", writer.session().writerIdentity());
    writeManifest(); workspace = new VisualizationWorkspace(ServerIdentity.ephemeral(), logs);
  }
  @AfterEach void stop() throws Exception {
    logs.addAllowedDirectory(temp);
    if (writer != null) writer.close();
    if (workspace != null) workspace.close();
    logs.shutdown();
  }
  void writeManifest() throws Exception { Files.writeString(manifest, session.toString()); logs.catalogPublished(); }
  void append(long time, long value) { writer.value(topic, new ValueFrame(1, time, 2, value), 0); }
  JsonObject ref() {
    return JsonParser.parseString("""
        {"kind":"session","origin_store_id":"%s","session_id":"%s",
         "anchor_member":{"original_name":"capture.wpilog","recording_started_at":"%s"}}
        """.formatted(STORE, SESSION, WHEN)).getAsJsonObject();
  }
  JsonObject inspectPath() throws Exception {
    var request = new JsonObject(); request.addProperty("path", file.toString());
    return workspace.inspect(Authority.STDIO_LOCAL, request);
  }
  JsonObject inventory() throws Exception { return workspace.sources.describe(ref()); }
  final RecordingInventories.Client inventoryClient = new RecordingInventories.Client(Authority.STDIO_LOCAL, "stdio", () -> true);
  JsonObject paged() throws Exception { return workspace.inventories.open(inventoryClient, ref()); }
  static JsonObject inventoryId(JsonObject inventory) { var request = new JsonObject(); request.add("inventory_id", inventory.get("inventory_id")); return request; }
  JsonObject inventoryPage(JsonObject inventory) throws Exception { return workspace.inventories.list(inventoryClient, inventoryId(inventory)); }
  JsonObject selectedDetails(JsonObject inventory, JsonObject page) throws Exception {
    var request = inventoryId(inventory); var ids = new JsonArray();
    for (var entry : page.getAsJsonArray("entries")) if (entry.getAsJsonObject().get("name").getAsString().equals("NT:/x")) ids.add(entry.getAsJsonObject().get("row_id"));
    request.add("row_ids", ids); return workspace.inventories.details(inventoryClient, request);
  }

  JsonObject binding(String name) throws Exception {
    var config = VisualizationContracts.blank();
    var requirement = JsonParser.parseString("""
        {"id":"s","selector_kind":"entry","entry":"%s","accepted_entry_types":["int64"],
         "value_type":"number","field_path":null,"unit":null}
        """.formatted(name)).getAsJsonObject();
    config.getAsJsonArray("requirements").add(requirement);
    var request = new JsonObject(); request.add("source_ref", ref()); request.add("configuration", config);
    return workspace.check(request);
  }
  @Test void recordingViewsSurviveAppendsAliasAndFinalizationWithoutOwningAcquisition() throws Exception {
    append(10_000_000, 1);
    var request = new JsonObject(); request.add("source_ref", ref()); request.addProperty("request_id", UUID.randomUUID().toString());
    var first = workspace.create(Authority.STDIO_LOCAL, request); request.addProperty("request_id", UUID.randomUUID().toString());
    var second = workspace.create(Authority.STDIO_LOCAL, request);
    assertNotEquals(first.get("view_id"), second.get("view_id"));
    assertNotNull(writer.session()); append(9_000_000, 2);
    assertEquals("2", binding("NT:/x").getAsJsonArray("bindings").get(0).getAsJsonObject().getAsJsonArray("members").get(0).getAsJsonObject().get("sample_count").getAsString());
    session.addProperty("id", "33333333-3333-4333-8333-333333333333"); session.add("merged_session_ids", StoreJson.JSON.toJsonTree(List.of(SESSION))); writeManifest();
    request.addProperty("request_id", UUID.randomUUID().toString());
    assertEquals(first.get("source_ref"), workspace.create(Authority.STDIO_LOCAL, request).get("source_ref"));
    finalizeRecording(false); request.addProperty("request_id", UUID.randomUUID().toString());
    var finalized = workspace.create(Authority.STDIO_LOCAL, request);
    assertEquals(first.get("source_ref"), finalized.get("source_ref")); assertEquals(first.get("configuration"), finalized.get("configuration"));
    assertEquals(first.get("configuration"), workspace.views.get(UUID.fromString(first.get("view_id").getAsString())).get("configuration"));
    workspace.close(); workspace = null; assertTrue(logs.release(temp).released());
  }

  @Test void bindingReadsFreshLivePrefixesAndKeepsFinalWriterProof() throws Exception {
    append(10_000_000, 1); var before = binding("NT:/x");
    assertEquals("compatible", before.get("compatibility").getAsString()); assertFalse(before.get("finalized").getAsBoolean());
    var proof = before.getAsJsonArray("bindings").get(0).getAsJsonObject().getAsJsonArray("members").get(0).getAsJsonObject();
    assertEquals("1", proof.get("sample_count").getAsString()); assertEquals(writer.session().writerIdentity(), proof.get("writer_identity").getAsString());
    assertEquals("incompatible", binding("NT:/late").get("compatibility").getAsString());
    var late = new Announce("/late", 2, "int", null, new JsonObject()); writer.announce(late);
    writer.value(late, new ValueFrame(2, 9_000_000, 2, 5L), 0); append(8_000_000, 3);
    assertEquals("compatible", binding("NT:/late").get("compatibility").getAsString());
    assertEquals("1", proof.get("sample_count").getAsString(), "An admitted result stays immutable");
    var grown = binding("NT:/x"); assertEquals("2", grown.getAsJsonArray("bindings").get(0).getAsJsonObject().getAsJsonArray("members").get(0).getAsJsonObject().get("sample_count").getAsString());
    finalizeRecording(false); var after = binding("NT:/x"); assertTrue(after.get("finalized").getAsBoolean());
    var finalProof = after.getAsJsonArray("bindings").get(0).getAsJsonObject().getAsJsonArray("members").get(0).getAsJsonObject();
    assertEquals(proof.get("writer_identity"), finalProof.get("writer_identity")); assertEquals(LogStore.contentHash(file), finalProof.get("content_sha256").getAsString());
    assertEquals(before.get("recording_id"), after.get("recording_id"));
  }
  @Test void bindingSurvivesRecordedAliasButRejectsAnotherWriter() throws Exception {
    append(10_000_000, 1); var before = binding("NT:/x");
    var moved = file.resolveSibling("renamed.wpilog"); Files.move(file, moved); logs.relocateCapture(index.live(), file, moved); file = moved;
    session.addProperty("id", "33333333-3333-4333-8333-333333333333"); session.add("merged_session_ids", StoreJson.JSON.toJsonTree(List.of(SESSION)));
    session.getAsJsonObject("open_capture").addProperty("path", "renamed.wpilog"); writeManifest();
    assertEquals(before, binding("NT:/x"));
    session.getAsJsonObject("open_capture").getAsJsonObject("provenance").addProperty("writer_identity", "other-writer"); writeManifest();
    assertThrows(org.triplehelix.wpilogmcp.read.ReadException.class, () -> binding("NT:/x"));
  }

  @Test void pagedTraversalStaysFrozenWhileFreshDetailsSeeAppendsAndRefreshSeesDeclarations() throws Exception {
    append(10_000_000, 7); var fixed = paged(); var before = inventoryPage(fixed);
    var late = new Announce("/late", 2, "double", null, new JsonObject()); writer.announce(late); append(9_000_000, 8); now.set(14_000_000.0);
    assertEquals(before, inventoryPage(fixed), "A page reads detached facts, never the moving prefix");
    var details = selectedDetails(fixed, before); assertTrue(details.get("counts_grew_since_inventory").getAsBoolean());
    assertEquals("2", details.getAsJsonArray("descriptors").get(0).getAsJsonObject().getAsJsonObject("snapshot").getAsJsonObject("sample_counts").get("NT:/x").getAsString());
    assertEquals(9.0, details.get("start_time_sec").getAsDouble()); assertEquals(14.0, details.getAsJsonObject("clock").get("robot_time_sec").getAsDouble());
    assertEquals(12.0, fixed.getAsJsonObject("clock").get("robot_time_sec").getAsDouble());
    var fresh = paged(); assertEquals(fixed.get("total_entry_names").getAsInt() + 1, fresh.get("total_entry_names").getAsInt());
  }

  @Test void pagedDetailsSurviveFinalizationOnlyWithTheSamePersistedWriter() throws Exception {
    append(10_000_000, 7); var fixed = paged(); var page = inventoryPage(fixed); finalizeRecording(false);
    var details = selectedDetails(fixed, page); assertTrue(details.get("finalized").getAsBoolean());
    assertEquals(LogStore.contentHash(file), details.getAsJsonArray("members").get(0).getAsJsonObject().get("content_sha256").getAsString());
    assertEquals(page, inventoryPage(fixed));
    session.getAsJsonArray("files").get(0).getAsJsonObject().getAsJsonObject("provenance").remove("writer_identity"); writeManifest();
    assertEquals("INVENTORY_CHANGED", assertThrows(InventoryFailure.class, () -> selectedDetails(fixed, page)).code);
  }

  @Test void changedSelectedTypeNeverAppearsUnderItsOldRowId() throws Exception {
    append(10_000_000, 7); var fixed = paged(); var page = inventoryPage(fixed);
    // Reannouncements in one writer intentionally keep the first declared type. Simulate
    // a changed finalized member with the same claimed writer to exercise the detail proof.
    String metadata = index.live().entries().get("NT:/x").metadata();
    finalizeRecording(false);
    try (var out = new WpilogWriter(file, "")) { int id = out.start("NT:/x", "double", metadata, 0); out.append(id, 10_000_000, WpilogWriter.encodeDouble(7)); }
    var completed = session.getAsJsonArray("files").get(0).getAsJsonObject();
    completed.addProperty("sha256", LogStore.contentHash(file)); completed.addProperty("size_bytes", Files.size(file)); writeManifest();
    assertEquals("INVENTORY_CHANGED", assertThrows(InventoryFailure.class, () -> selectedDetails(fixed, page)).code);
    assertEquals(page, inventoryPage(fixed));
  }

  @Test void detachedGrantTicketSurvivesARecordedAliasAndMemberRename() throws Exception {
    append(10_000_000, 1); var fixed = paged(); var before = inventoryPage(fixed);
    var moved = file.resolveSibling("renamed.wpilog"); Files.move(file, moved); logs.relocateCapture(index.live(), file, moved); file = moved;
    session.addProperty("id", "33333333-3333-4333-8333-333333333333"); session.add("merged_session_ids", StoreJson.JSON.toJsonTree(List.of(SESSION)));
    session.getAsJsonObject("open_capture").addProperty("path", "renamed.wpilog"); writeManifest();
    assertEquals(before, inventoryPage(fixed));
    assertEquals(file.toString(), selectedDetails(fixed, before).getAsJsonArray("descriptors").get(0).getAsJsonObject().getAsJsonObject("snapshot").get("path").getAsString());
  }

  @Test void aNewRolloverMemberRequiresAListingRefreshBeforeDetails() throws Exception {
    append(10_000_000, 1); var fixed = paged(); var page = inventoryPage(fixed);
    var extra = file.resolveSibling("earlier.wpilog");
    try (var out = new WpilogWriter(extra, "")) { out.start("NT:/other", "double", "", 0); }
    var completed = new LogFile("earlier.wpilog", LogStore.contentHash(extra), Files.size(extra), "wpilog",
        new Provenance("captured", null, "earlier.wpilog", WHEN, false), true, 0, 0, WHEN, WHEN, "pit_clock", false, null);
    session.getAsJsonArray("files").add(StoreJson.JSON.toJsonTree(completed)); writeManifest();
    assertEquals(page, inventoryPage(fixed));
    assertEquals("INVENTORY_CHANGED", assertThrows(InventoryFailure.class, () -> selectedDetails(fixed, page)).code);
  }

  @Test void changedSelectedMetadataAndShrunkenCountsRequireRefresh() throws Exception {
    append(10_000_000, 1); var fixed = paged(); var page = inventoryPage(fixed);
    var properties = new JsonObject(); properties.addProperty("unit", "updated");
    writer.properties(new org.triplehelix.wpilogmcp.nt4.ControlMessage.Properties("/x", null, properties));
    assertEquals("INVENTORY_CHANGED", assertThrows(InventoryFailure.class, () -> selectedDetails(fixed, page)).code);
    workspace.inventories.release(inventoryClient, inventoryId(fixed));
    var next = paged(); var nextPage = inventoryPage(next); String metadata = index.live().entries().get("NT:/x").metadata();
    finalizeRecording(false);
    try (var out = new WpilogWriter(file, "")) { out.start("NT:/x", "int64", metadata, 0); }
    var completed = session.getAsJsonArray("files").get(0).getAsJsonObject();
    completed.addProperty("sha256", LogStore.contentHash(file)); completed.addProperty("size_bytes", Files.size(file)); writeManifest();
    assertEquals("INVENTORY_CHANGED", assertThrows(InventoryFailure.class, () -> selectedDetails(next, nextPage)).code);
  }

  @Test void inventoryAndNativeFollowHaveIndependentLifetimes() throws Exception {
    append(10_000_000, 7);
    var source = new org.triplehelix.wpilogmcp.read.server.OrdinalFollow.Source() {
      public org.triplehelix.wpilogmcp.read.server.OrdinalFollow.Prefix open() throws java.io.IOException { return workspace.sources.admitRecording(ref()).prefix(); }
    };
    var query = new org.triplehelix.wpilogmcp.read.ReadQuery("NT:/x", List.of(), org.triplehelix.wpilogmcp.read.ReadQuery.Order.FILE, 4096, 1 << 20);
    try (var follow = new org.triplehelix.wpilogmcp.read.server.OrdinalFollow("fixture-epoch", source, List.of(query),
        new org.triplehelix.wpilogmcp.read.server.OrdinalFollow.Budget(8 << 20), 4096, 1 << 20,
        org.triplehelix.wpilogmcp.read.server.OrdinalFollow.FrontierScope.QUERIES)) {
      var inventory = paged(); var page = inventoryPage(inventory);
      try (var first = follow.next(follow.initialCursor())) {
        workspace.inventories.release(inventoryClient, inventoryId(inventory));
        try (var replay = follow.next(follow.initialCursor())) { assertEquals(first.payload(), replay.payload()); }
      }
      var remaining = paged(); follow.close(); assertEquals(page.get("total_matching"), inventoryPage(remaining).get("total_matching"));
    }
  }
  JsonObject member(JsonObject inventory, String name) {
    return inventory.getAsJsonArray("members").asList().stream().map(JsonElement::getAsJsonObject)
        .filter(m -> m.get("member_id").getAsString().equals(name)).findFirst().orElseThrow();
  }

  @Test void inspectionAdmitsTheAnchoredRecordingWithoutDecodingTelemetryOrFlatteningItToAPath() throws Exception {
    append(10_000_000, 7); index.live().advance(11_000_000); index.live().expire();
    assertEquals(0, index.live().hotRecordCount()); long reads = index.live().mappedReadCount();
    var result = inspectPath(); assertEquals(ref(), result.get("source_ref"));
    var inventory = result.getAsJsonObject("inventory");
    assertEquals("session", inventory.get("source_kind").getAsString());
    assertFalse(inventory.get("finalized").getAsBoolean());
    assertEquals(1, inventory.getAsJsonArray("members").size());
    assertEquals("1", member(inventory, "capture.wpilog").getAsJsonObject("snapshot").getAsJsonObject("sample_counts").get("NT:/x").getAsString());
    assertEquals(10.0, inventory.get("start_time_sec").getAsDouble());
    assertEquals(10.0, inventory.get("end_time_sec").getAsDouble());
    assertEquals(12.0, inventory.getAsJsonObject("clock").get("robot_time_sec").getAsDouble());
    assertEquals("robot_estimate", inventory.getAsJsonObject("clock").get("basis").getAsString());
    assertEquals(reads, index.live().mappedReadCount(), "Inventory must not decode telemetry to find counts or clock bounds");
    assertThrows(java.io.IOException.class, () -> workspace.sources.resolve(ref()), "A recording has no single path data route");
    assertFalse(workspace.capabilities().get("live").getAsBoolean(), "Inspection alone is not the live panel gate");
    assertTrue(workspace.capabilities().get("live_source_inspection").getAsBoolean());
    assertEquals(4_194_304, workspace.capabilities().get("max_recording_inventory_bytes").getAsInt());
  }

  @Test void aFreshInspectionSeesLateMetadataAndBackwardSamplesButLeavesTheEarlierAnswerAlone() throws Exception {
    append(10_000_000, 7); var before = inventory();
    var late = new Announce("/late", 2, "double", null, new JsonObject()); writer.announce(late);
    append(9_000_000, 8); now.set(14_000_000.0);
    var after = inventory(); var snapshot = member(after, "capture.wpilog").getAsJsonObject("snapshot");
    assertTrue(snapshot.getAsJsonObject("entries").has("NT:/late"));
    assertEquals("2", snapshot.getAsJsonObject("sample_counts").get("NT:/x").getAsString());
    for (var signal : after.getAsJsonArray("entries")) {
      var entry = signal.getAsJsonObject();
      assertEquals(entry.get("name").getAsString().equals("NT:/x") ? 2 : 0, entry.get("sample_count").getAsLong());
    }
    assertEquals(9.0, after.get("start_time_sec").getAsDouble()); assertEquals(10.0, after.get("end_time_sec").getAsDouble());
    assertEquals(14.0, after.getAsJsonObject("clock").get("robot_time_sec").getAsDouble());
    assertEquals("1", member(before, "capture.wpilog").getAsJsonObject("snapshot").getAsJsonObject("sample_counts").get("NT:/x").getAsString());
    assertEquals(12.0, before.getAsJsonObject("clock").get("robot_time_sec").getAsDouble());
    assertEquals(before.get("recording_id"), after.get("recording_id"));
  }

  @Test void missingOrInvalidTimeSyncIsExplicitAndNeverUsesReceiptOrWallClockTime() throws Exception {
    now.set(null); var empty = inventory();
    assertTrue(empty.get("start_time_sec").isJsonNull()); assertTrue(empty.get("end_time_sec").isJsonNull());
    assertTrue(empty.getAsJsonObject("clock").get("right_edge_sec").isJsonNull());
    assertEquals("waiting_for_time_sync", empty.getAsJsonObject("clock").get("basis").getAsString());
    append(7_000_000, 1);
    for (Double value : Arrays.asList(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
      now.set(value); var clock = inventory().getAsJsonObject("clock");
      assertTrue(clock.get("robot_time_sec").isJsonNull()); assertEquals("last_sample", clock.get("basis").getAsString());
      assertEquals(7.0, clock.get("right_edge_sec").getAsDouble()); assertTrue(clock.get("waiting_for_time_sync").getAsBoolean());
    }
  }

  @Test void inspectionFollowsSessionMergeAliasesAndMemberRenames() throws Exception {
    append(10_000_000, 1); var before = inventory();
    var moved = file.resolveSibling("renamed.wpilog"); Files.move(file, moved); logs.relocateCapture(index.live(), file, moved); file = moved;
    session.addProperty("id", "33333333-3333-4333-8333-333333333333");
    session.add("merged_session_ids", StoreJson.JSON.toJsonTree(List.of(SESSION)));
    session.getAsJsonObject("open_capture").addProperty("path", "renamed.wpilog"); writeManifest();
    var after = inventory(); assertEquals(before.get("recording_id"), after.get("recording_id"));
    assertEquals(file.toString(), member(after, "capture.wpilog").getAsJsonObject("snapshot").get("path").getAsString());
  }

  void finalizeRecording(boolean corruptHash) throws Exception {
    writer.close(); writer = null;
    var provenance = session.getAsJsonObject("open_capture").getAsJsonObject("provenance").deepCopy();
    var completed = new LogFile(file.getFileName().toString(), corruptHash ? "0".repeat(64) : LogStore.contentHash(file), Files.size(file), "wpilog",
        StoreJson.JSON.fromJson(provenance, Provenance.class), true, 10, 10, WHEN, WHEN, "pit_clock", false, null);
    session.getAsJsonArray("files").add(StoreJson.JSON.toJsonTree(completed)); session.add("open_capture", JsonNull.INSTANCE); writeManifest();
    logs.release(temp);
  }
  @Test void finalizedInspectionKeepsItsAnchorVerifiesContentAndUsesOnlyTheSampleClock() throws Exception {
    append(10_000_000, 1); var before = inventory(); finalizeRecording(false);
    var after = inventory(); assertTrue(after.get("finalized").getAsBoolean()); assertEquals(before.get("recording_id"), after.get("recording_id"));
    assertEquals(LogStore.contentHash(file), member(after, "capture.wpilog").get("content_sha256").getAsString());
    assertEquals("last_sample", after.getAsJsonObject("clock").get("basis").getAsString());
    assertFalse(after.getAsJsonObject("clock").get("waiting_for_time_sync").getAsBoolean());
    assertEquals(10.0, after.getAsJsonObject("clock").get("right_edge_sec").getAsDouble());
    // Opening the finalized file afresh still chooses the existing recorded-file workflow.
    assertEquals("recorded", inspectPath().getAsJsonObject("source_ref").get("kind").getAsString());
  }

  @Test void inspectionRetainsTheFollowersWriterHashAndGrantAdmission() throws Exception {
    append(10_000_000, 1); inventory();
    var provenance = session.getAsJsonObject("open_capture").getAsJsonObject("provenance");
    String identity = provenance.get("writer_identity").getAsString(); provenance.addProperty("writer_identity", UUID.randomUUID().toString()); writeManifest();
    assertTrue(assertThrows(java.io.IOException.class, this::inventory).getMessage().contains("another writer"));
    provenance.addProperty("writer_identity", identity); writeManifest(); inventory();
    logs.clearAllowedDirectories(); logs.addAllowedDirectory(Files.createDirectories(temp.resolve("elsewhere")));
    assertThrows(java.io.IOException.class, this::inventory);
    logs.addAllowedDirectory(temp); finalizeRecording(true);
    assertTrue(assertThrows(java.io.IOException.class, this::inventory).getMessage().contains("manifested hash"));
  }

  @Test void aMergedSessionDoesNotBroadenTheRecordingAndEveryRolloverRetainsItsOwnInventory() throws Exception {
    append(10_000_000, 1);
    for (String name : List.of("rollover.wpilog", "other-recording.wpilog")) {
      var extra = file.resolveSibling(name);
      try (var out = new WpilogWriter(extra, "")) { int id = out.start("NT:/x", "double", index.live().entries().get("NT:/x").metadata(), 0); out.append(id, 5_000_000, WpilogWriter.encodeDouble(3)); }
      var provenance = new Provenance("captured", null, name, name.startsWith("other") ? "2026-01-01T00:01:00Z" : WHEN, false);
      var entry = new LogFile(name, LogStore.contentHash(extra), Files.size(extra), "wpilog", provenance, true, 5, 5, WHEN, WHEN, "pit_clock", false, null);
      session.getAsJsonArray("files").add(StoreJson.JSON.toJsonTree(entry));
    }
    writeManifest(); var result = inventory(); assertEquals(2, result.getAsJsonArray("members").size());
    assertEquals("double", member(result, "rollover.wpilog").getAsJsonObject("snapshot").getAsJsonObject("entries").getAsJsonObject("NT:/x").get("type").getAsString());
    assertEquals("int64", member(result, "capture.wpilog").getAsJsonObject("snapshot").getAsJsonObject("entries").getAsJsonObject("NT:/x").get("type").getAsString());
    assertEquals(5.0, result.get("start_time_sec").getAsDouble()); assertEquals(10.0, result.get("end_time_sec").getAsDouble());
    assertEquals(0, result.getAsJsonArray("entries").size(), "Conflicting member types cannot become one selectable signal");
    assertEquals("NT:/x", result.getAsJsonArray("entry_conflicts").get(0).getAsJsonObject().get("name").getAsString());
  }

  @Test void knownFinalHashesRecoverTheRecordingWhenItsOldSessionAliasIsGone() throws Exception {
    append(10_000_000, 1); finalizeRecording(false);
    var reference = ref(); var hashes = new JsonArray(); var member = new JsonObject(); member.addProperty("content_sha256", LogStore.contentHash(file)); hashes.add(member); reference.add("finalized_members", hashes);
    session.addProperty("id", "33333333-3333-4333-8333-333333333333"); writeManifest();
    var inventory = workspace.sources.describe(reference); assertTrue(inventory.get("finalized").getAsBoolean());
    assertEquals(1, inventory.getAsJsonArray("members").size());
    member.addProperty("content_sha256", "0".repeat(64));
    assertTrue(assertThrows(java.io.IOException.class, () -> workspace.sources.describe(reference)).getMessage().contains("HISTORY_UNAVAILABLE"));
  }

  @Test void emptyFinalizedRecordingDoesNotPromiseFutureTimeSync() throws Exception {
    finalizeRecording(false); var inventory = inventory();
    assertTrue(inventory.get("start_time_sec").isJsonNull()); assertTrue(inventory.get("duration_sec").isJsonNull());
    var clock = inventory.getAsJsonObject("clock"); assertEquals("unavailable", clock.get("basis").getAsString());
    assertTrue(clock.get("right_edge_sec").isJsonNull()); assertFalse(clock.get("waiting_for_time_sync").getAsBoolean());
  }

  @Test void identicalDeclarationsAggregateCountsWhileMetadataConflictsRemainExplicit() throws Exception {
    append(10_000_000, 1);
    var extra = file.resolveSibling("rollover.wpilog");
    String metadata = index.live().entries().get("NT:/x").metadata();
    try (var out = new WpilogWriter(extra, "")) {
      int id = out.start("NT:/x", "int64", metadata, 0); out.append(id, 5_000_000, WpilogWriter.encodeInt64(3));
    }
    var entry = new LogFile("rollover.wpilog", LogStore.contentHash(extra), Files.size(extra), "wpilog",
        new Provenance("captured", null, "rollover.wpilog", WHEN, false), true, 5, 5, WHEN, WHEN, "pit_clock", false, null);
    session.getAsJsonArray("files").add(StoreJson.JSON.toJsonTree(entry)); writeManifest();
    var inventory = inventory(); var signal = inventory.getAsJsonArray("entries").get(0).getAsJsonObject();
    assertEquals(2, signal.get("sample_count").getAsLong()); assertEquals(2, signal.getAsJsonArray("member_ids").size());
    assertTrue(inventory.getAsJsonArray("entry_conflicts").isEmpty());
    var update = new JsonObject(); update.addProperty("unit", "changed");
    writer.properties(new org.triplehelix.wpilogmcp.nt4.ControlMessage.Properties("/x", null, update));
    var conflict = inventory(); assertTrue(conflict.getAsJsonArray("entries").isEmpty()); assertEquals(1, conflict.getAsJsonArray("entry_conflicts").size());
  }

  @Test void aggregateMetadataRefusesAtItsCapAndReleasesAllMemberPrefixes() throws Exception {
    for (int n = 2; n < 3002; n++) {
      var properties = new JsonObject(); properties.addProperty("description", "m".repeat(750));
      writer.announce(new Announce("/metadata/" + n, n, "double", null, properties));
    }
    var refusal = assertThrows(java.io.IOException.class, this::inventory);
    assertTrue(refusal.getMessage().contains("RESOURCE_LIMIT"), refusal.toString());
    assertTrue(refusal.getMessage().contains("4 MiB"), refusal.toString());
    writer.close(); writer = null;
    assertTrue(logs.release(file).released(), "Failed inventory cannot retain a native mapping");
    Files.move(file, file.resolveSibling("released.wpilog"));
  }

  @Test void nativeDescriptorPreflightAlsoHasAnExplainedInventoryLimit() throws Exception {
    var properties = new JsonObject(); properties.addProperty("description", "m".repeat(3 << 20));
    writer.announce(new Announce("/oversize", 2, "double", null, properties));
    var refusal = assertThrows(java.io.IOException.class, this::inventory);
    assertTrue(refusal.getMessage().contains("RESOURCE_LIMIT"), refusal.toString());
    assertTrue(refusal.getMessage().contains("4 MiB"), refusal.toString());
  }
}
