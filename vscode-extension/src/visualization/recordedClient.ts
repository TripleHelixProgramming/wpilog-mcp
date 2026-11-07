import { randomUUID } from "node:crypto";
import { McpClient, VisualizationRequest, LocalControlError } from "../mcpClient";
import { Backend, Configuration } from "./documentController";
import { Inventory, RecordedView, SourceRef } from "./recordedModel";
import { SourceContext } from "../connections";
export interface SourceSummary { source_ref: SourceRef; path: string; label: string; catalog: unknown; inventory_state: string; }
export interface StoreChoice { store_id: string; label: string; path: string; writable: boolean; }
export interface SaveChoice { title: string; }
export interface Capabilities { server_instance_id: string; working_state: string; working_state_reason?: string; blank_configuration: Configuration; library?: { available: boolean; unavailable_reason: string; library_id: string | null }; }

/** One window's adapter borrows its existing MCP session and directory grants. */
export class RecordedClient implements Backend {
  readonly destinations = new Map<string, SaveChoice>();
  private readonly pendingSaves = new Map<string, { request: { revision: number; request_id: string; title?: string }; uncertain: boolean }>();
  constructor(readonly client: () => Promise<McpClient>, readonly context?: SourceContext) {}
  async call<T>(route: string, body?: unknown, signal?: AbortSignal, options?: VisualizationRequest): Promise<T> { const library = route.startsWith("/templates/") || route.startsWith("/library/") || route.startsWith("/views") || route === "/saved/open" || route === "/source/views";
    const client = await this.client();
    if (body && typeof body === "object" && "source_ref" in body) this.context?.requireReference(body.source_ref);
    const value = await client.visualization<T>(route, body, signal, { apiVersion: this.context || library ? 2 : 1, ...options });
    this.context?.assertCurrent();
    if (value && typeof value === "object" && "source_ref" in value) this.context?.requireReference(value.source_ref);
    return value; }
  capabilities(): Promise<Capabilities> { return this.call("/capabilities"); }
  identify(path: string, signal?: AbortSignal): Promise<SourceSummary> { return this.call("/source", { path, ...(this.context?.scopeId ? { scope_id: this.context.scopeId } : {}) }, signal); }
  describe(source_ref: SourceRef, signal?: AbortSignal): Promise<Inventory> { return this.call("/source/describe", { source_ref }, signal); }
  lists(source_ref: SourceRef, after?: string): Promise<{ unreadable: { store_id: string; file: string; reason: string }[]; listing_incomplete: boolean; working_views: RecordedView[]; saved_views: (RecordedView & { saved_view_id: string; saved_view_ref: NonNullable<RecordedView["saved_view_ref"]>; title: string })[]; next: string | null; total_matching: number }> {
    return this.call("/source/views", { source_ref, ...(after ? { after } : {}) });
  }
  create(source_ref: SourceRef, configuration?: Configuration): Promise<RecordedView> { return this.call("/views", { request_id: randomUUID(), source_ref, ...(configuration ? { configuration } : {}) }); }
  read(id: string): Promise<RecordedView> { return this.call(`/views/${id}`); }
  attach(id: string): Promise<RecordedView> { return this.call(`/views/${id}/attach`, {}); }
  detach(id: string): Promise<RecordedView> { return this.call(`/views/${id}/detach`, {}); }
  async update(id: string, revision: number, configuration: Configuration): Promise<RecordedView> {
    await this.attach(id); return this.call(`/views/${id}`, { revision, configuration });
  }
  async save(id: string, revision: number): Promise<RecordedView> {
    await this.attach(id);
    let intent = this.pendingSaves.get(id);
    if (!intent) { intent = { request: { revision, request_id: randomUUID(), ...this.destinations.get(id) }, uncertain: false }; this.pendingSaves.set(id, intent); }
    try {
      const saved = await this.call<RecordedView>(`/views/${id}/save`, intent.request);
      // A completed receipt is immutable, even after later edits or saves. Recover that
      // intent first, then read the current working view and its checkpoint together. Keep
      // the UUID until both succeed so a failed read cannot duplicate the library write.
      const current = intent.uncertain ? await this.read(id) : saved;
      this.pendingSaves.delete(id); return current;
    } catch (error) {
      // Only an explicit refusal before publication permits a corrected save intent. Unknown
      // transport outcomes and pending checkpoints must recover the original request first.
      if (!intent.uncertain && error instanceof LocalControlError && ["LIBRARY_LIMIT", "LIBRARY_UNAVAILABLE", "REVISION_CONFLICT", "REQUEST_CONFLICT", "VIEW_UNAVAILABLE", "VIEW_ATTACHED"].includes(error.code ?? "")) this.pendingSaves.delete(id);
      else intent.uncertain = true;
      throw error;
    }
  }
}
