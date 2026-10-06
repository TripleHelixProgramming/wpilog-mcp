/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Refresh keeps an exact recovery copy and cannot race an installation or a daemon's claim. */
class InstallRefreshTest {
  @TempDir Path temp;
  private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

  private InstallCommand.Options options(Path root, boolean force) {
    return new InstallCommand.Options(root, List.of("ignored-new-default"), 9999, force, true, false, null, true);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void freshLayoutPreservesSettingsAndBacksUpEverything(boolean windows) throws Exception {
    var root = Files.createDirectory(temp.resolve("install"));
    var source = Files.writeString(temp.resolve("download.jar"), "new jar bytes");
    String settings = "# My settings and comments\nteam: 2363\nlogdir: ../my-logs\ntba_key: synthetic-private-key\n";
    Files.writeString(root.resolve("servers.yaml"), settings);
    Files.writeString(root.resolve("servers.json"), "{\"team\":2363}");
    Files.writeString(root.resolve("old-sentinel"), "keep in backup only");
    var summary = InstallCommand.install(source, "1.0.0", options(root, false), windows, "");
    var backup = Path.of(summary.backup_dir());
    assertEquals(root.getParent(), backup.getParent());
    assertEquals(settings, Files.readString(root.resolve("servers.yaml")));
    assertEquals(settings, Files.readString(backup.resolve("servers.yaml")));
    assertEquals("{\"team\":2363}", Files.readString(root.resolve("servers.json")));
    assertEquals("keep in backup only", Files.readString(backup.resolve("old-sentinel")));
    assertFalse(Files.exists(root.resolve("old-sentinel")));
    assertFalse(summary.config_created());
    assertTrue(summary.json().contains("backup_dir"));
    assertEquals("new jar bytes", Files.readString(root.resolve("jars").resolve("wpilog-mcp-1.0.0.jar")));
    assertThrows(IOException.class, () -> InstallGuard.acquire(backup, backup.resolve("install.lock")),
        "a pre-rename installer cannot claim the retired layout");
  }

  @Test
  void refreshCannotDowngradeOrMoveItsRunningJar() throws Exception {
    var root = Files.createDirectory(temp.resolve("install"));
    var source = Files.writeString(temp.resolve("download.jar"), "new");
    InstallCommand.install(source, "2.0.0", new InstallCommand.Options(root, List.of(), null, false, false), WINDOWS, "");
    var refused = assertThrows(IOException.class,
        () -> InstallCommand.install(source, "1.0.0", options(root, false), WINDOWS, ""));
    assertTrue(refused.getMessage().contains("downgrade"));
    var inside = root.resolve("jars").resolve("wpilog-mcp-2.0.0.jar");
    assertTrue(assertThrows(IOException.class,
        () -> InstallCommand.install(inside, "2.0.0", options(root, true), WINDOWS, ""))
        .getMessage().contains("outside the install directory"));
    var forced = InstallCommand.install(source, "1.0.0", options(root, true), WINDOWS, "");
    assertNotNull(forced.backup_dir());
    assertEquals("2.0.0", forced.launcher_version_before());
  }

  @Test
  void activeInstallAndStartingDaemonPreventRefresh() throws Exception {
    var root = Files.createDirectory(temp.resolve("install"));
    var run = Files.createDirectory(root.resolve("run"));
    var source = Files.writeString(temp.resolve("download.jar"), "new");
    try (var channel = FileChannel.open(root.resolve("install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        var held = channel.lock()) {
      assertTrue(assertThrows(IOException.class,
          () -> InstallCommand.install(source, "1.0.0", options(root, false), WINDOWS, ""))
          .getMessage().contains("in progress"));
    }
    Files.writeString(run.resolve("http.pid"), "9223372036854775806\n2363\nstarting\n");
    assertTrue(assertThrows(IOException.class,
        () -> InstallCommand.install(source, "1.0.0", options(root, false), WINDOWS, ""))
        .getMessage().contains("starting or stopping"));
    assertTrue(Files.exists(run.resolve("http.pid")));
  }

  @Test
  void guardRefusesStartsWhileHeldAndAfterItsHandleCloses() throws Exception {
    var root = Files.createDirectory(temp.resolve("install"));
    var run = Files.createDirectory(root.resolve("run"));
    var file = run.resolve(".install-refresh.guard");
    int port;
    try (var socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    var manager = new DaemonManager(run, Duration.ofMillis(1), (command, environment, log) -> {
      fail("refresh must prevent a spawn");
      return null;
    });
    try (var guard = InstallGuard.acquire(root, file)) {
      assertFalse(manager.spawnDaemon("http", port, null));
      guard.markForRefresh();
    }
    assertFalse(manager.spawnDaemon("http", port, null));
    assertFalse(Files.exists(run.resolve("http.pid")));
    assertThrows(IOException.class, () -> InstallGuard.acquire(root, file, true), "live marker owner wins");
    Files.writeString(file, "refresh 9223372036854775806 abandoned");
    try (var recovered = InstallGuard.acquire(root, file, true)) {
      // Windows enforces the exclusive byte-range lock even against another local handle.
    }
    assertEquals("", Files.readString(file));
  }
}
