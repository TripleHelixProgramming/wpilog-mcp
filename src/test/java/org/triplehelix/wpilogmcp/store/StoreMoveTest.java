/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.LogFileAccess;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/** A simulated second volume makes corruption and source-delete ordering deterministic. */
class StoreMoveTest {
  @TempDir Path temp;
  Path root;
  Path source;
  SecurityValidator security;
  Set<Path> previousAllowed;

  @BeforeEach void setup() throws Exception {
    temp = temp.toRealPath();
    root = Files.createDirectory(temp.resolve("store"));
    source = ImportFixture.write(temp.resolve("source.wpilog"), 17);
    security = new SecurityValidator();
    security.addAllowedDirectory(temp);
    previousAllowed = LogManager.getInstance().getConfiguredDirectories();
    LogManager.getInstance().addAllowedDirectory(temp);
  }

  @AfterEach void cleanup() {
    LogManager.getInstance().clearAllowedDirectories();
    previousAllowed.forEach(LogManager.getInstance()::addAllowedDirectory);
  }

  private LogStore.Outcome move(LogStore.Mover mover) throws Exception {
    try (var store = new LogStore(root, security, LogManager.getInstance(), StoreCatalog::read, mover)) {
      return store.importPaths(new LogStore.Request(List.of(source), true, "practice"), p -> {})
          .get(30, TimeUnit.SECONDS).files().get(0);
    }
  }

  private class OtherVolume extends LogStore.Mover {
    @Override boolean sameFileStore(Path from, Path to) { return false; }

    // Model the JDK's old cross-volume move: copy, then delete before the caller verifies.
    @Override void move(Path from, Path to, CopyOption... options) throws IOException {
      if (!from.equals(source)) { super.move(from, to, options); return; }
      copy(from, to, Files.size(from));
      delete(from);
    }
  }

  @Test void corruptCrossVolumeCopyLeavesOriginalAndNoReceiptOrPayload() throws Exception {
    byte[] original = Files.readAllBytes(source);
    var result = move(new OtherVolume() {
      @Override LogStore.CopyAttributes copy(Path from, Path to, long admittedBytes) throws IOException {
        var attributes = super.copy(from, to, admittedBytes);
        byte[] bytes = Files.readAllBytes(to);
        bytes[bytes.length - 1] ^= 1;
        Files.write(to, bytes);
        return attributes;
      }
    });
    assertEquals("refused", result.status());
    assertArrayEquals(original, Files.readAllBytes(source),
        "A bad cross-volume copy must leave the original byte for byte intact");
    assertTrue(result.reason().contains("Copy hash differs"), result.reason());
    var catalog = StoreCatalog.read(root, security);
    assertTrue(catalog.files().isEmpty(), "A rejected copy has no manifest receipt");
    assertTrue(catalog.unmanaged().isEmpty(), "Remove only this placement's rejected copy");
    try (var paths = Files.walk(root)) {
      assertTrue(paths.noneMatch(p -> p.getFileName().toString().equals("session.json")
          || p.getFileName().toString().equals("import.json") || p.toString().endsWith(".wpilog")));
    }
  }

  @Test void sourceDeleteFailureAfterCommitKeepsVerifiedImportAndExplainsOriginal() throws Exception {
    byte[] original = Files.readAllBytes(source);
    var result = move(new OtherVolume() {
      @Override void delete(Path from) throws IOException {
        throw new IOException("simulated source delete denied");
      }
    });
    assertEquals("imported", result.status(),
        "Source deletion failure after verification must not refuse or roll back the import");
    assertArrayEquals(original, Files.readAllBytes(source));
    assertArrayEquals(original, Files.readAllBytes(result.path()));
    assertTrue(result.reason().contains("original could not be removed"), result.reason());
    assertTrue(result.reason().contains("simulated source delete denied"), result.reason());
    var catalog = StoreCatalog.read(root, security);
    assertEquals(1, catalog.files().size());
    assertEquals(result.path(), catalog.files().get(0).path());
    assertTrue(catalog.files().get(0).file().verified());
  }

  @Test void sameVolumeKeepsTheAtomicRename() throws Exception {
    var calls = new ArrayList<String>();
    var optionsUsed = new ArrayList<CopyOption>();
    var result = move(new LogStore.Mover() {
      @Override void move(Path from, Path to, CopyOption... options) throws IOException {
        optionsUsed.addAll(List.of(options));
        calls.add("rename"); super.move(from, to, options);
      }
      @Override LogStore.CopyAttributes copy(Path from, Path to, long admittedBytes) throws IOException {
        throw new IOException("Same-volume rename must not copy");
      }
    });
    assertEquals("imported", result.status());
    assertEquals(List.of("rename"), calls);
    assertEquals(List.of(StandardCopyOption.ATOMIC_MOVE), optionsUsed,
        "Same-volume placement must require an atomic rename");
    assertFalse(Files.exists(source));
  }

  @Test void unsupportedAtomicRenameCopiesForcesAndCommitsBeforeDeleting() throws Exception {
    byte[] original = Files.readAllBytes(source);
    var calls = new ArrayList<String>();
    var result = move(new LogStore.Mover() {
      @Override void move(Path from, Path to, CopyOption... options) throws IOException {
        calls.add("rename");
        throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "synthetic volume");
      }
      @Override LogStore.CopyAttributes copy(Path from, Path to, long admittedBytes) throws IOException {
        calls.add("copy"); return super.copy(from, to, admittedBytes);
      }
      @Override void force(Path to) throws IOException {
        assertArrayEquals(original, Files.readAllBytes(source));
        assertTrue(StoreCatalog.read(root, security).files().isEmpty(), "Force precedes the receipt");
        calls.add("force"); super.force(to);
      }
      @Override void delete(Path from) throws IOException {
        assertEquals(List.of("rename", "copy", "force"), calls);
        var files = StoreCatalog.read(root, security).files();
        assertEquals(1, files.size(), "Commit the verified receipt before deleting the source");
        assertArrayEquals(original, Files.readAllBytes(files.get(0).path()));
        calls.add("delete"); super.delete(from);
      }
    });
    assertEquals("imported", result.status(), result.toString());
    assertEquals(List.of("rename", "copy", "force", "delete"), calls);
    assertFalse(Files.exists(source));
  }

  @Test void aNewOriginalAppearingDuringVerificationIsNeverDeleted() throws Exception {
    byte[] replacement = new byte[]{9, 8, 7, 6};
    var result = move(new OtherVolume() {
      @Override void force(Path to) throws IOException {
        super.force(to);
        Files.delete(source);
        Files.write(source, replacement);
      }
    });
    // The stronger staging rule refuses stale inspection facts before making a receipt.
    assertEquals("refused", result.status());
    assertTrue(Files.exists(source), "A new file at the original path must survive cleanup");
    assertArrayEquals(replacement, Files.readAllBytes(source));
    assertNull(result.path(), "A refused Move discards its own staged copy");
    assertTrue(StoreCatalog.read(root, security).files().isEmpty());
    assertTrue(result.reason().contains("changed"), result.reason());
  }

  @Test void anOccupiedDestinationIsRefusedRatherThanOverwrittenByAtomicRename() throws Exception {
    byte[] original = Files.readAllBytes(source);
    byte[] occupied = new byte[]{3, 1, 4, 1, 5};
    var destinations = new ArrayList<Path>();
    var result = move(new LogStore.Mover() {
      @Override boolean sameFileStore(Path from, Path to) throws IOException {
        Files.write(to, occupied);
        destinations.add(to);
        return true;
      }
    });
    assertEquals("refused", result.status(), "Atomic rename must not replace an occupied payload");
    assertArrayEquals(occupied, Files.readAllBytes(destinations.get(0)));
    assertArrayEquals(original, Files.readAllBytes(source));
  }

  @Test void anActiveReaderReturnsADeferredOutcome() throws Exception {
    try (var reader = LogFileAccess.read(source)) {
      var result = move(new LogStore.Mover());
      // A reader lets go before long: the move is deferred and tried again, not refused for good.
      assertEquals("deferred", result.status());
      assertTrue(result.reason().contains("read"), result.reason());
      assertTrue(Files.exists(source));
    }
  }

  @ParameterizedTest @ValueSource(strings = {"rw-------", "r--------"})
  void crossVolumeCopyRetainsSourcePermissions(String mode) throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Files.getFileStore(source).supportsFileAttributeView("posix"),
        "POSIX permissions are not provided by this filesystem");
    var permissions = java.nio.file.attribute.PosixFilePermissions.fromString(mode);
    Files.setPosixFilePermissions(source, permissions);
    var result = move(new OtherVolume());
    assertEquals("imported", result.status(), result.toString());
    assertEquals(permissions, Files.getPosixFilePermissions(result.path()),
        "Move must preserve private and read-only source permissions");
  }
}
