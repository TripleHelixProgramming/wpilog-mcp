/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.log.LazyParsedLog;
import org.triplehelix.wpilogmcp.log.LogFileAccess;
import org.triplehelix.wpilogmcp.log.ScopedLogReader;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.revlog.RevLogParser;
import org.triplehelix.wpilogmcp.revlog.dbc.DbcLoader;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;
import org.triplehelix.wpilogmcp.sync.LogSynchronizer;
import org.triplehelix.wpilogmcp.sync.SyncMethod;
import org.triplehelix.wpilogmcp.sync.SyncResult;

/**
 * One store's import queue, independent of HTTP and the extension. Inspection and correlation
 * finish before placement, and a manifest is committed only after every file in a pair loads
 * at its destination. Failed copies remain visible as strays; originals are never overwritten.
 */
public final class LogStore implements AutoCloseable {
  public record Request(List<Path> paths, boolean move, String statedRobot) {
    public Request { paths = List.copyOf(paths); }
  }
  public record Progress(String phase, Path path, int completed, int total) {}
  public record Outcome(Path originalPath, String status, Path path, String reason) {}
  public record SameRobot(String serialNumber, List<Path> directories) {}
  public record Result(List<Outcome> files, List<SameRobot> sameRobots) {}

  private record Candidate(Path path, String hash, Robot robot) {}
  private record Pair(Candidate wpilog, SyncResult sync) {}
  private record Placement(ImportInspection input, Path destination, Matching matching) {}

  private final Path root;
  private final SecurityValidator security;
  private final ExecutorService queue;

  LogStore(Path root, SecurityValidator security) {
    this.root = root;
    this.security = security;
    queue = Executors.newSingleThreadExecutor(task -> {
      var thread = new Thread(task, "store-import");
      thread.setDaemon(true);
      return thread;
    });
  }

  public Path root() { return root; }

  /** A second caller queues behind the first, including its inspection and identity changes. */
  public CompletableFuture<Result> importPaths(Request request, Consumer<Progress> progress) {
    Objects.requireNonNull(request);
    Objects.requireNonNull(progress);
    var result = new CompletableFuture<Result>();
    queue.execute(() -> {
      try { result.complete(run(request, progress)); }
      catch (Exception e) { result.completeExceptionally(e); }
      catch (OutOfMemoryError e) {
        result.completeExceptionally(new IOException("Not enough heap to inspect this import", e));
      }
    });
    return result;
  }

  @Override
  public void close() { queue.shutdown(); }

  private Result run(Request request, Consumer<Progress> progress) throws IOException {
    if (request.statedRobot() != null) StoreFiles.component(request.statedRobot());
    var io = new StoreFiles(root, security);
    var outcomes = new ArrayList<Outcome>();
    var sameRobots = new ArrayList<SameRobot>();
    var sources = expand(request.paths(), outcomes);
    if (!StoreCatalog.isStore(root)) {
      io.write(root.resolve("store.json"), new Header(StoreManifest.FORMAT_VERSION,
          Instant.now().toString(), UUID.randomUUID().toString(), List.of()));
    }
    var catalog = StoreCatalog.read(root, security);
    var known = new HashMap<String, Path>();
    catalog.files().forEach(f -> known.put(f.file().sha256(), f.path()));
    var inspected = new ArrayList<ImportInspection>();
    var batchHashes = new java.util.HashSet<String>();
    var repeats = new LinkedHashMap<Path, String>();
    var parser = new RevLogParser(new DbcLoader().load(null));
    int completed = 0;
    for (var source : sources) {
      notify(progress, new Progress("inspecting", source, completed++, sources.size()));
      try {
        security.validate(source);
        StoreFiles.component(source.getFileName().toString());
        // Hash first: a duplicate requires neither decoding nor a robot assignment.
        var hash = StoreFiles.hash(source);
        if (known.containsKey(hash)) {
          if (!hash.equals(StoreFiles.hash(known.get(hash)))) {
            throw new IOException("Stored content no longer matches its manifest: " + known.get(hash));
          }
          outcomes.add(new Outcome(source, "present", known.get(hash), "Already held by SHA-256"));
          continue;
        }
        if (batchHashes.contains(hash)) {
          repeats.put(source, hash);
          continue;
        }
        inspected.add(ImportInspection.read(source, parser));
        batchHashes.add(hash);
      } catch (IOException | IllegalArgumentException e) {
        outcomes.add(new Outcome(source, "refused", null, e.getMessage()));
      }
    }

    var robots = new HashMap<Path, Robot>();
    for (var input : inspected) {
      if (!input.kind().equals("wpilog")) continue;
      robots.put(input.path(), robotFor(io, input, request.statedRobot(), sameRobots));
    }
    // A serial promotion may have renamed a stored candidate's parent. Read those paths again.
    catalog = StoreCatalog.read(root, security);
    var currentRobots = catalog.robots().stream().map(StoreCatalog.RobotDirectory::robot).toList();
    robots.replaceAll((path, robot) -> robot == null ? null : currentRobots.stream()
        .filter(r -> r.id().equals(robot.id()) || robot.serialNumber() == null
            && Objects.equals(r.name(), robot.name()))
        .findFirst().orElse(robot));
    var candidates = new ArrayList<Candidate>();
    for (var input : inspected) {
      if (input.kind().equals("wpilog")) {
        candidates.add(new Candidate(input.path(), input.hash(), robots.get(input.path())));
      }
    }
    catalog.files().stream().filter(f -> f.file().kind().equals("wpilog") && f.session() != null)
        .forEach(f -> candidates.add(new Candidate(f.path(), f.file().sha256(), f.robot())));
    var pairs = new HashMap<Path, Pair>();
    for (var input : inspected) {
      if (!input.kind().equals("revlog")) continue;
      notify(progress, new Progress("pairing", input.path(), 0, inspected.size()));
      var pair = pair(input, candidates, parser, request.statedRobot());
      if (pair != null) pairs.put(input.path(), pair);
    }

    // Place each wpilog and its proven companions in one manifest commit.
    var pending = new ArrayList<>(inspected);
    for (var input : inspected) {
      if (!input.kind().equals("wpilog")) continue;
      var group = new ArrayList<ImportInspection>();
      group.add(input);
      pending.stream().filter(i -> pairs.containsKey(i.path())
          && pairs.get(i.path()).wpilog().path().equals(input.path())).forEach(group::add);
      place(io, group, input, robots.get(input.path()), pairs, request.move(), parser, outcomes, progress);
      pending.removeAll(group);
      notify(progress, new Progress("placing", input.path(), inspected.size() - pending.size(), inspected.size()));
    }
    for (var input : pending) {
      var pair = pairs.get(input.path());
      if (pair == null) {
        place(io, List.of(input), input, null, pairs, request.move(), parser, outcomes, progress);
      } else {
        var stored = StoreCatalog.read(root, security).files().stream()
            .filter(f -> f.file().sha256().equals(pair.wpilog().hash()) && f.session() != null)
            .findFirst().orElseThrow();
        placeGroup(io, List.of(input), stored.manifestPath(), stored.session(), pairs,
            request.move(), parser, outcomes, progress);
      }
    }
    var finalFiles = StoreCatalog.read(root, security).files();
    for (var repeat : repeats.entrySet()) {
      var present = finalFiles.stream().filter(f -> f.file().sha256().equals(repeat.getValue())).findFirst();
      outcomes.add(new Outcome(repeat.getKey(), present.isPresent() ? "present" : "refused",
          present.map(StoreCatalog.StoredFile::path).orElse(null),
          present.isPresent() ? "Already held by SHA-256" : "The first copy in this batch could not be imported"));
    }
    notify(progress, new Progress("complete", root, sources.size(), sources.size()));
    return new Result(List.copyOf(outcomes), List.copyOf(sameRobots));
  }

  private List<Path> expand(List<Path> paths, List<Outcome> outcomes) {
    var found = new java.util.TreeSet<Path>();
    for (var path : paths) {
      try {
        security.validate(path);
        var real = path.toRealPath();
        if (Files.isDirectory(real)) {
          try (var walk = Files.walk(real)) {
            for (var file : walk.filter(Files::isRegularFile).toList()) {
              security.validate(file);
              found.add(file.toRealPath());
            }
          }
        } else {
          found.add(real);
        }
      } catch (IOException e) {
        outcomes.add(new Outcome(path, "refused", null, e.getMessage()));
      }
    }
    return List.copyOf(found);
  }

  private Robot robotFor(StoreFiles io, ImportInspection input, String stated,
      List<SameRobot> sameRobots) throws IOException {
    var metadata = input.metadata();
    String serial = metadata.serialNumber();
    if (serial == null && stated == null) return null;
    if (serial != null) StoreFiles.component(serial);
    var catalog = StoreCatalog.read(root, security);
    Robot previous = stated == null ? null : catalog.robots().stream()
        .map(StoreCatalog.RobotDirectory::robot)
        .filter(r -> r.id().equals(stated) || stated.equals(r.name())).findFirst().orElse(null);
    if (previous != null && (serial == null || serial.equals(previous.serialNumber()))) return previous;
    if (serial != null && previous != null && previous.serialNumber() == null) {
      var old = root.resolve("robots").resolve(previous.id());
      var target = root.resolve("robots").resolve(serial);
      var promoted = new Robot(serial, serial, previous.name(), metadata.comments(), "logged");
      if (Files.exists(io.check(target))) {
        // Persist the shared identity, while keeping both histories physically separate.
        var alias = new Robot(previous.id(), serial, previous.name(), metadata.comments(), "logged");
        io.write(old.resolve("robot.json"), alias);
        sameRobots.add(new SameRobot(serial, List.of(old, target)));
        return alias;
      }
      io.check(old);
      try (var lease = LogFileAccess.move(List.of(old))) { Files.move(old, target); }
      io.write(target.resolve("robot.json"), promoted);
      rewriteMoves(io, old, target, catalog.files());
      return promoted;
    }
    String id = serial != null ? serial : stated;
    var existing = catalog.robots().stream().map(StoreCatalog.RobotDirectory::robot)
        .filter(r -> r.id().equals(id)).findFirst().orElse(null);
    var robot = new Robot(id, serial, existing != null ? existing.name() : stated,
        metadata.comments() != null ? metadata.comments() : existing != null ? existing.comments() : null,
        serial == null ? "stated" : "logged");
    io.write(root.resolve("robots").resolve(id).resolve("robot.json"), robot);
    return robot;
  }

  private Pair pair(ImportInspection input, List<Candidate> candidates, RevLogParser parser,
      String stated) throws IOException {
    var rev = parser.parse(input.path());
    var matches = new LinkedHashMap<String, Pair>();
    for (var candidate : candidates) {
      if (stated != null && candidate.robot() != null
          && !stated.equals(candidate.robot().id()) && !stated.equals(candidate.robot().name())) continue;
      security.validate(candidate.path());
      try (var reader = new ScopedLogReader(candidate.path());
           var log = new LazyParsedLog(candidate.path().toString(), reader.reader(), 32L * 1024 * 1024)) {
        var sync = new LogSynchronizer().synchronize(log, rev);
        // A time-only estimate aligns clocks but cannot establish which robot wrote the file.
        if (sync.method() == SyncMethod.CROSS_CORRELATION && sync.strongPairCount() > 0) {
          matches.putIfAbsent(candidate.hash(), new Pair(candidate, sync));
        }
      }
    }
    return matches.size() == 1 ? matches.values().iterator().next() : null;
  }

  private void place(StoreFiles io, List<ImportInspection> inputs, ImportInspection primary,
      Robot robot, Map<Path, Pair> pairs, boolean move, RevLogParser parser,
      List<Outcome> outcomes, Consumer<Progress> progress) throws IOException {
    if (robot == null || primary.start() == null) {
      for (var input : inputs) {
        var directory = root.resolve("unassigned").resolve(input.hash().substring(0, 16));
        placeGroup(io, List.of(input), directory.resolve("import.json"), null, pairs,
            move, parser, outcomes, progress);
      }
      return;
    }
    var catalog = StoreCatalog.read(root, security);
    var overlap = catalog.files().stream()
        .filter(f -> f.robot() != null && f.robot().id().equals(robot.id()) && f.session() != null)
        .filter(f -> !primary.end().isBefore(Instant.parse(f.session().startedAt()))
            && !primary.start().isAfter(Instant.parse(f.session().endedAt())))
        .findFirst();
    if (overlap.isPresent()) {
      var found = overlap.get();
      placeGroup(io, inputs, found.manifestPath(), found.session(), pairs, move, parser, outcomes, progress);
      return;
    }
    var metadata = primary.metadata();
    var day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(primary.start());
    var name = DateTimeFormatter.ofPattern("HHmmss'Z'").withZone(ZoneOffset.UTC).format(primary.start());
    if (metadata.event() != null && metadata.matchNumber() != null) {
      String code = switch (metadata.matchType()) {
        case "Practice" -> "P";
        case "Qualification" -> "Q";
        default -> "E";
      };
      name += "_" + metadata.event().replaceAll("[^A-Za-z0-9_-]", "_") + "_" + code + metadata.matchNumber();
    }
    var parent = root.resolve("robots").resolve(robot.id()).resolve("sessions").resolve(day);
    var directory = parent.resolve(name);
    int suffix = 2;
    while (Files.exists(io.check(directory))) directory = parent.resolve(name + "_" + suffix++);
    var session = new Session(UUID.randomUUID().toString(), primary.start().toString(),
        primary.end().toString(), primary.startBasis(), metadata.event(), metadata.matchType(),
        metadata.matchNumber(), metadata.teamNumber(), List.of());
    placeGroup(io, inputs, directory.resolve("session.json"), session, pairs, move, parser, outcomes, progress);
  }

  private void placeGroup(StoreFiles io, List<ImportInspection> inputs, Path manifest,
      Session session, Map<Path, Pair> pairs, boolean move, RevLogParser parser,
      List<Outcome> outcomes, Consumer<Progress> progress) throws IOException {
    var known = StoreCatalog.read(root, security).files();
    var placements = new ArrayList<Placement>();
    for (var input : inputs) {
      var duplicate = known.stream().filter(f -> f.file().sha256().equals(input.hash())).findFirst();
      if (duplicate.isPresent()) {
        outcomes.add(new Outcome(input.path(), "present", duplicate.get().path(), "Already held by SHA-256"));
        continue;
      }
      var parent = session == null ? manifest.getParent() : manifest.getParent().resolve("robot");
      var destination = parent.resolve(input.path().getFileName());
      if (Files.exists(io.check(destination))) {
        destination = parent.resolve(input.hash()).resolve(input.path().getFileName());
      }
      var pair = pairs.get(input.path());
      var matching = pair == null ? null : new Matching("by_correlation", pair.wpilog().hash(),
          pair.sync().offsetMicros(), pair.sync().confidence(), pair.sync().driftRateNanosPerSec(),
          pair.sync().referenceTimeSec(), "data_alone");
      placements.add(new Placement(input, destination, matching));
    }
    if (placements.isEmpty()) return;
    var transferred = new ArrayList<Placement>();
    try (var lease = move ? LogFileAccess.move(placements.stream().map(p -> p.input().path()).toList())
        : (LogFileAccess.Lease) () -> {}) {
      for (var placement : placements) {
        var input = placement.input();
        security.validate(input.path());
        if (!input.snapshot().sameAs(FileSnapshot.of(input.path()))
            || !input.hash().equals(StoreFiles.hash(input.path()))) {
          throw new IOException("File changed before placement: " + input.path());
        }
        io.check(placement.destination());
        Files.createDirectories(placement.destination().getParent());
        io.check(placement.destination());
        if (move) Files.move(input.path(), placement.destination());
        else Files.copy(input.path(), placement.destination(), StandardCopyOption.COPY_ATTRIBUTES);
        transferred.add(placement);
        notify(progress, new Progress("verifying", placement.destination(), transferred.size() - 1, placements.size()));
        var verified = ImportInspection.read(placement.destination(), parser);
        if (!verified.hash().equals(input.hash())) throw new IOException("Copy hash differs: " + input.path());
      }
      var records = new ArrayList<LogFile>(session == null ? List.of() : session.files());
      String now = Instant.now().toString();
      for (var placement : placements) {
        var input = placement.input();
        records.add(new LogFile(StoreFiles.relative(manifest.getParent(), placement.destination()),
            input.hash(), input.size(), input.kind(), new Provenance("imported", input.path().toString(),
            input.path().getFileName().toString(), now, move), true, input.min(), input.max(),
            input.start() == null ? null : input.start().toString(),
            input.end() == null ? null : input.end().toString(), input.startBasis(), input.truncated(),
            placement.matching()));
      }
      if (session == null) io.write(manifest, records.get(0));
      else {
        var start = Instant.parse(session.startedAt());
        var end = Instant.parse(session.endedAt());
        String basis = session.startBasis();
        for (var input : inputs) {
          if (input.start() != null && input.start().isBefore(start)) {
            start = input.start();
            basis = input.startBasis();
          }
          if (input.end() != null && input.end().isAfter(end)) end = input.end();
        }
        io.write(manifest, new Session(session.id(), start.toString(), end.toString(), basis,
            session.event(), session.matchType(), session.matchNumber(), session.teamNumber(), List.copyOf(records)));
      }
    } catch (IOException | RuntimeException e) {
      // Restore moves where possible; never remove an unverified copy or overwrite a new file.
      if (move) {
        for (var placement : transferred) {
          try { Files.move(placement.destination(), placement.input().path()); }
          catch (IOException restore) { e.addSuppressed(restore); }
        }
      }
      for (var placement : placements) outcomes.add(new Outcome(placement.input().path(), "refused",
          Files.exists(placement.destination()) ? placement.destination() : null, e.getMessage()));
      return;
    }
    if (move) {
      var header = io.read(root.resolve("store.json"), Header.class);
      var moves = new ArrayList<>(header.moves());
      for (var placement : placements) moves.add(new Move(placement.input().path().toString(),
          StoreFiles.relative(root, placement.destination()), Instant.now().toString()));
      io.write(root.resolve("store.json"), new Header(header.formatVersion(), header.createdAt(), header.id(), moves));
    }
    for (var placement : placements) outcomes.add(new Outcome(placement.input().path(),
        session == null ? "unassigned" : "imported", placement.destination(), session == null
            ? "No robot assignment or unique correlated wpilog; awaiting assignment" : null));
  }

  private void rewriteMoves(StoreFiles io, Path old, Path target, List<StoreCatalog.StoredFile> files)
      throws IOException {
    var header = io.read(root.resolve("store.json"), Header.class);
    var moves = new ArrayList<Move>();
    for (var move : header.moves()) {
      var path = io.resolve(root, move.movedTo());
      var destination = path.startsWith(old) ? target.resolve(old.relativize(path)) : path;
      moves.add(new Move(move.originalPath(), StoreFiles.relative(root, destination), move.movedAt()));
    }
    for (var file : files) {
      if (file.path().startsWith(old)) moves.add(new Move(file.path().toString(),
          StoreFiles.relative(root, target.resolve(old.relativize(file.path()))), Instant.now().toString()));
    }
    io.write(root.resolve("store.json"), new Header(header.formatVersion(), header.createdAt(), header.id(), moves));
  }

  private static void notify(Consumer<Progress> callback, Progress progress) {
    try { callback.accept(progress); }
    catch (RuntimeException e) {
      LoggerFactory.getLogger(LogStore.class).warn("Import progress listener failed: {}", e.getMessage());
    }
  }
}
