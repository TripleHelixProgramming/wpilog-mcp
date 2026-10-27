/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.*;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.CallerContext.Authority;
import org.triplehelix.wpilogmcp.store.*;

class RecordingInventoriesTest {
  @TempDir Path temp;
  final LogManager logs = LogManager.getInstance();
  final AtomicLong clock = new AtomicLong(); final AtomicInteger opens = new AtomicInteger(), hashes = new AtomicInteger();
  final AtomicBoolean alive = new AtomicBoolean(true);
  final RecordingInventories.Client client = new RecordingInventories.Client(Authority.HTTP_LOCAL, "fixture", alive::get);
  Set<Path> original; VisualizationService views; RecordedSources sources; RecordingInventories inventories; RecordingFixture fixture;
  @BeforeEach void start() throws Exception {
    temp = temp.toRealPath(); original = logs.getConfiguredDirectories(); logs.clearAllowedDirectories(); logs.addAllowedDirectory(temp);
    fixture = new RecordingFixture(temp.resolve("store"), 8);
    views = new VisualizationService(ServerIdentity.ephemeral());
    sources = new RecordedSources(logs, views, new StoreDoor(logs::getAllowedDirectories), path -> { hashes.incrementAndGet(); return LogStore.contentHash(path); });
    inventories = service(RecordingInventories.Limits.defaults());
  }
  RecordingInventories service(RecordingInventories.Limits limits) {
    return new RecordingInventories(ref -> { opens.incrementAndGet(); return sources.admitRecording(ref); }, limits, clock::get);
  }
  @AfterEach void stop() throws Exception {
    inventories.close(); sources.close(); views.close(); logs.addAllowedDirectory(temp); logs.release(temp);
    logs.clearAllowedDirectories(); original.forEach(logs::addAllowedDirectory);
  }
  JsonObject open() throws Exception { return inventories.open(client, fixture.reference); }
  static JsonObject id(JsonObject inventory) { var request = new JsonObject(); request.add("inventory_id", inventory.get("inventory_id")); return request; }
  JsonObject list(JsonObject inventory) throws Exception { return inventories.list(client, id(inventory)); }
  JsonObject details(JsonObject inventory, JsonObject page) throws Exception {
    var request = id(inventory); var ids = new JsonArray(); page.getAsJsonArray("entries").forEach(row -> ids.add(row.getAsJsonObject().get("row_id")));
    request.add("row_ids", ids); return inventories.details(client, request);
  }
  static void await(java.util.function.BooleanSupplier done) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!done.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
    assertTrue(done.getAsBoolean(), "Worker did not unwind before test deadline");
  }

  @Test void healthRefusesMoreThan64ManifestedMembersWithoutReadingTheirPayloads() throws Exception {
    var path = fixture.directory.resolve("session.json"); var manifest = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    var files = manifest.getAsJsonArray("files");
    for (int i = 2; i < 65; i++) {
      var copy = files.get(0).getAsJsonObject().deepCopy(); String name = "member-" + i + ".wpilog";
      Files.copy(fixture.directory.resolve("capture.wpilog"), fixture.directory.resolve(name));
      copy.addProperty("path", name); copy.getAsJsonObject("provenance").addProperty("original_name", name); files.add(copy);
    }
    Files.writeString(path, manifest.toString()); logs.catalogPublished();
    assertTrue(assertThrows(Exception.class, () -> sources.health(fixture.reference, null)).getMessage().contains("64-member"));
    assertEquals(0, hashes.get());
  }

  @Test void healthRechecksCatalogAndGrantsWithoutOpeningOrHashingData() throws Exception {
    for (int i = 0; i < 5; i++) {
      var health = sources.health(fixture.reference, null);
      assertEquals("inactive", health.get("acquisition_state").getAsString());
      assertEquals("finalized", health.get("history_state").getAsString());
      assertTrue(health.get("connected").isJsonNull()); assertTrue(health.get("publication_timing").isJsonNull());
    }
    assertEquals(0, opens.get()); assertEquals(0, hashes.get()); assertTrue(logs.release(temp).released());
    logs.clearAllowedDirectories(); assertThrows(Exception.class, () -> sources.health(fixture.reference, null));
  }

  @Test void pagesDoNotReopenOrRehashButDetailsReadFreshEvidence() throws Exception {
    var inventory = open(); assertEquals(1, opens.get()); assertEquals(2, hashes.get());
    assertTrue(logs.release(temp).released());
    for (int i = 0; i < 20; i++) assertEquals(8, list(inventory).get("total_matching").getAsInt());
    assertEquals(1, opens.get()); assertEquals(2, hashes.get());
    var detail = details(inventory, list(inventory)); assertEquals(2, opens.get()); assertEquals(2, hashes.get());
    assertFalse(detail.get("counts_grew_since_inventory").getAsBoolean());
    assertTrue(logs.release(temp).released());
    inventories.release(client, id(inventory)); assertEquals(0, inventories.retainedBytes()); assertEquals(0, inventories.retainedHandles());
  }

  @Test void expiryRenewsOnlyIdleTimeAndOldEpochsNeverCrossRestart() throws Exception {
    var inventory = open(); long allocated = inventories.retainedBytes(); assertTrue(allocated > 0);
    for (int i = 1; i < 6; i++) { clock.set(Duration.ofSeconds(50L * i).toNanos()); list(inventory); }
    clock.set(Duration.ofMinutes(5).toNanos());
    var failure = assertThrows(InventoryFailure.class, () -> list(inventory)); assertEquals("INVENTORY_EXPIRED", failure.code);
    assertEquals(0, inventories.retainedBytes()); inventories.release(client, id(inventory));
    var expired = open();
    clock.addAndGet(Duration.ofSeconds(60).toNanos()); inventories.expire(); assertEquals(0, inventories.retainedHandles());
    assertThrows(InventoryFailure.class, () -> list(expired));
    inventories.close(); inventories = service(RecordingInventories.Limits.defaults());
    assertEquals(410, assertThrows(InventoryFailure.class, () -> list(expired)).status);
  }

  @Test void responseReservationsLastUntilTransportFinishesAndCloseBypassesThem() throws Exception {
    var inventory = open();
    try (var transport = InventoryResponses.open()) {
      for (int i = 0; i < 4; i++) list(inventory);
      assertEquals(8 << 20, inventories.responseBytes());
      var failure = assertThrows(InventoryFailure.class, () -> list(inventory)); assertTrue(failure.retryable);
      assertEquals(413, failure.status); inventories.release(client, id(inventory)); assertEquals(0, inventories.retainedBytes());
      assertEquals(8 << 20, inventories.responseBytes());
    }
    assertEquals(0, inventories.responseBytes());
  }

  @Test void aFailedBuildReleasesItsWholeReservationAndPublishesNothing() throws Exception {
    inventories.close(); var defaults = RecordingInventories.Limits.defaults();
    inventories = service(new RecordingInventories.Limits(1024, 2048, 8, 2, defaults.idleNanos(), defaults.lifetimeNanos(), defaults.buildNanos()));
    var failure = assertThrows(InventoryFailure.class, this::open); assertEquals(413, failure.status); assertFalse(failure.retryable);
    assertEquals(0, inventories.retainedBytes()); assertEquals(0, inventories.retainedHandles()); assertTrue(logs.release(temp).released());
  }

  @Test void aTimedOutOsReadKeepsItsReservationUntilItUnwindsAndCannotPublish() throws Exception {
    inventories.close(); var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
    inventories = new RecordingInventories(ref -> {
      var admission = sources.admitRecording(ref); entered.countDown();
      boolean done = false; while (!done) try { release.await(); done = true; } catch (InterruptedException ignored) { /* Model an uninterruptible disk read. */ }
      return admission;
    // The deadline must outlast the admission before the stall: a timeout interrupts the builder
    // wherever it is, and admission takes longer than 80 ms on Windows runners.
    }, new RecordingInventories.Limits(32L << 20, 64L << 20, 8, 2, Duration.ofMinutes(1).toNanos(), Duration.ofMinutes(5).toNanos(), Duration.ofSeconds(2).toNanos()), System::nanoTime);
    try {
      var failure = assertThrows(InventoryFailure.class, this::open); assertTrue(failure.retryable);
      assertTrue(entered.await(1, TimeUnit.SECONDS), "Admission finished before the build deadline");
      assertEquals(32L << 20, inventories.retainedBytes()); assertEquals(1, inventories.retainedHandles());
      assertFalse(logs.release(temp).released(), "Uninterruptible admission still owns its prefix");
    } finally { release.countDown(); }
    await(() -> inventories.retainedHandles() == 0); assertEquals(0, inventories.retainedBytes()); assertTrue(logs.release(temp).released());
  }

  @Test void aBuildFinishingAfterItsDeadlineReportsRetryablePressureNotHandleExpiry() throws Exception {
    inventories.close(); inventories = new RecordingInventories(ref -> {
      var admission = sources.admitRecording(ref); clock.set(RecordingInventories.BUILD_DEADLINE.toNanos()); return admission;
    }, RecordingInventories.Limits.defaults(), clock::get);
    var failure = assertThrows(InventoryFailure.class, this::open);
    assertEquals(413, failure.status); assertTrue(failure.retryable); assertEquals(0, inventories.retainedBytes());
    assertTrue(logs.release(temp).released());
  }

  @Test void sessionRemovalDuringBuildNeverPublishesOrHoldsTheRemovalLockOverIo() throws Exception {
    inventories.close(); var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
    inventories = new RecordingInventories(ref -> {
      var admission = sources.admitRecording(ref); entered.countDown();
      try { release.await(); } catch (InterruptedException interrupted) { admission.close(); throw new java.io.InterruptedIOException(); }
      return admission;
    }, RecordingInventories.Limits.defaults(), System::nanoTime);
    var result = CompletableFuture.supplyAsync(() -> { try { return open(); } catch (Exception e) { throw new CompletionException(e); } });
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS)); long started = System.nanoTime(); alive.set(false); inventories.releaseSession(client.id());
      assertTrue(System.nanoTime() - started < Duration.ofMillis(100).toNanos());
      assertEquals(32L << 20, inventories.retainedBytes());
    } finally { release.countDown(); }
    var failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
    assertInstanceOf(InventoryFailure.class, failure.getCause()); assertEquals(410, ((InventoryFailure) failure.getCause()).status);
    await(() -> inventories.retainedBytes() == 0);
    assertTrue(logs.release(temp).released());
  }

  @Test void largeMetadataIsNotRetainedAndDoesNotHideTheName() throws Exception {
    fixture.rewrite(0, writer -> writer.start("/large", "int64", "m".repeat(3 << 20), 0));
    var inventory = open(); var page = list(inventory);
    assertEquals(0, page.get("unavailable_entry_names").getAsInt());
    assertTrue(page.getAsJsonArray("entries").asList().stream().anyMatch(row -> row.getAsJsonObject().get("name").getAsString().equals("/large")));
    assertTrue(inventories.retainedBytes() < 3 << 20, "Detached state must not retain the large metadata string");
    try (var admission = sources.admitRecording(fixture.reference)) {
      var detached = DetachedInventory.build(fixture.reference, admission, RecordingInventories.INVENTORY_BYTES, () -> false);
      for (var row : detached.rows) for (var declaration : row.declarations())
        assertTrue(declaration.metadata().matches("[0-9a-f]{64}"), "Only a fixed-size metadata digest may be retained");
    }
    var request = id(inventory); request.addProperty("query", "/large"); var selected = inventories.list(client, request);
    var failure = assertThrows(InventoryFailure.class, () -> details(inventory, selected)); assertFalse(failure.retryable); assertEquals(413, failure.status);
    assertEquals(page, list(inventory)); assertTrue(logs.release(temp).released());
  }

  @Test void oversizedNamesRemainExplicitlyUnavailableBesideUsableRows() throws Exception {
    fixture.rewrite(0, writer -> writer.start("z".repeat(2 << 20), "int64", "", 0));
    var inventory = open(); var page = list(inventory); assertEquals(7, page.get("total_entry_names").getAsInt());
    assertEquals(1, page.get("unavailable_entry_names").getAsInt()); assertEquals(6, page.get("total_matching").getAsInt());
    var request = id(inventory); request.addProperty("unavailable", true); var unavailable = inventories.list(client, request);
    assertEquals(1, unavailable.get("total_matching").getAsInt()); var row = unavailable.getAsJsonArray("entries").get(0).getAsJsonObject();
    assertFalse(row.has("name")); assertEquals(128, row.get("display_preview").getAsString().length()); assertEquals(2 << 20, row.get("name_length_utf16").getAsInt());
    assertFalse(assertThrows(InventoryFailure.class, () -> details(inventory, unavailable)).retryable);
  }

  @Test void conflictsKeepPerMemberCountsAndUnicodeFiltersUseExactTypes() throws Exception {
    fixture.rewrite(0, writer -> { writer.start("/İĞ", "double", "", 0); writer.start("/conflict", "int64", "one", 0); });
    fixture.rewrite(1, writer -> { writer.start("/İĞ", "double", "", 0); writer.start("/conflict", "double", "two", 0); });
    var inventory = open(); var page = list(inventory); assertEquals(1, inventory.get("conflicting_entry_names").getAsInt());
    var conflict = page.getAsJsonArray("entries").get(0).getAsJsonObject(); assertTrue(conflict.get("conflict").getAsBoolean()); assertTrue(conflict.get("sample_count").isJsonNull());
    assertEquals(2, conflict.getAsJsonArray("members").size());
    var request = id(inventory); request.addProperty("query", "i̇ğ"); request.add("types", JsonParser.parseString("[\"double\"]"));
    assertEquals(1, inventories.list(client, request).get("total_matching").getAsInt());
    request.add("types", JsonParser.parseString("[\"numeric\"]")); assertEquals(0, inventories.list(client, request).get("total_matching").getAsInt());
    var details = details(inventory, page); assertTrue(details.getAsJsonArray("selected_rows").get(0).getAsJsonObject().get("conflict").getAsBoolean());
  }

  @Test void selectedDetailsCarryOnlyTransitiveSchemasAndExplicitProblems() throws Exception {
    fixture.rewrite(0, writer -> {
      writer.append(writer.start("/.schema/struct:Inner", "structschema", "", 0), 0, "double x;".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      writer.append(writer.start("/.schema/struct:Outer", "structschema", "", 0), 0, "Inner nested;".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      writer.append(writer.start("/.schema/struct:Unused", "structschema", "", 0), 0, "double y;".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      writer.start("/selected", "struct:Outer", "", 0); writer.start("/missing", "struct:Missing", "", 0);
    });
    var inventory = open(); var request = id(inventory); request.addProperty("query", "/selected");
    var detail = details(inventory, inventories.list(client, request));
    var descriptor = detail.getAsJsonArray("descriptors").asList().stream().map(JsonElement::getAsJsonObject)
        .filter(d -> d.get("member_id").getAsString().equals("capture.wpilog")).findFirst().orElseThrow().getAsJsonObject("snapshot");
    assertEquals(Set.of("Inner", "Outer"), descriptor.getAsJsonArray("schemas").asList().stream().map(s -> s.getAsJsonObject().get("name").getAsString()).collect(java.util.stream.Collectors.toSet()));
    request.addProperty("query", "/missing"); detail = details(inventory, inventories.list(client, request));
    assertEquals("MISSING_SCHEMA", detail.getAsJsonArray("schema_problems").get(0).getAsJsonObject().get("code").getAsString());
  }

  @Test void invalidAndCrossMemberSchemaProblemsStayExplicit() throws Exception {
    for (int member = 0; member < 2; member++) {
      String schema = member == 0 ? "double x;" : "int64 x;";
      fixture.rewrite(member, writer -> {
        writer.append(writer.start("/.schema/struct:Different", "structschema", "", 0), 0, schema.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        writer.append(writer.start("/.schema/struct:Bad", "structschema", "", 0), 0, "unknown syntax ???".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        writer.start("/selected", "struct:Different", "", 0); writer.start("/bad", "struct:Bad", "", 0);
      });
    }
    var inventory = open(); var request = id(inventory); request.addProperty("query", "/selected");
    var selected = details(inventory, inventories.list(client, request));
    assertTrue(selected.getAsJsonArray("schema_problems").asList().stream().anyMatch(p -> p.getAsJsonObject().get("code").getAsString().equals("SCHEMA_CONFLICT")));
    request.addProperty("query", "/bad"); var bad = details(inventory, inventories.list(client, request));
    assertTrue(bad.getAsJsonArray("schema_problems").asList().stream().anyMatch(p -> p.getAsJsonObject().get("code").getAsString().equals("INVALID_SCHEMA")));
  }

  @Test void aFinishedBuildNoLongerHoldsABuilderWhileItsThreadReturnsToThePool() throws Exception {
    // A build completes its caller's result while its thread is still finishing. The probe keeps two such threads
    // there, as a busy machine can; the next build used to be refused as if both builders were busy.
    var hold = new CountDownLatch(1); var held = new CountDownLatch(2);
    inventories.afterBuild(() -> {
      held.countDown();
      try { hold.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    });
    try {
      open(); open();
      assertTrue(held.await(5, TimeUnit.SECONDS), "both builders' threads stay after completing their results");
      inventories.afterBuild(() -> {});
      var other = new RecordingInventories.Client(Authority.HTTP_LOCAL, "other", () -> true);
      assertTrue(inventories.open(other, fixture.reference).has("inventory_id"), "a third build is admitted");
    } finally { hold.countDown(); }
  }
  @Test void builderAndAggregateReservationsRejectWithoutAWaitingQueue() throws Exception {
    inventories.close(); var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
    var defaults = RecordingInventories.Limits.defaults();
    inventories = new RecordingInventories(ref -> {
      entered.countDown(); try { release.await(); } catch (InterruptedException e) { throw new java.io.InterruptedIOException(); }
      return sources.admitRecording(ref);
    }, new RecordingInventories.Limits(32L << 20, 128L << 20, 8, 2, defaults.idleNanos(), defaults.lifetimeNanos(), defaults.buildNanos()), System::nanoTime);
    var calls = new ArrayList<CompletableFuture<JsonObject>>();
    for (int i = 0; i < 2; i++) calls.add(CompletableFuture.supplyAsync(() -> { try { return open(); } catch (Exception e) { throw new CompletionException(e); } }));
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      var other = new RecordingInventories.Client(Authority.HTTP_LOCAL, "other", () -> true);
      var refusal = assertThrows(InventoryFailure.class, () -> inventories.open(other, fixture.reference));
      assertTrue(refusal.retryable); assertTrue(refusal.getMessage().contains("builders")); assertEquals(64L << 20, inventories.retainedBytes());
    } finally { release.countDown(); }
    for (var call : calls) inventories.release(client, id(call.get(3, TimeUnit.SECONDS)));
    assertEquals(0, inventories.retainedBytes());
    inventories.close(); inventories = service(new RecordingInventories.Limits(32L << 20, 32L << 20, 8, 2, defaults.idleNanos(), defaults.lifetimeNanos(), defaults.buildNanos()));
    open(); var refusal = assertThrows(InventoryFailure.class, this::open);
    assertTrue(refusal.retryable); assertTrue(refusal.getMessage().contains("metadata reservation"));
  }
}
