/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.store.DiskSpaceBudgets;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Unannounce;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Properties;
import org.triplehelix.wpilogmcp.nt4.NtType;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client;
import org.triplehelix.wpilogmcp.capture.context.DeviceIdentity;

/**
 * The NT4 listener is the only writer. Time-sync replies, not possibly ancient retained values,
 * identify a continuing robot clock. Completed writes publish the live index on this loop;
 * manifest work takes snapshots and never waits here. Admission buffers ordered events while
 * placement runs elsewhere, with explicit byte, event and time limits.
 * Periodic force runs on a dedicated thread, with at most one outstanding flush. Completion
 * returns to the listener loop before touching the index; close/rollover wait for that force.
 */
public final class CaptureWriter implements Nt4Client.Listener, AutoCloseable {
  /** Five seconds tolerates connection/RTT jitter; any backward server clock is a new boot. */
  public static final long RECONNECT_TOLERANCE_US = 5_000_000;
  public static final long FLUSH_PERIOD_US = 250_000;
  public static final long REPORT_PERIOD_US = 300_000_000;
  public static final long DEFAULT_MAX_FILE_BYTES = 1L << 30;
  private static final int FINISH_RESERVE_BYTES = 16; // Largest finish header plus its five-byte payload.

  @FunctionalInterface public interface OutputFactory {
    WpilogOutput open(Path path, int nextEntry, boolean resume) throws IOException;
  }
  /** A capture's output, which keeps its records in memory until a flush tick writes them at once. */
  public static final OutputFactory BUFFERED =
      (path, nextEntry, resume) -> new WpilogOutput(path, nextEntry, resume, WpilogOutput.CAPTURE_BUFFER_BYTES);
  @FunctionalInterface public interface ContextSink {
    void value(String name, String type, Object value, long timestampUs, JsonObject metadata);
  }
  private ContextSink contextSink = (name, type, value, time, metadata) -> {};
  /** Set before starting the listener. The sink only admits bounded gateway work; it never waits. */
  public void onContext(ContextSink sink) { contextSink = java.util.Objects.requireNonNull(sink); }
  public interface Observer {
    Path create(String address, Instant startedAt) throws IOException;
    /** Synchronous compatibility for callers outside the NT4 listener. */
    default Path create(String address, Instant startedAt, Session previous) throws IOException {
      return previous == null ? create(address, startedAt) : previous.path();
    }
    default Path create(String address, Instant startedAt, Session previous, DeviceIdentity identity) throws IOException {
      return create(address, startedAt, previous);
    }
    /** Cheap observers complete inline; store placement overrides this without joining its queue. */
    default CompletableFuture<Path> createAsync(String address, Instant start, Session previous, DeviceIdentity identity) {
      try { return CompletableFuture.completedFuture(create(address, start, previous, identity)); }
      catch (IOException e) { return CompletableFuture.failedFuture(e); }
    }
    /** Store lock retries must share this writer's budget, including short test admission limits. */
    default CompletableFuture<Path> createAsync(String address, Instant start, Session previous,
        DeviceIdentity identity, long timeoutUs) {
      return createAsync(address, start, previous, identity);
    }
    /** A late admission has no writer: retain its explicit failure instead of an empty healthy session. */
    default CompletableFuture<Void> abandoned(Path path, Instant endedAt, String reason) {
      return CompletableFuture.completedFuture(null);
    }
    /** Identity updates preserve the current file; directory promotion follows close. */
    default void identity(Session session) throws IOException {}
    /** A file of the session opened: its first, a rollover's, or one a same-boot reconnect continues in. */
    default void opened(Session session) throws IOException {}
    default void entry(Session session, EntryInfo entry) throws IOException {}
    default void metadata(Session session, EntryInfo entry) throws IOException {}
    default void value(Session session, EntryInfo entry, ValueFrame value, WpilogOutput.Written written) throws IOException {}
    /** Monotonic times around a received value's completed publication, including admission wait. */
    default void publication(Session session, long receivedUs, long publishedUs) {}
    default void flushed(Session session) throws IOException {}
    default void timeSync(Session session, long serverTimeUs) throws IOException {}
    default void providers(Session session) throws IOException {}
    default void fileClosed(Session session) throws IOException {}
    default void closed(Session session) throws IOException {}
    default void cost(String topic, TopicCost.Snapshot cost) {
      LoggerFactory.getLogger(CaptureWriter.class).debug("Capture topic {}: {}", topic, cost);
    }
  }

  /** Immutable facts for one closed file; all paths within a session remain relative. */
  public record ClosedFile(String name, long sizeBytes, long minUs, long maxUs, Instant endedAt, String writerIdentity) {
    public ClosedFile(String name, long sizeBytes, long minUs, long maxUs, Instant endedAt) {
      this(name, sizeBytes, minUs, maxUs, endedAt, null);
    }
  }

  /** Identity survives a short disconnect. The store may relocate the directory after close. */
  public static final class Session {
    private volatile Path path;
    private final Instant startedAt;
    private final String address;
    private final CapturePolicy policy;
    private String writerIdentity = java.util.UUID.randomUUID().toString();
    private Instant endedAt;
    private boolean open;
    private String endReason;
    /** Set when a write failed, so the file may end before records the live index already holds. */
    private boolean writeFailed;
    private DeviceIdentity identity;
    private int nextEntry = 1, fileNumber = 1;
    private long minUs = Long.MAX_VALUE, maxUs = Long.MIN_VALUE, sizeBytes, observedAtUs;
    private final List<ClosedFile> files = new ArrayList<>();
    private final Map<String, TopicCost> costs = new LinkedHashMap<>();
    private Instant observedAt;
    private volatile Map<String, TopicCost.Snapshot> closedCosts = Map.of();
    private List<org.triplehelix.wpilogmcp.capture.context.ProviderStatus> providers = List.of();
    private org.triplehelix.wpilogmcp.capture.context.ProviderStatus.KernelClock kernelClock;
    private Session(Path path, Instant startedAt, String address, CapturePolicy policy) {
      this.path = path; this.startedAt = startedAt; this.address = address; this.policy = policy;
    }
    public Path path() { return path; }
    public void relocate(Path path) { this.path = path; }
    public Instant startedAt() { return startedAt; }
    public Instant endedAt() { return endedAt; }
    public String address() { return address; }
    public boolean open() { return open; }
    /** Each file's own writer incarnation: a rollover and a same-boot reconnect each start the next file. */
    public String writerIdentity() { return writerIdentity; }
    public String endReason() { return endReason; }
    /** Whether the file stopped at a failed write, which may have lost records the live index holds. */
    public boolean writeFailed() { return writeFailed; }
    public DeviceIdentity identity() { return identity; }
    public long minTimestampUs() { return minUs == Long.MAX_VALUE ? 0 : minUs; }
    public long maxTimestampUs() { return maxUs == Long.MIN_VALUE ? 0 : maxUs; }
    /**
     * The bytes the current file holds, which never counts a record still in the writer's buffer:
     * the store publishes this size, and a reader of the growing file finds every byte it names.
     */
    public long sizeBytes() { return sizeBytes; }
    public long observedAtUs() { return observedAtUs; }
    public Instant observedAt() { return observedAt; }
    public List<ClosedFile> files() { return List.copyOf(files); }
    /** Accounting through the last closed file, safe to inspect after the listener has stopped. */
    public Map<String, TopicCost.Snapshot> closedCosts() { return closedCosts; }
    /** Only the writer calls this; consumers receive an immutable snapshot. */
    public CaptureStats statistics() {
      var values = new java.util.TreeMap<String, TopicCost.Snapshot>();
      costs.forEach((name, cost) -> values.put(name, cost.snapshot(observedAtUs)));
      return new CaptureStats(values.size(), values.values().stream().mapToLong(TopicCost.Snapshot::records).sum(),
          values.values().stream().mapToLong(TopicCost.Snapshot::bytes).sum(), values, policy.exclude(), policy.thinUs(), providers, kernelClock);
    }
  }

  private static final class Topic {
    EntryInfo entry;
    final long periodUs;
    Long lastUs;
    ValueFrame schema;
    Topic(EntryInfo entry, long periodUs) { this.entry = entry; this.periodUs = periodUs; }
  }
  private final Clock wallClock;
  private final ClientScheduler loop;
  private final CapturePolicy policy;
  private final Observer observer;
  private final OutputFactory outputs;
  private final long maxFileBytes;
  private final Map<Integer, Topic> topics = new LinkedHashMap<>();
  private final Map<String, EntryInfo> contexts = new LinkedHashMap<>();
  private List<org.triplehelix.wpilogmcp.capture.context.ProviderStatus> providers = List.of();
  private org.triplehelix.wpilogmcp.capture.context.ProviderStatus.KernelClock kernelClock;
  private final Map<Integer, Announce> announced = new LinkedHashMap<>();
  private Session session;
  private WpilogOutput output;
  private ExecutorService forceThread;
  private CompletableFuture<Long> pendingForce;
  private String address;
  private DeviceIdentity identity;
  private boolean connected, synchronizedClock, resume, failed;
  private boolean haveClock;
  private boolean diskPaused, diskDraining, diskDrainQueued, reopenAfterDisk;
  private volatile DiskSpaceBudgets.Snapshot diskSpace;
  private DiskSpaceBudgets.Policy diskPolicy;
  private String diskPauseReason;
  private long diskDroppedValues;
  private record SchemaSeed(ValueFrame frame, long receivedUs) {}
  private final Map<Integer, SchemaSeed> diskSchemas = new LinkedHashMap<>();
  private final Map<Integer, SchemaSeed> pendingDiskSeeds = new LinkedHashMap<>();
  private long serverUs, receiptUs, nextReportUs;
  private long rejected;
  /** Encoded event bytes plus a fixed allowance per event bound the startup backlog. */
  public record AdmissionLimits(long bytes, int events, long timeoutUs, int drainEvents, long drainUs) {
    public AdmissionLimits {
      if (bytes < 1 || events < 1 || timeoutUs < 1 || drainEvents < 1 || drainUs < 1)
        throw new IllegalArgumentException("Invalid capture admission limits");
    }
  }
  public static final AdmissionLimits ADMISSION_LIMITS = new AdmissionLimits(16L << 20, 65_536, 30_000_000, 256, 2_000);
  private final AdmissionLimits admissionLimits;
  private final CaptureProgress progress;
  private record Deferred(Runnable action, long bytes, long receivedUs, Instant wall) {}
  private final java.util.ArrayDeque<Deferred> deferred = new java.util.ArrayDeque<>();
  private long deferredBytes, admissionGeneration;
  private Deferred replaying;
  private boolean draining, drainQueued, waitingForRetirement;
  private volatile boolean shutdown;
  private volatile String admissionFailure;
  private Runnable activity = () -> {};
  private static final class Opening {
    final long generation;
    final String address;
    final Instant start;
    final Session previous;
    final DeviceIdentity identity;
    final CompletableFuture<Void> done = new CompletableFuture<>();
    volatile String cancelled;
    Opening(long generation, String address, Instant start, Session previous, DeviceIdentity identity) {
      this.generation = generation; this.address = address; this.start = start;
      this.previous = previous; this.identity = identity;
    }
  }
  private volatile Opening opening;
  private volatile CompletableFuture<Void> admissionCompletion = CompletableFuture.completedFuture(null);
  public void onActivity(Runnable callback) { activity = callback; }
  public String admissionFailure() { return admissionFailure; }
  CaptureProgress.Probe progress(long loopStallUs, long storeTurnUs) { return progress.probe(loopStallUs, storeTurnUs); }
  /** Startup recovery can fail off the listener; only this atomic public status is changed. */
  void startupFailed() { progress.stopped(CaptureProgress.Reason.STARTUP_FAILED); }
  public boolean ready() { return output != null && opening == null && deferred.isEmpty() && !draining; }
  public CompletableFuture<Void> admissionCompletion() { return admissionCompletion; }
  private long nowUs() { return replaying == null ? loop.nowUs() : replaying.receivedUs(); }
  private Instant wallNow() { return replaying == null ? wallClock.instant() : replaying.wall(); }

  private boolean defer(Runnable action, java.util.function.LongSupplier bytes) {
    if (replaying != null || opening == null && deferred.isEmpty() && !draining) return false;
    if (opening != null && opening.cancelled != null && !waitingForRetirement && !diskPaused) return false;
    long cost = 256 + bytes.getAsLong();
    if (deferred.size() >= admissionLimits.events() || cost > admissionLimits.bytes() - deferredBytes) {
      failAdmission("Capture admission buffer exceeded " + admissionLimits.events() + " events or "
          + admissionLimits.bytes() + " bytes; " + (deferred.size() + 1) + " pending events were not recorded");
      return true;
    }
    deferred.addLast(new Deferred(action, cost, loop.nowUs(), wallClock.instant())); deferredBytes += cost;
    if (diskPaused && !diskDrainQueued && !diskDraining) {
      diskDrainQueued = true; loop.execute(this::drainForDiskPause);
    }
    return true;
  }

  /**
   * Published filesystem facts arrive on the listener. Reading free space belongs to a separate
   * daemon; UNKNOWN changes neither recording nor pause state and never invents an empty disk.
   */
  public void diskSpace(DiskSpaceBudgets.Snapshot snapshot, DiskSpaceBudgets.Policy policy) {
    diskSpace = java.util.Objects.requireNonNull(snapshot);
    diskPolicy = java.util.Objects.requireNonNull(policy);
    if (shutdown || failed) return;
    switch (policy.decide(snapshot, diskPaused)) {
      case PAUSE -> pauseForDisk(snapshot, policy);
      case RESUME -> resumeAfterDisk();
      case KEEP_STATE, UNKNOWN -> { }
    }
  }

  public boolean diskPaused() { return diskPaused; }
  public String diskPauseReason() { return diskPaused ? diskPauseReason : null; }
  public long diskDroppedValues() { return diskDroppedValues; }
  public DiskSpaceBudgets.Snapshot diskSpace() { return diskSpace; }

  private void pauseForDisk(DiskSpaceBudgets.Snapshot snapshot, DiskSpaceBudgets.Policy policy) {
    diskPaused = true; resume = false;
    diskPauseReason = "Capture paused for low disk space: free_bytes=" + snapshot.freeBytes()
        + ", floor_bytes=" + policy.floorBytes();
    var pending = opening;
    if (pending != null && pending.cancelled == null) pending.cancelled = diskPauseReason;
    waitingForRetirement = false;
    if (output != null) {
      session.endReason = diskPauseReason;
      // Keep declarations and schema seeds. The next file is a new session, not an append across
      // an unrecorded interval; old ended_at/end_reason and new started_at delimit the gap.
      io(() -> { closeFile(); observer.closed(session); });
    }
    if (!failed) progress.stopped(CaptureProgress.Reason.LOW_DISK);
    drainForDiskPause();
    activity.run();
  }

  /** Controls stay ordered while values are discarded; a held placement cannot grow the backlog. */
  private void drainForDiskPause() {
    diskDrainQueued = false;
    if (!diskPaused || shutdown || failed || diskDraining) return;
    diskDraining = true;
    long started = System.nanoTime(); int count = 0;
    while (!deferred.isEmpty() && diskPaused && !shutdown && !failed
        && count < admissionLimits.drainEvents()
        && (count == 0 || System.nanoTime() - started < admissionLimits.drainUs() * 1000)) {
      var event = deferred.removeFirst(); deferredBytes -= event.bytes(); replaying = event;
      try { event.action().run(); } finally { replaying = null; }
      count++;
    }
    diskDraining = false;
    if (!deferred.isEmpty()) {
      if (!diskDrainQueued) { diskDrainQueued = true; loop.execute(this::drainForDiskPause); }
    } else if (opening == null && diskSpace != null
        && diskPolicy.decide(diskSpace, true) == DiskSpaceBudgets.CaptureDecision.RESUME) {
      resumeAfterDisk();
    }
  }

  private void resumeAfterDisk() {
    if (!diskPaused || opening != null || !deferred.isEmpty() || diskDraining || failed || shutdown) return;
    diskPaused = false; resume = false; reopenAfterDisk = true;
    pendingDiskSeeds.clear(); pendingDiskSeeds.putAll(diskSchemas);
    topics.clear();
    progress.stopped(!connected ? CaptureProgress.Reason.DISCONNECTED
        : synchronizedClock ? CaptureProgress.Reason.WAITING_FOR_TOPIC : CaptureProgress.Reason.WAITING_FOR_CLOCK);
    if (connected && synchronizedClock) {
      for (var topic : List.copyOf(announced.values())) announce(topic);
    }
    activity.run();
  }

  private void rememberDiskSchema(Announce announce, ValueFrame frame, long receivedUs) {
    if (!NtType.fromNt4(announce.type()).wpilog().equals("structschema")
        || NtType.fromNt4(announce.type()).code() != frame.typeCode()) return;
    var previous = diskSchemas.get(announce.id());
    if (previous == null || receivedUs >= previous.receivedUs()) {
      diskSchemas.put(announce.id(), new SchemaSeed(frame, receivedUs));
    }
  }

  private void failAdmission(String reason) {
    admissionFailure = reason; failed = true;
    progress.stopped(CaptureProgress.Reason.ADMISSION_FAILED);
    var pending = opening;
    if (pending != null && pending.cancelled == null) pending.cancelled = reason;
    waitingForRetirement = false;
    deferred.clear(); deferredBytes = 0;
    LoggerFactory.getLogger(CaptureWriter.class).error("Capture recording stopped until a new robot clock: {}", reason);
    if (output != null) {
      session.endReason = reason;
      io(() -> { closeFile(); topics.clear(); contexts.clear(); observer.closed(session); });
    }
    activity.run();
  }

  private boolean admit(Announce first) throws IOException {
    progress.waiting();
    if (opening != null) {
      // A new clock may arrive before an expired request can retire its manifest. Retain the
      // new episode, but never let two admissions mutate the placement observer's state.
      waitingForRetirement = true;
      enqueueFirst(first);
      var retiring = opening;
      loop.schedule(() -> {
        if (opening == retiring && waitingForRetirement) failAdmission("Capture admission exceeded "
            + admissionLimits.timeoutUs() / 1_000_000.0 + " seconds while retiring an earlier admission; "
            + deferred.size() + " pending events were not recorded");
      }, admissionLimits.timeoutUs());
      return false;
    }
    var previous = resume ? session : null;
    var start = previous == null ? wallNow() : previous.startedAt();
    if (previous == null) session = null;
    var device = identity != null && identity.address().equals(address) ? identity : previous == null ? null : previous.identity;
    var request = new Opening(++admissionGeneration, address, start, previous, device);
    var future = observer.createAsync(address, start, previous, device,
        Math.max(1, admissionLimits.timeoutUs() - (loop.nowUs() - nowUs())));
    if (future.isDone()) {
      Path path;
      try { path = future.join(); }
      catch (java.util.concurrent.CompletionException e) {
        progress.stopped(CaptureProgress.Reason.ADMISSION_FAILED); throw admissionError(e);
      }
      admitted(request, path);
      return true;
    }
    opening = request; admissionCompletion = request.done;
    enqueueFirst(first);
    loop.schedule(() -> {
      if (opening == request && request.cancelled == null) failAdmission("Capture admission exceeded "
          + admissionLimits.timeoutUs() / 1_000_000.0 + " seconds; " + deferred.size() + " pending events were not recorded");
    }, Math.max(0, admissionLimits.timeoutUs() - (loop.nowUs() - nowUs())));
    future.whenComplete((path, error) -> {
      if (shutdown) { abandon(request, path, error); return; }
      try { loop.execute(() -> completeAdmission(request, path, error)); }
      catch (java.util.concurrent.RejectedExecutionException e) { request.cancelled = "Capture listener stopped before admission completed"; abandon(request, path, error); }
    });
    activity.run(); return false;
  }

  private static long topicBytes(Announce topic) {
    return topic == null ? 0 : topic.name().length() * 2L + topic.type().length() * 2L + topic.properties().toString().length() * 2L;
  }
  private void enqueueFirst(Announce first) {
    long firstBytes = 256 + topicBytes(first);
    // A reconnect can start another admission while replaying the previous backlog.
    deferred.addFirst(new Deferred(() -> announce(first), firstBytes, nowUs(), wallNow())); deferredBytes += firstBytes;
    if (deferred.size() > admissionLimits.events() || deferredBytes > admissionLimits.bytes()) {
      failAdmission("Capture admission buffer exceeded its event or byte limit; " + deferred.size() + " pending events were not recorded");
    }
  }

  private static IOException admissionError(Throwable error) {
    while (error instanceof java.util.concurrent.CompletionException && error.getCause() != null) error = error.getCause();
    return error instanceof IOException io ? io : new IOException("Capture admission failed", error);
  }
  private void admitted(Opening request, Path path) throws IOException {
    if (request.previous == null) session = new Session(path, request.start, request.address, policy);
    else {
      // A closed file is never written again, since the store may have published its hash: the
      // same boot's reconnect continues its session in the next file, as a rollover does.
      session = request.previous; session.relocate(path); nextName();
    }
    session.identity = request.identity; session.providers = providers; session.kernelClock = kernelClock;
    openFile(); nextReportUs = nowUs() + REPORT_PERIOD_US;
    if (reopenAfterDisk) {
      for (var before : List.copyOf(contexts.values())) {
        room(WpilogOutput.startSize(before.name(), before.type(), before.metadata(), serverUs), 0);
        int id = output.start(before.name(), before.type(), before.metadata(), serverUs);
        progress.written();
        var entry = new EntryInfo(id, before.name(), before.type(), before.metadata());
        contexts.put(entry.name(), entry); observer.entry(session, entry);
      }
      session.sizeBytes = output.writtenBytes(); reopenAfterDisk = false;
    }
    if (deferred.isEmpty()) activity.run();
  }
  private void abandon(Opening request, Path path, Throwable error) {
    var retired = path == null ? CompletableFuture.<Void>completedFuture(null)
        : observer.abandoned(path, wallClock.instant(), request.cancelled == null ? "Capture admission failed" : request.cancelled);
    retired.whenComplete((ignored, failure) -> {
      if (failure == null) request.done.complete(null); else request.done.completeExceptionally(failure);
    });
  }
  private void completeAdmission(Opening request, Path path, Throwable error) {
    if (opening != request || request.generation != admissionGeneration || request.cancelled != null || shutdown) {
      if (request.cancelled == null) request.cancelled = "Capture admission superseded";
      abandon(request, path, error);
      request.done.whenComplete((ignored, retiredError) -> {
        try { loop.execute(() -> {
          if (opening != request) return;
          opening = null;
          if (diskPaused && retiredError == null && !shutdown && !failed) {
            drainForDiskPause();
            return;
          }
          if (retiredError != null) failAdmission("Earlier capture admission could not retire: " + retiredError.getMessage());
          else if (waitingForRetirement && !shutdown && !failed) {
            waitingForRetirement = false; drainDeferred(request);
          }
        }); } catch (java.util.concurrent.RejectedExecutionException stopped) { }
      });
      return;
    }
    if (error != null) {
      failAdmission("Capture admission failed: " + admissionError(error).getMessage());
      opening = null; request.done.complete(null); return;
    }
    opening = null;
    // Initial file facts use the queued announcement's clock, not the later placement reply.
    replaying = deferred.peekFirst();
    try { io(() -> admitted(request, path)); } finally { replaying = null; }
    if (failed) { deferred.clear(); deferredBytes = 0; request.done.complete(null); return; }
    drainDeferred(request);
  }
  private void drainDeferred(Opening owner) {
    drainQueued = false; draining = true;
    long start = System.nanoTime(); int count = 0;
    while (!deferred.isEmpty() && opening == null && !failed && !shutdown
        && count < admissionLimits.drainEvents() && (count == 0 || System.nanoTime() - start < admissionLimits.drainUs() * 1000)) {
      var event = deferred.removeFirst(); deferredBytes -= event.bytes(); replaying = event;
      try { event.action().run(); } finally { replaying = null; }
      count++;
    }
    draining = false;
    if (!deferred.isEmpty() && opening == null && !failed && !shutdown) {
      if (!drainQueued) { drainQueued = true; loop.execute(() -> drainDeferred(owner)); }
    } else {
      owner.done.complete(null);
      if (opening == null) activity.run();
    }
  }
  /** Called on the listener before its executor stops. Any abandoned input is named explicitly. */
  public void shutdown() {
    shutdown = true;
    if (opening != null || !deferred.isEmpty()) failAdmission("Capture stopped during admission; " + deferred.size() + " pending events were not recorded");
    close();
    progress.stopped(CaptureProgress.Reason.SHUTDOWN);
  }

  public CaptureWriter(Clock wallClock, ClientScheduler loop, CapturePolicy policy, Observer observer) {
    this(wallClock, loop, policy, observer, DEFAULT_MAX_FILE_BYTES);
  }
  public CaptureWriter(Clock wallClock, ClientScheduler loop, CapturePolicy policy, Observer observer, long maxFileBytes) {
    this(wallClock, loop, policy, observer, maxFileBytes, WpilogOutput::new);
  }
  /** The output factory permits failures after real record writes without filling a test disk. */
  public CaptureWriter(Clock wallClock, ClientScheduler loop, CapturePolicy policy, Observer observer,
      long maxFileBytes, OutputFactory outputs) {
    this(wallClock, loop, policy, observer, maxFileBytes, outputs, ADMISSION_LIMITS);
  }
  CaptureWriter(Clock wallClock, ClientScheduler loop, CapturePolicy policy, Observer observer,
      long maxFileBytes, OutputFactory outputs, AdmissionLimits limits) {
    if (maxFileBytes < 256 || maxFileBytes > org.triplehelix.wpilogmcp.log.MappedLogBytes.MAX_FILE_BYTES) throw new IllegalArgumentException("Invalid capture.max_file_bytes");
    admissionLimits = limits;
    this.wallClock = wallClock; this.loop = loop; this.policy = policy; this.observer = observer;
    progress = new CaptureProgress(loop::nowUs);
    this.maxFileBytes = maxFileBytes; this.outputs = outputs;
  }

  public Session session() { return session; }
  public long rejectedValues() { return rejected; }

  /** Called on the listener thread after SSH has supplied device evidence. */
  public void identity(DeviceIdentity device) {
    if (defer(() -> identity(device), () -> device.json().toString().length() * 2L)) return;
    identity = device;
    if (session == null || output == null || !device.address().equals(address)) return;
    session.identity = device;
    io(() -> {
      writeIdentity();
      observer.identity(session);
    });
  }

  @Override public void connected(URI uri, String protocol) {
    if (defer(() -> connected(uri, protocol), () -> uri.toString().length() * 2L + protocol.length() * 2L)) return;
    address = uri.getHost(); connected = true; synchronizedClock = false; resume = false;
    if (output == null && !failed && !diskPaused) progress.stopped(CaptureProgress.Reason.WAITING_FOR_CLOCK);
  }

  /**
   * The recorder's clock entry's metadata. A reading is this server's wall clock when the robot's
   * time synchronization reply arrived, carried to the robot time of the next value logged after
   * it, so it runs late by the reply's one-way network delay.
   */
  private static final String CLOCK_METADATA = "{\"source\":\"time_sync\",\"clock\":\"recorder\"}";
  /** Readings at least this far apart in robot time, so drift between synchronizations never runs the clock backwards. */
  private static final long CLOCK_SPACING_US = 1_000_000;
  /** The latest synchronization: the robot time it carried, and this server's wall clock when it arrived. */
  private long clockRobotUs, clockEpochUs;
  /** A synchronization or a new file awaits a reading, which the next value logged takes at its own time. */
  private boolean clockPending;
  private long lastClockUs = Long.MIN_VALUE;
  /** The clock entry of the current file, declared there at its first reading. */
  private EntryInfo clockEntry;

  private static long epochMicros(Instant instant) {
    return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1000);
  }

  /**
   * One reading of this server's wall clock, at the robot time of a value just written, so a
   * capture whose robot publishes no clock can still be placed in UTC. At a value's time it never
   * widens the file's time range; it is not forwarded, since it measures this recorder alone; and
   * it never rolls a file over: a reading that does not fit waits for the next file.
   */
  private void writeClock(long robotUs) throws IOException {
    String name = org.triplehelix.wpilogmcp.log.WallClock.PIT_CLOCK_ENTRY;
    long epoch = clockEpochUs + (robotUs - clockRobotUs);
    byte[] payload = WpilogOutput.payload(2, epoch);
    int declaration = clockEntry == null ? WpilogOutput.startSize(name, "int64", CLOCK_METADATA, robotUs) : 0;
    long reserved = (activeEntries() + (clockEntry == null ? 1L : 0L)) * FINISH_RESERVE_BYTES;
    if (output.size() + declaration + WpilogOutput.recordSize(Integer.MAX_VALUE, robotUs, payload.length) + reserved > maxFileBytes) return;
    if (clockEntry == null) {
      clockEntry = new EntryInfo(output.start(name, "int64", CLOCK_METADATA, robotUs), name, "int64", CLOCK_METADATA);
      progress.written();
      observer.entry(session, clockEntry);
    }
    var written = output.append(clockEntry.id(), robotUs, payload);
    progress.written();
    session.sizeBytes = output.writtenBytes();
    observer.value(session, clockEntry, new ValueFrame(clockEntry.id(), robotUs, 2, epoch), written);
    clockPending = false;
    lastClockUs = robotUs;
  }

  @Override public void timeSync(long timestampUs, long receivedAtUs) {
    if (defer(() -> timeSync(timestampUs, receivedAtUs), () -> 16)) return;
    long elapsed = receivedAtUs - receiptUs;
    boolean continuing = haveClock && timestampUs >= serverUs && elapsed >= 0
        && Math.abs((double) timestampUs - serverUs - elapsed) <= RECONNECT_TOLERANCE_US;
    boolean restartHere = failed && synchronizedClock && !continuing;
    if (!synchronizedClock || restartHere) {
      resume = !diskPaused && !reopenAfterDisk && session != null && continuing;
      if (!continuing) { failed = false; admissionFailure = null; diskSchemas.clear(); pendingDiskSeeds.clear(); }
      synchronizedClock = true;
    }
    serverUs = timestampUs; receiptUs = receivedAtUs; haveClock = true;
    if (output == null && opening == null && !failed && !diskPaused) progress.stopped(CaptureProgress.Reason.WAITING_FOR_TOPIC);
    if (restartHere) for (var topic : List.copyOf(announced.values())) announce(topic);
    if (output != null) io(() -> observer.timeSync(session, timestampUs));
    // The wall clock at the reply's receipt, not at this call, which may run from the queue.
    clockRobotUs = timestampUs; clockEpochUs = epochMicros(wallNow()) - (nowUs() - receivedAtUs); clockPending = true;
  }

  @Override public void announce(Announce announce) {
    if (defer(() -> announce(announce), () -> topicBytes(announce))) return;
    if (shutdown) return;
    if (!connected || !synchronizedClock) throw new IllegalStateException("Capture announce before time sync");
    announced.put(announce.id(), announce);
    if (failed || diskPaused) return;
    io(() -> {
      if (output == null) {
        if (!admit(announce)) return;
      }
      if (policy.excluded(announce.name()) || topics.containsKey(announce.id())) return;
      long periodUs = policy.periodUs(announce.name());
      String metadata = metadata(announce, periodUs);
      var diskSeed = pendingDiskSeeds.remove(announce.id());
      if (diskSeed != null) {
        var seeded = com.google.gson.JsonParser.parseString(metadata).getAsJsonObject();
        seeded.addProperty("capture_schema_seed", true); metadata = seeded.toString();
      }
      String name = org.triplehelix.wpilogmcp.nt4.ContextTopic.metadata(announce.name(), announce.properties()).isPresent()
          ? announce.name() : "NT:" + announce.name();
      // An upstream recorder's clock, as a pit gateway forwards it: this recorder measures its own.
      if (name.equals(org.triplehelix.wpilogmcp.log.WallClock.PIT_CLOCK_ENTRY)) return;
      String type = NtType.fromNt4(announce.type()).wpilog();
      int seedBytes = diskSeed == null ? 0 : ((byte[]) diskSeed.frame().value()).length;
      room(WpilogOutput.startSize(name, type, metadata, serverUs)
          + (diskSeed == null ? 0 : WpilogOutput.recordSize(Integer.MAX_VALUE, serverUs, seedBytes)), 1);
      int id = output.start(name, type, metadata, serverUs);
      progress.written();
      session.sizeBytes = output.writtenBytes();
      var entry = new EntryInfo(id, name, type, metadata);
      var topic = new Topic(entry, periodUs);
      topics.put(announce.id(), topic);
      session.costs.computeIfAbsent(announce.name(), ignored -> new TopicCost());
      observer.entry(session, entry);
      if (diskSeed != null) {
        var seed = new ValueFrame(announce.id(), serverUs, diskSeed.frame().typeCode(), diskSeed.frame().value());
        var written = output.append(entry.id(), serverUs, (byte[]) seed.value());
        progress.written();
        topic.schema = seed; session.sizeBytes = output.writtenBytes();
        session.minUs = Math.min(session.minUs, serverUs); session.maxUs = Math.max(session.maxUs, serverUs);
        observer.value(session, entry, seed, written);
      }
    });
  }

  private String metadata(Announce announce, long periodUs) {
    var metadata = org.triplehelix.wpilogmcp.nt4.ContextTopic.metadata(announce.name(), announce.properties()).orElseGet(() -> {
      var ordinary = new JsonObject(); ordinary.addProperty("source", "nt4"); return ordinary;
    });
    metadata.addProperty("robot", address);
    if (periodUs > 0) metadata.addProperty("period_sec", periodUs / 1_000_000.0);
    if (!announce.properties().isEmpty()) metadata.add("nt4_properties", announce.properties());
    return metadata.toString();
  }

  /** Keep publisher properties apart from recorder provenance, including null-as-delete patches. */
  @Override public void properties(Properties change) {
    if (defer(() -> properties(change), () -> change.name().length() * 2L + change.update().toString().length() * 2L)) return;
    for (var before : List.copyOf(announced.values())) {
      if (!before.name().equals(change.name())) continue;
      var after = before.withUpdate(change.update()); announced.put(after.id(), after);
      var topic = topics.get(after.id());
      if (topic == null || output == null || failed) continue;
      String metadata = metadata(after, topic.periodUs);
      if (metadata.equals(topic.entry.metadata())) continue;
      io(() -> {
        room(WpilogOutput.metadataSize(metadata, serverUs), 0);
        output.setMetadata(topic.entry.id(), metadata, serverUs);
        progress.written();
        topic.entry = new EntryInfo(topic.entry.id(), topic.entry.name(), topic.entry.type(), metadata);
        observer.metadata(session, topic.entry);
        session.sizeBytes = output.writtenBytes();
      });
    }
  }

  private void openFile() throws IOException {
    output = outputs.open(session.path(), session.nextEntry, false);
    progress.written();
    progress.recording();
    forceThread = Executors.newSingleThreadExecutor(r -> {
      var thread = new Thread(r, "capture-flush"); thread.setDaemon(true); return thread;
    });
    session.open = true; session.endedAt = null; session.sizeBytes = output.writtenBytes();
    session.observedAtUs = nowUs(); session.observedAt = wallNow();
    observer.opened(session);
    clockEntry = null; clockPending = haveClock; lastClockUs = Long.MIN_VALUE;
    scheduleFlush(output);
    writeIdentity();
  }

  private int identitySize() {
    if (session.identity == null) return 0;
    var device = session.identity;
    return WpilogOutput.startSize("/Daemon/Robot/Identity", "json", device.metadata().toString(), serverUs)
        + WpilogOutput.recordSize(Integer.MAX_VALUE, serverUs, device.json().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
        + FINISH_RESERVE_BYTES;
  }

  private void writeIdentity() throws IOException {
    if (session.identity == null) return;
    int bytes = identitySize();
    if (output.size() + bytes + (long) activeEntries() * FINISH_RESERVE_BYTES > maxFileBytes) {
      if (12L + bytes + (long) activeEntries() * FINISH_RESERVE_BYTES > maxFileBytes) {
        throw new IOException("capture.max_file_bytes cannot hold robot identity context");
      }
      closeFile(); nextFile(); return;
    }
    context("/Daemon/Robot/Identity", session.identity.json(), session.identity.metadata());
  }

  /** One context sample is a complete entry lifecycle; repeated names still share the live series. */
  private void context(String name, JsonObject value, JsonObject metadata) throws IOException {
    int id = output.start(name, "json", metadata.toString(), serverUs);
    progress.written();
    var entry = new EntryInfo(id, name, "json", metadata.toString()); observer.entry(session, entry);
    var frame = new ValueFrame(id, serverUs, 4, value.toString());
    var written = output.append(id, serverUs, WpilogOutput.payload(4, value.toString()));
    progress.written();
    session.minUs = Math.min(session.minUs, serverUs); session.maxUs = Math.max(session.maxUs, serverUs);
    observer.value(session, entry, frame, written); output.finish(id, serverUs); progress.written(); session.sizeBytes = output.writtenBytes();
    contextSink.value(name, "json", value.toString(), serverUs, metadata);
  }

  /** Ordered provider hook: its own entry ids, metadata updates, rollover and the same live index. */
  public int recordContext(String name, String type, Object value, long timestampUs, JsonObject metadata) {
    if (!ready() || failed) return 0;
    if (!name.startsWith("/Daemon/")) throw new IllegalArgumentException("Context entries belong under /Daemon/");
    var nt = NtType.fromWpilog(type); byte[] payload = WpilogOutput.payload(nt.code(), value);
    String meta = metadata.toString(); int[] bytes = {0};
    io(() -> {
      var before = contexts.get(name);
      if (before != null && !before.type().equals(type)) throw new IOException("Provider entry changed type: " + name);
      int declaration = before == null ? WpilogOutput.startSize(name, type, meta, timestampUs)
          : before.metadata().equals(meta) ? 0 : WpilogOutput.metadataSize(meta, timestampUs);
      room(declaration + WpilogOutput.recordSize(Integer.MAX_VALUE, timestampUs, payload.length), before == null ? 1 : 0);
      var entry = contexts.get(name);
      if (entry == null) {
        entry = new EntryInfo(output.start(name, type, meta, timestampUs), name, type, meta);
        progress.written();
        contexts.put(name, entry); observer.entry(session, entry);
      } else if (!entry.metadata().equals(meta)) {
        output.setMetadata(entry.id(), meta, timestampUs);
        progress.written();
        entry = new EntryInfo(entry.id(), name, type, meta); contexts.put(name, entry); observer.metadata(session, entry);
      }
      var frame = new ValueFrame(entry.id(), timestampUs, nt.code(), value);
      var written = output.append(entry.id(), timestampUs, payload);
      progress.written();
      session.minUs = Math.min(session.minUs, timestampUs); session.maxUs = Math.max(session.maxUs, timestampUs);
      session.sizeBytes = output.writtenBytes(); observer.value(session, entry, frame, written); bytes[0] = written.size();
      contextSink.value(name, nt.nt4(), value, timestampUs, metadata);
    });
    return bytes[0];
  }
  /** Facts are copied on the writer thread; state changes publish immediately, costs on flush. */
  public void providers(List<org.triplehelix.wpilogmcp.capture.context.ProviderStatus> values,
      org.triplehelix.wpilogmcp.capture.context.ProviderStatus.KernelClock kernel) {
    var snapshot = List.copyOf(values);
    if (defer(() -> providers(snapshot, kernel), () -> 64 + snapshot.stream().mapToLong(v -> v.toString().length() * 2L).sum())) return;
    boolean changed = providers.size() != values.size();
    if (!changed) for (int i = 0; i < values.size(); i++) {
      var a = providers.get(i); var b = values.get(i);
      if (!a.name().equals(b.name()) || !a.state().equals(b.state()) || !java.util.Objects.equals(a.reason(), b.reason())) changed = true;
    }
    providers = List.copyOf(values); kernelClock = kernel;
    if (session != null && output != null) {
      session.providers = providers; session.kernelClock = kernelClock;
      if (changed) io(() -> observer.providers(session));
    }
  }
  private int activeEntries() { return topics.size() + contexts.size() + (clockEntry == null ? 0 : 1); }
  private long contextDeclarations() {
    return contexts.values().stream().mapToLong(e -> WpilogOutput.startSize(e.name(), e.type(), e.metadata(), serverUs)).sum();
  }

  /** Reserve every finish before appending, so even the closed file stays below the bound. */
  private void room(int bytes, int extraEntries) throws IOException {
    long reserved = (activeEntries() + (long) extraEntries) * FINISH_RESERVE_BYTES;
    if (output.size() + bytes + reserved <= maxFileBytes) return;
    long declarations = topics.values().stream().mapToLong(t -> WpilogOutput.startSize(
        t.entry.name(), t.entry.type(), rolloverMetadata(t), serverUs)
        + (t.schema == null ? 0 : WpilogOutput.recordSize(Integer.MAX_VALUE, serverUs, ((byte[]) t.schema.value()).length))).sum();
    if (12 + declarations + contextDeclarations() + identitySize() + bytes + reserved > maxFileBytes) {
      throw new IOException("capture.max_file_bytes cannot hold the active declarations, record, and finishes");
    }
    closeFile();
    nextFile();
  }

  /** The session's next file, beside its last one, with its own writer incarnation and entry ids. */
  private void nextName() {
    session.path = session.path().resolveSibling("capture-" + ++session.fileNumber + ".wpilog");
    session.writerIdentity = java.util.UUID.randomUUID().toString();
    session.nextEntry = 1; session.minUs = Long.MAX_VALUE; session.maxUs = Long.MIN_VALUE;
  }

  private void nextFile() throws IOException {
    long declarations = topics.values().stream().mapToLong(t -> WpilogOutput.startSize(
        t.entry.name(), t.entry.type(), rolloverMetadata(t), serverUs)
        + (t.schema == null ? 0 : WpilogOutput.recordSize(Integer.MAX_VALUE, serverUs, ((byte[]) t.schema.value()).length))).sum();
    if (12 + declarations + contextDeclarations() + identitySize() + (long) activeEntries() * FINISH_RESERVE_BYTES > maxFileBytes) {
      throw new IOException("capture.max_file_bytes cannot hold identity and active declarations");
    }
    nextName();
    openFile();
    for (var topic : topics.values()) {
      var before = topic.entry;
      String metadata = rolloverMetadata(topic);
      int id = output.start(before.name(), before.type(), metadata, serverUs);
      progress.written();
      topic.entry = new EntryInfo(id, before.name(), before.type(), metadata);
      observer.entry(session, topic.entry);
      if (topic.schema != null) {
        var seed = new ValueFrame(topic.schema.topicId(), serverUs, topic.schema.typeCode(), topic.schema.value());
        var written = output.append(id, seed.timestampUs(), (byte[]) seed.value());
        progress.written();
        session.minUs = Math.min(session.minUs, seed.timestampUs()); session.maxUs = Math.max(session.maxUs, seed.timestampUs());
        observer.value(session, topic.entry, seed, written);
      }
    }
    for (var before : List.copyOf(contexts.values())) {
      int id = output.start(before.name(), before.type(), before.metadata(), serverUs);
      progress.written();
      var entry = new EntryInfo(id, before.name(), before.type(), before.metadata());
      contexts.put(entry.name(), entry); observer.entry(session, entry);
    }
    session.sizeBytes = output.writtenBytes();
  }

  private static String rolloverMetadata(Topic topic) {
    if (topic.schema == null) return topic.entry.metadata();
    var metadata = com.google.gson.JsonParser.parseString(topic.entry.metadata()).getAsJsonObject();
    metadata.addProperty("capture_schema_seed", true);
    return metadata.toString();
  }

  @Override public void unannounce(Unannounce announce) {
    if (defer(() -> unannounce(announce), () -> announce.name().length() * 2L)) return;
    announced.remove(announce.id()); diskSchemas.remove(announce.id()); pendingDiskSeeds.remove(announce.id());
    var topic = topics.remove(announce.id());
    if (topic != null && output != null) io(() -> {
      output.finish(topic.entry.id(), serverUs); progress.written(); session.sizeBytes = output.writtenBytes();
    });
  }

  @Override public void value(Announce announce, ValueFrame frame, long receivedAtUs) {
    if (diskPaused) {
      // A retained schema must follow queued declarations and removals. Ordinary paused values
      // still drop immediately, so a held admission cannot accumulate a numeric-value backlog.
      if (NtType.fromNt4(announce.type()).wpilog().equals("structschema")
          && defer(() -> value(announce, frame, receivedAtUs), () -> frame.encode().length + topicBytes(announce))) return;
      rememberDiskSchema(announce, frame, receivedAtUs); diskDroppedValues++; return;
    }
    if (defer(() -> value(announce, frame, receivedAtUs), () -> frame.encode().length + topicBytes(announce))) return;
    var topic = topics.get(announce.id());
    if (topic == null || output == null || failed) return;
    var cost = session.costs.get(announce.name());
    if (NtType.fromNt4(announce.type()).code() != frame.typeCode()) { rejected++; cost.drop(); return; }
    if (topic.periodUs > 0 && topic.lastUs != null && frame.timestampUs() - topic.lastUs < topic.periodUs) {
      cost.thin(); return;
    }
    io(() -> {
      byte[] payload = WpilogOutput.payload(frame.typeCode(), frame.value());
      room(WpilogOutput.recordSize(topic.entry.id(), frame.timestampUs(), payload.length), 0);
      var written = output.append(topic.entry.id(), frame.timestampUs(), payload);
      progress.written();
      topic.lastUs = frame.timestampUs(); session.sizeBytes = output.writtenBytes();
      if (topic.entry.type().equals("structschema")) {
        topic.schema = frame; rememberDiskSchema(announce, frame, receivedAtUs);
      }
      session.minUs = Math.min(session.minUs, frame.timestampUs());
      session.maxUs = Math.max(session.maxUs, frame.timestampUs());
      cost.add(receivedAtUs, written.size());
      observer.value(session, topic.entry, frame, written);
      observer.publication(session, receivedAtUs, loop.nowUs());
      if (clockPending && frame.timestampUs() >= lastClockUs + CLOCK_SPACING_US) writeClock(frame.timestampUs());
    });
  }

  @Override public void invalidValue(Announce topic, int typeCode) {
    if (defer(() -> invalidValue(topic, typeCode), () -> 16 + topicBytes(topic))) return;
    rejected++;
    if (session != null && topic != null) session.costs.computeIfAbsent(topic.name(), ignored -> new TopicCost()).drop();
  }

  private void scheduleFlush(WpilogOutput expected) {
    loop.schedule(() -> {
      if (output != expected) return;
      // The tick's records reach the file in one write, here on the loop, before any force.
      io(expected::drain);
      if (output != expected) return;
      if (pendingForce == null) {
        var force = CompletableFuture.supplyAsync(() -> {
          try { expected.flush(); return loop.nowUs(); }
          catch (IOException e) { throw new java.util.concurrent.CompletionException(e); }
        }, forceThread);
        pendingForce = force;
        force.whenComplete((time, failure) -> {
          try { loop.execute(() -> {
            if (output == expected && pendingForce == force) io(() -> finishForce(expected));
          }); } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Shutdown/rollover owns the completion barrier and failure reporting now.
          }
        });
      }
      scheduleFlush(expected);
    }, FLUSH_PERIOD_US);
  }

  /** Called only by the listener loop: ordinary ticks never wait; close is a durability barrier. */
  private void finishForce(WpilogOutput forced) throws IOException {
    var pending = pendingForce;
    if (pending == null) return;
    pendingForce = null;
    try { long completedAt = pending.join(); if (ready()) session.observedAtUs = completedAt; }
    catch (java.util.concurrent.CompletionException e) {
      if (e.getCause() instanceof IOException failure) throw failure;
      throw new IOException("Capture flush failed", e.getCause());
    }
    // The live index lets a value go cold only once its record is in the file.
    session.sizeBytes = forced.writtenBytes();
    observer.flushed(session);
    if (session.observedAtUs >= nextReportUs) {
      var stats = session.statistics();
      stats.costs().forEach(observer::cost);
      long dropped = stats.costs().values().stream().mapToLong(TopicCost.Snapshot::dropped).sum();
      long thinned = stats.costs().values().stream().mapToLong(TopicCost.Snapshot::thinned).sum();
      // Keep the ordinary journal bounded even when the robot publishes thousands of topics.
      // Cumulative recorded bytes rank the five largest contributors; ties use the topic name.
      var top = stats.costs().entrySet().stream()
          .sorted(Map.Entry.<String, TopicCost.Snapshot>comparingByValue(
              Comparator.comparingLong(TopicCost.Snapshot::bytes)).reversed().thenComparing(Map.Entry::getKey))
          .limit(5).map(entry -> entry.getKey() + "=" + entry.getValue().bytes()).toList();
      LoggerFactory.getLogger(CaptureWriter.class).info(
          "Capture cost: topics={}, records={}, bytes={}, dropped={}, thinned={}, top_by_bytes={}",
          stats.topicCount(), stats.records(), stats.bytes(), dropped, thinned, top);
      session.providers.forEach(provider -> LoggerFactory.getLogger(CaptureWriter.class).info("Capture provider {}: {}", provider.name(), provider));
      nextReportUs = session.observedAtUs + REPORT_PERIOD_US;
    }
  }

  @Override public void disconnected() {
    if (defer(this::disconnected, () -> 0)) return;
    connected = false; announced.clear(); diskSchemas.clear(); pendingDiskSeeds.clear(); close();
    if (!failed && !shutdown) progress.stopped(diskPaused ? CaptureProgress.Reason.LOW_DISK : CaptureProgress.Reason.DISCONNECTED);
  }

  private void closeFile() throws IOException {
    var closing = output;
    if (closing == null) return;
    output = null;
    IOException failure = null;
    try { finishForce(closing); }
    catch (IOException e) { failure = e; }
    finally { forceThread.shutdown(); }
    try {
      // A failed write of the buffer may have lost an entry's start, and no finish may name it.
      if (closing.lostRecords() == 0) {
        for (var topic : topics.values()) { closing.finish(topic.entry.id(), serverUs); progress.written(); }
        for (var entry : contexts.values()) { closing.finish(entry.id(), serverUs); progress.written(); }
        if (clockEntry != null) { closing.finish(clockEntry.id(), serverUs); progress.written(); }
      }
      clockEntry = null;
    }
    catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
    try { closing.close(); }
    catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
    session.nextEntry = closing.nextEntry(); session.open = false; session.endedAt = wallNow();
    session.observedAtUs = nowUs();
    var costs = new LinkedHashMap<String, TopicCost.Snapshot>();
    session.costs.forEach((name, cost) -> costs.put(name, cost.snapshot(session.observedAtUs)));
    session.closedCosts = Map.copyOf(costs);
    // The last records may have been in the buffer: the index must not outlive what the file lost.
    if (failure != null) session.writeFailed = true;
    try {
      session.sizeBytes = Files.size(session.path());
      session.files.add(new ClosedFile(session.path().getFileName().toString(), session.sizeBytes,
          session.minTimestampUs(), session.maxTimestampUs(), session.endedAt(), session.writerIdentity()));
      observer.fileClosed(session);
    } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
    if (failure != null) throw failure;
  }

  @Override public void close() {
    if (!shutdown && defer(this::close, () -> 0)) return;
    if (output == null) return;
    io(() -> { closeFile(); topics.clear(); contexts.clear(); observer.closed(session); });
    if (!failed) progress.stopped(CaptureProgress.Reason.CLOSED);
  }

  @FunctionalInterface private interface IO { void run() throws IOException; }
  private void io(IO operation) {
    try {
      if (session != null && (replaying != null || ready())) {
        session.observedAt = wallNow();
        if (replaying != null) session.observedAtUs = nowUs();
      }
      operation.run();
    }
    catch (IOException e) {
      progress.writeFailed();
      failed = true;
      if (session != null) {
        session.endReason = "Capture write failed: " + e.getMessage();
        session.writeFailed = true;
      }
      LoggerFactory.getLogger(CaptureWriter.class).error("Capture recording stopped until a new robot clock: {}", e.getMessage());
      try { closeFile(); } catch (IOException close) { e.addSuppressed(close); }
      topics.clear(); contexts.clear();
      if (session != null) {
        session.open = false; session.endedAt = wallNow(); session.observedAtUs = nowUs();
        try { observer.closed(session); }
        catch (IOException close) { LoggerFactory.getLogger(CaptureWriter.class).error("Capture failure could not be recorded", close); }
      }
    }
  }
}
