/** Native follow wire values stay tagged: this layer neither decodes nor orders telemetry. */
export const FOLLOW_PAGE_BYTES = 4 << 20;
export type Json = null | boolean | number | string | readonly Json[] | { readonly [key: string]: Json };
export interface FollowSource {
  readonly kind: "session";
  readonly origin_store_id: string;
  readonly session_id: string;
  readonly anchor_member: { readonly original_name: string; readonly recording_started_at: string };
  readonly finalized_members?: readonly { readonly content_sha256: string }[];
}
export interface FollowWindow { readonly start_us: string; readonly end_us: string; readonly end_inclusive: boolean }
export interface FollowSelection { readonly entry: string; readonly field?: string }
export type FollowFrontierScope = "all" | "queries";
export interface FollowLease { readonly id: string; readonly server_epoch: string; readonly cursor: string; readonly frontier_scope?: FollowFrontierScope }
export interface FollowFrontier {
  readonly recording_id: string; readonly catalog_revision: string; readonly finalized: boolean;
  readonly frontier_scope?: FollowFrontierScope;
  readonly members: readonly { readonly member_id: string; readonly snapshot: { readonly [key: string]: Json } }[];
}
export interface FollowSample {
  readonly timestamp_us: string; readonly record_offset: string; readonly ordinal: number;
  readonly payload_bytes: string; readonly value: Json;
}
export interface FollowBatch {
  readonly member_id: string; readonly entry: string; readonly field: string | null; readonly next_ordinal: number;
  readonly batch: { readonly samples: readonly FollowSample[]; readonly problems: readonly { readonly [key: string]: Json }[];
    readonly records_visited: number; readonly payload_bytes: string; readonly complete: boolean };
}
export interface FollowPage {
  readonly api_version: 1; readonly server_epoch: string; readonly follow_id: string;
  readonly cursor: string; readonly next_cursor: string; readonly prefix_id: string;
  readonly bootstrap: boolean; readonly frontier_complete: boolean; readonly ended: boolean;
  readonly frontier?: FollowFrontier; readonly batches: readonly FollowBatch[];
}
export class FollowError extends Error {
  constructor(readonly code: string, message: string, readonly status = 0, readonly retryable = false) { super(message); }
}
export function protocol(message: string): never { throw new FollowError("INVALID_RESPONSE", message); }
export function readFrontierScope(value: unknown): FollowFrontierScope {
  if (value === undefined || value === "all") return "all";
  if (value === "queries") return "queries";
  return protocol("Invalid follow frontier scope");
}
export function object(value: unknown): Record<string, any> {
  if (!value || typeof value !== "object" || Array.isArray(value)) protocol("Expected a follow object");
  return value as Record<string, any>;
}
export function text(value: unknown): string {
  if (typeof value !== "string" || value.length === 0) protocol("Expected nonempty follow text");
  return value;
}
function bool(value: unknown): void { if (typeof value !== "boolean") protocol("Expected follow boolean"); }
function integer(value: unknown, max = 2_147_483_647): number {
  if (typeof value !== "number" || !Number.isInteger(value) || value < 0 || value > max) protocol("Expected bounded follow integer");
  return value;
}
function decimal(value: unknown, unsigned = false): void {
  if (typeof value !== "string" || !/^-?(0|[1-9][0-9]*)$/.test(value) || value.length > 20) protocol("Expected decimal string");
  const n = BigInt(value);
  if (n < (unsigned ? 0n : -(1n << 63n)) || n > (unsigned ? (1n << 64n) - 1n : (1n << 63n) - 1n)) protocol("Integer outside wire range");
}
function array(value: unknown): any[] { if (!Array.isArray(value)) protocol("Expected follow array"); return value; }
function valueType(value: unknown, depth = 0): void {
  if (depth > 64) protocol("Follow value nesting limit");
  if (value === null) return;
  const v = object(value), type = text(v.type);
  switch (type) {
    case "bool": bool(v.value); break;
    case "string": if (typeof v.value !== "string") protocol("Expected string value"); break;
    case "i64": case "enum": decimal(v.value); break;
    case "u64": case "enum_u64": decimal(v.value, true); break;
    case "f32": case "f64":
      if (typeof v.value !== "string" || !/^[0-9a-f]+$/.test(v.value) || v.value.length > (type === "f32" ? 8 : 16)) protocol("Expected float bits");
      break;
    case "bytes":
      if (typeof v.value !== "string" || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(v.value)) protocol("Expected base64 bytes");
      break;
    case "struct": Object.values(object(v.value)).forEach(child => valueType(child, depth + 1)); break;
    case "list": case "i64[]": case "f32[]": case "f64[]": case "bool[]": case "string[]":
      array(v.value).forEach(child => { valueType(child, depth + 1);
        if (type !== "list" && child !== null && child.type !== type.slice(0, -2)) protocol("Array element type mismatch"); });
      break;
    default: protocol("Unknown native value tag");
  }
  if (type.startsWith("enum") && v.label !== null && typeof v.label !== "string") protocol("Expected enum label");
}
/** Every JSON node needs at least one encoded byte. Using the response's byte budget also bounds
 * traversal without refusing legal pages rich in tagged array elements. Depth is bounded separately. */
export function freezeJson<T>(value: T, byteBudget = FOLLOW_PAGE_BYTES): T {
  let count = 0;
  const visit = (node: any, depth: number): void => {
    if (++count > byteBudget || depth > 96) protocol("Follow structure limit exceeded");
    if (node && typeof node === "object") { Object.values(node).forEach(child => visit(child, depth + 1)); Object.freeze(node); }
  };
  visit(value, 0); return value;
}
export function selectionKey(selection: { readonly entry: string; readonly field?: string | null }): string { return JSON.stringify([selection.entry, selection.field ?? null]); }
export function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value && typeof value === "object") return `{${Object.entries(value).filter(([, v]) => v !== undefined)
    .sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([k, v]) => `${JSON.stringify(k)}:${canonical(v)}`).join(",")}}`;
  return JSON.stringify(value);
}
export function lease(value: unknown, source: FollowSource): FollowLease {
  const v = object(value);
  if (v.api_version !== 1 || canonical(v.source_ref) !== canonical(source)) protocol("Follow creation changed version or source");
  if (v.max_wait_ms !== 25000 || v.idle_timeout_sec !== 60) protocol("Unsupported follow timing contract");
  return Object.freeze({ id: text(v.id), server_epoch: text(v.server_epoch), cursor: text(v.cursor) });
}
export function page(value: unknown, lease: FollowLease, cursor: string, selections: ReadonlySet<string>,
  previous?: { readonly prefix: string; readonly frontier: FollowFrontier }, byteBudget = FOLLOW_PAGE_BYTES): { page: FollowPage; frontier: FollowFrontier } {
  const v = object(value);
  if (v.api_version !== 1 || v.server_epoch !== lease.server_epoch || v.follow_id !== lease.id || v.cursor !== cursor) protocol("Follow page identity mismatch");
  text(v.next_cursor); text(v.prefix_id);
  if (v.next_cursor === cursor) protocol("Follow cursor did not advance");
  bool(v.bootstrap); bool(v.frontier_complete); bool(v.ended);
  if (v.ended && !v.frontier_complete) protocol("Incomplete terminal page");
  const frontier = v.frontier === undefined ? previous?.frontier : object(v.frontier);
  if (!frontier || (v.frontier === undefined && previous?.prefix !== v.prefix_id)) protocol("Missing follow frontier");
  const scope = readFrontierScope(frontier.frontier_scope);
  if (scope !== (lease.frontier_scope ?? "all")) protocol("Follow frontier scope changed");
  text(frontier.recording_id); decimal(frontier.catalog_revision, true); bool(frontier.finalized);
  if (previous && previous.frontier.recording_id !== frontier.recording_id) protocol("Follow recording identity changed");
  if (v.ended && !frontier.finalized) protocol("Terminal live frontier");
  const members = array(frontier.members), ids = new Set<string>();
  const selectedEntries = new Set([...selections].map(key => JSON.parse(key)[0] as string));
  if (members.length < 1 || members.length > 64) protocol("Invalid follow member count");
  for (const m of members) { text(m.member_id); if (ids.has(m.member_id)) protocol("Duplicate follow member");
    ids.add(m.member_id); decimal(m.generation, true); object(m.snapshot); object(m.snapshot.entries); object(m.snapshot.sample_counts);
    if (scope === "queries") {
      const entries = Object.keys(m.snapshot.entries), counts = Object.keys(m.snapshot.sample_counts);
      if (entries.some(name => !selectedEntries.has(name)) || counts.length !== entries.length
          || counts.some(name => !Object.hasOwn(m.snapshot.entries, name))) protocol("Unrequested follow metadata");
      counts.forEach(name => decimal(m.snapshot.sample_counts[name], true));
    }
  }
  let visited = 0;
  for (const b of array(v.batches)) {
    if (!ids.has(b.member_id) || !selections.has(selectionKey(b))) protocol("Unrequested follow selection");
    text(b.entry); if (b.field !== null && typeof b.field !== "string") protocol("Invalid field projection");
    const next = integer(b.next_ordinal), batch = object(b.batch);
    if (batch.kind !== "batch") protocol("Invalid native batch");
    visited += integer(batch.records_visited, 4096); decimal(batch.payload_bytes, true); bool(batch.complete);
    const ordinals = new Set<number>();
    for (const s of array(batch.samples)) {
      decimal(s.timestamp_us); decimal(s.record_offset, true); decimal(s.payload_bytes, true); valueType(s.value);
      integer(s.ordinal); if (s.ordinal >= next || ordinals.has(s.ordinal)) protocol("Invalid sample ordinal"); ordinals.add(s.ordinal);
    }
    for (const p of array(batch.problems)) {
      text(p.code); text(p.message); if (p.entry !== b.entry) protocol("Problem selection mismatch");
      // Oversized follow gaps use a JSON number; ordinary native problems use decimal text.
      // Never round an offset from a remote reader into a different physical record.
      if (typeof p.record_offset === "number") {
        if (!Number.isSafeInteger(p.record_offset) || p.record_offset < 0) protocol("Unsafe problem offset");
      } else decimal(p.record_offset, true);
      decimal(p.payload_bytes, true); integer(p.ordinal);
      if (p.ordinal >= next || ordinals.has(p.ordinal)) protocol("Invalid problem ordinal"); ordinals.add(p.ordinal);
    }
    if (ordinals.size > batch.records_visited) protocol("Incorrect batch work count");
  }
  if (visited > 4096) protocol("Follow page work limit");
  freezeJson(v, byteBudget);
  return { page: v as unknown as FollowPage, frontier: frontier as FollowFrontier };
}
