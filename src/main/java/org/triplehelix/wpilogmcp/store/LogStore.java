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
 * One store's import queue, independent of HTTP and the extension. Inspection and correlation
 * finish before placement, and a manifest is committed only after every file in a pair loads
 * at its destination. Failed ordinary copies remain strays; failed cross-volume Move copies are
 * removed without changing their originals. A committed payload is never rolled back.
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
   * Arrival order must not decide whether two completed files are paired. This bounded queue
   * turn reuses assignment's exact correlation and placement path, without inventing a robot.
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
      try {
        queue.execute(() -> {
          Reconsideration value = null;
          Throwable failure = null;
          try (var lock = StoreLock.acquire(root, security)) {
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
            pending.decrementAndGet();
            reconsidering.compareAndSet(result, null);
          }
          // Callbacks may enqueue more work: release the store lock before completing them.
          if (failure == null) result.complete(value); else result.completeExceptionally(failure);
        });
      } catch (RuntimeException e) {
        pending.decrementAndGet();
        reconsidering.compareAndSet(result, null);
        result.completeExceptionally(e);
      }
      return result;
    }
  }

  private Reconsideration reconsiderRev(StoreFiles io) throws IOException {
    var catalog = new ImportCatalog(catalogReader.read(root, security));
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
      io.write(directory.resolve(last + ".json"), new RevAttempt(generation));
    }
    io.write(cursorPath, new RevCursor(generation, last));
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
  private final ExecutorService queue;
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
    CopyAttributes copy(Path source, Path destination) throws IOException {
      var attributes = CopyAttributes.read(source);
      // CREATE_NEW establishes ownership: failure cleanup must never delete an existing file.
      boolean created = false;
      try (var input = Files.newInputStream(source)) {
        try (var output = java.nio.channels.FileChannel.open(destination,
            Set.of(java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE),
            attributes.creationAttributes(destination))) {
          created = true;
          byte[] bytes = new byte[64 * 1024];
          int count;
          while ((count = input.read(bytes)) != -1) {
            var buffer = java.nio.ByteBuffer.wrap(bytes, 0, count);
            while (buffer.hasRemaining()) output.write(buffer);
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
    this(root, security, logManager, catalogReader, new Mover());
  }

  LogStore(Path root, SecurityValidator security, LogManager logManager, CatalogReader catalogReader,
      Mover mover) {
    FileNameEncoding.warnIfNeeded();
    this.root = root;
    this.security = security;
    this.logManager = logManager;
    this.catalogReader = catalogReader;
    this.inbox = new StoreInbox(this, security);
    this.mover = mover;
    queue = Executors.newSingleThreadExecutor(task -> {
      var thread = new Thread(task, "store-import");
      thread.setDaemon(true);
      return thread;
    });
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
  private void published(Path path, Object value) {
    logManager.catalogPublished();
    for (var observer : observers) {
      try { observer.manifest(path, value); }
      catch (RuntimeException e) { org.slf4j.LoggerFactory.getLogger(LogStore.class).warn("Session status update failed", e); }
    }
  }
  private void publishInventory() throws IOException {
    if (observers.isEmpty()) return;
    var snapshot = StoreCatalog.readInventory(root, security, (manifest, reason) ->
        LoggerFactory.getLogger(LogStore.class).warn("Skipping invalid session inventory {}: {}", manifest, reason));
    observers.forEach(observer -> observer.inventory(snapshot));
  }
  private volatile String replicaPublicationFailure;
  String replicaPublicationFailure() { return replicaPublicationFailure; }
  public CompletableFuture<Void> refreshStatus() {
    return captureAsync(io -> { recoverPublication(io); publishInventory(); return null; });
  }
  private void recoverPublication(StoreFiles io) throws IOException {
    try { ReplicaMasters.recover(root, io); ReplicaMasters.recoverCaptures(root, io); replicaPublicationFailure = null; }
    catch (IOException | RuntimeException failure) {
      replicaPublicationFailure = "PUBLICATION_PENDING: recorded captures remain local; " + failure.getMessage();
      throw failure;
    }
  }
  /** Explicit recovery has a bounded path budget and never blocks admission of a new capture. */
  public CompletableFuture<Void> recoverMasterPublication() {
    return mutate(io -> { recoverPublication(io); return null; }, false, false);
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
    return mutate(io -> {
      var header = io.read(root.resolve("store.json"), Header.class);
      if (header.replicas() != null) io.recording(reason -> {
        replicaPublicationFailure = reason;
        LoggerFactory.getLogger(LogStore.class).warn("{}", reason);
      });
      return operation.run(io);
    }, false);
  }

  private <T> CompletableFuture<T> mutate(CaptureOperation<T> operation, boolean allowMirror) {
    return mutate(operation, allowMirror, true);
  }

  private <T> CompletableFuture<T> mutate(CaptureOperation<T> operation, boolean allowMirror, boolean inventory) {
    var result = new CompletableFuture<T>();
    try { queue.execute(() -> {
      T value = null; Throwable failure = null;
      var publications = new ArrayList<Map.Entry<Path, Object>>();
      try (var lock = StoreLock.acquire(root, security)) {
        var io = new StoreFiles(root, security, (path, record) -> publications.add(Map.entry(path, record)));
        if (!StoreCatalog.isStore(root)) io.write(root.resolve("store.json"),
            new Header(StoreManifest.FORMAT_VERSION, Instant.now().toString(), UUID.randomUUID().toString(), List.of()));
        var header = io.read(root.resolve("store.json"), Header.class);
        if (!StoreManifest.readable(header.formatVersion())) throw new IOException("Unsupported store format version " + header.formatVersion());
        if (header.mirror() && !allowMirror) throw new IOException("A mirror is written only by its mirror synchronization");
        value = operation.run(io);
      } catch (Throwable e) { failure = e; }
      // Observers and future completions can run arbitrary caller code. Store ordering remains
      // on the queue, but none of those callbacks inherits the cross-process file lock.
      if (inventory) for (var publication : publications) published(publication.getKey(), publication.getValue());
      if (failure == null && allowMirror && inventory) try { publishInventory(); } catch (Throwable e) { failure = e; }
      if (failure == null) result.complete(value); else result.completeExceptionally(failure);
    }); } catch (java.util.concurrent.RejectedExecutionException closed) { result.completeExceptionally(new IOException("Store is closed", closed)); }
    return result;
  }

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
    replicaTransfers.close();
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
      var files = new ArrayList<>(session.files());
      if (session.openCapture() != null) {
        var open = session.openCapture();
        files.add(new LogFile(open.path(), null, open.sizeBytes(), "wpilog", open.provenance(), false,
            open.minTimestampSec(), open.maxTimestampSec(), session.startedAt(), session.endedAt(),
            session.startBasis(), false, null));
      }
      var captures = files.stream().filter(f -> f.kind().equals("wpilog") && f.provenance().kind().equals("captured")).toList();
      var candidates = captures.isEmpty() ? files.stream().filter(f -> f.kind().equals("wpilog")
          && f.matching() != null && "by_correlation".equals(f.matching().method())).toList() : captures;
      for (var file : candidates) anchors.add(new StoredFile(io.resolve(manifest.getParent(), file.path()),
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
      List<Outcome> outcomes, Consumer<Progress> progress) throws IOException {
    if (robot == null || primary.start() == null) {
      if (inputs.stream().anyMatch(input -> catalog.assigning.containsKey(input.hash()))) {
        for (var input : inputs) outcomes.add(new Outcome(input.path(), "refused", input.path(),
            NO_REV_ASSIGNMENT));
        return;
      }
      for (var input : inputs) {
        var directory = root.resolve("unassigned").resolve(input.hash().substring(0, 16));
        placeGroup(io, catalog, List.of(input), directory.resolve("import.json"), null, null, pairs,
            move, parser, outcomes, progress);
      }
      return;
    }
    var decision = matchImport(io, catalog, primary, robot, progress);
    if (decision.manifest() != null) {
      var owner = catalog.robots.get(root.relativize(decision.manifest()).getName(1).toString());
      placeGroup(io, catalog, inputs, decision.manifest(), owner, catalog.sessions.get(decision.manifest()),
          pairs, move, parser, outcomes, progress, decision);
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
          new ImportMatch(found.getKey(), null, decision.reason(), false));
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
        decision.hasAnchors() ? decision : null);
  }

  private void placeGroup(StoreFiles io, ImportCatalog catalog, List<ImportInspection> inputs, Path manifest,
      Robot robot, Session session, Map<Path, Pair> pairs, boolean move, RevLogParser parser,
      List<Outcome> outcomes, Consumer<Progress> progress) throws IOException {
    placeGroup(io, catalog, inputs, manifest, robot, session, pairs, move, parser, outcomes, progress, null);
  }

  private void placeGroup(StoreFiles io, ImportCatalog catalog, List<ImportInspection> inputs, Path manifest,
      Robot robot, Session session, Map<Path, Pair> pairs, boolean move, RevLogParser parser,
      List<Outcome> outcomes, Consumer<Progress> progress, ImportMatch decision) throws IOException {
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
    var transferred = new ArrayList<Placement>();
    var copiedMoves = new HashSet<Placement>();
    var removalReasons = new HashMap<Placement, String>();
    LogFileAccess.Lease reservation;
    try {
      reservation = move ? releaseForMove(placements.stream().map(p -> p.input().path()).toList()) : () -> {};
    } catch (IOException | RuntimeException failure) {
      for (var placement : placements) outcomes.add(new Outcome(placement.input().path(), "refused",
          null, failure.getMessage()));
      return;
    }
    try (var lease = reservation) {
      List<LogFile> records;
      try {
        records = new ArrayList<LogFile>(session == null ? List.of() : session.files());
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
          CopyAttributes copiedAttributes = null;
          if (move) {
            boolean renamed = false;
            if (mover.sameFileStore(input.path(), placement.destination())) {
              // ATOMIC_MOVE may replace an occupied target even without REPLACE_EXISTING.
              if (Files.exists(placement.destination(), LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(placement.destination().toString());
              }
              try {
                mover.move(input.path(), placement.destination(), StandardCopyOption.ATOMIC_MOVE);
                renamed = true;
              } catch (AtomicMoveNotSupportedException unsupported) {
                // Never let the JDK implement a non-atomic move as copy-then-delete.
              }
            }
            if (!renamed) {
              copiedAttributes = mover.copy(input.path(), placement.destination());
              copiedMoves.add(placement);
            }
          } else Files.copy(input.path(), placement.destination(), StandardCopyOption.COPY_ATTRIBUTES);
          transferred.add(placement);
          if (copiedAttributes != null) {
            mover.force(placement.destination());
            copiedAttributes.restore(placement.destination());
          }
          notify(progress, new Progress("verifying", placement.destination(), transferred.size() - 1, placements.size()));
          var verified = ImportInspection.read(placement.destination(), parser);
          if (!verified.hash().equals(input.hash())) throw new IOException("Copy hash differs: " + input.path());
        }
        if (session == null) io.write(manifest, records.get(0)); else io.write(manifest, session);
      } catch (IOException | RuntimeException e) {
        // Before commit, discard only Move copies we created; ordinary Copy keeps its strays.
        // A plain reverse move refuses an intervening original instead of replacing it.
        if (move) {
          for (var placement : transferred) {
            try {
              if (copiedMoves.contains(placement)) mover.discardCopy(placement.destination());
              else mover.move(placement.destination(), placement.input().path());
            } catch (IOException restore) {
              e.addSuppressed(restore);
            }
          }
        }
        for (var placement : placements) outcomes.add(new Outcome(placement.input().path(), "refused",
            Files.exists(placement.destination()) ? placement.destination() : null, e.getMessage()));
        return;
      }
      // The payload's manifest is committed. No later failure may roll this placement back.
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
          // Commit the new manifest first; until then the old manifest is the source of truth.
          Files.delete(io.check(old.manifestPath()));
          rewriteMoves(io, catalog, old.path(), placement.destination());
        }
      }
      for (var placement : copiedMoves) {
        var input = placement.input();
        try {
          security.validate(input.path());
          // A writer or another process may have replaced the original during verification.
          // Keep it rather than deleting a version this import never inspected.
          if (!input.snapshot().sameAs(FileSnapshot.of(input.path()))
              || !input.hash().equals(StoreFiles.hash(input.path()))) {
            throw new IOException("Source changed after inspection: " + input.path());
          }
          mover.delete(input.path());
        } catch (IOException | RuntimeException failure) {
          removalReasons.put(placement, "Stored copy verified and committed; original could not be removed: "
              + input.path() + ": " + failure.getMessage());
        }
      }
      for (var placement : placements) {
        String reason = session == null
            ? "No robot assignment or unique correlated wpilog; awaiting assignment" : null;
        if (removalReasons.containsKey(placement)) reason = reason == null ? removalReasons.get(placement)
            : reason + "; " + removalReasons.get(placement);
        outcomes.add(new Outcome(placement.input().path(), session == null ? "unassigned" : "imported",
            placement.destination(), reason));
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
            file.startBasis(), file.truncated(), file.matching(), file.robotFingerprint(), file.matchingReason(), file.placementMethod());
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
