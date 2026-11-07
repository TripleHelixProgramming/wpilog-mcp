/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.triplehelix.wpilogmcp.conformance.IndependentLog.Records;

/**
 * Replay's source and oracle use the differential reader, never the server or WPILib reader.
 * Only offsets stay on the heap; payloads are read one at a time, including for season-size logs.
 * Diagnostics contain counts and categories, never source values.
 */
final class ReplaySource implements AutoCloseable {
  static final class InvalidUtf8 extends IllegalArgumentException {}
  enum Kind { DATALOGMANAGER, ADVANTAGEKIT, OTHER }
  static final class Entry {
    final String name, type, metadata;
    private int[] offsets = new int[16];
    int count;
    long bytes;
    final List<String> metadataHistory = new ArrayList<>();
    Entry(Records.Start start) { name = start.name(); type = start.type(); metadata = start.metadata(); }
    void metadata(String text) {
      if (metadataHistory.isEmpty() || !metadataHistory.get(metadataHistory.size() - 1).equals(text)) metadataHistory.add(text);
    }
    void add(Records.Record record) {
      if (count == offsets.length) offsets = Arrays.copyOf(offsets, count * 2);
      offsets[count++] = record.offset(); bytes += record.end() - record.offset();
    }
    int offset(int index) { return offsets[index]; }
    boolean schema() { return type.equals("structschema") || type.equals("proto:FileDescriptorProto"); }
  }
  final Path path;
  final Records reader;
  final Map<String, Entry> entries = new LinkedHashMap<>();
  final Map<Integer, Entry> declarations = new HashMap<>();
  final Map<String, Long> limitations = new LinkedHashMap<>();
  private final Map<Entry, String> topics = new HashMap<>();
  final Kind kind;
  long records, bytes, minUs = Long.MAX_VALUE, maxUs = Long.MIN_VALUE;
  int metadataChanges, finishes, redeclaredMetadata, escapedNames;

  ReplaySource(Path path) throws IOException {
    this.path = path;
    reader = new Records(path);
    try {
      var active = new HashMap<Long, Entry>();
      for (int at = reader.first; ; ) {
        var record = reader.at(at); if (record == null) break; at = record.end();
        if (record.id() == 0) {
          if (reader.control(record) == 0) {
            var start = reader.start(record);
            if (start == null) { limitation("malformed_start"); break; }
            var entry = entries.computeIfAbsent(start.name(), ignored -> new Entry(start));
            if (!entry.type.equals(start.type())) limitation("name_redeclared_with_another_type");
            if (!entry.metadata.equals(start.metadata())) redeclaredMetadata++;
            entry.metadata(start.metadata());
            declarations.put(record.offset(), entry); active.put(start.id(), entry);
          } else if (reader.control(record) == 1) { active.remove(reader.controlId(record)); finishes++; }
          else if (reader.control(record) == 2) {
            metadataChanges++;
            var entry = active.get(reader.controlId(record));
            if (entry != null) entry.metadata(reader.metadata(record));
          }
        } else {
          var entry = active.get(record.id());
          if (entry == null) { reader.stopped = "undeclared_entry"; break; }
          entry.add(record); records++; bytes += record.end() - record.offset();
          minUs = Math.min(minUs, record.timestampUs()); maxUs = Math.max(maxUs, record.timestampUs());
        }
      }
      kind = reader.extraHeader().contains("AdvantageKit")
          || entries.keySet().stream().anyMatch(n -> n.startsWith("/RealOutputs/")) ? Kind.ADVANTAGEKIT
          : entries.keySet().stream().anyMatch(n -> n.startsWith("NT:")) ? Kind.DATALOGMANAGER : Kind.OTHER;
      var names = new HashMap<String, Integer>();
      entries.values().forEach(e -> names.merge(canonicalTopic(e), 1, Integer::sum));
      String namespace = "/__wpilog_replay__";
      while (occupied(names, namespace)) namespace += "_";
      int ordinal = 0;
      for (var entry : entries.values()) {
        String topic = canonicalTopic(entry);
        if (names.get(topic) > 1) { topic = namespace + "/" + ordinal; escapedNames++; }
        topics.put(entry, topic); ordinal++;
      }
      if (records == 0) { minUs = 0; maxUs = 0; }
    } catch (Throwable error) { reader.close(); throw error; }
  }

  private void limitation(String kind) { limitations.merge(kind, 1L, Long::sum); }

  private static boolean occupied(Map<String, Integer> names, String namespace) {
    return names.keySet().stream().anyMatch(n -> n.startsWith(namespace));
  }

  String topic(Entry entry) { return topics.get(entry); }

  private String canonicalTopic(Entry entry) {
    String name = entry.name.startsWith("NT:") ? entry.name.substring(3) : entry.name;
    if (entry.name.startsWith("NT:") || kind != Kind.ADVANTAGEKIT) return name;
    return "/AdvantageKit" + (name.startsWith("/") ? name : "/" + name);
  }

  String schemaAlias(Entry entry) {
    String topic = canonicalTopic(entry); int at = topic.indexOf("/.schema/");
    return entry.schema() && at >= 0 && !topic(entry).equals(topic.substring(at)) ? topic.substring(at) : null;
  }

  record Calendar(java.time.Instant start, String basis) {}

  /** Independently map the first data timestamp to the last consistent recorded calendar clock. */
  java.util.Optional<Calendar> calendar() {
    for (String name : List.of("systemTime", "NT:systemTime", "/SystemStats/EpochTimeMicros")) {
      var entry = entries.get(name); if (entry == null || !List.of("int64", "double", "float").contains(entry.type)) continue;
      Calendar result = null;
      for (int i = 0; i < entry.count; i++) {
        var record = reader.at(entry.offset(i));
        long epoch = ((Number) value(entry.type, reader.payload(record))).longValue();
        if (epoch > 1_420_070_400_000_000L) result = new Calendar(
            java.time.Instant.EPOCH.plusNanos(Math.multiplyExact(epoch - record.timestampUs() + minUs, 1000)),
            name.contains("EpochTimeMicros") ? "epoch_time_micros" : "system_time");
      }
      if (result != null) return java.util.Optional.of(result);
    }
    var date = java.util.regex.Pattern.compile("^FRC_(\\d{8}_\\d{6})(?:_|\\.)").matcher(path.getFileName().toString());
    if (date.find()) {
      try {
        var time = java.time.LocalDateTime.parse(date.group(1), java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss"));
        return java.util.Optional.of(new Calendar(time.toInstant(java.time.ZoneOffset.UTC), "datalogmanager_filename_assumed_utc"));
      } catch (java.time.DateTimeException invalid) { /* An unset filename gives no calendar evidence. */ }
    }
    return java.util.Optional.empty();
  }

  String serial() { return text("/SystemStats/SerialNumber").orElse("replay-robot"); }
  private java.util.Optional<String> text(String name) {
    String found = null;
    for (var entry : entries.values()) {
      if (!entry.type.equals("string") || !entry.name.toLowerCase(java.util.Locale.ROOT)
          .endsWith(name.toLowerCase(java.util.Locale.ROOT))) continue;
      for (int i = 0; i < entry.count; i++) {
        String value = ((String) value(entry.type, reader.payload(reader.at(entry.offset(i))))).strip();
        if (value.isBlank()) continue;
        if (found != null && !found.equals(value)) throw new IllegalArgumentException("Conflicting logged identities: " + path);
        found = value;
      }
    }
    return java.util.Optional.ofNullable(found);
  }

  static String ntType(String type) {
    return switch (type) { case "int64" -> "int"; case "int64[]" -> "int[]"; default -> type; };
  }

  long driverStationRecords() {
    var names = java.util.Set.of("DS:enabled", "DS:autonomous", "DS:test", "DS:estop",
        "/DriverStation/Enabled", "/DriverStation/Autonomous", "/DriverStation/Test", "/DriverStation/EmergencyStop",
        "/DriverStation/FMSAttached", "/DriverStation/DSAttached", "/DriverStation/EventName", "/DriverStation/MatchNumber",
        "/DriverStation/MatchType", "/FMSInfo/FMSControlData", "/FMSInfo/EventName", "/FMSInfo/MatchNumber", "/FMSInfo/MatchType");
    return entries.values().stream().filter(e -> names.contains(e.name.startsWith("NT:") ? e.name.substring(3) : e.name))
        .mapToLong(e -> e.count).sum();
  }

  String driverStationDigest(long shiftUs) throws Exception {
    var digest = java.security.MessageDigest.getInstance("SHA-256");
    var active = new HashMap<Long, Entry>(); int flags = 0, matchNumber = 0, matchType = 0;
    String eventName = "";
    for (int at = reader.first; ; ) {
      var record = reader.at(at); if (record == null) break; at = record.end();
      if (record.id() == 0) {
        if (reader.control(record) == 0) active.put(reader.controlId(record), declarations.get(record.offset()));
        else if (reader.control(record) == 1) active.remove(reader.controlId(record));
        continue;
      }
      var entry = active.get(record.id()); if (entry == null) break;
      String name = entry.name.startsWith("NT:") ? entry.name.substring(3) : entry.name;
      int bit = switch (name) {
        case "DS:enabled", "/DriverStation/Enabled" -> 1;
        case "DS:autonomous", "/DriverStation/Autonomous" -> 2;
        case "DS:test", "/DriverStation/Test" -> 4;
        case "DS:estop", "/DriverStation/EmergencyStop" -> 8;
        case "/DriverStation/FMSAttached" -> 16;
        case "/DriverStation/DSAttached" -> 32;
        case "/FMSInfo/FMSControlData" -> -1;
        case "/DriverStation/EventName", "/DriverStation/MatchNumber", "/DriverStation/MatchType",
            "/FMSInfo/EventName", "/FMSInfo/MatchNumber", "/FMSInfo/MatchType" -> 0;
        default -> -2;
      };
      if (bit == -2) continue;
      if (bit == -1) flags = ((Number) value(entry.type, reader.payload(record))).intValue() & 63;
      else if (bit > 0) flags = (Boolean) value(entry.type, reader.payload(record)) ? flags | bit : flags & ~bit;
      else switch (name) {
        case "/DriverStation/MatchNumber", "/FMSInfo/MatchNumber" -> matchNumber = ((Number) value(entry.type, reader.payload(record))).intValue();
        case "/DriverStation/MatchType", "/FMSInfo/MatchType" -> {
          int next = ((Number) value(entry.type, reader.payload(record))).intValue();
          if (next >= 0 && next <= 3) matchType = next;
        }
        default -> eventName = (String) value(entry.type, reader.payload(record));
      }
      digest.update(ByteBuffer.allocate(12).putLong(record.timestampUs() + shiftUs).putInt(flags).array());
      byte[] event = eventName.getBytes(StandardCharsets.UTF_8);
      digest.update(ByteBuffer.allocate(12).putInt(matchNumber).putInt(matchType).putInt(event.length).array());
      digest.update(event);
    }
    return java.util.HexFormat.of().formatHex(digest.digest());
  }

  /** Decoding from WPILOG's little-endian format is independent of the capture's encoder. */
  static Object value(String type, byte[] payload) {
    var bytes = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    return switch (type) {
      case "boolean" -> { require(payload.length == 1); yield bytes.get() != 0; }
      case "int64" -> { require(payload.length == 8); yield bytes.getLong(); }
      case "float" -> { require(payload.length == 4); yield bytes.getFloat(); }
      case "double" -> { require(payload.length == 8); yield bytes.getDouble(); }
      case "string", "json" -> utf8(payload);
      case "boolean[]", "int64[]", "float[]", "double[]" -> {
        int width = type.equals("boolean[]") ? 1 : type.equals("float[]") ? 4 : 8;
        require(payload.length % width == 0);
        var values = new ArrayList<Object>(payload.length / width);
        while (bytes.hasRemaining()) values.add(switch (type) {
          case "boolean[]" -> bytes.get() != 0;
          case "int64[]" -> bytes.getLong();
          case "float[]" -> bytes.getFloat();
          default -> bytes.getDouble();
        });
        yield values;
      }
      case "string[]" -> {
        require(bytes.remaining() >= 4); int count = bytes.getInt(); require(count >= 0 && count <= bytes.remaining() / 4);
        var values = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
          require(bytes.remaining() >= 4); int size = bytes.getInt(); require(size >= 0 && size <= bytes.remaining());
          byte[] text = new byte[size]; bytes.get(text); values.add(utf8(text));
        }
        require(!bytes.hasRemaining()); yield values;
      }
      default -> payload;
    };
  }

  private static String utf8(byte[] bytes) {
    String value = new String(bytes, StandardCharsets.UTF_8);
    if (!Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) throw new InvalidUtf8();
    return value;
  }
  private static void require(boolean valid) {
    if (!valid) throw new IllegalArgumentException("WPILOG payload has no lossless NT4 value");
  }
  @Override public void close() { reader.close(); }
}
