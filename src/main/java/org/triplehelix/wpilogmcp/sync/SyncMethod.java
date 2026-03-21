package org.triplehelix.wpilogmcp.sync;

/**
 * Methods used to synchronize revlog timestamps with wpilog FPGA time.
 *
 * @since 0.5.0
 */
public enum SyncMethod {
  /**
   * Full cross-correlation alignment using matching signal pairs.
   * This provides the highest accuracy when strong signal correlation exists.
   */
  CROSS_CORRELATION("Cross-correlation of matching signals"),

  /**
   * Coarse alignment using systemTime entries and filename timestamps only.
   * Used as fallback when no signal pairs can be correlated.
   */
  SYSTEM_TIME_ONLY("System time alignment only"),

  /**
   * Manual offset provided by user.
   * Used when automatic synchronization fails or user has known offset.
   */
  USER_PROVIDED("User-provided offset"),

  /**
   * Synchronization failed - no reliable offset could be determined.
   */
  FAILED("Synchronization failed");

  private final String description;

  SyncMethod(String description) {
    this.description = description;
  }

  /**
   * Gets a human-readable description of this sync method.
   *
   * @return The description
   */
  public String getDescription() {
    return description;
  }
}
