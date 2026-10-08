/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;

/** Capture is opt-in, and misspelled nested keys must never silently turn recording policy off. */
public record CaptureConfig(List<URI> addresses, Path store, double periodSeconds,
    CapturePolicy policy, long hotWindowUs, long maxFileBytes, PullConfig pull, int gatewayPort) {
  public static final Set<String> KEYS = Set.of("robot", "store", "period_sec", "exclude", "thin", "hot_window_sec", "max_file_bytes", "pull", "gateway");
  public static final Set<String> GATEWAY_KEYS = Set.of("port");
  public static final Set<String> ROBOT_KEYS = Set.of("team", "usb", "host", "port");
  public CaptureConfig { addresses = List.copyOf(addresses); }
  public CaptureConfig(List<URI> addresses, Path store, double periodSeconds, CapturePolicy policy,
      long hotWindowUs, long maxFileBytes, PullConfig pull) {
    this(addresses, store, periodSeconds, policy, hotWindowUs, maxFileBytes, pull, 0);
  }
  public CaptureConfig(List<URI> addresses, Path store, double periodSeconds, CapturePolicy policy,
      long hotWindowUs, long maxFileBytes) {
    this(addresses, store, periodSeconds, policy, hotWindowUs, maxFileBytes, PullConfig.DISABLED);
  }
  public CaptureConfig(List<URI> addresses, Path store, double periodSeconds, CapturePolicy policy, long hotWindowUs) {
    this(addresses, store, periodSeconds, policy, hotWindowUs,
        org.triplehelix.wpilogmcp.capture.CaptureWriter.DEFAULT_MAX_FILE_BYTES);
  }

  static CaptureConfig parse(JsonElement value, UnaryOperator<String> expand) throws ConfigException {
    return parse(value, expand, UnaryOperator.identity());
  }

  static CaptureConfig parse(JsonElement value, UnaryOperator<String> expand, UnaryOperator<String> text) throws ConfigException {
    if (value == null || value.isJsonNull()) return null;
    try {
      var block = object(value, "capture"); keys(block, KEYS, "capture");
      var robot = object(block.get("robot"), "capture.robot"); keys(robot, ROBOT_KEYS, "capture.robot");
      if (List.of("team", "usb", "host").stream().filter(robot::has).count() != 1) {
        throw bad("capture.robot", "requires exactly one of team, usb, or host");
      }
      int port = robot.has("port") ? integer(robot.get("port"), "capture.robot.port", 1, 65535) : 5810;
      List<String> hosts;
      if (robot.has("team")) hosts = RobotAddress.team(integer(robot.get("team"), "capture.robot.team", 1, 25599));
      else if (robot.has("usb")) {
        var usb = robot.get("usb");
        if (!usb.isJsonPrimitive() || !usb.getAsJsonPrimitive().isBoolean() || !usb.getAsBoolean()) throw bad("capture.robot.usb", "must be true");
        hosts = RobotAddress.usb();
      } else hosts = List.of(string(robot.get("host"), "capture.robot.host"));
      var addresses = new ArrayList<URI>();
      for (var host : hosts) {
        try { addresses.add(RobotAddress.uri(host, port, "wpilog-pit")); }
        catch (IllegalArgumentException e) { throw bad("capture.robot.host", "invalid host"); }
      }
      Path store;
      try { store = Path.of(expand.apply(string(block.get("store"), "capture.store"))).toAbsolutePath().normalize(); }
      catch (java.nio.file.InvalidPathException e) { throw bad("capture.store", "invalid path"); }
      double period = block.has("period_sec") ? seconds(block.get("period_sec"), "capture.period_sec", false) : 0.01;
      long hot = block.has("hot_window_sec") ? micros(block.get("hot_window_sec"), "capture.hot_window_sec", true) : 600_000_000;
      var exclude = new ArrayList<String>();
      if (block.has("exclude")) {
        if (!block.get("exclude").isJsonArray()) throw bad("capture.exclude", "must be a list of prefixes");
        for (var item : block.getAsJsonArray("exclude")) exclude.add(prefix(item, "capture.exclude"));
      }
      var thin = new LinkedHashMap<String, Long>();
      if (block.has("thin")) for (var e : object(block.get("thin"), "capture.thin").entrySet()) {
        thin.put(e.getKey(), micros(e.getValue(), "capture.thin." + e.getKey(), false));
      }
      long max = block.has("max_file_bytes") ? integer(block.get("max_file_bytes"), "capture.max_file_bytes", 256, Integer.MAX_VALUE)
          : org.triplehelix.wpilogmcp.capture.CaptureWriter.DEFAULT_MAX_FILE_BYTES;
      int gatewayPort = 0;
      if (block.has("gateway")) {
        var gateway = object(block.get("gateway"), "capture.gateway"); keys(gateway, GATEWAY_KEYS, "capture.gateway");
        gatewayPort = gateway.has("port") ? integer(gateway.get("port"), "capture.gateway.port", 0, 65535) : 5810;
      }
      return new CaptureConfig(addresses, store, period, new CapturePolicy(exclude, thin), hot, max,
          PullConfig.parse(block.get("pull"), expand, text), gatewayPort);
    } catch (IllegalArgumentException e) { throw new ConfigException("Invalid capture configuration: " + e.getMessage(), e); }
  }
  private static JsonObject object(JsonElement value, String key) throws ConfigException {
    if (value == null || !value.isJsonObject()) throw bad(key, "must be an object");
    return value.getAsJsonObject();
  }
  private static void keys(JsonObject value, Set<String> allowed, String parent) throws ConfigException {
    for (var key : value.keySet()) if (!allowed.contains(key)) throw bad(parent + "." + key, "unknown key");
  }
  private static String prefix(JsonElement value, String key) throws ConfigException {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw bad(key, "must be a string");
    return value.getAsString();
  }
  private static String string(JsonElement value, String key) throws ConfigException {
    var text = prefix(value, key);
    if (text.isBlank()) throw bad(key, "must not be blank");
    return text;
  }
  private static double number(JsonElement value, String key) throws ConfigException {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw bad(key, "must be a number");
    double n = value.getAsDouble();
    if (!Double.isFinite(n)) throw bad(key, "must be finite");
    return n;
  }
  private static int integer(JsonElement value, String key, int min, int max) throws ConfigException {
    double n = number(value, key);
    if (n != Math.rint(n) || n < min || n > max) throw bad(key, "must be an integer from " + min + " to " + max);
    return (int) n;
  }
  private static double seconds(JsonElement value, String key, boolean zero) throws ConfigException {
    double n = number(value, key);
    if (n < 0 || !zero && n < 0.000001 || n * 1_000_000 >= Long.MAX_VALUE) throw bad(key, "must be a representable " + (zero ? "nonnegative" : "positive") + " duration in seconds");
    return n;
  }
  private static long micros(JsonElement value, String key, boolean zero) throws ConfigException {
    return Math.round(seconds(value, key, zero) * 1_000_000);
  }
  private static ConfigException bad(String key, String reason) { return new ConfigException(key + ": " + reason); }
}
