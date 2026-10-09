/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.triplehelix.wpilogmcp.config.ConfigLoader;
import org.triplehelix.wpilogmcp.config.PullConfig;
import org.triplehelix.wpilogmcp.ssh.JschConnection;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.store.StoreRegistry;

/** An operator's explicit inspection uses the puller's credentials and pins, never a remote shell. */
public final class RobotFactsCommand {
  private RobotFactsCommand() {}
  public static final String USAGE = "wpilog-mcp robot-facts <host> [--user lvuser] [--port 22] [--key <path>] [--out <file>]"
      + " | robot-facts --server <name> [--config <file>] [--out <file>]";
  record Options(String host, String server, Path config, String user, int port, Path key, Path output) {}

  static Options parse(String[] args) {
    String host = null;
    var values = new LinkedHashMap<String, String>();
    for (int i = 1; i < args.length; i++) {
      String key = args[i];
      if (!key.startsWith("--") && host == null) { host = key; continue; }
      if (!Set.of("--server", "--config", "--user", "--port", "--key", "--out").contains(key)
          || i + 1 == args.length || values.putIfAbsent(key, args[++i]) != null)
        throw new IllegalArgumentException(USAGE);
    }
    String server = values.get("--server");
    if ((host == null) == (server == null)) throw new IllegalArgumentException("Choose a host or --server. " + USAGE);
    if (server != null && (values.containsKey("--user") || values.containsKey("--port") || values.containsKey("--key")))
      throw new IllegalArgumentException("--server uses capture.pull.ssh; do not combine it with authentication flags");
    if (host != null && values.containsKey("--config")) throw new IllegalArgumentException("--config requires --server");
    if (host != null && (!host.matches("[A-Za-z0-9._:%\\[\\]-]+") || host.startsWith("-")))
      throw new IllegalArgumentException("robot-facts requires a host name or IP address, without credentials or a URL");
    String user = values.getOrDefault("--user", "lvuser");
    if (user.isBlank() || user.chars().anyMatch(c -> c < 32)) throw new IllegalArgumentException("--user must be a nonempty account name");
    int port;
    try { port = Integer.parseInt(values.getOrDefault("--port", "22")); }
    catch (NumberFormatException e) { throw new IllegalArgumentException("--port must be an integer from 1 through 65535"); }
    if (port < 1 || port > 65535) throw new IllegalArgumentException("--port must be an integer from 1 through 65535");
    return new Options(host, server, path(values.get("--config")), user, port, path(values.get("--key")), path(values.get("--out")));
  }
  private static Path path(String value) {
    if (value == null) return null;
    try { return Path.of(value).toAbsolutePath().normalize(); }
    catch (java.nio.file.InvalidPathException e) { throw new IllegalArgumentException("Invalid local file path"); }
  }
  public static int run(String[] args, PrintStream out, PrintStream err) {
    return run(args, out, err, Path.of(System.getProperty("user.home")), Clock.systemUTC());
  }
  static int run(String[] args, PrintStream out, PrintStream err, Path home, Clock clock) {
    UnaryOperator<String> redact = RobotFactsReport.redactor(PullConfig.DISABLED.ssh());
    StoreRegistry stores = null;
    try {
      var options = parse(args);
      PullConfig pull;
      List<String> hosts;
      Path storePath;
      if (options.server() != null) {
        var capture = new ConfigLoader().load(options.server(), options.config()).capture();
        if (capture == null) throw new IllegalArgumentException("The named server must configure capture.robot and capture.pull.ssh");
        pull = capture.pull(); hosts = capture.addresses().stream().map(java.net.URI::getHost).distinct().toList();
        storePath = capture.store();
      } else {
        pull = new PullConfig(false, PullConfig.DISABLED.directories(), 0, 1_000_000,
            new PullConfig.Ssh(options.user(), "", options.key(), false, options.port()));
        hosts = List.of(options.host()); storePath = home.resolve(".wpilog-mcp/robot-facts");
      }
      redact = RobotFactsReport.redactor(pull.ssh());
      Path output = options.output() != null ? options.output() : Path.of("robot-facts-"
          + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(clock.instant()) + ".md").toAbsolutePath();
      if (Files.exists(output)) throw new IOException("Report already exists; choose a new --out file");
      var security = new SecurityValidator(); security.addAllowedDirectory(storePath);
      stores = new StoreRegistry(security);
      var store = stores.store(storePath);
      IOException last = null;
      for (String host : hosts) {
        String pin = store.hostKey(host).get(30, TimeUnit.SECONDS);
        JschConnection connection;
        try { connection = JschConnection.connect(host, pull.ssh(), pin); }
        catch (IOException e) { last = e; continue; }
        try (connection) {
          store.recordHostKey(host, connection.fingerprint()).get(30, TimeUnit.SECONDS);
          var report = RobotFacts.collect(connection, host, pull, clock);
          Files.writeString(output, RobotFactsReport.markdown(report, redact), StandardOpenOption.CREATE_NEW);
          out.println("Robot facts written to " + redact.apply(output.toString()));
          return 0;
        }
      }
      throw last != null ? last : new IOException("No configured robot address");
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      err.println("Cannot collect robot facts: " + redact.apply(e.getMessage())); return 1;
    } finally { if (stores != null) stores.close(); }
  }
}
