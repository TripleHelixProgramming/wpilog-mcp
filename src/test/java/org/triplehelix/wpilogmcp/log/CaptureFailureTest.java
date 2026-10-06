/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.*;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.*;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;
import org.triplehelix.wpilogmcp.store.StoreCatalog;

class CaptureFailureTest {
  @TempDir Path directory;

  @Test void diskFailureKeepsTheConnectionAndRecordsItsReasonUntilANewClock() throws Exception {
    var manager = new LogManager(); manager.addAllowedDirectory(directory);
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    var store = directory.resolve("store"); var placement = manager.stores().store(store).captures(Clock.systemUTC());
    var loop = new ManualScheduler(); var opened = new AtomicInteger(); var connected = new AtomicInteger();
    var received = new AtomicInteger(); var disconnected = new AtomicInteger();
    var writer = new CaptureWriter(Clock.systemUTC(), loop, CapturePolicy.ALL,
        new CaptureIndex(placement, manager, 0), 4096, (path, id, resume) -> new WpilogOutput(path, id, resume) {
          final boolean fail = opened.getAndIncrement() == 0;
          int records;
          @Override public Written append(int entry, long timestamp, byte[] payload) throws IOException {
            if (fail && records++ == 2) throw new IOException("planted disk full");
            return super.append(entry, timestamp, payload);
          }
        });
    var listener = new Nt4Client.Listener() {
      @Override public void connected(URI address, String protocol) { connected.incrementAndGet(); writer.connected(address, protocol); }
      @Override public void timeSync(long server, long receipt) { writer.timeSync(server, receipt); }
      @Override public void announce(Announce topic) { writer.announce(topic); }
      @Override public void value(Announce topic, ValueFrame value, long receipt) { writer.value(topic, value, receipt); received.incrementAndGet(); }
      @Override public void disconnected() { disconnected.incrementAndGet(); writer.disconnected(); }
    };
    var clock = new AtomicLong(10_000_000); var stderr = System.err; var messages = new ByteArrayOutputStream();
    try (var output = new PrintStream(messages, true, java.nio.charset.StandardCharsets.UTF_8);
         var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), clock::get)) {
      System.setErr(output);
      gateway.start().get(10, TimeUnit.SECONDS); gateway.announce("/x", "int", new JsonObject()).join();
      var client = new Nt4Client(List.of(RobotAddress.uri("127.0.0.1", gateway.port(), "failure")),
          Nt4Client.captureSubscription(0.001), listener, java.net.http.HttpClient.newHttpClient(), loop);
      try {
        client.start(); loop.until(() -> writer.session() != null);
        for (int i = 0; i < 20; i++) gateway.value("/x", 10_000_000 + i, 2, (long) i).join();
        loop.until(() -> received.get() == 20);
        assertTrue(client.isConnected()); assertEquals(1, connected.get()); assertEquals(0, disconnected.get());
        assertEquals(19L, client.latestValues().get("/x").value());
        assertFalse(writer.session().open()); assertEquals("Capture write failed: planted disk full", writer.session().endReason());
        placement.completion().get(10, TimeUnit.SECONDS);
        var failed = StoreCatalog.read(store, security); assertTrue(failed.openCaptures().isEmpty());
        assertEquals("Capture write failed: planted disk full", failed.files().get(0).session().endReason());
        var manifest = com.google.gson.JsonParser.parseString(Files.readString(failed.files().get(0).manifestPath())).getAsJsonObject();
        assertEquals("Capture write failed: planted disk full", manifest.get("end_reason").getAsString());
        var first = failed.files().get(0).path(); long bytes = Files.size(first);
        try (var use = manager.acquire(first.toString())) {
          assertEquals(List.of(0L, 1L), use.log().values().get("NT:/x").stream().map(TimestampedValue::value).toList());
        }
        gateway.value("/x", 11_000_000, 2, 20L).join(); loop.until(() -> received.get() == 21);
        assertEquals(bytes, Files.size(first)); assertTrue(client.isConnected());
        String log = messages.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(log.contains("Capture recording stopped until a new robot clock: planted disk full"), log);
        assertFalse(log.contains("NT4 connection stopped"), log);
        // A Wi-Fi reconnect on the same robot clock must not reopen the failed output.
        clock.set(11_000_000); gateway.dropClients().join(); loop.until(() -> !client.isConnected());
        loop.advance(1_000_000); loop.until(() -> connected.get() == 2 && received.get() == 22);
        assertEquals(1, opened.get()); assertFalse(writer.session().open());
        clock.set(100_000); gateway.dropClients().join(); loop.until(() -> !client.isConnected());
        gateway.value("/x", 100_000, 2, 43L).join();
        loop.advance(1_000_000); loop.until(() -> opened.get() == 2 && received.get() == 23);
        assertTrue(writer.session().open()); assertNotEquals(first, writer.session().path().toRealPath());
      } finally {
        var stopped = client.closeAsync(); loop.drain(); stopped.get(10, TimeUnit.SECONDS);
        placement.completion().get(10, TimeUnit.SECONDS);
      }
      assertEquals(2, StoreCatalog.read(store, security).files().size());
    } finally { System.setErr(stderr); manager.shutdown(); }
  }
}
