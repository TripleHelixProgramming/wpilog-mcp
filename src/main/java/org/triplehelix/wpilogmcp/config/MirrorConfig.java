/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import com.google.gson.JsonElement;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.triplehelix.wpilogmcp.sync.HttpRemoteFiles;

/** A mirror has one origin and an explicit retention policy; it never becomes an import target. */
public record MirrorConfig(String origin, Path folder, int days, long maxSizeBytes,
    List<String> robots, List<String> events, int intervalSec, long rateBytes) {
  public static final Set<String> KEYS = Set.of("origin", "folder", "days", "max_size_gb", "robots", "events", "interval_sec", "rate_bytes");
  public MirrorConfig {
    origin = new HttpRemoteFiles(origin).url();
    folder = folder.toAbsolutePath().normalize();
    robots = List.copyOf(robots); events = List.copyOf(events);
    if (days < 0) throw new IllegalArgumentException("mirror.days must be nonnegative");
    if (maxSizeBytes <= 0) throw new IllegalArgumentException("mirror.max_size_gb must be positive");
    if (intervalSec <= 0) throw new IllegalArgumentException("mirror.interval_sec must be positive");
    if (rateBytes < 0) throw new IllegalArgumentException("mirror.rate_bytes must be nonnegative");
  }

  public static MirrorConfig parse(JsonElement value, UnaryOperator<String> expand) throws ConfigException {
    if (value == null || value.isJsonNull()) return null;
    if (!value.isJsonObject()) throw new ConfigException("mirror must be an object");
    var block = value.getAsJsonObject();
    for (var key : block.keySet()) if (!KEYS.contains(key)) throw new ConfigException("mirror." + key + ": unknown key");
    try {
      String url = string(block.get("origin"), "origin");
      try { url = new HttpRemoteFiles(url).url(); }
      catch (IllegalArgumentException e) { throw new ConfigException("mirror.origin: " + e.getMessage()); }
      Path folder;
      try { folder = Path.of(expand.apply(string(block.get("folder"), "folder"))); }
      catch (java.nio.file.InvalidPathException e) { throw new ConfigException("mirror.folder: invalid path"); }
      double gb = number(block.get("max_size_gb"), "max_size_gb", 20, false);
      if (gb <= 0 || gb * 1_000_000_000 >= Long.MAX_VALUE) throw new ConfigException("mirror.max_size_gb must be positive and representable");
      return new MirrorConfig(url, folder, (int) integer(block.get("days"), "days", 14, Integer.MAX_VALUE),
          (long) (gb * 1_000_000_000), strings(block.get("robots"), "robots"), strings(block.get("events"), "events"),
          (int) integer(block.get("interval_sec"), "interval_sec", 30, Integer.MAX_VALUE),
          integer(block.get("rate_bytes"), "rate_bytes", 0, Long.MAX_VALUE));
    } catch (IllegalArgumentException e) { throw new ConfigException("Invalid mirror configuration: " + e.getMessage(), e); }
  }
  private static String string(JsonElement value, String key) throws ConfigException {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
      throw new ConfigException("mirror." + key + " must be a nonblank string");
    }
    return value.getAsString();
  }
  private static List<String> strings(JsonElement value, String key) throws ConfigException {
    if (value == null) return List.of();
    if (!value.isJsonArray()) throw new ConfigException("mirror." + key + " must be a list");
    var result = new ArrayList<String>();
    for (var item : value.getAsJsonArray()) result.add(string(item, key));
    return List.copyOf(result);
  }
  private static double number(JsonElement value, String key, double fallback, boolean integral) throws ConfigException {
    if (value == null) return fallback;
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ConfigException("mirror." + key + " must be a number");
    double n = value.getAsDouble();
    if (!Double.isFinite(n) || n < 0 || integral && n != Math.rint(n)) throw new ConfigException("mirror." + key + " must be a finite nonnegative " + (integral ? "integer" : "number"));
    return n;
  }
  private static long integer(JsonElement value, String key, long fallback, long max) throws ConfigException {
    double n = number(value, key, fallback, true);
    if (n >= max && (max == Long.MAX_VALUE || n > max)) throw new ConfigException("mirror." + key + " is too large");
    return (long) n;
  }
}
