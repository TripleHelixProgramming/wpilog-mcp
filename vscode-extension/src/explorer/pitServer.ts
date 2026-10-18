/** Pit and mirror decisions have no VS Code dependency: URLs never become local file paths. */
import * as path from "path";
import { ListedLog, LogListing, LogNode, formatSize, logMatches } from "./logsTree";

export interface MirrorSettings {
  enabled?: boolean;
  folder?: string;
  days?: number;
  maxSizeGb?: number;
  robots?: string[];
  events?: string[];
  intervalSec?: number;
}
export interface MirrorRequest {
  origin: string; folder: string; days: number; max_size_gb: number;
  robots: string[]; events: string[]; interval_sec: number;
}
export interface MirrorStatus {
  state: "disabled" | "waiting" | "synchronizing" | "synchronized" | "offline" | "partial" | "error";
  last_sync?: string | null; age_sec?: number | null; remaining_files?: number; remaining_bytes?: number;
  config?: { folder: string };
  result?: { reason?: string | null; refusals?: string[]; retained?: string[] } | null;
  origin?: { url: string; store_id: string; pinned_sessions: string[]; sessions: Record<string, { path: string; last_sync: string; complete: boolean; growing: boolean }> } | null;
}
export interface SessionPick { label: string; description: string; id: string; pinned: boolean; path: string }

export function pitEndpoint(value: string | undefined): string | undefined {
  if (!value?.trim()) return undefined;
  const url = new URL(value.trim());
  if (!["http:", "https:"].includes(url.protocol) || url.username || url.password || url.hash || url.search) {
    throw new Error("Pit server URL must use HTTP(S), without credentials, a query, or a fragment.");
  }
  if (url.pathname === "/") url.pathname = "/mcp";
  return url.href;
}
export function originOf(mcpUrl: string): string {
  const url = new URL(pitEndpoint(mcpUrl)!);
  url.pathname = url.pathname.replace(/\/mcp\/?$/, "").replace(/\/$/, "") || "/";
  return url.href.replace(/\/$/, "");
}
export function peerUrl(value: string): string {
  const url = new URL(/^[a-z][a-z0-9+.-]*:\/\//i.test(value.trim()) ? value.trim() : `http://${value.trim()}`);
  if (!["http:", "https:"].includes(url.protocol) || url.username || url.password || url.hash) throw new Error("Use host:port or an HTTP(S) URL without credentials.");
  if (url.search && (!url.searchParams.has("store") || [...url.searchParams.keys()].some(k => k !== "store") || url.searchParams.getAll("store").length !== 1 || !url.searchParams.get("store"))) {
    throw new Error("A peer URL accepts only ?store=<id>.");
  }
  url.pathname = url.pathname.replace(/\/(?:mcp|store)\/?$/, "");
  return url.href.replace(/\/(?=\?|$)/, "");
}
export function mirrorRequest(pitUrl: string | undefined, settings: MirrorSettings, storage: string,
  pathApi: path.PlatformPath = path): MirrorRequest | undefined {
  const endpoint = pitEndpoint(pitUrl);
  if (!endpoint || settings.enabled === false) return undefined;
  const folder = settings.folder?.trim() || pathApi.join(storage, "mirror");
  if (!pathApi.isAbsolute(folder)) throw new Error("Mirror folder must be an absolute path.");
  const days = settings.days ?? 14, max = settings.maxSizeGb ?? 20, interval = settings.intervalSec ?? 30;
  if (!Number.isSafeInteger(days) || days < 0) throw new Error("Mirror days must be a nonnegative integer.");
  if (!Number.isFinite(max) || max <= 0) throw new Error("Mirror max size must be positive.");
  if (!Number.isSafeInteger(interval) || interval < 1) throw new Error("Mirror interval must be a positive integer.");
  return { origin: originOf(endpoint), folder: pathApi.normalize(folder), days, max_size_gb: max,
    robots: [...(settings.robots ?? [])], events: [...(settings.events ?? [])], interval_sec: interval };
}
export interface LiveSettings { enabled?: boolean; folder?: string; gatewayPort?: number; }
/** The server's live source: disabled, connecting, waiting_for_time_sync, connected, recording or failed. */
export interface LiveStatus {
  state: string; configured_by?: string; configuration?: LiveRequest; address?: string; disconnect_reason?: string; error?: string;
  session?: { file: string; started_at: string | null; open: boolean; records: number | null } | null;
}
/** A laptop's live source: record the pit gateway's NetworkTables into a laptop folder. */
export interface LiveRequest { robot: { host: string; port: number }; store: string; }
/** The live source for a pit server: its gateway, on the pit's host, recorded into a folder of this
 * laptop, kept apart from the mirror's. A proxy in front of the pit's HTTP does not carry the
 * gateway, so the host is the pit URL's own. */
export function liveRequest(pitUrl: string | undefined, settings: LiveSettings, storage: string,
  pathApi: path.PlatformPath = path): LiveRequest | undefined {
  const endpoint = pitEndpoint(pitUrl);
  if (!endpoint || settings.enabled === false) return undefined;
  const folder = settings.folder?.trim() || pathApi.join(storage, "live");
  if (!pathApi.isAbsolute(folder)) throw new Error("Live folder must be an absolute path.");
  const port = settings.gatewayPort ?? 5810;
  if (!Number.isSafeInteger(port) || port < 1 || port > 65535) throw new Error("Live gateway port must be an integer from 1 to 65535.");
  return { robot: { host: new URL(endpoint).hostname.replace(/^\[(.*)\]$/, "$1"), port }, store: pathApi.normalize(folder) };
}
export function serverDefinitions(local: string | undefined, pit: string | undefined): { label: string; url: string }[] {
  const result = local ? [{ label: "WPILog Analyzer", url: local }] : [];
  const remote = pitEndpoint(pit);
  if (remote) result.push({ label: "WPILog Pit Server", url: remote });
  return result;
}
export function ageText(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || !Number.isFinite(seconds)) return "never synchronized";
  if (seconds < 60) return `${Math.max(0, Math.floor(seconds))} s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} h ago`;
  return `${Math.floor(seconds / 86400)} d ago`;
}
export function mirrorStatusText(status: MirrorStatus): string {
  switch (status.state) {
    case "synchronized": return "$(check) Mirror synchronized";
    case "synchronizing": return `$(sync~spin) Mirror: ${status.remaining_files ?? 0} files, ${formatSize(status.remaining_bytes ?? 0)} remaining`;
    case "offline": return `$(cloud-offline) Mirror offline · ${ageText(status.age_sec)}`;
    case "error": case "partial": return "$(warning) Mirror needs attention";
    case "waiting": return "$(sync) Mirror waiting";
    default: return "$(cloud) Pit server · mirror off";
  }
}
export function sessionPicks(listing: LogListing, pinned: readonly string[], onlyPinned = false): SessionPick[] {
  const sessions = new Map<string, ListedLog>();
  for (const log of listing.logs ?? []) if (log.session && !sessions.has(log.session.id)) sessions.set(log.session.id, log);
  return [...sessions.values()].sort((a, b) => b.session!.started_at.localeCompare(a.session!.started_at))
    .filter(log => !onlyPinned || pinned.includes(log.session!.id)).map(log => ({
      id: log.session!.id, pinned: pinned.includes(log.session!.id), path: log.path,
      label: [log.robot?.name || log.robot?.serial_number || "Unknown robot", log.event, log.match_type, log.match_number].filter(v => v !== undefined && v !== "").join(" · "),
      description: `${log.session!.started_at}${pinned.includes(log.session!.id) ? " · pinned" : ""}`,
    }));
}
/** A pit file's link. VS Code decodes a link's query once when it parses it and encodes its = and &
 * when it prints it, so each value is encoded twice here: after that decoding, a path's own &, =, +
 * and % still stand apart from the separators. */
export function remoteLogUri(url: string, logPath: string, session?: ListedLog["session"]): string {
  const name = logPath.replace(/\\/g, "/").split("/").pop() || "capture.wpilog";
  const fields: [string, string][] = [["url", pitEndpoint(url)!], ["path", logPath]];
  if (session) { fields.push(["session", session.id], ["session_path", session.path]);
    if (session.open !== undefined) fields.push(["open", String(session.open)]); }
  return `wpilog-pit:/${encodeURIComponent(name)}?${fields.map(([key, value]) => `${key}=${encodeURIComponent(encodeURIComponent(value))}`).join("&")}`;
}
/** A link's fields, from the query VS Code keeps for it, never from the printed link. A link saved
 * before values were encoded twice still reads when its path has none of those characters. */
export function readRemoteLogUri(uri: { scheme: string; query: string }): { url: string; path: string; session?: ListedLog["session"] } | undefined {
  if (uri.scheme !== "wpilog-pit") return undefined;
  const query = new URLSearchParams(uri.query);
  const url = pitEndpoint(query.get("url") ?? undefined), logPath = query.get("path");
  if (!url || !logPath) throw new Error("Remote log link is missing its server or path.");
  const id = query.get("session"), sessionPath = query.get("session_path");
  return { url, path: logPath, ...(id && sessionPath ? { session: { id, path: sessionPath, started_at: "", ended_at: "", start_basis: "", ...(query.has("open") ? { open: query.get("open") === "true" } : {}) } } : {}) };
}
/** Offline fallback requires an origin and session ID, never a similar file name. */
export function mirroredCopy(remote: ListedLog, local: LogListing, origin: string): ListedLog | undefined {
  if (!remote.session) return undefined;
  const relative = (log: ListedLog) => {
    const prefix = log.session?.path.replace(/\\/g, "/").replace(/\/$/, "") + "/";
    const full = log.path.replace(/\\/g, "/");
    return full.startsWith(prefix) ? full.slice(prefix.length) : undefined;
  };
  const suffix = relative(remote); if (suffix === undefined) return undefined;
  return local.logs?.find(log => log.session?.id === remote.session!.id && log.session?.origin === origin
    && relative(log) === suffix);
}


/** Where Open Source Overview goes for a pit file: a file on this laptop to open, with a note when
 * it is not the pit's own bytes, or why there is none. A command runs an action the person picks. */
export type PitOverview = { path: string; note?: string } | { message: string; actions: { title: string; command: string }[] };

/**
 * The laptop reads only its own stores. A file the pit pulled or was given opens from the mirror's
 * copy. The pit's capture is not copied, so it opens this laptop's own recording of that session:
 * a captured log in one of this laptop's stores, of the same robot, whose session overlaps the
 * pit's; the longest overlap wins. A capture an earlier version of the mirror copied opens too,
 * noted as such. Otherwise the reason, with the mirror's progress.
 */
export function pitOverview(remote: ListedLog, local: LogListing, origin: string, mirror: MirrorStatus, now: number,
  pathApi: path.PlatformPath = path): PitOverview {
  const name = remote.filename || remote.path.replace(/\\/g, "/").split("/").pop() || remote.path;
  const captured = remote.session?.open !== undefined;
  const copy = mirroredCopy(remote, local, origin);
  if (copy) return captured ? { path: copy.path, note: `This is the copy an earlier version of the mirror made of the pit's capture ${name}; it is no longer refreshed.` } : { path: copy.path };
  if (!captured) return notCopied(name, mirror);
  const own = ownRecording(remote, local, mirror, now, pathApi);
  if (own) {
    // Every captured member shares session.open. Only the manifest identifies which file is
    // still being written; a filename sort can choose an earlier rollover as a closed file.
    const member = own.session?.open ? local.logs?.find(log => own.session!.open_capture
      && sameLocalPath(log.path, own.session!.open_capture, pathApi) && log.session?.id === own.session!.id
      && log.store && sameLocalPath(log.store, own.store!, pathApi)) : own;
    if (!member) return { message: "This laptop's current recording file is unavailable in the listing. Refresh Logs and try again; update the local server if its listing does not identify the current member.", actions: [] };
    return { path: member.path, note: `The pit's capture ${name} stays on the pit. This is this laptop's own recording of the same robot at that time, from the pit's gateway.` };
  }
  return { message: `${name} is the pit's capture, which stays on the pit: the mirror copies the logs the pit pulled, not its captures. This laptop has no recording of that session from the pit's gateway.`, actions: [] };
}

/** Times as the listing writes them; an open session runs until now. */
function span(session: ListedLog["session"], now: number): [number, number] | undefined {
  if (!session) return undefined;
  const start = Date.parse(session.started_at), end = session.open || !session.ended_at ? now : Date.parse(session.ended_at);
  return Number.isFinite(start) && Number.isFinite(end) ? [start, Math.max(start, end)] : undefined;
}
/** Compare local catalog paths as the platform does, for both store roots and current members. */
function sameLocalPath(a: string, b: string, pathApi: path.PlatformPath): boolean {
  const clean = (folder: string) => pathApi.normalize(folder).replace(/(?<=.)[\\/]+$/, "");
  const x = clean(a), y = clean(b);
  return pathApi === path.win32 ? x.toLowerCase() === y.toLowerCase() : x === y;
}
function ownRecording(remote: ListedLog, local: LogListing, mirror: MirrorStatus, now: number, pathApi: path.PlatformPath): ListedLog | undefined {
  const wanted = span(remote.session, now); if (!wanted) return undefined;
  const mirrorFolder = mirror.config?.folder;
  let best: ListedLog | undefined, longest = 0;
  for (const log of local.logs ?? []) {
    if (log.kind === "revlog" || log.session?.open === undefined || !log.store) continue;
    if (mirrorFolder && sameLocalPath(log.store, mirrorFolder, pathApi)) continue;
    const serial = remote.robot?.serial_number, other = log.robot?.serial_number;
    if (!serial || !other || serial !== other) continue;
    const have = span(log.session, now); if (!have) continue;
    const overlap = Math.min(wanted[1], have[1]) - Math.max(wanted[0], have[0]);
    if (overlap > longest || overlap === longest && overlap > 0 && best && log.path < best.path) { best = log; longest = overlap; }
  }
  return longest > 0 ? best : undefined;
}
function notCopied(name: string, mirror: MirrorStatus): PitOverview {
  const status = { title: "Mirror Status", command: "wpilog-mcp.mirrorActions" }, pin = { title: "Pin Session…", command: "wpilog-mcp.pinSession" };
  switch (mirror.state) {
    case "synchronizing":
      return { message: `The mirror has not copied ${name} yet: ${mirror.remaining_files ?? 0} files, ${formatSize(mirror.remaining_bytes ?? 0)} remaining.`, actions: [status] };
    case "waiting":
      return { message: `The mirror has not copied ${name} yet; its next pass is waiting (last synchronized ${ageText(mirror.age_sec)}).`, actions: [status] };
    case "synchronized":
      return { message: `The mirror has not copied ${name}: it arrived after the last pass (${ageText(mirror.age_sec)}), or its session is outside the mirror's days, robots or size. A pinned session is always copied.`, actions: [pin, status] };
    case "offline":
      return { message: `The mirror cannot reach the pit (last synchronized ${ageText(mirror.age_sec)}), so ${name} is not on this laptop yet.`, actions: [status] };
    case "error": case "partial":
      return { message: `The mirror needs attention, so ${name} may not be copied${mirror.result?.reason ? ": " + mirror.result.reason : ""}.`, actions: [status] };
    default:
      return { message: `${name} is not on this laptop: the mirror is off. Turn on wpilog-mcp.mirror.enabled to copy the pit's logs.`, actions: [] };
  }
}

/** The remote root contains sessions newest first; remote paths never use the laptop's path API. */
export function pitLogTree(listing: LogListing, local: LogListing, origin: string, filter = ""): LogNode[] {
  if (listing.status === "error") return [{ kind: "note", label: "Pit server offline", tooltip: listing.error }];
  const sessions = new Map<string, ListedLog[]>();
  for (const log of listing.logs ?? []) if (log.session && logMatches(log, filter)) sessions.set(log.session.id, [...(sessions.get(log.session.id) ?? []), log]);
  return [...sessions.values()].sort((a, b) => b[0].session!.started_at.localeCompare(a[0].session!.started_at)).map(logs => {
    const first = logs[0], session = first.session!;
    const mirrored = logs.every(log => mirroredCopy(log, local, origin));
    const children = logs.flatMap(log => [log, ...(log.revlogs ?? []).map(rev => ({ ...log, ...rev,
      friendly_name: rev.filename, kind: "revlog" as const, wpilog: log.path }))])
      .map(log => ({ kind: "log" as const, label: log.filename, description: formatSize(log.size_bytes), tooltip: log.path, log }));
    return { kind: "session", label: [first.robot?.name || first.robot?.serial_number || "Unknown robot", session.started_at,
      first.event, first.match_type, first.match_number, session.open ? "open" : "closed", mirrored ? "mirrored" : undefined].filter(v => v !== undefined && v !== "").join(" · "),
      tooltip: session.path, children };
  });
}
