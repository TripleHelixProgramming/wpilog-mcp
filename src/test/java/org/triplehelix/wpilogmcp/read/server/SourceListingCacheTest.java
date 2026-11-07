/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.server;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.store.StoreJson;
import org.triplehelix.wpilogmcp.store.StoreManifest.Header;

/** Counts real listing opens; a request counter cannot demonstrate avoided filesystem walks. */
class SourceListingCacheTest {
  @TempDir Path temp;
  final AtomicLong now = new AtomicLong(System.currentTimeMillis());
  final AtomicLong reads = new AtomicLong();
  final Clock clock = new Clock() {
    public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId zone) { return this; }
    public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    public long millis() { return now.get(); }
  };
  private SourceScopes scopes() { return new SourceScopes(() -> Set.of(temp), ignored -> reads.incrementAndGet(), clock); }
  private FileTime old() { return FileTime.fromMillis(now.get() - 120_000); }
  private void ageTree() throws Exception {
    try (var paths = Files.walk(temp)) {
      for (var p : paths.filter(Files::isDirectory).sorted(java.util.Comparator.reverseOrder()).toList()) Files.setLastModifiedTime(p, old());
    }
  }
  @Test void unchangedAgedDirectoriesOpenNoListingsAfterTheFirstRequest() throws Exception {
    temp = temp.toRealPath();
    for (int i = 0; i < 20; i++) {
      var directory = Files.createDirectory(temp.resolve("directory-" + i));
      for (int f = 0; f < 10; f++) Files.writeString(directory.resolve("unrelated-" + f + ".txt"), "synthetic");
    }
    ageTree(); var owner = scopes(); owner.snapshot();
    assertEquals(21, reads.get(), "The first request opens root plus each of its twenty directories");
    reads.set(0); owner.snapshot();
    assertEquals(0, reads.get(), "An unchanged aged source tree must reuse every directory listing");
  }
  @Test void aNewStoreInAnExistingDeepDirectoryStillChangesSelectionImmediately() throws Exception {
    temp = temp.toRealPath(); var deep = Files.createDirectories(temp.resolve("a/b/c"));
    ageTree(); var owner = scopes(); var original = owner.select(null);
    var before = Files.getLastModifiedTime(deep); String id = UUID.randomUUID().toString();
    Files.writeString(deep.resolve("store.json"), StoreJson.JSON.toJson(new Header(1,"2026-01-01T00:00:00Z",id,List.of())));
    Files.setLastModifiedTime(deep, before); // Direct header checks must not depend on parent mtime.
    var failure = assertThrows(SourceScopes.Failure.class, () -> owner.select(null));
    assertEquals("SOURCE_SELECTION_REQUIRED", failure.result().get("code").getAsString());
    assertEquals(deep.toRealPath(), owner.select(id).root());
    assertThrows(SourceScopes.Failure.class, () -> owner.forPath(deep.resolve("future.wpilog"), original.id()));
  }
  @Test void aNewChildBelowAnAgedCachedParentChangesSelectionImmediately() throws Exception {
    temp = temp.toRealPath();
    Files.createDirectories(temp.resolve("previously-known/deep"));
    ageTree(); var owner = scopes(); var original = owner.select(null);
    reads.set(0); owner.snapshot();
    // A cached implementation may reuse this aged listing; the uncached control also works.
    var store = Files.createDirectories(temp.resolve("new-child/deep/store"));
    String id = UUID.randomUUID().toString();
    Files.writeString(store.resolve("store.json"),
        StoreJson.JSON.toJson(new Header(1, "2026-01-01T00:00:00Z", id, List.of())));
    var failure = assertThrows(SourceScopes.Failure.class, () -> owner.select(null));
    assertEquals("SOURCE_SELECTION_REQUIRED", failure.result().get("code").getAsString());
    assertEquals(store.toRealPath(), owner.select(id).root());
    assertThrows(SourceScopes.Failure.class,
        () -> owner.forPath(store.resolve("future.wpilog"), original.id()));
  }

  @Test void aRecentTimestampIsRelistedAndOneMinuteOldListingsExpire() throws Exception {
    temp = temp.toRealPath(); Files.setLastModifiedTime(temp, FileTime.fromMillis(now.get()));
    var owner = scopes(); owner.snapshot(); reads.set(0); owner.snapshot();
    assertEquals(1, reads.get(), "A timestamp within the coarse-resolution guard must be relisted");
    now.addAndGet(3_000); reads.set(0); owner.snapshot();
    assertEquals(0, reads.get(), "A now-aged unchanged listing can be reused");
    now.addAndGet(60_000); reads.set(0); owner.snapshot();
    assertEquals(1, reads.get(), "The maximum age bounds delayed filesystem attributes");
  }
}
