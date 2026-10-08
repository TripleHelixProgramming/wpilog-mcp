/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.triplehelix.wpilogmcp.capture.CaptureIndex;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.harness.HarnessHttp;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.HttpTransport;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.*;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client;
import org.triplehelix.wpilogmcp.tools.CoreTools;

/** The real client, writer, live index, manager and HTTP tools, with only clocks and placement injected. */
final class ReplayCapture implements AutoCloseable {
  final Path directory, capture;
  final CaptureWriter writer;
  final ClientScheduler loop;
  final AtomicInteger announcements = new AtomicInteger();
  final AtomicInteger connections = new AtomicInteger();
  final AtomicLong received = new AtomicLong();
  final AtomicLong receivedProperties = new AtomicLong();
  final AtomicLong synchronizedServerUs = new AtomicLong(Long.MIN_VALUE);
  final List<CaptureWriter.Session> sessions = new java.util.concurrent.CopyOnWriteArrayList<>();
  final LogManager manager = LogManager.getInstance();
  final HarnessHttp http;
  final List<Path> files = new java.util.concurrent.CopyOnWriteArrayList<>();
  final org.triplehelix.wpilogmcp.store.LogStore store;
  private final org.triplehelix.wpilogmcp.store.CaptureStore placement;
  private final java.util.Set<Path> savedDirectories;
  private final List<Path> savedLogDirectories;
  private final HttpTransport transport;
  private final Nt4Client client;
  private final Object progress = new Object();
  private boolean stopped;
  private int readyConnection;

  ReplayCapture(URI address, Path source, Path directory, Clock wallClock) throws Exception {
    this(address, source, directory, wallClock, null);
  }

  ReplayCapture(URI address, Path source, Path directory, Clock wallClock,
      org.triplehelix.wpilogmcp.capture.context.DeviceIdentity device) throws Exception {
    this(address, source, directory, wallClock, device, 64L << 20);
  }

  ReplayCapture(URI address, Path source, Path directory, Clock wallClock,
      org.triplehelix.wpilogmcp.capture.context.DeviceIdentity device, long maxFileBytes) throws Exception {
    this(address, source, directory, wallClock, device, maxFileBytes, ClientScheduler.daemon("replay-capture"));
  }

  ReplayCapture(URI address, Path source, Path directory, Clock wallClock,
      org.triplehelix.wpilogmcp.capture.context.DeviceIdentity device, long maxFileBytes, ClientScheduler loop) throws Exception {
    this.loop = loop;
    this.directory = Files.createDirectories(directory).toRealPath(); capture = this.directory.resolve("capture.wpilog");
    savedDirectories = manager.getAllowedDirectories(); manager.addAllowedDirectory(source.getParent()); manager.addAllowedDirectory(this.directory);
    var listing = org.triplehelix.wpilogmcp.log.LogDirectory.getInstance(); savedLogDirectories = listing.getLogDirectories();
    listing.setLogDirectories(List.of(this.directory.toString()));
    store = device == null ? null : manager.stores().store(this.directory);
    placement = store == null ? null : store.captures(wallClock);
    if (store != null) store.identify(device, wallClock).get(10, TimeUnit.SECONDS);
    var index = new CaptureIndex(new CaptureWriter.Observer() {
      public Path create(String robot, Instant start) { return capture; }
      public Path create(String robot, Instant start, CaptureWriter.Session previous,
          org.triplehelix.wpilogmcp.capture.context.DeviceIdentity identity) throws java.io.IOException {
        return placement == null ? previous == null ? capture : previous.path() : placement.create(robot, start, previous, identity);
      }
      public void opened(CaptureWriter.Session session, boolean resumed) throws java.io.IOException {
        if (!sessions.contains(session)) sessions.add(session);
        if (!resumed) files.add(session.path()); if (placement != null) placement.opened(session, resumed);
      }
      public void entry(CaptureWriter.Session session, org.triplehelix.wpilogmcp.log.EntryInfo entry) throws java.io.IOException {
        if (placement != null) placement.entry(session, entry);
      }
      public void value(CaptureWriter.Session session, org.triplehelix.wpilogmcp.log.EntryInfo entry, ValueFrame value,
          org.triplehelix.wpilogmcp.capture.WpilogOutput.Written written) throws java.io.IOException {
        if (placement != null) placement.value(session, entry, value, written);
      }
      public void flushed(CaptureWriter.Session session) throws java.io.IOException { if (placement != null) placement.flushed(session); }
      public void timeSync(CaptureWriter.Session session, long serverUs) throws java.io.IOException { if (placement != null) placement.timeSync(session, serverUs); }
      public void fileClosed(CaptureWriter.Session session) throws java.io.IOException { if (placement != null) placement.fileClosed(session); }
      public void closed(CaptureWriter.Session session) throws java.io.IOException { if (placement != null) placement.closed(session); }
    }, manager, 0);
    if (placement != null) placement.onMove((from, to) -> {
      try { if (index.live() != null && Path.of(index.live().path()).equals(from)) manager.relocateCapture(index.live(), from, to); }
      catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    });
    writer = new CaptureWriter(wallClock, loop, CapturePolicy.ALL, index, maxFileBytes);
    if (device != null) writer.identity(device);
    var registry = new ToolRegistry(); CoreTools.registerAll(registry);
    org.triplehelix.wpilogmcp.tools.RevLogTools.registerAll(registry);
    transport = new HttpTransport(registry, 0); transport.start(); http = new HarnessHttp(transport.getPort()); http.initialize();
    client = new Nt4Client(List.of(address), Nt4Client.captureSubscription(0.001), new Nt4Client.Listener() {
      public void connected(URI uri, String protocol) {
        announcements.set(0); connections.incrementAndGet(); writer.connected(uri, protocol);
      }
      public void timeSync(long server, long receipt) { writer.timeSync(server, receipt); synchronizedServerUs.set(server); }
      public void announce(Announce topic) { writer.announce(topic); announcements.incrementAndGet(); }
      public void unannounce(Unannounce topic) { writer.unannounce(topic); }
      public void properties(Properties properties) {
        writer.properties(properties);
        if (properties.update().has(LogReplayer.METADATA)) synchronized (progress) {
          receivedProperties.incrementAndGet(); progress.notifyAll();
        }
      }
      public void value(Announce topic, ValueFrame frame, long receipt) {
        if (wallClock instanceof ReplayClock replay) replay.advance(frame.timestampUs());
        writer.value(topic, frame, receipt);
        synchronized (progress) { received.incrementAndGet(); progress.notifyAll(); }
      }
      public void invalidValue(Announce topic, int code) { writer.invalidValue(topic, code); }
      public void disconnected() {
        announcements.set(0);
        synchronized (progress) { progress.notifyAll(); }
        writer.disconnected();
      }
    }, HttpClient.newHttpClient(), loop);
    client.start();
  }

  void ready(int count) throws Exception {
    // Readiness belongs to the current subscription. A transient connection before replay
    // can otherwise overshoot a lifetime counter and make equality impossible forever.
    try {
      if (loop instanceof org.triplehelix.wpilogmcp.nt4.client.ManualScheduler manual) {
        manual.until(() -> client.isConnected() && announcements.get() == count);
      } else HarnessHttp.await("replay subscription", 30, () -> client.isConnected() && announcements.get() == count);
      readyConnection = connections.get();
    } catch (AssertionError failure) {
      throw new AssertionError("Replay subscription: connected=" + client.isConnected() + ", connections=" + connections.get()
          + ", announcements=" + announcements.get() + ", expected=" + count + ", topics=" + client.topics().size(), failure);
    }
  }

  boolean connected() { return client.isConnected(); }

  void receivedThrough(long count) {
    through(received, count);
  }
  void propertiesThrough(long count) { through(receivedProperties, count); }

  private void through(AtomicLong counter, long count) {
    if (loop instanceof org.triplehelix.wpilogmcp.nt4.client.ManualScheduler manual) {
      try { manual.until(() -> { requireConnection(); return counter.get() >= count; }); return; }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    synchronized (progress) {
      requireConnection();
      while (counter.get() < count) {
        requireConnection();
        long left = deadline - System.nanoTime();
        if (left <= 0) throw new AssertionError("Replay capture stalled");
        try { TimeUnit.NANOSECONDS.timedWait(progress, left); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
      }
    }
  }

  private void requireConnection() {
    if (!client.isConnected() || connections.get() != readyConnection) {
      throw new AssertionError("Replay capture disconnected: " + client.disconnectReason());
    }
  }

  void stop() throws Exception {
    if (stopped) return; stopped = true;
    var closed = client.closeAsync();
    if (loop instanceof org.triplehelix.wpilogmcp.nt4.client.ManualScheduler manual) manual.until(closed::isDone);
    closed.get(30, TimeUnit.SECONDS);
    if (placement != null) {
      placement.completion().get(30, TimeUnit.SECONDS);
      files.clear(); sessions.forEach(s -> s.files().forEach(f -> files.add(s.path().resolveSibling(f.name()))));
    }
  }

  JsonObject info(Path path, String name) throws Exception {
    var args = new JsonObject(); args.addProperty("path", path.toString()); args.addProperty("name", name);
    return http.call("get_entry_info", args);
  }

  @Override public void close() throws Exception {
    try { stop(); }
    finally {
      transport.stop(); manager.release(directory); manager.clearAllowedDirectories(); savedDirectories.forEach(manager::addAllowedDirectory);
      org.triplehelix.wpilogmcp.log.LogDirectory.getInstance().setLogDirectories(savedLogDirectories.stream().map(Path::toString).toList());
    }
  }
}
