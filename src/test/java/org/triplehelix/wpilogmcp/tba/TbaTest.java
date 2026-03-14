package org.triplehelix.wpilogmcp.tba;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.LogDirectory.LogFileInfo;

/**
 * Tests for The Blue Alliance integration.
 *
 * <p>Note: These tests do not make actual API calls. They test the configuration, caching, and
 * enrichment logic with mock/empty data.
 */
class TbaTest {

  @BeforeEach
  void setUp() {
    // Reset singletons for clean test state
    TbaConfig.resetInstance();
    TbaClient.getInstance().clearCache();
  }

  @Nested
  @DisplayName("TbaConfig")
  class TbaConfigTest {

    @Test
    @DisplayName("returns singleton instance")
    void returnsSingletonInstance() {
      TbaConfig config1 = TbaConfig.getInstance();
      TbaConfig config2 = TbaConfig.getInstance();
      assertSame(config1, config2);
    }

    @Test
    @DisplayName("not configured by default (no env vars)")
    void notConfiguredByDefault() {
      TbaConfig config = TbaConfig.getInstance();
      // Only configured if TBA_API_KEY env var is set
      // In test environment, it might or might not be set
      if (System.getenv("TBA_API_KEY") == null) {
        assertFalse(config.isConfigured());
      }
    }

    @Test
    @DisplayName("can set and get API key")
    void canSetAndGetApiKey() {
      TbaConfig config = TbaConfig.getInstance();
      config.setApiKey("test_api_key");
      assertEquals("test_api_key", config.getApiKey());
      assertTrue(config.isConfigured());
    }

    @Test
    @DisplayName("getStatusSummary reflects configuration")
    void statusSummaryReflectsConfiguration() {
      TbaConfig config = TbaConfig.getInstance();

      // Not configured
      String notConfigured = config.getStatusSummary();
      assertTrue(notConfigured.contains("not configured"));

      // Configured with key
      config.setApiKey("test_key");
      String withKey = config.getStatusSummary();
      assertTrue(withKey.contains("configured"));
      assertFalse(withKey.contains("not configured"));
    }

    @Test
    @DisplayName("applyToClient configures TbaClient")
    void applyToClientConfiguresTbaClient() {
      TbaConfig config = TbaConfig.getInstance();
      config.setApiKey("test_key_12345");
      config.applyToClient();

      TbaClient client = TbaClient.getInstance();
      assertTrue(client.isAvailable());
    }
  }

  @Nested
  @DisplayName("TbaClient")
  class TbaClientTest {

    @Test
    @DisplayName("returns singleton instance")
    void returnsSingletonInstance() {
      TbaClient client1 = TbaClient.getInstance();
      TbaClient client2 = TbaClient.getInstance();
      assertSame(client1, client2);
    }

    @Test
    @DisplayName("not available without API key")
    void notAvailableWithoutApiKey() {
      TbaClient client = TbaClient.getInstance();
      client.configure(null);
      assertFalse(client.isAvailable());
    }

    @Test
    @DisplayName("available with API key")
    void availableWithApiKey() {
      TbaClient client = TbaClient.getInstance();
      client.configure("test_api_key");
      assertTrue(client.isAvailable());
    }

    @Test
    @DisplayName("returns empty when not configured")
    void returnsEmptyWhenNotConfigured() {
      TbaClient client = TbaClient.getInstance();
      client.configure(null);

      assertTrue(client.getEvent(2024, "vadc").isEmpty());
      assertTrue(client.getMatch(2024, "vadc", "Qualification", 42).isEmpty());
      assertTrue(client.getEventMatches(2024, "vadc").isEmpty());
    }

    @Test
    @DisplayName("cache statistics are available")
    void cacheStatisticsAvailable() {
      TbaClient client = TbaClient.getInstance();
      Map<String, Integer> stats = client.getCacheStats();

      assertNotNull(stats);
      assertTrue(stats.containsKey("events"));
      assertTrue(stats.containsKey("matches"));
      assertTrue(stats.containsKey("eventMatches"));
    }

    @Test
    @DisplayName("clearCache empties all caches")
    void clearCacheEmptiesAllCaches() {
      TbaClient client = TbaClient.getInstance();
      client.clearCache();

      Map<String, Integer> stats = client.getCacheStats();
      assertEquals(0, stats.get("events"));
      assertEquals(0, stats.get("matches"));
      assertEquals(0, stats.get("eventMatches"));
    }
  }

  @Nested
  @DisplayName("TbaEnrichment")
  class TbaEnrichmentTest {

    @Test
    @DisplayName("returns singleton instance")
    void returnsSingletonInstance() {
      TbaEnrichment enrich1 = TbaEnrichment.getInstance();
      TbaEnrichment enrich2 = TbaEnrichment.getInstance();
      assertSame(enrich1, enrich2);
    }

    @Test
    @DisplayName("qualification match with team number is eligible for enrichment")
    void qualificationMatchIsEligible() {
      TbaEnrichment enrichment = TbaEnrichment.getInstance();
      LogFileInfo log =
          new LogFileInfo(
              "/path/to/log.wpilog",
              "2024vadc_qm42.wpilog",
              "2024VADC",
              "Qualification",
              42,
              2168, // team number
              System.currentTimeMillis(),
              1024);
      assertTrue(enrichment.isEligibleForEnrichment(log));
    }

    @Test
    @DisplayName("semifinal match with team number is eligible for enrichment")
    void semifinalMatchIsEligible() {
      TbaEnrichment enrichment = TbaEnrichment.getInstance();
      LogFileInfo log =
          new LogFileInfo(
              "/path/to/log.wpilog",
              "2024vadc_sf1.wpilog",
              "2024VADC",
              "Semifinal",
              1,
              2168, // team number
              System.currentTimeMillis(),
              1024);
      assertTrue(enrichment.isEligibleForEnrichment(log));
    }

    @Test
    @DisplayName("final match with team number is eligible for enrichment")
    void finalMatchIsEligible() {
      TbaEnrichment enrichment = TbaEnrichment.getInstance();
      LogFileInfo log =
          new LogFileInfo(
              "/path/to/log.wpilog",
              "2024vadc_f1.wpilog",
              "2024VADC",
              "Final",
              1,
              2168, // team number
              System.currentTimeMillis(),
              1024);
      assertTrue(enrichment.isEligibleForEnrichment(log));
    }

    @Test
    @DisplayName("simulation log is not eligible for enrichment")
    void simulationNotEligible() {
      TbaEnrichment enrichment = TbaEnrichment.getInstance();
      LogFileInfo log =
          new LogFileInfo(
              "/path/to/sim.wpilog",
              "sim_test.wpilog",
              null,
              "Simulation",
              null,
              null, // no team number for simulations
              System.currentTimeMillis(),
              1024);
      assertFalse(enrichment.isEligibleForEnrichment(log));
    }

    @Test
    @DisplayName("practice match is not eligible for enrichment")
    void practiceNotEligible() {
      TbaEnrichment enrichment = TbaEnrichment.getInstance();
      LogFileInfo log =
          new LogFileInfo(
              "/path/to/practice.wpilog",
              "2024vadc_pm1.wpilog",
              "2024VADC",
              "Practice",
              1,
              2168, // team number
              System.currentTimeMillis(),
              1024);
      assertFalse(enrichment.isEligibleForEnrichment(log));
    }

    @Test
    @DisplayName("replay is not eligible for enrichment")
    void replayNotEligible() {
      TbaEnrichment enrichment = TbaEnrichment.getInstance();
      LogFileInfo log =
          new LogFileInfo(
              "/path/to/replay.wpilog",
              "replay_2024vadc_qm42.wpilog",
              "2024VADC",
              "Qualification Replay",
              42,
              2168, // team number
              System.currentTimeMillis(),
              1024);
      assertFalse(enrichment.isEligibleForEnrichment(log));
    }

    @Test
    @DisplayName("log without event name is not eligible")
    void noEventNameNotEligible() {
      TbaEnrichment enrichment = TbaEnrichment.getInstance();
      LogFileInfo log =
          new LogFileInfo(
              "/path/to/log.wpilog",
              "random_log.wpilog",
              null,
              "Qualification",
              42,
              2168, // team number
              System.currentTimeMillis(),
              1024);
      assertFalse(enrichment.isEligibleForEnrichment(log));
    }

    @Test
    @DisplayName("log without match number is not eligible")
    void noMatchNumberNotEligible() {
      TbaEnrichment enrichment = TbaEnrichment.getInstance();
      LogFileInfo log =
          new LogFileInfo(
              "/path/to/log.wpilog",
              "2024vadc.wpilog",
              "2024VADC",
              "Qualification",
              null,
              2168, // team number
              System.currentTimeMillis(),
              1024);
      assertFalse(enrichment.isEligibleForEnrichment(log));
    }

    @Test
    @DisplayName("log without team number is not eligible")
    void noTeamNumberNotEligible() {
      TbaEnrichment enrichment = TbaEnrichment.getInstance();
      LogFileInfo log =
          new LogFileInfo(
              "/path/to/log.wpilog",
              "2024vadc_qm42.wpilog",
              "2024VADC",
              "Qualification",
              42,
              null, // no team number
              System.currentTimeMillis(),
              1024);
      assertFalse(enrichment.isEligibleForEnrichment(log));
    }

    @Test
    @DisplayName("extracts year from eventName metadata")
    void extractsYearFromEventName() {
      TbaEnrichment enrichment = TbaEnrichment.getInstance();
      LogFileInfo log =
          new LogFileInfo(
              "/path/to/log.wpilog",
              "any_filename.wpilog",
              "2024VADC",
              "Qualification",
              42,
              2168,
              System.currentTimeMillis(),
              1024);
      assertEquals(2024, enrichment.extractYear(log));
    }

    @Test
    @DisplayName("extracts year from different eventName formats")
    void extractsYearFromDifferentEventNames() {
      TbaEnrichment enrichment = TbaEnrichment.getInstance();

      // 2023 event
      LogFileInfo log2023 =
          new LogFileInfo(
              "/path/to/log.wpilog",
              "any.wpilog",
              "2023DCMP",
              "Semifinal",
              2,
              2168,
              System.currentTimeMillis(),
              1024);
      assertEquals(2023, enrichment.extractYear(log2023));

      // 2025 event
      LogFileInfo log2025 =
          new LogFileInfo(
              "/path/to/log.wpilog",
              "any.wpilog",
              "CMPTX 2025",
              "Final",
              1,
              2168,
              System.currentTimeMillis(),
              1024);
      assertEquals(2025, enrichment.extractYear(log2025));
    }

    @Test
    @DisplayName("enrichLog returns empty when TBA not configured")
    void enrichLogReturnsEmptyWhenNotConfigured() {
      TbaClient.getInstance().configure(null);
      TbaEnrichment enrichment = TbaEnrichment.getInstance();

      LogFileInfo log =
          new LogFileInfo(
              "/path/to/log.wpilog",
              "any.wpilog",
              "2024VADC",
              "Qualification",
              42,
              2168,
              System.currentTimeMillis(),
              1024);

      Optional<JsonObject> result = enrichment.enrichLog(log);
      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("getMatchStartTime returns empty when TBA not configured")
    void getMatchStartTimeReturnsEmptyWhenNotConfigured() {
      TbaClient.getInstance().configure(null);
      TbaEnrichment enrichment = TbaEnrichment.getInstance();

      LogFileInfo log =
          new LogFileInfo(
              "/path/to/log.wpilog",
              "any.wpilog",
              "2024VADC",
              "Qualification",
              42,
              2168,
              System.currentTimeMillis(),
              1024);

      Optional<Long> result = enrichment.getMatchStartTime(log);
      assertTrue(result.isEmpty());
    }
  }
}
