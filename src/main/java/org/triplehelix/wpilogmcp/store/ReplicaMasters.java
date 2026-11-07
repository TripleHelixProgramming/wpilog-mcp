/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Completed writer receipts assign identities, never filenames, mtime stability or peer claims.
 * This bounded metadata step runs in the caller's existing store mutation turn after its receipt
 * commits. Publishing the header last cannot advertise an unfinished member. */
final class ReplicaMasters {
  private ReplicaMasters() {}
  static final String PENDING = ".replicas/master-pending.json";
  private record Pending(String path, String sha256, String kind, String previousSha256,
      String failureCode, String failureMessage, String failedAt, String firstFailedAt) {
    Pending(String path, String sha256, String kind) { this(path, sha256, kind, null, null, null, null, null); }
    Pending(String path, String sha256, String kind, String previous, ReplicaPublications.Cause cause) {
      this(path, sha256, kind, previous, cause.code(), cause.message(), cause.failedAt(), cause.firstFailedAt());
    }
    ReplicaPublications.Cause cause() { return ReplicaPublications.recorded(failureCode, firstFailedAt, failedAt); }
  }
  /** One bounded receipt intent bridges the two atomic manifests. Recovery never guesses
   * completion from a filename or adopts a receipt which differs from the prepared bytes. */
  static void prepare(Path root, StoreFiles io, Path path, Object value, Path serialized) throws IOException {
    if (!(value instanceof StoreManifest.Session) && !(value instanceof StoreManifest.LogFile)) return;
    if (!Files.exists(root.resolve("store.json"))) return;
    var header = io.read(root.resolve("store.json"), StoreManifest.Header.class);
    if (header.mirror() || header.replicas() == null) return;
    recover(root, io);
    preflight(root, io, path, value);
    io.writeDurable(io.check(root.resolve(PENDING)), new Pending(StoreFiles.relative(root, path), StoreFiles.hash(serialized),
        value instanceof StoreManifest.Session ? "session" : "file"));
  }
  static void recover(Path root, StoreFiles io) throws IOException {
    var intent = io.check(root.resolve(PENDING)); if (!Files.exists(intent)) return;
    var pending = io.read(intent, Pending.class);
    var receipt = io.resolve(root, pending.path());
    if (!Set.of("session", "file").contains(pending.kind()) || !pending.sha256().matches("[0-9a-f]{64}"))
      throw new IOException("Invalid master publication intent");
    if (Files.exists(receipt) && Files.size(receipt) <= StoreFiles.MAX_MANIFEST_BYTES && StoreFiles.hash(receipt).equals(pending.sha256())) {
      Object value = pending.kind().equals("session") ? io.read(receipt, StoreManifest.Session.class) : io.read(receipt, StoreManifest.LogFile.class);
      published(root, io, receipt, value);
    }
    Files.deleteIfExists(intent);
  }
  static final String CAPTURE_PENDING = ".replica-publication.json";
  /** Each capture receipt owns its recovery marker, so a failed publication cannot serialize
   * admission of the next recording behind a full catalog. Remember the currently committed
   * receipt too: a crash replacing its successor must not erase an earlier pending publication.
   * Markers are bounded individually;
   * the explicit recovery sweep inspects at most 32768 paths, never on the NT event loop. */
  static void prepareCapture(Path root, StoreFiles io, Path path, Object value, Path serialized) throws IOException {
    if (!(value instanceof StoreManifest.Session session)) return;
    boolean completed = session.files().stream().anyMatch(f -> f.verified() && f.sha256() != null
        && (session.openCapture() == null || !session.openCapture().path().equals(f.path()))) || !session.clocks().isEmpty();
    if (!completed) return;
    String previous = Files.exists(path) && Files.size(path) <= StoreFiles.MAX_MANIFEST_BYTES ? StoreFiles.hash(path) : null;
    var marker = path.resolveSibling(CAPTURE_PENDING);
    io.writeDurable(marker, new Pending(StoreFiles.relative(root, path), StoreFiles.hash(serialized),
        "session", previous, priorCause(io, marker)));
  }
  private static ReplicaPublications.Cause priorCause(StoreFiles io, Path marker) {
    try { return Files.exists(marker) ? io.read(marker, Pending.class).cause() : ReplicaPublications.unknown(); }
    catch (IOException | RuntimeException unavailable) { return ReplicaPublications.unknown(); }
  }

  /** The cause is diagnostic only; receipt hashes remain the sole publication witnesses. */
  static ReplicaPublications.Event captureFailed(Path root, StoreFiles io, Path receipt,
      StoreManifest.Session session, Throwable failure, java.time.Clock clock) {
    var marker = receipt.resolveSibling(CAPTURE_PENDING);
    var previous = priorCause(io, marker);
    var live = io.publicationCause(receipt);
    if (live != null && live.firstFailedAt() != null && (previous.firstFailedAt() == null
        || Instant.parse(live.firstFailedAt()).isBefore(Instant.parse(previous.firstFailedAt())))) previous = live;
    var cause = ReplicaPublications.failed(failure, previous, clock.instant(), true);
    try {
      io.beforeCaptureAnnotation(marker);
      var pending = io.read(marker, Pending.class);
      io.writeDurable(marker, new Pending(pending.path(), pending.sha256(), pending.kind(), pending.previousSha256(), cause));
    } catch (IOException | RuntimeException annotation) {
      cause = new ReplicaPublications.Cause(cause.code(), cause.message(), cause.firstFailedAt(), cause.failedAt(), false);
    }
    return new ReplicaPublications.Event(receipt, new ReplicaPublications.Entry(session.id(), cause));
  }

  /** Only one bounded receipt is retained between off-owner inspection and its short owner turn. */
  record CaptureObservation(Path marker, Path receipt, Pending pending, StoreManifest.Session session,
      String markerHash, String receiptHash) {}

  static CaptureObservation inspectCapture(Path root, StoreFiles io, Path marker) throws IOException {
    io.check(marker);
    if (Files.size(marker) > StoreFiles.MAX_MANIFEST_BYTES) throw new IOException("Invalid capture publication intent");
    String markerHash = StoreFiles.hash(marker);
    var pending = io.read(marker, Pending.class);
    var receipt = io.resolve(root, pending.path());
    if (!receipt.getParent().equals(marker.getParent()) || !"session".equals(pending.kind())
        || pending.sha256() == null || !pending.sha256().matches("[0-9a-f]{64}")
        || pending.previousSha256() != null && !pending.previousSha256().matches("[0-9a-f]{64}"))
      throw new IOException("Invalid capture publication intent");
    String committed = Files.exists(receipt) && Files.size(receipt) <= StoreFiles.MAX_MANIFEST_BYTES ? StoreFiles.hash(receipt) : null;
    StoreManifest.Session session = null;
    if (committed != null && (committed.equals(pending.sha256()) || committed.equals(pending.previousSha256()))) {
      session = io.read(receipt, StoreManifest.Session.class);
      validateCaptureReceipt(io, receipt, session);
      if (!committed.equals(StoreFiles.hash(receipt))) throw new IOException("Capture receipt changed during inspection");
    }
    if (!markerHash.equals(StoreFiles.hash(marker))) throw new IOException("Capture intent changed during inspection");
    return new CaptureObservation(marker, receipt, pending, session, markerHash, committed);
  }

  /** Bounded metadata hashes are rechecked under store.lock; tree/payload reads stay off-owner. */
  static ReplicaPublications.Event retryCapture(Path root, StoreFiles io, CaptureObservation observed,
      java.time.Clock clock) throws IOException {
    if (!Files.isRegularFile(io.check(observed.marker()))
        || Files.size(observed.marker()) > StoreFiles.MAX_MANIFEST_BYTES
        || !observed.markerHash().equals(StoreFiles.hash(observed.marker())))
      throw new IOException("Capture intent changed before publication");
    io.check(observed.receipt());
    String committed = Files.exists(observed.receipt()) && Files.size(observed.receipt()) <= StoreFiles.MAX_MANIFEST_BYTES
        ? StoreFiles.hash(observed.receipt()) : null;
    if (!Objects.equals(committed, observed.receiptHash())) throw new IOException("Capture receipt changed before publication");
    if (observed.session() != null) {
      validateCaptureReceipt(io, observed.receipt(), observed.session());
      try { published(root, io, observed.receipt(), observed.session()); }
      catch (IOException | RuntimeException failed) {
        return captureFailed(root, io, observed.receipt(), observed.session(), failed, clock);
      }
    }
    try { Files.delete(io.check(observed.marker())); }
    catch (IOException failed) {
      if (observed.session() == null) throw failed;
      return captureFailed(root, io, observed.receipt(), observed.session(), failed, clock);
    }
    return new ReplicaPublications.Event(observed.receipt(), null);
  }

  /** A receipt hash proves its bytes, not their safety. Unsafe references must fail recovery
   * before the publication-only catch can let recording continue beside an invalid receipt. */
  private static void validateCaptureReceipt(StoreFiles io, Path receipt, StoreManifest.Session session)
      throws IOException {
    if (session.id() == null || session.id().isBlank() || session.files() == null)
      throw new IOException("Invalid capture publication session");
    if (session.openCapture() != null) io.resolve(receipt.getParent(), session.openCapture().path());
    for (var file : session.files()) {
      if (file == null) throw new IOException("Invalid capture publication file");
      io.resolve(receipt.getParent(), file.path());
      if (file.verified() && file.sha256() != null && file.provenance() != null
          && (!file.sha256().matches("[0-9a-f]{64}") || file.sizeBytes() < 0
              || !Set.of("wpilog", "revlog").contains(file.kind())))
        throw new IOException("Invalid completed capture publication file");
    }
    for (var clock : session.clocks()) {
      ClockRecords.resolve(io, receipt.getParent(), clock);
      io.resolve(receipt.getParent(), clock.capture());
    }
  }
  /** A store-issued move is an explicit alias. Equal bytes elsewhere are never an alias. */
  static StoreManifest.Header moved(Path root, StoreManifest.Header header) throws IOException {
    var catalog = header.replicas(); if (catalog == null || header.mirror() || header.moves().isEmpty()) return header;
    var updated = new LinkedHashMap<String, ReplicaCatalog.Entry>(); boolean changed = false;
    for (var file : catalog.files()) {
      String target = file.path();
      for (var move : header.moves()) if (Path.of(move.originalPath()).toAbsolutePath().normalize().equals(root.resolve(file.path()).normalize()))
        target = move.movedTo();
      var next = file;
      if (!target.equals(file.path())) {
        changed = true;
        next = new ReplicaCatalog.Entry(file.key(), file.identity(), target, file.sha256(), file.sizeBytes(), file.kind(), file.sessionId(),
            file.completionBasis(), file.verificationBasis(), file.transferVerified(), file.confirmation(), file.descriptor(), file.placement());
      }
      var duplicate = updated.putIfAbsent(target, next);
      if (duplicate != null && (!duplicate.sha256().equals(next.sha256()) || duplicate.sizeBytes() != next.sizeBytes()))
        throw new IOException("AUTHORITY_CONFLICT: moved version conflicts with its destination");
    }
    return changed ? header.withReplicas(new ReplicaCatalog(1, catalog.epoch(), Math.addExact(catalog.revision(), 1),
        List.copyOf(updated.values()), catalog.includedSessions(), catalog.exclusions())) : header;
  }
  static void published(Path root, StoreFiles io, Path path, Object value) throws IOException {
    if (!(value instanceof StoreManifest.Session) && !(value instanceof StoreManifest.LogFile)) return;
    if (!Files.exists(root.resolve("store.json"))) return;
    var header = io.read(root.resolve("store.json"), StoreManifest.Header.class);
    if (header.mirror() || header.replicas() == null) return;
    var next = candidate(root, io, path, value, header);
    if (next != header) io.writeDurable(root.resolve("store.json"), next);
  }
  /** Check count and encoded budgets before a receipt or its imported payload is committed. */
  static void preflight(Path root, StoreFiles io, Path path, Object value) throws IOException {
    recover(root, io);
    var header = io.read(root.resolve("store.json"), StoreManifest.Header.class);
    if (header.mirror() || header.replicas() == null) return;
    try { ReplicaCatalogFiles.preflight(candidate(root, io, path, value, header)); }
    catch (IllegalArgumentException limit) { throw new ReplicaCatalogFiles.CapacityRefused("RESOURCE_LIMIT: replica publication refused before receipt commit: " + limit.getMessage(), limit); }
  }
  private static StoreManifest.Header candidate(Path root, StoreFiles io, Path path, Object value, StoreManifest.Header header) throws IOException {
    var session = value instanceof StoreManifest.Session s ? s : null;
    var files = session == null ? List.of((StoreManifest.LogFile) value) : session.files().stream()
        .filter(f -> session.openCapture() == null || !session.openCapture().path().equals(f.path())).toList();
    var catalog = header.replicas(); var entries = new ArrayList<>(catalog.files());
    long revision = Math.addExact(catalog.revision(), 1); boolean changed = false;
    for (var file : files) {
      if (!file.verified() || file.sha256() == null || file.provenance() == null) continue;
      var absolute = io.resolve(path.getParent(), file.path());
      String relative = StoreFiles.relative(root, absolute);
      var old = entries.stream().filter(f -> f.path().equals(relative)).findFirst().orElse(null);
      var placement = placement(root, path, session, "master_receipt", new ReplicaCatalog.Relationships(file.provenance(), file.matching(), null, null));
      if (old != null) {
        if (!old.sha256().equals(file.sha256()) || old.sizeBytes() != file.sizeBytes())
          throw new IOException("AUTHORITY_CONFLICT: completed file changed without a new immutable path");
        if (!Objects.equals(old.placement(), placement)) {
          entries.set(entries.indexOf(old), new ReplicaCatalog.Entry(old.key(), old.identity(), old.path(), old.sha256(), old.sizeBytes(), old.kind(),
              old.sessionId(), old.completionBasis(), old.verificationBasis(), old.transferVerified(), old.confirmation(), old.descriptor(), placement));
          changed = true;
        }
        continue;
      }
      var identity = new ReplicaCatalog.Identity(header.id(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
      String basis = file.provenance().captured() ? "closed_capture" : "completed_import";
      var confirmation = new ReplicaCatalog.Confirmation(new ReplicaCatalog.MasterRecord(identity, catalog.epoch(), revision,
          file.sha256(), file.sizeBytes(), basis), "local_master_catalog", Instant.now().toString());
      entries.add(new ReplicaCatalog.Entry(UUID.randomUUID().toString(), identity, relative, file.sha256(), file.sizeBytes(),
          file.kind(), session == null ? null : session.id(), basis, "master_local", true, confirmation, null,
          placement));
      changed = true;
    }
    if (session != null) changed |= companions(root, io, header, entries, path, session, catalog.epoch(), revision, Instant.now().toString());
    return changed ? header.withReplicas(new ReplicaCatalog(1, catalog.epoch(), revision,
        entries, catalog.includedSessions(), catalog.exclusions())) : header;
  }
  static ReplicaCatalog.Placement placement(Path root, Path manifest, StoreManifest.Session session, String basis) {
    return placement(root, manifest, session, basis, null);
  }
  static ReplicaCatalog.Placement placement(Path root, Path manifest, StoreManifest.Session session, String basis, ReplicaCatalog.Relationships relationships) {
    var relative = root.relativize(manifest); String robot = null;
    if (relative.getNameCount() >= 2 && relative.getName(0).toString().equals("robots")) robot = relative.getName(1).toString();
    return new ReplicaCatalog.Placement(robot, session == null ? null : session.id(), session == null ? null : session.event(),
        session == null ? null : session.endedAt(), session == null ? "unknown" : session.startBasis(),
        session == null ? "finalized_file" : session.openCapture() == null ? "finalized" : "growing", basis, relationships);
  }
  /** Only the closed-clock receipt is authoritative here. Payload validation belongs to migration
   * preparation or the transfer worker, outside this bounded store publication turn. */
  static boolean companions(Path root, StoreFiles io, StoreManifest.Header header, List<ReplicaCatalog.Entry> entries,
      Path manifest, StoreManifest.Session session, String epoch, long revision, String now) throws IOException {
    boolean changed = false;
    for (var clock : session.clocks()) {
      ClockRecords.validate(clock);
      if (session.openCapture() != null && session.openCapture().path().equals(clock.capture())) continue;
      String path = StoreFiles.relative(root, ClockRecords.resolve(io, manifest.getParent(), clock));
      var old = entries.stream().filter(f -> f.path().equals(path)).findFirst().orElse(null);
      if (old != null) {
        if (!old.sha256().equals(clock.sha256()) || old.sizeBytes() != clock.sizeBytes())
          throw new IOException("AUTHORITY_CONFLICT: closed clock changed without a new immutable path");
        continue;
      }
      String capture = StoreFiles.relative(root, io.resolve(manifest.getParent(), clock.capture()));
      var identity = header.mirror() ? null : new ReplicaCatalog.Identity(header.id(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
      var related = entries.stream().filter(f -> f.path().equals(capture) && f.sha256().equals(clock.captureSha256())).map(ReplicaCatalog.Entry::identity)
          .filter(Objects::nonNull).findFirst().orElse(null);
      var confirmation = identity == null ? null : new ReplicaCatalog.Confirmation(new ReplicaCatalog.MasterRecord(identity, epoch, revision,
          clock.sha256(), clock.sizeBytes(), "closed_clock"), "local_master_catalog", now);
      entries.add(new ReplicaCatalog.Entry(UUID.randomUUID().toString(), identity, path, clock.sha256(), clock.sizeBytes(), "clock_record", session.id(),
          "closed_clock", header.mirror() ? "legacy_unconfirmed" : "master_local", true, confirmation, null,
          placement(root, manifest, session, header.mirror() ? "legacy_claim" : "master_receipt", new ReplicaCatalog.Relationships(null, null, clock, related))));
      changed = true;
    }
    return changed;
  }
}
