/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The VS Code extension is released with the server it bundles, under the same version: the
 * project version in build.gradle. The extension tasks and the release workflow write it into
 * package.json; this keeps the committed files from drifting (they stayed at 0.8.2 while the
 * server moved on).
 */
@DisplayName("VS Code extension version")
class ExtensionVersionTest {

  private static final String FIX = " (run ./gradlew syncExtensionVersion and commit the result)";

  private static JsonObject read(String name) throws Exception {
    return JsonParser.parseString(Files.readString(Path.of("vscode-extension", name)))
        .getAsJsonObject();
  }

  @Test
  @DisplayName("package.json carries the project version")
  void packageJson() throws Exception {
    assertEquals(Version.VERSION, read("package.json").get("version").getAsString(),
        "vscode-extension/package.json" + FIX);
  }

  @Test
  @DisplayName("package-lock.json carries the project version, at its root and for the package")
  void packageLock() throws Exception {
    var lock = read("package-lock.json");
    assertEquals(Version.VERSION, lock.get("version").getAsString(),
        "vscode-extension/package-lock.json" + FIX);
    assertEquals(Version.VERSION, lock.getAsJsonObject("packages").getAsJsonObject("")
        .get("version").getAsString(), "vscode-extension/package-lock.json packages[\"\"]" + FIX);
  }
}
