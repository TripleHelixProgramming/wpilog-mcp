/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.triplehelix.wpilogmcp.log.LogFileAccess;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;
import org.triplehelix.wpilogmcp.sync.*;

/**
 * A sync owns text prefixes through its journal, independently of the immutable visible copy.
 * Network reads, hashing and the second copy finish off-owner; a short publication rechecks
 * the facts that selected the destination. Receipts retain the durable prefix across a crash.
 */
final class SystemPeerSync {
  private record Item(String remote, Path manifest, String robotId, String originSession, SystemLogIndex.Entry entry) {}
  private record Pending(String staged, String destination, String manifest, String robotId,
      SystemLogIndex.Entry entry, boolean committed, boolean metadataChecked, String metadataSha256,
      boolean identityChecked, String sessionId, String robotSerial) {
    Pending committedReceipt() {
      return new Pending(staged, destination, manifest, robotId, entry, true, metadataChecked, metadataSha256,
          identityChecked, sessionId, robotSerial);
    }
  }
  private final Path root, directory, journalPath, pendingPath;
  private final SyncPublication publication;
  private final StoreFiles io;
  private final StoreSync.Peer peer;
  private final Clock clock;
  private final StoreSync.Probe probe;
  private final Map<String, Item> wanted = new LinkedHashMap<>();
  private PullManifest journal;
  private final List<StoreSync.Copied> copied;
  private final List<StoreSync.Present> present;
  private final List<StoreSync.Refusal> refusals;
  private final Map<String, Long> transferred = new HashMap<>();
  private final Map<Path, FileSnapshot> facts = new LinkedHashMap<>();

  SystemPeerSync(SyncPublication publication, StoreSync.Peer peer, Clock clock,
      List<StoreSync.Copied> copied, List<StoreSync.Present> present, List<StoreSync.Refusal> refusals) throws IOException {
    this(publication, peer, clock, copied, present, refusals, (boundary, staged, destination, manifest) -> {});
  }
  SystemPeerSync(SyncPublication publication, StoreSync.Peer peer, Clock clock,
      List<StoreSync.Copied> copied, List<StoreSync.Present> present, List<StoreSync.Refusal> refusals,
      StoreSync.Probe probe) throws IOException {
    this.publication = publication; this.root = publication.store.root();
    this.io = publication.files; this.peer = peer; this.clock = clock;
    this.probe = probe;
    this.copied = copied; this.present = present; this.refusals = refusals;
    directory = root.resolve(".sync/peer-" + StoreFiles.component(peer.description().id()) + "/system");
    journalPath = directory.resolve("pull.json"); pendingPath = directory.resolve("placing.json");
  }
  void run(Map<String, Path> destinations, long rate, java.util.function.Consumer<StoreSync.Progress> progress) throws IOException {
    recover(publication, directory);
    var seen = new HashSet<String>();
    for (var session : peer.sessions().sessions()) for (var entry : StoreDoor.systemFiles(peer.sessions(), session)) {
      var file = entry.file(); SystemLogFiles.resolve(io, root, io.resolve(root, session.path()), session.manifest(), file);
      var item = new Item(SystemLogFiles.remotePath(session.path(), file), destinations.get(session.manifest().id()), session.robotId(), session.manifest().id(), entry);
      if (!seen.add(item.remote())) continue;
      remember(item);
      var existing = held(item);
      checkFacts(witness(item));
      if (existing != null) present.add(new StoreSync.Present(peer.url(), item.remote(), existing, file.sha256()));
      else wanted.put(item.remote(), item);
    }
    // A peer sync keeps the robot's whole index, even rotations predating its oldest session.
    // Mirrors instead select shared text by the sessions in their configured scope.
    for (var robot : peer.sessions().systemLogs()) {
      var directory = root.resolve("robots").resolve(StoreFiles.component(robot.robotId()));
      if (!Files.exists(io.check(directory.resolve("robot.json")))) {
        var identity = peer.robots().stream().filter(r -> r.id().equals(robot.robotId())).findFirst().orElseThrow(() -> new IOException("Shared system index has no peer robot"));
        publication.turn(owned -> {
          if (!Files.exists(owned.check(directory.resolve("robot.json")))) {
            owned.write(directory.resolve("robot.json"), new Robot(identity.id(), identity.serialNumber(),
                identity.name(), identity.comments(), identity.basis()));
          }
          return null;
        });
      }
      for (var entry : robot.files()) {
        SystemLogIndex.validate(entry); SystemLogFiles.shared(io, root, directory, entry.file());
        var item = new Item(entry.file().path(), null, robot.robotId(), null, entry);
        if (!seen.add(item.remote())) continue;
        remember(item);
        var existing = held(item);
        checkFacts(witness(item));
        if (existing != null) present.add(new StoreSync.Present(peer.url(), item.remote(), existing, entry.file().sha256()));
        else wanted.put(item.remote(), item);
      }
    }
    if (wanted.isEmpty()) return;
    journal = Files.exists(io.check(journalPath)) ? io.read(journalPath, PullManifest.class) : PullManifest.empty(peer.description().id());
    var selected = new RemoteFiles() {
      public List<File> list() { return wanted.values().stream().map(i -> new File(i.remote(), i.entry().file().sizeBytes(), i.entry().file().provenance().importedAt() == null ? 0 : java.time.Instant.parse(i.entry().file().provenance().importedAt()).toEpochMilli())).toList(); }
      public byte[] read(String name, long offset, int count) throws IOException {
        progress.accept(new StoreSync.Progress(peer.url(), "system_reading", name, offset, wanted.get(name).entry().file().sizeBytes()));
        return peer.remote().read(name, offset, count);
      }
      public Optional<String> prefixHash(String name, long count) throws IOException {
        progress.accept(new StoreSync.Progress(peer.url(), "system_checking", name, count, wanted.get(name).entry().file().sizeBytes()));
        return peer.remote().prefixHash(name, count);
      }
    };
    try (var transfer = new FileTransfer(selected, new Local(), journal, rate == 0 ? Long.MAX_VALUE : rate,
        () -> System.nanoTime() / 1000, () -> !Thread.currentThread().isInterrupted())) {
      while (true) {
        var step = transfer.step(); journal = transfer.manifest();
        if (step.bytes() > 0) transferred.merge(step.remoteName(), step.bytes(), Long::sum);
        var entry = journal.files().stream().filter(e -> e.remoteName().equals(step.remoteName())).findFirst();
        progress.accept(new StoreSync.Progress(peer.url(), "system_" + step.status().name().toLowerCase(Locale.ROOT), step.remoteName(), entry.map(PullManifest.Entry::bytesCopied).orElse(0L), entry.map(PullManifest.Entry::size).orElse(0L)));
        if (step.status() == FileTransfer.Status.IDLE) break;
        if (step.status() == FileTransfer.Status.REFUSED) refusals.add(new StoreSync.Refusal(peer.url(), step.remoteName(), step.detail()));
        if (step.status() == FileTransfer.Status.PAUSED) throw new IOException("System-text sync interrupted between blocks; held prefixes retained");
        if (step.waitUs() > 0) java.util.concurrent.locks.LockSupport.parkNanos(step.waitUs() * 1000);
      }
    }
  }

  private Path destination(Item item) throws IOException {
    var original = item.entry().file();
    // Event renames change a path, never the identity of the session's growing text.
    String key = HexFormat.of().formatHex(sha256().digest(
        (peer.description().id() + "/" + item.originSession() + "/" + original.path())
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    return original.location() == SystemLogState.Location.STORE ? io.resolve(root, original.path())
        : io.resolve(item.manifest().getParent(), "robot/system/peer/" + key + "/"
            + Path.of(original.path()).getFileName());
  }

  private Path metadata(Item item) {
    return item.entry().file().location() == SystemLogState.Location.STORE
        ? SystemLogIndex.path(root.resolve("robots").resolve(item.robotId())) : item.manifest();
  }

  private List<Path> selected(Item item) throws IOException {
    return List.of(root.resolve("robots").resolve(item.robotId()).resolve("robot.json"),
        metadata(item), destination(item));
  }

  private void remember(Item item) throws IOException {
    for (var path : selected(item)) if (!facts.containsKey(path)) facts.put(path, FileSnapshot.of(path));
  }

  private Map<Path, FileSnapshot> witness(Item item) throws IOException {
    var result = new LinkedHashMap<Path, FileSnapshot>();
    for (var path : selected(item)) result.put(path, facts.get(path));
    return result;
  }

  private static void checkFacts(Map<Path, FileSnapshot> expected) throws IOException {
    for (var entry : expected.entrySet()) {
      if (!Objects.equals(entry.getValue(), FileSnapshot.of(entry.getKey())))
        throw new SyncPublication.Changed();
    }
  }
  private Path held(Item item) throws IOException {
    var file = item.entry().file();
    var candidates = file.location() == SystemLogState.Location.STORE
        ? SystemLogIndex.read(io, root.resolve("robots").resolve(item.robotId())).files().stream().map(SystemLogIndex.Entry::file).toList()
        : io.read(item.manifest(), Session.class).systemLogs().files();
    for (var known : candidates) if (known.sha256().equals(file.sha256()) && known.sizeBytes() == file.sizeBytes()) {
      var path = io.resolve(known.location() == SystemLogState.Location.STORE ? root : item.manifest().getParent(), known.path());
      if (Files.isRegularFile(path) && Files.size(path) == known.sizeBytes() && StoreFiles.hash(path).equals(known.sha256())) return path;
    }
    return null;
  }
  private Path staging(String name) throws IOException {
    var path = io.resolve(root, name);
    if (!path.startsWith(directory.resolve("files"))) throw new IOException("System transfer journal is outside its staging directory");
    return path;
  }
  private final class Local implements FileTransfer.Local {
    @Override public FileTransfer.Reservation reserve(String remote, long bytes) throws IOException {
      var destination = io.check(directory.resolve("files"));
      Files.createDirectories(destination);
      try {
        var reservation = publication.store.reserveDiskCopy(destination, bytes);
        return reservation::close;
      } catch (DiskSpaceBudgets.Refused refused) {
        throw new StoreSync.Refused(remote, refused.getMessage());
      }
    }
    public String create(String remote) throws IOException {
      var path = io.check(directory.resolve("files").resolve(UUID.randomUUID() + ".part")); Files.createDirectories(path.getParent()); Files.createFile(path); return StoreFiles.relative(root, path);
    }
    public long size(String name) throws IOException { return Files.size(staging(name)); }
    public byte[] read(String name, long offset, int count) throws IOException {
      var buffer = ByteBuffer.allocate(count);
      try (var channel = FileChannel.open(staging(name), StandardOpenOption.READ)) { while (buffer.hasRemaining() && channel.read(buffer, offset + buffer.position()) >= 0) { } }
      return Arrays.copyOf(buffer.array(), buffer.position());
    }
    public String prefixHash(String name, long length) throws IOException {
      try (var input = Files.newInputStream(staging(name))) {
        var digest = sha256(); byte[] bytes = new byte[65536];
        for (long left = length; left > 0;) { int n = input.read(bytes, 0, (int) Math.min(left, bytes.length)); if (n < 0) throw new IOException("Short system-text prefix"); digest.update(bytes, 0, n); left -= n; }
        return HexFormat.of().formatHex(digest.digest());
      }
    }
    public void append(String name, long offset, byte[] bytes) throws IOException {
      try (var channel = FileChannel.open(staging(name), StandardOpenOption.WRITE)) {
        if (channel.size() != offset) throw new IOException("System-text prefix changed");
        var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer, offset + buffer.position());
      }
    }
    public String rename(String name, String remote) { return name; }
    public String archive(String name) { return name; }
    public void verify(String name) throws IOException {
      var transfer = journal.files().stream().filter(e -> e.localName().equals(name)).findFirst().orElseThrow();
      var file = wanted.get(transfer.remoteName()).entry().file();
      if (Files.size(staging(name)) != file.sizeBytes() || !StoreFiles.hash(staging(name)).equals(file.sha256())) throw new IOException("System-text hash differs from peer manifest");
    }
    public String verified(PullManifest.Entry transfer) throws IOException {
      var item = wanted.get(transfer.remoteName()); var original = item.entry().file(); var p = original.provenance();
      var expected = witness(item);
      try { checkFacts(expected); }
      catch (SyncPublication.Changed changed) { throw new StoreSync.Refused(item.remote(), changed.getMessage()); }
      var origins = new ArrayList<>(p.copiedFrom()); origins.add(new PeerCopy(peer.description().id(), peer.url(), clock.instant().toString()));
      var provenance = new Provenance(p.kind(), p.originalPath(), p.originalName(), p.importedAt(), p.moved(), p.sourceRobotSerial(), origins, p.writerIdentity());
      var destination = destination(item);
      var file = new SystemLogState.File(original.location(), StoreFiles.relative(original.location() == SystemLogState.Location.STORE ? root : item.manifest().getParent(), destination),
          original.source(), original.format(), original.sha256(), original.sizeBytes(), provenance, original.note());
      var entry = new SystemLogIndex.Entry(file, item.entry().writtenSpan());
      if (Files.exists(destination) && !owned(item, destination)) throw new IOException("System-text destination is unmanaged; it will not be adopted");
      var robot = io.read(root.resolve("robots").resolve(StoreFiles.component(item.robotId()))
          .resolve("robot.json"), Robot.class);
      if (!Objects.equals(item.robotId(), robot.id()))
        throw new StoreSync.Refused(item.remote(), "Selected system-text robot identity changed");
      String sessionId = file.location() == SystemLogState.Location.SESSION
          ? io.read(item.manifest(), Session.class).id() : null;
      try { checkFacts(expected); }
      catch (SyncPublication.Changed changed) { throw new StoreSync.Refused(item.remote(), changed.getMessage()); }
      var pending = new Pending(transfer.localName(), StoreFiles.relative(root, destination),
          item.manifest() == null ? null : StoreFiles.relative(root, item.manifest()), item.robotId(),
          entry, false, true, metadataHash(metadata(item)), true, sessionId, robot.serialNumber());
      try {
        // Only our own committed replacements update the next item's witness. An unrelated
        // mutation cannot be adopted by sampling again after an off-owner wait.
        facts.putAll(complete(publication, directory, pending, expected, probe));
      } catch (DiskSpaceBudgets.Refused | SyncPublication.Changed refused) {
        throw new StoreSync.Refused(item.remote(), refused.getMessage());
      }
      copied.add(new StoreSync.Copied(peer.url(), item.remote(), destination, file.sha256(), transferred.getOrDefault(item.remote(), 0L), file.sizeBytes()));
      return transfer.localName(); // Own a stable held prefix independently of the visible receipt.
    }
    public void save(PullManifest value) throws IOException { journal = value; io.write(journalPath, value); }
  }
  private boolean owned(Item item, Path path) throws IOException {
    var f = item.entry().file();
    var files = f.location() == SystemLogState.Location.STORE ? SystemLogIndex.read(io, root.resolve("robots").resolve(item.robotId())).files().stream().map(SystemLogIndex.Entry::file).toList()
        : io.read(item.manifest(), Session.class).systemLogs().files();
    for (var file : files) if (io.resolve(file.location() == SystemLogState.Location.STORE ? root : item.manifest().getParent(), file.path()).equals(path)) return true;
    return false;
  }
  private static java.security.MessageDigest sha256() { try { return java.security.MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); } }
  static void recover(SyncPublication publication, Path directory) throws IOException {
    var io = publication.files;
    ImportStage.recover(io);
    var path = io.check(directory.resolve("placing.json"));
    if (Files.exists(path)) {
      var pending = io.read(path, Pending.class);
      if (!pending.committed()) complete(publication, directory, pending, null,
          (boundary, staged, destination, manifest) -> {});
    }
  }
  private static String metadataHash(Path path) throws IOException {
    return Files.isRegularFile(path) ? StoreFiles.hash(path) : null;
  }

  /**
   * The prefix and disposable copy are owned until an admitted publication really unwinds.
   * A crash after rename can finish from exact visible bytes without copying again, while a
   * crash before rename must still match the metadata digest recorded by new receipts.
   */
  private static Map<Path, FileSnapshot> complete(SyncPublication publication, Path directory,
      Pending pending, Map<Path, FileSnapshot> originalFacts, StoreSync.Probe probe) throws IOException {
    var root = publication.store.root(); var io = publication.files;
    var from = io.resolve(root, pending.staged()); var to = io.resolve(root, pending.destination()); var entry = pending.entry();
    var file = entry.file();
    var manifest = pending.manifest() == null ? null : io.resolve(root, pending.manifest());
    if (!from.startsWith(directory.resolve("files"))) throw new IOException("Invalid system-text placement staging");
    var metadata = file.location() == SystemLogState.Location.STORE ? SystemLogIndex.path(root.resolve("robots")
        .resolve(StoreFiles.component(pending.robotId()))) : manifest;
    var expected = originalFacts;
    if (expected == null) {
      expected = new LinkedHashMap<>();
      for (var path : List.of(root.resolve("robots").resolve(StoreFiles.component(pending.robotId()))
          .resolve("robot.json"), metadata, to)) expected.put(path, FileSnapshot.of(path));
    }
    checkFacts(expected);
    var session = manifest == null ? null : io.read(manifest, Session.class);
    checkIdentity(io, root, pending, session);
    var resolved = file.location() == SystemLogState.Location.STORE
        ? SystemLogFiles.shared(io, root, root.resolve("robots").resolve(StoreFiles.component(pending.robotId())), file)
        : SystemLogFiles.resolve(io, root, manifest.getParent(), session, file);
    if (!resolved.equals(to)) throw new IOException("Invalid system-text placement destination");
    boolean alreadyRenamed = Files.isRegularFile(to) && Files.size(to) == file.sizeBytes()
        && StoreFiles.hash(to).equals(file.sha256());
    // Old receipts lack the digest; their existing content proof remains readable. A renamed
    // destination proves the pending write already won, so preserve newer unrelated metadata.
    if (originalFacts == null && pending.metadataChecked() && !alreadyRenamed
        && !Objects.equals(pending.metadataSha256(), metadataHash(metadata))) {
      throw new SyncPublication.Changed();
    }
    checkFacts(expected);
    try (var source = LogFileAccess.read(from)) {
      var stamp = FileSnapshot.of(from);
      if (stamp == null || stamp.size() != file.sizeBytes() || !StoreFiles.hash(from).equals(file.sha256()))
        throw new IOException("System-text placement hash changed");
      if (!Objects.equals(stamp, FileSnapshot.of(from))) throw new SyncPublication.Changed();
      Files.createDirectories(io.check(to.getParent()));
      try (var moving = LogFileAccess.reserveMove(Files.exists(to) ? List.of(to) : List.of())) {
        var released = publication.store.replicaLogManager().release(to);
        if (!released.released()) throw new IOException(released.reason());
        if (alreadyRenamed) {
          return publish(publication, directory, pending, expected, from, stamp, null, metadata, probe);
        }
        // FileTransfer retains its prefix reservation until verified returns. This independent
        // destination reservation charges the simultaneous immutable allocation.
        try (var stage = ImportStage.create(io, to.getParent());
             var reservation = publication.store.reserveDiskCopy(
                 stage.path(to.getFileName()).getParent(), file.sizeBytes())) {
          var copy = stage.path(to.getFileName());
          var copier = publication.store.mover();
          try {
            if (!FilesystemIdentity.same(copy.getParent(), to.getParent()))
              throw new IOException("System sync placement stage is not on the destination filesystem");
            var attributes = copier.copy(from, copy, file.sizeBytes());
            copier.force(copy);
            if (Files.size(copy) != file.sizeBytes() || !StoreFiles.hash(copy).equals(file.sha256()))
              throw new IOException("System-text content changed while staging its immutable copy");
            attributes.restore(copy);
            if (!Objects.equals(stamp, FileSnapshot.of(from))) throw new SyncPublication.Changed();
            return publish(publication, directory, pending, expected, from, stamp, copy, metadata, probe);
          } finally {
            if (Files.exists(copy)) {
              try { copier.discardCopy(copy); }
              catch (IOException cleanup) {
                org.slf4j.LoggerFactory.getLogger(SystemPeerSync.class)
                    .warn("System sync stage cleanup remains pending: {}", cleanup.getMessage());
              }
            }
          }
        }
      }
    }
  }

  /** Exact renamed bytes do not authorize attaching their receipt to a replacement owner. */
  private static void checkIdentity(StoreFiles io, Path root, Pending pending, Session session)
      throws IOException {
    if (!pending.identityChecked()) return; // Older receipts retain their existing content proof.
    var robot = io.read(root.resolve("robots").resolve(StoreFiles.component(pending.robotId()))
        .resolve("robot.json"), Robot.class);
    if (!Objects.equals(pending.robotId(), robot.id())
        || !Objects.equals(pending.robotSerial(), robot.serialNumber()))
      throw new IOException("System-text placement selected robot identity changed since admission");
    if (pending.entry().file().location() == SystemLogState.Location.SESSION
        && (session == null || !Objects.equals(pending.sessionId(), session.id())))
      throw new IOException("System-text placement selected session changed since admission");
  }

  private static Map<Path, FileSnapshot> publish(SyncPublication publication, Path directory,
      Pending pending, Map<Path, FileSnapshot> expected, Path from, FileSnapshot source,
      Path copy, Path metadata, StoreSync.Probe probe) throws IOException {
    var copyStamp = copy == null ? null : FileSnapshot.of(copy);
    return publication.turn(io -> {
      checkFacts(expected);
      if (!Objects.equals(source, FileSnapshot.of(from))
          || copy != null && !Objects.equals(copyStamp, FileSnapshot.of(copy)))
        throw new SyncPublication.Changed();
      var root = publication.store.root(); var to = io.resolve(root, pending.destination());
      var receipt = directory.resolve("placing.json");
      io.writeDurable(receipt, pending);
      if (copy != null) {
        probe.reached(StoreSync.Boundary.BEFORE_RENAME, from, to, metadata);
        Files.move(copy, io.check(to), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        probe.reached(StoreSync.Boundary.RENAMED, from, to, metadata);
      }
      var file = pending.entry().file();
      if (file.location() == SystemLogState.Location.STORE) {
        SystemLogIndex.put(io, root.resolve("robots").resolve(pending.robotId()), pending.entry());
      } else {
        var manifest = io.resolve(root, pending.manifest());
        var current = io.read(manifest, Session.class);
        var files = new ArrayList<>(current.systemLogs().files());
        files.removeIf(held -> held.sha256().equals(file.sha256()) || held.path().equals(file.path()));
        files.add(file);
        io.write(manifest, current.withSystemLogs(current.systemLogs().withFiles(files)));
      }
      io.writeDurable(receipt, pending.committedReceipt());
      probe.reached(StoreSync.Boundary.COMMITTED, from, to, metadata);
      var changed = new LinkedHashMap<Path, FileSnapshot>();
      changed.put(metadata, FileSnapshot.of(metadata));
      changed.put(to, FileSnapshot.of(to));
      return changed;
    });
  }
  static List<Path> managed(Path root, StoreFiles io, Path directory) throws IOException {
    return StoreTransferInventory.systemFiles(root, io, directory);
  }
}
