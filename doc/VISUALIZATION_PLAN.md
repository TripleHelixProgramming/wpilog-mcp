# WPILog Explorer reusable visualization design

Status: Stage 1 committed as `632b492`; accepted Stage 2a committed as `c6436d6` after owner
authorization. Accepted Stage 2b and its fixes are committed as `2fb2376`, and the Stage 3
first gate and review refinements as `719f163`. The accepted Stage 3 server integration checkpoint
is committed as `182cb00`. Its four pre-broker contract refinements and the small-page deferral
follow-up are committed as `6a0524f`; the shared broker and array fix as `9be98dc`, and the
accepted bounded-refusal/client-cap follow-up as `b57eaf6`. Immutable opened jobs are committed
as `14eff73`, and the accepted catalog-retry fix and review-9 disposition as `0f1cd3e`. Shared
recording-source inspection is accepted by review 10 and committed as `ffc1f63`. Queried-frontier
metadata is accepted by review 11 and committed as `7a8b656`, retaining 1 MiB panel pages.
Review 12 accepts the bounded inventory design with amendments. Its shared verified-hash
prerequisite is committed as `6d204a4`. The current checkpoint implements the shared paged
inventory service; the overview adapter and declaration-burst performance workloads remain
pending. The renderer remains recorded-only. Close cancellation
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

The server broker publishes cheap source/catalog/frontier changes without decoding or network work on the acquisition loop. The extension maintains one broker per server/window. Pending opens union signal requirements for the same anchored source and bootstrap windows; opened jobs are immutable and later joins bootstrap independently. It shares acquisition and data, not interactive state.

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

The server integration review added four **pre-broker requirements**. The owner authorized this
follow-up; the server portions below are committed as `6a0524f`. The shared broker checkpoint
below implements the client portions; live editor integration remains separate:

- **Terminal acknowledgement:** every page carries `ended`, true only when that page completes
  a finalized frontier. The terminal data page remains byte-identically retryable until
  acknowledged. Its acknowledgement releases retained data and returns HTTP 410 `FOLLOW_ENDED`,
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
- **Page fairness:** the engine rotates across `(member, entry, field)` selections, allowing
  at most 256 visited records per turn under the existing page work/byte limits. Retry pages keep
  the same order; only acknowledgement commits the next scheduling position. This places one
  scheduling owner above native cursors instead of making each client reorder queries. A
  byte-deferred selection is skipped for the rest of the page while the others keep packing;
  the first deferral receives the next page's first turn after acknowledgement. Test a
  chatty first entry with sparse later entries, tiny page budgets, deferred records, malformed
  gaps and a retried page. Lexicographic member names remain identities, not chronological order.

The implemented route contracts are documented in
[READ_API.md](READ_API.md#following-a-server-recording). Their failing-first and plant evidence
is separate from the previously accepted server checkpoint. None moves the remaining live
workflow out of Stage 3 or makes the recorded preview live.

Define the live last-N-seconds interval in the recorded source's robot/server clock domain, using the shared NT4 time estimate as the right edge and reporting `clock_basis`. Capture currently requires time synchronization before declarations and writes sample timestamps unchanged. Without a usable estimate, show Waiting for Time Sync and hold a previously valid edge with `clock_basis:"held_robot_edge"`. If no valid edge exists, use the latest published sample timestamp with `clock_basis:"last_sample"`; if neither exists, show the waiting state without fabricating a range. Do not mix receive timestamps into the sample axis. Receipt time remains appropriate for freshness and latency. A receive-time plotting mode would require a separate explicit projection and is deferred. See [capture clock admission](../src/main/java/org/triplehelix/wpilogmcp/capture/CaptureWriter.java#L407) and [sample timestamp preservation](../src/main/java/org/triplehelix/wpilogmcp/capture/CaptureWriter.java#L619).

Display behavior:

- New live views automatically follow the last 30 seconds.
- Panning/zooming switches to Inspect History; incoming data cannot move the interval.
- Resume Following returns to the configured rolling window.
- Pause Display freezes the displayed prefix, not acquisition.
- Source health distinguishes acquisition, transport heartbeat, publication age, and history availability.
- Late records invalidate affected visible reductions; update bounded data frames without rebuilding layouts or rereading the complete recording.

Hidden views stop rendering and their data work. Release decoded buffers after 30 seconds hidden; reveal reboots the requested interval. Apply one configurable aggregate decoded-data budget per VS Code window to recorded and live sources, counting any decoded broker caches and per-webview copies. The `wpilog-mcp.visualization.decodedBufferMiB` setting defaults to **64 MiB** and accepts integers from 16 to 1024 MiB. Raising it permits longer exact recorded series at greater memory cost; lowering it requests reductions sooner. Capabilities report the default and range, plus each attached window's effective `decoded_buffer_budget_bytes`; never report a caller-supplied value as the server's own allocation. Stage 3 implements and validates this setting with the broker. Request server reduction or bounded paging before admission would exceed that budget; do not load millions of recorded samples per series. Publish the budget and table/page limits through capabilities. Bound encoded acquisition separately to 64 MiB in the new visualization broker. The current core reserves two page limits per active job and retains no history cache. Any later encoded history cache must share that budget with in-flight responses and retained frontiers; a cache is not an additional 64 MiB allowance. The owner chose uninterrupted peers after review 5; the immutable-job checkpoint below implements that acquisition contract before live panel wiring.

Add an `AbortSignal`/owned HTTP request handle to data and follow requests. Closing/hiding a view or replacing its query destroys that request only when its last consumer detaches, and closes its follow lease. Server cancellation closes admitted cursors, removes follow waiters, and releases snapshots on disconnect, lease closure, or deadline. Disconnection is detected at response writes and cursor batch boundaries, so cancellation is bounded and cooperative, not instantaneous. The recorded DataClient already supports cancellation; the native follow adapter now supplies the same owned lifecycle. Last-view closure never stops acquisition. Every response also carries view generation, configuration revision, and query identity as a final stale-response guard. See [data request lifecycle](../vscode-extension/src/dataClient.ts#L134) and [read cursor cleanup](../src/main/java/org/triplehelix/wpilogmcp/mcp/ReadEndpoint.java#L240).

### Restoration and unavailable dependencies

Restore working-view UUIDs and configuration/checkpoints from named-server state and native backups; do not restore sample buffers or expired read handles. Reconcile backups against the current server revision rather than overwriting it. Opening a saved view deliberately forks a working UUID, whereas restoring a workspace resumes its UUID.

Missing or updated templates do not invalidate pinned configuration snapshots. Resolve moved managed sources through proven identity/aliases; unmanaged-file changes require fingerprint validation. An unavailable store or source leaves the recovered configuration inspectable with a specific unavailable state and retry/locate action. Do not silently substitute another source, recreate a missing store, or start acquisition. Ended live sessions reopen against their finalized source when it remains available.

### Bounded recording inventories

**Design accepted with review-12 amendments; the shared server operations are committed and
overview paging is implemented, pending review of its correction round. The performance workload
remains pending.** Queried follow metadata solves delivery after selection; it does not solve
discovery. Before the paged overview checkpoint, discovery requested every member's descriptor
and a combined name list in one 4 MiB reply. Review 11 confirmed that 20,000 topics could follow
at 1 MiB but could not be selected through that earlier overview. The legacy inspection result
and its cap remain; the overview now explicitly negotiates paged recording discovery.
Recorded single-file inspection is unchanged.

Source anchors checked for this design: the [legacy inventory builder](../src/main/java/org/triplehelix/wpilogmcp/visualization/RecordingInventory.java)
encodes all member descriptors before its summary; the [shared source service](../src/main/java/org/triplehelix/wpilogmcp/visualization/RecordedSources.java)
closes its admitted prefix after each description; the [recording admission owner](../src/main/java/org/triplehelix/wpilogmcp/read/server/CatalogRecordingSource.java)
owns grants, member limits and finalized hash checks. The [overview controller](../vscode-extension/src/visualization/recordedPreview.ts)
now uses the bounded list/detail operations for recordings. The operations and limits below
govern the additive server API; the latest checkpoint dispositions record the review state.

Choose a **short-lived, detached inventory snapshot**, with compact signal pages and separate
selected-entry details. Stateless keyset paging over unique ordered names can avoid duplicates,
but reopening admission on every page repeats catalog inventory scans during a growing capture
and does not describe one fixed prefix. A detached listing pays that admission cost once. Raising
the full-response cap only moves the ceiling and makes every overview carry more unused data.
A fixed listing is discovery evidence, not a continuously updated table or a follow cursor.

#### Ownership and admission

The workspace service owns the inventory, filter, ordering, cursors and limits for both HTTP
and MCP. Reuse the existing recording admission, catalog lease, alias/provenance rules,
writer/hash checks, index/member limits and shared clock facts. Extract summary construction
from the current inspection owner so paged and legacy modes do not make different deductions.
The extension displays returned rows; it does not merge member declarations, resolve roles,
infer units or compile schemas. No extra recorder, store scan policy or telemetry cache is added.

Opening freezes one admitted prefix. Copy only compact immutable listing facts; close every
member prefix and catalog lease **before publishing the inventory handle**. Retain neither
mapped log buffers nor complete JSON descriptors, raw metadata strings, schema graphs or
decoded values. Count and reserve the detached objects/strings before allocating them. A
failed, cancelled or oversized build releases everything and publishes no partial inventory.
The existing 64-member and 128 MiB native index admission still applies separately.

Before adding the build deadline, share successful finalized-member hash verification across
workspace source admissions: legacy inspection, future inventory opens and selected details
must use one owner. Retain only a bounded memory of canonical path, file boundary and expected
manifest hash, never payloads or open mappings. A miss hashes the file and verifies that its
boundary stayed unchanged before remembering success; failures are not remembered. Changed
path, size, modification time, file identity or manifest hash require verification again.
Always recheck current catalog/path grants and the admitted native snapshot boundary. The
memory is an optimization, not authority or proof against edits that preserve all observed
filesystem attributes. Expiry/eviction and a new workspace make admission cold again. Measure
cold and warm rollover costs separately; this does not promise an unmeasured cold build can
meet ten seconds on every disk.

For each exact entry name, retain its declared type, per-member counts, membership and
declaration agreement/conflict. Compare raw metadata through the existing owner while the
prefix is open; a digest is an identity token, not evidence for a unit or a binding. Identical
declarations may expose the exact summed count; conflicting declarations retain their per-member
facts and do not receive a fabricated aggregate. Counts are decimal strings, with checked sums.
Each row has an opaque inventory-local ID. Do not persist that ID in a view or template.

#### Server operations and payloads

All new operations require the existing trusted-local authority; reading additionally requires
current path/store admission. Inventory IDs use unguessable random values and bind to the
opening transport session (the stdio process is its session); another local session cannot
read, replay or close that handle. Session removal releases its inventories. Close remains
available to the opening session after grant revocation and reveals no contents.
These tools and routes are registered. HTTP paths below are relative to
`/visualizations/v1`; HTTP and MCP call the same workspace operations.

| Operation | HTTP | MCP | Result |
| --- | --- | --- | --- |
| Open inventory | POST `/source/describe` with `inventory_mode: "paged"` | `visualization_source_inspect` with the same optional mode | Explicit `inventory_mode: "paged"` echo, server epoch, inventory ID, fixed summary/member facts, exact totals, hard expiry and limits; no full entry/schema arrays. |
| List entries | POST `/source/entries/list` | `visualization_source_entries_list` | A bounded page from that inventory, exact matching/total counts, continuation and unavailable-row facts. |
| Read selected details | POST `/source/entries/get` | `visualization_source_entries_get` | Freshly admitted details for up to 32 inventory row IDs: exact names, member declarations, counts and native schema dependencies, with current declaration/binding evidence. |
| Release inventory | POST `/source/inventory/close` | `visualization_source_inventory_close` | Idempotent release acknowledgement. |

Omitting `inventory_mode` preserves today's full result; unknown values refuse. Advertise
`recording_inventory_paging` and its limits through capabilities only when implemented. The
client must verify the mode echo before treating a reply as paged. An old server ignoring the
option cannot accidentally trigger a full inventory download: check capabilities first, retain
the 1 MiB response cap for the paged request, and explain that the server needs updating. Neither
request intent nor a truncated `entries` array proves negotiation.

The open summary includes the anchored source reference, recording ID, inventory ID, finalized
state, member identities/boundaries/writer or final-hash proof, fixed clock/time bounds and
damaged/truncated facts. It reports `total_entry_names`, `conflicting_entry_names` and
`unavailable_entry_names`; it contains no duplicate name list. Clock facts are labeled as taken
at inventory creation; listing activity never advances the display clock. Oversized summary
facts refuse explicitly rather than dropping members or diagnostics.

List requests carry the inventory ID and either an initial literal name substring/exact-type
filter or an opaque continuation. Filter semantics are shared server code: case folding uses
`Locale.ROOT`; type matching is exact, not a type family. Order by exact name with the server's
UTF-16 string order, then stable member identity within a row. The client never computes an
offset or re-sorts pages. A cursor binds the server epoch, inventory, filter, row limit and next
position, authenticated by a per-process secret. Changed arguments, foreign-server handles,
cursors for another inventory or tampered cursors refuse before work. A retry returns the
identical data page, including its deterministic continuation; no single mutable “next”
position is advanced by a read. Changing a filter starts a new traversal of the same snapshot.

Each page reports `returned`, `total_matching`, `total_entry_names`, `complete` and
`next_cursor` (null only at completion). Counts mean names, not summed member occurrences.
The MCP adapter applies the ordinary result contract: a cut list carries `limits` with the
true matching total, returned count and effective page limit. A zero-match page has
`status: "no_match"`, `looked_for` and a hint to change the filter or refresh the inventory;
it never implies an empty recording. HTTP and MCP retain identical page facts and cursors
inside their respective envelopes. Every expiry refusal gives the explicit remedy to reopen
from the anchored source reference and restart the traversal; old cursors cannot cross that
refresh. Compact rows carry names,
types, counts and conflict status, not raw metadata, schema text or filesystem paths. A single
unrepresentable name/type row becomes an explicit unavailable row with a bounded display
preview, original lengths, row ID and reason; the preview is never usable as a binding name.
Unavailable rows have their own paged `unavailable` filter and exact count in every page;
ordinary name/type matching excludes them and says so. A filter must fit its authenticated
continuation within the 64 KiB request cap; otherwise admission explains how to shorten it.
This avoids one huge declaration
hiding other signals, without pretending its full text was searched. Large *metadata* alone
does not make a name unavailable because the listing never retains that text.

Selected details reopen through the same source owner and project only the requested entries
and their transitive native schemas. They are **fresh evidence**, not the inventory's original
sample-count boundary: return a distinct detail revision, current member facts and counts.
Compare the selected names/types/metadata with their original listing tokens. A changed or
removed selected declaration returns `INVENTORY_CHANGED` with an explicit refresh remedy.
In particular, a selected name whose declared type changed never receives new-type details
under an old-type row ID; refresh the listing and select/validate that name again;
ordinary count growth is allowed and labeled. New members, writer replacement or changed
recording identity require a fresh inventory. Finalization may retain the inventory when
membership and writer proof still match; fresh details carry the verified final hash. Share
the read owner's identity/continuity rules, extracting that policy where needed rather than
writing a second comparison in visualization code. Invalid/missing schemas and cross-member schema
conflicts remain explicit, and details do not mark a conflicting row compatible. A selected
detail set too large for 1 MiB refuses; the list and other entries remain usable.

Binding stays a separate shared-server decision over an admitted recording prefix. Templates
and resolver roles must inspect the complete native entry set, never only the page the user
has loaded. The subsequent binding/follow admission rechecks the selected evidence; no
inventory cursor authorizes access or substitutes for the follower's full history proof. New
follow jobs still use immutable queries, 1 MiB pages and queried-frontier negotiation. Snapshot
handles, cursors and detail revisions are ephemeral and never enter saved source references.

#### Resource and lifecycle bounds

These are enforced server admission limits, not measured heap or latency claims. Capabilities
publish the server limits; the extension row remains pending. Adjustments need recorded evidence.

| Resource | Bound and outcome |
| --- | --- |
| Inventory handles | 8 total per server workspace, at most 2 per opening transport session (including builds). A third from one session or a ninth overall refuses with retryable `RESOURCE_LIMIT`, without evicting another overview. Capabilities publish both limits. One window cannot consume all eight; this is a per-session share, not a per-user identity guarantee. |
| Detached listing | 32 MiB accounted allocation per inventory, 64 MiB aggregate including builds; 200,000 member-entry occurrences per inventory. Exceeding a structural limit is non-retryable and explained. |
| Page/details/summary | 1 MiB encoded per reply, including the transport envelope; at most 256 names per list page and 32 selected detail names. The shared owner sizes against the larger HTTP/MCP encoding so both adapters return the same data page within the cap. Requests at most 64 KiB of raw UTF-8 bytes, with name-filter text at most 256 Unicode code points and at most 16 exact types. MCP counts the entire HTTP exchange, including batch syntax and whitespace, for each inventory operation; stdio counts its message line without the newline. Inventory-containing MCP batches additionally cap the aggregate encoded reply, including array framing and other tools' replies, at 8 MiB. Overflow stops remaining calls and returns non-retryable HTTP 413 with a warning that earlier calls may have executed; inspect their state before resubmission. |
| Response work | At most 4 concurrent data-producing inventory requests (open/list/details), no waiting queue; reserve two reply limits per request in a separate 8 MiB server response pool. Close and expiry bypass these slots. Temporary saturation is retryable; refusals preserve normal session/JSON-RPC validation, notification suppression and mixed-batch controls. Refusal I/O uses bounded streaming workers and the progress watchdog. Exhausting final-refusal capacity closes the exchange before headers, without an unread-body drain on control workers. |
| Inventory construction | At most 2 read workers, no waiting build queue; reserve the per-inventory allowance before starting. A 10-second response deadline cancels publication and requests cooperative cancellation. |
| Lifetime | 60 seconds idle, 5 minutes absolute; successful access renews only idle expiry. Close, idle/absolute expiry and shutdown reclaim state. Restart invalidates handles with the server epoch. |
| Extension (inspection checkpoint) | At most two list pages retained per overview, one in-flight list/detail request per overview; use the broker's shared 64 MiB encoded budget owner, reserving twice the reply cap before sending. Host/webview copies share a fixed 64 MiB inventory/display allowance; integration with the configurable combined recorded/live sample budget remains panel work. |

The server metadata and response pools are separate from native indexes and the follow pool;
measure and report all of them, not just page bytes. Build reservations downgrade to the
actual accounted detached size on success. Slow filesystem I/O may outlive a cancelled response:
it retains its worker/reservation until unwound, cannot publish a handle, and releases its
prefix in `finally`. Test this explicitly; do not claim that cancelling a future interrupts an
arbitrary OS read. No inventory I/O occurs on the NT4 loop, store queue or under a workspace/
session registry lock. Reclamation claims ownership under a short lock and closes outside it.

Check current local authority and source grants before **every** page/detail response,
including replay; the opaque token is not a permission grant. Reuse the catalog owner for that
check without reopening/hash-verifying payloads for list pages. A detached list may describe
an older authorized prefix after ordinary growth or rename; that is its labeled snapshot
contract. Details and binding perform new identity/hash admission. Revocation closes the
inventory and stops delivery. Existing transport/session admission remains; no per-account
isolation or new remote mutation surface is implied by the handle.

Use explained typed refusals: 400 for invalid mode/filter/cursor, 403 for access denial, 410
`INVENTORY_EXPIRED` for an expired/old-epoch handle, 409 `INVENTORY_CHANGED` for stale selected
identity/declaration evidence, and 413 `RESOURCE_LIMIT` with a boolean `retryable` distinguishing
temporary reservation pressure from fixed limits. Preserve existing source-refusal codes where
the source owner already supplies them. MCP carries the same codes/remedies in its usual
result envelope. Return an explicit retry delay for temporary admission pressure; never spin.

#### Overview behavior and implementation gate

Show catalog facts immediately, then the paged summary and the first matching page. Use inline
search, exact-type filtering, visible result counts and keyboard-accessible Previous/Next
controls; do not fetch the entire inventory in the background. Loading another page replaces
the bounded row window. Preserve selection by exact name plus source identity, not row position.
Fetch details only when needed for inspection/selection. A late reply is accepted only for the
same source, inventory, filter and view generation; close/filter/refresh abort obsolete work.
Refresh explicitly creates a new inventory and retires the old one after successful admission;
failure preserves the readable old snapshot with its age while it is still valid. A visible
overview automatically opens a fresh inventory at absolute expiry, with at most one refresh
in flight, preserving filter and exact-name/source selection and revalidating selected details.
An expiry race follows the same refresh path. A failed refresh shows the reason and an explicit
Retry action with bounded backoff; it keeps selection/configuration and marks old rows expired
rather than presenting an empty recording or repeatedly spinning. Hidden overviews may expire
silently and open a fresh inventory on reveal. Hiding or closing releases its handle; no
heartbeat keeps hidden snapshots alive. Any fallback on an
older server remains visibly limited by the legacy 4 MiB contract.

Implement and review this prerequisite **before panel wiring**:

0. Build the shared verified-hash memory first, with real hash-read counts for repeated
   inspections and changed manifest/file boundaries, failed verification, eviction, expiry,
   concurrent callers and workspace close. Grant checks must still run on reuse.
1. Shared server service, additive HTTP/MCP operations, capabilities and typed lifecycle.
   Gate on independent exact enumeration of 5,000/10,000/20,000-topic recordings with two
   members, both overlapping and disjoint names. Include counts/conflicts, Unicode filters,
   no matches, exact caps, a 3 MiB metadata value, an unavailable oversized row, selected
   schema closure/problems, malformed/foreign/replayed cursors and unchanged legacy replies.
2. Interleave declarations, appends, alias changes, revocation, finalization, expiry and
   restart between pages. The frozen traversal has no duplicates/omissions; refresh sees
   new declarations. Prove fresh detail evidence and revalidation, bounded construction/
   cancellation, no retained mappings after open, and independent inventory/follow lifetimes.
3. Wire only overview discovery/selection to this service. Drive both supported VS Code
   versions through a source above the former full-inventory cap, search/page/detail/refresh,
   stale replies, automatic visible absolute-expiry refresh with retained selection, hidden/closed
   views and refusal states. Use the real Java server, and prove
   HTTP/MCP parity and no second binding logic. Live layout controls remain gated until the
   separate recording binding and panel stages pass.

Each implementation change needs failing-first evidence and mutation plants, including a
moving-prefix pagination plant, dropping a row/count, bypassing a byte/handle limit, accepting
stale or foreign cursors, skipping grant rechecks, leaking a cancelled build, and binding from
only the loaded page. Compare repeated per-page fresh admission with one detached open on the
season-sized catalog, recording catalog scans and cold/warm hash reads separately. Run the
declaration-burst gate below alongside these correctness cases; neither this proposed design
nor a successful small fixture proves season-sized performance.

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

Also gate admission and prefix refresh on recordings with 5,000 and 10,000 topics **per member**,
with two rollover members and 32 selected signals. Declare name/metadata lengths, schema shape,
sample counts and whether names overlap across members. Check the first frontier, subsequent
metadata-only prefixes, schema changes, backward-timestamp appends, finalization and exact replay
at 1 MiB. Measure frontier/page bytes and allocations separately from full inspection bytes.
Include large unselected metadata and nested selected schema dependencies; an oversized selected
declaration must still refuse explicitly. Full inspection above its 4 MiB cap remains an
explained refusal, not an empty signal list. Implement and review the
[bounded inventory design](#bounded-recording-inventories-proposed-before-panel-wiring)
before claiming the complete panel workflow supports these sources.

**Declaration burst with 32 leases (review 11; not yet run).** Start one synthetic recording
with 100 declared topics, then open 32 independent queried-frontier leases with 1 MiB pages
before publishing more declarations. Each immutable query names 28 existing signals and four
signals declared during the burst. Run separate 5,000/10,000/20,000 final-topic workloads:
announce the remaining topics as fast as the fixture can, then in a controlled 1,000-declaration/
second run, with independently counted 50 Hz values continuing on the 28 selected signals.
Include nested schema declarations, a 3 MiB **unselected** metadata value, late selected
declarations and equal/backward timestamps. Declare the name/property sizes, overlap and
schema shape in the evidence. Repeat with two rollover members and with the season-sized
catalog workload below; compare with the same recording and zero follow leases.

Read actual prefix-open counts from a server test seam or metric, per lease and total; never
infer them from HTTP polls. Measure coalesced declarations
per prefix, metadata-only/data pages, bytes, native index/mapping work, catalog scans,
CPU/allocation/GC and peak retained/in-flight memory. Correlate declaration receipt to the
next observed selected frontier, and selected-sample receipt to delivery, within measured
clock domains. Keep the complete native history proof and strict catalog reuse while measuring;
do not optimize away unselected publications without a separate correctness design. The
source may coalesce several publications into one opened prefix, so do not assert one page
per declaration as the oracle.

All 32 handles remain open. First rotate polls in two cohorts with at most 16 simultaneous
waits, matching the existing wait-worker pool. Then deliberately let all 32 wait together:
record expected retryable pool-pressure refusals and bounded backoff, with no healthy peer
reset or permanent stop. This distinguishes declaration cost from intentional poll saturation.
The 32 jobs already reserve the full 64 MiB client encoded allowance; additional inventory
requests must refuse locally until capacity is released. Use 31 follow jobs plus one inventory
request for the overlap case, proving the same aggregate budget rather than adding an allowance.

Pass only with exact final selected counts/physical ordinals/values, no acquisition loss,
unchanged peer generations, byte-identical replay, bounded queues/reservations and eventual
zero owned resources after closure. Include health and close latency and evaluate source-to-
delivery delay against the local 500 ms/LAN one-second target below; report p50/p95/max,
refusals and calibrated uncertainty, not only a passing percentile. Before panel integration
this establishes server/broker behavior only; rerun with renderer acknowledgements for the
publication-to-display gate. Sustained throughput and native platform runs remain unverified.

Proposed targets—not measured results—are:

- Publication-to-display p95 below 500 ms locally and one second on a controlled LAN.
- Plot updates at most 4 Hz; derived analyses at most 1 Hz; status heartbeat within five seconds.
- At most 10,000 displayed table rows and the configured aggregate decoded-buffer budget per window across **recorded and live** views (64 MiB by default); per-series limits remain subordinate to that aggregate cap. Measure the default and a changed setting, and confirm the effective budget in capabilities. Cap encoded visualization responses separately at 64 MiB.
- One active job per query plan, coalesced demand, bounded network pages, and no growing work queue.
- No hidden-view rendering or raw-data fetching, no dropped acquisition records, and measured recorder overhead against a no-visualization baseline.

Instrument latency with correlated source/frontier IDs: capture-listener monotonic receive time, server frontier/delta publication time, extension receipt time, and webview post-animation-frame acknowledgement. Capture and browser clocks have different epochs. Report receive-to-publication and client-receipt-to-render durations within their own clock domains; end-to-end p95 additionally requires bounded-RTT offset calibration from server to host and host to webview, with uncertainty reported. Never directly subtract independent `performance.now()` and server timestamps. Render acknowledgement approximates paint scheduling, not physical display illumination. Keep instrumentation coalesced per frame/frontier, off the acquisition hot path where possible. See [listener receive time](../src/main/java/org/triplehelix/wpilogmcp/nt4/client/Nt4Client.java#L255) and [scheduler clock](../src/main/java/org/triplehelix/wpilogmcp/nt4/client/ClientScheduler.java#L25).

Measure catalog work on a season-sized synthetic store during active recording, alongside a
small-store baseline. Declare robot, session, manifest and payload counts, filesystem/storage,
append cadence and active lease count; use the approved 1 MiB panel pages. Include simultaneous
and staggered leases and a quiet followed source while another recording grows. Cache reuse is
strict: payload growth invalidates the inventory. Concurrent refreshes share one scan, but
staggered calls can scan again after further growth. The five-second external-change check is
an idle fallback; changed-prefix admission can refresh sooner. Measure actual refresh count and
p50/p95/max duration, filesystem operations, CPU/allocation, read-worker occupancy, and follow
latency/recorder overhead as store size and lease count increase. Judge these against the existing
live latency and acquisition targets; no season-sized catalog cost has been measured yet.

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
loop or retained index. Final hashes are rechecked against the admitted file boundary. At this
checkpoint writer identity rejected replacement only while a member remained live, even at
unchanged sample counts. The pre-broker follow-up below persists that identity through finalization.
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
settled as the pre-broker requirements above. The accepted fix-round review independently reran
the full build (3,737 passed, 84 skipped), extension suite (428 passed), and HTTP probe (54/54).

### Stage 3 pre-broker contract follow-up (2026-10-15)

The native route now marks the terminal page with `ended`, retains exact retries, and retains
410 `FOLLOW_ENDED` for repeated terminal acknowledgements while releasing data. Every 413 carries
`retryable`; transient budget pressure preserves the submitted continuation even after retiring
its preceding page. Testing this boundary also found and corrected oversized follow request
bodies returning a 409 source error instead of a structural 413.

The capture writer owns a per-file incarnation, shared with the live index and captured in
immutable open/closed manifest facts. Recovery, relocation, peer copies and mirrors preserve it.
The engine compares proof across finalization, including a replacement with identical entry
types/counts after its live index is unloaded. Legacy missing proof refuses that transition;
an explicitly new finalized bootstrap can use the verified hash. Initial metadata must agree
with the currently registered live writer. Admission and rollover remain asynchronous with the
existing store queue; no per-value identity write was added.

Scheduling rotates member/entry/field selections in turns of at most 256 physical records, with
page-wide work and per-query limits preserved. Only acknowledgement commits the next turn;
malformed, filtered and byte-deferred records cannot silently lose their ordinals. The
independent-reader comparison continues to check values, timestamps, offsets and ordinal gaps.

Evidence, exact commands, failing-first corrections and plant restores are recorded under
`/private/tmp/wpilog-visualization-stage3-contracts/validation.md`. This is a server-contract
checkpoint for review. The shared broker, live editor and remote/resource/performance gates
remain outstanding; no native editor behavior changes in this follow-up.

### Small-page deferral follow-up (2026-10-15)

The independent fourth Stage 3 review accepted the four contracts and reproduced the full
build, extension suite and plants, plus its own 73-check HTTP and 47-check engine probes.
It found that, at a 64 KiB page budget, a 40,000-byte raw record could repeatedly lose space
to the first 256-record numeric turn: deferral ended the page and advanced the schedule past
the waiting selection. The default 4 MiB page did not expose that scenario in the review.

Resolve this before the broker chooses its page size: smaller budgets are already supported
by the engine and should not depend on the broker avoiding this case. The engine now skips
byte-deferred selections for the remainder of the page, keeps packing the others, and gives
the first deferred selection the next page's first turn. Acknowledgement still owns both
progress and scheduling; retained retries perform no reads. No default budget changed.

The 64 KiB regression has 5,000 numeric records and ten 40,000-byte raw records, with another
case adding a second large selection and one capping the numeric query. It checks next-page priority, remaining-space packing,
bounded lookahead and every value, timestamp and ordinal through completion. Failing-first
tests, plants and final verification are recorded under
`/private/tmp/wpilog-visualization-stage3-deferral/validation.md`. The broker remains the next
Stage 3 checkpoint; this follow-up changes no extension behavior.


### Stage 3 shared broker checkpoint (2026-10-15)

The original extension acquisition checkpoint is committed in `9be98dc`, including the array fix and
review-5 disposition, before live panel wiring. The following describes that historical checkpoint;
the immutable-job follow-up below replaces its shared-reset behavior. Its broker
is window/server scoped and unions entry/field demands for identical anchored sources and bootstrap
windows. Different historical windows retain separate follows. A joining consumer triggers an
explicit shared reset because the core stores no history cache. Existing view configurations and
interactive state are independent; consumers clear only their data generation. Departing consumers
leave the current query superset until the next reset; last departure aborts and DELETEs.

One immutable page is borrowed by all interested consumers. The broker acknowledges only after
they finish; a failure or five-second deadline removes that consumer with a reason. It carries
view/generation/revision/query guards, retains frontier metadata between pages and preserves native
tagged values and ordinal problems. A 204 does not end a stream; a terminal page does. Network and
typed budget retries keep the submitted cursor. Expiry or an orderly server shutdown reports a
reset and bootstraps the same recording/window. History/access/structural refusals stop explicitly.

The adapter caps each response before parsing, refuses redirects, borrows the origin-scoped
credential provider and cancels both sockets and pending credential reads. Admission caps 32 jobs,
256 consumers and 256 union selections per job. A 64 MiB reservation covers two page limits per
active job, with 4 MiB default pages. Traversal counts nodes, including primitives, up to the
negotiated page byte budget, with a separate 96-level depth limit; there is no history cache.
This is not a claim that the window's decoded display memory is already bounded. The setting,
capabilities reporting, recorded/live/webview accounting and hidden-view expiry will be integrated
with live panels in the next checkpoint, still within Stage 3. No preview behavior changes here.

Validation is recorded in `/private/tmp/wpilog-visualization-stage3-broker/validation.md`.
`followBrokerTest` drives the TypeScript core against an actual writer/index/HTTP server using
synthetic data. Ordinary Node checks cover protocol, refcounts, retries, stale delivery and resource
admission. The CI task runs on both platforms; this machine supplies only macOS evidence. Remote
editor routing, live clock/display semantics, histogram/scatter, latency instrumentation and full
Stage 3 workload gates remain outstanding. No commit or publication of this checkpoint is implied.

Review finding 1 exposed an incompatible traversal bound in the unreleased broker: a full page
of 19-element arrays fits the server's byte limit but exceeds the client's fixed 262,144-node
ceiling. The bound now follows the negotiated page byte budget, since each JSON node needs at
least one encoded byte. Both a 4,096-record synthetic page and a real capture/server page failed
first with `INVALID_RESPONSE`; the fixed broker consumes all 77,824 array elements unchanged.
The depth and HTTP byte limits remain enforced. Fix-round evidence and plants are recorded in
`/private/tmp/wpilog-visualization-stage3-broker-array-limit/validation.md`. This fixes finding 1
only; the review's shared-reset/history-cache design decision remains for the live integration.
The full Java build is not green in this fix round: an unchanged oversized-request endpoint test
lost its HTTP response in both the full suite and an isolated rerun. That separate failure is
recorded in [IDEAS.md](IDEAS.md); the array regression and real-server broker workflow pass.

### Stage 3 review 5: sharing behavior decision (2026-10-15)

Finding 1 is fixed as recorded above. Finding 2 distinguishes the current acquisition-core
checkpoint from the behavior required for live panels. At that checkpoint, joining an active job or
refreshing a subscription reset every consumer of the same anchored source and windows. Reveal
would have had that effect too when it re-subscribed. The core retains no bootstrap history; its
64 MiB encoded limit is a reservation, not a cache. The earlier resource paragraph now agrees.

The owner chose **uninterrupted peers** and **1 MiB panel pages**, with 4 MiB retained as the
protocol maximum. The approved contract, now implemented in the follow-up below, makes opened
jobs immutable: same-turn mounts may coalesce with a pending open, but a later join, refresh or reveal after hidden expiry gets its own job and
lease. The existing peers' samples, history interval, generation and cursor remain unchanged.
Admission failures affect only the requesting view. This needs no history cache or bootstrap
handoff; measure the extra leases at the resource/performance gate before considering a cache.
The gate must cover existing and new selections, wider windows, refresh, reveal, cancellation,
finalization and server replacement while joining. With two page limits reserved per job,
1 MiB pages allow the 32-job cap within 64 MiB; the server's 16 long-poll workers are a separate
quiet-stream limit. The immutable-job follow-up replaces shared resets without a history cache.

The review's six notes require no immediate changes. Linux/Windows CI execution, native live-panel
behavior, real proxy credentials, the real-webview deadline and performance measurements remain
unverified as the review states. Review 6 identified the array round's oversized-request failure
as unread bytes resetting the
socket before the refusal arrived. The follow-up now drains at most 4 MiB beyond the detection
prefix within ten seconds (or an earlier route deadline), across the JSON body readers. It also
aligns the client with the server's 64 KiB cap and sizes the actual encoded union before changing
subscriptions. Raw-socket failing-first tests lost all 280 replies on the old readers; evidence,
plants and the final build results are in
`/private/tmp/wpilog-visualization-stage3-refusals/validation.md`. Review 7 accepted that tree,
now committed as `b57eaf6`. Panel wiring has not started.


### Stage 3 immutable opened jobs (2026-10-15)

Accepted by review 8 and committed as `14eff73`.

Only pending opens coalesce. The job freezes its sorted entry/field union before publishing
startup states or awaiting credentials/create. A later join, refresh or re-subscription after
hiding gets an independent lease, with no reset or cursor change for existing peers. A valid
subscription that exceeds a pending union's selection/request cap opens separately if capacity
allows. Query supersets survive consumer departure and same-recording server replacement.

Refresh admits its new binding before retiring the old one, so local byte/job refusal leaves the
requesting view running as well as its peers. It needs spare job/byte capacity, even for a
single-consumer job. A later permanent remote refusal stops only the requesting view; a typed
transient refusal backs off without replacing the job. It replaces a
consumer within the 256-consumer cap. Reset numbers are
per subscription and remain monotonic across successive refreshes and server replacement.
Pending and retiring jobs are identified independently, so cleanup of an old matching job
cannot remove its successor. Retiring jobs retain reservations until bounded DELETE completes.
Ended/error jobs release bytes but retain a job slot until their subscriptions close.

The 32-job cap includes matching source/windows. At the approved 1 MiB panel page size,
two-page reservations for 32 jobs fit exactly in the 64 MiB encoded budget. The adapter's 4 MiB
default and protocol maximum remain available and independently tested; the fixture explicitly
requests 1 MiB for the uninterrupted-peer workflow. Server poll-pool pressure remains separate
and retryable. No shared history cache, display budget or panel wiring is introduced here.

Failing-first, plants and final verification are recorded in
`/private/tmp/wpilog-visualization-stage3-immutable-jobs/validation.md`. The real-server workflow
checks exact int64 values, timestamps and ordinals through joins, isolated refresh, subscription
release/reveal, wider windows, metadata, replacement while joining and finalization while joining.
Native editor hide/reveal, remote credentials, aggregate recorded/live/webview buffers, the
clock/display workflow and the full Stage 3 resource/performance gate remain outstanding.


### Stage 3 review 8: transient catalog refusal (2026-10-15)

The owner approved committing the immutable-job checkpoint, then fixing finding 1 before panels.
The separate follow-up accepts same-identity growth of manifested open capture payloads at the
post-scan catalog check, while cache reuse still invalidates on growth. Other moving dependencies
get one reread; two unstable scans return `FILE_CHANGED` with `retryable: true`. The native-read
409 response preserves that flag. The HTTP client retries only typed transient 409/413 refusals
with the existing backoff and submitted cursor. Permanent writer/history/source refusals remain
nonretryable; no message matching, automatic recording switch or buffer reset is added.

Failing-first continuous-append polls reproduced the review defect at both 4 ms and 20 ms.
Deterministic filesystem-boundary tests cover the scan rules, and HTTP/broker tests cover a
refusal during create and after an acknowledged page. Evidence, plants and final verification
are in `/private/tmp/wpilog-visualization-stage3-catalog-retry/validation.md`.

Review notes 1–3 require no contract change: same-turn mounts may coalesce, a refresh needs spare
capacity at the 32-job/lease cap, and the documented ownership remains current. Live panels still
use the approved 1 MiB page size when wired. Panel visibility, display accounting, clocks and the
full Stage 3 resource/performance gate remain outstanding. This fix adds no native editor or
Linux/Windows execution claim.


### Stage 3 review 9 disposition (2026-10-15)

Review 9 accepts the catalog-refusal fix and finds no new defect. Its catalog-cost note is now
part of the performance gate above: measure strict inventory refresh on a season-sized store,
including lease sharing and staggered demand. No cache policy change is made in this follow-up.
The client intentionally stops with `INVALID_RESPONSE` for a present non-boolean `retryable`
flag on a 409; only boolean true permits retry, and a missing flag retains older-server behavior.
That contract is already documented and regression-tested. Linux/Windows native execution,
live panels and the season-sized measurements remain outstanding.


### Stage 3 recording-source inspection checkpoint (2026-10-15)

The accepted catalog-retry tree and review-9 disposition are committed as `0f1cd3e`.
The next integration step first removes a single-file assumption from source admission:
an open capture now returns the same anchored recording reference consumed by the follower.
HTTP and MCP use the workspace-owned catalog and `CatalogRecordingSource` for membership,
writer/hash proof, aliases, grant checks and fixed member prefixes. They share the conversion
of that reference and the classification of typed native-reader refusals. No second recorder,
catalog policy, inferred member path or telemetry cache is introduced.

The detached inventory retains native descriptors for every admitted member, with a 4 MiB
aggregate encoded limit and the reader's existing allocation preflight. Its name summary
combines counts only for identical declared types and metadata, reporting conflicts separately;
it does not decide field/schema compatibility for binding. Counts and time bounds use index
metadata without decoding telemetry. Clock facts distinguish a finite shared robot estimate,
the last sample, waiting for time synchronization and an empty finalized recording. This
stateless inspection does not implement a held display edge. Each new finalized inspection
verifies hashes again, so the eventual panel controller must retain broker state instead of
polling this operation for every frame.

The real Source Overview shows the recording, member/conflict counts and a readable clock
status, with an inspection-only message and disabled live layout/template controls. Close and
reopen it to inspect a new prefix. Recorded-file views keep their existing behavior. The
capabilities result exposes `live_source_inspection: true`, while `live` remains false. Source
inspection can supply a 1 MiB native follow, but the visualization renderer still cannot consume
that follow or create/bind a live view.

Failing-first tests, mutation plants, the full build and both native VS Code checks are recorded
in `/private/tmp/wpilog-visualization-stage3-panels/validation.md`. Synthetic writer fixtures
cover identity, growth/backward timestamps, aliases, rollovers, conflicts, finalization, cold
reopen, hash/grant/writer refusals, clock absence and limits. The real HTTP/MCP inspection result
also opens a native follow without losing ordinals. Native checks drive the actual controller,
overview and Java server in isolated profiles; they establish inspection behavior only.

This is a reviewable prerequisite within Stage 3, not its completed editor gate. Next are
bindings over admitted recording members, the broker-to-panel controller with the agreed
1 MiB pages and uninterrupted peers, a common displayed frontier and follow/inspect/pause/held
clock behavior. The aggregate decoded-memory setting and hidden-view expiry, configured remote
routing, all panel types and the declared resource/season-sized performance gates remain open.
Native Linux/Windows execution, the manual Close-cancellation check and the Save As fallback
remain disclosed limitations. No new checkpoint commit or publication is implied.


### Stage 3 review 10: selected frontier metadata (2026-10-15)

Review 10 accepts recording-source inspection, now committed unchanged in `ffc1f63`.
Its check of `0f1cd3e` distinguishes the 16 blobs identical to the round-9 evidence from the
plan and ideas document, which additionally contain the review-9 disposition; no code differs.
The independent inspection probe passed 36 checks, including concurrent inspection, clock
states, exact HTTP/MCP inventory parity, follow admission and release. These are reviewer
observations, separate from this implementation round's evidence.

The review measured complete frontier metadata at about 909 KB for 4,000 topics, 1.13 MB for
5,000, and 2.26 MB for 10,000 in one synthetic member. Its names/properties were 41 characters
and 34 bytes; the figures are workload-specific, not fixed supported-topic counts. Every new
prefix must carry that metadata within one page. Raising the page limit applies to the entire
lease: at 4 MiB, the broker reserves 8 MiB per job and admits at most eight under its 64 MiB
encoded budget. This would also leave a finite all-topic ceiling.

The owner chose **keep 1 MiB pages and add queried-entry metadata**. The follow-up adds an
explicit `frontier_scope: "queries"` option, acknowledged by create and each new frontier.
Omission retains the full-descriptor contract for existing callers. The projection includes
selected entry declarations/counts and their transitive struct schema dependencies, preserving
schema provenance and diagnostics. It keeps every recording member and its identity/boundary,
writer/final-hash proof and diagnostics. Internal history checks still cover unselected entries.
New selected declarations and schema changes arrive with a new prefix; unselected declarations
are not a discovery stream. The scope is immutable with the lease's query union.

The adapter verifies negotiation before polling, closes an incompatible created lease within
its existing bounded cleanup, and refuses changed/malformed scope or unrequested entry metadata.
The request-size admission uses the same scoped encoding as HTTP, before mutating subscriptions.
No automatic page expansion, metadata truncation, history-cache change or weakened catalog
reuse is introduced. Selected metadata that exceeds its own page cap still refuses explicitly.

The performance gate now includes 5,000 and 10,000 topics per member across two members.
The separate 4 MiB inspection inventory limit still applies: the review saw about 3.98 MB at
10,000 topics in one member and refusal at 12,000. Queried follow metadata does not fix an
oversized overview. Design bounded inventory listing for discovery before claiming complete
large-recording panel support. Preserve the existing explained refusal until then; do not raise
that cap or turn inspection into per-frame polling. Repeated finalized inspections still verify
full hashes, and season-sized catalog scans remain an unmeasured gate.

The architecture/development prose now describes ownership and validation without the new
class/method names; the development guide links here for evidence. The disabled-button visual
treatment is deferred to panel accessibility work; the accepted DOM/native behavior is unchanged.
No change was requested to the pre-existing withdrawn-grant classification. Native Linux/Windows,
manual Close cancellation, Save As and remaining Stage 3 panel/resource gates remain open.
Evidence for this follow-up is recorded in
`/private/tmp/wpilog-visualization-stage3-selected-frontier/validation.md`.

### Stage 3 review 11 disposition and inventory design (2026-10-15)

Review 11 accepts the queried-frontier change without a defect. The owner requested its commit;
`7a8b656` contains exactly the 19 reviewed blobs, including the existing plan disposition. The
review independently reproduced 3,832 Java passes/84 skips, 523 extension passes/3 skips,
three real-server broker passes and all 21 plant restores. Its added probes passed 25 raw-HTTP
scope checks and nine compiled-client checks. These are reviewer observations; this design
round verified the tree/commit hashes and did not rerun the already accepted implementation.

The topic-count wire ceiling is removed for small queried metadata sets, not for arbitrary
selected metadata or native indexes. The review's one-member, 32-selection shape produced
8,279-byte first pages at 1,000, 5,000 and 20,000 topics. The separate 4 MiB overview cap still
prevents discovery of the largest source. The proposed bounded-inventory section above makes
that prerequisite concrete: fixed compact discovery pages, fresh selected details, explicit
limits/lifetime and one owner for GUI/MCP. No paged inventory route or panel behavior has been
implemented by this documentation round.

Every declaration can wake every queried lease even if the entry is unselected; actual prefix
opens may coalesce publications. The performance gate now includes 32 open leases during
declaration bursts, both within and above the existing wait-worker capacity, with zero-lease
controls, exact sample/history oracles, resource accounting and health/close measurements.
It has not run. Do not turn this note into an unmeasured declaration-suppression optimization.
The existing absence of a frontier diagnostic for an unknown schema is unchanged; proposed
selection details must make schema availability visible before binding.

Review 12 adds a shared verified-hash prerequisite before the bounded inventory service and its overview adapter,
after design review and before live binding/panel wiring. Linux/Windows, native editor validation
for this future adapter, two-member independent probing and sustained throughput remain open.
Local commit verification and documentation checks for this round are recorded in
`/private/tmp/wpilog-visualization-stage3-inventory-design/validation.md`.


### Stage 3 review 12 disposition and hash-memory prerequisite (2026-10-15)

Review 12 confirms `7a8b656` matches all 19 accepted blobs and accepts the detached inventory
design with amendments. It independently reran 3,832 Java passes/84 skips and 17 documentation
checks on the two-document design tree. Those are reviewer observations, not new runs here.

All amendments are incorporated above: a shared hash-verification owner precedes the ten-second
build deadline; inventory handles have eight server-wide and two per-session slots, unguessable
session-bound IDs and session-close reclamation; MCP list pages carry true limits and no-match
remedies; a visible overview automatically refreshes at absolute expiry while retaining and
revalidating selection. A changed selected type refuses with `INVENTORY_CHANGED`. The burst
gate reads prefix-open counts from an actual server seam/metric. Stateless keyset paging was
not duplicate-prone; repeated catalog admission on growing captures is the reason to prefer
a detached listing here.

This implementation checkpoint changes only verification ownership. Workspace source admissions
share up to 256 successful path/boundary/expected-hash proofs for ten idle minutes, with the same
policy used by followers. Changed boundaries/hashes and failed verification cannot reuse a
proof. Every current catalog/path grant and native snapshot check remains. No hash I/O runs
under the memory lock; concurrent cold misses can duplicate work, and close prevents later
publication. The memory retains no mapped log or payload and does not detect changes that
preserve every observed file attribute. Cold/evicted hashing and season-sized strict catalog
scans remain costs to measure, not a ten-second performance guarantee.

The next checkpoint is paged inventory service implementation, followed by its overview adapter
and native checks, before live binding/panel wiring. No paged operation or capability, deadline,
new handle quota, MCP schema, automatic overview refresh or prefix-open metric is claimed as
implemented here. Linux/Windows, native editor work and the 32-lease declaration-burst/season
workloads remain unrun. Local evidence is recorded in
`/private/tmp/wpilog-visualization-stage3-verified-hashes/validation.md`.


### Stage 3 paged inventory service checkpoint (2026-10-15)

The accepted verified-hash prerequisite is committed as `6d204a4`, with exactly its 12 reviewed
paths. This checkpoint implements the shared server prerequisite before the overview adapter:
explicit paged negotiation, detached name/type/count rows, fixed clock/member summaries,
session-bound authenticated cursors, fresh selected details, typed refusals and bounded
construction/lifetime. HTTP and MCP share the owner and reserve response bytes until transport
writing finishes. Four dedicated request workers leave control traffic available; two builders
retain reservations through cancellation until blocked admission actually unwinds.

Verification and remaining gaps are recorded in
`/private/tmp/wpilog-visualization-stage3-inventory-service/validation.md`. The current editor
still uses full inspection, so its large-recording discovery ceiling remains until the next
overview checkpoint. No live binding, panel rendering, automatic overview refresh or new editor
behavior is claimed here. The declaration-burst workload, season-sized catalog cost comparison,
native Linux/Windows runs and broader Stage 3 performance gate remain unrun. Source references,
metadata limits and the full-history follower proof are unchanged by choosing paged discovery.


### Stage 3 review 13 disposition (2026-10-15)

Review 13 reproduced the inventory algorithms and full suites but found three transport blockers:
final capacity responses could drain unread bodies indefinitely on dispatch workers; MCP checked
normalized JSON instead of raw input; and capacity shortcuts bypassed sessions, notifications
and mixed-batch controls. Those passing suites did not prove the refusal contract.

The uncommitted follow-up adds real-socket failing-first regressions for both final streaming
and MCP body-admission refusals. Two final-refusal workers per streaming pool own refusal I/O
under the existing watchdog; exhaustion closes before headers. Raw UTF-8 input counts now reach
normal MCP dispatch, including stdio. Session checks, basic JSON/JSON-RPC validation, notification
suppression and ordinary batch controls retain their existing owners. A pressured inventory data
operation alone receives a typed retryable refusal.

The separate batch design concern is settled here: 1 MiB bounds each logical inventory reply;
8 MiB bounds all replies and array framing in an inventory-containing MCP HTTP batch. Capabilities
advertise the latter separately. The 64 KiB raw request bound applies to the entire exchange for
inventory members, including whitespace and batch syntax. Batch overflow stops remaining calls
and warns that earlier calls may have executed; a caller must inspect state before resubmitting.
These are transport limits, not a claim of a measured physical heap bound.

Failing-first, mutation and final-check evidence is recorded in
`/private/tmp/wpilog-visualization-stage3-inventory-refusals/validation.md`. The checkpoint remains
uncommitted for focused re-review. Overview integration, native editor paging/expiry behavior,
live panels and the declaration-burst/season-sized performance gates remain pending.


### Stage 3 paged overview checkpoint (2026-10-15)

The owner authorized committing the accepted inventory/refusal tree and continuing. Commit
`2b9409f` contains the exact 48 validated files. The following checkpoint implements only the
bounded overview prerequisite before live bindings and panels. Catalog facts arrive first;
literal search, exact-type filters, 256-row pages and selected details use the existing shared
service. The adapter verifies capability and paged-mode echo, refuses an old server with an
update hint, enforces 64 KiB requests/1 MiB replies and a 15-second per-request deadline, and
keeps inventory handles in their opening MCP session.

Refresh admits the replacement before closing the old handle. Exact-name/source selection and
filters survive refresh, expiry and hide/reveal; selected details are revalidated. Two pages
plus bounded cursor breadcrumbs and one detail are accounted; explicit off-page selection
lookup uses a bounded literal prefix, matches the full name exactly, and has a 1,024-page ceiling. Hidden views discard their
renderer context and release the inventory. Renderer delivery coalesces pending state behind
one acknowledged copy with a five-second stall budget. Encoded requests share the broker's
64 MiB budget owner, while retained inventory/display copies have a conservative 64 MiB
window allowance. This is the inventory portion of display admission; the configurable combined
recorded/live sample budget and attached-window capability reporting still belong to panels.

Failing-first tests, plants, native editor results and final checks are recorded in
`/private/tmp/wpilog-visualization-stage3-overview/validation.md`. The native fixture uses an
active two-member 20,000-name recording, real capacity refusals, actual MCP/HTTP parity and a
real five-minute expiry, rather than shortening the production lifetime. New work remains
uncommitted for review. The 32-lease declaration-burst gate and season-sized scan/hash comparison
remain explicitly unrun; neither this functional gate nor conservative byte accounting claims
sustained performance. Native Linux/Windows runs, live binding/panels and the earlier disclosed
manual Close-cancellation and Save As limitations remain open.


### Review 14 correction round (2026-10-16)

The review reproduced two lifecycle defects beyond the ordinary passing editor workflows.
A renderer recreated during catalog identification could publish a second controller and lose
the first owner. Initialization now coalesces within a visible generation, rejects late replies
after hide/close, and publishes a source with its single owner before awaiting the renderer.
A failed reopen could also clear known idle expiry because the absolute deadline was still in
the future. Known expiry/session/declaration invalidation now persists until a replacement
page is admitted; rows stay readable but their actions stay disabled.

The nonblocking filter mismatch is corrected in the same round: **Show unavailable declarations
only** is an exclusive traversal, ordinary filters are disabled and omitted from its requests,
and their values survive switching back. The earlier bounded-inventory introduction now
distinguishes its historical full-inspection behavior from current paged integration.

Evidence, failing-first tests, plants and native results are recorded in
`/private/tmp/wpilog-visualization-stage3-overview-fixes/validation.md`. This correction round
and the overview checkpoint were committed as `f567d62`. Live bindings/panels, combined configurable sample budgets,
declaration-burst and season-sized performance gates, and native Linux/Windows remain pending.


### Stage 3 recording compatibility checkpoint (2026-10-16)

The paged overview and review-14 corrections are committed as `f567d62`, matching all 35 saved
implementation hashes. The next checkpoint extends the shared compatibility operation to
anchored recording references, before editor binding or panel wiring. It checks every member's
complete entry set through the existing resolver. Exact names never fall back, roles retain
candidates/ambiguity and unit basis, and per-member absence is explicit. A combined binding
requires agreement on the resolved name, declared type, metadata and transitive schema text;
conflicts never select a preferred member. Native schema fallbacks retain their warning basis.
Dynamic field/index compatibility needs a numeric witness, while an exact scalar declaration
can be compatible before its first sample.

A check uses fresh source admission and the shared hash memory, returns prefix/member proof,
then closes it. It is not a persistent binding or permission grant; a later follow must recheck
its selected evidence. Value-dependent resolver rules stream bounded native evidence instead
of loading whole histories; the shared Driver Station selector does not build a timeline.
The server publishes `recording_bindings: true` and its limits while `live` stays false.
Two checks may run concurrently, with no waiting queue; inventory, evidence, candidate and reply
limits are explicit in the standalone guide. The ten-second work budget is cooperative,
not a promise to interrupt an OS read. The complete workload and resource gate is still pending.

Evidence is recorded in `/private/tmp/wpilog-visualization-stage3-bindings/validation.md`.
This server-only checkpoint remains uncommitted for review. Native editor behavior does not
change: live layout/template controls remain disabled. Next are recording-view creation and
binding/follow admission, the broker-to-panel controller with immutable 1 MiB queried pages,
common displayed frontiers and clock states, combined configurable sample budgets, the
32-lease declaration burst, season-sized scans/hashes and sustained-performance measurements.
