/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * NT4 control messages. Unknown methods and malformed individual messages are ignored as the
 * protocol requires; a bad neighbor in a JSON batch does not hide a valid announcement.
 * Objects are defensively copied because announcements cross the client's listener boundary.
 */
public sealed interface ControlMessage {
  Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

  record Publish(String name, int pubuid, String type, JsonObject properties) implements ControlMessage {
    public Publish { Objects.requireNonNull(name); Objects.requireNonNull(type); properties = properties.deepCopy(); }
    @Override public JsonObject properties() { return properties.deepCopy(); }
  }
  record Unpublish(int pubuid) implements ControlMessage {}
  record SetProperties(String name, JsonObject update) implements ControlMessage {
    public SetProperties { Objects.requireNonNull(name); update = update.deepCopy(); }
    @Override public JsonObject update() { return update.deepCopy(); }
  }
  record Subscribe(List<String> topics, int subuid, JsonObject options) implements ControlMessage {
    public Subscribe {
      topics = List.copyOf(topics);
      options = options.deepCopy();
      if (options.has("periodic") && (!options.get("periodic").isJsonPrimitive()
          || !options.getAsJsonPrimitive("periodic").isNumber())) throw new IllegalArgumentException("periodic");
      double period = options.has("periodic") ? options.get("periodic").getAsDouble() : 0.1;
      if (!Double.isFinite(period) || period <= 0) throw new IllegalArgumentException("Invalid period");
      for (var key : List.of("all", "prefix", "topicsonly")) {
        if (options.has(key) && (!options.get(key).isJsonPrimitive()
            || !options.getAsJsonPrimitive(key).isBoolean())) throw new IllegalArgumentException(key);
      }
    }
    @Override public JsonObject options() { return options.deepCopy(); }
    public double periodic() { return options.has("periodic") ? options.get("periodic").getAsDouble() : 0.1; }
    public boolean all() { return flag("all"); }
    public boolean topicsOnly() { return flag("topicsonly"); }
    public boolean prefix() { return flag("prefix"); }
    private boolean flag(String key) { return options.has(key) && options.get(key).getAsBoolean(); }
    public boolean matches(String name) {
      return topics.stream().anyMatch(t -> prefix()
          ? name.startsWith(t) && !(t.isEmpty() && name.startsWith("$")) : name.equals(t));
    }
  }
  record Unsubscribe(int subuid) implements ControlMessage {}
  record Announce(String name, int id, String type, Integer pubuid, JsonObject properties)
      implements ControlMessage {
    public Announce { Objects.requireNonNull(name); Objects.requireNonNull(type); properties = properties.deepCopy(); }
    @Override public JsonObject properties() { return properties.deepCopy(); }
    public boolean cached() { return !properties.has("cached") || properties.get("cached").getAsBoolean(); }
    public Announce withUpdate(JsonObject update) {
      var changed = properties();
      update.entrySet().forEach(e -> {
        if (e.getValue().isJsonNull()) changed.remove(e.getKey());
        else changed.add(e.getKey(), e.getValue().deepCopy());
      });
      return new Announce(name, id, type, pubuid, changed);
    }
  }
  record Unannounce(String name, int id) implements ControlMessage {
    public Unannounce { Objects.requireNonNull(name); }
  }
  record Properties(String name, Boolean ack, JsonObject update) implements ControlMessage {
    public Properties { Objects.requireNonNull(name); update = update.deepCopy(); }
    @Override public JsonObject update() { return update.deepCopy(); }
  }

  default String method() {
    if (this instanceof SetProperties) return "setproperties";
    return getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
  }

  default JsonObject json() {
    var result = new JsonObject();
    result.addProperty("method", method());
    var params = JSON.toJsonTree(this).getAsJsonObject();
    if (this instanceof Announce a && a.pubuid() == null) params.remove("pubuid");
    if (this instanceof Properties p && p.ack() == null) params.remove("ack");
    result.add("params", params);
    return result;
  }

  static String encode(List<? extends ControlMessage> messages) {
    var array = new JsonArray();
    messages.forEach(m -> array.add(m.json()));
    return JSON.toJson(array);
  }

  static List<ControlMessage> decode(String text) {
    if (text.length() > MessagePack.MAX_BYTES) throw new IllegalArgumentException("NT4 text too large");
    var root = JsonParser.parseString(text);
    if (!root.isJsonArray()) return List.of();
    var messages = new ArrayList<ControlMessage>();
    for (var item : root.getAsJsonArray()) {
      try {
        var object = item.getAsJsonObject();
        var method = string(object, "method");
        var p = object.getAsJsonObject("params");
        ControlMessage message = switch (method) {
          case "publish" -> new Publish(string(p, "name"), integer(p, "pubuid"), string(p, "type"), p.getAsJsonObject("properties"));
          case "unpublish" -> new Unpublish(integer(p, "pubuid"));
          case "setproperties" -> new SetProperties(string(p, "name"), p.getAsJsonObject("update"));
          case "subscribe" -> new Subscribe(strings(p.getAsJsonArray("topics")), integer(p, "subuid"), p.getAsJsonObject("options"));
          case "unsubscribe" -> new Unsubscribe(integer(p, "subuid"));
          case "announce" -> new Announce(string(p, "name"), integer(p, "id"), string(p, "type"),
              p.has("pubuid") ? integer(p, "pubuid") : null, p.getAsJsonObject("properties"));
          case "unannounce" -> new Unannounce(string(p, "name"), integer(p, "id"));
          case "properties" -> new Properties(string(p, "name"), p.has("ack") ? bool(p.get("ack")) : null, p.getAsJsonObject("update"));
          default -> null;
        };
        if (message != null) messages.add(message);
      } catch (IllegalArgumentException | IllegalStateException | NullPointerException
          | ArithmeticException | ClassCastException e) {
        // Ignore a malformed individual JSON message, per Text Data Frames in the NT4 spec.
      }
    }
    return List.copyOf(messages);
  }

  private static String string(JsonObject p, String key) {
    var value = p.getAsJsonPrimitive(key);
    if (!value.isString()) throw new IllegalArgumentException(key);
    return value.getAsString();
  }

  private static int integer(JsonObject p, String key) {
    var value = p.getAsJsonPrimitive(key);
    if (!value.isNumber()) throw new IllegalArgumentException(key);
    return value.getAsBigDecimal().intValueExact();
  }

  private static boolean bool(JsonElement value) {
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("boolean");
    return value.getAsBoolean();
  }

  private static List<String> strings(JsonArray array) {
    var result = new ArrayList<String>();
    for (var value : array) {
      if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("topic");
      result.add(value.getAsString());
    }
    return result;
  }
}
