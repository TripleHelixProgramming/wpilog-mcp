import { sourceContent } from "./recordedModel";
import { canonical, object } from "./followProtocol";

/** Wire shape and identity admission only. Binning, pairing, units and statistics stay on the
 * server; a malformed or foreign page is never partially rendered as plausible measurements. */
export function chartResponse(result: any, body: any, scatter: boolean): void {
  const inputs = object(result.inputs), prefix = object(result.prefix);
  const refused = () => { throw new Error("Invalid chart response identity, prefix or measurements"); };
  if (canonical(inputs.source_ref) !== canonical(body.source_ref)
    || prefix.source_kind !== (sourceContent(body.source_ref).kind === "session" ? "session" : "file")
    || typeof prefix.finalized !== "boolean" || !Array.isArray(prefix.members)
    || sourceContent(body.source_ref).kind === "session" && typeof prefix.catalog_revision !== "string"
    || result.basis !== "admitted_source_prefix") refused();
  for (const key of scatter ? ["x_entry", "x_field", "y_entry", "y_field"] : ["entry", "field"])
    if ((inputs[key] ?? null) !== (body[key] ?? null)) refused();
  if (!["ok", "partial", "no_match"].includes(result.status) || result.status === "no_match" && typeof result.hint !== "string") refused();
  const count = (v: unknown) => Number.isSafeInteger(v) && (v as number) >= 0;
  if (scatter) {
    const points = result.points, limits = object(object(result.limits).points);
    if (!Array.isArray(points) || points.length > body.limit || !points.every(p => Array.isArray(p) && p.length === 3 && p.every(Number.isFinite))
      || ![result.matched, result.unpaired_x, result.unpaired_y, result.next_offset].every(count)
      || result.offset !== body.offset || limits.total !== result.matched || limits.returned !== points.length || limits.limit !== body.limit
      || result.next_offset !== Math.min(body.offset + points.length, result.matched)
      || result.complete !== (body.offset + points.length >= result.matched) || typeof result.alignment_rule !== "string") refused();
  } else {
    if (!Array.isArray(result.bins) || result.bins.length > 64 || typeof result.histogram_rule !== "string" || !count(result.summary?.count)
      || !result.bins.every((b: any) => Number.isFinite(b.low) && Number.isFinite(b.high) && b.low < b.high && count(b.count))) refused();
  }
}
