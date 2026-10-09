/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.config.ContextConfig;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;

/**
 * One JMX call at a time, away from capture. The external deadline publishes a stand-down even
 * when RMI ignores interruption; no replacement thread is created while that call still owns I/O.
 * Samples carry their session token through delivery, so a delayed reply cannot enter a new boot.
 */
public final class JvmProvider implements AutoCloseable {
  static final long DEADLINE_US = 5_000_000;
  static final String FLAGS = "Enable the robot launch flags -Dcom.sun.management.jmxremote.port, "
      + "-Dcom.sun.management.jmxremote.rmi.port (the same port), -Djava.rmi.server.hostname, "
      + "-Dcom.sun.management.jmxremote.authenticate=false and -Dcom.sun.management.jmxremote.ssl=false on a private network";
  public record Sample(long timestampUs, Map<String, Number> values, JsonObject runtime, JsonObject note, JsonObject metadata) {
    public Sample { values = Map.copyOf(values); }
  }
  @FunctionalInterface public interface Sink { CompletionStage<Void> write(Object session, Sample sample); }
  @FunctionalInterface interface Connect { JMXConnector open(String host, int port) throws Exception; }
  private record Target(Object session, String host) {}
  private static final class Attempt {
    final Target target;
    volatile boolean cancelled, ioPending;
    boolean timedOut;
    volatile JMXConnector connector;
    JvmSample reader;
    boolean runtimeWritten;
    long operation;
    Attempt(Target target) { this.target = target; }
    void release() { if (connector != null) try { connector.close(); } catch (Exception ignored) { /* Already failed. */ } }
  }
  private final ContextConfig.Jvm config;
  private final Supplier<Double> robotTime, ntRoundTripUs;
  private final Sink sink;
  private final Runnable activity;
  private final ClientScheduler control;
  private final ExecutorService io;
  private final Connect connect;
  private volatile Target desired;
  private volatile boolean closed;
  private volatile Attempt attempt;
  private boolean busy;
  private long retryUs = 1_000_000;
  private Object accountedSession;
  private Double previousOffset, previousBound;
  private final AtomicLong records = new AtomicLong(), bytes = new AtomicLong(), dropped = new AtomicLong();
  private volatile String state = "offline", reason = "No open NT4 session";
  private volatile Double roundTripMs;
  private volatile long sampleBytes;

  public JvmProvider(ContextConfig.Jvm config, Supplier<Double> robotTime, Supplier<Double> ntRoundTripUs, Sink sink, Runnable activity) {
    this(config, robotTime, ntRoundTripUs, sink, activity, ClientScheduler.daemon("jmx-control"), JvmProvider::connect);
  }
  JvmProvider(ContextConfig.Jvm config, Supplier<Double> robotTime, Supplier<Double> ntRoundTripUs,
      Sink sink, Runnable activity, ClientScheduler control, Connect connect) {
    this.config = config; this.robotTime = robotTime; this.ntRoundTripUs = ntRoundTripUs;
    this.sink = sink; this.activity = activity; this.control = control; this.connect = connect;
    io = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "jmx-io"); t.setDaemon(true); return t; });
  }
  static JMXConnector connect(String host, int port) throws Exception {
    String authority = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    return JMXConnectorFactory.connect(new JMXServiceURL("service:jmx:rmi:///jndi/rmi://" + authority + ":" + port + "/jmxrmi"));
  }
  /** Capture loop method: publishes admission only, with no RMI call or join. */
  public void session(Object session, boolean open, String host) {
    Target next = open && session != null && host != null ? new Target(session, host) : null;
    if (same(desired, next)) return;
    desired = next;
    submit(() -> {
      if (attempt != null) attempt.cancelled = true;
      if (next == null) state("offline", "No open NT4 session");
      if (!busy) replace();
    });
  }
  private static boolean same(Target a, Target b) { return a == b || a != null && b != null && a.session == b.session && a.host.equals(b.host); }
  private boolean current(Attempt a) { return !closed && !a.cancelled && attempt == a && same(desired, a.target); }
  private void replace() {
    var old = attempt; attempt = null;
    if (old != null) {
      busy = true;
      io.execute(() -> { old.release(); submit(() -> { busy = false; replace(); }); });
      return;
    }
    var target = desired;
    if (closed || target == null) return;
    if (accountedSession != target.session) {
      accountedSession = target.session; records.set(0); bytes.set(0); dropped.set(0);
      previousOffset = null; previousBound = null; sampleBytes = 0; roundTripMs = null;
    }
    retryUs = 1_000_000; attempt = new Attempt(target); poll(attempt);
  }
  private void poll(Attempt a) {
    if (!current(a) || busy) return;
    busy = true; a.ioPending = true; long operation = ++a.operation;
    state(a.connector == null ? "connecting" : "sampling", null);
    control.schedule(() -> {
      if (current(a) && a.ioPending && a.operation == operation) {
        a.timedOut = true;
        state("stand_down", "JMX call exceeded 5 seconds; waiting for that call to finish before retrying. " + FLAGS);
      }
    }, DEADLINE_US);
    io.execute(() -> {
      Sample sample = null; Throwable failure = null; double rtt = 0; long size = 0;
      try {
        if (!current(a)) throw new CancellationException("Session ended before JMX admission");
        long sent = System.nanoTime();
        if (a.connector == null) {
          a.connector = connect.open(a.target.host, config.port());
          a.reader = new JvmSample(a.connector.getMBeanServerConnection()); a.runtimeWritten = false;
        }
        var reading = a.reader.read(!a.runtimeWritten);
        // Stamp on receipt on the I/O thread, before control or capture delivery queues.
        Double received = robotTime.get(), ntBound = ntRoundTripUs.get();
        rtt = (System.nanoTime() - sent) / 1_000_000.0;
        if (received == null || ntBound == null) dropped.incrementAndGet();
        else {
          double bound = rtt / 1000.0 + ntBound / 1_000_000.0 + .001; // Uptime quantizes to milliseconds.
          double offset = received / 1_000_000.0 - reading.values().get("uptime_sec").doubleValue();
          var values = new LinkedHashMap<>(reading.values());
          values.put("clock/offset_sec", offset); values.put("clock/round_trip_bound_sec", bound);
          var metadata = new JsonObject(); metadata.addProperty("source", "jmx"); metadata.addProperty("host", a.target.host);
          metadata.addProperty("sampled", true); metadata.addProperty("period_sec", config.periodUs() / 1_000_000.0);
          metadata.addProperty("clock", "measured"); metadata.addProperty("timestamp_basis", "receipt mapped through NT4 server time");
          metadata.addProperty("jvm_start_time_ms", reading.startTimeMs());
          sample = new Sample(Math.max(0, Math.round(received)), values, reading.runtime(), null, metadata);
          size = JvmSample.payloadBytes(values, reading.runtime(), null);
        }
      } catch (Exception e) { failure = e; }
      a.ioPending = false;
      Sample result = sample; Throwable error = failure; double elapsed = rtt; long payload = size;
      submit(() -> finish(a, operation, result, error, elapsed, payload));
      if (closed) a.release();
    });
  }
  private void finish(Attempt a, long operation, Sample sample, Throwable error, double rtt, long size) {
    if (attempt != a || a.operation != operation) return;
    busy = false;
    if (!current(a)) { replace(); return; }
    if (a.timedOut) { a.timedOut = false; error = new java.io.IOException("JMX call deadline exceeded"); }
    if (error != null) {
      state("stand_down", "JMX connection/poll failed (" + error.getClass().getSimpleName() + "). " + FLAGS);
      long delay = retryUs; retryUs = Math.min(30_000_000, retryUs * 2);
      // Cleanup also belongs to I/O. A stuck close never allocates an unbounded retry backlog.
      busy = true;
      io.execute(() -> { a.release(); a.connector = null; submit(() -> {
        busy = false;
        if (current(a)) control.schedule(() -> poll(a), delay); else replace();
      }); });
      return;
    }
    retryUs = 1_000_000; roundTripMs = rtt; sampleBytes = size;
    if (sample == null) { state("waiting_for_sync", "No NT4 clock estimate; sample dropped"); schedule(a); return; }
    double offset = sample.values().get("clock/offset_sec").doubleValue(), bound = sample.values().get("clock/round_trip_bound_sec").doubleValue();
    JsonObject note = null;
    if (previousOffset != null && Math.abs(offset - previousOffset) > bound + previousBound) {
      note = new JsonObject(); note.addProperty("previous_offset_sec", previousOffset); note.addProperty("offset_sec", offset);
      note.addProperty("change_sec", offset - previousOffset); note.addProperty("round_trip_bound_sec", bound + previousBound);
      note.addProperty("reason", "JVM uptime to FPGA mapping changed beyond the two sample bounds; JVM restart or clock skew, not a wall-clock correction");
    }
    var delivered = new Sample(sample.timestampUs(), sample.values(), sample.runtime(), note, sample.metadata());
    sampleBytes = JvmSample.payloadBytes(sample.values(), sample.runtime(), note);
    busy = true;
    sink.write(a.target.session, delivered).whenComplete((ignored, failure) -> submit(() -> {
      busy = false;
      if (!current(a)) { replace(); return; }
      if (failure != null) { state("stand_down", "JMX sample delivery failed: " + failure.getClass().getSimpleName()); return; }
      previousOffset = offset; previousBound = bound; a.runtimeWritten = true;
      state("sampling", null); activity.run(); schedule(a);
    }));
  }
  private void schedule(Attempt a) { control.schedule(() -> poll(a), config.periodUs()); }
  public void recorded(int size) { if (size > 0) { records.incrementAndGet(); bytes.addAndGet(size); } }
  public ProviderStatus status() { return new ProviderStatus("jvm", state, reason, config.periodUs() / 1_000_000.0,
      roundTripMs, null, 0, 0, dropped.get(), records.get(), bytes.get(), sampleBytes); }
  private void state(String value, String why) {
    if (value.equals(state) && Objects.equals(reason, why)) return;
    state = value; reason = why;
    LoggerFactory.getLogger(JvmProvider.class).info("JVM provider {}{}", value, why == null ? "" : ": " + why);
    activity.run();
  }
  private void submit(Runnable action) {
    if (!closed) try { control.execute(action); } catch (RejectedExecutionException ignored) { /* Shutdown. */ }
  }
  @Override public void close() {
    closed = true; desired = null; control.close();
    var a = attempt;
    if (a != null) { a.cancelled = true; try { io.execute(a::release); } catch (RejectedExecutionException ignored) { } }
    io.shutdown(); // Daemon I/O may still be in RMI. Never make capture shutdown join it.
  }
}
