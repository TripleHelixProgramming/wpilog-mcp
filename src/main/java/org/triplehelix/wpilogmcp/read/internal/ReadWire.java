/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.internal;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.google.gson.LongSerializationPolicy;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.log.struct.EnumValue;
import org.triplehelix.wpilogmcp.log.struct.UnsignedEnumValue;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import java.math.BigInteger;
import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.ReadProblem;
import org.triplehelix.wpilogmcp.read.ReadQuery;
import org.triplehelix.wpilogmcp.read.ReadSnapshot;
import org.triplehelix.wpilogmcp.read.Sample;
import org.triplehelix.wpilogmcp.read.SampleBatch;

/** Versioned, lossless read wire values; transport adapters do not infer JavaScript numbers. */
public final class ReadWire {
  public static final int VERSION = 1;
  /** Bound one decoded network frame independently of the log's record size. Arrow avoids JSON
   * expansion for a single large array; neither transport silently truncates a record. */
  public static final int MAX_FRAME_BYTES = 16 << 20;
  public static final int MAX_REV_SAMPLES = 4096;
  public static final int MAX_DEPTH = 64;
  public static final Gson JSON = new GsonBuilder().serializeNulls()
      .setLongSerializationPolicy(LongSerializationPolicy.STRING)
      .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
      .registerTypeHierarchyAdapter(Path.class, (JsonSerializer<Path>) (p, t, c) -> new JsonPrimitive(p.toString()))
      .create();
  /** An overload is retryable even when a proxy or an older server returns plain text. */
  public static ReadException responseFailure(int status, byte[] body) {
    if (status == 503 || status == 429)
      return new ReadException(ReadProblem.Code.BUSY, "Remote reader is busy (HTTP " + status + "); retry later", true);
    try { return failure(parse(body)); }
    catch (IOException | RuntimeException invalid) { return new ReadException(ReadProblem.Code.MALFORMED_RECORD,
        "Remote reader returned HTTP " + status + " without a valid error document", invalid); }
  }
  private ReadWire() {}
  public static JsonElement value(Object value) { return value(value, MAX_FRAME_BYTES); }
  public static JsonElement value(Object value, int maxWireBytes) {
    estimatedValueBytes(value, maxWireBytes); return encode(value, 0);
  }
  /** The existing conservative expansion walk, without building JSON. Fixed-prefix analysis
   * uses it to bound retained decoded values before materializing a statistical history. */
  public static long estimatedValueBytes(Object value, long limit) {
    var budget = new Budget(limit); preflight(value, budget, 0); return limit - budget.left;
  }
  public static Object value(JsonElement value) { return decode(value, 0); }

  /** Conservative wire expansion is charged before creating a single tagged element. This is
   * deliberately separate from encoded-byte enforcement: a legal large primitive array must not
   * first become millions of Gson objects just to discover that its JSON is too large. */
  private static final class Budget {
    long left;
    Budget(long bytes) { if (bytes < 1) throw new IllegalArgumentException("A wire budget must be positive"); left = bytes; }
    void charge(long bytes) { if (bytes < 0 || bytes > left) throw new WireLimit("Read JSON expansion exceeds its byte budget; use Arrow or smaller batches"); left -= bytes; }
    void text(String text) { charge(text == null ? 4 : 6L * text.length() + 64); }
  }
  public static final class WireLimit extends IllegalArgumentException { WireLimit(String message) { super(message); } }
  private static void preflight(Object value, Budget budget, int depth) {
    depth(depth); budget.charge(64);
    if (value == null || value instanceof Number || value instanceof Boolean) return;
    if (value instanceof String s) { budget.text(s); return; }
    if (value instanceof byte[] bytes) { budget.charge(4L * ((bytes.length + 2L) / 3)); return; }
    if (value instanceof EnumValue e) { budget.text(e.label()); return; }
    if (value instanceof UnsignedEnumValue e) { budget.text(e.label()); return; }
    if (value instanceof Map<?, ?> map) {
      for (var entry : map.entrySet()) { budget.text(String.valueOf(entry.getKey())); preflight(entry.getValue(), budget, depth + 1); }
    } else if (value instanceof List<?> list) {
      for (var entry : list) preflight(entry, budget, depth + 1);
    } else if (value instanceof String[] array) {
      for (var entry : array) preflight(entry, budget, depth + 1);
    } else if (value.getClass().isArray()) budget.charge(64L * java.lang.reflect.Array.getLength(value));
    else throw new IllegalArgumentException("Unsupported recorded value type: " + value.getClass());
  }

  private static JsonElement encode(Object value, int depth) {
    depth(depth);
    if (value == null) return JsonNull.INSTANCE;
    var out = new JsonObject();
    if (value instanceof Boolean b) { out.addProperty("type", "bool"); out.addProperty("value", b); }
    else if (value instanceof String s) { out.addProperty("type", "string"); out.addProperty("value", s); }
    else if (value instanceof Float f) { out.addProperty("type", "f32"); out.addProperty("value", Integer.toUnsignedString(Float.floatToRawIntBits(f), 16)); }
    else if (value instanceof Double d) { out.addProperty("type", "f64"); out.addProperty("value", Long.toUnsignedString(Double.doubleToRawLongBits(d), 16)); }
    else if (value instanceof BigInteger n) { out.addProperty("type", "u64"); out.addProperty("value", unsigned(n).toString()); }
    else if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
      out.addProperty("type", "i64"); out.addProperty("value", Long.toString(((Number) value).longValue()));
    }
    else if (value instanceof byte[] bytes) { out.addProperty("type", "bytes"); out.addProperty("value", Base64.getEncoder().encodeToString(bytes)); }
    else if (value instanceof UnsignedEnumValue e) {
      out.addProperty("type", "enum_u64"); out.addProperty("value", unsigned(e.value()).toString()); out.addProperty("label", e.label());
    }
    else if (value instanceof EnumValue e) {
      out.addProperty("type", "enum"); out.addProperty("value", Long.toString(e.value())); out.addProperty("label", e.label());
    } else if (value instanceof Map<?, ?> map) {
      out.addProperty("type", "struct"); var fields = new JsonObject();
      map.forEach((k, v) -> { if (!(k instanceof String)) throw new IllegalArgumentException("A struct field must be named"); fields.add((String) k, encode(v, depth + 1)); });
      out.add("value", fields);
    } else if (value instanceof List<?> list || value.getClass().isArray()) {
      String type = value instanceof List<?> ? "list" : value instanceof long[] ? "i64[]"
          : value instanceof double[] ? "f64[]" : value instanceof float[] ? "f32[]"
          : value instanceof boolean[] ? "bool[]" : value instanceof String[] ? "string[]" : null;
      if (type == null) throw new IllegalArgumentException("Unsupported array type: " + value.getClass());
      out.addProperty("type", type); var elements = new JsonArray();
      if (value instanceof List<?> list) for (var element : list) elements.add(encode(element, depth + 1));
      else for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) elements.add(encode(java.lang.reflect.Array.get(value, i), depth + 1));
      out.add("value", elements);
    } else throw new IllegalArgumentException("Unsupported recorded value type: " + value.getClass());
    return out;
  }

  private static Object decode(JsonElement value, int depth) {
    depth(depth);
    if (value.isJsonNull()) return null;
    var object = value.getAsJsonObject(); String type = text(object, "type");
    var data = object.get("value");
    return switch (type) {
      case "bool" -> { if (!data.isJsonPrimitive() || !data.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Expected boolean"); yield data.getAsBoolean(); }
      case "string" -> text(object, "value");
      case "i64" -> Long.parseLong(text(object, "value"));
      case "u64" -> unsigned(new BigInteger(text(object, "value")));
      case "enum_u64" -> new UnsignedEnumValue(unsigned(new BigInteger(text(object, "value"))), nullableText(object, "label"));
      case "f32" -> Float.intBitsToFloat(Integer.parseUnsignedInt(text(object, "value"), 16));
      case "f64" -> Double.longBitsToDouble(Long.parseUnsignedLong(text(object, "value"), 16));
      case "bytes" -> Base64.getDecoder().decode(text(object, "value"));
      case "enum" -> new EnumValue(Long.parseLong(text(object, "value")), nullableText(object, "label"));
      case "struct" -> {
        var fields = new LinkedHashMap<String, Object>();
        data.getAsJsonObject().entrySet().forEach(e -> fields.put(e.getKey(), decode(e.getValue(), depth + 1)));
        yield fields;
      }
      case "list", "i64[]", "f64[]", "f32[]", "bool[]", "string[]" -> {
        var array = data.getAsJsonArray(); var elements = new ArrayList<Object>(array.size());
        array.forEach(v -> elements.add(decode(v, depth + 1)));
        yield switch (type) {
          case "i64[]" -> { long[] a = new long[elements.size()]; for (int i = 0; i < a.length; i++) a[i] = (Long) elements.get(i); yield a; }
          case "f64[]" -> { double[] a = new double[elements.size()]; for (int i = 0; i < a.length; i++) a[i] = (Double) elements.get(i); yield a; }
          case "f32[]" -> { float[] a = new float[elements.size()]; for (int i = 0; i < a.length; i++) a[i] = (Float) elements.get(i); yield a; }
          case "bool[]" -> { boolean[] a = new boolean[elements.size()]; for (int i = 0; i < a.length; i++) a[i] = (Boolean) elements.get(i); yield a; }
          case "string[]" -> elements.toArray(String[]::new);
          default -> elements;
        };
      }
      default -> throw new IllegalArgumentException("Unknown recorded value tag: " + type);
    };
  }

  private static BigInteger unsigned(BigInteger value) {
    if (value.signum() < 0 || value.bitLength() > 64) throw new IllegalArgumentException("Recorded uint64 must be between 0 and 18446744073709551615");
    return value;
  }

  private static void depth(int depth) {
    if (depth > MAX_DEPTH) throw new IllegalArgumentException("Recorded value nesting exceeds " + MAX_DEPTH);
  }
  public static String text(JsonObject object, String name) {
    var value = object.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
      throw new IllegalArgumentException("Expected string field '" + name + "'");
    return value.getAsString();
  }
  public static String nullableText(JsonObject object, String name) {
    return !object.has(name) || object.get(name).isJsonNull() ? null : text(object, name);
  }

  /** A descriptor is the complete schema and boundary; it does not trigger value decoding. */
  public static JsonObject descriptor(String id, ReadSnapshot snapshot) {
    return descriptor(id, snapshot, snapshot.entries(), snapshot.schemas().allStructs(), false);
  }

  /** An explicitly negotiated follow may project metadata before allocation/encoding. Keep the
   * native schema owner's transitive dependencies and diagnostics, without sending unrelated
   * topics or bundled schemas. The recording's internal identity/history proof stays complete. */
  public static JsonObject descriptorForEntries(String id, ReadSnapshot snapshot, Set<String> selected) {
    return descriptorForEntries(id, snapshot, selected, MAX_FRAME_BYTES);
  }
  public static JsonObject descriptorForEntries(String id, ReadSnapshot snapshot, Set<String> selected, int maxBytes) {
    var entries = new LinkedHashMap<String, EntryInfo>(); var roots = new TreeSet<String>();
    for (String name : new TreeSet<>(selected)) {
      var entry = snapshot.entries().get(name); if (entry == null) continue;
      entries.put(name, entry);
      String schema = StructSchemas.structName(entry.type());
      if (schema == null) schema = StructSchemas.schemaEntryStruct(name, entry.type());
      if (schema != null) roots.add(schema);
    }
    return descriptor(id, snapshot, entries, roots, true, maxBytes);
  }

  private static JsonObject descriptor(String id, ReadSnapshot snapshot, Map<String, EntryInfo> entries,
      Collection<String> roots, boolean dependencies) {
    return descriptor(id, snapshot, entries, roots, dependencies, MAX_FRAME_BYTES);
  }
  private static JsonObject descriptor(String id, ReadSnapshot snapshot, Map<String, EntryInfo> entries,
      Collection<String> roots, boolean dependencies, int maxBytes) {
    var budget = new Budget(maxBytes); budget.charge(512); budget.text(snapshot.path().toString());
    if (snapshot.replica() != null) budget.text(JSON.toJson(snapshot.replica()));
    for (var entry : entries.values()) {
      budget.charge(256); budget.text(entry.name()); budget.text(entry.type()); budget.text(entry.metadata());
    }
    var schemas = new ArrayList<StructSchemas.StructInfo>();
    var pending = new ArrayDeque<>(roots); var visited = new HashSet<String>();
    while (!pending.isEmpty()) {
      String name = pending.removeFirst(); if (!visited.add(name)) continue;
      var schema = snapshot.schemas().info(name).orElse(null); if (schema == null) continue;
      budget.charge(256); budget.text(schema.name()); budget.text(schema.schema()); budget.text(schema.schemaEntry()); budget.text(schema.error());
      for (var field : schema.fields()) {
        budget.charge(256); budget.text(field.name()); budget.text(field.type()); budget.text(field.structType());
        for (var constant : field.enumValues().keySet()) { budget.charge(64); budget.text(constant); }
        if (dependencies && field.structType() != null) pending.addLast(field.structType());
      }
      schemas.add(schema);
    }
    var out = new JsonObject(); out.addProperty("api_version", VERSION); out.addProperty("id", id);
    out.addProperty("path", snapshot.path().toString()); out.addProperty("live", snapshot.live());
    out.add("boundary", boundary(snapshot.boundary()));
    out.add("entries", JSON.toJsonTree(entries));
    var counts = new JsonObject(); entries.keySet().forEach(e -> counts.addProperty(e, Long.toString(snapshot.sampleCount(e))));
    out.add("sample_counts", counts); out.addProperty("index_bytes", Long.toString(snapshot.indexBytes()));
    out.addProperty("truncated", snapshot.truncated()); out.addProperty("damaged", snapshot.damaged());
    out.addProperty("truncation_message", snapshot.truncationMessage());
    out.add("schemas", JSON.toJsonTree(schemas));
    if (!snapshot.sources().isEmpty()) out.add("sources", JSON.toJsonTree(snapshot.sources()));
    if (snapshot.replica() != null) out.add("replica", JSON.toJsonTree(snapshot.replica()));
    if (!snapshot.excludedSources().isEmpty()) out.add("excluded_sources", JSON.toJsonTree(snapshot.excludedSources()));
    return out;
  }
  public static JsonElement boundary(FileSnapshot file) {
    if (file == null) return JsonNull.INSTANCE;
    var out = new JsonObject(); out.addProperty("size_bytes", Long.toString(file.size()));
    out.addProperty("modified", file.modified().toString()); out.addProperty("created", file.created() == null ? null : file.created().toString());
    out.addProperty("identity", file.fileKey() == null ? null : file.fileKey().toString()); return out;
  }
  public static FileSnapshot boundary(JsonElement element) {
    if (element.isJsonNull()) return null; var object = element.getAsJsonObject();
    var created = nullableText(object, "created");
    return new FileSnapshot(Long.parseLong(text(object, "size_bytes")), FileTime.from(java.time.Instant.parse(text(object, "modified"))),
        nullableText(object, "identity"), created == null ? null : FileTime.from(java.time.Instant.parse(created)));
  }
  public static ReadQuery query(JsonObject request) {
    var windows = new ArrayList<ReadQuery.Window>();
    if (request.has("windows") && !request.get("windows").isJsonArray()) throw new IllegalArgumentException("windows must be an array");
    if (request.has("windows")) for (var value : request.getAsJsonArray("windows")) {
      if (!value.isJsonObject()) throw new IllegalArgumentException("Each window must be an object");
      var w = value.getAsJsonObject();
      windows.add(new ReadQuery.Window(Long.parseLong(text(w, "start_us")), Long.parseLong(text(w, "end_us")),
          w.has("end_inclusive") && bool(w, "end_inclusive")));
    }
    return new ReadQuery(text(request, "entry"), windows,
        ReadQuery.Order.valueOf(request.has("order") ? text(request, "order").toUpperCase(java.util.Locale.ROOT) : "TIME"),
        request.has("batch_records") ? integer(request, "batch_records") : 4096,
        request.has("batch_bytes") ? integer(request, "batch_bytes") : 4 << 20,
        request.has("field") ? text(request, "field") : null,
        request.has("reduction") ? new ReadQuery.Reduction(integer(request.getAsJsonObject("reduction"), "max_points")) : null,
        request.has("first_ordinal") ? integer(request, "first_ordinal") : 0,
        request.has("record_limit") ? new ReadQuery.RecordLimit(integer(request.getAsJsonObject("record_limit"), "max_bytes"),
            ReadQuery.LimitAction.valueOf(text(request.getAsJsonObject("record_limit"), "on_limit").toUpperCase(java.util.Locale.ROOT))) : null);
  }
  public static int integer(JsonObject object, String name) {
    var value = object.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException(name + " must be an integer number");
    try { return value.getAsBigDecimal().intValueExact(); }
    catch (ArithmeticException failure) { throw new IllegalArgumentException(name + " must be an exact 32-bit integer", failure); }
  }
  private static boolean bool(JsonObject object, String name) {
    var value = object.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
      throw new IllegalArgumentException(name + " must be a boolean");
    return value.getAsBoolean();
  }
  public static JsonObject query(ReadQuery query) {
    var out = new JsonObject(); out.addProperty("entry", query.entry()); out.addProperty("order", query.order().name().toLowerCase(java.util.Locale.ROOT));
    out.addProperty("batch_records", query.batchRecords()); out.addProperty("batch_bytes", query.batchBytes());
    var windows = new JsonArray();
    for (var window : query.windows()) {
      var w = new JsonObject(); w.addProperty("start_us", Long.toString(window.startUs()));
      w.addProperty("end_us", Long.toString(window.endUs())); w.addProperty("end_inclusive", window.endInclusive()); windows.add(w);
    }
    out.add("windows", windows);
    if (query.firstOrdinal() != 0) out.addProperty("first_ordinal", query.firstOrdinal());
    if (query.field() != null) out.addProperty("field", query.field());
    if (query.reduction() != null) { var reduction = new JsonObject(); reduction.addProperty("max_points", query.reduction().maxPoints()); out.add("reduction", reduction); }
    if (query.recordLimit() != null) {
      var limit = new JsonObject(); limit.addProperty("max_bytes", query.recordLimit().maxBytes());
      limit.addProperty("on_limit", query.recordLimit().onLimit().name().toLowerCase(java.util.Locale.ROOT)); out.add("record_limit", limit);
    }
    return out;
  }
  public static JsonObject batch(SampleBatch batch) {
    return batch(batch, MAX_FRAME_BYTES);
  }
  public static JsonObject batch(SampleBatch batch, int maxWireBytes) {
    var budget = new Budget(maxWireBytes); budget.charge(256);
    for (var sample : batch.samples()) { budget.charge(192); preflight(sample.value(), budget, 0); if (sample.source() != null) { budget.charge(64); budget.text(sample.source().path()); } }
    for (var problem : batch.problems()) { budget.charge(192); budget.text(problem.entry()); budget.text(problem.message()); }
    var out = new JsonObject(); out.addProperty("kind", "batch"); var samples = new JsonArray();
    for (var sample : batch.samples()) {
      var s = new JsonObject(); s.addProperty("timestamp_us", Long.toString(sample.timestampUs()));
      s.addProperty("record_offset", Long.toString(sample.recordOffset())); s.addProperty("ordinal", sample.ordinal());
      s.addProperty("payload_bytes", Long.toString(sample.payloadBytes()));
      s.add("value", encode(sample.value(), 0));
      if (sample.source() != null) {
        var source = new JsonObject(); source.addProperty("path", sample.source().path());
        source.addProperty("timestamp_us", Long.toString(sample.source().timestampUs())); s.add("source", source);
      }
      samples.add(s);
    }
    out.add("samples", samples); out.add("problems", JSON.toJsonTree(batch.problems()));
    out.addProperty("records_visited", batch.recordsVisited()); out.addProperty("payload_bytes", Long.toString(batch.payloadBytes()));
    out.addProperty("complete", batch.complete()); return out;
  }
  /** Wire frames may be shorter than a source batch. Encoded sizes come from the record
   * headers, so splitting changes neither values nor total byte/work accounting. Malformed and
   * filtered records, with their original accounting, stay in the final (possibly empty) frame. */
  public static java.util.Iterator<SampleBatch> wireBatches(SampleBatch source) {
    var parts = new ArrayList<SampleBatch>();
    var budget = new Budget(MAX_FRAME_BYTES); budget.charge(256);
    int start = 0, visited = source.recordsVisited(); long remaining = source.payloadBytes(), bytes = 0;
    for (int i = 0; i < source.samples().size(); i++) {
      var sample = source.samples().get(i);
      var single = new Budget(MAX_FRAME_BYTES); single.charge(256 + 192); preflight(sample.value(), single, 0);
      if (sample.source() != null) { single.charge(64); single.text(sample.source().path()); }
      long weight = MAX_FRAME_BYTES - single.left - 256;
      if (weight > budget.left) {
        parts.add(new SampleBatch(source.samples().subList(start, i), List.of(), 0, 0, false));
         start = i; bytes = 0;
        budget = new Budget(MAX_FRAME_BYTES); budget.charge(256);
      }
      budget.charge(weight);
      if (sample.payloadBytes() < 0) throw new IllegalArgumentException("Wire rebatching requires recorded payload sizes");
      bytes += sample.payloadBytes();
    }
    var tail = new SampleBatch(source.samples().subList(start, source.samples().size()), source.problems(), visited, remaining, source.complete());
    // If diagnostics do not fit beside the last values, keep their accounting in a final frame.
    long diagnostics = 0;
    for (var problem : source.problems()) {
      var charge = new Budget(MAX_FRAME_BYTES); charge.charge(192); charge.text(problem.entry()); charge.text(problem.message());
      diagnostics += MAX_FRAME_BYTES - charge.left;
    }
    if (diagnostics > budget.left && start < source.samples().size()) {
      int count = source.samples().size() - start;
      parts.add(new SampleBatch(tail.samples(), List.of(), 0, 0, false));
      tail = new SampleBatch(List.of(), source.problems(), visited, remaining, source.complete());
    }
    parts.add(tail);
    return parts.iterator();
  }

  public static SampleBatch batch(JsonObject object) {
    if (!text(object, "kind").equals("batch")) throw new IllegalArgumentException("Expected a read batch");
    var samples = new ArrayList<Sample>();
    for (var item : object.getAsJsonArray("samples")) {
      var s = item.getAsJsonObject(); samples.add(new Sample(Long.parseLong(text(s, "timestamp_us")),
          Long.parseLong(text(s, "record_offset")), integer(s, "ordinal"), value(s.get("value")),
          s.has("payload_bytes") ? Long.parseLong(text(s, "payload_bytes")) : -1,
          s.has("source") ? new Sample.Source(text(s.getAsJsonObject("source"), "path"), Long.parseLong(text(s.getAsJsonObject("source"), "timestamp_us"))) : null));
    }
    var problems = List.of(JSON.fromJson(object.get("problems"), ReadProblem[].class));
    int visited = integer(object, "records_visited"); long payload = Long.parseLong(text(object, "payload_bytes"));
    if (visited < 0 || samples.size() > 4096 || problems.size() > 4096 || visited > 4096 || payload < 0
        || samples.stream().anyMatch(s -> s.ordinal() < 0 || s.recordOffset() < 0))
      throw new IllegalArgumentException("Invalid read batch counts or record coordinates");
    return new SampleBatch(samples, problems, visited, payload, bool(object, "complete"));
  }
  public static JsonObject end(ReadException failure) {
    var out = new JsonObject(); out.addProperty("kind", "end"); out.addProperty("status", failure == null ? "ok" : "error");
    if (failure != null) { out.addProperty("code", failure.code().name()); out.addProperty("message", failure.getMessage()); }
    return out;
  }
  public static void requireEnd(JsonObject end) throws IOException {
    if (!"end".equals(nullableText(end, "kind"))) throw new IOException("Read stream ended without its completion record");
    if (!"ok".equals(nullableText(end, "status"))) throw failure(end);
  }
  /**
   * A refusal keeps the server's retryable flag: clients decide by the flag, never by the message, and
   * dropping it made every remote refusal look permanent. An older server's missing flag means false,
   * and a flag that is not a boolean is an invalid response, as the extension reads it.
   */
  public static ReadException failure(JsonObject object) {
    ReadProblem.Code code;
    try { code = ReadProblem.Code.valueOf(text(object, "code")); }
    catch (RuntimeException ignored) { code = ReadProblem.Code.MALFORMED_RECORD; }
    var flag = object.get("retryable");
    if (flag != null && !(flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isBoolean()))
      return new ReadException(ReadProblem.Code.MALFORMED_RECORD, "Remote reader returned a retryable flag that is not a boolean");
    return new ReadException(code, nullableText(object, "message") == null ? "Remote read refused" : text(object, "message"),
        flag != null && flag.getAsBoolean());
  }

  /** The entire frame is bounded before publication: a failure cannot masquerade as a short batch. */
  public static byte[] bytes(Object object, int limit) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var bounded = new OutputStream() {
      @Override public void write(int b) throws IOException { if (bytes.size() == limit) throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT, "Read frame exceeds " + limit + " bytes; use Arrow or smaller batches"); bytes.write(b); }
      @Override public void write(byte[] b, int off, int len) throws IOException {
        if (len > limit - bytes.size()) throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT, "Read frame exceeds " + limit + " bytes; use Arrow or smaller batches");
        bytes.write(b, off, len);
      }
    };
    try (var writer = new OutputStreamWriter(bounded, StandardCharsets.UTF_8)) { JSON.toJson(object, writer); }
    catch (com.google.gson.JsonIOException failure) {
      if (failure.getCause() instanceof IOException io) throw io;
      throw failure;
    }
    return bytes.toByteArray();
  }
  public static JsonObject parse(byte[] bytes) throws IOException {
    String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    try (var reader = new JsonReader(new StringReader(text))) {
      reader.setStrictness(Strictness.STRICT);
      var value = parseElement(reader, 0, new int[] {0});
      if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Read JSON must contain one object");
      return value.getAsJsonObject();
    } catch (RuntimeException failure) { throw new IOException("Invalid read JSON", failure); }
  }
  private static JsonElement parseElement(JsonReader reader, int depth, int[] nodes) throws IOException {
    if (depth > 2 * MAX_DEPTH + 16 || ++nodes[0] > 1_000_000) throw new IOException("Read JSON nesting or node count exceeds its bound");
    return switch (reader.peek()) {
      case BEGIN_OBJECT -> {
        var object = new JsonObject(); reader.beginObject();
        while (reader.hasNext()) { String name = reader.nextName(); if (object.has(name)) throw new IOException("Duplicate read JSON field: " + name); object.add(name, parseElement(reader, depth + 1, nodes)); }
        reader.endObject(); yield object;
      }
      case BEGIN_ARRAY -> {
        var array = new JsonArray(); reader.beginArray(); while (reader.hasNext()) array.add(parseElement(reader, depth + 1, nodes)); reader.endArray(); yield array;
      }
      case STRING -> new JsonPrimitive(reader.nextString());
      case NUMBER -> new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
      case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
      case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
      default -> throw new IOException("Invalid read JSON token");
    };
  }
  public static JsonObject line(InputStream input) throws IOException {
    var bytes = new ByteArrayOutputStream();
    for (int next; (next = input.read()) != -1;) {
      if (next == '\n') return parse(bytes.toByteArray());
      if (bytes.size() == MAX_FRAME_BYTES) throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT, "Remote read frame exceeds " + MAX_FRAME_BYTES + " bytes");
      bytes.write(next);
    }
    throw new IOException("Read stream ended without its completion record");
  }
}
