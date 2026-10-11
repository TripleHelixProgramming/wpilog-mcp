# Explorer: remote sessions and mirrors

> **Experimental:** the pit server has not had real-world testing. These documents are
> retained for development and controlled shop evaluation, not as a recommendation for team use.
> Automated tests and simulation do not establish reliability on a real robot.

This preserves the experimental portion of the [explorer plan](../EXPLORER_PLAN.md).
Its section and milestone numbers are retained for the implementation record.

### 8. Pit server and live sessions

With `wpilog-mcp.pitServerUrl` set (the pit server plan's setting), the Logs view lists the pit server's sessions and the editor opens one by path through the pit server's tools, as it opens a local log through the local server's. An open session has a **follow** toggle: the window tracks the latest time, and the data client fetches each visible series from the last timestamp it holds, once a second, from the data endpoint, appending to the typed arrays in the webview. The console pane pages `search_strings` from the last completed boundary without collapsing repeats and retains the latest 500 matches. Follow runs while the editor is visible.

Mirrored sessions (pit server plan §11) open from the mirror when the pit server is unreachable, and the editor says which copy it is reading. Nothing here subscribes to the robot: the pit server is the one listener, by its own decision 6.


## Remote viewer decisions

It reads REV logs beside the robot's log, on the robot's clock, as the server already synchronizes them. With a pit server on the team's network (see the pit server plan), it lists the team's sessions, follows a live session as it is recorded, and shows which sessions are mirrored on this laptop for the trip home.

The webview draws results supplied by the extension host and opens no network connection. The shared server supplies both tool results and sample streams, so a log loaded for the viewer is loaded for the assistant. A configured pit-server URL supplies a separate HTTP connection, and the local server maintains its offline mirror.

2. **The webview never touches the network.** The extension host is the client. It starts the shared standalone server and registers the window’s directories by lease; the viewer and assistants reach that server over HTTP. A configured pit server has a separate HTTP client, with no local directory or key lease.

  - With a pit server URL set, a second root lists its sessions from `list_available_logs`, newest first, with the robot's name, whether it is open, and whether the mirror holds it (pit server plan §11).

  - Right-click: open, reveal in the file explorer, copy path, pin or unpin on the pit server.

  - a **data view**, a `<perspective-viewer>` fed the same Arrow bytes the panes draw from. It is a grid of the selected entries' samples with filtering, sorting, grouping, and pivots, Perspective's own charts for a quick scatter or histogram, and export to CSV. All of that is the library as it comes, for the exploration a person does before deciding what to plot. In follow mode (§8) each batch is appended to its table, so the grid tails the session. The view is the library's, so its charts carry none of the panes' conventions (phases, steps, the shared cursor); the panes stay the place for a time series.

- **Status**: the viewer's server state in the status bar (starting, ready, restarting), and for a pit server its reachability and the mirror's age, as the pit server plan's extension section specifies.

- **Who reads it.** The viewer, through its data client. An agent with shell access, by `curl` against the URL that `get_server_guide` reports under `data_endpoint` whenever the transport is HTTP, which gives a script every sample of an entry in one request. On a stdio server there is no endpoint, and the CSV export tool stays the way. A notebook or a script: `pyarrow.ipc.open_stream` on the response, or `polars.read_ipc_stream`, gives a data frame in one line, with the timestamps as timestamps and the struct fields as columns. A dashboard: this is the query endpoint the pit server plan leaves for Grafana (its §12), with `format=csv` or a `json` format added when Grafana's data source plugin asks for one.

- **Security.** The endpoint reads; it writes nothing. It is behind the `Origin` check, so no web page can fetch it, and behind the path validator, so it serves only files in the configured log directories. On the pit server it is under decision 7 of that plan, behind a team's proxy when they add one.

- **A real VS Code**: the `@vscode/test-electron` smoke (development dependency), in Linux CI under `xvfb` on the oldest supported and current stable versions. It checks activation, shared-server startup, the Logs tree listing a generated fixture, its custom editor opening, the Perspective table's fixture row count and appended batch, and the pit registration command producing secret-free user-scope arguments. `DEVELOPMENT.md` describes the isolated test home and remaining manual checks. The rendered plots and interaction, including their `max_points` request, remain follow-up coverage, not claims of this smoke.

8. **Pit server** (§8) (done: second HTTP registration, user-scope Claude URL bridge, remote session tree and path-safe editor, bounded follow, identity-based offline mirror fallback, mirror settings/status/pins, and remembered Sync from Laptop jobs; Node/real-transport tests and plants. Real VS Code on oldest/current versions remains the manual checklist): sessions in the Logs view, opening by path through the pit server, follow for an open session, and the mirror's state. Also the sync between two laptops' stores where there is no pit server (the pit server plan's decision 17 and §14).

11. **A real VS Code in CI** (§10) (done: an isolated `@vscode/test-electron` host on the oldest supported version and current stable under Linux `xvfb`; activation, the shared daemon, generated-fixture Logs listing, custom editor opening, Perspective row counts and append, and actual pit command arguments. Node tests remain separate; dependencies and smoke files stay out of the VSIX. Rendering, offers, credential dialogs, agent discovery, organizing and mirror controls retain the manual checklist).

## Fresh data

The assistant's fresh-data route is now explicit: `list_sessions`, `get_latest_values` and
`wait_for_change` answer for the present, while ordinary tools analyze the recorded past.
`pit://session/current` advertises the current file/identity/gateway/providers through MCP
resources. `list_available_logs` marks that file `open`; tools with a time scope accept
`last_seconds` and report its resolved bounds. A mirror remains a recorded copy with a stated
sync age, not a claim about the robot's present value.

## Open questions

- How much of the pit server's live data belongs here (§8) against in the pit server's own future web view. The proposal is follow and the console only, with the latest values and `wait_for_change` left to the assistant.
