# Review of the Stage 1 follow-up

Independent technical review, second round, 2026-10-14.

**Work reviewed:** the follow-up to [the Stage 1 review](VISUALIZATION_STAGE1_REVIEW.md), still
uncommitted on `development` at commit 441030c, plus `doc/VISUALIZATION_PLAN.md` at 836 lines
(SHA-256 beginning `1b50bc8e`). The implementer's evidence is in
`/private/tmp/wpilog-visualization-stage1-review/validation.md`. Changed since the first round:
`Main.java`, `ServerIdentity.java`, `StateFiles.java`, `VisualizationService.java`,
`documentController.ts`, `editorProvider.ts`, the fixture, suite and tests, the plan, and the
documentation; one new test, `VisualizationStartupTest`.

**Scope:** every changed source and test was read; the plan was diffed against the revision the
implementer saved; the Java build, the extension suite, one 1.101.0 and three 1.141.0 real-editor
lifecycle runs were rerun on this machine. Nothing other than this review was changed; nothing
was committed, pushed or published.

## Verdict

**Stage 1 is accepted for Stage 2a.** Both important findings are resolved: a named server now
starts with an ephemeral identity and a stated reason when its state directory cannot be opened,
and native undo is proven to reach the document and persist its result. One fixture race and one
changelog placement remain; neither blocks Stage 2a, but the race must be fixed before the
lifecycle check runs in CI.

## What reproduced

| Check | Implementer's report | Rerun on this machine |
|---|---|---|
| `./gradlew build license` | 3,601 passed, 84 skipped | 3,601 passed, 84 skipped, 0 failures |
| `npm test` | 398 passed | 398 passed |
| Startup fallback, three child-server cases | all passed | unwritable, symlink and blocked parents all ran and passed, none skipped |
| VS Code 1.101.0 lifecycle | 19 checks, 20 undo/redo pairs | 19 checks, 20 pairs |
| VS Code 1.141.0 lifecycle | 5 consecutive runs, 20 pairs each | 2 of 3 runs passed with 20 pairs; 1 run stopped at the fixture's active-editor guard after 14 pairs |
| Fault plants | 16 caught and restored | indexes consistent; restoration hashes match the tree |

## Disposition of the first-round findings

| Finding | Status | Evidence |
|---|---|---|
| 1, startup hard exit | Fixed | `Main.java` warns and falls back to an ephemeral identity; `working_state_reason` appears in `/health` and `get_server_guide`; `VisualizationStartupTest` drives a real child server through all three failure cases. |
| 2, undo gate | Product behavior proven; fixture race remains, see A below | Every passing run records exactly one callback per native Undo and Redo and the persisted configuration; the failed run shows 14 completed pairs before the harness lost the active editor. |
| 3, unguarded backup recovery | Fixed | `editorProvider.ts` catches recovery errors; a native assertion opens a document against a conflicting backup. |
| 4, save barrier without timeout | Fixed | Five-second clean-tab deadline after the server save, warning and repaint on expiry, cancelled by acknowledgement or disposal; fake-clock tests cover 4,999 and 5,000 ms. |
| 5, raw-text parser limit | Recorded as a Stage 2b requirement | Plan, architecture guide and ideas list say adapters must validate raw JSON before generic parsing. |
| 6, attachments outliving a window | Recorded as a Stage 2a requirement | Plan and architecture guide require detach on session expiry through the state queue. |
| 7, fail-closed load | Fixed | Invalid records move intact to `quarantine/<uuid>/`; the test checks the exact preserved bytes. |
| 8, quota counted expired drafts | Fixed | `checkBudget` prunes first; expired records are also removed at load. |

The plan's Stage 1 evidence paragraph now states that the original current-stable undo result
was unverified and points to the follow-up evidence, as the first review required.

## Remaining findings

A. **The fixture's editor switch still races, about one run in three here.** In the failed run,
`open()` passed its first active-editor check, then rejected at the webview acknowledgement
because the active group had moved back to the other document. The cleanup path then reverted
whichever editor was active, could not clean document A, and the whole run aborted after five
checks. The product undo had completed 14 pairs by then, so this is a harness defect, not a
provider one. Correction, in `vscode-extension/src/smoke/visualizationFixture.ts`: after the
acknowledgement, wait for the active editor to settle and retry the reveal within the deadline
instead of rejecting once; in the suite's cleanup, reveal A and confirm it is active before
reverting. Fix before this check is added to CI. Minor.

B. **Five changelog entries describe never-released behavior as fixed.** The entries under
`### Fixed` say, for example, that the prototype "previously aborted startup"; nothing of this
feature has shipped. Fold them into the existing `### Added` entry, or say in each that the
defect was in the unreleased Stage 1 prototype. The `### Testing` entry is fine. Minor.

## Disclosed gaps that stand as stated

- Native Close cancellation remains unverified; one manual click through the documented
  interactive run settles it.
- Linux and Windows native-editor runs are outstanding.
- The extension controller and the Java service have not run together; that is Stage 2a's
  integration gate.
- Native Save As still refuses with the Save view… hint, as the plan allows.

## Stage 2a readiness

Stage 2a can begin. Carry findings 5 and 6 and the fixture race above into its acceptance list,
and settle the changelog wording with the first commit of this work.
