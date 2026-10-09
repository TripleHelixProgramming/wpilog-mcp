/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Scratch installs pin the ownership of files and the ordering that prevents a downgrade. */
class InstallCommandTest {
  @TempDir Path temp;
  private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

  @Test
  void installedBinariesAreReadableByTheServiceUserAndReinstallRepairsTheirModes() throws Exception {
    Assumptions.assumeFalse(WINDOWS, "POSIX modes do not apply on Windows");
    var readable = java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--");
    var executable = java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x");
    var privateFile = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
    var privateDirectory = java.nio.file.attribute.PosixFilePermissions.fromString("rwx------");
    var root = temp.resolve("install");
    var input = source();
    Files.setPosixFilePermissions(input, privateFile);
    var jar = root.resolve("jars/wpilog-mcp-1.0.0.jar");
    var launcher = root.resolve("bin/wpilog-mcp-1.0.0");
    for (int install = 0; install < 2; install++) {
      InstallCommand.install(input, "1.0.0", options(root, false), false, "");
      assertAll("install " + install,
          () -> assertEquals(readable, Files.getPosixFilePermissions(jar), "JAR must be readable by the service user"),
          () -> assertEquals(executable, Files.getPosixFilePermissions(launcher), "launcher must be readable and executable"),
          () -> assertEquals(executable, Files.getPosixFilePermissions(root.resolve("bin")), "bin must be traversable"),
          () -> assertEquals(executable, Files.getPosixFilePermissions(root.resolve("jars")), "jars must be traversable"));
      assertEquals(privateFile, Files.getPosixFilePermissions(input), "The source belongs to the caller");
      if (install == 0) {
        for (var file : List.of(jar, launcher, root.resolve("servers.yaml"))) Files.setPosixFilePermissions(file, privateFile);
        for (var dir : List.of(root.resolve("bin"), root.resolve("jars"))) Files.setPosixFilePermissions(dir, privateDirectory);
      } else {
        assertEquals(privateFile, Files.getPosixFilePermissions(root.resolve("servers.yaml")), "Reinstall preserves private configuration");
      }
    }
  }

  @Test
  void extensionBootstrapRequiresAnExplicitVsix() {
    var error = assertThrows(IllegalArgumentException.class,
        () -> InstallCommand.parse(new String[] {"install", "--with-extension"}));
    assertTrue(error.getMessage().contains("--with-extension requires --vsix"), error.getMessage());
    assertThrows(IllegalArgumentException.class, () -> InstallCommand.parse(new String[] {"install", "--vsix", "alone.vsix"}));
    assertDoesNotThrow(() -> InstallCommand.parse(new String[] {"install", "--with-extension", "--vsix",
        temp.resolve("matching extension.vsix").toString()}));
  }

  @Test
  void refreshIsAnExplicitInstallOperation() {
    assertDoesNotThrow(() -> InstallCommand.parse(new String[] {"install", "--refresh", "--install-dir",
        temp.resolve("install").toString()}));
  }

  private InstallCommand.Options options(Path root, boolean force) {
    return new InstallCommand.Options(root, List.of(), null, force, false);
  }

  private Path source() throws Exception {
    return Files.write(temp.resolve("source.jar"), new byte[]{0, 1, 2, -1, 42});
  }

  static Stream<Arguments> escapingPaths() {
    return Stream.of("bin", "jars", "install.lock", "servers.yaml", "servers.json", "jar", "versioned", "current")
        .flatMap(role -> Stream.of(false, true).flatMap(windows -> Stream.of(false, true)
            .map(exists -> Arguments.of(role, windows, exists))));
  }

  @ParameterizedTest
  @MethodSource("escapingPaths")
  void refusesEveryEscapingInstallDestination(String role, boolean windows, boolean exists) throws Exception {
    var root = Files.createDirectories(temp.resolve("install"));
    var outside = Files.createDirectories(temp.resolve("outside"));
    var sentinel = Files.writeString(outside.resolve("sentinel"), "untouched");
    var target = outside.resolve("target");
    boolean directory = role.equals("bin") || role.equals("jars");
    if (exists) {
      if (directory) Files.createDirectory(target);
      else Files.writeString(target, "untouched target");
    }
    var link = switch (role) {
      case "jar" -> root.resolve("jars").resolve("wpilog-mcp-1.0.0.jar");
      case "versioned" -> root.resolve("bin").resolve("wpilog-mcp-1.0.0" + (windows ? ".bat" : ""));
      case "current" -> root.resolve("bin").resolve(windows ? "wpilog-mcp.bat" : "wpilog-mcp");
      default -> root.resolve(role);
    };
    Files.createDirectories(link.getParent());
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | IOException e) {
      Assumptions.abort("Symlinks unavailable: " + e.getMessage());
    }
    var input = source();
    List<Path> before;
    try (var walk = Files.walk(outside)) {
      before = walk.sorted().toList();
    }
    var error = assertThrows(IOException.class,
        () -> InstallCommand.install(input, "1.0.0", options(root, true), windows, ""));
    assertTrue(error.getMessage().contains(link.toString()), error.getMessage());
    assertEquals("untouched", Files.readString(sentinel));
    assertEquals(exists, Files.exists(target));
    if (exists && !directory) assertEquals("untouched target", Files.readString(target));
    try (var walk = Files.walk(outside)) {
      assertEquals(before, walk.sorted().toList(), "no outside file or directory may be created");
    }
    if (!role.equals("install.lock")) {
      assertFalse(Files.exists(root.resolve("install.lock")), "preflight precedes even the lock write");
    }
  }

  @Test
  void danglingCurrentLauncherInsideRootCanBeReplaced() throws Exception {
    Assumptions.assumeFalse(WINDOWS, "The current launcher is a copy on Windows");
    var root = Files.createDirectories(temp.resolve("install"));
    var bin = Files.createDirectories(root.resolve("bin"));
    var current = bin.resolve("wpilog-mcp");
    Files.createSymbolicLink(current, Path.of("wpilog-mcp-deleted-version"));
    var result = InstallCommand.install(source(), "1.0.0", options(root, false), false, "");
    assertTrue(result.repointed());
    assertNull(result.launcher_version_before());
    assertEquals(Path.of("wpilog-mcp-1.0.0"), Files.readSymbolicLink(current));
    assertEquals("1.0.0", InstallCommand.launcherVersion(Files.readString(current)));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void launcherTargetResolvesDirectoryLinksBeforeParentComponents(boolean exists) throws Exception {
    var root = Files.createDirectories(temp.resolve("install"));
    var bin = Files.createDirectories(root.resolve("bin"));
    var outside = Files.createDirectories(temp.resolve("outside"));
    var deeper = Files.createDirectory(outside.resolve("deeper"));
    var target = outside.resolve("launcher");
    Files.writeString(target, "# wpilog-mcp 9.0.0 launcher\n");
    var bridge = bin.resolve("bridge");
    var current = bin.resolve(WINDOWS ? "wpilog-mcp.bat" : "wpilog-mcp");
    try {
      Files.createSymbolicLink(bridge, deeper);
      Files.createSymbolicLink(current, Path.of("bridge").resolve("..").resolve("launcher"));
    } catch (UnsupportedOperationException | IOException e) {
      Assumptions.abort("Symlinks unavailable: " + e.getMessage());
    }
    Assumptions.assumeTrue(Files.exists(current) && Files.isSameFile(current, target),
        "This filesystem does not resolve the symlink's parent component through its target");
    if (!exists) Files.delete(target);
    var input = source();
    var error = assertThrows(IOException.class,
        () -> InstallCommand.install(input, "1.0.0", options(root, false), WINDOWS, ""));
    assertTrue(error.getMessage().contains(current.toString()), error.getMessage());
    assertEquals(exists, Files.exists(target));
    if (exists) assertEquals("# wpilog-mcp 9.0.0 launcher\n", Files.readString(target));
    assertFalse(Files.exists(root.resolve("install.lock")));
  }

  @ParameterizedTest
  @CsvSource({
      "2.10.0,2.9.9,1", "2.9.9,2.10.0,-1", "3.0.0,2.99.99,1", "1.0.1,1.0.0,1",
      "1.0,1.0.0,0", "1.0.0,1.0.0,0", "1.0.0,1.0.0-dev3,1", "1.0.0-dev3,1.0.0,-1",
      "1.0.0-dev10,1.0.0-dev2,1", "1.0.0-dev2,1.0.0-dev3,-1", "1.0.0-dev3,1.0.0-dev3,0",
      "1.0.0-rc1,1.0.0-dev99,1", "1.0.0-dev3.1,1.0.0-dev3,1",
      "999999999999999999999.0,2.0,1"
  })
  void versionOrdering(String version, String before, int expected) {
    assertEquals(expected, Integer.signum(InstallCommand.compareVersions(version, before)));
  }

  @ParameterizedTest
  @CsvSource({
      "2.10.0,2.9.9,false,true", "2.9.9,2.10.0,false,false", "2.10.0,2.10.0,false,false",
      "2.10.0,2.10.0-dev2,false,true", "2.10.0-dev3,2.10.0,false,false",
      "2.10.0-dev3,2.10.0-dev2,false,true", "2.10.0-dev2,2.10.0-dev3,false,false",
      "2.9.9,2.10.0,true,true", "2.10.0,2.10.0,true,true"
  })
  void repointing(String version, String before, boolean force, boolean expected) throws Exception {
    var root = temp.resolve("install");
    var bin = Files.createDirectories(root.resolve("bin"));
    var current = bin.resolve(WINDOWS ? "wpilog-mcp.bat" : "wpilog-mcp");
    var oldText = (WINDOWS ? "REM " : "# ") + "wpilog-mcp " + before + " launcher\n";
    Files.writeString(current, oldText);
    var input = source();
    var result = InstallCommand.install(input, version, options(root, force), WINDOWS, "");
    assertAll(
        () -> assertEquals(expected, result.repointed()),
        () -> assertEquals(before, result.launcher_version_before()),
        () -> assertEquals(expected ? version : before, result.launcher_version_after()),
        () -> assertEquals(expected ? version : before, InstallCommand.launcherVersion(Files.readString(current))),
        () -> assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(root.resolve("jars").resolve("wpilog-mcp-" + version + ".jar"))),
        () -> assertTrue(Files.isRegularFile(bin.resolve("wpilog-mcp-" + version + (WINDOWS ? ".bat" : "")))));
    if (!expected) {
      assertEquals(oldText, Files.readString(current));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void layoutAndSummary(boolean windows) throws Exception {
    // Windows CI exercises its native layout; Unix can also inspect the batch layout's files.
    if (WINDOWS && !windows) {
      Assumptions.abort("POSIX symlinks require privileges on Windows");
    }
    var root = temp.resolve("install with spaces").toAbsolutePath();
    var input = source();
    var result = InstallCommand.install(input, "2.10.0-dev3", options(root, false), windows, "");
    var jar = root.resolve("jars").resolve("wpilog-mcp-2.10.0-dev3.jar");
    var versioned = root.resolve("bin").resolve("wpilog-mcp-2.10.0-dev3" + (windows ? ".bat" : ""));
    var current = root.resolve("bin").resolve(windows ? "wpilog-mcp.bat" : "wpilog-mcp");
    assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(jar));
    if (windows) {
      assertFalse(Files.isSymbolicLink(current));
      assertEquals(Files.readString(versioned), Files.readString(current));
      assertFalse(Files.readString(current).replace("\r\n", "").contains("\n"));
    } else {
      assertEquals(versioned.getFileName(), Files.readSymbolicLink(current));
      assertTrue(Files.isExecutable(versioned));
    }
    assertEquals("2.10.0-dev3", InstallCommand.launcherVersion(Files.readString(versioned)));
    var json = JsonParser.parseString(result.json()).getAsJsonObject();
    assertEquals(9, json.size());
    assertEquals(root.toString(), json.get("install_dir").getAsString());
    assertEquals("2.10.0-dev3", json.get("installed_version").getAsString());
    assertTrue(json.get("launcher_version_before").isJsonNull());
    assertEquals("2.10.0-dev3", json.get("launcher_version_after").getAsString());
    assertTrue(json.get("repointed").getAsBoolean());
    assertTrue(json.get("config_created").getAsBoolean());
    assertEquals(root.resolve("servers.yaml").toString(), json.get("config_path").getAsString());
    assertEquals(current.toString(), json.get("launcher_path").getAsString());
    assertEquals(root.resolve("bin").toString(), json.get("path_hint").getAsString());
  }

  @Test void sameVersionDoesNotRewriteFilesAndExistingConfigsWin() throws Exception {
    var root = temp.resolve("install");
    var input = source();
    var first = InstallCommand.install(input, "1.0.0", options(root, false), WINDOWS, "");
    var jar = root.resolve("jars").resolve("wpilog-mcp-1.0.0.jar");
    var launcher = Path.of(first.launcher_path());
    var versioned = root.resolve("bin").resolve("wpilog-mcp-1.0.0" + (WINDOWS ? ".bat" : ""));
    var old = FileTime.fromMillis(1_600_000_000_000L);
    Files.setLastModifiedTime(jar, old);
    Files.setLastModifiedTime(launcher, old);
    Files.setLastModifiedTime(versioned, old);
    var config = Path.of(first.config_path());
    Files.writeString(config, "team: 2363\n# user's configuration\n");
    var second = InstallCommand.install(input, "1.0.0", options(root, false), WINDOWS, root.resolve("bin").toString());
    assertAll(
        () -> assertFalse(second.repointed()),
        () -> assertFalse(second.config_created()),
        () -> assertEquals(old, Files.getLastModifiedTime(jar)),
        () -> assertEquals(old, Files.getLastModifiedTime(launcher)),
        () -> assertEquals(old, Files.getLastModifiedTime(versioned)),
        () -> assertEquals("team: 2363\n# user's configuration\n", Files.readString(config)),
        () -> assertNull(second.path_hint()),
        () -> assertTrue(second.text().contains("Kept current launcher: 1.0.0")));
    // Installing from the already installed JAR is safe too.
    assertFalse(InstallCommand.install(jar, "1.0.0", options(root, false), WINDOWS, "").repointed());
  }

  @Test void seedsConfigurationAndPreservesLegacyJson() throws Exception {
    var root = temp.resolve("seeded");
    var logs = List.of(temp.resolve("logs #1").toString(), temp.resolve("quote'and space").toString());
    var input = source();
    var result = InstallCommand.install(input, "1.0.0",
        new InstallCommand.Options(root, logs, 2363, false, true), WINDOWS, "");
    var config = new ConfigLoader().load("http", Path.of(result.config_path()));
    assertEquals(logs, config.logdirs());
    assertEquals(2363, config.team());
    assertNull(config.tbaKey());
    assertFalse(Files.readString(Path.of(result.config_path())).matches("(?s).*\\n\\s*tba_key:.*"));
    var legacy = Files.createDirectories(temp.resolve("legacy"));
    var json = Files.writeString(legacy.resolve("servers.json"), "{\"team\": 5678}");
    var kept = InstallCommand.install(input, "1.0.0", options(legacy, true), WINDOWS, "");
    assertFalse(kept.config_created());
    assertEquals(json.toString(), kept.config_path());
    assertEquals("{\"team\": 5678}", Files.readString(json));
    assertFalse(Files.exists(legacy.resolve("servers.yaml")));
  }

  @Test void unreadableMarkerIsOlderAndParsingRejectsBadArguments() throws Exception {
    var root = temp.resolve("install");
    var bin = Files.createDirectories(root.resolve("bin"));
    Files.writeString(bin.resolve(WINDOWS ? "wpilog-mcp.bat" : "wpilog-mcp"), "unmarked launcher");
    var result = InstallCommand.install(source(), "1.0.0", options(root, false), WINDOWS, "");
    assertNull(result.launcher_version_before());
    assertTrue(result.repointed());
    assertNull(InstallCommand.launcherVersion("# wpilog-mcp ../../evil launcher\n"));
    assertEquals("1.2.3-dev2", InstallCommand.launcherVersion("@echo off\r\nREM wpilog-mcp 1.2.3-dev2 launcher\r\n"));
    var parsed = InstallCommand.parse(new String[]{"install", "--install-dir", root.toString(),
        "--logdir", "one", "--logdir", "two", "--team", "2363", "--force", "--json"});
    assertEquals(new InstallCommand.Options(root, List.of("one", "two"), 2363, true, true), parsed);
    assertEquals(Path.of(System.getProperty("user.home"), ".wpilog-mcp"), InstallCommand.parse(new String[]{"install"}).directory());
    for (var bad : List.of(new String[]{"install", "--logdir"}, new String[]{"install", "--team", "x"},
        new String[]{"install", "--team", "0"}, new String[]{"install", "--logdir", "--json"},
        new String[]{"install", "--key", "secret"})) {
      assertThrows(IllegalArgumentException.class, () -> InstallCommand.parse(bad));
    }
  }

  @Test void aHeldInstallLockAndUnwritableLayoutAreExplained() throws Exception {
    var root = Files.createDirectories(temp.resolve("install"));
    var input = source();
    try (var channel = FileChannel.open(root.resolve("install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        var lock = channel.lock()) {
      var error = assertThrows(IOException.class,
          () -> InstallCommand.install(input, "1.0.0", options(root, false), WINDOWS, ""));
      assertTrue(error.getMessage().contains("Another installation is in progress"));
      assertFalse(Files.exists(root.resolve("bin")));
    }
    Files.writeString(root.resolve("jars"), "not a directory");
    assertThrows(IOException.class,
        () -> InstallCommand.install(input, "1.0.0", options(root, false), WINDOWS, ""));
  }

  @Test void pathHintUsesWholeNormalizedEntriesAndWindowsCaseRules() {
    var bin = temp.toAbsolutePath().resolve("bin");
    assertTrue(InstallCommand.onPath(bin, bin.resolve(".").toString(), false));
    assertTrue(InstallCommand.onPath(bin, temp + ";" + bin.toString().toUpperCase(), true));
    assertFalse(InstallCommand.onPath(bin, bin + "-other", WINDOWS));
    assertFalse(InstallCommand.onPath(bin, null, WINDOWS));
  }
}
