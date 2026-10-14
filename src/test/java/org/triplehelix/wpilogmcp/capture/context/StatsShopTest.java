/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.config.ProviderConfig;
import org.triplehelix.wpilogmcp.config.PullConfig;
import org.triplehelix.wpilogmcp.ssh.SshConnection;

class StatsShopTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;
  private static final String DISCOVERY = ProcFixture.sample(0)
      + "WPILOG_STATS_1:disk_paths\n/home/lvuser\n/media/usb\n/media/usb\n";

  private static final class Remote implements SshConnection {
    final List<String> commands = new ArrayList<>();
    final AtomicLong clock;
    long cost = 300_000;
    Remote(AtomicLong clock) { this.clock = clock; }
    @Override public Optional<String> exec(String command, long timeout, int max) {
      commands.add(command);
      if (command.contains("robotCommand")) return Optional.of(DISCOVERY);
      clock.addAndGet(cost); return Optional.of(ProcFixture.sample(0));
    }
    @Override public String fingerprint() { return "synthetic"; }
    @Override public boolean connected() { return true; }
    @Override public Files files() { throw new UnsupportedOperationException(); }
    @Override public Command follow(String command) { throw new UnsupportedOperationException(); }
    @Override public void close() {}
  }

  @Test void resolvesRealDirectoriesOncePerConnectionAndSamplesEachAliasOnlyOnce() throws Exception {
    var time = new AtomicLong(); var remote = new Remote(time);
    var provider = new StatsProvider(ProviderConfig.DISABLED.stats(), PullConfig.DISABLED.directories(), time::get, () -> 0.0);
    provider.sample(remote); provider.sample(remote);
    assertEquals(3, remote.commands.size(), "One discovery and two steady samples");
    assertTrue(remote.commands.get(0).contains("pwd -P"), remote.commands.get(0));
    String sample = remote.commands.get(1);
    assertTrue(sample.contains("'/media/usb'"), sample);
    assertFalse(sample.contains("'/u'")); assertFalse(sample.contains("'/U'"));
    assertEquals(1, sample.split("set -- \\\"\\$@\\\" '/media/usb'", -1).length - 1, sample);
    assertFalse(sample.contains("pwd -P")); assertFalse(sample.contains("cd --"));
    assertEquals(sample, remote.commands.get(2));
    var reconnected = new Remote(time); provider.sample(reconnected);
    assertEquals(2, reconnected.commands.size());
    assertTrue(reconnected.commands.get(0).contains("pwd -P"));
  }

  @Test void backoffReasonKeepsTheCurrentRoundTripAndOriginalBudgetUntilRecovery() throws Exception {
    var time = new AtomicLong(); var remote = new Remote(time);
    var defaults = ProviderConfig.DISABLED.stats();
    assertEquals(100_000, defaults.budgetUs()); assertEquals(2_000_000, defaults.periodUs());
    var provider = new StatsProvider(defaults, PullConfig.DISABLED.directories(), time::get, () -> 0.0);
    var first = provider.sample(remote);
    assertEquals(4_000_000, provider.periodUs());
    assertTrue(first.sample().notes().stream().anyMatch(n -> n.contains("budget") && n.contains("100.0") && n.contains("300.0")), first.sample().notes().toString());
    provider.sample(remote); assertEquals(8_000_000, provider.periodUs());
    remote.cost = 50_000;
    var recovery = provider.sample(remote); assertEquals(4_000_000, provider.periodUs());
    assertTrue(recovery.sample().notes().stream().anyMatch(n -> n.contains("budget") && n.contains("100.0") && n.contains("50.0")), recovery.sample().notes().toString());
    var recovered = provider.sample(remote); assertEquals(2_000_000, provider.periodUs());
    assertFalse(recovered.sample().notes().stream().anyMatch(n -> n.contains("budget")));
  }

  @Test void remoteFilesystemPathsCannotIntroduceControlCharacters() throws Exception {
    var known = ProcStats.configuration(DISCOVERY, null);
    for (String path : List.of("/media/a\tb", "/media/a\u001bb", "/media/a\u007fb", "relative"))
      assertThrows(IllegalArgumentException.class, () -> StatsCommand.sample(List.of(path), known), path);
  }
  @Test @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
  void discoveryShellResolvesTwoSymlinksToTheSamePhysicalDirectory() throws Exception {
    var physical = java.nio.file.Files.createDirectory(temp.resolve("physical stick")).toRealPath();
    var lower = java.nio.file.Files.createSymbolicLink(temp.resolve("alias-lower"), physical);
    var upper = java.nio.file.Files.createSymbolicLink(temp.resolve("alias-upper"), physical);
    String command = StatsCommand.lookup(true, List.of(lower.toString(), upper.toString()));
    var child = new ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).start();
    try {
      assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
      String text = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertEquals(0, child.exitValue(), text);
      var known = ProcStats.configuration(text, null);
      assertEquals(2, known.diskDirectories().stream().filter(physical.toString()::equals).count(), text);
      String sample = StatsCommand.sample(known.diskDirectories(), known);
      assertEquals(1, sample.split(java.util.regex.Pattern.quote("set -- \"$@\" " + SshConnection.quote(physical.toString())), -1).length - 1, sample);
    } finally { child.destroyForcibly(); }
  }

  @Test void directoryIdentityDoesNotTrimPhysicalPathWhitespace() throws Exception {
    var known = ProcStats.configuration(DISCOVERY.replace("/media/usb\n", "/media/usb \n"), null);
    assertTrue(known.diskDirectories().contains("/media/usb "), known.diskDirectories().toString());
    assertTrue(StatsCommand.sample(known.diskDirectories(), known).contains("'/media/usb '"));
  }

  @Test void unitsAndProcessIdsMustBeValidatedBeforeBuildingAShellCommand() throws Exception {
    var original = ProcStats.configuration(DISCOVERY, null);
    for (var known : List.of(new ProcStats.Configuration(0, original.pages(), original.program(), List.of()),
        new ProcStats.Configuration(original.ticks(), 0, original.program(), List.of()),
        new ProcStats.Configuration(original.ticks(), original.pages(), new ProcStats.Program(-1, 0, 0, 0, 0), List.of())))
      assertThrows(IllegalArgumentException.class, () -> StatsCommand.sample(List.of(), known));
  }

  @Test void pathQuotingPreservesShellMetacharactersAndDoesNotAcceptRelativeRoots() throws Exception {
    String path = "/media/it's a stick; $(never-run)";
    var known = ProcStats.configuration(DISCOVERY, null);
    String command = StatsCommand.sample(List.of(path), known);
    assertTrue(command.contains("'/media/it'\"'\"'s a stick; $(never-run)'"), command);
    String discovery = StatsCommand.lookup(true, List.of(path));
    assertTrue(discovery.contains("'/media/it'\"'\"'s a stick; $(never-run)'"), discovery);
    assertThrows(IllegalArgumentException.class, () -> StatsCommand.lookup(true, List.of("relative")));
  }
}
