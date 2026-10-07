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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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
      if (statedRobot != null) StoreFiles.robotName(statedRobot);
    }
  }
  public record Progress(String phase, Path path, int completed, int total) {}
  public record Outcome(Path originalPath, String status, Path path, String reason) {}
  public record SameRobot(String serialNumber, List<Path> directories) {}
  public record Result(List<Outcome> files, List<SameRobot> sameRobots) {}

  private record Candidate(Path path, String hash, Robot robot, Instant start, Instant end) {}
  private record Pair(Candidate wpilog, SyncResult sync) {}
  private record Placement(ImportInspection input, Path destination, Matching matching) {}

  private static final Set<String> CONTROL_NAMES = Set.of(
      "import.json", "session.json", "robot.json", "store.json", "batch.json");

  private final Path root;
  private final SecurityValidator security;
  private final ExecutorService queue;
  private final LogManager logManager;
  private final CatalogReader catalogReader;
  private final StoreInbox inbox;
  private final AtomicInteger pending = new AtomicInteger();
  private final java.util.concurrent.atomic.AtomicBoolean syncing = new java.util.concurrent.atomic.AtomicBoolean();
  /** HTTP-owned staging paths are unique; source provenance lives only for their import job. */
  private final java.util.concurrent.ConcurrentHashMap<Path, Provenance> uploadSources = new java.util.concurrent.ConcurrentHashMap<>();

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
    final Map<String, StoredFile> assigning = new LinkedHashMap<>();
    final Map<Path, Session> sessions = new LinkedHashMap<>();

    ImportCatalog(StoreCatalog.Snapshot snapshot) {
      header = snapshot.header();
      snapshot.robots().forEach(r -> robots.put(r.robot().id(), r.robot()));
      snapshot.files().forEach(f -> files.put(f.file().sha256(), f));
      snapshot.sessions().forEach(s -> sessions.put(s.path().resolve("session.json"), s.session()));
    }

    void robot(Robot robot, Path old, Path target) {
      robots.remove(old.getFileName().toString());
      robots.put(robot.id(), robot);
      var movedSessions = new LinkedHashMap<Path, Session>();
      sessions.forEach((path, session) -> movedSessions.put(path.startsWith(old) ? target.resolve(old.relativize(path)) : path, session));
      sessions.clear(); sessions.putAll(movedSessions);
      files.replaceAll((hash, file) -> file.path().startsWith(old)
          ? new StoredFile(target.resolve(old.relativize(file.path())),
              target.resolve(old.relativize(file.manifestPath())), robot, file.session(), file.file())
          : file);
    }

    void placed(StoreFiles io, Path manifest, Robot robot, Session session, List<LogFile> records)
        throws IOException {
      if (session != null) sessions.put(manifest, session);
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
    this.inbox = new StoreInbox(this, security);
    queue = Executors.newSingleThreadExecutor(task -> {
      var thread = new Thread(task, "store-import");
      thread.setDaemon(true);
      return thread;
    });
  }

  public Path root() {
    return root;
  }

  /** The capture service observes durable facts on the store queue; tools never join that queue. */
  public interface Observer {
    void manifest(Path path, Object value);
    void inventory(StoreCatalog.Snapshot snapshot);
  }
  private final java.util.Set<Observer> observers = java.util.concurrent.ConcurrentHashMap.newKeySet();
  public AutoCloseable observe(Observer observer) { observers.add(observer); return () -> observers.remove(observer); }
  private void published(Path path, Object value) {
    for (var observer : observers) {
      try { observer.manifest(path, value); }
      catch (RuntimeException e) { org.slf4j.LoggerFactory.getLogger(LogStore.class).warn("Session status update failed", e); }
    }
  }
  private void publishInventory() throws IOException {
    if (observers.isEmpty()) return;
    var snapshot = StoreCatalog.readManaged(root, security);
    observers.forEach(observer -> observer.inventory(snapshot));
  }
  public CompletableFuture<Void> refreshStatus() {
    return captureAsync(io -> { publishInventory(); return null; });
  }

  /** Atomic manifest replacement permits nonblocking status reads while a sync is queued. */
  public StoreManifest.MirrorOrigin mirrorOrigin() throws IOException {
    if (!StoreCatalog.isStore(root)) return null;
    return new StoreFiles(root, security, this::published).read(root.resolve("store.json"), Header.class).origin();
  }

  @FunctionalInterface interface CaptureOperation<T> { T run(StoreFiles files) throws IOException; }

  /** Capture manifest changes share the import queue and the cross-process store lock. */
  <T> T capture(CaptureOperation<T> operation) throws IOException {
    try { return captureAsync(operation).get(); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted capture placement", e); }
    catch (java.util.concurrent.ExecutionException e) { throw new IOException("Capture placement failed: " + e.getCause().getMessage(), e.getCause()); }
  }

  <T> CompletableFuture<T> captureAsync(CaptureOperation<T> operation) {
    return mutate(operation, false);
  }

  private <T> CompletableFuture<T> mutate(CaptureOperation<T> operation, boolean allowMirror) {
    var result = new CompletableFuture<T>();
    queue.execute(() -> {
      try (var lock = StoreLock.acquire(root, security)) {
        var io = new StoreFiles(root, security, this::published);
        if (!StoreCatalog.isStore(root)) io.write(root.resolve("store.json"),
            new Header(StoreManifest.FORMAT_VERSION, Instant.now().toString(), UUID.randomUUID().toString(), List.of()));
        var header = io.read(root.resolve("store.json"), Header.class);
        if (header.formatVersion() != StoreManifest.FORMAT_VERSION) throw new IOException("Unsupported store format version " + header.formatVersion());
        if (header.mirror() && !allowMirror) throw new IOException("A mirror is written only by its mirror synchronization");
        var value = operation.run(io);
        if (allowMirror) publishInventory();
        result.complete(value);
      } catch (Throwable e) { result.completeExceptionally(e); }
    });
    return result;
  }

  public CaptureStore captures(java.time.Clock clock) { return new CaptureStore(this, logManager, clock); }

  public PullStore pulls(org.triplehelix.wpilogmcp.capture.context.DeviceIdentity identity, java.time.Clock clock) {
    return new PullStore(this, logManager, security, identity, clock);
  }

  /** Pin lookup is queued like every identity read/modify/write; callers are background pullers. */
  public CompletableFuture<String> hostKey(String address) {
    return captureAsync(io -> {
      String serial = io.read(root.resolve("store.json"), Header.class).addresses().get(address);
      if (serial == null) return null;
      var robot = io.read(root.resolve("robots").resolve(StoreFiles.component(serial)).resolve("robot.json"), Robot.class);
      return robot.contacts().stream().filter(c -> c.address().equals(address)).reduce((a, b) -> b)
          .map(Contact::hostKeyFingerprint).orElse(null);
    });
  }

  public CompletableFuture<Robot> identify(org.triplehelix.wpilogmcp.capture.context.DeviceIdentity identity,
      java.time.Clock clock) {
    return captureAsync(io -> {
      var robot = RobotIdentityStore.record(io, root, identity, clock.instant());
      RobotIdentityStore.promote(io, root, logManager, null, identity, clock.instant());
      return robot;
    });
  }

  public StoreInbox inbox() {
    return inbox;
  }

  public boolean importing() {
    return pending.get() > 0;
  }

  /** Peer synchronization is a store job; a second sync is refused rather than queued indefinitely. */
  public CompletableFuture<StoreSync.Result> sync(String url, long rateBytes, Consumer<StoreSync.Progress> progress) {
    return sync(url, rateBytes, progress, java.time.Clock.systemUTC(), StoreSync::http);
  }

  CompletableFuture<StoreSync.Result> sync(String url, long rateBytes, Consumer<StoreSync.Progress> progress,
      java.time.Clock clock, StoreSync.Source source) {
    if (rateBytes < 0) throw new IllegalArgumentException("rate_bytes must be nonnegative (0 means unlimited)");
    if (!syncing.compareAndSet(false, true)) return CompletableFuture.failedFuture(new IOException("A sync is already running for this store"));
    pending.incrementAndGet();
    try {
      return mutate(io -> new StoreSync(root, io, security, logManager, clock, source, progress).run(url, rateBytes), true)
          .whenComplete((result, error) -> { syncing.set(false); pending.decrementAndGet(); });
    } catch (RuntimeException e) { syncing.set(false); pending.decrementAndGet(); throw e; }
  }

  public CompletableFuture<MirrorSync.Result> mirror(org.triplehelix.wpilogmcp.config.MirrorConfig config,
      Consumer<MirrorSync.Progress> progress) {
    return mirror(config, progress, java.time.Clock.systemUTC(), StoreSync::http);
  }

  CompletableFuture<MirrorSync.Result> mirror(org.triplehelix.wpilogmcp.config.MirrorConfig config,
      Consumer<MirrorSync.Progress> progress, java.time.Clock clock, StoreSync.Source source) {
    if (!root.equals(config.folder().toAbsolutePath().normalize()) && !sameRealFolder(root, config.folder())) return CompletableFuture.failedFuture(new IOException("mirror.folder differs from this store"));
    if (!syncing.compareAndSet(false, true)) return CompletableFuture.failedFuture(new IOException("A sync is already running for this store"));
    pending.incrementAndGet();
    try {
      return mutate(io -> new MirrorSync(root, io, security, logManager, clock, progress).run(config, source), true)
          .whenComplete((result, error) -> { syncing.set(false); pending.decrementAndGet(); });
    } catch (RuntimeException e) { syncing.set(false); pending.decrementAndGet(); throw e; }
  }

  private static boolean sameRealFolder(Path left, Path right) {
    try { return left.toRealPath().equals(right.toRealPath()); }
    catch (IOException e) { return false; }
  }

  /** Pins are local cache policy, not a change to the origin's session or a robot command. */
  public CompletableFuture<StoreManifest.MirrorOrigin> pin(String sessionId, boolean pinned) {
    StoreFiles.component(sessionId);
    return mutate(io -> {
      var header = io.read(root.resolve("store.json"), Header.class); var origin = header.origin();
      if (!header.mirror() || origin == null) throw new IOException("pin_session requires a mirror");
      var ids = new java.util.TreeSet<>(origin.pinnedSessions());
      if (pinned) ids.add(sessionId); else ids.remove(sessionId);
      var updated = new MirrorOrigin(origin.storeId(), origin.url(), origin.lastSync(), List.copyOf(ids), origin.sessions());
      io.write(root.resolve("store.json"), header.withOrigin(updated)); return updated;
    }, true);
  }

  /** A second caller queues behind the first, including its inspection and identity changes. */
  public CompletableFuture<Result> importPaths(Request request, Consumer<Progress> progress) {
    return importPrepared(request, progress, r -> r, result -> {});
  }

  public StoreUpload receiveUpload(String name, long length, String hash, java.io.InputStream input) throws IOException {
    return StoreUpload.receive(root, security, name, length, hash, input);
  }

  /** The laptop original is never moved; only the HTTP-owned staging file may be consumed. */
  public CompletableFuture<Result> importUpload(StoreUpload upload, String robot, Consumer<Progress> progress) throws IOException {
    if (!upload.root().equals(root)) throw new IOException("Upload belongs to another store");
    String name = upload.path().getFileName().toString();
    uploadSources.put(upload.path(), new Provenance("imported", "upload:" + name, name, Instant.now().toString(), false));
    try {
      return importPaths(new Request(List.of(upload.path()), true, robot), progress).handle((result, error) -> {
        uploadSources.remove(upload.path());
        try { upload.close(); } catch (IOException e) {
          if (error != null) error.addSuppressed(e); else throw new java.util.concurrent.CompletionException(e);
        }
        if (error != null) throw new java.util.concurrent.CompletionException(error);
        return new Result(result.files().stream().map(f -> new Outcome(Path.of(name), f.status(), f.path(), f.reason())).toList(), result.sameRobots());
      });
    } catch (RuntimeException e) {
      uploadSources.remove(upload.path()); upload.close(); throw e;
    }
  }

  /** Assignment is explicit: a duplicate elsewhere must never silently move a stored file. */
  public CompletableFuture<Result> assignPaths(List<Path> paths, String robot, Consumer<Progress> progress) {
    StoreFiles.robotName(robot);
    return enqueue(new Request(paths, true, robot), progress, r -> r, result -> {}, true);
  }

  @FunctionalInterface
  interface Preparation {
    Request prepare(Request request) throws IOException;
  }

  /** An inbox file can change while queued; recheck its observed stamp when its turn arrives. */
  CompletableFuture<Result> importPrepared(Request request, Consumer<Progress> progress,
      Preparation preparation, Consumer<Result> beforeUnlock) {
    return enqueue(request, progress, preparation, beforeUnlock, false);
  }

  private CompletableFuture<Result> enqueue(Request request, Consumer<Progress> progress,
      Preparation preparation, Consumer<Result> beforeUnlock, boolean assignment) {
    Objects.requireNonNull(request);
    Objects.requireNonNull(progress);
    var result = new CompletableFuture<Result>();
    pending.incrementAndGet();
    try {
      queue.execute(() -> {
        try {
          Result imported;
          try (var lock = StoreLock.acquire(root, security)) {
            if (StoreCatalog.isStore(root) && new StoreFiles(root, security, this::published).read(root.resolve("store.json"), Header.class).mirror()) {
              throw new IOException("A mirror is owned by its synchronization; imports and assignments are refused");
            }
            notify(progress, new Progress("starting", root, 0, request.paths().size()));
            imported = run(preparation.prepare(request), progress, assignment);
            beforeUnlock.accept(imported);
            publishInventory();
          }
          result.complete(imported);
        } catch (Exception e) {
          result.completeExceptionally(e);
        } catch (OutOfMemoryError e) {
          result.completeExceptionally(new IOException("Not enough heap to inspect this import", e));
        } finally {
          pending.decrementAndGet();
        }
      });
    } catch (RuntimeException e) {
      pending.decrementAndGet();
      throw e;
    }
    return result;
  }

  @Override
  public void close() {
    queue.shutdown();
  }

  /** A barrier for transport shutdown, before the log manager closes its readers. */
  public void awaitImports() {
    var drained = new CompletableFuture<Void>();
    queue.execute(() -> drained.complete(null));
    drained.join();
  }

  private Result run(Request request, Consumer<Progress> progress, boolean assignment) throws IOException {
    if (request.statedRobot() != null) StoreFiles.robotName(request.statedRobot());
    var io = new StoreFiles(root, security, this::published);
    var outcomes = new ArrayList<Outcome>();
    var sameRobots = new ArrayList<SameRobot>();
    var sources = expand(request.paths(), outcomes);
    if (!StoreCatalog.isStore(root)) {
      io.write(root.resolve("store.json"), new Header(StoreManifest.FORMAT_VERSION,
          Instant.now().toString(), UUID.randomUUID().toString(), List.of()));
    }
    var catalog = new ImportCatalog(catalogReader.read(root, security));
    var migrated = migrateUnassigned(io, catalog);
    sources = sources.stream().map(path -> migrated.getOrDefault(path, path)).toList();
    Files.createDirectories(io.check(root.resolve("inbox")));
    var known = new HashMap<String, Path>();
    catalog.files.values().forEach(f -> known.put(f.file().sha256(), f.path()));
    var inspected = new ArrayList<ImportInspection>();
    var batchHashes = new HashSet<String>();
    var repeats = new LinkedHashMap<Path, String>();
    var otherStores = new HashMap<Path, Set<Path>>();
    var parser = new RevLogParser(new DbcLoader().load(null));
    int completed = 0;
    for (var source : sources) {
      notify(progress, new Progress("inspecting", source, completed++, sources.size()));
      try {
        security.validate(source);
        StoreFiles.component(source.getFileName().toString());
        if (request.move()) refuseManagedMove(source, otherStores);
        // Reject an unmappable WPILOG before even hashing gigabytes that cannot be imported.
        if (Files.size(source) > Integer.MAX_VALUE) {
          try (var input = Files.newInputStream(source)) {
            if (java.util.Arrays.equals(input.readNBytes(6), new byte[]{'W', 'P', 'I', 'L', 'O', 'G'})) {
              throw org.triplehelix.wpilogmcp.log.LogFileException.tooLarge(source, Files.size(source));
            }
          }
        }
        // Hash first: a duplicate requires neither decoding nor a robot assignment.
        var hash = StoreFiles.hash(source);
        if (assignment) {
          var held = catalog.files.get(hash);
          if (held == null || held.session() != null || !held.path().equals(source)) {
            throw new IOException("Assignment requires an unassigned file in this store");
          }
          catalog.assigning.put(hash, held);
          catalog.files.remove(hash);
          known.remove(hash);
        }
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

  /** Moving another store's payload would leave its manifest pointing to a missing file. */
  private void refuseManagedMove(Path source, Map<Path, Set<Path>> otherStores) throws IOException {
    var ancestor = source;
    var owner = StoreCatalog.containing(ancestor);
    // A WPILOG named store.json is a payload, not its own parent directory's store marker.
    while (owner.isPresent() && source.equals(owner.get().resolve("store.json"))) {
      ancestor = owner.get();
      owner = StoreCatalog.containing(ancestor);
    }
    if (owner.isEmpty() || owner.get().equals(root)) return;
    var directory = owner.get();
    var managed = otherStores.get(directory);
    if (managed == null) {
      managed = new HashSet<>();
      for (var file : catalogReader.read(directory, security).files()) {
        managed.add(file.path().toRealPath());
      }
      otherStores.put(directory, managed);
    }
    if (managed.contains(source)) {
      throw new IOException("Source is listed by another store: " + directory + "; copy it instead");
    }
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
              if (!storeControlFile(file)) found.add(file.toRealPath());
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

  private boolean storeControlFile(Path path) {
    return path.equals(root.resolve("store.lock")) || path.equals(root.resolve("store.json"))
        || path.equals(root.resolve("inbox").resolve("imported.log"));
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
      var promoted = new Robot(serial, serial, previous.name(), metadata.comments(), "logged", previous.contacts());
      if (Files.exists(io.check(target))) {
        // Persist the shared identity, while keeping both histories physically separate.
        var alias = new Robot(previous.id(), serial, previous.name(), metadata.comments(), "logged", previous.contacts());
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
        existing != null && "device".equals(existing.basis()) ? "device" : serial == null ? "stated" : "logged",
        existing == null ? List.of() : existing.contacts());
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
    return rev.nearClock(candidate.start(), candidate.end());
  }

  private void place(StoreFiles io, ImportCatalog catalog, List<ImportInspection> inputs, ImportInspection primary,
      Robot robot, Map<Path, Pair> pairs, boolean move, RevLogParser parser,
      List<Outcome> outcomes, Consumer<Progress> progress) throws IOException {
    if (robot == null || primary.start() == null) {
      if (inputs.stream().anyMatch(input -> catalog.assigning.containsKey(input.hash()))) {
        for (var input : inputs) outcomes.add(new Outcome(input.path(), "refused", input.path(),
            "Assignment needs a robot and session time, or a unique correlated wpilog for a REV log"));
        return;
      }
      for (var input : inputs) {
        var directory = root.resolve("unassigned").resolve(input.hash().substring(0, 16));
        placeGroup(io, catalog, List.of(input), directory.resolve("import.json"), null, null, pairs,
            move, parser, outcomes, progress);
      }
      return;
    }
    // A session with only an open capture has no finished hash in catalog.files yet.
    // Nominate sessions from their manifests so a live boot can receive an overlapping import.
    var overlap = catalog.sessions.entrySet().stream()
        .filter(s -> s.getKey().startsWith(root.resolve("robots").resolve(robot.id())))
        .filter(s -> !primary.end().isBefore(Instant.parse(s.getValue().startedAt()))
            && !primary.start().isAfter(Instant.parse(s.getValue().endedAt())))
        .findFirst();
    if (overlap.isPresent()) {
      var found = overlap.get();
      placeGroup(io, catalog, inputs, found.getKey(), robot, found.getValue(), pairs, move, parser, outcomes, progress);
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
      var destination = payloadDestination(io, manifest, input.path().getFileName(), input.hash());
      var pair = pairs.get(input.path());
      var matching = pair == null ? null : new Matching("by_correlation", pair.wpilog().hash(),
          pair.sync().offsetMicros(), pair.sync().confidence(), pair.sync().driftRateNanosPerSec(),
          pair.sync().referenceTimeSec(), "data_alone", pair.sync());
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
        var previous = catalog.assigning.get(input.hash());
        var provenance = previous == null ? uploadSources.getOrDefault(input.path(), new Provenance("imported", input.path().toString(),
            input.path().getFileName().toString(), now, move)) : previous.file().provenance();
        records.add(new LogFile(StoreFiles.relative(manifest.getParent(), placement.destination()),
            input.hash(), input.size(), input.kind(), provenance, true, input.min(), input.max(),
            input.start() == null ? null : input.start().toString(),
            input.end() == null ? null : input.end().toString(), input.startBasis(), input.truncated(),
            placement.matching(), input.robotFingerprint()));
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
            session.event(), session.matchType(), session.matchNumber(), session.teamNumber(), List.copyOf(records), session.openCapture(), session.endReason(), session.deviceIdentity(), session.identityConflicts(), session.conflicts(), session.captureStats());
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
      var updated = header.withMoves(moves);
      io.write(root.resolve("store.json"), updated);
      catalog.header = updated;
    }
    for (var placement : placements) {
      var old = catalog.assigning.remove(placement.input().hash());
      if (old != null) {
        // Commit the new manifest first; until then the old manifest is the source of truth.
        Files.delete(io.check(old.manifestPath()));
        rewriteMoves(io, catalog, old.path(), placement.destination());
      }
    }
    for (var placement : placements) outcomes.add(new Outcome(placement.input().path(),
        session == null ? "unassigned" : "imported", placement.destination(), session == null
            ? "No robot assignment or unique correlated wpilog; awaiting assignment" : null));
  }

  /** Payloads never share a directory with manifests, even when their original names match. */
  private Path payloadDestination(StoreFiles io, Path manifest, Path name, String hash)
      throws IOException {
    var parent = manifest.getParent().resolve("robot");
    var destination = parent.resolve(name);
    if (CONTROL_NAMES.contains(name.toString().toLowerCase(Locale.ROOT)) || Files.exists(io.check(destination))) {
      destination = parent.resolve(hash).resolve(name);
    }
    return io.check(destination);
  }

  /**
   * Old manifests remain readable. Under the import lock, copy and verify their payloads before
   * switching the manifest, then remove the old files: a failed migration must keep log bytes.
   */
  private Map<Path, Path> migrateUnassigned(StoreFiles io, ImportCatalog catalog) throws IOException {
    var migrated = new HashMap<Path, Path>();
    for (var stored : List.copyOf(catalog.files.values())) {
      if (stored.session() != null || stored.path().startsWith(stored.manifestPath().getParent().resolve("robot"))) {
        continue;
      }
      var file = stored.file();
      var destination = payloadDestination(io, stored.manifestPath(), stored.path().getFileName(), file.sha256());
      try (var lease = releaseForMove(List.of(stored.path()))) {
        if (!file.sha256().equals(StoreFiles.hash(io.check(stored.path())))) {
          throw new IOException("Stored content no longer matches its manifest: " + stored.path());
        }
        Files.createDirectories(destination.getParent());
        Files.copy(io.check(stored.path()), io.check(destination), StandardCopyOption.COPY_ATTRIBUTES);
        if (!file.sha256().equals(StoreFiles.hash(destination))) {
          throw new IOException("Migration copy hash differs: " + destination);
        }
        var updated = new LogFile(StoreFiles.relative(stored.manifestPath().getParent(), destination),
            file.sha256(), file.sizeBytes(), file.kind(), file.provenance(), file.verified(),
            file.minTimestampSec(), file.maxTimestampSec(), file.startedAt(), file.endedAt(),
            file.startBasis(), file.truncated(), file.matching(), file.robotFingerprint(), file.matchingReason());
        io.write(stored.manifestPath(), updated);
        rewriteMoves(io, catalog, stored.path(), destination);
        catalog.placed(io, stored.manifestPath(), null, null, List.of(updated));
        Files.delete(io.check(stored.path()));
        migrated.put(stored.path(), destination);
      }
    }
    return migrated;
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
    var updated = header.withMoves(moves);
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
