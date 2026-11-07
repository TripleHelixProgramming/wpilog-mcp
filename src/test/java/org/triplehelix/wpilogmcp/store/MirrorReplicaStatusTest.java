/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.config.MirrorConfig;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

class MirrorReplicaStatusTest {
  @TempDir Path temp;
  String id() { return UUID.randomUUID().toString(); }
  @Test void observationsShareThePageByteBudgetAndTraversalRemainsExact() throws Exception {
    String master = id(), now = "2026-10-19T00:00:00Z", hash = "a".repeat(64);
    var entries = new ArrayList<ReplicaCatalog.Entry>(); var observations = new ArrayList<ReplicaJournal.Observation>();
    for (int i = 0; i < 128; i++) {
      var identity = new ReplicaCatalog.Identity(master, id(), id());
      entries.add(new ReplicaCatalog.Entry(id(), identity, "object-" + i + ".wpilog", hash, 1, "wpilog", null, "completed_import", "provisional", true, null));
      for (int n = 0; n < 8; n++) observations.add(new ReplicaJournal.Observation(identity, hash, "界".repeat(1023) + n, "published", now));
    }
    var header = new Header(2, now, id(), List.of(), Map.of(), true, List.of(), new MirrorOrigin(master, "http://127.0.0.1:1", null, List.of(), Map.of()),
        new ReplicaCatalog(1, id(), 1, entries, List.of(), List.of()));
    var manager = LogManager.getInstance();
    try (var store = new LogStore(temp, manager.testGetSecurityValidator(), manager, StoreCatalog::read);
         var service = new MirrorService(store, new MirrorConfig("http://127.0.0.1:1", temp, 14, 20_000_000, List.of(), List.of(), 30, 0))) {
      // Exercise serialization from an immutable published snapshot without scheduling network I/O.
      var metadata = MirrorService.class.getDeclaredField("metadata"); metadata.setAccessible(true); metadata.set(service, header);
      var evidence = MirrorService.class.getDeclaredField("evidence"); evidence.setAccessible(true);
      evidence.set(service, new ReplicaJournal.State(1, null, List.of(), List.of(), List.of(), observations, null));
      int offset = 0; var seen = new HashSet<String>(); int notes = 0;
      do {
        var page = StoreJson.JSON.toJsonTree(service.files(offset, 256)).getAsJsonObject();
        assertTrue(page.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1 << 20, "observation history must be charged before selecting a page");
        var files = page.getAsJsonArray("files"); assertFalse(files.isEmpty());
        for (var file : files) assertTrue(seen.add(file.getAsJsonObject().get("key").getAsString()), "no duplicate rows");
        notes += page.getAsJsonArray("observations").size(); offset += files.size();
        if (!page.get("has_more").getAsBoolean()) break;
      } while (offset < 128);
      assertEquals(128, seen.size()); assertEquals(1024, notes); assertEquals(128, offset);
    }
  }
}
