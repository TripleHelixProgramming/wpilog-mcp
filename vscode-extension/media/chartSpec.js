// The v1 chart spec is the measurement recipe; uPlot only maps its coordinates to pixels.
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.ChartSpec = factory();
})(typeof self !== "undefined" ? self : this, function () {
  function series(spec) {
    if (spec.version !== 1) throw new Error("Unsupported chart specification version");
    if (spec.kind === "histogram") return spec.series.map(s => ({ name: s.name, unit: s.unit,
      x: s.bins.map(b => (b.low + b.high) / 2), y: s.bins.map(b => b.count) }));
    if (spec.kind === "scatter") return spec.pairs.map(p => ({ name: p.y + " versus " + p.x,
      x: p.points.map(row => row[0]), y: p.points.map(row => row[1]) }));
    throw new Error("This pane takes histogram or scatter specifications");
  }
  return { series };
});
