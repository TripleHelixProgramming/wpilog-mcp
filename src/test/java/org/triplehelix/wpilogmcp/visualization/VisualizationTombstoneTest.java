/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import static org.junit.jupiter.api.Assertions.*;
import static org.triplehelix.wpilogmcp.visualization.VisualizationServiceTest.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deleted retry history must never occupy the workspace that an agent just released. */
class VisualizationTombstoneTest {
  @TempDir Path temp;
  final MutableClock clock = new MutableClock();
  static final long LIVE_BYTES = 256L << 20;
  static final long RETRY_BYTES = 8L << 20;
  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId zone) { return this; }
    public Instant instant() { return now; }
  }
  JsonObject create(VisualizationService service, JsonObject config) throws Exception {
    return service.create(LOCAL, UUID.randomUUID(), source(), config).get();
  }
  JsonObject delete(VisualizationService service, JsonObject view, UUID request) throws Exception {
    return service.revise(LOCAL, id(view), 1, request, null, false, true).get();
  }
  Path file(ServerIdentity identity, UUID view) { return identity.stateDirectory().resolve("views/" + view + ".json"); }

  @Test void createDeletePastTheLiveCapEvictsOldestRetryRecordsAndSurvivesRestart() throws Exception {
    var identity = ServerIdentity.open(temp.toRealPath().resolve("state"));
    UUID first = null, last = null; var retry = UUID.randomUUID(); JsonObject answer = null;
    try (var service = new VisualizationService(identity, clock, 256, LIVE_BYTES)) {
      for (int i = 0; i < 260; i++) {
        var view = create(service, VisualizationContracts.blank());
        if (first == null) first = id(view);
        last = id(view); answer = delete(service, view, retry); clock.now = clock.now.plusSeconds(1);
      }
      assertTrue(service.all().isEmpty());
      assertFalse(Files.exists(file(identity, first)), "oldest retry record must yield to pressure");
      try (var files = Files.list(identity.stateDirectory().resolve("views"))) { assertEquals(256, files.count()); }
      assertEquals(answer, service.revise(LOCAL, last, 1, retry, null, false, true).get());
      create(service, VisualizationContracts.blank());
      assertEquals(1, service.all().size());
    }
    try (var service = new VisualizationService(identity, clock, 1, LIVE_BYTES)) {
      assertEquals(1, service.all().size(), "tombstones must not count during recovery either");
      assertEquals(answer, service.revise(LOCAL, last, 1, retry, null, false, true).get());
    }
  }

  @Test void deletedBytesDoNotConsumeLiveOrSourceLocatorBudgets() throws Exception {
    var identity = ServerIdentity.open(temp.toRealPath().resolve("state")); UUID live;
    try (var service = new VisualizationService(identity, clock, 10, 8192)) {
      for (int i = 0; i < 8; i++) delete(service, create(service, VisualizationContracts.blank()), UUID.randomUUID());
      var locator = new JsonObject(); locator.addProperty("path", "/synthetic/" + "x".repeat(1000));
      service.rememberSource(LOCAL, UUID.fromString(source().get("source_id").getAsString()), locator).get();
      live = id(create(service, VisualizationContracts.blank()));
      assertEquals(changed(), service.update(LOCAL, live, 1, changed()).get().get("configuration"));
    }
    try (var service = new VisualizationService(identity, clock, 1, 8192)) {
      assertEquals(changed(), service.get(live).get("configuration"));
      assertEquals(1, service.all().size());
    }
  }

  @Test void deleteRetryExpiresAfterSixHoursAndCreateCannotResurrectExpiredIdentity() throws Exception {
    var identity = ServerIdentity.open(temp.toRealPath().resolve("state"));
    var createRequest = UUID.randomUUID(); var deleteRequest = UUID.randomUUID(); UUID view; JsonObject answer;
    var expiry = clock.now.plus(Duration.ofHours(6));
    try (var service = new VisualizationService(identity, clock, 1, LIVE_BYTES)) {
      var created = service.create(LOCAL, createRequest, source(), VisualizationContracts.blank()).get(); view = id(created);
      answer = delete(service, created, deleteRequest);
      assertEquals(expiry.toString(), answer.get("detached_expires_at").getAsString());
    }
    clock.now = expiry.minusSeconds(1);
    try (var service = new VisualizationService(identity, clock, 1, LIVE_BYTES)) {
      assertEquals(answer, service.revise(LOCAL, view, 1, deleteRequest, null, false, true).get());
      clock.now = expiry;
      var failure = assertThrows(ExecutionException.class, () -> service.revise(LOCAL, view, 1, deleteRequest, null, false, true).get());
      assertTrue(failure.getCause().getMessage().contains("VIEW_UNAVAILABLE"));
      var created = service.create(LOCAL, createRequest, source(), VisualizationContracts.blank()).get();
      assertNotEquals(view, id(created)); assertFalse(created.get("deleted").getAsBoolean());
      assertFalse(Files.exists(file(identity, view)));
    }
  }

  @Test void retryBytePressureEvictsOldestBeforeRefusingAndRetainsLiveViews() throws Exception {
    var identity = ServerIdentity.open(temp.toRealPath().resolve("state")); UUID first = null, last = null;
    var config = largeConfiguration(); var retry = UUID.randomUUID(); JsonObject answer = null; UUID live;
    try (var service = new VisualizationService(identity, clock, 2, LIVE_BYTES)) {
      live = id(create(service, VisualizationContracts.blank()));
      for (int i = 0; i < 24; i++) {
        var view = create(service, config); if (first == null) first = id(view);
        last = id(view); answer = delete(service, view, retry); clock.now = clock.now.plusSeconds(1);
      }
      assertFalse(Files.exists(file(identity, first)));
      assertTrue(Files.exists(file(identity, last)));
      assertEquals(live, id(service.get(live)));
      assertRetryBytes(identity);
    }
    try (var service = new VisualizationService(identity, clock, 1, 8192)) {
      assertEquals(live, id(service.get(live)));
      assertEquals(answer, service.revise(LOCAL, last, 1, retry, null, false, true).get());
      assertRetryBytes(identity);
    }
  }

  @Test void recoveryShortensLegacyTombstonesOnceWithoutExtendingTheirDeadline() throws Exception {
    var identity = ServerIdentity.open(temp.toRealPath().resolve("state")); UUID view;
    try (var service = new VisualizationService(identity, clock, 1, LIVE_BYTES)) {
      var created = create(service, VisualizationContracts.blank()); view = id(created);
      delete(service, created, UUID.randomUUID());
    }
    var path = file(identity, view); var record = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    record.addProperty("detached_expires_at", clock.now.plus(Duration.ofDays(30)).toString()); Files.writeString(path, record.toString());
    clock.now = clock.now.plusSeconds(3600); var deadline = clock.now.plus(Duration.ofHours(6));
    try (var service = new VisualizationService(identity, clock, 1, LIVE_BYTES)) {
      assertEquals(deadline.toString(), JsonParser.parseString(Files.readString(path)).getAsJsonObject().get("detached_expires_at").getAsString());
    }
    clock.now = clock.now.plusSeconds(3600);
    try (var service = new VisualizationService(identity, clock, 1, LIVE_BYTES)) {
      assertEquals(deadline.toString(), JsonParser.parseString(Files.readString(path)).getAsJsonObject().get("detached_expires_at").getAsString());
    }
    clock.now = deadline;
    try (var service = new VisualizationService(identity, clock, 1, LIVE_BYTES)) { assertFalse(Files.exists(path)); }
  }

  @Test void recoveryEvictsExcessTombstonesInsteadOfFailingTheWorkspace() throws Exception {
    var identity = ServerIdentity.open(temp.toRealPath().resolve("state")); JsonObject seed;
    try (var service = new VisualizationService(identity, clock, 1, LIVE_BYTES)) {
      var view = create(service, VisualizationContracts.blank()); delete(service, view, UUID.randomUUID());
      seed = JsonParser.parseString(Files.readString(file(identity, id(view)))).getAsJsonObject();
    }
    UUID oldest = null;
    for (int i = 0; i < 260; i++) {
      var key = UUID.randomUUID(); if (oldest == null) oldest = key;
      var record = seed.deepCopy(); record.addProperty("view_id", key.toString());
      record.addProperty("creation_request_id", UUID.randomUUID().toString());
      record.addProperty("detached_expires_at", clock.now.plusSeconds(100 + i).toString());
      Files.writeString(file(identity, key), record.toString());
    }
    try (var service = new VisualizationService(identity, clock, 1, 8192)) {
      assertFalse(Files.exists(file(identity, oldest)));
      try (var files = Files.list(identity.stateDirectory().resolve("views"))) { assertEquals(256, files.count()); }
      assertEquals(1, create(service, VisualizationContracts.blank()).get("revision").getAsInt());
    }
  }

  @Test void realLiveQuotaNamesTheRecordsThatHoldItsSlots() throws Exception {
    try (var service = new VisualizationService(ServerIdentity.ephemeral(), clock, 1, LIVE_BYTES)) {
      var view = create(service, VisualizationContracts.blank());
      service.attach(LOCAL, id(view), UUID.randomUUID()).get();
      var failure = assertThrows(ExecutionException.class, () -> create(service, VisualizationContracts.blank()));
      assertTrue(failure.getCause().getMessage().contains("1 working views"), failure.getCause().getMessage());
      assertTrue(failure.getCause().getMessage().contains("1 attached"), failure.getCause().getMessage());
    }
  }

  @Test void deletedViewsDoNotPinUnusedSourceLocatorsUnderPressure() throws Exception {
    try (var service = new VisualizationService(ServerIdentity.ephemeral(), clock, 1, LIVE_BYTES)) {
      var key = UUID.fromString(source().get("source_id").getAsString());
      var locator = new JsonObject(); locator.addProperty("path", "/synthetic/unused.wpilog");
      service.rememberSource(LOCAL, key, locator).get();
      delete(service, create(service, VisualizationContracts.blank()), UUID.randomUUID());
      for (int i = 0; i < 1024; i++) service.rememberSource(LOCAL, UUID.randomUUID(), locator).get();
      assertThrows(IOException.class, () -> service.sourceLocator(key), "retrying a deleted view needs its receipt, not source bytes");
    }
  }

  @Test void recoveryEnforcesRetryBytesIndependentlyOfTheLiveQuota() throws Exception {
    var identity = ServerIdentity.open(temp.toRealPath().resolve("state")); JsonObject seed;
    try (var service = new VisualizationService(identity, clock, 1, LIVE_BYTES)) {
      var view = create(service, largeConfiguration()); delete(service, view, UUID.randomUUID());
      seed = JsonParser.parseString(Files.readString(file(identity, id(view)))).getAsJsonObject();
    }
    UUID oldest = null;
    for (int i = 0; i < 24; i++) {
      var key = UUID.randomUUID(); if (oldest == null) oldest = key;
      var record = seed.deepCopy(); record.addProperty("view_id", key.toString());
      record.addProperty("creation_request_id", UUID.randomUUID().toString());
      // Fractional and whole seconds must order by time, not by their ISO string spelling.
      record.addProperty("detached_expires_at", clock.now.plusMillis(100000 + 500L * i).toString());
      Files.writeString(file(identity, key), record.toString());
    }
    try (var service = new VisualizationService(identity, clock, 1, 8192)) {
      assertFalse(Files.exists(file(identity, oldest)));
      assertRetryBytes(identity);
      assertEquals(1, create(service, VisualizationContracts.blank()).get("revision").getAsInt());
    }
  }

  static JsonObject largeConfiguration() {
    var config = VisualizationContracts.blank();
    for (int i = 0; i < 100; i++) {
      var requirement = JsonParser.parseString("{\"selector_kind\":\"entry\",\"field_path\":null,\"unit\":null,\"value_type\":\"number\",\"accepted_entry_types\":[\"double\"]}").getAsJsonObject();
      requirement.addProperty("id", "r" + i); requirement.addProperty("entry", "/" + "x".repeat(900) + i);
      config.getAsJsonArray("requirements").add(requirement);
    }
    return config;
  }
  static void assertRetryBytes(ServerIdentity identity) throws IOException {
    long bytes = 0;
    try (var files = Files.list(identity.stateDirectory().resolve("views"))) {
      for (var path : files.toList()) if (JsonParser.parseString(Files.readString(path)).getAsJsonObject().get("deleted").getAsBoolean()) bytes += Files.size(path);
    }
    assertTrue(bytes <= RETRY_BYTES, "retry records use " + bytes + " bytes, exceeding 8 MiB");
  }
}
