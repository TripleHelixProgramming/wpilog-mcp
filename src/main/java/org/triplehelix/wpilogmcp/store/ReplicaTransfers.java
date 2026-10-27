/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.triplehelix.wpilogmcp.config.MirrorConfig;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.log.LogFileAccess;
import org.triplehelix.wpilogmcp.store.ReplicaJournal.*;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

/**
 * One destination owner and two server-wide workers retain transfers through verification and
 * publication. Queue turns contain bounded manifests only. A response timeout revokes publication
 * but never releases a worker or reservation still held by an OS read. The future peer adapter
 * calls this owner after scope/finalization admission; no network write route bypasses it.
 */
final class ReplicaTransfers implements AutoCloseable {
  static final int BLOCK_BYTES = 65_536;
  /**
   * Two transfer jobs at a time across every mirror in the process, one per mirror. The limit counts
   * jobs, not threads: a job returns its permit before it reports its result, so the caller it wakes
   * can start the next job while the job's thread is still on its way back to the pool. A pool of two
   * threads refused that next job whenever both threads were still finishing, which rechecks lasting
   * a few milliseconds made common on a busy machine.
   */
  private static final java.util.concurrent.Semaphore PERMITS = new java.util.concurrent.Semaphore(2);
  private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 30, TimeUnit.SECONDS,
      new SynchronousQueue<>(), task -> { var t = new Thread(task, "replica-transfer"); t.setDaemon(true); return t; });
  private static final java.util.concurrent.ScheduledExecutorService DEADLINES = Executors.newScheduledThreadPool(2,
      task -> { var t = new Thread(task, "replica-deadline"); t.setDaemon(true); return t; });
  record Offer(ReplicaCatalog.Identity identity, String name, String sha256, long sizeBytes,
      String kind, String completionBasis, String supplier, boolean growing, ReplicaCatalog.Placement placement) {
    Offer(ReplicaCatalog.Identity identity, String name, String sha256, long sizeBytes,
        String kind, String completionBasis, String supplier, boolean growing) {
      this(identity, name, sha256, sizeBytes, kind, completionBasis, supplier, growing, null);
    }
    Offer {
      Objects.requireNonNull(identity); StoreFiles.component(name); ReplicaJournal.hash(sha256);
      if (sizeBytes < 0 || sizeBytes > org.triplehelix.wpilogmcp.log.MappedLogBytes.MAX_FILE_BYTES
          || !List.of("wpilog", "revlog", "clock_record").contains(kind)
          || !List.of("closed_capture", "completed_import", "closed_clock").contains(completionBasis)
          || supplier == null || supplier.isBlank() || supplier.length() > 1024)
        throw new IllegalArgumentException("Invalid finalized offer");
    }
  }
  @FunctionalInterface interface Source { InputStream open(long offset) throws IOException; }
  @FunctionalInterface interface Boundary { void reached(String name) throws IOException; }
  record Receipt(String requestId, String state, long catalogRevision, ReplicaCatalog.Entry file) {}
  private final LogStore store;
  private final AtomicLong verificationBytes = new AtomicLong();
  long verificationBytes() { return verificationBytes.get(); }
  private final org.triplehelix.wpilogmcp.read.server.VerifiedContentHashes hashes =
      org.triplehelix.wpilogmcp.read.server.VerifiedContentHashes.replicaCatalog(path -> {
        verificationBytes.addAndGet(Files.size(path)); return StoreFiles.hash(path);
      });
  private final AtomicBoolean busy = new AtomicBoolean(), closed = new AtomicBoolean();
  private final AtomicLong generation = new AtomicLong();
  private final ThreadLocal<Long> reconciliationEpoch = new ThreadLocal<>();
  private final AtomicReference<Job> active = new AtomicReference<>();
  private volatile MirrorCapacity capacity;
  private volatile MirrorCapacity.Snapshot accounted;
  private volatile MirrorConfig policy;
  private volatile Boundary boundary = name -> {};
  private volatile Duration idleTimeout = Duration.ofSeconds(30);
  ReplicaTransfers(LogStore store) { this.store = store; }
  void boundary(Boundary value) { boundary = value; }
  void idleTimeout(Duration value) { idleTimeout = value; }
  boolean active() { return busy.get(); }
  MirrorCapacity.Snapshot capacity() { return accounted; }
  private final java.util.concurrent.atomic.AtomicLong measurements = new java.util.concurrent.atomic.AtomicLong();
  /** How many times this owner measured its mirror, for tests that bound the cost of a reconciliation. */
  long measurements() { return measurements.get(); }
  private void measure(MirrorConfig requested) throws IOException {
    measurements.incrementAndGet();
    capacity = MirrorCapacity.measure(store.root(), store.replicaSecurity(), requested); accounted = capacity.snapshot();
  }

  private final class Job {
    final long generation;
    // Publication has one admission point. A cancellation after that point returns false,
    // rather than reporting a timeout for a manifest already admitted to atomic replacement.
    final AtomicInteger phase = new AtomicInteger(); // running, publishing, cancelled, done
    final AtomicLong progressed = new AtomicLong(System.nanoTime());
    volatile Thread thread;
    Header published;
    final CompletableFuture<Receipt> result = new CompletableFuture<>() {
      @Override public boolean cancel(boolean interrupt) {
        if (!phase.compareAndSet(0, 2)) return false;
        if (interrupt && thread != null) thread.interrupt();
        return super.cancel(interrupt);
      }
    };
    Job(long value) { generation = value; }
    void check() throws IOException {
      if (closed.get() || generation != ReplicaTransfers.this.generation.get() || phase.get() == 2)
        throw new IOException("CANCELLED: replica publication was revoked");
    }
    void progress() throws IOException { check(); progressed.set(System.nanoTime()); }
    void expire() {
      if (System.nanoTime() - progressed.get() < idleTimeout.toNanos() || !phase.compareAndSet(0, 2)) return;
      if (thread != null) thread.interrupt();
      result.completeExceptionally(new IOException("TRANSFER_TIMEOUT: worker remains charged until its I/O unwinds"));
    }
  }
  @FunctionalInterface private interface Work { Receipt run(Job job) throws Exception; }

  /** A new policy revokes old work immediately; it never changes or removes held payloads. */
  void cancel() {
    generation.incrementAndGet(); var job = active.get(); if (job != null) job.result.cancel(true);
  }
  /** A reconciliation that could not start: another job holds the owner, or it is closing. Nothing was
   * started and a later attempt may succeed, so a caller retries instead of reporting a failure. */
  static final class NotStarted extends IOException {
    NotStarted(String message) { super(message); }
  }
  /** The coordinator carries one cancellation epoch through every child job, including jobs
   * started after a network wait. Cancellation cannot accidentally grant the old loop a new epoch. */
  long reconciliationEpoch(MirrorConfig requested) throws NotStarted {
    if (!busy.compareAndSet(false, true)) throw new NotStarted("BUSY: replica owner is occupied; retry");
    long epoch;
    try {
      if (closed.get()) throw new NotStarted("CANCELLED: replica owner is closed");
      if (!requested.equals(policy)) { policy = requested; generation.incrementAndGet(); }
      epoch = generation.get();
    } finally { busy.set(false); }
    return epoch;
  }
  <T> T reconcile(long epoch, java.util.concurrent.Callable<T> work) throws Exception {
    reconciliationEpoch.set(epoch);
    try { return work.call(); } finally { reconciliationEpoch.remove(); }
  }
  void checkReconciliation() throws IOException {
    var expected = reconciliationEpoch.get();
    if (closed.get() || expected == null || expected != generation.get()) throw new IOException("CANCELLED: reconciliation was revoked");
  }
  private CompletableFuture<Receipt> start(MirrorConfig requested, Work work) { return start(requested, true, work); }

  /**
   * One transfer at a time, under the mirror's owner lock. A job that may write measures the mirror
   * before and after its work, so its reservations rest on current usage. A held file's recheck
   * only reads and hashes, so it measures nothing unless the journal makes it recover first:
   * measuring the whole mirror twice per recheck made a cold reconciliation quadratic in the
   * mirror's files, and 300 held files took over ten minutes on a Windows runner.
   */
  private CompletableFuture<Receipt> start(MirrorConfig requested, boolean accounts, Work work) {
    if (closed.get()) return CompletableFuture.failedFuture(new IOException("Replica owner is closed"));
    if (!store.root().equals(requested.folder().toAbsolutePath().normalize()))
      return CompletableFuture.failedFuture(new IOException("Mirror folder differs from selected owner"));
    if (!busy.compareAndSet(false, true)) return CompletableFuture.failedFuture(new IOException("BUSY: one transfer per mirror; retry"));
    var expected = reconciliationEpoch.get();
    if (expected != null && (expected != generation.get() || !requested.equals(policy))) {
      busy.set(false); return CompletableFuture.failedFuture(new IOException("CANCELLED: reconciliation policy was revoked"));
    }
    long admitted = expected == null ? generation.get() : expected;
    if (!requested.equals(policy)) { policy = requested; admitted = generation.incrementAndGet(); }
    var job = new Job(admitted); active.set(job);
    if (!PERMITS.tryAcquire()) {
      active.compareAndSet(job, null); busy.set(false);
      job.result.completeExceptionally(new IOException("BUSY: two replica workers are occupied; retry"));
      return job.result;
    }
    try {
      WORKERS.execute(() -> {
        job.thread = Thread.currentThread(); Receipt value = null; Throwable failure = null; boolean measures = accounts;
        long period = Math.max(1, Math.min(1000, idleTimeout.toMillis()));
        var deadline = DEADLINES.scheduleWithFixedDelay(job::expire, period, period, TimeUnit.MILLISECONDS);
        try {
          job.check();
          var io = io(); var directory = io.check(store.root().resolve(".replicas")); Files.createDirectories(directory);
          var lockPath = io.check(directory.resolve("owner.lock"));
          if (Files.isSymbolicLink(lockPath)) throw new IOException("Replica owner lock cannot be a symlink");
          try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            java.nio.channels.FileLock lock;
            try { lock = channel.tryLock(); } catch (java.nio.channels.OverlappingFileLockException held) { lock = null; }
            if (lock == null) throw new IOException("BUSY: another process owns this mirror's transfer");
            try (var owned = lock) {
              if (measures) measure(requested);
              var recovery = ReplicaJournal.read(store.root(), io);
              if (recovery.pending() != null || !recovery.retired().isEmpty()) {
                if (!measures) { measures = true; measure(requested); } // Recovery writes, whichever job finds it.
                // Restart lost in-memory reservations, not the cost of replacing recovery metadata.
                try (var charge = capacity.reserveMetadata(Math.multiplyExact(3, Math.addExact(bytes(recovery), bytes(header()))))) {
                  accounted = capacity.snapshot(); recoverCommitted(job); retire(job); job.check();
                }
                measure(requested);
              }
              value = work.run(job);
            }
          }
        } catch (Throwable thrown) { failure = thrown; }
        finally {
          try {
            deadline.cancel(false); Thread.interrupted(); job.phase.set(3);
            if (measures) try { measure(requested); }
            catch (IOException ignored) { /* Retain the last conservative reservation snapshot on an unreadable catalog. */ }
            active.compareAndSet(job, null); busy.set(false);
          } finally { PERMITS.release(); } // Before the result wakes its caller, who may start the next job at once.
        }
        // No lease, file lock or accounting monitor is inherited by observers or continuations.
        if (job.published != null) store.replicaPublished(job.published);
        if (failure == null) job.result.complete(value); else job.result.completeExceptionally(failure);
      });
    } catch (java.util.concurrent.RejectedExecutionException full) {
      PERMITS.release(); active.compareAndSet(job, null); busy.set(false);
      job.result.completeExceptionally(new IOException("BUSY: two replica workers are occupied; retry", full));
    }
    return job.result;
  }

  CompletableFuture<Receipt> transfer(MirrorConfig policy, String requestId, Offer offer, Source source) {
    ReplicaJournal.uuid(requestId); Objects.requireNonNull(offer); Objects.requireNonNull(source);
    String fingerprint = hashText("transfer:" + StoreFiles.JSON.toJson(offer));
    return start(policy, job -> transfer(job, requestId, fingerprint, offer, source));
  }

  /** Reconciliation resumes the exact pending request, but a completed pass needs fresh admission
   * rather than replaying a historical HTTP receipt as proof that held bytes are still intact. */
  String reconciliationRequest(Offer offer) throws IOException {
    var pending = ReplicaJournal.read(store.root(), io()).pending();
    if (pending != null) {
      if (!pending.fingerprint().equals(hashText("transfer:" + StoreFiles.JSON.toJson(offer))))
        throw new IOException("RESUME_REQUIRED: another immutable candidate is pending");
      return pending.requestId();
    }
    return UUID.randomUUID().toString();
  }

  private Receipt transfer(Job job, String request, String fingerprint, Offer offer, Source source) throws Exception {
    var header = header(); var state = ReplicaJournal.read(store.root(), io());
    var cached = receipt(state, request, fingerprint); if (cached != null) return cached;
    requireMirror(header);
    if (offer.growing()) throw new IOException("NOT_FINALIZED: growing member cannot be replicated");
    if (!offer.identity().masterStoreId().equals(header.origin().storeId())) throw new IOException("FOREIGN_MASTER: offer does not belong to this mirror");
    // Legacy cleanup knows exact bytes but not a logical identity. Blocking those bytes is a
    // content exclusion only; it neither assigns identity nor establishes master authority.
    if (header.replicas().exclusions().stream().anyMatch(e -> offer.identity().equals(e.identity())
        || e.identity() == null && offer.sha256().equals(e.sha256())))
      throw new IOException("EXCLUDED: explicitly reinclude this file version before acquiring it");
    var incumbent = header.replicas().files().stream().filter(f -> offer.identity().equals(f.identity())).findFirst().orElse(null);
    var confirmation = state.confirmations().stream().filter(c -> c.record().identity().equals(offer.identity())).findFirst().orElse(null);
    if (confirmation != null && !matches(offer, confirmation.record())) throw new IOException("MASTER_MISMATCH: offered bytes contradict established master evidence");
    boolean repair = false;
    if (incumbent != null && incumbent.sha256().equals(offer.sha256()) && incumbent.sizeBytes() == offer.sizeBytes()) {
      // Reuse is still a real content admission: historical confirmation cannot mask corruption.
      try {
        verifyHeld(incumbent); job.check();
        return saveReceipt(job, request, fingerprint, "already_present", incumbent);
      } catch (IOException corrupt) {
        if (confirmation == null || !corrupt.getMessage().startsWith("CORRUPT_GENERATION")) throw corrupt;
        repair = true; // Only the established immutable master hash can authorize a repair.
      }
    }
    if (incumbent != null && !repair && (incumbent.verificationBasis().equals("verified") || confirmation == null))
      throw new IOException(incumbent.verificationBasis().equals("verified") ? "AUTHORITY_CONFLICT: verified generation is immutable" : "PEER_CONFLICT: first provisional candidate retained");
    if (state.retired().size() + (incumbent == null ? 0 : 1) > ReplicaJournal.MAX_RETIRED)
      throw new IOException("METADATA_LIMIT: deferred retirement is full; held files preserved");
    var pending = state.pending();
    if (pending != null && (!pending.requestId().equals(request) || !pending.fingerprint().equals(fingerprint)))
      throw new IOException("RESUME_REQUIRED: retry or explicitly discard the existing unpublished request");
    if (pending == null) {
      if (Files.exists(io().resolve(store.root(), ".replicas/staging/" + request + ".part")))
        throw new IOException("DESTINATION_OCCUPIED: an unowned file occupies the requested staging name");
      var target = new ReplicaCatalog.Entry(incumbent == null ? UUID.randomUUID().toString() : incumbent.key(), offer.identity(),
          ".replicas/objects/" + UUID.randomUUID() + "/" + offer.name(), offer.sha256(), offer.sizeBytes(), offer.kind(),
          offer.placement() == null ? null : offer.placement().sessionId(),
          offer.completionBasis(), confirmation == null ? "provisional" : "verified", true, confirmation, null, offer.placement());
      pending = new Intent(request, fingerprint, policyKey(), header.id(), header.replicas().epoch(), header.replicas().revision(), "transfer",
          target, incumbent == null ? List.of() : List.of(incumbent), List.of(), ".replicas/staging/" + request + ".part", 0, hashText(""), false);
    }
    if (!pending.storeId().equals(header.id()) || !pending.policyKey().equals(policyKey()) || !pending.catalogEpoch().equals(header.replicas().epoch())
        || pending.catalogRevision() != header.replicas().revision()) throw new IOException("STALE_INTENT: discard unpublished work before retrying under the new policy/catalog");
    var intent = pending; var stage = io().resolve(store.root(), intent.staging());
    var destination = io().resolve(store.root(), intent.target().path());
    if (Files.exists(destination) && (!Files.exists(stage) || !Files.isSameFile(stage, destination)))
      throw new IOException("DESTINATION_OCCUPIED: the planned generation is not the owned staging object");
    long heldStage = Files.exists(stage) ? Files.size(stage) : Files.exists(destination) ? Files.size(destination) : 0;
    long needed = Math.max(0, offer.sizeBytes() - heldStage);
    Files.createDirectories(io().check(stage.getParent()));
    // Uncheckpointed tail bytes will be truncated and rewritten. Reserve from the durable
    // prefix so another writer cannot spend those freed bytes before this transfer reuses them.
    long physicalNeeded = Files.exists(destination) ? 0 : Math.max(0, offer.sizeBytes() - intent.flushed());
    try (var physical = store.reserveDiskCopy(stage.getParent(), physicalNeeded);
        var payload = capacity.reserve(needed); var metadata = capacity.reserveMetadata(metadataBudget(header, state, intent))) {
      accounted = capacity.snapshot();
      try {
        var admitted = intent;
        turn(job, files -> {
          checkRevision(header, files); ReplicaJournal.write(store.root(), files, state.pending(admitted)); return null;
        });
        boundary.reached("intent"); job.progress();
        if (!Files.exists(destination)) {
          Files.createDirectories(io().check(stage.getParent()));
          if (!Files.exists(stage)) Files.createFile(stage);
          // Only journaled flushed bytes are reusable. Uncheckpointed tail bytes remain private.
          if (Files.size(stage) < intent.flushed() || !prefixHash(stage, intent.flushed()).equals(intent.prefixSha256()))
            throw new IOException("CORRUPT_PARTIAL: staging prefix differs from its durable checkpoint");
          try (var output = FileChannel.open(stage, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            output.truncate(intent.flushed()); output.position(intent.flushed());
            var digest = digest(); hashInto(stage, intent.flushed(), digest); long offset = intent.flushed();
            if (offset < offer.sizeBytes()) try (var input = source.open(offset)) {
              byte[] block = new byte[BLOCK_BYTES];
              while (offset < offer.sizeBytes()) {
                job.check(); long started = System.nanoTime();
                int count = input.read(block, 0, (int) Math.min(block.length, offer.sizeBytes() - offset));
                job.check(); if (count <= 0) throw new IOException("INCOMPLETE_TRANSFER: offered bytes ended early");
                var bytes = ByteBuffer.wrap(block, 0, count); while (bytes.hasRemaining()) output.write(bytes);
                output.force(true); boundary.reached("chunk_flushed"); job.check(); digest.update(block, 0, count); offset += count;
                String prefix;
                try { prefix = HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest()); }
                catch (CloneNotSupportedException unsupported) { prefix = prefixHash(stage, offset); }
                intent = intent.progress(offset, prefix, false, intent.target()); var checkpoint = intent;
                turn(job, files -> { var current = ReplicaJournal.read(store.root(), files); requirePending(current, request);
                  ReplicaJournal.write(store.root(), files, current.pending(checkpoint)); return null; });
                boundary.reached("checkpoint"); job.progress(); throttle(job, count, started);
              }
            }
          }
          if (Files.size(stage) != offer.sizeBytes() || !StoreFiles.hash(stage).equals(offer.sha256())) throw new IOException("HASH_MISMATCH: staged bytes do not match the offer");
          var descriptor = inspect(stage, intent.target(), offer);
          var target = intent.target(); target = new ReplicaCatalog.Entry(target.key(), target.identity(), target.path(), target.sha256(), target.sizeBytes(),
              target.kind(), target.sessionId(), target.completionBasis(), target.verificationBasis(), true, target.confirmation(), descriptor, target.placement());
          intent = intent.progress(offer.sizeBytes(), offer.sha256(), true, target); var verified = intent;
          turn(job, files -> { var current = ReplicaJournal.read(store.root(), files); requirePending(current, request);
            ReplicaJournal.write(store.root(), files, current.pending(verified)); return null; });
          boundary.reached("verified"); job.progress();
          Files.createDirectories(io().check(destination.getParent()));
          // A hard link is an atomic, no-replace publication of already forced bytes. Unsupported
          // filesystems refuse safely; no fallback ever overwrites a generation or copies in place.
          Files.createLink(destination, stage); boundary.reached("object");
        }
        if (!intent.ready()) throw new IOException("Invalid unverified generation in recovery journal");
        verifyHeld(intent.target()); job.progress();
        boundary.reached("before_manifest"); publish(job, intent); boundary.reached("manifest");
        dropStagingLink(intent); var completed = complete(job, intent); boundary.reached("receipt");
        retire(job); return completed;
      } finally { payload.publish(); }
    }
  }

  /** Only a direct, configured-master adapter may call this port. A peer's relayed receipt is
   * never passed here; its authority remains unestablished. M6 owns that wire admission. */
  CompletableFuture<Receipt> confirm(MirrorConfig policy, String request, ReplicaCatalog.Confirmation confirmation) {
    ReplicaJournal.uuid(request); String fingerprint = hashText("confirm:" + StoreFiles.JSON.toJson(confirmation));
    return start(policy, job -> {
      var header = header(); requireMirror(header); var state = ReplicaJournal.read(store.root(), io());
      var cached = receipt(state, request, fingerprint); if (cached != null) return cached;
      if (!policy.origin().equals(confirmation.obtainedFrom()) || !Objects.equals(header.origin().storeId(), confirmation.record().identity().masterStoreId()))
        throw new IOException("FOREIGN_MASTER: receipt did not come directly from this mirror's master");
      var record = confirmation.record();
      for (var old : state.confirmations()) if (old.record().identity().equals(record.identity())
          && (!old.record().sha256().equals(record.sha256()) || old.record().sizeBytes() != record.sizeBytes()
              || !old.record().finalizationBasis().equals(record.finalizationBasis())))
        throw new IOException("AUTHORITY_CONFLICT: established version definition is immutable");
      if (state.pending() != null && state.pending().operation().equals("transfer")
          && state.confirmations().stream().anyMatch(c -> c.record().identity().equals(record.identity())
              && c.record().sha256().equals(record.sha256()) && c.record().sizeBytes() == record.sizeBytes()))
        return new Receipt(request, "confirmation_retained", header.replicas().revision(), null);
      var entries = new ArrayList<>(header.replicas().files()); ReplicaCatalog.Entry promoted = null;
      for (int i = 0; i < entries.size(); i++) {
        var file = entries.get(i); if (!record.identity().equals(file.identity())) continue;
        boolean matches = file.sha256().equals(record.sha256()) && file.sizeBytes() == record.sizeBytes()
            && file.completionBasis().equals(record.finalizationBasis());
        if (!matches && file.verificationBasis().equals("verified")) throw new IOException("AUTHORITY_CONFLICT: verified generation retained");
        if (matches) {
          String basis = "verified"; boolean intact = true;
          try { verifyHeld(file); }
          catch (IOException corrupt) {
            if (!corrupt.getMessage().startsWith("CORRUPT_GENERATION")) throw corrupt;
            intact = false; basis = file.verificationBasis(); // Retain authority history, never certify the corrupt current bytes.
          }
          if (intact && state.pending() == null && file.verificationBasis().equals("verified")
              && policy.origin().equals(file.confirmation().obtainedFrom()))
            return saveReceipt(job, request, fingerprint, "confirmation_retained", file);
          promoted = new ReplicaCatalog.Entry(file.key(), file.identity(), file.path(), file.sha256(), file.sizeBytes(), file.kind(),
              file.sessionId(), file.completionBasis(), basis, true, confirmation, file.descriptor(), file.placement()); entries.set(i, promoted);
        } else {
          // The old bytes remain readable until replacement fits, but their disproved authority
          // claim is durable and travels with each newly admitted read of that generation.
          promoted = new ReplicaCatalog.Entry(file.key(), file.identity(), file.path(), file.sha256(), file.sizeBytes(), file.kind(),
              file.sessionId(), file.completionBasis(), "provisional", true, confirmation, file.descriptor(), file.placement()); entries.set(i, promoted);
        }
      }
      var confirmations = new ArrayList<>(state.confirmations()); confirmations.removeIf(c -> c.record().identity().equals(record.identity())); confirmations.add(confirmation);
      if (state.pending() != null) {
        var pending = state.pending();
        if (pending.requestId().equals(request) && pending.fingerprint().equals(fingerprint)) return control(job, header, state, pending);
        throw new IOException("RESUME_REQUIRED: finish or discard the previous intent");
      }
      var prior = promoted == null ? List.<ReplicaCatalog.Entry>of() : header.replicas().files().stream().filter(f -> record.identity().equals(f.identity())).toList();
      var catalog = header.replicas();
      var intent = new Intent(request, fingerprint, policyKey(), header.id(), catalog.epoch(), catalog.revision(), "confirm", promoted, prior, List.of(), null, 0, null, true);
      var next = new State(1, null, state.retired(), state.receipts(), confirmations, state.observations(), state.coverage());
      return control(job, header, next, intent);
    });
  }
  /** Pins share the one metadata writer and its peak disk admission; they never queue behind
   * an occupied transfer or consume bytes that worker already reserved. */
  CompletableFuture<Receipt> pin(MirrorConfig policy, String session, boolean pinned) {
    StoreFiles.component(session);
    return start(policy, job -> {
      var before = header(); requireMirror(before);
      if (ReplicaJournal.read(store.root(), io()).pending() != null)
        throw new IOException("RESUME_REQUIRED: finish or discard the unpublished request before changing pins");
      var updated = LogStore.pinnedHeader(before, session, pinned);
      try (var charge = capacity.reserveMetadata(Math.multiplyExact(2, bytes(updated)))) {
        accounted = capacity.snapshot();
        turn(job, files -> {
          checkRevision(before, files);
          if (!job.phase.compareAndSet(0, 1)) throw new IOException("CANCELLED: publication permit revoked");
          files.writeDurable(store.root().resolve("store.json"), updated); job.published = updated; return null;
        });
        return new Receipt(null, pinned ? "pinned" : "unpinned", updated.replicas().revision(), null);
      }
    });
  }
  CompletableFuture<Receipt> cleanup(MirrorConfig policy, String request, ReplicaCatalog.CleanupPreview preview) {
    return cleanup(policy, request, preview.storeId(), preview.catalogEpoch(), preview.catalogRevision(),
        policyKey(policy), preview.files().stream().map(ReplicaCatalog.Entry::key).toList());
  }
  CompletableFuture<Receipt> cleanup(MirrorConfig policy, String request, String storeId, String epoch,
      long revision, String selectedPolicy, List<String> keys) {
    ReplicaJournal.uuid(request); ReplicaJournal.uuid(storeId); ReplicaJournal.uuid(epoch); ReplicaJournal.hash(selectedPolicy);
    if (keys.isEmpty() || keys.size() > ReplicaCatalog.MAX_CLEANUP || keys.stream().distinct().count() != keys.size()) throw new IllegalArgumentException("Select 1 to 32 distinct replica keys");
    keys.forEach(ReplicaJournal::uuid);
    String fingerprint = hashText("cleanup:" + storeId + ":" + epoch + ":" + revision + ":" + selectedPolicy + ":" + StoreFiles.JSON.toJson(keys));
    return start(policy, job -> {
      var header = header(); requireMirror(header); var state = ReplicaJournal.read(store.root(), io());
      var cached = receipt(state, request, fingerprint); if (cached != null) return cached;
      if (!header.id().equals(storeId) || !header.replicas().epoch().equals(epoch)
          || header.replicas().revision() != revision || !selectedPolicy.equals(policyKey()))
        throw new IOException("STALE_PREVIEW: selected files, catalog or policy changed");
      var preview = header.replicas().preview(header.id(), keys);
      if (state.pending() != null) {
        var pending = state.pending();
        if (pending.requestId().equals(request) && pending.fingerprint().equals(fingerprint)) return control(job, header, state, pending);
        throw new IOException("RESUME_REQUIRED: finish or discard the unpublished request first");
      }
      // Constructing the future catalog proves exclusion capacity before any payload retires.
      header.replicas().excluding(preview);
      var intent = new Intent(request, fingerprint, policyKey(), header.id(), epoch, revision, "cleanup", null,
          preview.files(), preview.acquisitionExclusions(), null, 0, null, true);
      return control(job, header, state, intent);
    });
  }
  CompletableFuture<Receipt> reinclude(MirrorConfig policy, String request, String epoch, long revision, List<String> keys) {
    ReplicaJournal.uuid(request); ReplicaJournal.uuid(epoch);
    if (keys.isEmpty() || keys.size() > ReplicaCatalog.MAX_CLEANUP || keys.stream().distinct().count() != keys.size()) throw new IllegalArgumentException("Select 1 to 32 distinct exclusion keys");
    String fingerprint = hashText("reinclude:" + epoch + ":" + revision + ":" + StoreFiles.JSON.toJson(keys));
    return start(policy, job -> {
      var header = header(); requireMirror(header); var state = ReplicaJournal.read(store.root(), io());
      var cached = receipt(state, request, fingerprint); if (cached != null) return cached;
      if (!header.replicas().epoch().equals(epoch) || header.replicas().revision() != revision) throw new IOException("STALE_PREVIEW: catalog changed");
      if (state.pending() != null) {
        var pending = state.pending();
        if (pending.requestId().equals(request) && pending.fingerprint().equals(fingerprint)) return control(job, header, state, pending);
        throw new IOException("RESUME_REQUIRED: unpublished transfer must finish first");
      }
      var exclusions = keys.stream().map(key -> header.replicas().exclusions().stream().filter(e -> e.key().equals(key)).findFirst()
          .orElseThrow(() -> new IllegalArgumentException("Unknown exclusion key"))).toList();
      return control(job, header, state, new Intent(request, fingerprint, policyKey(), header.id(), epoch, revision, "reinclude", null, List.of(), exclusions, null, 0, null, true));
    });
  }
  private Receipt control(Job job, Header header, State state, Intent intent) throws Exception {
    if (!header.id().equals(intent.storeId()) || !header.replicas().epoch().equals(intent.catalogEpoch()) || header.replicas().revision() != intent.catalogRevision()
        || !policyKey().equals(intent.policyKey())) throw new IOException("STALE_INTENT: catalog or policy changed");
    if (state.retired().size() + intent.previous().size() > ReplicaJournal.MAX_RETIRED)
      throw new IOException("METADATA_LIMIT: deferred retirement is full; no payload removed");
    try (var charge = capacity.reserveMetadata(metadataBudget(header, state, intent))) {
      accounted = capacity.snapshot();
      turn(job, io -> { checkRevision(header, io); ReplicaJournal.write(store.root(), io, state.pending(intent)); return null; });
      boundary.reached("intent"); job.progress(); boundary.reached("before_manifest"); publish(job, intent); boundary.reached("manifest");
      var answer = complete(job, intent); boundary.reached("receipt"); retire(job); return answer;
    }
  }
  /** Only the exact unpublished request can discard its private bytes. An active worker must
   * unwind first, and a committed request is recovered instead of being treated as staging. */
  CompletableFuture<Receipt> discard(MirrorConfig policy, String request) {
    ReplicaJournal.uuid(request);
    return start(policy, job -> {
      var state = ReplicaJournal.read(store.root(), io()); var intent = state.pending();
      if (intent == null || !intent.requestId().equals(request)) throw new IOException("No such unpublished request");
      var header = header();
      if (intent.target() != null && header.replicas().files().contains(intent.target())) throw new IOException("Published generation cannot be discarded");
      if (intent.operation().equals("transfer")) {
        var stage = io().resolve(store.root(), intent.staging()); var destination = io().resolve(store.root(), intent.target().path());
        if (Files.exists(destination) && Files.exists(stage) && Files.isSameFile(stage, destination)) {
          try (var lease = LogFileAccess.move(List.of(destination))) { Files.delete(destination); }
          removeEmptyGeneration(destination);
        }
        if (Files.exists(stage)) try (var lease = LogFileAccess.move(List.of(stage))) { Files.delete(stage); }
      }
      turn(job, io -> { var current = ReplicaJournal.read(store.root(), io); requirePending(current, request);
        ReplicaJournal.write(store.root(), io, current.pending(null)); return null; });
      return new Receipt(request, "discarded", header.replicas().revision(), null);
    });
  }

  private static long metadataBudget(Header header, State state, Intent intent) throws IOException {
    // Includes old durable metadata plus two future header/state images and transient atomic
    // replacement files. This conservative admission is independent of serialized heap usage.
    return Math.addExact(16_384, Math.multiplyExact(3L, Math.addExact(bytes(header), bytes(state.pending(intent)))));
  }
  private static long bytes(Object value) throws IOException {
    return value instanceof Header header ? ReplicaCatalogFiles.metadataBytes(header)
        : StoreFiles.JSON.toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
  }
  private String policyKey() { return policyKey(policy); }
  static String policyKey(MirrorConfig policy) { return hashText(StoreJson.JSON.toJson(policy)); }
  private StoreFiles io() throws IOException { return new StoreFiles(store.root(), store.replicaSecurity()); }
  private Header header() throws IOException { return io().read(store.root().resolve("store.json"), Header.class); }
  private void requireMirror(Header header) throws IOException {
    if (!header.mirror() || header.replicas() == null) throw new IOException("MIGRATION_REQUIRED: format-2 mirror required");
    if (header.origin() == null || !header.origin().url().equals(policy.origin())) throw new IOException("FOREIGN_MASTER: mirror policy changed");
  }
  private <T> T turn(Job job, LogStore.CaptureOperation<T> operation) throws Exception {
    return store.replicaTurn(files -> { job.check(); return operation.run(files); }).get();
  }
  private void checkRevision(Header before, StoreFiles files) throws IOException {
    var now = files.read(store.root().resolve("store.json"), Header.class);
    if (!now.equals(before)) throw new IOException("STALE_INTENT: store changed before admission");
  }
  private static void requirePending(State state, String request) throws IOException {
    if (state.pending() == null || !state.pending().requestId().equals(request)) throw new IOException("STALE_INTENT: recovery ownership changed");
  }
  private static Receipt receipt(State state, String request, String fingerprint) throws IOException {
    for (var item : state.receipts()) if (item.requestId().equals(request)) {
      if (!item.fingerprint().equals(fingerprint)) throw new IOException("REQUEST_CONFLICT: request UUID reused with different facts");
      return new Receipt(request, item.state(), item.catalogRevision(), item.file());
    }
    return null;
  }
  private Receipt saveReceipt(Job job, String request, String fingerprint, String outcome, ReplicaCatalog.Entry entry) throws Exception {
    var before = ReplicaJournal.read(store.root(), io()); var header = header();
    var receipt = new ReplicaJournal.Receipt(request, fingerprint, outcome, header.replicas().revision(), entry);
    var next = before.withReceipt(receipt);
    try (var charge = capacity.reserveMetadata(Math.multiplyExact(2, bytes(next)))) {
      accounted = capacity.snapshot();
      return turn(job, files -> {
        if (!ReplicaJournal.read(store.root(), files).equals(before)) throw new IOException("STALE_INTENT: receipt owner changed");
        checkRevision(header, files); ReplicaJournal.write(store.root(), files, next);
        return new Receipt(request, outcome, receipt.catalogRevision(), entry);
      });
    }
  }
  private void publish(Job job, Intent intent) throws Exception {
    turn(job, files -> {
      var header = files.read(store.root().resolve("store.json"), Header.class); requireMirror(header);
      var catalog = header.replicas(); var state = ReplicaJournal.read(store.root(), files); requirePending(state, intent.requestId());
      if (!header.id().equals(intent.storeId()) || !catalog.epoch().equals(intent.catalogEpoch()) || catalog.revision() != intent.catalogRevision()
          || !intent.policyKey().equals(policyKey()) || !catalog.files().containsAll(intent.previous()))
        throw new IOException("STALE_INTENT: policy, incumbent or catalog changed");
      var next = new ArrayList<>(catalog.files()); next.removeAll(intent.previous());
      if (intent.target() != null) next.add(intent.target());
      var excluded = new ArrayList<>(catalog.exclusions());
      if (intent.operation().equals("cleanup")) for (var exclusion : intent.exclusions()) if (!excluded.contains(exclusion)) excluded.add(exclusion);
      if (intent.operation().equals("reinclude")) excluded.removeAll(intent.exclusions());
      var updated = header.withReplicas(new ReplicaCatalog(1, catalog.epoch(), Math.addExact(catalog.revision(), 1), next, catalog.includedSessions(), excluded));
      // This is the cancellation/publication linearization point, before the bounded atomic
      // manifest replacement. No monitor is held across a filesystem operation.
      if (!job.phase.compareAndSet(0, 1)) throw new IOException("CANCELLED: publication permit revoked");
      files.writeDurable(store.root().resolve("store.json"), updated); job.published = updated; return null;
    });
  }
  private Receipt complete(Job job, Intent intent) throws Exception {
    return turn(job, files -> {
      var header = files.read(store.root().resolve("store.json"), Header.class); var state = ReplicaJournal.read(store.root(), files);
      var existing = receipt(state, intent.requestId(), intent.fingerprint()); if (existing != null) return existing;
      requirePending(state, intent.requestId());
      var receipt = new ReplicaJournal.Receipt(intent.requestId(), intent.fingerprint(), intent.operation().equals("transfer") ? "published"
          : intent.operation().equals("confirm") ? intent.target() == null ? "master_recorded" : "confirmed" : intent.operation(),
          header.replicas().revision(), intent.target());
      ReplicaJournal.write(store.root(), files, state.completed(receipt, intent.operation().equals("confirm") ? List.of() : intent.previous()));
      return new Receipt(receipt.requestId(), receipt.state(), receipt.catalogRevision(), receipt.file());
    });
  }
  private void recoverCommitted(Job job) throws Exception {
    var state = ReplicaJournal.read(store.root(), io()); var pending = state.pending(); if (pending == null) return;
    var header = header(); requireMirror(header); var catalog = header.replicas();
    boolean committed = header.id().equals(pending.storeId()) && catalog.epoch().equals(pending.catalogEpoch()) && catalog.revision() > pending.catalogRevision()
        && (pending.target() != null ? catalog.files().contains(pending.target())
            : pending.operation().equals("cleanup") ? !pending.previous().isEmpty() && pending.previous().stream().noneMatch(catalog.files()::contains)
                && catalog.exclusions().containsAll(pending.exclusions())
            : pending.operation().equals("confirm") || pending.exclusions().stream().noneMatch(catalog.exclusions()::contains));
    if (committed) { dropStagingLink(pending); complete(job, pending); job.published = header; }
  }
  private void retire(Job job) throws Exception {
    var state = ReplicaJournal.read(store.root(), io());
    for (var entry : state.retired()) {
      job.check(); var path = io().resolve(store.root(), entry.path());
      if (Files.exists(path)) {
        try (var reservation = LogFileAccess.reserveMove(List.of(path))) {
          if (!store.replicaLogManager().release(path, Duration.ZERO).released()) continue;
          if (Files.size(path) != entry.sizeBytes() || !StoreFiles.hash(path).equals(entry.sha256()))
            continue; // Quarantine changed retired bytes, still charged and bounded, without blocking a valid repair.
          Files.delete(path); boundary.reached("retired");
        }
      }
      removeEmptyGeneration(path);
      turn(job, files -> {
        var current = ReplicaJournal.read(store.root(), files); var kept = new ArrayList<>(current.retired()); kept.remove(entry);
        ReplicaJournal.write(store.root(), files, new State(1, current.pending(), kept, current.receipts(), current.confirmations(), current.observations(), current.coverage())); return null;
      });
    }
  }
  private void dropStagingLink(Intent intent) throws IOException {
    if (!intent.operation().equals("transfer")) return;
    var stage = io().resolve(store.root(), intent.staging()); var destination = io().resolve(store.root(), intent.target().path());
    if (Files.exists(stage)) {
      if (!Files.isSameFile(stage, destination)) throw new IOException("STAGING_CHANGED: private link is no longer the published object");
      Files.delete(stage);
    }
  }
  private void removeEmptyGeneration(Path path) throws IOException {
    if (!ReplicaJournal.generation(StoreFiles.relative(store.root(), path))) return;
    try { Files.deleteIfExists(io().check(path.getParent())); }
    catch (java.nio.file.DirectoryNotEmptyException unmanaged) { /* A manually placed neighbor is preserved. */ }
  }
  CompletableFuture<Receipt> recover(MirrorConfig policy) {
    return start(policy, job -> new Receipt(null, "recovered", header().replicas().revision(), null));
  }

  CompletableFuture<Receipt> observe(MirrorConfig policy, ReplicaJournal.Observation observation) {
    return metadata(policy, state -> state.observed(observation));
  }
  CompletableFuture<Receipt> coverage(MirrorConfig policy, ReplicaJournal.Coverage coverage) {
    return metadata(policy, state -> state.coverage(coverage));
  }
  private CompletableFuture<Receipt> metadata(MirrorConfig policy, java.util.function.UnaryOperator<State> change) {
    return start(policy, job -> {
      var before = header(); requireMirror(before); var state = ReplicaJournal.read(store.root(), io()); var next = change.apply(state);
      try (var charge = capacity.reserveMetadata(Math.multiplyExact(2, bytes(next)))) {
        accounted = capacity.snapshot();
        turn(job, files -> {
          checkRevision(before, files);
          if (!job.phase.compareAndSet(0, 1)) throw new IOException("CANCELLED: replica observation revoked");
          ReplicaJournal.write(store.root(), files, next); return null;
        });
        return new Receipt(null, "observed", before.replicas().revision(), null);
      }
    });
  }

  /** Only the configured endpoint may establish a previously unbound master. Once bound,
   * neither rediscovery nor an address change may replace that identity. */
  CompletableFuture<Receipt> master(MirrorConfig policy, String master, String synchronizedAt) {
    ReplicaJournal.uuid(master);
    return start(policy, job -> {
      var before = header(); requireMirror(before); var origin = before.origin();
      if (origin.storeId() != null && !master.equals(origin.storeId())) throw new IOException("FOREIGN_MASTER: configured master identity changed");
      var next = before.withOrigin(new MirrorOrigin(master, origin.url(), synchronizedAt == null ? origin.lastSync() : synchronizedAt,
          origin.pinnedSessions(), origin.sessions()));
      if (next.equals(before)) return new Receipt(null, "master_observed", before.replicas().revision(), null);
      try (var reservation = capacity.reserveMetadata(Math.multiplyExact(2, bytes(next)))) {
        accounted = capacity.snapshot();
        turn(job, files -> {
          checkRevision(before, files);
          if (!job.phase.compareAndSet(0, 1)) throw new IOException("CANCELLED: master publication revoked");
          files.writeDurable(store.root().resolve("store.json"), next); job.published = next; return null;
        });
        return new Receipt(null, "master_observed", next.replicas().revision(), null);
      }
    });
  }
  /** An unchanged verified offer needs current content admission, not a new receipt or an
   * invented later confirmation time. Cold proofs still run on the two bounded I/O workers. */
  boolean unchanged(MirrorConfig policy, Offer offer) throws Exception {
    checkReconciliation(); var header = header(); requireMirror(header);
    var file = header.replicas().files().stream().filter(f -> offer.identity().equals(f.identity())).findFirst().orElse(null);
    if (file == null || !file.verificationBasis().equals("verified") || file.confirmation() == null
        || !policy.origin().equals(file.confirmation().obtainedFrom()) || !matches(offer, file.confirmation().record())
        || !file.sha256().equals(offer.sha256()) || file.sizeBytes() != offer.sizeBytes()) return false;
    var path = io().resolve(store.root(), file.path());
    try (var lease = LogFileAccess.read(path)) {
      if (hashes.remembered(path, FileSnapshot.of(path), file.sha256())) return true;
    }
    try {
      start(policy, false, job -> {
        if (!header().replicas().files().contains(file)) throw new IOException("SOURCE_CHANGED: held replica changed during recheck");
        verifyHeld(file); job.check(); return new Receipt(null, "already_present", header.replicas().revision(), file);
      }).get();
      return true;
    } catch (java.util.concurrent.ExecutionException failure) {
      if (failure.getCause() instanceof IOException corrupt && corrupt.getMessage().startsWith("CORRUPT_GENERATION")) return false;
      throw failure;
    }
  }
  private void verifyHeld(ReplicaCatalog.Entry file) throws IOException {
    var path = io().resolve(store.root(), file.path());
    try (var lease = LogFileAccess.read(path)) {
      var before = FileSnapshot.of(path);
      if (before == null || before.size() != file.sizeBytes()) throw new IOException("Held size changed");
      hashes.verify(path, before, file.sha256());
    } catch (IOException corrupt) { throw new IOException("CORRUPT_GENERATION: held bytes differ from their established hash", corrupt); }
  }
  private LogFile inspect(Path path, ReplicaCatalog.Entry target, Offer offer) throws IOException {
    var relations = offer.placement() == null ? null : offer.placement().relationships();
    if (offer.kind().equals("clock_record")) {
      ClockRecords.read(io(), path, relations == null ? null : relations.clock());
      return null; // Clock JSON is a managed companion, never a log descriptor.
    }
    double min, max;
    if (offer.kind().equals("wpilog")) try (var reader = new org.triplehelix.wpilogmcp.log.ScopedLogReader(path)) {
      var scan = org.triplehelix.wpilogmcp.log.LogScan.of(reader.reader(), path);
      if (scan.truncated() || scan.damaged()) throw new IOException("INVALID_FORMAT: WPILOG must reach a clean EOF");
      min = scan.minTimestamp(); max = scan.maxTimestamp();
    } else {
      var parser = new org.triplehelix.wpilogmcp.revlog.RevLogParser(new org.triplehelix.wpilogmcp.revlog.dbc.DbcLoader().load(null));
      var log = parser.parseComplete(path); if (log.devices().isEmpty()) throw new IOException("INVALID_FORMAT: no complete REV devices");
      min = log.minTimestamp(); max = log.maxTimestamp();
    }
    var provenance = relations != null && relations.provenance() != null ? relations.provenance()
        : new Provenance("replicated", offer.supplier(), offer.name(), Instant.now().toString(), false);
    return new LogFile(target.path(), target.sha256(), target.sizeBytes(), target.kind(), provenance, true, min, max, null, null, "unknown", false,
        relations == null ? null : relations.matching());
  }
  private static boolean matches(Offer offer, ReplicaCatalog.MasterRecord record) {
    return offer.identity().equals(record.identity()) && offer.sha256().equals(record.sha256()) && offer.sizeBytes() == record.sizeBytes()
        && offer.completionBasis().equals(record.finalizationBasis());
  }
  private void throttle(Job job, int count, long started) throws IOException {
    if (policy.rateBytes() == 0) return;
    long wait = (long) Math.ceil(count * 1_000_000_000.0 / policy.rateBytes());
    while (System.nanoTime() - started < wait) {
      job.progress(); java.util.concurrent.locks.LockSupport.parkNanos(Math.min(50_000_000, wait - (System.nanoTime() - started)));
    }
  }
  private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); } }
  private static String hashText(String text) { return HexFormat.of().formatHex(digest().digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
  private static String prefixHash(Path file, long length) throws IOException { var digest = digest(); hashInto(file, length, digest); return HexFormat.of().formatHex(digest.digest()); }
  private static void hashInto(Path file, long length, MessageDigest digest) throws IOException {
    try (var input = Files.newInputStream(file)) {
      byte[] bytes = new byte[BLOCK_BYTES]; long left = length;
      while (left > 0) { int count = input.read(bytes, 0, (int) Math.min(left, bytes.length)); if (count <= 0) throw new IOException("Incomplete staging prefix"); digest.update(bytes, 0, count); left -= count; }
    }
  }
  @Override public void close() { closed.set(true); cancel(); hashes.close(); }
}
