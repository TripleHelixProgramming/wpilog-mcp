import * as fs from "fs";
import * as path from "path";
import * as os from "os";
import { execFile } from "child_process";
import * as vscode from "vscode";

/**
 * Finds a suitable Java 17+ executable, preferring the IDE's own JDK.
 *
 * Search order:
 * 1. User setting (wpilog-mcp.javaPath)
 * 2. IDE's own JDK — if VS Code is a WPILib distribution (e.g. running from
 *    ~/wpilib/2026/), use that year's bundled JDK. This ensures the JDK
 *    matches the season the IDE was installed for.
 * 3. WPILib JDK (auto-detect latest year from known install paths)
 * 4. JAVA_HOME environment variable
 * 5. java on PATH
 */
export async function findJava(): Promise<string | undefined> {
  const config = vscode.workspace.getConfiguration("wpilog-mcp");

  // 1. User override
  const userJava = config.get<string>("javaPath");
  if (userJava && fs.existsSync(userJava)) {
    return userJava;
  }

  // 2. IDE's own JDK — detect if this VS Code is a WPILib distribution
  const ideJava = findIdeJdk();
  if (ideJava) {
    return ideJava;
  }

  // 3. WPILib JDK (auto-detect year from install paths)
  const wpilibJava = findWpiLibJdk(config.get<string>("wpiLibYear"));
  if (wpilibJava) {
    return wpilibJava;
  }

  // 4. JAVA_HOME
  const javaHome = process.env.JAVA_HOME;
  if (javaHome) {
    const javaExe = javaExecutable(path.join(javaHome, "bin"));
    if (fs.existsSync(javaExe)) {
      return javaExe;
    }
  }

  // 5. System PATH — check if `java` resolves and is 17+
  const systemJava = javaExecutable("");
  if (await validateJavaVersion(systemJava)) {
    return systemJava;
  }

  return undefined;
}

/**
 * Checks if the running VS Code is a WPILib distribution and returns its
 * bundled JDK. WPILib VS Code lives under ~/wpilib/{year}/ — if the IDE's
 * executable path contains a wpilib/{year} segment, we use that year's JDK.
 */
function findIdeJdk(): string | undefined {
  const appRoot = vscode.env.appRoot;
  if (!appRoot) {
    return undefined;
  }

  // Normalize to forward slashes for consistent matching
  const normalized = appRoot.replace(/\\/g, "/");
  const match = normalized.match(/[/\\]wpilib[/\\](\d{4})[/\\]/i)
    || normalized.match(/\/wpilib\/(\d{4})\//i);

  if (!match) {
    return undefined;
  }

  const year = match[1];

  // The JDK is a sibling of the VS Code install under the same wpilib/{year}/
  const home = os.homedir();
  const isWindows = process.platform === "win32";
  const candidates = isWindows
    ? [
        path.join(home, "wpilib", year, "jdk", "bin"),
        path.join("C:\\Users\\Public\\wpilib", year, "jdk", "bin"),
      ]
    : [path.join(home, "wpilib", year, "jdk", "bin")];

  for (const binDir of candidates) {
    const javaExe = javaExecutable(binDir);
    if (fs.existsSync(javaExe)) {
      return javaExe;
    }
  }

  return undefined;
}

/**
 * Scans WPILib installation directories for the JDK, using the latest year.
 */
function findWpiLibJdk(preferredYear?: string): string | undefined {
  const home = os.homedir();
  const isWindows = process.platform === "win32";

  const baseDirs: string[] = [];
  if (isWindows) {
    baseDirs.push(path.join(home, "wpilib"));
    baseDirs.push("C:\\Users\\Public\\wpilib");
  } else {
    baseDirs.push(path.join(home, "wpilib"));
  }

  for (const baseDir of baseDirs) {
    if (!fs.existsSync(baseDir)) {
      continue;
    }

    // If user specified a year, try that first
    if (preferredYear) {
      const javaExe = javaExecutable(
        path.join(baseDir, preferredYear, "jdk", "bin")
      );
      if (fs.existsSync(javaExe)) {
        return javaExe;
      }
    }

    // Scan for year directories, highest first
    const years = fs
      .readdirSync(baseDir)
      .filter((name) => /^\d{4}$/.test(name))
      .sort()
      .reverse();

    for (const year of years) {
      const javaExe = javaExecutable(
        path.join(baseDir, year, "jdk", "bin")
      );
      if (fs.existsSync(javaExe)) {
        return javaExe;
      }
    }
  }

  return undefined;
}

/**
 * Returns the platform-appropriate java executable path within a bin directory.
 * If binDir is empty, returns just "java" (for PATH resolution).
 */
function javaExecutable(binDir: string): string {
  const exe = process.platform === "win32" ? "java.exe" : "java";
  return binDir ? path.join(binDir, exe) : exe;
}

/**
 * Validates that a java executable is version 17+.
 */
function validateJavaVersion(javaPath: string): Promise<boolean> {
  return new Promise((resolve) => {
    execFile(javaPath, ["-version"], (error, _stdout, stderr) => {
      if (error) {
        resolve(false);
        return;
      }
      // Java version output goes to stderr: 'openjdk version "17.0.x"' or 'java version "17"'
      const output = stderr || _stdout;
      const match = output.match(/version "(\d+)/);
      if (match) {
        resolve(parseInt(match[1], 10) >= 17);
      } else {
        resolve(false);
      }
    });
  });
}
