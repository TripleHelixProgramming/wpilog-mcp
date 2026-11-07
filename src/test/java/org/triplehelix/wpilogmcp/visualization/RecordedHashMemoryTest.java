/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.store.*;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

/** Two synthetic rollover members, checked by declared counts and actual hash reads. */
class RecordedHashMemoryTest {
  @TempDir Path temp;
  static final String WHEN = "2026-01-01T00:00:00Z";
  static final String STORE = "11111111-1111-4111-8111-111111111111";
  static final String SESSION = "22222222-2222-4222-8222-222222222222";
  final LogManager logs = LogManager.getInstance();
  final AtomicInteger hashes = new AtomicInteger();
  Set<Path> original;
  VisualizationService views;
  RecordedSources sources;
  Path file, manifest;
  JsonObject session, reference, recorded;

  @BeforeEach void start() throws Exception {
    temp = temp.toRealPath(); original = logs.getConfiguredDirectories();
    logs.clearAllowedDirectories(); logs.addAllowedDirectory(temp);
    var robot = Files.createDirectories(temp.resolve("robots/synthetic"));
    var directory = Files.createDirectories(robot.resolve("sessions/2026-01-01/session"));
    Files.writeString(temp.resolve("store.json"), StoreJson.JSON.toJson(new Header(1, WHEN, STORE, List.of())));
    Files.writeString(robot.resolve("robot.json"), StoreJson.JSON.toJson(new Robot("synthetic", "synthetic", null, null, "logged")));
    var files = new ArrayList<LogFile>();
    for (String name : List.of("capture.wpilog", "rollover.wpilog")) {
      var path = directory.resolve(name);
      try (var writer = new WpilogWriter(path, "")) {
        int id = writer.start("/value", "int64", "", 0);
        writer.append(id, 1_000_000, WpilogWriter.encodeInt64(7));
        writer.append(id, 2_000_000, WpilogWriter.encodeInt64(9));
      }
      files.add(new LogFile(name, LogStore.contentHash(path), Files.size(path), "wpilog",
          new Provenance("captured", null, name, WHEN, false), true, 1, 2, WHEN, WHEN, "pit_clock", false, null));
    }
    file = directory.resolve("capture.wpilog"); manifest = directory.resolve("session.json");
    session = StoreJson.JSON.toJsonTree(new Session(SESSION, WHEN, WHEN, "pit_clock", null, null, null, null, files, null)).getAsJsonObject();
    publish();
    reference = JsonParser.parseString("""
        {"kind":"session","origin_store_id":"%s","session_id":"%s",
         "anchor_member":{"original_name":"capture.wpilog","recording_started_at":"%s"}}
        """.formatted(STORE, SESSION, WHEN)).getAsJsonObject();
    recorded = new JsonObject(); recorded.addProperty("kind", "recorded");
    recorded.addProperty("origin_store_id", STORE); recorded.addProperty("content_sha256", LogStore.contentHash(file));
    views = new VisualizationService(ServerIdentity.ephemeral());
    sources = new RecordedSources(logs, views, new StoreDoor(logs::getAllowedDirectories), path -> {
      hashes.incrementAndGet(); return LogStore.contentHash(path);
    });
  }
  void publish() throws Exception { Files.writeString(manifest, session.toString()); logs.catalogPublished(); }
  JsonObject inspect() throws Exception {
    var inventory = sources.describe(reference);
    assertTrue(inventory.get("finalized").getAsBoolean());
    assertEquals(2, inventory.getAsJsonArray("members").size());
    assertEquals(4, inventory.getAsJsonArray("entries").get(0).getAsJsonObject().get("sample_count").getAsLong());
    return inventory;
  }
  @AfterEach void stop() throws Exception {
    if (sources != null) sources.close(); if (views != null) views.close();
    logs.addAllowedDirectory(temp); logs.release(temp);
    logs.clearAllowedDirectories(); original.forEach(logs::addAllowedDirectory);
  }

  @Test void freshInspectionsReuseBothFinalizedMemberHashes() throws Exception {
    var before = inspect(); assertEquals(2, hashes.get());
    var after = inspect();
    assertEquals(before, after, "Reuse must preserve the complete inventory, counts and hash proof");
    assertEquals(2, hashes.get(), "A fresh inspection must share both stable member verifications");
  }

  @Test void recordingAndSingleFileAdmissionsShareTheWorkspaceMemory() throws Exception {
    inspect(); assertEquals(file, sources.resolve(recorded)); inspect();
    assertEquals(2, hashes.get(), "Recorded and recording admission must use one verification owner");
  }

  @Test void verificationMemoryRetainsNoMappedLogsAndSurvivesIndexEviction() throws Exception {
    inspect(); assertTrue(logs.release(temp).released());
    inspect(); assertEquals(2, hashes.get(), "Reopening indexes must not reread finalized bytes for SHA-256");
  }

  @Test void cachedProofCannotAuthorizeAChangedManifestOrWithdrawnGrant() throws Exception {
    inspect();
    var entry = session.getAsJsonArray("files").get(0).getAsJsonObject();
    String hash = entry.get("sha256").getAsString(); entry.addProperty("sha256", "0".repeat(64)); publish();
    assertTrue(assertThrows(IOException.class, this::inspect).getMessage().contains("manifested"));
    entry.addProperty("sha256", hash); publish(); inspect();
    logs.clearAllowedDirectories(); logs.addAllowedDirectory(Files.createDirectory(temp.resolve("elsewhere")));
    assertThrows(IOException.class, this::inspect);
    assertThrows(IOException.class, () -> sources.resolve(recorded));
  }

  @Test void closingTheWorkspaceOwnerDropsProofsAndItsReplacementStartsCold() throws Exception {
    inspect();
    var field = RecordedSources.class.getDeclaredField("verified"); field.setAccessible(true);
    var memory = (org.triplehelix.wpilogmcp.read.server.VerifiedContentHashes) field.get(sources);
    sources.close();
    assertThrows(IOException.class, () -> memory.verify(file,
        org.triplehelix.wpilogmcp.log.FileSnapshot.of(file), recorded.get("content_sha256").getAsString()));
    sources = new RecordedSources(logs, views, new StoreDoor(logs::getAllowedDirectories), path -> {
      hashes.incrementAndGet(); return LogStore.contentHash(path);
    });
    inspect(); assertEquals(4, hashes.get(), "A replacement workspace has no previous verification memory");
  }

  @Test void singleFileAdmissionRechecksChangedBytesAfterARecordingInspection() throws Exception {
    inspect(); assertEquals(file, sources.resolve(recorded)); assertEquals(2, hashes.get());
    logs.release(temp);
    var boundary = org.triplehelix.wpilogmcp.log.FileSnapshot.of(file);
    var bytes = Files.readAllBytes(file); bytes[bytes.length - 1] ^= 1; Files.write(file, bytes);
    Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(boundary.modified().toMillis() + 2_000));
    var refusal = assertThrows(IOException.class, () -> sources.resolve(recorded));
    assertTrue(refusal.getMessage().contains("manifested hash"), refusal.toString());
    assertEquals(3, hashes.get());
  }
}
