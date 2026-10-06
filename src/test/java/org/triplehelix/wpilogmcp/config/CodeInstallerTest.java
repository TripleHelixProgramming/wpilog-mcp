/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Platform path rules are exercised everywhere; native CLI execution is covered by InstallerTest. */
class CodeInstallerTest {
  @TempDir Path temp;

  private Path executable(Path file) throws Exception {
    Files.createDirectories(file.getParent());
    Files.writeString(file, "fake");
    assertTrue(file.toFile().setExecutable(true));
    return file;
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void lookupPrefersCurrentWpilibThenPath(boolean windows) throws Exception {
    var home = temp.resolve("home with spaces");
    var publicHome = temp.resolve("public");
    var name = windows ? "code.cmd" : "code";
    var fallback = executable(temp.resolve("path").resolve(name));
    String path = temp.resolve("absent") + (windows ? ";" : File.pathSeparator) + fallback.getParent();
    var older = executable(home.resolve("wpilib").resolve("2025").resolve("vscode").resolve("bin").resolve(name));
    assertEquals(fallback, CodeInstaller.find(windows, home, publicHome, 2026, path));
    var local = executable(home.resolve("wpilib").resolve("2026").resolve("vscode").resolve("bin").resolve(name));
    assertEquals(local, CodeInstaller.find(windows, home, publicHome, 2026, path));
    if (windows) {
      var shared = executable(publicHome.resolve("wpilib").resolve("2026").resolve("vscode").resolve("bin").resolve(name));
      assertEquals(shared, CodeInstaller.find(true, home, publicHome, 2026, path));
      Files.delete(shared);
    }
    Files.delete(local);
    Files.delete(fallback);
    assertTrue(assertThrows(IOException.class,
        () -> CodeInstaller.find(windows, home, publicHome, 2026, path)).getMessage().contains("VS Code was not found"));
  }

  @Test
  void commandPreservesSpacesAndUsesCmdOnWindows() throws Exception {
    var code = temp.resolve("VS Code").resolve("code.cmd").toAbsolutePath();
    var vsix = temp.resolve("matching extension.vsix").toAbsolutePath();
    assertEquals(List.of(code.toString(), "--install-extension", vsix.toString(), "--force"),
        CodeInstaller.command(false, code, vsix));
    assertEquals(List.of("cmd", "/d", "/v:off", "/s", "/c", "\"\"" + code + "\" --install-extension \"" + vsix + "\" --force\""),
        CodeInstaller.command(true, code, vsix));
    assertThrows(IOException.class, () -> CodeInstaller.command(true, code, temp.resolve("%PATH%.vsix")));
  }
}
