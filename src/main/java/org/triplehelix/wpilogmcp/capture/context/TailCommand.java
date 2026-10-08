/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import org.triplehelix.wpilogmcp.ssh.SshConnection;

/** The first stdout line is our protocol; all following lines belong to the selected source. */
public final class TailCommand {
  private TailCommand() {}
  public static final String FOLLOW = "WPILOG_TAIL_1:follow", POLL = "WPILOG_TAIL_1:poll", MISSING = "WPILOG_TAIL_1:missing";
  public static String open(String path, String role) {
    String nativeCommand = switch (role) {
      case "kernel" -> "if command -v dmesg >/dev/null 2>&1 && dmesg --help 2>&1 | grep -q -- '-w'; then printf '" + FOLLOW + "\\n'; exec dmesg -w; fi; ";
      case "journal" -> "if command -v journalctl >/dev/null 2>&1; then printf '" + FOLLOW + "\\n'; exec journalctl -n 0 -f --no-pager -o cat; fi; ";
      default -> "";
    };
    String fallback = role.equals("kernel") || role.equals("journal")
        ? "printf '" + POLL + "\\n'" : "printf '" + FOLLOW + "\\n'; exec tail -n 0 -F -s 0.25 -- " + SshConnection.quote(path);
    return "export LC_ALL=C; " + nativeCommand + "if [ ! -r " + SshConnection.quote(path)
        + " ]; then printf '" + MISSING + "\\n'; else " + fallback + "; fi";
  }
  /** Probe the inode and byte length; a different inode or shorter file resets the held offset. */
  public static String stat(String path) {
    return "export LC_ALL=C; stat -c '%i %s' -- " + SshConnection.quote(path);
  }
  public static String read(String path, long offset) {
    if (offset < 0) throw new IllegalArgumentException("Negative tail offset");
    return "export LC_ALL=C; tail -c +" + Math.addExact(offset, 1) + " -- " + SshConnection.quote(path) + " | head -c 65536";
  }
}
