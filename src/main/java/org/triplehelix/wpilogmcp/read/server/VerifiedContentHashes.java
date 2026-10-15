/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.server;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.store.LogStore;

/** Successful content proofs belong to an admission owner, not to a mapped log or one request.
 * Only stable path/boundary/manifest-hash tuples are remembered. Callers still authorize paths
 * and validate their opened snapshot: this memory grants no access and cannot detect an edit
 * that preserves every observed filesystem attribute. Cold concurrent misses may hash twice;
 * no file I/O runs under the memory's lock or a cache computation lock. */
public final class VerifiedContentHashes implements AutoCloseable {
  public static final int MAX_ENTRIES = 256;
  public static final Duration IDLE_LIFETIME = Duration.ofMinutes(10);
  @FunctionalInterface public interface Hasher { String hash(Path path) throws IOException; }
  private record Key(Path path, FileSnapshot boundary, String expected) {}
  private final Hasher hasher;
  private final Cache<Key, Boolean> verified;
  private boolean closed;

  public VerifiedContentHashes() { this(LogStore::contentHash); }
  public VerifiedContentHashes(Hasher hasher) { this(MAX_ENTRIES, IDLE_LIFETIME, Ticker.systemTicker(), hasher); }
  VerifiedContentHashes(int limit, Duration lifetime, Ticker ticker, Hasher hasher) {
    this.hasher = Objects.requireNonNull(hasher);
    verified = Caffeine.newBuilder().maximumSize(limit).expireAfterAccess(lifetime).ticker(ticker).build();
  }

  /** The path must already have passed the caller's current catalog and access checks. */
  public void verify(Path path, FileSnapshot boundary, String expected) throws IOException {
    if (boundary == null || expected == null) throw mismatch();
    var key = new Key(path, boundary, expected);
    boolean remembered;
    synchronized (this) {
      if (closed) throw new IOException("SOURCE_UNAVAILABLE: content verification owner is closed");
      remembered = verified.getIfPresent(key) != null;
    }
    unchanged(path, boundary);
    if (!remembered && !expected.equals(hasher.hash(path))) throw mismatch();
    unchanged(path, boundary);
    if (!remembered) synchronized (this) {
      // Close lets in-flight admission finish, but must not be followed by cache resurrection.
      if (!closed) { verified.put(key, true); verified.cleanUp(); }
    }
  }
  private static IOException mismatch() {
    return new IOException("SOURCE_UNAVAILABLE: recorded bytes do not match their manifested hash");
  }
  private static void unchanged(Path path, FileSnapshot boundary) throws IOException {
    if (!Objects.equals(boundary, FileSnapshot.of(path)))
      throw new IOException("SOURCE_UNAVAILABLE: recorded file changed during content verification");
  }
  synchronized long retained() { verified.cleanUp(); return verified.estimatedSize(); }
  @Override public synchronized void close() {
    closed = true; verified.invalidateAll(); verified.cleanUp();
  }
}
