/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.triplehelix.wpilogmcp.capture.pull.SystemLogTime;

/** Cross-boot syslog has one receipt per robot. Its written calendar selects sessions at read
 * time; recording a rotation must not rewrite a season's historical session manifests. */
public record SystemLogIndex(List<Entry> files) {
  public static final SystemLogIndex EMPTY = new SystemLogIndex(List.of());
  public SystemLogIndex { files = files == null ? List.of() : List.copyOf(files); }
  public record Span(double startEpochSec, double endEpochSec) {}
  public record Entry(SystemLogState.File file, Span writtenSpan) {}
  static Path path(Path robot) { return robot.resolve("system/index.json"); }
  static SystemLogIndex read(StoreFiles io, Path robot) throws IOException {
    var path = io.check(path(robot));
    var index = Files.exists(path) ? io.read(path, SystemLogIndex.class) : EMPTY;
    for (var entry : index.files()) validate(entry);
    return index;
  }
  static void validate(Entry entry) throws IOException {
    if (entry == null || entry.file() == null || entry.file().location() != SystemLogState.Location.STORE
        || !"syslog".equals(entry.file().source())) throw new IOException("Invalid shared system-log index receipt");
    SystemLogFiles.validate(entry.file());
    var span = entry.writtenSpan();
    if (span != null && (!Double.isFinite(span.startEpochSec()) || !Double.isFinite(span.endEpochSec())
        || span.startEpochSec() > span.endEpochSec())) throw new IOException("Invalid system-log written span");
  }
  static SystemLogIndex put(StoreFiles io, Path robot, Entry incoming) throws IOException {
    validate(incoming); var old = read(io, robot); var files = new ArrayList<>(old.files());
    // An idle pass's observation time is not a new content or provenance fact.
    for (var entry : files) {
      var a = entry.file(); var b = incoming.file();
      if (a.path().equals(b.path()) && a.sha256().equals(b.sha256()) && a.sizeBytes() == b.sizeBytes()
          && a.source().equals(b.source()) && a.format().equals(b.format())
          && java.util.Objects.equals(a.provenance().originalPath(), b.provenance().originalPath())
          && a.provenance().copiedFrom().equals(b.provenance().copiedFrom())
          && java.util.Objects.equals(a.note(), b.note()) && java.util.Objects.equals(entry.writtenSpan(), incoming.writtenSpan())) return old;
    }
    // Identical bytes at a rotated name remain one receipt; provenance retains the latest name.
    files.removeIf(e -> e.file().path().equals(incoming.file().path()) || e.file().sha256().equals(incoming.file().sha256()));
    files.add(incoming); var next = new SystemLogIndex(files);
    if (!next.equals(old)) io.write(path(robot), next);
    return next;
  }
  public static boolean applies(Entry entry, StoreManifest.Session session) {
    var span = entry.writtenSpan(); if (span == null || "unknown".equals(session.startBasis())) return true;
    try {
      double start = epoch(session.startedAt()), end = epoch(session.endedAt());
      return span.startEpochSec() <= end && span.endEpochSec() >= start;
    } catch (RuntimeException e) { return true; } // Unknown session clock cannot exclude written text.
  }
  private static double epoch(String text) { var i = Instant.parse(text); return i.getEpochSecond() + i.getNano() / 1e9; }
  static Span span(Path path, String format) throws IOException {
    double start = Double.POSITIVE_INFINITY, end = Double.NEGATIVE_INFINITY;
    try (var raw = Files.newInputStream(path);
         var reader = new java.io.BufferedReader(new java.io.InputStreamReader(format.equals("gzip_text")
             ? new java.util.zip.GZIPInputStream(raw) : raw, java.nio.charset.StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        var stamp = SystemLogTime.epoch(line, format);
        if (stamp != null) { start = Math.min(start, stamp); end = Math.max(end, stamp); }
      }
    } catch (IOException unreadable) { return null; } // Keep exact bytes; the search explains unreadable text.
    return Double.isFinite(start) ? new Span(start, end) : null;
  }
}
