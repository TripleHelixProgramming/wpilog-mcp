/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.triplehelix.wpilogmcp.sync.FileTransfer;
import org.triplehelix.wpilogmcp.sync.PullManifest;

/** Completes the production transfer before replay compares its placement and complete bytes. */
final class ReplayPullProgress {
  @FunctionalInterface interface Step { FileTransfer.Result run() throws IOException; }
  static void finish(Step step, Supplier<PullManifest> manifest, int expectedFiles,
      long expectedBytes, AtomicLong virtualUs, LongSupplier realNanos) throws IOException {
    long lastVirtual = virtualUs.get(), lastReal = realNanos.getAsLong();
    var previous = manifest.get();
    while (true) {
      var result = step.run(); virtualUs.addAndGet(Math.max(1, result.waitUs()));
      var held = manifest.get(); long real = realNanos.getAsLong();
      if (held.files().size() == expectedFiles
          && held.files().stream().allMatch(e -> e.verified() || e.failure() != null)) return;
      // Packet size and checkpoint cadence are not completion evidence. A long parse or
      // correlation can make useful manifest progress without returning any copied bytes.
      if (result.bytes() > 0 || !held.equals(previous)) { lastVirtual = virtualUs.get(); lastReal = real; }
      previous = held;
      String stalled = virtualUs.get() - lastVirtual >= 30_000_000 ? "virtual"
          : real - lastReal >= java.util.concurrent.TimeUnit.MINUTES.toNanos(5) ? "real" : null;
      if (stalled != null) throw new AssertionError("Replay pull made no " + stalled + " progress: "
          + held.files().size() + "/" + expectedFiles + " files, "
          + held.files().stream().mapToLong(PullManifest.Entry::bytesCopied).sum() + "/" + expectedBytes
          + " bytes held, last step " + result.status());
    }
  }
}
