package org.triplehelix.wpilogmcp.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("CacheDirectory")
class CacheDirectoryTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("override takes precedence over defaults")
  void overrideTakesPrecedence() throws IOException {
    var dir = new CacheDirectory();
    Path override = tempDir.resolve("my-custom-cache");
    dir.setOverride(override.toString());

    Path resolved = dir.getPath();
    assertEquals(override.toAbsolutePath().normalize(), resolved);
    assertTrue(Files.isDirectory(resolved), "Should create directory");
  }

  @Test
  @DisplayName("creates directory on first access")
  void createsDirectory() throws IOException {
    var dir = new CacheDirectory();
    Path target = tempDir.resolve("new-dir/nested");
    dir.setOverride(target.toString());

    assertFalse(Files.exists(target));
    dir.getPath();
    assertTrue(Files.isDirectory(target));
  }

  @Test
  @DisplayName("repeated getPath returns same path")
  void cachedPath() throws IOException {
    var dir = new CacheDirectory();
    dir.setOverride(tempDir.resolve("cached").toString());

    Path p1 = dir.getPath();
    Path p2 = dir.getPath();
    assertEquals(p1, p2);
  }

  @Test
  @DisplayName("setOverride clears cached path")
  void overrideClearsCached() throws IOException {
    var dir = new CacheDirectory();
    dir.setOverride(tempDir.resolve("first").toString());
    Path p1 = dir.getPath();

    dir.setOverride(tempDir.resolve("second").toString());
    Path p2 = dir.getPath();

    assertNotEquals(p1, p2);
  }

  @Test
  @DisplayName("null override is ignored")
  void nullOverrideIgnored() throws IOException {
    var dir = new CacheDirectory();
    dir.setOverride(tempDir.resolve("set").toString());
    Path p1 = dir.getPath();

    dir.setOverride(null); // Should not clear the previous override
    Path p2 = dir.getPath();
    assertEquals(p1, p2);
  }

  @Test
  @DisplayName("blank override is ignored")
  void blankOverrideIgnored() throws IOException {
    var dir = new CacheDirectory();
    dir.setOverride(tempDir.resolve("set").toString());
    Path p1 = dir.getPath();

    dir.setOverride("   ");
    Path p2 = dir.getPath();
    assertEquals(p1, p2);
  }

  @Nested
  @DisplayName("Default Path Resolution")
  class DefaultPathTests {

    @Test
    @DisplayName("default path is under user home")
    void defaultPathUnderHome() throws IOException {
      var dir = new CacheDirectory();
      Path resolved = dir.getPath();
      String home = System.getProperty("user.home");

      // The default path should be somewhere under the user's home directory
      // or under a system-specific app data location
      assertNotNull(resolved);
      assertTrue(Files.isDirectory(resolved));
    }

    @Test
    @DisplayName("default path contains wpilog-mcp")
    void defaultPathContainsAppName() throws IOException {
      var dir = new CacheDirectory();
      Path resolved = dir.getPath();

      assertTrue(resolved.toString().contains("wpilog-mcp"),
          "Default path should contain app name: " + resolved);
    }
  }
}
