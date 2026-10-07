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
