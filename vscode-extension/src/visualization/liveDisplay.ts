import { ByteBudget, decodedBytes } from "./byteBudget";
import { FollowDelivery } from "./liveBroker";
import { FollowSample, Json, RecordingClock, selectionKey } from "./followProtocol";
import { RecordedConfiguration } from "./recordedModel";
import { ViewFollowBinding } from "./viewFollowProtocol";

export interface LiveRecord extends FollowSample { readonly member_id: string }
interface Point { time: number; value: number | null; sequence: number }
interface Selection { points: Point[]; records: LiveRecord[]; issues: { [key: string]: Json }[]; last?: LiveRecord; visited: number; problems: number; trimmed: number; unplottable: number }
export interface LiveFrame {
  prefix: string; finalized: boolean; complete: boolean; binding: ViewFollowBinding;
  clock: RecordingClock & { display_basis?: string }; range?: { start: number; end: number };
  series: Record<string, { timestamps: number[]; values: (number | null)[]; count: number; bucketed: false; trimmed: number; duplicate_times: number; unplottable: number; problems: number }>;
  values: Record<string, LiveRecord | null>;
  raw: Record<string, { samples: LiveRecord[]; records_visited: number; problems: number; retained: number; issues: { [key: string]: Json }[] }>;
}
/** Numeric conversion is a display projection only. Tagged table values remain exact; an integer
 * outside JavaScript's exact range is a visible plot gap, never a rounded telemetry value. */
export function plotNumber(value: Json): number | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const v = value as { type: string; value: any };
  if (v.type === "bool") return v.value ? 1 : 0;
  if (["i64", "u64", "enum", "enum_u64"].includes(v.type)) { const n = Number(v.value); return Number.isSafeInteger(n) ? n : null; }
  if (v.type === "f64" || v.type === "f32") {
    const bytes = new ArrayBuffer(8), view = new DataView(bytes);
    if (v.type === "f64") view.setBigUint64(0, BigInt("0x" + v.value)); else view.setUint32(0, parseInt(v.value, 16));
    const n = v.type === "f64" ? view.getFloat64(0) : view.getFloat32(0); return Number.isFinite(n) ? n : null;
  }
  return null;
}
/** One bounded working prefix per view. Physical order is retained for raw rows; plot sorting
 * and equal-time display collapse never become inputs to statistics or cursor calculations. */
export class LiveDisplay {
  private selections = new Map<string, Selection>();
  private uses = new Map<string, Set<string>>();
  private release?: () => void;
  private sequence = 0;
  private previousCursor?: string;
  private trustedEdge?: number;
  private frame?: LiveFrame;
  constructor(readonly binding: ViewFollowBinding, config: RecordedConfiguration, private budget: ByteBudget,
    readonly pointLimit = 4096, readonly rowLimit = 256) {
    for (const b of binding.compatibility.bindings) if (b.state === "resolved" && b.entry) {
      const key = selectionKey({ entry: b.entry, field: b.field_path });
      const uses = this.uses.get(key) ?? new Set<string>();
      config.panels.filter(p => p.series.some(s => s.requirement_id === b.requirement_id)).forEach(p => uses.add(p.type)); this.uses.set(key, uses);
    }
    this.release = budget.reserve(decodedBytes(binding));
  }
  consume(delivery: FollowDelivery): LiveFrame | undefined {
    if (delivery.page.cursor === this.previousCursor) return this.frame;
    const next = new Map([...this.selections].map(([k, s]) => [k, { ...s, points: [...s.points], records: [...s.records], issues: [...s.issues] }]));
    const evidence = delivery.frontier.clock ?? this.binding.clock;
    let edge = evidence.right_edge_sec, basis: string = evidence.basis;
    if (evidence.robot_time_sec !== null) this.trustedEdge = evidence.robot_time_sec;
    else if (!delivery.frontier.finalized && evidence.waiting_for_time_sync && this.trustedEdge !== undefined) { edge = this.trustedEdge; basis = "held_robot_edge"; }
    const time = this.binding.time_policy;
    const range = time.mode === "fixed" ? { start: time.start_sec!, end: time.end_sec! }
      : time.mode === "follow" && edge !== null ? { start: edge - time.window_sec!, end: edge } : undefined;
    const inRange = (sample: LiveRecord) => !range || BigInt(sample.timestamp_us) >= BigInt(Math.round(range.start * 1e6))
      && BigInt(sample.timestamp_us) <= BigInt(Math.round(range.end * 1e6));
    for (const batch of delivery.page.batches) {
      const key = selectionKey(batch), uses = this.uses.get(key); if (!uses?.size) continue;
      const s = next.get(key) ?? { points: [], records: [], issues: [], visited: 0, problems: 0, trimmed: 0, unplottable: 0 };
      s.visited += batch.batch.records_visited; s.problems += batch.batch.problems.length;
      s.issues.push(...batch.batch.problems.map(p => ({ ...structuredClone(p), member_id: batch.member_id })));
      if (s.issues.length > 32) s.issues.splice(0, s.issues.length - 32);
      for (const sample of batch.batch.samples) {
        const record = { ...structuredClone(sample), member_id: batch.member_id };
        if (!inRange(record)) continue;
        s.last = record;
        if (uses.has("raw")) { s.records.push(record); if (s.records.length > this.rowLimit) s.records.shift(); }
        if (uses.has("time_series")) {
          const us = Number(sample.timestamp_us), value = plotNumber(sample.value);
          if (!Number.isSafeInteger(us)) { s.unplottable++; continue; }
          if (value === null) s.unplottable++;
          const at = us / 1e6;
          if (!range || at >= range.start && at <= range.end) s.points.push({ time: at, value, sequence: ++this.sequence });
        }
      }
      if (range) s.points = s.points.filter(p => p.time >= range.start && p.time <= range.end);
      if (s.points.length > this.pointLimit) { s.trimmed += s.points.length - this.pointLimit; s.points.splice(0, s.points.length - this.pointLimit); }
      next.set(key, s);
    }
    // Quiet selections still age out as the shared recorded clock advances.
    if (range) for (const s of next.values()) {
      s.points = s.points.filter(p => p.time >= range.start && p.time <= range.end);
      s.records = s.records.filter(inRange); if (s.last && !inRange(s.last)) s.last = undefined;
    }
    const series: LiveFrame['series'] = {}, values: LiveFrame['values'] = {}, raw: LiveFrame['raw'] = {};
    for (const b of this.binding.compatibility.bindings) if (b.state === "resolved" && b.entry) {
      const s = next.get(selectionKey({ entry: b.entry, field: b.field_path })) ?? { points: [], records: [], issues: [], visited: 0, problems: 0, trimmed: 0, unplottable: 0, last: undefined };
      const sorted = [...s.points].sort((a, b) => a.time - b.time || a.sequence - b.sequence), unique = new Map<number, number | null>();
      sorted.forEach(p => unique.set(p.time, p.value));
      series[b.requirement_id] = { timestamps: [...unique.keys()], values: [...unique.values()], count: unique.size, bucketed: false,
        trimmed: s.trimmed, duplicate_times: s.points.length - unique.size, unplottable: s.unplottable, problems: s.problems };
      values[b.requirement_id] = s.last ?? null;
      raw[b.requirement_id] = { samples: s.records, records_visited: s.visited, problems: s.problems, retained: s.records.length, issues: s.issues };
    }
    const frame: LiveFrame = { prefix: delivery.page.prefix_id, finalized: delivery.frontier.finalized, complete: delivery.page.frontier_complete,
      binding: this.binding, clock: { ...evidence, right_edge_sec: edge, display_basis: basis }, range, series, values, raw };
    const release = this.budget.reserve(decodedBytes({ selections: [...next.values()], frame }));
    this.release?.(); this.release = release; this.selections = next; this.frame = frame; this.previousCursor = delivery.page.cursor;
    return frame;
  }
  dispose(): void { this.release?.(); this.release = undefined; this.selections.clear(); this.frame = undefined; }
}
