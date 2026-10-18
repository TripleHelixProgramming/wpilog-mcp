import { Compatibility } from "./recordedModel";
import { FollowLease, FollowSelection, FollowSource, FollowWindow, RecordingClock, recordingClock, canonical, freezeJson, object, protocol, selectionKey } from "./followProtocol";

export const VIEW_PAGE_BYTES = 1 << 20;
export interface ViewFollowRequest { readonly view_id: string; readonly expected_revision: number }
export interface ViewFollowBinding {
  readonly api_version: 1; readonly view_id: string; readonly revision: number; readonly source_ref: FollowSource;
  readonly compatibility: Compatibility; readonly clock: RecordingClock;
  readonly time_policy: { readonly mode: "full" | "fixed" | "follow"; readonly start_sec?: number; readonly end_sec?: number; readonly window_sec?: number };
  readonly queries: readonly (FollowSelection & { readonly windows: readonly FollowWindow[] })[];
  readonly follow_ready: boolean; readonly reason: string; readonly frontier_scope: "queries"; readonly max_page_bytes: number;
}
export interface ViewFollowLease extends FollowLease { readonly view_binding: ViewFollowBinding }
export function viewRequest(request: ViewFollowRequest): void {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(request.view_id)
    || !Number.isSafeInteger(request.expected_revision) || request.expected_revision < 1) throw new Error("A working view UUID and positive expected revision are required");
}
/** This validates a negotiated plan; it never resolves names, roles, units or clock evidence. */
export function viewBinding(value: unknown, expected: ViewFollowRequest, source: FollowSource): ViewFollowBinding {
  const b = object(value);
  if (b.api_version !== 1 || b.view_id !== expected.view_id || b.revision !== expected.expected_revision
    || canonical(b.source_ref) !== canonical(source) || b.frontier_scope !== "queries" || b.max_page_bytes !== VIEW_PAGE_BYTES
    || typeof b.follow_ready !== "boolean" || typeof b.reason !== "string") protocol("View binding identity or policy mismatch");
  const clock = recordingClock(b.clock);
  const time = object(b.time_policy);
  if (!["full", "fixed", "follow"].includes(time.mode)) protocol("Invalid view time policy");
  if (time.mode === "follow" && (!Number.isFinite(time.window_sec) || time.window_sec < 1 || time.window_sec > 300)) protocol("Invalid following window");
  if (time.mode === "fixed" && (!Number.isFinite(time.start_sec) || !Number.isFinite(time.end_sec) || time.start_sec > time.end_sec)) protocol("Invalid fixed window");
  const compatibility = object(b.compatibility), resolved = new Set<string>(), requirements = new Set<string>();
  if (!["compatible", "partial", "incompatible"].includes(compatibility.compatibility)
    || !Array.isArray(compatibility.bindings) || compatibility.bindings.length > 256) protocol("Invalid view compatibility");
  for (const raw of compatibility.bindings) {
    const binding = object(raw);
    if (typeof binding.requirement_id !== "string" || !binding.requirement_id || requirements.has(binding.requirement_id)
      || !["resolved", "unresolved"].includes(binding.state) || typeof binding.reason !== "string"
      || !Array.isArray(binding.warnings) || binding.warnings.some((w: unknown) => typeof w !== "string")) protocol("Invalid view requirement");
    requirements.add(binding.requirement_id);
    if (binding.state === "resolved") {
      if (typeof binding.entry !== "string" || !binding.entry || (binding.field_path !== null && typeof binding.field_path !== "string")) protocol("Invalid resolved selection");
      resolved.add(selectionKey({ entry: binding.entry, field: binding.field_path }));
    }
  }
  if (!Array.isArray(b.queries) || b.queries.length > 32) protocol("Invalid view selection count");
  const queries = new Set<string>(); let windows: string | undefined;
  for (const raw of b.queries) {
    const q = object(raw), key = selectionKey(q as FollowSelection);
    if (!resolved.has(key) || queries.has(key) || q.order !== "file" || q.batch_records !== 4096 || q.batch_bytes !== VIEW_PAGE_BYTES
      || !Array.isArray(q.windows) || q.windows.length > 1 || q.reduction !== undefined || q.first_ordinal !== undefined) protocol("Invalid server-owned view query");
    for (const raw of q.windows) {
      const w = object(raw);
      for (const k of ["start_us", "end_us"]) if (typeof w[k] !== "string" || !/^-?(0|[1-9][0-9]{0,15})$/.test(w[k]) || !Number.isSafeInteger(Number(w[k]))) protocol("Invalid view window");
      if (BigInt(w.start_us) > BigInt(w.end_us) || w.end_inclusive !== true) protocol("Invalid view window bounds");
    }
    const encoded = canonical(q.windows); if (windows !== undefined && encoded !== windows) protocol("View queries disagree on their interval"); windows = encoded;
    queries.add(key);
  }
  if (queries.size !== resolved.size || (b.follow_ready && (!queries.size || time.mode === "follow" && clock.right_edge_sec === null))) protocol("View readiness disagrees with binding evidence");
  return freezeJson(b as ViewFollowBinding, VIEW_PAGE_BYTES);
}
