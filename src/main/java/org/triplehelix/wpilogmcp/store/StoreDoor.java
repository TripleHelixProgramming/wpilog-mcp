/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.triplehelix.wpilogmcp.Version;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/** A read-only catalog boundary, shared by the peer and mirror readers. */
public final class StoreDoor {
  public record Description(String id, int formatVersion, String serverVersion, boolean mirror) {}
  public record Session(String robotId, String path, StoreManifest.Session manifest) {}
  public record Unassigned(String path, StoreManifest.LogFile file) {}
  public record SharedSystem(String robotId, List<SystemLogIndex.Entry> files) {}
  public record Sessions(List<Session> sessions, List<Unassigned> unassigned, List<SharedSystem> systemLogs) {
    public Sessions { systemLogs = systemLogs == null ? List.of() : List.copyOf(systemLogs); }
    public Sessions(List<Session> sessions, List<Unassigned> unassigned) { this(sessions, unassigned, List.of()); }
  }
  /** Index entries are exported once per robot; a session selects them by written span. */
  static List<SystemLogIndex.Entry> systemFiles(Sessions catalog, Session session) {
    var result = new java.util.LinkedHashMap<String, SystemLogIndex.Entry>();
    session.manifest().systemLogs().files().forEach(f -> result.put(SystemLogFiles.remotePath(session.path(), f), new SystemLogIndex.Entry(f, null)));
    for (var robot : catalog.systemLogs()) if (robot.robotId().equals(session.robotId())) {
      for (var entry : robot.files()) if (SystemLogIndex.applies(entry, session.manifest())) result.put(entry.file().path(), entry);
    }
    return List.copyOf(result.values());
  }
  public record Selected(Path root, Description description, SecurityValidator security) {}
  public record Unreadable(String path, String reason) {}
  public record Inventory(List<Selected> stores, List<Unreadable> unreadable) {}
  private record Discovery(Set<Path> configured, List<Path> roots, long at) {}
  private final Supplier<Set<Path>> configured;
  private volatile Discovery discovered;

  public StoreDoor(Supplier<Set<Path>> configured) { this.configured = configured; }

  /** Remembered peers are for the local command picker, never part of the public door. */
  public List<String> peers(Selected selected) throws IOException {
    return new StoreFiles(selected.root(), selected.security()).read(selected.root().resolve("store.json"), StoreManifest.Header.class).peers();
  }

  /** Only store discovery is cached; manifests and growing-file lengths are read on demand. */
  public List<Selected> stores() throws IOException {
    return inventory().stores();
  }

  /** A damaged neighbor must not hide stores whose catalogs remain readable. */
  public Inventory inventory() throws IOException {
    var directories = Set.copyOf(configured.get());
    var security = new SecurityValidator(); directories.forEach(security::addAllowedDirectory);
    var seen = discovered;
    if (seen == null || !seen.configured().equals(directories) || System.nanoTime() - seen.at() > 5_000_000_000L) {
      var roots = new ArrayList<Path>();
      for (var directory : directories) roots.addAll(StoreCatalog.discover(directory));
      seen = new Discovery(directories, roots.stream().distinct().sorted().toList(), System.nanoTime());
      discovered = seen;
    }
    var result = new ArrayList<Selected>();
    var unreadable = new ArrayList<Unreadable>();
    for (var root : seen.roots()) {
      try {
        security.validate(root);
        var header = new StoreFiles(root, security).read(root.resolve("store.json"), StoreManifest.Header.class);
        if (header.formatVersion() != StoreManifest.FORMAT_VERSION || header.id() == null) {
          throw new IOException("Unsupported or incomplete store manifest");
        }
        result.add(new Selected(root, new Description(header.id(), header.formatVersion(), Version.VERSION, header.mirror()), security));
      } catch (IOException e) {
        unreadable.add(new Unreadable(root.toString(), e.getMessage()));
      }
    }
    return new Inventory(List.copyOf(result), List.copyOf(unreadable));
  }

  public Selected select(String id) throws IOException {
    var found = stores();
    if (id == null && found.size() == 1) return found.get(0);
    if (id != null) return found.stream().filter(s -> s.description().id().equals(id)).findFirst()
        .orElseThrow(() -> new IOException("Unknown configured store id"));
    throw new IOException(found.isEmpty() ? "No configured store" : "Select one store with ?store=<id> from GET /store");
  }

  public static Sessions sessions(Selected selected, String since, String robot, String event) throws IOException {
    Instant cutoff = since == null ? null : Instant.parse(since);
    var catalog = StoreCatalog.readManaged(selected.root(), selected.security());
    var sessions = new ArrayList<Session>();
    for (var item : catalog.sessions()) {
      var manifest = item.session();
      if (cutoff != null && Instant.parse(manifest.endedAt()).isBefore(cutoff)) continue;
      if (robot != null && !robot.equals(item.robot().id()) && !robot.equals(item.robot().serialNumber())) continue;
      if (event != null && !event.equals(manifest.event())) continue;
      // The durable open size is coalesced; the network view names the current readable prefix.
      if (manifest.openCapture() != null) {
        var open = manifest.openCapture();
        var path = new StoreFiles(selected.root(), selected.security()).resolve(item.path(), open.path());
        manifest = new StoreManifest.Session(manifest.id(), manifest.startedAt(), manifest.endedAt(), manifest.startBasis(),
            manifest.event(), manifest.matchType(), manifest.matchNumber(), manifest.teamNumber(), manifest.files(),
            new StoreManifest.OpenCapture(open.path(), open.provenance(), Files.size(path), open.minTimestampSec(), open.maxTimestampSec()),
            manifest.endReason(), manifest.deviceIdentity(), manifest.identityConflicts(), manifest.conflicts(), manifest.captureStats(), manifest.systemLogs());
      }
      sessions.add(new Session(item.robot().id(), StoreFiles.relative(selected.root(), item.path()), manifest));
    }
    var unassigned = robot != null || event != null || cutoff != null ? List.<Unassigned>of() : catalog.files().stream()
        .filter(f -> f.session() == null).map(f -> new Unassigned(StoreFiles.relative(selected.root(), f.path()), f.file())).toList();
    var shared = new ArrayList<SharedSystem>(); var io = new StoreFiles(selected.root(), selected.security());
    boolean filtered = since != null || robot != null || event != null;
    for (var owner : catalog.robots()) {
      var selectedSessions = sessions.stream().filter(s -> s.robotId().equals(owner.robot().id())).toList();
      if (filtered && selectedSessions.isEmpty()) continue;
      var entries = SystemLogIndex.read(io, owner.path()).files().stream()
          .filter(e -> !filtered || selectedSessions.stream().anyMatch(s -> SystemLogIndex.applies(e, s.manifest()))).toList();
      if (!entries.isEmpty()) shared.add(new SharedSystem(owner.robot().id(), entries));
    }
    return new Sessions(List.copyOf(sessions), unassigned, List.copyOf(shared));
  }
}
