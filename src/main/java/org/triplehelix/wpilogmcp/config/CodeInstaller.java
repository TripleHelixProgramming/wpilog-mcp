/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** One VS Code lookup serves release and development installs; extension updates never call it. */
final class CodeInstaller {
  private CodeInstaller() {
  }

  static Path find(boolean windows, Path home, Path publicHome, int year, String searchPath)
      throws IOException {
    var candidates = new ArrayList<Path>();
    String executable = windows ? "code.cmd" : "code";
    if (windows) {
      candidates.add(publicHome.resolve("wpilib").resolve(Integer.toString(year))
          .resolve("vscode").resolve("bin").resolve(executable));
    }
    candidates.add(home.resolve("wpilib").resolve(Integer.toString(year))
        .resolve("vscode").resolve("bin").resolve(executable));
    if (searchPath != null) {
      for (var directory : searchPath.split(Pattern.quote(windows ? ";" : File.pathSeparator))) {
        if (!directory.isBlank()) {
          candidates.add(Path.of(directory).resolve(executable));
        }
      }
    }
    return candidates.stream().filter(Files::isRegularFile)
        .filter(path -> windows || Files.isExecutable(path)).findFirst()
        .orElseThrow(() -> new IOException("VS Code was not found. Install WPILib's VS Code or put code on PATH."));
  }

  /** cmd owns .cmd launchers on Windows; quote its single command without exposing shell expansion. */
  static List<String> command(boolean windows, Path code, Path vsix) throws IOException {
    var cli = code.toAbsolutePath().toString();
    var file = vsix.toAbsolutePath().toString();
    if (!windows) {
      return List.of(cli, "--install-extension", file, "--force");
    }
    if (cli.contains("\"") || file.contains("\"") || cli.contains("%") || file.contains("%")
        || cli.contains("\n") || file.contains("\n")) {
      throw new IOException("VS Code paths cannot contain quotes, percent signs, or newlines on Windows");
    }
    return List.of("cmd", "/d", "/v:off", "/s", "/c", "\"\"" + cli + "\" --install-extension \"" + file + "\" --force\"");
  }

  static void install(Path vsix) throws IOException {
    boolean windows = System.getProperty("os.name", "").startsWith("Windows");
    var code = find(windows, Path.of(System.getProperty("user.home")),
        Path.of(System.getenv().getOrDefault("PUBLIC", "C:\\Users\\Public")),
        Year.now().getValue(), System.getenv("PATH"));
    var process = new ProcessBuilder(command(windows, code, vsix)).redirectErrorStream(true).start();
    // Even code's normal output belongs on stderr: install --json owns stdout.
    try (var output = process.getInputStream()) {
      output.transferTo(System.err);
    }
    try {
      int exit = process.waitFor();
      if (exit != 0) {
        throw new IOException("VS Code extension installation failed (exit " + exit + ")");
      }
    } catch (InterruptedException e) {
      process.destroy();
      Thread.currentThread().interrupt();
      throw new IOException("VS Code extension installation interrupted", e);
    }
  }
}
