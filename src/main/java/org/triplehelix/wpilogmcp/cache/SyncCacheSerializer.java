package org.triplehelix.wpilogmcp.cache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.revlog.ParsedRevLog;
import org.triplehelix.wpilogmcp.revlog.RevLogDevice;
import org.triplehelix.wpilogmcp.revlog.RevLogSignal;
import org.triplehelix.wpilogmcp.sync.ConfidenceLevel;
import org.triplehelix.wpilogmcp.sync.SignalPairResult;
import org.triplehelix.wpilogmcp.sync.SyncMethod;
import org.triplehelix.wpilogmcp.sync.SyncResult;

/**
 * Serializes and deserializes {@link ParsedRevLog} + {@link SyncResult} pairs
 * to/from MessagePack binary format for disk caching.
 *
 * @since 0.8.0
 */
public class SyncCacheSerializer {
  private static final Logger logger = LoggerFactory.getLogger(SyncCacheSerializer.class);

  /** Current sync cache format version. Increment on any serialization change. */
  public static final int CURRENT_FORMAT_VERSION = 1;

  /** Container for a cached sync entry. */
  public record CachedSyncEntry(
      ParsedRevLog revlog,
      SyncResult syncResult,
      String wpilogFingerprint,
      String revlogFingerprint,
      long createdAt) {}

  public void write(CachedSyncEntry entry, Path cacheFile) throws IOException {
    var crc = new CRC32();
    try (OutputStream rawOut = new BufferedOutputStream(Files.newOutputStream(cacheFile))) {
      var checkedOut = new CheckedOutputStream(rawOut, crc) {
        @Override public void close() throws IOException { flush(); }
      };
      var packer = MessagePack.newDefaultPacker(checkedOut);
      try {
        packer.packInt(CURRENT_FORMAT_VERSION);
        packer.packString(entry.wpilogFingerprint());
        packer.packString(entry.revlogFingerprint());
        packer.packLong(entry.createdAt());
        packRevLog(packer, entry.revlog());
        packSyncResult(packer, entry.syncResult());
        packer.flush();
      } finally {
        packer.close();
      }
      long crcValue = crc.getValue();
      rawOut.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
          .putInt((int) crcValue).array());
    }
  }

  public CachedSyncEntry read(Path cacheFile) {
    try {
      byte[] allBytes = Files.readAllBytes(cacheFile);
      if (allBytes.length < 4) return null;
      var crc = new CRC32();
      crc.update(allBytes, 0, allBytes.length - 4);
      int storedCrc = ByteBuffer.wrap(allBytes, allBytes.length - 4, 4)
          .order(ByteOrder.LITTLE_ENDIAN).getInt();
      if ((int) crc.getValue() != storedCrc) {
        logger.warn("Sync cache CRC mismatch: {}", cacheFile.getFileName());
        return null;
      }
      try (var unpacker = MessagePack.newDefaultUnpacker(allBytes, 0, allBytes.length - 4)) {
        int formatVersion = unpacker.unpackInt();
        if (formatVersion != CURRENT_FORMAT_VERSION) return null;
        String wpilogFp = unpacker.unpackString();
        String revlogFp = unpacker.unpackString();
        long createdAt = unpacker.unpackLong();
        ParsedRevLog revlog = unpackRevLog(unpacker);
        SyncResult syncResult = unpackSyncResult(unpacker);
        return new CachedSyncEntry(revlog, syncResult, wpilogFp, revlogFp, createdAt);
      }
    } catch (Exception e) {
      logger.debug("Failed to read sync cache {}: {}", cacheFile.getFileName(), e.getMessage());
      return null;
    }
  }

  public int readFormatVersion(Path cacheFile) {
    try (var in = new BufferedInputStream(Files.newInputStream(cacheFile));
         var unpacker = MessagePack.newDefaultUnpacker(in)) {
      return unpacker.unpackInt();
    } catch (Exception e) {
      return -1;
    }
  }

  private void packRevLog(MessagePacker p, ParsedRevLog revlog) throws IOException {
    packNullableString(p, revlog.path());
    packNullableString(p, revlog.filenameTimestamp());
    p.packDouble(revlog.minTimestamp());
    p.packDouble(revlog.maxTimestamp());
    p.packLong(revlog.recordCount());
    p.packInt(revlog.devices().size());
    for (var entry : revlog.devices().entrySet()) {
      p.packInt(entry.getKey());
      var dev = entry.getValue();
      p.packInt(dev.canId());
      p.packString(dev.deviceType());
      packNullableString(p, dev.firmwareVersion());
    }
    p.packInt(revlog.signals().size());
    for (var entry : revlog.signals().entrySet()) {
      p.packString(entry.getKey());
      var sig = entry.getValue();
      p.packString(sig.name());
      p.packString(sig.deviceKey());
      packNullableString(p, sig.unit());
      p.packInt(sig.values().size());
      for (var tv : sig.values()) {
        p.packDouble(tv.timestamp());
        if (tv.value() instanceof Number n) {
          p.packDouble(n.doubleValue());
        } else {
          p.packDouble(0.0);
        }
      }
    }
  }

  private ParsedRevLog unpackRevLog(MessageUnpacker u) throws IOException {
    String path = unpackNullableString(u);
    String filenameTimestamp = unpackNullableString(u);
    double minTs = u.unpackDouble();
    double maxTs = u.unpackDouble();
    long recordCount = u.unpackLong();
    int deviceCount = u.unpackInt();
    Map<Integer, RevLogDevice> devices = new HashMap<>();
    for (int i = 0; i < deviceCount; i++) {
      int key = u.unpackInt();
      int canId = u.unpackInt();
      String deviceType = u.unpackString();
      String firmware = unpackNullableString(u);
      devices.put(key, new RevLogDevice(canId, deviceType, firmware));
    }
    int signalCount = u.unpackInt();
    Map<String, RevLogSignal> signals = new HashMap<>();
    for (int i = 0; i < signalCount; i++) {
      String sigKey = u.unpackString();
      String name = u.unpackString();
      String deviceKey = u.unpackString();
      String unit = unpackNullableString(u);
      int valueCount = u.unpackInt();
      List<TimestampedValue> values = new ArrayList<>(valueCount);
      for (int j = 0; j < valueCount; j++) {
        double ts = u.unpackDouble();
        double val = u.unpackDouble();
        values.add(new TimestampedValue(ts, val));
      }
      signals.put(sigKey, new RevLogSignal(name, deviceKey, values, unit));
    }
    return new ParsedRevLog(path, filenameTimestamp, devices, signals, minTs, maxTs, recordCount);
  }

  private void packSyncResult(MessagePacker p, SyncResult r) throws IOException {
    p.packLong(r.offsetMicros());
    p.packDouble(r.confidence());
    p.packString(r.confidenceLevel().name());
    p.packString(r.method().name());
    packNullableString(p, r.explanation());
    p.packDouble(r.driftRateNanosPerSec());
    p.packDouble(r.referenceTimeSec());
    p.packInt(r.signalPairs().size());
    for (var pair : r.signalPairs()) {
      p.packString(pair.wpilogEntry());
      p.packString(pair.revlogSignal());
      p.packLong(pair.estimatedOffsetMicros());
      p.packDouble(pair.correlation());
      p.packInt(pair.samplesUsed());
    }
  }

  private SyncResult unpackSyncResult(MessageUnpacker u) throws IOException {
    long offsetMicros = u.unpackLong();
    double confidence = u.unpackDouble();
    ConfidenceLevel level = ConfidenceLevel.valueOf(u.unpackString());
    SyncMethod method = SyncMethod.valueOf(u.unpackString());
    String explanation = unpackNullableString(u);
    double driftRate = u.unpackDouble();
    double refTime = u.unpackDouble();
    int pairCount = u.unpackInt();
    List<SignalPairResult> pairs = new ArrayList<>(pairCount);
    for (int i = 0; i < pairCount; i++) {
      pairs.add(new SignalPairResult(
          u.unpackString(), u.unpackString(),
          u.unpackLong(), u.unpackDouble(), u.unpackInt()));
    }
    return new SyncResult(offsetMicros, confidence, level, pairs, method,
        explanation, driftRate, refTime);
  }

  private void packNullableString(MessagePacker p, String value) throws IOException {
    if (value == null) { p.packNil(); } else { p.packString(value); }
  }

  private String unpackNullableString(MessageUnpacker u) throws IOException {
    if (u.tryUnpackNil()) { return null; }
    return u.unpackString();
  }
}
