/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/**
 * Publish complete batches by rename. A separate lock, created before the hidden directory,
 * lets the watcher distinguish an abandoned transfer from a slow copy, even across processes.
 * The lock stays outside the renamed directory so Windows need not rename an open file.
 */
final class InboxTransfer {
  private InboxTransfer() {
  }

  @FunctionalInterface
  interface Copier {
    void copy(Path source, Path destination) throws IOException;
  }

  static List<Path> stage(Path root, List<Path> sources, boolean move, String robot,
      SecurityValidator security, PrintStream out) throws IOException {
    return stage(root, sources, move, robot, security, out,
        (source, destination) -> Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES));
  }

  static List<Path> stage(Path root, List<Path> sources, boolean move, String robot,
      SecurityValidator security, PrintStream out, Copier copier) throws IOException {
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
      } else {
        files = List.of(real);
      }
      String id = UUID.randomUUID().toString();
      var transfer = io.check(inbox.resolve(".transfer-" + id));
      var marker = io.check(inbox.resolve(".transfer-" + id + ".lock"));
      var batch = io.check(inbox.resolve("batch-" + id));
      var snapshots = new HashMap<Path, FileSnapshot>();
      var hashes = new HashMap<Path, String>();
      try (var channel = FileChannel.open(marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
           var lock = channel.lock()) {
        Files.createDirectory(transfer);
        for (var file : files) {
          local.validate(file);
          snapshots.put(file, FileSnapshot.of(file));
          var relative = Files.isDirectory(real) ? real.relativize(file) : file.getFileName();
          // Reserve batch.json for metadata; never silently replace a user's file with it.
          if (relative.equals(Path.of("batch.json"))) throw new IOException("batch.json is reserved for inbox metadata");
          var destination = io.check(transfer.resolve(relative));
          Files.createDirectories(destination.getParent());
          io.check(destination);
          copier.copy(file, destination);
          String hash = StoreFiles.hash(file);
          hashes.put(file, hash);
          if (!hash.equals(StoreFiles.hash(destination)) || !snapshots.get(file).sameAs(FileSnapshot.of(file))) {
            throw new IOException("Inbox transfer verification failed; the next poll will remove the incomplete transfer");
          }
        }
        if (robot != null) {
          StoreFiles.robotName(robot);
          Files.writeString(transfer.resolve("batch.json"), StoreJson.JSON.toJson(Map.of("stated_robot", robot)));
        }
        Files.move(transfer, batch, StandardCopyOption.ATOMIC_MOVE);
      }
      Files.delete(marker);
      for (var file : files) {
        var relative = Files.isDirectory(real) ? real.relativize(file) : file.getFileName();
        var destination = batch.resolve(relative);
        if (move) {
          local.validate(file);
          if (!snapshots.get(file).sameAs(FileSnapshot.of(file)) || !hashes.get(file).equals(StoreFiles.hash(file))) {
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
