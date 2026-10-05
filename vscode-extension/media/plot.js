// The explorer's plot (EXPLORER_PLAN.md §4, §5): a timeline across the top with the match
// phases shaded and the Driver Station's events marked, plot panes beneath it drawn with uPlot
// from the data endpoint's Arrow streams, a shared cursor with a readout, zoom and pan, and a
// statistics panel from get_statistics. Every number comes from the server: the samples from
// the data endpoint, the phases and events and statistics from the tools; this file draws.
//
// The host (explorer.ts) is the only client of the server. The plot asks it for data, the
// timeline, and statistics by postMessage, and draws what comes back. Requires uPlot
// (vendor/), ArrowStream, and PlotMath, loaded before it.
(function (root) {
  "use strict";

  const PALETTE = ["#4e79a7", "#f28e2b", "#59a14f", "#e15759", "#b07aa1", "#76b7b2", "#edc948", "#ff9da7", "#9c755f", "#bab0ac"];
  const PANE_HEIGHT = 240;
  const AXIS_WIDTH = 64;

  const css = (name, fallback) => getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback;

  /** A time for the readout and the axes: seconds with three decimals. */
  function seconds(t) {
    return Number.isFinite(t) ? t.toFixed(3) : "";
  }

  function fixed(v, digits = 4) {
    if (v === null || v === undefined || !Number.isFinite(v)) return "–";
    const a = Math.abs(v);
    if (a >= 1e6 || (a < 1e-4 && a > 0)) return v.toExponential(3);
    return v.toFixed(digits);
  }

  function el(tag, className, text) {
    const e = document.createElement(tag);
    if (className) e.className = className;
    if (text !== undefined) e.textContent = text;
    return e;
  }

  // ---- a series: one entry (or field path) in one pane ----

  class Series {
    constructor(name, color, sampleCount) {
      this.name = name;
      this.color = color;
      this.sampleCount = sampleCount;
      this.plan = null; // the plan the data came from
      this.data = null; // {timestamps, columns} from ArrowStream.entrySeries
      this.bucketed = false;
      this.sampling = undefined;
      this.unit = undefined;
      this.pending = null; // the request key in flight
      this.error = null;
    }

    /** Whether the data in hand serves the view, or a new request is needed. */
    covers(view) {
      return this.data !== null && PlotMath.planCovers(this.plan, view);
    }
  }

  // ---- the plot ----

  class Plot {
    /**
     * @param host {post(message)}: the way to the extension host
     * @param root the element the plot lives in
     */
    constructor(host, root) {
      this.host = host;
      this.root = root;
      this.log = null; // {path, name, start, end, entries: Map name -> sample_count}
      this.view = { start: 0, end: 1 };
      this.panes = [];
      this.activePane = -1;
      this.nextRequest = 1;
      this.requests = new Map(); // requestId -> {series, plan, key}
      this.colorIndex = 0;
      this.timeline = null; // {phases, ds}
      this.selected = null; // the series the statistics panel describes
      this.statisticsRequest = 0;
      this.fetchTimer = null;
      this.statisticsTimer = null;
      this.viewListeners = [];
      this.cursorListeners = [];
      this.marks = []; // the console's matches on the timeline: {time, level}
      this.markedTime = null; // the time a console line put the cursor at
      this.build();
      window.addEventListener("resize", () => this.resize());
      document.addEventListener("keydown", (e) => this.onKey(e));
    }

    build() {
      this.root.replaceChildren();
      const bar = el("div", "plot-bar");
      this.viewLabel = el("span", "muted", "");
      const addPane = el("button", "plot-button", "Add pane");
      addPane.type = "button";
      addPane.addEventListener("click", () => this.addPane());
      const whole = el("button", "plot-button", "Whole log");
      whole.type = "button";
      whole.title = "Show the whole log (Home)";
      whole.addEventListener("click", () => this.log && this.setView(this.log.start, this.log.end));
      bar.append(addPane, whole, this.viewLabel);
      this.timelineCanvas = el("canvas", "timeline");
      this.timelineCanvas.height = 44;
      this.timelineCanvas.title = "The whole log: enabled periods shaded (autonomous, teleop, test), Driver Station events marked. Drag to choose the window the panes show.";
      this.bindTimeline();
      this.panesRoot = el("div", "panes");
      this.statsRoot = el("aside", "stats hidden");
      const body = el("div", "plot-body");
      body.append(this.panesRoot, this.statsRoot);
      this.root.append(bar, this.timelineCanvas, body);
    }

    /** Registers a listener for the window: called with {start, end} after each change. */
    onViewChanged(listener) {
      this.viewListeners.push(listener);
    }

    /** Registers a listener for the cursor's time (null when the cursor leaves the panes). */
    onCursor(listener) {
      this.cursorListeners.push(listener);
    }

    /** A new log: everything starts over. */
    setLog(log) {
      this.log = log;
      this.view = { start: log.start, end: log.end };
      for (const pane of this.panes) pane.chart && pane.chart.destroy();
      this.panes = [];
      this.panesRoot.replaceChildren();
      this.activePane = -1;
      this.requests.clear();
      this.selected = null;
      this.statsRoot.classList.add("hidden");
      this.timeline = null;
      this.marks = [];
      this.markedTime = null;
      this.host.post({ type: "timeline" });
      this.drawTimeline();
      this.updateViewLabel();
      for (const listener of this.viewListeners) listener(this.view);
    }

    /**
     * Fetches a series for another view (the field view) by the plan the budget gives for the
     * current window, and resolves with {timestamps, values, count, bucketed}: the samples, or
     * the buckets' means. Rejects with the server's words.
     */
    fetchSeries(name) {
      if (!this.log) return Promise.reject(new Error("no log"));
      const plan = PlotMath.planRequest(
        { sampleCount: this.sampleCountOf(name), logStart: this.log.start, logEnd: this.log.end },
        { start: this.view.start, end: this.view.end, widthPx: this.paneWidth() }
      );
      return new Promise((resolve, reject) => {
        const requestId = this.nextRequest++;
        this.requests.set(requestId, { resolve, reject, plan });
        const message = { type: "fetch", requestId, name };
        if (plan.mode !== "full") {
          message.startTime = plan.startTime;
          message.endTime = plan.endTime;
        }
        if (plan.mode === "bucketed") message.maxPoints = plan.maxPoints;
        this.host.post(message);
      });
    }

    /** Opens a stream for an entry: {timestamps, values, count, bucketed}, values the samples or the buckets' means. */
    static seriesOf(bytes, name) {
      const stream = ArrowStream.read(bytes);
      const changed = ArrowStream.fileChanged(stream);
      if (changed) throw new Error("the file changed while it was read (" + changed + "); try again");
      const metadata = ArrowStream.parsedMetadata(stream);
      const data = ArrowStream.entrySeries(stream, name);
      const bucketed = Boolean(metadata.bucketed);
      return { timestamps: data.timestamps, values: bucketed ? data.columns.mean : data.columns.value, count: data.count, bucketed, data, metadata };
    }

    // ---- panes ----

    addPane() {
      const index = this.panes.length;
      const paneEl = el("div", "pane");
      const chips = el("div", "chips");
      const chartEl = el("div", "chart");
      const remove = el("button", "pane-remove", "×");
      remove.type = "button";
      remove.title = "Remove this pane";
      remove.addEventListener("click", () => this.removePane(pane));
      paneEl.append(chips, chartEl, remove);
      paneEl.addEventListener("mousedown", () => this.activate(pane));
      this.panesRoot.append(paneEl);
      const pane = { el: paneEl, chips, chartEl, chart: null, series: [] };
      this.panes.push(pane);
      this.activate(pane);
      this.rebuildChart(pane);
      return index;
    }

    removePane(pane) {
      const i = this.panes.indexOf(pane);
      if (i < 0) return;
      if (pane.chart) pane.chart.destroy();
      pane.el.remove();
      this.panes.splice(i, 1);
      if (pane.series.includes(this.selected)) this.selectSeries(null);
      if (this.activePane >= this.panes.length) this.activePane = this.panes.length - 1;
      this.panes.forEach((p) => p.el.classList.toggle("active", p === this.panes[this.activePane]));
    }

    activate(pane) {
      this.activePane = this.panes.indexOf(pane);
      this.panes.forEach((p) => p.el.classList.toggle("active", p === pane));
    }

    /** Adds an entry to the active pane (a new pane when there is none), and fetches it. */
    addSeries(name) {
      if (!this.log) return;
      if (this.activePane < 0) this.addPane();
      const pane = this.panes[this.activePane];
      if (pane.series.some((s) => s.name === name)) return;
      const sampleCount = this.sampleCountOf(name);
      const series = new Series(name, PALETTE[this.colorIndex++ % PALETTE.length], sampleCount);
      pane.series.push(series);
      this.drawChips(pane);
      this.fetchIfNeeded(series);
      this.rebuildChart(pane);
    }

    /** The listing's sample count for an entry, or the entry a field path is inside. */
    sampleCountOf(name) {
      if (this.log.entries.has(name)) return this.log.entries.get(name);
      let best = undefined;
      for (const [entry, count] of this.log.entries) {
        if (name.startsWith(entry) && (name[entry.length] === "." || name[entry.length] === "[")) {
          if (best === undefined || entry.length > best.length) best = { length: entry.length, count };
        }
      }
      return best ? best.count : Infinity;
    }

    removeSeries(pane, series) {
      pane.series = pane.series.filter((s) => s !== series);
      if (this.selected === series) this.selectSeries(null);
      this.drawChips(pane);
      this.rebuildChart(pane);
    }

    drawChips(pane) {
      pane.chips.replaceChildren();
      for (const series of pane.series) {
        const chip = el("span", "chip");
        chip.style.borderColor = series.color;
        const swatch = el("span", "swatch");
        swatch.style.background = series.color;
        const label = el("span", "chip-name", series.name + (series.unit ? " [" + series.unit + "]" : ""));
        label.title = "Statistics for this series over the visible window";
        label.addEventListener("click", () => this.selectSeries(series));
        const state = el("span", "chip-state muted", "");
        if (series.pending) state.textContent = "loading…";
        else if (series.error) { state.textContent = series.error; state.classList.add("error-text"); }
        else if (series.bucketed) state.textContent = "bucketed";
        else if (series.sampling === "change_only") state.textContent = "steps";
        const remove = el("button", "chip-remove", "×");
        remove.type = "button";
        remove.title = "Remove this series";
        remove.addEventListener("click", (e) => { e.stopPropagation(); this.removeSeries(pane, series); });
        chip.classList.toggle("selected", series === this.selected);
        chip.append(swatch, label, state, remove);
        pane.chips.append(chip);
      }
      if (pane.series.length === 0) pane.chips.append(el("span", "muted", "Click an entry's plot button, or an entry in the Entries view, to add it here."));
    }

    // ---- data ----

    fetchIfNeeded(series) {
      if (!this.log) return;
      const width = this.paneWidth();
      const plan = PlotMath.planRequest(
        { sampleCount: series.sampleCount, logStart: this.log.start, logEnd: this.log.end },
        { start: this.view.start, end: this.view.end, widthPx: width }
      );
      if (series.covers(this.view) && (plan.mode !== "bucketed" || !series.bucketed)) return;
      const key = PlotMath.requestKey(series.name, plan);
      if (series.pending === key) return;
      // A bucketed series already drawn at this window is not fetched again
      if (series.plan && PlotMath.requestKey(series.name, series.plan) === key) return;
      const requestId = this.nextRequest++;
      series.pending = key;
      series.error = null;
      this.requests.set(requestId, { series, plan, key });
      const message = { type: "fetch", requestId, name: series.name };
      if (plan.mode !== "full") {
        message.startTime = plan.startTime;
        message.endTime = plan.endTime;
      }
      if (plan.mode === "bucketed") message.maxPoints = plan.maxPoints;
      this.host.post(message);
      this.paneOf(series) && this.drawChips(this.paneOf(series));
    }

    /** The host's answer: the stream's bytes. */
    onData(message) {
      const request = this.requests.get(message.requestId);
      if (!request) return;
      this.requests.delete(message.requestId);
      if (request.resolve) {
        try {
          request.resolve(Plot.seriesOf(message.bytes, message.name));
        } catch (e) {
          request.reject(e);
        }
        return;
      }
      const { series, plan, key } = request;
      if (series.pending !== key) return; // superseded
      series.pending = null;
      try {
        const opened = Plot.seriesOf(message.bytes, series.name);
        const metadata = opened.metadata;
        const info = (metadata.entries || []).find((e) => e.name === series.name) || {};
        series.data = opened.data;
        series.plan = plan;
        series.bucketed = opened.bucketed;
        series.sampling = info.sampling;
        series.unit = info.unit;
        if (series.sampleCount === Infinity && Number.isFinite(info.sample_count)) series.sampleCount = info.sample_count;
      } catch (e) {
        series.error = String(e.message || e);
        series.data = null;
      }
      const pane = this.paneOf(series);
      if (pane) {
        this.drawChips(pane);
        this.rebuildChart(pane);
      }
    }

    onDataError(message) {
      const request = this.requests.get(message.requestId);
      if (!request) return;
      this.requests.delete(message.requestId);
      if (request.reject) {
        request.reject(new Error(message.message));
        return;
      }
      const { series, key } = request;
      if (series.pending !== key) return;
      series.pending = null;
      series.error = message.message;
      if (message.tooLarge && request.plan.mode !== "bucketed") {
        // The server refused the size: ask for buckets instead, whatever the budget said
        series.sampleCount = Infinity;
        this.fetchIfNeeded(series);
        return;
      }
      const pane = this.paneOf(series);
      if (pane) this.drawChips(pane);
    }

    paneOf(series) {
      return this.panes.find((p) => p.series.includes(series));
    }

    paneWidth() {
      const w = this.panesRoot.clientWidth || this.root.clientWidth || 800;
      return Math.max(100, w - AXIS_WIDTH - 24);
    }

    // ---- drawing ----

    rebuildChart(pane) {
      if (pane.chart) {
        pane.chart.destroy();
        pane.chart = null;
      }
      pane.chartEl.replaceChildren();
      const width = this.paneWidth() + AXIS_WIDTH;
      const fg = css("--vscode-foreground", "#ccc");
      const grid = css("--vscode-widget-border", "#444");
      const font = css("--vscode-font-size", "13px") + " " + css("--vscode-font-family", "sans-serif");
      const uSeries = [{ label: "t (s)", value: (u, v) => (v == null ? "–" : seconds(v)) }];
      const tables = [];
      const bands = [];
      const mapping = []; // uPlot series index -> {series, role}
      for (const series of pane.series) {
        if (!series.data) continue;
        const d = series.data;
        const t0 = this.view.start;
        const t1 = this.view.end;
        if (series.bucketed) {
          const [lo, hi] = PlotMath.visibleRange(d.timestamps, t0, t1);
          const ts = d.timestamps.subarray(lo, hi);
          const mean = d.columns.mean.subarray(lo, hi);
          const min = d.columns.min.subarray(lo, hi);
          const max = d.columns.max.subarray(lo, hi);
          const count = d.columns.count.subarray(lo, hi);
          const meanIndex = uSeries.length;
          uSeries.push({ label: series.name, stroke: series.color, width: 1.5, spanGaps: true, points: { show: false },
            value: (u, v, sidx, idx) => this.bucketReadout(u, sidx, idx) });
          mapping.push({ series, role: "mean" });
          uSeries.push({ label: series.name + " max", stroke: "transparent", show: true, spanGaps: true, points: { show: false }, value: () => "" });
          mapping.push({ series, role: "max" });
          uSeries.push({ label: series.name + " min", stroke: "transparent", show: true, spanGaps: true, points: { show: false }, value: () => "" });
          mapping.push({ series, role: "min" });
          bands.push({ series: [meanIndex + 1, meanIndex + 2], fill: series.color + "33" });
          tables.push([Array.from(ts), Array.from(mean, (v) => (Number.isNaN(v) ? null : v)),
            Array.from(max, (v) => (Number.isNaN(v) ? null : v)), Array.from(min, (v) => (Number.isNaN(v) ? null : v))]);
          series.counts = count;
          series.visibleTimes = ts;
        } else {
          const values = d.columns.value;
          const reduced = PlotMath.reduce(d.timestamps, values, t0, t1, this.paneWidth());
          const mode = PlotMath.drawMode(series.sampling, false);
          uSeries.push({
            label: series.name,
            stroke: series.color,
            width: 1.5,
            spanGaps: true,
            points: { show: reduced.times.length <= 60, size: 5 },
            paths: mode === "steps" ? uPlot.paths.stepped({ align: 1 }) : undefined,
            value: (u, v) => fixed(v),
          });
          mapping.push({ series, role: "value" });
          tables.push([Array.from(reduced.times), Array.from(reduced.values, (v) => (Number.isNaN(v) ? null : v))]);
        }
      }
      const data = tables.length > 0 ? uPlot.join(tables) : [[]];
      const self = this;
      const opts = {
        width,
        height: PANE_HEIGHT,
        scales: { x: { time: false, min: this.view.start, max: this.view.end }, y: { auto: true } },
        axes: [
          { stroke: fg, font, grid: { stroke: grid, width: 1 }, ticks: { stroke: grid }, values: (u, splits) => splits.map((s) => seconds(s).replace(/\.?0+$/, "")) },
          { stroke: fg, font, grid: { stroke: grid, width: 1 }, ticks: { stroke: grid }, size: AXIS_WIDTH, values: (u, splits) => splits.map((s) => fixed(s, 3).replace(/\.?0+$/, "")) },
        ],
        series: uSeries,
        bands,
        cursor: {
          sync: { key: "wpilog-explorer", setSeries: false },
          drag: { x: true, y: false },
          points: { size: 6 },
        },
        legend: { live: true },
        hooks: {
          setScale: [
            (u, key) => {
              if (key !== "x" || self.applying) return;
              const min = u.scales.x.min;
              const max = u.scales.x.max;
              if (Number.isFinite(min) && Number.isFinite(max) && (min !== self.view.start || max !== self.view.end)) {
                self.setView(min, max, u);
              }
            },
          ],
          setCursor: [
            (u) => {
              if (self.settingCursor) return;
              const left = u.cursor.left;
              const t = left != null && left >= 0 ? u.posToVal(left, "x") : null;
              for (const listener of self.cursorListeners) listener(t);
            },
          ],
          draw: [
            (u) => {
              // The time a console line put the cursor at, as a line through the pane
              if (self.markedTime === null) return;
              const x = u.valToPos(self.markedTime, "x", true);
              if (x < u.bbox.left || x > u.bbox.left + u.bbox.width) return;
              const ctx = u.ctx;
              ctx.save();
              ctx.strokeStyle = "#edc948";
              ctx.lineWidth = 1;
              ctx.setLineDash([3, 3]);
              ctx.beginPath();
              ctx.moveTo(x, u.bbox.top);
              ctx.lineTo(x, u.bbox.top + u.bbox.height);
              ctx.stroke();
              ctx.restore();
            },
          ],
        },
        plugins: [this.wheelPlugin()],
      };
      pane.chart = new uPlot(opts, data, pane.chartEl);
      pane.mapping = mapping;
      // uPlot resets to the full data range on double-click; make that the whole log
      pane.chartEl.addEventListener("dblclick", () => this.log && this.setView(this.log.start, this.log.end));
    }

    bucketReadout(u, sidx, idx) {
      const pane = this.panes.find((p) => p.chart === u);
      if (!pane || idx == null) return "–";
      const m = pane.mapping[sidx - 1];
      if (!m) return "–";
      const mean = u.data[sidx][idx];
      const max = u.data[sidx + 1][idx];
      const min = u.data[sidx + 2][idx];
      const t = u.data[0][idx];
      const k = m.series.visibleTimes ? PlotMath.indexAtOrBefore(m.series.visibleTimes, t) : -1;
      const n = k >= 0 && m.series.counts ? m.series.counts[k] : undefined;
      return "bucket: mean " + fixed(mean) + ", " + fixed(min) + " to " + fixed(max) + (n !== undefined ? ", n=" + n : "");
    }

    /** Zoom with the wheel about the cursor; shift+wheel pans. */
    wheelPlugin() {
      const self = this;
      return {
        hooks: {
          ready: (u) => {
            u.over.addEventListener("wheel", (e) => {
              if (!self.log) return;
              e.preventDefault();
              const rect = u.over.getBoundingClientRect();
              const x = e.clientX - rect.left;
              const about = u.posToVal(x, "x");
              const delta = Math.sign(e.deltaY);
              const next = e.shiftKey
                ? PlotMath.pan(self.view, 0.1 * delta, self.log.start, self.log.end)
                : PlotMath.zoom(self.view, delta > 0 ? 1.25 : 0.8, about, self.log.start, self.log.end);
              self.setView(next.start, next.end);
            }, { passive: false });
          },
        },
      };
    }

    onKey(e) {
      if (!this.log || e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;
      let next = null;
      if (e.key === "ArrowLeft") next = PlotMath.pan(this.view, -0.1, this.log.start, this.log.end);
      else if (e.key === "ArrowRight") next = PlotMath.pan(this.view, 0.1, this.log.start, this.log.end);
      else if (e.key === "+" || e.key === "=") next = PlotMath.zoom(this.view, 0.8, (this.view.start + this.view.end) / 2, this.log.start, this.log.end);
      else if (e.key === "-") next = PlotMath.zoom(this.view, 1.25, (this.view.start + this.view.end) / 2, this.log.start, this.log.end);
      else if (e.key === "Home") next = { start: this.log.start, end: this.log.end };
      if (next) {
        e.preventDefault();
        this.setView(next.start, next.end);
      }
    }

    /** The window every pane shows; `from` is the chart that set it, already there. */
    setView(start, end, from) {
      if (!this.log) return;
      if (!(end > start)) return;
      this.view = { start, end };
      this.applying = true;
      try {
        for (const pane of this.panes) {
          if (!pane.chart) continue;
          this.redrawPane(pane);
        }
      } finally {
        this.applying = false;
      }
      this.drawTimeline();
      this.updateViewLabel();
      for (const listener of this.viewListeners) listener(this.view);
      clearTimeout(this.fetchTimer);
      this.fetchTimer = setTimeout(() => {
        for (const pane of this.panes) for (const s of pane.series) this.fetchIfNeeded(s);
      }, PlotMath.DEBOUNCE_MS);
      this.scheduleStatistics();
    }

    /** Redraws a pane for the current view from the data in hand: the reduction is per view. */
    redrawPane(pane) {
      this.rebuildChart(pane);
    }

    updateViewLabel() {
      if (!this.log) return;
      this.viewLabel.textContent = "Window " + seconds(this.view.start) + " to " + seconds(this.view.end) + " s of " +
        seconds(this.log.start) + " to " + seconds(this.log.end) + " s. Drag to zoom, wheel to zoom about the cursor, shift+wheel or arrows to pan, double-click or Home for the whole log.";
    }

    resize() {
      for (const pane of this.panes) if (pane.chart) pane.chart.setSize({ width: this.paneWidth() + AXIS_WIDTH, height: PANE_HEIGHT });
      this.drawTimeline();
    }

    // ---- the console's marks and cursor ----

    /** The console's matches: marked on the timeline by level. */
    setMarks(marks) {
      this.marks = Array.isArray(marks) ? marks : [];
      this.drawTimeline();
    }

    /** Puts the cursor at a time: the window pans to show it, and every pane's cursor moves there. */
    markTime(t) {
      if (!this.log || !Number.isFinite(t)) return;
      if (t < this.view.start || t > this.view.end) {
        const span = this.view.end - this.view.start;
        const next = PlotMath.pan({ start: t - span / 2, end: t + span / 2 }, 0, this.log.start, this.log.end);
        this.setView(next.start, next.end);
      }
      this.markedTime = t;
      this.settingCursor = true;
      try {
        for (const pane of this.panes) {
          if (!pane.chart) continue;
          pane.chart.setCursor({ left: pane.chart.valToPos(t, "x"), top: 10 });
          pane.chart.redraw(false);
        }
      } finally {
        this.settingCursor = false;
      }
      for (const listener of this.cursorListeners) listener(t);
      this.drawTimeline();
    }

    // ---- the timeline ----

    onTimeline(message) {
      this.timeline = { phases: message.phases, ds: message.ds };
      this.drawTimeline();
    }

    bindTimeline() {
      const canvas = this.timelineCanvas;
      let dragStart = null;
      const timeAt = (e) => {
        const rect = canvas.getBoundingClientRect();
        const f = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width));
        return this.log.start + f * (this.log.end - this.log.start);
      };
      canvas.addEventListener("mousedown", (e) => {
        if (!this.log) return;
        dragStart = timeAt(e);
        e.preventDefault();
      });
      window.addEventListener("mousemove", (e) => {
        if (dragStart === null || !this.log) return;
        this.dragTo = timeAt(e);
        this.drawTimeline();
      });
      window.addEventListener("mouseup", (e) => {
        if (dragStart === null || !this.log) return;
        const end = timeAt(e);
        const a = Math.min(dragStart, end);
        const b = Math.max(dragStart, end);
        dragStart = null;
        this.dragTo = undefined;
        if (b - a > (this.log.end - this.log.start) / 1000) this.setView(a, b);
        else {
          // A click: center the window there
          const span = this.view.end - this.view.start;
          const next = PlotMath.pan({ start: a - span / 2, end: a + span / 2 }, 0, this.log.start, this.log.end);
          this.setView(next.start, next.end);
        }
        this.drawTimeline();
      });
    }

    drawTimeline() {
      const canvas = this.timelineCanvas;
      const width = canvas.clientWidth || this.root.clientWidth || 800;
      if (canvas.width !== width) canvas.width = width;
      const ctx = canvas.getContext("2d");
      const h = canvas.height;
      ctx.clearRect(0, 0, width, h);
      if (!this.log) return;
      const span = this.log.end - this.log.start;
      const x = (t) => (span > 0 ? ((t - this.log.start) / span) * width : 0);
      ctx.fillStyle = css("--vscode-editorWidget-background", "#222");
      ctx.fillRect(0, 0, width, h);
      // Phases: enabled segments shaded by mode
      const colors = { auto: "rgba(242, 142, 43, 0.35)", teleop: "rgba(78, 121, 167, 0.35)", test: "rgba(176, 122, 161, 0.35)", unknown: "rgba(128, 128, 128, 0.3)" };
      const segments = this.timeline && this.timeline.phases && Array.isArray(this.timeline.phases.segments) ? this.timeline.phases.segments : [];
      for (const s of segments) {
        if (s.state !== "enabled") continue;
        ctx.fillStyle = colors[s.mode] || colors.unknown;
        ctx.fillRect(x(s.start), 4, Math.max(1, x(s.end) - x(s.start)), h - 8);
      }
      // Driver Station events as marks
      const events = this.timeline && this.timeline.ds && Array.isArray(this.timeline.ds.events) ? this.timeline.ds.events : [];
      for (const ev of events) {
        if (!Number.isFinite(ev.timestamp)) continue;
        let color = null;
        if (ev.category === "power") color = "#e15759";
        else if (ev.category === "alert") color = "#edc948";
        else if (ev.category === "robot_state" && !ev.initial) color = css("--vscode-foreground", "#ccc");
        if (!color) continue;
        ctx.fillStyle = color;
        const px = x(ev.timestamp);
        if (ev.category === "robot_state") ctx.fillRect(px, 2, 1, h - 4);
        else ctx.fillRect(px - 1, ev.category === "power" ? 2 : h - 10, 3, 8);
      }
      // The console's matches, one mark per pixel column, by level
      const levels = { error: "#e15759", warning: "#edc948", info: "#76b7b2" };
      for (const level of ["info", "warning", "error"]) {
        const times = this.marks.filter((m) => (m.level || "info") === level).map((m) => m.time);
        ctx.fillStyle = levels[level];
        for (const t of PlotMath.markColumns(times, this.log.start, this.log.end, width)) {
          ctx.fillRect(x(t), h - 7, 1, 5);
        }
      }
      if (this.markedTime !== null) {
        ctx.fillStyle = "#edc948";
        ctx.fillRect(x(this.markedTime) - 1, 0, 2, h);
      }
      // The view
      ctx.strokeStyle = css("--vscode-focusBorder", "#09f");
      ctx.lineWidth = 2;
      ctx.strokeRect(x(this.view.start) + 1, 1, Math.max(2, x(this.view.end) - x(this.view.start) - 2), h - 2);
      if (this.dragTo !== undefined && this.dragStartTime !== undefined) {
        ctx.fillStyle = "rgba(255,255,255,0.15)";
      }
      ctx.fillStyle = css("--vscode-descriptionForeground", "#999");
      ctx.font = "10px " + css("--vscode-font-family", "sans-serif");
      ctx.fillText(seconds(this.log.start) + " s", 3, h - 3);
      const endText = seconds(this.log.end) + " s";
      ctx.fillText(endText, width - ctx.measureText(endText).width - 3, h - 3);
    }

    // ---- statistics ----

    selectSeries(series) {
      this.selected = series;
      for (const pane of this.panes) this.drawChips(pane);
      if (!series) {
        this.statsRoot.classList.add("hidden");
        return;
      }
      this.statsRoot.classList.remove("hidden");
      this.statsRoot.replaceChildren(el("div", "muted", "Statistics for " + series.name + "…"));
      this.scheduleStatistics(true);
    }

    scheduleStatistics(now) {
      if (!this.selected || !this.log) return;
      clearTimeout(this.statisticsTimer);
      this.statisticsTimer = setTimeout(() => {
        const requestId = ++this.statisticsRequest;
        this.host.post({ type: "statistics", requestId, name: this.selected.name, startTime: this.view.start, endTime: this.view.end });
      }, now ? 0 : PlotMath.STATISTICS_DEBOUNCE_MS);
    }

    onStatistics(message) {
      if (message.requestId !== this.statisticsRequest || !this.selected) return;
      const r = message.result || {};
      const root = this.statsRoot;
      root.replaceChildren();
      const head = el("div", "stats-head");
      head.append(el("h2", null, this.selected.name), el("div", "muted", "get_statistics over " + seconds(this.view.start) + " to " + seconds(this.view.end) + " s"));
      root.append(head);
      if (r.status !== "ok" && r.status !== "partial") {
        root.append(el("div", "error", r.error || r.reason || "No statistics for this window" + (r.hint ? "\n" + r.hint : "")));
        return;
      }
      const dl = el("dl");
      const add = (label, value) => { dl.append(el("dt", null, label), el("dd", null, value)); };
      add("Count", String(r.count));
      for (const k of ["min", "max", "mean", "median", "std_dev", "q1", "q3", "p5", "p95"]) {
        if (r[k] !== undefined) add(k.replace("_", " "), r[k] === null ? "null" : fixed(r[k]));
      }
      root.append(dl);
      const q = r.data_quality;
      if (q) {
        const qd = el("dl");
        const addQ = (label, value) => { qd.append(el("dt", null, label), el("dd", null, value)); };
        if (q.sampling) addQ("Sampling", q.sampling);
        if (q.quality_score !== undefined) addQ("Quality score", fixed(q.quality_score, 2));
        if (q.sample_count !== undefined) addQ("Samples in scope", String(q.sample_count));
        if (q.nan_filtered) addQ("Non-finite", String(q.nan_filtered));
        if (q.gap_count) addQ("Gaps", String(q.gap_count) + (q.max_gap_ms ? " (longest " + fixed(q.max_gap_ms, 1) + " ms)" : ""));
        root.append(el("h3", null, "Data quality"), qd);
        if (Array.isArray(q.reasons) && q.reasons.length > 0) {
          const ul = el("ul", "reasons");
          for (const reason of q.reasons) ul.append(el("li", null, String(reason)));
          root.append(ul);
        }
      }
      const d = r.server_analysis_directives;
      if (d) {
        const level = d.confidence_level ? "Confidence: " + d.confidence_level : "";
        if (level) root.append(el("div", "confidence", level));
        if (d.guidance) root.append(el("div", "muted small", String(d.guidance)));
      }
      if (Array.isArray(r.warnings) && r.warnings.length > 0) root.append(el("div", "warning", r.warnings.join("\n")));
    }
  }

  root.Plot = Plot;
})(typeof self !== "undefined" ? self : this);
