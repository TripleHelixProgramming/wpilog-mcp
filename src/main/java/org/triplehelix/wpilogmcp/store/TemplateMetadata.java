/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.triplehelix.wpilogmcp.mcp.CallerContext.Authority;
import org.triplehelix.wpilogmcp.visualization.*;

/** Template and receipts commit in one atomic file under the store queue/lock. A crash cannot
 * publish a template without its retry proof. Imports inspect a bounded complete registry before
 * deciding whether an identity/revision is new; an incomplete registry refuses that mutation. */
public final class TemplateMetadata {
  private static final int MAX_RECORDS = 1024, MAX_LIST_BYTES = 8 << 20, MAX_RECEIPTS = 64;
  private final LogStore store;
  private final String storeId;
  public TemplateMetadata(LogStore store, String storeId) { this.store = store; this.storeId = storeId; }
  public record Listing(List<JsonObject> records, List<VisualizationMetadata.Unreadable> unreadable, boolean incomplete) {}
  public static Listing list(StoreDoor.Selected selected) throws IOException {
    return list(new StoreFiles(selected.root(), selected.security()), selected.root());
  }
  private static Listing list(StoreFiles io, Path root) throws IOException {
    var directory = io.check(root.resolve("visualizations/templates"));
    var records = new ArrayList<JsonObject>(); var unreadable = new ArrayList<VisualizationMetadata.Unreadable>();
    boolean incomplete = false; int count = 0; long bytes = 0;
    if (Files.exists(directory)) try (var children = Files.list(directory)) {
      var it = children.iterator();
      while (it.hasNext()) {
        var path = it.next(); if (!path.getFileName().toString().endsWith(".json")) continue;
        if (++count > MAX_RECORDS) { incomplete = true; break; }
        try {
          io.check(path); long size = Files.size(path);
          if (size > VisualizationContracts.MAX_CONFIGURATION_BYTES) throw new IOException("Template metadata exceeds 1 MiB");
          if ((bytes += size) > MAX_LIST_BYTES) { incomplete = true; break; }
          records.add(read(io, path));
        } catch (IOException | RuntimeException e) {
          String reason = Objects.toString(e.getMessage(), e.getClass().getSimpleName());
          unreadable.add(new VisualizationMetadata.Unreadable(path.getFileName().toString(), reason.substring(0, Math.min(512, reason.length()))));
        }
      }
    }
    return new Listing(List.copyOf(records), List.copyOf(unreadable), incomplete);
  }
  private static Path path(Path root, UUID id) { return root.resolve("visualizations/templates/" + id + ".json"); }
  private static JsonObject read(StoreFiles io, Path path) throws IOException {
    io.check(path);
    final JsonObject record;
    try {
      if (Files.size(path) > VisualizationContracts.MAX_CONFIGURATION_BYTES) throw new IOException("Template metadata exceeds 1 MiB");
      record = VisualizationContracts.parse(Files.readString(path));
    } catch (NoSuchFileException missing) {
      throw new IOException("TEMPLATE_UNAVAILABLE: store template is missing; refresh the template catalog and select an available definition or save a new template from an existing view", missing);
    }
    if (!record.keySet().equals(Set.of("schema_version", "template", "requests", "imports")) || !new JsonPrimitive(1).equals(record.get("schema_version"))) throw new IOException("Unsupported template metadata");
    var template = TemplateDefinition.validate(record.getAsJsonObject("template"));
    if (!path.getFileName().toString().equals(template.get("template_id").getAsString() + ".json")) throw new IOException("Template identity does not match its file");
    if (record.getAsJsonObject("requests").size() > MAX_RECEIPTS || record.getAsJsonObject("imports").size() > MAX_RECEIPTS) throw new IOException("Template receipts exceed their bound");
    for (var field : List.of("requests", "imports")) for (var entry : record.getAsJsonObject(field).entrySet()) {
      if (field.equals("requests")) UUID.fromString(entry.getKey());
      else if (!entry.getKey().matches("[0-9a-f-]{36}:[0-9]+:[a-f0-9]{64}")) throw new IOException("Invalid portable receipt key");
      var receipt = entry.getValue().getAsJsonObject();
      if (!receipt.keySet().equals(Set.of("signature", "result")) || !receipt.get("signature").isJsonPrimitive()) throw new IOException("Invalid template receipt");
      TemplateDefinition.validate(receipt.getAsJsonObject("result").getAsJsonObject("template"));
    }
    return record;
  }
  public static JsonObject read(StoreDoor.Selected selected, UUID id) throws IOException {
    return read(new StoreFiles(selected.root(), selected.security()), path(selected.root(), id)).getAsJsonObject("template").deepCopy();
  }
  private static JsonObject empty() {
    var r = new JsonObject(); r.addProperty("schema_version", 1); r.add("requests", new JsonObject()); r.add("imports", new JsonObject()); return r;
  }
  public CompletableFuture<JsonObject> save(Authority authority, UUID request, UUID id, long expected, String name, String description, JsonObject configuration) {
    final JsonObject candidate; final String signature;
    try {
      Objects.requireNonNullElse(authority, Authority.UNTRUSTED).requireLocal();
      if (expected < 0 || expected >= 9_007_199_254_740_991L) throw new IOException("Invalid expected revision");
      candidate = new JsonObject(); candidate.addProperty("schema_version", 1); candidate.addProperty("template_id", id.toString()); candidate.addProperty("revision", expected + 1);
      candidate.addProperty("name", name); candidate.addProperty("description", description); candidate.add("configuration", configuration.deepCopy());
      TemplateDefinition.validate(candidate); signature = TemplateDefinition.digest(candidate);
    } catch (Exception e) { return CompletableFuture.failedFuture(e); }
    return store.captureAsync(io -> {
      var path = io.check(path(store.root(), id));
      // An update must read its existing record under the queue/lock. Missing is not revision 0,
      // and permissions or malformed data must keep their own diagnostics rather than create.
      var record = expected != 0 || !Files.notExists(path) ? read(io, path) : empty();
      var receipt = record.getAsJsonObject("requests").get(request.toString());
      if (receipt != null) return retry(receipt.getAsJsonObject(), signature);
      long actual = record.has("template") ? record.getAsJsonObject("template").get("revision").getAsLong() : 0;
      if (actual != expected) throw new IOException("REVISION_CONFLICT: current template revision is " + actual);
      if (actual == 0) admitNew(io);
      record.add("template", candidate); var answer = TemplateDefinition.catalog(storeId, candidate);
      receipt(record.getAsJsonObject("requests"), request.toString(), signature, answer);
      write(io, path, record); return answer;
    });
  }
  public CompletableFuture<JsonObject> importDefinition(Authority authority, UUID request, String envelope, String mode, Long expected) {
    final JsonObject imported; final String digest; final String portableKey; final String identity;
    try {
      Objects.requireNonNullElse(authority, Authority.UNTRUSTED).requireLocal();
      if (!Set.of("copy", "update").contains(mode)) throw new IOException("Import mode must be copy or update");
      imported = TemplateDefinition.imported(envelope); digest = TemplateDefinition.export(imported).get("content_sha256").getAsString();
      identity = imported.get("template_id").getAsString() + ":" + imported.get("revision").getAsLong(); portableKey = identity + ":" + digest;
    } catch (Exception e) { return CompletableFuture.failedFuture(e); }
    return store.captureAsync(io -> {
      var listing = list(io, store.root());
      if (listing.incomplete() || !listing.unreadable().isEmpty()) throw new IOException("INCOMPLETE_CATALOG: repair unreadable template records before importing; files were retained");
      String signature = digest + ":" + mode + ":" + expected;
      for (var record : listing.records()) {
        var prior = record.getAsJsonObject("requests").get(request.toString());
        if (prior != null) return retry(prior.getAsJsonObject(), signature);
      }
      for (var record : listing.records()) {
        var receipts = record.getAsJsonObject("imports");
        if (receipts.has(portableKey)) return receipts.getAsJsonObject(portableKey).getAsJsonObject("result").deepCopy();
        if (receipts.keySet().stream().anyMatch(key -> key.startsWith(identity + ":"))) throw new IOException("REVISION_CONFLICT: this portable identity/revision has different content");
      }
      UUID id = UUID.fromString(imported.get("template_id").getAsString());
      var target = io.check(path(store.root(), id)); JsonObject record = Files.exists(target) ? read(io, target) : empty();
      var candidate = imported.deepCopy();
      if (record.has("template")) {
        var current = record.getAsJsonObject("template"); long revision = current.get("revision").getAsLong();
        long importedRevision = imported.get("revision").getAsLong();
        if (revision == importedRevision) {
          if (!TemplateDefinition.digest(current).equals(TemplateDefinition.digest(imported))) throw new IOException("REVISION_CONFLICT: same template revision has different content");
          // Record the portable retry even when this was originally created locally.
          var answer = TemplateDefinition.catalog(storeId, current); receipt(record.getAsJsonObject("imports"), portableKey, digest, answer); receipt(record.getAsJsonObject("requests"), request.toString(), signature, answer); write(io, target, record); return answer;
        }
        if (mode.equals("update")) {
          if (expected == null || expected != revision || importedRevision <= revision || revision >= 9_007_199_254_740_991L) throw new IOException("REVISION_CONFLICT: Update existing requires its current revision and a newer import; use Import copy otherwise");
          candidate.addProperty("revision", revision + 1);
        } else {
          id = UUID.nameUUIDFromBytes(portableKey.getBytes(StandardCharsets.UTF_8)); target = io.check(path(store.root(), id));
          if (Files.exists(target)) throw new IOException("REQUEST_CONFLICT: import copy identity already exists");
          record = empty(); candidate.addProperty("template_id", id.toString()); candidate.addProperty("revision", 1);
        }
      } else if (mode.equals("update")) throw new IOException("REVISION_CONFLICT: Update existing requires an existing template");
      if (!record.has("template")) admitNew(io);
      record.add("template", candidate); var answer = TemplateDefinition.catalog(storeId, candidate);
      receipt(record.getAsJsonObject("imports"), portableKey, digest, answer);
      receipt(record.getAsJsonObject("requests"), request.toString(), signature, answer);
      write(io, target, record); return answer;
    });
  }
  private void admitNew(StoreFiles io) throws IOException {
    var listing = list(io, store.root());
    if (listing.incomplete() || listing.records().size() + listing.unreadable().size() >= MAX_RECORDS) throw new IOException("WORKSPACE_LIMIT: template registry is full; no files were removed");
  }
  private static JsonObject retry(JsonObject receipt, String signature) throws IOException {
    if (!signature.equals(receipt.get("signature").getAsString())) throw new IOException("REQUEST_CONFLICT: request id has different content");
    return receipt.getAsJsonObject("result").deepCopy();
  }
  private static void receipt(JsonObject receipts, String key, String signature, JsonObject result) throws IOException {
    if (!receipts.has(key) && receipts.size() >= MAX_RECEIPTS) throw new IOException("WORKSPACE_LIMIT: template retry history is full; save a new template copy");
    var item = new JsonObject(); item.addProperty("signature", signature); item.add("result", result.deepCopy()); receipts.add(key, item);
  }
  private static void write(StoreFiles io, Path path, JsonObject record) throws IOException {
    if (StoreFiles.JSON.toJson(record).getBytes(StandardCharsets.UTF_8).length > VisualizationContracts.MAX_CONFIGURATION_BYTES) throw new IOException("WORKSPACE_LIMIT: template and its receipts exceed 1 MiB; save a new copy");
    io.write(path, record);
  }
}
