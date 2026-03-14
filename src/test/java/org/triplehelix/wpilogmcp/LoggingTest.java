package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.mcp.McpServer;

/**
 * Basic tests to ensure logging doesn't break server initialization.
 */
class LoggingTest {

  @Test
  @DisplayName("server initializes without stdout interference")
  void serverInitializes() {
    // This will redirect System.out to System.err
    new McpServer();
    
    // Check that System.out is now different (it should be System.err)
    assertSame(System.out, System.err);
    
    // We can't easily test more without starting the server loop,
    // but this confirms the logging setup in constructor works.
  }
}
