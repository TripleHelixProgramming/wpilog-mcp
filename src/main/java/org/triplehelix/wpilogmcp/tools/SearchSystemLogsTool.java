/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.LogFileAccess;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;
import org.triplehelix.wpilogmcp.store.StoreManifest;
import org.triplehelix.wpilogmcp.store.SystemLogFiles;
import static org.triplehelix.wpilogmcp.tools.ToolUtils.*;

/** A query reads only durable local receipts. It cannot turn an assistant call into robot I/O. */
public final class SearchSystemLogsTool extends LogRequiringTool {
  @Override public String name() { return "search_system_logs"; }
  @Override public String description() {
    return "Search pulled system files from session.json and the robot's system/index.json, never the network. path is the session's capture. "
        + "Shared syslog spans must overlap the session or be unknown; legacy session receipts remain readable. "
        + "source selects kernel (dmesg only), syslog (files or the whole journal), program (NI logs), jvm_crash, or all. "
        + "Returns matches with file, source, line_number, text, level, original_timestamp, timestamp_sec, timestamp_basis "
        + "(uptime_pairing or system_time), and timestamp_reason when unmapped. Kernel clocks interpolate the nearest "
        + "measured kernel uptime/FPGA pairs without extrapolation; wall clocks use recorded systemTime. Unmapped lines have null "
        + "timestamp_sec and remain visible even in a time window, with their reason. Severity follows search_strings. "
        + "total_matches, offset, limit, returned, has_more and limits describe paging; inputs names files and clock_files read. "
        + "The pulled file is the exact record; a /Daemon/Tail entry is the timely copy stamped at receipt. On journald images "
        + "a kernel message may occur twice: dmesg as kernel with uptime_pairing, and the journal as syslog with system_time. "
        + "Logged lines are facts, so no statistical quality score is attached. Regex is case-insensitive and bounded to one second per line. "
        + "No companions or files not yet copied locally gives not_applicable, naming the collecting server for missing copies; no matching lines gives no_match.";
  }
  @Override protected JsonObject toolSchema() {
    return new SchemaBuilder()
        .addProperty("source", "string", "kernel, syslog, program, jvm_crash, or all (default)", false)
        .addProperty("pattern", "string", "Case-insensitive substring; omit to list all lines", false)
        .addProperty("regex", "boolean", "Interpret pattern as a Java regular expression (default false)", false)
        .addProperty("level", "string", "error, warning, info (alerts only), or any (default), as search_strings", false)
        .addNumberProperty("start_time", "Inclusive robot-clock start in seconds", false, null)
        .addNumberProperty("end_time", "Inclusive robot-clock end in seconds", false, null)
        .addProperty("scope", "string", TimeScope.SCOPE_DESCRIPTION, false)
        .addArrayProperty("windows", TimeScope.WINDOWS_DESCRIPTION, TimeScope.windowItemSchema(), false)
        .addIntegerProperty("offset", "Number of matching lines to skip (default 0)", false, 0)
        .addIntegerProperty("limit", "Maximum lines returned (default 100, maximum 1000)", false, 100).build();
  }

  @Override protected JsonElement executeWithLog(LogData log, JsonObject args) throws Exception {
    try { return search(log, args); }
    catch (IOException e) { return ResponseBuilder.error(e.getMessage()).build(); }
  }
  private JsonElement search(LogData log, JsonObject args) throws Exception {
    String source = getOptString(args, "source", "all"), level = getOptString(args, "level", "any").toLowerCase(Locale.ROOT);
    if (!List.of("all", "kernel", "syslog", "program", "jvm_crash").contains(source)) throw new IllegalArgumentException("source must be kernel, syslog, program, jvm_crash, or all");
    if (!List.of("any", "error", "warning", "info").contains(level)) throw new IllegalArgumentException("level must be error, warning, info, or any");
    int offset = getOptInt(args, "offset", 0), limit = getOptInt(args, "limit", 100);
    if (offset < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("offset must be nonnegative and limit must be 1..1000");
    String pattern = getOptString(args, "pattern", null); boolean regex = getOptBoolean(args, "regex");
    java.util.regex.Pattern compiled = null;
    if (regex && pattern != null) {
      try { compiled = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE | java.util.regex.Pattern.MULTILINE); }
      catch (java.util.regex.PatternSyntaxException e) { throw new IllegalArgumentException("Invalid regex: " + e.getDescription()); }
    }
    var scope = TimeScope.fromArguments(log, null, args);
    var found = logManager.stores().systemLogs(Path.of(log.path()));
    if (found.isEmpty() || found.get().files().isEmpty()) return ResponseBuilder.notApplicable("No pulled system files are recorded for this session.")
        .hint("Enable capture.pull.system on the pit server and let a disabled pull pass complete; search_strings reads the timely tail entries.").build();
    var snapshot = found.get();
    var missing = snapshot.files().stream().filter(r -> source.equals("all") || source.equals(r.file().source()))
        .filter(r -> !Files.isRegularFile(r.path())).toList();
    if (!missing.isEmpty()) return ResponseBuilder.notApplicable("System text has not been copied locally; query the collecting server " + snapshot.collectingServer() + ".")
        .hint("Synchronize these files before searching this copy: " + missing.stream().map(r -> r.path().toString()).toList()).build();
    var clocksRead = new JsonArray();
    var clocks = clocks(log, snapshot, clocksRead); var filesRead = new JsonArray(); var matches = new JsonArray(); long total = 0;
    for (var receipt : snapshot.files().stream().sorted(java.util.Comparator.comparing(r -> r.path().toString())).toList()) {
      var file = receipt.file(); if (!source.equals("all") && !source.equals(file.source())) continue;
      // Hold the same move lease as the log reader; consume exactly the committed prefix.
      try (var lease = LogFileAccess.read(receipt.path()); var input = Files.newInputStream(receipt.path())) {
        if (Files.size(receipt.path()) < file.sizeBytes()) throw new IOException("System file is shorter than its receipt in " + snapshot.manifest() + ": " + receipt.path());
        var detail = new JsonObject(); detail.addProperty("path", receipt.path().toString()); detail.addProperty("bytes", file.sizeBytes()); detail.addProperty("sha256", file.sha256()); filesRead.add(detail);
        var prefix = new Prefix(input, file.sizeBytes());
        java.io.InputStream decoded = file.format().equals("gzip_text") ? new java.util.zip.GZIPInputStream(prefix) : prefix;
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(decoded, java.nio.charset.StandardCharsets.UTF_8.newDecoder()))) {
          String line; long number = 0;
          while ((line = reader.readLine()) != null) {
            number++;
            var classified = classifyText(line); String severity = classified == null ? null : classified.type().toLowerCase(Locale.ROOT);
            if (!level.equals("any") && !level.equals(severity)) continue;
            if (compiled != null && !compiled.matcher(new QueryTools.SearchStringsTool.DeadlineCharSequence(line, System.nanoTime() + 1_000_000_000L)).find()) continue;
            if (!regex && pattern != null && !line.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT))) continue;
            var time = clocks.map(line, file.format());
            if (time.seconds() != null && !scope.contains(time.seconds())) continue;
            if (total++ < offset || matches.size() == limit) continue;
            var row = new JsonObject(); row.addProperty("file", receipt.path().toString()); row.addProperty("source", file.source()); row.addProperty("line_number", number);
            row.addProperty("text", line); row.addProperty("level", severity); row.addProperty("original_timestamp", time.written());
            row.addProperty("timestamp_sec", time.seconds()); row.addProperty("timestamp_basis", time.basis()); row.addProperty("timestamp_reason", time.reason()); matches.add(row);
          }
        }
      } catch (IOException e) { throw new IOException("Cannot read system file listed in " + snapshot.manifest() + ": " + receipt.path() + ": " + e.getMessage(), e); }
    }
    var builder = total == 0 ? ResponseBuilder.noMatch("No system-log line matches the requested filters.").hint("Use source all or omit pattern and level; unmapped timestamps remain visible.") : success();
    var result = builder.addInputScope(scope).addProperty("total_matches", total).addProperty("offset", offset).addProperty("limit", limit)
        .addProperty("returned", matches.size()).addProperty("has_more", total > (long) offset + matches.size()).addLimitedList("matches", matches, total, limit).build();
    if (!result.has("inputs")) result.add("inputs", new JsonObject());
    var inputs = result.getAsJsonObject("inputs"); inputs.add("files", filesRead); inputs.add("clock_files", clocksRead);
    inputs.addProperty("manifest", snapshot.manifest().toString()); return result;
  }
  private SystemLogClocks clocks(LogData log, SystemLogFiles.Snapshot snapshot, JsonArray read) throws IOException {
    var session = snapshot.session(); double min = log.minTimestamp(), max = log.maxTimestamp();
    for (var f : session.files()) { min = Math.min(min, f.minTimestampSec()); max = Math.max(max, f.maxTimestampSec()); }
    if (session.openCapture() != null) { min = Math.min(min, session.openCapture().minTimestampSec()); max = Math.max(max, session.openCapture().maxTimestampSec()); }
    var result = new SystemLogClocks(min, max);
    // Prefer the robot's systemTime when a pulled log supplies it, respecting its recorded offset.
    for (Path path : snapshot.clocks()) {
      StoreManifest.LogFile receipt = session.files().stream().filter(f -> snapshot.manifest().getParent().resolve(f.path()).equals(path)).findFirst().orElse(null);
      if (receipt == null || !receipt.provenance().kind().equals("pulled")) continue;
      try (var use = logManager.acquire(path.toString())) {
        var matching = receipt.matching();
        java.util.function.DoubleUnaryOperator shift = matching == null ? t -> t : t -> t + matching.offsetMicros() / 1e6
            + (matching.referenceTimeSec() == 0 ? 0 : (t - matching.referenceTimeSec()) * matching.driftRateNanosPerSec() / 1e9);
        result.add(use.log(), shift, true); read.add(path.toString());
      }
    }
    boolean wallFallback = !result.hasWall();
    for (Path path : snapshot.clocks()) {
      if (session.files().stream().anyMatch(f -> snapshot.manifest().getParent().resolve(f.path()).equals(path) && f.provenance().kind().equals("pulled"))) continue;
      if (path.toString().equals(log.path())) result.add(log, t -> t, wallFallback);
      else try (var use = logManager.acquire(path.toString())) { result.add(use.log(), t -> t, wallFallback); }
      read.add(path.toString());
    }
    result.sort(); return result;
  }
  private static final class Prefix extends java.io.FilterInputStream {
    private long remaining;
    Prefix(java.io.InputStream input, long remaining) { super(input); this.remaining = remaining; }
    @Override public int read() throws IOException { if (remaining == 0) return -1; int n = super.read(); if (n >= 0) remaining--; return n; }
    @Override public int read(byte[] bytes, int offset, int count) throws IOException {
      if (remaining == 0) return -1;
      int n = in.read(bytes, offset, (int) Math.min(count, remaining)); if (n > 0) remaining -= n; return n;
    }
  }
}
