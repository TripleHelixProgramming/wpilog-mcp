package org.triplehelix.wpilogmcp.tools;

import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.tba.TbaClient;
import org.triplehelix.wpilogmcp.tba.TbaConfig;

/**
 * Container for tool dependencies.
 *
 * <p>This record enables dependency injection for tools while maintaining backwards compatibility
 * with the existing singleton pattern. Tools can be created with explicit dependencies (for
 * testing with mocks) or using the default singleton-based factory method.
 *
 * <p>Example usage with singletons (default, backwards-compatible):
 * <pre>{@code
 * var deps = ToolDependencies.fromSingletons();
 * var tool = new SomeTool(deps);
 * }</pre>
 *
 * <p>Example usage with mocked dependencies (for testing):
 * <pre>{@code
 * var mockLogManager = mock(LogManager.class);
 * var deps = new ToolDependencies(mockLogManager, null, null, null);
 * var tool = new SomeTool(deps);
 * // Now tool uses the mocked LogManager for complete test isolation
 * }</pre>
 *
 * @param logManager The LogManager instance (or null)
 * @param tbaClient The TbaClient instance (or null)
 * @param tbaConfig The TbaConfig instance (or null)
 * @param logDirectory The LogDirectory instance (or null)
 * @since 0.4.0
 */
public record ToolDependencies(
    LogManager logManager,
    TbaClient tbaClient,
    TbaConfig tbaConfig,
    LogDirectory logDirectory) {

  /**
   * Creates a ToolDependencies instance from singleton instances.
   *
   * <p>This is the default factory method that maintains backwards compatibility
   * with the existing singleton architecture.
   *
   * @return A new ToolDependencies using singleton instances
   */
  public static ToolDependencies fromSingletons() {
    return new ToolDependencies(
        LogManager.getInstance(),
        TbaClient.getInstance(),
        TbaConfig.getInstance(),
        LogDirectory.getInstance());
  }
}
