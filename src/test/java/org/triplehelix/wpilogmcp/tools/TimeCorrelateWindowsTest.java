/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

/**
 * time_correlate over several windows: what it computes from neighboring samples (the lag-1
 * autocorrelations behind the effective sample size, and the sample rates it compares) comes
 * from samples in the same window. The last sample of one window and the first of the next are
 * not neighbors: the time between them is not in scope.
 */
@DisplayName("time_correlate with several windows")
class TimeCorrelateWindowsTest extends ToolTestBase {

  @Override
  protected void registerTools(ToolRegistry registry) {
    StatisticsTools.registerAll(registry);
  }

  /** Lag-1 autocorrelation by its definition, from the pairs inside each window only. */
  static double lag1Within(double[][] windows) {
    int n = 0;
    double sum = 0;
    for (var w : windows) {
      for (double v : w) {
        sum += v;
        n++;
      }
    }
    double mean = sum / n;
    double squares = 0;
    double products = 0;
    int pairs = 0;
    for (var w : windows) {
      for (int i = 0; i < w.length; i++) {
        squares += (w[i] - mean) * (w[i] - mean);
        if (i + 1 < w.length) {
          products += (w[i] - mean) * (w[i + 1] - mean);
          pairs++;
        }
      }
    }
    // Missing neighbors do not contribute invented products across gaps.
    return products / squares;
  }

  /** 50 Hz from 0 to 40 s: a ramp and a slow sine, logged together. */
  private JsonObject correlate(String windows) throws Exception {
    int n = 2001;
    var t = new double[n];
    var ramp = new double[n];
    var sine = new double[n];
    for (int i = 0; i < n; i++) {
      t[i] = i / 50.0;
      ramp[i] = 10.0 + t[i];
      sine[i] = 0.5 * Math.sin(0.2 * t[i]);
    }
    var log = new MockLogBuilder().setPath("/test/windows.wpilog")
        .addNumericEntry("/Ramp", t, ramp).addNumericEntry("/Sine", t, sine).build();
    putLogInCache(log);
    var args = new JsonObject();
    args.addProperty("path", log.path());
    args.addProperty("name1", "/Ramp");
    args.addProperty("name2", "/Sine");
    args.add("windows", JsonParser.parseString(windows));
    return findTool("time_correlate").execute(args).getAsJsonObject();
  }

  @Test
  @DisplayName("lag-1 autocorrelation pairs only samples in the same window")
  void autocorrelationWithinWindows() throws Exception {
    // five samples at 2.00..2.08 s and five at 20.00..20.08 s
    var r = correlate("[[1.99, 2.09], [19.99, 20.09]]");
    assertEquals("ok", r.get("status").getAsString(), r.toString());
    assertEquals(10, r.get("sample_count").getAsInt());

    var ramp = new double[2][5];
    var sine = new double[2][5];
    for (int k = 0; k < 5; k++) {
      ramp[0][k] = 12.0 + 0.02 * k;
      ramp[1][k] = 30.0 + 0.02 * k;
      sine[0][k] = 0.5 * Math.sin(0.2 * (2.0 + 0.02 * k));
      sine[1][k] = 0.5 * Math.sin(0.2 * (20.0 + 0.02 * k));
    }
    var lag1 = r.getAsJsonObject("lag1_autocorrelation");
    // Worked by hand for the ramp: deviations -9.04..-8.96 and 8.96..9.04 from the mean 21.04;
    // 8 neighbor products average 81.0004; (81.0004 * 8) / 810.008 = 0.7999960. Pairing the
    // last sample of the first window with the first of the second (-8.96 * 8.96) gave 0.7009.
    assertEquals(0.7999960, lag1.get("entry1").getAsDouble(), 1e-6, r.toString());
    assertEquals(lag1Within(ramp), lag1.get("entry1").getAsDouble(), 1e-9);
    assertEquals(lag1Within(sine), lag1.get("entry2").getAsDouble(), 1e-9);

    double product = lag1Within(ramp) * lag1Within(sine);
    assertEquals(Math.min(10.0, 10.0 * (1 - product) / (1 + product)),
        r.get("effective_sample_size").getAsDouble(), 1e-9);
  }

  @Test
  @DisplayName("one window gives the same result as before: all n - 1 pairs are neighbors")
  void oneWindowUnchanged() throws Exception {
    var r = correlate("[[1.99, 2.19]]"); // 2.00..2.18 s: ten samples
    var ramp = new double[1][10];
    for (int k = 0; k < 10; k++) ramp[0][k] = 12.0 + 0.02 * k;
    var values = new ArrayList<Double>();
    for (double v : ramp[0]) values.add(v);
    assertEquals(StatisticsTools.lag1Autocorrelation(values),
        r.getAsJsonObject("lag1_autocorrelation").get("entry1").getAsDouble(), 1e-12);
    assertEquals(lag1Within(ramp), StatisticsTools.lag1Autocorrelation(values), 1e-12);
  }

  @Test
  @DisplayName("sample rates are measured inside the windows, not across the time between them")
  void sampleRatesWithinWindows() throws Exception {
    // /A: 50 Hz throughout. /B: 50 Hz for the first 3 s, then only two samples, either side of
    // the second window. In scope both run at 50 Hz in the first window.
    int n = 2001;
    var ta = new double[n];
    var a = new double[n];
    for (int i = 0; i < n; i++) {
      ta[i] = i / 50.0;
      a[i] = Math.sin(ta[i]);
    }
    var tb = new ArrayList<Double>();
    for (int i = 0; i <= 150; i++) tb.add(i / 50.0);
    tb.add(29.99);
    tb.add(31.01);
    var tbArray = tb.stream().mapToDouble(Double::doubleValue).toArray();
    var b = new double[tbArray.length];
    for (int i = 0; i < b.length; i++) b[i] = Math.cos(tbArray[i]);
    var log = new MockLogBuilder().setPath("/test/rates.wpilog")
        .addNumericEntry("/A", ta, a).addNumericEntry("/B", tbArray, b).build();
    putLogInCache(log);
    var args = new JsonObject();
    args.addProperty("path", log.path());
    args.addProperty("name1", "/A");
    args.addProperty("name2", "/B");
    args.add("windows", JsonParser.parseString("[[0.99, 1.99], [29.995, 30.995]]"));
    var r = findTool("time_correlate").execute(args).getAsJsonObject();
    assertEquals("ok", r.get("status").getAsString(), r.toString());
    assertEquals(100, r.get("sample_count").getAsInt(), r.toString());
    // Measured across the 28 s between the windows, /A looked like 3.3 Hz against /B's 50 Hz
    var warnings = r.has("warnings") ? r.getAsJsonArray("warnings").toString() : "";
    assertFalse(warnings.contains("Sample rate mismatch"), warnings);
  }

  @Test
  @DisplayName("a real tenfold-plus rate difference inside the windows is still reported")
  void realRateMismatchStillReported() throws Exception {
    int n = 2001;
    var ta = new double[n];
    var a = new double[n];
    for (int i = 0; i < n; i++) {
      ta[i] = i / 50.0;
      a[i] = Math.sin(ta[i]);
    }
    // /Slow: 2 Hz throughout
    var ts = new double[81];
    var s = new double[81];
    for (int i = 0; i < ts.length; i++) {
      ts[i] = i / 2.0;
      s[i] = Math.cos(ts[i]);
    }
    var log = new MockLogBuilder().setPath("/test/mismatch.wpilog")
        .addNumericEntry("/A", ta, a).addNumericEntry("/Slow", ts, s).build();
    putLogInCache(log);
    var args = new JsonObject();
    args.addProperty("path", log.path());
    args.addProperty("name1", "/A");
    args.addProperty("name2", "/Slow");
    args.add("windows", JsonParser.parseString("[[0.99, 5.99], [29.99, 34.99]]"));
    var r = findTool("time_correlate").execute(args).getAsJsonObject();
    var warnings = List.of(r.getAsJsonArray("warnings").toString());
    assertTrue(warnings.get(0).contains("Sample rate mismatch (50.0Hz vs 2.0Hz"), warnings.get(0));
  }
}
