/**
 * The extension host alone talks to the log server. The webview may load bundled code,
 * styles and WebAssembly through VS Code's resource origin; it cannot fetch a remote URL.
 * Perspective creates shadow-root styles and a blob worker sourced from a bundled asset.
 * These are the narrow additions to the plot pane's nonce policy (EXPLORER_PLAN.md §5).
 */

/** What the page needs from the webview: its CSP source and the URIs of its assets. */
export interface PageAssets {
  cspSource: string;
  styleUri: string;
  scriptUri: string;
  /** The plot's assets: uPlot's style and script (vendor/), the Arrow reader, the arithmetic, the plot. */
  plot?: { styleUri: string; scriptUris: string[] };
  /** A random value per page, so only this page's script may run. */
  nonce: string;
  data?: { scriptUri: string; styleUri: string };
}

/** Only extension resources, with WebAssembly enabled when the data pane is bundled. */
export function contentSecurityPolicy(assets: PageAssets): string {
  return [
    "default-src 'none'",
    `style-src ${assets.cspSource}${assets.data ? " 'unsafe-inline'" : ""}`,
    `font-src ${assets.cspSource}`,
    `img-src ${assets.cspSource} data:`,
    `script-src 'nonce-${assets.nonce}'${assets.data ? ` ${assets.cspSource} 'wasm-unsafe-eval'` : ""}`,
    ...(assets.data ? [`connect-src ${assets.cspSource}`, "worker-src blob:"] : []),
  ].join("; ");
}

/** The page. The script fills it from the host's messages (media/explorer.js). */
export function explorerPage(assets: PageAssets): string {
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta http-equiv="Content-Security-Policy" content="${contentSecurityPolicy(assets)}">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
${assets.plot ? `  <link rel="stylesheet" href="${assets.plot.styleUri}">\n` : ""}  <link rel="stylesheet" href="${assets.styleUri}">
${assets.data ? `  <link rel="stylesheet" href="${assets.data.styleUri}">` : ""}
  <title>WPILog Explorer</title>
</head>
<body>
  <header id="header">
    <h1 id="title">WPILog Explorer</h1>
    <div id="path" class="muted"></div>
    <div id="state" class="muted">Starting the server…</div>
    <label id="follow-label" class="hidden"><input id="follow" type="checkbox"> Follow live session</label>
  </header>
  <section id="summary" class="cards hidden">
    <div class="card"><div class="card-label">Start</div><div id="start" class="card-value"></div></div>
    <div class="card"><div class="card-label">End</div><div id="end" class="card-value"></div></div>
    <div class="card"><div class="card-label">Duration</div><div id="duration" class="card-value"></div></div>
    <div class="card"><div class="card-label">Entries</div><div id="count" class="card-value"></div></div>
  </section>
  <div id="warning" class="warning hidden"></div>
  <div id="error" class="error hidden"></div>
  <div class="toolbar"><button id="show-data" type="button">Data view of selected entries</button></div>
  <details id="data-section" data-pane-kind="data" data-vscode-context='{"webviewSection":"data"}' class="hidden" open><summary>Data</summary><div class="data-status muted"></div><perspective-viewer theme="Pro Light"></perspective-viewer></details>
  <section id="plot" data-pane-kind="time_series" data-vscode-context='{"webviewSection":"time_series"}' class="hidden"></section>
  <details id="chart-section" class="hidden" open><summary>Chart</summary><div class="chart-status muted"></div><div class="chart-view"></div></details>
  <details id="field-section" data-pane-kind="field" data-vscode-context='{"webviewSection":"field"}' class="hidden" open>
    <summary>Field</summary>
    <div id="field"></div>
  </details>
  <details id="rev-section" class="hidden">
    <summary>REV logs</summary>
    <div id="rev"></div>
  </details>
  <details id="console-section" data-pane-kind="console" data-vscode-context='{"webviewSection":"console"}' class="hidden" open>
    <summary>Console</summary>
    <div id="console"></div>
  </details>
  <main id="main" class="hidden">
    <section id="entries">
      <div class="toolbar">
        <input id="filter" type="search" placeholder="Filter entries by name" aria-label="Filter entries by name">
        <span id="filter-count" class="muted"></span>
      </div>
      <table id="table">
        <thead><tr><th></th><th>Entry</th><th>Type</th><th class="num">Samples</th></tr></thead>
        <tbody id="rows"></tbody>
      </table>
    </section>
    <aside id="details" class="hidden">
      <div class="toolbar"><h2 id="details-name"></h2><button id="details-close" type="button" title="Close">×</button></div>
      <div id="details-body"></div>
    </aside>
  </main>
${assets.data ? `  <script type="module" nonce="${assets.nonce}" src="${assets.data.scriptUri}"></script>` : ""}
${(assets.plot?.scriptUris ?? []).map((uri) => `  <script nonce="${assets.nonce}" src="${uri}"></script>\n`).join("")}  <script nonce="${assets.nonce}" src="${assets.scriptUri}"></script>
</body>
</html>
`;
}
