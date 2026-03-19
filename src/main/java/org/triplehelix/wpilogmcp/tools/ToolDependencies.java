package org.triplehelix.wpilogmcp.tools;

import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.tba.TbaClient;
import org.triplehelix.wpilogmcp.tba.TbaConfig;

/**
 * Container for tool dependencies.
 *
 * <p>This class enables dependency injection for tools while maintaining backwards compatibility
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
 * @since 0.4.0
 */
public class ToolDependencies {
  private final LogManager logManager;
  private final TbaClient tbaClient;
  private final TbaConfig tbaConfig;
  private final LogDirectory logDirectory;

  /**
   * Creates a ToolDependencies instance from singleton instances.
   *
   * <p>This is the default factory method that maintains backwards compatibility
   * with the existing singleton architecture. Tools created with these dependencies
   * will behave identically to tools created before dependency injection was introduced.
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

  /**
   * Creates a ToolDependencies instance with explicit dependencies.
   *
   * <p>This constructor enables dependency injection for testing and alternative
   * configurations. Any dependency can be null if not needed by the tool.
   *
   * @param logManager The LogManager instance (or null)
   * @param tbaClient The TbaClient instance (or null)
   * @param tbaConfig The TbaConfig instance (or null)
   * @param logDirectory The LogDirectory instance (or null)
   */
  public ToolDependencies(
      LogManager logManager,
      TbaClient tbaClient,
      TbaConfig tbaConfig,
      LogDirectory logDirectory) {
    this.logManager = logManager;
    this.tbaClient = tbaClient;
    this.tbaConfig = tbaConfig;
    this.logDirectory = logDirectory;
  }

  /**
   * Gets the LogManager instance.
   *
   * @return The LogManager, or null if not set
   */
  public LogManager getLogManager() {
    return logManager;
  }

  /**
   * Gets the TbaClient instance.
   *
   * @return The TbaClient, or null if not set
   */
  public TbaClient getTbaClient() {
    return tbaClient;
  }

  /**
   * Gets the TbaConfig instance.
   *
   * @return The TbaConfig, or null if not set
   */
  public TbaConfig getTbaConfig() {
    return tbaConfig;
  }

  /**
   * Gets the LogDirectory instance.
   *
   * @return The LogDirectory, or null if not set
   */
  public LogDirectory getLogDirectory() {
    return logDirectory;
  }
}
