/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.fixtures;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Independent Prometheus 0.0.4 parser. Knows no production metric names or rendering helpers.
 * Requires grouped families, unique series, HELP/TYPE, legal escapes, and no sample timestamp. */
public final class PrometheusText {
  private PrometheusText() {}
  public record Key(String name, Map<String, String> labels) { public Key { labels = Map.copyOf(labels); } }
  public record Parsed(Map<Key, Double> samples, Map<String, String> types) {
    public double value(String name, Map<String, String> labels) {
      var key = new Key(name, labels); assertTrue(samples.containsKey(key), () -> "Missing metric " + name);
      return samples.get(key);
    }
  }
  private static final String NAME = "[a-zA-Z_:][a-zA-Z0-9_:]*";
  private static final Pattern SAMPLE = Pattern.compile("^(" + NAME + ")(?:\\{(.*)\\})? ([^ ]+)$");
  private static final Pattern NUMBER = Pattern.compile("(?:[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?|NaN|[+-]Inf)");
  public static Parsed parse(String text) {
    assertTrue(text.endsWith("\n"), "Missing final line feed");
    var samples = new HashMap<Key, Double>(); var types = new HashMap<String, String>();
    var help = new HashSet<String>(); var closed = new HashSet<String>(); String group = null;
    for (var line : text.split("\n")) {
      if (line.isEmpty()) continue;
      if (line.startsWith("# HELP ")) {
        var parts = line.split(" ", 4); assertEquals(4, parts.length); assertTrue(parts[2].matches(NAME));
        assertTrue(help.add(parts[2]), "Duplicate HELP"); unescape(parts[3], false); continue;
      }
      if (line.startsWith("# TYPE ")) {
        var parts = line.split(" "); assertEquals(4, parts.length); assertTrue(parts[2].matches(NAME));
        assertTrue(List.of("counter", "gauge", "untyped").contains(parts[3]));
        assertNull(types.put(parts[2], parts[3]), "Duplicate TYPE"); continue;
      }
      assertFalse(line.startsWith("#"), "Unknown metadata in this endpoint");
      var match = SAMPLE.matcher(line); assertTrue(match.matches(), "Invalid sample grammar");
      String name = match.group(1); assertTrue(types.containsKey(name)); assertTrue(help.contains(name));
      if (!name.equals(group)) { if (group != null) closed.add(group); assertFalse(closed.contains(name)); group = name; }
      var labels = labels(match.group(2)); String number = match.group(3); assertTrue(NUMBER.matcher(number).matches(), "Invalid number or timestamp");
      double value = switch (number) { case "+Inf" -> Double.POSITIVE_INFINITY; case "-Inf" -> Double.NEGATIVE_INFINITY; default -> Double.parseDouble(number); };
      assertNull(samples.put(new Key(name, labels), value), "Duplicate series");
    }
    return new Parsed(Map.copyOf(samples), Map.copyOf(types));
  }
  private static Map<String, String> labels(String text) {
    if (text == null) return Map.of();
    var fields = new HashMap<String, String>(); int at = 0;
    while (at < text.length()) {
      int equal = text.indexOf('=', at); assertTrue(equal > at);
      String key = text.substring(at, equal); assertTrue(key.matches("[a-zA-Z_][a-zA-Z0-9_]*"));
      assertEquals('"', text.charAt(equal + 1)); int start = equal + 2; at = start;
      while (at < text.length() && text.charAt(at) != '"') { if (text.charAt(at) == '\\') at++; at++; }
      assertTrue(at < text.length(), "Unclosed label"); assertNull(fields.put(key, unescape(text.substring(start, at), true)));
      at++; if (at < text.length()) { assertEquals(',', text.charAt(at++)); assertTrue(at < text.length()); }
    }
    return fields;
  }
  private static String unescape(String text, boolean label) {
    var result = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\\') {
        assertTrue(++i < text.length()); c = text.charAt(i);
        assertTrue(c == '\\' || c == 'n' || label && c == '"', "Invalid escape");
        if (c == 'n') c = '\n';
      } else if (label) assertNotEquals('"', c);
      result.append(c);
    }
    return result.toString();
  }
}
