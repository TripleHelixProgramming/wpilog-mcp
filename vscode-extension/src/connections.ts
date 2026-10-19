import { McpClient } from "./mcpClient";
import { HttpHeaders, noHeaders } from "./pitCredential";
import { SessionRegistration } from "./directoryLease";

export interface ConnectionDefinition {
  readonly id: string;
  readonly label: string;
  readonly kind: "local" | "remote";
  readonly url?: string;
  readonly serverInstanceId?: string;
}
export interface SourceScope { scope_id: string; path: string; kind: string; available: boolean; reason?: string; }
export interface ConnectionFacts { server_instance_id: string; server_epoch: string; }
export interface ConnectionHost {
  version: string;
  localEndpoint(): Promise<string>;
  registration?(): Promise<SessionRegistration>;
  headersFor?: HttpHeaders;
  pinned(id: string): string | undefined;
  pin(id: string, owner: string): Promise<void>;
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Configuration is a list of choices, never a preferred or first-available server. Reading it
 * must not start a daemon, resolve an address, or contact an inactive pit server. */
export function connectionDefinitions(value: unknown, pitUrl?: string): readonly ConnectionDefinition[] {
  const input = value == null ? [{ id: "local", label: "This laptop", kind: "local" },
    ...(pitUrl ? [{ id: "pit", label: "Pit server", kind: "remote", url: pitUrl }] : [])] : value;
  if (!Array.isArray(input) || input.length > 32) throw new Error("Connections must be an array of at most 32 choices");
  const ids = new Set<string>();
  return Object.freeze(input.map(raw => {
    if (!raw || typeof raw !== "object" || !/^[a-z0-9_-]{1,64}$/.test(raw.id) || ids.has(raw.id)
      || typeof raw.label !== "string" || !raw.label.trim() || raw.label.length > 128
      || !["local", "remote"].includes(raw.kind)) throw new Error("Every connection needs a unique id, label, and local or remote kind");
    if (raw.serverInstanceId !== undefined && (typeof raw.serverInstanceId !== "string" || !uuid.test(raw.serverInstanceId))) throw new Error("Invalid server identity");
    ids.add(raw.id);
    let url: string | undefined;
    if (raw.kind === "remote") {
      const parsed = new URL(raw.url);
      if (!["http:", "https:"].includes(parsed.protocol) || parsed.username || parsed.password || parsed.search || parsed.hash
        || !["", "/", "/mcp"].includes(parsed.pathname)) throw new Error("A remote connection needs an HTTP(S) MCP URL without credentials, query, or fragment");
      parsed.pathname = "/mcp"; url = parsed.toString();
    }
    return Object.freeze({ id: raw.id, label: raw.label, kind: raw.kind, ...(url ? { url } : {}),
      ...(raw.serverInstanceId ? { serverInstanceId: raw.serverInstanceId } : {}) }) as ConnectionDefinition;
  }));
}

/** Each context owns one immutable locator/scope and one MCP session. A replacement config
 * retires it: existing editors fail in place instead of following an id to another server. */
export class SourceContext {
  private clientValue?: Promise<McpClient>;
  private retired = false;
  private factsValue?: ConnectionFacts;
  constructor(readonly definition: ConnectionDefinition, readonly scopeId: string | undefined,
    private readonly host: ConnectionHost, private readonly verifyOwner: (facts: ConnectionFacts) => Promise<void>) {
    if (scopeId !== undefined && !uuid.test(scopeId)) throw new Error("Invalid source scope identity");
  }
  get facts(): ConnectionFacts | undefined { return this.factsValue; }
  assertCurrent(): void { if (this.retired) throw new Error("This connection was removed or changed; reopen the source explicitly"); }
  async client(): Promise<McpClient> {
    this.assertCurrent();
    const promise = this.clientValue ??= this.create();
    try { const client = await promise; this.assertCurrent(); return client; }
    catch (error) { if (this.clientValue === promise) this.clientValue = undefined; throw error; }
  }
  private async create(): Promise<McpClient> {
    const endpoint = this.definition.kind === "local" ? await this.host.localEndpoint() : this.definition.url!;
    this.assertCurrent();
    const client = new McpClient(endpoint, this.host.version,
      this.definition.kind === "local" ? this.host.registration : undefined, this.host.headersFor ?? noHeaders, {
        scopeId: this.scopeId,
        verify: async value => {
          this.assertCurrent();
          if (typeof value.server_instance_id !== "string" || !uuid.test(value.server_instance_id)
            || typeof value.server_epoch !== "string" || !uuid.test(value.server_epoch) || value.remote_visualization !== true)
            throw new Error("The selected server must support direct visualization API 2 and stable server identity");
          const facts = { server_instance_id: value.server_instance_id, server_epoch: value.server_epoch };
          if (this.factsValue && this.factsValue.server_instance_id !== facts.server_instance_id) throw new Error("The endpoint now belongs to a different server; reconnect to the original owner");
          await this.verifyOwner(facts); this.assertCurrent(); this.factsValue = Object.freeze(facts);
        },
      });
    try { await client.connect(); this.assertCurrent(); return client; }
    catch (error) { await client.dispose(); throw error; }
  }
  async scopes(signal?: AbortSignal): Promise<SourceScope[]> {
    const scopes: SourceScope[] = [], seen = new Set<string>(), client = await this.client();
    do {
      const result = await client.visualization<{ server_instance_id: string; source_scopes: SourceScope[]; offset: number; total: number; has_more: boolean; next_offset?: number }>(
        "/source/scopes", { offset: scopes.length, limit: 64 }, signal, { apiVersion: 2, timeoutMs: 10000, maxResponseBytes: 64 << 10 });
      this.assertCurrent();
      if (result.server_instance_id !== this.factsValue?.server_instance_id || !Array.isArray(result.source_scopes) || result.source_scopes.length > 64 || result.total > 1024 || result.offset !== scopes.length
        || result.source_scopes.some(scope => !uuid.test(scope.scope_id) || seen.has(scope.scope_id))) throw new Error("Source scopes changed or the listing is invalid; refresh this connection");
      for (const scope of result.source_scopes) { seen.add(scope.scope_id); scopes.push(scope); }
      if (!result.has_more) { if (scopes.length !== result.total) throw new Error("Source scopes changed; refresh this connection"); return scopes; }
      if (result.next_offset !== scopes.length || !result.source_scopes.length || scopes.length >= 1024) throw new Error("Invalid source scope continuation");
    } while (true);
  }
  requireReference(reference: unknown): void {
    const ref = reference as Record<string, unknown> | null;
    this.assertCurrent();
    if (!this.factsValue || ref?.schema_version !== 2 || ref.server_instance_id !== this.factsValue.server_instance_id
      || (this.scopeId !== undefined && ref.scope_id !== this.scopeId)) throw new Error("This source belongs to another server or scope");
  }
  async refreshRegistration(): Promise<void> {
    if (this.definition.kind === "local" && this.clientValue && !this.retired) await (await this.clientValue).refreshRegistration();
  }
  async dispose(): Promise<void> { this.retired = true; await this.clientValue?.then(client => client.dispose(), () => {}); }
}

export class ConnectionRegistry {
  private definitionsValue: readonly ConnectionDefinition[] = [];
  private readonly contexts = new Map<string, SourceContext>();
  private readonly pins = new Map<string, string>();
  private pinning: Promise<void> = Promise.resolve();
  constructor(private readonly host: ConnectionHost) {}
  get definitions(): readonly ConnectionDefinition[] { return this.definitionsValue; }
  configure(definitions: readonly ConnectionDefinition[]): void {
    for (const [key, context] of this.contexts) {
      if (!definitions.some(item => JSON.stringify(item) === JSON.stringify(context.definition))) {
        this.contexts.delete(key); void context.dispose();
      }
    }
    this.definitionsValue = definitions;
  }
  context(id: string, scopeId?: string): SourceContext {
    const definition = this.definitionsValue.find(item => item.id === id);
    if (!definition) throw new Error("This connection is no longer configured; choose a source explicitly");
    const key = JSON.stringify([id, scopeId]);
    let context = this.contexts.get(key);
    if (!context) {
      if (this.contexts.size >= 64) throw new Error("At most 64 source contexts can be opened in one window; close and reload the window to release unused contexts");
      context = new SourceContext(definition, scopeId, this.host, facts => {
        const task = this.pinning.catch(() => {}).then(async () => {
          const expected = definition.serverInstanceId ?? this.pins.get(id) ?? this.host.pinned(id);
          if (expected && expected !== facts.server_instance_id) throw new Error("The configured address belongs to a different server identity; restore its original owner or configure a new connection id");
          if (!expected) await this.host.pin(id, facts.server_instance_id);
          this.pins.set(id, facts.server_instance_id);
        }); this.pinning = task; return task;
      });
      this.contexts.set(key, context);
    }
    return context;
  }
  async dispose(): Promise<void> { await Promise.allSettled([...this.contexts.values()].map(context => context.dispose())); this.contexts.clear(); }
  async refreshLocalRegistrations(): Promise<void> { await Promise.all([...this.contexts.values()].map(context => context.refreshRegistration())); }
}
