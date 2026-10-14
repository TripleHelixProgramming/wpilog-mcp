/** Bundled assets for the Entries sidebar; all log access stays in the extension host. */
export interface EntriesPageAssets {
  cspSource: string;
  styleUri: string;
  scriptUri: string;
  nonce: string;
}

function attribute(value: string): string {
  return value.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

/** A persistent inline filter above a keyboard-accessible, lazily expanded entry tree. */
export function entriesViewPage(assets: EntriesPageAssets): string {
  const policy = `default-src 'none'; style-src ${assets.cspSource}; script-src 'nonce-${assets.nonce}'`;
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta http-equiv="Content-Security-Policy" content="${attribute(policy)}">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <link rel="stylesheet" href="${attribute(assets.styleUri)}">
  <title>Log entries</title>
</head>
<body>
  <div class="toolbar">
    <div class="toolbar-row" role="search" aria-label="Search log entries">
      <div class="filter-box">
        <input id="filter" type="text" placeholder="Filter entries by name…" aria-label="Filter entries by name" aria-controls="entries" autocomplete="off" spellcheck="false">
        <button id="clear" class="icon-button" type="button" title="Clear filter" aria-label="Clear filter" disabled>×</button>
      </div>
      <button id="collapse-all" class="icon-button" type="button" title="Collapse all" aria-label="Collapse all">
        <span class="collapse-icon" aria-hidden="true"></span>
      </button>
    </div>
    <div class="toolbar-row grouping-row">
      <label for="grouping">Group by</label>
      <select id="grouping" aria-controls="entries">
        <option value="path">Path</option>
        <option value="type">Data type</option>
      </select>
    </div>
  </div>
  <div id="status" role="status" aria-live="polite">Open a log to browse its entries.</div>
  <div id="entries" role="tree" aria-label="Log entries" aria-busy="false"></div>
  <script nonce="${attribute(assets.nonce)}" src="${attribute(assets.scriptUri)}"></script>
</body>
</html>
`;
}
