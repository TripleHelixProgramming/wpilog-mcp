/**
 * The MCP messages the explorer's client sends and the shape of what comes back, as pure
 * functions, so the protocol can be tested without a server. The client (`mcpClient.ts`) moves
 * these over HTTP. See EXPLORER_PLAN.md §3, "The MCP client".
 */

/** The protocol version the server speaks (McpMessageHandler.PROTOCOL_VERSION). */
export const PROTOCOL_VERSION = "2025-03-26";

/** The session header of the Streamable HTTP transport. */
export const SESSION_HEADER = "Mcp-Session-Id";

/** A JSON-RPC request or notification, as sent. */
export interface JsonRpcMessage {
  jsonrpc: "2.0";
  id?: number;
  method: string;
  params?: unknown;
}

/** The `initialize` request: the client's name and version, and no capabilities of its own. */
export function initializeRequest(id: number, clientVersion: string): JsonRpcMessage {
  return {
    jsonrpc: "2.0",
    id,
    method: "initialize",
    params: {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: { name: "wpilog-explorer", version: clientVersion },
    },
  };
}

/** The `initialized` notification, which ends the handshake; it has no id and gets no reply. */
export function initializedNotification(): JsonRpcMessage {
  return { jsonrpc: "2.0", method: "notifications/initialized" };
}

/** A `tools/call` request. */
export function toolCallRequest(id: number, name: string, args: Record<string, unknown>): JsonRpcMessage {
  return { jsonrpc: "2.0", id, method: "tools/call", params: { name, arguments: args } };
}

/** What a tool returned, parsed: the result's JSON, or why the call failed. */
export type ToolOutcome =
  | { ok: true; result: Record<string, unknown> }
  | { ok: false; error: string; result?: Record<string, unknown> };

/**
 * Reads a `tools/call` response. The server wraps a tool's result as one text content whose
 * text is the result's JSON. A result whose `status` is `error` (bad arguments, an unreadable
 * file: the result contract's error status, which the tool base returns as an ordinary result)
 * and one the envelope marks `isError` (an unknown tool, a tool that threw) are both failures,
 * with the result's own `error` as the message. A JSON-RPC error (an unknown method, invalid
 * params) has no result at all. Anything else is a response this client does not understand,
 * reported as such rather than guessed at. A `no_match` or `not_applicable` result is not a
 * failure: the tool answered, and the result says what it looked for.
 */
export function parseToolResponse(response: unknown): ToolOutcome {
  const message = response as { result?: unknown; error?: unknown } | null;
  if (!message || typeof message !== "object") {
    return { ok: false, error: "The server's answer was not a JSON-RPC response" };
  }
  if (message.error !== undefined) {
    const error = message.error as { code?: unknown; message?: unknown };
    const text = typeof error?.message === "string" ? error.message : JSON.stringify(message.error);
    return { ok: false, error: `The server refused the call: ${text}` };
  }
  const result = message.result as { content?: unknown; isError?: unknown } | undefined;
  const content = Array.isArray(result?.content) ? result.content : [];
  const text = content.find((c) => (c as { type?: unknown })?.type === "text") as { text?: unknown } | undefined;
  if (typeof text?.text !== "string") {
    return { ok: false, error: "The tool's result carried no text content" };
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(text.text);
  } catch {
    return { ok: false, error: "The tool's result was not JSON" };
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    return { ok: false, error: "The tool's result was not an object" };
  }
  const toolResult = parsed as Record<string, unknown>;
  if (result?.isError === true || toolResult.status === "error") {
    const reason = typeof toolResult.error === "string" ? toolResult.error : text.text;
    return { ok: false, error: reason, result: toolResult };
  }
  return { ok: true, result: toolResult };
}

/**
 * Whether an HTTP status with a session header means the session is gone: the server was
 * restarted, or let the session expire. The client then initializes again, once.
 */
export function sessionLost(status: number, hadSession: boolean): boolean {
  return status === 404 && hadSession;
}
