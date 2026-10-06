/** A window lends directories to a session; it never rewrites the shared server's configuration. */
import * as path from "path";
import { combineLogDirectories, directoryIdentity, LogSettings, overlaySettings } from "./logDirectories";

export interface LeaseProject {
  folderPath: string;
  own: LogSettings;
}
export interface DirectoryRegistration {
  paths: { path: string; team: number | null }[];
  team: number | null;
}
export interface SessionRegistration {
  directories: DirectoryRegistration;
  /** Sent only to /tba-key, never to a tool, a command, or a file. Null clears this session's key. */
  key: string | null;
}

/** Preserve User roots; projects add their own roots and resolve inherited relative settings locally. */
export function directoryRegistration(user: LogSettings, projects: LeaseProject[],
  pathApi: path.PlatformPath = path): DirectoryRegistration {
  const team = (value: unknown) => typeof value === "number" && Number.isSafeInteger(value) && value > 0 ? value : null;
  const paths = new Map<string, { path: string; team: number | null }>();
  const add = (dirs: string[], number: number | null, replace: boolean) => {
    for (const dir of dirs) {
      const id = directoryIdentity(dir, pathApi);
      if (replace || !paths.has(id)) paths.set(id, { path: paths.get(id)?.path ?? dir, team: number });
    }
  };
  add(combineLogDirectories(user.logDirectory, user.additionalLogDirectories, [], pathApi), team(user.teamNumber), false);
  for (const project of projects) {
    const settings = overlaySettings(user, project.own);
    const number = team(settings.teamNumber);
    add(combineLogDirectories(settings.logDirectory, settings.additionalLogDirectories, [project.folderPath], pathApi), number, false);
    add(combineLogDirectories(project.own.logDirectory, project.own.additionalLogDirectories, [project.folderPath], pathApi), number, true);
  }
  return { paths: [...paths.values()], team: team(user.teamNumber) };
}

/** Changes to the leased inputs require replacement; unrelated JVM/UI settings do not. */
export function leaseChanged(before: SessionRegistration | undefined, after: SessionRegistration, newSession = false): boolean {
  return newSession || before === undefined || JSON.stringify(before) !== JSON.stringify(after);
}

export const LEASE_SETTINGS = ["logDirectory", "additionalLogDirectories", "teamNumber"].map(name => `wpilog-mcp.${name}`);
