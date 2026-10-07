/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;

/** Reports only paths, counts and mismatch categories; assertions never print a robot value. */
final class ReplayAudit {
  private final Map<String, Long> failures = new LinkedHashMap<>();
  private long recorded, bytes, seeds;

  static Map<String, Object> gateway(ReplaySource source, Path output, long shiftUs, Clock wall) throws Exception {
    return gateway(source, output, shiftUs, wall, false);
  }

  static Map<String, Object> gateway(ReplaySource source, Path output, long shiftUs, Clock wall, boolean companions) throws Exception {
    var time = new AtomicLong(Math.max(1, source.minUs + shiftUs));
    var calendar = source.calendar();
    var captureClock = new ReplayClock(calendar.map(ReplaySource.Calendar::start).orElseGet(wall::instant), source.minUs + shiftUs);
    try (var remote = companions && calendar.isPresent() && ReplayPull.hasRevSiblings(source)
            ? new ReplayPull(source, output.resolveSibling(output.getFileName() + "-rio")) : null;
         var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), time::get)) {
      gateway.start().get(10, TimeUnit.SECONDS);
      var replay = new LogReplayer(source, gateway, time::set); replay.announce();
      try (var capture = new ReplayCapture(RobotAddress.uri("127.0.0.1", gateway.port(), "replay"), source.path, output,
          captureClock, remote == null ? null : remote.device, remote == null ? 64L << 20 : 1L << 30)) {
        capture.ready(replay.topicCount());
        replay.replay(shiftUs, 0, ignored -> { throw new AssertionError("Fast replay waited for source time"); }, capture::receivedThrough, capture::propertiesThrough);
        capture.stop();
        var result = new ReplayAudit().verify(source, capture, shiftUs);
        result.put("calendar_basis", calendar.map(ReplaySource.Calendar::basis).orElse("unavailable"));
        if (remote != null) {
          var pull = remote.verify(source, capture, captureClock, shiftUs); result.put("pull", pull);
          var mismatches = (Map<String, Long>) result.get("mismatches");
          if (pull.get("revlogs") instanceof java.util.List<?> buses) for (var bus : buses) {
            var facts = (Map<?, ?>) bus;
            for (String key : java.util.List.of("matches", "copy_equal", "http_visible", "verification_matches")) {
              if (!Boolean.TRUE.equals(facts.get(key))) mismatches.merge("rev_" + key, 1L, Long::sum);
            }
          }
        }
        return result;
      }
    }
  }

  private void check(boolean matches, String category) { if (!matches) failures.merge(category, 1L, Long::sum); }

  Map<String, Object> verify(ReplaySource source, ReplayCapture capture, long shiftUs) throws Exception {
    return verify(source, capture, shiftUs, capture.files, capture.writer.session());
  }

  Map<String, Object> verify(ReplaySource source, ReplayCapture capture, long shiftUs,
      java.util.List<Path> files, org.triplehelix.wpilogmcp.capture.CaptureWriter.Session session) throws Exception {
    var positions = new HashMap<String, Integer>();
    var costBytes = new HashMap<String, Long>(); var costRecords = new HashMap<String, Long>();
    var topics = new HashMap<String, ReplaySource.Entry>();
    var metadataHistory = new HashMap<String, java.util.List<String>>();
    source.entries.values().forEach(e -> topics.put("NT:" + source.topic(e), e));
    int fileNumber = 0;
    for (var file : files) {
      try (var log = new ReplaySource(file)) {
        var args = new JsonObject(); args.addProperty("path", file.toString());
        var listing = capture.http.call("list_entries", args);
        check(listing.has("entries"), "http_listing");
        if (listing.has("entries")) {
          check(listing.getAsJsonArray("entries").size() == log.entries.size(), "http_entry_count");
          for (var item : listing.getAsJsonArray("entries")) {
            var result = item.getAsJsonObject(); var raw = log.entries.get(result.get("name").getAsString());
            check(raw != null && raw.type.equals(result.get("type").getAsString()), "http_type");
            check(raw != null && raw.count == result.get("sample_count").getAsInt(), "http_record_count");
          }
          var range = listing.getAsJsonObject("time_range_sec");
          check(range.get("start").getAsDouble() == log.minUs / 1e6 && range.get("end").getAsDouble() == log.maxUs / 1e6, "http_range");
        }
        for (var entry : log.entries.values()) {
          if (entry.name.equals("/Daemon/Robot/Identity")) continue;
          costBytes.putIfAbsent(entry.name.substring(3), 0L);
          costRecords.putIfAbsent(entry.name.substring(3), 0L);
          var metadata = JsonParser.parseString(entry.metadata).getAsJsonObject();
          var props = metadata.getAsJsonObject("nt4_properties");
          boolean alias = props != null && props.has(LogReplayer.SCHEMA_ALIAS);
          var original = topics.get(entry.name);
          check(original != null || alias, "unexpected_entry");
          if (original != null) {
            check(entry.type.equals(original.type), "type");
            check(props != null && props.has(LogReplayer.ENTRY_NAME)
                && props.get(LogReplayer.ENTRY_NAME).getAsString().equals(original.name), "original_name");
            if (fileNumber == 0) check(props != null && props.has(LogReplayer.METADATA)
                && props.get(LogReplayer.METADATA).getAsString().equals(original.metadata), "metadata");
            var history = metadataHistory.computeIfAbsent(original.name, ignored -> new java.util.ArrayList<>());
            for (String text : entry.metadataHistory) {
              var envelope = JsonParser.parseString(text).getAsJsonObject().getAsJsonObject("nt4_properties");
              if (envelope == null || !envelope.has(LogReplayer.METADATA)) { check(false, "metadata_history"); continue; }
              String value = envelope.get(LogReplayer.METADATA).getAsString();
              if (history.isEmpty() || !history.get(history.size() - 1).equals(value)) history.add(value);
            }
          }
          for (int i = 0; i < entry.count; i++) {
            var record = log.reader.at(entry.offset(i));
            if (fileNumber > 0 && i == 0 && metadata.has("capture_schema_seed")) { seeds++; continue; }
            costBytes.merge(entry.name.substring(3), (long) (record.end() - record.offset()), Long::sum);
            costRecords.merge(entry.name.substring(3), 1L, Long::sum);
            if (original == null) continue;
            int position = positions.getOrDefault(original.name, 0);
            if (position >= original.count) { check(false, "extra_record"); continue; }
            var expected = source.reader.at(original.offset(position)); positions.put(original.name, position + 1);
            check(record.timestampUs() == Math.addExact(expected.timestampUs(), shiftUs), "timestamp");
            check(log.reader.samePayload(record, source.reader, expected), "payload");
            recorded++; bytes += record.end() - record.offset();
          }
        }
      }
      capture.manager.release(file); fileNumber++;
    }
    for (var entry : source.entries.values()) {
      check(positions.getOrDefault(entry.name, 0) == entry.count, "record_count");
      check(entry.metadataHistory.equals(metadataHistory.get(entry.name)), "metadata_history");
    }
    if (session != null) {
      check(session.closedCosts().size() == costBytes.size(), "cost_topics");
      session.closedCosts().forEach((name, cost) -> {
        check(cost.bytes() == costBytes.getOrDefault(name, 0L), "cost_bytes");
        check(cost.records() == costRecords.getOrDefault(name, 0L), "cost_records");
        check(cost.dropped() == 0 && cost.thinned() == 0, "cost_dropped");
      });
    }
    var report = new LinkedHashMap<String, Object>();
    report.put("path", source.path.toString()); report.put("kind", source.kind);
    report.put("entries", source.entries.size()); report.put("records", source.records); report.put("bytes", source.bytes);
    report.put("captured_records", recorded); report.put("captured_bytes", bytes); report.put("schema_seeds", seeds);
    report.put("accounted_records", costRecords.values().stream().mapToLong(Long::longValue).sum());
    report.put("accounted_bytes", costBytes.values().stream().mapToLong(Long::longValue).sum());
    report.put("files", files.size()); report.put("shift_us", shiftUs); report.put("mismatches", failures);
    report.put("incomplete_tail", source.reader.stopped);
    report.put("escaped_topic_names", source.escapedNames);
    return report;
  }
}
