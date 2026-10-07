/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import edu.wpi.first.util.datalog.DataLogReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Unannounce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.nt4.server.FixtureReplayer;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;

class CaptureFidelityTest {
  @TempDir Path directory;
  @TestFactory Stream<DynamicTest> everyFixtureCapturedThroughClient() throws Exception {
    return FixtureLogs.generateAll(FixtureLogs.defaultDirectory()).stream().map(f ->
        DynamicTest.dynamicTest(f.id(), () -> capture(f.path(), directory.resolve(f.id() + ".wpilog"))));
  }

  @TestFactory Stream<DynamicTest> everyFixtureCapturedAcrossBoundedFiles() throws Exception {
    return FixtureLogs.generateAll(FixtureLogs.defaultDirectory()).stream().map(f ->
        DynamicTest.dynamicTest(f.id(), () -> {
          var raw = IndependentLog.read(f.path(), Set.of());
          var samples = IndependentLog.read(f.path(), Set.of(), raw.series.keySet());
          long declarations = raw.series.values().stream().mapToLong(e -> 256L
              + e.name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
              + e.type.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum();
          long largest = samples.series.values().stream().flatMap(e -> e.payloads.stream()).mapToInt(a -> a.length).max().orElse(0);
          long schemas = samples.series.values().stream().filter(e -> e.type.equals("structschema"))
              .flatMap(e -> e.payloads.stream()).mapToLong(a -> a.length + 32L).sum();
          long bound = 2048 + declarations + largest + schemas;
          var dir = Files.createDirectory(directory.resolve(f.id()));
          var files = captureFiles(f.path(), dir.resolve("capture.wpilog"), null, () -> {}, bound);
          for (var file : files) assertTrue(Files.size(file) <= bound, file.toString());
          long dataBytes = samples.series.values().stream().flatMap(e -> e.payloads.stream()).mapToLong(a -> a.length).sum();
          if (dataBytes > bound) assertTrue(files.size() > 1, "Fixture must exercise rollover: " + f.id());
        }));
  }

  static void capture(Path fixture, Path capture) throws Exception {
    capture(fixture, capture, null, () -> {});
  }

  @FunctionalInterface interface OpenCheck { void check() throws Exception; }
  static void capture(Path fixture, Path capture, CaptureWriter.Observer observer, OpenCheck whileOpen) throws Exception {
    captureFiles(fixture, capture, observer, whileOpen, CaptureWriter.DEFAULT_MAX_FILE_BYTES);
  }

  private static List<Path> captureFiles(Path fixture, Path capture, CaptureWriter.Observer observer,
      OpenCheck whileOpen, long maxFileBytes) throws Exception {
    var names = IndependentLog.read(fixture, Set.of()).series.keySet();
    var files = new java.util.ArrayList<Path>();
    var delegate = observer != null ? observer : new CaptureWriter.Observer() {
      @Override public Path create(String address, Instant start) { return capture; }
    };
    var tracking = new CaptureWriter.Observer() {
      @Override public Path create(String address, Instant start) throws java.io.IOException { return delegate.create(address, start); }
      @Override public Path create(String address, Instant start, CaptureWriter.Session previous) throws java.io.IOException { return delegate.create(address, start, previous); }
      @Override public void opened(CaptureWriter.Session session, boolean resumed) throws java.io.IOException { files.add(session.path()); delegate.opened(session, resumed); }
      @Override public void entry(CaptureWriter.Session session, org.triplehelix.wpilogmcp.log.EntryInfo entry) throws java.io.IOException { delegate.entry(session, entry); }
      @Override public void metadata(CaptureWriter.Session session, org.triplehelix.wpilogmcp.log.EntryInfo entry) throws java.io.IOException { delegate.metadata(session, entry); }
      @Override public void value(CaptureWriter.Session session, org.triplehelix.wpilogmcp.log.EntryInfo entry, ValueFrame value,
          org.triplehelix.wpilogmcp.capture.WpilogOutput.Written written) throws java.io.IOException { delegate.value(session, entry, value, written); }
      @Override public void flushed(CaptureWriter.Session session) throws java.io.IOException { delegate.flushed(session); }
      @Override public void timeSync(CaptureWriter.Session session, long time) throws java.io.IOException { delegate.timeSync(session, time); }
      @Override public void fileClosed(CaptureWriter.Session session) throws java.io.IOException { delegate.fileClosed(session); }
      @Override public void closed(CaptureWriter.Session session) throws java.io.IOException { delegate.closed(session); }
    };
    var loop = new org.triplehelix.wpilogmcp.nt4.client.ManualScheduler(); var ready = new CompletableFuture<Void>(); var done = new CompletableFuture<Void>();
    var writer = new CaptureWriter(Clock.systemUTC(), loop, new CapturePolicy(List.of("/capture-test/"), Map.of()),
        tracking, maxFileBytes);
    var listener = new Nt4Client.Listener() {
      @Override public void connected(URI address, String protocol) { writer.connected(address, protocol); }
      @Override public void timeSync(long server, long received) { writer.timeSync(server, received); }
      @Override public void announce(Announce a) { writer.announce(a); if (a.name().equals("/capture-test/ready")) ready.complete(null); }
      @Override public void value(Announce a, ValueFrame v, long received) { writer.value(a, v, received); }
      @Override public void properties(org.triplehelix.wpilogmcp.nt4.ControlMessage.Properties change) { writer.properties(change); }
      @Override public void unannounce(Unannounce a) {
        writer.unannounce(a);
        if (a.name().equals("/capture-test/ready")) done.complete(null);
      }
      @Override public void disconnected() { writer.disconnected(); if (!done.isDone()) done.completeExceptionally(new AssertionError("Disconnected before replay finished")); }
    };
    // 4.0 plus the injected client clock keeps this an exact-byte replay, independent of the
    // time spent forcing tiny files or running tools. Separate NT4 tests pin 4.1 keepalives.
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 10_000_000, List.of(Nt4Client.V40))) {
      gateway.start().get(5, TimeUnit.SECONDS);
      var replayer = new FixtureReplayer(fixture); replayer.announce(gateway);
      gateway.announce("/capture-test/ready", "int", new JsonObject()).join();
      try (var client = new Nt4Client(List.of(RobotAddress.uri("127.0.0.1", gateway.port(), "capture")),
          Nt4Client.captureSubscription(0.001), listener, java.net.http.HttpClient.newHttpClient(), loop)) {
        try {
          client.start(); loop.until(ready::isDone); ready.get(10, TimeUnit.SECONDS); replayer.replay(gateway, 0, ignored -> fail("Fast replay must not sleep"));
          if (observer != null) {
            var patch = new JsonObject(); patch.addProperty("live_metadata_check", "changed");
            for (var name : names) gateway.properties(name, patch).join();
          }
          for (var name : names) gateway.unannounce(name).join();
          gateway.unannounce("/capture-test/ready").join();
          // Windows may spend more than ten seconds forcing all the tiny rollover files.
          // This is a fidelity check, not a filesystem-throughput requirement.
          loop.until(done::isDone, java.time.Duration.ofSeconds(60)); done.get(30, TimeUnit.SECONDS);
          whileOpen.check();
        }
        finally { var stopped = client.closeAsync(); loop.drain(); stopped.get(30, TimeUnit.SECONDS); }
      }
    }
    var expected = IndependentLog.read(fixture, Set.of(), names);
    var capturedNames = names.stream().map(n -> "NT:" + n).collect(Collectors.toSet());
    var times = new java.util.LinkedHashMap<String, java.util.List<Double>>();
    var payloads = new java.util.LinkedHashMap<String, java.util.List<byte[]>>();
    for (var file : files) {
      var actual = IndependentLog.read(file, Set.of(), capturedNames);
      var reader = new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(file))); assertTrue(reader.isValid());
      var ids = new HashMap<Integer, String>(); var counts = new HashMap<String, Integer>(); var seeds = new java.util.HashSet<String>();
      for (var record : reader) {
        if (record.isStart()) {
          var start = record.getStartData(); ids.put(start.entry, start.name);
          if (com.google.gson.JsonParser.parseString(start.metadata).getAsJsonObject().has("capture_schema_seed")) seeds.add(start.name);
        } else if (!record.isControl()) counts.merge(ids.get(record.getEntry()), 1, Integer::sum);
      }
      for (var got : actual.series.values()) {
        assertEquals(got.records, counts.getOrDefault(got.name, 0), got.name);
        assertEquals(expected.series.get(got.name.substring(3)).type, got.type);
        var seenTimes = times.computeIfAbsent(got.name, ignored -> new java.util.ArrayList<>());
        var seenPayloads = payloads.computeIfAbsent(got.name, ignored -> new java.util.ArrayList<>());
        int skip = 0;
        if (seeds.contains(got.name)) {
          assertEquals("structschema", got.type); assertFalse(seenTimes.isEmpty());
          assertEquals(10.0, got.payloadTimes.get(0), "Seed uses the planted server clock at rollover, not its original timestamp");
          assertArrayEquals(seenPayloads.get(seenPayloads.size() - 1), got.payloads.get(0), "Seed equals the last received schema");
          skip = 1;
        }
        seenTimes.addAll(got.payloadTimes.subList(skip, got.payloadTimes.size()));
        seenPayloads.addAll(got.payloads.subList(skip, got.payloads.size()));
      }
    }
    assertEquals(names.stream().map(n -> "NT:" + n).toList(), List.copyOf(times.keySet()));
    for (var entry : expected.series.values()) {
      var order = java.util.stream.IntStream.range(0, entry.payloadTimes.size()).boxed()
          .sorted(java.util.Comparator.comparingDouble(entry.payloadTimes::get)).toList();
      assertEquals(order.stream().map(entry.payloadTimes::get).toList(), times.get("NT:" + entry.name), entry.name);
      var got = payloads.get("NT:" + entry.name); assertEquals(entry.payloads.size(), got.size(), entry.name);
      for (int i = 0; i < got.size(); i++) assertArrayEquals(entry.payloads.get(order.get(i)), got.get(i), entry.name + " sample " + i);
    }
    return files;
  }
}
