/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.CallerContext.Authority;
import org.triplehelix.wpilogmcp.read.ReadQuery;
import org.triplehelix.wpilogmcp.store.*;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

/** Equal content on a master and mirror never makes their serving scopes interchangeable. */
class QualifiedSourcesTest {
  @TempDir Path temp;
  static final String WHEN = "2026-01-01T00:00:00Z";
  static final String MASTER = "11111111-1111-4111-8111-111111111111";
  static final String MIRROR = "22222222-2222-4222-8222-222222222222";
  static final String SESSION = "33333333-3333-4333-8333-333333333333";
  final LogManager logs = LogManager.getInstance();
  Set<Path> oldAllowed;
  final AtomicInteger hashes = new AtomicInteger();
  VisualizationService views;
  RecordedSources sources;
  Path master, mirror;
  @BeforeEach void start() throws Exception {
    oldAllowed = logs.getConfiguredDirectories(); logs.clearAllowedDirectories();
    master = store("master", MASTER, false); mirror = store("mirror", MIRROR, true);
    logs.addAllowedDirectory(master); logs.addAllowedDirectory(mirror);
    views = new VisualizationService(ServerIdentity.ephemeral());
    sources = new RecordedSources(logs, views, new StoreDoor(logs::getAllowedDirectories), file -> {
      hashes.incrementAndGet(); return LogStore.contentHash(file);
    });
  }
  @AfterEach void stop() throws Exception {
    if (sources != null) sources.close(); if (views != null) views.close();
    logs.addAllowedDirectory(temp); logs.release(temp); logs.clearAllowedDirectories(); oldAllowed.forEach(logs::addAllowedDirectory);
  }
  private Path store(String name, String id, boolean replica) throws Exception {
    var root = Files.createDirectory(temp.resolve(name)).toRealPath();
    var robot = Files.createDirectories(root.resolve("robots/synthetic"));
    var directory = Files.createDirectories(robot.resolve("sessions/2026-01-01/session"));
    var header = new Header(1, WHEN, id, List.of());
    if (replica) header = header.withOrigin(new MirrorOrigin(MASTER, "http://master.invalid", WHEN, List.of(), Map.of()));
    Files.writeString(root.resolve("store.json"), StoreJson.JSON.toJson(header));
    Files.writeString(robot.resolve("robot.json"), StoreJson.JSON.toJson(new Robot("synthetic", "synthetic", null, null, "logged")));
    var files = new ArrayList<LogFile>();
    for (int member = 0; member < 2; member++) {
      String filename = "member-" + member + ".wpilog"; var path = directory.resolve(filename);
      try (var writer = new WpilogWriter(path, "")) {
        int entry = writer.start("value", "int64", "", 0);
        writer.append(entry, (member + 1) * 1_000_000L, WpilogWriter.encodeInt64((member + 1) * 42));
      }
      files.add(new LogFile(filename, LogStore.contentHash(path), Files.size(path), "wpilog",
          new Provenance("captured", null, filename, WHEN, false), true, member + 1, member + 1, WHEN, WHEN, "pit_clock", false, null));
    }
    Files.writeString(directory.resolve("session.json"), StoreJson.JSON.toJson(new Session(SESSION, WHEN, WHEN,
        "pit_clock", null, null, null, null, files)));
    return root;
  }
  private Path first(Path root) { return root.resolve("robots/synthetic/sessions/2026-01-01/session/member-0.wpilog"); }
  private JsonObject recording(String scope) {
    var legacy = JsonParser.parseString("""
        {"kind":"session","origin_store_id":"11111111-1111-4111-8111-111111111111",
         "session_id":"33333333-3333-4333-8333-333333333333",
         "anchor_member":{"original_name":"member-0.wpilog","recording_started_at":"2026-01-01T00:00:00Z"}}
        """).getAsJsonObject();
    return VisualizationContracts.qualify(views.serverId(), scope, legacy);
  }

  @Test void qualifiedContentAndRecordingStayOnMirrorAndShareHashMemory() throws Exception {
    var identified = sources.identify(Authority.STDIO_LOCAL, first(mirror), MIRROR, true);
    var ref = identified.getAsJsonObject("source_ref");
    assertEquals(MIRROR, ref.get("scope_id").getAsString());
    assertEquals(first(mirror), sources.resolve(ref)); assertEquals(1, hashes.get());
    try (var admission = sources.admitRecording(recording(MIRROR))) {
      assertEquals(2, admission.prefix().members().size());
      var values = new ArrayList<Object>();
      for (var member : admission.prefix().members()) {
        assertTrue(member.snapshot().path().startsWith(mirror));
        try (var cursor = member.snapshot().read(ReadQuery.all("value"))) {
          cursor.next().samples().forEach(sample -> values.add(sample.value()));
        }
      }
      assertEquals(List.of(42L, 84L), values); admission.check();
    }
    assertEquals(2, hashes.get(), "The single-file verification is reused by recording admission");
    sources.describe(recording(MIRROR)); assertEquals(2, hashes.get());
  }

  @Test void foreignOwnersWrongScopesAndRevokedGrantsCannotUseAnotherCopy() throws Exception {
    var ref = sources.identify(Authority.STDIO_LOCAL, first(mirror), MIRROR, true).getAsJsonObject("source_ref");
    var foreign = ref.deepCopy(); foreign.addProperty("server_instance_id", UUID.randomUUID().toString());
    assertThrows(IOException.class, () -> sources.resolve(foreign)); assertEquals(0, hashes.get());
    var wrong = ref.deepCopy(); wrong.addProperty("scope_id", UUID.randomUUID().toString());
    assertThrows(IOException.class, () -> sources.resolve(wrong));
    try (var admission = sources.admitRecording(recording(MIRROR))) {
      logs.clearAllowedDirectories(); logs.addAllowedDirectory(master);
      assertThrows(IOException.class, admission.access()::check);
      assertThrows(IOException.class, () -> sources.resolve(ref));
    }
  }

  @Test void legacyAmbiguityNeedsChoiceAndExplicitIdentitySurvivesStoreRename() throws Exception {
    var ref = sources.identify(Authority.STDIO_LOCAL, first(mirror), MIRROR, true).getAsJsonObject("source_ref");
    var legacy = VisualizationContracts.content(ref);
    assertTrue(assertThrows(IOException.class, () -> sources.qualify(legacy, null)).getMessage().startsWith("SOURCE_SELECTION_REQUIRED"));
    assertEquals(ref, sources.qualify(legacy, MIRROR));
    var renamed = mirror.resolveSibling("renamed"); Files.move(mirror, renamed);
    logs.clearAllowedDirectories(); logs.addAllowedDirectory(renamed); logs.addAllowedDirectory(master);
    assertEquals(first(renamed), sources.resolve(ref));
  }

  @Test void cachedInventoryPagesRemainPinnedAndDetailsReuseVerifiedHashes() throws Exception {
    var opens = new AtomicInteger();
    var client = new RecordingInventories.Client(Authority.STDIO_LOCAL, "synthetic-client", () -> true);
    try (var inventory = new RecordingInventories(ref -> { opens.incrementAndGet(); return sources.admitRecording(ref); },
        RecordingInventories.Limits.defaults(), System::nanoTime)) {
      var opened = inventory.open(client, recording(MIRROR));
      var request = new JsonObject(); request.add("inventory_id", opened.get("inventory_id"));
      var page = inventory.list(client, request);
      for (int i = 0; i < 5; i++) assertEquals(page, inventory.list(client, request));
      assertEquals(1, opens.get()); assertEquals(2, hashes.get());
      var detail = request.deepCopy(); var ids = new JsonArray();
      page.getAsJsonArray("entries").forEach(row -> ids.add(row.getAsJsonObject().get("row_id"))); detail.add("row_ids", ids);
      inventory.details(client, detail); assertEquals(2, opens.get()); assertEquals(2, hashes.get());
      logs.clearAllowedDirectories(); logs.addAllowedDirectory(master);
      assertThrows(Exception.class, () -> inventory.list(client, request));
      inventory.release(client, request); assertEquals(0, inventory.retainedHandles());
    }
  }

  @Test void recordingAccessAndFollowPollsDoNotRepeatScopeDiscovery() throws Exception {
    try (var admission = sources.admitRecording(recording(MIRROR))) {
      long before = logs.sourceScopes().discoveryWalks();
      for (int i = 0; i < 5; i++) { admission.access().check(); admission.continuation().authorize(); }
      assertEquals(before, logs.sourceScopes().discoveryWalks(), "Cached pages and follow authorization must use bounded scope checks");
      Files.writeString(mirror.resolve("store.json"), StoreJson.JSON.toJson(new Header(1, WHEN, UUID.randomUUID().toString(), List.of())));
      assertThrows(IOException.class, admission.access()::check, "A replacement store cannot inherit the selected scope's access");
    }
  }

  @Test void aNewStoreBetweenARecordingAndItsRootRevokesItsAccessTicket() throws Exception {
    try (var admission = sources.admitRecording(recording(MIRROR))) {
      var nested = first(mirror).getParent().resolve("store.json");
      Files.writeString(nested, StoreJson.JSON.toJson(new Header(1, WHEN, UUID.randomUUID().toString(), List.of())));
      assertThrows(IOException.class, admission.access()::check, "An admitted member must still belong to its selected store");
    }
  }

  @Test void qualifiedUnmanagedViewAndLocatorSurviveRestartOnTheSameServer() throws Exception {
    var directory = Files.createDirectory(temp.resolve("ordinary")).toRealPath();
    var file = Files.copy(first(master), directory.resolve("same.wpilog")); logs.addAllowedDirectory(directory);
    var identity = ServerIdentity.open(temp.toRealPath().resolve("durable-state")); JsonObject reference; UUID view;
    try (var state = new VisualizationService(identity);
         var reader = new RecordedSources(logs, state, new StoreDoor(logs::getAllowedDirectories))) {
      reference = reader.identify(Authority.STDIO_LOCAL, file, null, true).getAsJsonObject("source_ref");
      var created = state.create(Authority.STDIO_LOCAL, UUID.randomUUID(), reference, VisualizationContracts.blank()).get();
      view = UUID.fromString(created.get("view_id").getAsString());
    }
    try (var state = new VisualizationService(ServerIdentity.open(identity.stateDirectory()));
         var reader = new RecordedSources(logs, state, new StoreDoor(logs::getAllowedDirectories))) {
      assertEquals(reference, state.get(view).get("source_ref")); assertEquals(file, reader.resolve(reference));
      assertThrows(IllegalArgumentException.class, () -> state.requireSourceVersion(view, 1));
      state.requireSourceVersion(view, 2);
    }
  }
}
