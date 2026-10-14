export function recordedPage(asset: (name: string) => string, csp: string, nonce: string, overview: boolean): string {
  const scripts = overview ? ["visualization.js"] : ["vendor/gridstack-all.js", "vendor/uPlot.iife.min.js", "arrowStream.js", "plotMath.js", "plot.js", "visualization.js"];
  const styles = ["visualization.css", ...(overview ? [] : ["vendor/gridstack.min.css", "vendor/uPlot.min.css"])];
  const escape = (s: string) => s.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;");
  return `<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-${nonce}'; style-src ${escape(csp)} 'unsafe-inline'; img-src ${escape(csp)} data:;">
    ${styles.map(s => `<link rel="stylesheet" href="${escape(asset(s))}">`).join("")}<title>Recorded visualization preview</title></head>
    <body data-overview="${overview}"><header><h1 id="title">Source overview</h1><p id="source"></p></header>
    <p id="status" role="status" aria-live="polite">Loading…</p><p id="warning" role="alert"></p>
    ${overview ? `<section aria-label="Source details"><h2>Source details</h2><dl id="facts"></dl><p id="inventory">Signal inventory: not requested</p></section>
      <button id="blank">Blank layout</button><button id="refresh">Refresh views</button>
      <div id="unreadable" role="status" aria-live="polite"></div><div class="view-lists"><section><h2>Saved views</h2><div id="saved"></div></section><section><h2>Unsaved views</h2><div id="drafts"></div></section></div>` : `<nav aria-label="Visualization actions"><button id="overview">Overview</button><button id="plot">Add plot</button><button id="values">Add value table</button><button id="save">Save view…</button><button id="duplicate">Duplicate and Open Beside</button><button id="undo">Undo draft</button><button id="redo">Redo draft</button><button id="reset">Reset draft</button></nav>
      <p>Native Split moves this view. Duplicate and Open Beside creates an independent copy.</p>
      <details id="picker"><summary>Add signals</summary><label>Search <input id="search" type="search" placeholder="Filter exact entry names"></label>
      <label>Target panel <select id="target"></select></label><label>Signals <select id="signals" multiple size="6"></select></label><button id="add">Add selected signals</button></details>
      <details class="time-picker"><summary>Recorded time and cursor</summary><div class="time-controls"><label>Start (s) <input id="start" type="number" step="any"></label><label>End (s) <input id="end" type="number" step="any"></label><button id="range">Apply range</button><button id="full">Full recording</button>
      <label>Cursor (s) <input id="cursor" type="number" step="any"></label><button id="at">Read at cursor</button><button id="latest">Latest recorded values</button></div></details>
      <p id="clock"></p><main id="grid" class="grid-stack" aria-label="Visualization panels"></main>`}
    ${scripts.map(s => `<script nonce="${nonce}" src="${escape(asset(s))}"></script>`).join("")}</body></html>`;
}
