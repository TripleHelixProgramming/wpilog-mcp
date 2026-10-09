/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;

class IncrementalScanTest {
  @TempDir Path temp;
  record Fixture(Path path, byte[] bytes, int half, int last) {}
  Fixture fixture() throws Exception {
    var path = temp.resolve("source.wpilog");
    try (var w = new WpilogWriter(path, "synthetic")) {
      int x = w.start("/x", "double", "original", 0);
      w.append(x, 1_000_000, WpilogWriter.encodeDouble(2));
      w.append(x, 2_000_000, WpilogWriter.encodeDouble(4));
      w.setMetadata(x, "updated", 2_000_000);
      int y = w.start("/late", "double", "late", 2_000_000);
      w.append(y, 3_000_000, WpilogWriter.encodeDouble(9));
      w.append(x, 4_000_000, WpilogWriter.encodeDouble(8));
    }
    byte[] bytes = Files.readAllBytes(path); int half, last;
    try (var r = new ScopedLogReader(path)) {
      var scan = LogScan.of(r.reader(), path);
      half = Math.toIntExact(scan.offsets().get("/x").get(1));
      last = Math.toIntExact(scan.offsets().get("/x").get(2));
    }
    Files.write(path, Arrays.copyOf(bytes, half));
    return new Fixture(path, bytes, half, last);
  }
  static LogScan scan(Path path) throws Exception {
    try (var r = new ScopedLogReader(path, 64)) { return LogScan.of(r.reader(), path); }
  }
  static LogScan resume(LogScan previous, Path path) throws Exception {
    try (var r = new ScopedLogReader(path, 64)) { return LogScan.resume(previous, r.reader(), path); }
  }
  static void equalScan(LogScan expected, LogScan actual) {
    assertEquals(expected.entries(), actual.entries());
    assertEquals(expected.minTimestamp(), actual.minTimestamp()); assertEquals(expected.maxTimestamp(), actual.maxTimestamp());
    assertEquals(expected.dataRecords(), actual.dataRecords()); assertEquals(expected.truncated(), actual.truncated());
    assertEquals(expected.damaged(), actual.damaged()); assertEquals(expected.truncationMessage(), actual.truncationMessage());
    assertEquals(expected.resumePoint(), actual.resumePoint());
    expected.offsets().forEach((name, offsets) -> {
      assertEquals(offsets.size(), actual.offsets().get(name).size(), name);
      for (int i = 0; i < offsets.size(); i++) assertEquals(offsets.get(i), actual.offsets().get(name).get(i), name);
    });
  }
  @Test void appendKeepsOldIndexAndIndexesLateDeclarationsAndMetadata() throws Exception {
    var f = fixture(); var previous = scan(f.path());
    Files.write(f.path(), Arrays.copyOfRange(f.bytes(), f.half(), f.bytes().length), StandardOpenOption.APPEND);
    var resumed = resume(previous, f.path());
    equalScan(scan(f.path()), resumed);
    assertEquals(f.half(), resumed.scannedFrom());
    assertEquals(List.of("/x", "/late"), new ArrayList<>(resumed.entries().keySet()));
    assertEquals("updated", resumed.entries().get("/x").metadata());
    assertEquals(1, previous.dataRecords()); assertEquals("original", previous.entries().get("/x").metadata());
    assertEquals(1, previous.offsets().get("/x").size(), "Appending never mutates a prior reader's index");
  }
  @Test void aMidFlushTailIsNotDamageAndIsRetriedFromItsFirstByte() throws Exception {
    var f = fixture(); var previous = scan(f.path());
    Files.write(f.path(), Arrays.copyOfRange(f.bytes(), f.half(), f.last() + 5), StandardOpenOption.APPEND);
    var partial = resume(previous, f.path());
    assertTrue(partial.truncated()); assertFalse(partial.damaged()); assertEquals(f.last(), partial.resumePoint());
    Files.write(f.path(), Arrays.copyOfRange(f.bytes(), f.last() + 5, f.bytes().length), StandardOpenOption.APPEND);
    var completed = resume(partial, f.path()); equalScan(scan(f.path()), completed);
    assertFalse(completed.truncated()); assertEquals(f.last(), completed.scannedFrom());
  }
  @Test void eitherChangedAnchorRequiresAFreshScan() throws Exception {
    var f = fixture(); var previous = scan(f.path());
    byte[] updated = f.bytes().clone(); updated[12] = 'S'; // Extra-header bytes are part of the anchor.
    Files.write(f.path(), updated);
    assertNotEquals(f.half(), resume(previous, f.path()).scannedFrom());
    Files.write(f.path(), f.bytes());
    var whole = scan(f.path());
    // Rewrite the final data payload in place, then grow: size alone must not nominate a resume.
    updated = f.bytes().clone(); updated[updated.length - 1] ^= 1;
    Files.write(f.path(), updated); Files.write(f.path(), new byte[]{0}, StandardOpenOption.APPEND);
    var fresh = resume(whole, f.path()); assertNotEquals(whole.resumePoint(), fresh.scannedFrom());
  }
  @Test void identityAndSizeMustBothProveAnAppend() {
    var time = java.nio.file.attribute.FileTime.fromMillis(1);
    var previous = new FileSnapshot(10, time, "file-A");
    assertTrue(new FileSnapshot(11, time, "file-A").grewFrom(previous));
    assertFalse(new FileSnapshot(11, time, "file-B").grewFrom(previous));
    assertFalse(new FileSnapshot(11, time, null).grewFrom(previous));
    assertFalse(new FileSnapshot(10, time, "file-A").grewFrom(previous));
  }
  @Test void creationTimeProvesIdentityWhenTheFileKeyIsUnavailable() throws Exception {
    var f = fixture(); var priorScan = scan(f.path());
    var modified = java.nio.file.attribute.FileTime.fromMillis(3000);
    var birth = java.nio.file.attribute.FileTime.fromMillis(1000);
    var laterBirth = java.nio.file.attribute.FileTime.fromMillis(2000);
    var previous = new FileSnapshot(f.half(), modified, null, birth);
    Files.write(f.path(), f.bytes()); // Both saved anchors remain identical.
    var grown = new FileSnapshot(f.bytes().length, modified, null, birth);
    var replaced = new FileSnapshot(f.bytes().length, modified, null, laterBirth);
    assertTrue(grown.grewFrom(previous));
    assertFalse(replaced.grewFrom(previous));
    try (var log = LazyParsedLog.open(f.path(), 1_000_000, grown.grewFrom(previous) ? priorScan : null)) {
      assertEquals(f.half(), log.scan().scannedFrom());
    }
    try (var log = LazyParsedLog.open(f.path(), 1_000_000, replaced.grewFrom(previous) ? priorScan : null)) {
      assertEquals(priorScan.scannedFrom(), log.scan().scannedFrom());
    }
    assertFalse(previous.sameAs(new FileSnapshot(f.half(), modified, null, laterBirth)));
    assertTrue(previous.describeChange(new FileSnapshot(f.half(), modified, null, laterBirth)).contains("replaced"));
  }
  @Test void managerEvictsDecodedPrefixesAndRetiresOnlyAfterUsesEnd() throws Exception {
    var f = fixture(); var manager = new LogManager(); manager.addAllowedDirectory(temp);
    try {
      try (var old = manager.acquire(f.path().toString())) {
        var first = (LazyParsedLog) old.log(); assertEquals(1, first.values().get("/x").size());
        assertNull(manager.reloadNoticeFor("growth-reader", f.path().toString()));
        Files.write(f.path(), Arrays.copyOfRange(f.bytes(), f.half(), f.bytes().length), StandardOpenOption.APPEND);
        assertNotNull(manager.changeDuringCall(f.path().toString(), first, old.snapshot()));
        assertNull(manager.reloadNoticeFor("growth-reader", f.path().toString()), "Retirement is not a second reload");
        try (var next = manager.acquire(f.path().toString())) {
          var second = (LazyParsedLog) next.log(); assertNotSame(first, second);
          assertEquals(f.half(), second.scan().scannedFrom());
          assertEquals(1, manager.reloadNoticeFor("growth-reader", f.path().toString()).generation());
          assertEquals(3, second.values().get("/x").size()); assertEquals(8., second.values().get("/x").get(2).value());
          assertEquals(1, first.values().get("/x").size(), "Old use keeps its prefix and readable mapping");
        }
      }
    } finally { manager.shutdown(); }
  }
  @Test void aReplacementWithTheSameAnchorsStillLoadsAfresh() throws Exception {
    var f = fixture(); var manager = new LogManager(); manager.addAllowedDirectory(temp);
    try {
      var old = (LazyParsedLog) manager.getOrLoad(f.path().toString());
      var original = FileSnapshot.of(f.path());
      // End the mapping before replacement so this probes identity on Windows too.
      old.close(); var replacement = temp.resolve("replacement.wpilog"); Files.write(replacement, f.bytes());
      Files.move(replacement, f.path(), StandardCopyOption.REPLACE_EXISTING);
      // NTFS can tunnel the deleted destination's creation time onto the renamed file.
      // Set the distinct identity afterwards; identical time and anchors cannot prove replacement.
      Files.setAttribute(f.path(), "basic:creationTime", java.nio.file.attribute.FileTime.fromMillis(123456));
      assertFalse(FileSnapshot.of(f.path()).grewFrom(original), "The replacement fixture must have a distinct identity");
      var loaded = (LazyParsedLog) manager.getOrLoad(f.path().toString());
      assertEquals(old.scan().scannedFrom(), loaded.scan().scannedFrom()); assertEquals(3, loaded.sampleCount("/x"));
    } finally { manager.shutdown(); }
  }
  @Test void aToolDiscardsTheCrossingCallAndNamesTheNewFileSizeOnRetry() throws Exception {
    var f = fixture(); var manager = new LogManager(); manager.addAllowedDirectory(temp);
    try {
      var args = new com.google.gson.JsonObject(); args.addProperty("path", f.path().toString());
      var grow = new CountTool(manager, () -> Files.write(f.path(), Arrays.copyOfRange(f.bytes(), f.half(), f.bytes().length), StandardOpenOption.APPEND));
      var crossed = grow.execute(args).getAsJsonObject();
      assertEquals("error", crossed.get("status").getAsString()); assertTrue(crossed.get("error").getAsString().contains("changed on disk"));
      var next = new CountTool(manager, () -> {}).execute(args).getAsJsonObject();
      assertEquals(3, next.get("count").getAsInt());
      assertEquals(f.bytes().length, next.getAsJsonObject("inputs").get("file_size_bytes").getAsLong());
    } finally { manager.shutdown(); }
  }
  private static class CountTool extends org.triplehelix.wpilogmcp.tools.LogRequiringTool {
    interface Step { void run() throws Exception; }
    private final Step append;
    CountTool(LogManager manager, Step append) { super(new org.triplehelix.wpilogmcp.tools.ToolDependencies(manager, null, null, null)); this.append = append; }
    public String name() { return "count_rescan"; }
    public String description() { return "Count synthetic samples"; }
    protected com.google.gson.JsonObject toolSchema() { return new org.triplehelix.wpilogmcp.mcp.ToolRegistry.SchemaBuilder().build(); }
    protected com.google.gson.JsonElement executeWithLog(LogData log, com.google.gson.JsonObject args) throws Exception {
      int count = log.values().get("/x").size(); append.run(); return success().addProperty("count", count).build();
    }
  }
}
