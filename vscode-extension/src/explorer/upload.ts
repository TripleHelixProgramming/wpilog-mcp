import { HttpHeaders, noHeaders } from "../pitCredential";
/** A selected laptop file is streamed, with a separately computed hash, to the pit importer.
 * Never repeat an uncertain POST: the user can retry and the store recognizes its hash. */
import * as http from "http";
import * as https from "https";
import * as fs from "fs";
import * as path from "path";
import { createHash } from "crypto";
import { Exchange, ImportJob, ImportResult, importEndpointOf, pollImport } from "./importJobs";

export async function uploadLog(mcpUrl: string, file: string, storeId: string,
  report: (job: ImportJob) => void, sleep?: (ms: number) => Promise<void>, headersFor: HttpHeaders = noHeaders): Promise<ImportResult> {
  const endpoint = importEndpointOf(mcpUrl);
  if (!["http:", "https:"].includes(endpoint.protocol) || endpoint.username || endpoint.password) throw new Error("Use an HTTP(S) pit URL without credentials");
  const info = await fs.promises.stat(file);
  if (!info.isFile() || info.size > 2147483647) throw new Error("Upload requires a file within the server's 2 GB reader limit");
  const hash = createHash("sha256");
  for await (const chunk of fs.createReadStream(file)) hash.update(chunk);
  endpoint.searchParams.set("store", storeId); endpoint.searchParams.set("filename", path.basename(file));
  const authorization = await headersFor(endpoint.href);
  const accepted = await new Promise<Exchange>((resolve, reject) => {
    const input = fs.createReadStream(file);
    const request = (endpoint.protocol === "https:" ? https : http).request(endpoint, { method: "POST", timeout: 30_000,
      headers: { ...authorization, "Content-Type": "application/octet-stream", "Content-Length": info.size, "X-WPILOG-SHA256": hash.digest("hex") } }, response => {
      const chunks: Buffer[] = []; let size = 0;
      response.on("data", (chunk: Buffer) => {
        size += chunk.length;
        if (size > 1024 * 1024) request.destroy(new Error("Upload response exceeds 1 MiB")); else chunks.push(chunk);
      });
      response.on("error", reject);
      response.on("end", () => { input.destroy(); resolve({ status: response.statusCode ?? 0, headers: response.headers, body: Buffer.concat(chunks).toString("utf8") }); });
    });
    input.on("error", error => request.destroy(error));
    request.on("timeout", () => request.destroy(new Error("The upload stopped making progress for 30 seconds")));
    request.on("error", error => { input.destroy(); reject(error); });
    input.pipe(request);
  });
  return pollImport(endpoint, accepted, report, sleep, headersFor);
}
