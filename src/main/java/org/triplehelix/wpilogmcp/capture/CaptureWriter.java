/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Unannounce;
import org.triplehelix.wpilogmcp.nt4.NtType;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client;

/**
 * The NT4 listener is the only writer. Time-sync replies, not possibly ancient retained values,
 * identify a continuing robot clock. Storage and index hooks execute on this same event loop;
 * future context providers can join it, but capture itself invents no context records.
 */
public final class CaptureWriter implements Nt4Client.Listener, AutoCloseable {
  /** Five seconds tolerates connection/RTT jitter; any backward server clock is a new boot. */
  public static final long RECONNECT_TOLERANCE_US = 5_000_000;
  public static final long FLUSH_PERIOD_US = 250_000;
  public static final long REPORT_PERIOD_US = 300_000_000;

  public interface Observer {
    Path create(String address, Instant startedAt) throws IOException;
    default void opened(Session session, boolean resumed) throws IOException {}
    default void entry(Session session, EntryInfo entry) throws IOException {}
    default void value(Session session, EntryInfo entry, ValueFrame value, WpilogOutput.Written written) throws IOException {}
    default void flushed(Session session) throws IOException {}
    default void closed(Session session) throws IOException {}
    default void cost(String topic, TopicCost.Snapshot cost) {
      LoggerFactory.getLogger(CaptureWriter.class).info("Capture topic {}: {}", topic, cost);
    }
  }

  /** Identity survives a short disconnect. Facts and paths are updated only by the writer. */
  public static final class Session {
    private volatile Path path;
    private final Instant startedAt;
    private final String address;
    private Instant endedAt;
    private boolean open;
    private int nextEntry = 1;
    private long minUs = Long.MAX_VALUE, maxUs = Long.MIN_VALUE;
    private final Map<String, TopicCost> costs = new LinkedHashMap<>();
    private Session(Path path, Instant startedAt, String address) {
      this.path = path; this.startedAt = startedAt; this.address = address;
    }
    public Path path() { return path; }
    public void relocate(Path path) { this.path = path; }
    public Instant startedAt() { return startedAt; }
    public Instant endedAt() { return endedAt; }
    public String address() { return address; }
    public boolean open() { return open; }
    public long minTimestampUs() { return minUs == Long.MAX_VALUE ? 0 : minUs; }
    public long maxTimestampUs() { return maxUs == Long.MIN_VALUE ? 0 : maxUs; }
  }

  private static final class Topic {
    final EntryInfo entry;
    final long periodUs;
    Long lastUs;
    Topic(EntryInfo entry, long periodUs) { this.entry = entry; this.periodUs = periodUs; }
  }
  private final Clock wallClock;
  private final ClientScheduler loop;
  private final CapturePolicy policy;
  private final Observer observer;
  private final Map<Integer, Topic> topics = new LinkedHashMap<>();
  private Session session;
  private WpilogOutput output;
  private String address;
  private boolean connected, synchronizedClock, resume;
  private boolean haveClock;
  private long serverUs, receiptUs, nextReportUs;
  private long rejected;

  public CaptureWriter(Clock wallClock, ClientScheduler loop, CapturePolicy policy, Observer observer) {
    this.wallClock = wallClock; this.loop = loop; this.policy = policy; this.observer = observer;
  }

  public Session session() { return session; }
  public long rejectedValues() { return rejected; }

  @Override public void connected(URI uri, String protocol) {
    address = uri.getHost(); connected = true; synchronizedClock = false; resume = false;
  }

  @Override public void timeSync(long timestampUs, long receivedAtUs) {
    if (!synchronizedClock) {
      long elapsed = receivedAtUs - receiptUs;
      resume = session != null && haveClock && timestampUs >= serverUs && elapsed >= 0
          && Math.abs((double) timestampUs - serverUs - elapsed) <= RECONNECT_TOLERANCE_US;
      synchronizedClock = true;
    }
    serverUs = timestampUs; receiptUs = receivedAtUs; haveClock = true;
  }

  @Override public void announce(Announce announce) {
    if (!connected || !synchronizedClock) throw new IllegalStateException("Capture announce before time sync");
    io(() -> {
      if (output == null) {
        if (!resume) {
          var now = wallClock.instant();
          session = new Session(observer.create(address, now), now, address);
        }
        output = new WpilogOutput(session.path(), session.nextEntry, resume);
        session.open = true; session.endedAt = null;
        observer.opened(session, resume);
        nextReportUs = loop.nowUs() + REPORT_PERIOD_US;
        scheduleFlush(output);
      }
      if (policy.excluded(announce.name()) || topics.containsKey(announce.id())) return;
      long periodUs = policy.periodUs(announce.name());
      var metadata = new JsonObject(); metadata.addProperty("source", "nt4"); metadata.addProperty("robot", address);
      if (periodUs > 0) metadata.addProperty("period_sec", periodUs / 1_000_000.0);
      String type = NtType.fromNt4(announce.type()).wpilog();
      int id = output.start("NT:" + announce.name(), type, metadata.toString(), serverUs);
      var entry = new EntryInfo(id, "NT:" + announce.name(), type, metadata.toString());
      topics.put(announce.id(), new Topic(entry, periodUs));
      session.costs.computeIfAbsent(announce.name(), ignored -> new TopicCost());
      observer.entry(session, entry);
    });
  }

  @Override public void unannounce(Unannounce announce) {
    var topic = topics.remove(announce.id());
    if (topic != null && output != null) io(() -> output.finish(topic.entry.id(), serverUs));
  }

  @Override public void value(Announce announce, ValueFrame frame, long receivedAtUs) {
    var topic = topics.get(announce.id());
    if (topic == null || output == null) return;
    var cost = session.costs.get(announce.name());
    // A different announced family cannot safely be stored as the declared WPILOG type.
    if (NtType.fromNt4(announce.type()).code() != frame.typeCode()) { rejected++; cost.drop(); return; }
    if (topic.periodUs > 0 && topic.lastUs != null && frame.timestampUs() - topic.lastUs < topic.periodUs) {
      cost.thin(); return;
    }
    io(() -> {
      var written = output.append(topic.entry.id(), frame.timestampUs(), WpilogOutput.payload(frame.typeCode(), frame.value()));
      topic.lastUs = frame.timestampUs();
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
      io(() -> { output.flush(); observer.flushed(session); });
      if (loop.nowUs() >= nextReportUs) {
        session.costs.forEach((name, cost) -> observer.cost(name, cost.snapshot(loop.nowUs())));
        nextReportUs = loop.nowUs() + REPORT_PERIOD_US;
      }
      scheduleFlush(expected);
    }, FLUSH_PERIOD_US);
  }

  @Override public void disconnected() { connected = false; close(); }

  @Override public void close() {
    var closing = output;
    if (closing == null) return;
    output = null;
    io(() -> {
      try {
        for (var topic : topics.values()) closing.finish(topic.entry.id(), serverUs);
      } finally {
        topics.clear(); session.nextEntry = closing.nextEntry();
        try { closing.close(); }
        finally { session.open = false; session.endedAt = wallClock.instant(); }
      }
      observer.closed(session);
    });
  }

  @FunctionalInterface private interface IO { void run() throws IOException; }
  private static void io(IO operation) {
    try { operation.run(); } catch (IOException e) { throw new UncheckedIOException("Capture write failed", e); }
  }
}
