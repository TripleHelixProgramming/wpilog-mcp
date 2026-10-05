/** Organizing is an offer, never a side effect of listing a folder. Decisions live here so a
 * remembered refusal, a cancelled picker, or a refresh cannot accidentally authorize a move. */
import * as path from "path";
import { LogListing, ListedRobot } from "./logsTree";

export const ORGANIZE_CHOICES = ["Organize", "Not now", "Never for this folder"] as const;
export type OrganizeChoice = typeof ORGANIZE_CHOICES[number] | undefined;
export const MOVE_CHOICES = [
  { label: "Move", description: "Organize in place (default); original paths stay in the manifests", move: true },
  { label: "Copy", description: "Keep the originals as well as the organized copies", move: false },
];

export interface Choices {
  never: readonly string[];
  deferred: readonly string[];
  offered: Readonly<Record<string, string>>;
}
export interface OrganizeOffer {
  folder: string;
  store: boolean;
  count: number;
  paths: string[];
  message: string;
  fingerprint: string;
}

export function folderKey(folder: string, paths: path.PlatformPath = path): string {
  const resolved = paths.resolve(folder);
  return paths.sep === "\\" ? resolved.toLowerCase() : resolved;
}

/** Relative paths, rather than string prefixes, distinguish logs/ from logs-old/ on both OSes. */
export function containsPath(folder: string, file: string, paths: path.PlatformPath = path): boolean {
  const relative = paths.relative(folderKey(folder, paths), folderKey(file, paths));
  return relative === "" || (!relative.startsWith(`..${paths.sep}`) && relative !== ".." && !paths.isAbsolute(relative));
}

export function organizeFolders(listing: LogListing): string[] {
  return [...new Set([...(listing.log_directories ?? []), ...(listing.stores ?? []).map(s => s.path)])];
}

/** Explicit commands may revisit a refused folder; automatic offers respect every remembered choice. */
export function offerFor(listing: LogListing, folder: string, choices: Choices,
  onDemand = false, paths: path.PlatformPath = path): OrganizeOffer | undefined {
  if (listing.status === "error" || listing.has_more || listing.skipped?.some(s => s.directory === folder)) return undefined;
  const key = folderKey(folder, paths);
  if (!organizeFolders(listing).some(f => folderKey(f, paths) === key)) return undefined;
  if (!onDemand && [...choices.never, ...choices.deferred].some(f => folderKey(f, paths) === key)) return undefined;
  const store = (listing.stores ?? []).some(s => folderKey(s.path, paths) === key);
  const files = store
    ? (listing.unmanaged ?? []).filter(f => folderKey(f.store, paths) === key).map(f => f.path)
    : (listing.logs ?? []).filter(log => !log.store && containsPath(folder, log.path, paths)).map(log => log.path);
  const unique = [...new Set(files)].sort();
  if (unique.length === 0) return undefined;
  const fingerprint = JSON.stringify(unique);
  if (!onDemand && choices.offered[key] !== undefined && (!store || choices.offered[key] === fingerprint)) return undefined;
  const names = unique.slice(0, 3).map(file => paths.basename(file)).join(", ");
  return {
    folder, store, count: unique.length, paths: unique, fingerprint,
    message: store
      ? `Organize these ${unique.length} unmanaged files by robot and session? ${names}${unique.length > 3 ? ", …" : ""}`
      : `Organize these ${unique.length} logs by robot and session?`,
  };
}

/** Dismissal is Not now. Never is the only choice persisted across activations. */
export function rememberChoice(choices: Choices, offer: OrganizeOffer, choice: OrganizeChoice): Choices {
  const key = folderKey(offer.folder);
  return {
    never: choice === "Never for this folder" ? [...new Set([...choices.never, key])] : choices.never,
    deferred: choice === "Organize" ? choices.deferred : [...new Set([...choices.deferred, key])],
    offered: { ...choices.offered, [key]: offer.fingerprint },
  };
}

export interface RobotPick {
  label: string;
  description?: string;
  action: "robot" | "new" | "later";
  robot?: string;
}

/** Robot names and serials are reported facts, never inferred from directory or file names. */
export function robotItems(listing: LogListing, store: string): RobotPick[] {
  const known: ListedRobot[] = [
    ...(listing.stores ?? []).filter(s => s.path === store).flatMap(s => s.robots ?? []),
    ...(listing.logs ?? []).filter(l => l.store === store && l.robot).map(l => l.robot!),
  ];
  const seen = new Set<string>();
  const items: RobotPick[] = [];
  for (const robot of known) {
    const value = robot.name || robot.id;
    const key = JSON.stringify([value, robot.serial_number]);
    if (seen.has(key)) continue;
    seen.add(key);
    items.push({ action: "robot", label: value, robot: value,
      description: robot.serial_number ? `Serial ${robot.serial_number}` : "Stated name; serial not logged" });
  }
  return [...items.sort((a, b) => a.label.localeCompare(b.label)),
    { action: "new", label: "New robot…" }, { action: "later", label: "Decide later" }];
}

/** Keep the words and portable-name rules in StoreFiles.robotName/component exactly. */
export function robotNameError(name: string): string | undefined {
  if (!/^[A-Za-z0-9._-]+$/.test(name)) {
    return "Robot names use only letters, digits, dots, hyphens, and underscores";
  }
  if (name === "." || name === ".." || name.endsWith(".") || /^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?$/i.test(name)) {
    return `Not a portable robot or file name: ${name}`;
  }
  return undefined;
}

/** All three decisions must be complete before the host can submit a batch. Null means the
 * person chose Decide later; undefined means they cancelled and authorizes no request. */
export function importPlan(offer: OrganizeOffer, choice: OrganizeChoice, move?: boolean, robot?: string | null):
    { store: string; paths: string[]; move: boolean; stated_robot: string | null } | undefined {
  if (choice !== "Organize" || move === undefined || robot === undefined) return undefined;
  return { store: offer.folder, paths: offer.paths, move, stated_robot: robot };
}
