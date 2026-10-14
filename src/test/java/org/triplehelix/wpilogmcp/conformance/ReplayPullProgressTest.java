/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.sync.FileTransfer;
import org.triplehelix.wpilogmcp.sync.PullManifest;
import org.triplehelix.wpilogmcp.sync.RemoteFiles;

class ReplayPullProgressTest {
  @Test void tinyPacketsFinishTheWholeRetryBeforeRefusal() throws Exception {
    byte[] source = new byte[128 * 1024 + 13]; new java.util.Random(73).nextBytes(source);
    var local = new Local(); var now = new AtomicLong(); var opens = new java.util.concurrent.atomic.AtomicInteger();
    var remote = new RemoteFiles() {
      public List<File> list() { return List.of(new File("/log.wpilog", source.length, 1)); }
      public byte[] read(String name, long offset, int count) { return Arrays.copyOfRange(source, (int) offset, Math.min(source.length, (int) offset + count)); }
      public Optional<java.io.InputStream> open(String name, long offset) {
        opens.incrementAndGet();
        return Optional.of(new ByteArrayInputStream(source, (int) offset, source.length - (int) offset) {
          @Override public synchronized int read(byte[] bytes, int start, int count) { return super.read(bytes, start, Math.min(17, count)); }
        });
      }
      public Optional<String> prefixHash(String name, long length) { return Optional.of(hash(source, length)); }
    };
    try (var transfer = new FileTransfer(remote, local, PullManifest.empty("SHORT-PACKET"), 1_000_000, now::get, () -> true)) {
      ReplayPullProgress.finish(transfer::step, transfer::manifest, 1, source.length, now, System::nanoTime);
      var held = transfer.manifest().files().get(0);
      assertNotNull(held.failure(), "A partial second copy is not a terminal refusal");
      assertEquals(1, held.retries()); assertEquals(source.length, held.bytesCopied());
      assertArrayEquals(source, local.files.get(held.localName()).toByteArray());
      assertEquals(2, local.verifications); assertEquals(2, opens.get());
      assertEquals(1, transfer.manifest().history().size());
      assertArrayEquals(source, local.files.get(transfer.manifest().history().get(0).localName()).toByteArray());
    }
  }

  @Test void virtualInactivityFailsWithProgressInsteadOfAnIncompleteReport() {
    var now = new AtomicLong(); var calls = new java.util.concurrent.atomic.AtomicInteger();
    var failure = assertThrows(AssertionError.class, () -> ReplayPullProgress.finish(() -> {
      if (calls.incrementAndGet() > 4) throw new IllegalStateException("virtual inactivity check did not fire");
      return new FileTransfer.Result(FileTransfer.Status.WAITING, 10_000_000, null, 0, null);
    }, () -> PullManifest.empty("STALL"), 1, 9, now, () -> 0));
    assertTrue(failure.getMessage().contains("virtual")); assertTrue(failure.getMessage().contains("0/1"));
  }

  @Test void realInactivityFailsWhenTheVirtualClockBarelyMoves() {
    var now = new AtomicLong(); var real = new AtomicLong(); var calls = new java.util.concurrent.atomic.AtomicInteger();
    var failure = assertThrows(AssertionError.class, () -> ReplayPullProgress.finish(() -> {
      if (calls.incrementAndGet() > 4) throw new IllegalStateException("real inactivity check did not fire");
      real.addAndGet(java.util.concurrent.TimeUnit.MINUTES.toNanos(2));
      return new FileTransfer.Result(FileTransfer.Status.WAITING, 0, null, 0, null);
    }, () -> PullManifest.empty("STALL"), 1, 9, now, real::get));
    assertTrue(failure.getMessage().contains("real")); assertTrue(failure.getMessage().contains("0/1"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void slowUsefulWorkResetsBothInactivityDeadlines(boolean checkpoint) throws Exception {
    var now = new AtomicLong(); var real = new AtomicLong(); var count = new java.util.concurrent.atomic.AtomicInteger();
    var progress = new java.util.concurrent.atomic.AtomicReference<>(PullManifest.empty("PROGRESS"));
    ReplayPullProgress.finish(() -> {
      int copied = count.incrementAndGet(); real.addAndGet(java.util.concurrent.TimeUnit.MINUTES.toNanos(6));
      if (checkpoint || copied == 5) progress.set(new PullManifest(1, "PROGRESS", List.of(new PullManifest.Entry("/log", 5, 1, copied, copied == 5, "local", 0, null)), List.of()));
      return new FileTransfer.Result(copied == 5 ? FileTransfer.Status.VERIFIED : FileTransfer.Status.COPIED, 31_000_000, null, checkpoint ? 0 : 1, null);
    }, progress::get, 1, 5, now, real::get);
    assertEquals(5, count.get()); assertTrue(progress.get().files().get(0).verified());
  }

  @Test void everyExpectedFileMustReachATerminalManifest() throws Exception {
    var count = new java.util.concurrent.atomic.AtomicInteger();
    var progress = new java.util.concurrent.atomic.AtomicReference<>(PullManifest.empty("TWO"));
    ReplayPullProgress.finish(() -> {
      int step = count.incrementAndGet();
      var first = new PullManifest.Entry("/first", 1, 1, 1, true, "first", 0, null);
      var second = new PullManifest.Entry("/second", 1, 1, step == 3 ? 1 : 0, step == 3, "second", 0, null);
      progress.set(new PullManifest(1, "TWO", step == 1 ? List.of(first) : List.of(first, second), List.of()));
      return new FileTransfer.Result(FileTransfer.Status.WAITING, 0, null, 0, null);
    }, progress::get, 2, 2, new AtomicLong(), () -> 0);
    assertEquals(3, count.get()); assertEquals(2, progress.get().files().size());
    assertTrue(progress.get().files().stream().allMatch(PullManifest.Entry::verified));
  }

  private static String hash(byte[] bytes, long length) {
    try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(bytes, (int) length))); }
    catch (java.security.NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
  }
  private static final class Local implements FileTransfer.Local {
    final java.util.Map<String, ByteArrayOutputStream> files = new HashMap<>(); int next, verifications;
    public String create(String name) { String local = "copy-" + ++next; files.put(local, new ByteArrayOutputStream()); return local; }
    public long size(String name) { return files.get(name).size(); }
    public byte[] read(String name, long offset, int count) { return Arrays.copyOfRange(files.get(name).toByteArray(), (int) offset, (int) offset + count); }
    public String prefixHash(String name, long length) { return hash(files.get(name).toByteArray(), length); }
    public void append(String name, long offset, byte[] bytes) { assertEquals(files.get(name).size(), offset); files.get(name).writeBytes(bytes); }
    public String rename(String name, String remote) { return name; }
    public String archive(String name) { return name; }
    public void verify(String name) throws IOException { verifications++; throw new IOException("incomplete synthetic source"); }
    public String verified(PullManifest.Entry entry) { fail("The fixture is incomplete"); return null; }
    public void save(PullManifest manifest) {}
  }
}
