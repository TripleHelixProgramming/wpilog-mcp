/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.sync;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileTransferTest {
  record Read(String name, long offset, int count) {}
  static String hash(byte[] bytes, long length) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(bytes, (int) length))); }
    catch (Exception e) { throw new AssertionError(e); }
  }
  static byte[] bytes(int size, int seed) {
    var bytes = new byte[size]; var random = new java.util.Random(seed); random.nextBytes(bytes); return bytes;
  }
  static class Fake implements FileTransfer.Local {
    final Map<String, byte[]> remote = new LinkedHashMap<>(), local = new LinkedHashMap<>();
    final Map<String, Long> times = new HashMap<>(); final List<Read> reads = new ArrayList<>();
    final List<Long> hashes = new ArrayList<>(); final AtomicLong now = new AtomicLong();
    final AtomicBoolean gate = new AtomicBoolean(true);
    PullManifest saved = PullManifest.empty("SYNTHETIC-A"); boolean exec = true;
    int unique, invalid, verifies, listings; Runnable reading = () -> {};
    // The remote and local ports intentionally have different adapters for read/hash.
    RemoteFiles transport() { return new RemoteFiles() {
      public List<File> list() { return Fake.this.list(); }
      public byte[] read(String name, long offset, int count) { reads.add(new Read(name, offset, count)); reading.run(); return slice(remote.get(name), offset, count); }
      public Optional<String> prefixHash(String name, long length) { hashes.add(length); return exec ? Optional.of(hash(remote.get(name), length)) : Optional.empty(); }
    }; }
    void put(String name, byte[] content, long time) { remote.put(name, content); times.put(name, time); }
    public List<RemoteFiles.File> list() { listings++; return remote.entrySet().stream().map(e -> new RemoteFiles.File(e.getKey(), e.getValue().length, times.get(e.getKey()))).toList(); }
    static byte[] slice(byte[] bytes, long offset, int count) { return Arrays.copyOfRange(bytes, (int) offset, Math.min(bytes.length, (int) offset + count)); }
    public String create(String remoteName) { String name = remoteName + "#" + ++unique; local.put(name, new byte[0]); return name; }
    public long size(String name) { return local.get(name).length; }
    public byte[] read(String name, long offset, int count) { return slice(local.get(name), offset, count); }
    public String prefixHash(String name, long length) { return hash(local.get(name), length); }
    public void append(String name, long offset, byte[] bytes) {
      assertEquals(local.get(name).length, offset); byte[] grown = Arrays.copyOf(local.get(name), (int) offset + bytes.length);
      System.arraycopy(bytes, 0, grown, (int) offset, bytes.length); local.put(name, grown);
    }
    public String rename(String name, String remoteName) { String next = remoteName + "#" + ++unique; local.put(next, local.remove(name)); return next; }
    public String archive(String name) { String next = name + ".previous-" + ++unique; local.put(next, local.remove(name)); return next; }
    public void verify(String name) throws IOException { verifies++; if (invalid-- > 0) throw new IOException("fixture scan did not reach EOF"); }
    public String verified(PullManifest.Entry entry) { return entry.localName(); }
    public void save(PullManifest manifest) {
      var gson = new com.google.gson.GsonBuilder().setFieldNamingPolicy(com.google.gson.FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();
      String json = gson.toJson(manifest); assertTrue(json.contains("bytes_copied"));
      saved = gson.fromJson(json, PullManifest.class); assertEquals(manifest, saved);
    }
    FileTransfer engine() { return new FileTransfer(transport(), this, saved, 1_000_000, now::get, gate::get); }
    FileTransfer.Result step(FileTransfer engine) throws IOException {
      var result = engine.step(); now.addAndGet(Math.max(1, result.waitUs())); return result;
    }
    void finish(FileTransfer engine) throws IOException {
      for (int i = 0; i < 100; i++) { var result = step(engine); if (result.status() == FileTransfer.Status.IDLE) return; }
      fail("did not settle");
    }
  }

  @Test void oneListingServesTheWholePassAndGrowthRefreshesAfterTenSeconds() throws Exception {
    var f = new Fake(); f.put("a", bytes(4 * 65536, 1), 1); f.put("b", bytes(2 * 65536, 2), 1);
    var engine = f.engine();
    for (int block = 0; block < 6; block++) {
      assertEquals(FileTransfer.Status.COPIED, f.step(engine).status());
      assertEquals(1, f.listings, "one recursive listing for both files, not per block");
    }
    f.finish(engine);
    assertEquals(2, f.listings, "a second pass confirms stable size and mtime before verification");
    assertTrue(f.saved.files().stream().allMatch(PullManifest.Entry::verified));
    byte[] growing = bytes(100 * 65536, 3); f.put("a", growing, 2);
    f.step(engine); assertEquals(3, f.listings);
    for (int block = 0; block < 15; block++) f.step(engine);
    assertEquals(3, f.listings);
    f.put("a", Arrays.copyOf(growing, growing.length + 100), 3);
    f.now.addAndGet(10_000_000); f.step(engine);
    assertEquals(4, f.listings, "long running passes refresh at ten seconds");
    assertEquals(3, f.saved.files().get(0).mtimeMillis());
  }

  @Test void newGrowthUsesTheWholeHeldPrefixAndRoundTripsProgress() throws Exception {
    var f = new Fake(); byte[] all = bytes(150_000, 1); f.put("log.wpilog", Arrays.copyOf(all, 90_000), 1);
    var engine = f.engine(); f.finish(engine); assertTrue(f.saved.files().get(0).verified());
    assertArrayEquals(Arrays.copyOf(all, 90_000), f.local.get(f.saved.files().get(0).localName()));
    f.reads.clear(); f.hashes.clear(); f.put("log.wpilog", all, 2);
    engine = f.engine(); f.finish(engine);
    assertEquals(90_000, f.reads.get(0).offset()); assertEquals(90_000L, f.hashes.get(0));
    assertArrayEquals(all, f.local.get(f.saved.files().get(0).localName()));
    assertEquals(150_000, f.saved.files().get(0).bytesCopied()); assertEquals(2, f.saved.files().get(0).mtimeMillis());
    assertEquals("SYNTHETIC-A", f.saved.serialNumber()); assertTrue(f.saved.history().isEmpty());
  }

  @Test void waitingCountIncludesAChangedRefusalFromTheLastListingWithoutAnotherRemoteCall() throws Exception {
    var f = new Fake(); f.put("z", bytes(10, 1), 1); f.invalid = 2;
    var engine = f.engine(); f.finish(engine);
    assertNotNull(f.saved.files().get(0).failure()); assertEquals(0, engine.pendingFiles());
    f.put("a", bytes(2 * 65536, 2), 1); f.put("z", bytes(20, 3), 2);
    assertEquals(FileTransfer.Status.COPIED, f.step(engine).status());
    // The new listing knows both generations before the sorted pass reaches z.
    assertEquals(2, engine.pendingFiles());
    int listings = f.listings; f.gate.set(false);
    assertEquals(FileTransfer.Status.PAUSED, f.step(engine).status());
    assertEquals(2, engine.pendingFiles()); assertEquals(listings, f.listings);
    f.gate.set(true); f.finish(engine);
    assertTrue(f.saved.files().stream().allMatch(PullManifest.Entry::verified));
    assertEquals(0, engine.pendingFiles());
  }

  @Test void aGrowingFileDuringTheSameTransferRechecksEveryHeldByte() throws Exception {
    var f = new Fake(); byte[] first = bytes(150_000, 21); f.put("log.wpilog", first, 1);
    var engine = f.engine(); f.step(engine); assertEquals(65536, f.saved.files().get(0).bytesCopied());
    byte[] next = Arrays.copyOf(first, 160_000); next[65535] ^= 1; f.put("log.wpilog", next, 2); f.now.addAndGet(10_000_000);
    f.step(engine);
    assertEquals(1, f.saved.history().size(), "A changed listing invalidates the earlier prefix proof");
    assertEquals(0, f.reads.get(1).offset()); assertEquals(65536L, f.hashes.get(0));
    assertArrayEquals(Arrays.copyOf(first, 65536), f.local.get(f.saved.history().get(0).localName()));
    f.finish(engine); assertArrayEquals(next, f.local.get(f.saved.files().get(0).localName()));
  }

  @Test void aNewContactRechecksVerifiedContentEvenWhenSizeAndMtimeRepeat() throws Exception {
    var f = new Fake(); byte[] first = bytes(100_000, 31); f.put("REV_19700101.revlog", first, 0); f.finish(f.engine());
    byte[] next = first.clone(); next[99_999] ^= 1; f.put("REV_19700101.revlog", next, 0);
    f.finish(f.engine()); assertEquals(1, f.saved.history().size());
    assertArrayEquals(first, f.local.get(f.saved.history().get(0).localName()));
    assertArrayEquals(next, f.local.get(f.saved.files().get(0).localName()));
  }

  @ParameterizedTest @ValueSource(strings = {"shrink", "rewind", "common-prefix", "unset-clock"})
  void reusedNamesNeverConcatenateBoots(String change) throws Exception {
    var f = new Fake(); byte[] old = bytes(100_000, 2); String name = change.equals("unset-clock") ? "REV_19700101_000000.revlog" : "FRC_TBD.wpilog";
    f.put(name, old, 10); var engine = f.engine(); f.finish(engine);
    byte[] next = bytes(change.equals("shrink") ? 40_000 : 160_000, 3);
    if (change.equals("rewind")) System.arraycopy(old, 0, next, 0, old.length);
    if (change.equals("common-prefix") || change.equals("unset-clock")) System.arraycopy(old, 0, next, 0, 70_000);
    f.put(name, next, change.equals("rewind") ? 0 : 10); f.reads.clear(); f.finish(engine);
    assertEquals(0, f.reads.get(0).offset()); assertEquals(1, f.saved.history().size());
    assertTrue(f.saved.history().get(0).localName().contains("previous"));
    assertArrayEquals(old, f.local.get(f.saved.history().get(0).localName()));
    assertArrayEquals(next, f.local.get(f.saved.files().get(0).localName()));
    assertArrayEquals(next, f.remote.get(name));
  }

  @Test void fallbackChecksTheTailOfTheHeldRangeAndPacesThatRead() throws Exception {
    var f = new Fake(); byte[] all = bytes(190_000, 4); f.put("log", Arrays.copyOf(all, 100_000), 0);
    var engine = f.engine(); f.finish(engine); f.exec = false; f.reads.clear(); f.put("log", all, 1);
    f.finish(f.engine());
    assertEquals(new Read("log", 100_000 - 65536, 65536), f.reads.get(0));
    assertEquals(100_000, f.reads.get(1).offset());
    assertArrayEquals(all, f.local.get(f.saved.files().get(0).localName()));
    all[189_999] ^= 1; f.put("log", Arrays.copyOf(all, 200_000), 2); f.finish(f.engine());
    assertEquals(1, f.saved.history().size());
  }

  @ParameterizedTest @ValueSource(booleans = {true, false})
  void renamedGrowingLogKeepsThePartialCopy(boolean exec) throws Exception {
    var f = new Fake(); f.exec = exec; byte[] all = bytes(140_000, 8); f.put("FRC_TBD.wpilog", all, 1);
    var engine = f.engine(); f.step(engine); // one held block, then DataLogManager renames it
    f.remote.remove("FRC_TBD.wpilog"); f.put("FRC_20260307_142233_Event_Q1.wpilog", all, 2); f.reads.clear(); f.now.addAndGet(10_000_000);
    f.finish(engine); assertEquals(1, f.local.size()); assertEquals(1, f.saved.files().size());
    assertEquals("FRC_20260307_142233_Event_Q1.wpilog", f.saved.files().get(0).remoteName());
    assertTrue(f.saved.files().get(0).localName().startsWith("FRC_20260307"));
    assertEquals(65536, f.reads.get(exec ? 0 : 1).offset());
    assertArrayEquals(all, f.local.get(f.saved.files().get(0).localName()));
  }

  @Test void paceAndGateFinishOneBlockThenResumeTheSameOffsetWithoutSleeping() throws Exception {
    var f = new Fake(); f.put("a", bytes(200_000, 9), 0); f.put("b", bytes(10, 1), 0);
    var engine = new FileTransfer(f.transport(), f, f.saved, 32768, f.now::get, f.gate::get);
    f.gate.set(false); assertEquals(FileTransfer.Status.PAUSED, engine.step().status()); assertTrue(f.reads.isEmpty());
    f.gate.set(true); f.reading = () -> f.gate.set(false);
    assertEquals(65536, engine.step().bytes()); assertEquals(1, f.reads.size());
    assertEquals(FileTransfer.Status.PAUSED, engine.step().status());
    f.gate.set(true); f.reading = () -> {};
    assertEquals(2_000_000, engine.step().waitUs()); assertEquals(FileTransfer.Status.WAITING, engine.step().status());
    f.now.set(1_999_999); assertEquals(1, engine.step().waitUs());
    f.now.incrementAndGet(); assertEquals(65536, engine.step().bytes()); assertEquals(65536, f.reads.get(1).offset());
    assertEquals(List.of("a", "a"), f.reads.stream().map(Read::name).toList());
  }

  @Test void incompleteScanIsFetchedAgainOnceAndNeverMarkedVerified() throws Exception {
    var f = new Fake(); f.put("bad", bytes(123, 1), 0); f.invalid = 2; var engine = f.engine(); f.finish(engine);
    assertEquals(2, f.verifies); assertEquals(2, f.reads.size());
    assertTrue(f.reads.stream().allMatch(r -> r.offset() == 0));
    assertFalse(f.saved.files().get(0).verified()); assertEquals(1, f.saved.files().get(0).retries());
    assertTrue(f.saved.files().get(0).failure().contains("EOF"));
    f.finish(engine); assertEquals(2, f.verifies);
    var succeeds = new Fake(); succeeds.put("retry", bytes(234, 1), 0); succeeds.invalid = 1; succeeds.finish(succeeds.engine());
    assertTrue(succeeds.saved.files().get(0).verified()); assertEquals(2, succeeds.verifies);
  }

  @Test void concurrentStepsAreRefusedWithoutHoldingALockAcrossIO() throws Exception {
    var f = new Fake(); f.put("a", bytes(10, 2), 0); var engine = f.engine();
    f.reading = () -> {
      f.reading = () -> {};
      assertThrows(IllegalStateException.class, engine::step);
    };
    assertEquals(FileTransfer.Status.COPIED, engine.step().status());
  }

  @Test void manifestAndRemoteRefuseImpossibleProgressAndUnsupportedVersions() {
    assertThrows(IllegalArgumentException.class, () -> new RemoteFiles.File("log", -1, 0));
    assertThrows(IllegalArgumentException.class, () -> new PullManifest(2, "SYNTHETIC-A", List.of(), List.of()));
    assertThrows(IllegalArgumentException.class, () -> new PullManifest.Entry("log", 1, 0, 2, false, "copy", 0, null));
    assertThrows(IllegalArgumentException.class, () -> new PullManifest.Entry("log", 2, 0, 1, true, "copy", 0, null));
    var entry = new PullManifest.Entry("log", 1, 0, 1, true, "copy", 0, null);
    assertThrows(IllegalArgumentException.class, () -> new PullManifest(1, "SYNTHETIC-A", List.of(entry, entry), List.of()));
  }

  @Test void emptyFilesWaitForAStableListingBeforeVerification() throws Exception {
    var f = new Fake(); f.put("empty", new byte[0], 0); var engine = f.engine();
    assertNotEquals(FileTransfer.Status.VERIFIED, f.step(engine).status()); assertEquals(0, f.verifies);
    assertEquals(FileTransfer.Status.VERIFIED, f.step(engine).status()); assertEquals(1, f.verifies);
  }

  @Test void guidesDescribeTheBoundedTransferAndItsFallback() throws Exception {
    for (String name : List.of("ARCHITECTURE", "DEVELOPMENT", "PIT_SERVER_PLAN")) {
      String text = java.nio.file.Files.readString(java.nio.file.Path.of("doc", name + ".md"));
      assertTrue(text.contains("64 KiB") || text.contains("64-KiB"), name);
      assertTrue(text.contains("EOF"), name);
    }
    assertEquals(11, org.triplehelix.wpilogmcp.cache.SyncCacheSerializer.CURRENT_FORMAT_VERSION);
  }
}
