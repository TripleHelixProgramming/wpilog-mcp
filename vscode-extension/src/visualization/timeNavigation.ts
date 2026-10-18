/** Robot-clock intervals are persisted at microsecond precision. Navigation changes a query,
 * never merely clips a retained plot: older samples may no longer be in the display buffer. */
export function historyRange(start: unknown, end: unknown, action?: unknown): { mode: "fixed"; start_sec: number; end_sec: number } {
  const valid = (s: unknown, e: unknown): s is number => typeof s === "number" && typeof e === "number"
    && Number.isFinite(s) && Number.isFinite(e) && Math.abs(s) <= 9_007_199_254 && Math.abs(e) <= 9_007_199_254 && s <= e;
  if (!valid(start, end)) throw new Error("Use finite, ordered robot-clock bounds in seconds");
  let first = start, last = end as number;
  if (action !== undefined) {
    const width = last - first;
    if (width < 0.000001) throw new Error("Choose a wider history interval before panning or zooming");
    switch (action) {
      case "earlier": first -= width / 2; last -= width / 2; break;
      case "later": first += width / 2; last += width / 2; break;
      case "zoom_in": first += width / 4; last -= width / 4; break;
      case "zoom_out": first -= width / 2; last += width / 2; break;
      default: throw new Error("Unknown history navigation action");
    }
  }
  first = Math.round(first * 1e6) / 1e6; last = Math.round(last * 1e6) / 1e6;
  if (!valid(first, last) || action !== undefined && last <= first) throw new Error("Navigation exceeds the robot-clock interval or precision");
  return { mode: "fixed", start_sec: first, end_sec: last };
}

export function followingWindow(value: unknown): number {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 1 || value > 300)
    throw new Error("Use a 1–300 second following window");
  return value;
}
