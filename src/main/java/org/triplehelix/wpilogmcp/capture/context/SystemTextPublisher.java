/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.capture.LiveCapture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;
import org.triplehelix.wpilogmcp.read.server.ManagedLogAccess;
import org.triplehelix.wpilogmcp.store.SystemPullStore;
import org.triplehelix.wpilogmcp.tools.SystemLogClocks;

/** The pull worker reads committed text and the search tool's clock evidence. Only bounded,
 * already-mapped records reach the capture loop; neither that loop nor the store queue reads files.
 * This is a timely copy, not history replay: durable receipts remain the complete record. */
public final class SystemTextPublisher implements java.util.function.Consumer<SystemPullStore.Text>, AutoCloseable {
  private record Line(String name, String value, long timestampUs, JsonObject metadata) {}
  private final LogManager manager;
  private final CaptureWriter writer;
  private final LiveCapture live;
  private final ClientScheduler loop;
  private final Nt4Gateway gateway;
  private volatile boolean closed;
  public SystemTextPublisher(LogManager manager, CaptureWriter writer, LiveCapture live, ClientScheduler loop, Nt4Gateway gateway) {
    this.manager = manager; this.writer = writer; this.live = live; this.loop = loop; this.gateway = gateway;
  }
  @Override public void close() { closed = true; }
  @Override public void accept(SystemPullStore.Text text) {
    var target = live.current();
    if (closed || target == null || !target.open()) return;
    // Receipt means the committed update reached this owner, before clock/file work or loop delay.
    var received = live.robotNowUs();
    try (var access = new ManagedLogAccess(manager)) {
      if (received == null) throw new IOException("System text has no synchronized receipt clock");
      var snapshot = manager.stores().systemLogs(text.capture()).orElseThrow(() -> new IOException("System text session disappeared"));
      var active = manager.stores().systemLogs(target.path()).orElseThrow(() -> new IOException("Capture session disappeared"));
      if (!snapshot.session().id().equals(text.sessionId()) || !active.session().id().equals(text.sessionId())) return;
      SystemLogClocks clocks;
      try (var log = access.acquire(text.capture().toString())) { clocks = SystemLogClocks.read(log.log(), snapshot, new JsonArray(), access); }
      long start = text.from();
      if (text.previous() != null && text.previousBytes() <= text.to() && !text.format().equals("gzip_text"))
        start = verifiedPrefix(text);
      try (var lease = org.triplehelix.wpilogmcp.log.LogFileAccess.read(text.path()); var file = Files.newInputStream(text.path())) {
        if (Files.size(text.path()) < text.to()) throw new IOException("System text is shorter than its committed receipt");
        file.skipNBytes(start);
        var prefix = new Prefix(file, text.to() - start);
        InputStream input = text.format().equals("gzip_text") ? new java.util.zip.GZIPInputStream(prefix) : prefix;
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()))) {
          var batch = new ArrayList<Line>(); int bytes = 0; var lines = new TextLines(reader);
          for (;;) {
            String line = lines.next(); if (line == null) break;
            var mapped = clocks.map(line, text.format());
            var body = new JsonObject(); body.addProperty("text", line); body.addProperty("source", text.source());
            body.addProperty("remote_path", text.remote()); body.addProperty("original_timestamp", mapped.written());
            body.addProperty("timestamp_sec", mapped.seconds()); body.addProperty("timestamp_basis", mapped.basis()); body.addProperty("timestamp_reason", mapped.reason());
            var metadata = new JsonObject(); metadata.addProperty("source", "system_log"); metadata.addProperty("format", text.format());
            metadata.addProperty("timestamp", mapped.seconds() == null ? "received" : "mapped");
            String topic = "/Daemon/System/" + text.source() + (mapped.seconds() == null ? "/unmapped" : "");
            String value = body.toString();
            batch.add(new Line(topic, value, mapped.seconds() == null ? Math.max(0, Math.round(received)) : Math.round(mapped.seconds() * 1_000_000), metadata)); bytes += value.length() * 2;
            if (batch.size() == 64 || bytes >= 256 * 1024) { deliver(List.copyOf(batch), target.startedAt()); batch.clear(); bytes = 0; }
          }
          if (!batch.isEmpty()) deliver(List.copyOf(batch), target.startedAt());
        }
      }
    } catch (Exception failure) {
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      if (!closed) {
        LoggerFactory.getLogger(SystemTextPublisher.class).warn("System text publication stopped; durable file retained: {}", failure.getMessage());
        gateway.dropClients(); // A view must see a discontinuity rather than silently keep incomplete text.
      }
    }
  }
  static final class TextLines {
    private final java.io.Reader input;
    private int count, characters;
    TextLines(java.io.Reader input) { this.input = input; }
    String next() throws IOException {
      String value = line(input); if (value == null) return null;
      characters += value.length();
      if (++count > 65536 || characters > 16 * 1024 * 1024) throw new IOException("System text publication exceeds 65,536 lines or 16 MiB of characters; read the stored file for history");
      return value;
    }
  }
  /** Stop before an unterminated line; a growing snapshot will retry that suffix on its next commit. */
  private static String line(java.io.Reader input) throws IOException {
    var value = new StringBuilder();
    for (int c; (c = input.read()) >= 0;) {
      if (c == '\n') { if (!value.isEmpty() && value.charAt(value.length() - 1) == '\r') value.setLength(value.length() - 1); return value.toString(); }
      if (value.length() == 65536) throw new IOException("System text line exceeds 64 KiB of characters; read the stored file");
      value.append((char) c);
    }
    return null;
  }
  /** Reuse only a byte-identical old prefix, and resume after its last complete UTF-8 line. */
  static long verifiedPrefix(SystemPullStore.Text text) throws IOException {
    try (var oldLease = org.triplehelix.wpilogmcp.log.LogFileAccess.read(text.previous());
        var nowLease = org.triplehelix.wpilogmcp.log.LogFileAccess.read(text.path());
        var old = Files.newInputStream(text.previous()); var now = Files.newInputStream(text.path())) {
      byte[] before = new byte[65536], after = new byte[65536]; long offset = 0, line = 0;
      while (offset < text.previousBytes()) {
        int count = (int) Math.min(before.length, text.previousBytes() - offset);
        if (old.readNBytes(before, 0, count) != count || now.readNBytes(after, 0, count) != count) return 0;
        for (int i = 0; i < count; i++) { if (before[i] != after[i]) return 0; if (before[i] == '\n') line = offset + i + 1; }
        offset += count;
      }
      return line;
    }
  }
  private void deliver(List<Line> lines, java.time.Instant start) throws Exception {
    var result = new CompletableFuture<Void>(); var cancelled = new AtomicBoolean();
    class Drain implements Runnable {
      int index;
      public void run() {
        if (closed || cancelled.get() || writer.session() == null || !writer.session().startedAt().equals(start) || !writer.ready()) {
          result.completeExceptionally(new IOException("System text recording changed before delivery")); return;
        }
        long began = System.nanoTime();
        try {
          do {
            var line = lines.get(index++); long time = line.timestampUs();
            if (writer.recordContext(line.name(), "json", line.value(), time, line.metadata()) == 0) throw new IOException("System text capture refused the record");
            live.context(line.name(), "json", line.value(), time, loop.nowUs(), line.metadata());
          } while (index < lines.size() && System.nanoTime() - began < 2_000_000);
          if (index == lines.size()) result.complete(null); else loop.execute(this);
        } catch (Throwable error) { result.completeExceptionally(error); }
      }
    }
    loop.execute(new Drain());
    try { result.get(5, TimeUnit.SECONDS); } finally { cancelled.set(true); }
  }
  private static final class Prefix extends java.io.FilterInputStream {
    long left;
    Prefix(InputStream input, long length) { super(input); left = length; }
    @Override public int read() throws IOException { if (left == 0) return -1; int v = in.read(); if (v >= 0) left--; return v; }
    @Override public int read(byte[] b, int off, int count) throws IOException {
      if (left == 0) return -1; int n = in.read(b, off, (int) Math.min(count, left)); if (n > 0) left -= n; return n;
    }
  }
}
