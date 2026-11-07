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

/** A persisted scan must be invalidated when its encoding or interpretation recipe changes. */
class ScanIndexVersionGuardTest {
  private static final Path SOURCE = Path.of("src/main/java");
  private static final String PACKAGE = "org/triplehelix/wpilogmcp/";
  private static final Path BASELINE = Path.of("src/test/resources/conformance/scan-index-inputs.json");
  private static final List<String> INPUTS = List.of(
      "cache/ScanIndexSerializer.java", "cache/DiskCache.java", "log/LogManager.java",
      "log/LogScan.java", "log/RecordOffsets.java", "log/LazyParsedLog.java", "log/EntryInfo.java", "log/FileSnapshot.java",
      "log/LogReader.java", "log/LogBytes.java", "log/MappedLogBytes.java", "log/ScopedLogReader.java");

  @Test void scanInputsRequireAnExplicitCacheCompatibilityDecision() throws Exception {
    assertTrue(Files.isRegularFile(BASELINE),
        "Missing scan-index input baseline; record source hashes and reviewed format and recipe versions");
    var baseline = JsonParser.parseString(Files.readString(BASELINE)).getAsJsonObject();
    // Reflection keeps the missing-baseline regression compilable before the new format exists.
    var serializer = Class.forName("org.triplehelix.wpilogmcp.cache.ScanIndexSerializer");
    int format = serializer.getField("FORMAT_VERSION").getInt(null);
    int recipe = serializer.getField("RECIPE_VERSION").getInt(null);
    assertEquals(format, baseline.get("format_version").getAsInt());
    assertEquals(recipe, baseline.get("recipe_version").getAsInt());
    assertTrue(baseline.get("reason").getAsString().strip().length() >= 20,
        "An index baseline update needs a reviewed compatibility explanation");
    java.time.LocalDate.parse(baseline.get("reviewed_at").getAsString());
    String decision = baseline.get("decision").getAsString();
    assertTrue(List.of("initial_baseline", "format_bump", "recipe_bump", "cache_unaffected").contains(decision));
    if (!decision.equals("initial_baseline")) {
      int previousFormat = baseline.get("previous_format_version").getAsInt();
      int previousRecipe = baseline.get("previous_recipe_version").getAsInt();
      assertTrue(format >= previousFormat && recipe >= previousRecipe, "Index versions must not go backwards");
      if (decision.equals("format_bump")) assertTrue(format > previousFormat,
          "A format_bump decision must increase the index format version");
      else if (decision.equals("recipe_bump")) assertTrue(recipe > previousRecipe,
          "A recipe_bump decision must increase the scan recipe version");
      else {
        assertEquals(previousFormat, format);
        assertEquals(previousRecipe, recipe);
      }
    }
    var paths = new TreeSet<String>();
    INPUTS.forEach(relative -> paths.add(PACKAGE + relative));
    paths.add("edu/wpi/first/util/datalog/DataLogAccess.java");
    var expected = baseline.getAsJsonObject("source_sha256");
    assertEquals(paths, expected.keySet(), "Index source coverage changes require a compatibility review");
    var changed = new java.util.ArrayList<String>();
    for (var relative : paths) {
      byte[] bytes = Files.readString(SOURCE.resolve(relative)).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
      String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      if (!expected.get(relative).getAsString().equals(actual)) changed.add(relative);
    }
    assertTrue(changed.isEmpty(), () -> "Scan-index inputs changed: " + changed
        + ". Raise FORMAT_VERSION or RECIPE_VERSION when cached scans change, or record why they do not; update the reviewed baseline.");
  }
}
