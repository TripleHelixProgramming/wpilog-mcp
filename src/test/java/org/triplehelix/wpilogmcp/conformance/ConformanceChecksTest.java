/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.conformance.ConformanceChecks.Check;

/** The rules the sweep applies, on hand-written results (review 6, section 6.2). */
@DisplayName("ConformanceChecks")
class ConformanceChecksTest {

  static JsonObject json(String s) {
    return JsonParser.parseString(s).getAsJsonObject();
  }

  @Test
  @DisplayName("labels and echoed arguments are not findings: such a success is silent-empty")
  void labelsAndEchoesAreNotFindings() {
    var args = json("{\"path\": \"/x.wpilog\", \"name\": \"/Motor\", \"threshold\": 6.8}");
    var echoOnly = json("{\"success\": true, \"status\": \"ok\", \"name\": \"/Motor\", "
        + "\"brownout_threshold\": 6.8, \"health_assessment\": \"\", \"maxima_count\": 0, "
        + "\"maxima\": [], \"custom_echo\": 6.8}");
    assertFalse(ConformanceChecks.isSilentEmpty(echoOnly),
        "the plain rule is satisfied by the echoed name alone");
    assertTrue(ConformanceChecks.isSilentEmpty(echoOnly, args),
        "with the arguments known, nothing here is a finding");
    var checks = ConformanceChecks.check(echoOnly, null, false, "find_peaks", args);
    assertTrue(checks.contains(Check.SILENT_EMPTY), checks.toString());
    assertTrue(checks.contains(Check.QUALITY_MISSING), "a statistical tool without data_quality");

    var withFinding = echoOnly.deepCopy();
    withFinding.addProperty("samples_analyzed", 2951);
    assertFalse(ConformanceChecks.isSilentEmpty(withFinding, args),
        "the samples searched are a finding to read the zero against");
  }

  @Test
  @DisplayName("data_quality counts when nested per compared log; a partial result may lack it")
  void qualityNestedOrSkipped() {
    var perLog = json("{\"success\": true, \"status\": \"ok\", \"comparisons\": [{\"statistics\": "
        + "{\"mean\": 1.0, \"data_quality\": {\"sample_count\": 10}}}]}");
    assertFalse(ConformanceChecks.hasQuality(perLog, 1));
    assertTrue(ConformanceChecks.hasQuality(perLog, 2));
    assertFalse(ConformanceChecks.check(perLog, null, false, "compare_matches", new JsonObject())
        .contains(Check.QUALITY_MISSING));

    var partial = json("{\"success\": true, \"status\": \"partial\", \"channel_analysis\": "
        + "[{\"peak_current_A\": 3.0}]}");
    assertFalse(ConformanceChecks.check(partial, null, false, "power_analysis", new JsonObject())
        .contains(Check.QUALITY_MISSING), "a partial result may have skipped its statistics");

    var whole = json("{\"success\": true, \"status\": \"ok\", \"mean\": 1.0}");
    assertTrue(ConformanceChecks.check(whole, null, false, "power_analysis", new JsonObject())
        .contains(Check.QUALITY_MISSING));
    assertFalse(ConformanceChecks.check(whole, null, false, "list_entries", new JsonObject())
        .contains(Check.QUALITY_MISSING), "listings carry no statistics");
  }
}
