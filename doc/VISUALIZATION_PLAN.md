# WPILog Explorer reusable visualization design

Status: Stage 1 committed as `632b492`; accepted Stage 2a committed as `c6436d6` after owner
authorization. Accepted Stage 2b and its fixes are committed as `2fb2376`, and the Stage 3
first gate and review refinements as `719f163`. The Stage 3 server integration checkpoint is
implemented locally, with its evidence and remaining live workflow work recorded below. Close cancellation
remains unverified on the manual dialog, Linux/Windows native runs are outstanding, and Save As
uses the specified refusal fallback. No publication or version change is part of this work.


Revision 2, 2026-10-14: incorporates the [independent design review](VISUALIZATION_PLAN_REVIEW.md). The review describes revision 1 (570 lines, SHA-256 `275d58298bd737e9c537a2014c89b099c6321d2de021cfc968aaa851fe5b903b`). Review disposition and verification are recorded at the end of this document.

Revision 3, 2026-10-14: incorporates the six refinements in [review 2](VISUALIZATION_PLAN_REVIEW_2.md), retained unchanged. Stage 1 implementation and its evidence are separate from the design claims below.

Investigated 2026-10-14 using source inspection and upstream API documentation. Revision 2 also checks local VS Code typings and the cached 1.101.0 workbench implementation. Runtime feasibility and performance gates below are not completed results.

## Recommendation and investigation findings

Build the replacement around **source overviews and independent, revisioned visualization documents**, backed by one server-owned configuration and persistence service shared by Explorer and MCP. Reuse the native reader, analysis services, Arrow transport, and uPlot; add GridStack for panel arrangement. Replace the existing editor interaction model.

This extends the existing [Explorer plan](EXPLORER_PLAN.md). Existing and concurrent unrelated work must be preserved. Implementation stages require review; this plan does not authorize production-store changes, commits, version changes, or publication.

### Verified current state

Source line references describe the investigation snapshot and may move as the repository changes.

| Area | What exists and what limits the replacement |
|---|---|
| Discovery and overview | Explorer uses `list_available_logs` and groups local/pit sources by store, robot, and session. Catalog records already contain optional identity, match/event, size, timestamps, completeness, and synchronization information. Signal counts and time ranges come from indexed log inspection. See [discovery](../vscode-extension/src/explorer.ts#L670) and [catalog presentation fields](../vscode-extension/src/explorer/logsTree.ts#L28). |
| Editor identity | The custom readonly editor uses the source URI and explicitly disables multiple editors per document. Its document contains only that URI; panel routing uses an active key. Independent layouts therefore need their own identities. See [registration](../vscode-extension/src/explorer.ts#L108) and [document/provider lifecycle](../vscode-extension/src/explorer.ts#L891). |
| Existing interface | The page has fixed sections for data, plots, chart details, field, REV, console, and entries. Plots support several series and panes, shared cursor, zoom, and server statistics, but not general rearrangement or resizing. Fresh data can destroy and recreate uPlot objects. See [page structure](../vscode-extension/src/explorer/webviewHtml.ts#L45) and [plot lifecycle](../vscode-extension/media/plot.js#L374). |
| Entries | Inline filtering, grouping, keyboard navigation, lazy fields, and generation guards are reusable. The current singleton source/active-editor routing needs explicit view targeting. See [Entries lifecycle](../vscode-extension/src/explorer/entriesView.ts#L119). |
| Persistence | No extension-owned durable visualization save/restore or custom-document backup implementation was found. Perspective `save()/restore()` only preserves configuration while rebuilding a table after schema expansion. See [Perspective adapter](../vscode-extension/media/dataView.js#L48). |
| Current live behavior | Follow is an initially unchecked control for an open pit source. Each visible editor polls every second; failure stops following and reloads the editor. Live messages refresh plots, Perspective, and console, but not histogram/scatter. Timestamp-based continuation misses newly appended earlier timestamps, and Perspective rows are unbounded. See [follow scheduling](../vscode-extension/src/explorer.ts#L1079), [refresh dispatch](../vscode-extension/media/explorer.js#L297), and [timestamp continuation](../vscode-extension/media/follow.js#L8). |
| Shared reading | Managed snapshots retain `LogUse`, expose fixed prefixes, and preserve integer microseconds and physical record ordinals. Queries support field projection, reduction, windows, and `first_ordinal`; progress includes filtered and undecodable records. These are the foundation for reliable live delivery. See [snapshot ownership](../src/main/java/org/triplehelix/wpilogmcp/read/server/ManagedReadSnapshot.java#L112), [query contract](../src/main/java/org/triplehelix/wpilogmcp/read/ReadQuery.java#L10), and [cursor progression](../src/main/java/org/triplehelix/wpilogmcp/read/SampleCursors.java#L125). |
| Acquisition | One `CaptureService` listener feeds the writer, published live state, and optional gateway. Visualization must consume that path, never create another recorder or NT acquisition connection. See [capture ownership](../src/main/java/org/triplehelix/wpilogmcp/capture/CaptureService.java#L202). |
| Store persistence | `LogStore` already serializes mutations under `StoreLock`; `StoreFiles` supplies validated, atomic replacement. New metadata directories must be excluded from unmanaged-file discovery. See [mutation ownership](../src/main/java/org/triplehelix/wpilogmcp/store/LogStore.java#L419), [atomic files](../src/main/java/org/triplehelix/wpilogmcp/store/StoreFiles.java#L105), and [catalog classification](../src/main/java/org/triplehelix/wpilogmcp/store/StoreCatalog.java#L277). |
| Identity and MCP | Finalization preserves a local session ID, but peer synchronization can consolidate sessions and choose a different ID. Existing MCP SSE supplies heartbeat pings, not visualization events. `render_chart.chart_spec` contains data and resolved source details, so it is not a reusable template format. See [session consolidation](../src/main/java/org/triplehelix/wpilogmcp/store/StoreSync.java#L210), [SSE implementation](../src/main/java/org/triplehelix/wpilogmcp/mcp/HttpTransport.java#L612), and [chart results](../src/main/java/org/triplehelix/wpilogmcp/tools/RenderChartTool.java#L56). |

These findings are source-grounded. Revision 2 compiled the extension and passed 52 focused existing pure-logic tests using Node/npm under `/opt/homebrew/bin`; the earlier PATH-based conclusion that tests could not run is withdrawn. These checks do not establish the proposed editor lifecycle or live-delivery behavior. No prototype or live benchmark ran.

## User workflow, identities, and configuration

### Overview and editing workflow

In the completed replacement, opening a source initially shows its overview. During preview, use **Open Source Overview** or **Reopen Editor With**; ordinary log opening retains the legacy editor until Stage 4:

```text
Practice capture · Robot Alpha                     [Source details]
Recording · Explorer connected · Last publication 40 ms ago

Identity / robot / session     Duration / signal count
Recorded metadata              Diagnostics and completeness

[ Search visualizations…                          ] [Blank layout]
Robot power (Built-in)  Compatible 3/3                   [Open]
User-code timing        Partial: role unresolved        [Review]
[Saved views]                         [Unsaved views]
Motor diagnostics · Saved             Draft 2 · Expires in 29 days [Resume]
```

List unsaved working views for this source beside Saved views, with their expiry or attached status. Resume reopens the same working UUID and reconciles recovery state; it does not create a new draft. Closing a draft detaches it without a native dirty prompt, and the overview remains its way back until the published 30-day expiry.

Show catalog metadata immediately. Load the signal inventory and snapshot descriptor separately; an uncached recorded log may need its initial indexing scan. Do not decode complete numeric series or run statistical analyses merely to display the overview.

Each information block distinguishes **loading, available, unavailable, failed, and not requested**. Missing robot identity is not a loading spinner; an unsuccessful request is not an empty summary. Live status distinguishes acquisition state, Explorer connectivity, publication age, and clock synchronization. Old change-only values remain legitimate holds.

Selecting a template defaults to **Open new view**:

```text
Motor diagnostics · Practice capture · View 2       [Overview]
[Add panel] [Add signals] [Apply another…] [Save view] [Save template…]
[Following last 30 s ▾] [Pause display] [Duplicate / Open beside]

┌ Velocity                         [Configure] [Move/resize] ┐
│ measured + commanded                                    │
└──────────────────────────────────────────────────────────┘
┌ Current                          [Configure] [Move/resize] ┐
│                                                          │
└──────────────────────────────────────────────────────────┘
```

The interaction rules are:

- **Blank layout** creates an exploratory view immediately; no template is required.
- **Apply another template** opens another view by default. **Replace this layout** is secondary and uses Save/Discard/Cancel for configuration that differs from its checkpoint, including a draft that is not marked dirty by VS Code.
- **Overview** reveals the source overview without discarding the visualization.
- **Save view** saves this source-bound configuration. **Save template** creates a reusable definition. Updating an existing template is a separately labelled action with revision checking.
- **Duplicate and Open Beside** creates an independent view, initially copying the configuration. Native Split moves the existing view under the single-instance setting; it does not create an independent copy.
- Selecting another source creates another view; a working view's source is immutable.
- An in-editor signal picker supports search, multi-selection, adding to a chosen plot, and creating a new plot. Entries offers the same actions and visibly names the target view and panel.
- Drag panel headers to move panels; plot-area dragging remains data navigation. Provide keyboard Move Earlier/Later, Left/Right, Width, Height, Add, Remove, and signal-placement controls. Cross-webview dragging from Entries is optional pending verification.

### Distinct objects and ownership

| Object | Identity and lifetime |
|---|---|
| Recorded source | Managed content uses origin store identity plus content hash. Serving location is separate. Unmanaged files receive a recoverable source UUID backed by a security-validated locator and fingerprint. |
| Live source | Origin store identity, existing capture-session UUID, and an anchor member identified by original filename and recorded capture-start provenance. Shared reader rules identify the rollover members of that recording. Finalized members acquire content hashes. |
| Template | Store-owned UUID, schema version, revision, descriptive metadata, and source-independent configuration. |
| Working view | Server-owned UUID, immutable source reference, configuration revision, checkpoint, and optional saved-view/template provenance. GUI and MCP target this object. |
| Saved view | Store-owned UUID/revision containing a source reference and configuration snapshot. Opening it creates a new working view; workspace restoration resumes the previous working UUID. |

`SourceRef` contains durable identity only. It never persists `connection_id`, a server URL, or credentials. An optional `connection_id` is a request/attachment routing hint, discarded before persistence. Resolve `origin_store_id` through configured local stores, pit connections, and mirrors, then verify the source identity. A missing connection produces an unavailable source, not a substituted one. Unmanaged-source UUID locators remain owner-server recovery state; another machine needs an authorized matching source before it can open such a saved view.

Use the existing session UUID rather than introducing a second capture registry. Add `merged_session_ids` to session manifests, defaulting to an empty list. Consolidation unions both sessions' canonical IDs and aliases; catalog lookup accepts any listed ID. Preserve aliases in every session reconstruction, sync/receipt replay, and mirror copy. Existing capture close and rename already preserve the original session ID. See [capture identity](../src/main/java/org/triplehelix/wpilogmcp/store/CaptureStore.java#L140) and [session consolidation](../src/main/java/org/triplehelix/wpilogmcp/store/StoreSync.java#L247).

An alias must not widen a view to every file in a merged session. Resolve exactly one anchor member, then reuse/extract the existing [same-recording rule](../src/main/java/org/triplehelix/wpilogmcp/read/catalog/SessionSnapshot.java#L113), which uses capture provenance, original capture start, and first collecting store. Rollover membership belongs to that shared owner. Ambiguous anchors fail explicitly. Extend the current exact-ID session-read lookup to support aliases and this recording-membership filter; do not duplicate the deduction in the visualization service.

Finalization adds hashes and final locations to the resolved members without changing the working view UUID or recording membership. New single-file recorded views use content hashes; existing live anchors continue to resolve those same members. Persist known final hashes with saved source resolution so later access can use them without guessing. Older writers can drop the new alias field: document this mixed-writer/downgrade limit and test it. If an alias is lost, use proven recorded hashes or return `SOURCE_UNAVAILABLE`; never fall back to robot identity, time overlap, or whichever session is current. Defer a separate identity registry and peer capability negotiation unless a concrete alias failure requires them.

Simultaneous display of independent sources is in scope. Overlaying different sources in one plot and automatically aligning their clocks are separate later extensions; the initial view has one source.

### VS Code document model

Two document kinds have separate providers. The readonly source document for `file:` and `wpilog-pit:` shows the overview and template selector. The working-view document uses `CustomEditorProvider<VisualizationDocument>` and a service-backed `wpilog-view:` filesystem provider. During preview, register the new overview as `wpilog-mcp.sourceOverview` with `priority:"option"`; retain the legacy editor under its existing view type. Open Source Overview and VS Code's Reopen Editor With select the preview. Only Stage 4 changes the default source editor.

Overview in a view tab reveals the matching source tab, opening it if closed. Entries targets the active working view; with an overview active, it targets that source and offers New visualization for a signal selection. It must never edit the last hidden view as a fallback.

Canonical working-view URIs are:

```text
wpilog-view://<server-instance-id>/<view-uuid>.wpiview
```

Stage 1 introduces the persistent server instance ID described under storage and exposes it through `/health` and `get_server_guide`; this is new work, not an existing server property. Working views are owned by the caller's local named server. A remote pit server supplies data and store reads, not ownership of that caller's working views. All filesystem-facade writes remain validated and revisioned; generic writes cannot bypass the owning service.

Keep `supportsMultipleEditorsPerDocument:false` for each working view in v1. Independent UUIDs give independent tabs in ordinary VS Code groups. Explain beside Duplicate and Open Beside that native Split moves this document instead of copying it.

A working view without a saved-view reference is untitled-like but **not reported native-dirty**. Its accepted edits persist as server revisions; Explorer provides undo/redo and reset-to-initial-configuration. Explicit Save view… selects a writable store and title, creates the saved definition, and attaches its checkpoint to the current working view. Only after that succeeds, clear Explorer's draft undo/redo history and begin the native undo history with subsequent edits; do not replay draft edits as native edits. A failed save or conflict preserves the draft history. Native Save/Save All and `files.autoSave` must not prompt for draft destinations. The service's `has_unsaved_changes` remains true even though `native_dirty` is false.

Before acknowledging a draft gesture, write an extension-host recovery journal under `ExtensionContext.storageUri/visualization-drafts/` (fall back to `globalStorageUri` when no workspace storage exists), then submit the server CAS update. This journal is recoverable workspace state, not a template store. Bound it to 64 MiB and 256 documents. A journal write failure or full journal degrades to a visible **Saved on server only; local recovery unavailable** warning and permits server-acknowledged edits. Never acknowledge an edit as durable until either the journal or the server has persisted it. If both are unavailable, keep the edit visibly pending in bounded memory, warn that closing can lose it, and offer retry or export; do not present it as saved. Show Saved locally, Pending server, Saved on server only, or Conflict. Do not rely on disposal, native dirty backups, or browser storage for draft recovery. Reopening reconciles the journal's base revision with the server rather than overwriting newer edits.

Working views with a saved-view reference use native dirty state, Save/auto-save, Revert, undo/redo, close cancellation, backup, and hot-exit restoration. Save checkpoints only the known destination; failure leaves the document dirty. Revert restores the saved checkpoint through CAS and retains local changes on conflict. Undo/redo are new revision-checked mutations, not unconditional restoration of old server state. The recovery journal also protects pending edits when native backup is skipped under auto-save. Backup conflicts keep the document open with local recovery content and a visible comparison warning; they never silently overwrite a newer server revision. After the server acknowledges a native Save, wait at most five seconds for the clean-tab event before releasing deferred gestures with a visible timeout warning. Cancel that timer on acknowledgement or document disposal. This deadline bounds the native acknowledgement wait, not the server save operation.

Native Save As must honor the destination URI VS Code actually selects and reopens. The filesystem provider exposes a human-readable save namespace such as `wpilog-view://<server-instance-id>/save-as/<store-label>/<title>.wpiview`, limited to writable stores. List human-readable store labels, disambiguating duplicate labels with readable stable suffixes; resolve those labels to immutable store IDs inside the provider. Persist the selected destination mapping so a later label change cannot retarget it. `saveCustomDocumentAs` validates the selected destination before mutation, mints a new saved-view UUID and working-view UUID, and persists a destination-URI alias to those objects. An idempotent operation receipt makes the saved definition, new working record, and alias recoverable across interrupted saves. The replacement tab resolves the exact selected URI through that alias; canonical UUID opens normalize to the same resource and attachment. Reject existing destinations and unknown authorities/non-store destinations without overwriting an object, with a hint to choose another name or use Save view…. This mapping is local working state, not portable saved-view metadata. Native picker, replacement-tab, alias restoration, and auto-save behavior are Stage 1 spike gates. If those gates fail, `saveCustomDocumentAs` refuses before any mutation with **Use Save view… to select a store and title**. Save view… remains the explicit supported path; this is the specified fallback, not an unresolved design choice.

One Explorer window owns a working-view attachment. Another window must reveal the owner or open a copy. This prevents one window's Discard from reverting another window's work. Alias and canonical URIs cannot bypass this attachment rule. Stage 2a must bind attachments to registered client sessions and enqueue detach when a session expires or is explicitly removed, including a crashed window. Disconnect/reconnect races must not release a replacement session or another client's attachments. Test expiry without a server restart and successful attachment from another window afterwards; Stage 1 currently supplies explicit detach and restart recovery only.

Entries routes every action with `view_id`, active panel, and generation. Shared source caches must not contain view selection, cursor, zoom, form edits, or pending request identity. Cursor and time-range linking is within one view by default; any cross-view synchronization must be explicitly requested and is not required for the initial release.

### One versioned configuration schema

`VisualizationConfiguration` is embedded unchanged in templates, saved views, and working views. It owns:

- Exact signal requirements or explicit shared-resolver roles, accepted declared types, canonical field paths, expected units and their basis.
- Panel IDs/types, titles, series, colors, axes, display settings, and allowlisted analysis configuration.
- Twelve-column layout geometry.
- Recorded/live time policies and within-view cursor/range links.

Templates contain no samples, source IDs, absolute source timestamps, paths, URLs, credentials, or transient handles. Hover, temporary inspection range, display pause, request generations, and buffers are runtime state.

Representative records:

```typescript
type SourceRef =
  | { kind: "recorded"; origin_store_id: UUID; content_sha256: string }
  | {
      kind: "session";
      origin_store_id: UUID;
      session_id: UUID;
      anchor_member: { original_name: string; recording_started_at: string };
      finalized_members?: { content_sha256: string }[];
    }
  | { kind: "unmanaged"; source_id: UUID };

type TemplateRef =
  | { origin: "bundled"; template_id: UUID; revision: number }
  | { origin: "store"; store_id: UUID; template_id: UUID; revision: number };

type TemplateV1 = {
  schema_version: 1;
  template_id: UUID;
  revision: number;
  name: string;
  description: string;
  configuration: VisualizationConfigurationV1;
};

type SavedViewV1 = {
  schema_version: 1;
  saved_view_id: UUID;
  revision: number;
  source_ref: SourceRef;
  title: string;
  configuration: VisualizationConfigurationV1;
  template_ref: TemplateRef | null;
};

type WorkingViewV1 = {
  schema_version: 1;
  view_id: UUID;
  revision: number;
  source_ref: SourceRef;
  configuration: VisualizationConfigurationV1;
  checkpoint_configuration: VisualizationConfigurationV1 | null;
  template_ref: TemplateRef | null;
  saved_view_ref: { store_id: UUID; saved_view_id: UUID; revision: number } | null;
  detached_expires_at: string | null;
};
```

Default time policies are full duration for recorded sources and following the last 30 seconds for live sources. Fixed ranges use seconds relative to the source's published clock origin. Out-of-range intervals remain visible with an empty/partial-range explanation rather than silently changing their meaning.

Template revisions are pinned snapshots. Updating a template never changes unrelated open or saved views.

### Compatibility rules

User-created templates default to exact signal names, preserving the requested cross-source workflow. A requirement may instead explicitly select a shared-resolver role; it never silently falls back from a missing exact name to a role. Binding uses the shared resolver and field-path/type services:

- Each requirement declares `selector_kind:"entry"` with `entry`, or `selector_kind:"role"` with `role`, never both selectors. Other requirement fields retain the common type/field/unit validation. This tagged configuration is owned by the shared validator rather than an MCP operation dispatcher.
- Missing names remain visible as unresolved requirements and placeholder panels.
- Changed types are incompatible unless explicitly included in the requirement's accepted types.
- `accepted_entry_types` contains exact declared type strings from the server, not JavaScript or transport type names. The capabilities tool enumerates supported declared primitives (`boolean`, `int64`, `float`, `double`, `string`, `json`, `raw`), supported array forms, and structured forms such as `struct:Name`, `struct:Name[]`, and legacy `structarray:Name`; source inspection supplies the actual declared strings and schemas. Preserve spelling, including custom types; unsupported numeric types are not coerced. See [entry decoding](../src/main/java/org/triplehelix/wpilogmcp/log/subsystems/EntryDecoder.java#L86) and [struct type forms](../src/main/java/org/triplehelix/wpilogmcp/log/struct/StructSchemas.java#L215).
- Structured fields validate against recorded schemas. Dynamic array indices require per-record bounds checks; missing elements produce explained unavailable values.
- Known unit differences block automatic binding. Unknown units produce a visible qualification. Explicit user declarations retain their basis; no automatic conversion or guessed units.
- Selector results show **compatible**, **partial**, or **incompatible**, with requirement-level reasons. Compatibility is not determined by name alone.
- Resolved role bindings report the selected entry, role, resolver basis, unit basis, and candidates that were not selected. Unresolved roles remain visible with candidates and reasons; no name-based guesses are added by the GUI.
- Live catalog/schema revisions and eligible sample/frontier advances recheck unresolved requirements at a bounded cadence. Some resolver roles require finite samples, so metadata-only rechecks are insufficient. A newly usable signal starts its own bootstrap automatically, without reapplying the template.

### Predefined visualizations

Stage 2b ships three immutable bundled definitions, exposed through the same template catalog/schema as store templates:

| Bundled definition | Shared resolver roles and panels |
|---|---|
| Robot power | `battery_voltage` and `total_current` plots; `brownout_flag` latest/cursor state. |
| Driver Station state | Latest/cursor table for `robot_enabled`, `autonomous`, `test_mode`, and `fms_attached`. |
| User-code timing | `loop_time_user` plot and server statistics, only where the shared resolver establishes the published convention and unit basis. |

Give each bundled definition a fixed UUID and revision. Catalog envelopes and `TemplateRef` distinguish `origin:"bundled"` from `origin:"store"`; a bundled reference has no `store_id`. `TemplateV1` remains the shared portable definition, independent of catalog location. A source need not have a writable store to apply a built-in or edit the resulting working view. Only explicitly saving a reusable definition creates a store-owned copy; bundled definitions themselves are immutable. The picker labels Built-in versus Store and explains partial compatibility before opening. Exact entry selectors remain the default when saving a user-created layout; keeping a role selector is an explicit reusable-setting choice.

These are existing role names in [SignalResolver](../src/main/java/org/triplehelix/wpilogmcp/tools/SignalResolver.java#L74), not new frontend conventions. Do not call `/Timestamp` a loop-duration series: `loop_time_full` can select that source, and deriving its periods belongs to the existing server timing owner. A richer full-loop template waits for that owner to expose the derived series. See [loop selection](../src/main/java/org/triplehelix/wpilogmcp/tools/SignalResolver.java#L549).

## Rendering, shared services, storage, and live delivery

### Toolkit and panel choices

| Choice | Assessment |
|---|---|
| **uPlot 1.6.32 — retain** | MIT; already bundled and aligned with server-produced reductions. Use `setData`, scale updates, resizing, and disposal instead of rebuilding charts. Current JS is 51,081 bytes, about 21,991 bytes gzip. Explorer owns accessible readings and configuration controls. [Versioned API](https://github.com/leeoniya/uPlot/blob/1.6.32/dist/uPlot.d.ts). |
| **GridStack 14.0.0 — add** | MIT, no package dependencies; supplies dashboard placement, resizing, and serialization APIs. Store only IDs and geometry through Explorer's schema, never widget HTML. Explorer must provide keyboard alternatives and accessibility. Actual packaged byte cost remains to be measured. [Versioned source](https://github.com/gridstack/gridstack.js/tree/v14.0.0). |
| **Dockview — do not add** | IDE-style inner tab groups duplicate VS Code's outer editor organization. Its documented built-in keyboard navigation has an Enterprise qualification; it cannot be assumed part of the MIT core. Grid arrangement better fits the requested analysis layouts. The investigation compared published 8.5.1; it is not a proposed dependency. [Official documentation](https://dockview.dev/docs/advanced/keyboard/). |
| **Perspective 5.5.1 — bounded, optional** | Apache-2.0; installed assets occupy roughly 4.9 MiB and require worker/WASM lifecycle handling. Retain only an optional exact-data table initially. Disable/defer browser aggregation and pivots that duplicate authoritative server analysis. Its existing append adapter cannot be reused unchanged. [Versioned source](https://github.com/perspective-dev/perspective/tree/v5.5.1). |

Replacing uPlot is not justified by the observed gaps: they concern identity, editing, persistence, and delivery. No alternative renderer benchmark established a migration benefit. Keep layout and renderer adapters behind Explorer-owned interfaces so library upgrades do not become template-schema migrations.

The panel roadmap is explicit:

| Panel | Initial scope | Live behavior |
|---|---|---|
| Time series | Recorded preview | Bounded updates at up to 4 Hz; preserve plot objects and viewport. |
| Latest/cursor-value table | Recorded preview | Server-resolved values with timestamps; no frontend interpolation. |
| Raw/event table | Recorded preview | Ordinal-keyed pages; bounded append and visible retention limits. |
| Statistics | Recorded preview | Server calculation over the effective interval, coalesced to at most 1 Hz. |
| Histogram | Before editor retirement | Existing server binning; bounded-window recomputation at most 1 Hz. |
| Scatter | Before editor retirement | Existing exact-timestamp, one-to-one record pairing; no interpolation or automatic alignment. |
| Field/pose, console, REV information | Before editor retirement | Shared clock/source controller and existing authoritative interpretation. |
| Advanced Data | Optional before retirement | Bounded exact rows, declarative column/filter/sort settings, automatic bounded replacement when following. |

Initial live derived-analysis windows are limited to **1–300 seconds**. Larger requests receive an actionable limit; they are not silently truncated. Recorded analysis may cover the complete source through existing bounded/streaming query owners.

Existing `FieldPath`, `TimeWindows`, `NumericBucket`, statistics, and `render_chart` services remain authoritative. Plot buckets are never inputs to statistical calculations. Preserve the existing histogram and scatter rules rather than introducing browser versions. [Shared arithmetic owner](../src/main/java/org/triplehelix/wpilogmcp/read/NumericBucket.java#L7), [existing chart semantics](../src/main/java/org/triplehelix/wpilogmcp/tools/RenderChartTool.java#L124).

Bundle all assets and licenses; no CDN or webview network access. Keep credentials in the extension host. CSP is per page: grant the existing narrowly scoped worker/WASM allowances only to pages whose configuration includes a panel that needs them. Use `ResizeObserver`, nonzero-size checks on reveal, and explicit chart/table/worker disposal. Real VS Code 1.101 and current-stable checks must verify these behaviors; browser demos are insufficient. [Custom-editor lifecycle](https://code.visualstudio.com/api/extension-guides/custom-editors), [webview lifecycle and persistence](https://code.visualstudio.com/api/extension-guides/webview).

### Shared service boundaries

Introduce one Java `VisualizationService` for configuration validation, compatibility, working-view revisions, saved views, templates, and operation receipts. HTTP and MCP are adapters to it.

It delegates source identity and resolution to the store/catalog owners, and data operations to the existing reading/analysis owners. A configured source-connection registry permits remote descriptors and queries; it does not accept arbitrary agent-supplied URLs.

Existing `RemoteLogAccess` provides fixed-snapshot access, but its current adapter accepts bare origins and does not inject Authorization. Extending it with configured credential leases is required work, not an existing capability. Unsupported proxy-prefix or older-server cases must return explicit capability errors. A verified mirror offers recorded/offline fallback, not presumed live freshness. [Remote access constraints](../src/main/java/org/triplehelix/wpilogmcp/read/http/RemoteLogAccess.java#L47).

### Durable storage and editing

Use:

```text
<store>/visualizations/templates/<template-id>.json
<store>/visualizations/saved-views/<saved-view-id>.json
```

Store-wide records avoid copying templates into every session. Optional preferred-template/saved-view associations live in the visualization registry, keyed by stable source reference.

All visualization metadata writes use a narrow `LogStore` mutation entry point, its queue, cross-process lock, containment checks, and atomic file replacement. Reject unknown future schema versions; older visualization versions require explicit tested migrations. Do not rewrite WPILog bytes or enable data-store enrollment.

Create the owning server's independent state directory at `~/.wpilog-mcp/state/visualizations/<config-key>/`, reusing/extracting the existing config identity derivation: SHA-256 of the real configuration path, a newline, and the named server name. This is the [LocalIngestion state-location convention](../src/main/java/org/triplehelix/wpilogmcp/store/LocalIngestion.java#L122), not ingestion policy or permission to enable it. Atomically create `server.json` under a state lock with a persistent `server_instance_id`; expose that ID through `/health` and `get_server_guide`. Today health reports configuration name/version/PID, not such an ID. Anonymous servers without a durable configuration identity advertise ephemeral working-state support and cannot promise restart recovery. If a named server cannot open this optional state, it warns and continues with an ephemeral identity; health and the guide include `working_state_reason`.

Store working instances in `views/<view-id>.json`, operation receipts in `receipts/`, and native Save As destination aliases with their working records. Bound live state to 256 non-deleted working documents and 256 MiB aggregate metadata, including checkpoints, receipts, and aliases; each configuration remains bounded to 1 MiB. Detached working records, including drafts and saved views' working copies, expire after 30 days; attached records do not. Quarantine malformed or unknown files intact under `quarantine/<uuid>/`, log a warning, and continue loading valid records. Keep quarantined files for manual inspection outside the active-state quota; if the move fails, retain the original and fail recovery. Prune expired records before every budget check, including updates, then return `WORKSPACE_LIMIT` rather than evicting attached or unexpired unsaved work. Responses and capabilities publish these limits and the supported idempotency-retention interval. Deleted working records instead have a six-hour retry window and separate caps of 256 records and 8 MiB, evicting the oldest under pressure. Expiry or eviction ends their retry guarantee; capabilities publish these bounds. Recovery shortens legacy longer tombstone deadlines once and persists the deadline. Saved definitions and templates in log stores do not expire automatically.

GUI semantic edits commit through compare-and-swap and become inspectable through MCP. Commit on completed actions, not every pointer movement. The extension recovery journal protects offline/unacknowledged edits; native backups supplement it where VS Code invokes them. Responses distinguish configuration/checkpoint differences from native dirty state.

Saving writes the store definition at its expected revision, then advances the working checkpoint only if the working revision has not changed meanwhile. Otherwise the view still has uncheckpointed changes (and a saved view stays native-dirty). Revision conflicts preserve both the local draft and current server configuration; offer comparison, intentional retry, or Save Copy.

Enforce this with a per-document **native save barrier** in the extension controller. Capture the revision being saved; journal and queue local gestures and incoming revision/native edit events while native Save is pending. Do not hold a store lock or block agent service edits while waiting. If checkpoint CAS fails, reject native Save so VS Code does not mark the document clean. After successful native-save completion, reconcile and emit gestures queued during that save as one new undo group, keeping their final journal state until that whole group reaches the server. Emit the group as a new edit event so they remain dirty instead of being included in VS Code's just-saved edit-stack position. The Stage 1 real-editor spike must verify event ordering across the extension-host/workbench boundary, including GUI and agent changes during save; a service-side revision check alone does not establish native dirty-state correctness.

Save view… and Save template… always include Create store… when there is no suitable writable destination. Let the user pick a folder and use the existing first-mutation path: `POST /store/import` with `paths:[]`, `move:false`, and an allowed destination creates an empty store through `LogStore`. See [first-write creation](../src/main/java/org/triplehelix/wpilogmcp/store/LogStore.java#L430) and the [standalone route reference](STANDALONE.md#L735). A selected directory must be configured or explicitly leased through existing directory registration; do not silently grant arbitrary paths or establish permanent ingestion policy. MCP returns `NO_WRITABLE_STORE` with this setup hint instead of choosing a directory. Creating metadata storage does not enable continuous experimental enrollment.

Preserve current access boundaries:

- Stage 1 adds an immutable **transport-supplied caller context** beside `SessionContext`. Direct stdio explicitly supplies trusted-local authority; unspecified context is untrusted. HTTP computes authority for every request from the actual loopback listener, loopback peer, and absent-or-allowed Origin, including single and batch dispatch. A session ID, tool argument, proxy header, or null session cannot grant trust.
- Every visualization mutation and client-registration/display action consults the same service-level admission check. Clear request context in `finally`; queued work carries the captured immutable authority rather than reading a thread-local later. Never cache trust on `McpSession`. See [current session context](../src/main/java/org/triplehelix/wpilogmcp/mcp/SessionContext.java#L14) and [HTTP local admission](../src/main/java/org/triplehelix/wpilogmcp/mcp/HttpTransport.java#L218).
- Network/shared-store discovery and export can be read-only.
- Remote source stores and mirrors remain read-only unless an independently authorized write mechanism is added later.
- Users can save a view/template into an explicitly selected writable local store while reading a remote source.
- Existing feature gates and directory grants remain effective; no implicit enrollment or mirror conversion.

### Sharing and validation

Use portable JSON with `format:"wpilog-visualization-template"` and `schema_version:1`, containing the template record only. Its content digest is SHA-256 over UTF-8 [RFC 8785 JCS](https://www.rfc-editor.org/rfc/rfc8785) canonical JSON of `{format,schema_version,template}`, excluding the digest field itself. Whitespace and property order are immaterial; array order is significant and Unicode is not normalized. Do not substitute sorted Gson output for JCS number formatting. Store-local timestamps, connections, and provenance are outside the portable envelope.

Validate before writing: maximum 1 MiB, 32 panels, 256 signal requirements, nesting depth at most 32, finite binary64 numbers, safe-integer positive revisions, valid layout geometry, enums, UUIDs, allowed analysis operations, and allowed fields. Reject duplicate JSON keys before generic parsing discards them, lone surrogates, executable content, arbitrary URLs, samples, and local paths. Signal entry names and field selectors remain allowed declarative identifiers, not filesystem locators.

Import rules:

- Same UUID/revision/content digest: idempotent no-op.
- Same UUID/revision with different content: conflict.
- For a known UUID with a higher imported revision, explicitly offer Update existing and Import copy; default to a copy. Update requires the current destination revision and assigns the next local monotonic revision, retaining imported identity/revision/digest as receipt provenance. A larger imported revision never authorizes overwrite by itself. Older imports offer a copy rather than downgrade.
- Persist import receipts by portable identity/revision/digest so repeating an import is a no-op even if the destination assigned a different local revision or UUID.
- Duplicate names are allowed and visibly disambiguated by store/revision/short ID.
- Unsupported versions and malformed records produce field-level errors without partial writes.

Users sharing one store discover its registry directly. Transfer between stores uses export/import. **In v1, teammates cannot save templates directly into a remote pit store:** they save locally and share validated export files, or an authorized local operator imports into the pit store. This is a deliberate event-workflow limitation. A future authenticated template-byte upload route can reuse the admission model of the existing off-loopback `POST /store/import` octet-stream upload; that route currently imports logs, not template JSON, and must not be repurposed without separate validation and authorization. See [byte-import admission](../src/main/java/org/triplehelix/wpilogmcp/mcp/StoreImportEndpoint.java#L96).

Automatic template replication through peer/mirror synchronization is a later extension. Ship the `visualizations/` catalog exclusion in the preparatory server work before template metadata is written. Older readers that lack it will show those JSON files as unmanaged strays; document the minimum visualization-aware reader and this downgrade behavior rather than claiming transparent compatibility.

### Continuous live delivery

Add a resumable follower above the native read layer:

```text
Recorder/index
    → shared source publication broker
    → immutable-prefix follow reader
    → extension source broker
    → independent view controllers
```

The server broker publishes cheap source/catalog/frontier changes without decoding or network work on the acquisition loop. The extension maintains one broker per source/server/window, unions signal requirements, and deduplicates identical reads. It shares acquisition and data, not interactive state.

Proposed `/read/v1/follows` operations are create, bounded `next`, and close. Long polling has a maximum wait of 25 seconds on a bounded dedicated executor. Telemetry remains separate from the MCP control notifications; the currently ping-only SSE stream needs the explicit control-channel implementation described below.

The continuation contract is:

1. Bootstrap fixes prefix **P0**, reads the requested historical interval, and records P0's full member/entry frontiers.
2. After bootstrap completes, deltas consume physical ordinals after P0. Publications during bootstrap therefore remain available.
3. Each delta fixes another prefix and returns bounded pages. Distinguish page completion from completion of the entire frontier.
4. Cursors cover server epoch, durable session/member or content identity, member generation, catalog revision, and per-member/per-entry next ordinals. Native snapshot member handles are transient and never saved into templates or durable source references.
5. Advance acknowledgement only after terminal success. Interrupted pages are discarded and retried; retained retries reproduce the same page.
6. Progress includes visited, filtered, and undecodable records. Equal or backward timestamps never identify or deduplicate records.
7. Metadata/schema changes publish their own revision even when no data sample arrives.

The first-gate review settles three contract refinements before the route is built:

- A record that exceeds the follow's native payload or bounded wire representation emits a
  `RESOURCE_LIMIT` problem with its physical ordinal, offset and payload size. The problem counts
  as visited and advances only when its page is acknowledged. This is an explicit data gap, not
  a delivered value or a claim of sample-lossless delivery. Other entries continue. Ordinary
  native read requests retain their fail-on-limit policy unless explicitly opted into problems.
- Send the complete frontier only on the first acknowledged page of each prefix. Subsequent
  pages refer to `prefix_id`; retries reproduce the original page, including its metadata when
  present. A metadata-only first page is allowed when a data batch needs the whole next page.
  Validate the complete metadata envelope at admission so an admitted prefix can always be sent.
  Clients retain that frontier until it completes; lost client state requires explicit rebootstrap.
- Fill pages across entries and members under one global visited-record and encoded-byte bound.
  A record that fits a fresh data page but not this page is deferred without ordinal advancement,
  not reported as oversized. Entry completion and full-frontier completion remain distinct.

Start with the native limits of 4,096 visited records and 4 MiB per page, plus a global in-flight byte budget. The work bound includes any lookahead needed to decide page fit. Do not create an unbounded telemetry queue for slow consumers.

Valid cursors support exact catch-up. After 60 seconds idle expiry or a server restart, explicitly rebootstrap the requested window from the **same** retained recording. If history is gone, report its available interval or `HISTORY_UNAVAILABLE`; do not claim lossless replay or attach to another session.

Finalization drains final counts, resolves final locations/hashes, and changes source status to recorded while retaining the view and inspected interval. Session alias resolution preserves the anchored recording membership. New boots or unproved clock continuity require explicit selection of another source.

The server integration review adds four **pre-broker requirements**. These are the next contract
changes, not behavior implemented by the malformed-body fix:

- **Terminal acknowledgement:** every page will carry `ended`, true only when that page completes
  a finalized frontier. The terminal data page remains byte-identically retryable until
  acknowledged. Its acknowledgement will release retained data and return HTTP 410 `FOLLOW_ENDED`,
  instead of generating another empty page. A bounded lightweight lease retains that terminal
  result until DELETE or idle expiry so a repeated terminal acknowledgement is unambiguous.
  The broker consumes the terminal page, stops scheduling polls and DELETEs; it never infers
  completion from an empty batch or a 204. Test a multi-page final drain, lost terminal response,
  repeated acknowledgement and last-consumer cleanup without stopping acquisition.
- **Byte-budget pressure:** keep the separate 32-lease and 64 MiB encoded-byte limits. Add an
  explicit `retryable` field to 413 responses: true for shared encoded-budget pressure, false for
  structural page/index admission limits. A transient refusal preserves the submitted cursor;
  the preceding page may already be acknowledged. The broker backs off with jitter, releases
  unneeded consumers and retries that cursor rather than rebootstrapping. It must not classify
  errors from message text. Test concurrent maximal pages, byte release and exact continuation.
- **Live-to-finalized proof:** persist the member's writer-incarnation identity in open and
  finalized capture metadata and retain it in the follow proof. Manifest copying, recovery,
  moves and mirrors must preserve it. Compare it before continuing over finalized bytes; a
  finalized hash alone does not prove the prior live prefix. Legacy metadata with no such proof
  requires explicit rebootstrap at this transition, while an initially finalized source can
  bootstrap from its verified hash. Test replacement plus finalization between polls with the
  same provenance, entry IDs/types/counts, and test genuine finalization and recovery separately.
  Identity publication stays off the acquisition hot path beyond its existing metadata handoff.
- **Page fairness:** the engine will rotate across `(member, entry, field)` selections, allowing
  at most 256 visited records per turn under the existing page work/byte limits. Retry pages keep
  the same order; only acknowledgement commits the next scheduling position. This places one
  scheduling owner above native cursors instead of making each client reorder queries. Test a
  chatty first entry with sparse later entries, tiny page budgets, deferred records, malformed
  gaps and a retried page. Lexicographic member names remain identities, not chronological order.

The current route's terminal behavior, transient-budget diagnostic, transition proof limitation
and query-order scheduling remain documented in [READ_API.md](READ_API.md#following-a-server-recording)
until these requirements are implemented and planted. None is claimed as part of the accepted
server checkpoint, and none moves the remaining live workflow out of Stage 3.

Define the live last-N-seconds interval in the recorded source's robot/server clock domain, using the shared NT4 time estimate as the right edge and reporting `clock_basis`. Capture currently requires time synchronization before declarations and writes sample timestamps unchanged. Without a usable estimate, show Waiting for Time Sync and hold a previously valid edge with `clock_basis:"held_robot_edge"`. If no valid edge exists, use the latest published sample timestamp with `clock_basis:"last_sample"`; if neither exists, show the waiting state without fabricating a range. Do not mix receive timestamps into the sample axis. Receipt time remains appropriate for freshness and latency. A receive-time plotting mode would require a separate explicit projection and is deferred. See [capture clock admission](../src/main/java/org/triplehelix/wpilogmcp/capture/CaptureWriter.java#L407) and [sample timestamp preservation](../src/main/java/org/triplehelix/wpilogmcp/capture/CaptureWriter.java#L619).

Display behavior:

- New live views automatically follow the last 30 seconds.
- Panning/zooming switches to Inspect History; incoming data cannot move the interval.
- Resume Following returns to the configured rolling window.
- Pause Display freezes the displayed prefix, not acquisition.
- Source health distinguishes acquisition, transport heartbeat, publication age, and history availability.
- Late records invalidate affected visible reductions; update bounded data frames without rebuilding layouts or rereading the complete recording.

Hidden views stop rendering and their data work. Release decoded buffers after 30 seconds hidden; reveal reboots the requested interval. Apply one configurable aggregate decoded-data budget per VS Code window to recorded and live sources, counting broker caches and per-webview copies. The `wpilog-mcp.visualization.decodedBufferMiB` setting defaults to **64 MiB** and accepts integers from 16 to 1024 MiB. Raising it permits longer exact recorded series at greater memory cost; lowering it requests reductions sooner. Capabilities report the default and range, plus each attached window's effective `decoded_buffer_budget_bytes`; never report a caller-supplied value as the server's own allocation. Stage 3 implements and validates this setting with the broker. Request server reduction or bounded paging before admission would exceed that budget; do not load millions of recorded samples per series. Publish the budget and table/page limits through capabilities. Bound encoded-response caches separately to 64 MiB in the new visualization broker rather than retaining the old 256 MiB default alongside unlimited decoded copies.

Add an `AbortSignal`/owned HTTP request handle to data and follow requests. Closing/hiding a view or replacing its query destroys that request only when its last consumer detaches, and closes its follow lease. Server cancellation closes admitted cursors, removes follow waiters, and releases snapshots on disconnect, lease closure, or deadline. Disconnection is detected at response writes and cursor batch boundaries, so cancellation is bounded and cooperative, not instantaneous. The current DataClient has timeout destruction but no public cancellation signal; this is new integration work. Last-view closure never stops acquisition. Every response also carries view generation, configuration revision, and query identity as a final stale-response guard. See [data request lifecycle](../vscode-extension/src/dataClient.ts#L134) and [read cursor cleanup](../src/main/java/org/triplehelix/wpilogmcp/mcp/ReadEndpoint.java#L240).

### Restoration and unavailable dependencies

Restore working-view UUIDs and configuration/checkpoints from named-server state and native backups; do not restore sample buffers or expired read handles. Reconcile backups against the current server revision rather than overwriting it. Opening a saved view deliberately forks a working UUID, whereas restoring a workspace resumes its UUID.

Missing or updated templates do not invalidate pinned configuration snapshots. Resolve moved managed sources through proven identity/aliases; unmanaged-file changes require fingerprint validation. An unavailable store or source leaves the recovered configuration inspectable with a specific unavailable state and retry/locate action. Do not silently substitute another source, recreate a missing store, or start acquisition. Ended live sessions reopen against their finalized source when it remains available.

## MCP interface and complete handoff example

Retain existing tools including `list_available_logs`, `list_entries`, `get_entry_info`, `resolve_signals`, `list_struct_types`, `get_statistics`, `align_entries`, `render_chart`, and `list_sessions`. `get_latest_values` is not history, and `wait_for_change` is not a replayable visualization feed. [Existing live tool semantics](../src/main/java/org/triplehelix/wpilogmcp/tools/LiveTools.java#L159).

Use **one MCP tool per operation**, named `visualization_<object>_<operation>`, with no multiplexing `operation` argument:

| Tools | Responsibility |
|---|---|
| `visualization_capabilities_get`, `visualization_source_inspect` | Supported versions, configuration schemas, type/role vocabulary, budgets and permissions; explicit source signals/status and durable identity. |
| `visualization_template_list`, `visualization_template_get`, `visualization_template_check`, `visualization_template_create`, `visualization_template_update`, `visualization_template_import`, `visualization_template_export` | Bundled/store catalogs, compatibility, revisioned persistence and portable transfer. |
| `visualization_view_list`, `visualization_view_get`, `visualization_view_create`, `visualization_view_update`, `visualization_view_clone`, `visualization_view_save`, `visualization_view_discard`, `visualization_view_delete`, `visualization_view_present` | Working instances, inspection, CAS configuration edits, saved checkpoints, and explicit follow/inspect/pause presentation changes. |
| `visualization_client_list`, `visualization_explorer_open`, `visualization_open_request_get` | Eligible windows, opening/reveal, and display acknowledgement. |

Each tool has its own parameter schema, output description, catalog registration, scenario, argument-generation rule, and conformance cases against isolated temporary state. Do not weaken claim checks or add a union-based operation dispatcher. All tools call the same `VisualizationService`; alternatives within a configuration are validated there with fixed declared fields and explicit selector kinds. See the [tool checklist](DEVELOPMENT.md#changing-or-adding-a-tool) and [existing schema builder](../src/main/java/org/triplehelix/wpilogmcp/mcp/ToolRegistry.java#L111).

Every envelope has `api_version`. Results include stable IDs, revision, effective configuration, bindings with basis, warnings, persistence class, `has_unsaved_changes`, `native_dirty`, checkpoint state, and relevant connection/update status. `connection_id`, where needed, is an optional request-level routing hint and is never copied into a durable `source_ref`. Use the existing result contract, including actionable errors and `partial`/`skipped` where appropriate.

Stage 2b adapters must preserve raw definition bytes until strict duplicate-key validation completes, either by validating at the JSON-RPC ingress before generic parsing or by carrying definition JSON as text. Re-serializing an already parsed object cannot detect discarded duplicate keys. Test duplicate keys through the real MCP/HTTP adapter, not only the contract parser.

Mutations require an idempotency request ID and expected revision where an object exists. Persist receipts so lost responses and MCP session retries do not create duplicate objects. Never automatically retry an edit against a newer revision.

Explorer registers a session-bound client/window identity, workspace-folder names, a short window label, supported capabilities, and attachment inventory through the locally admitted service. Window labels do not expose filesystem paths. Opening targets a view and an eligible client, never an "active log." `client_id` may be omitted only when exactly one eligible window exists; zero yields no display with a supported URI, and multiple yield `AMBIGUOUS_CLIENT` plus IDs and readable labels. An explicit but unavailable client never falls back to another. Creation, requested display, and acknowledged opening are separate results.

The control channel is **negotiated MCP server-to-client notifications**, separate from long-poll telemetry. Advertise a namespaced experimental visualization-notification capability. Send view-revision-changed, display-requested, and resync-required notifications through the session's existing HTTP `GET /mcp` SSE stream; add the equivalent serialized notification sink to direct stdio. Include server epoch, event sequence, affected view/revision, request ID, and intended client where applicable; never include sample buffers. Scope events to authorized registered clients/attachments. Notifications invalidate cached definitions; revisioned tool reads supply authoritative configuration.

This requires implementation at every layer: populate the currently ping-only SSE stream; make the extension MCP client consume it; add output support to direct `McpServer`; and extend `StdioBridge`, which already forwards JSON SSE events, with reconnection and event-ID handling. Use SSE `id:`/`Last-Event-ID`, bounded backoff with jitter, and re-registration after session replacement. Keep a per-session replay ring capped at 256 events or 1 MiB; overflow, expired replay, or server epoch change requires resynchronizing attachments, current revisions, and unexpired open requests. Deduplicate events and display requests by ID. Open requests expire after 60 seconds and never become an indefinite future popup. Direct stdio reconnects resynchronize through tool inspection. See [extension MCP client](../vscode-extension/src/mcpClient.ts#L128), [bridge stream forwarding](../src/main/java/org/triplehelix/wpilogmcp/mcp/StdioBridge.java#L295), and [direct stdio output](../src/main/java/org/triplehelix/wpilogmcp/mcp/McpServer.java#L64).

When Explorer is closed, creation still returns a recoverable view reference and a URI resolved only through configured trusted connections. No indefinite popup is queued. Subscriptions start when an eligible view attaches, not when an agent creates its definition.

### Example

This is the target Stage 3/4 live handoff. Stage 2b admits recorded sources only, uses
`configuration_json` text for MCP definitions, and does not yet request display.

All identifiers and signal names below are synthetic. The user says:

> Show drive velocity in radians per second, with current in amps below it. Follow the last 30 seconds.

The agent uses existing discovery and `visualization_source_inspect` to obtain a stable source reference and confirm exact names/types. If the source does not declare units, the supplied units remain explicitly user-declared. Stable source descriptors and alias-aware membership are proposed additive interfaces; existing discovery must not be assumed to supply all these fields today. The example's anchor timestamp is captured provenance, not a presentation time range.

Create the exploratory view without first creating a template:

```json
{
  "tool": "visualization_view_create",
  "arguments": {
    "api_version": 1,
    "request_id": "1c0f319a-e549-4f95-a215-e90cdb27fd44",
    "source_ref": {
      "origin_store_id": "1f0da397-39e4-4e16-ae0a-edc7199a3693",
      "kind": "session",
      "session_id": "96cedf93-c0ab-4898-a786-0a9ab9a604c1",
      "anchor_member": {
        "original_name": "capture-1.wpilog",
        "recording_started_at": "2026-10-14T12:00:00Z"
      }
    },
    "title": "Drive diagnostics",
    "configuration": {
      "schema_version": 1,
      "requirements": [
        {
          "id": "velocity",
          "entry": "NT:/Drive/Velocity",
          "field_path": null,
          "accepted_entry_types": [
            "double"
          ],
          "value_type": "number",
          "unit": {
            "symbol": "rad/s",
            "basis": "user_declared"
          },
          "selector_kind": "entry"
        },
        {
          "id": "current",
          "entry": "NT:/Drive/Current",
          "field_path": null,
          "accepted_entry_types": [
            "double"
          ],
          "value_type": "number",
          "unit": {
            "symbol": "A",
            "basis": "user_declared"
          },
          "selector_kind": "entry"
        }
      ],
      "time": {
        "recorded": {
          "mode": "full"
        },
        "live": {
          "mode": "follow",
          "window_sec": 30
        }
      },
      "links": {
        "cursor": true,
        "time_range": true
      },
      "layout": {
        "columns": 12,
        "items": [
          {
            "panel_id": "velocity",
            "x": 0,
            "y": 0,
            "w": 12,
            "h": 5
          },
          {
            "panel_id": "current",
            "x": 0,
            "y": 5,
            "w": 12,
            "h": 5
          }
        ]
      },
      "panels": [
        {
          "panel_id": "velocity",
          "type": "time_series",
          "title": "Velocity",
          "series": [
            {
              "requirement_id": "velocity",
              "axis_id": "left",
              "color": "#4E9BFF"
            }
          ],
          "axes": [
            {
              "axis_id": "left",
              "label": "rad/s",
              "scale": "linear",
              "range": "auto"
            }
          ],
          "display": {
            "legend": true,
            "line_width": 1.5
          }
        },
        {
          "panel_id": "current",
          "type": "time_series",
          "title": "Current",
          "series": [
            {
              "requirement_id": "current",
              "axis_id": "left",
              "color": "#F5A742"
            }
          ],
          "axes": [
            {
              "axis_id": "left",
              "label": "A",
              "scale": "linear",
              "range": "auto"
            }
          ],
          "display": {
            "legend": true,
            "line_width": 1.5
          }
        }
      ]
    },
    "connection_id": "5f11bf71-58a1-46cf-935a-573744376ad1"
  }
}
```

The response includes the complete normalized configuration above as `effective_configuration`. Other representative fields are:

```json
{
  "api_version": 1,
  "status": "ok",
  "view_id": "68714a26-5741-4163-b69f-cb590067ea5d",
  "revision": 1,
  "bindings": [
    {
      "requirement_id": "velocity",
      "state": "resolved",
      "entry": "NT:/Drive/Velocity",
      "declared_type": "double",
      "match": "explicit",
      "basis": "Exact entry name and declared type match; unit is user-declared."
    },
    {
      "requirement_id": "current",
      "state": "resolved",
      "entry": "NT:/Drive/Current",
      "declared_type": "double",
      "match": "explicit",
      "basis": "Exact entry name and declared type match; unit is user-declared."
    }
  ],
  "warnings": [
    {
      "code": "UNIT_USER_DECLARED",
      "requirement_ids": [
        "velocity",
        "current"
      ]
    }
  ],
  "persistence": {
    "kind": "recoverable_draft",
    "detached_expiry_days": 30
  },
  "updates": {
    "state": "detached",
    "configured_mode": "follow"
  },
  "display": {
    "state": "not_requested"
  },
  "has_unsaved_changes": true,
  "native_dirty": false
}
```

After listing eligible Explorer clients, request opening:

```json
{
  "tool": "visualization_explorer_open",
  "arguments": {
    "api_version": 1,
    "request_id": "9650fa4f-1d6c-46ca-ae59-9d4af2c8dd3b",
    "client_id": "201f25e2-a2ef-4a29-9b56-316a97aca895",
    "view_id": "68714a26-5741-4163-b69f-cb590067ea5d",
    "placement": "beside"
  }
}
```

The initial result says `display.state:"requested"`. Only Explorer acknowledgement reports `"opened"`. Attachment starts bootstrap and continuous updates; no further agent calls are needed.

The user moves Current above Velocity. Explorer commits revision 2. The agent's subsequent `visualization_view_get` returns that layout; `visualization_view_update` requires `expected_revision:2`. A stale call returns:

```json
{
  "api_version": 1,
  "status": "error",
  "error": {
    "code": "REVISION_CONFLICT",
    "expected_revision": 1,
    "current_revision": 2,
    "hint": "Inspect the current configuration before submitting another edit."
  }
}
```

Presentation commands such as "follow the last 30 seconds" target this same view. They update its policy and explicitly clear the historical-inspection override; presentation changes also check the current interaction revision so they cannot silently overwrite a newer gesture.

"Save this as Motor diagnostics" creates a template from the inspected view revision:

```json
{
  "tool": "visualization_template_create",
  "arguments": {
    "api_version": 1,
    "request_id": "7094b82d-52fe-4bca-823e-e9c513be3652",
    "target_store_id": "b70ebd9a-49e2-4d1b-bbd8-077198d251fb",
    "from_view": {
      "view_id": "68714a26-5741-4163-b69f-cb590067ea5d",
      "expected_revision": 2
    },
    "name": "Motor diagnostics",
    "description": "Velocity and current in linked plots."
  }
}
```

The response supplies a template UUID and revision 1. "Apply it to the other log" calls `visualization_view_create` with that pinned template reference and the other source:

```json
{
  "tool": "visualization_view_create",
  "arguments": {
    "api_version": 1,
    "request_id": "71436e52-b4f3-4c79-a23f-3d2fe86f98cf",
    "source_ref": {
      "origin_store_id": "b70ebd9a-49e2-4d1b-bbd8-077198d251fb",
      "kind": "recorded",
      "content_sha256": "412dd5a9c628d6a42f1cd81e29d50688cf543d5397127317b0f6960b38ad37de"
    },
    "title": "Motor diagnostics · Log B",
    "template_ref": {
      "store_id": "b70ebd9a-49e2-4d1b-bbd8-077198d251fb",
      "template_id": "8f75e932-8dea-46bd-a65b-ef2fc459cab8",
      "revision": 1,
      "origin": "store"
    },
    "connection_id": "09b795a0-8ebd-479c-b8ee-a3ae507a9c73"
  }
}
```

This produces an independent view using the recorded full-duration policy. Neither later template edits nor changes to either view alter the other.

## Staged implementation and acceptance

### Milestones

| Stage | Deliverables and review gate |
|---|---|
| **1. Contracts and native document lifecycle** | Define durable SourceRef/configuration schemas, persistent local server identity and bounded working state, transport-supplied caller context, and shared metadata CAS. Add the preparatory catalog exclusion. Use isolated fixtures for two independent custom documents and native lifecycle tests. Acceptance explicitly covers draft versus saved view, auto-save off/after-delay/on-focus-change, Save As destination alias/replacement, Revert, journal failure, offline edits, hot exit, GUI/agent edits during native Save, and native Split versus Duplicate and Open Beside on VS Code 1.101/current stable. No second capture registry or live follower implementation belongs here. |
| **2a. Recorded view foundation** | New optional source-overview provider, blank layout, keyboard-accessible layout editing, time series, latest/cursor table, saved views and first-store creation, Entries routing, multiple independent views and simultaneous sources. Gate on A/A/B view isolation and saved/draft restart recovery. Keep the legacy editor available by view type. |
| **2b. Reusable recorded-data preview** | Searchable bundled/store templates, exact-name and explicit-role compatibility, template editing/revisions, JCS export/import, statistics and bounded raw/event tables, and one-tool-per-operation MCP adapters. Add the notification plumbing needed for GUI/agent revision delivery. Gate on the complete recorded A/B, sharing, no-store first-save, and GUI/MCP revision-conflict workflows. This completes the minimum useful recorded-data preview. |
| **3. Continuous live views** | First gate: independently prove bootstrap-to-ordinal-delta correctness, including equal/backward timestamps, appends during bootstrap, interrupted pages and metadata-only changes. Then implement shared follow broker, session merge aliases and anchored recording membership, configured remote access, automatic panel updates, held/paused/following state, clock basis, catch-up, finalization, histogram/scatter and latency instrumentation. Gate on the full live workflow without interrupting acquisition and on recorded/live resource budgets. |
| **4. Complete handoff and replacement** | Complete explicit/sole-window routing, acknowledged opening, closed-Explorer handoff/recovery, notification replay/epoch recovery, retries, specialized-panel migration, accessibility, performance, and platform validation. Retire the old source editor only after all recorded/live/agent acceptance workflows pass. |

Stop for review between stages. Stage 1 establishes service/state/admission and native-document contracts; 2a establishes recorded source/view UX; 2b adds reuse and the MCP surface; 3 supplies continuous live semantics; 4 completes cross-client integration and retirement. The isolated Stage 3 follower proof can be researched in parallel because it does not depend on the UI, but integration waits for the shared contracts. Alias-preservation changes are tested before live saved references rely on them. Do not let the split silently defer multiple views, simultaneous sources, sharing, live updates, or MCP support beyond the named gates.

Before retirement, explicitly carry forward field/pose, console, REV controls, notebook handoff, Ask About Selection, and bounded Advanced Data. Browser aggregation/pivot behavior is an intentional deferred capability unless implemented through the shared analysis owner; it must not disappear without documentation.

Update the owning Explorer, read-API, operations, architecture, development, and tool documents, plus schemas/catalog/scenarios and Unreleased notes. Do not bump versions, commit, or publish as part of this investigation.

### Required validation

**Recorded workflow:** Open A through Open Source Overview or Reopen Editor With during preview (ordinary open defaults to the overview only in Stage 4); create/save a layout; apply it to compatible B; display two independent A views alongside B; edit one without changing the others; export/import the template into another store; restart and recover saved configurations and recoverable drafts.

**Live workflow:** Connect to an active capture; apply a saved template; verify automatic updates in every shipped panel; add independent views; inspect history while acquisition continues; pause display and resume following; reconnect with exact ordinal catch-up; expire a cursor and verify explicit rebootstrap within retained history; finalize without changing source/view identity.

**Agent workflow:** Inspect a source; create a multi-panel view; request and acknowledge display; edit through GUI; inspect/revise through MCP; reject stale revisions; save a template; apply it to another source. An agent-created live view must update without further calls. Repeat with Explorer closed and with multiple eligible windows.

**Failure and lifecycle coverage:** Missing and late signals, changed types, struct/array boundaries, unit differences, malformed/future imports, duplicate imports, concurrent store saves, lost responses, repeated calls, unavailable/read-only stores, moved logs, expired credentials, template deletion, stale data responses, window crashes, server restart, and closure during an admitted read.

**Review regression coverage:** Open a draft with each auto-save setting and prove there is no destination prompt; journal/offline/recovery failures cannot lose an acknowledged gesture. Native Save As reopens the exact selected URI with new saved/working UUIDs. Open a saved view on another laptop without the first laptop's connection ID. Merge two sessions and prove an alias selects only the anchor's recording/rollover members; test an older writer dropping aliases and require safe unavailability rather than widened scope. Exercise no-store creation without enrollment, exact versus role selectors, roles that become resolvable only after samples arrive, duplicate-key/JCS import cases, notification disconnect/replay/epoch reset, zero/one/multiple windows, and cancellation with another view still consuming the request. Test caller-context denial for remote, missing, spoofed, and batch contexts, and ensure thread reuse cannot inherit another request's authority.

**Numerical and ownership coverage:** Independently recompute results from generated records. Include duplicate/backward timestamps across page boundaries, appends during bootstrap, skipped/undecodable records, metadata-only changes, rollover, and finalization. Plant failures in revision checking, binding ownership, and cursor advancement and verify the guards fail. Closing one view must never release another view's `LogUse` or stop acquisition.

**Platform checks:** Focused Java and extension tests, one Gradle process per checkout, Linux/Windows lifecycle checks, and real VS Code 1.101/current-stable tests using isolated fixtures and homes. Verify actual rendered plots, CSP, worker/WASM loading, offline assets, resizing, keyboard operation, hidden/reveal behavior, native save/backup, and disposal.

### Performance gates and remaining uncertainty

Use declared workloads: large recorded logs around 1 GiB with thousands of entries, and a one-hour live run publishing 2,000 topics with 32 selected signals at 50 Hz, four visible views across two sources, and six plots per view.

Proposed targets—not measured results—are:

- Publication-to-display p95 below 500 ms locally and one second on a controlled LAN.
- Plot updates at most 4 Hz; derived analyses at most 1 Hz; status heartbeat within five seconds.
- At most 10,000 displayed table rows and the configured aggregate decoded-buffer budget per window across **recorded and live** views (64 MiB by default); per-series limits remain subordinate to that aggregate cap. Measure the default and a changed setting, and confirm the effective budget in capabilities. Cap encoded visualization responses separately at 64 MiB.
- One active job per query plan, coalesced demand, bounded network pages, and no growing work queue.
- No hidden-view rendering or raw-data fetching, no dropped acquisition records, and measured recorder overhead against a no-visualization baseline.

Instrument latency with correlated source/frontier IDs: capture-listener monotonic receive time, server frontier/delta publication time, extension receipt time, and webview post-animation-frame acknowledgement. Capture and browser clocks have different epochs. Report receive-to-publication and client-receipt-to-render durations within their own clock domains; end-to-end p95 additionally requires bounded-RTT offset calibration from server to host and host to webview, with uncertainty reported. Never directly subtract independent `performance.now()` and server timestamps. Render acknowledgement approximates paint scheduling, not physical display illumination. Keep instrumentation coalesced per frame/frontier, off the acquisition hot path where possible. See [listener receive time](../src/main/java/org/triplehelix/wpilogmcp/nt4/client/Nt4Client.java#L255) and [scheduler clock](../src/main/java/org/triplehelix/wpilogmcp/nt4/client/ClientScheduler.java#L25).

Measure JVM index/mapping costs separately from visualization buffers. Record actual added VSIX bytes and license inventory.

The material uncertainties are native custom-resource save/restore behavior, actual GridStack/CSP integration, authenticated remote-adapter behavior, and sustained performance on supported platforms. These are explicit pass/fail gates in the first stages; failed gates require design review, except for the explicitly specified native Save As refusal with a Save view… hint.

**First implementation milestone:** establish the durable source/view and caller-authority contracts, persistent local owner identity, and bounded state; prove the draft/saved native document lifecycle with isolated fixtures. Ordinal-follow correctness is the first Stage 3 gate. Once Stage 1 passes review, build the smaller Stage 2a recorded-view foundation.

## Revision 2 review disposition and verification

The [review](VISUALIZATION_PLAN_REVIEW.md) is retained unchanged. All 24 findings have a disposition:

| Finding | Revision 2 action |
|---|---|
| 1 | Separate non-native-dirty drafts from saved native documents; journal edits; honor the actual Save As destination through persisted aliases and replacement identity. Add auto-save/revert/hot-exit gates. |
| 2 | Remove `connection_id` from every durable source reference and JSON example; retain it only as optional request routing. |
| 3 | Add persistent `server_instance_id`, concrete local state location, health/guide exposure, and local ownership of working views. |
| 4 | Add immutable transport-supplied, per-request caller context with service-level checks and default denial. |
| 5 | Replace multiplexed tools with separately described/schema-tested tools per operation; update examples. |
| 6 | Specify readonly source-overview and working-view providers, optional preview registration, Overview navigation, and active-source Entries fallback. |
| 7 | Defer the capture registry; use merged-session aliases plus existing recording-membership proof. An alias alone must not include unrelated files after consolidation. Document mixed-writer limitations. |
| 8 | Add explicit first-save Create store… using the existing first-write path, without enabling enrollment; define `NO_WRITABLE_STORE`. |
| 9 | State the remote pit write limitation and identify a future authenticated template-byte route; existing byte upload remains log-only. |
| 10 | Ship Robot power, Driver Station state, and User-code timing bundles using actual resolver roles. Exact names remain the default for user templates. |
| 11 | Apply aggregate decoded/encoded budgets to recorded and live views and expose them through capabilities. |
| 12 | Specify negotiated MCP notifications over HTTP SSE/direct stdio, extension consumption, bridge replay/reconnect, bounded retention and resync. |
| 13 | Split Stage 2 into 2a/2b and move the follower proof to the first Stage 3 gate. |
| 14 | Require the metadata-directory exclusion before template writes and document old-reader unmanaged-stray behavior. |
| 15 | Label Duplicate and Open Beside and explicitly distinguish native Split moving the existing document. |
| 16 | Define the recorded robot-clock basis and unavailable-clock behavior. Do **not** adopt an implicit receive-time sample-axis fallback: current acquisition requires synchronization and preserves sample timestamps; receipt time remains freshness/latency evidence. |
| 17 | Define declared-type vocabulary, structured forms, and source-specific enumeration without fuzzy coercion. |
| 18 | Define RFC 8785 canonical digests, strict parsing, revision-safe Update existing/Import copy, and persistent idempotent import receipts. |
| 19 | Correct CSP allowance scope to pages, not individual panels. |
| 20 | Specify host request destruction, last-consumer reference counting, server cursor/waiter/lease cleanup, and cooperative cancellation tests. |
| 21 | Define correlated receive/publication/receipt/render instrumentation and clock calibration/error bounds; independent clocks are never subtracted directly. |
| 22 | Verify `/opt/homebrew/bin` Node/npm availability and run the focused compile/tests; withdraw the earlier PATH limitation. |
| 23 | Name and bound server working-state storage and extension recovery journals; reject quota exhaustion rather than evict unsaved work. |
| 24 | Permit omitted `client_id` only for one eligible window; list workspace names and window labels when selection is ambiguous. |

Verification during this revision:

- Confirmed Save As, auto-save, backup, and single-instance behavior against local VS Code 1.110 typings and the cached 1.101.0 workbench bundle, without launching an editor. Native lifecycle behavior of the proposed provider still needs the Stage 1 spike.
- Compiled the extension with Node `v26.11.1` and ran the existing `entriesViewBridge`, `entriesView`, `entriesInteraction`, `entriesTree`, `follow`, `plotMath`, and `plotWarnings` suites: **52 passed, 0 failed**. npm `11.20.0` is installed. These are existing pure-logic checks, not proof of new native lifecycle or follow protocol behavior.
- Reviewed current store identity, same-recording membership, first-store creation, caller context, schema builder, resolver roles, SSE/stdio forwarding, request cleanup, and clock ownership. The plan changes only this design document; no feature implementation or production data changes were made.

## Revision 3 review disposition

The [second review](VISUALIZATION_PLAN_REVIEW_2.md) remains unchanged. These are design decisions, not claims that their implementation gates have passed.

| Finding | Revision 3 action and gate |
|---|---|
| 1 | Save As lists readable store labels, resolved internally to immutable IDs. Persist destination mappings; reject native Save As with a Save view… hint if its Stage 1 lifecycle gates fail. |
| 2 | A failed or full recovery journal warns and permits edits persisted by the server. If neither persistence path works, edits remain visibly pending and are never acknowledged as durable. Test both outcomes in Stage 1. |
| 3 | The Stage 2a overview lists Unsaved views per source, with expiry and Resume for the same working UUID. Stage 1 supplies the bounded detached-draft state. |
| 4 | Successful Save view… clears draft undo history at the checkpoint; later changes start native undo history. Failure/conflict retains draft history. Prove the transition in Stage 1. |
| 5 | Stage 3 adds a 16–1024 MiB decoded-buffer setting, default 64 MiB, and reports defaults/range and effective window budgets through capabilities. |
| 6 | Preview overview access is explicit through Open Source Overview or Reopen Editor With; ordinary opening changes only at Stage 4. Recorded-workflow coverage states that caveat. |


## Stage 1 implementation evidence (2026-10-14)

The local implementation adds strict source/configuration contracts, persistent named-server
identity, transport-derived local authority, bounded working state, saved-view store CAS, and
catalog exclusion. The editable custom-document provider is registered only by an isolated
fixture extension. The existing Explorer editor and public tool set retain their behavior;
real source binding, overview navigation, public visualization adapters, and rendering remain
in later stages.

Validation on macOS: `./gradlew build license` passed (3,680 cases, 84 skipped), and `npm test`
passed all 395 extension tests. The initial runs on VS Code 1.101.0 and 1.141.0 each reported 18 automated lifecycle
assertions, including native Undo/Redo, both saved-view auto-save modes, GUI/agent save races,
Revert, single-instance Split, journal failure, and an actual process restart recovering an
offline edit. The independent Stage 1 review reproduced 1.101.0 but found native Undo
timeouts in both 1.141.0 reruns; the original current-stable Undo result was therefore unverified. The follow-up below records the corrected gate and fresh evidence.
Thirty-three fault plants were caught and restored, including the native
workbench save-barrier race. Detailed logs and the limitations are in the local evidence folder
`/private/tmp/wpilog-visualization-stage1/validation.md`.

Close cancellation reached the native dirty-document dialog, but the desktop automation tool
refused the click after successfully reading the app state. This gate remains unverified;
`WPILOG_VISUALIZATION_DIALOGS=1 npm run test:visualization` retains the explicit interactive
check. Native Save As deliberately refuses before mutation with the specified Save view…
hint; real picker labels, destination aliases and replacement-tab restoration are not proved.
The provider-level refusal was tested, not a successful native Save As workflow. Linux and
Windows native lifecycle runs are also outstanding. These results do not authorize Stage 2a.


## Stage 1 implementation review disposition

The [independent implementation review](VISUALIZATION_STAGE1_REVIEW.md) remains unchanged.
This follow-up addresses its startup and native-Undo blockers and four of its smaller findings.
It does not authorize Stage 2a or change the ordinary Explorer editor.

| Finding | Disposition |
|---|---|
| 1 | Named-server startup warns and falls back to ephemeral identity when visualization state cannot open; health and the guide expose `working_state_reason`. Real child-server tests cover unwritable, symlinked and file-blocked state parents. |
| 2 | The fixture now serializes editor switches through a webview acknowledgement, waits for closed-document disposal, and checks exactly one callback plus persisted configuration for each native Undo/Redo. Traces reproduced a late switch leaving the other editor active. Five consecutive current-stable lifecycle runs passed 20 Undo/Redo pairs each; they do not retroactively validate the original failed runs. |
| 3 | A backup revision conflict keeps opening the document, retains local recovery content, shows a comparison warning and leaves newer server content intact. |
| 4 | After server Save acknowledgement, the clean-tab barrier has a five-second deadline. Expiry releases queued gestures and repaints a warning; acknowledgement and disposal cancel the timer. |
| 5 | Stage 2b adapters must preserve raw JSON through duplicate-key validation, with actual adapter regression cases. The current parsed-object API cannot recover keys an earlier parser discarded. |
| 6 | Stage 2a client registration must enqueue attachment release on session expiry/removal and test crashed-window and replacement-session races. The current internal service supplies explicit detach and restart recovery only. |
| 7 | Invalid/unknown working records move intact to quarantine with a warning; valid views continue loading. Quarantine is retained for inspection outside the active-state quota. |
| 8 | Every budget check prunes expired records, so an update need not wait for a create to reclaim expired draft space. |

Follow-up evidence, including the failing-first runs, focus traces, fault plants, supported-editor
checks and final build, is recorded in
`/private/tmp/wpilog-visualization-stage1-review/validation.md`. The original evidence remains in
its separate folder. Native Close cancellation, Linux/Windows native-editor runs and combined
Java-service/editor integration remain outstanding. Native Save As still uses the specified
refusal fallback. Stage 2a remains subject to owner review.


## Second Stage 1 implementation review disposition

The [second implementation review](VISUALIZATION_STAGE1_REVIEW_2.md) accepts Stage 1 for Stage 2a
and confirms the product Undo/Redo behavior. Its two remaining findings are addressed locally:

- Fixture reveal now retries within one ten-second deadline and requires 200 ms of stable active
  editor state after the renderer acknowledgement. Cleanup explicitly reveals A before Revert.
  Native Undo/Redo still executes once and verifies the callback and persisted configuration;
  reveal retries do not hide a failed native command.
- The five corrections to the never-released prototype are folded into its Unreleased Added
  entry, rather than presented as fixes to released behavior.

Focused regression/plant evidence and native reruns are in
`/private/tmp/wpilog-visualization-stage1-review-2/validation.md`. Close cancellation, native
Linux/Windows runs, combined Java-service/editor integration and the Save As refusal fallback
retain their stated limits. Stage 2a implementation is not part of this follow-up.


### Stage 2a evidence and focused review follow-up

The independent Stage 2a review reproduced the original full build (3,613 passed, 84 skipped),
420 extension tests, both recorded editor runs, and the Stage 1 lifecycle checks. The original
evidence remains in `/private/tmp/wpilog-visualization-stage2a/validation.md`.

The three required corrections now have failing-first tests and plants:

- Saved records are validated independently. Invalid/newer files stay intact and appear in
  `unreadable`; record/byte limits return an explained partial listing. Opening a saved UUID
  reads only that file, even beyond the listing budget.
- Locator recovery drops unreferenced entries at restart, on draft expiry and under quota
  pressure, retaining every unexpired working view's locator. Opening an unchanged unmanaged
  file restores its deterministic locator.
- Source/requirements/series-use/time identify a data query separately from the configuration
  revision. Title, layout and style changes retain series, cursor values and in-flight replies.
  Programmatic uPlot cursor recalculations do not become new user cursor queries.

Focused validation: 67 Java tests, 38 Node tests and 24 regression plants pass; license and
claim checks pass. Both VS Code 1.101.0 and 1.141.0 pass seven native recorded-workflow checks,
including restart. Each run keeps Arrow/cursor counts at 2/3 before and after a title change
and keyboard resize, with no webview renderer or CSP errors. The evidence, intermediate failures,
source hashes and scope limits are in
`/private/tmp/wpilog-visualization-stage2a-review/validation.md`. This follow-up did not rerun the
full build or Stage 1 lifecycle round; it used the review's requested focused scope.

The unmanaged fingerprint limitation and route documentation placement are corrected. Stage 2b
still carries the endpoint-owned catalog cache, product-path native first-save fixture with
supplied pickers, request-counted hidden/reveal behavior, native overview Resume/Open-new-view,
and unavailable listings for older unmanaged fingerprints; see [IDEAS.md](IDEAS.md#94-reusable-visualization-documents).
Native Linux/Windows, the manual Close-cancellation click, the Save As gate and performance
claims remain unverified. No commit or publication is part of this follow-up.


### Stage 2b implementation and scope

The shared workspace now serves HTTP and 17 named MCP operations for capabilities, recorded
source inspection, template list/get/check/create/update/import/export, and working view
list/get/create/update/clone/save/discard/delete. Definition JSON travels as text on MCP to keep
duplicate-key evidence. Display handoff/client operations remain Stage 4; view presentation/live
state remains Stage 3. Revision notifications are implemented now across SSE, Explorer, bridge
replay and direct stdio, without creating telemetry or a deferred popup.

Templates use immutable built-ins, exact entry names or explicit resolver roles, pinned view
snapshots, store CAS and portable identity/revision/digest receipts. The RFC 8785 digest is
computed by the JCS library. The GUI supports default-copy import and explicit newer-revision
update. Statistics use original server samples; raw pages retain physical ordinals and replace
prior pages. The schema can describe later panels, but this preview admits plot/latest/statistics/
raw only, up to 32 series, and numeric field projections only. Template and working receipts
are atomic with their owner record, rather than a separate receipts directory; this avoids an
acknowledged definition without its retry proof. Store receipts are bounded to 64 entries per
map and 1 MiB per record; working records to 4,096 receipts and 4 MiB within the existing 256 MiB
live workspace budget. Exhaustion refuses for non-deleted views; explicit deletion moves retained proof into the separate bounded retry window described above.

The five Stage 2a minor carry-overs are implemented and tested: cached catalog ownership with
root invalidation, product-path native first save with supplied dialogs, request-counted hidden
views, native overview buttons, and unavailable listings for changed unmanaged fingerprints.
An overview left open across a restart still needs reopening to refresh its source reference.
Native Linux/Windows, manual Close cancellation, native Save As, aggregate resource budgets,
remote/live behavior and performance retain their disclosed staged limits.

Final local validation: `./gradlew build license` passed (3,654 passed, 84 skipped, no
failures); all 428 extension tests passed. VS Code 1.101.0 and 1.141.0 each passed 13 recorded
workflow checks, including a real Java/editor restart, and 20 native lifecycle checks with
20 Undo/Redo pairs. Fifty fault plants were caught and restored. The final build also checks
all 17 tool schemas/catalog/scenarios and their explicit frozen-reader-corpus classification;
the independent state-transition conformance covers successful calls for every operation.

Additional regression probes corrected serialized metadata byte accounting, overflow while a
notification reader sleeps, and malformed pinned references on admission/recovery. The first
full build found missing integration classifications/documentation sections; those checks now
pass and their plants fail. Earlier attempts, exact commands/times, screenshots, source hashes
and scope limits are recorded in `/private/tmp/wpilog-visualization-stage2b/validation.md`.
That implementation went through the independent Stage 2b review and focused fixes below.

### Stage 2b focused review follow-up (2026-10-14)

The independent review reproduced the macOS gate and 118 API/oracle checks, conditional on
correcting deleted-view capacity; the missing-template diagnostic goes with that fix. Both are
addressed in the accepted Stage 2b commit. Deleted records no longer consume live document/byte quotas
or pin unused source locators. Their separate 256-record/8 MiB retry budget evicts oldest first
and expires after six hours, including bounded recovery of legacy longer deadlines. Real live
quota refusals identify what holds capacity. A removed store template now yields
`TEMPLATE_UNAVAILABLE` through get/apply/export/update, including removal while an update waits
for the store queue. Existing views retain their copied configuration.

Nineteen new regression cases failed against the reviewed code before passing; 17 deliberate
plants were caught and restored. Focused execution logs, fixture corrections, final test counts
and hashes are in `/private/tmp/wpilog-visualization-stage2b-review/validation.md`. The independent
review's full build, Node and native results remain separate evidence; this focused follow-up
does not claim to rerun them. The maintainer authorized the commit and start of Stage 3;
`2fb2376` commits the 61 reviewed files with unchanged evidence hashes. It was not pushed.

### Stage 3 first-gate implementation

Stage 3 begins with the internal `OrdinalFollow` engine above native `ReadSnapshot` cursors.
Generated writer-owned prefixes are compared with the independent WPILOG reader, including
physical ordinals/offsets, equal/backward timestamps across pages, appends during bootstrap,
filtered/malformed records, interrupted exact-page retries, metadata/schema-only publications,
late entries, caller-proven rollover members, finalization and explicit identity/history refusal.
Response handles share a bounded encoded-byte owner; close during a read does not wait on the
writer or stop another consumer. The proof found a cold native live-reader failure: malformed
payload decoding threw instead of returning an ordinal diagnostic. Its regression failed on
the old reader before the native-only correction; legacy materialization behavior is preserved.

The SSE prerequisite from the review is also corrected with failing-first tests: optional
workspace failure still permits pings, stream delivery borrows the existing owner, and shutdown
drains streams and admitted calls before disposing it. Logs, mutation checks, source hashes,
fixture corrections and verification scope are recorded in
`/private/tmp/wpilog-visualization-stage3/validation.md`.

The accepted first-gate review's three contract refinements are implemented before route work:
oversized native payloads and conservative wire expansion produce explicit ordinal gaps;
metadata is sent once per prefix, with whole-envelope admission and metadata-only paging;
and pages pack multiple entries/members under global bounds. The shared native query adds an
explicit size-problem policy, preserved by its builders, wire encoding and session adapter;
ordinary queries retain their refusal. Regression tests failed against the prior engine/native
policy and the session adapter. The expanded proof includes 64 entry/member batches in one page,
32 entries under a three-record global cap, byte-boundary deferral, and metadata acknowledgement.
All 27 refinement plants are caught and restored; the metadata-page fixture was strengthened
after its first plant survived because even the incorrect gap would not fit that page.
Evidence and remaining validation limits are in
`/private/tmp/wpilog-visualization-stage3-review/validation.md`. The subsequent independent
[server integration review](VISUALIZATION_STAGE3_REVIEW_2.md) reran its engine proof against the
revised page format: 41 checks and 980 values bit-exact, in 22 pages. This is reviewer evidence,
not an additional implementer run.

The maintainer authorized the first-gate commit and continued implementation. `719f163` contains
the 21 validated first-gate/refinement files; it was not pushed. This is not acceptance of the
full Stage 3 live workflow.

### Stage 3 server integration checkpoint

Native HTTP follows now use the engine through one catalog/provenance adapter. Session merge
aliases survive manifest reconstruction, synchronization receipts and mirror copies. Alias lookup
selects the original captured recording, not every file in the merged calendar group; ambiguous
anchors refuse. Known final hashes can recover those exact members if an older writer dropped
an alias. Recovery retains the original capture provenance. Creation/next/DELETE routes have
bounded leases, a separate long-poll executor, 60-second idle expiry, explicit unavailable/expired
responses and same-Origin/path admission. Retry bytes do not bypass revoked access. Acknowledged
frontiers release native prefixes before waiting and retain immutable identity/type/count proof.
Publication checks retain only writer identity and revision, with no follower callbacks on its
loop or retained index. Final hashes are rechecked against the admitted file boundary; while a
member is live, writer identity rejects replacement even at unchanged sample counts. That live
identity is not yet persisted through finalization; the pre-broker proof requirement above closes
the theoretical replacement-plus-finalization gap.
Distinct projections of one entry have separate ordinal progress and use the native projector.

Failing-first regressions, plants and fresh build results belong in
`/private/tmp/wpilog-visualization-stage3-live/validation.md`. The source/lease integration is a
server checkpoint; configured remote visualization adapters, the shared window broker, automatic
panel updates, follow/inspect/pause and robot-clock states, histogram/scatter, aggregate decoded
budgets and latency/performance validation remain within Stage 3. The recorded preview and its
capabilities remain truthful about that scope. Five minor Stage 2b review follow-ups remain in
[IDEAS.md](IDEAS.md#94-reusable-visualization-documents). No new version or publication is part
of this checkpoint.

The independent server review reproduced the checkpoint's full build, extension suite and all
35 plants. Its real-transport client passed 54 of 56 checks; the two failures were the same
malformed-body classification on create and next. The focused correction classifies JSON parse
failures in the shared HTTP body reader as 400 `INVALID_REQUEST` with a short diagnostic, before
any source action. The regression also covers the snapshot route and preserves exact pending-page
retries after refused bodies. Failing-first, plant and current verification evidence is in
`/private/tmp/wpilog-visualization-stage3-live-review/validation.md`. The four design notes are
settled as the pre-broker requirements above; the route guide separately describes today's
behavior, including 410 `CLOSED`, numeric idle expiry, string wire expansion and member ordering.
