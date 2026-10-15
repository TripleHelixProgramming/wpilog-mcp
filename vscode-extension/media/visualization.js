/* Recorded preview presentation. Identity, revisions, samples and cursor values belong to the host/server. */
(function () {
  "use strict";
  const api = acquireVsCodeApi(), overview = document.body.dataset.overview === "true";
  const get = id => document.getElementById(id);
  const el = (tag, text, className) => { const e = document.createElement(tag); if (text !== undefined) e.textContent = text; if (className) e.className = className; return e; };
  let snapshot, inventory, generation = "", source, visible = true, cursorTimer;
  const charts = new Map(), panels = new Map(), data = new Map(), values = new Map();
  const cursorEvents = new WeakMap(), bindings = new Map(), statistics = new Map(), raw = new Map();
  let templates = [], selectedTemplate;
  let grid, applying = false;
  const send = (type, extra = {}) => api.postMessage({ type, view_id: snapshot?.view_id, revision: snapshot?.revision, generation, ...extra });
  const button = (label, action) => { const b = el("button", label); b.onclick = action; return b; };
  const action = (id, type, extra) => { if (get(id)) get(id).onclick = () => send(type, extra); };
  function cursorAt(at, origin) {
    if (at !== undefined && !Number.isFinite(at)) return;
    if (snapshot?.configuration.links.cursor) for (const chart of charts.values()) if (chart !== origin)
      chart.setCursor({ left: at === undefined ? -10 : chart.valToPos(at, "x"), top: at === undefined ? -10 : 0 }, false);
    get("cursor").value = at === undefined ? "" : String(at); send("cursor", { at });
  }
  function facts(summary) {
    source = summary; const recording = summary.source_ref.kind === "session";
    get("title").textContent = summary.label; get("source").textContent = recording ? "Recording · inspection preview" : "Recorded source · local preview";
    for (const id of ["blank", "template-open"]) if (get(id)) get(id).disabled = recording;
    get("warning").textContent = recording ? "Live visualization panels are not available yet. Use the existing Explorer to follow this source." : "";
    const catalog = summary.catalog, rows = [["Source", summary.label], ["Identity", recording ? "Anchored recording" : summary.source_ref.kind === "recorded" ? "Managed recorded content" : "Local file fingerprint"]];
    rows.push(["Robot", catalog?.robot?.name ?? catalog?.robot?.serial_number ?? "Unavailable: not recorded"]);
    rows.push(["Session", catalog?.session?.started_at ?? "Unavailable: no recorded session"]);
    rows.push(["Match", catalog?.session?.match_number == null ? "Unavailable: not recorded" : `${catalog.session.match_type ?? ""} ${catalog.session.match_number}`]);
    rows.push(["File size", `${summary.size_bytes} bytes`]);
    get("facts").replaceChildren(...rows.flatMap(([a, b]) => [el("dt", a), el("dd", String(b))]));
  }
  function templateAction(type) {
    if (source?.source_ref.kind === "session" && ["templateCheck", "applyTemplate", "replaceTemplate"].includes(type)) {
      get("template-status").textContent = "Live template binding is not available in this inspection preview."; return;
    }
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
  if (get("template-find")) get("template-find").onclick = () => send("templateSearch", { query: get("template-search").value });
  if (get("templates")) get("templates").onchange = () => {
    selectedTemplate = templates[Number(get("templates").value)]?.template_ref;
    if (selectedTemplate) { get("template-status").textContent = "Checking compatibility…"; templateAction("templateCheck"); }
  };
  for (const [id, type] of [["template-open", "applyTemplate"], ["template-replace", "replaceTemplate"], ["template-save", "saveTemplate"], ["template-update", "updateTemplate"], ["template-export", "exportTemplate"], ["template-import", "importTemplate"]]) if (get(id)) get(id).onclick = () => templateAction(type);
  if (overview) {
    action("blank", "blank"); action("refresh", "refresh");
  } else {
    grid = GridStack.init({ column: 12, cellHeight: 64, margin: 6, float: true, animate: false,
      handle: ".panel-header", draggable: { cancel: "input,button,select" } }, get("grid"));
    grid.on("change", () => {
      if (!applying && snapshot) send("geometry", { items: grid.save(false).map(w => ({ panel_id: w.id, x: w.x, y: w.y, w: w.w, h: w.h })) });
    });
    for (const [id, type] of [["overview", "overview"], ["plot", "addPanel"], ["values", "addPanel"], ["statistics", "addPanel"], ["raw", "addPanel"], ["save", "save"], ["duplicate", "duplicate"], ["undo", "undo"], ["redo", "redo"], ["reset", "reset"]]) action(id, type, ["plot", "values", "statistics", "raw"].includes(id) ? { panel_type: id === "plot" ? "time_series" : id === "values" ? "latest" : id } : {});
    get("search").oninput = signals;
    get("target").onchange = () => send("target", { panel_id: get("target").value });
    get("add").onclick = () => send("addSignals", { panel_id: get("target").value, names: [...get("signals").selectedOptions].map(o => o.value) });
    get("range").onclick = () => send("range", { start: Number(get("start").value), end: Number(get("end").value) }); action("full", "full");
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
  function draw(id) {
    const panel = panels.get(id), spec = snapshot?.configuration.panels.find(p => p.panel_id === id);
    if (!panel || !spec || !visible || panel.body.clientWidth <= 0 || panel.body.clientHeight <= 0) return;
    if (spec.type !== "time_series") { charts.get(id)?.destroy(); charts.delete(id); }
    if (spec.type === "statistics" || spec.type === "raw") {
      const content = el("div");
      for (const series of spec.series) {
        const r = snapshot.configuration.requirements.find(r => r.id === series.requirement_id), binding = bindings.get(r.id);
        const result = (spec.type === "statistics" ? statistics : raw).get(r.id);
        content.append(el("h3", (binding?.entry ?? r.entry ?? r.role) + (r.field_path ?? "")));
        if (binding?.state === "unresolved") { content.append(el("p", binding.reason)); continue; }
        if (!result) { content.append(el("p", "Loading…")); continue; }
        if (result.status === "error") { content.append(el("p", result.error)); continue; }
        const table = el("table"); table.className = spec.type === "raw" ? "raw-records" : "recorded-statistics";
        const head = el("tr");
        if (spec.type === "statistics") {
          for (const name of ["Measurement", "Value"]) head.append(el("th", name)); table.append(head);
          for (const key of ["count", "min", "max", "mean", "median", "std_dev", "non_finite_count"]) if (key in result) {
            const row = el("tr"); row.append(el("td", key), el("td", result[key] == null ? "Unavailable" : String(result[key]))); table.append(row);
          }
          content.append(table, el("p", JSON.stringify({ data_quality: result.data_quality, inputs: result.inputs, skipped: result.skipped })));
        } else {
          for (const name of ["Ordinal", "Timestamp (µs)", "Value"]) head.append(el("th", name)); table.append(head);
          for (const sample of result.samples) { const row = el("tr"); row.append(el("td", String(sample.ordinal)), el("td", sample.timestamp_us), el("td", display(sample.value))); table.append(row); }
          content.append(table, el("p", `${result.records_visited} visited; next ordinal ${result.next_ordinal} of ${result.total_records}. ${result.complete ? "End of entry." : "More records available."} ${result.problems.length} decode/projection problems.`));
          content.append(button("First page", () => send("rawPage", { requirement_id: r.id, first_ordinal: 0 })));
          if (!result.complete) content.append(button("Next page", () => send("rawPage", { requirement_id: r.id, first_ordinal: result.next_ordinal })));
        }
      }
      panel.body.replaceChildren(content); panel.note.textContent = spec.type === "statistics" ? "Server statistics over original samples in the selected time interval; plots are not inputs." : "Physical record order; bounded pages replace the previous page. Timestamps do not identify records.";
    } else if (spec.type === "latest") {
      const table = el("table"), head = el("tr"); table.className = "recorded-values"; for (const label of ["Signal", "Recorded value", "Timestamp (µs)"]) head.append(el("th", label)); table.append(head);
      for (const series of spec.series) {
        const requirement = snapshot.configuration.requirements.find(r => r.id === series.requirement_id), result = values.get(series.requirement_id);
        const row = el("tr"); row.append(el("td", (bindings.get(requirement.id)?.entry ?? requirement.entry ?? requirement.role) + (requirement.field_path ?? "")), el("td", bindings.get(requirement.id)?.state === "unresolved" ? bindings.get(requirement.id).reason : result ? display(result.value) : "Loading…"), el("td", result?.timestamp_us ?? "Unavailable")); table.append(row);
      }
      panel.body.replaceChildren(table);
      const problems = spec.series.reduce((n, s) => n + (values.get(s.requirement_id)?.problem_count ?? 0), 0);
      panel.note.textContent = `Last logged value at or before cursor; no interpolation.${problems ? ` ${problems} records could not be decoded in the selected signals.` : ""}`;
    } else {
      const definitions = [{ label: "Robot time (s)" }], tables = [], bands = [];
      for (const s of spec.series) {
        const item = data.get(s.requirement_id); if (!item) continue;
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
      if (!tables.length) { panel.note.textContent = spec.series.length ? "Loading recorded samples…" : "Add signals to this plot"; return; }
      const points = uPlot.join(tables), signature = JSON.stringify(definitions);
      let chart = charts.get(id);
      if (!chart || chart.previewSignature !== signature) {
        chart?.destroy(); panel.body.replaceChildren();
        chart = new uPlot({ width: panel.body.clientWidth, height: Math.max(80, panel.body.clientHeight - 4), series: definitions, bands,
          axes: [0, 1].map(() => ({ stroke: getComputedStyle(document.body).color, grid: { stroke: "rgba(128,128,128,0.25)" } })),
          scales: { x: { time: false } }, legend: { show: spec.display.legend },
          hooks: { setCursor: [u => {
            // uPlot can recalculate its cursor in a later microtask after setData/setSize.
            // Only a new input event is a gesture; a redraw of the same event is not a query.
            const event = u.cursor.event;
            if (!event || cursorEvents.get(u) === event) return;
            cursorEvents.set(u, event);
            if (!snapshot.configuration.links.cursor || u.cursor.left == null || u.cursor.left < 0 || u.cursor.top < 0) return;
            const at = u.posToVal(u.cursor.left, "x"); clearTimeout(cursorTimer);
            cursorTimer = setTimeout(() => cursorAt(at, u), 150);
          }] } }, points, panel.body);
        chart.previewSignature = signature; charts.set(id, chart);
      } else { chart.setData(points); chart.setSize({ width: panel.body.clientWidth, height: Math.max(80, panel.body.clientHeight - 4) }); }
      panel.note.textContent = [...data.values()].some(d => d.bucketed) ? "Server min/max bands and means; zoom with Start/End for detail." : "Recorded samples · robot time in seconds";
    }
    send("rendered", { panels: panels.size, plots: charts.size, rows: [...panels.values()].reduce((n, p) => n + p.body.querySelectorAll(".recorded-values tr").length, 0), width: panel.body.clientWidth, height: panel.body.clientHeight,
      axis_colors: [...charts.values()].map(c => c.axes[0].stroke(c, 0)), foreground: getComputedStyle(document.body).color });
  }
  window.addEventListener("message", event => {
    const message = event.data;
    if (message.type === "templates") {
      templates = message.catalog.templates; selectedTemplate = undefined;
      get("templates")?.replaceChildren(...templates.map((item, i) => {
        const ref = item.template_ref, option = el("option", `${item.template.name} · ${ref.origin === "bundled" ? "Built-in" : item.store_label ?? ref.store_id} · r${ref.revision} · ${ref.template_id.slice(0, 8)}`); option.value = String(i); return option;
      }));
      if (get("template-status")) get("template-status").textContent = "Select a template to check compatibility before opening.";
      get("template-problems")?.replaceChildren(...(message.catalog.unreadable ?? []).map(p => el("p", `${p.file}: ${p.reason}`)));
      if (message.catalog.listing_incomplete) get("template-problems")?.append(el("p", "Template listing is incomplete at its metadata budget."));
      return;
    }
    if (message.type === "templateCompatibility") {
      if (JSON.stringify(message.template_ref) !== JSON.stringify(selectedTemplate)) return;
      get("template-status").textContent = `${message.result.compatibility}: ` + message.result.bindings.map(b => `${b.entry ?? b.selector ?? b.requirement_id}: ${b.reason}; ${b.basis ?? ""} ${b.warnings.join("; ")}`).join(" | "); return;
    }
    if (message.type === "warning") { get("warning").textContent = message.message; return; }
    if (message.type === "summary" && overview) { facts(message.summary); get("status").textContent = "Source details available; signal inventory loading…"; return; }
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
      get("unreadable").replaceChildren(...(message.lists.unreadable ?? []).map(p => el("p", `${p.file}: ${p.reason} (store ${p.store_id})`)));
      if (message.lists.listing_incomplete) get("unreadable").append(el("p", "Saved-view listing is incomplete at its metadata budget. Known saved views can still be opened directly."));
      for (const [id, views] of [["saved", message.lists.saved_views], ["drafts", message.lists.working_views.filter(v => !v.saved_view_ref)]]) {
        get(id).replaceChildren(...views.map(v => {
          const row = el("div", undefined, "view-row"); row.append(el("span", v.title ?? `Unsaved view ${v.view_id.slice(0, 8)}`));
          if (id === "drafts") row.append(el("p", v.attached_client_id ? "Open in a window" : `Expires ${v.detached_expires_at}`));
          row.append(button(id === "saved" ? "Open new view" : "Resume", () => send(id === "saved" ? "openSaved" : "resume", { saved_view_id: v.saved_view_id, store_id: v.store_id, view_id: v.view_id }))); return row;
        }));
        if (!views.length) get(id).append(el("p", id === "saved" ? "No saved views yet" : "No unsaved views"));
      }
      return;
    }
    if (overview) return;
    if (message.type === "snapshot") {
      const changedQuery = generation !== message.generation;
      snapshot = message.snapshot; generation = message.generation; document.body.dataset.viewId = snapshot.view_id;
      get("title").textContent = message.title; get("source").textContent = message.label;
      get("warning").textContent = message.warning ?? ""; get("status").textContent = message.pending ? "Pending server" : snapshot.saved_view_ref ? "Saved view · edits use native Save" : "Draft saved on server · Save view to choose a destination";
      for (const id of ["undo", "redo", "reset"]) get(id).hidden = !!snapshot.saved_view_ref;
      get("save").textContent = snapshot.saved_view_ref ? "Save view" : "Save view…";
      const range = snapshot.configuration.time.recorded;
      if (range.mode === "fixed") { get("start").value = range.start_sec; get("end").value = range.end_sec; }
      if (changedQuery) {
        data.clear(); values.clear(); bindings.clear(); statistics.clear(); raw.clear();
        for (const chart of charts.values()) chart.setData(chart.series.map(() => []));
      }
      layout(); return;
    }
    if (message.type === "visibility") { visible = message.visible; if (visible) for (const id of panels.keys()) draw(id); return; }
    if (message.generation !== generation) return;
    if (message.type === "series") { data.set(message.requirement_id, Plot.seriesOf(new Uint8Array(message.bytes), message.name)); }
    if (message.type === "value") values.set(message.requirement_id, message.result);
    if (message.type === "statistics") statistics.set(message.requirement_id, message.result);
    if (message.type === "raw") raw.set(message.requirement_id, message.result);
    if (message.type === "bindings") {
      for (const binding of message.result.bindings) bindings.set(binding.requirement_id, binding);
      get("binding-status")?.replaceChildren(...message.result.bindings.map(b => el("p", `${b.entry ?? b.selector ?? b.requirement_id}: ${b.state} — ${b.reason}. ${b.basis ?? ""} Unit: ${b.unit?.symbol ?? "unknown"} (${b.unit?.basis ?? "unknown"}). ${b.warnings.join("; ")}`)));
    }
    for (const id of panels.keys()) draw(id);
  });
  window.addEventListener("unload", () => { clearTimeout(cursorTimer); for (const id of [...panels.keys()]) destroyPanel(id); grid?.destroy(false); });
  send("ready");
})();
