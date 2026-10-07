// Incremental views replace the inclusive boundary, preserving equal-timestamp records.
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.Follow = factory();
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";
  function append(previous, next, from, windowStart, budget) {
    let end = previous ? previous.timestamps.length : 0;
    while (end > 0 && previous.timestamps[end - 1] >= from) end--;
    let start = 0;
    while (start + 1 < end && previous.timestamps[start + 1] < windowStart) start++;
    start = Math.max(start, end + next.timestamps.length - budget);
    const prefix = Math.max(0, end - start), skip = Math.max(0, next.timestamps.length - budget);
    const count = prefix + next.timestamps.length - skip;
    const timestamps = new Float64Array(count), columns = {};
    if (prefix) timestamps.set(previous.timestamps.subarray(start, end));
    timestamps.set(next.timestamps.subarray(skip), prefix);
    for (const name of Object.keys(next.columns)) {
      columns[name] = new Float64Array(count);
      if (prefix) columns[name].set(previous.columns[name].subarray(start, end));
      columns[name].set(next.columns[name].subarray(skip), prefix);
    }
    return { timestamps, columns, count };
  }
  function windowAt(view, start, end) { return { start: Math.max(start, end - Math.max(0, view.end - view.start)), end }; }
  function consoleMatches(previous, next, from, windowStart, limit) {
    return [...previous.filter(m => m.timestamp_sec < from && (m.last_timestamp_sec ?? m.timestamp_sec) >= windowStart), ...next].slice(-limit);
  }
  return { append, windowAt, consoleMatches };
});
