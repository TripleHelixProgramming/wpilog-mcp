/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * A {@link BoundedReply} limit counts the complete JSON-RPC reply as the transports encode it, in
 * UTF-8 bytes with nulls kept and HTML characters escaped, and an oversized reply becomes the
 * tool's refusal rather than a shortened one.
 */
class BoundedReplyTest {
  /** A tool whose result and limit the test sets; it records what the handler measured. */
  static final class Fixed implements ToolRegistry.Tool, BoundedReply {
    final JsonObject result;
    int limit;
    long measured = -1;
    int seenLimit = -1;

    Fixed(JsonObject result, int limit) {
      this.result = result;
      this.limit = limit;
    }

    @Override public String name() { return "fixed_reply"; }
    @Override public String description() { return "A fixed result with a reply limit"; }
    @Override public JsonObject inputSchema() { return new ToolRegistry.SchemaBuilder().build(); }
    @Override public JsonElement execute(JsonObject arguments) { return result.deepCopy(); }
    @Override public int maxReplyBytes() { return limit; }

    @Override public JsonElement replyTooLarge(JsonElement result, long replyBytes, int limit) {
      measured = replyBytes;
      seenLimit = limit;
      var refusal = new JsonObject();
      refusal.addProperty("success", false);
      refusal.addProperty("status", "error");
      refusal.addProperty("error", "too large");
      refusal.addProperty("code", "RESPONSE_TOO_LARGE");
      refusal.addProperty("reply_bytes", replyBytes);
      return refusal;
    }
  }

  @Test void aReplyOfExactlyTheLimitIsSentAndOneByteMoreIsRefused() {
    var handler = new McpMessageHandler(new ToolRegistry());
    var content = new JsonObject();
    // e-acute is two UTF-8 bytes and the robot face four (two UTF-16 chars); '<' is escaped.
    content.addProperty("t", "é🤖<\"q\"");
    content.add("n", JsonNull.INSTANCE);
    var response = JsonRpc.createResponse(new JsonPrimitive(7), content);
    // Written out by hand as the transports send it: 68 bytes, 65 UTF-16 characters (the robot
    // face is two) and 64 code points, so a limit in characters would count it short.
    String sent = "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"t\":\"é🤖\\u003c\\\"q\\\"\",\"n\":null}}";
    int bytes = sent.getBytes(StandardCharsets.UTF_8).length;
    assertEquals(68, bytes);
    assertEquals(65, sent.length());
    assertEquals(64, sent.codePointCount(0, sent.length()));

    var tool = new Fixed(new JsonObject(), bytes);
    assertSame(response, handler.bounded(response, tool, content, new JsonPrimitive(7), 5),
        "A reply of exactly the limit is sent unchanged");
    assertEquals(-1, tool.measured, "No refusal is built for a reply that fits");

    tool.limit = bytes - 1;
    var refused = handler.bounded(response, tool, content, new JsonPrimitive(7), 5);
    assertEquals(bytes, tool.measured, "The handler measured the encoded reply in UTF-8 bytes");
    assertEquals(bytes - 1, tool.seenLimit);
    assertEquals(7, refused.get("id").getAsInt(), "The refusal answers the same request");
    var result = text(refused);
    assertEquals("RESPONSE_TOO_LARGE", result.get("code").getAsString());
    assertEquals(5, result.get("_execution_time_ms").getAsLong());
  }

  @Test void onlyABoundedToolsReplyIsReplacedThroughTheHandler() throws Exception {
    var large = new JsonObject();
    large.addProperty("success", true);
    large.addProperty("payload", "x".repeat(4096));
    var bounded = new Fixed(large, 1 << 20);
    var registry = new ToolRegistry();
    registry.registerTool(bounded);
    registry.registerTool(new ToolRegistry.Tool() {
      @Override public String name() { return "unbounded_reply"; }
      @Override public String description() { return "A large result without a reply limit"; }
      @Override public JsonObject inputSchema() { return new ToolRegistry.SchemaBuilder().build(); }
      @Override public JsonElement execute(JsonObject arguments) { return large.deepCopy(); }
    });
    var handler = new McpMessageHandler(registry);

    var fits = text(handler.handleMessage(call("fixed_reply")).response());
    assertEquals(4096, fits.get("payload").getAsString().length(), "Under its limit the reply is whole");

    bounded.limit = 1024;
    var reply = handler.handleMessage(call("fixed_reply")).response();
    assertEquals(9, reply.get("id").getAsInt());
    var refused = text(reply);
    assertEquals("RESPONSE_TOO_LARGE", refused.get("code").getAsString(), refused.toString());
    assertFalse(refused.has("payload"), "Nothing of the oversized result is sent");
    assertTrue(bounded.measured > 4096, "The measured reply holds the whole 4096-character payload");
    assertTrue(refused.has("_execution_time_ms"));

    var plain = text(handler.handleMessage(call("unbounded_reply")).response());
    assertEquals(4096, plain.get("payload").getAsString().length(), "A tool without a limit is untouched");
  }

  private static JsonObject call(String tool) {
    return JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\""
        + tool + "\",\"arguments\":{}}}").getAsJsonObject();
  }

  private static JsonObject text(JsonObject response) {
    var block = response.getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject();
    return JsonParser.parseString(block.get("text").getAsString()).getAsJsonObject();
  }
}
