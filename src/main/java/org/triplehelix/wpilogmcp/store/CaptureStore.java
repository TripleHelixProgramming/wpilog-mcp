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
import java.util.List;
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
  private record Update(CaptureWriter.Session owner, String name, boolean open, Instant endedAt,
      long size, double min, double max, List<CaptureWriter.ClosedFile> files,
      String event, String matchType, Integer matchNumber, Integer teamNumber, String endReason,
      org.triplehelix.wpilogmcp.capture.context.DeviceIdentity identity, List<IdentityConflict> conflicts) {}
  private record Pending(Update update, CompletableFuture<Void> done) {}
  private final AtomicReference<Pending> pending = new AtomicReference<>();
  private final AtomicBoolean queued = new AtomicBoolean();
  private final AtomicLong writes = new AtomicLong(), submissions = new AtomicLong();
  private volatile CompletableFuture<Void> completion = CompletableFuture.completedFuture(null);
  private long submittedAtUs;
  private BiConsumer<Path, Path> moved = (from, to) -> {};

  CaptureStore(LogStore store, LogManager manager, Clock clock) { this(store, manager, clock, Files::move); }
  CaptureStore(LogStore store, LogManager manager, Clock clock, Mover mover) {
    this.store = store; this.manager = manager; this.clock = clock; this.mover = mover;
  }
  public void onMove(BiConsumer<Path, Path> moved) { this.moved = moved; }

  /** Startup joins the same queue as imports; no client can write until this sweep finishes. */
  public CompletableFuture<Void> recoverAsync() {
    return store.captureAsync(io -> { CaptureRecovery.run(io, store.root()); return null; });
  }

  @Override public Path create(String address, Instant start) throws IOException {
    awaitPrevious();
    times.clear(); loggedSerials.clear(); conflicts.clear();
    event = null; matchType = null; matchNumber = null; teamNumber = null; typeOrdinal = 0; number = 0;
    return store.capture(io -> {
      // Percent escapes are portable and injective, unlike replacing every IPv6 ':' with '_'.
      var header = io.read(store.root().resolve("store.json"), Header.class);
      String id = header.addresses().getOrDefault(address, RobotIdentityStore.addressId(address));
      var robot = store.root().resolve("robots").resolve(StoreFiles.component(id));
      if (!Files.exists(io.check(robot.resolve("robot.json")))) {
        io.write(robot.resolve("robot.json"), new Robot(id, null, null, null, "address"));
      }
      var day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(start);
      var name = DateTimeFormatter.ofPattern("HHmmss'Z'").withZone(ZoneOffset.UTC).format(start);
      var parent = io.check(robot.resolve("sessions").resolve(day)); Files.createDirectories(parent);
      var directory = parent.resolve(name);
      int suffix = 2;
      while (true) {
        try { Files.createDirectory(io.check(directory)); break; }
        catch (java.nio.file.FileAlreadyExistsException e) { directory = parent.resolve(name + "_" + suffix++); }
      }
      io.write(directory.resolve("session.json"), new Session(UUID.randomUUID().toString(), start.toString(),
          start.toString(), "pit_clock", null, null, null, null, List.of()));
      return directory.resolve("capture.wpilog");
    });
  }

  @Override public Path create(String address, Instant start, CaptureWriter.Session previous,
      org.triplehelix.wpilogmcp.capture.context.DeviceIdentity identity) throws IOException {
    if (identity != null) store.capture(io -> {
      var robot = RobotIdentityStore.record(io, store.root(), identity, clock.instant());
      RobotIdentityStore.promote(io, store.root(), manager, null, identity, clock.instant());
      return robot;
    });
    return create(address, start, previous);
  }

  @Override public Path identified(CaptureWriter.Session session,
      org.triplehelix.wpilogmcp.capture.context.DeviceIdentity identity) throws IOException {
    awaitPrevious();
    return store.capture(io -> {
      RobotIdentityStore.record(io, store.root(), identity, clock.instant());
      var before = session.path();
      var after = RobotIdentityStore.promote(io, store.root(), manager, before, identity, clock.instant());
      if (!before.equals(after)) moved.accept(before, after);
      return after;
    });
  }

  @Override public Path create(String address, Instant start, CaptureWriter.Session previous) throws IOException {
    if (previous == null) return create(address, start);
    awaitPrevious();
    // The preceding close (and any cosmetic move) precedes this create in the same queue.
    // Remove the old hash before reopening its bytes, so the catalog never sees a stale verified size.
    return store.capture(io -> {
      var original = previous.path();
      var path = Files.exists(original) ? original : io.read(store.root().resolve("store.json"), Header.class).moves().stream()
          .filter(m -> Path.of(m.originalPath()).equals(original)).reduce((a, b) -> b)
          .map(m -> store.root().resolve(m.movedTo())).orElse(original);
      path = io.check(path);
      var manifest = path.getParent().resolve("session.json");
      var old = io.read(manifest, Session.class);
      var provenance = new Provenance("captured", null, path.getFileName().toString(), start.toString(), false);
      String filename = path.getFileName().toString();
      var files = old.files().stream().filter(f -> !f.path().equals(filename)).toList();
      io.write(manifest, new Session(old.id(), old.startedAt(), clock.instant().toString(), old.startBasis(),
          old.event(), old.matchType(), old.matchNumber(), old.teamNumber(), files,
          new OpenCapture(path.getFileName().toString(), provenance, Files.size(path),
              previous.minTimestampUs() / 1_000_000.0, previous.maxTimestampUs() / 1_000_000.0), null,
          old.deviceIdentity(), old.identityConflicts()));
      return path;
    });
  }
  @Override public void opened(CaptureWriter.Session session, boolean resumed) { update(session, true); }
  @Override public void flushed(CaptureWriter.Session session) { update(session, false); }
  @Override public void closed(CaptureWriter.Session session) { update(session, true); }
  @Override public void identity(CaptureWriter.Session session) { update(session, true); }
  public CompletableFuture<Void> completion() { return completion; }
  private void awaitPrevious() throws IOException {
    try { completion.get(); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted capture creation", e); }
    catch (java.util.concurrent.ExecutionException e) { throw new IOException("Previous capture manifest failed", e.getCause()); }
  }
  long manifestWrites() { return writes.get(); }
  long queuedUpdates() { return submissions.get(); }

  @Override public void value(CaptureWriter.Session session, EntryInfo entry, ValueFrame frame, WpilogOutput.Written written)
      throws IOException {
    var role = MetadataRole.of(entry.name(), entry.type()).orElse(null);
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
    long now = capture.observedAtUs();
    boolean due = factChanged || now - submittedAtUs >= UPDATE_PERIOD_US;
    if (!due && pending.get() == null) return;
    if (due) submittedAtUs = now;
    var snapshot = new Update(capture, capture.path().getFileName().toString(), capture.open(),
        capture.open() ? clock.instant() : capture.endedAt(), capture.sizeBytes(),
        capture.minTimestampUs() / 1_000_000.0, capture.maxTimestampUs() / 1_000_000.0,
        capture.files(), event, matchType, matchNumber, teamNumber, capture.endReason(), capture.identity(), List.copyOf(conflicts));
    var next = pending.updateAndGet(old -> new Pending(snapshot,
        old == null ? new CompletableFuture<>() : old.done()));
    completion = next.done();
    dispatch();
  }

  private void dispatch() {
    if (!queued.compareAndSet(false, true)) return;
    submissions.incrementAndGet();
    var selected = new AtomicReference<Pending>();
    store.captureAsync(io -> {
      var next = pending.getAndSet(null); selected.set(next);
      if (next != null) write(io, next.update());
      return null;
    }).whenComplete((ignored, error) -> {
      var next = selected.get();
      if (next == null) next = pending.getAndSet(null);
      if (next != null) {
        if (error == null) next.done().complete(null); else next.done().completeExceptionally(error);
      }
      if (error != null) org.slf4j.LoggerFactory.getLogger(CaptureStore.class).error("Capture manifest update failed", error);
      queued.set(false);
      if (pending.get() != null) dispatch();
    });
  }

  private void write(StoreFiles io, Update update) throws IOException {
    var capture = update.owner();
    Path before = capture.path();
    Path after = writeSnapshot(io, update, before);
    if (!before.equals(after)) { capture.relocate(after); moved.accept(before, after); }
  }

  private Path writeSnapshot(StoreFiles io, Update update, Path before) throws IOException {
    var capture = update.owner();
    var directory = before.getParent();
    var path = directory.resolve("session.json");
    var old = io.read(path, Session.class);
    var files = old.files().stream().filter(f -> !f.provenance().kind().equals("captured"))
        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    for (var closed : update.files()) {
      var file = io.check(directory.resolve(closed.name()));
      var previous = old.files().stream().filter(f -> f.path().equals(closed.name()) && f.sizeBytes() == closed.sizeBytes()).findFirst();
      String hash = previous.isPresent() ? previous.get().sha256() : StoreFiles.hash(file);
      files.add(new LogFile(closed.name(), hash, closed.sizeBytes(), "wpilog",
          new Provenance("captured", null, closed.name(), capture.startedAt().toString(), false), true,
          closed.minUs() / 1_000_000.0, closed.maxUs() / 1_000_000.0,
          capture.startedAt().toString(), closed.endedAt().toString(), "pit_clock", false, null));
    }
    OpenCapture open = update.open() ? new OpenCapture(update.name(),
        new Provenance("captured", null, update.name(), capture.startedAt().toString(), false),
        update.size(), update.min(), update.max()) : null;
    var identityConflicts = java.util.stream.Stream.concat(old.identityConflicts().stream(), update.conflicts().stream()).distinct().toList();
    var session = new Session(old.id(), old.startedAt(), update.endedAt().toString(),
        old.startBasis(), update.event(), update.matchType(), update.matchNumber(), update.teamNumber(),
        List.copyOf(files), open, update.endReason(), update.identity(), identityConflicts);
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
      for (var file : update.files()) moves.add(new Move(directory.resolve(file.name()).toString(),
          StoreFiles.relative(store.root(), target.resolve(file.name())), now.toString()));
      io.write(store.root().resolve("store.json"), new Header(header.formatVersion(), header.createdAt(), header.id(), moves, header.addresses()));
      return destination;
    }
  }
}
