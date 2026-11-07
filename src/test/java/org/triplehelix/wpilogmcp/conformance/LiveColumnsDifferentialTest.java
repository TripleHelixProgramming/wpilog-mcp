/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.WpilogOutput;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LiveLog;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.OpenColumns;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.read.FieldPath;

/**
 * An open capture answers from its columns as an independent read of the same bytes does: at
 * sampled publication boundaries, every entry's records, their positions, times and payload sizes,
 * their decoded values, and a number's samples, bit for bit, with the columns' blocks in a file
 * and in the heap, and recent values hot or read from the WPILOG.
 */
class LiveColumnsDifferentialTest {
  @TempDir Path directory;

  private static final Map<String, Integer> CODES = Map.of("double", 1, "float", 3, "int64", 2, "boolean", 0,
      "string", 4, "raw", 5, "double[]", 17);

  private record Written(String name, EntryInfo info, int code) {}

  @ParameterizedTest(name = "blocks in a file: {0}, hot window {1} us")
  @CsvSource({"true, 0", "false, 0", "true, 600000000", "false, 600000000"})
  void everyPrefixAnswersAsAnIndependentReadOfItsBytes(boolean inFile, long hotWindowUs) throws Exception {
    var path = directory.resolve("capture.wpilog");
    var columnsFile = directory.resolve("columns-open.col");
    OpenColumns.Placement placement = inFile ? new OpenColumns.Placement() {
      @Override public Optional<Path> file(String captureIdentity) { return Optional.of(columnsFile); }
      @Override public boolean reserveShort() { return false; }
    } : OpenColumns.Placement.HEAP;
    var manager = org.triplehelix.wpilogmcp.log.IsolatedLogManagers.create(directory.toRealPath(), "live-columns");
    var out = new WpilogOutput(path);
    var live = new LiveLog(path, hotWindowUs, () -> null, UUID.randomUUID().toString(), placement);
    manager.beginCapture(live);
    var random = new Random(11);
    int compared = 0;
    try (out) {
      var entries = new LinkedHashMap<String, Written>();
      for (var type : List.of("double", "float", "int64", "boolean", "string", "raw", "double[]")) {
        var name = "/" + type;
        var info = new EntryInfo(out.start(name, type, "", 0), name, type, "");
        live.announce(info);
        entries.put(name, new Written(name, info, CODES.get(type)));
      }
      // A number whose record holds an integer's bytes in an entry declared double, and one whose
      // payload is malformed: both are read from the WPILOG, as a reader of the file reads them.
      var odd = new EntryInfo(out.start("/odd", "double", "", 0), "/odd", "double", "");
      live.announce(odd);
      long time = 1_000_000;
      for (int step = 1; step <= 2600; step++) {
        time += 20_000 + (step % 211 == 0 ? -70_000 : 0);
        for (var entry : entries.values()) {
          // Strings and raw values arrive less often than numbers.
          if ((entry.code() == 4 || entry.code() == 5 || entry.code() == 17) && step % 3 != 0) continue;
          Object value = switch (entry.info().type()) {
            case "double" -> step % 97 == 0 ? Double.longBitsToDouble(0x7ff8_0000_0000_0123L) : random.nextGaussian();
            case "float" -> (float) random.nextGaussian();
            case "int64" -> (1L << 53) + step * (step % 2 == 0 ? 1L : -1L);
            case "boolean" -> step % 5 == 0;
            case "string" -> "text " + step;
            case "raw" -> new byte[] {(byte) step, (byte) (step >> 8), 7};
            case "double[]" -> List.of(step * 0.5, -step * 0.25);
            default -> throw new AssertionError();
          };
          var written = out.append(entry.info().id(), time, WpilogOutput.payload(entry.code(), value));
          live.append(entry.info(), new ValueFrame(entry.info().id(), time, entry.code(), value), written);
        }
        if (step % 50 == 0) {
          var bytes = step % 100 == 0 ? new byte[] {1, 2} : ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(step).array();
          var written = out.append(odd.id(), time, bytes);
          live.append(odd, new ValueFrame(odd.id(), time, 2, (long) step), written);
        }
        live.advance(time + 1);
        if (step % 13 == 0) live.expire();
        if (step == 1 || step == 7 || step % 433 == 0 || step == 2600) {
          // With no hot window, expiring first leaves every record of the prefix cold.
          if (hotWindowUs == 0) live.expire();
          compared += compare(manager, path, step, hotWindowUs == 0);
        }
      }
    } finally {
      live.finish(); manager.shutdown(); live.discard();
    }
    assertTrue(compared > 20_000, "records compared: " + compared);
    assertTrue(!Files.exists(columnsFile), "a discarded index deletes its columns' file");
  }

  /** One prefix: every record of every entry against an independent read of a copy of the bytes. */
  private int compare(LogManager manager, Path path, int step, boolean coldPrefix) throws Exception {
    var copy = directory.resolve("prefix-" + step + ".wpilog");
    // Read through a channel, which shares the file with its open writer on Windows too.
    Files.write(copy, Files.readAllBytes(path));
    int compared = 0;
    try (var use = manager.acquire(path.toString())) {
      var view = (LiveLog.View) use.log();
      var names = view.entries().keySet();
      var independent = IndependentLog.read(copy, Set.copyOf(names), Set.copyOf(names), Set.copyOf(names));
      for (var name : names) {
        var where = name + " at step " + step;
        var series = independent.series.get(name);
        var records = view.records(name, true);
        assertEquals(series.facts, records.size(), where + ": records");
        assertEquals(series.facts, view.sampleCount(name), where + ": count");
        for (int i = 0; i < records.size(); i++) {
          assertEquals(series.factOffsets[i], records.offset(i), where + " record " + i + ": position");
          assertEquals(series.factMicros[i], records.timestampUs(i), where + " record " + i + ": time");
          assertEquals(series.factSizes[i], records.payloadBytes(i), where + " record " + i + ": size");
          assertValue(view.entries().get(name).type(), name.equals("/odd"), series.payloads.get(i), records, i, where + " record " + i);
          compared++;
        }
        assertColumn(view, name, series, where);
        int width = switch (view.entries().get(name).type()) {
          case "double", "int64" -> 8;
          case "float" -> 4;
          case "boolean" -> 1;
          default -> -1;
        };
        // Only a cold prefix decodes every payload: a hot record is answered as its frame carried it.
        // There a malformed number fails the whole read, as the live read's legacy policy keeps it;
        // a number whose column holds every record finds no problem without decoding one.
        if (width > 0 && coldPrefix) {
          boolean malformed = false;
          for (int i = 0; i < series.facts; i++) malformed |= series.factSizes[i] != width;
          if (malformed) assertThrows(java.util.InputMismatchException.class, () -> view.decodeProblem(name), where);
          else assertTrue(view.decodeProblem(name).isEmpty(), where + ": no record is malformed");
        }
      }
    } finally {
      Files.delete(copy);
    }
    return compared;
  }

  /**
   * A record's recorded value, against its payload decoded here by the format's rules. The odd
   * entry's frames carry integers for a double entry, which the recorder never writes: while one
   * is hot it is answered as the frame carried it, as it always was, and from the WPILOG after.
   */
  private static void assertValue(String type, boolean odd, byte[] payload, org.triplehelix.wpilogmcp.read.RecordSeries records,
      int i, String where) {
    var buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    Object value;
    try {
      value = records.decode(i);
    } catch (RuntimeException malformed) {
      assertTrue(odd && payload.length != 8, where + ": only a malformed record fails: " + malformed);
      return;
    }
    if (odd && value instanceof Long) return;
    switch (type) {
      case "double" -> assertEquals(Double.doubleToRawLongBits(buffer.getDouble()), Double.doubleToRawLongBits((Double) value), where);
      case "float" -> assertEquals(Float.floatToRawIntBits(buffer.getFloat()), Float.floatToRawIntBits((Float) value), where);
      case "int64" -> assertEquals(buffer.getLong(), (Long) value, where);
      case "boolean" -> assertEquals(payload[0] != 0, value, where);
      case "string" -> assertEquals(new String(payload, StandardCharsets.UTF_8), value, where);
      case "raw" -> assertArrayEquals(payload, (byte[]) value, where);
      case "double[]" -> {
        var doubles = new double[payload.length / 8];
        for (int k = 0; k < doubles.length; k++) doubles[k] = buffer.getDouble();
        assertArrayEquals(doubles, (double[]) value, where);
      }
      default -> throw new AssertionError(type);
    }
  }

  /**
   * A number's samples from its column, when it offers them, against the independent read's
   * numbers in time order. A read in time order sorts an entry logged out of order, keeping equal
   * times in the order they were logged, so a column offered for one must give the sorted samples.
   */
  private static void assertColumn(LiveLog.View view, String name, IndependentLog.Series series, String where) {
    var column = view.numericColumn(name, FieldPath.ROOT);
    if (column.isEmpty()) return;
    var samples = column.get().samples();
    assertEquals(series.n, samples.size(), where + ": column samples");
    int[] order = java.util.stream.IntStream.range(0, series.n).boxed()
        .sorted(java.util.Comparator.comparingLong(i -> series.micros[i])).mapToInt(Integer::intValue).toArray();
    for (int k = 0; k < samples.size(); k++) {
      int i = order[k];
      assertEquals(series.micros[i], samples.timeUs(k), where + " sample " + k);
      assertEquals(Double.doubleToRawLongBits(series.values[i]), Double.doubleToRawLongBits(samples.number(k)), where + " sample " + k);
    }
  }
}
