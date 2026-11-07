/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4;

import com.google.gson.JsonObject;
import java.util.Optional;

/** An explicit wire declaration preserves provider provenance across a gateway recording.
 * A /Daemon name alone is ordinary NT data; publisher metadata is not a trust credential. */
public final class ContextTopic {
  private ContextTopic() {}
  public static JsonObject properties(JsonObject metadata) {
    var declaration = new JsonObject(); declaration.addProperty("version", 1); declaration.add("metadata", metadata.deepCopy());
    var properties = new JsonObject(); properties.add("wpilog_context", declaration); return properties;
  }
  public static Optional<JsonObject> metadata(String name, JsonObject properties) {
    if (!name.startsWith("/Daemon/")) return Optional.empty();
    var declaration = properties.get("wpilog_context");
    if (declaration == null || !declaration.isJsonObject()) return Optional.empty();
    var version = declaration.getAsJsonObject().get("version");
    var metadata = declaration.getAsJsonObject().get("metadata");
    if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
        || !version.getAsString().equals("1") || metadata == null || !metadata.isJsonObject()) return Optional.empty();
    var source = metadata.getAsJsonObject().get("source");
    if (source == null || !source.isJsonPrimitive() || !source.getAsJsonPrimitive().isString() || source.getAsString().isBlank()) return Optional.empty();
    return Optional.of(metadata.getAsJsonObject().deepCopy());
  }
}
