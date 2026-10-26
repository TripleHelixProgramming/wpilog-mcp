/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.read.LogAccess;
import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.ReadOptions;
import org.triplehelix.wpilogmcp.read.ReadProblem;
import org.triplehelix.wpilogmcp.read.ReadSnapshot;
import org.triplehelix.wpilogmcp.read.rev.RecordedRevRead;
import org.triplehelix.wpilogmcp.store.StoreCatalog;
import org.triplehelix.wpilogmcp.store.SystemLogIndex;
import org.triplehelix.wpilogmcp.sync.SyncResult;

/** Read-only manifest admission, independent of a server or a store writer. Explicit roots name
 * stores, not directories to discover recursively. Detached manifest facts are reused only while
 * their inspected filesystem dependencies agree; it neither adopts strays nor corrects alignment.
 * Stable store/session ids, content hashes, provenance, origin state and move aliases retain their
 * existing manifest meanings. Closing retires WPILOG snapshots and cached REV admission;
 * admitted cursors and explicitly owned REV reads may finish. */
public final class StoreReadCatalog implements AutoCloseable {
  public record CatalogProblem(Path manifest, String reason) {}
  public record Snapshot(List<StoreCatalog.Snapshot> stores, List<CatalogProblem> problems,
      List<SharedSystemLogs> sharedSystemLogs) {
    public Snapshot { stores = List.copyOf(stores); problems = List.copyOf(problems); sharedSystemLogs = List.copyOf(sharedSystemLogs); }
    public Snapshot(List<StoreCatalog.Snapshot> stores, List<CatalogProblem> problems) { this(stores, problems, List.of()); }
  }
  public record SharedSystemLogs(String storeId, String robotId, Path index, SystemLogIndex inventory) {}
  public record StoreFile(String storeId, Path root, StoreCatalog.StoredFile facts) {}
  public record Recording(RecordingReference reference, org.triplehelix.wpilogmcp.store.CaptureRecording identity,
      List<StoreFile> members, boolean finalized) {
    public Recording { members = List.copyOf(members); }
  }
  private final List<Path> roots;
  private final SecurityValidator security = new SecurityValidator();
  private final LogAccess logs;
  private final boolean ownsLogs;
  /** Across retained session snapshots and their cursors, not per file or HTTP request. */
  public static final long DEFAULT_SESSION_INDEX_BYTES = 128L << 20;
  private final long sessionIndexLimit;
  private long sessionIndexBytes;
  private final java.util.Set<SessionSnapshot> sessions = new java.util.HashSet<>();
  private final java.util.concurrent.atomic.AtomicBoolean openingSession = new java.util.concurrent.atomic.AtomicBoolean();
  private final org.triplehelix.wpilogmcp.read.rev.RevReadCache revs = new org.triplehelix.wpilogmcp.read.rev.RevReadCache();
  private volatile boolean closed;
  private record Cached(Snapshot snapshot, Map<Path, FileSnapshot> stamps,
      Map<Path, StoreFile> members, Instant expires) {
    boolean current() throws IOException { return current(java.util.Set.of()); }
    boolean current(java.util.Set<Path> growing) throws IOException {
      if (!Instant.now().isBefore(expires)) return false;
      for (var item : stamps.entrySet()) {
        var now = FileSnapshot.of(item.getKey());
        var before = item.getValue();
        if (before == null ? now != null : !before.sameAs(now)
            && !(growing.contains(item.getKey()) && now != null && now.grewFrom(before))) return false;
      }
      return true;
    }
  }
  private volatile Cached cached;
  private final AtomicReference<CompletableFuture<Cached>> refreshing = new AtomicReference<>();
  @FunctionalInterface interface ScanObserver { void completed() throws IOException; }
  private final ScanObserver afterScan;
  /** How the refresh owner waits before it scans a moving store again; tests replace it. */
  @FunctionalInterface interface Settle { void pause(java.time.Duration wait) throws InterruptedException; }
  /** A store moves in bursts: a capture closing its file when a robot disconnects writes its
   * placement and then its clock record. Scanned again at once, a store still inside the burst
   * refused a read that a moment later would have succeeded. */
  static final java.time.Duration RESCAN_PAUSE = java.time.Duration.ofMillis(50);
  private final Settle settle;

  public StoreReadCatalog(List<Path> roots) throws IOException { this(ReadOptions.defaults(roots)); }
  /** Canonical admission roots, used by a transport to retain one catalog while its leases agree. */
  public List<Path> roots() { return roots; }
  public StoreReadCatalog(ReadOptions options) throws IOException {
    this(options, null, DEFAULT_SESSION_INDEX_BYTES);
  }
  /** Borrow server admission without owning it. Session members then share its cache and live
   * prefixes. A null access creates an owned local reader; it excludes manifested open captures.
   * The budget counts indexed address bytes, not mapped file bytes or decoded value caches. */
  public StoreReadCatalog(ReadOptions options, LogAccess access, long sessionIndexBytes) throws IOException {
    this(options, access, sessionIndexBytes, () -> {});
  }
  /** Deterministic boundary for testing filesystem changes between inventory and validation. */
  StoreReadCatalog(ReadOptions options, LogAccess access, long sessionIndexBytes, ScanObserver afterScan) throws IOException {
    this(options, access, sessionIndexBytes, afterScan, wait -> Thread.sleep(wait.toMillis()));
  }
  StoreReadCatalog(ReadOptions options, LogAccess access, long sessionIndexBytes, ScanObserver afterScan, Settle settle)
      throws IOException {
    this.afterScan = afterScan; this.settle = settle;
    if (sessionIndexBytes < 0) throw new IllegalArgumentException("Session index budget must be nonnegative");
    this.sessionIndexLimit = sessionIndexBytes;
    this.ownsLogs = access == null;
    var paths = new ArrayList<Path>();
    for (var root : options.roots()) {
      var real = root.toRealPath();
      if (!Files.isDirectory(real)) throw new IOException("Store read root is not a directory: " + root);
      if (!paths.contains(real)) paths.add(real);
      security.addAllowedDirectory(real);
    }
    roots = List.copyOf(paths);
    logs = ownsLogs ? LogAccess.local(options) : access;
  }

  /** Invalid members and control manifests are named individually; an invalid store header
   * refuses that store. No source is created to repair an error. Writer inventories remain strict. */
  public Snapshot snapshot() throws IOException { return inventory().snapshot(); }

  private Cached inventory() throws IOException {
    requireOpen();
    var seen = cached;
    if (seen != null && seen.current()) return seen;
    var work = new CompletableFuture<Cached>();
    var prior = refreshing.compareAndExchange(null, work);
    if (prior != null) {
      try { return prior.get(); }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt(); throw new IOException("Interrupted reading store catalog", e);
      } catch (ExecutionException e) {
        if (e.getCause() instanceof IOException io) throw io;
        if (e.getCause() instanceof RuntimeException runtime) throw runtime;
        if (e.getCause() instanceof Error error) throw error;
        throw new IOException("Store catalog failed", e.getCause());
      }
    }
    try {
      for (int attempt = 0; attempt < 2; attempt++) {
        if (attempt > 0) settle();
        var result = scan();
        if (result != null) { requireOpen(); cached = result; work.complete(result); return result; }
      }
      throw new ReadException(ReadProblem.Code.FILE_CHANGED,
          "Store changed while reading its catalog; retry the read", true);
    } catch (Throwable failure) { work.completeExceptionally(failure); throw failure; }
    finally { refreshing.compareAndSet(work, null); }
  }

  /** The second scan of a moving store waits {@link #RESCAN_PAUSE} on the refresh owner's thread,
   * a request's, never a loop's; a stop that interrupts the wait ends the read as interrupted. */
  private void settle() throws IOException {
    try { settle.pause(RESCAN_PAUSE); }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException("Interrupted reading store catalog");
    }
  }

  /** A moving inventory is retried by its single refresh owner, never on the recording loop.
   * Open payload growth does not change membership. It is allowed only at scan validation;
   * cache reuse stays strict so a later read refreshes size facts and still detects truncation.
   * This stamp is not a proof of unchanged log history: the managed prefix/follow owns that. */
  private Cached scan() throws IOException {
    var stamps = new HashMap<Path, FileSnapshot>();
    StoreCatalog.PathObserver observer = path -> {
      // Directory mtimes find new manifests, including an initially absent folder. File
      // identity, mtime and size find replacements and growth without reading old payloads.
      for (var at = path.toAbsolutePath().normalize(); at != null; at = at.getParent()) {
        Path dependency = at;
        if (roots.stream().noneMatch(dependency::startsWith)) break;
        if (!stamps.containsKey(at)) stamps.put(at, FileSnapshot.of(at));
      }
    };
    for (var root : roots) observer.inspect(root.resolve("store.json"));
    var snapshot = readFresh(observer);
    afterScan.completed();
    var members = new HashMap<Path, StoreFile>();
    var growing = new java.util.HashSet<Path>();
    var expires = Instant.MAX;
    for (var store : snapshot.stores()) {
      for (var file : store.allFiles()) {
        try { members.put(file.path().toRealPath(), new StoreFile(store.header().id(), store.root(), file)); }
        catch (java.nio.file.NoSuchFileException moved) { return null; }
      }
      for (var file : store.openCaptures()) growing.add(file.path().toAbsolutePath().normalize());
      for (var move : store.moved()) {
        var until = Instant.parse(move.movedAt()).plus(StoreCatalog.MOVE_NOTICE_LIFETIME);
        if (until.isBefore(expires)) expires = until;
      }
    }
    var result = new Cached(snapshot, java.util.Collections.unmodifiableMap(stamps), Map.copyOf(members), expires);
    return result.current(growing) ? result : null;
  }

  private Snapshot readFresh(StoreCatalog.PathObserver observer) throws IOException {
    requireOpen();
    var stores = new ArrayList<StoreCatalog.Snapshot>();
    var problems = new ArrayList<CatalogProblem>();
    var shared = new ArrayList<SharedSystemLogs>();
    for (var root : roots) {
      try {
        stores.add(StoreCatalog.readTolerantInventory(root, security,
            (path, reason) -> problems.add(new CatalogProblem(path, reason)), observer));
      } catch (IOException failure) {
        problems.add(new CatalogProblem(root.resolve("store.json"), failure.getMessage()));
      }
    }
    for (var store : stores) for (var robot : store.robots()) {
      var index = SystemLogIndex.path(robot.path());
      if (problems.stream().anyMatch(problem -> problem.manifest().equals(index))) continue;
      try {
        var inventory = StoreCatalog.readSystemIndex(robot.path(), security, observer);
        if (!inventory.files().isEmpty()) shared.add(new SharedSystemLogs(store.header().id(), robot.robot().id(), index, inventory));
      } catch (IOException failure) {
        problems.add(new CatalogProblem(index, failure.getMessage()));
      }
    }
    requireOpen();
    return new Snapshot(stores, problems, shared);
  }

  /** Resolve durable aliases, then require actual membership in a readable manifest. A directory
   * or a familiar extension alone is never enough to admit a file. */
  public Optional<StoreFile> resolve(Path path) throws IOException {
    requireOpen();
    security.validate(path);
    return resolve(path, inventory());
  }
  private Optional<StoreFile> resolve(Path path, Cached inventory) throws IOException {
    security.validate(path);
    var containing = StoreCatalog.containing(path);
    Path resolved = path;
    if (containing.isPresent()) {
      var realRoot = containing.get().toRealPath();
      var store = inventory.snapshot().stores().stream().filter(s -> s.root().equals(realRoot)).findFirst();
      if (store.isEmpty()) throw new IOException("Unreadable store manifest: " + containing.get().resolve("store.json"));
      try { resolved = StoreCatalog.resolveMoved(path, store.get().root(), store.get().header(), security); }
      catch (RuntimeException malformed) {
        throw new IOException("Invalid move records in " + containing.get().resolve("store.json"), malformed);
      }
    }
    security.validate(resolved);
    var target = Files.exists(resolved) ? resolved.toRealPath() : resolved.toAbsolutePath().normalize();
    var member = inventory.members().get(target);
    if (member != null) security.validate(member.facts().path());
    return Optional.ofNullable(member);
  }

  /** A mirror retains the originating store's identity; serving location is not identity. A mirror
   * whose first contact has not yet named that identity has none, and matches no reference. */
  public static String originId(StoreCatalog.Snapshot store) {
    return org.triplehelix.wpilogmcp.store.RecordedClocks.origin(store);
  }

  private List<StoreCatalog.Snapshot> selectedStores(String scopeId, String origin) throws IOException {
    var stores = snapshot().stores().stream()
        .filter(s -> scopeId == null ? origin.equals(originId(s)) : scopeId.equals(s.header().id())).toList();
    if (stores.size() > 1) throw new IOException("SOURCE_SELECTION_REQUIRED: several serving stores match; ask the user to choose a scope_id");
    if (!stores.isEmpty() && !origin.equals(originId(stores.get(0))))
      throw new IOException("SOURCE_UNAVAILABLE: selected serving scope does not have the requested origin");
    return stores;
  }

  /** Recorded visualization references resolve by manifested content, never a similar filename. */
  public StoreFile resolveContent(String originStoreId, String sha256) throws IOException {
    return resolveContent(null, originStoreId, sha256);
  }
  public StoreFile resolveContent(String scopeId, String originStoreId, String sha256) throws IOException {
    for (var store : selectedStores(scopeId, originStoreId)) {
      for (var file : store.allFiles()) if ("wpilog".equals(file.file().kind()) && sha256.equals(file.file().sha256())) {
        security.validate(file.path()); return new StoreFile(store.header().id(), store.root(), file);
      }
    }
    throw new IOException("SOURCE_UNAVAILABLE: no configured store contains the recorded content");
  }

  /** A session or alias only nominates candidates. One provenance anchor proves the recording;
   * measured alignment and a shared calendar never widen its rollover membership. */
  public Recording recording(RecordingReference reference) throws IOException {
    return recording(null, reference);
  }
  public Recording recording(String scopeId, RecordingReference reference) throws IOException {
    var store = selectedStores(scopeId, reference.originStoreId());
    if (store.size() != 1) throw new IOException("SOURCE_UNAVAILABLE: select one configured origin store or mirror for this recording");
    var selected = store.get(0);
    var sessions = selected.sessions().stream().filter(s -> s.session().names(reference.sessionId())).toList();
    if (sessions.size() > 1) throw new IOException("SOURCE_UNAVAILABLE: ambiguous session alias");
    List<StoreCatalog.StoredFile> files;
    if (sessions.isEmpty()) {
      if (reference.finalizedHashes().isEmpty()) throw new IOException("SOURCE_UNAVAILABLE: session identity or merge alias is no longer retained");
      files = selected.allFiles().stream().filter(f -> reference.finalizedHashes().contains(f.file().sha256())).toList();
      if (!files.stream().map(f -> f.file().sha256()).collect(java.util.stream.Collectors.toSet()).containsAll(reference.finalizedHashes()))
        throw new IOException("HISTORY_UNAVAILABLE: a known finalized recording member is no longer retained");
    } else {
      var manifest = sessions.get(0).path().resolve("session.json");
      files = selected.allFiles().stream().filter(f -> f.manifestPath().equals(manifest)).toList();
    }
    String origin = originId(selected);
    var anchors = files.stream().filter(f -> "wpilog".equals(f.file().kind()))
        .filter(f -> org.triplehelix.wpilogmcp.store.CaptureRecording.of(f.file().provenance(), origin).isPresent())
        .filter(f -> reference.originalName().equals(f.file().provenance().originalName())
            && reference.recordingStartedAt().equals(f.file().provenance().importedAt())).toList();
    if (anchors.size() != 1) throw new IOException("SOURCE_UNAVAILABLE: recording anchor is missing or ambiguous");
    var anchor = anchors.get(0);
    var identity = org.triplehelix.wpilogmcp.store.CaptureRecording.of(anchor.file().provenance(), origin).orElseThrow();
    var members = files.stream().filter(f -> "wpilog".equals(f.file().kind()))
        .filter(f -> org.triplehelix.wpilogmcp.store.CaptureRecording.same(f.file().provenance(), anchor.file().provenance(), origin))
        .sorted(java.util.Comparator.comparing(f -> f.file().provenance().originalName()))
        .map(f -> new StoreFile(selected.header().id(), selected.root(), f)).toList();
    if (members.stream().map(f -> f.facts().file().provenance().originalName()).distinct().count() != members.size())
      throw new IOException("SOURCE_UNAVAILABLE: duplicate original member names in this recording");
    if (!members.stream().map(f -> f.facts().file().sha256()).collect(java.util.stream.Collectors.toSet()).containsAll(reference.finalizedHashes()))
      throw new IOException("HISTORY_UNAVAILABLE: finalized member evidence no longer belongs to the anchored recording");
    for (var member : members) security.validate(member.facts().path());
    return new Recording(reference, identity, members, members.stream().noneMatch(f -> open(f.facts())));
  }
  public static boolean open(StoreCatalog.StoredFile file) {
    return file.session() != null && file.session().openCapture() != null
        && file.session().openCapture().path().equals(file.file().path());
  }

  /** The native reader owns one mapped prefix; the catalog does not open another mapping. */
  public ReadSnapshot openWpilog(Path path) throws IOException {
    var member = requireMember(path);
    if (!"wpilog".equals(member.facts().file().kind())) throw new IOException("Not a WPILOG catalog member: " + path);
    return logs.open(member.facts().path());
  }

  /** Union only manifested WPILOGs, on a caller-named anchor's recorded clock. */
  public SessionSnapshot openSession(String storeId, String sessionId, String anchorPath,
      SessionSnapshot.Overlap overlap) throws IOException {
    java.util.Objects.requireNonNull(overlap);
    var store = snapshot().stores().stream().filter(s -> s.header().id().equals(storeId)).findFirst()
        .orElseThrow(() -> new IOException("Unknown store " + storeId));
    var matches = store.sessions().stream().filter(s -> s.session().names(sessionId)).toList();
    if (matches.size() != 1) throw new IOException("The session id must name one canonical session or merge alias");
    var selected = matches.get(0);
    var files = store.allFiles().stream().filter(f -> f.manifestPath().equals(selected.path().resolve("session.json"))).toList();
    String collectingStore = store.header().mirror() && store.header().origin() != null
        ? store.header().origin().storeId() : storeId;
    var anchors = files.stream().filter(f -> f.file().kind().equals("wpilog") && f.file().path().equals(anchorPath)).toList();
    if (anchors.size() != 1) throw new IOException("The store/session/anchor must name one manifested WPILOG");
    // The old id must not broaden to another recording merely because a merge placed it
    // beside this one. Ordinary canonical session unions still honor recorded alignment.
    if (!selected.session().id().equals(sessionId)) {
      var anchor = anchors.get(0);
      files = files.stream().filter(f -> org.triplehelix.wpilogmcp.store.CaptureRecording.same(
          f.file().provenance(), anchor.file().provenance(), collectingStore)).toList();
      if (files.isEmpty()) throw new IOException("The merge alias has no proven captured recording at the requested anchor");
    }
    // A cold scan runs outside every monitor, but only one may allocate an as-yet uncharged
    // index per catalog. Reject concurrent admissions rather than queueing HTTP workers.
    if (!openingSession.compareAndSet(false, true))
      throw new ReadException(ReadProblem.Code.BUSY, "Another session is opening; retry after it finishes");
    try {
      var result = SessionSnapshot.open(this, files, anchors.get(0), overlap, collectingStore);
      synchronized (sessions) {
        if (!closed) { sessions.add(result); return result; }
      }
      result.close(); requireOpen(); throw new AssertionError();
    } finally { openingSession.set(false); }
  }

  /** REV clocks are transformed only by recorded offsets: the companion's own against the named
   * anchor, or the companion's and the anchor's against one shared reference, combined. Missing,
   * conflicting or corrupt association evidence stays unsynchronized and raw-readable. */
  public RecordedRevRead openRev(Path companion, Path anchor) throws IOException {
    return openRev(companion, anchor, null);
  }
  public RecordedRevRead openRev(Path companion, Path anchor, java.util.Set<String> signals) throws IOException {
    var inventory = inventory();
    var rev = requireMember(companion, inventory); var wpilog = requireMember(anchor, inventory);
    if (!"revlog".equals(rev.facts().file().kind()) || !"wpilog".equals(wpilog.facts().file().kind()))
      throw new IOException("Recorded REV reads require a REV companion and a WPILOG anchor");
    var store = inventory.snapshot().stores().stream().filter(s -> s.root().equals(rev.root())).findFirst();
    var alignment = store.isEmpty() || !rev.root().equals(wpilog.root()) ? java.util.Optional.<SyncResult>empty()
        : org.triplehelix.wpilogmcp.store.RecordedClocks.revAlignment(rev.facts(), wpilog.facts(), store.get().allFiles(),
            org.triplehelix.wpilogmcp.store.RecordedClocks.origin(store.get()));
    var result = RecordedRevRead.open(rev.facts().path(), roots, alignment.orElseGet(() -> SyncResult.failed(
        "No recorded REV association with " + wpilog.facts().path() + " in " + rev.facts().manifestPath())), revs, signals);
    if (closed) { result.close(); requireOpen(); }
    return result;
  }

  /** How many REV signals this catalog's reads have decoded, counted so a test can see a selection hold. */
  public long revSignalsDecoded() { return revs.decodedSignals(); }

  private StoreFile requireMember(Path path) throws IOException {
    return requireMember(path, inventory());
  }
  private StoreFile requireMember(Path path, Cached inventory) throws IOException {
    return resolve(path, inventory).orElseThrow(() -> new IOException("File is not in a readable configured store manifest: " + path));
  }
  private void requireOpen() throws ReadException {
    if (closed) throw new ReadException(ReadProblem.Code.CLOSED, "Store read catalog is closed");
  }
  boolean localSessions() { return ownsLogs; }
  void reserveIndex(long bytes) throws ReadException {
    synchronized (sessions) {
      if (bytes < 0 || bytes > sessionIndexLimit - sessionIndexBytes)
        throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT, "Session indexes need " + bytes
            + " more bytes; the catalog budget is " + sessionIndexLimit + " bytes (" + sessionIndexBytes
            + " held). Close another session or read files individually.");
      sessionIndexBytes += bytes;
    }
  }
  void releaseIndex(long bytes, SessionSnapshot session) {
    synchronized (sessions) { sessionIndexBytes -= bytes; sessions.remove(session); }
  }
  @Override public void close() throws IOException {
    List<SessionSnapshot> retiring;
    synchronized (sessions) { closed = true; retiring = List.copyOf(sessions); }
    cached = null; revs.close(); IOException failure = null;
    for (var session : retiring) try { session.close(); }
      catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
    try { if (ownsLogs) logs.close(); }
    catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
    if (failure != null) throw failure;
  }
}
