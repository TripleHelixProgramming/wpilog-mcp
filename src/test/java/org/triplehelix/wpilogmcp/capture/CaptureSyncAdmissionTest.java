/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import edu.wpi.first.util.datalog.DataLogReader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.store.StoreSyncHardeningFixture;

/** A peer read held beyond the admission deadline must not own capture's placement worker. */
class CaptureSyncAdmissionTest {
  @TempDir Path directory;
  private static final CaptureWriter.AdmissionLimits LIMITS =
      new CaptureWriter.AdmissionLimits(1 << 20, 1_000, 200_000, 256, 2_000);
  private static final Announce TOPIC =
      new Announce("/ordered", 1, "int", null, new JsonObject());

  @Test void aStalledSyncCannotConsumeTheTwoHundredMillisecondAdmissionDeadline() throws Exception {
    try (var peer = new StoreSyncHardeningFixture(directory, true)) {
      var syncing = peer.sync();
      peer.awaitPayload();
      var loop = new ManualScheduler();
      var placement = peer.target.captures(StoreSyncHardeningFixture.CLOCK);
      var writer = new CaptureWriter(StoreSyncHardeningFixture.CLOCK, loop, CapturePolicy.ALL,
          placement, CaptureWriter.DEFAULT_MAX_FILE_BYTES, WpilogOutput::new, LIMITS);
      try {
        writer.connected(java.net.URI.create("ws://127.0.0.1:0/nt/synthetic"),
            "networktables.first.wpi.edu");
        writer.timeSync(1_000_000, 0);
        writer.announce(TOPIC);
        for (int i = 0; i < 3; i++) {
          writer.value(TOPIC, new ValueFrame(1, 1_000_100 + i * 20_000, 2, 17L + i),
              i * 20_000);
        }
        advanceAdmissionClock(loop, Duration.ofMillis(350));
        assertNull(writer.admissionFailure(),
            "Sync's held payload must not cause Capture admission exceeded 0.2 seconds");
        assertTrue(writer.ready(),
            "Capture must be admitted before the peer releases its second payload block");
        assertFalse(syncing.isDone(), "The peer remains held after capture admission");
        peer.releasePayload();
        var result = syncing.get(10, TimeUnit.SECONDS);
        assertEquals(List.of(), result.stopped(), result.toString());
        assertEquals(List.of(), result.refusals(), result.toString());
        assertEquals(1, result.filesCopied().size());
        assertEquals(peer.sha256, result.filesCopied().get(0).sha256());
        writer.disconnected();
        loop.drain();
        placement.completion().get(10, TimeUnit.SECONDS);
        var values = new ArrayList<Long>();
        var times = new ArrayList<Long>();
        for (var record : new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(writer.session().path())))) {
          if (record.isControl()) continue;
          values.add(record.getInteger());
          times.add(record.getTimestamp());
        }
        assertEquals(List.of(17L, 18L, 19L), values,
            "All three independently specified values survive deferred admission");
        assertEquals(List.of(1_000_100L, 1_020_100L, 1_040_100L), times,
            "Capture preserves the original robot timestamp for every deferred value");
      } finally {
        peer.releasePayload();
        syncing.get(10, TimeUnit.SECONDS);
        writer.shutdown();
        loop.drain();
        placement.completion().get(10, TimeUnit.SECONDS);
        loop.close();
      }
    }
  }

  /** Real elapsed time advances the listener's 200 ms deadline; latches order the peer read. */
  private static void advanceAdmissionClock(ManualScheduler loop, Duration duration)
      throws InterruptedException {
    long previous = System.nanoTime();
    long deadline = previous + duration.toNanos();
    while (previous < deadline) {
      Thread.sleep(2);
      long now = System.nanoTime();
      loop.advance(Math.max(1, (now - previous) / 1_000));
      previous = now;
    }
  }
}
