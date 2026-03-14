package org.triplehelix.wpilogmcp.tba;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for The Blue Alliance API integration.
 */
public class TbaConfig {
  private static final Logger logger = LoggerFactory.getLogger(TbaConfig.class);

  /** Environment variable name for TBA API key. */
  public static final String ENV_TBA_API_KEY = "TBA_API_KEY";

  /** Singleton instance. */
  private static TbaConfig instance;

  /** TBA API key. */
  private String apiKey;

  /** Private constructor for singleton pattern. */
  private TbaConfig() {
    refreshFromEnvironment();
  }

  /**
   * Gets the singleton instance.
   *
   * @return The singleton instance
   */
  public static synchronized TbaConfig getInstance() {
    if (instance == null) {
      instance = new TbaConfig();
    }
    return instance;
  }

  /**
   * Refreshes the configuration from environment variables.
   */
  public void refreshFromEnvironment() {
    String envKey = System.getenv(ENV_TBA_API_KEY);
    if (envKey != null && !envKey.isEmpty()) {
      this.apiKey = envKey;
      logger.info("Successfully loaded TBA API key from environment variable {}", ENV_TBA_API_KEY);
    } else {
      logger.debug("TBA_API_KEY not found in environment.");
    }
  }

  /**
   * Sets the TBA API key.
   *
   * @param apiKey The API key
   */
  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
    logger.debug("TBA API key set manually");
  }

  /**
   * Gets the current TBA API key.
   *
   * @return The API key, or null if not set
   */
  public String getApiKey() {
    return apiKey;
  }

  /**
   * Checks if the TBA API is configured.
   *
   * @return true if an API key is present
   */
  public boolean isConfigured() {
    return apiKey != null && !apiKey.isEmpty();
  }

  /**
   * Applies the current configuration to the TbaClient.
   */
  public void applyToClient() {
    // Final check: if still not configured, try one last time to read the env
    if (!isConfigured()) {
      refreshFromEnvironment();
    }
    TbaClient.getInstance().configure(apiKey);
  }

  /**
   * Gets a status summary for the TBA integration.
   *
   * @return Human-readable status string
   */
  public String getStatusSummary() {
    if (isConfigured()) {
      return "TBA API: configured";
    } else {
      return "TBA API: not configured (set TBA_API_KEY or use -tba-key)";
    }
  }

  /**
   * Resets the singleton instance (for testing).
   */
  public static synchronized void resetInstance() {
    instance = null;
  }
}
