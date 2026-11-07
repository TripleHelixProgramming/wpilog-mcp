/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.tools.LiveTools;

/** Provider reasons can explain healthy activity and pacing, not only unavailable data. */
class ProviderReasonDescriptionTest {
  @ParameterizedTest
  @CsvSource({"tool,current activity", "tool,sampling-budget", "reference-lf,current activity",
      "reference-lf,sampling-budget", "reference-crlf,current activity", "reference-crlf,sampling-budget"})
  void providerReasonExplainsActivityAndSamplingBudget(String source, String meaning) throws Exception {
    String description;
    if (source.equals("tool")) {
      var registry = new ToolRegistry(); LiveTools.registerAll(registry, null);
      var text = registry.getTool("list_sessions").description();
      description = text.substring(text.indexOf("providers[] reports"), text.indexOf("last_round_trip_ms"));
    } else {
      var text = Files.readString(Path.of("doc", "TOOLS.md"));
      String ending = source.endsWith("crlf") ? "\r\n" : "\n";
      text = text.replace("\r\n", "\n").replace("\n", ending);
      description = referenceParagraph(text);
    }
    assertTrue(description.contains(meaning), source + " provider reason must explain " + meaning + ": " + description);
  }

  @ParameterizedTest @ValueSource(strings = {"\n", "\r\n"})
  void referenceParagraphKeepsWrappedLinesButStopsAtABlankLine(String ending) {
    String paragraph = "`providers[]` contains current activity," + ending + "sampling-budget";
    String text = "Earlier paragraph" + ending + ending + paragraph + ending + " \t" + ending
        + "A later paragraph must not supply a missing provider claim.";
    assertEquals(paragraph.replace("\r\n", "\n"), referenceParagraph(text));
  }

  private static String referenceParagraph(String text) {
    // Read lines before finding blank ones: a regex can backtrack between CR and LF.
    return text.substring(text.indexOf("`providers[]` contains")).lines()
        .takeWhile(line -> !line.isBlank()).collect(java.util.stream.Collectors.joining("\n"));
  }
}
