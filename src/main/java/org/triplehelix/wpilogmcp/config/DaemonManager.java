/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.ConnectException;
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
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.Version;
import org.triplehelix.wpilogmcp.mcp.HttpTransport;

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
 * <p>The health check reads what the server says of itself, not only that something answered.
 * A start that took any answer for its daemon once mistook a daemon of an older version for its
 * own, so an update left the old JAR running until someone noticed; and it took any program on
 * the port for the daemon. Now {@code /health} carries the version and the process ID: a daemon
 * of another version is stopped and started again (the starter claims its record under the
 * same lock as the version check and holds that claim through stopping and spawning), a program
 * that does not answer as this server is reported as holding the port, and a daemon that answers
 * on the port with no record is recorded.
 *
 * <p>A daemon is stopped by {@code POST /stop} with a token the start that spawned it wrote to a
 * file beside the PID file that only the user can read ({@code {name}.token}) and gave the
 * daemon in its environment: so a process that can read the file may stop the daemon, and no
 * other. A daemon too old to have that endpoint is ended as a process.
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
  /** Third line for {@code stop}, or a failed restart whose old daemon did not exit. */
  static final String STOPPING_MARKER = "stopping";
  /** The environment variable that gives a spawned daemon its stop token. */
  public static final String STOP_TOKEN_ENV = "WPILOG_STOP_TOKEN";
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

  /**
   * Starts the daemon process with extra environment variables, its output appended to a log
   * file. Tests substitute their own.
   */
  interface Launcher {
    Launched launch(List<String> command, Map<String, String> environment, java.io.File logFile)
        throws IOException;
  }

  private Runnable onJoiningStart = () -> {};
  /** Observes the join decision before probing health; tests hold boot until that decision. */
  void onJoiningStart(Runnable observer) { onJoiningStart = observer; }

  private static final Launcher PROCESS_LAUNCHER = (command, environment, logFile) -> {
    var pb = new ProcessBuilder(command);
    pb.environment().putAll(environment);
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

  /** The processes a record names, by ID. Tests substitute their own. */
  interface Processes {
    boolean isAlive(long pid);

    /** Asks the process to end, as a signal does; returns false when it cannot be asked. */
    boolean destroy(long pid);

    /** Ends the process without asking. */
    boolean destroyForcibly(long pid);
  }

  static final Processes SYSTEM_PROCESSES = new Processes() {
    @Override
    public boolean isAlive(long pid) {
      return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    @Override
    public boolean destroy(long pid) {
      return ProcessHandle.of(pid).map(ProcessHandle::destroy).orElse(false);
    }

    @Override
    public boolean destroyForcibly(long pid) {
      return ProcessHandle.of(pid).map(ProcessHandle::destroyForcibly).orElse(false);
    }
  };

  /**
   * A booting record this many start timeouts old whose daemon still does not answer is stale,
   * and so is a stopping record this old whose daemon still answers: its stopper is gone.
   */
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
  private final Processes processes;
  /** The version a daemon must report to be this start's own. */
  private final String ownVersion;

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
    this(runDir, startTimeout, launcher, files, SYSTEM_PROCESSES, Version.VERSION);
  }

  DaemonManager(Path runDir, Duration startTimeout, Launcher launcher, PidFileWriter files,
      Processes processes, String ownVersion) {
    this.runDir = runDir;
    this.startTimeout = startTimeout;
    this.launcher = launcher;
    this.files = files;
    this.processes = processes;
    this.ownVersion = ownVersion;
  }

  /**
   * A daemon recorded in a PID file whose process is alive and answers the health check as this
   * server, with the version it reports (null for a server from before the version was
   * reported, which is to say an older one).
   */
  public record RunningDaemon(long pid, int port, String version, boolean managed) {
    public RunningDaemon(long pid, int port, String version) { this(pid, port, version, false); }
  }

  /** Who answers on a port. */
  enum Holder {
    /** Nothing: the connection is refused. */
    NOBODY,
    /** A wpilog-mcp server: {@code /health} answers as one. */
    THIS_SERVER,
    /** Something else: a connection is accepted, but {@code /health} is not answered as ours. */
    STRANGER
  }

  /** What a probe of a port found: who holds it, and for this server its version and PID. */
  record PortProbe(Holder holder, String version, Long pid, boolean managed) {
    PortProbe(Holder holder, String version, Long pid) { this(holder, version, pid, false); }
    static final PortProbe NOBODY = new PortProbe(Holder.NOBODY, null, null);
    static final PortProbe STRANGER = new PortProbe(Holder.STRANGER, null, null);
  }

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

  /** Find the owner for an import without starting or restarting a daemon. */
  public Optional<RunningDaemon> runningDaemon(String name) {
    return locked(name, () -> findRunning(name));
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
        + "Stop it first (wpilog-mcp stop {}), then start it again.",
        name, daemon.port(), daemon.pid(), port, name);
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
   * it still does not after {@value #BOOTING_GRACE_TIMEOUTS} start timeouts. The record of a
   * daemon being stopped is kept and reported as not running while its process lives, so that
   * no start reports a daemon on its way out as running, and no start claims the file before
   * the port is free; a stopping record that old whose daemon still answers has lost its
   * stopper, and counts as running again.
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
      var marker = lines.size() > 2 ? lines.get(2).trim() : "";
      boolean starting = STARTING_MARKER.equals(marker);
      boolean booting = BOOTING_MARKER.equals(marker);
      boolean stopping = STOPPING_MARKER.equals(marker);

      // Check if process is alive
      if (!processes.isAlive(pid)) {
        logger.debug("Stale PID file (process {} is dead): {}", pid, pidFile);
        deletePidFile(name);
        return Optional.empty();
      }

      if (starting) {
        logger.debug("Process {} is still starting server '{}' on port {}", pid, name, port);
        return Optional.empty();
      }

      if (stopping && !olderThanBootingGrace(pidFile)) {
        logger.debug("Server '{}' (PID {}) is being stopped", name, pid);
        return Optional.empty();
      }

      // Process is alive — verify it's actually our server via health check
      var probe = probe(port);
      if (probe.holder() == Holder.THIS_SERVER) {
        if (!probe.managed() && (booting || stopping)) settleRecord(name, pid, port); // it answers: a plain record
        return Optional.of(new RunningDaemon(pid, port, probe.version(), probe.managed()));
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
   * <p>A daemon that is running and reports this JAR's version is left as it is. One that
   * reports another version (or none, being older than the version in {@code /health}) is
   * stopped and started again, so that an update never leaves an old JAR serving. A port held
   * by something that does not answer as this server is reported, and nothing is spawned; a
   * daemon of this version answering on the port with no record (its PID file removed) is
   * recorded rather than started again.
   *
   * @param name The server configuration name
   * @param port The HTTP port
   * @param configPath Optional explicit config file path, or null
   * @return true if a daemon of this version is running on {@code port} when this returns:
   *     started by this call, already running, started by a concurrent call, or restarted;
   *     false if it could not be started, a daemon of this name is running on another port, or
   *     another program holds the port
   */
  public boolean spawnDaemon(String name, int port, Path configPath) {
    // Choosing to restart and reserving that restart are one decision. The starter's live
    // PID keeps the claim valid even after the old daemon exits, until its replacement boots.
    record Decision(Optional<RunningDaemon> running, boolean claimed) {}
    Decision decision;
    try {
      decision = locked(name, () -> {
        try (var refresh = InstallGuard.acquire(runDir, runDir.resolve(".install-refresh.guard"))) {
          var running = findRunning(name);
          if (running.isPresent() && running.get().managed()) return new Decision(running, false);
          if (running.isPresent() && !ownVersion.equals(running.get().version())) {
            writePidFile(name, ProcessHandle.current().pid(), port, STARTING_MARKER);
            return new Decision(running, true);
          }
          return new Decision(running, running.isEmpty() && claimPidFile(name, port));
        }
      });
    } catch (java.io.UncheckedIOException e) {
      logger.error("Failed to write PID file for '{}': {}", name, e.getCause().getMessage());
      return false;
    }
    if (decision.running().isPresent()) {
      var running = decision.running().get();
      if (running.managed()) return refuseManaged(name, "restart");
      if (ownVersion.equals(running.version())) {
        return reportRunning(name, port, running);
      }
      logger.info("Server '{}' is running version {} (PID {}); this is version {}: "
          + "restarting it", name, versionName(running.version()), running.pid(), ownVersion);
      if (!endDaemon(name, running, true)) {
        logger.error("Server '{}' (PID {}) could not be stopped for the restart", name,
            running.pid());
        return false;
      }
    }
    if (!decision.claimed()) {
      onJoiningStart.run();
      // The other start may be restarting a daemon of another version, which takes a stop
      // and a boot: wait for as long as a booting record is given.
      logger.info("Another start of '{}' is in progress; waiting for it on port {}", name, port);
      if (!waitForHealth(port, null, startTimeout.multipliedBy(BOOTING_GRACE_TIMEOUTS))) {
        return false;
      }
      logger.info("Server '{}' is running on port {} (started by the other start)", name, port);
      return true;
    }
    return launchClaimed(name, port, configPath);
  }

  private static String versionName(String version) {
    return version == null ? "an older version, from before /health said" : version;
  }

  /**
   * Spawns the daemon for a start that holds the claim, once the port is known to be free.
   */
  private boolean launchClaimed(String name, int port, Path configPath) {
    // The port: free, ours without a record, or a stranger's
    var probe = probe(port);
    if (probe.holder() == Holder.STRANGER) {
      logger.error("Port {} is in use by another program, which does not answer as wpilog-mcp. "
          + "Choose another port for server '{}'.", port, name);
      releaseRecord(name);
      return false;
    }
    if (probe.holder() == Holder.THIS_SERVER) {
      if (probe.managed()) { releaseRecord(name); return refuseManaged(name, "restart"); }
      if (ownVersion.equals(probe.version()) && probe.pid() != null) {
        try {
          recordDaemon(name, probe.pid(), port, null);
          logger.info("Server '{}' was already running on port {} (PID {}) without a record; "
              + "recorded it", name, port, probe.pid());
          return true;
        } catch (IOException e) {
          logger.error("Failed to record the running server '{}': {}", name, e.toString());
          releaseRecord(name);
          return false;
        }
      }
      logger.error("Port {} is served by a wpilog-mcp server (version {}{}) that is not "
          + "recorded as server '{}'. Stop it, or choose another port.", port,
          versionName(probe.version()), probe.pid() == null ? "" : ", PID " + probe.pid(), name);
      releaseRecord(name);
      return false;
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

      // The stop token: in a file only the user can read, and in the daemon's environment
      var token = newToken();
      writeToken(name, token);

      logger.debug("Spawning daemon: {}", command);
      process = launcher.launch(command, Map.of(STOP_TOKEN_ENV, token), logFile);

      // Replace the claim with the daemon's record, marked as booting until it answers: a
      // plain record of a live process that does not answer reads as a reused PID
      recordDaemon(name, process.pid(), port, BOOTING_MARKER);
      recorded = true;

      if (waitForHealth(port, process, startTimeout)) {
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
   * Stops the named server: asks the daemon to finish its calls and exit, waits for it, and
   * removes its record. A daemon that is not running is reported, and that is success: the
   * point of {@code stop} is that nothing runs afterwards.
   *
   * @return true when no daemon of this name runs afterwards
   */
  public boolean stopDaemon(String name) {
    return stopDaemon(name, null);
  }

  /** A managed service has no PID record. Probe the configured port before declaring it absent. */
  public boolean stopDaemon(String name, Integer port) {
    Optional<RunningDaemon> running;
    try {
      running = locked(name, () -> findRunning(name));
    } catch (java.io.UncheckedIOException e) {
      logger.error("Failed to read the PID file for '{}': {}", name, e.getCause().getMessage());
      return false;
    }
    if (running.isEmpty() && port != null && probe(port).managed()) return refuseManaged(name, "stop");
    if (running.isEmpty()) {
      logger.info("Server '{}' is not running", name);
      deleteToken(name);
      return true;
    }
    var daemon = running.get();
    if (daemon.managed()) return refuseManaged(name, "stop");
    if (endDaemon(name, daemon, false)) {
      logger.info("Server '{}' stopped (PID {})", name, daemon.pid());
      return true;
    }
    logger.error("Server '{}' (PID {}) did not stop", name, daemon.pid());
    return false;
  }

  private boolean refuseManaged(String name, String action) {
    logger.error("Server '{}' is managed; wpilog-mcp will not adopt, stop or replace it. Use: sudo systemctl {} wpilog-mcp-{}.service",
        name, action, name);
    return false;
  }

  /**
   * Ends a running daemon: marks its record as stopping under the lock and asks it to stop
   * (by {@code POST /stop} with the token, or, for a daemon too old to have that endpoint, as
   * a process), waits for the process to exit, ends it without asking if it has not after the
   * start timeout (the transport finishes its calls within half that), and removes its record
   * and token under the lock. A restart already holds its starter's claim and keeps it through
   * the stop, so neither another restart decision nor the old PID dying can admit a second
   * starter. A failed stop restores the old daemon's stopping record for later inspection.
   * The lock is not held while waiting.
   */
  private boolean endDaemon(String name, RunningDaemon daemon, boolean restarting) {
    if (daemon.managed() || probe(daemon.port()).managed()) return refuseManaged(name, restarting ? "restart" : "stop");
    try {
      locked(name, () -> {
        if (!restarting) {
          writePidFile(name, daemon.pid(), daemon.port(), STOPPING_MARKER);
        }
        var token = readToken(name);
        if (token != null && requestStop(daemon.port(), token)) {
          logger.debug("Server '{}' (PID {}) accepted the stop request", name, daemon.pid());
        } else {
          logger.info("Server '{}' (PID {}) takes no stop request{}; ending its process", name,
              daemon.pid(), token == null ? " (no token file)" : "");
          processes.destroy(daemon.pid());
        }
        return null;
      });
    } catch (java.io.UncheckedIOException e) {
      logger.error("Failed to mark server '{}' as stopping: {}", name, e.getCause().getMessage());
      return false;
    }
    if (!waitForExit(daemon.pid(), startTimeout)) {
      logger.warn("Server '{}' (PID {}) did not exit within {} ms; ending it", name, daemon.pid(),
          startTimeout.toMillis());
      processes.destroyForcibly(daemon.pid());
      if (!waitForExit(daemon.pid(), Duration.ofSeconds(2))) {
        if (restarting) {
          try {
            recordDaemon(name, daemon.pid(), daemon.port(), STOPPING_MARKER);
          } catch (IOException e) {
            logger.error("Failed to restore the stopping record for '{}': {}", name, e.toString());
          }
        }
        return false;
      }
    }
    locked(name, () -> {
      // A restart keeps its claim; a plain stop removes only the daemon it stopped.
      if (!restarting && recordedPid(name) == daemon.pid()) {
        deletePidFile(name);
      }
      deleteToken(name);
      return null;
    });
    return true;
  }

  private boolean waitForExit(long pid, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (processes.isAlive(pid)) {
      if (System.nanoTime() >= deadline) return false;
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return true;
  }

  /** The process ID on the first line of the named server's PID file, or -1. */
  private long recordedPid(String name) {
    try {
      var lines = Files.readAllLines(pidFilePath(name));
      return lines.isEmpty() ? -1 : Long.parseLong(lines.get(0).trim());
    } catch (IOException | NumberFormatException e) {
      return -1;
    }
  }

  /** Asks the server on {@code port} to stop, with the token. */
  boolean requestStop(int port, String token) {
    try {
      var url = URI.create("http://127.0.0.1:" + port + "/stop").toURL();
      var conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("POST");
      conn.setRequestProperty(HttpTransport.STOP_TOKEN_HEADER, token);
      conn.setConnectTimeout(2000);
      conn.setReadTimeout(5000);
      conn.setDoOutput(true);
      conn.getOutputStream().close();
      int status = conn.getResponseCode();
      conn.disconnect();
      if (status != 200) {
        logger.debug("Stop request to port {} answered {}", port, status);
      }
      return status == 200;
    } catch (IOException e) {
      logger.debug("Stop request to port {} failed: {}", port, e.toString());
      return false;
    }
  }

  /** A fresh stop token: 32 random bytes, as hex. */
  private static String newToken() {
    var bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  /**
   * Writes the named server's stop token to {@code {name}.token}, readable by the user alone
   * where the file system has permissions (the user's home folder is private on Windows).
   */
  void writeToken(String name, String token) throws IOException {
    Files.createDirectories(runDir);
    var path = tokenPath(name);
    Files.deleteIfExists(path);
    if (Files.getFileStore(runDir).supportsFileAttributeView("posix")) {
      // Created with its permissions, so there is no moment at which others can read it
      Files.createFile(path, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
          java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
    }
    Files.writeString(path, token + "\n");
  }

  /** The named server's stop token, or null when there is no token file. */
  String readToken(String name) {
    try {
      var lines = Files.readAllLines(tokenPath(name));
      return lines.isEmpty() || lines.get(0).isBlank() ? null : lines.get(0).trim();
    } catch (IOException e) {
      return null;
    }
  }

  private void deleteToken(String name) {
    try {
      Files.deleteIfExists(tokenPath(name));
    } catch (IOException e) {
      logger.debug("Failed to delete the token file for '{}': {}", name, e.getMessage());
    }
  }

  Path tokenPath(String name) {
    return runDir.resolve(name + ".token");
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
   * Polls the health endpoint until a server of this version answers, the process (when given)
   * dies, or the start timeout passes. A server of another version answering meanwhile is one
   * on its way out, being restarted by another start.
   */
  private boolean waitForHealth(int port, Launched process, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      if (process != null && !process.isAlive()) {
        return false;
      }
      var probe = probe(port);
      if (probe.holder() == Holder.THIS_SERVER && !probe.managed() && ownVersion.equals(probe.version())) {
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
   * Whether a wpilog-mcp server answers on the port, whatever its version.
   *
   * @param port The HTTP port to check
   */
  boolean healthCheck(int port) {
    return probe(port).holder() == Holder.THIS_SERVER;
  }

  /**
   * Asks {@code /health} on the port who holds it. A refused connection is nobody. A 200 with a
   * JSON body that says {@code status: ok} and counts sessions is this server, with the version
   * and process ID it reports (absent from a server older than the one that first said them). Anything else that
   * accepts the connection is a stranger: another program, or a server of ours that is not a
   * wpilog-mcp server answering as one.
   */
  PortProbe probe(int port) {
    try {
      // The dedicated /health endpoint returns at once; a GET of /mcp would open a stream
      var url = URI.create("http://127.0.0.1:" + port + "/health").toURL();
      var conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(2000);
      conn.setReadTimeout(2000);
      try {
        int status = conn.getResponseCode();
        if (status != 200) return PortProbe.STRANGER;
        String body;
        try (var in = conn.getInputStream()) {
          body = new String(in.readNBytes(4096), java.nio.charset.StandardCharsets.UTF_8);
        }
        var parsed = JsonParser.parseString(body);
        if (!parsed.isJsonObject()) return PortProbe.STRANGER;
        var health = parsed.getAsJsonObject();
        if (!"ok".equals(stringOrNull(health, "status")) || !health.has("sessions")) {
          return PortProbe.STRANGER;
        }
        Long pid = health.has("pid") && health.get("pid").isJsonPrimitive()
            ? health.get("pid").getAsLong() : null;
        return new PortProbe(Holder.THIS_SERVER, stringOrNull(health, "version"), pid,
            health.has("managed") && health.get("managed").isJsonPrimitive() && health.get("managed").getAsBoolean());
      } finally {
        conn.disconnect();
      }
    } catch (ConnectException e) {
      return PortProbe.NOBODY;
    } catch (IOException | RuntimeException e) {
      // Accepted the connection but did not answer as this server: a timeout, no HTTP, no JSON
      logger.debug("Port {} answered the health check as a stranger: {}", port, e.toString());
      return PortProbe.STRANGER;
    }
  }

  private static String stringOrNull(JsonObject object, String field) {
    return object.has(field) && object.get(field).isJsonPrimitive()
        ? object.get(field).getAsString() : null;
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
