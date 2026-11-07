import { test } from "node:test";
import * as assert from "node:assert/strict";
import { RecordedClient } from "../visualization/recordedClient";
import { McpClient, VisualizationRequest, LocalControlError } from "../mcpClient";

test("native library saves use API 2 and carry no store destination", async () => {
  const calls: { route: string; body: any; options: VisualizationRequest | undefined }[] = [];
  const client = new RecordedClient(async () => ({ visualization: async (route: string, body: unknown, _signal: unknown, options: VisualizationRequest) => {
    calls.push({ route, body, options }); return {};
  } }) as unknown as McpClient);
  client.destinations.set("view", { title: "Server view" });
  await client.save("view", 3);
  assert.deepEqual(calls.map(c => c.route), ["/views/view/attach", "/views/view/save"]);
  assert.equal((calls[1].options as any)?.apiVersion, 2);
  assert.equal(calls[1].body.title, "Server view"); assert.equal(calls[1].body.store_id, undefined);
});

test("library and working references stay on version 2 without changing source admission routes", async () => {
  const calls: { route: string; options: any }[] = [];
  const client = new RecordedClient(async () => ({ visualization: async (route: string, _body: unknown, _signal: unknown, options: unknown) => {
    calls.push({ route, options }); return {};
  } }) as unknown as McpClient);
  for (const route of ["/templates/list", "/saved/open", "/source/views", "/views/view", "/source"]) await client.call(route, {});
  assert.deepEqual(calls.map(c => c.options?.apiVersion ?? 1), [2, 2, 2, 2, 1]);
});

test("lost save responses retry the same reserved intent even after a later local revision", async () => {
  const intents: any[] = []; let fail = true;
  const client = new RecordedClient(async () => ({ visualization: async (route: string, body: unknown) => {
    if (route.endsWith("/save")) { intents.push(structuredClone(body)); if (fail) throw new Error("lost response"); }
    return {};
  } }) as unknown as McpClient);
  client.destinations.set("view", { title: "Original title" });
  await assert.rejects(client.save("view", 1), /lost response/); fail = false;
  client.destinations.set("view", { title: "Later title" }); await client.save("view", 2);
  assert.deepEqual(intents[1], intents[0]);
  await client.save("view", 3); assert.notEqual(intents[2].request_id, intents[0].request_id); assert.equal(intents[2].revision, 3);
});

test("definitive save refusals allow a corrected intent while pending checkpoints retain it", async () => {
  for (const code of ["LIBRARY_LIMIT", "REVISION_CONFLICT", "LIBRARY_CHECKPOINT_PENDING"]) {
    const intents: any[] = []; let fail = true;
    const client = new RecordedClient(async () => ({ visualization: async (route: string, body: unknown) => {
      if (route.endsWith("/save")) { intents.push(structuredClone(body)); if (fail) throw new LocalControlError(code, 409, code, code === "LIBRARY_CHECKPOINT_PENDING"); }
      return {};
    } }) as unknown as McpClient);
    await assert.rejects(client.save("view", 1)); fail = false; await client.save("view", 2);
    if (code === "LIBRARY_CHECKPOINT_PENDING") assert.deepEqual(intents[0], intents[1]);
    else { assert.notEqual(intents[0].request_id, intents[1].request_id); assert.equal(intents[1].revision, 2); }
  }
});

test("an uncertain save stays pinned across a later explicit refusal", async () => {
  const intents: any[] = []; let attempt = 0;
  const client = new RecordedClient(async () => ({ visualization: async (route: string, body: unknown) => {
    if (route.endsWith("/save")) {
      intents.push(structuredClone(body)); attempt++;
      if (attempt === 1) throw new Error("response lost after publication");
      if (attempt === 2) throw new LocalControlError("server temporarily unavailable", 409, "LIBRARY_UNAVAILABLE", false);
    }
    return {};
  } }) as unknown as McpClient);
  await assert.rejects(client.save("view", 1)); await assert.rejects(client.save("view", 2)); await client.save("view", 3);
  assert.deepEqual(intents[1], intents[0]); assert.deepEqual(intents[2], intents[0]);
});
