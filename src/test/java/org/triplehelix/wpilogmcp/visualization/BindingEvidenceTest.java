/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.*;
import org.triplehelix.wpilogmcp.log.*;
import org.triplehelix.wpilogmcp.read.*;
import org.triplehelix.wpilogmcp.read.server.ManagedReadSnapshot;
import org.triplehelix.wpilogmcp.tools.VisualizationBindings;

class BindingEvidenceTest {
  @TempDir Path temp;
  final LogManager logs = LogManager.getInstance(); Set<Path> original;
  @BeforeEach void start() throws Exception { temp = temp.toRealPath(); original = logs.getConfiguredDirectories(); logs.clearAllowedDirectories(); logs.addAllowedDirectory(temp); }
  @AfterEach void stop() throws Exception { assertTrue(logs.release(temp).released()); logs.clearAllowedDirectories(); original.forEach(logs::addAllowedDirectory); }
  Path file(String name, RecordingFixture.Contents contents) throws Exception {
    var p = temp.resolve(name + ".wpilog"); try (var w = new WpilogWriter(p, "")) { contents.write(w); } return p;
  }
  @Test void nativeWitnessesMatchTheExistingResolverForEveryRoleWithoutMaterializedHistory() throws Exception {
    var path = file("roles", w -> {
      RecordingBindingsTest.scalar(w, "/SystemStats/BatteryVoltage", "double", "", 3);
      RecordingBindingsTest.scalar(w, "/SystemStats/BrownoutVoltage", "double", "", 3);
      RecordingBindingsTest.scalar(w, "/RealOutputs/LoggedRobot/UserCodeMS", "double", "", 3);
      int ds = w.start("/DriverStation/Enabled", "boolean", "", 0); w.append(ds, 0, new byte[]{1});
      int a = w.start("/SmartDashboard/Auto/active", "string", "", 0); w.append(a, 0, new byte[]{65});
      int t = w.start("/SmartDashboard/Auto/.type", "string", "", 0); w.append(t, 0, "String Chooser".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    });
    try (var snapshot = logs.readAccess().open(path)) {
      var nativeLog = new BindingEvidence(snapshot, new BindingEvidence.Budget());
      for (var role : VisualizationBindings.roles()) {
        var c = RecordingBindingsTest.config("role", role.getAsString(), "double", "number", null);
        assertEquals(VisualizationBindings.check(((ManagedReadSnapshot) snapshot).log(), c), VisualizationBindings.check(nativeLog, c, true), role.getAsString());
      }
    }
  }
  @Test void witnessStopsAtFirstMatchAndLastValueUsesTimestampThenOrdinal() throws Exception {
    var path = file("times", w -> {
      int id = w.start("/value", "double", "", 0);
      w.append(id, 9_007_199_254_740_993L, WpilogWriter.encodeDouble(5));
      w.append(id, 9_007_199_254_740_992L, WpilogWriter.encodeDouble(7));
      w.append(id, 9_007_199_254_740_993L, WpilogWriter.encodeDouble(9));
      w.append(id, 1, WpilogWriter.encodeDouble(11));
    });
    try (var snapshot = logs.readAccess().open(path)) {
      var budget = new BindingEvidence.Budget(); var log = new BindingEvidence(snapshot, budget);
      assertTrue(log.anyValue("/value", v -> ((Number) v).doubleValue() == 5)); assertEquals(1, budget.records); assertEquals(8, budget.bytes);
      assertEquals(9.0, log.lastValue("/value", v -> true).orElseThrow().value()); assertEquals(5, budget.records);
      assertTrue(log.lastValue("/value", v -> false).isEmpty());
    }
  }
  @Test void recordBudgetIsSharedAcrossEvidenceScansAndFailsInsteadOfClaimingNoMatch() throws Exception {
    var path = file("count", w -> { int id = w.start("/value", "double", "", 0); for (int i = 0; i < BindingEvidence.MAX_RECORDS + 1; i++) w.append(id, i, WpilogWriter.encodeDouble(1)); });
    try (var snapshot = logs.readAccess().open(path)) {
      var budget = new BindingEvidence.Budget(); var log = new BindingEvidence(snapshot, budget);
      assertTrue(log.anyValue("/value", v -> true));
      var refusal = assertThrows(BindingLimit.class, () -> log.anyValue("/value", v -> false));
      assertTrue(refusal.getMessage().contains("65536")); assertEquals(BindingEvidence.MAX_RECORDS, budget.records);
    }
  }
  @Test void payloadAndIndividualRecordCapsRefuseWithoutTreatingOversizeAsMissing() throws Exception {
    var path = file("bytes", w -> { int id = w.start("/value", "raw", "", 0); for (int i = 0; i < 130; i++) w.append(id, i, new byte[64 << 10]); });
    try (var snapshot = logs.readAccess().open(path)) {
      var budget = new BindingEvidence.Budget(); var log = new BindingEvidence(snapshot, budget);
      assertThrows(BindingLimit.class, () -> log.anyValue("/value", v -> false)); assertEquals(8 << 20, budget.bytes); assertEquals(128, budget.records);
    }
    var huge = file("huge", w -> { int id = w.start("/value", "raw", "", 0); w.append(id, 0, new byte[(64 << 10) + 1]); });
    try (var snapshot = logs.readAccess().open(huge)) {
      var failure = assertThrows(java.io.UncheckedIOException.class, () -> new BindingEvidence(snapshot, new BindingEvidence.Budget()).anyValue("/value", v -> true));
      assertEquals(ReadProblem.Code.RESOURCE_LIMIT, ((ReadException) failure.getCause()).code());
    }
  }
  @Test void cancellationAndUnreadableRecordsDoNotBecomeNegativeEvidence() throws Exception {
    var path = file("malformed", w -> { int id = w.start("/value", "double", "", 0); w.append(id, 0, new byte[]{1}); });
    try (var snapshot = logs.readAccess().open(path)) {
      var log = new BindingEvidence(snapshot, new BindingEvidence.Budget());
      assertTrue(assertThrows(java.io.UncheckedIOException.class, () -> log.anyValue("/value", v -> true)).getMessage().contains("unreadable"));
      Thread.currentThread().interrupt();
      try { assertThrows(BindingLimit.class, () -> log.entries()); } finally { Thread.interrupted(); }
    }
  }
  @Test void cooperativeDeadlineRefusesEvenWithoutSampleDecoding() {
    var clock = new java.util.concurrent.atomic.AtomicLong(); var budget = new BindingEvidence.Budget(clock::get);
    clock.set(java.time.Duration.ofSeconds(10).toNanos() - 1); budget.check();
    clock.incrementAndGet(); assertThrows(BindingLimit.class, budget::check);
  }
  @Test void defaultEvidenceKeepsExistingChronologicalSemantics() {
    var log = new ParsedLog("fixture", Map.of("v", new EntryInfo(1, "v", "double", "")),
        Map.of("v", List.of(new TimestampedValue(1, 3.0), new TimestampedValue(2, 4.0), new TimestampedValue(2, 5.0))), 1, 2);
    assertTrue(log.anyValue("v", v -> v.equals(4.0))); assertFalse(log.anyValue("missing", v -> true));
    assertEquals(5.0, log.lastValue("v", v -> true).orElseThrow().value()); assertTrue(log.lastValue("missing", v -> true).isEmpty());
  }
}
