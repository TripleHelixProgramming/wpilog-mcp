/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Prints reviewable units. Installing users, configuration and units belongs to the operator. */
public final class ServiceUnit {
  private ServiceUnit() {}

  public static int run(String[] args, PrintStream out, PrintStream err) {
    try {
      if (args.length != 2 && !(args.length == 4 && args[2].equals("--config")))
        throw new IllegalArgumentException("Usage: wpilog-mcp service-unit <name> [--config <file>]");
      validateName(args[1]);
      var loaded = new ConfigLoader().loadDetailed(args[1], args.length == 4 ? Path.of(args[3]) : null);
      if (!loaded.config().isHttp()) throw new IllegalArgumentException("service-unit requires transport: http");
      if (loaded.config().idleExit().isPresent()) throw new IllegalArgumentException("service-unit requires idle_exit_minutes: 0 (the supervisor owns its lifetime)");
      for (var file : render(args[1], loaded.config().effectivePort(), loaded.file()).entrySet()) {
        out.println("# file: " + file.getKey());
        out.println(file.getValue());
      }
      return 0;
    } catch (ConfigException | IOException | IllegalArgumentException e) {
      err.println("Cannot print service units: " + e.getMessage()); return 1;
    }
  }

  static Map<String, String> render(String name, int port, Path config) throws IOException {
    validateName(name);
    var files = new LinkedHashMap<String, String>();
    for (String template : new String[] {"server.service", "health.service", "health.timer"}) {
      try (var in = ServiceUnit.class.getResourceAsStream("/service/" + template)) {
        if (in == null) throw new IOException("Missing service template " + template);
        String filename = "wpilog-mcp-" + name + (template.equals("server.service") ? ".service" : "-" + template);
        files.put(filename, new String(in.readAllBytes(), StandardCharsets.UTF_8)
            .replace("@NAME@", name).replace("@PORT@", Integer.toString(port))
            .replace("@CONFIG@", argument(config.toAbsolutePath().normalize().toString())));
      }
    }
    return files;
  }

  private static void validateName(String name) {
    if (!name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
      throw new IllegalArgumentException("service-unit name must be 1 to 64 ASCII letters, digits, underscores or hyphens, starting with a letter or digit");
  }

  /** systemd parses arguments and expands specifiers/environment even without a shell. */
  private static String argument(String value) {
    if (value.chars().anyMatch(c -> c < 32 || c == 127))
      throw new IllegalArgumentException("service-unit config path must not contain control characters");
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%").replace("$", "$$") + "\"";
  }
}
