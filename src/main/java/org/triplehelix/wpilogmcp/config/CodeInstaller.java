/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Finds the VS Code that receives the matching extension, for release and development installs;
 * extension updates never call it.
 *
 * <p>A team's editor is WPILib's. Each season's WPILib installer puts its own VS Code under
 * {@code ~/wpilib/<season>/vscode} (on Windows, for all users, under the Public folder), never on
 * PATH; on a Mac that editor is an app bundle whose command line lives inside it. 0.10.0-dev3
 * looked only at this calendar year's {@code vscode/bin/code}, then at {@code code} on PATH, so
 * it found no editor on a Mac, and on every system it found none each January until the new
 * season's WPILib was installed. So every season is found by listing the folders, as the
 * installers' JDK search already did; an editor older than the extension's
 * {@code engines.vscode} is never offered, since VS Code would refuse the install; the newest
 * season is the default; and any other VS Code receives the extension only when the caller names
 * it ({@code --vscode system}).
 */
final class CodeInstaller {
  /** Inside a Mac app bundle, the path of VS Code's command line. */
  private static final Path BUNDLE_CLI = Path.of("Contents", "Resources", "app", "bin", "code");
  private static final Pattern SEASON = Pattern.compile("[0-9]{4}");
  private static final Pattern EDITOR_VERSION = Pattern.compile("[0-9]+(?:\\.[0-9]+)*");
  /** The lowest version an engines.vscode range accepts: ^1.101.0, >=1.101.0, ~1.101.0 or 1.101.0. */
  private static final Pattern ENGINE = Pattern.compile("^\\s*(?:\\^|~|>=?|=)?\\s*v?([0-9]+(?:\\.[0-9]+)*)");

  private CodeInstaller() {
  }

  /**
   * One VS Code that can receive the extension.
   *
   * @param season the WPILib season it came with, or null for a VS Code installed apart from WPILib
   * @param version its own version, read from its package.json, or null when that cannot be read
   * @param cli its command line
   */
  record Editor(Integer season, String version, Path cli) {
    /** How the installers name it to a person, for example "WPILib VS Code 2026 (version 1.105.1)". */
    String label() {
      return (season == null ? "VS Code" : "WPILib VS Code " + season)
          + " (version " + (version == null ? "unknown" : version) + ")";
    }
  }

  /** Which editor {@code --vscode} names. Without it, the newest WPILib season the extension accepts. */
  sealed interface Choice permits Choice.Newest, Choice.Season, Choice.SystemVsCode {
    /** The default: the newest WPILib season whose editor the extension accepts. */
    record Newest() implements Choice {
    }

    /** One WPILib season's editor, as the installers' menus pass it. */
    record Season(int year) implements Choice {
    }

    /** VS Code installed apart from WPILib: never chosen unless named. */
    record SystemVsCode() implements Choice {
    }

    static Choice parse(String text) {
      if ("system".equals(text)) {
        return new SystemVsCode();
      }
      if (text != null && SEASON.matcher(text).matches()) {
        return new Season(Integer.parseInt(text));
      }
      throw new IllegalArgumentException("--vscode takes a WPILib season such as 2026, or system");
    }
  }

  /**
   * Where editors live. Production reads the places from the environment; tests build their own,
   * so every platform's layout is checked on every platform.
   *
   * @param windows whether command lines are {@code code.cmd} files run through cmd
   * @param home the user's home folder, holding {@code wpilib/<season>}
   * @param publicHome the Windows Public folder, holding WPILib installed for all users
   * @param systemEditors the usual command lines of a VS Code installed apart from WPILib, in order
   * @param searchPath PATH, searched for a VS Code installed apart from WPILib after those
   */
  record Places(boolean windows, Path home, Path publicHome, List<Path> systemEditors, String searchPath) {
    static Places current() {
      var os = System.getProperty("os.name", "");
      boolean windows = os.startsWith("Windows");
      var home = Path.of(System.getProperty("user.home"));
      var env = System.getenv();
      var systemEditors = new ArrayList<Path>();
      if (windows) {
        // The user installer's default folder, then the system installer's
        Optional.ofNullable(env.get("LOCALAPPDATA")).ifPresent(dir ->
            systemEditors.add(Path.of(dir, "Programs", "Microsoft VS Code", "bin", "code.cmd")));
        Optional.ofNullable(env.get("ProgramFiles")).ifPresent(dir ->
            systemEditors.add(Path.of(dir, "Microsoft VS Code", "bin", "code.cmd")));
      } else if (os.startsWith("Mac")) {
        systemEditors.add(Path.of("/Applications", "Visual Studio Code.app").resolve(BUNDLE_CLI));
        systemEditors.add(home.resolve("Applications").resolve("Visual Studio Code.app").resolve(BUNDLE_CLI));
      }
      return new Places(windows, home, Path.of(env.getOrDefault("PUBLIC", "C:\\Users\\Public")),
          List.copyOf(systemEditors), env.get("PATH"));
    }
  }

  /**
   * What an installer may offer: the WPILib editors the extension accepts, newest season first,
   * and a note for each one it leaves out, so a script never offers an install VS Code would
   * refuse and the person sees why an editor is missing.
   */
  record Listing(List<Editor> offered, List<String> notes) {
  }

  /** Every WPILib VS Code, newest season first; per season, the Public install before the user's own. */
  static List<Editor> wpilib(Places places) {
    var roots = places.windows() ? List.of(places.publicHome(), places.home()) : List.of(places.home());
    var bySeason = new TreeMap<Integer, Editor>(Comparator.reverseOrder());
    for (var root : roots) {
      for (var season : children(root.resolve("wpilib"), "[0-9][0-9][0-9][0-9]")) {
        int year = Integer.parseInt(season.getFileName().toString());
        if (bySeason.containsKey(year)) {
          continue;
        }
        var vscode = season.resolve("vscode");
        var candidates = new ArrayList<Path>();
        if (places.windows()) {
          candidates.add(vscode.resolve("bin").resolve("code.cmd"));
        } else {
          candidates.add(vscode.resolve("bin").resolve("code"));
          children(vscode, "*.app").forEach(bundle -> candidates.add(bundle.resolve(BUNDLE_CLI)));
        }
        candidates.stream().filter(cli -> runnable(cli, places.windows())).findFirst()
            .ifPresent(cli -> bySeason.put(year, new Editor(year, version(cli), cli)));
      }
    }
    return List.copyOf(bySeason.values());
  }

  /** VS Code installed apart from WPILib: its usual folders, then PATH. */
  static Optional<Editor> system(Places places) {
    return systemCandidates(places).stream().filter(cli -> runnable(cli, places.windows())).findFirst()
        .map(cli -> new Editor(null, version(cli), cli));
  }

  private static List<Path> systemCandidates(Places places) {
    var candidates = new ArrayList<Path>(places.systemEditors());
    if (places.searchPath() != null) {
      var name = places.windows() ? "code.cmd" : "code";
      for (var directory : places.searchPath().split(Pattern.quote(places.windows() ? ";" : File.pathSeparator))) {
        if (!directory.isBlank()) {
          candidates.add(Path.of(directory).resolve(name));
        }
      }
    }
    return candidates;
  }

  static Listing list(Places places, String engine) {
    var offered = new ArrayList<Editor>();
    var notes = new ArrayList<String>();
    for (var editor : wpilib(places)) {
      if (accepts(editor.version(), engine)) {
        offered.add(editor);
      } else {
        notes.add(tooOld(editor, engine) + ", so it is not offered.");
      }
    }
    return new Listing(List.copyOf(offered), List.copyOf(notes));
  }

  /**
   * The editor an install goes into. The caller runs this before writing anything, so a missing
   * or too-old editor fails the whole install instead of leaving the server installed without
   * the extension that was asked for.
   */
  static Editor choose(Places places, Choice choice, String engine) throws IOException {
    if (choice instanceof Choice.SystemVsCode) {
      var usual = places.systemEditors().stream().map(Path::toString).collect(Collectors.joining(", "));
      var editor = system(places).orElseThrow(() -> new IOException("No VS Code was found for --vscode system. Looked for "
          + (usual.isEmpty() ? "" : usual + ", then for ") + (places.windows() ? "code.cmd" : "code") + " on PATH."));
      return accepted(editor, engine);
    }
    var editors = wpilib(places);
    if (choice instanceof Choice.Season season) {
      var editor = editors.stream().filter(candidate -> candidate.season() == season.year()).findFirst()
          .orElseThrow(() -> new IOException("WPILib VS Code " + season.year() + " was not found; "
              + (editors.isEmpty() ? "no WPILib VS Code was found in " + wpilibRoots(places)
                  : "found " + editors.stream().map(Editor::label).collect(Collectors.joining("; "))) + "."));
      return accepted(editor, engine);
    }
    var newest = editors.stream().filter(editor -> accepts(editor.version(), engine)).findFirst();
    if (newest.isPresent()) {
      return newest.get();
    }
    if (editors.isEmpty()) {
      throw new IOException("No WPILib VS Code was found in " + wpilibRoots(places)
          + ". Install WPILib, or name another VS Code with --vscode system.");
    }
    throw new IOException("No WPILib VS Code accepts the extension: "
        + editors.stream().map(editor -> tooOld(editor, engine)).collect(Collectors.joining("; "))
        + ". Install a newer WPILib, or name another VS Code with --vscode system.");
  }

  private static Editor accepted(Editor editor, String engine) throws IOException {
    if (!accepts(editor.version(), engine)) {
      throw new IOException(tooOld(editor, engine) + ".");
    }
    return editor;
  }

  private static String tooOld(Editor editor, String engine) {
    return editor.label() + " is older than the extension needs (VS Code " + engine + ")";
  }

  private static String wpilibRoots(Places places) {
    var roots = places.windows() ? List.of(places.publicHome(), places.home()) : List.of(places.home());
    return roots.stream().map(root -> root.resolve("wpilib").toString()).collect(Collectors.joining(" or "));
  }

  /**
   * Whether an editor of this version accepts an extension with this engines.vscode range. Only
   * the range's lower bound is checked: VS Code is still on major version 1. An editor whose
   * version could not be read is offered, and VS Code itself reports a refusal; a range that
   * names no version accepts every editor.
   */
  static boolean accepts(String version, String engine) {
    var minimum = engine == null ? null : ENGINE.matcher(engine);
    if (version == null || minimum == null || !minimum.find() || !EDITOR_VERSION.matcher(version).matches()) {
      return true;
    }
    return compare(version, minimum.group(1)) >= 0;
  }

  /** Dotted versions, part by part and numerically; a missing part counts as zero. */
  static int compare(String first, String second) {
    var a = first.split("\\.");
    var b = second.split("\\.");
    for (int i = 0; i < Math.max(a.length, b.length); i++) {
      int compared = new BigInteger(i < a.length ? a[i] : "0").compareTo(new BigInteger(i < b.length ? b[i] : "0"));
      if (compared != 0) {
        return compared;
      }
    }
    return 0;
  }

  /**
   * The editor's version from its own package.json, without starting it: beside a Mac bundle's
   * command line ({@code app/bin/code} beside {@code app/package.json}), else under
   * {@code resources/app} of the folder holding {@code bin}. Links are followed first, since a
   * command line on PATH is often a link into the editor's folder. Null when neither reads.
   */
  static String version(Path cli) {
    Path app;
    try {
      app = cli.toRealPath().getParent().getParent();
    } catch (IOException e) {
      return null;
    }
    if (app == null) {
      return null;
    }
    for (var manifest : List.of(app.resolve("package.json"), app.resolve("resources").resolve("app").resolve("package.json"))) {
      try {
        var json = JsonParser.parseString(Files.readString(manifest));
        var version = json.isJsonObject() ? json.getAsJsonObject().get("version") : null;
        if (version != null && version.isJsonPrimitive() && EDITOR_VERSION.matcher(version.getAsString()).matches()) {
          return version.getAsString();
        }
      } catch (IOException | JsonParseException e) {
        // Not this layout, or unreadable: try the next
      }
    }
    return null;
  }

  private static boolean runnable(Path cli, boolean windows) {
    return Files.isRegularFile(cli) && (windows || Files.isExecutable(cli));
  }

  /** A folder's entries matching a glob, in name order; none when the folder cannot be listed. */
  private static List<Path> children(Path directory, String glob) {
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    try (var entries = Files.newDirectoryStream(directory, glob)) {
      var found = new ArrayList<Path>();
      entries.forEach(found::add);
      found.sort(Comparator.comparing(Path::toString));
      return found;
    } catch (IOException | DirectoryIteratorException e) {
      return List.of();
    }
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

  static void install(Editor editor, Path vsix, boolean windows) throws IOException {
    var process = new ProcessBuilder(command(windows, editor.cli(), vsix)).redirectErrorStream(true).start();
    // Even code's normal output belongs on stderr: install --json owns stdout.
    try (var output = process.getInputStream()) {
      output.transferTo(System.err);
    }
    try {
      int exit = process.waitFor();
      if (exit != 0) {
        throw new IOException("VS Code extension installation into " + editor.label() + " failed (exit " + exit + ")");
      }
    } catch (InterruptedException e) {
      process.destroy();
      Thread.currentThread().interrupt();
      throw new IOException("VS Code extension installation interrupted", e);
    }
  }
}
