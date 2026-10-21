/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * A replay's run folder under {@code build/}. A run that passes deletes its folder; a failed one
 * keeps it for diagnosis until the suite runs again, and the Gradle task that runs the suite
 * empties the suite's folder before its tests start. So a checkout holds at most the last run's
 * failures. Before, three replay suites never deleted a run, the fourth left the fake roboRIO
 * written beside each run, and one checkout's replay folders grew past 500 GiB.
 */
final class ReplayScratch {
  private ReplayScratch() {}

  /** A new run folder in {@code folder}, which is created if missing. */
  static Path create(Path folder, String prefix) throws IOException {
    return Files.createTempDirectory(Files.createDirectories(folder), prefix).toAbsolutePath();
  }

  /**
   * Deletes a passing run's folder, as far as it can. A file that cannot go (Windows refuses one
   * that is still mapped) is left for the next run's emptying: cleanup never fails a replay that
   * passed. Links are deleted, never followed.
   */
  static void delete(Path run) {
    long left = 0;
    try (var paths = Files.walk(run)) {
      for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
        try {
          Files.deleteIfExists(path);
        } catch (IOException held) {
          left++;
        }
      }
    } catch (IOException | UncheckedIOException unreadable) {
      left++;
    }
    if (left > 0) System.out.println(run + ": " + left + " paths left for the next run to empty");
  }
}
