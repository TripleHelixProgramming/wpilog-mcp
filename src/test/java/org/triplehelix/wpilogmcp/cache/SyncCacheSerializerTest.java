/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.cache.SyncCacheSerializer.CachedSyncEntry;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.revlog.ParsedRevLog;
import org.triplehelix.wpilogmcp.revlog.RevLogDevice;
import org.triplehelix.wpilogmcp.revlog.RevLogSignal;
import org.triplehelix.wpilogmcp.sync.ConfidenceLevel;
import org.triplehelix.wpilogmcp.sync.SignalPairResult;
import org.triplehelix.wpilogmcp.sync.SyncMethod;
import org.triplehelix.wpilogmcp.sync.SyncResult;

class SyncCacheSerializerTest {
  @TempDir Path tempDir;
  private final SyncCacheSerializer serializer = new SyncCacheSerializer();

  @Test void validCrcDoesNotAuthorizeUnboundedApplicationCounts() throws Exception {
    for (boolean pairs : List.of(false, true)) {
      var p = org.msgpack.core.MessagePack.newDefaultBufferPacker();
      p.packInt(SyncCacheSerializer.CURRENT_FORMAT_VERSION).packString("a").packString("b").packLong(0);
      p.packNil().packNil().packDouble(0).packDouble(1).packLong(1).packInt(0);
      if (!pairs) {
        p.packInt(1).packString("signal").packString("s").packString("d").packNil().packInt(Integer.MAX_VALUE);
      } else {
        p.packInt(0).packLong(0).packDouble(1).packString("HIGH").packString("CROSS_CORRELATION")
            .packNil().packDouble(0).packDouble(0).packInt(Integer.MAX_VALUE);
      }
      byte[] data = p.toByteArray(); p.close();
      var crc = new java.util.zip.CRC32(); crc.update(data);
      var bytes = java.nio.ByteBuffer.allocate(data.length + 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
          .put(data).putInt((int) crc.getValue()).array();
      var file = tempDir.resolve("huge-count.msgpack"); Files.write(file, bytes);
      try { assertNull(serializer.read(file), "A tiny cache must be rejected before allocation"); }
      catch (OutOfMemoryError failure) { fail("Untrusted cache count reached allocation: " + failure.getMessage()); }
    }
  }

  private ParsedRevLog createTestRevLog() {
    return new ParsedRevLog("/logs/REV_20260321_103045.revlog", "20260321_103045",
        Map.of(1, new RevLogDevice(1, "SPARK MAX", "v1.6.4")),
        Map.of("SparkMax_1/appliedOutput", new RevLogSignal("appliedOutput", "SparkMax_1",
            List.of(new TimestampedValue(0.0, 0.5), new TimestampedValue(0.02, 1.0)), "duty_cycle")),
        0.0, 150.0, 7500);
  }

  private SyncResult createTestSyncResult() {
    return new SyncResult(12345678L, 0.92, ConfidenceLevel.HIGH,
        List.of(new SignalPairResult("/Battery", "SparkMax_1/busVoltage", 12345678L, 0.95, 5000)),
        SyncMethod.CROSS_CORRELATION, "Test sync", 1.5, 75.0);
  }

  @Nested @DisplayName("Round-trip") class RoundTrip {
    @Test @DisplayName("full entry round-trips") void fullRoundTrip() throws IOException {
      var entry = new CachedSyncEntry(createTestRevLog(), createTestSyncResult(), "fp1", "fp2", 123L);
      Path file = tempDir.resolve("test.msgpack");
      serializer.write(entry, file);
      var loaded = serializer.read(file);
      assertNotNull(loaded);
      assertEquals("fp1", loaded.wpilogFingerprint());
      assertEquals(12345678L, loaded.syncResult().offsetMicros());
      assertEquals(1, loaded.revlog().signals().size());
    }

    @Test @DisplayName("devices and signals come back in the order the parser gave them")
    void orderKept() throws IOException {
      // The parser's order is the order the REV log first shows each; the tools list them so,
      // and a result read from the cache must list them the same way
      var devices = new java.util.LinkedHashMap<Integer, RevLogDevice>();
      for (int id : new int[]{17, 3, 12, 40, 1, 25, 8}) {
        devices.put(id, new RevLogDevice(id, "SPARK MAX", "v26.1.5"));
      }
      var signals = new java.util.LinkedHashMap<String, RevLogSignal>();
      for (var key : List.of("SparkMax_17/Velocity", "SparkMax_3/AppliedOutput",
          "SparkMax_12/Current", "SparkMax_40/Position", "SparkMax_1/Temperature",
          "SparkMax_25/BusVoltage", "SparkMax_8/Faults")) {
        var device = key.substring(0, key.indexOf('/'));
        signals.put(key, new RevLogSignal(key.substring(key.indexOf('/') + 1), device,
            List.of(new TimestampedValue(0.0, 1.0)), null));
      }
      var revlog = new ParsedRevLog("/logs/REV_20260321_103045.revlog", "20260321_103045",
          devices, signals, 0.0, 1.0, 14);
      Path file = tempDir.resolve("order.msgpack");
      serializer.write(new CachedSyncEntry(revlog, createTestSyncResult(), "fp1", "fp2", 0L), file);
      var loaded = serializer.read(file).revlog();
      assertEquals(List.copyOf(devices.keySet()), List.copyOf(loaded.devices().keySet()));
      assertEquals(List.copyOf(signals.keySet()), List.copyOf(loaded.signals().keySet()));
    }

    @Test @DisplayName("null fields preserved") void nullFields() throws IOException {
      var revlog = new ParsedRevLog(null, null, Map.of(), Map.of(), 0, 0, 0);
      var entry = new CachedSyncEntry(revlog, SyncResult.failed("x"), "a", "b", 0L);
      Path file = tempDir.resolve("null.msgpack");
      serializer.write(entry, file);
      var loaded = serializer.read(file);
      assertNotNull(loaded);
      assertNull(loaded.revlog().path());
      assertNull(loaded.revlog().filenameTimestamp());
    }
  }

  @Nested @DisplayName("Error handling") class Errors {
    @Test @DisplayName("corrupt file returns null") void corrupt() throws IOException {
      Path file = tempDir.resolve("corrupt.msgpack");
      Files.writeString(file, "not msgpack");
      assertNull(serializer.read(file));
    }

    @Test @DisplayName("tampered CRC returns null") void tamperedCrc() throws IOException {
      var entry = new CachedSyncEntry(createTestRevLog(), createTestSyncResult(), "a", "b", 0L);
      Path file = tempDir.resolve("tampered.msgpack");
      serializer.write(entry, file);
      byte[] bytes = Files.readAllBytes(file);
      bytes[10] ^= 0xFF;
      Files.write(file, bytes);
      assertNull(serializer.read(file));
    }
  }
}
