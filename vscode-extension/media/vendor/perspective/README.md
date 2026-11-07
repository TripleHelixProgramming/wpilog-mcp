# Perspective 5.5.1

Static browser assets from the official `@perspective-dev/client`, `server`, `viewer`,
`viewer-datagrid` and `viewer-charts` npm distributions, version 5.5.1.
Apache-2.0; see LICENSE. No runtime npm dependency or network download.
https://github.com/perspective-dev/perspective/tree/v5.5.1
https://perspective-dev.github.io/guide/how_to/javascript/importing.html

The ES modules and wasm binaries are unmodified. The explicit local worker avoids
Perspective's default worker fallback to Function (which the webview CSP refuses).
The viewer receives the local wasm glue module explicitly, avoiding a blob script import.
Charts add 285,577 uncompressed bytes, small beside the wasm engine and viewer.
No Memory64 engine, source maps, Node runtime or optional remote providers are bundled.
