/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.JsonElement;

/**
 * A tool whose complete reply has a byte limit. The limit counts the whole encoded JSON-RPC reply,
 * its id, envelope and the result's escaping inside the text block included, which only the
 * protocol handler sees: a check on the result object alone undercounts by the escaping, and a
 * tool never learns the request id. An oversized reply is replaced by the tool's refusal, never cut.
 *
 * @since 0.10.0
 */
public interface BoundedReply {
  /** The largest complete encoded reply, in bytes. */
  int maxReplyBytes();

  /**
   * The result to send instead of {@code result}, whose complete reply would be
   * {@code replyBytes} long. It must carry the true counts and a way to narrow the request.
   */
  JsonElement replyTooLarge(JsonElement result, long replyBytes, int limit);
}
