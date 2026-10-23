/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

class ReplicaProtocolBoundsTest {
  @TempDir Path root;
  final AtomicReference<Set<Path>> grants = new AtomicReference<>();
  final SecurityValidator security = new SecurityValidator();
  StoreDoor door; String master; List<ReplicaCatalog.Entry> files;
  static String id() { return UUID.randomUUID().toString(); }
  @BeforeEach void setup() throws Exception {
    root = root.toRealPath(); security.addAllowedDirectory(root); grants.set(Set.of(root)); door = new StoreDoor(grants::get);
    master = id(); String epoch = id(); files = new ArrayList<>();
    var first = ImportFixture.write(root.resolve("0.wpilog"), 31); String hash = StoreFiles.hash(first);
    for (int i = 0; i < 300; i++) {
      if (i > 0) Files.createLink(root.resolve(i + ".wpilog"), first);
      var identity = new ReplicaCatalog.Identity(master, new UUID(0, i + 1).toString(), id());
      files.add(new ReplicaCatalog.Entry(id(), identity, i + ".wpilog", hash, Files.size(first), "wpilog", null,
          "completed_import", "master_local", true, new ReplicaCatalog.Confirmation(new ReplicaCatalog.MasterRecord(identity, epoch, 1,
              hash, Files.size(first), "completed_import"), "local_master_catalog", "2026-10-19T00:00:00Z")));
    }
    new StoreFiles(root, security).write(root.resolve("store.json"), new Header(2, "2026-10-19T00:00:00Z", master,
        List.of(), Map.of(), false, List.of(), null, new ReplicaCatalog(1, epoch, 1, files, List.of(), List.of())));
  }
  @Test void reconciliationCrossesIdleAndAbsoluteExpiryWithoutLosingTheSecondPage() throws Exception {
    var manager = org.triplehelix.wpilogmcp.log.LogManager.getInstance(); var allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(root);
    var now = new java.util.concurrent.atomic.AtomicLong(); var handshakes = new java.util.concurrent.atomic.AtomicInteger();
    var downloads = new java.util.concurrent.atomic.AtomicInteger(); var pages = new java.util.concurrent.atomic.AtomicInteger();
    Clock clock = new Clock() {
      public ZoneId getZone() { return ZoneOffset.UTC; } public Clock withZone(ZoneId zone) { return this; }
      public Instant instant() { return Instant.ofEpochMilli(now.get()); } public long millis() { return now.get(); }
    };
    var http = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    try (var owner = new ReplicaProtocol(door, clock)) {
      http.createContext("/replicas/v1", exchange -> {
        try {
          String route = exchange.getRequestURI().getPath(), session = exchange.getRequestHeaders().getFirst("Replica-Session");
          var body = exchange.getRequestMethod().equals("GET") ? new com.google.gson.JsonObject()
              : org.triplehelix.wpilogmcp.read.internal.ReadWire.parse(exchange.getRequestBody().readAllBytes());
          Object result;
          if (route.endsWith("/handshake")) { handshakes.incrementAndGet(); result = owner.handshake(); }
          else if (route.endsWith("/catalog")) {
            pages.incrementAndGet(); result = owner.page(session, master, 256,
                body.has("inventory_id") ? body.get("inventory_id").getAsString() : null,
                body.has("cursor") ? body.get("cursor").getAsString() : null);
          } else if (route.endsWith("/file")) {
            var identity = new ReplicaCatalog.Identity(master, body.get("file_id").getAsString(), body.get("file_version").getAsString());
            String hash = body.get("sha256").getAsString();
            try (var payload = owner.payload(session, master, identity, hash)) {
              byte[] bytes = Files.readAllBytes(payload.path()); long offset = body.get("offset").getAsLong();
              exchange.getResponseHeaders().set("X-Content-SHA256", hash); exchange.getResponseHeaders().set("X-Replica-Offset", Long.toString(offset));
              exchange.sendResponseHeaders(200, bytes.length - offset);
              exchange.getResponseBody().write(bytes, (int) offset, bytes.length - (int) offset);
              int count = downloads.incrementAndGet(); now.addAndGet(count == 1 ? 61_000 : 301_000); return;
            }
          } else {
            if (body.has("inventory_id")) owner.closeInventory(session, body.get("inventory_id").getAsString()); else owner.closeSession(session);
            result = Map.of("status", "ok");
          }
          byte[] bytes = StoreJson.JSON.toJson(result).getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
        } catch (IOException failure) {
          String message = failure.getMessage(); String code = message.substring(0, message.indexOf(':'));
          byte[] bytes = StoreJson.JSON.toJson(Map.of("code", code, "message", message, "retryable", true)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(409, bytes.length); exchange.getResponseBody().write(bytes);
        } finally { exchange.close(); }
      }); http.start();
      String origin = "http://127.0.0.1:" + http.getAddress().getPort(); var mirrorRoot = Files.createDirectories(root.resolve("copy"));
      try (var mirror = new LogStore(mirrorRoot, manager.testGetSecurityValidator(), manager, StoreCatalog::read)) {
        var held = new ArrayList<ReplicaCatalog.Entry>();
        for (int i = 2; i < files.size() - 1; i++) {
          var file = files.get(i); Files.copy(root.resolve(file.path()), mirrorRoot.resolve(file.path()));
          var descriptor = new LogFile(file.path(), file.sha256(), file.sizeBytes(), "wpilog", new Provenance("replicated", origin, file.path(),
              "2026-10-19T00:00:00Z", false), true, 0, 1, null, null, "unknown", false, null);
          held.add(new ReplicaCatalog.Entry(id(), file.identity(), file.path(), file.sha256(), file.sizeBytes(), "wpilog", null, "completed_import", "verified", true,
              new ReplicaCatalog.Confirmation(file.confirmation().record(), origin, "2026-10-19T00:00:00Z"), descriptor));
        }
        new StoreFiles(mirrorRoot, security).write(mirrorRoot.resolve("store.json"), new Header(2, "2026-10-19T00:00:00Z", id(), List.of(), Map.of(), true,
            List.of(), new MirrorOrigin(master, origin, null, List.of(), Map.of()), new ReplicaCatalog(1, id(), 1, held, List.of(), List.of())));
        var policy = new org.triplehelix.wpilogmcp.config.MirrorConfig(origin, mirrorRoot, 14, 64L << 20, List.of(), List.of(), 30, 0, "all", 8L << 20, List.of(), false);
        // A wait, not a budget: reconciling 300 files takes about 15 s on Linux and over 120 s on Windows runners.
        var answer = mirror.mirror(policy, p -> {}).get(600, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("synchronized", answer.state(), answer.toString());
        assertEquals(3 * files.get(0).sizeBytes(), answer.bytesCopied()); assertEquals(3, downloads.get());
        assertTrue(handshakes.get() >= 3, "idle and absolute session expiry both require a new handshake");
        assertEquals(2, pages.get(), "listing is completed before file transfers; renewal cannot reopen its prefix");
        var actual = mirror.replicaHeader().replicas().files(); assertEquals(300, actual.size());
        assertEquals(new HashSet<>(files.stream().map(ReplicaCatalog.Entry::identity).toList()), new HashSet<>(actual.stream().map(ReplicaCatalog.Entry::identity).toList()));
        var coverage = ReplicaJournal.read(mirrorRoot, new StoreFiles(mirrorRoot, security)).coverage();
        assertEquals(300, coverage.offeredFiles()); assertEquals(300, coverage.heldMatchingFiles());
      }
    } finally { http.stop(0); manager.unloadAllLogs(); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  }

  @Test void cursorReplayRechecksRevokedGrantsAndCloseStillWorks() throws Exception {
    try (var owner = new ReplicaProtocol(door)) {
      String session = owner.handshake().sessionId(); var page = owner.page(session, master, 1, null, null);
      assertEquals(page, owner.page(session, master, 1, page.inventoryId(), null));
      grants.set(Set.of());
      assertThrows(IOException.class, () -> owner.page(session, master, 1, page.inventoryId(), null), "cached replies cannot retain a revoked root grant");
      owner.closeInventory(session, page.inventoryId()); owner.closeInventory(session, page.inventoryId()); assertEquals(0, owner.accountedBytes());
    }
  }
  @Test void completeTraversalIsOrderedExactAndDoesNotOpenOrHashPayloads() throws Exception {
    try (var owner = new ReplicaProtocol(door)) {
      String session = owner.handshake().sessionId(); var page = owner.page(session, master, 17, null, null);
      var seen = new ArrayList<ReplicaCatalog.Identity>();
      for (;;) {
        assertEquals(300, page.total()); assertTrue(page.offers().size() <= 17);
        seen.addAll(page.offers().stream().map(ReplicaProtocol.Offer::identity).toList());
        if (page.nextCursor() == null) break;
        page = owner.page(session, master, 17, page.inventoryId(), page.nextCursor());
      }
      assertEquals(files.stream().map(ReplicaCatalog.Entry::identity).toList(), seen);
      assertEquals(0, owner.diagnostics().hashBytes()); assertEquals(0, owner.diagnostics().admittedFiles());
      var file = files.get(0);
      for (int i = 0; i < 2; i++) try (var payload = owner.payload(session, master, file.identity(), file.sha256())) { assertEquals(file.sizeBytes(), payload.sizeBytes()); }
      assertEquals(file.sizeBytes(), owner.diagnostics().hashBytes(), "immutable reads share verified hashes across repeated admissions");
      assertEquals(2, owner.diagnostics().admittedFiles());
    }
  }
  @Test void unicodeMetadataByteShortensPagesWithoutDroppingOrDuplicatingOffers() throws Exception {
    var io = new StoreFiles(root, security); var header = io.read(root.resolve("store.json"), Header.class);
    var large = files.stream().map(file -> new ReplicaCatalog.Entry(file.key(), file.identity(), file.path(), file.sha256(), file.sizeBytes(), file.kind(),
        file.sessionId(), file.completionBasis(), file.verificationBasis(), true, file.confirmation(), null,
        new ReplicaCatalog.Placement("界".repeat(1024), null, "界".repeat(1024), null, "unknown", "finalized_file", "master_receipt"))).toList();
    io.write(root.resolve("store.json"), header.withReplicas(new ReplicaCatalog(1, header.replicas().epoch(), 2, large, List.of(), List.of())));
    try (var owner = new ReplicaProtocol(door)) {
      String session = owner.handshake().sessionId(); var page = owner.page(session, master, 256, null, null);
      assertTrue(page.offers().size() < 256); var seen = new ArrayList<ReplicaCatalog.Identity>();
      for (;;) {
        assertTrue(StoreJson.JSON.toJson(page).getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1 << 20); assertEquals(300, page.total());
        seen.addAll(page.offers().stream().map(ReplicaProtocol.Offer::identity).toList());
        if (page.nextCursor() == null) break; page = owner.page(session, master, 256, page.inventoryId(), page.nextCursor());
      }
      assertEquals(files.stream().map(ReplicaCatalog.Entry::identity).toList(), seen);
    }
  }
  @Test void handleBudgetsCursorOwnershipLimitsAndExpiryAreIndependent() throws Exception {
    var now = new java.util.concurrent.atomic.AtomicLong();
    Clock clock = new Clock() {
      public ZoneId getZone() { return ZoneOffset.UTC; } public Clock withZone(ZoneId zone) { return this; }
      public Instant instant() { return Instant.ofEpochMilli(now.get()); } public long millis() { return now.get(); }
    };
    try (var owner = new ReplicaProtocol(door, clock)) {
      String a = owner.handshake().sessionId(), b = owner.handshake().sessionId();
      var page = owner.page(a, master, 1, null, null); owner.page(a, master, 1, null, null);
      assertThrows(IOException.class, () -> owner.page(a, master, 1, null, null));
      assertThrows(IOException.class, () -> owner.page(b, master, 1, page.inventoryId(), page.nextCursor()));
      assertThrows(IOException.class, () -> owner.page(a, master, 2, page.inventoryId(), page.nextCursor()));
      assertThrows(IOException.class, () -> owner.page(a, master, 1, page.inventoryId(), id()));
      assertThrows(IllegalArgumentException.class, () -> owner.page(a, master, 257, null, null));
      for (int i = 0; i < 3; i++) { String s = owner.handshake().sessionId(); owner.page(s, master, 1, null, null); owner.page(s, master, 1, null, null); }
      assertThrows(IOException.class, () -> owner.page(b, master, 1, null, null));
      now.set(60_000); assertThrows(IOException.class, () -> owner.page(a, master, 1, page.inventoryId(), null));
      assertEquals(0, owner.accountedBytes());
      String active = owner.handshake().sessionId(); var kept = owner.page(active, master, 1, null, null);
      for (int i = 1; i <= 7; i++) { now.set(60_000 + i * 40_000); owner.page(active, master, 1, kept.inventoryId(), null); }
      now.set(360_000);
      assertThrows(IOException.class, () -> owner.page(active, master, 1, kept.inventoryId(), null), "activity does not extend the five-minute absolute lifetime");
      assertEquals(0, owner.accountedBytes());
    }
  }
  @Test void timedOutBuildersRemainChargedUntilBlockedIoUnwindsAndCannotPublish() throws Exception {
    var blocking = new java.util.concurrent.atomic.AtomicBoolean(); var entered = new java.util.concurrent.CountDownLatch(2);
    var release = new java.util.concurrent.CountDownLatch(1);
    var blockedDoor = new StoreDoor(() -> {
      if (blocking.get()) {
        entered.countDown();
        while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { /* Simulated uninterruptible OS read. */ }
      }
      return grants.get();
    });
    try (var owner = new ReplicaProtocol(blockedDoor)) {
      String a = owner.handshake().sessionId(), b = owner.handshake().sessionId(); blocking.set(true);
      var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> failure(() -> owner.page(a, master, 1, null, null)));
      var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> failure(() -> owner.page(b, master, 1, null, null)));
      assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
      assertTrue(first.get(12, java.util.concurrent.TimeUnit.SECONDS).contains("DEADLINE"));
      assertTrue(second.get(12, java.util.concurrent.TimeUnit.SECONDS).contains("DEADLINE"));
      assertEquals(64L << 20, owner.accountedBytes());
      assertTrue(failure(() -> owner.page(a, master, 1, null, null)).contains("BUSY"));
      owner.closeSession(a); owner.closeSession(b); assertEquals(64L << 20, owner.accountedBytes());
      release.countDown(); PeerDiscoveryTest.until(() -> owner.accountedBytes() == 0);
      blocking.set(false); String fresh = owner.handshake().sessionId();
      assertEquals(300, owner.page(fresh, master, 1, null, null).total());
    } finally { release.countDown(); }
  }
  @FunctionalInterface interface Checked { void run() throws Exception; }
  static String failure(Checked call) { try { call.run(); return "unexpected success"; } catch (Exception e) { return e.getMessage(); } }
}
