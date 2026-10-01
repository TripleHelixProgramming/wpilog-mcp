/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.tools.ResultContract.Status;

@DisplayName("ResultContract")
class ResultContractTest {

  static JsonObject enforce(JsonObject o) {
    return ResultContract.enforce(o).getAsJsonObject();
  }

  @Nested
  @DisplayName("status and success")
  class StatusTests {

    @Test
    @DisplayName("a success without status becomes ok, placed right after success")
    void successDefaultsToOk() {
      var raw = new JsonObject();
      raw.addProperty("count", 3);
      raw.addProperty("success", true);
      var out = enforce(raw);
      assertTrue(out.get("success").getAsBoolean());
      assertEquals("ok", out.get("status").getAsString());
      var keys = new ArrayList<>(out.keySet());
      assertEquals(List.of("success", "status", "count"), keys);
    }

    @Test
    @DisplayName("a failure without status becomes error and keeps its message")
    void failureDefaultsToError() {
      var out = enforce(ToolUtils.errorResult("Entry not found: /x"));
      assertFalse(out.get("success").getAsBoolean());
      assertEquals("error", out.get("status").getAsString());
      assertEquals("Entry not found: /x", out.get("error").getAsString());
    }

    @Test
    @DisplayName("not_applicable and no_match force success to false")
    void nonSuccessStatuses() {
      for (var status : List.of(Status.NOT_APPLICABLE, Status.NO_MATCH)) {
        var raw = new JsonObject();
        raw.addProperty("success", true); // a tool that got it wrong
        raw.addProperty("status", status.wire());
        raw.addProperty("reason", "nothing to analyze");
        var out = enforce(raw);
        assertFalse(out.get("success").getAsBoolean(), status.wire());
        assertEquals(status.wire(), out.get("status").getAsString());
      }
    }

    @Test
    @DisplayName("partial is a success")
    void partialIsSuccess() {
      var raw = new JsonObject();
      raw.addProperty("success", false);
      raw.addProperty("status", "partial");
      assertTrue(enforce(raw).get("success").getAsBoolean());
    }

    @Test
    @DisplayName("an unknown status falls back to the success flag")
    void unknownStatus() {
      var raw = new JsonObject();
      raw.addProperty("success", true);
      raw.addProperty("status", "maybe");
      assertEquals("ok", enforce(raw).get("status").getAsString());
    }

    @Test
    @DisplayName("no_match without a reason gets one")
    void reasonDefaulted() {
      var raw = new JsonObject();
      raw.addProperty("success", false);
      raw.addProperty("status", "no_match");
      var out = enforce(raw);
      assertFalse(out.get("reason").getAsString().isBlank());
    }

    @Test
    @DisplayName("wire forms round-trip and only ok/partial are successes")
    void wireForms() {
      for (var s : Status.values()) {
        assertEquals(s, Status.fromWire(s.wire()));
        assertEquals(s == Status.OK || s == Status.PARTIAL, s.isSuccess());
      }
      assertNull(Status.fromWire(null));
      assertNull(Status.fromWire("OK"));
    }

    @Test
    @DisplayName("non-object results pass through unchanged")
    void nonObjects() {
      assertNull(ResultContract.enforce(null));
      var array = new JsonArray();
      assertSame(array, ResultContract.enforce(array));
    }
  }

  @Nested
  @DisplayName("non-finite numbers")
  class NonFiniteTests {

    @Test
    @DisplayName("NaN and infinities become null and are named in a warning and in _metadata")
    void sanitized() {
      var raw = new JsonObject();
      raw.addProperty("success", true);
      raw.addProperty("rmse", Double.NaN);
      var nested = new JsonObject();
      nested.addProperty("max", Double.POSITIVE_INFINITY);
      nested.addProperty("min", 1.5);
      raw.add("stats", nested);
      var list = new JsonArray();
      list.add(1.0);
      list.add(Double.NEGATIVE_INFINITY);
      raw.add("values", list);

      var out = enforce(raw);
      assertTrue(out.get("rmse").isJsonNull());
      assertTrue(out.getAsJsonObject("stats").get("max").isJsonNull());
      assertEquals(1.5, out.getAsJsonObject("stats").get("min").getAsDouble());
      assertTrue(out.getAsJsonArray("values").get(1).isJsonNull());
      var fields = out.getAsJsonObject("_metadata").getAsJsonArray("non_finite_fields");
      assertEquals(List.of("rmse", "stats.max", "values[1]"),
          new Gson().fromJson(fields, List.class));
      var warning = out.getAsJsonArray("warnings").get(0).getAsString();
      assertTrue(warning.contains("rmse") && warning.contains("values[1]"), warning);
    }

    @Test
    @DisplayName("the serialized result is strict JSON")
    void strictJson() {
      var raw = new JsonObject();
      raw.addProperty("success", true);
      raw.addProperty("correlation", Double.NaN);
      var text = new Gson().toJson(enforce(raw));
      assertFalse(text.contains(":NaN"), text); // no bare NaN token as a value
      var reader = new com.google.gson.stream.JsonReader(new java.io.StringReader(text));
      reader.setStrictness(com.google.gson.Strictness.STRICT);
      assertDoesNotThrow(() -> JsonParser.parseReader(reader));
    }

    @Test
    @DisplayName("more than ten non-finite fields are summarized in the warning")
    void manyFields() {
      var raw = new JsonObject();
      raw.addProperty("success", true);
      var list = new JsonArray();
      for (int i = 0; i < 25; i++) list.add(Double.NaN);
      raw.add("values", list);
      var out = enforce(raw);
      assertEquals(25, out.getAsJsonObject("_metadata").getAsJsonArray("non_finite_fields").size());
      assertTrue(out.getAsJsonArray("warnings").get(0).getAsString().contains("and 15 more"));
    }

    @Test
    @DisplayName("existing warnings and metadata are kept")
    void keepsExisting() {
      var raw = ResponseBuilder.success().addWarning("first").addMetadata("k", 1)
          .addProperty("x", Double.NaN).build();
      var out = enforce(raw);
      assertEquals("first", out.getAsJsonArray("warnings").get(0).getAsString());
      assertEquals(2, out.getAsJsonArray("warnings").size());
      assertEquals(1, out.getAsJsonObject("_metadata").get("k").getAsInt());
    }
  }

  @Nested
  @DisplayName("ResponseBuilder helpers")
  class BuilderTests {

    @Test
    @DisplayName("notApplicable and noMatch carry status, reason, looked_for, and hint")
    void nonSuccessBuilders() {
      var na = enforce(ResponseBuilder.notApplicable("no autonomous period").build());
      assertEquals("not_applicable", na.get("status").getAsString());
      assertFalse(na.get("success").getAsBoolean());
      assertEquals("no autonomous period", na.get("reason").getAsString());

      var nm = enforce(ResponseBuilder.noMatch("no vision entries")
          .lookedFor(List.of("names containing hastarget", "struct arrays with a Pose3d field"))
          .hint("pass vision_prefix").build());
      assertEquals("no_match", nm.get("status").getAsString());
      assertEquals(2, nm.getAsJsonArray("looked_for").size());
      assertEquals("pass vision_prefix", nm.get("hint").getAsString());
    }

    @Test
    @DisplayName("a skipped section makes a success partial unless a status is set")
    void skippedMakesPartial() {
      var partial = enforce(ResponseBuilder.success().addProperty("a", 1)
          .addSkipped("wheel_slip", "no setpoint entries").build());
      assertEquals("partial", partial.get("status").getAsString());
      assertTrue(partial.get("success").getAsBoolean());
      var skipped = partial.getAsJsonArray("skipped").get(0).getAsJsonObject();
      assertEquals("wheel_slip", skipped.get("section").getAsString());
      assertEquals("no setpoint entries", skipped.get("reason").getAsString());

      var explicit = enforce(ResponseBuilder.success().status(Status.OK)
          .addSkipped("x", "y").build());
      assertEquals("ok", explicit.get("status").getAsString());
    }

    @Test
    @DisplayName("inputs record entries by role and the time window")
    void inputs() {
      var out = enforce(ResponseBuilder.success().addInput("voltage", "/SystemStats/BatteryVoltage")
          .addInput("ignored", null).addInputWindow(10.0, null).build());
      var inputs = out.getAsJsonObject("inputs");
      assertEquals("/SystemStats/BatteryVoltage",
          inputs.getAsJsonObject("entries").get("voltage").getAsString());
      assertFalse(inputs.getAsJsonObject("entries").has("ignored"));
      assertEquals(10.0, inputs.getAsJsonObject("window").get("start").getAsDouble());
      assertFalse(inputs.getAsJsonObject("window").has("end"));
    }

    @Test
    @DisplayName("limited lists report total, returned, and limit")
    void limitedList() {
      var items = new JsonArray();
      items.add(1);
      items.add(2);
      var out = enforce(ResponseBuilder.success().addLimitedList("anomalies", items, 40, 2).build());
      assertEquals(2, out.getAsJsonArray("anomalies").size());
      var limit = out.getAsJsonObject("limits").getAsJsonObject("anomalies");
      assertEquals(40, limit.get("total").getAsLong());
      assertEquals(2, limit.get("returned").getAsInt());
      assertEquals(2, limit.get("limit").getAsInt());
    }

    @Test
    @DisplayName("a builder without a status leaves status to the enforcer")
    void noStatusByDefault() {
      var built = ResponseBuilder.success().addProperty("a", 1).build();
      assertFalse(built.has("status"));
      assertEquals("ok", enforce(built).get("status").getAsString());
    }
  }
}
