/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.triplehelix.wpilogmcp.log.*;
import org.triplehelix.wpilogmcp.mcp.CallerContext.Authority;
import org.triplehelix.wpilogmcp.read.*;
import org.triplehelix.wpilogmcp.read.catalog.StoreReadCatalog;
import org.triplehelix.wpilogmcp.read.internal.ReadWire;
import org.triplehelix.wpilogmcp.store.*;

/** Source admission does no value decoding. Locators stay local; catalog identities stay portable.
 * Each operation rechecks the current grants and fixed file boundary before using a source. */
public final class RecordedSources {
  private record ContentBoundary(Path path, FileSnapshot boundary, String sha256) {}
  private final com.github.benmanes.caffeine.cache.Cache<ContentBoundary, Boolean> verified =
      com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(256)
          .expireAfterAccess(java.time.Duration.ofMinutes(10)).build();
  private final LogManager logs;
  private final VisualizationService views;
  private final StoreDoor door;
  public RecordedSources(LogManager logs, VisualizationService views, StoreDoor door) {
    this.logs = logs; this.views = views; this.door = door;
  }
  private StoreReadCatalog catalog() throws IOException {
    var roots = door.stores().stream().map(StoreDoor.Selected::root).toList();
    return roots.isEmpty() ? null : new StoreReadCatalog(ReadOptions.defaults(roots), logs.readAccess(), StoreReadCatalog.DEFAULT_SESSION_INDEX_BYTES);
  }
  public JsonObject identify(Authority caller, Path requested) throws Exception {
    caller.requireLocal(); var path = logs.validateSourcePath(requested);
    if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wpilog")) throw new IOException("SOURCE_UNAVAILABLE: recorded preview requires a WPILOG");
    var result = new JsonObject(); var ref = new JsonObject();
    try (var catalog = catalog()) {
      if (StoreCatalog.containing(path).isPresent()) {
        if (catalog == null) throw new IOException("SOURCE_UNAVAILABLE: no configured store catalog");
        var file = catalog.resolve(path).orElseThrow(() -> new IOException("SOURCE_UNAVAILABLE: file is not manifested"));
        if (file.facts().session() != null && file.facts().session().openCapture() != null
            && file.facts().file().sha256() == null) throw new IOException("SOURCE_UNAVAILABLE: live visualization arrives in Stage 3");
        var store = catalog.snapshot().stores().stream().filter(s -> s.header().id().equals(file.storeId())).findFirst().orElseThrow();
        ref.addProperty("kind", "recorded"); ref.addProperty("origin_store_id", StoreReadCatalog.originId(store));
        ref.addProperty("content_sha256", file.facts().file().sha256());
        result.add("catalog", StoreJson.JSON.toJsonTree(file.facts()));
      } else {
        var locator = new JsonObject(); locator.addProperty("path", path.toString()); locator.add("fingerprint", fingerprint(path));
        var id = UUID.nameUUIDFromBytes(locator.toString().getBytes(StandardCharsets.UTF_8));
        views.rememberSource(caller, id, locator).get();
        ref.addProperty("kind", "unmanaged"); ref.addProperty("source_id", id.toString());
        result.add("catalog", JsonNull.INSTANCE);
      }
    }
    result.add("source_ref", VisualizationContracts.source(ref)); result.addProperty("label", path.getFileName().toString());
    result.addProperty("path", path.toString()); result.addProperty("size_bytes", Files.size(path));
    result.addProperty("inventory_state", "not_requested"); return result;
  }
  public Path resolve(JsonObject source) throws IOException {
    var ref = VisualizationContracts.source(source);
    if (ref.get("kind").getAsString().equals("recorded")) {
      try (var catalog = catalog()) {
        if (catalog == null) throw new IOException("SOURCE_UNAVAILABLE: originating store is not configured");
        String expected = ref.get("content_sha256").getAsString();
        var path = logs.validateSourcePath(catalog.resolveContent(ref.get("origin_store_id").getAsString(), expected).facts().path());
        var boundary = FileSnapshot.of(path);
        try {
          verified.get(new ContentBoundary(path, boundary, expected), key -> {
            try {
              if (!expected.equals(LogStore.contentHash(path))) throw new IOException("SOURCE_UNAVAILABLE: recorded bytes no longer match their manifested content hash");
              return true;
            } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
          });
        } catch (java.io.UncheckedIOException e) { throw e.getCause(); }
        if (!Objects.equals(boundary, FileSnapshot.of(path))) throw new IOException("SOURCE_UNAVAILABLE: recorded file changed during content verification");
        return path;
      }
    }
    if (!ref.get("kind").getAsString().equals("unmanaged")) throw new IOException("SOURCE_UNAVAILABLE: live sources require Stage 3");
    var locator = views.sourceLocator(UUID.fromString(ref.get("source_id").getAsString()));
    var path = logs.validateSourcePath(Path.of(locator.get("path").getAsString()));
    if (!locator.get("fingerprint").equals(fingerprint(path))) throw new IOException("SOURCE_UNAVAILABLE: the recorded file changed; open its new overview");
    return path;
  }
  private static JsonObject fingerprint(Path path) throws IOException {
    var value = FileSnapshot.of(path);
    if (value == null || !Files.isRegularFile(path)) throw new IOException("SOURCE_UNAVAILABLE: source is not a regular file");
    var json = new JsonObject(); json.addProperty("size", value.size()); json.addProperty("modified", value.modified().toString());
    json.addProperty("identity", Objects.toString(value.fileKey(), null)); json.addProperty("created", Objects.toString(value.created(), null)); return json;
  }
  public ReadSnapshot open(JsonObject source) throws IOException {
    var path = resolve(source); var snapshot = logs.readAccess().open(path);
    try {
      if (snapshot.live()) throw new IOException("SOURCE_UNAVAILABLE: active capture needs the live preview");
      resolve(source); return snapshot;
    } catch (IOException | RuntimeException e) { snapshot.close(); throw e; }
  }
  public JsonObject describe(JsonObject source) throws IOException {
    try (var snapshot = open(source)) {
      var out = ReadWire.descriptor("", snapshot); var entries = new JsonArray();
      snapshot.entries().values().stream().sorted(Comparator.comparing(EntryInfo::name)).forEach(info -> {
        var item = new JsonObject(); item.addProperty("name", info.name()); item.addProperty("type", info.type());
        item.addProperty("metadata", info.metadata()); item.addProperty("sample_count", snapshot.sampleCount(info.name())); entries.add(item);
      });
      out.add("entries", entries); out.addProperty("inventory_state", "available");
      // Borrow the existing index metadata, with its lease; no second timestamp scan or decoder.
      try (var use = logs.acquireReadOnly(snapshot.path().toString())) {
        var log = use.log();
        if (Double.isFinite(log.minTimestamp()) && Double.isFinite(log.maxTimestamp())) {
          out.addProperty("start_time_sec", log.minTimestamp()); out.addProperty("end_time_sec", log.maxTimestamp());
          out.addProperty("duration_sec", log.duration());
        } else {
          out.add("start_time_sec", JsonNull.INSTANCE); out.add("end_time_sec", JsonNull.INSTANCE); out.add("duration_sec", JsonNull.INSTANCE);
        }
      }
      resolve(source);
      out.addProperty("clock_basis", "Recorded robot timestamps in seconds; independent source clocks are not aligned");
      out.addProperty("label", snapshot.path().getFileName().toString()); return out;
    }
  }
}
