/** One window owns acquisition and retained-display allowances, shared by its consumers.
 * Reservations are synchronous so two asynchronous callers cannot both admit the last slot. */
export class ByteBudget {
  private used = 0;
  constructor(readonly maximum = 64 << 20) {
    if (!Number.isSafeInteger(maximum) || maximum <= 0) throw new Error("Invalid byte budget");
  }
  get reserved(): number { return this.used; }
  get available(): number { return this.maximum - this.used; }
  reserve(bytes: number): () => void {
    if (!Number.isSafeInteger(bytes) || bytes < 0 || bytes > this.maximum - this.used)
      throw new Error("Window byte budget is full; close another overview or visualization and Retry");
    this.used += bytes; let released = false;
    return () => { if (!released) { released = true; this.used -= bytes; } };
  }
}
/** Conservative retained JSON accounting, including strings, keys, containers and references.
 * Two copies are reserved for the extension host and webview; wire bytes are charged separately. */
export function decodedBytes(value: unknown, depth = 0): number {
  if (depth > 64) throw new Error("Inventory structure exceeds the nesting limit");
  if (typeof value === "string") return 32 + value.length * 2;
  if (value === null || typeof value !== "object") return 16;
  if (Array.isArray(value)) return 64 + value.reduce((n, v) => n + 8 + decodedBytes(v, depth + 1), 0);
  return 64 + Object.entries(value).reduce((n, [k, v]) => n + 32 + decodedBytes(k, depth + 1) + decodedBytes(v, depth + 1), 0);
}
export class VisualizationBudgets {
  readonly encoded = new ByteBudget();
  readonly decoded: ByteBudget;
  constructor(mib: unknown = 64) {
    if (typeof mib !== "number" || !Number.isInteger(mib) || mib < 16 || mib > 1024) throw new Error("Decoded buffer budget must be an integer from 16 to 1024 MiB");
    this.decoded = new ByteBudget(mib * 1048576);
  }
}
