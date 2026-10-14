/** Normal editor launches and a foreground Java child; all state and logs stay in this fixture. */
import { spawn } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";
import { createServer } from "node:net";
import { downloadAndUnzipVSCode } from "@vscode/test-electron";
async function freePort(): Promise<number> {
  const server = createServer(); await new Promise<void>(r => server.listen(0, "127.0.0.1", r));
  const port = (server.address() as { port: number }).port; await new Promise<void>(r => server.close(() => r())); return port;
}
async function main(): Promise<void> {
  const extension = path.resolve(__dirname, "../.."), repository = path.dirname(extension);
  const version = process.env.VSCODE_VERSION ?? "1.101.0";
  if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error("Use an explicit editor version");
  const base = path.join(repository, "build/visualization-stage2b", version); fs.mkdirSync(base, { recursive: true });
  const root = fs.mkdtempSync(path.join(base, "run-")); console.log("Recorded fixture: " + root);
  for (const folder of ["logs", "home", "workspace", "extension", "profile/User"]) fs.mkdirSync(path.join(root, folder), { recursive: true });
  fs.writeFileSync(path.join(root, "fixture-only"), "Synthetic recorded preview\n");
  for (const name of ["a", "b"]) fs.copyFileSync(path.join(repository, "build/extension-smoke/fixture/smoke.wpilog"), path.join(root, "logs", name + ".wpilog"));
  fs.appendFileSync(path.join(root, "logs/b.wpilog"), fs.readFileSync(path.join(repository, "build/extension-smoke/fixture/append.bin")));
  fs.cpSync(path.join(extension, "out"), path.join(root, "extension/out"), { recursive: true });
  fs.cpSync(path.join(extension, "media"), path.join(root, "extension/media"), { recursive: true });
  const manifest = JSON.parse(fs.readFileSync(path.join(extension, "package.json"), "utf8"));
  fs.writeFileSync(path.join(root, "extension/package.json"), JSON.stringify({ name: "recorded-fixture", publisher: "wpilog-stage2b", version: "0.0.0", engines: { vscode: "^1.101.0" }, main: "out/smoke/recordedFixture.js", activationEvents: ["onStartupFinished"], contributes: { customEditors: manifest.contributes.customEditors.filter((e: any) => e.viewType !== "wpilog-mcp.explorer") } }));
  fs.writeFileSync(path.join(root, "profile/User/settings.json"), JSON.stringify({ "window.restoreWindows": "all", "files.hotExit": "onExitAndWindowClose", "workbench.startupEditor": "none", "security.workspace.trust.enabled": false, "telemetry.telemetryLevel": "off", "update.mode": "none", "extensions.autoUpdate": false, "git.openRepositoryInParentFolders": "never" }));
  const port = await freePort(), debug = await freePort();
  const config = path.join(root, "servers.yaml"); fs.writeFileSync(config, `diskcachedisable: true\nservers:\n  fixture:\n    transport: http\n    port: ${port}\n    logdir: ${JSON.stringify(path.join(root, "logs"))}\n`);
  const jar = path.join(repository, `build/libs/wpilog-mcp-${manifest.version}-all.jar`);
  const mac = path.join(extension, ".vscode-test", `vscode-darwin-arm64-${version}`, "Visual Studio Code.app/Contents/MacOS");
  const executable = process.platform === "darwin" ? path.join(mac, fs.existsSync(path.join(mac, "Code")) ? "Code" : "Electron") : await downloadAndUnzipVSCode(version);
  for (const phase of ["initial", "restore"]) {
    const env = { ...process.env, WPILOG_RECORDED_FIXTURE: root, WPILOG_RECORDED_PHASE: phase, WPILOG_RECORDED_ENDPOINT: `http://127.0.0.1:${port}/mcp`, WPILOG_RECORDED_DEBUG_PORT: String(debug) };
    delete (env as NodeJS.ProcessEnv).ELECTRON_RUN_AS_NODE;
    for (const key of ["TBA_API_KEY", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "WPILOG_DIR"]) delete (env as NodeJS.ProcessEnv)[key];
    const log = fs.openSync(path.join(root, phase + "-server.log"), "w");
    const server = spawn(process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin/java") : "java", ["-Duser.home=" + path.join(root, "home"), "-jar", jar, "run", "fixture", "--config", config], { env, stdio: ["ignore", log, log] });
    try {
      const deadline = Date.now() + 15000;
      while (true) {
        try { if ((await fetch(`http://127.0.0.1:${port}/health`)).ok) break; } catch { /* wait for this child */ }
        if (Date.now() > deadline) throw new Error("Fixture Java server did not start");
        await new Promise(r => setTimeout(r, 50));
      }
      await new Promise<void>((resolve, reject) => {
        const child = spawn(executable, [path.join(root, "workspace"), "--extensionDevelopmentPath", path.join(root, "extension"), "--disable-extensions", "--disable-gpu", "--skip-welcome", "--skip-release-notes", "--disable-workspace-trust", "--use-inmemory-secretstorage", "--user-data-dir", path.join(root, "profile"), "--extensions-dir", path.join(root, "extensions"), `--remote-debugging-port=${debug}`, ...(Number(version.split(".")[1]) >= 141 ? ["--shared-data-dir", path.join(root, "shared")] : [])], { env, stdio: "inherit" });
        const timeout = setTimeout(() => { child.kill(); reject(new Error("Recorded editor fixture timed out")); }, 120000);
        child.once("error", e => { clearTimeout(timeout); reject(e); }); child.once("exit", code => { clearTimeout(timeout); code === 0 ? resolve() : reject(new Error("Editor exited " + code)); });
      });
      const result = JSON.parse(fs.readFileSync(path.join(root, phase + "-result.json"), "utf8")); console.log(JSON.stringify(result)); if (result.error) throw new Error(result.error);
    } finally {
      server.kill(); await new Promise<void>(resolve => { const timeout = setTimeout(() => { server.kill("SIGKILL"); resolve(); }, 5000); server.once("exit", () => { clearTimeout(timeout); resolve(); }); }); fs.closeSync(log);
    }
  }
}
void main().catch(error => { console.error(error); process.exitCode = 1; });
