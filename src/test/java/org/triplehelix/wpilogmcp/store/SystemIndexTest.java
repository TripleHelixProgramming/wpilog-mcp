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
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.pull.FakeRobot;
import org.triplehelix.wpilogmcp.capture.pull.SystemPullPass;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.sync.FileTransfer;
import org.triplehelix.wpilogmcp.tools.SearchSystemLogsTool;

class SystemIndexTest {
  @TempDir Path temp;
  @Test void fiftySessionsAndFiveRotationsHaveFiveSharedReceiptsAndSearchUsesTheirSpan() throws Exception {
    var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    try {
      var root = temp.toRealPath().resolve("store"); Path current = null;
      for (int i = 0; i < 50; i++) current = create(root, "session-" + i, i == 49, i + 1);
      var store = manager.stores().store(root); var local = store.systemPulls(FakeRobot.device(SERIAL, "SHA256:fixture"), WALL);
      assertTrue(local.beginPass()); var remote = new FakeRobot(); var sources = new java.util.HashMap<String, String>();
      for (int i = 0; i < 5; i++) {
        String name = "/var/log/messages." + i;
        remote.files.put(name, ((i == 0 ? "2026-03-07T14:22:38Z" : "2025-01-01T00:00:00Z") + " warning rotation-" + i + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)); sources.put(name, "syslog");
      }
      local.sources(sources); var clock = new java.util.concurrent.atomic.AtomicLong(); var transfer = new FileTransfer(remote, local, local.manifest(), Long.MAX_VALUE, clock::get, () -> true);
      for (int i = 0; i < 30; i++) { var r = transfer.step(); clock.addAndGet(Math.max(1, r.waitUs())); if (r.status() == FileTransfer.Status.IDLE) break; assertNotEquals(FileTransfer.Status.REFUSED, r.status(), r.detail()); }
      for (var s : StoreCatalog.readManaged(root, manager.testGetSecurityValidator()).sessions()) {
        assertTrue(s.session().systemLogs().files().isEmpty(), "Shared syslog must not rewrite every session");
      }
      var index = com.google.gson.JsonParser.parseString(Files.readString(root.resolve("robots").resolve(SERIAL).resolve("system/index.json"))).getAsJsonObject();
      assertEquals(5, index.getAsJsonArray("files").size());
      var args = new com.google.gson.JsonObject(); args.addProperty("path", current.toString()); args.addProperty("source", "syslog");
      var found = new SearchSystemLogsTool().execute(args).getAsJsonObject();
      assertEquals("ok", found.get("status").getAsString(), found::toString); assertEquals(1, found.get("total_matches").getAsInt());
      assertEquals("2026-03-07T14:22:38Z warning rotation-0", found.getAsJsonArray("matches").get(0).getAsJsonObject().get("text").getAsString());
      assertEquals(15., found.getAsJsonArray("matches").get(0).getAsJsonObject().get("timestamp_sec").getAsDouble());
    } finally { manager.release(temp); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  }
  @Test void theFirstJournalCommandUsesJournalctlsOwnCurrentBoot() {
    String command = SystemPullPass.command("journal", null, null);
    assertTrue(command.contains("journalctl -q --no-pager -o short-unix --show-cursor -b;"), command);
    assertTrue(command.contains("/proc/sys/kernel/random/boot_id"), "The header still supplies the manifest's boot identity");
  }
  @Test void legacySharedReceiptsStayManagedAndTheIndexCanAdvanceTheirPrefix() throws Exception {
    var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    try {
      var root = temp.toRealPath().resolve("store"); var capture = create(root, "legacy", false, 1);
      var io = new StoreFiles(root, manager.testGetSecurityValidator()); var robot = root.resolve("robots").resolve(SERIAL);
      var text = robot.resolve("system/legacy.txt"); Files.createDirectories(text.getParent());
      Files.writeString(text, "2026-03-07T14:22:38Z warning first\n");
      var receipt = new SystemLogState.File(SystemLogState.Location.STORE, StoreFiles.relative(root, text), "syslog", "text", StoreFiles.hash(text), Files.size(text),
          new StoreManifest.Provenance("pulled", "/var/log/messages", "messages", WALL.instant().toString(), false, SERIAL), null);
      var manifest = capture.resolveSibling("session.json"); var session = io.read(manifest, StoreManifest.Session.class);
      io.write(manifest, session.withSystemLogs(SystemLogState.EMPTY.withFiles(List.of(receipt))));
      byte[] before = Files.readAllBytes(manifest);
      assertFalse(StoreCatalog.read(root, manager.testGetSecurityValidator()).unmanaged().contains(text), "A legacy shared receipt is still catalog membership");
      var args = new com.google.gson.JsonObject(); args.addProperty("path", capture.toString());
      assertEquals(1, new SearchSystemLogsTool().execute(args).getAsJsonObject().get("total_matches").getAsInt());
      Files.writeString(text, "2026-03-07T14:22:39Z warning next\n", java.nio.file.StandardOpenOption.APPEND);
      var current = new SystemLogState.File(receipt.location(), receipt.path(), receipt.source(), receipt.format(), StoreFiles.hash(text), Files.size(text), receipt.provenance(), null);
      SystemLogIndex.put(io, robot, new SystemLogIndex.Entry(current, SystemLogIndex.span(text, "text")));
      assertEquals(2, new SearchSystemLogsTool().execute(args).getAsJsonObject().get("total_matches").getAsInt(), "The robot index owns the newer committed prefix");
      assertArrayEquals(before, Files.readAllBytes(manifest), "Reading or indexing shared text never rewrites legacy receipts");
      var writes = new java.util.concurrent.atomic.AtomicInteger();
      var counting = new StoreFiles(root, manager.testGetSecurityValidator(), (p, v) -> writes.incrementAndGet());
      var provenance = current.provenance();
      var seenAgain = new SystemLogState.File(current.location(), current.path(), current.source(), current.format(), current.sha256(), current.sizeBytes(),
          new StoreManifest.Provenance(provenance.kind(), provenance.originalPath(), provenance.originalName(), WALL.instant().plusSeconds(6).toString(), false, SERIAL), null);
      SystemLogIndex.put(counting, robot, new SystemLogIndex.Entry(seenAgain, SystemLogIndex.span(text, "text")));
      assertEquals(0, writes.get(), "An unchanged content check must not replace index.json every pass");
    } finally { manager.release(temp); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  }
}
