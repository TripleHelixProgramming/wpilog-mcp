// The REV pane (EXPLORER_PLAN.md §4): the signals of the REV logs beside the wpilog, from
// list_revlog_signals, by bus and device, each with its unit, sample count, and how its
// timestamps were put on the robot's clock (the synchronization method, its confidence, and the
// offset), plotted in the same panes as the log's entries. A REV log that could not be
// synchronized is said so, with the server's reason and hint; nothing is plotted on a clock the
// server did not align. The grouping is a pure function, tested under Node (revPane.test.ts).
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else Object.assign(root, api);
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  /**
   * The listing grouped for display: buses in name order, each with its devices in name order,
   * each with its signals in name order, and per bus the synchronization the signals share.
   * A result that is not ok is one note with the server's words.
   */
  function groupSignals(result) {
    const r = result || {};
    if (r.status !== "ok" && r.status !== "partial") {
      return {
        note: r.reason || r.error || "No REV signals",
        hint: r.hint || null,
        buses: [],
        overall: null,
        accuracyMs: null,
      };
    }
    const byBus = new Map();
    for (const s of Array.isArray(r.signals) ? r.signals : []) {
      const bus = s.can_bus || "";
      let b = byBus.get(bus);
      if (!b) {
        b = { bus, sync: { method: s.sync_method, confidence: s.sync_confidence, offsetSeconds: s.offset_seconds, aligned: s.timestamps_aligned !== false }, devices: new Map() };
        byBus.set(bus, b);
      }
      const device = s.device || "";
      let d = b.devices.get(device);
      if (!d) {
        d = { device, signals: [] };
        b.devices.set(device, d);
      }
      d.signals.push({ key: s.key, signal: s.signal || s.key, unit: s.unit || "", sampleCount: s.sample_count || 0 });
    }
    const buses = [...byBus.values()].sort((a, b) => a.bus.localeCompare(b.bus)).map((b) => ({
      bus: b.bus,
      sync: b.sync,
      devices: [...b.devices.values()].sort((a, c) => a.device.localeCompare(c.device)).map((d) => ({
        device: d.device,
        signals: d.signals.sort((a, c) => a.signal.localeCompare(c.signal)),
      })),
    }));
    return {
      note: null,
      hint: null,
      buses,
      overall: r.overall_sync_confidence || null,
      accuracyMs: r._metadata && r._metadata.timing_accuracy_ms ? String(r._metadata.timing_accuracy_ms) : null,
      warnings: Array.isArray(r.warnings) ? r.warnings : [],
    };
  }

  /** A bus's synchronization in a few words: the method, the confidence, the offset. */
  function syncLabel(sync) {
    if (!sync) return "";
    const method = { CROSS_CORRELATION: "cross-correlation", SYSTEM_TIME_ONLY: "wall clock only", USER_PROVIDED: "user offset", FAILED: "not synchronized" }[sync.method] || sync.method || "";
    const parts = [method];
    if (sync.aligned && Number.isFinite(sync.offsetSeconds)) {
      parts.push("offset " + (sync.offsetSeconds >= 0 ? "+" : "") + sync.offsetSeconds.toFixed(3) + " s");
    }
    if (sync.confidence) parts.push(sync.confidence + " confidence");
    return parts.join(", ");
  }

  function el(tag, className, text) {
    const e = document.createElement(tag);
    if (className) e.className = className;
    if (text !== undefined) e.textContent = text;
    return e;
  }

  class RevPane {
    /**
     * @param host {post(message)}: the way to the extension host
     * @param plot the Plot the signals are plotted in
     * @param root the element the pane lives in
     */
    constructor(host, plot, root) {
      this.host = host;
      this.plot = plot;
      this.root = root;
      this.build();
    }

    build() {
      const bar = el("div", "rev-bar");
      this.status = el("span", "muted", "");
      const refresh = el("button", "plot-button", "Refresh");
      refresh.type = "button";
      refresh.title = "List the REV signals again (a synchronization still running finishes in the background)";
      refresh.addEventListener("click", () => this.ask());
      bar.append(refresh, this.status);
      this.list = el("div", "rev-list");
      this.root.append(bar, this.list);
    }

    reset() {
      this.list.replaceChildren();
      this.ask();
    }

    ask() {
      this.status.textContent = "Looking for REV logs and waiting for their synchronization…";
      this.host.post({ type: "rev" });
    }

    /** The host's answer: list_revlog_signals' result, after wait_for_sync. */
    onResult(message) {
      const grouped = groupSignals(message.result);
      this.list.replaceChildren();
      if (grouped.note) {
        this.status.textContent = "";
        this.list.append(el("div", "muted", grouped.note + (grouped.hint ? " " + grouped.hint : "")));
        return;
      }
      const counts = new Map();
      let total = 0;
      for (const bus of grouped.buses) {
        const section = el("div", "rev-bus");
        section.append(el("div", "rev-bus-head", (bus.bus ? "Bus " + bus.bus + ": " : "") + syncLabel(bus.sync)));
        if (!bus.sync.aligned) {
          section.append(el("div", "warning", "This bus's timestamps are on the REV log's own clock: its signals are not plotted until set_revlog_offset gives it an offset (sync_status says why the synchronization failed)."));
        }
        for (const device of bus.devices) {
          const block = el("div", "rev-device");
          block.append(el("div", "rev-device-head", device.device));
          for (const s of device.signals) {
            total++;
            counts.set(s.key, s.sampleCount);
            const row = el("div", "rev-signal");
            const button = el("button", "row-plot", "plot");
            button.type = "button";
            button.disabled = !bus.sync.aligned;
            button.title = bus.sync.aligned ? "Plot " + s.key + " in the active pane, on the robot's clock" : "Not synchronized";
            button.addEventListener("click", () => this.plot.addSeries(s.key));
            row.append(button, el("code", null, s.signal), el("span", "muted", s.unit ? " [" + s.unit + "]" : ""),
              el("span", "muted rev-count", s.sampleCount.toLocaleString("en-US") + " samples"));
            block.append(row);
          }
          section.append(block);
        }
        this.list.append(section);
      }
      this.plot.setEntryCounts(counts);
      this.status.textContent = total + " signals" + (grouped.overall ? ", overall synchronization confidence " + grouped.overall : "")
        + (grouped.accuracyMs ? ", timing accuracy " + grouped.accuracyMs + " ms" : "");
      for (const w of grouped.warnings || []) this.list.append(el("div", "warning", String(w)));
    }
  }

  return { RevPane, groupSignals, syncLabel };
});
