/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.sync;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FileTransferBatchTest {
  static final int BLOCK = 65536;
  static class Batched extends FileTransferTest.Fake {
    int appends, saves, checkpoints, opens, closes, truncations, sizeReads;
    boolean crash;
    Throwable checkpointFailure, closeFailure;
    long latencyUs; int maxRead = BLOCK;
    public int checkpointBytes() { return 4 * BLOCK; }
    public void checkpoint(String name, long offset, byte[] bytes, PullManifest next) throws IOException {
      if (checkpointFailure instanceof IOException io) throw io;
      if (checkpointFailure instanceof RuntimeException runtime) throw runtime;
      if (checkpointFailure instanceof Error error) throw error;
      checkpoints++; append(name, offset, bytes);
      if (crash) { crash = false; throw new IOException("crash after append before manifest"); }
      save(next);
    }
    public boolean truncate(String name, long length) {
      truncations++; local.put(name, Arrays.copyOf(local.get(name), (int) length)); return true;
    }
    @Override public long size(String name) { sizeReads++; return super.size(name); }
    @Override public void append(String name, long offset, byte[] bytes) { appends++; super.append(name, offset, bytes); }
    @Override public void save(PullManifest value) { saves++; super.save(value); }
    @Override RemoteFiles transport() {
      var ranged = super.transport();
      return new RemoteFiles() {
        public List<File> list() throws IOException { return ranged.list(); }
        public byte[] read(String name, long offset, int count) throws IOException {
          now.addAndGet(3 * latencyUs); return ranged.read(name, offset, count);
        }
        public Optional<InputStream> open(String name, long offset) {
          opens++; now.addAndGet(latencyUs);
          var stream = new ByteArrayInputStream(remote.get(name), (int) offset, remote.get(name).length - (int) offset);
          return Optional.of(new InputStream() {
            long position = offset;
            public int read() { throw new AssertionError("one-byte SFTP reads are forbidden"); }
            public int read(byte[] out, int start, int count) {
              if (count == 0) return 0;
              now.addAndGet(latencyUs); reading.run();
              int size = stream.read(out, start, Math.min(count, maxRead));
              if (size > 0) { reads.add(new FileTransferTest.Read(name, position, size)); position += size; }
              return size;
            }
            public byte[] readNBytes(int count) {
              byte[] result = new byte[count]; int n = read(result, 0, count);
              return n < 0 ? new byte[0] : Arrays.copyOf(result, n);
            }
            public void close() throws IOException {
              closes++; now.addAndGet(latencyUs);
              if (closeFailure instanceof IOException io) throw io;
              if (closeFailure instanceof RuntimeException runtime) throw runtime;
              if (closeFailure instanceof Error error) throw error;
            }
          });
        }
        public Optional<String> prefixHash(String name, long length) throws IOException { return ranged.prefixHash(name, length); }
      };
    }
    void input(int blocks) { put("robot.revlog", FileTransferTest.bytes(blocks * BLOCK, 42), 0); }
  }

  @Test void sequentialBlocksShareOneHandleAndOneCheckpoint() throws Exception {
    var f = new Batched(); f.input(4); var transfer = f.engine();
    f.step(transfer);
    assertEquals(0, f.saved.files().get(0).bytesCopied(), "manifest only counts a durable checkpoint");
    assertEquals(0, f.appends, "small blocks are coalesced before queue admission");
    for (int i = 1; i < 4; i++) f.step(transfer);
    assertAll(() -> assertEquals(1, f.opens), () -> assertEquals(1, f.closes),
        () -> assertEquals(1, f.appends), () -> assertEquals(1, f.checkpoints), () -> assertEquals(1, f.sizeReads),
        () -> assertEquals(4 * BLOCK, f.saved.files().get(0).bytesCopied()));
    f.finish(transfer);
    assertArrayEquals(f.remote.get("robot.revlog"), f.local.get(f.saved.files().get(0).localName()));
  }

  @Test void appendThenCrashResumesAtDurablePrefixWithoutArchivingOrStartingOver() throws Exception {
    var f = new Batched(); f.input(12); var transfer = f.engine();
    for (int i = 0; i < 4; i++) f.step(transfer);
    var durable = f.saved; assertEquals(4 * BLOCK, durable.files().get(0).bytesCopied());
    f.crash = true;
    for (int i = 0; i < 3; i++) f.step(transfer);
    assertEquals("crash after append before manifest", assertThrows(IOException.class, () -> f.step(transfer)).getMessage());
    assertEquals(8 * BLOCK, f.local.get(durable.files().get(0).localName()).length);
    assertEquals(durable, f.saved, "failed checkpoint never claims newly appended bytes");
    f.reads.clear(); f.finish(f.engine());
    assertEquals(4 * BLOCK, f.reads.get(0).offset());
    assertEquals(1, f.truncations); assertTrue(f.saved.history().isEmpty()); assertEquals(1, f.unique);
    assertArrayEquals(f.remote.get("robot.revlog"), f.local.get(f.saved.files().get(0).localName()));
  }

  @Test void anUncountedTailOnDiskIsTrimmedToTheManifestBeforeResume() throws Exception {
    var f = new Batched(); f.input(4); String name = f.create("robot.revlog");
    f.append(name, 0, Arrays.copyOf(f.remote.get("robot.revlog"), 2 * BLOCK));
    f.save(new PullManifest(1, "SYNTHETIC-A", List.of(new PullManifest.Entry("robot.revlog", 4 * BLOCK, 0, BLOCK, false, name, 0, null)), List.of()));
    f.finish(f.engine());
    assertEquals(BLOCK, f.reads.get(0).offset()); assertEquals(1, f.truncations);
    assertTrue(f.saved.history().isEmpty()); assertEquals(1, f.unique);
  }

  @Test void pauseCheckpointsBufferedBytesClosesTheHandleAndResumesAtThatOffset() throws Exception {
    var f = new Batched(); f.input(8); var transfer = f.engine(); f.step(transfer);
    f.gate.set(false); assertEquals(FileTransfer.Status.PAUSED, f.step(transfer).status());
    assertEquals(BLOCK, f.saved.files().get(0).bytesCopied()); assertEquals(1, f.closes);
    int reads = f.reads.size(); f.step(transfer); assertEquals(reads, f.reads.size());
    f.gate.set(true); f.step(transfer); assertEquals(BLOCK, f.reads.get(reads).offset()); assertEquals(2, f.opens);
  }

  @Test void aSlowTransferCheckpointsAfterOneSecondWithoutWaitingForTheByteLimit() throws Exception {
    var f = new Batched(); f.input(8);
    var transfer = new FileTransfer(f.transport(), f, f.saved, BLOCK, f.now::get, f.gate::get);
    f.step(transfer); assertEquals(0, f.appends);
    f.step(transfer); assertEquals(1, f.appends); assertEquals(BLOCK, f.saved.files().get(0).bytesCopied());
  }

  @Test void latencyConsumesTheRateDeadlineInsteadOfAddingAnotherFullSleep() throws Exception {
    var f = new Batched(); f.input(16); f.latencyUs = 30_000;
    var transfer = f.engine(); long copied = 0;
    for (int i = 0; i < 16; i++) copied += f.step(transfer).bytes();
    double throughput = copied * 1_000_000.0 / f.now.get();
    System.out.printf("S6 injected RTT=30ms copied=%d elapsed_us=%d bytes_per_sec=%.1f opens=%d checkpoints=%d%n", copied, f.now.get(), throughput, f.opens, f.checkpoints);
    assertEquals(16L * BLOCK, copied);
    assertTrue(throughput >= 900_000, "configured 1 MB/s should limit the copy: " + throughput);
    assertTrue(throughput <= 1_000_000, "rate must include every copied byte: " + throughput);
  }

  @Test void noReadStartsBeforeThePreviousBlockRateDeadline() throws Exception {
    var f = new Batched(); f.input(8); f.latencyUs = 30_000;
    var transfer = new FileTransfer(f.transport(), f, f.saved, BLOCK, f.now::get, f.gate::get);
    assertEquals(BLOCK, transfer.step().bytes());
    f.now.set(999_999); assertEquals(1, transfer.step().waitUs()); assertEquals(1, f.reads.size());
    f.now.incrementAndGet(); assertEquals(BLOCK, transfer.step().bytes()); assertEquals(2, f.reads.size());
  }

  @Test void readFailureClosesTheHandleAndDiscardsOnlyTheUncheckpointedBuffer() throws Exception {
    var f = new Batched(); f.input(8); var transfer = f.engine(); f.step(transfer);
    f.reading = () -> { throw new IllegalStateException("fixture read failure"); };
    assertThrows(IllegalStateException.class, () -> f.step(transfer));
    assertEquals(1, f.closes); assertEquals(0, f.saved.files().get(0).bytesCopied());
    f.reading = () -> {}; f.finish(transfer);
    assertArrayEquals(f.remote.get("robot.revlog"), f.local.get(f.saved.files().get(0).localName()));
  }
  @Test void routineCloseCheckpointsBeforeClosingTheRemoteHandle() throws Exception {
    var f = new Batched(); f.input(8); var transfer = f.engine(); f.step(transfer);
    assertInstanceOf(AutoCloseable.class, transfer).close();
    assertEquals(BLOCK, f.saved.files().get(0).bytesCopied()); assertEquals(1, f.checkpoints); assertEquals(1, f.closes);
    f.reads.clear(); f.finish(f.engine()); assertEquals(BLOCK, f.reads.get(0).offset());
  }

  @Test void aFailedCloseCheckpointStillClosesTheHandleAndPreservesTheOldCount() throws Exception {
    var f = new Batched(); f.input(8); var transfer = f.engine(); f.step(transfer); f.crash = true;
    var closeable = assertInstanceOf(AutoCloseable.class, transfer);
    assertThrows(IOException.class, closeable::close);
    assertEquals(0, f.saved.files().get(0).bytesCopied()); assertEquals(1, f.closes);
    f.finish(f.engine()); assertEquals(1, f.truncations);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"io", "runtime", "error"})
  void closePreservesCheckpointFailureAndSuppressesRemoteCloseFailure(String kind) throws Exception {
    var f = new Batched(); f.input(8); var transfer = f.engine(); f.step(transfer);
    f.checkpointFailure = switch (kind) {
      case "io" -> new java.nio.file.AccessDeniedException("synthetic staged file");
      case "runtime" -> new IllegalStateException("synthetic checkpoint invariant");
      default -> new AssertionError("synthetic checkpoint error");
    };
    f.closeFailure = new IOException("synthetic remote pipe closed");
    var failure = assertThrows(Throwable.class, transfer::close);
    assertSame(f.checkpointFailure, failure, "remote close must not replace the failed local checkpoint");
    assertArrayEquals(new Throwable[]{f.closeFailure}, failure.getSuppressed());
    assertEquals(0, f.saved.files().get(0).bytesCopied()); assertEquals(1, f.closes);
    f.checkpointFailure = null; transfer.close();
    assertEquals(BLOCK, f.saved.files().get(0).bytesCopied(), "failed close retains uncheckpointed bytes for retry");
    assertEquals(1, f.closes, "an already failed remote handle is not closed twice");
  }

  @Test void closeMarksTheRemoteOriginOnlyAfterTheCheckpointSucceeds() throws Exception {
    var f = new Batched(); f.input(8); var transfer = f.engine(); f.step(transfer);
    f.closeFailure = new IOException("synthetic remote pipe closed");
    var failure = assertThrows(IOException.class, transfer::close);
    assertEquals("RemoteCloseException", failure.getClass().getSimpleName());
    assertSame(f.closeFailure, failure.getCause());
    assertEquals(BLOCK, f.saved.files().get(0).bytesCopied()); assertEquals(1, f.closes);
    transfer.close(); assertEquals(1, f.closes);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"runtime", "error"})
  void closeKeepsUnexpectedRemoteFailureAsItsOriginalType(String kind) throws Exception {
    var f = new Batched(); f.input(8); var transfer = f.engine(); f.step(transfer);
    f.closeFailure = kind.equals("runtime") ? new IllegalStateException("synthetic close invariant") : new AssertionError("synthetic close error");
    assertSame(f.closeFailure, assertThrows(Throwable.class, transfer::close));
    assertEquals(BLOCK, f.saved.files().get(0).bytesCopied()); assertEquals(1, f.closes);
    transfer.close(); assertEquals(1, f.closes);
  }

  @Test void closeNeverWaitsForAnInFlightRemoteRead() throws Exception {
    var f = new Batched(); f.input(8); var transfer = f.engine();
    var closeable = assertInstanceOf(AutoCloseable.class, transfer);
    f.reading = () -> {
      assertTimeoutPreemptively(java.time.Duration.ofMillis(500), closeable::close);
      assertEquals(0, f.closes, "busy close must leave the in-flight step's stream to transport shutdown");
    };
    f.step(transfer); f.reading = () -> {};
    closeable.close(); assertEquals(BLOCK, f.saved.files().get(0).bytesCopied()); assertEquals(1, f.closes);
  }

  @Test void failedCheckpointCanResumeOnTheSameTransferAfterTrimmingUncountedBytes() throws Exception {
    var f = new Batched(); f.input(8); var transfer = f.engine(); f.crash = true;
    for (int i = 0; i < 3; i++) f.step(transfer);
    assertThrows(IOException.class, () -> f.step(transfer));
    f.finish(transfer); assertEquals(1, f.truncations); assertTrue(f.saved.history().isEmpty());
    assertArrayEquals(f.remote.get("robot.revlog"), f.local.get(f.saved.files().get(0).localName()));
  }

  @Test void checkpointLimitsCannotExceedTheDocumentedMemoryBound() {
    for (int limit : List.of(0, BLOCK - 1, 1024 * 1024 + 1)) {
      var f = new Batched() { public int checkpointBytes() { return limit; } };
      assertThrows(IllegalArgumentException.class, f::engine);
    }
  }

  @Test void aRateWaitStillCheckpointsTheBufferedPrefixAfterOneSecond() throws Exception {
    var f = new Batched(); f.input(8);
    var transfer = new FileTransfer(f.transport(), f, f.saved, 1, f.now::get, f.gate::get);
    transfer.step(); assertEquals(0, f.appends);
    f.now.set(1_000_000);
    assertEquals(FileTransfer.Status.WAITING, transfer.step().status());
    assertEquals(1, f.appends); assertEquals(BLOCK, f.saved.files().get(0).bytesCopied()); assertEquals(1, f.reads.size());
  }

  @Test void lateWorkerWakeupsKeepOnlyTheCreditThatFitsInOneBlock() throws Exception {
    var f = new Batched(); f.input(8); f.maxRead = BLOCK / 2;
    var transfer = new FileTransfer(f.transport(), f, f.saved, BLOCK, f.now::get, f.gate::get);
    assertEquals(BLOCK / 2, transfer.step().bytes()); assertEquals(500_000, transfer.step().waitUs());
    f.now.set(700_000);
    assertEquals(300_000, transfer.step().waitUs(), "short packets leave room to retain late-wakeup credit");
    f.now.set(1_000_000); assertEquals(BLOCK / 2, transfer.step().bytes());
    f.now.set(100_000_000); transfer.step(); transfer.step(); int reads = f.reads.size();
    assertEquals(FileTransfer.Status.WAITING, transfer.step().status()); assertEquals(reads, f.reads.size());
    var full = new Batched(); full.input(8);
    var other = new FileTransfer(full.transport(), full, full.saved, BLOCK, full.now::get, full.gate::get);
    other.step(); full.now.set(1_200_000);
    assertEquals(1_000_000, other.step().waitUs(), "a full block cannot retain credit beyond the full bucket");
  }

  @Test void aShortFirstReadDoesNotLimitLaterRequestsToThatPacketSize() throws Exception {
    var f = new Batched(); f.input(8); f.maxRead = 1;
    var transfer = f.engine(); assertEquals(1, transfer.step().bytes());
    f.maxRead = BLOCK; f.now.incrementAndGet();
    assertEquals(BLOCK, transfer.step().bytes(), "a short packet must not pin every later request to one byte");
    assertEquals(BLOCK + 1, f.reads.stream().mapToLong(FileTransferTest.Read::count).sum());
    f.finish(transfer);
    assertArrayEquals(f.remote.get("robot.revlog"), f.local.get(f.saved.files().get(0).localName()));
  }

  @Test void aChangedListingCheckpointsItsBufferedFileBeforeVisitingAnEarlierNewName() throws Exception {
    var f = new Batched(); byte[] first = FileTransferTest.bytes(8 * BLOCK, 42), next = FileTransferTest.bytes(100, 73);
    f.put("b.revlog", first, 0); var transfer = f.engine(); f.step(transfer);
    assertEquals(0, f.saved.files().get(0).bytesCopied());
    f.put("a.revlog", next, 0); f.now.addAndGet(FileTransfer.LISTING_PERIOD_US);
    assertEquals(next.length, f.step(transfer).bytes());
    var held = f.saved.files().stream().filter(e -> e.remoteName().equals("b.revlog")).findFirst().orElseThrow();
    var added = f.saved.files().stream().filter(e -> e.remoteName().equals("a.revlog")).findFirst().orElseThrow();
    assertEquals(BLOCK, held.bytesCopied()); assertArrayEquals(Arrays.copyOf(first, BLOCK), f.local.get(held.localName()));
    assertEquals(next.length, added.bytesCopied()); assertArrayEquals(next, f.local.get(added.localName()));
    f.finish(transfer);
    assertArrayEquals(first, f.local.get(f.saved.files().stream().filter(e -> e.remoteName().equals("b.revlog")).findFirst().orElseThrow().localName()));
  }

  @Test void peerUnlimitedAndNearMaximumRatesDoNotOverflowTheirCreditCeiling() throws Exception {
    for (long rate : new long[] {Long.MAX_VALUE, Long.MAX_VALUE - 1}) {
      var f = new Batched(); f.input(8);
      var transfer = new FileTransfer(f.transport(), f, f.saved, rate, f.now::get, f.gate::get);
      assertEquals(BLOCK, transfer.step().bytes());
      assertEquals(1, transfer.step().waitUs(), "a depleted block needs one microsecond even at an enormous finite rate");
      assertEquals(1, f.reads.size());
      f.now.incrementAndGet();
      assertEquals(BLOCK, transfer.step().bytes());
      assertEquals(2, f.reads.size());
    }
  }

  @Test void cumulativeRateIncludesArbitraryShortPacketsAndRestartsItsBudgetAfterIdle() throws Exception {
    var f = new Batched(); f.input(64); var random = new java.util.Random(26);
    var transfer = new FileTransfer(f.transport(), f, f.saved, BLOCK, f.now::get, f.gate::get);
    var times = new java.util.ArrayList<Long>(); var sizes = new java.util.ArrayList<Long>();
    for (int phase = 0; phase < 2; phase++) {
      if (phase == 1) f.now.addAndGet(100_000_000);
      long start = f.now.get(), copied = 0;
      for (int i = 0; i < 30; i++) {
        f.maxRead = 1 + random.nextInt(BLOCK);
        var result = transfer.step(); copied += result.bytes();
        if (result.bytes() > 0) { times.add(f.now.get()); sizes.add(result.bytes()); }
        for (int startIndex = 0; startIndex < times.size(); startIndex++) {
          long total = 0;
          for (int endIndex = startIndex; endIndex < times.size(); endIndex++) {
            total += sizes.get(endIndex);
            assertTrue(total <= (times.get(endIndex) - times.get(startIndex)) * (double) BLOCK / 1_000_000 + BLOCK,
                "every interval stays within rate plus one block: " + startIndex + " to " + endIndex);
          }
        }
        assertTrue(copied <= (f.now.get() - start) * (double) BLOCK / 1_000_000 + BLOCK,
            "one-block burst bound: phase=" + phase + " bytes=" + copied + " elapsed=" + (f.now.get() - start));
        f.now.addAndGet(Math.max(1, result.waitUs()) + random.nextInt(100_000));
      }
    }
  }

}
