/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.sync.FileTransfer;
import org.triplehelix.wpilogmcp.sync.PullManifest;
import org.triplehelix.wpilogmcp.sync.RemoteFiles;

/** The harness counts verified downloads while retaining evidence of an unopened pre-match name. */
class HarnessPullManifestTest {
  private static final Gson GSON = new GsonBuilder()
      .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();
  private static final String SERIAL = "SYNTHETIC-HARNESS";
  private static final String FINAL = "/u/logs/FRC_20010101_000000_Synthetic_Q1.wpilog";
  private static final String TEMPORARY = "/u/logs/FRC_TBD_0123abcdef.wpilog";
  @TempDir Path temp;

  @ParameterizedTest
  @ValueSource(strings = {TEMPORARY, "/u/logs/FRC_20010101_000000.wpilog"})
  void renameBetweenListingAndFirstOpenLeavesAnEmptyAttemptButCopiesTheFinalFile(String initial) throws Exception {
    var store = Files.createDirectory(temp.resolve("store"));
    var local = new MemoryLocal(store);
    var payload = new byte[173];
    for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 17 + 3);
    var remote = new RenamingRemote(initial, payload);
    var clock = new AtomicLong();
    try (var first = engine(remote, local, clock)) {
      var failure = assertThrows(IOException.class, first::step);
      assertEquals("Synthetic rename before first open", failure.getMessage());
    }
    var attempted = local.saved.getAsJsonArray("files").get(0).getAsJsonObject();
    assertEquals(initial, attempted.get("remote_name").getAsString());
    assertEquals(0, attempted.get("bytes_copied").getAsLong());
    assertFalse(attempted.get("verified").getAsBoolean());
    assertEquals(0, Files.size(store.resolve(attempted.get("local_name").getAsString())));

    // Reconstruct the engine from saved JSON, as reconnect does, rather than retaining its state.
    try (var resumed = engine(remote, local, clock)) {
      boolean finished = false;
      for (int i = 0; i < 30; i++) {
        var result = resumed.step();
        clock.addAndGet(Math.max(1, result.waitUs()));
        if (result.status() == FileTransfer.Status.IDLE) { finished = true; break; }
      }
      assertTrue(finished, "The final name must complete and verify after the failed first open");
    }
    assertEquals(2, local.saved.getAsJsonArray("files").size(), "The engine retains the zero-byte attempt");
    var finalEntry = GSON.fromJson(local.saved, PullManifest.class).files().stream()
        .filter(PullManifest.Entry::verified).findFirst().orElseThrow();
    assertEquals(FINAL, finalEntry.remoteName());
    assertArrayEquals(payload, local.bytes.get(finalEntry.localName()));
    assertEquals(payload.length, finalEntry.bytesCopied());
    assertEquals(1, local.verifications);
    assertEquals(2, remote.opens, "The failed first name and the successful final name each open once");
    var before = local.saved.deepCopy();
    var admitted = HarnessExpectations.verifiedPulls(local.saved, 1, store, Set.of(FINAL));
    assertEquals(1, admitted.getAsJsonArray("files").size());
    assertEquals(FINAL, admitted.getAsJsonArray("files").get(0).getAsJsonObject().get("remote_name").getAsString());
    assertEquals(before, local.saved, "Filtering harness evidence must not rewrite persisted progress");
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void extraVerifiedCopiesAreRejectedEvenWhenTheirBasenamesDiffer(boolean differentBasename) throws Exception {
    var fixture = fixture();
    fixture.manifest.getAsJsonArray("files").remove(0);
    var extra = fixture.manifest.getAsJsonArray("files").get(0).deepCopy().getAsJsonObject();
    extra.addProperty("remote_name", differentBasename
        ? "/U/logs/FRC_20010101_000001_Synthetic_Q2.wpilog" : FINAL.replace("/u/", "/U/"));
    fixture.manifest.getAsJsonArray("files").add(extra);
    assertThrows(AssertionError.class, () -> HarnessExpectations.verifiedPulls(
        fixture.manifest, 1, fixture.store, Set.of(FINAL, extra.get("remote_name").getAsString())));
  }

  @Test void missingExpectedVerifiedFileIsRejected() throws Exception {
    var fixture = fixture();
    fixture.manifest.getAsJsonArray("files").remove(1);
    assertThrows(AssertionError.class, () -> HarnessExpectations.verifiedPulls(
        fixture.manifest, 1, fixture.store, Set.of()));
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void verifiedNamesMustMatchTheFinalRemoteListing(boolean extraUnpulledRemote) throws Exception {
    var fixture = fixture();
    fixture.manifest.getAsJsonArray("files").remove(0);
    Set<String> names = extraUnpulledRemote ? Set.of(FINAL, "/u/logs/unpulled.wpilog")
        : Set.of("/u/logs/a-different-file.wpilog");
    assertThrows(AssertionError.class, () -> HarnessExpectations.verifiedPulls(
        fixture.manifest, 1, fixture.store, names));
  }

  @Test void duplicateVerifiedRemoteNamesDoNotSatisfyTheCount() throws Exception {
    var fixture = fixture();
    fixture.manifest.getAsJsonArray("files").remove(0);
    fixture.manifest.getAsJsonArray("files").add(fixture.manifest.getAsJsonArray("files").get(0).deepCopy());
    assertThrows(AssertionError.class, () -> HarnessExpectations.verifiedPulls(
        fixture.manifest, 2, fixture.store, Set.of(FINAL)));
  }

  @Test void verifiedFlagCannotHideMissingBytes() throws Exception {
    var fixture = fixture();
    fixture.manifest.getAsJsonArray("files").remove(0);
    fixture.manifest.getAsJsonArray("files").get(0).getAsJsonObject().addProperty("bytes_copied", 172);
    assertThrows(AssertionError.class, () -> HarnessExpectations.verifiedPulls(
        fixture.manifest, 1, fixture.store, Set.of(FINAL)));
  }

  enum UnsafeAttempt { PARTIAL, NONEMPTY_STAGE, MISSING_STAGE, STILL_PRESENT, RETRIED, FAILURE, OUTSIDE_STORE, ARBITRARY_NAME }

  @ParameterizedTest @EnumSource(UnsafeAttempt.class)
  void onlyTheVanishedUnopenedDataLogManagerAttemptCanBeExcluded(UnsafeAttempt unsafe) throws Exception {
    var fixture = fixture();
    var attempt = fixture.manifest.getAsJsonArray("files").get(0).getAsJsonObject();
    var stage = fixture.store.resolve(attempt.get("local_name").getAsString());
    Set<String> remoteNames = Set.of(FINAL);
    switch (unsafe) {
      case PARTIAL -> attempt.addProperty("bytes_copied", 1);
      case NONEMPTY_STAGE -> Files.write(stage, new byte[] {7});
      case MISSING_STAGE -> Files.delete(stage);
      case STILL_PRESENT -> remoteNames = Set.of(FINAL, TEMPORARY);
      case RETRIED -> attempt.addProperty("retries", 1);
      case FAILURE -> attempt.addProperty("failure", "Synthetic refusal");
      case OUTSIDE_STORE -> {
        Files.createFile(temp.resolve("outside.wpilog"));
        attempt.addProperty("local_name", "../outside.wpilog");
      }
      case ARBITRARY_NAME -> attempt.addProperty("remote_name", "/u/logs/arbitrary.wpilog");
    }
    Set<String> listing = remoteNames;
    assertThrows(AssertionError.class, () -> HarnessExpectations.verifiedPulls(
        fixture.manifest, 1, fixture.store, listing), unsafe.name());
  }

  private record Fixture(Path store, JsonObject manifest) {}

  private Fixture fixture() throws IOException {
    var store = Files.createDirectory(temp.resolve("store"));
    String stageName = "robots/" + SERIAL + "/pulled/attempt/FRC_TBD_0123abcdef.wpilog";
    var stage = store.resolve(stageName);
    Files.createDirectories(stage.getParent()); Files.createFile(stage);
    var empty = new PullManifest.Entry(TEMPORARY, 173, 1, 0, false, stageName, 0, null);
    var verified = new PullManifest.Entry(FINAL, 173, 1, 173, true,
        "robots/" + SERIAL + "/sessions/day/session/robot/final.wpilog", 0, null);
    var manifest = new PullManifest(PullManifest.FORMAT_VERSION, SERIAL, List.of(empty, verified), List.of());
    return new Fixture(store, GSON.toJsonTree(manifest).getAsJsonObject());
  }

  private static FileTransfer engine(RemoteFiles remote, MemoryLocal local, AtomicLong clock) {
    return new FileTransfer(remote, local, GSON.fromJson(local.saved, PullManifest.class),
        1_000_000, clock::get, () -> true);
  }

  private static String hash(byte[] bytes, long length) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(bytes, (int) length))); }
    catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
  }

  private static final class RenamingRemote implements RemoteFiles {
    private String current;
    private final byte[] payload;
    private int opens;
    RenamingRemote(String initial, byte[] payload) { current = initial; this.payload = payload; }
    @Override public List<File> list() { return List.of(new File(current, payload.length, 1)); }
    @Override public Optional<InputStream> open(String name, long offset) throws IOException {
      opens++;
      if (opens == 1) { assertEquals(current, name); current = FINAL; throw new IOException("Synthetic rename before first open"); }
      assertEquals(current, name);
      return Optional.of(new ByteArrayInputStream(payload, (int) offset, payload.length - (int) offset));
    }
    @Override public byte[] read(String name, long offset, int count) {
      fail("The fixture's supported stream must supply the copy"); return new byte[0];
    }
    @Override public Optional<String> prefixHash(String name, long length) {
      assertEquals(current, name); return Optional.of(hash(payload, length));
    }
  }

  private static final class MemoryLocal implements FileTransfer.Local {
    private final Path store;
    private final Map<String, byte[]> bytes = new HashMap<>();
    private JsonObject saved = GSON.toJsonTree(PullManifest.empty(SERIAL)).getAsJsonObject();
    private int next, verifications;
    MemoryLocal(Path store) { this.store = store; }
    @Override public String create(String remoteName) throws IOException {
      String name = "robots/" + SERIAL + "/pulled/attempt-" + ++next + "/" + remoteName.substring(remoteName.lastIndexOf('/') + 1);
      var path = store.resolve(name); Files.createDirectories(path.getParent()); Files.createFile(path);
      bytes.put(name, new byte[0]); return name;
    }
    @Override public long size(String name) { return bytes.get(name).length; }
    @Override public byte[] read(String name, long offset, int count) {
      return Arrays.copyOfRange(bytes.get(name), (int) offset, (int) offset + count);
    }
    @Override public String prefixHash(String name, long length) { return hash(bytes.get(name), length); }
    @Override public void append(String name, long offset, byte[] added) throws IOException {
      var existing = bytes.get(name); assertEquals(existing.length, offset);
      var complete = Arrays.copyOf(existing, existing.length + added.length);
      System.arraycopy(added, 0, complete, existing.length, added.length);
      bytes.put(name, complete); Files.write(store.resolve(name), complete);
    }
    @Override public String rename(String name, String remoteName) { throw new AssertionError("No bytes exist to prove the early rename"); }
    @Override public String archive(String name) { throw new AssertionError("An unopened attempt must not be restarted"); }
    @Override public void verify(String name) {
      verifications++; var held = bytes.get(name); assertEquals(173, held.length);
      for (int i = 0; i < held.length; i++) assertEquals((byte) (i * 17 + 3), held[i]);
    }
    @Override public String verified(PullManifest.Entry entry) { return entry.localName(); }
    @Override public void save(PullManifest manifest) { saved = GSON.toJsonTree(manifest).getAsJsonObject(); }
  }
}
