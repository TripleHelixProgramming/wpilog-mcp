# Review of the WPILog Explorer visualization plan

Independent technical and product review, 2026-10-14.

**Document reviewed:** `doc/VISUALIZATION_PLAN.md`, untracked on branch `development` at
commit 441030c, 570 lines, last modified 2026-10-14 08:19, SHA-256 beginning `275d5829`.
The document carries no revision marker beyond "Investigated 2026-10-14". Line numbers below
refer to that file as reviewed.

**Scope:** the plan was checked against the extension, server, store, capture, read-API and MCP
source, against the Explorer plan it extends, against the VS Code API typings in the extension's
`node_modules/@types/vscode` (1.110) and the VS Code workbench source, and against the published
GridStack, Dockview and Perspective releases. No repository file was changed, and nothing was
committed, pushed or published.

## Overall assessment

The plan is **not ready for implementation as written, but close for Stage 1.**

What holds up:

- Every "Verified current state" claim checked against the source is accurate, including the
  line references: the read-only custom editor with `supportsMultipleEditorsPerDocument: false`,
  the one-second follow poll that stops and reloads on failure, the uPlot destroy-and-recreate on
  new data, the unbounded Perspective append, the timestamp-based continuation, the ping-only
  SSE stream, the queued and locked store mutations with atomic replacement, and the
  `render_chart` specification that embeds data.
- The toolkit facts hold: GridStack 14.0.0 is published (21 Sep) under MIT with no runtime
  dependencies; Dockview's keyboard-navigation page is tagged Enterprise; Perspective 5.5.1 is
  the vendored version (5.6.0 was released on 14 Oct).
- The architecture fits the codebase: a service-backed virtual filesystem already exists for
  `wpilog-pit:` URIs (`vscode-extension/src/explorer.ts:92`); the native read API already
  supplies fixed prefixes, per-file ordinals, the 4,096-record and 4 MiB page bounds, session
  unions across rollover files, and 60-second idle expiry; the local server already forwards
  pit traffic with a leased credential.

Why it is not ready: four load-bearing statements are contradicted by the VS Code API or name
capabilities the server lacks (native Save As semantics; a persisted source reference carrying
a per-machine connection id; a server UUID that does not exist; a mutation-authority rule the
tools cannot evaluate), and two product questions are unanswered (which document shows the
overview; where "predefined" visualizations come from). These are decisions, not a redesign.
The smallest changes are given under "Amendments".

## Findings

Each finding states its severity, its classification (**confirmed** means contradicted by code
or API; **decision** means an unresolved assumption; **optional** means an improvement), the
plan location, the scenario, the consequence, and the correction.

### Blocker

**1. Native Save As and auto-save contradict the document model.** Confirmed.
Location: lines 109, 75, 232.
Evidence: the VS Code typings state that after Save As "the current editor is be replaced by
an non-dirty editor for the newly saved file"; the workbench source (`customEditorInput.ts`)
picks the destination through the file dialog service and re-resolves the editor for the new
URI; for a non-file scheme VS Code uses its simplified dialog, which browses the registered
`wpilog-view:` provider. A custom editor model (`mainThreadCustomEditors.ts`) is a working copy
with the Untitled capability only under the `untitled:` scheme, so `files.autoSave` calls the
provider's save on every dirty view.
Scenario: a user with auto-save after delay creates a Blank layout and adds one plot. One second
later VS Code saves a draft that has no saved-view destination; the extension must either prompt
on every gesture or fail. Separately, a Save As would ask the user to pick a server-minted UUID
path and would then re-target the tab, not change the checkpoint as the plan says.
Consequence: unusable draft editing for auto-save users; Save As behaves differently from the
specification; the view identity in the URI changes under the user.
Correction: treat a working view with no saved-view reference as untitled-like, either by not
reporting it dirty and offering an explicit "Save view…" command (store pick, title) with
Explorer-owned undo/redo over revisions, or by opening drafts under the `untitled:` scheme so
VS Code routes Save through Save As. Implement Save As as "mint a new saved view and a new
working view UUID, re-target the tab". Add auto-save, Save As, Revert and hot exit, on a draft
and on a saved view, to the Stage 1 spike acceptance.

### Important

**2. Persisted source references carry a machine-specific connection id.** Confirmed.
Location: lines 85 to 89, 142, and the example at line 346.
Scenario: laptop A saves a view into a shared store; laptop B opens it and cannot resolve A's
`connection_id`.
Consequence: saved views are not portable within a shared store, which the requirement on
shared templates implies they should be.
Correction: define `SourceRef` as durable identity only (`origin_store_id`, `kind`, and
`content_sha256` or session id plus member). Make `connection_id` a request parameter the client
supplies and never persists; the client resolves a connection from `origin_store_id` through its
configured connections (local store registry, pit server, mirror).

**3. `<owner-server-uuid>` has no source.** Confirmed.
Location: line 102.
Evidence: `/health` reports only the configuration name and version
(`src/main/java/org/triplehelix/wpilogmcp/mcp/HttpTransport.java:866`); stores have UUIDs,
servers do not.
Scenario: hot-exit restore must map a `wpilog-view:` URI's authority back to a connection.
Consequence: restored tabs cannot be resolved; the URI scheme is unimplementable as written.
Correction: either add a persistent server instance id written once to the server state
directory and reported by `/health` and `get_server_guide`, or key by configuration name and
state that working views are owned only by the caller's local server, never by a pit server,
with pit data reached through the connection registry proposed at line 211.

**4. Tools cannot evaluate the mutation-authority rule.** Confirmed.
Location: lines 234 to 241.
Evidence: `McpSession` carries no remote address or origin; tools see only a session id through
`SessionContext`; loopback checks exist only on specific transport routes.
Scenario: an agent on the network calls a template-create operation on a pit server and is
indistinguishable from a loopback caller.
Consequence: the stated access boundary cannot be enforced in the tool layer.
Correction: name a Stage 1 component, "transport-supplied caller context", that attaches
per-request trust to the session context (stdio trusted; HTTP trusted only when loopback and
allowed Origin, checked per request), and require every visualization mutation to consult it.

**5. Multiplexed tools fight the tool conventions and claim checks.** Confirmed.
Location: lines 312 to 321.
Evidence: the checklist in `doc/DEVELOPMENT.md:540` requires one schema declaring every
parameter the code reads, a description naming every output with a test that fails on a missing
one, a conformance sweep that calls each tool from its schema, and a scenarios entry per tool.
The schema builder has no `oneOf`.
Scenario: `visualization_views` with eight operations has one schema and one description; the
sweep exercises one operation.
Consequence: either the claim checks are weakened or the tool shape is reworked mid-stage.
Correction: one tool per operation, or keep the four groups and amend the sweep, the
description test and the scenarios file to iterate operations, stated in the plan.

**6. Two document kinds are implied but only one is specified.** Decision.
Location: lines 36 to 74 versus 97 to 113.
Scenario: a user double-clicks `match.wpilog` or a pit session. The overview must appear, views
are separate tabs, and the view tab also has an Overview button; nothing says which editor and
URI show the overview or how "keep the old editor available" works.
Consequence: ambiguity in editor identities, Entries targeting (active source versus active
view), and coexistence with the legacy editor.
Correction: state the two-provider model: a read-only source document for `file:` and
`wpilog-pit:` showing the overview and template selector, and the editable `wpilog-view:`
document. Define Overview as "reveal the source tab, opening it if closed", the Entries target
as the active view with the active source as fallback, and coexistence by view type with
`priority: option` during preview.

**7. The new capture identity registry is a store-format change without a shown failing case.**
Decision.
Location: lines 85 to 93 and 529.
Evidence: the session id is a random UUID minted at capture start
(`src/main/java/org/triplehelix/wpilogmcp/store/CaptureStore.java:140`), preserved through
close and the close-time rename with move aliases; rollover files join the same session; only
peer consolidation on another store picks a different id (`StoreSync`, smallest id wins).
Scenario that the existing identity fails: a view saved on laptop A references store B session
S2; A's peer sync later consolidates S2 into S1; lookup by S2 fails.
Consequence: Stage 1 carries a store-format change, peer capability negotiation and a registry
that can drift from manifests, on the critical path.
Correction: add an additive `merged_session_ids` field to the session manifest when
consolidating, resolve by any listed id, and use `sha256` once final and session plus file name
while live. Defer the registry and capability-aware peer operations unless a scenario the alias
cannot satisfy is shown. The warning that older writers drop unknown JSON fields applies equally
to registry hints.

**8. Templates need a store, and most users have none.** Decision.
Location: lines 217 to 228.
Evidence: the data store is experimental and off by default; a store appears only through
Organize Logs or data-store setup, where `store.json` is created on first mutation.
Scenario: a new user opens a log, builds a layout and clicks "Save template…"; there is no store
to pick.
Consequence: the create, save, reuse journey is blocked for a typical first-time user.
Correction: "Save template…" and "Save view…" offer to create a store when none is writable,
reusing the first-mutation creation path; MCP operations return `NO_WRITABLE_STORE` with that
hint.

**9. The shared pit store is read-only for templates, which weakens sharing at an event.**
Decision.
Location: lines 238 to 240 and 257.
Evidence: the policy matches the code (mirror writes refused; server-path mutations loopback
only).
Scenario: at a competition, teammates cannot save templates into the pit server's store.
Consequence: sharing is by export file; the requirement is met, but the limitation is unstated.
Correction: say it plainly in the sharing section and name the byte-upload import route, which is
admitted off loopback today, as the future authenticated path for template upload.

**10. "Predefined visualizations" are unspecified.** Decision.
Location: the mock at line 45 lists named templates; nothing says where they come from.
Evidence: binding is exact-name only (line 166), so a bundled template would bind to one team's
names.
Consequence: the requirement "select and apply predefined visualizations" is met only if
"predefined" means user-made templates.
Correction: allow a requirement to name a signal-resolver role (`battery_voltage`, `robot_pose`,
`loop_time`, `swerve_states`), bound through the shared resolver with the basis reported in
`bindings[]`; unresolved roles stay unresolved with candidates listed. Ship three or four
bundled templates on that basis, or state that none are bundled.

**11. Recorded-view memory is unbounded.** Decision.
Location: lines 556 to 566 bound live buffers only; the Explorer plan allows four million samples
per series.
Scenario: four views of six plots on a one-hour log at 1 kHz with context retained when hidden.
Consequence: gigabytes of decoded buffers across webviews.
Correction: apply the per-window decoded budget to recorded views too, with server reduction
beyond it, and publish the budget through the capabilities operation.

**12. The control channel is undefined.** Decision.
Location: lines 273 and 325.
Evidence: the extension's MCP client never opens the session's GET stream; the server's stream
only pings.
Consequence: revision and display-request delivery to Explorer has no mechanism.
Correction: specify MCP server-to-client notifications over the session's existing stream for
revision-changed and display-requested events, which also reaches the stdio bridge; keep
long-poll follows for data.

**13. Stage 2 is unbounded and Stage 1 mixes concerns.** Optional, planning quality.
Location: lines 529 to 530.
Correction: split Stage 2 into 2a (overview, blank layout, time series and latest-value table,
saved views, Entries routing) and 2b (templates, compatibility, export and import, MCP tools,
statistics and tables). Move bootstrap-to-delta correctness from Stage 1 to the first Stage 3
gate.

### Minor

14. Older servers will list `visualizations/*.json` as unmanaged strays: the catalog walk at
    `src/main/java/org/triplehelix/wpilogmcp/store/StoreCatalog.java:283` has no type filter and
    the listing labels every stray "import this file to assign it". Document it, or ship the
    exclusion in a server release before templates do. Confirmed.
15. Native Split moves a view rather than copying it under the option chosen at line 107. Say so
    in the UI copy beside Open Beside. Optional.
16. Define the clock for "last 30 seconds": robot time when time sync exists, otherwise receive
    time, with the basis shown (lines 160, 293). Decision.
17. `accepted_entry_types` needs a vocabulary; use the declared type strings the server reports
    and enumerate them in the capabilities operation (lines 119, 360). Decision.
18. Import digests need a canonical form; an import of a higher revision of a known template
    should offer an update, not only a new UUID (lines 249 to 255). Optional.
19. CSP allowances are per page, not per panel; phrase line 205 as "pages whose configuration
    includes a panel that needs them". Optional.
20. Name the cancellation path: the host destroys the HTTP request and the server closes the
    cursor; stale responses are already dropped by generation (lines 300, 548). Optional.
21. The latency gate at line 560 has no instrumentation. Define it: receive timestamp from the
    capture listener, publication time in the delta frame, paint time in the webview. Optional.
22. Line 30 is stale: node 26 and npm 11 are installed at `/opt/homebrew/bin` and the
    extension's `node_modules` exists, so the extension tests can run. Confirmed.
23. Say where recoverable working-view state lives and its bound (line 228). Decision.
24. Let `client_id` be optional when one eligible window exists, and identify windows by
    workspace folder names (lines 319, 441). Optional.

## Requirement coverage

| Requirement | Status | Open findings |
|---|---|---|
| Replace the current editor | Covered, Stage 4 | 6, 13 |
| Overview on open | Covered | 6 |
| Predefined visualizations via visible controls | Partial | 10 |
| Create, arrange, resize, edit, save layouts | Covered | 1, 8, 15 |
| Templates in the store, reuse by compatible names | Covered | 8, 9, 17 |
| Export, share, import | Covered | 9, 18 |
| Multiple views and multiple sources | Covered; overlay deferred with reason | 2, 11 |
| Live auto-update | Covered in Stage 3, unmeasured | 16, 21 |
| Agents create, inspect, modify, display, save | Covered | 4, 5, 12, 24 |
| Shared definitions and services | Covered | 4 |

## Amendments to apply before work begins

```text
Line 109, replace the paragraph with:
Use native dirty state, Save, Revert, undo/redo, close cancellation, backup and hot-exit
restoration for working views that have a saved-view reference. A working view without one is
untitled-like: it is never reported dirty, its edits persist as server revisions, and
"Save view…" (store pick, title) creates its saved view. Save As mints a new saved view and a
new working view UUID and re-targets the tab, as VS Code replaces the editor on Save As.
files.autoSave therefore saves only views that already have a destination.

Lines 85-89, add after the table:
SourceRef holds durable identity only: origin_store_id, kind, and content_sha256 or session id
plus member. connection_id is a request parameter the client supplies and is never persisted.

Line 102, replace <owner-server-uuid> with the server's persistent instance id, reported by
/health, and add: Working views are owned by the caller's local server; a pit server never
owns views.

Line 97, insert before "Use CustomEditorProvider":
Two documents exist: the read-only source document (file: and wpilog-pit:) shows the overview
and the template selector; the editable wpilog-view: document is one working view. Overview in
a view tab reveals the source tab. Entries targets the active view, else the active source.
During preview the new source editor registers with priority "option".

Line 234, add: Stage 1 adds transport-supplied caller context to the session context (stdio
trusted; HTTP trusted only when loopback and allowed Origin, checked per request); every
visualization mutation consults it.

Line 312, replace "Add four groups with operation-specific schemas" with one tool per
operation, named visualization_<object>_<operation>, each with its own schema, description,
scenario and conformance rule.

Line 226, add: Saving into a store creates it on first write when the user picks a folder with
none; MCP returns NO_WRITABLE_STORE with that hint.

Line 166, add: A requirement may name a signal-resolver role instead of an entry; bindings
report the resolver's basis and never guess.

Line 530, split Stage 2 into 2a and 2b as in finding 13, and move "bootstrap-to-delta
correctness" from Stage 1 to the first Stage 3 gate.

Line 562, replace "64 MiB aggregate decoded live buffers per window" with "64 MiB aggregate
decoded buffers per window, recorded and live".

Line 325, add: The control channel is MCP server-to-client notifications on the session's
existing stream.

Line 30, delete the npm sentence.
```

## Milestone readiness

- **Stage 1** can start once findings 1 to 4 are decided. The Save and Save As spike should
  settle finding 1, with auto-save in its acceptance. Hold the capture-identity registry until
  finding 7 is answered.
- **Stage 2** waits on findings 5, 6, 8 and 10 and on the split in finding 13.
- **Stage 3**'s follower prototype can begin at any time, since ordinal follow correctness is
  independent of the UI; its acceptance needs findings 11, 12, 16 and 21.
- **Stage 4** depends on all of the above.

## Sources consulted online

- GridStack releases: https://github.com/gridstack/gridstack.js/releases
- Dockview keyboard navigation: https://dockview.dev/docs/advanced/keyboard/
- Perspective releases: https://github.com/perspective-dev/perspective/releases
- VS Code custom editors guide: https://code.visualstudio.com/api/extension-guides/custom-editors
- VS Code `customEditorInput.ts`: https://raw.githubusercontent.com/microsoft/vscode/main/src/vs/workbench/contrib/customEditor/browser/customEditorInput.ts
- VS Code `mainThreadCustomEditors.ts`: https://raw.githubusercontent.com/microsoft/vscode/main/src/vs/workbench/api/browser/mainThreadCustomEditors.ts
- npm registry entries for `gridstack@14.0.0` and `uplot@1.6.32`
