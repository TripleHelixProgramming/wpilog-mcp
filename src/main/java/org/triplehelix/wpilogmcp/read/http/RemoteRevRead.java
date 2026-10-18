/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.http;

import org.triplehelix.wpilogmcp.read.internal.ReadWire;

import com.google.gson.JsonObject;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.ReadProblem;
import org.triplehelix.wpilogmcp.sync.SyncResult;

/** Streaming client for an explicitly clocked recorded-REV signal. No alignment is calculated
 * here: the descriptor states the association stored on the server. Each bounded frame has a
 * thirty-second read deadline, and only the final completion record establishes success. */
public final class RemoteRevRead implements AutoCloseable {
  public record Descriptor(String path, String signal, String clock, String state, String reason,
      String unit, SyncResult alignment, long sampleCount) {}
  public record SignalMetadata(String name, String deviceKey, String unit, long sampleCount) {}
  public record Description(String path, String state, String reason, SyncResult alignment,
      Map<String, SignalMetadata> signals) {
    public Description { signals = Map.copyOf(signals); }
  }
  /** Discovery uses the same eager parse as a query, but sends only signal metadata and recorded
   * proof. Later queries reuse the server decode; no alignment is calculated or persisted. */
  public static Description describe(URI server, String companionPath, String anchorPath) throws IOException {
    var body = paths(companionPath, anchorPath);
    var response = request(server, "/read/v1/rev/describe", body);
    try (var read = new RemoteRevRead(response.body())) {
      if (response.statusCode() != 200) throw read.errorResponse();
      var header = read.object(); requireHeader(header, "rev_description");
      var alignment = association(header);
      if (!header.has("signals") || !header.get("signals").isJsonObject()) throw new IOException("Missing REV signal inventory");
      var signals = new LinkedHashMap<String, SignalMetadata>();
      for (var entry : header.getAsJsonObject("signals").entrySet()) {
        var metadata = entry.getValue().getAsJsonObject();
        signals.put(entry.getKey(), new SignalMetadata(ReadWire.text(metadata, "name"),
            ReadWire.text(metadata, "device_key"), ReadWire.nullableText(metadata, "unit"), count(metadata)));
      }
      return new Description(ReadWire.text(header, "path"), ReadWire.text(header, "state"),
          ReadWire.text(header, "reason"), alignment, signals);
    } catch (RuntimeException failure) { throw new IOException("Invalid remote REV description", failure); }
  }
  private final InputStream raw;
  private final InputStream input;
  private static final java.net.http.HttpClient CLIENT = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
  private static final java.util.concurrent.ScheduledThreadPoolExecutor DEADLINES = deadlines();
  private static java.util.concurrent.ScheduledThreadPoolExecutor deadlines() {
    var executor = new java.util.concurrent.ScheduledThreadPoolExecutor(1, r -> {
      var thread = new Thread(r, "remote-rev-read-deadline"); thread.setDaemon(true); return thread;
    });
    executor.setRemoveOnCancelPolicy(true); return executor;
  }
  private final AtomicBoolean closed = new AtomicBoolean(), reading = new AtomicBoolean();
  private Descriptor descriptor;
  private volatile boolean complete;
  private long received;

  private RemoteRevRead(InputStream input) {
    raw = input; this.input = new BufferedInputStream(input);

  }
  public static RemoteRevRead open(URI server, String companionPath, String anchorPath, String signal,
      String clock) throws IOException {
    if (!"raw".equals(clock) && !"fpga".equals(clock)) throw new IllegalArgumentException("REV clock must be raw or fpga");
    var body = paths(companionPath, anchorPath); body.addProperty("signal", signal); body.addProperty("clock", clock);
    var response = request(server, "/read/v1/rev/query", body);
    var read = new RemoteRevRead(response.body());
    try {
      if (response.statusCode() != 200) throw read.errorResponse();
      var header = read.frame(); requireHeader(header, "rev");
      if (!signal.equals(ReadWire.text(header, "signal")) || !clock.equals(ReadWire.text(header, "clock")))
        throw new IOException("Remote REV descriptor does not match the requested signal and clock");
      String state = ReadWire.text(header, "state");
      var alignment = association(header); long count = count(header);
      if (clock.equals("fpga") && (alignment == null || !alignment.isSuccessful())) throw new IOException("Remote REV signal is unsynchronized");
      read.descriptor = new Descriptor(ReadWire.text(header, "path"), signal, clock, state,
          ReadWire.text(header, "reason"), ReadWire.nullableText(header, "unit"), alignment, count);
      return read;
    } catch (IOException | RuntimeException failure) {
      try { read.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
      if (failure instanceof IOException io) throw io;
      throw new IOException("Invalid remote REV descriptor", failure);
    }
  }
  private static JsonObject paths(String companion, String anchor) {
    var body = new JsonObject(); body.addProperty("companion_path", companion); body.addProperty("anchor_path", anchor); return body;
  }
  private static HttpResponse<InputStream> request(URI server, String route, JsonObject body) throws IOException {
    server = RemoteLogAccess.validateServer(server);
    var client = CLIENT;
    try {
      return client.send(HttpRequest.newBuilder(server.resolve(route)).timeout(Duration.ofSeconds(30))
          .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(ReadWire.bytes(body, 64 << 10))).build(),
          HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt(); throw new IOException("Interrupted while opening a remote REV read", failure);
    }
  }
  private static void requireHeader(JsonObject header, String kind) throws IOException {
    if (!kind.equals(ReadWire.text(header, "kind")) || ReadWire.integer(header, "api_version") != ReadWire.VERSION)
      throw new IOException("Unsupported remote REV descriptor");
  }
  private static long count(JsonObject object) throws IOException {
    long count = Long.parseLong(ReadWire.text(object, "sample_count"));
    if (count < 0) throw new IOException("Negative remote REV sample count");
    return count;
  }
  private static SyncResult association(JsonObject header) throws IOException {
    String state = ReadWire.text(header, "state");
    if (state.equals("unsynchronized")) return null;
    var alignment = ReadWire.JSON.fromJson(header.get("alignment"), SyncResult.class);
    if (alignment == null || alignment.method() == null || alignment.confidenceLevel() == null
        || alignment.signalPairs() == null || alignment.explanation() == null
        || !Double.isFinite(alignment.confidence()) || alignment.confidence() < 0 || alignment.confidence() > 1
        || !Double.isFinite(alignment.driftRateNanosPerSec()) || !Double.isFinite(alignment.referenceTimeSec())
        || !org.triplehelix.wpilogmcp.sync.Combination.consistent(alignment.method(), alignment.combination())
        || !(alignment.isSuccessful() ? state.equals("recorded") : state.equals("unsynchronized")))
      throw new IOException("Invalid remote REV association");
    // Gson's mutable lists must not make the supposedly fixed descriptor change after open.
    return new SyncResult(alignment.offsetMicros(), alignment.confidence(), alignment.confidenceLevel(),
        List.copyOf(alignment.signalPairs()), alignment.method(), alignment.explanation(),
        alignment.driftRateNanosPerSec(), alignment.referenceTimeSec(), alignment.combination());
  }
  public Descriptor descriptor() { return descriptor; }

  /** One immutable batch, or an empty list after the verified completion record. */
  public List<TimestampedValue> next() throws IOException {
    if (!reading.compareAndSet(false, true)) throw new IllegalStateException("Only one REV batch may be read at a time");
    try {
      if (complete) return List.of();
      if (closed.get()) throw new ReadException(ReadProblem.Code.CLOSED, "Remote REV read is closed");
      var frame = frame(); String kind = ReadWire.text(frame, "kind");
      if (kind.equals("end")) {
        ReadWire.requireEnd(frame);
        if (received != descriptor.sampleCount()) throw new IOException("Remote REV read ended before its advertised sample count");
        close(); complete = true; return List.of();
      }
      if (!kind.equals("rev_samples") || !frame.has("samples") || !frame.get("samples").isJsonArray())
        throw new IOException("Unexpected remote REV frame");
      var samples = frame.getAsJsonArray("samples");
      if (samples.isEmpty() || samples.size() > ReadWire.MAX_REV_SAMPLES
          || samples.size() > descriptor.sampleCount() - received)
        throw new IOException("Remote REV batch exceeds its declared sample bounds");
      var result = new ArrayList<TimestampedValue>(samples.size());
      for (var element : samples) {
        var sample = element.getAsJsonObject();
        var timestamp = ReadWire.value(sample.get("timestamp_sec"));
        if (!(timestamp instanceof Double seconds)) throw new IOException("Remote REV timestamp must retain double seconds");
        result.add(new TimestampedValue(seconds, ReadWire.value(sample.get("value"))));
      }
      received += result.size(); return List.copyOf(result);
    } catch (IOException | RuntimeException failure) {
      try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
      if (failure instanceof IOException io) throw io;
      throw new IOException("Invalid remote REV frame", failure);
    } finally { reading.set(false); }
  }
  private JsonObject frame() throws IOException {
    var deadline = DEADLINES.schedule(this::expire, 30, TimeUnit.SECONDS);
    try { return ReadWire.line(input); } finally { deadline.cancel(false); }
  }
  private IOException errorResponse() throws IOException {
    return ReadWire.failure(object());
  }
  private JsonObject object() throws IOException {
    var deadline = DEADLINES.schedule(this::expire, 30, TimeUnit.SECONDS);
    try {
      byte[] bytes = input.readNBytes(ReadWire.MAX_FRAME_BYTES + 1);
      if (bytes.length > ReadWire.MAX_FRAME_BYTES) throw new IOException("Oversized remote REV response");
      return ReadWire.parse(bytes);
    } finally { deadline.cancel(false); }
  }
  private void expire() { try { close(); } catch (IOException ignored) {} }
  public boolean complete() { return complete; }
  @Override public void close() throws IOException {
    if (closed.compareAndSet(false, true)) {
      // Close the underlying stream directly: a BufferedInputStream monitor can be held by the
      // blocked reader that the deadline or another caller is trying to interrupt.
      raw.close();
    }
  }
}
