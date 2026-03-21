package org.triplehelix.wpilogmcp.sync;

/**
 * Confidence levels for synchronization quality.
 *
 * <p>Each level represents a range of synchronization accuracy and reliability.
 * Higher confidence means timestamps can be trusted more precisely.
 *
 * @since 0.5.0
 */
public enum ConfidenceLevel {
  /**
   * High confidence: Multiple signals agree within 5ms, correlation > 0.9.
   * Estimated timing accuracy: 1-5ms.
   */
  HIGH("high", 0.85, "Multiple signals agree within 5ms, correlation > 0.9", "1-5"),

  /**
   * Medium confidence: Some signals correlate well, minor disagreement.
   * Estimated timing accuracy: 5-50ms.
   */
  MEDIUM("medium", 0.6, "Some signals correlate well, minor disagreement", "5-50"),

  /**
   * Low confidence: Weak correlation or significant disagreement.
   * Estimated timing accuracy: 50-5000ms.
   */
  LOW("low", 0.3, "Weak correlation or significant disagreement between signals", "50-5000"),

  /**
   * Failed: Could not establish reliable synchronization.
   * Timestamps should not be trusted for precise comparisons.
   */
  FAILED("failed", 0.0, "Could not establish reliable synchronization", "unknown");

  private final String label;
  private final double threshold;
  private final String description;
  private final String accuracyMs;

  ConfidenceLevel(String label, double threshold, String description, String accuracyMs) {
    this.label = label;
    this.threshold = threshold;
    this.description = description;
    this.accuracyMs = accuracyMs;
  }

  /**
   * Gets the label for this confidence level.
   *
   * @return The label (e.g., "high", "medium", "low", "failed")
   */
  public String getLabel() {
    return label;
  }

  /**
   * Gets the minimum confidence score threshold for this level.
   *
   * @return The threshold (0.0 to 1.0)
   */
  public double getThreshold() {
    return threshold;
  }

  /**
   * Gets a description of what this confidence level means.
   *
   * @return The description
   */
  public String getDescription() {
    return description;
  }

  /**
   * Gets the estimated timing accuracy range in milliseconds.
   *
   * @return The accuracy range (e.g., "1-5", "5-50")
   */
  public String getAccuracyMs() {
    return accuracyMs;
  }

  /**
   * Gets a numeric confidence value (0.0 to 1.0) for this level.
   *
   * @return The numeric value: HIGH=1.0, MEDIUM=0.67, LOW=0.33, FAILED=0.0
   */
  public double getNumericValue() {
    return switch (this) {
      case HIGH -> 1.0;
      case MEDIUM -> 0.67;
      case LOW -> 0.33;
      case FAILED -> 0.0;
    };
  }

  /**
   * Determines the confidence level from a numeric confidence score.
   *
   * @param confidence The confidence score (0.0 to 1.0)
   * @return The corresponding confidence level
   */
  public static ConfidenceLevel fromScore(double confidence) {
    if (confidence >= HIGH.threshold) return HIGH;
    if (confidence >= MEDIUM.threshold) return MEDIUM;
    if (confidence >= LOW.threshold) return LOW;
    return FAILED;
  }
}
