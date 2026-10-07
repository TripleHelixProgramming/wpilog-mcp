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
public record PullConfig(boolean enabled, List<String> directories, long settleUs, long rateBytes, Ssh ssh) {
  public static final Set<String> KEYS = Set.of("enabled", "directories", "settle_sec", "rate_bytes", "ssh");
  public static final Set<String> SSH_KEYS = Set.of("user", "password", "key", "accept_changed_host_key");
  public static final PullConfig DISABLED = new PullConfig(false, List.of("/home/lvuser/logs", "/u/logs", "/U/logs"),
      5_000_000, 1_000_000, new Ssh("lvuser", "", null));
  public PullConfig { directories = List.copyOf(directories); }
  public record Ssh(String user, String password, Path key, boolean acceptChangedHostKey) {
    public Ssh(String user, String password, Path key) { this(user, password, key, false); }
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
      if (!block.get("directories").isJsonArray()) throw bad("directories", "must be a list of absolute remote directories");
      var list = new java.util.ArrayList<String>();
      for (var v : block.getAsJsonArray("directories")) {
        String name = string(v, "directories");
        if (!name.startsWith("/") || name.indexOf('\0') >= 0 || List.of(name.split("/")).contains("..")) throw bad("directories", "must contain absolute remote paths without '..'");
        while (name.length() > 1 && name.endsWith("/")) name = name.substring(0, name.length() - 1);
        if (!list.contains(name)) list.add(name);
      }
      directories = List.copyOf(list);
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
      ssh = new Ssh(user, password, key, acceptChanged);
    }
    return new PullConfig(enabled, directories, Math.round(settle * 1_000_000), (long) rate, ssh);
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
