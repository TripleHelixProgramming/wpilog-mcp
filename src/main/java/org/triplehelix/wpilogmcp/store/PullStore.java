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

/** Pull placement shares store publication rules; payload copies and reader drains stay off its queue. */
public final class PullStore implements FileTransfer.Local {
  /** Several publish cycles of jitter are plausible; seconds of clock shift are another session. */
  public static final long MAX_MATCH_OFFSET_US = 250_000;
  private java.util.function.Consumer<Path> beforePlacement = ignored -> {};
  void beforePlacement(java.util.function.Consumer<Path> action) { beforePlacement = action; }
  @FunctionalInterface interface ParserFactory { RevLogParser get() throws IOException; }
  private ParserFactory parsers = () -> new RevLogParser(new DbcLoader().load(null));
  void parsers(ParserFactory factory) { parsers = factory; }
  @FunctionalInterface interface PrefixHasher { String hash(Path path, long length) throws IOException; }
  private PrefixHasher prefixHasher = PullStore::hashPrefix;
  void prefixHasher(PrefixHasher hasher) { prefixHasher = hasher; }
  private record Prepared(String name, PullManifest.Entry progress, Path path,
      ImportInspection input, TransferVerification.Content content) implements AutoCloseable {
    @Override public void close() { content.close(); }
  }
  // Each empty slot is also an identity token: a mutation invalidates a verification
  // still parsing, even if it has not published its retained reader yet.
  private record Preparation(Prepared value) {}
  private final java.util.concurrent.atomic.AtomicReference<Preparation> prepared =
      new java.util.concurrent.atomic.AtomicReference<>(new Preparation(null));
  private Preparation discardPrepared() {
    var next = new Preparation(null); var old = prepared.getAndSet(next);
    if (old.value() != null) old.value().close();
    return next;
  }
  private final CopyLeaseGroup copyLeases = new CopyLeaseGroup();
  private <T> T mutate(LogStore.CaptureOperation<T> operation) throws IOException {
    discardPrepared();
    try { return copyLeases.await(store.captureAsync(operation)); }
    finally { discardPrepared(); } // Release readers outside the queue, including overlapping verification.
  }
  private final LogStore store;
  private final LogManager manager;
  private final SecurityValidator security;
  private final DeviceIdentity identity;
  private final Clock clock;
  private final LogStore.Mover mover;
  private final Path robotRoot, manifest;
  PullStore(LogStore store, LogManager manager, SecurityValidator security, DeviceIdentity identity, Clock clock) {
    this(store, manager, security, identity, clock, store.mover());
  }
  PullStore(LogStore store, LogManager manager, SecurityValidator security, DeviceIdentity identity,
      Clock clock, LogStore.Mover mover) {
    this.store = store; this.manager = manager; this.security = security; this.identity = identity; this.clock = clock;
    this.mover = mover;
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
  @Override public FileTransfer.Reservation reserve(String remote, long bytes) throws IOException {
    var directory = store.capture(io -> {
      var target = io.check(robotRoot.resolve("pulled")); Files.createDirectories(target); return target;
    });
    return copyLeases.own(store.reserveDiskCopy(directory, bytes));
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
    var source = store.capture(io -> {
      var resolved = path(io, name);
      var snapshot = org.triplehelix.wpilogmcp.log.FileSnapshot.of(resolved);
      if (snapshot == null) throw new IOException("Local pull prefix no longer exists");
      return new java.util.AbstractMap.SimpleImmutableEntry<>(resolved, snapshot);
    });
    final String hash;
    // Hold the read lease only while reading: a mover waiting for it must not also
    // own the queue needed for the final metadata check.
    try (var lease = LogFileAccess.read(source.getKey())) { hash = prefixHasher.hash(source.getKey(), length); }
    return store.capture(io -> {
      if (!source.getKey().equals(path(io, name))
          || !source.getValue().sameAs(org.triplehelix.wpilogmcp.log.FileSnapshot.of(source.getKey()))) {
        throw new IOException("Local pull prefix changed during hashing; retry proof");
      }
      return hash;
    });
  }
  static String hashPrefix(Path path, long length) throws IOException {
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(path)) {
        byte[] buffer = new byte[FileTransfer.BLOCK_BYTES]; long remaining = length;
        while (remaining > 0) {
          int n = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
          if (n < 0) throw new IOException("Local prefix is shorter than pull progress");
          digest.update(buffer, 0, n); remaining -= n;
        }
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
  }
  @Override public int checkpointBytes() { return 1024 * 1024; }
  @Override public void checkpoint(String name, long offset, byte[] bytes, PullManifest progress) throws IOException {
    mutate(io -> { append(io, name, offset, bytes); save(io, progress); return null; });
  }
  @Override public void append(String name, long offset, byte[] bytes) throws IOException {
    mutate(io -> { append(io, name, offset, bytes); return null; });
  }
  private void append(StoreFiles io, String name, long offset, byte[] bytes) throws IOException {
    var path = path(io, name);
    if (!path.startsWith(robotRoot.resolve("pulled"))) throw new IOException("Only a staged pull can grow");
    try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
      if (channel.size() != offset) throw new IOException("Local size differs from pull progress");
      var buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) channel.write(buffer, offset + buffer.position());
      channel.force(true); // The atomic manifest must never get ahead of the staged bytes.
    }
  }
  @Override public boolean truncate(String name, long length) throws IOException {
    return mutate(io -> {
      var path = path(io, name);
      if (!path.startsWith(robotRoot.resolve("pulled"))) return false;
      var progress = io.read(manifest, PullManifest.class).files().stream().filter(e -> e.localName().equals(name)).findFirst();
      if (progress.isEmpty() || progress.get().bytesCopied() != length) throw new IOException("Staged pull checkpoint changed before truncation");
      try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
        if (length < 0 || length > channel.size()) throw new IOException("Invalid staged prefix length");
        channel.truncate(length); channel.force(true);
      }
      return true;
    });
  }
  @Override public String rename(String name, String remote) throws IOException {
    return relocate(name, (io, from) -> staging(io, remote), false);
  }
  @Override public String resume(PullManifest.Entry entry) throws IOException {
    return relocate(entry.localName(), (io, from) -> from.startsWith(robotRoot.resolve("pulled"))
        ? null : staging(io, entry.remoteName()), false);
  }
  @Override public String archive(String name) throws IOException {
    return relocate(name, (io, from) -> {
      String base = from.getFileName().toString(); int dot = base.lastIndexOf('.');
      String previous = (dot < 0 ? base : base.substring(0, dot)) + "-previous-" + UUID.randomUUID()
          + (dot < 0 ? "" : base.substring(dot));
      return io.check(from.resolveSibling(previous));
    }, true);
  }
  @FunctionalInterface private interface Destination { Path create(StoreFiles io, Path from) throws IOException; }
  private record Relocation(Path from, Path target, org.triplehelix.wpilogmcp.log.FileSnapshot snapshot) {}
  private String relocate(String name, Destination destination, boolean retain) throws IOException {
    discardPrepared();
    var move = store.capture(io -> {
      var from = path(io, name);
      var snapshot = org.triplehelix.wpilogmcp.log.FileSnapshot.of(from);
      if (snapshot == null) throw new IOException("Pulled file no longer exists before relocation");
      return new Relocation(from, destination.create(io, from), snapshot);
    });
    if (move.target() == null) return relative(move.from());
    try (var reservation = LogFileAccess.reserveMove(List.of(move.from()))) {
      var released = manager.release(move.from());
      if (!released.released()) throw new IOException(released.reason());
      try (var placement = prepareMove(move.from(), move.target(), move.snapshot(), null)) {
        String result = awaitPlacement(store.captureAsync(io -> {
          if (!move.from().equals(path(io, name))
              || !move.snapshot().sameAs(org.triplehelix.wpilogmcp.log.FileSnapshot.of(move.from()))) {
            throw new IOException("Pulled file changed before relocation; retry");
          }
          if (Files.exists(io.check(move.target()))) throw new IOException("Pull relocation destination changed; retry");
          return moveReserved(io, placement, retain);
        }));
        placement.removeOriginal();
        return result;
      }
    }
  }
  @Override public void save(PullManifest progress) throws IOException {
    mutate(io -> { save(io, progress); return null; });
  }
  private void save(StoreFiles io, PullManifest progress) throws IOException {
    if (!identity.serialNumber().equals(progress.serialNumber())) throw new IOException("Pull serial changed");
    for (var entry : java.util.stream.Stream.concat(progress.files().stream(), progress.history().stream()).toList()) path(io, entry.localName());
    io.write(manifest, progress);
  }
  @Override public void verify(String name) throws IOException {
    var preparing = discardPrepared();
    var source = store.capture(io -> {
      var path = path(io, name);
      var progress = io.read(manifest, PullManifest.class).files().stream().filter(e -> e.localName().equals(name)).findFirst().orElseThrow();
      Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(progress.mtimeMillis()));
      return new java.util.AbstractMap.SimpleImmutableEntry<>(path, progress);
    });
    var parser = parsers.get();
    var content = TransferVerification.open(source.getKey(), manager, parser);
    try {
      var input = ImportInspection.read(source.getKey(), parser, content);
      var next = new Preparation(new Prepared(name, source.getValue(), source.getKey(), input, content));
      if (!prepared.compareAndSet(preparing, next)) throw new IOException("Pull changed during verification; retry verification");
    } catch (Throwable failure) { content.close(); throw failure; }
  }

  private record Match(StoreCatalog.StoredFile candidate, SyncResult result, String basis) {}
  private record MatchDecision(Match match, String reason) {}
  private MatchDecision match(Path source, ImportInspection input, String serial,
      TransferVerification.Content content, List<StoreCatalog.StoredFile> candidates) throws IOException {
    var matches = new java.util.LinkedHashMap<Path, Match>();
    var reasons = new java.util.TreeSet<String>();
    var synchronizer = new LogSynchronizer();
    org.triplehelix.wpilogmcp.revlog.ParsedRevLog rev = null;
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
        if (input.kind().equals("revlog")) {
          if (rev == null) rev = content.revlog();
          result = synchronizer.synchronize(use.log(), rev);
        } else result = synchronizer.synchronize(use.log(), content.wpilog());
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

  /** Only facts used to nominate candidates invalidate the decision; a capture may append. */
  private record CandidateKey(Path path, Path manifest, String robotSerial, String sessionId,
      String startedAt, boolean nearClock, String kind, String provenance, boolean matched, String hash) { }
  private record CandidateSnapshot(List<StoreCatalog.StoredFile> files,
      java.util.Map<CandidateKey, org.triplehelix.wpilogmcp.log.FileSnapshot> stamps) {
    boolean remainsValid(CandidateSnapshot now) {
      if (!stamps.keySet().equals(now.stamps().keySet())) return false;
      for (var entry : stamps.entrySet()) {
        var before = entry.getValue(); var after = now.stamps().get(entry.getKey());
        if (before == null || after == null) return false;
        if (!before.sameAs(after) && !(entry.getKey().hash() == null && after.grewFrom(before))) return false;
      }
      return true;
    }
  }
  private CandidateSnapshot candidates(ImportInspection input) throws IOException {
    var files = StoreCatalog.readManaged(store.root(), security).allFiles();
    var stamps = new java.util.LinkedHashMap<CandidateKey, org.triplehelix.wpilogmcp.log.FileSnapshot>();
    for (var file : files) {
      if (file.session() == null || !file.file().kind().equals("wpilog") || file.path().equals(input.path())) continue;
      var session = file.session();
      var key = new CandidateKey(file.path(), file.manifestPath(), file.robot().serialNumber(), session.id(),
          session.startedAt(), input.nearClock(Instant.parse(session.startedAt()), Instant.parse(session.endedAt())),
          file.file().kind(), file.file().provenance().kind(), file.file().matching() != null, file.file().sha256());
      stamps.put(key, org.triplehelix.wpilogmcp.log.FileSnapshot.of(file.path()));
    }
    return new CandidateSnapshot(files, stamps);
  }

  @Override public String verified(PullManifest.Entry entry) throws IOException {
    var held = prepared.getAndSet(new Preparation(null)).value();
    if (held == null || !held.name().equals(entry.localName())) {
      if (held != null) held.close();
      throw new IOException("Pull has no prepared verification; retry verification before placement");
    }
    var source = held.path(); var input = held.input();
    final CandidateSnapshot candidates;
    final MatchDecision decision;
    try (held) {
      candidates = candidates(input);
      String serial = input.metadata() != null && input.metadata().serialNumber() != null ? input.metadata().serialNumber() : identity.serialNumber();
      decision = match(source, input, serial, held.content(), candidates.files());
    }
    // A retained tool read may take three seconds to drain. That wait belongs to this pull
    // worker, never to the store queue or the NT4 listener creating its next capture.
    try (var reservation = LogFileAccess.reserveMove(List.of(source))) {
      var released = manager.release(source);
      if (!released.released()) throw new IOException(released.reason());
      var plan = awaitPlacement(store.captureAsync(io -> {
        if (!source.equals(path(io, entry.localName())) || !input.snapshot().sameAs(org.triplehelix.wpilogmcp.log.FileSnapshot.of(source))) {
          throw new IOException("Pulled file changed after verification; retry before placement");
        }
        var progress = io.read(manifest, PullManifest.class).files().stream().filter(e -> e.localName().equals(entry.localName())).findFirst().orElse(null);
        if (!entry.equals(progress) || !entry.equals(held.progress())) throw new IOException("Pull progress changed before placement; retry");
        if (!candidates.remainsValid(candidates(input))) throw new IOException("Pull matching candidates changed before placement; retry correlation");
        String serial = input.metadata() != null && input.metadata().serialNumber() != null ? input.metadata().serialNumber() : identity.serialNumber();
        var conflict = !serial.equals(identity.serialNumber()) ? new IdentityConflict(source.getFileName().toString(), serial, identity.serialNumber()) : null;
        if (conflict != null) LoggerFactory.getLogger(PullStore.class).warn("Robot identity disagreement in pulled {}: logged {} versus device {}; logged serial wins", entry.remoteName(), serial, identity.serialNumber());
        var match = decision.match();
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
        return new PlacementPlan(sessionPath, session, target, serial, conflict, match == null);
      }));
      try (var placement = prepareMove(source, plan.target(), input.snapshot(), input.hash())) {
        // Test mutations need the same mapping-free window as final placement on Windows.
        beforePlacement.accept(source);
        String result = awaitPlacement(store.captureAsync(io -> {
          if (!source.equals(path(io, entry.localName()))
              || !input.snapshot().sameAs(org.triplehelix.wpilogmcp.log.FileSnapshot.of(source))) {
            throw new IOException("Pulled file changed after verification; retry before placement");
          }
          var progress = io.read(manifest, PullManifest.class).files().stream()
              .filter(e -> e.localName().equals(entry.localName())).findFirst().orElse(null);
          if (!entry.equals(progress) || !entry.equals(held.progress())) throw new IOException("Pull progress changed before placement; retry");
          if (!candidates.remainsValid(candidates(input))) throw new IOException("Pull matching candidates changed before placement; retry correlation");
          if (Files.exists(io.check(plan.target()))) throw new IOException("Pull placement destination changed; retry");
          Session session;
          if (plan.newSession()) {
            if (Files.exists(io.check(plan.manifest()))) throw new IOException("Pull session changed before placement; retry");
            session = plan.session();
          } else {
            session = io.read(plan.manifest(), Session.class);
            if (!session.id().equals(plan.session().id())) throw new IOException("Pull session changed before placement; retry");
          }
          moveReserved(io, placement, false);
          var target = plan.target(); var sessionPath = plan.manifest(); var match = decision.match();
          var files = new ArrayList<>(session.files());
          // A capture still recording has no hash yet, so its recording names the clock this was measured on.
          var recording = match == null ? null : CaptureRecording.of(match.candidate().file().provenance(),
              io.read(store.root().resolve("store.json"), Header.class).id()).orElse(null);
          Matching evidence = match == null ? null : new Matching("by_correlation", match.candidate().file().sha256(),
              match.result().offsetMicros(), match.result().confidence(), match.result().driftRateNanosPerSec(), match.result().referenceTimeSec(), match.basis(), match.result(), recording);
          files.add(new LogFile(StoreFiles.relative(sessionPath.getParent(), target), input.hash(), input.size(), input.kind(),
              new Provenance("pulled", entry.remoteName(), entry.remoteName().substring(entry.remoteName().lastIndexOf('/') + 1), clock.instant().toString(), true, identity.serialNumber()),
              true, input.min(), input.max(), input.start() == null ? session.startedAt() : input.start().toString(),
              input.end() == null ? session.endedAt() : input.end().toString(), input.startBasis(), false, evidence, input.robotFingerprint(), decision.reason()));
          var conflicts = new ArrayList<>(session.identityConflicts());
          if (plan.conflict() != null) conflicts.add(new IdentityConflict(StoreFiles.relative(sessionPath.getParent(), target), plan.serial(), identity.serialNumber()));
          io.write(sessionPath, copy(session, files, conflicts));
          return relative(target);
        }));
        placement.removeOriginal();
        return result;
      }
    }
  }

  private record PlacementPlan(Path manifest, Session session, Path target, String serial,
      IdentityConflict conflict, boolean newSession) {}

  private static Session copy(Session s, List<LogFile> files, List<IdentityConflict> conflicts) {
    return new Session(s.id(), s.startedAt(), s.endedAt(), s.startBasis(), s.event(), s.matchType(), s.matchNumber(), s.teamNumber(),
        files, s.openCapture(), s.endReason(), s.deviceIdentity(), conflicts, s.conflicts(), s.captureStats(), s.systemLogs(), s.mergedSessionIds(), s.clocks());
  }
  /**
   * Prepared copies own their allocation and marker until the admitted publication unwinds.
   * A filesystem provider must never silently turn a store-queue rename into a payload copy.
   */
  private final class PreparedMove implements AutoCloseable {
    final Path from, target, staged;
    final ImportStage owner;
    final DiskSpaceBudgets.Reservation disk;
    final org.triplehelix.wpilogmcp.log.FileSnapshot sourceSnapshot;
    final String sourceHash;
    org.triplehelix.wpilogmcp.log.FileSnapshot stagedSnapshot;
    PreparedMove(Path from, Path target, Path staged, ImportStage owner,
        DiskSpaceBudgets.Reservation disk,
        org.triplehelix.wpilogmcp.log.FileSnapshot sourceSnapshot, String sourceHash) {
      this.from = from; this.target = target; this.staged = staged; this.owner = owner; this.disk = disk;
      this.sourceSnapshot = sourceSnapshot; this.sourceHash = sourceHash;
    }
    void place(StoreFiles io) throws IOException {
      var moving = io.check(staged == null ? from : staged);
      if (staged != null && (stagedSnapshot == null
          || !stagedSnapshot.sameAs(org.triplehelix.wpilogmcp.log.FileSnapshot.of(moving)))) {
        throw new IOException("Prepared pull copy changed before placement; retry");
      }
      mover.move(moving, io.check(target), StandardCopyOption.ATOMIC_MOVE);
    }
    void removeOriginal() throws IOException {
      if (staged == null) return;
      security.validate(from);
      if (!sourceSnapshot.sameAs(org.triplehelix.wpilogmcp.log.FileSnapshot.of(from))
          || !sourceHash.equals(StoreFiles.hash(from))) {
        throw new IOException("Pulled source changed after placement; original retained");
      }
      mover.delete(from);
    }
    @Override public void close() throws IOException {
      try { if (staged != null && Files.exists(staged)) mover.discardCopy(staged); }
      finally {
        try { if (owner != null) owner.close(); }
        finally { if (disk != null) disk.close(); }
      }
    }
  }

  /** The source already has a move reservation, so neither reader drains nor copying use a turn. */
  private PreparedMove prepareMove(Path from, Path target,
      org.triplehelix.wpilogmcp.log.FileSnapshot snapshot, String expectedHash) throws IOException {
    var io = new StoreFiles(store.root(), security);
    Files.createDirectories(io.check(target.getParent()));
    if (mover.sameFileStore(from, target)) {
      return new PreparedMove(from, target, null, null, null, snapshot, null);
    }
    ImportStage owner = null;
    DiskSpaceBudgets.Reservation disk = null;
    PreparedMove result = null;
    try {
      owner = ImportStage.create(io, target.getParent());
      var staged = owner.path(Path.of("payload"));
      if (!FilesystemIdentity.same(staged.getParent(), target.getParent())) {
        throw new IOException("Pull placement stage is not on the destination filesystem");
      }
      disk = store.reserveDiskCopy(staged.getParent(), snapshot.size());
      String sourceHash = expectedHash == null ? StoreFiles.hash(from) : expectedHash;
      result = new PreparedMove(from, target, staged, owner, disk, snapshot, sourceHash);
      var attributes = mover.copy(from, staged, snapshot.size());
      if (Files.size(staged) != snapshot.size() || !sourceHash.equals(StoreFiles.hash(staged))) {
        throw new IOException("Cross-filesystem pull copy differs from its verified source");
      }
      mover.force(staged);
      attributes.restore(staged);
      // Publication checks this fixed copy without hashing its payload on the store queue.
      result.stagedSnapshot = org.triplehelix.wpilogmcp.log.FileSnapshot.of(staged);
      if (!snapshot.sameAs(org.triplehelix.wpilogmcp.log.FileSnapshot.of(from))) {
        throw new IOException("Pulled file changed during cross-filesystem copy; retry");
      }
      return result;
    } catch (IOException | RuntimeException | Error failure) {
      if (result != null) {
        try { result.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
      } else {
        try { if (owner != null) owner.close(); }
        catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
        finally { if (disk != null) disk.close(); }
      }
      throw failure;
    }
  }

  /**
   * An already admitted short turn owns its stage and reader barrier through completion.
   * Preserve interruption without retiring those resources while the store still uses them.
   */
  private static <T> T awaitPlacement(java.util.concurrent.CompletableFuture<T> operation) throws IOException {
    boolean interrupted = false;
    try {
      for (;;) {
        try { return operation.get(); }
        catch (InterruptedException stopped) { interrupted = true; }
        catch (java.util.concurrent.ExecutionException failed) {
          throw new IOException("Pull placement failed: " + failed.getCause().getMessage(), failed.getCause());
        }
      }
    } finally { if (interrupted) Thread.currentThread().interrupt(); }
  }

  /** The caller reserved and drained this source outside the queue. Only its own session changes. */
  private String moveReserved(StoreFiles io, PreparedMove placement, boolean retain) throws IOException {
    var from = placement.from; var target = placement.target;
    Path sessionPath = null; Session session = null; var records = new ArrayList<LogFile>();
    if (!from.startsWith(robotRoot.resolve("pulled"))) {
      var parts = store.root().relativize(from);
      var directory = store.root().resolve(parts.subpath(0, 5));
      sessionPath = directory.resolve("session.json"); session = io.read(sessionPath, Session.class);
      for (var file : session.files()) {
        if (!io.resolve(directory, file.path()).equals(from)) records.add(file);
        else if (retain) records.add(new LogFile(StoreFiles.relative(directory, target), file.sha256(), file.sizeBytes(), file.kind(),
            file.provenance(), file.verified(), file.minTimestampSec(), file.maxTimestampSec(), file.startedAt(), file.endedAt(), file.startBasis(), file.truncated(), file.matching(), file.robotFingerprint(), file.matchingReason(), file.placementMethod()));
      }
    }
    placement.place(io);
    if (session != null) io.write(sessionPath, copy(session, records, session.identityConflicts()));
    var header = io.read(store.root().resolve("store.json"), Header.class); var moves = new ArrayList<Move>();
    for (var m : header.moves()) moves.add(new Move(m.originalPath(), io.resolve(store.root(), m.movedTo()).equals(from) ? relative(target) : m.movedTo(), m.movedAt()));
    moves.add(new Move(from.toString(), relative(target), clock.instant().toString()));
    io.write(store.root().resolve("store.json"), header.withMoves(moves));
    return relative(target);
  }
}
