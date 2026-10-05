// The explorer editor's script. It draws what the extension host sends and asks the host for
// what it needs; it never opens a connection of its own (EXPLORER_PLAN.md decision 2). Messages
// from the host: loading, log, error, entryInfo, selectEntry, plotEntry, and the plot's data,
// dataError, timeline, statistics. To the host: ready, entryInfo, and the plot's fetch, timeline,
// statistics (see plot.js).
(function () {
  "use strict";
  const vscode = acquireVsCodeApi();
  const plot = typeof Plot === "function" ? new Plot({ post: (m) => vscode.postMessage(m) }, document.getElementById("plot")) : null;

  /** Whether an entry's type can be plotted as it is: a number or a boolean. */
  function plottable(type) {
    return type === "double" || type === "float" || type === "int64" || type === "boolean";
  }

  function plotButton(name, enabled, title) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "row-plot";
    button.textContent = "plot";
    button.title = title || "Plot this entry in the active pane";
    button.disabled = !enabled || !plot;
    button.addEventListener("click", (e) => {
      e.stopPropagation();
      if (plot) plot.addSeries(name);
    });
    return button;
  }
  const byId = (id) => document.getElementById(id);
  const els = {
    title: byId("title"),
    path: byId("path"),
    state: byId("state"),
    summary: byId("summary"),
    start: byId("start"),
    end: byId("end"),
    duration: byId("duration"),
    count: byId("count"),
    warning: byId("warning"),
    error: byId("error"),
    main: byId("main"),
    filter: byId("filter"),
    filterCount: byId("filter-count"),
    rows: byId("rows"),
    details: byId("details"),
    detailsName: byId("details-name"),
    detailsBody: byId("details-body"),
    detailsClose: byId("details-close"),
  };

  let entries = [];
  let selected = null;

  function show(el, visible) {
    el.classList.toggle("hidden", !visible);
  }

  function seconds(value) {
    return typeof value === "number" && Number.isFinite(value) ? value.toFixed(3) + " s" : "";
  }

  function count(value) {
    return typeof value === "number" && Number.isFinite(value) ? value.toLocaleString("en-US") : "";
  }

  function text(tag, content, className) {
    const el = document.createElement(tag);
    el.textContent = content;
    if (className) el.className = className;
    return el;
  }

  function drawRows() {
    const needle = els.filter.value.trim().toLowerCase();
    const shown = needle === "" ? entries : entries.filter((e) => e.name.toLowerCase().includes(needle));
    els.rows.replaceChildren(
      ...shown.map((entry) => {
        const row = document.createElement("tr");
        row.dataset.name = entry.name;
        if (entry.name === selected) row.classList.add("selected");
        const cell = document.createElement("td");
        cell.className = "plot-cell";
        cell.append(plotButton(entry.name, plottable(entry.type),
          plottable(entry.type) ? "Plot this entry in the active pane" : "A struct or array is plotted by its numeric fields: pick one in its details"));
        row.append(
          cell,
          text("td", entry.name, "name"),
          text("td", entry.type, "type"),
          text("td", count(entry.sample_count), "num")
        );
        row.addEventListener("click", () => select(entry.name));
        return row;
      })
    );
    els.filterCount.textContent =
      needle === "" ? "" : shown.length + " of " + entries.length + " entries";
  }

  function select(name) {
    selected = name;
    for (const row of els.rows.children) {
      row.classList.toggle("selected", row.dataset.name === name);
    }
    const row = [...els.rows.children].find((r) => r.dataset.name === name);
    if (row) row.scrollIntoView({ block: "nearest" });
    els.detailsName.textContent = name;
    els.detailsBody.replaceChildren(text("div", "Loading…", "muted"));
    show(els.details, true);
    vscode.postMessage({ type: "entryInfo", name });
  }

  function drawInfo(name, info) {
    if (name !== selected) return;
    const body = document.createDocumentFragment();
    if (info.status === "error" || info.error) {
      body.append(text("div", info.error || "The entry could not be described", "error"));
      els.detailsBody.replaceChildren(body);
      return;
    }
    const dl = document.createElement("dl");
    const add = (label, value) => {
      if (value === undefined || value === null || value === "") return;
      dl.append(text("dt", label), text("dd", String(value)));
    };
    add("Type", info.type);
    add("Samples", count(info.sample_count));
    if (info.non_empty_sample_count !== undefined) add("Non-empty", count(info.non_empty_sample_count));
    if (info.time_range_sec) {
      add("First sample", seconds(info.time_range_sec.start));
      add("Last sample", seconds(info.time_range_sec.end));
    }
    add("Metadata", info.metadata);
    if (info.struct) {
      add("Struct", info.struct.name + (info.struct.is_array ? "[]" : ""));
      add("Schema from", info.struct.source_note || info.struct.source);
      add("Schema", info.struct.schema);
    }
    if (info.decode_problem) {
      add("Decode problem", info.decode_problem.reason + " (" + count(info.decode_problem.failed_records) +
        " of " + count(info.decode_problem.total_records) + " records)");
    }
    body.append(dl);
    if (Array.isArray(info.numeric_leaf_paths) && info.numeric_leaf_paths.length > 0) {
      body.append(text("div", "Numeric fields, as the tools address them:", "muted"));
      const ul = document.createElement("ul");
      ul.className = "paths";
      for (const path of info.numeric_leaf_paths) {
        const li = document.createElement("li");
        li.append(plotButton(name + path, !path.includes("[*]"), path.includes("[*]")
          ? "A [*] path pools every element; plot one element by its index instead" : "Plot this field in the active pane"), text("span", " "), text("code", name + path));
        ul.append(li);
      }
      body.append(ul);
    }
    if (Array.isArray(info.sample_values) && info.sample_values.length > 0) {
      body.append(text("div", "Representative samples (first, middle, last):", "muted"));
      body.append(text("pre", JSON.stringify(info.sample_values, null, 2)));
    }
    if (Array.isArray(info.warnings) && info.warnings.length > 0) {
      body.append(text("div", info.warnings.join("\n"), "warning"));
    }
    els.detailsBody.replaceChildren(body);
  }

  function drawLog(message) {
    const listing = message.listing;
    els.title.textContent = message.name;
    els.path.textContent = message.path;
    els.state.textContent = message.server ? "Served by " + message.server : "";
    const range = listing.time_range_sec || {};
    els.start.textContent = seconds(range.start);
    els.end.textContent = seconds(range.end);
    els.duration.textContent = seconds(range.duration);
    els.count.textContent = count(listing.entry_count);
    show(els.summary, true);
    els.warning.textContent = listing.warning || "";
    show(els.warning, Boolean(listing.warning));
    show(els.error, false);
    entries = Array.isArray(listing.entries) ? listing.entries : [];
    drawRows();
    show(els.main, true);
    if (plot && Number.isFinite(range.start) && Number.isFinite(range.end)) {
      plot.setLog({
        path: message.path,
        name: message.name,
        start: range.start,
        end: range.end,
        entries: new Map(entries.map((e) => [e.name, e.sample_count])),
      });
      show(document.getElementById("plot"), true);
    }
    if (selected && !entries.some((e) => e.name === selected)) {
      selected = null;
      show(els.details, false);
    }
  }

  window.addEventListener("message", (event) => {
    const message = event.data;
    switch (message.type) {
      case "loading":
        els.title.textContent = message.name;
        els.path.textContent = message.path;
        els.state.textContent = message.text || "Loading…";
        show(els.error, false);
        break;
      case "log":
        drawLog(message);
        break;
      case "error":
        els.title.textContent = message.name || els.title.textContent;
        els.path.textContent = message.path || els.path.textContent;
        els.state.textContent = "";
        els.error.textContent = message.message + (message.hint ? "\n" + message.hint : "");
        show(els.error, true);
        break;
      case "entryInfo":
        drawInfo(message.name, message.info);
        break;
      case "selectEntry":
        if (entries.some((e) => e.name === message.name)) select(message.name);
        break;
      case "plotEntry":
        if (plot) plot.addSeries(message.name);
        break;
      case "data":
        if (plot) plot.onData(message);
        break;
      case "dataError":
        if (plot) plot.onDataError(message);
        break;
      case "timeline":
        if (plot) plot.onTimeline(message);
        break;
      case "statistics":
        if (plot) plot.onStatistics(message);
        break;
      default:
        break;
    }
  });

  els.filter.addEventListener("input", drawRows);
  els.detailsClose.addEventListener("click", () => {
    selected = null;
    show(els.details, false);
    drawRows();
  });
  vscode.postMessage({ type: "ready" });
})();
