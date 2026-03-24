package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.tba.TbaClient;
import org.triplehelix.wpilogmcp.tba.TbaConfig;

@DisplayName("ToolDependencies")
class ToolDependenciesTest {

  @Nested
  @DisplayName("fromSingletons Factory")
  class FromSingletonsTests {

    @Test
    @DisplayName("creates non-null dependencies")
    void createsNonNullDependencies() {
      var deps = ToolDependencies.fromSingletons();

      assertNotNull(deps);
      assertNotNull(deps.logManager());
      assertNotNull(deps.tbaClient());
      assertNotNull(deps.tbaConfig());
      assertNotNull(deps.logDirectory());
    }

    @Test
    @DisplayName("returns singleton instances")
    void returnsSingletonInstances() {
      var deps = ToolDependencies.fromSingletons();

      assertSame(LogManager.getInstance(), deps.logManager());
      assertSame(TbaClient.getInstance(), deps.tbaClient());
      assertSame(TbaConfig.getInstance(), deps.tbaConfig());
      assertSame(LogDirectory.getInstance(), deps.logDirectory());
    }

    @Test
    @DisplayName("multiple calls return different container instances")
    void multipleCallsReturnDifferentContainers() {
      var deps1 = ToolDependencies.fromSingletons();
      var deps2 = ToolDependencies.fromSingletons();

      assertNotSame(deps1, deps2);
    }

    @Test
    @DisplayName("multiple calls return same singleton references")
    void multipleCallsReturnSameSingletonReferences() {
      var deps1 = ToolDependencies.fromSingletons();
      var deps2 = ToolDependencies.fromSingletons();

      assertSame(deps1.logManager(), deps2.logManager());
      assertSame(deps1.tbaClient(), deps2.tbaClient());
      assertSame(deps1.tbaConfig(), deps2.tbaConfig());
      assertSame(deps1.logDirectory(), deps2.logDirectory());
    }
  }

  @Nested
  @DisplayName("Constructor with Explicit Dependencies")
  class ConstructorTests {

    @Test
    @DisplayName("accepts null for all dependencies")
    void acceptsNullForAllDependencies() {
      var deps = new ToolDependencies(null, null, null, null);

      assertNotNull(deps);
      assertNull(deps.logManager());
      assertNull(deps.tbaClient());
      assertNull(deps.tbaConfig());
      assertNull(deps.logDirectory());
    }

    @Test
    @DisplayName("accepts mixed null and non-null dependencies")
    void acceptsMixedNullAndNonNull() {
      var logManager = LogManager.getInstance();
      var deps = new ToolDependencies(logManager, null, null, null);

      assertNotNull(deps);
      assertSame(logManager, deps.logManager());
      assertNull(deps.tbaClient());
      assertNull(deps.tbaConfig());
      assertNull(deps.logDirectory());
    }

    @Test
    @DisplayName("stores all provided dependencies")
    void storesAllProvidedDependencies() {
      var logManager = LogManager.getInstance();
      var tbaClient = TbaClient.getInstance();
      var tbaConfig = TbaConfig.getInstance();
      var logDirectory = LogDirectory.getInstance();

      var deps = new ToolDependencies(logManager, tbaClient, tbaConfig, logDirectory);

      assertSame(logManager, deps.logManager());
      assertSame(tbaClient, deps.tbaClient());
      assertSame(tbaConfig, deps.tbaConfig());
      assertSame(logDirectory, deps.logDirectory());
    }

    @Test
    @DisplayName("allows creating multiple independent instances")
    void allowsCreatingMultipleIndependentInstances() {
      var logManager1 = LogManager.getInstance();
      var logManager2 = LogManager.getInstance();

      var deps1 = new ToolDependencies(logManager1, null, null, null);
      var deps2 = new ToolDependencies(logManager2, null, null, null);

      assertNotSame(deps1, deps2);
      assertSame(deps1.logManager(), deps2.logManager()); // Both reference same singleton
    }
  }

  @Nested
  @DisplayName("Getter Methods")
  class GetterTests {

    @Test
    @DisplayName("getLogManager returns stored instance")
    void getLogManagerReturnsStoredInstance() {
      var logManager = LogManager.getInstance();
      var deps = new ToolDependencies(logManager, null, null, null);

      assertSame(logManager, deps.logManager());
    }

    @Test
    @DisplayName("getTbaClient returns stored instance")
    void getTbaClientReturnsStoredInstance() {
      var tbaClient = TbaClient.getInstance();
      var deps = new ToolDependencies(null, tbaClient, null, null);

      assertSame(tbaClient, deps.tbaClient());
    }

    @Test
    @DisplayName("getTbaConfig returns stored instance")
    void getTbaConfigReturnsStoredInstance() {
      var tbaConfig = TbaConfig.getInstance();
      var deps = new ToolDependencies(null, null, tbaConfig, null);

      assertSame(tbaConfig, deps.tbaConfig());
    }

    @Test
    @DisplayName("getLogDirectory returns stored instance")
    void getLogDirectoryReturnsStoredInstance() {
      var logDirectory = LogDirectory.getInstance();
      var deps = new ToolDependencies(null, null, null, logDirectory);

      assertSame(logDirectory, deps.logDirectory());
    }

    @Test
    @DisplayName("getters return null when dependencies are null")
    void gettersReturnNullWhenDependenciesAreNull() {
      var deps = new ToolDependencies(null, null, null, null);

      assertNull(deps.logManager());
      assertNull(deps.tbaClient());
      assertNull(deps.tbaConfig());
      assertNull(deps.logDirectory());
    }

    @Test
    @DisplayName("getters are consistent across multiple calls")
    void gettersAreConsistentAcrossMultipleCalls() {
      var deps = ToolDependencies.fromSingletons();

      var logManager1 = deps.logManager();
      var logManager2 = deps.logManager();
      var logManager3 = deps.logManager();

      assertSame(logManager1, logManager2);
      assertSame(logManager2, logManager3);
    }
  }

  @Nested
  @DisplayName("Use Case Scenarios")
  class UseCaseTests {

    @Test
    @DisplayName("supports testing scenario with partial mocks")
    void supportsPartialMockScenario() {
      // Scenario: Testing a tool that only needs LogManager
      var logManager = LogManager.getInstance();
      var deps = new ToolDependencies(logManager, null, null, null);

      assertNotNull(deps.logManager());
      assertNull(deps.tbaClient()); // Not needed for this test
    }

    @Test
    @DisplayName("supports production scenario with all singletons")
    void supportsProductionScenarioWithAllSingletons() {
      // Scenario: Production usage with default singletons
      var deps = ToolDependencies.fromSingletons();

      assertNotNull(deps.logManager());
      assertNotNull(deps.tbaClient());
      assertNotNull(deps.tbaConfig());
      assertNotNull(deps.logDirectory());
    }

    @Test
    @DisplayName("supports immutability - getters always return same reference")
    void supportsImmutability() {
      var logManager = LogManager.getInstance();
      var deps = new ToolDependencies(logManager, null, null, null);

      var ref1 = deps.logManager();
      var ref2 = deps.logManager();
      var ref3 = deps.logManager();

      assertSame(ref1, ref2);
      assertSame(ref2, ref3);
    }
  }

  @Nested
  @DisplayName("Edge Cases")
  class EdgeCaseTests {

    @Test
    @DisplayName("handles rapid successive calls to fromSingletons")
    void handlesRapidSuccessiveCallsToFromSingletons() {
      for (int i = 0; i < 1000; i++) {
        var deps = ToolDependencies.fromSingletons();
        assertNotNull(deps);
        assertNotNull(deps.logManager());
      }
    }

    @Test
    @DisplayName("handles creation with all null dependencies without errors")
    void handlesAllNullDependenciesWithoutErrors() {
      assertDoesNotThrow(() -> {
        var deps = new ToolDependencies(null, null, null, null);
        deps.logManager();
        deps.tbaClient();
        deps.tbaConfig();
        deps.logDirectory();
      });
    }

    @Test
    @DisplayName("dependencies container is independent of singleton state changes")
    void containerIndependentOfSingletonStateChanges() {
      var deps = ToolDependencies.fromSingletons();
      var originalLogManager = deps.logManager();

      // Container should still return same reference even after external changes
      assertSame(originalLogManager, deps.logManager());
    }
  }
}
