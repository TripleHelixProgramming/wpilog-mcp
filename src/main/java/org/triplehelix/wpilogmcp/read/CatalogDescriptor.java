/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.util.List;
import org.triplehelix.wpilogmcp.store.StoreManifest;
import org.triplehelix.wpilogmcp.store.SystemLogIndex;

/** Transport-neutral catalog facts. Paths stay strings: a remote Windows path is not a Unix path.
 * Manifest records retain their existing owner; this envelope supplies no new placement policy. */
public record CatalogDescriptor(List<Store> stores, List<Problem> problems, List<SharedSystemLogs> sharedSystemLogs) {
  public CatalogDescriptor { stores = List.copyOf(stores); problems = List.copyOf(problems); sharedSystemLogs = List.copyOf(sharedSystemLogs); }
  public record Problem(String manifest, String reason) {}
  public record Robot(String path, StoreManifest.Robot robot) {}
  public record Session(String path, StoreManifest.Robot robot, StoreManifest.Session session) {}
  public record File(String path, String manifestPath, StoreManifest.Robot robot,
      StoreManifest.Session session, StoreManifest.LogFile file) {}
  public record Membership(String storeId, String root, File facts) {}
  public record SharedSystemLogs(String storeId, String robotId, String index, SystemLogIndex inventory) {}
  public record Store(String root, StoreManifest.Header header, List<Robot> robots, List<File> files,
      List<String> unmanaged, List<StoreManifest.Move> moved, List<File> openCaptures, List<Session> sessions) {
    public Store {
      robots = List.copyOf(robots); files = List.copyOf(files); unmanaged = List.copyOf(unmanaged);
      moved = List.copyOf(moved); openCaptures = List.copyOf(openCaptures); sessions = List.copyOf(sessions);
    }
  }
}
