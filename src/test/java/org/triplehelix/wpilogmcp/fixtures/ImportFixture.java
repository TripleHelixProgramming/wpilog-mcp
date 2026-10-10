/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.fixtures;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

/** Small, distinct logs with an exact wall clock; no team's data or native writer. */
public final class ImportFixture {
  private ImportFixture() {}

  /** Imports prove a shared boot clock; the general REV fixture deliberately has a 15.3 s lag. */
  public static Path sameClockRevPair(Path directory, String name) throws Exception {
    var wpilog = FixtureLogs.writeRevlogPair(directory, name, java.time.ZoneOffset.UTC, "systemTime");
    try (var paths = Files.list(directory)) {
      for (var rev : paths.filter(p -> p.toString().endsWith(".revlog")).toList()) sameClockRev(rev);
    }
    return wpilog;
  }

  /** Same independently defined signal as the corpus pair, recorded on its WPILOG clock. */
  public static void sameClockRev(Path path) throws Exception {
    try (var w = new WpilogWriter(path, "synthetic same-boot import")) {
      int output = w.start("CAN/3/Periodic Status 0", "raw", "", 0);
      for (int n = 0; n <= 5000; n++) {
        double time = n * 0.01 + FixtureLogs.REVLOG_PAIR_OFFSET_SEC;
        w.append(output, Math.round(time * 1e6), FixtureLogs.sparkStatus0(FixtureLogs.revlogPairOutput(time)));
      }
    }
  }

  public static Path write(Path path, int tag) throws Exception {
    return write(path, tag, null, 1_767_225_600_000_000L);
  }

  public static Path write(Path path, int tag, String serial, long epochUs) throws Exception {
    Files.createDirectories(path.getParent());
    try (var writer = new WpilogWriter(path, "import fixture " + tag)) {
      int clock = writer.start("systemTime", "int64", "", 0);
      int value = writer.start("/Value", "double", "", 0);
      writer.append(clock, 0, WpilogWriter.encodeInt64(epochUs));
      if (serial != null) {
        int identity = writer.start("/SystemStats/SerialNumber", "string", "", 0);
        writer.append(identity, 0, serial.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      writer.append(value, 0, WpilogWriter.encodeDouble(tag));
      writer.append(value, 1_000_000, WpilogWriter.encodeDouble(tag + 1));
    }
    Files.setLastModifiedTime(path, FileTime.from(Instant.parse("2026-01-01T00:00:01Z")));
    return path;
  }
}
