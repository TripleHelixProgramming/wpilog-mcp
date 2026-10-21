/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.PosixFilePermission;
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
import org.triplehelix.wpilogmcp.log.LogMetadata;
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
 * One store's import staging and publication owner, independent of HTTP and the extension.
 * Inspection, correlation and copy verification finish before a short turn renames each
 * prepared group and commits its manifest. Failed ordinary copies remain strays; failed
 * cross-volume Move copies are removed without changing their originals.
 */
public final class LogStore implements AutoCloseable {
  /** Placement and recorded-source admission share one content proof. Callers validate grants first. */
  public static String contentHash(Path path) throws IOException { return StoreFiles.hash(path); }

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
  public record Reconsideration(int considered, int imported, int remaining, List<Outcome> files) {
    public Reconsideration { files = List.copyOf(files); }
  }

  private static final int MAX_REV_RECONSIDERATION = 32;
  private static final String NO_REV_ASSIGNMENT =
      "Assignment needs a robot and session time, or a unique correlated wpilog for a REV log";
  private record RevAttempt(String candidateGeneration) {}
  private record RevCursor(String candidateGeneration, String lastAttemptedHash) {}
  private final java.util.concurrent.atomic.AtomicReference<CompletableFuture<Reconsideration>> reconsidering =
      new java.util.concurrent.atomic.AtomicReference<>();

  /**
   * Arrival order must not decide whether two completed files are paired. This bounded staging
   * batch reuses assignment's exact correlation and placement path, without inventing a robot.
   * Per-content attempt receipts suppress repeated correlations across idle scans and restarts;
   * only the set of session-owned WPILOG hashes invalidates them. An unmatched REV imported
   * later has no receipt and is therefore still considered against the complete candidate set.
   */
  public CompletableFuture<Reconsideration> reconsiderUnassignedRev() {
    for (;;) {
      var previous = reconsidering.get();
      if (previous != null) return previous;
      var result = new CompletableFuture<Reconsideration>();
      if (!reconsidering.compareAndSet(null, result)) continue;
      pending.incrementAndGet();
      importJobs.add(result);
      result.whenComplete((value, failure) -> { importJobs.remove(result); pending.decrementAndGet(); });
      try {
        staging.execute(new StagedJob(result, () -> {
          Reconsideration value = null;
          Throwable failure = null;
          try {
            ensureImporting();
            if (!StoreCatalog.isStore(root)) value = new Reconsideration(0, 0, 0, List.of());
            else {
              var io = new StoreFiles(root, security, this::published);
              var header = io.read(root.resolve("store.json"), Header.class);
              if (header.mirror()) throw new IOException("A mirror cannot reconsider imported files");
              value = reconsiderRev(io);
              if (value.imported() > 0) publishInventory();
            }
          } catch (Exception e) { failure = e; }
          catch (OutOfMemoryError e) { failure = new IOException("Not enough heap to reconsider REV files", e); }
          finally {
            reconsidering.compareAndSet(result, null);
          }
          // Callbacks may enqueue more work: release the store lock before completing them.
          if (failure == null) result.complete(value); else result.completeExceptionally(failure);
        }));
      } catch (RuntimeException e) {
        reconsidering.compareAndSet(result, null);
        result.completeExceptionally(e);
      }
      return result;
    }
  }

  private Reconsideration reconsiderRev(StoreFiles io) throws IOException {
    var catalog = readImportCatalog();
    migrateUnassigned(io, catalog);
    var hashes = catalog.files.values().stream()
        .filter(f -> f.session() != null && "wpilog".equals(f.file().kind()))
        .map(f -> f.file().sha256()).distinct().sorted().toList();
    if (hashes.isEmpty()) return new Reconsideration(0, 0, 0, List.of());
    String generation;
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      for (var hash : hashes) {
        checkContentHash(hash);
        digest.update(hash.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      }
      generation = java.util.HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("The JDK must provide SHA-256", e);
    }
    var directory = root.resolve(".rev-reconsideration");
    var cursorPath = directory.resolve("cursor.json");
    String cursor = "";
    if (Files.exists(io.check(cursorPath))) {
      try {
        var saved = io.read(cursorPath, RevCursor.class);
        if (generation.equals(saved.candidateGeneration()) && saved.lastAttemptedHash() != null) {
          checkContentHash(saved.lastAttemptedHash());
          cursor = saved.lastAttemptedHash();
        }
      } catch (IOException e) {
        LoggerFactory.getLogger(LogStore.class).warn("Revalidating invalid REV reconsideration cursor: {}", e.getMessage());
      }
    }
    var unassigned = catalog.files.values().stream()
        .filter(f -> f.session() == null && "revlog".equals(f.file().kind()))
        .sorted(java.util.Comparator.comparing(f -> f.file().sha256())).toList();
    // Resume after the last completed batch, then wrap for new REV hashes before the cursor.
    String start = cursor;
    var ordered = java.util.stream.Stream.concat(
        unassigned.stream().filter(f -> f.file().sha256().compareTo(start) > 0),
        unassigned.stream().filter(f -> f.file().sha256().compareTo(start) <= 0)).toList();
    var pending = new ArrayList<StoredFile>();
    for (var file : ordered) {
      checkContentHash(file.file().sha256());
      var receipt = directory.resolve(file.file().sha256() + ".json");
      boolean attempted = false;
      if (Files.exists(io.check(receipt))) {
        try { attempted = generation.equals(io.read(receipt, RevAttempt.class).candidateGeneration()); }
        catch (IOException e) {
          LoggerFactory.getLogger(LogStore.class).warn("Revalidating invalid REV reconsideration receipt: {}", e.getMessage());
        }
      }
      if (!attempted) pending.add(file);
    }
    if (pending.isEmpty()) return new Reconsideration(0, 0, 0, List.of());
    var batch = pending.subList(0, Math.min(MAX_REV_RECONSIDERATION, pending.size()));
    var imported = run(new Request(batch.stream().map(StoredFile::path).toList(), true, null), p -> {}, true);
    var files = new HashMap<Path, StoredFile>();
    batch.forEach(file -> files.put(file.path(), file));
    int placed = 0;
    String last = cursor;
    for (var outcome : imported.files()) {
      var source = files.get(outcome.originalPath());
      if (source == null) throw new IOException("Unexpected REV reconsideration outcome: " + outcome.originalPath());
      if ("imported".equals(outcome.status())) placed++;
      else if (!"refused".equals(outcome.status()) || !NO_REV_ASSIGNMENT.equals(outcome.reason())) {
        throw new IOException("REV reconsideration could not place " + outcome.originalPath() + ": " + outcome.reason());
      }
      // A placement failure never becomes an attempted-generation receipt. It must be retryable.
      last = source.file().sha256();
      String receipt = last;
      importTurn(owned -> { owned.write(directory.resolve(receipt + ".json"), new RevAttempt(generation)); return null; });
    }
    String completed = last;
    importTurn(owned -> { owned.write(cursorPath, new RevCursor(generation, completed)); return null; });
    return new Reconsideration(batch.size(), placed, pending.size() - batch.size(), imported.files());
  }

  private static void checkContentHash(String hash) throws IOException {
    if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid content hash in REV reconsideration inventory");
  }

  private record Candidate(Path path, String hash, Robot robot, Instant start, Instant end) {}
  private record Pair(Candidate wpilog, SyncResult sync) {}
  private record Placement(ImportInspection input, Path destination, Matching matching,
      String placementMethod, String reason) {}
  private record ImportMatch(Path manifest, Matching matching, String reason, boolean hasAnchors) {}

  private static final Set<String> CONTROL_NAMES = Set.of(
      "import.json", "session.json", "robot.json", "store.json", "batch.json");

  private final Path root;
  private final SecurityValidator security;
  private final StoreQueue queue;
  private final ExecutorService staging = Executors.newSingleThreadExecutor(task -> {
    var thread = new Thread(task, "store-import-staging"); thread.setDaemon(true); return thread;
  });
  private final java.util.concurrent.atomic.AtomicBoolean importsStopped = new java.util.concurrent.atomic.AtomicBoolean();
  private final Set<CompletableFuture<?>> importJobs = java.util.concurrent.ConcurrentHashMap.newKeySet();
  /** Only work which never started can release its caller's uploads and grants immediately. */
  private record StagedJob(CompletableFuture<?> result, Runnable operation) implements Runnable {
    @Override public void run() { operation.run(); }
    void abandon() {
      result.completeExceptionally(new IOException("Store closed before import preparation; source retained"));
    }
  }
  private final LogManager logManager;
  private final CatalogReader catalogReader;
  private final StoreInbox inbox;
  private final Mover mover;
  private final AtomicInteger pending = new AtomicInteger();
  private final java.util.concurrent.atomic.AtomicBoolean syncing = new java.util.concurrent.atomic.AtomicBoolean();
  /** HTTP-owned staging paths are unique; source provenance lives only for their import job. */
  private final java.util.concurrent.ConcurrentHashMap<Path, Provenance> uploadSources = new java.util.concurrent.ConcurrentHashMap<>();

  @FunctionalInterface
  interface CatalogReader {
    StoreCatalog.Snapshot read(Path root, SecurityValidator security) throws IOException;
  }

  /** Preserve the attributes of the copied version, even if the source is replaced later. */
  record CopyAttributes(BasicFileAttributes basic, Set<PosixFilePermission> permissions,
      DosFileAttributes dos) {
    static CopyAttributes read(Path source) throws IOException {
      var store = Files.getFileStore(source);
      var posix = store.supportsFileAttributeView("posix")
          ? Files.readAttributes(source, PosixFileAttributes.class) : null;
      var dos = store.supportsFileAttributeView("dos")
          ? Files.readAttributes(source, DosFileAttributes.class) : null;
      return new CopyAttributes(posix == null ? Files.readAttributes(source, BasicFileAttributes.class)
          : posix, posix == null ? null : Set.copyOf(posix.permissions()), dos);
    }

    FileAttribute<?>[] creationAttributes(Path destination) throws IOException {
      if (permissions == null || !Files.getFileStore(destination.getParent()).supportsFileAttributeView("posix")) {
        return new FileAttribute<?>[0];
      }
      var writable = new HashSet<>(permissions);
      // Keep group/other access unchanged; the owner needs write access until force completes.
      writable.add(PosixFilePermission.OWNER_WRITE);
      return new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(writable)};
    }

    void restore(Path destination) throws IOException {
      Files.getFileAttributeView(destination, BasicFileAttributeView.class)
          .setTimes(basic.lastModifiedTime(), basic.lastAccessTime(), basic.creationTime());
      if (permissions != null && Files.getFileStore(destination).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(destination, permissions);
      }
      var view = Files.getFileAttributeView(destination, DosFileAttributeView.class);
      if (dos != null && view != null) {
        view.setArchive(dos.isArchive()); view.setHidden(dos.isHidden());
        view.setSystem(dos.isSystem()); view.setReadOnly(dos.isReadOnly());
      }
    }
  }

  /** Filesystem operations are injectable so tests can model a second volume without one. */
  static class Mover {
    boolean sameFileStore(Path source, Path destination) throws IOException {
      return Files.getFileStore(source).equals(Files.getFileStore(destination.getParent()));
    }
    void move(Path source, Path destination, java.nio.file.CopyOption... options) throws IOException {
      Files.move(source, destination, options);
    }
    /** An externally growing source cannot consume more than its inspected reservation. */
    CopyAttributes copy(Path source, Path destination, long admittedBytes) throws IOException {
      var attributes = CopyAttributes.read(source);
      // CREATE_NEW establishes ownership: failure cleanup must never delete an existing file.
      boolean created = false;
      try (var input = Files.newInputStream(source)) {
        try (var output = java.nio.channels.FileChannel.open(destination,
            Set.of(java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE),
            attributes.creationAttributes(destination))) {
          created = true;
          byte[] bytes = new byte[64 * 1024];
          long remaining = admittedBytes;
          while (remaining > 0) {
            int count = input.read(bytes, 0, (int) Math.min(bytes.length, remaining));
            if (count == -1) break;
            var buffer = java.nio.ByteBuffer.wrap(bytes, 0, count);
            while (buffer.hasRemaining()) output.write(buffer);
            remaining -= count;
          }
        }
        return attributes;
      } catch (IOException | RuntimeException failure) {
        if (created) {
          try { Files.delete(destination); }
          catch (IOException cleanup) { failure.addSuppressed(cleanup); }
        }
        throw failure;
      }
    }
    void force(Path destination) throws IOException {
      try (var channel = java.nio.channels.FileChannel.open(destination, java.nio.file.StandardOpenOption.WRITE)) {
        channel.force(true);
      }
    }
    void delete(Path source) throws IOException { Files.delete(source); }
    void discardCopy(Path destination) throws IOException {
      // Windows refuses deletion of a read-only file; this is our rejected copy, not the source.
      var dos = Files.getFileAttributeView(destination, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
      if (dos != null && dos.readAttributes().isReadOnly()) dos.setReadOnly(false);
      Files.delete(destination);
    }
  }

  /**
   * One staging worker's catalog. Own commits update it directly; concurrent capture changes
   * refresh it off-owner only when the facts used for the batch's decisions remain valid.
   */
  private static final class ImportCatalog {
    Header header;
    Map<Path, FileSnapshot> stamps = Map.of();
    List<ImportInspection> inputs = List.of();
    Map<Path, Robot> selectedRobots = Map.of();
    String statedRobot;
    ImportProof proof;
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

  /** Metadata replacements invalidate preparation without hashing any payload on the owner. */
  private Map<Path, FileSnapshot> importStamps() throws IOException {
    var stamps = new LinkedHashMap<Path, FileSnapshot>();
    for (var base : List.of(root.resolve("robots"), root.resolve("unassigned"))) {
      stamps.put(base, FileSnapshot.of(base));
      if (!Files.isDirectory(base)) continue;
      try (var paths = Files.walk(base, base.getFileName().toString().equals("robots") ? 5 : 2)) {
        for (var path : paths.toList()) {
          String name = path.getFileName().toString();
          if (Files.isDirectory(path) || name.equals("robot.json") || name.equals("session.json")
              || name.equals("import.json")) {
            security.validate(path);
            stamps.put(path, FileSnapshot.of(path));
          }
        }
      }
    }
    stamps.put(root.resolve("store.json"), FileSnapshot.of(root.resolve("store.json")));
    return stamps;
  }

  /** Sync prepares the same manifest/ancestor witness off-owner before a short publication. */
  Map<Path, FileSnapshot> syncStamps() throws IOException { return importStamps(); }

  private ImportCatalog readImportCatalog() throws IOException {
    for (int attempt = 0; attempt < 3; attempt++) {
      var before = importStamps();
      var catalog = new ImportCatalog(catalogReader.read(root, security));
      var after = importStamps();
      if (before.equals(after)) { catalog.stamps = after; return catalog; }
    }
    throw new IOException("Store metadata changed during import preparation; retry");
  }

  private void refreshImportCatalog(ImportCatalog catalog) throws IOException {
    boolean changed = !importCatalogUnchanged(catalog);
    var latest = changed ? readImportCatalog() : catalog;
    if (catalog.proof != null) {
      var now = importProof(latest, catalog.inputs, catalog.selectedRobots, catalog.statedRobot);
      if (!catalog.proof.remainsValid(now))
        throw new IOException("Import candidates or duplicate receipts changed after preparation; retry");
      catalog.proof = now;
    }
    if (!changed) return;
    catalog.header = latest.header;
    catalog.robots.clear(); catalog.robots.putAll(latest.robots);
    catalog.files.clear(); catalog.files.putAll(latest.files);
    catalog.assigning.keySet().forEach(catalog.files::remove);
    catalog.sessions.clear(); catalog.sessions.putAll(latest.sessions);
    catalog.stamps = latest.stamps;
  }

  private record RobotChoice(String id, String serial, String name) {
    static RobotChoice of(Robot robot) {
      return robot == null ? null : new RobotChoice(robot.id(), robot.serialNumber(), robot.name());
    }
  }
  private record ImportCandidate(Path input, Path path, Path manifest, RobotChoice robot,
      String sessionId, String startedAt, String provenance, Matching matching, String hash) {}
  private record ImportOverlap(Path input, Path manifest, String sessionId, String startedAt) {}
  private record ImportProof(String storeId, Map<Path, RobotChoice> robots,
      Map<ImportCandidate, FileSnapshot> candidates, Set<ImportOverlap> overlaps) {
    boolean remainsValid(ImportProof now) {
      if (!Objects.equals(storeId, now.storeId) || !robots.equals(now.robots)
          || !overlaps.equals(now.overlaps) || !candidates.keySet().equals(now.candidates.keySet())) return false;
      for (var entry : candidates.entrySet()) {
        var before = entry.getValue(); var after = now.candidates.get(entry.getKey());
        if (before == null) {
          if (after != null) return false;
          continue;
        }
        if (after == null) return false;
        if (!before.sameAs(after) && !(entry.getKey().hash() == null && after.grewFrom(before))) return false;
      }
      return true;
    }
  }

  /**
   * Calendar and identity only nominate the bytes that were compared. A growing capture may
   * retain its proven prefix, while a replacement, new candidate, or changed duplicate must
   * invalidate the decision. Unrelated capture statistics are not part of that proof.
   */
  private ImportProof importProof(ImportCatalog catalog, List<ImportInspection> inputs,
      Map<Path, Robot> selectedRobots, String stated) throws IOException {
    var robots = new LinkedHashMap<Path, RobotChoice>();
    var candidates = new LinkedHashMap<ImportCandidate, FileSnapshot>();
    var overlaps = new HashSet<ImportOverlap>();
    var batchHashes = inputs.stream().map(ImportInspection::hash).collect(java.util.stream.Collectors.toSet());
    for (var input : inputs) {
      var selected = selectedRobots.get(input.path());
      var robot = selected == null ? null : catalog.robots.get(selected.id());
      robots.put(input.path(), RobotChoice.of(robot));
      var duplicate = catalog.files.get(input.hash());
      if (duplicate != null) {
        var key = new ImportCandidate(input.path(), duplicate.path(), duplicate.manifestPath(),
            RobotChoice.of(duplicate.robot()), duplicate.session() == null ? null : duplicate.session().id(),
            null, "duplicate", null, duplicate.file().sha256());
        candidates.put(key, FileSnapshot.of(duplicate.path()));
      }
      if (input.kind().equals("revlog")) {
        for (var file : catalog.files.values()) {
          if (!file.file().kind().equals("wpilog") || file.session() == null
              || batchHashes.contains(file.file().sha256())) continue;
          if (stated != null && (file.robot() == null
              || !stated.equals(file.robot().id()) && !stated.equals(file.robot().name()))) continue;
          if (!input.nearClock(Instant.parse(file.session().startedAt()),
              Instant.parse(file.session().endedAt()))) continue;
          addImportCandidate(candidates, input, file);
        }
      } else if (robot != null) {
        for (var entry : catalog.sessions.entrySet()) {
          var manifest = entry.getKey(); var session = entry.getValue();
          var owner = catalog.robots.get(root.relativize(manifest).getName(1).toString());
          if (owner == null || !owner.id().equals(robot.id())
              && (robot.serialNumber() == null || !robot.serialNumber().equals(owner.serialNumber()))) continue;
          if (input.start() != null && !input.end().isBefore(Instant.parse(session.startedAt()))
              && !input.start().isAfter(Instant.parse(session.endedAt())))
            overlaps.add(new ImportOverlap(input.path(), manifest, session.id(), session.startedAt()));
          if (!input.nearClock(Instant.parse(session.startedAt()), Instant.parse(session.endedAt()))) continue;
          for (var file : importAnchors(session)) {
            if (file.sha256() != null && batchHashes.contains(file.sha256())) continue;
            var path = new StoreFiles(root, security).resolve(manifest.getParent(), file.path());
            addImportCandidate(candidates, input, new StoredFile(path, manifest, owner, session, file));
          }
        }
      }
    }
    return new ImportProof(catalog.header.id(), robots, candidates, overlaps);
  }

  private void addImportCandidate(Map<ImportCandidate, FileSnapshot> candidates,
      ImportInspection input, StoredFile file) throws IOException {
    security.validate(file.path());
    var key = new ImportCandidate(input.path(), file.path(), file.manifestPath(), RobotChoice.of(file.robot()),
        file.session().id(), file.session().startedAt(), file.file().provenance().kind(),
        file.file().matching(), file.file().sha256());
    candidates.put(key, FileSnapshot.of(file.path()));
  }

  /** Matching and its recheck must nominate exactly the same anchors. */
  private static List<LogFile> importAnchors(Session session) {
    var files = new ArrayList<>(session.files());
    if (session.openCapture() != null) {
      var open = session.openCapture();
      files.add(new LogFile(open.path(), null, open.sizeBytes(), "wpilog", open.provenance(), false,
          open.minTimestampSec(), open.maxTimestampSec(), session.startedAt(), session.endedAt(),
          session.startBasis(), false, null));
    }
    var captures = files.stream().filter(f -> f.kind().equals("wpilog")
        && f.provenance().kind().equals("captured")).toList();
    return captures.isEmpty() ? files.stream().filter(f -> f.kind().equals("wpilog")
        && f.matching() != null && "by_correlation".equals(f.matching().method())).toList() : captures;
  }

  private void checkImportCatalog(ImportCatalog catalog) throws IOException {
    if (!importCatalogUnchanged(catalog))
      throw new ImportCatalogChanged();
  }

  private static final class ImportCatalogChanged extends IOException {
    ImportCatalogChanged() { super("Store metadata changed after import preparation; retry"); }
  }

  /**
   * Capture takes priority even after staging refreshed its facts. Retry only that pre-mutation
   * metadata race off-owner, retaining verified bytes; source or admission failures never retry.
   */
  private <T> T preparedImportTurn(ImportCatalog catalog, CaptureOperation<T> operation) throws IOException {
    for (int attempt = 0; attempt < 3; attempt++) {
      refreshImportCatalog(catalog);
      try {
        return importTurn(io -> {
          checkImportCatalog(catalog);
          return operation.run(io);
        });
      } catch (ImportCatalogChanged changed) {
        if (attempt == 2)
          throw new IOException("Store metadata changed during three publication attempts; retry the import", changed);
      }
    }
    throw new AssertionError("Bounded import attempts must return or refuse");
  }

  private boolean importCatalogUnchanged(ImportCatalog catalog) throws IOException {
    for (var entry : catalog.stamps.entrySet())
      if (!Objects.equals(entry.getValue(), FileSnapshot.of(entry.getKey()))) return false;
    return true;
  }

  /** Known metadata and its ancestors suffice after our own turn; no tree walk runs on the owner. */
  private void updateImportStamps(ImportCatalog catalog, Path... additionalPaths) throws IOException {
    var paths = new HashSet<>(catalog.stamps.keySet());
    catalog.robots.values().forEach(robot -> paths.add(root.resolve("robots").resolve(robot.id()).resolve("robot.json")));
    catalog.files.values().forEach(file -> paths.add(file.manifestPath()));
    paths.addAll(catalog.sessions.keySet());
    paths.addAll(List.of(additionalPaths));
    for (var path : List.copyOf(paths)) {
      for (var parent = path.getParent(); parent != null && parent.startsWith(root) && !parent.equals(root);
          parent = parent.getParent()) paths.add(parent);
    }
    var updated = new LinkedHashMap<Path, FileSnapshot>();
    for (var path : paths) updated.put(path, FileSnapshot.of(path));
    catalog.stamps = updated;
  }

  LogStore(Path root, SecurityValidator security) {
    this(root, security, LogManager.getInstance(), StoreCatalog::read);
  }

  LogStore(Path root, SecurityValidator security, LogManager logManager, CatalogReader catalogReader) {
    this(root, security, logManager, catalogReader, new Mover());
  }

  LogStore(Path root, SecurityValidator security, LogManager logManager, CatalogReader catalogReader,
      Mover mover) {
    this(root, security, logManager, catalogReader, mover, null);
  }

  LogStore(Path root, SecurityValidator security, LogManager logManager, CatalogReader catalogReader,
      Mover mover, DiskSpaceBudgets.StoreBudget diskBudget) {
    this.diskBudget.set(diskBudget);
    FileNameEncoding.warnIfNeeded();
    this.root = root;
    this.security = security;
    this.logManager = logManager;
    this.catalogReader = catalogReader;
    this.inbox = new StoreInbox(this, security);
    this.mover = mover;
    queue = new StoreQueue();
    // Recovery may walk a season of directories. It shares staging's lifetime and never
    // delays a capture turn or runs under the registry's constructor/publication monitor.
    staging.execute(() -> {
      try { if (!importsStopped.get()) ImportStage.recover(new StoreFiles(root, security)); }
      catch (IOException | RuntimeException failure) {
        LoggerFactory.getLogger(LogStore.class).warn("Abandoned import stage recovery will retry: {}", failure.getMessage());
      }
      if (!importsStopped.get()) initialCatalogCapacity();
    });
    requestPublicationRecovery(false);
  }

  private final ReplicaTransfers replicaTransfers = new ReplicaTransfers(this);
  ReplicaTransfers replicaTransfers() { return replicaTransfers; }
  void replicaPublished(Header header) { published(root.resolve("store.json"), header); }
  SecurityValidator replicaSecurity() { return security; }
  LogManager replicaLogManager() { return logManager; }

  /** Only bounded replica metadata uses this turn; transfer, hashing and retirement stay off-queue. */
  <T> CompletableFuture<T> replicaTurn(CaptureOperation<T> operation) {
    return mutate(operation, true, false);
  }

  private final java.util.concurrent.atomic.AtomicReference<DiskSpaceBudgets.StoreBudget> diskBudget =
      new java.util.concurrent.atomic.AtomicReference<>();

  /** Package-level owners share the manager's registry instead of inventing another disk budget. */
  public DiskSpaceBudgets.StoreBudget diskBudget() throws IOException {
    var value = diskBudget.get();
    if (value != null) return value;
    value = logManager.stores().diskBudget(root);
    diskBudget.compareAndSet(null, value);
    return diskBudget.get();
  }

  /** Resolve the destination filesystem off-owner; a nested mount cannot spend its parent's space. */
  DiskSpaceBudgets.Reservation reserveDiskCopy(Path destinationDirectory, long bytes) throws IOException {
    var destination = new StoreFiles(root, security).check(destinationDirectory);
    return diskBudget().destination(destination).reserveCopy(bytes);
  }

  public Path root() {
    return root;
  }

  /** Pull placement uses the same filesystem boundary as import placement. */
  Mover mover() { return mover; }

  /** The capture service observes durable facts on the store queue; tools never join that queue. */
  public interface Observer {
    void manifest(Path path, Object value);
    void inventory(StoreCatalog.Snapshot snapshot);
  }
  private final java.util.Set<Observer> observers = java.util.concurrent.ConcurrentHashMap.newKeySet();
  public AutoCloseable observe(Observer observer) { observers.add(observer); return () -> observers.remove(observer); }
  private final java.util.Set<Consumer<SystemPullStore.Text>> textObservers = java.util.concurrent.ConcurrentHashMap.newKeySet();
  /** Called by a pull worker after its receipt commits and the store queue/lock are released. */
  public AutoCloseable observeSystemText(Consumer<SystemPullStore.Text> observer) {
    textObservers.add(observer); return () -> textObservers.remove(observer);
  }
  void publishedSystemText(SystemPullStore.Text text) {
    if (text == null) return;
    for (var observer : textObservers) try { observer.accept(text); }
    catch (RuntimeException failure) { LoggerFactory.getLogger(LogStore.class).warn("System text gateway publication failed; durable text retained: {}", failure.getMessage()); }
  }
  private final java.util.concurrent.atomic.AtomicLong publicationGeneration = new java.util.concurrent.atomic.AtomicLong();
  private final java.util.concurrent.atomic.AtomicBoolean inventoryRetryQueued = new java.util.concurrent.atomic.AtomicBoolean();

  private final java.util.concurrent.atomic.AtomicReference<StoreRegistry.CatalogCapacity> catalogCapacity =
      new java.util.concurrent.atomic.AtomicReference<>();

  /** Status and scrapes read the last observed header; they never wait for a store mutation. */
  StoreRegistry.CatalogCapacity catalogCapacity() { return catalogCapacity.get(); }

  private void initialCatalogCapacity() {
    if (catalogCapacity.get() != null) return;
    try {
      if (!StoreCatalog.isStore(root)) return;
      var header = new StoreFiles(root, security).read(root.resolve("store.json"), Header.class);
      catalogCapacity.compareAndSet(null, capacityOf(header));
    } catch (IOException | RuntimeException failure) {
      // Unknown is omitted, never an invented empty catalog. A later publication supplies facts.
      LoggerFactory.getLogger(LogStore.class).warn("Initial replica capacity is unavailable: {}", failure.getClass().getSimpleName());
    }
  }

  private StoreRegistry.CatalogCapacity capacityOf(Header header) throws IOException {
    StoreCatalog.validateHeader(root, header);
    return new StoreRegistry.CatalogCapacity(header.id(), ReplicaCatalogCapacity.of(header.replicas()));
  }

  /**
   * Only committed owner turns replace these facts. A replica worker can notify observers after
   * a later turn has committed, so its delayed header must not rewind the capacity warning.
   */
  private void publishCatalogCapacity(Path path, Object value) {
    if (!(value instanceof Header header) || !path.equals(root.resolve("store.json"))) return;
    replicaPublications.header(header.id());
    try { catalogCapacity.set(capacityOf(header)); }
    catch (IOException invalid) {
      // A non-null unknown marker also prevents an older startup read from winning its CAS.
      catalogCapacity.set(new StoreRegistry.CatalogCapacity(null, null));
    }
  }

  private void published(Path path, Object value) {
    if (queue.ownsCurrentThread()) publishCatalogCapacity(path, value);
    publicationGeneration.incrementAndGet();
    logManager.catalogPublished();
    for (var observer : observers) {
      try { observer.manifest(path, value); }
      catch (RuntimeException e) { org.slf4j.LoggerFactory.getLogger(LogStore.class).warn("Session status update failed", e); }
    }
  }

  /**
   * Import preparation reads inventory off the owner, then joins only its bounded publication.
   * Capture has priority over that publication, so a generation check discards a snapshot which
   * a newer capture update overtook. A stale view must never erase that newer live state.
   */
  private void publishInventory() throws IOException {
    if (observers.isEmpty()) return;
    long generation = publicationGeneration.get();
    var snapshot = StoreCatalog.readInventory(root, security, (manifest, reason) ->
        LoggerFactory.getLogger(LogStore.class).warn("Skipping invalid session inventory {}: {}", manifest, reason));
    if (queue.ownsCurrentThread()) {
      publishInventorySnapshot(snapshot);
      return;
    }
    var published = new CompletableFuture<Boolean>();
    try {
      queue.importTurn("inventory-publication", () -> {
        try {
          if (publicationGeneration.get() != generation) published.complete(false);
          else { publishInventorySnapshot(snapshot); published.complete(true); }
        } catch (Throwable failure) { published.completeExceptionally(failure); }
      });
    } catch (java.util.concurrent.RejectedExecutionException closed) {
      return; // The durable import is committed; a closed observer owner needs no refresh.
    }
    boolean interrupted = false;
    try {
      for (;;) {
        try {
          if (!published.get()) retryInventory();
          return;
        } catch (InterruptedException stopping) {
          // Do not finish a staging job while its admitted observer publication is still using it.
          interrupted = true;
        } catch (java.util.concurrent.ExecutionException failure) {
          throw new IOException("Inventory publication failed", failure.getCause());
        }
      }
    } finally { if (interrupted) Thread.currentThread().interrupt(); }
  }

  private void publishInventorySnapshot(StoreCatalog.Snapshot snapshot) {
    publicationGeneration.incrementAndGet();
    observers.forEach(observer -> observer.inventory(snapshot));
  }

  /** One deferred read retries an overtaken view without failing an already committed import. */
  private void retryInventory() {
    if (importsStopped.get() || observers.isEmpty() || !inventoryRetryQueued.compareAndSet(false, true)) return;
    try {
      staging.execute(() -> {
        inventoryRetryQueued.set(false);
        if (importsStopped.get()) return;
        try { publishInventory(); }
        catch (IOException | RuntimeException failure) {
          LoggerFactory.getLogger(LogStore.class).warn("Inventory refresh remains pending: {}", failure.getMessage());
        }
      });
    } catch (java.util.concurrent.RejectedExecutionException stopped) { inventoryRetryQueued.set(false); }
  }
  private volatile String replicaPublicationFailure;
  String replicaPublicationFailure() { return replicaPublicationFailure; }
  private final ReplicaPublications replicaPublications = new ReplicaPublications();
  private volatile java.time.Clock publicationClock = java.time.Clock.systemUTC();
  private final Object publicationRecoveryLock = new Object();
  private CompletableFuture<Void> publicationRecovery, nextPublicationRecovery;
  private CompletableFuture<Void> lastPublicationRecovery = CompletableFuture.completedFuture(null);
  private final java.util.concurrent.atomic.AtomicLong publicationWalks = new java.util.concurrent.atomic.AtomicLong();
  @FunctionalInterface interface PublicationRecoveryProbe { void observed(Path marker) throws IOException; }
  private volatile PublicationRecoveryProbe publicationRecoveryProbe = marker -> {};
  void publicationRecoveryProbe(PublicationRecoveryProbe probe) { publicationRecoveryProbe = Objects.requireNonNull(probe); }
  @FunctionalInterface interface PublicationStartProbe { void selected(String storeId) throws IOException; }
  private volatile PublicationStartProbe publicationStartProbe = identity -> {};
  void publicationStartProbe(PublicationStartProbe probe) { publicationStartProbe = Objects.requireNonNull(probe); }
  private volatile StoreFiles.AnnotationProbe publicationAnnotationProbe = marker -> {};
  void publicationAnnotationProbe(StoreFiles.AnnotationProbe probe) { publicationAnnotationProbe = Objects.requireNonNull(probe); }
  void publicationClock(java.time.Clock clock) { publicationClock = Objects.requireNonNull(clock); }

  /** Status never starts a sweep, opens a receipt or waits for store work. */
  public ReplicaPublications.Snapshot publicationStatus() { return replicaPublications.snapshot(); }
  /** Counts actual recovery walk opens, independently of request or status counts. */
  public long publicationWalks() { return publicationWalks.get(); }
  public CompletableFuture<Void> publicationsSettled() {
    synchronized (publicationRecoveryLock) { return lastPublicationRecovery; }
  }

  public CompletableFuture<Void> refreshStatus() { return requestPublicationRecovery(true); }
  /** A request during a pass shares one follow-up, so its receipts cannot predate the selected pass. */
  public CompletableFuture<Void> recoverMasterPublication() { return requestPublicationRecovery(true); }

  private CompletableFuture<Void> requestPublicationRecovery(boolean followUp) {
    CompletableFuture<Void> start = null, selected;
    synchronized (publicationRecoveryLock) {
      if (importsStopped.get()) return CompletableFuture.failedFuture(new IOException("Store is closed"));
      if (publicationRecovery == null) {
        publicationRecovery = new CompletableFuture<>(); start = publicationRecovery;
        selected = publicationRecovery;
      } else if (followUp) {
        if (nextPublicationRecovery == null) nextPublicationRecovery = new CompletableFuture<>();
        selected = nextPublicationRecovery;
      } else selected = publicationRecovery;
      lastPublicationRecovery = selected;
    }
    if (start != null) submitPublicationRecovery(start);
    return selected;
  }

  private void submitPublicationRecovery(CompletableFuture<Void> result) {
    try {
      staging.execute(new StagedJob(result, () -> {
        Throwable failure = null;
        try { runPublicationRecovery(); }
        catch (Throwable stopped) { failure = stopped; }
        CompletableFuture<Void> next;
        synchronized (publicationRecoveryLock) {
          next = nextPublicationRecovery; nextPublicationRecovery = null; publicationRecovery = next;
        }
        if (failure == null) result.complete(null); else result.completeExceptionally(failure);
        if (next != null) submitPublicationRecovery(next);
      }));
    } catch (java.util.concurrent.RejectedExecutionException stopped) {
      result.completeExceptionally(new IOException("Store closed before publication recovery", stopped));
    }
  }

  /** Only one bounded receipt is held while a staging worker awaits its short owner turn. */
  private void runPublicationRecovery() throws IOException {
    ensureImporting();
    final StoreFiles io;
    final String identity;
    try {
      if (!StoreCatalog.isStore(root)) {
        publicationStartProbe.selected(null);
        publicationMutation(null, files -> {
          if (replicaPublications.snapshot().storeId() != null)
            throw new IOException("SOURCE_UNAVAILABLE: known store disappeared");
          return null;
        }, ignored -> { replicaPublications.begin(null); replicaPublications.finish(true, null); });
        return;
      }
      io = new StoreFiles(root, security);
      identity = io.read(root.resolve("store.json"), Header.class).id();
      publicationStartProbe.selected(identity);
      publicationMutation(identity, files -> null, ignored -> replicaPublications.begin(identity));
    } catch (IOException | RuntimeException unavailable) {
      publicationState(() -> { replicaPublications.unavailable("SOURCE_UNAVAILABLE"); replicaPublications.publish(); });
      throw unavailable instanceof IOException checked ? checked
          : new IOException("Publication header could not be inspected", unavailable);
    }
    IOException failure = null;
    try {
      publicationMutation(identity, files -> { ReplicaMasters.recover(root, files); return null; }, ignored -> {});
    } catch (IOException unsafe) { failure = unsafe; }
    int inspected = 0, observed = 0;
    publicationWalks.incrementAndGet();
    try (var paths = Files.walk(root)) {
      var iterator = paths.iterator();
      while (iterator.hasNext()) {
        ensureImporting();
        var marker = iterator.next();
        if (++inspected > ReplicaPublications.MAX_OBSERVATIONS)
          throw new IOException("RESOURCE_LIMIT: capture publication recovery exceeds32768 paths");
        if (!marker.getFileName().toString().equals(ReplicaMasters.CAPTURE_PENDING)) continue;
        try {
          var candidate = ReplicaMasters.inspectCapture(root, io, marker);
          publicationRecoveryProbe.observed(marker);
          publicationMutation(identity, files -> replicaPublications.changed(candidate.receipt()) ? null
              : ReplicaMasters.retryCapture(root, files, candidate, publicationClock), event -> {
                if (event != null) replicaPublications.observed(event);
              });
          if (++observed % 64 == 0) publicationState(replicaPublications::publish);
        } catch (java.nio.file.NoSuchFileException missing) {
          // Only a proven removed marker in the same store is benign. Missing receipt bytes
          // or a vanished/replaced store cannot turn failed inspection into a complete zero.
          boolean removed = false;
          try {
            removed = Files.notExists(io.check(marker)) && StoreCatalog.isStore(root)
                && identity.equals(io.read(root.resolve("store.json"), Header.class).id());
          } catch (IOException | RuntimeException unavailable) { /* Keep the original failed-read evidence. */ }
          if (!removed && failure == null) failure = missing;
        } catch (IOException | RuntimeException invalid) {
          if (failure == null) failure = invalid instanceof IOException checked ? checked
              : new IOException("Invalid capture publication recovery", invalid);
        }
      }
    } catch (IOException | RuntimeException incomplete) {
      if (failure == null) failure = incomplete instanceof IOException checked ? checked
          : new IOException("Capture publication enumeration could not finish", incomplete);
    }
    var finalFailure = failure;
    publicationState(() -> replicaPublications.finish(finalFailure == null,
        finalFailure != null && finalFailure.getMessage() != null && finalFailure.getMessage().startsWith("RESOURCE_LIMIT:")
            ? "RESOURCE_LIMIT" : "SOURCE_UNAVAILABLE"));
    var snapshot = publicationStatus();
    if (failure != null || snapshot.state() != ReplicaPublications.State.READY) {
      replicaPublicationFailure = "PUBLICATION_PENDING: " + (snapshot.failureCode() == null ? "SOURCE_UNAVAILABLE" : snapshot.failureCode());
      throw failure != null ? failure : new IOException(replicaPublicationFailure);
    }
    publishInventory();
    if (snapshot.knownPendingCount() != 0) {
      replicaPublicationFailure = "PUBLICATION_PENDING: recorded captures remain local; " + snapshot.entries().get(0).cause().code();
      throw new PublicationPendingException(replicaPublicationFailure);
    }
    replicaPublicationFailure = null;
  }

  private void publicationState(Runnable update) throws IOException {
    var completed = new CompletableFuture<Void>();
    try {
      queue.importTurn("publication-status", () -> {
        try { update.run(); completed.complete(null); }
        catch (Throwable failure) { completed.completeExceptionally(failure); }
      });
    } catch (java.util.concurrent.RejectedExecutionException stopped) { throw new IOException("Store is closed", stopped); }
    awaitPublication(completed);
  }

  /** Recovery never creates a vanished store, and observers run only after store.lock is released. */
  private <T> T publicationMutation(String identity, CaptureOperation<T> operation,
      Consumer<T> applied) throws IOException {
    var completed = new CompletableFuture<T>();
    try {
      queue.importTurn("capture-publication", () -> {
        T value = null; Throwable failure = null;
        var publications = new ArrayList<Map.Entry<Path, Object>>();
        try {
          ensureImporting();
          try (var lock = StoreLock.acquire(root, security)) {
            boolean exists = StoreCatalog.isStore(root);
            if (!exists && identity != null) throw new IOException("SOURCE_UNAVAILABLE: store disappeared");
            var files = new StoreFiles(root, security, (path, record) -> publications.add(Map.entry(path, record)));
            files.annotationProbe(publicationAnnotationProbe);
            files.publicationCauses(replicaPublications::cause);
            if (exists && !Objects.equals(identity, files.read(root.resolve("store.json"), Header.class).id()))
              throw new IOException("SOURCE_UNAVAILABLE: store identity changed");
            value = operation.run(files);
          }
        } catch (Throwable stopped) { failure = stopped; }
        for (var item : publications) publishCatalogCapacity(item.getKey(), item.getValue());
        if (failure == null) {
          try { applied.accept(value); completed.complete(value); }
          catch (Throwable invalid) { completed.completeExceptionally(invalid); }
        } else completed.completeExceptionally(failure);
      });
    } catch (java.util.concurrent.RejectedExecutionException stopped) { throw new IOException("Store is closed", stopped); }
    return awaitPublication(completed);
  }

  private static <T> T awaitPublication(CompletableFuture<T> completed) throws IOException {
    boolean interrupted = false;
    try {
      for (;;) try { return completed.get(); }
      catch (InterruptedException stopping) { interrupted = true; }
      catch (java.util.concurrent.ExecutionException failed) {
        if (failed.getCause() instanceof IOException io) throw io;
        throw new IOException("Publication recovery failed", failed.getCause());
      }
    } finally { if (interrupted) Thread.currentThread().interrupt(); }
  }

  /** Atomic manifest replacement permits nonblocking status reads while a sync is queued. */
  public StoreManifest.MirrorOrigin mirrorOrigin() throws IOException {
    if (!StoreCatalog.isStore(root)) return null;
    return new StoreFiles(root, security, this::published).read(root.resolve("store.json"), Header.class).origin();
  }

  public Header replicaHeader() throws IOException {
    return new StoreFiles(root, security).read(root.resolve("store.json"), Header.class);
  }
  public MirrorCapacity.Snapshot mirrorCapacity(org.triplehelix.wpilogmcp.config.MirrorConfig config) throws IOException {
    return MirrorCapacity.measure(root, security, config).snapshot();
  }

  private static final java.util.concurrent.ThreadPoolExecutor REPLICA_PREPARERS =
      new java.util.concurrent.ThreadPoolExecutor(0, 2, 30, java.util.concurrent.TimeUnit.SECONDS,
          new java.util.concurrent.SynchronousQueue<>(), task -> {
            var thread = new Thread(task, "store-replica-migration"); thread.setDaemon(true); return thread;
          });
  private final java.util.concurrent.atomic.AtomicBoolean replicaPreparing = new java.util.concurrent.atomic.AtomicBoolean();

  /** Explicit migration preserves payloads. Preparation is bounded and off-queue; only publication
   * takes the store lock, and no user continuation runs until that lock has been released. */
  public CompletableFuture<ReplicaCatalog> migrateReplicas(String expectedStoreId) {
    return migrateReplicas(expectedStoreId, null, () -> ReplicaMigration.prepare(root, security, java.time.Clock.systemUTC()));
  }
  CompletableFuture<ReplicaCatalog> migrateReplicas(String expectedStoreId, org.triplehelix.wpilogmcp.config.MirrorConfig config) {
    return migrateReplicas(expectedStoreId, config, () -> ReplicaMigration.prepare(root, security, java.time.Clock.systemUTC()));
  }
  @FunctionalInterface interface ReplicaPreparation { ReplicaMigration.Prepared prepare() throws IOException; }
  CompletableFuture<ReplicaCatalog> migrateReplicas(String expectedStoreId,
      org.triplehelix.wpilogmcp.config.MirrorConfig config, ReplicaPreparation preparation) {
    var result = new CompletableFuture<ReplicaCatalog>();
    if (!replicaPreparing.compareAndSet(false, true)) return CompletableFuture.failedFuture(new IOException("BUSY: replica migration already running"));
    pending.incrementAndGet();
    try {
      REPLICA_PREPARERS.execute(() -> {
        ReplicaMigration.Prepared prepared = null;
        MirrorCapacity.Reservation reservation = null;
        try {
          if (!replicaHeader().id().equals(expectedStoreId)) throw new IOException("SOURCE_UNAVAILABLE: store identity changed");
          prepared = preparation.prepare();
          if (!prepared.before().id().equals(expectedStoreId)) throw new IOException("SOURCE_UNAVAILABLE: store identity changed");
          if (config != null && prepared.before().replicas() == null) {
            long newBytes = ReplicaCatalogFiles.metadataBytes(prepared.after());
            long oldBytes = StoreFiles.JSON.toJson(prepared.before()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            reservation = MirrorCapacity.measure(root, security, config).reserveMetadata(Math.addExact(oldBytes, Math.multiplyExact(2, newBytes)));
          }
          var owned = prepared;
          var charged = reservation;
          var completed = new CompletableFuture<Void>();
          queue.execute(() -> {
            Header published = null; Throwable failure = null;
            try (owned; var lock = StoreLock.acquire(root, security)) {
              if (queue.isShutdown() || result.isCancelled()) throw new IOException("Migration cancelled before publication");
              published = ReplicaMigration.publish(root, new StoreFiles(root, security), owned);
            } catch (Throwable e) { failure = e; }
            finally { if (charged != null) charged.close(); }
            // Release ownership before completing a future: its callback may reenter the store.
            replicaPreparing.set(false); pending.decrementAndGet();
            completed.complete(null);
            if (failure == null) {
              published(root.resolve("store.json"), published); result.complete(published.replicas());
            } else result.completeExceptionally(failure);
          });
          // Keep the two preparation slots charged through publication and lease release.
          // Cancelling the public future does not release an outstanding OS read's slot.
          completed.join();
        } catch (Throwable failure) {
          if (prepared != null) prepared.close();
          if (reservation != null) reservation.close();
          replicaPreparing.set(false); pending.decrementAndGet(); result.completeExceptionally(failure);
        }
      });
    } catch (java.util.concurrent.RejectedExecutionException busy) {
      replicaPreparing.set(false); pending.decrementAndGet(); result.completeExceptionally(new IOException("BUSY: two replica migrations are preparing; retry", busy));
    }
    return result;
  }

  @FunctionalInterface interface CaptureOperation<T> { T run(StoreFiles files) throws IOException; }

  /** Capture manifest changes share the import queue and the cross-process store lock. */
  <T> T capture(CaptureOperation<T> operation) throws IOException {
    try { return captureAsync(operation).get(); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted capture placement", e); }
    catch (java.util.concurrent.ExecutionException e) { throw new IOException("Capture placement failed: " + e.getCause().getMessage(), e.getCause()); }
  }

  <T> CompletableFuture<T> captureAsync(CaptureOperation<T> operation) { return mutate(operation, false); }
  <T> CompletableFuture<T> recordingAsync(CaptureOperation<T> operation) {
    return recordingAsync(operation, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30));
  }
  <T> CompletableFuture<T> recordingAsync(CaptureOperation<T> operation, long deadlineNanos) {
    return mutate(io -> {
      var header = io.read(root.resolve("store.json"), Header.class);
      if (header.replicas() != null) io.recording(reason -> {
        replicaPublicationFailure = reason;
        LoggerFactory.getLogger(LogStore.class).warn("{}", reason);
      });
      return operation.run(io);
    }, false, true, true, deadlineNanos);
  }

  private <T> CompletableFuture<T> mutate(CaptureOperation<T> operation, boolean allowMirror) {
    return mutate(operation, allowMirror, true);
  }

  private <T> CompletableFuture<T> mutate(CaptureOperation<T> operation, boolean allowMirror, boolean inventory) {
    return mutate(operation, allowMirror, inventory, false, 0);
  }

  private static final java.util.concurrent.ScheduledExecutorService LOCK_RETRIES =
      Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "store-lock-retry"); thread.setDaemon(true); return thread;
      });

  private <T> CompletableFuture<T> mutate(CaptureOperation<T> operation, boolean allowMirror,
      boolean inventory, boolean priority, long deadlineNanos) {
    var result = new CompletableFuture<T>();
    class Attempt implements Runnable {
      private int retries;
      void submit() {
        try {
          if (priority) queue.capture("capture", this);
          else queue.importTurn("metadata", this);
        } catch (java.util.concurrent.RejectedExecutionException closed) {
          result.completeExceptionally(new IOException("Store is closed", closed));
        }
      }
      @Override public void run() {
        if (result.isDone()) return;
        T value = null; Throwable failure = null;
        var publications = new ArrayList<Map.Entry<Path, Object>>();
        var pendingPublications = new ArrayList<ReplicaPublications.Event>();
        String observedStoreId = null;
        try {
          if (deadlineNanos != 0 && System.nanoTime() >= deadlineNanos)
            throw new IOException("Capture admission deadline expired before its store turn");
          try (var lock = StoreLock.acquire(root, security)) {
            if (deadlineNanos != 0 && System.nanoTime() >= deadlineNanos)
              throw new IOException("Capture admission deadline expired while acquiring store.lock");
            var io = new StoreFiles(root, security, (path, record) -> publications.add(Map.entry(path, record)));
            io.publicationEvents(pendingPublications::add, publicationClock);
            io.annotationProbe(publicationAnnotationProbe);
            io.publicationCauses(replicaPublications::cause);
            if (!StoreCatalog.isStore(root)) io.write(root.resolve("store.json"),
                new Header(StoreManifest.FORMAT_VERSION, Instant.now().toString(), UUID.randomUUID().toString(), List.of()));
            var header = io.read(root.resolve("store.json"), Header.class);
            observedStoreId = header.id();
            if (!StoreManifest.readable(header.formatVersion())) throw new IOException("Unsupported store format version " + header.formatVersion());
            if (header.mirror() && !allowMirror) throw new IOException("A mirror is written only by its mirror synchronization");
            value = operation.run(io);
          }
        } catch (StoreLock.Held held) {
          long remaining = deadlineNanos - System.nanoTime();
          if (priority && remaining > 0 && !queue.isShutdown()) {
            long delay = Math.min(remaining, java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                Math.min(50, 5L << Math.min(retries++, 4))));
            LOCK_RETRIES.schedule(this::submit, delay, java.util.concurrent.TimeUnit.NANOSECONDS);
            return;
          }
          failure = held;
        } catch (Throwable e) { failure = e; }
        // Observers and future completions can run arbitrary caller code. Store ordering remains
        // on the queue, but none of those callbacks inherits the cross-process file lock.
        if (observedStoreId != null) replicaPublications.header(observedStoreId);
        pendingPublications.forEach(replicaPublications::live);
        if (inventory) for (var publication : publications) published(publication.getKey(), publication.getValue());
        else for (var publication : publications) publishCatalogCapacity(publication.getKey(), publication.getValue());
        if (failure == null && allowMirror && inventory) try { publishInventory(); } catch (Throwable e) { failure = e; }
        if (failure == null) result.complete(value); else result.completeExceptionally(failure);
      }
    }
    new Attempt().submit();
    return result;
  }

  StoreQueue.Metrics queueMetrics() { return queue.metrics(); }
  /** Public health reads one store's published turn timing without joining the queue. */
  public long takeLongestTurnUs() { return queue.takeLongestTurnUs(); }

  public CaptureStore captures(java.time.Clock clock) { return new CaptureStore(this, logManager, clock); }

  /** Reading a capture is the slow part; one thread does it for every store, off the store queues. */
  private static final ExecutorService CLOCK_WORKER = Executors.newSingleThreadExecutor(task -> {
    var thread = new Thread(task, "store-clock-records"); thread.setDaemon(true); return thread;
  });
  private final Object clocksLock = new Object();
  private CompletableFuture<Integer> clocksRunning, clocksQueued;
  /** A capture whose clock facts could not be read is not read again, for that hash, in this process. */
  private final Map<String, String> unreadableClocks = new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * Give every closed capture a current clock record, and drop records whose capture is gone. A
   * large capture takes seconds to read, so reading happens off the store queue, and each write
   * takes the queue only to confirm its capture is unchanged. Requests made during a pass share one
   * pass after it, so none is answered by a pass that began before it. A mirror holds its origin's
   * records and writes none. Completes with the number of records written.
   */
  public CompletableFuture<Integer> keepClocks() {
    synchronized (clocksLock) {
      if (clocksRunning == null) { clocksRunning = new CompletableFuture<>(); startClockPass(clocksRunning); return clocksRunning; }
      if (clocksQueued == null) clocksQueued = new CompletableFuture<>();
      return clocksQueued;
    }
  }
  /** Completes once the clock record passes running or queued at this call are done, without asking for one. */
  public CompletableFuture<Void> clocksSettled() {
    synchronized (clocksLock) {
      var last = clocksQueued != null ? clocksQueued : clocksRunning;
      return last == null ? CompletableFuture.completedFuture(null) : last.handle((written, error) -> null);
    }
  }
  private void startClockPass(CompletableFuture<Integer> result) {
    CLOCK_WORKER.execute(() -> {
      int written = 0; Throwable failure = null;
      try { written = keepClocksPass(); }
      catch (Exception e) { failure = e; }
      catch (OutOfMemoryError e) { failure = new IOException("Not enough heap to read a capture's clock facts", e); }
      CompletableFuture<Integer> next;
      synchronized (clocksLock) { next = clocksQueued; clocksQueued = null; clocksRunning = next; }
      if (next != null) startClockPass(next);
      if (failure == null) result.complete(written); else result.completeExceptionally(failure);
    });
  }
  private int keepClocksPass() throws IOException {
    if (!StoreCatalog.isStore(root)) return 0;
    var catalog = StoreCatalog.readManaged(root, security);
    if (catalog.header().mirror()) return 0;
    var io = new StoreFiles(root, security);
    int written = 0;
    for (var held : catalog.sessions()) {
      var session = held.session();
      for (var file : session.files()) {
        if (file.provenance() == null || !file.provenance().captured() || unreadableClocks.containsKey(file.sha256())) continue;
        var recording = CaptureRecording.of(file.provenance(), catalog.header().id()).orElse(null);
        var record = session.clocks().stream().filter(r -> r.capture().equals(file.path()) && r.captureSha256().equals(file.sha256())).findFirst();
        // An entry that does not name its capture's recording predates the field and is written again.
        if (record.isPresent() && present(io, held.path(), record.get()) && Objects.equals(record.get().recording(), recording)) continue;
        ClockRecords.Facts facts;
        try {
          facts = clockFacts(io.resolve(held.path(), file.path()), file, recording);
        } catch (IOException e) {
          unreadableClocks.put(file.sha256(), String.valueOf(e.getMessage()));
          LoggerFactory.getLogger(LogStore.class).warn("No clock record for {}: {}", file.path(), e.getMessage());
          continue;
        }
        if (awaitClocks(writeClock(held.path(), session.id(), file, facts))) written++;
      }
      if (session.clocks().stream().anyMatch(r -> !current(session, r))) awaitClocks(pruneClocks(held.path(), session.id()));
    }
    return written;
  }
  /** Every finite uptime sample in the capture, read through the decoder the system-log search uses. */
  private ClockRecords.Facts clockFacts(Path file, LogFile entry, CaptureRecording recording) throws IOException {
    try (var use = logManager.acquire(file.toString())) {
      var samples = use.log().values().get(ClockRecords.UPTIME_ENTRY);
      var pairs = new ArrayList<double[]>();
      if (samples != null) for (var sample : samples) {
        if (sample.value() instanceof Number uptime && Double.isFinite(uptime.doubleValue()) && Double.isFinite(sample.timestamp())) {
          pairs.add(new double[] {uptime.doubleValue(), sample.timestamp()});
        }
      }
      return new ClockRecords.Facts(ClockRecords.FORMAT_VERSION, entry.path(), entry.sha256(), entry.sizeBytes(),
          recording == null ? null : recording.collectingStoreId(), recording == null ? null : recording.startedAt(),
          entry.minTimestampSec(), entry.maxTimestampSec(), pairs);
    }
  }
  private static boolean current(Session session, StoreManifest.ClockRecord record) {
    return session.files().stream().anyMatch(f -> f.path().equals(record.capture()) && f.sha256().equals(record.captureSha256())
        && f.provenance() != null && f.provenance().captured());
  }
  /** Readers check a record's hash; the keeper only needs to know it is there, whole. */
  private static boolean present(StoreFiles io, Path session, StoreManifest.ClockRecord record) {
    try { var path = ClockRecords.resolve(io, session, record); return Files.isRegularFile(path) && Files.size(path) == record.sizeBytes(); }
    catch (IOException e) { return false; }
  }
  private CompletableFuture<Boolean> writeClock(Path directory, String sessionId, LogFile file, ClockRecords.Facts facts) {
    return mutate(io -> {
      var manifest = io.check(directory.resolve("session.json"));
      if (!Files.exists(manifest)) return false; // Moved while its capture was read: the next pass finds it.
      var session = io.read(manifest, Session.class);
      // A capture resumed or replaced while its facts were read is read again on the next pass.
      if (!session.id().equals(sessionId) || session.files().stream().noneMatch(f -> f.path().equals(file.path())
          && f.sha256().equals(file.sha256()) && f.provenance() != null && f.provenance().captured())) return false;
      String relative = ClockRecords.path(file.path());
      var target = io.check(io.resolve(directory, relative));
      Files.createDirectories(target.getParent());
      io.write(target, facts);
      var recording = facts.collectingStoreId() == null || facts.recordingStartedAt() == null ? null
          : new CaptureRecording(facts.collectingStoreId(), facts.recordingStartedAt());
      var record = new StoreManifest.ClockRecord(relative, StoreFiles.hash(target), Files.size(target), file.path(), file.sha256(), recording);
      var clocks = new ArrayList<StoreManifest.ClockRecord>();
      for (var old : session.clocks()) if (!old.capture().equals(file.path())) clocks.add(old);
      clocks.add(record);
      io.write(manifest, session.withClocks(clocks)); return true;
    }, false);
  }
  private CompletableFuture<Boolean> pruneClocks(Path directory, String sessionId) {
    return mutate(io -> {
      var manifest = io.check(directory.resolve("session.json"));
      if (!Files.exists(manifest)) return false;
      var session = io.read(manifest, Session.class);
      if (!session.id().equals(sessionId)) return false;
      var kept = new ArrayList<StoreManifest.ClockRecord>();
      for (var record : session.clocks()) {
        if (current(session, record)) { kept.add(record); continue; }
        // A record stays listed until its file is gone, so a failed delete never leaves a stray.
        try { Files.deleteIfExists(ClockRecords.resolve(io, directory, record)); }
        catch (IOException e) {
          LoggerFactory.getLogger(LogStore.class).warn("Stale clock record kept for now: {}", e.getMessage()); kept.add(record);
        }
      }
      if (kept.size() == session.clocks().size()) return false;
      io.write(manifest, session.withClocks(kept)); return true;
    }, false);
  }
  private static <T> T awaitClocks(CompletableFuture<T> future) throws IOException {
    try { return future.get(); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted keeping clock records", e); }
    catch (java.util.concurrent.ExecutionException e) { throw new IOException("Clock record write failed: " + e.getCause().getMessage(), e.getCause()); }
  }

  public PullStore pulls(org.triplehelix.wpilogmcp.capture.context.DeviceIdentity identity, java.time.Clock clock) {
    return new PullStore(this, logManager, security, identity, clock);
  }

  public SystemPullStore systemPulls(org.triplehelix.wpilogmcp.capture.context.DeviceIdentity identity, java.time.Clock clock) {
    return new SystemPullStore(this, security, identity, clock);
  }

  public record SshHosts(java.util.Map<String, String> fingerprints) {}
  /** Non-robot followers also pin first contact; this file contains fingerprints, never credentials. */
  public CompletableFuture<Void> recordHostKey(String address, String fingerprint) {
    return captureAsync(io -> {
      var path = root.resolve("ssh-hosts.json");
      var pins = new java.util.TreeMap<String, String>();
      if (java.nio.file.Files.exists(path)) pins.putAll(io.read(path, SshHosts.class).fingerprints());
      pins.put(address, fingerprint); io.write(path, new SshHosts(pins)); return null;
    });
  }

  /** Pin lookup is queued like every identity read/modify/write; callers are background pullers. */
  public CompletableFuture<String> hostKey(String address) {
    return captureAsync(io -> {
      String serial = io.read(root.resolve("store.json"), Header.class).addresses().get(address);
      if (serial == null) {
        var pins = root.resolve("ssh-hosts.json");
        return java.nio.file.Files.exists(pins) ? io.read(pins, SshHosts.class).fingerprints().get(address) : null;
      }
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
    return sync(url, rateBytes, progress, clock, source, (boundary, staged, destination, manifest) -> {});
  }

  CompletableFuture<StoreSync.Result> sync(String url, long rateBytes, Consumer<StoreSync.Progress> progress,
      java.time.Clock clock, StoreSync.Source source, StoreSync.Probe probe) {
    if (rateBytes < 0) throw new IllegalArgumentException("rate_bytes must be nonnegative (0 means unlimited)");
    if (!syncing.compareAndSet(false, true)) return CompletableFuture.failedFuture(new IOException("A sync is already running for this store"));
    pending.incrementAndGet();
    var result = new CompletableFuture<StoreSync.Result>();
    // Canceling a response cannot retire the journal owner or an in-flight disk reservation.
    var completion = new CompletableFuture<StoreSync.Result>();
    importJobs.add(completion);
    completion.whenComplete((value, failure) -> {
      importJobs.remove(completion); syncing.set(false); pending.decrementAndGet();
      if (failure == null) result.complete(value); else result.completeExceptionally(failure);
    });
    try {
      staging.execute(new StagedJob(completion, () -> {
        try {
          ensureImporting();
          var header = StoreCatalog.isStore(root)
              ? new StoreFiles(root, security).read(root.resolve("store.json"), Header.class)
              : importTurn(io -> io.read(root.resolve("store.json"), Header.class));
          // The daemon job remains queued until its first short owner turn actually starts.
          // Mirrors are refused without mutating them; later turns recheck the admitted role.
          if (!header.mirror()) header = importTurn(io -> io.read(root.resolve("store.json"), Header.class));
          completion.complete(new StoreSync(this, security, logManager, clock, source, progress, probe)
              .run(url, rateBytes, header));
        } catch (Exception failure) { completion.completeExceptionally(failure); }
        catch (OutOfMemoryError failure) {
          completion.completeExceptionally(new IOException("Not enough heap to prepare peer sync", failure));
        }
      }));
    } catch (RuntimeException closed) {
      completion.completeExceptionally(new IOException("Store sync staging is closed", closed));
    }
    return result;
  }

  public CompletableFuture<MirrorSync.Result> mirror(org.triplehelix.wpilogmcp.config.MirrorConfig config,
      Consumer<MirrorSync.Progress> progress) {
    return mirror(config, progress, java.time.Clock.systemUTC(), StoreSync::http);
  }

  CompletableFuture<MirrorSync.Result> mirror(org.triplehelix.wpilogmcp.config.MirrorConfig config,
      Consumer<MirrorSync.Progress> progress, java.time.Clock clock, StoreSync.Source source) {
    return mirror(config, progress, clock, source, master -> java.util.List.of());
  }
  CompletableFuture<MirrorSync.Result> mirror(org.triplehelix.wpilogmcp.config.MirrorConfig config,
      Consumer<MirrorSync.Progress> progress, java.time.Clock clock, StoreSync.Source source,
      java.util.function.Function<String, java.util.List<String>> discovered) {
    if (!root.equals(config.folder().toAbsolutePath().normalize()) && !sameRealFolder(root, config.folder())) return CompletableFuture.failedFuture(new IOException("mirror.folder differs from this store"));
    if (!syncing.compareAndSet(false, true)) return CompletableFuture.failedFuture(new IOException("A sync is already running for this store"));
    pending.incrementAndGet();
    try {
      long replicaEpoch = replicaTransfers.reconciliationEpoch(config);
      return mutate(io -> new MirrorSync(root, io, security, logManager, clock, progress).run(config, source), true)
          .thenCompose(result -> result.state().equals("ready")
              ? ReplicaReconciler.run(this, config, clock, progress, replicaEpoch, discovered) : CompletableFuture.completedFuture(result))
          .whenComplete((result, error) -> { syncing.set(false); pending.decrementAndGet(); });
    } catch (RuntimeException e) { syncing.set(false); pending.decrementAndGet(); throw e; }
  }

  private static boolean sameRealFolder(Path left, Path right) {
    try { return left.toRealPath().equals(right.toRealPath()); }
    catch (IOException e) { return false; }
  }

  /** Pins are acquisition inclusions, not permission to exceed capacity or change the master. */
  public CompletableFuture<StoreManifest.MirrorOrigin> pin(String sessionId, boolean pinned) {
    StoreFiles.component(sessionId);
    return mutate(io -> {
      var header = pinnedHeader(io.read(root.resolve("store.json"), Header.class), sessionId, pinned);
      io.write(root.resolve("store.json"), header); return header.origin();
    }, true);
  }

  /** Shared value transformation; the replica owner reserves the durable replacement separately. */
  static Header pinnedHeader(Header header, String sessionId, boolean pinned) throws IOException {
    var origin = header.origin();
    if (!header.mirror() || origin == null) throw new IOException("pin_session requires a mirror");
    var ids = new java.util.TreeSet<>(origin.pinnedSessions());
    if (pinned) ids.add(sessionId); else ids.remove(sessionId);
    var updated = new MirrorOrigin(origin.storeId(), origin.url(), origin.lastSync(), List.copyOf(ids), origin.sessions());
    header = header.withOrigin(updated);
    if (header.replicas() != null) {
      var catalog = header.replicas();
      header = header.withReplicas(new ReplicaCatalog(catalog.schemaVersion(), catalog.epoch(), Math.addExact(catalog.revision(), 1),
          catalog.files(), List.copyOf(ids), catalog.exclusions()));
    }
    return header;
  }

  /** A second caller queues behind the first, including its inspection and identity changes. */
  public CompletableFuture<Result> importPaths(Request request, Consumer<Progress> progress) {
    return importPrepared(request, progress, r -> r, result -> {});
  }

  public StoreUpload receiveUpload(String name, long length, String hash, java.io.InputStream input) throws IOException {
    return StoreUpload.receive(root, security, name, length, hash, input, this::reserveDiskCopy);
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
    importJobs.add(result);
    result.whenComplete((value, failure) -> { importJobs.remove(result); pending.decrementAndGet(); });
    try {
      staging.execute(new StagedJob(result, () -> {
        try {
          importTurn(io -> {
            if (io.read(root.resolve("store.json"), Header.class).mirror()) {
              throw new IOException("A mirror is owned by its synchronization; imports and assignments are refused");
            }
            Files.createDirectories(io.check(root.resolve("inbox")));
            return null;
          });
          notify(progress, new Progress("starting", root, 0, request.paths().size()));
          var admitted = importTurn(io -> preparation.prepare(request));
          var imported = run(admitted, progress, assignment, preparation);
          importTurn(io -> { beforeUnlock.accept(imported); return null; });
          publishInventory();
          result.complete(imported);
        } catch (Exception e) {
          result.completeExceptionally(e);
        } catch (OutOfMemoryError e) {
          result.completeExceptionally(new IOException("Not enough heap to inspect this import", e));
        }
      }));
    } catch (RuntimeException e) {
      result.completeExceptionally(new IOException("Store import staging is closed", e));
    }
    return result;
  }

  /** The staging worker may wait for a short publication turn; the owner never waits for it. */
  private <T> T importTurn(CaptureOperation<T> operation) throws IOException {
    return stagingTurn(operation, false);
  }

  /** Prepared peer publications use the same ownership and shutdown rule as imports. */
  <T> T syncTurn(CaptureOperation<T> operation) throws IOException {
    return stagingTurn(operation, false);
  }

  private <T> T stagingTurn(CaptureOperation<T> operation, boolean allowMirror) throws IOException {
    ensureImporting();
    boolean interrupted = false;
    var turn = mutate(io -> { ensureImporting(); return operation.run(io); }, allowMirror);
    try {
      while (true) {
        try { return turn.get(); }
        catch (InterruptedException stop) {
          // The caller can be refused promptly, but this worker retains its staged bytes until
          // an already-running owner turn has finished using them.
          interrupted = true;
        } catch (java.util.concurrent.ExecutionException failed) {
          if (failed.getCause() instanceof IOException io) throw io;
          throw new IOException("Import publication failed: " + failed.getCause().getMessage(), failed.getCause());
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private void ensureImporting() throws IOException {
    if (importsStopped.get() || Thread.currentThread().isInterrupted())
      throw new IOException("Import staging stopped before placement; source retained");
  }

  /** An OS read may unwind later, but it cannot publish after shutdown stops staging admission. */
  void stopImports() {
    if (!importsStopped.compareAndSet(false, true)) return;
    for (var job : staging.shutdownNow()) if (job instanceof StagedJob staged) staged.abandon();
    CompletableFuture<Void> queued;
    synchronized (publicationRecoveryLock) { queued = nextPublicationRecovery; nextPublicationRecovery = null; }
    if (queued != null) queued.completeExceptionally(new IOException("Store closed before publication recovery"));
  }

  @Override
  public void close() {
    stopImports();
    replicaTransfers.close();
    queue.shutdown();
  }

  /** An explicit completion barrier for offline callers and tests, including preparation. */
  public void awaitImports() {
    if (!staging.isShutdown()) {
      var staged = new CompletableFuture<Void>();
      try { staging.execute(() -> staged.complete(null)); }
      catch (java.util.concurrent.RejectedExecutionException closed) { staged.complete(null); }
      awaitBarrier(staged, 30);
    }
    awaitMutations(importsStopped.get() ? 1 : 30);
  }

  /** A transport does not own shared staging; closing it drains only admitted owner turns. */
  public void awaitMutations() { awaitMutations(1); }

  private void awaitMutations(long seconds) {
    var drained = new CompletableFuture<Void>();
    try { queue.execute(() -> drained.complete(null)); }
    catch (java.util.concurrent.RejectedExecutionException closed) { drained.complete(null); }
    awaitBarrier(drained, seconds);
  }

  private static void awaitBarrier(CompletableFuture<Void> barrier, long seconds) {
    try { barrier.get(seconds, java.util.concurrent.TimeUnit.SECONDS); }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failed) {
      LoggerFactory.getLogger(LogStore.class).warn("Store barrier did not finish within {} seconds", seconds);
    }
  }

  private Result run(Request request, Consumer<Progress> progress, boolean assignment) throws IOException {
    return run(request, progress, assignment, candidate -> candidate);
  }

  private Result run(Request request, Consumer<Progress> progress, boolean assignment, Preparation preparation) throws IOException {
    if (request.statedRobot() != null) StoreFiles.robotName(request.statedRobot());
    var io = new StoreFiles(root, security, this::published);
    ImportStage.recover(io);
    var outcomes = new ArrayList<Outcome>();
    var sameRobots = new ArrayList<SameRobot>();
    var sources = expand(request.paths(), outcomes);
    var catalog = readImportCatalog();
    var migrated = migrateUnassigned(io, catalog);
    sources = sources.stream().map(path -> migrated.getOrDefault(path, path)).toList();
    var known = new HashMap<String, Path>();
    catalog.files.values().forEach(f -> known.put(f.file().sha256(), f.path()));
    var inspected = new ArrayList<ImportInspection>();
    var batchHashes = new HashSet<String>();
    var repeats = new LinkedHashMap<Path, String>();
    var otherStores = new HashMap<Path, Set<Path>>();
    var parser = new RevLogParser(new DbcLoader().load(null));
    int completed = 0;
    for (var source : sources) {
      ensureImporting();
      notify(progress, new Progress("inspecting", source, completed++, sources.size()));
      try (var sourceLease = LogFileAccess.read(source)) {
        ensureImporting();
        security.validate(source);
        StoreFiles.component(source.getFileName().toString());
        if (request.move()) refuseManagedMove(source, otherStores);
        // Reject an unmappable WPILOG before even hashing gigabytes that cannot be imported.
        if (Files.size(source) > org.triplehelix.wpilogmcp.log.MappedLogBytes.MAX_FILE_BYTES) {
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
          var heldPath = known.get(hash);
          FileSnapshot heldSnapshot;
          try (var heldLease = LogFileAccess.read(known.get(hash))) {
            heldSnapshot = FileSnapshot.of(heldPath);
            if (!hash.equals(StoreFiles.hash(known.get(hash)))) {
              throw new IOException("Stored content no longer matches its manifest: " + known.get(hash));
            }
          }
          var checked = heldSnapshot;
          preparedImportTurn(catalog, owned -> {
            if (checked == null || !checked.sameAs(FileSnapshot.of(heldPath)))
              throw new IOException("Stored duplicate changed after verification: " + heldPath);
            var held = catalog.files.get(hash);
            if (held == null || !held.path().equals(heldPath))
              throw new IOException("Stored duplicate receipt changed after verification: " + heldPath);
            return null;
          });
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
    refreshImportCatalog(catalog);
    for (var input : inspected) {
      if (!input.kind().equals("wpilog")) continue;
      robots.put(input.path(), prepareRobot(catalog, input, request.statedRobot(), sameRobots));
    }
    // A serial promotion updates the catalog; earlier files in this batch use that identity too.
    var currentRobots = catalog.robots.values();
    robots.replaceAll((path, robot) -> robot == null ? null : currentRobots.stream()
        .filter(r -> r.id().equals(robot.id()) || robot.serialNumber() == null
            && Objects.equals(r.name(), robot.name()))
        .findFirst().orElse(robot));
    catalog.inputs = List.copyOf(inspected);
    catalog.selectedRobots = new HashMap<>(robots);
    catalog.statedRobot = request.statedRobot();
    catalog.proof = importProof(catalog, catalog.inputs, catalog.selectedRobots, catalog.statedRobot);
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
      place(io, catalog, group, input, robots.get(input.path()), pairs, request.move(), parser, outcomes, progress, preparation);
      pending.removeAll(group);
      notify(progress, new Progress("placing", input.path(), inspected.size() - pending.size(), inspected.size()));
    }
    for (var input : pending) {
      var pair = pairs.get(input.path());
      if (pair == null) {
        place(io, catalog, List.of(input), input, null, pairs, request.move(), parser, outcomes, progress, preparation);
      } else {
        var stored = catalog.files.values().stream()
            .filter(f -> f.file().sha256().equals(pair.wpilog().hash()) && f.session() != null)
            .findFirst().orElseThrow();
        placeGroup(io, catalog, List.of(input), stored.manifestPath(), stored.robot(), stored.session(), pairs,
            request.move(), parser, outcomes, progress, preparation);
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

  private Robot prepareRobot(ImportCatalog catalog, ImportInspection input, String stated,
      List<SameRobot> sameRobots) throws IOException {
    refreshImportCatalog(catalog);
    String serial = input.metadata().serialNumber();
    var previous = stated == null ? null : catalog.robots.values().stream()
        .filter(robot -> robot.id().equals(stated) || stated.equals(robot.name())).findFirst().orElse(null);
    Path moving = serial != null && previous != null && previous.serialNumber() == null
        && !Files.exists(root.resolve("robots").resolve(serial))
        ? root.resolve("robots").resolve(previous.id()) : null;
    try (var lease = moving == null ? (LogFileAccess.Lease) () -> {} : releaseForMove(List.of(moving))) {
      return preparedImportTurn(catalog, io -> {
        var robot = robotFor(io, catalog, input, stated, sameRobots);
        updateImportStamps(catalog);
        return robot;
      });
    }
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
      Files.move(old, target);
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
        if (sameBootCorrelation(sync)) {
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

  private static boolean sameBootCorrelation(SyncResult sync) {
    return sync.method() == SyncMethod.CROSS_CORRELATION && sync.strongPairCount() > 0
        && sync.offsetMicros() >= -PullStore.MAX_MATCH_OFFSET_US
        && sync.offsetMicros() <= PullStore.MAX_MATCH_OFFSET_US;
  }

  /** Follow recorded references only; new tolerances must not drift away from the boot clock. */
  private static long anchorOffset(StoredFile anchor) throws IOException {
    long offset = 0;
    var file = anchor.file(); var visited = new HashSet<String>();
    while (file.matching() != null) {
      if (!visited.add(file.path())) throw new IOException("Cycle in recorded anchor alignment: " + anchor.manifestPath());
      var evidence = StoreCatalog.recordedAlignment(new StoredFile(anchor.path(), anchor.manifestPath(),
          anchor.robot(), anchor.session(), file));
      if (!evidence.isSuccessful()) throw new IOException(evidence.explanation());
      if (!"by_correlation".equals(file.matching().method())) throw new IOException("Anchor has no recorded data proof");
      try { offset = Math.addExact(offset, file.matching().offsetMicros()); }
      catch (ArithmeticException e) { throw new IOException("Recorded anchor offset overflows", e); }
      if (offset < -PullStore.MAX_MATCH_OFFSET_US || offset > PullStore.MAX_MATCH_OFFSET_US) {
        throw new IOException("Recorded anchor exceeds the " + PullStore.MAX_MATCH_OFFSET_US + " us automatic matching limit");
      }
      var reference = file.matching().wpilogSha256();
      file = anchor.session().files().stream().filter(f -> Objects.equals(reference, f.sha256())).findFirst().orElse(null);
      if (file == null) break; // A peer may have supplied the proven file without its original capture.
    }
    return offset;
  }

  /**
   * Calendar overlap only nominates an anchored boot. Reusing its record requires the same
   * strong, unique, near-zero proof as a pull; a failed proof must not fall back to the clock.
   * The per-import catalog includes open captures and is updated after each placement.
   */
  private ImportMatch matchImport(StoreFiles io, ImportCatalog catalog, ImportInspection input,
      Robot robot, Consumer<Progress> progress) throws IOException {
    var anchors = new ArrayList<StoredFile>();
    for (var entry : catalog.sessions.entrySet()) {
      var manifest = entry.getKey(); var session = entry.getValue();
      var owner = catalog.robots.get(root.relativize(manifest).getName(1).toString());
      boolean sameRobot = owner != null && (owner.id().equals(robot.id())
          || robot.serialNumber() != null && robot.serialNumber().equals(owner.serialNumber()));
      if (!sameRobot || !input.nearClock(Instant.parse(session.startedAt()),
          Instant.parse(session.endedAt()))) continue;
      for (var file : importAnchors(session)) anchors.add(new StoredFile(io.resolve(manifest.getParent(), file.path()),
          manifest, owner, session, file));
    }
    if (anchors.isEmpty()) return new ImportMatch(null, null, "no capture or data-matched anchor", false);
    var matches = new LinkedHashMap<Path, Matching>();
    var reasons = new TreeSet<String>();
    var synchronizer = new LogSynchronizer();
    try (var reader = new ScopedLogReader(input.path());
         var imported = new LazyParsedLog(input.path().toString(), reader.reader(), 32L * 1024 * 1024)) {
      int completed = 0;
      for (var anchor : anchors) {
        notify(progress, new Progress("correlating", anchor.path(), completed++, anchors.size()));
        try (var use = logManager.acquire(anchor.path().toString())) {
          long anchorUs = anchorOffset(anchor);
          var metadata = LogMetadata.read(use.log());
          if (robot.serialNumber() != null && metadata.serialNumber() != null
              && !robot.serialNumber().equals(metadata.serialNumber())) {
            reasons.add("known robot serials differ"); continue;
          }
          var sync = synchronizer.synchronize(use.log(), imported);
          if (sync.method() != SyncMethod.CROSS_CORRELATION || sync.strongPairCount() == 0) {
            reasons.add("no strong data correlation"); continue;
          }
          if (!sameBootCorrelation(sync) || sync.offsetMicros() + anchorUs < -PullStore.MAX_MATCH_OFFSET_US
              || sync.offsetMicros() + anchorUs > PullStore.MAX_MATCH_OFFSET_US) {
            reasons.add("measured offset exceeds the " + PullStore.MAX_MATCH_OFFSET_US + " us automatic matching limit"); continue;
          }
          String basis = input.metadata().serialNumber() != null && metadata.serialNumber() != null
              ? "serial_and_data" : "data_alone";
          matches.putIfAbsent(anchor.manifestPath(), new Matching("by_correlation", anchor.file().sha256(),
              sync.offsetMicros(), sync.confidence(), sync.driftRateNanosPerSec(), sync.referenceTimeSec(), basis, sync,
              CaptureRecording.of(anchor.file().provenance(), catalog.header.id()).orElse(null)));
        } catch (IOException e) { reasons.add("unreadable anchor " + anchor.path() + ": " + e.getMessage()); }
      }
    }
    if (matches.size() == 1) {
      var match = matches.entrySet().iterator().next();
      return new ImportMatch(match.getKey(), match.getValue(), null, true);
    }
    return new ImportMatch(null, null, matches.isEmpty() ? String.join("; ", reasons)
        : "ambiguous data correlation with multiple sessions", true);
  }

  private void place(StoreFiles io, ImportCatalog catalog, List<ImportInspection> inputs, ImportInspection primary,
      Robot robot, Map<Path, Pair> pairs, boolean move, RevLogParser parser,
      List<Outcome> outcomes, Consumer<Progress> progress, Preparation preparation) throws IOException {
    refreshImportCatalog(catalog);
    if (robot == null || primary.start() == null) {
      if (inputs.stream().anyMatch(input -> catalog.assigning.containsKey(input.hash()))) {
        for (var input : inputs) outcomes.add(new Outcome(input.path(), "refused", input.path(),
            NO_REV_ASSIGNMENT));
        return;
      }
      for (var input : inputs) {
        var directory = root.resolve("unassigned").resolve(input.hash().substring(0, 16));
        placeGroup(io, catalog, List.of(input), directory.resolve("import.json"), null, null, pairs,
            move, parser, outcomes, progress, preparation);
      }
      return;
    }
    var decision = matchImport(io, catalog, primary, robot, progress);
    if (decision.manifest() != null) {
      var owner = catalog.robots.get(root.relativize(decision.manifest()).getName(1).toString());
      placeGroup(io, catalog, inputs, decision.manifest(), owner, catalog.sessions.get(decision.manifest()),
          pairs, move, parser, outcomes, progress, decision, preparation);
      return;
    }
    // Keep the old calendar-only rule only where no nominated boot can supply data proof.
    var overlap = catalog.sessions.entrySet().stream()
        .filter(s -> !decision.hasAnchors())
        .filter(s -> s.getKey().startsWith(root.resolve("robots").resolve(robot.id())))
        .filter(s -> !primary.end().isBefore(Instant.parse(s.getValue().startedAt()))
            && !primary.start().isAfter(Instant.parse(s.getValue().endedAt())))
        .findFirst();
    if (overlap.isPresent()) {
      var found = overlap.get();
      placeGroup(io, catalog, inputs, found.getKey(), robot, found.getValue(), pairs, move, parser, outcomes, progress,
          new ImportMatch(found.getKey(), null, decision.reason(), false), preparation);
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
    placeGroup(io, catalog, inputs, directory.resolve("session.json"), robot, session, pairs, move, parser, outcomes, progress,
        decision.hasAnchors() ? decision : null, preparation);
  }

  private void placeGroup(StoreFiles io, ImportCatalog catalog, List<ImportInspection> inputs, Path manifest,
      Robot robot, Session session, Map<Path, Pair> pairs, boolean move, RevLogParser parser,
      List<Outcome> outcomes, Consumer<Progress> progress, Preparation preparation) throws IOException {
    placeGroup(io, catalog, inputs, manifest, robot, session, pairs, move, parser, outcomes, progress, null, preparation);
  }

  private void placeGroup(StoreFiles io, ImportCatalog catalog, List<ImportInspection> inputs, Path manifest,
      Robot robot, Session session, Map<Path, Pair> pairs, boolean move, RevLogParser parser,
      List<Outcome> outcomes, Consumer<Progress> progress, ImportMatch decision, Preparation preparation) throws IOException {
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
      boolean wpilogDecision = decision != null && input.kind().equals("wpilog");
      placements.add(new Placement(input, destination, wpilogDecision ? decision.matching() : matching,
          wpilogDecision && decision.manifest() != null && decision.matching() == null ? "by_time_overlap" : null,
          wpilogDecision ? decision.reason() : null));
    }
    if (placements.isEmpty()) return;
    var prepared = new LinkedHashMap<Placement, PreparedTransfer>();
    var removalReasons = new HashMap<Placement, String>();
    var published = new java.util.concurrent.atomic.AtomicBoolean();
    boolean committed = false;
    try {
      for (int attempt = 0; ; attempt++) {
        stageTransfers(catalog, placements, move, attempt > 0, progress, prepared);
        try (var reservation = move
            ? releaseForMove(placements.stream().map(p -> p.input().path()).toList())
            : (LogFileAccess.Lease) () -> {}) {
          try {
            preparedImportTurn(catalog, owned -> {
              var current = preparation.prepare(new Request(inputs.stream().map(ImportInspection::path).toList(), move,
                  robot == null ? null : robot.name()));
              ensureImporting();
              for (var placement : placements) {
                var input = placement.input();
                if (!current.paths().contains(input.path()))
                  throw new IOException("Source admission changed after staging: " + input.path());
                security.validate(input.path());
                if (!input.snapshot().sameAs(FileSnapshot.of(input.path())))
                  throw new IOException("File changed after staging before placement: " + input.path());
                var transfer = prepared.get(placement);
                if (!transfer.snapshot().sameAs(FileSnapshot.of(transfer.path())))
                  throw new IOException("Staged file changed before placement: " + transfer.path());
              }
              var currentSession = session == null ? null : catalog.sessions.getOrDefault(manifest, session);
              if (session != null && !session.id().equals(currentSession.id()))
                throw new IOException("Selected import session changed after preparation");
              publishPreparedGroup(owned, catalog, inputs, placements, prepared, manifest, robot,
                  currentSession, move, decision, published);
              updateImportStamps(catalog);
              return null;
            });
            committed = true;
            catalog.proof = importProof(catalog, catalog.inputs, catalog.selectedRobots, catalog.statedRobot);
          } catch (AtomicMoveNotSupportedException unsupported) {
            if (attempt != 0) throw unsupported;
            // The owner rolled back earlier renames. Copy/force/hash belongs to staging, never
            // to the fallback branch of a publication turn.
            continue;
          }
          if (move) for (var placement : placements) {
            if (!prepared.get(placement).copied()) continue;
            var input = placement.input();
            try {
              security.validate(input.path());
              if (!input.snapshot().sameAs(FileSnapshot.of(input.path()))
                  || !input.hash().equals(StoreFiles.hash(input.path())))
                throw new IOException("Source changed after inspection: " + input.path());
              mover.delete(input.path());
            } catch (IOException | RuntimeException failure) {
              removalReasons.put(placement, "Stored copy verified and committed; original could not be removed: "
                  + input.path() + ": " + failure.getMessage());
            }
          }
          break;
        }
      }
      for (var placement : placements) {
        String reason = session == null ? "No robot assignment or unique correlated wpilog; awaiting assignment" : null;
        if (removalReasons.containsKey(placement)) reason = reason == null ? removalReasons.get(placement)
            : reason + "; " + removalReasons.get(placement);
        outcomes.add(new Outcome(placement.input().path(), session == null ? "unassigned" : "imported",
            placement.destination(), reason));
      }
    } catch (IOException | RuntimeException failure) {
      if (committed || published.get()) {
        for (var placement : placements) outcomes.add(new Outcome(placement.input().path(),
            session == null ? "unassigned" : "imported", placement.destination(),
            "Stored payload committed; import bookkeeping remains pending and any remaining original is retained: " + failure.getMessage()));
        return;
      }
      for (var placement : placements) {
        var transfer = prepared.get(placement);
        Path retained = transfer != null && transfer.copied() && Files.exists(transfer.path()) ? transfer.path() : null;
        // Capacity refusal never admitted this copy; preserve only other failed-Copy diagnostics.
        if ((move || failure instanceof ReplicaCatalogFiles.CapacityRefused) && retained != null) {
          try { mover.discardCopy(retained); retained = null; }
          catch (IOException cleanup) { failure.addSuppressed(cleanup); }
        }
        outcomes.add(new Outcome(placement.input().path(), "refused", retained, failure.getMessage()));
      }
    } finally {
      // Only this worker owns its temporary bytes. A cancelled OS copy keeps that ownership
      // until it unwinds; the source is never cleanup. Crash leftovers remain unmanaged.
      for (var transfer : prepared.values()) if (transfer.copied()) {
        if (importsStopped.get() && Files.exists(transfer.path())) {
          try { mover.discardCopy(transfer.path()); }
          catch (IOException cleanup) { LoggerFactory.getLogger(LogStore.class).warn("Import staging cleanup remains pending: {}", cleanup.getMessage()); }
        }
        transfer.stage().close();
      }
    }
  }

  private record PreparedTransfer(Path path, FileSnapshot snapshot, boolean copied, ImportStage stage) {}

  private void stageTransfers(ImportCatalog catalog, List<Placement> placements, boolean move,
      boolean copyFallback, Consumer<Progress> progress, Map<Placement, PreparedTransfer> prepared) throws IOException {
    var copies = preparedImportTurn(catalog, io -> {
      var result = new HashSet<Placement>();
      for (var placement : placements) {
        Files.createDirectories(io.check(placement.destination().getParent()));
        if (!move || copyFallback || !mover.sameFileStore(placement.input().path(), placement.destination())) {
          result.add(placement);
          var held = prepared.get(placement);
          if (held == null || !held.copied()) {
            var stage = ImportStage.create(io, placement.destination().getParent());
            prepared.put(placement, new PreparedTransfer(stage.path(placement.input().path().getFileName()),
                placement.input().snapshot(), true, stage));
          }
        }
        if (Files.exists(io.check(placement.destination()), LinkOption.NOFOLLOW_LINKS))
          throw new FileAlreadyExistsException(placement.destination().toString());
      }
      updateImportStamps(catalog, placements.stream().map(p -> p.destination().getParent()).toArray(Path[]::new));
      return result;
    });
    int verified = 0;
    for (var placement : placements) {
      ensureImporting();
      var input = placement.input();
      try (var lease = LogFileAccess.read(input.path())) {
        security.validate(input.path());
        if (!input.snapshot().sameAs(FileSnapshot.of(input.path())))
          throw new IOException("File changed before staging: " + input.path());
        if (!copies.contains(placement)) {
          notify(progress, new Progress("verifying", input.path(), verified++, placements.size()));
          prepared.put(placement, new PreparedTransfer(input.path(), input.snapshot(), false, null));
          continue;
        }
        var held = prepared.get(placement);
        if (Files.exists(held.path()) && held.snapshot().sameAs(FileSnapshot.of(held.path()))) continue;
        var path = held.path();
        try (var reservation = reserveDiskCopy(path.getParent(), input.snapshot().size())) {
          var attributes = mover.copy(input.path(), path, input.snapshot().size());
          if (move) mover.force(path);
          attributes.restore(path);
        }
        notify(progress, new Progress("verifying", path, verified++, placements.size()));
        if (!input.hash().equals(StoreFiles.hash(path))) throw new IOException("Copy hash differs: " + input.path());
        if (!input.snapshot().sameAs(FileSnapshot.of(input.path())))
          throw new IOException("File changed after staging: " + input.path());
        prepared.put(placement, new PreparedTransfer(path, FileSnapshot.of(path), true, held.stage()));
      }
    }
  }

  /** Prepared bytes are renamed together; only metadata work remains under store.lock. */
  private void publishPreparedGroup(StoreFiles io, ImportCatalog catalog, List<ImportInspection> inputs,
      List<Placement> placements, Map<Placement, PreparedTransfer> prepared, Path manifest,
      Robot robot, Session session, boolean move, ImportMatch decision,
      java.util.concurrent.atomic.AtomicBoolean published) throws IOException {
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
          placement.matching(), input.robotFingerprint(), placement.reason(), placement.placementMethod()));
    }
    if (session != null) {
      var start = Instant.parse(session.startedAt());
      var end = Instant.parse(session.endedAt());
      String basis = session.startBasis();
      for (var input : inputs) {
        // Data joined an already-proven boot. Its uncertain source calendar only nominated it.
        if (decision != null && decision.matching() != null) continue;
        // A REV wall clock nominates candidates only; the correlated wpilog owns session time.
        if (!input.kind().equals("wpilog")) continue;
        if (input.start() != null && input.start().isBefore(start)) {
          start = input.start();
          basis = input.startBasis();
        }
        if (input.end() != null && input.end().isAfter(end)) end = input.end();
      }
      session = new Session(session.id(), start.toString(), end.toString(), basis,
          session.event(), session.matchType(), session.matchNumber(), session.teamNumber(), List.copyOf(records), session.openCapture(), session.endReason(), session.deviceIdentity(), session.identityConflicts(), session.conflicts(), session.captureStats(), session.systemLogs(), session.mergedSessionIds(), session.clocks());
    }
    ReplicaMasters.preflight(root, io, manifest, session == null ? records.get(0) : session);
    var transferred = new ArrayList<Placement>();
    try {
      for (var placement : placements) {
        var transfer = prepared.get(placement);
        io.check(placement.destination());
        if (Files.exists(placement.destination(), LinkOption.NOFOLLOW_LINKS))
          throw new FileAlreadyExistsException(placement.destination().toString());
        if (transfer.copied()) Files.move(transfer.path(), placement.destination(), StandardCopyOption.ATOMIC_MOVE);
        else mover.move(transfer.path(), placement.destination(), StandardCopyOption.ATOMIC_MOVE);
        transferred.add(placement);
      }
      if (session == null) io.write(manifest, records.get(0)); else io.write(manifest, session);
      published.set(true);
    } catch (IOException | RuntimeException failure) {
      for (int i = transferred.size() - 1; i >= 0; i--) {
        var placement = transferred.get(i);
        try { Files.move(placement.destination(), prepared.get(placement).path()); }
        catch (IOException restore) { failure.addSuppressed(restore); }
      }
      updateImportStamps(catalog);
      throw failure;
    }
    // From this point, a later bookkeeping failure cannot roll back the durable payload.
    catalog.placed(io, manifest, robot, session, records);
    if (move) {
      var header = io.read(root.resolve("store.json"), Header.class);
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
        Files.delete(io.check(old.manifestPath()));
        rewriteMoves(io, catalog, old.path(), placement.destination());
      }
    }
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
   * Old manifests remain readable. Prepare and verify their payloads on staging, then switch
   * the manifest before removing the old file: a failed migration must keep log bytes.
   */
  private Map<Path, Path> migrateUnassigned(StoreFiles io, ImportCatalog catalog) throws IOException {
    var migrated = new HashMap<Path, Path>();
    for (var stored : List.copyOf(catalog.files.values())) {
      if (stored.session() != null || stored.path().startsWith(stored.manifestPath().getParent().resolve("robot"))) {
        continue;
      }
      var file = stored.file();
      var destination = payloadDestination(io, stored.manifestPath(), stored.path().getFileName(), file.sha256());
      ImportStage stage = null;
      Path staged = null;
      try {
        FileSnapshot source;
        try (var lease = LogFileAccess.read(stored.path())) {
          source = FileSnapshot.of(stored.path());
          if (!file.sha256().equals(StoreFiles.hash(io.check(stored.path()))))
            throw new IOException("Stored content no longer matches its manifest: " + stored.path());
          stage = importTurn(owned -> {
            checkImportCatalog(catalog);
            Files.createDirectories(owned.check(destination.getParent()));
            var prepared = ImportStage.create(owned, destination.getParent());
            try {
              updateImportStamps(catalog, destination.getParent());
              return prepared;
            } catch (IOException | RuntimeException failure) {
              prepared.close();
              throw failure;
            }
          });
          staged = stage.path(stored.path().getFileName());
          try (var reservation = reserveDiskCopy(staged.getParent(), source.size())) {
            mover.copy(stored.path(), staged, source.size()).restore(staged);
          }
          if (!file.sha256().equals(StoreFiles.hash(staged)) || !source.sameAs(FileSnapshot.of(stored.path())))
            throw new IOException("Migration copy changed: " + stored.path());
        }
        var prepared = staged;
        try (var lease = releaseForMove(List.of(stored.path()))) {
          importTurn(owned -> {
            checkImportCatalog(catalog);
            if (!source.sameAs(FileSnapshot.of(stored.path())))
              throw new IOException("Legacy source changed before migration: " + stored.path());
            if (Files.exists(owned.check(destination), LinkOption.NOFOLLOW_LINKS))
              throw new FileAlreadyExistsException(destination.toString());
            Files.move(prepared, owned.check(destination), StandardCopyOption.ATOMIC_MOVE);
            var updated = new LogFile(StoreFiles.relative(stored.manifestPath().getParent(), destination),
                file.sha256(), file.sizeBytes(), file.kind(), file.provenance(), file.verified(),
                file.minTimestampSec(), file.maxTimestampSec(), file.startedAt(), file.endedAt(),
                file.startBasis(), file.truncated(), file.matching(), file.robotFingerprint(), file.matchingReason(), file.placementMethod());
            owned.write(stored.manifestPath(), updated);
            rewriteMoves(owned, catalog, stored.path(), destination);
            catalog.placed(owned, stored.manifestPath(), null, null, List.of(updated));
            Files.delete(owned.check(stored.path()));
            updateImportStamps(catalog);
            return null;
          });
        }
        migrated.put(stored.path(), destination);
      } finally {
        try {
          if (staged != null && Files.exists(staged)) mover.discardCopy(staged);
        } finally {
          if (stage != null) stage.close();
        }
      }
    }
    return migrated;
  }

  private void rewriteMoves(StoreFiles io, ImportCatalog catalog, Path old, Path target)
      throws IOException {
    var header = io.read(root.resolve("store.json"), Header.class);
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
