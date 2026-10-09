/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import static org.triplehelix.wpilogmcp.fixtures.SystemSessionFixture.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.pull.*;
import org.triplehelix.wpilogmcp.config.*;
import org.triplehelix.wpilogmcp.harness.FakeRoboRio;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.store.StoreManifest.Session;
import org.triplehelix.wpilogmcp.sync.FileTransfer;

class SystemPullTest {
  @TempDir Path temp;
  Path root, capture;
  StoreRegistry registry;
  LogStore store;
  SystemPullStore local;
  @BeforeEach void setup() throws Exception {
    root = temp.toRealPath().resolve("store"); capture = create(root, "first", true, 42);
    var security = new SecurityValidator(); security.addAllowedDirectory(temp);
    registry = new StoreRegistry(security); store = registry.store(root); local = store.systemPulls(FakeRobot.device(SERIAL, "SHA256:fixture"), WALL);
  }
  @AfterEach void close() { registry.close(); }
  Session session(Path file) throws Exception { return StoreJson.JSON.fromJson(Files.readString(file.getParent().resolve("session.json")), Session.class); }
  static String reply(double uptime, String boot, String text, int status) { return "WPILOG_SYSTEM_BEGIN " + uptime + " " + boot + "\n" + text + "\nWPILOG_SYSTEM_END " + status + "\n"; }
  void pass(SystemPullPass pass, AtomicLong clock) throws Exception {
    for (int n = 0; n < 80; n++) {
      var result = pass.step(); clock.addAndGet(Math.max(1, result.waitUs()));
      assertNotEquals(FileTransfer.Status.REFUSED, result.status(), result.detail());
      if (result.status() == FileTransfer.Status.IDLE) return;
    }
    fail("system pass did not end");
  }
  @Test void aStrayAtTheKernelDestinationIsRefusedWithoutChangingIt() throws Exception {
    var path = capture.getParent().resolve("robot/system/dmesg.txt"); Files.createDirectories(path.getParent());
    Files.writeString(path, "synthetic user-owned stray\n"); assertTrue(local.beginPass());
    assertTrue(assertThrows(java.io.IOException.class, () -> local.kernel(List.of("[100] incoming"), 110)).getMessage().contains("Unmanifested system file"));
    assertEquals("synthetic user-owned stray\n", Files.readString(path));
    assertTrue(session(capture).systemLogs().files().isEmpty());
  }
  @Test void overlappingKernelPassesCommitCursorAndNewBootUsesANewSession() throws Exception {
    assertTrue(local.beginPass());
    local.kernel(List.of("[100.0] boot", "[110.0] warning synthetic"), 120);
    var resumed = store.systemPulls(FakeRobot.device(SERIAL, "SHA256:fixture"), WALL); assertTrue(resumed.beginPass());
    resumed.kernel(List.of("[100.0] boot", "[110.0] warning synthetic", "[115.0] error synthetic"), 122);
    var before = session(capture);
    assertEquals(List.of("[100.0] boot", "[110.0] warning synthetic", "[115.0] error synthetic"), Files.readAllLines(capture.getParent().resolve("robot/system/dmesg.txt")));
    assertEquals(115, before.systemLogs().kernelCursor().lastSeconds()); assertEquals(122, before.systemLogs().kernelCursor().uptimeSec());
    // An append interrupted before its manifest replacement is not committed or searched.
    Files.writeString(capture.getParent().resolve("robot/system/dmesg.txt"), "uncommitted\n", java.nio.file.StandardOpenOption.APPEND);
    resumed.kernel(List.of("[115.0] error synthetic", "[116.0] next"), 124);
    assertFalse(Files.readString(capture.getParent().resolve("robot/system/dmesg.txt")).contains("uncommitted"));
    assertThrows(java.io.IOException.class, () -> resumed.kernel(List.of("[1.0] new boot"), 2));
    store.capture(io -> { var s = io.read(capture.getParent().resolve("session.json"), Session.class); io.write(capture.getParent().resolve("session.json"), new Session(s.id(), s.startedAt(), s.endedAt(), s.startBasis(), null, null, null, null, List.of(), null, "closed", s.deviceIdentity(), List.of(), List.of(), s.captureStats(), s.systemLogs())); return null; });
    var second = create(root, "second", true, 43); assertTrue(resumed.beginPass()); resumed.kernel(List.of("[1.0] new boot"), 2);
    assertEquals("[1.0] new boot\n", Files.readString(second.getParent().resolve("robot/system/dmesg.txt")));
    assertEquals(4, Files.readAllLines(capture.getParent().resolve("robot/system/dmesg.txt")).size());
  }
  @Test void realSshSharesGateBudgetContentIdentityAndPidPlacement() throws Exception {
    var rioRoot = temp.resolve("rio"); var clock = new AtomicLong(); var enabled = new AtomicBoolean(true);
    var older = create(root, "older", false, 41);
    try (var rio = new FakeRoboRio(rioRoot, SERIAL, "synthetic")) {
      Files.createDirectories(rioRoot.resolve("var/log")); Files.createDirectories(rioRoot.resolve("var/local/natinst/log"));
      byte[] messages = ("2026-03-07T14:22:38Z warning example\n").repeat(3000).getBytes(java.nio.charset.StandardCharsets.UTF_8);
      Files.write(rioRoot.resolve("var/log/messages"), messages);
      Files.writeString(rioRoot.resolve("var/local/natinst/log/program.log"), "program text\n");
      Files.writeString(rioRoot.resolve("home/lvuser/hs_err_pid42.log"), "crash 42\n");
      Files.writeString(rioRoot.resolve("home/lvuser/hs_err_pid41.log"), "crash 41\n");
      Files.writeString(rioRoot.resolve("home/lvuser/hs_err_pid99.log"), "crash 99\n");
      var settings = new PullConfig(false, List.of(), 0, 65536, new PullConfig.Ssh("lvuser", "", null, false, rio.port()),
          new SystemPullConfig(true, SystemPullConfig.Kernel.OFF, List.of("/var/log/messages"), false, List.of("/var/local/natinst/log"), List.of("/home/lvuser")));
      try (var remote = SftpTransport.connect("127.0.0.1", settings, null);
           var pass = new SystemPullPass(remote, local, settings.system(), settings.rateBytes(), clock::get, enabled::get)) {
        // Every read finishes one block and then waits for its exact byte budget, without a sleep.
        var copied = pass.step(); assertEquals(FileTransfer.Status.COPIED, copied.status());
        assertEquals((long) Math.ceil(copied.bytes() * 1e6 / 65536), copied.waitUs());
        assertEquals(FileTransfer.Status.WAITING, pass.step().status());
        enabled.set(false); clock.addAndGet(2_000_000); assertEquals(FileTransfer.Status.PAUSED, pass.step().status());
        enabled.set(true); pass(pass, clock);
        var receipts = session(capture).systemLogs().files(); assertEquals(4, receipts.size());
        var syslog = receipts.stream().filter(f -> f.source().equals("syslog")).findFirst().orElseThrow();
        assertArrayEquals(messages, Files.readAllBytes(root.resolve(syslog.path()))); assertEquals(SystemLogState.Location.STORE, syslog.location());
        assertEquals("pulled", syslog.provenance().kind()); assertEquals("/var/log/messages", syslog.provenance().originalPath());
        assertEquals(StoreFiles.hash(root.resolve(syslog.path())), syslog.sha256());
        var crashes = receipts.stream().filter(f -> f.source().equals("jvm_crash")).toList(); assertEquals(2, crashes.size());
        assertTrue(session(older).systemLogs().files().stream().anyMatch(f -> f.source().equals("jvm_crash") && f.path().endsWith("41.log")), "A crash joins its recorded pid's session, not simply the current session");
        assertTrue(session(older).systemLogs().files().stream().anyMatch(f -> f.source().equals("syslog") && f.sha256().equals(syslog.sha256())), "The cross-boot syslog is also reachable from the older session");
        assertNull(crashes.stream().filter(f -> f.path().endsWith("42.log")).findFirst().orElseThrow().note());
        var unknown = crashes.stream().filter(f -> f.path().endsWith("99.log")).findFirst().orElseThrow();
        assertTrue(unknown.path().contains("/unassigned/")); assertTrue(unknown.note().contains("No session"));
        Files.move(rioRoot.resolve("var/log/messages"), rioRoot.resolve("var/log/messages.1"));
        long before = local.manifest().files().stream().mapToLong(e -> e.bytesCopied()).sum();
        pass(pass, clock);
        assertEquals(before, local.manifest().files().stream().mapToLong(e -> e.bytesCopied()).sum(), "rotation must not fetch the payload again");
        assertEquals(4, session(capture).systemLogs().files().size());
        assertEquals("/var/log/messages.1", session(capture).systemLogs().files().stream().filter(f -> f.source().equals("syslog")).findFirst().orElseThrow().provenance().originalPath());
        int checked = rio.commands.get(); pass(pass, clock);
        assertEquals(checked, rio.commands.get(), "An unchanged pass must not hash every file on the robot again");
      }
    }
  }
  @Test void receiptsSurviveCaptureFlushAndClose() throws Exception {
    var storage = registry.store(temp.resolve("fresh")); var placement = storage.captures(WALL);
    var loop = new org.triplehelix.wpilogmcp.nt4.client.ManualScheduler();
    var topic = new org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce("/x", 1, "int", null, new com.google.gson.JsonObject());
    Path path;
    try (var writer = new org.triplehelix.wpilogmcp.capture.CaptureWriter(WALL, loop, org.triplehelix.wpilogmcp.capture.CapturePolicy.ALL, placement)) {
      writer.identity(FakeRobot.device(SERIAL, "SHA256:fixture"));
      writer.connected(org.triplehelix.wpilogmcp.nt4.client.RobotAddress.uri("127.0.0.1", 5810, "fixture"), "networktables.first.wpi.edu");
      writer.timeSync(10_000_000, 0); writer.announce(topic); writer.value(topic, new org.triplehelix.wpilogmcp.nt4.ValueFrame(1, 10_000_000, 2, 1L), 0);
      var logs = storage.systemPulls(FakeRobot.device(SERIAL, "SHA256:fixture"), WALL); assertTrue(logs.beginPass()); logs.kernel(List.of("[100.0] warning fixture"), 110);
      loop.advance(5_000_000); loop.until(() -> writer.session().observedAtUs() == loop.nowUs());
      placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(1, session(writer.session().path()).systemLogs().files().size());
      writer.disconnected(); placement.completion().get(10, java.util.concurrent.TimeUnit.SECONDS); path = writer.session().path();
    }
    assertEquals(1, session(path).systemLogs().files().size());
    assertEquals("[100.0] warning fixture\n", Files.readString(path.getParent().resolve("robot/system/dmesg.txt")));
  }
  @Test void journalCursorRoundTripsAndMissingCommandStandsDownWithoutGuessingFiles() throws Exception {
    var rioRoot = temp.resolve("rio"); var clock = new AtomicLong();
    try (var rio = new FakeRoboRio(rioRoot, SERIAL, "synthetic")) {
      Files.createDirectories(rioRoot.resolve("var/log")); Files.writeString(rioRoot.resolve("var/log/messages"), "must not be guessed\n");
      String first = SystemPullPass.command("journal", null, null), next = SystemPullPass.command("journal", "cursor-1", "boot-a");
      rio.script(first, out -> out.write(reply(120, "boot-a", "1772893358.125 host service: warning fixture\n-- cursor: cursor-1\n", 0).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      rio.script(next, out -> out.write(reply(124, "boot-a", "1772893359.125 host kernel: next\n-- cursor: cursor-2\n", 0).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      var settings = new PullConfig(false, List.of(), 0, 1_000_000, new PullConfig.Ssh("lvuser", "", null, false, rio.port()),
          new SystemPullConfig(true, SystemPullConfig.Kernel.OFF, List.of("/var/log/messages"), true, List.of(), List.of()));
      try (var remote = SftpTransport.connect("127.0.0.1", settings, null)) {
        try (var pass = new SystemPullPass(remote, local, settings.system(), settings.rateBytes(), clock::get, () -> true)) { pass(pass, clock); }
        assertEquals("cursor-1", session(capture).systemLogs().journalCursor());
        var restarted = store.systemPulls(FakeRobot.device(SERIAL, "SHA256:fixture"), WALL);
        try (var pass = new SystemPullPass(remote, restarted, settings.system(), settings.rateBytes(), clock::get, () -> true)) {
          pass(pass, clock);
          assertEquals("cursor-2", session(capture).systemLogs().journalCursor());
          assertEquals(1, session(capture).systemLogs().files().size());
          var receipt = session(capture).systemLogs().files().get(0);
          assertEquals("syslog", receipt.source()); assertEquals("journal", receipt.format());
          assertEquals(2, Files.readAllLines(root.resolve(receipt.path())).size());
          assertEquals("1772893358.125 host service: warning fixture\n1772893359.125 host kernel: next\n", Files.readString(root.resolve(receipt.path())));
          rio.script(SystemPullPass.command("journal", "cursor-2", "boot-a"), out -> out.write(reply(126, "boot-a", "", 0).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
          pass(pass, clock); assertEquals("cursor-2", session(capture).systemLogs().journalCursor());
          assertTrue(session(capture).systemLogs().reasons().isEmpty(), "A quiet journal is not a missing command");
          // The wrapper reports command-not-found, distinct from a severed channel with no footer.
          rio.script(SystemPullPass.command("journal", "cursor-2", "boot-a"), out -> out.write(reply(126, "boot-a", "", 127).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
          pass(pass, clock);
          assertTrue(session(capture).systemLogs().reasons().get("journal").contains("no syslog-file fallback"));
          int commands = rio.commands.get(); pass(pass, clock); assertEquals(commands, rio.commands.get());
        }
      }
    }
  }

  @Test void execSnapshotsPauseAtOneBlockAndAnInterruptedReplyDoesNotCommitItsCursor() throws Exception {
    var clock = new AtomicLong(); var gate = new AtomicBoolean(true);
    String text = java.util.stream.IntStream.range(0, 4000).mapToObj(i -> "[" + (100 + i / 1000.) + "] synthetic kernel line " + i).collect(java.util.stream.Collectors.joining("\n"));
    try (var rio = new FakeRoboRio(temp.resolve("rio"), SERIAL, "synthetic")) {
      Files.writeString(rio.logs().resolve("tiny.txt"), "synthetic");
      rio.script(SystemPullPass.command("kernel", null, null), out -> out.write(reply(120, "boot-a", text, 0).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      var settings = new PullConfig(false, List.of(), 0, 65536, new PullConfig.Ssh("lvuser", "", null, false, rio.port()),
          new SystemPullConfig(true, SystemPullConfig.Kernel.DMESG, List.of(), false, List.of(), List.of()));
      try (var remote = SftpTransport.connect("127.0.0.1", settings, null)) {
        try (var pass = new SystemPullPass(remote, local, settings.system(), settings.rateBytes(), clock::get, gate::get)) {
          var first = pass.step(); assertEquals("dmesg", first.remoteName()); assertEquals(65536, first.bytes()); assertEquals(1_000_000, first.waitUs());
          clock.addAndGet(first.waitUs()); gate.set(false);
          assertEquals(FileTransfer.Status.PAUSED, pass.step().status());
          var separateChannel = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return remote.prefixHash("/home/lvuser/logs/tiny.txt", 9); }
            catch (java.io.IOException e) { throw new java.util.concurrent.CompletionException(e); }
          });
          assertTrue(separateChannel.get(5, java.util.concurrent.TimeUnit.SECONDS).isPresent(), "A paused exec stream must not stall another channel of the shared SSH connection");
          assertNull(session(capture).systemLogs().kernelCursor());
        }
        store.awaitImports();
        try (var held = Files.list(root.resolve("robots").resolve(SERIAL).resolve("system/.pull"))) {
          assertEquals(0, held.filter(p -> p.toString().endsWith(".part")).count(), "Interrupted exec snapshots must not accumulate at every gate pause or reconnect");
        }
        gate.set(true);
        try (var resumed = new SystemPullPass(remote, local, settings.system(), settings.rateBytes(), clock::get, gate::get)) { pass(resumed, clock); }
        assertEquals(text + "\n", Files.readString(capture.getParent().resolve("robot/system/dmesg.txt")));
        assertEquals(4000, Files.readAllLines(capture.getParent().resolve("robot/system/dmesg.txt")).size());
        var before = session(capture).systemLogs().kernelCursor();
        rio.script(SystemPullPass.command("kernel", null, null), out -> out.write("WPILOG_SYSTEM_BEGIN 130 boot-a\n[125] severed channel\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try (var broken = new SystemPullPass(remote, local, settings.system(), settings.rateBytes(), clock::get, gate::get)) {
          assertTrue(assertThrows(java.io.IOException.class, () -> pass(broken, clock)).getMessage().contains("cursor unchanged"));
        }
        assertEquals(before, session(capture).systemLogs().kernelCursor());
        assertTrue(session(capture).systemLogs().reasons().isEmpty(), "A disconnected command retries; it is not a missing executable");
      }
    }
  }
}
