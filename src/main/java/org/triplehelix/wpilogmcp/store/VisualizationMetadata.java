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
  public record Unreadable(String file, String reason) {}
  public record Listing(java.util.List<JsonObject> views, java.util.List<Unreadable> unreadable, boolean incomplete) {}
  private static final int MAX_RECORDS = 1024;
  private static final long MAX_RECORD_BYTES = 2L * 1024 * 1024, MAX_LIST_BYTES = 8L * 1024 * 1024;

  /** Shared/synced records can come from newer writers. Keep them intact and report each refusal;
   * one unreadable neighbor must not hide this reader's valid records. Limits count failed reads too. */
  public static Listing list(StoreDoor.Selected selected) throws IOException {
    var io = new StoreFiles(selected.root(), selected.security());
    var directory = io.check(selected.root().resolve("visualizations/saved-views"));
    var records = new java.util.ArrayList<JsonObject>();
    var unreadable = new java.util.ArrayList<Unreadable>();
    boolean incomplete = false;
    if (Files.exists(directory)) try (var children = Files.list(directory)) {
      long bytes = 0;
      int examined = 0;
      var iterator = children.iterator();
      while (iterator.hasNext()) {
        var path = iterator.next();
        var name = path.getFileName().toString();
        if (!name.endsWith(".json")) continue;
        if (examined++ >= MAX_RECORDS) {
          unreadable.add(new Unreadable(name, "WORKSPACE_LIMIT: listing stopped after 1024 records; later files were not read"));
          incomplete = true; break;
        }
        try {
          io.check(path);
          long size = Files.size(path);
          if (size > MAX_RECORD_BYTES) throw new IOException("Invalid saved-view metadata size (maximum 2 MiB)");
          if (bytes + size > MAX_LIST_BYTES) {
            unreadable.add(new Unreadable(name, "WORKSPACE_LIMIT: listing stopped before exceeding 8 MiB; later files were not read"));
            incomplete = true; break;
          }
          bytes += size;
          records.add(read(io, path));
        } catch (IOException | RuntimeException invalid) {
          String reason = invalid.getMessage() == null ? invalid.getClass().getSimpleName() : invalid.getMessage();
          unreadable.add(new Unreadable(name, reason.substring(0, Math.min(reason.length(), 512))));
        }
      }
    }
    return new Listing(records.stream().sorted(java.util.Comparator.comparing(r -> r.get("saved_view_id").getAsString())).toList(),
        java.util.List.copyOf(unreadable), incomplete);
  }

  /** Opening an identified record does not scan or depend on its neighbors or listing budget. */
  public static JsonObject read(StoreDoor.Selected selected, UUID id) throws IOException {
    var io = new StoreFiles(selected.root(), selected.security());
    var path = io.check(selected.root().resolve("visualizations/saved-views/" + id + ".json"));
    if (!Files.exists(path)) throw new IOException("SOURCE_UNAVAILABLE: saved view is missing");
    return read(io, path);
  }
  private static JsonObject read(StoreFiles io, java.nio.file.Path path) throws IOException {
    io.check(path);
    if (Files.size(path) > MAX_RECORD_BYTES) throw new IOException("Invalid saved-view metadata size (maximum 2 MiB)");
    var value = io.read(path, JsonObject.class);
    UUID id = UUID.fromString(value.get("saved_view_id").getAsString());
    if (!path.getFileName().toString().equals(id + ".json") || value.get("schema_version").getAsInt() != 1)
      throw new IOException("Invalid saved-view identity or unsupported schema version");
    // Validate every field the listing/open adapters consume before admitting this record.
    if (!value.keySet().equals(java.util.Set.of("schema_version", "saved_view_id", "revision", "request_id", "title", "source_ref", "configuration")))
      throw new IOException("Invalid saved-view fields");
    long revision = value.get("revision").getAsLong();
    if (revision < 1 || revision > 9_007_199_254_740_991L || revision != value.get("revision").getAsDouble())
      throw new IOException("Invalid saved-view revision");
    UUID.fromString(value.get("request_id").getAsString());
    if (!value.get("title").isJsonPrimitive() || !value.getAsJsonPrimitive("title").isString()
        || value.get("title").getAsString().isBlank() || value.get("title").getAsString().length() > 256)
      throw new IOException("Invalid saved-view title");
    VisualizationContracts.source(value.getAsJsonObject("source_ref"));
    VisualizationContracts.configuration(value.getAsJsonObject("configuration"));
    return value;
  }
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
