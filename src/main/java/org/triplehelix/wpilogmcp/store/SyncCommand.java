/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.triplehelix.wpilogmcp.config.DaemonManager;
import org.triplehelix.wpilogmcp.config.ServerConfig;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.sync.HttpRemoteFiles;

/** One command owns the offline lock or submits to the daemon; a failed daemon is never bypassed. */
public final class SyncCommand {
  private SyncCommand() {}
  public static final String USAGE = "Usage: wpilog-mcp sync [--server <name>] [--config <path>] [--store <dir>] [--rate-bytes <bytes/sec>] [url]";
  public record Options(String server, Path config, Path store, long rateBytes, String url) {}
  public static Options parse(String[] args) {
    String server = "default", url = null; Path config = null, store = null; long rate = 0;
    for (int i = 1; i < args.length; i++) {
      String argument = args[i];
      if (List.of("--server", "--config", "--store", "--rate-bytes").contains(argument)) {
        if (++i == args.length || args[i].isBlank()) throw new IllegalArgumentException(argument + " needs a value. " + USAGE);
        switch (argument) {
          case "--server" -> server = args[i];
          case "--config" -> config = Path.of(args[i]);
          case "--store" -> store = Path.of(args[i]).toAbsolutePath().normalize();
          case "--rate-bytes" -> {
            try { rate = Long.parseLong(args[i]); if (rate < 0) throw new NumberFormatException(); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("--rate-bytes must be nonnegative; 0 means unlimited"); }
          }
        }
      } else {
        if (argument.startsWith("-") || url != null) throw new IllegalArgumentException(USAGE);
        url = new HttpRemoteFiles(argument).url();
      }
    }
    return new Options(server, config, store, rate, url);
  }
  public static int run(Options options, ServerConfig config, DaemonManager daemon, PrintStream out) throws Exception {
    var directories = config.effectiveLogdirs().stream().map(d -> Path.of(d).toAbsolutePath().normalize()).toList();
    if (directories.isEmpty()) throw new IOException("Sync requires a configured log directory");
    var security = new SecurityValidator(); directories.forEach(security::addAllowedDirectory);
    Path selected = options.store() != null ? options.store() : directories.stream().filter(StoreCatalog::isStore).findFirst().orElse(directories.get(0));
    security.validate(selected);
    var running = daemon.runningDaemon(options.server());
    if (running.isPresent()) {
      out.println("Sync through running daemon " + options.server() + " into " + selected);
      return remote(URI.create("http://127.0.0.1:" + running.get().port()), selected, options, out);
    }
    out.println("No running daemon; syncing under store.lock into " + selected);
    var manager = LogManager.getInstance(); var previous = manager.getConfiguredDirectories();
    directories.forEach(manager::addAllowedDirectory);
    try (var stores = new StoreRegistry(security, manager)) {
      var result = stores.store(selected).sync(options.url(), options.rateBytes(), p -> out.println("progress " + StoreJson.JSON.toJson(p))).get();
      out.println("result " + StoreJson.JSON.toJson(result));
      return result.refusals().isEmpty() && result.stopped().isEmpty() ? 0 : 1;
    } finally {
      manager.clearAllowedDirectories(); previous.forEach(manager::addAllowedDirectory);
    }
  }
  private static int remote(URI base, Path store, Options options, PrintStream out) throws IOException, InterruptedException {
    var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    var body = new JsonObject(); body.addProperty("store", store.toString()); body.addProperty("url", options.url()); body.addProperty("rate_bytes", options.rateBytes());
    var accepted = client.send(HttpRequest.newBuilder(base.resolve("/store/sync")).timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
    if (accepted.statusCode() != 202) throw new IOException("Sync HTTP " + accepted.statusCode() + ": " + accepted.body());
    var job = JsonParser.parseString(accepted.body()).getAsJsonObject(); out.println("job " + job);
    var poll = base.resolve(job.get("url").getAsString());
    if (!base.getAuthority().equals(poll.getAuthority()) || !base.getScheme().equals(poll.getScheme()) || !poll.getPath().startsWith("/store/sync/")) {
      throw new IOException("Daemon returned an invalid sync job URL");
    }
    String previous = null;
    while (true) {
      var response = client.send(HttpRequest.newBuilder(poll).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw new IOException("Sync poll HTTP " + response.statusCode() + ": " + response.body());
      var state = JsonParser.parseString(response.body()).getAsJsonObject();
      if (!response.body().equals(previous)) { out.println("progress " + state); previous = response.body(); }
      switch (state.get("state").getAsString()) {
        case "done" -> {
          var result = state.getAsJsonObject("result"); out.println("result " + result);
          return result.getAsJsonArray("refusals").isEmpty() && result.getAsJsonArray("stopped").isEmpty() ? 0 : 1;
        }
        case "failed" -> throw new IOException("Sync failed: " + state.get("error").getAsString());
        default -> Thread.sleep(200);
      }
    }
  }
}
