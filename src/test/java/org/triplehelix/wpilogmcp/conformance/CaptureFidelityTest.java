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
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;
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

  static void capture(Path fixture, Path capture) throws Exception {
    capture(fixture, capture, null, () -> {});
  }

  @FunctionalInterface interface OpenCheck { void check() throws Exception; }
  static void capture(Path fixture, Path capture, CaptureWriter.Observer observer, OpenCheck whileOpen) throws Exception {
    var names = IndependentLog.read(fixture, Set.of()).series.keySet();
    var loop = ClientScheduler.daemon(); var ready = new CompletableFuture<Void>(); var done = new CompletableFuture<Void>();
    var writer = new CaptureWriter(Clock.systemUTC(), loop, new CapturePolicy(List.of("/capture-test/"), Map.of()),
        observer != null ? observer : new CaptureWriter.Observer() {
          @Override public Path create(String address, Instant start) { return capture; }
        });
    var listener = new Nt4Client.Listener() {
      @Override public void connected(URI address, String protocol) { writer.connected(address, protocol); }
      @Override public void timeSync(long server, long received) { writer.timeSync(server, received); }
      @Override public void announce(Announce a) { writer.announce(a); if (a.name().equals("/capture-test/ready")) ready.complete(null); }
      @Override public void value(Announce a, ValueFrame v, long received) { writer.value(a, v, received); }
      @Override public void unannounce(Unannounce a) {
        writer.unannounce(a);
        if (a.name().equals("/capture-test/ready")) done.complete(null);
      }
      @Override public void disconnected() { writer.disconnected(); }
    };
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 10_000_000)) {
      gateway.start().get(5, TimeUnit.SECONDS);
      var replayer = new FixtureReplayer(fixture); replayer.announce(gateway);
      gateway.announce("/capture-test/ready", "int", new JsonObject()).join();
      try (var client = new Nt4Client(List.of(RobotAddress.uri("127.0.0.1", gateway.port(), "capture")),
          Nt4Client.captureSubscription(0.001), listener, java.net.http.HttpClient.newHttpClient(), loop)) {
        client.start(); ready.get(10, TimeUnit.SECONDS); replayer.replay(gateway, 0, ignored -> fail("Fast replay must not sleep"));
        for (var name : names) gateway.unannounce(name).join();
        gateway.unannounce("/capture-test/ready").join(); done.get(30, TimeUnit.SECONDS);
        try { whileOpen.check(); }
        finally { client.closeAsync().get(30, TimeUnit.SECONDS); }
      }
    }
    var expected = IndependentLog.read(fixture, Set.of(), names);
    var capturedNames = names.stream().map(n -> "NT:" + n).collect(Collectors.toSet());
    var actual = IndependentLog.read(capture, Set.of(), capturedNames);
    assertEquals(names.stream().map(n -> "NT:" + n).toList(), List.copyOf(actual.series.keySet()));
    for (var entry : expected.series.values()) {
      var got = actual.series.get("NT:" + entry.name);
      var order = java.util.stream.IntStream.range(0, entry.payloadTimes.size()).boxed()
          .sorted(java.util.Comparator.comparingDouble(entry.payloadTimes::get)).toList();
      assertEquals(entry.type, got.type); assertEquals(order.stream().map(entry.payloadTimes::get).toList(), got.payloadTimes, entry.name);
      assertEquals(entry.payloads.size(), got.payloads.size(), entry.name);
      for (int i = 0; i < entry.payloads.size(); i++) assertArrayEquals(entry.payloads.get(order.get(i)), got.payloads.get(i), entry.name + " sample " + i);
    }
    // A second, independent check uses wpiutil's pure-Java DataLogReader over heap bytes.
    var reader = new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(capture))); assertTrue(reader.isValid());
    var ids = new HashMap<Integer, String>(); var counts = new HashMap<String, Integer>();
    for (var record : reader) {
      if (record.isStart()) ids.put(record.getStartData().entry, record.getStartData().name);
      else if (!record.isControl()) counts.merge(ids.get(record.getEntry()), 1, Integer::sum);
    }
    for (var entry : actual.series.values()) assertEquals(entry.records, counts.getOrDefault(entry.name, 0), entry.name);
  }
}
