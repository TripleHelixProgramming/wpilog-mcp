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
 * manifest work takes snapshots and never waits here, except when creating a capture.
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
    /** Creation is the only store barrier, including reopening a previously hashed file. */
    default Path create(String address, Instant startedAt, Session previous) throws IOException {
      return previous == null ? create(address, startedAt) : previous.path();
    }
    default Path create(String address, Instant startedAt, Session previous, DeviceIdentity identity) throws IOException {
      return create(address, startedAt, previous);
    }
    /** A file creation barrier: the old writer and mapping are closed before directory promotion. */
    default void identity(Session session) throws IOException {}
    default void opened(Session session, boolean resumed) throws IOException {}
    default void entry(Session session, EntryInfo entry) throws IOException {}
    default void metadata(Session session, EntryInfo entry) throws IOException {}
    default void value(Session session, EntryInfo entry, ValueFrame value, WpilogOutput.Written written) throws IOException {}
    default void flushed(Session session) throws IOException {}
    default void timeSync(Session session, long serverTimeUs) throws IOException {}
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
    private Instant endedAt;
    private boolean open;
    private String endReason;
    private DeviceIdentity identity;
    private int nextEntry = 1, fileNumber = 1;
    private long minUs = Long.MAX_VALUE, maxUs = Long.MIN_VALUE, sizeBytes, observedAtUs;
    private final List<ClosedFile> files = new ArrayList<>();
    private final Map<String, TopicCost> costs = new LinkedHashMap<>();
    private volatile Map<String, TopicCost.Snapshot> closedCosts = Map.of();
    private Session(Path path, Instant startedAt, String address) {
      this.path = path; this.startedAt = startedAt; this.address = address;
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
    public List<ClosedFile> files() { return List.copyOf(files); }
    /** Accounting through the last closed file, safe to inspect after the listener has stopped. */
    public Map<String, TopicCost.Snapshot> closedCosts() { return closedCosts; }
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
  private final Map<Integer, Announce> announced = new LinkedHashMap<>();
  private Session session;
  private WpilogOutput output;
  private String address;
  private DeviceIdentity identity;
  private boolean connected, synchronizedClock, resume, failed;
  private boolean haveClock;
  private long serverUs, receiptUs, nextReportUs;
  private long rejected;

  public CaptureWriter(Clock wallClock, ClientScheduler loop, CapturePolicy policy, Observer observer) {
    this(wallClock, loop, policy, observer, DEFAULT_MAX_FILE_BYTES);
  }
  public CaptureWriter(Clock wallClock, ClientScheduler loop, CapturePolicy policy, Observer observer, long maxFileBytes) {
    this(wallClock, loop, policy, observer, maxFileBytes, WpilogOutput::new);
  }
  /** The output factory permits failures after real record writes without filling a test disk. */
  public CaptureWriter(Clock wallClock, ClientScheduler loop, CapturePolicy policy, Observer observer,
      long maxFileBytes, OutputFactory outputs) {
    if (maxFileBytes < 256 || maxFileBytes > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid capture.max_file_bytes");
    this.wallClock = wallClock; this.loop = loop; this.policy = policy; this.observer = observer;
    this.maxFileBytes = maxFileBytes; this.outputs = outputs;
  }

  public Session session() { return session; }
  public long rejectedValues() { return rejected; }

  /** Called on the listener thread after SSH has supplied device evidence. */
  public void identity(DeviceIdentity device) {
    identity = device;
    if (session == null || output == null || !device.address().equals(address)) return;
    session.identity = device;
    io(() -> {
      writeIdentity();
      observer.identity(session);
    });
  }

  @Override public void connected(URI uri, String protocol) {
    address = uri.getHost(); connected = true; synchronizedClock = false; resume = false;
  }

  @Override public void timeSync(long timestampUs, long receivedAtUs) {
    long elapsed = receivedAtUs - receiptUs;
    boolean continuing = haveClock && timestampUs >= serverUs && elapsed >= 0
        && Math.abs((double) timestampUs - serverUs - elapsed) <= RECONNECT_TOLERANCE_US;
    boolean restartHere = failed && synchronizedClock && !continuing;
    if (!synchronizedClock || restartHere) {
      resume = session != null && continuing;
      if (!continuing) failed = false;
      synchronizedClock = true;
    }
    serverUs = timestampUs; receiptUs = receivedAtUs; haveClock = true;
    if (restartHere) for (var topic : List.copyOf(announced.values())) announce(topic);
    if (output != null) io(() -> observer.timeSync(session, timestampUs));
  }

  @Override public void announce(Announce announce) {
    if (!connected || !synchronizedClock) throw new IllegalStateException("Capture announce before time sync");
    announced.put(announce.id(), announce);
    if (failed) return;
    io(() -> {
      if (output == null) {
        if (!resume) {
          var now = wallClock.instant();
          session = null; // A failed new create must not overwrite the previous session's reason.
          var device = identity != null && identity.address().equals(address) ? identity : null;
          session = new Session(observer.create(address, now, null, device), now, address);
          session.identity = device;
        } else {
          if (identity != null && identity.address().equals(address)) session.identity = identity;
          session.relocate(observer.create(address, session.startedAt(), session, session.identity));
        }
        openFile(resume);
        nextReportUs = loop.nowUs() + REPORT_PERIOD_US;
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
    session.files.removeIf(f -> f.name().equals(session.path().getFileName().toString()));
    session.open = true; session.endedAt = null; session.sizeBytes = output.size();
    session.observedAtUs = loop.nowUs();
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
    if (output.size() + bytes + (long) topics.size() * FINISH_RESERVE_BYTES > maxFileBytes) {
      if (12L + bytes + (long) topics.size() * FINISH_RESERVE_BYTES > maxFileBytes) {
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

  /** Reserve every finish before appending, so even the closed file stays below the bound. */
  private void room(int bytes, int extraEntries) throws IOException {
    long reserved = (topics.size() + (long) extraEntries) * FINISH_RESERVE_BYTES;
    if (output.size() + bytes + reserved <= maxFileBytes) return;
    long declarations = topics.values().stream().mapToLong(t -> WpilogOutput.startSize(
        t.entry.name(), t.entry.type(), rolloverMetadata(t), serverUs)
        + (t.schema == null ? 0 : WpilogOutput.recordSize(Integer.MAX_VALUE, serverUs, ((byte[]) t.schema.value()).length))).sum();
    if (12 + declarations + identitySize() + bytes + reserved > maxFileBytes) {
      throw new IOException("capture.max_file_bytes cannot hold the active declarations, record, and finishes");
    }
    closeFile();
    nextFile();
  }

  private void nextFile() throws IOException {
    long declarations = topics.values().stream().mapToLong(t -> WpilogOutput.startSize(
        t.entry.name(), t.entry.type(), rolloverMetadata(t), serverUs)
        + (t.schema == null ? 0 : WpilogOutput.recordSize(Integer.MAX_VALUE, serverUs, ((byte[]) t.schema.value()).length))).sum();
    if (12 + declarations + identitySize() + (long) topics.size() * FINISH_RESERVE_BYTES > maxFileBytes) {
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
    session.sizeBytes = output.size();
  }

  private static String rolloverMetadata(Topic topic) {
    if (topic.schema == null) return topic.entry.metadata();
    var metadata = com.google.gson.JsonParser.parseString(topic.entry.metadata()).getAsJsonObject();
    metadata.addProperty("capture_schema_seed", true);
    return metadata.toString();
  }

  @Override public void unannounce(Unannounce announce) {
    announced.remove(announce.id());
    var topic = topics.remove(announce.id());
    if (topic != null && output != null) io(() -> {
      output.finish(topic.entry.id(), serverUs); session.sizeBytes = output.size();
    });
  }

  @Override public void value(Announce announce, ValueFrame frame, long receivedAtUs) {
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
    rejected++;
    if (session != null && topic != null) session.costs.computeIfAbsent(topic.name(), ignored -> new TopicCost()).drop();
  }

  private void scheduleFlush(WpilogOutput expected) {
    loop.schedule(() -> {
      if (output != expected) return;
      io(() -> {
        output.flush(); session.observedAtUs = loop.nowUs(); observer.flushed(session);
      });
      if (output != expected) return;
      if (loop.nowUs() >= nextReportUs) {
        session.costs.forEach((name, cost) -> observer.cost(name, cost.snapshot(loop.nowUs())));
        nextReportUs = loop.nowUs() + REPORT_PERIOD_US;
      }
      scheduleFlush(expected);
    }, FLUSH_PERIOD_US);
  }

  @Override public void disconnected() { connected = false; announced.clear(); close(); }

  private void closeFile() throws IOException {
    var closing = output;
    if (closing == null) return;
    output = null;
    IOException failure = null;
    try { for (var topic : topics.values()) closing.finish(topic.entry.id(), serverUs); }
    catch (IOException e) { failure = e; }
    try { closing.close(); }
    catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
    session.nextEntry = closing.nextEntry(); session.open = false; session.endedAt = wallClock.instant();
    session.observedAtUs = loop.nowUs();
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
    if (output == null) return;
    io(() -> { closeFile(); topics.clear(); observer.closed(session); });
  }

  @FunctionalInterface private interface IO { void run() throws IOException; }
  private void io(IO operation) {
    try { operation.run(); }
    catch (IOException e) {
      failed = true;
      if (session != null) session.endReason = "Capture write failed: " + e.getMessage();
      LoggerFactory.getLogger(CaptureWriter.class).error("Capture recording stopped until a new robot clock: {}", e.getMessage());
      try { closeFile(); } catch (IOException close) { e.addSuppressed(close); }
      topics.clear();
      if (session != null) {
        session.open = false; session.endedAt = wallClock.instant(); session.observedAtUs = loop.nowUs();
        try { observer.closed(session); }
        catch (IOException close) { LoggerFactory.getLogger(CaptureWriter.class).error("Capture failure could not be recorded", close); }
      }
    }
  }
}
