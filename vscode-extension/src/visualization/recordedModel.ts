import { Configuration, ViewSnapshot } from "./documentController";
export interface SourceRef { kind: string; [key: string]: unknown; }
export interface RecordedView extends ViewSnapshot {
  source_ref: SourceRef;
  initial_configuration: Configuration;
  attached_client_id: string | null;
  detached_expires_at: string | null;
  has_unsaved_changes: boolean;
}
export interface Requirement {
  id: string; selector_kind: "entry" | "role"; entry?: string; role?: string; field_path: string | null;
  accepted_entry_types: string[]; value_type: "number" | "boolean" | "string" | "raw"; unit: null | { symbol: string; basis: string };
}
export interface Panel {
  panel_id: string; type: "time_series" | "latest" | "statistics" | "raw"; title: string;
  series: { requirement_id: string; axis_id: string; color: string }[];
  axes: { axis_id: string; label: string; scale: "linear" | "log"; range: "auto" | { min: number; max: number } }[];
  display: { legend: boolean; line_width: number };
}
export interface LayoutItem { panel_id: string; x: number; y: number; w: number; h: number; }
export interface RecordedConfiguration extends Configuration {
  requirements: Requirement[]; panels: Panel[]; layout: { columns: 12; items: LayoutItem[] };
  links: { cursor: boolean; time_range: boolean };
  time: { recorded: { mode: "full" } | { mode: "fixed"; start_sec: number; end_sec: number }; live: unknown };
}
export interface Signal { name: string; type: string; sample_count: number; field?: string; }
export interface Inventory { path: string; label: string; entries: Signal[]; clock_basis: string; truncated: boolean; damaged: boolean; }
export type LayoutAction = "earlier" | "later" | "left" | "right" | "wider" | "narrower" | "taller" | "shorter" | "remove";

/** Presentation changes are pure; the server alone admits their schema and revisions. */
export function layoutEdit(input: RecordedConfiguration, panelId: string, action: LayoutAction): RecordedConfiguration {
  const config = structuredClone(input), items = config.layout.items;
  const item = items.find(i => i.panel_id === panelId); if (!item) throw new Error("Panel no longer exists");
  if (action === "remove") {
    config.layout.items = items.filter(i => i !== item); config.panels = config.panels.filter(p => p.panel_id !== panelId);
    const used = new Set(config.panels.flatMap(p => p.series.map(s => s.requirement_id)));
    config.requirements = config.requirements.filter(r => used.has(r.id)); return config;
  }
  const order = [...items].sort((a, b) => a.y - b.y || a.x - b.x);
  if (action === "earlier" || action === "later") {
    const i = order.indexOf(item), j = Math.max(0, Math.min(order.length - 1, i + (action === "earlier" ? -1 : 1)));
    [order[i], order[j]] = [order[j], order[i]];
    let y = 0; for (const at of order) { at.x = 0; at.y = y; y += at.h; }
  } else if (action === "left") item.x = Math.max(0, item.x - 1);
  else if (action === "right") item.x = Math.min(12 - item.w, item.x + 1);
  else if (action === "wider") item.w = Math.min(12 - item.x, item.w + 1);
  else if (action === "narrower") item.w = Math.max(1, item.w - 1);
  else if (action === "taller") item.h = Math.min(100, item.h + 1);
  else if (action === "shorter") item.h = Math.max(3, item.h - 1);
  else throw new Error("Unknown layout action");
  return config;
}
export function addPanel(input: RecordedConfiguration, id: string, type: Panel["type"]): RecordedConfiguration {
  const config = structuredClone(input);
  if (config.panels.length >= 32 || config.panels.some(p => p.panel_id === id)) throw new Error("Panel limit or duplicate identity");
  config.panels.push({ panel_id: id, type, title: type === "latest" ? "Recorded values" : type === "statistics" ? "Statistics" : type === "raw" ? "Raw records" : "Time series", series: [],
    axes: [{ axis_id: "y", label: "Logged values", scale: "linear", range: "auto" }], display: { legend: true, line_width: 1.5 } });
  const y = Math.max(0, ...config.layout.items.map(p => p.y + p.h));
  config.layout.items.push({ panel_id: id, x: 0, y, w: 12, h: 5 }); return config;
}
export function addSignals(input: RecordedConfiguration, panelId: string, signals: Signal[], id: () => string): RecordedConfiguration {
  const config = structuredClone(input), panel = config.panels.find(p => p.panel_id === panelId);
  if (!panel) throw new Error("Choose a panel before adding signals");
  for (const signal of signals) {
    if (panel.type !== "raw" && !signal.field && !["double", "float", "int64", "boolean", "string"].includes(signal.type)) throw new Error("Select a scalar field in Entries first");
    if (["time_series", "statistics"].includes(panel.type) && !signal.field && signal.type === "string") throw new Error("Text signals belong in a value table");
    let requirement = config.requirements.find(r => r.entry === signal.name && r.field_path === (signal.field ?? null) && r.accepted_entry_types.includes(signal.type));
    if (!requirement) {
      requirement = { id: id(), selector_kind: "entry", entry: signal.name, field_path: signal.field ?? null,
        accepted_entry_types: [signal.type], value_type: panel.type === "raw" ? "raw" : signal.field ? "number" : signal.type === "string" ? "string" : signal.type === "boolean" ? "boolean" : "number", unit: null };
      config.requirements.push(requirement);
    }
    if (!panel.series.some(s => s.requirement_id === requirement!.id)) {
      if (config.panels.reduce((n, p) => n + p.series.length, 0) >= 32) throw new Error("At most 32 series per recorded view");
      panel.series.push({ requirement_id: requirement.id, axis_id: "y", color: ["#66bbee", "#ee8866", "#88cc88", "#cc88dd"][panel.series.length % 4] });
    }
  }
  return config;
}

/** Query identity excludes presentation and revision. Shared requirements are fetched once per use
 * (plot or cursor table), regardless of which panel displays them or where that panel is placed. */
export function recordedDataKey(source: SourceRef, config: RecordedConfiguration): string {
  const requirements = config.requirements.map(r => ({
    id: r.id, selector_kind: r.selector_kind, entry: r.entry, role: r.role, unit: r.unit, field_path: r.field_path,
    accepted_entry_types: [...r.accepted_entry_types].sort(), value_type: r.value_type,
    uses: [...new Set(config.panels.filter(p => p.series.some(s => s.requirement_id === r.id)).map(p => p.type))].sort(),
  })).sort((a, b) => a.id.localeCompare(b.id));
  return JSON.stringify({ source, requirements, time: config.time.recorded });
}

/** A reply may render only into the document/query generation that admitted it. */
export class ViewGeneration {
  private serial = 0;
  private key?: string;
  private aborter?: AbortController;
  begin(view: string, revision: number): { key: string; signal: AbortSignal } {
    this.aborter?.abort(); this.aborter = new AbortController();
    this.key = `${view}:${revision}:${++this.serial}`;
    return { key: this.key, signal: this.aborter.signal };
  }
  current(key: string): boolean { return !this.aborter?.signal.aborted && key === this.key; }
  cancel(): void { this.aborter?.abort(); this.serial++; }
}


/** The recorded preview can render this bounded subset of the durable schema. */
export function requireRecordedPreview(config: RecordedConfiguration): void {
  if (config.panels.some(p => !["time_series", "latest", "statistics", "raw"].includes(p.type))) throw new Error("This panel needs a later visualization stage");
  if (config.panels.reduce((n, p) => n + p.series.length, 0) > 32) throw new Error("At most 32 series per recorded view");
}

export interface Binding { requirement_id: string; state: "resolved" | "unresolved"; entry: string | null; field_path: string | null; reason: string; basis?: string; unit?: { symbol: string | null; basis: string }; warnings: string[]; }
export interface Compatibility { compatibility: "compatible" | "partial" | "incompatible"; bindings: Binding[]; }
export interface TemplateRef { origin: "bundled" | "store"; store_id?: string; template_id: string; revision: number; }
export interface TemplateItem { template_ref: TemplateRef; template: { name: string; description: string; configuration: RecordedConfiguration }; store_label?: string; }
