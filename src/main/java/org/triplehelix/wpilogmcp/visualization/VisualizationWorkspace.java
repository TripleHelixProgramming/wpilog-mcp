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
  public final VisualizationLibrary library;
  public final WindowBudgets windows;
  public final ControlNotifications notifications = new ControlNotifications();
  public final RecordedSources sources;
  public final RecordingInventories inventories;
  private final RecordingBindings recordingBindings;
  private final ViewBinding viewBindings;
  private final RecordingAnalysis recordingAnalysis;
  private final SourceCharts sourceCharts;
  private final SourceReduction sourceReduction;
  private final ServerIdentity identity;
  private final UUID epoch = UUID.randomUUID();
  private final LogManager logs;
  private final StoreDoor door;
  private final java.util.function.Supplier<org.triplehelix.wpilogmcp.capture.LiveCapture> capture;
  public VisualizationWorkspace(ServerIdentity identity, LogManager logs) throws IOException {
    this(identity, logs, () -> null);
  }
  public VisualizationWorkspace(ServerIdentity identity, LogManager logs,
      java.util.function.Supplier<org.triplehelix.wpilogmcp.capture.LiveCapture> capture) throws IOException {
    this.capture = capture;
    this.identity = identity; this.logs = logs; door = new StoreDoor(logs::getAllowedDirectories);
    views = new VisualizationService(identity);
    try { library = new VisualizationLibrary(identity); } catch (IOException | RuntimeException failure) { views.close(); throw failure; }
    views.onChanged(notifications::changed); windows = new WindowBudgets(views.windowIds());
    sources = new RecordedSources(logs, views, door);
    inventories = new RecordingInventories(sources); recordingBindings = new RecordingBindings(sources); viewBindings = new ViewBinding(views, recordingBindings);
    recordingAnalysis = new RecordingAnalysis(recordingBindings); sourceCharts = new SourceCharts(sources); sourceReduction = new SourceReduction(sources);
  }
  public void close() { inventories.close(); views.close(); library.close(); try { sources.close(); } catch (IOException e) { throw new java.io.UncheckedIOException(e); } }
  /** For a caller without a transport session, such as direct stdio. */
  public JsonObject capabilities() { return capabilities(null); }
  public JsonObject sourceScopes(JsonObject request) throws IOException {
    var result = logs.sourceScopes().describe(request);
    result.addProperty("server_instance_id", identity.id().toString()); return result;
  }
  /** Both adapters pass the caller's transport session so its own window is marked; no session
   * ID, the caller's or a peer's, appears in the report. */
  public JsonObject capabilities(String session) {
    var result = identity.describe(); result.addProperty("stage", "reusable-recorded-preview");
    result.addProperty("remote_visualization", true); result.addProperty("server_epoch", epoch.toString());
    result.add("blank_configuration", VisualizationContracts.blank()); result.add("schema", VisualizationContracts.schema());
    result.add("source_schema_v2", VisualizationContracts.sourceSchema2());
    result.add("source_reference_versions", JsonParser.parseString("[1,2]"));
    result.add("library", library.capabilities());
    result.addProperty("max_working_views", VisualizationService.MAX_DOCUMENTS);
    result.addProperty("max_working_metadata_bytes", VisualizationService.MAX_STATE_BYTES); result.addProperty("max_working_record_bytes", 4 << 20); result.addProperty("max_working_receipts", 4096); result.addProperty("draft_lifetime_days", 30);
    result.addProperty("max_deleted_view_receipts", VisualizationService.MAX_TOMBSTONES);
    result.addProperty("max_deleted_view_metadata_bytes", VisualizationService.MAX_TOMBSTONE_BYTES);
    result.addProperty("deleted_view_lifetime_hours", VisualizationService.TOMBSTONE_LIFETIME.toHours());
    result.add("decoded_buffer_budget", WindowBudgets.limits()); result.add("attached_windows", windows.describe(session));
    result.addProperty("max_plot_points", 65536); result.addProperty("max_live_plot_points", 4096); result.addProperty("max_visible_series", 32);
    result.addProperty("max_raw_page_records", 4096); result.addProperty("max_raw_page_bytes", 1048576);
    result.addProperty("max_template_receipts", 64); result.addProperty("max_template_metadata_bytes", 1048576);
    result.addProperty("recording_bindings", true); result.add("recording_binding_limits", RecordingBindings.limits());
    result.addProperty("recording_views", true); result.addProperty("recording_view_follow", true);
    result.addProperty("source_reduction", true); result.add("source_reduction_limits", SourceReduction.capabilities());
    result.addProperty("source_charts", true); result.add("source_chart_limits", SourceCharts.capabilities());
    result.addProperty("recording_analysis", true); result.add("recording_analysis_limits", RecordingAnalysis.capabilities());
    result.addProperty("live", false); result.addProperty("live_source_inspection", true); result.addProperty("source_health", true);
    result.addProperty("max_recording_inventory_bytes", RecordingInventory.MAX_BYTES);
    result.addProperty("recording_inventory_paging", true); result.add("recording_inventory_limits", inventories.capabilities());
    result.addProperty("templates", true); result.add("roles", VisualizationBindings.roles());
    result.add("declared_types", JsonParser.parseString("[\"boolean\",\"int64\",\"float\",\"double\",\"string\",\"json\",\"raw\",\"boolean[]\",\"int64[]\",\"float[]\",\"double[]\",\"string[]\",\"struct:Name\",\"struct:Name[]\",\"structarray:Name\"]"));
    return result;
  }
  public ViewBinding.Prepared prepareViewFollow(Authority caller, JsonObject request, java.util.function.BooleanSupplier alive) throws IOException {
    VisualizationAccess.transport(caller, version2(request) ? 2 : 1, VisualizationAccess.Operation.READ);
    if (request.has("api_version") && !Set.of(new JsonPrimitive(1), new JsonPrimitive(2)).contains(request.get("api_version")))
      throw new IllegalArgumentException("Unsupported visualization api_version");
    if (!request.keySet().stream().allMatch(k -> Set.of("api_version", "view_id", "expected_revision").contains(k)))
      throw new IllegalArgumentException("View binding accepts only view_id and expected_revision");
    // The read API predates a version on view_binding and accepted both source forms locally.
    // Preserve that local contract; remote callers must explicitly opt into qualified API 2.
    if (request.has("api_version")) views.requireSourceVersion(uuid(request, "view_id"), version2(request) ? 2 : 1);
    return viewBindings.prepare(uuid(request, "view_id"), TemplateDefinition.revision(request, "expected_revision", false), alive);
  }
  public JsonObject bindView(Authority caller, JsonObject request) throws IOException {
    try (var prepared = prepareViewFollow(caller, request, () -> true)) { return prepared.describe(); }
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
    VisualizationAccess.require(caller, VisualizationAccess.Operation.WORKING); UUID id = uuid(request, "view_id"), operation = uuid(request, "request_id");
    var source = views.get(id).getAsJsonObject("source_ref");
    if (VisualizationContracts.kind(source).equals("session")) admitViewSource(source);
    var intent = request.deepCopy(); intent.addProperty("operation", "clone");
    var receipt = views.prepareMutation(caller, id, TemplateDefinition.revision(request, "expected_revision", false), operation, intent, null).get();
    if (receipt.has("result")) return receipt.getAsJsonObject("result");
    var view = receipt.getAsJsonObject("snapshot");
    var cloned = views.create(caller, operation, view.getAsJsonObject("source_ref"), view.getAsJsonObject("configuration"),
        view.get("template_ref").isJsonNull() ? null : view.getAsJsonObject("template_ref")).get();
    return views.completeMutation(caller, id, operation, cloned, null).get();
  }

  public JsonObject saveView(Authority caller, JsonObject request) throws Exception {
    VisualizationAccess.require(caller, VisualizationAccess.Operation.WORKING); UUID id = uuid(request, "view_id"); var args = request.deepCopy(); args.add("revision", args.get("expected_revision"));
    return save(caller, null, id, views.get(id), args);
  }
  public JsonObject health(Authority caller, JsonObject request) throws IOException {
    VisualizationAccess.require(caller, VisualizationAccess.Operation.WORKING); RecordingInventories.checkRequest(request);
    return sources.health(request.getAsJsonObject("source_ref"), capture.get());
  }
  public JsonObject inspect(Authority caller, JsonObject request) throws Exception {
    boolean paged = RecordingInventories.mode(request).equals("paged");
    if (paged) RecordingInventories.checkRequest(request);
    if (request.has("path") == request.has("source_ref")) throw new IllegalArgumentException("Name exactly one path or source_ref");
    boolean qualified = request.has("api_version") && request.get("api_version").getAsInt() == 2;
    String scope = request.has("scope_id") ? text(request, "scope_id") : null;
    var out = request.has("path") ? sources.identify(caller, Path.of(text(request, "path")), scope, qualified) : new JsonObject();
    var source = request.has("source_ref") ? request.getAsJsonObject("source_ref") : out.getAsJsonObject("source_ref");
    if (qualified) source = sources.qualify(source, scope);
    if (paged) out = new JsonObject();
    out.add("source_ref", source); out.add("inventory", describe(RecordingInventories.Client.current(caller), source, request)); return out;
  }
  public JsonObject describe(RecordingInventories.Client client, JsonObject source, JsonObject request) throws Exception {
    String mode = RecordingInventories.mode(request);
    if (mode.equals("full")) return sources.describe(source);
    RecordingInventories.checkRequest(request); return inventories.open(client, source);
  }
  public JsonObject create(Authority authority, JsonObject body) throws Exception {
    VisualizationAccess.require(authority, VisualizationAccess.Operation.WORKING); var source = VisualizationContracts.source(body.getAsJsonObject("source_ref"));
    if (body.has("template_ref") && (body.has("configuration") || body.has("configuration_json"))) throw new IllegalArgumentException("Choose a template_ref or configuration, not both");
    var definition = body.has("template_ref") ? template(body) : null;
    var configuration = definition != null ? definition.getAsJsonObject("template").getAsJsonObject("configuration")
        : body.has("configuration") || body.has("configuration_json") ? configuration(body) : VisualizationContracts.blank();
    UUID request = uuid(body, "request_id"); configuration = VisualizationContracts.configuration(configuration);
    admitViewSource(source);
    return views.create(authority, request, source, configuration,
        definition == null ? null : definition.getAsJsonObject("template_ref")).get();
  }
  /** Metadata admission shares recording identity/bounds, but retains no prefix while writing
   * working state. Unresolved requirements remain editable intent, never a cached permission. */
  private void admitViewSource(JsonObject source) throws IOException {
    if (VisualizationContracts.kind(source).equals("session")) recordingBindings.check(source, VisualizationContracts.blank());
    else sources.resolve(source);
  }
  public JsonObject templates(JsonObject request) throws IOException {
    if (version2(request)) return libraryTemplates(request);
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
    var ref = validated.getAsJsonObject();
    if (ref.has("library_id")) {
      if (!version2(body)) throw new IllegalArgumentException("Library references require API version 2");
      return await(library.get(Authority.STDIO_LOCAL, "template", ref));
    }
    String origin = text(ref, "origin"); UUID id = uuid(ref, "template_id");
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
    if (VisualizationContracts.kind(source).equals("session")) return recordingBindings.check(source, config);
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
    if (version2(request)) { fields(request, "request_id", "name", "description", "configuration", "configuration_json", "view_id", "expected_view_revision"); library.requireAvailable(); }
    VisualizationAccess.require(authority, VisualizationAccess.Operation.WORKING); UUID id = uuid(request, "request_id");
    if (request.has("view_id") && (request.has("configuration") || request.has("configuration_json"))) throw new IllegalArgumentException("Name a view or a configuration, not both");
    JsonObject reservation = null;
    if (version2(request) && request.has("view_id")) {
      var intent = request.deepCopy(); intent.addProperty("operation", "save_template");
      reservation = views.prepareMutation(authority, uuid(request, "view_id"), TemplateDefinition.revision(request, "expected_view_revision", false), id, intent, null).get();
      if (reservation.has("result")) return reservation.getAsJsonObject("result");
    }
    JsonObject config = reservation != null ? reservation.getAsJsonObject("snapshot").getAsJsonObject("configuration")
        : request.has("view_id") ? viewConfiguration(request) : configuration(request);
    if (version2(request)) {
      noStore(request); library.requireAvailable();
      var saved = library.save(authority, "template", id, 0, templateCandidate(id, 1, request, config), null,
          request.has("view_id") ? uuid(request, "view_id") : null).get();
      return reservation == null ? saved : completeLibraryMutation(authority, uuid(request, "view_id"), id, saved, null);
    }
    var store = destination(request);
    return new TemplateMetadata(logs.stores().store(store.root()), store.description().id()).save(authority, id, id, 0,
        text(request, "name"), text(request, "description"), config).get();
  }
  private JsonObject viewConfiguration(JsonObject request) throws IOException {
    var view = views.get(uuid(request, "view_id"));
    if (TemplateDefinition.revision(request, "expected_view_revision", false) != view.get("revision").getAsLong()) throw new IOException("REVISION_CONFLICT: working view changed");
    return view.getAsJsonObject("configuration");
  }
  public JsonObject updateTemplate(Authority authority, JsonObject request) throws Exception {
    if (version2(request)) fields(request, "request_id", "template_ref", "expected_revision", "name", "description", "configuration", "configuration_json");
    VisualizationAccess.require(authority, VisualizationAccess.Operation.WORKING); var ref = TemplateDefinition.validateReference(request.get("template_ref")).getAsJsonObject();
    if (version2(request)) {
      noStore(request); library.requireReference("template", ref);
      long expected = TemplateDefinition.revision(request, "expected_revision", false);
      if (TemplateDefinition.revision(ref, "revision", false) != expected) throw new IOException("REVISION_CONFLICT: reference and expected revision differ");
      return library.save(authority, "template", uuid(request, "request_id"), expected,
          templateCandidate(uuid(ref, "template_id"), expected + 1, request, configuration(request)), null).get();
    }
    if (!text(ref, "origin").equals("store")) throw new IOException("READ_ONLY: bundled templates are immutable; save a store copy");
    if (TemplateDefinition.revision(ref, "revision", false) != TemplateDefinition.revision(request, "expected_revision", false)) throw new IOException("REVISION_CONFLICT: reference and expected revision differ");
    var store = door.select(text(ref, "store_id"));
    return new TemplateMetadata(logs.stores().store(store.root()), store.description().id()).save(authority, uuid(request, "request_id"), uuid(ref, "template_id"),
        TemplateDefinition.revision(request, "expected_revision", false), text(request, "name"), text(request, "description"), configuration(request)).get();
  }
  public JsonObject exportTemplate(JsonObject request) throws IOException {
    return TemplateDefinition.exportReply(template(request).getAsJsonObject("template"));
  }
  /** Read-only choice preview. The actual import rechecks under the store lock. */
  public JsonObject importPreview(JsonObject request) throws IOException {
    if (version2(request)) { fields(request, "envelope_json"); noStore(request); return await(library.importPreview(Authority.STDIO_LOCAL, request)); }
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
    VisualizationAccess.require(authority, VisualizationAccess.Operation.WORKING);
    if (version2(request)) { fields(request, "request_id", "envelope_json", "mode", "expected_revision"); noStore(request); return library.importTemplate(authority, request).get(); }
    var store = destination(request);
    return new TemplateMetadata(logs.stores().store(store.root()), store.description().id()).importDefinition(authority, uuid(request, "request_id"), text(request, "envelope_json"),
        request.has("mode") ? text(request, "mode") : "copy", request.has("expected_revision") ? TemplateDefinition.revision(request, "expected_revision", false) : null).get();
  }
  public JsonObject reduction(JsonObject request, java.util.function.BooleanSupplier alive) throws IOException { return sourceReduction.read(request, alive); }
  public JsonObject histogram(JsonObject request, java.util.function.BooleanSupplier alive) throws Exception { return sourceCharts.read(request, false, alive); }
  public JsonObject scatter(JsonObject request, java.util.function.BooleanSupplier alive) throws Exception { return sourceCharts.read(request, true, alive); }
  public JsonObject statistics(JsonObject request) throws Exception {
    return statistics(request, () -> true);
  }
  public JsonObject statistics(JsonObject request, java.util.function.BooleanSupplier alive) throws Exception {
    if (VisualizationContracts.kind(request.getAsJsonObject("source_ref")).equals("session")) return recordingAnalysis.statistics(request, alive);
    var source = request.getAsJsonObject("source_ref"); var args = new JsonObject(); args.addProperty("path", sources.resolve(source).toString()); args.addProperty("name", text(request, "entry"));
    for (var key : List.of("field", "start_time", "end_time", "last_seconds")) if (request.has(key)) args.add(key, request.get(key));
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
      if (snapshot.replica() != null) out.add("replica", StoreJson.JSON.toJsonTree(snapshot.replica()));
      out.addProperty("next_ordinal", first + batch.recordsVisited()); out.addProperty("total_records", snapshot.sampleCount(query.entry()));
      out.addProperty("basis", "physical entry ordinal; duplicate/backward timestamps are retained"); sources.resolve(source); return out;
    }
  }
  private static long micros(double seconds) {
    if (!Double.isFinite(seconds) || Math.abs(seconds) > 9_007_199_254.0) throw new IllegalArgumentException("Time exceeds the exact microsecond range"); return Math.round(seconds * 1e6);
  }
  /** Preview is conservative for session views: removing one member can invalidate the whole
   * recording binding. An unreadable/oversized definition refuses the preview instead of hiding it. */
  public JsonArray replicaCleanupImpact(StoreManifest.Header header, List<ReplicaCatalog.Entry> entries) throws IOException {
    var hashes = entries.stream().map(ReplicaCatalog.Entry::sha256).collect(java.util.stream.Collectors.toSet());
    var sessions = entries.stream().map(ReplicaCatalog.Entry::sessionId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
    java.util.function.Predicate<JsonObject> matches = ref -> {
      if (ref.has("scope_id") && (!ref.get("scope_id").getAsString().equals(header.id())
          || !ref.get("server_instance_id").getAsString().equals(identity.id().toString()))) return false;
      var content = VisualizationContracts.content(ref); String kind = content.get("kind").getAsString();
      if (kind.equals("unmanaged")) return false; // A store member is always identified as recorded/session by admission.
      if (kind.equals("file_version")) return entries.stream().anyMatch(file -> file.identity() != null
          && content.get("master_store_id").getAsString().equals(file.identity().masterStoreId())
          && content.get("file_id").getAsString().equals(file.identity().fileId())
          && content.get("file_version").getAsString().equals(file.identity().fileVersion()));
      if (!content.get("origin_store_id").getAsString().equals(header.origin().storeId())) return false;
      return kind.equals("recorded") ? hashes.contains(content.get("content_sha256").getAsString())
          : sessions.contains(content.get("session_id").getAsString());
    };
    var affected = new JsonArray();
    for (var view : views.cleanupSources()) if (matches.test(view.getAsJsonObject("source_ref"))) {
      view.remove("source_ref"); view.addProperty("kind", "working"); affected.add(view);
    }
    try { library.cleanupImpact(matches).get(10, java.util.concurrent.TimeUnit.SECONDS).getAsJsonArray("views").forEach(affected::add); }
    catch (Exception failure) { throw new IOException("VIEW_IMPACT_UNAVAILABLE: server library could not be inspected; no cleanup admitted", failure); }
    int stores = 0;
    for (var selected : door.stores()) {
      if (++stores > 64) throw new IOException("VIEW_IMPACT_UNAVAILABLE: more than 64 stores to inspect");
      var listing = VisualizationMetadata.list(selected);
      if (listing.incomplete() || !listing.unreadable().isEmpty()) throw new IOException("VIEW_IMPACT_UNAVAILABLE: legacy saved views are unreadable or exceed the listing bound");
      for (var view : listing.views()) if (matches.test(view.getAsJsonObject("source_ref"))) {
        var row = new JsonObject(); row.addProperty("kind", "legacy"); row.addProperty("store_id", selected.description().id());
        for (String key : List.of("saved_view_id", "revision")) row.add(key, view.get(key)); affected.add(row);
        if (affected.size() > 2048) throw new IOException("VIEW_IMPACT_UNAVAILABLE: affected view count exceeds 2048");
      }
    }
    return affected;
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
  private JsonObject workingSourceViews(JsonObject source) throws IOException {
    var result = new JsonObject(); var working = new JsonArray(); views.summaries(source).forEach(working::add);
    var unavailable = new JsonArray();
    if (VisualizationContracts.kind(source).equals("unmanaged")) {
      var current = views.sourceLocator(uuid(VisualizationContracts.content(source), "source_id"));
      for (var view : views.all()) {
        var old = view.getAsJsonObject("source_ref");
        if (!old.equals(source) && VisualizationContracts.kind(old).equals("unmanaged")) try {
          if (views.sourceLocator(uuid(VisualizationContracts.content(old), "source_id")).get("path").equals(current.get("path"))) {
            var item = new JsonObject(); item.add("view_id", view.get("view_id")); item.addProperty("reason", "Recorded fingerprint changed; configuration is inspectable but this view cannot read the replacement file"); unavailable.add(item);
          }
        } catch (IOException ignored) { /* The missing locator cannot prove the same path. */ }
      }
    }
    result.add("unavailable_views", unavailable);
    result.add("working_views", working); return result;
  }
  public JsonObject sourceViews(JsonObject source) throws Exception {
    var result = workingSourceViews(source); var saved = new JsonArray(); var unreadable = new JsonArray();
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
    if (version2(request)) { fields(request, "request_id", "view_id", "revision", "expected_revision", "title"); noStore(request); library.requireAvailable(); }
    var intent = request.deepCopy(); intent.addProperty("operation", "save");
    var receipt = views.prepareMutation(authority, view, revision(request), operation, intent, client).get();
    if (receipt.has("result")) return receipt.getAsJsonObject("result");
    current = receipt.getAsJsonObject("snapshot");
    var previous = current.get("saved_view_ref");
    if (version2(request)) {
      UUID savedId = operation; long expected = 0; String title;
      if (!previous.isJsonNull() && previous.getAsJsonObject().has("library_id")) {
        var ref = previous.getAsJsonObject(); library.requireReference("view", ref);
        savedId = uuid(ref, "saved_view_id"); expected = ref.get("revision").getAsLong();
        // A missing title is resolved inside the library transaction, after checking its retry receipt.
        title = request.has("title") ? text(request, "title") : null;
      } else if (!previous.isJsonNull()) {
        var migration = new JsonObject(); migration.addProperty("api_version", 2); migration.addProperty("kind", "view"); migration.add("legacy_ref", previous);
        var migrated = migrateLibrary(authority, migration).getAsJsonObject("saved_view");
        savedId = uuid(migrated, "saved_view_id"); expected = migrated.get("revision").getAsLong(); title = text(migrated, "title");
      } else title = text(request, "title");
      var candidate = new JsonObject(); candidate.addProperty("schema_version", 1); candidate.addProperty("saved_view_id", savedId.toString());
      candidate.addProperty("revision", expected + 1); candidate.addProperty("title", title);
      for (var key : List.of("source_ref", "configuration", "template_ref")) candidate.add(key, current.get(key).deepCopy());
      var source = current.getAsJsonObject("source_ref");
      var locator = VisualizationContracts.kind(source).equals("unmanaged") ? views.sourceLocator(uuid(VisualizationContracts.content(source), "source_id")) : null;
      var preview = current.deepCopy(); preview.add("saved_view_ref", library.reference("view", candidate));
      preview.add("checkpoint_configuration", current.get("configuration"));
      VisualizationLibrary.wire(preview);
      var saved = library.save(authority, "view", operation, expected, candidate, locator, view).get();
      return completeLibraryMutation(authority, view, operation, null, saved.getAsJsonObject("saved_view_ref"));
    }
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
    VisualizationAccess.require(authority, VisualizationAccess.Operation.WORKING);
    if (version2(request)) {
      fields(request, "request_id", "saved_view_ref"); noStore(request); var ref = request.getAsJsonObject("saved_view_ref");
      var saved = await(library.get(authority, "view", ref)).getAsJsonObject("saved_view");
      requireWorkingReply(saved, ref);
      var source = saved.getAsJsonObject("source_ref"); var locator = await(library.locator(authority, ref));
      if (!locator.isEmpty()) views.rememberSource(authority, uuid(VisualizationContracts.content(source), "source_id"), locator).get();
      admitViewSource(source);
      return views.createSaved(authority, uuid(request, "request_id"), source, saved.getAsJsonObject("configuration"),
          saved.get("template_ref").isJsonNull() ? null : saved.getAsJsonObject("template_ref"), ref).get();
    }
    var store = door.select(text(request, "store_id")); var saved = saved(store, uuid(request, "saved_view_id"));
    VisualizationContracts.transportSource(saved, request.has("api_version") ? request.get("api_version").getAsInt() : 1);
    admitViewSource(saved.getAsJsonObject("source_ref"));
    var view = views.create(authority, uuid(request, "request_id"), saved.getAsJsonObject("source_ref"), saved.getAsJsonObject("configuration"), saved.get("template_ref").isJsonNull() ? null : saved.getAsJsonObject("template_ref")).get();
    if (!view.get("saved_view_ref").isJsonNull()) return view;
    return checkpoint(authority, null, UUID.fromString(view.get("view_id").getAsString()), view, store, saved);
  }
  private static JsonObject saved(StoreDoor.Selected store, UUID id) throws IOException {
    return VisualizationMetadata.read(store, id);
  }
  public JsonObject value(JsonObject request) throws IOException {
    return value(request, () -> true);
  }
  public JsonObject value(JsonObject request, java.util.function.BooleanSupplier alive) throws IOException {
    if (VisualizationContracts.kind(request.getAsJsonObject("source_ref")).equals("session")) return recordingAnalysis.value(request, alive);
    try (var snapshot = sources.open(request.getAsJsonObject("source_ref"))) {
      Long at = null;
      if (request.has("at_sec")) {
        double seconds = request.get("at_sec").getAsDouble();
        if (!Double.isFinite(seconds) || Math.abs(seconds) > 9_007_199_254.0) throw new IllegalArgumentException("Cursor time is outside the exact microsecond range");
        at = Math.round(seconds * 1e6);
      }
      var result = RecordedValue.at(snapshot, text(request, "entry"), request.has("field") ? text(request, "field") : null, at);
      var out = new JsonObject(); var sample = result.sample();
      if (snapshot.replica() != null) out.add("replica", StoreJson.JSON.toJsonTree(snapshot.replica()));
      out.add("value", sample == null ? JsonNull.INSTANCE : ReadWire.value(sample.value()));
      if (sample == null) out.add("timestamp_us", JsonNull.INSTANCE); else out.addProperty("timestamp_us", Long.toString(sample.timestampUs()));
      out.addProperty("basis", "last_recorded_sample_at_or_before_cursor"); out.add("problems", StoreJson.JSON.toJsonTree(result.problems()));
      out.addProperty("problem_count", result.problemCount()); out.addProperty("problems_truncated", result.problemCount() > result.problems().size());
      sources.resolve(request.getAsJsonObject("source_ref"));
      return out;
    }
  }
  private static void requireWorkingReply(JsonObject saved, JsonObject ref) throws IOException {
    var preview = new JsonObject();
    for (var key : List.of("configuration", "initial_configuration", "checkpoint_configuration")) preview.add(key, saved.get("configuration"));
    preview.add("source_ref", saved.get("source_ref")); preview.add("template_ref", saved.get("template_ref")); preview.add("saved_view_ref", ref);
    preview.addProperty("working_metadata_allowance", "x".repeat(4096)); VisualizationLibrary.wire(preview);
  }
  private static void fields(JsonObject request, String... allowed) {
    var names = new HashSet<>(List.of(allowed)); names.add("api_version");
    if (!names.containsAll(request.keySet())) throw new IllegalArgumentException("Unknown library request fields");
  }
  private static boolean version2(JsonObject request) { return new JsonPrimitive(2).equals(request.get("api_version")); }
  /** A failure after durable publication is not permission to start another save intent. */
  private JsonObject completeLibraryMutation(Authority authority, UUID view, UUID request, JsonObject result, JsonObject ref) throws IOException {
    try { return await(views.completeMutation(authority, view, request, result, ref)); }
    catch (Exception failure) { throw new IOException("LIBRARY_CHECKPOINT_PENDING: definition saved; retry the identical request to recover its working checkpoint", failure); }
  }
  private static void noStore(JsonObject request) {
    if (request.has("store_id")) throw new IllegalArgumentException("API version 2 saves belong to the server library; omit store_id");
  }
  private static JsonObject await(java.util.concurrent.CompletableFuture<JsonObject> future) throws IOException {
    try { return future.get(); }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Library request interrupted", interrupted); }
    catch (java.util.concurrent.ExecutionException failure) {
      if (failure.getCause() instanceof IOException io) throw io;
      if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
      throw new IOException("Library operation failed", failure.getCause());
    }
  }
  private static JsonObject templateCandidate(UUID id, long revision, JsonObject request, JsonObject configuration) {
    var t = new JsonObject(); t.addProperty("schema_version", 1); t.addProperty("template_id", id.toString()); t.addProperty("revision", revision);
    t.addProperty("name", text(request, "name")); t.addProperty("description", text(request, "description")); t.add("configuration", configuration);
    return TemplateDefinition.validate(t);
  }
  public JsonObject libraryList(Authority caller, JsonObject request) throws IOException {
    if (!version2(request)) throw new IllegalArgumentException("Library operations require API version 2");
    return await(library.list(caller, request));
  }
  private JsonObject libraryTemplates(JsonObject request) throws IOException {
    var query = request.deepCopy(); query.addProperty("kind", "template"); query.addProperty("include_bundled", true);
    var page = await(library.list(Authority.STDIO_LOCAL, query)); var templates = new JsonArray(); var unreadable = new JsonArray();
    for (var entry : page.getAsJsonArray("items")) {
      var row = entry.getAsJsonObject();
      if (!row.get("available").getAsBoolean()) { unreadable.add(row); continue; }
      var item = new JsonObject(); item.add("template_ref", row.get("template_ref")); var summary = new JsonObject();
      summary.add("name", row.get("title")); summary.add("description", row.has("description") ? row.get("description") : new JsonPrimitive("")); item.add("template", summary); templates.add(item);
    }
    page.remove("items"); page.add("templates", templates); page.add("unreadable", unreadable); page.addProperty("listing_incomplete", !page.get("next").isJsonNull()); return page;
  }
  public JsonObject migrateLibrary(Authority caller, JsonObject request) throws Exception {
    caller.requireLocal(); if (!version2(request)) throw new IllegalArgumentException("Library migration requires API version 2");
    fields(request, "kind", "legacy_ref"); library.requireAvailable(); String kind = text(request, "kind"); var legacy = request.getAsJsonObject("legacy_ref");
    if (kind.equals("template")) TemplateDefinition.validateReference(legacy); else LibraryReferences.saved(legacy);
    if (!legacy.has("store_id")) throw new IllegalArgumentException("Migration requires a store-backed legacy reference");
    var store = door.select(text(legacy, "store_id")); UUID oldId = uuid(legacy, VisualizationLibrary.idKey(kind));
    var object = kind.equals("template") ? TemplateMetadata.read(store, oldId) : VisualizationMetadata.read(store, oldId);
    if (TemplateDefinition.revision(legacy, "revision", false) != object.get("revision").getAsLong()) throw new IOException("REVISION_CONFLICT: legacy revision changed; refresh before migration");
    object.remove("request_id"); JsonObject locator = null;
    if (kind.equals("view") && VisualizationContracts.kind(object.getAsJsonObject("source_ref")).equals("unmanaged")) {
      try { locator = views.sourceLocator(uuid(VisualizationContracts.content(object.getAsJsonObject("source_ref")), "source_id")); }
      catch (IOException unavailable) { /* Preserve unresolved source intent. Opening still requires an authorized locator. */ }
    }
    return library.migrate(caller, kind, legacy, object, locator).get();
  }
  public JsonObject librarySourceViews(JsonObject request) throws IOException {
    var source = VisualizationContracts.source(request.getAsJsonObject("source_ref"));
    var list = request.deepCopy(); list.addProperty("kind", "view"); var page = await(library.list(Authority.STDIO_LOCAL, list));
    var saved = new JsonArray(); var unreadable = new JsonArray();
    for (var entry : page.getAsJsonArray("items")) {
      var row = entry.getAsJsonObject(); if (!row.get("available").getAsBoolean()) { unreadable.add(row); continue; }
      var item = row.deepCopy(); var ref = row.getAsJsonObject("saved_view_ref");
      item.add("saved_view_id", ref.get("saved_view_id")); item.add("revision", ref.get("revision")); saved.add(item);
    }
    page.remove("items"); page.add("saved_views", saved); page.add("unreadable", unreadable);
    var working = workingSourceViews(source); working.entrySet().forEach(e -> page.add(e.getKey(), e.getValue())); page.addProperty("listing_incomplete", !page.get("next").isJsonNull()); return page;
  }
  public static long revision(JsonObject body) {
    return TemplateDefinition.revision(body, "revision", false);
  }
  public static UUID uuid(JsonObject body, String key) { return UUID.fromString(text(body, key)); }
  public static String text(JsonObject body, String key) { return ReadWire.text(body, key); }
}
