(function (root) {
  "use strict";
  class ChartPane {
    constructor(host, section) { this.host = host; this.section = section; this.plot = null; }
    show(selection) {
      this.selection = selection; this.section.classList.remove("hidden"); this.section.open = true;
      this.section.dataset.paneKind = selection.kind;
      this.section.querySelector(".chart-status").textContent = "Loading chart specification…";
      this.host.post({ type: "chart", selection });
    }
    receive(result) {
      const note = this.section.querySelector(".chart-status");
      if (!result.chart_spec) { note.textContent = result.error || result.reason || "No chart"; return; }
      const spec = result.chart_spec, sources = ChartSpec.series(spec), element = this.section.querySelector(".chart-view");
      if (this.plot) this.plot.destroy(); element.replaceChildren();
      const colors = ["#3d9be9", "#df665e", "#6cba65", "#b18ae0"];
      this.plot = new uPlot({ width: Math.max(200, element.clientWidth), height: 320, mode: 2,
        scales: { x: { time: false } },
        series: [{}, ...sources.map((s, i) => ({ label: s.name + (s.unit ? ` (${s.unit})` : ""),
          stroke: colors[i % colors.length], fill: colors[i % colors.length],
          paths: spec.kind === "histogram" ? uPlot.paths.bars({ size: [0.9, Infinity] }) : () => null,
          points: { show: spec.kind === "scatter", size: 5 } }))],
        axes: [{ label: spec.kind === "histogram" ? "Value (name-stated units in legend)" : spec.pairs[0].x },
          { label: spec.kind === "histogram" ? "Sample count" : spec.pairs[0].y }],
      }, [null, ...sources.map(s => [s.x, s.y])], element);
      note.textContent = spec.histogram_rule || spec.alignment_rule;
    }
  }
  root.ChartPane = ChartPane;
})(typeof self !== "undefined" ? self : this);
