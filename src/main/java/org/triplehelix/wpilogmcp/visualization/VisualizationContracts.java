/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One schema owns portable definitions. Adapters carry JSON without inventing another validator.
 * Binding and numerical interpretation stay with the existing source/analysis owners. */
public final class VisualizationContracts {
  public static final int MAX_CONFIGURATION_BYTES = 1024 * 1024;
  private static final JsonObject SCHEMA = resource("contracts-v1.json");
  private VisualizationContracts() {}

  public static JsonObject schema() { return SCHEMA.deepCopy(); }
  public static JsonObject blank() { return resource("blank-v1.json"); }
  private static JsonObject resource(String name) {
    try (var in = VisualizationContracts.class.getResourceAsStream("/visualization/" + name)) {
      if (in == null) throw new IllegalStateException("Missing visualization contract " + name);
      return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
    } catch (IOException e) { throw new UncheckedIOException(e); }
  }

  /** Parse before Gson can discard duplicate keys. Depth and input bytes are bounded first. */
  public static JsonObject parse(String json) {
    if (json.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIGURATION_BYTES) fail("configuration exceeds 1 MiB");
    try (var reader = new JsonReader(new StringReader(json))) {
      reader.setLenient(false);
      var value = read(reader, 0);
      if (reader.peek() != JsonToken.END_DOCUMENT || !value.isJsonObject()) fail("expected one object");
      return value.getAsJsonObject();
    } catch (IOException e) { throw new IllegalArgumentException("INVALID_CONFIGURATION: " + e.getMessage(), e); }
  }

  private static JsonElement read(JsonReader r, int depth) throws IOException {
    if (depth > 32) fail("maximum nesting depth is 32");
    return switch (r.peek()) {
      case BEGIN_OBJECT -> {
        var o = new JsonObject(); r.beginObject();
        while (r.hasNext()) {
          var key = r.nextName(); unicode(key);
          if (o.has(key)) fail("duplicate key " + key);
          o.add(key, read(r, depth + 1));
        }
        r.endObject(); yield o;
      }
      case BEGIN_ARRAY -> {
        var a = new JsonArray(); r.beginArray();
        while (r.hasNext()) a.add(read(r, depth + 1));
        r.endArray(); yield a;
      }
      case STRING -> { var s = r.nextString(); unicode(s); yield new JsonPrimitive(s); }
      case NUMBER -> {
        var s = r.nextString(); double n = Double.parseDouble(s);
        if (!Double.isFinite(n)) fail("non-finite number");
        yield new JsonPrimitive(new java.math.BigDecimal(s));
      }
      case BOOLEAN -> new JsonPrimitive(r.nextBoolean());
      case NULL -> { r.nextNull(); yield JsonNull.INSTANCE; }
      default -> throw new IllegalArgumentException("INVALID_CONFIGURATION: unexpected JSON token");
    };
  }

  private static void unicode(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (++i >= s.length() || !Character.isLowSurrogate(s.charAt(i))) fail("lone surrogate");
      } else if (Character.isLowSurrogate(c)) fail("lone surrogate");
    }
  }

  public static JsonObject source(JsonObject input) {
    var source = parse(input.toString());
    check(source, SCHEMA.getAsJsonObject("$defs").getAsJsonObject("source_ref"), "source_ref");
    if (source.get("kind").getAsString().equals("session")) {
      var anchor = source.getAsJsonObject("anchor_member");
      try { java.time.Instant.parse(anchor.get("recording_started_at").getAsString()); }
      catch (java.time.format.DateTimeParseException invalid) { fail("anchor recording_started_at must be an ISO instant"); }
      var name = anchor.get("original_name").getAsString();
      if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) fail("anchor must be a filename");
    }
    return source;
  }

  public static JsonObject configuration(JsonObject input) {
    var config = parse(input.toString());
    check(config, SCHEMA.getAsJsonObject("$defs").getAsJsonObject("configuration"), "configuration");
    var requirements = ids(config.getAsJsonArray("requirements"), "id");
    var panels = ids(config.getAsJsonArray("panels"), "panel_id");
    var layout = config.getAsJsonObject("layout").getAsJsonArray("items");
    if (!panels.equals(ids(layout, "panel_id"))) fail("layout must contain every panel exactly once");
    for (var e : layout) {
      var o = e.getAsJsonObject();
      if (o.get("x").getAsInt() + o.get("w").getAsInt() > 12) fail("layout exceeds twelve columns");
    }
    for (var e : config.getAsJsonArray("panels")) {
      var panel = e.getAsJsonObject(); var axes = ids(panel.getAsJsonArray("axes"), "axis_id");
      for (var item : panel.getAsJsonArray("series")) {
        var series = item.getAsJsonObject();
        if (!requirements.contains(series.get("requirement_id").getAsString())
            || !axes.contains(series.get("axis_id").getAsString())) fail("series refers to an unknown requirement or axis");
      }
      for (var axis : panel.getAsJsonArray("axes")) {
        var range = axis.getAsJsonObject().get("range");
        if (range.isJsonObject() && range.getAsJsonObject().get("min").getAsDouble()
            >= range.getAsJsonObject().get("max").getAsDouble()) fail("axis range must increase");
      }
    }
    for (var time : config.getAsJsonObject("time").entrySet()) {
      var value = time.getValue().getAsJsonObject();
      if (value.get("mode").getAsString().equals("fixed")
          && value.get("start_sec").getAsDouble() > value.get("end_sec").getAsDouble()) fail("time range must increase");
    }
    return config;
  }

  private static Set<String> ids(JsonArray values, String key) {
    var seen = new HashSet<String>();
    for (var e : values) if (!seen.add(e.getAsJsonObject().get(key).getAsString())) fail("duplicate " + key);
    return seen;
  }

  /** Deliberately implements only the keywords in our checked-in schema, not general JSON Schema. */
  private static void check(JsonElement value, JsonObject rule, String at) {
    if (rule.has("oneOf")) {
      int matches = 0;
      for (var branch : rule.getAsJsonArray("oneOf")) {
        try { check(value, branch.getAsJsonObject(), at); matches++; }
        catch (IllegalArgumentException ignored) { /* another explicitly allowed shape */ }
      }
      if (matches != 1) fail(at + " must match exactly one declared shape");
      return;
    }
    if (rule.has("enum") && !rule.getAsJsonArray("enum").contains(value)) fail(at + " is not an allowed value");
    if (!rule.has("type")) return;
    String type = rule.get("type").getAsString();
    boolean valid = switch (type) {
      case "object" -> value.isJsonObject();
      case "array" -> value.isJsonArray();
      case "null" -> value.isJsonNull();
      case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
      case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
      case "number", "integer" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
          && Double.isFinite(value.getAsDouble()) && (!type.equals("integer") || value.getAsDouble() == Math.rint(value.getAsDouble()));
      default -> throw new IllegalStateException("Unsupported contract type " + type);
    };
    if (!valid) fail(at + " must be " + type);
    if (type.equals("object")) {
      var object = value.getAsJsonObject(); var properties = rule.getAsJsonObject("properties");
      for (var key : rule.getAsJsonArray("required")) if (!object.has(key.getAsString())) fail(at + " lacks " + key.getAsString());
      for (var entry : object.entrySet()) {
        if (!properties.has(entry.getKey())) fail(at + " has unknown field " + entry.getKey());
        check(entry.getValue(), properties.getAsJsonObject(entry.getKey()), at + "." + entry.getKey());
      }
    } else if (type.equals("array")) {
      var values = value.getAsJsonArray(); bounds(values.size(), rule, "minItems", "maxItems", at);
      for (var item : values) check(item, rule.getAsJsonObject("items"), at + "[]");
    } else if (type.equals("string")) {
      var s = value.getAsString(); bounds(s.codePointCount(0, s.length()), rule, "minLength", "maxLength", at);
      if (rule.has("pattern") && !s.matches(rule.get("pattern").getAsString())) fail(at + " has invalid syntax");
    } else if (type.equals("number") || type.equals("integer")) bounds(value.getAsDouble(), rule, "minimum", "maximum", at);
  }
  private static void bounds(double n, JsonObject rule, String min, String max, String at) {
    if (rule.has(min) && n < rule.get(min).getAsDouble() || rule.has(max) && n > rule.get(max).getAsDouble()) fail(at + " exceeds its declared bounds");
  }
  private static void fail(String message) { throw new IllegalArgumentException("INVALID_CONFIGURATION: " + message); }
}
