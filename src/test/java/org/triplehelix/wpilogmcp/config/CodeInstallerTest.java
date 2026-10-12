/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.Version;

/**
 * Every platform's editor layout is exercised on every platform; native CLI execution is covered
 * by InstallerTest. The seasons are fixed years, so nothing depends on the calendar: 0.10.0-dev3
 * looked only at the current year's folder.
 */
class CodeInstallerTest {
  private static final String ENGINE = "^1.101.0";
  private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
  @TempDir Path temp;

  private static Path executable(Path file) throws Exception {
    Files.createDirectories(file.getParent());
    Files.writeString(file, "fake");
    assertTrue(file.toFile().setExecutable(true));
    return file;
  }

  /** WPILib's layout on Linux and Windows: the version is in resources/app beside bin. */
  private static Path wpilibEditor(Path root, int season, boolean windows, String version) throws Exception {
    var vscode = root.resolve("wpilib").resolve(Integer.toString(season)).resolve("vscode");
    if (version != null) {
      manifest(vscode.resolve("resources").resolve("app"), version);
    }
    return executable(vscode.resolve("bin").resolve(windows ? "code.cmd" : "code"));
  }

  /** WPILib's layout on a Mac: an app bundle whose name WPILib chooses, version beside bin. */
  private static Path bundle(Path folder, String name, String version) throws Exception {
    var app = folder.resolve(name).resolve("Contents").resolve("Resources").resolve("app");
    if (version != null) {
      manifest(app, version);
    }
    return executable(app.resolve("bin").resolve("code"));
  }

  private static void manifest(Path app, String version) throws Exception {
    Files.createDirectories(app);
    Files.writeString(app.resolve("package.json"), "{\"name\": \"code\", \"version\": \"" + version + "\"}");
  }

  private CodeInstaller.Places places(boolean windows, Path home, List<Path> systemEditors, String searchPath) {
    return new CodeInstaller.Places(windows, home, temp.resolve("public"), systemEditors, searchPath);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void everySeasonIsFoundNewestFirstWithoutPath(boolean windows) throws Exception {
    var home = temp.resolve("home with spaces");
    var older = wpilibEditor(home, 2025, windows, "1.96.2");
    var newer = wpilibEditor(home, 2026, windows, "1.105.1");
    Files.createDirectories(home.resolve("wpilib").resolve("tools"));
    Files.createDirectories(home.resolve("wpilib").resolve("2024").resolve("jdk"));
    var places = places(windows, home, List.of(), null);
    assertEquals(List.of(new CodeInstaller.Editor(2026, "1.105.1", newer), new CodeInstaller.Editor(2025, "1.96.2", older)),
        CodeInstaller.wpilib(places));
    assertEquals(newer, CodeInstaller.choose(places, new CodeInstaller.Choice.Newest(), ENGINE).cli(),
        "the newest season is the default, whatever the calendar says");
    if (windows) {
      // WPILib for all users lives under Public; for one season it comes before the user's own
      var shared = wpilibEditor(temp.resolve("public"), 2026, true, "1.105.1");
      assertEquals(shared, CodeInstaller.wpilib(places).get(0).cli());
    }
  }

  @Test
  void aMacBundleIsFoundByItsContentsNotItsName() throws Exception {
    var home = temp.resolve("home");
    var folder = home.resolve("wpilib").resolve("2026").resolve("vscode");
    var cli = bundle(folder, "WPI Code 2026.app", "1.105.1");
    Files.createDirectories(folder.resolve("code-portable-data").resolve("extensions"));
    Files.createDirectories(folder.resolve("Empty.app"));
    var older = bundle(home.resolve("wpilib").resolve("2025").resolve("vscode"), "WPI Code 2025.app", "1.96.2");
    assertEquals(List.of(new CodeInstaller.Editor(2026, "1.105.1", cli), new CodeInstaller.Editor(2025, "1.96.2", older)),
        CodeInstaller.wpilib(places(false, home, List.of(), null)));
  }

  @Test
  void editorsTheExtensionRefusesAreNeitherOfferedNorChosen() throws Exception {
    var home = temp.resolve("home");
    wpilibEditor(home, 2025, WINDOWS, "1.96.2");
    var current = wpilibEditor(home, 2026, WINDOWS, "1.105.1");
    var places = places(WINDOWS, home, List.of(), null);
    var listing = CodeInstaller.list(places, ENGINE);
    assertEquals(List.of(new CodeInstaller.Editor(2026, "1.105.1", current)), listing.offered());
    assertEquals(List.of("WPILib VS Code 2025 (version 1.96.2) is older than the extension needs (VS Code ^1.101.0),"
        + " so it is not offered."), listing.notes());
    assertEquals(current, CodeInstaller.choose(places, new CodeInstaller.Choice.Newest(), ENGINE).cli());
    var named = assertThrows(IOException.class, () -> CodeInstaller.choose(places, new CodeInstaller.Choice.Season(2025), ENGINE));
    assertEquals("WPILib VS Code 2025 (version 1.96.2) is older than the extension needs (VS Code ^1.101.0).", named.getMessage());
    var missing = assertThrows(IOException.class, () -> CodeInstaller.choose(places, new CodeInstaller.Choice.Season(2024), ENGINE));
    assertTrue(missing.getMessage().startsWith("WPILib VS Code 2024 was not found; found WPILib VS Code 2026 (version 1.105.1);"
        + " WPILib VS Code 2025 (version 1.96.2)"), missing.getMessage());
    Files.delete(current);
    var none = assertThrows(IOException.class, () -> CodeInstaller.choose(places, new CodeInstaller.Choice.Newest(), ENGINE));
    assertTrue(none.getMessage().startsWith("No WPILib VS Code accepts the extension: WPILib VS Code 2025"), none.getMessage());
    assertTrue(none.getMessage().endsWith("or name another VS Code with --vscode system."), none.getMessage());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void anotherVsCodeIsUsedOnlyWhenNamed(boolean windows) throws Exception {
    var home = Files.createDirectories(temp.resolve("home"));
    var name = windows ? "code.cmd" : "code";
    var onPath = executable(temp.resolve("path").resolve(name));
    String path = temp.resolve("absent") + (windows ? ";" : File.pathSeparator) + onPath.getParent();
    var places = places(windows, home, List.of(temp.resolve("absent").resolve(name)), path);
    var refused = assertThrows(IOException.class, () -> CodeInstaller.choose(places, new CodeInstaller.Choice.Newest(), ENGINE));
    assertEquals("No WPILib VS Code was found in " + home.resolve("wpilib")
        + ". Install WPILib, or name another VS Code with --vscode system.", refused.getMessage().replace(
            temp.resolve("public").resolve("wpilib") + " or ", ""));
    assertTrue(CodeInstaller.list(places, ENGINE).offered().isEmpty(), "nothing is offered without WPILib's VS Code");
    assertEquals(new CodeInstaller.Editor(null, null, onPath), CodeInstaller.choose(places, new CodeInstaller.Choice.SystemVsCode(), ENGINE));
    // A usual install location comes before PATH, and a WPILib editor does not change the opt-in
    var installed = executable(temp.resolve("Programs").resolve(name));
    wpilibEditor(home, 2026, windows, "1.105.1");
    var withInstalled = places(windows, home, List.of(installed), path);
    assertEquals(installed, CodeInstaller.choose(withInstalled, new CodeInstaller.Choice.SystemVsCode(), ENGINE).cli());
    assertEquals(2026, CodeInstaller.choose(withInstalled, new CodeInstaller.Choice.Newest(), ENGINE).season());
    var nothing = places(windows, home, List.of(), null);
    var absent = assertThrows(IOException.class, () -> CodeInstaller.choose(nothing, new CodeInstaller.Choice.SystemVsCode(), ENGINE));
    assertTrue(absent.getMessage().startsWith("No VS Code was found for --vscode system."), absent.getMessage());
  }

  @Test
  void aSystemEditorsVersionIsReadThroughTheLinkOnPath() throws Exception {
    assumeFalse(WINDOWS, "symbolic links need privileges on Windows");
    var app = temp.resolve("usr share code");
    manifest(app.resolve("resources").resolve("app"), "1.141.0");
    var cli = executable(app.resolve("bin").resolve("code"));
    var bin = Files.createDirectories(temp.resolve("usr bin"));
    Files.createSymbolicLink(bin.resolve("code"), cli);
    assertEquals("1.141.0", CodeInstaller.version(bin.resolve("code")));
    var mac = bundle(temp.resolve("Applications"), "Visual Studio Code.app", "1.141.0");
    assertEquals("1.141.0", CodeInstaller.version(mac));
    Files.writeString(app.resolve("resources").resolve("app").resolve("package.json"), "{\"version\": [1]}");
    assertNull(CodeInstaller.version(cli), "a version that is not a dotted number is unknown");
    Files.writeString(app.resolve("resources").resolve("app").resolve("package.json"), "not json");
    assertNull(CodeInstaller.version(cli));
  }

  @Test
  void pathEntriesAreReadAsTheSystemReadsThem() throws Exception {
    var home = Files.createDirectories(temp.resolve("home"));
    // Windows reads a quoted entry as the folder inside the quotes
    var windowsCli = executable(temp.resolve("quoted").resolve("code.cmd"));
    var quoted = places(true, home, List.of(), "\"" + windowsCli.getParent() + "\"");
    assertEquals(windowsCli, CodeInstaller.choose(quoted, new CodeInstaller.Choice.SystemVsCode(), ENGINE).cli());
    // An entry no file system can name is passed over, not reported as a bad --vscode argument
    var cli = executable(temp.resolve("named").resolve("code"));
    var unnamed = places(false, home, List.of(), "bad\0entry" + File.pathSeparator + cli.getParent());
    assertEquals(cli, CodeInstaller.choose(unnamed, new CodeInstaller.Choice.SystemVsCode(), ENGINE).cli());
  }

  @Test
  void aVersionThatCannotBeReadIsOfferedAndNamedUnknown() throws Exception {
    var home = temp.resolve("home");
    var cli = wpilibEditor(home, 2026, WINDOWS, null);
    var places = places(WINDOWS, home, List.of(), null);
    assertEquals(List.of(new CodeInstaller.Editor(2026, null, cli)), CodeInstaller.list(places, ENGINE).offered());
    assertEquals("WPILib VS Code 2026 (version unknown)", CodeInstaller.wpilib(places).get(0).label());
    assertEquals("VS Code (version 1.141.0)", new CodeInstaller.Editor(null, "1.141.0", cli).label());
  }

  @Test
  void theEngineRangeSetsTheLowestVersionAccepted() {
    assertTrue(CodeInstaller.accepts("1.101.0", "^1.101.0"));
    assertTrue(CodeInstaller.accepts("1.105.1", "^1.101.0"));
    assertFalse(CodeInstaller.accepts("1.96.2", "^1.101.0"));
    assertFalse(CodeInstaller.accepts("1.100.9", ">=1.101.0"));
    assertTrue(CodeInstaller.accepts("1.101", "~1.101.0"), "a missing part counts as zero");
    assertFalse(CodeInstaller.accepts("1.9.0", "1.10.0"), "numeric, not lexical");
    assertTrue(CodeInstaller.accepts("0.1.0", "*"), "a range without a version accepts every editor");
    assertTrue(CodeInstaller.accepts(null, "^1.101.0"), "an unknown version is offered");
    assertTrue(CodeInstaller.accepts("1.96.2", null));
    assertEquals(0, CodeInstaller.compare("1.101.0", "1.101"));
  }

  @Test
  void theBuildCarriesTheExtensionsEngineRange() throws Exception {
    var manifest = com.google.gson.JsonParser.parseString(Files.readString(Path.of("vscode-extension", "package.json")))
        .getAsJsonObject();
    assertEquals(manifest.getAsJsonObject("engines").get("vscode").getAsString(), Version.VSCODE_ENGINE);
  }

  @Test
  void choicesNameASeasonOrTheSystemEditor() {
    assertEquals(new CodeInstaller.Choice.Season(2026), CodeInstaller.Choice.parse("2026"));
    assertEquals(new CodeInstaller.Choice.SystemVsCode(), CodeInstaller.Choice.parse("system"));
    for (var bad : List.of("26", "20260", "System", "latest", "")) {
      assertThrows(IllegalArgumentException.class, () -> CodeInstaller.Choice.parse(bad), bad);
    }
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
