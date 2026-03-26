package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.Tool;

/**
 * Logic-level unit tests for QueryTools using synthetic log data.
 */
class QueryToolsLogicTest extends ToolTestBase {

  @Override
  protected void registerTools(ToolRegistry registry) {
    QueryTools.registerAll(registry);
  }

  @Nested
  @DisplayName("search_entries Tool")
  class SearchEntriesToolTests {

    @Test
    @DisplayName("finds entries matching substring")
    void findsEntriesMatchingSubstring() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/query.wpilog")
          .addNumericEntry("/Drive/FrontLeft/Speed", new double[]{0}, new double[]{0})
          .addNumericEntry("/Drive/FrontRight/Speed", new double[]{0}, new double[]{0})
          .addNumericEntry("/Elevator/Height", new double[]{0}, new double[]{0})
          .build();

      putLogInCache(log);

      var tool = findTool("search_entries");
      var args = new JsonObject();
      args.addProperty("path", "/test/query.wpilog");
      args.addProperty("pattern", "Drive");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(2, resultObj.get("match_count").getAsInt());

      var matches = resultObj.getAsJsonArray("matches");
      var matchStrings = new ArrayList<String>();
      matches.forEach(m -> matchStrings.add(m.getAsString()));

      assertTrue(matchStrings.contains("/Drive/FrontLeft/Speed"));
      assertTrue(matchStrings.contains("/Drive/FrontRight/Speed"));
      assertFalse(matchStrings.contains("/Elevator/Height"));
    }

    @Test
    @DisplayName("case insensitive by default")
    void caseInsensitiveByDefault() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/query.wpilog")
          .addNumericEntry("/Drive/Speed", new double[]{0}, new double[]{0})
          .build();

      putLogInCache(log);

      var tool = findTool("search_entries");
      var args = new JsonObject();
      args.addProperty("path", "/test/query.wpilog");
      args.addProperty("pattern", "drive"); // lowercase

      var result = tool.execute(args);
      assertTrue(result.getAsJsonObject().get("match_count").getAsInt() > 0);
    }
  }

  @Nested
  @DisplayName("list_entries Tool")
  class ListEntriesToolTests {

    @Test
    @DisplayName("lists all entries with types")
    void listsAllEntriesWithTypes() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/query.wpilog")
          .addNumericEntry("/A", new double[]{0}, new double[]{0})
          .addEntry("/B", "boolean", List.of(new TimestampedValue(0, true)))
          .build();

      putLogInCache(log);

      // CoreTools now has list_entries, not QueryTools.
      // But for this test, we care about what list_entries returns.
      // Let's verify what the tool actually returns.

      var capturingRegistry = new ToolRegistry() {
        @Override
        public void registerTool(Tool tool) {
          if (tool.name().equals("list_entries")) {
            QueryToolsLogicTest.this.tools.add(tool);
          }
        }
      };
      CoreTools.registerAll(capturingRegistry);

      var tool = findTool("list_entries");
      var args = new JsonObject();
      args.addProperty("path", "/test/query.wpilog");
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      var entries = resultObj.getAsJsonArray("entries");

      boolean foundA = false;
      boolean foundB = false;
      for (var e : entries) {
        var obj = e.getAsJsonObject();
        if (obj.get("name").getAsString().equals("/A")) {
          assertEquals("double", obj.get("type").getAsString());
          foundA = true;
        } else if (obj.get("name").getAsString().equals("/B")) {
          assertEquals("boolean", obj.get("type").getAsString());
          foundB = true;
        }
      }
      assertTrue(foundA && foundB);
    }
  }

  // ==================== find_condition Tests ====================

  @Nested
  @DisplayName("find_condition Tool")
  class FindConditionToolTests {

    private Tool tool;

    @BeforeEach
    void findConditionTool() {
      tool = findTool("find_condition");
    }

    private ParsedLog buildNumericLog(String entryName, double[] timestamps, double[] values) {
      return new MockLogBuilder()
          .setPath("/test/condition.wpilog")
          .addNumericEntry(entryName, timestamps, values)
          .build();
    }

    private JsonObject makeArgs(String name, String operator, double threshold) {
      var args = new JsonObject();
      args.addProperty("path", "/test/condition.wpilog");
      args.addProperty("name", name);
      args.addProperty("operator", operator);
      args.addProperty("threshold", threshold);
      return args;
    }

    // --- Operator tests ---

    @Test
    @DisplayName("lt operator detects value dropping below threshold")
    void ltOperator() throws Exception {
      var log = buildNumericLog("/Voltage",
          new double[]{0, 1, 2, 3},
          new double[]{12.0, 11.0, 10.0, 9.0});
      putLogInCache(log);

      var result = tool.execute(makeArgs("/Voltage", "lt", 11.0)).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      assertEquals(1, result.get("transition_count").getAsInt());
      var transitions = result.getAsJsonArray("transitions");
      assertEquals(2.0, transitions.get(0).getAsJsonObject().get("timestamp_sec").getAsDouble(), 0.001);
      assertEquals(10.0, transitions.get(0).getAsJsonObject().get("value").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("< symbol operator works same as lt")
    void lessThanSymbol() throws Exception {
      var log = buildNumericLog("/Voltage",
          new double[]{0, 1, 2},
          new double[]{12.0, 10.0, 8.0});
      putLogInCache(log);

      var result = tool.execute(makeArgs("/Voltage", "<", 11.0)).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      assertEquals(1, result.get("transition_count").getAsInt());
      assertEquals(10.0, result.getAsJsonArray("transitions").get(0)
          .getAsJsonObject().get("value").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("lte operator includes threshold value")
    void lteOperator() throws Exception {
      // Values go from above to exactly at threshold
      var log = buildNumericLog("/Voltage",
          new double[]{0, 1, 2},
          new double[]{12.0, 11.0, 10.0});
      putLogInCache(log);

      var result = tool.execute(makeArgs("/Voltage", "lte", 11.0)).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      assertEquals(1, result.get("transition_count").getAsInt());
      // Transition at t=1 where value == 11.0 (lte includes equality)
      assertEquals(1.0, result.getAsJsonArray("transitions").get(0)
          .getAsJsonObject().get("timestamp_sec").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("<= symbol operator works same as lte")
    void lteSymbol() throws Exception {
      var log = buildNumericLog("/Voltage",
          new double[]{0, 1, 2},
          new double[]{12.0, 11.0, 10.0});
      putLogInCache(log);

      var result = tool.execute(makeArgs("/Voltage", "<=", 11.0)).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      assertEquals(1, result.get("transition_count").getAsInt());
    }

    @Test
    @DisplayName("gt operator detects value rising above threshold")
    void gtOperator() throws Exception {
      var log = buildNumericLog("/Current",
          new double[]{0, 1, 2, 3},
          new double[]{10.0, 20.0, 50.0, 60.0});
      putLogInCache(log);

      var result = tool.execute(makeArgs("/Current", "gt", 40.0)).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      assertEquals(1, result.get("transition_count").getAsInt());
      assertEquals(2.0, result.getAsJsonArray("transitions").get(0)
          .getAsJsonObject().get("timestamp_sec").getAsDouble(), 0.001);
      assertEquals(50.0, result.getAsJsonArray("transitions").get(0)
          .getAsJsonObject().get("value").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("> symbol operator works same as gt")
    void gtSymbol() throws Exception {
      var log = buildNumericLog("/Current",
          new double[]{0, 1, 2},
          new double[]{10.0, 50.0, 60.0});
      putLogInCache(log);

      var result = tool.execute(makeArgs("/Current", ">", 40.0)).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      assertEquals(1, result.get("transition_count").getAsInt());
    }

    @Test
    @DisplayName("gte operator includes threshold value")
    void gteOperator() throws Exception {
      var log = buildNumericLog("/Current",
          new double[]{0, 1, 2},
          new double[]{10.0, 40.0, 60.0});
      putLogInCache(log);

      var result = tool.execute(makeArgs("/Current", "gte", 40.0)).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      assertEquals(1, result.get("transition_count").getAsInt());
      // Transition at t=1 where value == 40.0 (gte includes equality)
      assertEquals(1.0, result.getAsJsonArray("transitions").get(0)
          .getAsJsonObject().get("timestamp_sec").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName(">= symbol operator works same as gte")
    void gteSymbol() throws Exception {
      var log = buildNumericLog("/Current",
          new double[]{0, 1, 2},
          new double[]{10.0, 40.0, 60.0});
      putLogInCache(log);

      var result = tool.execute(makeArgs("/Current", ">=", 40.0)).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      assertEquals(1, result.get("transition_count").getAsInt());
    }

    @Test
    @DisplayName("eq operator finds values equal to threshold")
    void eqOperator() throws Exception {
      var log = buildNumericLog("/Sensor",
          new double[]{0, 1, 2, 3},
          new double[]{1.0, 5.0, 3.0, 5.0});
      putLogInCache(log);

      var result = tool.execute(makeArgs("/Sensor", "eq", 5.0)).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      // First transition at t=1 (false -> true), second at t=3 (false -> true again after t=2 was false)
      assertEquals(2, result.get("transition_count").getAsInt());
    }

    @Test
    @DisplayName("== symbol operator works same as eq")
    void eqSymbol() throws Exception {
      var log = buildNumericLog("/Sensor",
          new double[]{0, 1, 2, 3},
          new double[]{1.0, 5.0, 3.0, 5.0});
      putLogInCache(log);

      var result = tool.execute(makeArgs("/Sensor", "==", 5.0)).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      assertEquals(2, result.get("transition_count").getAsInt());
    }

    // --- Unknown operator ---

    @Test
    @DisplayName("unknown operator returns error")
    void unknownOperatorReturnsError() throws Exception {
      var log = buildNumericLog("/Sensor",
          new double[]{0, 1},
          new double[]{1.0, 2.0});
      putLogInCache(log);

      var result = tool.execute(makeArgs("/Sensor", "ne", 1.5)).getAsJsonObject();

      assertFalse(result.get("success").getAsBoolean());
      assertTrue(result.get("error").getAsString().contains("Unknown operator"));
    }

    // --- Equality tolerance edge cases ---

    @Test
    @DisplayName("eq matches within relative epsilon tolerance")
    void eqWithinRelativeEpsilon() throws Exception {
      // The eq tolerance is max(1e-9, |threshold| * 1e-6)
      // For threshold=1000.0, tolerance = 1000.0 * 1e-6 = 0.001
      double threshold = 1000.0;
      double withinTolerance = threshold + 0.0005; // within 0.001 tolerance

      var log = buildNumericLog("/Sensor",
          new double[]{0, 1},
          new double[]{0.0, withinTolerance});
      putLogInCache(log);

      var result = tool.execute(makeArgs("/Sensor", "eq", threshold)).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      assertEquals(1, result.get("transition_count").getAsInt(),
          "Value within relative epsilon should match eq");
    }

    @Test
    @DisplayName("eq does not match outside relative epsilon tolerance")
    void eqOutsideRelativeEpsilon() throws Exception {
      // For threshold=1000.0, tolerance = 0.001
      double threshold = 1000.0;
      double outsideTolerance = threshold + 0.01; // well outside 0.001 tolerance

      var log = buildNumericLog("/Sensor",
          new double[]{0, 1},
          new double[]{0.0, outsideTolerance});
      putLogInCache(log);

      var result = tool.execute(makeArgs("/Sensor", "eq", threshold)).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      assertEquals(0, result.get("transition_count").getAsInt(),
          "Value outside relative epsilon should not match eq");
    }

    @Test
    @DisplayName("eq uses absolute epsilon for values near zero")
    void eqAbsoluteEpsilonNearZero() throws Exception {
      // For threshold=0.0, tolerance = max(1e-9, 0) = 1e-9
      double threshold = 0.0;
      double withinAbsEpsilon = 0.5e-9; // within 1e-9 tolerance

      var log = buildNumericLog("/Sensor",
          new double[]{0, 1},
          new double[]{999.0, withinAbsEpsilon});
      putLogInCache(log);

      var result = tool.execute(makeArgs("/Sensor", "eq", threshold)).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      assertEquals(1, result.get("transition_count").getAsInt(),
          "Value within absolute epsilon of zero should match eq");
    }

    @Test
    @DisplayName("eq does not match just outside absolute epsilon for zero threshold")
    void eqOutsideAbsoluteEpsilonNearZero() throws Exception {
      // For threshold=0.0, tolerance = 1e-9
      double threshold = 0.0;
      double outsideAbsEpsilon = 1e-7; // well outside 1e-9 tolerance

      var log = buildNumericLog("/Sensor",
          new double[]{0, 1},
          new double[]{999.0, outsideAbsEpsilon});
      putLogInCache(log);

      var result = tool.execute(makeArgs("/Sensor", "eq", threshold)).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      assertEquals(0, result.get("transition_count").getAsInt(),
          "Value outside absolute epsilon should not match eq with zero threshold");
    }

    // --- Entry not found ---

    @Test
    @DisplayName("returns error when entry not found")
    void entryNotFound() throws Exception {
      var log = buildNumericLog("/Sensor",
          new double[]{0, 1},
          new double[]{1.0, 2.0});
      putLogInCache(log);

      var result = tool.execute(makeArgs("/NonExistent", "gt", 0.0)).getAsJsonObject();

      assertFalse(result.get("success").getAsBoolean());
      assertTrue(result.get("error").getAsString().contains("Entry not found"));
    }

    // --- Non-numeric entry type ---

    @Test
    @DisplayName("returns error for non-numeric entry type")
    void nonNumericEntryType() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/condition.wpilog")
          .addEntry("/Console/Output", "string",
              List.of(new TimestampedValue(0.0, "hello"),
                  new TimestampedValue(1.0, "world")))
          .build();
      putLogInCache(log);

      var result = tool.execute(makeArgs("/Console/Output", "gt", 5.0)).getAsJsonObject();

      assertFalse(result.get("success").getAsBoolean());
      assertTrue(result.get("error").getAsString().contains("not numeric"));
    }

    // --- Empty values list ---

    @Test
    @DisplayName("returns error for entry with no values")
    void emptyValuesList() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/condition.wpilog")
          .addNumericEntry("/EmptyEntry", new double[]{}, new double[]{})
          .build();
      putLogInCache(log);

      var result = tool.execute(makeArgs("/EmptyEntry", "gt", 0.0)).getAsJsonObject();

      assertFalse(result.get("success").getAsBoolean());
      assertTrue(result.get("error").getAsString().contains("No values"));
    }

    // --- Multiple transitions ---

    @Test
    @DisplayName("detects multiple threshold crossings")
    void multipleTransitions() throws Exception {
      // Voltage oscillates across 11.0 threshold multiple times
      var log = buildNumericLog("/Voltage",
          new double[]{0, 1, 2, 3, 4, 5, 6, 7},
          new double[]{12.0, 10.0, 12.0, 10.0, 12.0, 10.0, 12.0, 10.0});
      putLogInCache(log);

      var result = tool.execute(makeArgs("/Voltage", "lt", 11.0)).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      // Transitions: t=1 (12->10), t=3 (12->10), t=5 (12->10), t=7 (12->10)
      assertEquals(4, result.get("transition_count").getAsInt());

      var transitions = result.getAsJsonArray("transitions");
      assertEquals(1.0, transitions.get(0).getAsJsonObject().get("timestamp_sec").getAsDouble(), 0.001);
      assertEquals(3.0, transitions.get(1).getAsJsonObject().get("timestamp_sec").getAsDouble(), 0.001);
      assertEquals(5.0, transitions.get(2).getAsJsonObject().get("timestamp_sec").getAsDouble(), 0.001);
      assertEquals(7.0, transitions.get(3).getAsJsonObject().get("timestamp_sec").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("limit parameter restricts number of transitions returned")
    void limitRestrictsTransitions() throws Exception {
      var log = buildNumericLog("/Voltage",
          new double[]{0, 1, 2, 3, 4, 5, 6, 7},
          new double[]{12.0, 10.0, 12.0, 10.0, 12.0, 10.0, 12.0, 10.0});
      putLogInCache(log);

      var args = makeArgs("/Voltage", "lt", 11.0);
      args.addProperty("limit", 2);

      var result = tool.execute(args).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      assertEquals(2, result.get("transition_count").getAsInt());
    }

    @Test
    @DisplayName("no transitions when condition is never met")
    void noTransitions() throws Exception {
      var log = buildNumericLog("/Voltage",
          new double[]{0, 1, 2, 3},
          new double[]{12.0, 12.5, 13.0, 12.8});
      putLogInCache(log);

      var result = tool.execute(makeArgs("/Voltage", "lt", 10.0)).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      assertEquals(0, result.get("transition_count").getAsInt());
    }

    @Test
    @DisplayName("condition true from start counts as first transition")
    void conditionTrueFromStart() throws Exception {
      // First value already satisfies condition
      var log = buildNumericLog("/Voltage",
          new double[]{0, 1, 2},
          new double[]{5.0, 6.0, 7.0});
      putLogInCache(log);

      var result = tool.execute(makeArgs("/Voltage", "lt", 11.0)).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      // wasTrue starts false, so first value satisfying condition is a transition
      assertEquals(1, result.get("transition_count").getAsInt());
      assertEquals(0.0, result.getAsJsonArray("transitions").get(0)
          .getAsJsonObject().get("timestamp_sec").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("condition string is formatted correctly in response")
    void conditionStringFormatting() throws Exception {
      var log = buildNumericLog("/Voltage",
          new double[]{0, 1},
          new double[]{12.0, 10.0});
      putLogInCache(log);

      var result = tool.execute(makeArgs("/Voltage", "lte", 11.0)).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      assertEquals("/Voltage <= 11.0", result.get("condition").getAsString());
    }
  }

  // ==================== search_strings Tests ====================

  @Nested
  @DisplayName("search_strings Tool")
  class SearchStringsToolTests {

    private Tool tool;

    @BeforeEach
    void findSearchStringsTool() {
      tool = findTool("search_strings");
    }

    @Test
    @DisplayName("finds matching strings in string entries")
    void basicPatternMatching() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/strings.wpilog")
          .addEntry("/Console/Output", "string", List.of(
              new TimestampedValue(0.0, "Starting auto mode"),
              new TimestampedValue(1.0, "ERROR: motor stall detected"),
              new TimestampedValue(2.0, "Teleop enabled"),
              new TimestampedValue(3.0, "ERROR: CAN timeout on device 5")))
          .build();
      putLogInCache(log);

      var args = new JsonObject();
      args.addProperty("path", "/test/strings.wpilog");
      args.addProperty("pattern", "ERROR");

      var result = tool.execute(args).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      assertEquals(2, result.get("match_count").getAsInt());

      var matches = result.getAsJsonArray("matches");
      assertEquals(1.0, matches.get(0).getAsJsonObject().get("timestamp_sec").getAsDouble(), 0.001);
      assertTrue(matches.get(0).getAsJsonObject().get("value").getAsString().contains("motor stall"));
      assertEquals(3.0, matches.get(1).getAsJsonObject().get("timestamp_sec").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("entry_pattern filters which entries to search")
    void entryPatternFiltering() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/strings.wpilog")
          .addEntry("/Console/StdOut", "string", List.of(
              new TimestampedValue(0.0, "hello from stdout")))
          .addEntry("/Console/StdErr", "string", List.of(
              new TimestampedValue(0.0, "hello from stderr")))
          .addEntry("/Other/Log", "string", List.of(
              new TimestampedValue(0.0, "hello from other")))
          .build();
      putLogInCache(log);

      var args = new JsonObject();
      args.addProperty("path", "/test/strings.wpilog");
      args.addProperty("pattern", "hello");
      args.addProperty("entry_pattern", "Console");

      var result = tool.execute(args).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      assertEquals(2, result.get("match_count").getAsInt());

      var matches = result.getAsJsonArray("matches");
      var entryNames = new ArrayList<String>();
      for (var m : matches) {
        entryNames.add(m.getAsJsonObject().get("entry").getAsString());
      }
      assertTrue(entryNames.stream().allMatch(n -> n.contains("Console")));
      assertFalse(entryNames.contains("/Other/Log"));
    }

    @Test
    @DisplayName("search is case insensitive")
    void caseInsensitiveMatching() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/strings.wpilog")
          .addEntry("/Console/Output", "string", List.of(
              new TimestampedValue(0.0, "Warning: low battery"),
              new TimestampedValue(1.0, "WARNING: CAN bus error"),
              new TimestampedValue(2.0, "warning: encoder disconnect")))
          .build();
      putLogInCache(log);

      var args = new JsonObject();
      args.addProperty("path", "/test/strings.wpilog");
      args.addProperty("pattern", "warning");

      var result = tool.execute(args).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      assertEquals(3, result.get("match_count").getAsInt());
    }

    @Test
    @DisplayName("limit parameter restricts number of results")
    void limitParameter() throws Exception {
      var tvs = new ArrayList<TimestampedValue>();
      for (int i = 0; i < 20; i++) {
        tvs.add(new TimestampedValue(i * 0.1, "message " + i));
      }
      var log = new MockLogBuilder()
          .setPath("/test/strings.wpilog")
          .addEntry("/Console/Output", "string", tvs)
          .build();
      putLogInCache(log);

      var args = new JsonObject();
      args.addProperty("path", "/test/strings.wpilog");
      args.addProperty("pattern", "message");
      args.addProperty("limit", 5);

      var result = tool.execute(args).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      assertEquals(5, result.get("match_count").getAsInt());
    }

    @Test
    @DisplayName("returns zero matches when pattern not found")
    void noMatchingEntries() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/strings.wpilog")
          .addEntry("/Console/Output", "string", List.of(
              new TimestampedValue(0.0, "all systems nominal"),
              new TimestampedValue(1.0, "teleop active")))
          .build();
      putLogInCache(log);

      var args = new JsonObject();
      args.addProperty("path", "/test/strings.wpilog");
      args.addProperty("pattern", "FATAL_CRASH");

      var result = tool.execute(args).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      assertEquals(0, result.get("match_count").getAsInt());
      assertEquals(0, result.getAsJsonArray("matches").size());
    }

    @Test
    @DisplayName("returns zero matches when log has no string entries")
    void noStringEntriesInLog() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/strings.wpilog")
          .addNumericEntry("/Sensor/Value", new double[]{0, 1}, new double[]{1.0, 2.0})
          .addEntry("/Flag/Active", "boolean",
              List.of(new TimestampedValue(0.0, true)))
          .build();
      putLogInCache(log);

      var args = new JsonObject();
      args.addProperty("path", "/test/strings.wpilog");
      args.addProperty("pattern", "anything");

      var result = tool.execute(args).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      assertEquals(0, result.get("match_count").getAsInt());
    }
  }

  // ==================== get_types Tests ====================

  @Nested
  @DisplayName("get_types Tool")
  class GetTypesToolTests {

    private Tool tool;

    @BeforeEach
    void findGetTypesTool() {
      tool = findTool("get_types");
    }

    @Test
    @DisplayName("groups entries by type with correct counts")
    void basicTypeGrouping() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/types.wpilog")
          .addNumericEntry("/Drive/Speed", new double[]{0}, new double[]{0})
          .addNumericEntry("/Drive/Angle", new double[]{0}, new double[]{0})
          .addNumericEntry("/Arm/Position", new double[]{0}, new double[]{0})
          .addEntry("/Robot/Enabled", "boolean",
              List.of(new TimestampedValue(0.0, true)))
          .addEntry("/Console/Output", "string",
              List.of(new TimestampedValue(0.0, "hello")))
          .build();
      putLogInCache(log);

      var args = new JsonObject();
      args.addProperty("path", "/test/types.wpilog");
      var result = tool.execute(args).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      assertEquals(3, result.get("type_count").getAsInt());

      var types = result.getAsJsonArray("types");
      assertEquals(3, types.size());

      // Types should be sorted alphabetically
      boolean foundBoolean = false;
      boolean foundDouble = false;
      boolean foundString = false;
      for (var t : types) {
        var typeObj = t.getAsJsonObject();
        var typeName = typeObj.get("type").getAsString();
        switch (typeName) {
          case "boolean" -> {
            assertEquals(1, typeObj.get("entry_count").getAsInt());
            foundBoolean = true;
          }
          case "double" -> {
            assertEquals(3, typeObj.get("entry_count").getAsInt());
            var entries = typeObj.getAsJsonArray("entries");
            assertEquals(3, entries.size());
            foundDouble = true;
          }
          case "string" -> {
            assertEquals(1, typeObj.get("entry_count").getAsInt());
            foundString = true;
          }
        }
      }
      assertTrue(foundBoolean, "Should have boolean type");
      assertTrue(foundDouble, "Should have double type");
      assertTrue(foundString, "Should have string type");
    }

    @Test
    @DisplayName("entries within each type are sorted alphabetically")
    void entriesSortedWithinType() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/types.wpilog")
          .addNumericEntry("/Z/Last", new double[]{0}, new double[]{0})
          .addNumericEntry("/A/First", new double[]{0}, new double[]{0})
          .addNumericEntry("/M/Middle", new double[]{0}, new double[]{0})
          .build();
      putLogInCache(log);

      var args = new JsonObject();
      args.addProperty("path", "/test/types.wpilog");
      var result = tool.execute(args).getAsJsonObject();

      assertTrue(result.get("success").getAsBoolean());
      var types = result.getAsJsonArray("types");
      assertEquals(1, types.size());

      var entries = types.get(0).getAsJsonObject().getAsJsonArray("entries");
      assertEquals("/A/First", entries.get(0).getAsString());
      assertEquals("/M/Middle", entries.get(1).getAsString());
      assertEquals("/Z/Last", entries.get(2).getAsString());
    }
  }
}
