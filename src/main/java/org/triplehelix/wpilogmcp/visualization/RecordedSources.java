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
import org.triplehelix.wpilogmcp.read.server.CatalogRecordingSource;
import org.triplehelix.wpilogmcp.read.server.VerifiedContentHashes;
import org.triplehelix.wpilogmcp.store.*;

/** Source admission does no value decoding. Locators stay local; catalog identities stay portable.
 * Each operation rechecks the current grants and fixed file boundary before using a source. */
public final class RecordedSources implements AutoCloseable {
  private final VerifiedContentHashes verified;
  private final LogManager logs;
  private final VisualizationService views;
  private final StoreDoor door;
  public RecordedSources(LogManager logs, VisualizationService views, StoreDoor door) {
    this(logs, views, door, LogStore::contentHash);
  }
  RecordedSources(LogManager logs, VisualizationService views, StoreDoor door, VerifiedContentHashes.Hasher hash) {
    this.logs = logs; this.views = views; this.door = door;
    verified = new VerifiedContentHashes(hash);
  }
  private final Object catalogLock = new Object();
  private Catalog current;
  private boolean closed;
  private static final class Catalog {
    final List<Path> roots; final StoreReadCatalog value; int users; boolean retired;
    Catalog(List<Path> roots, StoreReadCatalog value) { this.roots = roots; this.value = value; }
  }
  private final class Lease implements CatalogRecordingSource.Lease {
    final Catalog owner; Lease(Catalog owner) { this.owner = owner; }
    public StoreReadCatalog value() { return owner.value; }
    public void close() throws IOException {
      boolean release;
      synchronized (catalogLock) { release = --owner.users == 0 && owner.retired; }
      if (release && owner.value != null) owner.value.close();
    }
  }
  private Lease catalog() throws IOException {
    var roots = door.stores().stream().map(StoreDoor.Selected::root).sorted().toList();
    synchronized (catalogLock) {
      if (closed) throw new IOException("Source catalog is closed");
      if (current != null && current.roots.equals(roots)) { current.users++; return new Lease(current); }
    }
    // Construction resolves filesystem roots; never hold the admission lock over that I/O.
    var fresh = new Catalog(roots, roots.isEmpty() ? null : new StoreReadCatalog(ReadOptions.defaults(roots), logs.readAccess(), StoreReadCatalog.DEFAULT_SESSION_INDEX_BYTES));
    Catalog retiring = null, discard = null, selected;
    synchronized (catalogLock) {
      if (closed) { discard = fresh; selected = null; }
      else if (current != null && current.roots.equals(roots)) { discard = fresh; selected = current; selected.users++; }
      else {
        if (current != null) { current.retired = true; if (current.users == 0) retiring = current; }
        current = fresh; fresh.users++; selected = fresh;
      }
    }
    if (discard != null && discard.value != null) discard.value.close();
    if (retiring != null && retiring.value != null) retiring.value.close();
    if (selected == null) throw new IOException("Source catalog is closed");
    return new Lease(selected);
  }
  @Override public void close() throws IOException {
    Catalog retiring = null;
    synchronized (catalogLock) {
      closed = true;
      if (current != null) { current.retired = true; if (current.users == 0) retiring = current; current = null; }
    }
    verified.close();
    if (retiring != null && retiring.value != null) retiring.value.close();
  }
  public JsonObject identify(Authority caller, Path requested) throws Exception {
    caller.requireLocal(); var path = logs.validateSourcePath(requested);
    if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wpilog")) throw new IOException("SOURCE_UNAVAILABLE: recorded preview requires a WPILOG");
    var result = new JsonObject(); var ref = new JsonObject();
    try (var lease = catalog()) {
      var catalog = lease.value();
      if (StoreCatalog.containing(path).isPresent()) {
        if (catalog == null) throw new IOException("SOURCE_UNAVAILABLE: no configured store catalog");
        var file = catalog.resolve(path).orElseThrow(() -> new IOException("SOURCE_UNAVAILABLE: file is not manifested"));
        var store = catalog.snapshot().stores().stream().filter(s -> s.header().id().equals(file.storeId())).findFirst().orElseThrow();
        String origin = StoreReadCatalog.originId(store);
        if (StoreReadCatalog.open(file.facts())) {
          var provenance = file.facts().file().provenance();
          if (CaptureRecording.of(provenance, origin).isEmpty()) throw new IOException("SOURCE_UNAVAILABLE: open file has no captured recording provenance");
          ref.addProperty("kind", "session"); ref.addProperty("origin_store_id", origin);
          ref.addProperty("session_id", file.facts().session().id());
          var anchor = new JsonObject(); anchor.addProperty("original_name", provenance.originalName()); anchor.addProperty("recording_started_at", provenance.importedAt());
          ref.add("anchor_member", anchor);
          // The catalog owns same-recording membership and alias rules; identity admission is
          // not a guess from the session directory, a filename, or an NT connection address.
          catalog.recording(VisualizationContracts.recordingReference(ref));
        } else {
          ref.addProperty("kind", "recorded"); ref.addProperty("origin_store_id", origin);
          ref.addProperty("content_sha256", file.facts().file().sha256());
        }
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
      try (var lease = catalog()) {
      var catalog = lease.value();
        if (catalog == null) throw new IOException("SOURCE_UNAVAILABLE: originating store is not configured");
        String expected = ref.get("content_sha256").getAsString();
        var path = logs.validateSourcePath(catalog.resolveContent(ref.get("origin_store_id").getAsString(), expected).facts().path());
        var boundary = FileSnapshot.of(path);
        verified.verify(path, boundary, expected);
        return path;
      }
    }
    if (!ref.get("kind").getAsString().equals("unmanaged")) throw new IOException("SOURCE_UNAVAILABLE: a recording requires ordinal follow, not a single-file data route; live panel integration is pending");
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
  public record Admission(org.triplehelix.wpilogmcp.read.server.OrdinalFollow.Prefix prefix,
      CatalogRecordingSource.Access access) implements AutoCloseable {
    @Override public void close() throws IOException { prefix.close(); }
  }
  public Admission admitRecording(JsonObject source) throws IOException {
    var ref = VisualizationContracts.source(source);
    if (!ref.get("kind").getAsString().equals("session")) throw new IllegalArgumentException("Paged inventory requires an anchored recording source_ref");
    var recording = new CatalogRecordingSource(logs, this::catalog, VisualizationContracts.recordingReference(ref), verified);
    var prefix = recording.open();
    try { return new Admission(prefix, recording.access()); }
    catch (RuntimeException failure) { prefix.close(); throw failure; }
  }
  public JsonObject describe(JsonObject source) throws IOException {
    var ref = VisualizationContracts.source(source);
    if (ref.get("kind").getAsString().equals("session")) {
      try (var admission = admitRecording(ref)) {
        var out = RecordingInventory.describe(admission.prefix());
        admission.access().check(); return out;
      }
    }
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
