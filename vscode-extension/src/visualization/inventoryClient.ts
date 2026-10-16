import { McpClient } from "../mcpClient";
import { SourceRef } from "./recordedModel";
import { ByteBudget } from "./byteBudget";
import { canonical } from "./followProtocol";

export const INVENTORY_PAGE_BYTES = 1 << 20;
export interface InventoryRow {
  row_id: string; name?: string; type?: string | null; display_preview?: string; reason?: string;
  sample_count: string | null; conflict: boolean; unavailable: boolean;
  members: { member_id: string; type: string | null; sample_count: string }[];
}
export interface InventoryFilter { query: string; types: string[]; unavailable: boolean; }
export interface InventoryPage {
  entries: InventoryRow[]; total_matching: number; total_entry_names: number; unavailable_excluded: boolean; unavailable_entry_names: number;
  complete: boolean; next_cursor: string | null; returned: number;
}
export interface InventorySummary {
  inventory_mode: "paged"; inventory_id: string; server_epoch: string; source_ref: SourceRef;
  expires_at: string; idle_expiry_ms: number; total_entry_names: number; conflicting_entry_names: number;
  unavailable_entry_names: number; members: unknown[]; clock?: unknown; duration_sec?: number;
}
export interface InventoryHandle { summary: InventorySummary; session: string; client: McpClient; }
export interface InventoryDetails {
  selected_rows: InventoryRow[]; detail_revision: string; count_basis: "fresh_admission";
  counts_grew_since_inventory: boolean; descriptors: unknown[]; schema_problems: unknown[];
}
export interface InventoryBackend {
  open(source: SourceRef, signal: AbortSignal): Promise<InventoryHandle>;
  list(handle: InventoryHandle, filter: InventoryFilter, cursor: string | undefined, signal: AbortSignal): Promise<InventoryPage>;
  details(handle: InventoryHandle, row: InventoryRow, signal: AbortSignal): Promise<InventoryDetails>;
  close(handle: InventoryHandle): Promise<void>;
}
function invalid(): never { throw new Error("Invalid paged inventory response; update the server and Retry"); }
function object(v: any): any { if (!v || typeof v !== "object" || Array.isArray(v)) invalid(); return v; }
function text(v: any): string { if (typeof v !== "string" || !v.length) invalid(); return v; }
function count(v: any): void { if (!Number.isSafeInteger(v) || v < 0) invalid(); }
function decimal(v: any): void { if (typeof v !== "string" || !/^(0|[1-9][0-9]{0,18})$/.test(v) || BigInt(v) > 9223372036854775807n) invalid(); }
function row(v: any): InventoryRow {
  object(v); text(v.row_id); if (typeof v.unavailable !== "boolean" || typeof v.conflict !== "boolean") invalid();
  if (!v.unavailable) { text(v.name); if (!v.conflict) text(v.type); }
  else { text(v.display_preview); text(v.reason); }
  if (v.sample_count !== null) decimal(v.sample_count);
  if (!Array.isArray(v.members)) invalid();
  v.members.forEach((m: any) => { object(m); text(m.member_id); if (!v.unavailable) text(m.type); decimal(m.sample_count); });
  return v;
}
/** Uses the window's existing credentials/grants, never a second MCP connection. Handles retain
 * their opening client and session; restart means reopen, never replay a row id in a new owner. */
export class InventoryClient implements InventoryBackend {
  constructor(private readonly client: () => Promise<McpClient>, private readonly budget: ByteBudget) {}
  private async request<T>(client: McpClient, route: string, body: unknown, signal?: AbortSignal, session?: string,
    captureSession?: (session: string) => void): Promise<T> {
    const release = this.budget.reserve(2 * INVENTORY_PAGE_BYTES);
    try { return await client.visualization<T>(route, body, signal, { maxRequestBytes: 64 << 10,
      maxResponseBytes: INVENTORY_PAGE_BYTES, timeoutMs: 15000, session, captureSession }); }
    finally { release(); }
  }
  async open(source: SourceRef, signal: AbortSignal): Promise<InventoryHandle> {
    const client = await this.client();
    const caps = object(await this.request(client, "/capabilities", undefined, signal));
    if (caps.recording_inventory_paging !== true) throw new Error("This server does not support paged recording inventories; update the server and Retry");
    let session = "";
    const summary = object(await this.request(client, "/source/describe", { source_ref: source, inventory_mode: "paged" }, signal, undefined, s => session = s));
    const handle = { summary, session, client };
    try {
      if (summary.inventory_mode !== "paged" || canonical(summary.source_ref) !== canonical(source)) invalid();
      text(summary.inventory_id); text(summary.server_epoch);
      if (!Number.isFinite(Date.parse(summary.expires_at)) || !(summary.idle_expiry_ms > 0)) invalid();
      count(summary.total_entry_names); count(summary.conflicting_entry_names); count(summary.unavailable_entry_names);
      if (!Array.isArray(summary.members) || "entries" in summary || "schemas" in summary) invalid();
      if (signal.aborted) throw new Error("Inventory open cancelled");
      return handle;
    } catch (error) { if (typeof summary.inventory_id === "string") await this.close(handle).catch(() => {}); throw error; }
  }
  async list(h: InventoryHandle, filter: InventoryFilter, cursor: string | undefined, signal: AbortSignal): Promise<InventoryPage> {
    const value = object(await this.request(h.client, "/source/entries/list", { inventory_id: h.summary.inventory_id,
      ...(cursor ? { cursor } : { ...(filter.unavailable ? { unavailable: true } : filter), limit: 256 }) }, signal, h.session));
    if (!Array.isArray(value.entries) || value.entries.length > 256) invalid();
    count(value.total_matching); count(value.total_entry_names); count(value.unavailable_entry_names); count(value.returned);
    if (typeof value.unavailable_excluded !== "boolean" || value.inventory_id !== h.summary.inventory_id || value.server_epoch !== h.summary.server_epoch) invalid();
    if (typeof value.complete !== "boolean" || (value.complete ? value.next_cursor != null : typeof value.next_cursor !== "string")
      || value.returned !== value.entries.length || value.total_matching < value.returned
      || value.total_entry_names !== h.summary.total_entry_names || value.total_matching > value.total_entry_names
      || (!value.complete && value.next_cursor === cursor)) invalid();
    value.entries.forEach(row);
    if (new Set(value.entries.map((r: InventoryRow) => r.row_id)).size !== value.entries.length) invalid();
    return value;
  }
  async details(h: InventoryHandle, selected: InventoryRow, signal: AbortSignal): Promise<InventoryDetails> {
    const value = object(await this.request(h.client, "/source/entries/get", { inventory_id: h.summary.inventory_id, row_ids: [selected.row_id] }, signal, h.session));
    if (value.inventory_id !== h.summary.inventory_id || value.server_epoch !== h.summary.server_epoch || value.count_basis !== "fresh_admission" || typeof value.counts_grew_since_inventory !== "boolean"
      || !Array.isArray(value.descriptors) || !Array.isArray(value.schema_problems) || !Array.isArray(value.selected_rows)
      || value.selected_rows.length !== 1 || row(value.selected_rows[0]).name !== selected.name || value.selected_rows[0].row_id !== selected.row_id) invalid();
    text(value.detail_revision);
    for (const d of value.descriptors) {
      object(d); text(d.member_id); const snapshot = object(d.snapshot); object(snapshot.entries); object(snapshot.sample_counts);
      if (!Array.isArray(snapshot.schemas)) invalid();
      for (const [name, e] of Object.entries(snapshot.entries)) { if (name !== selected.name) invalid(); object(e); text((e as any).type); decimal(snapshot.sample_counts[name]); }
      for (const schema of snapshot.schemas) { object(schema); text(schema.name); if (schema.schema !== null && typeof schema.schema !== "string") invalid(); }
    }
    return value;
  }
  async close(h: InventoryHandle): Promise<void> {
    await this.request(h.client, "/source/inventory/close", { inventory_id: h.summary.inventory_id }, undefined, h.session);
  }
}
