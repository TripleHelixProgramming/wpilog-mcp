/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.catalog;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.read.ReadQuery;
import org.triplehelix.wpilogmcp.read.rev.RecordedRevRead;
import org.triplehelix.wpilogmcp.store.StoreJson;
import org.triplehelix.wpilogmcp.store.StoreManifest;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

class StoreReadCatalogTest {
  @TempDir Path temp;
  private static final String WHEN = "2026-01-01T00:00:00Z";

  @Test void aMergedIdResolvesOnlyTheAnchorsRecordingAndLostAliasesRefuse() throws Exception {
    var fixture = fixture("alias", false);
    var captured = new Provenance("captured", null, "capture.wpilog", WHEN, false);
    var original = file(fixture.anchor(), "wpilog", captured, null);
    var other = fixture.session().resolve("other.wpilog");
    try (var writer = new WpilogWriter(other, "")) {
      int id = writer.start("counter", "int64", "{}", 0);
      writer.append(id, 1_000_001, WpilogWriter.encodeInt64(999));
    }
    var anotherBoot = file(other, "wpilog", new Provenance("captured", null, "other.wpilog",
        "2026-01-01T00:00:01Z", false), new Matching("by_correlation", original.sha256(), 0, 1, 0, 0, "data"));
    var manifest = StoreJson.JSON.toJsonTree(fixture.manifest().withFiles(List.of(original, anotherBoot))).getAsJsonObject();
    manifest.add("merged_session_ids", StoreJson.JSON.toJsonTree(List.of("old-id")));
    write(fixture.session().resolve("session.json"), manifest);
    try (var catalog = new StoreReadCatalog(List.of(fixture.root()))) {
      try (var read = catalog.openSession("store-alias", "old-id", "capture.wpilog", SessionSnapshot.Overlap.KEEP_ALL);
           var cursor = read.read(ReadQuery.all("counter"))) {
        assertEquals(List.of(42L), cursor.next().samples().stream().map(s -> s.value()).toList(),
            "Even measured alignment cannot broaden an old recording reference after a merge");
        assertEquals(1, read.sources().size());
      }
      manifest.remove("merged_session_ids"); write(fixture.session().resolve("session.json"), manifest);
      assertThrows(IOException.class, () -> catalog.openSession("store-alias", "old-id", "capture.wpilog", SessionSnapshot.Overlap.KEEP_ALL));
    }
  }

  @Test void recordedViewResolvesExactOriginAndContentAcrossServingLocations() throws Exception {
    var fixture = fixture("view-mirror", true);
    try (var catalog = new StoreReadCatalog(List.of(fixture.root()))) {
      assertEquals("origin-id", StoreReadCatalog.originId(catalog.snapshot().stores().get(0)));
      var resolved = catalog.resolveContent("origin-id", fixture.anchorFile().sha256());
      assertEquals(fixture.anchor(), resolved.facts().path());
      assertThrows(IOException.class, () -> catalog.resolveContent("store-view-mirror", fixture.anchorFile().sha256()), "serving store identity is not the origin");
      assertThrows(IOException.class, () -> catalog.resolveContent("origin-id", "0".repeat(64)), "similar names cannot substitute other content");
    }
  }

  /** A mirror folder exists before its first contact names the origin's store id. Until then it
   * answers to no origin, and a reference to another store still resolves or refuses with its
   * reason instead of failing on the unnamed mirror. */
  @Test void aMirrorThatHasNotNamedItsOriginMatchesNoReference() throws Exception {
    var fresh = Files.createDirectory(temp.resolve("a-fresh-mirror")).toRealPath();
    write(fresh.resolve("store.json"), new Header(StoreManifest.FORMAT_VERSION, WHEN, "store-fresh", List.of())
        .withOrigin(new MirrorOrigin(null, "http://origin.invalid", null, List.of(), Map.of())));
    var plain = fixture("plain", false);
    try (var catalog = new StoreReadCatalog(List.of(fresh, plain.root()))) {
      assertNull(StoreReadCatalog.originId(catalog.snapshot().stores().stream().filter(s -> s.header().mirror()).findFirst().orElseThrow()));
      assertEquals(plain.anchor(), catalog.resolveContent("store-plain", plain.anchorFile().sha256()).facts().path());
      var missing = assertThrows(IOException.class, () -> catalog.resolveContent("origin-elsewhere", plain.anchorFile().sha256()));
      assertTrue(missing.getMessage().startsWith("SOURCE_UNAVAILABLE"), missing.getMessage());
      var refused = assertThrows(IOException.class, () -> catalog.recording(new RecordingReference("store-plain", "session-id", "capture.wpilog", WHEN, List.of())));
      assertTrue(refused.getMessage().startsWith("SOURCE_UNAVAILABLE"), refused.getMessage());
    }
  }

  @Test void durableRecordingReferencesUseProvenanceAndCanRecoverOnlyKnownHashes() throws Exception {
    var fixture = fixture("durable", false);
    var first = file(fixture.anchor(), "wpilog", new Provenance("captured", null, "capture.wpilog", WHEN, false), null);
    var later = fixture.session().resolve("capture-2.wpilog");
    try (var writer = new WpilogWriter(later, "rollover")) {
      int id = writer.start("counter", "int64", "{}", 0); writer.append(id, 3_000_000, WpilogWriter.encodeInt64(84));
    }
    var rollover = file(later, "wpilog", new Provenance("captured", null, "capture-2.wpilog", WHEN, false), null);
    var peerPath = fixture.session().resolve("peer.wpilog"); Files.copy(later, peerPath);
    var peer = file(peerPath, "wpilog", new Provenance("captured", null, "peer.wpilog", WHEN, false, null,
        List.of(new PeerCopy("another-collector", "http://fixture.invalid", WHEN))), null);
    var json = StoreJson.JSON.toJsonTree(fixture.manifest().withFiles(List.of(first, rollover, peer))).getAsJsonObject();
    json.add("merged_session_ids", StoreJson.JSON.toJsonTree(List.of("old-id")));
    write(fixture.session().resolve("session.json"), json);
    var ref = new RecordingReference("store-durable", "old-id", "capture.wpilog", WHEN, List.of());
    try (var catalog = new StoreReadCatalog(List.of(fixture.root()))) {
      var recording = catalog.recording(ref);
      assertTrue(recording.finalized());
      assertEquals(java.util.Set.of(fixture.anchor(), later), recording.members().stream().map(f -> f.facts().path()).collect(java.util.stream.Collectors.toSet()));
      assertEquals("store-durable", recording.identity().collectingStoreId());
      var open = new OpenCapture(rollover.path(), rollover.provenance(), rollover.sizeBytes(), 3, 3);
      json.add("open_capture", StoreJson.JSON.toJsonTree(open));
      json.add("files", StoreJson.JSON.toJsonTree(List.of(first, peer)));
      write(fixture.session().resolve("session.json"), json);
      assertFalse(catalog.recording(ref).finalized(), "A rollover that is still open is part of this recording");
      json.add("open_capture", com.google.gson.JsonNull.INSTANCE);
      json.add("files", StoreJson.JSON.toJsonTree(List.of(first, rollover)));
      json.remove("merged_session_ids"); write(fixture.session().resolve("session.json"), json);
      assertTrue(assertThrows(IOException.class, () -> catalog.recording(ref)).getMessage().contains("SOURCE_UNAVAILABLE"));
      var finalRef = new RecordingReference(ref.originStoreId(), ref.sessionId(), ref.originalName(), WHEN, List.of(first.sha256(), rollover.sha256()));
      assertEquals(2, catalog.recording(finalRef).members().size());
      assertEquals(1, catalog.recording(new RecordingReference(ref.originStoreId(), ref.sessionId(), ref.originalName(), WHEN,
          List.of(first.sha256()))).members().size(), "Hash recovery cannot discover unproven additional members after alias loss");
      var wrong = new RecordingReference(ref.originStoreId(), ref.sessionId(), ref.originalName(), WHEN, List.of(first.sha256(), "0".repeat(64)));
      assertTrue(assertThrows(IOException.class, () -> catalog.recording(wrong)).getMessage().contains("HISTORY_UNAVAILABLE"));
      assertThrows(IOException.class, () -> catalog.recording(new RecordingReference("unconfigured", ref.sessionId(), ref.originalName(), WHEN, List.of(first.sha256()))));
    }
  }

  @Test void aDurableRecordingRefusesAmbiguousAnchorsEvenWithIdenticalBytes() throws Exception {
    var fixture = fixture("ambiguous", false);
    var provenance = new Provenance("captured", null, "capture.wpilog", WHEN, false);
    var other = fixture.session().resolve("peer.wpilog"); Files.copy(fixture.anchor(), other);
    write(fixture.session().resolve("session.json"), fixture.manifest().withFiles(List.of(
        file(fixture.anchor(), "wpilog", provenance, null), file(other, "wpilog", provenance, null))));
    try (var catalog = new StoreReadCatalog(List.of(fixture.root()))) {
      assertTrue(assertThrows(IOException.class, () -> catalog.recording(new RecordingReference(
          "store-ambiguous", "session-id", "capture.wpilog", WHEN, List.of()))).getMessage().contains("ambiguous"));
    }
  }

  @Test void manifestsAreTheOnlyMembershipAndMirrorsRetainTheirRecordedFactsWithoutWrites() throws Exception {
    var fixture = fixture("mirror", true);
    Files.writeString(fixture.root().resolve("stray.wpilog"), "not adopted");
    var before = tree(fixture.root());
    try (var catalog = new StoreReadCatalog(List.of(fixture.root()))) {
      var snapshot = catalog.snapshot();
      assertTrue(snapshot.problems().isEmpty(), snapshot.problems().toString());
      var store = snapshot.stores().get(0);
      assertEquals("store-mirror", store.header().id());
      assertTrue(store.header().mirror());
      assertEquals("origin-id", store.header().origin().storeId());
      assertEquals("serial-fixture", store.robots().get(0).robot().serialNumber());
      assertEquals("session-id", store.sessions().get(0).session().id());
      assertEquals(2, store.files().size());
      assertTrue(store.unmanaged().isEmpty(), "A read catalog never inventories or adopts strays");
      assertTrue(catalog.resolve(fixture.root().resolve("stray.wpilog")).isEmpty());
      var file = catalog.resolve(fixture.anchor()).orElseThrow();
      assertEquals("store-mirror", file.storeId());
      assertEquals("usb", file.facts().file().provenance().kind());
      assertEquals("peer-id", file.facts().file().provenance().copiedFrom().get(0).storeId());
      try (var read = catalog.openWpilog(fixture.anchor()); var cursor = read.read(ReadQuery.all("counter"))) {
        var batch = cursor.next();
        assertEquals(42L, batch.samples().get(0).value());
        assertEquals(1_000_001L, batch.samples().get(0).timestampUs());
      }
    }
    assertEquals(before, tree(fixture.root()), "Readers create no locks, manifests, caches or receipts");
  }

  @Test void aMalformedSessionAndAnUnreadableStoreAreExplainedWithoutHidingGoodSessions() throws Exception {
    var fixture = fixture("good", false);
    var badSession = fixture.session().getParent().resolve("bad-session");
    Files.createDirectories(badSession);
    Files.writeString(badSession.resolve("session.json"), "{bad");
    var badStore = Files.createDirectory(temp.resolve("bad-store")).toRealPath();
    Files.writeString(badStore.resolve("store.json"), "{bad");
    try (var catalog = new StoreReadCatalog(List.of(fixture.root(), badStore))) {
      var snapshot = catalog.snapshot();
      assertEquals(1, snapshot.stores().size());
      assertEquals(1, snapshot.stores().get(0).sessions().size());
      assertEquals(2, snapshot.problems().size());
      assertTrue(snapshot.problems().stream().anyMatch(p -> p.manifest().equals(badSession.resolve("session.json"))));
      assertTrue(snapshot.problems().stream().anyMatch(p -> p.manifest().equals(badStore.resolve("store.json"))));
      assertTrue(snapshot.problems().stream().allMatch(p -> !p.reason().isBlank()));
    }
  }

  @Test void sharedSystemTextAppearsOnceWithItsSpanAndProvenance() throws Exception {
    var fixture = fixture("shared", false);
    var system = Files.createDirectories(fixture.root().resolve("robots/serial-fixture/system"));
    var text = system.resolve("messages.txt");
    Files.writeString(text, "A synthetic system line\n");
    var receipt = new org.triplehelix.wpilogmcp.store.SystemLogState.File(
        org.triplehelix.wpilogmcp.store.SystemLogState.Location.STORE, relative(fixture.root(), text),
        "syslog", "text", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(text))),
        Files.size(text), fixture.anchorFile().provenance(), "fixture");
    var index = new org.triplehelix.wpilogmcp.store.SystemLogIndex(List.of(
        new org.triplehelix.wpilogmcp.store.SystemLogIndex.Entry(receipt,
            new org.triplehelix.wpilogmcp.store.SystemLogIndex.Span(10, 20))));
    write(system.resolve("index.json"), index);
    try (var catalog = new StoreReadCatalog(List.of(fixture.root()))) {
      var snapshot = catalog.snapshot();
      assertEquals(1, snapshot.sharedSystemLogs().size());
      var shared = snapshot.sharedSystemLogs().get(0);
      assertEquals("store-shared", shared.storeId());
      assertEquals("serial-fixture", shared.robotId());
      assertEquals(system.resolve("index.json"), shared.index());
      assertEquals(index, shared.inventory());
      assertEquals("peer-id", shared.inventory().files().get(0).file().provenance().copiedFrom().get(0).storeId());
    }
  }

  @Test void aBadRobotManifestDoesNotHideTheOtherRobotsInItsStore() throws Exception {
    var fixture = fixture("partial-robots", false);
    var badRobot = Files.createDirectories(fixture.root().resolve("robots/bad"));
    Files.writeString(badRobot.resolve("robot.json"), "{bad");
    try (var catalog = new StoreReadCatalog(List.of(fixture.root()))) {
      var snapshot = catalog.snapshot();
      assertEquals(1, snapshot.stores().size(), "One broken robot must not discard a readable store");
      assertEquals(1, snapshot.stores().get(0).robots().size());
      assertEquals(1, snapshot.problems().size());
      assertEquals(badRobot.resolve("robot.json"), snapshot.problems().get(0).manifest());
    }
  }

  @Test void durableMoveChainsResolveToCatalogMembersAndCyclesAreRefused() throws Exception {
    var fixture = fixture("moves", false);
    var old = fixture.root().resolve("old.wpilog");
    var middle = fixture.root().resolve("middle.wpilog");
    var header = new Header(StoreManifest.FORMAT_VERSION, WHEN, "store-moves", List.of(
        new Move(old.toString(), "middle.wpilog", WHEN),
        new Move(middle.toString(), relative(fixture.root(), fixture.anchor()), WHEN)));
    write(fixture.root().resolve("store.json"), header);
    try (var catalog = new StoreReadCatalog(List.of(fixture.root()))) {
      assertEquals(fixture.anchor(), catalog.resolve(old).orElseThrow().facts().path());
      write(fixture.root().resolve("store.json"), header.withMoves(List.of(
          new Move(old.toString(), "middle.wpilog", WHEN), new Move(middle.toString(), "old.wpilog", WHEN))));
      assertTrue(assertThrows(IOException.class, () -> catalog.resolve(old)).getMessage().contains("Cyclic"));
    }
  }

  @Test void malformedMoveRecordsAreExplainedAsReadFailures() throws Exception {
    var fixture = fixture("bad-alias", false);
    write(fixture.root().resolve("store.json"), new Header(StoreManifest.FORMAT_VERSION, WHEN, "bad-alias", null));
    try (var catalog = new StoreReadCatalog(List.of(fixture.root()))) {
      var failure = assertThrows(IOException.class, () -> catalog.resolve(fixture.anchor()));
      assertTrue(failure.getMessage().contains("store.json"));
    }
  }

  @Test void explicitRootsAndMembershipRejectOutsideFilesAndControlManifests() throws Exception {
    var fixture = fixture("bounds", false);
    var outside = temp.resolve("outside.wpilog");
    Files.copy(fixture.anchor(), outside);
    assertThrows(IllegalArgumentException.class, () -> new StoreReadCatalog(List.of()));
    try (var catalog = new StoreReadCatalog(List.of(fixture.root()))) {
      assertThrows(IOException.class, () -> catalog.openWpilog(outside));
      assertThrows(IOException.class, () -> catalog.openWpilog(fixture.root().resolve("store.json")));
      assertThrows(IOException.class, () -> catalog.openWpilog(fixture.companion()));
      catalog.close();
      assertThrows(IOException.class, catalog::snapshot);
    }
  }

  @Test void recordedOffsetAndDriftAreAppliedWithTheirOriginalConfidenceEvenInAMirror() throws Exception {
    var fixture = fixture("alignment", true);
    try (var catalog = new StoreReadCatalog(List.of(fixture.root()))) {
      var read = catalog.openRev(fixture.companion(), fixture.anchor());
      assertEquals(RecordedRevRead.State.RECORDED, read.state());
      assertEquals(250_000L, read.alignment().offsetMicros());
      assertEquals(0.9, read.alignment().confidence());
      var key = read.signals().keySet().stream().filter(k -> k.endsWith("/AppliedOutput")).findFirst().orElseThrow();
      var raw = read.rawValues(key); var aligned = read.values(key);
      assertEquals(List.of(1.0, 3.0), raw.stream().map(v -> v.timestamp()).toList());
      // Offset +0.25 s; drift +1 ms/s about t=1 s adds 0 at 1 s and 0.002 at 3 s.
      assertEquals(List.of(1.25, 3.252), aligned.stream().map(v -> v.timestamp()).toList());
      assertEquals(raw.stream().map(v -> v.value()).toList(), aligned.stream().map(v -> v.value()).toList());
      assertThrows(UnsupportedOperationException.class, () -> raw.clear());
      assertThrows(UnsupportedOperationException.class, () -> read.signals().clear());
    }
  }

  @Test void aBoundedRevPageUsesTheSameRecordedClockAndHandlesLargeLimitsWithoutOverflow() throws Exception {
    var fixture = fixture("paged-rev", false);
    try (var catalog = new StoreReadCatalog(List.of(fixture.root()))) {
      var read = catalog.openRev(fixture.companion(), fixture.anchor());
      var key = read.signals().keySet().iterator().next();
      assertEquals(List.of(3.252), read.samples(key, 1, 1, RecordedRevRead.Clock.FPGA).stream().map(v -> v.timestamp()).toList());
      assertEquals(List.of(3.0), read.samples(key, 1, Integer.MAX_VALUE, RecordedRevRead.Clock.RAW).stream().map(v -> v.timestamp()).toList());
      assertTrue(read.samples(key, Integer.MAX_VALUE, Integer.MAX_VALUE, RecordedRevRead.Clock.RAW).isEmpty());
      assertThrows(IllegalArgumentException.class, () -> read.samples(key, -1, 1, RecordedRevRead.Clock.RAW));
    }
  }

  @Test void aNegativeRecordedOffsetKeepsItsSignAndNeverChangesTheRawClock() throws Exception {
    var fixture = fixture("negative", false);
    var old = fixture.revFile();
    var changed = new LogFile(old.path(), old.sha256(), old.sizeBytes(), old.kind(), old.provenance(),
        true, 1, 3, WHEN, WHEN, "log", false,
        new Matching("by_correlation", fixture.anchorFile().sha256(), -750_000, 0.8, 0, 0, "data"));
    write(fixture.session().resolve("session.json"), fixture.manifest().withFiles(List.of(fixture.anchorFile(), changed)));
    try (var catalog = new StoreReadCatalog(List.of(fixture.root()))) {
      var read = catalog.openRev(fixture.companion(), fixture.anchor());
      var key = read.signals().keySet().iterator().next();
      assertEquals(List.of(0.25, 2.25), read.values(key).stream().map(v -> v.timestamp()).toList());
      assertEquals(List.of(1.0, 3.0), read.rawValues(key).stream().map(v -> v.timestamp()).toList());
    }
  }

  @Test void missingOrCorruptAlignmentRetainsRawSignalsButNeverInventsAnOffset() throws Exception {
    var fixture = fixture("unmatched", false);
    var original = fixture.manifest();
    for (Matching match : java.util.Arrays.asList(null,
        new Matching("by_correlation", fixture.anchorFile().sha256(), 250_000, 2, 0, 0, "data"),
        new Matching("by_correlation", "f".repeat(64), 250_000, 0.9, 0, 0, "data"))) {
      var old = fixture.revFile();
      var changed = new LogFile(old.path(), old.sha256(), old.sizeBytes(), old.kind(), old.provenance(),
          true, 1, 3, WHEN, WHEN, "log", false, match);
      write(fixture.session().resolve("session.json"), original.withFiles(List.of(fixture.anchorFile(), changed)));
      try (var catalog = new StoreReadCatalog(List.of(fixture.root()))) {
        var read = catalog.openRev(fixture.companion(), fixture.anchor());
        assertEquals(RecordedRevRead.State.UNSYNCHRONIZED, read.state());
        assertFalse(read.reason().isBlank());
        assertFalse(read.alignment().isSuccessful());
        var key = read.signals().keySet().iterator().next();
        assertEquals(2, read.rawValues(key).size());
        assertThrows(IOException.class, () -> read.values(key));
        if (match != null && match.confidence() == 2) assertTrue(read.reason().contains("session.json"));
      }
    }
  }

  @Test void adaptersCannotInitializeManagersWritersCachesOrCorrelation() throws Exception {
    for (String directory : List.of("read/catalog", "read/rev")) {
      try (var paths = Files.walk(Path.of("src/main/java/org/triplehelix/wpilogmcp").resolve(directory))) {
        for (var path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
          var source = Files.readString(path);
          for (String forbidden : List.of("LogManager", "StoreRegistry", "LogStore", "LogSynchronizer",
              "SyncDiskCache", "DiskCache", "Files.write", "Files.create", "System.getenv", "user.home")) {
            assertFalse(source.contains(forbidden), path + " introduces forbidden owner " + forbidden);
          }
        }
      }
    }
  }

  @Test void catalogDependenciesRemainPureForTheStandaloneLibrary() throws Exception {
    var base = Path.of("src/main/java/org/triplehelix/wpilogmcp");
    var catalog = Files.readString(base.resolve("store/StoreCatalog.java"));
    for (String writer : List.of("StoreSync.", "MirrorSync.", "SystemPeerSync.")) {
      assertFalse(catalog.contains(writer), "Catalog inventory must not link a store writer: " + writer);
    }
    var fingerprints = Files.readString(base.resolve("log/RobotCandidates.java"));
    assertFalse(fingerprints.contains("LogDirectory."), "Fingerprint facts must not link the directory singleton");
    assertFalse(fingerprints.contains("LogMetadata "), "Fingerprint input must not link the analysis resolver");
  }

  @Test void repeatedReadsReuseInventoryAndDecodedRevButKeepAlignmentPerRequest() throws Exception {
    var fixture = fixture("reuse", false);
    try (var catalog = new StoreReadCatalog(List.of(fixture.root()))) {
      var first = catalog.snapshot();
      assertSame(first, catalog.snapshot(), "Unchanged inventory must not be parsed again");
      var a = catalog.openRev(fixture.companion(), fixture.anchor());
      var b = catalog.openRev(fixture.companion(), fixture.anchor());
      assertSame(a.signals(), b.signals(), "One immutable REV decode serves all signal queries");
    }
  }

  @Test void repeatedRevReadsShareTheDecodeWithoutDependingOnInventoryReuse() throws Exception {
    var fixture = fixture("rev-reuse", false);
    try (var catalog = new StoreReadCatalog(List.of(fixture.root()))) {
      var a = catalog.openRev(fixture.companion(), fixture.anchor());
      var b = catalog.openRev(fixture.companion(), fixture.anchor());
      assertSame(a.signals(), b.signals(), "One immutable REV decode serves all signal queries");
    }
  }

  @Test void cachedInventoryFollowsNewManifestsAtomicReplacementAndPayloadLoss() throws Exception {
    var fixture = fixture("freshness", false);
    try (var catalog = new StoreReadCatalog(List.of(fixture.root()))) {
      var first = catalog.snapshot();
      var added = Files.createDirectories(fixture.session().getParent().resolve("new-session"));
      write(added.resolve("session.json"), new Session("new", WHEN, WHEN, "log", null, null, null, null, List.of()));
      var second = catalog.snapshot();
      assertNotSame(first, second);
      assertEquals(2, second.stores().get(0).sessions().size());
      var replacement = fixture.session().resolve("replacement.json");
      write(replacement, fixture.manifest().withFiles(List.of(fixture.anchorFile())));
      Files.move(replacement, fixture.session().resolve("session.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      assertTrue(catalog.resolve(fixture.companion()).isEmpty(), "Removed membership is not served from cache");
      Files.delete(fixture.anchor());
      assertTrue(catalog.resolve(fixture.anchor()).isEmpty(), "A disappeared payload invalidates its session");
      assertTrue(catalog.snapshot().problems().stream().anyMatch(p -> p.manifest().equals(fixture.session().resolve("session.json"))));
    }
  }

  @Test void sessionUnionRequiresAnAnchorAndPreservesBothProvenClocksAndSources() throws Exception {
    var fixture = fixture("union", false);
    var other = fixture.session().resolve("other.wpilog");
    try (var writer = new WpilogWriter(other, "")) {
      int id = writer.start("counter", "int64", "{}", 0);
      writer.append(id, 500_001, WpilogWriter.encodeInt64(84));
    }
    var matched = file(other, "wpilog", fixture.anchorFile().provenance(),
        new Matching("by_correlation", fixture.anchorFile().sha256(), 500_000, .9, 0, 0, "data"));
    var loose = fixture.session().resolve("calendar.wpilog");
    try (var writer = new WpilogWriter(loose, "")) {
      int id = writer.start("counter", "int64", "{}", 0); writer.append(id, 1_000_001, WpilogWriter.encodeInt64(999));
    }
    write(fixture.session().resolve("session.json"), fixture.manifest().withFiles(
        List.of(fixture.anchorFile(), matched, file(loose, "wpilog", matched.provenance(), null))));
    var before = tree(fixture.root());
    try (var catalog = new StoreReadCatalog(List.of(fixture.root()))) {
      var overlap = assertThrows(IOException.class, () -> catalog.openSession("store-union", "session-id", "capture.wpilog", SessionSnapshot.Overlap.REJECT));
      assertEquals("Overlapping recordings of 'counter'; choose KEEP_ALL to retain both sources",
          overlap.getMessage(), "A closed session must not be described as a live recording");
      try (var union = catalog.openSession("store-union", "session-id", "capture.wpilog", SessionSnapshot.Overlap.KEEP_ALL)) {
        assertFalse(union.live()); assertEquals(2, union.sources().size());
        assertEquals(1, union.excludedSources().size()); assertTrue(union.excludedSources().get(0).reason().contains("No recorded alignment"));
        var query = ReadQuery.window("counter", 1_000_001, 1_000_001, true);
        try (var cursor = union.read(query)) {
          var values = cursor.next().samples(); assertEquals(2, values.size());
          assertEquals(List.of(42L, 84L), values.stream().map(v -> v.value()).toList());
          assertEquals(List.of(1_000_001L, 1_000_001L), values.stream().map(v -> v.timestampUs()).toList());
          assertEquals(500_001, values.get(1).source().timestampUs()); assertEquals(other.toString(), values.get(1).source().path());
        }
        var small = new ReadQuery("counter", query.windows(), ReadQuery.Order.TIME, 1, 1024);
        var stamp = Files.getLastModifiedTime(other);
        try (var cursor = union.read(small)) {
          assertEquals(42L, cursor.next().samples().get(0).value());
          Files.setLastModifiedTime(other, java.nio.file.attribute.FileTime.fromMillis(stamp.toMillis() + 2000));
          assertEquals(org.triplehelix.wpilogmcp.read.ReadProblem.Code.FILE_CHANGED,
              assertThrows(org.triplehelix.wpilogmcp.read.ReadException.class, cursor::next).code());
        } finally { Files.setLastModifiedTime(other, stamp); }
        try (var cursor = union.read(query.reduce(1))) {
          assertEquals(63., ((Map<?, ?>) cursor.next().samples().get(0).value()).get("mean"));
          cursor.close();
          assertEquals(org.triplehelix.wpilogmcp.read.ReadProblem.Code.CLOSED,
              assertThrows(org.triplehelix.wpilogmcp.read.ReadException.class, cursor::next).code());
        }
      }
    }
    assertEquals(before, tree(fixture.root()), "A union writes no inferred association or catalog state");
  }

  @Test void capturedRolloverUsesRecordedClockButOtherRecordersNeedAlignment() throws Exception {
    var fixture = fixture("rollover", false);
    var captured = new Provenance("captured", null, "capture.wpilog", WHEN, false);
    var first = file(fixture.anchor(), "wpilog", captured, null);
    var rollover = fixture.session().resolve("capture-2.wpilog");
    try (var writer = new WpilogWriter(rollover, "")) {
      int id = writer.start("counter", "int64", "{}", 0);
      writer.append(id, 500_001, WpilogWriter.encodeInt64(21));
      writer.append(id, 2_000_001, WpilogWriter.encodeInt64(84));
    }
    var second = file(rollover, "wpilog", captured, null);
    var foreign = fixture.session().resolve("peer.wpilog");
    try (var writer = new WpilogWriter(foreign, "")) {
      int id = writer.start("counter", "int64", "{}", 0);
      writer.append(id, 1_500_001, WpilogWriter.encodeInt64(999));
    }
    var peer = new Provenance("captured", null, "capture.wpilog", WHEN, false,
        null, List.of(new PeerCopy("another-recorder", "http://fixture.invalid", WHEN)));
    var later = new Provenance("captured", null, "capture.wpilog", "2026-01-01T00:00:01Z", false);
    for (var unrelated : List.of(peer, later)) {
      write(fixture.session().resolve("session.json"), fixture.manifest().withFiles(
          List.of(first, second, file(foreign, "wpilog", unrelated, null))));
      try (var catalog = new StoreReadCatalog(List.of(fixture.root()));
           var union = catalog.openSession("store-rollover", "session-id", "capture.wpilog", SessionSnapshot.Overlap.KEEP_ALL);
           var cursor = union.read(ReadQuery.all("counter"))) {
        assertEquals(2, union.sources().size(), "Rollover files have captured provenance, not capture");
        assertEquals(List.of(21L, 42L, 84L), cursor.next().samples().stream().map(v -> v.value()).toList());
        assertEquals(foreign.toString(), union.excludedSources().get(0).path(),
            "Peer consolidation and calendar overlap cannot prove a common recording clock");
      }
    }
    var returned = new Provenance("captured", null, "capture.wpilog", WHEN, false,
        null, List.of(new PeerCopy("store-rollover", "http://fixture.invalid", WHEN)));
    write(fixture.session().resolve("session.json"), fixture.manifest().withFiles(
        List.of(first, second, file(foreign, "wpilog", returned, null))));
    try (var catalog = new StoreReadCatalog(List.of(fixture.root()));
         var union = catalog.openSession("store-rollover", "session-id", "capture.wpilog", SessionSnapshot.Overlap.KEEP_ALL)) {
      assertEquals(3, union.sources().size(), "A peer hop preserves the original collecting store's identity");
    }
    write(fixture.root().resolve("store.json"), new Header(1, WHEN, "mirror-copy", List.of()).withOrigin(
        new MirrorOrigin("store-rollover", "http://fixture.invalid", WHEN, List.of(), Map.of())));
    try (var catalog = new StoreReadCatalog(List.of(fixture.root()));
         var union = catalog.openSession("mirror-copy", "session-id", "capture.wpilog", SessionSnapshot.Overlap.KEEP_ALL)) {
      assertEquals(3, union.sources().size(), "A mirror's unchanged provenance refers to its origin store");
    }
    write(fixture.root().resolve("store.json"), new Header(1, WHEN, "store-rollover", List.of()));
    // An old session-level match without an anchor hash is usable only on one recorded capture clock.
    write(fixture.session().resolve("session.json"), fixture.manifest().withFiles(List.of(first, second,
        file(foreign, "wpilog", new Provenance("usb", null, "peer.wpilog", WHEN, false),
            new Matching("by_correlation", null, 500_000, .9, 0, 0, "data")))));
    try (var catalog = new StoreReadCatalog(List.of(fixture.root()));
         var union = catalog.openSession("store-rollover", "session-id", "capture.wpilog", SessionSnapshot.Overlap.KEEP_ALL)) {
      assertEquals(3, union.sources().size());
    }
  }

  @Test void localSessionNamesOpenMembersItCannotFreezeAndRefusesAnOpenAnchor() throws Exception {
    var fixture = fixture("growing", false);
    var provenance = new Provenance("captured", null, "capture.wpilog", WHEN, false);
    var held = file(fixture.anchor(), "wpilog", provenance, null);
    var growing = fixture.session().resolve("growing.wpilog");
    Files.copy(fixture.anchor(), growing);
    var open = new OpenCapture("growing.wpilog", provenance, Files.size(growing), 1, 3);
    write(fixture.session().resolve("session.json"), new Session("session-id", WHEN, WHEN, "pit_clock",
        null, null, null, null, List.of(held), open));
    try (var catalog = new StoreReadCatalog(List.of(fixture.root()));
         var session = catalog.openSession("store-growing", "session-id", "capture.wpilog", SessionSnapshot.Overlap.KEEP_ALL)) {
      assertEquals(1, session.sources().size(), "A local session cannot freeze another process's open capture");
      assertEquals(growing.toString(), session.excludedSources().get(0).path());
      assertTrue(session.excludedSources().get(0).reason().contains("open capture"));
      var error = assertThrows(IOException.class, () -> catalog.openSession("store-growing", "session-id", "growing.wpilog", SessionSnapshot.Overlap.KEEP_ALL));
      assertTrue(error.getMessage().contains("open capture"), error.getMessage());
      assertTrue(error.getMessage().contains(growing.toString()));
    }
  }

  @Test void sessionIndexBudgetIsSharedAndHeldUntilItsLastCursorCloses() throws Exception {
    var fixture = fixture("budget", false);
    var options = org.triplehelix.wpilogmcp.read.ReadOptions.defaults(List.of(fixture.root()));
    try (var catalog = new StoreReadCatalog(options, null, 4)) {
      var first = catalog.openSession("store-budget", "session-id", "capture.wpilog", SessionSnapshot.Overlap.REJECT);
      assertEquals(4, first.indexBytes());
      try (var cursor = first.read(ReadQuery.all("counter"))) {
        first.close();
        var error = assertThrows(org.triplehelix.wpilogmcp.read.ReadException.class, () ->
            catalog.openSession("store-budget", "session-id", "capture.wpilog", SessionSnapshot.Overlap.REJECT));
        assertEquals(org.triplehelix.wpilogmcp.read.ReadProblem.Code.RESOURCE_LIMIT, error.code());
        assertTrue(error.getMessage().contains("4 held"), error.getMessage());
        assertEquals(42L, cursor.next().samples().get(0).value(), "An admitted cursor survives snapshot retirement");
      }
      try (var next = catalog.openSession("store-budget", "session-id", "capture.wpilog", SessionSnapshot.Overlap.REJECT)) {
        assertEquals(4, next.indexBytes(), "The failed admission and retired cursor release their charges");
      }
    }
  }

  @Test void managedSessionBorrowsCachedDiskIndexAndDoesNotCloseItsAccessOwner() throws Exception {
    var fixture = fixture("managed", false);
    var manager = org.triplehelix.wpilogmcp.log.LogManager.getInstance(); manager.resetConfiguration();
    manager.addAllowedDirectory(fixture.root());
    try (var access = new org.triplehelix.wpilogmcp.read.server.ManagedLogAccess(manager); var original = access.open(fixture.anchor())) {
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      var borrowed = new org.triplehelix.wpilogmcp.read.LogAccess() {
        @Override public org.triplehelix.wpilogmcp.read.ReadSnapshot open(Path path) throws IOException {
          calls.incrementAndGet(); var snapshot = access.open(path);
          assertSame(original.log(), snapshot.log(), "Session members must share the cached scan and decoded-value owner");
          return snapshot;
        }
        @Override public void close() { fail("A catalog must not close borrowed server admission"); }
      };
      try (var catalog = new StoreReadCatalog(org.triplehelix.wpilogmcp.read.ReadOptions.defaults(List.of(fixture.root())), borrowed, 4);
           var session = catalog.openSession("store-managed", "session-id", "capture.wpilog", SessionSnapshot.Overlap.REJECT)) {
        assertEquals(1, calls.get()); assertEquals(4, session.indexBytes());
      }
      try (var next = access.open(fixture.anchor())) { assertSame(original.log(), next.log()); }
    } finally { manager.resetConfiguration(); }
  }

  @Test void narrowSessionWindowSeeksBeforeDecodingWithOffsetAndDrift() throws Exception {
    var fixture = fixture("seek", false);
    var other = fixture.session().resolve("offset.wpilog");
    for (var path : List.of(fixture.anchor(), other)) {
      boolean anchor = path.equals(fixture.anchor());
      try (var writer = new WpilogWriter(path, "")) {
        int id = writer.start("counter", "int64", "{}", 0);
        for (int i = 0; i < 10_000; i++) writer.append(id, (anchor ? 1_000_000 : 500_000) + 1000L * i,
            WpilogWriter.encodeInt64((anchor ? 0 : 100_000) + i));
      }
    }
    var anchor = file(fixture.anchor(), "wpilog", fixture.anchorFile().provenance(), null);
    var matched = file(other, "wpilog", fixture.anchorFile().provenance(),
        new Matching("by_correlation", anchor.sha256(), 500_000, .9, 1_000_000, .5, "data"));
    write(fixture.session().resolve("session.json"), fixture.manifest().withFiles(List.of(anchor, matched)));
    try (var catalog = new StoreReadCatalog(List.of(fixture.root()));
         var union = catalog.openSession("store-seek", "session-id", "capture.wpilog", SessionSnapshot.Overlap.KEEP_ALL);
         var cursor = union.read(ReadQuery.window("counter", 9_008_000, 9_010_002, true))) {
      var values = new java.util.ArrayList<Object>(); var timestamps = new java.util.ArrayList<Long>();
      int visited = 0;
      for (boolean done = false; !done;) {
        var batch = cursor.next(); done = batch.complete(); visited += batch.recordsVisited();
        batch.samples().forEach(sample -> { values.add(sample.value()); timestamps.add(sample.timestampUs()); });
      }
      // Source B: y = x + 500000 + (x - 500000)/1000, hence y_i = 1000000 + 1001*i.
      assertEquals(List.of(8008L, 108000L, 8009L, 108001L, 8010L, 108002L), values);
      assertEquals(List.of(9_008_000L, 9_008_000L, 9_009_000L, 9_009_001L, 9_010_000L, 9_010_002L), timestamps);
      assertTrue(visited <= 20, "A six-row window must not decode all 20000 records; visited=" + visited);
    }
  }

  @Test void concurrentSessionOpenIsBusyAndCatalogRetirementClosesAnInFlightAdmission() throws Exception {
    var fixture = fixture("concurrent", false);
    var options = org.triplehelix.wpilogmcp.read.ReadOptions.defaults(List.of(fixture.root()));
    var entered = new java.util.concurrent.CountDownLatch(1); var resume = new java.util.concurrent.CountDownLatch(1);
    var worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { var t = new Thread(r); t.setDaemon(true); return t; });
    try (var local = org.triplehelix.wpilogmcp.read.LogAccess.local(options)) {
      var borrowed = new org.triplehelix.wpilogmcp.read.LogAccess() {
        @Override public org.triplehelix.wpilogmcp.read.ReadSnapshot open(Path path) throws IOException {
          entered.countDown();
          try { if (!resume.await(30, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Test did not release admission"); }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
          return local.open(path);
        }
        @Override public void close() { fail("Borrowed admission is not owned"); }
      };
      try (var catalog = new StoreReadCatalog(options, borrowed, 4)) {
        var opening = worker.submit(() -> catalog.openSession("store-concurrent", "session-id", "capture.wpilog", SessionSnapshot.Overlap.REJECT));
        assertTrue(entered.await(30, java.util.concurrent.TimeUnit.SECONDS));
        var busy = assertThrows(org.triplehelix.wpilogmcp.read.ReadException.class, () ->
            catalog.openSession("store-concurrent", "session-id", "capture.wpilog", SessionSnapshot.Overlap.REJECT));
        assertEquals(org.triplehelix.wpilogmcp.read.ReadProblem.Code.BUSY, busy.code());
        catalog.close(); resume.countDown();
        var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> opening.get(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(org.triplehelix.wpilogmcp.read.ReadProblem.Code.CLOSED,
            assertInstanceOf(org.triplehelix.wpilogmcp.read.ReadException.class, failure.getCause()).code());
        // The failed admission retired its mapping; a move can reserve it on Windows as well.
        try (var move = org.triplehelix.wpilogmcp.log.LogFileAccess.move(List.of(fixture.anchor()))) {
          Files.move(fixture.anchor(), fixture.anchor().resolveSibling("released.wpilog"));
        }
      }
    } finally { resume.countDown(); worker.shutdownNow(); }
  }

  @Test void anExcludedDuplicateDoesNotHideALaterProvenSource() throws Exception {
    var fixture = fixture("duplicate", false);
    var unproven = fixture.session().resolve("unproven.wpilog");
    try (var writer = new WpilogWriter(unproven, "")) {
      int id = writer.start("counter", "int64", "{}", 0); writer.append(id, 2_000_001, WpilogWriter.encodeInt64(84));
    }
    var proven = fixture.session().resolve("proven.wpilog"); Files.copy(unproven, proven);
    var provenance = fixture.anchorFile().provenance();
    write(fixture.session().resolve("session.json"), fixture.manifest().withFiles(List.of(fixture.anchorFile(),
        file(unproven, "wpilog", provenance, null), file(proven, "wpilog", provenance,
            new Matching("by_correlation", fixture.anchorFile().sha256(), 0, .9, 0, 0, "data")))));
    try (var catalog = new StoreReadCatalog(List.of(fixture.root()));
         var session = catalog.openSession("store-duplicate", "session-id", "capture.wpilog", SessionSnapshot.Overlap.KEEP_ALL);
         var cursor = session.read(ReadQuery.all("counter"))) {
      assertEquals(List.of(42L, 84L), cursor.next().samples().stream().map(v -> v.value()).toList(),
          "Deduplication counts admitted sources, not excluded candidates");
    }
  }

  private record Fixture(Path root, Path session, Path anchor, Path companion, Session manifest,
      LogFile anchorFile, LogFile revFile) {}
  private Fixture fixture(String name, boolean mirror) throws Exception {
    var root = Files.createDirectory(temp.resolve(name)).toRealPath();
    var robot = root.resolve("robots/serial-fixture");
    var session = Files.createDirectories(robot.resolve("sessions/2026-01-01/session-id"));
    var anchor = session.resolve("capture.wpilog"); var rev = session.resolve("bus.revlog");
    try (var writer = new WpilogWriter(anchor, "")) {
      int id = writer.start("counter", "int64", "{}", 0);
      writer.append(id, 1_000_001, WpilogWriter.encodeInt64(42));
    }
    try (var writer = new WpilogWriter(rev, "")) {
      int id = writer.start("CAN/3/Periodic Status 0", "raw", "", 0);
      writer.append(id, 1_000_000, FixtureLogs.sparkStatus0(0.25, 12, 2, 30, false));
      writer.append(id, 3_000_000, FixtureLogs.sparkStatus0(0.5, 12, 2, 30, false));
      writer.finish(id, 3_000_001); // Preserve the existing REV iterator's short-final-record rule.
    }
    var provenance = new Provenance("usb", "/fixture/source", "capture.wpilog", WHEN, false,
        "serial-fixture", List.of(new PeerCopy("peer-id", "http://fixture.invalid", WHEN)));
    var anchorFile = file(anchor, "wpilog", provenance, null);
    var revFile = file(rev, "revlog", provenance, new Matching("by_correlation", anchorFile.sha256(),
        250_000, 0.9, 1_000_000, 1, "data"));
    var manifest = new Session("session-id", WHEN, WHEN, "log", "event", "qualification", 1, 1234,
        List.of(anchorFile, revFile));
    var header = new Header(StoreManifest.FORMAT_VERSION, WHEN, "store-" + name, List.of());
    if (mirror) header = header.withOrigin(new MirrorOrigin("origin-id", "http://origin.invalid", WHEN, List.of(), Map.of()));
    write(root.resolve("store.json"), header);
    write(robot.resolve("robot.json"), new Robot("serial-fixture", "serial-fixture", "Fixture", "comment", "logged"));
    write(session.resolve("session.json"), manifest);
    return new Fixture(root, session, anchor, rev, manifest, anchorFile, revFile);
  }
  private static LogFile file(Path path, String kind, Provenance provenance, Matching matching) throws Exception {
    var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    return new LogFile(path.getFileName().toString(), hash, Files.size(path), kind, provenance,
        true, 1, 3, WHEN, WHEN, "log", false, matching);
  }
  private static void write(Path path, Object value) throws IOException { Files.writeString(path, StoreJson.JSON.toJson(value)); }
  private static String relative(Path root, Path path) { return root.relativize(path).toString().replace('\\', '/'); }
  private static Map<String, String> tree(Path root) throws Exception {
    var result = new java.util.TreeMap<String, String>();
    try (var files = Files.walk(root)) {
      for (var path : files.filter(Files::isRegularFile).toList()) {
        result.put(relative(root, path), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
      }
    }
    return result;
  }
}
