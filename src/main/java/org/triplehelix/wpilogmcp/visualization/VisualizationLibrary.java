/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.triplehelix.wpilogmcp.mcp.CallerContext.Authority;
import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.internal.ReadWire;

/** Definitions belong to this server, not to the store that supplied their samples. One bounded
 * worker owns disk transactions. Its lock never spans another owner's queue or a future's
 * completion. Records are read on demand, so an oversized installation remains inspectable. */
public final class VisualizationLibrary implements AutoCloseable {
  public static final int MAX_OBJECTS = 1024, MAX_RECORD_BYTES = 4 << 20, MAX_RECEIPTS = 64;
  public static final long MAX_BYTES = 64L << 20, MAX_INPUT_BYTES = 8L << 20;
  private final ServerIdentity server;
  private final StateFiles files;
  private final UUID id;
  private final long byteLimit;
  private final int objectLimit;
  private final AtomicLong inputBytes = new AtomicLong();
  private final Semaphore admitted = new Semaphore(4);
  private final ThreadPoolExecutor completions = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(4), task -> { var t = new Thread(task, "visualization-library-completion"); t.setDaemon(true); return t; });
  private final ThreadPoolExecutor queue = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(8), task -> { var t = new Thread(task, "visualization-library"); t.setDaemon(true); return t; }) {
    @Override protected void terminated() { completions.shutdown(); }
  };
  private volatile boolean closed;
  /** Test seam at the actual write boundary, after admission and while bytes remain charged. */
  Runnable beforeWrite = () -> {};

  public VisualizationLibrary(ServerIdentity server) throws IOException { this(server, MAX_OBJECTS, MAX_BYTES); }
  VisualizationLibrary(ServerIdentity server, int objects, long bytes) throws IOException {
    this.server = server; objectLimit = objects; byteLimit = bytes;
    files = server.durable() ? new StateFiles(server.stateDirectory().resolve("library")) : null;
    if (files == null) { id = null; return; }
    try (var lock = files.lock()) {
      if (Files.exists(files.path("identity.json"))) {
        var identity = VisualizationContracts.parse(files.read("identity.json", 4096));
        if (!identity.keySet().equals(Set.of("library_id", "server_instance_id", "schema_version"))
            || !new JsonPrimitive(1).equals(identity.get("schema_version"))
            || !server.id().toString().equals(identity.get("server_instance_id").getAsString())) throw new IOException("LIBRARY_UNAVAILABLE: invalid library identity; files retained");
        id = LibraryReferences.uuid(identity, "library_id");
      } else {
        id = UUID.randomUUID(); var identity = new JsonObject(); identity.addProperty("schema_version", 1);
        identity.addProperty("server_instance_id", server.id().toString()); identity.addProperty("library_id", id.toString()); files.write("identity.json", identity.toString());
      }
    } catch (IOException | RuntimeException failure) { queue.shutdownNow(); throw failure; }
  }
  public void requireAvailable() throws IOException {
    if (files == null || closed) throw new IOException("LIBRARY_UNAVAILABLE: this server has no available durable visualization library");
  }
  public JsonObject capabilities() {
    var out = new JsonObject(); out.addProperty("available", files != null && !closed);
    out.add("library_id", id == null ? JsonNull.INSTANCE : new JsonPrimitive(id.toString()));
    out.addProperty("max_objects", objectLimit); out.addProperty("max_metadata_bytes", byteLimit);
    out.addProperty("max_record_bytes", MAX_RECORD_BYTES); out.addProperty("max_receipts", MAX_RECEIPTS);
    out.addProperty("max_page_rows", 256); out.addProperty("max_reply_bytes", InventoryWire.BYTES);
    out.addProperty("max_queued_requests", 8); out.addProperty("max_inflight_input_bytes", MAX_INPUT_BYTES);
    out.addProperty("max_admitted_operations", 4);
    out.addProperty("unavailable_reason", files == null ? "Server working state is ephemeral" : ""); return out;
  }
  static String objectKey(String kind) { return switch (kind) { case "view" -> "saved_view"; case "template" -> "template"; default -> throw new IllegalArgumentException("kind must be view or template"); }; }
  static String idKey(String kind) { return objectKey(kind) + "_id"; }
  private static String path(String kind, UUID id) { objectKey(kind); return "objects/" + kind + "-" + id + ".json"; }
  public JsonObject reference(String kind, JsonObject object) {
    var ref = new JsonObject(); if (kind.equals("template")) ref.addProperty("origin", "library");
    ref.addProperty("server_instance_id", server.id().toString()); ref.addProperty("library_id", id.toString());
    ref.add(idKey(kind), object.get(idKey(kind))); ref.add("revision", object.get("revision")); return ref;
  }
  public void requireReference(String kind, JsonObject ref) throws IOException {
    requireAvailable();
    if (kind.equals("view")) LibraryReferences.saved(ref); else TemplateDefinition.validateReference(ref);
    if (!ref.has("library_id") || !id.toString().equals(ref.get("library_id").getAsString())
        || !server.id().toString().equals(ref.get("server_instance_id").getAsString()))
      throw new IOException("LIBRARY_UNAVAILABLE: reference belongs to another server or library");
  }
  private JsonObject publicObject(String kind, JsonObject object) {
    var out = new JsonObject(); out.add(objectKey(kind) + "_ref", reference(kind, object)); out.add(objectKey(kind), object.deepCopy()); return out;
  }
  private JsonObject admitDefinition(String kind, JsonObject object) throws IOException {
    var result = publicObject(kind, object); wire(result);
    if (kind.equals("template")) wire(TemplateDefinition.exportReply(object));
    return result;
  }
  /** Count MCP escaping plus bounded ID and contract overhead before any persistent mutation. */
  static void wire(JsonObject object) throws IOException {
    try { InventoryWire.size(object); }
    catch (InventoryFailure failure) { throw new IOException("LIBRARY_LIMIT: definition exceeds the 1 MiB encoded reply budget; original retained", failure); }
  }
  private static JsonObject validate(String kind, JsonObject object) {
    if (kind.equals("template")) return TemplateDefinition.validate(object);
    if (!object.keySet().equals(Set.of("schema_version", "saved_view_id", "revision", "title", "source_ref", "configuration", "template_ref"))
        || !new JsonPrimitive(1).equals(object.get("schema_version"))) throw new IllegalArgumentException("Invalid saved-view fields");
    LibraryReferences.uuid(object, "saved_view_id"); TemplateDefinition.revision(object, "revision", false);
    TemplateDefinition.text(object, "title", 256, false); VisualizationContracts.source(object.getAsJsonObject("source_ref"));
    VisualizationContracts.configuration(object.getAsJsonObject("configuration")); TemplateDefinition.validateReference(object.get("template_ref")); return object;
  }
  private JsonObject read(String kind, UUID objectId) throws IOException {
    var record = VisualizationContracts.parse(files.read(path(kind, objectId), MAX_RECORD_BYTES), MAX_RECORD_BYTES);
    if (!record.keySet().equals(Set.of("schema_version", "kind", "object", "requests", "imports", "locator", "legacy"))
        || !new JsonPrimitive(1).equals(record.get("schema_version")) || !kind.equals(record.get("kind").getAsString())) throw new IOException("Invalid library record");
    var object = validate(kind, record.getAsJsonObject("object"));
    if (!objectId.equals(LibraryReferences.uuid(object, idKey(kind)))) throw new IOException("Invalid library object identity");
    for (var name : List.of("requests", "imports")) {
      var receipts = record.getAsJsonObject(name);
      if (receipts.size() > MAX_RECEIPTS) throw new IOException("Invalid library receipt count");
      for (var e : receipts.entrySet()) {
        if (name.equals("requests")) UUID.fromString(e.getKey());
        var receipt = e.getValue().getAsJsonObject();
        if (!receipt.keySet().equals(Set.of("digest", "result")) || receipt.get("digest").getAsString().length() > 256) throw new IOException("Invalid library receipt");
        var result = receipt.getAsJsonObject("result"); validate(kind, result.getAsJsonObject(objectKey(kind)));
        requireReference(kind, result.getAsJsonObject(objectKey(kind) + "_ref")); wire(result);
      }
    }
    if (record.getAsJsonObject("requests").size() + record.getAsJsonObject("imports").size()
        + (record.get("legacy").isJsonNull() ? 0 : 1) > MAX_RECEIPTS) throw new IOException("Invalid combined library receipt count");
    if (!record.get("locator").isJsonNull()) validateLocator(record.getAsJsonObject("locator"));
    wire(publicObject(kind, object)); return record;
  }
  private static void validateLocator(JsonObject locator) throws IOException {
    if (ReadWire.bytes(locator, 16384).length > 16384) throw new IOException("Invalid source locator");
    TemplateDefinition.text(locator, "path", 8192, false);
  }
  private static JsonObject empty(String kind) {
    var out = new JsonObject(); out.addProperty("schema_version", 1); out.addProperty("kind", kind);
    out.add("requests", new JsonObject()); out.add("imports", new JsonObject()); out.add("locator", JsonNull.INSTANCE); out.add("legacy", JsonNull.INSTANCE); return out;
  }
  private static void receipt(JsonObject receipts, String key, String digest, JsonObject result) throws IOException {
    if (receipts.size() >= MAX_RECEIPTS && !receipts.has(key)) throw new IOException("LIBRARY_LIMIT: retry receipts are full; save a new copy");
    var value = new JsonObject(); value.addProperty("digest", digest); value.add("result", result.deepCopy()); receipts.add(key, value);
  }
  private static JsonObject retry(JsonObject receipt, String digest) throws IOException {
    if (!digest.equals(receipt.get("digest").getAsString())) throw new IOException("REQUEST_CONFLICT: request id reused with different content");
    return receipt.getAsJsonObject("result").deepCopy();
  }
  private void write(String kind, UUID objectId, JsonObject record) throws IOException {
    if (record.getAsJsonObject("requests").size() + record.getAsJsonObject("imports").size()
        + (record.get("legacy").isJsonNull() ? 0 : 1) > MAX_RECEIPTS)
      throw new IOException("LIBRARY_LIMIT: combined retry and migration receipts are full; save a new copy");
    byte[] bytes;
    try { bytes = ReadWire.bytes(record, MAX_RECORD_BYTES); }
    catch (ReadException limit) { throw new IOException("LIBRARY_LIMIT: record including receipts exceeds 4 MiB", limit); }
    long total = 0; long count = 0;
    // Include pending writes, quarantined and unknown files. Never follow a link to count its target.
    try (var paths = Files.walk(files.path("identity.json").getParent())) {
      var iterator = paths.iterator();
      while (iterator.hasNext()) {
        var file = iterator.next(); var attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink()) throw new IOException("LIBRARY_LIMIT: unsafe library entry retained; inspect local state");
        if (!attributes.isDirectory()) total = Math.addExact(total, attributes.size());
        if (file.getParent() != null && file.getParent().equals(files.path("objects"))) count++;
      }
    }
    boolean exists = Files.exists(files.path(path(kind, objectId)));
    // Charge the complete temporary replacement in addition to the old record until atomic rename.
    if (total + bytes.length > byteLimit || count + (exists ? 0 : 1) > objectLimit)
      throw new IOException("LIBRARY_LIMIT: library metadata quota reached; existing and quarantined records retained");
    beforeWrite.run();
    files.write(path(kind, objectId), new String(bytes, StandardCharsets.UTF_8));
  }
  public CompletableFuture<JsonObject> save(Authority caller, String kind, UUID request, long expected,
      JsonObject candidate, JsonObject locator) {
    return save(caller, kind, request, expected, candidate, locator, null);
  }
  CompletableFuture<JsonObject> save(Authority caller, String kind, UUID request, long expected,
      JsonObject candidate, JsonObject locator, UUID workingView) {
    var input = new JsonObject(); input.add("object", candidate); input.addProperty("expected_revision", expected);
    input.add("locator", locator == null ? JsonNull.INSTANCE : locator);
    input.add("working_view_id", workingView == null ? JsonNull.INSTANCE : new JsonPrimitive(workingView.toString()));
    return submit(caller, input, data -> {
      var object = data.getAsJsonObject("object"); UUID objectId = LibraryReferences.uuid(object, idKey(kind));
      String digest = TemplateDefinition.digest(data); String name = path(kind, objectId);
      var record = Files.exists(files.path(name)) ? read(kind, objectId) : empty(kind);
      var prior = record.getAsJsonObject("requests").get(request.toString()); if (prior != null) return retry(prior.getAsJsonObject(), digest);
      long actual = record.has("object") ? record.getAsJsonObject("object").get("revision").getAsLong() : 0;
      if (actual != expected || expected >= 9_007_199_254_740_991L || object.get("revision").getAsLong() != expected + 1) throw new IOException("REVISION_CONFLICT: current library revision is " + actual);
      if (!data.get("locator").isJsonNull()) validateLocator(data.getAsJsonObject("locator"));
      if (kind.equals("view") && object.get("title").isJsonNull() && record.has("object")) object.add("title", record.getAsJsonObject("object").get("title"));
      validate(kind, object); var answer = admitDefinition(kind, object);
      record.add("object", object); record.add("locator", data.get("locator"));
      receipt(record.getAsJsonObject("requests"), request.toString(), digest, answer); write(kind, objectId, record); return answer;
    });
  }
  public CompletableFuture<JsonObject> get(Authority caller, String kind, JsonObject ref) {
    return submit(caller, ref, input -> { requireReference(kind, input); var record = read(kind, LibraryReferences.uuid(input, idKey(kind)));
      if (!record.getAsJsonObject("object").get("revision").equals(input.get("revision"))) throw new IOException("REVISION_CONFLICT: library object changed; refresh the listing");
      return publicObject(kind, record.getAsJsonObject("object")); });
  }
  /** Owner-local locator is not returned in public definitions or portable exports. */
  CompletableFuture<JsonObject> locator(Authority caller, JsonObject ref) {
    return submit(caller, ref, input -> { requireReference("view", input); var value = read("view", LibraryReferences.uuid(input, "saved_view_id")).get("locator");
      return value.isJsonNull() ? new JsonObject() : value.getAsJsonObject(); });
  }
  public CompletableFuture<JsonObject> list(Authority caller, JsonObject request) {
    if (files == null && !closed) {
      try { VisualizationAccess.require(caller, VisualizationAccess.Operation.LIBRARY); return CompletableFuture.completedFuture(listNow(request)); }
      catch (Exception failure) { return CompletableFuture.failedFuture(failure); }
    }
    return submit(caller, request, this::listNow);
  }
  private JsonObject listNow(JsonObject request) throws IOException {
    if (!Set.of("api_version", "kind", "query", "after", "limit", "source_ref", "include_bundled").containsAll(request.keySet()))
      throw new IllegalArgumentException("Unknown library listing fields");
    String kind = request.has("kind") ? TemplateDefinition.text(request, "kind", 8, false) : "view"; objectKey(kind);
    int limit = request.has("limit") ? Math.toIntExact(TemplateDefinition.revision(request, "limit", false)) : 256;
    if (limit > 256) throw new IllegalArgumentException("limit must be 1 through 256");
    // Retained malformed filenames also appear as unavailable rows and must be traversable.
    String after = request.has("after") ? TemplateDefinition.text(request, "after", 1024, true) : "";
    String query = request.has("query") ? TemplateDefinition.text(request, "query", 256, true).toLowerCase(Locale.ROOT) : "";
    var selected = new TreeMap<String, JsonObject>(); long total = 0; long unavailable = 0;
    if (kind.equals("template") && request.has("include_bundled") && request.get("include_bundled").getAsBoolean()) {
      for (var template : BundledTemplates.all()) {
        String title = template.get("name").getAsString(); if (!(title + " " + template.get("description").getAsString()).toLowerCase(Locale.ROOT).contains(query)) continue;
        String name = "bundled-" + template.get("template_id").getAsString(); total++;
        var row = new JsonObject(); row.addProperty("key", name); row.addProperty("title", title); row.addProperty("available", true);
        row.add("template_ref", TemplateDefinition.reference(null, template)); row.add("description", template.get("description"));
        if (name.compareTo(after) > 0) { selected.put(name, row); if (selected.size() > limit + 1) selected.pollLastEntry(); }
      }
    }
    if (files != null && Files.exists(files.path("objects"))) try (var entries = Files.newDirectoryStream(files.path("objects"), kind + "-*.json")) {
      for (var file : entries) {
        String name = file.getFileName().toString(); var row = new JsonObject(); row.addProperty("key", name);
        try {
          UUID objectId = UUID.fromString(name.substring(kind.length() + 1, name.length() - 5));
          var object = read(kind, objectId).getAsJsonObject("object"); String title = object.get(kind.equals("view") ? "title" : "name").getAsString();
          if (!(title + (kind.equals("template") ? " " + object.get("description").getAsString() : "")).toLowerCase(Locale.ROOT).contains(query)) continue;
          if (request.has("source_ref") && !request.get("source_ref").equals(object.get("source_ref"))) continue;
          row.add(objectKey(kind) + "_ref", reference(kind, object)); row.addProperty("title", title); row.addProperty("available", true);
          if (kind.equals("template")) row.add("description", object.get("description"));
        } catch (IOException | RuntimeException invalid) { unavailable++; row.addProperty("available", false); row.addProperty("reason", "Record unreadable or exceeds public limits; retained for local inspection/export"); }
        total++;
        if (name.compareTo(after) > 0) { selected.put(name, row); if (selected.size() > limit + 1) selected.pollLastEntry(); }
      }
    }
    var out = new JsonObject(); var rows = new JsonArray(); out.add("items", rows); out.addProperty("total_matching", total); out.addProperty("total_unavailable", unavailable); out.addProperty("limit", limit);
    String last = null; boolean more = false;
    for (var entry : selected.entrySet()) {
      if (rows.size() == limit) { more = true; break; }
      rows.add(entry.getValue()); out.addProperty("next", entry.getKey());
      try { wire(out); } catch (IOException tooLarge) { rows.remove(rows.size() - 1); more = true; break; }
      last = entry.getKey();
    }
    out.add("next", more && last != null ? new JsonPrimitive(last) : JsonNull.INSTANCE); out.addProperty("returned", rows.size()); wire(out); return out;
  }
  /** Alias and copied bytes share the same atomic record. Store UUID namespaces conflicting
   * object IDs; changing an already-migrated legacy revision refuses instead of overwriting. */
  public CompletableFuture<JsonObject> migrate(Authority caller, String kind, JsonObject legacyRef, JsonObject object, JsonObject locator) {
    var input = new JsonObject(); input.add("legacy_ref", legacyRef); input.add("object", object); input.add("locator", locator == null ? JsonNull.INSTANCE : locator);
    return submit(caller, input, data -> {
      var alias = data.getAsJsonObject("legacy_ref"); String key = kind + ":" + alias.get("store_id").getAsString() + ":" + alias.get(idKey(kind)).getAsString();
      UUID objectId = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)); String digest = TemplateDefinition.digest(data.getAsJsonObject("object"));
      if (Files.exists(files.path(path(kind, objectId)))) {
        var record = read(kind, objectId); var previous = record.getAsJsonObject("legacy");
        if (previous == null || !alias.equals(previous.get("reference")) || !digest.equals(previous.get("content_sha256").getAsString())) throw new IOException("REVISION_CONFLICT: legacy definition changed since migration; originals retained");
        return previous.getAsJsonObject("result").deepCopy();
      }
      var candidate = data.getAsJsonObject("object"); candidate.addProperty(idKey(kind), objectId.toString()); validate(kind, candidate);
      var answer = admitDefinition(kind, candidate); var record = empty(kind); record.add("object", candidate);
      var legacy = new JsonObject(); legacy.add("reference", alias); legacy.addProperty("content_sha256", digest); legacy.add("result", answer.deepCopy());
      record.add("legacy", legacy); record.add("locator", data.get("locator")); write(kind, objectId, record); return answer;
    });
  }
  public CompletableFuture<JsonObject> importPreview(Authority caller, JsonObject request) {
    return submit(caller, request, data -> {
      var imported = TemplateDefinition.imported(data.get("envelope_json").getAsString());
      UUID objectId = LibraryReferences.uuid(imported, "template_id");
      var current = Files.exists(files.path(path("template", objectId))) ? read("template", objectId).getAsJsonObject("object") : null;
      var result = new JsonObject(); result.addProperty("name", imported.get("name").getAsString());
      result.addProperty("can_update", current != null && imported.get("revision").getAsLong() > current.get("revision").getAsLong());
      result.add("existing", current == null ? JsonNull.INSTANCE : reference("template", current));
      result.add("expected_revision", current == null ? JsonNull.INSTANCE : current.get("revision")); return result;
    });
  }
  /** Same portable digest and CAS rules as legacy imports; receipts live in this owner's record. */
  public CompletableFuture<JsonObject> importTemplate(Authority caller, JsonObject request) {
    return submit(caller, request, data -> {
      UUID operation = LibraryReferences.uuid(data, "request_id");
      var imported = TemplateDefinition.imported(data.get("envelope_json").getAsString());
      String mode = data.has("mode") ? TemplateDefinition.text(data, "mode", 8, false) : "copy";
      if (!Set.of("copy", "update").contains(mode)) throw new IllegalArgumentException("Import mode must be copy or update");
      Long expected = data.has("expected_revision") ? TemplateDefinition.revision(data, "expected_revision", false) : null;
      String digest = TemplateDefinition.export(imported).get("content_sha256").getAsString();
      String portableIdentity = imported.get("template_id").getAsString() + ":" + imported.get("revision").getAsLong();
      String portableKey = portableIdentity + ":" + digest, signature = digest + ":" + mode + ":" + expected;
      if (Files.exists(files.path("objects"))) try (var entries = Files.newDirectoryStream(files.path("objects"), "template-*.json")) {
        for (var file : entries) {
          String name = file.getFileName().toString();
          var record = read("template", UUID.fromString(name.substring(9, name.length() - 5)));
          var requests = record.getAsJsonObject("requests");
          if (requests.has(operation.toString())) return retry(requests.getAsJsonObject(operation.toString()), signature);
          var imports = record.getAsJsonObject("imports");
          if (imports.has(portableKey)) return imports.getAsJsonObject(portableKey).getAsJsonObject("result").deepCopy();
          if (imports.keySet().stream().anyMatch(key -> key.startsWith(portableIdentity + ":"))) throw new IOException("REVISION_CONFLICT: portable identity/revision has different content");
        }
      }
      UUID objectId = LibraryReferences.uuid(imported, "template_id");
      var record = Files.exists(files.path(path("template", objectId))) ? read("template", objectId) : empty("template");
      var candidate = imported.deepCopy();
      if (record.has("object")) {
        var current = record.getAsJsonObject("object"); long revision = current.get("revision").getAsLong();
        long importedRevision = imported.get("revision").getAsLong();
        if (revision == importedRevision) {
          if (!TemplateDefinition.digest(current).equals(TemplateDefinition.digest(imported))) throw new IOException("REVISION_CONFLICT: same template revision has different content");
        } else if (mode.equals("update")) {
          if (expected == null || expected != revision || importedRevision <= revision || revision >= 9_007_199_254_740_991L) throw new IOException("REVISION_CONFLICT: Update existing requires the current revision and a newer import");
          candidate.addProperty("revision", revision + 1);
        } else {
          objectId = UUID.nameUUIDFromBytes(portableKey.getBytes(StandardCharsets.UTF_8));
          if (Files.exists(files.path(path("template", objectId)))) throw new IOException("REQUEST_CONFLICT: import copy identity already exists");
          record = empty("template"); candidate.addProperty("template_id", objectId.toString()); candidate.addProperty("revision", 1);
        }
      } else if (mode.equals("update")) throw new IOException("REVISION_CONFLICT: Update existing requires an existing template");
      validate("template", candidate); var result = admitDefinition("template", candidate);
      record.add("object", candidate); receipt(record.getAsJsonObject("requests"), operation.toString(), signature, result);
      receipt(record.getAsJsonObject("imports"), portableKey, digest, result); write("template", objectId, record); return result;
    });
  }
  @FunctionalInterface private interface Operation { JsonObject run(JsonObject request) throws Exception; }
  private CompletableFuture<JsonObject> submit(Authority caller, JsonObject input, Operation operation) {
    var result = new CompletableFuture<JsonObject>(); long charge = 2L * VisualizationContracts.MAX_CONFIGURATION_BYTES;
    try { VisualizationAccess.require(caller, VisualizationAccess.Operation.LIBRARY); requireAvailable(); }
    catch (Exception failure) { return CompletableFuture.failedFuture(failure); }
    if (!admitted.tryAcquire()) return CompletableFuture.failedFuture(new IOException("LIBRARY_BUSY: admitted operation capacity busy; retry"));
    long reserved = inputBytes.addAndGet(charge);
    if (reserved > MAX_INPUT_BYTES) { inputBytes.addAndGet(-charge); admitted.release(); return CompletableFuture.failedFuture(new IOException("LIBRARY_BUSY: input capacity busy; retry")); }
    final String serialized;
    try { serialized = new String(ReadWire.bytes(input, VisualizationContracts.MAX_CONFIGURATION_BYTES), StandardCharsets.UTF_8); }
    catch (Exception failure) { inputBytes.addAndGet(-charge); admitted.release(); return CompletableFuture.failedFuture(failure); }
    try { queue.execute(() -> {
      JsonObject answer = null; Throwable failure = null;
      try (var lock = files.lock()) { answer = operation.run(VisualizationContracts.parse(serialized)); }
      catch (Throwable thrown) {
        failure = thrown.getMessage() != null && thrown.getMessage().startsWith("WORKSPACE_BUSY:")
            ? new IOException("LIBRARY_BUSY: another process holds the library lock; retry", thrown) : thrown;
      }
      finally { inputBytes.addAndGet(-charge); }
      // Caller callbacks must not run on the disk queue either: they can submit and await more
      // work. The admission permit stays charged until completion returns, bounding blocked callbacks.
      JsonObject completed = answer; Throwable rejected = failure;
      completions.execute(() -> {
        try { if (rejected == null) result.complete(completed); else result.completeExceptionally(rejected); }
        finally { admitted.release(); }
      });
      if (failure instanceof VirtualMachineError fatal) throw fatal;
    }); } catch (RejectedExecutionException rejected) { inputBytes.addAndGet(-charge); admitted.release(); result.completeExceptionally(new IOException("LIBRARY_BUSY: library queue is closed or full; retry", rejected)); }
    return result;
  }
  long reservedInputBytes() { return inputBytes.get(); }
  @Override public void close() { closed = true; queue.shutdown(); }
}
