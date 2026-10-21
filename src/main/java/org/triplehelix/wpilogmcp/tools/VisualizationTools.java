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
    registry.registerTool(new SourceHealth(registry));
    registry.registerTool(new SourceEntriesList(registry));
    registry.registerTool(new SourceEntriesGet(registry));
    registry.registerTool(new SourceInventoryClose(registry));
    registry.registerTool(new SourceValue(registry));
    registry.registerTool(new SourceStatistics(registry));
    registry.registerTool(new SourceReduction(registry));
    registry.registerTool(new SourceHistogram(registry));
    registry.registerTool(new SourceScatter(registry));
    registry.registerTool(new LibraryList(registry));
    registry.registerTool(new LibraryMigrate(registry));
    registry.registerTool(new SavedViewOpen(registry));
    registry.registerTool(new TemplateList(registry));
    registry.registerTool(new TemplateGet(registry));
    registry.registerTool(new TemplateCheck(registry));
    registry.registerTool(new TemplateCreate(registry));
    registry.registerTool(new TemplateUpdate(registry));
    registry.registerTool(new TemplateImport(registry));
    registry.registerTool(new TemplateExport(registry));
    registry.registerTool(new ViewList(registry));
    registry.registerTool(new ViewGet(registry));
    registry.registerTool(new ViewBind(registry));
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
      int version = 1;
      try {
        var schema = inputSchema();
        for (var key : schema.getAsJsonArray("required")) if (!arguments.has(key.getAsString())) throw new IllegalArgumentException("Missing " + key.getAsString());
        for (var key : arguments.keySet()) if (!schema.getAsJsonObject("properties").has(key)) throw new IllegalArgumentException("Unknown argument " + key);
        if (!new JsonPrimitive(1).equals(arguments.get("api_version")) && !new JsonPrimitive(2).equals(arguments.get("api_version"))) throw new IllegalArgumentException("Unsupported api_version");
        version = arguments.get("api_version").getAsInt();
        VisualizationContracts.transportSource(arguments, arguments.get("api_version").getAsInt());
        LibraryReferences.version(arguments, version);
        var caller = CallerContext.current(); VisualizationAccess.transport(caller, version, VisualizationAccess.tool(name()));
        var workspace = registry.visualizations();
        if (arguments.has("view_id")) workspace.views.requireSourceVersion(UUID.fromString(arguments.get("view_id").getAsString()), version);
        try (var reservation = LibraryRequests.tool(name(), arguments) ? workspace.inventories.reserveMetadataResponse() : null) {
          var result = run(workspace, caller, arguments);
          if (reservation != null) reservation.finish(result);
          VisualizationContracts.transportSource(result, version); LibraryReferences.version(result, version);
          if (result.has("views")) for (var view : result.getAsJsonArray("views")) VisualizationContracts.transportSource(view.getAsJsonObject(), version);
          if (result.has("view_id")) workspace.notifications.watch(SessionContext.current() == null ? "stdio" : SessionContext.current().getId(), result.get("view_id").getAsString());
          var response = name().equals("visualization_source_entries_list") && result.get("total_matching").getAsInt() == 0
              ? ResponseBuilder.noMatch("No inventory names match the filter").lookedFor(List.of("Authorized fixed inventory names and exact declared types"))
                  .hint("Change the filter or reopen from source_ref to refresh the inventory") : ResponseBuilder.success();
          if (LibraryRequests.tool(name(), arguments) && result.has("total_matching") && result.get("total_matching").getAsLong() == 0)
            response = ResponseBuilder.noMatch("No matching library definitions").lookedFor(List.of("This server library and the requested title filter"))
                .hint("Change the filter, save a definition, or explicitly migrate a legacy store definition");
          if (name().equals("visualization_view_bind") && result.getAsJsonArray("queries").isEmpty())
            response = ResponseBuilder.noMatch("No recording requirements resolved")
                .lookedFor(List.of("Every configured exact entry or explicit resolver role in the admitted recording members"))
                .hint("Inspect binding.compatibility, edit the requirements, or check again after new declarations arrive");
          boolean chart = name().equals("visualization_source_histogram") || name().equals("visualization_source_scatter");
          boolean analysis = name().equals("visualization_source_reduction") || chart || name().equals("visualization_source_value") || name().equals("visualization_source_statistics");
          if (analysis) {
            if (result.has("success") && !result.get("success").getAsBoolean() && result.has("error")) response = ResponseBuilder.error(result.get("error").getAsString());
            else if (name().equals("visualization_source_value") && result.get("value").isJsonNull())
              response = ResponseBuilder.noMatch("No recorded value at or before the requested cursor")
                  .lookedFor(List.of("Decodable records of the exact entry and scalar field in the admitted source"))
                  .hint("Choose a later cursor, inspect problems, or wait for a sample in the same recording");
            if (result.has("status") && result.get("status").getAsString().equals("no_match"))
              response = ResponseBuilder.noMatch(result.get("reason").getAsString())
                  .addData("looked_for", result.get("looked_for")).hint(result.get("hint").getAsString());
            if (chart && result.has("limits")) {
              var limits = new JsonObject(); result.getAsJsonObject("limits").entrySet().forEach(e -> limits.add(resultKey() + "." + e.getKey(), e.getValue()));
              response.addData("limits", limits);
            }
            if (result.has("problem_count") && result.get("problem_count").getAsLong() > 0)
              response.addSkipped("undecodable_records", result.get("problem_count").getAsString() + " selected records failed; inspect member-qualified problems");
            var inputs = result.has("inputs") ? result.getAsJsonObject("inputs").deepCopy() : new JsonObject();
            inputs.add("source_ref", arguments.get("source_ref")); if (arguments.has("entry")) inputs.add("entry", arguments.get("entry")); response.addData("inputs", inputs);
            for (var key : List.of("data_quality", "server_analysis_directives")) if (result.has(key)) response.addData(key, result.get(key));
          }
          response.addData("api_version", arguments.get("api_version")).addData(resultKey(), result);
          if (name().equals("visualization_source_entries_list")) {
            var limits = new JsonObject(); var page = new JsonObject();
            page.add("total", result.get("total_matching")); page.add("returned", result.get("returned")); page.add("limit", result.get("limit"));
            limits.add("page.entries", page); response.addData("limits", limits);
          }
          if (name().equals("visualization_source_inspect") || name().equals("visualization_view_bind")) {
            var inputs = new JsonObject(); inputs.add("source_ref", result.get("source_ref")); response.addData("inputs", inputs);
          }
          if ((result.has("items") || result.has("templates")) && result.has("total_matching") && result.has("returned") && result.has("limit")) {
            var limits = new JsonObject(); var page = new JsonObject(); page.add("total", result.get("total_matching"));
            page.add("returned", result.get("returned")); page.add("limit", result.get("limit"));
            if (result.has("templates") && result.has("total_unavailable")) {
              page.addProperty("total", result.get("total_matching").getAsLong() - result.get("total_unavailable").getAsLong());
              page.addProperty("returned", result.getAsJsonArray("templates").size());
              var missing = new JsonObject(); missing.add("total", result.get("total_unavailable"));
              missing.addProperty("returned", result.getAsJsonArray("unreadable").size()); missing.add("limit", result.get("limit"));
              limits.add(resultKey() + ".unreadable", missing);
              if (!result.getAsJsonArray("unreadable").isEmpty()) response.addSkipped("unreadable_definitions", "Records retained for local inspection; their names cannot be filtered");
            }
            limits.add(resultKey() + "." + (result.has("templates") ? "templates" : "items"), page); response.addData("limits", limits);
          }
          return response.build();
        }
      } catch (Exception failure) {
        Throwable cause = failure;
        while (cause instanceof java.util.concurrent.ExecutionException && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof org.triplehelix.wpilogmcp.read.server.SourceScopes.Failure selection) {
          var response = selection.result(); response.addProperty("api_version", version); return response;
        }
        if (cause instanceof InventoryFailure inventory) {
          var response = ResponseBuilder.error(inventory.getMessage()).addProperty("api_version", version).addProperty("code", inventory.code)
              .addProperty("retryable", inventory.retryable);
          if (inventory.retryable) response.addProperty("retry_after_ms", 1000);
          return response.build();
        }
        if (cause instanceof org.triplehelix.wpilogmcp.read.ReadException read) {
          var refusal = VisualizationReadFailure.of(read);
          var response = ResponseBuilder.error(refusal.message()).addProperty("api_version", version).addProperty("code", refusal.code())
              .addProperty("retryable", refusal.retryable());
          if (refusal.retryAfterMs() > 0) response.addProperty("retry_after_ms", refusal.retryAfterMs());
          return response.build();
        }
        String message = Objects.toString(cause.getMessage(), cause.getClass().getSimpleName());
        if (LibraryRequests.tool(name(), arguments) && message.length() > 1024) message = message.substring(0, 1024) + " (truncated)";
        String code = message.matches("^[A-Z_]+:.*") ? message.substring(0, message.indexOf(':')) : cause instanceof java.nio.file.AccessDeniedException || cause instanceof org.triplehelix.wpilogmcp.log.LogFileException ? "ACCESS_DENIED" : "INVALID_REQUEST";
        if (code.equals("SOURCE_SELECTION_REQUIRED")) {
          var response = new org.triplehelix.wpilogmcp.read.server.SourceScopes.Failure(code, message, logManager.sourceScopes().snapshot()).result();
          response.addProperty("api_version", version); return response;
        }
        var response = ResponseBuilder.error(message).addProperty("api_version", version).addProperty("code", code);
        if (code.startsWith("LIBRARY_")) {
          boolean retryable = code.equals("LIBRARY_BUSY") || code.equals("LIBRARY_CHECKPOINT_PENDING");
          response.addProperty("retryable", retryable);
          if (retryable) response.addProperty("retry_after_ms", 1000);
        }
        return response.build();
      }
    }
    /** Each adapter explicitly projects its declared inputs before the shared operation. */
    static JsonObject project(JsonObject arguments, String... keys) {
      var result = new JsonObject(); for (var key : keys) if (arguments.has(key)) result.add(key, arguments.get(key).deepCopy()); return result;
    }
    static JsonObject stringItem() { var item = new JsonObject(); item.addProperty("type", "string"); return item; }
    abstract String resultKey();
    abstract JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception;
    ToolRegistry.SchemaBuilder schema() { return new ToolRegistry.SchemaBuilder().addIntegerProperty("api_version", "Visualization API version: 1 for legacy references, 2 for qualified source references; API 1 is local-only; API 2 supports direct remote visualization", true, null); }
  }
  static final class CapabilitiesGet extends Operation {
    CapabilitiesGet(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_capabilities_get"; }
    @Override public String description() { return "Supported configuration schema, type and role vocabulary, persistent identity, budgets and preview limits. Capabilities include decoded_buffer_budget defaults/range and attached_windows with client_id, current_session, decoded_buffer_budget_bytes and basis client_reported; these facts are not server allocations or access grants. client_id is an opaque window ID for this server process, the same ID a working view reports for its attachment; it is not a session ID and grants nothing. current_session marks the caller's own window. Result envelope: api_version, status, capabilities. API 1 requires local authority; API 2 admits remote visualization. Local administration stays local-only."; }
    @Override String resultKey() { return "capabilities"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var input = project(arguments, "api_version"); var session = SessionContext.current();
      return workspace.capabilities(session == null ? null : session.getId());
    }
  }
  static final class SourceInspect extends Operation {
    SourceInspect(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_source_inspect"; }
    @Override public String description() { return "Inspect a recorded file or captured recording by an admitted path or durable source_ref. Returns identity, entry types, schemas, sample counts and clock bounds. A recording retains individual member inventories and conflicting declarations; its clock distinguishes the shared robot estimate from the last sample. Optional inventory_mode paged returns a session-owned fixed summary without entries or schemas; use the entry list/details operations and close it when finished. Expiry requires reopening from source_ref. Inspection does not enable live visualization panels. Result envelope: api_version, status, source. API 1 requires local authority; API 2 admits remote visualization. Local administration stays local-only."; }
    @Override String resultKey() { return "source"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .addProperty("inventory_mode", "string", "full (default) or paged for bounded recording discovery", false)
          .addProperty("path", "string", "WPILOG path on the selected server; alternative to source_ref", false)
          .addProperty("scope_id", "string", "Selected serving scope for API 2 identification or legacy qualification", false)
          .addProperty("source_ref", "object", "Durable recorded, unmanaged or anchored session source identity; never contains a local path", false)
          .build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var input = project(arguments, "api_version", "path", "source_ref", "scope_id", "inventory_mode");
      return workspace.inspect(caller, input);
    }
  }
  static final class SourceHealth extends Operation {
    SourceHealth(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_source_health"; }
    @Override public String description() { return "Observe acquisition for an anchored recording or the current generation and replica basis of an API 2 file version, without reading values or opening a data prefix. Quiet change-only signals do not imply disconnection. Acquisition and history are separate: finalized history can resume under the same robot clock; another recording is never substituted. Returns source_ref, acquisition_state, history_state, reason, connected, clock_synchronized, last_value_age_ms and publication_timing (null when unknown or inactive). Publication timing carries writer_identity, publication_revision and receive_to_publication_ms in the server monotonic clock domain. Health is not data admission or an end-to-end latency measurement. Result envelope: api_version, status, health. Requires admitted visualization authority and current source grants."; }
    @Override String resultKey() { return "health"; }
    @Override public JsonObject inputSchema() { return schema().addProperty("source_ref", "object", "Durable anchored recording or API 2 qualified file-version identity", true).build(); }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      return workspace.health(caller, project(arguments, "api_version", "source_ref"));
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
  static final class SourceValue extends Operation {
    SourceValue(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_source_value"; }
    @Override String resultKey() { return "value"; }
    @Override public String description() { return "Read the latest recorded value or the last sample at or before at_sec from a file or anchored recording. Never interpolates. Recording ties use member_id string order then physical ordinal; value includes exact tagged value, timestamp_us, member_id, ordinal, prefix proof and decode problems. A recording call admits a fresh bounded prefix, independent of displayed follow buffers. No sample is no_match with a hint; decode failures are partial. Result envelope: api_version, status, value, inputs. API 1 requires local authority; API 2 admits remote visualization. Local administration stays local-only."; }
    @Override public JsonObject inputSchema() {
      return schema().addProperty("source_ref", "object", "Durable file or anchored recording identity", true)
          .addProperty("entry", "string", "Exact declared entry name; no role or name guessing", true)
          .addProperty("field", "string", "Optional scalar field path or array index; recording fields require a numeric witness and refuse wildcards", false)
          .addNumberProperty("at_sec", "Inclusive robot-clock cursor in seconds; omit for latest by timestamp", false, null).build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var session = SessionContext.current();
      return workspace.value(project(arguments, "api_version", "source_ref", "entry", "field", "at_sec"), () -> session == null || session.active());
    }
  }
  static final class SourceStatistics extends Operation {
    SourceStatistics(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_source_statistics"; }
    @Override String resultKey() { return "statistics"; }
    @Override public String description() { return "Compute exact full-window statistics for a numeric entry or scalar field in a file or anchored recording. Uses the shared statistics, angle, sample standard deviation, percentile and data-quality rules; plot buckets never feed analysis. Duplicate and backward timestamps remain samples. An empty numeric window returns no_match with count 0, looked_for and a hint, retaining its inputs and prefix evidence. Recording members must agree on declarations and schemas; limits refuse rather than approximate. Statistics include count, min, max, mean, median, std_dev, q1, q3, iqr, p5, p95, data_quality and analysis directives, plus recording prefix and decode problems when applicable. Result envelope: api_version, status, statistics, inputs. Sample count and non-finite filtering bound confidence; one recording cannot establish a general cause. API 1 requires local authority; API 2 admits remote visualization. Local administration stays local-only."; }
    @Override public JsonObject inputSchema() {
      return schema().addProperty("source_ref", "object", "Durable file or anchored recording identity", true)
          .addProperty("entry", "string", "Exact declared numeric entry name", true)
          .addProperty("field", "string", "Optional scalar field path or array index; recording reads refuse wildcards", false)
          .addNumberProperty("start_time", "Inclusive robot-clock start in seconds", false, null)
          .addNumberProperty("end_time", "Inclusive robot-clock end in seconds", false, null)
          .addNumberProperty("last_seconds", "Positive finite recent duration, anchored at the admitted robot clock or last record; cannot combine with start_time/end_time. inputs.window reports absolute bounds", false, null).build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var session = SessionContext.current();
      return workspace.statistics(project(arguments, "api_version", "source_ref", "entry", "field", "start_time", "end_time", "last_seconds"), () -> session == null || session.active());
    }
  }
  static final class SourceReduction extends Operation {
    SourceReduction(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_source_reduction"; }
    @Override String resultKey() { return "reduction"; }
    @Override public String description() { return "Reduce one exact numeric or boolean entry or scalar field from a proven file or anchored recording prefix for drawing. Uses shared native time buckets over all selected original records, ordered by timestamp, member id and physical ordinal. Declared scalar struct angles unwrap in that merged order before reduction, starting anew for each request; plain numbers remain arithmetic. Buckets report start_us/end_us, count, finite_count, min/max/mean and first/last; empty buckets are omitted. Nonfinite and unsafe integers are gaps, with unplottable_count; exact values remain available through value and raw reads. Result includes buckets, sample_count, finite_count, max_points, window, reduction_rule, basis, prefix, records_visited and bounded member-qualified problems. No interpolation or deduplication. Reductions are not statistical inputs or evidence of causation. Work and byte limits refuse; empty windows are no_match with a hint. Result envelope: api_version, status, reduction, inputs. Local authority required."; }
    @Override public JsonObject inputSchema() {
      return schema().addProperty("source_ref", "object", "Durable file or anchored recording identity", true)
          .addProperty("entry", "string", "Exact numeric or boolean entry name", true)
          .addProperty("field", "string", "Optional scalar numeric field or array index, without wildcard", false)
          .addNumberProperty("start_time", "Inclusive robot-clock start in seconds; defaults to source start", false, null)
          .addNumberProperty("end_time", "Inclusive robot-clock end in seconds; defaults to source end", false, null)
          .addNumberProperty("last_seconds", "Positive finite recent duration on the admitted clock; cannot combine with start_time/end_time. window reports absolute bounds", false, null)
          .addIntegerProperty("max_points", "Maximum time buckets, 1..4096, default 1024", false, null)
          .addIntegerProperty("max_bytes", "Result byte allowance, 4096..1048576, default 1048576; lower max_points if the result cannot fit", false, null).build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var session = SessionContext.current();
      return workspace.reduction(project(arguments, "api_version", "source_ref", "entry", "field", "start_time", "end_time", "last_seconds", "max_points", "max_bytes"), () -> session == null || session.active());
    }
  }
  static final class SourceHistogram extends Operation {
    SourceHistogram(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_source_histogram"; }
    @Override String resultKey() { return "histogram"; }
    @Override public String description() { return "Count original finite numeric samples in a file or anchored recording using render_chart's histogram rule: equal-width ceil(sqrt(n)) bins capped at 64, [low,high), maximum included; constant data one unit-wide bin. Never bins plot buckets. Histogram includes bins with low/high/count, histogram_rule, summary, inputs, data_quality, prefix, records_visited and decode problems. Active recordings require a 1–300 second interval; admission and work limits refuse rather than approximate. Empty intervals are no_match with a hint. Sample weighting is not elapsed-time weighting, and one recording does not establish a cause. Result envelope: api_version, status, histogram, inputs, data_quality and analysis directives. API 1 requires local authority; API 2 admits remote visualization. Local administration stays local-only."; }
    @Override public JsonObject inputSchema() {
      return schema().addProperty("source_ref", "object", "Durable file or anchored recording identity", true)
          .addProperty("entry", "string", "Exact numeric entry; booleans use 0/1", true)
          .addProperty("field", "string", "Optional scalar numeric field or array index; no wildcard", false)
          .addNumberProperty("start_time", "Inclusive robot-clock start in seconds", false, null)
          .addNumberProperty("end_time", "Inclusive robot-clock end in seconds", false, null)
          .addNumberProperty("last_seconds", "Recent duration; mutually exclusive with start_time/end_time; 1–300 seconds while active", false, null).build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var session = SessionContext.current();
      return workspace.histogram(project(arguments, "api_version", "source_ref", "entry", "field", "start_time", "end_time", "last_seconds"), () -> session == null || session.active());
    }
  }
  static final class SourceScatter extends Operation {
    SourceScatter(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_source_scatter"; }
    @Override String resultKey() { return "scatter"; }
    @Override public String description() { return "Pair original finite numeric samples at exact timestamps from one admitted file or anchored recording prefix. Equal times pair one-to-one in stable member-id and physical order, with no interpolation or automatic clock alignment. Scatter contains points as [x, y, robot-clock seconds], matched, unpaired_x, unpaired_y, summary, alignment_rule, offset, next_offset, complete, limits with true total, inputs, data_quality, prefix and decode problems. limit pages the drawing only; totals and summaries describe the full interval. Each page read is fresh; inspect prefix before combining pages across appends. Active recordings require a 1–300 second interval. Empty pairs or pages are no_match with a hint. Work limits refuse rather than approximate; correlation does not imply causation. Result envelope: api_version, status, scatter, inputs, limits, data_quality and analysis directives. API 1 requires local authority; API 2 admits remote visualization. Local administration stays local-only."; }
    @Override public JsonObject inputSchema() {
      return schema().addProperty("source_ref", "object", "Durable file or anchored recording identity", true)
          .addProperty("x_entry", "string", "Exact horizontal numeric entry; booleans use 0/1", true)
          .addProperty("y_entry", "string", "Exact vertical numeric entry; may equal x_entry", true)
          .addProperty("x_field", "string", "Optional horizontal scalar numeric field or array index", false)
          .addProperty("y_field", "string", "Optional vertical scalar numeric field or array index", false)
          .addNumberProperty("start_time", "Inclusive robot-clock start in seconds", false, null)
          .addNumberProperty("end_time", "Inclusive robot-clock end in seconds", false, null)
          .addNumberProperty("last_seconds", "Recent duration; mutually exclusive with start_time/end_time; 1–300 seconds while active", false, null)
          .addIntegerProperty("offset", "Matched pairs skipped; nonnegative, default 0", false, null)
          .addIntegerProperty("limit", "Returned pairs, 1–4096, default 256; full-interval totals remain exact", false, null).build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var session = SessionContext.current();
      return workspace.scatter(project(arguments, "api_version", "source_ref", "x_entry", "y_entry", "x_field", "y_field", "start_time", "end_time", "last_seconds", "offset", "limit"), () -> session == null || session.active());
    }
  }
  static final class LibraryList extends Operation {
    LibraryList(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_library_list"; }
    @Override public String description() { return "List server-library view or template summaries with API 2. Returns items, total_matching, returned, limit and next; unreadable records remain visible and retained. Follow next as after; limits report the true matching total. No log store is selected or mutated. Result envelope: api_version, status, catalog. Requires trusted local authority."; }
    @Override String resultKey() { return "catalog"; }
    @Override public JsonObject inputSchema() { return schema().addProperty("kind", "string", "view or template", true)
        .addProperty("query", "string", "Case-insensitive title substring", false).addProperty("after", "string", "Previous page next key", false)
        .addProperty("limit", "integer", "Page size from 1 through 256", false).build(); }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception { return workspace.libraryList(caller, project(arguments, "api_version", "kind", "query", "after", "limit")); }
  }
  static final class LibraryMigrate extends Operation {
    LibraryMigrate(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_library_migrate"; }
    @Override public String description() { return "Copy one pinned legacy store definition into this server library with API 2. kind is view or template; legacy_ref names the store, object and revision. Canonical content and aliases make retries idempotent; conflicting store identities remain distinct and originals are retained. Returns saved_view_ref and saved_view, or template_ref and template, in definition. Result envelope: api_version, status, definition. Requires trusted local authority."; }
    @Override String resultKey() { return "definition"; }
    @Override public JsonObject inputSchema() { return schema().addProperty("kind", "string", "view or template", true).addProperty("legacy_ref", "object", "Pinned legacy store reference", true).build(); }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception { return workspace.migrateLibrary(caller, project(arguments, "api_version", "kind", "legacy_ref")); }
  }
  static final class SavedViewOpen extends Operation {
    SavedViewOpen(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_saved_view_open"; }
    @Override public String description() { return "Open a pinned API 2 saved_view_ref in a new working view, rechecking source ownership and current grants. request_id retries recover the same working identity. A missing source refuses without choosing a replacement. Result envelope: api_version, status, view. Requires trusted local authority."; }
    @Override String resultKey() { return "view"; }
    @Override public JsonObject inputSchema() { return schema().addProperty("request_id", "string", "Unique retry UUID", true).addProperty("saved_view_ref", "object", "Server/library identity, saved_view_id and revision", true).build(); }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      if (!new JsonPrimitive(2).equals(arguments.get("api_version"))) throw new IllegalArgumentException("Library operations require API version 2");
      return workspace.openSaved(caller, project(arguments, "api_version", "request_id", "saved_view_ref"));
    }
  }
  static final class TemplateList extends Operation {
    TemplateList(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_template_list"; }
    @Override public String description() { return "Search bundled and server-library templates with API 2 paging, or legacy configured-store templates with API 1. Returns templates, unreadable records and listing_incomplete. Result envelope: api_version, status, catalog. API 1 requires local authority; API 2 admits remote visualization. Local administration stays local-only."; }
    @Override String resultKey() { return "catalog"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .addProperty("query", "string", "Case-insensitive name/description substring", false)
          .addProperty("after", "string", "API 2 continuation key from the previous page", false)
          .addProperty("limit", "integer", "API 2 page size, 1 to 256", false)
          .build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      var input = project(arguments, "api_version", "query", "after", "limit");
      return workspace.templates(input);
    }
  }
  static final class TemplateGet extends Operation {
    TemplateGet(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_template_get"; }
    @Override public String description() { return "Read an exact pinned template_ref; a changed revision refuses rather than silently substituting. Result envelope: api_version, status, definition. API 1 requires local authority; API 2 admits remote visualization. Local administration stays local-only."; }
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
    @Override public String description() { return "Check exact entries or explicit shared-resolver roles against a recorded file or anchored recording. Recording checks inspect every admitted member, report absent members and refuse conflicting names, declarations or schemas. Returns compatibility and per-requirement bindings, reasons, candidates, unit basis and warnings; missing exact names never fall back. Recording evidence is bounded and can refuse with RESOURCE_LIMIT; this does not create a live view. Result envelope: api_version, status, compatibility. API 1 requires local authority; API 2 admits remote visualization. Local administration stays local-only."; }
    @Override String resultKey() { return "compatibility"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .addProperty("source_ref", "object", "Durable recorded, unmanaged or anchored recording source identity; never contains a local path", true)
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
    @Override public String description() { return "Create a server-library template with API 2, or a legacy store template with API 1, from configuration_json or a pinned working view. Returns template_ref and portable template. No source bytes or paths are stored in templates; API 2 requires durable server state and no store_id. Result envelope: api_version, status, definition. API 1 requires local authority; API 2 admits remote visualization. Local administration stays local-only."; }
    @Override String resultKey() { return "definition"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .addProperty("request_id", "string", "Unique UUID; reuse only for an identical retry", true)
          .addProperty("store_id", "string", "API 1 writable store UUID; omit for API 2 server library", false)
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
    @Override public String description() { return "Explicitly update a library (API 2) or store (API 1) template at expected_revision. Returns template_ref and template; existing views keep pinned configurations. Bundled definitions are immutable. Result envelope: api_version, status, definition. API 1 requires local authority; API 2 admits remote visualization. Local administration stays local-only."; }
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
    @Override public String description() { return "Validate a portable envelope_json and RFC 8785 SHA-256 before an atomic import. Default mode copy; explicit update requires expected_revision and a newer import. Durable portable receipts deduplicate retries. Same identity/revision with differing content conflicts. Result envelope: api_version, status, definition. API 1 requires local authority; API 2 admits remote visualization. Local administration stays local-only."; }
    @Override String resultKey() { return "definition"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .addProperty("request_id", "string", "Unique UUID; reuse only for an identical retry", true)
          .addProperty("store_id", "string", "API 1 writable store UUID; omit for API 2 server library", false)
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
    @Override public String description() { return "Return envelope_json with format, schema_version, template and content_sha256. Contains declarative configuration only, no source locators, sample buffers or credentials. Result envelope: api_version, status, export. API 1 requires local authority; API 2 admits remote visualization. Local administration stays local-only."; }
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
    @Override public String description() { return "List unexpired working views, optionally for source_ref. Returns views with revision, configuration, source_ref, checkpoint, saved/template references, attachment and expiry. attached_client_id is the attached window's opaque ID, as in capabilities, never a session ID. Result envelope: api_version, status, workspace. API 1 requires local authority; API 2 admits remote visualization. Local administration stays local-only."; }
    @Override String resultKey() { return "workspace"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .addProperty("source_ref", "object", "Durable recorded, unmanaged or anchored recording identity; never contains a local path", false)
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
    @Override public String description() { return "Read one working view: revision, configuration, source_ref, checkpoint, saved/template references, attachment, expiry, has_unsaved_changes and native_dirty. attached_client_id is the attached window's opaque ID, as in capabilities, never a session ID. Does not display it. Result envelope: api_version, status, view. API 1 requires local authority; API 2 admits remote visualization. Local administration stays local-only."; }
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
  static final class ViewBind extends Operation {
    ViewBind(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_view_bind"; }
    @Override public String description() { return "Resolve an anchored recording view at expected_revision on a fresh admitted prefix. Returns binding with view_id, revision, source_ref, compatibility including unresolved requirements, clock, time_policy, queries, follow_ready, reason, frontier_scope and max_page_bytes. No follow or display is started; this detached evidence is not a permission grant. Result envelope: api_version, status, binding, inputs; zero selections are no_match with looked_for and hint. API 1 requires local authority; API 2 admits remote visualization. Local administration stays local-only."; }
    @Override String resultKey() { return "binding"; }
    @Override public JsonObject inputSchema() {
      return schema().addProperty("view_id", "string", "Working recording view UUID", true)
          .addProperty("expected_revision", "integer", "Pinned working revision; concurrent edits refuse with REVISION_CONFLICT", true).build();
    }
    @Override JsonObject run(VisualizationWorkspace workspace, CallerContext.Authority caller, JsonObject arguments) throws Exception {
      return workspace.bindView(caller, project(arguments, "api_version", "view_id", "expected_revision"));
    }
  }
  static final class ViewCreate extends Operation {
    ViewCreate(ToolRegistry registry) { super(registry); }
    @Override public String name() { return "visualization_view_create"; }
    @Override public String description() { return "Create an independent working view from source_ref and configuration_json or template_ref, or an empty layout. Returns its stable view_id and revision, configuration and persistence state. Recording references admit metadata only; unresolved requirements stay editable. Creation does not request display or start acquisition. Result envelope: api_version, status, view. API 1 requires local authority; API 2 admits remote visualization. Local administration stays local-only."; }
    @Override String resultKey() { return "view"; }
    @Override public JsonObject inputSchema() {
      return schema()
          .addProperty("request_id", "string", "Unique UUID; reuse only for an identical retry", true)
          .addProperty("source_ref", "object", "Durable recorded, unmanaged or anchored recording identity; never contains a local path", true)
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
    @Override public String description() { return "CAS edit a working view with strict configuration_json at expected_revision. Returns the updated view and notifies its attached Explorer. Conflicts retain both edits; never retry automatically against a newer revision. Result envelope: api_version, status, view. API 1 requires local authority; API 2 admits remote visualization. Local administration stays local-only."; }
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
    @Override public String description() { return "Create another working UUID from the pinned expected_revision. Returns an independent view with the same source and configuration; selection and later edits are not shared. Recording sources are admitted again before cloning. Result envelope: api_version, status, view. API 1 requires local authority; API 2 admits remote visualization. Local administration stays local-only."; }
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
    @Override public String description() { return "Save source-bound configuration at expected_revision into the API 2 server library (or API 1 store) and advance its checkpoint. First save requires title; API 1 also requires store_id; API 2 uses the server library; existing saves retain their destination. A concurrent edit remains uncheckpointed. Returns the view. Result envelope: api_version, status, view. API 1 requires local authority; API 2 admits remote visualization. Local administration stays local-only."; }
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
    @Override public String description() { return "CAS restore the checkpoint or initial draft layout at expected_revision. Returns the view with an advanced revision; saved store definitions are unchanged. Result envelope: api_version, status, view. API 1 requires local authority; API 2 admits remote visualization. Local administration stays local-only."; }
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
    @Override public String description() { return "Delete an unattached working view at expected_revision, releasing its live quotas. Retry tombstones expire after six hours, with separate caps of 256 records and 8 MiB; pressure evicts the oldest. Expired or evicted delete retries return VIEW_UNAVAILABLE. Saved definitions and source files remain intact; close an attached editor first. Returns the deleted view receipt. Result envelope: api_version, status, view. API 1 requires local authority; API 2 admits remote visualization. Local administration stays local-only."; }
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
