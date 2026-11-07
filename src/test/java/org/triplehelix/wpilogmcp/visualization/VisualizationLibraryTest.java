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
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.mcp.CallerContext.Authority;

class VisualizationLibraryTest {
  static final Authority LOCAL = Authority.STDIO_LOCAL;
  @TempDir Path temp;
  ServerIdentity identity; VisualizationLibrary library;
  @BeforeEach void start() throws Exception { identity = ServerIdentity.open(temp.toRealPath().resolve("state")); library = new VisualizationLibrary(identity); }
  @AfterEach void stop() { library.close(); }
  static JsonObject template(UUID id, long revision) {
    var out = new JsonObject(); out.addProperty("schema_version", 1); out.addProperty("template_id", id.toString()); out.addProperty("revision", revision);
    out.addProperty("name", "Template " + id); out.addProperty("description", "Synthetic"); out.add("configuration", VisualizationContracts.blank()); return out;
  }
  JsonObject save(UUID request, long expected, JsonObject object) throws Exception { return library.save(LOCAL, "template", request, expected, object, null).get(5, TimeUnit.SECONDS); }
  static JsonObject listRequest(String kind, int limit) { var r = new JsonObject(); r.addProperty("kind", kind); r.addProperty("limit", limit); return r; }
  JsonObject list(JsonObject request) throws Exception { return library.list(LOCAL, request).get(5, TimeUnit.SECONDS); }
  static void code(String code, org.junit.jupiter.api.function.Executable action) {
    Throwable failure = assertThrows(Exception.class, action); while (failure.getCause() != null) failure = failure.getCause();
    assertTrue(failure.getMessage().contains(code), failure.toString());
  }
  @Test void strictIdentityAndCanonicalRevisionSurviveRestart() throws Exception {
    UUID id = UUID.randomUUID(), request = UUID.randomUUID(); var original = template(id, 1); var first = save(request, 0, original);
    assertEquals(first, save(request, 0, original)); var ref = first.getAsJsonObject("template_ref");
    library.close(); library = new VisualizationLibrary(identity);
    assertEquals(first, library.get(LOCAL, "template", ref).get()); assertEquals(first, save(request, 0, original));
    var second = template(id, 2); second.addProperty("name", "Changed"); save(UUID.randomUUID(), 1, second);
    assertEquals(first, save(request, 0, original), "Retry retains its own revision after later updates");
    code("REVISION_CONFLICT", () -> library.get(LOCAL, "template", ref).get());
    original.addProperty("description", "Different request intent"); code("REQUEST_CONFLICT", () -> save(request, 0, original));
  }
  @Test void foreignLibraryAndServerReferencesCannotRetargetAnObject() throws Exception {
    var saved = save(UUID.randomUUID(), 0, template(UUID.randomUUID(), 1));
    for (String key : List.of("library_id", "server_instance_id")) {
      var ref = saved.getAsJsonObject("template_ref").deepCopy(); ref.addProperty(key, UUID.randomUUID().toString());
      code("LIBRARY_UNAVAILABLE", () -> library.get(LOCAL, "template", ref).get());
    }
    var ref = saved.getAsJsonObject("template_ref").deepCopy(); ref.addProperty("store_id", UUID.randomUUID().toString());
    assertThrows(Exception.class, () -> library.get(LOCAL, "template", ref).get());
  }
  @Test void concurrentUpdatesHaveOneWinnerAndLeaveNoTemporaryRecords() throws Exception {
    UUID id = UUID.randomUUID(); save(UUID.randomUUID(), 0, template(id, 1));
    var a = library.save(LOCAL, "template", UUID.randomUUID(), 1, template(id, 2), null);
    var b = library.save(LOCAL, "template", UUID.randomUUID(), 1, template(id, 2), null);
    assertEquals(2, a.get().getAsJsonObject("template").get("revision").getAsInt()); code("REVISION_CONFLICT", b::get);
    try (var entries = Files.list(identity.stateDirectory().resolve("library/objects"))) { assertEquals(1, entries.count()); }
  }
  @Test void pagesTraverseEveryMatchingIdentityOnceInStableOrder() throws Exception {
    var expected = new TreeSet<String>();
    for (int i = 0; i < 261; i++) {
      UUID id = UUID.nameUUIDFromBytes(("synthetic-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var candidate = template(id, 1); candidate.addProperty("name", i % 2 == 0 ? "Αλφα 温度" : "Beta"); save(UUID.randomUUID(), 0, candidate);
      expected.add("template-" + id + ".json");
    }
    var request = listRequest("template", 37); var actual = new ArrayList<String>();
    do {
      var page = list(request); assertEquals(261, page.get("total_matching").getAsInt()); assertTrue(page.get("returned").getAsInt() <= 37);
      page.getAsJsonArray("items").forEach(item -> actual.add(item.getAsJsonObject().get("key").getAsString()));
      if (page.get("next").isJsonNull()) break; request.add("after", page.get("next"));
    } while (actual.size() < 300);
    assertEquals(new ArrayList<>(expected), actual); assertEquals(261, new HashSet<>(actual).size());
    request.remove("after"); request.addProperty("query", "温度"); assertEquals(131, list(request).get("total_matching").getAsInt());
    request.addProperty("query", "never"); assertEquals(0, list(request).get("returned").getAsInt());
    request.addProperty("limit", 257); assertThrows(Exception.class, () -> list(request));
  }
  @Test void encodedReplyLimitRefusesBeforeWritingAValidLargeConfiguration() throws Exception {
    var candidate = template(UUID.randomUUID(), 1); var requirements = candidate.getAsJsonObject("configuration").getAsJsonArray("requirements");
    for (int i = 0; i < 128; i++) {
      var requirement = RecordingBindingsTest.config("entry", "/value", "double", "number", null).getAsJsonArray("requirements").get(0).getAsJsonObject().deepCopy();
      requirement.addProperty("id", "r" + i); requirement.addProperty("entry", "\\".repeat(2000)); requirements.add(requirement);
    }
    VisualizationContracts.configuration(candidate.getAsJsonObject("configuration"));
    var failure = assertThrows(Exception.class, () -> save(UUID.randomUUID(), 0, candidate));
    assertTrue(failure.toString().contains("LIBRARY_LIMIT"), failure.toString());
    assertEquals(0, list(listRequest("template", 256)).get("total_matching").getAsInt());
  }
  @Test void receiptLimitRefusesTheSixtyFifthMutationWithoutLosingRetryProof() throws Exception {
    UUID id = UUID.randomUUID(), firstRequest = UUID.randomUUID(); var first = save(firstRequest, 0, template(id, 1));
    for (int i = 2; i <= 64; i++) save(UUID.randomUUID(), i - 1, template(id, i));
    code("LIBRARY_LIMIT", () -> save(UUID.randomUUID(), 64, template(id, 65)));
    assertEquals(first, save(firstRequest, 0, template(id, 1)));
    var row = list(listRequest("template", 1)).getAsJsonArray("items").get(0).getAsJsonObject(); assertEquals(64, row.getAsJsonObject("template_ref").get("revision").getAsInt());
  }
  @Test void templateAdmissionAlsoBoundsTheEscapedPortableExportReply() throws Exception {
    var candidate = template(UUID.randomUUID(), 1); var requirements = candidate.getAsJsonObject("configuration").getAsJsonArray("requirements");
    for (int i = 0; i < 128; i++) {
      var r = RecordingBindingsTest.config("entry", "/value", "double", "number", null).getAsJsonArray("requirements").get(0).getAsJsonObject().deepCopy();
      r.addProperty("id", "r" + i); r.addProperty("entry", "\\".repeat(1000)); requirements.add(r);
    }
    var definition = new JsonObject(); definition.add("template", candidate); definition.add("template_ref", library.reference("template", candidate));
    assertDoesNotThrow(() -> InventoryWire.size(definition));
    var portable = new JsonObject(); portable.addProperty("envelope_json", TemplateDefinition.export(candidate).toString());
    assertThrows(InventoryFailure.class, () -> InventoryWire.size(portable));
    var failure = assertThrows(Exception.class, () -> save(UUID.randomUUID(), 0, candidate));
    assertTrue(failure.toString().contains("LIBRARY_LIMIT"), failure.toString());
    assertEquals(0, list(listRequest("template", 1)).get("total_matching").getAsInt());
  }
  @Test void storageAdmissionCountsQuarantineAndAtomicReplacementBytes() throws Exception {
    var first = save(UUID.randomUUID(), 0, template(UUID.randomUUID(), 1));
    var root = identity.stateDirectory().resolve("library"); Files.createDirectories(root.resolve("quarantine")); Files.writeString(root.resolve("quarantine/retained"), "x".repeat(4096));
    long bytes; try (var paths = Files.walk(root)) { bytes = paths.filter(Files::isRegularFile).mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { throw new java.io.UncheckedIOException(e); } }).sum(); }
    long oldRecord = Files.size(root.resolve("objects/template-" + first.getAsJsonObject("template").get("template_id").getAsString() + ".json"));
    library.close(); library = new VisualizationLibrary(identity, 1024, bytes + oldRecord);
    var candidate = first.getAsJsonObject("template").deepCopy(); candidate.addProperty("revision", 2);
    code("LIBRARY_LIMIT", () -> save(UUID.randomUUID(), 1, candidate));
    assertEquals(first, library.get(LOCAL, "template", first.getAsJsonObject("template_ref")).get()); assertTrue(Files.exists(root.resolve("quarantine/retained")));
  }
  @Test void exactRevisionValidationDoesNotRoundAFractionIntoAValidRevision() throws Exception {
    var first = save(UUID.randomUUID(), 0, template(UUID.randomUUID(), 1));
    var ref = first.getAsJsonObject("template_ref").deepCopy(); ref.add("revision", JsonParser.parseString("1.0000000000000001"));
    assertThrows(IllegalArgumentException.class, () -> TemplateDefinition.validateReference(ref));
  }
  @Test void byteShortenedPagesStillAdvanceWithoutLosingAnyIdentity() throws Exception {
    var expected = new TreeSet<String>();
    for (int i = 0; i < 110; i++) {
      var t = template(UUID.randomUUID(), 1); t.addProperty("description", "\\".repeat(4096));
      var saved = save(UUID.randomUUID(), 0, t); expected.add(saved.getAsJsonObject("template_ref").get("template_id").getAsString());
    }
    var request = listRequest("template", 256); var actual = new HashSet<String>();
    var page = list(request); assertTrue(page.get("returned").getAsInt() < 110); assertFalse(page.get("next").isJsonNull());
    do {
      assertEquals(110, page.get("total_matching").getAsInt()); assertTrue(InventoryWire.size(page) <= 1 << 20);
      for (var row : page.getAsJsonArray("items")) assertTrue(actual.add(row.getAsJsonObject().getAsJsonObject("template_ref").get("template_id").getAsString()));
      if (page.get("next").isJsonNull()) break; request.add("after", page.get("next")); page = list(request);
    } while (actual.size() < 120);
    assertEquals(expected, actual);
  }
  @Test void persistentRecordCapIncludesEveryRetainedRevisionResult() throws Exception {
    UUID id = UUID.randomUUID(); var candidate = template(id, 1); var requirements = candidate.getAsJsonObject("configuration").getAsJsonArray("requirements");
    for (int i = 0; i < 128; i++) {
      var r = RecordingBindingsTest.config("entry", "/value", "double", "number", null).getAsJsonArray("requirements").get(0).getAsJsonObject().deepCopy();
      r.addProperty("id", "r" + i); r.addProperty("entry", "x".repeat(500)); requirements.add(r);
    }
    for (int revision = 1; revision <= 64; revision++) {
      candidate.addProperty("revision", revision);
      try { save(UUID.randomUUID(), revision - 1, candidate); }
      catch (ExecutionException limit) { assertTrue(limit.toString().contains("LIBRARY_LIMIT"), limit.toString()); assertTrue(revision < 64); return; }
      assertTrue(Files.size(identity.stateDirectory().resolve("library/objects/template-" + id + ".json")) <= 4 << 20, "Receipts must fit the 4 MiB persistent-record bound");
    }
    fail("Receipt copies must reach the record-byte cap before the receipt-count cap");
  }
  @Test void liveObjectLimitIsIndependentOfTheByteQuota() throws Exception {
    library.close(); library = new VisualizationLibrary(identity, 1, VisualizationLibrary.MAX_BYTES);
    save(UUID.randomUUID(), 0, template(UUID.randomUUID(), 1));
    code("LIBRARY_LIMIT", () -> save(UUID.randomUUID(), 0, template(UUID.randomUUID(), 1)));
    assertEquals(1, list(listRequest("template", 256)).get("total_matching").getAsInt());
  }
  @Test void overQuotaInstallationAndOversizedRecordsRemainInspectable() throws Exception {
    save(UUID.randomUUID(), 0, template(UUID.randomUUID(), 1));
    Path oversized = identity.stateDirectory().resolve("library/objects/template-" + UUID.randomUUID() + ".json");
    try (var file = java.nio.channels.FileChannel.open(oversized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) { file.position(VisualizationLibrary.MAX_RECORD_BYTES); file.write(java.nio.ByteBuffer.wrap(new byte[] {0})); }
    library.close(); library = new VisualizationLibrary(identity, 1, 1024);
    var page = list(listRequest("template", 256)); assertEquals(2, page.get("total_matching").getAsInt());
    assertEquals(1, page.getAsJsonArray("items").asList().stream().filter(e -> !e.getAsJsonObject().get("available").getAsBoolean()).count());
    code("LIBRARY_LIMIT", () -> save(UUID.randomUUID(), 0, template(UUID.randomUUID(), 1))); assertTrue(Files.exists(oversized));
  }
  @Test void unavailableLongFilenamesCannotStrandTheNextLibraryPage() throws Exception {
    var saved = save(UUID.randomUUID(), 0, template(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), 1));
    var damaged = identity.stateDirectory().resolve("library/objects/template-" + "0".repeat(180) + ".json");
    Files.writeString(damaged, "damaged synthetic metadata");
    var request = listRequest("template", 1); var first = list(request);
    assertFalse(first.getAsJsonArray("items").get(0).getAsJsonObject().get("available").getAsBoolean());
    request.add("after", first.get("next")); var second = list(request);
    assertEquals(2, second.get("total_matching").getAsInt());
    assertEquals(saved.get("template_ref"), second.getAsJsonArray("items").get(0).getAsJsonObject().get("template_ref"));
    assertTrue(second.get("next").isJsonNull()); assertEquals("damaged synthetic metadata", Files.readString(damaged));
  }
  @Test void queueChargesInputUntilBlockedWriteUnwindsEvenAfterClose() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    library.beforeWrite = () -> { entered.countDown(); try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); } };
    var jobs = new ArrayList<CompletableFuture<JsonObject>>();
    try {
      jobs.add(library.save(LOCAL, "template", UUID.randomUUID(), 0, template(UUID.randomUUID(), 1), null)); assertTrue(entered.await(2, TimeUnit.SECONDS));
      for (int i = 0; i < 3; i++) jobs.add(library.save(LOCAL, "template", UUID.randomUUID(), 0, template(UUID.randomUUID(), 1), null));
      code("LIBRARY_BUSY", () -> save(UUID.randomUUID(), 0, template(UUID.randomUUID(), 1)));
      assertEquals(VisualizationLibrary.MAX_INPUT_BYTES, library.reservedInputBytes()); library.close(); assertEquals(VisualizationLibrary.MAX_INPUT_BYTES, library.reservedInputBytes());
    } finally { release.countDown(); }
    for (var job : jobs) job.get(5, TimeUnit.SECONDS); assertEquals(0, library.reservedInputBytes());
  }
  @Test void completionRunsAfterTheCrossProcessLockIsReleased() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    library.beforeWrite = () -> { entered.countDown(); try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); } };
    var pending = library.save(LOCAL, "template", UUID.randomUUID(), 0, template(UUID.randomUUID(), 1), null);
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    var result = pending.thenApply(value -> {
      try (var lock = new StateFiles(identity.stateDirectory().resolve("library")).lock()) { assertTrue(lock.isOpen()); }
      catch (IOException e) { throw new java.io.UncheckedIOException(e); }
      try { assertEquals(1, library.list(LOCAL, listRequest("template", 1)).get(2, TimeUnit.SECONDS).get("returned").getAsInt()); }
      catch (Exception e) { throw new AssertionError("Completion cannot occupy the library disk queue", e); }
      return value;
    }); release.countDown(); assertNotNull(result.get(5, TimeUnit.SECONDS)); assertEquals(0, library.reservedInputBytes());
  }
  @Test void failedDiskWriteAndForeignAuthorityReleaseAllReservations() throws Exception {
    library.beforeWrite = () -> { throw new java.io.UncheckedIOException(new IOException("Synthetic disk failure")); };
    assertThrows(Exception.class, () -> save(UUID.randomUUID(), 0, template(UUID.randomUUID(), 1))); assertEquals(0, library.reservedInputBytes());
    assertThrows(Exception.class, () -> library.save(Authority.UNTRUSTED, "template", UUID.randomUUID(), 0, template(UUID.randomUUID(), 1), null).get());
    assertEquals(0, library.reservedInputBytes()); assertEquals(0, list(listRequest("template", 10)).get("total_matching").getAsInt());
  }
  @Test void migrationNamespacesCollisionsAndKeepsCanonicalRevisionAndAlias() throws Exception {
    UUID original = UUID.randomUUID(); var object = template(original, 7);
    var a = TemplateDefinition.reference(UUID.randomUUID().toString(), object); var b = TemplateDefinition.reference(UUID.randomUUID().toString(), object);
    var first = library.migrate(LOCAL, "template", a, object, null).get(); var second = library.migrate(LOCAL, "template", b, object, null).get();
    assertNotEquals(first.get("template_ref"), second.get("template_ref")); assertEquals(7, first.getAsJsonObject("template").get("revision").getAsInt());
    assertEquals(object.get("configuration"), first.getAsJsonObject("template").get("configuration")); assertEquals(first, library.migrate(LOCAL, "template", a, object, null).get());
    object.addProperty("description", "Conflicting content"); code("REVISION_CONFLICT", () -> library.migrate(LOCAL, "template", a, object, null).get());
  }
  @Test void portableImportRetriesConflictAndExplicitUpdateArePinned() throws Exception {
    UUID id = UUID.randomUUID(); var original = template(id, 1); var input = new JsonObject(); input.addProperty("request_id", UUID.randomUUID().toString());
    input.addProperty("envelope_json", TemplateDefinition.export(original).toString()); var first = library.importTemplate(LOCAL, input).get();
    assertEquals(first, library.importTemplate(LOCAL, input).get());
    var newer = template(id, 2); newer.addProperty("name", "Newer import"); var update = new JsonObject(); update.addProperty("request_id", UUID.randomUUID().toString());
    update.addProperty("envelope_json", TemplateDefinition.export(newer).toString()); update.addProperty("mode", "update"); update.addProperty("expected_revision", 1);
    assertTrue(library.importPreview(LOCAL, update).get().get("can_update").getAsBoolean());
    assertEquals(newer, library.importTemplate(LOCAL, update).get().get("template")); assertEquals(first, library.importTemplate(LOCAL, input).get());
    original.addProperty("name", "Forged same revision"); input.addProperty("request_id", UUID.randomUUID().toString()); input.addProperty("envelope_json", TemplateDefinition.export(original).toString());
    code("REVISION_CONFLICT", () -> library.importTemplate(LOCAL, input).get());
  }
}
