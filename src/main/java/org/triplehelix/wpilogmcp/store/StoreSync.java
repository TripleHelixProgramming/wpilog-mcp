/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.triplehelix.wpilogmcp.log.LogFileAccess;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;
import org.triplehelix.wpilogmcp.store.StoreTransferInventory.Placement;
import org.triplehelix.wpilogmcp.store.StoreTransferInventory.Merge;
import org.triplehelix.wpilogmcp.sync.FileTransfer;
import org.triplehelix.wpilogmcp.sync.HttpRemoteFiles;
import org.triplehelix.wpilogmcp.sync.PullManifest;
import org.triplehelix.wpilogmcp.sync.RemoteFiles;

/**
 * A peer contributes immutable file facts, never permission to overwrite local human fields.
 * The staging owner holds the transfer journal across peer waits; only short publication turns
 * hold store.lock. The transfer journal owns partial bytes; only
 * a hash and the ordinary import inspection admit a payload to the catalog. A placement receipt
 * bridges the file rename and the atomic manifest replacement across process termination.
 */
public final class StoreSync {
  public record Progress(String peerUrl, String phase, String remotePath, long bytesCopied, long sizeBytes) {}
  public record Created(String peerUrl, String sessionId, Path path) {}
  public record Copied(String peerUrl, String remotePath, Path path, String sha256, long bytes, long sizeBytes) {}
  public record Present(String peerUrl, String remotePath, Path path, String sha256) {}
  public record Disagreement(String sessionId, Conflict conflict) {}
  public record Refusal(String peerUrl, String remotePath, String reason) {}
  public record Stopped(String peerUrl, String remotePath, long bytesCopied, String reason) {}
  public record Result(List<Created> sessionsCreated, List<Copied> filesCopied, List<Present> filesPresent,
      List<Disagreement> conflicts, List<Refusal> refusals, List<Stopped> stopped) {}
  record Peer(String url, StoreDoor.Description description, List<Robot> robots, StoreDoor.Sessions sessions, RemoteFiles remote) {}
  @FunctionalInterface interface Source { Peer open(String url) throws IOException; }
  enum Boundary { STAGED, BEFORE_RENAME, RENAMED, COMMITTED, RECOVERY_VERIFIED }
  @FunctionalInterface interface Probe {
    void reached(Boundary boundary, Path staged, Path destination, Path manifest) throws IOException;
  }
  static Peer http(String url) throws IOException {
    var remote = new HttpRemoteFiles(url); var description = remote.description();
    return new Peer(remote.url(), description, description.mirror() ? List.of() : remote.robots(),
        description.mirror() ? new StoreDoor.Sessions(List.of(), List.of()) : remote.sessions(), remote);
  }
  private record Incoming(String path, Path manifest, LogFile file) {}
  /** Admission and stale preparation are file refusals, not a failed peer connection. */
  static final class Refused extends IOException {
    final String remote;
    Refused(String remote, String reason) { super(reason); this.remote = remote; }
  }
  private final LogStore store;
  private final Path root;
  private final StoreFiles io;
  private final SecurityValidator security;
  private final LogManager manager;
  private final Clock clock;
  private final Source source;
  private final Consumer<Progress> progress;
  private final Probe probe;
  private final List<Created> created = new ArrayList<>();
  private final List<Copied> copied = new ArrayList<>();
  private final List<Present> present = new ArrayList<>();
  private final List<Disagreement> conflicts = new ArrayList<>();
  private final List<Refusal> refusals = new ArrayList<>();
  private final List<Stopped> stopped = new ArrayList<>();
  private final Map<String, StoreCatalog.StoredFile> held = new LinkedHashMap<>();
  private final Map<Path, StoreCatalog.SessionDirectory> sessions = new LinkedHashMap<>();
  private final Map<String, Path> incomingSessions = new LinkedHashMap<>();
  private final Map<Path, Session> plannedSessions = new LinkedHashMap<>();
  private SyncPublication publication;
  private SyncPublication.Catalog catalog;

  StoreSync(LogStore store, SecurityValidator security, LogManager manager, Clock clock,
      Source source, Consumer<Progress> progress, Probe probe) throws IOException {
    this.store = store; this.root = store.root(); this.io = new StoreFiles(root, security);
    this.security = security; this.manager = manager;
    this.clock = clock; this.source = source; this.progress = progress; this.probe = probe;
  }

  Result run(String url, long rateBytes, Header header) throws IOException {
    if (rateBytes < 0) throw new IllegalArgumentException("rate_bytes must be nonnegative (0 means unlimited)");
    if (header.mirror()) {
      refusals.add(new Refusal(url, null, "A mirror is owned by its mirror synchronization and cannot be a peer sync target"));
      return result();
    }
    try (var owned = new SyncPublication(store, io, header.id())) {
      publication = owned;
      return runOwned(url, rateBytes, header);
    }
  }

  private Result runOwned(String url, long rateBytes, Header header) throws IOException {
    recover(); refresh();
    var peers = url == null ? header.peers() : List.of(url);
    if (peers.isEmpty()) refusals.add(new Refusal(null, null, "No remembered peers; run sync <url> first"));
    for (var peerUrl : peers) {
      String at = null; long bytes = 0;
      try {
        progress.accept(new Progress(peerUrl, "connecting", null, 0, 0));
        var peer = source.open(peerUrl);
        try (var remote = peer.remote()) {
          if (peer.description().mirror()) {
            refusals.add(new Refusal(peerUrl, null, "A mirror is a scoped cache, not a peer sync source")); continue;
          }
          if (peer.description().id().equals(header.id())) {
            refusals.add(new Refusal(peerUrl, null, "Peer is this same store")); continue;
          }
          publication.turn(owned -> {
            var latest = owned.read(root.resolve("store.json"), Header.class);
            var remembered = new ArrayList<>(latest.peers());
            if (!remembered.contains(peer.url())) remembered.add(peer.url());
            owned.write(root.resolve("store.json"), latest.withPeers(List.copyOf(remembered)));
            return null;
          });
          incomingSessions.clear(); var incoming = prepare(peer);
          var wanted = new LinkedHashMap<String, Incoming>(); var uniqueHashes = new java.util.HashSet<String>();
          for (var item : incoming) if (!held.containsKey(item.file().sha256()) && uniqueHashes.add(item.file().sha256())) wanted.put(item.path(), item);
          var local = new Local(peer, wanted);
          var journal = local.load();
          var selected = new RemoteFiles() {
            @Override public List<File> list() throws IOException { return remote.list().stream().filter(f -> wanted.containsKey(f.name())).toList(); }
            @Override public byte[] read(String name, long offset, int count) throws IOException { return remote.read(name, offset, count); }
            @Override public java.util.Optional<String> prefixHash(String name, long length) throws IOException { return remote.prefixHash(name, length); }
          };
          try (var transfer = new FileTransfer(selected, local, journal, rateBytes == 0 ? Long.MAX_VALUE : rateBytes,
              () -> System.nanoTime() / 1000, () -> !Thread.currentThread().isInterrupted())) {
            while (!wanted.isEmpty()) {
              var step = transfer.step(); at = step.remoteName();
              var entry = at == null ? null : transfer.manifest().files().stream().filter(e -> e.remoteName().equals(step.remoteName())).findFirst().orElse(null);
              bytes = entry == null ? 0 : entry.bytesCopied();
              if (step.status() == FileTransfer.Status.COPIED) local.transferred.merge(at, step.bytes(), Long::sum);
              if (step.status() == FileTransfer.Status.REFUSED) refusals.add(new Refusal(peer.url(), at, step.detail()));
              progress.accept(new Progress(peer.url(), step.status().name().toLowerCase(java.util.Locale.ROOT), at, bytes, entry == null ? 0 : entry.size()));
              if (step.status() == FileTransfer.Status.PAUSED || Thread.currentThread().isInterrupted()) throw new IOException("Sync interrupted between blocks");
              if (step.status() == FileTransfer.Status.IDLE) break;
              if (step.waitUs() > 0) java.util.concurrent.locks.LockSupport.parkNanos(step.waitUs() * 1000);
            }
          }
          for (var item : incoming) {
            var found = held.get(item.file().sha256());
            if (found != null && copied.stream().noneMatch(c -> c.peerUrl().equals(peer.url()) && c.remotePath().equals(item.path()))) {
              present.add(new Present(peer.url(), item.path(), found.path(), item.file().sha256()));
            } else if (found == null && refusals.stream().noneMatch(r -> peer.url().equals(r.peerUrl()) && item.path().equals(r.remotePath()))) {
              refusals.add(new Refusal(peer.url(), item.path(), "Peer file disappeared before verification; partial bytes retained"));
            }
          }
          var textProgress = new Progress[1];
          try {
            new SystemPeerSync(publication, peer, clock, copied, present, refusals).run(incomingSessions, rateBytes, p -> { textProgress[0] = p; progress.accept(p); });
          } finally {
            if (textProgress[0] != null) { at = textProgress[0].remotePath(); bytes = textProgress[0].bytesCopied(); }
          }
        }
      } catch (Refused refused) {
        refusals.add(new Refusal(peerUrl, refused.remote, refused.getMessage()));
      } catch (IOException | RuntimeException e) {
        stopped.add(new Stopped(peerUrl, at, bytes, e.getMessage()));
        if (Thread.currentThread().isInterrupted()) break;
      }
    }
    return result();
  }
  private Result result() {
    return new Result(List.copyOf(created), List.copyOf(copied), List.copyOf(present), List.copyOf(conflicts), List.copyOf(refusals), List.copyOf(stopped));
  }
  private void refresh() throws IOException {
    catalog = publication.catalog();
    var snapshot = catalog.snapshot(); held.clear(); sessions.clear();
    snapshot.files().forEach(f -> held.put(f.file().sha256(), f));
    snapshot.sessions().forEach(s -> sessions.put(s.path().resolve("session.json"), s));
  }

  private List<Incoming> prepare(Peer peer) throws IOException {
    var robots = new LinkedHashMap<String, Robot>();
    for (var robot : peer.robots()) {
      StoreFiles.component(robot.id());
      if (!List.of("logged", "device", "stated", "address").contains(robot.basis())) throw new IOException("Invalid peer robot basis");
      if (robots.put(robot.id(), robot) != null) throw new IOException("Duplicate peer robot id");
    }
    var incoming = new ArrayList<Incoming>(); var names = new java.util.HashSet<String>();
    for (var exported : peer.sessions().sessions()) {
      var robot = robots.get(exported.robotId()); if (robot == null) throw new IOException("Session has no peer robot manifest");
      var session = exported.manifest(); StoreFiles.component(session.id());
      if (Instant.parse(session.startedAt()).isAfter(Instant.parse(session.endedAt()))) throw new IOException("Inverted peer session window");
      refresh();
      // Reader drain happens before the owner turn. Reserve only directories which may merge.
      var moving = candidates(robot, session);
      var paths = moving.size() > 1 ? moving.stream().map(e -> e.getKey().getParent()).toList() : List.<Path>of();
      Path manifest;
      try (var lease = LogFileAccess.reserveMove(paths)) {
        for (var path : paths) {
          var released = manager.release(path);
          if (!released.released()) throw new IOException(released.reason());
        }
        manifest = publication.turn(catalog, owned -> session(owned, peer, robot, session));
      }
      refresh();
      plannedSessions.put(manifest, sessions.get(manifest).session());
      incomingSessions.put(session.id(), manifest);
      for (var file : session.files()) {
        validate(file); String name = exported.path() + "/" + file.path();
        io.resolve(root, name); // The peer's spelling is a relative store path, never a local filename.
        if (!names.add(name)) throw new IOException("Duplicate peer file path");
        incoming.add(new Incoming(name, manifest, file));
      }
      if (session.openCapture() != null) refusals.add(new Refusal(peer.url(), exported.path() + "/" + session.openCapture().path(),
          "Capture is still open; peer sync waits for its final hash. The read-only door serves its growing prefix."));
    }
    for (var file : peer.sessions().unassigned()) {
      validate(file.file()); io.resolve(root, file.path());
      if (!names.add(file.path())) throw new IOException("Duplicate peer file path");
      var manifest = root.resolve("unassigned").resolve(file.file().sha256().substring(0, 16)).resolve("import.json");
      incoming.add(new Incoming(file.path(), manifest, file.file()));
    }
    return List.copyOf(incoming);
  }
  private static void validate(LogFile file) throws IOException {
    if (file.sha256() == null || !file.sha256().matches("[0-9a-f]{64}") || !file.verified() || file.sizeBytes() < 0
        || file.provenance() == null || !List.of("wpilog", "revlog").contains(file.kind())) throw new IOException("Invalid peer file manifest");
  }
  private static boolean empty(String value) { return value == null || value.isBlank(); }
  private static String fill(String local, String peer) { return empty(local) ? peer : local; }
  private List<Map.Entry<Path, StoreCatalog.SessionDirectory>> candidates(Robot robot, Session remote) {
    return sessions.entrySet().stream().filter(e -> {
      var known = e.getValue();
      boolean sameSerial = robot.serialNumber() != null && robot.serialNumber().equals(known.robot().serialNumber());
      boolean sameId = remote.id().equals(known.session().id()) && java.util.Objects.equals(robot.serialNumber(), known.robot().serialNumber()) && robot.id().equals(known.robot().id());
      return sameId || sameSerial && !Instant.parse(remote.endedAt()).isBefore(Instant.parse(known.session().startedAt()))
          && !Instant.parse(remote.startedAt()).isAfter(Instant.parse(known.session().endedAt()));
    }).toList();
  }

  private Path session(StoreFiles io, Peer peer, Robot robot, Session remote) throws IOException {
    var robotPath = root.resolve("robots").resolve(robot.id()).resolve("robot.json");
    // Contact history is evidence collected by one store, not portable robot identity.
    var disagreements = new ArrayList<Conflict>();
    Robot localRobot = new Robot(robot.id(), robot.serialNumber(), robot.name(), robot.comments(), robot.basis());
    if (Files.exists(io.check(robotPath))) {
      var local = io.read(robotPath, Robot.class);
      if (local.serialNumber() != null && robot.serialNumber() != null && !local.serialNumber().equals(robot.serialNumber())) throw new IOException("Robot id has a different local serial");
      if (!empty(local.name()) && !empty(robot.name()) && !local.name().equals(robot.name())) disagreements.add(new Conflict("robot.name", local.name(), robot.name(), peer.description().id()));
      if (!empty(local.comments()) && !empty(robot.comments()) && !local.comments().equals(robot.comments())) disagreements.add(new Conflict("robot.comments", local.comments(), robot.comments(), peer.description().id()));
      localRobot = new Robot(local.id(), local.serialNumber(), fill(local.name(), robot.name()), fill(local.comments(), robot.comments()), local.basis(), local.contacts());
    }
    io.write(robotPath, localRobot);
    var candidates = candidates(robot, remote);
    if (candidates.size() > 1) candidates = List.of(consolidate(io, candidates));
    Path path; Session merged;
    if (candidates.isEmpty()) {
      String day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(Instant.parse(remote.startedAt()));
      String time = DateTimeFormatter.ofPattern("HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.parse(remote.startedAt()));
      path = root.resolve("robots").resolve(robot.id()).resolve("sessions").resolve(day).resolve(time + "_" + remote.id()).resolve("session.json");
      if (Files.exists(io.check(path))) throw new IOException("Session destination already exists outside the catalog");
      merged = new Session(remote.id(), remote.startedAt(), remote.endedAt(), remote.startBasis(), remote.event(), remote.matchType(),
          remote.matchNumber(), remote.teamNumber(), List.of(), null, remote.endReason(), remote.deviceIdentity(), remote.identityConflicts(), remote.conflicts(), remote.captureStats(), SystemLogState.EMPTY, remote.mergedSessionIds(), List.of());
      created.add(new Created(peer.url(), merged.id(), path.getParent()));
    } else {
      path = candidates.get(0).getKey(); var local = candidates.get(0).getValue().session();
      merged = combine(local, remote, local.files());
    }
    for (var conflict : disagreements) conflicts.add(new Disagreement(merged.id(), conflict));
    merged = new Session(merged.id(), merged.startedAt(), merged.endedAt(), merged.startBasis(), merged.event(), merged.matchType(),
        merged.matchNumber(), merged.teamNumber(), merged.files(), merged.openCapture(), merged.endReason(), merged.deviceIdentity(),
        merged.identityConflicts(), union(merged.conflicts(), disagreements), merged.captureStats(), merged.systemLogs(), merged.mergedSessionIds(), merged.clocks());
    io.write(path, merged); sessions.put(path, new StoreCatalog.SessionDirectory(path.getParent(), localRobot, merged));
    return path;
  }
  private static <T> List<T> union(List<T> a, List<T> b) { return java.util.stream.Stream.concat(a.stream(), b.stream()).distinct().toList(); }

  private static Session combine(Session local, Session remote, List<LogFile> files) {
    String id = local.id().compareTo(remote.id()) <= 0 ? local.id() : remote.id();
    boolean earlier = Instant.parse(remote.startedAt()).isBefore(Instant.parse(local.startedAt()));
    String end = Instant.parse(remote.endedAt()).isAfter(Instant.parse(local.endedAt())) ? remote.endedAt() : local.endedAt();
    return new Session(id, earlier ? remote.startedAt() : local.startedAt(), end, earlier ? remote.startBasis() : local.startBasis(),
        fill(local.event(), remote.event()), fill(local.matchType(), remote.matchType()), local.matchNumber() == null ? remote.matchNumber() : local.matchNumber(),
        local.teamNumber() == null ? remote.teamNumber() : local.teamNumber(), files, local.openCapture(), local.endReason(),
        local.deviceIdentity() == null ? remote.deviceIdentity() : local.deviceIdentity(), union(local.identityConflicts(), remote.identityConflicts()), union(local.conflicts(), remote.conflicts()), local.captureStats() == null ? remote.captureStats() : local.captureStats(), local.systemLogs(),
        java.util.stream.Stream.concat(java.util.stream.Stream.of(local.id(), remote.id()),
            java.util.stream.Stream.concat(local.mergedSessionIds().stream(), remote.mergedSessionIds().stream()))
            .filter(alias -> !alias.equals(id)).distinct().sorted().toList(), local.clocks());
  }

  /** Move closed fragments as directories, retaining their manifests and every old path alias. */
  private Map.Entry<Path, StoreCatalog.SessionDirectory> consolidate(StoreFiles io, List<Map.Entry<Path, StoreCatalog.SessionDirectory>> candidates) throws IOException {
    if (candidates.stream().filter(e -> e.getValue().session().openCapture() != null).count() > 1) {
      throw new IOException("Multiple active captures must close before their session fragments can be consolidated");
    }
    var target = candidates.stream().sorted(java.util.Comparator
        .<Map.Entry<Path, StoreCatalog.SessionDirectory>, Boolean>comparing(e -> e.getValue().session().openCapture() == null)
        .thenComparing(e -> e.getValue().session().id())).findFirst().orElseThrow();
    for (var old : candidates) {
      if (old.getKey().equals(target.getKey())) continue;
      var current = sessions.get(target.getKey()).session(); var previous = old.getValue().session();
      var destination = target.getKey().getParent().resolve("merged").resolve(previous.id() + "-" + UUID.randomUUID());
      var records = new ArrayList<>(current.files());
      for (var file : previous.files()) {
        String path = StoreFiles.relative(target.getKey().getParent(), io.resolve(destination, file.path()));
        records.add(new LogFile(path, file.sha256(), file.sizeBytes(), file.kind(), file.provenance(), file.verified(),
            file.minTimestampSec(), file.maxTimestampSec(), file.startedAt(), file.endedAt(), file.startBasis(), file.truncated(),
            file.matching(), file.robotFingerprint(), file.matchingReason(), file.placementMethod()));
      }
      var merged = combine(current, previous, List.copyOf(records));
      var text = new ArrayList<>(current.systemLogs().files());
      for (var file : previous.systemLogs().files()) {
        String path = file.location() == SystemLogState.Location.STORE ? file.path()
            : StoreFiles.relative(target.getKey().getParent(), io.resolve(destination, file.path()));
        text.add(new SystemLogState.File(file.location(), path, file.source(), file.format(), file.sha256(), file.sizeBytes(), file.provenance(), file.note()));
      }
      merged = merged.withSystemLogs(merged.systemLogs().withFiles(List.copyOf(text)));
      var pending = new Merge(StoreFiles.relative(root, old.getKey().getParent()), StoreFiles.relative(root, destination),
          StoreFiles.relative(root, target.getKey()), merged, clock.instant().toString(), false);
      var receipt = root.resolve(".sync/merges").resolve(UUID.randomUUID() + ".json");
      io.write(receipt, pending); completeMerge(io, receipt, pending);
      sessions.remove(old.getKey());
      // Recovery also carries rewritten clock receipts. Keep the committed value rather than
      // replacing it with the pre-move merge plan when the peer session is combined below.
      var committed = io.read(target.getKey(), Session.class);
      sessions.put(target.getKey(), new StoreCatalog.SessionDirectory(target.getKey().getParent(), target.getValue().robot(), committed));
    }
    return Map.entry(target.getKey(), sessions.get(target.getKey()));
  }

  private void completeMerge(StoreFiles io, Path receipt, Merge pending) throws IOException {
    var from = io.resolve(root, pending.sourceDirectory()); var to = io.resolve(root, pending.destinationDirectory());
    var manifest = io.resolve(root, pending.manifest());
    if (!to.startsWith(manifest.getParent().resolve("merged"))) throw new IOException("Invalid merge receipt destination");
    if (!Files.exists(to)) {
      Files.createDirectories(io.check(to.getParent()));
      Files.move(from, io.check(to), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
    var current = io.read(manifest, Session.class);
    var records = new LinkedHashMap<String, LogFile>(); current.files().forEach(f -> records.put(f.path(), f));
    pending.session().files().forEach(f -> records.putIfAbsent(f.path(), f));
    var text = new LinkedHashMap<String, SystemLogState.File>();
    current.systemLogs().files().forEach(f -> text.put(f.location() + "/" + f.path(), f));
    pending.session().systemLogs().files().forEach(f -> text.putIfAbsent(f.location() + "/" + f.path(), f));
    var archived = io.read(to.resolve("session.json"), Session.class);
    var clocks = new LinkedHashMap<String, StoreManifest.ClockRecord>(); current.clocks().forEach(c -> clocks.put(c.path(), c));
    for (var record : moved(io, archived, to, manifest.getParent())) clocks.putIfAbsent(record.path(), record);
    var merged = combine(current, pending.session(), List.copyOf(records.values()));
    io.write(manifest, merged.withSystemLogs(merged.systemLogs().withFiles(List.copyOf(text.values()))).withClocks(List.copyOf(clocks.values())));
    var header = io.read(root.resolve("store.json"), Header.class); var moves = new ArrayList<Move>();
    for (var old : header.moves()) {
      var path = io.resolve(root, old.movedTo());
      moves.add(path.startsWith(from) ? new Move(old.originalPath(), StoreFiles.relative(root, to.resolve(from.relativize(path))), pending.movedAt()) : old);
    }
    for (var file : archived.files()) moves.add(new Move(io.resolve(from, file.path()).toString(),
        StoreFiles.relative(root, io.resolve(to, file.path())), pending.movedAt()));
    io.write(root.resolve("store.json"), header.withMoves(moves.stream().distinct().toList()));
    io.write(receipt, new Merge(pending.sourceDirectory(), pending.destinationDirectory(), pending.manifest(), pending.session(), pending.movedAt(), true));
  }

  /**
   * The clock records of a fragment moved under the session's merged/, written again for the
   * capture's new path in the session: a record names its capture, and the capture moved. The moved
   * original stays with the fragment's archived manifest. A record that no longer reads as its
   * receipt is left out; a store that records writes it again from the capture.
   */
  private List<StoreManifest.ClockRecord> moved(StoreFiles io, Session archived, Path fragment, Path session) throws IOException {
    var result = new ArrayList<StoreManifest.ClockRecord>();
    for (var record : archived.clocks()) {
      var capture = archived.files().stream().filter(f -> f.path().equals(record.capture()) && f.sha256().equals(record.captureSha256())).findFirst();
      if (capture.isEmpty()) continue;
      ClockRecords.Facts facts;
      try { facts = ClockRecords.read(io, ClockRecords.resolve(io, fragment, record), record); }
      catch (IOException unreadable) {
        org.slf4j.LoggerFactory.getLogger(StoreSync.class).warn("Clock record not carried into the merged session: {}", unreadable.getMessage());
        continue;
      }
      String path = StoreFiles.relative(session, io.resolve(fragment, record.capture()));
      var target = io.check(io.resolve(session, ClockRecords.path(path)));
      Files.createDirectories(target.getParent());
      io.write(target, new ClockRecords.Facts(facts.formatVersion(), path, facts.captureSha256(), facts.captureSizeBytes(),
          facts.collectingStoreId(), facts.recordingStartedAt(), facts.minTimestampSec(), facts.maxTimestampSec(), facts.uptime()));
      result.add(new StoreManifest.ClockRecord(ClockRecords.path(path), StoreFiles.hash(target), Files.size(target), path,
          record.captureSha256(), record.recording()));
    }
    return result;
  }

  private Path destination(Incoming item) throws IOException {
    var parent = item.manifest().getParent();
    var desired = item.manifest().getFileName().toString().equals("import.json")
        ? io.resolve(parent.resolve("robot"), Path.of(item.file().path()).getFileName().toString())
        : io.resolve(parent, item.file().path());
    // An active writer reserves future capture-N names, not only its currently open file.
    // Keep peer captures in their own hash directory even when the local name is free today.
    if ("captured".equals(item.file().provenance().kind()) || desired.getParent().equals(parent)
        && desired.getFileName().toString().matches("(?i)capture(?:-[0-9]+)?\\.wpilog")) {
      desired = parent.resolve("peer").resolve(item.file().sha256()).resolve(desired.getFileName());
    }
    if (desired.equals(item.manifest())) desired = parent.resolve("robot").resolve(item.file().sha256()).resolve(desired.getFileName());
    // Even identical stray bytes are not ours to adopt. Existing catalog hashes were removed
    // from the transfer plan; every remaining occupied path belongs to something else.
    if (Files.exists(io.check(desired))) {
      desired = parent.resolve("peer").resolve(item.file().sha256()).resolve(desired.getFileName());
      while (Files.exists(io.check(desired))) {
        desired = parent.resolve("peer").resolve(UUID.randomUUID().toString()).resolve(desired.getFileName());
      }
    }
    return io.check(desired);
  }

  private final class Local implements FileTransfer.Local {
    final Peer peer; final Map<String, Incoming> wanted; final Path journal, directory, receipt;
    final Map<String, Long> transferred = new LinkedHashMap<>();
    Local(Peer peer, Map<String, Incoming> wanted) throws IOException {
      this.peer = peer; this.wanted = wanted;
      directory = io.check(root.resolve(".sync").resolve(peerDirectory(peer.description().id())));
      journal = directory.resolve("pull.json"); receipt = directory.resolve("placing.json"); Files.createDirectories(directory);
    }
    PullManifest load() throws IOException {
      var old = Files.exists(journal) ? io.read(journal, PullManifest.class) : PullManifest.empty(peer.description().id());
      if (!peer.description().id().equals(old.serialNumber())) throw new IOException("Peer transfer journal belongs to another store");
      var kept = old.files().stream().filter(e -> wanted.containsKey(e.remoteName())).toList();
      var next = new PullManifest(PullManifest.FORMAT_VERSION, peer.description().id(), kept, old.history()); save(next); return next;
    }
    Path path(String name) throws IOException {
      var path = io.resolve(root, name);
      if (!path.startsWith(directory.resolve("files"))) throw new IOException("Transfer journal path is outside peer staging");
      return path;
    }
    @Override public FileTransfer.Reservation reserve(String remote, long bytes) throws IOException {
      var payloads = io.check(directory.resolve("files"));
      Files.createDirectories(payloads);
      try { return store.reserveDiskCopy(payloads, bytes)::close; }
      catch (DiskSpaceBudgets.Refused refused) { throw new Refused(remote, refused.getMessage()); }
    }
    @Override public String create(String remote) throws IOException {
      var filename = Path.of(wanted.get(remote).file().path()).getFileName().toString(); StoreFiles.component(filename);
      var path = io.check(directory.resolve("files").resolve(UUID.randomUUID().toString()).resolve(filename));
      Files.createDirectories(path.getParent()); Files.createFile(path); return StoreFiles.relative(root, path);
    }
    @Override public long size(String name) throws IOException { return Files.size(path(name)); }
    @Override public byte[] read(String name, long offset, int count) throws IOException {
      var bytes = ByteBuffer.allocate(count);
      try (var channel = FileChannel.open(path(name), StandardOpenOption.READ)) {
        while (bytes.hasRemaining()) if (channel.read(bytes, offset + bytes.position()) < 0) break;
      }
      return java.util.Arrays.copyOf(bytes.array(), bytes.position());
    }
    @Override public String prefixHash(String name, long length) throws IOException {
      try {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path(name))) {
          byte[] block = new byte[FileTransfer.BLOCK_BYTES];
          for (long left = length; left > 0;) {
            int n = input.read(block, 0, (int) Math.min(left, block.length));
            if (n < 0) throw new IOException("Held prefix is shorter than the journal");
            digest.update(block, 0, n); left -= n;
          }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
      } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    @Override public void append(String name, long offset, byte[] bytes) throws IOException {
      try (var channel = FileChannel.open(path(name), StandardOpenOption.WRITE)) {
        if (channel.size() != offset) throw new IOException("Held bytes differ from the journal");
        var buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer, offset + buffer.position());
      }
    }
    @Override public String rename(String name, String remote) { return name; } // Remote spelling lives in the journal, not identity.
    @Override public String archive(String name) throws IOException {
      var from = path(name); var to = io.check(from.resolveSibling(from.getFileName() + ".previous-" + UUID.randomUUID()));
      move(from, to); return StoreFiles.relative(root, to);
    }
    @Override public void verify(String name) throws IOException {
      var entry = io.read(journal, PullManifest.class).files().stream().filter(e -> e.localName().equals(name)).findFirst()
          .orElseThrow(() -> new IOException("Missing transfer progress for verification"));
      verifyImported(path(name), wanted.get(entry.remoteName()).file());
    }
    @Override public String verified(PullManifest.Entry entry) throws IOException {
      var item = wanted.get(entry.remoteName()); var from = path(entry.localName());
      try (var lease = LogFileAccess.reserveMove(List.of(from))) {
        var released = manager.release(from);
        if (!released.released()) throw new IOException(released.reason());
        var stamp = FileSnapshot.of(from);
        if (stamp == null || stamp.size() != item.file().sizeBytes() || !StoreFiles.hash(from).equals(item.file().sha256())
            || !stamp.sameAs(FileSnapshot.of(from))) {
          throw new IOException("Verified bytes differ from the peer manifest; sync again for its current catalog");
        }
        // Each turn sees a fresh duplicate inventory, but the selected session must still be
        // the one used to prepare the download. A concurrent import is never overwritten.
        for (int attempt = 0; attempt < 3; attempt++) {
          refresh();
          if (held.containsKey(item.file().sha256()))
            throw new Refused(item.path(), "Duplicate facts changed during sync: content is already present");
          var owner = sessions.get(item.manifest());
          if (!java.util.Objects.equals(plannedSessions.get(item.manifest()), owner == null ? null : owner.session()))
            throw new Refused(item.path(), "Selected session changed during sync preparation; retry");
          var target = destination(item); var original = item.file(); var provenance = original.provenance();
          var origins = new ArrayList<>(provenance.copiedFrom()); origins.add(new PeerCopy(peer.description().id(), peer.url(), clock.instant().toString()));
          var file = new LogFile(StoreFiles.relative(item.manifest().getParent(), target), original.sha256(), original.sizeBytes(), original.kind(),
              new Provenance(provenance.kind(), provenance.originalPath(), provenance.originalName(), provenance.importedAt(), provenance.moved(), provenance.sourceRobotSerial(), origins, provenance.writerIdentity()),
              true, original.minTimestampSec(), original.maxTimestampSec(), original.startedAt(), original.endedAt(), original.startBasis(), original.truncated(),
              original.matching(), original.robotFingerprint(), original.matchingReason(), original.placementMethod());
          var pending = new Placement(entry.localName(), StoreFiles.relative(root, target),
              StoreFiles.relative(root, item.manifest()), file, false, true,
              owner == null ? null : owner.session().id(), metadataHash(item.manifest()));
          probe.reached(Boundary.STAGED, from, target, item.manifest());
          try (var prepared = preparePlacement(from, target, stamp, file.sha256())) {
            // Creating a destination stage changes directory metadata. Re-read off-owner while
            // preserving the original selected-session and duplicate decisions.
            refresh();
            if (held.containsKey(file.sha256()))
              throw new Refused(item.path(), "Duplicate facts changed during sync: content is already present");
            var selected = sessions.get(item.manifest());
            if (!java.util.Objects.equals(plannedSessions.get(item.manifest()), selected == null ? null : selected.session()))
              throw new Refused(item.path(), "Selected session changed during sync preparation; retry");
            publication.turn(catalog, owned -> {
              if (!stamp.sameAs(FileSnapshot.of(from)))
                throw new Refused(item.path(), "Verified sync bytes changed before publication");
              if (Files.exists(owned.check(target))) throw new Refused(item.path(), "Sync destination changed before publication");
              prepared.check();
              owned.writeDurable(receipt, pending);
              completeOwned(owned, receipt, pending, prepared.path);
              return null;
            });
            prepared.removeOriginal();
          } catch (DiskSpaceBudgets.Refused refused) {
            throw new Refused(item.path(), refused.getMessage());
          } catch (SyncPublication.Changed changed) {
            if (Files.exists(target)) throw new Refused(item.path(), "Sync destination changed before publication");
            if (attempt < 2) continue;
            throw new Refused(item.path(), changed.getMessage());
          }
          copied.add(new Copied(peer.url(), item.path(), target, original.sha256(), transferred.getOrDefault(item.path(), 0L), file.sizeBytes()));
          owner = sessions.get(item.manifest());
          held.put(file.sha256(), new StoreCatalog.StoredFile(target, item.manifest(), owner == null ? null : owner.robot(), owner == null ? null : owner.session(), file));
          if (owner != null) plannedSessions.put(item.manifest(), owner.session());
          // The completed journal may name a catalog file; the next pass drops completed entries.
          return StoreFiles.relative(root, target);
        }
        throw new AssertionError("Bounded sync publication must return or refuse");
      }
    }
    @Override public void save(PullManifest manifest) throws IOException { io.write(journal, manifest); }
  }

  private static String peerDirectory(String id) {
    return StoreTransferInventory.peerDirectory(id);
  }

  private void move(Path from, Path to) throws IOException {
    try (var lease = LogFileAccess.reserveMove(List.of(from))) {
      var released = manager.release(from); if (!released.released()) throw new IOException(released.reason());
      Files.createDirectories(io.check(to.getParent())); Files.move(from, io.check(to));
    }
  }
  private void complete(Path receipt, Placement pending) throws IOException {
    var from = io.resolve(root, pending.staged()); var to = io.resolve(root, pending.destination());
    if (!from.startsWith(receipt.getParent().resolve("files"))) throw new IOException("Invalid placement receipt source");
    var manifest = io.resolve(root, pending.manifest());
    var targetStamp = FileSnapshot.of(to);
    var manifestStamp = FileSnapshot.of(manifest);
    if (pending.committed() && targetStamp == null)
      throw new IOException("Committed placement destination is missing; original retained");
    var held = targetStamp == null ? from : to;
    checkRecoverySelection(manifest, pending);
    FileSnapshot stamp;
    try (var lease = LogFileAccess.read(held)) {
      stamp = FileSnapshot.of(held);
      if (stamp == null || stamp.size() != pending.file().sizeBytes()
          || !StoreFiles.hash(held).equals(pending.file().sha256())) throw new IOException("Placement receipt hash does not match its file");
      verifyImported(held, pending.file());
    }
    var movePaths = held.equals(from) || !Files.exists(from) ? List.of(held) : List.of(held, from);
    try (var lease = LogFileAccess.reserveMove(movePaths)) {
      for (var path : movePaths) {
        var released = manager.release(path);
        if (!released.released()) throw new IOException(released.reason());
      }
      if (!stamp.sameAs(FileSnapshot.of(held))) throw new IOException("Placement receipt bytes changed during recovery");
      probe.reached(Boundary.RECOVERY_VERIFIED, from, to, manifest);
      try (var prepared = held.equals(to) ? null : preparePlacement(from, to, stamp, pending.file().sha256())) {
        publication.turn(owned -> {
          if (!stamp.sameAs(FileSnapshot.of(held))) throw new IOException("Placement receipt bytes changed during recovery");
          if (!java.util.Objects.equals(targetStamp, FileSnapshot.of(to)))
            throw new IOException("Placement receipt destination changed during recovery");
          if (!java.util.Objects.equals(manifestStamp, FileSnapshot.of(manifest)))
            throw new IOException("Placement receipt session manifest changed during recovery");
          if (prepared != null) prepared.check();
          completeOwned(owned, receipt, pending, prepared == null ? to : prepared.path);
          return null;
        });
        if (prepared != null) prepared.removeOriginal();
        else if (movePaths.size() > 1) removeRecoveredOriginal(from, pending.file());
      }
    }
  }

  /** A rename on another filesystem leaves a journal-owned prefix until commit is proven. */
  private void removeRecoveredOriginal(Path from, LogFile file) throws IOException {
    var stamp = FileSnapshot.of(from);
    if (stamp == null) return;
    if (stamp.size() != file.sizeBytes() || !file.sha256().equals(StoreFiles.hash(from))
        || !stamp.sameAs(FileSnapshot.of(from)))
      throw new IOException("Stored sync copy committed; changed original retained");
    store.mover().delete(from);
  }

  private static String metadataHash(Path path) throws IOException {
    return Files.isRegularFile(path) ? StoreFiles.hash(path) : null;
  }

  /** Old receipts retain their legacy proof; new ones remember which session admitted them. */
  private void checkRecoverySelection(Path manifest, Placement pending) throws IOException {
    if (!pending.selectionChecked()) return;
    if (pending.sessionId() != null) {
      var session = io.read(manifest, Session.class);
      if (!pending.sessionId().equals(session.id()))
        throw new IOException("Placement receipt selected session changed since admission");
      // A crash after manifest replacement but before the commit marker is already published.
      if (session.files().stream().anyMatch(file -> file.equals(pending.file()))) return;
    } else if (Files.isRegularFile(manifest) && io.read(manifest, LogFile.class).equals(pending.file())) return;
    if (!java.util.Objects.equals(pending.manifestSha256(), metadataHash(manifest)))
      throw new IOException("Placement receipt session manifest changed since admission");
  }

  /** Caller retains the verified bytes and move reservation until this short turn unwinds. */
  private void completeOwned(StoreFiles io, Path receipt, Placement pending, Path prepared) throws IOException {
    var from = io.resolve(root, pending.staged()); var to = io.resolve(root, pending.destination());
    var manifest = io.resolve(root, pending.manifest());
    if (!from.startsWith(receipt.getParent().resolve("files"))) throw new IOException("Invalid placement receipt source");
    if (!Files.exists(to)) {
      Files.createDirectories(io.check(to.getParent()));
      probe.reached(Boundary.BEFORE_RENAME, from, to, manifest);
      store.mover().move(prepared, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      probe.reached(Boundary.RENAMED, from, to, manifest);
    }
    if (manifest.getFileName().toString().equals("import.json")) io.write(manifest, pending.file());
    else {
      var session = io.read(manifest, Session.class);
      if (session.files().stream().noneMatch(f -> f.sha256().equals(pending.file().sha256()))) {
        session = session.withFiles(union(session.files(), List.of(pending.file()))); io.write(manifest, session);
      }
      var owner = sessions.get(manifest);
      if (owner != null) sessions.put(manifest, new StoreCatalog.SessionDirectory(owner.path(), owner.robot(), session));
    }
    io.writeDurable(receipt, pending.committedReceipt());
    probe.reached(Boundary.COMMITTED, from, to, manifest);
  }

  /** A different target filesystem needs a second allocation before its atomic publication. */
  private PreparedPlacement preparePlacement(Path from, Path to, FileSnapshot source, String hash) throws IOException {
    Files.createDirectories(io.check(to.getParent()));
    if (store.mover().sameFileStore(from, to))
      return new PreparedPlacement(from, from, source, hash, null, null);
    var stage = ImportStage.create(io, to.getParent());
    var path = stage.path(to.getFileName());
    DiskSpaceBudgets.Reservation reservation = null;
    try {
      if (!Files.getFileStore(path.getParent()).equals(Files.getFileStore(to.getParent())))
        throw new IOException("Sync placement stage is not on the destination filesystem");
      reservation = store.reserveDiskCopy(path.getParent(), source.size());
      var attributes = store.mover().copy(from, path, source.size());
      store.mover().force(path);
      if (Files.size(path) != source.size() || !hash.equals(StoreFiles.hash(path))
          || !source.sameAs(FileSnapshot.of(from)))
        throw new IOException("Sync bytes changed while preparing the placement copy");
      attributes.restore(path);
      return new PreparedPlacement(from, path, source, hash, stage, reservation);
    } catch (IOException | RuntimeException | Error failure) {
      try { if (Files.exists(path)) store.mover().discardCopy(path); }
      catch (IOException cleanup) { failure.addSuppressed(cleanup); }
      finally {
        stage.close();
        if (reservation != null) reservation.close();
      }
      throw failure;
    }
  }

  private final class PreparedPlacement implements AutoCloseable {
    final Path original, path;
    final FileSnapshot source, prepared;
    final String hash;
    final ImportStage stage;
    final DiskSpaceBudgets.Reservation reservation;
    PreparedPlacement(Path original, Path path, FileSnapshot source, String hash,
        ImportStage stage, DiskSpaceBudgets.Reservation reservation) throws IOException {
      this.original = original; this.path = path; this.source = source; this.hash = hash;
      this.stage = stage; this.reservation = reservation; prepared = FileSnapshot.of(path);
    }
    void check() throws IOException {
      if (prepared == null || !prepared.sameAs(FileSnapshot.of(path)))
        throw new IOException("Prepared sync copy changed before publication");
    }
    void removeOriginal() throws IOException {
      if (stage == null) return;
      if (!source.sameAs(FileSnapshot.of(original)) || !hash.equals(StoreFiles.hash(original)))
        throw new IOException("Stored sync copy committed; changed original retained");
      store.mover().delete(original);
    }
    @Override public void close() throws IOException {
      try { if (stage != null && Files.exists(path)) store.mover().discardCopy(path); }
      finally {
        try { if (stage != null) stage.close(); }
        finally { if (reservation != null) reservation.close(); }
      }
    }
  }
  private void recover() throws IOException {
    var directory = io.check(root.resolve(".sync")); if (!Files.isDirectory(directory)) return;
    var merges = io.check(directory.resolve("merges"));
    if (Files.isDirectory(merges)) try (var receipts = Files.list(merges)) {
      for (var receipt : receipts.toList()) {
        var pending = io.read(receipt, Merge.class);
        if (!pending.committed()) {
          var from = io.resolve(root, pending.sourceDirectory());
          var paths = Files.exists(from) ? List.of(from) : List.<Path>of();
          try (var lease = LogFileAccess.reserveMove(paths)) {
            for (var path : paths) {
              var released = manager.release(path);
              if (!released.released()) throw new IOException(released.reason());
            }
            publication.turn(owned -> { completeMerge(owned, receipt, pending); return null; });
          }
        }
      }
    }
    try (var peers = Files.list(directory)) {
      for (var peer : peers.toList()) {
        if (peer.getFileName().toString().equals("merges")
            || peer.getFileName().toString().equals(SyncPublication.LOCK_NAME)) continue;
        SystemPeerSync.recover(publication, peer.resolve("system"));
        var receipt = io.check(peer.resolve("placing.json"));
        if (Files.isRegularFile(receipt)) {
          var pending = io.read(receipt, Placement.class);
          // The marker precedes deletion of a cross-filesystem original. A process can die
          // between them, so a committed receipt still owns that last cleanup operation.
          if (!pending.committed() || Files.exists(io.resolve(root, pending.staged()))) complete(receipt, pending);
        }
      }
    }
  }

  /** A hashed peer file may already be an imported power-cut prefix; preserve its declared note. */
  private static void verifyImported(Path path, LogFile expected) throws IOException {
    var inspected = ImportInspection.read(path, new org.triplehelix.wpilogmcp.revlog.RevLogParser(
        new org.triplehelix.wpilogmcp.revlog.dbc.DbcLoader().load(null)));
    if (!inspected.kind().equals(expected.kind())) throw new IOException("Transferred log kind differs from the peer manifest");
    if (inspected.truncated() && !expected.truncated()) throw new IOException("WPILOG scan did not reach the peer's declared clean EOF");
  }

  /** Journals own staging bytes without admitting them as logs or serving them through the door. */
  static List<Path> managed(Path root, StoreFiles io) throws IOException {
    return StoreTransferInventory.peerFiles(root, io);
  }
}
