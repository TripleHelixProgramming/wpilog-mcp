/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.server;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.log.FileSnapshot;

class VerifiedContentHashesTest {
  @TempDir Path temp;
  static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
  final AtomicInteger reads = new AtomicInteger();
  final AtomicLong clock = new AtomicLong();
  Path file;
  @BeforeEach void start() throws Exception { file = Files.writeString(temp.resolve("member"), "abc"); }
  String hash(Path path) throws IOException {
    reads.incrementAndGet();
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
    catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
  }
  VerifiedContentHashes memory() {
    return new VerifiedContentHashes(2, Duration.ofMinutes(10), clock::get, this::hash);
  }
  void verify(VerifiedContentHashes memory, Path path) throws IOException { memory.verify(path, FileSnapshot.of(path), ABC); }

  @Test void stableVerificationIsReusedWithoutHoldingTheFileOpen() throws Exception {
    try (var memory = memory()) {
      verify(memory, file); verify(memory, file); assertEquals(1, reads.get());
      assertEquals(1, memory.retained()); Files.delete(file);
      assertThrows(IOException.class, () -> verify(memory, file));
    }
  }

  @Test void theExpectedManifestHashIsPartOfTheProof() throws Exception {
    try (var memory = memory()) {
      verify(memory, file);
      for (int retry = 0; retry < 2; retry++) {
        var refusal = assertThrows(IOException.class, () -> memory.verify(file, FileSnapshot.of(file), "0".repeat(64)));
        assertTrue(refusal.getMessage().contains("manifested hash"));
      }
      assertEquals(3, reads.get(), "Failed verification cannot be remembered as success");
      verify(memory, file); assertEquals(3, reads.get()); assertEquals(1, memory.retained());
    }
  }

  @Test void aRenamedPathNeedsItsOwnVerificationEvenWhenItsBoundaryIsUnchanged() throws Exception {
    try (var memory = memory()) {
      verify(memory, file); var before = FileSnapshot.of(file);
      var renamed = Files.move(file, file.resolveSibling("renamed"));
      assertEquals(before, FileSnapshot.of(renamed));
      verify(memory, renamed); assertEquals(2, reads.get());
    }
  }

  @ParameterizedTest @ValueSource(strings = {"size", "modified", "identity"})
  void aChangedBoundaryRequiresAHashEvenWithTheOldManifest(String change) throws Exception {
    try (var memory = memory()) {
      verify(memory, file); var before = FileSnapshot.of(file);
      switch (change) {
        case "size" -> {
          Files.writeString(file, "abcd"); Files.setLastModifiedTime(file, before.modified());
        }
        case "modified" -> {
          Files.writeString(file, "abd"); Files.setLastModifiedTime(file, FileTime.fromMillis(before.modified().toMillis() + 2_000));
        }
        case "identity" -> {
          var replacement = Files.writeString(file.resolveSibling("replacement"), "abd");
          Files.setLastModifiedTime(replacement, before.modified());
          Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);
        }
        default -> throw new AssertionError(change);
      }
      assertNotEquals(before, FileSnapshot.of(file), "Fixture must expose the changed boundary on this filesystem");
      assertTrue(assertThrows(IOException.class, () -> verify(memory, file)).getMessage().contains("manifested hash"));
      assertEquals(2, reads.get());
    }
  }

  @Test void aStaleBoundaryRefusesBeforeHashingOrReusingAHit() throws Exception {
    try (var memory = memory()) {
      var before = FileSnapshot.of(file); verify(memory, file);
      Files.writeString(file, "abcd");
      assertTrue(assertThrows(IOException.class, () -> memory.verify(file, before, ABC)).getMessage().contains("changed"));
      assertEquals(1, reads.get());
    }
    try (var empty = memory()) {
      var before = FileSnapshot.of(file); Files.writeString(file, "abcde");
      assertThrows(IOException.class, () -> empty.verify(file, before, ABC));
      assertEquals(1, reads.get(), "A stale caller boundary must refuse before I/O for a hash");
    }
  }

  @Test void aFileChangingDuringTheHashNeverPublishesSuccess() throws Exception {
    try (var memory = new VerifiedContentHashes(path -> {
      String value = hash(path); Files.writeString(path, "abcd"); return value;
    })) {
      assertTrue(assertThrows(IOException.class, () -> verify(memory, file)).getMessage().contains("changed"));
      assertEquals(0, memory.retained());
      Files.writeString(file, "abc");
      assertThrows(IOException.class, () -> verify(memory, file)); assertEquals(2, reads.get());
      assertEquals(0, memory.retained());
    }
  }

  @Test void failedIoIsRetriedAndMissingProofNeverBecomesVerified() throws Exception {
    var attempts = new AtomicInteger();
    try (var memory = new VerifiedContentHashes(path -> {
      if (attempts.incrementAndGet() == 1) throw new IOException("synthetic disk failure");
      return hash(path);
    })) {
      assertEquals("synthetic disk failure", assertThrows(IOException.class, () -> verify(memory, file)).getMessage());
      assertEquals(0, memory.retained()); verify(memory, file); verify(memory, file);
      assertEquals(2, attempts.get()); assertEquals(1, reads.get());
      assertThrows(IOException.class, () -> memory.verify(file, null, ABC));
      assertThrows(IOException.class, () -> memory.verify(file, FileSnapshot.of(file), null));
      assertEquals(2, attempts.get());
    }
  }

  @Test void idleExpiryRenewsOnUseButEventuallyRequiresVerificationAgain() throws Exception {
    try (var memory = memory()) {
      verify(memory, file);
      clock.addAndGet(Duration.ofMinutes(9).toNanos()); verify(memory, file);
      clock.addAndGet(Duration.ofMinutes(9).toNanos()); verify(memory, file); assertEquals(1, reads.get());
      clock.addAndGet(Duration.ofMinutes(10).toNanos()); verify(memory, file); assertEquals(2, reads.get());
    }
  }

  @Test void theDefaultMemoryIsBoundedAndEvictedProofsAreRechecked() throws Exception {
    try (var memory = new VerifiedContentHashes(this::hash)) {
      var paths = new ArrayList<Path>();
      for (int n = 0; n < 270; n++) {
        var path = Files.writeString(temp.resolve("member-" + n), "abc"); paths.add(path); verify(memory, path);
        assertTrue(memory.retained() <= 256, "The documented default bounds retained proof tuples");
      }
      int before = reads.get(); for (var path : paths) verify(memory, path);
      assertTrue(reads.get() > before, "Eviction trades verification work for bounded memory");
    }
  }

  @Test void slowIoBlocksNeitherOtherHitsNorCloseAndCannotResurrectClosedMemory() throws Exception {
    var blocked = new CountDownLatch(1); var release = new CountDownLatch(1);
    var other = Files.writeString(temp.resolve("other"), "abc"); var pool = Executors.newFixedThreadPool(3);
    var memory = new VerifiedContentHashes(path -> {
      if (path.equals(other)) {
        blocked.countDown();
        try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test release timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
      }
      return hash(path);
    });
    try {
      verify(memory, file);
      var slow = pool.submit(() -> { verify(memory, other); return null; });
      assertTrue(blocked.await(2, TimeUnit.SECONDS));
      pool.submit(() -> { verify(memory, file); return null; }).get(1, TimeUnit.SECONDS);
      pool.submit(memory::close).get(1, TimeUnit.SECONDS); assertEquals(0, memory.retained());
      release.countDown(); slow.get(2, TimeUnit.SECONDS); assertEquals(0, memory.retained());
      assertThrows(IOException.class, () -> verify(memory, file)); assertEquals(2, reads.get());
    } finally { release.countDown(); memory.close(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
  }

  @Test void concurrentAdmissionsKeepTheSameProofAndAllWarmReadsReuseIt() throws Exception {
    var pool = Executors.newFixedThreadPool(8);
    try (var memory = memory()) {
      var calls = new ArrayList<Future<?>>();
      for (int n = 0; n < 32; n++) calls.add(pool.submit(() -> { verify(memory, file); return null; }));
      for (var call : calls) call.get(5, TimeUnit.SECONDS);
      int cold = reads.get(); assertTrue(cold >= 1 && cold <= 8, "Only concurrent cold misses may duplicate hash I/O");
      for (int n = 0; n < 32; n++) verify(memory, file);
      assertEquals(cold, reads.get()); assertEquals(1, memory.retained());
    } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
  }
}
