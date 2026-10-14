/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import org.triplehelix.wpilogmcp.ssh.SshConnection;

/** Discover once per connection; steady samples read fixed proc files with shell builtins. */
public final class StatsCommand {
  private StatsCommand() {}
  public static String lookup(boolean units) { return lookup(units, List.of()); }

  public static String lookup(boolean units, List<String> logDirectories) {
    var command = new StringBuilder("export LC_ALL=C; ");
    if (units) {
      section(command, "ticks"); command.append("getconf CLK_TCK; ");
      section(command, "pages"); command.append("getconf PAGESIZE; ");
      section(command, "disk_paths");
      var candidates = new LinkedHashSet<>(List.of("/home/lvuser", "/u", "/U"));
      for (String path : paths(logDirectories))
        if (candidates.stream().noneMatch(m -> path.equals(m) || path.startsWith(m + "/"))) candidates.add(path);
      command.append("for p in ");
      for (String path : candidates) command.append(SshConnection.quote(path)).append(' ');
      // NI's shell supplies these builtins; do not depend on a GNU readlink/realpath executable.
      command.append("; do if [ -d \"$p\" ]; then (cd -- \"$p\" && pwd -P); fi; done; ");
    }
    section(command, "program");
    // Only discovery scans command lines. A steady sample checks the cached pid's start ticks.
    command.append("jar=$(sed -n 's/.*-jar \"\\([^\"]*\\)\".*/\\1/p' /home/lvuser/robotCommand 2>/dev/null); ")
        .append("if [ -n \"$jar\" ]; then for f in /proc/[0-9]*/cmdline; do ")
        .append("if tr '\\000' '\\n' < \"$f\" 2>/dev/null | grep -Fqx -- \"$jar\"; then cat \"${f%/cmdline}/stat\" 2>/dev/null; fi; done; fi");
    return command.toString();
  }

  /** Paths came from pwd -P at connection discovery, never from a spelling-based alias guess. */
  public static String sample(List<String> resolvedDirectories, ProcStats.Configuration known) {
    var command = new StringBuilder(proc(known));
    section(command, "disk_paths"); command.append("set --; ");
    for (String path : paths(resolvedDirectories)) {
      String quoted = SshConnection.quote(path);
      command.append("if [ -d ").append(quoted).append(" ]; then set -- \"$@\" ").append(quoted)
          .append("; printf '%s\\n' ").append(quoted).append("; fi; ");
    }
    section(command, "disk");
    // Filesystem free space has no proc file. One df serves every distinct real directory.
    command.append("if [ \"$#\" -gt 0 ]; then ").append(df("\"$@\"")).append("; fi; ");
    return command.toString();
  }

  private static String proc(ProcStats.Configuration known) {
    if (known.ticks() <= 0 || known.pages() <= 0 || known.program() != null && known.program().pid() <= 0)
      throw new IllegalArgumentException("Invalid stats units or process id");
    var command = new StringBuilder("export LC_ALL=C; emit() { if [ -r \"$1\" ]; then "
        + "while IFS= read -r line || [ -n \"$line\" ]; do printf '%s\\n' \"$line\"; done < \"$1\"; fi; }; ");
    section(command, "ticks"); command.append("printf '%s\\n' ").append(known.ticks()).append("; ");
    section(command, "pages"); command.append("printf '%s\\n' ").append(known.pages()).append("; ");
    String[][] files = {{"load", "/proc/loadavg"}, {"stat", "/proc/stat"}, {"memory", "/proc/meminfo"},
        {"uptime", "/proc/uptime"}, {"network", "/proc/net/dev"}};
    for (var file : files) { section(command, file[0]); command.append("emit ").append(SshConnection.quote(file[1])).append("; "); }
    section(command, "program");
    if (known.program() != null) command.append("emit /proc/").append(known.program().pid()).append("/stat; ");
    return command.toString();
  }

  /** The facts probe times the very same steady command and its proc/df parts. */
  public static Map<String, String> timingCommands(ProcStats.Configuration known) {
    var commands = new LinkedHashMap<String, String>();
    String sample = sample(known.diskDirectories(), known);
    for (int attempt = 1; attempt <= 3; attempt++) commands.put("stats-steady-" + attempt, sample);
    commands.put("stats-proc", proc(known));
    for (String path : paths(known.diskDirectories()))
      commands.put("stats-df:" + path, "export LC_ALL=C; " + df(SshConnection.quote(path)));
    return java.util.Collections.unmodifiableMap(commands);
  }

  private static String df(String arguments) { return "df -Pk " + arguments + " 2>/dev/null"; }

  private static List<String> paths(List<String> directories) {
    for (String path : directories)
      if (!path.startsWith("/") || path.chars().anyMatch(c -> c < 32 || c == 127))
        throw new IllegalArgumentException("Invalid stats filesystem path");
    return directories.stream().distinct().toList();
  }
  private static void section(StringBuilder out, String name) {
    out.append("printf '%s\\n' ").append(SshConnection.quote("WPILOG_STATS_1:" + name)).append("; ");
  }
}
