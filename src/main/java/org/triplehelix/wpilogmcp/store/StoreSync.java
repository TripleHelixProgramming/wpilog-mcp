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
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;
import org.triplehelix.wpilogmcp.sync.FileTransfer;
import org.triplehelix.wpilogmcp.sync.HttpRemoteFiles;
import org.triplehelix.wpilogmcp.sync.PullManifest;
import org.triplehelix.wpilogmcp.sync.RemoteFiles;

/**
 * A peer contributes immutable file facts, never permission to overwrite local human fields.
 * Runs on the owning store queue under its lock. The transfer journal owns partial bytes; only
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
  static Peer http(String url) throws IOException {
    var remote = new HttpRemoteFiles(url); var description = remote.description();
    return new Peer(remote.url(), description, description.mirror() ? List.of() : remote.robots(),
        description.mirror() ? new StoreDoor.Sessions(List.of(), List.of()) : remote.sessions(), remote);
  }
  private record Incoming(String path, Path manifest, LogFile file) {}
  private record Placement(String staged, String destination, String manifest, LogFile file, boolean committed) {}
  private record Merge(String sourceDirectory, String destinationDirectory, String manifest,
      Session session, String movedAt, boolean committed) {}
  private final Path root;
  private final StoreFiles io;
  private final SecurityValidator security;
  private final LogManager manager;
  private final Clock clock;
  private final Source source;
  private final Consumer<Progress> progress;
  private final List<Created> created = new ArrayList<>();
  private final List<Copied> copied = new ArrayList<>();
  private final List<Present> present = new ArrayList<>();
  private final List<Disagreement> conflicts = new ArrayList<>();
  private final List<Refusal> refusals = new ArrayList<>();
  private final List<Stopped> stopped = new ArrayList<>();
  private final Map<String, StoreCatalog.StoredFile> held = new LinkedHashMap<>();
  private final Map<Path, StoreCatalog.SessionDirectory> sessions = new LinkedHashMap<>();

  StoreSync(Path root, StoreFiles io, SecurityValidator security, LogManager manager, Clock clock,
      Source source, Consumer<Progress> progress) {
    this.root = root; this.io = io; this.security = security; this.manager = manager;
    this.clock = clock; this.source = source; this.progress = progress;
  }

  Result run(String url, long rateBytes) throws IOException {
    if (rateBytes < 0) throw new IllegalArgumentException("rate_bytes must be nonnegative (0 means unlimited)");
    var header = io.read(root.resolve("store.json"), Header.class);
    if (header.mirror()) {
      refusals.add(new Refusal(url, null, "A mirror is owned by its mirror synchronization and cannot be a peer sync target"));
      return result();
    }
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
          var latest = io.read(root.resolve("store.json"), Header.class);
          var remembered = new ArrayList<>(latest.peers());
          if (!remembered.contains(peer.url())) remembered.add(peer.url());
          io.write(root.resolve("store.json"), latest.withPeers(List.copyOf(remembered)));
          var incoming = prepare(peer);
          var wanted = new LinkedHashMap<String, Incoming>(); var uniqueHashes = new java.util.HashSet<String>();
          for (var item : incoming) if (!held.containsKey(item.file().sha256()) && uniqueHashes.add(item.file().sha256())) wanted.put(item.path(), item);
          var local = new Local(peer, wanted);
          var journal = local.load();
          var selected = new RemoteFiles() {
            @Override public List<File> list() throws IOException { return remote.list().stream().filter(f -> wanted.containsKey(f.name())).toList(); }
            @Override public byte[] read(String name, long offset, int count) throws IOException { return remote.read(name, offset, count); }
            @Override public java.util.Optional<String> prefixHash(String name, long length) throws IOException { return remote.prefixHash(name, length); }
          };
          var transfer = new FileTransfer(selected, local, journal, rateBytes == 0 ? Long.MAX_VALUE : rateBytes,
              () -> System.nanoTime() / 1000, () -> !Thread.currentThread().isInterrupted());
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
          for (var item : incoming) {
            var found = held.get(item.file().sha256());
            if (found != null && copied.stream().noneMatch(c -> c.peerUrl().equals(peer.url()) && c.remotePath().equals(item.path()))) {
              present.add(new Present(peer.url(), item.path(), found.path(), item.file().sha256()));
            } else if (found == null && refusals.stream().noneMatch(r -> peer.url().equals(r.peerUrl()) && item.path().equals(r.remotePath()))) {
              refusals.add(new Refusal(peer.url(), item.path(), "Peer file disappeared before verification; partial bytes retained"));
            }
          }
        }
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
    var snapshot = StoreCatalog.readManaged(root, security); held.clear(); sessions.clear();
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
      var manifest = session(peer, robot, session);
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
  private Path session(Peer peer, Robot robot, Session remote) throws IOException {
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
    var candidates = sessions.entrySet().stream().filter(e -> {
      var known = e.getValue();
      boolean sameSerial = robot.serialNumber() != null && robot.serialNumber().equals(known.robot().serialNumber());
      boolean sameId = remote.id().equals(known.session().id()) && java.util.Objects.equals(robot.serialNumber(), known.robot().serialNumber()) && robot.id().equals(known.robot().id());
      return sameId || sameSerial && !Instant.parse(remote.endedAt()).isBefore(Instant.parse(known.session().startedAt()))
          && !Instant.parse(remote.startedAt()).isAfter(Instant.parse(known.session().endedAt()));
    }).toList();
    if (candidates.size() > 1) candidates = List.of(consolidate(candidates));
    Path path; Session merged;
    if (candidates.isEmpty()) {
      String day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(Instant.parse(remote.startedAt()));
      String time = DateTimeFormatter.ofPattern("HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.parse(remote.startedAt()));
      path = root.resolve("robots").resolve(robot.id()).resolve("sessions").resolve(day).resolve(time + "_" + remote.id()).resolve("session.json");
      if (Files.exists(io.check(path))) throw new IOException("Session destination already exists outside the catalog");
      merged = new Session(remote.id(), remote.startedAt(), remote.endedAt(), remote.startBasis(), remote.event(), remote.matchType(),
          remote.matchNumber(), remote.teamNumber(), List.of(), null, remote.endReason(), remote.deviceIdentity(), remote.identityConflicts(), remote.conflicts());
      created.add(new Created(peer.url(), merged.id(), path.getParent()));
    } else {
      path = candidates.get(0).getKey(); var local = candidates.get(0).getValue().session();
      merged = combine(local, remote, local.files());
    }
    for (var conflict : disagreements) conflicts.add(new Disagreement(merged.id(), conflict));
    merged = new Session(merged.id(), merged.startedAt(), merged.endedAt(), merged.startBasis(), merged.event(), merged.matchType(),
        merged.matchNumber(), merged.teamNumber(), merged.files(), merged.openCapture(), merged.endReason(), merged.deviceIdentity(),
        merged.identityConflicts(), union(merged.conflicts(), disagreements));
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
        local.deviceIdentity() == null ? remote.deviceIdentity() : local.deviceIdentity(), union(local.identityConflicts(), remote.identityConflicts()), union(local.conflicts(), remote.conflicts()));
  }

  /** Move closed fragments as directories, retaining their manifests and every old path alias. */
  private Map.Entry<Path, StoreCatalog.SessionDirectory> consolidate(List<Map.Entry<Path, StoreCatalog.SessionDirectory>> candidates) throws IOException {
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
            file.matching(), file.robotFingerprint(), file.matchingReason()));
      }
      var merged = combine(current, previous, List.copyOf(records));
      var pending = new Merge(StoreFiles.relative(root, old.getKey().getParent()), StoreFiles.relative(root, destination),
          StoreFiles.relative(root, target.getKey()), merged, clock.instant().toString(), false);
      var receipt = root.resolve(".sync/merges").resolve(UUID.randomUUID() + ".json");
      io.write(receipt, pending); completeMerge(receipt, pending);
      sessions.remove(old.getKey());
      sessions.put(target.getKey(), new StoreCatalog.SessionDirectory(target.getKey().getParent(), target.getValue().robot(), merged));
    }
    refresh();
    return Map.entry(target.getKey(), sessions.get(target.getKey()));
  }

  private void completeMerge(Path receipt, Merge pending) throws IOException {
    var from = io.resolve(root, pending.sourceDirectory()); var to = io.resolve(root, pending.destinationDirectory());
    var manifest = io.resolve(root, pending.manifest());
    if (!to.startsWith(manifest.getParent().resolve("merged"))) throw new IOException("Invalid merge receipt destination");
    if (!Files.exists(to)) move(from, to);
    var current = io.read(manifest, Session.class);
    var records = new LinkedHashMap<String, LogFile>(); current.files().forEach(f -> records.put(f.path(), f));
    pending.session().files().forEach(f -> records.putIfAbsent(f.path(), f));
    io.write(manifest, combine(current, pending.session(), List.copyOf(records.values())));
    var header = io.read(root.resolve("store.json"), Header.class); var moves = new ArrayList<Move>();
    for (var old : header.moves()) {
      var path = io.resolve(root, old.movedTo());
      moves.add(path.startsWith(from) ? new Move(old.originalPath(), StoreFiles.relative(root, to.resolve(from.relativize(path))), pending.movedAt()) : old);
    }
    var archived = io.read(to.resolve("session.json"), Session.class);
    for (var file : archived.files()) moves.add(new Move(io.resolve(from, file.path()).toString(),
        StoreFiles.relative(root, io.resolve(to, file.path())), pending.movedAt()));
    io.write(root.resolve("store.json"), header.withMoves(moves.stream().distinct().toList()));
    io.write(receipt, new Merge(pending.sourceDirectory(), pending.destinationDirectory(), pending.manifest(), pending.session(), pending.movedAt(), true));
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
      if (Files.size(from) != item.file().sizeBytes() || !StoreFiles.hash(from).equals(item.file().sha256())) {
        throw new IOException("Verified bytes differ from the peer manifest; sync again for its current catalog");
      }
      var target = destination(item); var original = item.file(); var provenance = original.provenance();
      var origins = new ArrayList<>(provenance.copiedFrom()); origins.add(new PeerCopy(peer.description().id(), peer.url(), clock.instant().toString()));
      var file = new LogFile(StoreFiles.relative(item.manifest().getParent(), target), original.sha256(), original.sizeBytes(), original.kind(),
          new Provenance(provenance.kind(), provenance.originalPath(), provenance.originalName(), provenance.importedAt(), provenance.moved(), provenance.sourceRobotSerial(), origins),
          true, original.minTimestampSec(), original.maxTimestampSec(), original.startedAt(), original.endedAt(), original.startBasis(), original.truncated(),
          original.matching(), original.robotFingerprint(), original.matchingReason());
      var pending = new Placement(entry.localName(), StoreFiles.relative(root, target), StoreFiles.relative(root, item.manifest()), file, false);
      io.write(receipt, pending); complete(receipt, pending);
      copied.add(new Copied(peer.url(), item.path(), target, original.sha256(), transferred.getOrDefault(item.path(), 0L), file.sizeBytes()));
      var owner = sessions.get(item.manifest());
      held.put(file.sha256(), new StoreCatalog.StoredFile(target, item.manifest(), owner == null ? null : owner.robot(), owner == null ? null : owner.session(), file));
      // The completed journal may name a catalog file; the next pass drops completed entries.
      return StoreFiles.relative(root, target);
    }
    @Override public void save(PullManifest manifest) throws IOException { io.write(journal, manifest); }
  }

  private static String peerDirectory(String id) {
    return "peer-" + StoreFiles.component(id);
  }

  private void move(Path from, Path to) throws IOException {
    try (var lease = LogFileAccess.reserveMove(List.of(from))) {
      var released = manager.release(from); if (!released.released()) throw new IOException(released.reason());
      Files.createDirectories(io.check(to.getParent())); Files.move(from, io.check(to));
    }
  }
  private void complete(Path receipt, Placement pending) throws IOException {
    var from = io.resolve(root, pending.staged()); var to = io.resolve(root, pending.destination());
    var manifest = io.resolve(root, pending.manifest());
    if (!from.startsWith(receipt.getParent().resolve("files"))) throw new IOException("Invalid placement receipt source");
    if (!Files.exists(to)) move(from, to);
    if (!StoreFiles.hash(to).equals(pending.file().sha256()) || Files.size(to) != pending.file().sizeBytes()) throw new IOException("Placement receipt hash does not match its file");
    verifyImported(to, pending.file());
    if (manifest.getFileName().toString().equals("import.json")) io.write(manifest, pending.file());
    else {
      var session = io.read(manifest, Session.class);
      if (session.files().stream().noneMatch(f -> f.sha256().equals(pending.file().sha256()))) {
        session = session.withFiles(union(session.files(), List.of(pending.file()))); io.write(manifest, session);
      }
      var owner = sessions.get(manifest);
      if (owner != null) sessions.put(manifest, new StoreCatalog.SessionDirectory(owner.path(), owner.robot(), session));
    }
    io.write(receipt, new Placement(pending.staged(), pending.destination(), pending.manifest(), pending.file(), true));
  }
  private void recover() throws IOException {
    var directory = io.check(root.resolve(".sync")); if (!Files.isDirectory(directory)) return;
    var merges = io.check(directory.resolve("merges"));
    if (Files.isDirectory(merges)) try (var receipts = Files.list(merges)) {
      for (var receipt : receipts.toList()) {
        var pending = io.read(receipt, Merge.class); if (!pending.committed()) completeMerge(receipt, pending);
      }
    }
    try (var peers = Files.list(directory)) {
      for (var peer : peers.toList()) {
        if (peer.getFileName().toString().equals("merges")) continue;
        var receipt = io.check(peer.resolve("placing.json"));
        if (Files.isRegularFile(receipt)) { var pending = io.read(receipt, Placement.class); if (!pending.committed()) complete(receipt, pending); }
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
    var result = new ArrayList<Path>(); var directory = io.check(root.resolve(".sync"));
    if (!Files.isDirectory(directory)) return List.of();
    try (var peers = Files.list(directory)) {
      for (var peer : peers.toList()) {
        if (peer.getFileName().toString().equals("merges")) {
          try (var receipts = Files.list(peer)) {
            for (var receipt : receipts.toList()) { io.read(receipt, Merge.class); result.add(io.check(receipt)); }
          }
          continue;
        }
        var journal = io.check(peer.resolve("pull.json"));
        if (!Files.isRegularFile(journal)) continue;
        var progress = io.read(journal, PullManifest.class);
        if (!peer.getFileName().toString().equals(peerDirectory(progress.serialNumber()))) throw new IOException("Peer journal id differs from its directory");
        result.add(journal);
        for (var entry : union(progress.files(), progress.history())) {
          var held = io.resolve(root, entry.localName());
          if (held.startsWith(peer.resolve("files")) && Files.isRegularFile(held)) result.add(held);
        }
        var receipt = io.check(peer.resolve("placing.json"));
        if (Files.isRegularFile(receipt)) {
          var pending = io.read(receipt, Placement.class); result.add(receipt);
          var staged = io.resolve(root, pending.staged());
          if (staged.startsWith(peer.resolve("files")) && Files.isRegularFile(staged)) result.add(staged);
          if (!pending.committed()) result.add(io.resolve(root, pending.destination()));
        }
      }
    }
    return List.copyOf(result);
  }
}
