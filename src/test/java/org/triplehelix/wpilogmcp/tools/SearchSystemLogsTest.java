/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.triplehelix.wpilogmcp.fixtures.SystemSessionFixture.*;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.store.*;

class SearchSystemLogsTest {
  @TempDir Path temp;
  Path capture;
  java.util.Set<Path> allowed;
  final LogManager manager = LogManager.getInstance();
  final SearchSystemLogsTool tool = new SearchSystemLogsTool();
  @BeforeEach void setup() throws Exception {
    temp = temp.toRealPath(); capture = create(temp.resolve("store"), "sample", true, 42);
    allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    var store = manager.stores().store(temp.resolve("store"));
    var pulls = store.systemPulls(org.triplehelix.wpilogmcp.capture.pull.FakeRobot.device(SERIAL, "SHA256:fixture"), WALL);
    assertTrue(pulls.beginPass());
    pulls.kernel(List.of("[99.000] early", "[100.000] start", "[105.000] warning synthetic", "[110.000] error synthetic", "[115.000] default value", "[120.000] end", "[121.000] after", "unclocked line"), 125);
    long epoch = WALL.instant().getEpochSecond();
    pulls.journal(List.of((epoch + 5) + ".250 host kernel: error synthetic", (epoch + 20) + ".0 host service: outside"), "synthetic-cursor", "synthetic-boot");
  }
  @AfterEach void close() throws Exception { manager.release(temp); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  JsonObject call(String extra) throws Exception {
    var args = com.google.gson.JsonParser.parseString("{" + extra + "}").getAsJsonObject(); args.addProperty("path", capture.toString());
    var result = tool.execute(args).getAsJsonObject(); assertNotEquals("error", result.get("status").getAsString(), result::toString); return result;
  }
  @Test void kernelInterpolationUsesTwoMeasuredPairsAndNeverExtrapolates() throws Exception {
    var result = call("\"source\":\"kernel\""); assertEquals("ok", result.get("status").getAsString());
    var lines = result.getAsJsonArray("matches"); assertEquals(8, lines.size());
    Double[] expected = {null, 10., 12.5, 15., 17.5, 20., null, null};
    for (int i = 0; i < expected.length; i++) {
      var row = lines.get(i).getAsJsonObject(); assertEquals(i + 1, row.get("line_number").getAsInt());
      if (expected[i] == null) { assertTrue(row.get("timestamp_sec").isJsonNull()); assertFalse(row.get("timestamp_reason").getAsString().isBlank()); }
      else { assertEquals(expected[i], row.get("timestamp_sec").getAsDouble(), 1e-12); assertEquals("uptime_pairing", row.get("timestamp_basis").getAsString()); }
    }
    assertEquals("[105.000]", lines.get(2).getAsJsonObject().get("original_timestamp").getAsString());
    assertFalse(result.has("data_quality"));
    assertEquals(1, result.getAsJsonObject("inputs").getAsJsonArray("files").size());
    assertEquals(List.of(capture.toString()), result.getAsJsonObject("inputs").getAsJsonArray("clock_files").asList().stream().map(com.google.gson.JsonElement::getAsString).toList());
  }
  @Test void journalUsesItsWrittenEpochAndKernelMeansOnlyDmesg() throws Exception {
    var result = call("\"source\":\"syslog\""); var rows = result.getAsJsonArray("matches"); assertEquals(2, rows.size());
    assertEquals(15.25, rows.get(0).getAsJsonObject().get("timestamp_sec").getAsDouble(), 1e-9);
    assertEquals("system_time", rows.get(0).getAsJsonObject().get("timestamp_basis").getAsString());
    assertTrue(rows.get(1).getAsJsonObject().get("timestamp_sec").isJsonNull());
    assertEquals(1, call("\"source\":\"kernel\",\"pattern\":\"error\"").get("total_matches").getAsInt());
    assertEquals(2, call("\"source\":\"all\",\"pattern\":\"error\"").get("total_matches").getAsInt());
  }
  @Test void severityPagingRegexAndRelativeScopesRetainUnmappedFacts() throws Exception {
    var result = call("\"source\":\"kernel\",\"offset\":2,\"limit\":2");
    assertEquals(8, result.get("total_matches").getAsInt()); assertEquals(8, result.getAsJsonObject("limits").getAsJsonObject("matches").get("total").getAsInt());
    assertEquals(2, result.get("returned").getAsInt()); assertTrue(result.get("has_more").getAsBoolean());
    var recent = call("\"source\":\"kernel\",\"last_seconds\":4");
    assertEquals(5, recent.getAsJsonArray("matches").size()); // 17.5, 20, and the three unplaced lines.
    assertEquals(16, recent.getAsJsonObject("inputs").getAsJsonObject("window").get("start").getAsDouble());
    assertEquals(20, recent.getAsJsonObject("inputs").getAsJsonObject("window").get("end").getAsDouble());
    assertEquals(1, call("\"source\":\"kernel\",\"level\":\"warning\",\"pattern\":\"SYNTH.*\",\"regex\":true").get("total_matches").getAsInt());
    assertEquals("no_match", call("\"source\":\"kernel\",\"level\":\"error\",\"pattern\":\"default\"").get("status").getAsString());
    // The same synthetic lines pass through the string tool's classification, not a copied rule.
    var strings = temp.resolve("console.wpilog");
    var text = Files.readAllLines(capture.getParent().resolve("robot/system/dmesg.txt"));
    try (var writer = new org.triplehelix.wpilogmcp.fixtures.WpilogWriter(strings, "synthetic severity")) {
      int id = writer.start("console", "string", "", 0);
      for (int i = 0; i < text.size(); i++) writer.append(id, i * 1_000_000L, text.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    for (String level : List.of("error", "warning", "info", "any")) {
      var args = new JsonObject(); args.addProperty("path", strings.toString()); args.addProperty("level", level);
      var other = new QueryTools.SearchStringsTool().execute(args).getAsJsonObject();
      var pulled = call("\"source\":\"kernel\",\"level\":\"" + level + "\"");
      assertEquals(other.get("total_matches").getAsLong(), pulled.get("total_matches").getAsLong(), level);
    }
  }
  @Test void searchReadsCommittedBytesAndExplainsAnInvalidOrMissingReceipt() throws Exception {
    var file = capture.getParent().resolve("robot/system/dmesg.txt");
    Files.writeString(file, "[116] uncommitted\n", java.nio.file.StandardOpenOption.APPEND);
    assertEquals("no_match", call("\"pattern\":\"uncommitted\"").get("status").getAsString());
    var path = capture.getParent().resolve("session.json");
    var manifest = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    manifest.getAsJsonObject("system_logs").getAsJsonArray("files").get(0).getAsJsonObject().addProperty("path", "../../../../robot.json");
    Files.writeString(path, manifest.toString());
    var args = new JsonObject(); args.addProperty("path", capture.toString());
    var result = tool.execute(args).getAsJsonObject(); assertEquals("error", result.get("status").getAsString());
    assertFalse(result.toString().contains("Internal error")); assertTrue(result.toString().contains("Traversal"));
  }
  @Test void discoveryAndGuideDistinguishExactFilesFromTimelyTails() throws Exception {
    assertTrue(tool.description().contains("kernel (dmesg only)"));
    assertTrue(tool.description().contains("twice"));
    assertTrue(AnalysisGuidance.analysisPrinciples().toString().contains("pulled file is the exact record"));
    var registry = new org.triplehelix.wpilogmcp.mcp.ToolRegistry(); DiscoveryTools.registerAll(registry);
    for (String word : List.of("kernel", "dmesg", "syslog", "journal", "crash", "hs_err", "system log")) {
      var args = new JsonObject(); args.addProperty("task", word);
      assertTrue(registry.getTool("suggest_tools").execute(args).toString().contains("search_system_logs"), word);
    }
  }
  @Test void missingClockEvidenceStaysNullAndWrittenDatesAreNeverGuessed() throws Exception {
    try (var writer = new org.triplehelix.wpilogmcp.fixtures.WpilogWriter(capture, "synthetic no clock evidence")) {
      int id = writer.start("/counter", "int64", "", 10_000_000);
      writer.append(id, 10_000_000, org.triplehelix.wpilogmcp.fixtures.WpilogWriter.encodeInt64(1));
      writer.append(id, 20_000_000, org.triplehelix.wpilogmcp.fixtures.WpilogWriter.encodeInt64(2));
    }
    var rows = call("\"source\":\"all\",\"pattern\":\"synthetic\"").getAsJsonArray("matches");
    assertEquals(3, rows.size());
    for (var value : rows) {
      var row = value.getAsJsonObject(); assertTrue(row.get("timestamp_sec").isJsonNull());
      assertTrue(row.get("timestamp_reason").getAsString().startsWith("No "));
    }
    var time = new SystemLogClocks(10, 20);
    var traditional = time.map("Mar  7 14:22:33 host warning example", "text");
    assertEquals("Mar  7 14:22:33", traditional.written()); assertNull(traditional.seconds());
    assertTrue(traditional.reason().contains("year and zone"));
    assertEquals(WALL.instant().getEpochSecond(), org.triplehelix.wpilogmcp.capture.pull.SystemLogTime.epoch("2026-03-07T09:22:33-05:00 host text", "text"));
  }
}
