/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.client;

import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.triplehelix.wpilogmcp.nt4.ControlMessage;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;

/** Delivers even a queued callback from an aborted socket, an interleaving TCP timing cannot pin. */
public final class ControlledSockets extends HttpClient {
  public final java.util.ArrayList<Peer> peers = new java.util.ArrayList<>();
  public static final class Peer implements WebSocket {
    private final Listener listener;
    private final java.util.ArrayList<ValueFrame> sent = new java.util.ArrayList<>();
    Peer(Listener listener) { this.listener = listener; }
    public void sync(long timeUs) { binary(new ValueFrame(-1, timeUs, 2, sent.get(sent.size() - 1).value())); }
    public void text(ControlMessage message) { listener.onText(this, ControlMessage.encode(List.of(message)), true); }
    public void binary(ValueFrame frame) { listener.onBinary(this, ByteBuffer.wrap(frame.encode()), true); }
    public void fail() { listener.onError(this, new java.io.IOException("scripted disconnect")); }
    private CompletableFuture<WebSocket> done() { return CompletableFuture.completedFuture(this); }
    public CompletableFuture<WebSocket> sendText(CharSequence text, boolean last) { return done(); }
    public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
      byte[] bytes = new byte[data.remaining()]; data.get(bytes); sent.addAll(ValueFrame.decode(bytes)); return done();
    }
    public CompletableFuture<WebSocket> sendPing(ByteBuffer bytes) { return done(); }
    public CompletableFuture<WebSocket> sendPong(ByteBuffer bytes) { return done(); }
    public CompletableFuture<WebSocket> sendClose(int status, String reason) { return done(); }
    public void request(long n) {}
    public String getSubprotocol() { return Nt4Client.V40; }
    public boolean isOutputClosed() { return false; }
    public boolean isInputClosed() { return false; }
    public void abort() {}
  }
  @Override public WebSocket.Builder newWebSocketBuilder() {
    return new WebSocket.Builder() {
      public WebSocket.Builder header(String name, String value) { return this; }
      public WebSocket.Builder connectTimeout(Duration duration) { return this; }
      public WebSocket.Builder subprotocols(String first, String... others) { return this; }
      public CompletableFuture<WebSocket> buildAsync(URI uri, WebSocket.Listener listener) {
        var peer = new Peer(listener); peers.add(peer); listener.onOpen(peer); return CompletableFuture.completedFuture(peer);
      }
    };
  }
  public Optional<java.net.CookieHandler> cookieHandler() { return Optional.empty(); }
  public Optional<Duration> connectTimeout() { return Optional.empty(); }
  public Redirect followRedirects() { return Redirect.NEVER; }
  public Optional<java.net.ProxySelector> proxy() { return Optional.empty(); }
  public javax.net.ssl.SSLContext sslContext() { throw new UnsupportedOperationException(); }
  public javax.net.ssl.SSLParameters sslParameters() { throw new UnsupportedOperationException(); }
  public Optional<java.net.Authenticator> authenticator() { return Optional.empty(); }
  public Version version() { return Version.HTTP_1_1; }
  public Optional<java.util.concurrent.Executor> executor() { return Optional.empty(); }
  public <T> HttpResponse<T> send(HttpRequest r, HttpResponse.BodyHandler<T> h) { throw new UnsupportedOperationException(); }
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) { throw new UnsupportedOperationException(); }
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) { throw new UnsupportedOperationException(); }
}
