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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.capture.context.DeviceIdentity;
import org.triplehelix.wpilogmcp.capture.pull.KernelLines;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;
import org.triplehelix.wpilogmcp.sync.*;

/** Text transfers use the store queue and the puller's block/content checks, never the NT4 loop.
 * Immutable snapshots retain rotations. Append cursors commit with their byte receipts, so an
 * interrupted append is truncated to the last committed prefix before it is retried. */
public final class SystemPullStore implements FileTransfer.Local {
  private final LogStore store;
  private final SecurityValidator security;
  private final DeviceIdentity identity;
  private final Clock wall;
  private final Path robot, staging, journal;
  private final StoreCatalog.SessionReader reader;
  private record Stamp(long size, java.nio.file.attribute.FileTime modified, Object key) {}
  private record Cached(Stamp stamp, Session session) {}
  private final Map<Path, Cached> inventory = new java.util.HashMap<>();
  private final java.util.Set<Path> seenManifests = new java.util.HashSet<>();
  private StoreCatalog.Snapshot catalog;
  private final Map<Path, Stamp> watched = new java.util.HashMap<>();
  private StoreCatalog.SessionDirectory current;
  private List<StoreCatalog.SessionDirectory> sessions = List.of();
  private Map<String, String> sources = Map.of();
  private final Map<String, SystemLogState.File> known = new java.util.HashMap<>();

  SystemPullStore(LogStore store, SecurityValidator security, DeviceIdentity identity, Clock wall) {
    this(store, security, identity, wall, (io, path) -> io.read(path, Session.class));
  }
  SystemPullStore(LogStore store, SecurityValidator security, DeviceIdentity identity, Clock wall, StoreCatalog.SessionReader reader) {
    this.reader = reader;
    this.store = store; this.security = security; this.identity = identity; this.wall = wall;
    robot = store.root().resolve("robots").resolve(StoreFiles.component(identity.serialNumber()));
    staging = robot.resolve("system/.pull"); journal = staging.resolve("pull.json");
  }

  /** A connection reads each manifest once. Metadata detects atomic replacements and new
   * sessions, including another process's placement; no timer re-parses unchanged history. */
  public boolean beginPass() throws IOException {
    return store.capture(io -> {
      if (catalog == null || inventoryChanged()) refreshInventory(io);
      sessions = catalog.sessions().stream()
          .filter(s -> identity.serialNumber().equals(s.robot().serialNumber()) || s.session().deviceIdentity() != null
              && identity.serialNumber().equals(s.session().deviceIdentity().serialNumber())).toList();
      var active = sessions.stream().filter(s -> s.session().openCapture() != null).toList();
      current = active.size() == 1 ? active.get(0) : null;
      known.clear();
      for (var entry : SystemLogIndex.read(io, robot).files()) {
        var file = entry.file();
        if (!file.format().equals("journal")) known.put(file.sha256(), file);
      }
      for (var session : sessions) for (var file : session.session().systemLogs().files()) {
        if (file.location() == SystemLogState.Location.STORE && !file.format().equals("journal")
            && Files.exists(SystemLogFiles.resolve(io, store.root(), session.path(), session.session(), file))) {
          known.put(file.sha256(), file);
        }
      }
      return current != null;
    });
  }
  private static Stamp stamp(Path path) throws IOException {
    var a = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
    return new Stamp(a.size(), a.lastModifiedTime(), a.fileKey());
  }
  private boolean inventoryChanged() throws IOException {
    for (var entry : watched.entrySet()) {
      try { if (!entry.getValue().equals(stamp(entry.getKey()))) return true; }
      catch (java.nio.file.NoSuchFileException e) { return true; }
    }
    return false;
  }
  private void refreshInventory(StoreFiles io) throws IOException {
    seenManifests.clear();
    catalog = StoreCatalog.readInventory(store.root(), security, (path, reason) ->
        LoggerFactory.getLogger(SystemPullStore.class).warn("System pull skipped {}: {}", path, reason), this::readSession);
    watched.clear(); watched.put(store.root(), stamp(store.root()));
    watched.put(store.root().resolve("store.json"), stamp(store.root().resolve("store.json")));
    var robots = store.root().resolve("robots");
    if (Files.isDirectory(robots)) watched.put(robots, stamp(robots));
    for (var robot : catalog.robots()) {
      watched.put(robot.path(), stamp(robot.path()));
      var manifest = robot.path().resolve("robot.json"); watched.put(manifest, stamp(manifest));
      var sessions = io.check(robot.path().resolve("sessions"));
      if (Files.isDirectory(sessions)) try (var directories = Files.walk(sessions, 2)) {
        for (var path : directories.filter(Files::isDirectory).toList()) watched.put(path, stamp(path));
      }
    }
    // Includes a rejected manifest, so fixing it makes it eligible without a timed retry.
    inventory.keySet().retainAll(seenManifests);
    for (var path : seenManifests) if (Files.exists(path)) watched.put(path, stamp(path));
  }
  private Session readSession(StoreFiles io, Path path) throws IOException {
    seenManifests.add(path);
    var stamp = stamp(io.check(path));
    var cached = inventory.get(path);
    if (cached != null && cached.stamp().equals(stamp)) return cached.session();
    var session = reader.read(io, path); inventory.put(path, new Cached(stamp, session)); return session;
  }
  public String sessionId() { return current == null ? null : current.session().id(); }
  public SystemLogState state() throws IOException { return store.capture(io -> session(io).systemLogs()); }
  public void sources(Map<String, String> value) { sources = Map.copyOf(value); }
  private Path manifest(StoreFiles io) throws IOException {
    if (current == null) throw new IOException("System pull is waiting for an open capture session");
    Path path = current.path().resolve("session.json");
    if (!Files.exists(io.check(path))) {
      // A close/event rename can move the directory while a network block is in flight.
      var moved = StoreCatalog.readManaged(store.root(), security).sessions().stream()
          .filter(s -> s.session().id().equals(current.session().id())).findFirst();
      if (moved.isEmpty()) throw new IOException("System pull session moved out of the store");
      current = moved.get(); path = current.path().resolve("session.json");
    }
    return io.check(path);
  }
  private Session session(StoreFiles io) throws IOException { return io.read(manifest(io), Session.class); }
  private Path staged(StoreFiles io, String name) throws IOException {
    var path = io.resolve(store.root(), name);
    if (!path.getParent().equals(staging) || !path.getFileName().toString().matches("[a-f0-9-]+\\.part")) throw new IOException("Not a system transfer staging file");
    if (Files.isSymbolicLink(path) || Files.exists(staging) && !staging.toRealPath().equals(staging)) throw new IOException("System staging paths cannot be symbolic links");
    return path;
  }
  public PullManifest manifest() throws IOException {
    return store.capture(io -> {
      if (!Files.exists(io.check(journal))) io.write(journal, PullManifest.empty(identity.serialNumber()));
      var value = io.read(journal, PullManifest.class);
      if (!identity.serialNumber().equals(value.serialNumber())) throw new IOException("System transfer serial changed");
      for (var file : value.files()) staged(io, file.localName());
      return value;
    });
  }
  @Override public String create(String remote) throws IOException {
    return store.capture(io -> {
      Files.createDirectories(io.check(staging)); var file = io.check(staging.resolve(UUID.randomUUID() + ".part"));
      Files.createFile(file); return StoreFiles.relative(store.root(), file);
    });
  }
  @Override public long size(String name) throws IOException { return store.capture(io -> Files.size(staged(io, name))); }
  @Override public byte[] read(String name, long offset, int count) throws IOException {
    return store.capture(io -> {
      var bytes = ByteBuffer.allocate(count);
      try (var channel = FileChannel.open(staged(io, name), StandardOpenOption.READ)) {
        while (bytes.hasRemaining() && channel.read(bytes, offset + bytes.position()) >= 0) { }
      }
      return java.util.Arrays.copyOf(bytes.array(), bytes.position());
    });
  }
  @Override public String prefixHash(String name, long length) throws IOException {
    return store.capture(io -> {
      try (var input = Files.newInputStream(staged(io, name))) {
        var digest = digest(); byte[] block = new byte[FileTransfer.BLOCK_BYTES]; long left = length;
        while (left > 0) { int n = input.read(block, 0, (int) Math.min(left, block.length)); if (n < 0) throw new IOException("Short system transfer prefix"); digest.update(block, 0, n); left -= n; }
        return java.util.HexFormat.of().formatHex(digest.digest());
      }
    });
  }
  private static java.security.MessageDigest digest() {
    try { return java.security.MessageDigest.getInstance("SHA-256"); }
    catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
  }
  @Override public void append(String name, long offset, byte[] bytes) throws IOException {
    store.capture(io -> {
      try (var out = FileChannel.open(staged(io, name), StandardOpenOption.WRITE)) {
        if (out.size() != offset) throw new IOException("System transfer prefix length changed");
        var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) out.write(buffer, offset + buffer.position());
      }
      return null;
    });
  }
  @Override public String rename(String name, String remote) { return name; } // Opaque staging names; provenance retains the remote name.
  @Override public String archive(String name) { return name; }
  @Override public void save(PullManifest value) throws IOException {
    store.capture(io -> { for (var file : value.files()) staged(io, file.localName()); io.write(journal, value); return null; });
  }
  @Override public void verify(String name) throws IOException {
    // FileTransfer has checked all bytes by hash (or the documented SFTP-only prefix fallback).
    store.capture(io -> { if (!Files.isRegularFile(staged(io, name))) throw new IOException("Missing system file"); return null; });
  }
  @Override public String verified(PullManifest.Entry entry) throws IOException {
    store.capture(io -> { place(io, staged(io, entry.localName()), entry.remoteName(), source(entry.remoteName())); return null; });
    return entry.localName(); // Keep the prefix in staging for content-checked resume after growth.
  }
  private String source(String name) throws IOException {
    String value = sources.get(name); if (value == null) throw new IOException("Unlisted system source: " + name); return value;
  }

  /** A rotated syslog whose complete hash is already held needs no SFTP payload read. */
  public boolean reuse(String remote, String source, String hash, long size) throws IOException {
    var file = known.get(hash);
    if (!source.equals("syslog") || file == null || file.sizeBytes() != size) return false;
    return store.capture(io -> {
      var s = session(io); var path = SystemLogFiles.resolve(io, store.root(), current.path(), s, file);
      if (!Files.isRegularFile(path) || Files.size(path) != size) return false;
      var receipt = new SystemLogState.File(file.location(), file.path(), source, file.format(), hash, size, provenance(remote), file.note());
      var previous = SystemLogIndex.read(io, robot).files().stream().filter(e -> e.file().sha256().equals(hash)).findFirst();
      SystemLogIndex.put(io, robot, new SystemLogIndex.Entry(receipt, previous.isPresent() ? previous.get().writtenSpan() : SystemLogIndex.span(path, file.format())));
      return true;
    });
  }
  private Provenance provenance(String remote) {
    return new Provenance("pulled", remote, remote.substring(remote.lastIndexOf('/') + 1), wall.instant().toString(), false, identity.serialNumber());
  }
  private void place(StoreFiles io, Path from, String remote, String source) throws IOException {
    String hash = StoreFiles.hash(from); String basename = remote.substring(remote.lastIndexOf('/') + 1);
    try { StoreFiles.component(basename); } catch (IllegalArgumentException e) { basename = "remote.txt"; }
    Path destinationManifest = manifest(io); Session target = session(io); String note = null;
    if (source.equals("jvm_crash")) {
      var pid = java.util.regex.Pattern.compile("hs_err_pid([0-9]+)\\.log").matcher(basename);
      var matches = pid.matches() ? sessions.stream().filter(s -> hasPid(s.session(), pid.group(1))).toList() : List.<StoreCatalog.SessionDirectory>of();
      if (matches.size() == 1) { destinationManifest = io.check(matches.get(0).path().resolve("session.json")); target = io.read(destinationManifest, Session.class); }
      else note = matches.isEmpty() ? "No session records this program pid" : "Several sessions record this program pid; placement is unassigned";
    }
    boolean shared = source.equals("syslog");
    Path parent = shared ? robot.resolve("system") : destinationManifest.getParent().resolve("robot/system");
    if (note != null) parent = parent.resolve("unassigned");
    var to = io.check(parent.resolve(hash).resolve(basename)); Files.createDirectories(to.getParent());
    if (!Files.exists(to)) Files.copy(from, io.check(to));
    if (!hash.equals(StoreFiles.hash(to))) throw new IOException("System file content differs from its hash");
    String format = basename.endsWith(".gz") ? "gzip_text" : "text";
    var receipt = new SystemLogState.File(shared ? SystemLogState.Location.STORE : SystemLogState.Location.SESSION,
        StoreFiles.relative(shared ? store.root() : destinationManifest.getParent(), to), source, format, hash, Files.size(to), provenance(remote), note);
    if (shared) {
      SystemLogIndex.put(io, robot, new SystemLogIndex.Entry(receipt, SystemLogIndex.span(to, format)));
      known.put(hash, receipt);
    } else record(io, destinationManifest, target, receipt);
  }

  private static boolean hasPid(Session session, String text) {
    if (session.captureStats() == null) return false;
    try { long pid = Long.parseLong(text); return session.captureStats().providers().stream().anyMatch(p -> p.programPids().contains(pid)); }
    catch (NumberFormatException e) { return false; }
  }
  private void record(StoreFiles io, Path manifest, Session session, SystemLogState.File receipt) throws IOException {
    var files = new ArrayList<>(session.systemLogs().files());
    if (files.stream().anyMatch(f -> f.location() == receipt.location() && f.path().equals(receipt.path())
        && f.sha256().equals(receipt.sha256()) && f.sizeBytes() == receipt.sizeBytes()
        && f.source().equals(receipt.source()) && f.format().equals(receipt.format())
        && java.util.Objects.equals(f.provenance().originalPath(), receipt.provenance().originalPath())
        && java.util.Objects.equals(f.note(), receipt.note()))) return;
    files.removeIf(f -> f.location() == receipt.location() && f.path().equals(receipt.path())); files.add(receipt);
    io.write(manifest, session.withSystemLogs(session.systemLogs().withFiles(files)));
  }

  public void standDown(String source, String reason) throws IOException {
    store.capture(io -> {
      var s = session(io); var old = s.systemLogs(); var reasons = new java.util.TreeMap<>(old.reasons());
      if (!reason.equals(reasons.put(source, reason))) LoggerFactory.getLogger(SystemPullStore.class).warn("System pull {} stood down for session {}: {}", source, s.id(), reason);
      io.write(manifest(io), s.withSystemLogs(new SystemLogState(old.files(), old.kernelCursor(), old.journalCursor(), old.journalBootId(), reasons))); return null;
    });
  }

  /** Completed snapshots are small ring buffers for dmesg, streamed for the journal. */
  public void kernel(List<String> lines, double uptime) throws IOException {
    store.capture(io -> {
      var s = session(io); var old = s.systemLogs(); var delta = KernelLines.following(lines, uptime, old.kernelCursor());
      if (delta.reboot()) throw new IOException("Kernel uptime restarted; waiting for the new capture session before appending");
      var files = appendLines(io, s, "robot/system/dmesg.txt", "kernel", "dmesg", "dmesg", delta.lines(), delta.note());
      io.write(manifest(io), s.withSystemLogs(new SystemLogState(files, delta.cursor(), old.journalCursor(), old.journalBootId(), old.reasons()))); return null;
    });
  }
  public void journal(Iterable<String> lines, String cursor, String boot) throws IOException {
    store.capture(io -> {
      var s = session(io); var old = s.systemLogs();
      if (old.journalBootId() != null && !old.journalBootId().equals(boot)) throw new IOException("Journal boot changed; waiting for the new capture session");
      var index = SystemLogIndex.read(io, robot);
      var indexed = new java.util.LinkedHashMap<String, SystemLogState.File>();
      // Pre-index stores keep their receipt unchanged; it still owns the append prefix.
      for (var file : old.files()) if (file.location() == SystemLogState.Location.STORE) indexed.put(file.path(), file);
      for (var entry : index.files()) indexed.put(entry.file().path(), entry.file());
      var files = List.copyOf(indexed.values());
      // UTC date is from the journal's own unambiguous epoch, never this laptop's date.
      var days = new java.util.LinkedHashMap<String, Path>();
      java.io.BufferedWriter output = null; String writingDay = null;
      try {
        for (String line : lines) {
          var stamp = org.triplehelix.wpilogmcp.capture.pull.SystemLogTime.epoch(line, "journal");
          String day = stamp == null ? "undated" : java.time.Instant.ofEpochMilli((long) (stamp * 1000)).atOffset(java.time.ZoneOffset.UTC).toLocalDate().toString();
          if (!day.equals(writingDay)) {
            if (output != null) output.close();
            if (!days.containsKey(day)) { Files.createDirectories(io.check(staging)); days.put(day, Files.createTempFile(staging, "journal-", ".tmp")); }
            output = Files.newBufferedWriter(io.check(days.get(day)), StandardOpenOption.APPEND); writingDay = day;
          }
          output.write(line); output.write('\n');
        }
        if (output != null) { output.close(); output = null; }
        for (var day : days.entrySet()) {
          String relative = StoreFiles.relative(store.root(), robot.resolve("system/journal").resolve(StoreFiles.component(s.id())).resolve("journal-" + day.getKey() + ".txt"));
          files = appendFile(io, s.withSystemLogs(old.withFiles(files)), relative, SystemLogState.Location.STORE, "syslog", "journal", "journalctl", day.getValue(), null);
          var receipt = files.stream().filter(f -> f.path().equals(relative)).findFirst().orElseThrow();
          SystemLogIndex.put(io, robot, new SystemLogIndex.Entry(receipt, SystemLogIndex.span(io.resolve(store.root(), relative), "journal")));
        }
      } finally {
        if (output != null) output.close();
        for (var path : days.values()) Files.deleteIfExists(path);
      }
      io.write(manifest(io), s.withSystemLogs(new SystemLogState(old.files(), old.kernelCursor(), cursor, boot, old.reasons()))); return null;
    });
  }
  private List<SystemLogState.File> appendLines(StoreFiles io, Session session, String relative, String source,
      String format, String remote, List<String> lines, String note) throws IOException {
    if (lines.isEmpty()) return session.systemLogs().files();
    Files.createDirectories(io.check(staging)); var temp = Files.createTempFile(staging, "append-", ".tmp");
    try {
      try (var writer = Files.newBufferedWriter(temp)) { for (String line : lines) { writer.write(line); writer.write('\n'); } }
      return appendFile(io, session, relative, SystemLogState.Location.SESSION, source, format, remote, temp, note);
    } finally { Files.deleteIfExists(temp); }
  }
  private List<SystemLogState.File> appendFile(StoreFiles io, Session session, String relative, SystemLogState.Location location, String source,
      String format, String remote, Path incoming, String note) throws IOException {
    var path = io.resolve(location == SystemLogState.Location.STORE ? store.root() : manifest(io).getParent(), relative); Files.createDirectories(path.getParent());
    var previous = session.systemLogs().files().stream().filter(f -> f.location() == location && f.path().equals(relative)).findFirst();
    long committed = previous.map(SystemLogState.File::sizeBytes).orElse(0L);
    if (previous.isEmpty() && Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unmanifested system file already exists; retained unchanged: " + path);
    try (var out = previous.isEmpty()
        ? FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        : FileChannel.open(path, StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      if (out.size() < committed) throw new IOException("System file is shorter than its manifest receipt: " + path);
      out.truncate(committed); out.position(committed);
      try (var input = FileChannel.open(incoming, StandardOpenOption.READ)) {
        var bytes = ByteBuffer.allocate(FileTransfer.BLOCK_BYTES);
        while (input.read(bytes) >= 0) { bytes.flip(); while (bytes.hasRemaining()) out.write(bytes); bytes.clear(); }
      }
      out.force(false);
    }
    var files = new ArrayList<>(session.systemLogs().files()); files.removeIf(f -> f.location() == location && f.path().equals(relative));
    files.add(new SystemLogState.File(location, relative, source, format, StoreFiles.hash(path), Files.size(path), provenance(remote), note));
    return List.copyOf(files);
  }
  public void discard(String name) throws IOException { store.capture(io -> { Files.deleteIfExists(staged(io, name)); return null; }); }
  /** Shutdown releases the channel immediately and leaves temporary-byte cleanup to the queue. */
  public void discardLater(String name) {
    try {
      store.captureAsync(io -> { Files.deleteIfExists(staged(io, name)); return null; }).whenComplete((ignored, failure) -> {
        if (failure != null) LoggerFactory.getLogger(SystemPullStore.class).warn("System snapshot cleanup failed for {}: {}", name, failure.getMessage());
      });
    } catch (java.util.concurrent.RejectedExecutionException e) {
      LoggerFactory.getLogger(SystemPullStore.class).warn("System snapshot cleanup deferred because the store is closed: {}", name);
    }
  }
  public Path snapshotPath(String name) throws IOException { return store.capture(io -> staged(io, name)); }
}
