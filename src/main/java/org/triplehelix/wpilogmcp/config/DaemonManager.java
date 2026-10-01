/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
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
 * spawns anything (its own process ID, the port, and a third line {@value #STARTING_MARKER});
 * once the daemon is spawned the file holds the daemon's process ID with
 * {@value #BOOTING_MARKER}, and once the daemon answers, its plain record. The claim is removed
 * if the start fails.
 *
 * <p>Two starts never spawn two daemons. Finding out whether one is running, removing a stale
 * record, and claiming the file are one step, taken under a lock that holds across threads and
 * processes ({@code {name}.lock}); and a record marked as starting or booting is left alone by
 * everyone but its own start, so a daemon that has been spawned and does not answer yet is not
 * mistaken for a reused process ID.
 *
 * <p>A start reads and writes the PID file only while it holds that lock. Windows refuses to
 * replace a file that is open in another program, so a start that replaced the record while
 * another start was reading it failed there. A write refused because some other program has the
 * file open (a virus scanner, someone displaying it) is tried again for half a second.
 *
 * @since 0.8.0
 */
public class DaemonManager {
  private static final Logger logger = LoggerFactory.getLogger(DaemonManager.class);
  private static final String APP_NAME = "wpilog-mcp";
  /** Third line of a PID file claimed by a start that has not yet recorded its daemon. */
  static final String STARTING_MARKER = "starting";
  /** Third line of a PID file whose daemon was spawned and has not answered yet. */
  static final String BOOTING_MARKER = "booting";
  /** How long a start waits for the daemon (or a concurrent start's daemon) to answer. */
  private static final Duration DEFAULT_START_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration HEALTH_POLL_INTERVAL = Duration.ofMillis(250);
  /** The launchers' heap when WPILOG_MAX_HEAP is unset (bin/wpilog-mcp, bin/wpilog-mcp.bat). */
  static final String DEFAULT_MAX_HEAP = "4g";
  /** SimpleLogger's level property; forwarded so a level set for this JVM applies to the daemon. */
  private static final String LOG_LEVEL_PROPERTY = "org.slf4j.simpleLogger.defaultLogLevel";

  /** A spawned daemon process, as far as a start needs it. */
  interface Launched {
    long pid();

    boolean isAlive();

    void destroy();
  }

  /** Starts the daemon process, its output appended to a log file. Tests substitute their own. */
  interface Launcher {
    Launched launch(List<String> command, java.io.File logFile) throws IOException;
  }

  private static final Launcher PROCESS_LAUNCHER = (command, logFile) -> {
    var pb = new ProcessBuilder(command);
    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
    pb.redirectErrorStream(true);
    var process = pb.start();
    return new Launched() {
      @Override
      public long pid() {
        return process.pid();
      }

      @Override
      public boolean isAlive() {
        return process.isAlive();
      }

      @Override
      public void destroy() {
        process.destroy();
      }
    };
  };

  /**
   * The writes that put a PID file in place. Windows refuses them while the file, or one just
   * deleted under the same name, is open in another program; tests substitute a file system
   * that refuses.
   */
  interface PidFileWriter {
    /** Creates the file with this content; fails if it exists. */
    void create(Path pidFile, String content) throws IOException;

    /** Moves a finished temporary file onto the PID file, replacing it. */
    void replace(Path temp, Path pidFile) throws IOException;
  }

  static final PidFileWriter FILE_SYSTEM = new PidFileWriter() {
    @Override
    public void create(Path pidFile, String content) throws IOException {
      Files.writeString(pidFile, content, StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);
    }

    @Override
    public void replace(Path temp, Path pidFile) throws IOException {
      Files.move(temp, pidFile,
          StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
  };

  /** A booting record this many start timeouts old whose daemon still does not answer is stale. */
  private static final int BOOTING_GRACE_TIMEOUTS = 3;

  /** Tries of a write to the PID file that is refused because the file is open elsewhere. */
  static final int FILE_BUSY_ATTEMPTS = 20;
  /** The wait between those tries: half a second in all. */
  private static final long FILE_BUSY_WAIT_MILLIS = 25;

  /**
   * The start lock within this JVM, by lock file: a file lock holds between processes, but a
   * second thread of the same process asking for it gets an exception, not a wait.
   */
  private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

  private final Path runDir;
  private final Duration startTimeout;
  private final Launcher launcher;
  private final PidFileWriter files;

  public DaemonManager() {
    this(Path.of(System.getProperty("user.home"), "." + APP_NAME, "run"), DEFAULT_START_TIMEOUT);
  }

  DaemonManager(Path runDir) {
    this(runDir, DEFAULT_START_TIMEOUT);
  }

  DaemonManager(Path runDir, Duration startTimeout) {
    this(runDir, startTimeout, PROCESS_LAUNCHER);
  }

  DaemonManager(Path runDir, Duration startTimeout, Launcher launcher) {
    this(runDir, startTimeout, launcher, FILE_SYSTEM);
  }

  DaemonManager(Path runDir, Duration startTimeout, Launcher launcher, PidFileWriter files) {
    this.runDir = runDir;
    this.startTimeout = startTimeout;
    this.launcher = launcher;
    this.files = files;
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
    return locked(name, () -> findRunning(name))
        .map(daemon -> reportRunning(name, port, daemon)).orElse(false);
  }

  /** An action on the named server's PID file that may fail to read or write it. */
  @FunctionalInterface
  private interface PidFileAction<T> {
    T run() throws IOException;
  }

  private Path lockPath(String name) {
    return runDir.resolve(name + ".lock").toAbsolutePath().normalize();
  }

  /** Whether the calling thread holds the named server's start lock. */
  boolean holdsStartLock(String name) {
    var lock = JVM_LOCKS.get(lockPath(name));
    return lock != null && lock.isHeldByCurrentThread();
  }

  /**
   * Runs {@code action} holding the named server's start lock, across threads and processes.
   * The lock is held only while the PID file is read and written (and one health check made),
   * never while a daemon boots. An action must not ask for the lock again.
   *
   * @throws java.io.UncheckedIOException if the action fails
   */
  private <T> T locked(String name, PidFileAction<T> action) {
    var lockPath = lockPath(name);
    var jvmLock = JVM_LOCKS.computeIfAbsent(lockPath, p -> new ReentrantLock());
    jvmLock.lock();
    try {
      FileChannel channel = null;
      try {
        Files.createDirectories(runDir);
        channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        channel.lock();
      } catch (IOException | UnsupportedOperationException e) {
        // No lock file (a read-only directory, a file system without locks): this JVM's lock
        // still holds, and the action reports what it cannot do
        logger.debug("No start lock file for '{}': {}", name, e.getMessage());
      }
      try {
        return action.run();
      } catch (IOException e) {
        throw new java.io.UncheckedIOException(e);
      } finally {
        if (channel != null) {
          try {
            channel.close(); // releases the lock
          } catch (IOException e) {
            logger.debug("Failed to release the start lock for '{}': {}", name, e.getMessage());
          }
        }
      }
    } finally {
      jvmLock.unlock();
    }
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
   * Finds the live daemon recorded in the named server's PID file. Call with the start lock
   * held: it removes stale records.
   *
   * <p>A malformed file, one whose process is dead, or one whose process no longer answers the
   * health check is removed. A claim by a start still in progress (its process alive) is kept
   * and reported as not running, and so is the record of a daemon that was spawned and does not
   * answer yet; that record becomes the daemon's plain one when it answers, and is removed if
   * it still does not after {@value #BOOTING_GRACE_TIMEOUTS} start timeouts.
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
      boolean booting = lines.size() > 2 && BOOTING_MARKER.equals(lines.get(2).trim());

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
        if (booting) settleRecord(name, pid, port); // it answers: no longer booting
        return Optional.of(new RunningDaemon(pid, port));
      }

      if (booting && !olderThanBootingGrace(pidFile)) {
        logger.debug("Server '{}' (PID {}) was spawned and does not answer on port {} yet",
            name, pid, port);
        return Optional.empty();
      }

      // Process alive but health check failed — a different process reused the PID, or a
      // daemon that never came up
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
    // One start decides at a time: a daemon is running, another start has the file, or this
    // start claims it. Of concurrent starts, only the one that claims the file spawns; the
    // others wait for its daemon.
    record Decision(Optional<RunningDaemon> running, boolean claimed) {}
    Decision decision;
    try {
      decision = locked(name, () -> {
        var running = findRunning(name);
        return new Decision(running, running.isEmpty() && claimPidFile(name, port));
      });
    } catch (java.io.UncheckedIOException e) {
      logger.error("Failed to write PID file for '{}': {}", name, e.getCause().getMessage());
      return false;
    }
    if (decision.running().isPresent()) {
      return reportRunning(name, port, decision.running().get());
    }
    if (!decision.claimed()) {
      logger.info("Another start of '{}' is in progress; waiting for it on port {}", name, port);
      if (!waitForHealth(port, null)) return false;
      logger.info("Server '{}' is running on port {} (started by the other start)", name, port);
      return true;
    }

    Launched process = null;
    boolean recorded = false;
    try {
      var logDir = runDir.getParent().resolve("logs");
      Files.createDirectories(logDir);
      var logFile = logDir.resolve(name + ".log").toFile();

      // Resolve the JAR path from the code source (a substituted launcher needs no JAR)
      var jarPath = launcher == PROCESS_LAUNCHER ? resolveJarPath() : "wpilog-mcp.jar";
      var javaCmd = ProcessHandle.current().info().command().orElse("java");

      var maxHeap = daemonMaxHeap(System.getenv("WPILOG_MAX_HEAP"),
          java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
      var command = daemonCommand(javaCmd, maxHeap, System.getProperty(LOG_LEVEL_PROPERTY),
          jarPath, name, configPath);

      logger.debug("Spawning daemon: {}", command);
      process = launcher.launch(command, logFile);

      // Replace the claim with the daemon's record, marked as booting until it answers: a
      // plain record of a live process that does not answer reads as a reused PID
      recordDaemon(name, process.pid(), port, BOOTING_MARKER);
      recorded = true;

      if (waitForHealth(port, process)) {
        try {
          recordDaemon(name, process.pid(), port, null);
        } catch (IOException e) {
          // It is running and recorded as booting: the next start that finds it answering
          // settles the record
          logger.warn("Server '{}' is running, but its PID file could not be updated: {}",
              name, e.toString());
        }
        logger.info("Server '{}' started as daemon (PID {}, port {}). Logs: {}",
            name, process.pid(), port, logFile);
        return true;
      }

      if (!process.isAlive()) {
        logger.error("Daemon process exited immediately. Check logs: {}", logFile);
        releaseRecord(name);
        return false;
      }

      // Alive but not answering yet: its record is kept, so a later start finds it healthy, or
      // finds it dead and clears the record.
      logger.error("Server '{}' started but is not responding on port {}. Check logs: {}",
          name, port, logFile);
      return false;

    } catch (IOException e) {
      logger.error("Failed to spawn daemon for '{}': {}", name, e.toString());
      if (process != null && !recorded) {
        process.destroy();
      }
      return false;
    } finally {
      if (!recorded) {
        // Release the claim: nothing was spawned, or its PID could not be recorded
        releaseRecord(name);
      }
    }
  }

  /**
   * Writes a daemon's record holding the start lock, so that no start has the file open to read
   * while it is replaced.
   */
  private void recordDaemon(String name, long pid, int port, String marker) throws IOException {
    try {
      locked(name, () -> {
        writePidFile(name, pid, port, marker);
        return null;
      });
    } catch (java.io.UncheckedIOException e) {
      throw e.getCause();
    }
  }

  /** Removes the named server's record, or this start's claim, holding the start lock. */
  private void releaseRecord(String name) {
    locked(name, () -> {
      deletePidFile(name);
      return null;
    });
  }

  /**
   * Replaces the booting record of a daemon that answers with its plain record. A record that
   * cannot be replaced stays as it is: the daemon is running either way, and the next start
   * that finds it answering tries again.
   */
  private void settleRecord(String name, long pid, int port) {
    try {
      writePidFile(name, pid, port);
    } catch (IOException e) {
      logger.debug("The PID file of running server '{}' could not be updated: {}", name,
          e.toString());
    }
  }

  /** A write to the PID file. */
  @FunctionalInterface
  private interface PidFileWrite {
    void run() throws IOException;
  }

  /**
   * Makes a write to the PID file, again after a short wait while the file system refuses it
   * because the file is open elsewhere. Only Windows refuses: it will not replace a file, or
   * create one under the name of a file just deleted, while another program has it open. A
   * refusal for any other reason (the file exists, the directory is gone) is not tried again.
   */
  private static void whenFileIsFree(PidFileWrite write) throws IOException {
    for (int attempt = 1;; attempt++) {
      try {
        write.run();
        return;
      } catch (FileSystemException e) {
        // Windows reports an open file as access denied, or as a sharing violation, which
        // Java gives no class of its own
        boolean busy = e instanceof AccessDeniedException
            || e.getClass() == FileSystemException.class;
        if (!busy || attempt >= FILE_BUSY_ATTEMPTS) throw e;
        try {
          Thread.sleep(FILE_BUSY_WAIT_MILLIS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw e;
        }
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
  private boolean waitForHealth(int port, Launched process) {
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

  /** Whether the PID file was written more than the booting grace period ago. */
  private boolean olderThanBootingGrace(Path pidFile) {
    try {
      var age = Duration.between(Files.getLastModifiedTime(pidFile).toInstant(),
          java.time.Instant.now());
      return age.compareTo(startTimeout.multipliedBy(BOOTING_GRACE_TIMEOUTS)) > 0;
    } catch (IOException e) {
      return true;
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
    var claim = ProcessHandle.current().pid() + "\n" + port + "\n" + STARTING_MARKER + "\n";
    try {
      whenFileIsFree(() -> files.create(pidFilePath(name), claim));
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
    writePidFile(name, pid, port, null);
  }

  /** Records a daemon with a marker on the third line ({@value #BOOTING_MARKER}), or none. */
  void writePidFile(String name, long pid, int port, String marker) throws IOException {
    Files.createDirectories(runDir);
    var pidFile = pidFilePath(name);
    var temp = Files.createTempFile(runDir, name + ".", ".pid.tmp");
    try {
      Files.writeString(temp, pid + "\n" + port + "\n" + (marker == null ? "" : marker + "\n"));
      whenFileIsFree(() -> files.replace(temp, pidFile));
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
