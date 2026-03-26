package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds contextual analysis directives for LLM interpretation guidance.
 *
 * <p>These directives are included in tool responses as {@code server_analysis_directives}
 * to help LLMs reason about results with appropriate uncertainty and nuance.
 *
 * <p>Example output:
 * <pre>{@code
 * "server_analysis_directives": {
 *   "confidence_level": "medium",
 *   "sample_context": "Based on 4500 samples over 150.0 seconds",
 *   "interpretation_guidance": [
 *     "Sample count is adequate for basic statistics but insufficient for rare-event detection"
 *   ],
 *   "suggested_followup": [
 *     "Use detect_anomalies to check for outliers that may skew these statistics"
 *   ]
 * }
 * }</pre>
 *
 * @since 0.5.0
 */
public class AnalysisDirectives {
  private String confidenceLevel;
  private String sampleContext;
  private final List<String> interpretationGuidance = new ArrayList<>();
  private final List<String> suggestedFollowup = new ArrayList<>();

  /**
   * Creates directives from data quality metrics.
   *
   * <p>Automatically generates guidance based on quality issues detected:
   * low sample count, data gaps, NaN values, and overall quality score.
   *
   * @param quality The data quality metrics
   * @return A new AnalysisDirectives with auto-generated guidance
   */
  public static AnalysisDirectives fromQuality(DataQuality quality) {
    var d = new AnalysisDirectives();
    d.confidenceLevel = quality.confidenceLevel();
    d.sampleContext = String.format("Based on %d samples over %.1f seconds",
        quality.sampleCount(), quality.timeSpanSeconds());

    if (quality.sampleCount() < 100) {
      d.interpretationGuidance.add(
          "Low sample count (" + quality.sampleCount() + "). "
          + "Statistical measures have high uncertainty.");
    }
    if (quality.gapCount() > 0 && quality.sampleCount() > 0
        && (double) quality.gapCount() / quality.sampleCount() > 0.02) {
      d.interpretationGuidance.add(
          quality.gapCount() + " data gaps detected (max "
          + String.format("%.1f", quality.maxGapMs()) + "ms). "
          + "Trend analysis may be affected by missing data.");
    }
    if (quality.nanFiltered() > 0) {
      d.interpretationGuidance.add(
          quality.nanFiltered() + " non-finite values were filtered. "
          + "This may indicate sensor dropouts or communication errors.");
    }
    if (quality.sampleCount() > 0 && quality.timeSpanSeconds() < 10) {
      d.interpretationGuidance.add(
          "Short time span (" + String.format("%.1f", quality.timeSpanSeconds())
          + "s). Results may not be representative of full-match behavior.");
    }

    return d;
  }

  /**
   * Adds an interpretation guidance message.
   *
   * @param guidance A guidance message for the LLM
   * @return This builder for chaining
   */
  public AnalysisDirectives addGuidance(String guidance) {
    interpretationGuidance.add(guidance);
    return this;
  }

  /**
   * Adds a suggested follow-up action.
   *
   * @param followup A suggested next step (tool name or action description)
   * @return This builder for chaining
   */
  public AnalysisDirectives addFollowup(String followup) {
    suggestedFollowup.add(followup);
    return this;
  }

  /**
   * Adds the standard single-match caveat.
   *
   * @return This builder for chaining
   */
  public AnalysisDirectives addSingleMatchCaveat() {
    interpretationGuidance.add(
        "This analysis is based on a single log. Patterns should be confirmed "
        + "across multiple matches before drawing conclusions.");
    return this;
  }

  /**
   * Serializes the directives to a JSON object.
   *
   * @return A JsonObject with all directive fields
   */
  public JsonObject toJson() {
    var json = new JsonObject();
    json.addProperty("confidence_level", confidenceLevel);
    json.addProperty("sample_context", sampleContext);

    if (!interpretationGuidance.isEmpty()) {
      var arr = new JsonArray();
      interpretationGuidance.forEach(arr::add);
      json.add("interpretation_guidance", arr);
    }
    if (!suggestedFollowup.isEmpty()) {
      var arr = new JsonArray();
      suggestedFollowup.forEach(arr::add);
      json.add("suggested_followup", arr);
    }
    return json;
  }
}
