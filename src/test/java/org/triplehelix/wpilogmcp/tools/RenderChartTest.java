/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.*;
import org.triplehelix.wpilogmcp.log.LogManager;

class RenderChartTest {
  @TempDir Path temp;
  Path log; Set<Path> allowed;
  final LogManager manager = LogManager.getInstance();
  final RenderChartTool tool = new RenderChartTool();
  @BeforeEach void setup() throws Exception {
    temp = temp.toRealPath(); log = temp.resolve("2026-chart.wpilog");
    allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    try (var w = new FixtureWriter(log, "")) {
      for (int t = 0; t < 10; t++) {
        w.dbl("/VoltageVolts", t, 2 * t + 1).dbl("/CurrentAmps", t, 3 * t);
        w.struct("/Pose", WpiStructs.POSE2D, t, WpiStructs.pose2d(t, t / 2., 0));
        w.bool("/DriverStation/Enabled", t, t >= 2 && t < 8);
      }
      w.dbl("NT:/Held", 0, 1).dbl("NT:/Held", 1, 2).dbl("NT:/Held", 9, 3);
    }
  }
  @AfterEach void cleanup() throws Exception { manager.release(temp); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  JsonObject args(String json) { var args = JsonParser.parseString(json).getAsJsonObject(); args.addProperty("path", log.toString()); return args; }
  JsonObject call(String json) throws Exception { var result = tool.execute(args(json)).getAsJsonObject(); assertNotEquals("error", result.get("status").getAsString(), result::toString); return result; }
  JsonObject spec(JsonObject result) { return result.getAsJsonObject("chart_spec"); }
  @Test void pngHasTheRequestedPixelsAndNamesEverySeriesWithTheWindowStatistics() throws Exception {
    var result = call("{\"entries\":[\"/VoltageVolts\",\"/CurrentAmps\"],\"start_time\":2,\"end_time\":4,\"width\":641,\"height\":321}");
    var block = ResponseBuilder.contentBlocks(result); assertEquals(2, block.size());
    var json = JsonParser.parseString(block.get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject();
    assertEquals("ok", json.get("status").getAsString()); assertTrue(json.has("inputs")); assertFalse(json.has("_content"));
    var image = block.get(1).getAsJsonObject(); assertEquals("image/png", image.get("mimeType").getAsString());
    var png = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(image.get("data").getAsString())));
    assertNotNull(png); assertEquals(641, png.getWidth()); assertEquals(321, png.getHeight());
    assertEquals(List.of("/VoltageVolts", "/CurrentAmps"), spec(result).getAsJsonArray("series").asList().stream().map(s -> s.getAsJsonObject().get("name").getAsString()).toList());
    for (var value : result.getAsJsonArray("summary")) {
      var summary = value.getAsJsonObject(); var request = args("{\"start_time\":2,\"end_time\":4}"); request.add("name", summary.get("name"));
      var stats = new StatisticsTools.GetStatisticsTool().execute(request).getAsJsonObject();
      for (String field : List.of("count", "min", "max", "mean")) assertEquals(stats.get(field).getAsDouble(), summary.get(field).getAsDouble(), 1e-12, field);
    }
    var first = result.getAsJsonArray("summary").get(0).getAsJsonObject();
    assertEquals(3, first.get("count").getAsInt()); assertEquals(5, first.get("min").getAsDouble()); assertEquals(9, first.get("max").getAsDouble()); assertEquals(7, first.get("mean").getAsDouble());
    assertEquals("V", spec(result).getAsJsonArray("series").get(0).getAsJsonObject().get("unit").getAsString());
    var link = java.net.URI.create(spec(result).get("open_url").getAsString());
    var path = java.net.URLDecoder.decode(link.getRawQuery().split("&")[0].substring("path=".length()), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(log.toString(), path);
    assertTrue(spec(result).get("open_url").getAsString().contains("&start=2.0&end=4.0&kind=time_series"));
  }
  @Test void histogramBinsUseTheStatedIndependentRule() throws Exception {
    var result = call("{\"name\":\"/VoltageVolts\",\"kind\":\"histogram\",\"start_time\":2,\"end_time\":5}");
    var bins = spec(result).getAsJsonArray("series").get(0).getAsJsonObject().getAsJsonArray("bins");
    assertEquals(2, bins.size()); // 5, 7, 9, 11: ceil(sqrt(4)) bins [5,8), [8,11].
    for (int i = 0; i < 2; i++) { var bin = bins.get(i).getAsJsonObject(); assertEquals(5 + 3 * i, bin.get("low").getAsDouble()); assertEquals(8 + 3 * i, bin.get("high").getAsDouble()); assertEquals(2, bin.get("count").getAsInt()); }
    assertTrue(spec(result).get("histogram_rule").getAsString().contains("sqrt"));
  }
  @Test void holdsAreStepsAndPhasesComeOnlyFromTheResolver() throws Exception {
    var result = call("{\"name\":\"NT:/Held\"}");
    assertEquals("step_after", spec(result).getAsJsonArray("series").get(0).getAsJsonObject().get("style").getAsString());
    var enabled = spec(result).getAsJsonArray("phases").asList().stream().map(JsonElement::getAsJsonObject).filter(p -> p.get("state").getAsString().equals("enabled")).findFirst().orElseThrow();
    assertEquals(2, enabled.get("start").getAsDouble()); assertEquals(8, enabled.get("end").getAsDouble());
    log = temp.resolve("2026-guessed.wpilog");
    try (var w = new FixtureWriter(log, "")) { w.bool("/Custom/Enabled", 0, true).bool("/Custom/Enabled", 3, false).dbl("/VoltageVolts", 0, 1).dbl("/VoltageVolts", 3, 2); }
    var unknown = call("{\"name\":\"/VoltageVolts\"}");
    assertTrue(spec(unknown).getAsJsonArray("phases").isEmpty(), "A word in a name does not supply Driver Station state");
    assertTrue(spec(unknown).get("phase_basis").getAsString().contains("no conventional"));
  }
  @Test void scatterIsExplicitExactAlignmentAndFieldUsesTheBundledGeometry() throws Exception {
    var scatter = call("{\"entries\":[\"/VoltageVolts\",\"/CurrentAmps\"],\"kind\":\"scatter\",\"start_time\":2,\"end_time\":4}");
    var pair = spec(scatter).getAsJsonArray("pairs").get(0).getAsJsonObject();
    assertEquals(JsonParser.parseString("[[5,6,2],[7,9,3],[9,12,4]]"), pair.get("points")); assertEquals(3, pair.get("matched").getAsInt());
    assertTrue(spec(scatter).get("alignment_rule").getAsString().contains("exact timestamp"));
    var field = call("{\"name\":\"/Pose\",\"kind\":\"field\",\"start_time\":2,\"end_time\":4}");
    assertEquals(JsonParser.parseString("[[2,1,2],[3,1.5,3],[4,2,4]]"), spec(field).getAsJsonArray("pairs").get(0).getAsJsonObject().get("points"));
    assertEquals(16.54, spec(field).getAsJsonObject("field_geometry").get("field_length_m").getAsDouble());
    assertTrue(spec(field).get("field_source").getAsString().contains("2026"));
  }
  @Test void boundsEmptyWindowAndUnavailableImagingAreExplained() throws Exception {
    for (String bad : List.of("\"width\":159", "\"height\":1e99", "\"width\":4096,\"height\":4096", "\"width\":200.5", "\"limit\":0", "\"offset\":-1", "\"max_points\":0")) {
      var result = tool.execute(args("{\"name\":\"/VoltageVolts\"," + bad + "}")).getAsJsonObject(); assertEquals("error", result.get("status").getAsString(), result::toString); assertFalse(result.toString().contains("Internal error"));
    }
    assertEquals("no_match", call("{\"name\":\"/VoltageVolts\",\"start_time\":100,\"end_time\":110}").get("status").getAsString());
    var noToolkit = new RenderChartTool(spec -> { throw new java.awt.HeadlessException("fixture"); });
    var result = noToolkit.execute(args("{\"name\":\"/VoltageVolts\"}")).getAsJsonObject();
    assertEquals("partial", result.get("status").getAsString()); assertFalse(result.has("_content"));
    for (RenderChartTool.Renderer absent : List.<RenderChartTool.Renderer>of(
        spec -> { throw new NoClassDefFoundError("java/awt/image/BufferedImage"); },
        spec -> { throw new java.io.IOException("no PNG writer"); })) {
      var skipped = new RenderChartTool(absent).execute(args("{\"name\":\"/VoltageVolts\"}")).getAsJsonObject();
      assertEquals("partial", skipped.get("status").getAsString()); assertTrue(skipped.has("summary")); assertTrue(skipped.has("skipped"));
    }
    assertTrue(result.has("summary")); assertTrue(result.has("inputs")); assertTrue(result.get("skipped").toString().contains("Headless imaging is unavailable"));
  }
  @Test void drawingPagingAndBucketsNeverChangeTheFullWindowSummary() throws Exception {
    var paged = call("{\"name\":\"/VoltageVolts\",\"start_time\":2,\"end_time\":5,\"limit\":1,\"offset\":1}");
    var s = spec(paged).getAsJsonArray("series").get(0).getAsJsonObject();
    assertEquals(JsonParser.parseString("[[3,7]]"), s.get("points")); assertEquals(3, s.getAsJsonObject("limits").getAsJsonObject("points").get("total").getAsInt());
    assertEquals(4, paged.getAsJsonArray("summary").get(0).getAsJsonObject().get("count").getAsInt());
    var buckets = call("{\"name\":\"/VoltageVolts\",\"start_time\":2,\"end_time\":5,\"max_points\":2}");
    assertEquals(2, spec(buckets).getAsJsonArray("series").get(0).getAsJsonObject().getAsJsonArray("buckets").size());
    assertEquals(4, buckets.getAsJsonArray("summary").get(0).getAsJsonObject().get("count").getAsInt());
    var bucketPage = call("{\"name\":\"/VoltageVolts\",\"start_time\":2,\"end_time\":5,\"max_points\":2,\"offset\":1,\"limit\":1}");
    var page = spec(bucketPage).getAsJsonArray("series").get(0).getAsJsonObject();
    assertEquals(1, page.getAsJsonArray("buckets").size(), "offset and limit page buckets as read_entry does");
    assertEquals(4, bucketPage.getAsJsonArray("summary").get(0).getAsJsonObject().get("count").getAsInt());
  }
  @Test void defaultDrawingCoversAllFiveThousandSamplesWithPixelBuckets() throws Exception {
    log = temp.resolve("dense.wpilog");
    try (var w = new FixtureWriter(log, "")) {
      for (int i = 0; i < 5000; i++) w.dbl("/Ramp", i, i);
    }
    var result = call("{\"name\":\"/Ramp\"}");
    var series = spec(result).getAsJsonArray("series").get(0).getAsJsonObject();
    assertTrue(series.has("buckets"), "A default chart must cover the whole window, not its first page");
    var buckets = series.getAsJsonArray("buckets");
    assertEquals(0, buckets.get(0).getAsJsonObject().get("start").getAsDouble());
    assertEquals(4999, buckets.get(buckets.size() - 1).getAsJsonObject().get("end").getAsDouble());
    assertEquals(5000, buckets.asList().stream().mapToInt(b -> b.getAsJsonObject().get("count").getAsInt()).sum());
    assertEquals(960 - 76, buckets.size(), "One min/max bucket per plot pixel column");
    assertEquals("buckets", series.getAsJsonObject("drawn").get("mode").getAsString());
    assertEquals(buckets.size(), series.getAsJsonObject("drawn").get("count").getAsInt());
    assertEquals(5000, result.getAsJsonArray("summary").get(0).getAsJsonObject().get("count").getAsInt());
    assertEquals(2499.5, result.getAsJsonArray("summary").get(0).getAsJsonObject().get("mean").getAsDouble());
  }
  @Test void unequalScatterTimesLeaveUnpairedSamplesOnBothSides() throws Exception {
    log = temp.resolve("unequal.wpilog");
    try (var w = new FixtureWriter(log, "")) {
      for (double t : new double[] {2, 3, 4}) w.dbl("/x", t, t * 2);
      for (double t : new double[] {2, 3.5, 4}) w.dbl("/y", t, t * 3);
    }
    var pair = spec(call("{\"entries\":[\"/x\",\"/y\"],\"kind\":\"scatter\"}")).getAsJsonArray("pairs").get(0).getAsJsonObject();
    assertEquals(JsonParser.parseString("[[4,6,2],[8,12,4]]"), pair.get("points"));
    assertEquals(2, pair.get("matched").getAsInt());
    assertEquals(1, pair.get("unpaired_x").getAsInt()); assertEquals(1, pair.get("unpaired_y").getAsInt());
  }
  @Test void linkRepeatsAndEncodesEveryEntry() throws Exception {
    log = temp.resolve("names.wpilog");
    var names = List.of("/one space", "/two\"quote", "/three&+?");
    try (var w = new FixtureWriter(log, "")) { for (var name : names) w.dbl(name, 0, 1); }
    var request = new JsonObject(); request.add("entries", new Gson().toJsonTree(names));
    var link = spec(call(request.toString())).get("open_url").getAsString();
    var encoded = Arrays.stream(java.net.URI.create(link).getRawQuery().split("&"))
        .filter(p -> p.startsWith("entries=")).map(p -> p.substring(8)).toList();
    assertEquals(List.of("%2Fone+space", "%2Ftwo%22quote", "%2Fthree%26%2B%3F"), encoded);
  }
  @Test void aFontConfigurationErrorSkipsOnlyTheImage() throws Exception {
    var result = new RenderChartTool(s -> { throw new InternalError("Fontconfig head is null"); })
        .execute(args("{\"name\":\"/VoltageVolts\"}")).getAsJsonObject();
    assertEquals("partial", result.get("status").getAsString(), result::toString);
    assertTrue(result.get("skipped").toString().contains("InternalError"));
    assertTrue(result.has("summary")); assertTrue(result.has("inputs")); assertFalse(result.has("_content"));
  }
  @Test void phasesAreClippedAtBothWindowEdges() throws Exception {
    var phases = spec(call("{\"name\":\"/VoltageVolts\",\"start_time\":3,\"end_time\":4}" )).getAsJsonArray("phases");
    assertEquals(1, phases.size()); var phase = phases.get(0).getAsJsonObject();
    assertEquals("enabled", phase.get("state").getAsString());
    assertEquals(3, phase.get("start").getAsDouble()); assertEquals(4, phase.get("end").getAsDouble()); assertEquals(1, phase.get("duration").getAsDouble());
  }

}
