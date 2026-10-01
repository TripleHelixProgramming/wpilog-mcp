/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The file a tool was pointed at cannot be read as a log: it is missing, empty, not a WPILOG, or
 * too large to map. This is a fact about the file, not a server fault, so tools report its
 * message as an ordinary explained error rather than an internal one.
 *
 * @since 0.9.1
 */
public class LogFileException extends IOException {

  public LogFileException(String message) {
    super(message);
  }

  /**
   * What makes the file not a WPILOG, stated as facts read from the file: its size and what its
   * first bytes are. Robots produce such files: one that lost power before its log was flushed
   * leaves an empty file, or one whose reserved space was never written and reads as zeros.
   */
  public static LogFileException invalid(Path file) {
    long size;
    byte[] head;
    try {
      size = Files.size(file);
      try (var in = Files.newInputStream(file)) {
        head = in.readNBytes(4096);
      }
    } catch (IOException e) {
      return new LogFileException("Invalid WPILOG file: " + file + " (it could not be read: "
          + e.getMessage() + ")");
    }
    String what;
    if (size == 0) {
      what = "the file is empty, 0 bytes";
    } else if (size < 12) {
      what = "the file is " + size + " bytes, too short for a WPILOG header";
    } else if (head.length >= 6 && new String(head, 0, 6, java.nio.charset.StandardCharsets.US_ASCII)
        .equals("WPILOG")) {
      what = String.format("it has the WPILOG header with version bytes %02x %02x, which this "
          + "reader does not support", head[6] & 0xFF, head[7] & 0xFF);
    } else {
      boolean zeros = true;
      for (byte b : head) {
        if (b != 0) {
          zeros = false;
          break;
        }
      }
      var start = new StringBuilder();
      for (int i = 0; i < Math.min(6, head.length); i++) {
        start.append(String.format(i == 0 ? "%02x" : " %02x", head[i] & 0xFF));
      }
      what = "it does not start with the WPILOG header: " + (zeros
          ? "its first " + head.length + " bytes are all zero" : "it starts with " + start)
          + ", of " + size + " bytes";
    }
    return new LogFileException("Invalid WPILOG file: " + file + " (" + what + ")");
  }
}
