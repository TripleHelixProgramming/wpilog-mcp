/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/**
 * Polling works on network drives where WatchService misses writes. A refusal is retried only
 * after the file changes (or the server restarts), so an unsupported file cannot flood the log.
 * Only imported.log is written here; file placement belongs to the store's existing queue.
 */
public final class StoreInbox {
  static final Duration SETTLE_TIME = Duration.ofSeconds(3);
  public record Entry(Path path, long size, String state, String reason) {}
  private record Stamp(long size, FileTime modified) {}
  private record Seen(Stamp stamp, long at, String state, String reason) {}
  private record Receipt(String at, Path originalPath, String status, Path path, String reason) {}

  private final LogStore store;
  private final SecurityValidator security;
  private final ConcurrentHashMap<Path, Seen> seen = new ConcurrentHashMap<>();

  StoreInbox(LogStore store, SecurityValidator security) {
    this.store = store;
    this.security = security;
  }

  /** Read current sizes without changing the stability observations used by the poller. */
  public List<Entry> listing() throws IOException {
    var entries = new ArrayList<Entry>();
    for (var file : files().entrySet()) {
      var previous = seen.get(file.getKey());
      boolean same = previous != null && previous.stamp().equals(file.getValue());
      entries.add(new Entry(file.getKey(), file.getValue().size(),
          same ? previous.state() : "waiting", same ? previous.reason() : null));
    }
    return List.copyOf(entries);
  }

  /** Called by one polling thread, with monotonic time; completion runs on the import queue. */
  void poll(long now) throws IOException {
    var current = files();
    seen.keySet().removeIf(path -> !current.containsKey(path));
    var ready = new ArrayList<Path>();
    for (var file : current.entrySet()) {
      var path = file.getKey();
      var stamp = file.getValue();
      var previous = seen.get(path);
      if (previous != null && previous.state().equals("importing")) continue;
      if (previous == null || !previous.stamp().equals(stamp)) {
        seen.put(path, new Seen(stamp, now, "waiting", null));
      } else if (previous.state().equals("waiting") && now - previous.at() >= SETTLE_TIME.toNanos()) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
          finish(new LogStore.Outcome(path, "refused", null,
              "Inbox accepts regular files, not symbolic links or special files"));
          continue;
        }
        seen.put(path, new Seen(stamp, now, "importing", null));
        ready.add(path);
      }
    }
    if (ready.isEmpty()) return;
    observe(store.importPrepared(new LogStore.Request(ready, true, null), p -> {}, request -> {
      var stable = new ArrayList<Path>();
      for (var path : request.paths()) {
        var old = seen.get(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { seen.remove(path); continue; }
        var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        var stamp = new Stamp(attrs.size(), attrs.lastModifiedTime());
        if (old != null && old.stamp().equals(stamp) && attrs.isRegularFile()) stable.add(path);
        else seen.put(path, new Seen(stamp, System.nanoTime(), "waiting", null));
      }
      return new LogStore.Request(stable, true, null);
    }, result -> result.files().forEach(this::finish)), ready);
  }

  /** The offline command uses the same receipts when it consumes its own inbox transfer. */
  public java.util.concurrent.CompletableFuture<LogStore.Result> importReady(List<Path> ready,
      String robot, java.util.function.Consumer<LogStore.Progress> progress) {
    return observe(store.importPrepared(new LogStore.Request(ready, true, robot), progress,
        r -> r, result -> result.files().forEach(this::finish)), ready);
  }

  private java.util.concurrent.CompletableFuture<LogStore.Result> observe(
      java.util.concurrent.CompletableFuture<LogStore.Result> future, List<Path> ready) {
    return future.whenComplete((result, error) -> {
      if (error != null) {
        for (var path : ready) finish(new LogStore.Outcome(path, "refused", null, error.getMessage()));
      }
    });
  }

  private void finish(LogStore.Outcome outcome) {
    var path = outcome.originalPath();
    String reason = outcome.reason();
    if (outcome.status().equals("present")) {
      reason = "Already held at " + outcome.path() + "; duplicate left in inbox";
    }
    var receipt = new Receipt(Instant.now().toString(), path, outcome.status(), outcome.path(), reason);
    try {
      var io = new StoreFiles(store.root(), security);
      // One JSON object per line: filenames and refusal text cannot forge another receipt.
      Files.writeString(io.check(store.root().resolve("inbox").resolve("imported.log")),
          StoreJson.JSON.toJson(receipt) + System.lineSeparator(),
          StandardOpenOption.CREATE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
    } catch (IOException e) {
      LoggerFactory.getLogger(StoreInbox.class).warn("Inbox receipt could not be written");
      reason = (reason == null ? "" : reason + "; ") + "Could not write inbox/imported.log";
    }
    final String finalReason = reason;
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      seen.computeIfPresent(path, (p, old) -> new Seen(old.stamp(), old.at(), "refused", finalReason));
    } else {
      seen.remove(path);
    }
  }

  private Map<Path, Stamp> files() throws IOException {
    var io = new StoreFiles(store.root(), security);
    var inbox = io.check(store.root().resolve("inbox"));
    var files = new java.util.LinkedHashMap<Path, Stamp>();
    if (!Files.isDirectory(inbox)) return files;
    try (var walk = Files.walk(inbox)) {
      for (var path : walk.filter(p -> !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
        if (path.equals(inbox.resolve("imported.log"))) continue;
        try {
          var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
          files.put(path, new Stamp(attrs.size(), attrs.lastModifiedTime()));
        } catch (java.nio.file.NoSuchFileException e) {
          // The import moved it between the directory scan and this stat.
        }
      }
    }
    return files;
  }
}
