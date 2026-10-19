/** Launch only existing editor binaries, with isolated profiles and synthetic server children. */
import { spawn, ChildProcessWithoutNullStreams } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";
import { createInterface } from "node:readline";
import { createServer } from "node:net";
async function main() {
  const extension = path.resolve(__dirname, "../.."), repo = path.dirname(extension), version = process.env.VSCODE_VERSION!;
  if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error("Explicit VSCODE_VERSION required");
  const base = process.env.WPILOG_CONNECTION_OUTPUT!;
  if (!base || !base.startsWith("/private/tmp/")) throw new Error("Isolated /private/tmp output required");
  fs.mkdirSync(base, { recursive: true }); const root = fs.mkdtempSync(path.join(base, "native-"));
  console.log("M3 native fixture: " + root);
  const children: ChildProcessWithoutNullStreams[] = [];
  const env: NodeJS.ProcessEnv = { ...process.env, WPILOG_CONNECTION_FIXTURE: root };
  for (const key of ["ELECTRON_RUN_AS_NODE", "TBA_API_KEY", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "WPILOG_DIR"]) delete env[key];
  try {
    const definitions = [];
    for (const id of ["a", "b"]) {
      const serverRoot = path.join(root, "server-" + id); fs.mkdirSync(serverRoot);
      const child = spawn(process.env.WPILOG_FOLLOW_JAVA ?? "java", ["-Xmx256m", `-Duser.home=${serverRoot}`, "-cp", fs.readFileSync(path.join(repo, "build/extension-smoke/classpath.txt"), "utf8"),
        "org.triplehelix.wpilogmcp.fixtures.FollowBrokerFixture", path.join(serverRoot, "store"), "direct"], { env: { ...env, WPILOG_DISK_CACHE_DIR: path.join(serverRoot, "cache") } });
      children.push(child); child.stderr.pipe(fs.createWriteStream(path.join(serverRoot, "server.log")));
      const lines = createInterface({ input: child.stdout }), replies: any[] = []; lines.on("line", line => { if (line.startsWith("BROKER ")) replies.push(JSON.parse(line.slice(7))); });
      async function next() { for (let n = 0; n < 1500; n++) { if (replies.length) return replies.shift(); await new Promise(r => setTimeout(r, 10)); } throw new Error("Fixture server did not reply"); }
      const ready = await next(); child.stdin.write('{"op":"finalize"}\n'); await next();
      definitions.push({ id, label: "Remote " + id.toUpperCase(), kind: "remote", url: `http://127.0.0.1:${ready.port}/mcp` });
    }
    definitions.push({ id: "offline", label: "Offline", kind: "remote", url: "http://127.0.0.1:1/mcp" });
    for (const folder of ["workspace", "extension", "profile/User"]) fs.mkdirSync(path.join(root, folder), { recursive: true });
    fs.writeFileSync(path.join(root, "fixture-only"), "Synthetic remote-only M3 fixture\n");
    for (const folder of ["out", "media", "images"]) fs.cpSync(path.join(extension, folder), path.join(root, "extension", folder), { recursive: true });
    const manifest = JSON.parse(fs.readFileSync(path.join(extension, "package.json"), "utf8"));
    fs.writeFileSync(path.join(root, "extension/package.json"), JSON.stringify({ ...manifest, name: "connections-fixture", publisher: "wpilog-fixture", main: "./out/smoke/connectionsFixture.js", activationEvents: ["onStartupFinished"] }));
    fs.writeFileSync(path.join(root, "profile/User/settings.json"), JSON.stringify({ "wpilog-mcp.connections": definitions, "workbench.startupEditor": "none", "security.workspace.trust.enabled": false,
      "telemetry.telemetryLevel": "off", "update.mode": "none", "extensions.autoUpdate": false, "git.openRepositoryInParentFolders": "never", "files.hotExit": "off" }));
    const port = createServer(); await new Promise<void>(r => port.listen(0, "127.0.0.1", r)); const debug = (port.address() as any).port; await new Promise<void>(r => port.close(() => r()));
    const mac = path.join(extension, ".vscode-test", `vscode-darwin-arm64-${version}`, "Visual Studio Code.app/Contents/MacOS");
    const executable = path.join(mac, fs.existsSync(path.join(mac, "Code")) ? "Code" : "Electron");
    if (!fs.existsSync(executable)) throw new Error("An existing editor binary is required; this fixture never downloads or installs one");
    await new Promise<void>((resolve, reject) => {
      const child = spawn(executable, [path.join(root, "workspace"), "--extensionDevelopmentPath", path.join(root, "extension"), "--disable-extensions", "--disable-gpu", "--skip-welcome", "--skip-release-notes", "--disable-workspace-trust", "--use-inmemory-secretstorage", "--user-data-dir", path.join(root, "profile"), "--extensions-dir", path.join(root, "extensions"), `--remote-debugging-port=${debug}`, ...(Number(version.split(".")[1]) >= 141 ? ["--shared-data-dir", path.join(root, "shared")] : [])], { env: { ...env, WPILOG_RECORDED_DEBUG_PORT: String(debug) }, stdio: "inherit" });
      const timeout = setTimeout(() => { child.kill(); reject(new Error("Native connection fixture timed out")); }, 150000);
      child.once("error", error => { clearTimeout(timeout); reject(error); }); child.once("exit", code => { clearTimeout(timeout); code === 0 ? resolve() : reject(new Error("Editor exit " + code)); });
    });
    const result = JSON.parse(fs.readFileSync(path.join(root, "result.json"), "utf8")); console.log(JSON.stringify(result, null, 2)); if (result.error) throw new Error(result.error);
  } finally { for (const child of children) { child.stdin.end(); child.kill(); } }
}
void main().catch(error => { console.error(error); process.exitCode = 1; });
