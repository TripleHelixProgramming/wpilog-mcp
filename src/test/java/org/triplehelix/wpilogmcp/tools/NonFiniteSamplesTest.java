/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

/**
 * Raw samples that are NaN or infinite, as vision code logs them when no target is seen (found
 * on other teams' published logs: read_entry failed with a Gson message, and the evidence
 * sentence counted the NaN samples).
 */
class NonFiniteSamplesTest extends ToolTestBase {

  @Override
  protected void registerTools(ToolRegistry registry) {
    WpilogTools.registerAll(registry);
  }

  @Test
  @DisplayName("read_entry returns NaN and infinite samples as strings, not an error")
  void readEntryReturnsNonFiniteSamples() throws Exception {
    var log = new MockLogBuilder().setPath("/mock/nan_samples.wpilog")
        .addNumericEntry("/Vision/PoseY", new double[] {0, 1, 2, 3},
            new double[] {1.5, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
        .build();
    putLogInCache(log);
    var args = new JsonObject();
    args.addProperty("path", log.path());
    args.addProperty("name", "/Vision/PoseY");
    var r = findTool("read_entry").execute(args).getAsJsonObject();
    assertEquals("ok", r.get("status").getAsString(), r.toString());
    var samples = r.getAsJsonArray("samples");
    assertEquals(4, samples.size());
    assertEquals(1.5, samples.get(0).getAsJsonObject().get("value").getAsDouble());
    assertEquals("NaN", samples.get(1).getAsJsonObject().get("value").getAsString());
    assertEquals("Infinity", samples.get(2).getAsJsonObject().get("value").getAsString());
    assertEquals("-Infinity", samples.get(3).getAsJsonObject().get("value").getAsString());
    // The entry's first and last sample are shown the same way
    var info = findTool("get_entry_info").execute(args).getAsJsonObject();
    assertEquals("ok", info.get("status").getAsString(), info.toString());
  }

  @Test
  @DisplayName("non-finite numbers inside arrays and struct fields become strings too")
  void nestedNonFiniteValues() {
    var array = ToolUtils.sampleToJson(new double[] {1.0, Double.NaN});
    assertEquals("[1.0,\"NaN\"]", array.toString());
    var struct = ToolUtils.sampleToJson(Map.of("x", Double.NEGATIVE_INFINITY));
    assertEquals("{\"x\":\"-Infinity\"}", struct.toString());
    assertEquals("12", ToolUtils.sampleToJson(12L).toString());
    assertEquals("\"text\"", ToolUtils.sampleToJson("text").toString());
  }

  @Test
  @DisplayName("the evidence sentence counts the finite samples the statistics rest on")
  void sampleContextCountsFiniteSamples() throws Exception {
    // 300 samples, 275 of them NaN: the statistics come from 25
    var values = new ArrayList<TimestampedValue>();
    var times = new double[300];
    var data = new double[300];
    for (int i = 0; i < 300; i++) {
      times[i] = i * 0.02;
      data[i] = i < 25 ? i : Double.NaN;
      values.add(new TimestampedValue(times[i], data[i]));
    }
    var quality = DataQuality.fromValues(values);
    var context = AnalysisDirectives.fromQuality(quality).toJson().get("sample_context").getAsString();
    assertTrue(context.startsWith("Based on 25 finite samples of 300 (275 NaN or infinite)"), context);
    var guidance = AnalysisDirectives.fromQuality(quality).toJson().getAsJsonArray("interpretation_guidance").toString();
    assertTrue(guidance.contains("Low sample count (25 finite)"), guidance);

    var log = new MockLogBuilder().setPath("/mock/mostly_nan.wpilog")
        .addNumericEntry("/Vision/EstimateAge", times, data).build();
    putLogInCache(log);
    var args = new JsonObject();
    args.addProperty("path", log.path());
    args.addProperty("name", "/Vision/EstimateAge");
    var stats = findTool("get_statistics").execute(args).getAsJsonObject();
    assertEquals(25, stats.get("count").getAsInt(), stats.toString());
    var directives = stats.getAsJsonObject("server_analysis_directives");
    assertTrue(directives.get("sample_context").getAsString().startsWith("Based on 25 finite samples of 300"),
        directives.toString());
    assertNotEquals("high", directives.get("confidence_level").getAsString());
  }
}
