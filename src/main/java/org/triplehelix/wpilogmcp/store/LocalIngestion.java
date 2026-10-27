/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.store.FileStability.Stamp;

/**
 * A person's explicit, machine-local enrollment survives editors and daemon restarts. The
 * poller only nominates settled immutable versions; LogStore alone verifies, matches and
 * places them. Portable manifests never enroll a source or grant access to a local path.
 */
public final class LocalIngestion implements AutoCloseable {
  static final int MAX_POLICY_BYTES = 64 * 1024;
  static final int MAX_SOURCES = 64;
  static final int MAX_TRACKED_FILES = 100_000;
  static final int BATCH_FILES = 32;
  static final long DISCOVERY_NANOS = Duration.ofSeconds(30).toNanos();
  static final long SETTLE_NANOS = FileStability.SETTLE_TIME.toNanos();
  private static final java.util.concurrent.atomic.AtomicLong GRANT_SEQUENCE = new java.util.concurrent.atomic.AtomicLong();
  private static final int RECEIPT_BYTES = 16 * 1024;

  public record Source(String path, String mode, boolean enabled, boolean completedFiles, String statedRobot) {}
  public record Policy(long revision, boolean enabled, String store, String storeId, List<Source> sources) {
    public Policy { sources = sources == null ? List.of() : List.copyOf(sources); }
  }
  public record SourceStatus(String path, String mode, String state, String reason,
      int pending, int imported, int present, int refused) {}
  public record Status(Policy policy, String state, List<SourceStatus> sources,
      int pending, int active, String error) {}
  public record FileState(String source, String path, String state, String destination,
      String reason, String sha256, long sizeBytes) {}
  public record FilePage(List<FileState> files, int offset, int limit, int total) {}
  public static final class ConflictException extends IOException {
    public ConflictException(String message) { super(message); }
  }
  /** A deferred file also waits until {@code retryAt}; {@code backoff} is its last delay. Any other state clears both. */
  private record Observed(Source source, Stamp stamp, long since, String state,
      String destination, String reason, String hash, long retryAt, java.time.Duration backoff) {
    Observed(Source source, Stamp stamp, long since, String state, String destination, String reason, String hash) {
      this(source, stamp, since, state, destination, reason, hash, 0, null);
    }
    Observed state(String value, String destination, String reason, String hash) {
      return new Observed(source, stamp, since, value, destination, reason, hash);
    }
    /** Admission keeps the deferral, so the next one doubles its wait. */
    Observed importing() { return new Observed(source, stamp, since, "importing", null, null, null, retryAt, backoff); }
    /** Waiting again, with the reason; tried once {@code now} reaches {@code retryAt}. */
    Observed deferred(String why, long now) {
      var next = StoreInbox.nextDeferral(backoff);
      return new Observed(source, stamp, since, "waiting", null, why, null, now + next.toNanos(), next);
    }
    boolean due(long now) { return backoff == null || now - retryAt >= 0; }
  }
  private record Receipt(String owner, String source, String originalPath, Stamp stamp,
      String state, String destination, String reason, String sha256, String at) {}

  private final StoreRegistry registry;
  private final SecurityValidator security;
  private final Path policyFile;
  private final String owner;
  private final String grantOwner;
  private final ConcurrentHashMap<Path, Observed> observed = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, String> sourceErrors = new ConcurrentHashMap<>();
  private final AtomicBoolean polling = new AtomicBoolean();
  private final AtomicBoolean applying = new AtomicBoolean();
  private final AtomicBoolean importing = new AtomicBoolean();
  private final AtomicBoolean requested = new AtomicBoolean(true);
  private final AtomicBoolean retryRefused = new AtomicBoolean();
  private final AtomicBoolean wakeQueued = new AtomicBoolean();
  private final AtomicBoolean reconsiderNeeded = new AtomicBoolean(true);
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Object admissionLock = new Object();
  private final Object inventoryPublication = new Object();
  private volatile Policy policy = new Policy(0, false, null, null, List.of());
  private volatile Policy admitted;
  private volatile String error;
  private volatile ScheduledExecutorService scheduler;
  private volatile CompletableFuture<?> lastJob = CompletableFuture.completedFuture(null);
  private volatile AutoCloseable inventoryWatch;
  private volatile Path inventoryRoot;
  private volatile Map<Path, String> heldHashes = Map.of();
  private volatile Set<String> anchorHashes = Set.of();
  private long inventoryGeneration;
  private long lastDiscovery = Long.MIN_VALUE;
  private int nextSource;
  LongSupplier clock = System::nanoTime;

  public LocalIngestion(StoreRegistry registry, SecurityValidator security, Path policyFile) throws IOException {
    this.registry = registry;
    this.security = security;
    this.policyFile = policyFile.toAbsolutePath().normalize();
    owner = digest(this.policyFile.toString());
    grantOwner = owner + ":" + java.util.UUID.randomUUID();
    if (Files.exists(this.policyFile, LinkOption.NOFOLLOW_LINKS)) {
      policy = normalize(readPolicy(), false);
      grants();
    }
  }

  /** A configuration identity survives upgrades and port changes, but never crosses installs. */
  public static Path policyFileFor(Path home, Path canonicalConfig, String name) throws IOException {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("A named server is required");
    return home.toRealPath().resolve(".wpilog-mcp/state/local-ingestion")
        .resolve(org.triplehelix.wpilogmcp.config.ConfigurationIdentity.key(canonicalConfig, name) + ".json");
  }

  /** Persistent grants remain separate from client leases, and a paused store remains readable. */
  private void grants() {
    long generation = GRANT_SEQUENCE.incrementAndGet();
    var roots = new HashSet<Path>();
    if (!closed.get()) {
      grant(policy, roots);
    }
    var running = admitted;
    if (running != null) grant(running, roots);
    security.replacePersistentDirectories(grantOwner, generation, roots);
  }
  private static void grant(Policy value, Set<Path> roots) {
    if (value.store() != null) roots.add(Path.of(value.store()));
    if (value.enabled()) value.sources().stream().filter(Source::enabled)
        .map(s -> Path.of(s.path())).forEach(roots::add);
  }

  public Status replace(Policy proposed) throws IOException {
    if (closed.get()) throw new IOException("Local ingestion is closed");
    if (!applying.compareAndSet(false, true)) throw new ConflictException("Another configuration update is in progress");
    try {
      var normalized = normalize(proposed, true);
      checkLocalPath(policyFile);
      Files.createDirectories(policyFile.getParent());
      checkLocalPath(policyFile);
      var lockPath = policyFile.resolveSibling(policyFile.getFileName() + ".lock");
      checkLocalPath(lockPath);
      try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
          StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
          var lock = channel.tryLock()) {
        if (lock == null) throw new ConflictException("Another server is changing this ingestion policy");
        long revision = Files.exists(policyFile) ? readPolicy().revision() : 0;
        if (proposed.revision() != revision) throw new ConflictException("Ingestion configuration changed; refresh and try again");
        String storeId = null;
        if (normalized.store() != null && !Files.isDirectory(Path.of(normalized.store()))) {
          if (normalized.enabled() || !normalized.store().equals(policy.store())) {
            throw new IOException("Destination store is unavailable: " + normalized.store());
          }
          storeId = policy.storeId();
        } else if (normalized.store() != null) {
          var store = registry.store(Path.of(normalized.store()));
          storeId = store.capture(io -> io.read(store.root().resolve("store.json"), StoreManifest.Header.class).id());
          if (normalized.storeId() != null && !normalized.storeId().equals(storeId)) {
            throw new IOException("The selected store's identity changed; select it again");
          }
        }
        var updated = new Policy(Math.addExact(revision, 1), normalized.enabled(), normalized.store(),
            storeId, normalized.sources());
        writePolicy(updated);
        policy = updated;
        observed.clear(); sourceErrors.clear(); error = null;
        grants(); requested.set(true);
      } catch (OverlappingFileLockException e) {
        throw new ConflictException("Another server is changing this ingestion policy");
      }
    } finally { applying.set(false); }
    scanNow();
    return snapshot();
  }

  private Policy normalize(Policy value, boolean enrollment) throws IOException {
    if (value == null || value.revision() < 0 || value.sources().size() > MAX_SOURCES) {
      throw new IOException("Invalid ingestion policy (maximum " + MAX_SOURCES + " source folders)");
    }
    if (value.store() == null || value.store().isBlank()) {
      if (value.enabled() || !value.sources().isEmpty()) throw new IOException("Choose a destination store first");
      return new Policy(value.revision(), false, null, null, List.of());
    }
    var destination = canonical(value.store(), enrollment && value.enabled());
    if (enrollment) security.validate(destination);
    if (StoreCatalog.isStore(destination)) {
      var containment = new SecurityValidator(); containment.addAllowedDirectory(destination);
      if (new StoreFiles(destination, containment).read(destination.resolve("store.json"), StoreManifest.Header.class).mirror()) {
        throw new IOException("A mirror cannot receive local imports");
      }
    }
    var sources = new ArrayList<Source>();
    var roots = new ArrayList<Path>();
    for (var source : value.sources()) {
      if (source == null || source.mode() == null || !Set.of("copy", "move").contains(source.mode())) throw new IOException("Source mode must be copy or move");
      if (source.mode().equals("move") && !source.completedFiles()) {
        throw new IOException("Move requires a completed-file handoff folder; a quiet writer may still append");
      }
      if (source.statedRobot() != null) StoreFiles.robotName(source.statedRobot());
      var path = canonical(source.path(), enrollment && value.enabled() && source.enabled());
      if (enrollment) security.validate(path);
      if (path.startsWith(destination)) {
        throw new IOException("A source cannot be the destination store or inside it");
      }
      if (StoreCatalog.isStore(path) || StoreCatalog.containing(path).isPresent()) throw new IOException("A source cannot be inside another data store: " + path);
      var canonicalSource = new Source(path.toString(), source.mode(), source.enabled(), source.completedFiles(), source.statedRobot());
      boolean covered = false;
      for (int n = sources.size() - 1; n >= 0; n--) {
        var other = sources.get(n); var otherPath = Path.of(other.path());
        if (!otherPath.startsWith(path) && !path.startsWith(otherPath)) continue;
        if (!other.mode().equals(source.mode()) || other.enabled() != source.enabled()
            || other.completedFiles() != source.completedFiles() || !java.util.Objects.equals(other.statedRobot(), source.statedRobot())) {
          throw new IOException("Overlapping source folders have conflicting policies: " + path);
        }
        if (path.startsWith(otherPath)) covered = true;
        else { sources.remove(n); roots.remove(n); }
      }
      if (covered) continue;
      roots.add(path);
      sources.add(canonicalSource);
    }
    return new Policy(value.revision(), value.enabled(), destination.toString(), value.storeId(), List.copyOf(sources));
  }

  private static Path canonical(String value, boolean existing) throws IOException {
    if (value == null || value.isBlank()) throw new IOException("An absolute folder path is required");
    var path = Path.of(value);
    if (!path.isAbsolute()) throw new IOException("An absolute folder path is required: " + value);
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      if (!Files.isDirectory(path)) throw new IOException("Not a directory: " + value);
      var real = path.toRealPath();
      if (!existing && !real.equals(path.normalize())) throw new IOException("Enrolled folder resolves elsewhere; enroll it again: " + value);
      return real;
    }
    if (existing) throw new IOException("Folder is unavailable: " + value);
    var normalized = path.normalize();
    for (var ancestor = normalized.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
      if (Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
        if (!ancestor.toRealPath().equals(ancestor)) throw new IOException("Enrolled folder ancestor resolves elsewhere: " + value);
        break;
      }
    }
    return normalized;
  }

  private Policy readPolicy() throws IOException {
    checkLocalPath(policyFile);
    if (Files.size(policyFile) > MAX_POLICY_BYTES) throw new IOException("Local ingestion policy exceeds 64 KiB");
    try (var input = Files.newBufferedReader(policyFile)) {
      var read = StoreJson.JSON.fromJson(input, Policy.class);
      if (read == null) throw new IOException("Empty local ingestion policy");
      return read;
    } catch (RuntimeException e) { throw new IOException("Invalid local ingestion policy", e); }
  }
  private void writePolicy(Policy value) throws IOException {
    byte[] bytes = StoreJson.JSON.toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (bytes.length > MAX_POLICY_BYTES) throw new IOException("Local ingestion policy exceeds 64 KiB");
    var temporary = Files.createTempFile(policyFile.getParent(), ".ingestion-", ".tmp");
    try {
      if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
      }
      Files.write(temporary, bytes);
      Files.move(temporary, policyFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally { Files.deleteIfExists(temporary); }
  }
  private static void checkLocalPath(Path file) throws IOException {
    for (var p = file; p != null; p = p.getParent()) {
      if (Files.isSymbolicLink(p)) throw new IOException("Local ingestion state must not follow symbolic links: " + p);
    }
  }

  public synchronized void start() {
    if (closed.get()) throw new IllegalStateException("Local ingestion is closed");
    if (scheduler != null) return;
    scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
      var thread = new Thread(task, "local-log-ingestion"); thread.setDaemon(true); return thread;
    });
    scheduler.scheduleWithFixedDelay(() -> poll(clock.getAsLong()), 0, 3, TimeUnit.SECONDS);
  }
  /**
   * An explicit refresh retries refusals once, after settling again. Ordinary discovery and
   * restart retain refusal receipts so a bad file cannot create a background import storm.
   * Requests coalesce; no filesystem work runs on the caller's thread.
   */
  public void scanNow() {
    retryRefused.set(true);
    requested.set(true);
    var worker = scheduler;
    if (worker != null && !closed.get() && wakeQueued.compareAndSet(false, true)) {
      try { worker.execute(() -> { wakeQueued.set(false); poll(clock.getAsLong()); }); }
      catch (java.util.concurrent.RejectedExecutionException ignored) { wakeQueued.set(false); }
    }
  }
  public boolean enabled() { return !closed.get() && policy.enabled(); }
  public Path preferredStore() { return policy.store() == null ? null : Path.of(policy.store()); }
  public String preferredStoreId() { return policy.storeId(); }

  void poll(long now) {
    if (!enabled() || applying.get() || !polling.compareAndSet(false, true)) return;
    try {
      var current = policy;
      var root = Path.of(current.store());
      if (!Files.isDirectory(root)) throw new IOException("Destination store is unavailable: " + root);
      security.validate(root);
      var io = new StoreFiles(root, security);
      var header = io.read(root.resolve("store.json"), StoreManifest.Header.class);
      if (header.mirror() || !header.id().equals(current.storeId())) throw new IOException("Destination store identity changed");
      watchInventory(registry.store(root));
      if (requested.getAndSet(false) || lastDiscovery == Long.MIN_VALUE || now - lastDiscovery >= DISCOVERY_NANOS) {
        discover(current, io, now, retryRefused.getAndSet(false));
        lastDiscovery = now;
      } else {
        for (var item : observed.entrySet()) {
          if (Set.of("waiting", "unavailable").contains(item.getValue().state())) observe(item.getKey(), item.getValue().source(), io, now, false);
        }
      }
      // A policy edit may complete while this filesystem walk is in progress. Its cleared
      // observations must not be replaced by old source modes that can never be admitted.
      if (policy != current) {
        observed.clear(); sourceErrors.clear(); requested.set(true);
        return;
      }
      error = null;
      // One job a poll. A batch can finish before this line, as one a busy store refuses does; reconsidering then
      // would make that job the poll's work in place of the batch, so it waits for the next poll.
      boolean admittedBatch = !importing.get() && admit(current, now);
      if (!admittedBatch && !importing.get() && reconsiderNeeded.compareAndSet(true, false)) reconsider(current);
    } catch (IOException | RuntimeException e) { error = message(e); }
    finally { polling.set(false); }
  }

  private void discover(Policy current, StoreFiles io, long now, boolean retry) throws IOException {
    var found = new HashSet<Path>();
    for (var source : current.sources()) {
      if (!source.enabled()) continue;
      var root = Path.of(source.path());
      try {
        security.validate(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Source folder is unavailable");
        Files.walkFileTree(root, Set.of(), org.triplehelix.wpilogmcp.log.LogDirectory.getInstance().getScanDepth(), new SimpleFileVisitor<>() {
          @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) {
            return path.equals(Path.of(current.store())) || !path.equals(root) && (path.getFileName().toString().startsWith(".") || StoreCatalog.isStore(path))
                ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
          }
          @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
            String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (attributes.isRegularFile() && !name.startsWith(".") && (name.endsWith(".wpilog") || name.endsWith(".revlog"))) {
              if (found.size() >= MAX_TRACKED_FILES || !observed.containsKey(path) && observed.size() >= MAX_TRACKED_FILES) {
                throw new IOException("Local ingestion file limit reached: " + MAX_TRACKED_FILES);
              }
              found.add(path); observe(path, source, io, now, retry);
            }
            return FileVisitResult.CONTINUE;
          }
          @Override public FileVisitResult visitFileFailed(Path path, IOException failure) throws IOException {
            if (failure instanceof java.nio.file.NoSuchFileException) return FileVisitResult.CONTINUE;
            throw failure;
          }
        });
        sourceErrors.remove(source.path());
      } catch (IOException e) { sourceErrors.put(source.path(), message(e)); }
    }
    observed.forEach((path, value) -> {
      if (!found.contains(path) && !value.state().equals("importing")) {
        // Move deliberately removes its original; only loss of the verified stored copy
        // makes that completed import unavailable. Duplicate originals were never moved.
        if (value.source().mode().equals("move") && Set.of("imported", "unassigned").contains(value.state())) {
          try { if (resolveDestination(value.destination(), value.hash()) != null) return; }
          catch (IOException ignored) { /* Report the unavailable copy below. */ }
        }
        observed.replace(path, value, value.state("unavailable", value.destination(), "Source file is unavailable", value.hash()));
      }
    });
  }

  /** Inventory publication already belongs to the store; idle folder scans never rescan manifests. */
  private void watchInventory(LogStore store) throws IOException {
    if (store.root().equals(inventoryRoot)) return;
    if (inventoryWatch != null) {
      try { inventoryWatch.close(); }
      catch (Exception e) { throw new IOException("Could not retire ingestion inventory", e); }
    }
    final long generation;
    synchronized (inventoryPublication) {
      inventoryRoot = store.root(); generation = ++inventoryGeneration;
    }
    reconsiderNeeded.set(true);
    var watched = store.root();
    inventoryWatch = store.observe(new LogStore.Observer() {
      @Override public void manifest(Path path, Object value) {}
      @Override public void inventory(StoreCatalog.Snapshot snapshot) {
        LocalIngestion.this.acceptInventory(snapshot, generation);
      }
    });
    acceptInventory(StoreCatalog.readInventory(store.root(), security, (path, why) -> {}), generation);
  }

  private void acceptInventory(StoreCatalog.Snapshot snapshot) {
    final long generation;
    synchronized (inventoryPublication) { generation = inventoryGeneration; }
    acceptInventory(snapshot, generation);
  }
  private void acceptInventory(StoreCatalog.Snapshot snapshot, long generation) {
    var hashes = new java.util.HashMap<Path, String>();
    var anchors = new HashSet<String>();
    for (var file : snapshot.files()) {
      hashes.put(file.path(), file.file().sha256());
      if (file.session() != null && file.file().kind().equals("wpilog")) anchors.add(file.file().sha256());
    }
    synchronized (inventoryPublication) {
      if (generation != inventoryGeneration || !snapshot.root().equals(inventoryRoot)) return;
      heldHashes = Map.copyOf(hashes);
      if (!anchorHashes.equals(anchors)) {
        anchorHashes = Set.copyOf(anchors);
        reconsiderNeeded.set(true);
      }
    }
  }

  private void observe(Path path, Source source, StoreFiles io, long now, boolean retry) throws IOException {
    var previous = observed.get(path);
    if (previous != null && previous.state().equals("importing")) return;
    Stamp stamp;
    try { stamp = Stamp.read(path); }
    catch (java.nio.file.NoSuchFileException e) {
      if (previous != null) observed.put(path, previous.state("unavailable", previous.destination(), "Source file is unavailable", previous.hash()));
      return;
    }
    if (retry && previous != null && previous.state().equals("refused")) {
      observed.put(path, new Observed(source, stamp, now, "waiting", null, "Retry requested; waiting for a settled version", null));
      return;
    }
    if (previous != null && previous.stamp().equals(stamp) && !previous.state().equals("unavailable")) {
      if (successful(previous.state()) && resolveDestination(previous.destination(), previous.hash()) == null) {
        observed.put(path, new Observed(source, stamp, now, "waiting", null, "Stored copy is unavailable; will import again", null));
      }
      return;
    }
    if (previous == null) {
      var receipt = readReceipt(io, path);
      if (receipt != null && !(retry && receipt.state().equals("refused"))
          && stamp.equals(receipt.stamp()) && receipt.source().equals(source.path())) {
        String destination = resolveDestination(receipt.destination(), receipt.sha256());
        if (!successful(receipt.state()) || destination != null) {
          observed.put(path, new Observed(source, stamp, now, receipt.state(), destination,
              receipt.reason(), receipt.sha256()));
          return;
        }
      }
    }
    observed.put(path, new Observed(source, stamp, now, "waiting", null, null, null));
  }

  /** Starts at most one batch; true when it did. */
  private boolean admit(Policy current, long now) throws IOException {
    var sources = current.sources();
    for (int turn = 0; turn < sources.size(); turn++) {
      int index = (nextSource + turn) % sources.size();
      var source = sources.get(index);
      if (!source.enabled() || sourceErrors.containsKey(source.path())) continue;
      var ready = observed.entrySet().stream().filter(e -> e.getValue().source().equals(source)
          && e.getValue().state().equals("waiting") && FileStability.settled(now, e.getValue().since()) && e.getValue().due(now))
          .sorted(Map.Entry.comparingByKey()).limit(BATCH_FILES).toList();
      if (ready.isEmpty()) continue;
      var ticket = begin(current);
      if (ticket == null) return false;
      nextSource = (index + 1) % sources.size();
      var captured = new LinkedHashMap<Path, Observed>();
      ready.forEach(e -> {
        captured.put(e.getKey(), e.getValue());
        observed.replace(e.getKey(), e.getValue(), e.getValue().importing());
      });
      try {
        var store = registry.store(Path.of(current.store()));
        var request = new LogStore.Request(List.copyOf(captured.keySet()), source.mode().equals("move"), source.statedRobot());
        store.importPrepared(request, p -> {}, candidate -> {
          var io = new StoreFiles(store.root(), security);
          var header = io.read(store.root().resolve("store.json"), StoreManifest.Header.class);
          if (header.mirror() || !java.util.Objects.equals(header.id(), current.storeId())) {
            throw new IOException("Destination store identity changed while import was queued");
          }
          var stable = new ArrayList<Path>();
          for (var path : candidate.paths()) {
            var original = captured.get(path);
            if (!enabled() || policy.revision() != current.revision()) {
              continue;
            }
            try {
              security.validate(path);
              if (original.stamp().equals(Stamp.read(path))) stable.add(path);
              else observed.put(path, new Observed(source, Stamp.read(path), clock.getAsLong(), "waiting", null, null, null));
            } catch (IOException e) { observed.put(path, original.state("unavailable", null, message(e), null)); }
          }
          return new LogStore.Request(stable, candidate.move(), candidate.statedRobot());
        }, result -> receipts(store, captured, result, current.revision())).thenCompose(result -> store.reconsiderUnassignedRev())
            .whenComplete((result, failure) -> {
          if (result != null) reconsiderNeeded.set(result.remaining() > 0);
          if (failure != null) {
            reconsiderNeeded.set(true);
            error = message(failure);
            // A busy store waits out its backoff; any other failure is tried at the next look, as before.
            boolean deferred = store.deferred(failure);
            long at = clock.getAsLong();
            captured.forEach((path, old) -> observed.computeIfPresent(path, (p, value) -> !value.state().equals("importing") ? value
                : deferred ? old.deferred(error, at) : old.state("waiting", null, error, null)));
          }
          finish(ticket, failure);
        });
      } catch (IOException | RuntimeException e) {
        finish(ticket, e); throw e;
      }
      return true;
    }
    return false;
  }

  private void reconsider(Policy current) throws IOException {
    var ticket = begin(current);
    if (ticket == null) { reconsiderNeeded.set(true); return; }
    try {
      registry.store(Path.of(current.store())).reconsiderUnassignedRev().whenComplete((result, failure) -> {
        if (failure != null) { error = message(failure); reconsiderNeeded.set(true); }
        else reconsiderNeeded.set(result.remaining() > 0);
        finish(ticket, failure);
      });
    } catch (IOException | RuntimeException e) {
      finish(ticket, e); throw e;
    }
  }

  /** Publish the lifetime before queue admission so close cannot retire its grants too soon. */
  private CompletableFuture<Void> begin(Policy current) {
    synchronized (admissionLock) {
      if (closed.get() || importing.get()) return null;
      importing.set(true); admitted = current;
      var ticket = new CompletableFuture<Void>(); lastJob = ticket; return ticket;
    }
  }
  private void finish(CompletableFuture<Void> ticket, Throwable failure) {
    synchronized (admissionLock) { admitted = null; importing.set(false); }
    try { grants(); }
    catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
    if (failure == null) ticket.complete(null); else ticket.completeExceptionally(failure);
  }

  /** Test barriers observe the whole admitted batch, including its bounded reconsideration. */
  CompletableFuture<?> currentWork() { return lastJob; }

  /** Receipts share the import's store lock; they record duplicate origins without rewriting provenance. */
  private void receipts(LogStore store, Map<Path, Observed> captured, LogStore.Result result, long revision) {
    try {
      var io = new StoreFiles(store.root(), security);
      for (var outcome : result.files()) {
        var old = captured.get(outcome.originalPath());
        if (old == null) continue;
        if (outcome.status().equals("deferred")) {
          // Never a receipt: one would outlive the passing cause, and a restart would not try again.
          if (policy.revision() == revision) observed.put(outcome.originalPath(), old.deferred(outcome.reason(), clock.getAsLong()));
          continue;
        }
        String hash = outcome.path() == null || !successful(outcome.status()) ? null
            : StoreCatalog.finalizedHash(store.root(), outcome.path(), security);
        String destination = outcome.path() == null ? null : outcome.path().toString();
        String state = outcome.status();
        var receipt = new Receipt(owner, old.source().path(), outcome.originalPath().toString(),
            old.stamp(), state, destination, outcome.reason(), hash, Instant.now().toString());
        var current = receiptPath(store.root(), outcome.originalPath());
        var history = current.resolveSibling(digest(StoreJson.JSON.toJson(old.stamp())) + ".json");
        io.write(history, receipt);
        io.write(current, receipt);
        if (policy.revision() == revision) observed.put(outcome.originalPath(), old.state(state, destination, outcome.reason(), hash));
      }
    } catch (IOException e) {
      error = "Imported files retained, but source receipts could not be written: " + message(e);
      captured.forEach((path, old) -> observed.computeIfPresent(path, (p, value) ->
          value.state().equals("importing") ? old.state("refused", null, error, null) : value));
    }
  }

  private Receipt readReceipt(StoreFiles io, Path source) throws IOException {
    var path = receiptPath(Path.of(policy.store()), source);
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
    try {
      if (Files.size(io.check(path)) > RECEIPT_BYTES) return null;
      var receipt = io.read(path, Receipt.class);
      if (!owner.equals(receipt.owner()) || receipt.source() == null
          || !source.toString().equals(receipt.originalPath()) || receipt.stamp() == null
          || receipt.state() == null || !Set.of("imported", "unassigned", "present", "refused").contains(receipt.state())
          || successful(receipt.state()) && (receipt.sha256() == null || !receipt.sha256().matches("[0-9a-f]{64}"))) return null;
      return receipt;
    } catch (IOException | RuntimeException invalid) {
      return null; // Receipts are an optimization: malformed metadata must trigger revalidation.
    }
  }
  private Path receiptPath(Path root, Path source) {
    return root.resolve(".local-ingestion").resolve(owner).resolve(digest(source.toString())).resolve("current.json");
  }
  private String resolveDestination(String path, String hash) throws IOException {
    if (path == null || hash == null) return null;
    var original = Path.of(path);
    var root = preferredStore();
    if (root == null || !original.startsWith(root)) return null;
    if (hash.equals(heldHashes.get(original)) && Files.isRegularFile(original)) {
      security.validate(original);
      return original.toString();
    }
    var resolved = registry.resolveMoved(original);
    security.validate(resolved);
    return resolved.startsWith(root) && hash.equals(heldHashes.get(resolved)) && Files.isRegularFile(resolved)
        ? resolved.toString() : null;
  }
  private static boolean successful(String state) { return Set.of("imported", "unassigned", "present").contains(state); }

  /** Only the exact current source version with a surviving verified receipt is hidden. */
  public boolean visible(Path path) {
    var value = observed.get(path.toAbsolutePath().normalize());
    if (value == null || !successful(value.state())) return true;
    try { return !value.stamp().equals(Stamp.read(path)) || resolveDestination(value.destination(), value.hash()) == null; }
    catch (IOException e) { return true; }
  }
  public FilePage files(int offset, int limit) {
    if (offset < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("offset must be nonnegative; limit 1..1000");
    var values = observed.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> {
      var v = e.getValue(); return new FileState(v.source().path(), e.getKey().toString(), v.state(),
          v.destination(), v.reason(), v.hash(), v.stamp().size());
    }).toList();
    return new FilePage(values.stream().skip(offset).limit(limit).toList(), offset, limit, values.size());
  }
  public Status snapshot() {
    var values = List.copyOf(observed.values());
    var sources = policy.sources().stream().map(s -> {
      var members = values.stream().filter(v -> v.source().path().equals(s.path())).toList();
      String why = sourceErrors.get(s.path());
      String state = !policy.enabled() || !s.enabled() ? "paused" : why == null ? "watching" : "unavailable";
      return new SourceStatus(s.path(), s.mode(), state, why, count(members, "waiting", "importing"),
          count(members, "imported", "unassigned"), count(members, "present"), count(members, "refused"));
    }).toList();
    return new Status(policy, error != null ? "error" : !enabled() ? "paused" : importing.get() ? "importing" : "watching",
        sources, count(values, "waiting"), count(values, "importing"), error);
  }
  private static int count(List<Observed> values, String... states) {
    var selected = Set.of(states); return (int) values.stream().filter(v -> selected.contains(v.state())).count();
  }
  private static String message(Throwable failure) {
    while (failure.getCause() != null) failure = failure.getCause();
    return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
  }
  private static String digest(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
  /** Stop admission without withdrawing grants from a staging worker which still owns them. */
  public void stop() { stopAdmission(); }

  private CompletableFuture<?> stopAdmission() {
    CompletableFuture<?> drained;
    synchronized (admissionLock) {
      if (!closed.compareAndSet(false, true)) return lastJob;
      drained = lastJob;
    }
    var worker = scheduler;
    if (worker != null) worker.shutdown();
    drained.whenComplete((value, failure) -> {
      grants();
      var observer = inventoryWatch;
      if (observer != null) try { observer.close(); }
      catch (Exception e) { error = message(e); }
    });
    return drained;
  }

  @Override public void close() {
    var drained = stopAdmission();
    try { drained.get(30, TimeUnit.SECONDS); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); error = "Interrupted waiting for local ingestion shutdown"; }
    catch (java.util.concurrent.TimeoutException e) { error = "Local ingestion shutdown exceeded 30 seconds; admitted work retains its grants until it finishes"; }
    catch (java.util.concurrent.ExecutionException e) { error = message(e); }
  }
}
