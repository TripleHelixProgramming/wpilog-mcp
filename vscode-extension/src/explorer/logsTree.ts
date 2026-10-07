/**
 * The Logs view's model, built from `list_available_logs` results (EXPLORER_PLAN.md §4): in a
 * plain directory, the logs grouped by event and then by date, each showing its friendly name,
 * its match, and its size, newest first as the listing orders them. Pure functions, so the
 * grouping and the filter are tested from recorded results; the view in `extension.ts` only
 * draws the nodes.
 */

import * as path from "path";

export interface ListedRobot {
  id: string;
  name?: string | null;
  serial_number?: string | null;
  basis: string;
}
export interface StoreFile {
  store: string;
  path: string;
  kind?: string;
  size?: number;
  state?: "waiting" | "importing" | "refused";
  reason?: string | null;
  stated_robot?: string | null;
}

/** A log as `list_available_logs` lists it; other fields are passed through untouched. */
export interface ListedLog {
  friendly_name: string;
  path: string;
  filename: string;
  event?: string;
  match_type?: string;
  match_number?: number;
  team_number?: number;
  size_bytes?: number;
  last_modified?: number;
  tba?: Record<string, unknown>;
  store?: string;
  robot?: ListedRobot;
  session?: { id: string; path: string; started_at: string; ended_at: string; start_basis: string;
    open?: boolean; origin?: string; complete?: boolean; growing?: boolean; last_sync?: string; age_sec?: number };
  revlogs?: { path: string; filename: string; size_bytes?: number }[];
  kind?: "wpilog" | "revlog";
  wpilog?: string;
}

/** What `list_available_logs` returned, as the model reads it. */
export interface LogListing {
  status?: string;
  logs?: ListedLog[];
  stores?: { path: string; robots?: ListedRobot[]; mirror?: boolean }[];
  unassigned?: StoreFile[];
  inbox?: StoreFile[];
  unmanaged?: StoreFile[];
  log_directories?: (string | { path: string; origin: "configured" | "leased"; team: number | null })[];
  log_directory_paths?: string[];
  log_count?: number;
  returned?: number;
  offset?: number;
  has_more?: boolean;
  skipped?: { section?: string; directory?: string; reason?: string }[];
  error?: string;
  hint?: string;
}

/** Accept both earlier servers and the origin-bearing listing introduced with leases. */
export function directoryPaths(listing: LogListing): string[] {
  return (listing.log_directories ?? listing.log_directory_paths ?? []).map(dir => typeof dir === "string" ? dir : dir.path);
}

/** A node of the tree. */
export type LogNode = { readOnly?: boolean } & (
  | { kind: "store" | "directory"; label: string; folder: string; description?: string; tooltip?: string; children: LogNode[] }
  | { kind: "robot" | "session"; label: string; tooltip: string; children: LogNode[] }
  | { kind: "imports"; label: string; group: "unassigned" | "inbox" | "unmanaged"; store: string; files: StoreFile[]; children: LogNode[] }
  | { kind: "importFile"; label: string; description: string; tooltip: string; group: "unassigned" | "inbox" | "unmanaged"; file: StoreFile }
  /** An event (or "No event" for logs that name none), holding its dates. */
  | { kind: "event"; label: string; children: LogNode[] }
  /** A date, holding its logs. */
  | { kind: "date"; label: string; children: LogNode[] }
  /** A log: its line in the tree, and the log itself. */
  | { kind: "log"; label: string; description: string; tooltip: string; log: ListedLog }
  /** A line that is not a log: a directory that could not be read, an empty listing, an error. */
  | { kind: "note"; label: string; tooltip?: string });

/** The label of logs that name no event. */
export const NO_EVENT = "No event";

/**
 * A log's date, from the time in its file name as the listing orders by, else the file's
 * modification time: `YYYY-MM-DD` in UTC, as the roboRIO names files. Two logs of one day are
 * one group; a log whose name holds no date and whose modification time is unknown goes under
 * "Undated".
 */
export function dateOf(log: ListedLog): string {
  const named = dateInFilename(log.filename);
  if (named) return named;
  if (typeof log.last_modified === "number" && Number.isFinite(log.last_modified)) {
    return new Date(log.last_modified).toISOString().slice(0, 10);
  }
  return "Undated";
}

/**
 * The date in a file name, in the two forms the logging frameworks write: WPILib's
 * `FRC_20260321_162956...` and AdvantageKit's `<prefix>_26-03-21_16-29-56...`.
 */
export function dateInFilename(filename: string): string | undefined {
  const wpilib = /^FRC_(\d{4})(\d{2})(\d{2})_\d{6}/.exec(filename);
  if (wpilib) return `${wpilib[1]}-${wpilib[2]}-${wpilib[3]}`;
  const akit = /_(\d{2})-(\d{2})-(\d{2})_\d{2}-\d{2}-\d{2}/.exec(filename);
  if (akit) return `20${akit[1]}-${akit[2]}-${akit[3]}`;
  return undefined;
}

/** A size for a tree line: bytes, KB, or MB with one decimal. */
export function formatSize(bytes: number | undefined): string {
  if (typeof bytes !== "number" || !Number.isFinite(bytes) || bytes < 0) return "";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** A log's match as one phrase (`Qualification 42`), or nothing when it has none. */
export function matchOf(log: ListedLog): string {
  if (!log.match_type) return "";
  return log.match_number !== undefined ? `${log.match_type} ${log.match_number}` : log.match_type;
}

/**
 * Whether a log passes the filter box: the text, case-insensitively, is in the friendly name,
 * the file name, the event, or the match. A blank filter passes everything.
 */
export function logMatches(log: ListedLog, filter: string): boolean {
  const needle = filter.trim().toLowerCase();
  if (needle === "") return true;
  return [log.friendly_name, log.filename, log.event ?? "", matchOf(log)].some((text) =>
    text.toLowerCase().includes(needle)
  );
}

function logNode(log: ListedLog): LogNode {
  const match = matchOf(log);
  const size = formatSize(log.size_bytes);
  const tba = log.tba as { alliance?: string; score?: number; opponent_score?: number; won?: boolean } | undefined;
  const outcome =
    tba && typeof tba.score === "number" && typeof tba.opponent_score === "number"
      ? `${tba.won === true ? "Won" : tba.won === false ? "Lost" : "Scored"} ${tba.score}-${tba.opponent_score}` +
        (tba.alliance ? ` on ${tba.alliance}` : "")
      : "";
  const tooltipLines = [
    log.friendly_name,
    log.path,
    match ? `Match: ${match}` : "",
    log.team_number !== undefined ? `Team ${log.team_number}` : "",
    size ? `Size: ${size}` : "",
    outcome,
  ].filter((line) => line !== "");
  return {
    kind: "log",
    label: log.friendly_name,
    description: [size, outcome].filter((s) => s !== "").join(" · "),
    tooltip: tooltipLines.join("\n"),
    log,
  };
}

/**
 * The tree for a listing: events in the order their newest log appears (so the listing's
 * newest-first order is kept), each with its dates newest first, each with its logs in the
 * listing's order. Directories the server could not read come first as notes, so a missing
 * drive is seen, not inferred from an empty tree; a listing that failed is one note, with the
 * server's error and hint; a listing with nothing in it says so.
 */
function plainLogTree(listing: LogListing, filter = ""): LogNode[] {
  const nodes: LogNode[] = [];
  if (listing.status === "error") {
    nodes.push({
      kind: "note",
      label: listing.error ?? "The log listing failed",
      tooltip: listing.hint,
    });
    return nodes;
  }
  for (const skipped of listing.skipped ?? []) {
    if (skipped.directory) {
      nodes.push({
        kind: "note",
        label: `Could not read ${skipped.directory}`,
        tooltip: skipped.reason,
      });
    }
  }
  const logs = (listing.logs ?? []).filter((log) => logMatches(log, filter));
  if (logs.length === 0) {
    const where = listing.log_directories?.length ? directoryPaths(listing).join(", ") : "the log directories";
    nodes.push({
      kind: "note",
      label: filter.trim() !== "" ? `No log matches "${filter.trim()}"` : `No logs in ${where}`,
      tooltip: listing.status === "no_match" ? listing.hint : undefined,
    });
    return nodes;
  }
  const events = new Map<string, Map<string, LogNode[]>>();
  for (const log of logs) {
    const event = log.event?.trim() ? log.event.trim().toUpperCase() : NO_EVENT;
    let dates = events.get(event);
    if (!dates) {
      dates = new Map();
      events.set(event, dates);
    }
    const date = dateOf(log);
    let list = dates.get(date);
    if (!list) {
      list = [];
      dates.set(date, list);
    }
    list.push(logNode(log));
  }
  for (const [event, dates] of events) {
    const dateNodes: LogNode[] = [...dates.entries()]
      .sort(([a], [b]) => (a < b ? 1 : a > b ? -1 : 0))
      .map(([date, children]) => ({ kind: "date", label: date, children }));
    nodes.push({ kind: "event", label: event, children: dateNodes });
  }
  if (listing.has_more) {
    nodes.push({
      kind: "note",
      label: `Showing ${logs.length} of ${listing.log_count ?? "more"} logs; narrow with the filter`,
    });
  }
  return nodes;
}


/** Origin belongs to the directory configured or leased, including stores nested inside it. */
function directoryNode(kind: "store" | "directory", folder: string, children: LogNode[], listing: LogListing): LogNode {
  const directories = (listing.log_directories ?? []).filter((dir): dir is Exclude<typeof dir, string> => typeof dir !== "string");
  const origin = directories.filter(dir => inside(dir.path, folder)).sort((a, b) => b.path.length - a.path.length)[0];
  const description = origin ? [origin.origin, origin.team === null ? undefined : `team ${origin.team}`].filter(Boolean).join(" · ") : undefined;
  return { kind, label: path.basename(folder) || folder, folder, children,
    ...(origin ? { description, tooltip: `${folder}\n${description}` } : {}) };
}

function inside(folder: string, file: string): boolean {
  const relative = path.relative(folder, file);
  return relative !== ".." && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative);
}

/** Stores use manifest identities and clocks. Plain folders retain their event/date grouping. */
export function buildLogTree(listing: LogListing, filter = ""): LogNode[] {
  if (listing.status === "error") return plainLogTree(listing, filter);
  const origins = (listing.log_directories ?? []).filter((dir): dir is Exclude<typeof dir, string> => typeof dir !== "string");
  if (!listing.stores?.length) {
    if (!origins.length) return plainLogTree(listing, filter);
    return origins.map(dir => directoryNode("directory", dir.path, plainLogTree({ ...listing,
      log_directories: [dir],
      logs: (listing.logs ?? []).filter(log => origins.filter(root => inside(root.path, log.path))
        .sort((a, b) => b.path.length - a.path.length)[0]?.path === dir.path),
      skipped: listing.skipped?.filter(skipped => skipped.directory === dir.path),
    }, filter), listing));
  }
  const roots: LogNode[] = [];
  for (const store of listing.stores) {
    const children: LogNode[] = [];
    const logs = (listing.logs ?? []).filter(l => l.store === store.path);
    const robots = new Map<string, ListedRobot>();
    for (const robot of store.robots ?? []) robots.set(robot.id, robot);
    for (const log of logs) if (log.robot) robots.set(log.robot.id, log.robot);
    for (const robot of robots.values()) {
      const sessions = new Map<string, ListedLog[]>();
      for (const log of logs.filter(l => l.robot?.id === robot.id && l.session)) {
        const files = [log, ...(log.revlogs ?? []).map(rev => ({ ...rev, friendly_name: rev.filename,
          kind: "revlog" as const, wpilog: log.path }))];
        const visible = files.filter(l => logMatches(l, filter));
        if (visible.length) sessions.set(log.session!.id, [...(sessions.get(log.session!.id) ?? []), log]);
      }
      const dates = new Map<string, LogNode[]>();
      for (const files of [...sessions.values()].sort((a, b) => Date.parse(b[0].session!.started_at) - Date.parse(a[0].session!.started_at))) {
        const first = files[0];
        const session = first.session!;
        const date = session.started_at.slice(0, 10);
        const time = session.started_at.slice(11).replace(/Z$/, " UTC");
        const nodes: LogNode[] = [];
        for (const log of files) {
          const family: ListedLog[] = [log, ...(log.revlogs ?? []).map(rev => ({ ...rev, friendly_name: rev.filename,
            kind: "revlog" as const, wpilog: log.path }))];
          nodes.push(...family.filter(l => logMatches(l, filter)).map(logNode));
        }
        const node: LogNode = { kind: "session", label: [time, first.event, matchOf(first)].filter(Boolean).join(" · "),
          tooltip: `${session.path}\nTime basis: ${session.start_basis}`, children: nodes };
        dates.set(date, [...(dates.get(date) ?? []), node]);
      }
      if (dates.size || !filter.trim()) children.push({ kind: "robot",
        label: [robot.name || robot.id, robot.serial_number].filter((v, i, a) => v && a.indexOf(v) === i).join(" · "),
        tooltip: `Identity basis: ${robot.basis}\n${robot.serial_number ? `Serial: ${robot.serial_number}` : "Serial not logged"}`,
        children: [...dates.entries()].map(([label, children]) => ({ kind: "date", label, children })) });
    }
    for (const [group, label] of [["unassigned", "Unassigned"], ["inbox", "Inbox"], ["unmanaged", "Unmanaged"]] as const) {
      const files = (listing[group] ?? []).filter(f => f.store === store.path)
        .filter(f => !filter.trim() || `${f.path} ${f.state ?? ""} ${f.reason ?? ""}`.toLowerCase().includes(filter.trim().toLowerCase()));
      children.push({ kind: "imports", label, group, store: store.path, files,
        children: files.map(file => ({ kind: "importFile", label: path.basename(file.path), group, file,
          description: [file.state, file.stated_robot, formatSize(file.size)].filter(Boolean).join(" · "),
          tooltip: [file.path, file.reason, file.stated_robot ? `Stated robot: ${file.stated_robot}` : undefined].filter(Boolean).join("\n") })) });
    }
    const node = directoryNode("store", store.path, children, listing);
    const markReadOnly = (item: LogNode) => { item.readOnly = true; if ("children" in item) item.children.forEach(markReadOnly); };
    if (store.mirror) markReadOnly(node);
    roots.push(node);
  }
  const plain = (listing.logs ?? []).filter(l => !l.store);
  const used = new Set<string>();
  for (const folder of directoryPaths(listing)) {
    if (listing.stores.some(s => s.path === folder)) continue;
    const logs = plain.filter(l => {
      const relative = path.relative(folder, l.path);
      return relative !== ".." && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative) && !used.has(l.path);
    });
    logs.forEach(l => used.add(l.path));
    if (logs.length || origins.some(dir => dir.path === folder)) {
      roots.push(directoryNode("directory", folder, plainLogTree({ logs, log_directories: [folder] }, filter), listing));
    }
  }
  const skipped = (listing.skipped ?? []).filter(s => s.directory).map(s => ({ kind: "note" as const,
    label: `Could not read ${s.directory}`, tooltip: s.reason }));
  if (listing.has_more) roots.push({ kind: "note", label: `Showing ${(listing.logs ?? []).length} of ${listing.log_count ?? "more"} logs; narrow with the filter` });
  return [...skipped, ...(!origins.length && roots.length === 1 && roots[0].kind === "store" ? roots[0].children : roots)];
}
