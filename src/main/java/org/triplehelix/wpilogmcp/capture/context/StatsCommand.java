/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import java.util.List;
import org.triplehelix.wpilogmcp.ssh.SshConnection;

/** Discover once per connection; steady samples read fixed proc files with shell builtins. */
public final class StatsCommand {
  private StatsCommand() {}
  public static String lookup(boolean units) {
    var command = new StringBuilder("export LC_ALL=C; ");
    if (units) {
      section(command, "ticks"); command.append("getconf CLK_TCK; ");
      section(command, "pages"); command.append("getconf PAGESIZE; ");
    }
    section(command, "program");
    // Only discovery scans command lines. A steady sample checks the cached pid's start ticks.
    command.append("jar=$(sed -n 's/.*-jar \"\\([^\"]*\\)\".*/\\1/p' /home/lvuser/robotCommand 2>/dev/null); ")
        .append("if [ -n \"$jar\" ]; then for f in /proc/[0-9]*/cmdline; do ")
        .append("if tr '\\000' '\\n' < \"$f\" 2>/dev/null | grep -Fqx -- \"$jar\"; then cat \"${f%/cmdline}/stat\" 2>/dev/null; fi; done; fi");
    return command.toString();
  }

  public static String sample(List<String> logDirectories, ProcStats.Configuration known) {
    var command = new StringBuilder("export LC_ALL=C; emit() { if [ -r \"$1\" ]; then "
        + "while IFS= read -r line || [ -n \"$line\" ]; do printf '%s\\n' \"$line\"; done < \"$1\"; fi; }; ");
    section(command, "ticks"); command.append("printf '%s\\n' ").append(known.ticks()).append("; ");
    section(command, "pages"); command.append("printf '%s\\n' ").append(known.pages()).append("; ");
    String[][] files = {{"load", "/proc/loadavg"}, {"stat", "/proc/stat"}, {"memory", "/proc/meminfo"},
        {"uptime", "/proc/uptime"}, {"network", "/proc/net/dev"}};
    for (var file : files) { section(command, file[0]); command.append("emit ").append(SshConnection.quote(file[1])).append("; "); }
    var mounts = new java.util.LinkedHashSet<>(List.of("/home/lvuser", "/u", "/U"));
    for (String path : logDirectories) if (mounts.stream().noneMatch(m -> path.equals(m) || path.startsWith(m + "/"))) mounts.add(path);
    section(command, "disk_paths"); command.append("set --; ");
    for (String path : mounts) {
      if (!path.startsWith("/") || path.contains("\n") || path.contains("\r")) throw new IllegalArgumentException("Invalid stats filesystem path");
      String quoted = SshConnection.quote(path);
      command.append("if [ -d ").append(quoted).append(" ]; then set -- \"$@\" ").append(quoted)
          .append("; printf '%s\\n' ").append(quoted).append("; fi; ");
    }
    section(command, "disk");
    // Filesystem free space has no proc file. One df serves every selected filesystem.
    command.append("if [ \"$#\" -gt 0 ]; then df -Pk \"$@\" 2>/dev/null; fi; ");
    section(command, "program");
    if (known.program() != null) command.append("emit /proc/").append(known.program().pid()).append("/stat;");
    return command.toString();
  }
  private static void section(StringBuilder out, String name) {
    out.append("printf '%s\\n' ").append(SshConnection.quote("WPILOG_STATS_1:" + name)).append("; ");
  }
}
