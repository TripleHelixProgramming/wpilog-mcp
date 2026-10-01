/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages daemon lifecycle for HTTP transport server instances.
 *
 * <p>Handles PID file management, process spawning via {@link ProcessBuilder},
 * health checking, and idempotent start detection.
 *
 * <p>PID files are stored at {@code ~/.wpilog-mcp/run/{name}.pid} and contain the process ID on
 * the first line and the port number on the second line. A start claims the file before it
 * spawns anything (its own process ID, the port, and a third line {@value #STARTING_MARKER}),
 * atomically, so two concurrent starts cannot both spawn a daemon; the claim is replaced by the
 * daemon's record once it is running and removed if the start fails.
 *
 * @since 0.8.0
 */
public class DaemonManager {
  private static final Logger logger = LoggerFactory.getLogger(DaemonManager.class);
  private static final String APP_NAME = "wpilog-mcp";
  /** Third line of a PID file claimed by a start that has not yet recorded its daemon. */
  static final String STARTING_MARKER = "starting";
  /** How long a start waits for the daemon (or a concurrent start's daemon) to answer. */
  private static final Duration DEFAULT_START_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration HEALTH_POLL_INTERVAL = Duration.ofMillis(250);
  /** The launchers' heap when WPILOG_MAX_HEAP is unset (bin/wpilog-mcp, bin/wpilog-mcp.bat). */
  static final String DEFAULT_MAX_HEAP = "4g";
  /** SimpleLogger's level property; forwarded so a level set for this JVM applies to the daemon. */
  private static final String LOG_LEVEL_PROPERTY = "org.slf4j.simpleLogger.defaultLogLevel";

  private final Path runDir;
  private final Duration startTimeout;

  public DaemonManager() {
    this(Path.of(System.getProperty("user.home"), "." + APP_NAME, "run"), DEFAULT_START_TIMEOUT);
  }

  DaemonManager(Path runDir) {
    this(runDir, DEFAULT_START_TIMEOUT);
  }

  DaemonManager(Path runDir, Duration startTimeout) {
    this.runDir = runDir;
    this.startTimeout = startTimeout;
  }

  /** A daemon recorded in a PID file whose process is alive and answers the health check. */
  public record RunningDaemon(long pid, int port) {}

  /**
   * Checks if a named server is already running on the given port.
   *
   * <p>Reads the PID file, checks that the process is alive, and performs an HTTP health check
   * on the port the file records. A stale file (process dead, or alive but not answering, so
   * the PID was reused) is removed. A healthy daemon on a different port is reported as an
   * error and is not counted as running: it has to be stopped before the server can be started
   * on the new port.
   *
   * @param name The server configuration name
   * @param port The expected HTTP port
   * @return true if the server is already running and healthy on {@code port}
   */
  public boolean isAlreadyRunning(String name, int port) {
    return findRunning(name).map(daemon -> reportRunning(name, port, daemon)).orElse(false);
  }

  private boolean reportRunning(String name, int port, RunningDaemon daemon) {
    if (daemon.port() == port) {
      logger.info("Server '{}' is already running (PID {}, port {})", name, daemon.pid(), port);
      return true;
    }
    logger.error("Server '{}' is running on port {} (PID {}), not the configured port {}. "
        + "Stop it first (kill {}), then start it again.",
        name, daemon.port(), daemon.pid(), port, daemon.pid());
    return false;
  }

  /**
   * Finds the live daemon recorded in the named server's PID file.
   *
   * <p>A malformed file, one whose process is dead, or one whose process no longer answers the
   * health check is removed. A claim by a start still in progress (its process alive) is kept
   * and reported as not running.
   */
  Optional<RunningDaemon> findRunning(String name) {
    var pidFile = pidFilePath(name);
    if (!Files.isRegularFile(pidFile)) {
      return Optional.empty();
    }

    try {
      var lines = Files.readAllLines(pidFile);
      if (lines.size() < 2) {
        logger.debug("Malformed PID file: {}", pidFile);
        deletePidFile(name);
        return Optional.empty();
      }

      long pid = Long.parseLong(lines.get(0).trim());
      int port = Integer.parseInt(lines.get(1).trim());
      boolean starting = lines.size() > 2 && STARTING_MARKER.equals(lines.get(2).trim());

      // Check if process is alive
      var handle = ProcessHandle.of(pid);
      if (handle.isEmpty() || !handle.get().isAlive()) {
        logger.debug("Stale PID file (process {} is dead): {}", pid, pidFile);
        deletePidFile(name);
        return Optional.empty();
      }

      if (starting) {
        logger.debug("Process {} is still starting server '{}' on port {}", pid, name, port);
        return Optional.empty();
      }

      // Process is alive — verify it's actually our server via health check
      if (healthCheck(port)) {
        return Optional.of(new RunningDaemon(pid, port));
      }

      // Process alive but health check failed — different process reused the PID
      logger.debug("PID {} alive but health check failed on port {}", pid, port);
      deletePidFile(name);
      return Optional.empty();

    } catch (IOException | NumberFormatException e) {
      logger.debug("Failed to read PID file {}: {}", pidFile, e.getMessage());
      deletePidFile(name);
      return Optional.empty();
    }
  }

  /**
   * Spawns a daemon process for the named server configuration, unless one is running.
   *
   * <p>Re-launches the current JAR with {@code --internal-daemon <name>} and optional
   * {@code --config <path>}. The child process stdout/stderr are redirected to a log file.
   *
   * @param name The server configuration name
   * @param port The HTTP port
   * @param configPath Optional explicit config file path, or null
   * @return true if the daemon is running on {@code port} when this returns: started by this
   *     call, already running, or started by a concurrent call; false if it could not be
   *     started, or a daemon of this name is running on another port
   */
  public boolean spawnDaemon(String name, int port, Path configPath) {
    var running = findRunning(name);
    if (running.isPresent()) {
      return reportRunning(name, port, running.get());
    }

    // Claim the PID file before spawning: of concurrent starts, only the one that claims it
    // spawns; the others wait for its daemon.
    try {
      if (!claimPidFile(name, port)) {
        logger.info("Another start of '{}' is in progress; waiting for it on port {}", name, port);
        return waitForHealth(port, null);
      }
    } catch (IOException e) {
      logger.error("Failed to write PID file for '{}': {}", name, e.getMessage());
      return false;
    }

    Process process = null;
    boolean recorded = false;
    try {
      var logDir = runDir.getParent().resolve("logs");
      Files.createDirectories(logDir);
      var logFile = logDir.resolve(name + ".log").toFile();

      // Resolve the JAR path from the code source
      var jarPath = resolveJarPath();
      var javaCmd = ProcessHandle.current().info().command().orElse("java");

      var maxHeap = daemonMaxHeap(System.getenv("WPILOG_MAX_HEAP"),
          java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
      var command = daemonCommand(javaCmd, maxHeap, System.getProperty(LOG_LEVEL_PROPERTY),
          jarPath, name, configPath);

      var pb = new ProcessBuilder(command);
      pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
      pb.redirectErrorStream(true);

      logger.debug("Spawning daemon: {}", command);
      process = pb.start();

      // Replace the claim with the daemon's record
      writePidFile(name, process.pid(), port);
      recorded = true;

      if (waitForHealth(port, process)) {
        logger.info("Server '{}' started as daemon (PID {}, port {}). Logs: {}",
            name, process.pid(), port, logFile);
        return true;
      }

      if (!process.isAlive()) {
        logger.error("Daemon process exited immediately. Check logs: {}", logFile);
        deletePidFile(name);
        return false;
      }

      // Alive but not answering yet: its record is kept, so a later start finds it healthy, or
      // finds it dead and clears the record.
      logger.error("Server '{}' started but is not responding on port {}. Check logs: {}",
          name, port, logFile);
      return false;

    } catch (IOException e) {
      logger.error("Failed to spawn daemon for '{}': {}", name, e.getMessage());
      if (process != null && !recorded) {
        process.destroy();
      }
      return false;
    } finally {
      if (!recorded) {
        // Release the claim: nothing was spawned, or its PID could not be recorded
        deletePidFile(name);
      }
    }
  }

  /**
   * The daemon's maximum heap: {@code WPILOG_MAX_HEAP}; else the {@code -Xmx} this JVM was
   * started with (the launcher passes one), the last one given being the one in force; else the
   * launcher's default. Never left to the JVM, whose default is a fraction of physical memory.
   */
  static String daemonMaxHeap(String envMaxHeap, List<String> jvmArguments) {
    if (envMaxHeap != null && !envMaxHeap.isBlank()) {
      return envMaxHeap.strip();
    }
    return jvmArguments.stream()
        .filter(arg -> arg.startsWith("-Xmx") && arg.length() > "-Xmx".length())
        .reduce((first, second) -> second)
        .map(arg -> arg.substring("-Xmx".length()))
        .orElse(DEFAULT_MAX_HEAP);
  }

  /** The command that starts the named server's daemon from {@code jarPath}. */
  static List<String> daemonCommand(String javaCmd, String maxHeap, String logLevel,
      String jarPath, String name, Path configPath) {
    var command = new ArrayList<String>();
    command.add(javaCmd);
    command.add("-Xmx" + maxHeap);
    // The daemon decides its log level from its configuration and environment; a level given
    // to this JVM as a system property is forwarded so it applies there too.
    if (logLevel != null && !logLevel.isBlank()) {
      command.add("-D" + LOG_LEVEL_PROPERTY + "=" + logLevel);
    }
    command.add("-jar");
    command.add(jarPath);
    command.add("--internal-daemon");
    command.add(name);
    if (configPath != null) {
      command.add("--config");
      command.add(configPath.toString());
    }
    return command;
  }

  /**
   * Polls the health endpoint until it answers, the process (when given) dies, or the start
   * timeout passes.
   */
  private boolean waitForHealth(int port, Process process) {
    long deadline = System.nanoTime() + startTimeout.toNanos();
    while (true) {
      if (process != null && !process.isAlive()) {
        return false;
      }
      if (healthCheck(port)) {
        return true;
      }
      if (System.nanoTime() >= deadline) {
        return false;
      }
      try {
        Thread.sleep(HEALTH_POLL_INTERVAL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }

  /**
   * Claims the named server's PID file for a start in progress: this process's ID, the port,
   * and the {@value #STARTING_MARKER} line. Created atomically, so of several concurrent starts
   * exactly one claims it.
   *
   * @return false if the file already exists (a daemon is recorded, or another start claimed it)
   */
  boolean claimPidFile(String name, int port) throws IOException {
    Files.createDirectories(runDir);
    try {
      Files.writeString(pidFilePath(name),
          ProcessHandle.current().pid() + "\n" + port + "\n" + STARTING_MARKER + "\n",
          StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      return true;
    } catch (FileAlreadyExistsException e) {
      return false;
    }
  }

  /**
   * Records a running daemon in the named server's PID file, replacing any claim. The file is
   * written whole (a temporary file moved into place) so that a concurrent reader never sees a
   * partial record and takes it for a stale one.
   */
  void writePidFile(String name, long pid, int port) throws IOException {
    Files.createDirectories(runDir);
    var pidFile = pidFilePath(name);
    var temp = Files.createTempFile(runDir, name + ".", ".pid.tmp");
    try {
      Files.writeString(temp, pid + "\n" + port + "\n");
      Files.move(temp, pidFile,
          StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temp);
    }
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
