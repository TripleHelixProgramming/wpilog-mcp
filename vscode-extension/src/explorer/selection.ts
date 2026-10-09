/** Portable selection shared by links, notebooks and assistant prompts. No editor API or execution. */
export interface Selection {
  path: string;
  entries: string[];
  start?: number;
  end?: number;
  kind: "time_series" | "histogram" | "scatter" | "field" | "data" | "console";
  inputs?: unknown;
  selected?: boolean;
}
const kinds = new Set(["time_series", "histogram", "scatter", "field", "data", "console"]);
export function parseExplorerUri(value: string): Selection {
  const url = new URL(value);
  if (url.protocol !== "vscode:" || url.host.toLowerCase() !== "triplehelixprogramming.wpilog-analyzer" || url.pathname !== "/open") throw new Error("Not a WPILog Explorer link");
  const path = url.searchParams.get("path"); if (!path) throw new Error("The explorer link needs a path");
  const number = (key: string): number | undefined => {
    const text = url.searchParams.get(key); if (text === null) return undefined;
    if (!text.trim() || !Number.isFinite(Number(text))) throw new Error(`Invalid ${key} in the explorer link: expected a finite number`);
    return Number(text);
  };
  const start = number("start"), end = number("end");
  if (start !== undefined && end !== undefined && start > end) throw new Error("The explorer link's start is after its end");
  const kind = url.searchParams.get("kind") ?? "time_series";
  if (!kinds.has(kind)) throw new Error(`Unknown explorer pane kind: ${kind}`);
  return { path, entries: url.searchParams.getAll("entries"), start, end, kind: kind as Selection["kind"] };
}
/** A link never grants filesystem access. The server's refusal is shown verbatim. */
export async function openValidated(selection: Selection, validate: (path: string) => Promise<unknown>, open: (selection: Selection) => Promise<void>): Promise<void> {
  await validate(selection.path);
  await open(selection);
}
export function selectionPrompt(selection: Selection): string {
  const tool = selection.kind === "console" ? "search_strings" : selection.selected ? "get_statistics" : "render_chart";
  const window = selection.start === undefined && selection.end === undefined ? "the whole recorded log" : `${selection.start ?? "start"} to ${selection.end ?? "end"} seconds on the robot clock`;
  return `Analyze this ${selection.kind} ${selection.selected ? "selection" : "pane"} from ${JSON.stringify(selection.path)}.\nEntries: ${selection.entries.map(name => JSON.stringify(name)).join(", ") || "use the logged console convention"}.\nWindow: ${window}.\nFirst call ${tool} with that path, entries and window. Use the recorded evidence and name anything the log cannot establish.`;
}
