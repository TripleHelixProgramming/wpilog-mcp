/** A shared server owns ongoing ingestion; an editor only reviews and submits explicit choices. */
import * as path from "path";
import { McpClient, LocalControlError } from "../mcpClient";
import { containsPath, folderKey } from "./organize";
import { LogListing, directoryPaths } from "./logsTree";

export const DATA_STORE_ENABLED_CONTEXT = "wpilog-mcp.dataStoreEnabled";

/** A disabled server is an expected discovery result, not a policy revision conflict. */
export function dataStoreDisabled(error: unknown): error is LocalControlError {
  return error instanceof LocalControlError && error.code === "FEATURE_DISABLED";
}

export interface IngestionSource {
  path: string;
  mode: "copy" | "move";
  enabled: boolean;
  completed_files: boolean;
  stated_robot: string | null;
}
export interface IngestionPolicy {
  revision: number;
  enabled: boolean;
  store: string | null;
  store_id: string | null;
  sources: IngestionSource[];
}
export interface IngestionStatus {
  policy: IngestionPolicy | null;
  preferred_store_id?: string | null;
  pending?: number;
  sources?: { path: string; state?: string; reason?: string; [key: string]: unknown }[];
}
export interface IngestionFiles {
  files: { path: string; state: string; reason?: string | null; destination?: string | null }[];
  offset: number;
  limit: number;
  total: number;
}
export interface SourceCandidate {
  path: string;
  origin: "configured" | "user" | "project or session" | "temporary" | "saved";
  selected: boolean;
}

/** macOS uses a per-user TMPDIR, but /tmp and /var/tmp are temporary locations too. */
export function temporaryDirectoryRoots(platform: NodeJS.Platform, temp: string,
  environment: NodeJS.ProcessEnv = process.env): string[] {
  return [...new Set([temp, environment.TMPDIR, environment.TMP, environment.TEMP,
    ...(platform === "win32" ? [] : ["/tmp", "/var/tmp"])].filter((value): value is string => !!value))];
}

/** A lease is not evidence of a temporary folder: permanent User roots are leased too. */
export function sourceCandidates(listing: LogListing, userRoots: readonly string[], temporaryRoots: readonly string[],
  store: string, policy: IngestionPolicy | null, paths: path.PlatformPath = path): SourceCandidate[] {
  const candidates = new Map<string, SourceCandidate>();
  const saved = new Map((policy?.sources ?? []).map(source => [folderKey(source.path, paths), source]));
  const configured = new Set((listing.log_directories ?? []).filter(dir => typeof dir !== "string" && dir.origin === "configured")
    .map(dir => folderKey(typeof dir === "string" ? dir : dir.path, paths)));
  const permanent = new Set(userRoots.map(root => folderKey(root, paths)));
  for (const root of [...directoryPaths(listing), ...userRoots, ...saved.values()].map(root => typeof root === "string" ? root : root.path)) {
    const key = folderKey(root, paths);
    if (containsPath(store, root, paths) || (listing.stores ?? []).some(known => containsPath(known.path, root, paths))) continue;
    const temporary = temporaryRoots.some(temp => containsPath(temp, root, paths));
    const origin: SourceCandidate["origin"] = temporary ? "temporary" : configured.has(key) ? "configured"
      : permanent.has(key) ? "user" : saved.has(key) ? "saved" : "project or session";
    candidates.set(key, { path: root, origin, selected: policy ? saved.get(key)?.enabled === true
      : !temporary && (configured.has(key) || permanent.has(key)) });
  }
  return [...candidates.values()];
}

/** Overlapping enrollments are ambiguous; the person chooses one owner and one removal policy. */
export function ingestionPolicyError(store: string, sources: readonly IngestionSource[], listing: LogListing,
  paths: path.PlatformPath = path): string | undefined {
  if (!sources.length) return "Choose at least one source folder.";
  if ((listing.stores ?? []).some(known => known.mirror && containsPath(known.path, store, paths))) return "A mirror is read-only; choose a writable data store.";
  if ((listing.stores ?? []).some(known => containsPath(known.path, store, paths) && folderKey(known.path, paths) !== folderKey(store, paths))) {
    return "Choose the store root, not a folder inside an existing store.";
  }
  for (const source of sources) {
    if (containsPath(store, source.path, paths)) return "The destination cannot contain a source folder.";
    if ((listing.stores ?? []).some(known => containsPath(known.path, source.path, paths))) return "An existing store or mirror cannot be an ordinary source folder.";
    if (source.mode === "move" && !source.completed_files) return "Move requires a source that contains only finished files.";
  }
  for (let i = 0; i < sources.length; i++) for (let j = i + 1; j < sources.length; j++) {
    if (containsPath(sources[i].path, sources[j].path, paths) || containsPath(sources[j].path, sources[i].path, paths)) {
      return `Source folders overlap: ${sources[i].path} and ${sources[j].path}. Choose one enrollment.`;
    }
  }
  return undefined;
}

export function ingestionReview(policy: IngestionPolicy): string {
  return [`Store: ${policy.store}`, ...policy.sources.map(source => `${source.mode === "move" ? "Move finished files" : "Copy; keep originals"}: ${source.path}`),
    "This shared server keeps synchronizing after VS Code closes. Newly configured folders need your approval.",
    "Files already present by content are not copied again; duplicate source files stay where they are."].join("\n");
}

export class LocalIngestionClient {
  constructor(private readonly client: McpClient) {}
  status(): Promise<IngestionStatus> { return this.client.requestLocalControl("/store/local-ingestion", "GET"); }
  save(policy: IngestionPolicy, admitReviewedRoots = true): Promise<IngestionStatus> {
    // Pausing uses the policy's existing grants: an unplugged source cannot be re-registered.
    if (!admitReviewedRoots) return this.client.requestLocalControl("/store/local-ingestion", "PUT", policy);
    return this.client.withTemporaryDirectories([...(policy.store ? [policy.store] : []), ...policy.sources.map(source => source.path)], request =>
      request<IngestionStatus>("/store/local-ingestion", "PUT", policy));
  }
  scan(): Promise<IngestionStatus> { return this.client.requestLocalControl("/store/local-ingestion/scan", "POST", {}); }
  files(offset = 0, limit = 100): Promise<IngestionFiles> {
    return this.client.requestLocalControl(`/store/local-ingestion/files?offset=${offset}&limit=${limit}`, "GET");
  }
}
