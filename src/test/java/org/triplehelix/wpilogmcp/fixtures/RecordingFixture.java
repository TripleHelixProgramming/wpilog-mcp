/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.fixtures;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.triplehelix.wpilogmcp.store.*;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

/** A generated two-member recording with independently enumerable overlap and counts. */
public final class RecordingFixture {
  public static final String WHEN = "2026-01-01T00:00:00Z";
  public final Path root, directory;
  public final JsonObject reference;
  public RecordingFixture(Path root, int names) throws Exception {
    this(root, names, "");
  }
  public RecordingFixture(Path root, int names, String metadata) throws Exception {
    this.root = Files.createDirectories(root).toRealPath();
    String store = UUID.randomUUID().toString(), session = UUID.randomUUID().toString();
    var robot = Files.createDirectories(this.root.resolve("robots/synthetic"));
    directory = Files.createDirectories(robot.resolve("sessions/2026-01-01/session"));
    Files.writeString(this.root.resolve("store.json"), StoreJson.JSON.toJson(new Header(1, WHEN, store, List.of())));
    Files.writeString(robot.resolve("robot.json"), StoreJson.JSON.toJson(new Robot("synthetic", "synthetic", null, null, "logged")));
    var files = new ArrayList<LogFile>();
    for (int member = 0; member < 2; member++) {
      String name = member == 0 ? "capture.wpilog" : "rollover.wpilog";
      var path = directory.resolve(name);
      try (var writer = new WpilogWriter(path, "")) {
        for (int i = 0; i < names; i++) {
          // Half overlap; the rest is split evenly between the two members.
          if (i >= names / 2 && i % 2 != member) continue;
          int id = writer.start(name(i), "int64", metadata, 0);
          for (int sample = 0; sample <= member; sample++) writer.append(id, (sample + 1) * 1_000_000L, WpilogWriter.encodeInt64(i + sample));
        }
      }
      files.add(new LogFile(name, LogStore.contentHash(path), Files.size(path), "wpilog",
          new Provenance("captured", null, name, WHEN, false), true, 1, 2, WHEN, WHEN, "pit_clock", false, null));
    }
    Files.writeString(directory.resolve("session.json"), StoreJson.JSON.toJson(new Session(session, WHEN, WHEN, "pit_clock", null, null, null, null, files, null)));
    reference = JsonParser.parseString("""
        {"kind":"session","origin_store_id":"%s","session_id":"%s",
         "anchor_member":{"original_name":"capture.wpilog","recording_started_at":"%s"}}
        """.formatted(store, session, WHEN)).getAsJsonObject();
  }
  public static String name(int index) { return "/signal/" + String.format(Locale.ROOT, "%05d", index); }
  @FunctionalInterface public interface Contents { void write(WpilogWriter writer) throws Exception; }
  public void rewrite(int member, Contents contents) throws Exception {
    String name = member == 0 ? "capture.wpilog" : "rollover.wpilog"; var path = directory.resolve(name);
    try (var writer = new WpilogWriter(path, "")) { contents.write(writer); }
    var manifest = directory.resolve("session.json"); var session = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
    var entry = session.getAsJsonArray("files").get(member).getAsJsonObject();
    entry.addProperty("sha256", LogStore.contentHash(path)); entry.addProperty("size_bytes", Files.size(path));
    Files.writeString(manifest, session.toString());
    org.triplehelix.wpilogmcp.log.LogManager.getInstance().catalogPublished();
  }
}
