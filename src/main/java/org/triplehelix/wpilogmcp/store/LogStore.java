/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.log.LazyParsedLog;
import org.triplehelix.wpilogmcp.log.LogFileAccess;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.ScopedLogReader;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.revlog.RevLogParser;
import org.triplehelix.wpilogmcp.revlog.dbc.DbcLoader;
import org.triplehelix.wpilogmcp.store.StoreCatalog.StoredFile;
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
    public Request {
      paths = List.copyOf(paths);
    }
  }
  public record Progress(String phase, Path path, int completed, int total) {}
  public record Outcome(Path originalPath, String status, Path path, String reason) {}
  public record SameRobot(String serialNumber, List<Path> directories) {}
  public record Result(List<Outcome> files, List<SameRobot> sameRobots) {}

  private record Candidate(Path path, String hash, Robot robot, Instant start, Instant end) {}
  private record Pair(Candidate wpilog, SyncResult sync) {}
  private record Placement(ImportInspection input, Path destination, Matching matching) {}

  private final Path root;
  private final SecurityValidator security;
  private final ExecutorService queue;
  private final LogManager logManager;
  private final CatalogReader catalogReader;

  @FunctionalInterface
  interface CatalogReader {
    StoreCatalog.Snapshot read(Path root, SecurityValidator security) throws IOException;
  }

  /**
   * One import's catalog. Only this store's queue writes manifests, so successful writes can
   * update these facts without re-reading a season of manifests or walking for strays per file.
   */
  private static final class ImportCatalog {
    Header header;
    final Map<String, Robot> robots = new LinkedHashMap<>();
    final Map<String, StoredFile> files = new LinkedHashMap<>();

    ImportCatalog(StoreCatalog.Snapshot snapshot) {
      header = snapshot.header();
      snapshot.robots().forEach(r -> robots.put(r.robot().id(), r.robot()));
      snapshot.files().forEach(f -> files.put(f.file().sha256(), f));
    }

    void robot(Robot robot, Path old, Path target) {
      robots.remove(old.getFileName().toString());
      robots.put(robot.id(), robot);
      files.replaceAll((hash, file) -> file.path().startsWith(old)
          ? new StoredFile(target.resolve(old.relativize(file.path())),
              target.resolve(old.relativize(file.manifestPath())), robot, file.session(), file.file())
          : file);
    }

    void placed(StoreFiles io, Path manifest, Robot robot, Session session, List<LogFile> records)
        throws IOException {
      for (var file : records) {
        files.put(file.sha256(), new StoredFile(io.resolve(manifest.getParent(), file.path()),
            manifest, robot, session, file));
      }
    }
  }

  LogStore(Path root, SecurityValidator security) {
    this(root, security, LogManager.getInstance(), StoreCatalog::read);
  }

  LogStore(Path root, SecurityValidator security, LogManager logManager, CatalogReader catalogReader) {
    this.root = root;
    this.security = security;
    this.logManager = logManager;
    this.catalogReader = catalogReader;
    queue = Executors.newSingleThreadExecutor(task -> {
      var thread = new Thread(task, "store-import");
      thread.setDaemon(true);
      return thread;
    });
  }

  public Path root() {
    return root;
  }

  /** A second caller queues behind the first, including its inspection and identity changes. */
  public CompletableFuture<Result> importPaths(Request request, Consumer<Progress> progress) {
    Objects.requireNonNull(request);
    Objects.requireNonNull(progress);
    var result = new CompletableFuture<Result>();
    queue.execute(() -> {
      try {
        result.complete(run(request, progress));
      } catch (Exception e) {
        result.completeExceptionally(e);
      } catch (OutOfMemoryError e) {
        result.completeExceptionally(new IOException("Not enough heap to inspect this import", e));
      }
    });
    return result;
  }

  @Override
  public void close() {
    queue.shutdown();
  }

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
    var catalog = new ImportCatalog(catalogReader.read(root, security));
    var known = new HashMap<String, Path>();
    catalog.files.values().forEach(f -> known.put(f.file().sha256(), f.path()));
    var inspected = new ArrayList<ImportInspection>();
    var batchHashes = new HashSet<String>();
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
      robots.put(input.path(), robotFor(io, catalog, input, request.statedRobot(), sameRobots));
    }
    // A serial promotion updates the catalog; earlier files in this batch use that identity too.
    var currentRobots = catalog.robots.values();
    robots.replaceAll((path, robot) -> robot == null ? null : currentRobots.stream()
        .filter(r -> r.id().equals(robot.id()) || robot.serialNumber() == null
            && Objects.equals(r.name(), robot.name()))
        .findFirst().orElse(robot));
    var candidates = new ArrayList<Candidate>();
    for (var input : inspected) {
      if (input.kind().equals("wpilog")) {
        candidates.add(new Candidate(input.path(), input.hash(), robots.get(input.path()), input.start(), input.end()));
      }
    }
    catalog.files.values().stream().filter(f -> f.file().kind().equals("wpilog") && f.session() != null)
        .forEach(f -> candidates.add(new Candidate(f.path(), f.file().sha256(), f.robot(),
            Instant.parse(f.session().startedAt()), Instant.parse(f.session().endedAt()))));
    var pairs = new HashMap<Path, Pair>();
    for (var input : inspected) {
      if (!input.kind().equals("revlog")) continue;
      notify(progress, new Progress("pairing", input.path(), 0, inspected.size()));
      var pair = pair(input, candidates, parser, request.statedRobot(), progress);
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
      place(io, catalog, group, input, robots.get(input.path()), pairs, request.move(), parser, outcomes, progress);
      pending.removeAll(group);
      notify(progress, new Progress("placing", input.path(), inspected.size() - pending.size(), inspected.size()));
    }
    for (var input : pending) {
      var pair = pairs.get(input.path());
      if (pair == null) {
        place(io, catalog, List.of(input), input, null, pairs, request.move(), parser, outcomes, progress);
      } else {
        var stored = catalog.files.values().stream()
            .filter(f -> f.file().sha256().equals(pair.wpilog().hash()) && f.session() != null)
            .findFirst().orElseThrow();
        placeGroup(io, catalog, List.of(input), stored.manifestPath(), stored.robot(), stored.session(), pairs,
            request.move(), parser, outcomes, progress);
      }
    }
    var finalFiles = catalog.files.values();
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
    var found = new TreeSet<Path>();
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

  private Robot robotFor(StoreFiles io, ImportCatalog catalog, ImportInspection input, String stated,
      List<SameRobot> sameRobots) throws IOException {
    var metadata = input.metadata();
    String serial = metadata.serialNumber();
    if (serial == null && stated == null) return null;
    if (serial != null) StoreFiles.component(serial);
    Robot previous = stated == null ? null : catalog.robots.values().stream()
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
        catalog.robot(alias, old, old);
        sameRobots.add(new SameRobot(serial, List.of(old, target)));
        return alias;
      }
      io.check(old);
      try (var lease = releaseForMove(List.of(old))) {
        Files.move(old, target);
      }
      io.write(target.resolve("robot.json"), promoted);
      rewriteMoves(io, catalog, old, target);
      catalog.robot(promoted, old, target);
      return promoted;
    }
    String id = serial != null ? serial : stated;
    var existing = catalog.robots.values().stream()
        .filter(r -> r.id().equals(id)).findFirst().orElse(null);
    var robot = new Robot(id, serial, existing != null ? existing.name() : stated,
        metadata.comments() != null ? metadata.comments() : existing != null ? existing.comments() : null,
        serial == null ? "stated" : "logged");
    io.write(root.resolve("robots").resolve(id).resolve("robot.json"), robot);
    catalog.robot(robot, root.resolve("robots").resolve(id), root.resolve("robots").resolve(id));
    return robot;
  }

  private Pair pair(ImportInspection input, List<Candidate> candidates, RevLogParser parser,
      String stated, Consumer<Progress> progress) throws IOException {
    var rev = parser.parse(input.path());
    var matches = new LinkedHashMap<String, Pair>();
    var nominated = candidates.stream().filter(c -> stated == null || c.robot() != null
        && (stated.equals(c.robot().id()) || stated.equals(c.robot().name())))
        .filter(c -> nearRevClock(input, c)).toList();
    int completed = 0;
    for (var candidate : nominated) {
      notify(progress, new Progress("correlating", candidate.path(), completed++, nominated.size()));
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

  /**
   * Clocks only nominate. Two hours on either side allow clock-setting delays; filename-only
   * estimates also allow any real UTC offset, since a REV filename carries no zone of its own.
   * Unknown clocks keep every candidate of the stated robot, and correlation still decides.
   */
  private static boolean nearRevClock(ImportInspection rev, Candidate candidate) {
    if (rev.start() == null || candidate.start() == null) return true;
    var slack = Duration.ofHours("filename".equals(rev.startBasis()) ? 16 : 2);
    return !candidate.end().isBefore(rev.start().minus(slack))
        && !candidate.start().isAfter(rev.end().plus(slack));
  }

  private void place(StoreFiles io, ImportCatalog catalog, List<ImportInspection> inputs, ImportInspection primary,
      Robot robot, Map<Path, Pair> pairs, boolean move, RevLogParser parser,
      List<Outcome> outcomes, Consumer<Progress> progress) throws IOException {
    if (robot == null || primary.start() == null) {
      for (var input : inputs) {
        var directory = root.resolve("unassigned").resolve(input.hash().substring(0, 16));
        placeGroup(io, catalog, List.of(input), directory.resolve("import.json"), null, null, pairs,
            move, parser, outcomes, progress);
      }
      return;
    }
    var overlap = catalog.files.values().stream()
        .filter(f -> f.robot() != null && f.robot().id().equals(robot.id()) && f.session() != null)
        .filter(f -> !primary.end().isBefore(Instant.parse(f.session().startedAt()))
            && !primary.start().isAfter(Instant.parse(f.session().endedAt())))
        .findFirst();
    if (overlap.isPresent()) {
      var found = overlap.get();
      placeGroup(io, catalog, inputs, found.manifestPath(), robot, found.session(), pairs, move, parser, outcomes, progress);
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
    placeGroup(io, catalog, inputs, directory.resolve("session.json"), robot, session, pairs, move, parser, outcomes, progress);
  }

  private void placeGroup(StoreFiles io, ImportCatalog catalog, List<ImportInspection> inputs, Path manifest,
      Robot robot, Session session, Map<Path, Pair> pairs, boolean move, RevLogParser parser,
      List<Outcome> outcomes, Consumer<Progress> progress) throws IOException {
    var known = catalog.files.values();
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
    try (var lease = move ? releaseForMove(placements.stream().map(p -> p.input().path()).toList())
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
          // A REV wall clock nominates candidates only; the correlated wpilog owns session time.
          if (!input.kind().equals("wpilog")) continue;
          if (input.start() != null && input.start().isBefore(start)) {
            start = input.start();
            basis = input.startBasis();
          }
          if (input.end() != null && input.end().isAfter(end)) end = input.end();
        }
        session = new Session(session.id(), start.toString(), end.toString(), basis,
            session.event(), session.matchType(), session.matchNumber(), session.teamNumber(), List.copyOf(records));
        io.write(manifest, session);
      }
      catalog.placed(io, manifest, robot, session, records);
    } catch (IOException | RuntimeException e) {
      // Restore moves where possible; never remove an unverified copy or overwrite a new file.
      if (move) {
        for (var placement : transferred) {
          try {
            Files.move(placement.destination(), placement.input().path());
          } catch (IOException restore) {
            e.addSuppressed(restore);
          }
        }
      }
      for (var placement : placements) outcomes.add(new Outcome(placement.input().path(), "refused",
          Files.exists(placement.destination()) ? placement.destination() : null, e.getMessage()));
      return;
    }
    if (move) {
      var header = catalog.header;
      var moves = new ArrayList<>(header.moves());
      for (var placement : placements) moves.add(new Move(placement.input().path().toString(),
          StoreFiles.relative(root, placement.destination()), Instant.now().toString()));
      var updated = new Header(header.formatVersion(), header.createdAt(), header.id(), moves);
      io.write(root.resolve("store.json"), updated);
      catalog.header = updated;
    }
    for (var placement : placements) outcomes.add(new Outcome(placement.input().path(),
        session == null ? "unassigned" : "imported", placement.destination(), session == null
            ? "No robot assignment or unique correlated wpilog; awaiting assignment" : null));
  }

  private void rewriteMoves(StoreFiles io, ImportCatalog catalog, Path old, Path target)
      throws IOException {
    var header = catalog.header;
    var moves = new ArrayList<Move>();
    for (var move : header.moves()) {
      var path = io.resolve(root, move.movedTo());
      var destination = path.startsWith(old) ? target.resolve(old.relativize(path)) : path;
      moves.add(new Move(move.originalPath(), StoreFiles.relative(root, destination), move.movedAt()));
    }
    for (var file : catalog.files.values()) {
      if (file.path().startsWith(old)) moves.add(new Move(file.path().toString(),
          StoreFiles.relative(root, target.resolve(old.relativize(file.path()))), Instant.now().toString()));
    }
    var updated = new Header(header.formatVersion(), header.createdAt(), header.id(), moves);
    io.write(root.resolve("store.json"), updated);
    catalog.header = updated;
  }

  /** Keep new requests out from eviction through rename, while existing calls get time to finish. */
  private LogFileAccess.Lease releaseForMove(List<Path> paths) throws IOException {
    var reservation = LogFileAccess.reserveMove(paths);
    try {
      for (var path : paths) {
        var result = logManager.release(path);
        if (!result.released()) throw new IOException(result.reason());
      }
      return reservation;
    } catch (IOException | RuntimeException e) {
      reservation.close();
      throw e;
    }
  }

  private static void notify(Consumer<Progress> callback, Progress progress) {
    try {
      callback.accept(progress);
    } catch (RuntimeException e) {
      LoggerFactory.getLogger(LogStore.class).warn("Import progress listener failed: {}", e.getMessage());
    }
  }
}
