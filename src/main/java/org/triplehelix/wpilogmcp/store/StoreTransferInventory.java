/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.triplehelix.wpilogmcp.store.StoreManifest.LogFile;
import org.triplehelix.wpilogmcp.store.StoreManifest.Session;
import org.triplehelix.wpilogmcp.sync.PullManifest;

/** Journal membership is a read-only fact. Keeping its records and interpretation here lets
 * catalog readers exclude staging bytes without loading transfer workers or acquiring write locks. */
final class StoreTransferInventory {
  private StoreTransferInventory() {}
  record Placement(String staged, String destination, String manifest, LogFile file, boolean committed) {}
  record Merge(String sourceDirectory, String destinationDirectory, String manifest,
      Session session, String movedAt, boolean committed) {}
  static String peerDirectory(String id) { return "peer-" + StoreFiles.component(id); }
  static Path mirrorJournal(Path root) { return root.resolve(".mirror/pull.json"); }

  static List<Path> peerFiles(Path root, StoreFiles io) throws IOException {
    var result = new ArrayList<Path>(); var directory = io.check(root.resolve(".sync"));
    if (!Files.isDirectory(directory)) return List.of();
    try (var peers = Files.list(directory)) {
      for (var peer : peers.toList()) {
        if (peer.getFileName().toString().equals("merges")) {
          try (var receipts = Files.list(peer)) {
            for (var receipt : receipts.toList()) { io.read(receipt, Merge.class); result.add(io.check(receipt)); }
          }
          continue;
        }
        result.addAll(systemFiles(root, io, peer.resolve("system")));
        var journal = io.check(peer.resolve("pull.json"));
        if (!Files.isRegularFile(journal)) continue;
        var progress = io.read(journal, PullManifest.class);
        if (!peer.getFileName().toString().equals(peerDirectory(progress.serialNumber()))) throw new IOException("Peer journal id differs from its directory");
        result.add(journal);
        for (var entry : java.util.stream.Stream.concat(progress.files().stream(), progress.history().stream()).distinct().toList()) {
          var held = io.resolve(root, entry.localName());
          if (held.startsWith(peer.resolve("files")) && Files.isRegularFile(held)) result.add(held);
        }
        var receipt = io.check(peer.resolve("placing.json"));
        if (Files.isRegularFile(receipt)) {
          var pending = io.read(receipt, Placement.class); result.add(receipt);
          var staged = io.resolve(root, pending.staged());
          if (staged.startsWith(peer.resolve("files")) && Files.isRegularFile(staged)) result.add(staged);
          if (!pending.committed()) result.add(io.resolve(root, pending.destination()));
        }
      }
    }
    return List.copyOf(result);
  }
  static Set<Path> mirrorFiles(Path root, StoreFiles io) throws IOException {
    var path = mirrorJournal(root); if (!Files.exists(path)) return Set.of();
    var result = new HashSet<Path>(); result.add(path);
    if (Files.exists(root.resolve(".mirror/pending.json"))) result.add(root.resolve(".mirror/pending.json"));
    var journal = io.read(path, PullManifest.class);
    for (var entry : java.util.stream.Stream.concat(journal.files().stream(), journal.history().stream()).toList()) result.add(io.resolve(root, entry.localName()));
    return result;
  }
  static List<Path> systemFiles(Path root, StoreFiles io, Path directory) throws IOException {
    var path = io.check(directory.resolve("pull.json")); if (!Files.exists(path)) return List.of();
    var result = new ArrayList<Path>(); result.add(path); var journal = io.read(path, PullManifest.class);
    for (var entry : java.util.stream.Stream.concat(journal.files().stream(), journal.history().stream()).toList()) {
      var file = io.resolve(root, entry.localName()); if (!file.startsWith(directory.resolve("files"))) throw new IOException("Invalid system transfer journal path"); result.add(file);
    }
    if (Files.exists(directory.resolve("placing.json"))) result.add(io.check(directory.resolve("placing.json")));
    return result;
  }
}
