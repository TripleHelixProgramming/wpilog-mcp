/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.triplehelix.wpilogmcp.config.DaemonManager;
import org.triplehelix.wpilogmcp.config.ServerConfig;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/**
 * A running daemon owns imports. Losing its HTTP connection is an error, never permission to
 * fall back to another writer. Outside sources are local file operations into a user's inbox;
 * they never widen the server's path permissions.
 */
public final class ImportCommand {
  public static final String USAGE = "wpilog-mcp import [--server <name>] [--config <path>] "
      + "[--store <dir>] [--move] [--robot <name>] <path>...";
  public record Options(String server, Path config, Path store, boolean move, String robot,
      List<Path> paths) {}

  private ImportCommand() {}

  public static Options parse(String[] args) {
    String server = "default";
    Path config = null;
    Path store = null;
    String robot = null;
    boolean move = false;
    boolean positional = false;
    var paths = new ArrayList<Path>();
    for (int i = 1; i < args.length; i++) {
      String arg = args[i];
      if (!positional && arg.equals("--")) { positional = true; continue; }
      if (!positional && arg.equals("--move")) { move = true; continue; }
      if (!positional && arg.startsWith("-")) {
        if (!List.of("--server", "--config", "--store", "--robot").contains(arg)
            || i + 1 == args.length || args[i + 1].startsWith("--")) {
          throw new IllegalArgumentException(USAGE);
        }
        String value = args[++i];
        switch (arg) {
          case "--server" -> server = value;
          case "--config" -> config = Path.of(value);
          case "--store" -> store = Path.of(value);
          case "--robot" -> robot = StoreFiles.component(value);
          default -> throw new IllegalArgumentException(USAGE);
        }
      } else {
        paths.add(Path.of(arg).toAbsolutePath().normalize());
      }
    }
    if (paths.isEmpty()) throw new IllegalArgumentException(USAGE);
    return new Options(server, config, store, move, robot, List.copyOf(paths));
  }

  public static int run(Options options, ServerConfig config, DaemonManager daemon, PrintStream out)
      throws Exception {
    var directories = config.logdirs() == null ? List.<Path>of()
        : config.logdirs().stream().map(Path::of).map(p -> p.toAbsolutePath().normalize()).toList();
    if (directories.isEmpty()) throw new IOException("Import requires a configured log directory");
    var security = new SecurityValidator();
    directories.forEach(security::addAllowedDirectory);
    var selected = options.store() != null ? options.store().toAbsolutePath().normalize()
        : directories.stream().filter(StoreCatalog::isStore).findFirst().orElse(directories.get(0));
    security.validate(selected);
    var inside = new ArrayList<Path>();
    var outside = new ArrayList<Path>();
    for (var path : options.paths()) {
      try { security.validate(path); inside.add(path); }
      catch (org.triplehelix.wpilogmcp.log.LogFileException e) { outside.add(path); }
    }
    var running = daemon.runningDaemon(options.server());
    if (running.isPresent()) {
      var base = URI.create("http://127.0.0.1:" + running.get().port());
      out.println("Import through daemon on port " + running.get().port());
      // Even an empty request creates the store under the daemon's lock before an inbox drop.
      int code = remote(base, selected, new LogStore.Request(inside, options.move(), options.robot()), out);
      if (!outside.isEmpty()) {
        if (!StoreCatalog.isStore(selected)) return 1;
        stage(selected.toRealPath(), outside, options.move(), security, out);
        if (options.robot() != null) {
          out.println("--robot applies to direct imports only. Inbox files use logged identity "
              + "and otherwise remain unassigned.");
        }
      }
      return code;
    }
    out.println("No running daemon; importing in this process under store.lock");
    try (var stores = new StoreRegistry(security)) {
      var store = stores.store(selected);
      var result = store.importPaths(new LogStore.Request(inside, options.move(), options.robot()),
          p -> out.println("progress " + StoreJson.JSON.toJson(p))).get();
      out.println("result " + StoreJson.JSON.toJson(result));
      int code = refused(result) ? 1 : 0;
      if (!outside.isEmpty()) {
        var staged = stage(store.root(), outside, options.move(), security, out);
        var imported = store.inbox().importReady(staged, options.robot(),
            p -> out.println("progress " + StoreJson.JSON.toJson(p))).get();
        out.println("result " + StoreJson.JSON.toJson(imported));
        if (refused(imported)) code = 1;
      }
      return code;
    }
  }

  private static boolean refused(LogStore.Result result) {
    return result.files().stream().anyMatch(file -> file.status().equals("refused"));
  }

  private static int remote(URI base, Path store, LogStore.Request request, PrintStream out)
      throws IOException, InterruptedException {
    var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    var body = StoreJson.JSON.toJsonTree(request).getAsJsonObject();
    body.addProperty("store", store.toString());
    var response = client.send(HttpRequest.newBuilder(base.resolve("/store/import"))
        .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 202) throw new IOException("Import HTTP " + response.statusCode() + ": " + response.body());
    var accepted = JsonParser.parseString(response.body()).getAsJsonObject();
    out.println("job " + accepted);
    var poll = base.resolve(accepted.get("url").getAsString());
    if (!base.getAuthority().equals(poll.getAuthority()) || !poll.getPath().startsWith("/store/import/")) {
      throw new IOException("Daemon returned an invalid import job URL");
    }
    String previous = null;
    while (true) {
      var status = client.send(HttpRequest.newBuilder(poll).timeout(Duration.ofSeconds(30)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      if (status.statusCode() != 200) throw new IOException("Import poll HTTP " + status.statusCode() + ": " + status.body());
      var job = JsonParser.parseString(status.body()).getAsJsonObject();
      if (!status.body().equals(previous)) { out.println("progress " + job); previous = status.body(); }
      switch (job.get("state").getAsString()) {
        case "done" -> {
          var result = job.getAsJsonObject("result");
          out.println("result " + result);
          for (var file : result.getAsJsonArray("files")) {
            if (file.getAsJsonObject().get("status").getAsString().equals("refused")) return 1;
          }
          return 0;
        }
        case "failed" -> throw new IOException("Import failed: " + job.get("error").getAsString());
        default -> Thread.sleep(200);
      }
    }
  }

  /** Publish only complete transfers: two quiet polls during a slow copy must not adopt it. */
  private static List<Path> stage(Path root, List<Path> sources, boolean move,
      SecurityValidator security, PrintStream out) throws IOException {
    var io = new StoreFiles(root, security);
    var inbox = io.check(root.resolve("inbox"));
    Files.createDirectories(inbox);
    var staged = new ArrayList<Path>();
    for (var source : sources) {
      var real = source.toRealPath();
      var local = new SecurityValidator();
      local.addAllowedDirectory(Files.isDirectory(real) ? real : real.getParent());
      List<Path> files;
      if (Files.isDirectory(real)) {
        try (var walk = Files.walk(real)) {
          files = walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
        }
      } else { files = List.of(real); }
      var transfer = Files.createTempDirectory(io.check(root), ".inbox-transfer-");
      var batch = io.check(inbox.resolve("batch-" + UUID.randomUUID()));
      var snapshots = new java.util.HashMap<Path, org.triplehelix.wpilogmcp.log.FileSnapshot>();
      var hashes = new java.util.HashMap<Path, String>();
      for (var file : files) {
        local.validate(file);
        snapshots.put(file, org.triplehelix.wpilogmcp.log.FileSnapshot.of(file));
        var relative = Files.isDirectory(real) ? real.relativize(file) : file.getFileName();
        var destination = io.check(transfer.resolve(relative));
        Files.createDirectories(destination.getParent());
        io.check(destination);
        Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES);
        String hash = StoreFiles.hash(file);
        hashes.put(file, hash);
        if (!hash.equals(StoreFiles.hash(destination))
            || !snapshots.get(file).sameAs(org.triplehelix.wpilogmcp.log.FileSnapshot.of(file))) {
          throw new IOException("Inbox transfer verification failed; retained at " + transfer);
        }
      }
      Files.move(transfer, batch, StandardCopyOption.ATOMIC_MOVE);
      for (var file : files) {
        var relative = Files.isDirectory(real) ? real.relativize(file) : file.getFileName();
        var destination = batch.resolve(relative);
        if (move) {
          local.validate(file);
          if (!snapshots.get(file).sameAs(org.triplehelix.wpilogmcp.log.FileSnapshot.of(file))
              || !hashes.get(file).equals(StoreFiles.hash(file))) {
            throw new IOException("Source changed after inbox transfer; original retained: " + file);
          }
          Files.delete(file);
        }
        staged.add(destination);
        out.println((move ? "Moved" : "Copied") + " to inbox: " + destination
            + "; result in " + inbox.resolve("imported.log"));
      }
    }
    return List.copyOf(staged);
  }
}
