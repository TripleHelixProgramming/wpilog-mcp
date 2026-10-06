/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.server;

import com.google.gson.JsonObject;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
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

/**
 * Loopback gateway adapter, deliberately unwired from server startup until the gateway milestone.
 * Java-WebSocket owns only RFC 6455; an ordered daemon loop owns core transitions and fan-out.
 * The supplied clock is robot time, allowing time-sync replies to mirror the upstream clock later.
 */
public final class Nt4Gateway implements AutoCloseable {
  private static final class Peer {
    final WebSocket socket;
    long pongUs;
    Peer(WebSocket socket, long nowUs) { this.socket = socket; this.pongUs = nowUs; }
  }
  private final GatewayCore core = new GatewayCore();
  private final LongSupplier serverClock;
  private final ScheduledThreadPoolExecutor loop;
  private final Map<String, Peer> peers = new HashMap<>();
  private final Map<WebSocket, String> connections = new HashMap<>();
  private final CompletableFuture<Void> listening = new CompletableFuture<>();
  private final SocketServer server;
  private int nextClient;
  private volatile boolean closed;

  public Nt4Gateway(InetSocketAddress address, LongSupplier serverClock) {
    this(address, serverClock, List.of(Nt4Client.V41, Nt4Client.V40));
  }

  public Nt4Gateway(InetSocketAddress address, LongSupplier serverClock, List<String> protocols) {
    if (address.isUnresolved() || !address.getAddress().isLoopbackAddress()) {
      throw new IllegalArgumentException("Milestone 1 gateway is loopback-only");
    }
    this.serverClock = serverClock;
    loop = new ScheduledThreadPoolExecutor(1, r -> {
      var t = new Thread(r, "nt4-gateway"); t.setDaemon(true); return t;
    });
    loop.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    server = new SocketServer(address, protocols);
    server.setDaemon(true);
    // The library's global pinger also pings 4.0 peers, which NT4 forbids. Use our per-peer pinger.
    server.setConnectionLostTimeout(0);
  }

  public CompletableFuture<Void> start() {
    server.start();
    loop.scheduleAtFixedRate(() -> send(core.tick(nowUs())), 1, 1, TimeUnit.MILLISECONDS);
    loop.scheduleAtFixedRate(this::heartbeat, 200, 200, TimeUnit.MILLISECONDS);
    return listening;
  }

  public int port() { return server.getPort(); }
  public int clientCount() { return core.clientCount(); }

  public CompletableFuture<Void> announce(String name, String type, JsonObject properties) {
    var copy = properties.deepCopy();
    return enqueue(() -> send(core.announce(name, type, copy)));
  }
  public CompletableFuture<Void> value(String name, long timestampUs, int code, Object value) {
    // Freeze mutable binary payloads before crossing the event-loop boundary.
    var frame = new ValueFrame(0, timestampUs, code, value);
    return enqueue(() -> core.value(name, timestampUs, code, frame.value()));
  }
  public CompletableFuture<Void> unannounce(String name) {
    return enqueue(() -> send(core.unannounce(name)));
  }
  public CompletableFuture<Void> properties(String name, JsonObject update) {
    var copy = update.deepCopy();
    return enqueue(() -> send(core.properties(name, copy)));
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
    for (var peer : peers.values()) {
      if (!Nt4Client.V41.equals(peer.socket.getProtocol().getProvidedProtocol())) continue;
      if (now - peer.pongUs >= 1_000_000) peer.socket.closeConnection(1001, "NT4 pong timeout");
      else if (peer.socket.isOpen()) {
        try { peer.socket.sendPing(); }
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
        if (delivery.control() != null) peer.socket.send(ControlMessage.encode(List.of(delivery.control())));
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
      } catch (RuntimeException e) { peer.socket.closeConnection(1011, "NT4 send failed"); }
    }
  }

  private static void sendBinary(WebSocket socket, byte[] bytes) {
    for (int offset = 0; offset < bytes.length; offset += 1200) {
      int length = Math.min(1200, bytes.length - offset);
      socket.sendFragmentedFrame(org.java_websocket.enums.Opcode.BINARY,
          ByteBuffer.wrap(bytes, offset, length), offset + length == bytes.length);
    }
  }

  @Override public void close() throws InterruptedException {
    if (closed) return;
    closed = true;
    server.stop(1000);
    loop.shutdown();
    loop.awaitTermination(5, TimeUnit.SECONDS);
  }

  private final class SocketServer extends WebSocketServer {
    SocketServer(InetSocketAddress address, List<String> protocols) {
      super(address, 1, List.of(new Draft_6455(List.of(),
          new ArrayList<>(protocols.stream().map(Protocol::new).toList()), MessagePack.MAX_BYTES)));
    }
    @Override public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(
        WebSocket socket, Draft draft, ClientHandshake request) throws InvalidDataException {
      if (!request.getResourceDescriptor().startsWith("/nt/") || request.getResourceDescriptor().length() <= 4) {
        throw new InvalidDataException(1008, "Expected /nt/<client name>");
      }
      return super.onWebsocketHandshakeReceivedAsServer(socket, draft, request);
    }
    @Override public void onStart() { listening.complete(null); }
    @Override public void onOpen(WebSocket socket, ClientHandshake request) {
      enqueue(() -> {
        String id = Integer.toString(nextClient++);
        connections.put(socket, id);
        peers.put(id, new Peer(socket, nowUs()));
        core.connect(id);
        LoggerFactory.getLogger(Nt4Gateway.class).debug("NT4 client connected: {}", request.getResourceDescriptor());
      });
    }
    @Override public void onClose(WebSocket socket, int code, String reason, boolean remote) {
      enqueue(() -> {
        var id = connections.remove(socket);
        if (id != null) { peers.remove(id); core.disconnect(id); }
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
    @Override public void onError(WebSocket socket, Exception error) {
      if (socket == null) listening.completeExceptionally(error);
      else socket.closeConnection(1011, "WebSocket error");
    }
  }
}
