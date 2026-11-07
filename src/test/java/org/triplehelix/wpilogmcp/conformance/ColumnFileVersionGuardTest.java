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

/**
 * A column file kept in a user's disk cache must be invalidated when what it holds for the same
 * log would change: its layout, the decode its values come from, or how it is read back. Each
 * change to these sources records whether files already written still give the same answers.
 */
class ColumnFileVersionGuardTest {
  private static final Path SOURCE = Path.of("src/main/java");
  private static final String PACKAGE = "org/triplehelix/wpilogmcp/";
  private static final Path BASELINE = Path.of("src/test/resources/conformance/column-file-inputs.json");
  private static final List<String> INPUTS = List.of(
      "columns/ColumnFileFormat.java", "columns/ColumnFileWriter.java", "columns/ColumnFile.java", "columns/Packed.java",
      "columns/StructLeaves.java",
      "log/subsystems/EntryDecoder.java", "log/subsystems/BinaryReader.java", "log/struct/StructSchemas.java",
      "log/struct/CanonicalSchemas.java", "log/struct/StructMap.java", "log/struct/EnumValue.java",
      "log/struct/UnsignedEnumValue.java", "log/LazyParsedLog.java", "log/LogReader.java", "log/LogScan.java",
      "log/RecordOffsets.java", "log/LogBytes.java", "log/MappedLogBytes.java", "log/DecodeProblem.java",
      "read/FieldPath.java", "read/SampleCursors.java", "read/RecordSeries.java");

  @Test void columnFileInputsRequireAnExplicitCacheCompatibilityDecision() throws Exception {
    assertTrue(Files.isRegularFile(BASELINE),
        "Missing column-file input baseline; record source hashes and reviewed format and recipe versions");
    var baseline = JsonParser.parseString(Files.readString(BASELINE)).getAsJsonObject();
    var format = Class.forName("org.triplehelix.wpilogmcp.columns.ColumnFileFormat");
    int formatVersion = format.getField("FORMAT_VERSION").getInt(null);
    int recipeVersion = format.getField("RECIPE_VERSION").getInt(null);
    assertEquals(formatVersion, baseline.get("format_version").getAsInt());
    assertEquals(recipeVersion, baseline.get("recipe_version").getAsInt());
    assertTrue(baseline.get("reason").getAsString().strip().length() >= 20,
        "A column-file baseline update needs a reviewed compatibility explanation");
    java.time.LocalDate.parse(baseline.get("reviewed_at").getAsString());
    String decision = baseline.get("decision").getAsString();
    assertTrue(List.of("initial_baseline", "format_bump", "recipe_bump", "cache_unaffected").contains(decision));
    if (!decision.equals("initial_baseline")) {
      int previousFormat = baseline.get("previous_format_version").getAsInt();
      int previousRecipe = baseline.get("previous_recipe_version").getAsInt();
      assertTrue(formatVersion >= previousFormat && recipeVersion >= previousRecipe,
          "Column-file versions must not go backwards");
      if (decision.equals("format_bump")) assertTrue(formatVersion > previousFormat,
          "A format_bump decision must increase the column file format version");
      else if (decision.equals("recipe_bump")) assertTrue(recipeVersion > previousRecipe,
          "A recipe_bump decision must increase the column file recipe version");
      else {
        assertEquals(previousFormat, formatVersion);
        assertEquals(previousRecipe, recipeVersion);
      }
    }
    var paths = new TreeSet<String>();
    INPUTS.forEach(relative -> paths.add(PACKAGE + relative));
    paths.add("edu/wpi/first/util/datalog/DataLogAccess.java");
    var expected = baseline.getAsJsonObject("source_sha256");
    assertEquals(paths, expected.keySet(), "Column-file source coverage changes require a compatibility review");
    var changed = new java.util.ArrayList<String>();
    for (var relative : paths) {
      byte[] bytes = Files.readString(SOURCE.resolve(relative)).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
      String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      if (!expected.get(relative).getAsString().equals(actual)) changed.add(relative);
    }
    assertTrue(changed.isEmpty(), () -> "Column-file inputs changed: " + changed
        + ". Raise FORMAT_VERSION or RECIPE_VERSION when column files would hold or read differently, or "
        + "record why they do not; update the reviewed baseline.");
  }
}
