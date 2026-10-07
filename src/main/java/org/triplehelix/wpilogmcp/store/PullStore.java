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
import java.util.List;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.capture.context.DeviceIdentity;
import org.triplehelix.wpilogmcp.log.LogFileAccess;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.LogMetadata;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.revlog.RevLogParser;
import org.triplehelix.wpilogmcp.revlog.dbc.DbcLoader;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;
import org.triplehelix.wpilogmcp.sync.*;

/** Pull placement shares the import queue, lock, path validator, readers and move reservations. */
public final class PullStore implements FileTransfer.Local {
  /** Several publish cycles of jitter are plausible; seconds of clock shift are another session. */
  public static final long MAX_MATCH_OFFSET_US = 250_000;
  private final LogStore store;
  private final LogManager manager;
  private final SecurityValidator security;
  private final DeviceIdentity identity;
  private final Clock clock;
  private final Path robotRoot, manifest;
  PullStore(LogStore store, LogManager manager, SecurityValidator security, DeviceIdentity identity, Clock clock) {
    this.store = store; this.manager = manager; this.security = security; this.identity = identity; this.clock = clock;
    robotRoot = store.root().resolve("robots").resolve(StoreFiles.component(identity.serialNumber()));
    manifest = robotRoot.resolve("pull.json");
  }
  public PullManifest manifest() throws IOException {
    return store.capture(io -> {
      if (!Files.exists(io.check(manifest))) io.write(manifest, PullManifest.empty(identity.serialNumber()));
      var result = io.read(manifest, PullManifest.class);
      if (!identity.serialNumber().equals(result.serialNumber())) throw new IOException("Pull manifest serial differs from device");
      for (var entry : java.util.stream.Stream.concat(result.files().stream(), result.history().stream()).toList()) path(io, entry.localName());
      return result;
    });
  }
  private Path path(StoreFiles io, String relative) throws IOException {
    var original = io.resolve(store.root(), relative);
    Path target = original;
    if (!Files.exists(original)) for (var move : io.read(store.root().resolve("store.json"), Header.class).moves()) {
      if (Path.of(move.originalPath()).equals(original)) target = io.resolve(store.root(), move.movedTo());
    }
    io.check(target);
    if (target.startsWith(robotRoot.resolve("pulled"))) return target;
    var parts = store.root().relativize(target);
    if (parts.getNameCount() < 7 || !parts.getName(0).toString().equals("robots")
        || !parts.getName(2).toString().equals("sessions") || !parts.getName(5).toString().equals("robot")) {
      throw new IOException("Pull path is outside its staging area or session robot files");
    }
    var directory = store.root().resolve(parts.subpath(0, 5));
    var session = io.read(directory.resolve("session.json"), Session.class);
    String member = StoreFiles.relative(directory, target);
    if (session.files().stream().noneMatch(f -> f.path().equals(member) && f.provenance().kind().equals("pulled")
        && identity.serialNumber().equals(f.provenance().sourceRobotSerial()))) throw new IOException("Pull path is not owned by this robot's transfer manifest");
    return target;
  }
  private String relative(Path path) { return StoreFiles.relative(store.root(), path); }
  private Path staging(StoreFiles io, String remote) throws IOException {
    String name = remote.substring(remote.lastIndexOf('/') + 1);
    try { StoreFiles.component(name); }
    catch (IllegalArgumentException e) {
      // The exact remote name remains provenance; Windows cannot create every POSIX basename.
      name = "remote-" + UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8))
          + (name.toLowerCase(java.util.Locale.ROOT).endsWith(".revlog") ? ".revlog" : ".wpilog");
    }
    var result = io.check(robotRoot.resolve("pulled").resolve(UUID.randomUUID().toString()).resolve(name));
    Files.createDirectories(result.getParent()); return io.check(result);
  }
  @Override public String create(String remote) throws IOException {
    return store.capture(io -> { var path = staging(io, remote); Files.createFile(path); return relative(path); });
  }
  @Override public long size(String name) throws IOException { return store.capture(io -> Files.size(path(io, name))); }
  @Override public byte[] read(String name, long offset, int count) throws IOException {
    return store.capture(io -> {
      var bytes = ByteBuffer.allocate(count);
      try (var channel = FileChannel.open(path(io, name), StandardOpenOption.READ)) {
        while (bytes.hasRemaining()) { int n = channel.read(bytes, offset + bytes.position()); if (n < 0) break; }
      }
      return java.util.Arrays.copyOf(bytes.array(), bytes.position());
    });
  }
  @Override public String prefixHash(String name, long length) throws IOException {
    return store.capture(io -> {
      try {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path(io, name))) {
          byte[] buffer = new byte[FileTransfer.BLOCK_BYTES]; long remaining = length;
          while (remaining > 0) {
            int n = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n < 0) throw new IOException("Local prefix is shorter than pull progress");
            digest.update(buffer, 0, n); remaining -= n;
          }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
      } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    });
  }
  @Override public void append(String name, long offset, byte[] bytes) throws IOException {
    store.capture(io -> {
      var path = path(io, name);
      if (!path.startsWith(robotRoot.resolve("pulled"))) throw new IOException("Only a staged pull can grow");
      try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
        if (channel.size() != offset) throw new IOException("Local size differs from pull progress");
        var buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer, offset + buffer.position());
      }
      return null;
    });
  }
  @Override public String rename(String name, String remote) throws IOException {
    return store.capture(io -> move(io, path(io, name), staging(io, remote), false));
  }
  @Override public String resume(PullManifest.Entry entry) throws IOException {
    return store.capture(io -> {
      var from = path(io, entry.localName());
      return from.startsWith(robotRoot.resolve("pulled")) ? relative(from) : move(io, from, staging(io, entry.remoteName()), false);
    });
  }
  @Override public String archive(String name) throws IOException {
    return store.capture(io -> {
      var from = path(io, name); String base = from.getFileName().toString(); int dot = base.lastIndexOf('.');
      String previous = (dot < 0 ? base : base.substring(0, dot)) + "-previous-" + UUID.randomUUID()
          + (dot < 0 ? "" : base.substring(dot));
      return move(io, from, io.check(from.resolveSibling(previous)), true);
    });
  }
  @Override public void save(PullManifest progress) throws IOException {
    store.capture(io -> {
      if (!identity.serialNumber().equals(progress.serialNumber())) throw new IOException("Pull serial changed");
      for (var entry : java.util.stream.Stream.concat(progress.files().stream(), progress.history().stream()).toList()) path(io, entry.localName());
      io.write(manifest, progress); return null;
    });
  }
  @Override public void verify(String name) throws IOException {
    store.capture(io -> {
      var path = path(io, name);
      var progress = io.read(manifest, PullManifest.class).files().stream().filter(e -> e.localName().equals(name)).findFirst().orElseThrow();
      Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(progress.mtimeMillis()));
      TransferVerification.verify(path, manager); return null;
    });
  }

  private record Match(StoreCatalog.StoredFile candidate, SyncResult result, String basis) {}
  private record MatchDecision(Match match, String reason) {}
  private MatchDecision match(Path source, ImportInspection input, String serial) throws IOException {
    var matches = new java.util.LinkedHashMap<Path, Match>();
    var reasons = new java.util.TreeSet<String>();
    var synchronizer = new LogSynchronizer();
    var rev = input.kind().equals("revlog") ? new RevLogParser(new DbcLoader().load(null)).parseComplete(source) : null;
    var candidates = StoreCatalog.read(store.root(), security).allFiles();
    var capturedSessions = candidates.stream().filter(c -> c.session() != null && c.file().kind().equals("wpilog")
        && c.file().provenance().kind().equals("captured")).map(StoreCatalog.StoredFile::manifestPath).collect(java.util.stream.Collectors.toSet());
    for (var candidate : candidates) {
      if (candidate.session() == null || !candidate.file().kind().equals("wpilog") || candidate.path().equals(source)) continue;
      // Do not chain tolerated offsets through earlier matches and drift away from the session clock.
      if (candidate.file().matching() != null || capturedSessions.contains(candidate.manifestPath())
          && !candidate.file().provenance().kind().equals("captured")) continue;
      if (!input.nearClock(Instant.parse(candidate.session().startedAt()),
          Instant.parse(candidate.session().endedAt()))) { reasons.add("calendar windows do not overlap"); continue; }
      try (var use = manager.acquire(candidate.path().toString())) {
        var facts = LogMetadata.read(use.log());
        String otherSerial = facts.serialNumber() != null ? facts.serialNumber() : candidate.robot().serialNumber();
        if (serial != null && otherSerial != null && !serial.equals(otherSerial)) { reasons.add("known robot serials differ"); continue; }
        SyncResult result;
        if (rev != null) result = synchronizer.synchronize(use.log(), rev);
        else try (var robot = manager.acquire(source.toString())) { result = synchronizer.synchronize(use.log(), robot.log()); }
        if (result.method() != SyncMethod.CROSS_CORRELATION || result.strongPairCount() == 0) {
          reasons.add("no strong data correlation"); continue;
        }
        if (Math.abs(result.offsetMicros()) > MAX_MATCH_OFFSET_US) {
          reasons.add("measured offset exceeds the " + MAX_MATCH_OFFSET_US + " us automatic matching limit"); continue;
        }
        String basis = input.metadata() != null && input.metadata().serialNumber() != null && facts.serialNumber() != null ? "serial_and_data" : "data_alone";
        matches.putIfAbsent(candidate.manifestPath(), new Match(candidate, result, basis));
      } catch (IOException e) {
        reasons.add("a candidate could not be read");
        LoggerFactory.getLogger(PullStore.class).warn("Pull matching skipped {}: {}", candidate.path(), e.getMessage());
      }
    }
    if (matches.size() == 1) return new MatchDecision(matches.values().iterator().next(), null);
    return new MatchDecision(null, "Not automatically matched: " + (matches.size() > 1 ? "several sessions correlate"
        : reasons.isEmpty() ? "no eligible session" : String.join("; ", reasons)));
  }

  @Override public String verified(PullManifest.Entry entry) throws IOException {
    return store.capture(io -> {
      var source = path(io, entry.localName());
      var input = ImportInspection.read(source, new RevLogParser(new DbcLoader().load(null)));
      String serial = input.metadata() != null && input.metadata().serialNumber() != null ? input.metadata().serialNumber() : identity.serialNumber();
      var conflict = !serial.equals(identity.serialNumber()) ? new IdentityConflict(source.getFileName().toString(), serial, identity.serialNumber()) : null;
      if (conflict != null) LoggerFactory.getLogger(PullStore.class).warn("Robot identity disagreement in pulled {}: logged {} versus device {}; logged serial wins", entry.remoteName(), serial, identity.serialNumber());
      var decision = match(source, input, serial); var match = decision.match();
      Path sessionPath;
      Session session;
      if (match != null) { sessionPath = match.candidate().manifestPath(); session = io.read(sessionPath, Session.class); }
      else {
        var robot = io.check(store.root().resolve("robots").resolve(StoreFiles.component(serial)));
        if (!Files.exists(robot.resolve("robot.json"))) io.write(robot.resolve("robot.json"), new Robot(serial, serial, null,
            input.metadata() == null ? identity.comments() : input.metadata().comments(), conflict == null ? "device" : "logged"));
        Instant start = input.start() == null ? Instant.ofEpochMilli(entry.mtimeMillis()).minusNanos(Math.round((input.max() - input.min()) * 1e9)) : input.start();
        Instant end = input.end() == null ? start.plusNanos(Math.round((input.max() - input.min()) * 1e9)) : input.end();
        var day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(start);
        String name = DateTimeFormatter.ofPattern("HHmmss'Z'").withZone(ZoneOffset.UTC).format(start);
        var directory = io.check(robot.resolve("sessions").resolve(day).resolve(name)); int suffix = 2;
        while (Files.exists(directory)) directory = io.check(directory.resolveSibling(name + "_" + suffix++));
        Files.createDirectories(directory); sessionPath = directory.resolve("session.json");
        var facts = input.metadata();
        session = new Session(UUID.randomUUID().toString(), start.toString(), end.toString(),
            input.startBasis() == null ? "modification_time" : input.startBasis(),
            facts == null ? null : facts.event(), facts == null ? null : facts.matchType(),
            facts == null ? null : facts.matchNumber(), facts == null ? null : facts.teamNumber(), List.of(), null, null, identity, List.of());
      }
      var target = io.check(sessionPath.getParent().resolve("robot").resolve(source.getFileName()));
      if (Files.exists(target)) target = target.getParent().resolve(input.hash().substring(0, 16)).resolve(source.getFileName());
      if (Files.exists(target)) target = target.getParent().resolve(UUID.randomUUID().toString()).resolve(source.getFileName());
      Files.createDirectories(target.getParent()); io.check(target);
      move(io, source, target, false);
      var files = new ArrayList<>(session.files());
      Matching evidence = match == null ? null : new Matching("by_correlation", match.candidate().file().sha256(),
          match.result().offsetMicros(), match.result().confidence(), match.result().driftRateNanosPerSec(), match.result().referenceTimeSec(), match.basis());
      files.add(new LogFile(StoreFiles.relative(sessionPath.getParent(), target), input.hash(), input.size(), input.kind(),
          new Provenance("pulled", entry.remoteName(), entry.remoteName().substring(entry.remoteName().lastIndexOf('/') + 1), clock.instant().toString(), true, identity.serialNumber()),
          true, input.min(), input.max(), input.start() == null ? session.startedAt() : input.start().toString(),
          input.end() == null ? session.endedAt() : input.end().toString(), input.startBasis(), false, evidence, input.robotFingerprint(), decision.reason()));
      var conflicts = new ArrayList<>(session.identityConflicts());
      if (conflict != null) conflicts.add(new IdentityConflict(StoreFiles.relative(sessionPath.getParent(), target), serial, identity.serialNumber()));
      io.write(sessionPath, copy(session, files, conflicts));
      return relative(target);
    });
  }

  private static Session copy(Session s, List<LogFile> files, List<IdentityConflict> conflicts) {
    return new Session(s.id(), s.startedAt(), s.endedAt(), s.startBasis(), s.event(), s.matchType(), s.matchNumber(), s.teamNumber(),
        files, s.openCapture(), s.endReason(), s.deviceIdentity(), conflicts);
  }
  private String move(StoreFiles io, Path from, Path target, boolean retain) throws IOException {
    var catalog = StoreCatalog.read(store.root(), security);
    var member = catalog.files().stream().filter(f -> f.path().equals(from)).findFirst();
    try (var reservation = LogFileAccess.reserveMove(List.of(from))) {
      var released = manager.release(from); if (!released.released()) throw new IOException(released.reason());
      Files.createDirectories(target.getParent()); Files.move(io.check(from), io.check(target));
    }
    if (member.isPresent()) {
      var old = member.get(); var records = new ArrayList<LogFile>();
      for (var file : old.session().files()) {
        if (!old.manifestPath().getParent().resolve(file.path()).equals(from)) records.add(file);
        else if (retain) records.add(new LogFile(StoreFiles.relative(old.manifestPath().getParent(), target), file.sha256(), file.sizeBytes(), file.kind(),
            file.provenance(), file.verified(), file.minTimestampSec(), file.maxTimestampSec(), file.startedAt(), file.endedAt(), file.startBasis(), file.truncated(), file.matching(), file.robotFingerprint(), file.matchingReason()));
      }
      io.write(old.manifestPath(), copy(old.session(), records, old.session().identityConflicts()));
    }
    var header = io.read(store.root().resolve("store.json"), Header.class); var moves = new ArrayList<Move>();
    for (var m : header.moves()) moves.add(new Move(m.originalPath(), io.resolve(store.root(), m.movedTo()).equals(from) ? relative(target) : m.movedTo(), m.movedAt()));
    moves.add(new Move(from.toString(), relative(target), clock.instant().toString()));
    io.write(store.root().resolve("store.json"), new Header(header.formatVersion(), header.createdAt(), header.id(), moves, header.addresses()));
    return relative(target);
  }
}
