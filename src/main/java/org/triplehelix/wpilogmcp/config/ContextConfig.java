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
public record ContextConfig(List<URI> photonvision) {
  public ContextConfig { photonvision = List.copyOf(photonvision); }
  static ContextConfig parse(JsonElement value, UnaryOperator<String> expand) throws ConfigException {
    if (value == null || value.isJsonNull()) return null;
    if (!value.isJsonObject()) throw new ConfigException("context: must be an object");
    var object = value.getAsJsonObject();
    for (String key : object.keySet()) if (!key.equals("photonvision")) throw new ConfigException("context." + key + ": unknown key");
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
    return new ContextConfig(hosts);
  }
}
