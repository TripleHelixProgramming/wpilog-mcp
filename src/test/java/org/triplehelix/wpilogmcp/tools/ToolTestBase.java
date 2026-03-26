package org.triplehelix.wpilogmcp.tools;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.Tool;

/**
 * Base class for tool tests that captures registered tools via a ToolRegistry wrapper.
 *
 * <p>Subclasses override {@link #registerTools(ToolRegistry)} to register the tool class(es)
 * under test. The base class provides {@link #findTool(String)} for looking up tools by name,
 * {@link #putLogInCache(ParsedLog)} for injecting test logs, and automatic LogManager cleanup.
 */
abstract class ToolTestBase {

  protected List<Tool> tools;

  @BeforeEach
  final void setUpToolRegistry() {
    tools = new ArrayList<>();
    var capturingRegistry = new ToolRegistry() {
      @Override
      public void registerTool(Tool tool) {
        tools.add(tool);
        super.registerTool(tool);
      }
    };
    registerTools(capturingRegistry);
  }

  /**
   * Register the tool class(es) under test with the given registry.
   * Called automatically during setUp.
   */
  protected abstract void registerTools(ToolRegistry registry);

  @AfterEach
  void tearDownLogManager() {
    LogManager.getInstance().unloadAllLogs();
  }

  protected Tool findTool(String name) {
    return tools.stream()
        .filter(t -> t.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Tool not found: " + name));
  }

  protected void putLogInCache(ParsedLog log) {
    LogManager.getInstance().testPutLog(log.path(), log);
  }
}
