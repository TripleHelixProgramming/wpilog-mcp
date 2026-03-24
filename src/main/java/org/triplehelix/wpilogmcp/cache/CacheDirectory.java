package org.triplehelix.wpilogmcp.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the OS-appropriate directory for the persistent parse cache.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code WPILOG_DISK_CACHE_DIR} environment variable</li>
 *   <li>{@code -diskcachedir} CLI argument (set via {@link #setOverride})</li>
 *   <li>OS-specific application data directory:
 *     <ul>
 *       <li>macOS: {@code ~/Library/Application Support/wpilog-mcp/cache/}</li>
 *       <li>Linux: {@code $XDG_DATA_HOME/wpilog-mcp/cache/} (default: {@code ~/.local/share/})</li>
 *       <li>Windows: {@code %LOCALAPPDATA%/wpilog-mcp/cache/}</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * @since 0.5.0
 */
public class CacheDirectory {
  private static final Logger logger = LoggerFactory.getLogger(CacheDirectory.class);
  private static final String APP_NAME = "wpilog-mcp";

  private Path override;
  private Path resolved;

  /**
   * Sets an explicit cache directory path (from CLI argument).
   *
   * @param path The directory path
   */
  public void setOverride(String path) {
    if (path != null && !path.isBlank()) {
      this.override = Path.of(path);
      this.resolved = null; // Force re-resolve
    }
  }

  /**
   * Gets the resolved cache directory, creating it if necessary.
   *
   * @return The cache directory path
   * @throws IOException if the directory cannot be created
   */
  public Path getPath() throws IOException {
    if (resolved != null) {
      return resolved;
    }

    Path dir;
    if (override != null) {
      dir = override;
    } else {
      String envDir = System.getenv("WPILOG_DISK_CACHE_DIR");
      if (envDir != null && !envDir.isBlank()) {
        dir = Path.of(envDir);
      } else {
        dir = resolveOsDefault();
      }
    }

    dir = dir.toAbsolutePath().normalize();
    Files.createDirectories(dir);
    resolved = dir;
    logger.info("Cache directory: {}", resolved);
    return resolved;
  }

  private Path resolveOsDefault() {
    String os = System.getProperty("os.name", "").toLowerCase();
    String home = System.getProperty("user.home");

    if (os.contains("mac")) {
      return Path.of(home, "Library", "Application Support", APP_NAME, "cache");
    } else if (os.contains("win")) {
      String localAppData = System.getenv("LOCALAPPDATA");
      if (localAppData != null && !localAppData.isBlank()) {
        return Path.of(localAppData, APP_NAME, "cache");
      }
      return Path.of(home, "AppData", "Local", APP_NAME, "cache");
    } else {
      // Linux / other Unix
      String xdgData = System.getenv("XDG_DATA_HOME");
      if (xdgData != null && !xdgData.isBlank()) {
        return Path.of(xdgData, APP_NAME, "cache");
      }
      return Path.of(home, ".local", "share", APP_NAME, "cache");
    }
  }
}
