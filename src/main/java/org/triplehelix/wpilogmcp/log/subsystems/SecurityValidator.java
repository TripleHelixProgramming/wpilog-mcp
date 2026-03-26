package org.triplehelix.wpilogmcp.log.subsystems;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
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

  private final Set<Path> allowedDirectories = new CopyOnWriteArraySet<>();

  /**
   * Adds a directory to the list of allowed directories for loading logs.
   *
   * <p>Only paths within allowed directories can be loaded. This prevents path traversal attacks
   * by restricting file access to explicitly allowed directories.
   *
   * @param directory The directory to allow (will be normalized to absolute path)
   */
  public void addAllowedDirectory(Path directory) {
    if (directory != null) {
      // Use toRealPath() to resolve symlinks if the directory exists,
      // so that the allowed path matches what toRealPath() returns during validation
      Path normalized;
      try {
        normalized = java.nio.file.Files.exists(directory)
            ? directory.toRealPath()
            : directory.toAbsolutePath().normalize();
      } catch (java.io.IOException e) {
        normalized = directory.toAbsolutePath().normalize();
      }
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
  public void clearAllowedDirectories() {
    allowedDirectories.clear();
    logger.info("Cleared all allowed directories");
  }

  /**
   * Gets a copy of the allowed directories set.
   *
   * @return A new set containing the allowed directories
   */
  public Set<Path> getAllowedDirectories() {
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
  public void validate(Path filePath) throws IOException {
    if (allowedDirectories.isEmpty()) {
      // No restrictions configured - allow all paths (backwards compatibility)
      return;
    }

    Path normalizedPath = resolvePath(filePath);

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
   * Resolves a path, following symlinks where possible to prevent symlink-based path traversal.
   * If the file exists, uses toRealPath() which resolves all symlinks.
   * If not, walks up the ancestor chain to find the nearest existing directory, resolves
   * symlinks there, and appends the remaining relative portion.
   */
  private Path resolvePath(Path filePath) throws IOException {
    Path absPath = filePath.toAbsolutePath().normalize();
    if (java.nio.file.Files.exists(absPath)) {
      return absPath.toRealPath();
    }
    // Walk up the path to find the nearest existing ancestor
    Path current = absPath;
    Path relative = Path.of("");
    while (current != null && !java.nio.file.Files.exists(current)) {
      relative = current.getFileName() != null
          ? current.getFileName().resolve(relative)
          : relative;
      current = current.getParent();
    }
    if (current != null) {
      Path resolved = current.toRealPath();
      return relative.toString().isEmpty() ? resolved : resolved.resolve(relative);
    }
    return absPath;
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
  public void validateOrAllowCached(Path filePath, java.util.function.Predicate<String> isInCache)
      throws IOException {
    if (allowedDirectories.isEmpty()) {
      return;
    }

    Path normalizedPath = resolvePath(filePath);

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
