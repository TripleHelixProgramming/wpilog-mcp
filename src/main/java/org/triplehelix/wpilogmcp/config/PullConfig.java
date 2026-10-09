/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/** Pulling stays off until a team opts in after checking its robot in the shop. */
public record PullConfig(boolean enabled, List<String> directories, long settleUs, long rateBytes, Ssh ssh,
    SystemPullConfig system) {
  public static final Set<String> KEYS = Set.of("enabled", "directories", "settle_sec", "rate_bytes", "ssh", "system");
  public static final Set<String> SSH_KEYS = Set.of("user", "password", "key", "port", "accept_changed_host_key");
  public static final PullConfig DISABLED = new PullConfig(false, List.of("/home/lvuser/logs", "/u/logs", "/U/logs"),
      5_000_000, 1_000_000, new Ssh("lvuser", "", null));
  public PullConfig { directories = List.copyOf(directories); }
  public PullConfig(boolean enabled, List<String> directories, long settleUs, long rateBytes, Ssh ssh) {
    this(enabled, directories, settleUs, rateBytes, ssh, SystemPullConfig.DISABLED);
  }
  public boolean active() { return enabled || system.enabled(); }
  public record Ssh(String user, String password, Path key, boolean acceptChangedHostKey, int port) {
    public Ssh(String user, String password, Path key) { this(user, password, key, false, 22); }
    public Ssh(String user, String password, Path key, boolean acceptChangedHostKey) { this(user, password, key, acceptChangedHostKey, 22); }
    @Override public String toString() { return "Ssh[user=" + user + ", password=<redacted>, key=" + key + "]"; }
  }
  static PullConfig parse(JsonElement value, UnaryOperator<String> paths, UnaryOperator<String> text) throws ConfigException {
    if (value == null) return DISABLED;
    var block = object(value, "capture.pull", KEYS);
    boolean enabled = false;
    if (block.has("enabled")) {
      var v = block.get("enabled");
      if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) throw bad("enabled", "must be a boolean");
      enabled = v.getAsBoolean();
    }
    var directories = DISABLED.directories();
    if (block.has("directories")) {
      directories = remotePaths(block.get("directories"), "directories");
    }
    double settle = block.has("settle_sec") ? number(block.get("settle_sec"), "settle_sec") : 5;
    if (settle < 0 || settle * 1_000_000 >= Long.MAX_VALUE) throw bad("settle_sec", "must be a representable nonnegative duration");
    double rate = block.has("rate_bytes") ? number(block.get("rate_bytes"), "rate_bytes") : 1_000_000;
    if (rate < 1 || rate > Integer.MAX_VALUE || rate != Math.rint(rate)) throw bad("rate_bytes", "must be an integer from 1 through 2147483647");
    var ssh = DISABLED.ssh();
    if (block.has("ssh")) {
      var obj = object(block.get("ssh"), "capture.pull.ssh", SSH_KEYS);
      String user = obj.has("user") ? text.apply(string(obj.get("user"), "ssh.user")) : "lvuser";
      String password = obj.has("password") ? text.apply(stringOrEmpty(obj.get("password"), "ssh.password")) : "";
      Path key = null;
      if (obj.has("key")) {
        try { key = Path.of(paths.apply(string(obj.get("key"), "ssh.key"))).toAbsolutePath().normalize(); }
        catch (java.nio.file.InvalidPathException e) { throw bad("ssh.key", "invalid path"); }
      }
      if (obj.has("password") && key != null) throw bad("ssh", "choose password or key");
      if (user.isBlank()) throw bad("ssh.user", "must not be blank");
      boolean acceptChanged = false;
      if (obj.has("accept_changed_host_key")) {
        var v = obj.get("accept_changed_host_key");
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) throw bad("ssh.accept_changed_host_key", "must be a boolean");
        acceptChanged = v.getAsBoolean();
      }
      double port = obj.has("port") ? number(obj.get("port"), "ssh.port") : 22;
      if (port < 1 || port > 65535 || port != Math.rint(port)) throw bad("ssh.port", "must be an integer from 1 through 65535");
      ssh = new Ssh(user, password, key, acceptChanged, (int) port);
    }
    return new PullConfig(enabled, directories, Math.round(settle * 1_000_000), (long) rate, ssh,
        SystemPullConfig.parse(block.get("system")));
  }
  static List<String> remotePaths(JsonElement value, String key) throws ConfigException {
    if (!value.isJsonArray()) throw bad(key, "must be a list of absolute remote paths");
    var list = new java.util.ArrayList<String>();
    for (var v : value.getAsJsonArray()) {
      String name = string(v, key);
      if (!name.startsWith("/") || name.chars().anyMatch(c -> c < 32) || List.of(name.split("/")).contains("..")) {
        throw bad(key, "must contain absolute remote paths without '..' or control characters");
      }
      while (name.length() > 1 && name.endsWith("/")) name = name.substring(0, name.length() - 1);
      if (!list.contains(name)) list.add(name);
    }
    return List.copyOf(list);
  }
  private static JsonObject object(JsonElement value, String key, Set<String> allowed) throws ConfigException {
    if (value == null || !value.isJsonObject()) throw new ConfigException(key + ": must be an object");
    var object = value.getAsJsonObject();
    for (var name : object.keySet()) if (!allowed.contains(name)) throw new ConfigException(key + "." + name + ": unknown key");
    return object;
  }
  private static String stringOrEmpty(JsonElement value, String key) throws ConfigException {
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw bad(key, "must be a string");
    return value.getAsString();
  }
  private static String string(JsonElement value, String key) throws ConfigException {
    String text = stringOrEmpty(value, key); if (text.isBlank()) throw bad(key, "must not be blank"); return text;
  }
  private static double number(JsonElement value, String key) throws ConfigException {
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble())) throw bad(key, "must be finite numeric data");
    return value.getAsDouble();
  }
  private static ConfigException bad(String key, String reason) { return new ConfigException("capture.pull." + key + ": " + reason); }
}
