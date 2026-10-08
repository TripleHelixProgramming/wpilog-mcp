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
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
    long pongUs;
    Peer(WebSocket socket, String name, long nowUs) { this.socket = socket; this.name = name; this.pongUs = nowUs; }
  }
  // 32 MiB of period-pending values in the core, and at most 32,768 fragments (~38 MiB)
  // in a socket queue. A maximum-size NT4 message fits; an unread socket cannot grow forever.
  static final int MAX_QUEUED_FRAGMENTS = 32768;
  private static final int FRAGMENT_BYTES = 1200;
  private static final class SlowClient extends RuntimeException {}
  private final GatewayCore core = new GatewayCore();
  private final LongSupplier serverClock;
  private final ScheduledThreadPoolExecutor loop;
  private final Map<String, Peer> peers = new HashMap<>();
  private final Map<WebSocket, String> connections = new HashMap<>();
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
    if (address.isUnresolved()) throw new IllegalArgumentException("Gateway bind address must resolve");
    if (maxQueuedFragments <= 0) throw new IllegalArgumentException("Positive socket queue limit required");
    this.maxQueuedFragments = maxQueuedFragments; this.disconnected = disconnected;
    this.serverClock = serverClock;
    this.address = address; this.protocols = List.copyOf(protocols); this.wallClock = wallClock; this.binds = binds;
    status = new GatewayStatus(GatewayStatus.State.WAITING, address.getPort(), "Not started", wallClock.instant());
    loop = new ScheduledThreadPoolExecutor(1, r -> {
      var t = new Thread(r, "nt4-gateway"); t.setDaemon(true); return t;
    });
    loop.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  public CompletableFuture<Void> start() {
    synchronized (lifecycle) {
      if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Gateway closed"));
      if (started) return listening;
      started = true;
      loop.scheduleAtFixedRate(() -> send(core.tick(nowUs())), 1, 1, TimeUnit.MILLISECONDS);
      loop.scheduleAtFixedRate(this::heartbeat, 200, 200, TimeUnit.MILLISECONDS);
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
    var copy = properties.deepCopy();
    return enqueue(() -> send(core.announce(name, type, copy)));
  }
  public CompletableFuture<Void> value(String name, long timestampUs, int code, Object value) {
    // Freeze mutable binary payloads before crossing the event-loop boundary.
    var frame = new ValueFrame(0, timestampUs, code, value);
    return enqueue(() -> {
      for (String client : core.value(name, timestampUs, code, frame.value())) {
        drop(client, "NT4 client fell behind: pending subscription queue limit");
      }
    });
  }
  public CompletableFuture<Void> unannounce(String name) {
    return enqueue(() -> send(core.unannounce(name)));
  }
  public CompletableFuture<Void> properties(String name, JsonObject update) {
    var copy = update.deepCopy();
    return enqueue(() -> send(core.properties(name, copy)));
  }
  public CompletableFuture<Void> endSession() { return enqueue(() -> send(core.endSession())); }
  /** ntcore 2026 retains the first time estimate on a connection. A changed clock needs a new one. */
  public CompletableFuture<Void> resetClock() {
    return enqueue(() -> {
      for (String id : List.copyOf(peers.keySet())) drop(id, "NT4 robot clock changed; reconnect to synchronize");
    });
  }
  public CompletableFuture<Void> dropClients() {
    return enqueue(() -> {
      for (var peer : peers.values()) peer.socket.closeConnection(1001, "fixture disconnect");
    });
  }

  private static long nowUs() { return System.nanoTime() / 1000; }

  private CompletableFuture<Void> enqueue(Runnable action) {
    var result = new CompletableFuture<Void>();
    if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Gateway closed"));
    try {
      loop.execute(() -> {
        try { action.run(); result.complete(null); }
        catch (RuntimeException e) { result.completeExceptionally(e); }
      });
    } catch (java.util.concurrent.RejectedExecutionException e) { result.completeExceptionally(e); }
    return result;
  }

  private void heartbeat() {
    long now = nowUs();
    for (var entry : List.copyOf(peers.entrySet())) {
      var peer = entry.getValue();
      SocketWrites.rearm(server, peer.socket);
      if (!Nt4Client.V41.equals(peer.socket.getProtocol().getProvidedProtocol())) continue;
      if (now - peer.pongUs >= 1_000_000) drop(entry.getKey(), "NT4 pong timeout");
      else if (peer.socket.isOpen()) {
        try { checkQueue(peer.socket); peer.socket.sendPing(); }
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
    connections.remove(peer.socket); core.disconnect(id); clientCount = peers.size();
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
    loop.shutdown();
    loop.awaitTermination(5, TimeUnit.SECONDS);
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
      var drafts = List.<Draft>of(new Draft_6455(List.of(),
          new ArrayList<>(protocols.stream().map(Protocol::new).toList()), MessagePack.MAX_BYTES));
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
      enqueue(() -> {
        String id = Integer.toString(nextClient++);
        connections.put(socket, id);
        String name = request.getResourceDescriptor().substring(4);
        peers.put(id, new Peer(socket, name, nowUs()));
        core.connect(id);
        clientCount = peers.size();
        LoggerFactory.getLogger(Nt4Gateway.class).info("NT4 gateway client connected: {} ({})", name, socket.getProtocol().getProvidedProtocol());
      });
    }
    @Override public void onClose(WebSocket socket, int code, String reason, boolean remote) {
      enqueue(() -> {
        var id = connections.remove(socket);
        if (id != null) { peers.remove(id); core.disconnect(id); clientCount = peers.size(); }
      });
    }
    @Override public void onMessage(WebSocket socket, String text) {
      enqueue(() -> {
        var id = connections.get(socket);
        if (id == null) return;
        for (var message : ControlMessage.decode(text)) {
          if ((message instanceof ControlMessage.Publish || message instanceof ControlMessage.SetProperties)
              && core.firstWrite(id)) LoggerFactory.getLogger(Nt4Gateway.class).warn("NT4 gateway is read-only; ignoring client {} writes", id);
          send(core.receive(id, message, nowUs()));
        }
      }).exceptionally(error -> { socket.close(1007, "Invalid NT4 text"); return null; });
    }
    @Override public void onMessage(WebSocket socket, ByteBuffer data) {
      var bytes = new byte[data.remaining()]; data.get(bytes);
      enqueue(() -> {
        var id = connections.get(socket);
        if (id == null) return;
        for (var value : ValueFrame.decode(bytes)) send(core.receive(id, value, serverClock.getAsLong()));
      }).exceptionally(error -> { socket.close(1007, "Invalid NT4 binary"); return null; });
    }
    @Override public void onWebsocketPong(WebSocket socket, Framedata frame) {
      enqueue(() -> {
        var peer = peers.get(connections.get(socket));
        if (peer != null) peer.pongUs = nowUs();
      });
    }
    @Override public void onWebsocketPing(WebSocket socket, Framedata frame) {
      var pong = new org.java_websocket.framing.PongFrame((org.java_websocket.framing.PingFrame) frame);
      enqueue(() -> {
        var id = connections.get(socket); if (id == null) return;
        try { checkQueue(socket); socket.sendFrame(pong); }
        catch (SlowClient e) { drop(id, "NT4 client fell behind: socket send queue limit"); }
        catch (org.java_websocket.exceptions.WebsocketNotConnectedException ignored) { }
      });
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
