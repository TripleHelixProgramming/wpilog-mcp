/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.store.StoreManifest.MirrorSession;
import org.triplehelix.wpilogmcp.tools.SessionStoreFixture.SessionSpec;

/**
 * compare_sessions on hand-written stores. Each expected number is worked out here from the
 * values the fixture writes (type-7 percentiles, n-1 deviations, the contract's 50-session
 * workload), never taken from a server result.
 */
class CompareSessionsToolTest {
  @TempDir Path temp;
  final LogManager manager = LogManager.getInstance();
  Set<Path> previous;
  ToolRegistry.Tool tool;

  @BeforeEach void setUp() throws Exception {
    temp = temp.toRealPath();
    previous = manager.getAllowedDirectories();
    manager.clearAllowedDirectories();
    var registry = new ToolRegistry();
    RobotAnalysisTools.registerAll(registry);
    tool = registry.getTool("compare_sessions");
  }

  @AfterEach void tearDown() {
    manager.unloadAllLogs();
    manager.clearAllowedDirectories();
    previous.forEach(manager::addAllowedDirectory);
  }

  // ==================== helpers ====================

  JsonObject call(JsonObject args) throws Exception {
    assertNotNull(tool, "compare_sessions is not registered");
    return tool.execute(args).getAsJsonObject();
  }

  static JsonObject args(JsonArray sessions, JsonObject... metrics) {
    var args = new JsonObject();
    args.add("sessions", sessions);
    var array = new JsonArray();
    for (var metric : metrics) array.add(metric);
    args.add("metrics", array);
    return args;
  }

  static JsonArray sessions(String... ids) {
    var array = new JsonArray();
    for (var id : ids) array.add(session(id, null));
    return array;
  }

  static JsonObject session(String id, String anchor) {
    var o = new JsonObject();
    o.addProperty("session_id", id);
    if (anchor != null) o.addProperty("anchor_path", anchor);
    return o;
  }

  static JsonObject metric(String id, String entry, String statistic) {
    var o = new JsonObject();
    o.addProperty("id", id); o.addProperty("name", entry); o.addProperty("statistic", statistic);
    return o;
  }

  static JsonObject role(String id, String role, String statistic) {
    var o = new JsonObject();
    o.addProperty("id", id); o.addProperty("role", role); o.addProperty("statistic", statistic);
    return o;
  }

  static String status(JsonObject result) { return result.get("status").getAsString(); }

  /** The refusal code, or null when there is none, so a missing refusal fails an assertion. */
  static String code(JsonObject result) { return result.has("code") ? result.get("code").getAsString() : null; }

  static JsonObject summary(JsonObject result, String metricId) {
    for (var item : result.getAsJsonArray("summary"))
      if (item.getAsJsonObject().get("metric_id").getAsString().equals(metricId)) return item.getAsJsonObject();
    throw new AssertionError("No summary for " + metricId + " in " + result);
  }

  static JsonObject row(JsonObject result, String sessionId) {
    for (var item : result.getAsJsonArray("sessions"))
      if (item.getAsJsonObject().get("session_id").getAsString().equals(sessionId)) return item.getAsJsonObject();
    throw new AssertionError("No row for " + sessionId + " in " + result);
  }

  static JsonObject value(JsonObject row, String metricId) {
    for (var item : row.getAsJsonArray("metrics"))
      if (item.getAsJsonObject().get("metric_id").getAsString().equals(metricId)) return item.getAsJsonObject();
    throw new AssertionError("No metric " + metricId + " in " + row);
  }

  static List<String> strings(JsonArray array) {
    var out = new ArrayList<String>();
    for (JsonElement e : array) out.add(e.isJsonObject() ? e.getAsJsonObject().get("session_id").getAsString() : e.getAsString());
    return out;
  }

  void allow(Path root) { manager.addAllowedDirectory(root); }

  static void assertCounts(JsonObject result, int requested, int analyzed, int skipped) {
    assertEquals(requested, result.get("requested_session_count").getAsInt(), result.toString());
    assertEquals(analyzed, result.get("analyzed_session_count").getAsInt(), result.toString());
    assertEquals(skipped, result.get("skipped_session_count").getAsInt(), result.toString());
    assertEquals(requested, analyzed + skipped, "requested sessions equal analyzed plus skipped");
  }

  // ==================== the contract's acceptance workload ====================

  @Test void equalSessionWeightsDifferFromOptInPooling() throws Exception {
    var store = SessionStoreFixture.store(temp, "season", "season-store");
    var ids = new String[50];
    for (int s = 0; s < 50; s++) {
      int count = 1000 + 100 * s; final int session = s;
      ids[s] = "session-" + s;
      SessionStoreFixture.write(store, new SessionSpec(ids[s]).member("capture.wpilog", 0, (count - 1) * 0.02, writer -> {
        int id = writer.start("/Measurement", "double", "", 0);
        for (int i = 0; i < count; i++) writer.append(id, 20_000L * i, WpilogWriter.encodeDouble(session + i % 5));
      }));
    }
    allow(store.root());
    var request = args(sessions(ids), metric("m", "/Measurement", "mean"));
    request.addProperty("pool_samples", true);
    var result = call(request);
    assertEquals("ok", status(result), result.toString());
    assertCounts(result, 50, 50, 0);
    // Session s holds 1000+100s values s+(i mod 5); each count is a multiple of 5, so its mean is s+2.
    for (int s = 0; s < 50; s++) assertEquals(s + 2.0, value(row(result, ids[s]), "m").get("value").getAsDouble(), 1e-12);
    // Equal weights: (sum of s for 0..49 = 1225, plus 2*50) / 50 = 26.5. Pooled: 5,612,500 / 172,500.
    var summary = summary(result, "m");
    assertEquals(50, summary.get("session_count").getAsInt());
    assertEquals(26.5, summary.get("mean").getAsDouble(), 1e-12);
    var pooled = result.getAsJsonArray("pooled").get(0).getAsJsonObject();
    assertEquals("sample_weighted", pooled.get("weighting").getAsString());
    assertEquals(172_500, pooled.get("sample_count").getAsInt());
    assertEquals(50, pooled.get("session_count").getAsInt());
    assertEquals(5_612_500.0 / 172_500.0, pooled.get("value").getAsDouble(), 1e-9);
    assertEquals(32.53623188405797, pooled.get("value").getAsDouble(), 1e-9);
  }

  @Test void medianAndPercentilesMatchIndependentTypeSevenInterpolation() throws Exception {
    var store = SessionStoreFixture.store(temp, "percentiles", "percentile-store");
    SessionStoreFixture.write(store, new SessionSpec("a").member("a.wpilog", 0, 4, SessionStoreFixture.doubles("/X", 1, 2, 3, 4, 10)));
    SessionStoreFixture.write(store, new SessionSpec("b").member("b.wpilog", 0, 3, SessionStoreFixture.doubles("/X", 5, 7, 9, 11)));
    SessionStoreFixture.write(store, new SessionSpec("c").member("c.wpilog", 0, 0, SessionStoreFixture.doubles("/X", 100)));
    allow(store.root());
    var result = call(args(sessions("a", "b", "c"), metric("median", "/X", "median"), metric("p5", "/X", "p5"),
        metric("p95", "/X", "p95")));
    assertEquals("ok", status(result), result.toString());
    // a = [1,2,3,4,10]: median 3; p5 at index 0.2 = 1 + 0.2*(2-1) = 1.2; p95 at 3.8 = 4 + 0.8*6 = 8.8
    assertEquals(3.0, value(row(result, "a"), "median").get("value").getAsDouble(), 1e-12);
    assertEquals(1.2, value(row(result, "a"), "p5").get("value").getAsDouble(), 1e-12);
    assertEquals(8.8, value(row(result, "a"), "p95").get("value").getAsDouble(), 1e-12);
    // b = [5,7,9,11]: median (7+9)/2 = 8; p5 at index 0.15 = 5 + 0.15*2 = 5.3
    assertEquals(8.0, value(row(result, "b"), "median").get("value").getAsDouble(), 1e-12);
    assertEquals(5.3, value(row(result, "b"), "p5").get("value").getAsDouble(), 1e-12);
    assertEquals(100.0, value(row(result, "c"), "p5").get("value").getAsDouble(), 1e-12);
    // The summary applies the same statistics to the per-session values, each session once.
    // Medians [3, 8, 100]: mean 37; deviations -34, -29, 63; n-1 variance (1156+841+3969)/2.
    var medians = summary(result, "median");
    assertEquals(3, medians.get("session_count").getAsInt());
    assertEquals(8.0, medians.get("median").getAsDouble(), 1e-12);
    assertEquals(37.0, medians.get("mean").getAsDouble(), 1e-12);
    assertEquals(Math.sqrt(5966.0 / 2), medians.get("std_dev").getAsDouble(), 1e-9);
    assertEquals(3.0 + 0.1 * (8 - 3), medians.get("p5").getAsDouble(), 1e-12);
    assertEquals(3.0, medians.get("min").getAsDouble(), 0);
    assertEquals(100.0, medians.get("max").getAsDouble(), 0);
    // p5 values [1.2, 5.3, 100]: their median 5.3 and their p5 1.2 + 0.1*(5.3-1.2)
    var p5 = summary(result, "p5");
    assertEquals(5.3, p5.get("median").getAsDouble(), 1e-12);
    assertEquals(1.2 + 0.1 * (5.3 - 1.2), p5.get("p5").getAsDouble(), 1e-12);
  }

  @Test void eachMetricCountsOnlyItsContributingSessions() throws Exception {
    var store = SessionStoreFixture.store(temp, "contributors", "contributor-store");
    SessionStoreFixture.write(store, new SessionSpec("s0").member("m.wpilog", 0, 2, SessionStoreFixture.all(
        SessionStoreFixture.doubles("/A", 1, 2, 3), SessionStoreFixture.doubles("/B", 10, 20, 30))));
    SessionStoreFixture.write(store, new SessionSpec("s1").member("m.wpilog", 0, 2, SessionStoreFixture.all(
        SessionStoreFixture.doubles("/A", 1, 2, 3), SessionStoreFixture.doubles("/B", 20, 30, 40))));
    SessionStoreFixture.write(store, new SessionSpec("s2").member("m.wpilog", 0, 2, SessionStoreFixture.doubles("/A", 4, 5, 6)));
    allow(store.root());
    var result = call(args(sessions("s0", "s1", "s2"), metric("a", "/A", "mean"), metric("b", "/B", "mean")));
    assertEquals("partial", status(result), result.toString());
    assertCounts(result, 3, 3, 0);
    var a = summary(result, "a");
    assertEquals(3, a.get("session_count").getAsInt());
    assertEquals((2.0 + 2.0 + 5.0) / 3, a.get("mean").getAsDouble(), 1e-12);
    var b = summary(result, "b");
    assertEquals(2, b.get("session_count").getAsInt(), "a missing entry is an omission, not a zero");
    assertEquals((20.0 + 30.0) / 2, b.get("mean").getAsDouble(), 1e-12);
    assertEquals(List.of("s0", "s1"), strings(b.getAsJsonArray("contributing_sessions")));
    assertEquals(List.of("s2"), strings(b.getAsJsonArray("excluded_sessions")));
    assertTrue(value(row(result, "s2"), "b").get("value").isJsonNull());
    assertFalse(value(row(result, "s2"), "b").get("null_reason").getAsString().isBlank());
    assertTrue(result.getAsJsonArray("skipped").toString().contains("s2"), "the omission is listed in skipped");
  }

  // ==================== confounders ====================

  @Test void aGitShaChangeAppearsInConfoundersWithItsSessionsAndSpans() throws Exception {
    var store = SessionStoreFixture.store(temp, "code", "code-store");
    String a = "a".repeat(40), b = "b".repeat(40), c = "c".repeat(40);
    SessionStoreFixture.write(store, new SessionSpec("s0").member("m.wpilog", 0, 2, SessionStoreFixture.all(
        SessionStoreFixture.doubles("/X", 1, 2, 3), SessionStoreFixture.strings("/RealMetadata/GitSHA", new double[] {0}, a))));
    SessionStoreFixture.write(store, new SessionSpec("s1").member("m.wpilog", 0, 2, SessionStoreFixture.all(
        SessionStoreFixture.doubles("/X", 1, 2, 3), SessionStoreFixture.strings("/RealMetadata/GitSHA", new double[] {0}, b))));
    SessionStoreFixture.write(store, new SessionSpec("s2").member("m.wpilog", 0, 2, SessionStoreFixture.all(
        SessionStoreFixture.doubles("/X", 1, 2, 3), SessionStoreFixture.strings("/RealMetadata/GitSHA", new double[] {0, 1.5}, a, c))));
    allow(store.root());
    var result = call(args(sessions("s0", "s1", "s2"), metric("x", "/X", "mean")));
    assertEquals("ok", status(result), result.toString());
    var git = result.getAsJsonObject("confounders").getAsJsonObject("git_sha");
    assertEquals(3, git.get("distinct_value_count").getAsInt(), git.toString());
    var bySha = new java.util.HashMap<String, List<String>>();
    for (var item : git.getAsJsonArray("values")) {
      var o = item.getAsJsonObject();
      bySha.put(o.get("value").getAsString(), strings(o.getAsJsonArray("sessions")));
    }
    assertEquals(Map.of(a, List.of("s0", "s2"), b, List.of("s1"), c, List.of("s2")), bySha);
    assertEquals(List.of("s2"), strings(git.getAsJsonArray("changed_within")),
        "a change within one session is not presented as its constant identity");
    assertTrue(git.getAsJsonArray("evidence").toString().contains("/RealMetadata/GitSHA"));
  }

  @Test void unknownAndConflictingFactsStayVisible() throws Exception {
    var store = SessionStoreFixture.store(temp, "facts", "fact-store");
    SessionStoreFixture.write(store, new SessionSpec("s0").member("m.wpilog", 0, 2, SessionStoreFixture.all(
        SessionStoreFixture.doubles("/X", 1, 2, 3), SessionStoreFixture.strings("/RealMetadata/GitSHA", new double[] {0}, "d".repeat(40)),
        SessionStoreFixture.strings("/SystemStats/SerialNumber", new double[] {0}, "OTHER-ROBOT"))));
    SessionStoreFixture.write(store, new SessionSpec("s1").member("m.wpilog", 0, 2, SessionStoreFixture.doubles("/X", 1, 2, 3)));
    allow(store.root());
    var result = call(args(sessions("s0", "s1"), metric("x", "/X", "mean")));
    var confounders = result.getAsJsonObject("confounders");
    assertEquals(List.of("s1"), strings(confounders.getAsJsonObject("git_sha").getAsJsonArray("unknown_sessions")));
    for (var fact : List.of("battery", "firmware")) {
      var unknown = confounders.getAsJsonObject(fact).getAsJsonArray("unknown_sessions");
      assertEquals(List.of("s0", "s1"), strings(unknown), fact + " is unknown, never assumed equal");
      assertFalse(unknown.get(0).getAsJsonObject().get("reason").getAsString().isBlank());
    }
    // The manifest says SYNTHETIC-A; the log says OTHER-ROBOT. Both stay visible.
    var robot = confounders.getAsJsonObject("robot").toString();
    assertTrue(robot.contains("SYNTHETIC-A") && robot.contains("OTHER-ROBOT"), robot);
    assertEquals(List.of("s0"), strings(confounders.getAsJsonObject("robot").getAsJsonArray("conflicting_sessions")));
  }

  // ==================== dispositions ====================

  @Test void anUnreadableSessionIsSkippedAndTheRestAreAnalyzed() throws Exception {
    var store = SessionStoreFixture.store(temp, "unreadable", "unreadable-store");
    int base = 0;
    for (var id : List.of("s0", "s1", "s2")) {
      base += 10;
      SessionStoreFixture.write(store, new SessionSpec(id).member("m.wpilog", 0, 2, SessionStoreFixture.doubles("/X", base, base + 1, base + 2)));
    }
    var member = store.root().resolve("robots/SYNTHETIC-A/sessions/2026-01-01/s1/m.wpilog");
    var bytes = Files.readAllBytes(member); bytes[bytes.length - 1] ^= 1; Files.write(member, bytes);
    allow(store.root());
    var result = call(args(sessions("s0", "s1", "s2"), metric("x", "/X", "mean")));
    assertEquals("partial", status(result), result.toString());
    assertCounts(result, 3, 2, 1);
    var skipped = result.getAsJsonArray("skipped").toString();
    assertTrue(skipped.contains("s1") && skipped.contains("UNREADABLE"), skipped);
    assertEquals(List.of("s0", "s2"), strings(summary(result, "x").getAsJsonArray("contributing_sessions")));
  }

  @Test void whenEverySessionIsUnreadableTheResultIsAnErrorNotNoMatch() throws Exception {
    var store = SessionStoreFixture.store(temp, "corrupt", "corrupt-store");
    for (var id : List.of("s0", "s1")) {
      var dir = SessionStoreFixture.write(store, new SessionSpec(id).member("m.wpilog", 0, 2,
          SessionStoreFixture.doubles("/X", id.equals("s0") ? 1 : 4, 2, 3)));
      var bytes = Files.readAllBytes(dir.resolve("m.wpilog")); bytes[bytes.length - 1] ^= 1; Files.write(dir.resolve("m.wpilog"), bytes);
    }
    allow(store.root());
    var result = call(args(sessions("s0", "s1"), metric("x", "/X", "mean")));
    assertEquals("error", status(result), result.toString());
    assertCounts(result, 2, 0, 2);
  }

  @Test void noMatchWhenNoSessionHoldsTheRequestedData() throws Exception {
    var store = SessionStoreFixture.store(temp, "absent", "absent-store");
    SessionStoreFixture.write(store, new SessionSpec("s0").member("m.wpilog", 0, 2, SessionStoreFixture.doubles("/X", 1, 2, 3)));
    SessionStoreFixture.write(store, new SessionSpec("s1").member("m.wpilog", 0, 2, SessionStoreFixture.doubles("/X", 4, 5, 6)));
    allow(store.root());
    var result = call(args(sessions("s0", "s1"), metric("y", "/Missing/Entry", "mean")));
    assertEquals("no_match", status(result), result.toString());
    assertCounts(result, 2, 0, 2);
    assertTrue(result.has("looked_for") && result.has("hint"), result.toString());
  }

  @Test void aStillRecordingSessionIsSkippedAsNotFinished() throws Exception {
    var store = SessionStoreFixture.store(temp, "recording", "recording-store");
    SessionStoreFixture.write(store, new SessionSpec("done").member("m.wpilog", 0, 2, SessionStoreFixture.doubles("/X", 1, 2, 3)));
    SessionStoreFixture.write(store, new SessionSpec("live").open("capture.wpilog", 0, 2, SessionStoreFixture.doubles("/X", 7, 8, 9)));
    allow(store.root());
    var result = call(args(sessions("done", "live"), metric("x", "/X", "mean")));
    assertEquals("partial", status(result), result.toString());
    assertCounts(result, 2, 1, 1);
    var skipped = result.getAsJsonArray("skipped").toString();
    assertTrue(skipped.contains("live") && skipped.contains("NOT_FINISHED"), skipped);
  }

  @Test void aMirrorSessionStillGrowingIsNotFinished() throws Exception {
    var mirror = SessionStoreFixture.mirror(temp, "mirror", "mirror-store", Map.of(
        "done", new MirrorSession("robots/SYNTHETIC-A/sessions/2026-01-01/done", SessionStoreFixture.WHEN, true, false),
        "growing", new MirrorSession("robots/SYNTHETIC-A/sessions/2026-01-01/growing", SessionStoreFixture.WHEN, false, true)));
    SessionStoreFixture.write(mirror, new SessionSpec("done").member("m.wpilog", 0, 2, SessionStoreFixture.doubles("/X", 1, 2, 3)));
    SessionStoreFixture.write(mirror, new SessionSpec("growing").member("m.wpilog", 0, 2, SessionStoreFixture.doubles("/X", 4, 5, 6)));
    allow(mirror.root());
    var result = call(args(sessions("done", "growing"), metric("x", "/X", "mean")));
    assertEquals("mirror", result.getAsJsonObject("source").get("kind").getAsString(), result.toString());
    assertCounts(result, 2, 1, 1);
    assertTrue(result.getAsJsonArray("skipped").toString().contains("NOT_FINISHED"));
  }

  // ==================== selection refusals ====================

  @Test void aPlainFolderIsRefusedWithTheRemedy() throws Exception {
    var folder = Files.createDirectories(temp.resolve("loose"));
    try (var writer = new WpilogWriter(folder.resolve("a.wpilog"), "")) { SessionStoreFixture.doubles("/X", 1, 2).write(writer); }
    allow(folder);
    var result = call(args(sessions("anything"), metric("x", "/X", "mean")));
    assertEquals("error", status(result), result.toString());
    assertEquals("NOT_A_STORE", code(result), result.toString());
    var hint = result.get("hint").getAsString();
    assertTrue(hint.contains("compare_matches") && hint.toLowerCase().contains("import"), hint);
  }

  @Test void severalSourcesRequireAnExplicitScopeId() throws Exception {
    var first = SessionStoreFixture.store(temp, "first", "first-store");
    var second = SessionStoreFixture.store(temp, "second", "second-store");
    for (var store : List.of(first, second))
      SessionStoreFixture.write(store, new SessionSpec("s0").member("m.wpilog", 0, 2, SessionStoreFixture.doubles("/X", 1, 2, 3)));
    allow(first.root()); allow(second.root());
    var refused = call(args(sessions("s0"), metric("x", "/X", "mean")));
    assertEquals("error", status(refused), refused.toString());
    assertEquals("SOURCE_SELECTION_REQUIRED", code(refused), refused.toString());
    var chosen = args(sessions("s0"), metric("x", "/X", "mean"));
    chosen.addProperty("scope_id", "second-store");
    var result = call(chosen);
    assertEquals("ok", status(result), result.toString());
    assertEquals("second-store", result.getAsJsonObject("source").get("store_id").getAsString());
  }

  @Test void unknownSessionsAreRefusedBeforeAnyRead() throws Exception {
    var store = SessionStoreFixture.store(temp, "unknown", "unknown-store");
    SessionStoreFixture.write(store, new SessionSpec("s0").member("m.wpilog", 0, 2, SessionStoreFixture.doubles("/X", 1, 2, 3)));
    allow(store.root());
    var result = call(args(sessions("s0", "nope"), metric("x", "/X", "mean")));
    assertEquals("error", status(result), result.toString());
    assertEquals("UNKNOWN_SESSION", code(result), result.toString());
    assertTrue(result.toString().contains("nope"));
  }

  @Test void duplicateSelectionsAreRefused() throws Exception {
    var store = SessionStoreFixture.store(temp, "duplicates", "duplicate-store");
    SessionStoreFixture.write(store, new SessionSpec("x").alias("old-x").member("m.wpilog", 0, 2, SessionStoreFixture.doubles("/X", 1, 2, 3)));
    var copyDir = SessionStoreFixture.write(store, new SessionSpec("copy").member("m.wpilog", 0, 2, SessionStoreFixture.doubles("/X", 1, 2, 3)));
    SessionStoreFixture.write(store, new SessionSpec("pair").member("p1.wpilog", 0, 2, SessionStoreFixture.doubles("/X", 7, 8, 9))
        .member("p2.wpilog", 0, 2, SessionStoreFixture.doubles("/Y", 4, 5, 6)));
    var shared = SessionStoreFixture.write(store, new SessionSpec("shared").member("q1.wpilog", 0, 2, SessionStoreFixture.doubles("/Y", 4, 5, 6)));
    allow(store.root());
    // The same session named twice, and a canonical id with its merge alias
    for (var pair : List.of(List.of("x", "x"), List.of("x", "old-x"))) {
      var result = call(args(sessions(pair.get(0), pair.get(1)), metric("x", "/X", "mean")));
      assertEquals("DUPLICATE_SELECTION", code(result), result.toString());
    }
    // Two sessions holding byte-identical members: equal content sets
    assertEquals(SessionStoreFixture.sha256(store.root().resolve("robots/SYNTHETIC-A/sessions/2026-01-01/x/m.wpilog")),
        SessionStoreFixture.sha256(copyDir.resolve("m.wpilog")));
    var equal = call(args(sessions("x", "copy"), metric("x", "/X", "mean")));
    assertEquals("DUPLICATE_SELECTION", code(equal), equal.toString());
    // One member shared by two otherwise different sessions: partial overlap
    var partial = new JsonArray(); partial.add(session("pair", "p1.wpilog")); partial.add(session("shared", null));
    assertEquals(SessionStoreFixture.sha256(shared.resolve("q1.wpilog")),
        SessionStoreFixture.sha256(store.root().resolve("robots/SYNTHETIC-A/sessions/2026-01-01/pair/p2.wpilog")));
    var overlap = call(args(partial, metric("y", "/Y", "mean")));
    assertEquals("DUPLICATE_SELECTION", code(overlap), overlap.toString());
  }

  // ==================== anchors ====================

  @Test void theAnchorDefaultsToTheOnlyWpilogAndSeveralRequireAChoice() throws Exception {
    var store = SessionStoreFixture.store(temp, "anchors", "anchor-store");
    SessionStoreFixture.write(store, new SessionSpec("single").member("only.wpilog", 0, 2, SessionStoreFixture.doubles("/X", 1, 2, 3)));
    SessionStoreFixture.write(store, new SessionSpec("rolled").member("first.wpilog", 0, 2, SessionStoreFixture.doubles("/X", 4, 5, 6))
        .member("second.wpilog", 3, 5, SessionStoreFixture.doubles("/Z", 7, 8, 9)));
    allow(store.root());
    var single = call(args(sessions("single"), metric("x", "/X", "mean")));
    assertEquals("ok", status(single), single.toString());
    var anchor = row(single, "single").getAsJsonObject("anchor");
    assertEquals("only.wpilog", anchor.get("path").getAsString());
    assertEquals("only_wpilog_member", anchor.get("basis").getAsString());

    var refused = call(args(sessions("single", "rolled"), metric("x", "/X", "mean")));
    assertEquals("ANCHOR_REQUIRED", code(refused), refused.toString());
    var choices = refused.toString();
    assertTrue(choices.contains("first.wpilog") && choices.contains("second.wpilog") && choices.contains("rolled"), choices);

    var chosen = new JsonArray(); chosen.add(session("single", null)); chosen.add(session("rolled", "first.wpilog"));
    var result = call(args(chosen, metric("x", "/X", "mean")));
    assertEquals(List.of("single", "rolled"), strings(summary(result, "x").getAsJsonArray("contributing_sessions")));
    var rolled = row(result, "rolled");
    assertEquals("requested", rolled.getAsJsonObject("anchor").get("basis").getAsString());
    assertTrue(rolled.getAsJsonObject("inputs").getAsJsonArray("excluded_members").toString().contains("second.wpilog"),
        "a member without recorded alignment is named, never silently dropped");
  }

  // ==================== numbers ====================

  @Test void aStandardDeviationOfOneValueIsNullWithAReason() throws Exception {
    var store = SessionStoreFixture.store(temp, "single", "single-store");
    SessionStoreFixture.write(store, new SessionSpec("one").member("m.wpilog", 0, 0, SessionStoreFixture.doubles("/X", 42)));
    allow(store.root());
    // The mean makes the session analyzed (one usable metric suffices); its spread is an omission.
    var result = call(args(sessions("one"), metric("level", "/X", "mean"), metric("spread", "/X", "std_dev")));
    assertEquals("partial", status(result), result.toString());
    var spread = value(row(result, "one"), "spread");
    assertTrue(spread.get("value").isJsonNull(), spread.toString());
    assertTrue(spread.get("null_reason").getAsString().contains("two"), spread.toString());
    assertEquals(1, spread.get("sample_count").getAsInt());
    // Across sessions as well: one session's mean has no spread, and get_statistics' 0 is not copied.
    var level = summary(result, "level");
    assertEquals(42.0, level.get("mean").getAsDouble(), 0);
    assertTrue(level.get("std_dev").isJsonNull(), level.toString());
    assertEquals(0, summary(result, "spread").get("session_count").getAsInt());
  }

  @Test void anglesAdmitOnlyACircularMeanAndSpread() throws Exception {
    var store = SessionStoreFixture.store(temp, "angles", "angle-store");
    SessionStoreFixture.write(store, new SessionSpec("h").member("m.wpilog", 0, 1, SessionStoreFixture.doubles("/Heading", 179, -179)));
    allow(store.root());
    var mean = metric("heading", "/Heading", "mean"); mean.addProperty("angle", "degrees");
    var result = call(args(sessions("h"), mean));
    assertEquals("ok", status(result), result.toString());
    // sin 179 + sin -179 = 0 and both cosines are negative, so the circular mean is 180 degrees
    assertEquals(180.0, Math.abs(value(row(result, "h"), "heading").get("value").getAsDouble()), 1e-9);
    var minimum = metric("lowest", "/Heading", "min"); minimum.addProperty("angle", "degrees");
    var refused = call(args(sessions("h"), minimum));
    assertEquals("INCOMPATIBLE_METRIC", code(refused), refused.toString());
  }

  @Test void aMissingRoleIsAnOmissionAndATiedRoleIsRefused() throws Exception {
    var store = SessionStoreFixture.store(temp, "roles", "role-store");
    SessionStoreFixture.write(store, new SessionSpec("battery").member("m.wpilog", 0, 2, SessionStoreFixture.all(
        SessionStoreFixture.doubles("/SystemStats/BatteryVoltage", 12, 12.5, 13), SessionStoreFixture.doubles("/X", 4, 5, 6))));
    SessionStoreFixture.write(store, new SessionSpec("none").member("m.wpilog", 0, 2, SessionStoreFixture.doubles("/X", 1, 2, 3)));
    SessionStoreFixture.write(store, new SessionSpec("tied").member("m.wpilog", 0, 2, SessionStoreFixture.all(
        SessionStoreFixture.doubles("/SystemStats/BatteryVoltage", 12, 12, 12), SessionStoreFixture.doubles("/Other/BatteryVoltage", 11, 11, 11))));
    allow(store.root());
    // /X keeps the session without a battery entry analyzed, so the missing role is a metric omission.
    var result = call(args(sessions("battery", "none"), role("v", "battery_voltage", "mean"), metric("x", "/X", "mean")));
    assertEquals("partial", status(result), result.toString());
    assertEquals(12.5, value(row(result, "battery"), "v").get("value").getAsDouble(), 1e-12);
    assertEquals("V", value(row(result, "battery"), "v").get("unit").getAsString());
    var omitted = value(row(result, "none"), "v");
    assertTrue(omitted.get("value").isJsonNull(), omitted.toString());
    assertTrue(omitted.get("null_reason").getAsString().contains("battery voltage"), omitted.toString());
    assertEquals(2, result.get("analyzed_session_count").getAsInt());
    assertEquals(1, summary(result, "v").get("session_count").getAsInt());
    assertEquals(2, summary(result, "x").get("session_count").getAsInt());
    var refused = call(args(sessions("battery", "tied"), role("v", "battery_voltage", "mean")));
    assertEquals("AMBIGUOUS_ROLE", code(refused), refused.toString());
    assertTrue(refused.toString().contains("/Other/BatteryVoltage"), refused.toString());
  }

  @Test void everyAnalyzedMetricCarriesItsDataQuality() throws Exception {
    var store = SessionStoreFixture.store(temp, "quality", "quality-store");
    SessionStoreFixture.write(store, new SessionSpec("q").member("m.wpilog", 0, 2, SessionStoreFixture.doubles("/X", 1, Double.NaN, 3)));
    allow(store.root());
    var result = call(args(sessions("q"), metric("x", "/X", "mean")));
    var x = value(row(result, "q"), "x");
    assertEquals(2.0, x.get("value").getAsDouble(), 1e-12);
    assertEquals(2, x.get("sample_count").getAsInt());
    assertEquals(1, x.get("non_finite_count").getAsInt());
    var quality = x.getAsJsonObject("data_quality");
    assertEquals(3, quality.get("sample_count").getAsInt(), "quality is scored on every in-scope sample");
    assertEquals(1, quality.get("nan_filtered").getAsInt());
  }

  @Test void streamedQualityMatchesTheSharedScoringOnePerWindow() throws Exception {
    var store = SessionStoreFixture.store(temp, "windows", "window-store");
    var values = new double[21];
    for (int i = 0; i <= 20; i++) values[i] = i;
    SessionStoreFixture.write(store, new SessionSpec("w").member("m.wpilog", 0, 20, SessionStoreFixture.doubles("/X", values)));
    allow(store.root());
    var request = args(sessions("w"), metric("x", "/X", "mean"));
    var windows = new JsonArray();
    for (double[] window : new double[][] {{0, 3}, {15, 18}}) {
      var item = new JsonObject();
      item.addProperty("start", window[0]);
      item.addProperty("end", window[1]);
      windows.add(item);
    }
    request.add("windows", windows);
    var x = value(row(call(request), "w"), "x");
    // Half-open windows keep 0, 1, 2 and 15, 16, 17: mean 51 / 6, and the 13 s step between the
    // windows is not a gap, because quality is scored one window at a time.
    assertEquals(8.5, x.get("value").getAsDouble(), 1e-12);
    var quality = x.getAsJsonObject("data_quality");
    assertEquals(6, quality.get("sample_count").getAsInt(), quality.toString());
    assertEquals(4.0, quality.get("time_span_seconds").getAsDouble(), 1e-12, quality.toString());
    assertEquals(0, quality.get("gap_count").getAsInt(), quality.toString());
    var shared = DataQuality.fromSegments(List.of(
        List.of(new TimestampedValue(0, 0.0), new TimestampedValue(1, 1.0), new TimestampedValue(2, 2.0)),
        List.of(new TimestampedValue(15, 15.0), new TimestampedValue(16, 16.0), new TimestampedValue(17, 17.0))), false);
    assertEquals(shared.toJson(), quality, "the streamed accumulator scores as DataQuality.fromSegments does");
  }

  @Test void streamedCircularStatisticsMatchTheSharedRule() throws Exception {
    var store = SessionStoreFixture.store(temp, "circle", "circle-store");
    SessionStoreFixture.write(store, new SessionSpec("c").member("m.wpilog", 0, 2, SessionStoreFixture.doubles("/Yaw", 10, 20, 30)));
    allow(store.root());
    var mean = metric("direction", "/Yaw", "mean"); mean.addProperty("angle", "degrees");
    var spread = metric("spread", "/Yaw", "std_dev"); spread.addProperty("angle", "degrees");
    var result = call(args(sessions("c"), mean, spread));
    double direction = value(row(result, "c"), "direction").get("value").getAsDouble();
    double dispersion = value(row(result, "c"), "spread").get("value").getAsDouble();
    // By symmetry about 20 degrees the mean direction is 20, and the resultant length is
    // R = (1 + 2 cos 10 deg) / 3, so the circular spread is sqrt(-2 ln R) in degrees.
    double r = (1 + 2 * Math.cos(Math.toRadians(10))) / 3;
    assertEquals(20.0, direction, 1e-12);
    assertEquals(Math.toDegrees(Math.sqrt(-2 * Math.log(r))), dispersion, 1e-9);
    var shared = NumericSignal.circularStatistics(new double[] {10, 20, 30}, NumericSignal.AngleUnit.DEGREES);
    assertEquals(shared.get("circular_mean").getAsDouble(), direction, 0.0);
    assertEquals(shared.get("circular_std").getAsDouble(), dispersion, 0.0);
  }
}
