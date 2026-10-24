/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.CaptureService;
import org.triplehelix.wpilogmcp.config.CaptureConfig;
import org.triplehelix.wpilogmcp.config.MirrorConfig;
import org.triplehelix.wpilogmcp.config.PullConfig;
import org.triplehelix.wpilogmcp.harness.HarnessHttp;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.HttpTransport;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;
import org.triplehelix.wpilogmcp.read.ReadQuery;
import org.triplehelix.wpilogmcp.read.ReadSnapshot;
import org.triplehelix.wpilogmcp.read.catalog.SessionSnapshot;
import org.triplehelix.wpilogmcp.read.catalog.StoreReadCatalog;
import org.triplehelix.wpilogmcp.read.http.RemoteLogAccess;

/** Real recorder and transfer paths must preserve the evidence used to join rollover files.
 * The robot is a loopback NT4 fixture; this does not assert anything about field networking. */
class SessionTransferReadTest {
  @TempDir Path temp;
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
  private static final String ENTRY = "NT:/counter";

  @Test void recordingPrefixesAndTransferredRolloverSessionsKeepEverySample() throws Exception {
    temp = temp.toRealPath();
    var manager = LogManager.getInstance(); manager.resetConfiguration(); manager.addAllowedDirectory(temp);
    var origin = manager.stores().store(temp.resolve("origin"));
    var http = new HttpTransport(new ToolRegistry(), 0);
    http.setStoreDirectories(Set.of(origin.root()));
    try (var robot = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 10_000_000)) {
      robot.start().get(30, TimeUnit.SECONDS);
      robot.announce("/counter", "int", new JsonObject()).get(30, TimeUnit.SECONDS);
      var config = new CaptureConfig(List.of(RobotAddress.uri("127.0.0.1", robot.port(), "session-test")),
          origin.root(), .001, CapturePolicy.ALL, 600_000_000, 1024, PullConfig.DISABLED);
      http.start();
      try (var capture = new CaptureService(config, manager, CLOCK, ClientScheduler.daemon());
           var remote = new RemoteLogAccess(URI.create(url(http)))) {
        capture.start().get(30, TimeUnit.SECONDS);
        HarnessHttp.await("capture subscription", 30, () -> capture.live().topics().containsKey("/counter")
            && capture.live().timeEstimate().isPresent());
        publish(robot, 0, 100);
        received(capture, 100);
        HarnessHttp.await("published rollover manifest", 30, () -> {
          var view = inventory(origin, manager);
          return view.sessions().size() == 1 && view.files().size() > 0
              && view.sessions().get(0).session().openCapture() != null;
        });
        var view = inventory(origin, manager);
        String storeId = view.header().id(), sessionId = view.sessions().get(0).session().id();
        try (var first = openRetrying(remote, storeId, sessionId, "capture.wpilog", SessionSnapshot.Overlap.KEEP_ALL)) {
          assertTrue(first.live(), "An HTTP session must use the recorder's managed prefix");
          assertTrue(first.sources().size() > 1, "The fixture must roll over, not just test one file");
          assertSeries(first, 100);
          publish(robot, 100, 200);
          received(capture, 200);
          assertSeries(first, 100); // Rollover retires the writer's view, not a reader's held prefix.
          try (var second = openRetrying(remote, storeId, sessionId, "capture.wpilog", SessionSnapshot.Overlap.KEEP_ALL)) {
            assertSeries(second, 200);
          }
        }
      }
      var original = inventory(origin, manager);
      assertEquals(1, original.sessions().size());
      assertNull(original.sessions().get(0).session().openCapture());
      int files = original.files().size(); assertTrue(files > 1);
      assertClosed(origin, http, manager, files, original.header().id(), false);

      var peer = manager.stores().store(temp.resolve("peer"));
      var sync = peer.sync(url(http), 0, p -> {}, CLOCK, StoreSync::http).get(30, TimeUnit.SECONDS);
      assertEquals(List.of(), sync.refusals(), sync.toString());
      assertEquals(List.of(), sync.stopped(), sync.toString());
      assertEquals(files, sync.filesCopied().size());
      var peerHttp = new HttpTransport(new ToolRegistry(), 0); peerHttp.setStoreDirectories(Set.of(peer.root()));
      try {
        peerHttp.start();
        assertClosed(peer, peerHttp, manager, files, original.header().id(), true);
        // A mirror leaves every capture with its origin, a peer's copies included: a laptop records
        // live data itself. These are the capture store's own records, not a fixture's.
        var mirror = manager.stores().store(temp.resolve("mirror"));
        var mirrored = org.triplehelix.wpilogmcp.store.LegacyMirrorFixture.sync(mirror, new MirrorConfig(url(peerHttp), mirror.root(), 14, 1 << 20,
            List.of(), List.of(), 30, 0), p -> {}, CLOCK, StoreSync::http).get(30, TimeUnit.SECONDS);
        assertEquals("synchronized", mirrored.state(), mirrored.toString());
        assertEquals(0, mirrored.bytesCopied(), mirrored.toString());
        var held = inventory(mirror, manager);
        assertEquals(List.of(), held.files()); assertEquals(List.of(), held.sessions());
      } finally { peerHttp.stop(); }
    } finally { http.stop(); manager.resetConfiguration(); }
  }

  private static void publish(Nt4Gateway robot, int start, int end) throws Exception {
    for (int i = start; i < end; i++) robot.value("/counter", 10_000_000L + i * 1000, 2, 7L * i).get(30, TimeUnit.SECONDS);
  }
  private static void received(CaptureService capture, int count) throws Exception {
    HarnessHttp.await("recorded value " + count, 30, () -> {
      assertTrue(capture.live().connected(), () -> "Recorder disconnected: " + capture.live().disconnectReason());
      var value = capture.live().latest().get("/counter");
      return value != null && value.value().equals(7L * (count - 1));
    });
  }
  /**
   * Opens a session read as clients do: a refusal the server marks retryable is retried, here for
   * 10 s. While the recorder rolls files over, a catalog read can see the store change on each of
   * its attempts, which the slower Windows runner hit often enough to fail this test.
   */
  private static RemoteLogAccess.RemoteSnapshot openRetrying(RemoteLogAccess remote, String storeId, String sessionId, String anchor,
      SessionSnapshot.Overlap overlap) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (true) {
      try { return remote.openSession(storeId, sessionId, anchor, overlap); }
      catch (org.triplehelix.wpilogmcp.read.ReadException refused) {
        if (!refused.retryable() || System.nanoTime() > deadline) throw refused;
        Thread.sleep(250);
      }
    }
  }

  private static String url(HttpTransport http) { return "http://127.0.0.1:" + http.getPort(); }
  private static StoreCatalog.Snapshot inventory(LogStore store, LogManager manager) throws Exception {
    return StoreCatalog.read(store.root(), manager.testGetSecurityValidator());
  }
  private static void assertClosed(LogStore store, HttpTransport http, LogManager manager,
      int files, String collector, boolean copied) throws Exception {
    var facts = inventory(store, manager);
    assertEquals(files, facts.files().size());
    String session = facts.sessions().get(0).session().id();
    String anchor = facts.files().stream().min(java.util.Comparator.comparingDouble(f -> f.file().minTimestampSec()))
        .orElseThrow().file().path();
    for (var file : facts.files()) {
      var provenance = file.file().provenance();
      assertTrue(provenance.captured()); assertEquals(CLOCK.instant().toString(), provenance.importedAt());
      if (copied) assertEquals(collector, provenance.copiedFrom().get(0).storeId());
    }
    try (var catalog = new StoreReadCatalog(List.of(store.root()));
         var local = catalog.openSession(facts.header().id(), session, anchor, SessionSnapshot.Overlap.KEEP_ALL)) {
      assertEquals(files, local.sources().size()); assertFalse(local.live()); assertTrue(local.excludedSources().isEmpty());
      assertSeries(local, 200);
    }
    try (var remote = new RemoteLogAccess(URI.create(url(http)));
         var read = openRetrying(remote, facts.header().id(), session, anchor, SessionSnapshot.Overlap.KEEP_ALL)) {
      assertEquals(files, read.sources().size());
      // "live" describes a writer-owned prefix, which the origin may keep cached after close.
      // A receiving store has disk files only, even when its provenance says captured.
      if (copied) assertFalse(read.live());
      assertSeries(read, 200);
    }
  }
  private static void assertSeries(ReadSnapshot snapshot, int count) throws Exception {
    int seen = 0;
    try (var cursor = snapshot.read(ReadQuery.all(ENTRY))) {
      while (true) {
        var batch = cursor.next(); assertTrue(batch.problems().isEmpty(), batch.problems().toString());
        for (var sample : batch.samples()) {
          assertEquals(10_000_000L + seen * 1000, sample.timestampUs());
          assertEquals(7L * seen, sample.value());
          assertNotNull(sample.source()); assertEquals(sample.timestampUs(), sample.source().timestampUs());
          seen++;
        }
        if (batch.complete()) break;
      }
    }
    assertEquals(count, seen, "No missing, repeated or invented rollover samples");
  }
}
