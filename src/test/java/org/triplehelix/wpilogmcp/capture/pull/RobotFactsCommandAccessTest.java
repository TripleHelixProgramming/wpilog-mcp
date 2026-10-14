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
    assertStoreGuidance(result, store, java.io.File.separatorChar == '\\');
  }
  private static void assertStoreGuidance(Run result, Path store, boolean windows) {
    assertEquals(1, result.code());
    assertTrue(result.err().contains("Cannot access configured store"), result.err());
    assertTrue(result.err().contains(store.toString()), result.err());
    if (windows) {
      assertTrue(result.err().contains("Windows service account"), result.err());
      assertFalse(result.err().contains("sudo"), result.err());
    } else assertTrue(result.err().contains("sudo -u wpilog-mcp"), result.err());
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
    var original = List.copyOf(view.getAcl());
    var deny = java.nio.file.attribute.AclEntry.newBuilder()
        .setType(java.nio.file.attribute.AclEntryType.DENY).setPrincipal(processUser(store))
        .setPermissions(java.nio.file.attribute.AclEntryPermission.READ_DATA,
            java.nio.file.attribute.AclEntryPermission.WRITE_DATA,
            java.nio.file.attribute.AclEntryPermission.APPEND_DATA,
            java.nio.file.attribute.AclEntryPermission.EXECUTE).build();
    var restricted = new java.util.ArrayList<>(original); restricted.add(0, deny);
    var args = arguments(store, 22);
    try {
      view.setAcl(restricted);
      var result = run(args, null);
      assertStoreGuidance(result, store);
      assertTrue(result.err().contains("permission denied"), result.err());
      assertFalse(result.err().contains("owned by"), result.err());
    } finally { view.setAcl(original); }
    try (var files = Files.list(store)) { assertEquals(List.of(), files.toList()); }
    assertFalse(Files.exists(temp.resolve("facts.md")));
  }

  @Test void unverifiableAccountRefusesBeforeCreatingTheStore() throws Exception {
    var store = temp.resolve("unverifiable-store");
    var result = run(arguments(store, 22), new RobotFactsCommand.Account() {
      public boolean posix(Path directory) throws java.io.IOException { Files.getFileStore(directory); return true; }
      public UserPrincipal currentUser(Path directory) { throw new UnsupportedOperationException("synthetic account lookup unavailable"); }
    });
    assertStoreGuidance(result, store);
    assertTrue(result.err().contains("Cannot verify"), result.err());
    assertFalse(Files.exists(store));
  }

  @ParameterizedTest @ValueSource(strings = {"root", "SHOP\\Administrator"})
  void foreignAccountsCannotCreateAPosixStoreOrItsLock(String account) throws Exception {
    var store = temp.resolve(account.equals("root") ? "linux-store" : "windows-store");
    try (var rio = new FakeRoboRio(temp.resolve(account.equals("root") ? "rio-linux" : "rio-windows"), "SYNTHETIC", "")) {
      for (var probe : RobotFacts.commands()) rio.reply(probe.command(), 127, "", "synthetic: not available");
      UserPrincipal foreign = () -> account;
      var result = run(arguments(store, rio.port()), posixAccount(foreign));
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
    var result = run(arguments(store, 22), posixAccount(() -> "root"));
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
    var result = run(args, new RobotFactsCommand.Account() {
      public boolean posix(Path directory) throws java.io.IOException { return failure(); }
      public UserPrincipal currentUser(Path directory) throws java.io.IOException { failure(); throw new AssertionError(); }
      private boolean failure() throws java.io.IOException { if (queued) throw new CompletionException(denied); throw denied; }
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
  private static RobotFactsCommand.Account posixAccount(UserPrincipal current) {
    return new RobotFactsCommand.Account() {
      public boolean posix(Path directory) throws java.io.IOException { Files.getFileStore(directory); return true; }
      public UserPrincipal currentUser(Path directory) { return current; }
    };
  }
  private static UserPrincipal processUser(Path directory) throws java.io.IOException {
    return directory.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(
        ProcessHandle.current().info().user().orElseThrow(() -> new java.io.IOException("No process account")));
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void nonPosixGroupOwnerDoesNotPreventCollection(boolean existing) throws Exception {
    var store = temp.resolve("group-store"); if (existing) Files.createDirectory(store);
    var owners = new java.util.concurrent.atomic.AtomicInteger(); var users = new java.util.concurrent.atomic.AtomicInteger();
    var account = new RobotFactsCommand.Account() {
      public boolean posix(Path directory) throws java.io.IOException { Files.getFileStore(directory); return false; }
      public UserPrincipal owner(Path directory) throws java.io.IOException {
        owners.incrementAndGet(); Files.getOwner(directory);
        return (java.nio.file.attribute.GroupPrincipal) () -> "BUILTIN\\Administrators";
      }
      public UserPrincipal currentUser(Path directory) { users.incrementAndGet(); return () -> "SHOP\\Operator"; }
      public boolean windows() { return true; }
    };
    assertCollection(store, account);
    assertEquals(0, owners.get(), "Non-POSIX admission must not ask for ownership");
    assertEquals(0, users.get(), "Non-POSIX admission must not ask for an account comparison");
  }

  @Test void nonPosixCollectionDoesNotRequireOwnerOrAccountLookup() throws Exception {
    assertCollection(temp.resolve("acl-only-store"), new RobotFactsCommand.Account() {
      public boolean posix(Path directory) throws java.io.IOException { Files.getFileStore(directory); return false; }
      public UserPrincipal owner(Path directory) { throw new AssertionError("Non-POSIX owner lookup"); }
      public UserPrincipal currentUser(Path directory) { throw new AssertionError("Non-POSIX account lookup"); }
      public boolean windows() { return true; }
    });
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void windowsAccessFailuresKeepPermissionCauseAndWindowsGuidance(boolean queued) throws Exception {
    var store = temp.resolve("windows-denied"); var denied = new AccessDeniedException(store.toString());
    var account = new RobotFactsCommand.Account() {
      public boolean posix(Path directory) throws java.io.IOException { return failure(); }
      public UserPrincipal currentUser(Path directory) throws java.io.IOException { failure(); throw new AssertionError(); }
      private boolean failure() throws java.io.IOException { if (queued) throw new CompletionException(denied); throw denied; }
      public boolean windows() { return true; }
    };
    var result = run(arguments(store, 22), account);
    assertStoreGuidance(result, store, true);
    assertTrue(result.err().contains("permission denied"), result.err());
    assertFalse(result.err().contains("owned by"), result.err());
    assertFalse(Files.exists(store));
  }

  @Test void groupWritablePosixStoreStillRefusesAForeignAccount() throws Exception {
    assumeTrue(Files.getFileStore(temp).supportsFileAttributeView("posix"));
    var store = Files.createDirectory(temp.resolve("group-writable"));
    var original = Files.getPosixFilePermissions(store);
    try {
      Files.setPosixFilePermissions(store, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwx---"));
      assertTrue(Files.isWritable(store));
      var result = run(arguments(store, 22), directory -> () -> "foreign-service-operator");
      assertStoreGuidance(result, store);
      assertTrue(result.err().contains("owned by"), result.err());
      try (var files = Files.list(store)) { assertEquals(List.of(), files.toList()); }
    } finally { Files.setPosixFilePermissions(store, original); }
  }

  @Test @EnabledOnOs(OS.WINDOWS)
  void nativeWindowsGroupOwnedStoreUsesItsActualAcl() throws Exception {
    var store = Files.createDirectory(temp.resolve("native-group-store"));
    var original = Files.getOwner(store);
    // The Windows CI runner uses the built-in Administrators group. Failure to establish
    // this ownership is a fixture failure, not skipped group-owner coverage.
    var group = store.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByGroupName("BUILTIN\\Administrators");
    try {
      assertDoesNotThrow(() -> Files.setOwner(store, group), "The native fixture must establish Administrators ownership");
      assertEquals(group, Files.getOwner(store), "The native regression requires an actual Administrators-owned directory");
      assertNotEquals(processUser(store), Files.getOwner(store));
      assertCollection(store, null);
    } finally { Files.setOwner(store, original); }
  }

  private void assertCollection(Path store, RobotFactsCommand.Account account) throws Exception {
    try (var rio = new FakeRoboRio(temp.resolve("collection-rio"), "SYNTHETIC", "")) {
      for (var probe : RobotFacts.commands()) rio.reply(probe.command(), 127, "", "synthetic: not available");
      var result = run(arguments(store, rio.port()), account);
      assertEquals(0, result.code(), result.err());
      assertTrue(Files.readString(store.resolve("ssh-hosts.json")).contains("SHA256:"));
      assertTrue(Files.exists(store.resolve("store.lock")));
      assertTrue(Files.exists(temp.resolve("facts.md")));
      assertEquals(1, rio.authentications.get());
    }
  }

}
