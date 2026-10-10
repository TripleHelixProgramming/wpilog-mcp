/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.triplehelix.wpilogmcp.Version;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/**
 * The release scripts, Gradle and the extension all install through this verb, so their layout,
 * launcher lookup and configuration defaults cannot drift. Ordinary updates leave daemons and
 * older JARs alone; explicit refresh retires the layout with a recovery copy. Neither downgrades
 * the current launcher without --force.
 */
public final class InstallCommand {
  private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
  private static final Pattern VERSION = Pattern.compile("[0-9]+(?:\\.[0-9]+)*(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?");
  private static final Pattern MARKER = Pattern.compile("(?m)^(?:#|REM) wpilog-mcp (\\S+) launcher\\r?$", Pattern.CASE_INSENSITIVE);
  private static final Pattern PART = Pattern.compile("[0-9]+|[A-Za-z]+");

  private InstallCommand() {
  }

  public record Options(Path directory, List<String> logdirs, Integer team, boolean force, boolean json,
      boolean withExtension, Path vsix, boolean refresh) {
    public Options(Path directory, List<String> logdirs, Integer team, boolean force, boolean json) {
      this(directory, logdirs, team, force, json, false, null, false);
    }
  }

  /** Plan every destination before the first write, so a bad link cannot leave a partial install. */
  private record Layout(Path root, Path bin, Path jars, Path jar, Path launcher, Path current,
      Path config, Path legacyConfig, Path lock) {
    static Layout at(Path root, String version, boolean windows) {
      var bin = root.resolve("bin");
      var jars = root.resolve("jars");
      return new Layout(root, bin, jars, jars.resolve("wpilog-mcp-" + version + ".jar"),
          bin.resolve("wpilog-mcp-" + version + (windows ? ".bat" : "")),
          bin.resolve(windows ? "wpilog-mcp.bat" : "wpilog-mcp"), root.resolve("servers.yaml"),
          root.resolve("servers.json"), root.resolve("install.lock"));
    }
  }

  /** Reuse the file-access containment rule, with this install's canonical root as its boundary. */
  private static final class InstallFiles {
    private final SecurityValidator containment = new SecurityValidator();

    InstallFiles(Path root) throws IOException {
      containment.addAllowedDirectory(root.toRealPath());
    }

    Path check(Path path) throws IOException {
      try {
        containment.validate(path);
        return path;
      } catch (IOException e) {
        throw new IOException("Install path is outside the install directory or cannot be resolved: " + path, e);
      }
    }

    /** A missing in-root launcher target still counts as older; an outside target never does. */
    Path current(Path path) throws IOException {
      check(path.getParent());
      if (!Files.isSymbolicLink(path)) {
        return check(path);
      }
      try {
        check(path.getParent().resolve(Files.readSymbolicLink(path)));
        return path;
      } catch (IOException e) {
        throw new IOException("Current launcher must point inside the install directory: " + path, e);
      }
    }

    void preflight(Layout layout) throws IOException {
      for (var path : List.of(layout.bin(), layout.jars(), layout.jar(), layout.launcher(),
          layout.config(), layout.legacyConfig(), layout.lock())) {
        check(path);
      }
      current(layout.current());
    }
  }

  /** Null fields are included so callers can distinguish a missing launcher from a bad response. */
  public record Summary(String install_dir, String installed_version, String launcher_version_before,
      String launcher_version_after, boolean repointed, boolean config_created, String config_path,
      String launcher_path, String path_hint, String backup_dir) {
    Summary(String installDir, String installedVersion, String before, String after, boolean repointed,
        boolean configCreated, String configPath, String launcherPath, String pathHint) {
      this(installDir, installedVersion, before, after, repointed, configCreated, configPath, launcherPath, pathHint, null);
    }

    Summary withBackup(InstallRefresh.Prepared refresh) {
      return new Summary(install_dir, installed_version, refresh.previousVersion(), launcher_version_after,
          repointed, config_created, config_path, launcher_path, path_hint, refresh.backup().toString());
    }

    public String json() {
      var result = JSON.toJsonTree(this).getAsJsonObject();
      if (backup_dir == null) {
        result.remove("backup_dir");
      }
      return JSON.toJson(result);
    }

    public String text() {
      return "Installed wpilog-mcp " + installed_version + " in " + install_dir + "\n"
          + (repointed ? "Current launcher: " : "Kept current launcher: ") + launcher_version_after + "\n"
          + (config_created ? "Created configuration: " : "Kept configuration: ") + config_path + "\n"
          + (path_hint == null ? "Launcher directory is already on PATH."
              : "Add this directory to PATH: " + path_hint)
          + (backup_dir == null ? "" : "\nPrevious install kept at: " + backup_dir);
    }
  }

  public static Options parse(String[] args) {
    var directory = Path.of(System.getProperty("user.home"), ".wpilog-mcp");
    var logdirs = new ArrayList<String>();
    Integer team = null;
    boolean force = false;
    boolean json = false;
    boolean withExtension = false;
    boolean refresh = false;
    Path vsix = null;
    for (int i = 1; i < args.length; i++) {
      switch (args[i]) {
        case "--force" -> force = true;
        case "--json" -> json = true;
        case "--with-extension" -> withExtension = true;
        case "--refresh" -> refresh = true;
        case "--install-dir", "--logdir", "--team", "--vsix" -> {
          var flag = args[i];
          if (++i == args.length || args[i].isBlank() || args[i].startsWith("--")) {
            throw new IllegalArgumentException("Missing value for " + flag);
          }
          switch (flag) {
            case "--install-dir" -> directory = Path.of(args[i]);
            case "--logdir" -> logdirs.add(args[i]);
            case "--vsix" -> vsix = Path.of(args[i]);
            default -> {
              try {
                team = Integer.valueOf(args[i]);
              } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--team must be a positive integer");
              }
              if (team <= 0) {
                throw new IllegalArgumentException("--team must be a positive integer");
              }
            }
          }
        }
        default -> throw new IllegalArgumentException("Unknown install argument: " + args[i]);
      }
    }
    if (withExtension && vsix == null) {
      throw new IllegalArgumentException("--with-extension requires --vsix <file>");
    }
    if (!withExtension && vsix != null) {
      throw new IllegalArgumentException("--vsix requires --with-extension");
    }
    return new Options(directory, List.copyOf(logdirs), team, force, json, withExtension, vsix, refresh);
  }

  public static Summary install(Options options) throws IOException {
    try {
      var source = Path.of(InstallCommand.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      if (!Files.isRegularFile(source)) {
        throw new IOException("Install must run from a JAR: java -jar <jar> install");
      }
      return install(source, Version.VERSION, options,
          System.getProperty("os.name", "").toLowerCase().startsWith("windows"), System.getenv("PATH"));
    } catch (URISyntaxException e) {
      throw new IOException("Cannot locate the running JAR", e);
    }
  }

  /** The file lock makes the no-downgrade decision hold across simultaneous VS Code windows. */
  static Summary install(Path source, String version, Options options, boolean windows, String searchPath)
      throws IOException {
    requireVersion(version);
    var root = options.directory().toAbsolutePath().normalize();
    boolean newRoot = !Files.exists(root);
    Files.createDirectories(root, programAttributes(root, true));
    if (newRoot) programMode(root, true);
    var layout = Layout.at(root, version, windows);
    var io = new InstallFiles(root);
    io.preflight(layout);
    if (options.withExtension() && (options.vsix() == null || !Files.isRegularFile(options.vsix()))) {
      throw new IOException("VSIX file does not exist: " + options.vsix());
    }
    var refresh = options.refresh() ? InstallRefresh.prepare(root, source, version, options.force(), windows) : null;
    Path backup = refresh == null ? null : refresh.backup();
    Summary summary;
    try (var guard = InstallGuard.acquire(root, io.check(layout.lock()))) {
      summary = writeLayout(source, version, options, windows, searchPath, layout, io);
      if (options.withExtension()) {
        CodeInstaller.install(options.vsix());
      }
    } catch (IOException e) {
      if (backup != null) {
        throw new IOException("Previous install kept at " + backup + "; fresh install failed: " + e.getMessage(), e);
      }
      throw e;
    }
    return refresh == null ? summary : summary.withBackup(refresh);
  }

  private static Summary writeLayout(Path source, String version, Options options, boolean windows,
      String searchPath, Layout layout, InstallFiles io) throws IOException {
    io.preflight(layout);
    for (var directory : List.of(layout.jars(), layout.bin())) {
      Files.createDirectories(io.check(directory), programAttributes(directory, true));
      programMode(io.check(directory), true);
    }
    var bin = layout.bin();
    var current = io.current(layout.current());
    String before;
    try {
      before = launcherVersion(Files.readString(current));
    } catch (IOException e) {
      before = null;
    }
    boolean repointed = options.force() || before == null || compareVersions(version, before) > 0;
    copyChanged(source, io.check(layout.jar()), io);
    var launcher = io.check(layout.launcher());
    var script = resource(windows ? "launcher.bat" : "launcher.sh").replace("@VERSION@", version);
    if (windows) {
      script = script.replace("\r\n", "\n").replace("\n", "\r\n");
    }
    writeChanged(launcher, script, io, !windows);
    if (!windows && !io.check(launcher).toFile().setExecutable(true, false)) {
      throw new IOException("Cannot make launcher executable: " + launcher);
    }
    if (repointed) {
      if (windows) {
        copyChanged(launcher, current, io);
      } else {
        var temporary = Files.createTempFile(io.check(bin), ".launcher-", ".tmp");
        try {
          Files.delete(io.check(temporary));
          Files.createSymbolicLink(io.check(temporary), launcher.getFileName());
          Files.move(io.check(temporary), io.current(current),
              StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
          Files.deleteIfExists(io.check(temporary));
        }
      }
    }

    var config = io.check(layout.config());
    boolean configCreated = false;
    if (!Files.exists(config, LinkOption.NOFOLLOW_LINKS) && Files.exists(io.check(layout.legacyConfig()))) {
      config = io.check(layout.legacyConfig());
    } else if (!Files.exists(config, LinkOption.NOFOLLOW_LINKS)) {
      var contents = initialConfiguration(options);
      try {
        Files.writeString(io.check(config), contents,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        configCreated = true;
      } catch (FileAlreadyExistsException e) {
        // A user creating their configuration while we install wins over the template.
      }
    }
    return new Summary(layout.root().toString(), version, before, repointed ? version : before, repointed,
        configCreated, config.toString(), current.toString(), onPath(bin, searchPath, windows) ? null : bin.toString());
  }

  /**
   * Seed only a new configuration, with the guide pinned to this JAR's version. A checkout
   * carries the next version before its tag exists, so that link returns 404 until the release
   * or pre-release is published. The version cannot distinguish a checkout from a release;
   * substitution uses no tag lookup or main fallback.
   */
  private static String initialConfiguration(Options options) throws IOException {
    var logdir = options.logdirs().isEmpty() ? "logdir: ~/riologs"
        : "logdir: " + JSON.toJson(options.logdirs());
    return resource("servers.yaml")
        .replace("@VERSION@", Version.VERSION)
        .replace("@TEAM@", options.team() == null ? "# team: 1234" : "team: " + options.team())
        .replace("@LOGDIR@", logdir);
  }

  /** Replace whole files, never truncate a JAR a daemon may still have open. */
  private static void copyChanged(Path source, Path target, InstallFiles io) throws IOException {
    io.check(target);
    if (Files.isRegularFile(target) && Files.mismatch(source, target) == -1) {
      programMode(io.check(target), false);
      return;
    }
    var temporary = Files.createTempFile(io.check(target.getParent()), ".install-", ".tmp", programAttributes(target, false));
    try {
      // Keep the new file's permissions instead of copying a private source JAR's mode.
      try (var input = Files.newInputStream(source); var output = Files.newOutputStream(io.check(temporary))) {
        input.transferTo(output);
      }
      programMode(io.check(temporary), false);
      Files.move(io.check(temporary), io.check(target),
          StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(io.check(temporary));
    }
  }

  private static void writeChanged(Path target, String text, InstallFiles io, boolean executable) throws IOException {
    io.check(target);
    if (Files.isRegularFile(target) && text.equals(Files.readString(target))) {
      programMode(io.check(target), executable);
      return;
    }
    var temporary = Files.createTempFile(io.check(target.getParent()), ".launcher-", ".tmp", programAttributes(target, false));
    try {
      Files.writeString(io.check(temporary), text);
      programMode(io.check(temporary), executable);
      Files.move(io.check(temporary), io.check(target),
          StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(io.check(temporary));
    }
  }

  /**
   * Program files must be readable by a separate service account, independent of umask.
   * Configuration and existing parent directories keep their owner's permissions.
   */
  private static FileAttribute<?>[] programAttributes(Path path, boolean executable) {
    return Files.getFileAttributeView(path, PosixFileAttributeView.class) == null ? new FileAttribute<?>[0]
        : new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString(executable ? "rwxr-xr-x" : "rw-r--r--"))};
  }

  private static void programMode(Path path, boolean executable) throws IOException {
    if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(executable ? "rwxr-xr-x" : "rw-r--r--"));
    }
  }

  private static String resource(String name) throws IOException {
    try (var input = InstallCommand.class.getResourceAsStream("/install/" + name)) {
      if (input == null) {
        throw new IOException("Missing installer resource: " + name);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  static boolean onPath(Path bin, String searchPath, boolean windows) {
    return searchPath != null && Arrays.stream(searchPath.split(Pattern.quote(windows ? ";" : File.pathSeparator)))
        .filter(entry -> !entry.isBlank())
        .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
        .anyMatch(entry -> windows ? entry.equalsIgnoreCase(bin.toString()) : entry.equals(bin.toString()));
  }

  static String launcherVersion(String text) {
    var marker = MARKER.matcher(text);
    return marker.find() && VERSION.matcher(marker.group(1)).matches() ? marker.group(1) : null;
  }

  private static void requireVersion(String version) {
    if (!VERSION.matcher(version).matches()) {
      throw new IllegalArgumentException("Invalid install version: " + version);
    }
  }

  /** Numeric components compare numerically; a release follows its prereleases, dev10 follows dev9. */
  static int compareVersions(String first, String second) {
    requireVersion(first);
    requireVersion(second);
    var a = first.split("-", 2);
    var b = second.split("-", 2);
    var an = a[0].split("\\.");
    var bn = b[0].split("\\.");
    for (int i = 0; i < Math.max(an.length, bn.length); i++) {
      int compared = new BigInteger(i < an.length ? an[i] : "0")
          .compareTo(new BigInteger(i < bn.length ? bn[i] : "0"));
      if (compared != 0) {
        return compared;
      }
    }
    if (a.length != b.length) {
      return a.length == 1 ? 1 : -1;
    }
    if (a.length == 1) {
      return 0;
    }
    var ap = PART.matcher(a[1]).results().map(result -> result.group()).toList();
    var bp = PART.matcher(b[1]).results().map(result -> result.group()).toList();
    for (int i = 0; i < Math.min(ap.size(), bp.size()); i++) {
      var x = ap.get(i);
      var y = bp.get(i);
      int compared = Character.isDigit(x.charAt(0)) && Character.isDigit(y.charAt(0))
          ? new BigInteger(x).compareTo(new BigInteger(y)) : x.compareTo(y);
      if (compared != 0) {
        return compared;
      }
    }
    return Integer.compare(ap.size(), bp.size());
  }
}
