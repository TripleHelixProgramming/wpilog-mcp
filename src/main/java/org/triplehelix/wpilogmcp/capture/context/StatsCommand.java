/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import java.util.List;
import org.triplehelix.wpilogmcp.ssh.SshConnection;

/** Read-only shell grammar with explicit sections; one exec channel gathers a whole sample. */
public final class StatsCommand {
  private StatsCommand() {}
  public static String sample(List<String> logDirectories) {
    var command = new StringBuilder("export LC_ALL=C; ");
    section(command, "ticks", "getconf CLK_TCK"); section(command, "pages", "getconf PAGESIZE");
    section(command, "load", "cat /proc/loadavg"); section(command, "stat", "cat /proc/stat");
    section(command, "memory", "cat /proc/meminfo"); section(command, "uptime", "cat /proc/uptime");
    section(command, "network", "cat /proc/net/dev");
    var mounts = new java.util.LinkedHashSet<>(List.of("/home/lvuser", "/u", "/U"));
    for (String path : logDirectories) {
      if (mounts.stream().noneMatch(m -> path.equals(m) || path.startsWith(m + "/"))) mounts.add(path);
    }
    for (String path : mounts) {
      if (!path.startsWith("/") || path.contains("\n") || path.contains("\r")) throw new IllegalArgumentException("Invalid stats filesystem path");
      command.append("if [ -d ").append(SshConnection.quote(path)).append(" ]; then ");
      section(command, "disk/" + path.substring(1), "df -Pk " + SshConnection.quote(path)); command.append("fi; ");
    }
    section(command, "program", "");
    // GradleRIO writes a quoted -jar path into robotCommand. Read that declaration; another
    // Java process or a custom deployment we cannot parse is never substituted for the robot.
    command.append("jar=$(sed -n 's/.*-jar \"\\([^\"]*\\)\".*/\\1/p' /home/lvuser/robotCommand 2>/dev/null); ")
        .append("if [ -n \"$jar\" ]; then for f in /proc/[0-9]*/cmdline; do ")
        .append("if tr '\\000' '\\n' < \"$f\" 2>/dev/null | grep -Fqx -- \"$jar\"; then cat \"${f%/cmdline}/stat\" 2>/dev/null; fi; done; fi");
    return command.toString();
  }
  private static void section(StringBuilder out, String name, String read) {
    out.append("printf '%s\\n' ").append(SshConnection.quote("WPILOG_STATS_1:" + name)).append("; ");
    if (!read.isEmpty()) out.append(read).append(" 2>/dev/null; ");
  }
}
