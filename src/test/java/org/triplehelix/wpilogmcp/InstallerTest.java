/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
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
 * a fake GitHub (and, for install.sh, a fake java), in a scratch home folder.
 */
@DisplayName("installers")
class InstallerTest {

  @TempDir
  Path tempDir;

  static String read(String file) throws Exception {
    return Files.readString(Path.of(file));
  }

  /** The lines after the line containing {@code start}, up to the line that is {@code end}. */
  static String between(String text, String start, String end) {
    text = text.replace("\r\n", "\n");
    int from = text.indexOf(start);
    assertTrue(from >= 0, "not found: " + start);
    from = text.indexOf('\n', from) + 1;
    int to = text.indexOf("\n" + end + "\n", from - 1);
    assertTrue(to >= 0, "no closing " + end + " after " + start);
    return text.substring(from, to + 1);
  }

  static String shConfig() throws Exception {
    return between(read("install.sh"), "<< 'CONFIG'", "CONFIG");
  }

  /** A literal here-string: in a "@ ... "@ one, PowerShell would expand any $ in the file. */
  static String ps1Config() throws Exception {
    return between(read("install.ps1"), "$configContent = @'", "'@");
  }

  static String gradleConfig() throws Exception {
    return between(read("build.gradle"), "yamlConfig.text = \"\"\"\\", "\"\"\"")
        .replace("\\$", "$");
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

  @Test
  @DisplayName("the default servers.yaml is the same in all three installers, with no team number")
  void defaultConfigs() throws Exception {
    var sh = shConfig();
    assertEquals(sh, gradleConfig(), "install.sh and ./gradlew install");
    assertEquals(sh.replace("/Volumes/LOGS/archive", "D:/frc-logs/archive"), ps1Config(),
        "install.sh and install.ps1 (apart from the example archive path)");
    checkDefaultConfig(Files.writeString(tempDir.resolve("servers.yaml"), sh));
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

  record Run(int exit, String output) {}

  static Run run(List<String> command, Map<String, String> env, boolean clearEnv)
      throws Exception {
    var pb = new ProcessBuilder(command);
    if (clearEnv) pb.environment().clear();
    pb.environment().remove("JAVA_HOME");
    pb.environment().remove("WPILOG_MAX_HEAP");
    pb.environment().putAll(env);
    pb.redirectErrorStream(true);
    pb.redirectInput(ProcessBuilder.Redirect.from(new File(isWindows() ? "NUL" : "/dev/null")));
    var process = pb.start();
    var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(120, TimeUnit.SECONDS), "did not finish:\n" + output);
    return new Run(process.exitValue(), output);
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
      case "$url" in
          https://api.github.com/*/releases*) cat "$FAKE_RELEASE_JSON" ;;
          *) printf 'jar for %s' "$url" > "$out" ;;
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

  @Test
  @DisabledOnOs(OS.WINDOWS)
  @DisplayName("install.sh: versioned JAR and launcher, wpilog-mcp a symlink to the newest")
  void installSh() throws Exception {
    var home = Files.createDirectories(tempDir.resolve("home"));
    var fakeBin = Files.createDirectories(tempDir.resolve("fakebin"));
    executable(fakeBin.resolve("curl"), FAKE_CURL);
    // The launcher prefers the newest WPILib JDK
    executable(home.resolve("wpilib/2026/jdk/bin/java"), FAKE_JAVA);
    var install = home.resolve(".wpilog-mcp");
    var bin = install.resolve("bin");
    // An earlier install.sh wrote the launcher itself here, not a symlink
    executable(bin.resolve("wpilog-mcp"), "#!/bin/sh\necho old launcher\n");
    var installer = Path.of("install.sh").toAbsolutePath().toString();
    var release = tempDir.resolve("release.json");
    Map<String, String> env = Map.of("HOME", home.toString(),
        "PATH", fakeBin + ":/usr/bin:/bin", "FAKE_RELEASE_JSON", release.toString());

    Files.writeString(release, releaseJson("1.2.3"));
    var first = run(List.of("sh", installer), env, true);
    assertEquals(0, first.exit(), first.output());
    var jar123 = install.resolve("jars/wpilog-mcp-1.2.3.jar");
    assertEquals("jar for " + jarUrl("1.2.3"), Files.readString(jar123));
    var launcher123 = bin.resolve("wpilog-mcp-1.2.3");
    assertTrue(Files.isExecutable(launcher123), first.output());
    assertTrue(Files.isSymbolicLink(bin.resolve("wpilog-mcp")), "wpilog-mcp is a symlink");
    assertEquals(Path.of("wpilog-mcp-1.2.3"), Files.readSymbolicLink(bin.resolve("wpilog-mcp")));
    checkDefaultConfig(install.resolve("servers.yaml"));

    var launched = run(List.of(bin.resolve("wpilog-mcp").toString(), "start", "default"),
        Map.of("HOME", home.toString(), "PATH", "/usr/bin:/bin"), true);
    assertEquals(List.of("-Xmx4g", "-jar", jar123.toString(), "start", "default"),
        launched.output().lines().toList());
    var bigHeap = run(List.of(bin.resolve("wpilog-mcp").toString()),
        Map.of("HOME", home.toString(), "PATH", "/usr/bin:/bin", "WPILOG_MAX_HEAP", "8g"), true);
    assertEquals("-Xmx8g", bigHeap.output().lines().findFirst().orElse(""));

    // Upgrade: the new version is added and becomes current; the old launcher still runs the
    // old JAR, and the user's configuration is kept
    var config = install.resolve("servers.yaml");
    Files.writeString(config, "team: 2363\n" + Files.readString(config));
    Files.writeString(release, releaseJson("1.2.4"));
    var second = run(List.of("sh", installer), env, true);
    assertEquals(0, second.exit(), second.output());
    assertEquals(Path.of("wpilog-mcp-1.2.4"), Files.readSymbolicLink(bin.resolve("wpilog-mcp")));
    var old = run(List.of(launcher123.toString()),
        Map.of("HOME", home.toString(), "PATH", "/usr/bin:/bin"), true);
    assertTrue(old.output().lines().anyMatch(jar123.toString()::equals), old.output());
    assertTrue(Files.isRegularFile(install.resolve("jars/wpilog-mcp-1.2.4.jar")));
    assertTrue(Files.readString(config).startsWith("team: 2363\n"), "servers.yaml kept");
  }

  /**
   * GitHub may answer the release API on one line. Then a selector that greps the line holding
   * a field and takes the last quoted value on it takes the last URL in the whole document, the
   * source zipball, and the last "v..." string, the release title: the installer downloaded the
   * source archive as the JAR, and Java refused it as corrupt. Each value must come from its own
   * field, whatever the formatting.
   */
  @ParameterizedTest(name = "compact JSON: {0}")
  @ValueSource(booleans = {false, true})
  @DisabledOnOs(OS.WINDOWS)
  @DisplayName("install.sh selects the version and the JAR from their own fields")
  void installShSelectsFieldsWhateverTheFormatting(boolean compact) throws Exception {
    var home = Files.createDirectories(tempDir.resolve("home"));
    var fakeBin = Files.createDirectories(tempDir.resolve("fakebin"));
    executable(fakeBin.resolve("curl"), FAKE_CURL);
    executable(home.resolve("wpilib/2026/jdk/bin/java"), FAKE_JAVA);
    var document = releaseJson("1.2.3");
    if (compact) document = new Gson().toJson(JsonParser.parseString(document));
    var release = Files.writeString(tempDir.resolve("release.json"), document);
    var result = run(List.of("sh", Path.of("install.sh").toAbsolutePath().toString()),
        Map.of("HOME", home.toString(), "PATH", fakeBin + ":/usr/bin:/bin",
            "FAKE_RELEASE_JSON", release.toString()), true);
    assertEquals(0, result.exit(), result.output());
    var jar = home.resolve(".wpilog-mcp/jars/wpilog-mcp-1.2.3.jar");
    assertAll(
        () -> assertEquals(List.of("Latest version: 1.2.3"),
            result.output().lines().filter(line -> line.startsWith("Latest version: ")).toList(),
            "the version is tag_name, not the release title"),
        () -> assertTrue(Files.isRegularFile(jar), "a JAR named by the version:\n" + result.output()),
        () -> assertEquals("jar for " + jarUrl("1.2.3"), Files.readString(jar),
            "the JAR is the -all.jar asset, not the source zipball"));
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
          Get-Content -Raw -Path $env:FAKE_RELEASE_JSON | ConvertFrom-Json }
      function Invoke-WebRequest { param([string]$Uri, [string]$OutFile, [switch]$UseBasicParsing)
          [System.IO.File]::WriteAllText($OutFile, "jar for $Uri") }
      & $env:WPILOG_INSTALLER
      exit $LASTEXITCODE
      """;

  @Test
  @DisplayName("install.ps1: versioned JAR and launcher, wpilog-mcp.bat a copy of the newest")
  void installPs1() throws Exception {
    var shell = powershell();
    assumeTrue(shell != null, "PowerShell is not installed");
    var profile = Files.createDirectories(tempDir.resolve("profile"));
    var wrapper = Files.writeString(tempDir.resolve("run.ps1"), PS_WRAPPER);
    var release = tempDir.resolve("release.json");
    var javaBin = Path.of(System.getProperty("java.home"), "bin").toString();
    Map<String, String> env = Map.of("USERPROFILE", profile.toString(),
        "FAKE_RELEASE_JSON", release.toString(),
        "WPILOG_INSTALLER", Path.of("install.ps1").toAbsolutePath().toString(),
        "PATH", javaBin + File.pathSeparator + System.getenv("PATH"));
    var command = List.of(shell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
        "-File", wrapper.toString());
    var install = profile.resolve(".wpilog-mcp");
    var bin = install.resolve("bin");

    Files.writeString(release, releaseJson("1.2.3"));
    var first = run(command, env, false);
    assertEquals(0, first.exit(), first.output());
    assertEquals("jar for " + jarUrl("1.2.3"),
        Files.readString(install.resolve("jars/wpilog-mcp-1.2.3.jar")));
    var bat123 = Files.readString(bin.resolve("wpilog-mcp-1.2.3.bat"));
    assertTrue(bat123.contains("\\wpilog-mcp-1.2.3.jar\" %*"), bat123);
    assertFalse(bat123.replace("\r\n", "").contains("\n"), "cmd.exe wants CRLF line endings");
    assertEquals(bat123, Files.readString(bin.resolve("wpilog-mcp.bat")));
    var config = install.resolve("servers.yaml");
    assertNotEquals((byte) 0xEF, Files.readAllBytes(config)[0], "no byte order mark");
    checkDefaultConfig(config);

    Files.writeString(config, "team: 2363\n" + Files.readString(config));
    Files.writeString(release, releaseJson("1.2.4"));
    var second = run(command, env, false);
    assertEquals(0, second.exit(), second.output());
    assertEquals(bat123, Files.readString(bin.resolve("wpilog-mcp-1.2.3.bat")),
        "the old launcher still runs the old JAR");
    assertTrue(Files.readString(bin.resolve("wpilog-mcp.bat"))
        .contains("\\wpilog-mcp-1.2.4.jar\" %*"));
    assertTrue(Files.readString(config).startsWith("team: 2363\n"), "servers.yaml kept");
  }

  @Test
  @DisplayName("the test's own helpers: between() takes whole lines")
  void betweenTakesLines() {
    assertEquals("a\nb\n", between("x << 'CONFIG'\na\nb\nCONFIG\ny\n", "<< 'CONFIG'", "CONFIG"));
    assertEquals("a\n", between("s @'\r\na\r\n'@\r\n", "@'", "'@"));
    assertThrows(AssertionError.class, () -> between("no marker", "<<", "END"));
    assertThrows(AssertionError.class, () -> between("<<\na\nEND-ish\n", "<<", "END"));
  }
}
