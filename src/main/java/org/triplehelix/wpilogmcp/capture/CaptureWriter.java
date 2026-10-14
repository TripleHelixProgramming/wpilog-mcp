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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.EntryInfo;
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
    /** A late admission has no writer: retain its explicit failure instead of an empty healthy session. */
    default CompletableFuture<Void> abandoned(Path path, Instant endedAt, String reason) {
      return CompletableFuture.completedFuture(null);
    }
    /** Identity updates preserve the current file; directory promotion follows close. */
    default void identity(Session session) throws IOException {}
    default void opened(Session session, boolean resumed) throws IOException {}
    default void entry(Session session, EntryInfo entry) throws IOException {}
    default void metadata(Session session, EntryInfo entry) throws IOException {}
    default void value(Session session, EntryInfo entry, ValueFrame value, WpilogOutput.Written written) throws IOException {}
    default void flushed(Session session) throws IOException {}
    default void timeSync(Session session, long serverTimeUs) throws IOException {}
    default void providers(Session session) throws IOException {}
    default void fileClosed(Session session) throws IOException {}
    default void closed(Session session) throws IOException {}
    default void cost(String topic, TopicCost.Snapshot cost) {
      LoggerFactory.getLogger(CaptureWriter.class).info("Capture topic {}: {}", topic, cost);
    }
  }

  /** Immutable facts for one closed file; all paths within a session remain relative. */
  public record ClosedFile(String name, long sizeBytes, long minUs, long maxUs, Instant endedAt) {}

  /** Identity survives a short disconnect. The store may relocate the directory after close. */
  public static final class Session {
    private volatile Path path;
    private final Instant startedAt;
    private final String address;
    private final CapturePolicy policy;
    private Instant endedAt;
    private boolean open;
    private String endReason;
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
    public String endReason() { return endReason; }
    public DeviceIdentity identity() { return identity; }
    public long minTimestampUs() { return minUs == Long.MAX_VALUE ? 0 : minUs; }
    public long maxTimestampUs() { return maxUs == Long.MIN_VALUE ? 0 : maxUs; }
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
  public boolean ready() { return output != null && opening == null && deferred.isEmpty() && !draining; }
  public CompletableFuture<Void> admissionCompletion() { return admissionCompletion; }
  private long nowUs() { return replaying == null ? loop.nowUs() : replaying.receivedUs(); }
  private Instant wallNow() { return replaying == null ? wallClock.instant() : replaying.wall(); }

  private boolean defer(Runnable action, java.util.function.LongSupplier bytes) {
    if (replaying != null || opening == null && deferred.isEmpty() && !draining) return false;
    if (opening != null && opening.cancelled != null && !waitingForRetirement) return false;
    long cost = 256 + bytes.getAsLong();
    if (deferred.size() >= admissionLimits.events() || cost > admissionLimits.bytes() - deferredBytes) {
      failAdmission("Capture admission buffer exceeded " + admissionLimits.events() + " events or "
          + admissionLimits.bytes() + " bytes; " + (deferred.size() + 1) + " pending events were not recorded");
      return true;
    }
    deferred.addLast(new Deferred(action, cost, loop.nowUs(), wallClock.instant())); deferredBytes += cost;
    return true;
  }

  private void failAdmission(String reason) {
    admissionFailure = reason; failed = true;
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
    var future = observer.createAsync(address, start, previous, device);
    if (future.isDone()) {
      try { admitted(request, future.join()); }
      catch (java.util.concurrent.CompletionException e) { throw admissionError(e); }
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
    else { session = request.previous; session.relocate(path); }
    session.identity = request.identity; session.providers = providers; session.kernelClock = kernelClock;
    openFile(request.previous != null); nextReportUs = nowUs() + REPORT_PERIOD_US;
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
  }

  @Override public void timeSync(long timestampUs, long receivedAtUs) {
    if (defer(() -> timeSync(timestampUs, receivedAtUs), () -> 16)) return;
    long elapsed = receivedAtUs - receiptUs;
    boolean continuing = haveClock && timestampUs >= serverUs && elapsed >= 0
        && Math.abs((double) timestampUs - serverUs - elapsed) <= RECONNECT_TOLERANCE_US;
    boolean restartHere = failed && synchronizedClock && !continuing;
    if (!synchronizedClock || restartHere) {
      resume = session != null && continuing;
      if (!continuing) { failed = false; admissionFailure = null; }
      synchronizedClock = true;
    }
    serverUs = timestampUs; receiptUs = receivedAtUs; haveClock = true;
    if (restartHere) for (var topic : List.copyOf(announced.values())) announce(topic);
    if (output != null) io(() -> observer.timeSync(session, timestampUs));
  }

  @Override public void announce(Announce announce) {
    if (defer(() -> announce(announce), () -> topicBytes(announce))) return;
    if (shutdown) return;
    if (!connected || !synchronizedClock) throw new IllegalStateException("Capture announce before time sync");
    announced.put(announce.id(), announce);
    if (failed) return;
    io(() -> {
      if (output == null) {
        if (!admit(announce)) return;
      }
      if (policy.excluded(announce.name()) || topics.containsKey(announce.id())) return;
      long periodUs = policy.periodUs(announce.name());
      String metadata = metadata(announce, periodUs);
      String name = "NT:" + announce.name(), type = NtType.fromNt4(announce.type()).wpilog();
      room(WpilogOutput.startSize(name, type, metadata, serverUs), 1);
      int id = output.start(name, type, metadata, serverUs);
      session.sizeBytes = output.size();
      var entry = new EntryInfo(id, name, type, metadata);
      topics.put(announce.id(), new Topic(entry, periodUs));
      session.costs.computeIfAbsent(announce.name(), ignored -> new TopicCost());
      observer.entry(session, entry);
    });
  }

  private String metadata(Announce announce, long periodUs) {
    var metadata = new JsonObject(); metadata.addProperty("source", "nt4"); metadata.addProperty("robot", address);
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
        topic.entry = new EntryInfo(topic.entry.id(), topic.entry.name(), topic.entry.type(), metadata);
        observer.metadata(session, topic.entry);
        session.sizeBytes = output.size();
      });
    }
  }

  private void openFile(boolean append) throws IOException {
    output = outputs.open(session.path(), session.nextEntry, append);
    forceThread = Executors.newSingleThreadExecutor(r -> {
      var thread = new Thread(r, "capture-flush"); thread.setDaemon(true); return thread;
    });
    session.files.removeIf(f -> f.name().equals(session.path().getFileName().toString()));
    session.open = true; session.endedAt = null; session.sizeBytes = output.size();
    session.observedAtUs = nowUs(); session.observedAt = wallNow();
    observer.opened(session, append);
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
    var entry = new EntryInfo(id, name, "json", metadata.toString()); observer.entry(session, entry);
    var frame = new ValueFrame(id, serverUs, 4, value.toString());
    var written = output.append(id, serverUs, WpilogOutput.payload(4, value.toString()));
    session.minUs = Math.min(session.minUs, serverUs); session.maxUs = Math.max(session.maxUs, serverUs);
    observer.value(session, entry, frame, written); output.finish(id, serverUs); session.sizeBytes = output.size();
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
        contexts.put(name, entry); observer.entry(session, entry);
      } else if (!entry.metadata().equals(meta)) {
        output.setMetadata(entry.id(), meta, timestampUs);
        entry = new EntryInfo(entry.id(), name, type, meta); contexts.put(name, entry); observer.metadata(session, entry);
      }
      var frame = new ValueFrame(entry.id(), timestampUs, nt.code(), value);
      var written = output.append(entry.id(), timestampUs, payload);
      session.minUs = Math.min(session.minUs, timestampUs); session.maxUs = Math.max(session.maxUs, timestampUs);
      session.sizeBytes = output.size(); observer.value(session, entry, frame, written); bytes[0] = written.size();
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
  private int activeEntries() { return topics.size() + contexts.size(); }
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

  private void nextFile() throws IOException {
    long declarations = topics.values().stream().mapToLong(t -> WpilogOutput.startSize(
        t.entry.name(), t.entry.type(), rolloverMetadata(t), serverUs)
        + (t.schema == null ? 0 : WpilogOutput.recordSize(Integer.MAX_VALUE, serverUs, ((byte[]) t.schema.value()).length))).sum();
    if (12 + declarations + contextDeclarations() + identitySize() + (long) activeEntries() * FINISH_RESERVE_BYTES > maxFileBytes) {
      throw new IOException("capture.max_file_bytes cannot hold identity and active declarations");
    }
    session.path = session.path().resolveSibling("capture-" + ++session.fileNumber + ".wpilog");
    session.nextEntry = 1; session.minUs = Long.MAX_VALUE; session.maxUs = Long.MIN_VALUE;
    openFile(false);
    for (var topic : topics.values()) {
      var before = topic.entry;
      String metadata = rolloverMetadata(topic);
      int id = output.start(before.name(), before.type(), metadata, serverUs);
      topic.entry = new EntryInfo(id, before.name(), before.type(), metadata);
      observer.entry(session, topic.entry);
      if (topic.schema != null) {
        var seed = new ValueFrame(topic.schema.topicId(), serverUs, topic.schema.typeCode(), topic.schema.value());
        var written = output.append(id, seed.timestampUs(), (byte[]) seed.value());
        session.minUs = Math.min(session.minUs, seed.timestampUs()); session.maxUs = Math.max(session.maxUs, seed.timestampUs());
        observer.value(session, topic.entry, seed, written);
      }
    }
    for (var before : List.copyOf(contexts.values())) {
      int id = output.start(before.name(), before.type(), before.metadata(), serverUs);
      var entry = new EntryInfo(id, before.name(), before.type(), before.metadata());
      contexts.put(entry.name(), entry); observer.entry(session, entry);
    }
    session.sizeBytes = output.size();
  }

  private static String rolloverMetadata(Topic topic) {
    if (topic.schema == null) return topic.entry.metadata();
    var metadata = com.google.gson.JsonParser.parseString(topic.entry.metadata()).getAsJsonObject();
    metadata.addProperty("capture_schema_seed", true);
    return metadata.toString();
  }

  @Override public void unannounce(Unannounce announce) {
    if (defer(() -> unannounce(announce), () -> announce.name().length() * 2L)) return;
    announced.remove(announce.id());
    var topic = topics.remove(announce.id());
    if (topic != null && output != null) io(() -> {
      output.finish(topic.entry.id(), serverUs); session.sizeBytes = output.size();
    });
  }

  @Override public void value(Announce announce, ValueFrame frame, long receivedAtUs) {
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
      topic.lastUs = frame.timestampUs(); session.sizeBytes = output.size();
      if (topic.entry.type().equals("structschema")) topic.schema = frame;
      session.minUs = Math.min(session.minUs, frame.timestampUs());
      session.maxUs = Math.max(session.maxUs, frame.timestampUs());
      cost.add(receivedAtUs, written.size());
      observer.value(session, topic.entry, frame, written);
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
      if (pendingForce == null) {
        var force = CompletableFuture.supplyAsync(() -> {
          try { expected.flush(); return loop.nowUs(); }
          catch (IOException e) { throw new java.util.concurrent.CompletionException(e); }
        }, forceThread);
        pendingForce = force;
        force.whenComplete((time, failure) -> {
          try { loop.execute(() -> {
            if (output == expected && pendingForce == force) io(this::finishForce);
          }); } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Shutdown/rollover owns the completion barrier and failure reporting now.
          }
        });
      }
      scheduleFlush(expected);
    }, FLUSH_PERIOD_US);
  }

  /** Called only by the listener loop: ordinary ticks never wait; close is a durability barrier. */
  private void finishForce() throws IOException {
    var pending = pendingForce;
    if (pending == null) return;
    pendingForce = null;
    try { long completedAt = pending.join(); if (ready()) session.observedAtUs = completedAt; }
    catch (java.util.concurrent.CompletionException e) {
      if (e.getCause() instanceof IOException failure) throw failure;
      throw new IOException("Capture flush failed", e.getCause());
    }
    observer.flushed(session);
    if (session.observedAtUs >= nextReportUs) {
      session.costs.forEach((name, cost) -> observer.cost(name, cost.snapshot(session.observedAtUs)));
      session.providers.forEach(provider -> LoggerFactory.getLogger(CaptureWriter.class).info("Capture provider {}: {}", provider.name(), provider));
      nextReportUs = session.observedAtUs + REPORT_PERIOD_US;
    }
  }

  @Override public void disconnected() {
    if (defer(this::disconnected, () -> 0)) return;
    connected = false; announced.clear(); close();
  }

  private void closeFile() throws IOException {
    var closing = output;
    if (closing == null) return;
    output = null;
    IOException failure = null;
    try { finishForce(); }
    catch (IOException e) { failure = e; }
    finally { forceThread.shutdown(); }
    try {
      for (var topic : topics.values()) closing.finish(topic.entry.id(), serverUs);
      for (var entry : contexts.values()) closing.finish(entry.id(), serverUs);
    }
    catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
    try { closing.close(); }
    catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
    session.nextEntry = closing.nextEntry(); session.open = false; session.endedAt = wallNow();
    session.observedAtUs = nowUs();
    var costs = new LinkedHashMap<String, TopicCost.Snapshot>();
    session.costs.forEach((name, cost) -> costs.put(name, cost.snapshot(session.observedAtUs)));
    session.closedCosts = Map.copyOf(costs);
    try {
      session.sizeBytes = Files.size(session.path());
      session.files.add(new ClosedFile(session.path().getFileName().toString(), session.sizeBytes,
          session.minTimestampUs(), session.maxTimestampUs(), session.endedAt()));
      observer.fileClosed(session);
    } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
    if (failure != null) throw failure;
  }

  @Override public void close() {
    if (!shutdown && defer(this::close, () -> 0)) return;
    if (output == null) return;
    io(() -> { closeFile(); topics.clear(); contexts.clear(); observer.closed(session); });
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
      failed = true;
      if (session != null) session.endReason = "Capture write failed: " + e.getMessage();
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
