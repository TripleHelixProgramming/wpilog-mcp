/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Linux documents ticks, pages and KiB separately; none is assumed to be the pit host's unit. */
public final class ProcStats {
  private ProcStats() {}
  /** An unsupported image is a session stand-down, distinct from a recoverable SSH loss. */
  public static final class UnsupportedOutputException extends IOException {
    private UnsupportedOutputException(RuntimeException cause) {
      super("Unsupported SSH stats output: " + cause.getMessage(), cause);
    }
  }
  public record Network(long received, long transmitted) {}
  public record Program(long pid, long startTicks, long cpuTicks, long rssPages, long threads) {}
  public record Snapshot(Map<String, Number> gauges, long ticksPerSecond, long busyTicks, long totalTicks,
      double uptime, Map<String, Network> networks, Program program, List<String> notes) {
    public Snapshot { gauges = Map.copyOf(gauges); networks = Map.copyOf(networks); notes = List.copyOf(notes); }
  }
  public record Sample(Map<String, Number> values, Double robotCpuSeconds, List<String> notes) {
    public Sample { values = Map.copyOf(values); notes = List.copyOf(notes); }
  }
  public static Snapshot parse(String text) throws IOException {
    try {
      var sections = sections(text);
      long ticks = positive(one(sections, "ticks")), pages = positive(one(sections, "pages"));
      var values = new LinkedHashMap<String, Number>(); var notes = new ArrayList<String>();
      var load = one(sections, "load").split("\\s+");
      if (load.length != 5) throw new IllegalArgumentException("loadavg shape");
      for (int i = 0; i < 3; i++) values.put(new String[] {"load_1min", "load_5min", "load_15min"}[i], nonnegative(load[i]));
      var tasks = load[3].split("/");
      if (tasks.length != 2) throw new IllegalArgumentException("loadavg task counts");
      values.put("runnable_tasks", integer(tasks[0]));
      var cpu = required(sections, "stat").stream().filter(s -> s.startsWith("cpu ")).findFirst().orElseThrow().split("\\s+");
      if (cpu.length < 5) throw new IllegalArgumentException("proc/stat CPU fields");
      long total = 0;
      // guest and guest_nice are already included in user/nice, so sum only the first eight.
      for (int i = 1; i < Math.min(9, cpu.length); i++) total = Math.addExact(total, integer(cpu[i]));
      long idle = integer(cpu[4]) + (cpu.length > 5 ? integer(cpu[5]) : 0);
      for (String field : List.of("MemAvailable", "MemFree")) {
        String line = required(sections, "memory").stream().filter(s -> s.startsWith(field + ":")).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("missing " + field));
        var parts = line.split("\\s+");
        if (parts.length != 3 || !parts[2].equals("kB")) throw new IllegalArgumentException("meminfo unit");
        values.put(field.equals("MemAvailable") ? "mem_available_bytes" : "mem_free_bytes", Math.multiplyExact(integer(parts[1]), 1024));
      }
      var uptime = one(sections, "uptime").split("\\s+");
      if (uptime.length != 2) throw new IllegalArgumentException("uptime shape");
      double up = nonnegative(uptime[0]); values.put("uptime_sec", up);
      var networks = new LinkedHashMap<String, Network>();
      for (String line : required(sections, "network")) {
        int colon = line.indexOf(':'); if (colon < 0) continue;
        String device = line.substring(0, colon).strip();
        if (!device.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("network interface name");
        var counters = line.substring(colon + 1).strip().split("\\s+");
        if (counters.length != 16) throw new IllegalArgumentException("network counter shape");
        networks.put(device, new Network(integer(counters[0]), integer(counters[8])));
      }
      for (var item : sections.entrySet()) if (item.getKey().startsWith("disk/")) {
        var lines = item.getValue();
        if (lines.size() != 2 || !lines.get(0).contains("1024-blocks")) throw new IllegalArgumentException("df -Pk shape");
        var disk = lines.get(1).split("\\s+");
        if (disk.length < 6) throw new IllegalArgumentException("df fields");
        values.put(item.getKey() + "/free_bytes", Math.multiplyExact(integer(disk[3]), 1024));
      }
      var paths = sections.get("disk_paths");
      if (paths != null && !paths.isEmpty()) {
        var disks = sections.getOrDefault("disk", List.of());
        if (disks.size() != paths.size() + 1 || !disks.get(0).contains("1024-blocks")) {
          notes.add("Filesystems changed during df; this sample omits filesystem free space");
        } else for (int i = 0; i < paths.size(); i++) {
          var fields = disks.get(i + 1).split("\\s+");
          if (fields.length < 6) throw new IllegalArgumentException("df fields");
          values.put("disk/" + paths.get(i).substring(1) + "/free_bytes", Math.multiplyExact(integer(fields[3]), 1024));
        }
      }
      Program program = program(required(sections, "program"));
      if (program == null) notes.add("No unique readable process with the robot JAR name");
      else {
        values.put("program/pid", program.pid()); values.put("program/threads", program.threads());
        values.put("program/rss_bytes", Math.multiplyExact(program.rssPages(), pages));
      }
      return new Snapshot(values, ticks, total - idle, total, up, networks, program, notes);
    } catch (RuntimeException e) { throw new UnsupportedOutputException(e); }
  }
  public record Configuration(long ticks, long pages, Program program) {}
  public static Configuration configuration(String text, Configuration before) throws IOException {
    try {
      var sections = sections(text);
      return new Configuration(before == null ? positive(one(sections, "ticks")) : before.ticks(),
          before == null ? positive(one(sections, "pages")) : before.pages(), program(required(sections, "program")));
    } catch (RuntimeException e) { throw new UnsupportedOutputException(e); }
  }
  private static Map<String, List<String>> sections(String text) {
    var sections = new LinkedHashMap<String, List<String>>(); String section = null;
    for (String line : text.split("\\R")) {
      if (line.startsWith("WPILOG_STATS_1:")) {
        section = line.substring("WPILOG_STATS_1:".length());
        if (sections.putIfAbsent(section, new ArrayList<>()) != null) throw new IllegalArgumentException("duplicate section " + section);
      } else if (section != null && !line.isBlank()) sections.get(section).add(line.strip());
      else if (!line.isBlank()) throw new IllegalArgumentException("unexpected output before stats sections");
    }
    return sections;
  }
  private static Program program(List<String> processes) {
    if (processes.size() != 1) return null;
    String stat = processes.get(0); int open = stat.indexOf('('), close = stat.lastIndexOf(')');
    if (open < 1 || close <= open) throw new IllegalArgumentException("program stat command name");
    var fields = stat.substring(close + 1).strip().split("\\s+");
    if (fields.length < 22) throw new IllegalArgumentException("program stat fields");
    return new Program(positive(stat.substring(0, open).strip()), integer(fields[19]),
        Math.addExact(integer(fields[11]), integer(fields[12])), integer(fields[21]), integer(fields[17]));
  }
  static Snapshot withoutProgram(Snapshot sample) {
    var values = new LinkedHashMap<>(sample.gauges()); values.keySet().removeIf(k -> k.startsWith("program/"));
    var notes = new ArrayList<>(sample.notes()); notes.add("Robot process changed; locating its JAR again");
    return new Snapshot(values, sample.ticksPerSecond(), sample.busyTicks(), sample.totalTicks(),
        sample.uptime(), sample.networks(), null, notes);
  }
  public static Sample between(Snapshot previous, Snapshot current) {
    var values = new LinkedHashMap<>(current.gauges()); var notes = new ArrayList<>(current.notes());
    Double processor = null;
    if (previous != null) {
      double elapsed = current.uptime() - previous.uptime();
      long total = current.totalTicks() - previous.totalTicks(), busy = current.busyTicks() - previous.busyTicks();
      if (elapsed > 0 && total > 0 && busy >= 0 && busy <= total && current.ticksPerSecond() == previous.ticksPerSecond()) {
        values.put("cpu_busy_fraction", (double) busy / total); processor = (double) busy / current.ticksPerSecond();
        for (var item : current.networks().entrySet()) {
          var before = previous.networks().get(item.getKey()); var after = item.getValue();
          if (before != null && after.received() >= before.received() && after.transmitted() >= before.transmitted()) {
            values.put("net/" + item.getKey() + "/rx_bytes_per_sec", (after.received() - before.received()) / elapsed);
            values.put("net/" + item.getKey() + "/tx_bytes_per_sec", (after.transmitted() - before.transmitted()) / elapsed);
          } else notes.add("Network counter baseline reset: " + item.getKey());
        }
        var before = previous.program(); var after = current.program();
        if (before != null && after != null && before.pid() == after.pid() && before.startTicks() == after.startTicks()
            && after.cpuTicks() >= before.cpuTicks()) {
          values.put("program/cpu_fraction", (after.cpuTicks() - before.cpuTicks()) / (elapsed * current.ticksPerSecond()));
        }
      } else notes.add("Clock or CPU counters reset; interval rates omitted");
    }
    return new Sample(values, processor, notes);
  }
  private static List<String> required(Map<String, List<String>> sections, String name) {
    var value = sections.get(name); if (value == null) throw new IllegalArgumentException("missing " + name); return value;
  }
  private static String one(Map<String, List<String>> sections, String name) {
    var value = required(sections, name); if (value.size() != 1) throw new IllegalArgumentException("expected one " + name + " line"); return value.get(0);
  }
  private static long integer(String text) { long value = Long.parseLong(text); if (value < 0) throw new IllegalArgumentException("negative counter"); return value; }
  private static long positive(String text) { long value = integer(text); if (value == 0) throw new IllegalArgumentException("zero unit"); return value; }
  private static double nonnegative(String text) { double value = Double.parseDouble(text); if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("invalid number"); return value; }
}
