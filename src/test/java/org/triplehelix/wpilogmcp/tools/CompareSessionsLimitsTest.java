/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.triplehelix.wpilogmcp.tools.CompareSessionsToolTest.args;
import static org.triplehelix.wpilogmcp.tools.CompareSessionsToolTest.assertCounts;
import static org.triplehelix.wpilogmcp.tools.CompareSessionsToolTest.code;
import static org.triplehelix.wpilogmcp.tools.CompareSessionsToolTest.metric;
import static org.triplehelix.wpilogmcp.tools.CompareSessionsToolTest.row;
import static org.triplehelix.wpilogmcp.tools.CompareSessionsToolTest.session;
import static org.triplehelix.wpilogmcp.tools.CompareSessionsToolTest.sessions;
import static org.triplehelix.wpilogmcp.tools.CompareSessionsToolTest.status;
import static org.triplehelix.wpilogmcp.tools.CompareSessionsToolTest.strings;
import static org.triplehelix.wpilogmcp.tools.CompareSessionsToolTest.summary;
import static org.triplehelix.wpilogmcp.tools.CompareSessionsToolTest.value;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.HttpTransport;
import org.triplehelix.wpilogmcp.mcp.McpMessageHandler;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.tools.SessionStoreFixture.SessionSpec;
import org.triplehelix.wpilogmcp.visualization.ServerIdentity;

/**
 * compare_sessions' caps, its two-call admission, its complete-or-refused reply, and the work it
 * must leave responsive. Each boundary is shown from both sides: the largest request a cap admits
 * succeeds, and one more is refused whole with its true count and a way to narrow, never answered
 * from the part that fit. The session, metric, window, kept-value and request caps run at their
 * production values; the others are lowered through the tool's test seam so the fixtures stay
 * small, and one test pins every production number.
 */
class CompareSessionsLimitsTest {
  private static final Gson REPLY_ENCODING = new GsonBuilder().serializeNulls().create();
  private static final Clock WALL = Clock.fixed(Instant.parse("2026-01-02T12:00:00Z"), ZoneOffset.UTC);
  private static final Announce TOPIC = new Announce("/ordered", 1, "int", null, new JsonObject());

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
    CompareSessionsTool.limits(CompareSessionsTool.Limits.PRODUCTION);
    CompareSessionsTool.admitted = () -> {};
    CompareSessionsTool.reading = () -> {};
    manager.unloadAllLogs();
    manager.clearAllowedDirectories();
    previous.forEach(manager::addAllowedDirectory);
  }

  // ==================== helpers ====================

  JsonObject call(JsonObject arguments) throws Exception {
    assertNotNull(tool, "compare_sessions is not registered");
    return tool.execute(arguments).getAsJsonObject();
  }

  void allow(Path root) { manager.addAllowedDirectory(root); }

  /** The production caps with some replaced, set through the tool's test seam. */
  static final class Caps {
    private static final CompareSessionsTool.Limits P = CompareSessionsTool.Limits.PRODUCTION;
    int occurrences = P.memberOccurrences();
    long bytes = P.memberBytes();
    long visits = P.recordVisits();
    int pooled = P.pooledValues();
    int facts = P.factValues();
    int evidence = P.confounderBytes();
    int reply = P.replyBytes();

    void apply() {
      CompareSessionsTool.limits(new CompareSessionsTool.Limits(P.sessions(), P.metrics(), P.windows(),
          occurrences, P.membersPerSession(), bytes, visits, P.keptValues(), pooled, P.requestBytes(),
          facts, evidence, reply));
    }
  }

  /** A cap refuses the whole call with its true count and a remedy; nothing is summarized. */
  static void assertLimit(JsonObject result, String name, long requested, long limit) {
    assertEquals("error", status(result), result.toString());
    assertEquals("RESOURCE_LIMIT", code(result), result.toString());
    assertEquals(name, result.get("limit_name").getAsString(), result.toString());
    assertEquals(limit, result.get("limit").getAsLong(), result.toString());
    assertEquals(requested, result.get("requested").getAsLong(), result.toString());
    assertFalse(result.get("hint").getAsString().isBlank(), "a refusal says how to narrow the request");
    for (var key : List.of("sessions", "summary", "pooled", "confounders")) {
      assertFalse(result.has(key), "a refused call answers nothing from the part it read: " + key);
    }
  }

  /** One double entry /X holding 0, 1, ..., count-1 at 20 ms intervals. */
  static SessionStoreFixture.Content ramp(int count) {
    return writer -> {
      int id = writer.start("/X", "double", "", 0);
      for (int i = 0; i < count; i++) writer.append(id, 20_000L * i, WpilogWriter.encodeDouble(i));
    };
  }

  static JsonArray windows(int count) {
    var array = new JsonArray();
    for (int k = 0; k < count; k++) {
      var window = new JsonObject();
      window.addProperty("start", 2.0 * k);
      window.addProperty("end", 2.0 * k + 1);
      array.add(window);
    }
    return array;
  }

  static JsonObject rpc(JsonObject arguments) {
    var message = new JsonObject();
    message.addProperty("jsonrpc", "2.0");
    message.addProperty("id", 41);
    message.addProperty("method", "tools/call");
    var params = new JsonObject();
    params.addProperty("name", "compare_sessions");
    params.add("arguments", arguments);
    message.add("params", params);
    return message;
  }

  static JsonObject toolBody(JsonObject response) {
    return JsonParser.parseString(response.getAsJsonObject("result").getAsJsonArray("content").get(0)
        .getAsJsonObject().get("text").getAsString()).getAsJsonObject();
  }

  @FunctionalInterface interface Check { void run() throws Exception; }

  /** Writes two finished sessions whose /X means are 2 and 5. */
  SessionStoreFixture.Store twoSessions(String name) throws Exception {
    var store = SessionStoreFixture.store(temp, name, name + "-store");
    SessionStoreFixture.write(store, new SessionSpec("s0").member("m.wpilog", 0, 2, SessionStoreFixture.doubles("/X", 1, 2, 3)));
    SessionStoreFixture.write(store, new SessionSpec("s1").member("m.wpilog", 0, 2, SessionStoreFixture.doubles("/X", 4, 5, 6)));
    allow(store.root());
    return store;
  }

  /**
   * Holds the first two reads that reach the tool's reading hook until the check has run, then
   * releases them. Later reads, including the held calls' own after release, pass through.
   */
  static CountDownLatch holdFirstTwo(CountDownLatch release) {
    var arrived = new CountDownLatch(2);
    var entered = new AtomicInteger();
    CompareSessionsTool.reading = () -> {
      if (entered.incrementAndGet() > 2) return;
      arrived.countDown();
      try {
        if (!release.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("A held comparison was never released");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(interrupted);
      }
    };
    return arrived;
  }

  /** Runs two comparisons, holds both inside their reads during the check, and checks both answers. */
  void holdTwo(ExecutorService pool, Check check) throws Exception {
    var release = new CountDownLatch(1);
    var arrived = holdFirstTwo(release);
    var first = pool.submit(() -> call(args(sessions("s0"), metric("x", "/X", "mean"))));
    var second = pool.submit(() -> call(args(sessions("s1"), metric("x", "/X", "mean"))));
    try {
      assertTrue(arrived.await(10, TimeUnit.SECONDS), "both comparisons must be admitted and reading");
      check.run();
    } finally {
      release.countDown();
    }
    var a = first.get(10, TimeUnit.SECONDS);
    var b = second.get(10, TimeUnit.SECONDS);
    assertEquals(2.0, value(row(a, "s0"), "x").get("value").getAsDouble(), 0, a.toString());
    assertEquals(5.0, value(row(b, "s1"), "x").get("value").getAsDouble(), 0, b.toString());
    CompareSessionsTool.reading = () -> {};
  }

  // ==================== the settled numbers ====================

  @Test void theProductionCapsAreTheSettledNumbers() {
    var caps = CompareSessionsTool.Limits.PRODUCTION;
    // The hardening plan's settled numbers, written out here rather than read from the code.
    assertEquals(64, caps.sessions());
    assertEquals(8, caps.metrics());
    assertEquals(16, caps.windows());
    assertEquals(256, caps.memberOccurrences());
    assertEquals(64, caps.membersPerSession());
    assertEquals(1_073_741_824L, caps.memberBytes(), "1 GiB of member files");
    assertEquals(8_388_608L, caps.recordVisits());
    assertEquals(65_536, caps.keptValues());
    assertEquals(1_048_576, caps.pooledValues());
    assertEquals(65_536, caps.requestBytes(), "64 KiB of encoded arguments");
    assertEquals(64, caps.factValues());
    assertEquals(262_144, caps.confounderBytes(), "256 KiB of confounder evidence");
    assertEquals(1_048_576, caps.replyBytes(), "one complete reply of at most 1 MiB");
    assertEquals(2, CompareSessionsTool.CONCURRENT);
    assertSame(caps, CompareSessionsTool.limits(), "every test restores the production caps");
  }

  // ==================== admission of concurrent work ====================

  @Test void aThirdComparisonIsRefusedAtOnceAndEverySlotComesBack() throws Exception {
    twoSessions("busy");
    var pool = Executors.newFixedThreadPool(3);
    try {
      holdTwo(pool, () -> {
        // Refused, not queued: the third call returns while both slots are still held.
        var third = pool.submit(() -> call(args(sessions("s0"), metric("x", "/X", "mean"))));
        var refused = assertDoesNotThrow(() -> third.get(5, TimeUnit.SECONDS),
            "a third comparison must be refused at once, not wait for a slot");
        assertEquals("error", status(refused), refused.toString());
        assertEquals("BUSY", code(refused), refused.toString());
        assertTrue(refused.get("retryable").getAsBoolean(), refused.toString());
        assertTrue(refused.get("retry_after_ms").getAsInt() > 0, refused.toString());
      });
      // A refusal inside a slot gives the slot back, as a success does.
      for (int i = 0; i < 3; i++) {
        var refused = call(args(sessions("nope"), metric("x", "/X", "mean")));
        assertEquals("UNKNOWN_SESSION", code(refused), refused.toString());
      }
      // So two comparisons can still run at once.
      holdTwo(pool, () -> {});
    } finally {
      pool.shutdownNow();
    }
  }

  // ==================== work caps ====================

  @Test void memberFileBytesAreAdmittedUpToTheCapAndRefusedOneByteOver() throws Exception {
    var store = SessionStoreFixture.store(temp, "bytes", "byte-store");
    var s0 = SessionStoreFixture.write(store, new SessionSpec("s0").member("m.wpilog", 0, 2, SessionStoreFixture.doubles("/X", 1, 2, 3)));
    var s1 = SessionStoreFixture.write(store, new SessionSpec("s1").member("m.wpilog", 0, 3, SessionStoreFixture.doubles("/X", 4, 5, 6, 7)));
    allow(store.root());
    // The cap counts the selected sessions' WPILOG members as the file system sizes them.
    long total = Files.size(s0.resolve("m.wpilog")) + Files.size(s1.resolve("m.wpilog"));
    var request = args(sessions("s0", "s1"), metric("x", "/X", "mean"));
    var caps = new Caps();
    caps.bytes = total;
    caps.apply();
    var admitted = call(request);
    assertEquals("ok", status(admitted), admitted.toString());
    assertCounts(admitted, 2, 2, 0);
    caps.bytes = total - 1;
    caps.apply();
    assertLimit(call(request), "member_bytes", total, total - 1);
  }

  @Test void recordVisitsAreAdmittedUpToTheCapAndRefusedOneOver() throws Exception {
    var store = SessionStoreFixture.store(temp, "visits", "visit-store");
    SessionStoreFixture.write(store, new SessionSpec("s0").member("m.wpilog", 0, 2, SessionStoreFixture.doubles("/X", 1, 2, 3)));
    SessionStoreFixture.write(store, new SessionSpec("s1").member("m.wpilog", 0, 3, SessionStoreFixture.doubles("/X", 4, 5, 6, 7)));
    allow(store.root());
    // The members hold 3 + 4 records of /X and nothing else the comparison reads: no Driver
    // Station entries to resolve a scope and no confounder entries. So the call visits 7 records.
    var request = args(sessions("s0", "s1"), metric("x", "/X", "mean"));
    var production = call(request);
    assertEquals(7, production.getAsJsonObject("inputs").get("record_visits").getAsLong(), production.toString());
    var caps = new Caps();
    caps.visits = 7;
    caps.apply();
    assertEquals("ok", status(call(request)));
    caps.visits = 6;
    caps.apply();
    // Each session's records arrive in one batch, so the call stops at 7 visits, one past the cap.
    assertLimit(call(request), "record_visits", 7, 6);
  }

  @Test void percentilesKeepAtMost65536ValuesWhileOtherStatisticsAreExempt() throws Exception {
    var store = SessionStoreFixture.store(temp, "kept", "kept-store");
    SessionStoreFixture.write(store, new SessionSpec("fits").member("m.wpilog", 0, 65_535 * 0.02, ramp(65_536)));
    SessionStoreFixture.write(store, new SessionSpec("over").member("m.wpilog", 0, 65_536 * 0.02, ramp(65_537)));
    allow(store.root());
    // fits holds 0, 1, ..., 65535. Its median is (32767 + 32768) / 2; type-7 p5 and p95 fall at
    // ranks 0.05 * 65535 = 3276.75 and 0.95 * 65535 = 62258.25, where each value equals its rank.
    var fits = call(args(sessions("fits"), metric("median", "/X", "median"), metric("p5", "/X", "p5"),
        metric("p95", "/X", "p95")));
    assertEquals("ok", status(fits), fits.toString());
    var kept = row(fits, "fits");
    assertEquals(32_767.5, value(kept, "median").get("value").getAsDouble(), 0);
    assertEquals(3_276.75, value(kept, "p5").get("value").getAsDouble(), 1e-9);
    assertEquals(62_258.25, value(kept, "p95").get("value").getAsDouble(), 1e-9);
    assertEquals(65_536, value(kept, "median").get("sample_count").getAsInt());
    // One value more is refused for every order statistic, never sampled down to the cap.
    for (var statistic : List.of("median", "p5", "p95")) {
      assertLimit(call(args(sessions("over"), metric("v", "/X", statistic))), "kept_values", 65_537, 65_536);
    }
    // Mean, deviation, minimum and maximum keep no values. over holds 0..65536, n = 65537:
    // mean 32768, n-1 variance n(n+1)/12 for consecutive integers, minimum 0, maximum 65536.
    var exempt = call(args(sessions("over"), metric("mean", "/X", "mean"), metric("sd", "/X", "std_dev"),
        metric("min", "/X", "min"), metric("max", "/X", "max")));
    assertEquals("ok", status(exempt), exempt.toString());
    var over = row(exempt, "over");
    assertEquals(32_768.0, value(over, "mean").get("value").getAsDouble(), 1e-9);
    assertEquals(Math.sqrt(65_537.0 * 65_538.0 / 12.0), value(over, "sd").get("value").getAsDouble(), 1e-6);
    assertEquals(0.0, value(over, "min").get("value").getAsDouble(), 0);
    assertEquals(65_536.0, value(over, "max").get("value").getAsDouble(), 0);
    assertEquals(65_537, value(over, "mean").get("sample_count").getAsInt());
  }

  // ==================== admission caps ====================

  @Test void sixtyFourSessionsEightMetricsAndSixteenWindowsAreTheMostAdmitted() throws Exception {
    var store = SessionStoreFixture.store(temp, "counts", "count-store");
    var ids = new String[65];
    for (int s = 0; s < 65; s++) {
      ids[s] = "s" + s;
      SessionStoreFixture.write(store, new SessionSpec(ids[s]).member("m.wpilog", 0, 0, SessionStoreFixture.doubles("/X", s)));
    }
    var seconds = new double[41];
    for (int i = 0; i <= 40; i++) seconds[i] = i;
    SessionStoreFixture.write(store, new SessionSpec("w").member("m.wpilog", 0, 40, SessionStoreFixture.doubles("/X", seconds)));
    allow(store.root());

    // 64 sessions, session s holding the one value s: the equal-weight mean of 0..63 is 31.5.
    var most = call(args(sessions(Arrays.copyOf(ids, 64)), metric("x", "/X", "mean")));
    assertEquals("ok", status(most), most.toString());
    assertCounts(most, 64, 64, 0);
    assertEquals(31.5, summary(most, "x").get("mean").getAsDouble(), 1e-12);
    assertLimit(call(args(sessions(ids), metric("x", "/X", "mean"))), "sessions", 65, 64);

    var metrics = new JsonObject[9];
    for (int m = 0; m < 9; m++) metrics[m] = metric("m" + m, "/X", "mean");
    var eight = call(args(sessions("s7"), Arrays.copyOf(metrics, 8)));
    assertEquals("ok", status(eight), eight.toString());
    assertEquals(8, eight.getAsJsonArray("summary").size());
    assertEquals(7.0, summary(eight, "m7").get("mean").getAsDouble(), 0);
    assertLimit(call(args(sessions("s7"), metrics)), "metrics", 9, 8);

    // w holds /X = i at i seconds. The half-open windows [2k, 2k+1) each keep the one sample at
    // 2k, so sixteen of them keep 0, 2, ..., 30, whose mean is 15.
    var sixteen = args(sessions("w"), metric("x", "/X", "mean"));
    sixteen.add("windows", windows(16));
    var fits = call(sixteen);
    assertEquals("ok", status(fits), fits.toString());
    assertEquals(15.0, value(row(fits, "w"), "x").get("value").getAsDouble(), 1e-12);
    assertEquals(16, value(row(fits, "w"), "x").get("sample_count").getAsInt());
    var seventeen = args(sessions("w"), metric("x", "/X", "mean"));
    seventeen.add("windows", windows(17));
    assertLimit(call(seventeen), "windows", 17, 16);
  }

  @Test void memberOccurrencesAndPooledValuesAreCappedWithTheirTrueCounts() throws Exception {
    var store = SessionStoreFixture.store(temp, "members", "member-store");
    SessionStoreFixture.write(store, new SessionSpec("one").member("m.wpilog", 0, 2, SessionStoreFixture.doubles("/X", 1, 2, 3)));
    SessionStoreFixture.write(store, new SessionSpec("two").member("a.wpilog", 0, 2, SessionStoreFixture.doubles("/X", 4, 5, 6))
        .member("b.wpilog", 3, 5, SessionStoreFixture.doubles("/Y", 7, 8, 9)));
    allow(store.root());
    var selection = new JsonArray();
    selection.add(session("one", null));
    selection.add(session("two", "a.wpilog"));
    // one names one WPILOG member and two names two: three occurrences.
    var caps = new Caps();
    caps.occurrences = 3;
    caps.apply();
    var admitted = call(args(selection, metric("x", "/X", "mean")));
    assertEquals(List.of("one", "two"), strings(summary(admitted, "x").getAsJsonArray("contributing_sessions")), admitted.toString());
    caps.occurrences = 2;
    caps.apply();
    assertLimit(call(args(selection, metric("x", "/X", "mean"))), "member_occurrences", 3, 2);

    // A pooled median keeps every pooled value: 1, 2, 3 from one and 4, 5, 6 from two's anchor.
    // Its type-7 median is (3 + 4) / 2.
    caps.occurrences = 3;
    caps.pooled = 6;
    caps.apply();
    var request = args(selection, metric("x", "/X", "median"));
    request.addProperty("pool_samples", true);
    var pooled = call(request);
    var pool = pooled.getAsJsonArray("pooled").get(0).getAsJsonObject();
    assertEquals(3.5, pool.get("value").getAsDouble(), 0, pooled.toString());
    assertEquals(6, pool.get("sample_count").getAsInt());
    caps.pooled = 5;
    caps.apply();
    assertLimit(call(request), "pooled_values", 6, 5);
  }

  @Test void confounderEvidenceIsCappedNotTruncated() throws Exception {
    var store = SessionStoreFixture.store(temp, "evidence", "evidence-store");
    String a = "a".repeat(40), b = "b".repeat(40), c = "c".repeat(40);
    SessionStoreFixture.write(store, new SessionSpec("s0").member("m.wpilog", 0, 2, SessionStoreFixture.all(
        SessionStoreFixture.doubles("/X", 1, 2, 3),
        SessionStoreFixture.strings("/RealMetadata/GitSHA", new double[] {0, 1, 2}, a, b, c))));
    allow(store.root());
    var request = args(sessions("s0"), metric("x", "/X", "mean"));
    var caps = new Caps();
    caps.facts = 3;
    caps.apply();
    var three = call(request);
    var git = three.getAsJsonObject("confounders").getAsJsonObject("git_sha");
    assertEquals(3, git.get("distinct_value_count").getAsInt(), three.toString());
    caps.facts = 2;
    caps.apply();
    assertLimit(call(request), "fact_values", 3, 2);
    // The encoded evidence is capped whole: the complete confounders object of the admitted call
    // is the size a refusal reports, and none of it is dropped to fit.
    long evidence = three.getAsJsonObject("confounders").toString().getBytes(StandardCharsets.UTF_8).length;
    caps.facts = 3;
    caps.evidence = 1;
    caps.apply();
    assertLimit(call(request), "confounder_bytes", evidence, 1);
  }

  @Test void theEncodedArgumentsAreCappedAt64KiB() throws Exception {
    var store = SessionStoreFixture.store(temp, "request", "request-store");
    SessionStoreFixture.write(store, new SessionSpec("s0").member("m.wpilog", 0, 2, SessionStoreFixture.doubles("/X", 1, 2, 3)));
    allow(store.root());
    // One entry name is padded until the encoded arguments are exactly 65,536 bytes, then 65,537.
    var exact = padded(65_536);
    assertEquals(65_536, exact.toString().getBytes(StandardCharsets.UTF_8).length);
    var admitted = call(exact);
    assertFalse(admitted.has("limit_name"), admitted.toString());
    assertEquals("no_match", status(admitted), "the padded entry is not in the session: " + admitted);
    var over = padded(65_537);
    assertEquals(65_537, over.toString().getBytes(StandardCharsets.UTF_8).length);
    assertLimit(call(over), "request_bytes", 65_537, 65_536);
  }

  static JsonObject padded(int bytes) {
    var metric = metric("x", "/", "mean");
    var request = args(sessions("s0"), metric);
    int base = request.toString().getBytes(StandardCharsets.UTF_8).length;
    metric.addProperty("name", "/" + "P".repeat(bytes - base));
    return request;
  }

  // ==================== the complete reply ====================

  @Test void aReplyOverItsLimitIsRefusedWholeWithTheTrueCounts() throws Exception {
    var store = SessionStoreFixture.store(temp, "reply", "reply-store");
    int base = 0;
    for (var id : List.of("s0", "s1", "s2")) {
      base += 10;
      SessionStoreFixture.write(store, new SessionSpec(id).member("m.wpilog", 0, 2,
          SessionStoreFixture.doubles("/X", base, base + 1, base + 2)));
    }
    allow(store.root());
    var registry = new ToolRegistry();
    RobotAnalysisTools.registerAll(registry);
    var handler = new McpMessageHandler(registry);
    var request = rpc(args(sessions("s0", "s1", "s2"), metric("x", "/X", "mean")));
    var whole = handler.handleMessage(request).response();
    var complete = toolBody(whole);
    assertEquals("ok", status(complete), complete.toString());
    assertCounts(complete, 3, 3, 0);
    // The reply as a client receives it, envelope, id and escaping included, measured here.
    long sent = REPLY_ENCODING.toJson(whole).getBytes(StandardCharsets.UTF_8).length;

    var caps = new Caps();
    caps.reply = (int) (sent / 2);
    caps.apply();
    var refused = toolBody(handler.handleMessage(request).response());
    assertEquals("error", status(refused), refused.toString());
    assertEquals("RESPONSE_TOO_LARGE", code(refused), refused.toString());
    assertEquals(sent / 2, refused.get("limit_bytes").getAsLong());
    // The same complete reply would have been sent, give or take the digits of its execution time.
    assertTrue(Math.abs(refused.get("reply_bytes").getAsLong() - sent) <= 8, refused + " against " + sent);
    // The true counts, and nothing of the answer itself: no rows and no summary of a prefix.
    assertCounts(refused, 3, 3, 0);
    assertEquals(1, refused.get("metric_count").getAsInt());
    for (var key : List.of("sessions", "summary", "pooled", "confounders")) {
      assertFalse(refused.has(key), "an oversized reply is refused whole, never cut: " + key);
    }
    assertFalse(refused.get("hint").getAsString().isBlank());

    caps.reply = (int) sent + 64;
    caps.apply();
    var within = toolBody(handler.handleMessage(request).response());
    assertEquals("ok", status(within), within.toString());
    assertEquals(3, within.getAsJsonArray("sessions").size());
  }

  // ==================== what stays responsive ====================

  @Test void captureAdmissionAndHealthStayResponsiveWhileTwoComparisonsRun() throws Exception {
    var store = twoSessions("shared");
    var registry = new ToolRegistry();
    registry.serverIdentity(ServerIdentity.ephemeral());
    RobotAnalysisTools.registerAll(registry);
    var server = new HttpTransport(registry, 0);
    server.start();
    var http = HttpClient.newHttpClient();
    String base = "http://127.0.0.1:" + server.getPort();
    var release = new CountDownLatch(1);
    var arrived = holdFirstTwo(release);
    var calls = new ArrayList<CompletableFuture<HttpResponse<String>>>();
    try {
      // Each comparison comes from its own MCP session, as two clients' calls do.
      for (var id : List.of("s0", "s1")) {
        calls.add(http.sendAsync(post(base, rpc(args(sessions(id), metric("x", "/X", "mean"))).toString(),
            initialize(http, base)), HttpResponse.BodyHandlers.ofString()));
      }
      // The checks below run even if the comparisons did not both arrive, so a comparison that
      // stalls the rest of the server is reported by the check it breaks.
      boolean bothRunning = arrived.await(10, TimeUnit.SECONDS);

      var health = assertDoesNotThrow(() -> http.send(HttpRequest.newBuilder(URI.create(base + "/health"))
          .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString()),
          "health must answer within 2 s while comparisons run");
      assertEquals(200, health.statusCode(), health.body());
      assertEquals("ok", JsonParser.parseString(health.body()).getAsJsonObject().get("status").getAsString());

      // The comparisons read the store through the process's registry, which a capture in the same
      // server shares: one store object owns the folder.
      var compared = manager.stores().store(store.root());
      compared.takeLongestTurnUs(); // the turns measured below are the capture's, while comparisons run
      {
        var placement = compared.captures(WALL);
        var loop = new ManualScheduler();
        var writer = new CaptureWriter(WALL, loop, CapturePolicy.ALL, placement);
        try {
          writer.connected(URI.create("ws://localhost/nt/test"), "networktables.first.wpi.edu");
          writer.timeSync(1_000_000, 0);
          writer.announce(TOPIC);
          for (int i = 0; i < 3; i++) writer.value(TOPIC, new ValueFrame(1, 1_000_100 + i * 20_000, 2, 17L + i), i * 20_000);
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
          long previousNanos = System.nanoTime();
          while (!writer.ready() && writer.admissionFailure() == null && System.nanoTime() < deadline) {
            Thread.sleep(2);
            long now = System.nanoTime();
            loop.advance(Math.max(1, (now - previousNanos) / 1_000));
            previousNanos = now;
          }
          assertNull(writer.admissionFailure(), "capture admission failed while comparisons ran");
          assertTrue(writer.ready(), "a capture into the compared store must be admitted within 5 s");
          assertTrue(compared.takeLongestTurnUs() < 1_000_000,
              "store turns stay within their one-second budget while comparisons run");
          writer.disconnected();
          loop.drain();
          placement.completion().get(10, TimeUnit.SECONDS);
        } finally {
          writer.shutdown();
          loop.drain();
          try {
            placement.completion().get(5, TimeUnit.SECONDS);
          } catch (Exception cleanup) {
            // Best effort: a failed check above is the finding, and this wait must not replace it.
          }
          loop.close();
        }
      }

      assertTrue(bothRunning, "both comparisons must be admitted and reading");
      var third = assertDoesNotThrow(() -> http.send(post(base, rpc(args(sessions("s0"),
          metric("x", "/X", "mean"))).toString(), initialize(http, base)), HttpResponse.BodyHandlers.ofString()),
          "a third comparison must be answered at once");
      var refused = toolBody(JsonParser.parseString(third.body()).getAsJsonObject());
      assertEquals("BUSY", code(refused), refused.toString());
    } finally {
      release.countDown();
      CompareSessionsTool.reading = () -> {};
    }
    try {
      for (int i = 0; i < 2; i++) {
        var reply = calls.get(i).get(30, TimeUnit.SECONDS);
        var body = toolBody(JsonParser.parseString(reply.body()).getAsJsonObject());
        assertEquals("ok", status(body), body.toString());
      }
    } finally {
      server.stop();
    }
  }

  static String initialize(HttpClient http, String base) throws Exception {
    var response = http.send(post(base, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}", null),
        HttpResponse.BodyHandlers.ofString());
    return response.headers().firstValue("Mcp-Session-Id").orElseThrow();
  }

  static HttpRequest post(String base, String body, String session) {
    var request = HttpRequest.newBuilder(URI.create(base + "/mcp")).timeout(Duration.ofSeconds(30))
        .POST(HttpRequest.BodyPublishers.ofString(body));
    if (session != null) request.header("Mcp-Session-Id", session);
    return request.build();
  }
}
