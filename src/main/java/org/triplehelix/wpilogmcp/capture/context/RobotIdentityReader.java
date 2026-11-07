/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * WPILib 2026's athena HAL reads getenv("serialnum") and /etc/machine-info's quoted
 * PRETTY_HOSTNAME. SSH does not inherit the robot launcher environment, so read the readable
 * process environments, not the login shell's environment or a guessed NI configuration token.
 * Conflicting serials are an error, never a first-process-wins identity.
 */
public final class RobotIdentityReader {
  public static final String COMMENTS_PATH = "/etc/machine-info";
  public static final String ENVIRONMENT_GLOB = "/proc/[0-9]*/environ";
  public interface Files {
    List<String> environments() throws IOException;
    byte[] read(String path) throws IOException;
  }
  private RobotIdentityReader() {}

  public static DeviceIdentity read(Files files, String address, String fingerprint) throws IOException {
    String serial = null, source = null;
    for (String path : files.environments().stream().sorted().toList()) {
      byte[] bytes;
      try { bytes = files.read(path); }
      catch (IOException ignored) { continue; } // Another user's process, or one that just exited.
      for (String item : new String(bytes, StandardCharsets.UTF_8).split("\u0000")) {
        if (!item.startsWith("serialnum=")) continue;
        String value = item.substring("serialnum=".length());
        if (value.isBlank()) continue;
        if (serial != null && !serial.equals(value)) throw new IOException("Conflicting serialnum process environments");
        serial = value; source = path;
      }
    }
    if (serial == null) throw new IOException("No readable robot serialnum in " + ENVIRONMENT_GLOB);
    String comments;
    try { comments = comments(files.read(COMMENTS_PATH)); }
    catch (IOException ignored) { comments = ""; } // HAL also returns empty when this file cannot be read.
    try {
      return new DeviceIdentity(serial, comments, address, fingerprint,
          Map.of("serial_source", source + ":serialnum", "comments_source", COMMENTS_PATH + ":PRETTY_HOSTNAME"));
    } catch (IllegalArgumentException e) { throw new IOException(e.getMessage(), e); }
  }

  /** The HAL unescapes a C string and retains at most 64 UTF-8 bytes, not 64 characters. */
  static String comments(byte[] bytes) {
    String text = new String(bytes, StandardCharsets.UTF_8), prefix = "PRETTY_HOSTNAME=\"";
    int start = text.indexOf(prefix);
    if (start < 0) return "";
    var result = new ByteArrayOutputStream();
    for (int i = start + prefix.length(); i < text.length();) {
      char c = text.charAt(i++);
      if (c == '"') break;
      if (c != '\\') {
        int cp = Character.codePointAt(text, i - 1);
        result.writeBytes(new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8));
        i += Character.charCount(cp) - 1; continue;
      }
      if (i == text.length()) break;
      char escape = text.charAt(i++);
      int value = switch (escape) {
        case 'a' -> 7; case 'b' -> 8; case 'f' -> 12; case 'n' -> 10;
        case 'r' -> 13; case 't' -> 9; case 'v' -> 11; default -> escape;
      };
      if (escape == 'x') {
        value = 0;
        while (i < text.length() && Character.digit(text.charAt(i), 16) >= 0) {
          value = value * 16 + Character.digit(text.charAt(i++), 16);
        }
      } else if (escape >= '0' && escape <= '7') {
        value = escape - '0'; int count = 1;
        while (count++ < 3 && i < text.length() && text.charAt(i) >= '0' && text.charAt(i) <= '7') {
          value = value * 8 + text.charAt(i++) - '0';
        }
      }
      result.write(value);
    }
    byte[] resultBytes = result.toByteArray();
    return new String(resultBytes, 0, Math.min(64, resultBytes.length), StandardCharsets.UTF_8);
  }
}
