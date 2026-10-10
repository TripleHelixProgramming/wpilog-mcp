/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.net.ServerSocket;
import java.time.Year;
import java.util.ArrayList;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.config.ConfigLoader;

/**
 * The one-line installers (install.sh, install.ps1) install a release the way {@code ./gradlew
 * install} installs a build: a versioned JAR and launcher, {@code wpilog-mcp[.bat]} pointing at
 * the newest, and the same default {@code servers.yaml}, kept on upgrade. They run here against
 * a fake GitHub in a scratch home folder. Both the current JAR and a tiny JAR with the old
 * argument handling are exercised, so a bootstrap change cannot strand a published release.
 */
@DisplayName("installers")
class InstallerTest {

  @TempDir
  Path tempDir;

  static String read(String file) throws Exception {
    return Files.readString(Path.of(file));
  }

  /** Checks a default configuration file as the server reads it. */
  void checkDefaultConfig(Path file) throws Exception {
    var text = Files.readString(file);
    assertFalse(Pattern.compile("(?m)^\\s*team\\s*:").matcher(text).find(),
        "the team number is the user's to set, not 0 or ours:\n" + text);
    assertTrue(text.contains("# team:"), "the file shows where the team number goes");
    var loader = new ConfigLoader(name -> name.equals("TBA_API_KEY") ? "key-from-env" : null);
    var standard = loader.load("default", file);
    assertNull(standard.team());
    assertEquals("stdio", standard.transport());
    assertEquals(1, standard.logdirs().size());
    assertTrue(standard.logdirs().get(0).endsWith("riologs"), standard.logdirs().toString());
    var http = loader.load("http", file);
    assertTrue(http.isHttp());
    assertEquals(2363, http.port());
    // The key has one place, the top level, which every server inherits: a server's own
    // tba_key looked like the place for it and left the others without one
    assertFalse(Pattern.compile("(?m)^\\s*tba_key\\s*:").matcher(text).find(),
        "no tba_key is set until the user sets one:\n" + text);
    assertTrue(Pattern.compile("(?m)^# tba_key:").matcher(text).find(),
        "the top-level line shows where the key goes");
    assertTrue(text.contains("TBA_API_KEY"), "the file says the environment variable works too");
    for (var name : List.of("default", "http", "stresstest")) {
      assertNull(loader.load(name, file).tbaKey(), name);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("the packaged JAR installs with JSON on stdout even when the JVM prints a banner on stderr")
  void jarInstall(boolean jvmBanner) throws Exception {
    var root = tempDir.resolve("install with spaces");
    var env = jvmBanner ? Map.of("JAVA_TOOL_OPTIONS", "-Dwpilog.install.banner=true") : Map.<String, String>of();
    var first = run(List.of(java(), "-jar", jar().toString(), "install", "--install-dir", root.toString(), "--json"), env, false);
    assertEquals(0, first.exit(), first.output());
    var summary = JsonParser.parseString(first.stdout()).getAsJsonObject();
    if (jvmBanner) {
      assertTrue(first.stderr().contains("-Dwpilog.install.banner=true"), first.output());
    }
    assertEquals(Version.VERSION, summary.get("installed_version").getAsString());
    assertTrue(summary.get("repointed").getAsBoolean());
    assertTrue(summary.get("config_created").getAsBoolean());
    assertEquals(9, summary.size(), first.output());
    assertArrayEquals(Files.readAllBytes(jar()), Files.readAllBytes(root.resolve("jars").resolve("wpilog-mcp-" + Version.VERSION + ".jar")));
    var launcher = Path.of(summary.get("launcher_path").getAsString());
    var launched = run(isWindows() ? List.of("cmd", "/c", launcher.toString(), "--version")
        : List.of(launcher.toString(), "--version"), Map.of("JAVA_HOME", System.getProperty("java.home")), false);
    assertEquals(0, launched.exit(), launched.output());
    assertEquals(List.of("wpilog-mcp version " + Version.VERSION),
        launched.output().lines().filter(line -> line.startsWith("wpilog-mcp version ")).toList());
    checkDefaultConfig(root.resolve("servers.yaml"));
    var again = run(List.of(java(), "-jar", jar().toString(), "install", "--install-dir", root.toString(), "--json"), env, false);
    var second = JsonParser.parseString(again.stdout()).getAsJsonObject();
    assertEquals(0, again.exit(), again.output());
    assertFalse(second.get("repointed").getAsBoolean());
    assertFalse(second.get("config_created").getAsBoolean());
  }

  @Test void commandErrorsDoNotWriteAJsonSuccess() throws Exception {
    var blocked = Files.writeString(tempDir.resolve("not-a-directory"), "kept");
    var failed = run(List.of(java(), "-jar", jar().toString(), "install", "--install-dir", blocked.toString(), "--json"), Map.of(), false);
    assertEquals(1, failed.exit());
    assertTrue(failed.output().contains("Install failed:"), failed.output());
    var bad = run(List.of(java(), "-jar", jar().toString(), "install", "--team", "nope", "--json"), Map.of(), false);
    assertEquals(2, bad.exit());
    assertTrue(bad.output().contains("--team must be a positive integer"), bad.output());
    var classes = MainProcess.run(tempDir, List.of("install", "--install-dir", tempDir.resolve("classes").toString()), Map.of());
    assertTrue(classes.contains("Install must run from a JAR"), classes);
    var root = Files.createDirectories(tempDir.resolve("locked"));
    try (var channel = FileChannel.open(root.resolve("install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        var lock = channel.lock()) {
      var busy = run(List.of(java(), "-jar", jar().toString(), "install", "--install-dir", root.toString(), "--json"), Map.of(), false);
      assertEquals(1, busy.exit());
      assertTrue(busy.output().contains("Another installation is in progress"), busy.output());
    }
  }

  @Test
  void packagedInstallerRefusesSymlinkedBinAndLeavesOutsideSentinelUntouched() throws Exception {
    var root = Files.createDirectories(tempDir.resolve("install"));
    var outside = Files.createDirectories(tempDir.resolve("outside"));
    var sentinel = outside.resolve("wpilog-mcp-" + Version.VERSION + (isWindows() ? ".bat" : ""));
    Files.writeString(sentinel, "untouched sentinel");
    var bin = root.resolve("bin");
    try {
      Files.createSymbolicLink(bin, outside);
    } catch (UnsupportedOperationException | IOException e) {
      Assumptions.abort("Symlinks unavailable: " + e.getMessage());
    }
    var result = run(List.of(java(), "-jar", jar().toString(), "install", "--install-dir", root.toString(),
        "--force", "--json"), Map.of(), false);
    assertAll(
        () -> assertEquals(1, result.exit(), result.output()),
        () -> assertTrue(result.stderr().contains(bin.toString()), result.output()),
        () -> assertTrue(result.stdout().isBlank(), "a failed install has no JSON success"),
        () -> assertEquals("untouched sentinel", Files.readString(sentinel)));
    try (var contents = Files.list(outside)) {
      assertEquals(List.of(sentinel), contents.toList());
    }
    assertFalse(Files.exists(root.resolve("jars")));
    assertFalse(Files.exists(root.resolve("install.lock")));
  }

  @Test
  void refreshStopsItsDaemonAndPreservesSettingsWithoutOldRuntimeFiles() throws Exception {
    var home = Files.createDirectory(tempDir.resolve("home"));
    var root = home.resolve(".wpilog-mcp");
    var env = Map.of("JAVA_TOOL_OPTIONS", "-Duser.home=" + home);
    assertEquals(0, run(List.of(java(), "-jar", jar().toString(), "install", "--install-dir", root.toString()), env, false).exit());
    int port;
    try (var socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    var config = root.resolve("servers.yaml");
    String settings = "# preserved comment\nteam: 2363\nlogdir: []\nservers:\n  http:\n    transport: http\n    port: " + port + "\n";
    Files.writeString(config, settings);
    var started = run(List.of(java(), "-jar", jar().toString(), "start", "http", "--config", config.toString()), env, false);
    assertEquals(0, started.exit(), started.output());
    var pidFile = root.resolve("run").resolve("http.pid");
    long pid = Long.parseLong(Files.readAllLines(pidFile).get(0));
    var daemon = ProcessHandle.of(pid).orElseThrow();
    try {
      assertTrue(daemon.isAlive());
      var refreshed = run(List.of(java(), "-jar", jar().toString(), "install", "--install-dir", root.toString(),
          "--refresh", "--json"), env, false);
      assertEquals(0, refreshed.exit(), refreshed.output());
      var summary = JsonParser.parseString(refreshed.stdout()).getAsJsonObject();
      var backup = Path.of(summary.get("backup_dir").getAsString());
      assertFalse(daemon.isAlive(), "refresh waits for the old JVM to exit before renaming its JAR");
      assertEquals(settings, Files.readString(config));
      assertEquals(settings, Files.readString(backup.resolve("servers.yaml")));
      assertFalse(Files.exists(pidFile));
      assertFalse(Files.exists(root.resolve("logs")));
      assertTrue(Files.isDirectory(backup.resolve("logs")));
    } finally {
      daemon.destroy();
      daemon.onExit().get(15, TimeUnit.SECONDS);
    }
  }

  @Test
  void matchingVsixIsInstalledOnceAndCliOutputDoesNotPolluteJson() throws Exception {
    var home = Files.createDirectory(tempDir.resolve("code home"));
    var code = home.resolve("wpilib").resolve(Integer.toString(Year.now().getValue()))
        .resolve("vscode").resolve("bin").resolve(isWindows() ? "code.cmd" : "code");
    var capture = tempDir.resolve("code-args.txt");
    executable(code, isWindows() ? "@echo off\r\n(echo %~1& echo %~2& echo %~3)>>\"%CODE_ARGS%\"\r\necho code output\r\nexit /b %CODE_EXIT%\r\n"
        : "#!/bin/sh\nprintf '%s\\n' \"$@\" >> \"$CODE_ARGS\"\necho code output\nexit \"${CODE_EXIT:-0}\"\n");
    var vsix = Files.writeString(tempDir.resolve("matching extension.vsix"), "synthetic vsix");
    var root = tempDir.resolve("standalone");
    var env = new HashMap<>(Map.of("JAVA_TOOL_OPTIONS", "-Duser.home=\"" + home + "\"", "PUBLIC", tempDir.resolve("public").toString(),
        "CODE_ARGS", capture.toString(), "CODE_EXIT", "0"));
    var command = List.of(java(), "-jar", jar().toString(), "install", "--install-dir", root.toString(),
        "--with-extension", "--vsix", vsix.toString(), "--json");
    var installed = run(command, env, false);
    assertEquals(0, installed.exit(), installed.output());
    assertTrue(JsonParser.parseString(installed.stdout()).getAsJsonObject().get("repointed").getAsBoolean());
    assertTrue(installed.stderr().contains("code output"));
    assertEquals(List.of("--install-extension", vsix.toString(), "--force"), Files.readAllLines(capture).stream().map(String::strip).toList());
    env.put("CODE_EXIT", "7");
    var failed = run(command, env, false);
    assertNotEquals(0, failed.exit());
    assertTrue(failed.stderr().contains("exit 7"), failed.output());
    assertTrue(failed.stdout().isBlank());
  }

  static Path jar() {
    return Path.of(System.getProperty("install.testJar"));
  }

  static String java() {
    return ProcessHandle.current().info().command().orElse("java");
  }

  @Test
  @DisplayName("install.ps1 is ASCII: Windows PowerShell reads a script without a BOM as ANSI")
  void ps1IsAscii() throws Exception {
    var bytes = Files.readAllBytes(Path.of("install.ps1"));
    for (int i = 0; i < bytes.length; i++) {
      assertTrue(bytes[i] >= 0, "non-ASCII byte at offset " + i);
    }
  }

  // ==================== running them ====================

  static String releaseJson(String version) {
    var base = "https://github.com/TripleHelixProgramming/wpilog-mcp/releases/download/v"
        + version + "/";
    return """
        {
          "tag_name": "v%1$s",
          "assets": [
            {
              "name": "wpilog-analyzer-%1$s.vsix",
              "browser_download_url": "%2$swpilog-analyzer-%1$s.vsix"
            },
            {
              "name": "wpilog-mcp-%1$s-all.jar",
              "browser_download_url": "%2$swpilog-mcp-%1$s-all.jar"
            }
          ],
          "name": "v-unrelated-release-title",
          "zipball_url": "https://api.github.com/repos/TripleHelixProgramming/wpilog-mcp/zipball/v%1$s"
        }
        """.formatted(version, base);
  }

  static String jarUrl(String version) {
    return "https://github.com/TripleHelixProgramming/wpilog-mcp/releases/download/v" + version
        + "/wpilog-mcp-" + version + "-all.jar";
  }

  record Run(int exit, String stdout, String stderr) {
    String output() {
      return stdout + stderr;
    }
  }

  Run run(List<String> command, Map<String, String> env, boolean clearEnv)
      throws Exception {
    var pb = new ProcessBuilder(command);
    if (clearEnv) pb.environment().clear();
    pb.environment().remove("JAVA_HOME");
    pb.environment().remove("WPILOG_MAX_HEAP");
    pb.environment().putAll(env);
    pb.redirectInput(ProcessBuilder.Redirect.from(env.containsKey("FAKE_ANSWERS") ? new File(env.get("FAKE_ANSWERS"))
        : new File(isWindows() ? "NUL" : "/dev/null")));
    var output = Files.createTempFile(tempDir, "process-", ".txt");
    var error = Files.createTempFile(tempDir, "process-error-", ".txt");
    pb.redirectOutput(output.toFile());
    pb.redirectError(error.toFile());
    var process = pb.start();
    try {
      assertTrue(process.waitFor(120, TimeUnit.SECONDS),
          "did not finish: " + Files.readString(output) + Files.readString(error));
      return new Run(process.exitValue(), Files.readString(output), Files.readString(error));
    } finally {
      process.destroyForcibly();
    }
  }

  static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase().contains("win");
  }

  static Path executable(Path file, String text) throws Exception {
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
    assertTrue(file.toFile().setExecutable(true));
    return file;
  }

  /** Answers the release API with {@code $FAKE_RELEASE_JSON}; "downloads" anything else. */
  static final String FAKE_CURL = """
      #!/bin/sh
      set -e
      out=""
      url=""
      while [ $# -gt 0 ]; do
          case "$1" in
              -o) out="$2"; shift 2 ;;
              -H) shift 2 ;;
              -*) shift ;;
              *) url="$1"; shift ;;
          esac
      done
      if [ -n "$FAKE_REQUESTS" ]; then printf '%s\\n' "$url" >> "$FAKE_REQUESTS"; fi
      case "$url" in
          https://api.github.com/*/releases*)
             if [ -n "$FAKE_API_URL" ]; then printf '%s' "$url" > "$FAKE_API_URL"; fi
             cat "$FAKE_RELEASE_JSON" ;;
          *.vsix) cp "$FAKE_VSIX" "$out"
             printf '%s' "$url" > "$FAKE_VSIX_URL"
             printf '%s' "$out" > "$FAKE_VSIX_FILE" ;;
          https://raw.githubusercontent.com/*)
             printf '%s' "$url" > "$FAKE_INSTALLER_URL"
             printf '%s' "$out" > "$FAKE_INSTALLER_FILE"
             cp "$FAKE_INSTALLER" "$out"
             if [ "$FAKE_INSTALLER_FAIL" = "1" ]; then exit 22; fi ;;
          *) cp "$FAKE_JAR" "$out"
             printf '%s' "$url" > "$FAKE_DOWNLOAD_URL"
             printf '%s' "$out" > "$FAKE_DOWNLOAD_FILE" ;;
      esac
      """;

  /** Prints its arguments, one per line, so a test sees how the launcher ran it. */
  static final String FAKE_JAVA = """
      #!/bin/sh
      if [ "$1" = "-version" ]; then
          echo 'openjdk version "17.0.99" (fake)' >&2
          exit 0
      fi
      for arg in "$@"; do printf '%s\\n' "$arg"; done
      """;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisabledOnOs(OS.WINDOWS)
  void shellAssetFieldsDoNotDependOnReleaseJsonFormatting(boolean compact) throws Exception {
    var home = Files.createDirectory(tempDir.resolve("home"));
    var fakeBin = Files.createDirectory(tempDir.resolve("bin"));
    executable(fakeBin.resolve("curl"), FAKE_CURL);
    executable(home.resolve("wpilib/2026/jdk/bin/java"), FAKE_JAVA);
    String version = "1.2.3-dev1";
    String document = releaseJson(version);
    var releaseObject = JsonParser.parseString(document).getAsJsonObject();
    for (String name : List.of("unrelated-all.jar", "unrelated.vsix")) {
      var asset = new com.google.gson.JsonObject();
      asset.addProperty("name", name);
      asset.addProperty("browser_download_url", "https://example.invalid/" + name);
      releaseObject.getAsJsonArray("assets").add(asset);
    }
    document = new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(releaseObject);
    if (compact) document = new Gson().toJson(JsonParser.parseString(document));
    var release = Files.writeString(tempDir.resolve("release.json"), document);
    var requests = tempDir.resolve("requests");
    var env = new HashMap<>(Map.of("HOME", home.toString(), "PATH", fakeBin + ":/usr/bin:/bin",
        "TMPDIR", tempDir.toString(), "FAKE_RELEASE_JSON", release.toString(),
        "FAKE_JAR", Files.writeString(tempDir.resolve("jar"), "fake JAR").toString(),
        "FAKE_VSIX", Files.writeString(tempDir.resolve("vsix"), "fake VSIX").toString(),
        "FAKE_REQUESTS", requests.toString()));
    for (String key : List.of("FAKE_DOWNLOAD_URL", "FAKE_DOWNLOAD_FILE", "FAKE_VSIX_URL", "FAKE_VSIX_FILE")) {
      env.put(key, tempDir.resolve(key).toString());
    }
    var result = run(List.of("sh", Path.of("install.sh").toAbsolutePath().toString(),
        "--non-interactive", "--with-extension"), env, true);
    assertEquals(0, result.exit(), result.output());
    var urls = Files.readAllLines(requests);
    assertEquals(3, urls.size(), urls.toString());
    assertAll(
        () -> assertEquals(jarUrl(version), urls.get(1), "JAR asset URL"),
        () -> assertEquals(jarUrl(version).replace("wpilog-mcp-", "wpilog-analyzer-")
            .replace("-all.jar", ".vsix"), urls.get(2), "VSIX asset URL"),
        () -> assertEquals(List.of("Installing release " + version),
            result.stdout().lines().filter(line -> line.startsWith("Installing release ")).toList(), "tag_name version"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisabledOnOs(OS.WINDOWS)
  @DisplayName("install.sh uses the verb when available and the tagged installer for an older release")
  void installSh(boolean legacy) throws Exception {
    var home = Files.createDirectories(tempDir.resolve("home with spaces"));
    var fakeBin = Files.createDirectories(tempDir.resolve("fakebin"));
    executable(fakeBin.resolve("curl"), FAKE_CURL);
    var version = legacy ? "0.9.1" : Version.VERSION;
    var release = Files.writeString(tempDir.resolve("release.json"), releaseJson(version));
    var downloadedUrl = tempDir.resolve("url.txt");
    var downloadedFile = tempDir.resolve("download.txt");
    var env = new HashMap<>(Map.of("HOME", home.toString(), "PATH", fakeBin + ":/usr/bin:/bin",
        "JAVA_HOME", System.getProperty("java.home"), "TMPDIR", tempDir.toString(), "FAKE_RELEASE_JSON", release.toString(),
        "FAKE_JAR", (legacy ? legacyJar() : jar()).toString(), "FAKE_DOWNLOAD_URL", downloadedUrl.toString(), "FAKE_DOWNLOAD_FILE", downloadedFile.toString()));
    addFallback(env, false);
    var command = List.of("sh", Path.of("install.sh").toAbsolutePath().toString());
    var first = run(command, env, true);
    assertEquals(0, first.exit(), first.output());
    var root = home.resolve(".wpilog-mcp");
    if (legacy) {
      checkFallback(first, env, root, false);
      assertEquals(jarUrl(version), Files.readString(downloadedUrl));
      checkFallbackFailures(command, env, false);
      return;
    }
    assertEquals(List.of("Install path: release " + version + " install command."),
        first.stdout().lines().filter(line -> line.startsWith("Install path:")).toList());
    assertArrayEquals(Files.readAllBytes(jar()), Files.readAllBytes(root.resolve("jars").resolve("wpilog-mcp-" + Version.VERSION + ".jar")));
    assertEquals(jarUrl(Version.VERSION), Files.readString(downloadedUrl));
    assertFalse(Files.exists(Path.of(Files.readString(downloadedFile))), "the temporary download is removed");
    assertEquals(Path.of("wpilog-mcp-" + Version.VERSION), Files.readSymbolicLink(root.resolve("bin").resolve("wpilog-mcp")));
    assertTrue(first.output().contains("Add this directory to PATH: " + root.resolve("bin")), first.output());
    checkDefaultConfig(root.resolve("servers.yaml"));
    var config = root.resolve("servers.yaml");
    Files.writeString(config, "team: 2363\n" + Files.readString(config));
    var second = run(command, env, true);
    assertEquals(0, second.exit(), second.output());
    assertTrue(Files.readString(config).startsWith("team: 2363\n"));
    assertTrue(second.output().contains("Kept current launcher: " + Version.VERSION), second.output());
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void shellLauncherJavaLookupAndHeap() throws Exception {
    var root = tempDir.resolve("install");
    assertEquals(0, run(List.of(java(), "-jar", jar().toString(), "install", "--install-dir", root.toString()), Map.of(), false).exit());
    var home = tempDir.resolve("home with spaces");
    var javaHome = tempDir.resolve("fallback");
    executable(javaHome.resolve("bin").resolve("java"), FAKE_JAVA.replace("for arg", "echo JAVA_HOME\nfor arg"));
    var older = executable(home.resolve("wpilib").resolve("2025").resolve("jdk").resolve("bin").resolve("java"), FAKE_JAVA.replace("for arg", "echo 2025\nfor arg"));
    var newer = executable(home.resolve("wpilib").resolve("2026").resolve("jdk").resolve("bin").resolve("java"), FAKE_JAVA.replace("for arg", "echo 2026\nfor arg"));
    var command = List.of(root.resolve("bin").resolve("wpilog-mcp").toString(), "--version", "argument with spaces");
    var env = Map.of("HOME", home.toString(), "PATH", "/usr/bin:/bin", "JAVA_HOME", javaHome.toString(), "WPILOG_MAX_HEAP", "8g");
    var newest = run(command, env, true).output().lines().toList();
    assertEquals("2026", newest.get(0));
    assertEquals("-Xmx8g", newest.get(1));
    assertEquals(root.resolve("jars").resolve("wpilog-mcp-" + Version.VERSION + ".jar"), Path.of(newest.get(3)).normalize());
    assertEquals(List.of("--version", "argument with spaces"), newest.subList(4, newest.size()));
    Files.delete(newer);
    assertEquals("2025", run(command, env, true).output().lines().findFirst().orElseThrow());
    Files.delete(older);
    assertEquals("JAVA_HOME", run(command, env, true).output().lines().findFirst().orElseThrow());
    var fakeBin = Files.createDirectories(tempDir.resolve("java-on-path"));
    executable(fakeBin.resolve("java"), FAKE_JAVA);
    var fallback = run(command, Map.of("HOME", home.toString(), "PATH", fakeBin + ":/usr/bin:/bin"), true);
    assertEquals("-Xmx4g", fallback.output().lines().findFirst().orElseThrow());
  }

  @Test
  @DisplayName("install.sh refuses a release tag that is not a version")
  @DisabledOnOs(OS.WINDOWS)
  void installShRejectsOddTag() throws Exception {
    var home = Files.createDirectories(tempDir.resolve("home"));
    var fakeBin = Files.createDirectories(tempDir.resolve("fakebin"));
    executable(fakeBin.resolve("curl"), FAKE_CURL);
    executable(home.resolve("wpilib/2026/jdk/bin/java"), FAKE_JAVA);
    var release = Files.writeString(tempDir.resolve("release.json"),
        releaseJson("1.2.3").replace("\"tag_name\": \"v1.2.3\"", "\"tag_name\": \"nightly/x y\""));
    var result = run(List.of("sh", Path.of("install.sh").toAbsolutePath().toString()),
        Map.of("HOME", home.toString(), "PATH", fakeBin + ":/usr/bin:/bin",
            "FAKE_RELEASE_JSON", release.toString()), true);
    assertNotEquals(0, result.exit(), result.output());
    assertFalse(Files.exists(home.resolve(".wpilog-mcp/bin")), result.output());
  }

  /** Windows PowerShell where it is (what irm | iex runs in by default), else pwsh. */
  static String powershell() {
    var names = isWindows() ? List.of("powershell.exe", "pwsh.exe") : List.of("pwsh");
    for (var name : names) {
      for (var dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
        if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, name))) {
          return Path.of(dir, name).toString();
        }
      }
    }
    return null;
  }

  /** Stands in for GitHub: functions take precedence over the cmdlets they are named after. */
  static final String PS_WRAPPER = """
      $ErrorActionPreference = 'Stop'
      function Invoke-RestMethod { param([string]$Uri, $Headers)
          if ($env:FAKE_API_URL) { [System.IO.File]::WriteAllText($env:FAKE_API_URL, $Uri) }
          Get-Content -Raw -Path $env:FAKE_RELEASE_JSON | ConvertFrom-Json }
      function Invoke-WebRequest { param([string]$Uri, [string]$OutFile, [switch]$UseBasicParsing)
          if ($Uri.StartsWith('https://raw.githubusercontent.com/')) {
              [System.IO.File]::WriteAllText($env:FAKE_INSTALLER_URL, $Uri)
              [System.IO.File]::WriteAllText($env:FAKE_INSTALLER_FILE, $OutFile)
              Copy-Item -LiteralPath $env:FAKE_INSTALLER -Destination $OutFile -Force
              if ($env:FAKE_INSTALLER_FAIL -eq "1") { Write-Error "Download interrupted" }
          } elseif ($Uri.EndsWith('.vsix')) {
              Copy-Item -LiteralPath $env:FAKE_VSIX -Destination $OutFile -Force
              [System.IO.File]::WriteAllText($env:FAKE_VSIX_URL, $Uri)
              [System.IO.File]::WriteAllText($env:FAKE_VSIX_FILE, $OutFile)
          } else {
              if ($env:FAKE_DOWNLOAD_URL) { [System.IO.File]::WriteAllText($env:FAKE_DOWNLOAD_URL, $Uri) }
              Copy-Item -LiteralPath $env:FAKE_JAR -Destination $OutFile -Force
              [System.IO.File]::WriteAllText($env:FAKE_DOWNLOAD_FILE, $OutFile)
          } }
      $installerArgs = @()
      if ($env:FAKE_SCRIPT_ARGS) { $installerArgs = @(Get-Content -Raw $env:FAKE_SCRIPT_ARGS | ConvertFrom-Json) }
      if ($env:FAKE_ANSWERS) {
          $global:installerAnswers = [System.Collections.Generic.Queue[string]]::new()
          Get-Content $env:FAKE_ANSWERS | ForEach-Object { $global:installerAnswers.Enqueue($_) }
          function Read-Host { param([string]$Prompt) return $global:installerAnswers.Dequeue() }
      }
      & $env:WPILOG_INSTALLER @installerArgs
      exit $LASTEXITCODE
      """;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("install.ps1 uses the verb when available and the tagged installer for an older release")
  void installPs1(boolean legacy) throws Exception {
    var shell = powershell();
    assumeTrue(shell != null, "PowerShell is not installed");
    var profile = Files.createDirectories(tempDir.resolve("profile with spaces"));
    var wrapper = Files.writeString(tempDir.resolve("run.ps1"), PS_WRAPPER);
    var version = legacy ? "0.9.1" : Version.VERSION;
    var release = Files.writeString(tempDir.resolve("release.json"), releaseJson(version));
    var download = tempDir.resolve("download.txt");
    var env = new HashMap<>(Map.of("USERPROFILE", profile.toString(), "FAKE_RELEASE_JSON", release.toString(),
        "WPILOG_INSTALLER", Path.of("install.ps1").toAbsolutePath().toString(), "FAKE_JAR", (legacy ? legacyJar() : jar()).toString(),
        "FAKE_DOWNLOAD_FILE", download.toString(), "JAVA_HOME", System.getProperty("java.home"),
        "TMPDIR", tempDir.toString(), "TEMP", tempDir.toString(), "TMP", tempDir.toString()));
    addFallback(env, true);
    var command = List.of(shell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", wrapper.toString());
    var result = run(command, env, false);
    assertEquals(0, result.exit(), result.output());
    var root = profile.resolve(".wpilog-mcp");
    if (legacy) {
      checkFallback(result, env, root, true);
      checkFallbackFailures(command, env, true);
      return;
    }
    assertEquals(List.of("Install path: release " + version + " install command."),
        result.stdout().lines().filter(line -> line.startsWith("Install path:")).toList());
    assertArrayEquals(Files.readAllBytes(jar()), Files.readAllBytes(root.resolve("jars").resolve("wpilog-mcp-" + Version.VERSION + ".jar")));
    assertFalse(Files.exists(Path.of(Files.readString(download))));
    checkDefaultConfig(root.resolve("servers.yaml"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void scriptsSelectReleaseBootstrapAndPromptWithoutLosingPaths(boolean ps) throws Exception {
    assumeTrue(ps ? powershell() != null : !isWindows(), "script shell unavailable");
    var home = Files.createDirectory(tempDir.resolve("script home"));
    var fakeBin = Files.createDirectory(tempDir.resolve("script-bin"));
    if (!ps) executable(fakeBin.resolve("curl"), FAKE_CURL);
    var capture = tempDir.resolve("script-code-args");
    executable(home.resolve("wpilib").resolve(Integer.toString(Year.now().getValue()))
        .resolve("vscode").resolve("bin").resolve(isWindows() ? "code.cmd" : "code"),
        isWindows() ? "@echo off\r\n(echo %~1& echo %~2& echo %~3)>>\"%CODE_ARGS%\"\r\n"
        : "#!/bin/sh\nprintf '%s\\n' \"$@\" >> \"$CODE_ARGS\"\n");
    var env = new HashMap<String, String>();
    env.put("HOME", home.toString());
    env.put("USERPROFILE", home.toString());
    env.put("JAVA_HOME", System.getProperty("java.home"));
    env.put("JAVA_TOOL_OPTIONS", "-Duser.home=\"" + home + "\"");
    env.put("PUBLIC", tempDir.resolve("public").toString());
    env.put("PATH", fakeBin + File.pathSeparator + System.getenv("PATH"));
    env.put("CODE_ARGS", capture.toString());
    env.put("FAKE_JAR", jar().toString());
    env.put("FAKE_VSIX", Files.writeString(tempDir.resolve("fake.vsix"), "vsix bytes").toString());
    env.put("FAKE_RELEASE_JSON", tempDir.resolve("script-release.json").toString());
    for (String name : List.of("FAKE_API_URL", "FAKE_DOWNLOAD_URL", "FAKE_DOWNLOAD_FILE", "FAKE_VSIX_URL", "FAKE_VSIX_FILE")) {
      env.put(name, tempDir.resolve(name).toString());
    }
    var wrapper = Files.writeString(tempDir.resolve("script-wrapper.ps1"), PS_WRAPPER);
    env.put("WPILOG_INSTALLER", Path.of("install.ps1").toAbsolutePath().toString());
    var base = ps ? List.of(powershell(), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", wrapper.toString())
        : List.of("sh", Path.of("install.sh").toAbsolutePath().toString());
    for (String selection : List.of("latest", "tag", "pre-release", "interactive")) {
      Files.deleteIfExists(capture);
      var args = new ArrayList<String>();
      String version = selection.equals("latest") ? "1.2.0" : "1.3.0-dev3";
      String release = releaseJson(version);
      if (selection.equals("pre-release")) release = "[" + release + "]";
      Files.writeString(Path.of(env.get("FAKE_RELEASE_JSON")), release);
      String expectedApi = "https://api.github.com/repos/TripleHelixProgramming/wpilog-mcp/releases/";
      if (selection.equals("tag")) {
        args.addAll(List.of("--tag", "v" + version));
        expectedApi += "tags/v" + version;
      } else if (selection.equals("pre-release")) {
        args.add("--pre-release");
        expectedApi = expectedApi.substring(0, expectedApi.length() - 1) + "?per_page=1";
      } else expectedApi += "latest";
      var root = tempDir.resolve("selected " + selection);
      var logs = tempDir.resolve("logs with spaces");
      args.addAll(List.of("--install-dir", root.toString()));
      if (selection.equals("interactive")) {
        args.add("--interactive");
        env.put("FAKE_ANSWERS", Files.writeString(tempDir.resolve("answers"), logs + "\n\n2363\n\n").toString());
      } else {
        args.addAll(List.of("--non-interactive", "--with-extension", "--logdir", logs.toString(), "--team", "2363"));
      }
      env.put("FAKE_SCRIPT_ARGS", Files.writeString(tempDir.resolve("args.json"), new Gson().toJson(args)).toString());
      var command = new ArrayList<>(base);
      if (!ps) command.addAll(args);
      var result = run(command, env, false);
      assertEquals(0, result.exit(), selection + ": " + result.output());
      assertEquals(expectedApi, Files.readString(Path.of(env.get("FAKE_API_URL"))));
      assertEquals(jarUrl(version), Files.readString(Path.of(env.get("FAKE_DOWNLOAD_URL"))));
      assertTrue(Files.readString(Path.of(env.get("FAKE_VSIX_URL"))).endsWith("/v" + version + "/wpilog-analyzer-" + version + ".vsix"));
      var codeArgs = Files.readAllLines(capture).stream().map(String::strip).toList();
      assertEquals(3, codeArgs.size(), "exactly one code invocation");
      assertEquals("--install-extension", codeArgs.get(0));
      assertEquals("--force", codeArgs.get(2));
      assertFalse(Files.exists(Path.of(codeArgs.get(1))), "VSIX download removed");
      assertFalse(Files.exists(Path.of(Files.readString(Path.of(env.get("FAKE_DOWNLOAD_FILE"))))));
      var config = new ConfigLoader().load("http", root.resolve("servers.yaml"));
      assertEquals(2363, config.team());
      assertEquals(List.of(logs.toString()), config.logdirs());
      if (selection.equals("interactive")) {
        // A refresh asks only about the extension; existing settings are copied byte for byte.
        var before = Files.readAllBytes(root.resolve("servers.yaml"));
        Files.writeString(Path.of(env.get("FAKE_ANSWERS")), "n\n");
        args.add("--refresh");
        Files.writeString(Path.of(env.get("FAKE_SCRIPT_ARGS")), new Gson().toJson(args));
        command = new ArrayList<>(base);
        if (!ps) command.addAll(args);
        var refreshed = run(command, env, false);
        assertEquals(0, refreshed.exit(), refreshed.output());
        assertTrue(refreshed.stdout().contains("Previous install kept at:"), refreshed.output());
        assertArrayEquals(before, Files.readAllBytes(root.resolve("servers.yaml")));
        assertEquals(codeArgs, Files.readAllLines(capture).stream().map(String::strip).toList(), "declining does not install again");
      }
      env.remove("FAKE_ANSWERS");
    }
    var invalidArgs = List.of("--non-interactive", "--install-dir", tempDir.resolve("bad-install").toString(), "--team", "0");
    Files.writeString(Path.of(env.get("FAKE_SCRIPT_ARGS")), new Gson().toJson(invalidArgs));
    var command = new ArrayList<>(base);
    if (!ps) command.addAll(invalidArgs);
    var refused = run(command, env, false);
    assertNotEquals(0, refused.exit());
    assertFalse(refused.output().contains("predates"), refused.output());
    assertTrue(refused.output().contains("--team must be a positive integer"), refused.output());
  }

  /** Reproduces pre-install argument handling without embedding or downloading an old release. */
  Path legacyJar() throws Exception {
    var source = Files.writeString(tempDir.resolve("LegacyRelease.java"), """
        public class LegacyRelease {
          public static void main(String[] args) {
            for (String arg : args) {
              if (arg.startsWith("--")) {
                System.err.println("Unknown option: " + arg);
                System.exit(1);
              }
            }
          }
        }
        """);
    var classes = Files.createDirectories(tempDir.resolve("legacy-classes"));
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
        "--release", "17", "-d", classes.toString(), source.toString()));
    var manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "LegacyRelease");
    var jar = tempDir.resolve("legacy.jar");
    try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
      output.putNextEntry(new JarEntry("LegacyRelease.class"));
      Files.copy(classes.resolve("LegacyRelease.class"), output);
      output.closeEntry();
    }
    return jar;
  }

  /** A tagged installer records its execution and inheritance of the guard against recursive fallback. */
  void addFallback(Map<String, String> env, boolean powershell) throws Exception {
    var script = powershell ? """
        [System.IO.File]::WriteAllText($env:FAKE_INSTALLER_RAN, (Join-Path $env:USERPROFILE '.wpilog-mcp'))
        [System.IO.File]::WriteAllText($env:FAKE_INSTALLER_GUARD, $env:WPILOG_INSTALL_FALLBACK)
        """ : """
        printf '%s' "$HOME/.wpilog-mcp" > "$FAKE_INSTALLER_RAN"
        printf '%s' "$WPILOG_INSTALL_FALLBACK" > "$FAKE_INSTALLER_GUARD"
        """;
    env.put("FAKE_INSTALLER", Files.writeString(tempDir.resolve("fallback-installer"), script).toString());
    env.put("FAKE_INSTALLER_RAN", tempDir.resolve("fallback-ran.txt").toString());
    env.put("FAKE_INSTALLER_GUARD", tempDir.resolve("fallback-guard.txt").toString());
    env.put("FAKE_FAILURE_JAVA", java());
    env.put("FAKE_INSTALLER_URL", tempDir.resolve("fallback-url.txt").toString());
    env.put("FAKE_INSTALLER_FILE", tempDir.resolve("fallback-download.txt").toString());
  }

  void checkFallback(Run result, Map<String, String> env, Path root, boolean powershell) throws Exception {
    var url = "https://raw.githubusercontent.com/TripleHelixProgramming/wpilog-mcp/v0.9.1/install."
        + (powershell ? "ps1" : "sh");
    assertEquals(List.of("Install path: release 0.9.1 predates the install command; using " + url + "."),
        result.stdout().lines().filter(line -> line.startsWith("Install path:")).toList());
    assertTrue(result.output().contains("Unknown option: --install-dir"), result.output());
    assertEquals(url, Files.readString(Path.of(env.get("FAKE_INSTALLER_URL"))));
    assertEquals(root, Path.of(Files.readString(Path.of(env.get("FAKE_INSTALLER_RAN")))));
    assertEquals("1", Files.readString(Path.of(env.get("FAKE_INSTALLER_GUARD"))));
    checkDownloadsRemoved(env);
  }

  void checkDownloadsRemoved(Map<String, String> env) throws Exception {
    assertFalse(Files.exists(Path.of(Files.readString(Path.of(env.get("FAKE_DOWNLOAD_FILE"))))), "temporary JAR removed");
    assertFalse(Files.exists(Path.of(Files.readString(Path.of(env.get("FAKE_INSTALLER_FILE"))))), "temporary installer removed");
  }

  /** Failed delegation must fail the bootstrap and remove downloads, including a repeated fallback. */
  void checkFallbackFailures(List<String> command, Map<String, String> env, boolean powershell) throws Exception {
    var ran = Path.of(env.get("FAKE_INSTALLER_RAN"));
    var downloaded = Path.of(env.get("FAKE_INSTALLER_FILE"));
    Files.delete(ran);
    Files.delete(downloaded);
    env.put("WPILOG_INSTALL_FALLBACK", "1");
    var repeated = run(command, env, !powershell);
    assertNotEquals(0, repeated.exit(), repeated.output());
    assertTrue(repeated.output().contains("Release installer fallback already attempted"), repeated.output());
    assertFalse(Files.exists(downloaded), "a repeated fallback does not fetch another installer");
    assertFalse(Files.exists(Path.of(Files.readString(Path.of(env.get("FAKE_DOWNLOAD_FILE"))))));
    env.remove("WPILOG_INSTALL_FALLBACK");

    var installer = Path.of(env.get("FAKE_INSTALLER"));
    // A native failure in PowerShell must be checked even when the script itself returns normally.
    var failure = powershell ? "& $env:FAKE_FAILURE_JAVA -jar $env:FAKE_JAR --legacy-error" : "exit 7";
    var original = Files.readString(installer);
    Files.writeString(installer, original + "\n" + failure + "\n");
    var failed = run(command, env, !powershell);
    assertNotEquals(0, failed.exit(), failed.output());
    assertTrue(Files.exists(ran), "the failing installer ran");
    checkDownloadsRemoved(env);

    Files.delete(ran);
    Files.writeString(installer, original);
    env.put("FAKE_INSTALLER_FAIL", "1"); // leaves executable script text in an incomplete download
    var unavailable = run(command, env, !powershell);
    assertNotEquals(0, unavailable.exit(), unavailable.output());
    assertFalse(Files.exists(ran), "a failed download does not execute an installer");
    checkDownloadsRemoved(env);
  }
}
