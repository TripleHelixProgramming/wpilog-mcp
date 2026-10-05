/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.log.LazyParsedLog;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogMetadata;
import org.triplehelix.wpilogmcp.log.ScopedLogReader;
import org.triplehelix.wpilogmcp.log.WallClock;
import org.triplehelix.wpilogmcp.revlog.RevLogParser;

/** Inspection is separate from placement: all readers close before any input is moved. */
record ImportInspection(Path path, String hash, long size, String kind, LogMetadata metadata,
    double min, double max, Instant start, Instant end, String startBasis, boolean truncated,
    FileSnapshot snapshot) {

  static ImportInspection read(Path path, RevLogParser revParser) throws IOException {
    var before = FileSnapshot.of(path);
    if (before == null) throw new IOException("File no longer exists: " + path);
    byte[] header;
    try (var input = Files.newInputStream(path)) {
      header = input.readNBytes(16);
    }
    String kind;
    LogMetadata metadata = null;
    double min;
    double max;
    Instant start = null;
    Instant end = null;
    String basis = null;
    boolean truncated = false;
    if (header.length >= 6 && new String(header, 0, 6, StandardCharsets.US_ASCII).equals("WPILOG")) {
      try (var reader = new ScopedLogReader(path);
           var log = new LazyParsedLog(path.toString(), reader.reader(), 32L * 1024 * 1024)) {
        // REV also writes WPILOG containers. Its CAN declarations distinguish them by content.
        kind = log.entries().values().stream().anyMatch(e -> e.type().equals("raw")
            && e.name().matches("CAN/\\d+(?:/Periodic Status \\d+)?")) ? "revlog" : "wpilog";
        min = log.minTimestamp();
        max = log.maxTimestamp();
        truncated = log.truncated();
        if (log.damaged()) throw new IOException("Damaged log cannot be verified: " + path.getFileName());
        if (kind.equals("wpilog")) {
          metadata = LogMetadata.read(log);
          if (metadata.serialNumber() != null) StoreFiles.component(metadata.serialNumber());
        }
        var clock = WallClock.first(log);
        if (clock.isPresent()) {
          var reading = clock.get();
          start = Instant.ofEpochSecond(Math.floorDiv(reading.epochMicros(), 1_000_000),
              Math.floorMod(reading.epochMicros(), 1_000_000) * 1000)
              .plusNanos(Math.round((min - reading.logTime()) * 1e9));
          basis = "logged:" + WallClock.entry(log).orElseThrow();
        } else if (kind.equals("wpilog")) {
          Long time = LogDirectory.getInstance().extractCreationTime(path.getFileName().toString());
          if (time != null) {
            start = Instant.ofEpochMilli(time);
            basis = "filename";
          } else {
            start = before.modified().toInstant().minusNanos(Math.round((max - min) * 1e9));
            basis = "modification_time";
          }
        }
      }
    } else if (nativeHeader(header, before.size())) {
      kind = "revlog";
      var rev = revParser.parse(path);
      if (rev.devices().isEmpty()) throw new IOException("No REV devices in " + path.getFileName());
      min = rev.minTimestamp();
      max = rev.maxTimestamp();
    } else {
      throw new IOException("Refused " + path.getFileName() + ": no WPILOG or REV record header");
    }
    if (kind.equals("revlog") && start == null) {
      var filename = path.getFileName().toString();
      if (filename.startsWith("REV_")) {
        start = WallClock.filenameTime(filename).map(t -> t.toInstant(ZoneOffset.UTC)).orElse(null);
        if (start != null) basis = "filename";
      }
    }
    if (start != null) end = start.plusNanos(Math.round((max - min) * 1e9));
    String hash = StoreFiles.hash(path);
    if (!before.sameAs(FileSnapshot.of(path))) throw new IOException("File changed during inspection: " + path);
    return new ImportInspection(path, hash, before.size(), kind, metadata, min, max,
        start, end, basis, truncated, before);
  }

  // Native REV has record headers, not a magic string. Check a complete firmware/status record
  // before invoking the permissive parser; a renamed text file is not a REV log.
  private static boolean nativeHeader(byte[] bytes, long size) {
    if (bytes.length < 3 || (bytes[0] & 0xf0) != 0) return false;
    int idBytes = (bytes[0] & 3) + 1;
    int sizeBytes = ((bytes[0] >>> 2) & 3) + 1;
    if (bytes.length < 1 + idBytes + sizeBytes) return false;
    var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    long id = 0;
    long length = 0;
    for (int i = 0; i < idBytes; i++) id |= (long) (buffer.get(1 + i) & 255) << (i * 8);
    for (int i = 0; i < sizeBytes; i++) length |= (long) (buffer.get(1 + idBytes + i) & 255) << (i * 8);
    return length > 0 && length <= size - 1 - idBytes - sizeBytes
        && ((id == 1 && length % 10 == 0) || (id == 2 && length % 16 == 0));
  }
}
