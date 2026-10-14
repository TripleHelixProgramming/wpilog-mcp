/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.mcp.CallerContext.Authority;

class VisualizationServiceTest {
  @TempDir Path temp;
  static final Authority LOCAL = Authority.STDIO_LOCAL;
  static JsonObject source() {
    var json = new JsonObject(); json.addProperty("kind", "unmanaged");
    json.addProperty("source_id", "00000000-0000-4000-8000-000000000001"); return json;
  }
  static UUID id(JsonObject view) { return UUID.fromString(view.get("view_id").getAsString()); }
  static JsonObject changed() {
    var config = VisualizationContracts.blank(); config.getAsJsonObject("links").addProperty("cursor", false); return config;
  }

  @Test void independentViewsSurviveRestartAndRejectStaleUpdates() throws Exception {
    var identity = ServerIdentity.open(temp.toRealPath().resolve("state")); UUID a, b;
    try (var service = new VisualizationService(identity)) {
      a = id(service.create(LOCAL, UUID.randomUUID(), source(), VisualizationContracts.blank()).get());
      b = id(service.create(LOCAL, UUID.randomUUID(), source(), VisualizationContracts.blank()).get());
      assertNotEquals(a, b);
      assertEquals(2, service.update(LOCAL, a, 1, changed()).get().get("revision").getAsLong());
      var failure = assertThrows(ExecutionException.class, () -> service.update(LOCAL, a, 1, VisualizationContracts.blank()).get());
      assertTrue(failure.getCause().getMessage().contains("REVISION_CONFLICT"));
      assertEquals(VisualizationContracts.blank(), service.get(b).get("configuration"));
    }
    try (var restored = new VisualizationService(ServerIdentity.open(identity.stateDirectory()))) {
      assertEquals(changed(), restored.get(a).get("configuration"));
      assertEquals(2, restored.list(source()).size());
    }
  }

  @Test void foreignCallerCannotCreateStateAndReturnedObjectsCannotMutateIt() throws Exception {
    try (var service = new VisualizationService(ServerIdentity.open(temp.toRealPath().resolve("state")))) {
      var failure = assertThrows(ExecutionException.class, () -> service.create(Authority.UNTRUSTED, UUID.randomUUID(), source(), VisualizationContracts.blank()).get());
      assertInstanceOf(java.nio.file.AccessDeniedException.class, failure.getCause());
      assertTrue(service.list(source()).isEmpty());
      var view = service.create(LOCAL, UUID.randomUUID(), source(), VisualizationContracts.blank()).get();
      var key = id(view); view.getAsJsonObject("configuration").addProperty("samples", "bad");
      assertEquals(VisualizationContracts.blank(), service.get(key).get("configuration"));
    }
  }

  @Test void lostCreateReplyReplaysSameIdentityAndRejectsReusedIntent() throws Exception {
    var identity = ServerIdentity.open(temp.toRealPath().resolve("state")); var request = UUID.randomUUID(); UUID key;
    try (var service = new VisualizationService(identity)) {
      key = id(service.create(LOCAL, request, source(), VisualizationContracts.blank()).get());
    }
    try (var service = new VisualizationService(identity)) {
      assertEquals(key, id(service.create(LOCAL, request, source(), VisualizationContracts.blank()).get()));
      var failure = assertThrows(ExecutionException.class, () -> service.create(LOCAL, request, source(), changed()).get());
      assertTrue(failure.getCause().getMessage().contains("REQUEST_CONFLICT"));
    }
  }

  @Test void quotasPreserveUnexpiredDraftsButExpiredDetachedDraftsCanBePruned() throws Exception {
    var identity = ServerIdentity.open(temp.toRealPath().resolve("state"));
    var now = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    try (var service = new VisualizationService(identity, now, 1, 8192)) {
      service.create(LOCAL, UUID.randomUUID(), source(), VisualizationContracts.blank()).get();
      var failure = assertThrows(ExecutionException.class, () -> service.create(LOCAL, UUID.randomUUID(), source(), VisualizationContracts.blank()).get());
      assertTrue(failure.getCause().getMessage().contains("WORKSPACE_LIMIT"));
      assertEquals(1, service.list(source()).size());
    }
    try (var service = new VisualizationService(identity, Clock.offset(now, Duration.ofDays(31)), 1, 8192)) {
      service.create(LOCAL, UUID.randomUUID(), source(), VisualizationContracts.blank()).get();
      assertEquals(1, service.list(source()).size());
    }
  }

  @Test void onlyOneWindowAttachesAndAConflictCannotAdvanceTheCheckpoint() throws Exception {
    try (var service = new VisualizationService(ServerIdentity.ephemeral())) {
      var view = service.create(LOCAL, UUID.randomUUID(), source(), VisualizationContracts.blank()).get();
      var key = id(view); var client = UUID.randomUUID();
      assertTrue(service.attach(LOCAL, key, client).get().get("detached_expires_at").isJsonNull());
      var failure = assertThrows(ExecutionException.class, () -> service.attach(LOCAL, key, UUID.randomUUID()).get());
      assertTrue(failure.getCause().getMessage().contains("VIEW_ATTACHED"));
      service.update(LOCAL, key, 1, changed()).get();
      var ref = new JsonObject(); ref.addProperty("store_id", UUID.randomUUID().toString());
      ref.addProperty("saved_view_id", UUID.randomUUID().toString()); ref.addProperty("revision", 1);
      assertThrows(ExecutionException.class, () -> service.checkpoint(LOCAL, key, 1, ref).get());
      assertTrue(service.get(key).get("saved_view_ref").isJsonNull());
      var saved = service.checkpoint(LOCAL, key, 2, ref).get();
      assertFalse(saved.get("has_unsaved_changes").getAsBoolean());
      assertFalse(service.detach(LOCAL, key, client).get().get("detached_expires_at").isJsonNull());
    }
  }

  @Test void persistentIdentityUsesTheSharedConfigurationKey() throws Exception {
    var home = temp.toRealPath(); var config = Files.writeString(home.resolve("servers.yaml"), "servers: {}\n");
    var identity = ServerIdentity.open(home, config, "fixture");
    assertEquals(identity.id(), ServerIdentity.open(home, config, "fixture").id());
    assertNotEquals(identity.id(), ServerIdentity.open(home, config, "another").id());
    var digest = java.security.MessageDigest.getInstance("SHA-256").digest((config.toRealPath() + "\nfixture").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(java.util.HexFormat.of().formatHex(digest), identity.stateDirectory().getFileName().toString());
  }

  @Test void corruptAndUnknownRecordsAreQuarantinedWithoutLosingValidViews() throws Exception {
    var identity = ServerIdentity.open(temp.toRealPath().resolve("state")); UUID valid;
    try (var service = new VisualizationService(identity)) {
      valid = id(service.create(LOCAL, UUID.randomUUID(), source(), changed()).get());
    }
    var views = identity.stateDirectory().resolve("views");
    var corrupt = Files.writeString(views.resolve(UUID.randomUUID() + ".json"), "{broken");
    var future = Files.writeString(views.resolve(UUID.randomUUID() + ".json"), "{\"schema_version\":99}");
    var unknown = Files.writeString(views.resolve("unexpected.txt"), "preserve unknown bytes");
    try (var service = new VisualizationService(identity)) {
      assertEquals(changed(), service.get(valid).get("configuration"));
      assertEquals(2, service.update(LOCAL, valid, 1, VisualizationContracts.blank()).get().get("revision").getAsLong());
      assertEquals(1, service.list(source()).size());
      assertFalse(Files.exists(corrupt)); assertFalse(Files.exists(future)); assertFalse(Files.exists(unknown));
      try (var quarantined = Files.walk(identity.stateDirectory().resolve("quarantine"))) {
        var contents = quarantined.filter(Files::isRegularFile).map(p -> {
          try { return Files.readString(p); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        }).toList();
        assertEquals(java.util.Set.of("{broken", "{\"schema_version\":99}", "preserve unknown bytes"), java.util.Set.copyOf(contents));
      }
    }
  }

  @Test void updatingAnAttachedViewPrunesExpiredDraftsBeforeCheckingBytes() throws Exception {
    var now = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    var clock = new Clock() {
      public ZoneId getZone() { return ZoneOffset.UTC; }
      public Clock withZone(ZoneId zone) { return this; }
      public Instant instant() { return now.get(); }
    };
    var identity = ServerIdentity.open(temp.toRealPath().resolve("state")); UUID active, expired; var client = UUID.randomUUID();
    try (var service = new VisualizationService(identity, clock, 10, MAX_BYTES_FOR_SETUP)) {
      active = id(service.create(LOCAL, UUID.randomUUID(), source(), VisualizationContracts.blank()).get());
      expired = id(service.create(LOCAL, UUID.randomUUID(), source(), VisualizationContracts.blank()).get());
      service.attach(LOCAL, active, client).get();
    }
    var activeFile = identity.stateDirectory().resolve("views/" + active + ".json");
    var expiredFile = identity.stateDirectory().resolve("views/" + expired + ".json");
    long budget = Files.size(activeFile) + Files.size(expiredFile) + 4096 + 1;
    try (var service = new VisualizationService(identity, clock, 10, budget)) {
      service.attach(LOCAL, active, client).get(); now.set(now.get().plus(Duration.ofDays(31)));
      var config = changed(); var range = config.getAsJsonObject("time").getAsJsonObject("recorded");
      range.addProperty("mode", "fixed"); range.addProperty("start_sec", 0); range.addProperty("end_sec", 60);
      assertEquals(config, service.update(LOCAL, active, 1, config).get().get("configuration"));
      assertFalse(Files.exists(expiredFile), "Expired bytes must leave the budget without a create request");
    }
  }
  private static final long MAX_BYTES_FOR_SETUP = 1024 * 1024;
}
