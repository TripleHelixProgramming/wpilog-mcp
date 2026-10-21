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

/** Scope selects acquisitions only; neither scope changes nor capacity authorize deletion. */
public record MirrorConfig(String origin, Path folder, int days, long maxSizeBytes,
    List<String> robots, List<String> events, int intervalSec, long rateBytes,
    String scope, long metadataReserveBytes, List<String> peers, boolean discovery, String masterStoreId) {
  public static final Set<String> KEYS = Set.of("origin", "folder", "days", "max_size_gb", "robots", "events", "interval_sec", "rate_bytes", "scope", "metadata_reserve_bytes", "peers", "discovery", "master_store_id");
  public MirrorConfig(String origin, Path folder, int days, long maxSizeBytes,
      List<String> robots, List<String> events, int intervalSec, long rateBytes, String scope, long metadataReserveBytes,
      List<String> peers, boolean discovery) {
    this(origin, folder, days, maxSizeBytes, robots, events, intervalSec, rateBytes, scope, metadataReserveBytes, peers, discovery, null);
  }
  public MirrorConfig(String origin, Path folder, int days, long maxSizeBytes,
      List<String> robots, List<String> events, int intervalSec, long rateBytes, String scope, long metadataReserveBytes) {
    this(origin, folder, days, maxSizeBytes, robots, events, intervalSec, rateBytes, scope, metadataReserveBytes, List.of(), true);
  }
  public MirrorConfig(String origin, Path folder, int days, long maxSizeBytes,
      List<String> robots, List<String> events, int intervalSec, long rateBytes) {
    this(origin, folder, days, maxSizeBytes, robots, events, intervalSec, rateBytes, "filtered", 1_048_576);
  }
  public MirrorConfig {
    origin = new HttpRemoteFiles(origin).url();
    folder = folder.toAbsolutePath().normalize();
    robots = List.copyOf(robots); events = List.copyOf(events);
    peers = peers.stream().map(address -> new HttpRemoteFiles(address).url()).distinct().toList();
    if (masterStoreId != null && !java.util.UUID.fromString(masterStoreId).toString().equals(masterStoreId))
      throw new IllegalArgumentException("mirror.master_store_id must be a canonical UUID");
    if (peers.size() > 64) throw new IllegalArgumentException("mirror.peers accepts at most 64 endpoints");
    if (days < 0) throw new IllegalArgumentException("mirror.days must be nonnegative");
    if (maxSizeBytes <= 0) throw new IllegalArgumentException("mirror.max_size_gb must be positive");
    if (intervalSec <= 0) throw new IllegalArgumentException("mirror.interval_sec must be positive");
    if (rateBytes < 0) throw new IllegalArgumentException("mirror.rate_bytes must be nonnegative");
    if (!Set.of("all", "filtered").contains(scope)) throw new IllegalArgumentException("mirror.scope must be all or filtered");
    if (metadataReserveBytes < 4096 || metadataReserveBytes > 128L * 1024 * 1024)
      throw new IllegalArgumentException("mirror.metadata_reserve_bytes must be between 4096 and 134217728");
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
          integer(block.get("rate_bytes"), "rate_bytes", 0, Long.MAX_VALUE),
          block.has("scope") ? string(block.get("scope"), "scope") : "filtered",
          integer(block.get("metadata_reserve_bytes"), "metadata_reserve_bytes", 1_048_576, 134_217_728),
          strings(block.get("peers"), "peers"), bool(block.get("discovery"), true),
          block.has("master_store_id") ? string(block.get("master_store_id"), "master_store_id") : null);
    } catch (IllegalArgumentException e) { throw new ConfigException("Invalid mirror configuration: " + e.getMessage(), e); }
  }
  private static String string(JsonElement value, String key) throws ConfigException {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
      throw new ConfigException("mirror." + key + " must be a nonblank string");
    }
    return value.getAsString();
  }
  private static boolean bool(JsonElement value, boolean fallback) throws ConfigException {
    if (value == null) return fallback;
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new ConfigException("mirror.discovery must be boolean");
    return value.getAsBoolean();
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
