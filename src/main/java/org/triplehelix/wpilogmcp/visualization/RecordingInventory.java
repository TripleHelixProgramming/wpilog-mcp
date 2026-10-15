/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import org.triplehelix.wpilogmcp.log.LiveLog;
import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.ReadProblem;
import org.triplehelix.wpilogmcp.read.internal.ReadWire;
import org.triplehelix.wpilogmcp.read.server.ManagedReadSnapshot;
import org.triplehelix.wpilogmcp.read.server.OrdinalFollow;

/** Detached metadata from the follower's admitted members. Inspection owns no continuing lease,
 * telemetry cache, clock extrapolation or binding inference. Member schemas/counts remain
 * separate; a shared entry list excludes conflicting declarations instead of choosing a file. */
final class RecordingInventory {
  static final int MAX_BYTES = 4 << 20;
  private RecordingInventory() {}

  static JsonObject describe(OrdinalFollow.Prefix prefix) throws IOException {
    try { return bounded(prefix); }
    catch (ReadWire.WireLimit limit) { throw limit(limit); }
  }
  private static JsonObject bounded(OrdinalFollow.Prefix prefix) throws IOException {
    var out = new JsonObject(); var members = new JsonArray();
    var entries = new TreeMap<String, JsonObject>(); var conflicts = new TreeMap<String, JsonObject>();
    double start = Double.POSITIVE_INFINITY, end = Double.NEGATIVE_INFINITY;
    Double robotTime = null; int estimates = 0, bytes = 0;
    boolean truncated = false, damaged = false;
    for (var member : prefix.members()) {
      var snapshot = member.snapshot();
      var descriptor = ReadWire.descriptor("", snapshot);
      // Charge before retaining another member descriptor. Each individual descriptor also
      // uses the native wire preflight; the aggregate response gets the same cap below.
      bytes += encodedSize(descriptor, MAX_BYTES - bytes);
      var item = new JsonObject(); item.addProperty("member_id", member.id()); item.addProperty("content_sha256", member.contentSha256());
      item.add("snapshot", descriptor); members.add(item);
      for (var info : snapshot.entries().values()) {
        var entry = entries.get(info.name());
        if (conflicts.containsKey(info.name())) continue;
        if (entry != null && (!entry.get("type").getAsString().equals(info.type()) || !Objects.equals(ReadWire.nullableText(entry, "metadata"), info.metadata()))) {
          var conflict = new JsonObject(); conflict.addProperty("name", info.name());
          conflict.addProperty("reason", "Recording members declare different types or metadata; inspect the individual member inventories");
          conflicts.put(info.name(), conflict); entries.remove(info.name()); continue;
        }
        if (entry == null) {
          entry = new JsonObject(); entry.addProperty("name", info.name()); entry.addProperty("type", info.type()); entry.addProperty("metadata", info.metadata());
          entry.addProperty("sample_count", 0L); entry.add("member_ids", new JsonArray()); entries.put(info.name(), entry);
        }
        entry.addProperty("sample_count", Math.addExact(entry.get("sample_count").getAsLong(), snapshot.sampleCount(info.name())));
        entry.getAsJsonArray("member_ids").add(member.id());
      }
      truncated |= snapshot.truncated(); damaged |= snapshot.damaged();
      if (!(snapshot instanceof ManagedReadSnapshot managed)) throw new IOException("SOURCE_UNAVAILABLE: recording inspection needs the server's managed prefix");
      var log = managed.log();
      if (log.entries().keySet().stream().anyMatch(name -> log.sampleCount(name) > 0)) {
        if (Double.isFinite(log.minTimestamp())) start = Math.min(start, log.minTimestamp());
        if (Double.isFinite(log.maxTimestamp())) end = Math.max(end, log.maxTimestamp());
      }
      if (!prefix.finalized() && log instanceof LiveLog.View live && live.robotTimeEstimateSec().isPresent()) {
        robotTime = live.robotTimeEstimateSec().getAsDouble(); estimates++;
      }
    }
    // Multiple simultaneous estimates cannot be selected by member order. A later display
    // policy may hold a previously trusted edge; a stateless inspection reports absence.
    if (estimates != 1) robotTime = null;
    var clock = new JsonObject(); clock.addProperty("robot_time_sec", robotTime);
    finite(clock, "last_sample_time_sec", end); clock.addProperty("waiting_for_time_sync", !prefix.finalized() && robotTime == null);
    finite(clock, "right_edge_sec", robotTime == null ? end : robotTime);
    clock.addProperty("basis", robotTime != null ? "robot_estimate" : Double.isFinite(end) ? "last_sample" : prefix.finalized() ? "unavailable" : "waiting_for_time_sync");
    var list = new JsonArray(); entries.values().forEach(list::add); var problems = new JsonArray(); conflicts.values().forEach(problems::add);
    out.addProperty("source_kind", "session"); out.addProperty("recording_id", prefix.recordingId()); out.addProperty("finalized", prefix.finalized());
    out.add("members", members); out.add("entries", list); out.add("entry_conflicts", problems); out.add("clock", clock);
    finite(out, "start_time_sec", start); finite(out, "end_time_sec", end); finite(out, "duration_sec", end - start);
    out.addProperty("inventory_state", "available"); out.addProperty("label", "Recording " + prefix.recordingId());
    out.addProperty("clock_basis", "Recorded robot timestamps in seconds; clock.basis names the available right-edge evidence");
    out.addProperty("truncated", truncated); out.addProperty("damaged", damaged);
    encodedSize(out, MAX_BYTES); return out;
  }
  private static void finite(JsonObject object, String key, double value) {
    if (Double.isFinite(value)) object.addProperty(key, value); else object.add(key, JsonNull.INSTANCE);
  }
  private static int encodedSize(JsonObject object, int remaining) throws IOException {
    try { return ReadWire.bytes(object, remaining).length; }
    catch (ReadException | IllegalArgumentException limit) {
      throw limit(limit);
    }
  }
  private static ReadException limit(Exception cause) {
    return new ReadException(ReadProblem.Code.RESOURCE_LIMIT, "RESOURCE_LIMIT: recording inventory exceeds its 4 MiB response or native metadata preflight limit; inspect individual members through the native reader", cause);
  }
}
