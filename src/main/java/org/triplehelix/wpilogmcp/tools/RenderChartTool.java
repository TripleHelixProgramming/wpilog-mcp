/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.triplehelix.wpilogmcp.log.*;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.SchemaBuilder;
import static org.triplehelix.wpilogmcp.tools.ToolUtils.*;

/** A picture of stated data, with its recipe and full-window measurements beside it.
 * The renderer has no resolver or analysis of its own: the PNG and the editor share this spec. */
public final class RenderChartTool extends LogRequiringTool {
  static final int MAX_PIXELS = 4_000_000;
  static final int MAX_SAMPLES = 10_000;
  @FunctionalInterface interface Renderer { byte[] draw(JsonObject spec) throws java.io.IOException; }
  private final Renderer renderer;
  public RenderChartTool() { this(spec -> ChartImage.draw(spec)); }
  RenderChartTool(Renderer renderer) { this.renderer = renderer; }
  @Override public String name() { return "render_chart"; }
  @Override public String description() {
    return "Draw a PNG image of named entries or numeric field paths: time_series, histogram, scatter, or field (Pose2d/Pose3d). "
        + "Returns chart_spec (version, kind, width, height, series, points or bins, phases, window, open_url; field geometry and its source for field), "
        + "summary per series (name, count, min, max, mean, window, non_finite_count, data_quality), inputs, data_quality and server_analysis_directives. "
        + "The image is MCP image content beside the JSON; a runtime without imaging returns skipped with the reason. "
        + "Change-only signals use steps; phases use logged Driver Station state, never a guessed name; units are only those the name states. "
        + "Histogram bins are equal width, ceil(sqrt(count)) capped at 64; constant data has one unit-wide bin. "
        + "Scatter uses exact timestamp pairs only (no interpolation or extrapolation); unpaired records are counted in chart_spec. "
        + "Summaries cover all finite samples of each series in the requested window, as get_statistics does; they are sample-weighted, not time-weighted. "
        + "limit/offset page the drawing, never its summary; max_points buckets a time series with extremes retained, and limits report omitted points. "
        + "Histograms count the whole window. Field summaries are for x and y separately, with their schema's units; Pose3d is projected onto the floor. "
        + "An empty window returns no_match with looked_for and hint. A chart is a view, not causal evidence; one log is one sample."
        + NumericSignal.PATH_HELP + StatisticsTools.SCOPE_HELP;
  }
  @Override protected JsonObject toolSchema() {
    return new SchemaBuilder()
        .addProperty("name", "string", "One entry or numeric field path; use this or entries", false)
        .addArrayProperty("entries", "Several entry names or field paths, in drawing order (maximum 16); mutually exclusive with name", stringSchema(), false)
        .addProperty("kind", "string", "time_series (default), histogram, scatter (exactly two numeric series), or field (pose entries)", false)
        .addNumberProperty("start_time", "Start timestamp in seconds", false, null)
        .addNumberProperty("end_time", "End timestamp in seconds", false, null)
        .addProperty("scope", "string", TimeScope.SCOPE_DESCRIPTION, false)
        .addArrayProperty("windows", TimeScope.WINDOWS_DESCRIPTION, TimeScope.windowItemSchema(), false)
        .addIntegerProperty("limit", "Drawing samples per series (default 1000, max 10000); summaries and histograms use the full window", false, 1000)
        .addIntegerProperty("offset", "Drawing samples to skip per series", false, 0)
        .addIntegerProperty("max_points", "Equal-time buckets for time_series, retaining min/max/first/last (max 10000)", false, null)
        .addIntegerProperty("width", "PNG width (default 960, 160..4096; width*height at most 4000000)", false, 960)
        .addIntegerProperty("height", "PNG height (default 540, 120..4096; width*height at most 4000000)", false, 540)
        .build();
  }
  @Override protected JsonElement executeWithLog(LogData log, JsonObject args) throws Exception {
    int width = integer(args, "width", 960, 160, 4096), height = integer(args, "height", 540, 120, 4096);
    if ((long) width * height > MAX_PIXELS) throw new IllegalArgumentException("width * height must not exceed " + MAX_PIXELS + " pixels");
    int limit = integer(args, "limit", 1000, 1, MAX_SAMPLES), offset = integer(args, "offset", 0, 0, Integer.MAX_VALUE);
    Integer maxPoints = args.has("max_points") ? integer(args, "max_points", 1000, 1, MAX_SAMPLES) : null;
    String kind = getOptString(args, "kind", "time_series");
    if (!Set.of("time_series", "histogram", "scatter", "field").contains(kind)) throw new IllegalArgumentException("Unknown chart kind: " + kind);
    if (maxPoints != null && !kind.equals("time_series")) throw new IllegalArgumentException("max_points applies only to time_series");
    var names = names(args); if (kind.equals("scatter") && names.size() != 2) throw new IllegalArgumentException("scatter needs exactly two entries");
    var timeline = MatchTimeline.of(log); var scope = TimeScope.fromArguments(log, timeline, args);
    var spec = new JsonObject(); spec.addProperty("version", 1); spec.addProperty("kind", kind);
    spec.addProperty("width", width); spec.addProperty("height", height); spec.add("window", scope.toJson());
    spec.add("phases", phases(timeline, scope));
    spec.addProperty("phase_basis", timeline.hasEnabledData() ? "logged Driver Station state through the signal resolver" : "no conventional Driver Station state; no phases drawn");
    spec.addProperty("open_url", openUrl(log.path().toString(), names, scope, kind));
    var series = new JsonArray(); var summaries = new JsonArray(); var measured = new ArrayList<List<TimestampedValue>>();
    var builder = ResponseBuilder.success().addInputScope(scope);
    DataQuality worst = null;
    var labels = new ArrayList<String>();
    for (var name : names) {
      if (kind.equals("field")) {
        PoseTools.requirePose(log, name, "entries"); labels.add(name + ".translation.x"); labels.add(name + ".translation.y");
      } else labels.add(name);
    }
    for (var label : labels) {
      var signal = NumericSignal.resolve(log, label, null).requireSingleValued("render_chart");
      var parts = StatisticsTools.finiteWindows(signal, scope, signal.isAngle());
      var values = StatisticsTools.flatten(parts); measured.add(values);
      var quality = DataQuality.fromSegments(log, signal.entry(), scope.split(signal.values()));
      if (worst == null || quality.qualityScore() < worst.qualityScore()) worst = quality;
      var summary = summary(signal.label(), values, scope, quality); summaries.add(summary);
      var item = signal.describe(); item.addProperty("name", signal.label());
      item.addProperty("unit", EntryData.unitFromName(signal.label()));
      item.addProperty("style", quality.sampling() == DataQuality.Sampling.CHANGE_ONLY ? "step_after" : "line");
      if (kind.equals("histogram")) item.add("bins", histogram(values));
      else {
        var page = values.stream().skip(offset).limit(limit).toList();
        var points = new JsonArray();
        for (var tv : page) { var point = new JsonArray(); point.add(tv.timestamp()); point.add(toDouble(tv.value())); points.add(point); }
        ResultContract.addLimitedList(item, "points", points, Math.max(0, values.size() - offset), limit);
        // Each scope window is a separate path. Drawing never bridges an excluded disabled interval.
        item.add("windows", new Gson().toJsonTree(scope.windows()));
        if (maxPoints != null && values.size() > maxPoints) {
          var buckets = new JsonArray();
          for (var part : parts) if (!part.isEmpty()) buckets.addAll(new Gson().toJsonTree(Buckets.of(part, null, null, maxPoints).buckets()).getAsJsonArray());
          item.remove("points"); item.remove("limits");
          var bucketPage = new JsonArray();
          buckets.asList().stream().skip(offset).limit(limit).forEach(bucketPage::add);
          ResultContract.addLimitedList(item, "buckets", bucketPage, Math.max(0, buckets.size() - offset), limit);
          item.addProperty("bucket_rule", "equal duration within each scope window; extrema and first/last retained");
        }
      }
      series.add(item); builder.addInputSignal("series" + series.size(), signal);
    }
    if (measured.stream().allMatch(List::isEmpty)) return ResponseBuilder.noMatch("No finite samples in the requested window")
        .addData("looked_for", new Gson().toJsonTree(names)).addProperty("hint", "Widen the time window or name an entry with samples").addInputScope(scope).build();
    spec.add("series", series);
    if (kind.equals("histogram")) spec.addProperty("histogram_rule", "equal-width ceil(sqrt(n)) bins, capped at 64; [low,high), last includes max; constant data one unit-wide bin");
    if (kind.equals("scatter") || kind.equals("field")) {
      var paths = new JsonArray();
      for (int i = 0; i < measured.size(); i += 2) {
        var pair = pairs(measured.get(i), measured.get(i + 1), offset, limit);
        pair.addProperty("x", labels.get(i)); pair.addProperty("y", labels.get(i + 1)); paths.add(pair);
      }
      if (paths.asList().stream().allMatch(p -> p.getAsJsonObject().getAsJsonArray("points").isEmpty())) {
        return ResponseBuilder.noMatch("No exact timestamp pairs on the requested drawing page")
            .addData("looked_for", new Gson().toJsonTree(names)).addProperty("hint", "Use overlapping records or reduce offset; alignment is exact").addInputScope(scope).build();
      }
      spec.add("pairs", paths); spec.addProperty("alignment_rule", "exact timestamp, one-to-one in record order; unpaired samples omitted; no interpolation or extrapolation");
    }
    if (kind.equals("field")) {
      var game = timeline.game();
      if (game.isEmpty()) return ResponseBuilder.notApplicable("No bundled field geometry for season " + timeline.season().year()).build();
      spec.add("field_geometry", game.get().raw().getAsJsonObject("field_geometry"));
      spec.addProperty("field_source", "bundled " + timeline.season().year() + " game data; season basis: " + timeline.season().basis());
    }
    builder.addData("chart_spec", spec).addData("summary", summaries).addDataQuality(worst)
        .addDirectives(AnalysisDirectives.fromQuality(worst).addSingleMatchCaveat());
    try { builder.addImage(renderer.draw(spec)); }
    catch (LinkageError e) { unavailable(builder, e); }
    catch (RuntimeException e) {
      if (!e.getClass().getName().equals("java.awt.HeadlessException")) throw e;
      unavailable(builder, e);
    } catch (Error e) {
      if (!e.getClass().getName().equals("java.awt.AWTError")) throw e;
      unavailable(builder, e);
    } catch (java.io.IOException e) { builder.addSkipped("image", "PNG writer is unavailable: " + e.getMessage()); }
    return builder.build();
  }
  private static void unavailable(ResponseBuilder builder, Throwable error) {
    builder.addSkipped("image", "Headless imaging is unavailable: " + error.getClass().getSimpleName());
  }
  private static JsonObject stringSchema() { var schema = new JsonObject(); schema.addProperty("type", "string"); return schema; }
  static int integer(JsonObject args, String key, int fallback, int min, int max) {
    if (!args.has(key) || args.get(key).isJsonNull()) return fallback;
    double number = args.get(key).getAsDouble();
    if (!Double.isFinite(number) || number != Math.rint(number) || number < min || number > max) throw new IllegalArgumentException(key + " must be an integer in " + min + ".." + max);
    return (int) number;
  }
  private static List<String> names(JsonObject args) {
    if (args.has("name") && args.has("entries")) throw new IllegalArgumentException("Pass name or entries, not both");
    var result = new ArrayList<String>();
    if (args.has("name")) result.add(getRequiredString(args, "name"));
    else if (args.has("entries") && args.get("entries").isJsonArray()) for (var n : args.getAsJsonArray("entries")) {
      if (!n.isJsonPrimitive() || !n.getAsJsonPrimitive().isString() || n.getAsString().isBlank()) throw new IllegalArgumentException("entries must contain nonempty names"); result.add(n.getAsString());
    }
    if (result.isEmpty() || result.size() > 16) throw new IllegalArgumentException("Supply name or entries with 1..16 series");
    return List.copyOf(new LinkedHashSet<>(result));
  }
  private static JsonObject summary(String name, List<TimestampedValue> values, TimeScope scope, DataQuality quality) {
    var stats = values.stream().mapToDouble(tv -> toDouble(tv.value())).summaryStatistics();
    var json = new JsonObject(); json.addProperty("name", name); json.addProperty("count", stats.getCount());
    json.addProperty("min", values.isEmpty() ? null : stats.getMin()); json.addProperty("max", values.isEmpty() ? null : stats.getMax());
    json.addProperty("mean", values.isEmpty() ? null : stats.getAverage()); json.add("window", scope.toJson());
    json.addProperty("non_finite_count", quality.nanFiltered()); json.add("data_quality", quality.toJson()); return json;
  }
  static JsonArray histogram(List<TimestampedValue> values) {
    var out = new JsonArray(); if (values.isEmpty()) return out;
    var stats = values.stream().mapToDouble(tv -> toDouble(tv.value())).summaryStatistics();
    int n = stats.getMin() == stats.getMax() ? 1 : Math.min(64, (int) Math.ceil(Math.sqrt(values.size())));
    double low = n == 1 ? stats.getMin() - 0.5 : stats.getMin(), high = n == 1 ? stats.getMax() + 0.5 : stats.getMax();
    double step = (high - low) / n; var counts = new int[n];
    for (var tv : values) counts[Math.min(n - 1, Math.max(0, (int) ((toDouble(tv.value()) - low) / step)))]++;
    for (int i = 0; i < n; i++) { var bin = new JsonObject(); bin.addProperty("low", low + i * step); bin.addProperty("high", low + (i + 1) * step); bin.addProperty("count", counts[i]); out.add(bin); }
    return out;
  }
  private static JsonObject pairs(List<TimestampedValue> x, List<TimestampedValue> y, int offset, int limit) {
    var points = new JsonArray(); int a = 0, b = 0, count = 0;
    while (a < x.size() && b < y.size()) {
      var first = x.get(a); var second = y.get(b);
      if (first.timestamp() < second.timestamp()) a++;
      else if (first.timestamp() > second.timestamp()) b++;
      else { if (count >= offset && count - offset < limit) { var point = new JsonArray(); point.add(toDouble(first.value())); point.add(toDouble(second.value())); point.add(first.timestamp()); points.add(point); } count++; a++; b++; }
    }
    var out = new JsonObject(); ResultContract.addLimitedList(out, "points", points, Math.max(0, count - offset), limit);
    out.addProperty("matched", count); out.addProperty("unpaired_x", x.size() - count); out.addProperty("unpaired_y", y.size() - count); return out;
  }
  private static JsonArray phases(MatchTimeline timeline, TimeScope scope) {
    var result = new JsonArray();
    if (!timeline.hasEnabledData()) return result;
    for (var s : timeline.segments()) for (var w : scope.windows()) if (s.end() > w.start() && s.start() < w.end()) {
      var phase = s.toJson(); phase.addProperty("start", Math.max(s.start(), w.start())); phase.addProperty("end", Math.min(s.end(), w.end())); phase.addProperty("duration", phase.get("end").getAsDouble() - phase.get("start").getAsDouble()); result.add(phase);
    }
    return result;
  }
  private static String openUrl(String path, List<String> names, TimeScope scope, String kind) {
    var url = new StringBuilder("vscode://TripleHelixProgramming.wpilog-analyzer/open?path=").append(encode(path));
    names.forEach(name -> url.append("&entries=").append(encode(name)));
    if (!scope.windows().isEmpty()) url.append("&start=").append(scope.windows().get(0).start()).append("&end=").append(scope.windows().get(scope.windows().size() - 1).end());
    return url.append("&kind=").append(kind).toString();
  }
  private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
