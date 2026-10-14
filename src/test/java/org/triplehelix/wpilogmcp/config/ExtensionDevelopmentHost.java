/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.triplehelix.wpilogmcp.Version;

/**
 * Gradle's development launcher uses the installer's editor lookup and argument quoting without
 * installing an extension. It lives with the build helpers so it is never shipped in the server.
 */
public final class ExtensionDevelopmentHost {
  private ExtensionDevelopmentHost() {
  }

  /** The editor selector, extension directory, and optional workspace to open. */
  public static void main(String[] args) throws IOException, InterruptedException {
    if (args.length < 2 || args.length > 3) {
      throw new IllegalArgumentException("Expected: <system|season> <extension-directory> [workspace]");
    }
    var extension = Path.of(args[1]).toAbsolutePath().normalize();
    if (!Files.isRegularFile(extension.resolve("out").resolve("extension.js"))) {
      throw new IOException("Compile the extension before opening it: " + extension);
    }
    var places = CodeInstaller.Places.current();
    var editor = CodeInstaller.choose(places, CodeInstaller.Choice.parse(args[0]), Version.VSCODE_ENGINE);
    var launchArgs = new ArrayList<>(List.of("--new-window", "--extensionDevelopmentPath=" + extension));
    if (args.length == 3) {
      launchArgs.add(Path.of(args[2]).toAbsolutePath().normalize().toString());
    }
    System.out.println("Opening Extension Development Host in " + editor.label());
    var process = new ProcessBuilder(CodeInstaller.command(places.windows(), editor.cli(), launchArgs))
        .inheritIO().start();
    try {
      int exit = process.waitFor();
      if (exit != 0) {
        throw new IOException("VS Code development window failed to launch (exit " + exit + ")");
      }
    } catch (InterruptedException e) {
      process.destroy();
      Thread.currentThread().interrupt();
      throw e;
    }
  }
}
