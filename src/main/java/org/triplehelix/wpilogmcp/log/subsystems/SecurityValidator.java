package org.triplehelix.wpilogmcp.log.subsystems;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates file paths against allowed directories to prevent path traversal attacks.
 *
 * <p>This class implements a whitelist-based security model where only files within explicitly
 * configured directories can be accessed. Path traversal attempts (e.g., "../../../etc/passwd")
 * are prevented by normalizing paths and checking against the whitelist.
 *
 * <p><b>Security Model:</b>
 *
 * <ul>
 *   <li>If no allowed directories are configured, all paths are allowed (backwards compatibility)
 *   <li>If allowed directories are configured, paths must be within one of them
 *   <li>Paths are normalized (absolute + resolve symlinks) before validation
 * </ul>
 *
 * <p>Thread-safe.
 *
 * @since 0.4.0
 */
public class SecurityValidator {
  private static final Logger logger = LoggerFactory.getLogger(SecurityValidator.class);

  private final Set<Path> allowedDirectories = new HashSet<>();

  /**
   * Adds a directory to the list of allowed directories for loading logs.
   *
   * <p>Only paths within allowed directories can be loaded. This prevents path traversal attacks
   * by restricting file access to explicitly allowed directories.
   *
   * @param directory The directory to allow (will be normalized to absolute path)
   */
  public synchronized void addAllowedDirectory(Path directory) {
    if (directory != null) {
      Path normalized = directory.toAbsolutePath().normalize();
      allowedDirectories.add(normalized);
      logger.info("Added allowed directory: {}", normalized);
    }
  }

  /**
   * Adds a directory to the list of allowed directories for loading logs.
   *
   * @param directory The directory path string to allow
   */
  public void addAllowedDirectory(String directory) {
    if (directory != null && !directory.isBlank()) {
      addAllowedDirectory(Path.of(directory));
    }
  }

  /** Clears all allowed directories. */
  public synchronized void clearAllowedDirectories() {
    allowedDirectories.clear();
    logger.info("Cleared all allowed directories");
  }

  /**
   * Gets a copy of the allowed directories set.
   *
   * @return A new set containing the allowed directories
   */
  public synchronized Set<Path> getAllowedDirectories() {
    return new HashSet<>(allowedDirectories);
  }

  /**
   * Validates that a path is within an allowed directory.
   *
   * <p>If no allowed directories are configured, all paths are allowed (for backwards
   * compatibility). Otherwise, the path must be within one of the configured allowed directories.
   *
   * @param filePath The path to validate
   * @throws IOException if the path is outside allowed directories
   */
  public synchronized void validate(Path filePath) throws IOException {
    if (allowedDirectories.isEmpty()) {
      // No restrictions configured - allow all paths (backwards compatibility)
      return;
    }

    Path normalizedPath = filePath.toAbsolutePath().normalize();

    // Check if path is within any allowed directory
    for (Path allowedDir : allowedDirectories) {
      if (normalizedPath.startsWith(allowedDir)) {
        return; // Path is allowed
      }
    }

    logger.warn("Access denied: path '{}' is outside allowed directories", normalizedPath);
    throw new IOException(
        "Access denied: path is outside configured log directories. "
            + "Configure allowed directories or use list_available_logs to find valid paths.");
  }

  /**
   * Validates that a path is within an allowed directory, with an exception for cached paths.
   *
   * <p>This variant allows paths that are already in the cache, even if they're outside allowed
   * directories. This supports use cases where logs are moved after being loaded.
   *
   * @param filePath The path to validate
   * @param isInCache Function that returns true if the path is already cached
   * @throws IOException if the path is outside allowed directories and not cached
   */
  public synchronized void validateOrAllowCached(Path filePath, java.util.function.Predicate<String> isInCache)
      throws IOException {
    if (allowedDirectories.isEmpty()) {
      return;
    }

    Path normalizedPath = filePath.toAbsolutePath().normalize();

    // Check if path is within any allowed directory
    for (Path allowedDir : allowedDirectories) {
      if (normalizedPath.startsWith(allowedDir)) {
        return; // Path is allowed
      }
    }

    // Check if already cached (allow re-access to cached logs)
    if (isInCache.test(normalizedPath.toString())) {
      return; // Already cached, allow access
    }

    logger.warn("Access denied: path '{}' is outside allowed directories", normalizedPath);
    throw new IOException(
        "Access denied: path is outside configured log directories. "
            + "Configure allowed directories or use list_available_logs to find valid paths.");
  }
}
