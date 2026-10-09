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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.triplehelix.wpilogmcp.config.MirrorConfig;
import org.triplehelix.wpilogmcp.log.LogFileAccess;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.revlog.RevLogParser;
import org.triplehelix.wpilogmcp.revlog.dbc.DbcLoader;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;
import org.triplehelix.wpilogmcp.sync.FileTransfer;
import org.triplehelix.wpilogmcp.sync.PullManifest;
import org.triplehelix.wpilogmcp.sync.RemoteFiles;

/**
 * Retention and placement belong to the mirror, byte transfer to FileTransfer. One pass takes
 * an origin catalog snapshot; growing files advance to that prefix, never chase the writer.
 * Session ids make remote directory renames independent of the transfer journal's names.
 * Runs only on the owning store queue under store.lock.
 */
public final class MirrorSync {
  public record Progress(String state, int remainingFiles, long remainingBytes, String path) {}
  public record Result(String state, String lastSync, long bytesCopied, List<String> evicted,
      List<String> retained, List<String> refusals, String reason) {}
  private record Item(String key, String remotePath, Path destination, long size, long mtime,
      LogFile file, SystemLogIndex.Entry text, StoreDoor.Session session) {}
  private record Pending(String from, String to, String hash, String sessionId) {}
  private final Path root;
  private final StoreFiles io;
  private final SecurityValidator security;
  private final LogManager manager;
  private final Clock clock;
  private final Consumer<Progress> progress;
  private Header header;
  private PullManifest journal;
  private final Map<String, MirrorSession> kept = new LinkedHashMap<>();
  private final Map<String, Item> wanted = new LinkedHashMap<>();
  private final List<String> evicted = new ArrayList<>(), retained = new ArrayList<>(), refusals = new ArrayList<>();
  private final RevLogParser parser = new RevLogParser(new DbcLoader().load(null));
  private long copied;
  private StoreDoor.Sessions offeredCatalog;
  private final Map<String, List<SystemLogIndex.Entry>> localText = new HashMap<>();
  private final Map<String, Boolean> textProofs = new HashMap<>();

  MirrorSync(Path root, StoreFiles io, SecurityValidator security, LogManager manager,
      Clock clock, Consumer<Progress> progress) throws IOException {
    this.root = root; this.io = io; this.security = security; this.manager = manager;
    this.clock = clock; this.progress = progress;
  }

  static Header initialize(Path root, StoreFiles io, SecurityValidator security, MirrorConfig config) throws IOException {
    var header = io.read(root.resolve("store.json"), Header.class);
    if (header.origin() != null) {
      if (!header.mirror() || !header.origin().url().equals(config.origin())) throw new IOException("mirror.origin differs from this folder's origin; choose another folder");
      return header;
    }
    var catalog = StoreCatalog.read(root, security);
    if (!catalog.robots().isEmpty() || !catalog.allFiles().isEmpty() || !catalog.unmanaged().isEmpty()) {
      throw new IOException("mirror.folder must be empty; an existing store is not converted into a cache");
    }
    header = header.withOrigin(new MirrorOrigin(null, config.origin(), null, List.of(), Map.of()));
    io.write(root.resolve("store.json"), header); return header;
  }

  Result run(MirrorConfig config, StoreSync.Source source) throws IOException {
    header = initialize(root, io, security, config); kept.putAll(header.origin().sessions());
    journal = Files.exists(journalPath(root)) ? io.read(journalPath(root), PullManifest.class) : PullManifest.empty(null);
    recover();
    try {
      var peer = source.open(config.origin());
      try (var remote = peer.remote()) {
        if (peer.description().mirror()) throw new IOException("A mirror cannot be another mirror's origin");
        if (header.origin().storeId() != null && !header.origin().storeId().equals(peer.description().id())) {
          throw new IOException("mirror.origin now names a different store id; choose another folder");
        }
        header = header.withOrigin(new MirrorOrigin(peer.description().id(), config.origin(), header.origin().lastSync(),
            header.origin().pinnedSessions(), kept)); saveHeader(header.origin().lastSync());
        offeredCatalog = peer.sessions();
        var offered = new LinkedHashMap<String, StoreDoor.Session>();
        for (var session : peer.sessions().sessions()) {
          validate(session);
          if (offered.put(session.manifest().id(), session) != null) throw new IOException("Duplicate origin session id");
        }
        var local = StoreCatalog.readManaged(root, security);
        for (var robot : local.robots()) localText.put(robot.robot().id(), SystemLogIndex.read(io, robot.path()).files());
        var safeToEvict = new HashSet<String>();
        for (var held : local.sessions()) {
          var present = offered.get(held.session().id());
          if (present != null && originStillHolds(held, present, remote)) safeToEvict.add(held.session().id());
        }
        var chosen = choose(config, offered, local, safeToEvict);
        for (var session : local.sessions()) {
          String id = session.session().id();
          if (!chosen.contains(id) && offered.containsKey(id)) {
            if (safeToEvict.contains(id)) evict(session);
            else retained.add(id + ": origin no longer advertises all held hashes; retained");
          } else if (!offered.containsKey(id)) retained.add(id + ": absent from origin; retained");
        }
        evictShared(local, chosen, offered, remote);
        for (var id : chosen) {
          var exported = offered.get(id); var robot = peer.robots().stream()
              .filter(r -> r.id().equals(exported.robotId())).findFirst().orElseThrow(() -> new IOException("Missing origin robot"));
          if (!Set.of("logged", "device", "stated", "address").contains(robot.basis())) throw new IOException("Invalid origin robot basis");
          var robotPath = io.resolve(root, "robots/" + StoreFiles.component(robot.id()) + "/robot.json");
          io.write(robotPath, new Robot(robot.id(), robot.serialNumber(), robot.name(), robot.comments(), robot.basis()));
          relocate(exported);
          for (var file : exported.manifest().files()) add(exported, file.path(), file.sizeBytes(), file);
          var open = exported.manifest().openCapture();
          if (open != null) add(exported, open.path(), open.sizeBytes(), null);
          for (var text : StoreDoor.systemFiles(offeredCatalog, exported)) addText(exported, text);
        }
        var localFiles = new Local();
        var snapshot = new RemoteFiles() {
          public List<File> list() { return wanted.values().stream().map(i -> new File(i.key(), i.size(), i.mtime())).toList(); }
          public byte[] read(String name, long offset, int count) throws IOException { return remote.read(wanted.get(name).remotePath(), offset, count); }
          public java.util.Optional<String> prefixHash(String name, long length) throws IOException { return remote.prefixHash(wanted.get(name).remotePath(), length); }
        };
        var transfer = new FileTransfer(snapshot, localFiles, journal, config.rateBytes() == 0 ? Long.MAX_VALUE : config.rateBytes(),
            () -> System.nanoTime() / 1000, () -> !Thread.currentThread().isInterrupted());
        var restarts = new HashMap<String, Integer>();
        while (true) {
          var step = transfer.step(); journal = transfer.manifest();
          copied += step.bytes(); report(step.remoteName());
          if (step.status() == FileTransfer.Status.IDLE) break;
          if (step.status() == FileTransfer.Status.PAUSED) throw new IOException("Mirror interrupted between blocks");
          if (step.status() == FileTransfer.Status.RETRIED && restarts.merge(step.remoteName(), 1, Integer::sum) > 1) {
            String reason = "Origin prefix keeps changing; resume on the next mirror pass";
            refusals.add(step.remoteName() + ": " + reason);
            return new Result("partial", header.origin().lastSync(), copied, List.copyOf(evicted), List.copyOf(retained), List.copyOf(refusals), reason);
          }
          if (step.status() == FileTransfer.Status.REFUSED) refusals.add(step.remoteName() + ": " + step.detail());
          if (step.status() == FileTransfer.Status.WAITING && step.waitUs() > 0) {
            try { java.util.concurrent.TimeUnit.MICROSECONDS.sleep(Math.min(step.waitUs(), 100_000)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Mirror interrupted", e); }
          }
        }
        for (var id : chosen) publish(offered.get(id));
        discardSupersededPartials();
        String now = clock.instant().toString(); saveHeader(now);
        return new Result(refusals.isEmpty() ? "synchronized" : "partial", now, copied,
            List.copyOf(evicted), List.copyOf(retained), List.copyOf(refusals), null);
      }
    } catch (IOException e) {
      return new Result("offline", header.origin().lastSync(), copied, List.copyOf(evicted), List.copyOf(retained), List.copyOf(refusals), e.getMessage());
    }
  }

  private void validate(StoreDoor.Session exported) throws IOException {
    var s = exported.manifest(); StoreFiles.component(s.id()); StoreFiles.component(exported.robotId());
    var path = io.resolve(root, exported.path()); var pieces = root.relativize(path);
    if (pieces.getNameCount() != 5 || !pieces.getName(0).toString().equals("robots")
        || !pieces.getName(1).toString().equals(exported.robotId()) || !pieces.getName(2).toString().equals("sessions")) {
      throw new IOException("Invalid origin session path");
    }
    if (Instant.parse(s.startedAt()).isAfter(Instant.parse(s.endedAt()))) throw new IOException("Invalid origin session time range");
    for (var text : StoreDoor.systemFiles(offeredCatalog, exported)) {
      SystemLogFiles.resolve(io, root, path, s, text.file());
      if (text.file().location() == SystemLogState.Location.STORE) SystemLogIndex.validate(text);
    }
    var names = new HashSet<Path>();
    for (var file : s.files()) {
      if (file.sha256() == null || !file.sha256().matches("[0-9a-f]{64}") || !file.verified()
          || !Set.of("wpilog", "revlog").contains(file.kind()) || file.sizeBytes() < 0 || file.provenance() == null) throw new IOException("Invalid origin file facts");
      if (!names.add(payload(path, file.path()))) throw new IOException("Duplicate origin file path");
    }
    if (s.openCapture() != null && (!names.add(payload(path, s.openCapture().path())) || s.openCapture().sizeBytes() < 0
        || s.openCapture().provenance() == null || !"captured".equals(s.openCapture().provenance().kind()))) throw new IOException("Invalid origin open capture");
  }
  private Path payload(Path session, String name) throws IOException {
    var path = io.resolve(session, name);
    if (!path.startsWith(session) || path.equals(session.resolve("session.json"))) throw new IOException("Invalid session payload path");
    return path;
  }
  private Set<String> choose(MirrorConfig config, Map<String, StoreDoor.Session> offered, StoreCatalog.Snapshot local, Set<String> safeToEvict) {
    var pinned = Set.copyOf(header.origin().pinnedSessions()); var result = new HashSet<String>();
    long budget = config.maxSizeBytes();
    // A rotation shared by fifty sessions consumes space once, including forced retention.
    var accounted = new HashMap<String, Long>();
    for (var held : local.sessions()) {
      var remote = offered.get(held.session().id());
      if (remote == null || !safeToEvict.contains(held.session().id()) && !pinned.contains(held.session().id())) {
        budget -= account(accounted, costs(held.session(), localSystemFiles(held)), true);
      }
    }
    var cutoff = clock.instant().minus(java.time.Duration.ofDays(config.days()));
    var ordered = offered.values().stream().sorted(Comparator.comparing((StoreDoor.Session s) -> s.manifest().startedAt()).reversed()
        .thenComparing(s -> s.manifest().id())).toList();
    for (var session : ordered) if (pinned.contains(session.manifest().id())) {
      result.add(session.manifest().id()); budget -= account(accounted, costs(session.manifest(), StoreDoor.systemFiles(offeredCatalog, session)), true);
    }
    for (var session : ordered) {
      var s = session.manifest();
      if (result.contains(s.id())) continue;
      if (!config.robots().isEmpty() && !config.robots().contains(session.robotId())) continue;
      boolean inScope = !Instant.parse(s.endedAt()).isBefore(cutoff) || s.event() != null && config.events().contains(s.event());
      var costs = costs(s, StoreDoor.systemFiles(offeredCatalog, session));
      long cost = account(accounted, costs, false);
      if (inScope && cost <= budget) { result.add(s.id()); budget -= account(accounted, costs, true); }
    }
    if (budget < 0) retained.add("Pinned or origin-missing sessions exceed mirror.max_size_gb; none were deleted");
    return result;
  }
  private List<SystemLogIndex.Entry> localSystemFiles(StoreCatalog.SessionDirectory session) {
    var entries = new LinkedHashMap<String, SystemLogIndex.Entry>();
    for (var file : session.session().systemLogs().files()) entries.put(file.location() + "/" + file.path(), new SystemLogIndex.Entry(file, null));
    for (var entry : localText.getOrDefault(session.robot().id(), List.of())) if (SystemLogIndex.applies(entry, session.session())) entries.putIfAbsent(entry.file().location() + "/" + entry.file().path(), entry);
    return List.copyOf(entries.values());
  }
  private static Map<String, Long> costs(Session s, List<SystemLogIndex.Entry> text) {
    var result = new HashMap<String, Long>();
    for (var file : s.files()) result.put(s.id() + "/" + file.path(), file.sizeBytes());
    if (s.openCapture() != null) result.put(s.id() + "/" + s.openCapture().path(), s.openCapture().sizeBytes());
    for (var entry : text) {
      var file = entry.file(); result.put(file.location() == SystemLogState.Location.STORE ? "system/" + file.path() : s.id() + "/system/" + file.path(), file.sizeBytes());
    }
    return result;
  }
  private static long account(Map<String, Long> accounted, Map<String, Long> incoming, boolean commit) {
    long extra = 0;
    for (var entry : incoming.entrySet()) {
      extra += Math.max(0, entry.getValue() - accounted.getOrDefault(entry.getKey(), 0L));
      if (commit) accounted.merge(entry.getKey(), entry.getValue(), Math::max);
    }
    return extra;
  }
  private boolean originStillHolds(StoreCatalog.SessionDirectory local, StoreDoor.Session remote, RemoteFiles source) throws IOException {
    var open = local.session().openCapture();
    if (open != null) {
      var path = io.resolve(local.path(), open.path());
      String key = local.session().id() + "/" + open.path();
      var held = journal.files().stream().filter(e -> e.remoteName().equals(key)).findFirst();
      if (held.isEmpty() || held.get().bytesCopied() != Files.size(path)) return false;
      String remoteName = remote.path() + "/" + open.path();
      long length = Files.size(path);
      long offered = remote.manifest().openCapture() != null && remote.manifest().openCapture().path().equals(open.path())
          ? remote.manifest().openCapture().sizeBytes() : remote.manifest().files().stream()
              .filter(f -> f.path().equals(open.path())).mapToLong(LogFile::sizeBytes).findFirst().orElse(-1);
      if (offered < length) return false;
      var proof = source.prefixHash(remoteName, length);
      if (proof.isEmpty() || !proof.get().equals(new Local().prefixHash(StoreFiles.relative(root, path), length))) return false;
    }
    var hashes = remote.manifest().files().stream().map(LogFile::sha256).collect(java.util.stream.Collectors.toSet());
    if (!local.session().files().stream().allMatch(f -> hashes.contains(f.sha256()))) return false;
    var text = StoreDoor.systemFiles(offeredCatalog, remote);
    for (var entry : localSystemFiles(local)) if (!textStillHeld(entry.file(), text, remote.path(), source)) return false;
    return true;
  }
  private boolean textStillHeld(SystemLogState.File held, List<SystemLogIndex.Entry> offered, String session, RemoteFiles remote) throws IOException {
    if (offered.stream().anyMatch(e -> e.file().sha256().equals(held.sha256()))) return true;
    for (var entry : offered) {
      var file = entry.file();
      if (file.location() != held.location() || !file.path().equals(held.path()) || file.sizeBytes() < held.sizeBytes()) continue;
      String path = SystemLogFiles.remotePath(session, file), key = path + "/" + held.sha256();
      if (!textProofs.containsKey(key)) textProofs.put(key, remote.prefixHash(path, held.sizeBytes()).filter(held.sha256()::equals).isPresent());
      return textProofs.get(key);
    }
    return false;
  }
  private void evictShared(StoreCatalog.Snapshot local, Set<String> chosen, Map<String, StoreDoor.Session> offered, RemoteFiles remote) throws IOException {
    var needed = new HashSet<String>();
    for (var held : local.sessions()) if (!evicted.contains(held.session().id())) {
      for (var entry : localSystemFiles(held)) if (entry.file().location() == SystemLogState.Location.STORE) needed.add(entry.file().path());
    }
    for (var id : chosen) for (var entry : StoreDoor.systemFiles(offeredCatalog, offered.get(id))) if (entry.file().location() == SystemLogState.Location.STORE) needed.add(entry.file().path());
    var removed = new HashSet<String>();
    for (var robot : local.robots()) {
      var remaining = new ArrayList<SystemLogIndex.Entry>();
      var remoteFiles = offeredCatalog.systemLogs().stream().filter(r -> r.robotId().equals(robot.robot().id())).flatMap(r -> r.files().stream()).toList();
      for (var entry : localText.getOrDefault(robot.robot().id(), List.of())) {
        var file = entry.file();
        if (needed.contains(file.path())) { remaining.add(entry); continue; }
        if (!textStillHeld(file, remoteFiles, "", remote)) { remaining.add(entry); retained.add(file.path() + ": absent or changed at origin; retained"); continue; }
        var path = SystemLogFiles.shared(io, root, robot.path(), file);
        if (Files.exists(path)) try (var reservation = LogFileAccess.reserveMove(List.of(path))) { release(path); Files.delete(path); }
        removed.add("system/" + file.path());
      }
      if (!remaining.equals(localText.getOrDefault(robot.robot().id(), List.of()))) io.write(SystemLogIndex.path(robot.path()), new SystemLogIndex(remaining));
    }
    if (!removed.isEmpty()) {
      journal = new PullManifest(PullManifest.FORMAT_VERSION, null,
          journal.files().stream().filter(e -> !removed.contains(e.remoteName())).toList(),
          journal.history().stream().filter(e -> !removed.contains(e.remoteName())).toList());
      io.write(journalPath(root), journal);
    }
  }
  private void evict(StoreCatalog.SessionDirectory session) throws IOException {
    var paths = new ArrayList<>(session.session().files().stream().map(f -> {
      try { return io.resolve(session.path(), f.path()); } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
    }).toList());
    if (session.session().openCapture() != null) paths.add(io.resolve(session.path(), session.session().openCapture().path()));
    for (var text : session.session().systemLogs().files()) if (text.location() == SystemLogState.Location.SESSION) paths.add(SystemLogFiles.resolve(io, root, session.path(), session.session(), text));
    try (var reservation = LogFileAccess.reserveMove(List.of(session.path()))) {
      release(session.path());
      // Never recursively delete a session: an unmanaged file inside it belongs to the person.
      for (var path : paths) Files.deleteIfExists(io.check(path));
      Files.deleteIfExists(io.check(session.path().resolve("session.json")));
    }
    String id = session.session().id(); kept.remove(id); evicted.add(id);
    journal = new PullManifest(PullManifest.FORMAT_VERSION, null,
        journal.files().stream().filter(e -> !e.remoteName().startsWith(id + "/")).toList(),
        journal.history().stream().filter(e -> !e.remoteName().startsWith(id + "/")).toList());
    io.write(journalPath(root), journal); saveHeader(header.origin().lastSync());
  }
  private void relocate(StoreDoor.Session remote) throws IOException {
    var old = kept.get(remote.manifest().id());
    if (old == null || old.path().equals(remote.path())) return;
    var from = io.resolve(root, old.path()); var to = io.resolve(root, remote.path());
    if (Files.exists(from)) {
      if (Files.exists(to)) throw new IOException("Mirror move destination already exists; unmanaged contents are not replaced");
      if (StoreCatalog.read(root, security).unmanaged().stream().anyMatch(p -> p.startsWith(from))) {
        throw new IOException("Mirror session contains an unmanaged file; move it out before following the origin's rename");
      }
      io.write(pendingPath(), new Pending(old.path(), remote.path(), null, remote.manifest().id()));
      try (var reservation = LogFileAccess.reserveMove(List.of(from))) {
        release(from); Files.createDirectories(io.check(to.getParent())); Files.move(from, to);
      }
      var moves = new ArrayList<>(header.moves());
      for (var entry : journal.files()) {
        var path = io.resolve(root, entry.localName());
        if (path.startsWith(from)) moves.add(new Move(path.toString(), StoreFiles.relative(root, to.resolve(from.relativize(path))), clock.instant().toString()));
      }
      header = header.withMoves(moves);
    }
    journal = new PullManifest(PullManifest.FORMAT_VERSION, null,
        journal.files().stream().map(e -> relocated(e, old.path(), remote.path())).toList(),
        journal.history().stream().map(e -> relocated(e, old.path(), remote.path())).toList());
    io.write(journalPath(root), journal);
    kept.put(remote.manifest().id(), new MirrorSession(remote.path(), old.lastSync(), old.complete(), old.growing()));
    saveHeader(header.origin().lastSync());
    Files.deleteIfExists(io.check(pendingPath()));
  }
  private static PullManifest.Entry relocated(PullManifest.Entry e, String from, String to) {
    String name = e.localName().startsWith(from + "/") ? to + e.localName().substring(from.length()) : e.localName();
    return new PullManifest.Entry(e.remoteName(), e.size(), e.mtimeMillis(), e.bytesCopied(), e.verified(), name, e.retries(), e.failure());
  }
  private void add(StoreDoor.Session s, String name, long size, LogFile file) throws IOException {
    String key = s.manifest().id() + "/" + name;
    var destination = payload(io.resolve(root, s.path()), name);
    long mtime = Instant.parse(s.manifest().endedAt()).toEpochMilli();
    wanted.put(key, new Item(key, s.path() + "/" + name, destination, size, mtime, file, null, s));
  }
  private static String textKey(StoreDoor.Session s, SystemLogState.File file) {
    return file.location() == SystemLogState.Location.STORE ? "system/" + file.path() : s.manifest().id() + "/system/" + file.path();
  }
  private void addText(StoreDoor.Session s, SystemLogIndex.Entry entry) throws IOException {
    var file = entry.file(); String key = textKey(s, file);
    var destination = SystemLogFiles.resolve(io, root, io.resolve(root, s.path()), s.manifest(), file);
    long mtime = file.provenance().importedAt() == null ? 0 : Instant.parse(file.provenance().importedAt()).toEpochMilli();
    wanted.putIfAbsent(key, new Item(key, SystemLogFiles.remotePath(s.path(), file), destination, file.sizeBytes(), mtime, null, entry, s));
  }
  private void publish(StoreDoor.Session remote) throws IOException {
    var s = remote.manifest();
    var entries = journal.files().stream().collect(java.util.stream.Collectors.toMap(PullManifest.Entry::remoteName, e -> e));
    for (var item : wanted.values()) if (item.session().manifest().id().equals(s.id())) {
      var entry = entries.get(item.key());
      if (entry == null || !entry.verified() || entry.bytesCopied() != item.size()) return;
      if (item.file() != null && !item.file().sha256().equals(StoreFiles.hash(item.destination()))) {
        refusals.add(item.remotePath() + ": origin hash differs from copied bytes"); return;
      }
    }
    for (var text : StoreDoor.systemFiles(offeredCatalog, remote)) {
      var item = wanted.get(textKey(remote, text.file())); var entry = entries.get(item.key());
      if (entry == null || !entry.verified() || entry.bytesCopied() != item.size()) return;
      if (!text.file().sha256().equals(StoreFiles.hash(item.destination()))) {
        refusals.add(item.remotePath() + ": system-file hash differs from copied bytes"); return;
      }
      if (text.file().location() == SystemLogState.Location.STORE) {
        SystemLogIndex.put(io, root.resolve("robots").resolve(remote.robotId()), text);
      }
    }
    io.write(io.resolve(root, remote.path()).resolve("session.json"), s);
    kept.put(s.id(), new MirrorSession(remote.path(), clock.instant().toString(), s.openCapture() == null, s.openCapture() != null));
  }
  private void saveHeader(String lastSync) throws IOException {
    header = header.withOrigin(new MirrorOrigin(header.origin().storeId(), header.origin().url(), lastSync,
        header.origin().pinnedSessions(), kept)); io.write(root.resolve("store.json"), header);
  }
  private void report(String path) {
    long remaining = 0; int count = 0;
    var byName = journal.files().stream().collect(java.util.stream.Collectors.toMap(PullManifest.Entry::remoteName, e -> e));
    for (var item : wanted.values()) {
      var entry = byName.get(item.key()); long bytes = entry == null ? 0 : entry.bytesCopied();
      if (entry == null || !entry.verified() || entry.size() != item.size()) count++;
      remaining += Math.max(0, item.size() - bytes);
    }
    progress.accept(new Progress("synchronizing", count, remaining, path));
  }
  private void discardSupersededPartials() throws IOException {
    var complete = journal.files().stream().filter(PullManifest.Entry::verified).map(PullManifest.Entry::remoteName).collect(java.util.stream.Collectors.toSet());
    var active = journal.files().stream().map(PullManifest.Entry::localName).collect(java.util.stream.Collectors.toSet());
    var history = new ArrayList<PullManifest.Entry>();
    for (var old : journal.history()) {
      if (!complete.contains(old.remoteName())) { history.add(old); continue; }
      var path = io.resolve(root, old.localName());
      if (!active.contains(old.localName()) && path.startsWith(root.resolve(".mirror/files")) && Files.exists(path)) {
        try (var reservation = LogFileAccess.reserveMove(List.of(path))) { release(path); Files.delete(path); }
      }
    }
    journal = new PullManifest(PullManifest.FORMAT_VERSION, null, journal.files(), history); io.write(journalPath(root), journal);
  }
  private void release(Path path) throws IOException {
    var release = manager.release(path); if (!release.released()) throw new IOException(release.reason());
  }
  private Path pendingPath() { return root.resolve(".mirror/pending.json"); }

  /** Finish an owned rename before contacting an offline origin; never adopt a neighboring file. */
  private void recover() throws IOException {
    if (!Files.exists(pendingPath())) return;
    var pending = io.read(pendingPath(), Pending.class);
    var from = io.resolve(root, pending.from()); var to = io.resolve(root, pending.to());
    if (Files.exists(from)) {
      // The operation had not happened. Its original journal still owns the bytes.
      Files.delete(io.check(pendingPath())); return;
    }
    if (!Files.exists(to) || pending.hash() != null && !pending.hash().equals(StoreFiles.hash(to))) {
      throw new IOException("Mirror pending placement is missing or changed");
    }
    journal = new PullManifest(PullManifest.FORMAT_VERSION, null,
        journal.files().stream().map(e -> pending.sessionId() == null
            ? e.localName().equals(pending.from()) ? new PullManifest.Entry(e.remoteName(), e.size(), e.mtimeMillis(),
                e.bytesCopied(), false, pending.to(), e.retries(), e.failure()) : e
            : relocated(e, pending.from(), pending.to())).toList(),
        journal.history().stream().map(e -> pending.sessionId() == null ? e : relocated(e, pending.from(), pending.to())).toList());
    io.write(journalPath(root), journal);
    if (pending.sessionId() != null) {
      var old = kept.get(pending.sessionId());
      if (old != null) kept.put(pending.sessionId(), new MirrorSession(pending.to(), old.lastSync(), old.complete(), old.growing()));
      var moves = new ArrayList<>(header.moves());
      for (var entry : journal.files()) if (entry.localName().startsWith(pending.to() + "/")) {
        String before = pending.from() + entry.localName().substring(pending.to().length());
        moves.add(new Move(io.resolve(root, before).toString(), entry.localName(), clock.instant().toString()));
      }
      header = header.withMoves(moves); saveHeader(header.origin().lastSync());
    }
    Files.delete(io.check(pendingPath()));
  }
  static Path journalPath(Path root) { return root.resolve(".mirror/pull.json"); }
  static Set<Path> managed(Path root, StoreFiles io) throws IOException {
    var path = journalPath(root); if (!Files.exists(path)) return Set.of();
    var result = new HashSet<Path>(); result.add(path);
    if (Files.exists(root.resolve(".mirror/pending.json"))) result.add(root.resolve(".mirror/pending.json"));
    var journal = io.read(path, PullManifest.class);
    for (var entry : java.util.stream.Stream.concat(journal.files().stream(), journal.history().stream()).toList()) result.add(io.resolve(root, entry.localName()));
    return result;
  }
  private final class Local implements FileTransfer.Local {
    public String create(String remoteName) throws IOException {
      var path = io.check(root.resolve(".mirror/files").resolve(UUID.randomUUID().toString()).resolve(wanted.get(remoteName).destination().getFileName()));
      Files.createDirectories(path.getParent()); Files.createFile(path); return StoreFiles.relative(root, path);
    }
    public long size(String name) throws IOException { var path = io.resolve(root, name); return Files.exists(path) ? Files.size(path) : 0; }
    public byte[] read(String name, long offset, int count) throws IOException {
      try (var channel = FileChannel.open(io.resolve(root, name), StandardOpenOption.READ)) {
        var buffer = ByteBuffer.allocate(count); channel.position(offset); while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
        return java.util.Arrays.copyOf(buffer.array(), buffer.position());
      }
    }
    public String prefixHash(String name, long length) throws IOException {
      try {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(io.resolve(root, name))) {
          byte[] block = new byte[FileTransfer.BLOCK_BYTES];
          for (long left = length; left > 0;) { int n = input.read(block, 0, (int) Math.min(left, block.length));
            if (n < 0) throw new IOException("Short mirror prefix"); digest.update(block, 0, n); left -= n; }
        }
        return HexFormat.of().formatHex(digest.digest());
      } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public void append(String name, long offset, byte[] bytes) throws IOException {
      try (var channel = FileChannel.open(io.resolve(root, name), StandardOpenOption.WRITE)) {
        if (channel.size() != offset) throw new IOException("Mirror prefix changed locally");
        channel.position(offset); var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer);
      }
    }
    public String rename(String name, String remoteName) { return name; }
    public String archive(String name) { return name; } // Keep the visible old generation until its replacement verifies.
    public void verify(String name) throws IOException {
      var entry = journal.files().stream().filter(e -> e.localName().equals(name)).findFirst().orElseThrow();
      var item = wanted.get(entry.remoteName());
      if (item.text() != null) {
        var path = io.resolve(root, name); var file = item.text().file();
        if (Files.size(path) != file.sizeBytes() || !StoreFiles.hash(path).equals(file.sha256())) throw new IOException("Mirror system-file hash verification failed");
        return;
      }
      var inspection = ImportInspection.read(io.resolve(root, name), parser);
      if (item.file() != null && (!inspection.hash().equals(item.file().sha256()) || !inspection.kind().equals(item.file().kind())
          || inspection.truncated() && !item.file().truncated())) throw new IOException("Mirror hash or reader verification failed");
    }
    public String verified(PullManifest.Entry entry) throws IOException {
      var item = wanted.get(entry.remoteName()); var from = io.resolve(root, entry.localName()); var to = item.destination();
      if (!from.equals(to)) {
        Files.createDirectories(io.check(to.getParent()));
        if (Files.exists(to)) {
          boolean owned = journal.history().stream().anyMatch(e -> e.localName().equals(StoreFiles.relative(root, to)));
          if (!owned) throw new IOException("Mirror destination is unmanaged; it will not be adopted or replaced");
          io.write(pendingPath(), new Pending(entry.localName(), StoreFiles.relative(root, to), StoreFiles.hash(from), null));
          try (var reservation = LogFileAccess.reserveMove(List.of(to))) {
            release(to); Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
          }
        } else {
          io.write(pendingPath(), new Pending(entry.localName(), StoreFiles.relative(root, to), StoreFiles.hash(from), null));
          Files.move(from, to);
        }
      }
      return StoreFiles.relative(root, to);
    }
    public void save(PullManifest value) throws IOException {
      journal = value; io.write(journalPath(root), value);
      if (Files.exists(pendingPath())) {
        var pending = io.read(pendingPath(), Pending.class);
        if (pending.sessionId() == null && value.files().stream().anyMatch(e -> e.localName().equals(pending.to()) && e.verified())) {
          Files.delete(io.check(pendingPath()));
        }
      }
    }
  }
}
