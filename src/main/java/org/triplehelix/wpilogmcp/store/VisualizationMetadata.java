/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.triplehelix.wpilogmcp.mcp.CallerContext.Authority;
import org.triplehelix.wpilogmcp.visualization.VisualizationContracts;

/** Narrow saved-view mutation boundary; no adapter can write arbitrary store paths. */
public final class VisualizationMetadata {
  private final LogStore store;
  public VisualizationMetadata(LogStore store) { this.store = store; }
  public CompletableFuture<JsonObject> save(Authority caller, UUID id, long expectedRevision,
      UUID request, String title, JsonObject source, JsonObject configuration) {
    final JsonObject candidate;
    try {
      java.util.Objects.requireNonNullElse(caller, Authority.UNTRUSTED).requireLocal();
      if (expectedRevision < 0 || expectedRevision >= 9_007_199_254_740_991L) throw new IOException("Invalid expected revision");
      if (title == null || title.isBlank() || title.length() > 256) throw new IOException("Title must contain 1–256 characters");
      candidate = new JsonObject(); candidate.addProperty("schema_version", 1);
      candidate.addProperty("saved_view_id", id.toString()); candidate.addProperty("revision", expectedRevision + 1);
      candidate.addProperty("request_id", request.toString()); candidate.addProperty("title", title);
      candidate.add("source_ref", VisualizationContracts.source(source));
      candidate.add("configuration", VisualizationContracts.configuration(configuration));
    } catch (Exception e) { return CompletableFuture.failedFuture(e); }
    // captureAsync supplies the queue, lock, current grants, mirror refusal and atomic writer.
    return store.captureAsync(io -> {
      var path = io.check(store.root().resolve("visualizations/saved-views/" + id + ".json"));
      long actual = 0;
      if (Files.exists(path)) {
        var current = io.read(path, JsonObject.class);
        if (current.get("schema_version").getAsInt() != 1 || !id.toString().equals(current.get("saved_view_id").getAsString())) throw new IOException("Invalid saved-view metadata");
        if (request.toString().equals(current.get("request_id").getAsString())) {
          if (!candidate.equals(current)) throw new IOException("REQUEST_CONFLICT: saved-view request has different content");
          return current.deepCopy();
        }
        actual = current.get("revision").getAsLong();
      }
      if (actual != expectedRevision) throw new IOException("REVISION_CONFLICT: current saved revision is " + actual);
      io.write(path, candidate);
      return candidate.deepCopy();
    });
  }
}
