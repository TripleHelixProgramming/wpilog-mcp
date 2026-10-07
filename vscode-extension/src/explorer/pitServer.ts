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
export function remoteLogUri(url: string, logPath: string, session?: ListedLog["session"]): string {
  const name = logPath.replace(/\\/g, "/").split("/").pop() || "capture.wpilog";
  const query = new URLSearchParams({ url: pitEndpoint(url)!, path: logPath });
  if (session) { query.set("session", session.id); query.set("session_path", session.path);
    if (session.open !== undefined) query.set("open", String(session.open)); }
  return `wpilog-pit:/${encodeURIComponent(name)}?${query}`;
}
export function readRemoteLogUri(value: string): { url: string; path: string; session?: ListedLog["session"] } | undefined {
  const uri = new URL(value);
  if (uri.protocol !== "wpilog-pit:") return undefined;
  const url = pitEndpoint(uri.searchParams.get("url") ?? undefined), logPath = uri.searchParams.get("path");
  if (!url || !logPath) throw new Error("Remote log link is missing its server or path.");
  const id = uri.searchParams.get("session"), sessionPath = uri.searchParams.get("session_path");
  return { url, path: logPath, ...(id && sessionPath ? { session: { id, path: sessionPath, started_at: "", ended_at: "", start_basis: "", ...(uri.searchParams.has("open") ? { open: uri.searchParams.get("open") === "true" } : {}) } } : {}) };
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
