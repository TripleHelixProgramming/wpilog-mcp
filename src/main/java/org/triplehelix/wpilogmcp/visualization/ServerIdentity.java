/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.triplehelix.wpilogmcp.config.ConfigurationIdentity;

/** Local owner identity is durable for a named configuration, independent of ports and PIDs. */
public record ServerIdentity(UUID id, Path stateDirectory, String reason) {
  public static ServerIdentity ephemeral() { return ephemeral("Unnamed server; working state is not persistent"); }
  public static ServerIdentity ephemeral(String reason) { return new ServerIdentity(UUID.randomUUID(), null, reason); }
  public boolean durable() { return stateDirectory != null; }
  public static ServerIdentity open(Path home, Path config, String name) throws IOException {
    return open(home.toRealPath().resolve(".wpilog-mcp/state/visualizations").resolve(ConfigurationIdentity.key(config, name)));
  }
  public static ServerIdentity open(Path directory) throws IOException {
    var files = new StateFiles(directory);
    try (var lock = files.lock()) {
      if (Files.exists(files.path("server.json"))) {
        try {
          var json = JsonParser.parseString(files.read("server.json", 4096)).getAsJsonObject();
          if (json.get("schema_version").getAsInt() != 1) throw new IOException("Unsupported server identity schema");
          return new ServerIdentity(UUID.fromString(json.get("server_instance_id").getAsString()), directory, null);
        } catch (RuntimeException e) { throw new IOException("Invalid server identity; existing state retained", e); }
      }
      var identity = new ServerIdentity(UUID.randomUUID(), directory, null);
      var json = identity.describe(); json.addProperty("schema_version", 1);
      files.write("server.json", json.toString());
      return identity;
    }
  }
  public JsonObject describe() {
    var json = new JsonObject(); json.addProperty("server_instance_id", id.toString());
    json.addProperty("working_state", durable() ? "durable" : "ephemeral");
    if (reason != null) json.addProperty("working_state_reason", reason);
    return json;
  }
}
