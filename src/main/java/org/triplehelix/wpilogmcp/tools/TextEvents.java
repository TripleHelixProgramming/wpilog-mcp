/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LogData;

/**
 * The text a log holds, as events: one source for {@code search_strings}, {@code get_ds_timeline},
 * {@code can_health}, and {@code generate_report} (review issues F1, F2).
 *
 * <ul>
 *   <li>{@code string} entries (console output, messages): each non-blank sample.
 *   <li>{@code string[]} entries (WPILib Alerts, e.g. {@code /RealOutputs/Alerts/warnings}): each
 *       element is state — an event from the record in which it appears to the record in which it
 *       is gone ({@code end}), or still present at the end of the log ({@code end} null). A robot
 *       program logs the whole array whenever any alert changes, so counting records would repeat
 *       every active alert.
 *   <li>{@code json} entries: the string values in each sample, one per line.
 * </ul>
 *
 * @since 0.9.0
 */
final class TextEvents {

  private TextEvents() {}

  /** Where an event's text came from. */
  enum Source {
    STRING, ALERT, JSON;

    String wire() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * One text event.
   *
   * @param entry The entry name
   * @param entryId The entry's id (declaration order, for stable sorting)
   * @param sampleIndex The index of the record the event starts in
   * @param timestamp When it was logged (for an alert, when it appeared)
   * @param text The text (for json, the string values, one per line)
   * @param source Where the text came from
   * @param end For an alert: when it was gone; null while still present at the end of the log,
   *     and for other sources
   */
  record Event(String entry, int entryId, int sampleIndex, double timestamp, String text,
      Source source, Double end) {

    /** For an alert, the time it was present; null otherwise or while still present. */
    Double duration() {
      return end == null ? null : end - timestamp;
    }

    /** Whether the event overlaps [start, end] (null bounds are open). */
    boolean overlaps(Double start, Double stop) {
      if (stop != null && timestamp > stop) return false;
      if (start == null) return true;
      if (source == Source.ALERT) return end == null || end > start;
      return timestamp >= start;
    }
  }

  /** Whether an entry type holds text this class reads. */
  static boolean isTextType(String type) {
    return "string".equals(type) || "string[]".equals(type) || "json".equals(type);
  }

  /** The text entries of a log, in declaration order. */
  static List<EntryInfo> textEntries(LogData log) {
    return log.entries().values().stream().filter(e -> isTextType(e.type()))
        .sorted(Comparator.comparingInt(EntryInfo::id)).toList();
  }

  /**
   * The level an alert entry's name gives its messages ({@code .../errors} → error,
   * {@code .../warnings} → warning, {@code .../infos} → info), or null.
   */
  static String alertLevel(String entryName) {
    var leaf = entryName.substring(entryName.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    return switch (leaf) {
      case "errors", "error" -> "error";
      case "warnings", "warning" -> "warning";
      case "infos", "info" -> "info";
      default -> null;
    };
  }

  /**
   * The level of an event: an alert's level from its entry name when it has one, otherwise the
   * text classified line by line ({@link ToolUtils#classifyText}); null when neither.
   */
  static String level(Event event) {
    if (event.source() == Source.ALERT) {
      var level = alertLevel(event.entry());
      if (level != null) return level;
    }
    var classified = ToolUtils.classifyText(event.text());
    return classified == null ? null : classified.type().toLowerCase(Locale.ROOT);
  }

  /** Every text event of every text entry, in time order (ties by entry id). */
  static List<Event> all(LogData log) {
    var out = new ArrayList<Event>();
    for (var info : textEntries(log)) out.addAll(of(log, info));
    out.sort(Comparator.comparingDouble(Event::timestamp).thenComparingInt(Event::entryId)
        .thenComparingInt(Event::sampleIndex));
    return out;
  }

  /** The text events of one entry, in time order. */
  static List<Event> of(LogData log, EntryInfo info) {
    var values = log.values().get(info.name());
    if (values == null || values.isEmpty()) return List.of();
    var out = new ArrayList<Event>();
    switch (info.type()) {
      case "string" -> {
        for (int i = 0; i < values.size(); i++) {
          if (values.get(i).value() instanceof String s && !s.isBlank()) {
            out.add(new Event(info.name(), info.id(), i, values.get(i).timestamp(), s,
                Source.STRING, null));
          }
        }
      }
      case "json" -> {
        for (int i = 0; i < values.size(); i++) {
          if (values.get(i).value() instanceof String s && !s.isBlank()) {
            var text = jsonText(s);
            if (!text.isBlank()) {
              out.add(new Event(info.name(), info.id(), i, values.get(i).timestamp(), text,
                  Source.JSON, null));
            }
          }
        }
      }
      case "string[]" -> alerts(info, values, out);
      default -> { }
    }
    return out;
  }

  /** Episodes of each distinct element of a string[] entry. */
  private static void alerts(EntryInfo info, List<org.triplehelix.wpilogmcp.log.TimestampedValue>
      values, List<Event> out) {
    // text -> [start timestamp, start record index]
    var active = new LinkedHashMap<String, double[]>();
    for (int i = 0; i < values.size(); i++) {
      double t = values.get(i).timestamp();
      Set<String> present = new LinkedHashSet<>();
      if (values.get(i).value() instanceof String[] array) {
        for (var s : array) {
          if (s != null && !s.isBlank()) present.add(s);
        }
      }
      for (var it = active.entrySet().iterator(); it.hasNext();) {
        var e = it.next();
        if (!present.contains(e.getKey())) {
          out.add(new Event(info.name(), info.id(), (int) e.getValue()[1], e.getValue()[0],
              e.getKey(), Source.ALERT, t));
          it.remove();
        }
      }
      for (var s : present) active.putIfAbsent(s, new double[] {t, i});
    }
    for (var e : active.entrySet()) {
      out.add(new Event(info.name(), info.id(), (int) e.getValue()[1], e.getValue()[0],
          e.getKey(), Source.ALERT, null));
    }
    out.sort(Comparator.comparingDouble(Event::timestamp).thenComparingInt(Event::sampleIndex));
  }

  /** The string values of a JSON document, one per line; the raw text when it does not parse. */
  static String jsonText(String json) {
    try {
      var lines = new ArrayList<String>();
      collectStrings(JsonParser.parseString(json), lines);
      return String.join("\n", lines);
    } catch (RuntimeException e) {
      return json;
    }
  }

  private static void collectStrings(JsonElement e, List<String> out) {
    if (e == null || e.isJsonNull()) return;
    if (e.isJsonPrimitive()) {
      if (e.getAsJsonPrimitive().isString() && !e.getAsString().isBlank()) out.add(e.getAsString());
    } else if (e.isJsonArray()) {
      e.getAsJsonArray().forEach(item -> collectStrings(item, out));
    } else {
      e.getAsJsonObject().entrySet().forEach(entry -> collectStrings(entry.getValue(), out));
    }
  }
}
