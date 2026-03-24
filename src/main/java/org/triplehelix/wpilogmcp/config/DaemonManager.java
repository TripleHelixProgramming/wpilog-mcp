package org.triplehelix.wpilogmcp.config;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages daemon lifecycle for HTTP transport server instances.
 *
 * <p>Handles PID file management, process spawning via {@link ProcessBuilder},
 * health checking, and idempotent start detection.
 *
 * <p>PID files are stored at {@code ~/.wpilog-mcp/run/{name}.pid} and contain
 * the process ID on the first line and the port number on the second line.
 *
 * @since 0.8.0
 */
public class DaemonManager {
  private static final Logger logger = LoggerFactory.getLogger(DaemonManager.class);
  private static final String APP_NAME = "wpilog-mcp";

  private final Path runDir;

  public DaemonManager() {
    this.runDir = Path.of(System.getProperty("user.home"), "." + APP_NAME, "run");
  }

  DaemonManager(Path runDir) {
    this.runDir = runDir;
  }

  /**
   * Checks if a named server is already running.
   *
   * <p>Reads the PID file, checks if the process is alive, and performs
   * an HTTP health check. If the PID file is stale (process dead), it is removed.
   *
   * @param name The server configuration name
   * @param port The expected HTTP port
   * @return true if the server is already running and healthy
   */
  public boolean isAlreadyRunning(String name, int port) {
    var pidFile = pidFilePath(name);
    if (!Files.isRegularFile(pidFile)) {
      return false;
    }

    try {
      var lines = Files.readAllLines(pidFile);
      if (lines.size() < 2) {
        logger.debug("Malformed PID file: {}", pidFile);
        deletePidFile(name);
        return false;
      }

      long pid = Long.parseLong(lines.get(0).trim());
      int pidPort = Integer.parseInt(lines.get(1).trim());

      // Check if process is alive
      var handle = ProcessHandle.of(pid);
      if (handle.isEmpty() || !handle.get().isAlive()) {
        logger.debug("Stale PID file (process {} is dead): {}", pid, pidFile);
        deletePidFile(name);
        return false;
      }

      // Process is alive — verify it's actually our server via health check
      if (healthCheck(pidPort)) {
        logger.info("Server '{}' is already running (PID {}, port {})", name, pid, pidPort);
        return true;
      }

      // Process alive but health check failed — different process reused the PID
      logger.debug("PID {} alive but health check failed on port {}", pid, pidPort);
      deletePidFile(name);
      return false;

    } catch (IOException | NumberFormatException e) {
      logger.debug("Failed to read PID file {}: {}", pidFile, e.getMessage());
      deletePidFile(name);
      return false;
    }
  }

  /**
   * Spawns a daemon process for the named server configuration.
   *
   * <p>Re-launches the current JAR with {@code --internal-daemon <name>} and optional
   * {@code --config <path>}. The child process stdout/stderr are redirected to a log file.
   *
   * @param name The server configuration name
   * @param port The HTTP port
   * @param configPath Optional explicit config file path, or null
   * @return true if the daemon started successfully
   */
  public boolean spawnDaemon(String name, int port, Path configPath) {
    // Check if a healthy server is already running before spawning, to prevent
    // orphaned daemon processes from concurrent start invocations.
    if (isAlreadyRunning(name, port)) {
      logger.info("Server '{}' is already running", name);
      return true;
    }

    try {
      Files.createDirectories(runDir);
      var logDir = runDir.getParent().resolve("logs");
      Files.createDirectories(logDir);
      var logFile = logDir.resolve(name + ".log").toFile();

      // Resolve the JAR path from the code source
      var jarPath = resolveJarPath();
      var javaCmd = ProcessHandle.current().info().command().orElse("java");

      var command = new java.util.ArrayList<String>();
      command.add(javaCmd);

      // Forward JVM heap settings if set
      var maxHeap = System.getenv("WPILOG_MAX_HEAP");
      if (maxHeap != null && !maxHeap.isBlank()) {
        command.add("-Xmx" + maxHeap);
      }

      command.add("-jar");
      command.add(jarPath);
      command.add("--internal-daemon");
      command.add(name);
      if (configPath != null) {
        command.add("--config");
        command.add(configPath.toString());
      }

      var pb = new ProcessBuilder(command);
      pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
      pb.redirectErrorStream(true);

      logger.debug("Spawning daemon: {}", command);
      var process = pb.start();

      // Write PID file — fails atomically if another start raced us
      try {
        writePidFile(name, process.pid(), port);
      } catch (java.nio.file.FileAlreadyExistsException e) {
        // Another start won the race — destroy our duplicate process
        logger.info("Another instance won the startup race for '{}', aborting duplicate", name);
        process.destroy();
        return false;
      }

      // Wait briefly and verify the server started
      Thread.sleep(2000);

      if (!process.isAlive()) {
        logger.error("Daemon process exited immediately. Check logs: {}", logFile);
        deletePidFile(name);
        return false;
      }

      if (healthCheck(port)) {
        logger.info("Server '{}' started as daemon (PID {}, port {}). Logs: {}",
            name, process.pid(), port, logFile);
        return true;
      }

      // Process alive but not responding — give it more time
      Thread.sleep(3000);
      if (healthCheck(port)) {
        logger.info("Server '{}' started as daemon (PID {}, port {}). Logs: {}",
            name, process.pid(), port, logFile);
        return true;
      }

      logger.error("Server '{}' started but is not responding on port {}. Check logs: {}",
          name, port, logFile);
      return false;

    } catch (IOException e) {
      logger.error("Failed to spawn daemon for '{}': {}", name, e.getMessage());
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Writes a PID file for the named server.
   */
  void writePidFile(String name, long pid, int port) throws IOException {
    Files.createDirectories(runDir);
    var pidFile = pidFilePath(name);
    // Use CREATE_NEW to fail atomically if another process already wrote the PID file.
    // FileAlreadyExistsException propagates to spawnDaemon which destroys the duplicate process.
    Files.writeString(pidFile, pid + "\n" + port + "\n",
        java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
  }

  /**
   * Deletes the PID file for the named server.
   */
  public void deletePidFile(String name) {
    try {
      Files.deleteIfExists(pidFilePath(name));
    } catch (IOException e) {
      logger.debug("Failed to delete PID file for '{}': {}", name, e.getMessage());
    }
  }

  /**
   * Returns the PID file path for a named server.
   */
  Path pidFilePath(String name) {
    return runDir.resolve(name + ".pid");
  }

  /**
   * Performs an HTTP health check against the server.
   *
   * @param port The HTTP port to check
   * @return true if the server responds (any HTTP status indicates it's alive)
   */
  boolean healthCheck(int port) {
    try {
      // Use the dedicated /health endpoint which returns immediately,
      // instead of /mcp GET which opens a long-lived SSE stream.
      var url = URI.create("http://127.0.0.1:" + port + "/health").toURL();
      var conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(2000);
      conn.setReadTimeout(2000);
      conn.connect();
      int status = conn.getResponseCode();
      conn.disconnect();
      // Any response means the server is alive
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private String resolveJarPath() {
    try {
      var codeSource = DaemonManager.class.getProtectionDomain().getCodeSource();
      if (codeSource != null) {
        var location = codeSource.getLocation();
        if (location != null) {
          var path = Path.of(location.toURI());
          if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
            return path.toString();
          }
        }
      }
    } catch (Exception e) {
      logger.debug("Could not resolve JAR path from CodeSource: {}", e.getMessage());
    }

    // Fallback: look for the shadow JAR in known build locations
    var candidates = new Path[]{
        Path.of("build", "libs", "wpilog-mcp.jar"),
        Path.of("wpilog-mcp.jar")
    };
    for (var candidate : candidates) {
      if (Files.isRegularFile(candidate)) {
        return candidate.toAbsolutePath().toString();
      }
    }

    throw new IllegalStateException(
        "Cannot locate wpilog-mcp JAR file. Run from the JAR or set the classpath explicitly.");
  }
}
