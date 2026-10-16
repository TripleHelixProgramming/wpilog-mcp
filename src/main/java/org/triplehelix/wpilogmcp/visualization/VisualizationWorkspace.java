/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.read.*;
import org.triplehelix.wpilogmcp.read.internal.ReadWire;
import org.triplehelix.wpilogmcp.store.*;
import org.triplehelix.wpilogmcp.tools.VisualizationBindings;
import org.triplehelix.wpilogmcp.mcp.CallerContext.Authority;

/** One application service for HTTP and MCP. Adapters select named operations, never duplicate
 * binding, persistence, save/checkpoint ordering, or numerical policy. */
public final class VisualizationWorkspace implements AutoCloseable {
  public final VisualizationService views;
  public final ControlNotifications notifications = new ControlNotifications();
  public final RecordedSources sources;
  public final RecordingInventories inventories;
  private final RecordingBindings recordingBindings;
  private final ServerIdentity identity;
  private final LogManager logs;
  private final StoreDoor door;
  public VisualizationWorkspace(ServerIdentity identity, LogManager logs) throws IOException {
    this.identity = identity; this.logs = logs; door = new StoreDoor(logs::getAllowedDirectories);
    views = new VisualizationService(identity); views.onChanged(notifications::changed); sources = new RecordedSources(logs, views, door);
    inventories = new RecordingInventories(sources); recordingBindings = new RecordingBindings(sources);
  }
  public void close() { inventories.close(); views.close(); try { sources.close(); } catch (IOException e) { throw new java.io.UncheckedIOException(e); } }
  public JsonObject capabilities() {
    var result = identity.describe(); result.addProperty("stage", "reusable-recorded-preview");
    result.add("blank_configuration", VisualizationContracts.blank()); result.add("schema", VisualizationContracts.schema());
    result.addProperty("max_working_views", VisualizationService.MAX_DOCUMENTS);
    result.addProperty("max_working_metadata_bytes", VisualizationService.MAX_STATE_BYTES); result.addProperty("max_working_record_bytes", 4 << 20); result.addProperty("max_working_receipts", 4096); result.addProperty("draft_lifetime_days", 30);
    result.addProperty("max_deleted_view_receipts", VisualizationService.MAX_TOMBSTONES);
    result.addProperty("max_deleted_view_metadata_bytes", VisualizationService.MAX_TOMBSTONE_BYTES);
    result.addProperty("deleted_view_lifetime_hours", VisualizationService.TOMBSTONE_LIFETIME.toHours());
    result.addProperty("max_plot_points", 4096); result.addProperty("max_visible_series", 32);
    result.addProperty("max_raw_page_records", 4096); result.addProperty("max_raw_page_bytes", 1048576);
    result.addProperty("max_template_receipts", 64); result.addProperty("max_template_metadata_bytes", 1048576);
    result.addProperty("recording_bindings", true); result.add("recording_binding_limits", RecordingBindings.limits());
    result.addProperty("live", false); result.addProperty("live_source_inspection", true);
    result.addProperty("max_recording_inventory_bytes", RecordingInventory.MAX_BYTES);
    result.addProperty("recording_inventory_paging", true); result.add("recording_inventory_limits", inventories.capabilities());
    result.addProperty("templates", true); result.add("roles", VisualizationBindings.roles());
    result.add("declared_types", JsonParser.parseString("[\"boolean\",\"int64\",\"float\",\"double\",\"string\",\"json\",\"raw\",\"boolean[]\",\"int64[]\",\"float[]\",\"double[]\",\"string[]\",\"struct:Name\",\"struct:Name[]\",\"structarray:Name\"]"));
    return result;
  }
  public JsonObject getView(JsonObject request) throws IOException { return views.get(uuid(request, "view_id")); }
  public JsonObject listViews(JsonObject request) throws IOException {
    var result = new JsonObject(); var list = new JsonArray();
    (request.has("source_ref") ? views.list(request.getAsJsonObject("source_ref")) : views.all()).forEach(list::add); result.add("views", list); return result;
  }
  public JsonObject updateView(Authority caller, JsonObject request) throws Exception {
    return views.revise(caller, uuid(request, "view_id"), TemplateDefinition.revision(request, "expected_revision", false), uuid(request, "request_id"), configuration(request), false, false).get();
  }
  public JsonObject discardView(Authority caller, JsonObject request) throws Exception {
    return views.revise(caller, uuid(request, "view_id"), TemplateDefinition.revision(request, "expected_revision", false), uuid(request, "request_id"), null, true, false).get();
  }
  public JsonObject deleteView(Authority caller, JsonObject request) throws Exception {
    return views.revise(caller, uuid(request, "view_id"), TemplateDefinition.revision(request, "expected_revision", false), uuid(request, "request_id"), null, false, true).get();
  }
  public JsonObject cloneView(Authority caller, JsonObject request) throws Exception {
    UUID id = uuid(request, "view_id"), operation = uuid(request, "request_id");
    var intent = request.deepCopy(); intent.addProperty("operation", "clone");
    var receipt = views.prepareMutation(caller, id, TemplateDefinition.revision(request, "expected_revision", false), operation, intent, null).get();
    if (receipt.has("result")) return receipt.getAsJsonObject("result");
    var view = receipt.getAsJsonObject("snapshot");
    var cloned = views.create(caller, operation, view.getAsJsonObject("source_ref"), view.getAsJsonObject("configuration"),
        view.get("template_ref").isJsonNull() ? null : view.getAsJsonObject("template_ref")).get();
    return views.completeMutation(caller, id, operation, cloned, null).get();
  }

  public JsonObject saveView(Authority caller, JsonObject request) throws Exception {
    caller.requireLocal(); UUID id = uuid(request, "view_id"); var args = request.deepCopy(); args.add("revision", args.get("expected_revision"));
    return save(caller, null, id, views.get(id), args);
  }
  public JsonObject inspect(Authority caller, JsonObject request) throws Exception {
    boolean paged = RecordingInventories.mode(request).equals("paged");
    if (paged) RecordingInventories.checkRequest(request);
    if (request.has("path") == request.has("source_ref")) throw new IllegalArgumentException("Name exactly one path or source_ref");
    var out = request.has("path") ? sources.identify(caller, Path.of(text(request, "path"))) : new JsonObject();
    var source = request.has("source_ref") ? request.getAsJsonObject("source_ref") : out.getAsJsonObject("source_ref");
    if (paged) out = new JsonObject();
    out.add("source_ref", source); out.add("inventory", describe(RecordingInventories.Client.current(caller), source, request)); return out;
  }
  public JsonObject describe(RecordingInventories.Client client, JsonObject source, JsonObject request) throws Exception {
    String mode = RecordingInventories.mode(request);
    if (mode.equals("full")) return sources.describe(source);
    RecordingInventories.checkRequest(request); return inventories.open(client, source);
  }
  public JsonObject create(Authority authority, JsonObject body) throws Exception {
    var source = body.getAsJsonObject("source_ref"); sources.resolve(source);
    if (body.has("template_ref") && (body.has("configuration") || body.has("configuration_json"))) throw new IllegalArgumentException("Choose a template_ref or configuration, not both");
    var definition = body.has("template_ref") ? template(body) : null;
    var configuration = definition != null ? definition.getAsJsonObject("template").getAsJsonObject("configuration")
        : body.has("configuration") || body.has("configuration_json") ? configuration(body) : VisualizationContracts.blank();
    return views.create(authority, uuid(body, "request_id"), source, configuration,
        definition == null ? null : definition.getAsJsonObject("template_ref")).get();
  }
  public JsonObject templates(JsonObject request) throws IOException {
    String query = request.has("query") ? text(request, "query").toLowerCase(Locale.ROOT) : "";
    var items = new JsonArray(); var unreadable = new JsonArray(); boolean incomplete = false;
    for (var t : BundledTemplates.all()) if (matches(t, query)) items.add(TemplateDefinition.catalog(null, t));
    for (var store : door.stores()) {
      var listing = TemplateMetadata.list(store); incomplete |= listing.incomplete();
      for (var r : listing.records()) if (matches(r.getAsJsonObject("template"), query)) {
        if (items.size() >= 1024) { incomplete = true; break; }
        var item = TemplateDefinition.catalog(store.description().id(), r.getAsJsonObject("template")); item.addProperty("store_label", store.root().getFileName().toString()); items.add(item);
      }
      for (var problem : listing.unreadable()) {
        if (unreadable.size() >= 1024) { incomplete = true; break; }
        var item = new JsonObject(); item.addProperty("store_id", store.description().id()); item.addProperty("file", problem.file()); item.addProperty("reason", problem.reason()); unreadable.add(item);
      }
      if (items.size() >= 1024 || unreadable.size() >= 1024) { incomplete = true; break; }
    }
    var out = new JsonObject(); out.add("templates", items); out.add("unreadable", unreadable); out.addProperty("listing_incomplete", incomplete); return out;
  }
  private static boolean matches(JsonObject t, String query) { return (text(t, "name") + " " + text(t, "description")).toLowerCase(Locale.ROOT).contains(query); }
  public JsonObject template(JsonObject body) throws IOException {
    var validated = TemplateDefinition.validateReference(body.get("template_ref"));
    if (validated.isJsonNull()) throw new IOException("INVALID_TEMPLATE: template_ref is required");
    var ref = validated.getAsJsonObject(); String origin = text(ref, "origin"); UUID id = uuid(ref, "template_id");
    JsonObject t; String store = null;
    if (origin.equals("bundled")) {
      t = BundledTemplates.all().stream().filter(v -> text(v, "template_id").equals(id.toString())).findFirst().orElseThrow(() -> new IOException("TEMPLATE_UNAVAILABLE: bundled template is missing"));
    } else if (origin.equals("store")) {
      store = text(ref, "store_id"); t = TemplateMetadata.read(door.select(store), id);
    } else throw new IOException("INVALID_TEMPLATE: unknown origin");
    if (TemplateDefinition.revision(ref, "revision", false) != t.get("revision").getAsLong()) throw new IOException("REVISION_CONFLICT: template changed; refresh its catalog before applying");
    return TemplateDefinition.catalog(store, t);
  }
  public JsonObject check(JsonObject request) throws Exception {
    var config = request.has("template_ref") ? template(request).getAsJsonObject("template").getAsJsonObject("configuration") : configuration(request);
    var source = VisualizationContracts.source(request.getAsJsonObject("source_ref"));
    if (source.get("kind").getAsString().equals("session")) return recordingBindings.check(source, config);
    try (var use = logs.acquireReadOnly(sources.resolve(source).toString())) {
      var out = VisualizationBindings.check(use.log(), config); sources.resolve(source); return out;
    }
  }
  /** Definitions travel as text on MCP, preserving duplicate keys until the shared parser. HTTP
   * can pass objects because its own ingress has already used that parser. */
  public static JsonObject configuration(JsonObject request) {
    if (request.has("configuration_json") && request.has("configuration")) throw new IllegalArgumentException("Use only configuration_json");
    return VisualizationContracts.configuration(request.has("configuration_json") ? VisualizationContracts.parse(text(request, "configuration_json")) : request.getAsJsonObject("configuration"));
  }
  private StoreDoor.Selected destination(JsonObject request) throws IOException {
    if (!request.has("store_id")) throw new IOException("NO_WRITABLE_STORE: choose a writable local store, or use Create store in Explorer; no enrollment is enabled");
    door.refresh();
    var selected = door.select(text(request, "store_id"));
    if (selected.description().mirror()) throw new IOException("NO_WRITABLE_STORE: mirrors are read-only; choose a local store");
    return selected;
  }
  public JsonObject createTemplate(Authority authority, JsonObject request) throws Exception {
    authority.requireLocal(); var store = destination(request); UUID id = uuid(request, "request_id");
    if (request.has("view_id") && (request.has("configuration") || request.has("configuration_json"))) throw new IllegalArgumentException("Name a view or a configuration, not both");
    JsonObject config = request.has("view_id") ? viewConfiguration(request) : configuration(request);
    return new TemplateMetadata(logs.stores().store(store.root()), store.description().id()).save(authority, id, id, 0,
        text(request, "name"), text(request, "description"), config).get();
  }
  private JsonObject viewConfiguration(JsonObject request) throws IOException {
    var view = views.get(uuid(request, "view_id"));
    if (TemplateDefinition.revision(request, "expected_view_revision", false) != view.get("revision").getAsLong()) throw new IOException("REVISION_CONFLICT: working view changed");
    return view.getAsJsonObject("configuration");
  }
  public JsonObject updateTemplate(Authority authority, JsonObject request) throws Exception {
    authority.requireLocal(); var ref = request.getAsJsonObject("template_ref");
    if (!text(ref, "origin").equals("store")) throw new IOException("READ_ONLY: bundled templates are immutable; save a store copy");
    if (TemplateDefinition.revision(ref, "revision", false) != TemplateDefinition.revision(request, "expected_revision", false)) throw new IOException("REVISION_CONFLICT: reference and expected revision differ");
    var store = door.select(text(ref, "store_id"));
    return new TemplateMetadata(logs.stores().store(store.root()), store.description().id()).save(authority, uuid(request, "request_id"), uuid(ref, "template_id"),
        TemplateDefinition.revision(request, "expected_revision", false), text(request, "name"), text(request, "description"), configuration(request)).get();
  }
  public JsonObject exportTemplate(JsonObject request) throws IOException {
    var t = template(request); var envelope = TemplateDefinition.export(t.getAsJsonObject("template")); var out = new JsonObject(); out.addProperty("envelope_json", envelope.toString());
    for (var key : List.of("format", "schema_version", "content_sha256")) out.add(key, envelope.get(key)); return out;
  }
  /** Read-only choice preview. The actual import rechecks under the store lock. */
  public JsonObject importPreview(JsonObject request) throws IOException {
    var imported = TemplateDefinition.imported(text(request, "envelope_json")); var selected = destination(request);
    var listing = TemplateMetadata.list(selected);
    if (listing.incomplete() || !listing.unreadable().isEmpty()) throw new IOException("INCOMPLETE_CATALOG: repair unreadable template records before importing");
    var current = listing.records().stream().map(r -> r.getAsJsonObject("template"))
        .filter(t -> t.get("template_id").equals(imported.get("template_id"))).findFirst();
    var out = new JsonObject(); out.addProperty("can_update", current.isPresent() && imported.get("revision").getAsLong() > current.get().get("revision").getAsLong());
    out.addProperty("name", text(imported, "name"));
    out.add("existing", current.map(t -> (JsonElement) TemplateDefinition.reference(selected.description().id(), t)).orElse(JsonNull.INSTANCE));
    out.add("expected_revision", current.map(t -> t.get("revision")).orElse(JsonNull.INSTANCE)); return out;
  }
  public JsonObject importTemplate(Authority authority, JsonObject request) throws Exception {
    authority.requireLocal(); var store = destination(request);
    return new TemplateMetadata(logs.stores().store(store.root()), store.description().id()).importDefinition(authority, uuid(request, "request_id"), text(request, "envelope_json"),
        request.has("mode") ? text(request, "mode") : "copy", request.has("expected_revision") ? TemplateDefinition.revision(request, "expected_revision", false) : null).get();
  }
  public JsonObject statistics(JsonObject request) throws Exception {
    var source = request.getAsJsonObject("source_ref"); var args = new JsonObject(); args.addProperty("path", sources.resolve(source).toString()); args.addProperty("name", text(request, "entry"));
    for (var key : List.of("field", "start_time", "end_time")) if (request.has(key)) args.add(key, request.get(key));
    var result = VisualizationBindings.statistics(args); sources.resolve(source); return result;
  }
  public JsonObject raw(JsonObject request) throws IOException {
    var source = request.getAsJsonObject("source_ref");
    int first = request.has("first_ordinal") ? ReadWire.integer(request, "first_ordinal") : 0;
    int limit = request.has("limit") ? ReadWire.integer(request, "limit") : 256;
    var windows = new ArrayList<ReadQuery.Window>();
    if (request.has("start_time") || request.has("end_time")) windows.add(new ReadQuery.Window(
        request.has("start_time") ? micros(request.get("start_time").getAsDouble()) : Long.MIN_VALUE,
        request.has("end_time") ? micros(request.get("end_time").getAsDouble()) : Long.MAX_VALUE, true));
    var query = new ReadQuery(text(request, "entry"), windows, ReadQuery.Order.FILE, limit, 256 << 10).fromOrdinal(first);
    if (request.has("field")) query = query.project(text(request, "field"));
    try (var snapshot = sources.open(source); var cursor = snapshot.read(query)) {
      var batch = cursor.next(); var out = ReadWire.batch(batch, 1 << 20);
      out.addProperty("next_ordinal", first + batch.recordsVisited()); out.addProperty("total_records", snapshot.sampleCount(query.entry()));
      out.addProperty("basis", "physical entry ordinal; duplicate/backward timestamps are retained"); sources.resolve(source); return out;
    }
  }
  private static long micros(double seconds) {
    if (!Double.isFinite(seconds) || Math.abs(seconds) > 9_007_199_254.0) throw new IllegalArgumentException("Time exceeds the exact microsecond range"); return Math.round(seconds * 1e6);
  }
  public JsonObject stores() throws IOException {
    door.refresh();
    var result = new JsonObject(); var values = new JsonArray();
    for (var store : door.stores()) {
      var value = new JsonObject(); value.addProperty("store_id", store.description().id());
      value.addProperty("label", store.root().getFileName().toString()); value.addProperty("path", store.root().toString());
      value.addProperty("writable", !store.description().mirror() && Files.isWritable(store.root())); values.add(value);
    }
    result.add("stores", values); return result;
  }
  public JsonObject sourceViews(JsonObject source) throws Exception {
    var result = new JsonObject(); var working = new JsonArray(); views.summaries(source).forEach(working::add);
    var unavailable = new JsonArray();
    if (text(source, "kind").equals("unmanaged")) {
      var current = views.sourceLocator(uuid(source, "source_id"));
      for (var view : views.all()) {
        var old = view.getAsJsonObject("source_ref");
        if (!old.equals(source) && text(old, "kind").equals("unmanaged")) try {
          if (views.sourceLocator(uuid(old, "source_id")).get("path").equals(current.get("path"))) {
            var item = new JsonObject(); item.add("view_id", view.get("view_id")); item.addProperty("reason", "Recorded fingerprint changed; configuration is inspectable but this view cannot read the replacement file"); unavailable.add(item);
          }
        } catch (IOException ignored) { /* The missing locator cannot prove the same path. */ }
      }
    }
    result.add("unavailable_views", unavailable);
    result.add("working_views", working); var saved = new JsonArray(); var unreadable = new JsonArray();
    boolean incomplete = false;
    for (var store : door.stores()) {
      var listing = VisualizationMetadata.list(store);
      incomplete |= listing.incomplete();
      for (var problem : listing.unreadable()) {
        if (unreadable.size() >= 1024) { incomplete = true; break; }
        var item = new JsonObject(); item.addProperty("store_id", store.description().id());
        item.addProperty("file", problem.file()); item.addProperty("reason", problem.reason()); unreadable.add(item);
      }
      for (var view : listing.views()) if (view.get("source_ref").equals(source)) {
        if (saved.size() >= 1024) { incomplete = true; break; }
        var item = new JsonObject();
        for (var key : List.of("saved_view_id", "revision", "title")) item.add(key, view.get(key).deepCopy());
        item.addProperty("store_id", store.description().id()); saved.add(item);
      }
      if (saved.size() >= 1024 || unreadable.size() >= 1024) { incomplete = true; break; }
    }
    result.add("saved_views", saved); result.add("unreadable", unreadable);
    result.addProperty("listing_incomplete", incomplete); return result;
  }

  public JsonObject save(Authority authority, UUID client, UUID view, JsonObject current, JsonObject request) throws Exception {
    UUID operation = uuid(request, "request_id");
    var intent = request.deepCopy(); intent.addProperty("operation", "save");
    var receipt = views.prepareMutation(authority, view, revision(request), operation, intent, client).get();
    if (receipt.has("result")) return receipt.getAsJsonObject("result");
    current = receipt.getAsJsonObject("snapshot");
    var previous = current.get("saved_view_ref");
    StoreDoor.Selected selected; UUID savedId; long savedRevision; String title;
    if (previous.isJsonNull()) {
      if (!request.has("store_id")) throw new IOException("NO_WRITABLE_STORE: choose a writable local store or Create store");
      selected = door.select(text(request, "store_id")); savedId = operation; savedRevision = 0; title = text(request, "title");
    } else {
      var ref = previous.getAsJsonObject(); selected = door.select(text(ref, "store_id"));
      savedId = uuid(ref, "saved_view_id"); savedRevision = ref.get("revision").getAsLong();
      title = saved(selected, savedId).get("title").getAsString();
    }
    var saved = new VisualizationMetadata(logs.stores().store(selected.root())).save(authority, savedId, savedRevision, operation, title,
        current.getAsJsonObject("source_ref"), current.getAsJsonObject("configuration"), current.get("template_ref")).get();
    var ref = new JsonObject(); ref.addProperty("store_id", selected.description().id()); ref.add("saved_view_id", saved.get("saved_view_id")); ref.add("revision", saved.get("revision"));
    return views.completeMutation(authority, view, operation, null, ref).get();
  }
  public JsonObject checkpoint(Authority authority, UUID client, UUID view, JsonObject current, StoreDoor.Selected store, JsonObject saved) throws Exception {
    var ref = new JsonObject(); ref.addProperty("store_id", store.description().id()); ref.add("saved_view_id", saved.get("saved_view_id")); ref.add("revision", saved.get("revision"));
    return views.checkpointAttached(authority, view, current.get("revision").getAsLong(), ref, client).get();
  }
  public JsonObject openSaved(Authority authority, JsonObject request) throws Exception {
    var store = door.select(text(request, "store_id")); var saved = saved(store, uuid(request, "saved_view_id"));
    sources.resolve(saved.getAsJsonObject("source_ref"));
    var view = views.create(authority, uuid(request, "request_id"), saved.getAsJsonObject("source_ref"), saved.getAsJsonObject("configuration"), saved.get("template_ref").isJsonNull() ? null : saved.getAsJsonObject("template_ref")).get();
    if (!view.get("saved_view_ref").isJsonNull()) return view;
    return checkpoint(authority, null, UUID.fromString(view.get("view_id").getAsString()), view, store, saved);
  }
  private static JsonObject saved(StoreDoor.Selected store, UUID id) throws IOException {
    return VisualizationMetadata.read(store, id);
  }
  public JsonObject value(JsonObject request) throws IOException {
    try (var snapshot = sources.open(request.getAsJsonObject("source_ref"))) {
      Long at = null;
      if (request.has("at_sec")) {
        double seconds = request.get("at_sec").getAsDouble();
        if (!Double.isFinite(seconds) || Math.abs(seconds) > 9_007_199_254.0) throw new IllegalArgumentException("Cursor time is outside the exact microsecond range");
        at = Math.round(seconds * 1e6);
      }
      var result = RecordedValue.at(snapshot, text(request, "entry"), request.has("field") ? text(request, "field") : null, at);
      var out = new JsonObject(); var sample = result.sample();
      out.add("value", sample == null ? JsonNull.INSTANCE : ReadWire.value(sample.value()));
      if (sample == null) out.add("timestamp_us", JsonNull.INSTANCE); else out.addProperty("timestamp_us", Long.toString(sample.timestampUs()));
      out.addProperty("basis", "last_recorded_sample_at_or_before_cursor"); out.add("problems", StoreJson.JSON.toJsonTree(result.problems()));
      out.addProperty("problem_count", result.problemCount()); out.addProperty("problems_truncated", result.problemCount() > result.problems().size());
      sources.resolve(request.getAsJsonObject("source_ref"));
      return out;
    }
  }
  public static long revision(JsonObject body) {
    double value = body.get("revision").getAsDouble();
    if (value < 1 || value > 9_007_199_254_740_991L || value != Math.rint(value)) throw new IllegalArgumentException("Invalid revision");
    return (long) value;
  }
  public static UUID uuid(JsonObject body, String key) { return UUID.fromString(text(body, key)); }
  public static String text(JsonObject body, String key) { return ReadWire.text(body, key); }
}
