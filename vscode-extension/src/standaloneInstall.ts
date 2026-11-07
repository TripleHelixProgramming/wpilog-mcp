/**
 * Installation decisions and the JAR's install protocol, without VS Code. The server owns the
 * layout and the final no-downgrade decision, including when another window installs first.
 */
import { olderVersion } from "./standaloneServer";

const VERSION = /^[0-9]+(?:\.[0-9]+)*(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?$/;

/** Read the launcher's comment, without executing a possibly older server just to ask its version. */
export function launcherVersion(text: string): string | undefined {
  const version = /^(?:#|REM) wpilog-mcp (\S+) launcher\r?$/im.exec(text)?.[1];
  return version !== undefined && VERSION.test(version) ? version : undefined;
}

export interface InstallSeed {
  logDirs?: readonly string[];
  teamNumber?: number | null;
}

/** Only User directories and team may seed a new config; no secret is part of this protocol. */
export function installArgs(seed: InstallSeed = {}): string[] {
  const args = ["install", "--json"];
  for (const dir of seed.logDirs ?? []) {
    if (dir.trim()) args.push("--logdir", dir);
  }
  if (typeof seed.teamNumber === "number" && Number.isSafeInteger(seed.teamNumber) && seed.teamNumber > 0) {
    args.push("--team", String(seed.teamNumber));
  }
  return args;
}

export interface InstallSummary {
  install_dir: string;
  installed_version: string;
  launcher_version_before: string | null;
  launcher_version_after: string;
  repointed: boolean;
  config_created: boolean;
  config_path: string;
  launcher_path: string;
  path_hint: string | null;
}

/** Reject incomplete or contradictory output before reporting success or remembering an install. */
export function parseInstallSummary(json: string): InstallSummary {
  const fail = (): never => { throw new Error("Invalid install summary from the server."); };
  let value: unknown;
  try {
    value = JSON.parse(json);
  } catch {
    return fail();
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) return fail();
  const summary = value as Record<string, unknown>;
  const text = (key: string): string => {
    const field = summary[key];
    return typeof field === "string" && field.trim() !== "" ? field : fail();
  };
  const version = (key: string): string => {
    const field = text(key);
    return VERSION.test(field) ? field : fail();
  };
  const boolean = (key: string): boolean => {
    const field = summary[key];
    return typeof field === "boolean" ? field : fail();
  };
  const result: InstallSummary = {
    install_dir: text("install_dir"),
    installed_version: version("installed_version"),
    launcher_version_before: summary.launcher_version_before === null ? null : version("launcher_version_before"),
    launcher_version_after: version("launcher_version_after"),
    repointed: boolean("repointed"),
    config_created: boolean("config_created"),
    config_path: text("config_path"),
    launcher_path: text("launcher_path"),
    path_hint: summary.path_hint === null ? null : text("path_hint"),
  };
  if (result.launcher_version_after !== (result.repointed ? result.installed_version : result.launcher_version_before)) {
    return fail();
  }
  return result;
}

export interface InstallActionInput {
  launcherVersion?: string;
  extensionVersion: string;
  missing: boolean;
}

/** Every window uses this install; the JAR makes the final no-downgrade decision. */
export function installAction(input: InstallActionInput): "offer" | "update" | "none" {
  if (input.missing) return "offer";
  if (olderVersion(input.launcherVersion, input.extensionVersion)) {
    return "update";
  }
  return "none";
}
