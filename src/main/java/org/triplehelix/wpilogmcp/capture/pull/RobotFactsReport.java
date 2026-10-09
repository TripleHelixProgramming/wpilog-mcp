/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import java.util.*;
import java.util.function.UnaryOperator;
import org.triplehelix.wpilogmcp.config.PullConfig;

/** Pure presentation: evidence keeps its exit status; lack of a measurement is never a success. */
public final class RobotFactsReport {
  private RobotFactsReport() {}
  public record Conclusion(String state, String reason) {}
  public static Map<String, Conclusion> conclusions(RobotFacts.Report report) {
    var out = new LinkedHashMap<String, Conclusion>();
    for (var o : report.observations()) out.put(o.probe().id(), classify(o));
    var dmesg = observation(report, "dmesg");
    out.put("dmesg-seconds-stamps", evidence(dmesg,
        dmesg != null && dmesg.reply().stdout().matches("(?s).*\\[\\s*[0-9]+(?:\\.[0-9]+)?\\].*"),
        "[seconds] stamps in the sampled lines", "No [seconds] stamp in these three lines; an empty buffer cannot establish support"));
    var process = observation(report, "program");
    out.put("proc-environ-readable", evidence(process,
        process != null && process.reply().stdout().contains("environ=readable"),
        "The selected account can read /proc/<pid>/environ; its contents were never read",
        "No readable program environment observed"));
    if (process != null && process.reply().stdout().contains("environ=refused"))
      out.put("proc-environ-readable", new Conclusion("refused", "The program environment is not readable by this account"));
    var modules = observation(report, "jre-modules");
    for (String module : List.of("jdk.management.agent", "jdk.jfr", "jdk.management.jfr")) {
      boolean found = modules != null && modules.reply().stdout().lines()
          .anyMatch(line -> line.equals(module) || line.startsWith(module + "@"));
      out.put("module:" + module, evidence(modules, found, "Listed by the deployed program's runtime",
          "Not observed in the deployed runtime's module list"));
    }
    var df = observation(report, "df");
    out.put("df-line-shape", evidence(df, df != null && df.reply().stdout().matches("(?s).*\\n[^\\n]+\\s+\\d+\\s+\\d+\\s+\\d+\\s+\\d+%\\s+/\\s*"),
        "df -Pk reports numeric 1 KiB block columns and the root mount", "No numeric POSIX df row observed"));
    out.put("ssh-authentication", new Conclusion("found", report.authentication()));
    for (String manual : List.of("wifi-loss-load-and-robot-timing", "provider-cpu-and-disk-budget", "tail-follow-options-and-rotation"))
      out.put(manual, new Conclusion("absent", "Not measured by this read-only snapshot; the shop exercise is still required"));
    return Collections.unmodifiableMap(out);
  }
  private static RobotFacts.Observation observation(RobotFacts.Report report, String id) {
    return report.observations().stream().filter(o -> o.probe().id().equals(id)).findFirst().orElse(null);
  }
  private static Conclusion evidence(RobotFacts.Observation o, boolean found, String yes, String no) {
    if (o == null) return new Conclusion("absent", no);
    var state = classify(o); if (!state.state().equals("found")) return state;
    return new Conclusion(found ? "found" : "absent", found ? yes : no);
  }
  private static Conclusion classify(RobotFacts.Observation o) {
    var r = o.reply();
    if (r.timedOut()) return new Conclusion("refused", "Command deadline expired; partial output is retained below");
    String error = r.stderr().toLowerCase(Locale.ROOT);
    if (error.contains("permission denied") || error.contains("not permitted") || error.contains("refused"))
      return new Conclusion("refused", "Permission or channel refusal; see stderr below");
    if (error.contains("not found") || error.contains("no such file"))
      return new Conclusion("absent", "The command or file is absent; see stderr below");
    if (r.exitStatus() != 0) {
      boolean absent = r.exitStatus() == 127 || (r.exitStatus() == 3 && List.of("program", "jre-modules").contains(o.probe().id())) || error.contains("no nonempty");
      return new Conclusion(absent ? "absent" : "refused", "Exit status " + r.exitStatus() + "; see command evidence below");
    }
    if (o.probe().id().startsWith("which:") && r.stdout().isBlank()) return new Conclusion("absent", "No executable path returned");
    if (o.probe().id().equals("hash") && (!r.stdout().strip().matches("[0-9a-fA-F]{64}\\s+.*") || !r.stderr().isBlank()))
      return new Conclusion("refused", "Hash did not return an unambiguous digest without a read error");
    return new Conclusion("found", r.truncated() ? "Command succeeded; output exceeded the recorded bound" : "Command succeeded");
  }
  /** Redact known credentials and sensitive command-line options, including report/log refusals. */
  public static UnaryOperator<String> redactor(PullConfig.Ssh ssh) {
    var secrets = new ArrayList<String>();
    if (ssh.password() != null && !ssh.password().isEmpty()) secrets.add(ssh.password());
    if (ssh.key() != null) secrets.add(ssh.key().toString());
    return text -> {
      String value = text == null ? "" : text;
      for (String secret : secrets) value = value.replace(secret, "[redacted]");
      value = value.replaceAll("(?i)((?:--?(?:password|passwd|token|secret|key)(?:=|[ \\t]+)|[A-Z_]*(?:PASSWORD|PASSWD|TOKEN|SECRET)(?:=|:[ \\t]*)))(?:\"[^\"]*\"|'[^']*'|[^\\s]+)", "$1[redacted]");
      return value;
    };
  }
  public static String markdown(RobotFacts.Report report, UnaryOperator<String> redact) {
    var out = new StringBuilder("# roboRIO shop facts\n\nCollected at ").append(report.collectedAt())
        .append(" (UTC) from ").append(report.host()).append(':').append(report.port()).append(" as ").append(report.user())
        .append(".\n\nAuthentication: ").append(report.authentication()).append(". Host key: ").append(report.fingerprint())
        .append(".\n\nThis is a read-only snapshot, not the load/rotation/radio shop exercise. Image paths are candidates, not assumed facts.\n\n## Conclusions\n\n");
    conclusions(report).forEach((name, result) -> out.append("- ").append(name).append(": **").append(result.state())
        .append("** — ").append(result.reason()).append('\n'));
    out.append("\n## Command evidence\n\nStdout and stderr are each bounded to ").append(RobotFacts.OUTPUT_LIMIT_BYTES).append(" bytes.\n");
    for (var observation : report.observations()) {
      var probe = observation.probe(); var result = observation.reply();
      out.append("\n### ").append(probe.id()).append("\n\n");
      literal(out, probe.command() == null ? "Not run" : probe.command());
      out.append("\nExit status: ").append(result.exitStatus()).append("; elapsed_ms: ").append(result.elapsedNanos() / 1_000_000.)
          .append("; timed_out: ").append(result.timedOut()).append("; output_truncated: ").append(result.truncated()).append(".\n");
      if (probe.id().equals("hash")) out.append("\nHash requested bytes: ").append(probe.hashBytes())
          .append(" (ceiling ").append(RobotFacts.HASH_LIMIT_BYTES)
          .append("); based on the remote size at listing. A changing file may yield fewer bytes; this measures the bounded command, not a whole-file hash.\n");
      out.append("\nStdout:\n\n"); literal(out, result.stdout()); out.append("\nStderr:\n\n"); literal(out, result.stderr());
    }
    return redact.apply(out.toString());
  }
  private static void literal(StringBuilder out, String text) {
    if (text.isEmpty()) { out.append("    (empty)\n"); return; }
    text.lines().forEach(line -> out.append("    ").append(line).append('\n'));
  }
}
