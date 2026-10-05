# Bundled libraries

The webview's libraries, as static assets with their licenses, never fetched at build or at run
time (EXPLORER_PLAN.md decision 8). The extension's rule against runtime npm dependencies is about
its Node side, which stays dependency-free; these files are loaded by the webview's page alone,
under its content security policy, by nonce.

| Library | Version | Files | License |
|---|---|---|---|
| [uPlot](https://github.com/leeoniya/uPlot) | 1.6.32 | `uPlot.iife.min.js`, `uPlot.min.css` | MIT (`uPlot.LICENSE`) |

To update one, download the release's distribution files, replace them here, and change the
version in this table; `manifest.test.ts` checks that each file and its license are present.
