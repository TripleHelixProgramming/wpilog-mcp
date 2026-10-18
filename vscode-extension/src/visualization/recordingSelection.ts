import { VisualizationBudgets, decodedBytes } from "./byteBudget";
import { canonical } from "./followProtocol";
import { OverviewState } from "./inventoryOverview";
import { RecordedClient } from "./recordedClient";
import { RecordedConfiguration, Requirement, Signal, SourceRef } from "./recordedModel";

/** The picker supplies literal intent; only the server may decide whether a field is numeric
 * and whether all present members agree. Detail freshness is independent of view revision. */
export function recordingSelection(state: OverviewState | undefined, generation: number, field: unknown): Signal {
  const row = state?.details?.selected_rows[0];
  if (!state || state.loading || state.expired || state.hidden || state.generation !== generation
    || !row?.name || row.name !== state.selected || row.conflict || row.unavailable || !row.type)
    throw new Error("Inspect a current, unambiguous recording signal before adding it");
  if (field !== undefined && (typeof field !== "string" || !field.trim() || [...field].length > 512))
    throw new Error("Enter a numeric field or array index using 1–512 characters");
  return { name: row.name, type: row.type, sample_count: 0, ...(field === undefined ? {} : { field: field as string }) };
}

/** One explicit gesture, no automatic retry or retained permission. Reserve before I/O and
 * discard the evidence after admission; the subsequent bound follow rechecks the source. */
export async function checkRecordingField(backend: RecordedClient, budgets: VisualizationBudgets,
  source: SourceRef, config: RecordedConfiguration, requirement: Requirement, abort: AbortSignal): Promise<string> {
  const limit = Math.min(1 << 20, Math.floor(budgets.decoded.maximum / 64));
  const encoded = budgets.encoded.reserve(2 * limit);
  let decoded: (() => void) | undefined;
  try {
    decoded = budgets.decoded.reserve(16 * limit);
    const configuration = { ...config, requirements: [requirement], panels: [], layout: { columns: 12, items: [] } };
    const result: any = await backend.call("/templates/check", { source_ref: source, configuration }, abort,
      { maxRequestBytes: 64 << 10, maxResponseBytes: limit, timeoutMs: 15000 });
    if (abort.aborted) throw new Error("Field selection cancelled; check the current selection again");
    if (decodedBytes(result) > 16 * limit) throw new Error("Field evidence exceeds this window's allowance; close another view or increase the allowance and reload");
    const binding = result?.bindings?.[0];
    if (canonical(result?.source_ref) !== canonical(source) || result?.binding_basis !== "fresh_recording_prefix"
      || !Array.isArray(result.bindings) || result.bindings.length !== 1 || binding?.requirement_id !== requirement.id
      || binding?.field_path !== requirement.field_path || !["resolved", "unresolved"].includes(binding?.state))
      throw new Error("Invalid recording field response; update the server and retry");
    if (binding.state !== "resolved") {
      const problems = Array.isArray(binding.problems) ? binding.problems.slice(0, 8).map((p: any) =>
        `${String(p.member_id ?? "Recording").slice(0, 128)}: ${String(p.reason ?? p.code).slice(0, 512)}`).join("; ") : "";
      throw new Error(`Field was not added: ${problems || String(binding.reason ?? "No compatible numeric field").slice(0, 512)}`);
    }
    if (result.compatibility !== "compatible" || binding.entry !== requirement.entry
      || !requirement.accepted_entry_types.includes(binding.declared_type))
      throw new Error("Invalid recording field response; the server changed the selected entry or declared type");
    return "Field checked across the recording and added. Later reads recheck the source.";
  } finally { decoded?.(); encoded(); }
}
