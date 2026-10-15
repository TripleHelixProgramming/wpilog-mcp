/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import org.triplehelix.wpilogmcp.mcp.*;
import org.triplehelix.wpilogmcp.visualization.*;

/** Separately described and schema-checked adapters; operation policy belongs to the shared
 * workspace service. Definition text reaches its duplicate-key parser without an earlier parse. */
public final class VisualizationTools {
  private VisualizationTools() {}
  public static void registerAll(ToolRegistry registry) {
    registry.registerTool(new CapabilitiesGet(registry));
    registry.registerTool(new SourceInspect(registry));
    registry.registerTool(new SourceEntriesList(registry));
    registry.registerTool(new SourceEntriesGet(registry));
    registry.registerTool(new SourceInventoryClose(registry));
    registry.registerTool(new TemplateList(registry));
    registry.registerTool(new TemplateGet(registry));
    registry.registerTool(new TemplateCheck(registry));
    registry.registerTool(new TemplateCreate(registry));
    registry.registerTool(new TemplateUpdate(registry));
    registry.registerTool(new TemplateImport(registry));
    registry.registerTool(new TemplateExport(registry));
    registry.registerTool(new ViewList(registry));
    registry.registerTool(new ViewGet(registry));
    registry.registerTool(new ViewCreate(registry));
    registry.registerTool(new ViewUpdate(registry));
    registry.registerTool(new ViewClone(registry));
    registry.registerTool(new ViewSave(registry));
    registry.registerTool(new ViewDiscard(registry));
    registry.registerTool(new ViewDelete(registry));
  }
  private abstract static class Operation extends ToolBase {
    final ToolRegistry registry;
    Operation(ToolRegistry registry) { this.registry = registry; }
    @Override protected final JsonElement executeInternal(JsonObject arguments) throws Exception {
      try {
        var schema = inputSchema();
        for (var key : schema.getAsJsonArray("required")) if (!arguments.has(key.getAsString())) throw new IllegalArgumentException("Missing " + key.getAsString());
        for (var key : arguments.keySet()) if (!schema.getAsJsonObject("properties").has(key)) throw new IllegalArgumentException("Unknown argument " + key);
        if (!new JsonPrimitive(1).equals(arguments.get("api_version"))) throw new IllegalArgumentException("Unsupported api_version");
        var caller = CallerContext.current(); caller.requireLocal();
        var workspace = registry.visualizations();
        var result = run(workspace, caller, arguments);
        if (result.has("view_id")) workspace.notifications.watch(SessionContext.current() == null ? "stdio" : SessionContext.current().getId(), result.get("view_id").getAsString());
        var response = name().equals("visualization_source_entries_list") && result.get("total_matching").getAsInt() == 0
            ? ResponseBuilder.noMatch("No inventory names match the filter").lookedFor(List.of("Authorized fixed inventory names and exact declared types"))
                .hint("Change the filter or reopen from source_ref to refresh the inventory") : ResponseBuilder.success();
        response.addProperty("api_version", 1).addData(resultKey(), result);
        if (name().equals("visualization_source_entries_list")) {
          var limits = new JsonObject(); var page = new JsonObject();
          page.add("total", result.get("total_matching")); page.add("returned", result.get("returned")); page.add("limit", result.get("limit"));
          limits.add("page.entries", page); response.addData("limits", limits);
        }
        if (name().equals("visualization_source_inspect")) {
          var inputs = new JsonObject(); inputs.add("source_ref", result.get("source_ref")); response.addData("inputs", inputs);
        }
        return response.build();
      } catch (Exception failure) {
        Throwable cause = failure;
        while (cause instanceof java.util.concurrent.ExecutionException && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof InventoryFailure inventory) {
          var response = ResponseBuilder.error(inventory.getMessage()).addProperty("api_version", 1).addProperty("code", inventory.code)
              .addProperty("retryable", inventory.retryable);
          if (inventory.retryable) response.addProperty("retry_after_ms", 1000);
          return response.build();
        }
        if (cause instanceof org.triplehelix.wpilogmcp.read.ReadException read) {
          var refusal = VisualizationReadFailure.of(read);
          return ResponseBuilder.error(refusal.message()).addProperty("api_version", 1).addProperty("code", refusal.code())
              .addProperty("retryable", refusal.retryable()).build();
        }
        String message = Objects.toString(cause.getMessage(), cause.getClass().getSimpleName());
        String code = message.matches("^[A-Z_]+:.*") ? message.substring(0, message.indexOf(':')) : cause instanceof java.nio.file.AccessDeniedException || cause instanceof org.triplehelix.wpilogmcp.log.LogFileException ? "ACCESS_DENIED" : "INVALID_REQUEST";
        return ResponseBuilder.error(message).addProperty("api_version", 1).addProperty("code", code).build();
      }
    }
    /** Each adapter explicitly projects its declared inputs before the shared operation. */
    static JsonObject project(JsonObject arguments, String... keys) {
      var result = new JsonObject(); for (var key : keys) if (arguments.has(key)) result.add(key, arguments.get(key).deepCopy()); return result;
    }
    static JsonObject stringItem() { var item = new JsonObject(); item.addProperty("type", "string"); return item; }
    abstract String resultKey();
    abstract JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception;
    ToolRegistry.SchemaBuilder schema() { return new ToolRegistry.SchemaBuilder().addIntegerProperty("api_version", "Visualization API version, currently 1", true, null); }
  }
  static final class CapabilitiesGet extends Operation {
    CapabilitiesGet(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_capabilities_get"; }
    @Override public String description() { return "Supported configuration schema, type and role vocabulary, persistent identity, budgets and preview limits. Result envelope: api_version, status, capabilities. All operations require trusted local transport authority."; }
    @Override String resultKey() { return "capabilities"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var input = project(arguments, "api_version");
      return workspace.capabilities();
    }
  }
  static final class SourceInspect extends Operation {
    SourceInspect(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_source_inspect"; }
    @Override public String description() { return "Inspect a recorded file or captured recording by an admitted path or durable source_ref. Returns identity, entry types, schemas, sample counts and clock bounds. A recording retains individual member inventories and conflicting declarations; its clock distinguishes the shared robot estimate from the last sample. Optional inventory_mode paged returns a session-owned fixed summary without entries or schemas; use the entry list/details operations and close it when finished. Expiry requires reopening from source_ref. Inspection does not enable live visualization panels. Result envelope: api_version, status, source. All operations require trusted local transport authority."; }
    @Override String resultKey() { return "source"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .addProperty("inventory_mode", "string", "full (default) or paged for bounded recording discovery", false)
          .addProperty("path", "string", "Allowed local WPILOG path; alternative to source_ref", false)
          .addProperty("source_ref", "object", "Durable recorded, unmanaged or anchored session source identity; never contains a local path", false)
          .build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var input = project(arguments, "api_version", "path", "source_ref", "inventory_mode");
      return workspace.inspect(caller, input);
    }
  }
  static final class SourceEntriesList extends Operation {
    SourceEntriesList(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_source_entries_list"; }
    @Override public String description() { return "Page a fixed recording inventory opened with inventory_mode paged. Literal query uses Locale.ROOT case folding; types match exact declarations. Use cursor alone for a byte-identical retry or continuation. Unavailable rows have bounded previews, never binding names, and a separate unavailable filter. Expiry requires reopening from source_ref. Result envelope: api_version, status, page, limits; zero matches include looked_for and hint. Page facts include entries, returned, total_matching, total_entry_names, unavailable_entry_names, complete and next_cursor. All operations require trusted local transport authority and the opening session."; }
    @Override String resultKey() { return "page"; }
    @Override public JsonObject inputSchema() {
      return schema().addProperty("inventory_id", "string", "Session-owned inventory handle", true)
          .addProperty("cursor", "string", "Opaque continuation; omit all filter and limit arguments when present", false)
          .addProperty("query", "string", "Literal name substring, at most 256 Unicode code points", false)
          .addArrayProperty("types", "At most 16 exact declared types", stringItem(), false)
          .addProperty("unavailable", "boolean", "List unavailable rows separately; no name/type filter", false)
          .addIntegerProperty("limit", "Names per page, 1 to 256, default 256; bytes may shorten the page", false, null).build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var input = project(arguments, "api_version", "inventory_id", "cursor", "query", "types", "unavailable", "limit");
      return workspace.inventories.list(RecordingInventories.Client.current(caller), input);
    }
  }
  static final class SourceEntriesGet extends Operation {
    SourceEntriesGet(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_source_entries_get"; }
    @Override public String description() { return "Read fresh selected declarations, counts and native schema dependencies for 1 to 32 inventory row_ids. Returns details with detail_revision, members, descriptors, schema_problems and count_basis. Changed selected declarations or recording members refuse with INVENTORY_CHANGED; refresh the inventory and reselect. Count growth is allowed and labeled. No compatible binding is inferred from a conflict. Result envelope: api_version, status, details. All operations require trusted local transport authority and the opening session."; }
    @Override String resultKey() { return "details"; }
    @Override public JsonObject inputSchema() {
      return schema().addProperty("inventory_id", "string", "Session-owned inventory handle", true)
          .addArrayProperty("row_ids", "1 to 32 distinct opaque row IDs from this inventory; never persist in a view", stringItem(), true).build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var input = project(arguments, "api_version", "inventory_id", "row_ids");
      return workspace.inventories.details(RecordingInventories.Client.current(caller), input);
    }
  }
  static final class SourceInventoryClose extends Operation {
    SourceInventoryClose(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_source_inventory_close"; }
    @Override public String description() { return "Release a session-owned recording inventory. Idempotent within this server epoch, including after grant revocation or expiry; no contents are returned. Result envelope: api_version, status, inventory with inventory_id and closed. All operations require trusted local transport authority and the opening session."; }
    @Override String resultKey() { return "inventory"; }
    @Override public JsonObject inputSchema() { return schema().addProperty("inventory_id", "string", "Session-owned inventory handle", true).build(); }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var input = project(arguments, "api_version", "inventory_id");
      return workspace.inventories.release(RecordingInventories.Client.current(caller), input);
    }
  }
  static final class TemplateList extends Operation {
    TemplateList(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_template_list"; }
    @Override public String description() { return "Search immutable bundled and configured-store templates by query. Returns templates, unreadable records and listing_incomplete. Result envelope: api_version, status, catalog. All operations require trusted local transport authority."; }
    @Override String resultKey() { return "catalog"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .addProperty("query", "string", "Case-insensitive name/description substring", false)
          .build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var input = project(arguments, "api_version", "query");
      return workspace.templates(input);
    }
  }
  static final class TemplateGet extends Operation {
    TemplateGet(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_template_get"; }
    @Override public String description() { return "Read an exact pinned template_ref; a changed revision refuses rather than silently substituting. Result envelope: api_version, status, definition. All operations require trusted local transport authority."; }
    @Override String resultKey() { return "definition"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .addProperty("template_ref", "object", "Pinned origin, template_id and revision; store origin also needs store_id", true)
          .build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var input = project(arguments, "api_version", "template_ref");
      return workspace.template(input);
    }
  }
  static final class TemplateCheck extends Operation {
    TemplateCheck(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_template_check"; }
    @Override public String description() { return "Check exact entries or explicit shared-resolver roles against a source. Returns compatibility and per-requirement bindings, reasons, candidates, unit basis and warnings; missing exact names never fall back. Result envelope: api_version, status, compatibility. All operations require trusted local transport authority."; }
    @Override String resultKey() { return "compatibility"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .addProperty("source_ref", "object", "Durable recorded or unmanaged source identity; never contains a local path", true)
          .addProperty("template_ref", "object", "Pinned origin, template_id and revision; store origin also needs store_id", false)
          .addProperty("configuration_json", "string", "Strict JSON text for the versioned configuration; duplicate keys are rejected", false)
          .build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var input = project(arguments, "api_version", "source_ref", "template_ref", "configuration_json");
      return workspace.check(input);
    }
  }
  static final class TemplateCreate extends Operation {
    TemplateCreate(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_template_create"; }
    @Override public String description() { return "Create a store template from configuration_json or a pinned working view. Returns template_ref and portable template. No source bytes or paths are stored in templates; no writable store yields NO_WRITABLE_STORE. Result envelope: api_version, status, definition. All operations require trusted local transport authority."; }
    @Override String resultKey() { return "definition"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .addProperty("request_id", "string", "Unique UUID; reuse only for an identical retry", true)
          .addProperty("store_id", "string", "Configured writable local store UUID", true)
          .addProperty("name", "string", "Template name, 1 to 256 characters", true)
          .addProperty("description", "string", "Template description, at most 4096 characters", true)
          .addProperty("configuration_json", "string", "Strict JSON text for the versioned configuration; duplicate keys are rejected", false)
          .addProperty("view_id", "string", "Working view UUID", false)
          .addProperty("expected_view_revision", "integer", "Pinned working view revision", false)
          .build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var input = project(arguments, "api_version", "request_id", "store_id", "name", "description", "configuration_json", "view_id", "expected_view_revision");
      return workspace.createTemplate(caller, input);
    }
  }
  static final class TemplateUpdate extends Operation {
    TemplateUpdate(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_template_update"; }
    @Override public String description() { return "Explicitly update a store template at expected_revision. Returns template_ref and template; existing views keep pinned configurations. Bundled definitions are immutable. Result envelope: api_version, status, definition. All operations require trusted local transport authority."; }
    @Override String resultKey() { return "definition"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .addProperty("request_id", "string", "Unique UUID; reuse only for an identical retry", true)
          .addProperty("template_ref", "object", "Pinned origin, template_id and revision; store origin also needs store_id", true)
          .addProperty("expected_revision", "integer", "Current object revision; stale revisions conflict", true)
          .addProperty("name", "string", "Template name, 1 to 256 characters", true)
          .addProperty("description", "string", "Template description, at most 4096 characters", true)
          .addProperty("configuration_json", "string", "Strict JSON text for the versioned configuration; duplicate keys are rejected", true)
          .build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var input = project(arguments, "api_version", "request_id", "template_ref", "expected_revision", "name", "description", "configuration_json");
      return workspace.updateTemplate(caller, input);
    }
  }
  static final class TemplateImport extends Operation {
    TemplateImport(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_template_import"; }
    @Override public String description() { return "Validate a portable envelope_json and RFC 8785 SHA-256 before an atomic import. Default mode copy; explicit update requires expected_revision and a newer import. Durable portable receipts deduplicate retries. Same identity/revision with differing content conflicts. Result envelope: api_version, status, definition. All operations require trusted local transport authority."; }
    @Override String resultKey() { return "definition"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .addProperty("request_id", "string", "Unique UUID; reuse only for an identical retry", true)
          .addProperty("store_id", "string", "Configured writable local store UUID", true)
          .addProperty("envelope_json", "string", "Strict portable template JSON text, including its content_sha256", true)
          .addProperty("mode", "string", "copy (default) or explicit update", false)
          .addProperty("expected_revision", "integer", "Current object revision; stale revisions conflict", false)
          .build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var input = project(arguments, "api_version", "request_id", "store_id", "envelope_json", "mode", "expected_revision");
      return workspace.importTemplate(caller, input);
    }
  }
  static final class TemplateExport extends Operation {
    TemplateExport(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_template_export"; }
    @Override public String description() { return "Return envelope_json with format, schema_version, template and content_sha256. Contains declarative configuration only, no source locators, sample buffers or credentials. Result envelope: api_version, status, export. All operations require trusted local transport authority."; }
    @Override String resultKey() { return "export"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .addProperty("template_ref", "object", "Pinned origin, template_id and revision; store origin also needs store_id", true)
          .build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var input = project(arguments, "api_version", "template_ref");
      return workspace.exportTemplate(input);
    }
  }
  static final class ViewList extends Operation {
    ViewList(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_view_list"; }
    @Override public String description() { return "List unexpired working views, optionally for source_ref. Returns views with revision, configuration, source_ref, checkpoint, saved/template references, attachment and expiry. Result envelope: api_version, status, workspace. All operations require trusted local transport authority."; }
    @Override String resultKey() { return "workspace"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .addProperty("source_ref", "object", "Durable recorded or unmanaged source identity; never contains a local path", false)
          .build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var input = project(arguments, "api_version", "source_ref");
      return workspace.listViews(input);
    }
  }
  static final class ViewGet extends Operation {
    ViewGet(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_view_get"; }
    @Override public String description() { return "Read one working view: revision, configuration, source_ref, checkpoint, saved/template references, attachment, expiry, has_unsaved_changes and native_dirty. Does not display it. Result envelope: api_version, status, view. All operations require trusted local transport authority."; }
    @Override String resultKey() { return "view"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .addProperty("view_id", "string", "Working view UUID", true)
          .build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var input = project(arguments, "api_version", "view_id");
      return workspace.getView(input);
    }
  }
  static final class ViewCreate extends Operation {
    ViewCreate(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_view_create"; }
    @Override public String description() { return "Create an independent working view from source_ref and configuration_json or template_ref, or an empty layout. Returns its stable view_id and revision, configuration and persistence state. Creation does not request display or start acquisition. Result envelope: api_version, status, view. All operations require trusted local transport authority."; }
    @Override String resultKey() { return "view"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .addProperty("request_id", "string", "Unique UUID; reuse only for an identical retry", true)
          .addProperty("source_ref", "object", "Durable recorded or unmanaged source identity; never contains a local path", true)
          .addProperty("configuration_json", "string", "Strict JSON text for the versioned configuration; duplicate keys are rejected", false)
          .addProperty("template_ref", "object", "Pinned origin, template_id and revision; store origin also needs store_id", false)
          .build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var input = project(arguments, "api_version", "request_id", "source_ref", "configuration_json", "template_ref");
      return workspace.create(caller, input);
    }
  }
  static final class ViewUpdate extends Operation {
    ViewUpdate(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_view_update"; }
    @Override public String description() { return "CAS edit a working view with strict configuration_json at expected_revision. Returns the updated view and notifies its attached Explorer. Conflicts retain both edits; never retry automatically against a newer revision. Result envelope: api_version, status, view. All operations require trusted local transport authority."; }
    @Override String resultKey() { return "view"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .addProperty("request_id", "string", "Unique UUID; reuse only for an identical retry", true)
          .addProperty("view_id", "string", "Working view UUID", true)
          .addProperty("expected_revision", "integer", "Current object revision; stale revisions conflict", true)
          .addProperty("configuration_json", "string", "Strict JSON text for the versioned configuration; duplicate keys are rejected", true)
          .build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var input = project(arguments, "api_version", "request_id", "view_id", "expected_revision", "configuration_json");
      return workspace.updateView(caller, input);
    }
  }
  static final class ViewClone extends Operation {
    ViewClone(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_view_clone"; }
    @Override public String description() { return "Create another working UUID from the pinned expected_revision. Returns an independent view with the same source and configuration; selection and later edits are not shared. Result envelope: api_version, status, view. All operations require trusted local transport authority."; }
    @Override String resultKey() { return "view"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .addProperty("request_id", "string", "Unique UUID; reuse only for an identical retry", true)
          .addProperty("view_id", "string", "Working view UUID", true)
          .addProperty("expected_revision", "integer", "Current object revision; stale revisions conflict", true)
          .build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var input = project(arguments, "api_version", "request_id", "view_id", "expected_revision");
      return workspace.cloneView(caller, input);
    }
  }
  static final class ViewSave extends Operation {
    ViewSave(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_view_save"; }
    @Override public String description() { return "Save source-bound configuration at expected_revision into a local store and advance its checkpoint. First save requires store_id and title; existing saves retain their destination. A concurrent edit remains uncheckpointed. Returns the view. Result envelope: api_version, status, view. All operations require trusted local transport authority."; }
    @Override String resultKey() { return "view"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .addProperty("request_id", "string", "Unique UUID; reuse only for an identical retry", true)
          .addProperty("view_id", "string", "Working view UUID", true)
          .addProperty("expected_revision", "integer", "Current object revision; stale revisions conflict", true)
          .addProperty("store_id", "string", "Configured writable local store UUID", false)
          .addProperty("title", "string", "Source-bound saved view title, 1 to 256 characters", false)
          .build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var input = project(arguments, "api_version", "request_id", "view_id", "expected_revision", "store_id", "title");
      return workspace.saveView(caller, input);
    }
  }
  static final class ViewDiscard extends Operation {
    ViewDiscard(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_view_discard"; }
    @Override public String description() { return "CAS restore the checkpoint or initial draft layout at expected_revision. Returns the view with an advanced revision; saved store definitions are unchanged. Result envelope: api_version, status, view. All operations require trusted local transport authority."; }
    @Override String resultKey() { return "view"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .addProperty("request_id", "string", "Unique UUID; reuse only for an identical retry", true)
          .addProperty("view_id", "string", "Working view UUID", true)
          .addProperty("expected_revision", "integer", "Current object revision; stale revisions conflict", true)
          .build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var input = project(arguments, "api_version", "request_id", "view_id", "expected_revision");
      return workspace.discardView(caller, input);
    }
  }
  static final class ViewDelete extends Operation {
    ViewDelete(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_view_delete"; }
    @Override public String description() { return "Delete an unattached working view at expected_revision, releasing its live quotas. Retry tombstones expire after six hours, with separate caps of 256 records and 8 MiB; pressure evicts the oldest. Expired or evicted delete retries return VIEW_UNAVAILABLE. Saved definitions and source files remain intact; close an attached editor first. Returns the deleted view receipt. Result envelope: api_version, status, view. All operations require trusted local transport authority."; }
    @Override String resultKey() { return "view"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .addProperty("request_id", "string", "Unique UUID; reuse only for an identical retry", true)
          .addProperty("view_id", "string", "Working view UUID", true)
          .addProperty("expected_revision", "integer", "Current object revision; stale revisions conflict", true)
          .build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var input = project(arguments, "api_version", "request_id", "view_id", "expected_revision");
      return workspace.deleteView(caller, input);
    }
  }
}
