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
    return identify(caller, requested, null, false);
  }
  public JsonObject identify(Authority caller, Path requested, String scopeId, boolean qualified) throws Exception {
    VisualizationAccess.transport(caller, qualified ? 2 : 1, VisualizationAccess.Operation.READ);
    if (!qualified && !requested.isAbsolute()) {
      var choices = logs.sourceScopes().snapshot();
      if (choices.size() > 1) throw new org.triplehelix.wpilogmcp.read.server.SourceScopes.Failure(
          "SOURCE_SELECTION_REQUIRED", "A relative file path requires an explicit source selection", choices,
          "Pass an absolute path as list_available_logs returns it, or use api_version 2 with scope_id.");
    }
    var path = logs.validateSourcePath(requested);
    var scope = qualified ? logs.sourceScopes().forPath(path, scopeId) : null;
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
          catalog.recording(qualified && scope != null ? scope.id() : null, VisualizationContracts.recordingReference(ref));
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
    if (qualified) {
      if (scope == null) throw new IOException("SOURCE_UNAVAILABLE: configure a source root before creating a qualified reference");
      var replica = logs.replicaEntry(path);
      if (replica != null && replica.identity() != null) {
        ref = new JsonObject(); ref.addProperty("kind", "file_version");
        ref.addProperty("master_store_id", replica.identity().masterStoreId()); ref.addProperty("file_id", replica.identity().fileId());
        ref.addProperty("file_version", replica.identity().fileVersion());
      }
      ref = VisualizationContracts.qualify(views.serverId(), scope.id(), ref);
    }
    result.add("source_ref", VisualizationContracts.source(ref)); result.addProperty("label", path.getFileName().toString());
    result.addProperty("path", path.toString()); result.addProperty("size_bytes", Files.size(path));
    result.addProperty("inventory_state", "not_requested"); return result;
  }
  private JsonObject checkOwner(JsonObject source) throws IOException {
    var ref = VisualizationContracts.source(source);
    if (ref.has("schema_version") && !views.serverId().toString().equals(ref.get("server_instance_id").getAsString()))
      throw new IOException("SOURCE_UNAVAILABLE: source belongs to another server instance");
    return ref;
  }
  private JsonObject authorize(JsonObject source) throws IOException {
    var ref = checkOwner(source);
    if (ref.has("schema_version")) logs.sourceScopes().select(VisualizationContracts.scopeId(ref));
    return ref;
  }
  /** Upgrade only an established serving location. Choosing the only matching bytes among
   * several copies would discard exactly the ambiguity schema 2 is intended to preserve. */
  public JsonObject qualify(JsonObject source, String scopeId) throws IOException {
    var ref = VisualizationContracts.source(source);
    if (ref.has("schema_version")) {
      if (scopeId != null && !scopeId.equals(VisualizationContracts.scopeId(ref)))
        throw new IOException("SOURCE_UNAVAILABLE: scope_id disagrees with the source reference");
      return authorize(ref);
    }
    String selected;
    if (VisualizationContracts.kind(ref).equals("unmanaged")) {
      var scope = logs.sourceScopes().forPath(resolve(ref), scopeId);
      if (scope == null) throw new IOException("SOURCE_UNAVAILABLE: configure a source root before qualification");
      selected = scope.id();
    } else try (var lease = catalog()) {
      if (lease.value() == null) throw new IOException("SOURCE_UNAVAILABLE: no configured store catalog");
      if (VisualizationContracts.kind(ref).equals("session")) {
        var recording = lease.value().recording(scopeId, VisualizationContracts.recordingReference(ref));
        if (recording.members().isEmpty()) throw new IOException("SOURCE_UNAVAILABLE: recording has no retained members");
        selected = recording.members().get(0).storeId();
      } else selected = lease.value().resolveContent(scopeId, ref.get("origin_store_id").getAsString(),
          ref.get("content_sha256").getAsString()).storeId();
    }
    return authorize(VisualizationContracts.qualify(views.serverId(), selected, ref));
  }
  /** Health checks manifest identity and grants without hashing, indexing, decoding or retaining a
   * prefix. It is an observation, never permission to reuse data: follows still prove every file. */
  public JsonObject health(JsonObject source, org.triplehelix.wpilogmcp.capture.LiveCapture capture) throws IOException {
    var ref = authorize(source);
    if (VisualizationContracts.kind(ref).equals("file_version")) {
      var content = VisualizationContracts.content(ref); var scope = logs.sourceScopes().select(VisualizationContracts.scopeId(ref));
      var identity = new org.triplehelix.wpilogmcp.store.ReplicaCatalog.Identity(content.get("master_store_id").getAsString(),
          content.get("file_id").getAsString(), content.get("file_version").getAsString());
      var path = logs.resolveReplica(scope.root(), identity); logs.sourceScopes().recheckPath(path, scope);
      var facts = logs.replicaFacts(path);
      var out = new JsonObject(); out.add("source_ref", ref); out.addProperty("acquisition_state", "inactive");
      out.addProperty("history_state", "finalized"); out.addProperty("reason", "Published replica generation; data admission separately verifies its bytes");
      out.add("connected", JsonNull.INSTANCE); out.add("clock_synchronized", JsonNull.INSTANCE); out.add("last_value_age_ms", JsonNull.INSTANCE);
      out.add("replica", org.triplehelix.wpilogmcp.store.StoreJson.JSON.toJsonTree(facts)); return out;
    }
    if (!VisualizationContracts.kind(ref).equals("session")) throw new IllegalArgumentException("Health requires an anchored recording source_ref");
    try (var lease = catalog()) {
      if (lease.value() == null) throw new IOException("SOURCE_UNAVAILABLE: originating recording store is not configured");
      var recording = lease.value().recording(VisualizationContracts.scopeId(ref), VisualizationContracts.recordingReference(ref));
      if (recording.members().size() > 64) throw new IOException("SOURCE_UNAVAILABLE: recording exceeds the 64-member read limit");
      for (var member : recording.members()) logs.validateSourcePath(member.facts().path());
      var observed = capture == null ? null : capture.health();
      boolean same = observed != null && recording.identity().equals(observed.recording());
      String state = !same ? "inactive" : !observed.connected() ? "reconnecting"
          : !observed.synchronizedClock() ? "waiting_for_time_sync" : observed.open() ? "recording" : "waiting_for_recording";
      String reason = switch (state) {
        case "reconnecting" -> "Acquisition disconnected; waiting for the same robot clock. " + Objects.toString(observed.reason(), "");
        case "waiting_for_time_sync" -> "Connected; waiting for the robot clock";
        case "waiting_for_recording" -> "Connected; waiting for recording admission";
        case "recording" -> "Acquisition connected; unchanged signals may remain quiet";
        default -> recording.finalized() ? "Finalized history; this recording is not currently acquiring"
            : "History is manifested as open, but no matching acquisition is running on this server";
      };
      var out = new JsonObject(); out.add("source_ref", ref);
      out.addProperty("acquisition_state", state); out.addProperty("history_state", recording.finalized() ? "finalized" : "live");
      out.addProperty("reason", reason.length() > 2048 ? reason.substring(0, 2048) : reason); out.addProperty("connected", same ? observed.connected() : null);
      out.addProperty("clock_synchronized", same ? observed.synchronizedClock() : null);
      out.addProperty("last_value_age_ms", same ? observed.lastValueAgeMs() : null);
      var timing = same ? capture.publicationTiming() : null;
      if (timing != null && !recording.identity().startedAt().equals(timing.recordingStartedAt())) timing = null;
      var measured = new JsonObject();
      if (timing != null) {
        measured.addProperty("writer_identity", timing.writerIdentity()); measured.addProperty("publication_revision", timing.revision());
        measured.addProperty("receive_to_publication_ms", timing.receiveToPublicationMs());
      }
      out.add("publication_timing", timing == null ? JsonNull.INSTANCE : measured);
      return out;
    }
  }
  public Path resolve(JsonObject source) throws IOException {
    var qualified = authorize(source); var ref = VisualizationContracts.content(qualified);
    String scope = VisualizationContracts.scopeId(qualified);
    if (ref.get("kind").getAsString().equals("file_version")) {
      var selected = logs.sourceScopes().select(scope);
      var identity = new org.triplehelix.wpilogmcp.store.ReplicaCatalog.Identity(ref.get("master_store_id").getAsString(),
          ref.get("file_id").getAsString(), ref.get("file_version").getAsString());
      var path = logs.resolveReplica(selected.root(), identity);
      logs.sourceScopes().recheckPath(path, selected);
      var file = logs.replicaEntry(path);
      verified.verify(path, FileSnapshot.of(path), file.sha256()); return path;
    }
    if (ref.get("kind").getAsString().equals("recorded")) {
      try (var lease = catalog()) {
      var catalog = lease.value();
        if (catalog == null) throw new IOException("SOURCE_UNAVAILABLE: originating store is not configured");
        String expected = ref.get("content_sha256").getAsString();
        var path = logs.validateSourcePath(catalog.resolveContent(scope, ref.get("origin_store_id").getAsString(), expected).facts().path());
        if (scope != null) logs.sourceScopes().forPath(path, scope);
        var boundary = FileSnapshot.of(path);
        verified.verify(path, boundary, expected);
        return path;
      }
    }
    if (!ref.get("kind").getAsString().equals("unmanaged")) throw new IOException("SOURCE_UNAVAILABLE: this single-file route cannot choose a recording member; use recording-aware value/statistics reads or ordinal follow");
    var locator = views.sourceLocator(UUID.fromString(ref.get("source_id").getAsString()));
    var path = logs.validateSourcePath(Path.of(locator.get("path").getAsString()));
    if (scope != null) logs.sourceScopes().forPath(path, scope);
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
      // A closed capture may retain its writer-built index in the cache. Its non-null disk
      // boundary is checked like any finalized file; only an active fixed prefix must refuse.
      if (snapshot.live() && snapshot.boundary() == null) throw new IOException("SOURCE_UNAVAILABLE: active capture needs the live preview");
      if (!snapshot.path().equals(resolve(source))) throw new IOException("SOURCE_CHANGED: selected version has a new generation");
      return snapshot;
    } catch (IOException | RuntimeException e) { snapshot.close(); throw e; }
  }
  public record Admission(org.triplehelix.wpilogmcp.read.server.OrdinalFollow.Prefix prefix,
      CatalogRecordingSource.Access access, org.triplehelix.wpilogmcp.read.server.OrdinalFollow.Source continuation) implements AutoCloseable {
    public Admission(org.triplehelix.wpilogmcp.read.server.OrdinalFollow.Prefix prefix, CatalogRecordingSource.Access access) {
      this(prefix, access, null);
    }
    /** A compatibility result cannot span replacement of a finalized member. Growing writer
     * prefixes have no file boundary and remain the immutable prefix admitted at open. */
    public void check() throws IOException {
      access.check();
      for (var member : prefix.members()) {
        var snapshot = member.snapshot(); var boundary = snapshot.boundary();
        if (boundary != null && !boundary.sameAs(FileSnapshot.of(snapshot.path())))
          throw new ReadException(ReadProblem.Code.FILE_CHANGED, "Recording member changed during compatibility; reopen and check again");
      }
    }
    @Override public void close() throws IOException { prefix.close(); }
  }
  /** Chart reads use the same fixed-prefix admission for an anchored recording or one file.
   * The synthetic single-member wrapper is internal; no recording identity is claimed for it. */
  Admission admitChart(JsonObject source) throws IOException {
    if (VisualizationContracts.kind(VisualizationContracts.source(source)).equals("session")) return admitRecording(source);
    var snapshot = open(source);
    try {
      var member = new org.triplehelix.wpilogmcp.read.server.OrdinalFollow.Member("file", 0, snapshot);
      var prefix = new org.triplehelix.wpilogmcp.read.server.OrdinalFollow.Prefix("single-file", 0, true, List.of(member));
      return new Admission(prefix, () -> {
        if (!snapshot.path().equals(resolve(source))) throw new IOException("SOURCE_CHANGED: selected version has a new generation");
      });
    } catch (RuntimeException failure) { snapshot.close(); throw failure; }
  }
  public Admission admitRecording(JsonObject source) throws IOException {
    var ref = checkOwner(source);
    if (!VisualizationContracts.kind(ref).equals("session")) throw new IllegalArgumentException("Paged inventory requires an anchored recording source_ref");
    String scopeId = VisualizationContracts.scopeId(ref);
    var scope = scopeId == null ? null : logs.sourceScopes().select(scopeId);
    var recording = new CatalogRecordingSource(logs, this::catalog, VisualizationContracts.recordingReference(ref), verified, scope);
    var prefix = recording.open();
    try { return new Admission(prefix, recording.access(), recording); }
    catch (RuntimeException failure) { prefix.close(); throw failure; }
  }
  public JsonObject describe(JsonObject source) throws IOException {
    var ref = VisualizationContracts.source(source);
    if (VisualizationContracts.kind(ref).equals("session")) {
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
