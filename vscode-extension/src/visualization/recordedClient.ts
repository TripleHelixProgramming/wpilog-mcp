import { randomUUID } from "node:crypto";
import { McpClient } from "../mcpClient";
import { Backend, Configuration } from "./documentController";
import { Inventory, RecordedView, SourceRef } from "./recordedModel";
export interface SourceSummary { source_ref: SourceRef; path: string; label: string; catalog: unknown; inventory_state: string; }
export interface StoreChoice { store_id: string; label: string; path: string; writable: boolean; }
export interface SaveChoice { store_id?: string; title: string; }
export interface Capabilities { server_instance_id: string; working_state: string; working_state_reason?: string; blank_configuration: Configuration; }

/** One window's adapter borrows its existing MCP session and directory grants. */
export class RecordedClient implements Backend {
  readonly destinations = new Map<string, SaveChoice>();
  constructor(readonly client: () => Promise<McpClient>) {}
  async call<T>(route: string, body?: unknown, signal?: AbortSignal): Promise<T> { return (await this.client()).visualization<T>(route, body, signal); }
  capabilities(): Promise<Capabilities> { return this.call("/capabilities"); }
  identify(path: string): Promise<SourceSummary> { return this.call("/source", { path }); }
  describe(source_ref: SourceRef, signal?: AbortSignal): Promise<Inventory> { return this.call("/source/describe", { source_ref }, signal); }
  lists(source_ref: SourceRef): Promise<{ unreadable: { store_id: string; file: string; reason: string }[]; listing_incomplete: boolean; working_views: RecordedView[]; saved_views: (RecordedView & { saved_view_id: string; store_id: string; title: string })[] }> {
    return this.call("/source/views", { source_ref });
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
    return this.call(`/views/${id}/save`, { revision, request_id: randomUUID(), ...this.destinations.get(id) });
  }
}
