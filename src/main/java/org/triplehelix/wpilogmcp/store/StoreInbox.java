/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/**
 * Polling works on network drives where WatchService misses writes. A refusal is retried only
 * after the file or its batch metadata changes, so an unsupported file cannot flood receipts.
 * Hidden transfers are never candidates: only a published batch may acquire a stated robot.
 */
public final class StoreInbox {
  static final Duration SETTLE_TIME = Duration.ofSeconds(3);
  private static final Gson BATCH_JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
  public record Entry(Path path, long size, String state, String reason, String statedRobot) {}
  private record Stamp(long size, FileTime modified) {}
  private record Batch(Path directory, Stamp stamp, String robot, String refusal) {}
  private record Observed(Stamp stamp, Batch batch) {}
  private record Seen(Observed observed, long at, String state, String reason) {}
  private record Receipt(String at, Path originalPath, String status, Path path, String reason) {}

  private final LogStore store;
  private final SecurityValidator security;
  private final ConcurrentHashMap<Path, Seen> seen = new ConcurrentHashMap<>();

  StoreInbox(LogStore store, SecurityValidator security) {
    this.store = store;
    this.security = security;
  }

  /** Reading the tree must not reset the poller's stability clock. */
  public List<Entry> listing() throws IOException {
    var entries = new ArrayList<Entry>();
    for (var file : files().entrySet()) {
      var previous = seen.get(file.getKey());
      var observed = file.getValue();
      boolean same = previous != null && previous.observed().equals(observed);
      entries.add(new Entry(file.getKey(), observed.stamp().size(),
          same ? previous.state() : "waiting", same ? previous.reason() : null, observed.batch().robot()));
    }
    return List.copyOf(entries);
  }

  /** A batch waits together, including its sidecar, so one quiet file cannot outrun its identity. */
  void poll(long now) throws IOException {
    cleanTransfers();
    var current = files();
    seen.keySet().removeIf(path -> !current.containsKey(path));
    var groups = new LinkedHashMap<Batch, List<Path>>();
    for (var file : current.entrySet()) {
      var path = file.getKey();
      var observed = file.getValue();
      var previous = seen.get(path);
      if (previous == null || !previous.state().equals("importing") && !previous.observed().equals(observed)) {
        seen.put(path, new Seen(observed, now, "waiting", null));
      }
      groups.computeIfAbsent(observed.batch(), b -> new ArrayList<>()).add(path);
    }
    for (var group : groups.entrySet()) {
      var ready = group.getValue().stream().filter(path -> {
        var old = seen.get(path);
        return old.state().equals("waiting") && now - old.at() >= SETTLE_TIME.toNanos();
      }).toList();
      if (ready.isEmpty()) continue;
      // Loose files need not wait for unrelated loose drops. A published directory is one batch.
      if (group.getKey().directory() != null && group.getValue().stream().anyMatch(path -> {
        var old = seen.get(path);
        return old.state().equals("importing") || old.state().equals("waiting") && !ready.contains(path);
      })) continue;
      for (var path : ready) {
        var old = seen.get(path);
        seen.put(path, new Seen(old.observed(), old.at(), "importing", null));
      }
      var batch = group.getKey();
      observe(store.importPrepared(new LogStore.Request(ready, true, batch.robot()), p -> {}, request -> {
        var latest = files();
        boolean changed = ready.stream().anyMatch(path -> !current.get(path).equals(latest.get(path)));
        if (changed) {
          for (var path : ready) {
            if (!latest.containsKey(path)) seen.remove(path);
            else seen.put(path, new Seen(latest.get(path), System.nanoTime(), "waiting", null));
          }
          return new LogStore.Request(List.of(), true, batch.robot());
        }
        var stable = new ArrayList<Path>();
        for (var path : ready) {
          String refusal = batch.refusal();
          if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            refusal = "Inbox accepts regular files, not symbolic links or special files";
          }
          if (refusal != null) finish(new LogStore.Outcome(path, "refused", null, refusal));
          else stable.add(path);
        }
        return new LogStore.Request(stable, true, batch.robot());
      }, result -> result.files().forEach(this::finish)), ready);
    }
  }

  /** The offline command consumes its published batch with the same receipts as the daemon. */
  public CompletableFuture<LogStore.Result> importReady(List<Path> ready,
      String robot, Consumer<LogStore.Progress> progress) {
    return observe(store.importPrepared(new LogStore.Request(ready, true, robot), progress,
        r -> r, result -> result.files().forEach(this::finish)), ready);
  }

  private CompletableFuture<LogStore.Result> observe(CompletableFuture<LogStore.Result> future, List<Path> ready) {
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
      seen.computeIfPresent(path, (p, old) -> new Seen(old.observed(), old.at(), "refused", finalReason));
    } else {
      seen.remove(path);
    }
  }

  /** The client alone creates transfer locks; the server only opens them, and writes receipts. */
  private void cleanTransfers() throws IOException {
    var io = new StoreFiles(store.root(), security);
    var inbox = io.check(store.root().resolve("inbox"));
    if (!Files.isDirectory(inbox)) return;
    List<Path> transfers;
    try (var children = Files.list(inbox)) {
      transfers = children.filter(p -> p.getFileName().toString().startsWith(".transfer-"))
          .filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList();
    }
    for (var transfer : transfers) {
      var marker = io.check(inbox.resolve(transfer.getFileName() + ".lock"));
      try (var storeLock = StoreLock.acquire(store.root(), security);
           var channel = Files.exists(marker, LinkOption.NOFOLLOW_LINKS)
               ? FileChannel.open(marker, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS) : null;
           var transferLock = channel == null ? null : channel.tryLock()) {
        if (channel != null && transferLock == null) continue;
        // Files.walk does not follow links. Deleting a link removes it, never its target.
        try (var walk = Files.walk(io.check(transfer))) {
          for (var path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        finish(new LogStore.Outcome(transfer, "removed", null, "Removed incomplete inbox transfer; originals were retained"));
      } catch (OverlappingFileLockException e) {
        continue;
      } catch (IOException e) {
        // An active store writer or an inaccessible transfer can be retried next poll.
        continue;
      }
      Files.deleteIfExists(marker);
    }
  }

  private Batch batch(Path directory, StoreFiles io) throws IOException {
    var sidecar = directory.resolve("batch.json");
    if (!Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) return new Batch(directory, null, null, null);
    var attrs = Files.readAttributes(sidecar, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    var stamp = new Stamp(attrs.size(), attrs.lastModifiedTime());
    try {
      io.check(sidecar);
      if (!attrs.isRegularFile() || attrs.size() > 4096) throw new IllegalArgumentException();
      var json = BATCH_JSON.fromJson(Files.readString(sidecar), JsonObject.class);
      var robot = json.get("stated_robot");
      if (robot == null || !robot.isJsonPrimitive() || !robot.getAsJsonPrimitive().isString()) {
        throw new IllegalArgumentException();
      }
      return new Batch(directory, stamp, StoreFiles.robotName(robot.getAsString()), null);
    } catch (IOException | RuntimeException e) {
      return new Batch(directory, stamp, null, "Invalid inbox batch.json: expected stated_robot with a portable robot name");
    }
  }

  private Map<Path, Observed> files() throws IOException {
    var io = new StoreFiles(store.root(), security);
    var inbox = io.check(store.root().resolve("inbox"));
    var files = new LinkedHashMap<Path, Observed>();
    var batches = new LinkedHashMap<Path, Batch>();
    if (!Files.isDirectory(inbox)) return files;
    Files.walkFileTree(inbox, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        return !dir.equals(inbox) && dir.getFileName().toString().startsWith(".")
            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
        if (path.getFileName().toString().startsWith(".") || path.equals(inbox.resolve("imported.log"))) {
          return FileVisitResult.CONTINUE;
        }
        var relative = inbox.relativize(path);
        var batch = new Batch(null, null, null, null);
        if (relative.getNameCount() > 1) {
          var directory = inbox.resolve(relative.getName(0));
          if (path.equals(directory.resolve("batch.json"))) return FileVisitResult.CONTINUE;
          if (!batches.containsKey(directory)) batches.put(directory, batch(directory, io));
          batch = batches.get(directory);
        }
        files.put(path, new Observed(new Stamp(attrs.size(), attrs.lastModifiedTime()), batch));
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
        if (error instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
        throw error;
      }
    });
    return files;
  }
}
