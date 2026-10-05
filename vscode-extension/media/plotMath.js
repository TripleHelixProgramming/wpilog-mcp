// The plot's arithmetic, with no DOM: how a series is fetched for a view (whole, a window, or
// bucketed), how samples are reduced to a pane's pixels, and how a series is drawn by its
// sampling class (EXPLORER_PLAN.md §5). Runs in the webview and under Node's test runner
// (plotMath.test.ts), so each rule is checked on arrays whose answers are known.
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.PlotMath = factory();
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  /** Samples a series may hold in the webview: about 64 MB of timestamps and values. */
  const SAMPLE_BUDGET = 4_000_000;
  /** The most buckets one request asks for: the server's limit. */
  const MAX_BUCKETS = 10_000;
  /** How many buckets per pixel of pane width a bucketed fetch asks for. */
  const BUCKETS_PER_PIXEL = 4;
  /** The wait after a zoom or pan before a bucketed series is fetched again. */
  const DEBOUNCE_MS = 150;
  /** Statistics are asked for this long after the view settles. */
  const STATISTICS_DEBOUNCE_MS = 400;

  /**
   * How to fetch a series for a view.
   *
   * @param series {sampleCount, logStart, logEnd}: the entry's count from the listing, the log's span
   * @param view {start, end, widthPx}: the visible window and the pane's width
   * @returns {mode: "full"} to fetch every sample once (then zoom and pan touch no server);
   *   {mode: "window", startTime, endTime} to fetch the window exactly when the whole entry is
   *   over the budget but the window fits; or {mode: "bucketed", startTime, endTime, maxPoints}
   */
  function planRequest(series, view, budget = SAMPLE_BUDGET) {
    const count = Number.isFinite(series.sampleCount) ? series.sampleCount : Infinity;
    if (count <= budget) return { mode: "full" };
    const span = series.logEnd - series.logStart;
    const width = Math.max(0, Math.min(view.end, series.logEnd) - Math.max(view.start, series.logStart));
    const estimated = span > 0 ? count * (width / span) : count;
    if (estimated <= budget) {
      return { mode: "window", startTime: view.start, endTime: view.end };
    }
    const maxPoints = Math.max(100, Math.min(MAX_BUCKETS, Math.round(view.widthPx * BUCKETS_PER_PIXEL)));
    return { mode: "bucketed", startTime: view.start, endTime: view.end, maxPoints };
  }

  /** Whether a plan fetched once serves a new view: a full fetch always, a window or buckets only inside it. */
  function planCovers(plan, view) {
    if (!plan) return false;
    if (plan.mode === "full") return true;
    if (plan.mode === "window") return view.start >= plan.startTime && view.end <= plan.endTime;
    return false;
  }

  /** The key of a plan for one series: the same plan twice is one request. */
  function requestKey(name, plan) {
    if (plan.mode === "full") return name + "|full";
    return name + "|" + plan.mode + "|" + plan.startTime + "|" + plan.endTime + "|" + (plan.maxPoints || "");
  }

  /**
   * How a series is drawn: steps for change-only sampling (a hold is a hold, never a slope
   * through it), a band for a bucketed series (minimum to maximum, with the mean as the line),
   * lines otherwise.
   */
  function drawMode(sampling, bucketed) {
    if (bucketed) return "band";
    return sampling === "change_only" ? "steps" : "lines";
  }

  /** The index of the first time >= t, by binary search on sorted times. */
  function lowerBound(times, t) {
    let lo = 0;
    let hi = times.length;
    while (lo < hi) {
      const mid = (lo + hi) >>> 1;
      if (times[mid] < t) lo = mid + 1;
      else hi = mid;
    }
    return lo;
  }

  /**
   * The slice of a series a view needs: the samples inside [t0, t1] and one on each side, so a
   * line or a hold reaches the pane's edges.
   */
  function visibleRange(times, t0, t1) {
    let lo = lowerBound(times, t0);
    let hi = lowerBound(times, t1);
    if (hi < times.length && times[hi] <= t1) hi++;
    if (lo > 0) lo--;
    if (hi < times.length) hi++;
    return [lo, hi];
  }

  /**
   * Reduces samples to a pane's pixels: the visible slice when it has no more than four samples
   * per pixel, else, per pixel column, the first, the least, the greatest, and the last sample,
   * in time order, which keeps every extreme (a spike one sample wide is drawn where it is) and
   * is a pass over a typed array. NaN samples are kept where they fall as the column's first or
   * last; they never count as an extreme.
   */
  function reduce(times, values, t0, t1, widthPx) {
    const [lo, hi] = visibleRange(times, t0, t1);
    const n = hi - lo;
    const width = Math.max(1, Math.floor(widthPx));
    if (n <= 4 * width) {
      return { times: times.subarray(lo, hi), values: values.subarray(lo, hi), reduced: false };
    }
    const span = t1 - t0;
    const outT = new Float64Array(4 * width + 8);
    const outV = new Float64Array(4 * width + 8);
    let out = 0;
    let column = -1;
    let first = -1;
    let last = -1;
    let minI = -1;
    let maxI = -1;
    const flush = () => {
      if (first < 0) return;
      const picks = [first, minI, maxI, last].filter((i) => i >= 0).sort((a, b) => a - b);
      let previous = -1;
      for (const i of picks) {
        if (i === previous) continue;
        previous = i;
        outT[out] = times[i];
        outV[out] = values[i];
        out++;
      }
    };
    for (let i = lo; i < hi; i++) {
      const c = span > 0 ? Math.floor(((times[i] - t0) / span) * width) : 0;
      if (c !== column) {
        flush();
        column = c;
        first = i;
        minI = -1;
        maxI = -1;
      }
      last = i;
      const v = values[i];
      if (v === v) { // not NaN
        if (minI < 0 || v < values[minI]) minI = i;
        if (maxI < 0 || v > values[maxI]) maxI = i;
      }
    }
    flush();
    return { times: outT.subarray(0, out), values: outV.subarray(0, out), reduced: true };
  }

  /** A view zoomed by a factor about a time (factor < 1 zooms in), kept inside the log. */
  function zoom(view, factor, about, logStart, logEnd) {
    const span = Math.max(1e-6, (view.end - view.start) * factor);
    const fraction = view.end > view.start ? (about - view.start) / (view.end - view.start) : 0.5;
    let start = about - span * fraction;
    let end = start + span;
    if (span >= logEnd - logStart) return { start: logStart, end: logEnd };
    if (start < logStart) { start = logStart; end = start + span; }
    if (end > logEnd) { end = logEnd; start = end - span; }
    return { start, end };
  }

  /** A view moved by a fraction of its span (negative is earlier), kept inside the log. */
  function pan(view, fraction, logStart, logEnd) {
    const span = view.end - view.start;
    let start = view.start + span * fraction;
    if (start < logStart) start = logStart;
    if (start + span > logEnd) start = Math.max(logStart, logEnd - span);
    return { start, end: start + span };
  }

  /** The index of the sample at or before t (the value a cursor reads), or -1 before the first. */
  function indexAtOrBefore(times, t) {
    const i = lowerBound(times, t);
    if (i < times.length && times[i] === t) return i;
    return i - 1;
  }

  return {
    SAMPLE_BUDGET, MAX_BUCKETS, BUCKETS_PER_PIXEL, DEBOUNCE_MS, STATISTICS_DEBOUNCE_MS,
    planRequest, planCovers, requestKey, drawMode, lowerBound, visibleRange, reduce, zoom, pan,
    indexAtOrBefore,
  };
});
