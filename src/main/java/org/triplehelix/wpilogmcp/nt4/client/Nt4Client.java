/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.client;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.nt4.ControlMessage;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Properties;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Unannounce;
import org.triplehelix.wpilogmcp.nt4.MessagePack;
import org.triplehelix.wpilogmcp.nt4.TimeSync;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;

/**
 * Read-only NT4 client. All connection state and listener calls belong to one event loop; JDK
 * callbacks hand off completed messages with backpressure. A robot being off is normal: candidate
 * sweeps retry forever at 1, 2, 4, 8, 10 seconds, reset after a successful connection. IDs and clock
 * estimates never cross a connection boundary. No startup/configuration wiring exists yet.
 * Each new sweep starts at the last successfully connected candidate, then tries the others in
 * configured order, so an unavailable mDNS name does not delay every reconnect. Before any success,
 * sweeps start at the first configured candidate.
 */
public final class Nt4Client implements AutoCloseable {
  public static final String V41 = "v4.1.networktables.first.wpi.edu";
  public static final String V40 = "networktables.first.wpi.edu";

  public interface Listener {
    default void connected(URI address, String protocol) {}
    default void disconnected() {}
    default void announce(Announce topic) {}
    default void unannounce(Unannounce topic) {}
    default void properties(Properties update) {}
    default void value(Announce topic, ValueFrame value, long receivedAtUs) {}
    default void invalidValue(Announce topic, int typeCode) {}
    default void timeSync(long serverTimeUs, long receivedAtUs) {}
  }

  public record LatestValue(Object value, long serverTimestampUs, long receivedAtUs) {
    public LatestValue { if (value instanceof byte[] b) value = b.clone(); }
    @Override public Object value() { return value instanceof byte[] b ? b.clone() : value; }
  }

  private final HttpClient http;
  private final ClientScheduler loop;
  private final List<URI> addresses;
  private final Listener listener;
  private final ControlMessage.Subscribe subscription;
  private final ConcurrentHashMap<String, LatestValue> latest = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Announce> topics = new ConcurrentHashMap<>();
  private final Map<Integer, Announce> ids = new HashMap<>();
  private final TimeSync sync = new TimeSync(30_000_000);
  private final LinkedHashSet<Long> pendingSync = new LinkedHashSet<>();
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile boolean connected;
  private volatile Attempt current;
  private long retryUs = 1_000_000;
  private int preferredIndex;
  private volatile long invalidValues;

  public Nt4Client(List<URI> addresses, double periodSeconds, Listener listener) {
    this(addresses, captureSubscription(periodSeconds), listener,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), ClientScheduler.daemon());
  }

  /** Injectable loop/clock, also useful for a downstream client testing other subscription options. */
  public Nt4Client(List<URI> addresses, ControlMessage.Subscribe subscription, Listener listener,
      HttpClient http, ClientScheduler loop) {
    if (addresses.isEmpty()) throw new IllegalArgumentException("No NT4 addresses");
    this.addresses = List.copyOf(addresses);
    this.subscription = subscription;
    this.listener = listener;
    this.http = http;
    this.loop = loop;
  }

  public static ControlMessage.Subscribe captureSubscription(double periodSeconds) {
    var options = new JsonObject();
    options.addProperty("prefix", true);
    options.addProperty("all", true);
    options.addProperty("periodic", periodSeconds);
    return new ControlMessage.Subscribe(List.of(""), 0, options);
  }

  public void start() {
    if (started.compareAndSet(false, true) && !closed.get()) loop.execute(() -> connect(0));
  }

  public boolean isConnected() { return connected; }
  public long invalidValueCount() { return invalidValues; }
  public Map<String, LatestValue> latestValues() { return Map.copyOf(latest); }
  public Map<String, Announce> topics() { return Map.copyOf(topics); }
  public Optional<TimeSync.Sample> timeEstimate() { return sync.best(loop.nowUs()); }

  private void connect(int tried) {
    if (closed.get()) return;
    int index = preferredIndex;
    if (tried > 0) {
      index = tried - 1;
      if (index >= preferredIndex) index++;
    }
    var attempt = new Attempt(index, tried);
    current = attempt;
    attempt.connecting = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(2))
        .subprotocols(V41, V40).buildAsync(addresses.get(attempt.index), attempt);
    attempt.connecting.whenComplete((socket, error) -> {
      if (error != null) submit(() -> failed(attempt));
      else if (closed.get()) socket.abort();
    });
  }

  private void failed(Attempt attempt) {
    if (current != attempt) return;
    current = null;
    if (attempt.socket != null) attempt.socket.abort();
    if (attempt.open) {
      connected = false;
      ids.clear();
      topics.clear();
      latest.clear();
      sync.clear();
      pendingSync.clear();
      try { listener.disconnected(); }
      catch (RuntimeException e) {
        LoggerFactory.getLogger(Nt4Client.class).warn("NT4 disconnect listener failed: {}", e.toString());
      }
    }
    if (closed.get()) return;
    if (!attempt.open && attempt.tried + 1 < addresses.size()) connect(attempt.tried + 1);
    else {
      long delayUs = retryUs;
      retryUs = Math.min(10_000_000, retryUs * 2);
      loop.schedule(() -> connect(0), delayUs);
    }
  }

  private void opened(Attempt attempt, WebSocket socket) {
    if (closed.get() || current != attempt) { socket.abort(); return; }
    attempt.socket = socket;
    if (!List.of(V41, V40).contains(socket.getSubprotocol())) { failed(attempt); return; }
    attempt.open = true;
    preferredIndex = attempt.index;
    retryUs = 1_000_000;
    connected = true;
    attempt.lastPongUs = loop.nowUs();
    attempt.lastSyncUs = loop.nowUs();
    listener.connected(addresses.get(attempt.index), socket.getSubprotocol());
    sendSync(attempt); // First RTT precedes subscribe, avoiding the initial value burst.
    heartbeat(attempt);
  }

  private void sendSync(Attempt attempt) {
    if (current != attempt || closed.get()) return;
    long now = loop.nowUs();
    pendingSync.add(now);
    while (pendingSync.size() > 4) pendingSync.remove(pendingSync.iterator().next());
    send(attempt, () -> attempt.socket.sendBinary(ByteBuffer.wrap(new ValueFrame(-1, 0, 2, now).encode()), true));
    loop.schedule(() -> sendSync(attempt), 3_000_000);
  }

  private void heartbeat(Attempt attempt) {
    if (current != attempt || closed.get()) return;
    long now = loop.nowUs();
    boolean v41 = V41.equals(attempt.socket.getSubprotocol());
    if (now - attempt.lastSyncUs >= 10_000_000 || v41 && now - attempt.lastPongUs >= 1_000_000) {
      failed(attempt);
      return;
    }
    if (v41) send(attempt, () -> attempt.socket.sendPing(ByteBuffer.allocate(0)));
    loop.schedule(() -> heartbeat(attempt), 200_000);
  }

  private void send(Attempt attempt, Supplier<CompletableFuture<WebSocket>> action) {
    attempt.outgoing = attempt.outgoing.thenCompose(ignored -> {
      if (closed.get() || current != attempt) return CompletableFuture.completedFuture(null);
      return action.get();
    });
    attempt.outgoing.whenComplete((ignored, error) -> {
      if (error != null) submit(() -> failed(attempt));
    });
  }

  private void text(String text) {
    for (var message : ControlMessage.decode(text)) {
      if (message instanceof Announce a) {
        if (a.id() < 0) continue;
        var prior = ids.put(a.id(), a);
        if (prior != null && !prior.name().equals(a.name())) {
          topics.remove(prior.name()); latest.remove(prior.name());
        }
        topics.put(a.name(), a);
        if (!a.cached()) latest.remove(a.name());
        listener.announce(a);
      } else if (message instanceof Unannounce u) {
        var removed = ids.get(u.id());
        if (removed == null || !removed.name().equals(u.name())) continue;
        ids.remove(u.id());
        topics.remove(u.name());
        latest.remove(u.name());
        listener.unannounce(u);
      } else if (message instanceof Properties p) {
        var old = topics.get(p.name());
        if (old == null) continue;
        var changed = old.withUpdate(p.update());
        topics.put(p.name(), changed);
        ids.put(changed.id(), changed);
        if (!changed.cached()) latest.remove(changed.name());
        listener.properties(p);
      }
    }
  }

  private void binary(Attempt attempt, byte[] bytes) {
    long received = loop.nowUs();
    for (var frame : ValueFrame.decode(bytes, (id, code) -> {
      invalidValues++;
      listener.invalidValue(ids.get(id), code);
    })) {
      if (frame.topicId() == -1) {
        if (frame.typeCode() != 2 || !(frame.value() instanceof Long sent) || !pendingSync.remove(sent)) continue;
        sync.add(sent, received, frame.timestampUs());
        listener.timeSync(frame.timestampUs(), received);
        attempt.lastSyncUs = received;
        if (!attempt.subscribed) {
          attempt.subscribed = true;
          send(attempt, () -> attempt.socket.sendText(ControlMessage.encode(List.of(subscription)), true));
        }
      } else {
        var topic = ids.get(frame.topicId());
        if (topic == null) continue;
        if (topic.cached()) latest.compute(topic.name(), (name, old) -> old == null
            || frame.timestampUs() >= old.serverTimestampUs()
                ? new LatestValue(frame.value(), frame.timestampUs(), received) : old);
        listener.value(topic, frame, received); // Older timestamps still reach the lossless listener.
      }
    }
  }

  private void submit(Runnable action) {
    if (closed.get()) return;
    try { loop.execute(action); }
    catch (java.util.concurrent.RejectedExecutionException e) { if (!closed.get()) throw e; }
  }

  @Override public void close() {
    if (!closed.compareAndSet(false, true)) return;
    loop.execute(() -> {
      if (current != null) {
        if (current.connecting != null) current.connecting.cancel(true);
        failed(current);
      }
      loop.close();
    });
  }

  private final class Attempt implements WebSocket.Listener {
    final int index;
    final int tried;
    WebSocket socket;
    CompletableFuture<WebSocket> connecting;
    CompletableFuture<WebSocket> outgoing = CompletableFuture.completedFuture(null);
    boolean open;
    boolean subscribed;
    long lastPongUs;
    long lastSyncUs;
    final StringBuilder text = new StringBuilder();
    final ByteArrayOutputStream binary = new ByteArrayOutputStream();

    Attempt(int index, int tried) { this.index = index; this.tried = tried; }

    private CompletionStage<Void> dispatch(WebSocket ws, Runnable action) {
      var done = new CompletableFuture<Void>();
      if (closed.get()) { ws.abort(); return CompletableFuture.completedFuture(null); }
      submit(() -> {
        try {
          if (current == this) action.run();
        } catch (RuntimeException e) {
          LoggerFactory.getLogger(Nt4Client.class).warn("NT4 connection stopped: {}", e.toString());
          failed(this);
        } finally { done.complete(null); ws.request(1); }
      });
      return done;
    }

    @Override public void onOpen(WebSocket ws) { dispatch(ws, () -> opened(this, ws)); }
    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
      var part = data.toString();
      return dispatch(ws, () -> {
        if (text.length() + part.length() > MessagePack.MAX_BYTES) throw new IllegalArgumentException("NT4 text too large");
        text.append(part);
        if (last) { Nt4Client.this.text(text.toString()); text.setLength(0); }
      });
    }
    @Override public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
      var part = new byte[data.remaining()];
      data.get(part);
      return dispatch(ws, () -> {
        if (binary.size() + part.length > MessagePack.MAX_BYTES) throw new IllegalArgumentException("NT4 binary too large");
        binary.writeBytes(part);
        if (last) { Nt4Client.this.binary(this, binary.toByteArray()); binary.reset(); }
      });
    }
    @Override public CompletionStage<?> onPong(WebSocket ws, ByteBuffer data) {
      return dispatch(ws, () -> lastPongUs = loop.nowUs());
    }
    @Override public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
      return dispatch(ws, () -> failed(this));
    }
    @Override public void onError(WebSocket ws, Throwable error) { submit(() -> failed(this)); }
  }
}
