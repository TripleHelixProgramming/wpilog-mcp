/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The person's temporary permissions, shared by all clients, but owned by live MCP sessions.
 * SessionManager serializes changes with session removal; this class never performs I/O.
 * Keys stay here in memory, separate from every directory description and tool result.
 */
public final class ClientLeases {
  private static final ClientLeases INSTANCE = new ClientLeases();

  public record Directory(Path path, Integer team) {}

  private record Directories(List<Directory> values, long order) {}
  private record Key(String value, long order) {}

  private final ConcurrentHashMap<String, Directories> directories = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Key> keys = new ConcurrentHashMap<>();
  private final AtomicLong sequence = new AtomicLong();
  private final AtomicInteger transports = new AtomicInteger();
  private volatile boolean used;

  public static ClientLeases getInstance() {
    return INSTANCE;
  }

  public void transportOpened() {
    transports.incrementAndGet();
  }

  public void transportClosed() {
    if (transports.decrementAndGet() == 0) {
      used = false;
    }
  }

  /** After the last lease ends, an empty allowlist must mean no access, not legacy unrestricted access. */
  public boolean enforcesDirectories() {
    return used;
  }

  public void replaceDirectories(String session, List<Directory> values) {
    used = true;
    directories.put(session, new Directories(List.copyOf(values), sequence.incrementAndGet()));
  }

  public void removeDirectories(String session) {
    directories.remove(session);
  }

  /** Oldest first: a newer lease wins equal-path team conflicts; a more specific path wins otherwise. */
  public List<Directory> directories() {
    return directories.values().stream().sorted(Comparator.comparingLong(Directories::order))
        .flatMap(lease -> lease.values().stream()).toList();
  }

  public Integer teamFor(Path file) {
    Directory selected = null;
    for (var directory : directories()) {
      if (directory.team() != null && file.startsWith(directory.path())
          && (selected == null || directory.path().getNameCount() >= selected.path().getNameCount())) {
        selected = directory;
      }
    }
    return selected == null ? null : selected.team();
  }

  /** Null clears this session's key. The most recently registered live key overrides the file's. */
  public void registerKey(String session, String value) {
    if (value == null) {
      keys.remove(session);
    } else {
      keys.put(session, new Key(value, sequence.incrementAndGet()));
    }
  }

  public String keyOr(String configured) {
    return keys.values().stream().max(Comparator.comparingLong(Key::order))
        .map(Key::value).orElse(configured);
  }

  public void remove(String session) {
    removeDirectories(session);
    keys.remove(session);
  }
}
