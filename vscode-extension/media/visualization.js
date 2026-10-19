/* Recorded preview presentation. Identity, revisions, samples and cursor values belong to the host/server. */
(function () {
  "use strict";
  const api = acquireVsCodeApi(), overview = document.body.dataset.overview === "true";
  const get = id => document.getElementById(id);
  const el = (tag, text, className) => { const e = document.createElement(tag); if (text !== undefined) e.textContent = text; if (className) e.className = className; return e; };
  let liveFrame, pendingLiveAck, pendingServer = false, navigationPending = false, restoredWindowFor;
  const selectionEvents = new WeakMap();
  function selectedRange() {
    if (historical()) {
      const windows = [...reductions.values()].filter(r => r.window && r.status !== "error").map(r => r.window);
      if (windows.length) return { start: Math.min(...windows.map(w => Number(w.start_us) / 1e6)), end: Math.max(...windows.map(w => Number(w.end_us) / 1e6)) };
    }
    if (!historical() && recording() && liveFrame?.range) return liveFrame.range;
    const policy = recording() ? liveFrame?.finalized ? snapshot.configuration.time.recorded : snapshot?.configuration.time.live : snapshot?.configuration.time.recorded;
    if (policy?.mode === "fixed") return { start: policy.start_sec, end: policy.end_sec };
    if (recording() && !liveFrame) return;
    let start = Infinity, end = -Infinity;
    for (const item of data.values()) for (const at of [item.timestamps[0], item.timestamps[item.timestamps.length - 1]])
      if (Number.isFinite(at)) { start = Math.min(start, at); end = Math.max(end, at); }
    if (Number.isFinite(start) && Number.isFinite(end)) return { start, end };
  }
  function timeControls() {
    if (overview || !snapshot) return;
    const range = selectedRange(), disabled = !visible || pendingServer || navigationPending;
    for (const id of ["time-earlier", "time-later", "time-zoom-in", "time-zoom-out"]) get(id).disabled = disabled || !range || range.end <= range.start;
    get("range").disabled = disabled; get("full").disabled = disabled || recording() && !liveFrame?.finalized;
    get("live-follow").disabled = disabled || !!liveFrame?.finalized;
    const policy = recording() ? liveFrame?.finalized ? snapshot.configuration.time.recorded : snapshot.configuration.time.live : snapshot.configuration.time.recorded;
    const label = policy.mode === "fixed" ? "Inspect History" : policy.mode === "follow" ? "Following" : "Full recording";
    get("time-mode").textContent = `${navigationPending ? "Updating interval… · " : ""}${label}${range ? ` · ${range.start} to ${range.end} s` : " · waiting for a displayed interval"}`;
  }
  function navigate(type, extra) {
    if (!visible || pendingServer || navigationPending) return;
    clearTimeout(cursorTimer); navigationPending = true; timeControls(); send(type, extra);
  }
  function rememberWindow() {
    const value = Number(get("live-window").value);
    if (Number.isFinite(value) && value >= 1 && value <= 300) api.setState?.({ view_id: snapshot.view_id, following_window_sec: value });
  }
  let analysisEpochs = { value: 0, statistics: 0, histogram: 0, scatter: 0, reduction: 0 }, analysisPaused = false, history = false;
  const sourceKind = ref => (ref?.schema_version === 2 ? ref.content : ref)?.kind;
  const recording = () => sourceKind(snapshot?.source_ref) === "session";
  const historical = () => recording() && (history
    || (liveFrame?.finalized ? snapshot.configuration.time.recorded : snapshot.configuration.time.live).mode !== "follow");
  let snapshot, inventory, generation = "", source, visible = true, cursorTimer;
  const charts = new Map(), panels = new Map(), data = new Map(), values = new Map(), reductions = new Map();
  const cursorEvents = new WeakMap(), bindings = new Map(), statistics = new Map(), raw = new Map(), histograms = new Map(), scatters = new Map();
  let templates = [], selectedTemplate, inventoryGeneration = 0, inventoryRetryTimer, inventoryFilterKey;
  let grid, applying = false;
  let fieldRequest = 0, fieldBusy = false, fieldEligible = false, fieldSelected;
  function fieldControls() {
    if (overview) return;
    get("inventory-field").disabled = !fieldEligible;
    get("inventory-field-add").disabled = !fieldEligible || fieldBusy || !get("inventory-field").value.trim();
  }
  function cancelField(announce = false) {
    fieldRequest++; fieldBusy = false;
    if (!overview) { get("inventory-field-status").textContent = ""; fieldControls(); }
    if (announce) send("cancelFieldSelection");
  }
  const send = (type, extra = {}) => api.postMessage({ type, view_id: snapshot?.view_id, revision: snapshot?.revision, generation, ...extra });
  const button = (label, action) => { const b = el("button", label); b.onclick = action; return b; };
  const action = (id, type, extra) => { if (get(id)) get(id).onclick = () => send(type, extra); };
  function cursorAt(at, origin) {
    if (recording() && analysisPaused || at !== undefined && !Number.isFinite(at)) return;
    if (snapshot?.configuration.links.cursor) for (const [id, chart] of charts) if (chart !== origin && snapshot.configuration.panels.find(p => p.panel_id === id)?.type === "time_series")
      chart.setCursor({ left: at === undefined ? -10 : chart.valToPos(at, "x"), top: at === undefined ? -10 : 0 }, false);
    get("cursor").value = at === undefined ? "" : String(at); send("cursor", { at });
  }
  function facts(summary) {
    source = summary; const recording = sourceKind(summary.source_ref) === "session";
    get("title").textContent = summary.label; get("source").textContent = recording ? "Recording · inspection preview" : "Recorded source · preview";
    for (const id of ["blank", "template-open"]) if (get(id)) get(id).disabled = false;
    get("warning").textContent = recording ? "Recording panel preview: plots and raw rows follow completed prefixes. Values and statistics read independent server prefixes." : "";
    const catalog = summary.catalog, rows = [["Source", summary.label], ["Identity", recording ? "Anchored recording" : sourceKind(summary.source_ref) === "recorded" ? "Managed recorded content" : "File fingerprint"]];
    rows.push(["Robot", catalog?.robot?.name ?? catalog?.robot?.serial_number ?? "Unavailable: not recorded"]);
    rows.push(["Session", catalog?.session?.started_at ?? "Unavailable: no recorded session"]);
    rows.push(["Match", catalog?.session?.match_number == null ? "Unavailable: not recorded" : `${catalog.session.match_type ?? ""} ${catalog.session.match_number}`]);
    rows.push(["File size", `${summary.size_bytes} bytes`]);
    get("facts").replaceChildren(...rows.flatMap(([a, b]) => [el("dt", a), el("dd", String(b))]));
  }
  function templateAction(type) {
    if (type === "importTemplate" || type === "saveTemplate" || selectedTemplate) send(type, { template_ref: selectedTemplate });
  }
  function recordingClock(clock) {
    switch (clock.basis) {
      case "robot_estimate": return "Robot clock estimate";
      case "last_sample": return clock.waiting_for_time_sync ? "Last sample; waiting for time sync" : "Last recorded sample";
      case "waiting_for_time_sync": return "Waiting for time sync";
      default: return "No recorded samples";
    }
  }
  function inventoryFilterControls() {
    for (const id of ["inventory-search", "inventory-type"]) get(id).disabled = get("inventory-unavailable").checked;
  }
  if (get("template-find")) get("template-find").onclick = () => send("templateSearch", { query: get("template-search").value });
  if (get("templates")) get("templates").onchange = () => {
    selectedTemplate = templates[Number(get("templates").value)]?.template_ref;
    if (selectedTemplate) { get("template-status").textContent = "Checking compatibility…"; templateAction("templateCheck"); }
  };
  for (const [id, type] of [["template-open", "applyTemplate"], ["template-replace", "replaceTemplate"], ["template-save", "saveTemplate"], ["template-update", "updateTemplate"], ["template-export", "exportTemplate"], ["template-import", "importTemplate"]]) if (get(id)) get(id).onclick = () => templateAction(type);
  if (get("inventory-browser")) {
    for (const [id, type] of [["inventory-next", "inventoryNext"], ["inventory-previous", "inventoryPrevious"], ["inventory-retry", "inventoryRefresh"]])
      get(id).onclick = () => send(type, { inventory_generation: inventoryGeneration });
    const filter = () => send("inventoryFilter", { query: get("inventory-search").value, exact_type: get("inventory-type").value, unavailable: get("inventory-unavailable").checked });
    get("inventory-unavailable").onchange = inventoryFilterControls;
    get("inventory-find").onclick = filter;
    for (const id of ["inventory-search", "inventory-type"]) get(id).onkeydown = event => { if (event.key === "Enter") { event.preventDefault(); filter(); } };
  }
  if (overview) { action("blank", "blank"); action("refresh", "refresh"); } else {
    action("live-pause", "livePause", { paused: true }); action("live-resume", "livePause", { paused: false }); action("live-retry", "liveRetry");
    get("live-follow").onclick = () => { rememberWindow(); navigate("liveFollow", { window_sec: Number(get("live-window").value) }); };
    get("live-window").onchange = rememberWindow;
    for (const [id, action] of [["time-earlier", "earlier"], ["time-later", "later"], ["time-zoom-in", "zoom_in"], ["time-zoom-out", "zoom_out"]])
      get(id).onclick = () => { const range = selectedRange(); if (range) navigate("timeNavigate", { action, ...range }); };
    get("inventory-field").oninput = () => cancelField(true);
    const addField = () => {
      fieldControls(); if (get("inventory-field-add").disabled) return;
      fieldBusy = true; fieldRequest++; fieldControls();
      get("inventory-field-status").textContent = "Checking this exact field across recording members…";
      send("addInventorySignal", { panel_id: get("target").value, inventory_generation: inventoryGeneration,
        field_path: get("inventory-field").value, field_request: fieldRequest });
    };
    get("inventory-field-add").onclick = addField;
    get("inventory-field").onkeydown = event => { if (event.key === "Enter") { event.preventDefault(); addField(); } };
    get("inventory-add").onclick = () => send("addInventorySignal", { panel_id: get("target").value, inventory_generation: inventoryGeneration });
    grid = GridStack.init({ column: 12, cellHeight: 64, margin: 6, float: true, animate: false,
      handle: ".panel-header", draggable: { cancel: "input,button,select" } }, get("grid"));
    grid.on("change", () => {
      if (!applying && snapshot) send("geometry", { items: grid.save(false).map(w => ({ panel_id: w.id, x: w.x, y: w.y, w: w.w, h: w.h })) });
    });
    for (const [id, type] of [["overview", "overview"], ["plot", "addPanel"], ["values", "addPanel"], ["statistics", "addPanel"], ["raw", "addPanel"], ["histogram", "addPanel"], ["scatter", "addPanel"], ["save", "save"], ["duplicate", "duplicate"], ["undo", "undo"], ["redo", "redo"], ["reset", "reset"]]) action(id, type, ["plot", "values", "statistics", "raw", "histogram", "scatter"].includes(id) ? { panel_type: id === "plot" ? "time_series" : id === "values" ? "latest" : id } : {});
    get("search").oninput = signals;
    get("target").onchange = () => { cancelField(); send("target", { panel_id: get("target").value }); };
    get("add").onclick = () => send("addSignals", { panel_id: get("target").value, names: [...get("signals").selectedOptions].map(o => o.value) });
    get("range").onclick = () => {
      if (!String(get("start").value).trim() || !String(get("end").value).trim()) { get("warning").textContent = "Enter both Start and End in robot-clock seconds"; return; }
      navigate("range", { start: Number(get("start").value), end: Number(get("end").value) });
    };
    get("full").onclick = () => navigate("full");
    get("at").onclick = () => cursorAt(Number(get("cursor").value)); get("latest").onclick = () => cursorAt(undefined);
  }
  function signals() {
    if (!inventory) return;
    const selected = new Set([...get("signals").selectedOptions].map(o => o.value));
    const query = get("search").value.toLocaleLowerCase();
    get("signals").replaceChildren(...inventory.entries.filter(e => e.name.toLocaleLowerCase().includes(query)).map(e => {
      const option = el("option", `${e.name} (${e.type})`); option.value = e.name; option.selected = selected.has(e.name); return option;
    }));
  }
  function destroyPanel(id) {
    charts.get(id)?.destroy(); charts.delete(id);
    const panel = panels.get(id); if (panel) { panel.resize.disconnect(); grid.removeWidget(panel.widget); panels.delete(id); }
  }
  function layout() {
    if (!snapshot) return;
    const config = snapshot.configuration; applying = true; grid.batchUpdate();
    for (const id of panels.keys()) if (!config.panels.some(p => p.panel_id === id)) destroyPanel(id);
    for (const spec of config.panels) {
      let panel = panels.get(spec.panel_id);
      if (!panel) {
        const widget = el("div", undefined, "grid-stack-item"), content = el("section", undefined, "grid-stack-item-content");
        const header = el("div", undefined, "panel-header"), title = el("input", undefined, "panel-title"); title.setAttribute("aria-label", "Panel title");
        title.onchange = () => send("title", { panel_id: spec.panel_id, title: title.value });
        const controls = el("details", undefined, "panel-controls"); controls.append(el("summary", "Move, resize or remove panel"));
        for (const [label, operation] of [["Earlier", "earlier"], ["Later", "later"], ["←", "left"], ["→", "right"], ["Wider", "wider"], ["Narrower", "narrower"], ["Taller", "taller"], ["Shorter", "shorter"], ["Remove", "remove"]]) {
          const b = button(label, () => send("layout", { panel_id: spec.panel_id, action: operation })); b.setAttribute("aria-label", `${label} panel`); controls.append(b);
        }
        const body = el("div", undefined, "panel-body"), note = el("p", "No signals selected", "panel-note");
        header.append(title); content.append(header, controls, body, note); widget.append(content); get("grid").append(widget);
        const geometry = config.layout.items.find(i => i.panel_id === spec.panel_id);
        grid.makeWidget(widget, { id: spec.panel_id, ...geometry });
        const resize = new ResizeObserver(() => { if (visible) draw(spec.panel_id); }); resize.observe(body);
        panel = { widget, title, body, note, resize }; panels.set(spec.panel_id, panel);
        content.addEventListener("focusin", () => { get("target").value = spec.panel_id; send("target", { panel_id: spec.panel_id }); });
      }
      if (document.activeElement !== panel.title) panel.title.value = spec.title;
      grid.update(panel.widget, config.layout.items.find(i => i.panel_id === spec.panel_id));
    }
    grid.batchUpdate(false); applying = false;
    const selected = get("target").value;
    get("target").replaceChildren(...config.panels.map(p => { const o = el("option", p.title); o.value = p.panel_id; return o; }));
    if (config.panels.some(p => p.panel_id === selected)) get("target").value = selected;
    for (const id of panels.keys()) draw(id);
  }
  function display(value) {
    if (!value) return "No recorded sample";
    if (value.type === "f64" || value.type === "f32") {
      const buffer = new ArrayBuffer(8), view = new DataView(buffer);
      if (value.type === "f64") { view.setBigUint64(0, BigInt("0x" + value.value)); return String(view.getFloat64(0)); }
      view.setUint32(0, parseInt(value.value, 16)); return String(view.getFloat32(0));
    }
    return typeof value.value === "object" ? JSON.stringify(value.value) : String(value.value);
  }
  function analysisProof(result) {
    const proof = el("details", undefined, "analysis-prefix");
    proof.append(el("summary", `Independent analysis prefix · ${result.prefix.source_kind === "file" ? "file" : "catalog " + result.prefix.catalog_revision} · ${result.prefix.finalized ? "Finalized" : "Recording"} · not the plot frontier`),
      el("pre", JSON.stringify({ prefix: result.prefix, inputs: result.inputs, problems: result.problems, problem_count: result.problem_count, problems_truncated: result.problems_truncated }, null, 2)));
    return proof;
  }
  // Hidden editors do not draw: explicitly release tables and chart storage before the host
  // releases their reservation. Clearing only the model would leave hidden DOM owning values.
  function clearSampleDisplays() {
    for (const chart of charts.values()) {
      chart.setData(chart.previewKind ? [null, ...chart.series.slice(1).map(() => [[], []])] : chart.series.map(() => [])); chart.destroy();
    }
    charts.clear();
    for (const panel of panels.values()) { panel.body.replaceChildren(); panel.note.textContent = "No retained samples"; }
  }
  // Charts are independent server analyses. Only bin coordinates are projected for drawing;
  // exact edges/counts and timestamp pairs remain available in the accessible table.
  function drawChart(id, spec, panel) {
    const scatter = spec.type === "scatter", content = el("div"), sources = [], names = [];
    const label = series => {
      const req = snapshot.configuration.requirements.find(r => r.id === series.requirement_id), b = bindings.get(series.requirement_id);
      return (b?.entry ?? req?.entry ?? req?.role ?? series.requirement_id) + (b?.field_path ?? req?.field_path ?? "");
    };
    const table = (headers, rows) => {
      const detail = el("details"), result = el("table"), head = el("tr");
      detail.append(el("summary", "Exact server measurements"));
      headers.forEach(name => head.append(el("th", name))); result.append(head);
      for (const cells of rows) { const row = el("tr"); cells.forEach(value => row.append(el("td", String(value)))); result.append(row); }
      detail.append(result); return detail;
    };
    const unresolved = spec.series.map(s => bindings.get(s.requirement_id)).filter(b => b?.state === "unresolved");
    const results = scatter ? [{ series: spec.series[0], result: scatters.get(JSON.stringify(spec.series.map(s => s.requirement_id))) }]
      : spec.series.map(series => ({ series, result: histograms.get(series.requirement_id) }));
    if (scatter) {
      content.append(el("p", `X: ${spec.series[0] ? label(spec.series[0]) : "select first signal"} · Y: ${spec.series[1] ? label(spec.series[1]) : "select second signal"}`));
      const swap = button("Swap X and Y", () => send("scatterSwap", { panel_id: id })); swap.disabled = spec.series.length !== 2; content.append(swap);
    }
    const retry = button("Refresh chart / Retry", () => send("analysisRetry")); retry.disabled = analysisPaused; content.append(retry);
    for (const { series, result } of results) {
      if (scatter && (spec.series.length !== 2 || unresolved.length)) {
        content.append(el("p", unresolved.map(b => b.reason).join("; ") || "Select two numeric signals: first X, then Y.")); continue;
      }
      if (!series) continue;
      const name = scatter ? `${label(spec.series[0])} / ${label(spec.series[1])}` : label(series);
      content.append(el("h3", name));
      const b = bindings.get(series.requirement_id);
      if (b?.state === "unresolved") { content.append(el("p", b.reason)); continue; }
      if (scatter && result) {
        const first = button("First pairs", () => send("scatterPage", { panel_id: id, offset: 0 })); first.disabled = analysisPaused; content.append(first);
      }
      if (!result) { content.append(el("p", "Loading server chart…")); continue; }
      if (!["ok", "partial", "no_match"].includes(result.status)) { content.append(el("p", result.error ?? result.reason ?? result.status)); continue; }
      if (result.prefix) content.append(analysisProof(result));
      content.append(el("p", scatter ? result.alignment_rule : result.histogram_rule));
      const quality = el("details"); quality.append(el("summary", "Summary and data quality"),
        el("pre", JSON.stringify({ summary: result.summary, data_quality: result.data_quality, skipped: result.skipped, directives: result.directives }, null, 2))); content.append(quality);
      if (scatter) {
        content.append(el("p", `${result.matched} matched; ${result.unpaired_x} unpaired X; ${result.unpaired_y} unpaired Y. Page offset ${result.offset}: ${result.points.length} pairs. Each page reads a fresh prefix and replaces the previous page.`));
        if (!result.complete) { const next = button("Next pairs", () => send("scatterPage", { panel_id: id, offset: result.next_offset })); next.disabled = analysisPaused; content.append(next); }
        content.append(table(["X", "Y", "Robot time (s)"], result.points));
        if (result.points.length) sources.push({ name, color: series.color, x: result.points.map(p => p[0]), y: result.points.map(p => p[1]) });
        names.push(label(spec.series[0]), label(spec.series[1]));
      } else {
        content.append(table(["Low", "High", "Count"], result.bins.map(b => [b.low, b.high, b.count])));
        if (result.bins.length) sources.push({ name, color: series.color, low: Math.min(...result.bins.map(b => b.low)), high: Math.max(...result.bins.map(b => b.high)),
          x: result.bins.map(b => b.low / 2 + b.high / 2), y: result.bins.map(b => b.count) });
      }
      if (result.status === "no_match") content.append(el("p", result.hint));
    }
    let chart = charts.get(id);
    if (sources.length) {
      const definitions = [{}, ...sources.map(s => ({ label: s.name, stroke: s.color, fill: s.color + "88",
        paths: scatter ? () => null : uPlot.paths.bars({ size: [0.9, Infinity] }), points: { show: scatter, size: 5,
          // Mode 2 has separate X arrays; uPlot's default points receive null shared indices.
          ...(scatter ? { paths: (u, series) => uPlot.paths.points()(u, series, 0, u.data[series][0].length - 1) } : {}) } }))];
      const signature = JSON.stringify([spec.type, sources.map(s => [s.name, s.color]), spec.display.legend]);
      const points = [null, ...sources.map(s => [s.x, s.y])];
      const size = { width: panel.body.clientWidth, height: Math.max(160, panel.body.clientHeight - 4) };
      if (!chart || chart.previewSignature !== signature) {
        chart?.destroy(); panel.chartElement = el("div", undefined, "analysis-chart");
        // Attach before construction: uPlot measures its legend and axes on creation.
        panel.body.replaceChildren(panel.chartElement, content);
        chart = new uPlot({ ...size, mode: 2, scales: { x: { time: false } }, series: definitions,
          legend: { show: spec.display.legend }, axes: [scatter ? names[0] : "Logged value", scatter ? names[1] : "Sample count"].map(label => ({
            label, stroke: getComputedStyle(document.body).color, grid: { stroke: "rgba(128,128,128,0.25)" } })) }, points, panel.chartElement);
        chart.previewSignature = signature; chart.previewKind = spec.type; charts.set(id, chart);
      } else { chart.setData(points); chart.setSize(size); panel.body.replaceChildren(panel.chartElement, content); }
      if (!scatter) {
        // Auto-ranging bin centers clips the outer bars and a nonzero Y minimum distorts counts.
        chart.setScale("x", { min: Math.min(...sources.map(s => s.low)), max: Math.max(...sources.map(s => s.high)) });
        chart.setScale("y", { min: 0, max: Math.max(1, ...sources.flatMap(s => s.y)) * 1.1 });
      }
    } else { chart?.destroy(); charts.delete(id); panel.body.replaceChildren(content); }
    panel.note.textContent = scatter ? "Exact timestamp pairs from original samples. Axes are logged values, not the linked time cursor." : "Server bins over original samples. Overlaid signals have independent bins; exact edges and counts are listed above.";
  }
  function draw(id) {
    const panel = panels.get(id), spec = snapshot?.configuration.panels.find(p => p.panel_id === id);
    if (!panel || !spec || !visible || panel.body.clientWidth <= 0 || panel.body.clientHeight <= 0) return;
    if (!["time_series", "histogram", "scatter"].includes(spec.type)) { charts.get(id)?.destroy(); charts.delete(id); }
    if (spec.type === "histogram" || spec.type === "scatter") drawChart(id, spec, panel);
    else if (spec.type === "statistics" || spec.type === "raw") {
      const content = el("div");
      for (const series of spec.series) {
        const r = snapshot.configuration.requirements.find(r => r.id === series.requirement_id), binding = bindings.get(r.id);
        const result = (spec.type === "statistics" ? statistics : raw).get(r.id);
        content.append(el("h3", (binding?.entry ?? r.entry ?? r.role) + (r.field_path ?? "")));
        if (binding?.state === "unresolved") { content.append(el("p", binding.reason)); continue; }
        if (!result) { content.append(el("p", "Loading…")); continue; }
        if (result.status === "error") { content.append(el("p", result.error)); continue; }
        if (result.status && !["ok", "partial"].includes(result.status)) { content.append(el("p", result.hint ?? result.reason ?? result.status)); continue; }
        const table = el("table"); table.className = spec.type === "raw" ? "raw-records" : "recorded-statistics";
        const head = el("tr");
        if (spec.type === "statistics") {
          for (const name of ["Measurement", "Value"]) head.append(el("th", name)); table.append(head);
          for (const key of ["count", "min", "max", "mean", "median", "std_dev", "non_finite_count"]) if (key in result) {
            const row = el("tr"); row.append(el("td", key), el("td", result[key] == null ? "Unavailable" : String(result[key]))); table.append(row);
          }
          content.append(table, el("p", JSON.stringify({ data_quality: result.data_quality, inputs: result.inputs, skipped: result.skipped })));
          if (recording() && result.prefix) content.append(analysisProof(result));
        } else {
          for (const name of [...(recording() ? ["Member"] : []), "Ordinal", "Timestamp (µs)", "Value"]) head.append(el("th", name)); table.append(head);
          for (const sample of result.samples) { const row = el("tr"); if (recording()) row.append(el("td", sample.member_id)); row.append(el("td", String(sample.ordinal)), el("td", sample.timestamp_us), el("td", display(sample.value))); table.append(row); }
          if (recording()) { content.append(table, el("p", `${result.records_visited} visited; ${result.retained} most recently delivered rows retained; ${result.problems} decode/projection problems. Up to 32 recent diagnostics:`)); for (const issue of result.issues ?? []) content.append(el("pre", JSON.stringify(issue))); }
          else { content.append(table, el("p", `${result.records_visited} visited; next ordinal ${result.next_ordinal} of ${result.total_records}. ${result.complete ? "End of entry." : "More records available."} ${result.problems.length} decode/projection problems.`));
          content.append(button("First page", () => send("rawPage", { requirement_id: r.id, first_ordinal: 0 })));
          if (!result.complete) content.append(button("Next page", () => send("rawPage", { requirement_id: r.id, first_ordinal: result.next_ordinal }))); }
        }
      }
      panel.body.replaceChildren(content); panel.note.textContent = spec.type === "statistics" ? "Server statistics over original samples in the selected time interval; plots are not inputs." : recording() ? "Physical delivery order, with member and ordinal identity. At most 256 recent rows per selected signal; timestamps do not identify records." : "Physical record order; bounded pages replace the previous page. Timestamps do not identify records.";
    } else if (spec.type === "latest") {
      const table = el("table"), head = el("tr"), proofs = []; table.className = "recorded-values";
      for (const label of ["Signal", "Recorded value", "Timestamp (µs)", ...(recording() ? ["Member", "Ordinal"] : [])]) head.append(el("th", label)); table.append(head);
      for (const series of spec.series) {
        const requirement = snapshot.configuration.requirements.find(r => r.id === series.requirement_id), result = values.get(series.requirement_id);
        const row = el("tr"); row.append(el("td", (bindings.get(requirement.id)?.entry ?? requirement.entry ?? requirement.role) + (requirement.field_path ?? "")), el("td", bindings.get(requirement.id)?.state === "unresolved" ? bindings.get(requirement.id).reason : result?.status === "error" ? result.error : result ? display(result.value) : "Loading…"), el("td", result?.timestamp_us ?? "Unavailable"));
        if (recording()) { row.append(el("td", result?.member_id ?? "Unavailable"), el("td", result?.ordinal ?? "Unavailable")); if (result?.prefix) proofs.push(analysisProof(result)); }
        table.append(row);
      }
      panel.body.replaceChildren(table, ...proofs);
      const problems = spec.series.reduce((n, s) => n + (values.get(s.requirement_id)?.problem_count ?? 0), 0);
      panel.note.textContent = `Last logged value at or before cursor; no interpolation.${recording() ? " Ties use member identity then physical ordinal. Independent server reads may be newer than the plot; exact integer text is retained." : ""}${problems ? ` ${problems} records could not be decoded in the selected signals.` : ""}`;
    } else {
      const plotted = historical() ? new Map([...reductions].filter(([, r]) => r.status !== "error").map(([id, r]) => [id, {
        bucketed: true, count: r.buckets.length, timestamps: r.buckets.map(b => Number(b.start_us) / 1e6), values: r.buckets.map(b => b.mean),
        data: { columns: { max: r.buckets.map(b => b.max), min: r.buckets.map(b => b.min) } }
      }])) : data;
      const definitions = [{ label: "Robot time (s)" }], tables = [], bands = [];
      for (const s of spec.series) {
        const item = plotted.get(s.requirement_id); if (!item) continue;
        const req = snapshot.configuration.requirements.find(r => r.id === s.requirement_id);
        const index = definitions.length;
        definitions.push({ label: (bindings.get(req.id)?.entry ?? req.entry ?? req.role) + (req.field_path ?? ""), stroke: s.color, width: spec.display.line_width, points: { show: item.count < 100 } });
        const values = Array.from(item.values, v => Number.isFinite(v) ? v : null);
        if (item.bucketed) {
          definitions.push({ label: "max", stroke: "transparent", points: { show: false } }, { label: "min", stroke: "transparent", points: { show: false } });
          bands.push({ series: [index + 1, index + 2], fill: s.color + "33" });
          tables.push([Array.from(item.timestamps), values, Array.from(item.data.columns.max), Array.from(item.data.columns.min)]);
        } else tables.push([Array.from(item.timestamps), values]);
      }
      const historyNotes = () => spec.series.map(s => {
        const r = reductions.get(s.requirement_id);
        return !r ? "Loading full-interval history…" : r.status === "error" ? r.error + ". Use Retry." : r.status === "no_match" ? r.hint
          : `${r.sample_count.toLocaleString()} original samples in ${r.buckets.length} server min/max/mean buckets; ${r.unplottable_count} unplottable, ${r.problem_count} undecoded. Catalog ${r.prefix.catalog_revision ?? "file"}; independent of the follow frontier.`;
      }).join(" ");
      if (!tables.length) { charts.get(id)?.destroy(); charts.delete(id); panel.body.replaceChildren();
        panel.note.textContent = spec.series.length ? historical() ? historyNotes() : "Loading recorded samples…" : "Add signals to this plot"; return; }
      const points = uPlot.join(tables), signature = JSON.stringify(definitions);
      let chart = charts.get(id);
      if (!chart || chart.previewSignature !== signature) {
        chart?.destroy(); panel.body.replaceChildren();
        chart = new uPlot({ width: panel.body.clientWidth, height: Math.max(80, panel.body.clientHeight - 4), series: definitions, bands,
          axes: [0, 1].map(() => ({ stroke: getComputedStyle(document.body).color, grid: { stroke: "rgba(128,128,128,0.25)" } })),
          scales: { x: { time: false } }, legend: { show: spec.display.legend },
          cursor: { drag: { x: true, y: false, setScale: false, dist: 6 }, bind: { dblclick: () => null } },
          hooks: { setCursor: [u => {
            // uPlot can recalculate its cursor in a later microtask after setData/setSize.
            // Only a new input event is a gesture; a redraw of the same event is not a query.
            const event = u.cursor.event;
            if (!event || cursorEvents.get(u) === event) return;
            cursorEvents.set(u, event);
            if (event.buttons === 1 || !snapshot.configuration.links.cursor || u.cursor.left == null || u.cursor.left < 0 || u.cursor.top < 0) return;
            const at = u.posToVal(u.cursor.left, "x"); clearTimeout(cursorTimer);
            cursorTimer = setTimeout(() => cursorAt(at, u), 150);
          }], setSelect: [u => {
            // Selection can also be reported during a redraw. Only a new completed drag is
            // a durable gesture; toolkit scaling would clip the retained buffer without a read.
            const event = u.cursor.event, selection = u.select;
            if (!event || event.type !== "mouseup" || selectionEvents.get(u) === event || selection.width < 6) return;
            selectionEvents.set(u, event); cursorEvents.set(u, event); clearTimeout(cursorTimer);
            const start = u.posToVal(selection.left, "x"), end = u.posToVal(selection.left + selection.width, "x");
            u.setSelect({ width: 0, height: 0 }, false);
            if (snapshot.configuration.links.time_range && Number.isFinite(start) && Number.isFinite(end) && start < end)
              navigate("range", { panel_id: id, start, end });
          }] } }, points, panel.body);
        chart.previewSignature = signature; charts.set(id, chart);
      } else { chart.setData(points); chart.setSize({ width: panel.body.clientWidth, height: Math.max(80, panel.body.clientHeight - 4) }); }
      const range = selectedRange();
      if (range && range.end > range.start) chart.setScale("x", { min: range.start, max: range.end });
      timeControls();
      panel.note.textContent = historical() ? historyNotes() + " Buckets are drawing summaries, not inputs to statistics." : recording() ? "At most 4,096 recent plot samples per signal. Equal timestamps display the last delivered value; unsafe integers and non-finite values are gaps. " + spec.series.map(s => { const d = data.get(s.requirement_id); return d ? `${d.trimmed} trimmed, ${d.duplicate_times} equal-time points collapsed, ${d.unplottable} unplottable, ${d.problems ?? 0} undecoded records (time may be unknown).` : ""; }).join(" ") : [...data.values()].some(d => d.bucketed) ? "Server min/max bands and means; drag or use Zoom in for detail." : "Recorded samples · robot time in seconds";
    }
    send("rendered", { panels: panels.size, plots: charts.size, rows: [...panels.values()].reduce((n, p) => n + p.body.querySelectorAll(".recorded-values tr").length, 0), width: panel.body.clientWidth, height: panel.body.clientHeight,
      axis_colors: [...charts.values()].map(c => c.axes[0].stroke(c, 0)), foreground: getComputedStyle(document.body).color });
  }
  window.addEventListener("message", event => {
    const message = event.data;
    if (message.type === "templates") {
      templates = message.catalog.templates; selectedTemplate = undefined;
      get("templates")?.replaceChildren(...templates.map((item, i) => {
        const ref = item.template_ref, option = el("option", `${item.template.name} · ${ref.origin === "bundled" ? "Built-in" : ref.origin === "library" ? "Server library" : item.store_label ?? ref.store_id} · r${ref.revision} · ${ref.template_id.slice(0, 8)}`); option.value = String(i); return option;
      }));
      const next = message.catalog.next;
      if (get("template-next")) { get("template-next").hidden = !next; get("template-next").onclick = () => send("templateSearch", { query: get("template-search").value, after: next }); }
      if (get("template-status")) get("template-status").textContent = "Select a template to check compatibility before opening.";
      get("template-problems")?.replaceChildren(...(message.catalog.unreadable ?? []).map(p => el("p", `${p.file ?? p.key}: ${p.reason}`)));
      if (message.catalog.listing_incomplete) get("template-problems")?.append(el("p", "More templates are available on the next page."));
      return;
    }
    if (message.type === "templateCompatibility") {
      if (JSON.stringify(message.template_ref) !== JSON.stringify(selectedTemplate)) return;
      get("template-status").textContent = `${message.result.compatibility}: ` + message.result.bindings.map(b => `${b.entry ?? b.selector ?? b.requirement_id}: ${b.reason}; ${b.basis ?? ""} ${b.warnings.join("; ")}`).join(" | "); return;
    }
    if (message.type === "warning") { get("warning").textContent = message.message; navigationPending = false; timeControls(); return; }
    if (message.type === "summary" && overview) { facts(message.summary); get("status").textContent = "Source details available; signal inventory loading…"; return; }
    if (message.type === "inventoryPage") {
      const s = message.state;
      const rendered = () => send("inventoryRendered", { delivery_id: message.delivery_id });
      if (s.generation < inventoryGeneration) { rendered(); return; }
      if (!overview && (inventoryGeneration !== s.generation || fieldSelected !== s.selected)) {
        if (fieldSelected !== s.selected) get("inventory-field").value = "";
        fieldSelected = s.selected; cancelField();
      }
      inventoryGeneration = s.generation; get("inventory-browser").hidden = false;
      const filterKey = JSON.stringify(s.filter);
      if (s.filter && filterKey !== inventoryFilterKey) {
        for (const [id, value] of [["inventory-search", s.filter.query], ["inventory-type", s.filter.types.join(", ")]])
          if (document.activeElement !== get(id)) get(id).value = value;
        if (document.activeElement !== get("inventory-unavailable")) get("inventory-unavailable").checked = s.filter.unavailable;
        inventoryFilterKey = filterKey;
      }
      inventoryFilterControls();
      const page = s.page, summary = s.summary;
      get("inventory-count").textContent = page
        ? `Page ${s.pageNumber ?? 1} · ${page.entries.length} shown of ${page.total_matching.toLocaleString()} ${s.filter?.unavailable ? "unavailable declarations" : "matching names"} · ${summary?.total_entry_names?.toLocaleString() ?? page.total_entry_names?.toLocaleString() ?? "unknown"} total names${page.unavailable_excluded && page.unavailable_entry_names ? ` · ${page.unavailable_entry_names} unavailable declarations excluded` : ""}${!page.entries.length ? s.filter?.unavailable ? ". No unavailable declarations; turn off this mode to search ordinary names." : ". No matches; change or clear the filter." : ""}`
        : s.hidden ? "Hidden overview released its inventory" : "Opening a bounded inventory…";
      get("inventory-age").textContent = s.openedAt ? `Snapshot opened ${new Date(s.openedAt).toLocaleTimeString()} · ${s.expired ? "Expired; Retry to refresh" : "Refresh to include newer declarations"}` : "";
      get("inventory-reason").textContent = s.reason ?? "";
      if (page) get("inventory-count").textContent += s.filter?.unavailable ? " · Unavailable declarations only; name and type filters are not applied"
        : ` · Applied search: ${s.filter?.query || "all names"}; type: ${s.filter?.types?.join(", ") || "all types"}`;
      get("inventory-previous").disabled = s.loading || s.expired || !s.previous;
      get("inventory-next").disabled = s.loading || s.expired || !s.next;
      clearTimeout(inventoryRetryTimer);
      get("inventory-retry").disabled = s.loading || Date.now() < s.retryAt;
      if (!s.loading && s.retryAt > Date.now()) inventoryRetryTimer = setTimeout(() => { get("inventory-retry").disabled = false; }, Math.min(30000, s.retryAt - Date.now()));
      get("inventory-find").disabled = !summary || s.hidden || s.expired;
      get("inventory-rows").replaceChildren(...(page?.entries ?? []).map(r => {
        const tr = el("tr"), inspect = button(r.name === s.selected ? "Selected" : "Inspect", () => send("inventorySelect", { name: r.name, inventory_generation: inventoryGeneration }));
        inspect.disabled = s.loading || s.expired || r.unavailable;
        const cell = el("td"); cell.append(inspect);
        tr.append(el("td", r.name ?? `${r.display_preview}… (unavailable: ${r.reason})`), el("td", r.conflict ? "Conflicting declarations" : r.type ?? "Unavailable"),
          el("td", r.sample_count ?? "Not combined"), el("td", String(r.members.length)), cell); return tr;
      }));
      get("inventory-selection").textContent = s.selected ? `Selected: ${s.selected}` : "No signal selected";
      const details = s.details, rows = [];
      if (details) {
        rows.push(el("p", `Fresh member details${details.counts_grew_since_inventory ? " · samples have grown since the inventory snapshot" : ""}. No live binding is inferred.`));
        for (const d of details.descriptors) {
          const section = el("section"); section.append(el("h4", `Member ${d.member_id}`));
          for (const [name, e] of Object.entries(d.snapshot.entries)) section.append(el("p", `${name} · ${e.type} · ${d.snapshot.sample_counts[name]} samples`), el("pre", e.metadata ?? "No recorded metadata"));
          for (const schema of d.snapshot.schemas) section.append(el("p", `Schema: ${schema.name}`), el("pre", schema.schema ?? schema.error ?? "Unavailable"));
          rows.push(section);
        }
        for (const p of details.schema_problems) rows.push(el("p", `${p.name}: ${p.reason}`));
      } else if (s.selected) rows.push(el("p", "Selection retained; details must be revalidated against the current recording."));
      get("inventory-detail").replaceChildren(...rows);
      const selectedRow = details?.selected_rows?.[0];
      const eligible = !(s.loading || s.expired || s.hidden || !selectedRow?.name || selectedRow.name !== s.selected || selectedRow.conflict || selectedRow.unavailable || !selectedRow.type);
      if (get("inventory-add")) get("inventory-add").disabled = !eligible;
      if (!overview) { fieldEligible = eligible; fieldControls(); }
      if (summary) get("inventory").textContent = `${summary.total_entry_names.toLocaleString()} signal names · ${summary.members.length} member(s) · ${summary.conflicting_entry_names ?? 0} conflicting declaration(s) · Recorded bounds ${summary.start_time_sec ?? "unavailable"}–${summary.end_time_sec ?? "unavailable"} s · Duration ${summary.duration_sec ?? "unavailable"} s · Robot timestamps in seconds · ${recordingClock(summary.clock)} · ${summary.truncated || summary.damaged ? "Incomplete or damaged recording; inspect member details" : "Fixed inventory snapshot"}`;
      if (overview) get("status").textContent = s.loading ? "Recording signals loading…" : s.reason ? "Recording inspection needs attention" : "Recording ready · panel preview available";
      rendered(); return;
    }
    if (message.type === "inventory") {
      inventory = message.inventory;
      if (overview) {
        const recording = inventory.source_kind === "session";
        const clock = recording ? `Robot timestamps in seconds · ${recordingClock(inventory.clock)}` : inventory.clock_basis;
        const members = recording ? ` · ${inventory.members.length} member(s) · ${inventory.entry_conflicts.length} conflicting declaration(s)` : "";
        const state = inventory.truncated || inventory.damaged ? "Incomplete or damaged file: " + (inventory.truncation_message ?? "see reader diagnostics")
          : recording ? "Snapshot of the recording; close and reopen this overview to update" : "Indexed to the recorded file boundary";
        get("inventory").textContent = `${inventory.entries.length} signals · Duration ${inventory.duration_sec ?? "unavailable"} s · ${clock}${members} · ${state}`;
        get("status").textContent = recording ? "Recording inspected · live panels pending" : "Recorded source ready";
      }
      else { signals(); get("source").textContent = inventory.label; get("clock").textContent = inventory.clock_basis; }
      return;
    }
    if (message.type === "lists" && overview) {
      get("unavailable")?.replaceChildren(...(message.lists.unavailable_views ?? []).map(v => el("p", `Unavailable view ${v.view_id}: ${v.reason}`)));
      get("unreadable").replaceChildren(...(message.lists.unreadable ?? []).map(p => el("p", `${p.file ?? p.key}: ${p.reason}`)));
      get("saved-next").hidden = !message.lists.next;
      get("saved-next").onclick = () => send("savedPage", { after: message.lists.next });
      get("saved-first").onclick = () => send("savedPage");
      if (message.lists.listing_incomplete) get("unreadable").append(el("p", "More saved views are available on the next page."));
      for (const [id, views] of [["saved", message.lists.saved_views], ["drafts", message.lists.working_views.filter(v => !v.saved_view_ref)]]) {
        get(id).replaceChildren(...views.map(v => {
          const row = el("div", undefined, "view-row"); row.append(el("span", v.title ?? `Unsaved view ${v.view_id.slice(0, 8)}`));
          if (id === "drafts") row.append(el("p", v.attached_client_id ? "Open in a window" : `Expires ${v.detached_expires_at}`));
          const recording = sourceKind(source?.source_ref) === "session";
          const open = button(id === "saved" ? "Open new view" : "Resume", () => {
            send(id === "saved" ? "openSaved" : "resume", { saved_view_ref: v.saved_view_ref, view_id: v.view_id });
          });
          row.append(open);

          return row;
        }));
        if (!views.length) get(id).append(el("p", id === "saved" ? "No saved views yet" : "No unsaved views"));
      }
      return;
    }
    if (overview) return;
    if (message.type === "fieldSelection") {
      if (message.generation !== generation || message.inventory_generation !== inventoryGeneration
        || message.field_request !== fieldRequest || message.field_path !== get("inventory-field").value) return;
      fieldBusy = message.state === "checking"; get("inventory-field-status").textContent = message.reason; fieldControls(); return;
    }
    if (message.type === "snapshot") {
      if (snapshot && fieldBusy && (message.snapshot.revision !== snapshot.revision || message.generation !== generation)) cancelField();
      const changedQuery = generation !== message.generation;
      snapshot = message.snapshot; generation = message.generation; document.body.dataset.viewId = snapshot.view_id; document.body.dataset.revision = String(snapshot.revision); document.body.dataset.generation = generation;
      get("title").textContent = message.title; get("source").textContent = message.label;
      get("budget").textContent = message.decoded_buffer_budget_bytes ? `Window sample allowance: ${message.decoded_buffer_budget_bytes / 1048576} MiB. Shared by recorded and live views; reload after changing the setting.` : "";
      get("warning").textContent = message.warning ?? ""; get("status").textContent = message.pending ? "Pending server" : snapshot.saved_view_ref ? "Saved view · edits use native Save" : "Draft saved on server · Save view to add it to the library";
      for (const id of ["undo", "redo", "reset"]) get(id).hidden = !!snapshot.saved_view_ref;
      get("save").textContent = snapshot.saved_view_ref ? "Save view" : "Save view…";
      get("live-controls").hidden = !recording(); get("recording-picker").hidden = !recording(); get("picker").hidden = recording();
      for (const id of ["search", "signals", "add", "full"]) get(id).disabled = recording();
      for (const id of ["cursor", "at", "latest"]) get(id).disabled = recording() && analysisPaused;
      pendingServer = message.pending === true; if (!pendingServer) navigationPending = false;
      if (restoredWindowFor !== snapshot.view_id) {
        const state = api.getState?.(); restoredWindowFor = snapshot.view_id;
        const remembered = state?.view_id === snapshot.view_id ? state.following_window_sec : undefined;
        get("live-window").value = Number.isFinite(remembered) && remembered >= 1 && remembered <= 300 ? remembered : 30;
      }
      const range = recording() ? liveFrame?.finalized ? snapshot.configuration.time.recorded : snapshot.configuration.time.live : snapshot.configuration.time.recorded;
      if (range.mode === "follow") { get("live-window").value = range.window_sec; rememberWindow(); }
      if (range.mode === "fixed") { get("start").value = range.start_sec; get("end").value = range.end_sec; }
      if (changedQuery) {
        pendingLiveAck?.(false);
        analysisEpochs = { value: 0, statistics: 0, histogram: 0, scatter: 0, reduction: 0 }; analysisPaused = false; history = false; reductions.clear();
        liveFrame = undefined; data.clear(); values.clear(); bindings.clear(); statistics.clear(); raw.clear(); histograms.clear(); scatters.clear();
        clearSampleDisplays();
      }
      layout(); timeControls(); return;
    }
    if (message.type === "visibility") { visible = message.visible; timeControls(); if (!visible) { pendingLiveAck?.(false); cancelField(); } if (visible) for (const id of panels.keys()) draw(id); return; }
    if (message.type === "liveFrame") {
      const received = performance.now();
      if (message.generation === generation) {
        pendingLiveAck?.(false);
        liveFrame = message.frame; data.clear(); raw.clear(); bindings.clear();
        if (liveFrame) {
          get("live-follow").disabled = liveFrame.finalized; get("full").disabled = !liveFrame.finalized;
          for (const [key, value] of Object.entries(liveFrame.series)) data.set(key, value);
          for (const [key, value] of Object.entries(liveFrame.raw)) raw.set(key, value);
          for (const b of liveFrame.binding.compatibility.bindings) bindings.set(b.requirement_id, b);
          get("clock").textContent = `Displayed prefix ${liveFrame.prefix} · ${liveFrame.finalized ? "Finalized" : "Recording"} · ${liveFrame.clock.display_basis === "held_robot_edge" ? "Held robot edge; waiting for time sync" : recordingClock(liveFrame.clock)} · right edge ${liveFrame.clock.right_edge_sec ?? "unavailable"} s`;
        } else {
          values.clear(); statistics.clear(); histograms.clear(); scatters.clear(); reductions.clear();
          clearSampleDisplays();
          get("clock").textContent = "No retained recording display";
        }
        for (const id of panels.keys()) draw(id); timeControls();
      }
      let acknowledged = false;
      const ack = rendered => {
        if (acknowledged) return;
        acknowledged = true; if (pendingLiveAck === ack) pendingLiveAck = undefined;
        send("liveRendered", { delivery_id: message.delivery_id, rendered, render_elapsed_ms: performance.now() - received });
      };
      // Hidden webviews suspend animation frames. Release immediately on hide/replacement;
      // a delayed callback must neither acknowledge twice nor claim that a frame was painted.
      if (visible && message.generation === generation && message.frame) {
        pendingLiveAck = ack;
        requestAnimationFrame(() => { if (!acknowledged) requestAnimationFrame(() => ack(visible && message.generation === generation)); });
      }
      else ack(false);
      return;
    }
    if (message.type === "analysisClear") {
      if (message.generation === generation) {
        values.clear(); statistics.clear(); histograms.clear(); scatters.clear(); reductions.clear();
        for (const [id, panel] of panels) if (["latest", "statistics", "histogram", "scatter", ...(historical() ? ["time_series"] : [])].includes(snapshot.configuration.panels.find(p => p.panel_id === id)?.type)) {
          charts.get(id)?.destroy(); charts.delete(id); panel.body.replaceChildren(); panel.note.textContent = "No retained analysis"; draw(id);
        }
      }
      send("recordedRendered", { delivery_id: message.delivery_id }); return;
    }
    if (message.type === "recordedClear") {
      if (message.generation === generation) {
        data.clear(); values.clear(); statistics.clear(); histograms.clear(); scatters.clear(); reductions.clear(); raw.clear();
        clearSampleDisplays();
        for (const id of panels.keys()) draw(id);
      }
      send("recordedRendered", { delivery_id: message.delivery_id }); return;
    }
    if (message.generation !== generation) { if (message.delivery_id) send("recordedRendered", { delivery_id: message.delivery_id }); return; }
    if (message.type === "analysisState") {
      analysisEpochs = { value: message.value_epoch, statistics: message.statistics_epoch, histogram: message.histogram_epoch, scatter: message.scatter_epoch, reduction: message.reduction_epoch }; history = message.history === true; analysisPaused = message.paused === true;
      for (const id of ["cursor", "at", "latest"]) get(id).disabled = analysisPaused;
      if (message.clear_values) { values.clear(); for (const spec of snapshot.configuration.panels) if (spec.type === "latest") draw(spec.panel_id); }
      for (const spec of snapshot.configuration.panels) if (["histogram", "scatter", "time_series"].includes(spec.type)) draw(spec.panel_id);
      return;
    }
    if (message.analysis_epoch !== undefined && message.analysis_epoch !== analysisEpochs[message.type]) {
      send("recordedRendered", { delivery_id: message.delivery_id }); return;
    }
    if (message.type === "liveTiming") {
      get("live-timing").textContent = `Host page receipt to render acknowledgement: ${message.timing.receipt_to_ack_ms.toFixed(1)} ms · renderer: ${message.timing.renderer_elapsed_ms.toFixed(1)} ms. Includes message/paint scheduling; not source-to-screen latency.`;
      return;
    }
    if (message.type === "liveState") {
      const state = message.state;
      const health = state.health, acquisition = health?.kind === "ok" ? `${health.health.acquisition_state}: ${health.reason}${health.health.last_value_age_ms === null ? "" : ` · last received value ${(health.health.last_value_age_ms / 1000).toFixed(1)} s ago (quiet signals are normal)`}` : health ? `health unavailable: ${health.reason}` : "Checking acquisition health";
      get("live-status").textContent = `${state.paused ? "Display paused · " : ""}${state.kind}: ${state.reason} · ${acquisition}`;
      get("live-pause").disabled = state.paused; get("live-resume").disabled = !state.paused;
      if (state.binding) {
        for (const b of state.binding.compatibility.bindings) bindings.set(b.requirement_id, b);
        get("binding-status").replaceChildren(...state.binding.compatibility.bindings.map(b => el("p", `${b.entry ?? b.requirement_id}: ${b.state} — ${b.reason}. ${b.warnings.join("; ")}`)));
      }
      return;
    }
    if (message.type === "series") {
      const decoded = Plot.seriesOf(new Uint8Array(message.bytes), message.name);
      if (message.point_limit && decoded.count > message.point_limit) throw new Error("Recorded response exceeds its admitted point limit");
      data.set(message.requirement_id, decoded);
    }
    if (message.type === "value") values.set(message.requirement_id, message.result);
    if (message.type === "reduction") reductions.set(message.requirement_id, message.result);
    if (message.type === "statistics") statistics.set(message.requirement_id, message.result);
    if (message.type === "histogram") histograms.set(message.requirement_id, message.result);
    if (message.type === "scatter") scatters.set(JSON.stringify(message.pair), message.result);
    if (message.type === "raw") raw.set(message.requirement_id, message.result);
    if (message.type === "bindings") {
      for (const binding of message.result.bindings) bindings.set(binding.requirement_id, binding);
      get("binding-status")?.replaceChildren(...message.result.bindings.map(b => el("p", `${b.entry ?? b.selector ?? b.requirement_id}: ${b.state} — ${b.reason}. ${b.basis ?? ""} Unit: ${b.unit?.symbol ?? "unknown"} (${b.unit?.basis ?? "unknown"}). ${b.warnings.join("; ")}`)));
    }
    for (const id of panels.keys()) draw(id);
    if (message.delivery_id) send("recordedRendered", { delivery_id: message.delivery_id });
  });
  window.addEventListener("unload", () => { pendingLiveAck?.(false); clearTimeout(cursorTimer); clearTimeout(inventoryRetryTimer); for (const id of [...panels.keys()]) destroyPanel(id); grid?.destroy(false); });
  send("ready");
})();
