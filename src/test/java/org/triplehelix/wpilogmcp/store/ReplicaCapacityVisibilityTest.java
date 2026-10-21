/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.config.MirrorConfig;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

/** Capture durability already has cap tests; these guard the missing operator/peer explanation. */
class ReplicaCapacityVisibilityTest {
  @TempDir Path temp;
  final LogManager manager = LogManager.getInstance();
  Set<Path> previous;
  LogStore store;
  @BeforeEach void start() throws Exception {
    temp = temp.toRealPath(); previous = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    store = new LogStore(Files.createDirectory(temp.resolve("master")), manager.testGetSecurityValidator(), manager, StoreCatalog::read);
    store.importPaths(new LogStore.Request(List.of(ImportFixture.write(temp.resolve("input.wpilog"),7)),false,"RIO"),p -> {}).get(30,TimeUnit.SECONDS);
    store.migrateReplicas(store.replicaHeader().id()).get(30,TimeUnit.SECONDS);
  }
  @AfterEach void stop() {
    store.close(); manager.unloadAllLogs(); manager.clearAllowedDirectories(); previous.forEach(manager::addAllowedDirectory);
  }
  private static String id() { return UUID.randomUUID().toString(); }
  private Header filled(int count, int excluded) throws Exception {
    var header = store.replicaHeader(); var seed = header.replicas().files().get(0);
    var entries = new ArrayList<ReplicaCatalog.Entry>(); entries.add(seed);
    for (int i = 1; i < count; i++) {
      var identity = new ReplicaCatalog.Identity(header.id(),id(),id());
      entries.add(new ReplicaCatalog.Entry(id(),identity,"synthetic-"+i+".wpilog",seed.sha256(),seed.sizeBytes(),"wpilog",null,
          "completed_import","master_local",true,new ReplicaCatalog.Confirmation(new ReplicaCatalog.MasterRecord(identity,
          header.replicas().epoch(),2,seed.sha256(),seed.sizeBytes(),"completed_import"),"local_master_catalog","2026-10-20T00:00:00Z")));
    }
    var exclusions = new ArrayList<ReplicaCatalog.Exclusion>();
    for (int i = 0; i < excluded; i++) exclusions.add(new ReplicaCatalog.Exclusion(id(),null,seed.sha256()));
    return header.withReplicas(new ReplicaCatalog(1,header.replicas().epoch(),2,entries,List.of(),exclusions));
  }

  @Test void statusWarnsAtTheFirstIntegerAtEightyPercentOfEitherCountLimit() throws Exception {
    try (var service = new MirrorService(store,new MirrorConfig("http://127.0.0.1:1",store.root(),14,20_000_000,List.of(),List.of(),30,0))) {
      var field = MirrorService.class.getDeclaredField("metadata"); field.setAccessible(true);
      for (var counts : List.of(new int[]{3276,0},new int[]{3277,0},new int[]{1,3276},new int[]{1,3277})) {
        field.set(service,filled(counts[0],counts[1]));
        var json = StoreJson.JSON.toJsonTree(service.status()).getAsJsonObject().getAsJsonObject("replicas");
        assertTrue(json.has("catalog_capacity"), "Replica status must expose count limits and their pre-cap warning");
        var capacity = json.getAsJsonObject("catalog_capacity");
        assertEquals(counts[0],capacity.get("records").getAsInt());
        assertEquals(4096,capacity.get("records_limit").getAsInt());
        assertEquals(counts[1],capacity.get("exclusions").getAsInt());
        assertEquals(4096,capacity.get("exclusions_limit").getAsInt());
        assertEquals(counts[0]>=3277 || counts[1]>=3277,capacity.get("near_limit").getAsBoolean());
      }
    }
  }

  @Test void metricsReportThePublishedWarningWithoutJoiningTheStoreOwner() throws Exception {
    var registered = manager.stores().store(Files.createDirectory(temp.resolve("metrics")));
    var header = filled(3277,0);
    registered.captureAsync(io -> { io.writeDurable(registered.root().resolve("store.json"),header); return null; }).get(30,TimeUnit.SECONDS);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var blocking = registered.captureAsync(io -> {
      entered.countDown();
      try { if (!release.await(10,TimeUnit.SECONDS)) throw new IOException("Synthetic owner barrier expired"); }
      catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IOException(stopped); }
      return null;
    });
    var server = new org.triplehelix.wpilogmcp.mcp.HttpTransport(new org.triplehelix.wpilogmcp.mcp.ToolRegistry(),0);
    try {
      assertTrue(entered.await(5,TimeUnit.SECONDS)); server.start();
      var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+server.getPort()+"/metrics"))
          .timeout(java.time.Duration.ofSeconds(2)).GET().build();
      var response = java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(200,response.statusCode());
      String labels = "{store_id=\""+header.id()+"\"}";
      for (String expected : List.of("wpilog_replica_catalog_records"+labels+" 3277",
          "wpilog_replica_catalog_records_limit"+labels+" 4096",
          "wpilog_replica_catalog_exclusions"+labels+" 0",
          "wpilog_replica_catalog_exclusions_limit"+labels+" 4096",
          "wpilog_replica_catalog_near_limit"+labels+" 1"))
        assertTrue(response.body().lines().anyMatch(expected::equals), "Missing published capacity metric: "+expected);
      assertFalse(response.body().contains(temp.toString()));
    } finally {
      release.countDown(); blocking.get(30,TimeUnit.SECONDS); server.stop(); registered.close();
    }
  }

}
