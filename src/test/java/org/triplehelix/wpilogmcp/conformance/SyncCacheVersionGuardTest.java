/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.cache.SyncCacheSerializer;

/** Cached REV answers must not outlive the code which interpreted their samples and clocks. */
class SyncCacheVersionGuardTest {
  private static final Path SOURCE = Path.of("src/main/java");
  private static final String PACKAGE = "org/triplehelix/wpilogmcp/";
  private static final Path BASELINE = Path.of("src/test/resources/conformance/sync-cache-inputs.json");
  private static final List<String> EXTRA = List.of(
      "cache/SyncCacheSerializer.java", "cache/SyncDiskCache.java", "cache/ContentFingerprint.java",
      "log/WallClock.java", "log/TimestampedValue.java", "log/LogData.java", "log/EntryInfo.java",
      "log/LogScan.java", "log/LogReader.java", "log/RecordOffsets.java", "log/LazyParsedLog.java",
      "log/ParsedLog.java", "log/LogManager.java", "log/LogDirectory.java", "log/LiveLog.java",
      "log/LogBytes.java", "log/MappedLogBytes.java", "log/ScopedLogReader.java", "log/FileSnapshot.java",
      "log/subsystems/EntryDecoder.java", "log/subsystems/LogParser.java",
      "read/LegacyMaterializer.java", "read/SampleCursors.java", "read/RecordSeries.java", "read/TimeWindows.java",
      "sync/LogSynchronizer.java", "sync/SignalMatcher.java", "sync/SynchronizedLogs.java",
      "sync/SyncResult.java", "sync/SyncMethod.java", "sync/SignalPair.java", "sync/SignalPairResult.java",
      "sync/ConfidenceLevel.java", "sync/Combination.java");

  @Test void synchronizationInputsRequireAnExplicitCacheCompatibilityDecision() throws Exception {
    assertTrue(Files.isRegularFile(BASELINE),
        "Missing synchronization cache input baseline; record source hashes and the reviewed format version");
    var baseline = JsonParser.parseString(Files.readString(BASELINE)).getAsJsonObject();
    assertEquals(SyncCacheSerializer.CURRENT_FORMAT_VERSION, baseline.get("format_version").getAsInt(),
        "Refresh the reviewed input baseline when raising the cache format version");
    assertTrue(baseline.get("reason").getAsString().strip().length() >= 20,
        "An input baseline update needs an explanation of why cached answers remain valid or a format bump");
    java.time.LocalDate.parse(baseline.get("reviewed_at").getAsString());
    String decision = baseline.get("decision").getAsString();
    assertTrue(List.of("initial_baseline", "format_bump", "cache_unaffected").contains(decision));
    if (!decision.equals("initial_baseline")) {
      int previous = baseline.get("previous_format_version").getAsInt();
      if (decision.equals("format_bump")) assertTrue(SyncCacheSerializer.CURRENT_FORMAT_VERSION > previous,
          "A format_bump decision must increase the synchronization cache format");
      else assertEquals(SyncCacheSerializer.CURRENT_FORMAT_VERSION, previous,
          "cache_unaffected explains a source-only compatibility decision");
    }
    var paths = new TreeSet<String>();
    EXTRA.forEach(relative -> paths.add(PACKAGE + relative));
    paths.add("edu/wpi/first/util/datalog/DataLogAccess.java");
    try (var sources = Files.walk(SOURCE.resolve(PACKAGE + "revlog"))) {
      sources.filter(p -> p.toString().endsWith(".java")).forEach(p ->
          paths.add(SOURCE.relativize(p).toString().replace('\\', '/')));
    }
    var expected = baseline.getAsJsonObject("source_sha256");
    assertEquals(paths, expected.keySet(), "Adding or removing a REV input source requires a compatibility review");
    var changed = new java.util.ArrayList<String>();
    for (var relative : paths) {
      byte[] bytes = Files.readString(SOURCE.resolve(relative)).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
      String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      if (!expected.get(relative).getAsString().equals(actual)) changed.add(relative);
    }
    assertTrue(changed.isEmpty(), () -> "Synchronization inputs changed: " + changed
        + ". Raise SyncCacheSerializer.CURRENT_FORMAT_VERSION when cached answers change, or record why they do not; update the reviewed baseline.");
  }
}
