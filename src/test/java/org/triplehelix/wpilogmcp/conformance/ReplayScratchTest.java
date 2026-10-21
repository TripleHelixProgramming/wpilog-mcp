/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;

/**
 * The real-log replay suites are opt-in, so their cleanup is checked here, on generated logs: a
 * replay that passes leaves nothing in its scratch folder. Before, the pull and pair replays never
 * deleted a run, and the gateway replay wrote its fake roboRIO beside the run folder, out of reach
 * of the run's delete; together they left hundreds of gigabytes in one checkout's build folder.
 */
class ReplayScratchTest {
  @TempDir Path directory;

  @Test void aPassingGatewayReplayDeletesItsFakeRoboRioToo() throws Exception {
    var log = ReplayPullTest.fixture(directory.resolve("logs"), ReplaySource.Kind.ADVANTAGEKIT);
    // A REV file beside the log is what makes the replay build a fake roboRIO.
    try (var out = new WpilogWriter(log.resolveSibling("REV_20260307_142233.revlog"), "")) {
      int can = out.start("CAN/3/Periodic Status 0", "raw", "", 0);
      for (int i = 0; i < 2000; i++) out.append(can, i * 10_000L, FixtureLogs.sparkStatus0(0, 12, 0, 25, false));
    }
    var scratch = directory.resolve("scratch");
    var counts = RealLogReplayTest.replay(log, scratch);
    assertTrue(RealLogReplayTest.passed(counts), counts.toString());
    assertTrue(counts.containsKey("pull"), "the replay did not build its fake roboRIO: " + counts.keySet());
    assertNothingLeftIn(scratch);
  }

  @Test void aPassingPullReplayDeletesItsRun() throws Exception {
    var log = ReplayPullTest.fixture(directory.resolve("logs"), ReplaySource.Kind.DATALOGMANAGER);
    var scratch = directory.resolve("scratch");
    var report = new StringWriter();
    try (var out = new BufferedWriter(report)) {
      RealReplayPullTest.replay(log, 0, scratch, out);
    } catch (AssertionError failed) {
      throw new AssertionError(report.toString(), failed);
    }
    assertNothingLeftIn(scratch);
  }

  @Test void aPassingPairDeletesItsRun() throws Exception {
    var first = ReplayPullTest.fixture(directory.resolve("first"), ReplaySource.Kind.ADVANTAGEKIT, 0);
    var second = ReplayPullTest.fixture(directory.resolve("second"), ReplaySource.Kind.ADVANTAGEKIT, 1);
    var scratch = directory.resolve("scratch");
    assertEquals(2, RealReplayPairTest.replay(first, second, scratch, false).size());
    assertNothingLeftIn(scratch);
  }

  @Test void aFileThatCannotBeDeletedNeverFailsAPassingRun() throws Exception {
    Assumptions.assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"), "needs POSIX permissions");
    Assumptions.assumeFalse("root".equals(System.getProperty("user.name")), "root ignores directory permissions");
    var run = Files.createDirectories(directory.resolve("run"));
    Files.writeString(run.resolve("capture.wpilog"), "capture");
    var held = Files.createDirectories(run.resolve("held"));
    Files.writeString(held.resolve("file"), "held");
    Files.setPosixFilePermissions(held, PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      ReplayScratch.delete(run);
      assertFalse(Files.exists(run.resolve("capture.wpilog")), "whatever can be deleted is deleted");
      assertTrue(Files.exists(held.resolve("file")), "the held file is left for the next run's emptying");
    } finally {
      Files.setPosixFilePermissions(held, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
  }

  @Test void deletingARunNeverFollowsALinkOutOfIt() throws Exception {
    var outside = Files.createDirectories(directory.resolve("logs"));
    Files.writeString(outside.resolve("match.wpilog"), "a robot log");
    var run = Files.createDirectories(directory.resolve("run"));
    try {
      Files.createSymbolicLink(run.resolve("logs"), outside);
    } catch (UnsupportedOperationException | IOException unavailable) {
      Assumptions.abort("symbolic links unavailable: " + unavailable);
    }
    ReplayScratch.delete(run);
    assertFalse(Files.exists(run, java.nio.file.LinkOption.NOFOLLOW_LINKS));
    assertEquals("a robot log", Files.readString(outside.resolve("match.wpilog")));
  }

  private static void assertNothingLeftIn(Path folder) throws IOException {
    try (var left = Files.list(folder)) {
      assertEquals(List.of(), left.map(path -> path.getFileName().toString()).toList(), "left in " + folder);
    }
  }
}
