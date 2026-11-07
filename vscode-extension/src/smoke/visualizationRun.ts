/** Explicit opt-in fixture runner; all writes go to a fresh fixture and editor profile. */
import { downloadAndUnzipVSCode } from "@vscode/test-electron";
import { spawn } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";
import { runFolder } from "./runFolders";

async function main(): Promise<void> {
  const extension = path.resolve(__dirname, "..", "..");
  const version = process.env.VSCODE_VERSION ?? "1.101.0";
  if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error("Choose an explicit VS Code version for the lifecycle gate");
  const base = path.resolve(extension, "..", "build", "visualization-stage1", version);
  const root = runFolder(base, "run-");
  const fixtureExtension = path.join(root, "extension"), profile = path.join(root, "user-data");
  for (const dir of [fixtureExtension, path.join(profile, "User"), path.join(root, "workspace")]) fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(root, "fixture-only"), "Stage 1 synthetic lifecycle fixture\n");
  // VS Code associates require("vscode") with the extension that owns the calling module.
  // Requiring product output from outside this fixture root creates nullExtensionDescription.
  fs.mkdirSync(path.join(fixtureExtension, "out", "smoke"), { recursive: true });
  fs.cpSync(path.join(extension, "out", "visualization"), path.join(fixtureExtension, "out", "visualization"), { recursive: true });
  for (const name of ["visualizationFixture.js", "visualizationSuite.js", "visualizationFocus.js"]) fs.copyFileSync(path.join(__dirname, name), path.join(fixtureExtension, "out", "smoke", name));
  fs.writeFileSync(path.join(fixtureExtension, "package.json"), JSON.stringify({ name: "lifecycle-fixture", publisher: "wpilog-stage1", version: "0.0.0",
    engines: { vscode: "^1.101.0" }, main: "./out/smoke/visualizationFixture.js", activationEvents: ["onStartupFinished"],
    contributes: { customEditors: [{ viewType: "wpilog-stage1.fixture", displayName: "Visualization lifecycle fixture", selector: [{ filenamePattern: "*.wpiview" }], priority: "default" }] } }));
  fs.writeFileSync(path.join(profile, "User", "settings.json"), JSON.stringify({ "files.hotExit": "onExitAndWindowClose", "window.restoreWindows": "all", "files.autoSaveDelay": 1000,
    "workbench.startupEditor": "none", "security.workspace.trust.enabled": false, "telemetry.telemetryLevel": "off",
    "update.mode": "none", "extensions.autoUpdate": false, "window.dialogStyle": "custom", "files.simpleDialog.enable": true }));
  const macDirectory = path.join(extension, ".vscode-test", `vscode-darwin-arm64-${version}`, "Visual Studio Code.app", "Contents", "MacOS");
  const executable = process.platform === "darwin" ? path.join(macDirectory, fs.existsSync(path.join(macDirectory, "Code")) ? "Code" : "Electron") : await downloadAndUnzipVSCode(version);
  console.log(`Lifecycle fixture: ${root}`);
  // --extensionTestsPath uses in-memory workbench storage and cannot prove hot exit.
  // Run the isolated extension normally; its activation drives the checks and quits normally.
  const launch = (phase: string) => new Promise<void>((resolve, reject) => {
    const env: NodeJS.ProcessEnv = { ...process.env, WPILOG_VISUALIZATION_FIXTURE: root, WPILOG_VISUALIZATION_PHASE: phase,
      WPILOG_VISUALIZATION_AUTORUN: "1" };
    delete env.ELECTRON_RUN_AS_NODE;
    const child = spawn(executable, [path.join(root, "workspace"), "--extensionDevelopmentPath", fixtureExtension,
      "--disable-extensions", "--disable-gpu", "--skip-welcome", "--skip-release-notes", "--disable-workspace-trust",
      "--use-inmemory-secretstorage", "--user-data-dir", profile, "--extensions-dir", path.join(root, "extensions"),
      ...(Number(version.split(".")[1]) >= 141 ? ["--shared-data-dir", path.join(root, "shared-data")] : [])], { env, stdio: "inherit" });
    const timeout = setTimeout(() => { child.kill("SIGTERM"); reject(new Error(`Lifecycle ${phase} timed out`)); }, process.env.WPILOG_VISUALIZATION_DIALOGS === "1" ? 180000 : 90000);
    child.once("error", error => { clearTimeout(timeout); reject(error); });
    child.once("exit", code => { clearTimeout(timeout); code === 0 ? resolve() : reject(new Error(`Lifecycle ${phase} exited ${code}`)); });
  });
  await launch("initial");
  const result = JSON.parse(fs.readFileSync(path.join(root, "result.json"), "utf8"));
  if (!fs.existsSync(path.join(root, "hot-exit.json"))) throw new Error(result.error ?? "No completed lifecycle fixture");
  console.log(JSON.stringify(result));
  await launch("restore");
  const restored = JSON.parse(fs.readFileSync(path.join(root, "restore-result.json"), "utf8"));
  if (restored.error) throw new Error(restored.error);
  console.log(JSON.stringify(restored));
  if (result.error) throw new Error(result.error);
}
void main().catch(error => { console.error(error); process.exitCode = 1; });
