/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.store.StoreManifest.Header;

/**
 * A sync owns its journal across network waits without owning the store worker. The permanent
 * lock inode excludes a second process; catalog witnesses are prepared off-owner and rechecked
 * before mutations, so releasing store.lock cannot silently admit stale placement decisions.
 */
final class SyncPublication implements AutoCloseable {
  static final String LOCK_NAME = ".owner.lock";
  final LogStore store;
  final StoreFiles files;
  private final String storeId;
  private final FileChannel ownership;

  SyncPublication(LogStore store, StoreFiles files, String storeId) throws IOException {
    this.store = store; this.files = files; this.storeId = storeId;
    var directory = files.check(store.root().resolve(".sync"));
    Files.createDirectories(directory);
    var marker = files.check(directory.resolve(LOCK_NAME));
    if (Files.isSymbolicLink(marker)) throw new IOException("Sync ownership lock cannot be a symbolic link");
    ownership = FileChannel.open(marker, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS);
    try {
      if (ownership.tryLock() == null) throw new IOException("A sync is already running for this store");
    } catch (OverlappingFileLockException held) {
      ownership.close();
      throw new IOException("A sync is already running for this store", held);
    } catch (IOException | RuntimeException failure) {
      ownership.close(); throw failure;
    }
  }

  record Catalog(StoreCatalog.Snapshot snapshot, Map<Path, FileSnapshot> stamps) {
    void check() throws IOException {
      for (var entry : stamps.entrySet()) {
        if (!Objects.equals(entry.getValue(), FileSnapshot.of(entry.getKey())))
          throw new Changed();
      }
    }
  }

  static final class Changed extends IOException {
    Changed() { super("Store metadata changed after sync preparation; retry"); }
  }

  Catalog catalog() throws IOException {
    for (int attempt = 0; attempt < 3; attempt++) {
      var before = store.syncStamps();
      var snapshot = StoreCatalog.readManaged(store.root(), store.replicaSecurity());
      var after = store.syncStamps();
      if (before.equals(after)) return new Catalog(snapshot, after);
    }
    throw new IOException("Store metadata changed during sync preparation; retry");
  }

  <T> T turn(LogStore.CaptureOperation<T> operation) throws IOException {
    return store.syncTurn(owned -> {
      var header = owned.read(store.root().resolve("store.json"), Header.class);
      if (!storeId.equals(header.id())) throw new IOException("Store identity changed during sync");
      if (header.mirror()) throw new IOException("A mirror is written only by its mirror synchronization");
      return operation.run(owned);
    });
  }

  <T> T turn(Catalog catalog, LogStore.CaptureOperation<T> operation) throws IOException {
    return turn(owned -> { catalog.check(); return operation.run(owned); });
  }

  @Override public void close() throws IOException { ownership.close(); }
}
