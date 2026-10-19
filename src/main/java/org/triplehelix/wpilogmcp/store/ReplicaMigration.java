/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.log.LogFileAccess;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.store.StoreManifest.Header;

/** Explicit, payload-preserving upgrade. Hashing and inventory construction occur off the store
 * queue; publication rechecks the prepared filesystem boundaries under the store owner. The old
 * header and all payloads/journals remain, so a failed or retried upgrade cannot discard history. */
final class ReplicaMigration {
  private ReplicaMigration() {}
  record Prepared(Header before, Header after, Map<Path, FileSnapshot> boundaries,
      List<LogFileAccess.Lease> leases) implements AutoCloseable {
    @Override public void close() { leases.forEach(LogFileAccess.Lease::close); }
  }

  static Prepared prepare(Path root, SecurityValidator security, Clock clock) throws IOException {
    var io = new StoreFiles(root, security);
    var header = io.read(root.resolve("store.json"), Header.class);
    if (!StoreManifest.readable(header.formatVersion())) throw new IOException("Unsupported store format");
    if (header.replicas() != null) return new Prepared(header, header, Map.of(), List.of());
    var boundaries = new LinkedHashMap<Path, FileSnapshot>();
    boundaries.put(root.resolve("store.json"), FileSnapshot.of(root.resolve("store.json")));
    // Directory boundaries detect newly created sessions/robots while hashing is off-queue.
    // Existing receipts are also pinned, including sessions with only an open member.
    try (var paths = Files.walk(root)) {
      var iterator = paths.iterator(); int count = 0;
      while (iterator.hasNext()) {
        var path = io.check(iterator.next());
        if (++count > 32768) throw new IOException("Migration inventory exceeds 32768 paths; no migration performed");
        if (Files.isDirectory(path) || path.getFileName().toString().endsWith(".json"))
          boundaries.put(path, FileSnapshot.of(path));
      }
    }
    var catalog = StoreCatalog.readManaged(root, security);
    if (!header.equals(catalog.header())) throw new IOException("Store changed during migration preparation; retry");
    var leases = new ArrayList<LogFileAccess.Lease>();
    try {
      if (catalog.files().size() > ReplicaCatalog.MAX_FILES) throw new IOException("Replica catalog exceeds 4096 files; no migration performed");
      var entries = new ArrayList<ReplicaCatalog.Entry>();
      String epoch = UUID.randomUUID().toString();
      for (var file : catalog.files()) {
        if (file.session() != null && !MirrorSync.finalized(file.session()).contains(file.file())) continue;
        var facts = file.file();
        if (!facts.verified() || facts.sha256() == null || facts.provenance() == null) throw new IOException("Incomplete finalized-file receipt");
        security.validate(file.path()); leases.add(LogFileAccess.read(file.path()));
        var boundary = FileSnapshot.of(file.path());
        if (boundary == null || boundary.size() != facts.sizeBytes() || !facts.sha256().equals(StoreFiles.hash(file.path()))
            || !boundary.equals(FileSnapshot.of(file.path()))) throw new IOException("Migration content differs from its receipt: " + file.path());
        boundaries.put(file.path(), boundary);
        var manifestBoundary = FileSnapshot.of(file.manifestPath());
        Object manifest = file.session() == null ? io.read(file.manifestPath(), StoreManifest.LogFile.class)
            : io.read(file.manifestPath(), StoreManifest.Session.class);
        if (!manifest.equals(file.session() == null ? file.file() : file.session())
            || !java.util.Objects.equals(manifestBoundary, FileSnapshot.of(file.manifestPath())))
          throw new IOException("Store receipt changed during migration preparation; retry");
        var prior = boundaries.putIfAbsent(file.manifestPath(), manifestBoundary);
        if (prior != null && !prior.equals(manifestBoundary)) throw new IOException("Store receipt changed during migration preparation; retry");
        String key = UUID.randomUUID().toString();
        String basis = facts.provenance().captured() ? "closed_capture" : "completed_import";
        ReplicaCatalog.Identity identity = header.mirror() ? null
            : new ReplicaCatalog.Identity(header.id(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        ReplicaCatalog.Confirmation confirmation = identity == null ? null : new ReplicaCatalog.Confirmation(
            new ReplicaCatalog.MasterRecord(identity, epoch, 1, facts.sha256(), facts.sizeBytes(), basis),
            "local_master_catalog", clock.instant().toString());
        entries.add(new ReplicaCatalog.Entry(key, identity, StoreFiles.relative(root, file.path()), facts.sha256(), facts.sizeBytes(),
            facts.kind(), file.session() == null ? null : file.session().id(), basis,
            header.mirror() ? "legacy_unconfirmed" : "master_local", true, confirmation));
      }
      var included = header.origin() == null ? List.<String>of() : header.origin().pinnedSessions();
      var next = header.withReplicas(new ReplicaCatalog(1, epoch, 1, entries, included, List.of()));
      if (StoreFiles.JSON.toJson(next).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > StoreFiles.MAX_MANIFEST_BYTES)
        throw new IOException("Replica metadata exceeds the 4 MiB manifest budget; no migration performed");
      return new Prepared(header, next, Map.copyOf(boundaries), List.copyOf(leases));
    } catch (IOException | RuntimeException failure) { leases.forEach(LogFileAccess.Lease::close); throw failure; }
  }

  static Header publish(Path root, StoreFiles io, Prepared prepared) throws IOException {
    var current = io.read(root.resolve("store.json"), Header.class);
    if (!current.equals(prepared.before())) throw new IOException("STALE_MIGRATION: store metadata changed; retry");
    if (current.replicas() != null) return current;
    for (var entry : prepared.boundaries().entrySet()) {
      io.check(entry.getKey());
      if (!entry.getValue().equals(FileSnapshot.of(entry.getKey()))) throw new IOException("STALE_MIGRATION: prepared file changed; retry");
    }
    var directory = io.check(root.resolve(".replicas")); Files.createDirectories(directory);
    var backup = io.check(directory.resolve("format-1-store.json"));
    if (Files.exists(backup)) {
      if (!io.read(backup, Header.class).equals(current)) throw new IOException("Migration backup differs; preserve it and inspect before retrying");
    } else {
      io.writeDurable(backup, current);
    }
    // A failed journal or marker write leaves the format-1 header readable. A retry prepares
    // fresh identities and replaces only the unpublished intent, never any recording bytes.
    io.writeDurable(directory.resolve("upgrade.json"), prepared.after());
    io.writeDurable(root.resolve("store.json"), prepared.after());
    return prepared.after();
  }
}
