/** Opt-in real editor host. Everything writable, including the standalone install, is disposable. */
import { runTests } from "@vscode/test-electron";
import { execFileSync } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";
import { createServer } from "node:net";

async function unusedPort(): Promise<number> {
  const socket = createServer();
  await new Promise<void>((resolve, reject) => { socket.once("error", reject); socket.listen(0, "127.0.0.1", resolve); });
  const address = socket.address();
  if (!address || typeof address === "string") throw new Error("No loopback port allocated");
  await new Promise<void>((resolve, reject) => socket.close(error => error ? reject(error) : resolve()));
  return address.port;
}

async function main(): Promise<void> {
  if (process.platform !== "linux") throw new Error("The editor smoke runs on Linux under Xvfb. Use the editor-smoke CI jobs; npm test runs on every platform.");
  const extension = path.resolve(__dirname, "..", ".."), root = path.dirname(extension);
  const fixture = path.join(root, "build", "extension-smoke", "fixture", "smoke.wpilog");
  const jar = path.join(extension, "server", "wpilog-mcp-all.jar");
  for (const file of [fixture, jar]) {
    if (!fs.existsSync(file)) throw new Error(`Run ./gradlew bundleExtension extensionSmokeFixture first: missing ${file}`);
  }
  const version = process.env.VSCODE_VERSION ?? "stable";
  if (!/^(stable|\d+\.\d+\.\d+)$/.test(version)) throw new Error("VSCODE_VERSION must be stable or a release number");
  const label = process.env.WPILOG_SMOKE_LABEL ?? "baseline";
  if (!/^[a-z0-9-]+$/.test(label)) throw new Error("Invalid smoke report label");
  const report = path.join(root, "build", "extension-smoke", version, label);
  fs.mkdirSync(report, { recursive: true });
  const scratch = fs.mkdtempSync(path.join(report, "run-"));
  const testHome = path.join(scratch, "home"), install = path.join(testHome, ".wpilog-mcp");
  const workspace = path.join(scratch, "workspace"), userData = path.join(scratch, "user-data");
  const bin = path.join(scratch, "bin"), calls = path.join(scratch, "claude-args.json");
  for (const dir of [testHome, workspace, bin, path.join(userData, "User")]) fs.mkdirSync(dir, { recursive: true });
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin", "java") : "java";
  // Child-only HOME and user.home keep Node's lookup, the launcher and the daemon in agreement.
  // Do not inherit a user's JVM flags, server key, configuration, or extension settings.
  const env = { ...process.env, HOME: testHome, USERPROFILE: testHome,
    JAVA_TOOL_OPTIONS: `-Duser.home="${testHome}"`, JDK_JAVA_OPTIONS: "", _JAVA_OPTIONS: "",
    WPILOG_DISK_CACHE_DIR: path.join(scratch, "cache"),
    PATH: `${bin}${path.delimiter}${process.env.PATH ?? ""}` };
  for (const key of Object.keys(env)) {
    if (key.startsWith("WPILOG_") && key !== "WPILOG_DISK_CACHE_DIR" || key === "TBA_API_KEY") delete (env as NodeJS.ProcessEnv)[key];
  }
  const runJava = (args: string[]) => execFileSync(java, ["-jar", jar, ...args], { env, encoding: "utf8", timeout: 120_000 });
  fs.writeFileSync(path.join(scratch, "install.json"), runJava(["install", "--install-dir", install, "--force", "--json"]));
  const config = path.join(install, "servers.yaml");
  const port = await unusedPort();
  fs.writeFileSync(config, `servers:\n  http:\n    transport: http\n    port: ${port}\n    idle_exit_minutes: 0\n    diskcachedisable: true\n`);
  // Use a PATH executable that records argv, never the user's real Claude configuration.
  fs.writeFileSync(path.join(bin, "claude"), `#!/usr/bin/env node\nrequire('node:fs').writeFileSync(${JSON.stringify(calls)}, JSON.stringify(process.argv.slice(2)));\n`, { mode: 0o755 });
  fs.writeFileSync(path.join(userData, "User", "settings.json"), JSON.stringify({
    "wpilog-mcp.javaPath": java, "wpilog-mcp.maxHeap": "256m", "wpilog-mcp.logDirectory": path.dirname(fixture),
    "wpilog-mcp.enableForClaudeCode": false, "wpilog-mcp.mirror.enabled": false,
    "workbench.startupEditor": "none", "security.workspace.trust.enabled": false,
    "telemetry.telemetryLevel": "off", "update.mode": "none", "extensions.autoUpdate": false,
  }, null, 2));
  try {
    await runTests({ version, extensionDevelopmentPath: extension, extensionTestsPath: path.join(__dirname, "suite.js"),
      extensionTestsEnv: { ...env, WPILOG_SMOKE_FIXTURE: fixture, WPILOG_SMOKE_HOME: testHome,
        WPILOG_SMOKE_CALLS: calls, WPILOG_SMOKE_RESULT: path.join(scratch, "result.json") },
      launchArgs: [workspace, "--disable-extensions", "--disable-gpu", "--skip-welcome", "--skip-release-notes",
        "--disable-workspace-trust", "--user-data-dir", userData, "--extensions-dir", path.join(scratch, "extensions")] });
    const result = JSON.parse(fs.readFileSync(path.join(scratch, "result.json"), "utf8"));
    if (result.checks?.length !== 5 || result.status !== "ok") throw new Error("Editor exited without all five smoke checks");
    fs.writeFileSync(path.join(report, "result.json"), JSON.stringify(result, null, 2) + "\n");
  } finally {
    // start uses its own PID file and the server is shared; closing the editor alone does not stop it.
    try { fs.writeFileSync(path.join(scratch, "stop.log"), runJava(["stop", "http", "--config", config])); }
    catch (error) { console.error("Could not stop the isolated smoke daemon", error); }
    console.log(`Editor smoke evidence: ${scratch}`);
  }
}

void main().catch(error => { console.error(error); process.exitCode = 1; });
