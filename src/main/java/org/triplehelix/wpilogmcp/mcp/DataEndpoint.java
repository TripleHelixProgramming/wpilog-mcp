/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.Version;
import org.triplehelix.wpilogmcp.data.ArrowStreamWriter;
import org.triplehelix.wpilogmcp.data.ArrowType;
import org.triplehelix.wpilogmcp.data.ArrowType.Field;
import org.triplehelix.wpilogmcp.data.ColumnBuilder;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.LogFileException;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.tools.Buckets;
import org.triplehelix.wpilogmcp.tools.EntryData;

/**
 * {@code GET /data/entries}: every sample of one or more entries over a window, as an Apache
 * Arrow IPC stream or as CSV, for the viewer, a script, and a dashboard, which MCP's JSON
 * messages are the wrong shape for (EXPLORER_PLAN.md decision 7 and §6). It reads; it writes
 * nothing. The log is validated as every tool's path is, by the log manager's validator, so the
 * endpoint serves only files inside the configured log directories, and the transport's
 * {@code Origin} check keeps a web page from fetching it.
 *
 * <p>Query parameters: {@code path} (the log), {@code names} (entries or field paths, comma
 * separated, or repeated), {@code start_time} and {@code end_time} (seconds), {@code max_points}
 * (buckets, by {@link Buckets}' rule, the same as {@code read_entry}'s), and {@code format}
 * ({@code arrow}, the default, or {@code csv}). An Arrow stream has one schema, so every entry
 * in one request must have the same Arrow value type (several doubles, say); a bucketed request
 * needs only numeric entries. The stream's schema metadata carries what a tool result would (the
 * server version, {@code inputs}, the log's time range, and per entry its sampling class, the
 * unit its name states, and its sample count), each batch is tagged with its entry in its
 * message metadata, and a file that changes during the stream ends it with an empty batch whose
 * metadata says so, for the reader to discard what it received. {@code ETag} is from the file's
 * snapshot and the query, so a repeated request for an unchanged file is a {@code 304}. A
 * request over the size cap is refused with the count and the size, never cut.
 */
final class DataEndpoint {
  private static final Logger logger = LoggerFactory.getLogger(DataEndpoint.class);
  /** The content type of an Arrow IPC stream. */
  static final String ARROW_TYPE = "application/vnd.apache.arrow.stream";
  /** Rows per record batch: a few thousand, so no entry is held whole. */
  static final int BATCH_ROWS = 4096;
  /** A response may be this large by default: generous, and far below a heap exhausted. */
  static final long DEFAULT_MAX_BYTES = 512L * 1024 * 1024;

  private final LogManager logManager;
  private final Gson gson = new GsonBuilder().serializeNulls().create();
  private volatile long maxBytes = DEFAULT_MAX_BYTES;

  DataEndpoint(LogManager logManager) {
    this.logManager = logManager;
  }

  /** The size cap, in bytes. */
  void setMaxBytes(long maxBytes) {
    this.maxBytes = maxBytes;
  }

  /** A refusal with a status and a JSON body. */
  private static final class Refusal extends Exception {
    final int status;
    final JsonObject body;

    Refusal(int status, String error, String hint) {
      super(error);
      this.status = status;
      body = new JsonObject();
      body.addProperty("error", error);
      if (hint != null) body.addProperty("hint", hint);
    }
  }

  /** A request, parsed. */
  private record Request(String path, List<String> names, Double startTime, Double endTime,
      Integer maxPoints, String format) {}

  void handle(HttpExchange exchange) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      exchange.getResponseHeaders().set("Allow", "GET");
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    try {
      var request = parse(exchange.getRequestURI().getRawQuery());
      serve(exchange, request);
    } catch (Refusal r) {
      if (r.status == 503) exchange.getResponseHeaders().set("Retry-After", "2");
      sendJson(exchange, r.status, r.body);
    } catch (RuntimeException e) {
      logger.error("Data endpoint failed: {}", e.getMessage(), e);
      var body = new JsonObject();
      body.addProperty("error", "Internal error: " + e.getMessage());
      sendJson(exchange, 500, body);
    }
  }

  // ---- the request ----

  static Map<String, List<String>> query(String rawQuery) {
    var out = new LinkedHashMap<String, List<String>>();
    if (rawQuery == null || rawQuery.isEmpty()) return out;
    for (var pair : rawQuery.split("&")) {
      if (pair.isEmpty()) continue;
      int eq = pair.indexOf('=');
      var key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
      var value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      out.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }
    return out;
  }

  private static Request parse(String rawQuery) throws Refusal {
    var q = query(rawQuery);
    var paths = q.getOrDefault("path", List.of());
    if (paths.size() != 1 || paths.get(0).isBlank()) {
      throw new Refusal(400, "The query needs one path: the log file, as list_available_logs "
          + "gives it", "GET /data/entries?path=<log>&names=<entry>[,<entry>...]");
    }
    var names = new ArrayList<String>();
    for (var value : q.getOrDefault("names", List.of())) {
      for (var name : value.split(",")) {
        if (!name.isBlank()) names.add(name.strip());
      }
    }
    if (names.isEmpty()) {
      throw new Refusal(400, "The query needs names: one or more entries, or entries with a "
          + "field path appended, comma separated", "names=/SystemStats/BatteryVoltage");
    }
    Double start = number(q, "start_time");
    Double end = number(q, "end_time");
    if (start != null && end != null && end < start) {
      throw new Refusal(400, "end_time " + end + " is before start_time " + start, null);
    }
    Integer maxPoints = null;
    var maxPointsText = q.getOrDefault("max_points", List.of());
    if (!maxPointsText.isEmpty()) {
      try {
        maxPoints = Integer.parseInt(maxPointsText.get(0).strip());
      } catch (NumberFormatException e) {
        throw new Refusal(400, "max_points must be a whole number, not " + maxPointsText.get(0), null);
      }
      if (maxPoints < 1) throw new Refusal(400, "max_points must be at least 1", null);
    }
    var format = q.getOrDefault("format", List.of("arrow")).get(0).strip().toLowerCase();
    if (!format.equals("arrow") && !format.equals("csv")) {
      throw new Refusal(400, "format must be arrow or csv, not " + format, null);
    }
    return new Request(paths.get(0), List.copyOf(names), start, end, maxPoints, format);
  }

  private static Double number(Map<String, List<String>> q, String key) throws Refusal {
    var values = q.getOrDefault(key, List.of());
    if (values.isEmpty() || values.get(0).isBlank()) return null;
    try {
      double d = Double.parseDouble(values.get(0).strip());
      if (!Double.isFinite(d)) throw new NumberFormatException();
      return d;
    } catch (NumberFormatException e) {
      throw new Refusal(400, key + " must be a number of seconds, not " + values.get(0), null);
    }
  }

  // ---- the response ----

  /** A series with what the response says about it; {@code rev} for a REV log signal. */
  private record Prepared(EntryData.Series series, List<TimestampedValue> values,
      ArrowType valueType, Buckets.Result buckets, String sampling, String unit,
      EntryData.RevSync rev) {}

  private void serve(HttpExchange exchange, Request request) throws IOException, Refusal {
    LogManager.LogUse use;
    try {
      use = logManager.acquire(request.path());
    } catch (LogFileException e) {
      throw new Refusal(e.getMessage().startsWith("File not found") ? 404 : 403, e.getMessage(),
          "Pass a log file inside the configured log directories, as list_available_logs lists them");
    } catch (IOException e) {
      throw new Refusal(400, "The log could not be read: " + e.getMessage(), null);
    }
    try (use) {
      var log = use.log();
      var before = use.snapshot();

      var prepared = new ArrayList<Prepared>();
      var revValidators = new ArrayList<EntryData.RevValidator>();
      long estimatedBytes = 0;
      long rows = 0;
      for (var name : request.names()) {
        EntryData.Series series;
        EntryData.RevSync rev = null;
        if (name.startsWith(EntryData.REV_PREFIX)) {
          // A REV log signal, on the wpilog's clock, as get_revlog_data reads it
          try {
            var resolved = EntryData.resolveRev(logManager, log, name);
            series = resolved.series();
            rev = resolved.sync();
            revValidators.add(resolved.validator());
          } catch (EntryData.RevUnavailable e) {
            var refusal = new Refusal(e.status, e.getMessage(), e.hint);
            throw refusal;
          }
        } else {
          try {
            series = EntryData.resolve(log, name);
          } catch (IllegalArgumentException e) {
            throw new Refusal(404, e.getMessage(), "list_entries lists the log's entries; get_entry_info "
                + "lists an entry's numeric field paths");
          }
        }
        var values = series.inWindow(request.startTime(), request.endTime());
        Buckets.Result buckets = null;
        ArrowType valueType;
        if (request.maxPoints() != null) {
          if (!series.numeric() && !"boolean".equals(series.type())) {
            throw new Refusal(400, "max_points buckets numeric entries; " + name + " is "
                + series.type(), "Ask for a numeric field inside it (get_entry_info lists them), "
                + "or leave max_points out to read every sample");
          }
          var numeric = series.numeric() ? values : asNumbers(values);
          buckets = numeric.size() > request.maxPoints()
              ? Buckets.of(numeric, request.startTime(), request.endTime(), request.maxPoints())
              : null;
          valueType = series.numeric() ? EntryData.arrowType(log, series) : new ArrowType.Float64();
          if (!series.numeric()) values = numeric;
        } else {
          valueType = EntryData.arrowType(log, series);
        }
        long count = buckets != null ? buckets.buckets().size() : values.size();
        rows += count;
        estimatedBytes += buckets != null ? count * 56 : size(values, valueType);
        prepared.add(new Prepared(series, values, valueType, buckets,
            EntryData.sampling(series.values()),
            rev != null && rev.unit() != null ? rev.unit() : EntryData.unitFromName(name), rev));
      }
      if (estimatedBytes > maxBytes) {
        var refusal = new Refusal(413, "The response would be about " + estimatedBytes + " bytes ("
            + rows + " rows), over the cap of " + maxBytes,
            "Narrow the window with start_time and end_time, or pass max_points to bucket");
        refusal.body.addProperty("rows", rows);
        refusal.body.addProperty("bytes", estimatedBytes);
        refusal.body.addProperty("max_bytes", maxBytes);
        throw refusal;
      }
      boolean bucketed = request.maxPoints() != null;
      if (request.format().equals("arrow") && !bucketed) {
        var first = prepared.get(0).valueType();
        for (var p : prepared) {
          if (!p.valueType().equals(first)) {
            throw new Refusal(400, "An Arrow stream has one schema, and " + p.series().name()
                + " (" + describe(p.valueType()) + ") does not share the value type of "
                + prepared.get(0).series().name() + " (" + describe(first) + ")",
                "Request entries of different types separately, or pass max_points, whose "
                    + "buckets have one shape for every numeric entry");
          }
        }
      }

      var etag = etag(before, exchange.getRequestURI().getRawQuery(), revValidators);
      var ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
      if (etag != null && ifNoneMatch != null && matches(ifNoneMatch, etag)) {
        exchange.getResponseHeaders().set("ETag", etag);
        exchange.sendResponseHeaders(304, -1);
        exchange.close();
        return;
      }

      var headers = exchange.getResponseHeaders();
      headers.set("Content-Type", request.format().equals("arrow") ? ARROW_TYPE : "text/csv; charset=utf-8");
      headers.set("Cache-Control", "private, max-age=0, must-revalidate");
      if (etag != null) headers.set("ETag", etag);
      exchange.sendResponseHeaders(200, 0);
      try (var out = new BufferedOutputStream(exchange.getResponseBody(), 1 << 16)) {
        if (request.format().equals("arrow")) {
          writeArrow(out, log, request, prepared, before);
        } else {
          writeCsv(out, log, request, prepared, before);
        }
      }
    }
  }

  /** Booleans as numbers, for bucketing a boolean entry as the tools read it (1/0). */
  private static List<TimestampedValue> asNumbers(List<TimestampedValue> values) {
    var out = new ArrayList<TimestampedValue>(values.size());
    for (var tv : values) {
      out.add(new TimestampedValue(tv.timestamp(), tv.value() instanceof Boolean b ? (b ? 1.0 : 0.0)
          : tv.value() instanceof Number n ? n.doubleValue() : Double.NaN));
    }
    return out;
  }

  /** The bytes a series takes in the stream, near enough for the cap. */
  private static long size(List<TimestampedValue> values, ArrowType type) {
    long perRow = 8; // the timestamp
    if (type instanceof ArrowType.Float64 || type instanceof ArrowType.Int64) return values.size() * (perRow + 8);
    if (type instanceof ArrowType.Bool) return values.size() * (perRow + 1);
    long total = 0;
    for (var tv : values) total += perRow + sizeOf(tv.value());
    return total;
  }

  private static long sizeOf(Object value) {
    if (value == null) return 0;
    if (value instanceof String s) return 4 + s.length();
    if (value instanceof byte[] b) return 4 + b.length;
    if (value instanceof Number || value instanceof Boolean) return 8;
    if (value instanceof double[] a) return 4 + 8L * a.length;
    if (value instanceof long[] a) return 4 + 8L * a.length;
    if (value instanceof float[] a) return 4 + 8L * a.length;
    if (value instanceof boolean[] a) return 4 + a.length;
    if (value instanceof String[] a) {
      long n = 4;
      for (var s : a) n += 4 + (s == null ? 0 : s.length());
      return n;
    }
    if (value instanceof List<?> l) {
      long n = 4;
      for (var e : l) n += sizeOf(e);
      return n;
    }
    if (value instanceof Map<?, ?> m) {
      long n = 0;
      for (var e : m.values()) n += sizeOf(e);
      return n;
    }
    return 16;
  }

  private static String describe(ArrowType type) {
    if (type instanceof ArrowType.Float64) return "float64";
    if (type instanceof ArrowType.Int64) return "int64";
    if (type instanceof ArrowType.Bool) return "bool";
    if (type instanceof ArrowType.Utf8) return "utf8";
    if (type instanceof ArrowType.Binary) return "binary";
    if (type instanceof ArrowType.ListOf l) return "list<" + describe(l.child().type()) + ">";
    if (type instanceof ArrowType.Struct s) {
      var names = new ArrayList<String>();
      for (var f : s.fields()) names.add(f.name() + ": " + describe(f.type()));
      return "struct<" + String.join(", ", names) + ">";
    }
    return type.toString();
  }

  // ---- ETag ----

  static String etag(FileSnapshot snapshot, String rawQuery, List<EntryData.RevValidator> revValidators) {
    if (snapshot == null) return null;
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      var text = snapshot.size() + "|" + snapshot.modified().toMillis() + "|" + snapshot.fileKey()
          + "|" + Version.VERSION + "|" + (rawQuery == null ? "" : rawQuery) + "|" + revValidators;
      var hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
      return "\"" + HexFormat.of().formatHex(hash, 0, 16) + "\"";
    } catch (NoSuchAlgorithmException e) {
      return null;
    }
  }

  private static boolean matches(String ifNoneMatch, String etag) {
    for (var candidate : ifNoneMatch.split(",")) {
      var c = candidate.strip();
      if (c.startsWith("W/")) c = c.substring(2);
      if (c.equals("*") || c.equals(etag)) return true;
    }
    return false;
  }

  // ---- the metadata both formats carry ----

  private JsonObject inputs(LogData log, Request request, List<Prepared> prepared,
      FileSnapshot snapshot) {
    var inputs = new JsonObject();
    inputs.addProperty("log", log.path());
    var entries = new JsonArray();
    for (var p : prepared) entries.add(p.series().name());
    inputs.add("entries", entries);
    var window = new JsonObject();
    if (request.startTime() != null) window.addProperty("start_time", request.startTime());
    if (request.endTime() != null) window.addProperty("end_time", request.endTime());
    inputs.add("window", window);
    if (request.maxPoints() != null) inputs.addProperty("max_points", request.maxPoints());
    if (snapshot != null) {
      var file = new JsonObject();
      file.addProperty("size", snapshot.size());
      file.addProperty("modified", snapshot.modified().toString());
      inputs.add("file", file);
    }
    return inputs;
  }

  private JsonArray entriesMetadata(List<Prepared> prepared) {
    var entries = new JsonArray();
    for (var p : prepared) {
      var e = new JsonObject();
      e.addProperty("name", p.series().name());
      e.addProperty("entry", p.series().entry());
      if (p.series().field() != null) e.addProperty("field", p.series().field());
      e.addProperty("type", p.series().type());
      e.addProperty("value_type", describe(p.valueType()));
      e.addProperty("sampling", p.sampling());
      if (p.unit() != null) e.addProperty("unit", p.unit());
      e.addProperty("sample_count", p.series().values().size());
      e.addProperty("total_in_range", p.values().size());
      e.addProperty("bucketed", p.buckets() != null);
      if (p.buckets() != null) {
        e.addProperty("bucket_sec", p.buckets().bucketSec());
        e.addProperty("bucket_count", p.buckets().buckets().size());
      }
      if (p.rev() != null) {
        // How the REV timestamps were put on the wpilog's clock: the offset's basis
        var rev = new JsonObject();
        rev.addProperty("device", p.rev().device());
        rev.addProperty("signal", p.rev().signal());
        rev.addProperty("can_bus", p.rev().canBus());
        rev.addProperty("sync_method", p.rev().method());
        rev.addProperty("timestamps_aligned", p.rev().aligned());
        if (p.rev().offsetSeconds() != null) rev.addProperty("offset_seconds", p.rev().offsetSeconds());
        rev.addProperty("sync_confidence", p.rev().confidence());
        e.add("rev", rev);
      }
      entries.add(e);
    }
    return entries;
  }

  private Map<String, String> schemaMetadata(LogData log, Request request, List<Prepared> prepared,
      FileSnapshot snapshot) {
    var metadata = new LinkedHashMap<String, String>();
    metadata.put("server", "wpilog-mcp");
    metadata.put("server_version", Version.VERSION);
    metadata.put("inputs", gson.toJson(inputs(log, request, prepared, snapshot)));
    var range = new JsonObject();
    range.addProperty("start", log.minTimestamp());
    range.addProperty("end", log.maxTimestamp());
    metadata.put("time_range_sec", gson.toJson(range));
    metadata.put("entries", gson.toJson(entriesMetadata(prepared)));
    metadata.put("bucketed", String.valueOf(request.maxPoints() != null));
    if (log.truncated() && log.truncationMessage() != null) {
      metadata.put("log_truncation", log.truncationMessage());
    }
    return metadata;
  }

  // ---- Arrow ----

  private static final List<Field> BUCKET_FIELDS = List.of(
      new Field("timestamp", new ArrowType.TimestampMicros(), false),
      new Field("count", new ArrowType.Int64(), false),
      new Field("min", new ArrowType.Float64(), true),
      new Field("max", new ArrowType.Float64(), true),
      new Field("mean", new ArrowType.Float64(), true),
      new Field("first", new ArrowType.Float64(), false),
      new Field("last", new ArrowType.Float64(), false));

  private void writeArrow(OutputStream out, LogData log, Request request, List<Prepared> prepared,
      FileSnapshot before) throws IOException {
    boolean bucketed = request.maxPoints() != null;
    var fields = bucketed ? BUCKET_FIELDS : List.of(
        new Field("timestamp", new ArrowType.TimestampMicros(), false),
        new Field("value", prepared.get(0).valueType(), true));
    var writer = new ArrowStreamWriter(out, fields);
    writer.writeSchema(schemaMetadata(log, request, prepared, before));
    var columns = new ArrayList<ColumnBuilder>();
    for (var f : fields) columns.add(ColumnBuilder.of(f.type()));
    for (var p : prepared) {
      var tag = Map.of("entry", p.series().name());
      if (bucketed) {
        if (p.buckets() != null) {
          for (var b : p.buckets().buckets()) {
            columns.get(0).append(micros(b.start()));
            columns.get(1).append((long) b.count());
            columns.get(2).append(b.min());
            columns.get(3).append(b.max());
            columns.get(4).append(b.mean());
            columns.get(5).append(b.first());
            columns.get(6).append(b.last());
            if (columns.get(0).length() >= BATCH_ROWS) writer.writeBatch(columns, tag);
          }
        } else {
          // Few enough samples: each is its own bucket of one, exact
          for (var tv : p.values()) {
            double v = ((Number) tv.value()).doubleValue();
            columns.get(0).append(micros(tv.timestamp()));
            columns.get(1).append(1L);
            Double finite = Double.isFinite(v) ? v : null;
            columns.get(2).append(finite);
            columns.get(3).append(finite);
            columns.get(4).append(finite);
            columns.get(5).append(v);
            columns.get(6).append(v);
            if (columns.get(0).length() >= BATCH_ROWS) writer.writeBatch(columns, tag);
          }
        }
      } else {
        for (var tv : p.values()) {
          columns.get(0).append(micros(tv.timestamp()));
          columns.get(1).append(tv.value());
          if (columns.get(0).length() >= BATCH_ROWS) writer.writeBatch(columns, tag);
        }
      }
      // A batch per entry at least, so a reader sees every entry, samples or none
      writer.writeBatch(columns, tag);
    }
    var change = logManager.changeDuringCall(request.path(), log, before);
    if (change != null) {
      writer.writeBatch(columns, Map.of("file_changed", change));
    }
    writer.finish();
  }

  /** Seconds to the log's microseconds, as the file stores them. */
  static long micros(double seconds) {
    return Math.round(seconds * 1e6);
  }

  // ---- CSV ----

  private void writeCsv(OutputStream out, LogData log, Request request, List<Prepared> prepared,
      FileSnapshot before) throws IOException {
    Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
    w.write("# wpilog-mcp " + Version.VERSION + "\n");
    w.write("# inputs: " + gson.toJson(inputs(log, request, prepared, before)) + "\n");
    w.write("# entries: " + gson.toJson(entriesMetadata(prepared)) + "\n");
    boolean first = true;
    for (var p : prepared) {
      if (!first) w.write("\n");
      first = false;
      w.write("# entry: " + p.series().name() + "\n");
      if (p.buckets() != null) {
        w.write("timestamp_sec,count,min,max,mean,first,last\n");
        for (var b : p.buckets().buckets()) {
          w.write(b.start() + "," + b.count() + "," + cell(b.min()) + "," + cell(b.max()) + ","
              + cell(b.mean()) + "," + b.first() + "," + b.last() + "\n");
        }
      } else if (request.maxPoints() != null) {
        w.write("timestamp_sec,count,min,max,mean,first,last\n");
        for (var tv : p.values()) {
          double v = ((Number) tv.value()).doubleValue();
          var finite = Double.isFinite(v) ? String.valueOf(v) : "";
          w.write(tv.timestamp() + ",1," + finite + "," + finite + "," + finite + "," + v + "," + v + "\n");
        }
      } else {
        var columns = EntryData.csvColumns(p.values());
        w.write(String.join(",", columns) + "\n");
        for (var tv : p.values()) {
          for (var row : EntryData.csvRows(tv, columns)) w.write(String.join(",", row) + "\n");
        }
      }
    }
    var change = logManager.changeDuringCall(request.path(), log, before);
    if (change != null) {
      w.write("\n# file_changed: " + change + "\n");
    }
    w.flush();
  }

  private static String cell(Double d) {
    return d == null ? "" : String.valueOf(d);
  }

  // ---- JSON ----

  private void sendJson(HttpExchange exchange, int status, JsonObject body) throws IOException {
    var bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  /** The endpoint's URL on a transport bound at {@code host:port}. */
  static String url(String host, int port) {
    var h = host == null || host.isBlank() || host.equals("0.0.0.0") ? "127.0.0.1" : host;
    return "http://" + h + ":" + port + "/data/entries";
  }
}
