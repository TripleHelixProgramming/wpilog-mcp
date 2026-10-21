/** Normal editor launches and a foreground Java child; all state and logs stay in this fixture. */
import { spawn } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";
import { createServer } from "node:net";
import { downloadAndUnzipVSCode } from "@vscode/test-electron";
import { runFolder } from "./runFolders";
async function freePort(): Promise<number> {
  const server = createServer(); await new Promise<void>(r => server.listen(0, "127.0.0.1", r));
  const port = (server.address() as { port: number }).port; await new Promise<void>(r => server.close(() => r())); return port;
}
async function main(): Promise<void> {
  const extension = path.resolve(__dirname, "../.."), repository = path.dirname(extension);
  const panelsOnly = process.env.WPILOG_PANEL_FIXTURE === "true";
  const overviewOnly = process.env.WPILOG_OVERVIEW_FIXTURE === "true";
  const version = process.env.VSCODE_VERSION ?? "1.101.0";
  if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error("Use an explicit editor version");
  const output = process.env.WPILOG_RECORDED_OUTPUT;
  const base = output ?? path.join(repository, panelsOnly ? "build/visualization-stage3-panels" : overviewOnly ? "build/visualization-stage3-overview" : "build/visualization-stage2b", version);
  const root = runFolder(base, "run-", { keepEarlier: output !== undefined }); console.log("Recorded fixture: " + root);
  for (const folder of ["logs", "home", "workspace", "extension", "profile/User"]) fs.mkdirSync(path.join(root, folder), { recursive: true });
  fs.writeFileSync(path.join(root, "fixture-only"), "Synthetic recorded preview\n");
  for (const name of ["a", "b"]) fs.copyFileSync(path.join(repository, "build/extension-smoke/fixture/smoke.wpilog"), path.join(root, "logs", name + ".wpilog"));
  fs.appendFileSync(path.join(root, "logs/b.wpilog"), fs.readFileSync(path.join(repository, "build/extension-smoke/fixture/append.bin")));
  fs.cpSync(path.join(extension, "out"), path.join(root, "extension/out"), { recursive: true });
  fs.cpSync(path.join(extension, "media"), path.join(root, "extension/media"), { recursive: true });
  const manifest = JSON.parse(fs.readFileSync(path.join(extension, "package.json"), "utf8"));
  fs.writeFileSync(path.join(root, "extension/package.json"), JSON.stringify({ name: "recorded-fixture", publisher: "wpilog-stage2b", version: "0.0.0", engines: { vscode: "^1.101.0" }, main: panelsOnly ? "out/smoke/recordingFixture.js" : overviewOnly ? "out/smoke/overviewFixture.js" : "out/smoke/recordedFixture.js", activationEvents: ["onStartupFinished"], contributes: { configuration: manifest.contributes.configuration, customEditors: manifest.contributes.customEditors.filter((e: any) => e.viewType !== "wpilog-mcp.explorer") } }));
  fs.writeFileSync(path.join(root, "profile/User/settings.json"), JSON.stringify({ ...(process.env.WPILOG_DECODED_MIB ? { "wpilog-mcp.visualization.decodedBufferMiB": Number(process.env.WPILOG_DECODED_MIB) } : {}), "window.restoreWindows": "all", "files.hotExit": "onExitAndWindowClose", "workbench.startupEditor": "none", "security.workspace.trust.enabled": false, "telemetry.telemetryLevel": "off", "update.mode": "none", "extensions.autoUpdate": false, "git.openRepositoryInParentFolders": "never" }));
  const port = await freePort(), debug = await freePort();
  const config = path.join(root, "servers.yaml"); fs.writeFileSync(config, `diskcachedisable: true\nservers:\n  fixture:\n    transport: http\n    port: ${port}\n    logdir: ${JSON.stringify(path.join(root, "logs"))}\n`);
  const jar = path.join(repository, `build/libs/wpilog-mcp-${manifest.version}-all.jar`);
  const mac = path.join(extension, ".vscode-test", `vscode-darwin-arm64-${version}`, "Visual Studio Code.app/Contents/MacOS");
  const executable = process.platform === "darwin" ? path.join(mac, fs.existsSync(path.join(mac, "Code")) ? "Code" : "Electron") : await downloadAndUnzipVSCode(version);
  for (const phase of (overviewOnly || panelsOnly ? ["initial"] : ["initial", "restore"])) {
    const env = { ...process.env, WPILOG_DISK_CACHE_DIR: path.join(root, "cache"), WPILOG_RECORDED_FIXTURE: root, WPILOG_RECORDED_PHASE: phase, WPILOG_RECORDED_ENDPOINT: `http://127.0.0.1:${port}/mcp`, WPILOG_RECORDED_DEBUG_PORT: String(debug) };
    delete (env as NodeJS.ProcessEnv).ELECTRON_RUN_AS_NODE;
    for (const key of ["TBA_API_KEY", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "WPILOG_DIR"]) delete (env as NodeJS.ProcessEnv)[key];
    const log = fs.openSync(path.join(root, phase + "-server.log"), "w");
    const serverArgs = overviewOnly || panelsOnly ? ["-Duser.home=" + path.join(root, "home"), "-cp", fs.readFileSync(path.join(repository, "build/extension-smoke/classpath.txt"), "utf8"), "org.triplehelix.wpilogmcp.fixtures.OverviewServerFixture", path.join(root, "logs/recording-store"), String(port), ...(panelsOnly ? [path.join(root, "command-result.json")] : [])]
      : ["-Duser.home=" + path.join(root, "home"), "-jar", jar, "run", "fixture", "--config", config];
    const launch = (restore = false) => spawn(process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin/java") : "java", restore ? [...serverArgs, "restore"] : serverArgs, { env, stdio: ["pipe", log, log] });
    let server = launch();
    let commandId: string | undefined;
    const commandTimer = panelsOnly ? setInterval(() => {
      try {
        const command = JSON.parse(fs.readFileSync(path.join(root, "command.json"), "utf8"));
        if (command.id !== commandId) {
          commandId = command.id;
          if (command.op === "processRestart") void (async () => {
            const previous = server;
            await new Promise<void>((resolve, reject) => {
              const timeout = setTimeout(() => { previous.kill("SIGKILL"); reject(new Error("Fixture process did not stop")); }, 10000);
              previous.once("exit", () => { clearTimeout(timeout); resolve(); }); previous.stdin?.end();
            });
            server = launch(true);
            const deadline = Date.now() + 15000;
            while (Date.now() < deadline) {
              try { if ((await fetch(`http://127.0.0.1:${port}/health`)).ok) {
                fs.writeFileSync(path.join(root, "command-result.json"), JSON.stringify({ id: command.id, previous_pid: previous.pid, pid: server.pid })); return;
              } } catch { /* Fresh process starting. */ }
              await new Promise(r => setTimeout(r, 50));
            }
            throw new Error("Restored fixture process did not answer health");
          })().catch(error => fs.writeFileSync(path.join(root, "command-result.json"), JSON.stringify({ id: command.id, error: String(error) })));
          else server.stdin?.write(JSON.stringify(command) + "\n");
        }
      } catch { /* fixture command not yet published */ }
    }, 25) : undefined;
    try {
      const deadline = Date.now() + 15000;
      while (true) {
        try { if ((await fetch(`http://127.0.0.1:${port}/health`)).ok) break; } catch { /* wait for this child */ }
        if (Date.now() > deadline) throw new Error("Fixture Java server did not start");
        await new Promise(r => setTimeout(r, 50));
      }
      await new Promise<void>((resolve, reject) => {
        const child = spawn(executable, [path.join(root, "workspace"), "--extensionDevelopmentPath", path.join(root, "extension"), "--disable-extensions", "--disable-gpu", "--skip-welcome", "--skip-release-notes", "--disable-workspace-trust", "--use-inmemory-secretstorage", "--user-data-dir", path.join(root, "profile"), "--extensions-dir", path.join(root, "extensions"), `--remote-debugging-port=${debug}`, ...(Number(version.split(".")[1]) >= 141 ? ["--shared-data-dir", path.join(root, "shared")] : [])], { env, stdio: "inherit" });
        const timeout = setTimeout(() => { child.kill(); reject(new Error("Recorded editor fixture timed out")); }, overviewOnly ? 430000 : 120000);
        child.once("error", e => { clearTimeout(timeout); reject(e); }); child.once("exit", code => { clearTimeout(timeout); code === 0 ? resolve() : reject(new Error("Editor exited " + code)); });
      });
      const result = JSON.parse(fs.readFileSync(path.join(root, phase + "-result.json"), "utf8")); console.log(JSON.stringify(result)); if (result.error) throw new Error(result.error);
    } finally {
      clearInterval(commandTimer); server.stdin?.end(); server.kill(); await new Promise<void>(resolve => { const timeout = setTimeout(() => { server.kill("SIGKILL"); resolve(); }, 5000); server.once("exit", () => { clearTimeout(timeout); resolve(); }); }); fs.closeSync(log);
    }
  }
}
void main().catch(error => { console.error(error); process.exitCode = 1; });
