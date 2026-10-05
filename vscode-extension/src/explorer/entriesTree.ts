/**
 * The Entries view's model, built from `list_entries` and `get_entry_info` results
 * (EXPLORER_PLAN.md §4): the active log's entries as a tree by the segments of their names
 * (`/Drive/Odometry/Pose` under `Drive`, then `Odometry`), each leaf with its type and sample
 * count, and a struct or array entry expandable into the numeric field paths `get_entry_info`
 * reports, which the numeric tools take appended to the entry's name. Pure functions, tested
 * from recorded results.
 */

/** An entry as `list_entries` lists it. */
export interface ListedEntry {
  name: string;
  type: string;
  sample_count: number;
}

/** What `list_entries` returned, as the model reads it. */
export interface EntryListing {
  status?: string;
  log_path?: string;
  entry_count?: number;
  time_range_sec?: { start?: number; end?: number; duration?: number };
  truncated?: boolean;
  warning?: string;
  entries?: ListedEntry[];
  error?: string;
  hint?: string;
  reason?: string;
}

/** A node of the tree. */
export type EntryNode =
  /** A name segment with entries beneath it. */
  | { kind: "group"; label: string; path: string; children: EntryNode[] }
  /** An entry: its last segment, its type and count, and whether it has field paths to show. */
  | { kind: "entry"; label: string; description: string; tooltip: string; entry: ListedEntry; expandable: boolean }
  /** A numeric field path inside an entry, as the numeric tools address it. */
  | { kind: "field"; label: string; description: string; fieldPath: string; entry: ListedEntry }
  /** A line that is not an entry: nothing matched, or the listing failed. */
  | { kind: "note"; label: string; tooltip?: string };

/**
 * Whether an entry may hold numeric fields the tools address by path: a struct, a struct array,
 * or a numeric array. A scalar number, a boolean, a string, or raw bytes has none.
 */
export function hasFieldPaths(type: string): boolean {
  return type.startsWith("struct:") || type.endsWith("[]");
}

/** A count with thousands separators, as the tree shows sample counts. */
export function formatCount(count: number): string {
  return Number.isFinite(count) ? count.toLocaleString("en-US") : "";
}

function entryNode(entry: ListedEntry, label: string): EntryNode {
  return {
    kind: "entry",
    label,
    description: `${entry.type} · ${formatCount(entry.sample_count)}`,
    tooltip: `${entry.name}\n${entry.type}, ${formatCount(entry.sample_count)} samples`,
    entry,
    expandable: hasFieldPaths(entry.type),
  };
}

/**
 * The tree for a listing: groups by name segment, with the groups before the entries at each
 * level and both in name order, as `list_entries` orders them. An entry named like a group
 * (`/Drive` beside `/Drive/Pose`) is an entry under the group's parent, not the group itself.
 * A listing that failed, or matched nothing, is one note with the server's words.
 */
export function buildEntryTree(listing: EntryListing): EntryNode[] {
  if (listing.status === "error") {
    return [{ kind: "note", label: listing.error ?? "The entry listing failed", tooltip: listing.hint }];
  }
  const entries = listing.entries ?? [];
  if (entries.length === 0) {
    return [{ kind: "note", label: listing.reason ?? "No entries", tooltip: listing.hint }];
  }
  interface Group {
    children: Map<string, Group>;
    entries: EntryNode[];
  }
  const root: Group = { children: new Map(), entries: [] };
  for (const entry of entries) {
    const segments = entry.name.split("/").filter((s) => s !== "");
    const leaf = segments.pop() ?? entry.name;
    let group = root;
    for (const segment of segments) {
      let next = group.children.get(segment);
      if (!next) {
        next = { children: new Map(), entries: [] };
        group.children.set(segment, next);
      }
      group = next;
    }
    group.entries.push(entryNode(entry, leaf));
  }
  const toNodes = (group: Group, path: string): EntryNode[] => {
    const groups: EntryNode[] = [...group.children.entries()]
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([segment, child]) => ({
        kind: "group" as const,
        label: segment,
        path: `${path}/${segment}`,
        children: toNodes(child, `${path}/${segment}`),
      }));
    const own = [...group.entries].sort((a, b) => a.label.localeCompare(b.label));
    return [...groups, ...own];
  };
  return toNodes(root, "");
}

/**
 * The field nodes of an entry, from the `numeric_leaf_paths` of its `get_entry_info` result:
 * each path as the numeric tools take it (`.translation.x`, `[*].tagCount`), with the field's
 * type where the struct's fields say it.
 */
export function buildFieldNodes(entry: ListedEntry, info: Record<string, unknown>): EntryNode[] {
  const paths = Array.isArray(info.numeric_leaf_paths)
    ? info.numeric_leaf_paths.filter((p): p is string => typeof p === "string")
    : [];
  const struct = info.struct as { fields?: { name?: string; type?: string }[] } | undefined;
  const types = new Map<string, string>();
  for (const field of struct?.fields ?? []) {
    if (field.name && field.type) types.set(field.name, field.type);
  }
  if (paths.length === 0) {
    return [{ kind: "note", label: "No numeric fields" }];
  }
  return paths.map((fieldPath) => {
    const first = /^(?:\[\*\])?\.?([^.[]+)/.exec(fieldPath)?.[1];
    const type = first ? types.get(first) : undefined;
    return {
      kind: "field",
      label: fieldPath,
      description: type ?? "",
      fieldPath,
      entry,
    };
  });
}

/** Seconds as the editor shows a time range: `12.658 s`. */
export function formatSeconds(seconds: number | undefined): string {
  return typeof seconds === "number" && Number.isFinite(seconds) ? `${seconds.toFixed(3)} s` : "";
}
