// The console pane (EXPLORER_PLAN.md §4): the log's text from search_strings, a pattern and a
// level filter, repeats collapsed as the server collapses them, each match with its time. A click
// on a line moves the plot's cursor to it, and the matches in the window are marked on the
// timeline. The pane lists what the server lists and nothing else: no line is prioritized or
// dropped here, and the counts are the server's totals.
(function (root) {
  "use strict";

  function el(tag, className, text) {
    const e = document.createElement(tag);
    if (className) e.className = className;
    if (text !== undefined) e.textContent = text;
    return e;
  }

  function seconds(t) {
    return Number.isFinite(t) ? t.toFixed(3) : "";
  }

  const LEVEL_COLORS = { error: "#e15759", warning: "#edc948", info: "#76b7b2" };

  class ConsolePane {
    /**
     * @param host {post(message)}: the way to the extension host
     * @param plot the Plot, whose window the pane follows and whose cursor a click moves
     * @param root the element the pane lives in
     */
    constructor(host, plot, root) {
      this.host = host;
      this.plot = plot;
      this.root = root;
      this.request = 0;
      this.result = null;
      this.timer = null;
      this.build();
      plot.onViewChanged((view) => {
        if (this.windowOnly.checked) this.schedule();
      });
    }

    build() {
      const bar = el("div", "console-bar");
      this.pattern = el("input");
      this.pattern.type = "search";
      this.pattern.placeholder = "Pattern (substring, case-insensitive)";
      this.pattern.setAttribute("aria-label", "Console pattern");
      this.level = el("select");
      for (const level of ["any", "error", "warning", "info"]) {
        const option = el("option", null, level === "any" ? "any level" : level);
        option.value = level;
        this.level.append(option);
      }
      this.windowOnly = el("input");
      this.windowOnly.type = "checkbox";
      this.windowOnly.checked = true;
      const windowLabel = el("label", "console-window");
      windowLabel.append(this.windowOnly, el("span", null, " in the visible window"));
      this.count = el("span", "muted", "");
      bar.append(this.pattern, this.level, windowLabel, this.count);
      this.list = el("div", "console-list");
      this.root.append(bar, this.list);
      this.pattern.addEventListener("input", () => this.schedule());
      this.level.addEventListener("change", () => this.schedule(true));
      this.windowOnly.addEventListener("change", () => this.schedule(true));
    }

    /** A new log: the filters stay, the lines go. */
    reset() {
      this.result = null;
      this.list.replaceChildren();
      this.count.textContent = "";
      this.plot.setMarks([]);
      this.schedule(true);
    }

    schedule(now) {
      clearTimeout(this.timer);
      this.timer = setTimeout(() => this.ask(), now ? 0 : 300);
    }

    ask() {
      if (!this.plot.log) return;
      const requestId = ++this.request;
      const message = { type: "console", requestId, pattern: this.pattern.value, level: this.level.value };
      if (this.windowOnly.checked) {
        message.startTime = this.plot.view.start;
        message.endTime = this.plot.view.end;
      }
      this.count.textContent = "searching…";
      this.host.post(message);
    }

    /** The host's answer: search_strings' result. */
    onResult(message) {
      if (message.requestId !== this.request) return;
      const r = message.result || {};
      this.result = r;
      this.list.replaceChildren();
      if (r.status === "error") {
        this.count.textContent = "";
        this.list.append(el("div", "error", r.error || "The search failed"));
        this.plot.setMarks([]);
        return;
      }
      const matches = Array.isArray(r.matches) ? r.matches : [];
      const total = r.total_after_collapse !== undefined ? r.total_after_collapse : r.total_matches;
      if (r.status === "no_match" || matches.length === 0) {
        this.count.textContent = r.reason || "No matching text";
        this.plot.setMarks([]);
        return;
      }
      this.count.textContent = (r.has_more ? "first " + matches.length + " of " + total : matches.length) + " matches"
        + (r.total_after_collapse !== undefined && r.total_after_collapse !== r.total_matches ? " (" + r.total_matches + " before collapsing repeats)" : "");
      for (const m of matches) {
        const line = el("div", "console-line");
        line.dataset.time = m.timestamp_sec;
        const time = el("span", "console-time", seconds(m.timestamp_sec));
        const level = el("span", "console-level", m.level || "");
        if (m.level && LEVEL_COLORS[m.level]) level.style.color = LEVEL_COLORS[m.level];
        const entry = el("span", "console-entry muted", shortEntry(m.entry));
        entry.title = m.entry;
        const text = el("span", "console-text", m.line || m.value || "");
        line.append(time, level, entry, text);
        if (m.repeat_count > 1) {
          const repeat = el("span", "console-repeat", "×" + m.repeat_count);
          repeat.title = "Repeated " + m.repeat_count + " times, through " + seconds(m.last_timestamp_sec) + " s";
          line.append(repeat);
        }
        if (m.source === "alert") {
          const span = el("span", "console-alert muted",
            m.active_at_log_end ? "active at the log's end" : "cleared at " + seconds(m.end_sec) + " s");
          line.append(span);
        }
        if (m.value && m.line && m.value !== m.line) line.title = m.value;
        line.addEventListener("click", () => {
          for (const other of this.list.children) other.classList.toggle("selected", other === line);
          this.plot.markTime(m.timestamp_sec);
        });
        this.list.append(line);
      }
      this.plot.setMarks(matches.map((m) => ({ time: m.timestamp_sec, level: m.level || "info" })));
    }
  }

  function shortEntry(name) {
    if (!name) return "";
    const parts = name.split("/").filter((p) => p !== "");
    return parts.length > 2 ? "…/" + parts.slice(-2).join("/") : name;
  }

  root.ConsolePane = ConsolePane;
})(typeof self !== "undefined" ? self : this);
