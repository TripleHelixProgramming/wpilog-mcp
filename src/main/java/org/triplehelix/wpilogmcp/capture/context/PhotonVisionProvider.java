/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.zip.ZipInputStream;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.nt4.MessagePack;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;

/**
 * One session-scoped backend, on its own worker. A socket requests its next message only after
 * the preceding snapshot is delivered, bounding copied work without joining the capture loop.
 * The backend has no snapshot clock: receipt is mapped through the robot's NT4 estimate.
 */
public final class PhotonVisionProvider implements AutoCloseable {
  static final int MAX_MESSAGE_BYTES = 4 * 1024 * 1024;
  static final int MAX_EXPORT_BYTES = 64 * 1024 * 1024;
  static final int MAX_INFLATED_BYTES = 256 * 1024 * 1024;
  @FunctionalInterface public interface Sink {
    CompletionStage<Void> write(Object session, long timestampUs, List<PhotonSettings.Camera> cameras, JsonObject metadata);
  }
  private static final class Attempt {
    final Object session;
    volatile WebSocket socket;
    volatile CompletableFuture<?> request;
    volatile boolean ended;
    boolean gotSnapshot;
    boolean refreshing;
    long sentUs, revision;
    String exportHash;
    Attempt(Object session) { this.session = session; }
    void close() { ended = true; if (request != null) request.cancel(true); if (socket != null) socket.abort(); }
  }
  private final URI address;
  private final HttpClient http;
  private final ClientScheduler worker;
  private final Supplier<Double> robotTime;
  private final Sink sink;
  private final Runnable activity;
  private final AtomicLong records = new AtomicLong(), bytes = new AtomicLong();
  private volatile Attempt attempt;
  private volatile Object failedSession;
  private Object accountedSession;
  private volatile boolean closed;
  private volatile String state = "waiting", reason = "No open session with an NT4 clock estimate";
  private volatile Double roundTripMs;
  private volatile long sampleBytes;

  public PhotonVisionProvider(URI address, Supplier<Double> robotTime, Sink sink, Runnable activity) {
    this(address, robotTime, sink, activity, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
        ClientScheduler.daemon("photonvision-" + address.getAuthority()));
  }
  PhotonVisionProvider(URI address, Supplier<Double> robotTime, Sink sink, Runnable activity,
      HttpClient http, ClientScheduler worker) {
    this.address = address; this.robotTime = robotTime; this.sink = sink; this.activity = activity;
    this.http = http; this.worker = worker;
  }
  /** Called only on the capture loop; no HTTP, decoding or socket waits happen here. */
  public void session(Object session, boolean open) {
    if (closed) return;
    if (!open || session == null) {
      var previous = attempt; attempt = null; if (previous != null) previous.close();
      if (failedSession != session) state("offline", "No open NT4 session");
      return;
    }
    if (failedSession == session || attempt != null && attempt.session == session) return;
    if (robotTime.get() == null) { state("waiting_for_sync", "No NT4 clock estimate"); return; }
    var previous = attempt; if (previous != null) previous.close();
    var next = new Attempt(session); attempt = next; failedSession = null;
    if (accountedSession != session) { records.set(0); bytes.set(0); accountedSession = session; }
    roundTripMs = null; sampleBytes = 0;
    state("connecting", null); submit(() -> start(next));
  }
  public void recorded(int size) { if (size > 0) { records.incrementAndGet(); bytes.addAndGet(size); } }
  public ProviderStatus status() {
    return new ProviderStatus("photonvision/" + address.getAuthority(), state, reason, 0, roundTripMs, null,
        0, 0, 0, records.get(), bytes.get(), sampleBytes);
  }
  private boolean current(Attempt a) { return !closed && attempt == a && !a.ended; }
  private void start(Attempt a) {
    if (!current(a)) return;
    try {
      a.sentUs = worker.nowUs();
      var request = HttpRequest.newBuilder(address.resolve(PhotonSettings.EXPORT_PATH)).timeout(Duration.ofSeconds(10)).GET().build();
      var pending = http.sendAsync(request, ignored -> new LimitedBody()); a.request = pending;
      var response = pending.get(10, TimeUnit.SECONDS);
      if (!current(a)) return;
      if (response.statusCode() != 200) throw new IOException("settings export HTTP " + response.statusCode());
      validateExport(response.body());
      a.exportHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(response.body()));
      sampleBytes = response.body().length; roundTripMs = (worker.nowUs() - a.sentUs) / 1000.0;
      connect(a);
    } catch (Exception e) { fail(a, explain(e)); }
  }
  /** Export storage is SQLite; current UI settings supply the structured data without a SQL runtime. */
  static void validateExport(byte[] bytes) throws IOException {
    boolean database = false; long expanded = 0; int entries = 0;
    try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (++entries > 10000) throw new IOException("settings export has too many members");
        byte[] first = zip.readNBytes(16); expanded += first.length;
        if (entry.getName().equals("photon.sqlite")) {
          if (!java.util.Arrays.equals(first, "SQLite format 3\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new IOException("settings export photon.sqlite has an unsupported header");
          }
          database = true;
        }
        byte[] buffer = new byte[8192];
        for (int n; (n = zip.read(buffer)) >= 0;) {
          expanded += n; if (expanded > MAX_INFLATED_BYTES) throw new IOException("settings export exceeds 256 MiB expanded");
        }
      }
    }
    if (!database) throw new IOException("settings export shape: missing photon.sqlite");
  }
  private void connect(Attempt a) {
    if (!current(a)) return;
    // onConnect broadcasts full state to every UI client. Stop accepting the old socket
    // before this handshake, or the refresh can record the same broadcast from both.
    var previous = a.socket; a.socket = null; if (previous != null) previous.abort();
    a.refreshing = true; if (a.gotSnapshot) a.sentUs = worker.nowUs(); long revision = ++a.revision;
    URI socketUri = URI.create("ws://" + address.getRawAuthority() + PhotonSettings.SOCKET_PATH);
    var listener = new Socket(a);
    var pending = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(socketUri, listener);
    a.request = pending;
    pending.whenComplete((socket, error) -> {
      if (error != null) fail(a, "WebSocket: " + explain(error));
      else if (!current(a)) socket.abort();
    });
    worker.schedule(() -> { if (current(a) && a.revision == revision && a.refreshing) fail(a, "No complete settings snapshot within 10 seconds"); }, 10_000_000);
  }
  private final class Socket implements WebSocket.Listener {
    final Attempt owner;
    final ByteArrayOutputStream message = new ByteArrayOutputStream();
    volatile long pongAtUs = Long.MIN_VALUE;
    long pingSentUs = Long.MIN_VALUE;
    Socket(Attempt owner) { this.owner = owner; }
    @Override public void onOpen(WebSocket socket) {
      if (!current(owner)) { socket.abort(); return; }
      var previous = owner.socket; owner.socket = socket;
      if (previous != null) previous.abort();
      socket.request(1); submit(() -> keepalive(owner, socket, this));
    }
    @Override public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
      if (!current(owner) || owner.socket != socket) return CompletableFuture.completedFuture(null);
      if ((long) message.size() + data.remaining() > MAX_MESSAGE_BYTES) {
        fail(owner, "UI message exceeds 4 MiB"); return CompletableFuture.completedFuture(null);
      }
      byte[] chunk = new byte[data.remaining()]; data.get(chunk); message.writeBytes(chunk);
      if (!last) { socket.request(1); return CompletableFuture.completedFuture(null); }
      byte[] complete = message.toByteArray(); message.reset();
      // Capture the estimate at receipt, before worker/loop queues or export work can delay it.
      Double timestamp = robotTime.get();
      var done = new CompletableFuture<Void>();
      submit(() -> {
        if (!current(owner) || owner.socket != socket) { done.complete(null); return; }
        try {
          var tree = new Gson().toJsonTree(MessagePack.decode(complete));
          var object = PhotonSettings.requireObject(tree, "WebSocket root");
          if (object.has("settings") || object.has("cameraSettings")) {
            var cameras = PhotonSettings.snapshot(object);
            if (timestamp == null) throw new IOException("Snapshot has no NT4 clock estimate");
            if (owner.refreshing) roundTripMs = (worker.nowUs() - owner.sentUs) / 1000.0;
            owner.refreshing = false; owner.gotSnapshot = true; sampleBytes = complete.length;
            var metadata = new JsonObject(); metadata.addProperty("source", "photonvision");
            metadata.addProperty("host", address.getAuthority()); metadata.addProperty("photonvision_release", PhotonSettings.RELEASE);
            metadata.addProperty("timestamp_basis", "receipt mapped through NT4 server time");
            metadata.addProperty("settings_export_sha256", owner.exportHash);
            state("following", null);
            sink.write(owner.session, Math.max(0, Math.round(timestamp)), cameras, metadata)
                .whenComplete((ignored, error) -> { if (error != null) fail(owner, explain(error)); done.complete(null); });
          } else if (object.has("mutatePipelineSettings")) {
            PhotonSettings.requireObject(object.get("mutatePipelineSettings"), "mutatePipelineSettings");
            // This release's selective broadcast omits the camera ID. Opening a new read-only
            // socket requests full state; applying the delta to a guessed camera would lie.
            if (!owner.refreshing) connect(owner);
            done.complete(null);
          } else {
            for (String key : object.keySet()) if (!List.of("log", "ntConnectionInfo", "metrics", "updatePipelineResult",
                "networkInfo", "calibrationData", "visionSourceManager").contains(key)) throw PhotonSettings.bad(key, "unknown UI message");
            done.complete(null);
          }
        } catch (Exception e) { fail(owner, explain(e)); done.complete(null); }
      });
      done.thenRun(() -> { if (current(owner) && owner.socket == socket) socket.request(1); });
      return done;
    }
    @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
      fail(owner, "Expected binary MessagePack settings, received text"); return CompletableFuture.completedFuture(null);
    }
    @Override public CompletionStage<?> onPing(WebSocket socket, ByteBuffer data) {
      // The JDK answers ping automatically; its default listener requests the next callback.
      return WebSocket.Listener.super.onPing(socket, data);
    }
    @Override public CompletionStage<?> onPong(WebSocket socket, ByteBuffer data) { pongAtUs = worker.nowUs(); socket.request(1); return null; }
    @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
      if (owner.socket == socket) fail(owner, "WebSocket closed (" + code + "): " + reason); return null;
    }
    @Override public void onError(WebSocket socket, Throwable error) {
      if (owner.socket == socket) fail(owner, "WebSocket: " + explain(error));
    }
  }
  private void keepalive(Attempt a, WebSocket socket, Socket receipt) {
    if (!current(a) || a.socket != socket) return;
    long now = worker.nowUs();
    if (receipt.pingSentUs != Long.MIN_VALUE && receipt.pongAtUs >= receipt.pingSentUs) receipt.pingSentUs = Long.MIN_VALUE;
    if (receipt.pingSentUs != Long.MIN_VALUE && now - receipt.pingSentUs >= 5_000_000) {
      fail(a, "WebSocket unanswered ping exceeded 5 seconds"); return;
    }
    if (receipt.pingSentUs == Long.MIN_VALUE) {
      receipt.pingSentUs = now;
      socket.sendPing(ByteBuffer.allocate(0)).orTimeout(5, TimeUnit.SECONDS).whenComplete((ignored, error) -> {
        if (error != null && a.socket == socket) fail(a, "WebSocket keepalive: " + explain(error));
      });
    }
    worker.schedule(() -> keepalive(a, socket, receipt), Math.min(2_000_000, 5_000_000 - (now - receipt.pingSentUs)));
  }
  private void fail(Attempt a, String why) {
    if (!current(a)) return;
    failedSession = a.session; a.close(); state("stand_down", why);
  }
  private void state(String value, String why) {
    if (value.equals(state) && java.util.Objects.equals(reason, why)) return;
    state = value; reason = why;
    LoggerFactory.getLogger(PhotonVisionProvider.class).info("PhotonVision {} {}{}", address.getAuthority(), value, why == null ? "" : ": " + why);
    activity.run();
  }
  private void submit(Runnable action) {
    if (!closed) try { worker.execute(action); } catch (java.util.concurrent.RejectedExecutionException ignored) { /* Closing. */ }
  }
  private static String explain(Throwable error) {
    while (error.getCause() != null && (error instanceof java.util.concurrent.ExecutionException || error instanceof java.util.concurrent.CompletionException)) error = error.getCause();
    return error.getClass().getSimpleName() + ": " + error.getMessage();
  }
  @Override public void close() { closed = true; var a = attempt; if (a != null) a.close(); worker.close(); }
  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> body = HttpResponse.BodySubscribers.ofByteArray();
    private java.util.concurrent.Flow.Subscription subscription;
    private long size;
    public CompletionStage<byte[]> getBody() { return body.getBody(); }
    public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription = value; body.onSubscribe(value); }
    public void onNext(List<ByteBuffer> blocks) {
      for (var block : blocks) size += block.remaining();
      if (size > MAX_EXPORT_BYTES) { subscription.cancel(); body.onError(new IOException("Settings export exceeds 64 MiB")); }
      else body.onNext(blocks);
    }
    public void onError(Throwable error) { body.onError(error); }
    public void onComplete() { body.onComplete(); }
  }
}
