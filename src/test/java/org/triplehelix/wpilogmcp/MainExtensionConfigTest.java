/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The server as the VS Code extension registers it for Claude Code: {@code start default --config
 * <file>}, where the file is the extension's settings in the configuration format the standalone
 * install uses (see buildServerConfig in vscode-extension/src/mcpJson.ts), in a fresh JVM.
 */
@DisplayName("Main with the VS Code extension's configuration file")
class MainExtensionConfigTest {

  @TempDir
  Path tempDir;

  /** The extension's own disk cache (here in the test's directory, never the user's). */
  private Path cacheDir() {
    return tempDir.resolve("extension-storage").resolve("cache");
  }

  /** The file as the extension writes it. */
  private Path writeConfig(List<Path> logDirs, String tbaKey) throws Exception {
    var server = new JsonObject();
    server.addProperty("transport", "stdio");
    var dirs = new JsonArray();
    logDirs.forEach(d -> dirs.add(d.toString()));
    server.add("logdir", dirs);
    server.addProperty("team", 2363);
    if (tbaKey != null) server.addProperty("tba_key", tbaKey);
    server.addProperty("diskcachedir", cacheDir().toString());
    var servers = new JsonObject();
    servers.add("default", server);
    var root = new JsonObject();
    root.add("servers", servers);
    return Files.writeString(tempDir.resolve("servers.json"), root.toString());
  }

  @Test
  @DisplayName("every log directory and the TBA key come from the file; the key is never logged")
  void readsEverything() throws Exception {
    var a = Files.createDirectories(tempDir.resolve("riologs"));
    var b = Files.createDirectories(tempDir.resolve("archive"));
    var config = writeConfig(List.of(a, b), "secret-key-1234");

    var output = MainProcess.run(tempDir, List.of("start", "default", "--config",
        config.toString()), Map.of());
    assertTrue(output.contains("Loaded configuration 'default' from " + config), output);
    assertTrue(output.contains("Configuring log directory: " + a), output);
    assertTrue(output.contains("Configuring log directory: " + b), output);
    assertTrue(output.contains("TBA enrichment enabled"), output);
    assertFalse(output.contains("secret-key-1234"), output);
  }

  @Test
  @DisplayName("the disk cache is the extension's own, never the standalone install's")
  void ownDiskCache() throws Exception {
    var config = writeConfig(List.of(Files.createDirectories(tempDir.resolve("logs"))), null);
    var output = MainProcess.run(tempDir, List.of("start", "default", "--config",
        config.toString()), Map.of());
    assertTrue(output.contains("Cache directory: " + cacheDir()), output);
    assertTrue(Files.isDirectory(cacheDir()), "the cache directory is created");
  }

  @Test
  @DisplayName("the server VS Code starts (flags, not a file) uses the extension's cache too")
  void ownDiskCacheByFlag() throws Exception {
    var logs = Files.createDirectories(tempDir.resolve("logs"));
    var output = MainProcess.run(tempDir, List.of("-logdir", logs.toString(), "-diskcachedir",
        cacheDir().toString()), Map.of());
    assertTrue(output.contains("Cache directory: " + cacheDir()), output);
  }

  @Test
  @DisplayName("without a key in the file, TBA is off")
  void noKey() throws Exception {
    var config = writeConfig(List.of(Files.createDirectories(tempDir.resolve("logs"))), null);
    var output = MainProcess.run(tempDir, List.of("start", "default", "--config",
        config.toString()), Map.of());
    assertTrue(output.contains("TBA enrichment disabled"), output);
  }
}
