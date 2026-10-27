/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.capture.WpilogOutput;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogFileAccess;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;
import org.triplehelix.wpilogmcp.tools.SignalResolver.MetadataRole;

/**
 * Captures use the same store manifests and queue as imports. The optional open_capture field
 * leaves the existing finished-file contract intact: hashes and exact sizes are facts only after
 * close. Updates coalesce off the NT4 loop; the directory stays stable until close so rollover
 * opens and mapping growth cannot race a cosmetic rename. The manifest already has the match.
 */
public final class CaptureStore implements CaptureWriter.Observer {
  @FunctionalInterface interface Mover { void move(Path from, Path to) throws IOException; }
  private final LogStore store;
  private final LogManager manager;
  private final Clock clock;
  private final Mover mover;
  private final EnumMap<MetadataRole, Long> times = new EnumMap<>(MetadataRole.class);
  private String event, matchType;
  private Integer matchNumber, teamNumber;
  private long typeOrdinal, number;
  private final java.util.Map<String, String> loggedSerials = new java.util.LinkedHashMap<>();
  private final List<IdentityConflict> conflicts = new ArrayList<>();
  public static final long UPDATE_PERIOD_US = 5_000_000;
  private record Update(CaptureWriter.Session owner, String name, String writerIdentity, boolean open, Instant endedAt,
      long size, double min, double max, List<CaptureWriter.ClosedFile> files,
      String event, String matchType, Integer matchNumber, Integer teamNumber, String endReason,
      org.triplehelix.wpilogmcp.capture.context.DeviceIdentity identity, List<IdentityConflict> conflicts, org.triplehelix.wpilogmcp.capture.CaptureStats statistics) {}
  private record Pending(Update update, CompletableFuture<Void> done) {}
  private final AtomicReference<Pending> pending = new AtomicReference<>();
  private final AtomicBoolean queued = new AtomicBoolean();
  private final AtomicLong writes = new AtomicLong(), submissions = new AtomicLong();
  /** The session object survives reconnects and rollovers, but a new boot starts its own count. */
  private record ClosedFiles(CaptureWriter.Session owner, int count) {}
  private final AtomicReference<ClosedFiles> closedFiles = new AtomicReference<>();
  private volatile CompletableFuture<Void> completion = CompletableFuture.completedFuture(null);
  private long submittedAtUs;
  private final java.util.Map<Path, Session> reopening = new java.util.concurrent.ConcurrentHashMap<>();
  private org.triplehelix.wpilogmcp.capture.context.DeviceIdentity recordedIdentity;
  /** The NT4 loop publishes these facts before any asynchronous manifest work. */
  public record Status(Path path, Instant startedAt, Instant endedAt, String address, boolean open,
      String endReason, org.triplehelix.wpilogmcp.capture.context.DeviceIdentity identity,
      org.triplehelix.wpilogmcp.capture.CaptureStats statistics, String event, String matchType, Integer matchNumber,
      String replicaPublicationFailure) {
    public Status(Path path, Instant startedAt, Instant endedAt, String address, boolean open, String endReason,
        org.triplehelix.wpilogmcp.capture.context.DeviceIdentity identity, org.triplehelix.wpilogmcp.capture.CaptureStats statistics,
        String event, String matchType, Integer matchNumber) {
      this(path, startedAt, endedAt, address, open, endReason, identity, statistics, event, matchType, matchNumber, null);
    }
  }
  private volatile Status lastStatus;
  private java.util.function.Consumer<Status> status = ignored -> {};
  public void onStatus(java.util.function.Consumer<Status> listener) { status = listener; }
  private BiConsumer<Path, Path> moved = (from, to) -> {};

  CaptureStore(LogStore store, LogManager manager, Clock clock) { this(store, manager, clock, Files::move); }
  CaptureStore(LogStore store, LogManager manager, Clock clock, Mover mover) {
    this.store = store; this.manager = manager; this.clock = clock; this.mover = mover;
  }
  public void onMove(BiConsumer<Path, Path> moved) { this.moved = moved; }

  /** Startup joins the same queue as imports; no client can write until this sweep finishes. */
  public CompletableFuture<Void> recoverAsync() {
    return store.recordingAsync(io -> { CaptureRecovery.run(io, store.root()); return null; });
  }

  @Override public Path create(String address, Instant start) throws IOException {
    return create(address, start, null, null);
  }
  @Override public Path create(String address, Instant start, CaptureWriter.Session previous) throws IOException {
    return create(address, start, previous, null);
  }
  @Override public Path create(String address, Instant start, CaptureWriter.Session previous,
      org.triplehelix.wpilogmcp.capture.context.DeviceIdentity identity) throws IOException {
    try { return createAsync(address, start, previous, identity).get(); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted capture creation", e); }
    catch (java.util.concurrent.ExecutionException e) { throw new IOException("Capture creation failed", e.getCause()); }
  }
  @Override public CompletableFuture<Path> createAsync(String address, Instant start, CaptureWriter.Session previous,
      org.triplehelix.wpilogmcp.capture.context.DeviceIdentity identity) {
    return createAsync(address, start, previous, identity, CaptureWriter.ADMISSION_LIMITS.timeoutUs());
  }
  @Override public CompletableFuture<Path> createAsync(String address, Instant start, CaptureWriter.Session previous,
      org.triplehelix.wpilogmcp.capture.context.DeviceIdentity identity, long timeoutUs) {
    long deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.MICROSECONDS.toNanos(timeoutUs);
    if (previous == null) {
      times.clear(); loggedSerials.clear(); conflicts.clear();
      event = null; matchType = null; matchNumber = null; teamNumber = null; typeOrdinal = 0; number = 0;
    }
    var preceding = completion;
    var result = preceding.thenCompose(ignored -> store.recordingAsync(io -> {
      if (identity != null) {
        RobotIdentityStore.record(io, store.root(), identity, clock.instant());
        RobotIdentityStore.promote(io, store.root(), manager, null, identity, clock.instant());
      }
      if (previous != null) {
        // Earlier close/rename precedes admission. The closed file keeps its hash: the writer
        // continues the session in its next file beside it, which opened() then records.
        var original = previous.path();
        var path = Files.exists(original) ? original : io.read(store.root().resolve("store.json"), Header.class).moves().stream()
            .filter(m -> Path.of(m.originalPath()).equals(original)).reduce((a, b) -> b)
            .map(m -> store.root().resolve(m.movedTo())).orElse(original);
        path = io.check(path);
        reopening.put(path.getParent(), io.read(path.getParent().resolve("session.json"), Session.class));
        return path;
      }
      var header = io.read(store.root().resolve("store.json"), Header.class);
      String id = header.addresses().getOrDefault(address, RobotIdentityStore.addressId(address));
      var robot = store.root().resolve("robots").resolve(StoreFiles.component(id));
      if (!Files.exists(io.check(robot.resolve("robot.json")))) io.write(robot.resolve("robot.json"), new Robot(id, null, null, null, "address"));
      var day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(start);
      var name = DateTimeFormatter.ofPattern("HHmmss'Z'").withZone(ZoneOffset.UTC).format(start);
      var parent = io.check(robot.resolve("sessions").resolve(day)); Files.createDirectories(parent);
      var directory = parent.resolve(name); int suffix = 2;
      while (true) {
        try { Files.createDirectory(io.check(directory)); break; }
        catch (java.nio.file.FileAlreadyExistsException e) { directory = parent.resolve(name + "_" + suffix++); }
      }
      io.write(directory.resolve("session.json"), new Session(UUID.randomUUID().toString(), start.toString(),
          start.toString(), "pit_clock", null, null, null, null, List.of()));
      return directory.resolve("capture.wpilog");
    }, deadlineNanos));
    // A refused new admission is retryable; an earlier unfinished close remains a barrier.
    completion = result.handle((ignored, error) -> (Void) null).thenCompose(ignored -> preceding);
    return result;
  }
  @Override public CompletableFuture<Void> abandoned(Path path, Instant endedAt, String reason) {
    var result = store.recordingAsync(io -> {
      var manifest = io.check(path).getParent().resolve("session.json");
      var old = io.read(manifest, Session.class);
      var previous = reopening.remove(path.getParent());
      var files = new ArrayList<>(old.files());
      if (previous != null) previous.files().stream().filter(f -> f.path().equals(path.getFileName().toString()))
          .filter(f -> files.stream().noneMatch(current -> current.path().equals(f.path()))).forEach(files::add);
      io.write(manifest, new Session(old.id(), old.startedAt(), previous == null ? endedAt.toString() : previous.endedAt(), old.startBasis(),
          old.event(), old.matchType(), old.matchNumber(), old.teamNumber(), List.copyOf(files), null, reason,
          old.deviceIdentity(), old.identityConflicts(), old.conflicts(), old.captureStats(), old.systemLogs(), old.mergedSessionIds(), old.clocks()));
      return (Void) null;
    });
    completion = result; return result;
  }
  @Override public void opened(CaptureWriter.Session session) { reopening.remove(session.path().getParent()); update(session, true); }
  @Override public void flushed(CaptureWriter.Session session) { update(session, false); }
  @Override public void closed(CaptureWriter.Session session) { update(session, true); }
  @Override public void identity(CaptureWriter.Session session) { update(session, true); }
  @Override public void providers(CaptureWriter.Session session) { update(session, true); }
  public CompletableFuture<Void> completion() { return completion; }
  long manifestWrites() { return writes.get(); }
  long queuedUpdates() { return submissions.get(); }

  /** An entry's metadata role, which its name and type decide once; the writer's loop only. */
  private record Role(String type, MetadataRole role) {}
  private final Map<String, Role> roles = new HashMap<>();

  private MetadataRole role(EntryInfo entry) {
    var known = roles.get(entry.name());
    if (known == null || !known.type().equals(entry.type())) {
      known = new Role(entry.type(), MetadataRole.of(entry.name(), entry.type()).orElse(null));
      roles.put(entry.name(), known);
    }
    return known.role();
  }

  @Override public void value(CaptureWriter.Session session, EntryInfo entry, ValueFrame frame, WpilogOutput.Written written)
      throws IOException {
    var role = role(entry);
    if (role == null || role == MetadataRole.COMMENTS) return;
    if (role == MetadataRole.SERIAL) {
      if (frame.value() instanceof String serial && !serial.isBlank()) {
        String previous = loggedSerials.put(session.path().getFileName().toString(), serial.strip());
        if (!serial.strip().equals(previous)) update(session, true);
      }
      return;
    }
    if (times.containsKey(role) && times.get(role) > frame.timestampUs()) return;
    times.put(role, frame.timestampUs());
    String oldEvent = event, oldType = matchType; Integer oldMatch = matchNumber, oldTeam = teamNumber;
    Object value = frame.value();
    switch (role) {
      case EVENT -> { if (value instanceof String s && !s.isBlank()) event = s.strip(); }
      case MATCH_TYPE -> typeOrdinal = ((Number) value).longValue();
      case MATCH_NUMBER -> number = ((Number) value).longValue();
      case TEAM -> { long team = ((Number) value).longValue(); if (team > 0 && team <= Integer.MAX_VALUE) teamNumber = (int) team; }
      default -> { }
    }
    if (typeOrdinal >= 1 && typeOrdinal <= 3 && number > 0 && number <= Integer.MAX_VALUE) {
      matchType = LogDirectory.MatchType.fromOrdinal((int) typeOrdinal).getFriendlyName(); matchNumber = (int) number;
    }
    if (!Objects.equals(oldEvent, event) || !Objects.equals(oldType, matchType)
        || !Objects.equals(oldMatch, matchNumber) || !Objects.equals(oldTeam, teamNumber)) update(session, true);
  }

  private void update(CaptureWriter.Session capture, boolean factChanged) {
    if (capture.identity() != null) loggedSerials.forEach((path, serial) -> {
      var conflict = new IdentityConflict(path, serial, capture.identity().serialNumber());
      if (!serial.equals(conflict.deviceSerial()) && !conflicts.contains(conflict)) {
        conflicts.add(conflict);
        org.slf4j.LoggerFactory.getLogger(CaptureStore.class).warn(
            "Robot identity disagreement in {}: logged {} versus device {}; the file's logged serial wins",
            capture.path().resolveSibling(path), serial, conflict.deviceSerial());
      }
    });
    var statistics = capture.statistics();
    var current = new Status(capture.path(), capture.startedAt(), capture.endedAt(), capture.address(), capture.open(),
        capture.endReason(), capture.identity(), statistics, event, matchType, matchNumber, store.replicaPublicationFailure());
    lastStatus = current; status.accept(current);
    long now = capture.observedAtUs();
    boolean due = factChanged || now - submittedAtUs >= UPDATE_PERIOD_US;
    if (!due && pending.get() == null) return;
    if (due) submittedAtUs = now;
    var snapshot = new Update(capture, capture.path().getFileName().toString(), capture.writerIdentity(), capture.open(),
        capture.open() ? capture.observedAt() : capture.endedAt(), capture.sizeBytes(),
        capture.minTimestampUs() / 1_000_000.0, capture.maxTimestampUs() / 1_000_000.0,
        capture.files(), event, matchType, matchNumber, teamNumber, capture.endReason(), capture.identity(), List.copyOf(conflicts), statistics);
    var next = pending.updateAndGet(old -> new Pending(snapshot,
        old == null ? new CompletableFuture<>() : old.done()));
    completion = next.done();
    dispatch();
  }

  private void dispatch() {
    if (!queued.compareAndSet(false, true)) return;
    submissions.incrementAndGet();
    var selected = new AtomicReference<Pending>();
    store.recordingAsync(io -> {
      var next = pending.getAndSet(null); selected.set(next);
      if (next != null) write(io, next.update());
      return null;
    }).whenComplete((ignored, error) -> {
      var next = selected.get();
      if (next == null) next = pending.getAndSet(null);
      // A file that just closed has its hash; its clock record is read off this queue. The pass is
      // asked for before the update completes, so a close that then waits for settled clock records
      // includes it: it was asked for after, and a stop could close the store under it.
      if (error == null && next != null) {
        var update = next.update();
        var before = closedFiles.getAndSet(new ClosedFiles(update.owner(), update.files().size()));
        int previousCount = before != null && before.owner() == update.owner() ? before.count() : 0;
        if (!update.open() || update.files().size() > previousCount) store.keepClocks();
      }
      if (next != null) {
        if (error == null) next.done().complete(null); else next.done().completeExceptionally(error);
      }
      if (error != null) org.slf4j.LoggerFactory.getLogger(CaptureStore.class).error("Capture manifest update failed", error);
      var current = lastStatus;
      if (current != null && store.replicaPublicationFailure() != null) status.accept(new Status(current.path(), current.startedAt(), current.endedAt(),
          current.address(), current.open(), current.endReason(), current.identity(), current.statistics(), current.event(), current.matchType(),
          current.matchNumber(), store.replicaPublicationFailure()));
      queued.set(false);
      if (pending.get() != null) dispatch();
    });
  }

  private void write(StoreFiles io, Update update) throws IOException {
    var capture = update.owner();
    Path before = capture.path();
    if (update.identity() != null && !update.identity().equals(recordedIdentity)) {
      RobotIdentityStore.record(io, store.root(), update.identity(), clock.instant());
      recordedIdentity = update.identity();
    }
    Path after = writeSnapshot(io, update, before);
    if (!update.open() && update.identity() != null) {
      after = RobotIdentityStore.promote(io, store.root(), manager, after, update.identity(), clock.instant());
    }
    if (!before.equals(after)) { capture.relocate(after); moved.accept(before, after); }
  }

  private Path writeSnapshot(StoreFiles io, Update update, Path before) throws IOException {
    var capture = update.owner();
    var directory = before.getParent();
    var path = directory.resolve("session.json");
    var old = io.read(path, Session.class);
    // A peer capture has the same provenance kind. Replace only this writer's own files;
    // a flush must retain the other laptop's contribution to the same boot.
    var owned = update.files().stream().map(CaptureWriter.ClosedFile::name).collect(java.util.stream.Collectors.toSet());
    owned.add(update.name());
    var files = old.files().stream().filter(f -> !owned.contains(f.path()))
        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    for (var closed : update.files()) {
      var file = io.check(directory.resolve(closed.name()));
      var previous = old.files().stream().filter(f -> f.path().equals(closed.name()) && f.sizeBytes() == closed.sizeBytes()).findFirst();
      String hash = previous.isPresent() ? previous.get().sha256() : StoreFiles.hash(file);
      files.add(new LogFile(closed.name(), hash, closed.sizeBytes(), "wpilog",
          new Provenance(Provenance.CAPTURED, null, closed.name(), capture.startedAt().toString(), false,
              null, List.of(), closed.writerIdentity()), true,
          closed.minUs() / 1_000_000.0, closed.maxUs() / 1_000_000.0,
          capture.startedAt().toString(), closed.endedAt().toString(), "pit_clock", false, null));
    }
    OpenCapture open = update.open() ? new OpenCapture(update.name(),
        new Provenance(Provenance.CAPTURED, null, update.name(), capture.startedAt().toString(), false,
            null, List.of(), update.writerIdentity()),
        update.size(), update.min(), update.max()) : null;
    var identityConflicts = java.util.stream.Stream.concat(old.identityConflicts().stream(), update.conflicts().stream()).distinct().toList();
    String ended = Instant.parse(old.endedAt()).isAfter(update.endedAt()) ? old.endedAt() : update.endedAt().toString();
    var session = new Session(old.id(), old.startedAt(), ended,
        old.startBasis(), update.event() == null ? old.event() : update.event(), update.matchType() == null ? old.matchType() : update.matchType(),
        update.matchNumber() == null ? old.matchNumber() : update.matchNumber(), update.teamNumber() == null ? old.teamNumber() : update.teamNumber(),
        List.copyOf(files), open, update.endReason(), update.identity() == null ? old.deviceIdentity() : update.identity(), identityConflicts, old.conflicts(), update.statistics(), old.systemLogs(), old.mergedSessionIds(), old.clocks());
    io.write(path, session); writes.incrementAndGet();
    // Keep the directory stable while its writer can open another rollover file or remap.
    // Creation of a resumed capture is a queue barrier, so it cannot race this close-time move.
    if (update.open() || update.event() == null || update.matchNumber() == null || update.matchType() == null) return before;
    var now = clock.instant();
    String event = update.event(), matchType = update.matchType(); Integer matchNumber = update.matchNumber();
    String prefix = DateTimeFormatter.ofPattern("HHmmss'Z'").withZone(ZoneOffset.UTC).format(capture.startedAt());
    String code = switch (matchType) { case "Practice" -> "P"; case "Qualification" -> "Q"; default -> "E"; };
    String name = prefix + "_" + event.replaceAll("[^A-Za-z0-9_-]", "_") + "_" + code + matchNumber;
    var target = io.check(directory.resolveSibling(name));
    if (target.equals(directory) || directory.getFileName().toString().matches(java.util.regex.Pattern.quote(name) + "_[0-9]+")) return before;
    int suffix = 2;
    while (Files.exists(target)) target = io.check(directory.resolveSibling(name + "_" + suffix++));
    try (var reservation = LogFileAccess.reserveMove(List.of(directory))) {
      if (!manager.release(directory).released()) return before;
      try { mover.move(io.check(directory), target); }
      catch (java.nio.file.FileSystemException e) {
        // Windows refuses an open/mapped capture. Keep its facts and retry after close.
        org.slf4j.LoggerFactory.getLogger(CaptureStore.class).debug("Capture rename deferred: {}", e.getMessage());
        return before;
      }
      var header = io.read(store.root().resolve("store.json"), Header.class);
      var moves = new ArrayList<Move>();
      for (var move : header.moves()) {
        var oldTarget = io.resolve(store.root(), move.movedTo());
        moves.add(new Move(move.originalPath(), StoreFiles.relative(store.root(), oldTarget.startsWith(directory)
            ? target.resolve(directory.relativize(oldTarget)) : oldTarget), move.movedAt()));
      }
      var destination = target.resolve(before.getFileName());
      // A verified pull can join an open capture. Its persisted pull.json path needs the same
      // alias as the capture when this directory is renamed, before identity promotion follows.
      for (var file : files) moves.add(new Move(io.resolve(directory, file.path()).toString(),
          StoreFiles.relative(store.root(), io.resolve(target, file.path())), now.toString()));
      io.write(store.root().resolve("store.json"), header.withMoves(moves));
      return destination;
    }
  }
}
