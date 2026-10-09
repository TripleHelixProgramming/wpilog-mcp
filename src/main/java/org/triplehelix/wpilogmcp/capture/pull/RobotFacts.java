/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.triplehelix.wpilogmcp.config.PullConfig;
import org.triplehelix.wpilogmcp.ssh.JschConnection;
import org.triplehelix.wpilogmcp.ssh.SshConnection;

/** A fixed read-only inspection, not a remote shell. Missing utilities and permissions are facts. */
public final class RobotFacts {
  private RobotFacts() {}
  public static final long HASH_LIMIT_BYTES = 104_857_600;
  public static final int OUTPUT_LIMIT_BYTES = 32_768;
  public record Probe(String id, String command, long hashBytes) {}
  public record Observation(Probe probe, JschConnection.Reply reply) {}
  public record Report(String host, int port, String user, String authentication, String fingerprint,
      Instant collectedAt, List<Observation> observations) {
    public Report { observations = List.copyOf(observations); }
  }
  private static final String PROGRAM = "jar=$(sed -n 's/.*-jar \"\\([^\"]*\\)\".*/\\1/p' /home/lvuser/robotCommand); "
      + "found=0; if [ -n \"$jar\" ]; then for f in /proc/[0-9]*/cmdline; do "
      + "if tr '\\000' '\\n' < \"$f\" 2>/dev/null | grep -Fqx -- \"$jar\"; then found=1; "
      + "p=${f%/cmdline}; printf 'pid=%s\\n' \"${p##*/}\"; printf 'cmdline='; tr '\\000' ' ' < \"$f\"; printf '\\n'; "
      + "if [ -r \"$p/environ\" ]; then printf 'environ=readable\\n'; "
      + "elif [ -e \"$p/environ\" ]; then printf 'environ=refused\\n'; else printf 'environ=absent\\n'; fi; fi; done; fi; "
      + "if [ \"$found\" = 0 ]; then printf 'No matching robot program\\n'; exit 3; fi";
  // /proc/<pid>/exe follows the deployed process, not the login shell's possibly different java.
  private static final String MODULES = PROGRAM.substring(0, PROGRAM.indexOf("p=${f%/cmdline};"))
      + "p=${f%/cmdline}; \"$p/exe\" --list-modules; exit $?; fi; done; fi; "
      + "printf 'No matching robot runtime\\n'; exit 3";
  private static final List<Probe> FIXED = fixed();
  private static List<Probe> fixed() {
    var probes = new ArrayList<Probe>();
    add(probes, "uname", "uname -a");
    for (String file : List.of("/etc/os-release", "/etc/natinst/share/ni-rt.ini", "/etc/natinst/share/ni-imaging-info.ini"))
      add(probes, "image:" + file, "cat " + file);
    for (String directory : List.of("/var/local/natinst/log", "/var/log", "/home/lvuser", "/home/lvuser/logs", "/u/logs", "/U/logs"))
      add(probes, "list:" + directory, "ls -la " + directory);
    for (String tool : List.of("journalctl", "dmesg", "df", "tail", "sha256sum")) add(probes, "which:" + tool, "which " + tool);
    add(probes, "journal-version", "journalctl --version"); add(probes, "tail-version", "tail --version");
    add(probes, "boot-id", "cat /proc/sys/kernel/random/boot_id");
    add(probes, "journal-boot", "journalctl -b --no-pager -n 3 -o short-unix");
    // -b alone only proves current-boot selection. Ask separately about the UUID form.
    add(probes, "journal-uuid", "journalctl -b \"$(cat /proc/sys/kernel/random/boot_id)\" --no-pager -n 3 -o short-unix");
    add(probes, "dmesg", "dmesg | head -3"); add(probes, "df", "df -Pk /");
    add(probes, "console", "ls -la /home/lvuser/FRC_UserProgram.log");
    add(probes, "console-ni", "ls -la /var/local/natinst/log/FRC_UserProgram.log");
    add(probes, "program", PROGRAM);
    add(probes, "jre-modules", MODULES);
    add(probes, "clocks", "cat /proc/uptime; date +%s.%N");
    return List.copyOf(probes);
  }
  private static void add(List<Probe> probes, String id, String command) { probes.add(new Probe(id, command, 0)); }
  public static List<Probe> commands() { return FIXED; }
  public static boolean allowed(String command) {
    if (FIXED.stream().anyMatch(p -> p.command().equals(command))) return true;
    var pattern = java.util.regex.Pattern.compile("head -c ([0-9]+) -- ('(?:[^']|'\"'\"')*') \\| sha256sum");
    var m = pattern.matcher(command); if (!m.matches()) return false;
    try {
      long count = Long.parseLong(m.group(1));
      String path = m.group(2).substring(1, m.group(2).length() - 1).replace("'\"'\"'", "'");
      return count > 0 && count <= HASH_LIMIT_BYTES && path.startsWith("/") && path.chars().noneMatch(c -> c < 32);
    } catch (NumberFormatException e) { return false; }
  }
  public static Report collect(JschConnection connection, String host, PullConfig config, Clock clock) {
    var observations = new ArrayList<Observation>(); var started = clock.instant();
    for (var probe : FIXED) observations.add(inspect(connection, probe, 10_000));
    try (var files = SftpTransport.using(connection, config.directories(), host, false)) {
      var largest = files.list().stream().max(Comparator.comparingLong(RobotRemote.File::size).thenComparing(RobotRemote.File::name));
      if (largest.isPresent() && largest.get().size() > 0) {
        var file = largest.get(); long count = Math.min(HASH_LIMIT_BYTES, file.size());
        var probe = new Probe("hash", "head -c " + count + " -- " + SshConnection.quote(file.name()) + " | sha256sum", count);
        observations.add(inspect(connection, probe, SftpTransport.hashTimeoutMs(count)));
      } else observations.add(missingHash("No nonempty WPILOG or REV file in the configured log directories"));
    } catch (IOException e) { observations.add(missingHash("SFTP log inventory refused: " + e.getMessage())); }
    String auth = connection.authentication();
    if (auth.equals("password")) auth = config.ssh().password().isEmpty() ? "empty password" : "password (nonempty)";
    return new Report(host, config.ssh().port(), config.ssh().user(), auth, connection.fingerprint(), started, observations);
  }
  private static Observation inspect(JschConnection connection, Probe probe, long timeoutMs) {
    if (!allowed(probe.command())) return new Observation(probe,
        new JschConnection.Reply(-1, "", "Command refused: outside read-only allowlist", false, false, 0));
    try { return new Observation(probe, connection.inspect(probe.command(), timeoutMs, OUTPUT_LIMIT_BYTES)); }
    catch (IOException e) { return new Observation(probe, new JschConnection.Reply(-1, "", e.getMessage(), false, false, 0)); }
  }
  private static Observation missingHash(String reason) {
    return new Observation(new Probe("hash", null, 0), new JschConnection.Reply(-1, "", reason, false, false, 0));
  }
}
