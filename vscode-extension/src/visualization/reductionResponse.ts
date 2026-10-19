import { sourceContent } from "./recordedModel";
import { canonical, object } from "./followProtocol";

/** Validate measurements and their identity before the renderer allocates any drawing arrays.
 * Bucketing stays in the native reader; the client only admits and displays its result. */
export function reductionResponse(result: any, body: any): void {
  const inputs = object(result.inputs), prefix = object(result.prefix), window = object(result.window);
  const refused = (): never => { throw new Error("Invalid reduction response identity, bounds or measurements"); };
  const count = (v: any): boolean => Number.isSafeInteger(v) && v >= 0;
  const time = (v: any): bigint => {
    if (typeof v !== "string" || !/^-?(0|[1-9][0-9]*)$/.test(v)) return refused();
    const t = BigInt(v); if (t < -9007199254740991n || t > 9007199254740991n) return refused(); return t;
  };
  if (canonical(inputs.source_ref) !== canonical(body.source_ref) || inputs.entry !== body.entry
    || (inputs.field ?? null) !== (body.field ?? null) || inputs.max_points !== body.max_points || inputs.max_bytes !== body.max_bytes
    || ["start_time", "end_time"].some(k => (inputs[k] ?? null) !== (body[k] ?? null))
    || prefix.source_kind !== (sourceContent(body.source_ref).kind === "session" ? "session" : "file") || typeof prefix.finalized !== "boolean" || !Array.isArray(prefix.members)
    || sourceContent(body.source_ref).kind === "session" && !/^\d+$/.test(prefix.catalog_revision)
    || result.basis !== "admitted_source_prefix" || result.reduction_rule !== "time_buckets_min_max_mean"
    || !["ok", "partial", "no_match"].includes(result.status) || result.status === "no_match" && typeof result.hint !== "string"
    || result.max_points !== body.max_points || !Array.isArray(result.buckets) || result.buckets.length > body.max_points
    || ![result.sample_count, result.finite_count, result.unplottable_count, result.records_visited, result.problem_count].every(count)
    || !Array.isArray(result.problems) || result.problems.length > 100 || result.problem_count < result.problems.length
    || result.problems_truncated !== (result.problem_count > result.problems.length)) refused();
  const start = time(window.start_us), end = time(window.end_us); if (start > end) refused();
  if (body.start_time !== undefined && start !== BigInt(Math.round(body.start_time * 1e6))
    || body.end_time !== undefined && end !== BigInt(Math.round(body.end_time * 1e6))) refused();
  let samples = 0, finite = 0, previous: bigint | undefined;
  for (const b of result.buckets) {
    const from = time(b.start_us), to = time(b.end_us);
    if (from < start || to > end || from > to || previous !== undefined && from < previous
      || !count(b.count) || b.count === 0 || !count(b.finite_count) || b.finite_count > b.count
      || ![b.min, b.max, b.mean, b.first, b.last].every(v => v === null || typeof v === "number" && Number.isFinite(v))
      || b.finite_count === 0 && [b.min, b.max, b.mean, b.first, b.last].some(v => v !== null)
      || b.finite_count > 0 && (b.min === null || b.max === null || b.min > b.max)) refused();
    previous = to; samples += b.count; finite += b.finite_count;
  }
  if (samples !== result.sample_count || finite !== result.finite_count || samples - finite !== result.unplottable_count
    || result.records_visited < samples || (result.status === "no_match") !== (samples === 0)) refused();
}
