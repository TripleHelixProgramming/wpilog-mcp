import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as net from "node:net";
import { McpClient } from "../mcpClient";
import { DataClient } from "../dataClient";

for (const kind of ["mcp", "data"] as const) test(`${kind} honors an HTTPS pit URL and starts TLS, never plaintext`, async () => {
  const firstBytes: number[] = [];
  const server = net.createServer(socket => socket.once("data", bytes => { firstBytes.push(bytes[0]); socket.destroy(); }));
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  const port = (server.address() as net.AddressInfo).port;
  try {
    const url = `https://127.0.0.1:${port}/mcp`;
    await assert.rejects(kind === "mcp" ? new McpClient(url, "test").callTool("get_server_guide")
      : new DataClient().fetch(url, { path: "/log", names: ["/value"] }), /could not be reached/);
    assert.deepEqual(firstBytes, [22], "TLS handshake record content type, not HTTP's P or G");
  } finally { await new Promise<void>(resolve => server.close(() => resolve())); }
});
