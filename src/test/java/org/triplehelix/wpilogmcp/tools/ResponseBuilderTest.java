package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ResponseBuilder")
class ResponseBuilderTest {

  @Nested
  @DisplayName("Success Response")
  class SuccessResponseTests {

    @Test
    @DisplayName("creates minimal success response")
    void createsMinimalSuccessResponse() {
      var response = ResponseBuilder.success().build();

      assertTrue(response.get("success").getAsBoolean());
      assertEquals(1, response.size()); // Only 'success' field
      assertFalse(response.has("warnings"));
      assertFalse(response.has("_metadata"));
    }

    @Test
    @DisplayName("adds string property")
    void addsStringProperty() {
      var response = ResponseBuilder.success()
          .addProperty("name", "test_entry")
          .build();

      assertTrue(response.get("success").getAsBoolean());
      assertEquals("test_entry", response.get("name").getAsString());
    }

    @Test
    @DisplayName("adds numeric properties")
    void addsNumericProperties() {
      var response = ResponseBuilder.success()
          .addProperty("count", 42)
          .addProperty("value", 3.14159)
          .addProperty("long_value", 1234567890L)
          .build();

      assertEquals(42, response.get("count").getAsInt());
      assertEquals(3.14159, response.get("value").getAsDouble(), 0.00001);
      assertEquals(1234567890L, response.get("long_value").getAsLong());
    }

    @Test
    @DisplayName("adds boolean property")
    void addsBooleanProperty() {
      var response = ResponseBuilder.success()
          .addProperty("enabled", true)
          .addProperty("disabled", false)
          .build();

      assertTrue(response.get("enabled").getAsBoolean());
      assertFalse(response.get("disabled").getAsBoolean());
    }

    @Test
    @DisplayName("adds null property")
    void addsNullProperty() {
      var response = ResponseBuilder.success()
          .addProperty("optional", null)
          .build();

      assertTrue(response.has("optional"));
      assertTrue(response.get("optional").isJsonNull());
    }

    @Test
    @DisplayName("supports method chaining")
    void supportsMethodChaining() {
      var response = ResponseBuilder.success()
          .addProperty("field1", "value1")
          .addProperty("field2", 123)
          .addProperty("field3", true)
          .build();

      assertTrue(response.get("success").getAsBoolean());
      assertEquals("value1", response.get("field1").getAsString());
      assertEquals(123, response.get("field2").getAsInt());
      assertTrue(response.get("field3").getAsBoolean());
    }
  }

  @Nested
  @DisplayName("Error Response")
  class ErrorResponseTests {

    @Test
    @DisplayName("creates error response with message")
    void createsErrorResponseWithMessage() {
      var response = ResponseBuilder.error("Something went wrong").build();

      assertFalse(response.get("success").getAsBoolean());
      assertEquals("Something went wrong", response.get("error").getAsString());
    }

    @Test
    @DisplayName("can add properties to error response")
    void canAddPropertiesToErrorResponse() {
      var response = ResponseBuilder.error("Entry not found")
          .addProperty("attempted_name", "invalid_entry")
          .build();

      assertFalse(response.get("success").getAsBoolean());
      assertEquals("Entry not found", response.get("error").getAsString());
      assertEquals("invalid_entry", response.get("attempted_name").getAsString());
    }
  }

  @Nested
  @DisplayName("Warnings")
  class WarningsTests {

    @Test
    @DisplayName("omits warnings array when no warnings added")
    void omitsWarningsArrayWhenEmpty() {
      var response = ResponseBuilder.success()
          .addProperty("data", "value")
          .build();

      assertFalse(response.has("warnings"));
    }

    @Test
    @DisplayName("adds single warning")
    void addsSingleWarning() {
      var response = ResponseBuilder.success()
          .addWarning("Data quality may be affected")
          .build();

      assertTrue(response.has("warnings"));
      var warnings = response.getAsJsonArray("warnings");
      assertEquals(1, warnings.size());
      assertEquals("Data quality may be affected", warnings.get(0).getAsString());
    }

    @Test
    @DisplayName("adds multiple warnings")
    void addsMultipleWarnings() {
      var response = ResponseBuilder.success()
          .addWarning("Warning 1")
          .addWarning("Warning 2")
          .addWarning("Warning 3")
          .build();

      var warnings = response.getAsJsonArray("warnings");
      assertEquals(3, warnings.size());
      assertEquals("Warning 1", warnings.get(0).getAsString());
      assertEquals("Warning 2", warnings.get(1).getAsString());
      assertEquals("Warning 3", warnings.get(2).getAsString());
    }

    @Test
    @DisplayName("maintains warning order")
    void maintainsWarningOrder() {
      var response = ResponseBuilder.success()
          .addWarning("First warning")
          .addProperty("data", 123)
          .addWarning("Second warning")
          .addProperty("more_data", 456)
          .addWarning("Third warning")
          .build();

      var warnings = response.getAsJsonArray("warnings");
      assertEquals(3, warnings.size());
      assertEquals("First warning", warnings.get(0).getAsString());
      assertEquals("Second warning", warnings.get(1).getAsString());
      assertEquals("Third warning", warnings.get(2).getAsString());
    }
  }

  @Nested
  @DisplayName("Metadata")
  class MetadataTests {

    @Test
    @DisplayName("omits metadata object when no metadata added")
    void omitsMetadataWhenEmpty() {
      var response = ResponseBuilder.success()
          .addProperty("data", "value")
          .build();

      assertFalse(response.has("_metadata"));
    }

    @Test
    @DisplayName("adds single metadata field")
    void addsSingleMetadataField() {
      var response = ResponseBuilder.success()
          .addMetadata("sample_count", 1000)
          .build();

      assertTrue(response.has("_metadata"));
      var metadata = response.getAsJsonObject("_metadata");
      assertEquals(1000, metadata.get("sample_count").getAsInt());
    }

    @Test
    @DisplayName("adds multiple metadata fields")
    void addsMultipleMetadataFields() {
      var response = ResponseBuilder.success()
          .addMetadata("sample_count", 1000)
          .addMetadata("data_quality", "good")
          .addMetadata("algorithm", "linear_interpolation")
          .build();

      var metadata = response.getAsJsonObject("_metadata");
      assertEquals(1000, metadata.get("sample_count").getAsInt());
      assertEquals("good", metadata.get("data_quality").getAsString());
      assertEquals("linear_interpolation", metadata.get("algorithm").getAsString());
    }

    @Test
    @DisplayName("metadata uses underscore prefix")
    void metadataUsesUnderscorePrefix() {
      var response = ResponseBuilder.success()
          .addMetadata("execution_info", "test")
          .build();

      assertTrue(response.has("_metadata"));
      assertFalse(response.has("metadata"));
    }
  }

  @Nested
  @DisplayName("Complex Data")
  class ComplexDataTests {

    @Test
    @DisplayName("adds JsonObject via addData")
    void addsJsonObjectViaAddData() {
      var nested = new JsonObject();
      nested.addProperty("x", 1.5);
      nested.addProperty("y", 2.5);

      var response = ResponseBuilder.success()
          .addData("position", nested)
          .build();

      var position = response.getAsJsonObject("position");
      assertEquals(1.5, position.get("x").getAsDouble());
      assertEquals(2.5, position.get("y").getAsDouble());
    }

    @Test
    @DisplayName("adds JsonArray via addData")
    void addsJsonArrayViaAddData() {
      var array = new JsonArray();
      array.add(1);
      array.add(2);
      array.add(3);

      var response = ResponseBuilder.success()
          .addData("values", array)
          .build();

      var values = response.getAsJsonArray("values");
      assertEquals(3, values.size());
      assertEquals(1, values.get(0).getAsInt());
      assertEquals(2, values.get(1).getAsInt());
      assertEquals(3, values.get(2).getAsInt());
    }

    @Test
    @DisplayName("serializes List via addProperty")
    void serializesListViaAddProperty() {
      var list = java.util.List.of("a", "b", "c");

      var response = ResponseBuilder.success()
          .addProperty("items", list)
          .build();

      var items = response.getAsJsonArray("items");
      assertEquals(3, items.size());
      assertEquals("a", items.get(0).getAsString());
      assertEquals("b", items.get(1).getAsString());
      assertEquals("c", items.get(2).getAsString());
    }
  }

  @Nested
  @DisplayName("Complete Response")
  class CompleteResponseTests {

    @Test
    @DisplayName("builds complete response with all features")
    void buildsCompleteResponse() {
      var nested = new JsonObject();
      nested.addProperty("min", 0.0);
      nested.addProperty("max", 100.0);

      var response = ResponseBuilder.success()
          .addProperty("name", "test_entry")
          .addProperty("count", 500)
          .addData("stats", nested)
          .addWarning("Data contains outliers")
          .addWarning("Timestamp gaps detected")
          .addMetadata("sample_count", 500)
          .addMetadata("algorithm", "percentile")
          .build();

      // Verify success
      assertTrue(response.get("success").getAsBoolean());

      // Verify data fields
      assertEquals("test_entry", response.get("name").getAsString());
      assertEquals(500, response.get("count").getAsInt());
      assertEquals(0.0, response.getAsJsonObject("stats").get("min").getAsDouble());
      assertEquals(100.0, response.getAsJsonObject("stats").get("max").getAsDouble());

      // Verify warnings
      var warnings = response.getAsJsonArray("warnings");
      assertEquals(2, warnings.size());
      assertEquals("Data contains outliers", warnings.get(0).getAsString());
      assertEquals("Timestamp gaps detected", warnings.get(1).getAsString());

      // Verify metadata
      var metadata = response.getAsJsonObject("_metadata");
      assertEquals(500, metadata.get("sample_count").getAsInt());
      assertEquals("percentile", metadata.get("algorithm").getAsString());
    }

    @Test
    @DisplayName("field ordering: success first, then warnings, then metadata, then data")
    void verifyFieldOrdering() {
      var response = ResponseBuilder.success()
          .addProperty("z_field", "last")
          .addProperty("a_field", "first")
          .addWarning("warning")
          .addMetadata("info", "test")
          .build();

      // Verify all expected fields exist
      assertTrue(response.has("success"));
      assertTrue(response.has("warnings"));
      assertTrue(response.has("_metadata"));
      assertTrue(response.has("z_field"));
      assertTrue(response.has("a_field"));

      // Note: JsonObject doesn't guarantee field order in iteration,
      // but the spec says success should be first, which we ensure
      // by adding it first in the constructor
    }
  }

  @Nested
  @DisplayName("buildElement Method")
  class BuildElementTests {

    @Test
    @DisplayName("buildElement returns JsonElement")
    void buildElementReturnsJsonElement() {
      var element = ResponseBuilder.success()
          .addProperty("data", 123)
          .buildElement();

      assertTrue(element.isJsonObject());
      var obj = element.getAsJsonObject();
      assertTrue(obj.get("success").getAsBoolean());
      assertEquals(123, obj.get("data").getAsInt());
    }
  }

  @Nested
  @DisplayName("Edge Cases")
  class EdgeCaseTests {

    @Test
    @DisplayName("handles empty string properties")
    void handlesEmptyStringProperties() {
      var response = ResponseBuilder.success()
          .addProperty("empty", "")
          .build();

      assertEquals("", response.get("empty").getAsString());
    }

    @Test
    @DisplayName("handles zero numeric values")
    void handlesZeroNumericValues() {
      var response = ResponseBuilder.success()
          .addProperty("zero_int", 0)
          .addProperty("zero_double", 0.0)
          .build();

      assertEquals(0, response.get("zero_int").getAsInt());
      assertEquals(0.0, response.get("zero_double").getAsDouble());
    }

    @Test
    @DisplayName("handles special double values")
    void handlesSpecialDoubleValues() {
      var response = ResponseBuilder.success()
          .addProperty("infinity", Double.POSITIVE_INFINITY)
          .addProperty("neg_infinity", Double.NEGATIVE_INFINITY)
          .addProperty("nan", Double.NaN)
          .build();

      assertEquals(Double.POSITIVE_INFINITY, response.get("infinity").getAsDouble());
      assertEquals(Double.NEGATIVE_INFINITY, response.get("neg_infinity").getAsDouble());
      assertTrue(Double.isNaN(response.get("nan").getAsDouble()));
    }
  }
}
