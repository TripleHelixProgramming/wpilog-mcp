/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.config.PullConfig;
import org.triplehelix.wpilogmcp.harness.FakeRoboRio;
import org.triplehelix.wpilogmcp.ssh.JschConnection;

/** Scripted NI-like and sparse replies exercise real SSH, never robot text or a local shell. */
class RobotFactsTest {
  @TempDir Path temp;
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
  private static PullConfig config(int port) {
    return new PullConfig(false, PullConfig.DISABLED.directories(), 0, 1_000_000,
        new PullConfig.Ssh("lvuser", "", null, false, port));
  }
  private static RobotFacts.Probe probe(String id) {
    return RobotFacts.commands().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
  }
  private static void replies(FakeRoboRio rio, boolean sparse) {
    for (var probe : RobotFacts.commands()) {
      String id = probe.id();
      String stdout = switch (id) {
        case "uname" -> "Linux synthetic-rio2 0.0-test armv7l\n";
        case "dmesg" -> "[    2.500000] synthetic kernel message\n";
        case "program" -> "pid=42\ncmdline=java -jar /home/lvuser/synthetic.jar\nenviron=readable\n";
        case "jre-modules" -> "java.base@17.0.1\njdk.management.agent@17.0.1\njdk.jfr@17.0.1\njdk.management.jfr@17.0.1\n";
        case "df" -> "Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/root 9000 2000 7000 23% /\n";
        case "clocks" -> "12.50 8.0\n1767323045.000000000\n";
        case "boot-id" -> "00000000-0000-4000-8000-000000000001\n";
        case "journal-uuid", "journal-boot" -> "1767323045.000000 synthetic program message\n";
        default -> id.startsWith("which:") ? "/usr/bin/" + id.substring(6) + "\n" : "synthetic readable evidence\n";
      };
      if (sparse && id.equals("dmesg")) rio.reply(probe.command(), 0, "", "dmesg: read kernel buffer failed: Operation not permitted\n");
      else if (sparse && List.of("program", "jre-modules").contains(id)) rio.reply(probe.command(), 3, "No matching robot program\n", "");
      else if (sparse && (id.contains("journal") || id.startsWith("list:") || id.startsWith("image:") || id.startsWith("console")))
        rio.reply(probe.command(), 127, "", "synthetic: not found\n");
      else rio.reply(probe.command(), 0, stdout, "");
    }
  }

  @Test void rio2LikeAndSparseImagesProduceDatedEvidenceAndHonestConclusionsOverRealSsh() throws Exception {
    for (boolean sparse : List.of(false, true)) {
      try (var rio = new FakeRoboRio(temp.resolve("rio-" + sparse), "SYNTHETIC", "")) {
        replies(rio, sparse);
        if (!sparse) Files.write(rio.logs().resolve("sample.wpilog"), new byte[] {1, 2, 3});
        var config = config(rio.port());
        try (var connection = JschConnection.connect("127.0.0.1", config.ssh(), null)) {
          var report = RobotFacts.collect(connection, "127.0.0.1", config, CLOCK);
          var conclusions = RobotFactsReport.conclusions(report);
          var markdown = RobotFactsReport.markdown(report, RobotFactsReport.redactor(config.ssh()));
          assertEquals("empty password", report.authentication());
          assertTrue(markdown.contains("2026-01-02T03:04:05Z (UTC)"));
          assertTrue(markdown.contains(connection.fingerprint()), "Host-key fingerprint is evidence, not a secret");
          assertEquals("found", conclusions.get("uname").state());
          for (String module : List.of("jdk.management.agent", "jdk.jfr", "jdk.management.jfr"))
            assertEquals(sparse ? "absent" : "found", conclusions.get("module:" + module).state());
          assertEquals(sparse ? "absent" : "found", conclusions.get("which:journalctl").state());
          assertEquals(sparse ? "refused" : "found", conclusions.get("dmesg-seconds-stamps").state());
          assertEquals(sparse ? "absent" : "found", conclusions.get("proc-environ-readable").state());
          assertEquals("found", conclusions.get("df-line-shape").state());
          assertEquals(sparse ? "absent" : "found", conclusions.get("journal-uuid").state());
          assertEquals(sparse ? "absent" : "found", conclusions.get("hash").state());
          assertEquals("absent", conclusions.get("provider-cpu-and-disk-budget").state());
          assertEquals(RobotFacts.commands().size() + (sparse ? 0 : 1), rio.commands.get());
          assertEquals(1, rio.authentications.get(), "Every channel shares the one connection");
          if (sparse) assertTrue(markdown.contains("Operation not permitted"));
          else {
            assertTrue(markdown.contains("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"));
            assertTrue(markdown.contains("Hash requested bytes: 3"));
          }
          for (var observation : report.observations()) assertTrue(markdown.contains("### " + observation.probe().id() + "\n"));
        }
      }
    }
  }

  @Test void everyCommandBelongsToAnIndependentReadOnlyAllowlist() throws Exception {
    var ordinary = new HashSet<>(List.of("uname -a", "journalctl --version", "tail --version", "cat /proc/sys/kernel/random/boot_id",
        "journalctl -b --no-pager -n 3 -o short-unix",
        "journalctl -b \"$(cat /proc/sys/kernel/random/boot_id)\" --no-pager -n 3 -o short-unix",
        "dmesg | head -3", "df -Pk /", "ls -la /home/lvuser/FRC_UserProgram.log",
        "ls -la /var/local/natinst/log/FRC_UserProgram.log", "cat /proc/uptime; date +%s.%N"));
    for (String file : List.of("/etc/os-release", "/etc/natinst/share/ni-rt.ini", "/etc/natinst/share/ni-imaging-info.ini")) ordinary.add("cat " + file);
    for (String dir : List.of("/var/local/natinst/log", "/var/log", "/home/lvuser", "/home/lvuser/logs", "/u/logs", "/U/logs")) ordinary.add("ls -la " + dir);
    for (String tool : List.of("journalctl", "dmesg", "df", "tail", "sha256sum")) ordinary.add("which " + tool);
    for (var p : RobotFacts.commands()) {
      assertTrue(RobotFacts.allowed(p.command()));
      if (!List.of("program", "jre-modules").contains(p.id())) assertTrue(ordinary.remove(p.command()), p.id() + " left the read-only allowlist");
      else {
        // The one compound discovery script only inspects cmdline and tests environ readability.
        assertTrue(p.command().startsWith("jar=$(sed -n "));
        assertTrue(p.command().contains("/home/lvuser/robotCommand"));
        assertTrue(p.command().contains("for f in /proc/[0-9]*/cmdline"));
        if (p.id().equals("program")) assertTrue(p.command().contains("[ -r \"$p/environ\" ]"));
        else assertTrue(p.command().contains("\"$p/exe\" --list-modules; exit $?"));
        assertFalse(p.command().matches("(?s).*\\b(?:cat|tr|head|sed) [^;]*environ.*"));
        assertFalse(p.command().matches("(?s).*\\b(?:rm|touch|mkdir|tee|dd|cp|mv|truncate|chmod|kill|sudo)\\b.*"));
        assertFalse(p.command().replace("2>/dev/null", "").contains(">"), "No writes, even a redirected empty file");
      }
    }
    assertTrue(ordinary.isEmpty(), "Every checklist probe must run");
    assertTrue(RobotFacts.allowed("head -c 104857600 -- '/home/lvuser/logs/a'\"'\"'b.wpilog' | sha256sum"));
    for (String bad : List.of("touch /tmp/a", "head -c 104857601 -- '/a' | sha256sum", "head -c 1 -- '/a'; rm /tmp/b | sha256sum",
        "head -c 1 -- '/a\nb' | sha256sum", "head -c 1 -- 'relative' | sha256sum")) assertFalse(RobotFacts.allowed(bad));
  }

  @Test void timedHashNamesItsSizeAndNeverReadsMoreThanOneHundredMiB() throws Exception {
    try (var rio = new FakeRoboRio(temp.resolve("hash"), "SYNTHETIC", "")) {
      replies(rio, false);
      try (var file = new java.io.RandomAccessFile(rio.logs().resolve("largest.wpilog").toFile(), "rw")) { file.setLength(200_000_000); }
      Files.write(rio.logs().resolve("small.wpilog"), new byte[] {1});
      String command = "head -c 104857600 -- '/home/lvuser/logs/largest.wpilog' | sha256sum";
      rio.reply(command, 0, "a".repeat(64) + "  -\n", "");
      var cfg = config(rio.port());
      try (var connection = JschConnection.connect("127.0.0.1", cfg.ssh(), null)) {
        var report = RobotFacts.collect(connection, "127.0.0.1", cfg, CLOCK);
        var hash = report.observations().get(report.observations().size() - 1);
        assertEquals(command, hash.probe().command()); assertEquals(104_857_600, hash.probe().hashBytes());
        assertTrue(hash.reply().elapsedNanos() > 0);
        var markdown = RobotFactsReport.markdown(report, UnaryOperator.identity());
        assertTrue(markdown.contains("Hash requested bytes: 104857600 (ceiling 104857600)"));
        assertTrue(markdown.substring(markdown.indexOf("### hash")).contains("elapsed_ms:"));
        assertEquals("found", RobotFactsReport.conclusions(report).get("hash").state());
      }
    }
  }

  @Test void pureConclusionsDoNotGuessFromAZeroPipelineExitOrAnUnrelatedExitCode() {
    var report = new RobotFacts.Report("synthetic", 22, "lvuser", "none", "SHA256:public", CLOCK.instant(), List.of(
        new RobotFacts.Observation(probe("dmesg"), new JschConnection.Reply(0, "", "dmesg: not found", false, false, 1)),
        new RobotFacts.Observation(probe("df"), new JschConnection.Reply(3, "", "cannot inspect", false, false, 1)),
        new RobotFacts.Observation(probe("program"), new JschConnection.Reply(0, "environ=refused", "", false, false, 1)),
        new RobotFacts.Observation(probe("which:tail"), new JschConnection.Reply(0, "", "", false, false, 1))));
    var conclusions = RobotFactsReport.conclusions(report);
    assertEquals("absent", conclusions.get("dmesg").state());
    assertEquals("refused", conclusions.get("df").state());
    assertEquals("refused", conclusions.get("proc-environ-readable").state());
    assertEquals("absent", conclusions.get("which:tail").state());
  }

  @Test void runtimeModuleConclusionsRequireTheProbeAndAnExactModuleName() {
    for (String output : List.of("", "jdk.management.agent.extra@17\njdk.management.jfr.extra@17", "jdk.jfr@17")) {
      var observations = output.isEmpty() ? List.<RobotFacts.Observation>of() : List.of(
          new RobotFacts.Observation(probe("jre-modules"), new JschConnection.Reply(0, output, "", false, false, 1)));
      var report = new RobotFacts.Report("synthetic", 22, "lvuser", "none", "public", CLOCK.instant(), observations);
      var conclusions = RobotFactsReport.conclusions(report);
      assertEquals("absent", conclusions.get("module:jdk.management.agent").state());
      assertEquals("absent", conclusions.get("module:jdk.management.jfr").state());
      assertEquals(output.equals("jdk.jfr@17") ? "found" : "absent", conclusions.get("module:jdk.jfr").state());
    }
  }

  @Test void inspectionKeepsExitAndStderrBoundsAndAClockDeadlineDoesNotDropTheConnection() throws Exception {
    try (var rio = new FakeRoboRio(temp.resolve("bounds"), "SYNTHETIC", "")) {
      rio.reply("bounded", 7, "x".repeat(100_000), "y".repeat(100_000));
      var deadlines = new SftpLoopbackTest.Deadlines();
      try (var connection = JschConnection.connect("127.0.0.1", config(rio.port()).ssh(), null, new com.jcraft.jsch.JSch(), deadlines)) {
        var reply = connection.inspect("bounded", 10000, 32768);
        assertEquals(7, reply.exitStatus()); assertEquals(32768, reply.stdout().length()); assertEquals(32768, reply.stderr().length());
        assertTrue(reply.truncated()); assertFalse(reply.timedOut()); assertTrue(deadlines.cancelled);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        rio.script("silent", output -> { output.write('x'); output.flush(); entered.countDown(); release.await(); });
        try {
          var pending = CompletableFuture.supplyAsync(() -> {
            try { return connection.inspect("silent", 10000, 32768); }
            catch (Exception e) { throw new CompletionException(e); }
          });
          assertTrue(entered.await(30, TimeUnit.SECONDS)); deadlines.pending.run();
          assertTrue(pending.get(30, TimeUnit.SECONDS).timedOut()); assertTrue(connection.connected());
        } finally { release.countDown(); }
      }
    }
  }

  @Test void reportAndRefusalOutputNeverContainKeysOrPasswords() throws Exception {
    String password = "synthetic-password-secret";
    Path key = temp.resolve("private-key-file");
    var auth = new PullConfig.Ssh("lvuser", password, key);
    var report = new RobotFacts.Report("synthetic", 22, "lvuser", "publickey", "SHA256:public-fingerprint", CLOCK.instant(), List.of(
        new RobotFacts.Observation(probe("program"), new JschConnection.Reply(0,
            "cmdline=java --password '" + password + "' --key \"" + key + "\" TOKEN=unrelated-secret\n", password + " " + key, false, false, 1))));
    var markdown = RobotFactsReport.markdown(report, RobotFactsReport.redactor(auth));
    assertFalse(markdown.contains(password)); assertFalse(markdown.contains(key.toString())); assertFalse(markdown.contains("unrelated-secret"));
    assertTrue(markdown.contains("SHA256:public-fingerprint")); assertTrue(markdown.contains("[redacted]"));
    var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
    int code = RobotFactsCommand.run(new String[] {"robot-facts", "127.0.0.1", "--key", key.toString(), "--out", temp.resolve("never.md").toString()},
        new PrintStream(out), new PrintStream(err), temp, CLOCK);
    assertEquals(1, code); assertFalse(out.toString().contains(key.toString())); assertFalse(err.toString().contains(key.toString()));
    assertTrue(err.toString().contains("Cannot collect robot facts"));
    assertFalse(Files.exists(temp.resolve("never.md")));
  }

  @Test void commandUsesNamedSshConfigurationAndItsPinsAndDoesNotOverwriteReports() throws Exception {
    try (var rio = new FakeRoboRio(temp.resolve("named-rio"), "SYNTHETIC", "")) {
      replies(rio, true);
      var store = temp.resolve("store"); var yaml = temp.resolve("servers.yaml"); var report = temp.resolve("facts.md");
      Files.writeString(yaml, "servers:\n  pit:\n    transport: http\n    logdir: '" + store + "'\n    capture:\n"
          + "      robot: {host: 127.0.0.1}\n      store: '" + store + "'\n      pull:\n        ssh: {user: lvuser, port: " + rio.port() + "}\n");
      var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
      String[] args = {"robot-facts", "--server", "pit", "--config", yaml.toString(), "--out", report.toString()};
      assertEquals(0, RobotFactsCommand.run(args, new PrintStream(out), new PrintStream(err), temp, CLOCK), err.toString());
      assertTrue(Files.readString(report).contains("Authentication: empty password"));
      assertTrue(Files.readString(store.resolve("ssh-hosts.json")).contains("SHA256:"));
      assertFalse(Files.exists(temp.resolve(".wpilog-mcp/robot-facts")), "Named server must use its capture store's pins");
      int before = rio.commands.get();
      assertEquals(1, RobotFactsCommand.run(args, new PrintStream(out), new PrintStream(err), temp, CLOCK));
      assertTrue(err.toString().contains("already exists")); assertEquals(before, rio.commands.get());
    }
  }

  @Test void argumentsRejectAmbiguitySecretsInUrlsAndBadPortsWithoutEchoingValues() {
    for (String[] args : List.of(new String[] {"robot-facts"}, new String[] {"robot-facts", "x", "--port", "0"},
        new String[] {"robot-facts", "user:password@host"}, new String[] {"robot-facts", "x", "--password", "secret"},
        new String[] {"robot-facts", "--server", "pit", "--user", "x"}, new String[] {"robot-facts", "x", "--config", "file"}))
      assertThrows(IllegalArgumentException.class, () -> RobotFactsCommand.parse(args));
    var parsed = RobotFactsCommand.parse(new String[] {"robot-facts", "10.0.0.2"});
    assertEquals("lvuser", parsed.user()); assertEquals(22, parsed.port());
  }

  @Test void publicVerbUsesEnvironmentAuthenticationWithoutSecretsInTheReportOrProcessLog() throws Exception {
    String secret = "synthetic-environment-password";
    try (var rio = new FakeRoboRio(temp.resolve("cli-rio"), "SYNTHETIC", "")) {
      replies(rio, true); rio.acceptPassword(secret);
      rio.reply(probe("program").command(), 0, "pid=42\ncmdline=java --password '" + secret + "'\nenviron=readable\n", "");
      var store = temp.resolve("cli-store"); var yaml = temp.resolve("cli.yaml"); var report = temp.resolve("cli.md");
      Files.writeString(yaml, "servers:\n  pit:\n    transport: http\n    capture:\n      robot: {host: 127.0.0.1}\n"
          + "      store: '" + store + "'\n      pull:\n        ssh: {port: " + rio.port() + ", password: '${FACTS_TEST_PASSWORD}'}\n");
      var out = temp.resolve("cli.stdout"); var err = temp.resolve("cli.stderr");
      var builder = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "-Xmx128m", "-Duser.home=" + temp,
          "-cp", System.getProperty("java.class.path"), "org.triplehelix.wpilogmcp.Main", "robot-facts", "--server", "pit",
          "--config", yaml.toString(), "--out", report.toString()).redirectOutput(out.toFile()).redirectError(err.toFile());
      builder.environment().put("FACTS_TEST_PASSWORD", secret); builder.environment().remove("WPILOG_DEBUG");
      var child = builder.start(); child.getOutputStream().close();
      try {
        assertTrue(child.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, child.exitValue(), Files.readString(err));
      } finally { child.destroyForcibly(); }
      assertFalse(Files.readString(out).contains(secret)); assertFalse(Files.readString(err).contains(secret));
      String markdown = Files.readString(report);
      assertFalse(markdown.contains(secret)); assertTrue(markdown.contains("Authentication: password (nonempty)"));
      assertFalse(Files.readString(store.resolve("ssh-hosts.json")).contains(secret));
      assertEquals(1, rio.authentications.get());
    }
  }
}
