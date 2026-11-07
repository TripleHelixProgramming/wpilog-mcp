/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Scrape scope limits series without changing what the recorder subscribes to or stores. */
public record MetricsConfig(List<String> include, int maxArrayLength) {
  public static final MetricsConfig DEFAULT = new MetricsConfig(List.of(), 16);
  public static final Set<String> KEYS = Set.of("include", "max_array_length");
  public MetricsConfig {
    include = List.copyOf(include);
    if (include.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("metrics.include needs nonblank prefixes");
    if (maxArrayLength < 0) throw new IllegalArgumentException("metrics.max_array_length must be nonnegative");
  }
  public boolean includes(String topic) { return include.isEmpty() || include.stream().anyMatch(topic::startsWith); }
  public static MetricsConfig parse(JsonElement value) throws ConfigException {
    if (value == null || value.isJsonNull()) return null;
    if (!value.isJsonObject()) throw new ConfigException("metrics must be an object");
    var block = value.getAsJsonObject();
    for (var key : block.keySet()) if (!KEYS.contains(key)) throw new ConfigException("metrics." + key + ": unknown key");
    var prefixes = new ArrayList<String>();
    if (block.has("include")) {
      var list = block.get("include");
      if (!list.isJsonArray()) throw new ConfigException("metrics.include must be a list of nonblank prefixes");
      for (var item : list.getAsJsonArray()) {
        if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() || item.getAsString().isBlank()) {
          throw new ConfigException("metrics.include must be a list of nonblank prefixes");
        }
        prefixes.add(item.getAsString());
      }
    }
    int limit = DEFAULT.maxArrayLength();
    if (block.has("max_array_length")) {
      var n = block.get("max_array_length");
      try {
        if (!n.isJsonPrimitive() || !n.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
        limit = n.getAsBigDecimal().intValueExact();
        if (limit < 0) throw new IllegalArgumentException();
      } catch (IllegalArgumentException | ArithmeticException invalid) {
        throw new ConfigException("metrics.max_array_length must be an integer from 0 through 2147483647");
      }
    }
    return new MetricsConfig(prefixes, limit);
  }
}
