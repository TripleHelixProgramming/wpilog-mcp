/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.tools.LiveTools;

/** Provider reasons can explain healthy activity and pacing, not only unavailable data. */
class ProviderReasonDescriptionTest {
  @ParameterizedTest
  @CsvSource({"tool,current activity", "tool,sampling-budget", "reference,current activity", "reference,sampling-budget"})
  void providerReasonExplainsActivityAndSamplingBudget(String source, String meaning) throws Exception {
    String description;
    if (source.equals("tool")) {
      var registry = new ToolRegistry(); LiveTools.registerAll(registry, null);
      var text = registry.getTool("list_sessions").description();
      description = text.substring(text.indexOf("providers[] reports"), text.indexOf("last_round_trip_ms"));
    } else {
      var text = Files.readString(Path.of("doc", "TOOLS.md"));
      description = text.substring(text.indexOf("`providers[]` contains")).split("\\R\\s*\\R", 2)[0];
    }
    assertTrue(description.contains(meaning), source + " provider reason must explain " + meaning + ": " + description);
  }
}
