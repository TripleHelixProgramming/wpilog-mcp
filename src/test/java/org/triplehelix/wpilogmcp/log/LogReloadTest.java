/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.fixtures.FixtureWriter;
import org.triplehelix.wpilogmcp.mcp.McpSession;
import org.triplehelix.wpilogmcp.mcp.SessionContext;
import org.triplehelix.wpilogmcp.sync.SyncMethod;
import org.triplehelix.wpilogmcp.sync.SyncResult;
import org.triplehelix.wpilogmcp.sync.SynchronizedLogs;
import org.triplehelix.wpilogmcp.tools.LogRequiringTool;
import org.triplehelix.wpilogmcp.tools.ToolDependencies;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.SchemaBuilder;

/**
 * A loaded log follows its file: a file copied off the robot again once it has grown is loaded
 * again, a result read while the file was being replaced is discarded, a read that faults
 * because the file was truncated under its mapping is an explained error, each session is told
 * once, and a REV log copied in later is found. Before this, a loaded log answered from its first
 * load for as long as it stayed in memory, applied its old record offsets to a file overwritten
 * in place, and a faulting read ended a stdio server.
 */
@DisplayName("logs that change after loading")
class LogReloadTest {

  private static final String ENTRY = "/Drive/Speed";

  private final LogManager manager = new LogManager();

  @AfterEach
  void shutDown() {
    SessionContext.clear();
    manager.shutdown();
  }

  // ==================== reloading ====================

  @Test
  @DisplayName("a file overwritten in place (same inode) is loaded again on the next call")
  void overwrittenInPlace(@TempDir Path dir) throws Exception {
    var log = dir.resolve("overwritten.wpilog");
    writeLog(log, 10.0);
    age(log);
    manager.addAllowedDirectory(dir);

    var first = manager.getOrLoad(log.toString());
    assertEquals(10.0, first.maxTimestamp(), 1e-6);
    assertSame(first, manager.getOrLoad(log.toString()), "unchanged file: the same log");

    var longer = dir.resolve("longer.tmp");
    writeLog(longer, 20.0);
    overwriteInPlace(log, longer);

    var second = manager.getOrLoad(log.toString());
    assertNotSame(first, second, "the changed file was not loaded again");
    assertEquals(20.0, second.maxTimestamp(), 1e-6);
    assertEquals(1001, second.sampleCount(ENTRY), "the new file's records");
    assertSame(second, manager.getOrLoad(log.toString()), "reloaded once, then cached");
  }

  @Test
  @DisplayName("a file renamed into place (new inode) is loaded again on the next call")
  void renamedIntoPlace(@TempDir Path dir) throws Exception {
    var log = dir.resolve("renamed.wpilog");
    writeLog(log, 10.0);
    age(log);
    manager.addAllowedDirectory(dir);
    var first = manager.getOrLoad(log.toString());

    var longer = dir.resolve("longer.tmp");
    writeLog(longer, 20.0);
    Files.move(longer, log, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

    var second = manager.getOrLoad(log.toString());
    assertNotSame(first, second, "the replaced file was not loaded again");
    assertEquals(20.0, second.maxTimestamp(), 1e-6);
  }

  @Test
  @DisplayName("a loaded log whose file was removed is an error, not the old data")
  void removed(@TempDir Path dir) throws Exception {
    var log = dir.resolve("removed.wpilog");
    writeLog(log, 10.0);
    manager.addAllowedDirectory(dir);
    manager.getOrLoad(log.toString());

    Files.delete(log);
    var e = assertThrows(LogFileException.class, () -> manager.getOrLoad(log.toString()));
    assertTrue(e.getMessage().contains("File not found"), e.getMessage());
    assertFalse(manager.testIsLogLoaded(log.toString()), "the old log stayed loaded");
  }

  // ==================== a change during a call ====================

  @Test
  @DisplayName("a result read while the file changed is discarded with an explained error, and "
      + "the next call loads the new file")
  void changedDuringCall(@TempDir Path dir) throws Exception {
    var log = dir.resolve("changing.wpilog");
    writeLog(log, 10.0);
    age(log);
    manager.addAllowedDirectory(dir);
    var longer = dir.resolve("longer.tmp");
    writeLog(longer, 20.0);

    var tool = new CountingTool(manager, () -> overwriteInPlace(log, longer));
    var result = tool.execute(args(log)).getAsJsonObject();
    assertEquals("error", result.get("status").getAsString(), result.toString());
    var error = result.get("error").getAsString();
    assertTrue(error.contains("changed on disk while this call was reading it"), error);
    assertTrue(error.contains("size went from"), error);

    // Not left loaded from the old file: the next call reads the new one
    var next = new CountingTool(manager, () -> { }).execute(args(log)).getAsJsonObject();
    assertEquals("ok", next.get("status").getAsString(), next.toString());
    assertEquals(1001, next.get("count").getAsInt());
  }

  @Test
  @DisplayName("a faulting read of the mapped file is an explained error that unloads the log")
  void faultingRead(@TempDir Path dir) throws Exception {
    var log = dir.resolve("faulting.wpilog");
    writeLog(log, 10.0);
    manager.addAllowedDirectory(dir);
    var loaded = manager.getOrLoad(log.toString());

    var tool = new CountingTool(manager, () -> {
      // What the JVM throws when a mapped page is gone: the file was truncated under it
      throw new InternalError("a fault occurred in a recent unsafe memory access operation in "
          + "compiled Java code");
    });
    var result = tool.execute(args(log)).getAsJsonObject();
    assertEquals("error", result.get("status").getAsString(), result.toString());
    var error = result.get("error").getAsString();
    assertTrue(error.contains("changed on disk while this call was reading it"), error);
    assertTrue(error.contains("read of the file faulted"), error);

    assertNotSame(loaded, manager.getOrLoad(log.toString()), "the faulting log stayed loaded");
  }

  @Test
  @DisplayName("a log truncated under its mapping does not end the server: the call is an error "
      + "and the next call loads what is left")
  void truncatedUnderMapping(@TempDir Path dir) throws Exception {
    // Windows refuses to truncate a mapped file, so the fault cannot happen there
    assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"));
    var log = dir.resolve("truncated.wpilog");
    writeLog(log, 600.0); // ~400 KB: many pages past the cut
    manager.addAllowedDirectory(dir);
    manager.getOrLoad(log.toString());

    // Cut the file once the call holds the loaded log (the check before the call has passed),
    // then read records past the cut through the mapping of the whole original file
    var tool = new CountingTool(manager, () -> {
      try (var channel = FileChannel.open(log, StandardOpenOption.WRITE)) {
        channel.truncate(4096);
      }
    }, CountingTool.NOTHING);
    var result = tool.execute(args(log)).getAsJsonObject();
    assertEquals("error", result.get("status").getAsString(), result.toString());
    var error = result.get("error").getAsString();
    assertTrue(error.contains("changed on disk while this call was reading it"), error);
    assertTrue(error.contains("read of the file faulted"), "the mapped read did not fault: "
        + error);

    var next = new CountingTool(manager, () -> { }).execute(args(log)).getAsJsonObject();
    assertEquals("ok", next.get("status").getAsString(), next.toString());
    assertTrue(next.get("count").getAsInt() < 30001, "the cut file has fewer records");
  }

  // ==================== telling each session once ====================

  @Test
  @DisplayName("a session that used the log is told once that it was reloaded; a session that "
      + "did not is not told")
  void sessionsToldOnce(@TempDir Path dir) throws Exception {
    var log = dir.resolve("told.wpilog");
    writeLog(log, 10.0);
    age(log);
    manager.addAllowedDirectory(dir);
    var tool = new CountingTool(manager, () -> { });
    var a = new McpSession();
    var b = new McpSession();

    // Before the change: session A and the stdio client (no session) use the log
    SessionContext.set(a);
    assertNull(reloadNote(tool.execute(args(log))), "nothing to tell before any reload");
    SessionContext.clear();
    assertNull(reloadNote(tool.execute(args(log))));

    var longer = dir.resolve("longer.tmp");
    writeLog(longer, 20.0);
    overwriteInPlace(log, longer);

    SessionContext.set(a);
    var note = reloadNote(tool.execute(args(log)));
    assertNotNull(note, "session A used the old file and is told of the reload");
    assertTrue(note.get("change").getAsString().contains("size went from"), note.toString());
    assertNull(reloadNote(tool.execute(args(log))), "told once, not on every call");

    SessionContext.set(b);
    assertNull(reloadNote(tool.execute(args(log))),
        "session B never used the old file, so it has nothing to be told");

    SessionContext.clear();
    assertNotNull(reloadNote(tool.execute(args(log))), "the stdio client is told once too");
    assertNull(reloadNote(tool.execute(args(log))));
  }

  @Test
  @DisplayName("the reload notice is a warning as well as metadata")
  void noticeIsAWarning(@TempDir Path dir) throws Exception {
    var log = dir.resolve("warned.wpilog");
    writeLog(log, 10.0);
    age(log);
    manager.addAllowedDirectory(dir);
    var tool = new CountingTool(manager, () -> { });
    tool.execute(args(log));
    var longer = dir.resolve("longer.tmp");
    writeLog(longer, 20.0);
    overwriteInPlace(log, longer);

    var result = tool.execute(args(log)).getAsJsonObject();
    assertEquals("ok", result.get("status").getAsString());
    assertEquals(1001, result.get("count").getAsInt(), "the result is from the new file");
    var warnings = result.getAsJsonArray("warnings");
    assertNotNull(warnings);
    assertTrue(warnings.toString().contains("reloaded from disk"), warnings.toString());
  }

  // ==================== REV logs that change ====================

  @Test
  @DisplayName("a REV log copied in after the wpilog was loaded is found and synchronized")
  void revLogCopiedInLater(@TempDir Path dir) throws Exception {
    var wpilog = FixtureLogs.writeRevlogPair(dir, "2026-later_revlog.wpilog", ZoneOffset.UTC,
        "systemTime");
    var revlog = onlyRevlog(dir);
    var bytes = Files.readAllBytes(revlog);
    Files.delete(revlog);
    manager.addAllowedDirectory(dir);

    manager.getOrLoad(wpilog.toString());
    assertTrue(manager.awaitSyncExecutorIdle(60_000));
    assertEquals(0, manager.getSynchronizedLogs(wpilog.toString()).revlogCount(),
        "no REV log yet");
    assertFalse(manager.refreshRevLogsIfChanged(wpilog.toString()), "nothing changed");

    Files.write(revlog, bytes);
    manager.testForgetRevCheck(wpilog.toString());
    assertTrue(manager.refreshRevLogsIfChanged(wpilog.toString()),
        "the REV log that appeared was not noticed");
    assertTrue(manager.awaitSyncExecutorIdle(60_000));
    var synced = manager.getSynchronizedLogs(wpilog.toString());
    assertEquals(1, synced.revlogCount());
    assertTrue(synced.revlogs().get(0).syncResult().isSuccessful(),
        synced.revlogs().get(0).syncResult().explanation());
  }

  @Test
  @DisplayName("an offset set by hand survives a synchronization run again for a new REV log")
  void userOffsetKept(@TempDir Path dir) throws Exception {
    var wpilog = FixtureLogs.writeRevlogPair(dir, "2026-kept_offset.wpilog", ZoneOffset.UTC,
        "systemTime");
    var revlog = onlyRevlog(dir);
    manager.addAllowedDirectory(dir);
    manager.getOrLoad(wpilog.toString());
    assertTrue(manager.awaitSyncExecutorIdle(60_000));
    assertEquals(1, manager.getSynchronizedLogs(wpilog.toString()).revlogCount());

    // As set_revlog_offset does
    var user = SyncResult.fromUserOffset(123_000L);
    manager.updateSynchronizedLogs(wpilog.toString(), current -> {
      var builder = new SynchronizedLogs.Builder().wpilog(current.wpilog());
      for (var s : current.revlogs()) builder.addRevLog(s.revlog(), user, s.canBusName());
      return builder.build();
    });

    // A second REV log, on another bus, appears
    var second = dir.resolve(revlog.getFileName().toString().replace(".revlog", "_can1.revlog"));
    Files.copy(revlog, second);
    manager.testForgetRevCheck(wpilog.toString());
    assertTrue(manager.refreshRevLogsIfChanged(wpilog.toString()));
    assertTrue(manager.awaitSyncExecutorIdle(60_000));

    var synced = manager.getSynchronizedLogs(wpilog.toString());
    assertEquals(2, synced.revlogCount());
    var kept = synced.revlogs().stream()
        .filter(s -> Path.of(s.revlog().path()).equals(revlog)).findFirst().orElseThrow();
    assertEquals(SyncMethod.USER_PROVIDED, kept.syncResult().method(),
        "the offset set by hand was replaced by a measured one");
    assertEquals(123_000L, kept.syncResult().offsetMicros());
    var added = synced.revlogs().stream()
        .filter(s -> Path.of(s.revlog().path()).equals(second)).findFirst().orElseThrow();
    assertTrue(added.syncResult().isSuccessful(), added.syncResult().explanation());
  }

  // ==================== helpers ====================

  /**
   * A tool that counts an entry's records, running {@code beforeRead} after the log is loaded
   * and before the entry is read, and {@code afterRead} after it.
   */
  static final class CountingTool extends LogRequiringTool {
    interface Step {
      void run() throws Exception;
    }

    static final Step NOTHING = () -> { };

    private final Step beforeRead;
    private final Step afterRead;

    CountingTool(LogManager manager, Step afterRead) {
      this(manager, NOTHING, afterRead);
    }

    CountingTool(LogManager manager, Step beforeRead, Step afterRead) {
      super(new ToolDependencies(manager, null, null, null));
      this.beforeRead = beforeRead;
      this.afterRead = afterRead;
    }

    @Override
    public String name() {
      return "count_records";
    }

    @Override
    public String description() {
      return "Counts an entry's records";
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder().build();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      beforeRead.run();
      int count = log.values().get(ENTRY).size();
      afterRead.run();
      return success().addProperty("count", count).build();
    }
  }

  private static JsonObject args(Path log) {
    var args = new JsonObject();
    args.addProperty("path", log.toString());
    return args;
  }

  private static JsonObject reloadNote(JsonElement result) {
    var object = result.getAsJsonObject();
    assertEquals("ok", object.get("status").getAsString(), object.toString());
    var metadata = object.getAsJsonObject("_metadata");
    return metadata == null || !metadata.has("log_reloaded") ? null
        : metadata.getAsJsonObject("log_reloaded");
  }

  /** A log with one double entry at 50 Hz from 0 to {@code endSec}: (end / 0.02) + 1 records. */
  private static void writeLog(Path path, double endSec) throws IOException {
    try (var w = new FixtureWriter(path, FixtureWriter.AKIT_METADATA)) {
      int n = (int) Math.round(endSec / 0.02) + 1;
      for (int i = 0; i < n; i++) {
        double t = i * 0.02;
        w.dbl(ENTRY, t, Math.sin(t));
      }
    }
  }

  /** Dates the file a minute back, so a change made right after has a different time. */
  private static void age(Path path) throws IOException {
    Files.setLastModifiedTime(path, FileTime.from(Instant.now().minusSeconds(60)));
  }

  /** Writes {@code source}'s bytes over {@code target}, keeping its identity (as cp does). */
  private static void overwriteInPlace(Path target, Path source) throws IOException {
    try (var in = FileChannel.open(source, StandardOpenOption.READ);
        var out = FileChannel.open(target, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING)) {
      long position = 0;
      long size = in.size();
      while (position < size) {
        position += in.transferTo(position, size - position, out);
      }
    }
  }

  private static Path onlyRevlog(Path dir) throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      var revlogs = files.filter(p -> p.toString().endsWith(".revlog")).toList();
      assertEquals(1, revlogs.size(), "one REV log in " + dir);
      return revlogs.get(0);
    }
  }
}
