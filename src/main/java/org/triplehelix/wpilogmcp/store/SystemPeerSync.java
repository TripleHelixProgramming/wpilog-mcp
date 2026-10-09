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
import org.triplehelix.wpilogmcp.store.StoreManifest.*;
import org.triplehelix.wpilogmcp.sync.*;

/** Text uses FileTransfer's prefix/content checks, with its own receipts instead of a telemetry
 * decoder. Pending placements bridge copying bytes and committing a session or robot index. */
final class SystemPeerSync {
  private record Item(String remote, Path manifest, String robotId, String originSession, SystemLogIndex.Entry entry) {}
  private record Pending(String staged, String destination, String manifest, String robotId, SystemLogIndex.Entry entry, boolean committed) {}
  private final Path root, directory, journalPath, pendingPath;
  private final StoreFiles io;
  private final StoreSync.Peer peer;
  private final Clock clock;
  private final Map<String, Item> wanted = new LinkedHashMap<>();
  private PullManifest journal;
  private final List<StoreSync.Copied> copied;
  private final List<StoreSync.Present> present;
  private final List<StoreSync.Refusal> refusals;
  private final Map<String, Long> transferred = new HashMap<>();

  SystemPeerSync(Path root, StoreFiles io, StoreSync.Peer peer, Clock clock,
      List<StoreSync.Copied> copied, List<StoreSync.Present> present, List<StoreSync.Refusal> refusals) throws IOException {
    this.root = root; this.io = io; this.peer = peer; this.clock = clock;
    this.copied = copied; this.present = present; this.refusals = refusals;
    directory = root.resolve(".sync/peer-" + StoreFiles.component(peer.description().id()) + "/system");
    journalPath = directory.resolve("pull.json"); pendingPath = directory.resolve("placing.json");
  }
  void run(Map<String, Path> destinations, long rate, java.util.function.Consumer<StoreSync.Progress> progress) throws IOException {
    recover(root, io, directory);
    var seen = new HashSet<String>();
    for (var session : peer.sessions().sessions()) for (var entry : StoreDoor.systemFiles(peer.sessions(), session)) {
      var file = entry.file(); SystemLogFiles.resolve(io, root, io.resolve(root, session.path()), session.manifest(), file);
      var item = new Item(SystemLogFiles.remotePath(session.path(), file), destinations.get(session.manifest().id()), session.robotId(), session.manifest().id(), entry);
      if (!seen.add(item.remote())) continue;
      var existing = held(item);
      if (existing != null) present.add(new StoreSync.Present(peer.url(), item.remote(), existing, file.sha256()));
      else wanted.put(item.remote(), item);
    }
    // A peer sync keeps the robot's whole index, even rotations predating its oldest session.
    // Mirrors instead select shared text by the sessions in their configured scope.
    for (var robot : peer.sessions().systemLogs()) {
      var directory = root.resolve("robots").resolve(StoreFiles.component(robot.robotId()));
      if (!Files.exists(io.check(directory.resolve("robot.json")))) {
        var identity = peer.robots().stream().filter(r -> r.id().equals(robot.robotId())).findFirst().orElseThrow(() -> new IOException("Shared system index has no peer robot"));
        io.write(directory.resolve("robot.json"), new Robot(identity.id(), identity.serialNumber(), identity.name(), identity.comments(), identity.basis()));
      }
      for (var entry : robot.files()) {
        SystemLogIndex.validate(entry); SystemLogFiles.shared(io, root, directory, entry.file());
        var item = new Item(entry.file().path(), null, robot.robotId(), null, entry);
        if (!seen.add(item.remote())) continue;
        var existing = held(item);
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
    var transfer = new FileTransfer(selected, new Local(), journal, rate == 0 ? Long.MAX_VALUE : rate,
        () -> System.nanoTime() / 1000, () -> !Thread.currentThread().isInterrupted());
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
      var origins = new ArrayList<>(p.copiedFrom()); origins.add(new PeerCopy(peer.description().id(), peer.url(), clock.instant().toString()));
      var provenance = new Provenance(p.kind(), p.originalPath(), p.originalName(), p.importedAt(), p.moved(), p.sourceRobotSerial(), origins);
      // Event renames change a store path, not the identity of that session's growing text.
      String sourceKey = HexFormat.of().formatHex(sha256().digest((peer.description().id() + "/" + item.originSession() + "/" + original.path()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      var destination = original.location() == SystemLogState.Location.STORE ? io.resolve(root, original.path())
          : io.resolve(item.manifest().getParent(), "robot/system/peer/" + sourceKey + "/" + Path.of(original.path()).getFileName());
      var file = new SystemLogState.File(original.location(), StoreFiles.relative(original.location() == SystemLogState.Location.STORE ? root : item.manifest().getParent(), destination),
          original.source(), original.format(), original.sha256(), original.sizeBytes(), provenance, original.note());
      var entry = new SystemLogIndex.Entry(file, item.entry().writtenSpan());
      if (Files.exists(destination) && !owned(item, destination)) throw new IOException("System-text destination is unmanaged; it will not be adopted");
      var pending = new Pending(transfer.localName(), StoreFiles.relative(root, destination), item.manifest() == null ? null : StoreFiles.relative(root, item.manifest()), item.robotId(), entry, false);
      io.write(pendingPath, pending); complete(root, io, directory, pending);
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
  static void recover(Path root, StoreFiles io, Path directory) throws IOException {
    var path = io.check(directory.resolve("placing.json"));
    if (Files.exists(path)) { var pending = io.read(path, Pending.class); if (!pending.committed()) complete(root, io, directory, pending); }
  }
  private static void complete(Path root, StoreFiles io, Path directory, Pending pending) throws IOException {
    var from = io.resolve(root, pending.staged()); var to = io.resolve(root, pending.destination()); var entry = pending.entry();
    var manifest = pending.manifest() == null ? null : io.resolve(root, pending.manifest());
    if (!from.startsWith(directory.resolve("files"))) throw new IOException("Invalid system-text placement staging");
    var session = manifest == null ? null : io.read(manifest, Session.class); var file = entry.file();
    var resolved = file.location() == SystemLogState.Location.STORE
        ? SystemLogFiles.shared(io, root, root.resolve("robots").resolve(StoreFiles.component(pending.robotId())), file)
        : SystemLogFiles.resolve(io, root, manifest.getParent(), session, file);
    if (!resolved.equals(to)) throw new IOException("Invalid system-text placement destination");
    if (!Files.isRegularFile(from) || Files.size(from) != file.sizeBytes() || !StoreFiles.hash(from).equals(file.sha256())) throw new IOException("System-text placement hash changed");
    Files.createDirectories(io.check(to.getParent()));
    try (var lease = LogFileAccess.reserveMove(Files.exists(to) ? List.of(to) : List.of())) {
      var release = org.triplehelix.wpilogmcp.log.LogManager.getInstance().release(to);
      if (!release.released()) throw new IOException(release.reason());
      var temp = Files.createTempFile(to.getParent(), ".system-", ".tmp");
      try { Files.copy(from, temp, StandardCopyOption.REPLACE_EXISTING); Files.move(temp, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
      finally { Files.deleteIfExists(temp); }
    }
    if (file.location() == SystemLogState.Location.STORE) SystemLogIndex.put(io, root.resolve("robots").resolve(pending.robotId()), entry);
    else {
      var files = new ArrayList<>(session.systemLogs().files());
      files.removeIf(f -> f.sha256().equals(file.sha256()) || f.path().equals(file.path())); files.add(file);
      io.write(manifest, session.withSystemLogs(session.systemLogs().withFiles(files)));
    }
    io.write(directory.resolve("placing.json"), new Pending(pending.staged(), pending.destination(), pending.manifest(), pending.robotId(), entry, true));
  }
  static List<Path> managed(Path root, StoreFiles io, Path directory) throws IOException {
    var path = io.check(directory.resolve("pull.json")); if (!Files.exists(path)) return List.of();
    var result = new ArrayList<Path>(); result.add(path); var journal = io.read(path, PullManifest.class);
    for (var entry : java.util.stream.Stream.concat(journal.files().stream(), journal.history().stream()).toList()) {
      var file = io.resolve(root, entry.localName()); if (!file.startsWith(directory.resolve("files"))) throw new IOException("Invalid system transfer journal path"); result.add(file);
    }
    if (Files.exists(directory.resolve("placing.json"))) result.add(io.check(directory.resolve("placing.json")));
    return result;
  }
}
