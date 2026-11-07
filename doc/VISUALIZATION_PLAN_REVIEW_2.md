# Review of the WPILog Explorer visualization plan, revision 2

Independent technical and product review, second round, 2026-10-14.

**Document reviewed:** `doc/VISUALIZATION_PLAN.md` revision 2, 763 lines, last modified
2026-10-14 08:59, SHA-256 beginning `70a9b011`, untracked on branch `development` at commit
441030c. The author's copy of revision 1 in `/private/tmp` matches the hash recorded in the
[first review](VISUALIZATION_PLAN_REVIEW.md), so the comparison below is an exact diff.
Line numbers refer to revision 2.

**Scope:** the revision was diffed against revision 1, every one of its new source references
was checked against the code, the first review's 24 findings were checked against its
disposition table, and the extension test suite it cites was rerun. No repository file other
than this review was changed; nothing was committed, pushed or published.

| Check | Result |
|---|---|
| Lines added, lines removed | 249, 79 |
| Findings with a disposition | 24 of 24 |
| New source references verified | 17, all accurate |
| Extension suite, `npm test` on Node 26 | 384 passed, 0 failed |

## Verdict

**Ready to begin Stage 1.** Every amendment from the first review is applied, and most go
further than proposed. Nothing in revision 2 blocks implementation. The findings below are
refinements that can be settled during Stage 1 or 2a.

## Disposition check of the first review's findings

| Finding | Status in revision 2 |
|---|---|
| 1, Save As and auto-save | Resolved: drafts are untitled-like and not native-dirty; Save As mints new saved and working UUIDs and re-targets the tab through a persisted alias; auto-save, Revert and hot exit are Stage 1 gates (lines 119 to 125, 680). |
| 2, connection id in SourceRef | Resolved: `SourceRef` is durable identity only; `connection_id` is a request-level hint, moved out of every example (lines 93, 145 to 154, 538). |
| 3, server UUID | Resolved: persistent `server_instance_id` in a bounded state directory, exposed by `/health` and `get_server_guide`; working views owned by the caller's local server (lines 112 to 115, 277). |
| 4, caller context | Resolved: immutable transport-supplied authority per request, consulted by every mutation; stdio trusted, unspecified untrusted (lines 291 to 292). |
| 5, multiplexed tools | Resolved: one tool per operation, 21 tools, each with schema, description, scenario and sweep rule (lines 374 to 383). |
| 6, two document kinds | Resolved: read-only source overview provider at `priority: option` during preview, editable `wpilog-view:` provider, Overview reveals the source tab, Entries targets the active view (lines 105 to 107). |
| 7, capture identity registry | Resolved: deferred; `merged_session_ids` alias plus the existing same-recording rule; mixed-writer limit documented (lines 95 to 99). |
| 8, no store | Resolved: Create store… through the empty-paths import, `NO_WRITABLE_STORE` for MCP (line 287). |
| 9, remote store read-only | Resolved: stated as a deliberate v1 limitation with the future upload route named (line 313). |
| 10, predefined visualizations | Resolved: three bundled definitions on existing resolver roles; exact names stay the default for user templates (lines 198 to 222). |
| 11, recorded memory bound | Resolved: 64 MiB decoded per window for recorded and live, 64 MiB encoded cache (lines 360, 716). |
| 12, control channel | Resolved: negotiated MCP notifications on the session stream, bridge replay and reconnection, bounded ring (lines 391 to 393). |
| 13, stage scope | Resolved: Stages 2a and 2b; follower proof is the first Stage 3 gate (lines 680 to 686). |
| 14 to 24 | Resolved as the disposition table states; each was checked against the text. |

## What was verified beyond the disposition table

- The eight resolver roles the bundled templates use, `robot_enabled`, `autonomous`,
  `test_mode`, `fms_attached`, `battery_voltage`, `total_current`, `brownout_flag` and
  `loop_time_user`, exist under those names in the resolver and in `doc/TOOLS.md:773`.
- The same-recording rule the plan reuses, in `read/catalog/SessionSnapshot.java`, requires
  captured provenance, an equal original capture start and the same first collecting store,
  as the plan says.
- Session consolidation in `store/StoreSync.java` keeps the smaller session id; the plan's
  alias field addresses exactly that.
- An import with an empty `paths` array creates an empty store: `doc/STANDALONE.md:735`
  documents it, and the store writes its header on the first mutation.
- Capture refuses announcements before time sync, `capture/CaptureWriter.java:407`, and writes
  sample timestamps unchanged, `capture/CaptureWriter.java:619`, so the clock-basis section
  rests on real behavior.
- The declared-type vocabulary matches `log/subsystems/EntryDecoder.java:86`, the legacy
  `structarray:` form is still recognized, and NetworkTables integers are written as `int64`.
- The state-directory convention is SHA-256 over the real configuration path, a newline and the
  server name, as `store/LocalIngestion.java:122` does for ingestion policy.
- `/health` reports name, version and PID today, and no instance id, as stated.
- The stdio bridge already opens the session's SSE stream and forwards its events; the extension
  MCP client does not open that stream, so the notification work is new at the layers the plan
  names.
- Cached VS Code 1.101.0 and 1.141.0 builds exist under the extension's test folder, so the
  workbench check the revision cites was possible without launching an editor.
- The 79 removed lines were all replaced by revised text. Nothing was dropped silently.
- The full extension suite passes on this machine, which is consistent with, and stronger than,
  the 52 focused tests the revision reports.

## Remaining findings

All are minor. None changes the verdict.

1. **The Save As picker would show UUID folders.** Line 125 exposes
   `save-as/<store-id>/<title>.wpiview`, which puts store UUIDs in the simplified dialog.
   Correction: list stores by label and resolve the id inside the provider. Also write the
   fallback down now: if the Stage 1 gate fails, `saveCustomDocumentAs` refuses with a hint to
   use Save view…, rather than leaving the outcome to a later design review.
2. **A journal write failure stops editing.** Line 121 rejects further edits when the recovery
   journal cannot be written. The server persists every acknowledged gesture, so the failure
   should degrade to a visible warning that edits are saved on the server only, not block edits.
3. **Closed drafts have no way back in the UI.** Drafts are not dirty, so closing the tab asks
   nothing, and the overview at lines 47 to 50 lists only saved views. Correction: add an
   Unsaved views list per source, with each draft's expiry, beside Saved views.
4. **Two undo models meet at Save view….** Lines 119 and 123 give drafts Explorer-owned
   undo and saved views VS Code undo. Say what happens to the draft's undo history when it
   becomes a saved view: cleared, or replayed as VS Code edits.
5. **The recorded budget trades exactness for memory.** Today a series can hold four million
   exact samples; under the window budget at line 360, long logs bucket sooner. Correction:
   make the budget a setting with 64 MiB as the default, and report the effective value in the
   capabilities result.
6. **The overview arrives by default only at Stage 4.** Line 105 is the right staging, but the
   coverage of "opening a log shows the overview" should say that the preview needs Open
   Source Overview or Reopen Editor With until retirement.

## Requirement coverage

| Requirement | Status | Notes |
|---|---|---|
| Replace the current editor | Covered, Stage 4 | Legacy editor kept by view type until retirement |
| Overview on open | Covered | Default at Stage 4; finding 6 |
| Predefined visualizations via visible controls | Covered | Three bundled definitions, Stage 2b |
| Create, arrange, resize, edit, save layouts | Covered | Findings 1 to 4 |
| Templates in the store, reuse by compatible names | Covered | Create store… on first save |
| Export, share, import | Covered | JCS digests, receipts, update or copy |
| Multiple views and multiple sources | Covered | Overlay deferred with reason |
| Live auto-update | Covered, Stage 3 | Clock basis and latency instrumentation defined |
| Agents create, inspect, modify, display, save | Covered | 21 tools, notifications, client routing |
| Shared definitions and services | Covered | One Java service; caller context |

## Milestone readiness

- **Stage 1** can start now. Its acceptance list covers the native lifecycle gates the first
  review asked for.
- **Stage 2a** follows the Stage 1 review. Findings 3, 4 and 6 belong to its design.
- **Stage 2b** and **Stage 3** are unchanged in readiness; the Stage 3 follower proof can be
  researched in parallel, as the plan says. Finding 5 belongs to Stage 3's budget gate.
- **Stage 4** depends on all of the above.
