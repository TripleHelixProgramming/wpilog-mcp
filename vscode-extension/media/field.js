// The field view (EXPLORER_PLAN.md §4): the robot's pose over the window, top-down, on an outline
// drawn from the bundled game data's field geometry (its length and width, and the zone depths
// the season's data gives), with no field image, since none is bundled. The pose entry is the
// one the server's signal resolver finds by convention, or the one the person picks from the
// resolver's candidates: the server does not guess, and neither does this view. The samples come
// through the plot's data requests, so the path is drawn from every sample the budget allows.
(function (root) {
  "use strict";

  function el(tag, className, text) {
    const e = document.createElement(tag);
    if (className) e.className = className;
    if (text !== undefined) e.textContent = text;
    return e;
  }

  const css = (name, fallback) => getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback;

  class FieldView {
    /**
     * @param host {post(message)}: the way to the extension host
     * @param plot the Plot: the window, the cursor, and the data requests
     * @param root the element the view lives in
     */
    constructor(host, plot, root) {
      this.host = host;
      this.plot = plot;
      this.root = root;
      this.geometry = null;
      this.season = undefined;
      this.pose = null; // the resolver's robot_pose role
      this.entry = null; // the entry drawn
      this.series = null; // {x, y, heading}: {timestamps, values}
      this.cursorTime = null;
      this.build();
      plot.onViewChanged(() => this.draw());
      plot.onCursor((t) => {
        this.cursorTime = t;
        this.draw();
      });
      window.addEventListener("resize", () => this.draw());
    }

    build() {
      const bar = el("div", "field-bar");
      this.select = el("select");
      this.select.setAttribute("aria-label", "Pose entry");
      this.select.addEventListener("change", () => this.show(this.select.value || null));
      this.basis = el("span", "muted", "");
      bar.append(el("span", null, "Pose entry: "), this.select, this.basis);
      this.canvas = el("canvas", "field-canvas");
      this.canvas.height = 300;
      this.note = el("div", "muted small", "");
      this.root.append(bar, this.canvas, this.note);
    }

    /** A new log: ask the host for the pose role and the field. */
    reset() {
      this.pose = null;
      this.entry = null;
      this.series = null;
      this.geometry = null;
      this.select.replaceChildren();
      this.basis.textContent = "";
      this.note.textContent = "Finding the robot pose…";
      this.host.post({ type: "field" });
      this.draw();
    }

    /** The host's answer: the resolver's role, the season, and the field geometry. */
    onField(message) {
      this.pose = message.pose;
      this.season = message.season;
      const game = message.game;
      this.geometry = game && game.field_geometry ? game.field_geometry : null;
      this.gameNote = game && game.game_name
        ? game.game_name + " " + (this.season || "") + " field, per the bundled game data (" + (game.manual_version || "") + ")"
        : (game && game.error) || "No bundled field geometry for this season";
      this.select.replaceChildren();
      const none = el("option", null, "(none)");
      none.value = "";
      this.select.append(none);
      const candidates = this.pose && Array.isArray(this.pose.candidates) ? this.pose.candidates : [];
      const chosen = this.pose && this.pose.entry ? this.pose.entry : null;
      for (const c of chosen && !candidates.includes(chosen) ? [chosen, ...candidates] : candidates) {
        const option = el("option", null, c);
        option.value = c;
        this.select.append(option);
      }
      if (chosen) {
        this.select.value = chosen;
        this.basis.textContent = "chosen by " + (this.pose.match || "") + ": " + (this.pose.basis || "");
      } else if (candidates.length > 0) {
        this.basis.textContent = "the server does not guess among these; pick the one the robot code logs as its pose";
      } else {
        this.basis.textContent = "no Pose2d entry the resolver recognizes";
      }
      this.note.textContent = this.gameNote;
      this.show(chosen);
    }

    /** Draws an entry's pose: its translation and heading, fetched as field paths. */
    show(entry) {
      this.entry = entry;
      this.series = null;
      this.draw();
      if (!entry) return;
      const want = { x: entry + ".translation.x", y: entry + ".translation.y", heading: entry + ".rotation.value" };
      const fetched = {};
      const all = Object.entries(want).map(([key, name]) =>
        this.plot.fetchSeries(name).then((data) => { fetched[key] = data; }, (e) => { fetched[key] = { error: String(e.message || e) }; }));
      Promise.all(all).then(() => {
        if (this.entry !== entry) return;
        if (!fetched.x || fetched.x.error || !fetched.y || fetched.y.error) {
          this.note.textContent = (fetched.x && fetched.x.error) || (fetched.y && fetched.y.error) || "The pose could not be read";
          return;
        }
        this.series = fetched;
        this.note.textContent = this.gameNote + ". " + fetched.x.count + " poses" + (fetched.x.bucketed ? ", bucketed: each point is a bucket's mean" : "")
          + (fetched.heading && !fetched.heading.error ? "" : "; no heading field");
        this.draw();
      });
    }

    draw() {
      const canvas = this.canvas;
      const width = canvas.clientWidth || this.root.clientWidth || 800;
      if (canvas.width !== width) canvas.width = width;
      const ctx = canvas.getContext("2d");
      const h = canvas.height;
      ctx.clearRect(0, 0, width, h);
      ctx.fillStyle = css("--vscode-editorWidget-background", "#222");
      ctx.fillRect(0, 0, width, h);
      const g = this.geometry;
      const lengthM = g && Number.isFinite(g.field_length_m) ? g.field_length_m : 16.54;
      const widthM = g && Number.isFinite(g.field_width_m) ? g.field_width_m : 8.07;
      const t = PlotMath.fieldTransform(lengthM, widthM, width, h, 10);
      const fg = css("--vscode-foreground", "#ccc");
      const faint = css("--vscode-descriptionForeground", "#888");
      // The outline, the center line, and the alliance zones where the season's data gives them
      ctx.strokeStyle = fg;
      ctx.lineWidth = 1.5;
      ctx.strokeRect(t.x0, t.y0, t.width, t.height);
      ctx.strokeStyle = faint;
      ctx.lineWidth = 1;
      ctx.setLineDash([4, 4]);
      ctx.beginPath();
      ctx.moveTo(t.toX(lengthM / 2), t.y0);
      ctx.lineTo(t.toX(lengthM / 2), t.y0 + t.height);
      ctx.stroke();
      if (g && Number.isFinite(g.alliance_zone_depth_m)) {
        for (const x of [g.alliance_zone_depth_m, lengthM - g.alliance_zone_depth_m]) {
          ctx.beginPath();
          ctx.moveTo(t.toX(x), t.y0);
          ctx.lineTo(t.toX(x), t.y0 + t.height);
          ctx.stroke();
        }
      }
      ctx.setLineDash([]);
      ctx.fillStyle = "rgba(78, 121, 167, 0.12)";
      ctx.fillRect(t.x0, t.y0, t.width / 2, t.height);
      ctx.fillStyle = "rgba(225, 87, 89, 0.12)";
      ctx.fillRect(t.toX(lengthM / 2), t.y0, t.width / 2, t.height);
      ctx.fillStyle = faint;
      ctx.font = "10px " + css("--vscode-font-family", "sans-serif");
      ctx.fillText("blue", t.x0 + 4, t.y0 + 12);
      ctx.fillText("red", t.x0 + t.width - 24, t.y0 + 12);
      ctx.fillText(lengthM.toFixed(2) + " m × " + widthM.toFixed(2) + " m, x along the length, y up", t.x0 + 4, t.y0 + t.height - 4);
      if (!this.series) return;
      // The path over the window, colored from blue (earlier) to orange (later)
      const xs = this.series.x;
      const ys = this.series.y;
      const view = this.plot.view;
      const [lo, hi] = PlotMath.visibleRange(xs.timestamps, view.start, view.end);
      const yIndex = (i) => PlotMath.indexAtOrBefore(ys.timestamps, xs.timestamps[i]);
      const span = Math.max(1e-9, view.end - view.start);
      ctx.lineWidth = 2;
      let previous = null;
      for (let i = lo; i < hi; i++) {
        const j = yIndex(i);
        if (j < 0) continue;
        const x = xs.values[i];
        const y = ys.values[j];
        if (!Number.isFinite(x) || !Number.isFinite(y)) { previous = null; continue; }
        const f = Math.min(1, Math.max(0, (xs.timestamps[i] - view.start) / span));
        const point = [t.toX(x), t.toY(y)];
        if (previous) {
          ctx.strokeStyle = "rgb(" + Math.round(78 + (242 - 78) * f) + "," + Math.round(121 + (142 - 121) * f) + "," + Math.round(167 + (43 - 167) * f) + ")";
          ctx.beginPath();
          ctx.moveTo(previous[0], previous[1]);
          ctx.lineTo(point[0], point[1]);
          ctx.stroke();
        }
        previous = point;
      }
      // The pose at the cursor: a dot with its heading
      if (this.cursorTime !== null) {
        const i = PlotMath.indexAtOrBefore(xs.timestamps, this.cursorTime);
        const j = PlotMath.indexAtOrBefore(ys.timestamps, this.cursorTime);
        if (i >= 0 && j >= 0 && Number.isFinite(xs.values[i]) && Number.isFinite(ys.values[j])) {
          const px = t.toX(xs.values[i]);
          const py = t.toY(ys.values[j]);
          ctx.fillStyle = css("--vscode-focusBorder", "#09f");
          ctx.beginPath();
          ctx.arc(px, py, 5, 0, 2 * Math.PI);
          ctx.fill();
          const heading = this.series.heading && !this.series.heading.error ? this.series.heading : null;
          if (heading) {
            const k = PlotMath.indexAtOrBefore(heading.timestamps, this.cursorTime);
            if (k >= 0 && Number.isFinite(heading.values[k])) {
              const a = heading.values[k];
              ctx.strokeStyle = css("--vscode-focusBorder", "#09f");
              ctx.lineWidth = 2;
              ctx.beginPath();
              ctx.moveTo(px, py);
              ctx.lineTo(px + 14 * Math.cos(a), py - 14 * Math.sin(a));
              ctx.stroke();
            }
          }
          ctx.fillStyle = fg;
          ctx.fillText(xs.values[i].toFixed(2) + ", " + ys.values[j].toFixed(2) + " m at " + this.cursorTime.toFixed(3) + " s", px + 8, py - 8);
        }
      }
    }
  }

  root.FieldView = FieldView;
})(typeof self !== "undefined" ? self : this);
