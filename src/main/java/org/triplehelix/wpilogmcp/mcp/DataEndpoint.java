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
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import org.triplehelix.wpilogmcp.read.UtcTimeMapping;
import org.triplehelix.wpilogmcp.read.server.SourceScopes;
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
 * <p>Query parameters: {@code path} (the log), or {@code capture=current} for the capture
 * recording at the time of the request, which follows a rollover or a new session without the
 * caller knowing the file, and reads the closed files of its session that the window reaches as
 * one recording ({@link DataRecording}); {@code names} (entries or field paths, comma separated, or repeated),
 * {@code start_time} and {@code end_time} (seconds), {@code max_points} (buckets, by
 * {@link Buckets}' rule, the same as {@code read_entry}'s), and {@code format} ({@code arrow}, the
 * default, or {@code csv}). Optional {@code utc=true} adds a nullable timestamp measured from the
 * log's WallClock readings, with its basis beside each series. {@code start_utc} and
 * {@code end_utc} instead choose the window by that measured UTC, as a dashboard's time range
 * does; a log that cannot be placed in UTC is refused rather than answered with an empty table.
 * Scalar struct angles unwrap before presentation; {@code angle=radians|degrees} declares
 * the same treatment for plain numbers.
 * An Arrow stream has one schema, so every entry
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
  private static final DateTimeFormatter UTC_FORMAT =
      new DateTimeFormatterBuilder().appendInstant(6).toFormatter(Locale.ROOT);
  /** Epochs whose UTC text has a four-digit year, 27 characters: years 1000 through 9999. */
  private static final long FOUR_DIGIT_YEARS_FROM = epochMicros(Instant.parse("1000-01-01T00:00:00Z"));
  private static final long FOUR_DIGIT_YEARS_TO = epochMicros(Instant.parse("9999-12-31T23:59:59.999999Z"));

  private final LogManager logManager;
  private final org.triplehelix.wpilogmcp.read.server.ManagedLogAccess logAccess;
  /** The capture this server records, if any: {@code capture=current} reads its open file. */
  private final java.util.function.Supplier<org.triplehelix.wpilogmcp.capture.LiveCapture> captures;
  /** The stores' catalog, the read API's own, which chooses a robot range's files. */
  private final org.triplehelix.wpilogmcp.read.server.CatalogRecordingSource.Catalogs catalogs;
  private final Gson gson = new GsonBuilder().serializeNulls().create();
  private volatile long maxBytes = DEFAULT_MAX_BYTES;

  DataEndpoint(LogManager logManager) {
    this(logManager, () -> null);
  }

  DataEndpoint(LogManager logManager, java.util.function.Supplier<org.triplehelix.wpilogmcp.capture.LiveCapture> captures) {
    this(logManager, captures, () -> NO_CATALOG);
  }

  DataEndpoint(LogManager logManager, java.util.function.Supplier<org.triplehelix.wpilogmcp.capture.LiveCapture> captures,
      org.triplehelix.wpilogmcp.read.server.CatalogRecordingSource.Catalogs catalogs) {
    this.logManager = logManager;
    this.logAccess = logManager.readAccess();
    this.captures = captures;
    this.catalogs = catalogs;
  }

  private static final org.triplehelix.wpilogmcp.read.server.CatalogRecordingSource.Lease NO_CATALOG =
      new org.triplehelix.wpilogmcp.read.server.CatalogRecordingSource.Lease() {
        @Override public org.triplehelix.wpilogmcp.read.catalog.StoreReadCatalog value() { return null; }
        @Override public void close() {}
      };

  /** The size cap, in bytes. */
  void setMaxBytes(long maxBytes) {
    this.maxBytes = maxBytes;
  }

  /** A refusal with a status and a JSON body. */
  static final class Refusal extends Exception {
    final int status;
    final JsonObject body;

    Refusal(int status, JsonObject body) {
      super(body.get("error").getAsString());
      this.status = status;
      this.body = body;
    }

    Refusal(int status, String error, String hint) {
      super(error);
      this.status = status;
      body = new JsonObject();
      body.addProperty("error", error);
      if (hint != null) body.addProperty("hint", hint);
    }
  }

  /**
   * A request, parsed. {@code path} is null for {@code capture=current} until the capture is
   * resolved; {@code startUtc} and {@code endUtc} are epoch microseconds, a start rounded up and an
   * end down, so the window holds the whole microseconds inside the instants asked for.
   */
  private record Request(String path, boolean currentCapture, List<String> names, Double startTime,
      Double endTime, Long startUtc, Long endUtc, Integer maxPoints, String format, String scopeId,
      JsonObject source, boolean utc, String angle) {
    boolean utcWindow() { return startUtc != null || endUtc != null; }
    Request at(String resolved) {
      return new Request(resolved, currentCapture, names, startTime, endTime, startUtc, endUtc, maxPoints,
          format, scopeId, source, utc, angle);
    }
  }

  @FunctionalInterface interface SourceResolver { java.nio.file.Path resolve(JsonObject source) throws IOException; }

  void handle(HttpExchange exchange) throws IOException {
    handle(exchange, source -> { throw new IOException("Qualified sources are unavailable on this server"); });
  }
  void handle(HttpExchange exchange, SourceResolver sources) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      exchange.getResponseHeaders().set("Allow", "GET");
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    try {
      String raw = exchange.getRequestURI().getRawQuery();
      if (query(raw).containsKey("robot")) serveRange(exchange, parseRange(raw));
      else serve(exchange, parse(raw), sources);
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
    if (rawQuery != null && rawQuery.length() > 65536) throw new Refusal(413, "Data query exceeds 64 KiB", null);
    var q = query(rawQuery);
    var paths = q.getOrDefault("path", List.of());
    String capture = one(q, "capture");
    if (capture != null && !capture.equals("current"))
      throw new Refusal(400, "capture must be current, the capture recording when the request arrives, not " + capture, null);
    if (capture != null && !paths.isEmpty())
      throw new Refusal(400, "Pass either path or capture=current, not both", null);
    if (capture == null && (paths.size() != 1 || paths.get(0).isBlank())) {
      throw new Refusal(400, "The query needs one path: the log file, as list_available_logs "
          + "gives it", "GET /data/entries?path=<log>&names=<entry>[,<entry>...]");
    }
    var names = names(q, "names");
    if (names.isEmpty()) {
      throw new Refusal(400, "The query needs names: one or more entries, or entries with a "
          + "field path appended, comma separated", "names=/SystemStats/BatteryVoltage");
    }
    Double start = number(q, "start_time");
    Double end = number(q, "end_time");
    if (start != null && end != null && end < start) {
      throw new Refusal(400, "end_time " + end + " is before start_time " + start, null);
    }
    Long startUtc = instant(q, "start_utc", true);
    Long endUtc = instant(q, "end_utc", false);
    if ((startUtc != null || endUtc != null) && (start != null || end != null))
      throw new Refusal(400, "A window is start_time and end_time, in the log's seconds, or start_utc and "
          + "end_utc, as instants; not both", null);
    if (startUtc != null && endUtc != null && endUtc < startUtc)
      throw new Refusal(400, "end_utc " + one(q, "end_utc") + " is before start_utc " + one(q, "start_utc"), null);
    Integer maxPoints = maxPoints(q);
    var format = format(q);
    String utc = one(q, "utc");
    if (utc != null && !utc.equals("true") && !utc.equals("false"))
      throw new Refusal(400, "utc must be true or false", null);
    boolean utcWindow = startUtc != null || endUtc != null;
    if (utcWindow && "false".equals(utc))
      throw new Refusal(400, "start_utc and end_utc choose the window by UTC, so utc cannot be false", null);
    String angle = angle(q);
    String scope = one(q, "scope_id"), reference = one(q, "source_ref");
    JsonObject source = null;
    try {
      if (scope != null) java.util.UUID.fromString(scope);
      if (reference != null) {
        source = org.triplehelix.wpilogmcp.visualization.VisualizationContracts.source(
            com.google.gson.JsonParser.parseString(reference).getAsJsonObject());
        if (!source.has("schema_version") || source.get("schema_version").getAsInt() != 2)
          throw new IllegalArgumentException("Data source_ref requires schema version 2");
        if (scope != null && !scope.equals(source.get("scope_id").getAsString()))
          throw new IllegalArgumentException("Data scope differs from source_ref");
        scope = source.get("scope_id").getAsString();
      }
    } catch (RuntimeException invalid) { throw new Refusal(400, "Invalid data source qualification: " + invalid.getMessage(), null); }
    if (capture != null && (scope != null || source != null))
      throw new Refusal(400, "capture=current reads this server's own recording, which no source qualification names", null);
    return new Request(capture == null ? paths.get(0) : null, capture != null, List.copyOf(names), start, end,
        startUtc, endUtc, maxPoints, format, scope, source, utcWindow || "true".equals(utc), angle);
  }

  /** Entries, comma separated or repeated, in the order asked. */
  private static List<String> names(Map<String, List<String>> q, String key) {
    var names = new ArrayList<String>();
    for (var value : q.getOrDefault(key, List.of())) {
      for (var name : value.split(",")) {
        if (!name.isBlank()) names.add(name.strip());
      }
    }
    return List.copyOf(names);
  }

  private static Integer maxPoints(Map<String, List<String>> q) throws Refusal {
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
    return maxPoints;
  }

  private static String format(Map<String, List<String>> q) throws Refusal {
    var format = q.getOrDefault("format", List.of("arrow")).get(0).strip().toLowerCase(Locale.ROOT);
    if (!format.equals("arrow") && !format.equals("csv")) {
      throw new Refusal(400, "format must be arrow or csv, not " + format, null);
    }
    return format;
  }

  private static String angle(Map<String, List<String>> q) throws Refusal {
    String angle = one(q, "angle");
    try { EntryData.validateAngle(angle); }
    catch (IllegalArgumentException invalid) { throw new Refusal(400, invalid.getMessage(), null); }
    return angle;
  }

  /**
   * An ISO 8601 instant as epoch microseconds. A start that falls between two microseconds
   * rounds up and an end rounds down, so the window holds exactly the whole microseconds inside it.
   */
  private static Long instant(Map<String, List<String>> q, String key, boolean start) throws Refusal {
    String text = one(q, key);
    if (text == null) return null;
    try {
      var instant = java.time.OffsetDateTime.parse(text.strip()).toInstant();
      long micros = Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1000);
      return start && instant.getNano() % 1000 != 0 ? Math.addExact(micros, 1) : micros;
    } catch (java.time.format.DateTimeParseException | ArithmeticException invalid) {
      throw new Refusal(400, key + " must be an ISO 8601 instant with its offset, such as "
          + "2026-03-21T16:30:00Z, not " + text, null);
    }
  }

  private static String one(Map<String, List<String>> q, String name) throws Refusal {
    var values = q.getOrDefault(name, List.of());
    if (values.size() > 1 || !values.isEmpty() && values.get(0).isBlank()) throw new Refusal(400, name + " must occur once and be nonempty", null);
    return values.isEmpty() ? null : values.get(0);
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
      EntryData.RevSync rev, JsonObject decoding, JsonObject utcBasis, long sampleCount) {}

  /** Places a time on a log's clock in UTC: one log's own mapping, or a recording's clock across its files. */
  @FunctionalInterface interface Placement { UtcTimeMapping.Mapping at(long logMicros); }

  /** What a recording of several files adds to a response: the files read, oldest first, those skipped, and their span. */
  private record Recorded(JsonArray files, JsonArray skipped, double minSec, double maxSec) {}

  /**
   * The capture recording now: its open file and its session's closed files. With nothing
   * recording, a refusal that says why and names the last session, so a panel shows why it is blank.
   */
  private org.triplehelix.wpilogmcp.capture.LiveCapture.Recording currentCapture() throws Refusal {
    var live = captures.get();
    var recording = live == null ? null : live.recording();
    if (recording != null) return recording;
    var current = live == null ? null : live.current();
    var last = org.triplehelix.wpilogmcp.capture.LiveCapture.location(live, live == null ? null : current);
    var body = new JsonObject();
    body.addProperty("error", live == null ? "Capture is not enabled on this server" : "No capture session is open");
    body.addProperty("last_session", last.path());
    body.addProperty("ended_at", last.endedAt());
    body.addProperty("hint", "Read a finished session by its path, as list_sessions or list_available_logs gives it");
    throw new Refusal(404, body);
  }

  /**
   * The inclusive window of log microseconds whose samples have UTC inside [startUtc, endUtc],
   * or null when none can. The mapping never runs backwards along the log's time, so its first
   * and last such microseconds are found by search: a sample is inside exactly when its time is.
   * Samples before the clock's first reading have no UTC and lie in no window.
   */
  static long[] logWindow(UtcTimeMapping utc, Long startUtc, Long endUtc) {
    long first = utc.firstLogMicros();
    long low = startUtc == null ? first : firstAtOrAfter(utc, first, startUtc);
    if (endUtc == null) return new long[] {low, Long.MAX_VALUE};
    var atFirst = utc.at(first).epochMicros();
    if (atFirst == null || atFirst > endUtc) return null;
    // The last microsecond still at or before endUtc: past it the mapping exceeds it or overflows.
    long lo = first, hi = Long.MAX_VALUE;
    while (lo < hi) {
      long middle = lo + (hi - lo + 1) / 2;
      var epoch = utc.at(middle).epochMicros();
      if (epoch != null && epoch <= endUtc) lo = middle; else hi = middle - 1;
    }
    return low > lo ? null : new long[] {low, lo};
  }

  /** The first microsecond, from {@code first} on, that the mapping places at or after {@code target}. */
  private static long firstAtOrAfter(UtcTimeMapping utc, long first, long target) {
    long lo = first, hi = Long.MAX_VALUE;
    while (lo < hi) {
      long middle = lo + (hi - lo) / 2;
      var epoch = utc.at(middle).epochMicros();
      if (epoch == null || epoch >= target) hi = middle; else lo = middle + 1;
    }
    return lo;
  }

  private void serve(HttpExchange exchange, Request request, SourceResolver sources) throws IOException, Refusal {
    org.triplehelix.wpilogmcp.read.server.ManagedReadSnapshot use;
    List<org.triplehelix.wpilogmcp.capture.LiveCapture.SessionFile> earlier = List.of();
    try {
      if (request.currentCapture()) {
        var recording = currentCapture();
        request = request.at(recording.open().toString());
        earlier = DataRecording.nominate(recording.earlier(), request.startTime(), request.endTime(),
            request.startUtc(), request.endUtc());
      }
      checkSource(request, sources);
      use = logAccess.acquire(request.path(), request.scopeId());
    } catch (SourceScopes.Failure failure) {
      throw new Refusal(409, failure.result());
    } catch (LogFileException e) {
      throw new Refusal(e.getMessage().startsWith("File not found") ? 404 : 403, e.getMessage(),
          "Pass a log file inside the configured log directories, as list_available_logs lists them");
    } catch (IOException e) {
      throw new Refusal(400, "The log could not be read: " + e.getMessage(), null);
    }
    try (use) {
      if (!earlier.isEmpty()) {
        serveRecording(exchange, request, sources, use, earlier);
        return;
      }
      var log = use.log();
      var before = use.snapshot();
      var utc = request.utc() ? log.utcMapping() : null;
      Placement placement = utc == null ? null : utc::at;
      long[] logWindow = null;
      if (request.utcWindow()) {
        var unplaced = utc.unavailableReason();
        if (unplaced.isPresent()) {
          var refusal = new Refusal(422, "This log cannot be placed in UTC: " + unplaced.get(),
              "Read it by start_time and end_time, in the log's own seconds");
          refusal.body.add("utc_basis", basisJson(utc.basis()));
          throw refusal;
        }
        logWindow = logWindow(utc, request.startUtc(), request.endUtc());
      }

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
        EntryData.Prepared presentation;
        try {
          if (!request.utcWindow()) {
            presentation = EntryData.prepare(log, series, request.startTime(), request.endTime(), request.angle());
          } else if (logWindow != null) {
            presentation = EntryData.prepareMicros(log, series, logWindow[0], logWindow[1], request.angle());
          } else {
            // No microsecond of the log lies in the UTC window: an empty selection, presented alike.
            presentation = EntryData.prepareMicros(log, series, 0, -1, request.angle());
          }
        } catch (IllegalArgumentException invalid) {
          throw new Refusal(400, invalid.getMessage(), "Select a numeric entry or scalar field for angle presentation");
        }
        var values = presentation.values();
        Buckets.Result buckets = null;
        ArrowType valueType;
        if (request.maxPoints() != null) {
          if (!series.numeric() && !"boolean".equals(series.type())) {
            throw new Refusal(400, "max_points buckets numeric entries; " + name + " is "
                + series.type(), "Ask for a numeric field inside it (get_entry_info lists them), "
                + "or leave max_points out to read every sample");
          }
          var numbers = presentation.samples();
          if (numbers != null) {
            // By position: the window's numbers are bucketed where they lie.
            buckets = numbers.size() > request.maxPoints()
                ? Buckets.of(numbers, bucketStart(request, logWindow), bucketEnd(request, logWindow), request.maxPoints())
                : null;
            if (!series.numeric()) values = org.triplehelix.wpilogmcp.read.NumericSamples.asValues(numbers);
          } else {
            var numeric = series.numeric() ? values : asNumbers(values);
            buckets = numeric.size() > request.maxPoints()
                ? Buckets.of(numeric, bucketStart(request, logWindow), bucketEnd(request, logWindow), request.maxPoints())
                : null;
            if (!series.numeric()) values = numeric;
          }
          valueType = series.numeric() ? EntryData.arrowType(log, series) : new ArrowType.Float64();
        } else {
          valueType = EntryData.arrowType(log, series);
        }
        // An integer measured in radians can acquire a fractional full-turn adjustment.
        // Ordinary typed reads still use their original Arrow representation.
        if (presentation.angle()) valueType = new ArrowType.Float64();
        long count = buckets != null ? buckets.buckets().size() : values.size();
        rows += count;
        estimatedBytes += buckets != null ? count * 56 : size(values, valueType);
        if (utc != null) estimatedBytes += utcSize(request, values, buckets, count, placement);
        var basis = utc == null ? null : utcBasis(utc.basis(), placement, values, buckets);
        if (basis != null && request.utcWindow()) unplaced(basis, utc, series);
        prepared.add(new Prepared(series, values, valueType, buckets,
            EntryData.sampling(log, series, presentation),
            rev != null && rev.unit() != null ? rev.unit() : EntryData.unitFromName(name), rev,
            EntryData.decodingMetadata(log, series), basis, series.values().size()));
      }
      respond(exchange, request, sources, use, log, before, prepared, estimatedBytes, rows, revValidators, placement, null);
    }
  }

  /**
   * The rest of a request once its entries are prepared: the size cap, the Arrow schema's one
   * value type, the source and the file checked again, the validator, and the response.
   */
  private void respond(HttpExchange exchange, Request request, SourceResolver sources,
      org.triplehelix.wpilogmcp.read.server.ManagedReadSnapshot use, LogData log, FileSnapshot before,
      List<Prepared> prepared, long estimatedBytes, long rows, List<EntryData.RevValidator> revValidators,
      Placement utc, Recorded recorded) throws IOException, Refusal {
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

    // A cached reply is still a read of this exact owner/scope/content. Recheck after
    // preparing values so replacement or revoked admission cannot publish stale data.
    checkSource(request, sources);
    // A capture still recording is read as its writer's published prefix, which has no
    // snapshot: appending does not change what this request read.
    if (before != null && !before.sameAs(FileSnapshot.of(use.path()))) throw new Refusal(409, "The log changed during this data request", "Reopen the selected source");
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
        writeArrow(out, log, request, prepared, before, use.replica(), utc, recorded);
      } else {
        writeCsv(out, log, request, prepared, before, use.replica(), utc, recorded);
      }
    }
  }

  // ---- a recording of several files ----

  /** A file of a recording, read: its use, its log, and its snapshot, null while it records. */
  private record Part(org.triplehelix.wpilogmcp.read.server.ManagedReadSnapshot use, LogData log, FileSnapshot before) {}

  /**
   * {@code capture=current} once the window reaches closed files of the session as well as the
   * open one, as {@link DataRecording} describes: each entry's samples from every file in time
   * order, presented as one file's are and placed in UTC by the recording's clock. A nominated
   * file that cannot be read is listed with why, and the others answer.
   */
  private void serveRecording(HttpExchange exchange, Request request, SourceResolver sources,
      org.triplehelix.wpilogmcp.read.server.ManagedReadSnapshot open,
      List<org.triplehelix.wpilogmcp.capture.LiveCapture.SessionFile> earlier) throws IOException, Refusal {
    var parts = new ArrayList<Part>();
    var skipped = new JsonArray();
    try {
      for (var file : earlier) {
        try {
          // A closed file of the recording is read for what the request names: its load starts no REV synchronization.
          var use = logAccess.acquireWithoutRevSync(file.path().toString());
          parts.add(new Part(use, use.log(), use.snapshot()));
        } catch (IOException unreadable) {
          var json = new JsonObject();
          json.addProperty("path", file.path().toString());
          json.addProperty("reason", "Unreadable: " + unreadable.getMessage());
          skipped.add(json);
        }
      }
      // Oldest first, by each file's last record; the open file runs on after them.
      parts.sort(java.util.Comparator.comparingDouble((Part p) -> p.log().maxTimestamp()).thenComparing(p -> p.log().path()));
      parts.add(new Part(open, open.log(), open.snapshot()));
      answerRecording(exchange, request, sources, parts, skipped);
    } finally {
      for (var part : parts) if (part.use() != open) part.use().close();
    }
  }

  private void answerRecording(HttpExchange exchange, Request request, SourceResolver sources, List<Part> parts,
      JsonArray skipped) throws IOException, Refusal {
    int n = parts.size();
    var ends = new long[n];
    for (int k = 0; k < n; k++) ends[k] = micros(parts.get(k).log().maxTimestamp());
    DataRecording.Clock clock = null;
    List<long[]> window = null;
    if (request.utc()) {
      var clocks = new ArrayList<UtcTimeMapping>();
      for (var part : parts) clocks.add(part.log().utcMapping());
      clock = new DataRecording.Clock(clocks, ends);
      if (request.utcWindow()) {
        if (!clock.places()) {
          var own = clock.clock(n - 1);
          var refusal = new Refusal(422, "No file of this recording can be placed in UTC: " + own.unavailableReason().get(),
              "Read it by start_time and end_time, in the log's own seconds");
          refusal.body.add("utc_basis", basisJson(own.basis()));
          throw refusal;
        }
        window = clock.window(request.startUtc(), request.endUtc());
      }
    }
    var prepared = new ArrayList<Prepared>();
    long estimatedBytes = 0, rows = 0;
    for (var name : request.names()) {
      if (name.startsWith(EntryData.REV_PREFIX))
        throw new Refusal(400, "capture=current reads the recording's WPILOG entries; " + name
            + " is a REV signal, read by its log's path", "GET /data/entries?path=<log>&names=" + name);
      var pieces = new ArrayList<List<TimestampedValue>>();
      var found = new ArrayList<EntryData.Series>();
      EntryData.Series last = null;
      LogData lastLog = null;
      ArrowType first = null;
      long sampleCount = 0;
      String absent = null;
      for (var part : parts) {
        EntryData.Series series;
        try {
          series = EntryData.resolve(part.log(), name);
        } catch (IllegalArgumentException missing) {
          absent = missing.getMessage();
          continue;
        }
        if (request.maxPoints() != null && !series.numeric() && !"boolean".equals(series.type())) {
          throw new Refusal(400, "max_points buckets numeric entries; " + name + " is " + series.type() + " in "
              + part.log().path(), "Ask for a numeric field inside it (get_entry_info lists them), "
              + "or leave max_points out to read every sample");
        }
        var type = EntryData.arrowType(part.log(), series);
        if (first == null) first = type;
        else if (request.format().equals("arrow") && request.maxPoints() == null && !type.equals(first)) {
          throw new Refusal(400, "An Arrow stream has one schema, and " + name + " in " + part.log().path() + " ("
              + describe(type) + ") does not share the value type of the same entry in an earlier file ("
              + describe(first) + ")", "Pass max_points, whose buckets have one shape, or ask for CSV");
        }
        pieces.add(recorded(part.log(), series, request, window));
        found.add(series);
        sampleCount += series.values().size();
        last = series;
        lastLog = part.log();
      }
      if (last == null) {
        throw new Refusal(404, absent, "list_entries lists the log's entries; get_entry_info "
            + "lists an entry's numeric field paths");
      }
      // One series of the recording, so an angle unwraps once across its files.
      var series = new EntryData.Series(last.name(), last.entry(), last.field(), last.type(),
          DataRecording.merge(pieces), last.numeric());
      EntryData.Prepared presentation;
      try {
        presentation = EntryData.prepareMicros(lastLog, series, Long.MIN_VALUE, Long.MAX_VALUE, request.angle());
      } catch (IllegalArgumentException invalid) {
        throw new Refusal(400, invalid.getMessage(), "Select a numeric entry or scalar field for angle presentation");
      }
      var values = presentation.values();
      Buckets.Result buckets = null;
      ArrowType valueType = EntryData.arrowType(lastLog, last);
      if (request.maxPoints() != null) {
        var numeric = series.numeric() ? values : asNumbers(values);
        Double start = request.startTime(), end = request.endTime();
        if (request.utcWindow()) {
          start = window.isEmpty() ? null : window.get(0)[0] / 1e6;
          long high = window.isEmpty() ? Long.MAX_VALUE : window.get(window.size() - 1)[1];
          end = high == Long.MAX_VALUE ? null : high / 1e6;
        }
        buckets = numeric.size() > request.maxPoints() ? Buckets.of(numeric, start, end, request.maxPoints()) : null;
        if (!series.numeric()) {
          values = numeric;
          valueType = new ArrowType.Float64();
        }
      }
      if (presentation.angle()) valueType = new ArrowType.Float64();
      long count = buckets != null ? buckets.buckets().size() : values.size();
      rows += count;
      estimatedBytes += buckets != null ? count * 56 : size(values, valueType);
      JsonObject basis = null;
      if (clock != null) {
        estimatedBytes += utcSize(request, values, buckets, count, clock);
        basis = utcBasis(clock.clock(n - 1).basis(), clock, values, buckets);
        if (request.utcWindow()) unplaced(basis, clock, found);
      }
      prepared.add(new Prepared(series, values, valueType, buckets, EntryData.sampling(lastLog, last.entry(), pieces),
          EntryData.unitFromName(name), null, EntryData.decodingMetadata(lastLog, last), basis, sampleCount));
    }
    // A closed file that changed while it was read is refused, as a single file is.
    for (var part : parts.subList(0, n - 1)) {
      if (part.before() != null && !part.before().sameAs(FileSnapshot.of(part.use().path())))
        throw new Refusal(409, "A log changed during this data request: " + part.log().path(), "Ask again");
    }
    var files = new JsonArray();
    double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
    for (int k = 0; k < n; k++) {
      var log = parts.get(k).log();
      var file = new JsonObject();
      file.addProperty("index", k);
      file.addProperty("path", log.path());
      if (k == n - 1) file.addProperty("recording", true);
      else file.addProperty("end_sec", log.maxTimestamp());
      if (clock != null) file.add("utc_basis", basisJson(clock.clock(k).basis()));
      files.add(file);
      min = Math.min(min, log.minTimestamp());
      max = Math.max(max, log.maxTimestamp());
    }
    var open = parts.get(n - 1);
    respond(exchange, request, sources, open.use(), open.log(), open.before(), prepared, estimatedBytes, rows,
        List.of(), clock, new Recorded(files, skipped, min, max));
  }

  /**
   * An entry's samples in one file of a recording, as recorded: inside the UTC window's robot
   * times, inside the window in the log's seconds, which the files share, or all of them.
   */
  private static List<TimestampedValue> recorded(LogData log, EntryData.Series series, Request request,
      List<long[]> window) {
    if (!request.utcWindow()) return EntryData.prepare(log, series, request.startTime(), request.endTime(), null).recordedValues();
    var out = new ArrayList<TimestampedValue>();
    long first = micros(log.minTimestamp()), last = micros(log.maxTimestamp());
    for (var interval : window) {
      if (interval[1] < first || interval[0] > last) continue;
      out.addAll(EntryData.prepareMicros(log, series, interval[0], interval[1], null).recordedValues());
    }
    return out;
  }

  /**
   * How many of an entry's samples, in every file of a recording, its clock places nowhere, and
   * why: a dashboard's response says what a UTC window cannot hold, as for one file.
   */
  private static void unplaced(JsonObject basis, DataRecording.Clock clock, List<EntryData.Series> series) {
    long count = 0;
    var hit = new ArrayList<DataRecording.Clock.Unplaced>();
    for (var stretch : clock.unplaced()) {
      long here = 0;
      for (var one : series) here += between(one, stretch.fromUs(), stretch.toUs());
      if (here > 0) hit.add(stretch);
      count += here;
    }
    basis.addProperty("unplaced_sample_count", count);
    basis.addProperty("unplaced_reason", DataRecording.Clock.reasons(hit));
  }

  /** How many of a series' samples lie between two times on its log's clock, both included. */
  private static long between(EntryData.Series series, long fromUs, long toUs) {
    if (series.samples() != null) {
      var samples = series.samples();
      return org.triplehelix.wpilogmcp.read.TimeWindows.bound(samples.size(), i -> samples.timeUs(i) <= toUs)
          - org.triplehelix.wpilogmcp.read.TimeWindows.bound(samples.size(), i -> samples.timeUs(i) < fromUs);
    }
    return series.values().stream().filter(tv -> tv.timestampUs() >= fromUs && tv.timestampUs() <= toUs).count();
  }

  // ---- robot ranges ----

  /**
   * A robot range: the robot's files over a UTC window, chosen by {@link DataRanges} and each read
   * by its own clock. With {@code source=robot_log}, the default, a stretch reads the roboRIO's log
   * where one exists and the pit's recording otherwise, which needs each entry's name in both, since
   * the two name one signal differently: {@code names} for the roboRIO's logs and
   * {@code capture_names} for the recordings, paired by position. {@code source=capture} reads the
   * recordings alone. A REV signal is one signal whichever log places it, so a pair names it
   * alike; it is read from the robot's REV logs, each placed through a recorded alignment.
   */
  private record RangeRequest(String robot, String store, boolean capturesOnly, List<String> names,
      List<String> captureNames, long startUtc, long endUtc, Integer maxPoints, String format, String angle) {
    int entries() { return capturesOnly ? captureNames.size() : names.size(); }
    String label(int entry) { return names.isEmpty() ? captureNames.get(entry) : names.get(entry); }
    String name(DataRanges.Role role, int entry) {
      var chosen = role == DataRanges.Role.ROBOT_LOG ? names : captureNames;
      return chosen.isEmpty() ? null : chosen.get(entry);
    }
    /** A REV signal, read from the robot's REV logs and not from a WPILOG. */
    boolean rev(int entry) { return label(entry).startsWith(EntryData.REV_PREFIX); }
    String revKey(int entry) { return label(entry).substring(EntryData.REV_PREFIX.length()); }
    boolean anyRev() {
      for (int i = 0; i < entries(); i++) if (rev(i)) return true;
      return false;
    }
  }

  /**
   * A placed file: what the catalog nominated, the log read, and its UTC span inside the window; a
   * WPILOG's clock, and a REV log's anchor, the index of the WPILOG that places it, with the alignment.
   */
  private record Placed(DataRanges.Candidate candidate, String log, long startUtc, long endUtc, UtcTimeMapping utc,
      int anchor, org.triplehelix.wpilogmcp.sync.SyncResult alignment) {}

  /** A sample of a range: its measured UTC, the placed file it came from, and its value there. */
  private record RangeSample(long utcUs, int file, TimestampedValue value) {}

  /** One entry of a range: its samples from every file in UTC order, bucketed when asked. */
  private record RangeEntry(String label, String robotLogName, String captureName, List<RangeSample> samples,
      Buckets.Result buckets, JsonArray files, ArrowType valueType) {}

  private static RangeRequest parseRange(String rawQuery) throws Refusal {
    if (rawQuery != null && rawQuery.length() > 65536) throw new Refusal(413, "Data query exceeds 64 KiB", null);
    var q = query(rawQuery);
    String robot = one(q, "robot");
    if (robot == null || robot.isBlank())
      throw new Refusal(400, "robot names a robot by its id or serial number", "GET /data/robots lists the store's robots");
    for (var other : List.of("path", "capture", "start_time", "end_time", "source_ref", "scope_id")) {
      if (q.containsKey(other))
        throw new Refusal(400, "A robot range chooses its files by UTC; " + other + " cannot be combined with robot", null);
    }
    String source = one(q, "source");
    if (source != null && !source.equals("robot_log") && !source.equals("capture"))
      throw new Refusal(400, "source must be robot_log or capture, not " + source, null);
    boolean capturesOnly = "capture".equals(source);
    var names = names(q, "names");
    var captureNames = names(q, "capture_names");
    if (capturesOnly ? captureNames.isEmpty() : names.isEmpty()) {
      throw new Refusal(400, capturesOnly
          ? "source=capture needs capture_names: the entries as the pit's recordings name them"
          : "A robot range needs names: the entries as the roboRIO's logs name them",
          "names=/SystemStats/BatteryVoltage&capture_names=NT:/AdvantageKit/SystemStats/BatteryVoltage");
    }
    if (!names.isEmpty() && !captureNames.isEmpty() && names.size() != captureNames.size())
      throw new Refusal(400, "names and capture_names pair by position, so they need as many entries each, not "
          + names.size() + " and " + captureNames.size(), null);
    // A REV signal is one signal whichever WPILOG places its log, so a pair names it alike.
    for (int i = 0; i < Math.max(names.size(), captureNames.size()); i++) {
      String robotName = names.isEmpty() ? null : names.get(i), captureName = captureNames.isEmpty() ? null : captureNames.get(i);
      boolean rev = robotName != null && robotName.startsWith(EntryData.REV_PREFIX)
          || captureName != null && captureName.startsWith(EntryData.REV_PREFIX);
      if (rev && robotName != null && captureName != null && !robotName.equals(captureName))
        throw new Refusal(400, "A REV signal is one signal whichever log places it, so names and capture_names name it "
            + "alike; entry " + (i + 1) + " is " + robotName + " and " + captureName, null);
      for (var name : java.util.Arrays.asList(robotName, captureName)) {
        if (name != null && name.equals(EntryData.REV_PREFIX))
          throw new Refusal(400, "A REV signal names a device and a signal after " + EntryData.REV_PREFIX,
              "names=REV/SparkMax_3/AppliedOutput");
      }
    }
    Long startUtc = instant(q, "start_utc", true);
    Long endUtc = instant(q, "end_utc", false);
    if (startUtc == null || endUtc == null)
      throw new Refusal(400, "A robot range needs start_utc and end_utc",
          "start_utc=2026-10-23T14:00:00Z&end_utc=2026-10-23T15:00:00Z");
    if (endUtc < startUtc)
      throw new Refusal(400, "end_utc " + one(q, "end_utc") + " is before start_utc " + one(q, "start_utc"), null);
    String utc = one(q, "utc");
    if (utc != null && !utc.equals("true"))
      throw new Refusal(400, "A robot range places every file by its UTC, so utc can only be true", null);
    return new RangeRequest(robot.strip(), one(q, "store"), capturesOnly, names, captureNames, startUtc, endUtc,
        maxPoints(q), format(q), angle(q));
  }

  private void serveRange(HttpExchange exchange, RangeRequest request) throws IOException, Refusal {
    var placed = new ArrayList<Placed>();
    var skipped = new JsonArray();
    int count = request.entries();
    var samples = new ArrayList<List<RangeSample>>();
    var files = new ArrayList<JsonArray>();
    var types = new ArrayList<ArrowType>();
    for (int i = 0; i < count; i++) { samples.add(new ArrayList<>()); files.add(new JsonArray()); types.add(null); }
    JsonArray problems;
    // The catalog is held while the files are read: a REV log opens through it, by its recorded alignment.
    try (var lease = catalogs.acquire()) {
      var stores = DataRanges.stores(lease.value(), request.store());
      problems = DataRanges.problems(lease.value(), stores);
      var candidates = DataRanges.candidates(stores, request.robot(), !request.capturesOnly(),
          !request.captureNames().isEmpty(), request.anyRev(), request.startUtc(), request.endUtc());
      for (var candidate : candidates) {
        if (candidate.role() != DataRanges.Role.REV) readWpilog(request, candidate, placed, skipped, samples, files, types);
      }
      if (request.anyRev()) readRev(lease.value(), stores, candidates, request, placed, skipped, samples, files, types);
    }
    refuseOverlaps(placed);
    var covered = placed.stream().filter(p -> p.candidate().role() == DataRanges.Role.ROBOT_LOG).toList();
    var entries = new ArrayList<RangeEntry>();
    long rows = 0, estimatedBytes = 0;
    if (request.format().equals("arrow") && request.maxPoints() == null) {
      ArrowType first = null;
      for (int i = 0; i < count; i++) {
        if (types.get(i) == null) continue;
        if (first == null) first = types.get(i);
        else if (!types.get(i).equals(first)) {
          throw new Refusal(400, "An Arrow stream has one schema, and " + request.label(i) + " (" + describe(types.get(i))
              + ") does not share the value type of the first entry (" + describe(first) + ")",
              "Request entries of different types separately, or pass max_points");
        }
      }
    }
    for (int i = 0; i < count; i++) {
      var list = samples.get(i);
      // A recording's samples within a roboRIO log's span are left to that log: nothing merges them.
      list.removeIf(s -> placed.get(s.file()).candidate().role() == DataRanges.Role.CAPTURE && within(covered, s.utcUs()));
      list.sort(java.util.Comparator.comparingLong(RangeSample::utcUs));
      Buckets.Result buckets = null;
      if (request.maxPoints() != null && list.size() > request.maxPoints()) {
        var relative = new ArrayList<TimestampedValue>(list.size());
        for (var sample : list) {
          relative.add(new TimestampedValue((sample.utcUs() - request.startUtc()) / 1e6, sample.value().value()));
        }
        buckets = Buckets.of(relative, 0.0, (request.endUtc() - request.startUtc()) / 1e6, request.maxPoints());
      }
      long n = buckets != null ? buckets.buckets().size() : list.size();
      rows += n;
      if (request.maxPoints() != null) estimatedBytes += n * 88;
      else for (var sample : list) estimatedBytes += 48 + sizeOf(sample.value().value());
      entries.add(new RangeEntry(request.label(i), request.name(DataRanges.Role.ROBOT_LOG, i),
          request.name(DataRanges.Role.CAPTURE, i), list, buckets, files.get(i),
          types.get(i) == null ? new ArrowType.Float64() : types.get(i)));
    }
    if (estimatedBytes > maxBytes) {
      var refusal = new Refusal(413, "The response would be about " + estimatedBytes + " bytes ("
          + rows + " rows), over the cap of " + maxBytes, "Narrow the window, or pass max_points to bucket");
      refusal.body.addProperty("rows", rows);
      refusal.body.addProperty("bytes", estimatedBytes);
      refusal.body.addProperty("max_bytes", maxBytes);
      throw refusal;
    }
    var inputs = rangeInputs(request, placed, skipped, stretches(request, placed, covered));
    inputs.add("catalog_problems", problems);
    var described = rangeEntries(entries);
    var headers = exchange.getResponseHeaders();
    headers.set("Content-Type", request.format().equals("arrow") ? ARROW_TYPE : "text/csv; charset=utf-8");
    // Several files and perhaps a recording still growing: no validator stands for them all.
    headers.set("Cache-Control", "private, no-store");
    exchange.sendResponseHeaders(200, 0);
    try (var out = new BufferedOutputStream(exchange.getResponseBody(), 1 << 16)) {
      if (request.format().equals("arrow")) writeRangeArrow(out, request, inputs, described, entries);
      else writeRangeCsv(out, request, inputs, described, entries);
    }
  }

  /** One WPILOG of a range: placed by its own clock, and each entry's samples inside the window. */
  private void readWpilog(RangeRequest request, DataRanges.Candidate candidate, List<Placed> placed, JsonArray skipped,
      List<List<RangeSample>> samples, List<JsonArray> files, List<ArrowType> types) throws IOException, Refusal {
    org.triplehelix.wpilogmcp.read.server.ManagedReadSnapshot use;
    try {
      // A range reads what it names: loading a log here starts no REV synchronization.
      use = logAccess.acquireWithoutRevSync(candidate.path().toString());
    } catch (IOException unreadable) {
      skipped.add(skippedFile(candidate, "Unreadable: " + unreadable.getMessage()));
      return;
    }
    try (use) {
      var log = use.log();
      var before = use.snapshot();
      var utc = log.utcMapping();
      var unplaced = utc.unavailableReason();
      if (unplaced.isPresent()) {
        skipped.add(skippedFile(candidate, "Not placed in UTC: " + unplaced.get()));
        return;
      }
      var window = logWindow(utc, request.startUtc(), request.endUtc());
      var span = window == null ? null : span(log, utc, window, request);
      if (span == null) {
        skipped.add(skippedFile(candidate, "Outside the window"));
        return;
      }
      int index = placed.size();
      placed.add(new Placed(candidate, log.path(), span[0], span[1], utc, -1, null));
      for (int i = 0; i < request.entries(); i++) {
        if (request.rev(i)) continue;
        String name = request.name(candidate.role(), i);
        if (name == null) continue;
        var facts = new JsonObject();
        facts.addProperty("file", index);
        facts.addProperty("entry", name);
        files.get(i).add(facts);
        EntryData.Series series;
        try {
          series = EntryData.resolve(log, name);
        } catch (IllegalArgumentException missing) {
          facts.addProperty("missing", missing.getMessage());
          continue;
        }
        if (request.maxPoints() != null && !series.numeric() && !"boolean".equals(series.type())) {
          throw new Refusal(400, "max_points buckets numeric entries; " + name + " is " + series.type()
              + " in " + log.path(), "Ask for a numeric field inside it, or leave max_points out to read every sample");
        }
        EntryData.Prepared presentation;
        try {
          presentation = EntryData.prepareMicros(log, series, window[0], window[1], request.angle());
        } catch (IllegalArgumentException invalid) {
          throw new Refusal(400, invalid.getMessage(), "Select a numeric entry or scalar field for angle presentation");
        }
        var type = request.maxPoints() != null || presentation.angle() ? new ArrowType.Float64() : EntryData.arrowType(log, series);
        if (types.get(i) == null) types.set(i, type);
        else if (request.format().equals("arrow") && !types.get(i).equals(type)) {
          throw new Refusal(400, "An Arrow stream has one schema, and " + name + " in " + log.path() + " ("
              + describe(type) + ") does not share the value type of the same entry in an earlier file ("
              + describe(types.get(i)) + ")", "Pass max_points, whose buckets have one shape, or ask for CSV");
        }
        var values = presentation.values();
        if (request.maxPoints() != null && !series.numeric()) values = asNumbers(values);
        int kept = 0;
        for (var tv : values) {
          var at = utc.at(tv.timestampUs()).epochMicros();
          if (at == null || at < request.startUtc() || at > request.endUtc()) continue;
          samples.get(i).add(new RangeSample(at, index, tv));
          kept++;
        }
        facts.addProperty("type", series.type());
        facts.addProperty("sample_count", kept);
      }
      // A finished file that changed while it was read is refused, as a single file is.
      if (before != null && !before.sameAs(FileSnapshot.of(use.path())))
        throw new Refusal(409, "A log changed during this data request: " + log.path(), "Ask again");
    }
  }

  /**
   * A range's REV signals. Each REV log the catalog nominated is placed through its recorded
   * alignment with a WPILOG the range placed, of its own session, a roboRIO log before a recording,
   * and decoded for the signals asked for alone; nothing is correlated during a request. A REV log
   * that no placed WPILOG aligns is listed with why. Two REV logs that answer one entry over the
   * same minutes are refused together, as two WPILOGs of one source are.
   */
  private static void readRev(org.triplehelix.wpilogmcp.read.catalog.StoreReadCatalog catalog,
      List<org.triplehelix.wpilogmcp.store.StoreCatalog.Snapshot> stores, List<DataRanges.Candidate> candidates,
      RangeRequest request, List<Placed> placed, JsonArray skipped, List<List<RangeSample>> samples, List<JsonArray> files,
      List<ArrowType> types) throws IOException, Refusal {
    var keys = new java.util.LinkedHashSet<String>();
    for (int i = 0; i < request.entries(); i++) if (request.rev(i)) keys.add(request.revKey(i));
    var wpilogs = placed.stream().map(Placed::candidate).toList();
    var answered = new LinkedHashMap<Integer, List<long[]>>();
    for (var candidate : candidates) {
      if (candidate.role() != DataRanges.Role.REV) continue;
      var anchor = DataRanges.anchor(candidate, wpilogs, stores);
      if (anchor.file() < 0) {
        skipped.add(skippedFile(candidate, anchor.refusal()));
        continue;
      }
      var on = placed.get(anchor.file());
      org.triplehelix.wpilogmcp.read.rev.RecordedRevRead read;
      try {
        read = catalog.openRev(candidate.path(), on.candidate().path(), keys);
      } catch (org.triplehelix.wpilogmcp.read.ReadException refused) {
        switch (refused.code()) {
          case BUSY, RESOURCE_LIMIT -> throw new Refusal(503, "A REV log cannot be decoded now: " + refused.getMessage(), "Ask again");
          case FILE_CHANGED -> throw new Refusal(409, "A log changed during this data request: " + candidate.path(), "Ask again");
          default -> {
            skipped.add(skippedFile(candidate, "Unreadable: " + refused.getMessage()));
            continue;
          }
        }
      } catch (IOException unreadable) {
        skipped.add(skippedFile(candidate, "Unreadable: " + unreadable.getMessage()));
        continue;
      }
      try (read) {
        if (read.state() != org.triplehelix.wpilogmcp.read.rev.RecordedRevRead.State.RECORDED) {
          skipped.add(skippedFile(candidate, "Its recorded alignment cannot place it: " + read.reason()));
          continue;
        }
        int index = placed.size();
        var kept = new LinkedHashMap<Integer, List<RangeSample>>();
        long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
        boolean holds = false;
        for (int i = 0; i < request.entries(); i++) {
          if (!request.rev(i) || !read.signals().containsKey(request.revKey(i))) continue;
          holds = true;
          var here = new ArrayList<RangeSample>();
          // On the anchor's clock, by the recorded alignment, then in UTC by the anchor's own clock.
          for (var tv : read.values(request.revKey(i))) {
            var at = on.utc().at(tv.timestampUs()).epochMicros();
            if (at == null || at < request.startUtc() || at > request.endUtc()) continue;
            here.add(new RangeSample(at, index, tv));
            first = Math.min(first, at);
            last = Math.max(last, at);
          }
          kept.put(i, here);
        }
        if (!holds) {
          skipped.add(skippedFile(candidate, "Holds none of the REV signals asked for"));
          continue;
        }
        if (first > last) {
          skipped.add(skippedFile(candidate, "Outside the window"));
          continue;
        }
        placed.add(new Placed(candidate, read.path().toString(), first, last, null, anchor.file(), read.alignment()));
        for (var entry : kept.entrySet()) {
          int i = entry.getKey();
          var here = entry.getValue();
          var facts = new JsonObject();
          facts.addProperty("file", index);
          facts.addProperty("entry", request.revKey(i));
          facts.addProperty("type", "double");
          facts.addProperty("sample_count", here.size());
          files.get(i).add(facts);
          if (here.isEmpty()) continue;
          here.sort(java.util.Comparator.comparingLong(RangeSample::utcUs));
          for (var span : answered.getOrDefault(i, List.of())) {
            if (span[0] <= here.get(here.size() - 1).utcUs() && here.get(0).utcUs() <= span[1]) {
              var refusal = new Refusal(409, "Two of the robot's REV logs answer " + request.label(i) + " over the same "
                  + "minutes, and nothing merges them: " + placed.get((int) span[2]).log() + " and " + read.path(),
                  "Narrow the time range to one of them, or read each by its path");
              var both = new JsonArray();
              both.add(placed.get((int) span[2]).log());
              both.add(read.path().toString());
              refusal.body.add("files", both);
              throw refusal;
            }
          }
          answered.computeIfAbsent(i, k -> new ArrayList<>()).add(new long[] {here.get(0).utcUs(), here.get(here.size() - 1).utcUs(), index});
          samples.get(i).addAll(here);
          if (types.get(i) == null) types.set(i, new ArrowType.Float64());
        }
      }
    }
  }

  private static JsonObject skippedFile(DataRanges.Candidate candidate, String reason) {
    var skipped = new JsonObject();
    skipped.addProperty("path", candidate.path().toString());
    skipped.addProperty("source", candidate.role().wire);
    skipped.addProperty("reason", reason);
    return skipped;
  }

  /**
   * A placed file's span: the UTC of its first and last placed records inside the window, or null
   * when none lie in it. Records before the clock's first reading have no UTC and are not counted.
   */
  private static long[] span(LogData log, UtcTimeMapping utc, long[] window, RangeRequest request) {
    long first = Math.max(Math.max(window[0], utc.firstLogMicros()), micros(log.minTimestamp()));
    long last = Math.min(window[1], micros(log.maxTimestamp()));
    if (last < first) return null;
    Long from = utc.at(first).epochMicros(), to = utc.at(last).epochMicros();
    if (from == null || to == null || to < request.startUtc() || from > request.endUtc()) return null;
    return new long[] {Math.max(from, request.startUtc()), Math.min(to, request.endUtc())};
  }

  /**
   * Two of the robot's logs, or recordings of two sessions, that cover the same minutes are refused
   * together, since nothing merges them. The files of one recording are read in order, as one: a
   * same-boot reconnect's file starts with values that the closed file already holds.
   */
  private static void refuseOverlaps(List<Placed> placed) throws Refusal {
    for (int a = 0; a < placed.size(); a++) {
      for (int b = a + 1; b < placed.size(); b++) {
        var x = placed.get(a);
        var y = placed.get(b);
        // REV logs are checked entry by entry as they are read: two buses' logs answer different signals.
        if (x.candidate().role() != y.candidate().role() || x.candidate().role() == DataRanges.Role.REV) continue;
        if (x.candidate().role() == DataRanges.Role.CAPTURE
            && java.util.Objects.equals(x.candidate().sessionId(), y.candidate().sessionId())) continue;
        if (x.startUtc() <= y.endUtc() && y.startUtc() <= x.endUtc()) {
          var refusal = new Refusal(409, "Two of the robot's " + (x.candidate().role() == DataRanges.Role.ROBOT_LOG
              ? "logs" : "recordings") + " cover the same minutes, and nothing merges them: " + x.log() + " and " + y.log(),
              "Narrow the time range to one of them, or read each by its path");
          var both = new JsonArray();
          both.add(x.log());
          both.add(y.log());
          refusal.body.add("files", both);
          throw refusal;
        }
      }
    }
  }

  private static boolean within(List<Placed> spans, long utcUs) {
    for (var span : spans) if (span.startUtc() <= utcUs && utcUs <= span.endUtc()) return true;
    return false;
  }

  /** Which file answers each stretch of the window: a roboRIO log where one exists, else a recording. */
  private static JsonArray stretches(RangeRequest request, List<Placed> placed, List<Placed> covered) {
    record Stretch(long start, long end, int file, String source) {}
    var stretches = new ArrayList<Stretch>();
    for (int i = 0; i < placed.size(); i++) {
      var p = placed.get(i);
      // A REV log answers its own signals, beside whichever WPILOG answers a stretch.
      if (p.candidate().role() == DataRanges.Role.REV) continue;
      if (p.candidate().role() == DataRanges.Role.ROBOT_LOG) {
        stretches.add(new Stretch(p.startUtc(), p.endUtc(), i, p.candidate().role().wire));
        continue;
      }
      // A recording answers only where no roboRIO log does.
      long from = p.startUtc();
      var cuts = covered.stream().filter(c -> c.startUtc() <= p.endUtc() && c.endUtc() >= p.startUtc())
          .sorted(java.util.Comparator.comparingLong(Placed::startUtc)).toList();
      for (var cut : cuts) {
        if (cut.startUtc() > from) stretches.add(new Stretch(from, cut.startUtc() - 1, i, p.candidate().role().wire));
        from = Math.max(from, cut.endUtc() + 1);
      }
      if (from <= p.endUtc()) stretches.add(new Stretch(from, p.endUtc(), i, p.candidate().role().wire));
    }
    stretches.sort(java.util.Comparator.comparingLong(Stretch::start).thenComparingInt(Stretch::file));
    var out = new JsonArray();
    for (var stretch : stretches) {
      var json = new JsonObject();
      json.addProperty("file", stretch.file());
      json.addProperty("source", stretch.source());
      json.addProperty("start_utc", utcText(stretch.start()));
      json.addProperty("end_utc", utcText(stretch.end()));
      out.add(json);
    }
    return out;
  }

  private JsonObject rangeInputs(RangeRequest request, List<Placed> placed, JsonArray skipped, JsonArray stretches) {
    var inputs = new JsonObject();
    inputs.addProperty("robot", request.robot());
    if (request.store() != null) inputs.addProperty("store", request.store());
    inputs.addProperty("source", request.capturesOnly() ? "capture" : "robot_log");
    var window = new JsonObject();
    window.addProperty("start_utc", utcText(request.startUtc()));
    window.addProperty("end_utc", utcText(request.endUtc()));
    inputs.add("window", window);
    if (request.maxPoints() != null) inputs.addProperty("max_points", request.maxPoints());
    inputs.addProperty("utc", true);
    if (request.angle() != null) inputs.addProperty("angle", request.angle());
    var list = new JsonArray();
    for (int i = 0; i < placed.size(); i++) {
      var p = placed.get(i);
      var file = new JsonObject();
      file.addProperty("index", i);
      file.addProperty("path", p.log());
      file.addProperty("source", p.candidate().role().wire);
      file.addProperty("session_id", p.candidate().sessionId());
      if (p.candidate().open()) file.addProperty("recording", true);
      file.addProperty("start_utc", utcText(p.startUtc()));
      file.addProperty("end_utc", utcText(p.endUtc()));
      if (p.alignment() != null) {
        // How the REV log's times were put on its anchor's clock: the recorded alignment's basis.
        file.addProperty("anchor", p.anchor());
        file.addProperty("sync_method", p.alignment().method().name());
        file.addProperty("offset_seconds", p.alignment().offsetSeconds());
        file.addProperty("sync_confidence", p.alignment().confidenceLevel().getLabel());
      }
      list.add(file);
    }
    inputs.add("files", list);
    inputs.add("skipped", skipped);
    inputs.add("stretches", stretches);
    return inputs;
  }

  private static JsonArray rangeEntries(List<RangeEntry> entries) {
    var out = new JsonArray();
    for (var e : entries) {
      var json = new JsonObject();
      json.addProperty("name", e.label());
      if (e.robotLogName() != null) json.addProperty("robot_log_name", e.robotLogName());
      if (e.captureName() != null) json.addProperty("capture_name", e.captureName());
      var unit = EntryData.unitFromName(e.label());
      if (unit != null) json.addProperty("unit", unit);
      json.addProperty("value_type", describe(e.valueType()));
      json.addProperty("total_in_range", e.samples().size());
      json.addProperty("bucketed", e.buckets() != null);
      if (e.buckets() != null) {
        json.addProperty("bucket_sec", e.buckets().bucketSec());
        json.addProperty("bucket_count", e.buckets().buckets().size());
      }
      json.add("files", e.files());
      out.add(json);
    }
    return out;
  }

  private void writeRangeCsv(OutputStream out, RangeRequest request, JsonObject inputs, JsonArray described,
      List<RangeEntry> entries) throws IOException {
    Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
    w.write("# wpilog-mcp " + Version.VERSION + "\n");
    w.write("# inputs: " + gson.toJson(inputs) + "\n");
    w.write("# entries: " + gson.toJson(described) + "\n");
    boolean first = true;
    var row = new CsvRow(w);
    for (var e : entries) {
      if (!first) w.write("\n");
      first = false;
      w.write("# entry: " + e.label() + "\n");
      if (e.buckets() != null) {
        w.write("timestamp_utc,count,min,max,mean,first,last\n");
        for (int i = 0; i < e.buckets().buckets().size(); i++) {
          var b = e.buckets().buckets().get(i);
          row.epoch(request.startUtc() + e.buckets().startUs(i)).number(b.count()).comma().cell(b.min()).comma()
              .cell(b.max()).comma().cell(b.mean()).comma().number(b.first()).comma().number(b.last()).end();
        }
      } else if (request.maxPoints() != null) {
        w.write("timestamp_utc,count,min,max,mean,first,last\n");
        for (var sample : e.samples()) {
          double v = ((Number) sample.value().value()).doubleValue();
          Double finite = Double.isFinite(v) ? v : null;
          row.epoch(sample.utcUs()).number(1).comma().cell(finite).comma().cell(finite).comma().cell(finite).comma()
              .number(v).comma().number(v).end();
        }
      } else {
        // Each row keeps its own file's time, timestamp_sec, beside the UTC the file's clock gives it.
        var columns = EntryData.csvColumns(e.samples().stream().map(RangeSample::value).toList());
        w.write("timestamp_utc,file," + String.join(",", columns) + "\n");
        for (var sample : e.samples()) {
          for (var cells : EntryData.csvRows(sample.value(), columns)) {
            w.write(utcText(sample.utcUs()) + "," + sample.file() + "," + String.join(",", cells) + "\n");
          }
        }
      }
    }
    w.flush();
  }

  private static final List<Field> RANGE_BUCKET_FIELDS = List.of(
      new Field("timestamp_utc", new ArrowType.TimestampMicros("UTC"), false),
      new Field("count", new ArrowType.Int64(), false),
      new Field("min", new ArrowType.Float64(), true),
      new Field("max", new ArrowType.Float64(), true),
      new Field("mean", new ArrowType.Float64(), true),
      new Field("first", new ArrowType.Float64(), false),
      new Field("last", new ArrowType.Float64(), false));

  private void writeRangeArrow(OutputStream out, RangeRequest request, JsonObject inputs, JsonArray described,
      List<RangeEntry> entries) throws IOException {
    boolean bucketed = request.maxPoints() != null;
    var fields = bucketed ? RANGE_BUCKET_FIELDS : List.of(
        new Field("timestamp_utc", new ArrowType.TimestampMicros("UTC"), false),
        new Field("file", new ArrowType.Int64(), false),
        new Field("timestamp", new ArrowType.TimestampMicros(), false),
        new Field("value", entries.get(0).valueType(), true));
    var metadata = new LinkedHashMap<String, String>();
    metadata.put("server", "wpilog-mcp");
    metadata.put("server_version", Version.VERSION);
    metadata.put("inputs", gson.toJson(inputs));
    metadata.put("entries", gson.toJson(described));
    metadata.put("bucketed", String.valueOf(bucketed));
    var writer = new ArrowStreamWriter(out, fields);
    writer.writeSchema(metadata);
    var columns = new ArrayList<ColumnBuilder>();
    for (var f : fields) columns.add(ColumnBuilder.of(f.type()));
    for (var e : entries) {
      var tag = Map.of("entry", e.label());
      if (e.buckets() != null) {
        for (int i = 0; i < e.buckets().buckets().size(); i++) {
          var b = e.buckets().buckets().get(i);
          columns.get(0).append(request.startUtc() + e.buckets().startUs(i));
          columns.get(1).append((long) b.count());
          columns.get(2).append(b.min());
          columns.get(3).append(b.max());
          columns.get(4).append(b.mean());
          columns.get(5).append(b.first());
          columns.get(6).append(b.last());
          if (columns.get(0).length() >= BATCH_ROWS) writer.writeBatch(columns, tag);
        }
      } else if (bucketed) {
        for (var sample : e.samples()) {
          double v = ((Number) sample.value().value()).doubleValue();
          Double finite = Double.isFinite(v) ? v : null;
          columns.get(0).append(sample.utcUs());
          columns.get(1).append(1L);
          columns.get(2).append(finite);
          columns.get(3).append(finite);
          columns.get(4).append(finite);
          columns.get(5).append(v);
          columns.get(6).append(v);
          if (columns.get(0).length() >= BATCH_ROWS) writer.writeBatch(columns, tag);
        }
      } else {
        for (var sample : e.samples()) {
          columns.get(0).append(sample.utcUs());
          columns.get(1).append((long) sample.file());
          columns.get(2).append(sample.value().timestampUs());
          columns.get(3).append(sample.value().value());
          if (columns.get(0).length() >= BATCH_ROWS) writer.writeBatch(columns, tag);
        }
      }
      writer.writeBatch(columns, tag);
    }
    writer.finish();
  }

  // ---- the catalog, for a dashboard's choices ----

  /** {@code GET /data/robots}: a store's robots, a CSV row each, for a dashboard's robot choice. */
  void handleRobots(HttpExchange exchange) throws IOException {
    if (!onlyGet(exchange)) return;
    try {
      var q = query(exchange.getRequestURI().getRawQuery());
      var rows = new StringBuilder("robot_id,serial_number,name,basis,store_id\n");
      try (var lease = catalogs.acquire()) {
        var stores = DataRanges.stores(lease.value(), one(q, "store"));
        var problems = DataRanges.problems(lease.value(), stores);
        if (!problems.isEmpty()) rows.insert(0, "# catalog_problems: " + gson.toJson(problems) + "\n");
        for (var store : stores) {
          for (var directory : store.robots()) {
            var robot = directory.robot();
            if (robot == null) continue;
            rows.append(cell(robot.id())).append(',').append(cell(robot.serialNumber())).append(',')
                .append(cell(robot.name())).append(',').append(cell(robot.basis())).append(',')
                .append(cell(store.header() == null ? null : store.header().id())).append('\n');
          }
        }
      }
      sendCsv(exchange, rows);
    } catch (Refusal r) {
      sendJson(exchange, r.status, r.body);
    }
  }

  /**
   * {@code GET /data/sessions}: one robot's sessions over an optional UTC range, a CSV row each,
   * from the catalog alone. Each time is the session's own, labeled with its basis: measured from a
   * log's wall clock, the pit's clock for a capture, or a file's name or modification time.
   */
  void handleSessions(HttpExchange exchange) throws IOException {
    if (!onlyGet(exchange)) return;
    try {
      var q = query(exchange.getRequestURI().getRawQuery());
      String robot = one(q, "robot");
      if (robot == null || robot.isBlank())
        throw new Refusal(400, "robot names a robot by its id or serial number", "GET /data/robots lists the store's robots");
      Long startUtc = instant(q, "start_utc", true), endUtc = instant(q, "end_utc", false);
      record Row(long start, String text) {}
      var found = new ArrayList<Row>();
      boolean known = false;
      JsonArray problems;
      try (var lease = catalogs.acquire()) {
        var stores = DataRanges.stores(lease.value(), one(q, "store"));
        problems = DataRanges.problems(lease.value(), stores);
        for (var store : stores) {
          for (var directory : store.robots()) known |= DataRanges.matches(directory.robot(), robot.strip());
          for (var directory : store.sessions()) {
            var session = directory.session();
            if (session == null || !DataRanges.matches(directory.robot(), robot.strip())) continue;
            Long start = DataRanges.epochMicros(session.startedAt());
            boolean recording = session.openCapture() != null;
            Long end = recording ? null : DataRanges.epochMicros(session.endedAt());
            if (endUtc != null && start != null && start > endUtc) continue;
            if (startUtc != null && end != null && end < startUtc) continue;
            boolean robotLogs = session.files().stream().anyMatch(f -> "wpilog".equals(f.kind())
                && (f.provenance() == null || !f.provenance().captured()));
            boolean captures = recording || session.files().stream().anyMatch(f -> f.provenance() != null && f.provenance().captured());
            String sources = robotLogs && captures ? "both" : robotLogs ? "robot_log" : captures ? "capture" : "";
            var text = new StringBuilder();
            text.append(cell(session.id())).append(',').append(cell(session.startedAt())).append(',')
                .append(cell(recording ? null : session.endedAt())).append(',')
                .append(start == null ? "" : String.valueOf(Math.floorDiv(start, 1000))).append(',')
                .append(end == null ? "" : String.valueOf(Math.floorDiv(end, 1000))).append(',')
                .append(cell(session.startBasis())).append(',').append(cell(session.event())).append(',')
                .append(cell(session.matchType())).append(',')
                .append(session.matchNumber() == null ? "" : session.matchNumber().toString()).append(',')
                .append(sources).append(',').append(session.files().size() + (recording ? 1 : 0)).append(',')
                .append(recording).append('\n');
            found.add(new Row(start == null ? Long.MAX_VALUE : start, text.toString()));
          }
        }
      }
      if (!known) {
        throw new Refusal(404, "The store knows no robot " + robot.strip() + ", by id or serial number",
            "GET /data/robots lists the store's robots");
      }
      found.sort(java.util.Comparator.comparingLong(Row::start));
      var rows = new StringBuilder();
      if (!problems.isEmpty()) rows.append("# catalog_problems: ").append(gson.toJson(problems)).append('\n');
      rows.append("session_id,started_at,ended_at,start_ms,end_ms,time_basis,event,match_type,match_number,sources,files,recording\n");
      found.forEach(r -> rows.append(r.text()));
      sendCsv(exchange, rows);
    } catch (Refusal r) {
      sendJson(exchange, r.status, r.body);
    }
  }

  private static boolean onlyGet(HttpExchange exchange) throws IOException {
    if ("GET".equals(exchange.getRequestMethod())) return true;
    exchange.getResponseHeaders().set("Allow", "GET");
    exchange.sendResponseHeaders(405, -1);
    exchange.close();
    return false;
  }

  /** A CSV cell: empty for null, quoted when it holds a comma, a quote or a line break. */
  static String cell(String value) {
    if (value == null) return "";
    if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) return value;
    return '"' + value.replace("\"", "\"\"") + '"';
  }

  private static void sendCsv(HttpExchange exchange, CharSequence body) throws IOException {
    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
    exchange.sendResponseHeaders(200, bytes.length);
    try (var out = exchange.getResponseBody()) { out.write(bytes); }
  }

  private void checkSource(Request request, SourceResolver sources) throws Refusal {
    try {
      if (request.source() != null && !sources.resolve(request.source()).equals(logManager.validateSourcePath(java.nio.file.Path.of(request.path()))))
        throw new Refusal(409, "The data path no longer identifies the selected source", "Resolve the original source again; do not select another copy");
    } catch (IOException unavailable) { throw new Refusal(409, unavailable.getMessage(), "Reopen the selected source; do not switch owners automatically"); }
  }

  /** Booleans as numbers, for bucketing a boolean entry as the tools read it (1/0). */
  private static List<TimestampedValue> asNumbers(List<TimestampedValue> values) {
    var out = new ArrayList<TimestampedValue>(values.size());
    for (var tv : values) {
      out.add(tv.withValue(tv.value() instanceof Boolean b ? (b ? 1.0 : 0.0)
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

  /** CSV expands arrays into rows, so their repeated UTC cells must also count toward the cap. */
  private static long utcSize(Request request, List<TimestampedValue> values,
      Buckets.Result buckets, long count, Placement utc) {
    if (request.format().equals("arrow")) return count * 9; // int64 plus rounded-up validity
    long bytes = 0;
    for (int i = 0; i < count; i++) {
      long logUs = buckets == null ? values.get(i).timestampUs() : buckets.startUs(i);
      // Four-digit years need 28 bytes including the comma, counted without formatting each
      // row's text; extended years need more.
      var epoch = utc.at(logUs).epochMicros();
      int cellBytes = epoch == null || epoch >= FOUR_DIGIT_YEARS_FROM && epoch <= FOUR_DIGIT_YEARS_TO
          ? 28 : Math.max(28, utcText(epoch).length() + 1);
      long rows = 1;
      if (request.maxPoints() == null) {
        var value = values.get(i).value();
        rows = value instanceof List<?> list ? list.size()
            : value != null && value.getClass().isArray() && !(value instanceof byte[])
                ? java.lang.reflect.Array.getLength(value) : 1;
      }
      bytes += rows * cellBytes;
    }
    return bytes;
  }

  /** A UTC window's bucket edges are the window's own, in the log's seconds; a whole read's are the data's. */
  private static Double bucketStart(Request request, long[] logWindow) {
    if (!request.utcWindow()) return request.startTime();
    return logWindow == null ? null : logWindow[0] / 1e6;
  }

  private static Double bucketEnd(Request request, long[] logWindow) {
    if (!request.utcWindow()) return request.endTime();
    return logWindow == null || logWindow[1] == Long.MAX_VALUE ? null : logWindow[1] / 1e6;
  }

  /**
   * A sample from before the clock's first reading has no UTC and lies in no UTC window; a
   * dashboard's response says how many of the entry's samples that leaves out, and why.
   */
  private static void unplaced(JsonObject basis, UtcTimeMapping utc, EntryData.Series series) {
    long first = utc.firstLogMicros();
    long count;
    if (series.samples() != null) {
      var samples = series.samples();
      count = org.triplehelix.wpilogmcp.read.TimeWindows.bound(samples.size(), i -> samples.timeUs(i) < first);
    } else {
      count = series.values().stream().filter(tv -> tv.timestampUs() < first).count();
    }
    basis.addProperty("unplaced_sample_count", count);
    basis.addProperty("unplaced_reason", count == 0 ? null : "Before the first valid wall-clock reading");
  }

  private static JsonObject basisJson(UtcTimeMapping.Basis basis) {
    var out = new JsonObject();
    out.addProperty("entry", basis.entry());
    out.addProperty("clock", basis.clock());
    out.addProperty("reading_count", basis.readingCount());
    out.addProperty("first_offset_us", basis.firstOffsetUs());
    out.addProperty("last_offset_us", basis.lastOffsetUs());
    out.addProperty("max_offset_change_us", basis.maxOffsetChangeUs());
    out.addProperty("rounding", basis.rounding());
    return out;
  }

  /** Count emitted coordinates, not expanded CSV array rows, and retain a reason for each null. */
  private static JsonObject utcBasis(UtcTimeMapping.Basis basis, Placement utc, List<TimestampedValue> values,
      Buckets.Result buckets) {
    var out = basisJson(basis);
    var reasons = new LinkedHashMap<String, Long>();
    int count = buckets == null ? values.size() : buckets.buckets().size();
    for (int i = 0; i < count; i++) {
      var mapped = utc.at(buckets == null ? values.get(i).timestampUs() : buckets.startUs(i));
      if (mapped.epochMicros() == null) reasons.merge(mapped.reason(), 1L, Long::sum);
    }
    long nulls = 0;
    var rows = new JsonArray();
    for (var reason : reasons.entrySet()) {
      var row = new JsonObject();
      row.addProperty("reason", reason.getKey());
      row.addProperty("count", reason.getValue());
      rows.add(row);
      nulls += reason.getValue();
    }
    out.addProperty("null_sample_count", nulls);
    out.add("null_reasons", rows);
    return out;
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
      FileSnapshot snapshot, org.triplehelix.wpilogmcp.store.ReplicaReads.Facts replica, Recorded recorded) {
    var inputs = new JsonObject();
    inputs.addProperty("log", log.path());
    if (request.currentCapture()) inputs.addProperty("capture", "current");
    if (recorded != null) {
      inputs.add("files", recorded.files());
      inputs.add("skipped", recorded.skipped());
    }
    if (replica != null) inputs.add("replica", org.triplehelix.wpilogmcp.store.StoreJson.JSON.toJsonTree(replica));
    var entries = new JsonArray();
    for (var p : prepared) entries.add(p.series().name());
    inputs.add("entries", entries);
    var window = new JsonObject();
    if (request.startTime() != null) window.addProperty("start_time", request.startTime());
    if (request.endTime() != null) window.addProperty("end_time", request.endTime());
    if (request.startUtc() != null) window.addProperty("start_utc", utcText(request.startUtc()));
    if (request.endUtc() != null) window.addProperty("end_utc", utcText(request.endUtc()));
    inputs.add("window", window);
    if (request.maxPoints() != null) inputs.addProperty("max_points", request.maxPoints());
    if (request.utc()) inputs.addProperty("utc", true);
    if (request.angle() != null) inputs.addProperty("angle", request.angle());
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
      e.addProperty("sample_count", p.sampleCount());
      p.decoding().entrySet().forEach(field -> e.add(field.getKey(), field.getValue()));
      e.addProperty("total_in_range", p.values().size());
      e.addProperty("bucketed", p.buckets() != null);
      if (p.utcBasis() != null) e.add("utc_basis", p.utcBasis());
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
      FileSnapshot snapshot, org.triplehelix.wpilogmcp.store.ReplicaReads.Facts replica, Recorded recorded) {
    var metadata = new LinkedHashMap<String, String>();
    metadata.put("server", "wpilog-mcp");
    metadata.put("server_version", Version.VERSION);
    metadata.put("inputs", gson.toJson(inputs(log, request, prepared, snapshot, replica, recorded)));
    var range = new JsonObject();
    range.addProperty("start", recorded == null ? log.minTimestamp() : recorded.minSec());
    range.addProperty("end", recorded == null ? log.maxTimestamp() : recorded.maxSec());
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
      FileSnapshot before, org.triplehelix.wpilogmcp.store.ReplicaReads.Facts replica,
      Placement utc, Recorded recorded) throws IOException {
    boolean bucketed = request.maxPoints() != null;
    var fields = new ArrayList<>(bucketed ? BUCKET_FIELDS : List.of(
        new Field("timestamp", new ArrowType.TimestampMicros(), false),
        new Field("value", prepared.get(0).valueType(), true)));
    if (utc != null) fields.add(1, new Field("timestamp_utc", new ArrowType.TimestampMicros("UTC"), true));
    int offset = utc == null ? 0 : 1;
    var writer = new ArrowStreamWriter(out, fields);
    writer.writeSchema(schemaMetadata(log, request, prepared, before, replica, recorded));
    var columns = new ArrayList<ColumnBuilder>();
    for (var f : fields) columns.add(ColumnBuilder.of(f.type()));
    for (var p : prepared) {
      var tag = Map.of("entry", p.series().name());
      if (bucketed) {
        if (p.buckets() != null) {
          for (int i = 0; i < p.buckets().buckets().size(); i++) {
            var b = p.buckets().buckets().get(i);
            appendTime(columns, p.buckets().startUs(i), utc);
            columns.get(1 + offset).append((long) b.count());
            columns.get(2 + offset).append(b.min());
            columns.get(3 + offset).append(b.max());
            columns.get(4 + offset).append(b.mean());
            columns.get(5 + offset).append(b.first());
            columns.get(6 + offset).append(b.last());
            if (columns.get(0).length() >= BATCH_ROWS) writer.writeBatch(columns, tag);
          }
        } else {
          // Few enough samples: each is its own bucket of one, exact
          for (var tv : p.values()) {
            double v = ((Number) tv.value()).doubleValue();
            appendTime(columns, tv.timestampUs(), utc);
            columns.get(1 + offset).append(1L);
            Double finite = Double.isFinite(v) ? v : null;
            columns.get(2 + offset).append(finite);
            columns.get(3 + offset).append(finite);
            columns.get(4 + offset).append(finite);
            columns.get(5 + offset).append(v);
            columns.get(6 + offset).append(v);
            if (columns.get(0).length() >= BATCH_ROWS) writer.writeBatch(columns, tag);
          }
        }
      } else {
        for (var tv : p.values()) {
          appendTime(columns, tv.timestampUs(), utc);
          columns.get(1 + offset).append(tv.value());
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

  private static void appendTime(List<ColumnBuilder> columns, long logUs, Placement utc) {
    columns.get(0).append(logUs);
    if (utc != null) columns.get(1).append(utc.at(logUs).epochMicros());
  }

  /** Seconds to the log's microseconds, as the file stores them. */
  static long micros(double seconds) {
    return Math.round(seconds * 1e6);
  }

  // ---- CSV ----

  private void writeCsv(OutputStream out, LogData log, Request request, List<Prepared> prepared,
      FileSnapshot before, org.triplehelix.wpilogmcp.store.ReplicaReads.Facts replica,
      Placement utc, Recorded recorded) throws IOException {
    Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
    w.write("# wpilog-mcp " + Version.VERSION + "\n");
    w.write("# inputs: " + gson.toJson(inputs(log, request, prepared, before, replica, recorded)) + "\n");
    w.write("# entries: " + gson.toJson(entriesMetadata(prepared)) + "\n");
    boolean first = true;
    for (var p : prepared) {
      if (!first) w.write("\n");
      first = false;
      w.write("# entry: " + p.series().name() + "\n");
      if (p.buckets() != null) {
        w.write("timestamp_sec," + (utc == null ? "" : "timestamp_utc,") + "count,min,max,mean,first,last\n");
        var row = new CsvRow(w);
        for (int i = 0; i < p.buckets().buckets().size(); i++) {
          var b = p.buckets().buckets().get(i);
          row.number(b.start()).comma().utc(utc, p.buckets().startUs(i)).number(b.count()).comma().cell(b.min()).comma()
              .cell(b.max()).comma().cell(b.mean()).comma().number(b.first()).comma().number(b.last()).end();
        }
      } else if (request.maxPoints() != null) {
        w.write("timestamp_sec," + (utc == null ? "" : "timestamp_utc,") + "count,min,max,mean,first,last\n");
        var row = new CsvRow(w);
        for (var tv : p.values()) {
          double v = ((Number) tv.value()).doubleValue();
          Double finite = Double.isFinite(v) ? v : null;
          row.number(tv.timestamp()).comma().utc(utc, tv.timestampUs()).number(1).comma().cell(finite).comma().cell(finite).comma()
              .cell(finite).comma().number(v).comma().number(v).end();
        }
      } else {
        var columns = EntryData.csvColumns(p.values());
        var outputColumns = new ArrayList<>(columns);
        if (utc != null) outputColumns.add(1, "timestamp_utc");
        w.write(String.join(",", outputColumns) + "\n");
        for (var tv : p.values()) {
          for (var row : EntryData.csvRows(tv, columns)) {
            // The legacy column order tells csvRows how to expand arrays. Insert UTC afterwards.
            var outputRow = new ArrayList<>(row);
            if (utc != null) outputRow.add(1, utcText(utc.at(tv.timestampUs()).epochMicros()));
            w.write(String.join(",", outputRow) + "\n");
          }
        }
      }
    }
    var change = logManager.changeDuringCall(request.path(), log, before);
    if (change != null) {
      w.write("\n# file_changed: " + change + "\n");
    }
    w.flush();
  }

  /**
   * One CSV row, built in a buffer the response keeps, so that writing a row makes no strings of
   * its own: a dashboard's refresh writes a thousand rows for each panel. Numbers are appended as
   * {@code String.valueOf} spells them, so the text is the same.
   */
  private static final class CsvRow {
    private final Writer out;
    private final StringBuilder row = new StringBuilder(256);
    private char[] chars = new char[256];

    CsvRow(Writer out) {
      this.out = out;
    }

    CsvRow number(double value) { row.append(value); return this; }
    CsvRow number(long value) { row.append(value); return this; }
    /** An empty cell for null. */
    CsvRow cell(Double value) {
      if (value != null) row.append(value.doubleValue());
      return this;
    }
    CsvRow comma() { row.append(','); return this; }

    /** The UTC cell and its comma when the response has the column; an empty cell where the clock places no time. */
    CsvRow utc(Placement utc, long logUs) {
      if (utc == null) return this;
      var epoch = utc.at(logUs).epochMicros();
      if (epoch != null) UTC_FORMAT.formatTo(instant(epoch), row);
      return comma();
    }

    /** A UTC cell from epoch microseconds, and its comma. */
    CsvRow epoch(long epochUs) {
      UTC_FORMAT.formatTo(instant(epochUs), row);
      return comma();
    }

    void end() throws IOException {
      row.append('\n');
      int length = row.length();
      if (chars.length < length) chars = new char[2 * length];
      row.getChars(0, length, chars, 0);
      out.write(chars, 0, length);
      row.setLength(0);
    }
  }

  private static Instant instant(long epochUs) {
    return Instant.ofEpochSecond(Math.floorDiv(epochUs, 1_000_000L), Math.floorMod(epochUs, 1_000_000L) * 1000);
  }

  private static long epochMicros(Instant instant) {
    return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1000);
  }

  private static String utcText(Long epochUs) {
    return epochUs == null ? "" : UTC_FORMAT.format(instant(epochUs));
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
