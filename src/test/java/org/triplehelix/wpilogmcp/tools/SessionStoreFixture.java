/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.store.StoreJson;
import org.triplehelix.wpilogmcp.store.StoreManifest;
import org.triplehelix.wpilogmcp.store.StoreManifest.Header;
import org.triplehelix.wpilogmcp.store.StoreManifest.LogFile;
import org.triplehelix.wpilogmcp.store.StoreManifest.MirrorOrigin;
import org.triplehelix.wpilogmcp.store.StoreManifest.MirrorSession;
import org.triplehelix.wpilogmcp.store.StoreManifest.OpenCapture;
import org.triplehelix.wpilogmcp.store.StoreManifest.Provenance;
import org.triplehelix.wpilogmcp.store.StoreManifest.Robot;
import org.triplehelix.wpilogmcp.store.StoreManifest.Session;

/**
 * Hand-written stores whose sessions hold synthetic WPILOG members. Every value a test checks is
 * written here from a formula the test also states, so expected answers never come from the
 * server. Manifests carry each member's true hash, size and data time range.
 */
public final class SessionStoreFixture {
  public static final String WHEN = "2026-01-01T00:00:00Z";

  private SessionStoreFixture() {}

  @FunctionalInterface public interface Content { void write(WpilogWriter writer) throws IOException; }

  public record Store(Path root, String id) {}

  private record Member(String name, double minSec, double maxSec, Content content) {}

  /** One session: its members, an optional open capture, aliases and the robot it belongs to. */
  public static final class SessionSpec {
    final String id;
    String serial = "SYNTHETIC-A";
    String event;
    final List<String> aliases = new ArrayList<>();
    final List<Member> members = new ArrayList<>();
    Member open;

    public SessionSpec(String id) { this.id = id; }
    public SessionSpec member(String name, double minSec, double maxSec, Content content) {
      members.add(new Member(name, minSec, maxSec, content)); return this;
    }
    /** The session is still recording into this member. */
    public SessionSpec open(String name, double minSec, double maxSec, Content content) {
      open = new Member(name, minSec, maxSec, content); return this;
    }
    public SessionSpec serial(String value) { serial = value; return this; }
    public SessionSpec event(String value) { event = value; return this; }
    public SessionSpec alias(String value) { aliases.add(value); return this; }
  }

  public static Store store(Path parent, String name, String id) throws IOException {
    var root = Files.createDirectories(parent.resolve(name)).toRealPath();
    write(root.resolve("store.json"), new Header(StoreManifest.FORMAT_VERSION, WHEN, id, List.of()));
    return new Store(root, id);
  }

  /** A mirror without a replica catalog lists every manifested member, as a legacy mirror does. */
  public static Store mirror(Path parent, String name, String id, Map<String, MirrorSession> origin) throws IOException {
    var root = Files.createDirectories(parent.resolve(name)).toRealPath();
    write(root.resolve("store.json"), new Header(StoreManifest.FORMAT_VERSION, WHEN, id, List.of())
        .withOrigin(new MirrorOrigin("origin-" + id, "http://origin.invalid", WHEN, List.of(), origin)));
    return new Store(root, id);
  }

  /** Writes the session's members and manifest; returns the session directory. */
  public static Path write(Store store, SessionSpec spec) throws Exception {
    var robot = store.root().resolve("robots").resolve(spec.serial);
    Files.createDirectories(robot);
    if (!Files.exists(robot.resolve("robot.json")))
      write(robot.resolve("robot.json"), new Robot(spec.serial, spec.serial, "Synthetic", "fixture", "logged"));
    var directory = Files.createDirectories(robot.resolve("sessions/2026-01-01").resolve(spec.id));
    var files = new ArrayList<LogFile>();
    for (var member : spec.members) {
      var path = directory.resolve(member.name());
      try (var writer = new WpilogWriter(path, "compare_sessions fixture")) { member.content().write(writer); }
      files.add(file(path, member.minSec(), member.maxSec()));
    }
    OpenCapture open = null;
    if (spec.open != null) {
      var path = directory.resolve(spec.open.name());
      try (var writer = new WpilogWriter(path, "compare_sessions fixture")) { spec.open.content().write(writer); }
      open = new OpenCapture(spec.open.name(), new Provenance("captured", null, null, null, false),
          Files.size(path), spec.open.minSec(), spec.open.maxSec());
    }
    var session = new Session(spec.id, WHEN, WHEN, "log", spec.event, null, null, null, files, open, null,
        null, List.of(), List.of(), null, null, List.copyOf(spec.aliases), List.of());
    write(directory.resolve("session.json"), session);
    return directory;
  }

  public static LogFile file(Path path, double minSec, double maxSec) throws Exception {
    return new LogFile(path.getFileName().toString(), sha256(path), Files.size(path), "wpilog",
        new Provenance("usb", "/fixture/" + path.getFileName(), path.getFileName().toString(), WHEN, false),
        true, minSec, maxSec, WHEN, WHEN, "log", false, null);
  }

  public static String sha256(Path path) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }

  public static void write(Path path, Object value) throws IOException {
    Files.writeString(path, StoreJson.JSON.toJson(value));
  }

  /** One double entry with one sample per second from zero: values[i] at i seconds. */
  public static Content doubles(String entry, double... values) {
    return writer -> {
      int id = writer.start(entry, "double", "", 0);
      for (int i = 0; i < values.length; i++) writer.append(id, 1_000_000L * i, WpilogWriter.encodeDouble(values[i]));
    };
  }

  /** Several contents in one member. */
  public static Content all(Content... parts) {
    return writer -> { for (var part : parts) part.write(writer); };
  }

  /** A string entry holding the given values at the given seconds. */
  public static Content strings(String entry, double[] seconds, String... values) {
    return writer -> {
      int id = writer.start(entry, "string", "", 0);
      for (int i = 0; i < values.length; i++)
        writer.append(id, Math.round(seconds[i] * 1e6), WpilogWriter.encodeString(values[i]));
    };
  }
}
