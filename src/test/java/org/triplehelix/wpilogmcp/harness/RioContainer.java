/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.harness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** A fresh OpenSSH/JRE process namespace. No privileged mode, host proc mount, or NI image. */
public final class RioContainer implements AutoCloseable {
  private final String id;
  private final Path run;
  public RioContainer(String image, Path run, String serial, String comments, int ssh, int nt, int jmx) throws Exception {
    this.run = run;
    id = command("run", "--detach", "--rm", "--init",
        "--publish", "127.0.0.1:" + ssh + ":22",
        "--publish", "127.0.0.1:" + nt + ":" + nt,
        "--publish", "127.0.0.1:" + jmx + ":" + jmx,
        "--mount", "type=bind,source=" + run + ",target=/harness",
        "--env", "HARNESS_SERIAL=" + serial, "--env", "HARNESS_COMMENTS=" + comments,
        "--env", "HARNESS_NT_PORT=" + nt, "--env", "HARNESS_JMX_PORT=" + jmx, image).strip();
  }
  public Process robot(int boot, Path output) throws Exception {
    return new ProcessBuilder("docker", "exec", "--user", "lvuser", id,
        "/home/lvuser/robotCommand", Integer.toString(boot))
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
  }
  public String console() throws Exception { return command("exec", id, "cat", "/var/local/natinst/log/FRC_UserProgram.log"); }
  public String modules() throws Exception { return command("exec", id, "/opt/java/bin/java", "--list-modules"); }
  private static String command(String... arguments) throws Exception {
    var cmd = new ArrayList<>(List.of("docker")); cmd.addAll(List.of(arguments));
    var output = Files.createTempFile("rio-docker-", ".txt");
    try {
      var process = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(output.toFile()).start();
      if (!process.waitFor(60, TimeUnit.SECONDS)) {
        process.destroyForcibly(); throw new java.io.IOException("Docker command exceeded 60 seconds: " + arguments[0]);
      }
      String text = Files.readString(output);
      if (process.exitValue() != 0) throw new java.io.IOException("Docker " + arguments[0] + " failed: " + text);
      return text;
    } finally { Files.deleteIfExists(output); }
  }
  @Override public void close() throws Exception {
    try { Files.writeString(run.resolve("sshd.log"), command("logs", id)); }
    finally {
      try { Files.writeString(run.resolve("console.log"), console()); }
      finally { command("rm", "--force", id); }
    }
  }
}
