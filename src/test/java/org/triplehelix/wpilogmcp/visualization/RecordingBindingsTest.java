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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.fixtures.*;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.read.ReadException;

class RecordingBindingsTest {
  @TempDir Path temp;
  final LogManager logs = LogManager.getInstance();
  Set<Path> original; VisualizationWorkspace workspace; RecordingFixture fixture;
  @BeforeEach void start() throws Exception {
    temp = temp.toRealPath(); original = logs.getConfiguredDirectories(); logs.clearAllowedDirectories(); logs.addAllowedDirectory(temp);
    fixture = new RecordingFixture(temp.resolve("store"), 8);
    workspace = new VisualizationWorkspace(ServerIdentity.ephemeral(), logs);
  }
  @AfterEach void stop() throws Exception {
    workspace.close(); logs.addAllowedDirectory(temp); assertTrue(logs.release(temp).released(), "Compatibility must release every prefix/cursor");
    logs.clearAllowedDirectories(); original.forEach(logs::addAllowedDirectory);
  }
  static JsonObject config(String selector, String name, String type, String value, String field) {
    var out = VisualizationContracts.blank(); var r = new JsonObject(); r.addProperty("id", "signal");
    r.addProperty("selector_kind", selector); r.addProperty(selector, name);
    var types = new JsonArray(); types.add(type); r.add("accepted_entry_types", types);
    r.addProperty("value_type", value); r.addProperty("field_path", field); r.add("unit", JsonNull.INSTANCE);
    out.getAsJsonArray("requirements").add(r); return out;
  }
  JsonObject check(JsonObject config) throws Exception {
    var request = new JsonObject(); request.add("source_ref", fixture.reference); request.add("configuration", config);
    return workspace.check(request);
  }
  static JsonObject binding(JsonObject result) { return result.getAsJsonArray("bindings").get(0).getAsJsonObject(); }
  static void resolved(JsonObject result, String name) {
    assertEquals("compatible", result.get("compatibility").getAsString(), result.toString());
    assertEquals("resolved", binding(result).get("state").getAsString()); assertEquals(name, binding(result).get("entry").getAsString());
  }
  static void unresolved(JsonObject result, String code) {
    assertEquals("incompatible", result.get("compatibility").getAsString(), result.toString());
    assertEquals("unresolved", binding(result).get("state").getAsString()); assertTrue(binding(result).get("entry").isJsonNull());
    assertTrue(binding(result).getAsJsonArray("problems").asList().stream().anyMatch(p -> p.getAsJsonObject().get("code").getAsString().equals(code)), result.toString());
  }
  static void scalar(WpilogWriter w, String name, String type, String metadata, int n) throws Exception {
    int id = w.start(name, type, metadata, 0);
    for (int i = 0; i < n; i++) w.append(id, i * 1000L, type.equals("double") ? WpilogWriter.encodeDouble(i + 2) : WpilogWriter.encodeInt64(i + 2));
  }
  @Test void completeInventoryBindsNamesBeyondAnyLoadedPageAndReportsAbsentMembers() throws Exception {
    fixture = new RecordingFixture(temp.resolve("large"), 20_000);
    var out = check(config("entry", RecordingFixture.name(19_999), "int64", "number", null));
    resolved(out, RecordingFixture.name(19_999));
    assertEquals("fresh_recording_prefix", out.get("binding_basis").getAsString()); assertEquals(fixture.reference, out.get("source_ref"));
    assertEquals(2, binding(out).getAsJsonArray("members").size());
    var states = binding(out).getAsJsonArray("members").asList().stream().map(e -> e.getAsJsonObject().get("state").getAsString()).sorted().toList();
    assertEquals(List.of("absent", "resolved"), states);
    assertTrue(out.get("finalized").getAsBoolean());
    assertFalse(binding(out).getAsJsonArray("warnings").isEmpty(), "Unknown units stay visible on the combined binding");
  }
  @Test void publishedRoleUsesAllMembersAndRetainsExactCountsAndUnits() throws Exception {
    for (int m = 0; m < 2; m++) { int n = m + 1; fixture.rewrite(m, w -> scalar(w, "/SystemStats/BatteryVoltage", "double", "", n)); }
    var out = check(config("role", "battery_voltage", "double", "number", null)); resolved(out, "/SystemStats/BatteryVoltage");
    assertEquals("V", binding(out).getAsJsonObject("unit").get("symbol").getAsString());
    assertEquals(List.of("1", "2"), binding(out).getAsJsonArray("members").asList().stream().map(e -> e.getAsJsonObject().get("sample_count").getAsString()).sorted().toList());
  }
  @ParameterizedTest @ValueSource(strings = {"type", "metadata", "role"})
  void conflictingMembersNeverBecomeOneCompatibleBinding(String conflict) throws Exception {
    fixture.rewrite(0, w -> scalar(w, "/SystemStats/BatteryVoltage", "double", "", 2));
    fixture.rewrite(1, w -> scalar(w, conflict.equals("role") ? "/PDH/Voltage" : "/SystemStats/BatteryVoltage", conflict.equals("type") ? "int64" : "double", conflict.equals("metadata") ? "{\"unit\":\"A\"}" : "", 2));
    var c = config("role", "battery_voltage", "double", "number", null);
    c.getAsJsonArray("requirements").get(0).getAsJsonObject().getAsJsonArray("accepted_entry_types").add("int64");
    unresolved(check(c), conflict.equals("role") ? "ROLE_CONFLICT" : "DECLARATION_CONFLICT");
  }
  @Test void exactNamesNeverFallbackAndHeuristicsStayUnresolved() throws Exception {
    fixture.rewrite(0, w -> scalar(w, "/MaybeVoltage", "double", "", 1));
    unresolved(check(config("entry", "/missing", "double", "number", null)), "MISSING_ENTRY");
    unresolved(check(config("role", "battery_voltage", "double", "number", null)), "UNRESOLVED_ROLE");
  }
  @Test void unknownRoleIsActionableAndDoesNotLeakAnEnumClass() throws Exception {
    var out = check(config("role", "not_a_role", "double", "number", null)); unresolved(out, "UNRESOLVED_ROLE");
    assertFalse(out.toString().contains("SignalResolver")); assertTrue(out.toString().contains("Unknown role"));
  }
  @Test void expectedUnitMismatchNeverConvertsOrBinds() throws Exception {
    fixture.rewrite(0, w -> scalar(w, "/SystemStats/BatteryVoltage", "double", "", 1));
    var c = config("role", "battery_voltage", "double", "number", null);
    c.getAsJsonArray("requirements").get(0).getAsJsonObject().add("unit", JsonParser.parseString("{\"symbol\":\"A\",\"basis\":\"user_declared\"}"));
    unresolved(check(c), "INCOMPATIBLE_MEMBER");
  }
  static void struct(WpilogWriter w, String schema, double value) throws Exception {
    if (schema != null) { int s = w.start("/.schema/struct:Measure", "structschema", "", 0); w.append(s, 0, schema.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    int id = w.start("/measure", "struct:Measure", "", 0); w.append(id, 0, WpilogWriter.encodeDouble(value));
  }
  @Test void structuredProjectionUsesNativeEvidenceAndComparesAllMemberSchemas() throws Exception {
    fixture.rewrite(0, w -> struct(w, "double value;", 2)); fixture.rewrite(1, w -> struct(w, "double value;", 3));
    var c = config("entry", "/measure", "struct:Measure", "number", "value"); resolved(check(c), "/measure");
    fixture.rewrite(1, w -> struct(w, "double other;", 3)); unresolved(check(c), "SCHEMA_CONFLICT");
  }
  @ParameterizedTest @ValueSource(strings = {"missing", "invalid", "field", "index", "wildcard"})
  void missingOrInvalidStructuredEvidenceNeverBinds(String defect) throws Exception {
    fixture.rewrite(0, w -> struct(w, defect.equals("missing") ? null : defect.equals("invalid") ? "NoSuch child;" : "double value;", 2));
    unresolved(check(config("entry", "/measure", "struct:Measure", "number", defect.equals("field") ? "absent" : defect.equals("index") ? "value[9]" : defect.equals("wildcard") ? "[*]" : "value")), defect.equals("missing") || defect.equals("invalid") ? "SCHEMA_UNAVAILABLE" : "INCOMPATIBLE_MEMBER");
  }
  @Test void capabilityNamesBindingWithoutClaimingLivePanels() throws Exception {
    assertTrue(workspace.capabilities().get("recording_bindings").getAsBoolean()); assertFalse(workspace.capabilities().get("live").getAsBoolean());
    assertEquals(65_536, workspace.capabilities().getAsJsonObject("recording_binding_limits").get("max_evidence_records").getAsInt());
    assertEquals(1 << 20, workspace.capabilities().getAsJsonObject("recording_binding_limits").get("max_result_bytes").getAsInt());
  }
  @Test void everyCheckReadmitsTheSourceAndRejectsChangedHashOrGrant() throws Exception {
    resolved(check(config("entry", RecordingFixture.name(0), "int64", "number", null)), RecordingFixture.name(0));
    var path = fixture.directory.resolve("capture.wpilog"); var bytes = Files.readAllBytes(path); bytes[bytes.length - 1] ^= 1; Files.write(path, bytes);
    assertTrue(assertThrows(java.io.IOException.class, () -> check(config("entry", RecordingFixture.name(0), "int64", "number", null))).getMessage().contains("SOURCE_UNAVAILABLE"));
    logs.clearAllowedDirectories(); logs.addAllowedDirectory(Files.createDirectory(temp.resolve("different")));
    assertThrows(Exception.class, () -> check(config("entry", RecordingFixture.name(0), "int64", "number", null)));
  }
  @Test void capacityRefusesWithoutQueueingAndRechecksRevocationBeforeReturning() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(2); var release = new java.util.concurrent.CountDownLatch(1);
    var service = new RecordingBindings(source -> {
      var admission = workspace.sources.admitRecording(source); entered.countDown();
      try { release.await(); return admission; }
      catch (InterruptedException failure) { admission.close(); throw new java.io.InterruptedIOException(); }
    });
    var c = config("entry", RecordingFixture.name(0), "int64", "number", null);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var a = pool.submit(() -> service.check(fixture.reference, c)); var b = pool.submit(() -> service.check(fixture.reference, c));
      assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
      var refusal = assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> assertThrows(ReadException.class, () -> service.check(fixture.reference, c)));
      assertTrue(refusal.retryable()); assertEquals(org.triplehelix.wpilogmcp.read.ReadProblem.Code.RESOURCE_LIMIT, refusal.code());
      logs.clearAllowedDirectories(); logs.addAllowedDirectory(Files.createDirectory(temp.resolve("revoked"))); release.countDown();
      for (var result : List.of(a, b)) assertInstanceOf(java.io.IOException.class,
          assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(3, java.util.concurrent.TimeUnit.SECONDS)).getCause());
      logs.addAllowedDirectory(temp);
      resolved(service.check(fixture.reference, c), RecordingFixture.name(0));
    } finally { release.countDown(); pool.shutdownNow(); }
  }
  @Test void tooManyRoleCandidatesRefuseRatherThanSilentlyChoosingASubset() throws Exception {
    fixture.rewrite(0, w -> { for (int i = 0; i < 129; i++) scalar(w, "/candidate/" + i + "/InputVoltage", "double", "", 1); });
    var refusal = assertThrows(ReadException.class, () -> check(config("role", "battery_voltage", "double", "number", null)));
    assertEquals(org.triplehelix.wpilogmcp.read.ReadProblem.Code.RESOURCE_LIMIT, refusal.code()); assertFalse(refusal.retryable());
    assertTrue(refusal.getMessage().contains("128 candidates"));
  }

  @Test void oversizedRepliesRefuseBeforePublication() throws Exception {
    fixture.rewrite(0, w -> scalar(w, "/value", "double", "{\"unit\":\"" + "x".repeat(1 << 20) + "\"}", 1));
    var refusal = assertThrows(ReadException.class, () -> check(config("entry", "/value", "double", "number", null)));
    assertEquals(org.triplehelix.wpilogmcp.read.ReadProblem.Code.RESOURCE_LIMIT, refusal.code()); assertFalse(refusal.retryable());
    assertTrue(refusal.getMessage().contains("Recording compatibility result")); assertFalse(refusal.getMessage().contains("Arrow"));
  }
  @Test void occurrenceLimitIsCheckedBeforeResolverWorkAndAlwaysReleasesThePrefix() throws Exception {
    var service = new RecordingBindings(source -> {
      var original = workspace.sources.admitRecording(source);
      var members = original.prefix().members().stream().map(member -> {
        var snapshot = (org.triplehelix.wpilogmcp.read.ReadSnapshot) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{org.triplehelix.wpilogmcp.read.ReadSnapshot.class}, (proxy, method, args) -> {
              if (method.getName().equals("entries")) return new AbstractMap<String, org.triplehelix.wpilogmcp.log.EntryInfo>() {
                public int size() { return 200_001; }
                public Set<Entry<String, org.triplehelix.wpilogmcp.log.EntryInfo>> entrySet() { fail("Must refuse before traversing over-budget native inventory"); return Set.of(); }
              };
              try { return method.invoke(member.snapshot(), args); } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
            });
        return new org.triplehelix.wpilogmcp.read.server.OrdinalFollow.Member(member.id(), member.generation(), snapshot, member.contentSha256(), member.writerIdentity());
      }).toList();
      return new RecordedSources.Admission(new org.triplehelix.wpilogmcp.read.server.OrdinalFollow.Prefix(original.prefix().recordingId(), original.prefix().catalogRevision(), original.prefix().finalized(), members), original.access());
    });
    var failure = assertThrows(ReadException.class, () -> service.check(fixture.reference, config("entry", "/value", "double", "number", null)));
    assertEquals(org.triplehelix.wpilogmcp.read.ReadProblem.Code.RESOURCE_LIMIT, failure.code());
    assertTrue(logs.release(temp).released());
  }

  @Test void fileChangedDuringCompatibilityNeverPublishesStaleEvidence() throws Exception {
    var service = new RecordingBindings(source -> {
      var admission = workspace.sources.admitRecording(source);
      var path = fixture.directory.resolve("capture.wpilog");
      Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 20_000));
      return admission;
    });
    var failure = assertThrows(ReadException.class, () -> service.check(fixture.reference, config("entry", RecordingFixture.name(0), "int64", "number", null)));
    assertEquals(org.triplehelix.wpilogmcp.read.ReadProblem.Code.FILE_CHANGED, failure.code());
  }

}
