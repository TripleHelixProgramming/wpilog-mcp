export function recordedPage(asset: (name: string) => string, csp: string, nonce: string, overview: boolean): string {
  const scripts = overview ? ["visualization.js"] : ["vendor/gridstack-all.js", "vendor/uPlot.iife.min.js", "arrowStream.js", "plotMath.js", "plot.js", "visualization.js"];
  const styles = ["visualization.css", ...(overview ? [] : ["vendor/gridstack.min.css", "vendor/uPlot.min.css"])];
  const escape = (s: string) => s.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;");
  return `<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-${nonce}'; style-src ${escape(csp)} 'unsafe-inline'; img-src ${escape(csp)} data:;">
    ${styles.map(s => `<link rel="stylesheet" href="${escape(asset(s))}">`).join("")}<title>Recorded visualization preview</title></head>
    <body data-overview="${overview}"><header><h1 id="title">Source overview</h1><p id="source"></p></header>
    <p id="status" role="status" aria-live="polite">Loading…</p><p id="warning" role="alert"></p>
    <details id="template-picker"><summary>Reusable templates</summary><label>Search templates <input id="template-search" type="search"></label><button id="template-find">Search</button>
    <label>Built-in and store templates <select id="templates" size="4"></select></label><p id="template-status" role="status"></p><div id="template-problems"></div>
    <button id="template-open">Open new view</button>${overview ? "" : `<button id="template-replace">Replace this layout…</button><button id="template-save">Save template…</button><button id="template-update">Update selected template…</button>`}
    <button id="template-export">Export selected template…</button><button id="template-import">Import template…</button></details>
    ${overview ? `<section aria-label="Source details"><h2>Source details</h2><dl id="facts"></dl><p id="inventory">Signal inventory: not requested</p></section>
      <section id="inventory-browser" hidden aria-label="Recording signals"><h2>Recording signals</h2>
      <p>Search literal names and filter by an exact declared type. Counts describe this snapshot; Inspect reads current member details.</p>
      <label>Search signal names <input id="inventory-search" type="search" maxlength="256"></label>
      <label>Exact type <input id="inventory-type" type="text" maxlength="256" placeholder="For example, double or struct:Pose2d"></label>
      <label><input id="inventory-unavailable" type="checkbox"> Show unavailable declarations only</label><button id="inventory-find">Apply filter</button>
      <p id="inventory-count" role="status" aria-live="polite"></p><p id="inventory-age"></p><p id="inventory-reason" role="alert"></p>
      <nav aria-label="Signal pages"><button id="inventory-previous">Previous</button><button id="inventory-next">Next</button><button id="inventory-retry">Refresh signals / Retry</button></nav>
      <div class="inventory-table" tabindex="0" aria-label="Signal rows"><table><thead><tr><th>Signal</th><th>Declared type</th><th>Samples</th><th>Members</th><th>Details</th></tr></thead><tbody id="inventory-rows"></tbody></table></div>
      <section aria-label="Selected signal"><h3 id="inventory-selection">No signal selected</h3><div id="inventory-detail"></div></section></section>
      <button id="blank">Blank layout</button><button id="refresh">Refresh views</button>
      <div id="unavailable" role="status"></div><div id="unreadable" role="status" aria-live="polite"></div><div class="view-lists"><section><h2>Saved views</h2><div id="saved"></div></section><section><h2>Unsaved views</h2><div id="drafts"></div></section></div>` : `<nav aria-label="Visualization actions"><button id="overview">Overview</button><button id="plot">Add plot</button><button id="values">Add value table</button><button id="statistics">Add statistics</button><button id="raw">Add raw table</button><button id="save">Save view…</button><button id="duplicate">Duplicate and Open Beside</button><button id="undo">Undo draft</button><button id="redo">Redo draft</button><button id="reset">Reset draft</button></nav>
      <p>Native Split moves this view. Duplicate and Open Beside creates an independent copy.</p>
      <details id="picker"><summary>Add signals</summary><label>Search <input id="search" type="search" placeholder="Filter exact entry names"></label>
      <label>Target panel <select id="target"></select></label><label>Signals <select id="signals" multiple size="6"></select></label><button id="add">Add selected signals</button></details>
      <details class="time-picker"><summary>Recorded time and cursor</summary><div class="time-controls"><label>Start (s) <input id="start" type="number" step="any"></label><label>End (s) <input id="end" type="number" step="any"></label><button id="range">Apply range</button><button id="full">Full recording</button>
      <label>Cursor (s) <input id="cursor" type="number" step="any"></label><button id="at">Read at cursor</button><button id="latest">Latest recorded values</button></div></details>
      <p id="clock"></p><details><summary>Signal bindings and units</summary><div id="binding-status" role="status"></div></details><main id="grid" class="grid-stack" aria-label="Visualization panels"></main>`}
    ${scripts.map(s => `<script nonce="${nonce}" src="${escape(asset(s))}"></script>`).join("")}</body></html>`;
}
