/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.harness.FakeRoboRio;

/** Filesystem errors and foreign accounts use the public command's admission path, before SSH. */
class RobotFactsCommandAccessTest {
  @TempDir Path temp;
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
  private record Run(int code, String out, String err) {}

  private String[] arguments(Path store, int port) throws Exception {
    var yaml = temp.resolve("servers.yaml");
    Files.writeString(yaml, "servers:\n  pit:\n    transport: http\n    capture:\n"
        + "      robot: {host: 127.0.0.1}\n      store: '" + store + "'\n"
        + "      pull:\n        ssh: {user: lvuser, port: " + port + "}\n");
    return new String[] {"robot-facts", "--server", "pit", "--config", yaml.toString(),
        "--out", temp.resolve("facts.md").toString()};
  }

  private Run run(String[] args, RobotFactsCommand.Account account) {
    var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
    int code = account == null
        ? RobotFactsCommand.run(args, new PrintStream(out), new PrintStream(err), temp, CLOCK)
        : RobotFactsCommand.run(args, new PrintStream(out), new PrintStream(err), temp, CLOCK, account);
    return new Run(code, out.toString(), err.toString());
  }

  private static void assertStoreGuidance(Run result, Path store) {
    assertEquals(1, result.code());
    assertTrue(result.err().contains("Cannot access configured store"), result.err());
    assertTrue(result.err().contains(store.toString()), result.err());
    assertTrue(result.err().contains("sudo -u wpilog-mcp"), result.err());
    assertTrue(result.err().contains("robot-facts --server pit"), result.err());
    assertTrue(result.err().contains("robot-facts 127.0.0.1"), result.err());
    assertEquals("", result.out());
  }

  @Test void deniedPosixDirectoryExplainsTheStoreAndBothWaysForward() throws Exception {
    assumeTrue(Files.getFileStore(temp).supportsFileAttributeView("posix"));
    var store = Files.createDirectory(temp.resolve("denied-store"));
    var original = Files.getPosixFilePermissions(store);
    var args = arguments(store, 22);
    try {
      Files.setPosixFilePermissions(store, Set.of());
      assumeFalse(Files.isWritable(store), "A privileged account bypasses POSIX mode denial");
      assertStoreGuidance(run(args, null), store);
    } finally { Files.setPosixFilePermissions(store, original); }
    try (var files = Files.list(store)) { assertEquals(List.of(), files.toList()); }
    assertFalse(Files.exists(temp.resolve("facts.md")));
  }

  @Test @EnabledOnOs(OS.WINDOWS)
  void deniedWindowsAclExplainsTheStoreAndBothWaysForward() throws Exception {
    var store = Files.createDirectory(temp.resolve("acl-denied-store"));
    var view = Files.getFileAttributeView(store, java.nio.file.attribute.AclFileAttributeView.class);
    assumeTrue(view != null, "The native Windows test needs an ACL filesystem");
    var original = view.getAcl();
    var deny = java.nio.file.attribute.AclEntry.newBuilder()
        .setType(java.nio.file.attribute.AclEntryType.DENY).setPrincipal(Files.getOwner(store))
        .setPermissions(java.nio.file.attribute.AclEntryPermission.READ_DATA,
            java.nio.file.attribute.AclEntryPermission.WRITE_DATA,
            java.nio.file.attribute.AclEntryPermission.APPEND_DATA,
            java.nio.file.attribute.AclEntryPermission.EXECUTE).build();
    var restricted = new java.util.ArrayList<>(original); restricted.add(0, deny);
    var args = arguments(store, 22);
    try {
      view.setAcl(restricted);
      assertStoreGuidance(run(args, null), store);
    } finally { view.setAcl(original); }
    try (var files = Files.list(store)) { assertEquals(List.of(), files.toList()); }
    assertFalse(Files.exists(temp.resolve("facts.md")));
  }

  @Test void unverifiableAccountRefusesBeforeCreatingTheStore() throws Exception {
    var store = temp.resolve("unverifiable-store");
    var result = run(arguments(store, 22), directory -> {
      throw new UnsupportedOperationException("synthetic account lookup unavailable");
    });
    assertStoreGuidance(result, store);
    assertTrue(result.err().contains("Cannot verify"), result.err());
    assertFalse(Files.exists(store));
  }

  @ParameterizedTest @ValueSource(strings = {"root", "SHOP\\Administrator"})
  void foreignPosixAndWindowsAccountsCannotCreateAStoreOrItsLock(String account) throws Exception {
    var store = temp.resolve(account.equals("root") ? "linux-store" : "windows-store");
    try (var rio = new FakeRoboRio(temp.resolve(account.equals("root") ? "rio-linux" : "rio-windows"), "SYNTHETIC", "")) {
      for (var probe : RobotFacts.commands()) rio.reply(probe.command(), 127, "", "synthetic: not available");
      UserPrincipal foreign = () -> account;
      var result = run(arguments(store, rio.port()), directory -> foreign);
      assertStoreGuidance(result, store);
      assertTrue(result.err().contains("owned by"), result.err());
      assertTrue(result.err().contains(account), result.err());
      assertFalse(Files.exists(store), "Refuse before even creating the store directory");
      assertEquals(0, rio.authentications.get());
    }
  }

  @Test void foreignAccountCannotReplaceExistingPinsOrTouchThePermanentLock() throws Exception {
    var store = Files.createDirectory(temp.resolve("existing-store"));
    Files.writeString(store.resolve("ssh-hosts.json"), "{\"fingerprints\":{\"old\":\"SHA256:synthetic\"}}");
    Files.writeString(store.resolve("store.lock"), "permanent inode");
    var lockTime = Files.getLastModifiedTime(store.resolve("store.lock"));
    var result = run(arguments(store, 22), directory -> () -> "root");
    assertStoreGuidance(result, store);
    assertEquals("{\"fingerprints\":{\"old\":\"SHA256:synthetic\"}}", Files.readString(store.resolve("ssh-hosts.json")));
    assertEquals("permanent inode", Files.readString(store.resolve("store.lock")));
    assertEquals(lockTime, Files.getLastModifiedTime(store.resolve("store.lock")));
    assertFalse(Files.exists(store.resolve("store.json")), "Pin lookup must not initialize a foreign store");
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void permissionErrorsRetainGuidanceWithDirectAndQueuedFilesystemFailures(boolean queued) throws Exception {
    var store = temp.resolve(queued ? "queued-denied" : "direct-denied");
    var args = arguments(store, 22);
    var denied = new AccessDeniedException(store.toString());
    var result = run(args, directory -> {
      if (queued) throw new CompletionException(denied);
      throw denied;
    });
    assertStoreGuidance(result, store);
    assertTrue(result.err().contains("permission denied"), result.err());
    assertFalse(Files.exists(store));
  }

  @Test void ownAccountCanInitializeItsPins() throws Exception {
    var store = temp.resolve("own-store");
    try (var rio = new FakeRoboRio(temp.resolve("own-rio"), "SYNTHETIC", "")) {
      for (var probe : RobotFacts.commands()) rio.reply(probe.command(), 127, "", "synthetic: not available");
      var result = run(arguments(store, rio.port()), null);
      assertEquals(0, result.code(), result.err());
      assertTrue(Files.readString(store.resolve("ssh-hosts.json")).contains("SHA256:"));
      assertTrue(Files.exists(store.resolve("store.lock")));
      assertTrue(Files.exists(temp.resolve("facts.md")));
      assertEquals(Files.getOwner(temp), Files.getOwner(store.resolve("ssh-hosts.json")));
    }
  }
}
