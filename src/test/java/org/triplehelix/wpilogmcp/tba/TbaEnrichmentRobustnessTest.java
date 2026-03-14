package org.triplehelix.wpilogmcp.tba;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.LogDirectory.LogFileInfo;

/**
 * Robustness tests for TBA enrichment logic.
 */
class TbaEnrichmentRobustnessTest {

  private TbaEnrichment enrichment;

  @BeforeEach
  void setUp() {
    enrichment = TbaEnrichment.getInstance();
  }

  @Test
  @DisplayName("extractYear handles various eventName formats")
  void testExtractYear() {
    // Standard format
    assertEquals(2024, enrichment.extractYear(createInfoWithEvent("2024VADC")));
    
    // Year at the end
    assertEquals(2023, enrichment.extractYear(createInfoWithEvent("DCMP 2023")));
    
    // Embedded year
    assertEquals(2025, enrichment.extractYear(createInfoWithEvent("CMPTX_2025_Match")));
    
    // Fallback to modification date (2022)
    long modTime2022 = Instant.parse("2022-03-15T12:00:00Z").toEpochMilli();
    assertEquals(2022, enrichment.extractYear(createInfoWithDate(modTime2022)));
  }

  @Test
  @DisplayName("normalizeEventCode handles various formats")
  void testNormalizeEventCode() throws Exception {
    Method method = TbaEnrichment.class.getDeclaredMethod("normalizeEventCode", String.class);
    method.setAccessible(true);

    // Standard year prefix
    assertEquals("vadc", method.invoke(enrichment, "2024vadc"));
    assertEquals("dcmp", method.invoke(enrichment, "2023DCMP"));
    
    // No year prefix
    assertEquals("chcmp", method.invoke(enrichment, "chcmp"));
    
    // Mixed case
    assertEquals("cur", method.invoke(enrichment, "2024Cur"));
    
    // Null handling
    assertNull(method.invoke(enrichment, (Object) null));
  }

  @Test
  @DisplayName("isEligibleForEnrichment correctly filters logs")
  void testIsEligible() {
    // Eligible
    assertTrue(enrichment.isEligibleForEnrichment(createFullInfo("VADC", "Qualification", 1, 2363)));
    assertTrue(enrichment.isEligibleForEnrichment(createFullInfo("VADC", "Semifinal", 2, 2363)));
    
    // Ineligible - Simulation
    assertFalse(enrichment.isEligibleForEnrichment(createFullInfo("VADC", "Simulation", 1, 2363)));
    
    // Ineligible - Practice
    assertFalse(enrichment.isEligibleForEnrichment(createFullInfo("VADC", "Practice", 1, 2363)));
    
    // Ineligible - Replay
    assertFalse(enrichment.isEligibleForEnrichment(createFullInfo("VADC", "Qualification Replay", 1, 2363)));
    
    // Ineligible - Missing pieces
    assertFalse(enrichment.isEligibleForEnrichment(createFullInfo(null, "Qualification", 1, 2363)));
    assertFalse(enrichment.isEligibleForEnrichment(createFullInfo("VADC", null, 1, 2363)));
    assertFalse(enrichment.isEligibleForEnrichment(createFullInfo("VADC", "Qualification", null, 2363)));
    assertFalse(enrichment.isEligibleForEnrichment(createFullInfo("VADC", "Qualification", 1, null)));
  }

  private LogFileInfo createInfoWithEvent(String eventName) {
    return new LogFileInfo("/path", "file.wpilog", eventName, "Qualification", 1, 2363, 0, 0);
  }

  private LogFileInfo createInfoWithDate(long modTime) {
    return new LogFileInfo("/path", "file.wpilog", null, "Qualification", 1, 2363, modTime, 0);
  }

  private LogFileInfo createFullInfo(String event, String type, Integer num, Integer team) {
    return new LogFileInfo("/path", "file.wpilog", event, type, num, team, 0, 0);
  }
}
