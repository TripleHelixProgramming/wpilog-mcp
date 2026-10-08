/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.server;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.enums.Opcode;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.protocols.Protocol;
import org.java_websocket.server.WebSocketServer;
import org.triplehelix.wpilogmcp.nt4.ControlMessage;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;

/** A scripted wire peer independent of GatewayCore, for fragmentation and aliveness faults. */
public final class ScriptedPeer extends WebSocketServer implements AutoCloseable {
  public final CompletableFuture<WebSocket> connected = new CompletableFuture<>();
  public final CompletableFuture<Void> listening = new CompletableFuture<>();
  public final BlockingQueue<ControlMessage> controls = new LinkedBlockingQueue<>();
  public final BlockingQueue<ValueFrame> frames = new LinkedBlockingQueue<>();
  public final AtomicInteger pings = new AtomicInteger();
  public volatile boolean answerSync = true;
  public volatile boolean answerPing = true;
  public volatile String offeredProtocols;
  public volatile String resource;
  private final java.util.concurrent.ScheduledExecutorService writes = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
    var thread = new Thread(r, "scripted-peer-writes"); thread.setDaemon(true); return thread;
  });

  public ScriptedPeer(String protocol) throws Exception {
    super(new InetSocketAddress("127.0.0.1", 0), 1,
        List.of(new Draft_6455(List.of(), List.of(new Protocol(protocol)))));
    setDaemon(true); setConnectionLostTimeout(0); start(); listening.get(5, TimeUnit.SECONDS);
    // The independent wire fixture uses the same RFC 6455 library, including its write-demand race.
    writes.scheduleAtFixedRate(() -> {
      var socket = connected.getNow(null); if (socket != null) SocketWrites.rearm(this, socket);
    }, 200, 200, TimeUnit.MILLISECONDS);
  }

  @Override public void onStart() { listening.complete(null); }
  @Override public void onOpen(WebSocket socket, ClientHandshake handshake) {
    offeredProtocols = handshake.getFieldValue("Sec-WebSocket-Protocol");
    resource = handshake.getResourceDescriptor(); connected.complete(socket);
  }
  @Override public void onClose(WebSocket socket, int code, String reason, boolean remote) {}
  @Override public void onError(WebSocket socket, Exception error) { listening.completeExceptionally(error); }
  @Override public void onMessage(WebSocket socket, String text) { controls.addAll(ControlMessage.decode(text)); }
  @Override public void onMessage(WebSocket socket, ByteBuffer binary) {
    var bytes = new byte[binary.remaining()]; binary.get(bytes);
    for (var frame : ValueFrame.decode(bytes)) {
      frames.add(frame);
      if (answerSync && frame.topicId() == -1) socket.send(new ValueFrame(-1, ((Number) frame.value()).longValue() + 5000, frame.typeCode(), frame.value()).encode());
    }
  }
  @Override public void onWebsocketPing(WebSocket socket, Framedata frame) {
    pings.incrementAndGet(); if (answerPing) super.onWebsocketPing(socket, frame);
  }
  public void text(String text) throws Exception { connected.get(5, TimeUnit.SECONDS).send(text); }
  public void binary(byte[] bytes) throws Exception { connected.get(5, TimeUnit.SECONDS).send(bytes); }
  public void fragmented(String text, byte[] bytes) throws Exception {
    var socket = connected.get(5, TimeUnit.SECONDS);
    byte[] json = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    for (int i = 0; i < json.length; i++) socket.sendFragmentedFrame(Opcode.TEXT, ByteBuffer.wrap(json, i, 1), i == json.length - 1);
    for (int i = 0; i < bytes.length; i++) socket.sendFragmentedFrame(Opcode.BINARY, ByteBuffer.wrap(bytes, i, 1), i == bytes.length - 1);
  }
  @Override public void close() throws InterruptedException { writes.shutdownNow(); stop(1000); }
}
