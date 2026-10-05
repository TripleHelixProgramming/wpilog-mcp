import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as http from "node:http";
import { AddressInfo } from "node:net";
import * as path from "node:path";
import { ImportError, ImportJob, ImportResult, completeListing, importEndpointOf, progressText, resultDetails, resultSummary, runImport, sameRobotMessages } from "../explorer/importJobs";
import { plainLog, store } from "./fixtures/organizing";
const request = { store, paths: [plainLog.path], move: true, stated_robot: "Practice" };
const result: ImportResult = { files: [
  { status: "imported", original_path: plainLog.path, path: path.join(store, "one.wpilog"), reason: null },
  { status: "present", original_path: "duplicate", path: path.join(store, "one.wpilog"), reason: "Already held by SHA-256" },
  { status: "unassigned", original_path: "loose", path: path.join(store, "unassigned", "loose"), reason: "No robot assignment" },
  { status: "refused", original_path: "bad", path: null, reason: "Unsupported file content" },
], same_robots: [{ serial_number: "SERIAL", directories: [path.join(store, "robots", "one"), path.join(store, "robots", "two")] }] };

async function endpoint(handler: http.RequestListener, run: (mcp: string) => Promise<void>): Promise<void> {
  const server = http.createServer(handler);
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  try { await run(`http://127.0.0.1:${(server.address() as AddressInfo).port}/mcp?old=1#old`); }
  finally { await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve())); }
}
function json(response: http.ServerResponse, status: number, value: unknown, headers: http.OutgoingHttpHeaders = {}) {
  response.writeHead(status, { "Content-Type": "application/json", ...headers });
  response.end(JSON.stringify(value));
}

test("202 is followed through queued and running to done, every 500 ms, with complete progress and refusals", async () => {
  const requests: { method?: string; url?: string; body: string }[] = [];
  const jobs: ImportJob[] = [
    { job_id: "job-1", state: "queued", progress: null },
    { job_id: "job-1", state: "running", progress: { phase: "verifying", path: plainLog.path, completed: 1, total: 4 } },
    { job_id: "job-1", state: "done", progress: { phase: "complete", path: store, completed: 4, total: 4 }, result },
  ];
  let poll = 0;
  await endpoint((req, res) => {
    let body = "";
    req.on("data", chunk => body += chunk);
    req.on("end", () => {
      requests.push({ method: req.method, url: req.url, body });
      if (req.method === "POST") json(res, 202, { job_id: "job-1", url: "/store/import/job-1" });
      else json(res, 200, jobs[poll++]);
    });
  }, async mcp => {
    const reported: string[] = [], sleeps: number[] = [];
    assert.deepEqual(await runImport(mcp, request, job => reported.push(progressText(job)), false, async ms => { sleeps.push(ms); }), result);
    assert.deepEqual(sleeps, [500, 500]);
    assert.deepEqual(reported, ["queued", `verifying: ${plainLog.path} (1/4)`, `complete: ${store} (4/4)`]);
  });
  assert.deepEqual(requests.map(r => [r.method, r.url]), [["POST", "/store/import"], ["GET", "/store/import/job-1"], ["GET", "/store/import/job-1"], ["GET", "/store/import/job-1"]]);
  assert.deepEqual(JSON.parse(requests[0].body), request);
  assert.equal(resultSummary(result), "1 imported, 1 present, 1 unassigned, 1 refused");
  assert.equal(resultDetails(result)[3], "refused: bad\nUnsupported file content");
  assert.deepEqual(sameRobotMessages(result), [`Robot serial SERIAL appears in ${result.same_robots[0].directories.join(" and ")}. These directories remain separate.`]);
});

test("a 403 preserves the server's error and inbox hint without retrying", async () => {
  let calls = 0;
  await endpoint((req, res) => { calls++; json(res, 403, { error: "Import paths must be inside configured log directories", hint: "Copy outside files into the store's inbox/ folder" }); }, async mcp => {
    await assert.rejects(runImport(mcp, request, () => {}, false, async () => assert.fail("403 must not retry")), error => {
      assert.ok(error instanceof ImportError);
      assert.equal(error.status, 403);
      assert.equal(error.message, "Import paths must be inside configured log directories\nCopy outside files into the store's inbox/ folder");
      return true;
    });
  });
  assert.equal(calls, 1);
});

test("503 honors Retry-After for admission and polling; assignment posts to its own endpoint", async () => {
  let posts = 0, gets = 0;
  const urls: string[] = [];
  await endpoint((req, res) => {
    urls.push(req.url!);
    if (req.method === "POST" && posts++ === 0 || req.method === "GET" && gets++ === 0) {
      json(res, 503, { error: "Import job queue is full", hint: "Wait for a running job to finish, then retry" }, { "Retry-After": "3" });
    } else if (req.method === "POST") json(res, 202, { job_id: "assign", url: "/store/import/assign" });
    else json(res, 200, { job_id: "assign", state: "done", progress: null, result });
  }, async mcp => {
    const sleeps: number[] = [];
    assert.deepEqual(await runImport(mcp, request, () => {}, true, async ms => { sleeps.push(ms); }), result);
    assert.deepEqual(sleeps, [3000, 3000]);
    assert.equal(importEndpointOf(mcp).search, "");
    assert.equal(importEndpointOf(mcp).hash, "");
  });
  assert.deepEqual(urls, ["/store/assign", "/store/assign", "/store/import/assign", "/store/import/assign"]);
});

test("persistent busy, failed jobs, invalid job URLs and poll refusals stop with an explanation", async () => {
  let calls = 0;
  await endpoint((req, res) => { calls++; json(res, 503, { error: "busy", hint: "try later" }, { "Retry-After": "0" }); }, async mcp => {
    await assert.rejects(runImport(mcp, request, () => {}, false, async () => {}), { message: "busy\ntry later" });
  });
  assert.equal(calls, 4);
  for (const scenario of ["failed", "bad_url", "poll_refused", "invalid_state"] as const) {
    await endpoint((req, res) => {
      if (req.method === "POST") json(res, 202, { job_id: "a", url: scenario === "bad_url" ? "http://other.invalid/store/import/a" : "/store/import/a" });
      else if (scenario === "poll_refused") json(res, 404, { error: "Unknown import job", hint: "Jobs expire from memory and are lost at restart" });
      else json(res, 200, { job_id: "a", state: scenario === "failed" ? "failed" : "invalid", progress: null, error: "Store lock is held" });
    }, async mcp => {
      const expected = { failed: "Store lock is held", bad_url: "The server returned an invalid import job URL",
        poll_refused: "Unknown import job\nJobs expire from memory and are lost at restart", invalid_state: "The server returned an invalid import job" };
      await assert.rejects(runImport(mcp, request, () => {}), { message: expected[scenario] });
    });
  }
});

test("offers collect every listing page and refuse stalled pagination or a later error", async () => {
  const calls: Record<string, unknown>[] = [];
  const combined = await completeListing(async args => {
    calls.push(args);
    return args.offset === 0 ? { logs: [plainLog], log_count: 2, offset: 0, returned: 1, has_more: true }
      : { logs: [{ ...plainLog, path: "second" }], offset: 1, returned: 1, has_more: false };
  });
  assert.deepEqual(calls, [{ limit: 500, offset: 0 }, { limit: 500, offset: 1 }]);
  assert.deepEqual(combined.logs?.map(l => l.path), [plainLog.path, "second"]);
  assert.equal(combined.has_more, false);
  await assert.rejects(completeListing(async () => ({ logs: [], has_more: true })), /did not advance/);
  const error = { status: "error", error: "unmounted" };
  let page = 0;
  assert.deepEqual(await completeListing(async () => page++ === 0
    ? { logs: [plainLog], returned: 1, has_more: true } : error), error);
});
