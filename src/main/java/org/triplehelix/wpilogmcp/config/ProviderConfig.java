/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/** Provider defaults follow explicit SSH configuration, without enabling the disabled-only puller. */
public record ProviderConfig(boolean robotSsh, Stats stats, List<Tail> tails, List<java.net.URI> photonvision) {
  public static final String CONSOLE = "/home/lvuser/FRC_UserProgram.log";
  public static final Set<String> STATS_KEYS = Set.of("enabled", "period_sec", "budget_ms");
  public static final Set<String> TAIL_KEYS = Set.of("host", "user", "key", "password", "port", "path", "role", "files");
  public static final ProviderConfig DISABLED = new ProviderConfig(false, new Stats(false, 2_000_000, 100_000), List.of());
  public ProviderConfig { tails = List.copyOf(tails); photonvision = List.copyOf(photonvision); }
  public ProviderConfig(boolean robotSsh, Stats stats, List<Tail> tails) {
    this(robotSsh, stats, tails, List.of());
  }
  public record Stats(boolean enabled, long periodUs, long budgetUs) {}
  public record Tail(String host, PullConfig.Ssh ssh, String path, String role) {}

  static ProviderConfig parse(JsonObject capture, PullConfig pull, UnaryOperator<String> paths,
      UnaryOperator<String> text) throws ConfigException {
    boolean configured = pull.active() || capture.has("pull") && capture.get("pull").isJsonObject()
        && capture.getAsJsonObject("pull").has("ssh");
    boolean enabled = configured; long period = 2_000_000, budget = 100_000;
    if (capture.has("stats")) {
      var stats = object(capture.get("stats"), "capture.stats", STATS_KEYS);
      if (stats.has("enabled")) {
        var value = stats.get("enabled");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw bad("capture.stats.enabled", "must be a boolean");
        enabled = value.getAsBoolean();
      }
      if (stats.has("period_sec")) period = duration(stats.get("period_sec"), "capture.stats.period_sec", 1_000_000, 30);
      if (stats.has("budget_ms")) budget = duration(stats.get("budget_ms"), "capture.stats.budget_ms", 1000, 30_000);
    }
    var tails = new ArrayList<Tail>();
    if (!capture.has("tail")) {
      if (configured) tails.add(new Tail(null, pull.ssh(), CONSOLE, "program_console"));
    } else {
      var value = capture.get("tail");
      if (!value.isJsonArray()) throw bad("capture.tail", "must be a list");
      int i = 0;
      for (var item : value.getAsJsonArray()) {
        String key = "capture.tail[" + i++ + "]";
        var entry = object(item, key, TAIL_KEYS);
        String host = entry.has("host") ? text.apply(string(entry.get("host"), key + ".host")) : null;
        if (host != null) try { org.triplehelix.wpilogmcp.nt4.client.RobotAddress.uri(host, 5810, "host"); }
        catch (IllegalArgumentException e) { throw bad(key + ".host", "invalid host"); }
        var ssh = host == null ? pull.ssh() : PullConfig.DISABLED.ssh();
        if (host == null && List.of("user", "key", "password", "port").stream().anyMatch(entry::has)) {
          throw bad(key, "robot SSH settings belong in capture.pull.ssh");
        }
        if (host != null) {
          String user = entry.has("user") ? text.apply(string(entry.get("user"), key + ".user")) : ssh.user();
          String password = entry.has("password") ? secret(entry.get("password"), key + ".password", text) : "";
          Path identity = entry.has("key") ? Path.of(secret(entry.get("key"), key + ".key", paths)).toAbsolutePath().normalize() : null;
          if (entry.has("password") && identity != null) throw bad(key, "choose password or key");
          int port = ssh.port();
          if (entry.has("port")) {
            double n = number(entry.get("port"), key + ".port");
            if (n != Math.rint(n) || n < 1 || n > 65535) throw bad(key + ".port", "must be an integer from 1 to 65535");
            port = (int) n;
          }
          if (user.isBlank()) throw bad(key + ".user", "must not be blank");
          ssh = new PullConfig.Ssh(user, password, identity, false, port);
        }
        if (entry.has("files")) {
          if (entry.has("path") || entry.has("role")) throw bad(key, "choose files or path and role");
          if (!entry.get("files").isJsonArray()) throw bad(key + ".files", "must be a list");
          int j = 0;
          for (var file : entry.getAsJsonArray("files")) {
            String location = key + ".files[" + j++ + "]";
            tails.add(tail(object(file, location, Set.of("path", "role")), location, host, ssh));
          }
        } else tails.add(tail(entry, key, host, ssh));
      }
    }
    var identities = new java.util.HashMap<String, PullConfig.Ssh>();
    var entries = new java.util.HashSet<String>();
    for (var tail : tails) {
      String host = tail.host() == null ? "<robot>" : tail.host();
      var previous = identities.putIfAbsent(host, tail.ssh());
      if (previous != null && !previous.equals(tail.ssh())) throw bad("capture.tail", "one host must use one set of SSH settings");
      if (!entries.add(host + "/" + tail.role())) throw bad("capture.tail", "each host and role must name only one file");
    }
    return new ProviderConfig(configured || enabled || tails.stream().anyMatch(t -> t.host() == null), new Stats(enabled, period, budget), tails);
  }
  private static Tail tail(JsonObject value, String key, String host, PullConfig.Ssh ssh) throws ConfigException {
    String path = string(value.get("path"), key + ".path"), role = string(value.get("role"), key + ".role");
    if (!path.startsWith("/") || path.indexOf('\0') >= 0 || path.contains("\n")) throw bad(key + ".path", "must be an absolute remote path without NUL or newline");
    if (!role.matches("[A-Za-z0-9_.-]+")) throw bad(key + ".role", "must be one entry path component");
    return new Tail(host, ssh, path, role);
  }
  private static String secret(JsonElement value, String key, UnaryOperator<String> expand) throws ConfigException {
    String reference = string(value, key);
    if (!reference.matches("\\$\\{[A-Za-z_][A-Za-z0-9_]*\\}")) throw bad(key, "must name an environment variable (${NAME}), never a literal");
    String result = expand.apply(reference);
    if (result.isBlank() || result.contains("${")) throw bad(key, "environment variable is unset or empty");
    return result;
  }
  private static JsonObject object(JsonElement value, String key, Set<String> keys) throws ConfigException {
    if (value == null || !value.isJsonObject()) throw bad(key, "must be an object");
    var obj = value.getAsJsonObject();
    for (String name : obj.keySet()) if (!keys.contains(name)) throw bad(key + "." + name, "unknown key");
    return obj;
  }
  private static String string(JsonElement value, String key) throws ConfigException {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) throw bad(key, "must be a nonempty string");
    return value.getAsString();
  }
  private static double number(JsonElement value, String key) throws ConfigException {
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble())) throw bad(key, "must be finite numeric data");
    return value.getAsDouble();
  }
  private static long duration(JsonElement value, String key, long unit, double max) throws ConfigException {
    double number = number(value, key);
    if (number * unit < 1 || number > max) throw bad(key, "must be positive and no greater than " + max);
    return Math.round(number * unit);
  }
  private static ConfigException bad(String key, String reason) { return new ConfigException(key + ": " + reason); }
}
