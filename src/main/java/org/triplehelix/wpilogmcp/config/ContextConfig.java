/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import com.google.gson.JsonElement;
import java.net.URI;
import java.util.List;
import java.util.function.UnaryOperator;

/** Explicit coprocessor addresses; NT4 topic names cannot prove a backend's network address. */
public record ContextConfig(List<URI> photonvision, Jvm jvm) {
  public ContextConfig { photonvision = List.copyOf(photonvision); }
  public ContextConfig(List<URI> photonvision) { this(photonvision, null); }
  public record Jvm(int port, long periodUs) {}
  static ContextConfig parse(JsonElement value, UnaryOperator<String> expand) throws ConfigException {
    if (value == null || value.isJsonNull()) return null;
    if (!value.isJsonObject()) throw new ConfigException("context: must be an object");
    var object = value.getAsJsonObject();
    for (String key : object.keySet()) if (!java.util.Set.of("photonvision", "jvm").contains(key)) throw new ConfigException("context." + key + ": unknown key");
    var hosts = new java.util.ArrayList<URI>();
    if (object.has("photonvision")) {
      var list = object.get("photonvision");
      if (!list.isJsonArray()) throw new ConfigException("context.photonvision: must be a list of hosts");
      for (var item : list.getAsJsonArray()) {
        try {
          if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
          String host = expand.apply(item.getAsString());
          var uri = URI.create("http://" + host);
          if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
              || !uri.getPath().isEmpty() || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
          uri = new URI("http", null, uri.getHost(), uri.getPort() < 0 ? 5800 : uri.getPort(), "", null, null);
          if (hosts.contains(uri)) throw new IllegalArgumentException();
          hosts.add(uri);
        } catch (Exception e) { throw new ConfigException("context.photonvision: each item must be a distinct host, optionally with a port, without credentials or a path"); }
      }
    }
    Jvm jvm = null;
    if (object.has("jvm")) {
      if (!object.get("jvm").isJsonObject()) throw new ConfigException("context.jvm: must be an object with port");
      var valueObject = object.getAsJsonObject("jvm");
      for (String key : valueObject.keySet()) if (!java.util.Set.of("port", "period_sec").contains(key))
        throw new ConfigException("context.jvm." + key + ": unknown key");
      double port = number(valueObject.get("port"), "port");
      if (port < 1 || port > 65535 || port != Math.rint(port)) throw new ConfigException("context.jvm.port: must be an integer from 1 to 65535");
      double period = valueObject.has("period_sec") ? number(valueObject.get("period_sec"), "period_sec") : 1;
      if (period < .001 || period > 3600) throw new ConfigException("context.jvm.period_sec: must be from 0.001 to 3600 seconds");
      jvm = new Jvm((int) port, Math.round(period * 1_000_000));
    }
    return new ContextConfig(hosts, jvm);
  }
  private static double number(JsonElement value, String key) throws ConfigException {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
        || !Double.isFinite(value.getAsDouble())) throw new ConfigException("context.jvm." + key + ": must be a finite number");
    return value.getAsDouble();
  }
}
