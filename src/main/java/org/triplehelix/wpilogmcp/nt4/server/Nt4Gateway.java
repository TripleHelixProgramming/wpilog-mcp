/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.server;

import com.google.gson.JsonObject;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.protocols.Protocol;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.nt4.ControlMessage;
import org.triplehelix.wpilogmcp.nt4.Keepalive;
import org.triplehelix.wpilogmcp.nt4.MessagePack;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;

/**
 * Read-only gateway adapter, also used as the robot fixture on loopback.
 * Java-WebSocket owns only RFC 6455; an ordered daemon loop owns core transitions and fan-out.
 * The supplied clock is robot time. Socket writes only enqueue bounded, MTU-sized fragments;
 * a client that stops reading loses its connection, never another client's publications.
 */
public final class Nt4Gateway implements AutoCloseable {
  private static final class Peer {
    final WebSocket socket;
    final String name;
    final Keepalive keepalive = new Keepalive();
    Peer(WebSocket socket, String name) { this.socket = socket; this.name = name; }
  }
  // 32 MiB of period-pending values in the core, and at most 32,768 fragments (~38 MiB)
  // in a socket queue. A maximum-size NT4 message fits; an unread socket cannot grow forever.
  static final int MAX_QUEUED_FRAGMENTS = 32768;
  private static final int FRAGMENT_BYTES = 1200;
  private static final class SlowClient extends RuntimeException {}
  private final GatewayCore core = new GatewayCore();
  private final LongSupplier serverClock;
  private final ClientScheduler loop;
  private final Map<String, Peer> peers = new HashMap<>();
  private final Map<WebSocket, String> connections = new HashMap<>();
  private final Map<WebSocket, CompletableFuture<Void>> closing = new HashMap<>();
  // Only pong receipt crosses threads; the rest of each peer's state remains loop-owned.
  private final Map<WebSocket, Keepalive> keepalives = new java.util.concurrent.ConcurrentHashMap<>();
  private final CompletableFuture<Void> listening = new CompletableFuture<>();
  private final InetSocketAddress address;
  private final List<String> protocols;
  private final Clock wallClock;
  private final ClientScheduler binds;
  private final Object lifecycle = new Object();
  private volatile SocketServer server;
  private boolean started;
  private long retryUs = 1_000_000;
  private volatile GatewayStatus status;
  private int nextClient;
  private volatile boolean closed;
  private volatile int clientCount;
  private final int maxQueuedFragments;
  private final java.util.function.BiConsumer<String, String> disconnected;
  private long maxWorkBytes = 32L * 1024 * 1024;
  // A second of 100 topics at 50 Hz is 5,000 items. Keep the record bound consistent
  // with the core rather than treating a short GC pause as failure of every dashboard.
  private int maxWorkItems = 65536;
  static final long DEFAULT_FAN_OUT_STALL_US = 5_000_000;
  private final long maxFanOutStallUs;
  private long lastWorkProgressUs;
  private long sharedWorkBytes;
  private int sharedWorkItems;
  private static final class WorkShare {
    long bytes;
    int items;
    boolean rejected;
    final java.util.Set<Work> queued = new java.util.LinkedHashSet<>();
  }
  private static final class Work {
    final long bytes;
    final WorkShare owner;
    final WebSocket source;
    final CompletableFuture<Void> result;
    Runnable action;
    Work(long bytes, WorkShare owner, WebSocket source, CompletableFuture<Void> result, Runnable action) {
      this.bytes = bytes; this.owner = owner; this.source = source; this.result = result; this.action = action;
    }
  }
  private final java.util.Set<Work> queuedWork = new java.util.LinkedHashSet<>();
  private boolean workScheduled;
  // Admission alone owns these counters; socket/core state remains on the fan-out loop.
  private final Map<WebSocket, WorkShare> shares = new HashMap<>();
  private final Object admission = new Object();
  private long workBytes;
  private int workItems;
  private final java.util.concurrent.atomic.AtomicBoolean overflowPending = new java.util.concurrent.atomic.AtomicBoolean();
  private static final String WORK_LIMIT = "NT4 gateway work queue limit; reconnect for the current view";
  void workLimits(long bytes, int items) { maxWorkBytes = bytes; maxWorkItems = items; }

  static Draft receiveDraft(List<String> protocols, int maxBytes, int maxFragments) {
    return new BoundedReceiveDraft(protocols, maxBytes, maxFragments);
  }

  public Nt4Gateway(InetSocketAddress address, LongSupplier serverClock) {
    this(address, serverClock, List.of(Nt4Client.V41, Nt4Client.V40));
  }

  public Nt4Gateway(InetSocketAddress address, LongSupplier serverClock, List<String> protocols) {
    this(address, serverClock, protocols, MAX_QUEUED_FRAGMENTS, (name, reason) -> {});
  }

  /** Capture supplies its wall clock; tests advance bind backoff without sleeping. */
  public Nt4Gateway(InetSocketAddress address, LongSupplier serverClock, Clock wallClock, ClientScheduler binds) {
    this(address, serverClock, List.of(Nt4Client.V41, Nt4Client.V40), MAX_QUEUED_FRAGMENTS,
        (name, reason) -> {}, wallClock, binds);
  }

  /** Queue and diagnostic seams for real-socket slow-reader tests, without machine-size buffers. */
  Nt4Gateway(InetSocketAddress address, LongSupplier serverClock, List<String> protocols,
      int maxQueuedFragments, java.util.function.BiConsumer<String, String> disconnected) {
    this(address, serverClock, protocols, maxQueuedFragments, disconnected, Clock.systemUTC(),
        ClientScheduler.daemon("nt4-gateway-bind"));
  }

  private Nt4Gateway(InetSocketAddress address, LongSupplier serverClock, List<String> protocols,
      int maxQueuedFragments, java.util.function.BiConsumer<String, String> disconnected,
      Clock wallClock, ClientScheduler binds) {
    this(address, serverClock, protocols, maxQueuedFragments, disconnected, wallClock, binds,
        ClientScheduler.daemon("nt4-gateway"));
  }

  /** Separate monotonic fan-out clock for keepalive tests; bind retries have their own loop. */
  Nt4Gateway(InetSocketAddress address, LongSupplier serverClock, List<String> protocols,
      int maxQueuedFragments, java.util.function.BiConsumer<String, String> disconnected,
      Clock wallClock, ClientScheduler binds, ClientScheduler loop) {
    this(address, serverClock, protocols, maxQueuedFragments, disconnected, wallClock, binds,
        loop, DEFAULT_FAN_OUT_STALL_US);
  }

  /**
   * The global fallback requires five seconds without fan-out progress by default. This is
   * outside the loop's own keepalive judgement and tolerates short local GC/encoding stalls.
   * A peer's own pending work is still bounded immediately; recording never waits here.
   */
  Nt4Gateway(InetSocketAddress address, LongSupplier serverClock, List<String> protocols,
      int maxQueuedFragments, java.util.function.BiConsumer<String, String> disconnected,
      Clock wallClock, ClientScheduler binds, ClientScheduler loop, long maxFanOutStallUs) {
    if (maxFanOutStallUs <= 0) throw new IllegalArgumentException("Positive fan-out stall bound required");
    this.maxFanOutStallUs = maxFanOutStallUs;
    if (address.isUnresolved()) throw new IllegalArgumentException("Gateway bind address must resolve");
    if (maxQueuedFragments <= 0) throw new IllegalArgumentException("Positive socket queue limit required");
    this.maxQueuedFragments = maxQueuedFragments; this.disconnected = disconnected;
    this.serverClock = serverClock;
    this.address = address; this.protocols = List.copyOf(protocols); this.wallClock = wallClock; this.binds = binds;
    status = new GatewayStatus(GatewayStatus.State.WAITING, address.getPort(), "Not started", wallClock.instant());
    this.loop = loop;
  }

  public CompletableFuture<Void> start() {
    synchronized (lifecycle) {
      if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Gateway closed"));
      if (started) return listening;
      started = true;
      repeat(() -> send(core.tick(nowUs())), 1_000);
      repeat(this::heartbeat, 200_000);
      binds.execute(this::bind);
    }
    return listening;
  }

  public int port() { return status.port(); }
  public GatewayStatus status() { return status; }

  private void bind() {
    if (closed) return;
    ServerSocketChannel channel = null;
    try {
      // Pre-bind so a normal busy port neither leaks the library's failed channel nor
      // produces its fatal-error log on every retry. Ownership passes only on success.
      channel = ServerSocketChannel.open();
      // The library reapplies this after binding, too late to reuse a port in TIME_WAIT.
      channel.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true);
      channel.bind(address);
      var candidate = new SocketServer(channel, protocols);
      synchronized (lifecycle) {
        if (!closed) { server = candidate; candidate.start(); channel = null; }
      }
    } catch (java.io.IOException | RuntimeException error) { retry(error); }
    finally {
      if (channel != null) try { channel.close(); } catch (java.io.IOException ignored) { }
    }
  }

  private void retry(Exception error) {
    if (closed) return;
    String cause = error.getClass().getSimpleName() + ": " + error.getMessage();
    if (status.state() != GatewayStatus.State.WAITING || !cause.equals(status.cause())) {
      status = new GatewayStatus(GatewayStatus.State.WAITING, address.getPort(), cause, wallClock.instant());
      LoggerFactory.getLogger(Nt4Gateway.class).error("capture.gateway.port: waiting for port {}: {}; capture continues",
          address.getPort(), cause);
    }
    try { binds.schedule(this::bind, retryUs); }
    catch (java.util.concurrent.RejectedExecutionException ignored) { /* Shutdown won the race. */ }
    retryUs = Math.min(30_000_000, retryUs * 2);
  }

  private void onBindThread(Runnable action) {
    if (closed) return;
    try { binds.execute(() -> { if (!closed) action.run(); }); }
    catch (java.util.concurrent.RejectedExecutionException ignored) { /* Shutdown won the race. */ }
  }
  /** Published on the fan-out thread; a metrics scrape never acquires the core's state lock. */
  public int clientCount() { return clientCount; }

  public CompletableFuture<Void> announce(String name, String type, JsonObject properties) {
    return enqueue(256L + name.length() * 2L + properties.toString().length() * 2L, () -> {
      var copy = properties.deepCopy();
      return () -> send(core.announce(name, type, copy));
    });
  }
  public CompletableFuture<Void> value(String name, long timestampUs, int code, Object value) {
    // Freeze mutable binary payloads before crossing the event-loop boundary.
    return enqueue(256L + name.length() * 2L + retainedBytes(value), () -> {
      var frame = new ValueFrame(0, timestampUs, code, value);
      return () -> {
        for (String client : core.value(name, timestampUs, code, frame.value())) {
          drop(client, "NT4 client fell behind: pending subscription queue limit");
        }
      };
    });
  }
  public CompletableFuture<Void> unannounce(String name) {
    return enqueue(() -> send(core.unannounce(name)));
  }
  public CompletableFuture<Void> properties(String name, JsonObject update) {
    return enqueue(256L + name.length() * 2L + update.toString().length() * 2L, () -> {
      var copy = update.deepCopy();
      return () -> send(core.properties(name, copy));
    });
  }
  public CompletableFuture<Void> endSession() { return enqueue(() -> send(core.endSession())); }
  /** ntcore 2026 retains the first time estimate on a connection. A changed clock needs a new one. */
  public CompletableFuture<Void> resetClock() {
    return enqueue(() -> {
      for (String id : List.copyOf(peers.keySet())) drop(id, "NT4 robot clock changed; reconnect to synchronize");
    });
  }
  /**
   * Fixture disconnect barrier: peers have received the close and their sockets have closed.
   * An abrupt closeConnection only proves local close initiation, not peer delivery. Sending
   * the close frame ourselves keeps the socket until the reply/EOF; the library's close()
   * instead closes a server socket as soon as its output queue drains, before the reply.
   */
  public CompletableFuture<Void> dropClients() {
    var pending = new ArrayList<CompletableFuture<Void>>();
    return enqueue(() -> {
      for (var peer : peers.values()) {
        var previous = closing.get(peer.socket);
        if (previous != null) { pending.add(previous); continue; }
        var done = new CompletableFuture<Void>();
        closing.put(peer.socket, done); pending.add(done);
        var frame = new org.java_websocket.framing.CloseFrame();
        frame.setCode(1001); frame.setReason("fixture disconnect");
        peer.socket.sendFrame(frame);
      }
    }).thenCompose(ignored -> CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)));
  }

  private long nowUs() { return loop.nowUs(); }

  private void repeat(Runnable action, long periodUs) {
    loop.schedule(() -> {
      if (closed) return;
      action.run();
      repeat(action, periodUs);
    }, periodUs);
  }

  private CompletableFuture<Void> enqueue(Runnable action) {
    return enqueue(256, () -> action);
  }

  /** Reserve before copying: the loop can be stalled while network threads continue delivering. */
  private CompletableFuture<Void> enqueue(long bytes, java.util.function.Supplier<Runnable> prepare) {
    return enqueue(null, bytes, prepare);
  }

  private CompletableFuture<Void> enqueue(WebSocket source, long bytes,
      java.util.function.Supplier<Runnable> prepare) {
    var result = new CompletableFuture<Void>();
    if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Gateway closed"));
    if (source != null && !source.isOpen()) return CompletableFuture.failedFuture(
        new IllegalStateException("Peer closed"));
    var refused = new ArrayList<WebSocket>();
    var cancelled = new ArrayList<Work>();
    WorkShare owner;
    boolean admitted;
    boolean stalled;
    synchronized (admission) {
      if (workItems == 0) lastWorkProgressUs = nowUs();
      owner = source == null ? null : shares.computeIfAbsent(source, ignored -> new WorkShare());
      boolean full = bytes > maxWorkBytes - workBytes || workItems >= maxWorkItems;
      stalled = full && nowUs() - lastWorkProgressUs >= maxFanOutStallUs;
      if (!stalled) {
        // Shared upstream work is charged to every view once. Private receive work divides
        // the remaining capacity fairly, so a noisy peer cannot spend another peer's share.
        int active = Math.max(1, (int) shares.values().stream().filter(v -> !v.rejected).count());
        for (var entry : shares.entrySet()) {
          var share = entry.getValue();
          if (share.rejected || (source != null && source != entry.getKey())) continue;
          long byteLimit = (maxWorkBytes - sharedWorkBytes - (source == null ? bytes : 0)) / active;
          int itemLimit = (maxWorkItems - sharedWorkItems - (source == null ? 1 : 0)) / active;
          boolean exceeds = source == null
              ? bytes > maxWorkBytes - sharedWorkBytes || sharedWorkItems >= maxWorkItems
                  || share.bytes > byteLimit || share.items > itemLimit
              : bytes > byteLimit - share.bytes || share.items >= itemLimit;
          if (exceeds) {
            share.rejected = true; refused.add(entry.getKey());
            // Remove pending payloads now, not later behind the stalled loop. Ordered work for
            // surviving peers keeps its position; cancellation costs only this peer's queue.
            for (var work : share.queued) {
              queuedWork.remove(work); work.action = null; cancelled.add(work);
              releaseWork(work.bytes, share);
            }
            share.queued.clear();
          }
        }
      }
      admitted = !stalled && bytes <= maxWorkBytes - workBytes && workItems < maxWorkItems
          && (owner == null || !owner.rejected);
      if (admitted) {
        workBytes += bytes; workItems++;
        if (owner == null) { sharedWorkBytes += bytes; sharedWorkItems++; }
        else { owner.bytes += bytes; owner.items++; }
      }
    }
    for (var work : cancelled) work.result.completeExceptionally(
        new java.util.concurrent.RejectedExecutionException(WORK_LIMIT));
    for (var socket : refused) refusePeer(socket);
    if (stalled) overrun();
    if (!admitted) return CompletableFuture.failedFuture(
        new java.util.concurrent.RejectedExecutionException(WORK_LIMIT));
    try {
      var work = new Work(bytes, owner, source, result, prepare.get());
      boolean schedule;
      synchronized (admission) {
        if (closed || (owner != null && owner.rejected)) {
          releaseWork(bytes, owner);
          return CompletableFuture.failedFuture(new java.util.concurrent.RejectedExecutionException(WORK_LIMIT));
        }
        queuedWork.add(work);
        if (owner != null) owner.queued.add(work);
        schedule = !workScheduled; workScheduled = true;
      }
      if (schedule) scheduleWork();
    } catch (RuntimeException e) { releaseWork(bytes, owner); result.completeExceptionally(e); }
    return result;
  }

  private void scheduleWork() {
    try { loop.execute(this::runWork); }
    catch (java.util.concurrent.RejectedExecutionException error) { cancelWork(error); }
  }

  private void runWork() {
    Work work;
    synchronized (admission) {
      var iterator = queuedWork.iterator();
      if (!iterator.hasNext()) { workScheduled = false; return; }
      work = iterator.next(); iterator.remove();
      if (work.owner != null) work.owner.queued.remove(work);
    }
    RuntimeException failure = null;
    try { if (work.source == null || work.source.isOpen()) work.action.run(); }
    catch (RuntimeException e) { failure = e; }
    finally {
      work.action = null;
      synchronized (admission) { releaseWork(work.bytes, work.owner); lastWorkProgressUs = nowUs(); }
    }
    // Completion can enqueue more work synchronously; its predecessor is already released.
    if (failure == null) work.result.complete(null); else work.result.completeExceptionally(failure);
    scheduleWork();
  }

  private void cancelWork(RuntimeException reason) {
    List<Work> cancelled;
    synchronized (admission) {
      cancelled = List.copyOf(queuedWork); queuedWork.clear(); workScheduled = false;
      for (var work : cancelled) {
        work.action = null;
        if (work.owner != null) work.owner.queued.remove(work);
        releaseWork(work.bytes, work.owner);
      }
    }
    cancelled.forEach(work -> work.result.completeExceptionally(reason));
  }

  private void releaseWork(long bytes, WorkShare owner) {
    synchronized (admission) {
      workBytes -= bytes; workItems--;
      if (owner == null) { sharedWorkBytes -= bytes; sharedWorkItems--; }
      else { owner.bytes -= bytes; owner.items--; }
    }
  }

  private void refusePeer(WebSocket socket) {
    String reason = WORK_LIMIT + ": peer's pending work exceeded its allowance";
    socket.closeConnection(1008, reason);
    // One cleanup per rejected peer, outside the data budget; no socket I/O under admission.
    try { loop.execute(() -> { var id = connections.get(socket); if (id != null) drop(id, reason); }); }
    catch (java.util.concurrent.RejectedExecutionException ignored) { /* Closing. */ }
  }

  /** A global refusal is a last defence after measured absence of fan-out progress. */
  private void overrun() {
    if (!overflowPending.compareAndSet(false, true)) return;
    String reason = WORK_LIMIT + ": fan-out loop stalled for at least " + maxFanOutStallUs + " us";
    LoggerFactory.getLogger(Nt4Gateway.class).warn(reason);
    try {
      loop.execute(() -> {
        try {
          for (String id : List.copyOf(peers.keySet())) drop(id, reason);
          closing.values().forEach(done -> done.complete(null)); closing.clear();
        } finally { overflowPending.set(false); }
      });
    } catch (java.util.concurrent.RejectedExecutionException ignored) { overflowPending.set(false); }
  }

  private static long retainedBytes(Object value) {
    if (value instanceof byte[] bytes) return bytes.length;
    if (value instanceof String text) return 40L + text.length() * 2L;
    if (value instanceof List<?> list) {
      long size = 32L + list.size() * 8L;
      for (var item : list) size += item instanceof String text ? 40L + text.length() * 2L : 32L;
      return size;
    }
    return 32;
  }

  private void heartbeat() {
    long now = nowUs();
    for (var entry : List.copyOf(peers.entrySet())) {
      var peer = entry.getValue();
      SocketWrites.rearm(server, peer.socket);
      if (!Nt4Client.V41.equals(peer.socket.getProtocol().getProvidedProtocol())) continue;
      if (peer.keepalive.expired(now)) drop(entry.getKey(), "NT4 pong timeout");
      else if (peer.socket.isOpen()) {
        try { checkQueue(peer.socket); peer.keepalive.sent(nowUs()); peer.socket.sendPing(); }
        catch (SlowClient e) { drop(entry.getKey(), "NT4 client fell behind: socket send queue limit"); }
        catch (org.java_websocket.exceptions.WebsocketNotConnectedException e) {
          // The peer can close between isOpen and sendPing; other peers still need keepalives.
        }
      }
    }
  }

  private void send(List<GatewayCore.Delivery> deliveries) {
    for (var delivery : deliveries) {
      var peer = peers.get(delivery.client());
      if (peer == null || !peer.socket.isOpen()) continue;
      try {
        if (delivery.control() != null) sendBytes(peer.socket,
            ControlMessage.encode(List.of(delivery.control())).getBytes(java.nio.charset.StandardCharsets.UTF_8), org.java_websocket.enums.Opcode.TEXT);
        // Bound/coalesce packets near the MTU; a large individual value is fragmented by RFC 6455.
        var packet = new java.io.ByteArrayOutputStream();
        for (var value : delivery.values()) {
          byte[] encoded = value.encode();
          if (packet.size() > 0 && packet.size() + encoded.length > 1200) {
            sendBinary(peer.socket, packet.toByteArray()); packet.reset();
          }
          packet.writeBytes(encoded);
        }
        if (packet.size() > 0) sendBinary(peer.socket, packet.toByteArray());
      } catch (SlowClient e) { drop(delivery.client(), "NT4 client fell behind: socket send queue limit"); }
      catch (RuntimeException e) { drop(delivery.client(), "NT4 send failed"); }
    }
  }

  private void checkQueue(WebSocket socket) {
    // Java-WebSocket uses a LinkedBlockingQueue (constant-time size) drained by its selector.
    // Every application frame is fragmented below, so a count also bounds queued payload bytes.
    if (((org.java_websocket.WebSocketImpl) socket).outQueue.size() >= maxQueuedFragments) throw new SlowClient();
  }
  private void sendBinary(WebSocket socket, byte[] bytes) { sendBytes(socket, bytes, org.java_websocket.enums.Opcode.BINARY); }
  private void sendBytes(WebSocket socket, byte[] bytes, org.java_websocket.enums.Opcode opcode) {
    for (int offset = 0; offset < bytes.length; offset += FRAGMENT_BYTES) {
      checkQueue(socket);
      int length = Math.min(FRAGMENT_BYTES, bytes.length - offset);
      socket.sendFragmentedFrame(opcode,
          ByteBuffer.wrap(bytes, offset, length), offset + length == bytes.length);
    }
  }

  private void drop(String id, String reason) {
    var peer = peers.remove(id);
    if (peer == null) return;
    synchronized (admission) { shares.remove(peer.socket); }
    connections.remove(peer.socket); keepalives.remove(peer.socket); core.disconnect(id); clientCount = peers.size();
    LoggerFactory.getLogger(Nt4Gateway.class).warn("NT4 gateway client {} disconnected: {}", peer.name, reason);
    peer.socket.closeConnection(1001, reason); disconnected.accept(peer.name, reason);
  }

  @Override public void close() throws InterruptedException {
    final SocketServer bound;
    synchronized (lifecycle) {
      if (closed) return;
      closed = true; bound = server;
      status = new GatewayStatus(GatewayStatus.State.STOPPED, status.port(), null, wallClock.instant());
    }
    binds.close();
    listening.cancel(false);
    if (bound != null) bound.stopListening();
    cancelWork(new java.util.concurrent.RejectedExecutionException("Gateway closed"));
    loop.close();
    loop.awaitTermination(5_000_000);
    clientCount = 0;
    if (started) LoggerFactory.getLogger(Nt4Gateway.class).info("capture.gateway.port: stopped on port {}", status.port());
  }

  private final class SocketServer extends WebSocketServer {
    private final ServerSocketChannel channel;
    SocketServer(ServerSocketChannel channel, List<String> protocols) {
      super(channel);
      this.channel = channel;
      // The pre-bound constructor has no draft/worker overload. Configure both before start.
      decoders.subList(1, decoders.size()).clear();
      var drafts = List.of(receiveDraft(protocols, MessagePack.MAX_BYTES, MAX_QUEUED_FRAGMENTS));
      setWebSocketFactory(new org.java_websocket.server.DefaultWebSocketServerFactory() {
        @Override public org.java_websocket.WebSocketImpl createWebSocket(org.java_websocket.WebSocketAdapter adapter,
            List<Draft> ignored) { return super.createWebSocket(adapter, drafts); }
      });
      setReuseAddr(true); setDaemon(true);
      // The library's global pinger also pings 4.0 peers, which NT4 forbids.
      setConnectionLostTimeout(0);
    }
    void stopListening() throws InterruptedException {
      try { stop(1000); }
      finally { try { channel.close(); } catch (java.io.IOException ignored) { } }
    }
    @Override public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(
        WebSocket socket, Draft draft, ClientHandshake request) throws InvalidDataException {
      if (!request.getResourceDescriptor().startsWith("/nt/") || request.getResourceDescriptor().length() <= 4) {
        throw new InvalidDataException(1008, "Expected /nt/<client name>");
      }
      return super.onWebsocketHandshakeReceivedAsServer(socket, draft, request);
    }
    @Override public void onStart() {
      onBindThread(() -> {
        status = new GatewayStatus(GatewayStatus.State.LISTENING, getPort(), null, wallClock.instant());
        retryUs = 1_000_000;
        LoggerFactory.getLogger(Nt4Gateway.class).info("capture.gateway.port: listening on port {}", getPort());
        listening.complete(null);
      });
    }
    @Override public void onOpen(WebSocket socket, ClientHandshake request) {
      enqueue(socket, 256, () -> () -> {
        String id = Integer.toString(nextClient++);
        connections.put(socket, id);
        String name = request.getResourceDescriptor().substring(4);
        var peer = new Peer(socket, name);
        peers.put(id, peer); keepalives.put(socket, peer.keepalive);
        core.connect(id);
        clientCount = peers.size();
        LoggerFactory.getLogger(Nt4Gateway.class).info("NT4 gateway client connected: {} ({})", name, socket.getProtocol().getProvidedProtocol());
      }).exceptionally(error -> { socket.closeConnection(1008, WORK_LIMIT); return null; });
    }
    @Override public void onClose(WebSocket socket, int code, String reason, boolean remote) {
      keepalives.remove(socket);
      synchronized (admission) { shares.remove(socket); }
      // A close must release its identity even when the work budget is full.
      try { loop.execute(() -> {
        var id = connections.remove(socket);
        keepalives.remove(socket);
        if (id != null) { peers.remove(id); core.disconnect(id); clientCount = peers.size(); }
        var done = closing.remove(socket);
        if (done != null) done.complete(null);
      }); } catch (java.util.concurrent.RejectedExecutionException ignored) { /* Closing. */ }
    }
    @Override public void onMessage(WebSocket socket, String text) {
      enqueue(socket, 256L + text.length() * 2L, () -> () -> {
        var id = connections.get(socket);
        if (id == null) return;
        for (var message : ControlMessage.decode(text)) {
          if ((message instanceof ControlMessage.Publish || message instanceof ControlMessage.SetProperties)
              && core.firstWrite(id)) LoggerFactory.getLogger(Nt4Gateway.class).warn("NT4 gateway is read-only; ignoring client {} writes", id);
          send(core.receive(id, message, nowUs()));
        }
      }).exceptionally(error -> { rejectMessage(socket, error, "Invalid NT4 text"); return null; });
    }
    @Override public void onMessage(WebSocket socket, ByteBuffer data) {
      enqueue(socket, 256L + data.remaining(), () -> {
        var bytes = new byte[data.remaining()]; data.get(bytes);
        return () -> {
          var id = connections.get(socket);
          if (id == null) return;
          for (var value : ValueFrame.decode(bytes)) send(core.receive(id, value, serverClock.getAsLong()));
        };
      }).exceptionally(error -> { rejectMessage(socket, error, "Invalid NT4 binary"); return null; });
    }
    private void rejectMessage(WebSocket socket, Throwable error, String malformed) {
      if (error instanceof java.util.concurrent.RejectedExecutionException) socket.closeConnection(1008, WORK_LIMIT);
      else socket.close(1007, malformed);
    }
    @Override public void onWebsocketPong(WebSocket socket, Framedata frame) {
      var keepalive = keepalives.get(socket);
      if (keepalive != null) keepalive.received(nowUs());
    }
    @Override public void onWebsocketPing(WebSocket socket, Framedata frame) {
      var pong = new org.java_websocket.framing.PongFrame((org.java_websocket.framing.PingFrame) frame);
      // sendFrame serializes enqueueing in Java-WebSocket; application work cannot delay a pong.
      try { checkQueue(socket); socket.sendFrame(pong); }
      catch (SlowClient e) {
        enqueue(() -> { var id = connections.get(socket);
          if (id != null) drop(id, "NT4 client fell behind: socket send queue limit"); });
      }
      catch (org.java_websocket.exceptions.WebsocketNotConnectedException ignored) { }
    }
    @Override public void onError(WebSocket socket, Exception error) {
      if (socket == null) onBindThread(() -> {
        try { stopListening(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        retry(error);
      });
      else socket.closeConnection(1011, "WebSocket error");
    }
  }
}
