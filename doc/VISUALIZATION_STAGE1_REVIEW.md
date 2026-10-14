# Review of the visualization Stage 1 implementation

Independent technical review, 2026-10-14.

**Work reviewed:** the uncommitted Stage 1 changes in the `development` checkout at commit
441030c: 19 modified tracked files and 21 new files (Java identity, caller-context, contract,
working-state and store-metadata classes; the extension's document controller, editor provider
and recovery journal; the isolated real-editor fixture; tests; documentation), plus
`doc/VISUALIZATION_PLAN.md` revision 3 (807 lines, SHA-256 beginning `39fae216`). The
implementer's evidence is in `/private/tmp/wpilog-visualization-stage1/validation.md`.

**Scope:** every new source file and every tracked diff was read; the plan's revision 3 was
diffed against revision 2; the Java build, the extension suite and the real-editor lifecycle
check were rerun on this machine; two startup failure modes were probed against the built jar.
Nothing in the repository other than this review was changed; nothing was committed, pushed or
published.

## Verdict

**Stage 1 is close, but not yet accepted for Stage 2a.** The code is compact, careful and
matches the plan, and most of the evidence reproduces. Two items must be fixed first, and one
statement in the plan's evidence section must be corrected.

## What reproduced

| Check | Implementer's report | Rerun on this machine |
|---|---|---|
| `./gradlew build license` | 3,596 passed, 84 skipped | 3,596 passed, 84 skipped, 0 failures |
| `npm test` in `vscode-extension` | 395 passed | 395 passed |
| Real VS Code 1.101.0 lifecycle, `npm run test:visualization` | 18 assertions passed | 18 passed; no extension-host exit 11 this time |
| Real VS Code 1.141.0 lifecycle | 18 assertions passed | 17 passed; native undo timed out in both of two runs |
| Fault plants | 33 caught and restored | Plant logs consistent; final source hashes match the tree |

Also confirmed:

- License headers and two-space indentation on every new file; `git diff --check` clean.
- No product wiring beyond the identity fields: `VisualizationService` and
  `VisualizationMetadata` are constructed only by tests, the editor provider is registered only
  by the fixture extension, and `extension.ts` is unchanged.
- The smoke code stays out of the VSIX through `.vscodeignore`.
- The fixture runs in its own profile, extensions directory and, on 1.141, shared-data
  directory; it does not activate the product extension or touch a user's server.
- The six review-2 refinements are present in plan revision 3, with a disposition table.
- Caller authority is computed per request from the real listener, peer and Origin, defaults to
  untrusted, is cleared in a `finally` block, and is captured as a value before work is queued;
  the test covers batch dispatch and a spoofed forwarding header.
- Saved-view writes run on the store queue under the store lock with request replay and
  revision comparison; the `visualizations/` directory is excluded from stray discovery with a
  test.
- The contract parser bounds size and depth, rejects duplicate keys, lone surrogates and
  non-finite numbers, and cross-checks panel, axis and layout references.
- The recovery journal is bounded, refuses symlinked directories, and rejects any view id that
  is not a UUID, which closes path traversal.

## Important, fix before Stage 2a

1. **Named servers now refuse to start when the visualization state directory cannot be
   opened.** `src/main/java/org/triplehelix/wpilogmcp/Main.java:766` exits with code 1 when
   `ServerIdentity.open` throws. A probe against `build/libs/wpilog-mcp-0.10.0-dev5-all.jar`
   confirmed two realistic cases abort startup: a symlinked `~/.wpilog-mcp`, which the new
   state code refuses to traverse, and an unwritable `state/visualizations` parent. That is a
   new hard failure mode for every named server, the pit service included, in exchange for a
   feature nobody can use yet.
   Correction: on failure, log a warning and fall back to the ephemeral identity, reporting
   `working_state: "ephemeral"` with a reason field in `/health` and `get_server_guide`. Add
   a test in which an unwritable state parent still yields a running server.

2. **The native undo gate is not established on current VS Code.** Both reruns on 1.141.0
   failed at "native undo restores saved position" while every other assertion passed, and
   1.101.0 passed cleanly. The implementer's evidence folder shows the same timeout in four of
   five 1.141.0 runs before the single passing run the report cites. Both bundles dispatch
   undo identically: the custom-editor implementation at priority 105 runs before the webview's
   at 100, so the fault is downstream of dispatch and version-dependent.
   Correction: instrument the fixture's edit callback to record whether VS Code invokes the
   undo at all, diagnose from there, and make the gate deterministic. Until then, the plan's
   Stage 1 evidence paragraph must say that undo on current stable is unverified, not passed.
   Evidence from this review: `build/visualization-stage1/1.141.0/run-dPZCMw/result.json` and
   `run-*/result.json` from the second run.

## Minor, can be settled in Stage 2a

3. **Backup recovery is unguarded.** `vscode-extension/src/visualization/editorProvider.ts:52`
   recovers from a VS Code backup without a try/catch; a revision conflict during it rejects the
   whole open. Wrap it like the journal path.
4. **The save barrier has no release timeout.** In `documentController.ts`, if the clean-tab
   event never arrives after a save, `saving` stays true, every later edit is deferred, and the
   257th is refused. Release after a bounded wait with a warning.
5. **The strict parser only sees raw text when a caller passes raw text.**
   `VisualizationContracts.source` and `configuration` re-serialize an already parsed object,
   and MCP arguments arrive parsed by the JSON-RPC layer, so the duplicate-key promise holds
   only for routes that keep raw bytes. Record that limit for the Stage 2b adapters.
6. **Attachments outlive their window within one server run.** A crashed window leaves a view
   attached, and other windows get `VIEW_ATTACHED` until the server restarts. Stage 2a's client
   registration must release attachments when the client's session expires.
7. **Service load fails closed on one bad record.** One corrupt or unknown file under `views/`
   prevents the service from constructing at all. Quarantine the record and continue.
8. **The quota counts expired drafts until a create prunes them.** An update can hit
   `WORKSPACE_LIMIT` while expired drafts still occupy space. Prune before the budget check.

## Disclosed gaps that stand as stated

- Close cancellation is unverified. One manual click through
  `WPILOG_VISUALIZATION_DIALOGS=1 npm run test:visualization` settles it.
- Save As uses the refusal fallback the plan allows; no picker, alias or replacement behavior is
  claimed.
- The native lifecycle ran against a synthetic backend. The extension controller and the Java
  service are not yet proven together; that is Stage 2a's integration gate, not a Stage 1
  defect.
- Linux and Windows native runs are outstanding, as the report says.

## Stage 2a readiness

Stage 2a can begin once findings 1 and 2 are fixed and the plan's evidence statement is
corrected. Findings 3 to 8 belong in Stage 2a's own acceptance list.
