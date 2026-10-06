/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.capture.context.DeviceIdentity;
import org.triplehelix.wpilogmcp.log.LogFileAccess;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

/** Identity writes and moves share the store queue; a host key change never changes a serial. */
final class RobotIdentityStore {
  private RobotIdentityStore() {}

  static Robot record(StoreFiles io, Path root, DeviceIdentity device, Instant now) throws IOException {
    String serial = StoreFiles.component(device.serialNumber());
    var path = io.check(root.resolve("robots").resolve(serial).resolve("robot.json"));
    var old = Files.exists(path) ? io.read(path, Robot.class) : null;
    var contacts = new ArrayList<>(old == null ? List.<Contact>of() : old.contacts());
    var previous = contacts.stream().filter(c -> c.address().equals(device.address())).reduce((a, b) -> b);
    if (previous.isEmpty() || !previous.get().hostKeyFingerprint().equals(device.hostKeyFingerprint())) {
      previous.ifPresent(c -> LoggerFactory.getLogger(RobotIdentityStore.class).warn(
          "SSH host key changed for robot {} at {}: {} -> {}; continuing its pull manifest",
          serial, device.address(), c.hostKeyFingerprint(), device.hostKeyFingerprint()));
      contacts.add(new Contact(device.address(), device.hostKeyFingerprint(), now.toString()));
    }
    var robot = new Robot(serial, serial, old == null ? null : old.name(), device.comments(), "device", contacts);
    io.write(path, robot);
    var header = io.read(root.resolve("store.json"), Header.class);
    var addresses = new LinkedHashMap<>(header.addresses()); addresses.put(device.address(), serial);
    io.write(root.resolve("store.json"), new Header(header.formatVersion(), header.createdAt(), header.id(), header.moves(), addresses));
    return robot;
  }

  static String addressId(String address) {
    return "address-" + java.net.URLEncoder.encode(address, java.nio.charset.StandardCharsets.UTF_8);
  }

  static Path promote(StoreFiles io, Path root, LogManager manager, Path current,
      DeviceIdentity device, Instant now) throws IOException {
    Path result = current;
    var placeholder = io.check(root.resolve("robots").resolve(addressId(device.address())));
    var sessions = new ArrayList<Path>();
    if (Files.isDirectory(placeholder)) {
      try (var walk = Files.walk(placeholder)) {
        for (var manifest : walk.filter(p -> p.getFileName().toString().equals("session.json")).sorted().toList()) {
          // Another live writer keeps its stable directory until its own file creation barrier.
          if (io.read(manifest, Session.class).openCapture() == null) sessions.add(manifest.getParent());
        }
      }
    }
    if (current != null && !sessions.contains(current.getParent())) sessions.add(current.getParent());
    for (var old : sessions) {
      var robotRoot = root.resolve("robots").resolve(StoreFiles.component(device.serialNumber()));
      if (old.startsWith(robotRoot)) continue;
      var parent = io.check(robotRoot.resolve("sessions").resolve(old.getParent().getFileName()));
      Files.createDirectories(parent);
      var target = parent.resolve(old.getFileName()); int suffix = 2;
      while (Files.exists(target)) target = parent.resolve(old.getFileName() + "_" + suffix++);
      io.check(old); io.check(target);
      var session = io.read(old.resolve("session.json"), Session.class);
      if (session.openCapture() != null) throw new IOException("Cannot relocate an open capture: " + old);
      try (var reservation = LogFileAccess.reserveMove(List.of(old))) {
        if (!manager.release(old).released()) continue;
        try { Files.move(old, target); }
        catch (java.nio.file.FileSystemException e) {
          LoggerFactory.getLogger(RobotIdentityStore.class).warn("Robot identity move deferred for {}: {}", old, e.getMessage());
          continue;
        }
      }
      var header = io.read(root.resolve("store.json"), Header.class);
      var moves = new ArrayList<Move>();
      for (var move : header.moves()) {
        var before = io.resolve(root, move.movedTo());
        moves.add(new Move(move.originalPath(), StoreFiles.relative(root,
            before.startsWith(old) ? target.resolve(old.relativize(before)) : before), move.movedAt()));
      }
      for (var file : session.files()) moves.add(new Move(old.resolve(file.path()).toString(),
          StoreFiles.relative(root, io.resolve(target, file.path())), now.toString()));
      io.write(root.resolve("store.json"), new Header(header.formatVersion(), header.createdAt(), header.id(), moves, header.addresses()));
      if (current != null && current.startsWith(old)) result = target.resolve(old.relativize(current));
    }
    // Remove only our empty placeholder metadata/directories. Strays are reported and retained.
    if (Files.isDirectory(placeholder)) {
      try (var walk = Files.walk(placeholder)) {
        for (var path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
          if (!Files.isDirectory(path)) continue;
          List<Path> contents;
          try (var children = Files.list(path)) { contents = children.toList(); }
          if (path.equals(placeholder) && contents.equals(List.of(path.resolve("robot.json")))) {
            Files.delete(io.check(contents.get(0))); Files.delete(path);
          } else if (contents.isEmpty()) Files.delete(io.check(path));
        }
      }
    }
    return result;
  }
}
